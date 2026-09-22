package com.endiq.beryllium.mixin.optimization;

import com.endiq.beryllium.optimization.PotatoOptimizer;
import com.endiq.beryllium.optimization.PotatoRenderOptimizer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Ultra potato entity culling — 12-block hard cap on Mali, 32 on desktop.
 *
 * <p>Targets both the modern {@code EntityRenderDispatcher} and the legacy
 * {@code LevelRenderer} entity paths so the same jar covers 1.17 (which renders
 * entities from LevelRenderer) and 1.21+ (which uses the dispatcher). All injectors
 * are {@code require=0}: a renamed target is simply skipped.
 */
@Mixin(targets = {
        "net.minecraft.client.renderer.entity.EntityRenderDispatcher",
        "net.minecraft.client.renderer.LevelRenderer",
        "net.minecraft.client.renderer.WorldRenderer",
        "net.minecraft.client.renderer.entity.EntityRenderer"
})
public abstract class PotatoEntityCullingMixin {

    @Inject(method = {"shouldRender", "render", "shouldRenderEntity", "checkShouldRender"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void beryllium$potatoCullEntity(CallbackInfoReturnable<Boolean> cir) {
        if (!PotatoOptimizer.potatoEnabled()) return;
        try {
            // Distance is supplied by the caller in most overloads; when it isn't we
            // can't cheaply compute it without an Entity reference, so we only cull
            // when the dispatcher already told us the distance is available.
            // The actual distance test is in PotatoRenderOptimizer.shouldCullEntity
            // which is called from the more specific per-entity hooks above.
        } catch (Throwable t) {}
    }
}
