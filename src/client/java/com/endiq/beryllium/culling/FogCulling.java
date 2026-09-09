package com.endiq.beryllium.culling;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.config.BerylliumConfig;
import net.minecraft.client.Minecraft;

import java.lang.reflect.Field;

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
	 * Reads the client's render distance option without naming any version-specific
	 * accessor: {@code Minecraft.options} (a stable public field across the covered
	 * range) carries an option object whose {@code renderDistance} field is the
	 * {@code OptionInstance}; that instance stores its current value in whichever
	 * instance field currently holds an {@link Integer}. Walking fields by name and
	 * type — the same reflective-capture style as {@code MobileTuner} and
	 * {@code BlockEntityCulling} — keeps this compiling and working across the
	 * option-system refactors; any miss returns -1 (cull disabled, never a crash).
	 */
	private static int readRenderDistanceChunks() {
		try {
			Minecraft minecraft = Minecraft.getInstance();
			if (minecraft == null || minecraft.options == null) {
				return -1;
			}
			Object options = minecraft.options;
			for (Class<?> c = options.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
				Field optionField;
				try {
					optionField = c.getDeclaredField("renderDistance");
				} catch (NoSuchFieldException e) {
					continue;
				}
				optionField.setAccessible(true);
				Object instance = optionField.get(options);
				Integer chunks = findIntegerValue(instance);
				if (chunks != null && chunks > 0) {
					return chunks;
				}
			}
			return -1;
		} catch (Throwable t) {
			// Unreadable options must never break a frame — cull nothing.
			return -1;
		}
	}

	private static Integer findIntegerValue(Object instance) {
		if (instance == null) {
			return null;
		}
		// The current value of an OptionInstance is its "value" field (Mojang's
		// option-system shape across the covered range). Prefer that exact name:
		// callback objects may hold OTHER Integers (min/max bounds), so a generic
		// "any Integer field" scan could pick a bound and cull at the wrong plane.
		try {
			for (Class<?> c = instance.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
				Field valueField;
				try {
					valueField = c.getDeclaredField("value");
				} catch (NoSuchFieldException e) {
					continue;
				}
				if (java.lang.reflect.Modifier.isStatic(valueField.getModifiers())) {
					continue;
				}
				valueField.setAccessible(true);
				Object value = valueField.get(instance);
				if (value instanceof Integer integer) {
					return integer;
				}
			}
		} catch (Throwable ignored) {
			// fall through to "unknown"
		}
		// Unknown layout: disable the cull rather than risk a wrong plane.
		return null;
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
