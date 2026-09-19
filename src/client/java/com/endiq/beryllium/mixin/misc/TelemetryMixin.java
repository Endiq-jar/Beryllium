package com.endiq.beryllium.mixin.misc;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.compat.Reflect;
import com.endiq.beryllium.config.BerylliumConfig;
import net.minecraft.client.telemetry.TelemetryEventSender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Turns telemetry off by handing the game the "disabled" event sender.
 *
 * <p>Every telemetry event is sent through a {@link TelemetryEventSender}, and the game already
 * ships one that does nothing for the case where the player said no. This hands that one out
 * instead of a real sender, so nothing is collected, queued or sent — including the events the
 * game records by itself.
 *
 * <p>There are two senders to replace, and releases disagree about how they are built: one for
 * events sent outside a world session, one for the events of the world session itself. Each is
 * hooked where it is created, and each hook is allowed not to match ({@code require = 0}), so a
 * release that renamed one of them keeps the others instead of failing to load. Because the
 * hook is on the factory method itself, it only ever replaces the sender — it never has to
 * reproduce the original.
 *
 * <p>The disabled sender is read off the sender interface by name rather than referenced as a
 * constant, so a release that does not have that constant keeps the game's own behaviour
 * instead of failing to compile.
 */
@Mixin(targets = "net.minecraft.client.telemetry.ClientTelemetryManager")
public abstract class TelemetryMixin {

	/** The sender used for events sent while no world session is open. */
	@Inject(method = "getOutsideSessionSender", at = @At("HEAD"), cancellable = true, require = 0)
	private void beryllium$disableOutsideSessionTelemetry(CallbackInfoReturnable<TelemetryEventSender> cir) {
		beryllium$disable(cir);
	}

	/** The sender the world session manager is given, on the newer releases. */
	@Inject(method = "createEventSender", at = @At("HEAD"), cancellable = true, require = 0)
	private void beryllium$disableSessionTelemetry(CallbackInfoReturnable<TelemetryEventSender> cir) {
		beryllium$disable(cir);
	}

	/** The same, under the name the older releases build it with. */
	@Inject(method = "createWorldSessionEventSender", at = @At("HEAD"), cancellable = true, require = 0)
	private void beryllium$disableSessionTelemetryLegacy(CallbackInfoReturnable<TelemetryEventSender> cir) {
		beryllium$disable(cir);
	}

	private void beryllium$disable(CallbackInfoReturnable<TelemetryEventSender> cir) {
		try {
			BerylliumConfig config = Beryllium.config();
			if (config == null || !config.enabled || !config.noTelemetry) {
				return;
			}
			Object disabled = Reflect.getStatic(TelemetryEventSender.class, "DISABLED");
			if (disabled instanceof TelemetryEventSender sender) {
				cir.setReturnValue(sender);
			}
		} catch (Throwable t) {
			// Telemetry stays exactly as the game's own options say.
		}
	}
}
