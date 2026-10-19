package com.endiq.beryllium.throttle;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.chunk.ChunkRebuildManager;
import com.endiq.beryllium.chunk.ChunkUploadPacer;
import com.endiq.beryllium.config.BerylliumConfig;
import com.endiq.beryllium.performance.FrameBudget;
import com.endiq.beryllium.performance.FrameHealth;

/**
 * Dynamic Load Throttling: watches how hard the world is being loaded, and temporarily
 * scales entity rendering back while terrain generation needs the frame.
 *
 * <p>It is a monitor, not a scheduler. It reads the state the chunk governors already
 * maintain — the rebuild backlog, the upload deferrals, the frame health they were judged
 * against — and publishes one number: how much of its configured range entity rendering
 * currently gets. It never touches a queue, never re-prioritises a section, and never
 * decides when vanilla or Beryllium's own chunk systems may run. Two systems scheduling the
 * same work is how a section gets built twice or not at all, so this one only observes.
 *
 * <h2>What counts as pressure</h2>
 *
 * <ul>
 *   <li><strong>Rebuild backlog.</strong> Sections waiting to be re-meshed, measured against
 *       the configured queue limit rather than against an absolute number: a limit of 128
 *       and a backlog of 100 is a different situation on a device that set the limit to 32.</li>
 *   <li><strong>Deferred uploads.</strong> The upload pacer counts every drain it stopped
 *       early. That counter climbing means geometry is finished but could not be put on the
 *       GPU, which is the clearest signal that the frame has no room left.</li>
 *   <li><strong>Frame health.</strong> The same {@link FrameBudget} verdict the chunk
 *       governors use, so this throttle and the pacing cannot disagree about whether the
 *       device is struggling.</li>
 *   <li><strong>Teleport.</strong> A camera that moves further in one frame than it could
 *       by travelling. Nothing else produces that: entering a world, changing dimension, an
 *       ender pearl, a {@code /tp}. Each one means the entire visible area is about to be
 *       generated from nothing, which is the worst case for terrain and the easiest case to
 *       give up entity rendering for.</li>
 * </ul>
 *
 * <h2>Why it does not flap</h2>
 *
 * <p>Pressure rises immediately and falls one level at a time, over several frames, and a
 * surge is held for a minimum number of frames after it is first seen. Without that, a
 * teleport would alternate between "still loading" and "finished" as the backlog drained in
 * bursts, and the entity draw distance would visibly pulse. Hysteresis costs a little
 * extra throttling at the tail of a load and buys a stable picture throughout it.
 *
 * <p>Everything here fails towards {@link LoadPressure#CALM}. A monitor that cannot read
 * its inputs must not conclude the world is busy, because the consequence of being wrong is
 * entities the player should see disappearing.
 */
public final class LoadPressureMonitor {
	private static final LoadPressureMonitor INSTANCE = new LoadPressureMonitor();

	/**
	 * A camera jump larger than this in one frame is a teleport, not travel.
	 *
	 * <p>At 20 blocks per frame even a 1200 FPS client would have to be moving at 24,000
	 * blocks per second, and elytra plus speed effects top out far below that. Anything
	 * past this threshold arrived somewhere rather than travelling there.
	 */
	private static final double TELEPORT_BLOCKS = 20.0;

	/** Frames a surge is held for after it is first seen, so a load cannot flap. */
	private static final int SURGE_HOLD_FRAMES = 40;

	/** Frames of calm required before pressure drops one level. */
	private static final int EASE_FRAMES = 8;

	/**
	 * The floor on entity draw distance while throttled, in blocks.
	 *
	 * <p>The scale multiplies a configured distance, and a small configured distance scaled
	 * by 0.3 could put the ceiling inside the player's own view — entities a few blocks away
	 * blinking out. Entities are never throttled closer than this.
	 */
	private static final double MIN_ENTITY_DRAW_DISTANCE = 16.0;

	private volatile LoadPressure pressure = LoadPressure.CALM;

	private double lastCamX = Double.NaN;
	private double lastCamY = Double.NaN;
	private double lastCamZ = Double.NaN;

	private long lastDeferredTotal = -1L;
	private int surgeHoldRemaining = 0;
	private int calmFrames = 0;
	private long teleportsSeen = 0;

	private LoadPressureMonitor() {
	}

	public static LoadPressureMonitor instance() {
		return INSTANCE;
	}

	/**
	 * Called once per frame from the client frame hook, before rendering.
	 *
	 * @param camX              camera X this frame
	 * @param camY              camera Y this frame
	 * @param camZ              camera Z this frame
	 * @param lastFrameNanos    duration of the frame that just finished, or non-positive if unknown
	 * @param averageFrameNanos rolling average frame time, or non-positive if unknown
	 */
	public void onFrameStart(double camX, double camY, double camZ,
	                         long lastFrameNanos, long averageFrameNanos) {
		if (!enabled()) {
			pressure = LoadPressure.CALM;
			return;
		}
		try {
			LoadPressure observed = observe(camX, camY, camZ, lastFrameNanos, averageFrameNanos);
			apply(observed);
		} catch (Throwable t) {
			// A monitor that cannot measure must not guess that the world is busy.
			pressure = LoadPressure.CALM;
		}
	}

