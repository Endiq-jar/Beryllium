package com.endiq.beryllium.render;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.config.BerylliumConfig;
import com.endiq.beryllium.culling.VisibilityCulling;
import net.minecraft.world.level.block.entity.BlockEntity;

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
 * which a chest stops being more than a smudge) the renderer call is skipped outright.
 * Closer than that nothing changes, and off-screen chests are already handled by
 * Beryllium's block entity frustum culling, so the two rules compose instead of competing.
 */
public final class ChestRenderCulling {
	private ChestRenderCulling() {
	}

	/**
	 * @return true if this chest's renderer should be skipped
	 */
	public static boolean shouldCullChest(BlockEntity chest) {
		BerylliumConfig config = Beryllium.config();
		if (config == null || !config.enabled || !config.chestRenderCulling) {
			return false;
		}
		if (Beryllium.isBlockEntityCullingDeferredToOtherMod()) {
			return false;
		}
		if (chest == null || config.chestRenderCullDistance <= 0.0) {
			return false;
		}

		double distanceSq = VisibilityCulling.distanceSqToCamera(chest.getBlockPos());
		if (distanceSq < 0.0) {
			return false;
		}
		return distanceSq > config.chestRenderCullDistance * config.chestRenderCullDistance;
	}
}
