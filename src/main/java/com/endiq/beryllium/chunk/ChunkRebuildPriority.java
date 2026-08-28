package com.endiq.beryllium.chunk;

import com.endiq.beryllium.performance.WorkPriority;

/**
 * Scores pending chunk rebuilds per spec section 10's ordering: player proximity,
 * visibility/camera direction, urgency, distance. Pure math, zero Minecraft dependency —
 * fully unit-testable on its own.
 *
 * <p>Lower score = built sooner.
 */
public final class ChunkRebuildPriority {
	private ChunkRebuildPriority() {
	}

	/** Urgent requests are shifted well below anything a normal score could produce,
	 *  so they always sort first without needing a separate priority lane. */
	private static final double URGENT_BIAS = -1_000_000.0;

	/**
	 * @param camX/camY/camZ camera position
	 * @param forwardX/forwardY/forwardZ camera forward direction (need not be normalized)
	 * @param targetX/targetY/targetZ world-space center of the chunk section being scored
	 * @param urgent see {@link ChunkRebuildRequest#urgent()}
	 */
	public static double score(
		double camX, double camY, double camZ,
		double forwardX, double forwardY, double forwardZ,
		double targetX, double targetY, double targetZ,
		boolean urgent
	) {
		double dx = targetX - camX;
		double dy = targetY - camY;
		double dz = targetZ - camZ;
		double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);

		double forwardLen = Math.sqrt(forwardX * forwardX + forwardY * forwardY + forwardZ * forwardZ);
		double alignment = 0.0; // -1 (behind) .. 0 (side) .. 1 (ahead)
		if (distance > 1e-6 && forwardLen > 1e-6) {
			alignment = (dx * forwardX + dy * forwardY + dz * forwardZ) / (distance * forwardLen);
		}

		// Chunks behind the camera get a distance penalty multiplier so they sort after
		// equally-distant in-view chunks, without ever being starved outright (still
		// finite, still eventually gets built — this ranges 0.5 dead-ahead to 1.5 dead-behind).
		double viewPenalty = 1.0 - (alignment * 0.5);

		double score = distance * viewPenalty;
		return urgent ? score + URGENT_BIAS : score;
	}

	/**
	 * Maps a request's distance from the camera to one of {@link WorkPriority}'s buckets,
	 * for feeding into {@code FrameBudgetScheduler}. Urgent requests always go CRITICAL.
	 */
	public static WorkPriority toWorkPriority(ChunkRebuildRequest request, double distanceFromCamera) {
		if (request.urgent()) {
			return WorkPriority.CRITICAL;
		}
		if (distanceFromCamera < 32.0) {
			return WorkPriority.HIGH;
		}
		if (distanceFromCamera < 64.0) {
			return WorkPriority.NORMAL;
		}
		if (distanceFromCamera < 128.0) {
			return WorkPriority.LOW;
		}
		return WorkPriority.BACKGROUND;
	}
}
