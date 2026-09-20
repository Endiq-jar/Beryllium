package com.endiq.beryllium.mixin.chat;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.chat.ChatFilterRules;
import com.endiq.beryllium.config.BerylliumConfig;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drops advancement announcements and admin (command feedback) chat.
 *
 * <p>There is one handler per shape of {@code addMessage} the supported releases have, because
 * the message types moved and gained parameters inside the range. Each is independent: on a
 * release where one shape does not exist, the others still work.
 *
 * <p>The decision is made on the message's translation key, never on its text, so a player whose
 * name or message resembles an advancement line is never hidden by this.
 */
@Mixin(targets = "net.minecraft.client.gui.components.ChatComponent")
public abstract class ChatFilterMixin {

	// 1.19.4 - 1.20.x: (Component, MessageSignature, int, GuiMessageTag, boolean)
	@Inject(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;ILnet/minecraft/client/GuiMessageTag;Z)V",
		at = @At("HEAD"), cancellable = true, require = 0)
	private void beryllium$filterLegacy(Component message, @Coerce Object signature, int id,
			@Coerce Object tag, boolean refresh, CallbackInfo ci) {
		beryllium$filter(message, ci);
	}

	// 1.21.x: (Component, MessageSignature, GuiMessageTag)
	@Inject(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/GuiMessageTag;)V",
		at = @At("HEAD"), cancellable = true, require = 0)
	private void beryllium$filter(Component message, @Coerce Object signature, @Coerce Object tag, CallbackInfo ci) {
		beryllium$filter(message, ci);
	}

	// 26.x: (Component, MessageSignature, GuiMessageSource, GuiMessageTag)
	@Inject(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/multiplayer/chat/GuiMessageSource;Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V",
		at = @At("HEAD"), cancellable = true, require = 0)
	private void beryllium$filterWithSource(Component message, @Coerce Object signature,
			@Coerce Object source, @Coerce Object tag, CallbackInfo ci) {
		beryllium$filter(message, ci);
	}

	@Unique
	private void beryllium$filter(Component message, CallbackInfo ci) {
		try {
			BerylliumConfig config = Beryllium.config();
			if (config == null || !config.enabled || !config.chatFilter || message == null) {
				return;
			}

			Object contents = com.endiq.beryllium.compat.Reflect.call(message, "getContents");
			if (contents == null) {
				return;
			}
			String key = com.endiq.beryllium.compat.Reflect.asString(
					com.endiq.beryllium.compat.Reflect.call(contents, "getKey"));

			if (ChatFilterRules.isAdvancementAnnouncement(key) && !config.chatAnnounceAdvancements) {
				ci.cancel();
				return;
			}
			if (ChatFilterRules.isAdminMessage(key) && !config.chatAdminMessages) {
				ci.cancel();
			}
		} catch (Throwable t) {
			// Showing a message that could have been hidden is the safe failure.
		}
	}
}
