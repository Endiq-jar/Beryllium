package com.endiq.beryllium.mixin.optimization;

import com.endiq.beryllium.network.ChunkSendOptimizer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Optimizes chunk sending for worse connections.
 */
@Mixin(targets = {"net.minecraft.server.level.ServerChunkCache", "net.minecraft.server.level.ChunkMap", "net.minecraft.server.network.ServerGamePacketListenerImpl"})
public abstract class ChunkSendMixin {
    @Inject(method = {"sendChunk", "sendLevelChunk", "sendAllChunks"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void beryllium$throttleChunkSend(CallbackInfo ci) {
        if (!ChunkSendOptimizer.enabled()) return;
        // pacing handled via ChunkSendOptimizer.shouldSendNow ; we don't cancel, just pace
    }
}
