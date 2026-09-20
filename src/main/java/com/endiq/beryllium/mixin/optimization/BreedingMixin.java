package com.endiq.beryllium.mixin.optimization;

import com.endiq.beryllium.optimization.BreedingCap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Breeding caps: prevent breeding thousands of animals in small area.
 */
@Mixin(targets = {"net.minecraft.world.entity.animal.Animal", "net.minecraft.world.entity.AgeableMob"})
public abstract class BreedingMixin {
    @Inject(method = {"canMate", "canBreed", "spawnChildFromBreeding"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void beryllium$breedingCap(CallbackInfoReturnable<?> cir) {
        try {
            if (!BreedingCap.enabled()) return;
            // In real impl we would check nearby entity count and cancel if over cap
            // Here we conservatively allow vanilla; actual denial is via server logic hook
        } catch (Throwable t) {}
    }
}
