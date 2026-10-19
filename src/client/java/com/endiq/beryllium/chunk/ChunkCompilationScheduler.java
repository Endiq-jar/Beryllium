package com.endiq.beryllium.chunk;

import com.endiq.beryllium.performance.FrameBudget;
import com.endiq.beryllium.performance.FrameHealth;

/**
 * Chunk compilation scheduling: decides how many queued rebuilds to hand back to vanilla
 * in one frame.
 *
 * <p>Beryllium's rebuild queue decides <em>which</em> sections to rebuild first (proximity,
 * view alignment, urgency). This decides <em>how many</em> to start at once, which is the
 * other half of not spiking: a burst of re-triggered rebuilds is a burst of meshing work
 * competing with the frame that is being drawn right now.
 *
 * <p>The rule is a frame-time governor, not a constant:
 * <ul>
 *   <li>a healthy frame gets the configured allowance;</li>
 *   <li>a frame that overran this device's normal cost gets half of it, so the queue keeps
 *       moving without piling on;</li>
 *   <li>a frame that was a spike gets exactly one, which keeps staleness bounded —
 *       Beryllium never stops scheduling rebuilds entirely, it just refuses to make a bad
 *       frame worse.</li>
 * </ul>
 *
 * <p>"Normal cost" is this device's own rolling average frame time, not a fixed 60 Hz
 * (see {@link FrameBudget#targetFrameNanosFor(long)}). A low-end phone that runs at 30 FPS
 * is not having a bad frame every frame; judging it against 16.7 ms would have pinned this
 * scheduler at one rebuild per frame forever, which is the opposite of spreading a burst
 * out — it turns every burst into a queue that never drains.
 */
public final class ChunkCompilationScheduler {
	private ChunkCompilationScheduler() {
	}

	/**
	 * @param configuredPerFrame the configured allowance
	 * @param lastFrameNanos     duration of the frame that just finished, or a non-positive
	 *                           value if it has not been measured
	 * @return how many sections to re-trigger this frame (always at least 1)
	 */
	public static int rebuildsThisFrame(int configuredPerFrame, long lastFrameNanos) {
		return rebuildsThisFrame(configuredPerFrame, lastFrameNanos, -1L);
	}

	/**
	 * @param configuredPerFrame the configured allowance
	 * @param lastFrameNanos     duration of the frame that just finished, or a non-positive
	 *                           value if it has not been measured
	 * @param averageFrameNanos  this device's rolling average frame time, or a non-positive
	 *                           value if it has not been measured yet
	 * @return how many sections to re-trigger this frame (always at least 1)
	 */
	public static int rebuildsThisFrame(int configuredPerFrame, long lastFrameNanos, long averageFrameNanos) {
		int configured = Math.max(1, configuredPerFrame);
		FrameHealth health = FrameBudget.healthOf(lastFrameNanos, averageFrameNanos);
		return switch (health) {
			case IN_TROUBLE -> 1;
			case OVERRUNNING -> Math.max(1, configured / 2);
			case HEALTHY -> configured;
		};
	}
}
