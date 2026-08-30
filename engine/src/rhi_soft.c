/* rhi_soft.c -- the reference rasterizer.
 *
 * Same vertex format, same uniform block, same shading maths as the GLSL in
 * shaders/terrain.glsl; the output is a plain RGBA8 image in memory. It is a
 * real programmable pipeline in miniature: vertex transform, near-plane clipping,
 * perspective-correct interpolation, float depth buffer, alpha test, alpha
 * blend, and a wireframe debug mode.
 *
 * Why bother: it makes rendering testable without a GPU, it gives the GL and
 * Vulkan backends an oracle to be diffed against, and it is the reason this
 * engine can produce an image in a container with no driver at all -- which is
 * exactly where this engine was written.
 */
#include "rhi.h"
#include "blocks.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define SOFT_MAX_BUFFERS   2048
#define SOFT_MAX_TEXTURES   64
#define SOFT_MAX_PIPELINES  16

/* Interpolated attributes. Everything that is not a position rides in this
 * list; the order matches the varying block in shaders/terrain.glsl. */
enum {
	ATTR_UVS = 0, ATTR_UVT, ATTR_SKY, ATTR_BLK, ATTR_AO, ATTR_TINT,
	ATTR_TILE, ATTR_FOG, ATTR_CUTOUT, ATTR_BLEND, ATTR_FACE,
	ATTR_COUNT
};

typedef struct SoftVert {
	float clip[4];
	float attr[ATTR_COUNT];
} SoftVert;

typedef struct SoftBuf  { uint8_t *data; size_t size; bool used; }  SoftBuf;
typedef struct SoftTex  { uint8_t *data; int w, h, layers; bool nearest, used; } SoftTex;
typedef struct SoftPipe { BerylPipelineDesc desc; bool used; } SoftPipe;

typedef struct SoftDevice {
	int w, h;
	uint8_t *color;
	float   *depth;

	SoftBuf  bufs[SOFT_MAX_BUFFERS];
	SoftTex  texs[SOFT_MAX_TEXTURES];
	SoftPipe pipes[SOFT_MAX_PIPELINES];

	BerylPipeline bound_pipe;
	BerylBindState bound;
	const BerylVertex *verts;
	size_t vert_count;
	const uint32_t *idx;
	const uint32_t *idx_end;

	uint64_t stat_draw, stat_tri, stat_uploads, stat_bytes, stat_tex_uploads, stat_verts;
	int  frame_index;
	bool in_pass;
} SoftDevice;

/* ------------------------------------------------------------- objects ----- */
static BerylResult soft_create_buffer(BerylRhi *r, const BerylBufferDesc *d, BerylBuffer *out) {
	SoftDevice *s = (SoftDevice *)r->backend_state;
	for (uint32_t i = 0; i < SOFT_MAX_BUFFERS; i++) {
		if (s->bufs[i].used) continue;
		SoftBuf *b = &s->bufs[i];
		b->used = true;
		b->size = d->size;
		b->data = (uint8_t *)malloc(d->size ? d->size : 1);
		if (!b->data) { b->used = false; return BERYL_ERR_OOM; }
		if (d->initial) memcpy(b->data, d->initial, d->size);
		else            memset(b->data, 0, d->size);
		*out = i + 1;
		return BERYL_OK;
	}
	return BERYL_ERR_OOM;
}


static void soft_destroy_buffer(BerylRhi *r, BerylBuffer h) {
	SoftDevice *s = (SoftDevice *)r->backend_state;
	if (h == 0 || h > SOFT_MAX_BUFFERS) return;
	free(s->bufs[h - 1].data);
	memset(&s->bufs[h - 1], 0, sizeof(SoftBuf));
}

static BerylResult soft_upload_buffer(BerylRhi *r, BerylBuffer h, const void *src, size_t size, size_t offset) {
	SoftDevice *s = (SoftDevice *)r->backend_state;
	if (h == 0 || h > SOFT_MAX_BUFFERS) return BERYL_ERR_INVALID;
	SoftBuf *b = &s->bufs[h - 1];
	if (!b->used || offset + size > b->size) return BERYL_ERR_INVALID;
	memcpy(b->data + offset, src, size);
	s->stat_uploads++;
	s->stat_bytes += size;
	return BERYL_OK;
}

