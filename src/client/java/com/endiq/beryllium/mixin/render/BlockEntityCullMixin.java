package com.endiq.beryllium.mixin.render;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.config.BerylliumConfig;
import com.endiq.beryllium.culling.BlockEntityCulling;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityCullMixin {
	@Inject(
		method = "render(Lnet/minecraft/world/level/block/entity/BlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;)V",
		at = @At("HEAD"),
		cancellable = true
	)
	private void beryllium$cullInvisibleBlockEntities(
		BlockEntity blockEntity, float tickDelta, PoseStack poseStack,
		MultiBufferSource bufferSource, CallbackInfo ci
	) {
		BerylliumConfig config = Beryllium.config();
		if (config == null || !config.enabled || !config.cullBlockEntities) {
			return;
		}
		if (Beryllium.isBlockEntityCullingDeferredToOtherMod()) {
			return;
		}

		if (BlockEntityCulling.shouldCull(blockEntity, config.blockEntityCullSafeRadius)) {
			ci.cancel();
		}
	}
}
