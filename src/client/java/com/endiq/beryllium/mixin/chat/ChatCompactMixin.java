package com.endiq.beryllium.mixin.chat;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.chat.ChatCompactor;
import com.endiq.beryllium.config.BerylliumConfig;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Collapses a repeated chat message into the previous copy of it, with a count.
 *
 * <p>Repeating something into chat is how players make a point, and the result is a wall of the
 * same line pushing everything else out of the window. Counting instead of repeating keeps the
 * history useful.
 *
 * <p>The handler runs before vanilla decides anything about the message, and returns the
 * <em>same</em> message unchanged whenever anything does not line up, so the worst case is
 * vanilla behaviour. The previous copy is removed from the chat's own history through
 * {@link ChatCompactor}, which finds the history and its refresh method by name rather than by
 * type — both moved inside the supported range.
 */
@Mixin(targets = "net.minecraft.client.gui.components.ChatComponent")
public abstract class ChatCompactMixin {
	@ModifyVariable(
		method = {
			"addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;ILnet/minecraft/client/GuiMessageTag;Z)V",
			"addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/GuiMessageTag;)V",
			"addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/multiplayer/chat/GuiMessageSource;Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V"
		},
		at = @At("HEAD"),
		argsOnly = true,
		require = 0
	)
	private Component beryllium$compact(Component message) {
		try {
			BerylliumConfig config = Beryllium.config();
			if (config == null || !config.enabled || !config.compactChat) {
				return message;
			}
			return ChatCompactor.compact(this, message, config.compactChatMode);
		} catch (Throwable t) {
			return message;
		}
	}
}
