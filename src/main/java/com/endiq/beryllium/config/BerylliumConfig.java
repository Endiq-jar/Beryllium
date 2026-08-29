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

	// --- Performance engine (common) ---

	/** Enables the voxel-shape optimization suite: specialized empty/simple-cuboid shape
	 *  types, precomputed coordinate ranges, fast shape-merging, and the
	 *  {@code isShapeFullBlock} cache. These are the dominant CPU consumers in
	 *  collision resolution, pathfinding and block updates.
	 *
	 *  <p>This gate is read at class-load time (via {@code BerylliumMixinPlugin}) because
	 *  mixins cannot be toggled at runtime — a restart is required for changes here to
	 *  take effect. */
	public boolean voxelShapeOptimizations = true;

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

	// --- Block entity culling ---

	/** Skips block entity render calls (signs, banners, item frames, redstone, ...) whose
	 *  bounding box is outside the camera's frustum. This is one of the largest remaining
	 *  rendering costs on top of a full Sodium-style meshing replacement, since vanilla
	 *  renders every block entity within a fixed radius regardless of facing. */
	public boolean cullBlockEntities = true;

	/** Block entities closer than this distance to the camera are never frustum-culled,
	 *  regardless of facing — avoids any chance of something large popping out of view
	 *  while looking at it. */
	public double blockEntityCullSafeRadius = 6.0;

	// --- Text / name tag culling ---

	/** Skips rendering an entity's floating name tag (nameplate) when it's beyond
	 *  {@code nameTagCullRange} of the camera. Independent from entity-model culling —
	 *  a distant entity's model can already be behind-camera-culled while its name tag
	 *  (a screen-space-ish billboard) would otherwise still get drawn every frame it's
	 *  in view. See {@code TextCulling} for the distance check, {@code NameTagCullMixin}
	 *  for the hook. Covers the "Name Tag Culling" / "Text Culling" settings together —
	 *  in-world block-entity text (signs, hanging signs) is already covered by the
	 *  existing {@code cullBlockEntities} frustum culler, since sign text renders through
	 *  the normal block-entity renderer dispatch; it doesn't need a second, separate
	 *  culling mechanism. */
	public boolean cullNameTags = true;

	/** Name tags beyond this many blocks from the camera are skipped entirely. Deliberately
	 *  more generous than {@code cullSafeRadius}/entity-model ranges — legible text at
	 *  range is one of the things players most often want to keep (finding teammates,
	 *  reading shop signs on player heads, etc.), so this only trims genuinely far tags. */
	public double nameTagCullRange = 48.0;

	/** Disables the drop-shadow behind rendered text, trading a small amount of
	 *  legibility for less overdraw in text-heavy scenes. Independently configurable
	 *  from {@code cullNameTags} — one hides text entirely at range, the other makes text
	 *  that IS drawn cheaper.
	 *
	 *  <p>Wired via {@code FontTextShadowMixin}, which suppresses the {@code dropShadow}
	 *  argument of every {@code Font.drawInBatch} overload at its source — the same flag
	 *  vanilla passes on to glyph layout. Setting this to {@code false} disables the text
	 *  shadow everywhere (GUI, name tags, signs, tooltips). See phase 11 in README.md. */
	public boolean textShadowsEnabled = true;

	// --- Leaves culling ---

	/** Skips rendering the shared face between two adjacent leaves blocks (both sides are
	 *  covered by leaves geometry either way, so the hidden face contributes overdraw with
	 *  no visible difference — no holes, since each leaf block still renders its own
	 *  remaining outward faces normally). See {@code LeavesCullMixin}. */
	public boolean cullLeavesInternalFaces = true;

	// --- Chunk rebuild prioritization ---

	/** Reorders vanilla chunk-section rebuilds by proximity + view alignment + urgency
	 *  instead of FIFO. Intercepts the dirty-marking entry points
	 *  ({@code LevelRenderer.setSectionDirty} / {@code SectionRenderDispatcher.setSectionDirty}),
	 *  holds the sections in {@code ChunkRebuildQueue}, and re-triggers a small prioritized
	 *  batch on every rendered frame. The queue has a hard size cap (see
	 *  {@code chunkRebuildQueueLimit}) — beyond it, vanilla's own scheduling takes back
	 *  over, so this can never indefinitely starve a visible section.
	 *
	 *  <p>Disabled automatically when Sodium is loaded (it replaces the mesh pipeline and
	 *  already orders its rebuilds). See phase 4 in README.md. */
	public boolean chunkRebuildPrioritization = true;

	/** How many prioritized section rebuilds are re-triggered per rendered frame. Vanilla
	 *  effectively completes a small fixed number of section builds per frame anyway, so
	 *  this only changes the *order* of the work — values of 2-4 are sensible; larger
	 *  values let the queue drain faster at the cost of more main-thread work per frame. */
	public int chunkRebuildsPerFrame = 3;

	/** Hard cap on the number of sections held in the prioritization queue. When the
	 *  queue reaches this size (e.g. a redstone machine or caving session dirties far more
	 *  sections than the per-frame drain can clear), interception is suspended and vanilla
	 *  schedules directly again — guaranteed bounded staleness. Resumed once the queue
	 *  drops well below the cap. */
	public int chunkRebuildQueueLimit = 128;

	// --- Frame-budgeted deferred work ---

	/** Runs registered low-priority maintenance work inside a per-frame millisecond
	 *  budget (see {@code frameBudgetMillisPerFrame}) driven by {@code FrameBudgetScheduler},
	 *  instead of letting it pile up on the frame's critical path. The CRITICAL priority
	 *  class always runs in full; everything else yields when the budget is spent. */
	public boolean frameBudgetScheduling = true;

	/** Upper bound (in milliseconds) of non-critical work executed per rendered frame by
	 *  {@code frameBudgetScheduling}. The actual budget is min(this, ~10% of the current
	 *  frame time, capped at the 60 FPS frame budget) — so on a fast machine the work gets
	 *  more room, and during a frame-time spike it shrinks to protect the frame. */
	public double frameBudgetMillisPerFrame = 2.0;

	// --- Shader preload & caches ---

	/** Preloads the UI shader as early as safely possible (client-start, rather than
	 *  waiting for the first world load) and discovers the core shader set for the
	 *  session, moving the GL shader compile off the first-frame hitch path where the
	 *  version allows it. Best-effort and reflection-based — if a hook doesn't exist in
	 *  this exact Minecraft version it is skipped and logged, never fatal. */
	public boolean shaderPreloadEnabled = true;

	/** Persists a small per-Minecraft-version state cache (whether the UI shader was
	 *  preloaded, how long it took, discovered shader count) under
	 *  {@code beryllium-cache/shaders} in the game directory, so repeat launches skip the
	 *  reflection scan entirely. GL shader *programs* are never cached to disk — GPU
	 *  drivers invalidate compiled programs between sessions; only the scan/preload state
	 *  is versioned. */
	public boolean shaderCacheEnabled = true;

	// --- Mobile auto-tuning ---

	/** On weak devices (capability tier COMPATIBILITY or STANDARD — the mobile/low-end
	 *  population this mod targets), automatically apply a conservative one-shot preset
	 *  of vanilla video settings (particles=MINIMAL, entity shadows off, clouds off,
	 *  biome blending off, view bobbing off) on first launch. Desktop devices are never
	 *  touched. Every change is logged and written to options.txt, so it is fully
	 *  reversible from the video settings screen. */
	public boolean autoTuneWeakDevices = true;

	/** Internal bookkeeping: set to true once {@code autoTuneWeakDevices} has run, so the
	 *  preset is only ever applied once. Do not edit by hand (set false to re-apply). */
	public boolean autoTuneApplied = false;

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
