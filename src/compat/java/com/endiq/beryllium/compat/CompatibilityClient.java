package com.endiq.beryllium.compat;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.config.BerylliumConfig;
import com.endiq.beryllium.platform.LauncherEnvironment;
import com.endiq.beryllium.util.BerylliumLog;
import net.fabricmc.api.ClientModInitializer;

/**
 * Client entry point used by every version other than the separately verified 1.21.4
 * renderer profile.
 *
 * <p>This class deliberately has no Minecraft, Fabric API, LWJGL, or mixin references.
 * Minecraft has changed both its renderer internals and mapping format several times
 * between 1.19.4 and current releases; linking any one of those APIs at class-load time
 * is exactly how a performance mod turns into a launcher crash.  Keeping this entry
 * point Loader-only gives each exact-version artifact a safe baseline that can always
 * load, including in Pojav, Zalith, and TurtleLauncher-style Android environments.
 *
 * <p>The common configuration, compatibility detection, scheduling primitives and
 * Android safety policy are still active. Version-specific renderer hooks are enabled
 * only after they are verified for a profile, rather than guessed on an unrelated game
 * version.
 */
public final class CompatibilityClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		BerylliumConfig config = Beryllium.config();
		LauncherEnvironment launcher = LauncherEnvironment.detect();

		if (config == null || !config.enabled) {
			return;
		}

		// Phase 14 — the compatibility core's FPS layer. Runs *before* the Android safe
		// path below, deliberately: option tuning is the one optimization that does not
		// touch GL, GLFW or any renderer internals (it reflects on the options object
		// from a daemon thread once the client is up), so it is safe exactly where the
		// engine is otherwise most constrained — and that Android phone population is
		// the hardware Beryllium exists for. Every write is individually best-effort;
		// a build whose options shape differs simply logs "skipped".
		CompatTuner.schedule();

		if (launcher.isAndroidJavaLauncher()) {
			if (config.androidSafeMode) {
				BerylliumLog.mobile("Android launcher safe mode is active (" + launcher.describe()
					+ "). This compatibility build intentionally avoids early GLFW/OpenGL calls and "
					+ "version-specific renderer mixins so the game can reach the title screen safely.");
			} else {
				BerylliumLog.mobile("Android launcher detected (" + launcher.describe()
					+ "), but androidSafeMode is disabled by configuration.");
			}
			return;
		}

		BerylliumLog.info("Loaded the mapping-free compatibility client for this Minecraft "
			+ "release. It performs no native renderer work during startup.");

		// Phase 13 — be blunt here, because this is the artifact most users will
		// accidentally install and then report "the mod does nothing". The full
		// renderer profile (voxel-shape suite, all culling, chunk rebuild
		// prioritization, fog-wall/occlusion culling, Dynamic FPS, the max-FPS
		// preset) is the 1.21.4 artifact only; every other release ships this
		// launch-safe core by design, because guessing renderer internals on an
		// unverified version is how a performance mod becomes a startup crash.
		BerylliumLog.info("[BERYLLIUM-PROFILE] This artifact is the cross-version compatibility core:"
			+ " it contains configuration, device detection, launch safety, option tuning and the"
			+ " scheduling primitives, but NO renderer hooks and NO culling. The option tuner"
			+ " (compatPreset, default \"maxfps\") removes VSync, framerate-cap and simulation-distance"
			+ " cost without touching visuals. For the full renderer optimization set (culling,"
			+ " occlusion culling, chunk prioritization, fog-wall culling) install the 1.21.4 build —"
			+ " that is the only version where those hook targets have been verified against the exact"
			+ " game code. On any other version, pair this artifact with Sodium + Lithium +"
			+ " EntityCulling + FerriteCore + ImmediatelyFast + Dynamic FPS, which cover the same ground"
			+ " per-version.");
	}
}
