package com.endiq.beryllium.mixin.misc;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.config.BerylliumConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Local;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Skips the "this world uses experimental settings" confirmation when opening a world.
 *
 * <p>The trap here is that the confirmation <em>is</em> the code path that opens the world: the
 * method builds the prompt and passes the loading step to it as a callback, so cancelling the
 * method would leave a player clicking a world and nothing happening. Instead the screen is
 * intercepted where it would be shown: the loading step runs directly and the prompt never
 * appears.
 *
 * <p>The loading step is picked up as the method's {@code Runnable} argument. If a release
 * changed that argument's shape, the redirect does not apply, the prompt is shown, and the world
 * opens exactly as vanilla opens it — the failure direction is "still asks", never "does not
 * load".
 *
 * <p>This moves the warning rather than removing the risk: the world is converted exactly as it
 * would have been after pressing the button in the prompt. Back up a world before opening it
 * with a newer game version.
 */
@Mixin(targets = "net.minecraft.client.gui.screens.worldselection.WorldOpenFlows")
public abstract class WorldAdviceMixin {
	@Redirect(
		method = "confirmWorldCreation",
		at = @At(value = "INVOKE",
				target = "Lnet/minecraft/client/Minecraft;setScreen(Lnet/minecraft/client/gui/screens/Screen;)V",
				ordinal = 0),
		require = 0
	)
	private static void beryllium$skipExperimentalWarning(Minecraft minecraft, Screen screen,
			@Local(argsOnly = true) Runnable openWorld) {
		try {
			BerylliumConfig config = Beryllium.config();
			if (config != null && config.enabled && config.disableWorldAdvice && openWorld != null) {
				// No prompt: open the world the prompt would have opened.
				openWorld.run();
				return;
			}
		} catch (Throwable t) {
			// Fall through to vanilla, which asks.
		}
		minecraft.setScreen(screen);
	}
}
