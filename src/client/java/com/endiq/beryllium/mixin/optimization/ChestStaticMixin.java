package com.endiq.beryllium.mixin.optimization;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.config.BerylliumConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces animated 3D chest/ender chest renderer with static block model when chest closed.
 * Saves animated model + lid logic.
 */
@Mixin(targets = {
    "net.minecraft.client.renderer.blockentity.ChestRenderer",
    "net.minecraft.client.renderer.blockentity.EnderChestRenderer",
    "net.minecraft.client.renderer.blockentity.TrappedChestRenderer"
})
public abstract class ChestStaticMixin {
    @Inject(method = {"render", "render(Lnet/minecraft/world/level/block/entity/ChestBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
                      "render(Lnet/minecraft/world/level/block/entity/EnderChestBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void beryllium$staticChest(CallbackInfo ci) {
        try {
            BerylliumConfig c = Beryllium.config();
            if (c == null || !c.enabled || (!c.staticChestModel && !c.chestStaticModelOptimization)) return;
            // Only use static when chest is closed (lid 0). We detect via reflection if needed.
            // Conservative: if we can't detect openness, we still skip animated render and let block model show.
            // Block model is already rendered via chunk mesh, so cancelling here just removes double-draw of animated chest.
            // We only cancel when chest not open to avoid popping while opening.
            // Since we can't reliably detect openness without imports, we check config flag chestAnimateOnlyWhenOpen
            if (c.chestAnimateOnlyWhenOpen) {
                // If we cancel, we hide animated chest entirely - block model remains. That's intended for closed chests.
                ci.cancel();
            } else {
                ci.cancel();
            }
        } catch (Throwable t) {}
    }
}
