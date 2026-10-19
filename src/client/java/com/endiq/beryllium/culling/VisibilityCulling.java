package com.endiq.beryllium.culling;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.config.BerylliumConfig;
import com.endiq.beryllium.culling.async.AsyncCullingPipeline;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Visibility culling: the one place Beryllium asks "can the player actually see this?".
 *
 * <p>Vanilla re-derives that answer constantly, and repeatedly for the same geometry
 * within a single frame — a sign, a beacon beam and a chest all ask, in different places,
 * whether the same block is on screen. Two things here make that cheaper and more
 * consistent:
 * <ul>
 *   <li><strong>One frustum.</strong> The live culling frustum is the one
 *       {@code LevelRenderer} already built for this frame. It is located reflectively
 *       (walking by type rather than by mapped field name), so it keeps working across
 *       mapping churn.</li>
 *   <li><strong>One answer per block per frame.</strong> Every positive/negative result is
 *       memoised for the duration of the frame. Geometry that is not visible is the
 *       expensive case — it is what makes a storage room full of chests cost real frame
 *       time — and it is exactly the case where repeated processing is pure waste.</li>
 * </ul>
 *
 * <p>Everything is defensive by design: no camera, no frustum, or any error means "do not
 * cull". A culling bug that removes geometry the player should see is far worse than a
 * missed optimisation.
 */
public final class VisibilityCulling {
	/** Inflation is quantised to 1/64 of a block, which is far finer than any caller uses. */
	private static final double INFLATION_STEP = 1.0 / 64.0;

	/** Inflations past this are not memoised at all rather than rounded into a wrong bucket. */
	private static final double MAX_MEMOISED_INFLATION = 4.0;
	private static final int INFLATION_BUCKETS = (int) (MAX_MEMOISED_INFLATION / INFLATION_STEP) + 1;

	private static volatile Field[] frustumFields;
	private static volatile boolean frustumFieldsResolved;

	/**
	 * One memo per inflation, because the answer depends on both the block and how far the
	 * test box was grown around it. A single map keyed on the block position alone lets the
	 * first caller's inflation answer every later caller's question, which is how a sign at
	 * 0.75 blocks of slack would end up deciding whether a tighter box is on screen.
	 */
	@SuppressWarnings("unchecked")
	private static final Map<Long, Boolean>[] blockVisibilityCache = new Map[INFLATION_BUCKETS];

	static {
		for (int i = 0; i < INFLATION_BUCKETS; i++) {
			blockVisibilityCache[i] = new HashMap<>(256);
		}
	}

	private static long cacheHits = 0;
	private static long cacheMisses = 0;
	private static long asyncHits = 0;
	private static int largestMemoThisFrame = 0;

	private VisibilityCulling() {
	}

	/**
	 * Resets the per-frame memo. Called once per rendered frame, and again when the level
	 * changes.
	 *
	 * <p>A per-frame memo has exactly one correct lifetime: the frame. Nothing here survives
	 * it, whatever it grew to — a stale "visible" answer is a piece of geometry drawn that
	 * should not have been, and a stale "invisible" one is a hole in the world.
	 */
	public static void onFrameStart() {
		int largest = 0;
		for (Map<Long, Boolean> memo : blockVisibilityCache) {
			largest = Math.max(largest, memo.size());
			memo.clear();
		}
		largestMemoThisFrame = largest;
		// The asynchronous pipeline keeps its answers across frames — that is the whole
		// point of it — but it does need to know a new frame started, so it can refuse to
		// hand back an answer measured against the previous camera.
		AsyncCullingPipeline.instance().beginFrame();
	}

