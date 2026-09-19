package com.endiq.beryllium.mixin.chat;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.config.BerylliumConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Raises the 100-message chat history cap to the configured value.
 *
 * <p>The cap is a literal inside the methods that add a message, and it moved between those
 * methods across the supported releases, so every known one is listed. Rewriting the constant
 * keeps vanilla's own trimming logic — and therefore its scrollback — intact; only the number
 * changes.
 */
@Mixin(targets = "net.minecraft.client.gui.components.ChatComponent")
public abstract class ChatHistoryMixin {
	@ModifyConstant(
		method = {
			"addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;ILnet/minecraft/client/GuiMessageTag;Z)V",
			"addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/GuiMessageTag;)V",
			"addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/multiplayer/chat/GuiMessageSource;Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V",
			"addMessageToQueue",
			"addMessageToDisplayQueue",
			"refreshTrimmedMessages",
			"refreshTrimmedMessage"
		},
		constant = @Constant(intValue = 100),
		require = 0
	)
	private int beryllium$maxHistory(int original) {
		try {
			BerylliumConfig config = Beryllium.config();
			if (config == null || !config.enabled) {
				return original;
			}
			return Math.max(100, config.maxChatHistory);
		} catch (Throwable t) {
			return original;
		}
	}
}