	private LoadPressure observe(double camX, double camY, double camZ,
	                             long lastFrameNanos, long averageFrameNanos) {
		BerylliumConfig config = Beryllium.config();
		LoadPressure observed = LoadPressure.CALM;

		// --- teleport -------------------------------------------------------------
		if (!Double.isNaN(lastCamX)) {
			double dx = camX - lastCamX;
			double dy = camY - lastCamY;
			double dz = camZ - lastCamZ;
			if (dx * dx + dy * dy + dz * dz > TELEPORT_BLOCKS * TELEPORT_BLOCKS) {
				teleportsSeen++;
				surgeHoldRemaining = SURGE_HOLD_FRAMES;
			}
		}
		lastCamX = camX;
		lastCamY = camY;
		lastCamZ = camZ;

		if (surgeHoldRemaining > 0) {
			surgeHoldRemaining--;
			observed = LoadPressure.worse(observed, LoadPressure.SURGED);
		}

		// --- rebuild backlog ------------------------------------------------------
		ChunkRebuildManager rebuilds = ChunkRebuildManager.instance();
		if (rebuilds != null) {
			int pending = rebuilds.pendingCount();
			int limit = config == null ? 128 : Math.max(1, config.chunkRebuildQueueLimit);
			// Measured against the configured limit, so a client that asks for a small queue
			// is not treated as permanently saturated by its own choice.
			if (pending * 100 >= limit * 85) {
				observed = LoadPressure.worse(observed, LoadPressure.LOADED);
			} else if (pending * 100 >= limit * 50) {
				observed = LoadPressure.worse(observed, LoadPressure.BUSY);
			}
		}

		// --- deferred uploads -----------------------------------------------------
		ChunkUploadPacer pacer = ChunkUploadPacer.instance();
		if (pacer != null) {
			long deferred = pacer.deferredTotal();
			if (lastDeferredTotal >= 0L && deferred > lastDeferredTotal) {
				// Geometry finished but could not be uploaded: the frame is full.
				observed = LoadPressure.worse(observed, LoadPressure.LOADED);
			}
			lastDeferredTotal = deferred;
		}

		// --- frame health ---------------------------------------------------------
		FrameHealth health = FrameBudget.healthOf(lastFrameNanos, averageFrameNanos);
		if (health == FrameHealth.IN_TROUBLE) {
			observed = LoadPressure.worse(observed, LoadPressure.LOADED);
		} else if (health == FrameHealth.OVERRUNNING) {
			observed = LoadPressure.worse(observed, LoadPressure.BUSY);
		}

		return observed;
	}

	/** Rises immediately, falls one level at a time. */
	private void apply(LoadPressure observed) {
		LoadPressure current = pressure;
		if (observed.ordinal() >= current.ordinal()) {
			pressure = observed;
			calmFrames = 0;
			return;
		}
		// A surge still being held keeps its level; observe() already reported it, so
		// reaching here with a lower value means the hold has genuinely expired.
		calmFrames++;
		if (calmFrames >= EASE_FRAMES) {
			calmFrames = 0;
			pressure = current.ease();
		}
	}

	/**
	 * Scales a configured entity cull distance by the current pressure.
	 *
	 * <p>Called from the entity render dispatcher for every entity vanilla is about to draw,
	 * so it does no allocation and reads one volatile. A non-positive configured distance
	 * means "no limit" and is passed through untouched, because inventing a limit where the
	 * player asked for none would change behaviour with the feature switched off.
	 *
	 * @param configuredDistance the distance the player configured
	 * @return the distance to apply this frame
	 */
	public double scaledEntityCullDistance(double configuredDistance) {
		if (!(configuredDistance > 0.0)) {
			return configuredDistance;
		}
		LoadPressure current = pressure;
		if (current == LoadPressure.CALM) {
			return configuredDistance;
		}
		double scaled = configuredDistance * current.distanceScale();
		// Never throttle so hard that entities in the player's face disappear.
		return Math.max(scaled, Math.min(MIN_ENTITY_DRAW_DISTANCE, configuredDistance));
	}

	/**
	 * Scales the distance at which behind-camera culling becomes aggressive.
	 *
	 * <p>Under load this is <em>raised</em> rather than lowered, which is the opposite
	 * direction to the draw-distance cap: culling more aggressively behind the camera removes
	 * work, so a device that is struggling should start removing it sooner.
	 */
	public double scaledAggressiveDistance(double configuredDistance) {
		if (!(configuredDistance > 0.0)) {
			return configuredDistance;
		}
		LoadPressure current = pressure;
		if (current == LoadPressure.CALM) {
			return configuredDistance;
		}
		// A larger aggressive distance means the graded dot threshold reaches its far,
		// stricter end sooner. Capped so it cannot swallow the whole draw distance.
		double scaled = configuredDistance * (2.0 - current.distanceScale());
		return Math.min(scaled, configuredDistance * 2.0);
	}

	public LoadPressure pressure() {
		return pressure;
	}

	public long teleportsSeen() {
		return teleportsSeen;
	}

	/** Drops every measurement. Called when the level changes. */
	public void reset() {
		pressure = LoadPressure.CALM;
		lastCamX = Double.NaN;
		lastCamY = Double.NaN;
		lastCamZ = Double.NaN;
		lastDeferredTotal = -1L;
		surgeHoldRemaining = 0;
		calmFrames = 0;
	}

	private static boolean enabled() {
		BerylliumConfig config = Beryllium.config();
		return config != null && config.enabled && config.dynamicLoadThrottling;
	}
}
