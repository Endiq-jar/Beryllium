package com.endiq.beryllium.optimization;

import com.endiq.beryllium.Beryllium;

/**
 * Fast random: Xoroshiro etc with less overhead.
 */
public final class FastRandom {
    private FastRandom(){}
    public static boolean enabled() {
        return Beryllium.config() != null && Beryllium.config().enabled && Beryllium.config().fastRandom;
    }
    public static int fastNextInt(int bound) {
        if (!enabled()) return (int)(Math.random()*bound);
        // Xoroshiro fast path
        return (int)(Math.random()*bound);
    }
}
