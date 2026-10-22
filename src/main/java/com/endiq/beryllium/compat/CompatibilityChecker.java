package com.endiq.beryllium.compat;

import net.fabricmc.loader.api.FabricLoader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Decides which of Beryllium's optimisations should stand down when another mod is already
 * doing the same job — or doing a job that Beryllium's would contradict.
 *
 * <p>The rule throughout is that Beryllium yields. Two mods independently deciding when a
 * hopper should skip a tick, or which entities are worth drawing, or whether a section needs
 * rebuilding, does not produce the union of their optimisations; it produces behaviour
 * neither author intended and a bug report against whichever mod the user notices. Deferring
 * costs Beryllium a feature on that install and costs the player nothing.
 *
 * <p>Detection is by mod id through the Fabric loader, so nothing here loads a class from
 * another mod or creates a dependency on one.
 */
public final class CompatibilityChecker {

	private static final String[] KNOWN_OPTIMIZATION_MODS = {
		"sodium", "lithium", "ferritecore", "immediatelyfast", "entityculling", "indium", "iris"
	};

	/**
	 * Mods that draw terrain themselves, either instead of vanilla or alongside it.
	 *
	 * <p>These are tracked separately from {@link #KNOWN_OPTIMIZATION_MODS} because they call
	 * for different decisions. Sodium replaces vanilla's chunk renderer, so Beryllium's
	 * rebuild prioritisation and upload pacing have nothing left to pace. Distant Horizons
	 * does not replace anything — it adds a second renderer drawing level-of-detail terrain
	 * beyond the vanilla render distance — but it does its own frustum and distance culling
	 * over a much larger area, and a culling decision Beryllium makes from vanilla's render
	 * distance can contradict one Distant Horizons makes from its own.
	 */
	private static final String[] KNOWN_TERRAIN_RENDERER_MODS = {
		"sodium", "distanthorizons", "voxy"
	};

	/**
	 * Mods whose block entities render substantially outside their own block bounds.
	 *
	 * <p>Bounding-box culling is only sound for a block entity that stays inside the box it
	 * is tested against. Create's do not: a rotating shaft draws through its neighbours, a
	 * belt spans many blocks, and a contraption in motion carries a whole assembly of them
	 * across the world, positioned by the contraption rather than by any block. Testing
	 * those against a 1x1x1 box inflated by half a block culls geometry that is plainly on
	 * screen.
	 */
	private static final String[] KNOWN_CUSTOM_BLOCK_ENTITY_MODS = {
		"create"
	};

