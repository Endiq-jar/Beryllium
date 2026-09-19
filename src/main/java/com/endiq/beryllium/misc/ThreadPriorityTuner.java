package com.endiq.beryllium.misc;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.config.BerylliumConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gives the render, server and worker threads the priorities the player asked for.
 *
 * <p>Java thread priorities are hints to the operating system scheduler, and on a phone with
 * two or four cores they are the difference between a background chunk compile eating the frame
 * and not: with everything at the default priority the scheduler is free to run a compiler
 * thread while the render thread is still waiting for it to get out of the way.
 *
 * <p>Applied by watching the live threads rather than by patching thread creation: the game
 * creates and recreates these threads (and the launchers Beryllium targets rename some of
 * them), so a periodic pass over the running set is both simpler and more accurate than hooking
 * every place a thread is born. The pass is rate limited and does nothing when the priorities
 * are already what they should be.
 */
public final class ThreadPriorityTuner {
	private ThreadPriorityTuner() {
	}

	/** How often the tuner is allowed to walk the live threads. */
	private static final long INTERVAL_NANOS = 2_000_000_000L;

	private static final Map<String, Integer> APPLIED = new ConcurrentHashMap<>();
	private static volatile long lastPass;
	private static volatile String summary = "not run yet";

	public static void applyIfDue() {
		long now = System.nanoTime();
		if (now - lastPass < INTERVAL_NANOS) {
			return;
		}
		lastPass = now;
		applyNow();
	}

	public static void applyNow() {
		BerylliumConfig config = Beryllium.config();
		if (config == null || !config.enabled || !config.threadPriorities) {
			return;
		}

		int changed = 0;
		for (Thread thread : liveThreads()) {
			int wanted = priorityFor(thread.getName(), config);
			if (wanted <= 0) {
				continue;
			}
			try {
				if (thread.isAlive() && thread.getPriority() != wanted) {
					thread.setPriority(wanted);
					changed++;
				}
				APPLIED.put(thread.getName(), wanted);
			} catch (Throwable t) {
				// A thread can die between the walk and the call, and a security manager (or a
				// launcher's own policy) can refuse the change. Neither is worth a log line.
			}
		}
		if (changed > 0) {
			summary = "set " + changed + " thread priority/priorities this pass";
		}
	}

	public static String describe() {
		return summary;
	}

	/**
	 * The running threads, without capturing their stacks.
	 *
	 * <p>{@code Thread.getAllStackTraces()} would be the one-liner, but it walks every thread's
	 * stack — which is a suspension point for each of them and costs more than every priority
	 * change it is being used to make. Thread enumeration is cheap and is enough: only the names
	 * matter here.
	 */
	private static List<Thread> liveThreads() {
		ThreadGroup group = Thread.currentThread().getThreadGroup();
		ThreadGroup root = group;
		while (root != null && root.getParent() != null) {
			root = root.getParent();
		}
		if (root == null) {
			root = group;
		}
		Thread[] buffer = new Thread[Math.max(64, root.activeCount() * 2)];
		int count = root.enumerate(buffer, true);
		List<Thread> threads = new ArrayList<>(Math.max(1, count));
		for (int i = 0; i < count && i < buffer.length; i++) {
			if (buffer[i] != null) {
				threads.add(buffer[i]);
			}
		}
		return threads;
	}

	private static int priorityFor(String name, BerylliumConfig config) {
		if (name == null || name.isEmpty()) {
			return 0;
		}
		if (name.startsWith("Render thread")) {
			return clamp(config.renderThreadPriority);
		}
		if (name.startsWith("Server thread")) {
			return clamp(config.serverThreadPriority);
		}
		if (isWorker(name)) {
			return clamp(config.workerThreadPriority);
		}
		return 0;
	}

	/** The game's background worker pools. Kept as prefixes because the counts vary by version
	 *  and by machine ("Worker-Main-3", "Worker-12", "Beryllium-Dimension-2"). */
	private static boolean isWorker(String name) {
		String lower = name.toLowerCase(Locale.ROOT);
		return lower.startsWith("worker-main")
				|| lower.startsWith("worker-")
				|| lower.startsWith("beryllium-")
				|| lower.startsWith("chunk-compile")
				|| lower.startsWith("io-worker");
	}

	private static int clamp(int priority) {
		if (priority < Thread.MIN_PRIORITY) {
			return Thread.MIN_PRIORITY;
		}
		if (priority > Thread.MAX_PRIORITY) {
			return Thread.MAX_PRIORITY;
		}
		return priority;
	}
}
