/* blocks.c -- registry data + procedural texture generation. */
#include "blocks.h"

#include <string.h>

/* ----------------------------------------------------------- tile layout --- */
enum BerylTile {
	T_STONE = 0, T_DIRT, T_GRASS_TOP, T_GRASS_SIDE, T_COBBLE, T_MOSSY,
	T_SAND, T_GRAVEL, T_CLAY, T_SANDSTONE_TOP, T_SANDSTONE_SIDE,
	T_OAK_LOG_SIDE, T_OAK_LOG_TOP, T_PLANKS, T_OAK_LEAVES,
	T_BIRCH_LOG_SIDE, T_BIRCH_LOG_TOP, T_BIRCH_LEAVES, T_SPRUCE_LEAVES,
	T_BRICKS, T_GLASS, T_SNOW, T_ICE,
	T_COAL_ORE, T_IRON_ORE, T_GOLD_ORE, T_DIAMOND_ORE,
	T_GLOWSTONE, T_TORCH, T_WATER, T_LAVA, T_BEDROCK,
	T_COUNT
};

/* --------------------------------------------------------------- registry -- */
/* The registry is filled by registration rather than a giant designated
 * initializer: it keeps the table below readable, and gives every block a
 * single obvious row. Order is frozen (see blocks.h). */
static BerylBlockInfo g_blocks[BERYL_BLOCK_COUNT];
static bool g_blocks_ready = false;

typedef struct BerylBlockSeed {
	beryl_bid      id;
	const char    *name;
	uint32_t       flags;
	uint8_t        top, bottom, side;
	uint8_t        tint, emission, attenuation, skylight_filter, render_layer;
} BerylBlockSeed;

/* flags: O = opaque cube, T = translucent blend, C = alpha-cutout
 *   + same-type interior face culling (Beryllium's leaf-cull idea)          */
#define CUBE (BERYL_BFLG_OPAQUE | BERYL_BFLG_SOLID)
#define FLAT 0
#define ROW(i, nm, fl, tp, bt, sd, tn, em, at, sk, ly) \
	{ i, nm, fl, tp, bt, sd, tn, em, at, sk, ly }

