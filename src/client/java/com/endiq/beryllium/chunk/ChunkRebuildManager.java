package com.endiq.beryllium.chunk;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.config.BerylliumConfig;
import com.endiq.beryllium.util.BerylliumLog;

import java.util.List;

/**
 * Phase 4 — wires {@link ChunkRebuildQueue} into the vanilla 1.21.4 section pipeline.
 *
 * <p>Vanilla rebuilds chunk sections in the order their dirty marks arrive; during a
 * mining run, caving session or redstone flood that order is essentially random relative
 * to the camera. This manager instead: (1) intercepts the two dirty-marking entry points
 * ({@code LevelRenderer.setSectionDirty} and its 1.21.4 delegate
 * {@code ViewArea.setDirty}) and parks the sections in {@link ChunkRebuildQueue}, then
 * (2) on every rendered frame drains a small prioritized batch (proximity + view
 * alignment + urgency, see {@link ChunkRebuildPriority}) and re-triggers each one
 * through vanilla's own dirty-marking path, which schedules the actual (asynchronous)
 * rebuild.
 *
 * <p>Safety properties:
 * <ul>
 *   <li>The queue has a hard cap ({@code chunkRebuildQueueLimit}). Past it, interception
 *       is suspended and vanilla schedules directly — staleness is provably bounded.</li>
 *   <li>Only the *order* of rebuilds changes; the rebuild work itself is always executed
 *       by vanilla's own machinery, never by Beryllium.</li>
 *   <li>The re-trigger call is guarded by a bypass flag so it never re-enters the queue
 *       (infinite loop protection).</li>
 *   <li>Nothing is ever intercepted unless {@link SectionDirtyBridge} can prove the mark can
 *       be handed back to vanilla on this release, and a drained request that still fails to
 *       re-trigger goes back into the queue instead of being dropped. Losing an update is
 *       worse than not reordering it.</li>
 *   <li>The queue is cleared on level unload so stale sections from a previous world are
 *       never re-triggered into the next one.</li>
 *   <li>Deferred automatically when Sodium is present (it owns the mesh pipeline and
 *       already orders its rebuilds).</li>
 * </ul>
 *
 * <p>The mixin targets were verified against a 1.21.4 Mojang-mapped decompile
 * ({@code LevelRenderer.setSectionDirty(int,int,int)} / {@code (int,int,int,boolean)}
 * and {@code ViewArea.setDirty(int,int,int,boolean)}; the pre-1.21.2
 * {@code SectionRenderDispatcher.setSectionDirty(long,boolean)} does not exist in
 * 1.21.4). The re-trigger bridge is reflection-based so a future signature drift
 * degrades to vanilla behavior instead of crashing. All methods here are called from
 * the render thread only (mixins and {@code WorldRenderEvents.START}).
 */
public final class ChunkRebuildManager {
	private static volatile ChunkRebuildManager instance;

	private final ChunkRebuildQueue queue = new ChunkRebuildQueue();

	/** The renderer object to re-trigger parked sections through (a
	 *  {@code ViewArea} or {@code LevelRenderer}, whichever mixin fired first);
	 *  captured on first interception; null until a world is actually rendering. */
	private volatile Object rendererRef;

	/** True while the per-frame drain is re-triggering a request through vanilla's
	 *  setSectionDirty — mixins check this and let the call through instead of re-queueing. */
	private boolean bypassing = false;

	private boolean saturationLogged = false;
	private boolean requeueLogged = false;
	private long drainedTotal = 0;
	private long requeueTotal = 0;
	private int maxPendingSeen = 0;
	private long lastTelemetryLogNanos = 0;

	public static ChunkRebuildManager instance() {
		return instance;
	}

	public static void setInstance(ChunkRebuildManager manager) {
		instance = manager;
	}

	/** Kept for callers that just want the camera-driven drain with no frame timing. */
	public void onFrameStart(
		double camX, double camY, double camZ,
		double forwardX, double forwardY, double forwardZ
	) {
		onFrameStart(camX, camY, camZ, forwardX, forwardY, forwardZ, -1L, -1L);
	}

