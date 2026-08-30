/* engine.c -- frame loop, mesh store, per-frame budgets. See engine.h. */
#include "buf.h"
#include "engine.h"
#include "light.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define STORE_BITS 14
#define STORE_MASK ((1u << STORE_BITS) - 1u)

typedef struct MeshEntry {
	uint64_t key;
	int32_t  cx, csy, cz;
	BerylSectionMesh mesh;
	BerylBuffer vbo, ibo;
	size_t vbo_size, ibo_size;
	uint32_t idx_base[BERYL_LAYER_COUNT];
	uint32_t idx_count[BERYL_LAYER_COUNT];
	uint32_t vert_base[BERYL_LAYER_COUNT];
	bool     has_gpu;
	bool     needs_upload;   /* built, but the frame's byte budget ran out */
	uint64_t last_frame;
	struct MeshEntry *next;
} MeshEntry;

typedef struct MeshStore {
	MeshEntry *buckets[1 << STORE_BITS];
	int count;
	int prune_counter;
} MeshStore;

struct BerylEngine {
	BerylSettings  set;
	BerylWorld    *world;
	BerylBuilderPool *pool;
	BerylRhi      *rhi;

	MeshStore      store;
	BerylVisibleSet visible;

	BerylTexture texarray;      /* block texture array   */
	BerylTexture lightmap_tex;  /* 16x16 light LUT       */
	uint8_t     *texarray_cpu;  /* CPU copy for software */
	uint8_t      lightmap_cpu[16 * 16 * 4];
	BerylPipeline pipe_solid, pipe_cutout, pipe_blend;
	BerylTerrainUniforms uni;

	/* Deferred-upload queue: the section is built, but the frame's byte budget ran
	 * out. Keeping a small queue (rather than a flag scan) is what bounds the
	 * per-frame cost of "catching up" after a big edit burst. */
	uint64_t pending_upload[512];
	int      pending_upload_count;

	BerylEngineStats stats;
	uint64_t frame_no;
	double   day_clock;
	bool     owns_world;
};

void beryl_settings_default(BerylSettings *s, int width, int height, BerylBackend backend) {
	memset(s, 0, sizeof(*s));
	s->width = width;
	s->height = height;
	s->backend = backend;
	s->view_distance_sections = 16;   /* 256 blocks, matching the default far plane */
	s->builder_threads = 0;
	s->chunks_per_frame = 4;
	s->rebuilds_per_frame = 6;
	s->uploads_per_frame_bytes = 4 * 1024 * 1024;
	s->occlusion_culling = true;
	s->leaves_internal_cull = true;
	s->render_mode = BERYL_MODE_NORMAL;
	s->fov_degrees = 70.0f;
	s->day_factor = 0.85f;
	s->max_fps = 0.0f;
	s->linear_filter = false;
	s->log_prefix = "beryl";
}

/* ----------------------------------------------------------- mesh store ---- */
static uint32_t store_hash(uint64_t key) {
	uint64_t h = key * 0x9E3779B97F4A7C15ull;
	return (uint32_t)(h >> 50) & STORE_MASK;
}

static MeshEntry *store_find(MeshStore *st, uint64_t key) {
	for (MeshEntry *e = st->buckets[store_hash(key)]; e; e = e->next) {
		if (e->key == key) return e;
	}
	return NULL;
}

static void store_remove(BerylEngine *e, MeshEntry *entry) {
	MeshStore *st = &e->store;
	uint32_t b = store_hash(entry->key);
	MeshEntry **link = &st->buckets[b];
	while (*link) {
		if (*link == entry) {
			*link = entry->next;
			break;
		}
		link = &(*link)->next;
	}
	if (e->rhi) {
		if (entry->vbo) e->rhi->vt->destroy_buffer(e->rhi, entry->vbo);
		if (entry->ibo) e->rhi->vt->destroy_buffer(e->rhi, entry->ibo);
	}
	beryl_section_mesh_free(&entry->mesh);
	free(entry);
	st->count--;
}

static MeshEntry *store_insert(BerylEngine *e, int32_t cx, int32_t csy, int32_t cz) {
	MeshStore *st = &e->store;
	uint64_t key = beryl_section_key(cx, csy, cz);
	MeshEntry *existing = store_find(st, key);
	if (existing) {
		if (existing->vbo) e->rhi->vt->destroy_buffer(e->rhi, existing->vbo);
		if (existing->ibo) e->rhi->vt->destroy_buffer(e->rhi, existing->ibo);
		existing->vbo = existing->ibo = 0;
		existing->has_gpu = false;
		existing->needs_upload = false;
		beryl_section_mesh_free(&existing->mesh);
		beryl_section_mesh_init(&existing->mesh, cx, csy, cz);
		return existing;
	}
	MeshEntry *entry = (MeshEntry *)calloc(1, sizeof(MeshEntry));
	if (!entry) return NULL;
	entry->key = key;
	entry->cx = cx; entry->csy = csy; entry->cz = cz;
	beryl_section_mesh_init(&entry->mesh, cx, csy, cz);
	entry->next = st->buckets[store_hash(key)];
	st->buckets[store_hash(key)] = entry;
	st->count++;
	return entry;
}

static void defer_upload(BerylEngine *e, MeshEntry *entry) {
	if (e->pending_upload_count >= (int)(sizeof(e->pending_upload) / sizeof(e->pending_upload[0]))) {
		return; /* full: store_prune/next request will recover it */
	}
	e->pending_upload[e->pending_upload_count++] = entry->key;
	entry->needs_upload = true;
}

