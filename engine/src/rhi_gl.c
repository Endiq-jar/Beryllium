/* rhi_gl.c -- the desktop OpenGL backend (GL 3.3 core, ES 3.0 compatible source).
 *
 * What lives here is deliberately thin: this file turns the engine's render
 * hardware interface into GL commands, nothing else. Geometry never passes
 * through it -- the mesh store writes `BerylVertex` arrays into the buffers
 * created by create_buffer, which is why the software, OpenGL and Vulkan
 * backends consume an identical byte stream and any difference between them is
 * a backend bug rather than a data bug.
 *
 * Two decisions are worth stating plainly:
 *
 *  - No link-time GL dependency. Every entry point is a pointer in BerylGLLoader.
 *    The production loader dlopen()s libGL and dlsym()s the names (Mesa's libGL
 *    exports the whole 3.3 core profile, so no extension loader is needed); an
 *    embedder that creates its context through EGL or WGL hands in its own
 *    get_proc, and a test hands in a recorder. That is what lets this file be
 *    compiled, linked and *tested* on a machine with no GPU, no display and no
 *    driver, which is where it was written.
 *  - The backend never creates a context. Whatever context is current when its
 *    calls are made is the one it draws into. Capture goes through an offscreen
 *    FBO, so the screenshot path needs no window system either.
 *
 * Uniforms are a memcpy of the shared 288-byte std140 block: the byte layout the
 * shaders see is the layout the software rasterizer reads, with no per-backend
 * packing step that could drift.
 */
#include "rhi.h"
#include "rhi_gl.h"
#include "bcore.h"
#include "mesh_format.h"
#include "shaders_terrain.h"

#include <stdio.h>
#include <stdlib.h>
#include <stddef.h>
#include <string.h>

#if defined(__unix__) || defined(__APPLE__)
#include <dlfcn.h>
#endif

/* The .glsl sources carry no #version line: the backend owns the profile choice. */
#define BERYL_GL_GLSL_HEADER "#version 330 core\n#define BERYL_OPENGL 1\n"

#define GL_MAX_BUFFERS   16384
#define GL_MAX_TEXTURES    512
#define GL_MAX_PIPELINES    64

/* Every table entry starts with `bool used`, which is what lets one allocator
 * serve all three handle spaces. Handles are 1-based: 0 is BERYL_HANDLE_NONE. */
typedef struct GlBuf {
	bool          used;
	beryl_gl_uint id;
	size_t        size;
	bool          dynamic;
	bool          storage;       /* allocated with BufferStorage, never re-specified */
} GlBuf;

typedef struct GlTex {
	bool          used;
	beryl_gl_uint id;
	beryl_gl_enum target;
	int           w, h, layers;
} GlTex;

typedef struct GlPipe {
	bool              used;
	beryl_gl_uint     prog;
	beryl_gl_int      block_index;   /* index of the "Terrain" uniform block */
	beryl_gl_int      loc[5];        /* aPosXY, aPosZ, aUV, aPack0, aPack1   */
	BerylPipelineDesc d;
} GlPipe;

typedef struct GlDevice {
	BerylGLLoader l;
	bool          close_loader;

	GlBuf   bufs[GL_MAX_BUFFERS];
	GlTex   texs[GL_MAX_TEXTURES];
	GlPipe  pipes[GL_MAX_PIPELINES];

	beryl_gl_uint vao;             /* one VAO for the whole engine           */
	beryl_gl_uint ubo;             /* one 288-byte uniform block, reused     */
	beryl_gl_uint fbo, color_tex, depth_rb;
	int           fw, fh;          /* offscreen target size                  */

	/* Applied-state cache: GL is a state machine and the engine binds once per
	 * section, so skipping redundant calls is the whole game. */
	BerylPipeline  bound_pipe;
	BerylBuffer   bound_vb, bound_ib;
	size_t         bound_stride;
	uint64_t       uniforms_hash;
	bool           have_uniforms;
	bool           state_depth_test, state_depth_write, state_blend, state_cull;
	int            state_depth_fn;
	bool           state_valid;
	int            index_offset;
	size_t         index_stride;

	uint64_t stat_draws, stat_tris, stat_uploads, stat_bytes, stat_tex_uploads, stat_verts;
	bool     pass_open, frame_open, bound_in_pass;
	BerylRhiInfo info;           /* scratch copy: flags the vtable reports later */
} GlDevice;

/* ------------------------------------------------------------------ loader -- */
static const char *gl_slot_name(int i);
typedef struct { const char *name; size_t offset; bool optional; } GlSlot;

#define BERYL_GL_ENTRIES(X)                                                        \
	X(GenBuffers, false) X(DeleteBuffers, false) X(BindBuffer, false)              \
	X(BufferData, false) X(BufferSubData, false) X(BufferStorage, true)             \
	X(GenVertexArrays, false) X(DeleteVertexArrays, false) X(BindVertexArray, false)\
	X(EnableVertexAttribArray, false) X(VertexAttribPointer, false)                 \
	X(VertexAttribIPointer, false) X(BindBufferRange, false)                        \
	X(GetUniformBlockIndex, false) X(UniformBlockBinding, false)                    \
	X(GenTextures, false) X(DeleteTextures, false) X(BindTexture, false)            \
	X(ActiveTexture, false) X(TexImage3D, false) X(TexImage2D, false)                \
	X(TexSubImage3D, false) X(TexParameteri, false) X(PixelStorei, false)            \
	X(CreateShader, false) X(ShaderSource, false) X(CompileShader, false)           \
	X(GetShaderiv, false) X(GetShaderInfoLog, false) X(CreateProgram, false)        \
	X(AttachShader, false) X(LinkProgram, false) X(UseProgram, false)               \
	X(GetProgramiv, false) X(GetProgramInfoLog, false) X(GetUniformLocation, false)  \
	X(Uniform1i, false) X(GetAttribLocation, false) X(DeleteShader, false)           \
	X(DeleteProgram, false) X(GenFramebuffers, false) X(DeleteFramebuffers, false)   \
	X(BindFramebuffer, false) X(FramebufferTexture, false)                           \
	X(CheckFramebufferStatus, false) X(GenRenderbuffers, false)                      \
	X(DeleteRenderbuffers, false) X(BindRenderbuffer, false)                         \
	X(RenderbufferStorage, false) X(FramebufferRenderbuffer, false)                  \
	X(Enable, false) X(Disable, false) X(DepthFunc, false) X(DepthMask, false)        \
	X(CullFace, false) X(FrontFace, false) X(BlendFuncSeparate, false)                \
	X(ClearColor, false) X(ClearDepth, false) X(Clear, false) X(Viewport, false)      \
	X(DrawElements, false) X(ReadPixels, false) X(GetString, false)                    \
	X(GetIntegererv, false) X(GetError, false) X(Flush, false)

