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
flag.

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

691 checks in four suites, all of them running against the real generator and the
real mesh data:

- `test_basics` — math, block table, format packing/unpacking, the light
  attenuation rules, PNG structure.
- `test_world` — world/section/chunk bookkeeping, revisions, light. Its central
  test walks *every vertex of every meshed section* and asserts that a face corner
  whose four air-side cells are open sky is baked to full brightness with no
  ambient occlusion, which pins the light sampler to the correct side of the face
  and would catch a "reads the block it belongs to" bug immediately.
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
uniform cache, or using `GL_REPEAT` on the tile array each make the suite fail.

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

- **The demo orphans CPU-side mesh arrays.** Running the demo under
  AddressSanitizer leaks roughly 0.25 MB per frame (4 frames at `--view-distance 4`:
  1200 blocks, all allocated in `build_from_slice` in `src/mesher.c`), and the
  amount grows with the frame count. `./build/beryl_tests` under ASan+UBSan is
  leak-free, so the store teardown and the tested install paths are sound; the
  leak is in a per-frame ownership handoff that the current tests do not cover.
  Reproduce with
  `make clean && make EXTRA_CFLAGS="-fsanitize=address -O1 -g" EXTRA_LDFLAGS="-fsanitize=address" && ./build/beryl --frames 4 --view-distance 4`.
  The first fix is a test: mesh the same sections N times and assert the mesh
  allocation count returns to its starting value.
- `beryl_engine_stats().total_quads` and `merge_ratio` are cumulative for the
  process, not per frame — never compare them against one export.
- Occlusion culling does not yet shrink the *draw* set as much as it could:
  sections recorded as empty while the walk expands through air are counted in
  `visible_sections` (they are skipped when drawing).
