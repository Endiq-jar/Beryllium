package com.endiq.beryllium.chunk;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.config.BerylliumConfig;
import com.endiq.beryllium.mixin.chunk.ViewAreaMixin;
import com.endiq.beryllium.util.BerylliumLog;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientWorldEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

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
	private long drainedTotal = 0;
	private int maxPendingSeen = 0;
	private long lastTelemetryLogNanos = 0;

	public static ChunkRebuildManager instance() {
		return instance;
	}

	public static void setInstance(ChunkRebuildManager manager) {
		instance = manager;
	}

	public void register() {
		WorldRenderEvents.START.register(context -> {
			Vec3 camPos = context.camera().getPosition();
			Vector3f look = context.camera().getLookVector();
			onFrameStart(camPos.x, camPos.y, camPos.z, look.x(), look.y(), look.z());
		});
		// Fires whenever the client world instance changes (including leaving to the
		// menu) — the point at which queued section keys for the old world go stale.
		ClientWorldEvents.AFTER_CLIENT_WORLD_CHANGE.register((client, level) -> onWorldUnload());
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

	/** Per-rendered-frame drain: re-trigger the highest-priority requests via vanilla. */
	public void onFrameStart(
		double camX, double camY, double camZ,
		double forwardX, double forwardY, double forwardZ
	) {
		BerylliumConfig config = Beryllium.config();
		if (config == null || !config.enabled || !config.chunkRebuildPrioritization) {
			return;
		}
		if (queue.size() == 0 || rendererRef == null) {
			return;
		}

		int budget = Math.max(1, config.chunkRebuildsPerFrame);
		List<ChunkRebuildRequest> next = queue.drain(budget, camX, camY, camZ, forwardX, forwardY, forwardZ);
		for (ChunkRebuildRequest request : next) {
			bypassing = true;
			try {
				if (ViewAreaMixin.beryllium$rescheduleDirty(rendererRef, request.key(), request.urgent())) {
					drainedTotal++;
				} else {
					// Re-trigger failed (vanilla method not reachable) — the request is
					// already out of the queue. The section will be re-dirtied by vanilla
					// on the next relevant event; log once so it is diagnosable.
					BerylliumLog.warn("[BERYLLIUM-CHUNK] Re-trigger failed for section key "
						+ request.key() + "; dropping (vanilla will re-mark it on its next pass).");
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
			+ ", drained=" + drainedTotal + ", bypassing=" + bypassing);
	}
}
