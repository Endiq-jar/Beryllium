<H1 align="centre">Beryllium</H1>

> [!NOTE]
> AI assistance was used during development due to time while working on TurtleLauncher.

**A Fabric performance and launch-safety mod built for every stable Minecraft Java
Edition release from 1.19.4 through the current release (26.2 on 2026-09-07), focused
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
- **Fog-wall culling** — the outermost fringe of the render distance is already
  (nearly) fully replaced by distance fog, yet vanilla still pays full render calls
  for entity models (plus their shadow passes), block entities and name tags out
  there. Beryllium skips content beyond a fog-wall plane derived from the client's
  own render distance (`fogCullFactor`, default 0.9) with a hard never-cull zone
  (`fogCullSafeRadius`, default 12 blocks). No version-sensitive renderer internals
  are touched — it reuses the same verified `shouldRender`/block-entity/name-tag
  hook points and pure distance math, so it cannot break a version at load. In
  normal play there is no visible difference (the fog already hid it); the debug
  overlay counts every skipped call per session.
- **Occlusion culling for entities** — "hidden behind a mountain" entities are the one
  class of invisible geometry vanilla's frustum-only check never catches. Beryllium
  traces each entity's silhouette (center + four box corners) against the voxel world
  and skips the render call when every ray passes through opaque blocks. Conservative
  by construction: only `BlockState#canOcclude` full-solid blocks count (glass, leaves,
  water, fences and partial shapes never hide anything), glowing entities and the
  camera entity are never culled, near/far distance limits apply, at most
  `occlusionCullRaycastsPerFrame` (default 16) silhouettes are traced per frame, and
  verdicts are cached for 100 ms. Nothing visual is affected — only fully hidden
  models are skipped.
- **Dynamic FPS** — while the game window is unfocused or minimized, Beryllium lowers
  the framerate limit (`dynamicFpsUnfocusedLimit`, default 10 — vanilla's floor),
  restores the player's own value on focus, and never writes the throttled value to
  `options.txt`. Invisible during play; large battery/heat savings in the background.
- **Max-FPS preset** — a one-shot preset that removes vanilla's *non-visual* frame
  costs while leaving every fancy visual setting exactly as configured: VSync off,
  framerate limit unlocked, simulation distance at vanilla's minimum (terrain still
  renders at the full render distance; only distant simulation work drops). It never
  touches particles, clouds, shadows, lighting, biome blending or graphics mode.
- **Runtime hook self-diagnosis** — every mixin hook is probed at client start and
  logged as `[applied]`/`[MISSING]`, with a summary line in the debug overlay
  (`Hooks: N/M applied`). This exists because `require = 0` hooks fail *silently* by
  design; without the report, a user cannot tell "Beryllium is optimizing" from "every
  hook drifted on this build".
- **No-mercy defaults** — phase 12 posture is maximum FPS out of the box:
  `textShadowsEnabled` now defaults to `false` (removes the duplicate glyph shadow
  pass behind all text — the cheapest pure-overdraw win available), and the
  low-end auto-tune additionally caps render distance per device tier (6 chunks on
  COMPATIBILITY, 10 on STANDARD), the single biggest FPS lever on weak GPUs.
- **Frame profiler & debug overlay** — FPS, frame time, 1% low, 0.1% low
  (`debugMode: true`).

### Mobile performance

- **Automatic low-end video preset** — on devices classified as COMPATIBILITY or
  STANDARD tier (Adreno/Mali/Powervr-class GPUs, < 8 GB RAM, few cores), Beryllium
  applies a one-shot conservative preset on first launch: particles → MINIMAL, entity
  shadows → off, clouds → off, biome blending → off, view bobbing → off. Desktop
  devices are never touched. Everything is logged under `[BERYLLIUM-MOBILE]`, written
  to `options.txt`, and reversible in the video settings screen.
- **Compatibility-core option tuning (every supported version)** — the compatibility
  core contains no renderer hooks by design, so on those releases Beryllium removes
  frame cost through vanilla's own options instead: on first launch a daemon thread
  waits for the client, then applies `compatPreset`. `"maxfps"` (default) touches only
  non-visual settings — VSync off, framerate limit unlocked, simulation distance at
  vanilla's minimum — so **every fancy visual stays exactly as configured**.
  `"mobile"` adds the weak-device trade-offs (particles minimal, clouds off, entity
  shadows off, biome blending off, view bobbing off, plus a device-tier render
  distance cap). `"off"` disables it. Implemented entirely with reflection: no mixin,
  no GL call, no Minecraft type named at compile time, so an unknown build logs each
  option as "skipped" instead of failing. This is the FPS layer that works on phones
  running 1.19.4 → 26.2, where the renderer profile does not ship.
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

