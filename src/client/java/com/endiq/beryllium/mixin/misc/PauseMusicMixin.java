package com.endiq.beryllium.mixin.misc;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.compat.Reflect;
import com.endiq.beryllium.config.BerylliumConfig;
import com.endiq.beryllium.misc.SoundChannelPause;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps music paused when the rest of the game resumes.
 *
 * <p>When the game pauses it pauses every sound, and when it resumes it resumes every sound —
 * including music that the player had turned off with the Music volume slider, which starts
 * playing again for as long as it takes the music manager to notice. Closing a screen should not
 * be able to start music a player has switched off, so after the engine resumes, music is paused
 * again if the player's own music volume is zero.
 *
 * <p>Nothing else is touched: mobs, blocks and ambient audio resume normally.
 */
@Mixin(targets = "net.minecraft.client.sounds.SoundEngine")
public abstract class PauseMusicMixin {
	@Inject(method = "resume", at = @At("TAIL"), require = 0)
	private void beryllium$keepMusicPaused(CallbackInfo ci) {
		try {
			BerylliumConfig config = Beryllium.config();
			if (config == null || !config.enabled || !config.pauseMusic) {
				return;
			}
			if (!beryllium$musicTurnedOff()) {
				return;
			}
			SoundChannelPause.pauseMusic(this);
		} catch (Throwable t) {
			// Music resumes the way vanilla resumes it.
		}
	}

	private static boolean beryllium$musicTurnedOff() {
		try {
			Object options = Reflect.get(Minecraft.getInstance(), "options");
			Object volume = Reflect.call(options, "getSoundSourceVolume", SoundSource.MUSIC);
			return volume instanceof Float level && level <= 0.0F;
		} catch (Throwable t) {
			return false;
		}
	}
}
