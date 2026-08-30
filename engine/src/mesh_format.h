/* mesh_format.h -- the vertex layout shared by the mesher, all three backends
 * and the terrain shader. One layout, one truth: what the mesher writes is
 * byte-for-byte what the GPU consumes, so uploading a section is one memcpy and
 * there is no per-frame conversion (Sodium's "immediate upload" idea taken to
 * its conclusion).
 *
 * Every attribute is an *integer* stream with explicit fixed-point scaling done
 * in the vertex shader. Two reasons:
 *   - uint16 8.8 positions are exact at any world coordinate once the section
 *     origin is added in float, so there is no jitter far from the origin
 *     (float16 could not offer that; float32 costs 2x the memory);
 *   - the attribute formats used here (R16G16_UINT, R16_UINT, R8G8_UINT) exist
 *     identically in desktop GL, GL ES 3 and Vulkan, so no backend needs a
 *     different vertex buffer.
 */
#ifndef BERYL_MESH_FORMAT_H
#define BERYL_MESH_FORMAT_H

#include "bcore.h"
#include "bmath.h"
#include <stddef.h>

#define BERYL_POS_SHIFT 8                       /* 1/256 block              */
#define BERYL_POS_SCALE (1 << BERYL_POS_SHIFT)  /* 16 blocks -> 4096        */
/* UV: the in-plane corner coordinate in 1/16-*block* units, aligned to the
 * section origin. Not atlas texels, not normalized [0,1]: the vertex shader
 * applies fract() to it, which tiles the block texture once per block exactly
 * the way Minecraft expects, works on any merged rectangle size, and needs no
 * clamp-to-sprite inset because there is no packed atlas to bleed from. */
#define BERYL_UV_SHIFT  4                       /* 1/16 block                 */
#define BERYL_UV_SCALE  (1 << BERYL_UV_SHIFT)   /* 16 blocks -> 256           */

/* packed attribute 0 (offset 10): ao_face = AO level bits 0-1 | face id bits 2-4 */
#define BERYL_AO_LEVEL_MASK 3
#define BERYL_FACE_SHIFT    2
/* packed attribute 1 (offset 12): tile = texture array layer, flags below */
#define BERYL_VFLAG_CUTOUT  0x01
#define BERYL_VFLAG_BLEND   0x02
/* The biome tint index shares the flags byte with the layer bits: eight slots is
 * all a Minecraft tint cache needs, and it keeps the vertex at 16 bytes with
 * five attributes instead of growing both by a byte and a padding hole. */
#define BERYL_VTINT_SHIFT   4
#define BERYL_VTINT_MASK    7

enum BerylLayer {
	BERYL_LAYER_SOLID = 0,
	BERYL_LAYER_CUTOUT = 1,
	BERYL_LAYER_BLEND = 2,
	BERYL_LAYER_COUNT = 3
};

typedef struct BerylVertex {
	uint16_t pos_x, pos_y;  /* 0, 2   section-local 8.8          */
	uint16_t pos_z;         /* 4      section-local 8.8          */
	uint16_t uv_s, uv_t;    /* 6, 8   texel * 16, wraps per 16   */
	uint8_t  ao_face;       /* 10                                */
	uint8_t  light;         /* 11     low nibble sky, high block */
	uint8_t  tile;          /* 12     texture array layer        */
	uint8_t  flags;         /* 13                                */
	uint8_t  pad[2];        /* 14     keeps stride at 16         */
} BerylVertex;

#if defined(__STDC_VERSION__) && __STDC_VERSION__ >= 201112L
_Static_assert(sizeof(BerylVertex) == 16, "BerylVertex is 16 bytes by contract with the GPU layout");
_Static_assert(offsetof(BerylVertex, pos_x) == 0, "pos offset moved");
_Static_assert(offsetof(BerylVertex, pos_z) == 4, "pos offset moved");
_Static_assert(offsetof(BerylVertex, uv_s) == 6, "uv offset moved");
_Static_assert(offsetof(BerylVertex, ao_face) == 10, "pack0 offset moved");
_Static_assert(offsetof(BerylVertex, tile) == 12, "pack1 offset moved");
#endif

/* Attribute binding table, shared by the three backends so they cannot drift. */
typedef enum BerylAttrib {
	BERYL_ATTRIB_POS_XY = 0,  /* location 0, 16-bit x2 */
	BERYL_ATTRIB_POS_Z  = 1,  /* location 1, 16-bit    */
	BERYL_ATTRIB_UV     = 2,  /* location 2, 16-bit x2 */
	BERYL_ATTRIB_PACK0  = 3,  /* location 3,  8-bit x2 */
	BERYL_ATTRIB_PACK1  = 4,  /* location 4,  8-bit x2 */
	BERYL_ATTRIB_COUNT  = 5
} BerylAttrib;

typedef struct BerylAttribDesc {
	uint32_t location;
	uint32_t components;   /* 2 or 1 (uvec2 / uint semantics)     */
	uint32_t offset;
	uint32_t size_bytes;   /* per component: 2 or 1                */
	const char *name;
} BerylAttribDesc;

const BerylAttribDesc *beryl_attrib_desc(BerylAttrib a);

static inline int beryl_vertex_ao(const BerylVertex *v)   { return v->ao_face & BERYL_AO_LEVEL_MASK; }
static inline int beryl_vertex_face(const BerylVertex *v) { return (v->ao_face >> BERYL_FACE_SHIFT) & 7; }
static inline int beryl_vertex_tint(const BerylVertex *v)   { return (v->flags >> BERYL_VTINT_SHIFT) & BERYL_VTINT_MASK; }
static inline uint8_t beryl_vertex_pack_flags(int tint, bool cutout, bool blend) {
	return (uint8_t)(((tint & BERYL_VTINT_MASK) << BERYL_VTINT_SHIFT)
	               | (cutout ? BERYL_VFLAG_CUTOUT : 0)
	               | (blend ? BERYL_VFLAG_BLEND : 0));
}

/* ------------------------------------------------------------- section mesh */
typedef struct BerylMeshLayer {
	BerylVertex *verts;
	size_t       vert_count;
	uint32_t    *indices;
	size_t       index_count;
	uint32_t     quad_count;
} BerylMeshLayer;

typedef struct BerylSectionMesh {
	BerylMeshLayer layer[BERYL_LAYER_COUNT];
	uint64_t source_revision;
	uint32_t quad_total;
	int32_t  cx, csy, cz;
	bool     valid;
	bool     uploaded;
	/* World-space AABB of the geometry actually emitted, for the culler. Equal
	 * to the section box when untouched, tighter for a thin slab. */
	BerylAabb bounds;
} BerylSectionMesh;

void   beryl_section_mesh_init(BerylSectionMesh *m, int32_t cx, int32_t csy, int32_t cz);
void   beryl_section_mesh_free(BerylSectionMesh *m);
void   beryl_section_mesh_reset(BerylSectionMesh *m);
size_t beryl_section_mesh_vertex_count(const BerylSectionMesh *m);
size_t beryl_section_mesh_index_count(const BerylSectionMesh *m);
/* Vertex buffers are indexed with uint32 indices; a section can't overflow that,
 * but the assertion is here so a future 32x32x32 section trips it in tests. */
bool   beryl_section_mesh_index_range_ok(const BerylSectionMesh *m);

#endif /* BERYL_MESH_FORMAT_H */
