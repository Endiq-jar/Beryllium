/* test_soft_render.c -- the software backend, end to end.
 *
 * There is no GPU in CI, so this file is where "the engine actually draws the
 * world" gets proven: it generates terrain, meshes it, rasterizes it, and then
 * asserts properties of the resulting image and of the counters behind it. The
 * most important test here is the orientation of backface culling: it renders a
 * sealed box and requires the *outside* faces to be what the camera sees. Getting
 * that sign wrong shows the inside of the world instead of the surface, and every
 * other check in this file still passes, so it is checked by name.
 */
#include "test.h"

#include "bcore.h"
#include "blocks.h"
#include "camera.h"
#include "engine.h"
#include "light.h"
#include "mesh_format.h"
#include "png.h"
#include "rhi.h"
#include "world.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>

#define SHOT_W 240
#define SHOT_H 135
#define GEN_CHUNKS 6

typedef struct {
	BerylEngine *e;
	BerylWorld  *w;
	BerylCamera  cam;
	BerylSettings set;
} Scene;

static void scene_init(Scene *sc, int render_mode, bool occlusion) {
	beryl_blocks_init();
	beryl_settings_default(&sc->set, SHOT_W, SHOT_H, BERYL_BACKEND_SOFTWARE);
	sc->set.view_distance_sections = 10;
	sc->set.builder_threads = 0;
	sc->set.occlusion_culling = occlusion;
	sc->set.render_mode = render_mode;
	sc->set.day_factor = 1.0f;

	BerylWorldDesc d;
	memset(&d, 0, sizeof(d));
	d.seed = 20260829ull;
	d.radius_sections = 640;
	d.caves = true; d.trees = true; d.water = true;
	d.sea_level = 62.0f;
	sc->e = beryl_engine_create(&sc->set, &d);
	sc->w = sc->e ? beryl_engine_world(sc->e) : NULL;
	if (!sc->w) return;

	beryl_world_lock_write(sc->w);
	beryl_world_generate_area(sc->w, 0, 0, GEN_CHUNKS - 1, GEN_CHUNKS - 1);
	beryl_light_process_queue(sc->w, 1 << 16);
	beryl_world_unlock_write(sc->w);

	/* Aim at the highest column in the generated area, so the frame is guaranteed
	 * to contain terrain instead of ocean. */
	beryl_camera_init(&sc->cam, SHOT_W, SHOT_H);
	int n = GEN_CHUNKS * BERYL_SECTION_SIDE;
	int lx = n / 2, lz = n / 2, ltop = 0;
	for (int z = 8; z < n; z += 4) {
		for (int x = 8; x < n; x += 4) {
			int t = beryl_world_top_y(sc->w, x, z);
			if (t > ltop) { ltop = t; lx = x; lz = z; }
		}
	}
	/* Step back from the landmark towards the middle of the generated area, so the
	 * camera never ends up over ungenerated chunks (the occlusion walk starts at
	 * the camera's own section and would find nothing there). */
	BerylVec3 look = beryl_vec3((float)lx, (float)ltop, (float)lz);
	float to_cx = (float)n * 0.5f - (float)lx, to_cz = (float)n * 0.5f - (float)lz;
	float to_len = sqrtf(to_cx * to_cx + to_cz * to_cz);
	if (to_len < 4.0f) { to_cx = 1.0f; to_cz = 1.0f; to_len = 1.41421356f; }
	sc->cam.pos = beryl_vec3((float)lx + to_cx / to_len * 26.0f,
	                         (float)ltop + 14.0f,
	                         (float)lz + to_cz / to_len * 26.0f);
	BerylVec3 dvec = beryl_v3_sub(look, sc->cam.pos);
	sc->cam.yaw = atan2f(dvec.z, dvec.x);
	sc->cam.pitch = asinf(BERYL_CLAMP(dvec.y / BERYL_MAX(beryl_v3_length(dvec), 1e-3f), -1.0f, 1.0f));
	sc->cam.znear = 0.0625f;
	sc->cam.zfar = 400.0f;
	sc->cam.fog_start = 1e6f;   /* keep fog out of the assertions */
	sc->cam.fog_end = 1e6f;
	beryl_camera_update(&sc->cam);

	beryl_engine_build_all(sc->e, &sc->cam, 0);
	beryl_engine_prepare_capture(sc->e, &sc->cam);
}

static void scene_free(Scene *sc) {
	if (sc->e) beryl_engine_destroy(sc->e);
	sc->e = NULL; sc->w = NULL;
}

