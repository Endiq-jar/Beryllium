/* rhi.h -- the render hardware interface: one command stream, three backends.
 *
 *   BERYL_BACKEND_OPENGL   desktop GL 3.3 core / GL ES 3.0 (a single shader
 *                          source, because both speak GLSL 300), loaded through
 *                          dlopen -- no link-time dependency on libGL
 *   BERYL_BACKEND_VULKAN   Vulkan 1.0, entry points via libvulkan, shaders built
 *                          by the in-tree SPIR-V writer (spirv.h) so there is no
 *                          offline compiler requirement
 *   BERYL_BACKEND_SOFTWARE the reference rasterizer: CPU vertex+fragment program
 *                          that implements the *same* terrain shading, into the
 *                          same RGBA8 image, through the same draw calls
 *
 * The software backend is not a toy fallback and not a "null renderer": it is the
 * oracle the GPU paths are tested against, and it is what lets this engine render
 * a real image on a machine with no GPU, no display and no drivers (such as a
 * headless CI container -- which is exactly where this engine was written).
 *
 * All three consume the same mesh_format vertex layout and the same
 * BerylTerrainUniforms block, so per-frame CPU work is identical and a rendering
 * bug is immediately attributable to either the shared code or the backend.
 */
#ifndef BERYL_RHI_H
#define BERYL_RHI_H

#include "mesh_format.h"

/* ------------------------------------------------------------------ handles */
typedef uint32_t BerylBuffer;
typedef uint32_t BerylTexture;
typedef uint32_t BerylPipeline;
#define BERYL_HANDLE_NONE 0u

typedef enum BerylResult {
	BERYL_OK = 0,
	BERYL_ERR_UNSUPPORTED = -1,   /* backend or extension missing        */
	BERYL_ERR_INIT = -2,          /* driver present, init failed           */
	BERYL_ERR_OOM = -3,
	BERYL_ERR_INVALID = -4,
	BERYL_ERR_DEVICE_LOST = -5
} BerylResult;

/* ------------------------------------------------------------- terrain UB -- */
/* One uniform block for the whole terrain pipeline. std140 layout, so this
 * struct can be memcpy'd straight into a UBO with no reflection step. */
#define BERYL_TINT_MAX 8
typedef struct BerylTerrainUniforms {
	float mvp[16];             /* 0   projection * view             */
	float section[4];          /* 64  section world origin, w unused*/
	float cam_pos[4];          /* 80                                */
	float fog[4];              /* 96  start, end, sky high?, mode   */
	float fog_color[4];        /* 112                               */
	float tint[BERYL_TINT_MAX][4]; /* 128 .. 256                    */
	float params[4];           /* 256 time_of_day, texel size, mode, alpha */
	float pad_[4];             /* pad to 288: a multiple of 16 for std140      */
} BerylTerrainUniforms;
#if defined(__STDC_VERSION__) && __STDC_VERSION__ >= 201112L
_Static_assert(sizeof(BerylTerrainUniforms) == 288, "uniform block size is part of the shader ABI");
#endif

/* Uniform "mode" bits (params.z and fog.w): lets one pipeline serve debug views
 * without a pipeline switch storm. */
#define BERYL_MODE_MASK          7
#define BERYL_MODE_NORMAL        0
#define BERYL_MODE_LIGHTMAP      1   /* light levels only, no texture */
#define BERYL_MODE_TINT          2   /* texel colour, no lighting     */
#define BERYL_MODE_WIREFRAME     3   /* edges only (software + gl)    */
#define BERYL_MODE_FOG_NEAR      4   /* fog as a depth ramp           */

/* ------------------------------------------------------------------- state */
typedef enum BerylCullMode { BERYL_CULL_NONE, BERYL_CULL_BACK, BERYL_CULL_FRONT } BerylCullMode;
typedef enum BerylDepthOp  { BERYL_DEPTH_ALWAYS, BERYL_DEPTH_LEQUAL, BERYL_DEPTH_LESS } BerylDepthOp;

