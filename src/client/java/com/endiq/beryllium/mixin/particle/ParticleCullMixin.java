package com.endiq.beryllium.mixin.particle;

import com.endiq.beryllium.particle.ParticleCulling;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Particle culling. See {@link ParticleCulling}: particles that would be created beyond
 * {@code particleCullDistance} of the camera are dropped at the door.
 *
 * <p>Culling at spawn is what makes this worth doing — a particle that is never added does
 * not tick, does not sort into a buffer, and is never uploaded to the GPU, so the saving
 * covers the whole frame and not just the draw. Particles created inside the distance
 * (including every particle the player's own actions produce) are untouched.
 */
@Mixin(ParticleEngine.class)
public abstract class ParticleCullMixin {

	@Inject(method = "add(Lnet/minecraft/client/particle/Particle;)V", at = @At("HEAD"), cancellable = true, require = 0)
	private void beryllium$cullDistantParticles(Particle particle, CallbackInfo ci) {
		if (ParticleCulling.shouldCullSpawn(particle)) {
			ci.cancel();
		}
	}
}
