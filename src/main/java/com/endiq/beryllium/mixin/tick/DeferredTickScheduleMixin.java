package com.endiq.beryllium.mixin.tick;

import com.endiq.beryllium.tick.DeferredWorldActions;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Defers block/fluid tick scheduling performed by a worker.
 *
 * <p>Fire, water and a long list of other blocks respond to a random tick by scheduling a
 * follow-up tick. The scheduler ({@code LevelTicks}) is a plain sorted collection with no
 * concurrency guarantees whatsoever, so letting two workers add to it at once is exactly
 * the kind of "works until it does not" bug that ruins a world. Hooking {@code schedule}
 * catches every scheduler in the game — block ticks and fluid ticks alike — through one
 * funnel.
 *
 * <p>The handler uses raw types on purpose: the vanilla method is generic in the ticked
 * element, and the erased descriptor is the only thing Mixin matches on, so a raw
 * signature is both correct here and immune to generic-signature drift.
 */
@Mixin(LevelTicks.class)
public abstract class DeferredTickScheduleMixin {

	@SuppressWarnings({"rawtypes", "unchecked"})
	@Inject(method = "schedule(Lnet/minecraft/world/ticks/ScheduledTick;)V", at = @At("HEAD"), cancellable = true, require = 0)
	private void beryllium$deferSchedule(ScheduledTick tick, CallbackInfo ci) {
		if (!DeferredWorldActions.isDeferring()) {
			return;
		}
		LevelTicks self = (LevelTicks) (Object) this;
		if (DeferredWorldActions.offer(() -> self.schedule(tick))) {
			DeferredWorldActions.noteDeferred();
			ci.cancel();
		}
	}
}
