package com.endiq.beryllium;

import com.endiq.beryllium.capability.GraphicsCapabilityClassifier;
import com.endiq.beryllium.capability.GraphicsCapabilityTier;
import com.endiq.beryllium.device.DeviceDetector;
import com.endiq.beryllium.device.GpuDetector;
import com.endiq.beryllium.device.GpuInfo;
import com.endiq.beryllium.profiler.DebugOverlay;
import com.endiq.beryllium.profiler.FrameProfiler;
import com.endiq.beryllium.tune.MobileTuner;
import com.endiq.beryllium.util.BerylliumLog;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;

public class BerylliumClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		if (!Beryllium.config().enabled) {
			return;
		}

		FrameProfiler profiler = new FrameProfiler();
		profiler.register();
		new DebugOverlay(profiler).register();

		ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
			GpuInfo gpu = GpuDetector.detect();

			BerylliumLog.gpu("Vendor: " + gpu.vendor());
			BerylliumLog.gpu("Renderer: " + gpu.renderer());
			BerylliumLog.gpu("Graphics API: " + gpu.graphicsApi());
			BerylliumLog.gpu("OpenGL Version: " + gpu.glVersion());
			BerylliumLog.gpu("Shading Language Version: " + gpu.shadingLanguageVersion());
			BerylliumLog.gpu("Max Texture Size: " + (gpu.maxTextureSize() > 0 ? gpu.maxTextureSize() : "unknown"));
			BerylliumLog.gpu("Display Refresh Rate: "
				+ (gpu.displayRefreshRateHz() > 0 ? gpu.displayRefreshRateHz() + " Hz" : "unknown"));

			GraphicsCapabilityTier tier = GraphicsCapabilityClassifier.classify(
				gpu.maxTextureSize(),
				gpu.renderer() == null ? null : gpu.renderer().toLowerCase(),
				Runtime.getRuntime().availableProcessors(),
				DeviceDetector.detect().totalRamGigabytes()
			);
			BerylliumLog.gpu("Capability Tier: " + tier);

			MobileTuner.applyIfEligible(Beryllium.config(), tier);
		});
	}
}
