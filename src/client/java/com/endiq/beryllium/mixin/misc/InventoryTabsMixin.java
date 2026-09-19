package com.endiq.beryllium.mixin.misc;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.compat.Reflect;
import com.endiq.beryllium.config.BerylliumConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Switches creative inventory tabs when the mouse button goes down, not when it comes back up.
 *
 * <p>Vanilla checks which tab the cursor was over when the button is released, so a click that
 * moves even a pixel between press and release lands on the wrong tab — and a tab "click" that
 * was really a drag selects something the player never aimed at. Doing the check on press makes
 * the tab list behave like every other tabbed interface.
 *
 * <p>The release-time switch is muted so the tab is not selected twice.
 */
@Mixin(targets = "net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen")
public abstract class InventoryTabsMixin {
	@Redirect(
		method = "mouseClicked",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/screens/inventory/CreativeModeInventoryScreen;checkTabClicked(Lnet/minecraft/world/item/CreativeModeTab;DD)Z"),
		require = 0
	)
	private boolean beryllium$switchOnPress(@Coerce Object screen, @Coerce Object tab, double mouseX, double mouseY) {
		try {
			BerylliumConfig config = Beryllium.config();
			if (config == null || !config.enabled || !config.fixInventoryTabSwitching) {
				// Vanilla's answer, obtained the same way vanilla would have.
				return Boolean.TRUE.equals(Reflect.call(screen, "checkTabClicked", tab, mouseX, mouseY));
			}
			if (Boolean.TRUE.equals(Reflect.call(screen, "checkTabClicked", tab, mouseX, mouseY))) {
				beryllium$select(screen, tab);
				return true;
			}
			return false;
		} catch (Throwable t) {
			return false;
		}
	}

	@Redirect(
		method = "mouseReleased",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/screens/inventory/CreativeModeInventoryScreen;selectTab(Lnet/minecraft/world/item/CreativeModeTab;)V"),
		require = 0
	)
	private void beryllium$suppressReleaseSwitch(@Coerce Object screen, @Coerce Object tab) {
		try {
			BerylliumConfig config = Beryllium.config();
			if (config == null || !config.enabled || !config.fixInventoryTabSwitching) {
				beryllium$select(screen, tab);
			}
			// With the fix on, the tab was already selected on press.
		} catch (Throwable t) {
			// Nothing selected twice.
		}
	}

	@Unique
	private static void beryllium$select(Object screen, Object tab) {
		Reflect.call(screen, "selectTab", tab);
	}
}
