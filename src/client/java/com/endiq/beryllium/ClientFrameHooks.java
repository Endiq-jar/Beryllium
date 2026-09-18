package com.endiq.beryllium;

import com.endiq.beryllium.chunk.ChunkRebuildManager;
import com.endiq.beryllium.chunk.ChunkUploadPacer;
import com.endiq.beryllium.culling.CameraAccess;
import com.endiq.beryllium.culling.VisibilityCulling;
import com.endiq.beryllium.debug.HudOverlayBridge;
import com.endiq.beryllium.performance.FrameMaintenanceScheduler;
import com.endiq.beryllium.profiler.DebugOverlay;
import com.endiq.beryllium.profiler.FrameProfiler;
import com.endiq.beryllium.text.SignTextState;
import com.endiq.beryllium.util.BerylliumLog;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * Beryllium's single "once per client frame" entry point.
 *
 * <p>Everything that needs a per-frame clock — the frame profiler, the visibility cache,
 * the chunk rebuild drain, the upload pacer, the frame-budgeted maintenance work — hangs
 * off here rather than off a modding-API render event. That is what lets one source tree
 * build for every supported Minecraft release (and on installs with no Fabric API at all),
 * while keeping exactly one place where "a frame happened" is defined.
 *
 * <p>The hook itself is installed by a mixin into {@code Minecraft#runTick}, which has kept
 * the same name and shape across the whole supported range. If it ever stops matching,
 * Beryllium's per-frame features go quiet instead of breaking the game.
 */
public final class ClientFrameHooks {
	private static volatile FrameProfiler profiler;
	private static volatile FrameMaintenanceScheduler maintenance;
	private static volatile boolean clientStartedWorkDone;
	private static volatile boolean guiOverlayRegistered;
	private static boolean frameInProgress;

	private ClientFrameHooks() {
	}

	public static void setProfiler(FrameProfiler instance) {
		profiler = instance;
	}

	public static void setMaintenance(FrameMaintenanceScheduler instance) {
		maintenance = instance;
	}

	public static void registerHudOverlay(DebugOverlay overlay) {
		if (guiOverlayRegistered) {
			return;
		}
		guiOverlayRegistered = true;
		HudOverlayBridge.register(overlay);
	}

	/** Called at the start of every client tick. */
	public static void onFrameStart() {
		if (frameInProgress) {
			// Guards against the hook being installed twice (two matching injectors).
			return;
		}
		frameInProgress = true;

		try {
			FrameProfiler frameProfiler = profiler;
			if (frameProfiler != null) {
				frameProfiler.onFrameStart();
			}

			// Per-frame caches: sign text state and the visibility memo must not survive
			// into the next frame, even if a renderer forgot to close its scope.
			SignTextState.onFrame();
			VisibilityCulling.onFrameStart();
			ChunkUploadPacer.instance().onFrameStart(frameProfiler == null ? -1L : frameProfiler.lastFrameNanos());

			drainChunkRebuilds(frameProfiler);

			FrameMaintenanceScheduler scheduler = maintenance;
			if (scheduler != null) {
				scheduler.onFrameStart();
			}

			if (!clientStartedWorkDone) {
				clientStartedWorkDone = true;
				// The first rendered frame is the earliest point at which the window and GL
				// context are guaranteed to exist on every launcher — which is exactly what
				// this work needs, and which a modding-API lifecycle event only approximates.
				RendererBootstrap.onClientStarted();
			}
		} catch (Throwable t) {
			// A performance mod must never be the reason a frame does not happen.
			BerylliumLog.debug("[BERYLLIUM] Per-frame hook failed: " + t);
		}
	}

	/** Called at the end of every client tick. */
	public static void onFrameEnd() {
		frameInProgress = false;
	}

	/** Called when the client's level instance changes (world load, or leaving to the menu). */
	public static void onWorldChanged() {
		ChunkRebuildManager manager = ChunkRebuildManager.instance();
		if (manager != null) {
			manager.onWorldUnload();
		}
		VisibilityCulling.onFrameStart();
		SignTextState.onFrame();
	}

	private static void drainChunkRebuilds(FrameProfiler frameProfiler) {
		ChunkRebuildManager manager = ChunkRebuildManager.instance();
		if (manager == null) {
			return;
		}
		try {
			Minecraft minecraft = Minecraft.getInstance();
			if (minecraft == null || minecraft.gameRenderer == null) {
				return;
			}
			Camera camera = minecraft.gameRenderer.getMainCamera();
			if (camera == null) {
				return;
			}
			Vec3 cameraPosition = CameraAccess.position(camera);
			if (cameraPosition == null) {
				return;
			}
			Vector3f look = CameraAccess.lookVector(camera);
			long lastFrameNanos = frameProfiler == null ? -1L : frameProfiler.lastFrameNanos();
			manager.onFrameStart(
				cameraPosition.x, cameraPosition.y, cameraPosition.z,
				look.x(), look.y(), look.z(),
				lastFrameNanos
			);
		} catch (Throwable t) {
			BerylliumLog.debug("[BERYLLIUM] Per-frame hook failed: " + t);
		}
	}
}
