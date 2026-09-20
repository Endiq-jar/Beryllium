package com.endiq.beryllium.optimization;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.config.BerylliumConfig;

/** Chunk ticking distance management. */
public final class ChunkTickManager {
    private ChunkTickManager(){}
    public static boolean enabled() {
        BerylliumConfig c = Beryllium.config();
        return c != null && c.enabled && (c.chunkTickDistance || c.chunkTickDistanceOptimization);
    }
    public static int effectiveRadius(int vanillaRadius) {
        if (!enabled()) return vanillaRadius;
        BerylliumConfig c = Beryllium.config();
        int cfg = c == null ? 6 : (c.chunkTickDistanceValue != 0 ? c.chunkTickDistanceValue : c.chunkTickRadius);
        return Math.min(vanillaRadius, Math.max(2, cfg));
    }
    public static boolean shouldTickChunk(int chunkX, int chunkZ, int centerX, int centerZ) {
        if (!enabled()) return true;
        int radius = effectiveRadius(8);
        int dx = Math.abs(chunkX - centerX);
        int dz = Math.abs(chunkZ - centerZ);
        return dx <= radius && dz <= radius;
    }
}
