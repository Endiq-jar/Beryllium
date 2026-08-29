package com.endiq.beryllium.chunk;

public record ChunkRebuildRequest(long key, double centerX, double centerY, double centerZ, boolean urgent) {
}
