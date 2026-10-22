package com.endiq.beryllium.performance;

/**
 * Target-FPS-to-frame-time conversions, per spec section 9, plus the one shared rule for
 * judging whether a finished frame was expensive.
 */
public final class FrameBudget {
	/** 60 FPS: the assumed frame cost before this device has reported its own. */
	public static final long DEFAULT_TARGET_FRAME_NANOS = targetFrameNanos(60);

	/**
	 * Bounds on how far the measured average may move the target. Without them a 240 FPS
	 * desktop would treat a 6 ms frame as a spike, and a device stuck in a slideshow would
	 * treat a 400 ms frame as normal and stop protecting itself at all.
	 */
	private static final long MIN_TARGET_FRAME_NANOS = targetFrameNanos(120);
	private static final long MAX_TARGET_FRAME_NANOS = targetFrameNanos(20);

	/** A frame this far over the target is overrunning; further than {@link #TROUBLE_RATIO} is a spike. */
	private static final double OVERRUN_RATIO = 1.5;
	private static final double TROUBLE_RATIO = 2.5;

	private FrameBudget() {
	}

	public static long targetFrameNanos(int targetFps) {
		if (targetFps <= 0) {
			throw new IllegalArgumentException("targetFps must be positive, got " + targetFps);
		}
		return 1_000_000_000L / targetFps;
	}

	public static double targetFrameMillis(int targetFps) {
		return targetFrameNanos(targetFps) / 1_000_000.0;
	}

	/**
	 * The frame cost a governor should treat as "normal" on this device.
	 *
	 * <p>Derived from the rolling average frame time rather than from a fixed 60 Hz, because
	 * Beryllium's primary audience is hardware that legitimately runs below 60: judging a
	 * 30 FPS phone against a 16.7 ms target puts every single frame in the spike band, which
	 * pins every governor at its minimum allowance and starves the work they are supposed to
	 * be pacing.
	 *
	 * @param averageFrameNanos rolling average frame time, or a non-positive value if unknown
	 * @return the target to compare a single frame against
	 */
	public static long targetFrameNanosFor(long averageFrameNanos) {
		if (averageFrameNanos <= 0L) {
			return DEFAULT_TARGET_FRAME_NANOS;
		}
		return Math.max(MIN_TARGET_FRAME_NANOS, Math.min(MAX_TARGET_FRAME_NANOS, averageFrameNanos));
	}

	/**
	 * Judges one finished frame against this device's normal cost.
	 *
	 * @param lastFrameNanos    duration of the frame that just finished, or a non-positive
	 *                          value when it has not been measured (treated as healthy: a
	 *                          governor with no evidence should not start withholding work)
	 * @param averageFrameNanos rolling average frame time, or a non-positive value if unknown
	 */
	public static FrameHealth healthOf(long lastFrameNanos, long averageFrameNanos) {
		if (lastFrameNanos <= 0L) {
			return FrameHealth.HEALTHY;
		}
		long target = targetFrameNanosFor(averageFrameNanos);
		if (lastFrameNanos > (long) (target * TROUBLE_RATIO)) {
			return FrameHealth.IN_TROUBLE;
		}
		if (lastFrameNanos > (long) (target * OVERRUN_RATIO)) {
			return FrameHealth.OVERRUNNING;
		}
		return FrameHealth.HEALTHY;
	}
}
