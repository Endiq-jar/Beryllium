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

	@Override
	public void onInitialize() {
		config = BerylliumConfig.load();
		BerylliumLog.setDebugEnabled(config.debugMode);

		if (!config.enabled) {
			BerylliumLog.info("Disabled via beryllium.json (\"enabled\": false) — skipping initialization.");
			return;
		}
		enforceNoMercy();
		checkCompatibility();
		logStartupBanner();
		if (config.noMercy) {
			BerylliumLog.info("[BERYLLIUM-NO-MERCY] ⚡ All optimizations FORCED ON — crystal optimizer, static chests, lightmap cache, sky cache, packet hardening, activation range, villager lobotomy, redstone batching, painting blocks, DSA/RBO/SIMD, deduplication, fast math/perlin/AABB, 1.17->26.3 single jar enabled.");
		}
	}

	private void enforceNoMercy() {
		if (config == null || !config.noMercy) return;
		// Force every ultra optimization on, even if an old config had it false
		config.crystalOptimizer = true; config.crystalFastPlace = true; config.crystalFastBreak = true; config.crystalOptimizePlacement = true; config.crystalSkipAnimation = true;
		config.staticChestModel = true; config.chestStaticModelOptimization = true; config.chestAnimateOnlyWhenOpen = true;
		config.chunkSendOptimization = true; config.chunkSendThrottle = true; config.optimizeChunkSending = true;
		config.itemBounceSuppress = true; config.removeItemBounce = true; config.itemNoBounce = true;
		config.lightmapCache = true; config.avoidUpdatingLightmap = true; config.cacheLightmap = true;
		config.skipDebugLogic = true; config.debugLogicOptimization = true; config.onlyTickDebugWhenNeeded = true;
		config.skyColorCache = true; config.skyColorOptimization = true; config.cacheSkyColor = true;
		config.modelGapFixEnhanced = true; config.fixModelGapsEnhanced = true; config.fixItemModelGaps = true; config.itemModelQuadOptimization = true;
		config.packetSizeGuard = true; config.nbtSizeGuard = true; config.preventPacketExploits = true; config.packetHardening = true; config.payloadGuard = true; config.varIntGuard = true; config.nbtGuard = true;
		config.recipeFix = true; config.ignoreBrokenRecipes = true; config.ignoreInvalidRecipes = true;
		config.tagIdFix = true; config.invalidTagFix = true; config.filterInvalidTags = true;
		config.dnsBypass = true; config.bypassReverseDns = true; config.useDirectIp = true;
		config.entityActivationRange = true; config.activationRangeEnabled = true;
		config.dynamicPerformance = true; config.dynamicPerformanceChecks = true; config.autoAdjustViewDistance = true; config.autoAdjustSimulationDistance = true; config.autoAdjustMobcaps = true; config.autoAdjustChunkTickDistance = true;
		config.villagerLobotomize = true; config.villagerLobotomization = true; config.villagerTickIn1x1Less = true;
		config.mobSpawnConfig = true; config.configurableMobSpawning = true;
		config.breedingCap = true; config.breedingCaps = true;
		config.chunkTickDistance = true; config.chunkTickDistanceOptimization = true;
		config.fastBoot = true; config.deferNonEssentialInit = true; config.bootOptimization = true;
		config.preGenerateSync = true; config.syncChunkGenWithRender = true;
		config.cancellableLoadingScreens = true; config.loadingScreenCancellable = true; config.preRenderPhase = true; config.preRenderingPhase = true;
		config.threadedEventPolling = true; config.bufferedRawInput = true;
		config.redstoneOptimization = true; config.redstonePowerOptimization = true; config.optimizeRedstone = true; config.fastRedstoneWire = true;
		config.paintingOptimization = true; config.paintingAsBlock = true; config.optimizePaintings = true; config.paintingsUseBakedModels = true;
		config.worldGenOptimization = true; config.optimizeWorldGen = true; config.packedWorldStorage = true;
		config.dsaBuffers = true; config.dsaOptimization = true; config.vboPool = true; config.rboDepth = true; config.useRboDepth = true; config.simdEnabled = true; config.simdOptimization = true;
		config.fastMath = true; config.fastMathOperations = true; config.fastRandom = true; config.fastPerlin = true; config.fastPerlinNoise = true; config.fastAabb = true; config.fastAabbDirection = true; config.enumCloneOptimization = true; config.replaceEnumClone = true; config.cacheSystemProperties = true;
		config.deduplication = true; config.dedupResourceKeys = true; config.dedupResourceLocation = true; config.dedupVertices = true;
		config.mobAiOptimization = true; config.optimizeMobAi = true;
		config.shaderUniformCache = true; config.cacheShaderUniforms = true; config.chunkBuildOptimization = true; config.optimizeChunkBuilding = true; config.nbtOptimization = true; config.optimizeNbt = true;
		// Also ensure legacy culling etc are aggressive
		config.cullLeavesInternalFaces = true; config.cullBlockEntities = true; config.cullBehindCameraEntities = true; config.entityRenderCulling = true;
		config.chestRenderCulling = true; config.particleCulling = true; config.visibilityCulling = true; config.leafCulling = true;
		// Ensure every legacy performance toggle is on the most aggressive (true) value
		config.voxelShapeOptimizations = true; config.tickOptimizations = true; config.chunkRebuildPrioritization = true; config.frameBudgetScheduling = true;
		// Single jar flag
		config.singleJarMultiVersion = true;
		try { config.save(); } catch (Throwable t) {}
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

		if (CompatibilityChecker.shouldDeferHopperOptimizationTo(loaded)) {
			deferHopperOptimizationsToOtherMod = true;
			BerylliumLog.compat("Lithium is loaded — it ships its own hopper optimization, so "
					+ "Beryllium's hopper throttling is disabled for this session.");
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