static const BerylBlockSeed k_seeds[] = {
	ROW(BERYL_BLOCK_AIR,               "air",                FLAT, 0, 0, 0,                             0,  0,  0,  0, 0),
	ROW(BERYL_BLOCK_STONE,             "stone",              CUBE, T_STONE, T_STONE, T_STONE,           0,  0, 15, 15, 0),
	ROW(BERYL_BLOCK_DIRT,              "dirt",               CUBE, T_DIRT, T_DIRT, T_DIRT,               0,  0, 15, 15, 0),
	ROW(BERYL_BLOCK_GRASS_BLOCK,       "grass_block",        CUBE, T_GRASS_TOP, T_DIRT, T_GRASS_SIDE,    1,  0, 15, 15, 0),
	ROW(BERYL_BLOCK_COBBLESTONE,       "cobblestone",        CUBE, T_COBBLE, T_COBBLE, T_COBBLE,         0,  0, 15, 15, 0),
	ROW(BERYL_BLOCK_MOSSY_COBBLESTONE, "mossy_cobblestone",  CUBE, T_MOSSY, T_MOSSY, T_MOSSY,            0,  0, 15, 15, 0),
	ROW(BERYL_BLOCK_SAND,              "sand",               CUBE, T_SAND, T_SAND, T_SAND,               0,  0, 15, 15, 0),
	ROW(BERYL_BLOCK_GRAVEL,            "gravel",             CUBE, T_GRAVEL, T_GRAVEL, T_GRAVEL,         0,  0, 15, 15, 0),
	ROW(BERYL_BLOCK_CLAY,              "clay",               CUBE, T_CLAY, T_CLAY, T_CLAY,               0,  0, 15, 15, 0),
	ROW(BERYL_BLOCK_SANDSTONE,         "sandstone",          CUBE, T_SANDSTONE_TOP, T_SANDSTONE_TOP, T_SANDSTONE_SIDE, 0, 0, 15, 15, 0),
	ROW(BERYL_BLOCK_OAK_LOG,           "oak_log",            CUBE, T_OAK_LOG_TOP, T_OAK_LOG_TOP, T_OAK_LOG_SIDE,      0, 0, 15, 15, 0),
	ROW(BERYL_BLOCK_OAK_PLANKS,        "oak_planks",         CUBE, T_PLANKS, T_PLANKS, T_PLANKS,         0,  0, 15, 15, 0),
	ROW(BERYL_BLOCK_OAK_LEAVES,        "oak_leaves",         BERYL_BFLG_CUTOUT | BERYL_BFLG_SAME_TYPE_CULL,
	                                                        T_OAK_LEAVES, T_OAK_LEAVES, T_OAK_LEAVES,    2,  0,  1,  1, 1),
	ROW(BERYL_BLOCK_BIRCH_LOG,         "birch_log",          CUBE, T_BIRCH_LOG_TOP, T_BIRCH_LOG_TOP, T_BIRCH_LOG_SIDE, 0, 0, 15, 0, 0),
	ROW(BERYL_BLOCK_BIRCH_LEAVES,      "birch_leaves",       BERYL_BFLG_CUTOUT | BERYL_BFLG_SAME_TYPE_CULL,
	                                                        T_BIRCH_LEAVES, T_BIRCH_LEAVES, T_BIRCH_LEAVES, 3, 0, 1, 1, 1),
	ROW(BERYL_BLOCK_SPRUCE_LEAVES,     "spruce_leaves",      BERYL_BFLG_CUTOUT | BERYL_BFLG_SAME_TYPE_CULL,
	                                                        T_SPRUCE_LEAVES, T_SPRUCE_LEAVES, T_SPRUCE_LEAVES, 4, 0, 1, 1, 1),
	ROW(BERYL_BLOCK_BRICKS,            "bricks",             CUBE, T_BRICKS, T_BRICKS, T_BRICKS,         0,  0, 15, 15, 0),
	ROW(BERYL_BLOCK_GLASS,             "glass",              BERYL_BFLG_TRANSLUCENT | BERYL_BFLG_SAME_TYPE_CULL,
	                                                        T_GLASS, T_GLASS, T_GLASS,                   0,  0,  0,  0, 2),
	ROW(BERYL_BLOCK_SNOW,              "snow_block",         CUBE, T_SNOW, T_SNOW, T_SNOW,               0,  0, 15, 15, 0),
	ROW(BERYL_BLOCK_ICE,               "ice",                BERYL_BFLG_TRANSLUCENT | BERYL_BFLG_SAME_TYPE_CULL,
	                                                        T_ICE, T_ICE, T_ICE,                          0,  0,  2,  2, 2),
	ROW(BERYL_BLOCK_COAL_ORE,          "coal_ore",           CUBE, T_COAL_ORE, T_COAL_ORE, T_COAL_ORE,   0,  0, 15, 15, 0),
	ROW(BERYL_BLOCK_IRON_ORE,          "iron_ore",           CUBE, T_IRON_ORE, T_IRON_ORE, T_IRON_ORE,   0,  0, 15, 15, 0),
	ROW(BERYL_BLOCK_GOLD_ORE,          "gold_ore",           CUBE, T_GOLD_ORE, T_GOLD_ORE, T_GOLD_ORE,   0,  0, 15, 15, 0),
	ROW(BERYL_BLOCK_DIAMOND_ORE,       "diamond_ore",        CUBE, T_DIAMOND_ORE, T_DIAMOND_ORE, T_DIAMOND_ORE, 0, 0, 15, 15, 0),
	ROW(BERYL_BLOCK_GLOWSTONE,         "glowstone",          CUBE, T_GLOWSTONE, T_GLOWSTONE, T_GLOWSTONE, 0, 15, 15, 15, 0),
	ROW(BERYL_BLOCK_TORCH,             "torch",              BERYL_BFLG_CUTOUT, T_TORCH, T_TORCH, T_TORCH, 0, 14, 0, 0, 1),
	ROW(BERYL_BLOCK_WATER,             "water",              BERYL_BFLG_TRANSLUCENT | BERYL_BFLG_SAME_TYPE_CULL,
	                                                        T_WATER, T_WATER, T_WATER,                    5,  0,  1,  1, 2),
	ROW(BERYL_BLOCK_LAVA,              "lava",               CUBE, T_LAVA, T_LAVA, T_LAVA,               0, 15, 15, 15, 0),
	ROW(BERYL_BLOCK_BEDROCK,           "bedrock",            CUBE, T_BEDROCK, T_BEDROCK, T_BEDROCK,      0,  0, 15, 15, 0),
};

