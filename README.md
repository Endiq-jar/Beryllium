<H1 align="centre">Beryllium</H1>

> [!NOTE]
> AI assistance was used during development due to time while working on TurtleLauncher.

**A Fabric performance mod for Minecraft 1.21.4, focused on making Java Edition run
smoother on Android and other mobile/low-end Java launchers
(PojavLauncher/ZalithLauncher-family) — while still paying off on desktop.**

Beryllium reduces CPU work, removes unnecessary render calls, and tunes the video
settings that matter most on weak devices. It is designed as a lightweight
optimization layer: it composes with Sodium where Sodium can run, and stands alone
where it cannot (OpenGL ES environments, older devices, mod-conflict situations).

✦ Features

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
  blocks). Independent from entity-model culling — a name tag is a billboard that
  keeps costing a draw call even once its owning entity is small/behind-camera-culled.
  In-world block-entity text (signs, hanging signs) doesn't get a separate mechanism;
  it's already covered by the block-entity frustum culler below, since sign text
  renders through the normal block-entity render dispatch.
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
  close to ~93° by 48 blocks out. Crowded farms and mob-heavy servers feel the
  difference most.
- **Frame profiler & debug overlay** — FPS, frame time, 1% low, 0.1% low
  (`debugMode: true`).

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

Targets Minecraft 1.21.4 / Fabric Loader 0.19.3 / Fabric API 0.119.4+1.21.4, Mojang
mappings, Java 21.

```
./gradlew build
```

```
./gradlew runClient
```

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
| `voxelShapeOptimizations` | `true` | Voxel-shape suite (common). **Restart required** — read at class-load time by the mixin plugin |
| `cullBehindCameraEntities` | `true` | Behind-camera entity culling |
| `cullSafeRadius` | `4.0` | Never cull anything within this many blocks, regardless of facing |
| `cullAggressiveDistance` | `48.0` | Distance at which the entity cull angle reaches its most aggressive setting |
| `cullDotThresholdNear` | `-0.6` | Entity cull angle right at the safe radius (conservative, ~127° off-center) |
| `cullDotThresholdFar` | `-0.05` | Entity cull angle at/beyond the aggressive distance (aggressive, ~93° off-center) |
| `cullBlockEntities` | `true` | Frustum-cull block entity render calls |
| `blockEntityCullSafeRadius` | `6.0` | Block entities within this distance are never frustum-culled |
| `cullNameTags` | `true` | Distance-cull entity name tags independently of model culling |
| `nameTagCullRange` | `48.0` | Name tags beyond this many blocks from the camera are skipped |
| `textShadowsEnabled` | `true` | Text drop-shadow toggle — `false` removes the shadow pass behind all text (GUI, name tags, signs, tooltips) |
| `cullLeavesInternalFaces` | `true` | Skip the shared face between two adjacent leaves blocks during meshing |
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

## Compatibility

With `compatibilityModeEnabled: true`, Beryllium defers automatically:

- **Sodium** loaded → Beryllium's chunk-rebuild work stays inert (Sodium owns the
  mesh pipeline). Shape caching and culling remain active (complementary).
- **EntityCulling** loaded → Beryllium's behind-camera entity culling and block
  entity frustum culling are disabled for the session.
- **Lithium** loaded → the voxel-shape suite is kept active unless you disable it in
  the config; the two cache layers are compatible (Beryllium's cache sits in front of
  the same vanilla entry points). If you see odd behavior with both installed, set
  `voxelShapeOptimizations: false` and restart.

## License

MIT — see [LICENSE](LICENSE). Third-party code (Lithium shape suite) is MIT; see
[THIRD_PARTY.md](THIRD_PARTY.md).
