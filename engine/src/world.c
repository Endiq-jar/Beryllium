/* world.c -- sparse chunk map with open hashing, loader, and editing. */
#include "world.h"
#include "worldgen.h"
#include "light.h"

#include <stdlib.h>
#include <string.h>
#include <pthread.h>

#define BERYL_WORLD_BUCKET_BITS 13   /* 8192 buckets; grows by rehash if needed */

struct BerylWorld {
	BerylWorldDesc desc;
	BerylChunk    *buckets[1 << BERYL_WORLD_BUCKET_BITS];
	int chunk_count;
	int generated_chunks;
	pthread_rwlock_t lock;
};

static uint64_t chunk_hash(int32_t x, int32_t z) {
	uint64_t h = ((uint64_t)(uint32_t)x * 0x9E3779B97F4A7C15ull)
	           ^ ((uint64_t)(uint32_t)z * 0xC2B2AE3D27D4EB4Full);
	h ^= h >> 29;
	h *= 0xBF58476D1CE4E5B9ull;
	h ^= h >> 32;
	return h & ((1u << BERYL_WORLD_BUCKET_BITS) - 1);
}

BerylWorld *beryl_world_new(const BerylWorldDesc *desc) {
	BerylWorld *w = (BerylWorld *)calloc(1, sizeof(BerylWorld));
	if (!w) return NULL;
	if (desc) w->desc = *desc;
	else {
		w->desc.seed = 1;
		w->desc.radius_sections = 8;
		w->desc.caves = true;
		w->desc.trees = true;
		w->desc.water = true;
		w->desc.sea_level = 62.0f;
	}
	pthread_rwlock_init(&w->lock, NULL);
	beryl_blocks_init();
	return w;
}

static void free_chunks(BerylWorld *w) {
	size_t n = sizeof(w->buckets) / sizeof(w->buckets[0]);
	for (size_t i = 0; i < n; i++) {
		BerylChunk *c = w->buckets[i];
		while (c) {
			BerylChunk *next = c->hash_next;
			beryl_chunk_free(c);
			c = next;
		}
		w->buckets[i] = NULL;
	}
}

void beryl_world_free(BerylWorld *w) {
	if (!w) return;
	free_chunks(w);
	pthread_rwlock_destroy(&w->lock);
	free(w);
}

void beryl_world_desc(const BerylWorld *w, BerylWorldDesc *out) { *out = w->desc; }
int64_t beryl_world_seed(const BerylWorld *w) { return (int64_t)w->desc.seed; }
int32_t beryl_world_radius(const BerylWorld *w) { return w->desc.radius_sections; }

void beryl_world_lock_read(BerylWorld *w)   { pthread_rwlock_rdlock(&w->lock); }
void beryl_world_unlock_read(BerylWorld *w) { pthread_rwlock_unlock(&w->lock); }
void beryl_world_lock_write(BerylWorld *w)   { pthread_rwlock_wrlock(&w->lock); }
void beryl_world_unlock_write(BerylWorld *w){ pthread_rwlock_unlock(&w->lock); }

BerylChunk *beryl_world_chunk(BerylWorld *w, int32_t cx, int32_t cz, bool create) {
	uint64_t b = chunk_hash(cx, cz);
	BerylChunk *c = w->buckets[b];
	while (c) {
		if (c->x == cx && c->z == cz) return c;
		c = c->hash_next;
	}
	if (!create) return NULL;
	c = beryl_chunk_new(cx, cz);
	if (!c) return NULL;
	c->hash_next = w->buckets[b];
	w->buckets[b] = c;
	w->chunk_count++;
	return c;
}

BerylSection *beryl_world_section(BerylWorld *w, int32_t cx, int32_t sy, int32_t cz, bool create) {
	BerylChunk *c = beryl_world_chunk(w, cx, cz, create);
	if (!c) return NULL;
	BerylSection *s = beryl_chunk_section_at(c, sy, create);
	if (s && !s->all_air) { /* nothing to do; hook for future section registry */ }
	return s;
}

bool beryl_world_fill_slice(BerylWorld *w, int32_t cx, int32_t sy, int32_t cz, BerylSlice *out) {
	out->ccx = cx; out->csy = sy; out->ccz = cz;
	out->ox = (cx - 1) * BERYL_SECTION_SIDE;
	out->oy = (sy - 1) * BERYL_SECTION_SIDE;
	out->oz = (cz - 1) * BERYL_SECTION_SIDE;
	int present = 0;
	for (int dz = -1; dz <= 1; dz++) {
		for (int dy = -1; dy <= 1; dy++) {
			for (int dx = -1; dx <= 1; dx++) {
				BerylSection *s = beryl_world_section(w, cx + dx, sy + dy, cz + dz, false);
				out->sec[dx + 1][dy + 1][dz + 1] = s;
				present += s != NULL;
			}
		}
	}
	/* The centre must exist; edge sections may be missing (view border) and read
	 * as air, which is exactly the behaviour we want at the unloaded frontier:
	 * faces at the border stay visible instead of disappearing. */
	return out->sec[1][1][1] != NULL && present > 0;
}

