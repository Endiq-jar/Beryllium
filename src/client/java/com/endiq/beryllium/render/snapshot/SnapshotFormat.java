package com.endiq.beryllium.render.snapshot;

/**
 * The Minecraft half of the section-snapshot wire format.
 *
 * <p>These constants mirror {@code engine/src/snapshot.h} exactly. That header is the
 * contract; this class is the Java transcription of it, and the two must be changed
 * together. The version number is what makes a mismatch survivable: a producer and a
 * consumer that disagree about the layout will disagree about {@link #VERSION}, and the
 * engine refuses a version it does not recognise rather than misreading the buffer.
 *
 * <p>Everything is little-endian, matching the C side.
 */
public final class SnapshotFormat {
	/** {@code 'B','R','S','1'} read as a little-endian u32. */
	public static final int MAGIC = 0x31535242;

	/** Bump this whenever the layout changes, on both sides at once. */
	public static final int VERSION = 1;

	/** Bytes in the fixed header, before the palette. */
	public static final int HEADER_BYTES = 56;

	/** Header flag bit 0: a light block follows the voxel indices. */
	public static final int FLAG_HAS_LIGHT = 0x1;

	/** Voxels in one 16x16x16 section. */
	public static final int SECTION_VOLUME = 4096;

	public static final int SECTION_SIDE = 16;

	/* Block flags. Must match BERYL_BFLG_* in engine/src/blocks.h. */
	public static final int FLAG_OPAQUE = 1;
	public static final int FLAG_SOLID = 1 << 1;
	public static final int FLAG_CUTOUT = 1 << 2;
	public static final int FLAG_TRANSLUCENT = 1 << 3;
	public static final int FLAG_SAME_TYPE_CULL = 1 << 4;
	public static final int FLAG_EMIT_LIGHT = 1 << 5;

	/* Render layers. Must match BerylBlockInfo.render_layer. */
	public static final int LAYER_OPAQUE = 0;
	public static final int LAYER_CUTOUT = 1;
	public static final int LAYER_TRANSLUCENT = 2;

	/* Model kinds. Must match BerylModelKind in engine/src/snapshot.h. */
	public static final int MODEL_AIR = 0;
	public static final int MODEL_FULL_CUBE = 1;
	public static final int MODEL_CROSS = 2;
	public static final int MODEL_LIQUID = 3;
	public static final int MODEL_CUSTOM = 4;

	/**
	 * The narrowest bit width that can express a palette of {@code count} entries.
	 *
	 * <p>Only 1, 2, 4, 8 and 16 are legal — the engine refuses anything else — so the
	 * result is rounded up to the next legal width rather than being the exact
	 * {@code ceil(log2(count))}.
	 */
	public static int bitsPerEntryFor(int paletteCount) {
		if (paletteCount <= 1) {
			return 1;
		}
		if (paletteCount <= 2) {
			return 1;
		}
		if (paletteCount <= 4) {
			return 2;
		}
		if (paletteCount <= 16) {
			return 4;
		}
		if (paletteCount <= 256) {
			return 8;
		}
		return 16;
	}

	/** Bytes needed to pack {@link #SECTION_VOLUME} indices at {@code bitsPerEntry}. */
	public static int indexBytes(int bitsPerEntry) {
		return (SECTION_VOLUME * bitsPerEntry + 7) / 8;
	}

	private SnapshotFormat() {
	}
}
