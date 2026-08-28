<H1 align= "centre">Beryllium</H1>

**A Fabric mod focused on making Minecraft Java Edition run smoother on Android and other
mobile Java launchers (PojavLauncher/ZalithLauncher-family.**

**Primary goals:** higher and more *consistent* frame times, faster chunk building, lower RAM/GC pressure, and mobile-GPU-aware
rendering.


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

## Status

**Not "all phases."** The full design doc spans chunk-rebuild rewrites, buffer pooling,
a shader cache, thermal/battery management, an auto-optimizer, a compatibility layer,
and an optional Vulkan backend — each of those needs its own real implementation, and
several genuinely can't be verified without running the game (chunk rebuild timing,
GPU capability tiers, thermal behavior under load, etc.). Writing all of it blind in one
pass would mean handing over hundreds of untested lines with no way to know which parts
actually work — IN short **"In Development"**

**Implemented and included:**

- **Phase 1** — init, config, device detection, structured logging
- **Phase 2** — frame profiler: FPS, frame time, 1% low, 0.1% low, debug overlay
- **Phase 3** — frame budget scheduler (priority-queued background work). As of this
  update it's no longer inert: `ChunkRebuildPriority.toWorkPriority` (Phase 4) maps a
  chunk request onto one of its priority buckets, closing the loop between the two.
- **camera culling** —
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

## Building

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
