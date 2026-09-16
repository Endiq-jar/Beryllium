package com.endiq.beryllium.tick;

import com.endiq.beryllium.util.BerylliumLog;

/**
 * The single place that owns Beryllium's per-tick state: the tick-time governor, the
 * hopper throttle table and the counters that make all of it observable.
 *
 * <p>Kept out of {@code Beryllium} itself so the mixins (which can fire before mod
 * initialization on some loaders) never have to touch mod-lifecycle state, and so the
 * throttle table can be reset when the level changes.
 */
public final class TickRuntime {
	private static final TickRuntime INSTANCE = new TickRuntime();

	private final HopperThrottle hoppers = HopperThrottle.create();
	private volatile boolean configured = false;
	private long ticks = 0;

	private TickRuntime() {
	}

	public static TickRuntime instance() {
		return INSTANCE;
	}

	public HopperThrottle hoppers() {
		return hoppers;
	}

	/** Called once per server tick, before vanilla's tick body. */
	public void onTickStart() {
		if (!configured) {
			TickTimeGovernor.instance().configure(
				BerylliumConfigCache.tickGovernorEnabled(),
				BerylliumConfigCache.targetTickTimeMillis(),
				BerylliumConfigCache.tickGovernorMaxScale()
			);
			configured = true;
		} else {
			TickTimeGovernor.instance().configure(
				BerylliumConfigCache.tickGovernorEnabled(),
				BerylliumConfigCache.targetTickTimeMillis(),
				BerylliumConfigCache.tickGovernorMaxScale()
			);
		}
		TickTimeGovernor.instance().beginTick();
	}

	/** Called once per server tick, after vanilla's tick body. */
	public void onTickEnd() {
		if (!TickTimeGovernor.instance().endTick()) {
			// Not the outermost tick measurement (see TickTimeGovernor#endTick).
			return;
		}
		hoppers.onTick();
		ticks++;

		if ((ticks % 1200) == 0 && BerylliumConfigCache.tickGovernorEnabled()) {
			BerylliumLog.debug("[BERYLLIUM-TICK] " + TickTimeGovernor.instance().summary()
				+ "; hoppers tracked=" + hoppers.trackedHoppers()
				+ " idleSkips=" + hoppers.throttledTicks()
				+ "; items throttled=" + ItemEntityThrottle.throttledTicks()
				+ "; randomTicks async=" + AsyncRandomTicks.chunksRunAsync()
				+ " inline=" + AsyncRandomTicks.chunksRunInline()
				+ "; " + EntityTickBatcher.instance().summary());
		}
	}

	/** Resets per-world state. Called when the server changes or unloads its levels. */
	public void reset() {
		hoppers.clear();
	}

	public long ticks() {
		return ticks;
	}

	public String summary() {
		return TickTimeGovernor.instance().summary()
			+ " | hoppers: " + hoppers.trackedHoppers() + " tracked, "
			+ hoppers.throttledTicks() + " idle ticks skipped"
			+ " | items: " + ItemEntityThrottle.throttledTicks() + " ticks skipped"
			+ " | random ticks: " + AsyncRandomTicks.chunksRunAsync() + " async / "
			+ AsyncRandomTicks.chunksRunInline() + " inline"
			+ " | " + EntityTickBatcher.instance().summary();
	}
}
