# VulkanMod (vendored + universal build)

This directory vendors **[VulkanMod](https://github.com/xCollateral/VulkanMod)** by
xCollateral — a complete Vulkan-based voxel rendering engine for Minecraft that
replaces the default OpenGL renderer (chunk meshing, section culling, entity
rendering, chunk-building threads, windowed fullscreen, native Wayland, …) —
and packages it two ways:

1. **Per-version builds** — one standalone Fabric Loom build per Minecraft
   version under `mc-<version>/`, producing the upstream-style self-contained
   jar (bundled Fabric API + LWJGL natives) for that exact version.
2. **Universal build** — `universal/` merges every per-version jar into a
   **single jar** that installs on all of them.

## Supported versions

| Code set  | Directory      | Source snapshot (upstream) | Java |
|-----------|----------------|----------------------------|------|
| 1.20.4    | `mc-1.20.4`    | tag `0.4.7`                | 17   |
| 1.21      | `mc-1.21`      | tag `0.5.3`                | 21   |
| 1.21.1    | `mc-1.21.1`    | tag `0.5.5`                | 21   |
| 1.21.10   | `mc-1.21.10`   | tag `0.6.6`                | 21   |
| 1.21.11   | `mc-1.21.11`   | `dev` HEAD (0.6.8-dev)     | 21   |

These are the versions for which upstream VulkanMod ships **compilable**
source (each verified by CI). Upstream also has untagged port commits for
1.21.3 (`e55e432`) and 1.21.4 (`3fa7807`), but neither compiles at any commit
(the 1.21.3 port was never completed on its own, and the 1.21.4 port carries
upstream compile errors), so they are not included rather than shipped
unverified. No other version has a renderer code set; on those, the jar is
inert (see below).

## How the universal jar works

Each per-version jar contains the complete renderer compiled and Loom-remapped
against its own Minecraft version — method and class names differ between
versions, so the code sets cannot share a package. The universal build:

- **relocates** every `net/vulkanmod/**` class of version `V` into
  `net/vulkanmod/mc<V>'/**` (ASM remapping of the whole constant pool;
  references to game classes stay untouched);
- **rewrites** each per-version mixin configuration (package, plugin, refmap)
  to the relocated names;
- **unions** the per-version access wideners (entries for a foreign version
  never match any class of the running game, so they are inert);
- **keeps** the bundled LWJGL natives (newest version wins on ties);
- **drops** the bundled Fabric API classes — the universal jar declares Fabric
  API as a runtime dependency instead, because the per-version API bundles
  would collide and could not be made safe on a foreign game version;
- **installs** one universal client entry point
  (`net.vulkanmod.universal.Initializer`) that reads the running game's
  version and delegates to the matching code set's original `Initializer`.

### Version gating (why one jar is safe on every version)

Every per-version tree carries a version gate in its mixin config plugin
(`net.vulkanmod.mixin.MixinPlugin`):

- `acceptTargets()` — called by Mixin once at startup — compares the running
  game version with the version the tree was compiled against. On a mismatch it
  removes **every target** of that configuration, which removes all of its
  mixins from the transformer's target map. Mixin treats a missing target as a
  no-op (a warning in production environments), so a foreign-version code set
  applies **zero** mixins and never crashes the loader.
- `Initializer.onInitializeClient()` — the same check, so the renderer is never
  initialized on a foreign version either.

The universal entry point dispatches by exact version string; on a version
without a code set it logs and stays inert. Net effect: **the jar can be
dropped into the mods folder of any Minecraft 1.19.4+ instance — on a
supported version the full Vulkan renderer activates; on anything else the
game runs its default renderer unchanged.**

The universal build's assembler (`universal/src/main/java/net/vulkanmod/universal/JarPacker.java`)
re-opens the merged jar and structurally verifies it before CI accepts it:
every class parses with a matching internal name, no class sits outside its
version package, every mixin configuration's entries/plugin/refmap resolve to
jar entries, and the access widener and entry point are present.

## What "one jar" does and does not cover

- **Does:** one file, installable on every listed version, fully self-contained
  apart from Fabric API (install the Fabric API for your version alongside).
- **Does not:** versions without an upstream code set (1.19.x, 1.21.2–9, 25.x/26.x,
  snapshots) — the jar loads there and stays inert by design; it does
  not silently fake a renderer.

## Adding a version

1. Extract the upstream source snapshot for that version into `mc-<version>/`
   (full tree: `build.gradle`, `settings.gradle`, `gradle/`, `src/`,
   `gradle.properties`, `LICENSE`).
2. Apply the same gate to `mixin/MixinPlugin.java` and `Initializer.java`
   (copy from any sibling tree, change the `EXPECTED_MC_VERSION` constant).
3. Bump stale `fabric-loom` snapshot pins to a stable release if needed.
4. Add the version to `.github/workflows/vulkanmod.yml` (matrix + the universal
   job's version list) and to the dispatch map in
   `universal/src/main/java/net/vulkanmod/universal/Initializer.java`.

## Building locally

```sh
# one exact-version jar (upstream layout)
cd mc-1.21.11 && ./gradlew build

# the universal jar (after all per-version builds exist)
cd universal && ./gradlew assembleUniversal \
  --args="--in 1.21.11=../mc-1.21.11/build/libs/VulkanMod_*.jar ... --out build/VulkanModUniversal.jar --version 1.0.0+universal"
```

CI (`.github/workflows/vulkanmod.yml`) runs the full matrix plus the universal
merge on every push and uploads:

- `vulkanmod-mc-<version>` — the self-contained per-version jar
- `vulkanmod-universal` — the single multi-version jar

## License

VulkanMod is **LGPL-3.0** — Copyright (c) xCollateral. The full license text is
kept in every `mc-*/LICENSE` and inside the built jars. The universal
packaging glue (entry point, jar packer, gates, workflow) is Beryllium code,
MIT — see `LICENSE_BERYLLIUM` inside the universal jar.
