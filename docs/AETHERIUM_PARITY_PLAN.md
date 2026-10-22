# Aetherium feature study & port plan

> **Provenance.** This document records a study of the public *Aetherium* project
> (CurseForge: `aetherium-fabric`, source: `github.com/entitybrian69-bit/aetherium`,
> **LGPL-3.0-only**, © 2026 Aetherium contributors) and plans how Beryllium reaches
> feature parity with it.
>
> **Clean-room pledge.** No Aetherium source file is copied into this repository, and
> none of the code added under this plan is a translation of theirs. Beryllium is MIT;
> importing their LGPL-3.0 code would put those parts under LGPL-3.0 and change the
> obligations of this project permanently. What is taken is the *feature ideas* —
> zoom with scroll-adjust and a p99 frame-time metric — the same way Aetherium
> credits Zume for the zoom idea while reimplementing it independently. Everything
> below is implemented from Minecraft's own public API and from techniques common
> across the optimization-mod ecosystem.

## What Aetherium actually is (verified against its repository)

Aetherium is built around **one reference release (1.21.1)** plus mechanical
per-version "delta" patches for 32 more versions. Its honest-status table says:

- The **full renderer is NOT SHIPPED** — the code exists but is refused at startup,
  falling back to a "SHADOW/Compatibility" mode, because the author could not verify
  it on real hardware. The CurseForge page's "Aetherium renderer (on by default,
  every version)" claim applies to the shipped SHADOW path.
- The shipping feature set is: tabbed/animated video-settings screen, backend probe,
  dynamic lights (updates only where changed), gamma suite, zoom,
  entity/terrain culling, HUD overlay, conflict scanner with auto-delegation,
  Android launcher routing, chat price of 58 config options.

## Gap analysis vs. Beryllium (src scan, 2026-10-22)

| Aetherium feature | Beryllium today | Verdict |
| --- | --- | --- |
| Own renderer (HZB occlusion, multi-draw-indirect, persistent-mapped buffers) | not present | **Long horizon.** Aetherium itself doesn't ship it; do not start. |
| Animated 6-tab video settings screen + presets | Gson config, no custom screen | Batch 4. |
| Zoom (hold/toggle, smooth, scroll-adjust, rebindable) | none | Implemented, then **removed at maintainer request** (2026-10-22) to keep this branch focused on the engine work. Useful reference for batch 3+. |
| Dynamic lights ("where changed" light-updates) | none | Batch 3. |
| Freelook | none | Batch 3. |
| p99 frame-time in HUD | 1% low / 0.1% low only | **Batch 1 — this pass.** |
| Even frame pacing at max-framerate | chunk-upload pacing only | Batch 2. |
| Battery saver + thermal guard (Android) | device tiers + mobile auto-preset | Batch 3. |
| Smart chunk-in-fog culling, entity occlusion culling | visibility culling, behind-camera culling, frustum culling | Partially covered; extend in Batch 2/3. |
| Conflict scanner + one-line HUD notice, auto-delegate | CompatibilityChecker, defer flags | Covered. |
| Threaded chunk building (worker count option) | BerylliumWorkers pool | Covered concept. |
| 58-option versioned config schema (+docs site) | flat Gson config | Batch 4 (schema/versioned migration). |

Beryllium already has features Aetherium does **not**: the voxel-shape engine
(Lithium-family), hopper/item throttling, the tick governor, sign/beacon/chest
cullers, chat QoL suite, sign-text draw reduction, delete-to-trash, experimental
off-thread ticking. Parity here is one-directional; nothing is removed.

## Batch 1 (this pass)

1. **Zoom** — *removed at maintainer request after landing; see the gap table.*
2. **P99 metric** — `FrameTimeRingBuffer.Snapshot` gains `p99FrameNanos` with
   `p99FrameMillis()`/`p99Fps()` accessors; the debug overlay prints it beside the
   1%/0.1% lows. Same meaning Aetherium documents: the slowest 1% of frames.

Verification status, in this repo's own terms: **code complete, not yet compiled by
CI**. Every injection is `require = 0` and every subsystem is wrapped, so a release
where a target moved gets vanilla behaviour for that feature. The first CI matrix
run tells us which releases each hook covers; rows that fail get per-release mixin
variants like the existing Legacy/Modern pairs.

## Batch 2 (next)

- **Even frame pacing** — smooth the sleep quantum of vanilla's max-framerate cap
  so frames land evenly instead of drifting (Windows timer-resolution aware).
- **Smart chunk-in-fog culling** — extension of the existing `VisibilityCulling`.

## Batch 3

- **Freelook** — camera yaw/pitch override while a second bind is held, toggle or
  hold mode; never touches the player's own look state.
- **Battery saver / thermal guard** — extends `MobileTuner`: FPS cap + reduced
  background work when `LauncherEnvironment` reports a phone-class device.
- **Dynamic lights** — the feature here is *where updates land*, not the light
  engine: recompute lighting only in chunks whose light sources changed. Do not
  start before play-testing batch 1+2 on a real device.

## Batch 4

- **Settings screen** — Beryllium's own screen, not a port of theirs: tabs mirroring
  the config sections, greyed-out "not on this version" rows, presets. This is the
  largest single UI item; it should land only after the config grows a versioned
  schema (also in this batch).

## Deliberately not planned

- Their renderer work (HZB, persistent-mapped, Vulkan probe): unshipped upstream,
  unverifiable here, and Beryllium's composition-with-Sodium strategy already covers
  the desktop case.
- Any change to the license: stays MIT. Their code stays at `../aetherium` as
  read-only reference and is ignored by git.
