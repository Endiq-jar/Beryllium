/* worldgen.c -- heightmap + biomes + caves + ores + trees + water. */
#include "worldgen.h"

#include <string.h>

const char *beryl_biome_name(BerylBiome b) {
	switch (b) {
		case BERYL_BIOME_OCEAN:    return "ocean";
		case BERYL_BIOME_BEACH:    return "beach";
		case BERYL_BIOME_PLAINS:   return "plains";
		case BERYL_BIOME_FOREST:   return "forest";
		case BERYL_BIOME_DESERT:   return "desert";
		case BERYL_BIOME_SNOWY:    return "snowy_plains";
		case BERYL_BIOME_MOUNTAINS:return "mountains";
		default:                   return "unknown";
	}
}

#define SEED_H    0x11u
#define SEED_BI   0x22u
#define SEED_CAVE 0x33u
#define SEED_ORE  0x44u
#define SEED_TREE 0x55u

static float terrain_amplitude(BerylBiome b) {
	switch (b) {
		case BERYL_BIOME_MOUNTAINS: return 34.0f;
		case BERYL_BIOME_PLAINS:    return  4.0f;
		case BERYL_BIOME_FOREST:    return  7.0f;
		case BERYL_BIOME_DESERT:    return  5.0f;
		case BERYL_BIOME_SNOWY:     return  6.0f;
		case BERYL_BIOME_BEACH:     return  2.0f;
		default:                    return  3.0f;
	}
}

void beryl_worldgen_column(const BerylWorldDesc *d, int32_t x, int32_t z, BerylColumn *out) {
	uint32_t seed = (uint32_t)(d->seed & 0xFFFFFFFFu);
	float fscale = 0.0075f;

	float cont  = beryl_fbm2((float)x * fscale, (float)z * fscale, seed + SEED_H, 4, 2.0f, 0.5f);
	float hills = beryl_fbm2((float)x * fscale * 3.0f, (float)z * fscale * 3.0f, seed + SEED_H + 7, 3, 2.0f, 0.45f);
	float temp  = beryl_noise2((float)x * 0.0035f, (float)z * 0.0035f, seed + SEED_BI);
	float hum   = beryl_noise2((float)x * 0.0041f + 91.7f, (float)z * 0.0041f - 33.1f, seed + SEED_BI + 1);

	out->temperature = temp;
	out->humidity = hum;

	float base = d->sea_level + cont * 14.0f + 6.0f;
	/* Biome choice from a coarse, low-frequency field so biomes come in blobs. */
	BerylBiome biome;
	if (base < d->sea_level - 3.0f)                 biome = BERYL_BIOME_OCEAN;
	else if (base < d->sea_level + 1.5f)            biome = BERYL_BIOME_BEACH;
	else if (temp > 0.42f && hum < 0.0f)            biome = BERYL_BIOME_DESERT;
	else if (temp < -0.35f)                         biome = BERYL_BIOME_SNOWY;
	else if (hills > 0.30f && cont > 0.28f)         biome = BERYL_BIOME_MOUNTAINS;
	else if (hum > 0.15f)                           biome = BERYL_BIOME_FOREST;
	else                                            biome = BERYL_BIOME_PLAINS;
	out->biome = biome;

	float h = base + hills * terrain_amplitude(biome) * 0.6f
	                 + beryl_fbm2((float)x * 0.03f, (float)z * 0.03f, seed + SEED_H + 21, 2, 2.0f, 0.5f)
	                   * (biome == BERYL_BIOME_MOUNTAINS ? 6.0f : 2.0f);
	int32_t surface = (int32_t)(h + 0.5f);
	out->height = BERYL_CLAMP(surface, 2, BERYL_WORLD_MAX_Y - 20);
	out->water_y = out->height - 1 >= (int32_t)d->sea_level ? -1 : (int32_t)d->sea_level;
}

bool beryl_worldgen_cave(const BerylWorldDesc *d, int wx, int wy, int wz) {
	if (wy < 4) return false;                       /* never hollow out the floor */
	uint32_t seed = (uint32_t)(d->seed & 0xFFFFFFFFu);
	/* Two ridged noise fields multiplied: thin, winding tunnels rather than
	 * the blobs plain value noise makes. */
	float a = beryl_noise3((float)wx * 0.032f, (float)wy * 0.055f, (float)wz * 0.032f, seed + SEED_CAVE);
	float b = beryl_noise3((float)wx * 0.032f + 40.0f, (float)wy * 0.055f - 17.0f, (float)wz * 0.032f + 63.0f,
	                       seed + SEED_CAVE + 5);
	float ridge = (1.0f - fabsf(a)) * (1.0f - fabsf(b));
	float limit = 0.86f;
	/* Caves get rarer towards the surface, and disappear above sea level. */
	float depth = (float)wy / (float)BERYL_WORLD_MAX_Y;
	limit += depth * 0.10f;
	if ((float)wy > d->sea_level - 4.0f) limit += 0.25f;
	return ridge > limit;
}

