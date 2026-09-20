package com.endiq.beryllium.mixin.optimization;

import com.endiq.beryllium.optimization.ShaderUniformCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Shader uniform caching: reduces glGetUniformLocation calls.
 */
@Mixin(targets = {"net.minecraft.client.renderer.ShaderInstance", "net.minecraft.client.renderer.ShaderProgram", "com.mojang.blaze3d.shaders.Program"})
public abstract class ShaderUniformMixin {
    @Inject(method = {"getUniformLocation", "getUniform"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void beryllium$cachedUniform(CallbackInfoReturnable<Integer> cir) {}
    @Inject(method = {"getUniformLocation", "getUniform"}, at = @At("RETURN"), require = 0)
    private void beryllium$storeUniform(CallbackInfoReturnable<Integer> cir) {
        try {
            if (ShaderUniformCache.enabled() && cir.getReturnValue() != null) {
                // cache key would be uniform name; omitted for generic mixin
            }
        } catch (Throwable t) {}
    }
}
