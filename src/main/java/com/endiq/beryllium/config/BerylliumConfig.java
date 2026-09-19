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

	/** Enables the tick-side optimization suite: hopper throttling, item entity throttling,
	 *  the tick-time governor, and the experimental off-thread ticking
	 *  (async random ticks / parallel entity ticking).
	 *
	 *  <p>Like {@code voxelShapeOptimizations}, this is read at class-load time by
	 *  {@code BerylliumMixinPlugin}, because these features inject into vanilla tick
	 *  methods — a restart is required for changes here to take effect. */
	public boolean tickOptimizations = true;

	// --- Culling ---

	/** Keeps distance-based culling (see {@code cullAggressiveDistance} and
	 *  {@code nameTagCullRange} below) in sync with the player's live "Render Distance"
	 *  video option, via {@code RenderDistanceSync}. When enabled, the effective cull
	 *  distance for each is {@code max(configuredValue, renderDistanceInBlocks)} — so
	 *  raising render distance automatically pushes culling out to match it, and the
	 *  configured values below only ever act as a floor, never a cap that cuts players or
	 *  name tags off closer than the render distance the player actually chose. */
	public boolean cullRangeSyncWithRenderDistance = true;

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
	 *  any further. Acts as a floor, not a fixed value, when
	 *  {@code cullRangeSyncWithRenderDistance} is enabled — see that field. */
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
	 *  reading shop signs on player heads, etc.), so this only trims genuinely far tags.
	 *  Acts as a floor, not a fixed value, when {@code cullRangeSyncWithRenderDistance} is
	 *  enabled — see that field. */
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

	// --- Sign optimization ---

	/** Master switch for Beryllium's sign work: hiding sign text at distance, hiding it
	 *  when the sign is off-screen, and the glowing-text optimizations below. The sign
	 *  board always renders; only the text passes are affected. */
	public boolean signOptimization = true;

	/** Sign text beyond this many blocks is not drawn at all. Text is illegible long
	 *  before this range, while each line is still a full glyph pass with its own
	 *  transform. Set to 0 to always draw sign text. */
	public double signTextCullDistance = 16.0;

	/** Skips sign text for signs that are outside the camera frustum. Off-screen signs
	 *  already skip their board through {@code cullBlockEntities}; this covers the text
	 *  draws, which are dispatched separately from the board model. */
	public boolean signTextHideOutOfView = true;

	/** Enables the glowing-text optimizations below (outline reduction and distance-based
	 *  glow removal). Glowing text is the most expensive text in the game: vanilla draws it
	 *  many times per line to build the outline. */
	public boolean signTextGlowOptimization = true;

	/** Drops the multi-direction outline vanilla draws behind glowing sign text. In
	 *  NORMAL mode one pass per line is kept (it reads as a shadow); in FAST mode the
	 *  outline is removed entirely and the text renders flat. If Beryllium ever observes a
	 *  version where those outline passes *are* the text, it stops dropping them. */
	public boolean signTextHideGlowOutline = true;

	/** {@code NORMAL} = keep one outline pass per line (looks like a shadow, costs one draw
	 *  instead of eight). {@code FAST} = drop every outline pass. */
	public String signTextOutlineMode = "FAST";

	/** Beyond this distance, glowing sign text renders as ordinary (non-glowing) text: no
	 *  outline, no see-through layer. The text itself still draws. */
	public double signTextGlowCullDistance = 24.0;

	// --- Beacon optimization ---

	/** Master switch for beacon beam work. The beacon block itself is a normal block model,
	 *  so everything Beryllium skips here is beam. */
	public boolean beaconOptimization = true;

	/** Stops drawing beacon beams beyond {@code beaconBeamCullDistance}. */
	public boolean beaconBeamHideAtDistance = true;

	/** Beacon beams further than this many blocks from the camera are not drawn. A beam is a
	 *  large soft shape; past a few dozen blocks it contributes almost nothing. */
	public double beaconBeamCullDistance = 64.0;

	/** Stops drawing a beacon beam when no part of its column is on screen. The test covers
	 *  the full height the beam can reach (up to the world's build height), so a beam that
	 *  is visible above a wall still renders. */
	public boolean beaconBeamHideOutOfView = true;

	// --- Chest render culling ---

	/** Skips the chest/ender chest renderer beyond {@code chestRenderCullDistance}. Storage
	 *  rooms are the classic case: hundreds of animated lids, none of them more than a few
	 *  pixels tall, all of them costing a draw call every frame. */
	public boolean chestRenderCulling = true;

	/** Chests (and ender chests) further than this many blocks from the camera are not
	 *  rendered. 0 disables the distance rule. */
	public double chestRenderCullDistance = 24.0;

	// --- Particle culling ---

	/** Drops particles that would be created further than {@code particleCullDistance} from
	 *  the camera. Culling at spawn is the cheap kind: the particle never ticks, never sorts
	 *  into a buffer and never reaches the GPU. */
	public boolean particleCulling = true;

	/** Particles spawned beyond this many blocks from the camera are discarded. 0 disables
	 *  particle culling. */
	public double particleCullDistance = 32.0;

	// --- Entity render distance ---

	/** Hard ceiling on how far away an entity may be and still be rendered. Vanilla has no
	 *  entity render distance at all — it renders every entity in the loaded area. */
	public boolean entityRenderCulling = true;

	/** Entities further than this many blocks from the camera are not rendered. This is a
	 *  cap, not a floor: it is deliberately *not* render-distance-synced by default. 0
	 *  disables the rule. */
	public double entityRenderCullDistance = 64.0;

	/** When true, the entity render distance acts as a floor as well (it can never be closer
	 *  than the player's live Render Distance option). Off by default, because the point of
	 *  this setting is to cap how far out entities are drawn. */
	public boolean entityRenderCullSyncWithRenderDistance = false;

	// --- Visibility culling ---

	/** Remembers, for the duration of one frame, whether a piece of geometry was already
	 *  found to be invisible, so the same question is only answered once. The invisible case
	 *  is the expensive one — it is what makes a room full of chests or signs cost real
	 *  frame time — and it is exactly where repeated processing is pure waste. */
	public boolean visibilityCulling = true;

	// --- Chunk compilation scheduling & upload pacing ---

	/** Spreads GPU uploads of freshly compiled chunk geometry across frames instead of
	 *  letting one frame drain the whole queue. Uploads are deferred, never dropped. */
	public boolean chunkUploadPacing = true;

	/** How many chunk-geometry uploads a single frame may perform (0 = let vanilla drain the
	 *  queue whenever it likes). Halved automatically when the previous frame overran its
	 *  target, and reduced to 1 when a frame was in real trouble. */
	public int chunkUploadsPerFrame = 2;

	// --- Hopper throttling ---

	/** Idle hoppers stop running their full transfer attempt every tick. A hopper counts as
	 *  idle when its contents have not changed across {@code hopperIdleSamples} runs, so
	 *  hoppers that are actually moving items are never throttled. Worst case is one
	 *  interval of extra latency before a newly arriving item is noticed. */
	public boolean hopperThrottling = true;

	/** Run an idle hopper once every this many ticks. 4 (default) means an idle hopper
	 *  attempts a transfer every ~32 ticks instead of every 8; set 1 to disable throttling.
	 *  The tick governor widens this further while the server is behind. */
	public int hopperThrottleInterval = 4;

	/** How many consecutive identical content fingerprints mark a hopper as idle. Raise it to
	 *  be more conservative about throttling, lower it to throttle sooner. */
	public int hopperIdleSamples = 3;

	// --- Item entity throttling ---

	/** Items lying still on the ground tick less often. Items that are moving, burning, in a
	 *  fluid or freshly spawned always tick at full rate. Note that a stationary item's
	 *  despawn timer only advances on ticks that run, so ignored items live proportionally
	 *  longer; set {@code itemEntityThrottleInterval} to 1 to turn this off. */
	public boolean itemEntityThrottling = true;

	/** Tick a stationary ground item once every this many ticks (1 = off). 4 is the default. */
	public int itemEntityThrottleInterval = 4;

	// --- Tick-time governor ("improved TPS") ---

	/** Measures server tick time and scales Beryllium's throttles with it, so tick time stays
	 *  stable instead of collapsing under entity load. Nothing is ever dropped — work is
	 *  spaced out, and it returns to the configured baseline when the server catches up. */
	public boolean tickGovernorEnabled = true;

	/** Tick time (ms) considered healthy; vanilla's own budget is 50 ms. */
	public double targetTickTimeMillis = 45.0;

	/** Upper bound on how far the governor may stretch a throttle interval under full load
	 *  (4.0 = up to 4x the configured interval). */
	public double tickGovernorMaxScale = 4.0;

	// --- Experimental off-thread ticking ---
	//
	// These are the two features that move world work onto other CPU cores. They are on by
	// default, but they are guarded, not hoped-for: before any concurrency happens Beryllium
	// runs a calibration pass on the server thread and proves that its deferred-mutation and
	// per-thread-RNG hooks are live on this Minecraft version. If it cannot prove it, or if
	// a worker ever throws, the features switch themselves off for the session and say so in
	// the log. See README "Experimental off-thread ticking".

	/** Runs vanilla's per-chunk random-tick pass (crop growth, fire, leaf decay, ...) on
	 *  worker threads. Requires the calibration above to succeed; otherwise it stays off. */
	public boolean asyncRandomTicks = true;

	/** Worker threads for async random ticks. 0 = auto (half the cores, at most 4). */
	public int asyncRandomTickThreads = 0;

	/** Spreads one tick's entity work across worker threads: entities are grouped by a 3x3
	 *  chunk colouring so no two groups can touch each other, and every world mutation is
	 *  deferred to the server thread. Requires the calibration above to succeed. */
	public boolean parallelEntityTicking = true;

	/** Worker threads for parallel entity ticking. 0 = auto (half the cores, at most 4). */
	public int parallelEntityTickThreads = 0;

	/** Below this many eligible entities in a tick, entity work stays sequential —
	 *  parallelism that small costs more than it saves. */
	public int parallelEntityTickMinEntities = 32;

	/** How many entities a worker takes per task. Larger = less scheduling overhead, smaller
	 *  = better load balancing. */
	public int parallelEntityTickChunkSize = 8;

	// -------------------------------------------------------------------------------------
	// Client quality-of-life set
	//
	// Every switch below is on. They are the "keep everything enabled" set: each one names the
	// vanilla behaviour it replaces, so turning one off restores exactly that behaviour on the
	// next launch. Nothing here changes world data except deleteToTrash, which only decides
	// where a deleted world goes.
	// -------------------------------------------------------------------------------------

	/** Matches command suggestions that contain the typed text, not only those that start
	 *  with it, and keeps arguments whose id carries no namespace prefix. */
	public boolean improvedCommandSuggestions = true;

	/** Lets a command be longer than the vanilla 256-character limit. Chat messages are not
	 *  affected: the server kicks for those, so that limit is left alone. */
	public boolean commandLengthLimit = true;

	/** Master switch for the chat filters below. */
	public boolean chatFilter = true;

	/** Hides "X has made the advancement Y" and the challenge/goal variants. */
	public boolean chatAnnounceAdvancements = true;

	/** Hides command feedback and other server-generated admin lines. */
	public boolean chatAdminMessages = true;

	/** How many chat lines are kept. Vanilla keeps 100; anything lower is ignored. */
	public int maxChatHistory = 1000;

	/** Merges a repeated chat message into the line before it with a "(xN)" counter. */
	public boolean compactChat = true;

	/** {@code CONSECUTIVE} only folds a repeat of the newest line; {@code ALWAYS} also folds
	 *  a repeat of a line further up. */
	public String compactChatMode = "CONSECUTIVE";

	/** Removes the "unsigned message" marker from chat. */
	public boolean removeUnsignedChatIcon = true;

	/** Moves a deleted world to the operating system's trash instead of erasing it. */
	public boolean deleteToTrash = true;

	/** Skips the "this world uses experimental settings" confirmation when opening a world. */
	public boolean disableWorldAdvice = true;

	/** Allows several keys to be bound to the same action, and one key to several actions. */
	public boolean multipleBindingsPerKey = true;

	/** Stops the controls screen from warning about a key that is part of a combination, or
	 *  about a conflict that is Beryllium's own default. */
	public boolean noReusedModifierKeyWarning = true;

	/** Makes the narrator key rebindable. */
	public boolean remapNarrator = true;

	/** Keeps the reload background out of the way: the loading overlay is never drawn. */
	public boolean removeOverlay = true;

	/** Runs the splash/loading overlay without pausing the game: the world behind it keeps
	 *  ticking and mouse input reaches the game while the overlay is up. */
	public boolean disableSplashScreen = true;

	/** Hides the loading screen's fade-out entirely, which is what makes a reload finish as
	 *  soon as the work is done. */
	public boolean disableLoadingFadeAnimation = true;

	/** Closes the gaps the game leaves between the faces of generated block and item models. */
	public boolean fixModelGaps = true;

	/** Stops the client from adding, ticking and drawing particles. */
	public boolean disableParticles = true;

	/** Freezes animated textures (water, lava, fire, portals) on their first frame. */
	public boolean disableTextureAnimation = true;

	/** Hides advancement, recipe and unverified-chat toasts. */
	public boolean disableToasts = true;

	/** Stops rain and snow from being drawn, and their splash particles and sounds. */
	public boolean disableWeather = true;

	/** Shrinks a title or subtitle that is too wide for the screen instead of drawing it off
	 *  both edges. */
	public boolean fixTitleSize = true;

	/** Fraction of the screen width a title may use. Clamped to 0.1 - 1.0. */
	public double maxTitleWidthFraction = 0.9;

	/** Removes the night vision flicker at the end of the effect. */
	public boolean noNightVisionFlicker = true;

	/** Keeps the current screen open when walking through a portal. */
	public boolean allowScreensInPortals = true;

	/** Skips the "loading terrain" and "reconfiguring" screens. */
	public boolean disableLoadingTerrain = true;

	/** Selects a pack built for another game version without the mismatch screen. */
	public boolean disablePackVersionMismatchScreen = true;

	/** Switches creative inventory tabs on mouse press instead of on mouse release. */
	public boolean fixInventoryTabSwitching = true;

	/** Suppresses the "narrator not available" error on systems without one. */
	public boolean noNarratorError = true;

	/** Keeps the client from reporting telemetry. */
	public boolean noTelemetry = true;

	/** Pauses music while the window is unfocused instead of playing it over everything. */
	public boolean pauseMusic = true;

	/** Removes the fade-in animation of title-screen widgets. */
	public boolean removeWidgetFade = true;

	/** Wraps tooltip lines that would not fit on screen, and keeps the whole tooltip inside
	 *  the screen instead of letting it run off an edge. */
	public boolean tooltips = true;

	/** Maximum tooltip width in pixels. Lines wider than this are wrapped. */
	public int maxTooltipWidth = 320;

	/** Lets server resource packs be moved and disabled like normal packs. */
	public boolean unPinResourcePacks = true;

	/** Lowers the volume of the game while its window is not focused. */
	public boolean unfocusedVolumeReducer = true;

	/** Volume multiplier used while unfocused. 1.0 disables the reduction. */
	public double unfocusedVolume = 0.25;

	/** Lets the game's threads be nudged towards the work that is on the critical path. */
	public boolean threadPriorities = true;

	/** Priority for the client render thread (Java 1-10). */
	public int renderThreadPriority = 7;

	/** Priority for the client worker pools (Java 1-10). */
	public int workerThreadPriority = 5;

	/** Priority for the integrated server thread (Java 1-10). */
	public int serverThreadPriority = 7;

	/** Ticks the dimensions of an integrated server in parallel. Each tick is a barrier: no
	 *  dimension starts the next tick until all of them have finished this one, and the whole
	 *  thing falls back to serial ticking when no safe parallel path is found. */
	public boolean parallelDimensionTicking = true;

	/** Worker threads for parallel dimension ticking. 0 = auto (cores - 2, at most 8). */
	public int parallelDimensionTickThreads = 0;

	/** Parallel dimension ticking only stays engaged while it measures at least this much
	 *  faster than ticking in series. */
	public double parallelDimensionTickMinSpeedup = 1.02;

	/** Renders supported block entities into the terrain while they are idle, and switches
	 *  back to the game's own block entity rendering while they are animating. */
	public boolean blockEntityMeshing = true;

	/** Block entities closer than this are never meshed into the terrain. */
	public double blockEntityMeshMinDistance = 24.0;

	/** How many frames a captured block entity render state stays reusable. */
	public int blockEntityMeshCacheFrames = 40;

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
