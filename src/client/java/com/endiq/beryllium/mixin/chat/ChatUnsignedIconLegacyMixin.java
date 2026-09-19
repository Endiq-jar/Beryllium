package com.endiq.beryllium.mixin.chat;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.config.BerylliumConfig;
import net.minecraft.client.GuiMessageTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Removes the padlock the game draws next to a chat message it could not verify.
 *
 * <p>The indicator is the message's tag, and the chat renderer draws the lock whenever that tag
 * is the "not secure" one, so clearing it is the whole fix — the message itself, its sender and
 * everything else about it are untouched.
 *
 * <p>This is the pre-1.21.11 location of the chat message type; {@link ChatUnsignedIconModernMixin}
 * is the same fix for the package it moved to. The build compiles exactly one of the two per
 * release, so neither ever references a type that release does not have.
 */
@Mixin(targets = "net.minecraft.client.GuiMessage")
public abstract class ChatUnsignedIconLegacyMixin {
	@Inject(method = "tag", at = @At("HEAD"), cancellable = true, require = 0)
	private void beryllium$hideUnsignedIcon(CallbackInfoReturnable<GuiMessageTag> cir) {
		try {
			BerylliumConfig config = Beryllium.config();
			if (config != null && config.enabled && config.removeUnsignedChatIcon) {
				cir.setReturnValue(null);
			}
		} catch (Throwable t) {
			// Leaving the tag alone keeps vanilla's indicator.
		}
	}
}
