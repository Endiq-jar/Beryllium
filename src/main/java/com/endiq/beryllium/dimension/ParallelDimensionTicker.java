package com.endiq.beryllium.dimension;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.config.BerylliumConfig;
import com.endiq.beryllium.util.BerylliumLog;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * Ticks the loaded dimensions at the same time, then waits for all of them.
 *
 * <p>Minecraft ticks dimensions strictly one after another inside a single server tick. Each
 * dimension's tick is mostly independent — it drives its own chunk map, its own entity lists and
 * its own block-entity and scheduling queues — so the work can overlap, which is the difference
 * between a server that keeps up with several dimensions loaded and one that does not.
 *
 * <p><b>What this is careful about.</b> Dimensions are not fully independent: a portal, a
 * teleport or an ender pearl moves a player between them, and redstone contraptions that span
 * dimensions rely on the order in which the two sides see each other. So:
 *
 * <ul>
 *   <li>the pass is a <em>barrier</em>: every dimension's tick has finished before the server
 *       tick continues, so nothing that runs after the level loop can observe a half-ticked
 *       dimension, and the tick that dimension A sees is never behind the tick dimension B
 *       sees — they are the same tick on both sides, exactly as in vanilla;</li>
 *   <li>the work is submitted once per server tick, from the server thread, and the barrier is
 *       awaited on the server thread — no tick is ever started from a stale batch;</li>
 *   <li>the first throwable from any dimension stops the parallel path for the rest of the
 *       session, permanently, and is rethrown on the server thread so the game reports it the
 *       way vanilla would rather than swallowing it in a worker thread;</li>
 *   <li>if the pass is not measurably faster than doing the work one dimension at a time, the
 *       parallel path turns itself off. Overlapping four dimensions on a phone that has two
 *       cores is slower than doing them in order, and a performance mod that guesses wrong is
 *       worse than one that does not try;</li>
 *   <li>a single dimension, an active profiler (its stack is not thread-safe), a client
 *       integrated server that is already the bottleneck, and any failure to create the worker
 *       pool all fall back to ticking in order on the server thread.</li>
 * </ul>
 *
 * <p>Every failure mode ends in serial execution, never in a skipped or repeated dimension tick.
 */
public final class ParallelDimensionTicker {
	private ParallelDimensionTicker() {
	}

	/** One dimension's tick, held until the barrier at the end of the level loop. */
	private record Pending(ServerLevel level, BooleanSupplier hasTimeLeft) {
	}

	private static final Object LOCK = new Object();
	private static final List<Pending> BATCH = new ArrayList<>();

	private static volatile boolean disabled;
	private static volatile String disabledReason;
	private static volatile ExecutorService pool;
	private static volatile boolean poolFailed;

	/** Consecutive ticks where running in parallel was not worth it. */
	private static int unprofitableTicks;

	/** Cheap self-measurement so an unhelpful parallel pass turns itself off. */
	private static double cpuNanosEma = -1.0;
	private static double wallNanosEma = -1.0;

	/** @return true when the caller must tick the dimension itself (the serial path). */
	public static boolean defer(ServerLevel level, BooleanSupplier hasTimeLeft) {
		if (level == null) {
			return false;
		}
		if (disabled || !enabled()) {
			return false;
		}
		if (!isServerThread()) {
			// Only the server thread drives a batch; anything else ticks in order.
			return false;
		}
		if (profilerActive()) {
			return false;
		}

		synchronized (LOCK) {
			BATCH.add(new Pending(level, hasTimeLeft));
		}
		return true;
	}

