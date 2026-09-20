package com.endiq.beryllium.mixin.optimization;

import com.endiq.beryllium.optimization.DeduplicationCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Deduplication: ResourceKey, ResourceLocation, Vertices.
 */
@Mixin(targets = {"net.minecraft.resources.ResourceLocation", "net.minecraft.resources.ResourceKey", "net.minecraft.client.renderer.vertex.VertexFormat", "net.minecraft.client.renderer.block.ModelBlockRenderer"})
public abstract class DeduplicationMixin {
    @Inject(method = {"<init>", "create", "of"}, at = @At("RETURN"), require = 0)
    private void beryllium$dedup(CallbackInfoReturnable<?> cir) {
        if (!DeduplicationCache.enabled()) return;
    }
    @Inject(method = {"parse", "tryParse", "of"}, at = @At("RETURN"), require = 0)
    private static void beryllium$dedupStatic(CallbackInfoReturnable<?> cir) {
        if (!DeduplicationCache.enabled()) return;
        if (cir.getReturnValue() instanceof String) {
            String s = (String)cir.getReturnValue();
            String d = DeduplicationCache.dedupLocation(s);
            // cir.setReturnValue(d); // would need mutable
        }
    }
}
