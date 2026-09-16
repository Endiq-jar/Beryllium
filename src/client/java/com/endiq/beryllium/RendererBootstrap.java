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

/**
 * Wires Beryllium's client-side subsystems, isolated from {@link BerylliumClient}'s
 * Android-safe entry point.
 *
 * <p>Deliberately free of any modding-API dependency. Beryllium builds one source tree for
 * every Minecraft release it supports, and the APIs that would otherwise be used here
 * (client lifecycle events, world render events, HUD callbacks) have all been renamed or
 * reshaped somewhere inside that range. Instead:
 * <ul>
 *   <li>"a frame happened" comes from {@code ClientFrameHooks} (a mixin into
 *       {@code Minecraft#runTick});</li>
 *   <li>"the world changed" comes from {@code MinecraftSetLevelMixin};</li>
 *   <li>"the game has started" is the first rendered frame, which is the earliest point
 *       where the window and GL context exist on every launcher;</li>
 *   <li>the debug overlay is registered through Fabric API at runtime if Fabric API happens
 *       to be installed ({@link com.endiq.beryllium.debug.HudOverlayBridge}).</li>
 * </ul>
 */
final class RendererBootstrap {
	private static volatile ShaderPreloader shaderPreloader;

	private RendererBootstrap() {
	}

	static void initialize() {
		// The profiler has no game-class dependencies, so it can be created immediately.
		FrameProfiler profiler = new FrameProfiler();
		ClientFrameHooks.setProfiler(profiler);
		ClientFrameHooks.registerHudOverlay(new DebugOverlay(profiler));

		// Phase 4 — chunk rebuild prioritization. The mixins feed dirty sections into
		// ChunkRebuildQueue; this manager drains a prioritized batch every frame and
		// re-triggers them through vanilla. Cleared on world unload so stale sections
		// never cross into the next world.
		ChunkRebuildManager chunkManager = new ChunkRebuildManager();
		ChunkRebuildManager.setInstance(chunkManager);

		// Phase 8 — shader preload + versioned shader-state cache. init() runs on the first
		// rendered frame (GL context exists by then); the background scan task below runs
		// inside the frame budget.
		ShaderPreloader preloader = new ShaderPreloader();
		ShaderPreloader.setInstance(preloader);
		shaderPreloader = preloader;

		// Phase 3 — frame-budgeted deferred work. Gives FrameBudgetScheduler a live work
		// source: recurring maintenance tasks are submitted every frame and run within a
		// budget derived from the profiler's frame-time stats.
		FrameMaintenanceScheduler maintenance = new FrameMaintenanceScheduler(profiler);
		FrameMaintenanceScheduler.setInstance(maintenance);
		ClientFrameHooks.setMaintenance(maintenance);
		maintenance.addRecurringTask(WorkPriority.LOW, chunkManager::logTelemetryIfDue);
		maintenance.addRecurringTask(WorkPriority.BACKGROUND, preloader::backgroundScanIfDue);
	}

	/**
	 * Runs once, on the first rendered frame, when the window and GL context are guaranteed
	 * to exist. Called from {@link ClientFrameHooks}.
	 */
	static void onClientStarted() {
		ShaderPreloader preloader = shaderPreloader;
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

			// Options are loaded and the camera/game renderer exist by the time a frame has
			// been rendered, which is what the auto-tuner needs. Running it here puts the
			// preset in place before the first world is actually played.
			MobileTuner.applyIfEligible(Beryllium.config(), tier);

			// The GL context is current, so any shader compile done here is earlier than
			// vanilla's first use of the shader.
			if (preloader != null) {
				preloader.init();
			}
		} catch (LinkageError | RuntimeException failure) {
			// A renderer bridge that rejects a native call must leave vanilla's startup
			// alive rather than propagating into the frame.
			BerylliumLog.error("Optional client-start renderer work failed; vanilla rendering "
				+ "continues without Beryllium GPU/shader tuning for this session.", failure);
		}
	}
}
