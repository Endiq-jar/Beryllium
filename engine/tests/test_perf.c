/* test_perf.c -- the frame governor and the tuning presets.
 *
 * The governor decides how much work the next frame may take, so the properties
 * that matter are not "is it fast" but: it does nothing while the device is
 * meeting its target, it can never starve the store, it ignores stalls, and it
 * cannot be pushed outside its clamps. Each of those is asserted here by feeding
 * it a synthetic frame-time sequence -- which is the only way any of it is
 * testable at all without a phone.
 */
#include "test.h"

#include "bcore.h"
#include "camera.h"
#include "engine.h"
#include "world.h"
#include "perf.h"

#include <math.h>
#include <string.h>

static void check_settings_eq(const BerylSettings *a, const BerylSettings *b, const char *what) {
	CHECK(a->width == b->width, "%s: width", what);
	CHECK(a->height == b->height, "%s: height", what);
	CHECK(a->backend == b->backend, "%s: backend", what);
	CHECK(a->view_distance_sections == b->view_distance_sections, "%s: view distance", what);
	CHECK(a->builder_threads == b->builder_threads, "%s: threads", what);
	CHECK(a->chunks_per_frame == b->chunks_per_frame, "%s: chunks per frame", what);
	CHECK(a->rebuilds_per_frame == b->rebuilds_per_frame, "%s: rebuilds", what);
	CHECK(a->uploads_per_frame_bytes == b->uploads_per_frame_bytes, "%s: uploads", what);
	CHECK(a->occlusion_culling == b->occlusion_culling, "%s: occlusion", what);
	CHECK(a->leaves_internal_cull == b->leaves_internal_cull, "%s: leaf cull", what);
	CHECK(a->render_mode == b->render_mode, "%s: render mode", what);
	CHECK(fabsf(a->fov_degrees - b->fov_degrees) < 1e-6f, "%s: fov", what);
	CHECK(fabsf(a->day_factor - b->day_factor) < 1e-6f, "%s: day", what);
	CHECK(a->anisotropic == b->anisotropic, "%s: aniso", what);
	CHECK(a->linear_filter == b->linear_filter, "%s: filter", what);
	CHECK(a->adaptive_budget == b->adaptive_budget, "%s: adaptive", what);
	CHECK(fabsf(a->target_frame_ms - b->target_frame_ms) < 1e-6f, "%s: target ms", what);
}

/* A stall is not a budget problem, and a frame that meets the target is not an
 * invitation to spend more. */
