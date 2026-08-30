/* pool.c */
#include "pool.h"

#include <pthread.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

typedef struct Job {
	int32_t cx, csy, cz;
	uint64_t key;
	float priority;
} Job;

typedef struct JobHeap {
	Job   *a;
	size_t len, cap;
} JobHeap;

static void heap_init(JobHeap *h) { h->a = NULL; h->len = h->cap = 0; }
static void heap_free(JobHeap *h) { free(h->a); h->a = NULL; h->len = h->cap = 0; }

static int heap_less(const Job *x, const Job *y) { return x->priority < y->priority; }

static void heap_push(JobHeap *h, Job j) {
	if (h->len == h->cap) {
		size_t nc = h->cap ? h->cap * 2 : 64;
		Job *p = (Job *)realloc(h->a, nc * sizeof(Job));
		if (!p) return;
		h->a = p; h->cap = nc;
	}
	size_t i = h->len++;
	h->a[i] = j;
	while (i > 0) {
		size_t parent = (i - 1) / 2;
		if (!heap_less(&h->a[i], &h->a[parent])) break;
		Job t = h->a[i]; h->a[i] = h->a[parent]; h->a[parent] = t;
		i = parent;
	}
}

static bool heap_pop(JobHeap *h, Job *out) {
	if (h->len == 0) return false;
	*out = h->a[0];
	h->a[0] = h->a[--h->len];
	size_t i = 0;
	for (;;) {
		size_t l = i * 2 + 1, r = i * 2 + 2, best = i;
		if (l < h->len && heap_less(&h->a[l], &h->a[best])) best = l;
		if (r < h->len && heap_less(&h->a[r], &h->a[best])) best = r;
		if (best == i) break;
		Job t = h->a[i]; h->a[i] = h->a[best]; h->a[best] = t;
		i = best;
	}
	return true;
}

/* Pending-key table so duplicate requests collapse into one job.
 *
 * Linear probing with tombstones, cleared when the job is *popped* rather than
 * when the frame drains: a section that changes again while its build is in
 * flight must be re-requestable immediately, otherwise the mesh would stay
 * stale. Rehashes when load (used + tombstones) passes 3/4 capacity. */
#define PENDING_BITS 13
#define PENDING_CAP  (1 << PENDING_BITS)
#define PENDING_MASK (PENDING_CAP - 1)

enum { P_EMPTY = 0, P_USED = 1, P_TOMB = 2 };

struct BerylBuilderPool {
	BerylWorld     *world;
	BerylPoolDesc   desc;

	uint64_t        pending_keys[PENDING_CAP];
	uint8_t         pending_state[PENDING_CAP];
	size_t          pending_used;

	pthread_t      *threads;
	int             nthreads;

	pthread_mutex_t mtx;
	pthread_cond_t  cv_work;
	pthread_cond_t  cv_done;

	JobHeap         pending;

	BerylBuildResult *done;
	size_t            done_len, done_cap;

	_Atomic int       inflight;
	_Atomic bool      quit;
	_Atomic long long total_builds;
	_Atomic uint64_t  total_ms_u;   /* microseconds, summed */

	_Atomic int       overflowed;
};


static uint32_t key_slot(uint64_t key) {
	uint64_t h = key * 0x9E3779B97F4A7C15ull;
	return (uint32_t)(h >> 51) & PENDING_MASK;
}

static void pending_rehash(BerylBuilderPool *p) {
	static const uint64_t k_empty = 0;
	uint64_t old[PENDING_CAP];
	uint8_t old_state[PENDING_CAP];
	memcpy(old, p->pending_keys, sizeof(old));
	memcpy(old_state, p->pending_state, sizeof(old_state));
	memset(p->pending_keys, 0, sizeof(p->pending_keys));
	memset(p->pending_state, 0, sizeof(p->pending_state));
	p->pending_used = 0;
	for (uint32_t i = 0; i < PENDING_CAP; i++) {
		if (old_state[i] != P_USED) continue;
		(void)k_empty;
		uint32_t j = key_slot(old[i]);
		while (p->pending_state[j] == P_USED) j = (j + 1u) & PENDING_MASK;
		p->pending_keys[j] = old[i];
		p->pending_state[j] = P_USED;
		p->pending_used++;
	}
}

/* Returns true when the key was not already queued (and inserts it). */
static bool pending_insert(BerylBuilderPool *p, uint64_t key) {
	if ((p->pending_used + 1u) * 4u > PENDING_CAP * 3u) {
		pending_rehash(p);
	}
	uint32_t i = key_slot(key);
	uint32_t first_tomb = PENDING_CAP;
	while (p->pending_state[i] != P_EMPTY) {
		if (p->pending_state[i] == P_USED) {
			if (p->pending_keys[i] == key) return false;
		} else if (first_tomb == PENDING_CAP) {
			first_tomb = i;
		}
		i = (i + 1u) & PENDING_MASK;
	}
	if (first_tomb != PENDING_CAP) i = first_tomb;
	p->pending_keys[i] = key;
	p->pending_state[i] = P_USED;
	p->pending_used++;
	return true;
}

