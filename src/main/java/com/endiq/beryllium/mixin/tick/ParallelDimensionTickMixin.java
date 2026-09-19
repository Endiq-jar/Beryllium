package com.endiq.beryllium.mixin.tick;

import com.endiq.beryllium.dimension.ParallelDimensionTicker;
import com.endiq.beryllium.misc.ThreadPriorityTuner;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

/**
 * Ticks the loaded dimensions at the same time instead of one after another.
 *
 * <p>The level loop is the one place where the server's tick does the same thing several times
 * over for different dimensions, and where those repetitions are independent: each dimension
 * drives its own chunk map, its own entities and its own schedulers. This hands each of them to
 * {@link ParallelDimensionTicker} and lets it decide — it is the part that knows whether
 * overlapping is safe and worth it, whether to wait, and when to give up and go back to doing
 * them in order.
 *
 * <p>The wait matters as much as the overlap: the barrier at the end of the loop is what keeps
 * the tick that dimension A sees identical to the tick dimension B sees, which is what
 * redstone that spans dimensions depends on. A dimension is never left ticking while the rest of
 * the server has moved on.
 *
 * <p>Every hook here is optional. On a release where the loop, the method or the signature moved,
 * the dimension is simply ticked inline on the server thread — vanilla's behaviour — and the
 * only thing lost is the overlap.
 */
@Mixin(targets = "net.minecraft.server.MinecraftServer")
public abstract class ParallelDimensionTickMixin {
	@Inject(method = "tickChildren", at = @At("HEAD"), require = 0)
	private void beryllium$prepareDimensionPass(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
		try {
			// A batch that was never flushed means the previous tick threw before it reached the
			// barrier. Those dimensions are already being handled by the crash, so drop them and
			// stop overlapping rather than ticking a stale batch later.
			ParallelDimensionTicker.discardPending();
			ParallelDimensionTicker.ServerHolder.set(this);
			ThreadPriorityTuner.applyIfDue();
		} catch (Throwable t) {
			// Nothing here is required for the server to tick.
		}
	}

	@Redirect(
		method = "tickChildren",
		at = @At(value = "INVOKE",
				target = "Lnet/minecraft/server/level/ServerLevel;tick(Ljava/util/function/BooleanSupplier;)V"),
		require = 0
	)
	private void beryllium$tickDimension(ServerLevel level, BooleanSupplier hasTimeLeft) {
		if (!ParallelDimensionTicker.defer(level, hasTimeLeft)) {
			level.tick(hasTimeLeft);
		}
	}

	@Inject(method = "tickChildren", at = @At("RETURN"), require = 0)
	private void beryllium$joinDimensionPass(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
		// Deliberately not wrapped: if a dimension threw while ticking on a worker, flush()
		// switches the session back to serial ticking and rethrows the original failure here, on
		// the server thread, so the server reports it and stops exactly as it would have if that
		// dimension had ticked inline.
		ParallelDimensionTicker.flush();
	}
}
