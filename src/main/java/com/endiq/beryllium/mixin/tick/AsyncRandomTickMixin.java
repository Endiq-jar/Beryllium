package com.endiq.beryllium.mixin.tick;

import com.endiq.beryllium.tick.AsyncRandomTicks;
import com.endiq.beryllium.tick.ExperimentalCertification;
import com.endiq.beryllium.tick.ReplayGuard;
import com.endiq.beryllium.tick.VanillaBridges;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Async random ticks — takes vanilla's per-chunk random-tick pass off the server thread.
 * See {@link AsyncRandomTicks} for the safety model: deferred world mutations, per-thread
 * RNG, calibration before any concurrency, weather opt-out, self-disabling on failure.
 *
 * <p>Vanilla's {@code ServerChunkCache#tick} loop calls {@code ServerLevel#tickChunk} once
 * per chunk. This mixin cancels that call for eligible chunks and replays the identical
 * vanilla body on a worker (through {@link VanillaBridges}), then the batch is joined and
 * the recorded mutations applied by the server thread
 * ({@link AsyncRandomTickBatchMixin}).
 *
 * <p>{@code require = 0} throughout: a rename on some release turns the feature off, it
 * does not turn the server off.
 */
@Mixin(ServerLevel.class)
public abstract class AsyncRandomTickMixin {

	@Inject(method = "tickChunk(Lnet/minecraft/world/level/chunk/LevelChunk;I)V", at = @At("HEAD"), cancellable = true, require = 0)
	private void beryllium$asyncRandomTick(LevelChunk chunk, int randomTickSpeed, CallbackInfo ci) {
		if (ReplayGuard.isReplaying()) {
			// This is Beryllium's own replay of the vanilla body — let it run.
			return;
		}

		ServerLevel level = beryllium$self();
		if (level == null || chunk == null || level.isClientSide()) {
			return;
		}
		if (!VanillaBridges.hasTickChunk(level)) {
			return;
		}

		Runnable body = () -> VanillaBridges.tickChunk(level, chunk, randomTickSpeed);

		// First eligible chunk of the session: run it inline (with deferral active) so the
		// safety hooks can be verified before a single task runs concurrently.
		if (!ExperimentalCertification.wasAttempted()) {
			if (AsyncRandomTicks.shouldAttempt(level)) {
				AsyncRandomTicks.calibrate(level, chunk, body);
				ci.cancel();
			}
			return;
		}

		if (AsyncRandomTicks.dispatch(level, chunk, randomTickSpeed, body)) {
			ci.cancel();
		}
	}

	@Unique
	private ServerLevel beryllium$self() {
		return (ServerLevel) (Object) this;
	}
}