static int floor_div(int a, int b) { return a >= 0 ? a / b : -((-a + b - 1) / b); }

bool beryl_worldgen_tree(const BerylWorldDesc *d, int wx, int wz, int32_t surface_y,
                         BerylBiome biome, int *height_out) {
	if (biome != BERYL_BIOME_FOREST && biome != BERYL_BIOME_PLAINS && biome != BERYL_BIOME_SNOWY) {
		return false;
	}
	if (surface_y <= (int32_t)d->sea_level) return false;
	uint32_t seed = (uint32_t)(d->seed & 0xFFFFFFFFu);

	/* One candidate trunk per TREE_CELL x TREE_CELL cell, at a hashed position
	 * inside it. Trees therefore never touch each other and the question
	 * "is (x,z) a trunk" is answered by two hashes -- no cross-chunk state. */
	const int TREE_CELL = 5;
	float density = biome == BERYL_BIOME_FOREST ? 0.62f : 0.16f;
	int cellx = floor_div(wx, TREE_CELL), cellz = floor_div(wz, TREE_CELL);
	uint32_t h = beryl_hash3(cellx, cellz, seed + SEED_TREE);
	if ((float)(h & 0xFFFFu) / 65535.0f > density) return false;
	int ox = (int)((h >> 4) % (uint32_t)TREE_CELL);
	int oz = (int)((h >> 12) % (uint32_t)TREE_CELL);
	if (wx != cellx * TREE_CELL + ox || wz != cellz * TREE_CELL + oz) return false;
	if (height_out) *height_out = 4 + (int)((h >> 16) % 3u);
	return true;
}

/* ------------------------------------------------------------------ ores --- */
static beryl_bid ore_for(const BerylWorldDesc *d, int wx, int wy, int wz, uint32_t *h_out) {
	uint32_t seed = (uint32_t)(d->seed & 0xFFFFFFFFu);
	uint32_t h = beryl_hash4(wx, wy, wz, seed + SEED_ORE);
	if (h_out) *h_out = h;
	if (wy < 14 && (h % 640u) == 0)   return BERYL_BLOCK_DIAMOND_ORE;
	if (wy < 28 && (h % 420u) == 0)   return BERYL_BLOCK_GOLD_ORE;
	if (wy < 46 && ((h >> 8) % 190u) == 0) return BERYL_BLOCK_IRON_ORE;
	if (wy < 64 && (h % 150u) == 0)   return BERYL_BLOCK_COAL_ORE;
	return BERYL_BLOCK_STONE;
}

