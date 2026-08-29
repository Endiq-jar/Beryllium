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
- **Phase 4 (Sodium-companion)** — chunk rebuild prioritization queue (proximity, view-alignment, urgency, distance). Sodium-aware: stays inert whenever Sodium is loaded (same model as Lithium), rather than Beryllium also trying to own chunk rendering. Not yet wired to a live rebuild trigger for when Sodium isn't loaded.
- **Behind-camera entity culling ("Player culling"), aggressive** — 4-block safe
  radius, distance-graduated cull angle (conservative up close, aggressive by 48 blocks
  out).
- **Buffer pooling infrastructure** — generic acquire/release pool + deferred (delayed)
  disposal, ready for real GPU buffers once chunk rendering is wired in.
- **Shader cache infrastructure** — versioned, corruption-safe on-disk cache. Not yet
  wired to actual shader compilation.
- **Compatibility layer** — detects known optimization mods (Sodium, Lithium,
  FerriteCore, ImmediatelyFast, EntityCulling, Indium, Iris) and defers to them where
  they'd overlap with Beryllium's own features.

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
