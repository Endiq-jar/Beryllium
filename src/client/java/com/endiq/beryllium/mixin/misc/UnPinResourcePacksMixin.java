package com.endiq.beryllium.mixin.misc;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.config.BerylliumConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Unties the packs the game keeps pinned, so a server resource pack can be moved below a local
 * one — which is the only way to keep a local pack's textures on top of a server's.
 *
 * <p>Pinned packs cannot be dragged or disabled in the pack screen; reporting them as unpinned
 * hands that control back to the player. Nothing is reordered automatically, and the server's
 * pack still arrives enabled.
 */
@Mixin(targets = "net.minecraft.server.packs.repository.Pack")
public abstract class UnPinResourcePacksMixin {
	@Inject(method = "isFixedPosition", at = @At("HEAD"), cancellable = true, require = 0)
	private void beryllium$unpin(CallbackInfoReturnable<Boolean> cir) {
		try {
			BerylliumConfig config = Beryllium.config();
			if (config != null && config.enabled && config.unPinResourcePacks) {
				cir.setReturnValue(Boolean.FALSE);
			}
		} catch (Throwable t) {
			// Vanilla's answer stands.
		}
	}
}
