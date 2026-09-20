package com.endiq.beryllium.mixin.optimization;

import com.endiq.beryllium.Beryllium;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Optimizes chunk sending for poor connections + chunk upload pacing.
 */
@Mixin(targets = {"net.minecraft.client.renderer.chunk.ChunkBuilder", "net.minecraft.client.renderer.LevelRenderer"})
public abstract class ChunkUploadPacerMixin2 {
    @Inject(method = {"upload", "uploadSectionLayer"}, at = @At("HEAD"), require = 0)
    private void beryllium$pace(CallbackInfo ci) {
        if (Beryllium.config() == null || !Beryllium.config().enabled || !Beryllium.config().chunkUploadPacing) return;
    }
}
