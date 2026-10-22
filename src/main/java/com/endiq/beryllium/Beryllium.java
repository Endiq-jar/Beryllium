package com.endiq.beryllium;

import com.endiq.beryllium.compat.CompatibilityChecker;
import com.endiq.beryllium.config.BerylliumConfig;
import com.endiq.beryllium.device.DeviceDetector;
import com.endiq.beryllium.device.DeviceInfo;
import com.endiq.beryllium.platform.LauncherEnvironment;
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
	private static volatile boolean deferHopperOptimizationsToOtherMod = false;
	private static volatile boolean deferCustomTerrainRendering = false;
	private static volatile boolean lodRendererPresent = false;
	private static volatile boolean customBlockEntitiesPresent = false;

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

	public static boolean isHopperOptimizationDeferredToOtherMod() {
		return deferHopperOptimizationsToOtherMod;
	}

	/**
	 * Whether Beryllium's own terrain renderer must stay off, because another mod already
	 * draws the terrain. Consulted by the renderer mode switch: it is the compatibility half
	 * of the rule that only one renderer ever owns the ground.
	 */
	public static boolean isCustomTerrainRenderingDeferred() {
		return deferCustomTerrainRendering;
	}

	/**
	 * Whether a level-of-detail renderer (Distant Horizons, Voxy) is drawing terrain beyond
	 * the vanilla render distance. When it is, distances derived from vanilla's render
	 * distance describe only the near field and must not be used as culling ceilings.
	 */
	public static boolean isLodRendererPresent() {
		return lodRendererPresent;
	}

	/** Whether any loaded mod has block entities that render outside their own block. */
	public static boolean hasCustomBlockEntities() {
		return customBlockEntitiesPresent;
	}

	private void checkCompatibility() {
		if (!config.compatibilityModeEnabled) {
			return;
		}

		List<String> loaded = CompatibilityChecker.detectLoadedOptimizationMods();
		List<String> terrainRenderers = CompatibilityChecker.detectLoadedTerrainRendererMods();
		List<String> customBlockEntities = CompatibilityChecker.detectLoadedCustomBlockEntityMods();

		List<String> everything = CompatibilityChecker.detectAllKnownMods();
		if (!everything.isEmpty()) {
			BerylliumLog.compat("Detected known mods: " + String.join(", ", everything));
		}

		// Terrain renderers and block-entity mods are checked even when no optimisation mod
		// is present: Distant Horizons and Create are not optimisations, and an install with
		// only those still needs the decisions below.
		checkTerrainRendererCompatibility(terrainRenderers);
		checkBlockEntityCompatibility(customBlockEntities);

		if (loaded.isEmpty()) {
			return;
		}

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

		if (CompatibilityChecker.shouldDeferHopperOptimizationTo(loaded)) {
			deferHopperOptimizationsToOtherMod = true;
			BerylliumLog.compat("Lithium is loaded — it ships its own hopper optimization, so "
					+ "Beryllium's hopper throttling is disabled for this session.");
		}

		List<String> rendererAware = new java.util.ArrayList<>(loaded);
		rendererAware.addAll(terrainRendererModsForSession);
		if (CompatibilityChecker.isChunkRendererReplaced(rendererAware)) {
			deferChunkOptimizationsToOtherMod = true;
			BerylliumLog.compat("Another mod replaces vanilla's chunk renderer ("
				+ String.join(", ", rendererAware) + "), so Beryllium's own chunk rebuild "
				+ "prioritization and upload pacing are disabled for this session. They schedule "
				+ "work inside vanilla's dispatcher, which that mod no longer uses; the "
				+ "replacement already does this, and better, on the platforms where it runs.");
		}
	}

	/** Terrain renderers found at startup, kept so the chunk-deferral check can see them. */
	private static volatile List<String> terrainRendererModsForSession = List.of();

	private void checkTerrainRendererCompatibility(List<String> terrainRenderers) {
		terrainRendererModsForSession = List.copyOf(terrainRenderers);

		if (CompatibilityChecker.isLodRendererPresent(terrainRenderers)) {
			lodRendererPresent = true;
			BerylliumLog.compat("A level-of-detail terrain renderer is loaded ("
				+ String.join(", ", terrainRenderers) + "). It draws terrain well beyond the "
				+ "vanilla render distance and does its own frustum and distance culling over "
				+ "that area, so Beryllium stops deriving cull distances from vanilla's render "
				+ "distance — a ceiling computed from a render distance of 12 sits far inside a "
				+ "world the player can see for a thousand blocks. Frustum culling is unaffected: "
				+ "both renderers agree on where the camera is pointing.");
		}

		if (CompatibilityChecker.shouldDeferCustomTerrainRenderingTo(terrainRenderers)) {
			deferCustomTerrainRendering = true;
			BerylliumLog.compat("Terrain rendering on this install belongs to "
				+ String.join(", ", terrainRenderers) + ", so Beryllium's own terrain renderer "
				+ "stays off. Two renderers drawing the same ground is not a tuning problem — it "
				+ "is z-fighting, doubled geometry and a seam where each one stops.");
		}
	}

	private void checkBlockEntityCompatibility(List<String> customBlockEntityMods) {
		if (!CompatibilityChecker.hasCustomBlockEntityMods(customBlockEntityMods)) {
			return;
		}
		customBlockEntitiesPresent = true;
		BerylliumLog.compat("A mod with block entities that render outside their own block is "
			+ "loaded (" + String.join(", ", customBlockEntityMods) + "). Bounding-box culling is "
			+ "only sound for a block entity that stays inside the box it is tested against, so "
			+ "those are bypassed individually by class: Create's shafts draw through their "
			+ "neighbours, its belts span many blocks, and a contraption in motion is positioned "
			+ "by the contraption rather than by any block.");
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

		LauncherEnvironment launcher = LauncherEnvironment.detect();
		if (launcher.isAndroidJavaLauncher()) {
			BerylliumLog.mobile("Android Java launcher detected (" + launcher.describe() + "). "
				+ "Safe mode is " + (config.androidSafeMode ? "enabled" : "disabled") + ".");
		}

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
