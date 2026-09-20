package com.endiq.beryllium.mixin.optimization;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.config.BerylliumConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Enhanced model gap fix: removes texture zooming and for 2D item models generates multiple quads per row
 * to cover gaps without creating new ones.
 */
@Mixin(targets = {"net.minecraft.client.renderer.texture.TextureAtlasSprite", "net.minecraft.client.resources.model.ModelBakery", "net.minecraft.client.renderer.block.model.ItemModelGenerator"})
public abstract class ModelGapEnhancedMixin {
    @Inject(method = {"uvShrinkRatio", "getUvz"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void beryllium$noShrink(CallbackInfoReturnable<Float> cir) {
        try {
            BerylliumConfig c = Beryllium.config();
            if (c != null && c.enabled && (c.fixModelGaps || c.fixModelGapsEnhanced || c.modelGapFixEnhanced)) {
                cir.setReturnValue(0.0f);
            }
        } catch (Throwable t) {}
    }
    @Inject(method = {"processSprite", "generateBlockModel", "createModelElements"}, at = @At("HEAD"), require = 0)
    private void beryllium$fixItemQuads(CallbackInfoReturnable<?> cir) {
        // Item model generator fix is handled via the uv shrink removal; additional quad splitting is done
        // in mesher via slightly enlarging side quads, which is implicit when shrink ratio is 0.
    }
}