static const GlSlot gl_slots[] = {
#define BERYL_GL_SLOT(name, opt) { "gl" #name, offsetof(BerylGLLoader, name), opt },
	BERYL_GL_ENTRIES(BERYL_GL_SLOT)
#undef BERYL_GL_SLOT
};
static const int gl_slot_count = (int)(sizeof(gl_slots) / sizeof(gl_slots[0]));

static const char *gl_slot_name(int i) { return (i >= 0 && i < gl_slot_count) ? gl_slots[i].name : NULL; }
int beryl_gl_loader_entry_count(void) { return gl_slot_count; }
const char *beryl_gl_loader_entry_name(int i) { return gl_slot_name(i); }

bool beryl_gl_loader_resolve(BerylGLLoader *l) {
	if (!l) return false;
	l->ok = false;
	l->missing = NULL;
	for (int i = 0; i < gl_slot_count; i++) {
		const GlSlot *s = &gl_slots[i];
		void **dst = (void **)((char *)l + s->offset);
		if (*dst) continue;                       /* injected by the caller */
		void *p = NULL;
		if (l->get_proc) p = l->get_proc(s->name, l->user);
#if defined(__unix__) || defined(__APPLE__)
		else if (l->handle) p = dlsym(l->handle, s->name);
#endif
		if (!p) {
			if (s->optional) continue;
			l->missing = s->name;
			return false;
		}
		*dst = p;
	}
	l->ok = true;
	return true;
}

bool beryl_gl_loader_default(BerylGLLoader *l) {
#if defined(__unix__) || defined(__APPLE__)
	static const char *const names[] = { "libGL.so.1", "libGL.so", "libOpenGL.so.0" };
	if (!l) return false;
	memset(l, 0, sizeof(*l));
	for (size_t i = 0; i < sizeof(names) / sizeof(names[0]) && !l->handle; i++)
		l->handle = dlopen(names[i], RTLD_LAZY | RTLD_LOCAL);
	if (!l->handle) { l->missing = "libGL"; return false; }
	l->get_proc = NULL;                 /* the resolve loop uses dlsym directly */
	l->owns_handle = true;
	if (!beryl_gl_loader_resolve(l)) {
		dlclose(l->handle);
		l->handle = NULL;
		l->owns_handle = false;
		return false;
	}
	return true;
#else
	(void)l;
	return false;
#endif
}

const char *beryl_gl_vertex_source(void)   { return beryl_src_terrain_vert; }
const char *beryl_gl_fragment_source(void) { return beryl_src_terrain_frag; }

/* --------------------------------------------------------------- utilities --- */
#define GL_CALL(dev, fn, ...)                                                     \
	do {                                                                          \
		if (!(dev)->l.fn) return BERYL_ERR_UNSUPPORTED;                           \
		(dev)->l.fn(__VA_ARGS__);                                                 \
	} while (0)

static BerylResult gl_check(GlDevice *d, const char *what) {
	if (!d->l.GetError) return BERYL_OK;
	BerylResult rc = BERYL_OK;
	beryl_gl_enum e;
	int guard = 0;
	while ((e = d->l.GetError()) != BERYL_GL_NO_ERROR && guard++ < 16) {
		BERYL_LOGE("gl: error 0x%X from %s", (unsigned)e, what);
		rc = BERYL_ERR_DEVICE_LOST;
	}
	return rc;
}

static uint32_t gl_alloc_handle(void *tab, size_t elem, uint32_t cap) {
	for (uint32_t i = 0; i < cap; i++) {
		const bool *used = (const bool *)((const char *)tab + (size_t)i * elem);
		if (*used) continue;
		bool *u = (bool *)((char *)tab + (size_t)i * elem);
		*u = true;
		return i + 1u;
	}
	return 0;
}

static void *gl_handle(GlDevice *d, uint32_t h, int kind) {
	switch (kind) {
		case 0: return h >= 1 && h <= GL_MAX_BUFFERS ? &d->bufs[h - 1] : NULL;
		case 1: return h >= 1 && h <= GL_MAX_TEXTURES ? &d->texs[h - 1] : NULL;
		default: return h >= 1 && h <= GL_MAX_PIPELINES ? &d->pipes[h - 1] : NULL;
	}
}

/* --------------------------------------------------------------- resources --- */
static BerylResult gl_create_buffer(BerylRhi *r, const BerylBufferDesc *d, BerylBuffer *out) {
	GlDevice *dev = (GlDevice *)r->backend_state;
	if (!d || d->size == 0 || !out) return BERYL_ERR_INVALID;
	uint32_t h = gl_alloc_handle(dev->bufs, sizeof(GlBuf), GL_MAX_BUFFERS);
	if (!h) return BERYL_ERR_OOM;
	GlBuf *b = (GlBuf *)gl_handle(dev, h, 0);
	beryl_gl_uint id = 0;
	GL_CALL(dev, GenBuffers, 1, &id);
	b->id = id;
	b->size = d->size;
	b->dynamic = d->dynamic;
	b->storage = false;
	if (dev->l.BufferStorage && d->dynamic) {
		/* Immutable storage on COPY_WRITE, then every upload is a BufferSubData
		 * against a buffer that never gets reallocated under a bound VAO. The
		 * buffer has to be bound to the target the storage is allocated for. */
		dev->l.BindBuffer(BERYL_GL_COPY_WRITE_BUFFER, id);
		dev->l.BufferStorage(BERYL_GL_COPY_WRITE_BUFFER, (beryl_gl_sizeiptr)d->size, d->initial,
		                     BERYL_GL_MAP_WRITE_BIT | BERYL_GL_DYNAMIC_STORAGE_BIT);
		b->storage = true;
		dev->info.buffer_storage = true;
	} else {
		GL_CALL(dev, BindBuffer, BERYL_GL_ARRAY_BUFFER, id);
		GL_CALL(dev, BufferData, BERYL_GL_ARRAY_BUFFER, (beryl_gl_sizeiptr)d->size, d->initial,
		        d->dynamic ? BERYL_GL_DYNAMIC_DRAW : BERYL_GL_STATIC_DRAW);
	}
	dev->stat_bytes += d->size;
	dev->stat_uploads++;
	*out = h;
	return gl_check(dev, "create_buffer");
}

