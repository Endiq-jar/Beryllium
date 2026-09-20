package com.endiq.beryllium.optimization;

import com.endiq.beryllium.Beryllium;

/**
 * SIMD via jdk.incubator.vector. Requires JVM arg --add-modules jdk.incubator.vector
 * Accelerates Frustum and Weather rendering.
 */
public final class SimdHelper {
    private static volatile Boolean available = null;
    private SimdHelper(){}
    public static boolean isAvailable() {
        if (available != null) return available;
        try {
            if (Beryllium.config() == null || !Beryllium.config().enabled || !Beryllium.config().simdEnabled) { available = false; return false; }
            Class.forName("jdk.incubator.vector.FloatVector");
            available = true;
        } catch (Throwable t) { available = false; }
        return available;
    }
    public static boolean shouldUseVector() { return isAvailable(); }
}
