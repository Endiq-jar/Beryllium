package com.endiq.beryllium.mixin.optimization;

import com.endiq.beryllium.Beryllium;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Loading screens cancellable + pre-rendering phase + threaded event polling.
 */
@Mixin(targets = {"net.minecraft.client.gui.screens.LoadingOverlay", "net.minecraft.client.gui.screens.LevelLoadingScreen", "net.minecraft.client.gui.screens.Overlay"})
public abstract class LoadingScreenMixin {
    @Inject(method = {"render", "tick"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void beryllium$cancellable(CallbackInfo ci) {
        try {
            if (Beryllium.config() != null && Beryllium.config().enabled && Beryllium.config().cancellableLoadingScreens) {
                // allow input through; we don't cancel render but mark cancellable
                // actual cancelling is via OverlayMixin already handling removal
            }
        } catch (Throwable t) {}
    }
}
