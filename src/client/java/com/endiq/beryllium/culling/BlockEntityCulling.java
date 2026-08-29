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

public final class BlockEntityCulling {
	private BlockEntityCulling() {
	}

	private static final double BOX_INFLATION = 0.55;

	private static volatile Field[] frustumFields;
	private static volatile boolean frustumFieldsResolved = false;

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

			return false;
		}
	}

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

			}
		}
		return null;
	}
}