typedef struct BerylPipelineDesc {
	const char *debug_name;
	int   shader_variant;        /* 0 = terrain, 1 = terrain with blending */
	bool  blend;                 /* SRC_ALPHA, ONE_MINUS_SRC_ALPHA         */
	bool  depth_write;
	bool  depth_test;
	bool  cull_back;
	bool  alpha_test;            /* discard fragments below 0.5            */
	BerylCullMode cull;
	BerylDepthOp  depth;
} BerylPipelineDesc;

typedef struct BerylBufferDesc {
	size_t      size;
	bool        dynamic;         /* hint: re-uploaded every frame          */
	const void *initial;
	const char *debug_name;
} BerylBufferDesc;

typedef struct BerylTextureDesc {
	int   width, height;
	int   layers;                /* >1 selects a 2D array texture          */
	bool  nearest;               /* false -> linear                        */
	bool  wrap;                  /* true -> REPEAT on both axes            */
	const void *initial;         /* layers*width*height*4 RGBA8, or NULL   */
	const char *debug_name;
} BerylTextureDesc;

typedef struct BerylBindState {
	BerylBuffer vertex_buffer;
	BerylBuffer index_buffer;
	size_t      index_stride;    /* bytes: 4 with this engine's index type  */
	const BerylTerrainUniforms *uniforms;
	BerylTexture textures[4];
	int        texture_count;
	/* For the blend pass: which layers of the section mesh to draw. */
	uint32_t   index_offset;     /* in indices                              */
	uint32_t   index_count;
} BerylBindState;

typedef struct BerylPassDesc {
	int    width, height;
	float  clear_color[4];
	float  clear_depth;
	bool   clear;
	bool   draw_into_texture;    /* offscreen (software/GL FBO) vs present   */
	BerylTexture color_target;   /* optional render target                    */
} BerylPassDesc;

typedef struct BerylFrameDesc {
	double time_seconds;
	int    frame_index;
} BerylFrameDesc;

typedef struct BerylRhiInfo {
	char    backend[48];
	char    renderer[96];
	char    version[64];
	int     api_major, api_minor;
	bool    anisotropic;
	bool    buffer_storage;      /* persistent mapped / glBufferStorage       */
	bool    mesh_shader;         /* reported for the overlay, unused here     */
	int     max_texture_size;
	int     max_bound_texture;
	uint64_t vram_estimate;
} BerylRhiInfo;

/* -------------------------------------------------------------- vtable ----- */
typedef struct BerylRhi BerylRhi;

typedef struct BerylRhiVTable {
	const char *name;
	void (*destroy)(BerylRhi *);

	BerylResult (*create_buffer)(BerylRhi *, const BerylBufferDesc *, BerylBuffer *out);
	void        (*destroy_buffer)(BerylRhi *, BerylBuffer);
	BerylResult (*upload_buffer)(BerylRhi *, BerylBuffer, const void *, size_t size, size_t offset);

	BerylResult (*create_texture)(BerylRhi *, const BerylTextureDesc *, BerylTexture *out);
	void        (*destroy_texture)(BerylRhi *, BerylTexture);
	BerylResult (*upload_texture_layer)(BerylRhi *, BerylTexture, int layer, const void *pixels);

	BerylResult (*create_pipeline)(BerylRhi *, const BerylPipelineDesc *, BerylPipeline *out);
	void        (*destroy_pipeline)(BerylRhi *, BerylPipeline);

	BerylResult (*begin_frame)(BerylRhi *, const BerylFrameDesc *);
	BerylResult (*begin_pass)(BerylRhi *, const BerylPassDesc *);
	BerylResult (*bind)(BerylRhi *, BerylPipeline, const BerylBindState *);
	BerylResult (*draw_indexed)(BerylRhi *, uint32_t index_count);
	void        (*end_pass)(BerylRhi *);
	BerylResult (*end_frame)(BerylRhi *);

	/* CPU-readable copy of the current render target: software backend returns
	 * its own buffer, GL returns a glReadPixels result, Vulkan does a
	 * device-copy. Used for screenshots and by the image comparison tests. */
	const uint8_t *(*readback)(BerylRhi *, int *w, int *h);
	void          (*get_info)(BerylRhi *, BerylRhiInfo *);
	/* Draw/statistics counters that the overlay reports per backend. */
	uint64_t      (*stat)(BerylRhi *, int which);
	/* Lets a backend report how much geometry it actually consumed, for the
	 * "software == GL command stream" equivalence tests. */
	void          (*reset_stats)(BerylRhi *);
} BerylRhiVTable;

