package com.endiq.beryllium.render;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.config.BerylliumConfig;
import com.endiq.beryllium.culling.VisibilityCulling;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps the render state of block entities that cannot have changed, so the game stops building
 * it again every frame.
 *
 * <p>This is the "static meshing" half of block entity rendering: a sign's text, a banner's
 * pattern and a bed's colour are the same this frame as they were last frame, and computing them
 * again sixty times a second is pure cost. Everything that <em>is</em> animated — chest lids,
 * shulker lids, bells, campfires, decorated pots, enchanting tables — is deliberately not on this
 * list and keeps going through the game's own per-frame path, because their state genuinely
 * changes without a block update.
 *
 * <p>An entry is reused only while all of these hold:
 * <ul>
 *   <li>the type is one of the static ones above;</li>
 *   <li>it is further away than {@code blockEntityMeshMinDistance} — close enough to be the thing
 *       the player is looking at, the game does the work itself;</li>
 *   <li>the block state object is the same one (an identical state is enough to change what a
 *       block entity looks like, so this is an identity check, not an equality check);</li>
 *   <li>{@code setChanged} has not been called on it since — which is what sign editing, banner
 *       re-patterning and every other content change does;</li>
 *   <li>it is younger than {@code blockEntityMeshCacheFrames} frames.</li>
 * </ul>
 *
 * <p>Anything else, and the state is dropped and rebuilt by the game exactly as before.
 */
public final class BlockEntityMeshCache {
	private BlockEntityMeshCache() {
	}

	/**
	 * The block entities whose renderers are a pure function of their saved contents. Named as
	 * strings so a release that moved one of them simply stops matching instead of failing.
	 */
	private static final Set<String> STATIC_TYPES = Set.of(
			"net.minecraft.world.level.block.entity.SignBlockEntity",
			"net.minecraft.world.level.block.entity.HangingSignBlockEntity",
			"net.minecraft.world.level.block.entity.BannerBlockEntity",
			"net.minecraft.world.level.block.entity.BedBlockEntity"
	);

	private record Entry(Object state, Object blockState, long frame, long changes) {
	}

	private static final Map<BlockPos, Entry> CACHE = new ConcurrentHashMap<>();

	/** Counts {@code setChanged} calls per position, so a content change invalidates an entry. */
	private static final Map<BlockPos, Long> CHANGES = new ConcurrentHashMap<>();

	private static volatile long frame;

	/** The camera position captured once per frame; asking for it per block entity would itself
	 *  cost more than the caching saves. */
	private static volatile Vec3 frameCamera;

	/** @return a state that can be used again, or null when the game must build one. */
	public static Object reuse(BlockEntity blockEntity) {
		try {
			BerylliumConfig config = Beryllium.config();
			if (config == null || !config.enabled || !config.blockEntityMeshing || blockEntity == null) {
				return null;
			}
			if (!STATIC_TYPES.contains(blockEntity.getClass().getName())) {
				return null;
			}
			if (!berylliumFarEnough(blockEntity, config)) {
				return null;
			}
			BlockPos pos = blockEntity.getBlockPos();
			if (pos == null) {
				return null;
			}
			Entry entry = CACHE.get(pos);
			if (entry == null || entry.state() == null) {
				return null;
			}
			if (entry.blockState() != blockEntity.getBlockState()) {
				CACHE.remove(pos);
				return null;
			}
			if (entry.changes() != changesAt(pos)) {
				CACHE.remove(pos);
				return null;
			}
			long age = frame - entry.frame();
			if (age > Math.max(1, config.blockEntityMeshCacheFrames)) {
				CACHE.remove(pos);
				return null;
			}
			return entry.state();
		} catch (Throwable t) {
			return null;
		}
	}

	/** Stores the state the game just built, so the next frame can skip building it. */
	public static void remember(BlockEntity blockEntity, Object state) {
		try {
			BerylliumConfig config = Beryllium.config();
			if (config == null || !config.enabled || !config.blockEntityMeshing
					|| blockEntity == null || state == null) {
				return;
			}
			if (!STATIC_TYPES.contains(blockEntity.getClass().getName())) {
				return;
			}
			BlockPos pos = blockEntity.getBlockPos();
			if (pos == null) {
				return;
			}
			CACHE.put(pos, new Entry(state, blockEntity.getBlockState(), frame, changesAt(pos)));
		} catch (Throwable t) {
			// Not caching is always correct.
		}
	}

	/** A block entity changed; whatever was cached for it is no longer what it looks like. */
	public static void invalidate(BlockPos pos) {
		long next = changesAt(pos) + 1L;
		CHANGES.put(pos, next);
		CACHE.remove(pos);
	}

	/** Drops everything; used when the hook itself is unsure. */
	public static void invalidateAll() {
		CACHE.clear();
		CHANGES.clear();
	}

	/** Called once per frame: ages entries out and keeps the maps from growing without bound. */
	public static void tick() {
		long current = ++frame;
		frameCamera = VisibilityCulling.cameraPosition();
		BerylliumConfig config = Beryllium.config();
		long limit = config == null ? 600 : Math.max(1, config.blockEntityMeshCacheFrames);
		if ((current & 0xFF) != 0) {
			// Only sweep every 256 frames; the entries are cheap and the frame counter alone
			// decides whether they are still trusted.
			return;
		}
		CACHE.entrySet().removeIf(entry -> current - entry.getValue().frame() > limit);
		if (CHANGES.size() > 8192) {
			CHANGES.clear();
			CACHE.clear();
		}
	}

	public static int size() {
		return CACHE.size();
	}

	private static long changesAt(BlockPos pos) {
		return CHANGES.getOrDefault(pos, 0L);
	}

	private static boolean berylliumFarEnough(BlockEntity blockEntity, BerylliumConfig config) {
		Vec3 camera = frameCamera;
		if (camera == null) {
			// No camera yet (world loading): nothing to compare against, so do not cache.
			return false;
		}
		BlockPos pos = blockEntity.getBlockPos();
		if (pos == null) {
			return false;
		}
		double dx = pos.getX() + 0.5 - camera.x;
		double dy = pos.getY() + 0.5 - camera.y;
		double dz = pos.getZ() + 0.5 - camera.z;
		double minDistance = config.blockEntityMeshMinDistance;
		return dx * dx + dy * dy + dz * dz >= minDistance * minDistance;
	}
}
