package com.endiq.beryllium;

import com.endiq.beryllium.compat.CompatibilityChecker;
import com.endiq.beryllium.config.BerylliumConfig;
import com.endiq.beryllium.device.DeviceDetector;
import com.endiq.beryllium.device.DeviceInfo;
import com.endiq.beryllium.util.BerylliumLog;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.util.List;
import java.util.Optional;

public class Beryllium implements ModInitializer {
	public static final String MOD_ID = "beryllium";

	private static BerylliumConfig config;
	private static volatile boolean deferCullingToOtherMod = false;

	@Override
	public void onInitialize() {
		config = BerylliumConfig.load();
		BerylliumLog.setDebugEnabled(config.debugMode);

		if (!config.enabled) {
			BerylliumLog.info("Disabled via beryllium.json (\"enabled\": false) — skipping initialization.");
			return;
		}

		checkCompatibility();
		logStartupBanner();
	}

	public static BerylliumConfig config() {
		return config;
	}

	/** True if a compatible/overlapping mod is loaded that Beryllium's own behind-camera
	 *  culling should defer to. Checked by {@code EntityRenderDispatcherMixin} in
	 *  addition to the config toggle. */
	public static boolean isCullingDeferredToOtherMod() {
		return deferCullingToOtherMod;
	}

	private void checkCompatibility() {
		if (!config.compatibilityModeEnabled) {
			return;
		}

		List<String> loaded = CompatibilityChecker.detectLoadedOptimizationMods();
		if (loaded.isEmpty()) {
			return;
		}

		BerylliumLog.compat("Detected optimization mods: " + String.join(", ", loaded));

		if (CompatibilityChecker.shouldDeferBehindCameraCullingTo(loaded)) {
			deferCullingToOtherMod = true;
			BerylliumLog.compat("EntityCulling is loaded — deferring to it for entity culling; "
				+ "Beryllium's own behind-camera culling is disabled for this session to avoid "
				+ "two mods independently deciding whether to skip rendering the same entity.");
		}
	}

	private void logStartupBanner() {
		DeviceInfo device = DeviceDetector.detect();

		BerylliumLog.info("Beryllium " + versionOf(MOD_ID));
		BerylliumLog.info("Minecraft Version: " + versionOf("minecraft"));
		BerylliumLog.info("Fabric Loader: " + versionOf("fabricloader"));
		BerylliumLog.info("Fabric API: " + versionOf("fabric-api"));
		BerylliumLog.info("Java Version: " + device.javaVersion());
		BerylliumLog.info("OS: " + device.osName() + " " + device.osVersion() + " (" + device.osArch() + ")");
		BerylliumLog.info("CPU: " + device.cpuModel() + " (" + device.cpuCores() + " threads)");

		if (device.totalRamBytes() >= 0) {
			BerylliumLog.info(String.format("RAM: %.1f GB total, %.1f GB max heap",
				device.totalRamGigabytes(), device.maxHeapGigabytes()));
		} else {
			BerylliumLog.info(String.format("RAM: unknown total, %.1f GB max heap", device.maxHeapGigabytes()));
		}

		BerylliumLog.info("Debug mode: " + (config.debugMode ? "enabled" : "disabled"));

		if (FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT) {
			BerylliumLog.info("GPU/display info will follow once the client window has started.");
		}
	}

	private static String versionOf(String modId) {
		Optional<ModContainer> container = FabricLoader.getInstance().getModContainer(modId);
		if (container.isEmpty()) {
			return "not present";
		}
		return container.get().getMetadata().getVersion().getFriendlyString();
	}
}
