/* pool.h -- the mesh builder pool: jobs in, meshes out, off the main thread.
 *
 * Job selection is the part that decides how the engine *feels*, and it is the
 * piece Beryllium exists for in the vanilla renderer. Priorities here are:
 *
 *     priority = distance_from_eye
 *              - view_alignment_bonus   (sections the camera points at win)
 *              - urgency_bonus          (blocks broken/placed right in front)
 *
 * Lower priority runs first. Distance alone (the naive policy) makes you stare at
 * a hole in the world while the mesher builds a mountain behind you; alignment
 * alone makes panning the camera thrash the queue. The bonus terms are bounded, so
 * a job can never starve: distance dominates past ~32 blocks.
 */
#ifndef BERYL_POOL_H
#define BERYL_POOL_H

#include "mesher.h"

typedef struct BerylBuilderPool BerylBuilderPool;

typedef struct BerylPoolDesc {
	BerylWorld *world;
	int  threads;              /* 0 -> auto (cores-1, clamped to 4) */
	int  max_queue;            /* queue cap; overflow jobs are re-derived later */
	float view_alignment_bonus; /* 0..1 of the distance term, see above     */
} BerylPoolDesc;

typedef struct BerylBuildResult {
	int32_t  cx, csy, cz;
	uint64_t key;
	uint64_t built_revision;
	BerylSectionMesh mesh;
	BerylMeshStats   stats;
	float    ms;
	bool     ok;
} BerylBuildResult;

BerylBuilderPool *beryl_pool_new(const BerylPoolDesc *desc);
void              beryl_pool_free(BerylBuilderPool *p);

/* Requests a rebuild. Coalesced per section: enqueueing the same section twice
 * before it is built is one job, not two (this is what makes a redstone storm or
 * a worldgen flood affordable). */
void beryl_pool_request(BerylBuilderPool *p, int32_t cx, int32_t csy, int32_t cz,
                        float distance, float urgency);

int  beryl_pool_queued(BerylBuilderPool *p);
int  beryl_pool_inflight(BerylBuilderPool *p);
/* Pops finished builds. `max` bounds per-frame work (Beryllium's rebuild batch);
 * returns the number drained. Results must be beryl_pool_release_result()d after
 * the caller has taken ownership of the mesh. */
int  beryl_pool_drain(BerylBuilderPool *p, BerylBuildResult *out, int max);
void beryl_pool_release_result(BerylBuilderPool *p, BerylBuildResult *res);

/* Blocks until the queue and all workers are idle (used by tests and by
 * "--generate then render" batch mode). */
void beryl_pool_finish_all(BerylBuilderPool *p);

/* Blocks until the queue and all workers are idle *without* consuming the done
 * ring: the caller installs the results itself. A capture path needs this --
 * finish_all would free meshes whose section the worker already marked up to
 * date, and the hole would be permanent. */
void beryl_pool_wait_idle(BerylBuilderPool *p);

int64_t beryl_pool_total_builds(BerylBuilderPool *p);
double  beryl_pool_total_ms(BerylBuilderPool *p);

#endif /* BERYL_POOL_H */