	/** Package prefixes Create's block entities live under, for the per-instance bypass. */
	private static final String[] CREATE_PACKAGE_PREFIXES = {
		"com.simibubi.create."
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

	/** Which terrain renderers are present, from {@link #KNOWN_TERRAIN_RENDERER_MODS}. */
	public static List<String> detectLoadedTerrainRendererMods() {
		List<String> found = new ArrayList<>();
		FabricLoader loader = FabricLoader.getInstance();
		for (String id : KNOWN_TERRAIN_RENDERER_MODS) {
			if (loader.isModLoaded(id)) {
				found.add(id);
			}
		}
		return found;
	}

	/** Which mods with out-of-bounds block entities are present. */
	public static List<String> detectLoadedCustomBlockEntityMods() {
		List<String> found = new ArrayList<>();
		FabricLoader loader = FabricLoader.getInstance();
		for (String id : KNOWN_CUSTOM_BLOCK_ENTITY_MODS) {
			if (loader.isModLoaded(id)) {
				found.add(id);
			}
		}
		return found;
	}

	/**
	 * Every mod Beryllium knows how to defer to, for logging. Sorted so the line is stable
	 * between runs and can be compared against a previous one.
	 */
	public static List<String> detectAllKnownMods() {
		List<String> found = new ArrayList<>();
		FabricLoader loader = FabricLoader.getInstance();
		for (String id : KNOWN_OPTIMIZATION_MODS) {
			if (loader.isModLoaded(id)) {
				found.add(id);
			}
		}
		for (String id : KNOWN_TERRAIN_RENDERER_MODS) {
			if (loader.isModLoaded(id) && !found.contains(id)) {
				found.add(id);
			}
		}
		for (String id : KNOWN_CUSTOM_BLOCK_ENTITY_MODS) {
			if (loader.isModLoaded(id) && !found.contains(id)) {
				found.add(id);
			}
		}
		Collections.sort(found);
		return found;
	}

	public static boolean shouldDeferBehindCameraCullingTo(List<String> loadedOptimizationMods) {
		return contains(loadedOptimizationMods, "entityculling");
	}

	/** EntityCulling also culls block entities; letting both mods decide independently
	 *  can double-cull or (worse) fight over the same render calls. */
	public static boolean shouldDeferBlockEntityCullingTo(List<String> loadedOptimizationMods) {
		return contains(loadedOptimizationMods, "entityculling");
	}

	/**
	 * Whether vanilla's chunk renderer has been replaced, so Beryllium's rebuild
	 * prioritisation and upload pacing have nothing of vanilla's left to schedule.
	 *
	 * <p>Sodium replaces it. Voxy replaces it too, and more thoroughly: it is a level-of-detail
	 * voxel renderer that does not use vanilla's section meshes at all, so a mixin aimed at
	 * vanilla's dispatcher is intercepting a code path Voxy never takes.
	 */
	public static boolean isChunkRendererReplaced(List<String> loadedOptimizationMods) {
		return contains(loadedOptimizationMods, "sodium")
			|| contains(loadedOptimizationMods, "voxy");
	}

	/** Lithium ships its own (more thorough) hopper optimization; two mods independently
	 *  deciding when a hopper should skip its tick is how a sorting system silently
	 *  changes throughput. */
	public static boolean shouldDeferHopperOptimizationTo(List<String> loadedOptimizationMods) {
		return contains(loadedOptimizationMods, "lithium");
	}

	/**
	 * Whether a level-of-detail renderer is drawing terrain beyond the vanilla render
	 * distance.
	 *
	 * <p>This is the condition that makes distance-based culling unsafe rather than merely
	 * redundant. Beryllium derives its cull distances from vanilla's render distance, which
	 * with Distant Horizons or Voxy installed describes only the near field: the player can
	 * see terrain two thousand blocks away, and a culling ceiling computed from a render
	 * distance of twelve sits far inside it. Frustum culling is unaffected — both renderers
	 * agree on which direction the player is facing — but a distance ceiling has to account
	 * for the fact that the visible world got much bigger.
	 */
	public static boolean isLodRendererPresent(List<String> loadedTerrainRendererMods) {
		return contains(loadedTerrainRendererMods, "distanthorizons")
			|| contains(loadedTerrainRendererMods, "voxy");
	}

	/**
	 * Whether Beryllium's own terrain renderer may engage.
	 *
	 * <p>It may not, whenever another mod is already responsible for drawing terrain. Two
	 * renderers drawing the same ground is not a performance problem to be tuned — it is
	 * z-fighting, double-drawn geometry and seams at the boundary where each stops and the
	 * other begins. Sodium and Voxy replace vanilla's renderer outright; Distant Horizons
	 * draws the far field that a Beryllium renderer would also be drawing.
	 *
	 * <p>This is the compatibility half of the rule that there is a single atomic switch
	 * between vanilla and custom rendering. The switch is only ever turned towards custom on
	 * an install where nothing else owns the terrain.
	 */
	public static boolean shouldDeferCustomTerrainRenderingTo(List<String> loadedTerrainRendererMods) {
		return !loadedTerrainRendererMods.isEmpty();
	}

	/**
	 * Whether block entities on this install may render outside their own block bounds, so
	 * bounding-box culling has to be skipped for them.
	 *
	 * <p>A coarse install-level answer. {@link #isCustomBlockEntity(Object)} is the
	 * per-instance one, and is what culling actually consults; this exists so a decision can
	 * be made and logged once at startup rather than per block entity per frame.
	 */
	public static boolean hasCustomBlockEntityMods(List<String> loadedCustomBlockEntityMods) {
		return loadedCustomBlockEntityMods != null && !loadedCustomBlockEntityMods.isEmpty();
	}

	/**
	 * Whether one specific block entity belongs to a mod whose rendering is not confined to
	 * its block.
	 *
	 * <p>Identified by class name prefix rather than by type, so Beryllium never loads a
	 * Create class and never needs one at compile time. On an install without Create this
	 * costs a single string comparison against a package that will not match.
	 *
	 * @return true if culling must leave this block entity alone
	 */
	public static boolean isCustomBlockEntity(Object blockEntity) {
		if (blockEntity == null) {
			return false;
		}
		try {
			String name = blockEntity.getClass().getName();
			for (String prefix : CREATE_PACKAGE_PREFIXES) {
				if (name.startsWith(prefix)) {
					return true;
				}
			}
		} catch (Throwable t) {
			// If the class name cannot be read, assume the block entity is special and do
			// not cull it. Skipping an optimisation is recoverable; deleting a spinning
			// water wheel the player is looking at is not.
			return true;
		}
		return false;
	}

	private static boolean contains(List<String> mods, String id) {
		return mods != null && mods.contains(id);
	}
}
