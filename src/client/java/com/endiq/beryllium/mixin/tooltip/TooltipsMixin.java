package com.endiq.beryllium.mixin.tooltip;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.config.BerylliumConfig;
import com.endiq.beryllium.tooltip.TooltipWrapper;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Wraps tooltips that are too wide to fit on screen.
 *
 * <p>A tooltip wider than the window cannot be positioned anywhere sensible, so the game lets it
 * run off the edge. Wrapping it into several lines is what keeps the whole thing readable, and it
 * gives the game's own positioner a box it can keep on screen — which is also why this needs no
 * second change to the positioning.
 *
 * <p>Both shapes of {@code getTooltipLines} are hooked (the context argument arrived partway
 * through the supported range); whichever this release has is the one that runs.
 */
@Mixin(targets = "net.minecraft.world.item.ItemStack")
public abstract class TooltipsMixin {
	@Inject(method = "getTooltipLines(Lnet/minecraft/world/item/Item$TooltipContext;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/TooltipFlag;)Ljava/util/List;",
			at = @At("RETURN"), cancellable = true, require = 0)
	private void beryllium$wrapTooltip(@Coerce Object context, @Coerce Object player, @Coerce Object flag,
			CallbackInfoReturnable<List<Component>> cir) {
		beryllium$wrap(cir);
	}

	@Inject(method = "getTooltipLines(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/TooltipFlag;)Ljava/util/List;",
			at = @At("RETURN"), cancellable = true, require = 0)
	private void beryllium$wrapTooltipLegacy(@Coerce Object player, @Coerce Object flag,
			CallbackInfoReturnable<List<Component>> cir) {
		beryllium$wrap(cir);
	}

	private void beryllium$wrap(CallbackInfoReturnable<List<Component>> cir) {
		try {
			BerylliumConfig config = Beryllium.config();
			if (config == null || !config.enabled || !config.tooltips) {
				return;
			}
			List<Component> lines = cir.getReturnValue();
			if (lines == null || lines.isEmpty()) {
				return;
			}
			Minecraft minecraft = Minecraft.getInstance();
			if (minecraft == null || minecraft.font == null) {
				return;
			}
			int width = Math.min(config.maxTooltipWidth, Math.max(80, minecraft.getWindow().getGuiScaledWidth() - 16));
			List<Component> wrapped = TooltipWrapper.wrap(lines, width, minecraft.font);
			if (wrapped != lines) {
				cir.setReturnValue(wrapped);
			}
		} catch (Throwable t) {
			// An unwrapped tooltip is the tooltip the game built.
		}
	}
}