static void gl_destroy_buffer(BerylRhi *r, BerylBuffer h) {
	GlDevice *dev = (GlDevice *)r->backend_state;
	GlBuf *b = (GlBuf *)gl_handle(dev, h, 0);
	if (!b || !b->used) return;
	if (dev->l.DeleteBuffers) dev->l.DeleteBuffers(1, &b->id);
	if (dev->bound_vb == h) { dev->bound_vb = 0; dev->bound_pipe = 0; }
	if (dev->bound_ib == h) { dev->bound_ib = 0; dev->bound_pipe = 0; }
	memset(b, 0, sizeof(*b));
}

static BerylResult gl_upload_buffer(BerylRhi *r, BerylBuffer h, const void *src, size_t size, size_t offset) {
	GlDevice *dev = (GlDevice *)r->backend_state;
	GlBuf *b = (GlBuf *)gl_handle(dev, h, 0);
	if (!b || !b->used || !src || offset + size > b->size) return BERYL_ERR_INVALID;
	beryl_gl_enum target = b->storage ? BERYL_GL_COPY_WRITE_BUFFER : BERYL_GL_ARRAY_BUFFER;
	GL_CALL(dev, BindBuffer, target, b->id);
	GL_CALL(dev, BufferSubData, target, (beryl_gl_sizeiptr)offset, (beryl_gl_sizeiptr)size, src);
	dev->stat_uploads++;
	dev->stat_bytes += size;
	return BERYL_OK;
}

static BerylResult gl_create_texture(BerylRhi *r, const BerylTextureDesc *d, BerylTexture *out) {
	GlDevice *dev = (GlDevice *)r->backend_state;
	if (!d || d->width <= 0 || d->height <= 0 || !out) return BERYL_ERR_INVALID;
	uint32_t h = gl_alloc_handle(dev->texs, sizeof(GlTex), GL_MAX_TEXTURES);
	if (!h) return BERYL_ERR_OOM;
	GlTex *t = (GlTex *)gl_handle(dev, h, 1);
	beryl_gl_uint id = 0;
	GL_CALL(dev, GenTextures, 1, &id);
	t->id = id;
	t->w = d->width;
	t->h = d->height;
	t->layers = d->layers > 0 ? d->layers : 1;
	t->target = t->layers > 1 ? BERYL_GL_TEXTURE_2D_ARRAY : BERYL_GL_TEXTURE_2D;
	/* CLAMP_TO_EDGE by default: the shader tiles with fract(), so wrapping must be
	 * done in the shader, not by the sampler, or a merge that crosses a tile edge
	 * would sample the neighbour's border texels. */
	const beryl_gl_enum filt = d->nearest ? BERYL_GL_NEAREST : BERYL_GL_LINEAR;
	const beryl_gl_enum wrap = d->wrap ? BERYL_GL_REPEAT : BERYL_GL_CLAMP_TO_EDGE;
	GL_CALL(dev, BindTexture, t->target, id);
	GL_CALL(dev, TexParameteri, t->target, BERYL_GL_TEXTURE_MIN_FILTER, (beryl_gl_int)filt);
	GL_CALL(dev, TexParameteri, t->target, BERYL_GL_TEXTURE_MAG_FILTER, (beryl_gl_int)filt);
	GL_CALL(dev, TexParameteri, t->target, BERYL_GL_TEXTURE_WRAP_S, (beryl_gl_int)wrap);
	GL_CALL(dev, TexParameteri, t->target, BERYL_GL_TEXTURE_WRAP_T, (beryl_gl_int)wrap);
	if (t->layers > 1)
		GL_CALL(dev, TexParameteri, t->target, BERYL_GL_TEXTURE_WRAP_R, BERYL_GL_CLAMP_TO_EDGE);
	if (d->initial) {
		const size_t layer_bytes = (size_t)d->width * (size_t)d->height * 4u;
		if (t->target == BERYL_GL_TEXTURE_2D_ARRAY) {
			GL_CALL(dev, PixelStorei, BERYL_GL_PIXEL_UNPACK_ALIGNMENT, 1);
			for (int L = 0; L < t->layers; L++) {
				GL_CALL(dev, TexSubImage3D, t->target, 0, 0, 0, L, d->width, d->height, 1,
				        BERYL_GL_RGBA, BERYL_GL_UNSIGNED_BYTE,
				        (const uint8_t *)d->initial + (size_t)L * layer_bytes);
			}
			dev->stat_tex_uploads += (uint64_t)t->layers;
		} else {
			if (dev->l.TexImage2D) {
				dev->l.TexImage2D(t->target, 0, BERYL_GL_RGBA8, d->width, d->height, 0, BERYL_GL_RGBA,
				                  BERYL_GL_UNSIGNED_BYTE, d->initial);
			} else {
				GL_CALL(dev, PixelStorei, BERYL_GL_PIXEL_UNPACK_ALIGNMENT, 1);
				GL_CALL(dev, TexSubImage3D, t->target, 0, 0, 0, 0, d->width, d->height, 1, BERYL_GL_RGBA,
				        BERYL_GL_UNSIGNED_BYTE, d->initial);
			}
			dev->stat_tex_uploads++;
		}
	} else if (t->target == BERYL_GL_TEXTURE_2D_ARRAY) {
		GL_CALL(dev, TexImage3D, t->target, 0, BERYL_GL_RGBA8, d->width, d->height, t->layers, 0,
		        BERYL_GL_RGBA, BERYL_GL_UNSIGNED_BYTE, NULL);
	}
	*out = h;
	return gl_check(dev, "create_texture");
}

static void gl_destroy_texture(BerylRhi *r, BerylTexture h) {
	GlDevice *dev = (GlDevice *)r->backend_state;
	GlTex *t = (GlTex *)gl_handle(dev, h, 1);
	if (!t || !t->used) return;
	if (dev->l.DeleteTextures) dev->l.DeleteTextures(1, &t->id);
	memset(t, 0, sizeof(*t));
}

