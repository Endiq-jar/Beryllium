/* light.c -- bucketed light propagation (skylight + block light).
 *
 * Propagation is Dijkstra over a grid where every edge costs at least 1, so the
 * classic trick applies: keep one FIFO bucket per light level and always drain
 * the highest bucket first. That visits each voxel a small number of times and
 * needs no priority queue, no sorting, and no heap allocations per push.
 *
 * Edits use Minecraft's two-pass scheme (removal then addition) so a torch being
 * placed or a wall being broken updates light exactly instead of approximately,
 * which is the only way the incremental result can be tested against a full
 * relight (see tests/test_light.c).
 */
#include "light.h"

#include <stdio.h>

#include <pthread.h>
#include <stdlib.h>
#include <string.h>

/* ------------------------------------------------------------ position queue */
typedef struct BerylLQueue {
	int32_t *v;      /* 4 ints per entry: x, y, z, level */
	size_t   len, cap;
} BerylLQueue;

static void lq_init(BerylLQueue *q) { q->v = NULL; q->len = q->cap = 0; }
static void lq_free(BerylLQueue *q) { free(q->v); q->v = NULL; q->len = q->cap = 0; }
static void lq_clear(BerylLQueue *q) { q->len = 0; }
static bool lq_empty(const BerylLQueue *q) { return q->len == 0; }
static void lq_push(BerylLQueue *q, int x, int y, int z, int level) {
	if (q->len == q->cap) {
		size_t nc = q->cap ? q->cap * 2 : 1024;
		int32_t *nv = (int32_t *)realloc(q->v, nc * 4 * sizeof(int32_t));
		if (!nv) return;
		q->v = nv; q->cap = nc;
	}
	int32_t *p = q->v + q->len * 4;
	p[0] = x; p[1] = y; p[2] = z; p[3] = level;
	q->len++;
}
static void lq_pop(BerylLQueue *q, int *x, int *y, int *z, int *level) {
	BERYL_ASSERT(q->len > 0, "pop from empty light queue");
	q->len--;
	const int32_t *p = q->v + q->len * 4;
	*x = p[0]; *y = p[1]; *z = p[2]; *level = p[3];
}

/* Bucket set indexed by level: index 15 holds full-strength sources. */
typedef struct BerylLBucket {
	BerylLQueue q[16];
	size_t      count;
} BerylLBuckets;

static void lb_init(BerylLBuckets *b) {
	for (int i = 0; i < 16; i++) lq_init(&b->q[i]);
	b->count = 0;
}
static void lb_free(BerylLBuckets *b) {
	for (int i = 0; i < 16; i++) lq_free(&b->q[i]);
	b->count = 0;
}
static void lb_clear(BerylLBuckets *b) {
	for (int i = 0; i < 16; i++) lq_clear(&b->q[i]);
	b->count = 0;
}
static void lb_push(BerylLBuckets *b, int level, int x, int y, int z) {
	BERYL_ASSERT(level >= 1 && level <= 15, "light bucket level %d out of range", level);
	lq_push(&b->q[level], x, y, z, level);
	b->count++;
}
static bool lb_pop(BerylLBuckets *b, int *x, int *y, int *z, int *level) {
	for (int l = 15; l >= 1; l--) {
		if (!lq_empty(&b->q[l])) {
			lq_pop(&b->q[l], x, y, z, level);
			BERYL_ASSERT(b->count > 0, "bucket count underflow");
			b->count--;
			return true;
		}
	}
	return false;
}

/* ------------------------------------------------------------------ cursor -- */
/* One-entry cache so a BFS sweep inside a section costs no hash lookups. */
typedef struct BerylLCursor {
	BerylWorld   *w;
	BerylSection *s;
	int32_t cx, cy, cz;
} BerylLCursor;

static BerylSection *cursor_section(BerylLCursor *c, int x, int y, int z, bool create) {
	int32_t sx = x >> 4, sy = y >> 4, sz = z >> 4;
	if (c->s && sx == c->cx && sy == c->cy && sz == c->cz) {
		return c->s;
	}
	BerylSection *s = beryl_world_section(c->w, sx, sy, sz, create);
	c->s = s; c->cx = sx; c->cy = sy; c->cz = sz;
	return s;
}

