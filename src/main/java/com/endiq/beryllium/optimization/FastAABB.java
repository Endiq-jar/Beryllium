package com.endiq.beryllium.optimization;

import com.endiq.beryllium.Beryllium;

/** Fast AABB direction calc. */
public final class FastAABB {
    private FastAABB(){}
    public static boolean enabled() {
        return Beryllium.config() != null && Beryllium.config().enabled && Beryllium.config().fastAabb;
    }
}
