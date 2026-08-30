# Beryllium Engine

A Sodium-inspired voxel renderer: chunked world, greedy meshing, baked ambient
occlusion and skylight/block light, a mesh store fed by a worker pool, and a
potentially-visible-set culler. Written in dependency-free C11 — libc, pthreads
and `dl` only, no build system beyond `make`, no asset pipeline.

It is the rendering half of the Beryllium project. The Fabric mod in the parent
directory is the Minecraft-side Mod; this engine is standalone and does not link
against it.

## Why a software backend at all

This engine was written in a container with no GPU, no display, no GL driver, no
Vulkan loader and no shader compiler. Rather than write a GPU backend that
nobody here could run, the renderer is built around a small
[render hardware interface](src/rhi.h) with a **software rasterizer as a first
class backend**: it executes the same vertex format, the same 288-byte uniform
block and the same draw calls as the GPU paths, into the same RGBA8 image, and
writes PNGs through the engine's own encoder.

That makes almost the whole pipeline testable head-lessly — and the tests are
the point. A GPU backend that cannot be run is a claim; a rasterizer that
produces a decodable, statistically-checked image of generated terrain is
evidence.

## Build and run

```sh
make                      # build/beryl (demo) + build/beryl_tests + tools
make test                 # build and run the whole suite
make bench                # meshing/render benchmark on a generated region
make EXTRA_CFLAGS=-Werror # the tree is warning-free with -Wall -Wextra -Wshadow -Werror
make ANDROID=1            # phone profile: LTO, section gc, hidden visibility, mobile presets
make ANDROID=1 test       # same suite, same expectations, under those flags
```

```sh
./build/beryl --frames 1 --screenshot frame.png --view-distance 14
./build/beryl --backends                    # which backends can start on this machine
./build/beryl --mode lightmap --camera 132,108,74,180,-14 --orbit
./build/beryl --obj terrain.obj --benchmark
python3 tools/pngstat.py --strict frame.png  # structural + content check, exit 1 if blank
```

`--backend opengl` on a machine without libGL prints why and falls back to the
software rasterizer instead of failing to link or crashing. `--help` lists every
flag; the frame-budget ones are `--preset desktop|mobile|low-end`,
`--target-ms N` and `--no-adaptive` (see "Android and low-end devices").

## Layout

| File | Role |
| --- | --- |
| `src/bcore.*`, `bmath.*` | logging, asserts, timing, vectors/matrices/frustums |
| `src/blocks.*` | block table: opacity, light emission/attenuation, tint palette, tile mapping |
| `src/worldgen.*`, `src/world.*`, `src/chunk.*` | seeded terrain, 16×16×16 sections, chunk/column store, top-height map |
| `src/light.*` | sky + block light: column seeding, BFS spread, incremental edit queue |
| `src/mesher.*`, `src/mesh_format.*` | per-axis sweep, greedy rectangle merge, packed 16-byte vertex |
| `src/occlusion.*` | PVS walk from the camera section, sealed-face rejection |
| `src/pool.*` | worker pool: priority queue, coalescing, done-ring |
| `src/engine.*` | the store, per-frame budget, uniform assembly, draw submission, OBJ export |
| `src/perf.*` | the frame governor: how much a frame may spend, and the presets a launcher exposes |
| `src/rhi.*`, `rhi_soft.*`, `rhi_gl.*` | the backend interface, the reference rasterizer, the GL backend |
| `src/png.*` | the deflate/stored-block PNG writer used for screenshots |
| `shaders/terrain.*.glsl` | the GPU source of truth, embedded into the binary by `tools/embed.c` |

## The contract every backend must honour

- **Vertex**: 16 bytes, five *integer* attributes so fixed-point values reach the
  shader unmodified — `pos_x,pos_y` R16G16_UINT@0, `pos_z` R16_UINT@4,
  `uv_s,uv_t` R16G16_UINT@6, `ao|face` R8G8_UINT@10, `tile|flags` R8G8_UINT@12.
  Positions are section-local 8.8 fixed point (`BERYL_POS_SCALE 256`); UVs are
  in-plane block coordinates scaled by 16, grid-aligned to the section origin, so
  the shader tiles with `fract()` and a merged quad stays in phase.
- **Uniforms**: one 288-byte `std140` block (`BerylTerrainUniforms`) memcpy'd
  verbatim: MVP, section origin, camera position, fog, fog colour, eight tints,
  and `params = { day, 1/tile_size, render_mode, water_alpha }`.
- **Textures**: a 2D **array** with one 16×16 RGBA8 layer per tile
  (`BERYL_TILE_LAYERS`), `CLAMP_TO_EDGE` on all axes because tiling is the
  shader's job; plus a 16×16 lightmap whose texel `(sky*16 + blk)` carries the
  colour ramp.
- **Shading**: `texel.rgb * tint[vert_tint] * lightmap * AO{0.43,0.68,0.84,1.00} *
  face{0.5,1.0,0.8,0.8,0.6,0.6}`, then linear fog. Cutout texels discard below
  alpha 0.5; the blend layer forces alpha ≥ 0.65.
