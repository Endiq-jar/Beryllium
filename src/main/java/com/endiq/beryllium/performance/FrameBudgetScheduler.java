package com.endiq.beryllium.performance;

import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.Map;
import java.util.Queue;

/**
 * A small, allocation-light scheduler for background work that needs to respect a
 * per-frame time budget (spec section 9).
 *
 * <p><b>Not wired into any Beryllium subsystem yet.</b> Phase 4 (chunk optimization) is
 * what will eventually submit real work here (chunk mesh builds, uploads, etc.) — this
 * class is deliberately standalone and game-independent so it can be built and verified
 * on its own first, per the "compile, launch, be testable" rule for every milestone.
 *
 * <p>Thread-safety: none. Intended to be owned and driven entirely from the render
 * thread, matching how frame budgets are naturally consumed once per rendered frame.
 */
public final class FrameBudgetScheduler {
	private final Map<WorkPriority, Queue<Runnable>> queues = new EnumMap<>(WorkPriority.class);

	public FrameBudgetScheduler() {
		for (WorkPriority priority : WorkPriority.values()) {
			queues.put(priority, new ArrayDeque<>());
		}
	}

	public void submit(WorkPriority priority, Runnable task) {
		if (priority == null || task == null) {
			throw new IllegalArgumentException("priority and task must not be null");
		}
		queues.get(priority).add(task);
	}

	public int pending(WorkPriority priority) {
		return queues.get(priority).size();
	}

	public int totalPending() {
		int total = 0;
		for (Queue<Runnable> queue : queues.values()) {
			total += queue.size();
		}
		return total;
	}

	/**
	 * Runs queued work for up to {@code budgetNanos}.
	 *
	 * <p>CRITICAL work always runs to completion regardless of the budget — per section 9,
	 * "never let optional background work stall the render thread" implies CRITICAL work
	 * is, by definition, not optional. Every other priority stops as soon as the deadline
	 * passes, even mid-queue, and lower priorities than the one that just ran out of time
	 * are skipped entirely for this call.
	 *
	 * @return how many tasks actually ran.
	 */
	public int runFor(long budgetNanos) {
		int ran = 0;

		Queue<Runnable> critical = queues.get(WorkPriority.CRITICAL);
		while (!critical.isEmpty()) {
			critical.poll().run();
			ran++;
		}

		long deadline = System.nanoTime() + budgetNanos;
		for (WorkPriority priority : WorkPriority.values()) {
			if (priority == WorkPriority.CRITICAL) {
				continue;
			}
			if (System.nanoTime() >= deadline) {
				break;
			}
			Queue<Runnable> queue = queues.get(priority);
			while (!queue.isEmpty() && System.nanoTime() < deadline) {
				queue.poll().run();
				ran++;
			}
		}
		return ran;
	}
}
