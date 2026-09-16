package com.endiq.beryllium.tick;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.HashMap;
import java.util.Map;

/**
 * Hopper throttling: idle hoppers stop paying for a full transfer attempt every single
 * tick.
 *
 * <p>Vanilla already spaces transfer attempts out with an 8-tick cooldown, but the tick
 * itself ({@code HopperBlockEntity#pushItemsTick}) still runs every game tick for every
 * hopper, and it still decrements the cooldown and walks the hopper's inventory. In a
 * large storage system — hundreds or thousands of hoppers, most of them either full with
 * nowhere to push, or empty with nothing above to pull — that is a steady, completely
 * wasted cost on the server thread.
 *
 * <p>The rule here is deliberately conservative: <strong>only hoppers that demonstrably
 * have nothing to do are throttled.</strong> Beryllium records a cheap fingerprint of the
 * hopper's contents (which of its slots are non-empty) each time it is allowed to run.
 * If the fingerprint is unchanged for {@code hopperIdleSamples} consecutive runs, the
 * hopper is treated as idle and its next attempts are skipped so it only runs once every
 * {@code interval} ticks. The moment anything about its contents changes — an item
 * arrives, an item leaves, a comparator reads it — the fingerprint changes, the hopper is
 * immediately back to full speed, and throttling can only ever cost it one interval of
 * latency.
 *
 * <p>Consequences, stated honestly: an idle hopper that suddenly receives an item waits
 * up to {@code interval} ticks (default 4 = 0.2 s) before it notices. A hopper that is
 * actively moving items is never throttled at all. Set {@code hopperThrottleInterval} to
 * 1 to turn this off.
 *
 * <p>State is keyed by block position and pruned, so a world with a million hopper
 * positions cannot grow the table without bound.
 */
public final class HopperThrottle {
	private static final int PRUNE_THRESHOLD = 4096;
	private static final long PRUNE_AGE_TICKS = 6000L; // 5 minutes

	private final Map<Long, State> states = new HashMap<>(256);
	private long currentTick = 0;
	private long throttledTicks = 0;
	private long idleHoppers = 0;
	private boolean countedThisTick;

	private HopperThrottle() {
	}

	private static final class State {
		int fingerprint;
		int unchangedRuns;
		long lastSeenTick;
	}

	public static HopperThrottle create() {
		return new HopperThrottle();
	}

	/**
	 * @param pos        the hopper's position (the throttle's identity)
	 * @param hopper     the hopper block entity, used only to read its contents
	 * @param interval   run one tick out of this many when idle (1 = disabled)
	 * @param idleSamples consecutive identical fingerprints before a hopper counts as idle
	 * @return true if the hopper should run its transfer logic this tick
	 */
	public boolean shouldRun(BlockPos pos, BlockEntity hopper, int interval, int idleSamples) {
		if (interval <= 1 || pos == null) {
			return true;
		}

		long key = pos.asLong();
		State state = states.get(key);
		if (state == null) {
			state = new State();
			state.fingerprint = fingerprint(hopper);
			state.unchangedRuns = 0;
			state.lastSeenTick = currentTick;
			states.put(key, state);
			// Never throttle the very first sighting: let it tick once so the fingerprint
			// has a real baseline to compare against.
			return true;
		}

		state.lastSeenTick = currentTick;
		int fingerprint = fingerprint(hopper);

		if (fingerprint != state.fingerprint) {
			state.fingerprint = fingerprint;
			state.unchangedRuns = 0;
			return true;
		}

		if (state.unchangedRuns < Math.max(1, idleSamples)) {
			state.unchangedRuns++;
			return true;
		}

		// Idle: run one tick out of `interval`, staggered by position so a wall of hoppers
		// does not all wake up on the same tick and re-create the spike this removes.
		int phase = phaseOf(key);
		boolean run = (currentTick + phase) % interval == 0;
		if (!run) {
			if (!countedThisTick) {
				countedThisTick = true;
				idleHoppers++;
			}
			throttledTicks++;
		}
		return run;
	}

	/** Advances the internal tick counter; called once per server tick. */
	public void onTick() {
		currentTick++;
		countedThisTick = false;
		if (states.size() > PRUNE_THRESHOLD) {
			prune();
		}
	}

	/** Drops state for hoppers that have not been seen in a long time (chunk unload, ...). */
	public void prune() {
		long cutoff = currentTick - PRUNE_AGE_TICKS;
		states.entrySet().removeIf(entry -> entry.getValue().lastSeenTick < cutoff);
	}

	public void clear() {
		states.clear();
	}

	public int trackedHoppers() {
		return states.size();
	}

	public long throttledTicks() {
		return throttledTicks;
	}

	public long idleSightings() {
		return idleHoppers;
	}

	/** One bit per slot: cheap, allocation-free, and sensitive to any content change. */
	private static int fingerprint(BlockEntity hopper) {
		if (!(hopper instanceof Container container)) {
			return -1;
		}
		int size = container.getContainerSize();
		int mask = 0;
		for (int slot = 0; slot < size && slot < 32; slot++) {
			if (!container.getItem(slot).isEmpty()) {
				mask |= 1 << slot;
			}
		}
		return mask;
	}

	/** Spreads hoppers across the interval so they do not synchronise. */
	private static int phaseOf(long key) {
		int hash = (int) (key ^ (key >>> 32));
		return Math.floorMod(hash, 997);
	}
}