- **Winding**: the mesher emits CCW triangles in a right-handed clip space. The
  software rasterizer sees a y-down pixel grid, which negates the signed area, so
  it keeps `area < 0`; GL uses `glFrontFace(GL_CCW)`. A backend that "fixes" the
  mesher instead breaks both of the other two.
- **Layers**: solid, cutout, blend, drawn in that order, all from one VBO/IBO pair
  per section with an index-range offset.

## Test suite (`make test`)

2502 checks in six suites, all of them running against the real generator and the
real mesh data:

- `test_basics` — math, block table, format packing/unpacking, the light
  attenuation rules, PNG structure.
- `test_world` — world/section/chunk bookkeeping, revisions, light. Its central
  test walks *every vertex of every meshed section* and asserts that a face corner
  whose four air-side cells are open sky is baked to full brightness with no
  ambient occlusion, which pins the light sampler to the correct side of the face
  and would catch a "reads the block it belongs to" bug immediately.
- `test_pool` — the mesh store's input contract: `beryl_pool_drain()` must hand
  back one result row per finished build, each owning distinct buffers, with every
  index in range and every vertex inside its section. A missing result is not a
  visible bug (the section stays dirty and is rebuilt later), it is a leak, so this
  suite counts rows and checks pointer aliasing instead of pixels.
- `test_perf` — the governor: dead-band silence while the device is on target,
  monotone back-off under sustained overrun, reopening under headroom, never
  outside its clamps, stalls neither averaged nor combined with neighbouring
  frames; plus the presets (mobile tighter than desktop, explicit flags win,
  idempotent, budgets inside the governor's range) and one test that drives a real
  engine and reads the moved budgets back through `beryl_engine_settings()`.
- `test_soft_render` — end-to-end frames: alpha is always opaque, colour variety,
  ground coverage below the horizon, byte-identical repeat renders, resize, the
  debug render modes, the box-in-lightmap-culling test, OBJ export integrity,
  PNG file structure, and that occlusion culling changes **no visible pixel**
  while submitting fewer draws.
- `test_gl_backend` — the OpenGL backend driven through a recording loader (see
  below).

The suite is deliberately free of thresholds that a correct engine could wander
across: `light == 15` where nothing can shadow it, `AO == 3` where nothing is
beside it, and so on.

The suite is also **mutation checked**: inverting the rasterizer's backface sign,
sampling light from the owning cell instead of the air side, pinning AO to a
constant, selecting `GL_UNSIGNED_SHORT` for indices of a 4-byte stride, using
`glFrontFace(GL_CW)`, uploading 256 instead of 288 bytes of uniforms, dropping the
uniform cache or using `GL_REPEAT` on the tile array each make the suite fail — as
do writing drained results into one slot instead of one row per result, and
removing the governor's dead band, its hitch guard or its install floor. Every one
of those mutants was actually built and run.

## Android and low-end devices

A phone-class SoC does not need different rendering, it needs different
*scheduling*: the frame is mostly bookkeeping (mesh installs, VBO uploads, chunk
generation) and the ceiling is thermal rather than architectural. Three pieces,
all of them exercised by the suite.

**Presets.** `beryl_settings_apply_preset()` picks a starting point. `desktop` is
the compiled defaults; `mobile` is 8 sections of view distance, 8 installs and
2 MB of uploads per frame, 2 chunks of generation per frame, occlusion on, nearest
filtering, governor on at 16.7 ms; `low-end` is 6 sections, 4 installs, 1 MB,
33.3 ms. A preset starts from the defaults and sets every field it cares about
while preserving the caller's framebuffer and backend, so applying one twice is
applying it once, and an explicit `--view-distance` still wins over `--preset`.
`make ANDROID=1` (`-DBERYL_MOBILE=1`) makes the demo begin in `mobile`: a build for
a launcher arrives shaped for the device, rather than depending on a flag.

**The governor** (`src/perf.h`) owns `rebuilds_per_frame` and
`uploads_per_frame_bytes`. Nothing happens inside −20 %/+10 % of the target, a
sustained overrun cuts both budgets to three quarters after two bad frames,
sustained headroom adds an eighth back after eight good ones, and a frame worse
than 3x the target is counted as a stall and ignored — GC, an activity switch and
the launcher's own stutter say nothing about the steady-state cost of a budget, and
folding them into the average would pin the budgets at the floor for minutes. Both
floors are pinned above the values that would wedge the renderer (0 installs is a
permanent hole; a 0 upload budget means *unlimited* to the store). It is arithmetic
only — no clock, no globals, no allocation — which is what makes it testable
without a phone, and `beryl_engine_note_frame_ms()` lets the embedder feed it the
half of the frame the engine cannot see (its own submit and swap). Minecraft's
client frame timer is the right source for the mod.

**The build profile.** `ANDROID=1` adds `-flto -fno-semantic-interposition
-fvisibility=hidden -fno-plt -ffunction-sections -fdata-sections` plus
`-Wl,--gc-sections`, and `NDEBUG=1` drops the asserts. It changes no code path, so
the same `make test` proves it: here it took the demo binary from 757 KB to 709 KB
(-6.4 %) with the suite's expectations unchanged. The lto-wrapper's
"serial compilation of N LTRANS jobs" note is informational, not a warning about
the code.

**The rasterizer.** `raster_tri`'s scanline walk now solves each row's covered
span once (the barycentric weights are affine, so the span is closed-form) instead
of testing every pixel of the bounding box, while coverage itself stays the exact
per-pixel test. A 6-frame 960x540 run at view distance 10 went from 53.2 ms/frame
to 47.2 ms/frame (-11 %) and wrote a **byte-identical PNG** — verified by hashing
the two renders. That is the only kind of rasterizer change worth making without a
device to look at: fewer pixels examined, provably the same pixels written.

**Not verified:** anything device-specific. No NDK, no ARM hardware and no GPU were
available, so the preset numbers are "where a mid-range phone should start", with
the reasoning in the comments — not measurements on a phone. The governor's
behaviour, the presets' consistency, the profile's flags and the rasterizer's
identity are all tested; what a Snapdragon does with them is not.

## OpenGL backend: what "tested" means here

`src/rhi_gl.c` is a real GL 3.3-core implementation: VAO plus five integer
attributes, a texture array and a lightmap, an offscreen FBO with a depth
renderbuffer for capture, immutable buffer storage for dynamic geometry when
`glBufferStorage` exists, an applied-state cache so a section bind is
`UseProgram` plus a couple of state calls, and a row-flipping `glReadPixels`
readback. It never calls `glfwCreateWindow` or makes a context current: the
context is the caller's, which is what an embedder (or the mod's own GL layer)
provides.

