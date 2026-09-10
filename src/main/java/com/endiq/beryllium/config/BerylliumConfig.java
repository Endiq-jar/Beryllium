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

	// --- Launcher safety ---

	/**
	 * Uses Beryllium's conservative startup path when Android or a known Android Java
	 * launcher (Pojav, Zalith, TurtleLauncher-family) is detected. The safe path avoids
	 * early GLFW/OpenGL probes and version-sensitive renderer mixins, which are the two
	 * classes of work most likely to turn a launcher-specific compatibility problem into
	 * a crash before the title screen. Disable only after testing a particular launcher
	 * and renderer combination yourself.
	 */
	public boolean androidSafeMode = true;

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
	 *  shadow everywhere (GUI, name tags, signs, tooltips). See phase 11 in README.md.
	 *
	 *  <p>Phase 12 default flip: {@code false} (no text shadows) is the out-of-the-box
	 *  value now — removing the duplicate shadow pass behind every glyph is one of the
	 *  cheapest pure-overdraw wins available, and the phase-12 posture is maximum FPS
	 *  by default. Set it back to {@code true} in beryllium.json if you want the
	 *  vanilla shadowed look. */
	public boolean textShadowsEnabled = false;

	// --- Fog-wall culling (phase 12) ---

	/** Skips render calls for content sitting in the outermost fringe of the render
	 *  distance, where distance fog has already (nearly) fully hidden it: entity
	 *  models (plus their shadow pass), block-entity renders and name tags beyond the
	 *  fog-wall plane are unreadable either way, so their per-frame CPU/GPU cost is
	 *  pure waste. The plane is derived from the client's own render distance and
	 *  {@code fogCullFactor} — no version-sensitive renderer internals are touched
	 *  (see {@code FogCulling}); anything close to the camera (see
	 *  {@code fogCullSafeRadius}) is never culled.
	 *
	 *  <p>Visual impact: none in normal play — the culled band is where fog colour has
	 *  already replaced the image. With fog disabled by another mod or an extreme
	 *  "no fog" video setting, content pops in at the fog-wall line instead of
	 *  gradually: set {@code fogCullFactor} to {@code 2.0} (or higher) to turn the
	 *  cull off entirely. */
	public boolean cullFogHiddenContent = true;

	/** Where the fog-wall cull plane sits, as a fraction of the render distance
	 *  (render distance in chunks x 16 blocks x this factor). 0.9 = 90% of the render
	 *  distance, inside the band where fog is dense enough that culled content is
	 *  unreadable anyway. Lower = more aggressive (more culled, more visible pop when
	 *  fog is off); {@code >= 2.0} disables the cull. */
	public double fogCullFactor = 0.9;

	/** Hard never-cull zone for {@code cullFogHiddenContent}: anything within this
	 *  many blocks of the camera is never fog-wall-culled, no matter how the plane is
	 *  configured — a close object is a large on-screen object and must never pop. */
	public double fogCullSafeRadius = 12.0;

	// --- Occlusion culling for entities (phase 13) ---

	/** Traces each entity's silhouette against the voxel world and skips its render
	 *  call when solid terrain fully hides it behind opaque blocks — the "hidden
	 *  behind a mountain" class of entities vanilla's frustum-only check still draws
	 *  every frame. Conservative by construction: all five rays must be blocked by a
	 *  run of {@code BlockState#canOcclude} blocks, glass/leaves/water/partial blocks
	 *  never count, glowing entities and the camera entity are never culled, and the
	 *  per-frame raycast budget is bounded ({@code occlusionCullRaycastsPerFrame}).
	 *  Purely a skip of invisible geometry: no visual setting is affected. */
	public boolean cullOccludedEntities = true;

	/** Occlusion culling never applies within this distance of the camera (near
	 *  entities are cheap, important, and the ray is shortest — no point risking a
	 *  visible pop on something the player is standing next to). */
	public double occlusionCullMinDistance = 6.0;

	/** Occlusion culling never applies beyond this distance; farther entities are
	 *  already handled by frustum/fog culling. */
	public double occlusionCullMaxDistance = 64.0;

	/** Hard cap on silhouette raycasts per frame. Each candidate costs at most five
	 *  voxel-march traces, and verdicts are cached for 100 ms, so this bounds the
	 *  render thread's added work to a small, predictable amount per frame regardless
	 *  of how many entities are in the world. Lower it if a very weak CPU shows the
	 *  culling itself as a cost; raise it to cull large crowds sooner. */
	public int occlusionCullRaycastsPerFrame = 16;

	// --- Dynamic FPS (phase 13) ---

	/** Lowers the client framerate limit while the game window is unfocused or
	 *  minimized (the "Dynamic FPS" behaviour), then restores the player's own value
	 *  the moment focus returns. The throttled value is never written to options.txt,
	 *  and while the window is focused nothing is touched at all, so this is invisible
	 *  during play. */
	public boolean dynamicFps = true;

	/** The framerate limit used while the window is backgrounded. Clamped up to
	 *  vanilla's own minimum of 10 (the option's slider floor), so 10 is the lowest
	 *  effective value — still a large saving over rendering at full speed for a
	 *  window nobody is looking at. */
	public int dynamicFpsUnfocusedLimit = 10;

	// --- Max-FPS preset (phase 13) ---

	/** One-shot preset that removes vanilla's non-visual frame costs while leaving
	 *  every fancy visual setting exactly as the player configured it: VSync off,
	 *  framerate limit unlocked, simulation distance at vanilla's minimum (terrain
	 *  still renders at the full render distance; only distant simulation work
	 *  drops). Unlike {@code autoTuneWeakDevices} this never changes particles,
	 *  clouds, shadows, lighting, biome blending or graphics mode. Runs once;
	 *  revert any individual setting in the video settings screen at any time. */
	public boolean fancyMaxFpsPreset = true;

	/** Internal bookkeeping: set to true once {@code fancyMaxFpsPreset} has run, so it
	 *  is only ever applied once. Set false to re-apply. */
	public boolean maxFpsPresetApplied = false;

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
