package com.endiq.beryllium.render.snapshot;

import com.endiq.beryllium.compat.MappedNames;
import com.endiq.beryllium.compat.Reflect;
import com.endiq.beryllium.util.BerylliumLog;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds a {@link SectionSnapshot} out of block states, without knowing which release of
 * Minecraft produced them.
 *
 * <p>The factory is split from the adapter on purpose. Reading a section out of a world is
 * version-specific and belongs in {@code ReflectiveTerrainAdapter}; deciding what a block
 * state <em>means</em> — does it occupy space, does it hide faces, does it glow, which layer
 * does it draw in — is the part that has to be rich enough to describe tens of thousands of
 * states and modded ones nobody has heard of. That is this class, and it is the piece the
 * engine's palette format was designed around.
 *
 * <h2>Why it is all reflection</h2>
 *
 * <p>Every accessor this needs has been renamed, re-signatured or moved at least once across
 * the supported range. {@code getLightEmission()} takes no arguments on some releases and a
 * level plus a position on others; {@code canOcclude()} gave way to {@code isSolidRender()}
 * which gave way to {@code isRedstoneConductor()}. Writing against any one of them means the
 * jar compiles for that release and no other.
 *
 * <p>So each property is read by trying a list of candidate no-argument method names and
 * falling back to a conservative default when none of them exist. Conservative means: when in
 * doubt, say the block occupies space and hides faces behind it. A block drawn as solid that
 * should have been transparent is visible and gets reported; a block treated as air that
 * should have been solid is a hole in the world that looks like a bug in something else.
 *
 * <p>Nothing here is wired into rendering yet. This is the extraction layer Phase 1 asks for,
 * built and separable before anything tries to replace terrain.
 */
public final class SectionSnapshotFactory {
	/** Supplies the block state at a local coordinate, or null if it cannot be read. */
	public interface BlockStateSource {
		Object blockStateAt(int localX, int localY, int localZ);
	}

	/** Supplies baked light, or {@code -1} per value when this release does not expose it. */
	public interface LightSource {
		int skyAt(int localX, int localY, int localZ);

		int blockAt(int localX, int localY, int localZ);
	}

	/**
	 * Descriptor cache, keyed on the block state object.
	 *
	 * <p>Block states are interned singletons in Minecraft — the same {@code BlockState}
	 * instance answers for every stone in the world — so an identity map is both correct and
	 * the whole reason this is affordable. Describing a state costs a dozen reflective calls;
	 * doing that 4096 times per section would cost more than the meshing it feeds.
	 *
	 * <p>Concurrent because capture may run on more than one thread, and unbounded growth is
	 * prevented by the cap below: a modpack can have a very large number of states, but not
	 * an unbounded one, and a cache that grows without limit in a client mod is a leak.
	 */
	private static final Map<Object, BlockDescriptor> DESCRIPTOR_CACHE = new ConcurrentHashMap<>();
	private static final int DESCRIPTOR_CACHE_LIMIT = 8192;

	private SectionSnapshotFactory() {
	}

