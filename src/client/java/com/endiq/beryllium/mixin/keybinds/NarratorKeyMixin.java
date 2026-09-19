package com.endiq.beryllium.mixin.keybinds;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.compat.Reflect;
import com.endiq.beryllium.config.BerylliumConfig;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Makes the narrator key rebindable.
 *
 * <p>Vanilla's narrator shortcut is not a key bind at all from the keyboard handler's point of
 * view — the handler compares the pressed key against a hard-coded {@code B}. The binding in the
 * controls screen exists, is saved, and is then ignored. This replaces the hard-coded key with
 * whatever the player actually bound, which is why the shortcut starts working after the change
 * and can be moved off {@code B} entirely.
 *
 * <p>66 is {@code GLFW_KEY_B}. It is written as a literal because GLFW's own constants are not
 * on the compile classpath of every supported release.
 */
@Mixin(targets = "net.minecraft.client.KeyboardHandler")
public abstract class NarratorKeyMixin {
	@ModifyConstant(method = "keyPress", constant = @Constant(intValue = 66), require = 0)
	private int beryllium$useBoundNarratorKey(int original) {
		try {
			BerylliumConfig config = Beryllium.config();
			if (config == null || !config.enabled || !config.remapNarrator) {
				return original;
			}
			int bound = beryllium$boundNarratorKey();
			// -1 or -2 is "not bound": no key press matches, and the shortcut stays off rather
			// than falling back to B behind the player's back.
			return bound <= 0 ? -2 : bound;
		} catch (Throwable t) {
			return original;
		}
	}

	@Unique
	private static int beryllium$boundNarratorKey() {
		try {
			Object options = Reflect.get(Minecraft.getInstance(), "options");
			Object narrator = Reflect.get(options, "keyNarrator");
			Object key = Reflect.get(narrator, "key");
			Object value = Reflect.call(key, "getValue");
			if (value instanceof Integer code) {
				// -1 means the key is unbound.
				return code == -1 ? -2 : code;
			}
		} catch (Throwable ignored) {
			// No binding found: leave vanilla's key alone.
		}
		return -1;
	}
}
