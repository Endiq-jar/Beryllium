package com.endiq.beryllium.culling;

public final class BehindCameraCulling {
	private BehindCameraCulling() {
	}

	/**
	 * @param camX/camY/camZ camera position
	 * @param forwardX/forwardY/forwardZ camera forward direction (need not be
	 *        pre-normalized, but is assumed non-zero)
	 * @param targetX/targetY/targetZ the point being tested (an entity's render position)
	 * @param safeRadius points within this distance of the camera are never culled
	 * @param aggressiveDistance distance at which the threshold reaches its most
	 *        aggressive value; beyond this, the threshold no longer relaxes further
	 * @param dotThresholdNear the (conservative) cull threshold right at safeRadius
	 * @param dotThresholdFar the (aggressive) cull threshold at aggressiveDistance and beyond
	 * @return true if this point should be culled (skipped) as "behind the camera"
	 */
	public static boolean isBehindCamera(
		double camX, double camY, double camZ,
		double forwardX, double forwardY, double forwardZ,
		double targetX, double targetY, double targetZ,
		double safeRadius, double aggressiveDistance,
		double dotThresholdNear, double dotThresholdFar
	) {
		double dx = targetX - camX;
		double dy = targetY - camY;
		double dz = targetZ - camZ;

		double distanceSq = dx * dx + dy * dy + dz * dz;
		if (distanceSq <= safeRadius * safeRadius) {
			return false;
		}

		double forwardLenSq = forwardX * forwardX + forwardY * forwardY + forwardZ * forwardZ;
		if (forwardLenSq == 0.0) {
			// Degenerate/uninitialized forward vector — don't cull, just defer to vanilla.
			return false;
		}

		double distance = Math.sqrt(distanceSq);
		double forwardLen = Math.sqrt(forwardLenSq);
		double dot = (dx * forwardX + dy * forwardY + dz * forwardZ) / (distance * forwardLen);

		double threshold = gradedThreshold(distance, safeRadius, aggressiveDistance, dotThresholdNear, dotThresholdFar);
		return dot < threshold;
	}

	/** Linearly interpolates from dotThresholdNear (at safeRadius) to dotThresholdFar (at aggressiveDistance+), clamped. */
	private static double gradedThreshold(
		double distance, double safeRadius, double aggressiveDistance,
		double dotThresholdNear, double dotThresholdFar
	) {
		double range = Math.max(1e-6, aggressiveDistance - safeRadius);
		double t = (distance - safeRadius) / range;
		t = Math.max(0.0, Math.min(1.0, t));
		return dotThresholdNear + (dotThresholdFar - dotThresholdNear) * t;
	}
}
