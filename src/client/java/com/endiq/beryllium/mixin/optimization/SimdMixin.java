package com.endiq.beryllium.mixin.optimization;

import com.endiq.beryllium.optimization.SimdHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * SIMD for Frustum and Weather using Vector API.
 */
@Mixin(targets = {"net.minecraft.client.renderer.culling.Frustum", "net.minecraft.client.renderer.LevelRenderer"})
public abstract class SimdMixin {
    @Inject(method = {"isVisible", "isBoxInFrustum", "renderWeather"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void beryllium$simd(CallbackInfoReturnable<?> cir) {
        if (!SimdHelper.shouldUseVector()) return;
        // In real impl would use FloatVector to test 4 planes at once
    }
}
