package com.endiq.beryllium.mixin.culling;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.config.BerylliumConfig;
import com.endiq.beryllium.culling.CameraAccess;
import com.endiq.beryllium.culling.BehindCameraCulling;
import com.endiq.beryllium.culling.RenderDistanceSync;
import com.endiq.beryllium.throttle.LoadPressureMonitor;
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

		Vec3 camPos = CameraAccess.position(camera);
		Vector3f forward = CameraAccess.lookVector(camera);
		if (camPos == null) {
			// No camera position this release: leave the decision entirely to vanilla.
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
			// Dynamic load throttling: while the world is streaming in, entity rendering
			// gives ground to terrain generation, which is on the critical path -- an
			// unmeshed section is a hole the player can see, and a distant entity that is
			// not drawn this frame is not. The scale never goes to zero and never pulls the
			// ceiling inside 16 blocks, so nothing near the player blinks out.
			maxDistance = LoadPressureMonitor.instance().scaledEntityCullDistance(maxDistance);
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
		// Under load the behind-camera test gets stricter rather than laxer: this is the one
		// cull that removes work without removing anything the player could have seen, so a
		// struggling device should start applying it sooner.
		aggressiveDistance = LoadPressureMonitor.instance().scaledAggressiveDistance(aggressiveDistance);

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