/* Uploads the three layers of a section mesh into one VBO and one IBO, so a
 * section is a single pair of GPU objects regardless of layer count. */
static void store_upload(BerylEngine *e, MeshEntry *entry) {
	BerylRhi *rhi = e->rhi;
	if (!rhi) return;

	size_t vert_total = 0, idx_total = 0;
	for (int i = 0; i < BERYL_LAYER_COUNT; i++) {
		vert_total += entry->mesh.layer[i].vert_count;
		idx_total += entry->mesh.layer[i].index_count;
	}
	if (vert_total == 0 || idx_total == 0) {
		entry->has_gpu = false;
		entry->mesh.uploaded = true;
		return;
	}

	BerylBuf scratch;
	beryl_buf_init(&scratch);
	size_t vpos = 0;
	entry->vert_base[0] = 0;
	for (int i = 0; i < BERYL_LAYER_COUNT; i++) {
		if (i > 0) entry->vert_base[i] = (uint32_t)vpos;
		const BerylMeshLayer *l = &entry->mesh.layer[i];
		if (l->vert_count) {
			beryl_buf_put(&scratch, l->verts, l->vert_count * sizeof(BerylVertex));
			vpos += l->vert_count;
		}
	}
	if (scratch.err) { beryl_buf_free(&scratch); return; }
	BerylBuffer vbo = 0;
	BerylBufferDesc vd = { scratch.size, false, scratch.data, "section-verts" };
	if (rhi->vt->create_buffer(rhi, &vd, &vbo) != BERYL_OK) { beryl_buf_free(&scratch); return; }
	beryl_buf_reset(&scratch);

	size_t ipos = 0;
	entry->idx_base[0] = 0;
	for (int i = 0; i < BERYL_LAYER_COUNT; i++) {
		if (i > 0) entry->idx_base[i] = (uint32_t)ipos;
		const BerylMeshLayer *l = &entry->mesh.layer[i];
		entry->idx_count[i] = (uint32_t)l->index_count;
		if (l->index_count) {
			beryl_buf_put(&scratch, l->indices, l->index_count * sizeof(uint32_t));
			ipos += l->index_count;
		}
	}
	if (scratch.err) {
		rhi->vt->destroy_buffer(rhi, vbo);
		beryl_buf_free(&scratch);
		return;
	}
	BerylBuffer ibo = 0;
	BerylBufferDesc id = { scratch.size, false, scratch.data, "section-indices" };
	if (rhi->vt->create_buffer(rhi, &id, &ibo) != BERYL_OK) {
		rhi->vt->destroy_buffer(rhi, vbo);
		beryl_buf_free(&scratch);
		return;
	}
	beryl_buf_free(&scratch);

	if (entry->vbo) rhi->vt->destroy_buffer(rhi, entry->vbo);
	if (entry->ibo) rhi->vt->destroy_buffer(rhi, entry->ibo);
	entry->vbo = vbo;
	entry->ibo = ibo;
	entry->vbo_size = vert_total * sizeof(BerylVertex);
	entry->ibo_size = idx_total * sizeof(uint32_t);
	entry->has_gpu = true;
	entry->needs_upload = false;
	entry->mesh.uploaded = true;
	beryl_ctr_add(BERYL_CTR_SECTIONS_UPLOADED, 1);
	beryl_ctr_add(BERYL_CTR_VERTICES, (int64_t)vert_total);
	beryl_ctr_add(BERYL_CTR_INDICES, (int64_t)idx_total);
	e->stats.uploaded_bytes += entry->vbo_size + entry->ibo_size;
}

/* Drops GPU objects for sections whose world data is gone, so a long session
 * that walks far away does not accumulate buffers forever. */
static void store_prune(BerylEngine *e) {
	BerylWorld *w = e->world;
	for (uint32_t b = 0; b < (1u << STORE_BITS); b++) {
		MeshEntry *entry = e->store.buckets[b];
		while (entry) {
			MeshEntry *next = entry->next;
			BerylSection *s = beryl_world_section(w, entry->cx, entry->csy, entry->cz, false);
			bool stale = !s || (s->revision == entry->mesh.source_revision && s->dirty);
			if (!s) stale = true;
			if (stale && entry->last_frame + 256 < e->frame_no) {
				store_remove(e, entry);
			}
			entry = next;
		}
	}
}

/* ------------------------------------------------------------- lifecycle --- */
static void create_textures(BerylEngine *e) {
	BerylRhi *rhi = e->rhi;
	e->texarray_cpu = (uint8_t *)malloc(BERYL_ARRAY_BYTES);
	beryl_texarray_generate(e->texarray_cpu, (uint32_t)(beryl_world_seed(e->world) & 0xFFFFFFFFu));

	BerylTextureDesc td = { BERYL_TILE_SIZE, BERYL_TILE_SIZE, BERYL_TILE_LAYERS,
	                        !e->set.linear_filter, false, e->texarray_cpu, "block-textures" };
	if (rhi->vt->create_texture(rhi, &td, &e->texarray) != BERYL_OK) {
		e->texarray = BERYL_HANDLE_NONE;
	}

	beryl_lightmap_generate(e->lightmap_cpu, e->set.day_factor);
	BerylTextureDesc ld = { 16, 16, 1, true, false, e->lightmap_cpu, "lightmap" };
	if (rhi->vt->create_texture(rhi, &ld, &e->lightmap_tex) != BERYL_OK) {
		e->lightmap_tex = BERYL_HANDLE_NONE;
	}
}