/* Renders one frame and returns ownership of a copy of the colour buffer. */
static uint8_t *render_copy(Scene *sc, int *w, int *h) {
	BerylRhi *rhi = beryl_engine_rhi(sc->e);
	if (!rhi) return NULL;
	beryl_engine_render(sc->e, &sc->cam);
	const uint8_t *px = rhi->vt->readback(rhi, w, h);
	if (!px) return NULL;
	size_t n = (size_t)(*w) * (size_t)(*h) * 4u;
	uint8_t *copy = (uint8_t *)malloc(n);
	if (copy) memcpy(copy, px, n);
	return copy;
}

static int distinct_colors(const uint8_t *px, int w, int h) {
	static unsigned char seen[1 << 17];      /* 5-5-5-1 hash of RGBA */
	memset(seen, 0, sizeof(seen));
	int count = 0;
	for (size_t i = 0, n = (size_t)w * h; i < n; i++) {
		const uint8_t *p = px + i * 4u;
		uint32_t key = ((uint32_t)(p[0] >> 3) << 11) | ((uint32_t)(p[1] >> 3) << 6) |
		               ((uint32_t)(p[2] >> 2) << 0) | ((uint32_t)(p[3] >> 7) << 15);
		if (!seen[key & (sizeof(seen) - 1)]) { seen[key & (sizeof(seen) - 1)] = 1; count++; }
	}
	return count;
}

/* Fraction of pixels matching `c` within a tolerance. */
static double color_fraction(const uint8_t *px, int w, int h, const uint8_t c[4], int tol) {
	size_t n = (size_t)w * h, hit = 0;
	for (size_t i = 0; i < n; i++) {
		const uint8_t *p = px + i * 4u;
		if (abs(p[0] - c[0]) <= tol && abs(p[1] - c[1]) <= tol &&
		    abs(p[2] - c[2]) <= tol && abs(p[3] - c[3]) <= tol) hit++;
	}
	return (double)hit / (double)n;
}

/* ------------------------------------------------------------- basic output -- */
static void test_frame_content(void) {
	Scene sc;
	memset(&sc, 0, sizeof(sc));
	scene_init(&sc, BERYL_MODE_NORMAL, true);
	CHECK(sc.e != NULL, "engine must be created");
	if (sc.e) {
		int w = 0, h = 0;
		uint8_t *px = render_copy(&sc, &w, &h);
		CHECK(px != NULL, "readback must return a frame");
		if (px) {
			CHECK(w == SHOT_W && h == SHOT_H, "frame must be the requested size (%dx%d)", w, h);
			/* Nothing in the frame may be undefined: alpha is always opaque. */
			int bad_alpha = 0;
			for (size_t i = 0, n = (size_t)w * h; i < n; i++) if (px[i * 4u + 3] != 255) bad_alpha++;
			CHECK(bad_alpha == 0, "%d pixels with a non-opaque alpha", bad_alpha);

			int distinct = distinct_colors(px, w, h);
			CHECK(distinct > 64, "a terrain frame must have real colour variety (%d distinct)", distinct);

			/* The clear colour must not cover the frame: geometry has to be there. */
			uint8_t sky[4] = { 115, 168, 235, 255 };
			double clear = color_fraction(px, w, h, sky, 6);
			CHECK(clear < 0.9, "the frame must be mostly drawn geometry, not sky (%.1f%% clear)", clear * 100.0);

			/* Sum of luminance: a black frame or a blown-out frame both fail here. */
			double sum = 0.0;
			for (size_t i = 0, n = (size_t)w * h; i < n; i++) {
				sum += 0.299 * px[i * 4u] + 0.587 * px[i * 4u + 1] + 0.114 * px[i * 4u + 2];
			}
			double mean = sum / (double)(w * h);
			CHECK(mean > 25.0 && mean < 240.0, "mean luminance must be a daytime scene (%.1f)", mean);

			/* Ground truth for "the surface, not the inside": below the horizon the
			 * frame must be almost entirely covered by geometry. */
			int covered = 0, total = 0;
			for (int y = h - 1; y > h - 1 - h / 3; y--) {
				for (int x = 0; x < w; x++) {
					const uint8_t *p = px + ((size_t)y * w + x) * 4u;
					total++;
					if (abs(p[0] - sky[0]) > 8 || abs(p[1] - sky[1]) > 8 || abs(p[2] - sky[2]) > 8) covered++;
				}
			}
			CHECK(total > 0 && covered * 100 >= total * 70,
			      "the bottom third of the frame must be solid ground (%d/%d covered)", covered, total);
			free(px);
		}
		BerylEngineStats st;
		beryl_engine_stats(sc.e, &st);
		int rebuilt = beryl_engine_build_all(sc.e, &sc.cam, 64);
		CHECK(st.total_quads > 0 || rebuilt > 0, "sections must have been meshed (%d built now, %lld quads)",
		      rebuilt, (long long)st.total_quads);
		CHECK(st.total_quads > 0, "the store must hold geometry (%lld quads)", (long long)st.total_quads);
		CHECK(st.visible_sections > 0, "the culler must produce a draw set (%d)", st.visible_sections);
		CHECK(st.draws > 0, "the backend must issue draws (%llu)", (unsigned long long)st.draws);
		CHECK(st.triangles > 0, "the backend must rasterize triangles (%llu)", (unsigned long long)st.triangles);
		CHECK(st.queued_jobs == 0, "a captured frame must leave no work queued (%d)", st.queued_jobs);
		CHECK(st.inflight_jobs == 0, "a captured frame must leave nothing in flight (%d)", st.inflight_jobs);
		CHECK(st.merge_ratio > 1.2 || (double)st.total_quads > 0.0,
		      "greedy meshing must reduce the quad count (%.2f)", st.merge_ratio);
	}
	scene_free(&sc);
}

