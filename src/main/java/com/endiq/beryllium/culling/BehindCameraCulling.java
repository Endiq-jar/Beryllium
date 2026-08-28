package com.endiq.beryllium.culling;

/**
 * Pure geometry for "is this point behind the camera and far enough away to safely
 * cull". Deliberately has zero Minecraft/LWJGL dependency so it can be unit-tested on
 * its own — the mixin that calls this is where all the Minecraft-API risk lives.
 */
public final class BehindCameraCulling {
	private BehindCameraCulling() {
	}

	/**
	 * @param camX/camY/camZ camera position
	 * @param forwardX/forwardY/forwardZ camera forward direction (need not be
	 *        pre-normalized, but is assumed non-zero)
	 * @param targetX/targetY/targetZ the point being tested (an entity's render position)
	 * @param safeRadius points within this distance of the camera are never culled
	 * @param dotThreshold cull when the normalized dot product falls below this
	 * @return true if this point should be culled (skipped) as "behind the camera"
	 */
	public static boolean isBehindCamera(
		double camX, double camY, double camZ,
		double forwardX, double forwardY, double forwardZ,
		double targetX, double targetY, double targetZ,
		double safeRadius, double dotThreshold
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
		return dot < dotThreshold;
	}
}
