/* worldgen.h -- deterministic procedural terrain.
 *
 * Everything is a pure function of (seed, x, z) / (seed, x, y, z): no global
 * mutable state, no RNG stream that depends on generation order. That is what
 * lets the tests assert "same seed -> identical chunk", and what lets a
 * multiplayer client re-generate unloaded terrain without a region file.
 */
#ifndef BERYL_WORLDGEN_H
#define BERYL_WORLDGEN_H

#include "world.h"

typedef enum BerylBiome {
	BERYL_BIOME_OCEAN = 0,
	BERYL_BIOME_BEACH,
	BERYL_BIOME_PLAINS,
	BERYL_BIOME_FOREST,
	BERYL_BIOME_DESERT,
	BERYL_BIOME_SNOWY,
	BERYL_BIOME_MOUNTAINS,
	BERYL_BIOME_COUNT
} BerylBiome;

const char *beryl_biome_name(BerylBiome b);

/* Terrain parameters for a column. */
typedef struct BerylColumn {
	int32_t height;       /* first block above the ground surface */
	BerylBiome biome;
	float   temperature;  /* -1..1 */
	float   humidity;     /* -1..1 */
	int32_t water_y;      /* sea level if the column is under water, else -1 */
	int32_t cave_densities[4]; /* scratch for the cave pass, not persisted */
} BerylColumn;

void beryl_worldgen_column(const BerylWorldDesc *d, int32_t x, int32_t z, BerylColumn *out);
bool beryl_worldgen_cave(const BerylWorldDesc *d, int wx, int wy, int wz);
bool beryl_worldgen_tree(const BerylWorldDesc *d, int wx, int wz, int32_t surface_y,
                         BerylBiome biome, int *height_out);
/* Fills every section of the chunk (allocating as needed). */
void beryl_worldgen_generate_chunk(BerylWorld *w, BerylChunk *c);
/* A single section, exposed for tests and for incremental generation. */
void beryl_worldgen_generate_section(BerylWorld *w, BerylSection *s, int32_t cx, int32_t cz, int32_t sy);

#endif /* BERYL_WORLDGEN_H */
