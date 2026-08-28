package com.endiq.beryllium.buffer;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class BufferPool<T> {
	private final Deque<T> idle = new ArrayDeque<>();
	private final Supplier<T> factory;
	private final Consumer<T> reset;
	private final Consumer<T> onDiscard;
	private final int maxIdleSize;

	private int createdCount = 0;
	private int reuseCount = 0;
	
	public BufferPool(Supplier<T> factory, Consumer<T> reset, Consumer<T> onDiscard, int maxIdleSize) {
		if (factory == null) {
			throw new IllegalArgumentException("factory must not be null");
		}
		if (maxIdleSize <= 0) {
			throw new IllegalArgumentException("maxIdleSize must be positive, got " + maxIdleSize);
		}
		this.factory = factory;
		this.reset = reset;
		this.onDiscard = onDiscard;
		this.maxIdleSize = maxIdleSize;
	}

	public T acquire() {
		T item = idle.poll();
		if (item != null) {
			reuseCount++;
			return item;
		}
		createdCount++;
		return factory.get();
	}

	public void release(T item) {
		if (item == null) {
			return;
		}
		if (reset != null) {
			reset.accept(item);
		}
		if (idle.size() < maxIdleSize) {
			idle.push(item);
		} else if (onDiscard != null) {
			onDiscard.accept(item);
		}
	}

	public int idleSize() {
		return idle.size();
	}

	public int createdCount() {
		return createdCount;
	}

	public int reuseCount() {
		return reuseCount;
	}
}
