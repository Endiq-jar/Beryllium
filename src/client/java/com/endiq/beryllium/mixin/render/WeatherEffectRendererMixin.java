package com.endiq.beryllium.mixin.render;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.config.BerylliumConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The post-split half of {@link WeatherMixin}: on the releases that moved weather drawing into
 * its own renderer, this is the method that actually draws the rain.
 *
 * <p>On releases without the class the mixin plugin skips this entirely, so it costs nothing
 * there.
 */
@Mixin(targets = "net.minecraft.client.renderer.WeatherEffectRenderer")
public abstract class WeatherEffectRendererMixin {
	@Inject(method = "render", at = @At("HEAD"), cancellable = true, require = 0)
	private void beryllium$skipRain(CallbackInfo ci) {
		beryllium$skip(ci);
	}

	/**
	 * The splash particles and the rain sounds come from here, not from the render call, so
	 * "no weather" means cancelling this too. The argument list grew a parameter partway
	 * through the supported range, which is why this handler takes none: Mixin allows a
	 * callback to ignore the target's arguments, and that keeps one hook correct everywhere.
	 */
	@Inject(method = "tickRainParticles", at = @At("HEAD"), cancellable = true, require = 0)
	private void beryllium$skipRainEffects(CallbackInfo ci) {
		beryllium$skip(ci);
	}

	private void beryllium$skip(CallbackInfo ci) {
		try {
			BerylliumConfig config = Beryllium.config();
			if (config != null && config.enabled && config.disableWeather) {
				ci.cancel();
			}
		} catch (Throwable t) {
			// Drawing the rain is what vanilla does.
		}
	}
}
