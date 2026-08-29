package com.endiq.beryllium.chunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Holds pending chunk-section rebuilds, deduplicated by key, and orders them against the
 * camera's current position/facing on demand.
 *
 * <p><b>Sodium-companion architecture:</b> this class has no idea whether Sodium is
 * loaded, and shouldn't — that's a compatibility decision, not a scheduling one. Any
 * future code that feeds real game events (block updates, chunk loads) into this queue
 * must first check {@code Beryllium.isChunkOptimizationDeferredToOtherMod()} and simply
 * not do so if it's true. When Sodium is present it already replaces vanilla's chunk
 * renderer wholesale, so this queue has nothing correct to attach to and must stay
 * inert, the same way Lithium avoids rendering entirely so it composes safely whether
 * or not Sodium is present.
 *
 * <p>Scores aren't cached at submission time, because the camera moves every frame and a
 * stale score would defeat the point of view-aware prioritization. Instead,
 * {@link #drain} recomputes scores against whatever camera state you pass in. For very
 * large pending counts this is an O(n log n) sort per call, so callers driving this from
 * the render thread should throttle how often they call it (e.g. once every few ticks)
 * rather than every single frame — this class doesn't do that throttling itself, since
 * it has no opinion on frame timing (that's {@code FrameBudgetScheduler}'s job).
 *
 * <p>Not thread-safe. Intended to be owned by a single thread (the render/main thread),
 * same as {@code FrameBudgetScheduler}.
 */
public final class ChunkRebuildQueue {
	private final Map<Long, ChunkRebuildRequest> pending = new LinkedHashMap<>();

	/** Submits a new request, or replaces an existing one for the same key (e.g. a
	 *  chunk that was queued normally becomes urgent after a nearby block edit). */
	public void submitOrUpdate(ChunkRebuildRequest request) {
		pending.put(request.key(), request);
	}

	public boolean contains(long key) {
		return pending.containsKey(key);
	}

	public void remove(long key) {
		pending.remove(key);
	}

	public int size() {
		return pending.size();
	}

	/**
	 * Returns all pending requests ordered highest-priority-first against the given
	 * camera state, without removing anything.
	 */
	public List<ChunkRebuildRequest> orderedSnapshot(
		double camX, double camY, double camZ,
		double forwardX, double forwardY, double forwardZ
	) {
		List<ChunkRebuildRequest> list = new ArrayList<>(pending.values());
		list.sort(Comparator.comparingDouble(r -> ChunkRebuildPriority.score(
			camX, camY, camZ, forwardX, forwardY, forwardZ,
			r.centerX(), r.centerY(), r.centerZ(), r.urgent()
		)));
		return list;
	}

	/**
	 * Removes and returns up to {@code maxCount} of the highest-priority pending
	 * requests, ordered against the given camera state.
	 */
	public List<ChunkRebuildRequest> drain(
		int maxCount,
		double camX, double camY, double camZ,
		double forwardX, double forwardY, double forwardZ
	) {
		if (maxCount <= 0) {
			return List.of();
		}
		List<ChunkRebuildRequest> ordered = orderedSnapshot(camX, camY, camZ, forwardX, forwardY, forwardZ);
		int count = Math.min(maxCount, ordered.size());
		List<ChunkRebuildRequest> result = new ArrayList<>(ordered.subList(0, count));
		for (ChunkRebuildRequest r : result) {
			pending.remove(r.key());
		}
		return result;
	}
}