void beryl_blocks_init(void) {
	memset(g_blocks, 0, sizeof(g_blocks));
	g_blocks[BERYL_BLOCK_AIR].name = "air";
	g_blocks[BERYL_BLOCK_AIR].flags = 0;
	g_blocks[BERYL_BLOCK_AIR].light_attenuation = 0;
	for (size_t i = 0; i < sizeof(k_seeds) / sizeof(k_seeds[0]); i++) {
		const BerylBlockSeed *s = &k_seeds[i];
		BERYL_ASSERT(s->id < BERYL_BLOCK_COUNT, "seed id %u out of range", s->id);
		BerylBlockInfo *b = &g_blocks[s->id];
		b->name = s->name;
		b->flags = s->flags;
		b->tiles[BERYL_FACE_DOWN]  = s->bottom;
		b->tiles[BERYL_FACE_UP]    = s->top;
		b->tiles[BERYL_FACE_NORTH] = s->side;
		b->tiles[BERYL_FACE_SOUTH] = s->side;
		b->tiles[BERYL_FACE_WEST]  = s->side;
		b->tiles[BERYL_FACE_EAST]  = s->side;
		b->tint_index = s->tint;
		b->light_emission = s->emission;
		b->light_attenuation = s->attenuation;
		b->skylight_filter = s->skylight_filter;
		b->render_layer = s->render_layer;
	}
	g_blocks_ready = true;
}

const BerylBlockInfo *beryl_block_info(beryl_bid id) {
	if (!g_blocks_ready) {
		beryl_blocks_init();
	}
	if (id >= BERYL_BLOCK_COUNT) {
		return &g_blocks[BERYL_BLOCK_AIR];
	}
	return &g_blocks[id];
}

const char *beryl_block_name(beryl_bid id) { return beryl_block_info(id)->name; }

beryl_bid beryl_block_by_name(const char *name) {
	if (!name) return BERYL_BLOCK_AIR;
	for (int i = 0; i < BERYL_BLOCK_COUNT; i++) {
		if (strcmp(g_blocks[i].name, name) == 0) return (beryl_bid)i;
	}
	return BERYL_BLOCK_AIR;
}

bool beryl_block_flag(beryl_bid id, enum BerylBlockFlag f) {
	return (beryl_block_info(id)->flags & (uint32_t)f) != 0;
}

bool beryl_block_is_opaque(beryl_bid id) {
	return beryl_block_flag(id, BERYL_BFLG_OPAQUE);
}

uint8_t beryl_block_light_emission(beryl_bid id)     { return beryl_block_info(id)->light_emission; }
uint8_t beryl_block_light_attenuation(beryl_bid id) { return beryl_block_info(id)->light_attenuation; }

bool beryl_block_propagates_skylight_down(beryl_bid id) {
	return beryl_block_info(id)->skylight_filter < 15;
}

