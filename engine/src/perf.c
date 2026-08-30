/* perf.c -- see perf.h. Deliberately arithmetic only: no clock reads, no globals,
 * no allocation, so it can be stepped from a unit test and reasoned about.
 */
#include "perf.h"

#include "bcore.h"

#include <math.h>
#include <string.h>

/* The dead band. Decisions are taken only outside it, which is what keeps a
 * device that is already hitting its target from moving at all. */
#define PERF_OVER_BAND  1.10f
#define PERF_UNDER_BAND 0.80f
/* Anything past this is a stall, not a budget problem. */
#define PERF_HITCH      3.0f
/* Consecutive samples required before acting: fail fast, trust slowly. */
#define PERF_OVER_NEED  2
#define PERF_UNDER_NEED 8

static void clamp_cfg(BerylPerfCfg *c) {
	/* A governor that can reach 0 is worse than no governor: the mesh store treats
	 * a zero upload budget as "unlimited", and zero installs is a permanent hole,
	 * so both floors are pinned above the magic values. */
	if (!(c->target_frame_ms > 0.5f) || !isfinite(c->target_frame_ms)) c->target_frame_ms = 16.7f;
	if (!isfinite(c->responsiveness)) c->responsiveness = 0.15f;
	c->responsiveness = BERYL_CLAMP(c->responsiveness, 0.02f, 0.5f);
	if (c->min_rebuilds < 1) c->min_rebuilds = 1;
	if (c->max_rebuilds < c->min_rebuilds) c->max_rebuilds = c->min_rebuilds;
	if (c->min_upload_bytes < BERYL_KB(64)) c->min_upload_bytes = BERYL_KB(64);
	if (c->max_upload_bytes < c->min_upload_bytes) c->max_upload_bytes = c->min_upload_bytes;
}

void beryl_perf_cfg_default(BerylPerfCfg *cfg) {
	if (!cfg) return;
	memset(cfg, 0, sizeof(*cfg));
	cfg->target_frame_ms = 16.7f;
	cfg->responsiveness = 0.15f;
	cfg->min_rebuilds = 1;
	cfg->max_rebuilds = 24;
	cfg->min_upload_bytes = BERYL_KB(256);
	cfg->max_upload_bytes = BERYL_KB(8192);
	clamp_cfg(cfg);
}

void beryl_perf_cfg_30fps(BerylPerfCfg *cfg) {
	if (!cfg) return;
	beryl_perf_cfg_default(cfg);
	/* A lower target buys headroom for the two things a phone cannot defer:
	 * input latency on the next tap and the loader keeping up with movement. */
	cfg->target_frame_ms = 33.3f;
	cfg->responsiveness = 0.20f;
	cfg->max_rebuilds = 16;
	cfg->max_upload_bytes = BERYL_KB(6144);
	clamp_cfg(cfg);
}

void beryl_perf_init(BerylPerf *p, const BerylPerfCfg *cfg, int rebuilds, size_t upload_bytes) {
	if (!p) return;
	memset(p, 0, sizeof(*p));
	if (cfg) p->cfg = *cfg;
	else beryl_perf_cfg_default(&p->cfg);
	clamp_cfg(&p->cfg);

	p->rebuilds = rebuilds > 0 ? BERYL_CLAMP(rebuilds, p->cfg.min_rebuilds, p->cfg.max_rebuilds)
	                           : (p->cfg.min_rebuilds + p->cfg.max_rebuilds) / 2;
	p->upload_bytes = upload_bytes ? BERYL_CLAMP(upload_bytes, p->cfg.min_upload_bytes,
	                                             p->cfg.max_upload_bytes)
	                               : (p->cfg.min_upload_bytes + p->cfg.max_upload_bytes) / 2;
	p->ema_ms = p->cfg.target_frame_ms;   /* neutral: start inside the dead band */
	p->primed = false;
}

bool beryl_perf_tick(BerylPerf *p, float frame_ms) {
	if (!p) return false;
	if (!isfinite(frame_ms) || frame_ms < 0.0f) return false;

	const float target = p->cfg.target_frame_ms;
	if (!p->primed) {
		p->primed = true;
		/* The first sample is usually the frame that built the world: seed with it
		 * only if it is plausible, otherwise stay neutral. */
		p->ema_ms = frame_ms <= target * PERF_HITCH ? frame_ms : target;
		return false;
	}
	if (frame_ms > target * PERF_HITCH) {
		p->hitches++;
		p->over_streak = p->under_streak = 0;
		return false;
	}

	p->ema_ms += p->cfg.responsiveness * (frame_ms - p->ema_ms);
	bool changed = false;

	if (p->ema_ms > target * PERF_OVER_BAND) {
		p->under_streak = 0;
		if (++p->over_streak >= PERF_OVER_NEED) {
			p->over_streak = 0;
			int r = p->rebuilds * 3 / 4;
			if (r < p->cfg.min_rebuilds) r = p->cfg.min_rebuilds;
			size_t u = p->upload_bytes / 4 * 3;
			if (u < p->cfg.min_upload_bytes) u = p->cfg.min_upload_bytes;
			if (r != p->rebuilds || u != p->upload_bytes) {
				p->rebuilds = r;
				p->upload_bytes = u;
				changed = true;
			}
		}
	} else if (p->ema_ms < target * PERF_UNDER_BAND) {
		p->over_streak = 0;
		if (++p->under_streak >= PERF_UNDER_NEED) {
			p->under_streak = 0;
			int r = p->rebuilds + BERYL_MAX(1, p->rebuilds / 8);
			if (r > p->cfg.max_rebuilds) r = p->cfg.max_rebuilds;
			size_t u = p->upload_bytes + BERYL_MAX(BERYL_KB(64), p->upload_bytes / 8);
			if (u > p->cfg.max_upload_bytes) u = p->cfg.max_upload_bytes;
			if (r != p->rebuilds || u != p->upload_bytes) {
				p->rebuilds = r;
				p->upload_bytes = u;
				changed = true;
			}
		}
	} else {
		/* Inside the band: no movement at all, which is the whole point of a band. */
		p->over_streak = p->under_streak = 0;
	}

	if (changed) p->adjustments++;
	return changed;
}

void beryl_perf_forget(BerylPerf *p) {
	if (!p) return;
	p->ema_ms = p->cfg.target_frame_ms;
	p->primed = false;
	p->over_streak = p->under_streak = 0;
}