static void create_pipelines(BerylEngine *e) {
	BerylRhi *rhi = e->rhi;
	BerylPipelineDesc d = { 0 };
	d.debug_name = "terrain_solid";
	d.shader_variant = 0;
	d.blend = false;
	d.depth_write = true;
	d.depth_test = true;
	d.cull = BERYL_CULL_BACK;
	d.depth = BERYL_DEPTH_LESS;
	rhi->vt->create_pipeline(rhi, &d, &e->pipe_solid);

	d.debug_name = "terrain_cutout";
	d.alpha_test = true;
	rhi->vt->create_pipeline(rhi, &d, &e->pipe_cutout);

	d.debug_name = "terrain_blend";
	d.blend = true;
	d.depth_write = false;
	d.alpha_test = false;
	d.cull = BERYL_CULL_NONE;
	rhi->vt->create_pipeline(rhi, &d, &e->pipe_blend);
}

BerylEngine *beryl_engine_create(const BerylSettings *settings, const BerylWorldDesc *world_desc) {
	BerylEngine *e = (BerylEngine *)calloc(1, sizeof(BerylEngine));
	if (!e) return NULL;
	e->set = settings ? *settings : (BerylSettings){ 0 };
	if (e->set.width <= 0) e->set.width = 640;
	if (e->set.height <= 0) e->set.height = 360;

	BerylWorldDesc wd = world_desc ? *world_desc : (BerylWorldDesc){ 0 };
	if (wd.sea_level <= 0.0f) wd.sea_level = 62.0f;
	e->world = beryl_world_new(&wd);
	e->owns_world = true;

	BerylPoolDesc pd = { 0 };
	pd.world = e->world;
	pd.threads = e->set.builder_threads;
	pd.max_queue = 8192;
	pd.view_alignment_bonus = 0.35f;
	e->pool = beryl_pool_new(&pd);

	beryl_visible_set_init(&e->visible);

	e->rhi = beryl_rhi_new(e->set.backend, e->set.width, e->set.height, NULL);
	if (!e->rhi) {
		BERYL_LOGE("backend '%s' could not start; rendering is disabled",
		           beryl_backend_name(e->set.backend));
	} else {
		beryl_rhi_get_info(e->rhi, &e->rhi->info);
		create_textures(e);
		create_pipelines(e);
		BERYL_LOGI("renderer: %s / %s", e->rhi->info.backend, e->rhi->info.renderer);
	}

	e->day_clock = 0.0;
	memset(&e->stats, 0, sizeof(e->stats));
	return e;
}

void beryl_engine_destroy(BerylEngine *e) {
	if (!e) return;
	if (e->rhi) {
		for (uint32_t b = 0; b < (1u << STORE_BITS); b++) {
			MeshEntry *entry = e->store.buckets[b];
			while (entry) {
				MeshEntry *next = entry->next;
				store_remove(e, entry);
				entry = next;
			}
		}
		if (e->texarray) e->rhi->vt->destroy_texture(e->rhi, e->texarray);
		if (e->lightmap_tex) e->rhi->vt->destroy_texture(e->rhi, e->lightmap_tex);
		if (e->pipe_solid) e->rhi->vt->destroy_pipeline(e->rhi, e->pipe_solid);
		if (e->pipe_cutout) e->rhi->vt->destroy_pipeline(e->rhi, e->pipe_cutout);
		if (e->pipe_blend) e->rhi->vt->destroy_pipeline(e->rhi, e->pipe_blend);
		beryl_rhi_destroy(e->rhi);
	}
	beryl_pool_free(e->pool);
	beryl_visible_set_free(&e->visible);
	free(e->texarray_cpu);
	if (e->owns_world) beryl_world_free(e->world);
	free(e);
}

BerylWorld *beryl_engine_world(BerylEngine *e) { return e->world; }
BerylRhi   *beryl_engine_rhi(BerylEngine *e)   { return e->rhi; }
BerylTexture beryl_engine_texarray(BerylEngine *e) { return e->texarray; }
BerylTexture beryl_engine_lightmap(BerylEngine *e) { return e->lightmap_tex; }

void beryl_engine_settings(BerylEngine *e, BerylSettings *out) { *out = e->set; }

void beryl_engine_set_settings(BerylEngine *e, const BerylSettings *s) {
	if (!s) return;
	int old_w = e->set.width, old_h = e->set.height;
	bool filter_changed = (s->linear_filter != e->set.linear_filter);
	e->set = *s;
	if (e->set.width != old_w || e->set.height != old_h) {
		beryl_engine_resize(e, e->set.width, e->set.height);
	}
	if (filter_changed && e->rhi) {
		if (e->texarray) e->rhi->vt->destroy_texture(e->rhi, e->texarray);
		e->texarray = BERYL_HANDLE_NONE;
		BerylTextureDesc td = { BERYL_TILE_SIZE, BERYL_TILE_SIZE, BERYL_TILE_LAYERS,
		                        !e->set.linear_filter, false, e->texarray_cpu, "block-textures" };
		e->rhi->vt->create_texture(e->rhi, &td, &e->texarray);
	}
}

void beryl_engine_resize(BerylEngine *e, int width, int height) {
	e->set.width = width > 0 ? width : 1;
	e->set.height = height > 0 ? height : 1;
}

