/* chunk.c -- section/chunk storage and derived-mask computation. */
#include "chunk.h"

#include <stdlib.h>
#include <string.h>

BerylSection *beryl_section_new(int32_t cx, int32_t cz, int32_t sy) {
	BerylSection *s = (BerylSection *)calloc(1, sizeof(BerylSection));
	if (!s) return NULL;
	s->cx = cx; s->cz = cz; s->sy = sy;
	s->all_air = true;
	s->dirty = true;
	s->light_dirty = true;
	s->min_y = 0;
	s->max_y = BERYL_SECTION_SIDE;
	return s;
}

void beryl_section_free(BerylSection *s) { free(s); }

bool beryl_section_is_solid(const BerylSection *s, int lx, int ly, int lz) {
	uint32_t i = (uint32_t)beryl_section_index(lx, ly, lz);
	return (s->solid[i >> 6] >> (i & 63)) & 1u;
}

bool beryl_section_is_opaque(const BerylSection *s, int lx, int ly, int lz) {
	uint32_t i = (uint32_t)beryl_section_index(lx, ly, lz);
	return (s->opaque[i >> 6] >> (i & 63)) & 1u;
}

void beryl_section_recompute_derived(BerylSection *s) {
	memset(s->solid, 0, sizeof(s->solid));
	memset(s->opaque, 0, sizeof(s->opaque));
	memset(s->occluder, 0, sizeof(s->occluder));
	s->non_air = 0;
	s->min_y = BERYL_SECTION_SIDE;
	s->max_y = 0;

	for (int y = 0; y < BERYL_SECTION_SIDE; y++) {
		for (int z = 0; z < BERYL_SECTION_SIDE; z++) {
			for (int x = 0; x < BERYL_SECTION_SIDE; x++) {
				uint32_t i = (uint32_t)beryl_section_index(x, y, z);
				beryl_bid id = (beryl_bid)s->states[i];
				if (id == BERYL_BLOCK_AIR) continue;
				s->non_air++;
				if (y < s->min_y) s->min_y = y;
				if (y + 1 > s->max_y) s->max_y = y + 1;
				const BerylBlockInfo *bi = beryl_block_info(id);
				if (bi->flags & BERYL_BFLG_OPAQUE) s->opaque[i >> 6] |= 1ull << (i & 63);
				/* An occluder must be both cube-shaped and light-blocking: glass and
				 * water fill the voxel but you can see through them, so they must not
				 * hide geometry behind them. */
				if ((bi->flags & (BERYL_BFLG_OPAQUE | BERYL_BFLG_SOLID))
				    == (BERYL_BFLG_OPAQUE | BERYL_BFLG_SOLID)) {
					s->solid[i >> 6] |= 1ull << (i & 63);
				}
			}
		}
	}
	s->all_air = (s->non_air == 0);
	/* A section that is solid *and* opaque everywhere (end stone room, a filled
	 * area) can be dropped from the mesh entirely and reported as sealing all
	 * six faces to the culler. */
	s->all_opaque = false;
	if (s->non_air == BERYL_SECTION_VOL) {
		s->all_opaque = true;
		for (uint32_t i = 0; i < BERYL_SECTION_VOL / 64; i++) {
			if (s->solid[i] != ~(uint64_t)0) { s->all_opaque = false; break; }
		}
	}

	/* Occluder columns: for direction d, walk the 16x16 grid of the far plane and
	 * require every voxel along d to be solid. Faces 0..5 map to
	 * DOWN/UP/NORTH/SOUTH/WEST/EAST (see blocks.h). */
	/* A column that is solid for the full extent of the axis blocks line of
	 * sight in BOTH directions along that axis, so one mask serves the + and -
	 * face of the axis pair. */
	static const int k_axis[6] = { 1, 1, 2, 2, 0, 0 };
	for (int f = 0; f < 6; f++) {
		int axis = k_axis[f];
		for (int a = 0; a < BERYL_SECTION_SIDE; a++) {
			for (int b = 0; b < BERYL_SECTION_SIDE; b++) {
				bool occludes = true;
				for (int k = 0; k < BERYL_SECTION_SIDE; k++) {
					int x, y, z;
					if (axis == 0)      { x = k; y = a; z = b; }
					else if (axis == 1) { x = a; y = k; z = b; }
					else                { x = a; y = b; z = k; }
					uint32_t i = (uint32_t)beryl_section_index(x, y, z);
					if (!((s->solid[i >> 6] >> (i & 63)) & 1u)) { occludes = false; break; }
				}
				if (occludes) {
					uint32_t col = (uint32_t)(a * BERYL_SECTION_SIDE + b);
					s->occluder[f][col >> 6] |= 1ull << (col & 63);
				}
			}
		}
	}

	s->sealed_faces = 0;
	for (int f = 0; f < 6; f++) {
		bool sealed = true;
		for (uint32_t w = 0; w < BERYL_OCCL_WORD_COUNT; w++) {
			if (s->occluder[f][w] != ~(uint64_t)0) { sealed = false; break; }
		}
		if (sealed) s->sealed_faces |= (uint8_t)(1u << f);
	}
}

BerylChunk *beryl_chunk_new(int32_t x, int32_t z) {
	BerylChunk *c = (BerylChunk *)calloc(1, sizeof(BerylChunk));
	if (!c) return NULL;
	c->x = x; c->z = z;
	return c;
}

void beryl_chunk_free(BerylChunk *c) {
	if (!c) return;
	for (int i = 0; i < BERYL_CHUNK_SECTIONS; i++) {
		beryl_section_free(c->sections[i]);
	}
	free(c);
}

BerylSection *beryl_chunk_section_at(BerylChunk *c, int32_t sy, bool create) {
	if (sy < 0 || sy >= BERYL_CHUNK_SECTIONS) {
		return NULL;
	}
	if (!c->sections[sy] && create) {
		c->sections[sy] = beryl_section_new(c->x, c->z, sy);
	}
	return c->sections[sy];
}

void beryl_chunk_mark_dirty(BerylChunk *c, int32_t sy) {
	for (int i = sy - 1; i <= sy + 1; i++) {
		BerylSection *s = beryl_chunk_section_at(c, i, false);
		if (s) {
			s->dirty = true;
			s->revision++;
		}
	}
}

void beryl_chunk_rebuild_top_map(BerylChunk *c) {
	for (int z = 0; z < BERYL_SECTION_SIDE; z++) {
		for (int x = 0; x < BERYL_SECTION_SIDE; x++) {
			int top = BERYL_WORLD_MIN_Y;
			for (int sy = BERYL_CHUNK_SECTIONS - 1; sy >= 0; sy--) {
				BerylSection *s = c->sections[sy];
				if (!s || s->all_air) continue;
				for (int y = BERYL_SECTION_SIDE - 1; y >= 0; y--) {
					if (s->states[beryl_section_index(x, y, z)] != BERYL_BLOCK_AIR) {
						top = sy * BERYL_SECTION_SIDE + y + 1;
						goto done;
					}
				}
			}
			done:
			c->top[z * BERYL_SECTION_SIDE + x] = (uint8_t)BERYL_MIN(top, 255);
		}
	}
}

bool beryl_chunk_is_empty(const BerylChunk *c) {
	for (int i = 0; i < BERYL_CHUNK_SECTIONS; i++) {
		BerylSection *s = c->sections[i];
		if (s && !s->all_air) return false;
	}
	return true;
}