No link-time GL dependency exists: every entry point is a function pointer in
`BerylGLLoader`. `beryl_gl_loader_default()` dlopen()s libGL and dlsym()s the 60
names it needs; `beryl_gl_loader_resolve()` refuses to start a device when a
required one is missing and names it. A test injects stubs with the real
prototypes and **records the command stream**, so these claims are checked
without a driver:

- the exact targets, sizes and usage of every `glBufferData`/`glBufferSubData`;
- `GL_TEXTURE_2D_ARRAY`, one `glTexSubImage3D` per layer, `GL_NEAREST` and
  `GL_CLAMP_TO_EDGE` on all three axes;
- that the shaders compiled are the embedded `shaders/*.glsl` with `#version 330
  core` prepended, that the `Terrain` block is looked up by name and bound to
  point 0, that both samplers are assigned to units 0 and 1, and that attribute
  locations are queried rather than assumed;
- that all five attributes use `glVertexAttribIPointer` (never the normalising
  float path) with the offsets from `mesh_format.h`;
- that identical uniforms are not re-uploaded, that a draw before a bind in the
  same pass is refused, that `index_offset` becomes a *byte* offset, and that
  per-backend statistics match.

**Not verified**: that a GPU driver renders the same image. That needs hardware.
The software backend is the oracle for the shading maths, and the GL path shares
its uniform block and vertex layout byte for byte, which is the strongest
guarantee available without a device.

## Vulkan: not implemented, on purpose

`--backend vulkan` reports that no Vulkan backend is compiled in, and `rhi.h`
reserves the slot (`BERYL_WITH_VULKAN`, `beryl_vk_create_terrain_shaders`) rather
than pretending. A Vulkan backend needs a hand-written subset of the API
(`VkStructureType` values, format/layout/usage enums, an SPIR-V module built in
tree because there is no shader compiler here). Every one of those numbers would
be transcribed by hand against no header, no validator and no driver in this
environment — a wrong `sType` or `VkFormat` is a silent, hard-to-find bug on real
hardware, and no test in this repo could catch it. Shipping that as "the Vulkan
backend" would be worse than shipping none, so it stays out until it can be built
against a real SDK.

The interface is what makes that cheap: the vtable is 21 calls, the mesh format
and uniform block are backend-neutral, and the GL backend is the worked example
of what a third one has to do.

## Known issues

- **`beryl_pool_drain()` used to lose meshes, silently.** It took a `max` count and
  a single result pointer, and wrote every popped result into that one slot, so a
  caller that asked for the whole queue installed the last build and orphaned all
  the others: about 0.25 MB per captured frame under ASan, with no visible artefact,
  because a dropped section simply stays dirty and gets rebuilt. The API now drains
  into `out[0..max)` — one row per result — and `test_pool` is what keeps it that
  way. The general lesson for an async engine: an ownership handoff with no test on
  it is not a clean design, it is a leak with good manners.
- `beryl_engine_stats().total_quads` and `merge_ratio` are cumulative for the
  process, not per frame — never compare them against one export.
- Occlusion culling does not yet shrink the *draw* set as much as it could:
  sections recorded as empty while the walk expands through air are counted in
  `visible_sections` (they are skipped when drawing).
