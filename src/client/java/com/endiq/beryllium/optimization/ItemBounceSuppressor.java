package com.endiq.beryllium.optimization;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.config.BerylliumConfig;

/** Removes bounce effect from items for cleaner visuals and less CPU. */
public final class ItemBounceSuppressor {
    private ItemBounceSuppressor(){}
    public static boolean shouldSuppress() {
        BerylliumConfig c = Beryllium.config();
        return c != null && c.enabled && (c.itemBounceSuppress || c.removeItemBounce || c.itemNoBounce);
    }
    public static float suppressBounce(float original) {
        return shouldSuppress() ? 0.0f : original;
    }
}