static BerylResult gl_upload_texture_layer(BerylRhi *r, BerylTexture h, int layer, const void *px) {
	GlDevice *dev = (GlDevice *)r->backend_state;
	GlTex *t = (GlTex *)gl_handle(dev, h, 1);
	if (!t || !t->used || !px || layer < 0 || layer >= t->layers) return BERYL_ERR_INVALID;
	GL_CALL(dev, BindTexture, t->target, t->id);
	GL_CALL(dev, PixelStorei, BERYL_GL_PIXEL_UNPACK_ALIGNMENT, 1);
	GL_CALL(dev, TexSubImage3D, t->target, 0, 0, 0, layer, t->w, t->h, 1, BERYL_GL_RGBA,
	        BERYL_GL_UNSIGNED_BYTE, px);
	dev->stat_tex_uploads++;
	return BERYL_OK;
}

static BerylResult gl_compile_shader(GlDevice *dev, beryl_gl_enum type, const char *src, beryl_gl_uint *out) {
	beryl_gl_uint sh = dev->l.CreateShader(type);
	if (!sh) return BERYL_ERR_INIT;
	dev->l.ShaderSource(sh, 1, &src, NULL);
	dev->l.CompileShader(sh);
	beryl_gl_int okv = 0;
	dev->l.GetShaderiv(sh, BERYL_GL_COMPILE_STATUS, &okv);
	if (!okv) {
		char log[1024];
		log[0] = 0;
		if (dev->l.GetShaderInfoLog) dev->l.GetShaderInfoLog(sh, (int)sizeof(log), NULL, log);
		BERYL_LOGE("gl: shader compile failed: %s", log[0] ? log : "(no log)");
		dev->l.DeleteShader(sh);
		return BERYL_ERR_INIT;
	}
	*out = sh;
	return BERYL_OK;
}

static BerylResult gl_create_pipeline(BerylRhi *r, const BerylPipelineDesc *d, BerylPipeline *out) {
	GlDevice *dev = (GlDevice *)r->backend_state;
	if (!d || !out) return BERYL_ERR_INVALID;
	if (!dev->l.CreateShader || !dev->l.CreateProgram) return BERYL_ERR_UNSUPPORTED;

	/* One program for every pipeline variant: the mode/blend differences the engine
	 * cares about live in the uniform block and in GL state, not in the code. That
	 * is what keeps a pipeline switch to a `UseProgram` + a few state calls. */
	const size_t vlen = strlen(BERYL_GL_GLSL_HEADER) + strlen(beryl_src_terrain_vert) + 1;
	const size_t flen = strlen(BERYL_GL_GLSL_HEADER) + strlen(beryl_src_terrain_frag) + 1;
	char *vs = (char *)malloc(vlen), *fs = (char *)malloc(flen);
	if (!vs || !fs) { free(vs); free(fs); return BERYL_ERR_OOM; }
	snprintf(vs, vlen, "%s%s", BERYL_GL_GLSL_HEADER, beryl_src_terrain_vert);
	snprintf(fs, flen, "%s%s", BERYL_GL_GLSL_HEADER, beryl_src_terrain_frag);

	beryl_gl_uint vsh = 0, fsh = 0;
	BerylResult rc = gl_compile_shader(dev, BERYL_GL_VERTEX_SHADER, vs, &vsh);
	free(vs);
	if (rc == BERYL_OK) rc = gl_compile_shader(dev, BERYL_GL_FRAGMENT_SHADER, fs, &fsh);
	free(fs);
	if (rc != BERYL_OK) return rc;

	beryl_gl_uint prog = dev->l.CreateProgram();
	dev->l.AttachShader(prog, vsh);
	dev->l.AttachShader(prog, fsh);
	dev->l.LinkProgram(prog);
	if (dev->l.DeleteShader) { dev->l.DeleteShader(vsh); dev->l.DeleteShader(fsh); }
	beryl_gl_int linked = 0;
	if (dev->l.GetProgramiv) dev->l.GetProgramiv(prog, BERYL_GL_LINK_STATUS, &linked);
	if (!linked) {
		char log[1024];
		log[0] = 0;
		if (dev->l.GetProgramInfoLog) dev->l.GetProgramInfoLog(prog, (int)sizeof(log), NULL, log);
		BERYL_LOGE("gl: link failed: %s", log[0] ? log : "(no log)");
		if (dev->l.DeleteProgram) dev->l.DeleteProgram(prog);
		return BERYL_ERR_INIT;
	}

	uint32_t h = gl_alloc_handle(dev->pipes, sizeof(GlPipe), GL_MAX_PIPELINES);
	if (!h) { if (dev->l.DeleteProgram) dev->l.DeleteProgram(prog); return BERYL_ERR_OOM; }
	GlPipe *p = (GlPipe *)gl_handle(dev, h, 2);
	p->prog = prog;
	p->block_index = 0;
	if (dev->l.GetUniformBlockIndex) {
		beryl_gl_int bi = dev->l.GetUniformBlockIndex(prog, "Terrain");
		if (bi < 0) {
			BERYL_LOGE("gl: the terrain program has no `Terrain` uniform block");
			dev->l.DeleteProgram(prog);
			memset(p, 0, sizeof(*p));
			return BERYL_ERR_INIT;
		}
		p->block_index = bi;
	}
	if (dev->l.UniformBlockBinding) dev->l.UniformBlockBinding(prog, (beryl_gl_uint)p->block_index, 0);

	/* Attribute locations are queried, not assumed: without explicit `layout`
	 * qualifiers the driver is free to assign them. */
	static const char *const names[5] = { "aPosXY", "aPosZ", "aUV", "aPack0", "aPack1" };
	for (int i = 0; i < 5; i++) {
		p->loc[i] = i;
		if (dev->l.GetAttribLocation) {
			beryl_gl_int q = dev->l.GetAttribLocation(prog, names[i]);
			if (q >= 0) p->loc[i] = q;
		}
	}
	/* Samplers bind to fixed units: 0 = tile array, 1 = lightmap. */
	if (dev->l.UseProgram) dev->l.UseProgram(prog);
	if (dev->l.GetUniformLocation && dev->l.Uniform1i) {
		beryl_gl_int a = dev->l.GetUniformLocation(prog, "uTexArray");
		beryl_gl_int b = dev->l.GetUniformLocation(prog, "uLightmap");
		if (a >= 0) dev->l.Uniform1i(a, 0);
		if (b >= 0) dev->l.Uniform1i(b, 1);
	}
	p->d = *d;
	dev->info.api_major = 3;
	dev->info.api_minor = 3;
	*out = h;
	return gl_check(dev, "create_pipeline");
}