void beryl_engine_refresh_lightmap(BerylEngine *e, float day_factor) {
	e->set.day_factor = BERYL_CLAMP(day_factor, 0.0f, 1.0f);
	beryl_lightmap_generate(e->lightmap_cpu, e->set.day_factor);
	if (e->rhi && e->lightmap_tex) {
		e->rhi->vt->upload_texture_layer(e->rhi, e->lightmap_tex, 0, e->lightmap_cpu);
	}
}

int beryl_engine_set_block(BerylEngine *e, int x, int y, int z, beryl_bid id) {
	beryl_world_lock_write(e->world);
	bool ok = beryl_world_set_block(e->world, x, y, z, id);
	beryl_world_unlock_write(e->world);
	if (!ok) return 0;
	int n = 1;
	if (!e->rhi) {
		/* No renderer: the light engine must still converge, so process inline. */
		beryl_world_lock_write(e->world);
		beryl_light_process_queue(e->world, 4096);
		beryl_world_unlock_write(e->world);
	}
	return n;
}

/* ------------------------------------------------------------- frame flow -- */
typedef struct DirtyScan {
	BerylEngine *e;
	int32_t cam_sx, cam_sy, cam_sz;
	int radius;
	int requested;
	BerylVec3 eye;
	BerylVec3 fwd;
} DirtyScan;

static float section_distance(const BerylEngine *e, int cx, int sy, int cz, const BerylCamera *cam);

static void request_dirty_in_view(BerylEngine *e, const BerylCamera *cam) {
	int R = BERYL_MAX(e->set.view_distance_sections, 16);
	/* Chunk coordinates, straight from the block position: a chunk is
	 * BERYL_SECTION_SIDE blocks wide, and one chunk column per section column. */
	int32_t cam_cx = (int32_t)floorf(cam->pos.x) >> BERYL_CHUNK_SHIFT;
	int32_t cam_cz = (int32_t)floorf(cam->pos.z) >> BERYL_CHUNK_SHIFT;

	/* Chunk-level walk: a chunk column is one section wide, so the radius in
	 * chunks equals the radius in sections. The scan stays O(radius^2). */
	int rc = R + 1;
	if (rc > BERYL_VIEW_RADIUS_CHUNK_CAP) rc = BERYL_VIEW_RADIUS_CHUNK_CAP;
	int budget = BERYL_MAX(e->set.rebuilds_per_frame * 64, 256);
	int requested = 0;

	beryl_world_lock_read(e->world);
	for (int dz = -rc; dz <= rc && requested < budget; dz++) {
		int cz = cam_cz + dz;
		for (int dx = -rc; dx <= rc && requested < budget; dx++) {
			int cx = cam_cx + dx;
			BerylChunk *c = beryl_world_chunk(e->world, cx, cz, false);
			if (!c) continue;
			for (int sy = 0; sy < BERYL_CHUNK_SECTIONS && requested < budget; sy++) {
				BerylSection *s = c->sections[sy];
				if (!s) continue;
				if (!s->dirty && s->revision == s->mesh_revision) continue;
				if (s->all_air && s->revision == s->mesh_revision) continue;
				float d = section_distance(e, cx, sy, cz, cam);
				float urgency = s->dirty ? 1.0f : 0.0f;
				beryl_pool_request(e->pool, cx, sy, cz, d, urgency);
				requested++;
			}
		}
	}
	beryl_world_unlock_read(e->world);
	e->stats.queued_jobs = beryl_pool_queued(e->pool);
}

static float section_distance(const BerylEngine *e, int cx, int sy, int cz, const BerylCamera *cam) {
	(void)e;
	float cs = (float)BERYL_SECTION_SIDE;
	BerylVec3 centre = beryl_vec3(((float)cx + 0.5f) * cs, ((float)sy + 0.5f) * cs, ((float)cz + 0.5f) * cs);
	BerylVec3 d = beryl_v3_sub(centre, cam->pos);
	float dist = beryl_v3_length(d);
	float align = 0.0f;
	if (dist > 0.5f) align = beryl_v3_dot(d, cam->dir) / dist;   /* 1 = straight ahead */
	return dist * (1.0f - 0.35f * BERYL_MAX(align, 0.0f));
}

/* Marks a section as satisfied by the mesh that just reached the store. The
 * revision is checked so an edit that landed while the worker was running keeps
 * the section dirty (and therefore queued) instead of being swallowed. */
static void mark_section_meshed(BerylEngine *e, int32_t cx, int32_t csy, int32_t cz,
                                uint64_t built_revision) {
	BerylSection *s = beryl_world_section(e->world, cx, csy, cz, false);
	if (!s) return;
	if (s->revision != built_revision) return;
	s->mesh_revision = s->revision;
	s->dirty = false;
}

