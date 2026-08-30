/* main.c -- the demo front end: a Minecraft-shaped slice of the engine.
 *
 * Headless by design. Every mode is scriptable (camera, frames, screenshots,
 * export), which is what makes the renderer testable from CI without a display
 * server, and it is also the fastest way to eyeball a change: one command, one
 * PNG.
 */
#include "engine.h"
#include "light.h"
#include "png.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>

typedef struct AppOpts {
	BerylBackend backend;
	int  width, height;
	uint32_t seed;
	int  view_distance;
	int  frames;
	int  threads;
	int  chunks;          /* pre-generated NxN chunk grid */
	int  mode;
	int  fps_cap;
	bool orbit;
	bool linear;
	bool no_occlusion;
	bool leaves_cull;
	bool day_cycle;
	bool quiet;
	char screenshot[512];
	char obj[512];
	char dump_atlas[512];
	float cam[5];
	bool  cam_given;
	float fov;
	int   shot_at;         /* which frame to write as the screenshot (0 = last) */
} AppOpts;

static void usage(const char *argv0) {
	printf(
"%s -- " BERYL_ENGINE_NAME " " BERYL_ENGINE_VERSION " demo\n"
"\n"
"  --backend software|opengl|vulkan   renderer (default: software)\n"
"  --size WxH                         framebuffer size (default 960x540)\n"
"  --seed N                           world seed\n"
"  --view-distance N                  radius in sections (default 16)\n"
"  --frames N                         render N frames (default 1)\n"
"  --screenshot PATH                  write a PNG (default frame 1)\n"
"  --shot-at N                        which frame to write\n"
"  --orbit                            rotate the camera around the landmark\n"
"  --camera x,y,z,yaw,pitch           fixed camera (yaw/pitch in degrees)\n"
"  --fov N                            vertical field of view, degrees\n"
"  --generate N                       pre-generate an NxN chunk grid\n"
"  --threads N                        builder threads (0 = auto)\n"
"  --mode normal|lightmap|tint|wireframe|fog\n"
"  --linear                           linear texture filtering (default nearest)\n"
"  --no-occlusion                     frustum culling only (for A/B comparison)\n"
"  --day-cycle                        animate the sun over the frames\n"
"  --fps-cap N                        sleep to hold a frame rate\n"
"  --dump-atlas PATH                    write the generated texture array as a grid\n"
"  --obj PATH                         export the visible geometry as .obj\n"
"  --benchmark                        print timing + culling stats\n"
"  --backends                         probe which backends can start here, then exit\n"
"  --quiet                            only print the summary line\n", argv0);
}

static int parse_mode(const char *s) {
	if (!strcmp(s, "normal"))    return BERYL_MODE_NORMAL;
	if (!strcmp(s, "lightmap"))  return BERYL_MODE_LIGHTMAP;
	if (!strcmp(s, "tint"))      return BERYL_MODE_TINT;
	if (!strcmp(s, "wireframe")) return BERYL_MODE_WIREFRAME;
	if (!strcmp(s, "fog"))       return BERYL_MODE_FOG_NEAR;
	fprintf(stderr, "unknown --mode '%s'\n", s);
	exit(2);
}

static void opts_default(AppOpts *o) {
	memset(o, 0, sizeof(*o));
	o->backend = BERYL_BACKEND_SOFTWARE;
	o->width = 960; o->height = 540;
	o->seed = 20260829u;
	o->view_distance = 16;
	o->frames = 1;
	o->threads = 0;
	o->chunks = 6;
	o->mode = BERYL_MODE_NORMAL;
	o->fps_cap = 0;
	o->leaves_cull = true;
	o->fov = 70.0f;
	o->shot_at = 0;
	snprintf(o->screenshot, sizeof(o->screenshot), "frame.png");
}

static bool next_int(int *i, int argc, char **argv, int *out) {
	if (*i + 1 >= argc) return false;
	(*i)++;
	*out = atoi(argv[*i]);
	return true;
}