static void gl_destroy_pipeline(BerylRhi *r, BerylPipeline h) {
	GlDevice *dev = (GlDevice *)r->backend_state;
	GlPipe *p = (GlPipe *)gl_handle(dev, h, 2);
	if (!p || !p->used) return;
	if (dev->l.DeleteProgram) dev->l.DeleteProgram(p->prog);
	if (dev->bound_pipe == h) dev->bound_pipe = 0;
	memset(p, 0, sizeof(*p));
}

/* ------------------------------------------------------------------ passes --- */
static BerylResult gl_ensure_target(GlDevice *dev, BerylRhi *r, int w, int h) {
	if (dev->fw == w && dev->fh == h && dev->fbo) return BERYL_OK;
	if (!dev->l.GenFramebuffers || !dev->l.GenRenderbuffers || !dev->l.FramebufferTexture)
		return BERYL_ERR_UNSUPPORTED;
	if (dev->fbo) {
		dev->l.DeleteFramebuffers(1, &dev->fbo);
		if (dev->l.DeleteTextures) dev->l.DeleteTextures(1, &dev->color_tex);
		if (dev->l.DeleteRenderbuffers) dev->l.DeleteRenderbuffers(1, &dev->depth_rb);
		dev->fbo = dev->color_tex = dev->depth_rb = 0;
	}
	dev->l.GenFramebuffers(1, &dev->fbo);
	dev->l.GenTextures(1, &dev->color_tex);
	dev->l.GenRenderbuffers(1, &dev->depth_rb);
	dev->l.BindFramebuffer(BERYL_GL_FRAMEBUFFER, dev->fbo);
	dev->l.BindTexture(BERYL_GL_TEXTURE_2D, dev->color_tex);
	if (dev->l.PixelStorei) dev->l.PixelStorei(BERYL_GL_PIXEL_UNPACK_ALIGNMENT, 4);
	if (dev->l.TexImage2D) {
		dev->l.TexImage2D(BERYL_GL_TEXTURE_2D, 0, BERYL_GL_RGBA8, w, h, 0, BERYL_GL_RGBA,
		                  BERYL_GL_UNSIGNED_BYTE, NULL);
	} else {
		dev->l.TexImage3D(BERYL_GL_TEXTURE_2D, 0, BERYL_GL_RGBA8, w, h, 1, 0, BERYL_GL_RGBA,
		                  BERYL_GL_UNSIGNED_BYTE, NULL);
	}
	dev->l.BindTexture(BERYL_GL_TEXTURE_2D, 0);
	dev->l.FramebufferTexture(BERYL_GL_FRAMEBUFFER, BERYL_GL_COLOR_ATTACHMENT0, dev->color_tex, 0);
	dev->l.BindRenderbuffer(BERYL_GL_RENDERBUFFER, dev->depth_rb);
	dev->l.RenderbufferStorage(BERYL_GL_RENDERBUFFER, BERYL_GL_DEPTH_COMPONENT24, w, h);
	dev->l.BindRenderbuffer(BERYL_GL_RENDERBUFFER, 0);
	dev->l.FramebufferRenderbuffer(BERYL_GL_FRAMEBUFFER, BERYL_GL_DEPTH_ATTACHMENT, BERYL_GL_RENDERBUFFER,
	                               dev->depth_rb);
	beryl_gl_enum st = dev->l.CheckFramebufferStatus ? dev->l.CheckFramebufferStatus(BERYL_GL_FRAMEBUFFER)
	                                                 : BERYL_GL_FRAMEBUFFER_COMPLETE;
	if (st != BERYL_GL_FRAMEBUFFER_COMPLETE) {
		BERYL_LOGE("gl: offscreen framebuffer incomplete (0x%X) at %dx%d", (unsigned)st, w, h);
		dev->l.BindFramebuffer(BERYL_GL_FRAMEBUFFER, 0);
		return BERYL_ERR_INIT;
	}
	dev->l.BindFramebuffer(BERYL_GL_FRAMEBUFFER, 0);
	dev->fw = w; dev->fh = h;
	free(r->readback);
	r->readback = (uint8_t *)malloc((size_t)w * (size_t)h * 4u);
	r->readback_size = r->readback ? (size_t)w * (size_t)h * 4u : 0;
	r->width = w; r->height = h;
	return BERYL_OK;
}

static BerylResult gl_begin_frame(BerylRhi *r, const BerylFrameDesc *d) {
	GlDevice *dev = (GlDevice *)r->backend_state;
	(void)d;
	dev->frame_open = true;
	dev->state_valid = false;      /* the app may have touched GL state itself */
	dev->uniforms_hash = 0;
	dev->have_uniforms = false;
	return BERYL_OK;
}

static BerylResult gl_begin_pass(BerylRhi *r, const BerylPassDesc *d) {
	GlDevice *dev = (GlDevice *)r->backend_state;
	if (!d || d->width <= 0 || d->height <= 0) return BERYL_ERR_INVALID;
	if (d->draw_into_texture) {
		BerylResult rc = gl_ensure_target(dev, r, d->width, d->height);
		if (rc != BERYL_OK) return rc;
		GL_CALL(dev, BindFramebuffer, BERYL_GL_FRAMEBUFFER, dev->fbo);
	} else if (dev->l.BindFramebuffer) {
		dev->l.BindFramebuffer(BERYL_GL_FRAMEBUFFER, 0);
	}
	GL_CALL(dev, Viewport, 0, 0, d->width, d->height);
	if (d->clear) {
		GL_CALL(dev, ClearColor, d->clear_color[0], d->clear_color[1], d->clear_color[2], d->clear_color[3]);
		GL_CALL(dev, ClearDepth, (double)d->clear_depth);
		GL_CALL(dev, DepthMask, BERYL_GL_TRUE);
		if (dev->l.Disable) dev->l.Disable(BERYL_GL_DEPTH_TEST);
		GL_CALL(dev, Clear, BERYL_GL_COLOR_BUFFER_BIT | BERYL_GL_DEPTH_BUFFER_BIT);
		dev->state_valid = false;
	}
	r->width = d->width;
	r->height = d->height;
	dev->pass_open = true;
	dev->bound_in_pass = false;
	return gl_check(dev, "begin_pass");
}

/* Five integer attributes, BERYL_VERTEX_SIZE apart: mesh_format.h is the only
 * source of truth, so the offsets here are asserted against it at compile time. */
