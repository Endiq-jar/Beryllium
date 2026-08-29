package com.endiq.beryllium.text;

public final class TextCulling {
	private TextCulling() {
	}

	/**
	 * @param camX/camY/camZ camera position
	 * @param targetX/targetY/targetZ the text anchor point (e.g. an entity's position)
	 * @param range beyond this distance the text is culled
	 * @return true if the text should be culled (skipped)
	 */
	public static boolean exceedsRange(
		double camX, double camY, double camZ,
		double targetX, double targetY, double targetZ,
		double range
	) {
		double dx = targetX - camX;
		double dy = targetY - camY;
		double dz = targetZ - camZ;
		double distanceSq = dx * dx + dy * dy + dz * dz;
		return distanceSq > range * range;
	}
}
