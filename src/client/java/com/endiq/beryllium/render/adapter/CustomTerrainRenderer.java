package com.endiq.beryllium.render.adapter;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.render.snapshot.MeshBuildResult;
import com.endiq.beryllium.render.snapshot.MeshRevision;
import com.endiq.beryllium.render.snapshot.SectionSnapshot;
import com.endiq.beryllium.util.BerylliumLog;

import java.util.concurrent.atomic.AtomicLong;

/**
 * The client end of Beryllium's own terrain renderer: it owns the adapter, feeds snapshots to
 * the native engine, and validates what comes back.
 *
 * <h2>This does not render anything yet, and that is the correct state for it to be in</h2>
 *
 * <p>What exists: the snapshot contract, implemented and tested on the engine side in C and
 * transcribed on this side; the extraction layer that turns a Minecraft section into one; the
 * revision tracking that makes a stale mesh detectable; and the atomic switch that decides who
 * owns the terrain.
 *
 * <p>What does not: a mesher that translates real Minecraft baked models rather than the
 * engine's own thirty-block registry, an Android GLES 3.0 backend that uses the launcher's
 * existing context instead of assuming a desktop windowing library, a JNI layer, and anything
 * that uploads a buffer or issues a draw call. {@link BerylliumNative#isReady()} is therefore
 * false on every install, {@link RendererMode#tryEnableCustom()} cannot succeed, and this
 * class never reaches the point of drawing.
 *
 * <p>Shipping the switch already able to flip would mean shipping a renderer that draws
 * nothing where the world used to be. The seam is here so the remaining work can be added
 * without the rest of the mod learning about it.
 *
 * <h2>The discipline this class exists to enforce</h2>
 *
 * <p>Three rules, each of which is a real failure mode rather than a stylistic preference:
 * <ul>
 *   <li>A worker never sees a live world object. It gets a {@link SectionSnapshot}, which
 *       holds copied arrays and no reference back to the level, the chunk or the section.</li>
 *   <li>A result is validated before it is used. {@link #acceptResult} compares the revision
 *       the mesh was built from against the revision now, and rejects on any difference —
 *       world, section or resource pack.</li>
 *   <li>Only one renderer draws the terrain. Everything here is gated on
 *       {@link RendererMode#isCustom()}, and reaching that mode requires that no other mod
 *       owns the terrain and that Beryllium's vanilla-facing chunk systems stand down.</li>
 * </ul>
 */
public final class CustomTerrainRenderer {
	private static final CustomTerrainRenderer INSTANCE = new CustomTerrainRenderer();

	private final AtomicLong snapshotsCaptured = new AtomicLong();
	private final AtomicLong snapshotsSubmitted = new AtomicLong();
	private final AtomicLong resultsAccepted = new AtomicLong();
	private final AtomicLong resultsRejectedStale = new AtomicLong();
	private final AtomicLong resultsFailed = new AtomicLong();

	private volatile MinecraftTerrainAdapter adapter;
	private volatile boolean statusLogged;

	private CustomTerrainRenderer() {
	}

	public static CustomTerrainRenderer instance() {
		return INSTANCE;
	}

	/**
	 * Selects the adapter for this release, once.
	 *
	 * <p>Safe to call repeatedly; only the first call does work. Called during client startup
	 * so the decision and its reasoning appear in the log next to the rest of the
	 * compatibility report rather than in the middle of a frame.
	 */
	public synchronized MinecraftTerrainAdapter adapter() {
		MinecraftTerrainAdapter current = adapter;
		if (current != null) {
			return current;
		}
		current = MinecraftTerrainAdapter.select();
		adapter = current;
		if (!statusLogged) {
			statusLogged = true;
			BerylliumLog.info("[BERYLLIUM] terrain adapter: " + current.describes()
				+ (current.isAvailable() ? " (available)" : " (unavailable)"));
		}
		return current;
	}

	/**
	 * Whether this renderer is drawing the terrain.
	 *
	 * <p>Read by the frame hook and by anything that would otherwise schedule vanilla chunk
	 * work, so the two can never both be active for the same section.
	 */
	public boolean isActive() {
		return RendererMode.isCustom() && BerylliumNative.isReady();
	}

	/**
	 * Captures one section and hands it to the engine.
	 *
	 * <p>Runs on the render thread, because capturing is the part that has to read the world.
	 * Everything after the hand-off is the worker's.
	 *
	 * @return true if the engine accepted the snapshot for building
	 */
	public boolean submitSection(int sectionX, int sectionY, int sectionZ) {
		if (!isActive()) {
			return false;
		}
		try {
			MinecraftTerrainAdapter terrainAdapter = adapter();
			if (!terrainAdapter.isAvailable()) {
				return false;
			}
			SectionSnapshot snapshot = terrainAdapter.captureSection(sectionX, sectionY, sectionZ);
			if (snapshot == null) {
				return false;
			}
			snapshotsCaptured.incrementAndGet();
			if (!BerylliumNative.submitSnapshot(snapshot)) {
				return false;
			}
			snapshotsSubmitted.incrementAndGet();
			return true;
		} catch (Throwable t) {
			// A failure here leaves the section dirty, so it is retried. It must never
			// propagate into the frame.
			BerylliumLog.debug("[BERYLLIUM] snapshot submission failed: " + t);
			return false;
		}
	}

	/**
	 * Validates a finished mesh against the inputs as they are now.
	 *
	 * <p>This is the render thread's half of the contract. The worker built something from a
	 * snapshot; the world may have moved since. Accepting a stale mesh is not a performance
	 * problem, it is geometry that does not match what the player is standing in, and there is
	 * no error anywhere to point at. Rejecting costs one wasted build and the section is
	 * rebuilt from current inputs.
	 *
	 * @param result  what the worker produced
	 * @param section the section it was for
	 * @return true if the mesh may be uploaded and drawn
	 */
	public boolean acceptResult(MeshBuildResult result, int sectionX, int sectionY, int sectionZ) {
		if (result == null || !isActive()) {
			return false;
		}
		MeshRevision now = adapter().currentRevision(sectionX, sectionY, sectionZ);
		MeshBuildResult.Status status = result.status(now);
		switch (status) {
			case ACCEPTED:
			case EMPTY:
				resultsAccepted.incrementAndGet();
				return true;
			case FAILED:
				resultsFailed.incrementAndGet();
				return false;
			case STALE_WORLD:
			case STALE_SECTION:
			case STALE_PACK:
			default:
				resultsRejectedStale.incrementAndGet();
				return false;
		}
	}

	/**
	 * Called when the level changes. Everything held belongs to the world that just went away,
	 * including the revision counters that make staleness detectable.
	 */
	public void onWorldChanged() {
		SectionRevisionClock.instance().clear();
		BerylliumNative.shutdown();
	}

	/** Stands the renderer down for the session and returns the terrain to vanilla. */
	public void retire(String reason) {
		RendererMode.retireCustomRenderer(reason);
		BerylliumNative.shutdown();
	}

	public long snapshotsCaptured() {
		return snapshotsCaptured.get();
	}

	public long snapshotsSubmitted() {
		return snapshotsSubmitted.get();
	}

	public long resultsAccepted() {
		return resultsAccepted.get();
	}

	/** Meshes discarded because the world, section or resource pack moved during the build. */
	public long resultsRejectedStale() {
		return resultsRejectedStale.get();
	}

	public long resultsFailed() {
		return resultsFailed.get();
	}
}