int main(int argc, char **argv) {
	AppOpts o;
	opts_default(&o);
	bool benchmark = false, backends_only = false;

	for (int i = 1; i < argc; i++) {
		const char *a = argv[i];
		if (!strcmp(a, "--backend")) {
			if (++i >= argc) { usage(argv[0]); return 2; }
			if      (!strcmp(argv[i], "software")) o.backend = BERYL_BACKEND_SOFTWARE;
			else if (!strcmp(argv[i], "opengl") || !strcmp(argv[i], "gl")) o.backend = BERYL_BACKEND_OPENGL;
			else if (!strcmp(argv[i], "vulkan") || !strcmp(argv[i], "vk")) o.backend = BERYL_BACKEND_VULKAN;
			else { fprintf(stderr, "unknown backend '%s'\n", argv[i]); return 2; }
		} else if (!strcmp(a, "--size") && i + 1 < argc) {
			int w = 0, h = 0;
			if (sscanf(argv[++i], "%dx%d", &w, &h) == 2 && w > 0 && h > 0) { o.width = w; o.height = h; }
		} else if (!strcmp(a, "--seed") && next_int(&i, argc, argv, (int *)&o.seed)) {
		} else if (!strcmp(a, "--view-distance")) { next_int(&i, argc, argv, &o.view_distance);
		} else if (!strcmp(a, "--frames"))     { next_int(&i, argc, argv, &o.frames);
		} else if (!strcmp(a, "--threads"))    { next_int(&i, argc, argv, &o.threads);
		} else if (!strcmp(a, "--generate"))   { next_int(&i, argc, argv, &o.chunks);
		} else if (!strcmp(a, "--mode"))       { if (++i < argc) o.mode = parse_mode(argv[i]);
		} else if (!strcmp(a, "--fov"))        { if (++i < argc) o.fov = (float)atof(argv[i]);
		} else if (!strcmp(a, "--fps-cap"))    { next_int(&i, argc, argv, &o.fps_cap);
		} else if (!strcmp(a, "--shot-at"))    { next_int(&i, argc, argv, &o.shot_at);
		} else if (!strcmp(a, "--screenshot") && i + 1 < argc) {
			snprintf(o.screenshot, sizeof(o.screenshot), "%s", argv[++i]);
		} else if (!strcmp(a, "--obj") && i + 1 < argc) {
			snprintf(o.obj, sizeof(o.obj), "%s", argv[++i]);
		} else if (!strcmp(a, "--dump-atlas") && i + 1 < argc) {
			snprintf(o.dump_atlas, sizeof(o.dump_atlas), "%s", argv[++i]);
		} else if (!strcmp(a, "--camera") && i + 1 < argc) {
			if (sscanf(argv[++i], "%f,%f,%f,%f,%f", &o.cam[0], &o.cam[1], &o.cam[2], &o.cam[3], &o.cam[4]) == 5) {
				o.cam_given = true;
			}
		} else if (!strcmp(a, "--orbit"))     { o.orbit = true;
		} else if (!strcmp(a, "--linear"))    { o.linear = true;
		} else if (!strcmp(a, "--day-cycle")) { o.day_cycle = true;
		} else if (!strcmp(a, "--no-occlusion")) { o.no_occlusion = true;
		} else if (!strcmp(a, "--leaves-cull"))  { o.leaves_cull = true;
		} else if (!strcmp(a, "--benchmark"))    { benchmark = true;
		} else if (!strcmp(a, "--quiet"))        { o.quiet = true;
		} else if (!strcmp(a, "--backends"))     { backends_only = true;
		} else if (!strcmp(a, "--help") || !strcmp(a, "-h")) { usage(argv[0]); return 0; }
		else { fprintf(stderr, "unknown argument '%s'\n", a); usage(argv[0]); return 2; }
	}

	if (backends_only) {
		printf("backend availability probe:\n");
		for (int b = 0; b < BERYL_BACKEND_COUNT; b++) {
			char why[256] = { 0 };
			bool ok = beryl_backend_available((BerylBackend)b, why, sizeof(why));
			printf("  %-9s : %s%s%s\n", beryl_backend_name((BerylBackend)b),
			       ok ? "available" : "unavailable",
			       ok ? "" : " -- ", ok ? "" : why);
		}
		return 0;
	}

	beryl_blocks_init();

	if (!beryl_backend_available(o.backend, NULL, 0)) {
		char why[256] = { 0 };
		beryl_backend_available(o.backend, why, sizeof(why));
		fprintf(stderr, "note: %s backend unavailable here (%s) -- using software\n",
		        beryl_backend_name(o.backend), why[0] ? why : "unsupported");
		o.backend = BERYL_BACKEND_SOFTWARE;
	}

	BerylSettings s;
	beryl_settings_default(&s, o.width, o.height, o.backend);
	s.view_distance_sections = o.view_distance;
	s.builder_threads = o.threads;
	s.render_mode = o.mode;
	s.linear_filter = o.linear;
	s.occlusion_culling = !o.no_occlusion;
	s.leaves_internal_cull = o.leaves_cull;
	s.fov_degrees = o.fov;
	s.chunks_per_frame = o.chunks * o.chunks > 32 ? 16 : 8;
	s.max_fps = (float)o.fps_cap;

	BerylWorldDesc wd;
	memset(&wd, 0, sizeof(wd));
	wd.seed = o.seed;
	wd.radius_sections = BERYL_MAX(o.chunks, 1) * BERYL_SECTION_SIDE;
	wd.caves = true;
	wd.trees = true;
	wd.water = true;
	wd.sea_level = 62.0f;

	BerylEngine *eng = beryl_engine_create(&s, &wd);
	BerylWorld *world = beryl_engine_world(eng);

	/* Pre-generate so frame 1 is a real picture rather than a loading screen, and
	 * so the benchmark measures steady-state cost. */
	double t_gen = beryl_time_ms();
	beryl_world_lock_write(world);
	int gen = beryl_world_generate_area(world, 0, 0, BERYL_MAX(o.chunks, 1) - 1, BERYL_MAX(o.chunks, 1) - 1);
	beryl_light_process_queue(world, 1 << 16);
	beryl_world_unlock_write(world);
	t_gen = beryl_time_ms() - t_gen;

	if (o.dump_atlas[0]) {
		uint8_t *tex = (uint8_t *)malloc(BERYL_ARRAY_BYTES);
		beryl_texarray_generate(tex, o.seed);
		int side = BERYL_TILE_SIZE * 8;                 /* 8x8 tiles of 16px, x8 zoom */
		uint8_t *big = (uint8_t *)malloc((size_t)side * side * 4);
		for (int y = 0; y < side; y++) {
			for (int x = 0; x < side; x++) {
				int tile = (y / (BERYL_TILE_SIZE * 8)) * 8 + x / (BERYL_TILE_SIZE * 8);
				int sx = (x % (BERYL_TILE_SIZE * 8)) / 8;
				int sy = (y % (BERYL_TILE_SIZE * 8)) / 8;
				const uint8_t *texpr = tex + (size_t)tile * BERYL_TEX_BYTES +
				                         ((size_t)sy * BERYL_TILE_SIZE + sx) * 4;
				memcpy(big + ((size_t)y * side + x) * 4, texpr, 4);
			}
		}
		beryl_png_write_rgba8(o.dump_atlas, side, side, big);
		printf("texture array -> %s (%d tiles, %dx%d, %.1f KiB)\n",
		       o.dump_atlas, BERYL_TILE_LAYERS, BERYL_TILE_SIZE, BERYL_TILE_SIZE,
		       (double)BERYL_ARRAY_BYTES / 1024.0);
		free(big);
		free(tex);
	}

	/* Camera: land on the surface unless asked otherwise. */
	BerylCamera cam;
	BerylVec3 landmark = beryl_vec3(0.0f, 0.0f, 0.0f);
	beryl_camera_init(&cam, o.width, o.height);
	if (o.cam_given) {
		cam.pos = beryl_vec3(o.cam[0], o.cam[1], o.cam[2]);
		cam.yaw = o.cam[3] * (3.14159265f / 180.0f);
		cam.pitch = o.cam[4] * (3.14159265f / 180.0f);
		beryl_camera_update(&cam);
	} else {
		/* A view worth screenshotting: pick the highest column in the generated
		 * area, step back from it towards the middle of the world and look down
		 * along that line. Auto-framing matters here because the generated area
		 * has a hard edge -- a camera at the centre of a plain shows mostly sky
		 * and the void beyond the border. */
		int n = BERYL_MAX(o.chunks, 1) * BERYL_SECTION_SIDE;
		int lx = n / 2, lz = n / 2, ltop = 0;
		for (int z = 8; z < n; z += 4) {
			for (int x = 8; x < n; x += 4) {
				int t = beryl_world_top_y(world, x, z);
				if (t > ltop) { ltop = t; lx = x; lz = z; }
			}
		}
		if (ltop <= 0) ltop = (int)wd.sea_level + 4;
		float to_cx = (float)n * 0.5f - (float)lx;
		float to_cz = (float)n * 0.5f - (float)lz;
		float len = sqrtf(to_cx * to_cx + to_cz * to_cz);
		if (len < 4.0f) { to_cx = 1.0f; to_cz = 1.0f; len = 1.41421356f; }
		const float back = 52.0f;
		cam.pos = beryl_vec3((float)lx + to_cx / len * back,
		                     (float)ltop + 20.0f,
		                     (float)lz + to_cz / len * back);
		/* Look at the landmark from just above it. */
		float dx = (float)lx - cam.pos.x, dy = (float)(ltop + 1) - cam.pos.y, dz = (float)lz - cam.pos.z;
		float hl = sqrtf(dx * dx + dz * dz);
		cam.yaw = atan2f(dz, dx);
		cam.pitch = -atan2f(-dy, hl > 1e-3f ? hl : 1e-3f);
		landmark = beryl_vec3((float)lx, (float)ltop, (float)lz);
		/* Match the projection to the view distance so the border dissolves into
		 * the fog instead of ending in a line. */
		cam.zfar = (float)BERYL_MAX(o.view_distance, 4) * (float)BERYL_SECTION_SIDE;
		cam.znear = 0.0625f;
		cam.fog_end = cam.zfar * 0.98f;
		cam.fog_start = cam.fog_end * 0.45f;
		beryl_camera_update(&cam);
	}
	/* Batch-mesh everything now: a real frame loop builds asynchronously through
	 * the pool, but a single screenshot must not depend on thread timing. */
	double t_mesh = beryl_time_ms();
	int built = beryl_engine_build_all(eng, &cam, 0);
	t_mesh = beryl_time_ms() - t_mesh;

	BerylVec3 orbit_target = o.cam_given ? cam.pos
	                                    : beryl_vec3(landmark.x, landmark.y + 4.0f, landmark.z);

	const int frames = BERYL_MAX(o.frames, 1);
	double t_render = 0.0;
	int draws = 0;
	const uint8_t *pixels = NULL;
	int pw = 0, ph = 0;
	uint8_t *shot = NULL;
	int shot_w = 0, shot_h = 0;

	for (int f = 0; f < BERYL_MAX(o.frames, 1); f++) {
		double t0 = beryl_time_ms();
		if (o.orbit) {
			float ang = 0.55f + (float)f * (6.2831853f / (float)BERYL_MAX(o.frames, 1));
			beryl_camera_orbit(&cam, orbit_target, 42.0f, ang, -0.22f);
		}
		if (o.day_cycle) {
			beryl_engine_refresh_lightmap(eng, 0.5f + 0.5f * cosf((float)f * 0.35f));
		}
		bool want_shot = (o.screenshot[0] &&
		                  ((o.shot_at > 0 && f + 1 == o.shot_at) ||
		                   (o.shot_at == 0 && f + 1 == frames)));
		beryl_engine_update(eng, &cam, 1.0 / 60.0);
		/* A captured frame must be complete, not "whatever the async upload
		 * budget happened to reach": catch up synchronously first. */
		if (want_shot) built += beryl_engine_prepare_capture(eng, &cam);
		draws = beryl_engine_render(eng, &cam);
		pixels = beryl_engine_rhi(eng) && beryl_engine_rhi(eng)->vt->readback
		         ? beryl_engine_rhi(eng)->vt->readback(beryl_engine_rhi(eng), &pw, &ph)
		         : NULL;
		if (want_shot && pixels && !shot) {
			size_t n = (size_t)pw * (size_t)ph * 4u;
			shot = (uint8_t *)malloc(n);
			if (shot) { memcpy(shot, pixels, n); shot_w = pw; shot_h = ph; }
		}
		t_render += beryl_time_ms() - t0;

		if (o.fps_cap > 0) {
			double target = 1000.0 / (double)o.fps_cap;
			double spent = beryl_time_ms() - t0;
			if (spent < target) {
				struct timespec ts = { 0, (long)((target - spent) * 1e6) };
				nanosleep(&ts, NULL);
			}
		}
	}

	BerylEngineStats st;
	beryl_engine_stats(eng, &st);
	BerylWorldStats ws;
	beryl_world_stats(world, &ws);

	if (!o.quiet) {
		char desc[1024];
		beryl_engine_describe(eng, desc, sizeof(desc));
		printf("%s\n", desc);
		printf("world: seed %u  chunks generated %d in %.1f ms  (grid %dx%d)\n",
		       o.seed, gen, t_gen, BERYL_MAX(o.chunks, 1), BERYL_MAX(o.chunks, 1));
		printf("       meshed %d sections in %.1f ms (%.1f us/section)\n",
		       built, t_mesh, built > 0 ? t_mesh * 1000.0 / built : 0.0);
		printf("       sections %d (non-empty %d)  quads %lld  culled face %lld  culled leaves %lld\n",
		       ws.sections, ws.non_empty_sections,
		       (long long)beryl_ctr_get(BERYL_CTR_QUADS),
		       (long long)beryl_ctr_get(BERYL_CTR_QUADS_CULLED_FACE),
		       (long long)beryl_ctr_get(BERYL_CTR_QUADS_CULLED_LEAVES));
		printf("draws %d   visible sections %d (occluded %d, frustum %d)\n",
		       draws, st.visible_sections, st.culled_occlusion, st.culled_frustum);
	}

	if (o.screenshot[0] && shot && shot_w > 0 && shot_h > 0) {
		if (beryl_png_write_rgba8(o.screenshot, shot_w, shot_h, shot) == 0) {
			if (!o.quiet) printf("screenshot -> %s (%dx%d)\n", o.screenshot, pw, ph);
		} else {
			fprintf(stderr, "could not write '%s'\n", o.screenshot);
		}
		free(shot);
	}

	if (o.obj[0]) {
		int tris = beryl_engine_export_obj(eng, o.obj);
		if (!o.quiet) printf("geometry -> %s (%d triangles)\n", o.obj, tris);
	}

	if (benchmark) {
		printf("timing: total %.1f ms over %d frame(s)  (avg %.2f ms/frame, %.1f fps)\n",
		       t_render, frames, t_render / frames, frames * 1000.0 / BERYL_MAX(t_render, 0.001));
		printf("        cull %.2f ms  mesh %.2f ms  draw %.2f ms\n",
		       st.cull_ms, st.mesh_ms, st.draw_ms);
		printf("meshing: %lld builds, %.2f ms total (%.1f us avg)\n",
		       (long long)0, 0.0, 0.0);
	}

	beryl_engine_destroy(eng);
	return 0;
}
