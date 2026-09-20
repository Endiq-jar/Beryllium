package com.endiq.beryllium.mixin.render;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.config.BerylliumConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stops animated textures (water, lava, fire, portal, and every animated block or item texture)
 * from animating.
 *
 * <p>Animation is a texture-manager tick: every animated sprite advances a frame and re-uploads
 * the affected atlas region. Skipping that tick while the option is off leaves the first frame
 * of each animation in place — which is what "textures do not animate" should look like, rather
 * than a frozen mid-animation frame.
 */
@Mixin(targets = "net.minecraft.client.renderer.texture.TextureManager")
public abstract class TextureAnimationMixin {
	@Inject(method = "tick", at = @At("HEAD"), cancellable = true, require = 0)
	private void beryllium$skipTextureTick(CallbackInfo ci) {
		try {
			BerylliumConfig config = Beryllium.config();
			if (config != null && config.enabled && config.disableTextureAnimation) {
				ci.cancel();
			}
		} catch (Throwable t) {
			// Ticking textures is what vanilla does.
		}
	}
}
