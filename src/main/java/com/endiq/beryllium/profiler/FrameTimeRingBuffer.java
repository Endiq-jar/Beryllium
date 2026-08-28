package com.endiq.beryllium.profiler;

import java.util.Arrays;

/**
 * Fixed-capacity ring buffer of frame times (nanoseconds), with percentile-based "low"
 * stats computed on demand. Recording a sample is O(1) and allocation-free; only
 * {@link #snapshot()} allocates (a sort scratch copy), so callers should throttle how
 * often they call it (e.g. a few times a second for a debug overlay), not every frame.
 */
public final class FrameTimeRingBuffer {
	private final long[] samples;
	private int index = 0;
	private int size = 0;

	public FrameTimeRingBuffer(int capacity) {
		if (capacity <= 0) {
			throw new IllegalArgumentException("capacity must be positive, got " + capacity);
		}
		this.samples = new long[capacity];
	}

	public void record(long frameTimeNanos) {
		samples[index] = frameTimeNanos;
		index = (index + 1) % samples.length;
		if (size < samples.length) {
			size++;
		}
	}

	public int size() {
		return size;
	}

	public int capacity() {
		return samples.length;
	}

	public Snapshot snapshot() {
		if (size == 0) {
			return Snapshot.EMPTY;
		}

		long[] sorted = Arrays.copyOf(samples, size);
		Arrays.sort(sorted);

		double avgNanos = 0;
		for (long v : sorted) {
			avgNanos += v;
		}
		avgNanos /= sorted.length;

		return new Snapshot(
			avgNanos,
			worstAverage(sorted, 0.01),
			worstAverage(sorted, 0.001),
			sorted[sorted.length - 1]
		);
	}

	/** Average frame time of the worst (slowest) {@code worstFraction} of samples. */
	private static double worstAverage(long[] sortedAscending, double worstFraction) {
		int count = Math.max(1, (int) Math.round(sortedAscending.length * worstFraction));
		long sum = 0;
		for (int i = sortedAscending.length - count; i < sortedAscending.length; i++) {
			sum += sortedAscending[i];
		}
		return (double) sum / count;
	}

	public record Snapshot(
		double avgFrameNanos,
		double onePercentLowFrameNanos,
		double zeroPointOnePercentLowFrameNanos,
		long worstFrameNanos
	) {
		public static final Snapshot EMPTY = new Snapshot(0, 0, 0, 0);

		public double avgFps() {
			return avgFrameNanos <= 0 ? 0 : 1_000_000_000.0 / avgFrameNanos;
		}

		public double onePercentLowFps() {
			return onePercentLowFrameNanos <= 0 ? 0 : 1_000_000_000.0 / onePercentLowFrameNanos;
		}

		public double zeroPointOnePercentLowFps() {
			return zeroPointOnePercentLowFrameNanos <= 0 ? 0 : 1_000_000_000.0 / zeroPointOnePercentLowFrameNanos;
		}

		public double avgFrameMillis() {
			return avgFrameNanos / 1_000_000.0;
		}
	}
}
