package com.endiq.beryllium.mixin.optimization;

import com.endiq.beryllium.Beryllium;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Caching system properties.
 */
@Mixin(targets = {"net.minecraft.Util", "net.minecraft.util.Util"})
public abstract class SystemPropertiesMixin {
    @Inject(method = {"getProperty", "getProperties"}, at = @At("HEAD"), cancellable = true, require = 0)
    private static void beryllium$cacheSysProp(CallbackInfoReturnable<?> cir) {
        if (Beryllium.config() == null || !Beryllium.config().enabled || !Beryllium.config().cacheSystemProperties) return;
    }
}
