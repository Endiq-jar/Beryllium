package com.endiq.beryllium.mixin.misc;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.compat.Reflect;
import com.endiq.beryllium.config.BerylliumConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Suppresses the three toasts nobody asked for: "chat messages can't be verified" (which the
 * game shows on every server that does not sign chat), advancement toasts, and recipe toasts.
 *
 * <p>Toasts are identified by the name of the toast class — an advancement toast is an
 * {@code AdvancementToast} on every supported release — rather than by the packet that caused
 * them, because that packet's shape changed several times across the range while the toast
 * classes did not. The insecure-chat warning is a general-purpose system toast, so it is
 * identified by its token, which is what the game itself uses to avoid showing it twice.
 */
@Mixin(targets = {
		"net.minecraft.client.gui.components.toasts.ToastComponent",
		"net.minecraft.client.gui.components.toasts.ToastManager"
})
public abstract class ToastsMixin {
	@Inject(method = "addToast(Lnet/minecraft/client/gui/components/toasts/Toast;)V",
			at = @At("HEAD"), cancellable = true, require = 0)
	private void beryllium$filterToast(@Coerce Object toast, CallbackInfo ci) {
		try {
			BerylliumConfig config = Beryllium.config();
			if (config == null || !config.enabled || !config.disableToasts || toast == null) {
				return;
			}
			if (beryllium$shouldHide(toast)) {
				ci.cancel();
			}
		} catch (Throwable t) {
			// Showing a toast that could have been hidden is the safe failure.
		}
	}

	@Unique
	private boolean beryllium$shouldHide(Object toast) {
		String name = toast.getClass().getName();
		if (name.endsWith("AdvancementToast") || name.endsWith("RecipeToast")) {
			return true;
		}
		if (!name.endsWith("SystemToast")) {
			return false;
		}
		Object token = Reflect.call(toast, "getToken");
		if (token == null) {
			return false;
		}
		String tokenName = token instanceof Enum<?> constant ? constant.name() : String.valueOf(token);
		String upper = tokenName.toUpperCase(java.util.Locale.ROOT);
		return upper.contains("INSECURE") || upper.contains("UNVERIFIED") || upper.contains("UNSIGNED");
	}
}
