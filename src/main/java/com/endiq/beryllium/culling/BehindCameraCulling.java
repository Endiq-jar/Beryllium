package com.endiq.beryllium.culling;

public final class BehindCameraCulling {
	private BehindCameraCulling() {
	}

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

			return false;
		}

		double distance = Math.sqrt(distanceSq);
		double forwardLen = Math.sqrt(forwardLenSq);
		double dot = (dx * forwardX + dy * forwardY + dz * forwardZ) / (distance * forwardLen);

		double threshold = gradedThreshold(distance, safeRadius, aggressiveDistance, dotThresholdNear, dotThresholdFar);
		return dot < threshold;
	}

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
