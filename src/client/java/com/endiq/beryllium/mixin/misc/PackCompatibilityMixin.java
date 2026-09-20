package com.endiq.beryllium.mixin.misc;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.config.BerylliumConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Answers "yes, this pack is fine" for every resource pack, which is what removes the version
 * mismatch confirmation.
 *
 * <p>The game asks a pack's compatibility to decide whether to warn before applying it; the
 * warning is the only thing this changes. Packs that genuinely cannot be read still fail the
 * same way they always did, at the point they are loaded.
 */
@Mixin(targets = "net.minecraft.server.packs.repository.PackCompatibility")
public abstract class PackCompatibilityMixin {
	@Inject(method = "isCompatible", at = @At("HEAD"), cancellable = true, require = 0)
	private void beryllium$alwaysCompatible(CallbackInfoReturnable<Boolean> cir) {
		try {
			BerylliumConfig config = Beryllium.config();
			if (config != null && config.enabled && config.disablePackVersionMismatchScreen) {
				cir.setReturnValue(Boolean.TRUE);
			}
		} catch (Throwable t) {
			// Vanilla's answer stands.
		}
	}
}
