package com.endiq.beryllium.mixin.optimization;

import com.endiq.beryllium.Beryllium;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mob AI: improve cat/wolf attack, wolf/rabbit flee.
 */
@Mixin(targets = {
    "net.minecraft.world.entity.ai.goal.MeleeAttackGoal",
    "net.minecraft.world.entity.ai.goal.AvoidEntityGoal",
    "net.minecraft.world.entity.animal.Cat",
    "net.minecraft.world.entity.animal.Wolf",
    "net.minecraft.world.entity.animal.Rabbit"
})
public abstract class MobAiMixin {
    @Inject(method = {"tick", "canUse", "canContinueToUse"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void beryllium$aiOpt(CallbackInfo ci) {
        if (Beryllium.config() == null || !Beryllium.config().enabled || !Beryllium.config().mobAiOptimization) return;
    }
}
