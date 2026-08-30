/* blocks.h -- block registry, procedural texture atlas, tint palette, lightmap.
 *
 * The engine ships zero binary assets: the atlas is generated in code from a
 * seeded hash so that a rendered frame is reproducible on any machine (which is
 * what makes the software-rasterizer regression tests meaningful), and so the
 * repository stays free of PNG/JAR blobs. tools/gen_atlas_preview.c dumps the
 * atlas if you want to look at it.
 */
#ifndef BERYL_BLOCKS_H
#define BERYL_BLOCKS_H

#include "bcore.h"
#include "bmath.h"

/* Vanilla Direction order, so face data can be pasted from Minecraft sources. */
typedef enum BerylFace {
	BERYL_FACE_DOWN = 0,
	BERYL_FACE_UP,
	BERYL_FACE_NORTH,   /* -Z */
	BERYL_FACE_SOUTH,   /* +Z */
	BERYL_FACE_WEST,    /* -X */
	BERYL_FACE_EAST,    /* +X */
	BERYL_FACE_COUNT = 6
} BerylFace;

/* Axis of a face: DOWN/UP -> Y, NORTH/SOUTH -> Z, WEST/EAST -> X. */
static inline int beryl_face_axis(BerylFace f) {
	static const int t[6] = { 1, 1, 2, 2, 0, 0 };
	return t[(int)f];
}
/* Which of the two faces along its axis (0 = negative side, 1 = positive). */
static inline int beryl_face_sign(BerylFace f) {
	return (f == BERYL_FACE_UP || f == BERYL_FACE_SOUTH || f == BERYL_FACE_EAST) ? 1 : 0;
}
static inline BerylFace beryl_face_opposite(BerylFace f) {
	static const int t[6] = { 1, 0, 3, 2, 5, 4 };
	return (BerylFace)t[(int)f];
}
/* Face normal in block space. */
BerylVec3 beryl_face_normal(BerylFace f);
/* Per-face directional shading, matching vanilla's 1.0 / 0.8 / 0.6 / 0.5 ramp. */
float beryl_face_shade(BerylFace f);

/* ------------------------------------------------------------------- flags */
enum BerylBlockFlag {
	/* OPAQUE: fills the voxel with light-blocking geometry -- hides the face of
	 * a neighbour, blocks light, and (with SOLID) can occlude a section. */
	BERYL_BFLG_OPAQUE        = 1 << 0,
	/* SOLID: cube-shaped, i.e. the voxel's AABB is fully occupied. Combined with
	 * OPAQUE this is what the occlusion graph treats as an occluder; a solid but
	 * see-through block (glass, water, ice) therefore never hides anything. */
	BERYL_BFLG_SOLID         = 1 << 1,
	BERYL_BFLG_CUTOUT        = 1 << 2, /* alpha-tested (leaves, torch)          */
	BERYL_BFLG_TRANSLUCENT   = 1 << 3, /* blended, drawn back-to-front          */
	BERYL_BFLG_SAME_TYPE_CULL= 1 << 4, /* Beryllium's internal-face culling     */
	BERYL_BFLG_EMITLIGHT     = 1 << 5, /* has block light emission              */
};

typedef struct BerylBlockInfo {
	const char *name;
	uint32_t    flags;
	uint8_t     tiles[BERYL_FACE_COUNT]; /* atlas tile index per face      */
	uint8_t     tint_index;              /* index into the tint palette    */
	uint8_t     light_emission;          /* 0..15                          */
	uint8_t     light_attenuation;       /* light lost passing through     */
	uint8_t     skylight_filter;         /* loss per step going straight down */
	uint8_t     render_layer;            /* 0 opaque, 1 cutout, 2 blend    */
} BerylBlockInfo;

/* Registry. Id 0 is the air gap; ids are frozen by this order and are the on-disk
 * representation, so append-only changes keep saved worlds readable. */
