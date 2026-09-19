package com.endiq.beryllium.mixin.chat;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.compat.Reflect;
import com.endiq.beryllium.config.BerylliumConfig;
import net.minecraft.client.gui.components.EditBox;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets commands be longer than the 256 characters vanilla allows — and only commands.
 *
 * <p>The limit exists because the <em>server</em> kicks a client that sends an over-long chat
 * message, so the moment the input stops looking like a command the vanilla limit comes back.
 * That check happens on every edit, which is why a message that starts as a command but turns
 * into ordinary text is re-limited as soon as it changes.
 *
 * <p>{@code normalizeChatMessage} also folds the text through the same normalisation vanilla
 * uses, so a long command reaches the server in exactly the shape a short one would.
 */
@Mixin(targets = "net.minecraft.client.gui.screens.ChatScreen")
public abstract class ChatScreenLengthMixin {
	private static final int BERYLLIUM_VANILLA_LIMIT = 256;

	@Inject(method = "init", at = @At("RETURN"), require = 0)
	private void beryllium$allowLongCommands(CallbackInfo ci) {
		beryllium$setLimit(beryllium$text(), true);
	}

	@Inject(method = "onEdited", at = @At("HEAD"), require = 0)
	private void beryllium$trackLimit(String text, CallbackInfo ci) {
		beryllium$setLimit(text, false);
	}

	@Inject(method = "normalizeChatMessage", at = @At("HEAD"), cancellable = true, require = 0)
	private void beryllium$normalizeLongCommand(String text, CallbackInfoReturnable<String> cir) {
		try {
			BerylliumConfig config = Beryllium.config();
			if (config == null || !config.enabled || !config.commandLengthLimit) {
				return;
			}
			if (text != null && text.startsWith("/")) {
				// Vanilla's own normalisation, minus the length check that follows it.
				cir.setReturnValue(text.trim().replaceAll("\\s+", " "));
			}
		} catch (Throwable t) {
			// Vanilla's path.
		}
	}

	private void beryllium$setLimit(String text, boolean initialising) {
		try {
			BerylliumConfig config = Beryllium.config();
			if (config == null || !config.enabled || !config.commandLengthLimit) {
				return;
			}
			Object input = Reflect.get(this, "input");
			if (!(input instanceof EditBox box)) {
				return;
			}
			boolean command = text != null && text.startsWith("/");
			if (command) {
				box.setMaxLength(Integer.MAX_VALUE);
				return;
			}
			// Ordinary chat text: vanilla's limit, and clamp anything already past it so the
			// player cannot send a message that gets them kicked.
			if (!initialising) {
				Object value = box.getValue();
				if (value instanceof String current && current.length() > BERYLLIUM_VANILLA_LIMIT) {
					beryllium$clamp(box, current.substring(0, BERYLLIUM_VANILLA_LIMIT));
				}
			}
			box.setMaxLength(BERYLLIUM_VANILLA_LIMIT);
		} catch (Throwable t) {
			// Leaving the field's own limit alone is the safe failure.
		}
	}

	private void beryllium$clamp(EditBox box, String text) {
		try {
			Reflect.call(box, "setValue", text);
		} catch (Throwable ignored) {
			// Best effort.
		}
	}

	private String beryllium$text() {
		try {
			Object input = Reflect.get(this, "input");
			Object value = input == null ? null : Reflect.call(input, "getValue");
			return value instanceof String text ? text : null;
		} catch (Throwable t) {
			return null;
		}
	}
}
