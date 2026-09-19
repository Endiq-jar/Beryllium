package com.endiq.beryllium.mixin.render;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.compat.Reflect;
import com.endiq.beryllium.config.BerylliumConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Removes the hairline gaps at the edges of generated models.
 *
 * <p>Every sprite drawn from the block atlas is inset by a small UV shrink ratio so that
 * neighbouring texels cannot bleed into it. On generated models — the ones with flat faces,
 * like item frames, ladders and panes — that inset shows up as a one-pixel gap at the edge of
 * the model, which is the seam players see against another block.
 *
 * <p>Only the block atlas is affected, and only when the sprite exposes its shrink ratio as a
 * method; on releases where it is a constructor value instead, this does not match and nothing
 * changes. The sprite's atlas is identified reflectively so no sprite or resource-location type
 * has to be named here.
 */
@Mixin(targets = "net.minecraft.client.renderer.texture.TextureAtlasSprite")
public abstract class ModelGapMixin {
	private static final String BERYLLIUM_BLOCK_ATLAS = "minecraft:textures/atlas/blocks.png";

	@Inject(method = "uvShrinkRatio", at = @At("HEAD"), cancellable = true, require = 0)
	private void beryllium$closeModelGaps(CallbackInfoReturnable<Float> cir) {
		try {
			BerylliumConfig config = Beryllium.config();
			if (config == null || !config.enabled || !config.fixModelGaps) {
				return;
			}
			if (beryllium$isBlockAtlasSprite()) {
				cir.setReturnValue(0.0F);
			}
		} catch (Throwable t) {
			// Vanilla's inset stays.
		}
	}

	@Unique
	private boolean beryllium$isBlockAtlasSprite() {
		try {
			Object location = Reflect.get(this, "atlasLocation");
			if (location == null) {
				return false;
			}
			return BERYLLIUM_BLOCK_ATLAS.equals(String.valueOf(location));
		} catch (Throwable t) {
			return false;
		}
	}
}
