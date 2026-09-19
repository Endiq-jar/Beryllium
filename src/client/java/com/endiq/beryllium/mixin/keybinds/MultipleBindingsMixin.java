package com.endiq.beryllium.mixin.keybinds;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.compat.Reflect;
import com.endiq.beryllium.config.BerylliumConfig;
import com.endiq.beryllium.keybinds.KeyBindingRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets one key drive several binds.
 *
 * <p>Vanilla keeps one bind per key, so binding a second action to a key either moves the first
 * one or leaves one of the two dead. The fix is at the dispatch point: the game presses a key,
 * looks up the single bind it maps to, and clicks that one. Here the lookup becomes a broadcast —
 * every bind on that key is clicked, in the order they were declared, and the game's own handling
 * is skipped so nothing fires twice.
 *
 * <p>Whether any of this is needed depends on the release: the keyboard dispatch was rewritten
 * late in the supported range, and on those releases the game handles shared keys itself. The
 * hooks are matched by name and are allowed to be absent, so on those releases this mixin simply
 * does not apply.
 */
@Mixin(targets = "net.minecraft.client.KeyMapping")
public abstract class MultipleBindingsMixin {
	@Inject(method = "click(Lcom/mojang/blaze3d/platform/InputConstants$Key;)V",
			at = @At("HEAD"), cancellable = true, require = 0, order = 1001)
	private static void beryllium$clickEveryBinding(@Coerce Object key, CallbackInfo ci) {
		if (KeyBindingRegistry.click(key)) {
			ci.cancel();
		}
	}

	@Inject(method = "set(Lcom/mojang/blaze3d/platform/InputConstants$Key;Z)V",
			at = @At("HEAD"), cancellable = true, require = 0, order = 1001)
	private static void beryllium$pressEveryBinding(@Coerce Object key, boolean down, CallbackInfo ci) {
		if (KeyBindingRegistry.set(key, down)) {
			ci.cancel();
		}
	}

	@Inject(method = "resetMapping", at = @At("HEAD"), require = 0)
	private static void beryllium$rebuildIndex(CallbackInfo ci) {
		KeyBindingRegistry.invalidate();
	}

	@Inject(method = "<init>(Ljava/lang/String;Lcom/mojang/blaze3d/platform/InputConstants$Type;ILjava/lang/String;)V",
			at = @At("TAIL"), require = 0)
	private void beryllium$indexNewBinding(CallbackInfo ci) {
		try {
			BerylliumConfig config = Beryllium.config();
			if (config != null && config.enabled && config.multipleBindingsPerKey) {
				KeyBindingRegistry.add(this);
			}
		} catch (Throwable t) {
			// An unindexed binding behaves exactly like vanilla's.
		}
	}
}