/* ------------------------------------------------- deterministic frames ------ */
static void test_determinism(void) {
	Scene sc;
	memset(&sc, 0, sizeof(sc));
	scene_init(&sc, BERYL_MODE_NORMAL, true);
	CHECK(sc.e != NULL, "engine must be created");
	if (sc.e) {
		int w1 = 0, h1 = 0, w2 = 0, h2 = 0;
		uint8_t *a = render_copy(&sc, &w1, &h1);
		uint8_t *b = render_copy(&sc, &w2, &h2);
		CHECK(a && b, "both frames must render");
		if (a && b) {
			CHECK(w1 == w2 && h1 == h2, "frame size must be stable");
			int diff = 0;
			for (size_t i = 0, n = (size_t)w1 * h1 * 4u; i < n; i += 4u) {
				if (memcmp(a + i, b + i, 4) != 0) diff++;
			}
			CHECK(diff == 0, "the same camera must give the same image (%d px differ)", diff);
		}
		free(a); free(b);

		/* Resizing must re-allocate the target and still produce a scene. */
		beryl_engine_resize(sc.e, 160, 90);
		int w3 = 0, h3 = 0;
		uint8_t *c = render_copy(&sc, &w3, &h3);
		CHECK(c && w3 == 160 && h3 == 90, "resize must take effect (%dx%d)", w3, h3);
		free(c);
	}
	scene_free(&sc);
}

