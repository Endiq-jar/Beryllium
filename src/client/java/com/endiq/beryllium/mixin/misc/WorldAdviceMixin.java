package com.endiq.beryllium.mixin.misc;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.config.BerylliumConfig;
import com.mojang.serialization.Lifecycle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skips the "this world uses experimental settings" confirmation when opening a world.
 *
 * <p>The trap here is that the confirmation <em>is</em> the code path that opens the world: the
 * method builds the prompt and passes the loading step to it as a callback, so simply dropping
 * the prompt would leave a player clicking a world and nothing happening. The loading step is
 * the method's {@code Runnable} argument, so it is run directly here — the world opens exactly
 * as it would have after pressing the button in the prompt.
 *
 * <p>That argument list is the same on every covered release, which is why this hook can name
 * every parameter instead of the screen call being redirected: the screen switch itself was
 * renamed on the newest releases, and nothing here touches it.
 *
 * <p>Every condition is checked before anything is skipped. If the setting is off, if the
 * loading step is missing, or if anything at all goes wrong, the method runs as the game wrote
 * it and the prompt is shown — the failure direction is "still asks", never "does not load".
 *
 * <p>This moves the warning rather than removing the risk: the world is converted exactly as it
 * would have been after pressing the button in the prompt. Back up a world before opening it
 * with a newer game version.
 */
@Mixin(targets = "net.minecraft.client.gui.screens.worldselection.WorldOpenFlows")
public abstract class WorldAdviceMixin {

	@Inject(method = "confirmWorldCreation", at = @At("HEAD"), cancellable = true, require = 0)
	private static void beryllium$skipExperimentalWarning(Minecraft minecraft, CreateWorldScreen parent,
			Lifecycle lifecycle, Runnable openWorld, boolean skipWarning, CallbackInfo ci) {
		try {
			BerylliumConfig config = Beryllium.config();
			if (config == null || !config.enabled || !config.disableWorldAdvice || openWorld == null) {
				return;
			}
			// No prompt: open the world the prompt would have opened.
			openWorld.run();
			ci.cancel();
		} catch (Throwable t) {
			// Fall through to vanilla, which asks.
		}
	}
}