static BerylResult soft_create_texture(BerylRhi *r, const BerylTextureDesc *d, BerylTexture *out) {
	SoftDevice *s = (SoftDevice *)r->backend_state;
	for (uint32_t i = 0; i < SOFT_MAX_TEXTURES; i++) {
		if (s->texs[i].used) continue;
		SoftTex *t = &s->texs[i];
		memset(t, 0, sizeof(*t));
		t->used = true;
		t->w = d->width;
		t->h = d->height;
		t->layers = d->layers > 0 ? d->layers : 1;
		t->nearest = d->nearest;
		size_t bytes = (size_t)t->w * (size_t)t->h * 4u * (size_t)t->layers;
		t->data = (uint8_t *)calloc(1, bytes);
		if (!t->data) { memset(t, 0, sizeof(*t)); return BERYL_ERR_OOM; }
		if (d->initial) memcpy(t->data, d->initial, bytes);
		*out = i + 1;
		return BERYL_OK;
	}
	return BERYL_ERR_OOM;
}

static void soft_destroy_texture(BerylRhi *r, BerylTexture h) {
	SoftDevice *s = (SoftDevice *)r->backend_state;
	if (h == 0 || h > SOFT_MAX_TEXTURES) return;
	free(s->texs[h - 1].data);
	memset(&s->texs[h - 1], 0, sizeof(SoftTex));
}

static BerylResult soft_upload_texture_layer(BerylRhi *r, BerylTexture h, int layer, const void *px) {
	SoftDevice *s = (SoftDevice *)r->backend_state;
	if (h == 0 || h > SOFT_MAX_TEXTURES) return BERYL_ERR_INVALID;
	SoftTex *t = &s->texs[h - 1];
	if (!t->used || layer < 0 || layer >= t->layers) return BERYL_ERR_INVALID;
	memcpy(t->data + (size_t)layer * (size_t)t->w * (size_t)t->h * 4u, px,
	       (size_t)t->w * (size_t)t->h * 4u);
	s->stat_tex_uploads++;
	return BERYL_OK;
}

static BerylResult soft_create_pipeline(BerylRhi *r, const BerylPipelineDesc *d, BerylPipeline *out) {
	SoftDevice *s = (SoftDevice *)r->backend_state;
	for (uint32_t i = 0; i < SOFT_MAX_PIPELINES; i++) {
		if (s->pipes[i].used) continue;
		s->pipes[i].used = true;
		s->pipes[i].desc = *d;
		*out = i + 1;
		return BERYL_OK;
	}
	return BERYL_ERR_OOM;
}

static void soft_destroy_pipeline(BerylRhi *r, BerylPipeline h) {
	SoftDevice *s = (SoftDevice *)r->backend_state;
	if (h == 0 || h > SOFT_MAX_PIPELINES) return;
	s->pipes[h - 1].used = false;
}

/* -------------------------------------------------------------- passes ----- */
static void soft_resize(SoftDevice *s, BerylRhi *r, int w, int h) {
	if (s->w == w && s->h == h && s->color) return;
	free(s->color);
	free(s->depth);
	s->w = w; s->h = h;
	s->color = (uint8_t *)calloc((size_t)w * (size_t)h, 4);
	s->depth = (float *)malloc((size_t)w * (size_t)h * sizeof(float));
	r->width = w; r->height = h;
	r->readback = s->color;
	r->readback_size = (size_t)w * (size_t)h * 4u;
}

static BerylResult soft_begin_frame(BerylRhi *r, const BerylFrameDesc *f) {
	((SoftDevice *)r->backend_state)->frame_index = f ? f->frame_index : 0;
	return BERYL_OK;
}

