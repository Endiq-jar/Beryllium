package com.endiq.beryllium.mixin.keybinds;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.compat.Reflect;
import com.endiq.beryllium.config.BerylliumConfig;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Locale;

/**
 * Stops the controls screen from flagging keys that are meant to be shared.
 *
 * <p>The warning is a UI feature: the controls list asks each binding whether it is unbound and
 * whether another binding uses the same key, and marks the row when the answer says the key is
 * taken. Both questions are answered here for the two cases where sharing is deliberate — a
 * binding in the creative category, and two bindings of the same category that are both still on
 * their default key. Nothing else is touched: a genuine collision between a player's own binds
 * still shows the warning exactly as it always did.
 *
 * <p>The hooks are on the list row, not on {@code KeyMapping} itself, so the game's own key
 * handling is untouched — this changes what the screen prints and nothing else. Both redirects
 * are allowed not to match ({@code require = 0}), so a release whose list code was reshaped keeps
 * vanilla's warning instead of failing to load.
 */
@Mixin(targets = "net.minecraft.client.gui.screens.controls.KeyBindsList$KeyEntry")
public abstract class KeyConflictWarningMixin {

	/** Marks a creative-category binding as having nothing to warn about. */
	@Redirect(method = "refreshEntry", require = 0,
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/KeyMapping;isUnbound()Z"))
	private boolean beryllium$creativeKeyCountsAsUnbound(KeyMapping mapping) {
		if (beryllium$creative(mapping)) {
			return true;
		}
		return mapping.isUnbound();
	}

	/** Answers "these are the same key" with no only for collisions that are deliberate. */
	@Redirect(method = "refreshEntry", require = 0,
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/KeyMapping;same(Lnet/minecraft/client/KeyMapping;)Z"))
	private boolean beryllium$hideDeliberateConflicts(KeyMapping mapping, KeyMapping other) {
		if (!mapping.same(other)) {
			return false;
		}
		if (beryllium$creative(mapping) || beryllium$creative(other)) {
			return false;
		}
		// Two bindings of one category that are both untouched: the mod's own defaults, not a
		// mistake the player made.
		return !(beryllium$sameCategory(mapping, other) && beryllium$isDefault(mapping)
				&& beryllium$isDefault(other));
	}

	private static boolean beryllium$creative(KeyMapping mapping) {
		if (!beryllium$enabled()) {
			return false;
		}
		try {
			Object category = Reflect.call(mapping, "getCategory");
			if (category == null) {
				return false;
			}
			String name = category instanceof Enum<?> constant ? constant.name() : String.valueOf(category);
			return name.toUpperCase(Locale.ROOT).contains("CREATIVE");
		} catch (Throwable t) {
			return false;
		}
	}

	private static boolean beryllium$sameCategory(KeyMapping one, KeyMapping two) {
		try {
			Object first = Reflect.call(one, "getCategory");
			Object second = Reflect.call(two, "getCategory");
			return first != null && first.equals(second);
		} catch (Throwable t) {
			return false;
		}
	}

	private static boolean beryllium$isDefault(KeyMapping mapping) {
		try {
			return Reflect.asBoolean(Reflect.call(mapping, "isDefault"));
		} catch (Throwable t) {
			return false;
		}
	}

	private static boolean beryllium$enabled() {
		try {
			BerylliumConfig config = Beryllium.config();
			return config != null && config.enabled && config.noReusedModifierKeyWarning;
		} catch (Throwable t) {
			return false;
		}
	}
}
