/* rhi.c -- backend selection, shared program code. */
#include "rhi.h"
#include "blocks.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

const char *beryl_backend_name(BerylBackend b) {
	switch (b) {
		case BERYL_BACKEND_SOFTWARE: return "software";
		case BERYL_BACKEND_OPENGL:   return "opengl";
		case BERYL_BACKEND_VULKAN:   return "vulkan";
		default:                     return "unknown";
	}
}

#if defined(__unix__) || defined(__APPLE__)
#include <dlfcn.h>
static void *try_dlopen(const char *const *names, size_t n, char *out, size_t out_len) {
	for (size_t i = 0; i < n; i++) {
		void *h = dlopen(names[i], RTLD_LAZY | RTLD_LOCAL);
		if (h) return h;
	}
	if (out && out_len) snprintf(out, out_len, "none of: %s", n ? names[0] : "?");
	return NULL;
}
#endif

bool beryl_backend_available(BerylBackend b, char *why, size_t why_len) {
	if (why && why_len) why[0] = 0;
	switch (b) {
		case BERYL_BACKEND_SOFTWARE:
			return true;
#if defined(__unix__) || defined(__APPLE__)
		case BERYL_BACKEND_OPENGL: {
			static const char *const gl[] = { "libGL.so.1", "libGL.so", "libOpenGL.so.0" };
			char whybuf[128] = { 0 };
			void *h = try_dlopen(gl, sizeof(gl) / sizeof(gl[0]), whybuf, sizeof(whybuf));
			if (!h) {
				if (why) snprintf(why, why_len, "%s (no libGL: GL is not available in a headless container)", whybuf);
				return false;
			}
			dlclose(h);
			return true;
		}
		case BERYL_BACKEND_VULKAN: {
			static const char *const vk[] = { "libvulkan.so.1", "libvulkan.so" };
			char whybuf[128] = { 0 };
			void *h = try_dlopen(vk, sizeof(vk) / sizeof(vk[0]), whybuf, sizeof(whybuf));
			if (!h) {
				if (why) snprintf(why, why_len, "%s (no Vulkan loader installed)", whybuf);
				return false;
			}
			dlclose(h);
			return true;
		}
#endif
		default:
			if (why) snprintf(why, why_len, "unknown backend");
			return false;
	}
}

#ifndef BERYL_WITH_OPENGL
static BerylRhi *beryl_rhi_create_gl(int w, int h, const void *hint) {
	(void)w; (void)h; (void)hint;
	BERYL_LOGE("this build has no OpenGL backend (rebuild with src/rhi_gl.c present)");
	return NULL;
}
#endif
#ifndef BERYL_WITH_VULKAN
static BerylRhi *beryl_rhi_create_vk(int w, int h, const void *hint) {
	(void)w; (void)h; (void)hint;
	BERYL_LOGE("this build has no Vulkan backend (rebuild with src/rhi_vk.c present)");
	return NULL;
}
#endif

BerylRhi *beryl_rhi_new(BerylBackend backend, int width, int height, const void *hint) {
	BERYL_ASSERT(width > 0 && height > 0, "framebuffer must be positive: %dx%d", width, height);
	switch (backend) {
		case BERYL_BACKEND_SOFTWARE: return beryl_rhi_create_software(width, height);
		case BERYL_BACKEND_OPENGL:   return beryl_rhi_create_gl(width, height, hint);
		case BERYL_BACKEND_VULKAN:   return beryl_rhi_create_vk(width, height, hint);
		default: return NULL;
	}
}

void beryl_rhi_get_info(BerylRhi *rhi, BerylRhiInfo *out) {
	if (rhi && rhi->vt && rhi->vt->get_info) rhi->vt->get_info(rhi, out);
	else memset(out, 0, sizeof(*out));
}

void beryl_rhi_destroy(BerylRhi *rhi) {
	if (!rhi) return;
	if (rhi->vt && rhi->vt->destroy) rhi->vt->destroy(rhi);
}

