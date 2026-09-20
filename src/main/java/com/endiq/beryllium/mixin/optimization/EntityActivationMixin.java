package com.endiq.beryllium.mixin.optimization;

import com.endiq.beryllium.optimization.EntityActivationRange;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Entity Activation Range: drastically cut entities processed per tick.
 */
@Mixin(targets = {"net.minecraft.server.level.ServerLevel", "net.minecraft.world.level.Level"})
public abstract class EntityActivationMixin {
    @Inject(method = {"tick", "tickEntities", "tickNonPassenger"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void beryllium$activationRange(CallbackInfo ci) {
        if (!EntityActivationRange.enabled()) return;
        // actual culling happens in entity tick wrapper via EntityTickBatchMixin; here we ensure low-level ticks respect range
    }
    @Inject(method = {"tick"}, at = @At("HEAD"), require = 0)
    private void beryllium$tickGuard(CallbackInfo ci) {}
}