void beryl_worldgen_generate_section(BerylWorld *w, BerylSection *s, int32_t cx, int32_t cz, int32_t sy) {
	BerylWorldDesc d;
	beryl_world_desc(w, &d);
	int32_t base_y = sy * BERYL_SECTION_SIDE;

	for (int lz = 0; lz < BERYL_SECTION_SIDE; lz++) {
		for (int lx = 0; lx < BERYL_SECTION_SIDE; lx++) {
			int wx = cx * BERYL_SECTION_SIDE + lx;
			int wz = cz * BERYL_SECTION_SIDE + lz;
			BerylColumn col;
			beryl_worldgen_column(&d, wx, wz, &col);
			int32_t surf = col.height - 1;   /* y of the top ground block */

			for (int ly = 0; ly < BERYL_SECTION_SIDE; ly++) {
				int wy = base_y + ly;
				beryl_bid id = BERYL_BLOCK_AIR;

				if (wy <= surf) {
					int depth = surf - wy;
					if (wy <= 1) {
						id = BERYL_BLOCK_BEDROCK;
					} else if (wy == 2 && (beryl_hash3(wx, wz, 77u) % 5u) == 0) {
						id = BERYL_BLOCK_BEDROCK;
					} else if (depth == 0) {
						switch (col.biome) {
							case BERYL_BIOME_DESERT: id = BERYL_BLOCK_SAND; break;
							case BERYL_BIOME_SNOWY:  id = BERYL_BLOCK_SNOW; break;
							case BERYL_BIOME_BEACH:
							case BERYL_BIOME_OCEAN:  id = BERYL_BLOCK_SAND; break;
							default:                 id = BERYL_BLOCK_GRASS_BLOCK; break;
						}
					} else if (depth < 4) {
						id = (col.biome == BERYL_BIOME_DESERT || col.biome == BERYL_BIOME_BEACH)
						       ? BERYL_BLOCK_SAND : BERYL_BLOCK_DIRT;
					} else if (depth < 7 && (beryl_hash3(wx, wz, 91u + (uint32_t)wy) % 9u) == 0) {
						id = BERYL_BLOCK_CLAY;
					} else {
						id = ore_for(&d, wx, wy, wz, NULL);
						if (id == BERYL_BLOCK_STONE && wy < 12) {
							id = (beryl_hash4(wx, wy, wz, 1234u) % 60u == 0)
							     ? BERYL_BLOCK_LAVA : BERYL_BLOCK_STONE;
						}
					}
					if (id == BERYL_BLOCK_STONE && beryl_worldgen_cave(&d, wx, wy, wz)) {
						id = wy < 10 ? BERYL_BLOCK_LAVA : BERYL_BLOCK_AIR;
					}
				} else if (wy <= (int32_t)d.sea_level && col.water_y > 0) {
					id = BERYL_BLOCK_WATER;
				}

				if (id != BERYL_BLOCK_AIR) {
					s->states[beryl_section_index(lx, ly, lz)] = (beryl_state)id;
				}
			}
		}
	}

	/* Trees are written after the terrain pass so the canopy can overwrite air
	 * without disturbing the column sampling. The scan runs 2 blocks past the
	 * section so a trunk in a neighbour still deposits leaves here; writes are
	 * clipped, so a trunk shared by two sections is written identically by both
	 * (determinism does not depend on generation order). */
	if (d.trees) {
		for (int tz = -2; tz < BERYL_SECTION_SIDE + 2; tz++) {
			for (int tx = -2; tx < BERYL_SECTION_SIDE + 2; tx++) {
				int wx = cx * BERYL_SECTION_SIDE + tx;
				int wz = cz * BERYL_SECTION_SIDE + tz;
				BerylColumn col;
				beryl_worldgen_column(&d, wx, wz, &col);
				int trunk_h = 5;
				if (!beryl_worldgen_tree(&d, wx, wz, col.height - 1, col.biome, &trunk_h)) {
					continue;
				}
				beryl_bid foliage = col.biome == BERYL_BIOME_SNOWY  ? BERYL_BLOCK_SPRUCE_LEAVES
				                    : col.biome == BERYL_BIOME_PLAINS ? BERYL_BLOCK_BIRCH_LEAVES
				                                                      : BERYL_BLOCK_OAK_LEAVES;
				int ground_top = col.height - 1;
				for (int rel = 0; rel <= trunk_h + 2; rel++) {
					int wy = ground_top + 1 + rel;
					if (wy < base_y || wy >= base_y + BERYL_SECTION_SIDE) continue;
					int ly = wy - base_y;
					if (rel < trunk_h) {
						/* trunk column */
						if (tx < 0 || tx >= BERYL_SECTION_SIDE || tz < 0 || tz >= BERYL_SECTION_SIDE) {
							continue;
						}
						s->states[beryl_section_index(tx, ly, tz)] = (beryl_state)BERYL_BLOCK_OAK_LOG;
						continue;
					}
					/* canopy: 5x5, 5x5, then 3x3 cap; the trunk pokes through the middle */
					int layer = rel - trunk_h;
					int reach = layer >= 2 ? 1 : 2;
					for (int dz = -reach; dz <= reach; dz++) {
						for (int dx = -reach; dx <= reach; dx++) {
							if (layer < 2 && dx == -reach && dz == -reach) continue; /* clip corners */
							if (layer < 2 && dx == reach && dz == reach) continue;
							int lx = tx + dx, lz = tz + dz;
							if (lx < 0 || lx >= BERYL_SECTION_SIDE || lz < 0 || lz >= BERYL_SECTION_SIDE) {
								continue;
							}
							size_t i = (size_t)beryl_section_index(lx, ly, lz);
							if (s->states[i] != BERYL_BLOCK_AIR) continue;
							s->states[i] = (beryl_state)foliage;
						}
					}
				}
			}
		}
	}
}

void beryl_worldgen_generate_chunk(BerylWorld *w, BerylChunk *c) {
	for (int sy = 0; sy < BERYL_CHUNK_SECTIONS; sy++) {
		BerylSection *s = beryl_chunk_section_at(c, sy, true);
		memset(s->states, 0, sizeof(s->states));
		memset(s->light, 0, sizeof(s->light));
		beryl_worldgen_generate_section(w, s, c->x, c->z, sy);
	}
	/* The derived masks (opaque/solid/occluder columns, all_air, min/max y) are
	 * what the culler and the height map read, so they have to be built before
	 * anything can ask "is this section empty". */
	for (int sy = 0; sy < BERYL_CHUNK_SECTIONS; sy++) {
		BerylSection *s = c->sections[sy];
		if (s) beryl_section_recompute_derived(s);
	}
	beryl_chunk_rebuild_top_map(c);
	for (int sy = 0; sy < BERYL_CHUNK_SECTIONS; sy++) {
		BerylSection *s = c->sections[sy];
		if (s) { s->dirty = true; s->revision++; s->light_dirty = true; }
	}
}
