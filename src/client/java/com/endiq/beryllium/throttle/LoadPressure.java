package com.endiq.beryllium.throttle;

/**
 * How busy the world-loading machinery is right now, and what that entitles entity
 * rendering to.
 *
 * <p>Dynamic Load Throttling exists because terrain and entities compete for the same
 * frame, and they do not compete fairly. Terrain generation is on the critical path: until
 * a section is meshed and uploaded there is a hole in the world, and the player can see it.
 * A distant entity that is not drawn this frame is not a hole — it is one less thing to
 * draw. So when the world is streaming in, entity rendering gives ground.
 *
 * <p>The levels are ordered and the scale is monotonic, which is what lets the monitor
 * apply hysteresis by moving one step at a time instead of snapping between extremes.
 */
public enum LoadPressure {
	/**
	 * Nothing is backing up. Entity rendering keeps its full configured range.
	 *
	 * <p>This is the normal state and the one the throttle returns to: the scale is exactly
	 * 1.0, so an idle world behaves as though this feature did not exist.
	 */
	CALM(1.0),

	/**
	 * Work is queuing but the frame is keeping up. Trim the far end of entity rendering,
	 * where the player is least likely to notice and the savings are largest.
	 */
	BUSY(0.75),

	/**
	 * Sections are backing up faster than they are being drained, or frames are spiking.
	 * Terrain needs the whole budget.
	 */
	LOADED(0.5),

	/**
	 * A teleport, a dimension change, or a world load: essentially the whole visible area
	 * has to be generated at once. Entity rendering is cut to the near field until the
	 * stream catches up.
	 */
	SURGED(0.3);

	private final double distanceScale;

	LoadPressure(double distanceScale) {
		this.distanceScale = distanceScale;
	}

	/**
	 * Multiplier applied to a configured entity cull distance.
	 *
	 * <p>Always in (0, 1]: the throttle shortens the distance at which entities stop being
	 * drawn, it never extends it, and it never scales to zero. Scaling to zero would mean
	 * "draw no entities at all", which is a visible glitch rather than an optimisation.
	 */
	public double distanceScale() {
		return distanceScale;
	}

	/** One step towards {@code CALM}, or this level if already there. */
	public LoadPressure ease() {
		LoadPressure[] all = values();
		int index = ordinal();
		return index == 0 ? this : all[index - 1];
	}

	/** The more severe of two levels. */
	public static LoadPressure worse(LoadPressure a, LoadPressure b) {
		if (a == null) {
			return b == null ? CALM : b;
		}
		if (b == null) {
			return a;
		}
		return a.ordinal() >= b.ordinal() ? a : b;
	}
}
