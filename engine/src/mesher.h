/* mesher.h -- section -> triangle mesh, Sodium style.
 *
 * What this does per section (see README for the comparison with Sodium):
 *   1. face culling against the 3x3x3 slice, so faces touching a neighbour are
 *      not emitted at all (never: "emit then cull"),
 *   2. coplanar greedy merging into rectangles, which is what collapses a flat
 *      16x16 floor from 256 quads to 1,
 *   3. baked smooth lighting: 4-level ambient occlusion per quad corner plus the
 *      packed sky/block light nibbles, sampled from the *air* side of the face so
 *      merged rectangles keep correct corners,
 *   4. layer bucketing (solid / cutout / blend) so one section is at most three
 *      state changes and three draws,
 *   5. output straight into the final interleaved vertex layout: the upload path
 *      is a single memcpy per layer.
 */
#ifndef BERYL_MESHER_H
#define BERYL_MESHER_H

#include "mesh_format.h"
#include "world.h"

/* Builds `out` from the section at (cx, csy, cz). Returns false when the slice
 * is not fully available (neighbour sections missing) and the caller should
 * retry later, or on OOM. */
bool beryl_mesh_section(BerylWorld *w, int32_t cx, int32_t csy, int32_t cz,
                        BerylSectionMesh *out);

/* Same, but from an already-held slice (the builder pool uses this to avoid a
 * second lookup, and tests use it to mesh synthetic slices). */
bool beryl_mesh_slice(const BerylSlice *slice, BerylSectionMesh *out);

/* Statistics from the last build, useful for the overlay and for tests. */
typedef struct BerylMeshStats {
	uint32_t quads[BERYL_LAYER_COUNT];
	uint32_t quads_culled_by_neighbour;
	uint32_t quads_culled_same_type;   /* Beryllium's leaf internal-face cull */
	uint32_t faces_examined;
	uint32_t merged_cells;             /* cells absorbed into a bigger quad    */
	float    merge_ratio;              /* (faces_examined / quads)             */
} BerylMeshStats;
void beryl_mesh_last_stats(BerylMeshStats *out);

#endif /* BERYL_MESHER_H */
