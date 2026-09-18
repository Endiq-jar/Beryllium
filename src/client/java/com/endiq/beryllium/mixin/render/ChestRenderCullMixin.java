package com.endiq.beryllium.mixin.render;

import com.endiq.beryllium.render.ChestRenderCulling;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.ChestRenderer;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
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
 * <p>Injectors use erased descriptors and {@code require = 0}: if a release reshapes
 * the renderer, the matching injector no-ops and chests render as they always did.
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

	/**
	 * The ender-chest half of chest render culling. There is no dedicated ender-chest
	 * renderer class anywhere in the supported range (where the renderer reshaped, it took
	 * ender chests with it as a generic parameter), so this overload is targeted by its
	 * erased descriptor and simply does not resolve on releases that render ender chests
	 * through another shape — those keep vanilla rendering ({@code require = 0}).
	 */
	@Inject(
		method = "render(Lnet/minecraft/world/level/block/entity/EnderChestBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
		at = @At("HEAD"),
		cancellable = true,
		require = 0
	)
	private void beryllium$cullDistantEnderChest(
		EnderChestBlockEntity chest, float tickDelta, PoseStack poseStack,
		MultiBufferSource bufferSource, int light, int overlay, CallbackInfo ci
	) {
		if (ChestRenderCulling.shouldCullChest(chest)) {
			ci.cancel();
		}
	}
}
