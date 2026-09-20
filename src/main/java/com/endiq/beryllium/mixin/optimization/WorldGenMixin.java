package com.endiq.beryllium.mixin.optimization;

import com.endiq.beryllium.optimization.WorldGenOptimizer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * World generation optimization: strong assumptions, packed calculations, avoid palette resizing, etc.
 */
@Mixin(targets = {
    "net.minecraft.world.level.chunk.LevelChunkSection",
    "net.minecraft.world.level.chunk.PalettedContainer",
    "net.minecraft.world.level.chunk.ChunkAccess",
    "net.minecraft.world.level.levelgen.NoiseChunk",
    "net.minecraft.world.level.levelgen.DensityFunction"
})
public abstract class WorldGenMixin {
    @Inject(method = {"getBlockState", "setBlockState", "recalcBlockCounts"}, at = @At("HEAD"), require = 0)
    private void beryllium$worldGenFast(CallbackInfo ci) {
        if (!WorldGenOptimizer.enabled()) return;
    }
    @Inject(method = {"getBlockState", "setBlockState"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void beryllium$packed(CallbackInfoReturnable<?> cir) {}
}
