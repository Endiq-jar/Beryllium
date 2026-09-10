package com.endiq.beryllium.profiler;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.culling.FogCulling;
import com.endiq.beryllium.culling.OcclusionCulling;
import com.endiq.beryllium.diagnostics.HookReport;
import com.endiq.beryllium.performance.DynamicFpsController;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Draws the "BERYLLIUM / FPS / 1% Low / 0.1% Low" block from spec section 8, top-left
 * corner. Only draws when {@code debugMode} is on in beryllium.json.
 */
public final class DebugOverlay {
	private final FrameProfiler profiler;

	public DebugOverlay(FrameProfiler profiler) {
		this.profiler = profiler;
	}

	public void register() {
		HudRenderCallback.EVENT.register((guiGraphics, tickDelta) -> render(guiGraphics));
	}

	private void render(GuiGraphics guiGraphics) {
		if (!Beryllium.config().enabled || !Beryllium.config().debugMode) {
			return;
		}

		Minecraft client = Minecraft.getInstance();
		FrameTimeRingBuffer.Snapshot snapshot = profiler.snapshot();

		int x = 4;
		int y = 4;
		int lineHeight = client.font.lineHeight + 1;
		int color = 0xFFFFFF;

		guiGraphics.drawString(client.font, "BERYLLIUM", x, y, color);
		y += lineHeight;
		guiGraphics.drawString(client.font,
			String.format("FPS: %.0f (%.1f ms)", snapshot.avgFps(), snapshot.avgFrameMillis()), x, y, color);
		y += lineHeight;
		guiGraphics.drawString(client.font,
			String.format("1%% Low: %.0f", snapshot.onePercentLowFps()), x, y, color);
		y += lineHeight;
		guiGraphics.drawString(client.font,
			String.format("0.1%% Low: %.0f", snapshot.zeroPointOnePercentLowFps()), x, y, color);
		y += lineHeight;

		// Phase 12/13 — culling receipts: how many render calls were skipped this
		// session because the fog hid them, and how many entities were proven hidden
		// behind solid terrain by the occlusion tracer. A cheap way to confirm the
		// culls are doing work (and a diagnostic if they ever look inert).
		long fogEntities = FogCulling.hiddenEntities();
		long fogBlockEntities = FogCulling.hiddenBlockEntities();
		long fogNameTags = FogCulling.hiddenNameTags();
		if (fogEntities + fogBlockEntities + fogNameTags > 0) {
			guiGraphics.drawString(client.font, String.format(
				"Fog-culled: %d entities, %d block entities, %d name tags",
				fogEntities, fogBlockEntities, fogNameTags), x, y, color);
			y += lineHeight;
		}

		long occluded = OcclusionCulling.occludedEntities();
		long raycasts = OcclusionCulling.raycasts();
		if (raycasts > 0) {
			guiGraphics.drawString(client.font, String.format(
				"Occlusion-culled: %d entities (%d raycasts)", occluded, raycasts), x, y, color);
			y += lineHeight;
		}

		// Phase 13 — hook self-diagnosis: how many mixin hooks actually attached on
		// this build. Fewer than all means some features are silently inactive here
		// (see the log for the per-hook table).
		guiGraphics.drawString(client.font, HookReport.summaryLine(), x, y, color);
		y += lineHeight;

		if (DynamicFpsController.isThrottling()) {
			guiGraphics.drawString(client.font,
				"Dynamic FPS: throttled to " + DynamicFpsController.appliedLimit() + " (window unfocused)",
				x, y, color);
		}
	}
}
