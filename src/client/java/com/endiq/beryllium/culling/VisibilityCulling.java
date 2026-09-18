package com.endiq.beryllium.culling;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.config.BerylliumConfig;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

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
	private static final int CACHE_CLEAR_THRESHOLD = 4096;

	private static volatile Field[] frustumFields;
	private static volatile boolean frustumFieldsResolved;

	private static final Map<Long, Boolean> blockVisibilityCache = new HashMap<>(512);
	private static int cacheHits = 0;
	private static int cacheMisses = 0;

	private VisibilityCulling() {
	}

	/** Resets the per-frame memo. Called once per rendered frame. */
	public static void onFrameStart() {
		if (blockVisibilityCache.size() > CACHE_CLEAR_THRESHOLD) {
			blockVisibilityCache.clear();
		} else {
			blockVisibilityCache.clear();
		}
	}

	/**
	 * @return true if the block at {@code pos} is inside the camera frustum (or the test
	 *         could not be performed, in which case it is treated as visible).
	 */
	public static boolean isBlockVisible(BlockPos pos, double inflation) {
		if (pos == null) {
			return true;
		}
		if (!cacheEnabled()) {
			// Memoisation off: answer the question directly every time.
			return isBoxVisible(new AABB(
				pos.getX() - inflation,
				pos.getY() - inflation,
				pos.getZ() - inflation,
				pos.getX() + 1.0 + inflation,
				pos.getY() + 1.0 + inflation,
				pos.getZ() + 1.0 + inflation
			));
		}
		Long key = pos.asLong();
		Boolean cached = blockVisibilityCache.get(key);
		if (cached != null) {
			cacheHits++;
			return cached;
		}
		cacheMisses++;

		boolean visible = isBoxVisible(new AABB(
			pos.getX() - inflation,
			pos.getY() - inflation,
			pos.getZ() - inflation,
			pos.getX() + 1.0 + inflation,
			pos.getY() + 1.0 + inflation,
			pos.getZ() + 1.0 + inflation
		));
		blockVisibilityCache.put(key, visible);
		return visible;
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
		Vec3 position = cameraAccessorPosition();
		if (position != null) {
			return position;
		}
		// Fallback for releases whose Camera carries its state elsewhere (26.x): the
		// player's eye position. In third person the camera pulls back a few blocks from
		// the eye, so the anchor is slightly different — the culling ranges already treat
		// distances as approximate, and the alternative is not culling at all.
		return playerEyePosition();
	}

	private static Vec3 cameraAccessorPosition() {
		try {
			Camera camera = mainCamera();
			if (camera == null) {
				return null;
			}
			// Looked up reflectively: the accessor was renamed late in the supported range
			// (getPosition existed through 1.21.10). A miss means "cannot determine" and the
			// callers fall back to not culling.
			if (!cameraPositionResolved) {
				synchronized (VisibilityCulling.class) {
					if (!cameraPositionResolved) {
						cameraPositionMethod = findFirstMethod(Camera.class, "getPosition");
						cameraPositionResolved = true;
					}
				}
			}
			Method method = cameraPositionMethod;
			if (method == null) {
				return null;
			}
			Object value = method.invoke(camera);
			return value instanceof Vec3 vec ? vec : null;
		} catch (Throwable t) {
			return null;
		}
	}

	private static Vec3 playerEyePosition() {
		try {
			Object player = Minecraft.getInstance() == null ? null : Minecraft.getInstance().player;
			if (player == null) {
				return null;
			}
			if (!playerEyeResolved) {
				synchronized (VisibilityCulling.class) {
					if (!playerEyeResolved) {
						playerEyeMethod = findFirstMethod(player.getClass(), "getEyePosition");
						playerEyeResolved = true;
					}
				}
			}
			Method method = playerEyeMethod;
			if (method == null) {
				return null;
			}
			Object value = method.invoke(player);
			return value instanceof Vec3 vec ? vec : null;
		} catch (Throwable t) {
			return null;
		}
	}

	/**
	 * The direction the camera is looking, or {@code null} when it cannot be determined.
	 *
	 * <p>Reflective for the same reason as {@link #cameraPosition()}; the result is
	 * normalised to a JOML vector because the accessor's return type changed across the
	 * range (a {@code Vector3f} on older releases). The fallback for state-carrying
	 * cameras is the player's view vector at full tick delta.
	 */
	public static Vector3f cameraLookVector() {
		try {
			Camera camera = mainCamera();
			if (camera != null) {
				if (!cameraLookResolved) {
					synchronized (VisibilityCulling.class) {
						if (!cameraLookResolved) {
							cameraLookMethod = findFirstMethod(Camera.class, "getLookVector", "getLookDirection");
							cameraLookResolved = true;
						}
					}
				}
				Method method = cameraLookMethod;
				if (method != null) {
					Object value = method.invoke(camera);
					if (value instanceof Vector3f vec) {
						return vec;
					}
					if (value instanceof Vec3 vec) {
						return new Vector3f((float) vec.x, (float) vec.y, (float) vec.z);
					}
				}
			}

			Object player = Minecraft.getInstance() == null ? null : Minecraft.getInstance().player;
			if (player == null) {
				return null;
			}
			if (!playerLookResolved) {
				synchronized (VisibilityCulling.class) {
					if (!playerLookResolved) {
						// getViewVector takes a partial-tick float; getLookAngle is no-arg.
						playerLookMethod = findFirstMethod(player.getClass(), float.class, "getViewVector");
						if (playerLookMethod == null) {
							playerLookMethod = findFirstMethod(player.getClass(), "getLookAngle");
						}
						playerLookResolved = true;
					}
				}
			}
			Method method = playerLookMethod;
			if (method == null) {
				return null;
			}
			Object value = method.getParameterCount() == 1 ? method.invoke(player, 1.0F) : method.invoke(player);
			if (value instanceof Vector3f vec) {
				return vec;
			}
			if (value instanceof Vec3 vec) {
				return new Vector3f((float) vec.x, (float) vec.y, (float) vec.z);
			}
			return null;
		} catch (Throwable t) {
			return null;
		}
	}

	private static Camera mainCamera() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || minecraft.gameRenderer == null) {
			return null;
		}
		return minecraft.gameRenderer.getMainCamera();
	}

	/** @return the world's build height, used to size beam columns. */
	public static double ceilingOf(Level level, BlockPos pos) {
		if (level != null) {
			Double top = buildCeilingY(level);
			if (top != null) {
				return top;
			}
		}
		return pos == null ? 256.0 : pos.getY() + 256.0;
	}

	/**
	 * The top of the world's build volume, or {@code null} when it cannot be determined.
	 *
	 * <p>Looked up reflectively because the accessor was renamed across the supported
	 * range ({@code getMaxBuildHeight} on older releases, {@code getMaxY} on 1.21.5+), and
	 * Beryllium builds one source tree for all of them. Either name answers the same
	 * question closely enough for a beam-column bound; a miss falls back to the
	 * conservative default in {@link #ceilingOf(Level, BlockPos)}.
	 */
	private static Double buildCeilingY(Level level) {
		try {
			if (!buildCeilingResolved) {
				synchronized (VisibilityCulling.class) {
					if (!buildCeilingResolved) {
						buildCeilingMethod = findFirstMethod(level.getClass(), "getMaxY", "getMaxBuildHeight");
						buildCeilingResolved = true;
					}
				}
			}
			Method method = buildCeilingMethod;
			if (method == null) {
				return null;
			}
			Object value = method.invoke(level);
			return value instanceof Number number ? number.doubleValue() : null;
		} catch (Throwable t) {
			return null;
		}
	}

	/** Walks the class hierarchy for the first declared no-arg method with one of the
	 *  given names; {@code null} when none exists. */
	private static Method findFirstMethod(Class<?> clazz, String... names) {
		for (String name : names) {
			for (Class<?> current = clazz; current != null && current != Object.class; current = current.getSuperclass()) {
				try {
					Method method = current.getDeclaredMethod(name);
					method.setAccessible(true);
					return method;
				} catch (NoSuchMethodException ignored) {
					// try the next class up the hierarchy
				} catch (Throwable ignored) {
					break;
				}
			}
		}
		return null;
	}

	/** Same walk, but for a single-{@code float} method. */
	private static Method findFirstMethod(Class<?> clazz, Class<?> parameter, String... names) {
		for (String name : names) {
			for (Class<?> current = clazz; current != null && current != Object.class; current = current.getSuperclass()) {
				try {
					Method method = current.getDeclaredMethod(name, parameter);
					method.setAccessible(true);
					return method;
				} catch (NoSuchMethodException ignored) {
					// try the next class up the hierarchy
				} catch (Throwable ignored) {
					break;
				}
			}
		}
		return null;
	}

	private static volatile Method buildCeilingMethod;
	private static volatile boolean buildCeilingResolved;

	private static volatile Method cameraPositionMethod;
	private static volatile boolean cameraPositionResolved;
	private static volatile Method cameraLookMethod;
	private static volatile boolean cameraLookResolved;
	private static volatile Method playerEyeMethod;
	private static volatile boolean playerEyeResolved;
	private static volatile Method playerLookMethod;
	private static volatile boolean playerLookResolved;

	/** The per-frame memo is a pure optimisation; it can be switched off without changing
	 *  what is culled, only how often the frustum is asked. */
	private static boolean cacheEnabled() {
		BerylliumConfig config = Beryllium.config();
		return config == null || config.visibilityCulling;
	}

	public static int cacheHits() {
		return cacheHits;
	}

	public static int cacheMisses() {
		return cacheMisses;
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