/* ------------------------------------------------------- terrain program --- */
/* This is the single most important function for "the software renderer shows the
 * same picture as the GPU one": it *is* the vertex stage of shaders/terrain.glsl,
 * written in C. The GLSL file and this function are reviewed together. */
void beryl_terrain_vert(const BerylVertex *v, const BerylTerrainUniforms *u, BerylShadedVertex *out) {
	float px = (float)v->pos_x * (1.0f / (float)BERYL_POS_SCALE) + u->section[0];
	float py = (float)v->pos_y * (1.0f / (float)BERYL_POS_SCALE) + u->section[1];
	float pz = (float)v->pos_z * (1.0f / (float)BERYL_POS_SCALE) + u->section[2];

	/* u MVP is stored column-major like the shader's mat4. */
	for (int i = 0; i < 4; i++) {
		out->clip[i] = u->mvp[i] * px + u->mvp[4 + i] * py + u->mvp[8 + i] * pz + u->mvp[12 + i];
	}

	out->uv_s = (float)v->uv_s * (1.0f / (float)BERYL_UV_SCALE);   /* in blocks */
	out->uv_t = (float)v->uv_t * (1.0f / (float)BERYL_UV_SCALE);
	out->tile = (float)v->tile;
	out->ao = beryl_ao_multiplier(beryl_vertex_ao(v));
	out->face = beryl_vertex_face(v);
	out->sky = (float)(v->light & 0xF);
	out->blk = (float)((v->light >> 4) & 0xF);
	out->layer = (v->flags & BERYL_VFLAG_BLEND) ? 2 : (v->flags & BERYL_VFLAG_CUTOUT) ? 1 : 0;
	out->cutout = (v->flags & BERYL_VFLAG_CUTOUT) != 0;
	out->blend = (v->flags & BERYL_VFLAG_BLEND) != 0;
	out->color[0] = out->color[1] = out->color[2] = 1.0f;
	out->tint_index = (float)beryl_vertex_tint(v);

	BerylVec3 world = beryl_vec3(px, py, pz);
	BerylVec3 d = beryl_v3_sub(world, beryl_vec3(u->cam_pos[0], u->cam_pos[1], u->cam_pos[2]));
	out->fog_depth = beryl_v3_length(d);
}

