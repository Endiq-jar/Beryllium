package com.endiq.beryllium.profiler;

import com.endiq.beryllium.Beryllium;
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
	}
}
