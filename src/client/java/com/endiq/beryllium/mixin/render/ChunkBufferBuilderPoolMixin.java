package com.endiq.beryllium.mixin.render;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.config.BerylliumConfig;
import com.endiq.beryllium.util.BerylliumLog;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.chunk.ChunkBufferBuilderPool;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * NOT COMPILE-VERIFIED — same sandbox caveat as the other mixins: targets
 * {@code ChunkBufferBuilderPool.acquire()/release(BufferBuilder)} by string descriptor
 * with {@code require = 0}. If the pool's surface differs in a decompile of 1.21.4, this
 * silently no-ops — it is read-only telemetry, it never changes behavior.
 *
 * <p>Phase 8 — GPU-buffer recycling visibility. Vanilla 1.21.4 pools the
 * {@link BufferBuilder}s used for chunk meshes in a {@code ChunkBufferBuilderPool}; every
 * {@code acquire()} that returns null means the pool was empty and the caller had to
 * allocate a fresh growable buffer on the spot (allocation churn), and every
 * {@code release()} is a successful recycling. Counting both gives a live picture of how
 * well chunk-buffer reuse is working on this device — exactly the telemetry a mobile
 * performance mod needs before deciding whether a custom {@code BufferPool}-backed
 * replacement is worth it. Logged under debug mode, rate-limited, never per-frame.
 */
@Mixin(ChunkBufferBuilderPool.class)
public abstract class ChunkBufferBuilderPoolMixin {
	@Unique
	private static long acquires = 0;
	@Unique
	private static long releases = 0;
	@Unique
	private static long misses = 0;
	@Unique
	private static long lastLogNanos = 0;

	@Inject(method = "acquire()Lnet/minecraft/client/renderer/BufferBuilder;", at = @At("RETURN"), require = 0)
	private void beryllium$trackAcquire(CallbackInfoReturnable<BufferBuilder> cir) {
		acquires++;
		if (cir.getReturnValue() == null) {
			misses++;
		}
		maybeLog();
	}

	@Inject(method = "release(Lnet/minecraft/client/renderer/BufferBuilder;)V", at = @At("HEAD"), require = 0)
	private void beryllium$trackRelease(BufferBuilder bufferBuilder, CallbackInfo ci) {
		releases++;
		maybeLog();
	}

	@Unique
	private static void maybeLog() {
		BerylliumConfig config = Beryllium.config();
		if (config == null || !config.enabled || !config.debugMode) {
			return;
		}
		long now = System.nanoTime();
		if (now - lastLogNanos < 10_000_000_000L) {
			return;
		}
		lastLogNanos = now;
		BerylliumLog.debug("[BERYLLIUM-BUFFER] ChunkBufferBuilderPool: " + acquires + " acquires, "
			+ releases + " releases, " + misses + " misses (" + missRatePercent() + "%) — "
			+ (misses == 0 ? "pool recycling healthy." : "misses mean on-the-spot allocations; consider a larger pool or Beryllium's BufferPool."));
	}

	@Unique
	private static long missRatePercent() {
		return acquires == 0 ? 0 : Math.round(100.0 * misses / acquires);
	}
}
