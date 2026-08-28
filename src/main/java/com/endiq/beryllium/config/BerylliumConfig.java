package com.endiq.beryllium.config;

import com.endiq.beryllium.util.BerylliumLog;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Beryllium's on-disk configuration, stored at {@code config/beryllium.json}.
 *
 * <p>Only the "General" category from the full config plan is implemented here — the
 * other categories (Rendering, Performance, Mobile, Cache, Compatibility) will be added
 * incrementally as their corresponding subsystems are actually built, rather than being
 * stubbed out ahead of time with fields that do nothing.
 */
public class BerylliumConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("beryllium.json");

	/** Master switch. When false, Beryllium logs once and otherwise does nothing. */
	public boolean enabled = true;

	/** Enables verbose [BERYLLIUM-DEBUG] logging and the profiler overlay. */
	public boolean debugMode = false;

	// --- Culling ---

	/** Skips rendering entities that are solidly behind the camera and far enough away
	 *  that popping is not noticeable (see {@code BehindCameraCulling}). */
	public boolean cullBehindCameraEntities = true;

	/** Entities within this many blocks of the camera are never culled by
	 *  {@code cullBehindCameraEntities}, regardless of facing — avoids pop-in for
	 *  anything close enough to matter (mounts, passengers, melee range, etc.).
	 *  Lowered from 8.0 -> 4.0 for more aggressive culling. */
	public double cullSafeRadius = 4.0;

	/** Distance at which the cull angle reaches its most aggressive value (see
	 *  {@code cullDotThresholdFar}). Beyond this distance the threshold doesn't relax
	 *  any further. */
	public double cullAggressiveDistance = 48.0;

	/** Dot product cull threshold used right at {@code cullSafeRadius} — conservative,
	 *  since a close entity that's only slightly off-center is still a big, obvious
	 *  object on screen. -1.0 = directly behind, 0.0 = directly to the side, 1.0 =
	 *  directly ahead. */
	public double cullDotThresholdNear = -0.6;

	/** Dot product cull threshold used at {@code cullAggressiveDistance} and beyond —
	 *  aggressive, since a distant entity that's a bit off from directly-behind is only
	 *  a couple of pixels, and this is where most of the actual savings come from
	 *  (crowded servers, farms, lots of entities at range). */
	public double cullDotThresholdFar = -0.05;

	// --- Compatibility ---

	/** When true, Beryllium checks for known optimization mods at startup and defers
	 *  its own overlapping features to them rather than assuming safe coexistence
	 *  (currently: disables behind-camera entity culling if EntityCulling is loaded). */
	public boolean compatibilityModeEnabled = true;

	public static BerylliumConfig load() {
		if (Files.exists(CONFIG_PATH)) {
			try {
				String json = Files.readString(CONFIG_PATH);
				BerylliumConfig loaded = GSON.fromJson(json, BerylliumConfig.class);
				if (loaded != null) {
					return loaded;
				}
			} catch (IOException | JsonSyntaxException e) {
				BerylliumLog.warn("Could not read beryllium.json (" + e.getMessage()
					+ "); regenerating defaults instead of crashing startup.");
			}
		}

		BerylliumConfig defaults = new BerylliumConfig();
		defaults.save();
		return defaults;
	}

	public void save() {
		try {
			Files.createDirectories(CONFIG_PATH.getParent());
			Files.writeString(CONFIG_PATH, GSON.toJson(this));
		} catch (IOException e) {
			BerylliumLog.warn("Could not write beryllium.json (" + e.getMessage() + ").");
		}
	}
}
