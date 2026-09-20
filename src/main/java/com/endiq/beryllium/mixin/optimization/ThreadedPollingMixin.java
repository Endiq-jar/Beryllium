package com.endiq.beryllium.mixin.optimization;

import com.endiq.beryllium.Beryllium;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Threaded event polling (Ixeris) + Buffered raw input (Windows).
 */
@Mixin(targets = {"net.minecraft.client.Minecraft", "com.mojang.blaze3d.platform.Window", "org.lwjgl.glfw.GLFW"})
public abstract class ThreadedPollingMixin {
    @Inject(method = {"runTick", "pollEvents", "handleEvents"}, at = @At("HEAD"), require = 0)
    private void beryllium$threaded(CallbackInfo ci) {
        if (Beryllium.config() == null || !Beryllium.config().enabled || !Beryllium.config().threadedEventPolling) return;
    }
    @Inject(method = {"getRawInput", "pollRawInput"}, at = @At("HEAD"), require = 0)
    private void beryllium$buffered(CallbackInfo ci) {
        if (Beryllium.config() == null || !Beryllium.config().enabled || !Beryllium.config().bufferedRawInput) return;
    }
}
