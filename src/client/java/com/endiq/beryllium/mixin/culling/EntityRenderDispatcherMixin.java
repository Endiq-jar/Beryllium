package com.endiq.beryllium.mixin.culling;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.culling.BehindCameraCulling;
import com.endiq.beryllium.config.BerylliumConfig;
import com.endiq.beryllium.culling.RenderDistanceSync;
import com.endiq.beryllium.culling.VisibilityCulling;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {
	@Shadow
	public Camera camera;

	@Inject(method = "shouldRender", at = @At("RETURN"), cancellable = true)
	private <E extends Entity> void beryllium$cullEntitiesBehindCamera(
		E entity, Frustum frustum, double x, double y, double z, CallbackInfoReturnable<Boolean> cir
	) {
		// Vanilla already said "don't render" — nothing for us to do.
		if (!cir.getReturnValueZ()) {
			return;
		}
		if (!Beryllium.config().enabled || !Beryllium.config().cullBehindCameraEntities) {
			return;
		}
		if (Beryllium.isCullingDeferredToOtherMod()) {
			return;
		}
		if (camera == null) {
			return;
		}

		Vec3 camPos = VisibilityCulling.cameraPosition();
		Vector3f forward = VisibilityCulling.cameraLookVector();
		if (camPos == null || forward == null) {
			// Camera access is resolved per Minecraft release; a miss means no behind-camera
			// or render-distance decision this frame (vanilla rendering continues).
			return;
		}

		// --- Entity render distance -------------------------------------------------
		// A hard ceiling on how far away an entity may be and still be drawn. Vanilla has
		// no such limit: it renders every entity inside the loaded chunk area, so a
		// crowded server keeps paying for entities the player could never pick out of the
		// terrain. Unlike the cull ranges above this is a cap, not a floor, so it is not
		// render-distance-synced by default.
		BerylliumConfig config = Beryllium.config();
		if (config == null) {
			return;
		}
		if (config.entityRenderCulling && config.entityRenderCullDistance > 0.0) {
			double maxDistance = config.entityRenderCullSyncWithRenderDistance
				? RenderDistanceSync.effectiveCullDistance(config.entityRenderCullDistance, true)
				: config.entityRenderCullDistance;
			double dx = x - camPos.x;
			double dy = y - camPos.y;
			double dz = z - camPos.z;
			if (dx * dx + dy * dy + dz * dz > maxDistance * maxDistance) {
				cir.setReturnValue(false);
				return;
			}
		}

		double aggressiveDistance = RenderDistanceSync.effectiveCullDistance(
			Beryllium.config().cullAggressiveDistance,
			Beryllium.config().cullRangeSyncWithRenderDistance
		);

		boolean behind = BehindCameraCulling.isBehindCamera(
			camPos.x, camPos.y, camPos.z,
			forward.x(), forward.y(), forward.z(),
			x, y, z,
			Beryllium.config().cullSafeRadius,
			aggressiveDistance,
			Beryllium.config().cullDotThresholdNear,
			Beryllium.config().cullDotThresholdFar
		);

		if (behind) {
			cir.setReturnValue(false);
		}
	}
}
