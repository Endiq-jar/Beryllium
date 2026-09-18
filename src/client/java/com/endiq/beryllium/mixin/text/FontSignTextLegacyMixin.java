package com.endiq.beryllium.mixin.text;

import com.endiq.beryllium.text.SignTextState;
import org.joml.Matrix4f;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sign text optimisation for releases that predate {@code Font.DisplayMode} (1.19.4),
 * where the see-through/glow layer is selected by a plain {@code boolean} instead.
 *
 * <p>Identical logic to {@code FontSignTextMixin} — the only difference is which argument
 * identifies the outline layer. On 1.20+ these descriptors simply do not resolve and the
 * mixin is a no-op ({@code require = 0}), which is why both variants can live in the same
 * source tree.
 */
@Mixin(Font.class)
public abstract class FontSignTextLegacyMixin {

	@Inject(
		method = "drawInBatch(Lnet/minecraft/network/chat/Component;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;ZII)I",
		at = @At("HEAD"),
		cancellable = true,
		require = 0
	)
	private void beryllium$signTextComponentLegacy(
		Component text, float x, float y, int color, boolean dropShadow, Matrix4f matrix,
		MultiBufferSource bufferSource, boolean seeThrough, int backgroundColor,
		int packedLightCoords, CallbackInfoReturnable<Integer> cir
	) {
		beryllium$decideLegacy(seeThrough, cir);
	}

	@Inject(
		method = "drawInBatch(Ljava/lang/String;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;ZII)I",
		at = @At("HEAD"),
		cancellable = true,
		require = 0
	)
	private void beryllium$signTextStringLegacy(
		String text, float x, float y, int color, boolean dropShadow, Matrix4f matrix,
		MultiBufferSource bufferSource, boolean seeThrough, int backgroundColor,
		int packedLightCoords, CallbackInfoReturnable<Integer> cir
	) {
		beryllium$decideLegacy(seeThrough, cir);
	}

	@Unique
	private void beryllium$decideLegacy(boolean seeThrough, CallbackInfoReturnable<Integer> cir) {
		if (!SignTextState.isInSignText()) {
			return;
		}

		SignTextState.noteDraw(seeThrough);

		if (SignTextState.shouldHideText()) {
			cir.setReturnValue(0);
			return;
		}
		if (seeThrough && SignTextState.shouldDropGlow()) {
			cir.setReturnValue(0);
			return;
		}
		if (seeThrough && SignTextState.shouldCancelOutline()) {
			cir.setReturnValue(0);
		}
	}
}
