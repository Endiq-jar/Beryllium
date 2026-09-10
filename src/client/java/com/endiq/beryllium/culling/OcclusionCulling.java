package com.endiq.beryllium.culling;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.config.BerylliumConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * Phase 13 — occlusion culling for entities ("EntityCulling technology").
 *
 * <p>Vanilla's entity culling is frustum-only: an entity standing behind a mountain,
 * a wall or a hillside is still submitted for rendering every frame as long as it is
 * inside the view cone. Beryllium already skips behind-camera and fog-hidden
 * entities (phases 6/12); this adds the third and largest class of hidden entities —
 * the ones <em>occluded by solid terrain</em>.
 *
 * <p>The test is a voxel raymarch from the camera to the entity's bounding-box
 * silhouette: five rays (center plus four corners of a slightly tightened box). An
 * entity is culled only when <em>every</em> ray passes through at least one run of
 * opaque blocks — i.e. a curtain of solid geometry covers the whole silhouette. A
 * single opaque block does not cull: the run requirement keeps grazing corners, thin
 * fences-in-front-of-a-hole situations and one-block pillars from hiding anything the
 * player could still see. Opacity means {@code BlockState#canOcclude} — full solid
 * blocks only, so glass, leaves, water, fences and every partial shape stay
 * see-through for this test, exactly as they are for the player.
 *
 * <p>Cost control, because this runs on the render thread:
 * <ul>
 *   <li>Only entities between {@code occlusionCullMinDistance} and
 *       {@code occlusionCullMaxDistance} are candidates (near entities are cheap to
 *       draw and too important to risk; far ones are already fog/frustum territory).</li>
 *   <li>At most {@code occlusionCullRaycastsPerFrame} new entities are evaluated per
 *       frame; every other candidate reuses its cached verdict.</li>
 *   <li>Verdicts are cached per entity id with a short TTL
 *       ({@link #CACHE_TTL_NANOS}), so a culled entity stays culled for several frames
 *       without re-tracing the ray.</li>
 *   <li>Anything that cannot be trusted — a glowing entity (which renders through
 *       walls by design), the camera entity, no world, an exception — is never culled.</li>
 * </ul>
 *
 * <p>The cache is a fixed-size open-addressed table of primitives allocated once, so
 * steady-state operation performs no boxing and no allocation on the render thread.
 */
public final class OcclusionCulling {
	private OcclusionCulling() {
	}

	/** Ray sample spacing in blocks. 0.5 guarantees at least two samples inside a
	 *  full block, which is what the run requirement below relies on. */
	private static final double RAY_STEP = 0.5;

	/** Consecutive opaque samples required along a ray before it counts as blocked
	 *  (2 samples x 0.5 = one full block of solid matter at minimum). */
	private static final int OPAQUE_RUN_REQUIRED = 2;

	/** How long a verdict stays valid. 100 ms is a few frames at any playable rate. */
	private static final long CACHE_TTL_NANOS = 100_000_000L;

	/** Corner rays are pulled this far in from the box edge so a corner sample cannot
	 *  sit inside a neighbouring block that the entity itself does not touch. */
	private static final double CORNER_INSET = 0.05;

	// --- session counters (debug overlay) ---

	private static long occludedEntities;
	private static long raycasts;

	public static long occludedEntities() {
		return occludedEntities;
	}

	public static long raycasts() {
		return raycasts;
	}

	// --- fixed-size cache: entity id -> verdict, stamped with the time it was taken ---

	private static final int CACHE_SIZE = 1024;
	private static final int CACHE_MASK = CACHE_SIZE - 1;
	private static final int[] cacheIds = new int[CACHE_SIZE];
	private static final long[] cacheStamps = new long[CACHE_SIZE];
	private static final boolean[] cacheOccluded = new boolean[CACHE_SIZE];
	private static int raycastsThisFrame;
	private static long lastFrameStamp;

	private static int cacheSlot(int entityId) {
		// Multiplicative hash (Knuth) keeps consecutive entity ids off adjacent slots.
		int hash = entityId * 0x9E3779B1;
		return (hash ^ (hash >>> 16)) & CACHE_MASK;
	}

	private static boolean cacheLookup(int entityId, long now) {
		int slot = cacheSlot(entityId);
		return cacheIds[slot] == entityId && now - cacheStamps[slot] < CACHE_TTL_NANOS && cacheOccluded[slot];
	}

	private static void cacheStore(int entityId, long now, boolean occluded) {
		int slot = cacheSlot(entityId);
		cacheIds[slot] = entityId;
		cacheStamps[slot] = now;
		cacheOccluded[slot] = occluded;
	}

	/**
	 * @return true when this entity is provably hidden behind solid blocks and its
	 *         render call may be skipped. Conservative in every ambiguous case.
	 */
	public static boolean isOccluded(Entity entity, double camX, double camY, double camZ) {
		try {
			BerylliumConfig config = Beryllium.config();
			if (config == null || !config.enabled || !config.cullOccludedEntities) {
				return false;
			}
			if (entity == null) {
				return false;
			}
			// Glowing entities render through walls by design (spectral/team glow is
			// meant to be seen through terrain) — never cull them.
			if (entity.isCurrentlyGlowing() || entity.hasGlowingTag()) {
				return false;
			}
			// Never cull the entity the camera is attached to (third-person view).
			var minecraft = net.minecraft.client.Minecraft.getInstance();
			if (minecraft != null && minecraft.getCameraEntity() == entity) {
				return false;
			}
			Level level = entity.level();
			if (level == null) {
				return false;
			}

			int entityId = entity.getId();
			long now = System.nanoTime();
			if (now - lastFrameStamp > 5_000_000L) {
				lastFrameStamp = now;
				raycastsThisFrame = 0;
			}
			if (cacheLookup(entityId, now)) {
				return true;
			}

			double minDistance = Math.max(0.0, config.occlusionCullMinDistance);
			double maxDistance = Math.max(minDistance, config.occlusionCullMaxDistance);

			AABB box = entity.getBoundingBox();
			double cx = (box.minX + box.maxX) * 0.5;
			double cy = Math.max(box.minY, Math.min(box.maxY, (box.minY + box.maxY) * 0.5));
			double cz = (box.minZ + box.maxZ) * 0.5;

			double dx = cx - camX;
			double dy = cy - camY;
			double dz = cz - camZ;
			double distanceSq = dx * dx + dy * dy + dz * dz;
			if (distanceSq < minDistance * minDistance || distanceSq > maxDistance * maxDistance) {
				cacheStore(entityId, now, false);
				return false;
			}

			int budget = Math.max(1, config.occlusionCullRaycastsPerFrame);
			if (raycastsThisFrame >= budget) {
				// Budget spent for this frame: keep the previous verdict if it is
				// still fresh (cacheLookup already checked), otherwise render.
				return false;
			}
			raycastsThisFrame++;

			boolean occluded = silhouetteOccluded(level, box, camX, camY, camZ, minDistance);
			cacheStore(entityId, now, occluded);
			if (occluded) {
				occludedEntities++;
			}
			raycasts++;
			return occluded;
		} catch (Throwable t) {
			// A culling decision must never break entity rendering.
			return false;
		}
	}

	/** Center ray plus the four box corners, all of which must be blocked. */
	private static boolean silhouetteOccluded(
		Level level, AABB box, double camX, double camY, double camZ, double minDistance
	) {
		double x0 = box.minX + CORNER_INSET;
		double x1 = box.maxX - CORNER_INSET;
		double z0 = box.minZ + CORNER_INSET;
		double z1 = box.maxZ - CORNER_INSET;
		// Vertical: midpoint of the box is what shouldRender's own bounds culling
		// cares about, and it is the most reliable "body" sample for tall entities.
		double y = Math.max(box.minY, Math.min(box.maxY, (box.minY + box.maxY) * 0.5));

		if (!rayBlocked(level, camX, camY, camZ, x0, y, z0, minDistance)
			|| !rayBlocked(level, camX, camY, camZ, x1, y, z0, minDistance)
			|| !rayBlocked(level, camX, camY, camZ, x0, y, z1, minDistance)
			|| !rayBlocked(level, camX, camY, camZ, x1, y, z1, minDistance)
			|| !rayBlocked(level, camX, camY, camZ, (x0 + x1) * 0.5, y, (z0 + z1) * 0.5, minDistance)) {
			return false;
		}
		return true;
	}

	/**
	 * Marches from the camera to the target and reports whether the ray passes through
	 * a run of at least {@link #OPAQUE_RUN_REQUIRED} opaque samples. Sampling starts
	 * {@code minDistance} away so a block the camera is standing inside can never
	 * account for the blockage.
	 */
	private static boolean rayBlocked(
		Level level, double camX, double camY, double camZ,
		double targetX, double targetY, double targetZ, double minDistance
	) {
		double dx = targetX - camX;
		double dy = targetY - camY;
		double dz = targetZ - camZ;
		double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (distance <= minDistance + RAY_STEP) {
			return false;
		}

		double start = minDistance;
		int steps = (int) ((distance - start) / RAY_STEP);
		if (steps < 1) {
			return false;
		}

		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int run = 0;
		int lastX = Integer.MIN_VALUE;
		int lastY = Integer.MIN_VALUE;
		int lastZ = Integer.MIN_VALUE;

		for (int i = 1; i <= steps; i++) {
			double t = (start + i * RAY_STEP) / distance;
			int bx = (int) Math.floor(camX + dx * t);
			int by = (int) Math.floor(camY + dy * t);
			int bz = (int) Math.floor(camZ + dz * t);

			if (bx == lastX && by == lastY && bz == lastZ) {
				// Several samples inside one block: count once, so the run length
				// measures real thickness rather than sample density.
				continue;
			}
			lastX = bx;
			lastY = by;
			lastZ = bz;

			pos.set(bx, by, bz);
			BlockState state = level.getBlockState(pos);
			if (state.canOcclude()) {
				run++;
				if (run >= OPAQUE_RUN_REQUIRED) {
					return true;
				}
			} else {
				run = 0;
			}
		}
		return false;
	}
}
