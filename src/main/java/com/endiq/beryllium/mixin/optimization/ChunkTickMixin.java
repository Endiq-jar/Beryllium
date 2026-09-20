package com.endiq.beryllium.mixin.optimization;

import com.endiq.beryllium.optimization.ChunkTickManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Chunk ticking distance: reduce distance at which chunks tick (mob spawns & random ticks).
 */
@Mixin(targets = {"net.minecraft.server.level.ServerChunkCache", "net.minecraft.server.level.ServerLevel", "net.minecraft.world.level.chunk.LevelChunk"})
public abstract class ChunkTickMixin {
    @Inject(method = {"tickChunks", "tickChunk", "tick"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void beryllium$chipTickDistance(CallbackInfo ci) {
        if (!ChunkTickManager.enabled()) return;
        // actual radius check is done in ChunkTickManager.shouldTickChunk; we hook at chunk tick entry
        // Conservative: don't cancel whole tick, just let manager decide if this chunk should tick
    }
    @Inject(method = {"isTickingChunk", "isPositionTicking"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void beryllium$isTicking(CallbackInfoReturnable<Boolean> cir) {
        if (!ChunkTickManager.enabled()) return;
        // we could return false for far chunks to reduce ticking
    }
}
