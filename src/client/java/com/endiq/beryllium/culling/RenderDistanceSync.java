package com.endiq.beryllium.culling;

import net.minecraft.client.Minecraft;

/**
 * Reads the player's live "Render Distance" video option so distance-based culling can be
 * kept in sync with it, instead of using a fixed block distance that has no relationship to
 * how far the player has actually asked to see.
 *
 * <p>Without this, {@code cullAggressiveDistance} and {@code nameTagCullRange} are flat
 * constants (48 blocks by default). That is fine at vanilla's old default of 8-12 chunks, but
 * on a large render distance (the mobile/low-end-desktop population this mod targets often
 * pushes render distance up specifically to see further) it means entities and name tags get
 * culled tens or hundreds of blocks before the terrain itself would even stop rendering —
 * players "disappear" or lose their name tag well inside what should be visible range. Tying
 * the effective cull distance to the current render distance keeps culling from ever kicking
 * in closer than the player can actually see.
 *
 * <p>Deliberately defensive: any unexpected state (no client instance yet, options not
 * initialized) returns {@code -1} so callers can fall back to their configured static default
 * rather than culling based on a bogus distance.
 */
public final class RenderDistanceSync {
	private RenderDistanceSync() {
	}

	private static final int BLOCKS_PER_CHUNK = 16;

	/**
	 * @return the current render distance in blocks (chunks * 16), or -1 if it could not be
	 *         read (no client instance, options not yet initialized, or an unexpected error).
	 */
	public static int currentRenderDistanceBlocks() {
		try {
			Minecraft minecraft = Minecraft.getInstance();
			if (minecraft == null || minecraft.options == null) {
				return -1;
			}

			Integer chunks = minecraft.options.renderDistance().get();
			if (chunks == null || chunks <= 0) {
				return -1;
			}

			return chunks * BLOCKS_PER_CHUNK;
		} catch (Throwable t) {
			// Never let this take a culling decision down with it.
			return -1;
		}
	}

	/**
	 * Resolves the effective cull distance: the larger of {@code configuredDistance} and the
	 * live render distance (when {@code syncEnabled} and the render distance can be read) —
	 * so syncing only ever pushes the cull range out to match what the player can see, never
	 * pulls it in tighter than what was explicitly configured.
	 *
	 * @param configuredDistance the static fallback/floor distance from config
	 * @param syncEnabled        whether render-distance syncing is turned on
	 */
	public static double effectiveCullDistance(double configuredDistance, boolean syncEnabled) {
		if (!syncEnabled) {
			return configuredDistance;
		}

		int renderDistanceBlocks = currentRenderDistanceBlocks();
		if (renderDistanceBlocks <= 0) {
			return configuredDistance;
		}

		return Math.max(configuredDistance, renderDistanceBlocks);
	}
}
