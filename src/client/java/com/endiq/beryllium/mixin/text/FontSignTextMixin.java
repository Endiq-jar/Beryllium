package com.endiq.beryllium.mixin.text;

import com.endiq.beryllium.text.SignTextState;
import com.mojang.blaze3d.vertex.Matrix4f;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sign text optimisation (1.20+): the place where the decision actually takes effect.
 *
 * <p>Every glyph of every sign line goes through {@code Font#drawInBatch} — so does every
 * other piece of text in the game, which is why the decision needs
 * {@link SignTextState} (set by {@code SignTextScopeMixin}) to know that this call is sign
 * text. Given that context, three separate savings apply:
 * <ul>
 *   <li><strong>Text hidden at distance / out of view</strong> — the whole line is skipped.
 *       The sign board itself still renders; only the (illegible) glyph passes go.</li>
 *   <li><strong>Glow hidden at distance</strong> — beyond
 *       {@code signTextGlowCullDistance} the see-through passes that make text glow are
 *       skipped, and the text still draws normally.</li>
 *   <li><strong>Outline converted to a shadow</strong> — vanilla draws glowing text eight
 *       times per line to produce its outline. NORMAL mode keeps one pass per line (which
 *       reads as a shadow), FAST mode keeps none.</li>
 * </ul>
 *
 * <p>All three {@code drawInBatch} overloads that carry a display mode are targeted with
 * {@code require = 0}: a signature that does not exist on the running version is skipped,
 * and the ones that do survive still get the optimisation.
 *
 * <p>This mixin uses 1.20+'s {@code Font.DisplayMode}; the pre-1.20 boolean variant is
 * handled by {@code FontSignTextLegacyMixin}, and the build picks one per Minecraft
 * release.
 */
@Mixin(Font.class)
public abstract class FontSignTextMixin {

	@Inject(
		method = "drawInBatch(Lnet/minecraft/network/chat/Component;FFFFIZLcom/mojang/blaze3d/vertex/Matrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/gui/Font$DisplayMode;II)I",
		at = @At("HEAD"),
		cancellable = true,
		require = 0
	)
	private void beryllium$signTextComponent(
		Component text, float x, float y, int color, boolean dropShadow, Matrix4f matrix,
		MultiBufferSource bufferSource, Font.DisplayMode displayMode, int backgroundColor,
		int packedLightCoords, CallbackInfoReturnable<Integer> cir
	) {
		beryllium$decide(displayMode, cir);
	}

	@Inject(
		method = "drawInBatch(Lnet/minecraft/util/FormattedText;FFFFIZLcom/mojang/blaze3d/vertex/Matrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/gui/Font$DisplayMode;II)I",
		at = @At("HEAD"),
		cancellable = true,
		require = 0
	)
	private void beryllium$signTextFormatted(
		FormattedText text, float x, float y, int color, boolean dropShadow, Matrix4f matrix,
		MultiBufferSource bufferSource, Font.DisplayMode displayMode, int backgroundColor,
		int packedLightCoords, CallbackInfoReturnable<Integer> cir
	) {
		beryllium$decide(displayMode, cir);
	}

	@Inject(
		method = "drawInBatch(Ljava/lang/String;FFFFIZLcom/mojang/blaze3d/vertex/Matrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/gui/Font$DisplayMode;II)I",
		at = @At("HEAD"),
		cancellable = true,
		require = 0
	)
	private void beryllium$signTextString(
		String text, float x, float y, int color, boolean dropShadow, Matrix4f matrix,
		MultiBufferSource bufferSource, Font.DisplayMode displayMode, int backgroundColor,
		int packedLightCoords, CallbackInfoReturnable<Integer> cir
	) {
		beryllium$decide(displayMode, cir);
	}

	@Unique
	private void beryllium$decide(Font.DisplayMode displayMode, CallbackInfoReturnable<Integer> cir) {
		if (!SignTextState.isInSignText()) {
			return;
		}

		boolean seeThrough = displayMode == Font.DisplayMode.SEE_THROUGH;
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
