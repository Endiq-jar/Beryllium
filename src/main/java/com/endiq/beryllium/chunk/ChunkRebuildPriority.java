package com.endiq.beryllium.chunk;

import com.endiq.beryllium.performance.WorkPriority;

public final class ChunkRebuildPriority {
	private ChunkRebuildPriority() {
	}

	private static final double URGENT_BIAS = -1_000_000.0;

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
		double alignment = 0.0;
		if (distance > 1e-6 && forwardLen > 1e-6) {
			alignment = (dx * forwardX + dy * forwardY + dz * forwardZ) / (distance * forwardLen);
		}

		double viewPenalty = 1.0 - (alignment * 0.5);

		double score = distance * viewPenalty;
		return urgent ? score + URGENT_BIAS : score;
	}

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
