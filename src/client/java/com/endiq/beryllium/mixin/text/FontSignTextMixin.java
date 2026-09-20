package com.endiq.beryllium.mixin.text;

import com.endiq.beryllium.text.SignTextState;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sign text optimisation: the place where the decision actually takes effect.
 *
 * <p>Every glyph of every sign line goes through {@code Font#drawInBatch} — so does every
 * other piece of text in the game, which is why the decision needs {@link SignTextState}
 * (set by {@code SignTextScopeMixin}) to know that this call is sign text. Given that
 * context, three separate savings apply:
 * <ul>
 *   <li><strong>Text hidden at distance / out of view</strong> — the whole line is skipped.
 *       The sign board itself still renders; only the (illegible) glyph passes go.</li>
 *   <li><strong>Glow hidden at distance</strong> — beyond
 *       {@code signTextGlowCullDistance} the see-through passes that make text glow are
 *       skipped, and the text still draws normally.</li>
 *   <li><strong>Outline converted to a shadow</strong> — vanilla draws glowing text eight
 *       times per line to produce its outline. NORMAL mode keeps one darker, offset pass
 *       (which reads as a shadow), FAST mode keeps none.</li>
 * </ul>
 *
 * <p>Every injector names its target with a full descriptor and {@code require = 0}, so a
 * signature that a release does not carry is skipped rather than guessed at. That matters
 * more than it looks: the {@code drawInBatch} overloads gained and lost a trailing
 * {@code boolean} between releases, changed their second parameter type across the
 * {@code Component}/{@code FormattedCharSequence} split, and changed their return type from
 * {@code int} to {@code void} and back again — so the int-returning and void-returning
 * variants are both declared here and each release matches exactly one of them.
 *
 * <p>The matrix type is {@link org.joml.Matrix4f} on every release from 1.19.4 to 26.1
 * (verified against the real game jar), and {@code FormattedCharSequence} lives in
 * {@code net.minecraft.util} throughout, so no version-only class is imported here.
 */
@Mixin(Font.class)
public abstract class FontSignTextMixin {

	/** One vanilla outline step: 8xOutline draws at ±1 in text space. */
	@Unique
	private static final float BERYLLIUM$SHADOW_OFFSET = 1.0f;

	// --- int-returning drawInBatch -----------------------------------------------

	@Inject(
		method = "drawInBatch(Ljava/lang/String;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/gui/Font$DisplayMode;II)I",
		at = @At("HEAD"),
		cancellable = true,
		require = 0
	)
	private void beryllium$signTextString(
		String text, float x, float y, int color, boolean dropShadow, Matrix4f matrix,
		MultiBufferSource bufferSource, Font.DisplayMode displayMode, int backgroundColor,
		int packedLightCoords, CallbackInfoReturnable<Integer> cir
	) {
		beryllium$decide(displayMode, x, y, cir, null);
	}

	@Inject(
		method = "drawInBatch(Lnet/minecraft/network/chat/Component;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/gui/Font$DisplayMode;II)I",
		at = @At("HEAD"),
		cancellable = true,
		require = 0
	)
	private void beryllium$signTextComponent(
		Component text, float x, float y, int color, boolean dropShadow, Matrix4f matrix,
		MultiBufferSource bufferSource, Font.DisplayMode displayMode, int backgroundColor,
		int packedLightCoords, CallbackInfoReturnable<Integer> cir
	) {
		beryllium$decide(displayMode, x, y, cir, null);
	}

	@Inject(
		method = "drawInBatch(Lnet/minecraft/util/FormattedCharSequence;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/gui/Font$DisplayMode;II)I",
		at = @At("HEAD"),
		cancellable = true,
		require = 0
	)
	private void beryllium$signTextSequence(
		FormattedCharSequence text, float x, float y, int color, boolean dropShadow,
		Matrix4f matrix, MultiBufferSource bufferSource, Font.DisplayMode displayMode,
		int backgroundColor, int packedLightCoords, CallbackInfoReturnable<Integer> cir
	) {
		beryllium$decide(displayMode, x, y, cir, null);
	}

	// The same three overloads carry an extra trailing flag on several releases.

	@Inject(
		method = "drawInBatch(Ljava/lang/String;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/gui/Font$DisplayMode;IIZ)I",
		at = @At("HEAD"),
		cancellable = true,
		require = 0
	)
	private void beryllium$signTextStringForced(
		String text, float x, float y, int color, boolean dropShadow, Matrix4f matrix,
		MultiBufferSource bufferSource, Font.DisplayMode displayMode, int backgroundColor,
		int packedLightCoords, boolean force, CallbackInfoReturnable<Integer> cir
	) {
		beryllium$decide(displayMode, x, y, cir, null);
	}

	@Inject(
		method = "drawInBatch(Lnet/minecraft/network/chat/Component;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/gui/Font$DisplayMode;IIZ)I",
		at = @At("HEAD"),
		cancellable = true,
		require = 0
	)
	private void beryllium$signTextComponentForced(
		Component text, float x, float y, int color, boolean dropShadow, Matrix4f matrix,
		MultiBufferSource bufferSource, Font.DisplayMode displayMode, int backgroundColor,
		int packedLightCoords, boolean force, CallbackInfoReturnable<Integer> cir
	) {
		beryllium$decide(displayMode, x, y, cir, null);
	}

	// --- void-returning drawInBatch (the return value was dropped on some releases) ------

	@Inject(
		method = "drawInBatch(Ljava/lang/String;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/gui/Font$DisplayMode;II)V",
		at = @At("HEAD"),
		cancellable = true,
		require = 0
	)
	private void beryllium$signTextStringVoid(
		String text, float x, float y, int color, boolean dropShadow, Matrix4f matrix,
		MultiBufferSource bufferSource, Font.DisplayMode displayMode, int backgroundColor,
		int packedLightCoords, CallbackInfo ci
	) {
		beryllium$decide(displayMode, x, y, null, ci);
	}

	@Inject(
		method = "drawInBatch(Lnet/minecraft/network/chat/Component;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/gui/Font$DisplayMode;II)V",
		at = @At("HEAD"),
		cancellable = true,
		require = 0
	)
	private void beryllium$signTextComponentVoid(
		Component text, float x, float y, int color, boolean dropShadow, Matrix4f matrix,
		MultiBufferSource bufferSource, Font.DisplayMode displayMode, int backgroundColor,
		int packedLightCoords, CallbackInfo ci
	) {
		beryllium$decide(displayMode, x, y, null, ci);
	}

	@Inject(
		method = "drawInBatch(Lnet/minecraft/util/FormattedCharSequence;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/gui/Font$DisplayMode;II)V",
		at = @At("HEAD"),
		cancellable = true,
		require = 0
	)
	private void beryllium$signTextSequenceVoid(
		FormattedCharSequence text, float x, float y, int color, boolean dropShadow,
		Matrix4f matrix, MultiBufferSource bufferSource, Font.DisplayMode displayMode,
		int backgroundColor, int packedLightCoords, CallbackInfo ci
	) {
		beryllium$decide(displayMode, x, y, null, ci);
	}

	// --- the glowing outline ------------------------------------------------------

	/**
	 * Vanilla draws glowing sign text eight times per line ({@code drawInBatch8xOutline}).
	 * In FAST mode all eight go; in NORMAL mode they are replaced by a single darkened,
	 * offset pass, which is what a shadow is and costs one draw instead of eight.
	 */
	@Inject(
		method = "drawInBatch8xOutline(Lnet/minecraft/util/FormattedCharSequence;FFIILorg/joml/Matrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
		at = @At("HEAD"),
		cancellable = true,
		require = 0
	)
	private void beryllium$signTextOutline(
		FormattedCharSequence text, float x, float y, int color, int backgroundColor,
		Matrix4f matrix, MultiBufferSource bufferSource, int packedLightCoords, CallbackInfo ci
	) {
		SignTextState.noteOutline(y);
		if (!SignTextState.shouldDropOutline()) {
			return;
		}
		ci.cancel();
		if (!SignTextState.shouldKeepOneShadowPass()) {
			return;
		}

		Font self = (Font) (Object) this;
		int shadow = (color & 0xFCFCFC) >> 2 | (color & 0xFF000000);
		SignTextState.setDrawingShadowPass(true);
		try {
			self.drawInBatch(
				text, x + BERYLLIUM$SHADOW_OFFSET, y + BERYLLIUM$SHADOW_OFFSET, shadow, false,
				matrix, bufferSource, Font.DisplayMode.SEE_THROUGH, backgroundColor,
				packedLightCoords
			);
		} finally {
			SignTextState.setDrawingShadowPass(false);
		}
	}

	@Unique
	private void beryllium$decide(
		Font.DisplayMode displayMode, float x, float y,
		CallbackInfoReturnable<Integer> cir, CallbackInfo ci
	) {
		if (SignTextState.isDrawingShadowPass()) {
			// Our own replacement pass: never cancel it.
			return;
		}
		if (!SignTextState.isInSignText()) {
			return;
		}

		boolean seeThrough = displayMode == null ? false : displayMode == Font.DisplayMode.SEE_THROUGH;
		SignTextState.noteDraw(seeThrough, y);

		if (SignTextState.shouldHideText()) {
			beryllium$cancel(cir, ci);
			return;
		}
		if (seeThrough && SignTextState.shouldDropGlow()) {
			beryllium$cancel(cir, ci);
			return;
		}
		if (seeThrough && SignTextState.shouldCancelOutline()) {
			beryllium$cancel(cir, ci);
		}
	}

	@Unique
	private static void beryllium$cancel(CallbackInfoReturnable<Integer> cir, CallbackInfo ci) {
		if (cir != null) {
			cir.setReturnValue(0);
		} else if (ci != null) {
			ci.cancel();
		}
	}
}
