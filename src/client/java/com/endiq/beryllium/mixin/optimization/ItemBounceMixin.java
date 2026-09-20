package com.endiq.beryllium.mixin.optimization;

import com.endiq.beryllium.optimization.ItemBounceSuppressor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Removes bounce effect from items. Hits ItemEntity bobbing.
 */
@Mixin(targets = {"net.minecraft.world.entity.item.ItemEntity", "net.minecraft.client.renderer.entity.ItemEntityRenderer"})
public abstract class ItemBounceMixin {
    @Inject(method = {"getSpin", "getRotation", "getLerpedAmount"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void beryllium$noBounce(CallbackInfoReturnable<Float> cir) {
        if (ItemBounceSuppressor.shouldSuppress()) cir.setReturnValue(0.0f);
    }
    @ModifyVariable(method = {"tick"}, at = @At("STORE"), ordinal = 0, require = 0)
    private float beryllium$modifyBob(float v) {
        return ItemBounceSuppressor.suppressBounce(v);
    }
    @Inject(method = {"tick"}, at = @At("HEAD"), require = 0)
    private void beryllium$tickNoBounce(CallbackInfoReturnable<?> ci) {}
}
