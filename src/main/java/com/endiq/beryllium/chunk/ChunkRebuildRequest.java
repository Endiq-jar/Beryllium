package com.endiq.beryllium.chunk;

/**
 * A pending chunk-section rebuild, waiting in {@link ChunkRebuildQueue}.
 *
 * <p>{@code key} is the 64-bit section key as packed by {@link SectionPacking} (the same
 * layout as {@code SectionPos.asLong}) — this class doesn't know or care about
 * Minecraft's own coordinate packing; it only uses {@code key} for dedup/removal and
 * {@code centerX/Y/Z} (world-space section center, also derived via
 * {@link SectionPacking}) purely for distance/view-alignment scoring in
 * {@link ChunkRebuildPriority}.
 *
 * <p>{@code urgent} marks a request that must jump the queue regardless of distance or
 * facing — e.g. a block was just placed/broken in a section the player can see right
 * now, and leaving a hole or a floating update unrendered for multiple frames would be
 * a correctness issue, not just a performance one.
 */
public record ChunkRebuildRequest(long key, double centerX, double centerY, double centerZ, boolean urgent) {
}
