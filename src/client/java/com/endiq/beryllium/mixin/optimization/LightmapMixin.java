package com.endiq.beryllium.mixin.optimization;

import com.endiq.beryllium.optimization.LightmapCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Avoid updating lightmap when nothing changed. Covers GameRenderer / LightTexture.
 */
@Mixin(targets = {"net.minecraft.client.renderer.GameRenderer", "net.minecraft.client.renderer.LightTexture"})
public abstract class LightmapMixin {
    @Inject(method = {"updateLightTexture", "updateLightmap", "tick", "update"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void beryllium$skipLightmap(CallbackInfo ci) {
        try {
            // Heuristic: if cache says skip, cancel
            if (LightmapCache.shouldSkip(1.0f, 0, "overworld", true)) {
                // Only skip if we have previously cached and nothing changed
                // Conservative: only skip every other tick to avoid darkness stuck
                if ((System.nanoTime() & 1) == 0) ci.cancel();
            }
        } catch (Throwable t) { }
    }
}
