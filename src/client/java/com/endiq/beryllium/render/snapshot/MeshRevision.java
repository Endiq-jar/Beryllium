package com.endiq.beryllium.render.snapshot;

import com.endiq.beryllium.chunk.SectionPacking;

/**
 * The identity of the inputs a mesh was built from.
 *
 * <p>A mesh build takes time, and three things can change while it runs: the level the
 * section belongs to, the section's own contents, and the resource pack that decided what
 * the blocks look like. If any of them moved, the finished mesh describes a world that is
 * no longer on screen, and uploading it draws stale geometry over current geometry.
 *
 * <p>This is the value the render thread compares against before it accepts a result. The
 * comparison has to be exact and it has to cover all three, which is why they travel
 * together as one immutable value rather than being checked ad hoc at the upload site:
 *
 * <ul>
 *   <li><strong>World.</strong> Changing dimension, or leaving and rejoining, produces a new
 *       level. Coordinates coincide across worlds constantly — the spawn point is usually
 *       near the origin in both — so a mesh built in the Nether must not be drawn in the
 *       Overworld just because the section position matches.</li>
 *   <li><strong>Section revision.</strong> Bumped whenever the section's contents change. A
 *       revision that does not match exactly means the mesh is stale, including when it is
 *       <em>lower</em> than the current one: a build that started before a later build
 *       finished must not land after it and overwrite the newer geometry.</li>
 *   <li><strong>Resource-pack generation.</strong> Reloaded packs change block models and
 *       textures without touching a single voxel. Every section's geometry is stale the
 *       moment a reload completes, and nothing else about it looks different.</li>
 * </ul>
 *
 * <p>Rejecting a stale mesh is always the safe direction. The section is still dirty, so it
 * gets rebuilt from current inputs; the cost is one wasted build. Accepting one is a visible
 * artefact with no error to point at.
 */
public final class MeshRevision {
	/** Value meaning "no revision", used before a section has ever been captured. */
	public static final MeshRevision NONE = new MeshRevision(0L, 0L, 0L, 0L);

	private final long worldId;
	private final long sectionKey;
	private final long sectionRevision;
	private final long packGeneration;

	public MeshRevision(long worldId, long sectionKey, long sectionRevision, long packGeneration) {
		this.worldId = worldId;
		this.sectionKey = sectionKey;
		this.sectionRevision = sectionRevision;
		this.packGeneration = packGeneration;
	}

	/**
	 * Packs section coordinates into one long.
	 *
	 * <p>Delegates to {@link SectionPacking} rather than defining a second layout. That class
	 * already mirrors Minecraft's own {@code SectionPos.asLong} bit-for-bit and is what the
	 * chunk rebuild queue keys on, so a snapshot, a rebuild request and a cached mesh all
	 * describe "which section is this" the same way. Two encodings for one concept is how a
	 * revision check ends up comparing keys that were never about the same section.
	 */
	public static long sectionKey(int sectionX, int sectionY, int sectionZ) {
		return SectionPacking.pack(sectionX, sectionY, sectionZ);
	}

	public static int sectionXOf(long sectionKey) {
		return SectionPacking.x(sectionKey);
	}

	public static int sectionYOf(long sectionKey) {
		return SectionPacking.y(sectionKey);
	}

	public static int sectionZOf(long sectionKey) {
		return SectionPacking.z(sectionKey);
	}

	public long worldId() {
		return worldId;
	}

	public long sectionKey() {
		return sectionKey;
	}

	public long sectionRevision() {
		return sectionRevision;
	}

	public long packGeneration() {
		return packGeneration;
	}

	/**
	 * Whether a result built from this revision may still be used, given the inputs as they
	 * are now.
	 *
	 * <p>Exact equality on all four fields. Not "at least as new": a mesh from an older
	 * revision arriving after a newer one is precisely the case that produces visible
	 * flicker, and treating it as acceptable would reintroduce it.
	 */
	public boolean isCurrent(MeshRevision now) {
		if (now == null) {
			return false;
		}
		return worldId == now.worldId
			&& sectionKey == now.sectionKey
			&& sectionRevision == now.sectionRevision
			&& packGeneration == now.packGeneration;
	}

	/** Same section, possibly a different revision. */
	public boolean sameSection(MeshRevision other) {
		return other != null && worldId == other.worldId && sectionKey == other.sectionKey;
	}

	@Override
	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}
		if (!(other instanceof MeshRevision)) {
			return false;
		}
		MeshRevision that = (MeshRevision) other;
		return worldId == that.worldId && sectionKey == that.sectionKey
			&& sectionRevision == that.sectionRevision && packGeneration == that.packGeneration;
	}

	@Override
	public int hashCode() {
		long h = worldId;
		h = h * 31 + sectionKey;
		h = h * 31 + sectionRevision;
		h = h * 31 + packGeneration;
		return (int) (h ^ (h >>> 32));
	}

	@Override
	public String toString() {
		return "MeshRevision[world=" + worldId
			+ " section=" + sectionXOf(sectionKey) + "," + sectionYOf(sectionKey) + ","
			+ sectionZOf(sectionKey)
			+ " rev=" + sectionRevision + " pack=" + packGeneration + "]";
	}
}
