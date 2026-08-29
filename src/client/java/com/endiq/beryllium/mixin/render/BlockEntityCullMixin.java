package com.endiq.beryllium.mixin.render;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.config.BerylliumConfig;
import com.endiq.beryllium.culling.BlockEntityCulling;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.VertexConsumerProvider;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skips {@link BlockEntityRenderDispatcher#render} entirely for block entities whose
 * (inflated) bounding box is outside the camera's frustum and far enough away that
 * popping is not noticeable.
 *
 * <p>Vanilla iterates all block entities within a fixed radius of the camera and renders
 * each one, regardless of facing; in scenes full of signs, banners, item frames, redstone
 * comparators and similar this is a large, easy win. See {@link BlockEntityCulling} for
 * the geometry and the safety guards (safe radius, all-or-nothing error handling).
 *
 * <p>Disabled automatically when the EntityCulling mod is present (it does the same job),
 * and independently toggleable via {@code cullBlockEntities} in beryllium.json.
 */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityCullMixin {
	@Inject(
		method = "render(Lnet/minecraft/world/level/block/entity/BlockEntity;FLnet/minecraft/client/renderer/VertexConsumerProvider;Lcom/mojang/blaze3d/vertex/PoseStack;II)V",
		at = @At("HEAD"),
		cancellable = true
	)
	private void beryllium$cullInvisibleBlockEntities(
		BlockEntity blockEntity, float tickDelta, VertexConsumerProvider vertexConsumers,
		PoseStack poseStack, int packedLight, int packedOverlay, CallbackInfo ci
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
