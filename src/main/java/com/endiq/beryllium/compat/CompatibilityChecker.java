package com.endiq.beryllium.compat;

import net.fabricmc.loader.api.FabricLoader;

import java.util.ArrayList;
import java.util.List;

/**
 * Spec section 21: detect other optimization mods and avoid conflicting/duplicating
 * their work rather than assuming every mod is compatible.
 *
 * <p>Uses only {@code FabricLoader.getInstance().isModLoaded(String)} — bedrock-stable
 * Fabric Loader API, unaffected by Minecraft mappings or version, so unlike most of the
 * rest of this mod, this file carries essentially no compile-risk.
 */
public final class CompatibilityChecker {
	/** Mod IDs this mod knows to look for and has an actual documented interaction with.
	 *  Anything else loaded is simply unknown to Beryllium — not treated as incompatible,
	 *  just not specifically reasoned about (see spec: "do not assume every mod is
	 *  compatible" is about not blindly assuming safety, not about blocklisting the
	 *  unfamiliar). */
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

	/**
	 * EntityCulling already does its own general-purpose frustum/visibility-based entity
	 * culling. Running Beryllium's behind-camera culling on top of it means two mods
	 * independently deciding whether to skip rendering the same entity, a combination
	 * that was never tested together — deferring to EntityCulling avoids that, rather
	 * than assuming they compose safely.
	 */
	public static boolean shouldDeferBehindCameraCullingTo(List<String> loadedOptimizationMods) {
		return loadedOptimizationMods.contains("entityculling");
	}
}
