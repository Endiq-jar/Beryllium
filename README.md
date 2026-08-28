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

- **Phase 1** — init, config, device detection, structured logging
- **Phase 2** — frame profiler: FPS, frame time, 1% low, 0.1% low, debug overlay
- **Phase 3** — frame budget scheduler (priority-queued background work). As of this
  update it's no longer inert: `ChunkRebuildPriority.toWorkPriority` (Phase 4) maps a
  chunk request onto one of its priority buckets, closing the loop between the two.
- **Behind-camera entity culling ("Superb Player culling"), now more aggressive** —
  safe radius dropped 8→4 blocks, and the cull angle is now distance-graduated instead
  of one fixed threshold: conservative right at the safe radius (must be ~127° off-center
  before culling), relaxing to aggressive by 48 blocks out (~93° off-center is enough).
  Close things still get a wide margin; far-away things — where most of the actual
  overdraw savings are, and where popping is imperceptible — get culled a lot more
  readily. Still never culls anything within the safe radius, and never culls anything
  actually in front of you or directly to the side, at any distance.
- **Phase 4 (started): chunk rebuild prioritization** — `ChunkRebuildRequest` /
  `ChunkRebuildPriority` / `ChunkRebuildQueue` implement spec section 10's ordering
  (proximity, view-alignment, urgency, distance) as a real, tested, dedup'd priority
  queue. Not yet wired to Minecraft's actual chunk renderer — see below for why.

**Not implemented:** the rest of Phase 4 (actual mesh generation / buffer changes),
mobile GPU capability tiers, shader cache, thermal/battery management, auto optimizer,
compatibility layer, benchmarking harness, Vulkan backend.

### Why Phase 4 stops at prioritization for now

Wiring `ChunkRebuildQueue` into the real game means mixing into
`net.minecraft.client.renderer.chunk.SectionRenderDispatcher` (confirmed as the correct
1.21.4 class/package via Fabric's own migration notes) — but *which* method actually
enqueues a rebuild, and its exact signature, wasn't something I could confirm without
the real deobfuscated jar (no Mojang/Fabric maven access in this sandbox). That class is
also one of the more version-fragile parts of the renderer. Guessing there risks a
mixin that fails to apply, or worse, one that applies against the wrong method and
silently breaks chunk rendering — a much bigger blast radius than the entity culling
mixin. If you run `./gradlew genSources` (or just check the decompiled
`SectionRenderDispatcher` in your IDE) and share the method that currently handles
"rebuild this section," I can wire this precisely instead of guessing at it.

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
  unit-tested locally, including the graduated-threshold behavior specifically (same
  off-center angle culled far away but NOT culled just past the safe radius; directly
  ahead/to-the-side never culled at any distance; safe-radius boundary inclusive). All
  tests pass.
- `ChunkRebuildPriority` / `ChunkRebuildQueue` — pure Java, zero Minecraft dependency.
  Compiled and unit-tested locally: ahead-vs-behind ordering, closer-vs-farther
  ordering, urgent requests always outranking normal ones regardless of distance,
  dedup-by-key on resubmission, and `drain()` correctly removing and returning only the
  requested count in priority order. All tests pass.

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
| `cullBehindCameraEntities` | `true` | Enable behind-camera entity culling |
| `cullSafeRadius` | `4.0` | Never cull anything within this many blocks, regardless of facing |
| `cullAggressiveDistance` | `48.0` | Distance at which the cull angle reaches its most aggressive setting |
| `cullDotThresholdNear` | `-0.6` | Cull angle right at the safe radius (conservative, ~127° off-center) |
| `cullDotThresholdFar` | `-0.05` | Cull angle at/beyond the aggressive distance (aggressive, ~93° off-center) |

## Sodium licensing

No Sodium source is vendored or referenced anywhere in this delivery. The entity culling
feature above is an original, independently-derived heuristic (dot product + safe
radius), not a port of anything from Sodium/EntityCulling — only the *choke point it
hooks into* is the same one those mods use, which is just how Minecraft's own render
loop works.