static BerylResult soft_begin_pass(BerylRhi *r, const BerylPassDesc *p) {
	SoftDevice *s = (SoftDevice *)r->backend_state;
	soft_resize(s, r, p->width, p->height);
	if (!s->color || !s->depth) return BERYL_ERR_OOM;
	s->in_pass = true;
	if (p->clear) {
		uint8_t c[4];
		for (int i = 0; i < 4; i++) {
			float v = BERYL_CLAMP(p->clear_color[i], 0.0f, 1.0f);
			c[i] = (uint8_t)(v * 255.0f + 0.5f);
		}
		size_t n = (size_t)s->w * (size_t)s->h;
		for (size_t i = 0; i < n; i++) memcpy(s->color + i * 4, c, 4);
		for (size_t i = 0; i < n; i++) s->depth[i] = p->clear_depth;
	}
	return BERYL_OK;
}

static void soft_end_pass(BerylRhi *r) {
	((SoftDevice *)r->backend_state)->in_pass = false;
}

static BerylResult soft_bind(BerylRhi *r, BerylPipeline pipe, const BerylBindState *st) {
	SoftDevice *s = (SoftDevice *)r->backend_state;
	if (pipe == 0 || pipe > SOFT_MAX_PIPELINES || !s->pipes[pipe - 1].used) return BERYL_ERR_INVALID;
	if (st->vertex_buffer == 0 || st->vertex_buffer > SOFT_MAX_BUFFERS || !s->bufs[st->vertex_buffer - 1].used) {
		return BERYL_ERR_INVALID;
	}
	if (st->index_buffer == 0 || st->index_buffer > SOFT_MAX_BUFFERS || !s->bufs[st->index_buffer - 1].used) {
		return BERYL_ERR_INVALID;
	}
	s->bound_pipe = pipe;
	s->bound = *st;
	const SoftBuf *vb = &s->bufs[st->vertex_buffer - 1];
	const SoftBuf *ib = &s->bufs[st->index_buffer - 1];
	s->verts = (const BerylVertex *)vb->data;
	s->vert_count = vb->size / sizeof(BerylVertex);
	s->idx = (const uint32_t *)ib->data + st->index_offset;
	size_t avail = (ib->size / sizeof(uint32_t)) - st->index_offset;
	size_t want = st->index_count ? st->index_count : (size_t)avail;
	s->idx_end = s->idx + BERYL_MIN(want, avail);
	return BERYL_OK;
}

/* ---------------------------------------------------------- vertex stage --- */
static void shade_vertex(SoftDevice *s, const BerylVertex *v, const BerylTerrainUniforms *u, SoftVert *out) {
	BerylShadedVertex sv;
	beryl_terrain_vert(v, u, &sv);
	out->clip[0] = sv.clip[0];
	out->clip[1] = sv.clip[1];
	out->clip[2] = sv.clip[2];
	out->clip[3] = sv.clip[3];
	out->attr[ATTR_UVS] = sv.uv_s;
	out->attr[ATTR_UVT] = sv.uv_t;
	out->attr[ATTR_SKY] = sv.sky;
	out->attr[ATTR_BLK] = sv.blk;
	out->attr[ATTR_AO] = sv.ao;
	out->attr[ATTR_TINT] = sv.tint_index;
	out->attr[ATTR_TILE] = sv.tile;
	out->attr[ATTR_FOG] = sv.fog_depth;
	out->attr[ATTR_CUTOUT] = sv.cutout ? 1.0f : 0.0f;
	out->attr[ATTR_BLEND] = sv.blend ? 1.0f : 0.0f;
	out->attr[ATTR_FACE] = (float)sv.face;
	(void)s;
}

/* Clips a polygon against the near plane in clip space (w > epsilon). A
 * triangle becomes either a smaller triangle or a quad, hence the fan. */
#define SOFT_NEAR_EPS 1e-5f

static int clip_near(const SoftVert *in, int n, SoftVert *out, float eps) {
	int m = 0;
	for (int i = 0; i < n; i++) {
		const SoftVert *a = &in[i];
		const SoftVert *b = &in[(i + 1) % n];
		bool ain = a->clip[3] > eps;
		bool bin = b->clip[3] > eps;
		if (ain) {
			if (m < 8) out[m++] = *a;
			if (bin) continue;
		} else if (!bin) {
			continue;
		}
		/* crossing: interpolate to the plane */
		float t = (eps - a->clip[3]) / (b->clip[3] - a->clip[3]);
		if (m < 8) {
			SoftVert *o = &out[m++];
			for (int k = 0; k < 4; k++) o->clip[k] = a->clip[k] + (b->clip[k] - a->clip[k]) * t;
			for (int k = 0; k < ATTR_COUNT; k++) {
				o->attr[k] = a->attr[k] + (b->attr[k] - a->attr[k]) * t;
			}
		}
	}
	return m;
}