	/**
	 * Captures one section.
	 *
	 * @param sectionX section coordinate
	 * @param sectionY section coordinate
	 * @param sectionZ section coordinate
	 * @param revision the inputs' identity, which the finished mesh will be checked against
	 * @param states   where to read block states from
	 * @param light    where to read baked light from, or null if the engine should compute it
	 * @return the snapshot, or null if the section could not be read at all
	 */
	public static SectionSnapshot capture(int sectionX, int sectionY, int sectionZ,
	                                      MeshRevision revision,
	                                      BlockStateSource states,
	                                      LightSource light) {
		if (revision == null || states == null) {
			return null;
		}
		try {
			// States are interned, so identity is the right equality and the right cache key:
			// two distinct states that happen to be equal() would need distinct descriptors.
			IdentityHashMap<Object, Integer> paletteIndex = new IdentityHashMap<>(64);
			List<BlockDescriptor> palette = new ArrayList<>(16);
			short[] indices = new short[SnapshotFormat.SECTION_VOLUME];

			int unreadable = 0;
			for (int y = 0; y < SnapshotFormat.SECTION_SIDE; y++) {
				for (int z = 0; z < SnapshotFormat.SECTION_SIDE; z++) {
					for (int x = 0; x < SnapshotFormat.SECTION_SIDE; x++) {
						Object state = states.blockStateAt(x, y, z);
						int voxel = x + z * SnapshotFormat.SECTION_SIDE
							+ y * SnapshotFormat.SECTION_SIDE * SnapshotFormat.SECTION_SIDE;
						if (state == null) {
							unreadable++;
							indices[voxel] = indexForAir(paletteIndex, palette);
							continue;
						}
						Integer existing = paletteIndex.get(state);
						if (existing != null) {
							indices[voxel] = existing.shortValue();
							continue;
						}
						if (palette.size() >= SnapshotFormat.SECTION_VOLUME) {
							// A section cannot hold more distinct states than voxels. Reaching
							// this means the source is not returning interned instances, so
							// deduplication is not working and the palette would be useless.
							BerylliumLog.debug("[BERYLLIUM] snapshot palette overflowed at "
								+ sectionX + "," + sectionY + "," + sectionZ);
							return null;
						}
						int index = palette.size();
						palette.add(describe(state));
						paletteIndex.put(state, index);
						indices[voxel] = (short) index;
					}
				}
			}

			/* A section whose states could not be read at all is not a snapshot of anything.
			 * Partially readable is fine — the gaps became air, which is the safe answer for
			 * a cell whose contents are unknown at the edge of the loaded world. */
			if (unreadable == SnapshotFormat.SECTION_VOLUME) {
				return null;
			}
			if (palette.isEmpty()) {
				return null;
			}

			byte[] lightBytes = null;
			if (light != null) {
				lightBytes = readLight(light);
			}

			return new SectionSnapshot(sectionX, sectionY, sectionZ, revision,
				palette, indices, lightBytes);
		} catch (Throwable t) {
			BerylliumLog.debug("[BERYLLIUM] section snapshot failed: " + t);
			return null;
		}
	}

	private static short indexForAir(IdentityHashMap<Object, Integer> paletteIndex,
	                                 List<BlockDescriptor> palette) {
		// Air is not interned into the identity map because there is no state object for it;
		// it gets a palette entry the first time a cell turns out to be unreadable.
		for (int i = 0; i < palette.size(); i++) {
			if (palette.get(i).modelKind() == SnapshotFormat.MODEL_AIR) {
				return (short) i;
			}
		}
		palette.add(BlockDescriptor.air());
		return (short) (palette.size() - 1);
	}

	private static byte[] readLight(LightSource light) {
		byte[] out = new byte[SnapshotFormat.SECTION_VOLUME];
		boolean anyRead = false;
		for (int y = 0; y < SnapshotFormat.SECTION_SIDE; y++) {
			for (int z = 0; z < SnapshotFormat.SECTION_SIDE; z++) {
				for (int x = 0; x < SnapshotFormat.SECTION_SIDE; x++) {
					int sky = light.skyAt(x, y, z);
					int block = light.blockAt(x, y, z);
					if (sky < 0 || block < 0) {
						continue;
					}
					anyRead = true;
					out[x + z * SnapshotFormat.SECTION_SIDE
						+ y * SnapshotFormat.SECTION_SIDE * SnapshotFormat.SECTION_SIDE] =
						(byte) ((sky & 0xF) | ((block & 0xF) << 4));
				}
			}
		}
		/* If not one light value could be read, the section carries no light rather than a
		 * block of zeros — zeros would be believed, and a world lit to zero is a world the
		 * player cannot see. Absent means the engine computes its own. */
		return anyRead ? out : null;
	}

	/* ------------------------------------------------------------ describe -- */

	/**
	 * Describes one block state: the properties that decide how its geometry is produced and
	 * drawn.
	 *
	 * <p>Cached by identity, because Minecraft interns block states and the same instance
	 * answers for every occurrence in the world.
	 */
	public static BlockDescriptor describe(Object blockState) {
		if (blockState == null) {
			return BlockDescriptor.air();
		}
		BlockDescriptor cached = DESCRIPTOR_CACHE.get(blockState);
		if (cached != null) {
			return cached;
		}

		BlockDescriptor descriptor = describeUncached(blockState);
		if (DESCRIPTOR_CACHE.size() < DESCRIPTOR_CACHE_LIMIT) {
			DESCRIPTOR_CACHE.put(blockState, descriptor);
		}
		return descriptor;
	}

