package com.endiq.beryllium.mixin.misc;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.compat.Reflect;
import com.endiq.beryllium.config.BerylliumConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Turns the game down while the window is in the background.
 *
 * <p>Every sound's volume is computed in one place, so scaling it there covers music, mobs,
 * blocks and ambient alike, without touching any of the volume sliders the player set. The
 * multiplier is read live: refocusing the window brings the volume straight back on the next
 * sound that starts or updates.
 *
 * <p>Whether the window is focused is asked of the game rather than tracked with a hook, so a
 * launcher that reports focus differently than the desktop does still gets the right answer.
 */
@Mixin(targets = "net.minecraft.client.sounds.SoundEngine")
public abstract class UnfocusedVolumeMixin {
	@Inject(method = "calculateVolume", at = @At("HEAD"), cancellable = true, require = 0)
	private void beryllium$reduceVolumeWhenUnfocused(float volume, SoundSource source,
			CallbackInfoReturnable<Float> cir) {
		try {
			BerylliumConfig config = Beryllium.config();
			if (config == null || !config.enabled || !config.unfocusedVolumeReducer) {
				return;
			}
			if (config.unfocusedVolume >= 1.0) {
				return;
			}
			if (beryllium$windowActive()) {
				return;
			}
			cir.setReturnValue(volume * (float) Math.max(0.0, config.unfocusedVolume));
		} catch (Throwable t) {
			// Full volume is what vanilla does.
		}
	}

	private boolean beryllium$windowActive() {
		try {
			Minecraft minecraft = Minecraft.getInstance();
			Object active = Reflect.call(minecraft, "isWindowActive");
			// Not being able to ask counts as focused, which is the quiet failure.
			return !(active instanceof Boolean focused) || focused;
		} catch (Throwable t) {
			return true;
		}
	}
}
