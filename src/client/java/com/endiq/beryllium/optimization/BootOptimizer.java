package com.endiq.beryllium.optimization;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.config.BerylliumConfig;

/** Defers non-essential init to make game boot faster. */
public final class BootOptimizer {
    private BootOptimizer(){}
    public static boolean shouldDefer(String task) {
        BerylliumConfig c = Beryllium.config();
        if (c == null || !c.enabled || (!c.fastBoot && !c.deferNonEssentialInit && !c.bootOptimization)) return false;
        // defer non-critical tasks
        if (task == null) return false;
        String t = task.toLowerCase();
        return t.contains("shader") || t.contains("preload") || t.contains("cache") || t.contains("overlay") || t.contains("telemetry");
    }
}