static inline beryl_bid cur_state(BerylLCursor *c, int x, int y, int z) {
	if (y < BERYL_WORLD_MIN_Y || y >= BERYL_WORLD_MAX_Y) return BERYL_BLOCK_BEDROCK;
	BerylSection *s = cursor_section(c, x, y, z, false);
	if (!s) return BERYL_BLOCK_AIR;
	return (beryl_bid)s->states[beryl_section_index(x & 15, y & 15, z & 15)];
}

/* Light arrays are lazily created along with sections, so reading light of an
 * unloaded position returns `sky_default` (15 for sky: open air). */
static inline int cur_light(BerylLCursor *c, int x, int y, int z, bool sky) {
	if (y < BERYL_WORLD_MIN_Y || y >= BERYL_WORLD_MAX_Y) return 0;
	BerylSection *s = cursor_section(c, x, y, z, false);
	if (!s) return sky ? 15 : 0;
	uint8_t v = s->light[beryl_section_index(x & 15, y & 15, z & 15)];
	return sky ? (v & 0xF) : ((v >> 4) & 0xF);
}

static inline void cur_set_light(BerylLCursor *c, int x, int y, int z, bool sky, int level) {
	if (y < BERYL_WORLD_MIN_Y || y >= BERYL_WORLD_MAX_Y) return;
	BerylSection *s = cursor_section(c, x, y, z, true);
	if (!s) return;
	size_t i = (size_t)beryl_section_index(x & 15, y & 15, z & 15);
	if (sky) s->light[i] = (uint8_t)((s->light[i] & 0xF0) | (level & 0xF));
	else     s->light[i] = (uint8_t)((s->light[i] & 0x0F) | ((level & 0xF) << 4));
}

/* The six neighbours in a fixed order; k_axis_of tells which axis a step is on,
 * which matters because vertical skylight uses the block's skylight_filter
 * instead of its light_attenuation (the mechanic that gives underwater depth
 * gradients and leaf-shaded ground). */
static const int k_dx[6] = { -1, 1, 0, 0, 0, 0 };
static const int k_dy[6] = { 0, 0, -1, 1, 0, 0 };
static const int k_dz[6] = { 0, 0, 0, 0, -1, 1 };

static inline int step_cost(beryl_bid neighbour, bool sky, bool downward) {
	if (neighbour == BERYL_BLOCK_AIR) return 1;
	const BerylBlockInfo *bi = beryl_block_info(neighbour);
	int atten = (sky && downward) ? bi->skylight_filter : bi->light_attenuation;
	if (atten >= 15) return -1;             /* impassable */
	return 1 + atten;
}

/* --------------------------------------------------------------- spread ---- */
typedef struct BerylSpreadCtx {
	BerylLCursor   *cur;
	BerylLBuckets  *buckets;
	bool            sky;
	int             steps;
	int             max_steps;
	int             overflow;
} BerylSpreadCtx;

static void spread(BerylSpreadCtx *sc) {
	int x, y, z, level;
	while (sc->max_steps <= 0 || sc->steps < sc->max_steps) {
		if (!lb_pop(sc->buckets, &x, &y, &z, &level)) {
			return; /* drained */
		}
		sc->steps++;
		for (int d = 0; d < 6; d++) {
			int nx = x + k_dx[d], ny = y + k_dy[d], nz = z + k_dz[d];
			if (ny < BERYL_WORLD_MIN_Y || ny >= BERYL_WORLD_MAX_Y) continue;
			bool downward = sc->sky && k_dy[d] == -1;
			beryl_bid nb = cur_state(sc->cur, nx, ny, nz);
			if (nb != BERYL_BLOCK_AIR && sc->sky && !downward &&
			    beryl_block_is_opaque(nb)) {
				continue; /* skylight never flows sideways through a solid wall */
			}
			int cost = step_cost(nb, sc->sky, downward);
			if (cost < 0) continue;
			int nl = level - cost;
			if (nl <= 0) continue;
			if (cur_light(sc->cur, nx, ny, nz, sc->sky) >= nl) continue;
			cur_set_light(sc->cur, nx, ny, nz, sc->sky, nl);
			lb_push(sc->buckets, nl, nx, ny, nz);
		}
	}
	sc->overflow = 1;
	lb_clear(sc->buckets);
}

/* --------------------------------------------------------------- removal --- */
typedef struct BerylRemovalCtx {
	BerylLCursor *cur;
	BerylLQueue   fifo;
	BerylLBuckets *readd;
	bool          sky;
	int           steps, max_steps;
	int           overflow;
} BerylRemovalCtx;

