package com.endiq.beryllium;

import com.endiq.beryllium.capability.GraphicsCapabilityClassifier;
import com.endiq.beryllium.capability.GraphicsCapabilityTier;
import com.endiq.beryllium.chunk.ChunkRebuildManager;
import com.endiq.beryllium.device.DeviceDetector;
import com.endiq.beryllium.diagnostics.HookReport;
import com.endiq.beryllium.performance.DynamicFpsController;
import com.endiq.beryllium.device.GpuDetector;
import com.endiq.beryllium.device.GpuInfo;
import com.endiq.beryllium.performance.FrameMaintenanceScheduler;
import com.endiq.beryllium.performance.WorkPriority;
import com.endiq.beryllium.profiler.DebugOverlay;
import com.endiq.beryllium.profiler.FrameProfiler;
import com.endiq.beryllium.shader.ShaderPreloader;
import com.endiq.beryllium.tune.MaxFpsPreset;
import com.endiq.beryllium.tune.MobileTuner;
import com.endiq.beryllium.util.BerylliumLog;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;

/**
 * The 1.21.4-only renderer bootstrap, deliberately isolated from
 * {@link BerylliumClient}'s Android-safe entry point.
 */
final class RendererBootstrap {
	private RendererBootstrap() {
	}

	static void initialize() {
		// Profiler + overlay only need Fabric API's event bus, which is available
		// immediately — no need to wait for CLIENT_STARTED.
		FrameProfiler profiler = new FrameProfiler();
		profiler.register();
		new DebugOverlay(profiler).register();

		// Phase 13 — Dynamic FPS: drop the framerate limit while the window is in the
		// background, restore it on focus. Pure option reflection; no hooks, no risk.
		DynamicFpsController.register();

		// Phase 4 — chunk rebuild prioritization, wired into the 1.21.4 section pipeline.
		// The mixins (ViewAreaMixin/LevelRendererMixin) feed dirty sections into
		// ChunkRebuildQueue; this manager drains a prioritized batch every rendered
		// frame and re-triggers them through vanilla. Cleared on world unload so stale
		// sections never cross into the next world. Set the singleton before the frame
		// scheduler below binds its telemetry task.
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
		// cannot happen there. CLIENT_STARTED fires once the client has fully started
		// (window created, GL context current), which is the earliest safe point.
		ClientLifecycleEvents.CLIENT_STARTED.register(client -> onClientStarted(shaderPreloader));
	}

	private static void onClientStarted(ShaderPreloader shaderPreloader) {
		try {
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

			// Phase 13 — max-FPS preset (non-visual options only). Runs after the
			// weak-device tuner so the two presets compose: on a weak device the
			// tuner's visual trade-offs apply first and this then removes the
			// non-visual frame costs; on a strong device only this preset applies.
			MaxFpsPreset.applyIfEligible(Beryllium.config());

			// Phase 13 — hook self-diagnosis. Mixins are applied by now (the client is
			// fully started), so this is the first point where "did Beryllium's hooks
			// actually attach to this exact Minecraft build?" can be answered. Logged
			// unconditionally: an inert hook set is the number-one cause of "the mod
			// looks like it does nothing", and that must be visible in every log.
			HookReport.runAndLog();

			// Same timing argument: the GL context exists and no world is rendering yet,
			// so any shader compile that happens here is earlier than vanilla's first use.
			shaderPreloader.init();
		} catch (LinkageError | RuntimeException failure) {
			// The callback runs after the normal entry point has returned, so it needs its
			// own containment boundary. A renderer bridge that rejects a native call must
			// leave vanilla's startup alive rather than propagating into Fabric's event bus.
			BerylliumLog.error("Optional client-start renderer work failed; vanilla rendering "
				+ "continues without Beryllium GPU/shader tuning for this session.", failure);
		}
	}
}
