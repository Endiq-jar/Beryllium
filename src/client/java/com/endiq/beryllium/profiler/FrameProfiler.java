package com.endiq.beryllium.profiler;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;

/**
 * Wires {@link FrameTimeRingBuffer} to an actual per-frame signal. Uses
 * {@code WorldRenderEvents.START} (fabric-rendering-v1) purely as a "once per rendered
 * frame" clock — the time between consecutive firings is the frame time.
 */
public final class FrameProfiler {
	private static final int SAMPLE_CAPACITY = 3600;

	private final FrameTimeRingBuffer frameTimes = new FrameTimeRingBuffer(SAMPLE_CAPACITY);
	private long lastFrameStartNanos = -1L;

	public void register() {
		WorldRenderEvents.START.register(context -> onFrameStart());
	}

	private void onFrameStart() {
		long now = System.nanoTime();
		if (lastFrameStartNanos >= 0L) {
			frameTimes.record(now - lastFrameStartNanos);
		}
		lastFrameStartNanos = now;
	}

	/** Nanoseconds since the previous rendered frame's START event; -1 before the first
	 *  frame. Used by {@code FrameMaintenanceScheduler} to size the per-frame budget. */
	public long lastFrameNanos() {
		return lastFrameStartNanos < 0 ? -1L : System.nanoTime() - lastFrameStartNanos;
	}

	public FrameTimeRingBuffer.Snapshot snapshot() {
		return frameTimes.snapshot();
	}
}
