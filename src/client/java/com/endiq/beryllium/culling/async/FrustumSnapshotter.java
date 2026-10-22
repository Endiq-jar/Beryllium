package com.endiq.beryllium.culling.async;

import com.endiq.beryllium.util.BerylliumLog;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.AABB;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns the live {@link Frustum} into a {@link FrustumPlanes} the worker threads may use —
 * and, critically, <em>proves</em> the copy behaves like the original before anything is
 * allowed to depend on it.
 *
 * <p>Everything in this class runs on the render thread. It is the one place Beryllium
 * touches a live Minecraft object on behalf of the asynchronous pipeline, and it does so
 * for as short a time as possible: read the planes, read the origin, copy them out, let go.
 *
 * <h2>Why validation is not optional</h2>
 *
 * <p>The plane storage is found reflectively, by shape rather than by field name, because
 * Beryllium ships across releases whose mappings differ and because {@code Frustum}'s
 * internals have changed shape more than once (an array of JOML {@code Vector4f} in some
 * releases, a {@code double[][]} in others). Guessing correctly on the releases tested
 * today says nothing about next year's.
 *
 * <p>A wrong guess here is not a crash — it is silently culling geometry the player should
 * see, which is the worst failure a rendering optimisation can have. So instead of trusting
 * the reflection, every candidate snapshot is checked against the frustum it came from: a
 * spread of probe boxes is tested both ways, and any disagreement disqualifies the
 * candidate. Extraction that cannot be validated is treated as extraction that did not
 * happen, and the caller falls back to asking the live frustum directly.
 *
 * <p>The probe set is deliberately adversarial — boxes near and far, in front and behind,
 * straddling the edges, degenerate and enormous — because the failure modes worth catching
 * are the subtle ones: a missing camera-relative offset, a flipped plane normal, or an
 * extra plane that only bites at the periphery of the view.
 */
public final class FrustumSnapshotter {
	/**
	 * How often a validated extraction strategy is re-checked against the live frustum.
	 *
	 * <p>The <em>strategy</em> (which fields hold the planes) is stable for the life of the
	 * process, but the frustum object itself is rebuilt and mutated constantly, so the copy
	 * is re-extracted every frame regardless. Re-validating every frame would be wasteful;
	 * re-validating rarely would let a change in the frustum's contents go unnoticed. Every
	 * N frames is the compromise, and the first snapshot is always validated.
	 */
	private static final int REVALIDATE_EVERY_FRAMES = 20;

	private static volatile Field planeField;
	private static volatile Field[] originFields;
	private static volatile boolean shapeResolved;
	private static volatile boolean shapeUsable;

	/** Snapshots taken since the last validation, and how many have been disqualified. */
	private static int framesSinceValidation = REVALIDATE_EVERY_FRAMES;
	private static long extracted = 0;
	private static long rejected = 0;

	private FrustumSnapshotter() {
	}

	/**
	 * Copies {@code frustum} into an immutable snapshot, or returns {@code null} if the copy
	 * could not be made or could not be shown to agree with the original.
	 *
	 * <p>A {@code null} return is not an error to be reported to the player — it means "no
	 * snapshot", and every caller treats that as "do the culling synchronously, as before".
	 */
	public static FrustumPlanes snapshot(Frustum frustum) {
		if (frustum == null) {
			return null;
		}
		try {
			resolveShape(frustum);
			if (!shapeUsable) {
				return null;
			}

			double[] planes = readPlanes(frustum);
			if (planes == null) {
				return null;
			}
			double[] origin = readOrigin(frustum);
			FrustumPlanes candidate = new FrustumPlanes(planes, origin[0], origin[1], origin[2]);
			extracted++;

			if (framesSinceValidation >= REVALIDATE_EVERY_FRAMES) {
				framesSinceValidation = 0;
				if (!agreesWith(candidate, frustum)) {
					rejected++;
					// The strategy has stopped reproducing the frustum. Drop it rather than
					// keep handing out copies that might be wrong; the next call re-resolves
					// from scratch, and if it still fails we stay on the synchronous path.
					shapeUsable = false;
					shapeResolved = false;
					planeField = null;
					originFields = null;
					BerylliumLog.debug("[BERYLLIUM] frustum snapshot disagreed with the live frustum; "
						+ "asynchronous culling will re-resolve or stay synchronous");
					return null;
				}
			} else {
				framesSinceValidation++;
			}
			return candidate;
		} catch (Throwable t) {
			// Any surprise at all means no snapshot. The synchronous path is unaffected.
			rejected++;
			shapeUsable = false;
			shapeResolved = false;
			return null;
		}
	}

