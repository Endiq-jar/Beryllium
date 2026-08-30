/* occlusion.h -- section-graph visibility, the second half of "why is Sodium
 * fast".
 *
 * Frustum culling alone is not enough in a voxel world: a cave system behind a
 * hill, or everything on the far side of a mountain, is inside the frustum and
 * still costs a draw. Sodium solves this by walking a graph of sections starting
 * at the camera and refusing to cross a section face that is sealed by solid
 * geometry. This is the same algorithm:
 *
 *   - every section publishes, per axis, the set of 16x16 columns that are solid
 *     for the full 16 voxels along that axis (chunk.c: section->occluder[]),
 *   - a direction is "sealed" when all 256 columns are, meaning no line of sight
 *     can pass through that face at all,
 *   - the walk starts at the camera's section, expands to neighbours whose face
 *     is front-facing relative to the eye, and refuses to cross sealed faces.
 *
 * Two properties make this safe to ship: it is only ever a *rejection* rule built
 * from conservative geometry (a missing section is never an occluder, so the
 * unloaded frontier cannot swallow visible content), and the test suite checks it
 * against a brute-force raycast, so "the culler never hides something visible" is
 * an invariant, not a hope.
 */
#ifndef BERYL_OCCLUSION_H
#define BERYL_OCCLUSION_H

#include "camera.h"
#include "world.h"

typedef struct BerylVisibleEntry {
	int32_t cx, csy, cz;
	uint64_t key;
	float    distance_sq;
	uint8_t  faces;       /* which faces point at the camera */
	bool     empty;       /* section exists but has no geometry */
} BerylVisibleEntry;

typedef struct BerylVisibleSet {
	BerylVisibleEntry *entries;
	int count;
	int capacity;
	/* visited-table: open-addressed, stamped per frame so it needs no clearing */
	uint64_t *visit_keys;
	uint32_t *visit_stamp;
	uint32_t  frame;
	int       visit_mask;

	/* stats */
	int visited_sections;
	int culled_by_occlusion;
	int culled_by_frustum;
} BerylVisibleSet;

void beryl_visible_set_init(BerylVisibleSet *v);
void beryl_visible_set_free(BerylVisibleSet *v);

/* Computes the visible set for one frame. `radius` is in sections (a cube around
 * the camera, matching the loader). Returns the number of visible sections. */
int beryl_visible_set_compute(BerylVisibleSet *v, BerylWorld *w, const BerylCamera *cam,
                              int radius_sections);

/* Linear probe used by tests/debug. */
bool beryl_visible_set_contains(const BerylVisibleSet *v, int32_t cx, int32_t csy, int32_t cz);

#endif /* BERYL_OCCLUSION_H */
