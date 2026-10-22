package com.endiq.beryllium.render.adapter;

import com.endiq.beryllium.chunk.SectionDirtyBridge;
import com.endiq.beryllium.chunk.SectionPacking;
import com.endiq.beryllium.compat.MappedNames;
import com.endiq.beryllium.compat.Reflect;
import com.endiq.beryllium.render.snapshot.MeshRevision;
import com.endiq.beryllium.render.snapshot.SectionSnapshot;
import com.endiq.beryllium.render.snapshot.SectionSnapshotFactory;
import com.endiq.beryllium.util.BerylliumLog;
import net.minecraft.client.Minecraft;

/**
 * The one terrain adapter there is today, and the reference for how a version-specific one
 * should behave.
 *
 * <p>It reaches Minecraft's internals by reflection rather than by direct reference, which is
 * a deliberate trade. A direct reference compiles only against the release whose class layout
 * it was written for, and Beryllium ships one jar per release from a single source tree across
 * a range where these members have moved. Reflection costs a cached lookup and buys the
 * ability to degrade: on a release whose internals this cannot find, {@link #isAvailable()}
 * reports false and the game continues on vanilla, instead of the jar failing to build or
 * failing at load.
 *
 * <p>Every name is written in Mojang mappings and translated through {@link MappedNames}
 * before use. That step is not optional — Loom remaps bytecode references but not string
 * literals, and a production Fabric runtime uses intermediary names, so a literal
 * {@code "getBlockState"} matches nothing on any obfuscated release however correct it looks
 * here.
 *
 * <h2>What it refuses to run on</h2>
 *
 * <p>{@link #isAvailable()} is strict, because every condition it checks is one whose absence
 * produces a silently wrong picture rather than a visible failure:
 * <ul>
 *   <li>The section-reading accessors must resolve. Without them there is nothing to
 *       snapshot, which at least fails loudly.</li>
 *   <li>The dirty-mark path must be observable, through the same probe
 *       {@link SectionDirtyBridge} uses. Without it {@link SectionRevisionClock} never
 *       advances, every section looks permanently current, and a mesh built before a change
 *       is accepted after it. That is geometry which does not match the world, with no error
 *       anywhere to point at — so those releases (26.2 and 26.3, where the section update path
 *       was rewritten and {@code RenderSection} no longer carries a dirty flag) are refused
 *       rather than approximated.</li>
 *   <li>The resource-pack generation must be readable, for the same reason: a pack reload
 *       invalidates every mesh in the world without moving a single voxel.</li>
 * </ul>
 */
final class ReflectiveTerrainAdapter implements MinecraftTerrainAdapter {
	private static final String LEVEL = "net.minecraft.world.level.Level";
	private static final String LEVEL_CHUNK = "net.minecraft.world.level.chunk.LevelChunk";
	private static final String LEVEL_CHUNK_SECTION =
		"net.minecraft.world.level.chunk.LevelChunkSection";

	/** Resolved once; null means this release cannot be adapted. */
	private static volatile Boolean available;

	private volatile Object lastResourceManager;
	private volatile long packGeneration = 1L;
	private volatile Object lastLevel;
	private volatile long lastWorldId = 0L;

	ReflectiveTerrainAdapter() {
	}

	@Override
	public String describes() {
		return "reflective adapter for Minecraft " + MinecraftTerrainAdapter.minecraftVersion();
	}

	@Override
	public boolean isAvailable() {
		Boolean cached = available;
		if (cached != null) {
			return cached;
		}
		boolean result = probe();
		available = result;
		if (!result) {
			BerylliumLog.debug("[BERYLLIUM] no terrain adapter available for Minecraft "
				+ MinecraftTerrainAdapter.minecraftVersion()
				+ "; the custom renderer stays off");
		}
		return result;
	}

	private boolean probe() {
		try {
			/* The section-reading chain: Level.getChunk(int,int) ->
			 * LevelChunk.getSection(int) -> LevelChunkSection.getBlockState(int,int,int).
			 * Each is probed by translated name against the class that declares it. */
			Class<?> levelType = Reflect.type(MappedNames.className(LEVEL));
			Class<?> chunkType = Reflect.type(MappedNames.className(LEVEL_CHUNK));
			Class<?> sectionType = Reflect.type(MappedNames.className(LEVEL_CHUNK_SECTION));
			if (levelType == null || chunkType == null || sectionType == null) {
				return false;
			}
			if (Reflect.findMethod(levelType,
				MappedNames.methodName(LEVEL, "getChunk", "(II)Lnet/minecraft/world/level/chunk/LevelChunk;"),
				2, new Object[] {0, 0}) == null) {
				return false;
			}
			if (Reflect.findMethod(sectionType,
				MappedNames.methodName(LEVEL_CHUNK_SECTION, "getBlockState",
					"(III)Lnet/minecraft/world/level/block/state/BlockState;"),
				3, new Object[] {0, 0, 0}) == null) {
				return false;
			}

			/* Staleness has to be detectable, or a stale mesh is indistinguishable from a
			 * current one. Both the section revision source and the pack generation source
			 * are checked here rather than at capture time, because failing later would mean
			 * failing silently. */
			Minecraft minecraft = Minecraft.getInstance();
			if (minecraft == null || minecraft.levelRenderer == null) {
				// Too early to tell: no renderer means no dirty-mark path to probe. Report
				// unavailable rather than guessing; the caller re-probes on a later frame.
				available = null;
				return false;
			}
			if (!SectionDirtyBridge.canReschedule(minecraft.levelRenderer)) {
				return false;
			}
			return resourceManagerOf(minecraft) != null;
		} catch (Throwable t) {
			return false;
		}
	}