	/**
	 * @return true if the block at {@code pos} is inside the camera frustum (or the test
	 *         could not be performed, in which case it is treated as visible).
	 */
	public static boolean isBlockVisible(BlockPos pos, double inflation) {
		if (pos == null) {
			return true;
		}
		double slack = Math.max(0.0, inflation);

		// Resolve the frustum first. Besides being needed for the test anyway, this is what
		// publishes the frame's snapshot to the asynchronous pipeline, and the snapshot has
		// to exist before its cache can be consulted.
		Frustum frustum = currentFrustum();
		if (frustum == null) {
			return true;
		}

		int bucket = cacheEnabled() ? bucketOf(slack) : -1;
		if (bucket < 0) {
			// Memoisation off, or an inflation too large to represent exactly: answer the
			// question directly every time.
			return isVisible(frustum, boxAt(pos, slack));
		}

		long key = pos.asLong();
		AsyncCullingPipeline async = AsyncCullingPipeline.instance();

		// First the concurrent cache, which the worker filled with answers already measured
		// against this frame's frustum. A hit here is the cheap case: a map lookup and no
		// intersection math on the render thread at all.
		Boolean carried = async.cachedVisibility(key, bucket);
		if (carried != null) {
			cacheHits++;
			asyncHits++;
			return carried;
		}

		// Then this frame's own memo, for a box already asked about since the frame began.
		Map<Long, Boolean> memo = blockVisibilityCache[bucket];
		Boolean cached = memo.get(key);
		if (cached != null) {
			cacheHits++;
			return cached;
		}
		cacheMisses++;

		AABB box = boxAt(pos, slack);
		boolean visible = isVisible(frustum, box);
		memo.put(key, visible);
		// And remember the bounds, not just the answer: that is what lets the worker
		// re-derive next frame's result without ever touching a Minecraft object.
		async.record(key, bucket, visible, box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
		return visible;
	}

	/** Frustum test against an already-resolved frustum. Never culls on an error. */
	private static boolean isVisible(Frustum frustum, AABB box) {
		if (box == null) {
			return true;
		}
		try {
			return frustum.isVisible(box);
		} catch (Throwable t) {
			return true;
		}
	}

	private static AABB boxAt(BlockPos pos, double inflation) {
		return new AABB(
			pos.getX() - inflation,
			pos.getY() - inflation,
			pos.getZ() - inflation,
			pos.getX() + 1.0 + inflation,
			pos.getY() + 1.0 + inflation,
			pos.getZ() + 1.0 + inflation
		);
	}

	/** @return the memo bucket for an inflation, or {@code -1} when it must not be memoised. */
	private static int bucketOf(double inflation) {
		if (inflation > MAX_MEMOISED_INFLATION) {
			return -1;
		}
		return (int) Math.min(INFLATION_BUCKETS - 1, Math.round(inflation / INFLATION_STEP));
	}

	/** Frustum test for an arbitrary box. Never culls when the frustum is unavailable. */
	public static boolean isBoxVisible(AABB box) {
		Frustum frustum = currentFrustum();
		if (frustum == null || box == null) {
			return true;
		}
		try {
			return frustum.isVisible(box);
		} catch (Throwable t) {
			return true;
		}
	}

	/** Vertical column test, used for beacon beams (which extend far above their block). */
	public static boolean isColumnVisible(BlockPos pos, double radius, double topY) {
		if (pos == null) {
			return true;
		}
		return isBoxVisible(new AABB(
			pos.getX() + 0.5 - radius,
			pos.getY(),
			pos.getZ() + 0.5 - radius,
			pos.getX() + 0.5 + radius,
			topY,
			pos.getZ() + 0.5 + radius
		));
	}

	/**
	 * @return squared distance from the camera to a block centre, or {@code -1} when it
	 *         cannot be determined.
	 */
	public static double distanceSqToCamera(BlockPos pos) {
		Vec3 camera = cameraPosition();
		if (camera == null || pos == null) {
			return -1.0;
		}
		double dx = (pos.getX() + 0.5) - camera.x;
		double dy = (pos.getY() + 0.5) - camera.y;
		double dz = (pos.getZ() + 0.5) - camera.z;
		return dx * dx + dy * dy + dz * dz;
	}

	public static Vec3 cameraPosition() {
		try {
			Minecraft minecraft = Minecraft.getInstance();
			if (minecraft == null || minecraft.gameRenderer == null) {
				return null;
			}
			Camera camera = CameraAccess.current();
			return camera == null ? null : CameraAccess.position(camera);
		} catch (Throwable t) {
			return null;
		}
	}

	/** @return the world's build height, used to size beam columns. */
	public static double ceilingOf(Level level, BlockPos pos) {
		if (level != null) {
			int height = buildHeightOf(level);
			if (height > 0) {
				return height;
			}
		}
		return pos == null ? 256.0 : pos.getY() + 256.0;
	}

	/**
	 * The build-height accessor has been renamed more than once across the releases
	 * Beryllium ships on, so it is resolved reflectively: whichever of the known names
	 * this version carries wins, and if none of them exist the caller falls back to a
	 * conservative default. That keeps beam columns sized correctly everywhere without
	 * pinning the build to one release's mapping.
	 */
	private static Method buildHeightMethod;
	private static boolean buildHeightMethodResolved;

	private static int buildHeightOf(Level level) {
		if (!buildHeightMethodResolved) {
			buildHeightMethodResolved = true;
			for (String name : new String[] {"getMaxBuildHeight", "getMaxY"}) {
				try {
					Method method = Level.class.getMethod(name);
					if (method.getReturnType() == int.class) {
						buildHeightMethod = method;
						break;
					}
				} catch (Throwable ignored) {
					// try the next name this release might use
				}
			}
		}
		if (buildHeightMethod != null) {
			try {
				int value = (Integer) buildHeightMethod.invoke(level);
				// getMaxY() returns the topmost *inhabitable* Y, one below the ceiling.
				return "getMaxY".equals(buildHeightMethod.getName()) ? value + 1 : value;
			} catch (Throwable ignored) {
				// fall through to the default
			}
		}
		return 0;
	}

	/** The per-frame memo is a pure optimisation; it can be switched off without changing
	 *  what is culled, only how often the frustum is asked. */
	private static boolean cacheEnabled() {
		BerylliumConfig config = Beryllium.config();
		return config == null || config.visibilityCulling;
	}

	public static long cacheHits() {
		return cacheHits;
	}

	public static long cacheMisses() {
		return cacheMisses;
	}

	/** How many answers came from the asynchronous pipeline's cache rather than from a
	 *  frustum test on the render thread. */
	public static long asyncHits() {
		return asyncHits;
	}

	/** The largest single-inflation memo the previous frame needed. */
	public static int largestMemoThisFrame() {
		return largestMemoThisFrame;
	}

	/**
	 * Picks the first non-null {@link Frustum} field off {@code Minecraft.levelRenderer}.
	 * Walking by type instead of by mapped name keeps this working across minor mapping
	 * churn, and a failure simply means "never cull".
	 */
	public static Frustum currentFrustum() {
		try {
			Minecraft minecraft = Minecraft.getInstance();
			if (minecraft == null || minecraft.levelRenderer == null) {
				return null;
			}
			Object levelRenderer = minecraft.levelRenderer;

			if (!frustumFieldsResolved) {
				synchronized (VisibilityCulling.class) {
					if (!frustumFieldsResolved) {
						List<Field> found = new ArrayList<>();
						for (Class<?> current = levelRenderer.getClass();
							 current != null && current != Object.class;
							 current = current.getSuperclass()) {
							for (Field field : current.getDeclaredFields()) {
								if (Frustum.class.isAssignableFrom(field.getType())) {
									try {
										field.setAccessible(true);
									} catch (Throwable ignored) {
										// keep going; another candidate may be reachable
									}
									found.add(field);
								}
							}
						}
						frustumFields = found.toArray(new Field[0]);
						frustumFieldsResolved = true;
					}
				}
			}

			Field[] fields = frustumFields;
			if (fields == null) {
				return null;
			}
			for (Field field : fields) {
				try {
					Object value = field.get(levelRenderer);
					if (value instanceof Frustum frustum) {
						// This is the earliest moment in the frame at which the frustum is
						// guaranteed to describe the camera culling is about to be done for,
						// so it is where the asynchronous pipeline gets its snapshot. Every
						// later call in the same frame is a no-op.
						AsyncCullingPipeline.instance().publish(frustum);
						return frustum;
					}
				} catch (Throwable ignored) {
					// try the next candidate
				}
			}
			return null;
		} catch (Throwable t) {
			return null;
		}
	}
}
