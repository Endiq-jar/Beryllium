package com.endiq.beryllium.buffer;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Consumer;

public final class DeferredReleaseQueue<T> {
	private record Entry<T>(T item, int readyAtTick) {
	}

	private final Deque<Entry<T>> queue = new ArrayDeque<>();
	private final Consumer<T> disposer;
	private final int delayTicks;
	private int currentTick = 0;

	public DeferredReleaseQueue(Consumer<T> disposer, int delayTicks) {
		if (disposer == null) {
			throw new IllegalArgumentException("disposer must not be null");
		}
		if (delayTicks < 0) {
			throw new IllegalArgumentException("delayTicks must not be negative, got " + delayTicks);
		}
		this.disposer = disposer;
		this.delayTicks = delayTicks;
	}

	public void scheduleDisposal(T item) {
		queue.addLast(new Entry<>(item, currentTick + delayTicks));
	}

	public int advance() {
		currentTick++;
		int disposed = 0;
		while (!queue.isEmpty() && queue.peekFirst().readyAtTick() <= currentTick) {
			disposer.accept(queue.pollFirst().item());
			disposed++;
		}
		return disposed;
	}

	public int pendingCount() {
		return queue.size();
	}
}
