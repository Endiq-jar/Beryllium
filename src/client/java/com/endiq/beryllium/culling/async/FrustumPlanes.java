package com.endiq.beryllium.culling.async;

/**
 * An immutable snapshot of a camera frustum, reduced to the only thing a visibility test
 * actually needs: six planes and the origin they are measured from.
 *
 * <p>This exists because of one architectural rule that the whole asynchronous culling
 * pipeline is built around: <strong>a worker thread must never read a live Minecraft
 * object.</strong> {@code net.minecraft.client.renderer.culling.Frustum} is mutated in
 * place every frame, holds JOML vectors, and is not safe to touch from anywhere but the
 * render thread. So the render thread copies it into this — plain {@code double}s, final
 * fields, nothing mutable — and hands the copy to the worker. The worker then does all of
 * its intersection math against a picture of the frustum that cannot change underneath it.
 *
 * <p>Planes use Minecraft's own convention: a box is visible when, for every plane, the
 * corner furthest along that plane's normal lies on the near side. Planes are stored
 * relative to {@link #originX}/{@link #originY}/{@link #originZ} (the camera position the
 * frustum was built at), exactly as {@code Frustum} does internally, so callers pass
 * world-space boxes and the offset is applied here.
 *
 * <p>Instances of this class carry no guarantee that they are <em>correct</em> — only that
 * they are stable. Whether the extraction that produced one faithfully reproduces the live
 * frustum is settled by {@link FrustumSnapshotter}, which validates every candidate before
 * it is allowed to drive a culling decision.
 */
public final class FrustumPlanes {
	/** Minecraft's {@code Frustum} keeps six planes; a null entry means "no constraint". */
	public static final int PLANE_COUNT = 6;

	private final double[] planes;

	private final double originX;
	private final double originY;
	private final double originZ;

	/**
	 * @param planes 24 doubles, four per plane (normal x, y, z and offset w), in Minecraft's
	 *               plane order. Copied, not retained — the caller may reuse its array.
	 * @param originX camera X the planes are measured from
	 * @param originY camera Y the planes are measured from
	 * @param originZ camera Z the planes are measured from
	 */
	public FrustumPlanes(double[] planes, double originX, double originY, double originZ) {
		if (planes == null || planes.length != PLANE_COUNT * 4) {
			throw new IllegalArgumentException(
				"frustum needs " + (PLANE_COUNT * 4) + " plane components, got "
					+ (planes == null ? "null" : planes.length));
		}
		this.planes = planes.clone();
		this.originX = originX;
		this.originY = originY;
		this.originZ = originZ;

		// A plane whose normal has no length cannot reject anything, and dividing by it
		// would produce NaN that then compares false against every threshold — silently
		// culling the world. Refuse to build such a snapshot instead.
		for (int i = 0; i < PLANE_COUNT; i++) {
			double a = this.planes[i * 4];
			double b = this.planes[i * 4 + 1];
			double c = this.planes[i * 4 + 2];
			if (!(a * a + b * b + c * c > 0.0)) {
				throw new IllegalArgumentException("frustum plane " + i + " has a degenerate normal");
			}
		}
	}

	public double originX() {
		return originX;
	}

	public double originY() {
		return originY;
	}

	public double originZ() {
		return originZ;
	}

	/** Component {@code index} (0..3) of plane {@code plane} (0..5). */
	public double planeComponent(int plane, int index) {
		return planes[plane * 4 + index];
	}

	/**
	 * Whether a world-space axis-aligned box intersects the frustum.
	 *
	 * <p>Uses the positive-vertex test: for each plane, the box corner furthest along the
	 * plane normal is the last part of the box to leave the near side, so if even that
	 * corner is outside, the whole box is. This is the same test {@code Frustum.isVisible}
	 * performs, which is what makes a snapshot comparable against the live frustum.
	 *
	 * <p>Deliberately takes doubles rather than an {@code AABB}: taking an {@code AABB}
	 * would put a Minecraft type on the worker's call path, and allocating one per test
	 * would defeat the point of caching the answer.
	 */
	public boolean isVisible(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
		// NaN in any bound means the caller's geometry is broken. Answering "not visible"
		// would delete real geometry, so treat it as visible and let the frame draw it.
		if (Double.isNaN(minX) || Double.isNaN(minY) || Double.isNaN(minZ)
			|| Double.isNaN(maxX) || Double.isNaN(maxY) || Double.isNaN(maxZ)) {
			return true;
		}

		double relMinX = minX - originX;
		double relMinY = minY - originY;
		double relMinZ = minZ - originZ;
		double relMaxX = maxX - originX;
		double relMaxY = maxY - originY;
		double relMaxZ = maxZ - originZ;

		for (int i = 0; i < PLANE_COUNT; i++) {
			double a = planes[i * 4];
			double b = planes[i * 4 + 1];
			double c = planes[i * 4 + 2];
			double d = planes[i * 4 + 3];

			double px = a >= 0.0 ? relMaxX : relMinX;
			double py = b >= 0.0 ? relMaxY : relMinY;
			double pz = c >= 0.0 ? relMaxZ : relMinZ;

			if (a * px + b * py + c * pz + d < 0.0) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Whether two snapshots describe the same frustum. Used to decide if a cached answer is
	 * still about the camera the player is currently looking through: a frustum that has not
	 * moved cannot have changed its mind about a box that has not moved either.
	 */
	public boolean sameViewAs(FrustumPlanes other) {
		if (other == this) {
			return true;
		}
		if (other == null) {
			return false;
		}
		if (originX != other.originX || originY != other.originY || originZ != other.originZ) {
			return false;
		}
		for (int i = 0; i < planes.length; i++) {
			if (planes[i] != other.planes[i]) {
				return false;
			}
		}
		return true;
	}
}