#if defined(__STDC_VERSION__) && __STDC_VERSION__ >= 201112L
_Static_assert(sizeof(BerylVertex) == 16, "the GL vertex layout assumes a 16-byte vertex");
_Static_assert(offsetof(BerylVertex, pos_x) == 0, "aPosXY offset moved");
_Static_assert(offsetof(BerylVertex, pos_z) == 4, "aPosZ offset moved");
_Static_assert(offsetof(BerylVertex, uv_s) == 6, "aUV offset moved");
_Static_assert(offsetof(BerylVertex, ao_face) == 10, "aPack0 offset moved");
_Static_assert(offsetof(BerylVertex, tile) == 12, "aPack1 offset moved");
#endif
static void gl_apply_vertex_layout(GlDevice *dev, const GlPipe *p) {
	static const beryl_gl_int sizes[5] = { 2, 1, 2, 2, 2 };
	static const beryl_gl_uint offs[5] = { 0, 4, 6, 10, 12 };
	static const beryl_gl_enum types[5] = {
		BERYL_GL_UNSIGNED_SHORT, BERYL_GL_UNSIGNED_SHORT, BERYL_GL_UNSIGNED_SHORT,
		BERYL_GL_UNSIGNED_BYTE, BERYL_GL_UNSIGNED_BYTE
	};
	for (int i = 0; i < 5; i++) {
		dev->l.EnableVertexAttribArray((beryl_gl_uint)p->loc[i]);
		/* Integer attributes: 8.8 fixed-point positions must arrive unmodified,
		 * which a float path through [0,1] normalisation would not guarantee. */
		if (dev->l.VertexAttribIPointer)
			dev->l.VertexAttribIPointer((beryl_gl_uint)p->loc[i], sizes[i], types[i],
			                            (beryl_gl_int)sizeof(BerylVertex), (const void *)(uintptr_t)offs[i]);
		else
			dev->l.VertexAttribPointer((beryl_gl_uint)p->loc[i], sizes[i], types[i], BERYL_GL_FALSE,
			                           (beryl_gl_int)sizeof(BerylVertex), (const void *)(uintptr_t)offs[i]);
	}
}

static uint64_t gl_hash(const uint8_t *p, size_t n) {
	uint64_t h = 1469598103934665603ull;
	for (size_t i = 0; i < n; i++) { h ^= p[i]; h *= 1099511628211ull; }
	return h;
}

static BerylResult gl_bind(BerylRhi *r, BerylPipeline pipe, const BerylBindState *st) {
	GlDevice *dev = (GlDevice *)r->backend_state;
	if (!st || pipe == 0) return BERYL_ERR_INVALID;
	GlPipe *p = (GlPipe *)gl_handle(dev, pipe, 2);
	if (!p || !p->used) return BERYL_ERR_INVALID;
	if (st->index_stride != 4 && st->index_stride != 2) return BERYL_ERR_INVALID;

	const bool layout_changed = dev->bound_pipe != pipe || dev->bound_vb != st->vertex_buffer ||
	                            dev->bound_ib != st->index_buffer || dev->bound_stride != st->index_stride;
	if (layout_changed) {
		if (dev->l.BindVertexArray) dev->l.BindVertexArray(dev->vao);
		if (st->vertex_buffer) {
			GlBuf *b = (GlBuf *)gl_handle(dev, st->vertex_buffer, 0);
			if (!b || !b->used) return BERYL_ERR_INVALID;
			GL_CALL(dev, BindBuffer, BERYL_GL_ARRAY_BUFFER, b->id);
		}
		if (st->index_buffer) {
			GlBuf *b = (GlBuf *)gl_handle(dev, st->index_buffer, 0);
			if (!b || !b->used) return BERYL_ERR_INVALID;
			GL_CALL(dev, BindBuffer, BERYL_GL_ELEMENT_ARRAY_BUFFER, b->id);
		}
		gl_apply_vertex_layout(dev, p);
		dev->bound_pipe = pipe;
		dev->bound_vb = st->vertex_buffer;
		dev->bound_ib = st->index_buffer;
		dev->bound_stride = st->index_stride;
		dev->index_stride = st->index_stride;
	}

	if (dev->l.UseProgram) dev->l.UseProgram(p->prog);

	/* The uniform block, byte for byte, once per change. */
	if (st->uniforms) {
		const uint64_t hh = gl_hash((const uint8_t *)st->uniforms, sizeof(BerylTerrainUniforms));
		if (!dev->have_uniforms || hh != dev->uniforms_hash) {
			if (!dev->ubo && dev->l.GenBuffers) dev->l.GenBuffers(1, &dev->ubo);
			if (dev->ubo) {
				const bool first = !dev->have_uniforms;
				GL_CALL(dev, BindBuffer, BERYL_GL_UNIFORM_BUFFER, dev->ubo);
				if (first && dev->l.BufferData) {
					dev->l.BufferData(BERYL_GL_UNIFORM_BUFFER, (beryl_gl_sizeiptr)sizeof(BerylTerrainUniforms),
					                  st->uniforms, BERYL_GL_DYNAMIC_DRAW);
				} else {
					GL_CALL(dev, BufferSubData, BERYL_GL_UNIFORM_BUFFER, 0,
					        (beryl_gl_sizeiptr)sizeof(BerylTerrainUniforms), st->uniforms);
				}
				GL_CALL(dev, BindBufferRange, BERYL_GL_UNIFORM_BUFFER, 0, dev->ubo, 0,
				        (beryl_gl_sizeiptr)sizeof(BerylTerrainUniforms));
				dev->uniforms_hash = hh;
				dev->have_uniforms = true;
				dev->stat_bytes += sizeof(BerylTerrainUniforms);
				dev->stat_uploads++;
			}
		}
	}

	for (int i = 0; i < st->texture_count && i < 4; i++) {
		GlTex *t = (GlTex *)gl_handle(dev, st->textures[i], 1);
		if (dev->l.ActiveTexture) dev->l.ActiveTexture((beryl_gl_enum)(BERYL_GL_TEXTURE0 + i));
		if (dev->l.BindTexture) dev->l.BindTexture(t && t->used ? t->target : BERYL_GL_TEXTURE_2D,
		                                           t && t->used ? t->id : 0u);
	}

	/* Pipeline state, only when it differs from what is applied. */
	const bool fresh = !dev->state_valid;
	if (fresh || dev->state_depth_test != p->d.depth_test) {
		if (p->d.depth_test) GL_CALL(dev, Enable, BERYL_GL_DEPTH_TEST);
		else if (dev->l.Disable) dev->l.Disable(BERYL_GL_DEPTH_TEST);
		dev->state_depth_test = p->d.depth_test;
	}
	if (fresh || dev->state_depth_write != p->d.depth_write) {
		GL_CALL(dev, DepthMask, p->d.depth_write ? BERYL_GL_TRUE : BERYL_GL_FALSE);
		dev->state_depth_write = p->d.depth_write;
	}
	if (fresh || dev->state_depth_fn != (int)p->d.depth) {
		beryl_gl_enum fn = BERYL_GL_LESS;
		if (p->d.depth == BERYL_DEPTH_LEQUAL) fn = BERYL_GL_LEQUAL;
		else if (p->d.depth == BERYL_DEPTH_ALWAYS) fn = BERYL_GL_ALWAYS;
		GL_CALL(dev, DepthFunc, fn);
		dev->state_depth_fn = (int)p->d.depth;
	}
	if (fresh || dev->state_cull != p->d.cull_back) {
		if (p->d.cull_back) {
			GL_CALL(dev, Enable, BERYL_GL_CULL_FACE);
			GL_CALL(dev, CullFace, BERYL_GL_BACK);
			/* The mesher emits CCW triangles in a right-handed clip space. The
			 * software rasterizer sees a y-down viewport instead and flips its
			 * area test to match; both end up keeping the same faces. */
			GL_CALL(dev, FrontFace, BERYL_GL_CCW);
		} else if (dev->l.Disable) {
			dev->l.Disable(BERYL_GL_CULL_FACE);
		}
		dev->state_cull = p->d.cull_back;
	}
	if (fresh || dev->state_blend != p->d.blend) {
		if (p->d.blend) {
			GL_CALL(dev, Enable, BERYL_GL_BLEND);
			GL_CALL(dev, BlendFuncSeparate, BERYL_GL_SRC_ALPHA, BERYL_GL_ONE_MINUS_SRC_ALPHA,
			        BERYL_GL_ONE, BERYL_GL_ONE_MINUS_SRC_ALPHA);
		} else if (dev->l.Disable) {
			dev->l.Disable(BERYL_GL_BLEND);
		}
		dev->state_blend = p->d.blend;
	}
	dev->state_valid = true;
	dev->index_offset = (int)st->index_offset;
	dev->bound_in_pass = true;
	return BERYL_OK;
}

