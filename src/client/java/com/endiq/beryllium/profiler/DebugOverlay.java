package com.endiq.beryllium.profiler;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.config.BerylliumConfig;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

/**
 * Produces the "BERYLLIUM / FPS / 1% Low / 0.1% Low" block from spec section 8 (top-left
 * corner), plus the live server-tick summary when one is available. Only produces lines
 * when {@code debugMode} is on in beryllium.json.
 *
 * <p>The drawing itself lives in {@code HudOverlayBridge} (Fabric API's HUD callback, when
 * it is present) or in the per-version GUI mixin. That split is deliberate: the text is
 * version-neutral, the drawing API is not, and Beryllium now builds one source tree for
 * every supported release.
 */
public final class DebugOverlay {
	private final FrameProfiler profiler;

	public DebugOverlay(FrameProfiler profiler) {
		this.profiler = profiler;
	}

	/** @return the lines to draw, or an empty list when the overlay is off. */
	public List<String> lines() {
		BerylliumConfig config = Beryllium.config();
		if (config == null || !config.enabled || !config.debugMode) {
			return List.of();
		}
		if (profiler == null) {
			return List.of();
		}

		List<String> lines = new ArrayList<>(8);
		FrameTimeRingBuffer.Snapshot snapshot = profiler.snapshot();
		lines.add("BERYLLIUM");
		lines.add(String.format("FPS: %.0f (%.1f ms)", snapshot.avgFps(), snapshot.avgFrameMillis()));
		lines.add(String.format("1%% Low: %.0f", snapshot.onePercentLowFps()));
		lines.add(String.format("0.1%% Low: %.0f", snapshot.zeroPointOnePercentLowFps()));

		Minecraft client = Minecraft.getInstance();
		if (client != null && client.font != null) {
			// Kept as a hook for future per-subsystem rows; line height is the overlay's
			// concern, the renderer decides where each line lands.
			lines.add("");
		}
		return lines;
	}

	public int lineHeight() {
		Minecraft client = Minecraft.getInstance();
		int fontHeight = client == null || client.font == null ? 9 : client.font.lineHeight;
		return fontHeight + 1;
	}
}
