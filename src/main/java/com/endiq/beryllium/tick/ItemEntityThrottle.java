package com.endiq.beryllium.tick;

/**
 * Policy + telemetry for Beryllium's item entity throttling.
 *
 * <p>The per-entity state itself lives on the entity (unique fields injected by
 * {@code ItemEntityTickMixin}), because that is the only place it can be kept without a
 * weak map lookup on every item, every tick. This class holds the decisions that do not
 * depend on a particular entity and the counters that make the feature observable.
 */
public final class ItemEntityThrottle {
	/** Anything that moves less than this (blocks/tick) counts as stationary. */
	public static final double STATIONARY_EPSILON = 0.0015;

	private static long throttledTicks = 0;
	private static long stationaryTicks = 0;

	private ItemEntityThrottle() {
	}

	/**
	 * @param configuredInterval ticks between full ticks of a stationary item (1 = off)
	 * @return the interval to use, widened by the tick governor when the server is behind
	 */
	public static int effectiveInterval(int configuredInterval) {
		if (configuredInterval <= 1) {
			return 1;
		}
		return TickTimeGovernor.instance().scaleInterval(configuredInterval);
	}

	/**
	 * @return true if an item that is burning or in a fluid must always tick at full rate
	 */
	public static boolean mustTickAlways(boolean inFluid, boolean burning, boolean justSpawned) {
		return inFluid || burning || justSpawned;
	}

	public static void noteThrottled() {
		throttledTicks++;
	}

	public static void noteStationary() {
		stationaryTicks++;
	}

	public static long throttledTicks() {
		return throttledTicks;
	}

	public static long stationaryTicks() {
		return stationaryTicks;
	}
}
