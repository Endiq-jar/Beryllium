/* test_pool.c -- the builder pool's result-ownership contract.
 *
 * The pool is where a mesh stops being a worker's property and becomes the mesh
 * store's, and that handoff is the easiest place in the engine to lose memory
 * without any visible symptom: a dropped result is not a hole in the world (the
 * section stays dirty and is rebuilt later), it is just a slow, silent leak.
 * These tests therefore look at the *rows* drain() hands back -- one per result,
 * each owning its own buffers -- rather than at pixels.
 */
#include "test.h"

#include "bcore.h"
#include "blocks.h"
#include "mesh_format.h"
#include "pool.h"
#include "world.h"

#include <stdlib.h>
#include <string.h>

static BerylWorld *pool_world(uint64_t seed) {
	beryl_blocks_init();
	BerylWorldDesc d;
	memset(&d, 0, sizeof(d));
	d.seed = seed;
	d.radius_sections = 640;
	d.caves = true;
	d.trees = true;
	d.water = true;
	d.sea_level = 62.0f;
	BerylWorld *w = beryl_world_new(&d);
	if (w) beryl_world_generate_area(w, 0, 0, 7, 7);
	return w;
}

static int cmp_u64(const void *a, const void *b) {
	uint64_t x = *(const uint64_t *)a, y = *(const uint64_t *)b;
	return x < y ? -1 : (x > y ? 1 : 0);
}

/* Every vertex of every mesh must be inside its own section, and a quad must be
 * four vertices plus six indices: cheap invariants that say "this buffer really
 * belongs to this result" rather than "it is somebody else's, again". */
static void check_mesh_shape(const BerylSectionMesh *m, int cx, int csy, int cz) {
	size_t quads = 0;
	for (int i = 0; i < BERYL_LAYER_COUNT; i++) {
		const BerylMeshLayer *l = &m->layer[i];
		CHECK(l->vert_count % 4u == 0u, "layer %d of %d,%d,%d has %zu verts", i, cx, csy, cz,
		      l->vert_count);
		CHECK(l->index_count == l->vert_count / 4u * 6u,
		      "layer %d of %d,%d,%d: %zu indices for %zu verts", i, cx, csy, cz, l->index_count,
		      l->vert_count);
		/* One check per layer, not per vertex: a violation is reported with its
		 * index either way, and the suite's totals stay a count of assertions. */
		size_t bad_v = 0, bad_v_at = 0, bad_i = 0, bad_i_at = 0;
		for (size_t v = 0; l->verts && v < l->vert_count; v++) {
			const BerylVertex *vv = &l->verts[v];
			float px = (float)vv->pos_x / (float)BERYL_POS_SCALE;
			float py = (float)vv->pos_y / (float)BERYL_POS_SCALE;
			float pz = (float)vv->pos_z / (float)BERYL_POS_SCALE;
			if (!(px >= 0.0f && px <= 16.0f && py >= 0.0f && py <= 16.0f &&
			      pz >= 0.0f && pz <= 16.0f)) {
				if (!bad_v) bad_v_at = v;
				bad_v++;
			}
		}
		CHECK(bad_v == 0, "%zu verts of %d,%d,%d escape the section, first at %zu", bad_v, cx,
		      csy, cz, bad_v_at);
		for (size_t k = 0; l->indices && k < l->index_count; k++) {
			if (l->indices[k] >= l->vert_count) {
				if (!bad_i) bad_i_at = k;
				bad_i++;
			}
		}
		CHECK(bad_i == 0, "%zu indices of %d,%d,%d are out of range, first at %zu", bad_i, cx,
		      csy, cz, bad_i_at);
		quads += l->quad_count;
	}
	CHECK(quads == m->quad_total, "layer quads %zu != section total %u", quads, m->quad_total);
}

/* drain() must hand back one row per result. This is the whole point of the
 * suite: an earlier version wrote every popped result into out[0], so a batch of
 * 16 came back as sixteen copies of the newest mesh and fifteen meshes were owned
 * by nobody. Nothing a render test can see, everything an allocator can. */
