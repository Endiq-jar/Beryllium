/* world.h -- the chunk map: sparse storage, on-demand generation, editing.
 *
 * Concurrency model (deliberately simple, deliberately honest):
 *   - one pthread_rwlock guards the chunk map and all section contents,
 *   - mesh/lighting workers hold it in read mode for the duration of one job,
 *   - the main thread takes it in write mode to generate or edit blocks.
 * This is the same trade Sodium makes with its per-chunk view lock: builders may
 * run concurrently with each other, and a block edit serializes against them.
 */
#ifndef BERYL_WORLD_H
#define BERYL_WORLD_H

#include "chunk.h"

/* 3x3x3 section neighbourhood around a target section. The mesher and the
 * light engine sample through this so cross-section faces and light spread
 * work without per-voxel hash lookups. */
typedef struct BerylSlice {
	BerylSection *sec[3][3][3]; /* indexed by [dx+1][dy+1][dz+1] */
	int32_t ox, oy, oz;        /* world coords of the min corner of sec[-1][-1][-1] */
	int32_t ccx, csy, ccz;     /* centre section coords */
} BerylSlice;

#define BERYL_SLICE_SIDE (3 * BERYL_SECTION_SIDE)
static inline bool beryl_slice_has(const BerylSlice *s, int x, int y, int z) {
	(void)s;
	return x >= 0 && x < BERYL_SLICE_SIDE &&
	       y >= 0 && y < BERYL_SLICE_SIDE &&
	       z >= 0 && z < BERYL_SLICE_SIDE;
}
static inline BerylSection *beryl_slice_section(const BerylSlice *s, int x, int y, int z) {
	if (!beryl_slice_has(s, x, y, z)) return NULL;
	return s->sec[x >> 4][y >> 4][z >> 4];
}
static inline beryl_bid beryl_slice_get_state(const BerylSlice *s, int x, int y, int z) {
	BerylSection *sec = beryl_slice_section(s, x, y, z);
	if (!sec) return BERYL_BLOCK_AIR;
	return (beryl_bid)sec->states[beryl_section_index(x & 15, y & 15, z & 15)];
}
static inline uint8_t beryl_slice_get_light(const BerylSlice *s, int x, int y, int z) {
	BerylSection *sec = beryl_slice_section(s, x, y, z);
	if (!sec) return 0;
	return sec->light[beryl_section_index(x & 15, y & 15, z & 15)];
}
/* Caller guarantees 0 <= x,y,z < BERYL_SLICE_SIDE (the mesher's AO taps are all
 * inside that window). Sections may still be NULL at the unloaded frontier, so
 * the only shortcut versus the checked accessor is skipping the range test and
 * the hash lookup -- that is the whole point, and it stays safe. */
static inline beryl_bid beryl_slice_get_state_unchecked(const BerylSlice *s, int x, int y, int z) {
	BerylSection *sec = s->sec[x >> 4][y >> 4][z >> 4];
	if (!sec) return BERYL_BLOCK_AIR;
	return (beryl_bid)sec->states[beryl_section_index(x & 15, y & 15, z & 15)];
}
static inline uint8_t beryl_slice_get_light_unchecked(const BerylSlice *s, int x, int y, int z) {
	BerylSection *sec = s->sec[x >> 4][y >> 4][z >> 4];
	if (!sec) return 0;
	return sec->light[beryl_section_index(x & 15, y & 15, z & 15)];
}

typedef struct BerylWorldDesc {
	uint64_t seed;
	int32_t  radius_sections;   /* generated box, in sections, from origin   */
	bool     caves;
	bool     trees;
	bool     water;
	float    sea_level;         /* world y of the water surface               */
} BerylWorldDesc;

typedef struct BerylWorld BerylWorld;

BerylWorld *beryl_world_new(const BerylWorldDesc *desc);
void        beryl_world_free(BerylWorld *w);
void        beryl_world_desc(const BerylWorld *w, BerylWorldDesc *out);

int64_t     beryl_world_seed(const BerylWorld *w);
int32_t     beryl_world_radius(const BerylWorld *w);

/* --- locking (exposed for the builder pool; reentrancy is not supported) --- */
void beryl_world_lock_read(BerylWorld *w);
void beryl_world_unlock_read(BerylWorld *w);
void beryl_world_lock_write(BerylWorld *w);
void beryl_world_unlock_write(BerylWorld *w);

/* --- chunk / section access. Coordinates are chunk or section coords. ------ */
BerylChunk   *beryl_world_chunk(BerylWorld *w, int32_t cx, int32_t cz, bool create);
BerylSection *beryl_world_section(BerylWorld *w, int32_t cx, int32_t sy, int32_t cz, bool create);
bool          beryl_world_fill_slice(BerylWorld *w, int32_t cx, int32_t sy, int32_t cz, BerylSlice *out);
int           beryl_world_chunk_count(const BerylWorld *w);
int           beryl_world_section_count(const BerylWorld *w);

/* --- block access in world coordinates ------------------------------------ */
beryl_bid beryl_world_get_block(BerylWorld *w, int wx, int wy, int wz);
/* Edits are refused (return false) outside the generated box or world height.
 * The affected sections are marked dirty; caller decides about relighting. */
bool      beryl_world_set_block(BerylWorld *w, int wx, int wy, int wz, beryl_bid id);
void      beryl_world_get_light(BerylWorld *w, int wx, int wy, int wz, int *sky, int *block);
bool      beryl_world_is_solid(BerylWorld *w, int wx, int wy, int wz);
int       beryl_world_top_y(BerylWorld *w, int wx, int wz);   /* highest non-air +1 */

/* --- generation ----------------------------------------------------------- */
/* Generates terrain + lighting for the box of chunks [x0,x1] x [z0,z1]
 * inclusive, skipping chunks that already exist. Returns chunks generated.
 * Safe to call repeatedly; it is how the view-distance loader ticks. */
int beryl_world_generate_area(BerylWorld *w, int32_t x0, int32_t z0, int32_t x1, int32_t z1);
/* Ensures every chunk whose sections can be seen from `center` within
 * radius_sections is present. max_chunks bounds per-call work (loader budget).
 * Returns the number of chunks still missing after this call. */
int beryl_world_update_loader(BerylWorld *w, BerylVec3i camera_block, int radius_sections,
                              int max_chunks, int *generated_out);
/* Marks a section and its border neighbours dirty for remesh. */
void beryl_world_mark_dirty(BerylWorld *w, int32_t cx, int32_t sy, int32_t cz, bool include_neighbours);

/* Iterates all sections; the callback may not modify the chunk map. */
typedef bool (*beryl_section_iter_fn)(void *user, BerylSection *s);
void beryl_world_for_each_section(BerylWorld *w, beryl_section_iter_fn fn, void *user);

/* Stats that the overlay reads. */
typedef struct BerylWorldStats {
	int chunks, sections, non_empty_sections, dirty_sections, generated_chunks;
} BerylWorldStats;
void beryl_world_stats(BerylWorld *w, BerylWorldStats *out);

#endif /* BERYL_WORLD_H */
