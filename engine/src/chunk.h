/* chunk.h -- section storage. The unit of everything in this engine.
 *
 * A section is a 16x16x16 cube holding block states, packed light, a solid
 * bitset, and the derived data the renderer asks for (occlusion masks, height
 * extents). Sections are allocated lazily: an all-air section costs one pointer,
 * which matters a lot at view distance 12+ where most of the box is sky.
 */
#ifndef BERYL_CHUNK_H
#define BERYL_CHUNK_H

#include "blocks.h"
#include "bmath.h"

#define BERYL_OCCL_WORD_COUNT (BERYL_SECTION_AREA / 64) /* 4 x uint64 = 256 cols */

typedef struct BerylSection {
	/* --- identity --- */
	int32_t cx, cz;     /* owning chunk coords (block origin = cx*16, cz*16) */
	int32_t sy;         /* section index along Y (world y = sy*16)           */

	/* --- authoritative data --- */
	beryl_state states[BERYL_SECTION_VOL];   /* block id per voxel          */
	uint8_t     light[BERYL_SECTION_VOL];    /* low nibble sky, high block  */
	uint64_t    solid[BERYL_SECTION_VOL / 64]; /* BFLG_SOLID|OPAQUE bitset  */
	uint64_t    opaque[BERYL_SECTION_VOL / 64];/* BFLG_OPAQUE bitset        */

	/* --- derived, recomputed on rebuild --- */
	uint32_t non_air;
	int32_t  min_y, max_y;                  /* local [min,max) of non-air     */
	bool     all_air;
	bool     all_opaque;
	/* For each direction: the 16x16 columns of the *far plane* of this section
	 * that are solid for all 16 voxels along that direction. If a column is set,
	 * no line of sight through it can leave/enter the section, which is the core
	 * of the occlusion graph (see occlusion.c). */
	uint64_t occluder[6][BERYL_OCCL_WORD_COUNT];
	/* Per-face "this side is completely sealed" bit, derived from occluder. */
	uint8_t  sealed_faces;

	/* --- bookkeeping --- */
	uint64_t revision;      /* bumped on every content change           */
	uint64_t mesh_revision; /* revision the current mesh was built from */
	bool     dirty;         /* needs remesh                               */
	bool     light_dirty;
	int32_t  last_error;
} BerylSection;

typedef struct BerylChunk {
	int32_t x, z;
	BerylSection *sections[BERYL_CHUNK_SECTIONS];
	uint8_t  top[BERYL_SECTION_AREA]; /* highest non-air world y (0..128) per column */
	bool     generated;
	bool     lit;
	struct BerylChunk *hash_next;
} BerylChunk;

/* ------------------------------------------------------------- section ---- */
BerylSection *beryl_section_new(int32_t cx, int32_t cz, int32_t sy);
void          beryl_section_free(BerylSection *s);

static inline beryl_bid beryl_section_get_state(const BerylSection *s, int lx, int ly, int lz) {
	return (beryl_bid)s->states[beryl_section_index(lx, ly, lz)];
}
static inline void beryl_section_set_state(BerylSection *s, int lx, int ly, int lz, beryl_bid id) {
	s->states[beryl_section_index(lx, ly, lz)] = (beryl_state)id;
}
static inline int beryl_section_get_sky_light(const BerylSection *s, int lx, int ly, int lz) {
	return s->light[beryl_section_index(lx, ly, lz)] & 0xF;
}
static inline int beryl_section_get_block_light(const BerylSection *s, int lx, int ly, int lz) {
	return (s->light[beryl_section_index(lx, ly, lz)] >> 4) & 0xF;
}
static inline void beryl_section_set_sky_light(BerylSection *s, int lx, int ly, int lz, int v) {
	size_t i = (size_t)beryl_section_index(lx, ly, lz);
	s->light[i] = (uint8_t)((s->light[i] & 0xF0) | (uint8_t)(v & 0xF));
}
static inline void beryl_section_set_block_light(BerylSection *s, int lx, int ly, int lz, int v) {
	size_t i = (size_t)beryl_section_index(lx, ly, lz);
	s->light[i] = (uint8_t)((s->light[i] & 0x0F) | (uint8_t)((v & 0xF) << 4));
}

bool beryl_section_is_solid(const BerylSection *s, int lx, int ly, int lz);
bool beryl_section_is_opaque(const BerylSection *s, int lx, int ly, int lz);

/* Recomputes non_air/min_y/max_y/solid/opaque/occluder/sealed_faces. Called by
 * the mesh builder, because it already walks every voxel. */
void beryl_section_recompute_derived(BerylSection *s);

/* World-space block position -> section-local coords. */
static inline void beryl_worldpos_to_local(int wx, int wy, int wz,
                                           int32_t scx, int32_t scz, int32_t ssy,
                                           int *lx, int *ly, int *lz) {
	*lx = (wx - scx * BERYL_SECTION_SIDE) & (BERYL_SECTION_SIDE - 1);
	*ly = (wy - ssy * BERYL_SECTION_SIDE) & (BERYL_SECTION_SIDE - 1);
	*lz = (wz - scz * BERYL_SECTION_SIDE) & (BERYL_SECTION_SIDE - 1);
}

/* --------------------------------------------------------------- chunk ----- */
BerylChunk *beryl_chunk_new(int32_t x, int32_t z);
void        beryl_chunk_free(BerylChunk *c);
/* Fetches (creating on demand) the section holding (lx,ly,lz) of this chunk. */
BerylSection *beryl_chunk_section_at(BerylChunk *c, int32_t sy, bool create);
/* Marks a section dirty plus its neighbours if the edit touched a border. */
void beryl_chunk_mark_dirty(BerylChunk *c, int32_t sy);
void beryl_chunk_rebuild_top_map(BerylChunk *c);
bool beryl_chunk_is_empty(const BerylChunk *c);

#endif /* BERYL_CHUNK_H */
