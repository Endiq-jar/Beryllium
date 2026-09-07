package com.endiq.beryllium;

import com.endiq.beryllium.config.BerylliumConfig;
import com.endiq.beryllium.platform.LauncherEnvironment;
import com.endiq.beryllium.util.BerylliumLog;
import net.fabricmc.api.ClientModInitializer;

/**
 * Minimal, launcher-safe client entry point for the 1.21.4 renderer profile.
 *
 * <p>Keep native renderer classes out of this class. Fabric creates the client entry
 * point very early; on Android Java launchers, merely resolving an optional GLFW/GL
 * integration before the title screen can be enough to abort startup. The heavier
 * renderer code is isolated in {@link RendererBootstrap} and is never linked when
 * {@code androidSafeMode} selects the conservative path.
 */
public class BerylliumClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		BerylliumConfig config = Beryllium.config();
		if (config == null || !config.enabled) {
			return;
		}

		LauncherEnvironment launcher = LauncherEnvironment.detect();
		if (config.androidSafeMode && launcher.isAndroidJavaLauncher()) {
			// Do this before resolving RendererBootstrap or any Fabric rendering event.
		// Android Java launchers commonly emulate desktop GLFW/OpenGL through a
		// translation layer; startup probing and renderer transformation must be
		// opt-in, never a reason Minecraft cannot reach its title screen.
			BerylliumLog.mobile("Android launcher safe mode is active (" + launcher.describe()
				+ "). Skipping native GPU probes, shader preload, renderer hooks, and frame callbacks. "
				+ "Set androidSafeMode=false only after validating this launcher/renderer pair.");
			return;
		}

		try {
			RendererBootstrap.initialize();
		} catch (LinkageError | RuntimeException failure) {
			// A performance module must never make the client unlaunchable. This also
			// contains missing/partial native bridges supplied by third-party launchers.
			BerylliumLog.error("Renderer bootstrap failed; disabling Beryllium's optional "
				+ "client hooks for this session and leaving vanilla rendering active.", failure);
		}
	}
}
