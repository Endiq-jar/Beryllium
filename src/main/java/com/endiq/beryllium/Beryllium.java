package com.endiq.beryllium;

import com.endiq.beryllium.config.BerylliumConfig;
import com.endiq.beryllium.device.DeviceDetector;
import com.endiq.beryllium.device.DeviceInfo;
import com.endiq.beryllium.util.BerylliumLog;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.util.Optional;

/**
 * Common entrypoint. Phase 1 scope only: initialization, config, device detection and
 * startup logging. GPU/display info is intentionally NOT queried here — this runs before
 * (or without) a render context existing, so GL calls would either fail or, on a dedicated
 * server, be meaningless. That half of the picture is logged from {@link BerylliumClient}
 * once the client has actually started.
 */
public class Beryllium implements ModInitializer {
	public static final String MOD_ID = "beryllium";

	private static BerylliumConfig config;

	@Override
	public void onInitialize() {
		config = BerylliumConfig.load();
		BerylliumLog.setDebugEnabled(config.debugMode);

		if (!config.enabled) {
			BerylliumLog.info("Disabled via beryllium.json (\"enabled\": false) — skipping initialization.");
			return;
		}

		logStartupBanner();
	}

	public static BerylliumConfig config() {
		return config;
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