bool beryl_face_visible(beryl_bid self, beryl_bid neighbour) {
	if (neighbour == BERYL_BLOCK_AIR) {
		return true;
	}
	const BerylBlockInfo *si = beryl_block_info(self);
	const BerylBlockInfo *ni = beryl_block_info(neighbour);
	/* Same-type interior faces: free win, no visual difference for the block
	 * kinds flagged for it (leaves, glass, water, ice). */
	if (self == neighbour && (si->flags & BERYL_BFLG_SAME_TYPE_CULL)) {
		return false;
	}
	/* An opaque neighbour always wins. A translucent one never hides a face, so
	 * blocks sitting in water still draw their sides. */
	if ((ni->flags & BERYL_BFLG_OPAQUE) && !(si->flags & BERYL_BFLG_TRANSLUCENT)) {
		return false;
	}
	if (si->flags & BERYL_BFLG_TRANSLUCENT) {
		/* Translucent against translucent: only the air/ground boundary matters. */
		return !(ni->flags & BERYL_BFLG_OPAQUE);
	}
	return true;
}

BerylVec3 beryl_face_normal(BerylFace f) {
	switch (f) {
		case BERYL_FACE_DOWN:  return beryl_vec3(0.0f, -1.0f, 0.0f);
		case BERYL_FACE_UP:    return beryl_vec3(0.0f,  1.0f, 0.0f);
		case BERYL_FACE_NORTH: return beryl_vec3(0.0f,  0.0f, -1.0f);
		case BERYL_FACE_SOUTH: return beryl_vec3(0.0f,  0.0f,  1.0f);
		case BERYL_FACE_WEST:  return beryl_vec3(-1.0f, 0.0f, 0.0f);
		default:               return beryl_vec3( 1.0f, 0.0f, 0.0f);
	}
}

float beryl_face_shade(BerylFace f) {
	switch (f) {
		case BERYL_FACE_UP:   return 1.00f;
		case BERYL_FACE_NORTH:
		case BERYL_FACE_SOUTH: return 0.80f;
		case BERYL_FACE_WEST:
		case BERYL_FACE_EAST:  return 0.60f;
		default:              return 0.50f; /* DOWN */
	}
}

/* Vanilla packs 4 AO corners per vertex; the shader ramps them 0.4..1.0. */
float beryl_ao_multiplier(int ao_level) {
	static const float k[4] = { 0.43f, 0.68f, 0.84f, 1.0f };
	return ao_level < 0 ? 1.0f : (ao_level > 3 ? 1.0f : k[ao_level]);
}

/* ------------------------------------------------------------------- tiles -- */
static uint8_t clamp8i(int v) { return (uint8_t)(v < 0 ? 0 : (v > 255 ? 255 : v)); }
static uint32_t hpx(int gx, int gy, uint32_t s) { return beryl_hash3(gx, gy, s); }

static void put(uint8_t *atlas, int tile, int x, int y, int r, int g, int b, int a) {
	if (tile < 0 || tile >= T_COUNT) return;
	size_t o = ((size_t)tile * BERYL_TEX_BYTES) + ((size_t)y * BERYL_TILE_SIZE + (size_t)x) * 4u;
	atlas[o + 0] = clamp8i(r);
	atlas[o + 1] = clamp8i(g);
	atlas[o + 2] = clamp8i(b);
	atlas[o + 3] = clamp8i(a);
}

/* Grainy monochrome fill, the base of the stone family. */
static void paint_grain(uint8_t *atlas, int tile, int r, int g, int b, int amp, uint32_t seed) {
	for (int y = 0; y < BERYL_TILE_SIZE; y++) {
		for (int x = 0; x < BERYL_TILE_SIZE; x++) {
			uint32_t h = hpx((tile * 16) + x, y, seed);
			int j = (int)(h % (uint32_t)(2 * amp + 1)) - amp;
			int s = (int)(h >> 20) & 3;           /* sparse dark specks */
			int d = s == 0 ? -amp * 2 : 0;
			put(atlas, tile, x, y, r + j + d, g + j + d, b + j + d, 255);
		}
	}
}