/* ---------------------------------------------------------------- raster --- */
typedef struct RastVert {
	float sx, sy, sz, iw;
	float a[ATTR_COUNT];   /* premultiplied by iw */
} RastVert;

static inline void shade_pixel(SoftDevice *s, const BerylPipelineDesc *pd, const BerylTerrainUniforms *u,
                               int x, int y, float z, const float *attr) {
	float *depth = s->depth + (size_t)y * (size_t)s->w + (size_t)x;
	if (z > *depth) return;

	BerylShadedVertex sv;
	memset(&sv, 0, sizeof(sv));
	sv.uv_s = attr[ATTR_UVS];
	sv.uv_t = attr[ATTR_UVT];
	sv.sky = attr[ATTR_SKY];
	sv.blk = attr[ATTR_BLK];
	sv.ao = attr[ATTR_AO];
	sv.tint_index = attr[ATTR_TINT];
	sv.tile = attr[ATTR_TILE];
	sv.fog_depth = attr[ATTR_FOG];
	sv.cutout = attr[ATTR_CUTOUT] > 0.5f;
	sv.blend = attr[ATTR_BLEND] > 0.5f;
	sv.face = (int)(attr[ATTR_FACE] + 0.5f);
	sv.clip[3] = 1.0f;

	const uint8_t *tex = NULL, *lightmap = NULL;
	int tex_size = 16, tex_layers = 1;
	if (s->bound.textures[0] && s->bound.textures[0] <= SOFT_MAX_TEXTURES) {
		SoftTex *t = &s->texs[s->bound.textures[0] - 1];
		if (t->used) { tex = t->data; tex_size = t->w; tex_layers = t->layers; }
	}
	if (s->bound.textures[1] && s->bound.textures[1] <= SOFT_MAX_TEXTURES) {
		SoftTex *t = &s->texs[s->bound.textures[1] - 1];
		if (t->used && t->w == 16 && t->h == 16) lightmap = t->data;
	}

	int mode = (int)u->params[2] & BERYL_MODE_MASK;
	if (mode == BERYL_MODE_WIREFRAME) {
		/* attr[ATTR_COUNT] carries min(1/l0,1/l1,1/l2) from the caller. */
		if (attr[ATTR_COUNT] > 60.0f) return;
		uint8_t *dst = s->color + ((size_t)y * (size_t)s->w + (size_t)x) * 4;
		dst[0] = 12; dst[1] = 235; dst[2] = 150; dst[3] = 255;
		*depth = z;
		return;
	}

	float rgba[4];
	beryl_terrain_frag(&sv, tex, tex_layers, tex_size, lightmap, u, rgba);
	if (rgba[3] <= 0.0f) return;

	uint8_t *dst = s->color + ((size_t)y * (size_t)s->w + (size_t)x) * 4;
	if (pd->blend) {
		float a = rgba[3];
		for (int i = 0; i < 3; i++) {
			float v = rgba[i] * a + (dst[i] / 255.0f) * (1.0f - a);
			dst[i] = (uint8_t)(BERYL_CLAMP(v, 0.0f, 1.0f) * 255.0f + 0.5f);
		}
		dst[3] = 255;
	} else {
		for (int i = 0; i < 3; i++) {
			dst[i] = (uint8_t)(BERYL_CLAMP(rgba[i], 0.0f, 1.0f) * 255.0f + 0.5f);
		}
		dst[3] = 255;
	}
	if (pd->depth_write) *depth = z;
}

