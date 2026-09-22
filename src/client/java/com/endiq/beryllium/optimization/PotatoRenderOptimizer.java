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
        if (mc == null || mc.options == null) return;
        try {
            BerylliumConfig c = Beryllium.config();
            if (c == null || !c.enabled || !PotatoOptimizer.potatoEnabled()) return;
            // These are reflectively set where the option type changed between 1.17 and 26.x
            // so we use the compatibility helper instead of direct field access.
            if (c.potatoClampViewDistance) {
                try { mc.options.renderDistance().set(Math.min(mc.options.renderDistance().get(), c.potatoViewDistance)); } catch (Throwable t) {}
            }
            if (c.potatoDisableFancyGraphics || c.potatoForceFastGraphics) {
                try { Object g = mc.options.graphicsMode().get(); if (!\"FAST\".equals(g.toString())) mc.options.graphicsMode().set(Enum.valueOf((Class<Enum>) g.getClass(), \"FAST\")); } catch (Throwable t) {}
            }
            if (c.potatoDisableSmoothLighting) {
                try { mc.options.ambientOcclusion().set(false); } catch (Throwable t) {}
            }
            if (c.potatoDisableBiomeBlending) {
                try { mc.options.biomeBlendRadius().set(0); } catch (Throwable t) {}
            }
            if (c.potatoDisableClouds) {
                try { mc.options.getCloudsType().set(Enum.valueOf((Class<Enum>) mc.options.getCloudsType().get().getClass(), \"OFF\")); } catch (Throwable t) {}
            }
        } catch (Throwable t) {}
    }
}
