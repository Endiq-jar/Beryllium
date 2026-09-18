package com.endiq.beryllium.render;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.config.BerylliumConfig;
import com.endiq.beryllium.culling.VisibilityCulling;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;

/**
 * Chest render culling.
 *
 * <p>Vanilla renders every chest — and every trapped chest and ender chest — within its
 * fixed block entity radius, complete with lid animation and (for ender chests) a
 * translucent box, regardless of how far away it is. A storage hall with a wall of chests
 * is the classic case: hundreds of animated models, none of them more than a few pixels
 * tall on screen, all of them costing draw calls and vertex work every frame.
 *
 * <p>Beyond {@code chestRenderCullDistance} (24 blocks by default — roughly the distance at
 * which a chest stops being more than a smudge) the block entity is not drawn at all.
 * Closer than that nothing changes, and off-screen chests are already handled by
 * Beryllium's block entity frustum culling, so the two rules compose instead of competing.
 *
 * <p>This is decided in the block entity dispatcher hook rather than in a mixin per chest
 * renderer: the renderer classes themselves were reshaped repeatedly inside the supported
 * range of releases (ender chests stopped having a renderer class of their own entirely),
 * while every chest — normal, trapped, ender, Christmas — is still an instance of one of
 * the two block entity types tested here. One rule covers all of them on every release.
 */
public final class ChestRenderCulling {
	private ChestRenderCulling() {
	}

	/**
	 * @return true if this block entity is a chest that is too far away to draw
	 */
	public static boolean shouldCullChest(BlockEntity blockEntity) {
		if (blockEntity == null) {
			return false;
		}
		BerylliumConfig config = Beryllium.config();
		if (config == null || !config.enabled || !config.chestRenderCulling) {
			return false;
		}
		if (Beryllium.isBlockEntityCullingDeferredToOtherMod()) {
			return false;
		}
		if (config.chestRenderCullDistance <= 0.0) {
			return false;
		}
		// Ender chests have no renderer class of their own any more; the type test is what
		// stays true across the whole range.
		if (!(blockEntity instanceof ChestBlockEntity) && !(blockEntity instanceof EnderChestBlockEntity)) {
			return false;
		}

		double distanceSq = VisibilityCulling.distanceSqToCamera(blockEntity.getBlockPos());
		if (distanceSq < 0.0) {
			return false;
		}
		return distanceSq > config.chestRenderCullDistance * config.chestRenderCullDistance;
	}
}
