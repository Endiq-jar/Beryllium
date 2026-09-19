package com.endiq.beryllium.mixin.chat;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.chat.SuggestionOrdering;
import com.endiq.beryllium.config.BerylliumConfig;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Ranks the command suggestion popup by how well each candidate matches, instead of hiding the
 * ones that do not start with what was typed.
 *
 * <p>The half that decides <em>what may be suggested</em> lives in
 * {@link SharedSuggestionProviderMixin}; this half only reorders, so nothing a player could
 * previously see has disappeared — it has moved, and usually upwards.
 *
 * <p>{@code sortSuggestions} is matched by name and is allowed to be missing: on a release with
 * a different suggestion pipeline the popup keeps vanilla's order and the filtering half still
 * applies.
 */
@Mixin(targets = "net.minecraft.client.gui.components.CommandSuggestions")
public abstract class CommandSuggestionsMixin {
	@Inject(method = "sortSuggestions", at = @At("HEAD"), cancellable = true, require = 0)
	private void beryllium$rankSuggestions(Suggestions suggestions, CallbackInfoReturnable<List<Suggestion>> cir) {
		try {
			BerylliumConfig config = Beryllium.config();
			if (config == null || !config.enabled || !config.improvedCommandSuggestions) {
				return;
			}
			Object typed = beryllium$typedText();
			if (!(typed instanceof String text) || suggestions == null) {
				return;
			}
			cir.setReturnValue(SuggestionOrdering.rank(text, suggestions.getList()));
		} catch (Throwable t) {
			// Leaving the popup in vanilla's order is always an option.
		}
	}

	@Unique
	private Object beryllium$typedText() {
		try {
			Object input = com.endiq.beryllium.compat.Reflect.get(this, "input");
			if (input == null) {
				return null;
			}
			Object value = com.endiq.beryllium.compat.Reflect.call(input, "getValue");
			Object cursor = com.endiq.beryllium.compat.Reflect.call(input, "getCursorPosition");
			if (!(value instanceof String text) || !(cursor instanceof Integer position)) {
				return null;
			}
			return text.substring(0, Math.max(0, Math.min(position, text.length())));
		} catch (Throwable t) {
			return null;
		}
	}
}
