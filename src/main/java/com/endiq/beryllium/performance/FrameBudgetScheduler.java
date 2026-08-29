package com.endiq.beryllium.performance;

import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.Map;
import java.util.Queue;

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
