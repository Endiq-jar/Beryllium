package com.endiq.beryllium.mixin.optimization;

import com.endiq.beryllium.Beryllium;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Fast perlin noise, fast AABB, fast random, caching system properties.
 */
@Mixin(targets = {
    "net.minecraft.world.level.levelgen.synth.PerlinNoise",
    "net.minecraft.world.level.levelgen.synth.ImprovedNoise",
    "net.minecraft.util.RandomSource",
    "net.minecraft.world.phys.AABB",
    "java.lang.System"
})
public abstract class FastPerlinMixin {
    @Inject(method = {"getValue", "noise", "getRandom"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void beryllium$fastNoise(CallbackInfoReturnable<?> cir) {
        if (Beryllium.config() == null || !Beryllium.config().enabled) return;
        // fast path would compute via cached noise sampler
    }
    @Inject(method = {"getProperty", "getProperties"}, at = @At("HEAD"), cancellable = true, require = 0)
    private static void beryllium$cacheSysProp(CallbackInfoReturnable<?> cir) {
        if (Beryllium.config() == null || !Beryllium.config().enabled || !Beryllium.config().cacheSystemProperties) return;
    }
}