static void test_pool_drain_batch(void) {
	enum { WANT = 40, BATCH = 16 };
	BerylWorld *w = pool_world(20260829ull);
	if (!w) { CHECK(0, "world alloc"); return; }
	BerylPoolDesc d;
	memset(&d, 0, sizeof(d));
	d.world = w;
	d.threads = 2;
	d.max_queue = 0;         /* unlimited: every request must be served */
	d.view_alignment_bonus = 0.0f;
	BerylBuilderPool *p = beryl_pool_new(&d);
	if (!p) { CHECK(0, "pool alloc"); beryl_world_free(w); return; }

	for (int i = 0; i < WANT; i++) {
		int32_t cx = (int32_t)(i % 8), cz = (int32_t)(i / 8), csy = 3;
		beryl_pool_request(p, cx, csy, cz, (float)i, 0.0f);
	}
	beryl_pool_wait_idle(p);

	static uint64_t keys[WANT];
	static const void *vert_ptrs[WANT];
	int nkeys = 0, npts = 0, total = 0, with_quads = 0;
	BerylBuildResult batch[BATCH];
	int k;
	while ((k = beryl_pool_drain(p, batch, BATCH)) > 0) {
		CHECK(k <= BATCH, "drain returned %d rows for a batch of %d", k, BATCH);
		for (int i = 0; i < k; i++) {
			BerylBuildResult *r = &batch[i];
			CHECK(r->ok, "worker reported a failed build for %d,%d,%d", r->cx, r->csy, r->cz);
			if (nkeys < WANT) keys[nkeys++] = ((uint64_t)r->cx << 40) ^ ((uint64_t)r->csy << 20) ^
				                              (uint64_t)r->cz ^ r->key * 3u;
			if (r->mesh.layer[0].verts && npts < WANT) vert_ptrs[npts++] = r->mesh.layer[0].verts;
			if (r->mesh.quad_total) with_quads++;
			check_mesh_shape(&r->mesh, r->cx, r->csy, r->cz);
			beryl_section_mesh_free(&r->mesh);
			beryl_pool_release_result(p, r);
			/* Releasing must not leave a half-owned result behind: the caller can
			 * not accidentally free the same buffers twice. */
			CHECK(r->mesh.layer[0].verts == NULL && r->mesh.layer[1].verts == NULL,
			      "release_result left mesh pointers in place");
			total++;
		}
	}
	CHECK(total == WANT, "drained %d results for %d requests (a dropped row is a leak)", total,
	      WANT);
	CHECK(with_quads > WANT / 4, "only %d of %d slabs at y=48..63 produced geometry", with_quads,
	      total);

	qsort(keys, (size_t)nkeys, sizeof keys[0], cmp_u64);
	int dup = 0;
	for (int i = 1; i < nkeys; i++) dup += keys[i] == keys[i - 1];
	CHECK(dup == 0, "%d of %d drained results were the same section twice", dup, nkeys);

	for (int i = 1; i < npts; i++)
		for (int j = 0; j < i; j++)
			CHECK(vert_ptrs[i] != vert_ptrs[j], "results %d and %d share one vertex buffer", i, j);

	CHECK(beryl_pool_drain(p, batch, BATCH) == 0, "drain still finds results after emptying");
	CHECK(beryl_pool_total_builds(p) >= WANT, "pool counted %ld builds for %d requests",
	      (long)beryl_pool_total_builds(p), WANT);
	beryl_pool_free(p);
	beryl_world_free(w);
}

/* The max=0 / NULL arguments are what a caller under memory pressure or an
 * early-out path passes; they must be no-ops rather than a drain of the queue
 * into a wild pointer. */
static void test_pool_degenerate_calls(void) {
	BerylWorld *w = pool_world(4242ull);
	if (!w) { CHECK(0, "world alloc"); return; }
	BerylPoolDesc d;
	memset(&d, 0, sizeof(d));
	d.world = w;
	d.threads = 1;
	BerylBuilderPool *p = beryl_pool_new(&d);
	if (!p) { CHECK(0, "pool alloc"); beryl_world_free(w); return; }

	BerylBuildResult r;
	memset(&r, 0xA5, sizeof r);
	CHECK(beryl_pool_drain(p, &r, 0) == 0, "max=0 must drain nothing");
	CHECK(beryl_pool_drain(p, &r, -5) == 0, "negative max must drain nothing");
	CHECK(beryl_pool_drain(p, NULL, 8) == 0, "NULL out must drain nothing");
	CHECK(((const unsigned char *)&r)[0] == 0xA5, "a refused drain touched the caller's struct");
	beryl_pool_release_result(p, NULL);       /* must be a no-op, not a crash */
	CHECK(beryl_pool_queued(p) == 0, "fresh pool has queued work");

	/* finish_all() drains and frees in its own batches; afterwards the ring must
	 * be empty, or the next caller inherits meshes nobody owns. */
	for (int i = 0; i < 24; i++) beryl_pool_request(p, i % 4, 3, i / 4, 1.0f, 0.0f);
	beryl_pool_finish_all(p);
	CHECK(beryl_pool_drain(p, &r, 8) == 0, "finish_all left results in the done ring");
	CHECK(beryl_pool_queued(p) == 0, "finish_all left work in the queue");
	CHECK(beryl_pool_inflight(p) == 0, "finish_all left a job in flight");

	beryl_pool_free(p);
	beryl_world_free(w);
}

void test_pool(void) {
	test_pool_drain_batch();
	test_pool_degenerate_calls();
}
