package com.endiq.beryllium.optimization;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.config.BerylliumConfig;

/** World gen optimization: packed storage, reduced allocations, palette tricks. */
public final class WorldGenOptimizer {
    private WorldGenOptimizer(){}
    public static boolean enabled() {
        BerylliumConfig c = Beryllium.config();
        return c != null && c.enabled && (c.worldGenOptimization || c.optimizeWorldGen);
    }
    public static boolean shouldUsePacked() {
        return enabled() && Beryllium.config().packedWorldStorage;
    }
}
