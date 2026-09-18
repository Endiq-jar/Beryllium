package com.endiq.beryllium.mixin.tick;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.tick.BerylliumConfigCache;
import com.endiq.beryllium.tick.TickRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hopper throttling. See {@link com.endiq.beryllium.tick.HopperThrottle} for the rule and
 * for what it costs: only hoppers whose contents have not changed across several runs are
 * spaced out, and one interval of extra latency is the worst case when something does
 * arrive.
 *
 * <p>The target is {@code HopperBlockEntity#pushItemsTick} — the per-tick entry point
 * every hopper pays for, including the ones that will move nothing. Descriptor is given
 * in full and {@code require = 0}, so a rename in some future release disables the
 * optimisation instead of breaking the hopper.
 */
@Mixin(HopperBlockEntity.class)
public abstract class HopperTickMixin {

	@Inject(
		method = "pushItemsTick(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;"
			+ "Lnet/minecraft/world/level/block/state/BlockState;"
			+ "Lnet/minecraft/world/level/block/entity/HopperBlockEntity;)V",
		at = @At("HEAD"),
		cancellable = true,
		require = 0
	)
	private static void beryllium$throttleIdleHoppers(
		Level level, BlockPos pos, BlockState state, HopperBlockEntity hopper, CallbackInfo ci
	) {
		if (!BerylliumConfigCache.hopperThrottling()) {
			return;
		}
		if (Beryllium.isHopperOptimizationDeferredToOtherMod()) {
			return;
		}
		if (level == null || level.isClientSide()) {
			return;
		}
		boolean run = TickRuntime.instance().hoppers().shouldRun(
			pos,
			hopper,
			BerylliumConfigCache.hopperThrottleInterval(),
			BerylliumConfigCache.hopperIdleSamples()
		);
		if (!run) {
			ci.cancel();
		}
	}
}