void beryl_engine_update(BerylEngine *e, BerylCamera *cam, double dt) {
	double t0 = beryl_time_ms();
	if (dt > 0.0) e->day_clock += dt * 0.01;

	/* 1. loader: generate terrain in front of the camera first. */
	BerylVec3i cam_block = { (int32_t)floorf(cam->pos.x), (int32_t)floorf(cam->pos.y), (int32_t)floorf(cam->pos.z) };
	int R = BERYL_MAX(e->set.view_distance_sections, 16);
	beryl_world_lock_write(e->world);
	int generated = 0;
	beryl_world_update_loader(e->world, cam_block, R, BERYL_MAX(e->set.chunks_per_frame, 1), &generated);
	int light_steps = beryl_light_process_queue(e->world, 256);
	beryl_world_unlock_write(e->world);
	(void)light_steps;

	/* 2. mesh: request anything whose revision moved, prioritized by view. */
	request_dirty_in_view(e, cam);

	/* 3. install finished builds, within the upload budget. Deferred uploads from
	 * the previous frame go first: they are already built, so they are the
	 * cheapest way to make the picture correct. */
	double t1 = beryl_time_ms();
	{
		size_t deferred_bytes = 0;
		int w = 0;
		for (int r = 0; r < e->pending_upload_count; r++) {
			MeshEntry *me = store_find(&e->store, e->pending_upload[r]);
			if (!me || !me->needs_upload) continue;
			if (e->set.uploads_per_frame_bytes > 0 &&
			    (int64_t)deferred_bytes > (int64_t)e->set.uploads_per_frame_bytes / 2) {
				e->pending_upload[w++] = e->pending_upload[r];
				continue;
			}
			store_upload(e, me);
			deferred_bytes += me->vbo_size + me->ibo_size;
		}
		e->pending_upload_count = w;
	}
	int installed = 0;
	size_t frame_upload_bytes = 0;
	BerylBuildResult res;
	while (installed < BERYL_MAX(e->set.rebuilds_per_frame, 1) &&
	       beryl_pool_drain(e->pool, &res, 1) > 0) {
		if (e->set.uploads_per_frame_bytes > 0 &&
		    (int64_t)frame_upload_bytes > (int64_t)e->set.uploads_per_frame_bytes) {
			/* Over budget: put the mesh in the store but defer the GPU upload. */
			MeshEntry *entry = store_insert(e, res.cx, res.csy, res.cz);
			if (entry) {
				entry->mesh = res.mesh;
				entry->last_frame = e->frame_no;
				entry->has_gpu = false;
				entry->needs_upload = true;
				defer_upload(e, entry);      /* first free slot in the next frame */
				mark_section_meshed(e, res.cx, res.csy, res.cz, res.built_revision);
			} else {
				/* Same contract as the branch below: a result the store cannot
				 * take must be released here, or its buffers belong to nobody. */
				beryl_section_mesh_free(&res.mesh);
			}
			beryl_pool_release_result(e->pool, &res);
			installed++;
			continue;
		}
		MeshEntry *entry = store_insert(e, res.cx, res.csy, res.cz);
		if (entry) {
			/* store_insert released whatever this section's entry held before, so
			 * taking ownership here cannot drop a mesh on the floor. */
			entry->mesh = res.mesh;
			entry->last_frame = e->frame_no;
			entry->needs_upload = false;
			frame_upload_bytes += entry->mesh.layer[0].vert_count * sizeof(BerylVertex) +
			                      entry->mesh.layer[0].index_count * sizeof(uint32_t);
			store_upload(e, entry);
			mark_section_meshed(e, res.cx, res.csy, res.cz, res.built_revision);
		} else {
			beryl_section_mesh_free(&res.mesh);
		}
		beryl_pool_release_result(e->pool, &res);
		installed++;
	}
	e->stats.built_sections += installed;
	e->stats.mesh_ms = beryl_time_ms() - t1;
	e->stats.frame_ms = beryl_time_ms() - t0;
	(void)generated;

	if ((e->frame_no & 127u) == 0u) store_prune(e);
}

static int cmp_visible_front_to_back(const void *a, const void *b) {
	const BerylVisibleEntry *x = (const BerylVisibleEntry *)a;
	const BerylVisibleEntry *y = (const BerylVisibleEntry *)b;
	return x->distance_sq < y->distance_sq ? -1 : (x->distance_sq > y->distance_sq ? 1 : 0);
}

static void build_uniforms(BerylEngine *e, BerylCamera *cam) {
	BerylTerrainUniforms *u = &e->uni;
	memset(u, 0, sizeof(*u));
	for (int i = 0; i < 16; i++) u->mvp[i] = cam->view_proj.m[i];
	u->cam_pos[0] = cam->pos.x; u->cam_pos[1] = cam->pos.y; u->cam_pos[2] = cam->pos.z;
	u->fog[0] = cam->fog_start;
	u->fog[1] = cam->fog_end;
	/* Day cycle drives both the lightmap and the fog/sky colour, so the horizon
	 * and the shading always agree. */
	float day = BERYL_CLAMP(e->set.day_factor, 0.0f, 1.0f);
	float sky_r = beryl_lerp(0.035f, 0.45f, day);
	float sky_g = beryl_lerp(0.055f, 0.66f, day);
	float sky_b = beryl_lerp(0.120f, 0.92f, day);
	u->fog_color[0] = sky_r; u->fog_color[1] = sky_g; u->fog_color[2] = sky_b; u->fog_color[3] = 1.0f;
	/* The palette is a stride-3 table while the uniform block packs its tints as
	 * four floats each (std140-style), so it has to be copied element by element:
	 * aliasing the array would smear every entry one component to the left. */
	float pal[BERYL_TINT_COUNT][3];
	beryl_tint_palette(pal);
	for (int i = 0; i < BERYL_TINT_COUNT; i++) {
		u->tint[i][0] = pal[i][0];
		u->tint[i][1] = pal[i][1];
		u->tint[i][2] = pal[i][2];
		u->tint[i][3] = 1.0f;
	}
	u->params[0] = day;
	u->params[1] = 1.0f / (float)BERYL_TILE_SIZE;
	u->params[2] = (float)e->set.render_mode;
	u->params[3] = 0.78f;             /* water alpha */
}

