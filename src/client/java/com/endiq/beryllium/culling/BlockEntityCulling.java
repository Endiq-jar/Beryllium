package com.endiq.beryllium.culling;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Decides whether a block entity's render call can be skipped entirely because it is not
 * visible to the player.
 *
 * <p>Vanilla renders every block entity within a fixed radius of the camera regardless of
 * facing — a sign farm or a row of banners facing away still costs full render calls. This
 * test reproduces the same geometry vanilla's own chunk-section culling uses (a {@link
 * Frustum} built from the camera's projection matrix and position) and skips the render
 * when the block's (slightly inflated) bounding box does not intersect the view frustum.
 *
 * <p>The safe-radius guard mirrors {@link BehindCameraCulling}: anything close to the
 * camera is never culled, so anything large enough to matter on screen can never pop out
 * of existence.
 *
 * <p>Everything here is deliberately defensive — any unexpected state (no camera, no
 * game renderer, a thrown error) results in "do not cull", because a culling bug that
 * breaks rendering is far worse than a missed optimization.
 */
public final class BlockEntityCulling {
	private BlockEntityCulling() {
	}

	/** How much to grow the culling box beyond the 1x1x1 block, on each side. Item frames,
	 *  beehive occupants and similar render slightly outside their own block; this keeps
	 *  them from being culled while still culling the rest. */
	private static final double BOX_INFLATION = 0.55;

	// The camera's projection matrix is resolved reflectively (getter first, public field
	// second) and cached, so this stays correct across point releases of the 1.21.x line
	// regardless of whether the accessor is a method or a field in a given build.
	private static volatile Method projectionGetter;
	private static volatile Field projectionField;
	private static volatile boolean projectionAccessorResolved = false;

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

			Matrix4f projection = projectionMatrixOf(camera);
			if (projection == null) {
				return false;
			}

			Frustum frustum = new Frustum();
			frustum.update(projection, camera.getPosition());

			AABB box = new AABB(
				pos.getX() - BOX_INFLATION, pos.getY() - BOX_INFLATION, pos.getZ() - BOX_INFLATION,
				pos.getX() + 1.0 + BOX_INFLATION, pos.getY() + 1.0 + BOX_INFLATION, pos.getZ() + 1.0 + BOX_INFLATION
			);

			return !frustum.intersectsBox(box);
		} catch (Throwable t) {
			// Never let a culling decision take rendering down with it.
			return false;
		}
	}

	/**
	 * Resolves the camera's projection matrix once (preferring a public getter, then the
	 * public field) and caches the accessor for subsequent frames.
	 */
	private static Matrix4f projectionMatrixOf(Camera camera) {
		if (!projectionAccessorResolved) {
			synchronized (BlockEntityCulling.class) {
				if (!projectionAccessorResolved) {
					try {
						projectionGetter = Camera.class.getMethod("getProjectionMatrix");
					} catch (NoSuchMethodException e) {
						try {
							projectionField = Camera.class.getField("projectionMatrix");
						} catch (Throwable t) {
							// Neither accessor found — culling is simply disabled for BEs.
						}
					}
					projectionAccessorResolved = true;
				}
			}
		}

		try {
			if (projectionGetter != null) {
				return (Matrix4f) projectionGetter.invoke(camera);
			}
			if (projectionField != null) {
				return (Matrix4f) projectionField.get(camera);
			}
		} catch (Throwable t) {
			// Fall through — treat as "cannot decide", i.e. do not cull.
		}
		return null;
	}
}
