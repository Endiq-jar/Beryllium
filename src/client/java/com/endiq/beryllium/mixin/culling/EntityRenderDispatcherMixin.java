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

/**
 * "Behind-camera" entity culling ("Superb Player culling" in the original request):
 * skips rendering entities that vanilla's frustum/distance check would still allow
 * through, but that are solidly behind the camera and far enough away that skipping
 * them can't cause visible popping.
 *
 * <p>Injects at the tail of {@code EntityRenderDispatcher#shouldRender}, which is the
 * same extension point other entity-culling mods (e.g. EntityCulling) use — it's the
 * single choke point every entity passes through before being drawn, so this can't be
 * bypassed by a different render path and can't accidentally cull something vanilla
 * already decided not to render (we only ever turn a "yes" into a "no", never the
 * reverse).
 *
 * <p>All geometry math lives in {@link BehindCameraCulling}, which is unit-tested on its
 * own with no Minecraft dependency. Everything in <b>this</b> file — the exact Mojang
 * mapping names for {@code Camera#getLookVector}/{@code getPosition} and the
 * {@code camera} field on {@code EntityRenderDispatcher} — could not be checked against
 * the real 1.21.4 Mojang-mapped jar in the environment this was written in (no network
 * access to Mojang/Fabric's servers there). If the build fails here, this is the first
 * place to look; the fix is almost certainly a one-line accessor rename.
 */
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
			Beryllium.config().cullDotThreshold
		);

		if (behind) {
			cir.setReturnValue(false);
		}
	}
}
