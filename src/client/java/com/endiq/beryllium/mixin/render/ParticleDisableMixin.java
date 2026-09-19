package com.endiq.beryllium.mixin.render;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.config.BerylliumConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The "Disable Particles" option: nothing new is ever added to the particle engine.
 *
 * <p>Only the two ways in are closed — {@code add}, and the tracking-emitter helper that ends in
 * it. The engine keeps ticking, so particles that were already alive finish their life instead
 * of freezing mid-air, and no internal list is ever left in a state vanilla would not recognise.
 * The handlers take no arguments, which is what lets one mixin cover the releases where those
 * methods grew, shrank or changed parameter types.
 */
@Mixin(targets = "net.minecraft.client.particle.ParticleEngine")
public abstract class ParticleDisableMixin {
	@Inject(method = "add(Lnet/minecraft/client/particle/Particle;)V", at = @At("HEAD"),
			cancellable = true, require = 0)
	private void beryllium$blockParticleAddition(CallbackInfo ci) {
		if (beryllium$disabled()) {
			ci.cancel();
		}
	}

	/** Nothing new is added, but particles created before the switch was read are still in
	 *  the engine's queues; cancelling the tick and the draw clears them out. */
	@Inject(method = "tick", at = @At("HEAD"), cancellable = true, require = 0)
	private void beryllium$skipParticleTick(CallbackInfo ci) {
		if (beryllium$disabled()) {
			ci.cancel();
		}
	}

	@Inject(method = {"render", "extract"}, at = @At("HEAD"), cancellable = true, require = 0)
	private void beryllium$skipParticleDraw(CallbackInfo ci) {
		if (beryllium$disabled()) {
			ci.cancel();
		}
	}

	@Inject(method = "createTrackingEmitter(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/particles/ParticleOptions;)V",
			at = @At("HEAD"), cancellable = true, require = 0)
	private void beryllium$blockTrackingEmitter(@Coerce Object entity, @Coerce Object particle,
			CallbackInfo ci) {
		if (beryllium$disabled()) {
			ci.cancel();
		}
	}

	@Inject(method = "createTrackingEmitter(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/particles/ParticleOptions;I)V",
			at = @At("HEAD"), cancellable = true, require = 0)
	private void beryllium$blockTrackingEmitterWithAge(@Coerce Object entity, @Coerce Object particle,
			int age, CallbackInfo ci) {
		if (beryllium$disabled()) {
			ci.cancel();
		}
	}

	private boolean beryllium$disabled() {
		try {
			BerylliumConfig config = Beryllium.config();
			return config != null && config.enabled && config.disableParticles;
		} catch (Throwable t) {
			return false;
		}
	}
}
