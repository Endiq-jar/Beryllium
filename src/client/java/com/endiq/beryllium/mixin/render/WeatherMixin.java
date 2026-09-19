package com.endiq.beryllium.mixin.render;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.config.BerylliumConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stops rain and snow from being drawn, and stops the rain loop sound and splash particles that
 * come with them.
 *
 * <p>Three hooks over the supported range, because the renderer was split out of
 * {@code LevelRenderer} partway through it: the two level-renderer entry points and the modern
 * weather renderer. Each is independent, and the ones this release does not have simply do not
 * match.
 *
 * <p>The weather itself keeps advancing — only drawing and the ambience are suppressed — so this
 * is purely visual and cannot desynchronise a player from the server.
 */
@Mixin(targets = "net.minecraft.client.renderer.LevelRenderer")
public abstract class WeatherMixin {
	@Inject(method = "renderSnowAndRain", at = @At("HEAD"), cancellable = true, require = 0)
	private void beryllium$skipRain(CallbackInfo ci) {
		if (beryllium$hidden()) {
			ci.cancel();
		}
	}

	@Inject(method = "tickRain", at = @At("HEAD"), cancellable = true, require = 0)
	private void beryllium$skipRainAmbience(CallbackInfo ci) {
		if (beryllium$hidden()) {
			ci.cancel();
		}
	}

	private boolean beryllium$hidden() {
		try {
			BerylliumConfig config = Beryllium.config();
			return config != null && config.enabled && config.disableWeather;
		} catch (Throwable t) {
			return false;
		}
	}
}