	private static BlockDescriptor describeUncached(Object blockState) {
		String name = nameOf(blockState);

		/* Air is recognised by registry name first, and that order matters. Registry names
		 * are not obfuscated -- "minecraft:air" is what the game calls air on every release,
		 * in every runtime, remapped or not -- whereas the isAir() accessor is a mapped
		 * method name that only resolves through MappedNames. Getting this backwards would
		 * mean every air cell in the world describing itself as a solid cube on a release
		 * where the mapping lookup failed, which is a world filled in rather than a feature
		 * quietly switched off. */
		if (isAirName(name) || invokeBoolean(blockState, "isAir", "()Z")) {
			return BlockDescriptor.air();
		}

		int flags = 0;
		int emission = invokeIntInRange(blockState, 0, 15, "getLightEmission", "()I");
		int attenuation = 15;
		int skylightFilter = 15;

		boolean liquid = isLiquid(blockState, name);
		boolean translucent = isTranslucent(blockState, name);
		boolean cutout = isCutout(name);
		boolean occludes = invokeBooleanAny(blockState,
			new String[][] {
				{"canOcclude", "()Z"},
				{"isSolid", "()Z"},
				{"isRedstoneConductor", "()Z"},
			});

		if (emission > 0) {
			flags |= SnapshotFormat.FLAG_EMIT_LIGHT;
			/* A block that gives off light does not stop it, and skylight passes through
			 * anything that does not occlude. */
			attenuation = 0;
		}
		if (!occludes) {
			skylightFilter = 0;
			attenuation = Math.min(attenuation, 1);
		}

		int modelKind;
		int renderLayer;
		if (liquid) {
			modelKind = SnapshotFormat.MODEL_LIQUID;
			renderLayer = SnapshotFormat.LAYER_TRANSLUCENT;
			flags |= SnapshotFormat.FLAG_TRANSLUCENT;
		} else if (translucent) {
			modelKind = SnapshotFormat.MODEL_FULL_CUBE;
			renderLayer = SnapshotFormat.LAYER_TRANSLUCENT;
			flags |= SnapshotFormat.FLAG_TRANSLUCENT;
		} else if (cutout || isCrossModel(name)) {
			modelKind = isCrossModel(name) ? SnapshotFormat.MODEL_CROSS : SnapshotFormat.MODEL_FULL_CUBE;
			renderLayer = SnapshotFormat.LAYER_CUTOUT;
			flags |= SnapshotFormat.FLAG_CUTOUT;
		} else {
			/* A block whose shape is drawn by a block entity rather than by a cube lands
			 * here and is described as a full cube. That is deliberately not read
			 * reflectively: the accessor returns an enum, and MappedNames only translates
			 * descriptors made of primitives, so on an obfuscated runtime the lookup would
			 * miss and the answer would be silently wrong rather than safely approximate.
			 * Extra geometry where a block entity also draws is the conservative outcome;
			 * translating real baked models is the next phase's work. */
			modelKind = SnapshotFormat.MODEL_FULL_CUBE;
			renderLayer = SnapshotFormat.LAYER_OPAQUE;
		}

		if (occludes && renderLayer == SnapshotFormat.LAYER_OPAQUE
			&& modelKind == SnapshotFormat.MODEL_FULL_CUBE) {
			flags |= SnapshotFormat.FLAG_OPAQUE;
		}
		if (modelKind != SnapshotFormat.MODEL_AIR) {
			flags |= SnapshotFormat.FLAG_SOLID;
		}

		return new BlockDescriptor(name, flags, emission, attenuation, skylightFilter,
			renderLayer, modelKind, null);
	}

	/** The three air states. Registry names are identical on every release and runtime. */
	private static boolean isAirName(String name) {
		return name.equals("minecraft:air")
			|| name.equals("minecraft:cave_air")
			|| name.equals("minecraft:void_air");
	}

	/**
	 * The registry name of a block state, e.g. {@code minecraft:stone}.
	 *
	 * <p>Read from the state's own {@code toString()}, which renders as
	 * {@code Block{minecraft:stone}[facing=north]}, and that is not a hack so much as the one
	 * stable surface available. Registry names are data, not code: they are the same on an
	 * obfuscated runtime as on an unobfuscated one, and the same on 1.19.4 as on 26.3. Every
	 * alternative — a registry lookup, an accessor call — goes through a class or method name
	 * that has moved inside the supported range.
	 *
	 * <p>If the string cannot be parsed, the class name is used instead. It will not match any
	 * known block, so the state is described from its flags, which is the intended path for a
	 * block the engine has never heard of anyway.
	 */
	private static String nameOf(Object blockState) {
		try {
			String text = blockState.toString();
			if (text != null) {
				int open = text.indexOf('{');
				int close = text.indexOf('}', open + 1);
				if (open >= 0 && close > open) {
					String name = text.substring(open + 1, close);
					if (!name.isEmpty() && name.indexOf(' ') < 0) {
						return name;
					}
				}
			}
		} catch (Throwable ignored) {
			// fall through to the class name
		}
		return blockState.getClass().getName();
	}

