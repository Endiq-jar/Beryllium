package com.endiq.beryllium.profiler;

/**
 * Wires {@link FrameTimeRingBuffer} to an actual per-frame signal. The time between two
 * consecutive {@link #onFrameStart()} calls is the frame time.
 *
 * <p>The signal comes from Beryllium's own client tick hook
 * ({@code ClientFrameHooks}) rather than from a modding-API render event, so the profiler
 * works on every supported Minecraft release and on installs with no Fabric API present.
 */
public final class FrameProfiler {
	private static final int SAMPLE_CAPACITY = 3600;

	private final FrameTimeRingBuffer frameTimes = new FrameTimeRingBuffer(SAMPLE_CAPACITY);
	private long lastFrameStartNanos = -1L;

	/** Called once per client frame by {@code ClientFrameHooks}. */
	public void onFrameStart() {
		long now = System.nanoTime();
		if (lastFrameStartNanos >= 0L) {
			frameTimes.record(now - lastFrameStartNanos);
		}
		lastFrameStartNanos = now;
	}

	/** Nanoseconds since the previous frame's start; -1 before the first frame. Used to
	 *  size the per-frame maintenance budget and the chunk upload/compile budgets. */
	public long lastFrameNanos() {
		return lastFrameStartNanos < 0 ? -1L : System.nanoTime() - lastFrameStartNanos;
	}

	public FrameTimeRingBuffer.Snapshot snapshot() {
		return frameTimes.snapshot();
	}
}