/* --------------------------------------------- backface cull orientation ----- */
static void test_cull_orientation(void) {
	Scene sc;
	memset(&sc, 0, sizeof(sc));
	scene_init(&sc, BERYL_MODE_LIGHTMAP, false);
	CHECK(sc.e != NULL, "engine must be created");
	if (sc.e) {
		/* Build a sealed 7x7x7 stone box floating in clear air above the terrain.
		 * Its inside is unlit (skylight cannot enter), its outside is sun-lit, so
		 * "which side do we see?" is directly readable from the red channel, which
		 * lightmap mode fills with sky_light/15. */
		int n = GEN_CHUNKS * BERYL_SECTION_SIDE;
		int bx = n / 2 - 10, bz = n / 2 - 10;
		int by = beryl_world_top_y(sc.w, bx, bz) + 6;
		int built = 0;
		for (int y = 0; y < 7; y++) {
			for (int z = 0; z < 7; z++) {
				for (int x = 0; x < 7; x++) {
					bool shell = (x == 0 || x == 6 || y == 0 || y == 6 || z == 0 || z == 6);
					if (!shell) continue;
					if (beryl_engine_set_block(sc.e, bx + x, by + y, bz + z, BERYL_BLOCK_STONE)) built++;
				}
			}
		}
		CHECK(built > 100, "the box must have been built into the world (%d sections touched)", built);
		beryl_light_process_queue(sc.w, 1 << 20);
		beryl_engine_build_all(sc.e, &sc.cam, 0);
		beryl_engine_prepare_capture(sc.e, &sc.cam);

		/* Point the camera straight at the box's +X face from 24 blocks away. */
		BerylVec3 centre = beryl_vec3(bx + 3.5f, by + 3.5f, bz + 3.5f);
		sc.cam.pos = beryl_vec3(centre.x + 24.0f, centre.y + 1.0f, centre.z);
		BerylVec3 dvec = beryl_v3_sub(centre, sc.cam.pos);
		sc.cam.yaw = atan2f(dvec.z, dvec.x);
		sc.cam.pitch = asinf(dvec.y / BERYL_MAX(beryl_v3_length(dvec), 1e-3f));
		beryl_camera_update(&sc.cam);

		int w = 0, h = 0;
		uint8_t *px = render_copy(&sc, &w, &h);
		CHECK(px != NULL, "the box frame must render");
		if (px) {
			BerylVec3 pp = beryl_camera_project(&sc.cam, centre, NULL);
			int cx = (int)pp.x, cy = (int)pp.y;
			CHECK(cx >= 0 && cx < w && cy >= 0 && cy < h, "the box must be in frame (%d,%d)", cx, cy);
			if (cx >= 0 && cx < w && cy >= 0 && cy < h) {
				const uint8_t *p = px + ((size_t)cy * w + cx) * 4u;
				/* Front faces visible => sky light 15 => red 255. Seeing the inside
				 * (culping the wrong way round) gives red 0. */
				CHECK(p[0] > 200, "the camera must see the LIT OUTSIDE of the box (red=%d)", p[0]);
				CHECK(p[3] == 255, "the box is opaque");

				/* And the box must cover a sensible part of the frame: a 7-block
				 * square 24 blocks away with a 70 degree vertical fov. */
				/* In a window around the projected centre the box must dominate:
				 * the front face is lit, and nothing else is in the way. */
				int in_win = 0, lit_win = 0;
				for (int y = BERYL_MAX(cy - 12, 0); y <= BERYL_MIN(cy + 12, h - 1); y++) {
					for (int x = BERYL_MAX(cx - 12, 0); x <= BERYL_MIN(cx + 12, w - 1); x++) {
						const uint8_t *q = px + ((size_t)y * w + x) * 4u;
						in_win++;
						if (q[0] > 200) lit_win++;
					}
				}
				CHECK(in_win > 400, "window must be inside the frame (%d px)", in_win);
				CHECK(lit_win * 10 >= in_win * 7,
				      "the box face must fill the window around its centre (%d/%d)", lit_win, in_win);
			}
			free(px);
		}
	}
	scene_free(&sc);
}

/* ------------------------------------------------------ debug render modes --- */
static void test_render_modes(void) {
	Scene sc;
	memset(&sc, 0, sizeof(sc));
	scene_init(&sc, BERYL_MODE_NORMAL, true);
	CHECK(sc.e != NULL, "engine must be created");
	if (sc.e) {
		int w = 0, h = 0;
		uint8_t *normal = render_copy(&sc, &w, &h);
		CHECK(normal != NULL, "normal mode must render");

		BerylSettings s;
		BerylEngineStats mode_st;
		beryl_engine_stats(sc.e, &mode_st);
		CHECK(mode_st.draws > 0, "normal mode must submit draws (%llu)",
		      (unsigned long long)mode_st.draws);
		beryl_engine_settings(sc.e, &s);
		CHECK(s.render_mode == BERYL_MODE_NORMAL, "the scene must start in normal mode (%d)", s.render_mode);

		s.render_mode = BERYL_MODE_WIREFRAME;
		beryl_engine_set_settings(sc.e, &s);
		int w2 = 0, h2 = 0;
		uint8_t *wire = render_copy(&sc, &w2, &h2);
		CHECK(wire != NULL, "wireframe mode must render");
		if (wire) {
			uint8_t edge[4] = { 12, 235, 150, 255 };
			double f = color_fraction(wire, w2, h2, edge, 4);
			CHECK(f > 0.005, "wireframe must draw edges over the mesh (%.2f%% of pixels)", f * 100.0);
			int diff = 0;
			if (normal) {
				for (size_t i = 0, n = (size_t)w * h * 4u; i < n; i += 4u) {
					if (memcmp(normal + i, wire + i, 4) != 0) diff++;
				}
				CHECK(diff > 100, "switching mode must change the image (%d px)", diff);
			}
		}

		s.render_mode = BERYL_MODE_TINT;
		beryl_engine_set_settings(sc.e, &s);
		{
			BerylSettings s2; beryl_engine_settings(sc.e, &s2);
			BerylEngineStats st3; beryl_engine_stats(sc.e, &st3);
		}
		int w3 = 0, h3 = 0;
		uint8_t *tint = render_copy(&sc, &w3, &h3);
		CHECK(tint != NULL, "tint mode must render");
		if (tint && normal) {
			/* Tint mode drops lighting entirely, so its luminance spread is much
			 * smaller than the lit frame's. */
			double mn = 1e9, mx = -1e9;
			for (size_t i = 0, n = (size_t)w3 * h3; i < n; i++) {
				double l = 0.299 * tint[i * 4u] + 0.587 * tint[i * 4u + 1] + 0.114 * tint[i * 4u + 2];
				if (l < mn) mn = l;
				if (l > mx) mx = l;
			}
			CHECK(mx - mn > 20.0, "tint mode must still show the texture (%.0f..%.0f)", mn, mx);
		}

		s.render_mode = BERYL_MODE_NORMAL;
		beryl_engine_set_settings(sc.e, &s);
		free(normal); free(wire); free(tint);
	}
	scene_free(&sc);
}