static void paint_cobble(uint8_t *atlas, int tile, int base, int moss, uint32_t seed) {
	for (int y = 0; y < BERYL_TILE_SIZE; y++) {
		for (int x = 0; x < BERYL_TILE_SIZE; x++) {
			/* cellular-ish: quantize a low-frequency field into stones + seams */
			int cx = x / 5, cy = y / 5;
			uint32_t hc = beryl_hash3(cx, cy, seed);
			int ox = (int)(hc % 3u) - 1, oy = (int)((hc >> 4) % 3u) - 1;
			int dx = x - (cx * 5 + 2 + ox), dy = y - (cy * 5 + 2 + oy);
			int d = dx * dx + dy * dy;
			int v = base + (int)(hpx(x + tile * 16, y, seed >> 3) % 22u) - 11;
			if (d > 6) v -= 38;                    /* mortar */
			put(atlas, tile, x, y, v, v + moss, v - moss / 2, 255);
		}
	}
}

static void paint_ore(uint8_t *atlas, int tile, int r, int g, int b, uint32_t seed) {
	paint_grain(atlas, tile, 125, 125, 128, 14, seed ^ 0x51u);
	for (int i = 0; i < 5; i++) {
		uint32_t hb = beryl_hash3(i, tile, seed);
		int bx = (int)(hb % 12u) + 2, by = (int)((hb >> 8) % 12u) + 2;
		int sz = 2 + (int)((hb >> 16) % 2u);
		for (int dy = -sz; dy <= sz; dy++) {
			for (int dx = -sz; dx <= sz; dx++) {
				if (dx * dx + dy * dy > sz * sz + 1) continue;
				int j = (int)(beryl_hash3(bx + dx, by + dy, seed) % 30u) - 15;
				put(atlas, tile, bx + dx, by + dy, r + j, g + j, b + j, 255);
			}
		}
	}
}

static void paint_leaves(uint8_t *atlas, int tile, int r, int g, int b, uint32_t seed) {
	for (int y = 0; y < BERYL_TILE_SIZE; y++) {
		for (int x = 0; x < BERYL_TILE_SIZE; x++) {
			uint32_t h = hpx(x + tile * 16, y, seed);
			int holes = (int)((h >> 12) % 11u);
			if (holes == 0) {                       /* alpha-tested gap */
				put(atlas, tile, x, y, 0, 0, 0, 0);
				continue;
			}
			int j = (int)(h % 46u) - 23;
			int blade = ((x + y * 2) % 5 == 0) ? -14 : 0;
			/* grayscale-ish so the biome tint multiplication reads well */
			int lum = 150 + j + blade;
			put(atlas, tile, x, y, lum * r / 128, lum * g / 128, lum * b / 128, 255);
		}
	}
}

static void paint_log(uint8_t *atlas, int tile, int r, int g, int b, bool top, uint32_t seed) {
	for (int y = 0; y < BERYL_TILE_SIZE; y++) {
		for (int x = 0; x < BERYL_TILE_SIZE; x++) {
			uint32_t h = hpx(x + tile * 16, y, seed);
			int j = (int)(h % 26u) - 13;
			if (top) {
				int dx = x - 8, dy = y - 8;
				int ring = ((int)sqrtf((float)(dx * dx + dy * dy)) + (j > 0 ? 1 : 0)) % 3;
				int k = ring == 0 ? 26 : ring == 1 ? -8 : -22;
				put(atlas, tile, x, y, r + k + j / 2, g + k + j / 2, b + k + j / 2, 255);
			} else {
				int stripe = (x % 4 == 0) ? -20 : (x % 4 == 2 ? 10 : 0);
				int knot = ((y % 7 == 3) && (x % 5 == 1)) ? -26 : 0;
				put(atlas, tile, x, y, r + j + stripe + knot,
				    g + j + stripe + knot, b + j + stripe + knot, 255);
			}
		}
	}
}

static void paint_planks(uint8_t *atlas, int tile, int r, int g, int b, uint32_t seed) {
	for (int y = 0; y < BERYL_TILE_SIZE; y++) {
		for (int x = 0; x < BERYL_TILE_SIZE; x++) {
			uint32_t h = hpx(x + tile * 16, y, seed);
			int j = (int)(h % 18u) - 9;
			int seam = (y % 8 == 7) ? -34 : 0;
			int grain = ((x + (y / 8) * 3) % 6 == 0) ? -12 : 0;
			put(atlas, tile, x, y, r + j + seam + grain, g + j + seam + grain, b + j + seam + grain, 255);
		}
	}
}

