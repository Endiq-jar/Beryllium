package com.endiq.beryllium.buffer;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A generic reuse pool: acquire() returns an idle instance if one exists, otherwise
 * creates a new one; release() returns an instance for reuse (after an optional reset),
 * up to a capacity cap.
 *
 * <p>Deliberately generic and free of any GPU/GL specifics — spec section 13 wants
 * "buffer reuse, pooling, reduced allocations" for GPU buffers specifically, but the
 * pooling *pattern* itself (and the bug-prone parts of it — capacity limits, proper
 * disposal of what doesn't fit) is identical whether {@code T} is a GPU buffer handle,
 * a mesh builder scratch object, or anything else. The actual GL buffer creation code
 * that would use this is future work (needs the same real-jar verification as chunk
 * rendering); this is the reusable primitive it will sit on top of.
 *
 * <p>Not thread-safe — intended for single-threaded (render-thread or one worker
 * thread's) use, matching every other scheduler class in this mod.
 */
public final class BufferPool<T> {
	private final Deque<T> idle = new ArrayDeque<>();
	private final Supplier<T> factory;
	private final Consumer<T> reset;
	private final Consumer<T> onDiscard;
	private final int maxIdleSize;

	private int createdCount = 0;
	private int reuseCount = 0;

	/**
	 * @param factory creates a new instance when the pool is empty
	 * @param reset optional; called on an instance right before it's handed back out
	 *              via {@link #acquire()} is NOT where this runs — it runs at
	 *              {@link #release} time, so a released instance is already
	 *              "clean" while sitting idle
	 * @param onDiscard optional; called on an instance that's released while the pool
	 *                  is already at {@code maxIdleSize} — this is the caller's chance
	 *                  to actually dispose of a native resource (e.g. glDeleteBuffers)
	 *                  rather than letting a handle leak
	 * @param maxIdleSize caps how many idle instances are kept around at once
	 */
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
