package com.endiq.beryllium.tick;

import com.endiq.beryllium.util.BerylliumLog;

/**
 * Measures how long the *server* tick actually takes and turns that into a single
 * 0..1 "load factor" that every adaptive throttle in Beryllium reads.
 *
 * <p>This is the piece behind the "improved TPS / stable tick times with a large number
 * of entities" behaviour, and it works by reducing work rather than by moving it: when
 * tick time climbs, Beryllium's throttles (hopper checks, item entity ticks, particle
 * distance, entity render distance) scale up automatically, and when the server is
 * healthy they scale back down to their configured baseline. Nothing is skipped
 * permanently and no gameplay state is dropped — work is spaced out, not discarded.
 *
 * <p>Why an EMA instead of a hard cap: Minecraft's server thread cannot be "budgeted"
 * the way the render thread can. A tick either completes or the server starts running
 * ticks back-to-back to catch up, which is exactly the stutter players feel. So the
 * governor watches the trend and leans on the throttles that are cheapest to lean on.
 *
 * <p>All state is on the server thread; only the derived {@code loadFactor} is read from
 * other threads, so it is {@code volatile}.
 */
public final class TickTimeGovernor {
	private static final TickTimeGovernor INSTANCE = new TickTimeGovernor();

	/** Below this many measured ticks the EMA is still settling and must not drive policy. */
	private static final int WARMUP_TICKS = 40;

	private double targetTickMillis = 45.0;
	private double maxScale = 4.0;
	private boolean enabled = true;

	private long tickStartNanos = 0;
	private boolean inTick = false;

	private double emaTickMillis = 0.0;
	private double worstTickMillis = 0.0;
	private long measuredTicks = 0;
	private long overloadedTicks = 0;
	private long overloadStreak = 0;
	private boolean overloadLogged = false;

	private volatile double loadFactor = 0.0;

	private TickTimeGovernor() {
	}

	public static TickTimeGovernor instance() {
		return INSTANCE;
	}

	/**
	 * @param enabled            master switch for adaptive throttling
	 * @param targetTickMillis   tick time considered "healthy" (vanilla's budget is 50 ms)
	 * @param maxScale           upper bound of the throttle multiplier under full load
	 */
	public synchronized void configure(boolean enabled, double targetTickMillis, double maxScale) {
		this.enabled = enabled;
		this.targetTickMillis = Math.max(5.0, targetTickMillis);
		this.maxScale = Math.max(1.0, maxScale);
	}

	/** Called by the server-tick mixin before vanilla's tick body runs. */
	public void beginTick() {
		if (!enabled || inTick) {
			return;
		}
		inTick = true;
		tickStartNanos = System.nanoTime();
	}

	/**
	 * Called by the server-tick mixin after vanilla's tick body returns.
	 *
	 * @return true if this call actually closed a tick. The mixin targets two possible
	 *         names for the server tick method, and on a release that declares both the
	 *         inner one closes first; the outer call then reports false so per-tick state
	 *         is advanced exactly once.
	 */
	public boolean endTick() {
		if (!enabled || !inTick) {
			return false;
		}
		inTick = false;

		long elapsedNanos = System.nanoTime() - tickStartNanos;
		double elapsedMillis = elapsedNanos / 1_000_000.0;
		double factor;

		synchronized (this) {
			if (measuredTicks == 0) {
				emaTickMillis = elapsedMillis;
			} else {
				// ~1 second of half-life at 20 TPS: responsive, but not twitchy.
				emaTickMillis += (elapsedMillis - emaTickMillis) * 0.05;
			}
			measuredTicks++;
			worstTickMillis = Math.max(worstTickMillis, elapsedMillis);

			if (elapsedMillis > targetTickMillis) {
				overloadedTicks++;
				overloadStreak++;
			} else {
				overloadStreak = 0;
			}

			factor = measuredTicks < WARMUP_TICKS
				? 0.0
				: clamp01((emaTickMillis - targetTickMillis * 0.5) / targetTickMillis);

			loadFactor = factor;

			if (overloadStreak == 100 && !overloadLogged) {
				overloadLogged = true;
				BerylliumLog.warn("[BERYLLIUM-TICK] Sustained tick overrun: " + format(emaTickMillis)
					+ " ms average (target " + format(targetTickMillis)
					+ " ms) over 100 ticks. Adaptive throttling is scaling Beryllium's "
					+ "hopper/item/particle intervals up; if this persists, raise "
					+ "hopperThrottleInterval/itemEntityThrottleInterval or lower entity counts.");
			}
		}
		return true;
	}

	/**
	 * @return 0.0 when the server is comfortably inside its budget, 1.0 when the average
	 *         tick costs ~1.5x the configured target.
	 */
	public double loadFactor() {
		return enabled ? loadFactor : 0.0;
	}

	/**
	 * Scales a throttle interval by the current load. A throttle interval of 1 is never
	 * scaled (it means "disabled"), so a feature that is off stays off under load.
	 */
	public int scaleInterval(int baseInterval) {
		if (!enabled || baseInterval <= 1) {
			return baseInterval;
		}
		double multiplier = 1.0 + loadFactor() * (maxScale - 1.0);
		int scaled = (int) Math.round(baseInterval * multiplier);
		return Math.max(1, scaled);
	}

	/**
	 * Shrinks a client-side cull distance under sustained load (single player shares one
	 * thread between the server tick and the frame, so tick pressure is frame pressure).
	 *
	 * @return the distance to use, never below {@code minDistance} and never above
	 *         {@code configuredDistance}.
	 */
	public double scaleDistance(double configuredDistance, double minDistance) {
		if (!enabled || configuredDistance <= 0.0) {
			return configuredDistance;
		}
		double reduced = configuredDistance * (1.0 - 0.5 * loadFactor());
		return Math.max(minDistance, Math.min(configuredDistance, reduced));
	}

	public synchronized double averageTickMillis() {
		return emaTickMillis;
	}

	public synchronized double worstTickMillis() {
		return worstTickMillis;
	}

	public synchronized long measuredTicks() {
		return measuredTicks;
	}

	public synchronized long overloadedTicks() {
		return overloadedTicks;
	}

	/** Human-readable one-liner for the debug log / future overlay. */
	public synchronized String summary() {
		return "tick avg=" + format(emaTickMillis) + "ms worst=" + format(worstTickMillis)
			+ "ms load=" + format(loadFactor() * 100.0) + "% ticks=" + measuredTicks
			+ " over=" + overloadedTicks;
	}

	private static double clamp01(double value) {
		if (value < 0.0) {
			return 0.0;
		}
		return value > 1.0 ? 1.0 : value;
	}

	private static String format(double value) {
		return String.format("%.2f", value);
	}
}
