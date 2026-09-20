package com.endiq.beryllium.optimization;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.config.BerylliumConfig;

/**
 * Sky color cubic sampler cache (1.21.10 and below).
 * Vanilla loops 216 times per frame for biome sky blending. We cache per tick
 * and fast-path when surrounding biomes share sky color.
 */
public final class SkyColorCache {
    private static volatile long lastTick = -1;
    private static volatile int cachedColor = -1;
    private static volatile float cr, cg, cb;
    private SkyColorCache(){}
    public static boolean shouldUseFastPath(long tick, boolean uniformBiomes) {
        BerylliumConfig c = Beryllium.config();
        if (c == null || !c.enabled || (!c.skyColorCache && !c.skyColorOptimization && !c.cacheSkyColor)) return false;
        if (uniformBiomes) return true;
        return tick == lastTick && cachedColor != -1;
    }
    public static int getCachedColor() { return cachedColor; }
    public static void store(long tick, int color) { lastTick = tick; cachedColor = color; }
    public static void storeFast(float r, float g, float b) { cr=r; cg=g; cb=b; }
}
