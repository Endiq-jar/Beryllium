package com.endiq.beryllium.profiler;

/**
 * Wires {@link FrameTimeRingBuffer} to an actual per-frame signal. The time between two
 * consecutive {@link #onFrameStart()} calls is the frame time.
 *
 * <p>The signal comes from Beryllium's own client tick hook
 * ({@code ClientFrameHooks}) rather than from a modding-API render event, so the profiler
 * works on every supported Minecraft release and on installs with no Fabric API present.
 *
 * <p>Two different quantities are exposed, and keeping them apart matters:
 * <ul>
 *   <li>{@link #lastFrameNanos()} — the duration of the most recently <em>completed</em>
 *       frame. This is what every frame-time governor in Beryllium wants: it is a finished
 *       measurement, so a decision taken from it cannot be wrong about the frame it is
 *       judging.</li>
 *   <li>{@link #currentFrameElapsedNanos()} — how long the frame now in progress has been
 *       running. Called at the start of a frame this is a few microseconds, which is why
 *       it is not a substitute for the above.</li>
 * </ul>
 *
 * <p>{@link #lastFrameNanos()} used to return the second quantity while every caller
 * documented it as the first. The value it produced at the point the chunk governors read
 * it was the handful of microseconds between two adjacent calls in the same hook, so both
 * governors permanently saw a healthy frame and never tightened their allowance — the
 * adaptive half of chunk compilation scheduling and of chunk upload pacing was inert.
 */
public final class FrameProfiler {
	private static final int SAMPLE_CAPACITY = 3600;

	/** How often {@link #averageFrameNanos()} is recomputed. The snapshot sorts a copy of
	 *  the ring buffer, so it is far too expensive to take every frame. */
	private static final int AVERAGE_REFRESH_FRAMES = 30;

	private final FrameTimeRingBuffer frameTimes = new FrameTimeRingBuffer(SAMPLE_CAPACITY);
	private long lastFrameStartNanos = -1L;
	private long lastFrameDurationNanos = -1L;
	private long cachedAverageFrameNanos = -1L;
	private boolean averageStale = true;
	private int framesSinceAverageRefresh = AVERAGE_REFRESH_FRAMES;

	/** Called once per client frame by {@code ClientFrameHooks}. */
	public void onFrameStart() {
		long now = System.nanoTime();
		if (lastFrameStartNanos >= 0L) {
			lastFrameDurationNanos = now - lastFrameStartNanos;
			frameTimes.record(lastFrameDurationNanos);
		}
		lastFrameStartNanos = now;
		if (++framesSinceAverageRefresh >= AVERAGE_REFRESH_FRAMES) {
			framesSinceAverageRefresh = 0;
			averageStale = true;
		}
	}

	/**
	 * Duration of the most recently completed frame in nanoseconds; {@code -1} before the
	 * second frame has started. Used to size the per-frame maintenance budget and the chunk
	 * upload/compile budgets.
	 */
	public long lastFrameNanos() {
		return lastFrameDurationNanos;
	}

	/**
	 * Nanoseconds elapsed in the frame that is running right now; {@code -1} before the
	 * first frame. Small at the top of a frame by definition — see the class comment.
	 */
	public long currentFrameElapsedNanos() {
		return lastFrameStartNanos < 0 ? -1L : System.nanoTime() - lastFrameStartNanos;
	}

	/**
	 * The rolling average frame time, or {@code -1} when no frame has completed yet.
	 *
	 * <p>This is the baseline the chunk governors compare a single frame against. A fixed
	 * 60 Hz target would leave a low-end device that legitimately runs at 30 FPS permanently
	 * in the "frame in trouble" state, which is exactly the audience Beryllium is built for.
	 * Recomputed at most every {@value #AVERAGE_REFRESH_FRAMES} frames.
	 */
	public long averageFrameNanos() {
		if (averageStale) {
			averageStale = false;
			double average = frameTimes.snapshot().avgFrameNanos();
			cachedAverageFrameNanos = average > 0 ? (long) average : -1L;
		}
		return cachedAverageFrameNanos;
	}

	public FrameTimeRingBuffer.Snapshot snapshot() {
		return frameTimes.snapshot();
	}
}
