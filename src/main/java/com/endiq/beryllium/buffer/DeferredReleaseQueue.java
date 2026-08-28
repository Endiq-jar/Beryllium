package com.endiq.beryllium.buffer;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Consumer;

/**
 * Delays disposal of released items by a fixed number of {@link #advance()} calls
 * (intended to be one per frame), instead of disposing immediately. This matters for
 * GPU resources specifically: a buffer the CPU just finished with might still be
 * in-flight on the GPU, and deleting it right away can force a driver-side stall or
 * sync point. Waiting a few frames avoids that. (Spec section 13: "deferred deletion".)
 *
 * <p>Not thread-safe; intended to be driven from the render thread.
 */
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

	/** Advances the internal clock by one tick and disposes anything now due.
	 *  @return how many items were disposed this call. */
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