beryl_bid beryl_world_get_block(BerylWorld *w, int wx, int wy, int wz) {
	if (wy < BERYL_WORLD_MIN_Y || wy >= BERYL_WORLD_MAX_Y) return BERYL_BLOCK_AIR;
	int cx = wx >> 4, cz = wz >> 4;
	BerylChunk *c = beryl_world_chunk(w, cx, cz, false);
	if (!c) return BERYL_BLOCK_AIR;
	BerylSection *s = c->sections[wy >> 4];
	if (!s) return BERYL_BLOCK_AIR;
	return (beryl_bid)s->states[beryl_section_index(wx & 15, wy & 15, wz & 15)];
}

bool beryl_world_set_block(BerylWorld *w, int wx, int wy, int wz, beryl_bid id) {
	if (wy < BERYL_WORLD_MIN_Y || wy >= BERYL_WORLD_MAX_Y) return false;
	int cx = wx >> 4, cz = wz >> 4, sy = wy >> 4;
	BerylChunk *c = beryl_world_chunk(w, cx, cz, false);
	if (!c || !c->generated) return false;
	BerylSection *s = beryl_chunk_section_at(c, sy, id != BERYL_BLOCK_AIR);
	if (!s) return false;
	size_t i = (size_t)beryl_section_index(wx & 15, wy & 15, wz & 15);
	if (s->states[i] == (beryl_state)id) return true;
	beryl_bid replaced = s->states[i];
	s->states[i] = (beryl_state)id;
	s->revision++;
	beryl_world_mark_dirty(w, cx, sy, cz, true);
	if (id == BERYL_BLOCK_AIR) {
		s->light[i] = 0; /* removed block: dark until the light engine passes */
	}
	beryl_light_queue_edit_source(w, wx, wy, wz, (int)beryl_block_light_emission(replaced));
	/* Derived masks and the column height map are read by the culler and by
	 * anything that asks where the surface is, so they move with the block. */
	beryl_section_recompute_derived(s);
	beryl_chunk_rebuild_top_map(c);
	return true;
}

void beryl_world_get_light(BerylWorld *w, int wx, int wy, int wz, int *sky, int *block) {
	*sky = *block = 0;
	if (wy < BERYL_WORLD_MIN_Y || wy >= BERYL_WORLD_MAX_Y) return;
	BerylSection *s = beryl_world_section(w, wx >> 4, wy >> 4, wz >> 4, false);
	if (!s) { *sky = 15; return; }   /* unloaded = open sky, avoids dark borders */
	uint8_t v = s->light[beryl_section_index(wx & 15, wy & 15, wz & 15)];
	*sky = v & 0xF;
	*block = (v >> 4) & 0xF;
}

bool beryl_world_is_solid(BerylWorld *w, int wx, int wy, int wz) {
	beryl_bid id = beryl_world_get_block(w, wx, wy, wz);
	return beryl_block_flag(id, BERYL_BFLG_SOLID);
}

int beryl_world_top_y(BerylWorld *w, int wx, int wz) {
	BerylChunk *c = beryl_world_chunk(w, wx >> 4, wz >> 4, false);
	if (c && c->generated) {
		return c->top[(wz & 15) * BERYL_SECTION_SIDE + (wx & 15)];
	}
	return BERYL_WORLD_MIN_Y;
}

void beryl_world_mark_dirty(BerylWorld *w, int32_t cx, int32_t sy, int32_t cz, bool include_neighbours) {
	if (include_neighbours) {
		/* Neighbour sections need re-meshing when an edit touches a section edge;
		 * marking the whole neighbour is what vanilla and Sodium do too (cheap,
		 * and the mesher skips untouched sections by revision). */
		for (int dz = -1; dz <= 1; dz++) {
			for (int dx = -1; dx <= 1; dx++) {
				BerylChunk *c = beryl_world_chunk(w, cx + dx, cz + dz, false);
				if (c) beryl_chunk_mark_dirty(c, sy);
			}
		}
	} else {
		BerylChunk *c = beryl_world_chunk(w, cx, cz, false);
		if (c) beryl_chunk_mark_dirty(c, sy);
	}
	(void)w;
}

