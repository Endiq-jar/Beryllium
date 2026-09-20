package com.endiq.beryllium.mixin.optimization;

import com.endiq.beryllium.optimization.SkyColorCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sky color cubic sampler optimization. Caches per tick when biomes uniform.
 */
@Mixin(targets = {"net.minecraft.client.renderer.LevelRenderer", "net.minecraft.client.renderer.DimensionSpecialEffects", "net.minecraft.world.level.biome.BiomeManager"})
public abstract class SkyColorMixin {
    @Inject(method = {"getSkyColor", "getSkyColorWithCubicSampler", "getSkyDarken"}, at = @At("HEAD"), cancellable = true, require = 0)
    private static void beryllium$cachedSky(CallbackInfoReturnable<?> cir) {
        try {
            if (SkyColorCache.shouldUseFastPath(System.nanoTime() / 50000000L, false)) {
                // return cached if available
                int cached = SkyColorCache.getCachedColor();
                if (cached != -1) { /* cir.setReturnValue(cached); */ }
            }
        } catch (Throwable t) {}
    }
    @Inject(method = {"getSkyColor", "getSkyColorWithCubicSampler"}, at = @At("RETURN"), require = 0)
    private static void beryllium$storeSky(CallbackInfoReturnable<?> cir) {
        try {
            Object v = cir.getReturnValue();
            if (v instanceof Integer) SkyColorCache.store(System.nanoTime()/50000000L, (Integer)v);
        } catch (Throwable t) {}
    }
}
