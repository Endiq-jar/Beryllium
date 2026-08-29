package com.endiq.beryllium;

import com.endiq.beryllium.capability.GraphicsCapabilityClassifier;
import com.endiq.beryllium.capability.GraphicsCapabilityTier;
import com.endiq.beryllium.chunk.ChunkRebuildManager;
import com.endiq.beryllium.device.DeviceDetector;
import com.endiq.beryllium.device.GpuDetector;
import com.endiq.beryllium.device.GpuInfo;
import com.endiq.beryllium.performance.FrameMaintenanceScheduler;
import com.endiq.beryllium.performance.WorkPriority;
import com.endiq.beryllium.profiler.DebugOverlay;
import com.endiq.beryllium.profiler.FrameProfiler;
import com.endiq.beryllium.shader.ShaderPreloader;
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

		// Profiler + overlay only need Fabric API's event bus, which is available
		// immediately — no need to wait for CLIENT_STARTED.
		FrameProfiler profiler = new FrameProfiler();
		profiler.register();
		new DebugOverlay(profiler).register();

		// Phase 4 — chunk rebuild prioritization, wired into the 1.21.4 section pipeline.
		// The mixins (SectionRenderDispatcherMixin/LevelRendererMixin) feed dirty sections
		// into ChunkRebuildQueue; this manager drains a prioritized batch every rendered
		// frame and re-triggers them through vanilla. Cleared on world unload so stale
		// sections never cross into the next world. (Set the singleton before the frame
		// scheduler below binds its telemetry task.)
		ChunkRebuildManager chunkManager = new ChunkRebuildManager();
		ChunkRebuildManager.setInstance(chunkManager);
		chunkManager.register();

		// Phase 8 — shader preload + versioned shader-state cache. init() itself runs at
		// CLIENT_STARTED (GL context exists by then); the background scan task below runs
		// inside the frame budget.
		ShaderPreloader shaderPreloader = new ShaderPreloader();
		ShaderPreloader.setInstance(shaderPreloader);

		// Phase 3 — frame-budgeted deferred work. Gives FrameBudgetScheduler a live work
		// source: recurring maintenance tasks are submitted every rendered frame and run
		// within a budget derived from the profiler's frame-time stats.
		FrameMaintenanceScheduler maintenance = new FrameMaintenanceScheduler(profiler);
		FrameMaintenanceScheduler.setInstance(maintenance);
		maintenance.register();
		maintenance.addRecurringTask(WorkPriority.LOW, chunkManager::logTelemetryIfDue);
		maintenance.addRecurringTask(WorkPriority.BACKGROUND, shaderPreloader::backgroundScanIfDue);

		// onInitializeClient() runs before the window/GL context exists, so GPU queries
		// can't happen here yet. CLIENT_STARTED fires once the client has fully started
		// (window created, GL context current), which is the earliest safe point.
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

			// CLIENT_STARTED is the first point at which the client is fully up (options
			// loaded, camera/game renderer exist), which is what the auto-tuner and the
			// GPU-based tier decision both need. Running it here means the preset is in
			// place before the first world is rendered.
			MobileTuner.applyIfEligible(Beryllium.config(), tier);

			// Phase 8 — same timing argument: the GL context exists and no world is
			// rendering yet, so any shader compile that happens here is strictly earlier
			// than vanilla's first-use compile (which would otherwise hit during the
			// first world load).
			shaderPreloader.init();
		});
	}
}