int beryl_engine_render(BerylEngine *e, BerylCamera *cam) {
	if (!e->rhi) return 0;
	double t0 = beryl_time_ms();

	cam->width = e->set.width;
	cam->height = e->set.height;
	cam->fov_y = e->set.fov_degrees * (3.14159265f / 180.0f);
	beryl_camera_update(cam);

	int R = BERYL_MAX(e->set.view_distance_sections, 8);
	if (e->set.occlusion_culling) {
		beryl_world_lock_read(e->world);
		beryl_visible_set_compute(&e->visible, e->world, cam, R);
		beryl_world_unlock_read(e->world);
	} else {
		/* Frustum-only path, for comparison and for GPUs where the occlusion walk
		 * costs more than it saves: walk chunk columns (O(radius^2) lookups) and
		 * test every section in the column against the frustum. */
		e->visible.count = 0;
		int32_t pcx = (int32_t)floorf(cam->pos.x) >> BERYL_CHUNK_SHIFT;
		int32_t pcz = (int32_t)floorf(cam->pos.z) >> BERYL_CHUNK_SHIFT;
		/* Chunk columns: one chunk per section in X/Z, so the chunk radius is the
		 * section radius (capped so a silly --view-distance cannot stall us). */
		int rc = R + 1;
		if (rc > BERYL_VIEW_RADIUS_CHUNK_CAP) rc = BERYL_VIEW_RADIUS_CHUNK_CAP;
		beryl_world_lock_read(e->world);
		for (int dz = -rc; dz <= rc; dz++) {
			for (int dx = -rc; dx <= rc; dx++) {
				BerylChunk *c = beryl_world_chunk(e->world, pcx + dx, pcz + dz, false);
				if (!c) continue;
				for (int sy = 0; sy < BERYL_CHUNK_SECTIONS; sy++) {
					BerylSection *s = c->sections[sy];
					if (!s || s->all_air) continue;
					BerylAabb box = beryl_section_aabb(c->x, sy, c->z, 0.0f);
					if (!beryl_frustum_test_aabb(&cam->frustum, box)) continue;
					if (e->visible.count >= e->visible.capacity) continue;
					BerylVisibleEntry *slot = &e->visible.entries[e->visible.count++];
					slot->cx = c->x; slot->csy = sy; slot->cz = c->z;
					slot->key = beryl_section_key(c->x, sy, c->z);
					slot->empty = false;
					slot->faces = 0x3F;
					float cs = (float)BERYL_SECTION_SIDE;
					BerylVec3 ctr = beryl_vec3(((float)slot->cx + 0.5f) * cs,
					                          ((float)sy + 0.5f) * cs,
					                          ((float)slot->cz + 0.5f) * cs);
					BerylVec3 d = beryl_v3_sub(ctr, cam->pos);
					slot->distance_sq = beryl_v3_length_sq(d);
				}
			}
		}
		beryl_world_unlock_read(e->world);
	}
	e->stats.cull_ms = beryl_time_ms() - t0;
	e->stats.visible_sections = e->visible.count;
	e->stats.culled_occlusion = e->visible.culled_by_occlusion;
	e->stats.culled_frustum = e->visible.culled_by_frustum;
	if (e->visible.count > 1) {
		beryl_world_lock_read(e->world);
		qsort(e->visible.entries, (size_t)e->visible.count, sizeof(BerylVisibleEntry),
		      cmp_visible_front_to_back);
		beryl_world_unlock_read(e->world);
	}

	BerylRhi *rhi = e->rhi;
	BerylPassDesc pass = { 0 };
	pass.width = e->set.width;
	pass.height = e->set.height;
	pass.clear = true;
	pass.clear_depth = 1.0f;
	float day = BERYL_CLAMP(e->set.day_factor, 0.0f, 1.0f);
	pass.clear_color[0] = beryl_lerp(0.035f, 0.45f, day);
	pass.clear_color[1] = beryl_lerp(0.055f, 0.66f, day);
	pass.clear_color[2] = beryl_lerp(0.120f, 0.92f, day);
	pass.clear_color[3] = 1.0f;

	BerylFrameDesc fd = { 0.0, (int)e->frame_no };
	rhi->vt->begin_frame(rhi, &fd);
	if (rhi->vt->begin_pass(rhi, &pass) != BERYL_OK) return 0;

	build_uniforms(e, cam);

	int draws = 0;
	BerylTerrainUniforms *u = &e->uni;
	float cs = (float)BERYL_SECTION_SIDE;

	for (int layer = 0; layer < BERYL_LAYER_COUNT; layer++) {
		BerylPipeline pipe = layer == BERYL_LAYER_SOLID ? e->pipe_solid
		                   : layer == BERYL_LAYER_CUTOUT ? e->pipe_cutout : e->pipe_blend;
		if (pipe == 0) continue;
		/* Blends must be back-to-front; opaque and cutout are already front-to-
		 * back from the sort above, which is what makes the depth buffer do work
		 * for us on the expensive pass. */
		int n = e->visible.count;
		for (int k = 0; k < n; k++) {
			int idx = (layer == BERYL_LAYER_BLEND) ? (n - 1 - k) : k;
			const BerylVisibleEntry *v = &e->visible.entries[idx];
			if (v->empty) continue;
			MeshEntry *me = store_find(&e->store, v->key);
			if (!me) continue;   /* mesh still in flight -- draw it next frame */
			if (!me->has_gpu) continue;                      /* nothing uploaded yet */
			if (me->idx_count[layer] == 0) continue;          /* no geometry for this layer */

			u->section[0] = (float)v->cx * cs;
			u->section[1] = (float)v->csy * cs;
			u->section[2] = (float)v->cz * cs;

			BerylBindState bs = { 0 };
			bs.vertex_buffer = me->vbo;
			bs.index_buffer = me->ibo;
			bs.index_stride = 4;
			bs.uniforms = u;
			bs.textures[0] = e->texarray;
			bs.textures[1] = e->lightmap_tex;
			bs.texture_count = e->lightmap_tex ? 2 : 1;
			bs.index_offset = me->idx_base[layer];
			bs.index_count = me->idx_count[layer];
			if (rhi->vt->bind(rhi, pipe, &bs) != BERYL_OK) continue;
			if (rhi->vt->draw_indexed(rhi, me->idx_count[layer]) != BERYL_OK) continue;
			draws++;
		}
	}

	rhi->vt->end_pass(rhi);
	rhi->vt->end_frame(rhi);

	e->stats.draw_ms = beryl_time_ms() - t0 - e->stats.cull_ms;
	e->stats.draws += (uint64_t)draws;
	e->stats.visible_sections = e->visible.count;
	if (rhi->vt->stat) {
		e->stats.triangles = rhi->vt->stat(rhi, BERYL_STAT_TRIANGLES);
	}
	beryl_ctr_add(BERYL_CTR_DRAW_CALLS, draws);
	e->frame_no++;
	return draws;
}