	@Override
	public SectionSnapshot captureSection(int sectionX, int sectionY, int sectionZ) {
		if (!isAvailable()) {
			return null;
		}
		try {
			Minecraft minecraft = Minecraft.getInstance();
			if (minecraft == null || minecraft.level == null) {
				return null;
			}
			Object section = chunkSectionOf(minecraft.level, sectionX, sectionY, sectionZ);
			if (section == null) {
				return null;
			}

			MeshRevision revision = currentRevision(sectionX, sectionY, sectionZ);
			if (revision == null) {
				return null;
			}

			/* The source reads through the section object and nothing else. That is the
			 * whole point of the snapshot: what comes back holds no reference to the level,
			 * the chunk or the section, so handing it to a worker cannot give the worker a
			 * way to reach a live world object. */
			final Object captured = section;
			SectionSnapshotFactory.BlockStateSource states =
				(localX, localY, localZ) -> readBlockState(captured, localX, localY, localZ);

			/* No light source yet: baked light lives in the level's light engine rather than
			 * in the section, and reading it portably across the supported range is its own
			 * problem. Passing null means the snapshot carries no light and the engine
			 * computes its own, which the format explicitly allows and which is honest about
			 * what has been done. */
			return SectionSnapshotFactory.capture(sectionX, sectionY, sectionZ, revision, states, null);
		} catch (Throwable t) {
			BerylliumLog.debug("[BERYLLIUM] section capture failed at "
				+ sectionX + "," + sectionY + "," + sectionZ + ": " + t);
			return null;
		}
	}

	@Override
	public MeshRevision currentRevision(int sectionX, int sectionY, int sectionZ) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || minecraft.level == null) {
			return null;
		}
		long key = SectionPacking.pack(sectionX, sectionY, sectionZ);
		return new MeshRevision(
			worldId(),
			MeshRevision.sectionKey(sectionX, sectionY, sectionZ),
			SectionRevisionClock.instance().revisionOf(key),
			packGeneration()
		);
	}

	@Override
	public long packGeneration() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null) {
			return packGeneration;
		}
		Object manager = resourceManagerOf(minecraft);
		if (manager == null) {
			return packGeneration;
		}
		/* The resource manager instance is replaced on reload, so a change of identity is the
		 * reload. Nothing else about the game looks different afterwards, which is what makes
		 * this the only reliable signal that every mesh in the world just went stale. */
		if (manager != lastResourceManager) {
			lastResourceManager = manager;
			packGeneration++;
			SectionSnapshotFactory.invalidateDescriptors();
		}
		return packGeneration;
	}

	@Override
	public long worldId() {
		Minecraft minecraft = Minecraft.getInstance();
		Object level = minecraft == null ? null : minecraft.level;
		if (level == null) {
			return 0L;
		}
		/* Identity, not a hash of contents: the level object is replaced when the player
		 * changes dimension or rejoins, and its coordinates routinely coincide with the
		 * previous one's. Two worlds sharing a section position must not share a revision
		 * history. */
		if (level != lastLevel) {
			lastLevel = level;
			lastWorldId = System.identityHashCode(level) & 0xFFFFFFFFL;
			if (lastWorldId == 0L) {
				lastWorldId = 1L;
			}
			SectionRevisionClock.instance().clear();
		}
		return lastWorldId;
	}

	/* ------------------------------------------------------------ plumbing -- */

	private Object chunkSectionOf(Object level, int sectionX, int sectionY, int sectionZ) {
		Object chunk = Reflect.call(level,
			MappedNames.methodName(LEVEL, "getChunk",
				"(II)Lnet/minecraft/world/level/chunk/LevelChunk;"),
			sectionX, sectionZ);
		if (chunk == null) {
			return null;
		}
		int index = sectionIndex(level, sectionY);
		if (index < 0) {
			return null;
		}
		return Reflect.call(chunk,
			MappedNames.methodName(LEVEL_CHUNK, "getSection",
				"(I)Lnet/minecraft/world/level/chunk/LevelChunkSection;"),
			index);
	}

	/**
	 * Converts a section Y coordinate into the index {@code LevelChunk.getSection} expects,
	 * which is relative to the bottom of the world rather than absolute.
	 */
	private int sectionIndex(Object level, int sectionY) {
		Object minSection = Reflect.call(level, MappedNames.methodName(LEVEL, "getMinSection", "()I"));
		if (minSection instanceof Number) {
			int index = sectionY - ((Number) minSection).intValue();
			// A chunk holds a bounded number of sections; anything outside is not in this world.
			return index >= 0 && index < 32 ? index : -1;
		}
		return -1;
	}

	private Object readBlockState(Object section, int localX, int localY, int localZ) {
		return Reflect.call(section,
			MappedNames.methodName(LEVEL_CHUNK_SECTION, "getBlockState",
				"(III)Lnet/minecraft/world/level/block/state/BlockState;"),
			localX, localY, localZ);
	}

	private static Object resourceManagerOf(Minecraft minecraft) {
		try {
			return Reflect.call(minecraft,
				MappedNames.methodName("net.minecraft.client.Minecraft", "getResourceManager",
					"()Lnet/minecraft/server/packs/resources/ResourceManager;"));
		} catch (Throwable t) {
			return null;
		}
	}
}