	/**
	 * The barrier. Runs every deferred dimension tick — in parallel when that is worth doing,
	 * in order otherwise — and does not return until all of them have finished.
	 */
	public static void flush() {
		List<Pending> pending;
		synchronized (LOCK) {
			if (BATCH.isEmpty()) {
				return;
			}
			pending = new ArrayList<>(BATCH);
			BATCH.clear();
		}

		ExecutorService executor = pending.size() > 1 ? executor() : null;
		if (executor == null) {
			// Serial fallback: no pool, or only one dimension. This is vanilla's own path.
			for (Pending p : pending) {
				p.level().tick(p.hasTimeLeft());
			}
			return;
		}

		long wallStart = System.nanoTime();
		List<CompletableFuture<Long>> futures = new ArrayList<>(pending.size());
		for (Pending p : pending) {
			ServerLevel level = p.level();
			BooleanSupplier hasTimeLeft = p.hasTimeLeft();
			futures.add(CompletableFuture.supplyAsync(() -> {
				long start = System.nanoTime();
				level.tick(hasTimeLeft);
				return System.nanoTime() - start;
			}, executor));
		}

		long cpuNanos = 0L;
		Throwable failure = null;
		for (CompletableFuture<Long> future : futures) {
			try {
				cpuNanos += future.join();
			} catch (CompletionException e) {
				if (failure == null) {
					failure = e.getCause() == null ? e : e.getCause();
				}
			} catch (Throwable t) {
				if (failure == null) {
					failure = t;
				}
			}
		}
		long wallNanos = System.nanoTime() - wallStart;

		if (failure != null) {
			// Stop overlapping for the rest of the session, then let the server thread see the
			// exception exactly as it would have seen it from an inline tick.
			disable("a dimension tick failed on a worker thread", failure);
			sneakyThrow(failure);
			return;
		}

		noteCost(cpuNanos, wallNanos);
	}

	/** Drops a batch that was never flushed, which can only happen after a tick that threw. */
	public static void discardPending() {
		synchronized (LOCK) {
			if (!BATCH.isEmpty()) {
				if (!disabled) {
					BerylliumLog.warn("[BERYLLIUM] " + BATCH.size()
							+ " dimension tick(s) were never flushed (the previous server tick threw); "
							+ "dropping them and going back to ticking dimensions one at a time.");
				}
				BATCH.clear();
			}
		}
		disable("the previous server tick did not reach the barrier", null);
	}

	public static boolean isParallel() {
		return !disabled && enabled() && pool != null;
	}

	public static String status() {
		if (disabled) {
			return "off (" + disabledReason + ")";
		}
		return enabled() ? "on" : "off (disabled in beryllium.json)";
	}

	// --- internals ---------------------------------------------------------------------

	private static boolean enabled() {
		BerylliumConfig config = Beryllium.config();
		return config == null || config.enabled && config.parallelDimensionTicking;
	}

	private static boolean isServerThread() {
		Thread thread = Thread.currentThread();
		return thread.getName() != null && thread.getName().startsWith("Server thread");
	}

	/**
	 * True while the debug profiler is collecting. Its zone stack is shared and pushed to from
	 * inside every dimension's tick, so overlapping ticks would interleave it. Reported through
	 * the class name rather than a typed call so it costs nothing on the versions where the
	 * profiler types moved; failing to tell reports "not profiling", which is the harmless way
	 * to be wrong.
	 */
	private static boolean profilerActive() {
		if (ProfilerProbe.profilerClass == null) {
			return false;
		}
		return ProfilerProbe.profilerClass.endsWith("ActiveProfiler");
	}

	/** Holds the cached result of naming the server's current profiler. */
	private static final class ProfilerProbe {
		private static final String profilerClass = resolve();

		private static String resolve() {
			try {
				Object server = ServerHolder.server();
				if (server == null) {
					return null;
				}
				java.lang.reflect.Method getter = server.getClass().getMethod("getProfiler");
				Object profiler = getter.invoke(server);
				return profiler == null ? null : profiler.getClass().getName();
			} catch (Throwable t) {
				return null;
			}
		}
	}

	/** Set by the server mixin so the profiler check has something to look at. */
	public static final class ServerHolder {
		private static volatile Object server;

		private ServerHolder() {
		}

		public static void set(Object instance) {
			server = instance;
		}

		static Object server() {
			return server;
		}
	}

