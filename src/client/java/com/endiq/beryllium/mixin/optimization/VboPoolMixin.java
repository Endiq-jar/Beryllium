package com.endiq.beryllium.mixin.optimization;

import com.endiq.beryllium.optimization.VboPool;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * DSA VBO/EBO pool.
 */
@Mixin(targets = {"net.minecraft.client.renderer.VertexBuffer", "com.mojang.blaze3d.vertex.BufferBuilder", "net.minecraft.client.renderer.RenderType"})
public abstract class VboPoolMixin {
    @Inject(method = {"<init>", "upload", "bind"}, at = @At("HEAD"), require = 0)
    private void beryllium$vbo(CallbackInfo ci) {
        if (!VboPool.enabled()) return;
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    @Inject(method = {"create", "allocate"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void beryllium$pool(CallbackInfoReturnable cir) {
        if (!VboPool.enabled()) return;
        Object pooled = VboPool.acquire();
        if (pooled != null) cir.setReturnValue(pooled);
    }
}