	/**
	 * Whether a snapshot and the frustum it came from give the same answer for every probe
	 * box. This is the check that makes reflective extraction safe to rely on.
	 */
	private static boolean agreesWith(FrustumPlanes candidate, Frustum frustum) {
		double cx = candidate.originX();
		double cy = candidate.originY();
		double cz = candidate.originZ();

		// Offsets chosen to cover the interesting cases: inside the view, outside it, on the
		// near and far planes, behind the camera, and boxes big enough to straddle an edge
		// (where an off-by-one in the offset shows up first).
		double[][] offsets = {
			{0, 0, -4},      // just in front
			{0, 0, -32},     // mid distance
			{0, 0, -256},    // far, may be past the far plane
			{0, 0, 8},       // behind the camera
			{0, 0, 512},     // far behind the camera
			{16, -2, -16},   // off to the side and below
			{-16, 6, -16},   // off to the other side and above
			{64, 0, -64},    // wide angle, likely outside the FOV
			{-64, 0, -64},   // wide angle, other side
			{0, 48, -16},    // straight up and slightly forward
			{0, -48, -16},   // straight down and slightly forward
			{2, 1, -2},      // close, partially behind: straddles the near plane
		};
		// Half-extents: a point-like box, a block-sized box, and a chunk-sized box.
		double[] sizes = {0.0, 0.5, 8.0};

		int probes = 0;
		for (double[] offset : offsets) {
			for (double size : sizes) {
				double bx = cx + offset[0];
				double by = cy + offset[1];
				double bz = cz + offset[2];
				AABB box = new AABB(bx - size, by - size, bz - size, bx + size, by + size, bz + size);

				boolean live;
				try {
					live = frustum.isVisible(box);
				} catch (Throwable t) {
					// The live frustum will not answer, so there is nothing to agree with.
					return false;
				}
				boolean snapshotSays = candidate.isVisible(
					box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
				probes++;
				if (live != snapshotSays) {
					return false;
				}
			}
		}
		// A probe set that tested nothing proves nothing.
		return probes == offsets.length * sizes.length;
	}

	/**
	 * Finds the plane storage and the camera-relative origin on this release's
	 * {@code Frustum}, once, by shape.
	 *
	 * <p>Planes: an array field of exactly six elements whose component type can supply four
	 * numbers. Origin: if the class carries exactly three {@code double} fields, those are
	 * the camera offset every release of {@code Frustum} has kept; otherwise the origin is
	 * taken as zero and validation decides whether that was right.
	 */
	private static void resolveShape(Frustum frustum) {
		if (shapeResolved) {
			return;
		}
		synchronized (FrustumSnapshotter.class) {
			if (shapeResolved) {
				return;
			}
			shapeUsable = false;
			try {
				Class<?> type = frustum.getClass();
				Field planes = null;
				List<Field> doubles = new ArrayList<>();

				for (Class<?> current = type; current != null && current != Object.class;
				     current = current.getSuperclass()) {
					for (Field field : current.getDeclaredFields()) {
						Class<?> fieldType = field.getType();
						if (planes == null && fieldType.isArray()
							&& !fieldType.getComponentType().isPrimitive()) {
							try {
								field.setAccessible(true);
								Object value = field.get(frustum);
								if (value instanceof Object[] array
									&& array.length == FrustumPlanes.PLANE_COUNT) {
									planes = field;
								}
							} catch (Throwable ignored) {
								// not this one
							}
						} else if (planes == null && fieldType == double[][].class) {
							try {
								field.setAccessible(true);
								Object value = field.get(frustum);
								if (value instanceof double[][] grid
									&& grid.length == FrustumPlanes.PLANE_COUNT) {
									planes = field;
								}
							} catch (Throwable ignored) {
								// not this one
							}
						}
						if (fieldType == double.class) {
							try {
								field.setAccessible(true);
								doubles.add(field);
							} catch (Throwable ignored) {
								// unreadable; the origin stays at zero and validation decides
							}
						}
					}
				}

				planeField = planes;
				originFields = doubles.size() == 3 ? doubles.toArray(new Field[0]) : new Field[0];
				shapeUsable = planes != null;
			} catch (Throwable t) {
				shapeUsable = false;
			}
			shapeResolved = true;
		}
	}

	/**
	 * Reads the 24 plane components out of whichever storage shape this release uses.
	 * Supports an array of JOML-style vectors (accessed by {@code x()}/{@code y()}/... or by
	 * public {@code x}/{@code y}/... fields) and a {@code double[][]}.
	 */
	private static double[] readPlanes(Frustum frustum) throws Exception {
		Field field = planeField;
		if (field == null) {
			return null;
		}
		Object storage = field.get(frustum);
		double[] out = new double[FrustumPlanes.PLANE_COUNT * 4];

		if (storage instanceof double[][] grid) {
			for (int i = 0; i < FrustumPlanes.PLANE_COUNT; i++) {
				double[] plane = grid[i];
				if (plane == null || plane.length < 4) {
					return null;
				}
				out[i * 4] = plane[0];
				out[i * 4 + 1] = plane[1];
				out[i * 4 + 2] = plane[2];
				out[i * 4 + 3] = plane[3];
			}
			return out;
		}

		if (storage instanceof Object[] vectors) {
			for (int i = 0; i < FrustumPlanes.PLANE_COUNT; i++) {
				Object vector = vectors[i];
				if (vector == null) {
					return null;
				}
				if (!readVector4(vector, out, i * 4)) {
					return null;
				}
			}
			return out;
		}
		return null;
	}

	/** Reads four components off a plane vector, by accessor method or by public field. */
	private static boolean readVector4(Object vector, double[] out, int offset) {
		String[] names = {"x", "y", "z", "w"};
		Class<?> type = vector.getClass();
		for (int i = 0; i < 4; i++) {
			Double value = null;
			try {
				Object result = type.getMethod(names[i]).invoke(vector);
				if (result instanceof Number number) {
					value = number.doubleValue();
				}
			} catch (Throwable ignored) {
				// no accessor; try the field below
			}
			if (value == null) {
				try {
					Field field = type.getField(names[i]);
					Object result = field.get(vector);
					if (result instanceof Number number) {
						value = number.doubleValue();
					}
				} catch (Throwable ignored) {
					return false;
				}
			}
			if (value == null) {
				return false;
			}
			out[offset + i] = value;
		}
		return true;
	}

	private static double[] readOrigin(Frustum frustum) {
		Field[] fields = originFields;
		if (fields == null || fields.length != 3) {
			return new double[3];
		}
		double[] origin = new double[3];
		try {
			for (int i = 0; i < 3; i++) {
				origin[i] = fields[i].getDouble(frustum);
			}
		} catch (Throwable t) {
			return new double[3];
		}
		return origin;
	}

	public static long extracted() {
		return extracted;
	}

	public static long rejected() {
		return rejected;
	}

	/** Whether the reflective shape was found at all — useful for the debug overlay. */
	public static boolean shapeUsable() {
		return shapeUsable;
	}

	/**
	 * Drops the resolved shape so the next snapshot re-derives it. Called on world change,
	 * where a different {@code LevelRenderer} (and therefore possibly a different frustum
	 * class) may take over.
	 */
	public static void invalidate() {
		synchronized (FrustumSnapshotter.class) {
			shapeResolved = false;
			shapeUsable = false;
			planeField = null;
			originFields = null;
			framesSinceValidation = REVALIDATE_EVERY_FRAMES;
		}
	}
}