void beryl_terrain_frag(const BerylShadedVertex *it,
                        const uint8_t *tex_array, int tex_layers, int tex_size,
                        const uint8_t *lightmap,
                        const BerylTerrainUniforms *u,
                        float out_rgba[4]) {
	int mode = (int)u->params[2] & BERYL_MODE_MASK;
	float rgb[3] = { 1.0f, 1.0f, 1.0f };
	float alpha = 1.0f;

	if (tex_array && mode != BERYL_MODE_LIGHTMAP) {
		/* fract() tiling: exactly what the GLSL `texture()` does with a wrapped
		 * layer, expressed without depending on a sampler. */
		int layer = (int)it->tile;
		if (layer < 0) layer = 0;
		if (layer >= tex_layers) layer = tex_layers - 1;
		float su = it->uv_s - floorf(it->uv_s);
		float st = it->uv_t - floorf(it->uv_t);
		int tx = (int)(su * (float)tex_size);
		int ty = (int)(st * (float)tex_size);
		if (tx < 0) tx = 0;
		if (tx >= tex_size) tx = tex_size - 1;
		if (ty < 0) ty = 0;
		if (ty >= tex_size) ty = tex_size - 1;
		const uint8_t *base = tex_array + (size_t)layer * (size_t)tex_size * (size_t)tex_size * 4u;
		const uint8_t *t = base + ((size_t)ty * (size_t)tex_size + (size_t)tx) * 4u;
		rgb[0] = t[0] / 255.0f;
		rgb[1] = t[1] / 255.0f;
		rgb[2] = t[2] / 255.0f;
		alpha = t[3] / 255.0f;
	}

	if (it->cutout && alpha < 0.5f) {
		out_rgba[0] = out_rgba[1] = out_rgba[2] = out_rgba[3] = 0.0f;
		return;                                   /* discard */
	}

	/* Biome tint, exactly as MC multiplies the grass/foliage colour in. */
	int ti = (int)it->tint_index;
	if (ti > 0 && ti < BERYL_TINT_MAX) {
		rgb[0] *= u->tint[ti][0];
		rgb[1] *= u->tint[ti][1];
		rgb[2] *= u->tint[ti][2];
	}

	if (mode != BERYL_MODE_TINT) {
		/* Lightmap lookup: (block, sky) into the 16x16 LUT, like vanilla. */
		int sky = BERYL_CLAMP((int)it->sky, 0, 15);
		int blk = BERYL_CLAMP((int)it->blk, 0, 15);
		/* Sample the same 16x16 LUT the GPU does. With no LUT bound (unit tests of
		 * the fragment stage alone) fall back to the analytic ramp that generated
		 * it, so both routes agree. */
		float lr = 1.0f, lg = 1.0f, lb = 1.0f;
		if (lightmap) {
			const uint8_t *e = lightmap + ((size_t)sky * 16u + (size_t)blk) * 4u;
			lr = e[0] / 255.0f; lg = e[1] / 255.0f; lb = e[2] / 255.0f;
		} else {
			float fs = (float)sky / 15.0f, fb = (float)blk / 15.0f;
			float daylight = u->params[0];
			lr = beryl_lerp(0.06f, 1.0f, fs) * beryl_lerp(0.34f, 1.0f, daylight) + fb * fb * 0.9f + fb * 0.1f;
			lg = beryl_lerp(0.09f, 1.0f, fs) * beryl_lerp(0.38f, 1.0f, daylight) + (fb * fb * 0.9f + fb * 0.1f) * 0.62f;
			lb = beryl_lerp(0.20f, 1.0f, fs) * beryl_lerp(0.58f, 1.0f, daylight) + (fb * fb * 0.9f + fb * 0.1f) * 0.34f;
		}
		rgb[0] *= lr;
		rgb[1] *= lg;
		rgb[2] *= lb;
		rgb[0] *= it->ao;
		rgb[1] *= it->ao;
		rgb[2] *= it->ao;
		/* Face shading: vanilla's "which way does this quad point" darkening. */
		static const float k_shade[6] = { 0.5f, 1.0f, 0.8f, 0.8f, 0.6f, 0.6f };
		int face = BERYL_CLAMP(it->face, 0, 5);
		rgb[0] *= k_shade[face];
		rgb[1] *= k_shade[face];
		rgb[2] *= k_shade[face];
	}

	if (mode == BERYL_MODE_LIGHTMAP) {
		int sky = BERYL_CLAMP((int)it->sky, 0, 15);
		int blk = BERYL_CLAMP((int)it->blk, 0, 15);
		rgb[0] = (float)sky / 15.0f;
		rgb[1] = (float)blk / 15.0f;
		rgb[2] = 0.25f;
		alpha = 1.0f;
	} else if (mode == BERYL_MODE_FOG_NEAR) {
		float f = BERYL_CLAMP(it->fog_depth, 0.0f, 1.0f);
		rgb[0] = 1.0f - f; rgb[1] = 0.6f; rgb[2] = 0.1f;
		alpha = 1.0f;
	}

	/* Distance fog. */
	float fstart = u->fog[0], fend = u->fog[1];
	float fog_t = 0.0f;
	if (fend > fstart) {
		fog_t = BERYL_CLAMP((it->fog_depth - fstart) / (fend - fstart), 0.0f, 1.0f);
	}
	rgb[0] = beryl_lerp(rgb[0], u->fog_color[0], fog_t);
	rgb[1] = beryl_lerp(rgb[1], u->fog_color[1], fog_t);
	rgb[2] = beryl_lerp(rgb[2], u->fog_color[2], fog_t);

	out_rgba[0] = rgb[0];
	out_rgba[1] = rgb[1];
	out_rgba[2] = rgb[2];
	out_rgba[3] = alpha * (it->blend ? BERYL_MAX(u->params[3], 0.65f) : 1.0f);
}
