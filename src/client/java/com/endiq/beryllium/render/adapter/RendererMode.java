package com.endiq.beryllium.render.adapter;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.config.BerylliumConfig;
import com.endiq.beryllium.culling.LodRendererCoordination;
import com.endiq.beryllium.util.BerylliumLog;

import java.util.concurrent.atomic.AtomicReference;

/**
 * The single switch that decides who draws the terrain.
 *
 * <p>There is exactly one of these, and that is the point. Beryllium can run either
 * alongside vanilla's renderer or in place of it, and the two must never be drawing the same
 * ground at the same time: the result is not a performance problem to be tuned but
 * z-fighting, doubled geometry and a seam wherever one stops and the other begins. So the
 * decision lives in one atomic reference that both sides read, rather than in two booleans
 * that can disagree.
 *
 * <p>The switch is one-directional in practice. It starts at {@link #VANILLA} and moves to
 * {@link #CUSTOM} only when every precondition holds, and it moves back — permanently, for
 * the session — the moment any of them stops holding. There is no path back to custom after
 * that: a renderer that has already failed once on this device is not a good bet, and
 * flickering between two renderers mid-session is worse than either one alone.
 *
 * <h2>Current status</h2>
 *
 * <p>{@link #CUSTOM} cannot be reached yet, and this is deliberate. The snapshot contract
 * that feeds the engine exists and is tested, but the mesher does not yet translate real
 * Minecraft baked models, there is no Android GLES backend, and nothing uploads or draws the
 * result. Shipping the switch already able to flip would mean shipping a renderer that
 * draws nothing where the world used to be. So {@link #tryEnableCustom()} reports that the
 * native side is not ready and the game continues on vanilla, exactly as it does today.
 */
public enum RendererMode {
	/** Vanilla's renderer draws the terrain. Beryllium's chunk features may assist it. */
	VANILLA,

	/**
	 * Beryllium's own renderer draws the terrain, and vanilla's does not.
	 *
	 * <p>Reaching this state also means Beryllium's vanilla-facing chunk systems — rebuild
	 * prioritisation and upload pacing — stand down, because they schedule work inside a
	 * dispatcher that is no longer being used. Two systems scheduling the same section is how
	 * it gets built twice or not at all.
	 */
	CUSTOM;

	private static final AtomicReference<RendererMode> CURRENT = new AtomicReference<>(VANILLA);

	/** Set once the mode has been retired for the session, so it cannot be retried. */
	private static volatile boolean retired = false;
	private static volatile String retireReason = null;

	/** The mode in force right now. Read by both renderers, every frame. */
	public static RendererMode current() {
		return CURRENT.get();
	}

	public static boolean isCustom() {
		return CURRENT.get() == CUSTOM;
	}

	/**
	 * Whether Beryllium's vanilla-facing chunk systems should run.
	 *
	 * <p>They schedule and pace work inside vanilla's section dispatcher, which is the right
	 * thing to do while vanilla is drawing and the wrong thing to do once it is not. Asking
	 * one question keeps the two systems from ever being active for the same section.
	 */
	public static boolean vanillaChunkSystemsActive() {
		return CURRENT.get() == VANILLA;
	}

	/**
	 * Attempts to move to {@link #CUSTOM}.
	 *
	 * <p>Every precondition is checked, and the first one that fails is reported. The switch
	 * itself is a single compare-and-set, so two callers racing cannot both believe they own
	 * the terrain.
	 *
	 * @return true if custom rendering is now in force
	 */
	public static synchronized boolean tryEnableCustom() {
		if (retired) {
			return false;
		}
		if (CURRENT.get() == CUSTOM) {
			return true;
		}

		BerylliumConfig config = Beryllium.config();
		if (config == null || !config.enabled || !config.customTerrainRenderer) {
			return false;
		}
		if (!LodRendererCoordination.mayRenderTerrain()) {
			retire("another mod already renders the terrain on this install");
			return false;
		}
		if (!BerylliumNative.isReady()) {
			/* Not a failure and not a retirement: the native side is simply not built yet.
			 * The snapshot contract is in place and tested, but the mesher does not translate
			 * real Minecraft models and there is no Android GLES backend, so there is nothing
			 * to draw with. Checked every time rather than retired, because a later phase
			 * makes this true. */
			return false;
		}
		if (!CURRENT.compareAndSet(VANILLA, CUSTOM)) {
			return CURRENT.get() == CUSTOM;
		}
		BerylliumLog.info("[BERYLLIUM] terrain rendering switched to Beryllium's own renderer; "
			+ "vanilla chunk scheduling and upload pacing are standing down");
		return true;
	}

	/**
	 * Returns to vanilla rendering and refuses to leave it again this session.
	 *
	 * <p>Called when the custom renderer fails for any reason — a lost context, a driver
	 * error, a device that turns out not to support what was probed. Falling back is always
	 * correct; falling back and forth is not.
	 */
	public static synchronized void retireCustomRenderer(String reason) {
		retire(reason);
	}

	private static void retire(String reason) {
		if (retired) {
			return;
		}
		retired = true;
		retireReason = reason;
		CURRENT.set(VANILLA);
		BerylliumLog.info("[BERYLLIUM] custom terrain renderer retired for this session: " + reason);
	}

	public static boolean isRetired() {
		return retired;
	}

	public static String retireReason() {
		return retireReason;
	}

	/** Drops the retirement, for tests and for a deliberate re-probe after a video change. */
	public static synchronized void resetForTesting() {
		retired = false;
		retireReason = null;
		CURRENT.set(VANILLA);
	}
}
