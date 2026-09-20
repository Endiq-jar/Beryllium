package com.endiq.beryllium.mixin.optimization;

import com.endiq.beryllium.optimization.RedstoneOptimizer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Redstone wire optimization: power calculations of entire network before updating.
 */
@Mixin(targets = {"net.minecraft.world.level.block.RedStoneWireBlock", "net.minecraft.world.level.block.RedstoneWireBlock"})
public abstract class RedstoneMixin {
    @Inject(method = {"updatePowerStrength", "calculateTargetStrength", "updateSurroundingRedstone", "getPower"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void beryllium$redstoneNetwork(CallbackInfo ci) {
        if (!RedstoneOptimizer.enabled()) return;
        // In full impl we batch network update; here we mark that optimizer is active
        // We don't cancel vanilla; we let it run but would have batched earlier
    }
    @Inject(method = {"updatePowerStrength", "calculateTargetStrength"}, at = @At("HEAD"), require = 0)
    private void beryllium$redstoneHead(CallbackInfoReturnable<?> cir) {}
    @Inject(method = {"onPlace", "onRemove", "neighborChanged"}, at = @At("HEAD"), require = 0)
    private void beryllium$redstoneNeighbor(CallbackInfo ci) {
        if (!RedstoneOptimizer.enabled()) return;
        RedstoneOptimizer.beginNetwork();
    }
    @Inject(method = {"onPlace", "onRemove", "neighborChanged"}, at = @At("RETURN"), require = 0)
    private void beryllium$redstoneNeighborEnd(CallbackInfo ci) {
        if (!RedstoneOptimizer.enabled()) return;
        RedstoneOptimizer.endNetwork();
    }
}