static void paint_bricks(uint8_t *atlas, int tile, uint32_t seed) {
	for (int y = 0; y < BERYL_TILE_SIZE; y++) {
		for (int x = 0; x < BERYL_TILE_SIZE; x++) {
			int row = y / 4;
			int off = (row & 1) ? 4 : 0;
			bool mortar = (y % 4 == 3) || (((x + off) % 8) == 7);
			uint32_t h = hpx(x + tile * 16, y, seed);
			int j = (int)(h % 20u) - 10;
			if (mortar) put(atlas, tile, x, y, 150 + j, 140 + j, 130 + j, 255);
			else        put(atlas, tile, x, y, 150 + j, 78 + j, 62 + j, 255);
		}
	}
}

void beryl_texarray_generate(uint8_t *rgba_out, uint32_t seed) {
	memset(rgba_out, 0, BERYL_ARRAY_BYTES);

	paint_grain(rgba_out, T_STONE, 125, 125, 128, 15, seed + 1);
	paint_grain(rgba_out, T_DIRT, 134, 96, 67, 20, seed + 2);
	/* grass top is intentionally near-white: the biome tint supplies the green */
	paint_grain(rgba_out, T_GRASS_TOP, 178, 196, 150, 16, seed + 3);
	for (int y = 0; y < BERYL_TILE_SIZE; y++) {
		for (int x = 0; x < BERYL_TILE_SIZE; x++) {
			uint32_t h = hpx(x, y, seed + 4);
			int j = (int)(h % 22u) - 11;
			int fringe = (int)(beryl_hash3(x, 0, seed + 5) % 4u) + 2;
			if (y < fringe) put(rgba_out, T_GRASS_SIDE, x, y, 172 + j, 190 + j, 146 + j, 255);
			else            put(rgba_out, T_GRASS_SIDE, x, y, 134 + j, 96 + j, 67 + j, 255);
		}
	}
	paint_cobble(rgba_out, T_COBBLE, 118, 0, seed + 6);
	paint_cobble(rgba_out, T_MOSSY, 106, 26, seed + 7);
	paint_grain(rgba_out, T_SAND, 219, 207, 160, 12, seed + 8);
	paint_grain(rgba_out, T_GRAVEL, 132, 129, 126, 22, seed + 9);
	paint_grain(rgba_out, T_CLAY, 160, 166, 179, 10, seed + 10);
	paint_grain(rgba_out, T_SANDSTONE_TOP, 216, 203, 155, 10, seed + 11);
	for (int y = 0; y < BERYL_TILE_SIZE; y++) {
		for (int x = 0; x < BERYL_TILE_SIZE; x++) {
			int band = (y < 2 || y > 13) ? 14 : 0;
			int j = (int)(hpx(x, y, seed + 12) % 12u) - 6;
			put(rgba_out, T_SANDSTONE_SIDE, x, y, 214 + band + j, 201 + band + j, 152 + j, 255);
		}
	}
	paint_log(rgba_out, T_OAK_LOG_SIDE, 102, 81, 50, false, seed + 13);
	paint_log(rgba_out, T_OAK_LOG_TOP, 153, 126, 78, true, seed + 13);
	paint_log(rgba_out, T_BIRCH_LOG_SIDE, 200, 196, 186, false, seed + 14);
	paint_log(rgba_out, T_BIRCH_LOG_TOP, 168, 148, 106, true, seed + 14);
	paint_planks(rgba_out, T_PLANKS, 162, 130, 78, seed + 15);
	paint_leaves(rgba_out, T_OAK_LEAVES, 120, 200, 90, seed + 16);
	paint_leaves(rgba_out, T_BIRCH_LEAVES, 140, 205, 110, seed + 17);
	paint_leaves(rgba_out, T_SPRUCE_LEAVES, 108, 176, 118, seed + 18);
	paint_bricks(rgba_out, T_BRICKS, seed + 19);
	for (int y = 0; y < BERYL_TILE_SIZE; y++) {
		for (int x = 0; x < BERYL_TILE_SIZE; x++) {
			bool border = (x == 0 || y == 0 || x == 15 || y == 15);
			bool shine = ((x - y) == 6 || (x - y) == 7) && x > 2 && x < 13;
			if (border) put(rgba_out, T_GLASS, x, y, 200, 226, 236, 210);
			else if (shine) put(rgba_out, T_GLASS, x, y, 220, 240, 248, 90);
			else put(rgba_out, T_GLASS, x, y, 180, 215, 230, 26);
		}
	}
	paint_grain(rgba_out, T_SNOW, 240, 246, 250, 7, seed + 21);
	for (int y = 0; y < BERYL_TILE_SIZE; y++) {
		for (int x = 0; x < BERYL_TILE_SIZE; x++) {
			int j = (int)(hpx(x, y, seed + 22) % 16u) - 8;
			int crack = (((x * 3 + y * 5) % 13) == 0) ? -26 : 0;
			put(rgba_out, T_ICE, x, y, 150 + j + crack, 190 + j + crack, 230 + j + crack, 216);
		}
	}
	paint_ore(rgba_out, T_COAL_ORE, 32, 30, 30, seed + 24);
	paint_ore(rgba_out, T_IRON_ORE, 196, 152, 110, seed + 25);
	paint_ore(rgba_out, T_GOLD_ORE, 240, 210, 90, seed + 26);
	paint_ore(rgba_out, T_DIAMOND_ORE, 110, 230, 226, seed + 27);
	for (int y = 0; y < BERYL_TILE_SIZE; y++) {
		for (int x = 0; x < BERYL_TILE_SIZE; x++) {
			int j = (int)(hpx(x, y, seed + 28) % 40u) - 20;
			int blob = ((x / 4 + y / 4) % 3 == 0) ? 30 : 0;
			put(rgba_out, T_GLOWSTONE, x, y, 200 + j + blob, 160 + j + blob, 70 + j, 255);
		}
	}
	for (int y = 0; y < BERYL_TILE_SIZE; y++) {
		for (int x = 0; x < BERYL_TILE_SIZE; x++) {
			int j = (int)(hpx(x, y, seed + 30) % 26u) - 13;
			bool stick = (x == 7 || x == 8) && y >= 7;
			bool flame = y >= 2 && y <= 5 && x >= 6 && x <= 9 &&
			             ((x + y) % 2 == 0 || (x >= 7 && x <= 8));
			if (flame)      put(rgba_out, T_TORCH, x, y, 255, 210 + j, 120, 255);
			else if (stick) put(rgba_out, T_TORCH, x, y, 140 + j, 105 + j, 66 + j, 255);
			else            put(rgba_out, T_TORCH, x, y, 0, 0, 0, 0);
		}
	}
	for (int y = 0; y < BERYL_TILE_SIZE; y++) {
		for (int x = 0; x < BERYL_TILE_SIZE; x++) {
			int j = (int)(hpx(x, y, seed + 31) % 20u) - 10;
			int wave = (int)(sinf((float)(x + y) * 0.8f) * 12.0f);
			put(rgba_out, T_WATER, x, y, 90 + j, 130 + j + wave / 3, 210 + j + wave, 175);
			int l = (int)(hpx(x, y, seed + 32 + (y / 3)) % 40u) - 20;
			int blob = ((x / 5 + y / 4) % 4 == 0) ? 40 : 0;
			put(rgba_out, T_LAVA, x, y, 220 + l + blob, 110 + l, 20 + l / 2, 255);
		}
	}
	paint_grain(rgba_out, T_BEDROCK, 70, 70, 74, 26, seed + 33);
	for (int y = 0; y < BERYL_TILE_SIZE; y++) {
		for (int x = 0; x < BERYL_TILE_SIZE; x++) {
			if ((int)(hpx(x, y, seed + 34) % 9u) == 0) {
				put(rgba_out, T_BEDROCK, x, y, 20, 20, 22, 255);
			}
		}
	}
}

