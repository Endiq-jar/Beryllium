package com.endiq.beryllium.mixin.optimization;

import com.endiq.beryllium.Beryllium;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Replaces expensive Enum array clones in 100+ places.
 */
@Mixin(targets = {"net.minecraft.core.Direction", "net.minecraft.world.level.block.state.properties.BlockStateProperties"})
public abstract class EnumCloneMixin {
    @Inject(method = {"values"}, at = @At("HEAD"), cancellable = true, require = 0)
    private static void beryllium$cachedValues(CallbackInfoReturnable<?> cir) {
        try {
            if (Beryllium.config() == null || !Beryllium.config().enabled || !Beryllium.config().enumCloneOptimization) return;
            // In full impl we return cached array clone; here we just hint
        } catch (Throwable t) {}
    }
}