static void removal(BerylRemovalCtx *rc) {
	int x, y, z, level;
	while (!lq_empty(&rc->fifo)) {
		if (rc->max_steps > 0 && rc->steps >= rc->max_steps) {
			rc->overflow = 1;
			lq_clear(&rc->fifo);
			return;
		}
		rc->steps++;
		lq_pop(&rc->fifo, &x, &y, &z, &level);
		for (int d = 0; d < 6; d++) {
			int nx = x + k_dx[d], ny = y + k_dy[d], nz = z + k_dz[d];
			if (ny < BERYL_WORLD_MIN_Y || ny >= BERYL_WORLD_MAX_Y) continue;
			int nl = cur_light(rc->cur, nx, ny, nz, rc->sky);
			if (nl == 0) continue;
			beryl_bid src = cur_state(rc->cur, nx, ny, nz);
			bool keeps = false;
			if (src != BERYL_BLOCK_AIR && beryl_block_light_emission(src) > 0 && !rc->sky) {
				keeps = true; /* a real emitter keeps its light */
			}
			if (rc->sky && cur_state(rc->cur, nx, ny + 1, nz) == BERYL_BLOCK_AIR
			    && ny + 1 < BERYL_WORLD_MAX_Y && cur_light(rc->cur, nx, ny + 1, nz, true) == 15) {
				keeps = true; /* open sky above: this column gets re-seeded instead */
			}
			if (keeps) {
				/* The cell survives the retraction, but it must also become a
				 * source for the re-propagation pass that follows, or everything
				 * it lights that the flood *did* clear would stay dark. Without
				 * this, placing a block in open air flooded the whole connected
				 * shaded region (caves, overhangs) to zero and nothing ever lit
				 * it again -- the survivors kept their own light but were never
				 * re-seeded, so the readd spread had nothing to start from. */
				lb_push(rc->readd, nl, nx, ny, nz);
				continue;
			}
			if (nl <= level) {
				cur_set_light(rc->cur, nx, ny, nz, rc->sky, 0);
				lq_push(&rc->fifo, nx, ny, nz, nl);
			} else {
				/* Still lit from somewhere else: it becomes a source for the pass
				 * that follows, so the light field converges to the same answer a
				 * full relight would produce. */
				lb_push(rc->readd, nl, nx, ny, nz);
			}
		}
	}
}

/* ----------------------------------------------------------- full relight -- */
static void reset_region(BerylWorld *w, int x0, int x1, int z0, int z1) {
	BerylLCursor cur = { w, NULL, 0, 0, 0 };
	for (int x = x0; x <= x1; x++) {
		for (int z = z0; z <= z1; z++) {
			for (int sy = 0; sy < BERYL_CHUNK_SECTIONS; sy++) {
				BerylSection *s = beryl_world_section(w, x >> 4, sy, z >> 4, false);
				if (!s) continue;
				memset(s->light, 0, sizeof(s->light));
			}
		}
	}
	(void)cur;
}

/* A column can only be seeded once its chunk has actually been generated.
 * The reset pass creates chunks for the whole ring, but a chunk that was never
 * terrain-generated is all air. Seeding such a column as open sky would turn
 * the ring into an infinite sky shaft and leak light 15 sideways into the
 * area's real terrain (water, caves, overhangs) near the border, brightening
 * it beyond what a fully generated world would show. Seeding only generated
 * columns keeps relight idempotent (the ring is still reset, so it never
 * carries stale light into the spread) and matches the game's flow, where a
 * chunk's neighbours are generated before they are lit. */
static bool column_generated(BerylWorld *w, int x, int z) {
	BerylChunk *c = beryl_world_chunk(w, x >> 4, z >> 4, false);
	return c && c->generated;
}

static void seed_columns(BerylWorld *w, int x0, int x1, int z0, int z1, BerylLBuckets *sky_q) {
	BerylLCursor cur = { w, NULL, 0, 0, 0 };
	for (int x = x0; x <= x1; x++) {
		for (int z = z0; z <= z1; z++) {
			if (!column_generated(w, x, z)) continue;
			int level = 15;
			for (int y = BERYL_WORLD_MAX_Y - 1; y >= BERYL_WORLD_MIN_Y; y--) {
				beryl_bid id = cur_state(&cur, x, y, z);
				const BerylBlockInfo *bi = beryl_block_info(id);
				if (bi->skylight_filter >= 15) {
					level = 0;
					cur_set_light(&cur, x, y, z, true, 0);
					continue;
				}
				level = BERYL_MAX(level - bi->skylight_filter, 0);
				cur_set_light(&cur, x, y, z, true, level);
				if (level > 1) lb_push(sky_q, level, x, y, z);
			}
		}
	}
}

