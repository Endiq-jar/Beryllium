package com.endiq.beryllium.culling;

import com.endiq.beryllium.Beryllium;

/**
 * Coordination with the level-of-detail terrain renderers — Distant Horizons and Voxy — so
 * that Beryllium's culling and theirs do not contradict each other at the seam where one
 * stops and the other begins.
 *
 * <h2>The actual conflict</h2>
 *
 * <p>It is not that both mods cull the same thing. Distant Horizons draws simplified terrain
 * far beyond the vanilla render distance and culls its own LOD chunks; Voxy replaces the
 * terrain renderer entirely. Neither of them renders entities or block entities, so there is
 * no entity for Beryllium to fight over.
 *
 * <p>The conflict is over <em>how far the player can see</em>. Every distance-based decision
 * Beryllium makes is derived, directly or by default, from vanilla's render distance — and
 * with an LOD renderer installed that number no longer describes the visible world. A render
 * distance of 12 chunks next to Distant Horizons configured for 128 means the player is
 * looking at terrain two thousand blocks away while Beryllium reasons as though the world
 * ended at 192. An entity culling ceiling chosen for the smaller world then sits inside the
 * larger one, and the symptom is entities and name tags disappearing against a landscape that
 * is plainly still being drawn.
 *
 * <h2>What this does about it</h2>
 *
 * <ul>
 *   <li>A distance ceiling is never allowed to come out <em>smaller</em> than the live render
 *       distance when an LOD renderer is present, whatever the configured static default says
 *       and whatever the sync option is set to. Syncing only ever pushes a ceiling out; this
 *       makes that guarantee hold on an install where the unsynced default was calibrated for
 *       a world that no longer ends where vanilla says it does.</li>
 *   <li>Beryllium's own terrain renderer stays off. This is the compatibility half of the rule
 *       that a single atomic switch decides who draws the ground, and that both renderers are
 *       never drawing it at once.</li>
 * </ul>
 *
 * <p>Frustum culling is deliberately left alone. Which direction the camera is pointing is not
 * a matter of opinion between two renderers, so a frustum test agrees with an LOD renderer's
 * own frustum test and there is no seam to coordinate.
 *
 * <h2>What is deliberately not floored</h2>
 *
 * <p>Two distance-derived values keep using {@link RenderDistanceSync} directly:
 * <ul>
 *   <li><strong>The behind-camera aggressive distance.</strong> That threshold governs how
 *       strictly geometry <em>behind</em> the player is culled. An LOD renderer changes how
 *       far ahead the player can see, not what is behind them, so there is no seam to protect
 *       and flooring it would only weaken a cull that is safe at any distance.</li>
 *   <li><strong>Sign text ranges.</strong> Sign text is legible at a few dozen blocks at most;
 *       its configured range is 16-24. Flooring it at the render distance would push it to
 *       ~192 blocks and cost real frame time drawing text nobody can read, to fix a symptom
 *       that cannot occur.</li>
 * </ul>
 */
public final class LodRendererCoordination {
	private LodRendererCoordination() {
	}

	/**
	 * Whether a level-of-detail renderer owns the far field on this install.
	 *
	 * <p>Decided once at startup by {@link Beryllium#isLodRendererPresent()} rather than
	 * re-detected per call: this sits on the entity render path, and the set of loaded mods
	 * cannot change while the game is running.
	 */
	public static boolean ownsFarTerrain() {
		return Beryllium.isLodRendererPresent();
	}

	/**
	 * Resolves a distance ceiling, accounting for an LOD renderer if one is present.
	 *
	 * <p>Without an LOD renderer this is exactly
	 * {@link RenderDistanceSync#effectiveCullDistance(double, boolean)} — the behaviour does
	 * not change on a normal install. With one, the result is additionally floored at the live
	 * render distance so a static default calibrated for a smaller world cannot pull the
	 * ceiling inside terrain the player can see.
	 *
	 * @param configuredDistance the static distance from config
	 * @param syncEnabled        whether render-distance syncing is turned on
	 */
	public static double distanceCeiling(double configuredDistance, boolean syncEnabled) {
		double resolved = RenderDistanceSync.effectiveCullDistance(configuredDistance, syncEnabled);
		if (!ownsFarTerrain()) {
			return resolved;
		}
		int renderDistanceBlocks = RenderDistanceSync.currentRenderDistanceBlocks();
		if (renderDistanceBlocks <= 0) {
			// The render distance could not be read, so there is nothing to floor against.
			// Falling back to the configured value is what every other caller does.
			return resolved;
		}
		return Math.max(resolved, renderDistanceBlocks);
	}

	/**
	 * Whether Beryllium's own terrain renderer may draw.
	 *
	 * <p>False whenever another mod is responsible for the terrain, which is the case for
	 * Sodium and Voxy (they replace vanilla's renderer) and for Distant Horizons (it draws the
	 * far field a Beryllium renderer would also be drawing).
	 */
	public static boolean mayRenderTerrain() {
		return !Beryllium.isCustomTerrainRenderingDeferred();
	}
}
