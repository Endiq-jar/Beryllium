package com.endiq.beryllium.mixin.misc;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.config.BerylliumConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Makes buttons appear at full opacity instead of fading in over the first seconds of the title
 * screen.
 *
 * <p>Widgets are given an alpha every frame; this only ever raises it to fully opaque, so the
 * fade-in of anything else that uses the same mechanism (a screen opened during the fade, for
 * instance) is unaffected in the direction that matters: nothing is ever made more transparent.
 */
@Mixin(targets = "net.minecraft.client.gui.components.AbstractWidget")
public abstract class WidgetFadeMixin {
	@ModifyVariable(method = "setAlpha", at = @At("HEAD"), argsOnly = true, require = 0)
	private float beryllium$noFadeIn(float alpha) {
		try {
			BerylliumConfig config = Beryllium.config();
			if (config != null && config.enabled && config.removeWidgetFade) {
				return 1.0F;
			}
		} catch (Throwable t) {
			// Vanilla's alpha stands.
		}
		return alpha;
	}
}
