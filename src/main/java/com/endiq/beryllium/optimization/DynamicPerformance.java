package com.endiq.beryllium.optimization;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.config.BerylliumConfig;

/**
 * Dynamic performance checks: auto-adjust view/simulation/chunk-tick distance and mobcaps based on tick time.
 */
public final class DynamicPerformance {
    private static volatile long lastAdjustTick = 0;
    private DynamicPerformance(){}
    public static boolean enabled() {
        BerylliumConfig c = Beryllium.config();
        return c != null && c.enabled && (c.dynamicPerformance || c.dynamicPerformanceChecks);
    }
    public static boolean shouldAdjust(long tick, double tickTimeMs) {
        if (!enabled()) return false;
        if (tick - lastAdjustTick < 100) return false;
        BerylliumConfig c = Beryllium.config();
        double target = c == null ? 45.0 : c.dynamicTargetTickTime;
        if (tickTimeMs > target) {
            lastAdjustTick = tick;
            return true;
        }
        if (tickTimeMs < target * 0.7 && tick - lastAdjustTick > 600) {
            lastAdjustTick = tick;
            return true;
        }
        return false;
    }
    public static int adjustedViewDistance(int current, double tickTimeMs) {
        BerylliumConfig c = Beryllium.config();
        if (c == null) return current;
        double target = c.dynamicTargetTickTime;
        if (tickTimeMs > target) return Math.max(c.dynamicMinViewDistance, current - 1);
        if (tickTimeMs < target * 0.7) return Math.min(c.dynamicMaxViewDistance, current + 1);
        return current;
    }
}
