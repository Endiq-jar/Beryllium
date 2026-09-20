package com.endiq.beryllium.optimization;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.config.BerylliumConfig;

/**
 * Redstone wire power optimization: compute whole network before updating power levels.
 * Each wire checks non-wire power once, wire power twice, then propagates from sources.
 */
public final class RedstoneOptimizer {
    private static final ThreadLocal<Boolean> inNetworkUpdate = ThreadLocal.withInitial(()->false);
    private RedstoneOptimizer(){}
    public static boolean enabled() {
        BerylliumConfig c = Beryllium.config();
        return c != null && c.enabled && (c.redstoneOptimization || c.redstonePowerOptimization || c.optimizeRedstone || c.fastRedstoneWire);
    }
    public static boolean isInNetworkUpdate() { return inNetworkUpdate.get(); }
    public static void beginNetwork() { inNetworkUpdate.set(true); }
    public static void endNetwork() { inNetworkUpdate.set(false); }
}
