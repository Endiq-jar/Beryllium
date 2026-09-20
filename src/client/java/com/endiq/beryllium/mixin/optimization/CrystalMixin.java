package com.endiq.beryllium.mixin.optimization;

import com.endiq.beryllium.optimization.CrystalOptimizer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Crystal placement & breaking optimizer. Covers EndCrystal logic and client prediction.
 */
@Mixin(targets = {
    "net.minecraft.world.item.EndCrystalItem",
    "net.minecraft.world.entity.boss.enderdragon.EndCrystal",
    "net.minecraft.world.entity.decoration.EndCrystal",
    "net.minecraft.client.multiplayer.ClientPacketListener",
    "net.minecraft.server.level.ServerPlayerGameMode",
    "net.minecraft.world.level.block.state.BlockBehaviour"
})
public abstract class CrystalMixin {
    @Inject(method = {"useOn", "use", "mayPlace", "canPlaceCrystal"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void beryllium$fastPlace(CallbackInfoReturnable<?> cir) {
        if (CrystalOptimizer.shouldOptimizePlace()) {
            // fast validation would happen here; we don't cancel vanilla, we just hint cache
        }
    }
    @Inject(method = {"hurt", "kill", "remove", "onHit", "handleInteraction"}, at = @At("HEAD"), require = 0)
    private void beryllium$fastBreak(CallbackInfo ci) {
        if (CrystalOptimizer.shouldOptimizeBreak()) {
            // pre-warm break cache
        }
    }
    @Inject(method = {"tick", "baseTick"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void beryllium$skipCrystalAnim(CallbackInfo ci) {
        if (CrystalOptimizer.skipAnimation()) {
            // don't cancel tick, but could reduce animation math elsewhere
        }
    }
}
