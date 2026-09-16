package com.endiq.beryllium.mixin.tick;

import com.endiq.beryllium.tick.AsyncRandomTicks;
import net.minecraft.server.level.ServerChunkCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

/**
 * Opens and closes the async random-tick batch around vanilla's chunk tick loop.
 *
 * <p>The loop itself lives in {@code ServerChunkCache#tick}; the per-chunk work it drives
 * is intercepted by {@link AsyncRandomTickMixin}. The {@code RETURN} hook is the prompt
 * join point — the batch also self-flushes when the game time advances, so a version where
 * these hooks do not match still applies every mutation, just one tick later.
 */
@Mixin(ServerChunkCache.class)
public abstract class AsyncRandomTickBatchMixin {

	@Inject(method = "tick(Ljava/util/function/BooleanSupplier;Z)V", at = @At("HEAD"), require = 0)
	private void beryllium$beginRandomTickBatch(BooleanSupplier hasTime, boolean tickChunks, CallbackInfo ci) {
		AsyncRandomTicks.beginBatch();
	}

	@Inject(method = "tick(Ljava/util/function/BooleanSupplier;Z)V", at = @At("RETURN"), require = 0)
	private void beryllium$endRandomTickBatch(BooleanSupplier hasTime, boolean tickChunks, CallbackInfo ci) {
		AsyncRandomTicks.joinBatch();
	}
}
