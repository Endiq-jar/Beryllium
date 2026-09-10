package com.endiq.beryllium.culling;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.config.BerylliumConfig;
import com.endiq.beryllium.tune.ClientOptions;

/**
 * Phase 12 — "fog-wall" culling: skip content that sits deep inside the
 * distance-fog band, where vanilla would still pay a full render call for
 * something the player essentially cannot see.
 *
 * <p>Vanilla frustum-culls against the whole view distance, and entity
 * {@code shouldRender} only asks "is it inside the frustum" — so the outermost
 * fringe of the render distance (where distance fog has almost fully replaced
 * the image with the fog colour) still submits entity models, block-entity
 * render calls and name tags every frame. That fringe is exactly where fog
 * already hides geometry: by the time fog is (nearly) complete, a model a few
 * pixels tall is unreadable, and its per-frame CPU/GPU cost is pure waste.
 *
 * <p>This class draws a conservative line inside that fringe. It is deliberately
 * <em>not</em> tied to a guessed fog-renderer field — it derives the fog wall
 * from the client's own render-distance option (the same quantity vanilla's
 * distance fog is built from), so there is no version-sensitive internals
 * access and nothing here can crash on any Minecraft version the renderer
 * profile targets. The factor is configurable ({@code fogCullFactor}); the
 * default keeps the line at 90% of the render distance, inside the band where
 * fog is dense enough that hidden content is unreadable either way. A factor of
 * {@code >= 2.0} disables the cull entirely (it then lies beyond any reachable
 * render distance).
 *
 * <p>All entry points are defensive: any state that cannot be read (no client,
 * no options, an exception) disables the cull for that call. Counters are
 * session totals, read by the debug overlay; they are touched only from the
 * render thread (the mixins that call this run there).
 */
public final class FogCulling {
	private FogCulling() {
	}

	private static final long CACHE_TTL_MS = 1000;

	/** Render distance (chunks) last read from options; -1 = unknown/disabled. */
	private static volatile int cachedRenderDistanceChunks = -1;
	private static volatile long cacheFilledAtMs = 0;

	/** Session totals, incremented right before a render call is cancelled. */
	private static long hiddenEntities;
	private static long hiddenBlockEntities;
	private static long hiddenNameTags;

	public static long hiddenEntities() {
		return hiddenEntities;
	}

	public static long hiddenBlockEntities() {
		return hiddenBlockEntities;
	}

	public static long hiddenNameTags() {
		return hiddenNameTags;
	}

	public static void noteHiddenEntity() {
		hiddenEntities++;
	}

	public static void noteHiddenBlockEntity() {
		hiddenBlockEntities++;
	}

	public static void noteHiddenNameTag() {
		hiddenNameTags++;
	}

	/**
	 * The far cull plane in blocks, or {@code <= 0} when fog culling is
	 * disabled/unavailable. Derived from the client render distance (chunks)
	 * times 16 blocks per chunk times {@code fogCullFactor}; re-read from the
	 * options object at most once per second, so changing the render distance
	 * in-game is picked up within a second.
	 */
	public static double fogCullFarBlocks() {
		BerylliumConfig config = Beryllium.config();
		if (config == null || !config.enabled || !config.cullFogHiddenContent) {
			return -1.0;
		}
		double factor = config.fogCullFactor;
		if (!(factor > 0.0) || factor >= 2.0) {
			// <= 0 or >= 2: disabled by configuration.
			return -1.0;
		}

		int chunks = cachedRenderDistanceChunks;
		long now = System.currentTimeMillis();
		if (chunks <= 0 || now - cacheFilledAtMs > CACHE_TTL_MS) {
			chunks = readRenderDistanceChunks();
			if (chunks <= 0) {
				return -1.0;
			}
			cachedRenderDistanceChunks = chunks;
			cacheFilledAtMs = now;
		}
		return chunks * 16.0 * factor;
	}

	/**
	 * Reads the client's render distance option through the shared reflective options
	 * layer ({@link ClientOptions}). Any miss returns -1, which disables the cull for
	 * that call — never a wrong plane, never a crash.
	 */
	private static int readRenderDistanceChunks() {
		try {
			Integer chunks = ClientOptions.readInt("renderDistance");
			if (chunks != null && chunks > 0) {
				return chunks;
			}
		} catch (Throwable ignored) {
			// unresolvable -> cull disabled
		}
		return -1;
	}

	/**
	 * @return true when the target point lies beyond the fog-wall plane and
	 *         farther than {@code safeRadius} from the camera. The safe radius
	 *         is a hard never-cull zone: anything close enough to be a large
	 *         on-screen object is never skipped no matter how foggy.
	 */
	public static boolean isBeyondFogWall(
		double camX, double camY, double camZ,
		double targetX, double targetY, double targetZ,
		double safeRadius
	) {
		double farBlocks = fogCullFarBlocks();
		if (farBlocks <= 0.0) {
			return false;
		}
		double dx = targetX - camX;
		double dy = targetY - camY;
		double dz = targetZ - camZ;
		double distanceSq = dx * dx + dy * dy + dz * dz;

		double safe = safeRadius;
		if (!(safe > 0.0)) {
			safe = 0.0;
		}
		if (distanceSq <= safe * safe) {
			return false;
		}
		return distanceSq > farBlocks * farBlocks;
	}
}