typedef enum BerylBlock {
	BERYL_BLOCK_AIR = 0,
	BERYL_BLOCK_STONE,
	BERYL_BLOCK_DIRT,
	BERYL_BLOCK_GRASS_BLOCK,
	BERYL_BLOCK_COBBLESTONE,
	BERYL_BLOCK_MOSSY_COBBLESTONE,
	BERYL_BLOCK_SAND,
	BERYL_BLOCK_GRAVEL,
	BERYL_BLOCK_CLAY,
	BERYL_BLOCK_SANDSTONE,
	BERYL_BLOCK_OAK_LOG,
	BERYL_BLOCK_OAK_PLANKS,
	BERYL_BLOCK_OAK_LEAVES,
	BERYL_BLOCK_BIRCH_LOG,
	BERYL_BLOCK_BIRCH_LEAVES,
	BERYL_BLOCK_SPRUCE_LEAVES,
	BERYL_BLOCK_BRICKS,
	BERYL_BLOCK_GLASS,
	BERYL_BLOCK_SNOW,
	BERYL_BLOCK_ICE,
	BERYL_BLOCK_COAL_ORE,
	BERYL_BLOCK_IRON_ORE,
	BERYL_BLOCK_GOLD_ORE,
	BERYL_BLOCK_DIAMOND_ORE,
	BERYL_BLOCK_GLOWSTONE,
	BERYL_BLOCK_TORCH,
	BERYL_BLOCK_WATER,
	BERYL_BLOCK_LAVA,
	BERYL_BLOCK_BEDROCK,
	BERYL_BLOCK_COUNT
} BerylBlock;

/* Textures are a *layer per tile* 2D array, not a packed atlas. Reason: greedy
 * merging produces rectangles up to 16x16 blocks wide, and their textures must
 * repeat per block. With an atlas that needs UV wrapping, which needs
 * clamp-to-sprite tricks to stop neighbouring tiles bleeding in. With an array,
 * wrap mode applies per layer, so repeating is exact and free. The array is
 * 32 layers x 16x16 x RGBA8 = 32 KiB total. */
#define BERYL_TILE_SIZE   16
#define BERYL_TILE_LAYERS 32     /* == number of generated tiles (enum in .c) */
#define BERYL_TEX_BYTES    (BERYL_TILE_SIZE * BERYL_TILE_SIZE * 4)
#define BERYL_ARRAY_BYTES  ((size_t)BERYL_TILE_LAYERS * BERYL_TEX_BYTES)

void                beryl_blocks_init(void);
const BerylBlockInfo *beryl_block_info(beryl_bid id);
const char         *beryl_block_name(beryl_bid id);
beryl_bid           beryl_block_by_name(const char *name); /* 0 if unknown */

static inline bool beryl_block_is_air(beryl_bid id)       { return id == BERYL_BLOCK_AIR; }
bool beryl_block_flag(beryl_bid id, enum BerylBlockFlag f);
bool beryl_block_is_opaque(beryl_bid id);
uint8_t beryl_block_light_emission(beryl_bid id);
uint8_t beryl_block_light_attenuation(beryl_bid id);
/* True when skylight may travel straight down through this block. */
bool beryl_block_propagates_skylight_down(beryl_bid id);

/* Face culling rule (the mesher's only reason to look at two blocks):
 *  - a neighbour that is fully opaque hides the face,
 *  - BFLG_SAME_TYPE_CULL additionally hides faces shared between two blocks of
 *    the same kind -- this is the "leaves internal-face culling" idea Beryllium
 *    ships for vanilla meshing, applied here at mesh-build time where it is free. */
bool beryl_face_visible(beryl_bid self, beryl_bid neighbour);

/* --------------------------------------------------------- texture array ---- */
/* Writes BERYL_TILE_LAYERS layers of 16x16 RGBA8 texels, layer-major (the exact
 * memory image a texture array upload wants). Deterministic for a given seed. */
void beryl_texarray_generate(uint8_t *rgba_out, uint32_t seed);
int  beryl_texarray_layer_count(void);
/* Average colour of one layer (used for distance fog tinting and for the
 * "no textures" debug view). `array` is a buffer produced by
 * beryl_texarray_generate(). */
void beryl_texarray_average_color(const uint8_t *array, int layer,
                                  float out_rgb[3], float *alpha_out);

/* ---------------------------------------------------------------- tint ------ */
#define BERYL_TINT_COUNT 8
/* RGB multipliers indexed by BerylBlockInfo.tint_index (1.0 = untinted). */
void beryl_tint_palette(float out[BERYL_TINT_COUNT][3]);

/* ------------------------------------------------------------ lightmap ------ */
/* Vanilla's 16x16 sky/block-light LUT. `daylight` in [0,1] shifts the sky ramp
 * from night (deep blue) to noon (white). Sampled by the terrain shader with
 * (blockLight, skyLight) as coordinates. */
void beryl_lightmap_generate(uint8_t rgba_out[16 * 16 * 4], float daylight);

/* Ambient-occlusion brightness ramp, quantized to the 4 MC AO levels. */
float beryl_ao_multiplier(int ao_level /* 0..3 */);

#endif /* BERYL_BLOCKS_H */
