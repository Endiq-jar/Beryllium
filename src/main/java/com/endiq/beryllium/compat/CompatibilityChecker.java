package com.endiq.beryllium.compat;

import net.fabricmc.loader.api.FabricLoader;

import java.util.ArrayList;
import java.util.List;

public final class CompatibilityChecker {
	
	private static final String[] KNOWN_OPTIMIZATION_MODS = {
		"sodium", "lithium", "ferritecore", "immediatelyfast", "entityculling", "indium", "iris"
	};

	private CompatibilityChecker() {
	}

	public static List<String> detectLoadedOptimizationMods() {
		List<String> found = new ArrayList<>();
		FabricLoader loader = FabricLoader.getInstance();
		for (String id : KNOWN_OPTIMIZATION_MODS) {
			if (loader.isModLoaded(id)) {
				found.add(id);
			}
		}
		return found;
	}

	public static boolean shouldDeferBehindCameraCullingTo(List<String> loadedOptimizationMods) {
		return loadedOptimizationMods.contains("entityculling");
	}

	public static boolean isChunkRendererReplaced(List<String> loadedOptimizationMods) {
		return loadedOptimizationMods.contains("sodium");
	}
}