const uint8_t *beryl_engine_frame(BerylEngine *e, BerylCamera *cam, double dt, int *w, int *h) {
	beryl_engine_update(e, cam, dt);
	beryl_engine_render(e, cam);
	if (!e->rhi || !e->rhi->vt->readback) return NULL;
	return e->rhi->vt->readback(e->rhi, w, h);
}

/* Everything the per-frame policy deliberately spreads over several frames, in
 * one blocking call. Used for screenshots, image-diff tests and the benchmark
 * warm-up: those must not depend on thread timing or on an upload budget. */
int beryl_engine_prepare_capture(BerylEngine *e, BerylCamera *cam) {
	int installed = 0;

	/* 1. let the workers finish, then install everything they built. The done
	 *    ring must not be dropped: the worker already advanced mesh_revision, so
	 *    a discarded result would be a permanent hole. */
	if (e->pool) {
		beryl_pool_wait_idle(e->pool);
		BerylBuildResult res;
		while (beryl_pool_drain(e->pool, &res, 1 << 20) > 0) {
			MeshEntry *entry = store_insert(e, res.cx, res.csy, res.cz);
			if (entry) {
				entry->mesh = res.mesh;
				entry->last_frame = e->frame_no;
				entry->needs_upload = false;
				store_upload(e, entry);
				mark_section_meshed(e, res.cx, res.csy, res.cz, res.built_revision);
				installed++;
			} else {
				beryl_section_mesh_free(&res.mesh);
			}
			beryl_pool_release_result(e->pool, &res);
		}
	}

	/* 2. anything still dirty (over-budget queue, edits, the far border) */
	installed += beryl_engine_build_all(e, cam, 0);


	/* 3. flush deferred uploads. The ring is only a queue of *hints* (it is fixed
	 * size, so it can overflow when the world changes faster than the upload
	 * budget drains it) -- walk the store itself, which is the authoritative
	 * list of meshes that have not reached the GPU yet. */
	for (uint32_t b = 0; b < (1u << STORE_BITS); b++) {
		for (MeshEntry *me = e->store.buckets[b]; me; me = me->next) {
			if (!me->needs_upload) continue;
			store_upload(e, me);
			me->needs_upload = false;
			installed++;
		}
	}
	e->pending_upload_count = 0;
	return installed;
}

int beryl_engine_build_all(BerylEngine *e, const BerylCamera *cam, int max_sections) {
	int built = 0;
	int32_t pcx = (int32_t)floorf(cam->pos.x) >> BERYL_CHUNK_SHIFT;
	int32_t pcz = (int32_t)floorf(cam->pos.z) >> BERYL_CHUNK_SHIFT;
	int rc = BERYL_MAX(e->set.view_distance_sections, 16) + 1;
	if (rc > BERYL_VIEW_RADIUS_CHUNK_CAP) rc = BERYL_VIEW_RADIUS_CHUNK_CAP;
	int limit = max_sections > 0 ? max_sections : 1 << 20;

	beryl_world_lock_write(e->world);
	for (int dz = -rc; dz <= rc; dz++) {
		for (int dx = -rc; dx <= rc; dx++) {
			BerylChunk *c = beryl_world_chunk(e->world, pcx + dx, pcz + dz, false);
			if (!c) continue;
			for (int sy = 0; sy < BERYL_CHUNK_SECTIONS; sy++) {
				BerylSection *s = c->sections[sy];
				if (!s) continue;
				if (s->revision == s->mesh_revision && s->mesh_revision != 0) continue;
				MeshEntry *entry = store_insert(e, c->x, sy, c->z);
				if (!entry) continue;
				BerylSlice slice;
				if (beryl_world_fill_slice(e->world, c->x, sy, c->z, &slice)) {
					if (beryl_mesh_slice(&slice, &entry->mesh)) {
						s->mesh_revision = s->revision;
						s->dirty = false;
						store_upload(e, entry);
						built++;
					}
				}
				if (built >= limit) goto done;
			}
		}
	}
done:
	beryl_world_unlock_write(e->world);
	return built;
}