	private static ExecutorService executor() {
		ExecutorService current = pool;
		if (current != null) {
			return current;
		}
		if (poolFailed) {
			return null;
		}
		synchronized (LOCK) {
			if (pool != null) {
				return pool;
			}
			if (poolFailed) {
				return null;
			}
			try {
				int threads = threadsWanted();
				if (threads < 2) {
					// One core (or a deliberately serial configuration): nothing to overlap.
					poolFailed = true;
					BerylliumLog.info("[BERYLLIUM] Parallel dimension ticking needs two threads; "
							+ "ticking dimensions in order instead.");
					return null;
				}
				AtomicInteger counter = new AtomicInteger();
				ThreadFactory factory = runnable -> {
					Thread thread = new Thread(runnable,
							"Beryllium-Dimension-" + counter.incrementAndGet());
					thread.setDaemon(true);
					thread.setPriority(Thread.NORM_PRIORITY);
					return thread;
				};
				pool = Executors.newFixedThreadPool(threads, factory);
				BerylliumLog.info("[BERYLLIUM] Parallel dimension ticking enabled with " + threads
						+ " worker thread(s).");
				return pool;
			} catch (Throwable t) {
				poolFailed = true;
				BerylliumLog.warn("[BERYLLIUM] Could not create the dimension tick pool (" + t
						+ "); ticking dimensions in order instead.");
				return null;
			}
		}
	}

	private static int threadsWanted() {
		BerylliumConfig config = Beryllium.config();
		int available = Runtime.getRuntime().availableProcessors();
		int wanted = config != null && config.parallelDimensionTickThreads > 0
				? config.parallelDimensionTickThreads
				: available - 1;
		// Leave a core for the server thread itself, and never claim more workers than the
		// machine can actually run at once.
		int cap = Math.max(1, Math.min(8, available - 1));
		return Math.max(1, Math.min(wanted, cap));
	}

	/**
	 * Watches whether overlapping the dimensions actually overlaps them. Sum of the individual
	 * tick times against the wall time of the pass is the honest measure: with two dimensions
	 * of similar cost the wall time should be about half the sum. If it never is, the pool is
	 * just adding thread handoffs, and the serial path is used from then on.
	 */
	private static void noteCost(long cpuNanos, long wallNanos) {
		BerylliumConfig config = Beryllium.config();
		double minSpeedup = config == null ? 1.05 : Math.max(1.01, config.parallelDimensionTickMinSpeedup);

		if (cpuNanosEma < 0) {
			cpuNanosEma = cpuNanos;
			wallNanosEma = wallNanos;
			return;
		}
		cpuNanosEma += (cpuNanos - cpuNanosEma) * 0.05;
		wallNanosEma += (wallNanos - wallNanosEma) * 0.05;

		if (wallNanosEma * minSpeedup < cpuNanosEma) {
			unprofitableTicks = 0;
			return;
		}
		if (++unprofitableTicks >= 40) {
			disable(String.format("no speedup after 40 ticks (%.2f ms of work in %.2f ms wall)",
					cpuNanosEma / 1.0e6, wallNanosEma / 1.0e6), null);
		}
	}

	private static void disable(String reason, Throwable cause) {
		synchronized (LOCK) {
			if (disabled) {
				return;
			}
			disabled = true;
			disabledReason = reason;
		}
		if (cause != null) {
			BerylliumLog.warn("[BERYLLIUM] Parallel dimension ticking disabled for this session: "
					+ reason + "; dimensions are ticking in order again, which is vanilla's behaviour.", cause);
		} else {
			BerylliumLog.info("[BERYLLIUM] Parallel dimension ticking disabled for this session: "
					+ reason + "; dimensions are ticking in order again, which is vanilla's behaviour.");
		}
	}

	@SuppressWarnings("unchecked")
	private static <T extends Throwable> void sneakyThrow(Throwable t) throws T {
		throw (T) t;
	}

	/** Bounded shutdown, used when the game stops. */
	public static void shutdown() {
		ExecutorService current = pool;
		pool = null;
		if (current == null) {
			return;
		}
		try {
			current.shutdown();
			if (!current.awaitTermination(2, TimeUnit.SECONDS)) {
				current.shutdownNow();
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (Throwable ignored) {
			// Shutting down is best effort.
		}
	}
}
