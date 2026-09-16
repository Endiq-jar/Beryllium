package com.endiq.beryllium.mixin.text;

import com.endiq.beryllium.text.SignTextState;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Opens and closes Beryllium's "we are rendering a sign" scope.
 *
 * <p>Hooking the dispatcher rather than {@code SignRenderer} is deliberate. Every block
 * entity in the game is rendered through this one call, and hanging signs extend
 * {@link SignBlockEntity}, so a single {@code instanceof} here covers standing signs and
 * hanging signs alike without Beryllium referring to a single version-specific renderer
 * class — which matters, because those classes do not exist across the whole range of
 * Minecraft releases this mod builds for.
 *
 * <p>The scope is what lets the font-level mixin tell sign text apart from every other
 * piece of text in the game, and it is reset every frame as well, so a renderer that never
 * reaches its return can never leak the scope into the next frame.
 */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class SignTextScopeMixin {

	@Inject(
		method = "render(Lnet/minecraft/world/level/block/entity/BlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;)V",
		at = @At("HEAD"),
		require = 0
	)
	private void beryllium$beginSignScope(
		BlockEntity blockEntity, float tickDelta, PoseStack poseStack,
		MultiBufferSource bufferSource, CallbackInfo ci
	) {
		if (blockEntity instanceof SignBlockEntity) {
			SignTextState.begin(blockEntity);
		}
	}

	@Inject(
		method = "render(Lnet/minecraft/world/level/block/entity/BlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;)V",
		at = @At("RETURN"),
		require = 0
	)
	private void beryllium$endSignScope(
		BlockEntity blockEntity, float tickDelta, PoseStack poseStack,
		MultiBufferSource bufferSource, CallbackInfo ci
	) {
		if (blockEntity instanceof SignBlockEntity) {
			SignTextState.end();
		}
	}
}
