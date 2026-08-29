package com.endiq.beryllium.mixin.culling;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.culling.BehindCameraCulling;
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

		Vec3 camPos = camera.getPosition();
		Vector3f forward = camera.getLookVector();

		boolean behind = BehindCameraCulling.isBehindCamera(
			camPos.x, camPos.y, camPos.z,
			forward.x(), forward.y(), forward.z(),
			x, y, z,
			Beryllium.config().cullSafeRadius,
			Beryllium.config().cullAggressiveDistance,
			Beryllium.config().cullDotThresholdNear,
			Beryllium.config().cullDotThresholdFar
		);

		if (behind) {
			cir.setReturnValue(false);
		}
	}
}
