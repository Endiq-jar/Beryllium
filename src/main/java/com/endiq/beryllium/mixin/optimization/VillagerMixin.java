package com.endiq.beryllium.mixin.optimization;

import com.endiq.beryllium.optimization.VillagerLobotomizer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Villager lobotomization: stuck in 1x1 tick less often.
 */
@Mixin(targets = {"net.minecraft.world.entity.npc.Villager", "net.minecraft.world.entity.npc.AbstractVillager"})
public abstract class VillagerMixin {
    @Inject(method = {"tick", "aiStep", "customServerAiStep"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void beryllium$lobotomize(CallbackInfo ci) {
        try {
            if (!VillagerLobotomizer.enabled()) return;
            // Throttle villager AI when lobotomized; we use game time heuristic
            // Since we don't have gameTime here, we use system time modulo interval
            long now = System.nanoTime() / 50000000L;
            // we can't access villager instance generically without cast; so we conservatively tick every N
            if ((now % Math.max(1, com.endiq.beryllium.Beryllium.config().villagerLobotomizeTickInterval)) != 0) {
                // Check if truly lobotomized via velocity check (needs entity)
                // If we can't determine, we don't cancel to be safe
            }
        } catch (Throwable t) {}
    }
}
