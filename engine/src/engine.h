/* engine.h -- the front end: world + builder pool + cullers + RHI, one object.
 *
 * This is the layer a game or a launcher talks to, and the layer where the
 * frame-time policies live:
 *   - loader budget (chunks generated per frame),
 *   - rebuild budget (sections meshed per frame, prioritized),
 *   - upload budget (bytes uploaded per frame),
 *   - draw ordering (opaque front-to-back for early-z, blends back-to-front).
 * Every one of those is bounded per frame on purpose: a single frame that tries
 * to catch up on everything is what makes a mobile device stutter, and Beryllium
 * exists because stutter is worse than a chunk appearing two frames later.
 */
#ifndef BERYL_ENGINE_H
#define BERYL_ENGINE_H

/* Guard against an absurd --view-distance turning the per-frame chunk walk into
 * a multi-second scan: sections past the far plane are never drawn anyway. A
 * chunk column is one section wide, so the chunk radius equals the section
 * radius; this is the cap on that number. */
#define BERYL_VIEW_RADIUS_CHUNK_CAP 48

#include "occlusion.h"
#include "pool.h"
#include "rhi.h"
#include "world.h"

typedef struct BerylSettings {
	int   width, height;
	BerylBackend backend;
	int   view_distance_sections;      /* radius in sections (16 blocks) */
	int   builder_threads;             /* 0 = auto                       */
	int   chunks_per_frame;            /* loader budget                  */
	int   rebuilds_per_frame;          /* how many finished meshes to upload */
	int   uploads_per_frame_bytes;     /* soft cap, 0 = unlimited        */
	bool  occlusion_culling;
	bool  leaves_internal_cull;        /* Beryllium feature, via block flags */
	int   render_mode;                 /* BERYL_MODE_*                   */
	float fov_degrees;
	float day_factor;                  /* 0 = night, 1 = noon            */
	float max_fps;                     /* 0 = uncapped                   */
	int   anisotropic;                 /* texture filter hint            */
	bool  linear_filter;
	const char *log_prefix;
} BerylSettings;

void beryl_settings_default(BerylSettings *s, int width, int height, BerylBackend backend);

typedef struct BerylEngineStats {
	double frame_ms, mesh_ms, upload_ms, cull_ms, draw_ms;
	int    chunks, sections, non_empty_sections;
	int    queued_jobs, inflight_jobs, built_sections;
	int    visible_sections, culled_occlusion, culled_frustum;
	uint64_t draws, triangles;
	size_t   vertices, indices, uploaded_bytes;
	int64_t  total_quads;
	double   merge_ratio;
	float    fps;
	double   frame_seconds;
} BerylEngineStats;

typedef struct BerylEngine BerylEngine;

BerylEngine *beryl_engine_create(const BerylSettings *settings, const BerylWorldDesc *world_desc);
void         beryl_engine_destroy(BerylEngine *e);

BerylWorld  *beryl_engine_world(BerylEngine *e);
BerylRhi    *beryl_engine_rhi(BerylEngine *e);
void         beryl_engine_settings(BerylEngine *e, BerylSettings *out);
void         beryl_engine_set_settings(BerylEngine *e, const BerylSettings *s);
void         beryl_engine_resize(BerylEngine *e, int width, int height);

/* Loads terrain around the camera, services the builder pool and uploads meshes.
 * `dt` is seconds; used for the frame budget and the day cycle animation. */
void beryl_engine_update(BerylEngine *e, BerylCamera *cam, double dt);

/* One full frame: visible set + draws. Returns the number of draw calls. */
int  beryl_engine_render(BerylEngine *e, BerylCamera *cam);

/* Convenience: update + render + return the CPU image (software backend, or a
 * GL readback). Pixels are RGBA8, row-major top-down. Valid until next call. */
const uint8_t *beryl_engine_frame(BerylEngine *e, BerylCamera *cam, double dt, int *w, int *h);

void beryl_engine_stats(BerylEngine *e, BerylEngineStats *out);
/* Writes the stats + counters as a short human-readable block (overlay/telemetry). */
void beryl_engine_describe(BerylEngine *e, char *buf, size_t len);

/* Texture objects owned by the engine, exposed for tests and for hot-reload of
 * the lightmap (day cycle). */
BerylTexture beryl_engine_texarray(BerylEngine *e);
BerylTexture beryl_engine_lightmap(BerylEngine *e);
void         beryl_engine_refresh_lightmap(BerylEngine *e, float day_factor);

/* Editing: goes through the world, queues relight and rebuilds, and returns the
 * number of sections marked dirty (used by the mining demo and tests). */
int beryl_engine_set_block(BerylEngine *e, int x, int y, int z, beryl_bid id);

/* Wavefront export of every mesh currently held by the store, with vt
 * coordinates and a per-layer material group. This is how a mesh that nobody can
 * see on a GPU gets verified: any 3D tool opens it, and the test suite parses it
 * back to assert triangle counts and bounds. Returns the triangle count, or -1. */
int beryl_engine_export_obj(BerylEngine *e, const char *path);

/* Meshes every dirty section synchronously (single pass, no pool): used by batch
 * rendering, tests and the export path so the result does not depend on thread
 * scheduling. */
int beryl_engine_build_all(BerylEngine *e, const BerylCamera *cam, int max_sections);

/* Blocking "catch up completely" for screenshots and image tests: installs all
 * pending worker results, meshes anything still dirty, flushes uploads. Never
 * call it from a real-time frame loop. */
int beryl_engine_prepare_capture(BerylEngine *e, BerylCamera *cam);

#endif /* BERYL_ENGINE_H */
