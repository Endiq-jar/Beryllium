package com.endiq.beryllium.performance;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.config.BerylliumConfig;
import com.endiq.beryllium.tune.ClientOptions;
import com.endiq.beryllium.util.BerylliumLog;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

/**
 * Phase 13 — Dynamic FPS technology: stop burning frames nobody is looking at.
 *
 * <p>Vanilla keeps rendering at the full configured framerate while the window is in
 * the background (on a server, ticking and drawing continue at full speed; only
 * singleplayer's pause-on-lost-focus stops it). On the battery-constrained devices
 * Beryllium targets — and on any laptop — that is pure waste: the player alt-tabbed,
 * the GPU and CPU are still pinned, and the machine gets hot and drains.
 *
 * <p>This controller watches the window's focus/iconified state each client tick and
 * lowers vanilla's framerate-limit option while the window is unfocused or minimized,
 * restoring the user's own value the moment focus returns. Properties:
 * <ul>
 *   <li><b>Non-destructive.</b> The player's framerate setting is captured before the
 *       first throttle and written back verbatim on restore; the throttled value is
 *       never persisted to options.txt, so a crash while alt-tabbed cannot leave the
 *       user's settings changed.</li>
 *   <li><b>Never lower than vanilla's own floor.</b> The framerate option is clamped by
 *       the game; the requested value is at least {@code max(10, configured)} so the
 *       write is always in range and cannot be rejected or misread.</li>
 *   <li><b>Invisible in play.</b> While the window is focused, nothing is touched at
 *       all — this cannot cost a single frame or change a single pixel during play,
 *       which is why it composes with "maximum fancy visuals".</li>
 *   <li><b>Vanilla pause still wins.</b> If singleplayer has already paused (no
 *       rendering at all), the option value is irrelevant; we still restore correctly.</li>
 * </ul>
 *
 * <p>Window state is read through the mapped {@code Minecraft.getWindow()} API, which
 * is the one part of the window stack the renderer profile already uses elsewhere;
 * any failure leaves the throttle off.
 */
public final class DynamicFpsController {
	private DynamicFpsController() {
	}

	/** Vanilla's lowest selectable framerate limit; requesting less would be clamped
	 *  (or rejected) by the option itself. */
	private static final int VANILLA_MIN_FRAMERATE = 10;

	private static boolean throttling;
	private static Integer userFramerateLimit;
	private static int lastAppliedLimit = Integer.MIN_VALUE;

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
	}

	public static boolean isThrottling() {
		return throttling;
	}

	/** The framerate limit currently written while throttled, or -1 when not throttling. */
	public static int appliedLimit() {
		return throttling ? lastAppliedLimit : -1;
	}

	private static void tick() {
		BerylliumConfig config = Beryllium.config();
		if (config == null || !config.enabled || !config.dynamicFps) {
			restore();
			return;
		}

		boolean background = isBackgrounded();
		if (background) {
			int limit = Math.max(VANILLA_MIN_FRAMERATE, config.dynamicFpsUnfocusedLimit);
			if (!throttling) {
				// Capture the player's own value exactly once, before the first write.
				Integer current = ClientOptions.readInt("framerateLimit");
				userFramerateLimit = current;
				throttling = true;
				if (current == null) {
					BerylliumLog.debug("[BERYLLIUM-DYNAMIC-FPS] framerateLimit option not readable;"
						+ " Dynamic FPS throttling is inactive on this build.");
				}
			}
			if (lastAppliedLimit != limit) {
				if (ClientOptions.set("framerateLimit", limit)) {
					lastAppliedLimit = limit;
					BerylliumLog.debug("[BERYLLIUM-DYNAMIC-FPS] window backgrounded; framerate limit"
						+ " temporarily lowered to " + limit + ".");
				}
			}
		} else {
			restore();
		}
	}

	private static void restore() {
		if (!throttling) {
			return;
		}
		throttling = false;
		Integer user = userFramerateLimit;
		userFramerateLimit = null;
		lastAppliedLimit = Integer.MIN_VALUE;
		if (user != null) {
			// Deliberately NOT saving options here: the throttled value must never
			// reach options.txt, and the user's value is already on disk.
			ClientOptions.set("framerateLimit", user);
			BerylliumLog.debug("[BERYLLIUM-DYNAMIC-FPS] window focused again; framerate limit"
				+ " restored to " + user + ".");
		}
	}

	/** True when the game window is minimized or not the focused window. */
	private static boolean isBackgrounded() {
		try {
			Minecraft minecraft = Minecraft.getInstance();
			if (minecraft == null || minecraft.getWindow() == null) {
				return false;
			}
			var window = minecraft.getWindow();
			return window.isIconified() || !window.isFocused();
		} catch (Throwable t) {
			return false;
		}
	}
}
