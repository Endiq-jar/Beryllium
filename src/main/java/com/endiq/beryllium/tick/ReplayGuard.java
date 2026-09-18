package com.endiq.beryllium.tick;

/**
 * Stops Beryllium's own re-entry when it replays a vanilla body off-thread.
 *
 * <p>Both off-thread features work by cancelling a vanilla method at its head and then
 * running that same method from a worker through {@link VanillaBridges}. Without a guard,
 * the replayed call would land in the same injector, be cancelled, be dispatched again,
 * and recurse forever.
 *
 * <p>The guard is a thread-local flag that is set only while Beryllium itself is invoking
 * the vanilla body, so the injector can tell "vanilla called this, take it over" from
 * "this is our own replay, let it run".
 */
public final class ReplayGuard {
	private static final ThreadLocal<Boolean> ACTIVE = new ThreadLocal<>();

	private ReplayGuard() {
	}

	public static boolean isReplaying() {
		return Boolean.TRUE.equals(ACTIVE.get());
	}

	/** Runs a vanilla body with the guard held. */
	public static void run(Runnable body) {
		ACTIVE.set(Boolean.TRUE);
		try {
			body.run();
		} finally {
			ACTIVE.remove();
		}
	}
}
