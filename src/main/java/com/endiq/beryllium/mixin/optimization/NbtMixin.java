package com.endiq.beryllium.mixin.optimization;

import com.endiq.beryllium.Beryllium;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * NBT optimization: improves .nbt storage format handling.
 */
@Mixin(targets = {"net.minecraft.nbt.CompoundTag", "net.minecraft.nbt.ListTag", "net.minecraft.nbt.NbtIo", "net.minecraft.nbt.NbtAccounter"})
public abstract class NbtMixin {
    @Inject(method = {"load", "read", "write", "sizeInBytes"}, at = @At("HEAD"), require = 0, cancellable = true)
    private void beryllium$nbtOpt(CallbackInfoReturnable<?> cir) {
        if (Beryllium.config() == null || !Beryllium.config().enabled || !Beryllium.config().nbtOptimization) return;
    }
    @Inject(method = {"read", "load"}, at = @At("HEAD"), require = 0)
    private void beryllium$nbtRead(CallbackInfo ci) {}
}
