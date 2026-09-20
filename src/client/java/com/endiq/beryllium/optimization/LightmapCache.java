package com.endiq.beryllium.optimization;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.config.BerylliumConfig;
import java.util.concurrent.atomic.AtomicLong;

/**
 * No-mercy lightmap cache: avoids expensive lightmap vector math + GPU upload
 * when nothing affecting block brightness changed (gamma, effects, dimension).
 * Mirrors Lithium/Starlight-style fast-path.
 */
public final class LightmapCache {
    private static volatile long lastHash = 0;
    private static volatile boolean lastShouldUpdate = true;
    private static final AtomicLong skipped = new AtomicLong();
    private LightmapCache(){}

    public static boolean shouldSkip(float gamma, int effectHash, String dimension, boolean needsUpdate) {
        try {
            BerylliumConfig c = Beryllium.config();
            if (c == null || !c.enabled || (!c.lightmapCache && !c.avoidUpdatingLightmap && !c.cacheLightmap)) return false;
            if (!needsUpdate) return false;
            long hash = Double.doubleToLongBits(gamma) * 31 + effectHash * 31 + (dimension == null ? 0 : dimension.hashCode());
            if (hash == lastHash && !lastShouldUpdate) {
                skipped.incrementAndGet();
                return true;
            }
            lastHash = hash;
            lastShouldUpdate = needsUpdate;
            return false;
        } catch (Throwable t) { return false; }
    }
    public static void markDirty() { lastShouldUpdate = true; }
    public static long skipped() { return skipped.get(); }
}