static void pending_remove(BerylBuilderPool *p, uint64_t key) {
	uint32_t i = key_slot(key);
	while (p->pending_state[i] != P_EMPTY) {
		if (p->pending_state[i] == P_USED && p->pending_keys[i] == key) {
			p->pending_state[i] = P_TOMB;
			if (p->pending_used > 0) p->pending_used--;
			return;
		}
		i = (i + 1u) & PENDING_MASK;
	}
}

static void *worker_main(void *arg) {
	BerylBuilderPool *p = (BerylBuilderPool *)arg;
	pthread_mutex_lock(&p->mtx);
	for (;;) {
		Job job = { 0, 0, 0, 0, 0.0f };
		while (!p->quit && !heap_pop(&p->pending, &job)) {
			pthread_cond_wait(&p->cv_work, &p->mtx);
		}
		if (p->quit) break;

		pending_remove(p, job.key);
		atomic_fetch_add(&p->inflight, 1);
		pthread_mutex_unlock(&p->mtx);

		double t0 = beryl_time_ms();
		BerylSectionMesh mesh;
		beryl_section_mesh_init(&mesh, job.cx, job.csy, job.cz);
		bool ok = false;
		beryl_world_lock_read(p->world);
		BerylSlice slice;
		if (beryl_world_fill_slice(p->world, job.cx, job.csy, job.cz, &slice)) {
			BerylSection *s = slice.sec[1][1][1];
			if (s) {
				ok = beryl_mesh_slice(&slice, &mesh);
				if (ok) {
					/* Only record which revision this mesh represents. Whether
					 * the section counts as "up to date" is the installer's
					 * decision: a result that is dropped (over the per-frame
					 * upload budget, a store OOM, a newer edit) must stay dirty,
					 * otherwise the hole it left is never rebuilt. */
					mesh.source_revision = s->revision;
				}
			}
		}
		beryl_world_unlock_read(p->world);
		float ms = (float)(beryl_time_ms() - t0);

		BerylMeshStats stats;
		beryl_mesh_last_stats(&stats);

		pthread_mutex_lock(&p->mtx);
		if (p->done_len == p->done_cap) {
			size_t nc = p->done_cap ? p->done_cap * 2 : 64;
			BerylBuildResult *nd = (BerylBuildResult *)realloc(p->done, nc * sizeof(*nd));
			if (nd) { p->done = nd; p->done_cap = nc; }
		}
		if (p->done_len < p->done_cap) {
			BerylBuildResult *r = &p->done[p->done_len++];
			r->cx = job.cx; r->csy = job.csy; r->cz = job.cz;
			r->key = job.key;
			r->built_revision = ok ? mesh.source_revision : 0;
			r->mesh = mesh;
			r->stats = stats;
			r->ms = ms;
			r->ok = ok;
			if (!ok) beryl_section_mesh_free(&mesh);
		}
		atomic_fetch_sub(&p->inflight, 1);
		atomic_fetch_add(&p->total_builds, 1);
		/* Sum integer microseconds: exact accumulation under a lock-free atomic,
		 * at a resolution nobody can perceive in an overlay. */
		atomic_fetch_add(&p->total_ms_u, (uint64_t)(ms * 1000.0f + 0.5f));
		pthread_cond_broadcast(&p->cv_done);
	}
	pthread_mutex_unlock(&p->mtx);
	return NULL;
}

BerylBuilderPool *beryl_pool_new(const BerylPoolDesc *desc) {
	BerylBuilderPool *p = (BerylBuilderPool *)calloc(1, sizeof(*p));
	if (!p) return NULL;
	p->desc = *desc;
	p->world = desc->world;
	int n = desc->threads;
	if (n <= 0) {
		long cores = sysconf(_SC_NPROCESSORS_ONLN);
		n = (int)(cores > 1 ? cores - 1 : 1);
		if (n > 4) n = 4;
	}
	p->nthreads = n;
	pthread_mutex_init(&p->mtx, NULL);
	pthread_cond_init(&p->cv_work, NULL);
	pthread_cond_init(&p->cv_done, NULL);
	heap_init(&p->pending);
	p->threads = (pthread_t *)calloc((size_t)n, sizeof(pthread_t));
	for (int i = 0; i < n; i++) {
		pthread_create(&p->threads[i], NULL, worker_main, p);
	}
	return p;
}

