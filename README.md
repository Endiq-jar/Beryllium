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

### Infrastructure (ready for the next phases)

- **Frame budget scheduler** (`FrameBudgetScheduler`/`FrameBudget`) — per-frame
  millisecond budgets with priority classes, fed by frame-time statistics.
- **Chunk rebuild prioritization queue** (`ChunkRebuildQueue`/`ChunkRebuildRequest`/
  `ChunkRebuildPriority`) — proximity + view-alignment + urgency scoring. *Not yet
  wired into the 1.21.4 chunk-meshing pipeline* — that integration depends on
  version-specific internals and will be added as its own verified phase.
- **Buffer pooling** (`BufferPool`/`DeferredReleaseQueue`) and **versioned file
  cache** (`VersionedFileCache`) for future GPU-buffer and shader-cache work.

## Status

| Phase | State |
|---|---|
| 1 — init, config, device detection, structured logging | ✅ done |
| 2 — frame profiler: FPS / frame time / 1% / 0.1% lows + overlay | ✅ done |
| 3 — frame budget scheduler | ✅ built, waiting for a live work source |
| 4 — chunk rebuild prioritization queue | ✅ built, **not yet wired** to the 1.21.4 mesh pipeline |
| 5 — voxel shape specialization & caching (Lithium-family) | ✅ done |
| 6 — block entity frustum culling | ✅ done |
| 7 — mobile auto-tune preset | ✅ done |
| 8 — shader precompile cache / GPU buffer recycling | 🚧 infrastructure only |

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
