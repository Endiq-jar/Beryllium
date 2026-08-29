<H1 align= "centre">Beryllium</H1>

**A Fabric mod focused on making Minecraft Java Edition run smoother on Android and other
mobile Java launchers (PojavLauncher/ZalithLauncher-family).**

**Primary goals:** higher and more *consistent* frame times, faster chunk building,
lower RAM/GC pressure, and mobile-GPU-aware rendering.

>Beryllium focuses on reducing unnecessary rendering, CPU work, memory usage, and frame-time spikes without sacrificing Minecraft's core gameplay.

✦ Features

- Optimized chunk rendering & chunk building

- Frustum, occlusion, text, name-tag, leaves, entity & block entity culling

- Level of Detail

- Caching & Memory

### Mobile Performance

🔧 OpenGL ES optimizations

🚀 Fast startup & warm caching

⚙️ Fully Configurable

## Status: In Development

**Implemented:**

- **Phase 1** — init, config, device detection, structured logging
- **Phase 2** — frame profiler: FPS, frame time, 1% low, 0.1% low, debug overlay
- **Phase 3** — frame budget scheduler, fed by Phase 4's chunk rebuild priorities
- **Phase 4 (Sodium-companion architecture, chosen direction)** — chunk rebuild
  prioritization queue (proximity, view-alignment, urgency, distance), and it's now
  Sodium-aware: `CompatibilityChecker.isChunkRendererReplaced` + a real gating flag
  (`Beryllium.isChunkOptimizationDeferredToOtherMod()`) mean this queue is guaranteed to
  stay inert whenever Sodium is loaded — the same "don't touch rendering, stay
  compatible either way" model Lithium uses, rather than Beryllium trying to also own
  chunk rendering. **Still not wired to a live rebuild trigger when Sodium *isn't*
  loaded** — see below for why that's a separate, harder problem than the gating logic.
- **Behind-camera entity culling ("Superb Player culling"), aggressive** — 4-block safe
  radius, distance-graduated cull angle (conservative up close, aggressive by 48 blocks
  out). Never culls anything ahead or to the side. Defers to EntityCulling automatically
  if it's loaded.
- **Buffer pooling infrastructure** — generic acquire/release pool + deferred (delayed)
  disposal, ready for real GPU buffers once chunk rendering is wired in.
- **GPU capability tiers** — classifies device capability from real queried values
  (max texture size, CPU, RAM), not a marketing-name lookup table. Logged at startup.
- **Shader cache infrastructure** — versioned, corruption-safe on-disk cache. Not yet
  wired to actual shader compilation (see below).
- **Compatibility layer** — detects known optimization mods (Sodium, Lithium,
  FerriteCore, ImmediatelyFast, EntityCulling, Indium, Iris) and defers to them where
  they'd overlap with Beryllium's own features.

**Not in scope right now:** thermal/battery management, auto-optimizer, benchmarking
harness (dropped on request).

**Blocked on real Minecraft internals, not guessed at — and choosing the Sodium-companion
direction doesn't remove this, it only narrows when it matters:** when Sodium *is*
loaded, Beryllium correctly does nothing (done, tested, real). When Sodium *isn't*
loaded — the actual primary case, since mobile launchers running through GL4ES/ANGLE-style
translation layers generally can't run Sodium at all (it requires real OpenGL 4.5, which
translation layers don't reliably provide) — something still has to tell vanilla's
renderer to actually rebuild sections in the order this queue produces, and that
still means hooking `SectionRenderDispatcher`, with the same unconfirmed-signature risk
as before. Sodium's architecture couldn't shortcut that: it doesn't expose an extension
API for this, since it replaces the chunk renderer rather than augmenting it. Hooking
the shader cache to real shader compilation has the identical problem, different class.
Send the relevant method signatures (e.g. via `./gradlew genSources`) for either and
they can be wired in directly instead of guessed at.

**Not applicable:** a Vulkan backend — Minecraft 1.21.4 (this mod's target version)
doesn't have one to hook into yet; that's landing in later versions.

## What's been tested

Everything with zero Minecraft/LWJGL dependency was actually compiled and unit-tested
(not just written): the frame budget scheduler, frame time ring buffer + percentile-low
math, the behind-camera culling geometry (including the graduated-threshold behavior),
the chunk rebuild priority queue, buffer pooling and deferred release, the GPU capability
classifier, and the versioned file cache (including corrupted-entry and version-bump
behavior). All passing.

Code that touches Minecraft/Fabric/LWJGL APIs (the profiler overlay, GPU detection, and
especially the culling mixin) could not be compiled here — no network access to
Mojang/Fabric's servers in this environment — so it's written against known-stable APIs
but not verified against the real 1.21.4 jar. The culling mixin is the highest-risk file;
if `./gradlew build` fails there, it's almost certainly a one-line accessor rename.

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
| `cullBehindCameraEntities` | `true` | Enable behind-camera entity culling |
| `cullSafeRadius` | `4.0` | Never cull anything within this many blocks, regardless of facing |
| `cullAggressiveDistance` | `48.0` | Distance at which the cull angle reaches its most aggressive setting |
| `cullDotThresholdNear` | `-0.6` | Cull angle right at the safe radius (conservative, ~127° off-center) |
| `cullDotThresholdFar` | `-0.05` | Cull angle at/beyond the aggressive distance (aggressive, ~93° off-center) |
| `compatibilityModeEnabled` | `true` | Detect known optimization mods and defer to them where they overlap |
