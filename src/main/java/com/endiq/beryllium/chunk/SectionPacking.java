package com.endiq.beryllium.chunk;

/**
 * Pack/unpack chunk-section coordinates to/from the 64-bit section key used by
 * {@link ChunkRebuildRequest} and {@link ChunkRebuildQueue}.
 *
 * <p>This mirrors Minecraft's {@code SectionPos.asLong}/{@code SectionPos.x/y/z} bit
 * layout exactly (x: 22 bits at 42, y: 20 bits at 0, z: 22 bits at 20), implemented as
 * pure integer math so the chunk-prioritization engine stays free of Minecraft imports:
 *
 * <pre>{@code
 * key = ((long)(x & 4194303) << 42) | ((long)(y & 1048575)) | ((long)(z & 4194303) << 20)
 * x   = (int)(key >> 42)
 * y   = (int)(key << 44 >> 44)
 * z   = (int)((key >> 20) << 42 >> 42)
 * }</pre>
 *
 * <p>Bit fields (64 bits total, exactly as in Minecraft's own packing): y at 0..19
 * (20 bits), z at 20..41 (22 bits), x at 42..63 (22 bits).
 *
 * <p>The layout is stable across Minecraft versions (it predates the
 * {@code SectionPos} class itself), so if the day ever comes that Mojang repacks it,
 * only this class needs to change — the scoring/queueing code is key-agnostic.
 */
public final class SectionPacking {
	private SectionPacking() {
	}

	private static final long X_MASK = 4194303L; // 2^22 - 1
	private static final long Y_MASK = 1048575L; // 2^20 - 1

	/** Packs three section coordinates into the canonical 64-bit key. */
	public static long pack(int sectionX, int sectionY, int sectionZ) {
		return (sectionX & X_MASK) << 42 | (sectionY & Y_MASK) | (sectionZ & X_MASK) << 20;
	}

	/** Section X coordinate (each unit = 16 blocks). */
	public static int x(long key) {
		return (int) (key >> 42);
	}

	/** Section Y coordinate (each unit = 16 blocks). */
	public static int y(long key) {
		return (int) (key << 44 >> 44);
	}

	/** Section Z coordinate (each unit = 16 blocks). */
	public static int z(long key) {
		return (int) (key >> 20 << 42 >> 42);
	}

	/** World-space X of the section's center block. */
	public static double centerX(long key) {
		return (double) (x(key) << 4) + 8.0;
	}

	/** World-space Y of the section's center block. */
	public static double centerY(long key) {
		return (double) (y(key) << 4) + 8.0;
	}

	/** World-space Z of the section's center block. */
	public static double centerZ(long key) {
		return (double) (z(key) << 4) + 8.0;
	}
}
