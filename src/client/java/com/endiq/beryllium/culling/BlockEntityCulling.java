package com.endiq.beryllium.culling;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Decides whether a block entity's render call can be skipped entirely because it is not
 * visible to the player.
 *
 * <p>Vanilla renders every block entity within a fixed radius of the camera regardless of
 * facing — a sign farm or a row of banners facing away still costs full render calls. This
 * test reuses the {@link Frustum} {@code LevelRenderer} already built for the frame (looked
 * up reflectively so we do not depend on a private field name) and skips the render when
 * the block's (slightly inflated) bounding box does not intersect the view frustum.
 *
 * <p>The safe-radius guard mirrors {@link BehindCameraCulling}: anything close to the
 * camera is never culled, so anything large enough to matter on screen can never pop out
 * of existence.
 *
 * <p>Everything here is deliberately defensive — any unexpected state (no camera, no
 * frustum, a thrown error) results in "do not cull", because a culling bug that
 * breaks rendering is far worse than a missed optimization.
 */
public final class BlockEntityCulling {
	private BlockEntityCulling() {
	}

	/** How much to grow the culling box beyond the 1x1x1 block, on each side. Item frames,
	 *  beehive occupants and similar render slightly outside their own block; this keeps
	 *  them from being culled while still culling the rest. */
	private static final double BOX_INFLATION = 0.55;

	private static volatile Field[] frustumFields;
	private static volatile boolean frustumFieldsResolved = false;

	/**
	 * @param blockEntity the block entity about to be rendered
	 * @param safeRadius  block entities closer than this distance to the camera are never culled
	 * @return true if the render call should be skipped
	 */
	public static boolean shouldCull(BlockEntity blockEntity, double safeRadius) {
		try {
			if (blockEntity == null) {
				return false;
			}

			Minecraft minecraft = Minecraft.getInstance();
			if (minecraft == null || minecraft.gameRenderer == null) {
				return false;
			}

			Camera camera = minecraft.gameRenderer.getMainCamera();
			if (camera == null) {
				return false;
			}

			BlockPos pos = blockEntity.getBlockPos();

			// Never cull anything close: the frustum is exact, but a stale/mid-update camera
			// plus a 1-block box could shave a pixel off something the player is looking at.
			Vec3 center = Vec3.atCenterOf(pos);
			double safeRadiusSq = safeRadius * safeRadius;
			if (camera.getPosition().distanceToSqr(center) < safeRadiusSq) {
				return false;
			}

			Frustum frustum = currentFrustum(minecraft);
			if (frustum == null) {
				return false;
			}

			AABB box = new AABB(
				pos.getX() - BOX_INFLATION, pos.getY() - BOX_INFLATION, pos.getZ() - BOX_INFLATION,
				pos.getX() + 1.0 + BOX_INFLATION, pos.getY() + 1.0 + BOX_INFLATION, pos.getZ() + 1.0 + BOX_INFLATION
			);

			return !frustum.isVisible(box);
		} catch (Throwable t) {
			// Never let a culling decision take rendering down with it.
			return false;
		}
	}

	/**
	 * Picks the first non-null {@link Frustum} field off {@code Minecraft.levelRenderer}.
	 * 1.21.4 stores the live culling frustum and an optional captured debug frustum;
	 * walking by type (instead of a mapped field name) keeps this compiling and working
	 * across minor mapping churn.
	 */
	private static Frustum currentFrustum(Minecraft minecraft) {
		Object levelRenderer = minecraft.levelRenderer;
		if (levelRenderer == null) {
			return null;
		}

		if (!frustumFieldsResolved) {
			synchronized (BlockEntityCulling.class) {
				if (!frustumFieldsResolved) {
					List<Field> found = new ArrayList<>();
					for (Field field : levelRenderer.getClass().getDeclaredFields()) {
						if (Frustum.class.isAssignableFrom(field.getType())) {
							field.setAccessible(true);
							found.add(field);
						}
					}
					frustumFields = found.toArray(Field[]::new);
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
	}
}
