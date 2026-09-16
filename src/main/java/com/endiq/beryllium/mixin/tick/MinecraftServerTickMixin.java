package com.endiq.beryllium.mixin.tick;

import com.endiq.beryllium.tick.TickRuntime;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

/**
 * Drives Beryllium's tick-time governor.
 *
 * <p>Two method names are targeted because the entry point was renamed across the
 * supported range ({@code MinecraftServer#tick} on older releases,
 * {@code MinecraftServer#tickServer} on newer ones); whichever does not exist is skipped
 * ({@code require = 0}). If a version ever declares both, the inner call is collapsed by
 * the governor's own re-entrancy guard, so the tick is still measured exactly once.
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerTickMixin {

	@Inject(method = {"tickServer", "tick"}, at = @At("HEAD"), require = 0)
	private void beryllium$tickStart(BooleanSupplier shouldKeepTicking, CallbackInfo ci) {
		TickRuntime.instance().onTickStart();
	}

	@Inject(method = {"tickServer", "tick"}, at = @At("RETURN"), require = 0)
	private void beryllium$tickEnd(BooleanSupplier shouldKeepTicking, CallbackInfo ci) {
		TickRuntime.instance().onTickEnd();
	}
}
