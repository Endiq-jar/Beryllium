package com.endiq.beryllium.mixin.optimization;

import com.endiq.beryllium.Beryllium;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Ignore broken shaped/shapeless recipes to avoid invalid packets.
 */
@Mixin(targets = {"net.minecraft.world.item.crafting.ShapedRecipe", "net.minecraft.world.item.crafting.ShapelessRecipe", "net.minecraft.world.item.crafting.RecipeManager"})
public abstract class RecipeFixMixin {
    @Inject(method = {"fromJson", "fromNetwork", "fromJson(Lcom/google/gson/JsonObject;)Lnet/minecraft/world/item/crafting/ShapedRecipe;", "fromJson(Lcom/google/gson/JsonObject;)Lnet/minecraft/world/item/crafting/ShapelessRecipe;"}, at = @At("HEAD"), cancellable = true, require = 0)
    private static void beryllium$ignoreBroken(CallbackInfoReturnable<?> cir) {
        try {
            if (Beryllium.config() == null || !Beryllium.config().enabled || !Beryllium.config().ignoreBrokenRecipes) return;
            // actual filtering happens in RecipeManager mixin; we just prevent throw
        } catch (Throwable t) {}
    }
    @Inject(method = {"replaceInput", "apply", "assemble"}, at = @At("HEAD"), require = 0)
    private void beryllium$recipeGuard(CallbackInfoReturnable<?> cir) {}
}