int beryl_world_generate_area(BerylWorld *w, int32_t x0, int32_t z0, int32_t x1, int32_t z1) {
	int made = 0;
	for (int32_t cz = z0; cz <= z1; cz++) {
		for (int32_t cx = x0; cx <= x1; cx++) {
			BerylChunk *c = beryl_world_chunk(w, cx, cz, true);
			if (c->generated) continue;
			beryl_worldgen_generate_chunk(w, c);
			c->generated = true;
			c->lit = false;
			w->generated_chunks++;
			made++;
		}
	}
	if (made > 0) {
		beryl_light_relight_area(w, x0, z0, x1, z1);
		/* New geometry changes neighbour face visibility; bump the border. */
		for (int32_t cz = z0 - 1; cz <= z1 + 1; cz++) {
			for (int32_t cx = x0 - 1; cx <= x1 + 1; cx++) {
				BerylChunk *c = beryl_world_chunk(w, cx, cz, false);
				if (!c) continue;
				for (int sy = 0; sy < BERYL_CHUNK_SECTIONS; sy++) {
					BerylSection *s = c->sections[sy];
					if (s) { s->dirty = true; s->revision++; }
				}
			}
		}
	}
	return made;
}

/* Section-space circular loader: prefer chunks near the camera and near the
 * view direction, mirroring Beryllium's chunk-rebuild prioritization but for
 * generation. Uses a squared-distance sorted ring walk (no sort: rings). */
int beryl_world_update_loader(BerylWorld *w, BerylVec3i cam, int radius_sections,
                              int max_chunks, int *generated_out) {
	int32_t pcx = cam.x >> 4, pcz = cam.z >> 4;
	/* A chunk column is exactly one section wide, so a radius given in sections
	 * is the same number in chunks. (This division by 16 used to shrink the load
	 * radius 16x -- terrain appeared only right under the camera.) */
	int radius_chunks = BERYL_MAX(radius_sections, 1) + 1;
	int made = 0, missing = 0;

	for (int r = 0; r <= radius_chunks; r++) {
		int x0 = pcx - r, x1 = pcx + r, z0 = pcz - r, z1 = pcz + r;
		for (int cz = z0; cz <= z1; cz++) {
			for (int cx = x0; cx <= x1; cx++) {
				/* Only the ring itself, except r==0. */
				if (r > 0 && cx != x0 && cx != x1 && cz != z0 && cz != z1) continue;
				BerylChunk *c = beryl_world_chunk(w, cx, cz, false);
				if (c && c->generated) continue;
				missing++;
				if (made < max_chunks) {
					BerylChunk *nc = beryl_world_chunk(w, cx, cz, true);
					beryl_worldgen_generate_chunk(w, nc);
					nc->generated = true;
					beryl_light_relight_area(w, cx, cz, cx, cz);
					for (int sy = 0; sy < BERYL_CHUNK_SECTIONS; sy++) {
						BerylSection *s = nc->sections[sy];
						if (s) { s->dirty = true; s->revision++; }
					}
					w->generated_chunks++;
					made++;
				}
			}
		}
	}
	if (generated_out) *generated_out = made;
	return missing;
}

/* ------------------------------------------------------------- iteration -- */
void beryl_world_for_each_section(BerylWorld *w, beryl_section_iter_fn fn, void *user) {
	size_t n = sizeof(w->buckets) / sizeof(w->buckets[0]);
	for (size_t i = 0; i < n; i++) {
		BerylChunk *c = w->buckets[i];
		while (c) {
			BerylChunk *next = c->hash_next; /* fn may not touch the map, but this is free */
			for (int sy = 0; sy < BERYL_CHUNK_SECTIONS; sy++) {
				BerylSection *s = c->sections[sy];
				if (s && !fn(user, s)) return;
			}
			c = next;
		}
	}
}

void beryl_world_stats(BerylWorld *w, BerylWorldStats *out) {
	out->chunks = w->chunk_count;
	out->generated_chunks = w->generated_chunks;
	out->sections = 0;
	out->non_empty_sections = 0;
	out->dirty_sections = 0;
	size_t n = sizeof(w->buckets) / sizeof(w->buckets[0]);
	for (size_t i = 0; i < n; i++) {
		for (BerylChunk *c = w->buckets[i]; c; c = c->hash_next) {
			for (int sy = 0; sy < BERYL_CHUNK_SECTIONS; sy++) {
				BerylSection *s = c->sections[sy];
				if (!s) continue;
				out->sections++;
				if (!s->all_air) out->non_empty_sections++;
				if (s->dirty || s->revision != s->mesh_revision) out->dirty_sections++;
			}
		}
	}
}
