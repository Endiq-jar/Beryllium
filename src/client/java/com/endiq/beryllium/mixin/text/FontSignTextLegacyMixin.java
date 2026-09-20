package com.endiq.beryllium.mixin.text;

import com.endiq.beryllium.text.SignTextState;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sign text optimisation for the releases before the display-mode overloads.
 *
 * <p>The two {@code drawInBatch} overloads that sign text goes through on 1.19.x carry a plain
 * {@code seeThrough} boolean instead of a {@link Font.DisplayMode}; the optimisation decides the
 * same three things there — hide text that is too far away or out of view, hide the glowing
 * outline pass, and drop it entirely for a sign that is not glowing — and answers with the same
 * "drew nothing" value vanilla returns for an empty draw.
 *
 * <p>Both injectors name a full descriptor and are allowed not to match
 * ({@code require = 0}), so every release that carries the display-mode overloads instead
 * simply skips this file and is handled by {@code FontSignTextMixin}.
 */
@Mixin(targets = {"net.minecraft.client.gui.Font", "net.minecraft.client.gui.font.Font"})
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
		beryllium$decideLegacy(seeThrough, y, cir);
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
		beryllium$decideLegacy(seeThrough, y, cir);
	}

	@Unique
	private void beryllium$decideLegacy(boolean seeThrough, float y, CallbackInfoReturnable<Integer> cir) {
		if (SignTextState.isDrawingShadowPass()) {
			// Our own replacement pass: never cancel it.
			return;
		}
		if (!SignTextState.isInSignText()) {
			return;
		}

		SignTextState.noteDraw(seeThrough, y);

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
