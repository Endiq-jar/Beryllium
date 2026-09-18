package com.endiq.beryllium.mixin.lifecycle;

import com.endiq.beryllium.ClientFrameHooks;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Beryllium's per-frame clock.
 *
 * <p>{@code Minecraft#runTick} has kept the same name and shape across every release
 * Beryllium builds for, which makes it the most reliable "a client frame happened" signal
 * that does not depend on a modding API. It is also the earliest point at which the window
 * and GL context are guaranteed to exist, so it doubles as Beryllium's client-started
 * trigger.
 *
 * <p>Everything hung off this hook is contained: a failure inside Beryllium's per-frame
 * work is logged and dropped rather than propagated into the client loop.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftRunTickMixin {

	@Inject(method = "runTick(Z)V", at = @At("HEAD"), require = 0)
	private void beryllium$frameStart(boolean renderLevel, CallbackInfo ci) {
		ClientFrameHooks.onFrameStart();
	}

	@Inject(method = "runTick(Z)V", at = @At("RETURN"), require = 0)
	private void beryllium$frameEnd(boolean renderLevel, CallbackInfo ci) {
		ClientFrameHooks.onFrameEnd();
	}
}
