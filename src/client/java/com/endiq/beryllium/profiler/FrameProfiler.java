package com.endiq.beryllium.profiler;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;

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

	public FrameTimeRingBuffer.Snapshot snapshot() {
		return frameTimes.snapshot();
	}
}
