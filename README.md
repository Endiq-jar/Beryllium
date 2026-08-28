<H1 align= "centre">Beryllium</H1>

**A modern Fabric optimization mod focused on delivering smoother gameplay and better performance on mobile, low-end, and high-end devices.**

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

---

A Fabric mod focused on making Minecraft Java Edition run smoother on Android and other
mobile Java launchers (PojavLauncher/ZalithLauncher-family), while staying compatible
with desktop Java Edition and the wider Fabric mod ecosystem.

Primary goals: higher and more *consistent* frame times (1% / 0.1% lows matter more than
average FPS), faster chunk building, lower RAM/GC pressure, and mobile-GPU-aware
rendering — see the full design doc for the complete roadmap.

## Status

**Not "all phases."** The full design doc spans chunk-rebuild rewrites, buffer pooling,
a shader cache, thermal/battery management, an auto-optimizer, a compatibility layer,
and an optional Vulkan backend — each of those needs its own real implementation, and
several genuinely can't be verified without running the game (chunk rebuild timing,
GPU capability tiers, thermal behavior under load, etc.). Writing all of it blind in one
pass would mean handing over hundreds of untested lines with no way to know which parts
actually work — the opposite of the doc's own "every milestone must compile, launch, and
be testable" rule. So this delivery is a large, real increment, not the whole roadmap:

**Implemented and included:**

- **Phase 1** — init, config, device detection, structured logging (unchanged from before)
- **Phase 2** — frame profiler: FPS, frame time, 1% low, 0.1% low, debug overlay
- **Phase 3** — frame budget scheduler (priority-queued background work), *not yet wired
  to anything* — there's no chunk/background work to schedule until Phase 4 exists
- **New feature (your ask): behind-camera entity culling** — skips rendering entities
  that are solidly behind the camera and far enough away to not pop, layered on top of
  (not replacing) vanilla's own frustum/distance check

**Not implemented:** Phase 4 (chunk optimization), buffer management, mobile GPU
capability tiers, shader cache, thermal/battery management, auto optimizer, compatibility
layer, benchmarking harness, Vulkan backend. These are the right next milestones, in
roughly that order, once everything below is confirmed working in-game.

## What's actually been tested vs. what hasn't

This was built in a sandbox with **no network access to Mojang's or Fabric's servers**,
so the Minecraft-dependent code (anything touching `net.minecraft.*`, LWJGL, or Fabric
API) could not be compiled here — same limitation as the Phase 1 delivery.

What *could* be verified, and was:

- `FrameBudgetScheduler` / `FrameBudget` — pure Java, zero Minecraft dependency.
  Compiled and unit-tested locally (priority ordering, CRITICAL always running even at
  zero budget, lower priorities correctly deferred). All tests pass.
- `FrameTimeRingBuffer` — pure Java. Compiled and unit-tested locally (wraparound
  behavior, 1%/0.1% low math against hand-computed expected values). All tests pass.
- `BehindCameraCulling` — pure Java geometry, zero Minecraft dependency. Compiled and
  unit-tested locally against 9 cases (ahead/behind/to-the-side, safe-radius boundary
  inclusive/exclusive, degenerate zero-vector, non-normalized input). All tests pass.

What's real code but **not compile-checked** (needs `./gradlew build` on your end):

- `FrameProfiler` / `DebugOverlay` — Fabric API (`WorldRenderEvents`, `HudRenderCallback`)
  + `Minecraft.getInstance().font` / `GuiGraphics.drawString(...)`. These are
  long-standing, low-churn APIs, but weren't checked against the actual 1.21.4 jar.
- `EntityRenderDispatcherMixin` — **the highest-risk file.** It injects into
  `EntityRenderDispatcher#shouldRender` (the same hook mods like EntityCulling use — it's
  the one choke point every entity passes through before rendering), and calls
  `Camera#getPosition()` / `Camera#getLookVector()` plus shadows `EntityRenderDispatcher`'s
  `camera` field. These names were cross-checked against published Mojang-mapped
  decompiles from a similar version rather than the actual 1.21.4 jar, so treat them as
  "probably right, not verified." If the build fails here, it's almost certainly a
  one-line accessor rename, not a design problem — the actual culling math
  (`BehindCameraCulling`) is solid and already tested.

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
| `cullBehindCameraEntities` | `true` | Enable the new behind-camera culling |
| `cullSafeRadius` | `8.0` | Never cull anything within this many blocks, regardless of facing |
| `cullDotThreshold` | `-0.35` | How far behind (~140° rear cone) something must be before it's culled |

## Sodium licensing

No Sodium source is vendored or referenced anywhere in this delivery. The entity culling
feature above is an original, independently-derived heuristic (dot product + safe
radius), not a port of anything from Sodium/EntityCulling — only the *choke point it
hooks into* is the same one those mods use, which is just how Minecraft's own render
loop works.