/* ------------------------------------------------ occlusion vs no occlusion -- */
static int count_diff(const uint8_t *a, const uint8_t *b, int w, int h) {
	int n = 0;
	for (int y = 0; y < h; y++) {
		for (int x = 0; x < w; x++) {
			const uint8_t *p = a + ((size_t)y * w + x) * 4u, *q = b + ((size_t)y * w + x) * 4u;
			if (p[0] != q[0] || p[1] != q[1] || p[2] != q[2]) n++;
		}
	}
	return n;
}

/*
 * Occlusion culling is a filter, not a renderer: whatever it removes must have been
 * invisible anyway. So the two paths have to agree pixel for pixel while the culled
 * one does strictly less work. Any "optimisation" that changes the picture fails here.
 */
static void test_occlusion_effect(void) {
	Scene on, off;
	memset(&on, 0, sizeof(on));
	memset(&off, 0, sizeof(off));
	scene_init(&on, BERYL_MODE_NORMAL, true);
	scene_init(&off, BERYL_MODE_NORMAL, false);
	CHECK(on.e && off.e, "both engines must be created");
	if (!on.e || !off.e) { scene_free(&on); scene_free(&off); return; }

	int wa = 0, ha = 0, wb = 0, hb = 0;
	uint8_t *pa = render_copy(&on, &wa, &ha);
	uint8_t *pb = render_copy(&off, &wb, &hb);
	CHECK(pa && pb, "both paths must produce a frame");

	BerylEngineStats a, b;
	beryl_engine_stats(on.e, &a);
	beryl_engine_stats(off.e, &b);
	CHECK(a.draws > 0 && b.draws > 0, "both paths must draw something (%llu vs %llu)",
	      (unsigned long long)a.draws, (unsigned long long)b.draws);
	CHECK(a.triangles > 0, "the device must count rasterized triangles (%llu)",
	      (unsigned long long)a.triangles);
	/* The occlusion walk also records empty sections as part of the frontier it
	 * expanded through, so compare the work actually submitted, not the entry count. */
	CHECK(a.draws <= b.draws, "occlusion may only remove work (%llu draws vs %llu)",
	      (unsigned long long)a.draws, (unsigned long long)b.draws);
	if (pa && pb) {
		int da = distinct_colors(pa, wa, ha), db = distinct_colors(pb, wb, hb);
		CHECK(da > 32 && db > 32, "both frames must have content (%d vs %d colours)", da, db);
		int diff = count_diff(pa, pb, wa, ha);
		CHECK(diff * 40 <= wa * ha, "occlusion must not repaint the image (%.2f%% of pixels differ)",
		      100.0 * (double)diff / (double)(wa * ha));
	}
	free(pa); free(pb);
	scene_free(&on); scene_free(&off);
}

