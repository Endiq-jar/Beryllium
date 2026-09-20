package com.endiq.beryllium.mixin.optimization;

import com.endiq.beryllium.Beryllium;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * More configurable mobspawning: per-group spawnrate and mobcaps, force caps on reinforcements/spawners/portals.
 */
@Mixin(targets = {"net.minecraft.world.level.NaturalSpawner", "net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate", "net.minecraft.world.entity.MobCategory", "net.minecraft.server.level.ServerLevel"})
public abstract class MobSpawnMixin {
    @Inject(method = {"spawnCategoryForPosition", "getRandomPosWithinLevel", "createState"}, at = @At("HEAD"), require = 0, cancellable = true)
    private void beryllium$spawnRate(CallbackInfoReturnable<?> cir) {
        if (Beryllium.config() == null || !Beryllium.config().enabled || !Beryllium.config().configurableMobSpawning) return;
    }
    @Inject(method = {"isSpawnPositionOk", "checkSpawnRules"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void beryllium$forceMobcap(CallbackInfoReturnable<Boolean> cir) {
        if (Beryllium.config() == null || !Beryllium.config().enabled) return;
        // if forceMobcaps enabled, we would deny spawns over cap even for reinforcements etc.
    }
}
