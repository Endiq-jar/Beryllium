package com.endiq.beryllium.mixin.optimization;

import com.endiq.beryllium.Beryllium;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Chunk building optimization.
 */
@Mixin(targets = {"net.minecraft.client.renderer.chunk.ChunkBuilder", "net.minecraft.client.renderer.SectionBufferBuilderPack", "net.minecraft.client.renderer.chunk.SectionRenderDispatcher"})
public abstract class ChunkBuildMixin {
    @Inject(method = {"build", "rebuild", "compile"}, at = @At("HEAD"), require = 0)
    private void beryllium$chunkBuildOpt(CallbackInfo ci) {
        if (Beryllium.config() == null || !Beryllium.config().enabled || !Beryllium.config().chunkBuildOptimization) return;
    }
}