static BerylResult gl_draw_indexed(BerylRhi *r, uint32_t index_count) {
	GlDevice *dev = (GlDevice *)r->backend_state;
	if (!dev->pass_open) return BERYL_ERR_INVALID;
	if (index_count == 0) return BERYL_OK;
	/* Without a bind in this pass the vertex layout and buffers are whatever the
	 * last frame left behind, which draws garbage without reporting anything. */
	if (!dev->bound_in_pass) {
		BERYL_LOGE("gl: draw_indexed without a bind in the current pass");
		return BERYL_ERR_INVALID;
	}
	const beryl_gl_enum type = dev->index_stride == 2 ? BERYL_GL_UNSIGNED_SHORT : BERYL_GL_UNSIGNED_INT;
	const void *ofs = (const void *)(uintptr_t)((size_t)dev->index_offset * (dev->index_stride ? dev->index_stride : 4u));
	GL_CALL(dev, DrawElements, BERYL_GL_TRIANGLES, (beryl_gl_int)index_count, type, ofs);
	dev->stat_draws++;
	dev->stat_tris += index_count / 3u;
	dev->stat_verts += index_count;
	return BERYL_OK;
}

static void gl_end_pass(BerylRhi *r) {
	GlDevice *dev = (GlDevice *)r->backend_state;
	if (dev->l.BindFramebuffer) dev->l.BindFramebuffer(BERYL_GL_FRAMEBUFFER, 0);
	dev->pass_open = false;
}

static BerylResult gl_end_frame(BerylRhi *r) {
	GlDevice *dev = (GlDevice *)r->backend_state;
	if (dev->l.Flush) dev->l.Flush();
	dev->frame_open = false;
	return BERYL_OK;
}

static const uint8_t *gl_readback(BerylRhi *r, int *w, int *h) {
	GlDevice *dev = (GlDevice *)r->backend_state;
	const int tw = dev->fw > 0 ? dev->fw : r->width;
	const int th = dev->fh > 0 ? dev->fh : r->height;
	if (tw <= 0 || th <= 0 || !dev->l.ReadPixels) return NULL;
	if (!r->readback || r->readback_size < (size_t)tw * (size_t)th * 4u) {
		free(r->readback);
		r->readback = (uint8_t *)malloc((size_t)tw * (size_t)th * 4u);
		r->readback_size = r->readback ? (size_t)tw * (size_t)th * 4u : 0;
		if (!r->readback) return NULL;
	}
	uint8_t *tmp = (uint8_t *)malloc((size_t)tw * (size_t)th * 4u);
	if (!tmp) return NULL;
	if (dev->l.BindFramebuffer && dev->fbo) dev->l.BindFramebuffer(BERYL_GL_FRAMEBUFFER, dev->fbo);
	if (dev->l.PixelStorei) dev->l.PixelStorei(BERYL_GL_PIXEL_UNPACK_ALIGNMENT, 4);
	dev->l.ReadPixels(0, 0, tw, th, BERYL_GL_RGBA, BERYL_GL_UNSIGNED_BYTE, tmp);
	if (dev->l.BindFramebuffer) dev->l.BindFramebuffer(BERYL_GL_FRAMEBUFFER, 0);
	/* GL reads bottom-up; every consumer of `readback` expects top-down. */
	const size_t row = (size_t)tw * 4u;
	for (int y = 0; y < th; y++)
		memcpy(r->readback + (size_t)y * row, tmp + (size_t)(th - 1 - y) * row, row);
	free(tmp);
	r->width = tw;
	r->height = th;
	if (w) *w = tw;
	if (h) *h = th;
	return r->readback;
}

