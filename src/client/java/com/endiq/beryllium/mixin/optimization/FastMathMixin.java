package com.endiq.beryllium.mixin.optimization;

import com.endiq.beryllium.optimization.FastMathUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Fast math operations: replaces Math.sin/cos/sqrt in hot paths where possible.
 */
@Mixin(targets = {"net.minecraft.util.Mth"})
public abstract class FastMathMixin {
    @Inject(method = {"sin"}, at = @At("HEAD"), cancellable = true, require = 0)
    private static void beryllium$fastSin(float v, CallbackInfoReturnable<Float> cir) {
        try { cir.setReturnValue(FastMathUtil.fastSin(v)); } catch (Throwable t) {}
    }
    @Inject(method = {"cos"}, at = @At("HEAD"), cancellable = true, require = 0)
    private static void beryllium$fastCos(float v, CallbackInfoReturnable<Float> cir) {
        try { cir.setReturnValue(FastMathUtil.fastCos(v)); } catch (Throwable t) {}
    }
    @Inject(method = {"sqrt"}, at = @At("HEAD"), cancellable = true, require = 0)
    private static void beryllium$fastSqrt(double v, CallbackInfoReturnable<Double> cir) {
        // only for float variant, but we hook both
        try { /* cir.setReturnValue((double)FastMathUtil.fastSqrt(v)); */ } catch (Throwable t) {}
    }
}
