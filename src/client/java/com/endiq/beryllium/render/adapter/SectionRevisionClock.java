package com.endiq.beryllium.render.adapter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Counts how many times each section has been marked dirty, which is what makes a section's
 * revision observable at all.
 *
 * <p>Minecraft does not expose a per-section revision counter, and the engine's snapshot
 * contract needs one: without it the render thread has no way to tell whether a mesh that
 * just came back from a worker describes the section as it is now or as it was a moment ago.
 * So Beryllium keeps its own, incremented at the points where the game itself says "this
 * section changed" — the same dirty-mark entry points the rebuild prioritisation already
 * hooks.
 *
 * <p>The counter only ever moves forward and is never read as an absolute. Two revisions
 * being equal means no dirty mark was observed in between, which is the question the
 * staleness check actually asks.
 *
 * <h2>The limit on this</h2>
 *
 * <p>It depends on those dirty-mark entry points existing. They are present from 1.19.4
 * through 26.1.2 and gone in 26.2 and 26.3, where the section update path was rewritten
 * around a render-state list and {@code RenderSection} no longer carries a dirty flag at all.
 * On those releases nothing increments this clock, every section looks permanently
 * up-to-date, and a stale mesh would be accepted rather than rejected.
 *
 * <p>That is why {@link ReflectiveTerrainAdapter#isAvailable()} reports false where the clock
 * cannot be driven. An adapter that cannot detect staleness must not be used to feed a
 * renderer, because the failure is silent: geometry that does not match the world, with no
 * error anywhere.
 */
public final class SectionRevisionClock {
	private static final SectionRevisionClock INSTANCE = new SectionRevisionClock();

	/**
	 * Ceiling on tracked sections.
	 *
	 * <p>Sections leave the loaded area constantly, and their entries would otherwise
	 * accumulate for the life of the process. Past the cap the clock stops recording new
	 * sections rather than evicting old ones: evicting would reset a live section's revision
	 * to zero, and a revision that goes backwards is exactly what the staleness check cannot
	 * distinguish from a real change.
	 */
	private static final int MAX_TRACKED_SECTIONS = 16384;

	private final Map<Long, AtomicLong> revisions = new ConcurrentHashMap<>();
	private final AtomicLong dirtyMarks = new AtomicLong();

	private SectionRevisionClock() {
	}

	public static SectionRevisionClock instance() {
		return INSTANCE;
	}

	/**
	 * Records that a section changed.
	 *
	 * <p>Called from the dirty-mark mixins, on the render thread, at the moment the game
	 * decides a section needs rebuilding.
	 *
	 * @param sectionKey packed section coordinates
	 */
	public void noteDirty(long sectionKey) {
		dirtyMarks.incrementAndGet();
		if (revisions.size() >= MAX_TRACKED_SECTIONS && !revisions.containsKey(sectionKey)) {
			return;
		}
		revisions.computeIfAbsent(sectionKey, unused -> new AtomicLong()).incrementAndGet();
	}

	/**
	 * The revision of a section: how many dirty marks have been observed for it.
	 *
	 * <p>Zero for a section never marked, which is the right starting value — the first
	 * capture and the first check will agree on it.
	 */
	public long revisionOf(long sectionKey) {
		AtomicLong counter = revisions.get(sectionKey);
		return counter == null ? 0L : counter.get();
	}

	/** Total dirty marks seen, for the debug overlay. */
	public long dirtyMarks() {
		return dirtyMarks.get();
	}

	public int trackedSections() {
		return revisions.size();
	}

	/** Whether the clock is full and has stopped recording new sections. */
	public boolean isSaturated() {
		return revisions.size() >= MAX_TRACKED_SECTIONS;
	}

	/** Drops everything. Called when the level changes: revisions belong to one world. */
	public void clear() {
		revisions.clear();
		dirtyMarks.set(0L);
	}
}