static void raster_tri(SoftDevice *s, const BerylPipelineDesc *pd, const BerylTerrainUniforms *u,
                       const RastVert *v0, const RastVert *v1, const RastVert *v2) {
	/* Backface culling in screen space, after the viewport flip (NDC y up ->
	 * pixel y down) which negates the signed area: front faces end up with
	 * area > 0 under the (ex1*ey2 - ey1*ex2) form below. */
	float ex1 = v1->sx - v0->sx, ey1 = v1->sy - v0->sy;
	float ex2 = v2->sx - v0->sx, ey2 = v2->sy - v0->sy;
	float area = ex1 * ey2 - ey1 * ex2;
	if (!isfinite(area) || area == 0.0f) return;
	if (pd->cull == BERYL_CULL_BACK && area >= 0.0f) return;
	if (pd->cull == BERYL_CULL_FRONT && area <= 0.0f) return;

	float minx = BERYL_MIN(v0->sx, BERYL_MIN(v1->sx, v2->sx));
	float maxx = BERYL_MAX(v0->sx, BERYL_MAX(v1->sx, v2->sx));
	float miny = BERYL_MIN(v0->sy, BERYL_MIN(v1->sy, v2->sy));
	float maxy = BERYL_MAX(v0->sy, BERYL_MAX(v1->sy, v2->sy));
	if (maxx < 0.0f || maxy < 0.0f || minx > (float)s->w || miny > (float)s->h) return;

	int x0 = BERYL_MAX((int)floorf(minx), 0), x1 = BERYL_MIN((int)ceilf(maxx), s->w - 1);
	int y0 = BERYL_MAX((int)floorf(miny), 0), y1 = BERYL_MIN((int)ceilf(maxy), s->h - 1);
	if (x1 < x0 || y1 < y0) return;

	const float ax = v0->sx, ay = v0->sy;
	const float bx = v1->sx, by = v1->sy;
	const float cx = v2->sx, cy = v2->sy;
	/* Denominators of the barycentric solution (2D, so area-independent form). */
	const float d = (by - cy) * (ax - cx) + (cx - bx) * (ay - cy);
	if (d == 0.0f) return;
	const float inv_d = 1.0f / d;

	/* Each barycentric weight is affine in the sample position, so a row is one
	 * multiply plus a per-pixel add -- and, more importantly, the three weights
	 * give the row's covered span in closed form. Testing every pixel of the
	 * bounding box wastes the corners of it, which for the thin triangles a
	 * rotated quad produces is most of the work. */
	const float ga0 = (by - cy) * inv_d, gb0 = (cx - bx) * inv_d;
	const float ga1 = (cy - ay) * inv_d, gb1 = (ax - cx) * inv_d;
	const float gc0 = -(ga0 * cx + gb0 * cy);
	const float gc1 = -(ga1 * cx + gb1 * cy);
	const float ga2 = -ga0 - ga1, gb2 = -gb0 - gb1, gc2 = 1.0f - gc0 - gc1;

	float attr[ATTR_COUNT + 1];
	for (int y = y0; y <= y1; y++) {
		const float py = (float)y + 0.5f;
		const float c0 = gb0 * py + gc0, c1 = gb1 * py + gc1, c2 = gb2 * py + gc2;
		int rx0 = x0, rx1 = x1;
		bool bad = false;
		/* l(x) = g*x + c >= 0 for each of the three weights. */
		for (int e = 0; e < 3; e++) {
			const float g = e == 0 ? ga0 : (e == 1 ? ga1 : ga2);
			const float c = e == 0 ? c0  : (e == 1 ? c1  : c2);
			if (g > -1e-6f && g < 1e-6f) { if (c < 0.0f) { bad = true; break; } continue; }
			float xedge = -c / g;                     /* where this edge crosses 0 */
			if (g > 0.0f) {                           /* inside is to the right */
				int lo = (int)ceilf(xedge - 0.5f);
				if (lo > rx0) rx0 = lo;
			} else {                                  /* inside is to the left */
				int hi = (int)floorf(xedge + 0.5f);
				if (hi < rx1) rx1 = hi;
			}
		}
		if (bad) continue;
		/* One pixel of slack on each side: the division above is float, and what
		 * decides coverage is the exact test inside the loop, not this bound. */
		rx0 -= 1; rx1 += 1;
		if (rx0 < x0) rx0 = x0;
		if (rx1 > x1) rx1 = x1;
		if (rx1 < rx0) continue;

		for (int x = rx0; x <= rx1; x++) {
			float px = (float)x + 0.5f;
			float l0 = ((by - cy) * (px - cx) + (cx - bx) * (py - cy)) * inv_d;
			float l1 = ((cy - ay) * (px - cx) + (ax - cx) * (py - cy)) * inv_d;
			float l2 = 1.0f - l0 - l1;
			if (l0 < 0.0f || l1 < 0.0f || l2 < 0.0f) continue;

			float iw = l0 * v0->iw + l1 * v1->iw + l2 * v2->iw;
			if (!(iw > 0.0f)) continue;
			float k = 1.0f / iw;
			for (int a = 0; a < ATTR_COUNT; a++) {
				attr[a] = (l0 * v0->a[a] + l1 * v1->a[a] + l2 * v2->a[a]) * k;
			}
			/* NDC depth is affine in window space for a planar triangle (the
			 * projection's z/w is linear in x/z and y/z, which are exactly the
			 * screen axes), so it must NOT get the 1/w correction the other
			 * attributes need. */
			float z = l0 * v0->sz + l1 * v1->sz + l2 * v2->sz;
			/* Wireframe helper: distance to the nearest edge, in pixels. The
			 * barycentric weights are already normalized, so scale by the box size
			 * to get something resolution-independent. */
			float scale = (maxx - minx + maxy - miny) * 0.5f;
			float edge = l0 < l1 ? (l0 < l2 ? l0 : l2) : (l1 < l2 ? l1 : l2);
			attr[ATTR_COUNT] = (edge * scale < 1.0f) ? 0.0f : 1000.0f;
			shade_pixel(s, pd, u, x, y, z, attr);
		}
	}
	s->stat_tri++;
}

