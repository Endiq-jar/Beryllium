package com.endiq.beryllium.mixin.chat;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.chat.SuggestionOrdering;
import com.endiq.beryllium.config.BerylliumConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes a candidate "match" when the typed text appears anywhere in it, not only at the front.
 *
 * <p>Vanilla's rule is a plain prefix test, which is why a half-remembered command name or an
 * id typed without its namespace does not appear. The extra candidates are ranked below the
 * prefix matches by {@link CommandSuggestionsMixin}, so the list still opens with what vanilla
 * would have shown.
 */
@Mixin(targets = "net.minecraft.commands.SharedSuggestionProvider")
public interface SharedSuggestionProviderMixin {
	@Inject(method = "matchesSubStr", at = @At("HEAD"), cancellable = true, require = 0)
	private static void beryllium$matchAnywhere(String remaining, String candidate, CallbackInfoReturnable<Boolean> cir) {
		try {
			BerylliumConfig config = Beryllium.config();
			if (config == null || !config.enabled || !config.improvedCommandSuggestions) {
				return;
			}
			if (SuggestionOrdering.matchesSubstring(remaining, candidate)) {
				cir.setReturnValue(true);
			}
		} catch (Throwable t) {
			// Falling through leaves vanilla's own answer in place.
		}
	}
}