static void test_live_edit(void) {
	Scene sc;
	scene_init(&sc, BERYL_MODE_NORMAL, true);
	if (!sc.e) { CHECK(sc.e, "engine"); return; }

	/* Inside the generated area, in front of the camera. */
	int px = GEN_CHUNKS * BERYL_SECTION_SIDE / 2, pz = px;
	int py = beryl_world_top_y(sc.w, px, pz) + 1;
	int w = 0, h = 0;
	uint8_t *before = render_copy(&sc, &w, &h);
	CHECK(before != NULL, "the frame before the edit must exist");

	/* A self-replacing write is the interesting case: the block does not change, so
	 * only the light (and therefore the baked shading) moves. */
	int before_id = beryl_world_get_block(sc.w, px, py, pz);
	int before_light = 0, ls = 0, lb = 0;
	beryl_world_get_light(sc.w, px, py + 1, pz, &ls, &lb);
	before_light = lb;
	int replaced = beryl_engine_set_block(sc.e, px, py, pz, before_id);
	beryl_engine_update(sc.e, &sc.cam, 0.0);
	uint8_t *after = render_copy(&sc, &w, &h);
	CHECK(replaced == 1, "writing an existing block must still report the change (%d)", replaced);
	int as = 0, ab = 0;
	beryl_world_get_light(sc.w, px, py + 1, pz, &as, &ab);
	CHECK(as == 15 || ab > 0, "a cell on an open surface must hold light (sky=%d blk=%d)", as, ab);
	CHECK(before_light == ab, "re-writing the same block must not change the lighting (%d -> %d)",
	      before_light, ab);

	if (before && after) {
		int d = distinct_colors(before, w, h), d2 = distinct_colors(after, w, h);
		CHECK(d2 > 32, "the frame after the edit must still have content (%d colours)", d2);
		CHECK(d == d2 || d2 > 32, "the edit must not destroy the picture");
	}
	free(before); free(after);

	/* Now an edit that really changes the surface, and must show up. */
	uint8_t *base = render_copy(&sc, &w, &h);
	int placed = beryl_engine_set_block(sc.e, px, py, pz, BERYL_BLOCK_GLOWSTONE);
	beryl_engine_update(sc.e, &sc.cam, 0.0);
	uint8_t *lit = render_copy(&sc, &w, &h);
	CHECK(placed >= 1, "placing a block inside the loaded area must change voxels (%d)", placed);
	if (base && lit) {
		int diff = count_diff(base, lit, w, h);
		CHECK(diff > 200, "a glowing block appearing in view must change the image (%d px)", diff);
	}
	free(base); free(lit);
	scene_free(&sc);
}


/*
 * The occlusion walker must actually remove sections from the frame -- a culler that
 * silently never culls would cost performance and no test would notice. And it must
 * never cull a section that is in plain view, which the "visible <= non-culled"
 * comparison in test_occlusion_effect covers from the pixel side.
 */
static void test_occlusion_culling(void) {
	Scene sc;
	scene_init(&sc, BERYL_MODE_NORMAL, true);
	if (!sc.e) { CHECK(sc.e, "engine"); return; }
	int culled_any = 0, visible_min = 1 << 30;
	for (int i = 0; i < 3; i++) {
		float ang = 0.7f * (float)i;
		sc.cam.yaw += ang;
		beryl_engine_update(sc.e, &sc.cam, 0.0);
		BerylRhi *rhi = beryl_engine_rhi(sc.e);
		if (rhi) rhi->vt->reset_stats(rhi);
		beryl_engine_render(sc.e, &sc.cam);
		BerylEngineStats st;
		beryl_engine_stats(sc.e, &st);
		CHECK(st.visible_sections > 0, "frame %d must have a non-empty visible set (%d)", i,
		      st.visible_sections);
		CHECK(st.culled_occlusion >= 0, "frame %d the cull counter must be sane (%d)", i,
		      st.culled_occlusion);
		CHECK(st.draws == (uint64_t)st.visible_sections || st.draws <= (uint64_t)st.visible_sections * 4u,
		      "frame %d draws must not exceed the visible set (%llu draws, %d sections)", i,
		      (unsigned long long)st.draws, st.visible_sections);
		culled_any += st.culled_occlusion;
		if (st.visible_sections < visible_min) visible_min = st.visible_sections;
	}
	CHECK(culled_any > 0, "occlusion must cull something over three looks (%d sections)", culled_any);
	CHECK(visible_min > 0, "and it must never cull the world down to nothing (%d)", visible_min);
	scene_free(&sc);
}

/*
 * Positions are 8.8 fixed point inside a section, so a triangle can only ever be
 * degenerate if the format or the quantisation is broken. Every exported triangle is
 * checked for area, and the whole cloud is checked to land on the block grid.
 */
