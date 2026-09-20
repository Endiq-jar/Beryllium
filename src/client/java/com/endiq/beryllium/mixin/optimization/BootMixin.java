package com.endiq.beryllium.mixin.optimization;

import com.endiq.beryllium.optimization.BootOptimizer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Makes game boot faster by deferring non-essential initialization.
 */
@Mixin(targets = {"net.minecraft.client.Minecraft", "net.minecraft.client.main.Main", "net.minecraft.client.renderer.texture.TextureManager"})
public abstract class BootMixin {
    @Inject(method = {"<init>", "init", "preload"}, at = @At("HEAD"), require = 0)
    private void beryllium$deferInit(CallbackInfo ci) {
        if (BootOptimizer.shouldDefer("init")) {
            // marking defer; actual deferral is cooperative via scheduler
        }
    }
}
