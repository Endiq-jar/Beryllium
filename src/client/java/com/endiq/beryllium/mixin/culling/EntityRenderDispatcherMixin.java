package com.endiq.beryllium.mixin.culling;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.config.BerylliumConfig;
import com.endiq.beryllium.culling.BehindCameraCulling;
import com.endiq.beryllium.culling.FogCulling;
import com.endiq.beryllium.culling.OcclusionCulling;
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
		BerylliumConfig config = Beryllium.config();
		if (config == null || !config.enabled) {
			return;
		}
		if (camera == null) {
			return;
		}

		Vec3 camPos = camera.getPosition();
		Vector3f forward = camera.getLookVector();

		// Behind-camera cull: its own toggle, deferred to EntityCulling when that
		// mod owns the same decision.
		if (config.cullBehindCameraEntities && !Beryllium.isCullingDeferredToOtherMod()) {
			boolean behind = BehindCameraCulling.isBehindCamera(
				camPos.x, camPos.y, camPos.z,
				forward.x(), forward.y(), forward.z(),
				x, y, z,
				config.cullSafeRadius,
				config.cullAggressiveDistance,
				config.cullDotThresholdNear,
				config.cullDotThresholdFar
			);
			if (behind) {
				cir.setReturnValue(false);
				return;
			}
		}

		// Phase 12 — fog-wall cull: the outermost fringe of the render distance is
		// already fully fogged over, so an entity model there is unreadable; skip it
		// instead of paying a render call (plus its shadow pass) for fog colour.
		// Independent toggle (enforced inside FogCulling via cullFogHiddenContent)
		// and intentionally NOT deferred to EntityCulling: that mod decides by
		// visibility direction, this decides by fog range — and two mods both
		// skipping the same entity is harmless (skips are idempotent), unlike two
		// mods both deciding to draw it.
		if (FogCulling.isBeyondFogWall(camPos.x, camPos.y, camPos.z, x, y, z,
			config.fogCullSafeRadius)) {
			FogCulling.noteHiddenEntity();
			cir.setReturnValue(false);
			return;
		}

		// Phase 13 — occlusion cull ("EntityCulling technology"): an entity behind
		// solid terrain is still frustum-visible to vanilla. Trace the entity's
		// silhouette against the voxel world and skip the render call when every ray
		// is blocked by opaque blocks. Budgeted and cached inside OcclusionCulling;
		// glowing entities, the camera entity and anything ambiguous are never culled.
		if (OcclusionCulling.isOccluded(entity, camPos.x, camPos.y, camPos.z)) {
			cir.setReturnValue(false);
		}
	}
}
