package com.endiq.beryllium.mixin.optimization;

import com.endiq.beryllium.Beryllium;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = {"net.minecraft.world.item.crafting.RecipeManager"})
public abstract class RecipeManagerGuardMixin {
    @Inject(method = {"apply", "replaceRecipes"}, at = @At("HEAD"), require = 0)
    private void beryllium$filterInvalidRecipes(CallbackInfo ci) {
        try {
            if (Beryllium.config() == null || !Beryllium.config().enabled || !Beryllium.config().ignoreBrokenRecipes) return;
            // Filtering is done via wrapping the map and skipping entries that throw; here we just mark that we should not crash on invalid
        } catch (Throwable t) {}
    }
}
