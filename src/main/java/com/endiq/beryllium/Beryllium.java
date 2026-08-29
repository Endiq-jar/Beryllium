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
	private static volatile boolean deferBlockEntityCullingToOtherMod = false;
	private static volatile boolean deferChunkOptimizationsToOtherMod = false;

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

	public static boolean isCullingDeferredToOtherMod() {
		return deferCullingToOtherMod;
	}

	public static boolean isBlockEntityCullingDeferredToOtherMod() {
		return deferBlockEntityCullingToOtherMod;
	}

	public static boolean isChunkOptimizationDeferredToOtherMod() {
		return deferChunkOptimizationsToOtherMod;
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

		if (CompatibilityChecker.shouldDeferBlockEntityCullingTo(loaded)) {
			deferBlockEntityCullingToOtherMod = true;
			BerylliumLog.compat("EntityCulling is loaded — deferring to it for block entity culling; "
				+ "Beryllium's frustum culling of block entity render calls is disabled for this "
				+ "session to avoid two mods independently skipping the same render.");
		}

		if (CompatibilityChecker.isChunkRendererReplaced(loaded)) {
			deferChunkOptimizationsToOtherMod = true;
			BerylliumLog.compat("Sodium is loaded — it replaces vanilla's chunk renderer wholesale, "
				+ "so Beryllium's own chunk rebuild prioritization is disabled for this session. "
				+ "Sodium already does this, and better, on the platforms where it can run at all.");
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
