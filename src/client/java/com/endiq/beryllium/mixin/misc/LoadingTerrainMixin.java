package com.endiq.beryllium.mixin.misc;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.config.BerylliumConfig;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;

/**
 * Skips the "Loading terrain" and "Reconfiguring" screens when changing world or dimension.
 *
 * <p>Identified by screen class name rather than by the code that opens them, because those call
 * sites were rewritten inside the supported range while the screens themselves kept their names.
 * Only these specific screens are suppressed, and the player stays on whatever was on screen
 * before instead of watching a "Loading terrain" bar.
 *
 * <p>Two screens that look like they belong here deliberately are not touched. The progress
 * screen also covers saving and leaving a world. The server-reconfiguration screen — the one
 * shown when a server changes your settings mid-connection — is what keeps that connection
 * ticking while the server is not reading from it, and suppressing it can leave a player stuck
 * before they have even joined. Both keep vanilla's behaviour; the disconnect, save and error
 * screens are unaffected either way, and clearing the screen is never suppressed.
 */
@Mixin(targets = "net.minecraft.client.Minecraft")
public abstract class LoadingTerrainMixin {
	private static final Set<String> BERYLLIUM_LOADING_SCREENS = Set.of(
			"net.minecraft.client.gui.screens.ReceivingLevelScreen",
			"net.minecraft.client.gui.screens.LevelLoadingScreen",
			"net.minecraft.client.gui.screens.LevelLoadingScreen$1"
	);

	@Inject(method = "setScreen", at = @At("HEAD"), cancellable = true, require = 0)
	private void beryllium$skipLoadingScreens(Screen screen, CallbackInfo ci) {
		try {
			BerylliumConfig config = Beryllium.config();
			if (config == null || !config.enabled || !config.disableLoadingTerrain || screen == null) {
				return;
			}
			if (BERYLLIUM_LOADING_SCREENS.contains(screen.getClass().getName())) {
				ci.cancel();
			}
		} catch (Throwable t) {
			// Showing the screen is what vanilla does.
		}
	}
}
