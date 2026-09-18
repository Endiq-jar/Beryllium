package com.endiq.beryllium.render;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.config.BerylliumConfig;
import com.endiq.beryllium.culling.VisibilityCulling;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Beacon optimisation: deciding whether a beacon's beam is worth drawing at all.
 *
 * <p>A beacon beam is one of the most expensive small things in Minecraft. It is a tall
 * translucent column with its own render type, it is drawn even when the beacon itself is
 * nowhere near the player's view, and — crucially — vanilla renders it at any distance
 * inside the block entity radius, so a base with a ring of beacons pays for every beam
 * every frame.
 *
 * <p>Two rules, both conservative:
 * <ul>
 *   <li><strong>Distance.</strong> Beyond {@code beaconBeamCullDistance} the beam is
 *       dropped. A beam is a large, soft, translucent shape; past a few dozen blocks it
 *       contributes almost nothing to what the player can identify.</li>
 *   <li><strong>Out of view.</strong> The beam is treated as the tall column it actually
 *       is — from the beacon up to the world's build height — and tested against the frame's
 *       frustum. Turning away from a beacon stops costing anything, and looking at one
 *       still shows the whole beam, because the column (not the block) is what is tested.</li>
 * </ul>
 */
public final class BeaconBeamCulling {
	/** Horizontal half-width of the column test; the beam is narrow, this is generous. */
	private static final double BEAM_RADIUS = 1.25;

	private BeaconBeamCulling() {
	}

	/**
	 * @return true if the beam render should be skipped entirely
	 */
	public static boolean shouldCullBeam(BlockEntity beacon) {
		BerylliumConfig config = Beryllium.config();
		if (config == null || !config.enabled || !config.beaconOptimization) {
			return false;
		}
		if (Beryllium.isBlockEntityCullingDeferredToOtherMod()) {
			return false;
		}
		if (beacon == null) {
			return false;
		}

		BlockPos pos = beacon.getBlockPos();
		double distanceSq = VisibilityCulling.distanceSqToCamera(pos);
		if (distanceSq < 0.0) {
			return false; // camera unavailable — never cull blind
		}

		if (config.beaconBeamHideAtDistance && config.beaconBeamCullDistance > 0.0) {
			if (distanceSq > config.beaconBeamCullDistance * config.beaconBeamCullDistance) {
				return true;
			}
		}

		if (config.beaconBeamHideOutOfView) {
			Level level = beacon.getLevel();
			double ceiling = VisibilityCulling.ceilingOf(level, pos);
			if (!VisibilityCulling.isColumnVisible(pos, BEAM_RADIUS, ceiling)) {
				return true;
			}
		}

		return false;
	}
}
