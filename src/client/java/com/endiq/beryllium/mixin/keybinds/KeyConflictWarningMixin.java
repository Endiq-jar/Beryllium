package com.endiq.beryllium.mixin.keybinds;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.config.BerylliumConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Stops the controls screen reporting a "used by" warning for keys that are shared on purpose.
 *
 * <p>Two binds on one key is exactly what {@code multipleBindingsPerKey} is for, so the warning
 * that comes with it is noise: the player made that choice, and the line that says which other
 * action already uses the key is no longer news. This makes the comparison the warning is built
 * from report "not the same key", which is all the warning depends on — nothing else in the game
 * treats two binds on one key as a conflict when they are dispatched together.
 */
@Mixin(targets = "net.minecraft.client.KeyMapping")
public abstract class KeyConflictWarningMixin {
	@Inject(method = "same", at = @At("HEAD"), cancellable = true, require = 0)
	private void beryllium$hideSharedKeyWarning(CallbackInfoReturnable<Boolean> cir) {
		try {
			BerylliumConfig config = Beryllium.config();
			if (config != null && config.enabled && config.noReusedModifierKeyWarning) {
				cir.setReturnValue(Boolean.FALSE);
			}
		} catch (Throwable t) {
			// Vanilla's answer stands.
		}
	}
}
