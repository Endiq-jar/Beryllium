package com.endiq.beryllium.mixin.render;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.compat.Reflect;
import com.endiq.beryllium.config.BerylliumConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/**
 * Shrinks an oversized title or subtitle so it stays on the screen.
 *
 * <p>The game draws the title at four times the normal text size and the subtitle at two, and
 * does not care how wide the result is: a long title from a server, a long item name in the
 * title, or a translated string in a language that needs more room simply runs off both edges,
 * where the player cannot read the ends of it. This scales the title down just enough to fit,
 * and leaves every title that already fits exactly as it was.
 *
 * <p>Applied to the two scaling calls in the title rendering, found by ordinal, so the rest of
 * the HUD's scaling is untouched. Both the classic pose-stack call and the newer matrix call are
 * targeted; whichever this release uses is the one that matches, and a release with neither
 * keeps the game's own size.
 */
@Mixin(targets = "net.minecraft.client.gui.Hud")
public abstract class TitleSizeHudMixin {
	@ModifyArgs(method = { "render", "renderTitle", "extractTitle" },
			at = @At(value = "INVOKE",
					target = "Lcom/mojang/blaze3d/vertex/PoseStack;scale(FFF)V", ordinal = 0),
			require = 0)
	private void beryllium$fitTitle(Args args) {
		beryllium$fit(args, 4.0F, "title");
	}

	@ModifyArgs(method = { "render", "renderTitle", "extractTitle" },
			at = @At(value = "INVOKE",
					target = "Lcom/mojang/blaze3d/vertex/PoseStack;scale(FFF)V", ordinal = 1),
			require = 0)
	private void beryllium$fitSubtitle(Args args) {
		beryllium$fit(args, 2.0F, "subtitle");
	}

	@ModifyArgs(method = { "render", "renderTitle", "extractTitle" },
			at = @At(value = "INVOKE",
					target = "Lorg/joml/Matrix3x2fStack;scale(FF)Lorg/joml/Matrix3x2f;", ordinal = 0),
			require = 0)
	private void beryllium$fitTitleMatrix(Args args) {
		beryllium$fit(args, 4.0F, "title");
	}

	@ModifyArgs(method = { "render", "renderTitle", "extractTitle" },
			at = @At(value = "INVOKE",
					target = "Lorg/joml/Matrix3x2fStack;scale(FF)Lorg/joml/Matrix3x2f;", ordinal = 1),
			require = 0)
	private void beryllium$fitSubtitleMatrix(Args args) {
		beryllium$fit(args, 2.0F, "subtitle");
	}

	@Unique
	private void beryllium$fit(Args args, float baseScale, String which) {
		try {
			BerylliumConfig config = Beryllium.config();
			if (config == null || !config.enabled || !config.fixTitleSize) {
				return;
			}
			Component text = (Component) Reflect.get(this, which);
			if (text == null) {
				return;
			}
			Minecraft minecraft = Minecraft.getInstance();
			if (minecraft == null) {
				return;
			}
			Object font = com.endiq.beryllium.compat.Reflect.get(minecraft, "font");
			if (font == null) {
				return;
			}
			int screenWidth = minecraft.getWindow().getGuiScaledWidth();
			int allowed = (int) Math.max(40.0, screenWidth * Math.min(1.0, Math.max(0.1, config.maxTitleWidthFraction)));
			int width = -1;
			try { Object w = font.getClass().getMethod("width", Component.class).invoke(font, text); width = ((Number) w).intValue(); } catch (Throwable e) { try { Object w = font.getClass().getMethod("width", String.class).invoke(font, text.getString()); width = ((Number) w).intValue(); } catch (Throwable ex) { return; } }
			if (width <= 0 || width * baseScale <= allowed) {
				return;
			}
			float scale = (float) allowed / width;
			for (int index = 0; index < args.size(); index++) {
				args.set(index, scale);
			}
		} catch (Throwable t) {
			// The game's own size stands.
		}
	}
}