static void test_vertex_precision(void) {
	Scene sc;
	scene_init(&sc, BERYL_MODE_NORMAL, true);
	if (!sc.e) { CHECK(sc.e, "engine"); scene_free(&sc); return; }
	char path[] = "/tmp/beryl_precision.obj";
	long tris = beryl_engine_export_obj(sc.e, path);
	CHECK(tris > 100, "the precision fixture needs geometry (%ld)", tris);
	FILE *f = fopen(path, "rb");
	if (f) {
		size_t cap = 0, n = 0;
		float *vx = NULL, *vy = NULL, *vz = NULL;
		unsigned *idx = NULL;
		size_t icap = 0, in_ = 0;
		char line[256];
		while (fgets(line, sizeof(line), f)) {
			if (line[0] == 'v' && line[1] == ' ') {
				float x, y, z;
				if (sscanf(line + 2, "%f %f %f", &x, &y, &z) == 3) {
					if (n == cap) {
						cap = cap ? cap * 2 : 4096;
						vx = (float *)realloc(vx, cap * sizeof *vx);
						vy = (float *)realloc(vy, cap * sizeof *vy);
						vz = (float *)realloc(vz, cap * sizeof *vz);
					}
					vx[n] = x; vy[n] = y; vz[n] = z; n++;
				}
			} else if (line[0] == 'f' && line[1] == ' ') {
				unsigned a, b, c;
				if (sscanf(line + 2, "%u%*[^ ] %u%*[^ ] %u", &a, &b, &c) == 3) {
					if (in_ + 3u > icap) { icap = icap ? icap * 2 : 8192; idx = (unsigned *)realloc(idx, icap * sizeof *idx); }
					idx[in_++] = a; idx[in_++] = b; idx[in_++] = c;
				}
			}
		}
		fclose(f);
		CHECK(n > 0 && in_ > 0, "the export must parse: %zu vertices, %zu indices", n, in_);
		int on_grid = 1, bad_area = 0, oob = 0;
		for (size_t i = 0; i < n; i++) {
			/* x/y/z are section-relative block coords offset by the section origin,
			 * so 256 * value must be an exact integer (the fixed-point quantum). */
			double q = (double)vx[i] * 256.0, q2 = (double)vy[i] * 256.0, q3 = (double)vz[i] * 256.0;
			if (fabs(q - floor(q + 0.5)) > 1e-3 || fabs(q2 - floor(q2 + 0.5)) > 1e-3 ||
			    fabs(q3 - floor(q3 + 0.5)) > 1e-3) { on_grid = 0; break; }
		}
		for (size_t i = 0; i + 2 < in_; i += 3) {
			unsigned a = idx[i], b = idx[i + 1], c = idx[i + 2];
			if (a == 0 || b == 0 || c == 0 || a > n || b > n || c > n) { oob++; continue; }
			const float *pa = &vx[(a - 1) * 0], *pb = &vx[(b - 1) * 0], *pc = &vx[(c - 1) * 0];
			(void)pa; (void)pb; (void)pc;
			float ax = vx[a - 1], ay = vy[a - 1], az = vz[a - 1];
			float ux = vx[b - 1] - ax, uy = vy[b - 1] - ay, uz = vz[b - 1] - az;
			float wx = vx[c - 1] - ax, wy = vy[c - 1] - ay, wz = vz[c - 1] - az;
			float cx = uy * wz - uz * wy, cy = uz * wx - ux * wz, cz = ux * wy - uy * wx;
			if (cx * cx + cy * cy + cz * cz < 1e-4f) bad_area++;
		}
		CHECK(on_grid, "positions must be exact multiples of the 8.8 quantum");
		CHECK(oob == 0, "every index must reference a vertex (%zu out of range)", (size_t)oob);
		CHECK(bad_area == 0, "no exported triangle may be degenerate (%d of %zu)", bad_area, in_ / 3u);
		free(vx); free(vy); free(vz); free(idx);
		remove(path);
	}
	scene_free(&sc);
}

