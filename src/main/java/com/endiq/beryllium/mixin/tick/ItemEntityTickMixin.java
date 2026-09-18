package com.endiq.beryllium.mixin.tick;

import com.endiq.beryllium.tick.BerylliumConfigCache;
import com.endiq.beryllium.tick.ItemEntityThrottle;
import com.endiq.beryllium.tick.VanillaBridges;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Item entity throttling: an item lying still on the ground does not need a full tick
 * every single tick.
 *
 * <p>Ground items are one of the classic lag sources — a mob farm, a broken sorting
 * system or a creeper hole can leave hundreds of them sitting in one place, and vanilla
 * pays for every one of them every tick (fluid checks, block checks, merge searches,
 * despawn counting). Beryllium records where the item was the last time it was allowed to
 * tick; if it has not moved since then, it is ticked once every
 * {@code itemEntityThrottleInterval} ticks instead.
 *
 * <p>Items that are burning, in a fluid or freshly spawned always tick at full rate: those
 * are the cases where a skipped tick would be visible (an item burning up late, or
 * floating away late).
 *
 * <p>Honest consequence: because the despawn counter only advances on ticks that run, a
 * stationary item's five-minute despawn timer stretches by the throttle interval. Items
 * are never permanent — they simply live longer while they are being ignored. Set
 * {@code itemEntityThrottleInterval} to 1 to turn this off.
 */
@Mixin(ItemEntity.class)
public abstract class ItemEntityTickMixin {
	@Unique
	private double beryllium$lastX;
	@Unique
	private double beryllium$lastY;
	@Unique
	private double beryllium$lastZ;
	@Unique
	private boolean beryllium$hasLastPosition;
	@Unique
	private int beryllium$phase = -1;

	@Inject(method = "tick()V", at = @At("HEAD"), cancellable = true, require = 0)
	private void beryllium$throttleStationary(CallbackInfo ci) {
		if (!BerylliumConfigCache.itemEntityThrottling()) {
			return;
		}

		ItemEntity self = (ItemEntity) (Object) this;
		Level world = VanillaBridges.entityLevel(self);
		if (world == null || world.isClientSide()) {
			return;
		}

		int interval = ItemEntityThrottle.effectiveInterval(BerylliumConfigCache.itemEntityThrottleInterval());
		if (interval <= 1) {
			return;
		}

		boolean inFluid = self.isInWater() || self.isInLava();
		boolean burning = self.getRemainingFireTicks() > 0;
		boolean justSpawned = self.tickCount < 20;
		if (ItemEntityThrottle.mustTickAlways(inFluid, burning, justSpawned)) {
			beryllium$remember(self);
			return;
		}

		if (!beryllium$hasLastPosition || beryllium$moved(self)) {
			beryllium$remember(self);
			return;
		}

		ItemEntityThrottle.noteStationary();
		if (Math.floorMod(self.tickCount + beryllium$phase(), interval) == 0) {
			beryllium$remember(self);
			return;
		}

		ItemEntityThrottle.noteThrottled();
		ci.cancel();
	}

	@Unique
	private void beryllium$remember(ItemEntity self) {
		beryllium$lastX = self.getX();
		beryllium$lastY = self.getY();
		beryllium$lastZ = self.getZ();
		beryllium$hasLastPosition = true;
	}

	@Unique
	private boolean beryllium$moved(ItemEntity self) {
		double dx = self.getX() - beryllium$lastX;
		double dy = self.getY() - beryllium$lastY;
		double dz = self.getZ() - beryllium$lastZ;
		double epsilon = ItemEntityThrottle.STATIONARY_EPSILON;
		return dx * dx + dy * dy + dz * dz > epsilon * epsilon;
	}

	/** Staggered so a whole pile of items does not wake up on the same tick. */
	@Unique
	private int beryllium$phase() {
		if (beryllium$phase < 0) {
			beryllium$phase = Math.floorMod(((ItemEntity) (Object) this).getId(), 997);
		}
		return beryllium$phase;
	}
}
