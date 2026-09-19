package com.endiq.beryllium.mixin.render;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.config.BerylliumConfig;
import com.endiq.beryllium.culling.BlockEntityCulling;
import com.endiq.beryllium.render.BlockEntityMeshCache;
import com.endiq.beryllium.render.ChestRenderCulling;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Block entity culling and static block entity meshing on the 26.1+ renderer.
 *
 * <p>From 26.1 the dispatcher no longer renders a block entity when asked: it <em>extracts</em>
 * a render state, and the caller submits it later. Nothing is drawn for a block entity whose
 * extraction returns nothing, which makes this the one place where both decisions can be made —
 * "this block entity cannot be seen" (the frustum and chest rules) and "the game already knows
 * what this looks like" ({@link BlockEntityMeshCache}).
 *
 * <p>It lives in its own file because answering "nothing to extract" means naming the render
 * state type in the callback, and that type only exists from 26.1 on. Built for those releases;
 * older ones use {@link BlockEntityCullMixin}'s direct render hook instead.
 */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityExtractMixin {

	// --- 26.1: (BlockEntity, float, CrumblingOverlay) -----------------------------------

	@Inject(
		method = "tryExtractRenderState(Lnet/minecraft/world/level/block/entity/BlockEntity;FLnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;)Lnet/minecraft/client/renderer/blockentity/state/BlockEntityRenderState;",
		at = @At("HEAD"), cancellable = true, require = 0)
	private void beryllium$skipOrReuse(BlockEntity blockEntity, float partialTick,
			@Coerce Object crumblingOverlay, CallbackInfoReturnable<BlockEntityRenderState> cir) {
		if (beryllium$culled(blockEntity)) {
			cir.setReturnValue(null);
			return;
		}
		Object cached = BlockEntityMeshCache.reuse(blockEntity);
		if (cached instanceof BlockEntityRenderState state) {
			cir.setReturnValue(state);
		}
	}

	@Inject(
		method = "tryExtractRenderState(Lnet/minecraft/world/level/block/entity/BlockEntity;FLnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;)Lnet/minecraft/client/renderer/blockentity/state/BlockEntityRenderState;",
		at = @At("RETURN"), require = 0)
	private void beryllium$remember(BlockEntity blockEntity, float partialTick,
			@Coerce Object crumblingOverlay, CallbackInfoReturnable<BlockEntityRenderState> cir) {
		BlockEntityMeshCache.remember(blockEntity, cir.getReturnValue());
	}

	// --- 26.2+: the same, with the crumbling overlay flag --------------------------------

	@Inject(
		method = "tryExtractRenderState(Lnet/minecraft/world/level/block/entity/BlockEntity;FLnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;Z)Lnet/minecraft/client/renderer/blockentity/state/BlockEntityRenderState;",
		at = @At("HEAD"), cancellable = true, require = 0)
	private void beryllium$skipOrReuseWithOverlay(BlockEntity blockEntity, float partialTick,
			@Coerce Object crumblingOverlay, boolean crumbling, CallbackInfoReturnable<BlockEntityRenderState> cir) {
		if (beryllium$culled(blockEntity)) {
			cir.setReturnValue(null);
			return;
		}
		Object cached = BlockEntityMeshCache.reuse(blockEntity);
		if (cached instanceof BlockEntityRenderState state) {
			cir.setReturnValue(state);
		}
	}

	@Inject(
		method = "tryExtractRenderState(Lnet/minecraft/world/level/block/entity/BlockEntity;FLnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;Z)Lnet/minecraft/client/renderer/blockentity/state/BlockEntityRenderState;",
		at = @At("RETURN"), require = 0)
	private void beryllium$rememberWithOverlay(BlockEntity blockEntity, float partialTick,
			@Coerce Object crumblingOverlay, boolean crumbling, CallbackInfoReturnable<BlockEntityRenderState> cir) {
		BlockEntityMeshCache.remember(blockEntity, cir.getReturnValue());
	}

	/**
	 * The same decision the pre-26.1 hook makes: chests by distance (unconditional), then the
	 * frustum rule, which defers entirely when another mod is already doing it.
	 */
	@Unique
	private boolean beryllium$culled(BlockEntity blockEntity) {
		try {
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
		} catch (Throwable t) {
			// Drawing something that could have been culled is the safe failure.
			return false;
		}
	}
}
