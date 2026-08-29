package com.endiq.beryllium.mixin.text;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.config.BerylliumConfig;
import net.minecraft.client.gui.Font;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * NOT COMPILE-VERIFIED — same sandbox caveat as the other mixins: the target methods are
 * declared by full string descriptors with {@code require = 0}, so overloads that don't
 * exist on this exact 1.21.4 build silently no-op instead of crashing. All three
 * {@code Font.drawInBatch} overloads that carry a {@code dropShadow} parameter are
 * targeted; if the method set drifted in a decompile, at least the ones that survive
 * still get the shadow suppression.
 *
 * <p>Phase 11 — the text-shadows toggle. {@code textShadowsEnabled: false} in
 * beryllium.json suppresses the {@code dropShadow} argument at the source, before any
 * glyph layout happens, so every text path that renders through {@code Font.drawInBatch}
 * (GUI screens, name tags, signs, tooltips, the F3 text) draws without the duplicate
 * shadow pass. That is one fewer overdraw layer per glyph in text-heavy scenes — the
 * exact trade Beryllium's mobile audience wants: slightly flatter text, measurably
 * cheaper rendering.
 *
 * <p>{@code dropShadow} is the only {@code boolean} parameter of each overload, so
 * {@code argsOnly = true, ordinal = 0} selects it unambiguously regardless of the other
 * parameter types.
 */
@Mixin(Font.class)
public abstract class FontTextShadowMixin {

	@ModifyVariable(
		method = "drawInBatch(Lnet/minecraft/network/chat/Component;FFFFIZLcom/mojang/blaze3d/vertex/Matrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/gui/Font$DisplayMode;II)I",
		at = @At("HEAD"),
		argsOnly = true,
		ordinal = 0,
		require = 0
	)
	private boolean beryllium$suppressComponentShadow(boolean dropShadow) {
		return beryllium$suppressShadow(dropShadow);
	}

	@ModifyVariable(
		method = "drawInBatch(Lnet/minecraft/util/FormattedText;FFFFIZLcom/mojang/blaze3d/vertex/Matrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/gui/Font$DisplayMode;II)I",
		at = @At("HEAD"),
		argsOnly = true,
		ordinal = 0,
		require = 0
	)
	private boolean beryllium$suppressFormattedTextShadow(boolean dropShadow) {
		return beryllium$suppressShadow(dropShadow);
	}

	@ModifyVariable(
		method = "drawInBatch(Ljava/lang/String;FFFFIZLcom/mojang/blaze3d/vertex/Matrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/gui/Font$DisplayMode;II)I",
		at = @At("HEAD"),
		argsOnly = true,
		ordinal = 0,
		require = 0
	)
	private boolean beryllium$suppressStringShadow(boolean dropShadow) {
		return beryllium$suppressShadow(dropShadow);
	}

	@Unique
	private boolean beryllium$suppressShadow(boolean dropShadow) {
		BerylliumConfig config = Beryllium.config();
		if (config == null || !config.enabled || config.textShadowsEnabled) {
			return dropShadow;
		}
		return false;
	}
}