	/** Cross-shaped models are the plants: cutout, and they do not occlude. */
	private static boolean isCrossModel(String name) {
		String bare = stripNamespace(name);
		String[] plants = {
			"grass", "fern", "seagrass", "dandelion", "poppy", "blue_orchid", "allium",
			"azure_bluet", "tulip", "orchid", "cornflower", "lily_of_the_valley",
			"wither_rose", "torchflower", "sapling", "bush", "seedling", "sprout",
			"dead_bush", "warped_roots", "crimson_roots", "nether_sprouts", "flower",
			"coral_fan", "coral", "kelp", "vine", "cave_vines", "torch", "candle"
		};
		for (String plant : plants) {
			if (bare.contains(plant)) {
				return true;
			}
		}
		return false;
	}

	private static String stripNamespace(String name) {
		int colon = name.indexOf(':');
		return colon >= 0 ? name.substring(colon + 1) : name;
	}

	/** Liquids are recognised by the fluid state they carry, and by name as a fallback. */
	private static boolean isLiquid(Object blockState, String name) {
		Object fluid = callNoArg(blockState, "getFluidState",
			"()Lnet/minecraft/world/level/material/FluidState;");
		if (fluid != null) {
			Object empty = callNoArg(fluid, "isEmpty", "()Z");
			if (empty instanceof Boolean) {
				return !(Boolean) empty;
			}
		}
		String bare = stripNamespace(name);
		return bare.equals("water") || bare.equals("lava")
			|| bare.contains("water") || bare.contains("lava");
	}

	private static boolean isTranslucent(Object blockState, String name) {
		if (isLiquid(blockState, name)) {
			return true;
		}
		String bare = stripNamespace(name);
		return bare.contains("glass") || bare.contains("ice")
			|| bare.contains("slime") || bare.contains("honey");
	}

	private static boolean isCutout(String name) {
		String bare = stripNamespace(name);
		return bare.contains("leaves");
	}

	/* ---------------------------------------------------------- reflection -- */

	/**
	 * The class that declares block-state accessors, in Mojang mappings.
	 *
	 * <p>Used as the owner when translating a method name. On an obfuscated runtime the
	 * literal names below would match nothing at all — Loom remaps bytecode references but
	 * not string literals, and a production Fabric runtime uses intermediary names — so every
	 * lookup goes through {@link MappedNames}, which is the mechanism the rest of the mod uses
	 * for exactly this.
	 */
	private static final String BLOCK_STATE_OWNER =
		"net.minecraft.world.level.block.state.BlockState";

	/**
	 * Calls a no-argument accessor whose name is given in Mojang mappings.
	 *
	 * @return the value, or null when the accessor does not exist on this release or would
	 *         not run. Every caller treats null as "unknown" and falls back to a default.
	 */
	private static Object callNoArg(Object target, String namedMethod, String namedDescriptor) {
		if (target == null) {
			return null;
		}
		String runtimeName = MappedNames.methodName(BLOCK_STATE_OWNER, namedMethod, namedDescriptor);
		return Reflect.call(target, runtimeName);
	}

	static boolean invokeBoolean(Object target, String namedMethod, String namedDescriptor) {
		Object value = callNoArg(target, namedMethod, namedDescriptor);
		return value instanceof Boolean && (Boolean) value;
	}

	/**
	 * Tries several accessors that have meant the same thing on different releases.
	 *
	 * <p>{@code canOcclude}, {@code isSolid} and {@code isRedstoneConductor} are not synonyms
	 * in general, but they are the three names this property has answered to inside the
	 * supported range, and whichever exists is a better answer than the default.
	 */
	static boolean invokeBooleanAny(Object target, String[][] candidates) {
		for (String[] candidate : candidates) {
			Object value = callNoArg(target, candidate[0], candidate[1]);
			if (value instanceof Boolean) {
				return (Boolean) value;
			}
		}
		return false;
	}

	static int invokeIntInRange(Object target, int min, int max,
	                            String namedMethod, String namedDescriptor) {
		Object value = callNoArg(target, namedMethod, namedDescriptor);
		if (value instanceof Number) {
			return Math.max(min, Math.min(max, ((Number) value).intValue()));
		}
		return min;
	}

	/** Clears both caches. Called on resource-pack reload, when every descriptor is stale. */
	public static void invalidateDescriptors() {
		DESCRIPTOR_CACHE.clear();
	}
}
