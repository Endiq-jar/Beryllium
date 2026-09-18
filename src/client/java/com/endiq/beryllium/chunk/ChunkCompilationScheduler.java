package com.endiq.beryllium.chunk;

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
 *   <li>a healthy frame gets the configured allowance (default 2);</li>
 *   <li>a frame already over its target gets half of it, so the queue keeps moving without
 *       piling on;</li>
 *   <li>a frame in trouble (more than twice the target) gets exactly one, which keeps
 *       staleness bounded — Beryllium never stops scheduling rebuilds entirely, it just
 *       refuses to make a bad frame worse.</li>
 * </ul>
 */
public final class ChunkCompilationScheduler {
	private static final long TARGET_FRAME_NANOS = 16_666_667L;

	private ChunkCompilationScheduler() {
	}

	/**
	 * @param configuredPerFrame the configured allowance
	 * @param lastFrameNanos     previous frame duration, or a negative value if unknown
	 * @return how many sections to re-trigger this frame (always at least 1)
	 */
	public static int rebuildsThisFrame(int configuredPerFrame, long lastFrameNanos) {
		int configured = Math.max(1, configuredPerFrame);
		if (lastFrameNanos <= 0L) {
			return configured;
		}
		if (lastFrameNanos > TARGET_FRAME_NANOS * 2L) {
			return 1;
		}
		if (lastFrameNanos > TARGET_FRAME_NANOS) {
			return Math.max(1, configured / 2);
		}
		return configured;
	}
}
