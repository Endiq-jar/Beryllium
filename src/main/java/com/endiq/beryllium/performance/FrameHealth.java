package com.endiq.beryllium.performance;

/**
 * How expensive the frame that just finished was, relative to what this device normally
 * costs.
 *
 * <p>Every Beryllium governor that decides "may I add work to the frame that is starting
 * now?" answers from this instead of from its own copy of the same thresholds, so the
 * chunk compilation scheduler and the chunk upload pacing cannot drift apart.
 */
public enum FrameHealth {
	/** The frame came in at or under this device's normal cost: spend the full allowance. */
	HEALTHY,

	/** The frame overran its normal cost: spend part of the allowance, keep making progress. */
	OVERRUNNING,

	/** The frame was a spike: spend the bare minimum, but never nothing. */
	IN_TROUBLE
}