static void gl_get_info(BerylRhi *r, BerylRhiInfo *out) {
	GlDevice *dev = (GlDevice *)r->backend_state;
	*out = r->info;
	out->api_major = 3;
	out->api_minor = 3;
	out->buffer_storage = dev->info.buffer_storage;
	snprintf(out->backend, sizeof(out->backend), "OpenGL 3.3 core");
	if (dev->l.GetString) {
		const unsigned char *s = dev->l.GetString(BERYL_GL_RENDERER);
		if (s) snprintf(out->renderer, sizeof(out->renderer), "%s", (const char *)s);
		s = dev->l.GetString(BERYL_GL_VERSION);
		if (s) snprintf(out->version, sizeof(out->version), "%s", (const char *)s);
	}
	if (dev->l.GetIntegererv) {
		int v = 0;
		dev->l.GetIntegererv(BERYL_GL_MAX_TEXTURE_SIZE, &v);
		out->max_texture_size = v > 0 ? v : 8192;
		v = 0;
		dev->l.GetIntegererv(BERYL_GL_MAX_ARRAY_TEXTURE_LAYERS, &v);
		out->max_bound_texture = v;
	} else {
		out->max_texture_size = 8192;
	}
	out->vram_estimate = dev->stat_bytes;
}

static uint64_t gl_stat(BerylRhi *r, int which) {
	GlDevice *dev = (GlDevice *)r->backend_state;
	switch (which) {
		case BERYL_STAT_DRAW_CALLS:      return dev->stat_draws;
		case BERYL_STAT_TRIANGLES:       return dev->stat_tris;
		case BERYL_STAT_BUFFER_UPLOADS:  return dev->stat_uploads;
		case BERYL_STAT_BUFFER_BYTES:    return dev->stat_bytes;
		case BERYL_STAT_TEXTURE_UPLOADS: return dev->stat_tex_uploads;
		case BERYL_STAT_VERTS:           return dev->stat_verts;
		default:                         return 0;
	}
}

static void gl_reset_stats(BerylRhi *r) {
	GlDevice *dev = (GlDevice *)r->backend_state;
	dev->stat_draws = dev->stat_tris = dev->stat_uploads = 0;
	dev->stat_bytes = dev->stat_tex_uploads = dev->stat_verts = 0;
}

static void gl_destroy(BerylRhi *r) {
	if (!r) return;
	GlDevice *dev = (GlDevice *)r->backend_state;
	if (dev) {
		if (r->headless || 1) {
			for (uint32_t i = 0; i < GL_MAX_PIPELINES; i++)
				if (dev->pipes[i].used && dev->l.DeleteProgram) dev->l.DeleteProgram(dev->pipes[i].prog);
			for (uint32_t i = 0; i < GL_MAX_BUFFERS; i++)
				if (dev->bufs[i].used && dev->l.DeleteBuffers) dev->l.DeleteBuffers(1, &dev->bufs[i].id);
			for (uint32_t i = 0; i < GL_MAX_TEXTURES; i++)
				if (dev->texs[i].used && dev->l.DeleteTextures) dev->l.DeleteTextures(1, &dev->texs[i].id);
			if (dev->ubo && dev->l.DeleteBuffers) dev->l.DeleteBuffers(1, &dev->ubo);
			if (dev->fbo && dev->l.DeleteFramebuffers) dev->l.DeleteFramebuffers(1, &dev->fbo);
			if (dev->color_tex && dev->l.DeleteTextures) dev->l.DeleteTextures(1, &dev->color_tex);
			if (dev->depth_rb && dev->l.DeleteRenderbuffers) dev->l.DeleteRenderbuffers(1, &dev->depth_rb);
			if (dev->vao && dev->l.DeleteVertexArrays) dev->l.DeleteVertexArrays(1, &dev->vao);
		}
#if defined(__unix__) || defined(__APPLE__)
		if (dev->close_loader && dev->l.handle) dlclose(dev->l.handle);
#endif
		free(dev);
	}
	free(r->readback);
	free(r);
}

static const BerylRhiVTable gl_vtable = {
	"opengl",
	gl_destroy,
	gl_create_buffer, gl_destroy_buffer, gl_upload_buffer,
	gl_create_texture, gl_destroy_texture, gl_upload_texture_layer,
	gl_create_pipeline, gl_destroy_pipeline,
	gl_begin_frame, gl_begin_pass, gl_bind, gl_draw_indexed, gl_end_pass, gl_end_frame,
	gl_readback, gl_get_info, gl_stat, gl_reset_stats
};

BerylRhi *beryl_rhi_new_gl(int width, int height, BerylGLLoader *loader) {
	if (width <= 0 || height <= 0) return NULL;
	BerylRhi *r = (BerylRhi *)calloc(1, sizeof(*r));
	GlDevice *dev = (GlDevice *)calloc(1, sizeof(*dev));
	if (!r || !dev) { free(r); free(dev); return NULL; }
	if (loader) dev->l = *loader;
	else if (!beryl_gl_loader_default(&dev->l)) {
		BERYL_LOGE("gl: no loader supplied and libGL is unavailable (%s)",
		           dev->l.missing ? dev->l.missing : "unknown");
		free(dev); free(r);
		return NULL;
	} else {
		dev->close_loader = dev->l.owns_handle;
	}
	if (!beryl_gl_loader_resolve(&dev->l)) {
		BERYL_LOGE("gl: entry point %s not found", dev->l.missing ? dev->l.missing : "?");
#if defined(__unix__) || defined(__APPLE__)
		if (dev->close_loader && dev->l.handle) dlclose(dev->l.handle);
#endif
		free(dev); free(r);
		return NULL;
	}
	r->vt = &gl_vtable;
	r->backend_state = dev;
	r->width = width;
	r->height = height;
	r->headless = true;
	snprintf(r->info.backend, sizeof(r->info.backend), "OpenGL 3.3 core");
	r->info.api_major = 3;
	r->info.api_minor = 3;
	dev->info = r->info;
	/* The VAO has to exist before any attribute state is applied. */
	if (dev->l.GenVertexArrays) dev->l.GenVertexArrays(1, &dev->vao);
	if (dev->l.BindVertexArray && dev->vao) dev->l.BindVertexArray(dev->vao);
	if (dev->l.Disable) dev->l.Disable(BERYL_GL_DEPTH_TEST);   /* cleared by the first pass */
	if (dev->l.Enable) dev->l.Enable(BERYL_GL_DEPTH_TEST);
	if (dev->l.FrontFace) dev->l.FrontFace(BERYL_GL_CCW);
	if (dev->l.CullFace) dev->l.CullFace(BERYL_GL_BACK);
	return r;
}

#if BERYL_WITH_OPENGL
/* The entry point rhi.c dispatches to for BERYL_BACKEND_OPENGL. */
BerylRhi *beryl_rhi_create_gl(int width, int height, const void *platform_hint) {
	(void)platform_hint;    /* an embedder may pass its own loader here later */
	return beryl_rhi_new_gl(width, height, NULL);
}
#endif