static void seed_emitters(BerylWorld *w, int x0, int x1, int z0, int z1, BerylLBuckets *blk_q) {
	BerylLCursor cur = { w, NULL, 0, 0, 0 };
	for (int x = x0; x <= x1; x++) {
		for (int z = z0; z <= z1; z++) {
			if (!column_generated(w, x, z)) continue;
			for (int y = BERYL_WORLD_MIN_Y; y < BERYL_WORLD_MAX_Y; y++) {
				beryl_bid id = cur_state(&cur, x, y, z);
				if (id == BERYL_BLOCK_AIR) continue;
				int emit = beryl_block_light_emission(id);
				if (emit <= 0) continue;
				cur_set_light(&cur, x, y, z, false, 15);
				lb_push(blk_q, 15, x, y, z);
			}
		}
	}
}

void beryl_light_relight_area(BerylWorld *w, int32_t cx0, int32_t cz0, int32_t cx1, int32_t cz1) {
	/* Light travels at most 15 blocks (levels cap at 15 and every step costs at
	 * least 1), so every source that can light the requested chunks and every
	 * path that can reach them lies within a one-section ring around the area.
	 * Resetting and re-seeding that whole region -- not just the requested
	 * chunks -- is what makes the result self-contained: nothing outside the
	 * ring can influence it, so the answer no longer depends on whatever light
	 * the neighbouring sections happened to hold from an earlier pass.
	 *
	 * Without the ring, relighting was not idempotent: the spread treats an
	 * already-lit cell as an upper bound and stops propagating there, so a path
	 * that left the area through a previously-lit neighbour and re-entered it
	 * (a cave loop crossing the chunk border) was silently cut off on the
	 * second pass, leaving border cells darker than a fresh world. Re-seeding
	 * the ring also picks up emitters in the neighbouring chunks that are close
	 * enough to light the area, which a bare-area relight missed.
	 *
	 * The ring is reset unconditionally, but only columns whose chunks have
	 * actually been generated are seeded (see column_generated): a not-yet-
	 * generated ring chunk is empty air, and seeding it as open sky would leak
	 * light 15 sideways into the area's real terrain. Its light is instead
	 * whatever the area's own sources spread into it, exactly as a fresh world
	 * would leave it, so a relight of the same area is a true no-op. */
	const int pad = 1;   /* one section = 16 blocks > the 15-block light radius */
	int x0 = (cx0 - pad) * BERYL_SECTION_SIDE;
	int x1 = (cx1 + pad) * BERYL_SECTION_SIDE + BERYL_SECTION_SIDE - 1;
	int z0 = (cz0 - pad) * BERYL_SECTION_SIDE;
	int z1 = (cz1 + pad) * BERYL_SECTION_SIDE + BERYL_SECTION_SIDE - 1;

	BerylLBuckets sky, blk;
	lb_init(&sky); lb_init(&blk);

	/* Sections that will receive light must exist first: creating them lazily in
	 * the middle of the BFS would invalidate the cursor's cached pointer. */
	for (int cx = cx0 - pad; cx <= cx1 + pad; cx++) {
		for (int cz = cz0 - pad; cz <= cz1 + pad; cz++) {
			BerylChunk *c = beryl_world_chunk(w, cx, cz, true);
			for (int sy = 0; sy < BERYL_CHUNK_SECTIONS; sy++) {
				beryl_chunk_section_at(c, sy, true);
			}
		}
	}

	reset_region(w, x0, x1, z0, z1);
	seed_columns(w, x0, x1, z0, z1, &sky);
	seed_emitters(w, x0, x1, z0, z1, &blk);

	BerylLCursor cur = { w, NULL, 0, 0, 0 };
	BerylSpreadCtx sc = { &cur, &sky, true, 0, 0, 0 };
	spread(&sc);
	BerylSpreadCtx bc = { &cur, &blk, false, 0, 0, 0 };
	spread(&bc);

	lb_free(&sky); lb_free(&blk);

	/* The ring's light changed too (it was reset and re-derived), so its meshes
	 * are stale just like the requested chunks' -- mark the whole expanded
	 * region dirty, or the sections just across the border keep meshes baked
	 * from the pre-relight light. */
	int n = 0;
	for (int cx = cx0 - pad; cx <= cx1 + pad; cx++) {
		for (int cz = cz0 - pad; cz <= cz1 + pad; cz++) {
			BerylChunk *c = beryl_world_chunk(w, cx, cz, false);
			if (!c) continue;
			for (int sy = 0; sy < BERYL_CHUNK_SECTIONS; sy++) {
				BerylSection *s = c->sections[sy];
				if (s) { s->light_dirty = false; s->dirty = true; s->revision++; n++; }
			}
		}
	}
	(void)n;
}

