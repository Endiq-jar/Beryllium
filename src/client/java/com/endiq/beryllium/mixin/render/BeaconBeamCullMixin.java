package com.endiq.beryllium.mixin.render;

import com.endiq.beryllium.render.BeaconBeamCulling;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BeaconRenderer;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skips {@link BeaconRenderer} entirely when the beam cannot be seen.
 *
 * <p>The beacon block itself is an ordinary block model, so everything this renderer draws
 * is the beam: cancelling the call removes the beam and nothing else. See
 * {@link BeaconBeamCulling} for the two rules (distance, and a column-shaped frustum test
 * that covers the full height a beam can reach).
 */
@Mixin(BeaconRenderer.class)
public abstract class BeaconBeamCullMixin {

	@Inject(
		method = "render(Lnet/minecraft/world/level/block/entity/BeaconBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
		at = @At("HEAD"),
		cancellable = true,
		require = 0
	)
	private void beryllium$cullBeaconBeam(
		BeaconBlockEntity beacon, float tickDelta, PoseStack poseStack,
		MultiBufferSource bufferSource, int light, int overlay, CallbackInfo ci
	) {
		if (BeaconBeamCulling.shouldCullBeam(beacon)) {
			ci.cancel();
		}
	}
}