int beryl_texarray_layer_count(void) { return BERYL_TILE_LAYERS; }

void beryl_texarray_average_color(const uint8_t *array, int layer,
                                  float out_rgb[3], float *alpha_out) {
	out_rgb[0] = out_rgb[1] = out_rgb[2] = 1.0f;
	if (alpha_out) *alpha_out = 1.0f;
	if (!array || layer < 0 || layer >= BERYL_TILE_LAYERS) return;
	const uint8_t *base = array + (size_t)layer * BERYL_TEX_BYTES;
	uint32_t sum[4] = { 0, 0, 0, 0 };
	for (int i = 0; i < BERYL_TILE_SIZE * BERYL_TILE_SIZE; i++) {
		for (int k = 0; k < 4; k++) sum[k] += base[i * 4 + k];
	}
	const uint32_t n = (uint32_t)(BERYL_TILE_SIZE * BERYL_TILE_SIZE);
	/* Premultiplied-ish average: transparent texels must not brighten the
	 * average of a cutout tile's silhouette. */
	float a = (float)sum[3] / (255.0f * (float)n);
	for (int k = 0; k < 3; k++) {
		out_rgb[k] = sum[k] ? (float)sum[k] / (float)sum[3] : 0.0f;
		if (sum[3] == 0) out_rgb[k] = 0.0f;
	}
	if (alpha_out) *alpha_out = a;
}

