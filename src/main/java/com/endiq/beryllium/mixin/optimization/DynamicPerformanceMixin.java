package com.endiq.beryllium.mixin.optimization;

import com.endiq.beryllium.optimization.DynamicPerformance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Dynamic performance checks: adjust view/simulation/mobcaps automatically.
 */
@Mixin(targets = {"net.minecraft.server.MinecraftServer", "net.minecraft.server.level.ServerLevel"})
public abstract class DynamicPerformanceMixin {
    @Inject(method = {"tickServer", "tick"}, at = @At("RETURN"), require = 0)
    private void beryllium$dynamic(CallbackInfo ci) {
        if (!DynamicPerformance.enabled()) return;
        try {
            // heuristic tick time from last tick would be used here
            // DynamicPerformance.shouldAdjust tickTime ...
        } catch (Throwable t) {}
    }
}