static void test_perf_governor(void) {
	BerylPerfCfg cfg;
	beryl_perf_cfg_default(&cfg);
	CHECK(cfg.target_frame_ms > 16.0f && cfg.target_frame_ms < 17.5f, "default target %g",
	      (double)cfg.target_frame_ms);
	CHECK(cfg.min_rebuilds == 1, "a governor that can reach 0 installs starves the store");
	CHECK(cfg.min_upload_bytes >= BERYL_KB(64), "a 0 upload budget means UNLIMITED to the store");

	BerylPerf p;
	beryl_perf_init(&p, &cfg, 6, BERYL_KB(4096));
	CHECK(p.rebuilds == 6 && p.upload_bytes == BERYL_KB(4096), "init must keep the caller's budgets");

	/* The first sample primes the average and must not decide anything. */
	CHECK(!beryl_perf_tick(&p, 900.0f), "the priming tick moved a budget");
	CHECK(p.rebuilds == 6 && p.upload_bytes == BERYL_KB(4096), "priming changed the budgets");

	/* Dead band: comfortably inside the target for hundreds of frames, and the
	 * budgets must not twitch. */
	int moved = 0;
	for (int i = 0; i < 300; i++) moved += beryl_perf_tick(&p, 16.0f) ? 1 : 0;
	CHECK(moved == 0, "the governor moved %d times inside the dead band", moved);
	CHECK(p.rebuilds == 6 && p.upload_bytes == BERYL_KB(4096), "budgets drifted while on target");

	/* Garbage in, nothing out. */
	float before = p.ema_ms;
	CHECK(!beryl_perf_tick(&p, -1.0f), "a negative frame time was accepted");
	CHECK(!beryl_perf_tick(&p, (float)NAN), "a NaN frame time was accepted");
	CHECK(!beryl_perf_tick(&p, (float)INFINITY), "an infinite frame time was accepted");
	CHECK(p.ema_ms == before, "rejected samples still moved the average");

	/* Sustained overrun: shrink monotonically to the floor, never past it. */
	int prev_r = p.rebuilds;
	size_t prev_u = p.upload_bytes;
	int grew = 0;
	for (int i = 0; i < 400; i++) {
		beryl_perf_tick(&p, 40.0f);
		grew += p.rebuilds > prev_r || p.upload_bytes > prev_u;
		prev_r = p.rebuilds;
		prev_u = p.upload_bytes;
		CHECK(p.rebuilds >= cfg.min_rebuilds && p.rebuilds <= cfg.max_rebuilds, "install budget out of range");
		CHECK(p.upload_bytes >= cfg.min_upload_bytes, "upload budget below its floor");
		if (beryl_test_failed) break;
	}
	CHECK(grew == 0, "the governor opened up %d times while the device was 2.4x over target", grew);
	CHECK(p.rebuilds == cfg.min_rebuilds && p.upload_bytes == cfg.min_upload_bytes,
	      "after 400 slow frames: %d installs, %zu KB", p.rebuilds, p.upload_bytes / 1024u);

	/* Sustained headroom: climb back to the ceiling and stop there. */
	for (int i = 0; i < 6000; i++) {
		beryl_perf_tick(&p, 2.0f);
		if (p.rebuilds >= cfg.max_rebuilds && p.upload_bytes >= cfg.max_upload_bytes) break;
	}
	CHECK(p.rebuilds == cfg.max_rebuilds, "did not reopen the throttle: %d/%d", p.rebuilds,
	      cfg.max_rebuilds);
	CHECK(p.upload_bytes == cfg.max_upload_bytes, "did not reopen the upload budget: %zu",
	      p.upload_bytes);
	for (int i = 0; i < 500; i++) beryl_perf_tick(&p, 2.0f);
	CHECK(p.rebuilds == cfg.max_rebuilds && p.upload_bytes == cfg.max_upload_bytes,
	      "budgets escaped their ceiling");

	/* A hitch, and the stalls around it, must not be evidence. */
	beryl_perf_init(&p, &cfg, 12, BERYL_KB(2048));
	for (int i = 0; i < 40; i++) beryl_perf_tick(&p, 16.5f);
	uint64_t adj_before = p.adjustments;
	float ema_before = p.ema_ms;
	for (int i = 0; i < 5; i++) CHECK(!beryl_perf_tick(&p, 5000.0f), "a stall was treated as load");
	CHECK(p.ema_ms == ema_before, "a 5 s stall polluted the frame average");
	CHECK(p.adjustments == adj_before, "a stall changed the budgets");
	CHECK(p.hitches >= 5u, "hitches not counted (%lu)", (unsigned long)p.hitches);
	CHECK(p.rebuilds == 12 && p.upload_bytes == BERYL_KB(2048), "budgets moved behind a stall");

	/* A stall must not be *combined* with its neighbours either: the streaks
	 * reset, so "one slow frame, a GC hitch, one slow frame" is not two bad
	 * frames and must not shrink anything. */
	beryl_perf_init(&p, &cfg, 12, BERYL_KB(2048));
	for (int i = 0; i < 10; i++) beryl_perf_tick(&p, 16.5f);
	beryl_perf_tick(&p, 40.0f);
	CHECK(p.over_streak == 1, "expected a one-frame overrun streak, got %d", p.over_streak);
	beryl_perf_tick(&p, 5000.0f);
	CHECK(p.over_streak == 0, "a stall did not reset the overrun streak");
	beryl_perf_tick(&p, 40.0f);
	CHECK(p.rebuilds == 12 && p.upload_bytes == BERYL_KB(2048),
	      "a stall plus one slow frame was enough to shrink the budgets");

	/* forget() drops history, keeps knowledge. */
	beryl_perf_forget(&p);
	CHECK(!p.primed, "forget did not un-prime the average");
	CHECK(p.rebuilds == 12 && p.upload_bytes == BERYL_KB(2048), "forget reset the budgets too");
	CHECK(!beryl_perf_tick(&p, 4000.0f), "after forget, one hitch changed the budgets");

	/* A caller that hands over an impossible configuration still gets a governor
	 * that cannot wedge the renderer. */
	BerylPerfCfg bad;
	memset(&bad, 0, sizeof(bad));
	bad.min_rebuilds = 0;
	bad.max_rebuilds = -4;
	bad.min_upload_bytes = 0;
	bad.max_upload_bytes = 0;
	beryl_perf_init(&p, &bad, 999999, 0);
	CHECK(p.rebuilds >= 1, "no floor on installs (rebuilds=%d)", p.rebuilds);
	CHECK(p.upload_bytes >= BERYL_KB(64), "upload floor below the store's 'unlimited' sentinel");
	for (int i = 0; i < 200; i++) beryl_perf_tick(&p, 500.0f);
	CHECK(p.rebuilds >= 1 && p.upload_bytes > 0, "a degenerate config starved the store");

	beryl_perf_tick(NULL, 16.0f);          /* must not crash */
	beryl_perf_forget(NULL);
	beryl_perf_init(NULL, &cfg, 1, 1024);
	beryl_perf_cfg_default(NULL);
	CHECK(1, "degenerate governor calls survived");
}

