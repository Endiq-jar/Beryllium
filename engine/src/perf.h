/* perf.h -- the frame governor: how much work this frame is allowed to take.
 *
 * This is the part a phone needs and a desktop pretends it does not. A voxel
 * renderer's per-frame cost is not the draw call (the GPU queues those) but the
 * bookkeeping in front of it: mesh installs, VBO uploads, chunk generation. Every
 * one of those has a budget, and on a desktop the honest answer is "spend as much
 * as there is". On a phone-class SoC that is how you trade a fast start for a
 * thermal clamp: the budgets are what the governor moves so that frame time
 * settles just under the target instead of oscillating around the throttling
 * point.
 *
 * Two asymmetries, both deliberate:
 *
 *   - it backs off after 2 bad frames and opens up only after 8 good ones, so a
 *     transient (GC, an activity switch, the launcher's own stutter) costs one
 *     small step while sustained headroom is rewarded gradually;
 *   - a frame longer than HITCH_FACTOR * target is recorded and *ignored*: it says
 *     nothing about the steady-state cost of a budget, and folding a multi-second
 *     stall into an average would pin the budgets at the floor for minutes.
 *
 * It is a pure function of the measured frame time -- no clock, no I/O, no
 * globals -- which is what makes it testable without a device and safe to drive
 * from whatever timer the embedder already has (the mod has Minecraft's own).
 */
#ifndef BERYL_PERF_H
#define BERYL_PERF_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

typedef struct BerylPerfCfg {
	float  target_frame_ms;    /* the frame this device must hit: 16.7 (60), 33.3 (30) */
	float  responsiveness;     /* 0..1, EMA rate; 0.15 is a good default */
	int    min_rebuilds, max_rebuilds;
	size_t min_upload_bytes, max_upload_bytes;
} BerylPerfCfg;

typedef struct BerylPerf {
	BerylPerfCfg cfg;
	float    ema_ms;        /* smoothed frame time the decisions are taken on */
	int      rebuilds;      /* current mesh-install budget per frame */
	size_t   upload_bytes;  /* current vertex-byte budget per frame */
	int      over_streak;
	int      under_streak;
	uint64_t adjustments;   /* how often a budget actually moved */
	uint64_t hitches;       /* frames too abnormal to count as evidence */
	bool     primed;        /* the first sample only seeds the average */
} BerylPerf;

/* A phone at 60 fps: budgets from "must not stall a frame" to "a desktop would
 * not notice", which is the range the governor has room to work in. */
void beryl_perf_cfg_default(BerylPerfCfg *cfg);
void beryl_perf_cfg_30fps(BerylPerfCfg *cfg);

void beryl_perf_init(BerylPerf *p, const BerylPerfCfg *cfg, int rebuilds, size_t upload_bytes);

/* Feed one measured frame time. Returns true when a budget changed, so a caller
 * can log or refresh overlay text without comparing fields itself. */
bool beryl_perf_tick(BerylPerf *p, float frame_ms);

/* Drop the history (resize, world load, returning to the foreground): the old
 * samples describe a frame that no longer exists. The budgets stay where they
 * are -- they are the accumulated knowledge, the average is not. */
void beryl_perf_forget(BerylPerf *p);

#endif /* BERYL_PERF_H */
