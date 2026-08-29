package com.endiq.beryllium.shader;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.cache.VersionedFileCache;
import com.endiq.beryllium.config.BerylliumConfig;
import com.endiq.beryllium.util.BerylliumLog;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

/**
 * Phase 8 — shader preload + versioned shader-state cache.
 *
 * <p>On mobile/OpenGL-ES devices the first shader compile (UI shader at world load, core
 * render-type shaders at first render) is a visible hitch. This class moves the UI shader
 * preload as early as safely possible — client start, before the first world is ever
 * rendered — and records per-version state in a {@link VersionedFileCache} so repeat
 * launches skip the reflection scan entirely.
 *
 * <p>Everything here is best-effort and reflection-based (the same convention as
 * {@code MobileTuner}): hooks that do not exist in this exact Minecraft version are
 * skipped and logged, never fatal. Notably:
 * <ul>
 *   <li>{@code GameRenderer#preloadUiShader()} — present since 1.20.x, called by vanilla
 *       itself at world load; invoking it earlier just moves the compile earlier.</li>
 *   <li>A scan of {@code GameRenderer}'s fields for a shader map (values named
 *       {@code ShaderInstance} in 1.20.x, {@code ShaderProgram} from the 1.21.2 render
 *       refactor) — discovery + count only; GL compilation stays vanilla-owned.</li>
 * </ul>
 *
 * <p>GL shader *programs* are never written to disk: GPU drivers invalidate compiled
 * programs between sessions, so a disk cache of them would be garbage. Only the
 * preload/scan *state* is cached, keyed by Minecraft version via {@link VersionedFileCache}
 * (stale versions are swept automatically).
 */
public final class ShaderPreloader {
	private static final int CACHE_VERSION = 1;
	private static final String MARKER_KEY = "ui-shader-preload";
	private static final String STATS_KEY = "shader-stats";

	private static volatile ShaderPreloader instance;

	private VersionedFileCache cache;
	private boolean initialized = false;
	private boolean backgroundScanDone = false;

	public static ShaderPreloader instance() {
		return instance;
	}

	public static void setInstance(ShaderPreloader preloader) {
		instance = preloader;
	}

	/** Called from {@code CLIENT_STARTED} (game thread, GL context current, options
	 *  loaded) — the earliest point at which shader preload is safe. */
	public void init() {
		if (initialized) {
			return;
		}
		initialized = true;

		BerylliumConfig config = Beryllium.config();
		if (config == null || !config.enabled || !config.shaderPreloadEnabled) {
			return;
		}

		Path cacheDir = FabricLoader.getInstance().getGameDir().resolve("beryllium-cache").resolve("shaders");
		this.cache = new VersionedFileCache(cacheDir, CACHE_VERSION);
		if (config.shaderCacheEnabled) {
			int swept = cache.invalidateStaleEntries();
			if (swept > 0) {
				BerylliumLog.cache("[BERYLLIUM-SHADER] Swept " + swept + " stale shader-cache entries "
					+ "(from other Minecraft versions).");
			}
		}

		// Skip the whole scan when the state for this exact version is already on disk.
		if (config.shaderCacheEnabled && cache.get(MARKER_KEY).isPresent()) {
			BerylliumLog.cache("[BERYLLIUM-SHADER] UI shader already preloaded for this Minecraft version — skipping repeat scan.");
			return;
		}

		long start = System.nanoTime();
		String preloaded = preloadUiShader();
		long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

		if (preloaded != null) {
			BerylliumLog.gpu("[BERYLLIUM-SHADER] Preloaded UI shader in " + elapsedMs + " ms (" + preloaded + ").");
			if (config.shaderCacheEnabled) {
				cache.put(MARKER_KEY, "preloaded".getBytes(StandardCharsets.UTF_8));
				cache.put(STATS_KEY, ("ui-preload-ms=" + elapsedMs + "\n").getBytes(StandardCharsets.UTF_8));
			}
		} else {
			BerylliumLog.debug("[BERYLLIUM-SHADER] preloadUiShader() not found on this version — vanilla timing applies.");
		}
	}

	/**
	 * Invokes {@code GameRenderer#preloadUiShader()} reflectively.
	 *
	 * @return a short description of what was invoked, or null if the method is not
	 *         present on this version.
	 */
	private String preloadUiShader() {
		try {
			Minecraft minecraft = Minecraft.getInstance();
			if (minecraft == null || minecraft.gameRenderer == null) {
				return null;
			}
			Object gameRenderer = minecraft.gameRenderer;
			Method preload = gameRenderer.getClass().getMethod("preloadUiShader");
			preload.invoke(gameRenderer);
			return gameRenderer.getClass().getSimpleName() + "#preloadUiShader()";
		} catch (ReflectiveOperationException e) {
			return null;
		} catch (RuntimeException e) {
			// Some GL drivers throw during an early shader compile; that is fine — vanilla
			// will re-attempt the compile at its normal point later.
			BerylliumLog.warn("[BERYLLIUM-SHADER] Early UI shader preload failed at GL level: " + e);
			return null;
		}
	}

	/**
	 * Background scan task for the frame-budget scheduler: discovers the core shader set
	 * on {@code GameRenderer} (reflection) and logs it under debug mode. One-shot per
	 * session. Never touches GL state.
	 */
	public void backgroundScanIfDue() {
		if (backgroundScanDone) {
			return;
		}
		backgroundScanDone = true;
		BerylliumConfig config = Beryllium.config();
		if (config == null || !config.enabled || !config.shaderPreloadEnabled) {
			return;
		}

		int shaderCount = 0;
		String shaderField = null;
		try {
			Minecraft minecraft = Minecraft.getInstance();
			if (minecraft == null || minecraft.gameRenderer == null) {
				return;
			}
			Object gameRenderer = minecraft.gameRenderer;
			for (Field field : gameRenderer.getClass().getDeclaredFields()) {
				field.setAccessible(true);
				Object value = field.get(gameRenderer);
				if (value instanceof Map<?, ?> map && !map.isEmpty()) {
					int shaderLike = 0;
					for (Object entry : map.values()) {
						if (entry != null && entry.getClass().getSimpleName().contains("Shader")) {
							shaderLike++;
						}
					}
					if (shaderLike > 0) {
						shaderCount += shaderLike;
						shaderField = field.getName();
						break;
					}
				}
			}
		} catch (Throwable t) {
			BerylliumLog.debug("[BERYLLIUM-SHADER] Background shader scan skipped: " + t);
			return;
		}

		if (shaderCount > 0 && cache != null) {
			cache.put(STATS_KEY, ("discovered-shaders=" + shaderCount + "\n").getBytes(StandardCharsets.UTF_8));
		}
		BerylliumLog.debug("[BERYLLIUM-SHADER] Discovered " + shaderCount + " core shaders"
			+ (shaderField != null ? " (field '" + shaderField + "')" : "") + ".");
	}
}
