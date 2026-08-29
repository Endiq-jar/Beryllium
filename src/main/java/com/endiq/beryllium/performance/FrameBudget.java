package com.endiq.beryllium.performance;

public final class FrameBudget {
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
}