static void test_perf_presets(void) {
	BerylSettings base, desk, mob, low;
	beryl_settings_default(&base, 1280, 720, BERYL_BACKEND_SOFTWARE);

	beryl_settings_default(&desk, 1280, 720, BERYL_BACKEND_SOFTWARE);
	beryl_settings_apply_preset(&desk, BERYL_PRESET_DESKTOP);
	check_settings_eq(&base, &desk, "desktop preset must be the compiled defaults");

	beryl_settings_default(&mob, 1280, 720, BERYL_BACKEND_SOFTWARE);
	beryl_settings_apply_preset(&mob, BERYL_PRESET_MOBILE);
	CHECK(mob.width == 1280 && mob.height == 720, "a preset may not resize the framebuffer");
	CHECK(mob.backend == BERYL_BACKEND_SOFTWARE, "a preset may not change the backend");
	CHECK(mob.view_distance_sections < base.view_distance_sections,
	      "mobile view distance %d not smaller than desktop %d", mob.view_distance_sections,
	      base.view_distance_sections);
	CHECK(mob.view_distance_sections >= 4, "mobile view distance %d is below a usable radius",
	      mob.view_distance_sections);
	CHECK(mob.uploads_per_frame_bytes > 0 &&
	              mob.uploads_per_frame_bytes < base.uploads_per_frame_bytes,
	      "mobile upload budget %d KB (desktop %d KB)", mob.uploads_per_frame_bytes / 1024,
	      base.uploads_per_frame_bytes / 1024);
	CHECK(mob.rebuilds_per_frame >= 1, "mobile preset cannot install meshes");
	CHECK(mob.chunks_per_frame >= 1, "mobile preset generates nothing per frame");
	CHECK(mob.occlusion_culling, "mobile must cull: it is a pure saving in draws");
	CHECK(!mob.linear_filter, "linear filtering on a 16px atlas costs bandwidth for nothing");
	CHECK(mob.adaptive_budget, "the mobile preset exists to run the governor");
	CHECK(fabsf(mob.target_frame_ms - 16.7f) < 0.5f, "mobile target %g ms is not 60 fps",
	      (double)mob.target_frame_ms);

	/* Applying it twice must be the same as once: a launcher re-applies presets on
	 * every config change. */
	BerylSettings mob2 = mob;
	beryl_settings_apply_preset(&mob2, BERYL_PRESET_MOBILE);
	check_settings_eq(&mob, &mob2, "mobile preset is not idempotent");

	beryl_settings_default(&low, 1280, 720, BERYL_BACKEND_SOFTWARE);
	beryl_settings_apply_preset(&low, BERYL_PRESET_LOW_END);
	CHECK(low.view_distance_sections <= mob.view_distance_sections,
	      "low-end view distance %d wider than mobile %d", low.view_distance_sections,
	      mob.view_distance_sections);
	CHECK(low.uploads_per_frame_bytes <= mob.uploads_per_frame_bytes,
	      "low-end uploads %d KB above mobile %d KB", low.uploads_per_frame_bytes / 1024,
	      mob.uploads_per_frame_bytes / 1024);
	CHECK(fabsf(low.target_frame_ms - 33.3f) < 1.0f, "low-end target %g ms is not 30 fps",
	      (double)low.target_frame_ms);
	beryl_settings_apply_preset(NULL, BERYL_PRESET_MOBILE);
	CHECK(1, "NULL settings accepted");

	/* The presets and the governor have to agree, or the first tick clamps the
	 * starting point and the "mobile" numbers are a lie. */
	BerylPerfCfg cfg;
	beryl_perf_cfg_default(&cfg);
	BerylPerf p;
	beryl_perf_init(&p, &cfg, mob.rebuilds_per_frame, (size_t)mob.uploads_per_frame_bytes);
	CHECK(p.rebuilds == mob.rebuilds_per_frame, "mobile install budget %d outside the governor",
	      mob.rebuilds_per_frame);
	CHECK(p.upload_bytes == (size_t)mob.uploads_per_frame_bytes,
	      "mobile upload budget %d KB outside the governor", mob.uploads_per_frame_bytes / 1024);
	beryl_perf_cfg_30fps(&cfg);
	beryl_perf_init(&p, &cfg, low.rebuilds_per_frame, (size_t)low.uploads_per_frame_bytes);
	CHECK(p.rebuilds == low.rebuilds_per_frame && p.upload_bytes == (size_t)low.uploads_per_frame_bytes,
	      "low-end budgets outside the 30 fps governor");
	/* A phone that turns out to be fine gets its throughput back. */
	beryl_perf_tick(&p, 33.0f);
	int start = p.rebuilds;
	for (int i = 0; i < 2000; i++) beryl_perf_tick(&p, 6.0f);
	CHECK(p.rebuilds > start, "a fast device never got more budget (%d)", p.rebuilds);
}

