package com.endiq.beryllium.profiler;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.chunk.ChunkRebuildManager;
import com.endiq.beryllium.chunk.ChunkUploadPacer;
import com.endiq.beryllium.config.BerylliumConfig;
import com.endiq.beryllium.culling.VisibilityCulling;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

/**
 * Produces the "BERYLLIUM / FPS / 1% Low / 0.1% Low" block from spec section 8 (top-left
 * corner), plus the live server-tick summary when one is available, and — while a world is
 * loaded — one line each for the chunk rebuild queue, the upload pacer and the visibility
 * memo. Only produces lines when {@code debugMode} is on in beryllium.json.
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
		if (client != null && client.level != null) {
			// Live counters for the three render-thread governors. A clean compile only
			// proves a hook resolves; these are what prove it is doing something in game.
			lines.add("");
			lines.add(chunkSchedulingLine());
			lines.add(uploadPacingLine());
			lines.add(visibilityLine());
		}
		return lines;
	}

	private static String chunkSchedulingLine() {
		ChunkRebuildManager manager = ChunkRebuildManager.instance();
		if (manager == null) {
			return "CHUNKS: scheduler not installed";
		}
		long requeued = manager.requeueTotal();
		return String.format("CHUNKS: queued %d, drained %d%s",
			manager.pendingCount(), manager.drainedTotal(),
			requeued == 0 ? "" : ", re-queued " + requeued);
	}

	private static String uploadPacingLine() {
		ChunkUploadPacer pacer = ChunkUploadPacer.instance();
		if (pacer.budget() <= 0) {
			return "UPLOADS: pacing off (vanilla drains freely)";
		}
		long lastNanos = pacer.lastUploadNanos();
		return String.format("UPLOADS: %d/%d this frame, %d deferred, last %.2f ms",
			pacer.used(), pacer.budget(), pacer.deferredTotal(),
			lastNanos < 0 ? 0.0 : lastNanos / 1_000_000.0);
	}

	private static String visibilityLine() {
		long hits = VisibilityCulling.cacheHits();
		long misses = VisibilityCulling.cacheMisses();
		long total = hits + misses;
		if (total == 0) {
			return "VISIBILITY: no frustum queries yet";
		}
		return String.format("VISIBILITY: memo %d%% hit (%d/%d), peak %d entries",
			Math.round(100.0 * hits / total), hits, total,
			VisibilityCulling.largestMemoThisFrame());
	}

	public int lineHeight() {
		Minecraft client = Minecraft.getInstance();
		int fontHeight = client == null || client.font == null ? 9 : client.font.lineHeight;
		return fontHeight + 1;
	}
}
