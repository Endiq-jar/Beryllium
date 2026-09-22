package com.endiq.beryllium.optimization;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.config.BerylliumConfig;
import net.minecraft.client.Minecraft;

/**
 * Client-side potato renderer — throttles chunk rebuilds, disables fancy, clamps distances.
 *
 * <p>Called from render mixins to decide per-frame whether a chunk section should be
 * rebuilt, whether a particle should spawn, and whether the frame should be paced.
 * All checks are branch-predictor friendly (early-out on !potatoEnabled) and never
 * allocate.
 */
public final class PotatoRenderOptimizer {
    private PotatoRenderOptimizer() {}

    public static boolean shouldCullParticle(double dist) {
        double limit = PotatoOptimizer.particleCullingDistance();
        return dist > limit * limit;
    }

    public static boolean shouldCullEntity(double dist) {
        double limit = PotatoOptimizer.entityCullingDistance();
        return dist > limit * limit;
    }

    public static boolean shouldThrottleChunkBuild(int pending) {
        if (!PotatoOptimizer.shouldThrottleChunkUpdates()) return false;
        int limit = PotatoOptimizer.chunkUpdatesPerFrame();
        return pending > limit;
    }

    public static boolean shouldForceFastGraphics() {
        return PotatoOptimizer.shouldForceFastGraphics();
    }

    public static void applyPotatoGraphics(Minecraft mc) {
        if (mc == null) return;
        try {
            BerylliumConfig c = Beryllium.config();
            if (c == null || !c.enabled || !PotatoOptimizer.potatoEnabled()) return;
            // Reflective graphics tuning — version-agnostic (1.17 uses different OptionInstance types)
            // We intentionally use raw reflection so the same bytecode compiles from 1.17 to 26.3.
            Object options = mc.options;
            if (options == null) return;
            if (c.potatoClampViewDistance) {
                try {
                    Object vd = options.getClass().getMethod("renderDistance").invoke(options);
                    vd.getClass().getMethod("set", Object.class).invoke(vd, Math.min((Integer) vd.getClass().getMethod("get").invoke(vd), c.potatoViewDistance));
                } catch (Throwable t) {}
            }
            if (c.potatoDisableFancyGraphics || c.potatoForceFastGraphics) {
                try {
                    Object gm = options.getClass().getMethod("graphicsMode").invoke(options);
                    Object cur = gm.getClass().getMethod("get").invoke(gm);
                    if (!"FAST".equals(cur.toString())) {
                        for (Object e : cur.getClass().getEnumConstants()) {
                            if ("FAST".equals(e.toString())) { gm.getClass().getMethod("set", Object.class).invoke(gm, e); break; }
                        }
                    }
                } catch (Throwable t) {}
            }
            if (c.potatoDisableSmoothLighting) {
                try {
                    Object ao = options.getClass().getMethod("ambientOcclusion").invoke(options);
                    ao.getClass().getMethod("set", Object.class).invoke(ao, false);
                } catch (Throwable t) {}
            }
            if (c.potatoDisableBiomeBlending) {
                try {
                    Object bb = options.getClass().getMethod("biomeBlendRadius").invoke(options);
                    bb.getClass().getMethod("set", Object.class).invoke(bb, 0);
                } catch (Throwable t) {}
            }
            if (c.potatoDisableClouds) {
                try {
                    Object ct = null;
                    try { ct = options.getClass().getMethod("getCloudsType").invoke(options); } catch (Throwable t) { ct = options.getClass().getMethod("cloudStatus").invoke(options); }
                    if (ct != null) {
                        Object cur = ct.getClass().getMethod("get").invoke(ct);
                        for (Object e : cur.getClass().getEnumConstants()) {
                            if ("OFF".equals(e.toString())) { ct.getClass().getMethod("set", Object.class).invoke(ct, e); break; }
                        }
                    }
                } catch (Throwable t) {}
            }
        } catch (Throwable t) {}
    }
}
