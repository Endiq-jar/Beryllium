package com.endiq.beryllium.text;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.config.BerylliumConfig;
import com.endiq.beryllium.culling.RenderDistanceSync;
import com.endiq.beryllium.culling.VisibilityCulling;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Per-sign-render state for Beryllium's sign text optimisation.
 *
 * <p>Vanilla's sign text is drawn through {@code Font#drawInBatch}, the same call every
 * other piece of text in the game uses, so the only way to make sign text cheap is to know
 * that we are inside a sign's text pass when the call happens. This class is that context.
 * It is set by the sign renderer mixins (which have the block entity and therefore the
 * world position), read by the font mixin (which has the draw call), and reset every frame
 * so a missed "end" can never leak into the next frame.
 *
 * <p>The decisions it exposes:
 * <ul>
 *   <li>{@link #shouldHideText()} — text is beyond {@code signTextCullDistance}: the sign
 *       board still renders, the text does not. Text is illegible at that range anyway, and
 *       each line is a full glyph pass with its own transform.</li>
 *   <li>{@link #isTextOutOfView()} — the sign is off-screen; the board renderer is already
 *       handled by Beryllium's block entity culling, the text draws are not.</li>
 *   <li>{@link #shouldDropGlow()} — beyond {@code signTextGlowCullDistance} the glowing
 *       outline is turned off entirely (glowing text costs many extra passes per line).</li>
 *   <li>{@link #shouldCancelOutline()} — the multi-direction outline vanilla draws behind
 *       glowing text is reduced to a single shadow-like pass (NORMAL mode) or removed
 *       (FAST mode).</li>
 * </ul>
 *
 * <p>Outline reduction has one learned safety rule: if a glowing sign pass is ever observed
 * that contains <em>only</em> see-through draws — meaning vanilla has no separate main pass
 * to fall back on — Beryllium stops cancelling them, because in that case the outline
 * passes are the text. That check runs once and only ever protects the player's signs.
 */
public final class SignTextState {
	/** Vanilla signs render four lines; NORMAL mode keeps one outline pass per line. */
	private static final int LINES_PER_SIGN = 4;

	private static boolean inSignText = false;
	private static boolean hideText = false;
	private static boolean outOfView = false;
	private static boolean glowing = false;
	private static boolean dropGlow = false;

	private static int seeThroughSeen = 0;
	private static int mainPassSeen = 0;
	private static boolean outlineDroppingUnsafe = false;

	private SignTextState() {
	}

	/** Called at the head of a sign/hanging-sign text render. */
	public static void begin(BlockEntity blockEntity) {
		inSignText = true;
		hideText = false;
		outOfView = false;
		glowing = false;
		dropGlow = false;
		seeThroughSeen = 0;
		mainPassSeen = 0;

		BerylliumConfig config = Beryllium.config();
		if (config == null || !config.enabled || !config.signOptimization || blockEntity == null) {
			return;
		}

		BlockPos pos = blockEntity.getBlockPos();
		double distanceSq = VisibilityCulling.distanceSqToCamera(pos);
		if (distanceSq < 0.0) {
			return;
		}

		double textRange = RenderDistanceSync.effectiveCullDistance(
			config.signTextCullDistance, config.cullRangeSyncWithRenderDistance);
		if (textRange > 0.0 && distanceSq > textRange * textRange) {
			hideText = true;
		}

		if (config.signTextHideOutOfView && !VisibilityCulling.isBlockVisible(pos, 0.75)) {
			outOfView = true;
		}

		double glowRange = RenderDistanceSync.effectiveCullDistance(
			config.signTextGlowCullDistance, config.cullRangeSyncWithRenderDistance);
		if (glowRange > 0.0 && distanceSq > glowRange * glowRange) {
			dropGlow = true;
		}
	}

	/** Called when the sign text pass ends. Also applies the outline safety learning. */
	public static void end() {
		if (inSignText && glowing && seeThroughSeen > 0 && mainPassSeen == 0) {
			// Vanilla drew the glowing text only through see-through passes: those ARE the
			// text, so dropping them would erase the sign. Never do that again.
			outlineDroppingUnsafe = true;
		}
		inSignText = false;
		hideText = false;
		outOfView = false;
		glowing = false;
		dropGlow = false;
	}

	/**
	 * Records that a text draw happened inside the current sign text pass.
	 *
	 * @param seeThrough true when the draw is a see-through pass (the outline/glow layer)
	 */
	public static void noteDraw(boolean seeThrough) {
		if (!inSignText) {
			return;
		}
		if (seeThrough) {
			seeThroughSeen++;
		} else {
			mainPassSeen++;
		}
	}

	/** Marks the current sign text as glowing (vanilla's outline/see-through path). */
	public static void setGlowing(boolean value) {
		glowing = value;
	}

	public static void onFrame() {
		inSignText = false;
		hideText = false;
		outOfView = false;
		glowing = false;
		dropGlow = false;
	}

	public static boolean isInSignText() {
		return inSignText;
	}

	public static boolean isTextHidden() {
		return inSignText && (hideText || outOfView);
	}

	public static boolean shouldHideText() {
		return isTextHidden();
	}

	public static boolean isTextOutOfView() {
		return inSignText && outOfView;
	}

	/** True when glowing should be disabled entirely for this sign (distance). */
	public static boolean shouldDropGlow() {
		return inSignText && dropGlow;
	}

	/**
	 * Decides whether to cancel one outline (see-through) draw of glowing sign text.
	 *
	 * @return true if this draw should be skipped
	 */
	public static boolean shouldCancelOutline() {
		if (!inSignText || !glowing) {
			return false;
		}
		BerylliumConfig config = Beryllium.config();
		if (config == null || !config.enabled || !config.signOptimization) {
			return false;
		}
		if (!config.signTextGlowOptimization || !config.signTextHideGlowOutline) {
			return false;
		}
		if (outlineDroppingUnsafe) {
			return false;
		}
		if (shouldDropGlow()) {
			// Glow is off for this sign anyway; the glow mixin handles that, and there is
			// no point cancelling passes that are already not being drawn.
			return false;
		}
		if (OutlineMode.of(config.signTextOutlineMode) == OutlineMode.FAST) {
			return true;
		}
		return seeThroughSeen >= LINES_PER_SIGN;
	}

	public static boolean isOutlineDroppingUnsafe() {
		return outlineDroppingUnsafe;
	}

	public static int seeThroughPasses() {
		return seeThroughSeen;
	}

	public static int mainPasses() {
		return mainPassSeen;
	}

	/** How the glowing-text outline is handled. */
	public enum OutlineMode {
		/** Keep one outline pass per line — reads as a shadow, costs one draw, not eight. */
		NORMAL,
		/** Drop every outline pass; glowing text renders flat. */
		FAST;

		public static OutlineMode of(String configured) {
			if (configured != null && configured.trim().equalsIgnoreCase("NORMAL")) {
				return NORMAL;
			}
			return FAST;
		}
	}
}
