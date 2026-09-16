package com.endiq.beryllium.mixin.render;

import com.endiq.beryllium.render.ChestRenderCulling;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.ChestRenderer;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skips chest rendering beyond {@code chestRenderCullDistance} (24 blocks by default).
 *
 * <p>Chests, trapped chests and (see {@code EnderChestRenderCullMixin}) ender chests are
 * the block entities most likely to exist in the hundreds in one place, and each one costs
 * a model with a lid animation every frame. At 24 blocks a chest is a few pixels tall;
 * past that the animation is invisible and the draw call is not.
 *
 * <p>{@code ChestRenderer} is generic in the chest block entity type, so the erased
 * descriptor is used here; if a future release reshapes it, this mixin no-ops
 * ({@code require = 0}) and chests render as they always did.
 */
@Mixin(ChestRenderer.class)
public abstract class ChestRenderCullMixin {

	@Inject(
		method = "render(Lnet/minecraft/world/level/block/entity/ChestBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
		at = @At("HEAD"),
		cancellable = true,
		require = 0
	)
	private void beryllium$cullDistantChest(
		ChestBlockEntity chest, float tickDelta, PoseStack poseStack,
		MultiBufferSource bufferSource, int light, int overlay, CallbackInfo ci
	) {
		if (ChestRenderCulling.shouldCullChest(chest)) {
			ci.cancel();
		}
	}
}