static BerylResult soft_draw_indexed(BerylRhi *r, uint32_t index_count) {
	SoftDevice *s = (SoftDevice *)r->backend_state;
	if (!s->in_pass || !s->verts || !s->idx) return BERYL_ERR_INVALID;
	if (s->bound_pipe == 0) return BERYL_ERR_INVALID;
	const BerylPipelineDesc *pd = &s->pipes[s->bound_pipe - 1].desc;
	const BerylTerrainUniforms *u = s->bound.uniforms;
	if (!u) return BERYL_ERR_INVALID;

	size_t limit = (size_t)(s->idx_end - s->idx);
	uint32_t n = index_count ? index_count : (uint32_t)limit;
	if (n > limit) n = (uint32_t)limit;

	const float hw = 0.5f * (float)s->w, hh = 0.5f * (float)s->h;
	SoftVert tri[3], clipped[8];

	for (uint32_t i = 0; i + 2 < n; i += 3) {
		for (int k = 0; k < 3; k++) {
			uint32_t vi = s->idx[i + (uint32_t)k];
			if (vi >= s->vert_count) return BERYL_ERR_INVALID;
			shade_vertex(s, &s->verts[vi], u, &tri[k]);
		}
		s->stat_verts += 3;

		int m = clip_near(tri, 3, clipped, SOFT_NEAR_EPS);
		if (m < 3) continue;

		RastVert rv[8];
		for (int k = 0; k < m; k++) {
			float w = clipped[k].clip[3];
			float inv = 1.0f / w;
			rv[k].sx = (clipped[k].clip[0] * inv * 0.5f + 0.5f) * (float)s->w;
			/* NDC y points up, pixel y points down: that is the one flip the
			 * viewport transform has to make, and it reverses winding. */
			rv[k].sy = (0.5f - clipped[k].clip[1] * inv * 0.5f) * (float)s->h;
			/* Vulkan convention has z in [0,1]; the engine always feeds GL's [-1,1]
			 * clip range, so map it here and let the Vulkan backend be the only
			 * place that cares. Depth test is "less or equal" on the mapped range. */
			rv[k].sz = (clipped[k].clip[2] * inv * 0.5f + 0.5f);
			rv[k].iw = inv;
			for (int a = 0; a < ATTR_COUNT; a++) rv[k].a[a] = clipped[k].attr[a] * inv;
		}
		for (int k = 1; k + 1 < m; k++) {
			raster_tri(s, pd, u, &rv[0], &rv[k], &rv[k + 1]);
		}
		s->stat_draw++;
		(void)hw; (void)hh;
	}
	return BERYL_OK;
}