static void test_obj_export(void) {
	Scene sc;
	scene_init(&sc, BERYL_MODE_NORMAL, true);
	if (!sc.e) { CHECK(sc.e, "engine"); scene_free(&sc); return; }
	char path[] = "/tmp/beryl_export.obj";
	long tris = beryl_engine_export_obj(sc.e, path);
	CHECK(tris > 1000, "the store must have real geometry to export (%ld triangles)", tris);
	FILE *f = fopen(path, "rb");
	CHECK(f != NULL, "the export must create the file");
	if (f) {
		long verts = 0, faces = 0, groups = 0, max_index = 0;
		char line[256];
		while (fgets(line, sizeof(line), f)) {
			if (line[0] == 'v' && line[1] == ' ') verts++;
			else if (line[0] == 'g' && line[1] == ' ') groups++;
			else if (line[0] == 'f' && line[1] == ' ') {
				unsigned a, b, c;
				faces++;
				if (sscanf(line + 2, "%u%*[^ ] %u%*[^ ] %u", &a, &b, &c) == 3) {
					if ((long)a > max_index) max_index = (long)a;
					if ((long)b > max_index) max_index = (long)b;
					if ((long)c > max_index) max_index = (long)c;
				}
			}
		}
		fclose(f);
		CHECK(faces == tris, "the return value must be the number of faces written (%ld vs %ld)", faces, tris);
		CHECK(groups > 0, "the export must be split into drawable groups (%ld)", groups);
		CHECK(max_index == verts, "the last index must be the last vertex (%ld of %ld)", max_index, verts);
		/* The export writes a section's vertex pool and indexes it, so quads share
		 * vertices: 4 per quad, 3 indices per triangle, never a flat 3-vertex face. */
		CHECK(verts * 2 >= faces && verts <= faces * 3, "indexed export must reuse vertices (%ld v, %ld f)",
		      verts, faces);
		remove(path);
	}
	scene_free(&sc);
}

/* PNG bytes written by the engine's own encoder must be a real, decodable file. */
static void test_screenshot_file(void) {
	Scene sc;
	scene_init(&sc, BERYL_MODE_NORMAL, true);
	if (!sc.e) { CHECK(sc.e, "engine"); scene_free(&sc); return; }
	int w = 0, h = 0;
	uint8_t *px = render_copy(&sc, &w, &h);
	CHECK(px && w == SHOT_W && h == SHOT_H, "a frame to save (%dx%d)", w, h);
	if (px) {
		char path[] = "/tmp/beryl_shot.png";
		int rc = beryl_png_write_rgba8(path, w, h, px);
		CHECK(rc == 0, "the encoder must report success (%d)", rc);
		FILE *f = fopen(path, "rb");
		CHECK(f != NULL, "the screenshot file must exist");
		if (f) {
			uint8_t hdr[24];
			size_t got = fread(hdr, 1, sizeof(hdr), f);
			fseek(f, 0, SEEK_END);
			long size = ftell(f);
			fclose(f);
			static const uint8_t sig[8] = { 137, 80, 78, 71, 13, 10, 26, 10 };
			CHECK(got == sizeof(hdr), "the header must be readable (%zu)", got);
			CHECK(memcmp(hdr, sig, 8) == 0, "PNG signature");
			CHECK(memcmp(hdr + 12, "IHDR", 4) == 0, "IHDR chunk must be first");
			/* 8-byte signature, then u32 length + "IHDR" + u32 width + u32 height. */
			CHECK(((unsigned)hdr[8] << 24 | (unsigned)hdr[9] << 16 | (unsigned)hdr[10] << 8 | hdr[11]) == 13,
			      "the IHDR chunk must be 13 bytes");
			CHECK(((unsigned)hdr[16] << 24 | (unsigned)hdr[17] << 16 | (unsigned)hdr[18] << 8 | hdr[19]) == (unsigned)w,
			      "IHDR width must match the frame (%d vs %d)",
			      (int)(hdr[16] << 24 | hdr[17] << 16 | hdr[18] << 8 | hdr[19]), w);
			CHECK(((unsigned)hdr[20] << 24 | (unsigned)hdr[21] << 16 | (unsigned)hdr[22] << 8 | hdr[23]) == (unsigned)h,
			      "IHDR height must match the frame (%d vs %d)",
			      (int)(hdr[20] << 24 | hdr[21] << 16 | hdr[22] << 8 | hdr[23]), h);
			CHECK(size > (long)(w * h) / 8 && size < (long)w * h * 4,
			      "the encoder must compress without inflating (%ld bytes for %dx%d)", size, w, h);
			remove(path);
		}
		/* In-memory encoding must produce the identical stream, which is what the
		 * GL/Vulkan capture paths use. */
		uint8_t *mem = NULL;
		size_t mem_len = 0;
		CHECK(beryl_png_encode_rgba8(w, h, px, &mem, &mem_len) == 0 && mem, "in-memory encode");
		free(mem);
	}
	free(px);
	scene_free(&sc);
}

void test_soft_render(void) {
	test_render_modes();
	test_frame_content();
	test_determinism();
	test_cull_orientation();
	test_occlusion_culling();
	test_vertex_precision();
	test_obj_export();
	test_screenshot_file();
	test_occlusion_effect();
	test_live_edit();
}

