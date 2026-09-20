package com.endiq.beryllium.optimization;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.config.BerylliumConfig;

/** Paintings as blocks with baked models instead of ticking entities. */
public final class PaintingOptimization {
    private PaintingOptimization(){}
    public static boolean enabled() {
        BerylliumConfig c = Beryllium.config();
        return c != null && c.enabled && (c.paintingOptimization || c.paintingAsBlock || c.optimizePaintings);
    }
    public static boolean useBakedModel() { return enabled() && Beryllium.config().paintingsUseBakedModels; }
}
