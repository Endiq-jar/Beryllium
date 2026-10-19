package com.endiq.beryllium.culling.async;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Concurrent Data Management: the store the asynchronous culling pipeline writes into and
 * the render thread reads from.
 *
 * <p>The point of the store is not to remember an answer within one frame — {@link
 * com.endiq.beryllium.culling.VisibilityCulling} already does that with a plain
 * {@code HashMap}. The point is to carry answers <em>across</em> frames, so that the
 * frustum math for a box the player keeps looking at happens once on a worker thread
 * instead of once per frame on the render thread. That needs a map safe to read and write
 * from both, hence {@link ConcurrentHashMap}.
 *
 * <h2>How an answer stays honest</h2>
 *
 * <p>A cached visibility answer is only true of the frustum it was measured against, so
 * every entry carries the epoch (frame number) it belongs to and the exact box bounds it
 * describes. A lookup is accepted only when the entry's epoch is the one currently being
 * rendered; anything older is a miss, never a wrong answer. Nothing here can serve a
 * decision made against a camera the player is no longer looking through.
 *
 * <p>Storing the bounds is what makes the cross-frame carry possible. The worker re-tests
 * those bounds against the new frame's {@link FrustumPlanes} — plain doubles, no Minecraft
 * object involved — and publishes a fresh entry under the new epoch. The render thread then
 * finds its answer already waiting.
 *
 * <p>Entries are immutable and published by replacement, so a reader racing with the worker
 * sees either the previous entry or the new one. Both are internally consistent, and only
 * the one matching the current epoch is ever acted on.
 *
 * <h2>Failure behaviour</h2>
 *
 * <p>Every path here degrades to "cache miss". A miss costs exactly what culling cost
 * before this class existed: the render thread asks the live frustum. There is no state in
 * which a full, corrupted or slow cache produces a wrong culling decision — only one in
 * which it produces no speedup.
 */
public final class CullCache {
	/** Matches {@code VisibilityCulling}'s quantisation so both layers agree on buckets. */
	private static final double INFLATION_STEP = 1.0 / 64.0;
	private static final double MAX_MEMOISED_INFLATION = 4.0;
	private static final int INFLATION_BUCKETS = (int) (MAX_MEMOISED_INFLATION / INFLATION_STEP) + 1;

	/**
	 * Hard ceiling on remembered boxes.
	 *
	 * <p>Culling queries are naturally bounded by render distance, but a mod or a broken
	 * caller could ask about unbounded positions, and a cache that grows without limit in a
	 * client mod is a memory leak with extra steps. Past the cap new boxes simply are not
	 * remembered; existing entries keep working and the synchronous path is unaffected.
	 */
	private static final int MAX_ENTRIES = 8192;

	/**
	 * How many boxes one worker pass may re-test. Bounded so a worker can never be handed
	 * more work than fits between two frames on a slow device; the rest catches up next pass.
	 */
	private static final int REVALIDATE_BUDGET = 2048;

	private final ConcurrentHashMap<Long, Entry>[] buckets;

	private final AtomicLong hits = new AtomicLong();
	private final AtomicLong misses = new AtomicLong();
	private final AtomicLong stale = new AtomicLong();
	private final AtomicLong published = new AtomicLong();
	private final AtomicLong droppedOverCap = new AtomicLong();

	private volatile int size = 0;

	@SuppressWarnings("unchecked")
	public CullCache() {
		buckets = new ConcurrentHashMap[INFLATION_BUCKETS];
		for (int i = 0; i < INFLATION_BUCKETS; i++) {
			buckets[i] = new ConcurrentHashMap<>(256);
		}
	}

	/** An immutable cached visibility answer, tagged with the frame it is true for. */
	public static final class Entry {
		final long epoch;
		final boolean visible;
		final double minX;
		final double minY;
		final double minZ;
		final double maxX;
		final double maxY;
		final double maxZ;

		Entry(long epoch, boolean visible,
		      double minX, double minY, double minZ,
		      double maxX, double maxY, double maxZ) {
			this.epoch = epoch;
			this.visible = visible;
			this.minX = minX;
			this.minY = minY;
			this.minZ = minZ;
			this.maxX = maxX;
			this.maxY = maxY;
			this.maxZ = maxZ;
		}

		public boolean visible() {
			return visible;
		}

		public long epoch() {
			return epoch;
		}
	}

	/** @return the memo bucket for an inflation, or {@code -1} when it must not be cached. */
	public static int bucketOf(double inflation) {
		if (inflation > MAX_MEMOISED_INFLATION || inflation < 0.0 || Double.isNaN(inflation)) {
			return -1;
		}
		return (int) Math.min(INFLATION_BUCKETS - 1, Math.round(inflation / INFLATION_STEP));
	}

