package com.endiq.beryllium.tick;

import com.endiq.beryllium.util.BerylliumLog;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Beryllium's shared worker pool — the "use more than one core" primitive behind the
 * parallel ticking features.
 *
 * <p>Deliberately small and boring:
 * <ul>
 *   <li>Daemon threads, so a pool can never keep the JVM (or a launcher) alive.</li>
 *   <li>A bounded submission path: if the queue is full the task runs on the *calling*
 *       thread (the server thread), which is slower but always correct. Bursts of work
 *       therefore degrade in speed, never in safety.</li>
 *   <li>Threads are created lazily on first use and sized from the machine, not from a
 *       guess: half the available cores, clamped to [1, configuredMax], and never more
 *       than 4 — a Minecraft server needs its main thread and its chunk threads too.</li>
 * </ul>
 *
 * <p>Nothing here touches the world. Everything submitted must either be read-only with
 * respect to the level, or funnel its writes through {@link DeferredWorldActions}.
 */
public final class BerylliumWorkers {
	private static final int HARD_THREAD_LIMIT = 4;
	private static final int QUEUE_CAPACITY = 512;

	private static volatile ThreadPoolExecutor pool;
	private static volatile int activeThreads = 0;
	private static volatile long tasksRun = 0;

	private BerylliumWorkers() {
	}

	/**
	 * @param configuredThreads 0 (or negative) = auto-size from the CPU count.
	 * @return the shared pool, never null.
	 */
	public static ThreadPoolExecutor pool(int configuredThreads) {
		ThreadPoolExecutor existing = pool;
		if (existing != null && !existing.isShutdown()) {
			return existing;
		}
		synchronized (BerylliumWorkers.class) {
			if (pool == null || pool.isShutdown()) {
				pool = create(configuredThreads);
			}
			return pool;
		}
	}

	public static int activeThreads() {
		return activeThreads;
	}

	public static long tasksRun() {
		return tasksRun;
	}

	public static boolean isAlive() {
		ThreadPoolExecutor existing = pool;
		return existing != null && !existing.isShutdown();
	}

	/** Sizes the pool: half the cores, clamped to [1, min(configuredMax, 4)]. */
	static int threadCount(int configuredThreads) {
		int cores = Runtime.getRuntime().availableProcessors();
		int auto = Math.max(1, cores / 2);
		if (configuredThreads <= 0) {
			return Math.min(auto, HARD_THREAD_LIMIT);
		}
		return Math.max(1, Math.min(configuredThreads, HARD_THREAD_LIMIT));
	}

	private static ThreadPoolExecutor create(int configuredThreads) {
		int threads = threadCount(configuredThreads);
		BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
		ThreadFactory factory = new ThreadFactory() {
			private final AtomicInteger counter = new AtomicInteger();

			@Override
			public Thread newThread(Runnable runnable) {
				Thread thread = new Thread(runnable, "Beryllium-Worker-" + counter.incrementAndGet());
				thread.setDaemon(true);
				thread.setPriority(Thread.NORM_PRIORITY - 1);
				return thread;
			}
		};
		// Saturation fallback: run the task on the submitting thread. Slower, but it keeps
		// the work on the server thread, where every world mutation is legal.
		RejectedExecutionHandler onReject = (runnable, executor) -> {
			if (!executor.isShutdown()) {
				runnable.run();
			}
		};
		ThreadPoolExecutor executor = new ThreadPoolExecutor(
			threads, threads, 60L, TimeUnit.SECONDS, queue, factory, onReject
		);
		executor.allowCoreThreadTimeOut(true);
		activeThreads = threads;
		BerylliumLog.info("[BERYLLIUM-TICK] Worker pool ready: " + threads + " thread(s) on "
			+ Runtime.getRuntime().availableProcessors() + " detected core(s).");
		return executor;
	}

	/**
	 * Submits one unit of work, counting it for telemetry. Exceptions are contained: a
	 * failing task must never take the tick (or the pool) down with it.
	 */
	public static void submit(int configuredThreads, Runnable task) {
		pool(configuredThreads).execute(() -> {
			tasksRun++;
			try {
				task.run();
			} catch (Throwable t) {
				DeferredWorldActions.noteFailure(t);
			}
		});
	}

	/** Best-effort shutdown; the pool is daemon-backed so it can never block exit. */
	public static void shutdown() {
		ThreadPoolExecutor existing = pool;
		if (existing != null) {
			existing.shutdownNow();
		}
	}
}
