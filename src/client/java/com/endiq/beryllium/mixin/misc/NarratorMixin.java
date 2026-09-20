package com.endiq.beryllium.mixin.misc;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.config.BerylliumConfig;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Suppresses the wall of stack trace the game logs when the narrator cannot start.
 *
 * <p>Narrator support is missing on a great many setups — most Android launchers, headless
 * servers, and any machine without the native speech library — and the game treats it as an
 * error every time. Nothing is broken when the narrator is unavailable: the feature is simply
 * off, and the log line turns into pages of noise that hide the real problems next to it.
 *
 * <p>Only the log call is redirected. The failure itself is still handled by the game, and the
 * narrator is still unavailable, exactly as before.
 */
@Mixin(targets = "com.mojang.text2speech.Narrator")
public interface NarratorMixin {
	@Redirect(method = "getNarrator",
			at = @At(value = "INVOKE", target = "Lorg/slf4j/Logger;error(Ljava/lang/String;Ljava/lang/Throwable;)V"),
			require = 0)
	private static void beryllium$quietNarratorFailure(Logger logger, String message, Throwable cause) {
		if (!beryllium$quiet()) {
			logger.error(message, cause);
		}
	}

	@Redirect(method = "getNarrator",
			at = @At(value = "INVOKE", target = "Lorg/slf4j/Logger;error(Ljava/lang/String;)V"),
			require = 0)
	private static void beryllium$quietNarratorFailure(Logger logger, String message) {
		if (!beryllium$quiet()) {
			logger.error(message);
		}
	}

	private static boolean beryllium$quiet() {
		try {
			BerylliumConfig config = Beryllium.config();
			return config == null || !config.enabled || config.noNarratorError;
		} catch (Throwable t) {
			return true;
		}
	}
}
