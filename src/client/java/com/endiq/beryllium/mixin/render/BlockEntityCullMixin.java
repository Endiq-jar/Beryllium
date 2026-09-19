package com.endiq.beryllium.mixin.render;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.config.BerylliumConfig;
import com.endiq.beryllium.culling.BlockEntityCulling;
import com.endiq.beryllium.render.ChestRenderCulling;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skips drawing a block entity whose (inflated) bounding box is outside the camera's frustum
 * and far enough away that popping is not noticeable — plus the chest-specific distance rule
 * from {@link ChestRenderCulling}.
 *
 * <p>Vanilla iterates all block entities within a fixed radius of the camera and draws each
 * one, regardless of facing; in scenes full of signs, banners, item chests, item frames and
 * redstone comparators this is a large, easy win. See {@link BlockEntityCulling} for the
 * geometry and the safety guards (safe radius, all-or-nothing error handling).
 *
 * <p>This is the direct-render hook: through 1.21.8 the dispatcher draws a block entity when
 * it is asked to, so cancelling that call is the whole job. From 1.21.9 the pipeline became
 * "extract a render state, then submit it", where the way to say "nothing to draw" is to
 * return no state at all — and that needs the render state's type in the callback, so it
 * lives in its own file ({@code BlockEntityExtractMixin}) and this hook is simply skipped
 * there by {@code require = 0}.
 *
 * <p>Disabled automatically when the EntityCulling mod is present (it does the same job),
 * and independently toggleable via {@code cullBlockEntities} in beryllium.json.
 */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityCullMixin {

	// --- through 1.21.x / 26.0 ---------------------------------------------------------

	@Inject(
		method = "render(Lnet/minecraft/world/level/block/entity/BlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;)V",
		at = @At("HEAD"),
		cancellable = true,
		require = 0
	)
	private void beryllium$cullInvisibleBlockEntities(
		BlockEntity blockEntity, float tickDelta, PoseStack poseStack,
		@Coerce Object bufferSource, CallbackInfo ci
	) {
		if (beryllium$shouldCull(blockEntity)) {
			ci.cancel();
		}
	}

	/**
	 * One decision shared by every hook. Chest culling (24 blocks by default) comes first
	 * because it is unconditional and independent of the frustum rule below.
	 */
	@Unique
	private boolean beryllium$shouldCull(BlockEntity blockEntity) {
		if (ChestRenderCulling.shouldCullChest(blockEntity)) {
			return true;
		}

		BerylliumConfig config = Beryllium.config();
		if (config == null || !config.enabled || !config.cullBlockEntities) {
			return false;
		}
		if (Beryllium.isBlockEntityCullingDeferredToOtherMod()) {
			return false;
		}
		return BlockEntityCulling.shouldCull(blockEntity, config.blockEntityCullSafeRadius);
	}
}