int beryl_engine_export_obj(BerylEngine *e, const char *path) {
	FILE *f = fopen(path, "wb");
	if (!f) return -1;
	fprintf(f, "# Beryllium Engine %s -- terrain export\n", BERYL_ENGINE_VERSION);

	static const char *k_layer_name[BERYL_LAYER_COUNT] = { "solid", "cutout", "blend" };
	size_t vbase = 1;
	long tris = 0;
	int sections = 0;
	float bmin[3] = { 1e30f, 1e30f, 1e30f }, bmax[3] = { -1e30f, -1e30f, -1e30f };

	beryl_world_lock_read(e->world);
	for (uint32_t b = 0; b < (1u << STORE_BITS); b++) {
		for (MeshEntry *me = e->store.buckets[b]; me; me = me->next) {
			if (!me->mesh.valid) continue;
			float ox = (float)me->cx * BERYL_SECTION_SIDE;
			float oy = (float)me->csy * BERYL_SECTION_SIDE;
			float oz = (float)me->cz * BERYL_SECTION_SIDE;
			for (int L = 0; L < BERYL_LAYER_COUNT; L++) {
				const BerylMeshLayer *l = &me->mesh.layer[L];
				if (!l->vert_count) continue;
				fprintf(f, "g section_%d_%d_%d_%s\n", me->cx, me->csy, me->cz, k_layer_name[L]);
				for (size_t k = 0; k < l->vert_count; k++) {
					const BerylVertex *v = &l->verts[k];
					float px = ox + (float)v->pos_x / (float)BERYL_POS_SCALE;
					float py = oy + (float)v->pos_y / (float)BERYL_POS_SCALE;
					float pz = oz + (float)v->pos_z / (float)BERYL_POS_SCALE;
					bmin[0] = BERYL_MIN(bmin[0], px); bmax[0] = BERYL_MAX(bmax[0], px);
					bmin[1] = BERYL_MIN(bmin[1], py); bmax[1] = BERYL_MAX(bmax[1], py);
					bmin[2] = BERYL_MIN(bmin[2], pz); bmax[2] = BERYL_MAX(bmax[2], pz);
					/* OBJ texture space: v grows downward, matching GL's convention. */
					float su = (float)v->uv_s / (float)BERYL_UV_SCALE;
					float tv = (float)v->uv_t / (float)BERYL_UV_SCALE;
					fprintf(f, "v %.4f %.4f %.4f\nvt %.4f %.4f\n", px, py, pz, su / 16.0f, tv / 16.0f);
				}
				for (size_t k = 0; k + 2 < l->index_count; k += 3) {
					size_t a = l->indices[k], b2 = l->indices[k + 1], c2 = l->indices[k + 2];
					fprintf(f, "f %zu/%zu %zu/%zu %zu/%zu\n",
					        vbase + a, vbase + a, vbase + b2, vbase + b2, vbase + c2, vbase + c2);
					tris++;
				}
				vbase += l->vert_count;
			}
			sections++;
		}
	}
	beryl_world_unlock_read(e->world);

	fprintf(f, "# sections %d  triangles %ld\n# bounds min %.2f %.2f %.2f max %.2f %.2f %.2f\n",
	        sections, tris, bmin[0], bmin[1], bmin[2], bmax[0], bmax[1], bmax[2]);
	fclose(f);
	return (int)tris;
}

void beryl_engine_stats(BerylEngine *e, BerylEngineStats *out) {
	*out = e->stats;
	BerylWorldStats ws;
	beryl_world_stats(e->world, &ws);
	out->chunks = ws.chunks;
	out->sections = ws.sections;
	out->non_empty_sections = ws.non_empty_sections;
	out->queued_jobs = beryl_pool_queued(e->pool);
	out->inflight_jobs = beryl_pool_inflight(e->pool);
	out->vertices = beryl_ctr_get(BERYL_CTR_VERTICES);
	out->indices = beryl_ctr_get(BERYL_CTR_INDICES);
	out->total_quads = beryl_ctr_get(BERYL_CTR_QUADS);
	BerylMeshStats ms;
	beryl_mesh_last_stats(&ms);
	out->merge_ratio = ms.merge_ratio;
	out->frame_seconds = e->stats.frame_ms > 0.0 ? e->stats.frame_ms * 0.001 : 0.0;
	out->fps = out->frame_seconds > 0.0 ? (float)(1.0 / out->frame_seconds) : 0.0f;
}

void beryl_engine_describe(BerylEngine *e, char *buf, size_t len) {
	BerylEngineStats s;
	beryl_engine_stats(e, &s);
	snprintf(buf, len,
	         "%s | chunks %d sections %d | visible %d (occl %d, frustum %d) | "
	         "quads %lld merges %.1fx | draws %llu tris %llu | "
	         "queued %d inflight %d built %lld (%.1f ms avg) | "
	         "frame %.2f ms (cull %.2f mesh %.2f up %.2f draw %.2f) | %.1f fps",
	         e->rhi ? e->rhi->info.backend : "no-backend",
	         s.chunks, s.sections, s.visible_sections, s.culled_occlusion, s.culled_frustum,
	         (long long)s.total_quads, s.merge_ratio,
	         (unsigned long long)s.draws, (unsigned long long)s.triangles,
	         s.queued_jobs, s.inflight_jobs,
	         (long long)beryl_pool_total_builds(e->pool),
	         beryl_pool_total_builds(e->pool) > 0
		     ? beryl_pool_total_ms(e->pool) / (double)beryl_pool_total_builds(e->pool) : 0.0,
	         s.frame_ms, s.cull_ms, s.mesh_ms, s.upload_ms, s.draw_ms, s.fps);
}