	/**
	 * Looks up a cached answer for a block position.
	 *
	 * @param key    {@code BlockPos#asLong()}
	 * @param bucket inflation bucket from {@link #bucketOf(double)}
	 * @param epoch  the frame currently being rendered
	 * @return the cached visibility, or {@code null} when nothing usable is stored
	 */
	public Boolean lookup(long key, int bucket, long epoch) {
		if (bucket < 0 || bucket >= INFLATION_BUCKETS) {
			return null;
		}
		Entry entry = buckets[bucket].get(key);
		if (entry == null) {
			misses.incrementAndGet();
			return null;
		}
		if (entry.epoch != epoch) {
			// Remembered, but measured against an older camera. Report it as a miss so the
			// caller recomputes, and count it separately because a cache where *everything*
			// is stale means the worker is not keeping up.
			stale.incrementAndGet();
			misses.incrementAndGet();
			return null;
		}
		hits.incrementAndGet();
		return entry.visible;
	}

	/**
	 * Remembers an answer, along with the bounds it was measured on so a later worker pass
	 * can re-derive it without asking Minecraft anything.
	 *
	 * <p>Never throws and never blocks: if the cache is at its cap the answer is simply not
	 * remembered, which costs a recomputation later and nothing else.
	 */
	public void store(long key, int bucket, long epoch, boolean visible,
	                  double minX, double minY, double minZ,
	                  double maxX, double maxY, double maxZ) {
		if (bucket < 0 || bucket >= INFLATION_BUCKETS) {
			return;
		}
		ConcurrentHashMap<Long, Entry> map = buckets[bucket];
		if (map.size() >= MAX_ENTRIES) {
			// Replace an existing entry for this key, but do not grow past the cap.
			if (!map.containsKey(key)) {
				droppedOverCap.incrementAndGet();
				return;
			}
		}
		map.put(key, new Entry(epoch, visible, minX, minY, minZ, maxX, maxY, maxZ));
		published.incrementAndGet();
	}

	/**
	 * Re-tests remembered boxes against a new frame's frustum and republishes them under
	 * {@code epoch}. Called on a worker thread; touches no Minecraft object.
	 *
	 * <p>Only entries older than {@code epoch} are rewritten, so a render thread that has
	 * already stored a fresh answer for this frame is never overwritten with a worker's
	 * idea of the same box.
	 *
	 * @return how many entries were republished
	 */
	public int carryForward(FrustumPlanes planes, long epoch) {
		if (planes == null) {
			return 0;
		}
		int carried = 0;
		for (ConcurrentHashMap<Long, Entry> map : buckets) {
			// entrySet(), not values(): republishing has to be keyed, and iterating entries
			// hands back both halves at once instead of forcing a lookup per box. The entry
			// type is spelled out fully because CHM's own implementation class is
			// package-private and shares its name with the nested Entry below.
			for (java.util.Map.Entry<Long, Entry> slot : map.entrySet()) {
				if (carried >= REVALIDATE_BUDGET) {
					return carried;
				}
				Entry entry = slot.getValue();
				if (entry == null || entry.epoch >= epoch) {
					// Already fresh for this frame — a render thread got here first, or a
					// previous pass carried it. Leave it alone either way.
					continue;
				}
				boolean visible = planes.isVisible(
					entry.minX, entry.minY, entry.minZ, entry.maxX, entry.maxY, entry.maxZ);
				// Republished even when the answer did not change, because it is the epoch
				// tag that makes an entry usable this frame. The common case is "no change":
				// a box the player keeps looking at stays visible.
				//
				// A conditional replace, not a put (and not Entry#setValue, which on a
				// ConcurrentHashMap is just a put): if the render thread stored a fresher
				// answer for this box while the worker was computing, the worker's copy must
				// lose. A stale overwrite would be the one way this cache could serve a
				// wrong answer, so the compare-and-set is the whole point.
				map.replace(slot.getKey(), entry,
					new Entry(epoch, visible, entry.minX, entry.minY, entry.minZ,
						entry.maxX, entry.maxY, entry.maxZ));
				carried++;
			}
		}
		return carried;
	}

	/**
	 * Drops everything. Called on world change: an answer measured in one world is not an
	 * answer about another, even when the coordinates coincide.
	 */
	public void clear() {
		for (ConcurrentHashMap<Long, Entry> map : buckets) {
			map.clear();
		}
		size = 0;
	}

	/** Total remembered boxes across all inflation buckets. */
	public int size() {
		int total = 0;
		for (ConcurrentHashMap<Long, Entry> map : buckets) {
			total += map.size();
		}
		size = total;
		return total;
	}

	public long hits() {
		return hits.get();
	}

	public long misses() {
		return misses.get();
	}

	/** Lookups that found an entry which belonged to an earlier frame. */
	public long stale() {
		return stale.get();
	}

	public long published() {
		return published.get();
	}

	/** Answers not remembered because the cache was at its cap. */
	public long droppedOverCap() {
		return droppedOverCap.get();
	}
}