Beryllium deliberately builds **one JAR per exact Minecraft release**. Its workflow asks
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
26.1, 26.1.1, 26.1.2, 26.2
```

Snapshots and pre-releases are intentionally not called supported releases: their mapping
and renderer changes are not stable enough to make a launch-safety promise. When `26.3`
or a later **stable** release arrives, the CI resolver adds it automatically and the build
must pass before it can be called covered.

### Profiles

| Artifact target | Profile | What loads |
|---|---|---|
| `1.21.4` | **Renderer profile** | The existing verified voxel-shape, culling, scheduler, shader and mobile-tuning hooks. Fabric API is required. |
| Every other covered release | **Cross-version compatibility core** | Configuration, compatibility detection, frame/chunk primitives and Android launch safety, with no game-class, mixin, GLFW or OpenGL linkage during client startup. No Fabric API dependency. |

This split is intentional. Minecraft's rendering internals and mapping format changed
repeatedly across this range (and 26.1 switched to unobfuscated game jars). Guessing a
renderer descriptor on an unverified version is worse than a missing optimization: it can
make a mobile launcher crash before the title screen. The compatibility-core artifact is a
real, exact-version supported launch-safe baseline; version-specific renderer hooks are
only included once they are verified for their profile.

### Android / TurtleLauncher crash guard

`androidSafeMode` defaults to `true`. Before game mixins are applied, Beryllium uses only
safe JVM properties, environment markers, and standard Android filesystem hints to detect
Android/Pojav/Zalith/TurtleLauncher-style hosts. On a match it suppresses Beryllium's
version-sensitive mixins; the client entry point also returns before creating GPU probes,
shader preloads, or Fabric render callbacks. This avoids both early native GLFW/OpenGL
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
| 12 — fog-wall culling & max-FPS defaults | ✅ done — entities / block entities / name tags beyond the fog-wall plane are skipped on the existing verified hook surfaces (`FogCulling` + the phase 6/9/11 mixins); `textShadowsEnabled` defaults off; low-end auto-tune caps render distance per tier |
| 13 — occlusion culling, Dynamic FPS, max-FPS preset, hook self-diagnosis | ✅ done — silhouette raymarch behind solid terrain, backgrounded-window framerate throttle, a non-visual max-FPS preset, and a startup `[applied]/[MISSING]` report for every mixin hook |
| 14 — compatibility-core option tuning (all versions) | ✅ done — reflection-only preset application for every non-1.21.4 artifact; `maxfps` (non-visual: VSync/framerate/simulation distance) or `mobile` (visual trade-offs + render-distance cap), no mixins or GL calls |

> **Verification note (phases 4, 8, 11):** the mixins and hooks added in these phases
> target 1.21.4 internals. Phase 12 deliberately adds **no new injection points**: its
> fog-wall culling extends the already-hooked phase 6/9/11 decision points with pure
> distance logic, and the only game API it reads (`Minecraft.getInstance()`,
> `Minecraft.options`) are members this codebase already used — the render-distance
> value itself is captured reflectively by field name/type (`FogCulling`), so a
> different option-system shape degrades to "culling off" instead of a wrong plane or
> a crash.
>
> **Verification note (phase 13):** phase 13 also adds no new *injection points* — the
> occlusion cull is evaluated inside the already-hooked `EntityRenderDispatcher#shouldRender`,
> and Dynamic FPS / the max-FPS preset / the hook report are Fabric-event + reflection
> code with no mixins at all. The four `@Overwrite`-style shape mixins gained a
> behaviour-free `@Unique` marker field purely so the runtime hook report can prove they
> applied (`@Overwrite` leaves no handler method to detect). `HookReport` prints
> `[applied]/[MISSING]` per hook at client start: treat any `[MISSING]` line as "that
> feature is inactive on this build, and needs re-verification against this exact
> version's mappings before it can be trusted". Phase 4's targets (`LevelRenderer.setSectionDirty(int,int,int)`
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
do not install a JAR made for one target into a different Minecraft version. The `1.21.4`
renderer profile uses Fabric API 0.119.4+1.21.4. Compatibility-core artifacts deliberately
need Fabric Loader only.

```bash
# Runs the development client for the selected target (use 1.21.4 for the renderer profile)
./gradlew runClient -Pminecraft_version=1.21.4
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
| `voxelShapeOptimizations` | `true` | Voxel-shape suite (1.21.4 renderer profile). **Restart required** — read at class-load time by the mixin plugin |
| `cullBehindCameraEntities` | `true` | Behind-camera entity culling |
| `cullSafeRadius` | `4.0` | Never cull anything within this many blocks, regardless of facing |
| `cullAggressiveDistance` | `48.0` | Distance at which the entity cull angle reaches its most aggressive setting |
| `cullDotThresholdNear` | `-0.6` | Entity cull angle right at the safe radius (conservative, ~127° off-center) |
| `cullDotThresholdFar` | `-0.05` | Entity cull angle at/beyond the aggressive distance (aggressive, ~93° off-center) |
| `cullBlockEntities` | `true` | Frustum-cull block entity render calls |
| `blockEntityCullSafeRadius` | `6.0` | Block entities within this distance are never frustum-culled |
| `cullNameTags` | `true` | Distance-cull entity name tags independently of model culling |
| `nameTagCullRange` | `48.0` | Name tags beyond this many blocks from the camera are skipped |
| `cullLeavesInternalFaces` | `true` | Skip the shared face between two adjacent leaves blocks during meshing |
| `cullFogHiddenContent` | `true` | Skip entities / block entities / name tags sitting beyond the fog-wall plane (phase 12) |
| `fogCullFactor` | `0.9` | Fog-wall plane as a fraction of render distance; `>= 2.0` disables fog-wall culling |
| `fogCullSafeRadius` | `12.0` | Fog-wall culling never applies inside this distance from the camera |
| `textShadowsEnabled` | `false` | Text drop-shadow toggle — `false` (default) removes the shadow pass behind all text (GUI, name tags, signs, tooltips); set `true` for the vanilla look |
| `cullOccludedEntities` | `true` | Skip entities whose silhouette is fully blocked by opaque blocks (phase 13) |
| `occlusionCullMinDistance` | `6.0` | Occlusion culling never applies closer than this |
| `occlusionCullMaxDistance` | `64.0` | Occlusion culling never applies farther than this |
| `occlusionCullRaycastsPerFrame` | `16` | Hard cap on silhouette raycasts per frame (verdicts cached 100 ms) |
| `dynamicFps` | `true` | Lower the framerate limit while the window is unfocused/minimized |
| `dynamicFpsUnfocusedLimit` | `10` | Framerate limit used while backgrounded (vanilla floor) |
| `fancyMaxFpsPreset` | `true` | One-shot non-visual preset: VSync off, FPS unlocked, simulation distance at minimum |
| `maxFpsPresetApplied` | `false` | Internal: set once the max-FPS preset has run |
| `compatAutoTune` | `true` | Compatibility-core option tuning on non-1.21.4 artifacts (reflection-only) |
| `compatPreset` | `"maxfps"` | `"maxfps"` = non-visual only (visuals untouched), `"mobile"` = weak-device trade-offs + render-distance cap, `"off"` = nothing |
| `compatAutoTuneApplied` | `false` | Internal: set once the compat preset has run |
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

## VulkanMod integration (`vulkanmod/`)

`vulkanmod/` vendors the complete
[VulkanMod](https://github.com/xCollateral/VulkanMod) Vulkan rendering engine
(LGPL-3.0, by xCollateral) — a full replacement of Minecraft's OpenGL renderer
with optimized chunk meshing, section culling, entity rendering and
chunk-building threads — as seven per-version Loom builds plus a
**universal single jar** covering all of them at once:

- **Per-version jars** (`vulkanmod-mc-1.20.4` … `vulkanmod-mc-1.21.11` CI
  artifacts) are the upstream-style self-contained builds for exactly one
  Minecraft version: 1.20.4, 1.21, 1.21.1, 1.21.10, 1.21.11 — every version
  for which upstream VulkanMod ships compilable source.
- **The universal jar** (`vulkanmod-universal` CI artifact) merges all seven
  code sets into one file. Each code set is relocated into its own
  `net.vulkanmod.mc<version>` package, and every per-version mixin
  configuration carries a version gate: on a game version that doesn't match,
  the configuration removes all of its targets, so zero mixins apply and the
  jar is inert. On a matching version the full Vulkan renderer activates; on
  any other 1.19.4+ version the jar loads safely and stays off. It requires
  the Fabric API for your version alongside (the per-version API bundles are
  replaced by a runtime dependency in the merge).

The merge is structurally verified by CI (every relocated class, mixin config,
refmap and access-widener entry resolves inside the jar) before the artifact
is accepted. Details, the support table and the "add a version" procedure are
in [`vulkanmod/README.md`](vulkanmod/README.md).

## License

MIT — see [LICENSE](LICENSE). Third-party code: the Lithium shape suite (MIT,
see [THIRD_PARTY.md](THIRD_PARTY.md)) and VulkanMod (LGPL-3.0, see
`vulkanmod/README.md`).