void beryl_pool_free(BerylBuilderPool *p) {
	if (!p) return;
	pthread_mutex_lock(&p->mtx);
	p->quit = true;
	pthread_cond_broadcast(&p->cv_work);
	pthread_cond_broadcast(&p->cv_done);
	pthread_mutex_unlock(&p->mtx);
	for (int i = 0; i < p->nthreads; i++) pthread_join(p->threads[i], NULL);
	for (size_t i = 0; i < p->done_len; i++) beryl_section_mesh_free(&p->done[i].mesh);
	free(p->done);
	heap_free(&p->pending);
	free(p->threads);
	pthread_mutex_destroy(&p->mtx);
	pthread_cond_destroy(&p->cv_work);
	pthread_cond_destroy(&p->cv_done);
	free(p);
}

void beryl_pool_request(BerylBuilderPool *p, int32_t cx, int32_t csy, int32_t cz,
                        float distance, float urgency) {
	uint64_t key = beryl_section_key(cx, csy, cz);
	pthread_mutex_lock(&p->mtx);
	if (p->pending.len >= (size_t)(p->desc.max_queue > 0 ? p->desc.max_queue : 4096)) {
		atomic_store(&p->overflowed, 1);
		pthread_mutex_unlock(&p->mtx);
		return;
	}
	if (!pending_insert(p, key)) {
		pthread_mutex_unlock(&p->mtx);
		return; /* already queued: coalesced */
	}
	float align_bonus = p->desc.view_alignment_bonus > 0.0f
	    ? p->desc.view_alignment_bonus * BERYL_MIN(distance, 64.0f) * 0.5f : 0.0f;
	Job j = { cx, csy, cz, key, distance - align_bonus - urgency * 8.0f };
	heap_push(&p->pending, j);
	pthread_cond_signal(&p->cv_work);
	pthread_mutex_unlock(&p->mtx);
}

int beryl_pool_queued(BerylBuilderPool *p) {
	pthread_mutex_lock(&p->mtx);
	int n = (int)p->pending.len;
	pthread_mutex_unlock(&p->mtx);
	return n;
}

int beryl_pool_inflight(BerylBuilderPool *p) { return (int)atomic_load(&p->inflight); }

int beryl_pool_drain(BerylBuilderPool *p, BerylBuildResult *out, int max) {
	pthread_mutex_lock(&p->mtx);
	int n = 0;
	while (n < max && p->done_len > 0) {
		/* Newest first: an old result for a section that has since changed again is
		 * pure waste, so the freshest upload is the one that reaches the GPU. */
		BerylBuildResult *r = &p->done[p->done_len - 1];
		if (r->ok) {
			*out = *r;
			n++;
		} else {
			beryl_section_mesh_free(&r->mesh);
		}
		p->done_len--;
		r = NULL;
	}
	pthread_mutex_unlock(&p->mtx);
	return n;
}

void beryl_pool_release_result(BerylBuilderPool *p, BerylBuildResult *res) {
	(void)p;
	if (res) {
		/* Ownership of mesh buffers transfers to the caller (the mesh store); the
		 * pool only reclaims results that were never taken. */
		memset(res, 0, sizeof(*res));
	}
}

void beryl_pool_finish_all(BerylBuilderPool *p) {
	for (;;) {
		pthread_mutex_lock(&p->mtx);
		bool busy = p->pending.len > 0 || atomic_load(&p->inflight) > 0 || p->done_len > 0;
		pthread_mutex_unlock(&p->mtx);
		if (!busy) break;
		/* Drain results so the done-ring cannot grow without bound. */
		BerylBuildResult r;
		while (beryl_pool_drain(p, &r, 32) > 0) {
			beryl_section_mesh_free(&r.mesh);
		}
		struct timespec ts = { 0, 2000000 };
		nanosleep(&ts, NULL);
	}
	BerylBuildResult r;
	while (beryl_pool_drain(p, &r, 32) > 0) {
		beryl_section_mesh_free(&r.mesh);
	}
}

void beryl_pool_wait_idle(BerylBuilderPool *p) {
	for (;;) {
		pthread_mutex_lock(&p->mtx);
		bool busy = p->pending.len > 0 || atomic_load(&p->inflight) > 0;
		pthread_mutex_unlock(&p->mtx);
		if (!busy) return;
		struct timespec ts = { 0, 500000 };
		nanosleep(&ts, NULL);
	}
}

int64_t beryl_pool_total_builds(BerylBuilderPool *p) { return atomic_load(&p->total_builds); }

double beryl_pool_total_ms(BerylBuilderPool *p) {
	return (double)atomic_load(&p->total_ms_u) * 0.001;
}