enum { BERYL_STAT_DRAW_CALLS = 0, BERYL_STAT_TRIANGLES = 1, BERYL_STAT_BUFFER_UPLOADS = 2,
	   BERYL_STAT_BUFFER_BYTES = 3, BERYL_STAT_TEXTURE_UPLOADS = 4, BERYL_STAT_VERTS = 5 };

struct BerylRhi {
	const BerylRhiVTable *vt;
	void  *backend_state;
	int    width, height;       /* current target size                     */
	bool   headless;
	uint8_t *readback;         /* shared readback image (backend-owned)   */
	size_t   readback_size;
	BerylRhiInfo info;
};

/* ------------------------------------------------------------------ factory */
typedef enum BerylBackend {
	BERYL_BACKEND_SOFTWARE = 0,
	BERYL_BACKEND_OPENGL   = 1,
	BERYL_BACKEND_VULKAN   = 2,
	BERYL_BACKEND_COUNT      = 3
} BerylBackend;

const char *beryl_backend_name(BerylBackend b);
/* Fills rhi->info from the backend, with a safe fallback. */
void beryl_rhi_get_info(BerylRhi *rhi, BerylRhiInfo *out);
/* Reports whether the backend can start here (loader present, device found). */
bool beryl_backend_available(BerylBackend b, char *why, size_t why_len);
BerylRhi *beryl_rhi_new(BerylBackend backend, int width, int height, const void *platform_hint);
void      beryl_rhi_destroy(BerylRhi *rhi);

/* Inline dispatch: the vtable is one indirection per call, which is what a
 * real RHI costs; the per-frame call count is small (a few hundred). */
#define BERYL_RHI_CALL(rhi, fn, ...) ((rhi)->vt->fn ? (rhi)->vt->fn(rhi, ##__VA_ARGS__) : BERYL_OK)

/* Backend constructors, each present only in builds that compile that backend
 * (the Makefile sets BERYL_WITH_OPENGL / BERYL_WITH_VULKAN accordingly). */
BerylRhi *beryl_rhi_create_software(int width, int height);
#if BERYL_WITH_OPENGL
BerylRhi *beryl_rhi_create_gl(int width, int height, const void *platform_hint);
#endif
#if BERYL_WITH_VULKAN
BerylRhi *beryl_rhi_create_vk(int width, int height, const void *platform_hint);
/* Vulkan needs a shader module; the GLSL above is the human-readable source of
 * truth and the SPIR-V writer in spirv.c produces the equivalent module. */
BerylResult beryl_vk_create_terrain_shaders(void *device, void *allocator_unused,
                                            void *out_vert, void *out_frag);
#endif

/* ---------------------------------------------------------------- terrain --- */
/* The shared vertex/fragment program the software backend executes, and that
 * shaders/terrain.glsl mirrors. Keeping it in one C file means the reference
 * implementation cannot drift from the interface without a compile error. */
typedef struct BerylShadedVertex {
	float clip[4];      /* gl_Position                        */
	float uv_s, uv_t;   /* interpolated texel-ish coords      */
	float light;        /* packed sky/block as float pair? no */
	float sky, blk;
	float ao;
	float fog_depth;
	float tint_index;
	float tile;
	int   layer;
	int   face;
	bool  cutout, blend;
	float color[3];     /* flat-shaded (face) multiplier      */
} BerylShadedVertex;

void beryl_terrain_vert(const BerylVertex *v, const BerylTerrainUniforms *u, BerylShadedVertex *out);
/* Returns premultiplied RGBA in [0,1]. `tex` points at the texture array layer. */
void beryl_terrain_frag(const BerylShadedVertex *itrp,
                        const uint8_t *tex_array, int tex_layers, int tex_size,
                        const uint8_t *lightmap /* 16x16 RGBA8 or NULL */,
                        const BerylTerrainUniforms *u,
                        float out_rgba[4]);

#endif /* BERYL_RHI_H */
