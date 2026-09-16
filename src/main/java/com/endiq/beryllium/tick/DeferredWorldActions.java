package com.endiq.beryllium.tick;

import com.endiq.beryllium.util.BerylliumLog;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * The safety core of Beryllium's off-thread ticking.
 *
 * <p>Problem: Minecraft's world was written for exactly one thread. If a worker thread
 * calls {@code Level.setBlock} directly it mutates state that other workers are reading
 * in the same instant, and the result is not a crash — it is a subtly wrong world (and
 * occasionally a genuinely corrupt one).
 *
 * <p>Solution: while a worker runs vanilla code, every world mutation it attempts is
 * <em>recorded</em> instead of performed. The worker sees a consistent read-only snapshot
 * for its whole task; when the main thread joins the tasks it replays the recorded
 * mutations in a deterministic order. Cross-thread reads are therefore never torn, and
 * the only behavioural difference from vanilla is that a mutation is not visible to
 * other tasks until the join — the same as vanilla, where the tick order decides, just
 * batched.
 *
 * <p>The class is deliberately tiny and total: {@link #offer} returns {@code false} when
 * there is no active recording scope, and the caller must then let vanilla perform the
 * mutation normally. A dead hook can therefore only ever mean "no optimisation", never
 * "illegal mutation".
 */
public final class DeferredWorldActions {
	/** Per-thread recording scope. Only ever non-null on a Beryllium worker. */
	private static final ThreadLocal<ArrayDeque<Runnable>> SCOPE = new ThreadLocal<>();

	private static volatile boolean experimentalEnabled = true;
	private static volatile String disabledReason = null;
	private static volatile long deferredTotal = 0;
	private static volatile long tasksRun = 0;
	private static volatile long tasksWithMutations = 0;
	private static volatile boolean failureSeen = false;

	private DeferredWorldActions() {
	}

	/** Opens a recording scope on the current thread. Nested scopes are not supported. */
	public static void begin() {
		SCOPE.set(new ArrayDeque<>(4));
	}

	/**
	 * Closes the recording scope and returns everything that was recorded, in submission
	 * order. The caller (always the main thread, after joining) replays it.
	 */
	public static List<Runnable> end() {
		ArrayDeque<Runnable> recorded = SCOPE.get();
		SCOPE.remove();
		if (recorded == null || recorded.isEmpty()) {
			return List.of();
		}
		return new ArrayList<>(recorded);
	}

	public static boolean isDeferring() {
		return SCOPE.get() != null;
	}

	/**
	 * Records a world mutation if this thread is inside a recording scope.
	 *
	 * @return true if the action was recorded (the caller must cancel vanilla's own
	 *         execution of it); false if it must run inline.
	 */
	public static boolean offer(Runnable action) {
		ArrayDeque<Runnable> recorded = SCOPE.get();
		if (recorded == null || action == null) {
			return false;
		}
		recorded.add(action);
		return true;
	}

	/** Counts a completed off-thread task; used by the telemetry/self-checks. */
	public static void noteTask(boolean mutated) {
		tasksRun++;
		if (mutated) {
			tasksWithMutations++;
		}
	}

	public static void noteDeferred() {
		deferredTotal++;
	}

	/**
	 * Records a failure inside off-thread work and permanently disables every
	 * experimental off-thread feature for the session. A wrong optimisation must never
	 * be allowed to keep trying: the moment anything unexpected happens, Beryllium
	 * reverts to vanilla single-threaded ticking and says so in the log.
	 */
	public static synchronized void noteFailure(Throwable failure) {
		if (failureSeen) {
			return;
		}
		failureSeen = true;
		disableExperimental("an off-thread task threw " + failure);
		BerylliumLog.error("[BERYLLIUM-TICK] Off-thread tick work failed; Beryllium's "
			+ "experimental parallel/async ticking is disabled for the rest of this session "
			+ "and vanilla single-threaded ticking is in effect.", failure);
	}

	/** Disables the experimental off-thread features, once, with a reason. */
	public static synchronized void disableExperimental(String reason) {
		if (!experimentalEnabled) {
			return;
		}
		experimentalEnabled = false;
		disabledReason = reason;
		BerylliumLog.warn("[BERYLLIUM-TICK] Experimental off-thread ticking disabled: " + reason
			+ ". Set asyncRandomTicks/parallelEntityTicking to false in beryllium.json to "
			+ "make that permanent.");
	}

	public static boolean isExperimentalEnabled() {
		return experimentalEnabled && !failureSeen;
	}

	public static String disabledReason() {
		return disabledReason;
	}

	public static long deferredTotal() {
		return deferredTotal;
	}

	public static long tasksRun() {
		return tasksRun;
	}

	public static long tasksWithMutations() {
		return tasksWithMutations;
	}

	/**
	 * Replays recorded mutations on the main thread. Each action is individually
	 * contained: one bad replay must not cancel the rest of the batch (dropping a block
	 * update is worse than logging it).
	 */
	public static void replay(List<Runnable> actions) {
		if (actions == null || actions.isEmpty()) {
			return;
		}
		for (Runnable action : actions) {
			try {
				action.run();
			} catch (Throwable t) {
				BerylliumLog.error("[BERYLLIUM-TICK] Replaying a deferred world action failed; "
					+ "continuing with the rest of the batch.", t);
			}
		}
	}
}
