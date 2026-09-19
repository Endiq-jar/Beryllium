package com.endiq.beryllium.mixin.misc;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.config.BerylliumConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps the loading overlay from ever being installed.
 *
 * <p>An overlay is not just something drawn: while one is installed the game does not treat
 * the screen underneath as active, so the mouse is handed to the overlay and the game behind
 * it may as well be paused. Refusing to install it is therefore the whole of "no reload
 * background" and "still interactive while reloading" at once — nothing draws over the world,
 * nothing captures the mouse, and there is no fade because there is nothing to fade.
 *
 * <p>The argument is not declared: this is a void method and the callback does not need the
 * overlay it was given, which keeps the hook free of any type that could have moved.
 */
@Mixin(targets = "net.minecraft.client.Minecraft")
public abstract class OverlayInstallMixin {
	@Inject(method = "setOverlay", at = @At("HEAD"), cancellable = true, require = 0)
	private void beryllium$skipOverlay(CallbackInfo ci) {
		try {
			BerylliumConfig config = Beryllium.config();
			if (config != null && config.enabled && config.removeOverlay) {
				ci.cancel();
			}
		} catch (Throwable t) {
			// Showing the overlay is what vanilla does.
		}
	}
}