/* The governor is only worth having if the engine actually moves its budgets on
 * it: this drives a real engine through three updates and reads the settings back. */
static void test_perf_engine_wiring(void) {
	BerylSettings s;
	beryl_settings_default(&s, 320, 180, BERYL_BACKEND_SOFTWARE);
	s.view_distance_sections = 4;
	s.adaptive_budget = true;
	s.target_frame_ms = 10.0f;
	s.rebuilds_per_frame = 16;
	s.uploads_per_frame_bytes = 2 * 1024 * 1024;
	BerylWorldDesc d;
	memset(&d, 0, sizeof(d));
	d.seed = 1234ull;
	d.radius_sections = 128;
	BerylEngine *e = beryl_engine_create(&s, &d);
	if (!e) { CHECK(0, "engine create"); return; }

	BerylCamera cam;
	beryl_camera_init(&cam, 320, 180);
	cam.pos = beryl_vec3(8.0f, 70.0f, 8.0f);
	beryl_camera_update(&cam);

	BerylSettings now;
	beryl_engine_settings(e, &now);
	CHECK(now.rebuilds_per_frame == 16, "the engine did not start at the caller's budget");
	/* Three frames the device cannot hit: the governor must have backed off, and
	 * it must not be able to reach a budget that starves the store. */
	for (int i = 0; i < 3; i++) {
		beryl_engine_note_frame_ms(e, 400.0f);   /* > 3x target => hitch, ignored ... */
		beryl_engine_update(e, &cam, 0.016f);
	}
	beryl_engine_settings(e, &now);
	CHECK(now.rebuilds_per_frame == 16,
	      "a 40x overrun hitch moved the budget (hitches must be ignored)");
	/* 14 ms against a 10 ms target: outside the +10% band, inside the 3x hitch
	 * window -- which is the only overrun the governor is allowed to act on. */
	for (int i = 0; i < 40; i++) {
		beryl_engine_note_frame_ms(e, 14.0f);
		beryl_engine_update(e, &cam, 0.016f);
	}
	beryl_engine_settings(e, &now);
	CHECK(now.rebuilds_per_frame < 16 && now.rebuilds_per_frame >= 1,
	      "14 ms frames against a 10 ms target left %d installs per frame",
	      now.rebuilds_per_frame);
	CHECK(now.uploads_per_frame_bytes >= 64 * 1024,
	      "upload budget fell to %d KB (0 would mean unlimited to the store)",
	      now.uploads_per_frame_bytes);
	BerylEngineStats st;
	beryl_engine_stats(e, &st);
	CHECK(st.perf_adjustments > 0, "the engine never counted an adjustment");
	/* Off means off: with the governor disabled the same 60 ms frames must leave
	 * the budgets exactly where the caller put them. */
	beryl_engine_settings(e, &now);
	now.adaptive_budget = false;
	now.rebuilds_per_frame = 16;
	now.uploads_per_frame_bytes = 2 * 1024 * 1024;
	beryl_engine_set_settings(e, &now);
	int pinned_at = now.rebuilds_per_frame;
	for (int i = 0; i < 30; i++) {
		beryl_engine_note_frame_ms(e, 14.0f);
		beryl_engine_update(e, &cam, 0.016f);
	}
	beryl_engine_settings(e, &now);
	CHECK(now.rebuilds_per_frame == pinned_at && now.uploads_per_frame_bytes == 2 * 1024 * 1024,
	      "adaptive_budget=off still moved the budgets (%d installs, %d KB)",
	      now.rebuilds_per_frame, now.uploads_per_frame_bytes);
	beryl_engine_note_frame_ms(NULL, 16.0f);   /* must not crash */
	beryl_engine_destroy(e);
}

void test_perf(void) {
	test_perf_governor();
	test_perf_presets();
	test_perf_engine_wiring();
}
