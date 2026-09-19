package com.endiq.beryllium.mixin.misc;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.config.BerylliumConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Three separate things about the loading/splash overlay, each with its own switch.
 *
 * <ul>
 *   <li>{@code removeOverlay} — nothing is drawn: the background, the logo and the progress
 *       bar are the overlay, so cancelling its draw call removes all of it.</li>
 *   <li>{@code disableSplashScreen} — the overlay stops pausing the game, so the world behind
 *       it keeps ticking and the player is not held at a dead screen while a resource reload
 *       or a world load runs.</li>
 *   <li>{@code disableLoadingFadeAnimation} — the fade is skipped and the overlay reports
 *       itself ready to go away as soon as the game is, which is what ends a reload the moment
 *       the work finishes instead of a second and a half later.</li>
 * </ul>
 *
 * <p>Every hook is allowed not to match ({@code require = 0}), because both the draw entry
 * point and the fade arithmetic were rewritten inside the supported range: on a release where
 * a hook does not match, that switch simply does nothing and the rest still work.
 */
@Mixin(targets = "net.minecraft.client.gui.screens.LoadingOverlay")
public abstract class OverlayMixin {

	/** The overlay draws through {@code render} up to 26.0 and through {@code extractRenderState}
	 *  from there on; both names are listed so either release is covered. */
	@Inject(method = {"render", "extractRenderState"}, at = @At("HEAD"), cancellable = true, require = 0)
	private void beryllium$hideOverlay(CallbackInfo ci) {
		if (beryllium$config(Config.REMOVE_OVERLAY)) {
			ci.cancel();
		}
	}

	@Inject(method = "isPauseScreen", at = @At("HEAD"), cancellable = true, require = 0)
	private void beryllium$doNotPause(CallbackInfoReturnable<Boolean> cir) {
		if (beryllium$config(Config.DO_NOT_PAUSE)) {
			cir.setReturnValue(Boolean.FALSE);
		}
	}

	@Inject(method = "isReadyToFadeOut", at = @At("HEAD"), cancellable = true, require = 0)
	private void beryllium$readyToFadeOut(CallbackInfoReturnable<Boolean> cir) {
		if (beryllium$config(Config.NO_FADE)) {
			cir.setReturnValue(Boolean.TRUE);
		}
	}

	/**
	 * The fade is the constant the overlay scales its alpha by: 2.0 at the start of the fade,
	 * 1.0 once it is done. Replacing it with 1.0 means there is no fade to wait for. This
	 * targets the same methods as the draw hook because the constant lives in their arithmetic.
	 */
	@ModifyConstant(method = {"render", "extractRenderState"}, constant = @Constant(floatValue = 2.0F), require = 0)
	private float beryllium$noFade(float original) {
		return beryllium$config(Config.NO_FADE) ? 1.0F : original;
	}

	/** Which switch this call answers to. */
	private enum Config {
		REMOVE_OVERLAY, DO_NOT_PAUSE, NO_FADE
	}

	private boolean beryllium$config(Config which) {
		try {
			BerylliumConfig config = Beryllium.config();
			if (config == null || !config.enabled) {
				return false;
			}
			return switch (which) {
				case REMOVE_OVERLAY -> config.removeOverlay;
				case DO_NOT_PAUSE -> config.disableSplashScreen;
				case NO_FADE -> config.disableLoadingFadeAnimation;
			};
		} catch (Throwable t) {
			// Whatever the game was going to do is what happens.
			return false;
		}
	}
}