void beryl_tint_palette(float out[BERYL_TINT_COUNT][3]) {
	static const float k[6][3] = {
		{ 1.00f, 1.00f, 1.00f },  /* 0: untinted                     */
		{ 0.56f, 0.86f, 0.42f },  /* 1: grass                        */
		{ 0.49f, 0.83f, 0.36f },  /* 2: oak foliage                  */
		{ 0.60f, 0.86f, 0.45f },  /* 3: birch foliage                 */
		{ 0.45f, 0.70f, 0.50f },  /* 4: spruce foliage               */
		{ 0.36f, 0.52f, 0.86f },  /* 5: water                        */
	};
	for (int i = 0; i < BERYL_TINT_COUNT; i++) {
		out[i][0] = k[i < 6 ? i : 0][0];
		out[i][1] = k[i < 6 ? i : 0][1];
		out[i][2] = k[i < 6 ? i : 0][2];
	}
}

/* --------------------------------------------------------------- lightmap -- */
void beryl_lightmap_generate(uint8_t rgba_out[16 * 16 * 4], float daylight) {
	daylight = BERYL_CLAMP(daylight, 0.0f, 1.0f);
	for (int sky = 0; sky < 16; sky++) {
		for (int blk = 0; blk < 16; blk++) {
			float fs = (float)sky / 15.0f;
			float fb = (float)blk / 15.0f;
			/* Night sky keeps a cold floor; daylight ramps to full brightness.
			 * Block light adds a warm torch glow that never goes blue. */
			float sky_r = beryl_lerp(0.06f, 1.00f, fs) * beryl_lerp(0.34f, 1.0f, daylight);
			float sky_g = beryl_lerp(0.09f, 1.00f, fs) * beryl_lerp(0.38f, 1.0f, daylight);
			float sky_b = beryl_lerp(0.20f, 1.00f, fs) * beryl_lerp(0.58f, 1.0f, daylight);
			float warm = fb * fb * 0.9f + fb * 0.1f;
			float r = sky_r + warm * 1.00f;
			float g = sky_g + warm * 0.62f;
			float b = sky_b + warm * 0.34f;
			size_t o = ((size_t)sky * 16u + (size_t)blk) * 4u;
			rgba_out[o + 0] = clamp8i((int)(r * 255.0f));
			rgba_out[o + 1] = clamp8i((int)(g * 255.0f));
			rgba_out[o + 2] = clamp8i((int)(b * 255.0f));
			rgba_out[o + 3] = 255;
		}
	}
}
