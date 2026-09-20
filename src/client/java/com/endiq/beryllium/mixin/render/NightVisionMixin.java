package com.endiq.beryllium.mixin.render;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.compat.Reflect;
import com.endiq.beryllium.config.BerylliumConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Removes the night-vision flicker in the last seconds of the effect.
 *
 * <p>Vanilla fades night vision in and out with a sine while it is running out, which is
 * intended as a warning and is indistinguishable from a broken shader setup. This keeps the
 * warning — the brightness still drops as the effect ends — but takes the oscillation out, so
 * the screen dims once instead of strobing.
 *
 * <p>The entity and the effect are reached without naming their types, so a release that
 * changed how effects are looked up falls back to vanilla's flicker rather than failing.
 */
@Mixin(targets = "net.minecraft.client.renderer.GameRenderer")
public abstract class NightVisionMixin {
	@Inject(method = "getNightVisionScale", at = @At("HEAD"), cancellable = true, require = 0)
	private static void beryllium$steadyNightVision(@Coerce Object entity, float partialTick,
			CallbackInfoReturnable<Float> cir) {
		try {
			BerylliumConfig config = Beryllium.config();
			if (config == null || !config.enabled || !config.noNightVisionFlicker || entity == null) {
				return;
			}
			Object effectType = Reflect.getStatic(Reflect.type("net.minecraft.world.effect.MobEffects"), "NIGHT_VISION");
			if (effectType == null) {
				return;
			}
			Object effect = Reflect.call(entity, "getEffect", effectType);
			if (effect == null) {
				return;
			}
			Object duration = Reflect.call(effect, "getDuration");
			if (!(duration instanceof Integer ticks)) {
				return;
			}
			if (ticks == -1) {
				cir.setReturnValue(1.0F);
				return;
			}
			// A single smooth ramp over the last second, instead of vanilla's oscillation.
			cir.setReturnValue(Math.min((ticks - partialTick) / 20.0F, 1.0F));
		} catch (Throwable t) {
			// Vanilla's flicker stays.
		}
	}
}
