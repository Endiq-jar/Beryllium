package com.endiq.beryllium.mixin.chat;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.config.BerylliumConfig;
import net.minecraft.client.multiplayer.chat.GuiMessageTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Removes the padlock next to a chat message the client could not verify — the location of the
 * chat message type after 1.21.11. See {@link ChatUnsignedIconLegacyMixin}.
 */
@Mixin(targets = "net.minecraft.client.multiplayer.chat.GuiMessage")
public abstract class ChatUnsignedIconModernMixin {
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