	/**
	 * Called by the dirty-mark mixins when vanilla wants a section rebuilt.
	 *
	 * @return true if the request was accepted into the priority queue (the mixin should
	 *         cancel vanilla's immediate scheduling); false if the queue is saturated or
	 *         the feature is off (vanilla proceeds directly).
	 */
	public boolean enqueue(long sectionPos, boolean important) {
		if (bypassing) {
			return false;
		}
		BerylliumConfig config = Beryllium.config();
		if (config == null || !config.enabled || !config.chunkRebuildPrioritization) {
			return false;
		}
		if (Beryllium.isChunkOptimizationDeferredToOtherMod()) {
			return false;
		}
		if (!SectionDirtyBridge.canReschedule(rendererRef)) {
			// A queue that accepts a dirty mark and then cannot hand it back has *lost* that
			// update: the section would stay stale until something else dirtied it again.
			// Refusing to intercept is the only safe answer, and vanilla then schedules the
			// rebuild itself. SectionDirtyBridge logs the reason once.
			return false;
		}

		int limit = Math.max(16, config.chunkRebuildQueueLimit);
		if (queue.size() >= limit) {
			if (!saturationLogged) {
				BerylliumLog.warn("[BERYLLIUM-CHUNK] Rebuild queue saturated at " + limit
					+ " sections — suspending prioritization; vanilla schedules directly until the queue drains.");
				saturationLogged = true;
			}
			return false;
		}
		if (saturationLogged && queue.size() < limit - 32) {
			BerylliumLog.info("[BERYLLIUM-CHUNK] Rebuild queue drained below " + (limit - 32)
				+ " — resuming prioritized scheduling.");
			saturationLogged = false;
		}

		queue.submitOrUpdate(new ChunkRebuildRequest(
			sectionPos,
			SectionPacking.centerX(sectionPos),
			SectionPacking.centerY(sectionPos),
			SectionPacking.centerZ(sectionPos),
			important
		));
		maxPendingSeen = Math.max(maxPendingSeen, queue.size());
		return true;
	}

	/** Captured from the {@code LevelRendererMixin}/{@code ViewAreaMixin} on first
	 *  interception; used by the per-frame drain as the re-trigger target. */
	public void setRendererRef(Object renderer) {
		if (rendererRef != renderer) {
			rendererRef = renderer;
			BerylliumLog.debug("[BERYLLIUM-CHUNK] Attached to renderer@"
				+ System.identityHashCode(renderer) + " (" + renderer.getClass().getSimpleName() + ")");
		}
	}

	public boolean isBypassing() {
		return bypassing;
	}

	/**
	 * Per-rendered-frame drain: re-trigger the highest-priority requests via vanilla.
	 *
	 * <p>How many are started in one frame is decided by
	 * {@link ChunkCompilationScheduler}, which tightens the allowance when the previous
	 * frame was already expensive — the queue keeps making progress, it just refuses to
	 * make a slow frame worse.
	 *
	 * @param lastFrameNanos    duration of the frame that just finished, or a non-positive
	 *                          value when it has not been measured
	 * @param averageFrameNanos this device's rolling average frame time, or a non-positive
	 *                          value when it has not been measured yet
	 */
	public void onFrameStart(
		double camX, double camY, double camZ,
		double forwardX, double forwardY, double forwardZ,
		long lastFrameNanos, long averageFrameNanos
	) {
		BerylliumConfig config = Beryllium.config();
		if (config == null || !config.enabled || !config.chunkRebuildPrioritization) {
			return;
		}
		if (queue.size() == 0 || rendererRef == null) {
			return;
		}

		int budget = ChunkCompilationScheduler.rebuildsThisFrame(
			config.chunkRebuildsPerFrame, lastFrameNanos, averageFrameNanos);
		List<ChunkRebuildRequest> next = queue.drain(budget, camX, camY, camZ, forwardX, forwardY, forwardZ);
		for (ChunkRebuildRequest request : next) {
			bypassing = true;
			try {
				if (SectionDirtyBridge.rescheduleDirty(rendererRef, request.key(), request.urgent())) {
					drainedTotal++;
				} else {
					// The re-trigger could not be performed. Putting the request back is what
					// keeps this feature from ever *losing* an update: a dropped request is a
					// section that stays stale until vanilla happens to dirty it again. The
					// queue's own cap still bounds this — if the bridge is permanently broken,
					// the queue fills, interception suspends and vanilla takes over.
					queue.submitOrUpdate(request);
					requeueTotal++;
					if (!requeueLogged) {
						requeueLogged = true;
						BerylliumLog.warn("[BERYLLIUM-CHUNK] Re-trigger failed for section key "
							+ request.key() + "; re-queued rather than dropped. Prioritization "
							+ "suspends itself if this keeps happening.");
					}
				}
			} finally {
				bypassing = false;
			}
		}
	}

	public void onWorldUnload() {
		int cleared = queue.size();
		queue.clear();
		rendererRef = null;
		BerylliumLog.debug("[BERYLLIUM-CHUNK] World unloaded — cleared " + cleared + " queued rebuilds.");
	}

	public int pendingCount() {
		return queue.size();
	}

	public long drainedTotal() {
		return drainedTotal;
	}

	/** How many drained requests had to be put back because the re-trigger did not go through. */
	public long requeueTotal() {
		return requeueTotal;
	}

	public int maxPendingSeen() {
		return maxPendingSeen;
	}

	/** Rate-limited telemetry task for the frame-budget scheduler (debug mode only). */
	public void logTelemetryIfDue() {
		BerylliumConfig config = Beryllium.config();
		if (config == null || !config.debugMode) {
			return;
		}
		long now = System.nanoTime();
		if (now - lastTelemetryLogNanos < 5_000_000_000L) {
			return;
		}
		lastTelemetryLogNanos = now;
		BerylliumLog.debug("[BERYLLIUM-CHUNK] queue=" + queue.size() + " pending, max=" + maxPendingSeen
			+ ", drained=" + drainedTotal + ", requeued=" + requeueTotal + ", bypassing=" + bypassing);
	}
}
