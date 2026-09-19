<H1 align="centre">Beryllium</H1>

> [!NOTE]
> AI assistance was used during development due to time while working on TurtleLauncher.

**A Fabric performance and launch-safety mod built for every stable Minecraft Java
Edition release from 1.19.4 through the current release (26.3), focused
on making Java Edition safer and smoother on Android and other mobile/low-end Java
launchers (PojavLauncher/ZalithLauncher/TurtleLauncher-family) — while still paying
off on desktop.**

Every release receives its own exact-version JAR. The GitHub Actions matrix is resolved
from Mojang's official version manifest on every run, so a stable version in this range
cannot be silently skipped. See [Version coverage](#version-coverage) for the profile
and safety guarantees for each artifact.

Beryllium reduces CPU work, removes unnecessary render calls, and tunes the video
settings that matter most on weak devices. It is designed as a lightweight
optimization layer: it composes with Sodium where Sodium can run, and stands alone
where it cannot (OpenGL ES environments, older devices, mod-conflict situations).

✦ Features

> **Verification status of this release:**
> **Compiled and built:** every covered release builds, and each jar passes the metadata
> validator (it checks the Minecraft pin, and that the declared access widener, mixin configs,
> entrypoints and mod classes are really inside the jar). That is what CI enforces on every
> push, and it is how the per-release API differences below were found and fixed.
> **Not yet done:** nobody has *played* it. A clean compile proves the hooks resolve and the
> API calls exist; it cannot prove that a culled sign looked right, that a beacon beam
> reappeared when it should, or that the experimental off-thread ticking is safe under a real
> workload. Treat the first in-game session as the real test, and start with a copy of a world.
> Every injector is `require = 0` and every subsystem is wrapped, so a hook that does not
> match a given release degrades to vanilla behaviour for that feature instead of breaking
> the launch; the ones that currently do that are listed per feature below.

### Performance engine

- **Voxel shape specialization & caching** — the single biggest CPU consumer in
  Minecraft is collision/interaction math on block shapes. Vanilla rebuilds and
  iterates generic shape objects on every check; Beryllium replaces the results of
  `Shapes.create(...)` with specialized immutable types (shared full-block shape,
  shared empty shape, single-cuboid fast paths, bit-aligned cuboids with precomputed
  interior hitbox walls), precomputes coordinate ranges on `CubeVoxelShape`,
  replaces the vanilla shape-merge list with a flat-array implementation (~50% faster
  upstream), adds a fast "does shape A match anywhere in shape B" path, and caches
  `isShapeFullBlock` results. This is the same technology family as Lithium's shape
  work, ported for 1.21.4 (see [THIRD_PARTY.md](THIRD_PARTY.md)).

### Rendering (client)

- **Name tag / text distance culling** — hooks the same `shouldShowName` decision
  vanilla itself uses, and drops name tags beyond `nameTagCullRange` (default 48
  blocks, floor only — see render-distance sync below). Independent from
  entity-model culling — a name tag is a billboard that keeps costing a draw call
  even once its owning entity is small/behind-camera-culled. In-world block-entity
  text (signs, hanging signs) doesn't get a separate mechanism; it's already
  covered by the block-entity frustum culler below, since sign text renders
  through the normal block-entity render dispatch.
- **Leaves internal-face culling** — skips the shared face between two adjacent
  leaves blocks (any combination of leaves types) during meshing. Both sides of that
  face are already covered by leaves geometry either way, so it's pure overdraw with
  zero visual difference — no holes, since each leaf block still renders every face
  that actually borders air or a non-leaves block normally.
- **Chunk rebuild prioritization** — vanilla rebuilds chunk sections in the order their
  dirty marks arrive; during mining, caving or redstone floods that order is essentially
  random relative to the camera. Beryllium intercepts the dirty-marking entry points
  (`LevelRenderer.setSectionDirty` / its 1.21.4 delegate `ViewArea.setDirty`), parks the
  sections in a priority queue (proximity + view alignment + urgency), and re-triggers a
  small prioritized batch (default 3) every rendered frame through vanilla's own
  scheduling — the rebuilds the player can actually see happen first. A hard queue cap
  bounds staleness, and the rebuild work itself always runs on vanilla's machinery.
  Deferred automatically when Sodium is loaded.
- **Text shadows toggle** — `textShadowsEnabled: false` suppresses the `dropShadow`
  argument of every `Font.drawInBatch` overload at its source, removing the duplicate
  shadow pass behind all text (GUI, name tags, signs, tooltips). Slightly flatter text,
  measurably cheaper text-heavy rendering — the kind of trade mobile players want.
- **Block entity frustum culling** — vanilla renders every block entity within a
  fixed radius of the camera regardless of facing. Beryllium skips the render call
  entirely when the block entity's bounding box is outside the camera frustum and
  beyond a small safe radius (default 6 blocks, so nothing near ever pops). Big win
  in scenes full of signs, banners, item frames, beehives, redstone comparators, etc.
- **Behind-camera entity culling ("player culling")** — aggressive, distance-graded:
  a 4-block never-cull radius, with the cull angle relaxing from ~127° off-center up
  close to ~93° by `cullAggressiveDistance` out (default 48 blocks, floor only — see
  render-distance sync below). Crowded farms and mob-heavy servers feel the
  difference most.
- **Render-distance-synced culling** — `cullRangeSyncWithRenderDistance` (default
  `true`) keeps `cullAggressiveDistance` and `nameTagCullRange` from ever kicking in
  closer than the player's live "Render Distance" video option: the effective cull
  distance for each becomes `max(configuredValue, renderDistanceInBlocks)`. Without
  this, raising render distance to see further doesn't change either fixed 48-block
  default, so players and their name tags could disappear tens or hundreds of blocks
  before the terrain itself would. The configured values become a floor rather than a
  cap — turn this off to go back to the flat, unsynced defaults.
- **Frame profiler & debug overlay** — FPS, frame time, 1% low, 0.1% low
  (`debugMode: true`).
- **Entity render distance** — a hard ceiling (`entityRenderCullDistance`, default 64
  blocks) on how far away an entity may be and still be rendered. Vanilla has no entity
  render distance at all: it renders every entity in the loaded area, so crowded servers
  keep paying for entities no player could pick out of the terrain. This is a cap, not the
  floor-style ranges above, so it is deliberately *not* render-distance-synced by default.
- **Particle culling** — particles that would be created beyond `particleCullDistance`
  (default 32 blocks) are dropped at spawn, so they never tick, never sort into a buffer
  and never reach the GPU. Culling at the door is the cheap kind; particles the player can
  actually see are untouched.
- **Chest render culling** — chests, trapped chests and ender chests are not drawn beyond
  `chestRenderCullDistance` (default 24 blocks). Storage rooms are the classic case:
  hundreds of animated lids, none of them more than a few pixels tall, all costing a draw
  call every frame. The rule is applied where every block entity is dispatched, and tests
  the block entity types rather than the renderer classes — ender chests stopped having a
  renderer class of their own entirely, so a per-renderer patch could not cover them.
- **Visibility culling** — one shared frustum, and one answer per block per frame. Vanilla
  asks "is this visible?" repeatedly for the same geometry inside a single frame, and the
  invisible answer is the expensive one — it is exactly what makes a room full of chests
  or signs cost real frame time. Beryllium memoises it (`visibilityCulling`), so that work
  happens once.
- **Chunk compilation scheduling** — how many queued section rebuilds are handed back to
  vanilla in one frame is decided from live frame time, not a constant: a healthy frame
  gets the configured allowance, an overrunning frame gets half, and a frame in trouble
  gets one. The queue always keeps moving; it just refuses to make a slow frame worse, and
  the frame spikes caused by rebuild bursts are spread out instead of landing at once.
- **Chunk upload pacing** — GPU uploads of freshly compiled geometry are distributed
  across frames (`chunkUploadsPerFrame`, default 2) instead of letting one frame drain the
  whole queue after a flight, a world load or a redstone flood. Uploads are deferred, never
  dropped, and the allowance shrinks automatically when frames are already expensive.

### Client quality-of-life

One flat set of switches, all on by default ("keep everything enabled"). Each one names
the vanilla behaviour it replaces, so turning it off restores exactly that behaviour.

**Chat**

- **Improved command suggestions** — arguments that *contain* the typed text match, not
  only arguments that start with it, and suggestions whose id has no namespace prefix
  are kept. Prefix matches still rank above substring matches.
- **Command length** — commands may be longer than vanilla's 256 characters. Chat
  *messages* keep the vanilla limit on purpose: a server kicks for an over-long chat
  message, so raising that would be a way to get kicked, not a feature.
- **Deduplicate ("compact chat")** — a repeated message folds into the line above it
  with a `(xN)` counter, reached through the game's own history so the count survives
  the message being trimmed. `CONSECUTIVE` folds only a repeat of the newest line,
  `ALWAYS` also folds a repeat of a line further up; separator-only lines are never
  folded.
- **Chat filter** — hides "X has made the advancement Y" announcements and the
  server-generated admin/command-feedback lines, decided from the message's translation
  key rather than its text.
- **More history** — the chat keeps `maxChatHistory` lines instead of 100.
- **Unsigned-message icon** — the "cannot be verified" marker is removed.

**Keybinds**

- **Multiple bindings per key** — one action may have several keys and one key may
  drive several actions. Vanilla's arrays assume one key per action; Beryllium keeps its
  own index and applies a key press to every binding it owns, and only if every one of
  them accepted it.
- **No reused-modifier-key warning** — the controls screen stops flagging a key in the
  creative category (the combination keys) and stops flagging a conflict between two
  untouched defaults of one category. Real collisions between player-set keys still warn.
- **Narrator rebinding** — the narrator key is rebindable instead of hardcoded.

**Loading and screens**

- **Remove overlay** — the loading/reload overlay is never installed: nothing is drawn
  over the world, the mouse is not captured by it, and the game stays interactive while
  a reload or a world load runs. `disableSplashScreen` and
  `disableLoadingFadeAnimation` cover the same behaviour from inside the overlay for the
  case where it is still installed.
- **Loading terrain** — the "loading terrain" screen is skipped.
- **Pack version mismatch** — a pack built for another game version can be selected
  without the mismatch screen.
- **World advice** — opening a world that uses experimental settings no longer stops at
  the confirmation screen; the world opens the way the confirmation would have opened
  it. Back up a world before opening it with a newer game version.
- **Inventory tabs** — creative inventory tabs switch on mouse press instead of release.
- **Portal screens** — a screen stays open while walking through a portal.

**Rendering switches**

- **Model gaps** — the inset the game applies to atlas sprites inside a block atlas is
  removed, closing the seams between the faces of generated models.
- **Particles** — particles are not added, ticked or drawn.
- **Texture animation** — animated textures hold their first frame.
- **Toasts** — advancement, recipe and unverified-chat toasts are hidden.
- **Weather** — rain and snow are neither drawn nor given their splash particles and
  sounds.
- **Title size** — a title or subtitle wider than the screen is scaled down instead of
  running off both edges.
- **Tooltips** — tooltip lines wider than `maxTooltipWidth` are wrapped onto several
  lines, which keeps the game's own positioner able to keep the tooltip on screen.
- **Night vision flicker** — the last second of the effect ramps smoothly instead of
  flashing.
- **Widget fade** — the title screen widgets do not fade in.

**Threads and worlds**

- **Thread priorities** — the render, server and worker threads are nudged to the
  priorities in the config. More is not always better: the defaults are close to normal,
  and the tuner re-checks on a timer rather than every frame.
- **Parallel dimension ticking** — an integrated server's dimensions tick in parallel,
  each tick is a barrier so no dimension runs ahead of another, and the whole thing falls
  back to ticking them in series when no safe parallel path was found. Every world
  mutation a worker makes is deferred to the server thread in tick order, so redstone and
  chunk loading see exactly the order they would see in vanilla.
- **Conditional block entity meshing** — supported block entities (signs, hanging signs,
  banners, beds) are drawn by the game while they are animating and reused as a captured
  render state while they are idle and far enough away, which is what makes them
  effectively part of the terrain. Cached states are dropped when the block entity
  reports a change, when the world changes, and after `blockEntityMeshCacheFrames`
  frames.

**Worlds**

- **Delete to trash** — a deleted world is moved to the operating system's trash
  (freedesktop trash on Linux, `~/.Trash` on macOS, a `.trash` folder inside the saves
  directory when neither is available; on Windows the world is deleted as before, because
  there is no portable trash API).
- **Resource packs** — the server's resource packs are no longer pinned in place, so they
  can be moved and disabled like any other pack.
- **Telemetry** — the game is handed a telemetry sender that does nothing.
- **Narrator error** — asking for a narrator on a machine that has none no longer logs an
  error.
- **Paused music** — music stops while the window is unfocused and resumes when it is
  focused again.
- **Unfocused volume** — the game plays at `unfocusedVolume` of its volume while the
  window is unfocused.

### Sign optimization

- **Sign text hidden at distance** — beyond `signTextCullDistance` (default 16 blocks) the
  text is not drawn. The sign *board* still renders; only the illegible glyph passes go.
- **Sign text out of view** — signs outside the camera frustum skip their text draws
  (`signTextHideOutOfView`). The board is already handled by the block entity culler; the
  text is dispatched separately and needed its own rule.
- **Glow hidden at distance** — beyond `signTextGlowCullDistance` (default 24 blocks),
  glowing sign text renders as ordinary text: no see-through layer, no outline. The text
  itself still draws.
- **Glowing-text outline → shadow** — vanilla draws glowing text eight times per line to
  build its outline. `signTextOutlineMode: NORMAL` replaces those eight passes with a single
  darker, offset pass — a shadow, at an eighth of the cost; `FAST` drops the outline
  entirely. If Beryllium ever observes a version where those passes *are* the text, it stops
  dropping them — see
  [Experimental & self-checking behaviour](#experimental--self-checking-behaviour).
- **Which releases** — 1.19.4 through 26.1. Minecraft 26.2 replaced `Font#drawInBatch` with
  `Font#prepareText`, a prepare-then-submit text pipeline that has no per-draw hook to make
  the decision at, so on 26.2 and later sign text renders exactly as vanilla does. Every
  other sign rule (beacon, chest, particles, entity distance, chunk work) still applies
  there.
- **Glow-effect optimization** — the glow layer is identified at the `Font#drawInBatch`
  level, so every text path that renders through it is covered by the same rules rather
  than needing a per-renderer patch.

### Beacon optimization

- **Beacon beams hidden at distance** — beyond `beaconBeamCullDistance` (default 64
  blocks) the beam is not drawn. The beacon block itself is an ordinary block model, so
  everything skipped here is beam.
- **Beams out of view** — the beam is treated as the tall column it actually is (from the
  beacon up to the world's build height) and tested against the frame's frustum
  (`beaconBeamHideOutOfView`). Turning away from a beacon stops costing anything; looking
  at one still shows the whole beam, including the part visible above a wall.
- **Beacon beam optimization** — the beam is the single most expensive small thing in a
  base: a tall translucent column with its own render type, drawn at any distance inside
  the block entity radius. A ring of beacons pays for every beam every frame; these two
  rules are what stop that.

### Server / tick performance

- **Improved TPS under entity load** — a tick-time governor (`tickGovernorEnabled`)
  measures how long the server tick really takes and scales Beryllium's throttles with it,
  so tick time stays stable instead of collapsing when a world fills up. Work is spaced
  out and scaled back automatically when the server catches up; nothing is dropped.
- **Hopper throttling** — hoppers that demonstrably have nothing to do stop running a full
  transfer attempt every tick. Beryllium fingerprints a hopper's contents each time it is
  allowed to run; when the fingerprint has not changed for `hopperIdleSamples` runs the
  hopper is idle and runs once every `hopperThrottleInterval` ticks (default 4). A hopper
  that is actually moving items is never throttled, and the worst case for an idle hopper
  is one interval of extra latency before a newly arriving item is noticed.
- **Item entity throttling** — stationary ground items tick once every
  `itemEntityThrottleInterval` ticks (default 4) instead of every tick. Items that are
  moving, burning, in a fluid or freshly spawned always tick at full rate. Because the
  despawn counter only advances on ticks that run, an ignored item lives proportionally
  longer — it is never permanent, and `1` turns the feature off.
- **Multithreading** — a small worker pool (`BerylliumWorkers`, sized from the machine:
  half the cores, at most 4) used for the parallel work below, with a saturation policy
  that runs tasks on the server thread rather than queueing without bound.
- **Async random ticks (experimental)** — vanilla's per-chunk random-tick pass runs on
  worker threads. Random ticks are crop growth, saplings, grass, fire, ice and leaf decay,
  and they are naturally chunk-shaped. See
  [Experimental & self-checking behaviour](#experimental--self-checking-behaviour) for the
  safety model — it is the reason this can be on by default.
- **Parallel entity processing (experimental)** — one tick's entity work is spread across
  the pool: entities are collected out of `Level#tickEntities`, grouped by a 3x3 chunk
  colouring so no two groups can touch each other, ticked in parallel, and their world
  mutations replayed by the server thread. Players, vehicles, passengers and anything whose
  chunk neighbourhood is not fully loaded always stay on the server thread.

### Mobile performance

- **Automatic low-end video preset** — on devices classified as COMPATIBILITY or
  STANDARD tier (Adreno/Mali/Powervr-class GPUs, < 8 GB RAM, few cores), Beryllium
  applies a one-shot conservative preset on first launch: particles → MINIMAL, entity
  shadows → off, clouds → off, biome blending → off, view bobbing → off. Desktop
  devices are never touched. Everything is logged under `[BERYLLIUM-MOBILE]`, written
  to `options.txt`, and reversible in the video settings screen.
- **OpenGL ES-aware GPU detection & capability tiers** — vendor/renderer/GL version,
  max texture size and display refresh rate are logged and used to pick the
  performance tier.

### Performance / frame management

- **Frame-budgeted deferred work** — `FrameMaintenanceScheduler` gives the frame budget
  scheduler a live work source: registered maintenance tasks (chunk-rebuild telemetry,
  shader background scan, future deferred-buffer disposal) are submitted every rendered
  frame and run inside a budget derived from live frame-time statistics
  (`min(frameBudgetMillisPerFrame, ~10% of frame time, capped at the 60 FPS budget)`).
  CRITICAL-priority work always runs; everything else yields when the budget is spent —
  deferred work never rides the frame's critical path.

### Infrastructure

- **Frame budget scheduler** (`FrameBudgetScheduler`/`FrameBudget`) — per-frame
  millisecond budgets with priority classes, fed by frame-time statistics. **Wired and
  live** via `FrameMaintenanceScheduler` (phase 3).
- **Chunk rebuild prioritization queue** (`ChunkRebuildQueue`/`ChunkRebuildRequest`/
  `ChunkRebuildPriority`/`SectionPacking`) — proximity + view-alignment + urgency
  scoring. **Wired into the 1.21.4 section pipeline** via
  `ChunkRebuildManager` + `ViewAreaMixin`/`LevelRendererMixin` (phase 4). The mixin
  targets (`LevelRenderer.setSectionDirty(int,int,int)` / `(int,int,int,boolean)` and
  `ViewArea.setDirty(int,int,int,boolean)`) are verified against a 1.21.4
  Mojang-mapped decompile; the re-trigger bridge is reflection-based so a future
  signature drift degrades to vanilla behavior instead of crashing.
- **Shader preload & versioned shader-state cache** — `ShaderPreloader` preloads the UI
  shader at client start (before the first world load) and records per-version state
  through `VersionedFileCache`, so repeat launches skip the scan. GL programs are never
  cached to disk (drivers invalidate them); only scan/preload state is versioned.
- **Buffer pooling** (`BufferPool`/`DeferredReleaseQueue`) — pooling infrastructure
  ready for a verified GPU-buffer recycling phase. Note: the 1.21.x renderer replaced
  the per-section `BufferBuilder` pool with a different allocation model, so the
  telemetry mixin that tracked pool misses was dropped rather than shipped unverified.

## Version coverage

Beryllium deliberately builds **one JAR per exact Minecraft release**, each carrying the
full optimization set. Its workflow asks
Mojang's version manifest for every stable numeric release from `1.19.4` through the
manifest's `latest.release`, then compiles and validates the generated `fabric.mod.json`
for each entry. It does not use a manually curated subset that can drift when Mojang ships
a hotfix.

At the time of this update, that means:

```
1.19.4
1.20, 1.20.1, 1.20.2, 1.20.3, 1.20.4, 1.20.5, 1.20.6
1.21, 1.21.1, 1.21.2, 1.21.3, 1.21.4, 1.21.5, 1.21.6,
1.21.7, 1.21.8, 1.21.9, 1.21.10, 1.21.11
26.1, 26.1.1, 26.1.2, 26.2, 26.3
```

That is the full list CI builds, and every entry in it compiles and produces a jar whose
`fabric.mod.json` validates against its own version.

Snapshots and pre-releases are intentionally not called supported releases: their mapping
and renderer changes are not stable enough to make a launch-safety promise. When a later
**stable** release arrives, the CI resolver adds it automatically and the build must pass
before it can be called covered.

### One source tree, every release

Beryllium used to ship a stripped "compatibility core" to every release except one, which
meant most artifacts carried almost none of the mod. That split is gone: **every covered
release now builds the full optimization set from one source tree**, and it stays safe
because of how the mixins are written rather than by withholding them:

| Technique | Why |
|---|---|
| Every injector names its target descriptor explicitly with `require = 0` | A renamed or reshaped method is *skipped*, never guessed. No bad descriptor can crash a launch. |
| Version-only members are reached through access wideners or reflection | `ViewArea`, `Entity#getCommandSenderWorld`, `Level`'s build-height accessor and the shape internals never have to link at compile time. |
| Two era-specific files are swapped by `build.gradle` | `FontSignTextMixin` (the per-draw text hook, which exists up to 26.1) and `ViewAreaMixin` (1.21.2+). Both are excluded per release rather than guessed at. |
| Overloads of the same method are declared side by side | `Font#drawInBatch` changed its return type from `int` to `void` *and back* inside the covered range, and gained and lost an argument. All variants are declared; each release matches exactly one. |
| Reflection for vanilla bodies and cross-class state | `VanillaBridges`, `SectionDirtyBridge` — a drift in a private method or a renamed getter disables one feature instead of failing class transformation. |
| Containment everywhere | Every new hook is wrapped so a throw is logged and vanilla behaviour continues. |

The access widener itself is generated per release (`build.gradle` writes it into
`.gradle/beryllium/`) because its **namespace depends on the release**: 1.19.4–1.21.11 take a `named` widener, while 26.1+ ships unobfuscated code and
Fabric requires an `official` one in the v2 format. The entries are the same
Mojang-named members either way.

What this means per release:

| Artifact target | What loads |
|---|---|
| Every covered release | The full optimization set. Anything whose hook does not match that exact release degrades to vanilla behaviour *for that feature* — visibly, in the log, rather than silently. |
| 26.1+ (the extract/submit renderer) | Block entity and chest culling hook `tryExtractRenderState` instead of the old dispatcher `render` call, because that is where a block entity's draw now comes from. Sign text is the one feature that switches itself off there: 26.2 replaced `Font#drawInBatch` with a prepare-then-submit pipeline that has no per-draw hook to make the decision at. |
| Any release on an Android/Pojav/Zalith/TurtleLauncher host | Still governed by `androidSafeMode`, which suppresses Beryllium's mixins entirely before the title screen. |

No artifact depends on Fabric API on any release (see below).

### Android / TurtleLauncher crash guard

`androidSafeMode` defaults to `true`. Before game mixins are applied, Beryllium uses only
safe JVM properties, environment markers, and standard Android filesystem hints to detect
Android/Pojav/Zalith/TurtleLauncher-style hosts. On a match it suppresses Beryllium's
version-sensitive mixins; the client entry point also returns before creating GPU probes,
shader preloads, or renderer hooks. This avoids both early native GLFW/OpenGL
linkage and renderer-class transformation — the common causes of startup crashes on GL4ES
and other launcher render bridges.

The setting can be set to `false` only for users who have personally tested their precise
launcher, Java runtime, renderer bridge, and Minecraft version. A failed optional
optimization should never prevent Minecraft from reaching the title screen.

## Status

| Phase | State |
|---|---|
| 1 — init, config, device detection, structured logging | ✅ done |
| 2 — frame profiler: FPS / frame time / 1% / 0.1% lows + overlay | ✅ done |
| 3 — frame budget scheduler | ✅ done — wired to a live work source (`FrameMaintenanceScheduler`, per-frame budget from profiler stats) |
| 4 — chunk rebuild prioritization queue | ✅ done — wired into the 1.21.4 section pipeline (`ChunkRebuildManager` + dirty-mark mixins, prioritized per-frame drain) |
| 5 — voxel shape specialization & caching (Lithium-family) | ✅ done |
| 6 — block entity frustum culling | ✅ done |
| 7 — mobile auto-tune preset | ✅ done |
| 8 — shader precompile cache / GPU buffer recycling | ✅ shader preload + versioned shader-state cache live; GL buffer recycling infra remains for a verified phase |
| 9 — name tag / text distance culling | ✅ done |
| 10 — leaves internal-face culling | ✅ done |
| 11 — text shadows toggle | ✅ done — `FontTextShadowMixin` suppresses the `dropShadow` argument of the `Font.drawInBatch` overloads |
| 12 — sign optimization | ✅ done — text hidden at distance / out of view; glow hidden at distance; outline → shadow (`NORMAL`/`FAST`) |
| 13 — beacon optimization | ✅ done — beams hidden at distance and out of view, column-shaped frustum test |
| 14 — chest render culling | ✅ done — chests + ender chests skipped beyond 24 blocks, on every release |
| 15 — particle & entity render distance culling | ✅ done — spawn-distance particle culling; hard entity render-distance ceiling |
| 16 — visibility culling | ✅ done — one shared frustum, one visibility answer per block per frame |
| 17 — chunk compilation scheduling & upload pacing | ✅ done — frame-time-driven rebuild allowance and per-frame GPU upload budget |
| 18 — hopper & item entity throttling | ✅ done — idle hoppers and stationary ground items tick less often |
| 19 — tick-time governor ("improved TPS") | ✅ done — measured tick time scales every throttle, nothing is dropped |
| 20 — worker pool / parallel entity processing | ✅ done — real, but experimental; see below |
| 21 — async random ticks | ✅ done — real, but experimental; see below |

| 22 — client quality-of-life set | ✅ done — chat, keybinds, overlay/loading, rendering switches, worlds, toasts/weather, tooltips, thread priorities |
| 23 — parallel dimension ticking | ✅ done — per-tick barrier, deferred mutations, measured speedup gate, serial fallback |
| 24 — conditional block entity meshing | ✅ done — captured render states for idle block entities on the extract pipeline (1.21.9+); the pre-1.21.9 path keeps the direct cull |
## Experimental & self-checking behaviour

Two features on the list are genuinely risky, and they are the reason this section exists:
**async random ticks** and **parallel entity processing** move world work onto other CPU
cores. Minecraft's world was written for one thread. Doing this naively does not crash — it
produces a subtly wrong world, and occasionally a corrupt one. Beryllium does it like this
instead:

1. **Read-only snapshot.** While a worker runs vanilla code, every world mutation it
   attempts is *recorded*, not performed (`DeferredWorldActions`). Workers see one
   consistent snapshot for the whole task; the server thread replays the recorded mutations
   when the batch joins. Cross-thread reads are never torn.
2. **Per-thread RNG.** Vanilla draws random-tick positions from one shared
   `Level#random`; two threads tearing at one `RandomSource` can produce out-of-range
   values, in the worst case a position in an unloaded chunk. Reads of that field are
   redirected to a per-thread source while a worker is inside `tickChunk` — and only then,
   so a vanilla-shaped random sequence is preserved for every chunk that is not async.
3. **Chunk colouring.** Entities are grouped by `(chunkX mod 3, chunkZ mod 3)`, so two
   chunks in the same round are at least three chunks apart on both axes and their 3x3
   neighbourhoods are disjoint. No entity in one group can touch an entity in another.
4. **Eligibility.** Players, vehicles, passengers, already-removed entities and anything
   whose 3x3 chunk neighbourhood is not fully loaded always stay on the server thread, as
   does every chunk in a world that is raining or thundering (that path can strike
   lightning and spawn entities, not just tick blocks).
5. **Calibration before concurrency.** The first eligible chunk/entity of the session runs
   **on the server thread** with deferral active, while Beryllium performs one deliberately
   harmless mutation (writing a block to the state it already has, with no update flags — a
   no-op either way) and checks whether its hooks actually fired. If the deferral hook or
   the RNG redirect is missing on that Minecraft version, the features disable themselves
   for the session and say so in the log. **Not one concurrent task ever runs un-certified.**
6. **Self-disabling.** Any throw inside a worker permanently turns both features off for the
   session (`DeferredWorldActions#noteFailure`) and logs why. A wrong optimisation must
   never get a second attempt.

The same idea protects the sign outline: if Beryllium ever observes a glowing sign pass
that contains *only* see-through draws — meaning the outline passes **are** the text — it
stops cancelling them, permanently, for that session.

Behavioural differences you can actually observe, stated plainly:

- Mutations made during an async batch become visible to the rest of the game when that
  batch completes, so the exact order in which two distant chunks grow or burn can differ
  from a strictly sequential tick. Nothing is lost or duplicated.
- Parallel-ticked entities are ticked after the rest of the level's entity loop rather than
  in their vanilla position within it.
- A stationary item's despawn timer advances only on ticks that run, so ignored items live
  proportionally longer. They are never permanent.
- An idle hopper that receives an item notices it within one `hopperThrottleInterval`.

If you want the conservative build, set `asyncRandomTicks: false` and
`parallelEntityTicking: false` in `config/beryllium.json` (restart required — they are
applied at class-load time).

> **Verification note (phases 4, 8, 11):** the mixins and hooks added in these phases
> target 1.21.4 internals. Phase 4's targets (`LevelRenderer.setSectionDirty(int,int,int)`
> and `(int,int,int,boolean)`, `ViewArea.setDirty(int,int,int,boolean)` — the
> pre-1.21.2 `SectionRenderDispatcher.setSectionDirty(long,boolean)` no longer exists)
> and phase 11's `Font.drawInBatch` overloads are verified against 1.21.4
> Mojang-mapped decompiles. Phase 8's shader preload calls
> `GameRenderer#preloadUiShader(ResourceProvider)` (1.21.4 signature) reflectively and
> is best-effort by design. Where reflection is used a signature mismatch degrades to
> vanilla behavior instead of crashing — but it also means a wrong guess is silent, so
> if any of these features appears inert in-game, re-check the targeted method
> names/descriptors against a decompile of the exact 1.21.4 build. The pure-Java
> engine pieces (`SectionPacking`, queue/scoring, scheduler) carry no such caveat.

> **Honest note on "Sodium replacement":** Sodium's headline FPS gain comes from
> replacing the entire chunk-meshing and lighting pipeline (per-quad culling, vertex
> packing, dynamic lighting). Beryllium is not that rewrite — it is the
> high-impact, low-risk layer around it: CPU shape math, render-call culling, and
> device-aware settings. Where Sodium can run, run both; where it can't, Beryllium
> still removes real work from every frame.

## Building

Use JDK 25 to build the complete range: Minecraft 26.1+ requires it while Loom prepares
the development jars. Beryllium's own compatibility-core classes are emitted as Java 17
bytecode, the floor needed by Minecraft 1.19.4 and Android Java launchers.

```bash
# Current stable release (the default target at the time of writing)
./gradlew clean build

# Any exact stable release in the supported range
./gradlew clean build -Pminecraft_version=1.19.4
./gradlew clean build -Pminecraft_version=1.21.4
./gradlew clean build -Pminecraft_version=26.2
```

Each output JAR includes that exact version in both its filename and `fabric.mod.json`;
do not install a JAR made for one target into a different Minecraft version. **Every
artifact needs Fabric Loader only** — Beryllium has no Fabric API dependency on any
release (its per-frame clock comes from a mixin into `Minecraft#runTick`, world changes
from `Minecraft#setLevel`, and the debug HUD is registered reflectively when Fabric API
happens to be installed).

```bash
# Runs the development client for the selected target
./gradlew runClient -Pminecraft_version=1.21.4
./gradlew runClient -Pminecraft_version=26.2
```

CI runs the equivalent build for every release resolved from Mojang's manifest and verifies
that the JAR metadata is pinned to the matrix version.

## Standalone engine (`engine/`)

Alongside the mod there is a small dependency-free C11 voxel renderer — the same
ideas Beryllium pushes in Minecraft (greedy meshing, baked skylight/block light
with ambient occlusion, a PVS culler, a mesh store fed by worker threads), in a
form that can be built and *run* without a JVM, Minecraft, or a GPU.

```
make -C engine test            # 2502 checks, no GPU/network/driver needed
make -C engine                 # ./build/beryl renders generated terrain to a PNG
make -C engine ANDROID=1 test  # the phone profile: LTO, --gc-sections, mobile presets
```

Frame budgets for Android/low-end launchers live in the engine too: presets
(`--preset mobile|low-end`) and a governor that widens and narrows the per-frame
mesh-install and upload budgets from measured frame time, ignoring stalls —
`beryl_engine_note_frame_ms()` is the hook a launcher drives it from, so the mod can
use Minecraft's own frame timer.

`engine/README.md` states exactly what is verified and what is not: the software
rasterizer and the OpenGL backend are exercised by tests (the GL one through a
recording loader, since there is no driver here), while the Vulkan slot in the
backend interface is deliberately left unimplemented rather than shipped on
transcribed constants nobody could check.

## Config (`config/beryllium.json`)

| Field | Default | Meaning |
|---|---|---|
| `enabled` | `true` | Master switch |
| `debugMode` | `false` | Verbose logging + the FPS/1%/0.1% overlay |
| `androidSafeMode` | `true` | On Android/Pojav/Zalith/TurtleLauncher-style hosts, suppress native GPU startup work and version-sensitive renderer mixins before the title screen. Disable only after testing the exact launcher/renderer/runtime combination. |
| `voxelShapeOptimizations` | `true` | Voxel-shape suite. **Restart required** — read at class-load time by the mixin plugin |
| `tickOptimizations` | `true` | Tick-side suite: hopper/item throttling, tick governor, async random ticks, parallel entity ticking. **Restart required** — read at class-load time by the mixin plugin |
| `cullRangeSyncWithRenderDistance` | `true` | Keep `cullAggressiveDistance` and `nameTagCullRange` from culling closer than the player's live Render Distance option — effective distance is `max(configuredValue, renderDistanceInBlocks)` |
| `cullBehindCameraEntities` | `true` | Behind-camera entity culling |
| `cullSafeRadius` | `4.0` | Never cull anything within this many blocks, regardless of facing |
| `cullAggressiveDistance` | `48.0` | Distance at which the entity cull angle reaches its most aggressive setting (floor only when `cullRangeSyncWithRenderDistance` is on) |
| `cullDotThresholdNear` | `-0.6` | Entity cull angle right at the safe radius (conservative, ~127° off-center) |
| `cullDotThresholdFar` | `-0.05` | Entity cull angle at/beyond the aggressive distance (aggressive, ~93° off-center) |
| `cullBlockEntities` | `true` | Frustum-cull block entity render calls |
| `blockEntityCullSafeRadius` | `6.0` | Block entities within this distance are never frustum-culled |
| `cullNameTags` | `true` | Distance-cull entity name tags independently of model culling |
| `nameTagCullRange` | `48.0` | Name tags beyond this many blocks from the camera are skipped (floor only when `cullRangeSyncWithRenderDistance` is on) |
| `textShadowsEnabled` | `true` | Text drop-shadow toggle — `false` removes the shadow pass behind all text (GUI, name tags, signs, tooltips) |
| `cullLeavesInternalFaces` | `true` | Skip the shared face between two adjacent leaves blocks during meshing |
| `signOptimization` | `true` | Master switch for sign text/glow optimization |
| `signTextCullDistance` | `16.0` | Sign text beyond this many blocks is not drawn (`0` = always draw) |
| `signTextHideOutOfView` | `true` | Skip sign text for signs outside the camera frustum |
| `signTextGlowOptimization` | `true` | Master switch for the glowing-text optimizations |
| `signTextHideGlowOutline` | `true` | Drop vanilla's multi-pass glowing outline behind sign text |
| `signTextOutlineMode` | `FAST` | `NORMAL` = replace vanilla's eight outline passes with one shadow-like pass; `FAST` = drop all of them |
| `signTextGlowCullDistance` | `24.0` | Beyond this distance, glowing sign text renders as ordinary text |
| `beaconOptimization` | `true` | Master switch for beacon beam work |
| `beaconBeamHideAtDistance` | `true` | Stop drawing beams beyond `beaconBeamCullDistance` |
| `beaconBeamCullDistance` | `64.0` | Beams further than this many blocks are not drawn |
| `beaconBeamHideOutOfView` | `true` | Stop drawing a beam when no part of its column is on screen |
| `chestRenderCulling` | `true` | Skip the chest/ender chest renderer at distance |
| `chestRenderCullDistance` | `24.0` | Chests further than this many blocks are not rendered (`0` = off) |
| `particleCulling` | `true` | Drop particles created beyond `particleCullDistance` |
| `particleCullDistance` | `32.0` | Particles spawned beyond this many blocks are discarded (`0` = off) |
| `entityRenderCulling` | `true` | Hard ceiling on how far away an entity may be and still render |
| `entityRenderCullDistance` | `64.0` | Entities beyond this distance are not rendered (`0` = off) |
| `entityRenderCullSyncWithRenderDistance` | `false` | Treat the above as a floor as well, so it never culls closer than the live Render Distance option |
| `visibilityCulling` | `true` | Reuse one visibility answer per block per frame instead of re-testing geometry |
| `chunkUploadPacing` | `true` | Spread GPU uploads of compiled chunk geometry across frames |
| `chunkUploadsPerFrame` | `2` | Uploads allowed per frame (`0` = let vanilla drain freely); shrinks automatically on slow frames |
| `hopperThrottling` | `true` | Idle hoppers skip their transfer attempt on most ticks |
| `hopperThrottleInterval` | `4` | Run an idle hopper once every this many ticks (`1` = off) |
| `hopperIdleSamples` | `3` | Unchanged-content fingerprints before a hopper counts as idle |
| `itemEntityThrottling` | `true` | Stationary ground items tick less often |
| `itemEntityThrottleInterval` | `4` | Tick a stationary item once every this many ticks (`1` = off) |
| `tickGovernorEnabled` | `true` | Measure server tick time and scale throttles to keep it stable |
| `targetTickTimeMillis` | `45.0` | Tick time considered healthy (vanilla's budget is 50 ms) |
| `tickGovernorMaxScale` | `4.0` | How far the governor may stretch a throttle interval under full load |
| `asyncRandomTicks` | `true` | Experimental: run per-chunk random ticks on worker threads (see the experimental section) |
| `asyncRandomTickThreads` | `0` | Worker threads for async random ticks (`0` = auto: half the cores, max 4) |
| `parallelEntityTicking` | `true` | Experimental: spread one tick's entity work across workers |
| `parallelEntityTickThreads` | `0` | Worker threads for parallel entity ticking (`0` = auto) |
| `parallelEntityTickMinEntities` | `32` | Below this many eligible entities, entity work stays sequential |
| `parallelEntityTickChunkSize` | `8` | Entities per worker task |
| `chunkRebuildPrioritization` | `true` | Reorder chunk-section rebuilds by proximity + view alignment + urgency |
| `chunkRebuildsPerFrame` | `3` | Prioritized rebuilds re-triggered per rendered frame |
| `chunkRebuildQueueLimit` | `128` | Hard cap on the prioritization queue; past it, vanilla schedules directly (bounded staleness) |
| `frameBudgetScheduling` | `true` | Run deferred maintenance work inside a per-frame millisecond budget |
| `frameBudgetMillisPerFrame` | `2.0` | Upper bound of non-critical work per rendered frame (ms) |
| `shaderPreloadEnabled` | `true` | Preload the UI shader at client start and discover the core shader set (best-effort) |
| `shaderCacheEnabled` | `true` | Persist per-Minecraft-version shader preload/scan state under `beryllium-cache/shaders` |
| `autoTuneWeakDevices` | `true` | One-shot low-end video preset on COMPATIBILITY/STANDARD-tier devices |
| `autoTuneApplied` | `false` | Internal: set automatically once the preset has run (set `false` to re-apply) |
| `compatibilityModeEnabled` | `true` | Detect known optimization mods and defer overlapping features (Sodium → chunk work, EntityCulling → entity & block entity culling) |

| `improvedCommandSuggestions` | `true` | Match command arguments that contain the typed text, and keep namespace-less suggestions |
| `commandLengthLimit` | `true` | Allow commands longer than 256 characters (chat messages keep the vanilla limit) |
| `chatFilter` | `true` | Master switch for the two chat filters below |
| `chatAnnounceAdvancements` | `true` | Hide "X has made the advancement Y" announcements |
| `chatAdminMessages` | `true` | Hide command feedback / server-generated admin lines |
| `maxChatHistory` | `1000` | Chat lines kept (values below vanilla's 100 are ignored) |
| `compactChat` | `true` | Fold a repeated chat message into the previous line with a `(xN)` counter |
| `compactChatMode` | `CONSECUTIVE` | `CONSECUTIVE` = fold repeats of the newest line only; `ALWAYS` = fold any repeat still in history |
| `removeUnsignedChatIcon` | `true` | Remove the "unsigned message" marker |
| `deleteToTrash` | `true` | Move a deleted world to the OS trash instead of erasing it |
| `disableWorldAdvice` | `true` | Open a world with experimental settings without the confirmation screen |
| `multipleBindingsPerKey` | `true` | Several keys per action, and several actions per key |
| `noReusedModifierKeyWarning` | `true` | No warning for creative-category keys or for two untouched defaults of one category |
| `remapNarrator` | `true` | Make the narrator key rebindable |
| `removeOverlay` | `true` | The loading/reload overlay is never installed (no background, not paused, interactive) |
| `disableSplashScreen` | `true` | The overlay does not pause the game while it is installed |
| `disableLoadingFadeAnimation` | `true` | Skip the loading fade and end the overlay as soon as the game is ready |
| `disableLoadingTerrain` | `true` | Skip the "loading terrain" screen |
| `disablePackVersionMismatchScreen` | `true` | Select a pack built for another version without the mismatch screen |
| `fixModelGaps` | `true` | Close the seams between faces of generated block/item models |
| `disableParticles` | `true` | Do not add, tick or draw particles |
| `disableTextureAnimation` | `true` | Hold animated textures on their first frame |
| `disableToasts` | `true` | Hide advancement, recipe and unverified-chat toasts |
| `disableWeather` | `true` | No rain/snow drawing, splash particles or rain sounds |
| `fixTitleSize` | `true` | Scale down a title/subtitle that would not fit on screen |
| `maxTitleWidthFraction` | `0.9` | Fraction of the screen width a title may use (clamped to 0.1 - 1.0) |
| `noNightVisionFlicker` | `true` | Smooth ramp instead of the night vision flicker |
| `allowScreensInPortals` | `true` | Keep the current screen open through a portal |
| `fixInventoryTabSwitching` | `true` | Creative inventory tabs switch on mouse press |
| `noNarratorError` | `true` | Do not log an error when no narrator is available |
| `noTelemetry` | `true` | Hand the game a telemetry sender that does nothing |
| `pauseMusic` | `true` | Pause music while the window is unfocused |
| `removeWidgetFade` | `true` | No fade-in for title-screen widgets |
| `tooltips` | `true` | Wrap tooltip lines that are too wide to fit |
| `maxTooltipWidth` | `320` | Tooltip width in pixels before lines are wrapped |
| `unPinResourcePacks` | `true` | Server resource packs can be moved and disabled |
| `unfocusedVolumeReducer` | `true` | Lower the volume while the window is unfocused |
| `unfocusedVolume` | `0.25` | Volume multiplier used while unfocused (`1.0` = off) |
| `threadPriorities` | `true` | Nudge the render/server/worker thread priorities |
| `renderThreadPriority` | `7` | Priority for the client render thread (1-10) |
| `workerThreadPriority` | `5` | Priority for the client worker pools (1-10) |
| `serverThreadPriority` | `7` | Priority for the integrated server thread (1-10) |
| `parallelDimensionTicking` | `true` | Tick the server's dimensions in parallel with a per-tick barrier and a serial fallback |
| `parallelDimensionTickThreads` | `0` | Worker threads for parallel dimension ticking (`0` = auto: cores - 2, max 8) |
| `parallelDimensionTickMinSpeedup` | `1.02` | Parallel ticking only stays engaged while it measures this much faster than serial |
| `blockEntityMeshing` | `true` | Reuse captured block entity render states while they are idle and far away |
| `blockEntityMeshMinDistance` | `24.0` | Block entities closer than this are always drawn by the game |
| `blockEntityMeshCacheFrames` | `40` | How many frames a captured render state stays reusable |

## Compatibility

With `compatibilityModeEnabled: true`, Beryllium defers automatically:

- **Sodium** loaded → Beryllium's chunk-rebuild work stays inert (Sodium owns the
  mesh pipeline). Shape caching and culling remain active (complementary).
- **EntityCulling** loaded → Beryllium's behind-camera entity culling and block
  entity frustum culling are disabled for the session.
- **Lithium** loaded → Beryllium's hopper throttling stands down (Lithium ships its own).
- **Lithium** loaded → the voxel-shape suite is kept active unless you disable it in
  the config; the two cache layers are compatible (Beryllium's cache sits in front of
  the same vanilla entry points). If you see odd behavior with both installed, set
  `voxelShapeOptimizations: false` and restart.

## License

MIT — see [LICENSE](LICENSE). Third-party code (Lithium shape suite) is MIT; see
[THIRD_PARTY.md](THIRD_PARTY.md).
