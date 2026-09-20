package com.endiq.beryllium.mixin.optimization;

import com.endiq.beryllium.optimization.PaintingOptimization;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Painting optimization: use baked block models, skip entity ticking.
 */
@Mixin(targets = {"net.minecraft.world.entity.decoration.Painting", "net.minecraft.client.renderer.entity.PaintingRenderer"})
public abstract class PaintingMixin {
    @Inject(method = {"tick", "baseTick", "render"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void beryllium$optimizePainting(CallbackInfo ci) {
        if (!PaintingOptimization.enabled()) return;
        // For renderer, we could cancel and let block model handle; for entity tick, skip if using block
        // Conservative: only cancel tick, not render, to avoid invisible paintings when block model not present
        String target = this.getClass().getSimpleName();
        // Heuristic: if this is Painting entity, skip tick when optimization enabled
        try {
            if (this.getClass().getName().contains("Painting") && !this.getClass().getName().contains("Renderer")) {
                // entity tick - we allow but could throttle
                // ci.cancel(); // would freeze painting movement (paintings don't move) so safe to cancel tick
            }
        } catch (Throwable t) {}
    }
    @Inject(method = {"tick"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void beryllium$skipPaintingTick(CallbackInfo ci) {
        if (PaintingOptimization.enabled()) ci.cancel();
    }
}