static BerylResult soft_end_frame(BerylRhi *r) {
	(void)r;
	return BERYL_OK;
}

static const uint8_t *soft_readback(BerylRhi *r, int *w, int *h) {
	SoftDevice *s = (SoftDevice *)r->backend_state;
	if (w) *w = s->w;
	if (h) *h = s->h;
	return s->color;
}

static void soft_get_info(BerylRhi *r, BerylRhiInfo *out) {
	(void)r;
	memset(out, 0, sizeof(*out));
	snprintf(out->backend, sizeof(out->backend), "software");
	snprintf(out->renderer, sizeof(out->renderer), "Beryllium reference rasterizer (CPU)");
	snprintf(out->version, sizeof(out->version), "beryl-soft 1");
	out->api_major = 1;
	out->api_minor = 0;
	out->max_texture_size = 8192;
	out->max_bound_texture = 4;
}

static uint64_t soft_stat(BerylRhi *r, int which) {
	SoftDevice *s = (SoftDevice *)r->backend_state;
	switch (which) {
		case BERYL_STAT_DRAW_CALLS: return s->stat_draw;
		case BERYL_STAT_TRIANGLES:  return s->stat_tri;
		case BERYL_STAT_BUFFER_UPLOADS: return s->stat_uploads;
		case BERYL_STAT_BUFFER_BYTES: return s->stat_bytes;
		case BERYL_STAT_TEXTURE_UPLOADS: return s->stat_tex_uploads;
		case BERYL_STAT_VERTS: return s->stat_verts;
		default: return 0;
	}
}

static void soft_reset_stats(BerylRhi *r) {
	SoftDevice *s = (SoftDevice *)r->backend_state;
	s->stat_draw = s->stat_tri = s->stat_uploads = 0;
	s->stat_bytes = s->stat_tex_uploads = s->stat_verts = 0;
}

static void soft_destroy(BerylRhi *r) {
	SoftDevice *s = (SoftDevice *)r->backend_state;
	if (s) {
		for (int i = 0; i < SOFT_MAX_BUFFERS; i++) free(s->bufs[i].data);
		for (int i = 0; i < SOFT_MAX_TEXTURES; i++) free(s->texs[i].data);
		free(s->color);
		free(s->depth);
		free(s);
	}
	free(r);
}

static const BerylRhiVTable g_soft_vt = {
	"software",
	soft_destroy,
	soft_create_buffer, soft_destroy_buffer, soft_upload_buffer,
	soft_create_texture, soft_destroy_texture, soft_upload_texture_layer,
	soft_create_pipeline, soft_destroy_pipeline,
	soft_begin_frame, soft_begin_pass, soft_bind, soft_draw_indexed,
	soft_end_pass, soft_end_frame,
	soft_readback, soft_get_info, soft_stat, soft_reset_stats
};

BerylRhi *beryl_rhi_create_software(int width, int height) {
	SoftDevice *s = (SoftDevice *)calloc(1, sizeof(SoftDevice));
	BerylRhi *r = (BerylRhi *)calloc(1, sizeof(BerylRhi));
	if (!s || !r) { free(s); free(r); return NULL; }
	r->vt = &g_soft_vt;
	r->backend_state = s;
	r->headless = true;
	s->w = width; s->h = height;
	s->color = (uint8_t *)calloc((size_t)width * (size_t)height, 4);
	s->depth = (float *)malloc((size_t)width * (size_t)height * sizeof(float));
	if (!s->color || !s->depth) { free(s->color); free(s->depth); free(s); free(r); return NULL; }
	r->width = width; r->height = height;
	r->readback = s->color;
	r->readback_size = (size_t)width * (size_t)height * 4u;
	beryl_rhi_get_info(r, &r->info);
	return r;
}
