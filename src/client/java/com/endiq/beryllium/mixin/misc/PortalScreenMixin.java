package com.endiq.beryllium.mixin.misc;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.config.BerylliumConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Keeps the open screen open while the player walks through a portal.
 *
 * <p>The player's portal transition decides whether to close the screen by asking it two
 * questions, and the question changed partway through the supported range: older releases ask
 * whether the screen pauses the game, newer ones whether it is allowed to stay open inside a
 * portal. Both are answered with "yes, leave it alone" here.
 *
 * <p>The hooks are on {@code LocalPlayer} and answer the question at the place it is asked,
 * which works for every screen rather than only for the ones that would opt in. Each hook is
 * allowed not to match, because the older and newer question do not exist at the same time.
 */
@Mixin(targets = "net.minecraft.client.player.LocalPlayer")
public abstract class PortalScreenMixin {

	/** The releases that ask {@code isPauseScreen}. */
	@Redirect(
		method = {"handlePortalTransitionEffect", "handleNetherPortalClient", "handleConfusionTransitionEffect"},
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/Screen;isPauseScreen()Z"),
		require = 0
	)
	private boolean beryllium$keepScreenOpenLegacy() {
		return beryllium$enabled();
	}

	/** The releases that ask {@code isAllowedInPortal}. */
	@Redirect(
		method = "handlePortalTransitionEffect",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/Screen;isAllowedInPortal()Z"),
		require = 0
	)
	private boolean beryllium$keepScreenOpenModern() {
		return beryllium$enabled();
	}

	private boolean beryllium$enabled() {
		try {
			BerylliumConfig config = Beryllium.config();
			return config != null && config.enabled && config.allowScreensInPortals;
		} catch (Throwable t) {
			// Closing the screen is what vanilla does.
			return false;
		}
	}
}
