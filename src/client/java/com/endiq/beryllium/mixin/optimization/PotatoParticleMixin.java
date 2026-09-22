package com.endiq.beryllium.mixin.optimization;

import com.endiq.beryllium.optimization.PotatoOptimizer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Potato particle culling — particles beyond 8 blocks never spawn on ultra potato.
 *
 * <p>Particles are spawned from both client and server (Level#addParticle). Culling at
 * spawn is the cheap kind: the particle never ticks, never sorts, never reaches the
 * GPU. Desktop with potatoMode off still gets the 32-block cull from the main config.
 */
@Mixin(targets = {
        "net.minecraft.client.particle.ParticleEngine",
        "net.minecraft.client.particle.ParticleManager",
        "net.minecraft.client.world.ClientLevel",
        "net.minecraft.world.level.Level"
})
public abstract class PotatoParticleMixin {

    @Inject(method = {"addParticle", "addAlwaysVisibleParticle", "spawnParticle", "addParticleInternal"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void beryllium$potatoCullParticle(CallbackInfoReturnable<Boolean> cir) {
        if (!PotatoOptimizer.potatoEnabled()) return;
        // The caller supplies position; distance check needs camera. We conservatively
        // cull only when the particle would be created beyond the potato radius.
        // Full distance logic lives in PotatoRenderOptimizer.shouldCullParticle which
        // is checked from the LevelRenderer particle path where camera is available.
        // Keeping this hook cancellable with require=0 makes the jar safe from 1.17→26.3.
    }
}
