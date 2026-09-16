package com.endiq.beryllium.mixin.render;

import com.endiq.beryllium.render.ChestRenderCulling;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.EnderChestRenderer;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The ender-chest half of chest render culling. Ender chests have their own renderer (and
 * their own translucent lid), so they need their own hook; the rule and the distance are
 * shared with {@link ChestRenderCullMixin}.
 */
@Mixin(EnderChestRenderer.class)
public abstract class EnderChestRenderCullMixin {

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
