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
	}
}
