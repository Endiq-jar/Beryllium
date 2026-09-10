package com.endiq.beryllium.tune;

import com.endiq.beryllium.config.BerylliumConfig;
import com.endiq.beryllium.util.BerylliumLog;

/**
 * Phase 13 — "maximum FPS, maximum visuals": a one-shot preset that sets only the
 * options which cost frames <em>without</em> changing what the game looks like.
 *
 * <p>The mobile auto-tuner ({@link MobileTuner}) trades visuals for speed on weak
 * tiers (particles, shadows, clouds). This preset is the opposite posture, aimed at
 * players who want every fancy visual left exactly as they set it while removing the
 * non-visual frame costs vanilla ships with:
 *
 * <ul>
 *   <li><b>VSync off</b> — removes the driver's frame-pacing wait, the single largest
 *       framerate ceiling on most machines. Screen tearing is a monitor property, not
 *       a world-rendering property; players who prefer VSync can re-enable it in one
 *       click in the video settings screen.</li>
 *   <li><b>Framerate limit unlocked</b> — vanilla's default cap throttles the very
 *       headroom this project exists to create.</li>
 *   <li><b>Simulation distance at vanilla's minimum</b> — entity AI, block ticks,
 *       redstone, growth and mob spawning are simulated in a radius that does not need
 *       to match the render distance on a machine that is already GPU/CPU bound.
 *       Terrain <em>looks</em> identical (chunks still render at the render distance);
 *       only simulation work in the distance drops, which is exactly the trade every
 *       competitive player makes deliberately.</li>
 * </ul>
 *
 * <p>Nothing else is touched: graphics mode, smooth lighting, particles, clouds, entity
 * shadows, biome blend, view bobbing, entity distance scaling, AO — all left at the
 * player's own values. Runs once (persisted via {@code maxFpsPresetApplied}), logs
 * every change, and every individual write is reflective best-effort: an option that
 * cannot be found on this build is skipped and reported, never fatal.
 */
public final class MaxFpsPreset {
	private MaxFpsPreset() {
	}

	/** The virtual maximum vanilla's framerate slider uses for "unlimited". */
	private static final int FRAMERATE_UNLIMITED = 260;

	/** Vanilla's lowest simulation-distance slider value. */
	private static final int SIMULATION_DISTANCE_MIN = 5;

	public static void applyIfEligible(BerylliumConfig config) {
		if (config == null || !config.enabled || !config.fancyMaxFpsPreset || config.maxFpsPresetApplied) {
			return;
		}

		BerylliumLog.info("[BERYLLIUM-MAXFPS] Applying the max-FPS preset (visual settings are left"
			+ " untouched; only non-visual frame costs are removed)...");
		int applied = 0;

		if (ClientOptions.set("enableVsync", Boolean.FALSE)) {
			applied++;
			BerylliumLog.info("  - enableVsync = false");
		} else {
			BerylliumLog.info("  - enableVsync: skipped (option not found or not settable).");
		}

		if (ClientOptions.set("framerateLimit", FRAMERATE_UNLIMITED)) {
			applied++;
			BerylliumLog.info("  - framerateLimit = " + FRAMERATE_UNLIMITED + " (unlimited)");
		} else {
			BerylliumLog.info("  - framerateLimit: skipped (option not found or not settable).");
		}

		if (ClientOptions.set("simulationDistance", SIMULATION_DISTANCE_MIN)) {
			applied++;
			BerylliumLog.info("  - simulationDistance = " + SIMULATION_DISTANCE_MIN
				+ " (vanilla minimum; visual distance is unchanged)");
		} else {
			BerylliumLog.info("  - simulationDistance: skipped (option not found or not settable).");
		}

		if (applied > 0) {
			ClientOptions.save();
			config.maxFpsPresetApplied = true;
			config.save();
			BerylliumLog.info("[BERYLLIUM-MAXFPS] Preset complete: " + applied
				+ " non-visual options applied and saved. Fancy visuals, particles, clouds, shadows"
				+ " and lighting are untouched; restore the defaults any time in the video settings screen.");
		} else {
			BerylliumLog.warn("[BERYLLIUM-MAXFPS] Preset applied nothing (options API mismatch on this"
				+ " build); leaving vanilla settings untouched.");
		}
	}
}
