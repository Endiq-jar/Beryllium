# Third-party code

## VulkanMod (Vulkan rendering engine)

The entire `vulkanmod/` directory is a vendored copy of
**VulkanMod** (<https://github.com/xCollateral/VulkanMod>), a Vulkan based
voxel rendering engine that replaces Minecraft's OpenGL renderer, by
xCollateral.

- Copyright (c) xCollateral, released under the **GNU Lesser General Public
  License, version 3** (LGPL-3.0). The full license text is kept in every
  `vulkanmod/mc-*/LICENSE` file and inside the built jars.
- The per-version trees (`vulkanmod/mc-1.20.4` … `vulkanmod/mc-1.21.11`) are
  upstream source snapshots (tags `0.4.7`, `0.5.3`, `0.5.5`, `0.6.6`, `dev`
  HEAD, plus the untagged 1.21.3/1.21.4 port commits), kept complete so each
  remains an independent, rebuildable Fabric Loom project.
- The additions made for the Beryllium integration are: a version gate in
  each tree's `mixin/MixinPlugin` (removes all mixin targets when the running
  game version doesn't match the tree's compiled version, keeping the jar
  launch-safe and inert on foreign versions), the matching guard in
  `Initializer`, and the `vulkanmod/universal/` assembler (version-dispatch
  entry point + ASM-based jar merger with structural verification), which is
  Beryllium code under the MIT license of this repository.

## Voxel shape optimization suite

The code under `com.endiq.beryllium.engine.voxelshape` and the mixins under
`com.endiq.beryllium.mixin.common.shapes` are ports of the shape-optimization features
from **Lithium** (<https://github.com/CaffeineMC/lithium>), specifically:

- Specialized shape types (`VoxelShapeEmpty`, `VoxelShapeSimpleCube`,
  `VoxelShapeAlignedCuboid`, `VoxelShapeAlignedCuboidOffset`, `CuboidVoxelSet`)
- Precomputed coordinate ranges (`CubeVoxelShape`/`CubePointRange` mixins)
- Fast shape merging (`BerylliumDoublePairList`, the `createIndexMerger` mixin)
- Fast "matches anywhere" checks (`VoxelShapeMatchesAnywhere`, the
  `joinIsNotEmpty` mixin)
- The `isShapeFullBlock` cache (`Object2BooleanCacheTable`, the `Block` mixin)
- Lazy `EntityCollisionContext` item/fluid checks

Lithium is Copyright (c) 2018 JellySquid and contributors, and is released under the
MIT License. The upstream 1.21.4 implementation by JellySquid, 2No2Name and gegy1000
is reproduced here with permission granted by that license, with package names and
class names changed for Beryllium. See `src/main/java/com/endiq/beryllium/engine/` for
per-file attribution headers.