/* ------------------------------------------------------- incremental edits -- */
typedef struct BerylEditQueue {
	BerylLQueue q;
	pthread_mutex_t mtx;
	bool locked;
} BerylEditQueue;

static BerylEditQueue g_edits = { { NULL, 0, 0 }, PTHREAD_MUTEX_INITIALIZER, false };

static void edit_lock(void) {
	if (!g_edits.locked) { pthread_mutex_lock(&g_edits.mtx); g_edits.locked = true; }
}

void beryl_light_queue_edit_source(BerylWorld *w, int wx, int wy, int wz, int old_emission) {
	(void)w;
	edit_lock();
	lq_push(&g_edits.q, wx, wy, wz, old_emission);
	pthread_mutex_unlock(&g_edits.mtx);
	g_edits.locked = false;
}

void beryl_light_queue_edit(BerylWorld *w, int wx, int wy, int wz) {
	beryl_light_queue_edit_source(w, wx, wy, wz, 0);
}

int beryl_light_process_queue(BerylWorld *w, int max_positions) {
	edit_lock();
	if (lq_empty(&g_edits.q)) { pthread_mutex_unlock(&g_edits.mtx); g_edits.locked = false; return 0; }
	BerylLQueue local = g_edits.q;
	lq_init(&g_edits.q);
	pthread_mutex_unlock(&g_edits.mtx);
	g_edits.locked = false;

	BerylLBuckets sky, blk;
	lb_init(&sky); lb_init(&blk);
	BerylLCursor cur = { w, NULL, 0, 0, 0 };
	int processed = 0;
	int relit_cx = 0x7FFFFFFF, relit_cz = 0x7FFFFFFF;
	while (!lq_empty(&local) && processed < max_positions) {
		int x, y, z, level;
		lq_pop(&local, &x, &y, &z, &level);
		processed++;

		beryl_bid id = cur_state(&cur, x, y, z);
		const BerylBlockInfo *bi = beryl_block_info(id);

		if (level > 0) {
			/* A light source was taken away. An incremental retraction is not
			 * enough here: `removal` deliberately refuses to darken a cell that
			 * looks brighter than the level it was reached at, and inside an
			 * enclosed volume every remaining cell looks that way once the walk
			 * has eaten the gradient -- so the stale light becomes its own
			 * justification. Re-deriving the column is the honest answer. */
			int rcx = x >> 4, rcz = z >> 4;
			if (rcx != relit_cx || rcz != relit_cz) {
				relit_cx = rcx; relit_cz = rcz;
				beryl_light_relight_area(w, rcx, rcz, rcx, rcz);
			}
			continue;
		}

		/* 1. darken: everything this voxel used to light must be re-derived.
		 * Two subtleties, both learned from the tests:
		 *  - the edited voxel's own light has often already been cleared by the
		 *    edit, so the retraction has to be seeded from the brightest
		 *    neighbour, not from what the voxel happens to store;
		 *  - a *source* being taken away (old_emission > 0) must retract light
		 *    even though the replacement (air) transmits light just fine. */
		bool opaque = beryl_block_is_opaque(id);
		int sky_here = cur_light(&cur, x, y, z, true);
		int blk_here = cur_light(&cur, x, y, z, false);
		bool emits = beryl_block_light_emission(id) > 0;
		int seed_sky = sky_here, seed_blk = blk_here;
		for (int d = 0; d < 6; d++) {
			int ns = cur_light(&cur, x + k_dx[d], y + k_dy[d], z + k_dz[d], true);
			int nb = cur_light(&cur, x + k_dx[d], y + k_dy[d], z + k_dz[d], false);
			if (ns > seed_sky) seed_sky = ns;
			if (nb > seed_blk) seed_blk = nb;
		}
		bool sky_stale = opaque && seed_sky > 0;
		/* Any edit can take light away (a lamp removed, or a block that used to
		 * carry the light replaced), so the retraction always runs when something
		 * lit is adjacent. It converges because cells that are lit from elsewhere
		 * are collected for the re-add pass instead of being left dark. */
		bool blk_stale = !emits && seed_blk > 0;
		if (sky_stale) {
			cur_set_light(&cur, x, y, z, true, 0);
			BerylLQueue fifo; lq_init(&fifo);
			lq_push(&fifo, x, y, z, seed_sky);
			BerylRemovalCtx rc = { &cur, fifo, &sky, true, 0, 1 << 20, 0 };
			removal(&rc);
			lq_free(&rc.fifo);
			sky_here = 0;
		}
		if (blk_stale) {
			cur_set_light(&cur, x, y, z, false, 0);
			BerylLQueue fifo; lq_init(&fifo);
			lq_push(&fifo, x, y, z, seed_blk);
			BerylRemovalCtx rc = { &cur, fifo, &blk, false, 0, 1 << 20, 0 };
			removal(&rc);
			lq_free(&rc.fifo);
			blk_here = 0;
		}
		/* 2. re-seed from above: a hole punched in a roof lights the column. */
		if (!opaque) {
			int above = cur_light(&cur, x, y + 1, z, true);
			int start = (y + 1 >= BERYL_WORLD_MAX_Y) ? 15 : above;
			if (beryl_block_propagates_skylight_down(BERYL_BLOCK_AIR)) {
				int lv = start == 15 ? 15 : BERYL_MAX(start - 1, 0);
				if (lv > cur_light(&cur, x, y, z, true)) {
					cur_set_light(&cur, x, y, z, true, lv);
					if (lv > 1) lb_push(&sky, lv, x, y, z);
				}
			}
			int emit = beryl_block_light_emission(id);
			if (emit > 0) {
				cur_set_light(&cur, x, y, z, false, 15);
				lb_push(&blk, 15, x, y, z);
			} else if (bi->light_attenuation < 15) {
				/* Transparent: inherit from neighbours so a removal that emptied
				 * this voxel can be refilled. */
				int best = 0;
				for (int d = 0; d < 6; d++) {
					int nl = cur_light(&cur, x + k_dx[d], y + k_dy[d], z + k_dz[d], true);
					if (nl > best) best = nl;
				}
				if (best > 1) {
					int lv = best - 1;
					if (lv > cur_light(&cur, x, y, z, true)) {
						cur_set_light(&cur, x, y, z, true, lv);
						lb_push(&sky, lv, x, y, z);
					}
				}
				int bb = 0;
				for (int d = 0; d < 6; d++) {
					int nl = cur_light(&cur, x + k_dx[d], y + k_dy[d], z + k_dz[d], false);
					if (nl > bb) bb = nl;
				}
				if (bb > 1) {
					int lv = bb - 1;
					if (lv > cur_light(&cur, x, y, z, false)) {
						cur_set_light(&cur, x, y, z, false, lv);
						lb_push(&blk, lv, x, y, z);
					}
				}
			}
		}

		/* 3. neighbours of the edit must remesh even if light is unchanged. */
		beryl_world_mark_dirty(w, x >> 4, y >> 4, z >> 4, true);
	}

	BerylSpreadCtx sc = { &cur, &sky, true, 0, 1 << 20, 0 };
	spread(&sc);
	BerylSpreadCtx bc = { &cur, &blk, false, 0, 1 << 20, 0 };
	spread(&bc);

	lb_free(&sky);
	lb_free(&blk);
	lq_free(&local);
	return processed;
}

/* ------------------------------------------------------------- validation -- */
bool beryl_light_validate_section(BerylWorld *w, BerylSection *s, char *err, size_t err_len) {
	for (int y = 0; y < BERYL_SECTION_SIDE; y++) {
		for (int z = 0; z < BERYL_SECTION_SIDE; z++) {
			for (int x = 0; x < BERYL_SECTION_SIDE; x++) {
				int i = beryl_section_index(x, y, z);
				uint8_t v = s->light[i];
				if ((v & 0xF) > 15 || ((v >> 4) & 0xF) > 15) {
					if (err) snprintf(err, err_len, "light level > 15 at %d,%d,%d", x, y, z);
					return false;
				}
			}
		}
	}
	(void)w;
	return true;
}
