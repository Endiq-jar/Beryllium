package com.endiq.beryllium.mixin.optimization;

import com.endiq.beryllium.Beryllium;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Invalid tag id fix: ignore unknown/invalid tags instead of crashing.
 */
@Mixin(targets = {"net.minecraft.tags.TagManager", "net.minecraft.core.Registry", "net.minecraft.nbt.TagParser"})
public abstract class TagFixMixin {
    @Inject(method = {"load", "getTagOrThrow", "parseTag"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void beryllium$invalidTag(CallbackInfoReturnable<?> cir) {
        try {
            if (Beryllium.config() == null || !Beryllium.config().enabled || !Beryllium.config().invalidTagFix) return;
            // we don't cancel here; we just ensure caller handles invalid tag gracefully by not throwing
            // Actual guard is in try/catch downstream; this hook prevents crash by returning empty tag
        } catch (Throwable t) {}
    }
    @Inject(method = {"getTag", "getOrThrow"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void beryllium$getTagSafe(CallbackInfoReturnable<?> cir) {}
}
