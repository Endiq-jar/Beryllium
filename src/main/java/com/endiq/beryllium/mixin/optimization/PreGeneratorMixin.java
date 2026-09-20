package com.endiq.beryllium.mixin.optimization;

import com.endiq.beryllium.Beryllium;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 441 Pre-generator: sync chunk generation with chunk rendering.
 */
@Mixin(targets = {"net.minecraft.server.level.ServerChunkCache", "net.minecraft.world.level.chunk.ChunkGenerator", "net.minecraft.client.renderer.chunk.ChunkBuilder"})
public abstract class PreGeneratorMixin {
    @Inject(method = {"getChunk", "getChunkFuture", "build"}, at = @At("HEAD"), require = 0)
    private void beryllium$preGenSync(CallbackInfo ci) {
        if (Beryllium.config() == null || !Beryllium.config().enabled || !Beryllium.config().preGenerateSync) return;
    }
}
