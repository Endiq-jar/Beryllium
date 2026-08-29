package com.endiq.beryllium.mixin.text;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.config.BerylliumConfig;
import com.endiq.beryllium.text.TextCulling;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * NOT COMPILE-VERIFIED — see EntityRenderDispatcherMixin's general note on this sandbox
 * lacking Fabric/Mojang maven access.
 *
 * Hooks {@code EntityRenderer#shouldShowName}, the same decision point vanilla already
 * uses to decide "is this entity's name tag worth drawing at all" (distance, sneaking,
 * etc.) — this mirrors the {@code shouldRender} hook style used by
 * {@link com.endiq.beryllium.mixin.culling.EntityRenderDispatcherMixin}, injecting at the
 * decision point rather than the draw call itself.
 *
 * <p>Two overloads of {@code shouldShowName} exist across recent versions: an older one
 * taking just the entity, and a newer one that also takes a precomputed
 * {@code distanceToCameraSq} to avoid a second sqrt. Both are targeted here — whichever
 * one doesn't exist on 1.21.4 will simply fail Mixin's method match, which needs a real
 * decompile of {@code EntityRenderer} to confirm. If BOTH fail to apply, that's the signal
 * this guess was wrong in both cases, not that the feature is unfixable.
 */
@Mixin(EntityRenderer.class)
public abstract class NameTagCullMixin {

	@Inject(method = "shouldShowName(Lnet/minecraft/world/entity/Entity;)Z", at = @At("RETURN"), cancellable = true, require = 0)
	private void beryllium$cullNameTagNoDistance(Entity entity, CallbackInfoReturnable<Boolean> cir) {
		beryllium$maybeCull(entity, cir);
	}

	@Inject(method = "shouldShowName(Lnet/minecraft/world/entity/Entity;D)Z", at = @At("RETURN"), cancellable = true, require = 0)
	private void beryllium$cullNameTagWithDistance(Entity entity, double distanceToCameraSq, CallbackInfoReturnable<Boolean> cir) {
		beryllium$maybeCull(entity, cir);
	}

	private void beryllium$maybeCull(Entity entity, CallbackInfoReturnable<Boolean> cir) {
		if (!cir.getReturnValueZ()) {
			return; // vanilla already said no
		}

		BerylliumConfig config = Beryllium.config();
		if (config == null || !config.enabled || !config.cullNameTags) {
			return;
		}
		if (Beryllium.isCullingDeferredToOtherMod()) {
			return;
		}
		if (entity == null) {
			return;
		}

		Minecraft mc = Minecraft.getInstance();
		if (mc.gameRenderer == null || mc.gameRenderer.getMainCamera() == null) {
			return;
		}

		Vec3 camPos = mc.gameRenderer.getMainCamera().getPosition();
		boolean cull = TextCulling.exceedsRange(
			camPos.x, camPos.y, camPos.z,
			entity.getX(), entity.getY(), entity.getZ(),
			config.nameTagCullRange
		);

		if (cull) {
			cir.setReturnValue(false);
		}
	}
}
