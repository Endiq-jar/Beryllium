# Third-party code

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
