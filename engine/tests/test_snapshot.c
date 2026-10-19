/* test_snapshot.c -- the Minecraft-to-engine section snapshot contract.
 *
 * This is the boundary between two programs that do not share a language, a
 * memory model or a release cycle, so it is tested the way a network protocol
 * would be: build valid buffers and check they round-trip, then feed truncated,
 * corrupt and hostile ones and check every single one is refused rather than
 * partially believed.
 *
 * Two properties matter more than the rest and are checked by name:
 *
 *   - A block the engine has never heard of must NOT resolve to air. The engine's
 *     registry holds about thirty blocks; Minecraft has tens of thousands of
 *     states. If an unknown name fell back to air, a modded world would arrive
 *     looking like it had been deleted. Unknown blocks fall back from their own
 *     material flags instead, so they stay solid, translucent or cutout.
 *   - A stale snapshot must be rejected. The whole point of versioning the
 *     snapshot is that the render thread can refuse a mesh whose inputs moved
 *     while it was being built.
 */
#include "test.h"

#include "bcore.h"
#include "blocks.h"
#include "chunk.h"
#include "snapshot.h"
#include "world.h"

#include <stdlib.h>
#include <string.h>

/* ------------------------------------------------------------- buffer ------ */
typedef struct {
	uint8_t *bytes;
	size_t   len;
	size_t   cap;
} Buf;

static void buf_init(Buf *b) { b->bytes = NULL; b->len = 0; b->cap = 0; }

static void buf_free(Buf *b) { free(b->bytes); buf_init(b); }

static bool buf_reserve(Buf *b, size_t extra) {
	if (b->len + extra <= b->cap) return true;
	size_t cap = b->cap ? b->cap : 256;
	while (cap < b->len + extra) cap *= 2;
	uint8_t *grown = (uint8_t *)realloc(b->bytes, cap);
	if (!grown) return false;
	b->bytes = grown;
	b->cap = cap;
	return true;
}

static bool buf_u8(Buf *b, uint8_t v) {
	if (!buf_reserve(b, 1)) return false;
	b->bytes[b->len++] = v;
	return true;
}

static bool buf_u16(Buf *b, uint16_t v) {
	if (!buf_reserve(b, 2)) return false;
	b->bytes[b->len++] = (uint8_t)(v & 0xFF);
	b->bytes[b->len++] = (uint8_t)((v >> 8) & 0xFF);
	return true;
}

static bool buf_u32(Buf *b, uint32_t v) {
	if (!buf_reserve(b, 4)) return false;
	for (int i = 0; i < 4; i++) b->bytes[b->len++] = (uint8_t)((v >> (8 * i)) & 0xFF);
	return true;
}

static bool buf_u64(Buf *b, uint64_t v) {
	return buf_u32(b, (uint32_t)(v & 0xFFFFFFFFu)) && buf_u32(b, (uint32_t)(v >> 32));
}

static bool buf_raw(Buf *b, const uint8_t *src, size_t n) {
	if (n == 0) return true;
	if (!buf_reserve(b, n)) return false;
	memcpy(b->bytes + b->len, src, n);
	b->len += n;
	return true;
}

static bool buf_str(Buf *b, const char *s) {
	size_t n = strlen(s);
	return buf_u16(b, (uint16_t)n) && buf_raw(b, (const uint8_t *)s, n);
}

/* --------------------------------------------------------- snapshot builder -- */
typedef struct {
	const char *name;
	uint32_t    flags;
	uint8_t     emission;
	uint8_t     layer;
	uint8_t     model;
	uint32_t    engine_id;
} PaletteSpec;

typedef struct {
	int32_t  sx, sy, sz;
	uint64_t world_id, revision, pack_generation;
	uint32_t flags;
	uint8_t  bits_per_entry;
	const PaletteSpec *palette;
	uint32_t palette_count;
	const uint16_t *indices;   /* BERYL_SECTION_VOL entries */
	const uint8_t  *light;     /* BERYL_SECTION_VOL entries, or NULL */
} SnapshotSpec;

static bool write_palette_entry(Buf *b, const PaletteSpec *p, uint32_t index) {
	return buf_u32(b, index)
	    && buf_u32(b, p->engine_id)
	    && buf_u32(b, p->flags)
	    && buf_u8(b, p->emission)
	    && buf_u8(b, 15)             /* attenuation */
	    && buf_u8(b, 15)             /* skylight filter */
	    && buf_u8(b, p->layer)
	    && buf_u8(b, p->model)
	    && buf_u8(b, 0) && buf_u8(b, 0) && buf_u8(b, 0)
	    && buf_str(b, p->name)
	    && buf_u32(b, 0);            /* no custom model blob */
}

/** Packs indices at the spec's bit width, little-endian, LSB first. */
static bool write_indices(Buf *b, const SnapshotSpec *s) {
	unsigned bits = s->bits_per_entry;
	size_t total_bits = (size_t)BERYL_SECTION_VOL * bits;
	size_t bytes = (total_bits + 7) / 8;
	uint8_t *packed = (uint8_t *)calloc(bytes ? bytes : 1, 1);
	if (!packed) return false;

	size_t bitpos = 0;
	for (int v = 0; v < BERYL_SECTION_VOL; v++) {
		uint32_t value = s->indices[v];
		for (unsigned i = 0; i < bits; i++) {
			/* Only the low 16 bits of a palette index can ever be meaningful, but
			 * the parser is tested with declared widths above that (it must refuse
			 * them). Shifting a uint32 by its own width or more is undefined, so
			 * the excess bits are written as zeroes: the buffer is still the size
			 * the declared width implies, which is all the refusal path needs. */
			unsigned bit = i < 16 ? (unsigned)((value >> i) & 1u) : 0u;
			if (bit) {
				packed[bitpos / 8] |= (uint8_t)(1u << (bitpos % 8));
			}
			bitpos++;
		}
	}
	bool ok = buf_raw(b, packed, bytes);
	free(packed);
	return ok;
}

static bool build_snapshot(Buf *b, const SnapshotSpec *s) {
	buf_init(b);
	if (!buf_u32(b, BERYL_SNAPSHOT_MAGIC)) return false;
	if (!buf_u32(b, BERYL_SNAPSHOT_VERSION)) return false;
	if (!buf_u32(b, s->flags)) return false;
	if (!buf_u32(b, (uint32_t)s->sx)) return false;
	if (!buf_u32(b, (uint32_t)s->sy)) return false;
	if (!buf_u32(b, (uint32_t)s->sz)) return false;
	if (!buf_u64(b, s->world_id)) return false;
	if (!buf_u64(b, s->revision)) return false;
	if (!buf_u64(b, s->pack_generation)) return false;
	if (!buf_u32(b, s->palette_count)) return false;
	if (!buf_u8(b, s->bits_per_entry)) return false;
	if (!buf_u8(b, 0) || !buf_u8(b, 0) || !buf_u8(b, 0)) return false;

	for (uint32_t i = 0; i < s->palette_count; i++) {
		if (!write_palette_entry(b, &s->palette[i], i)) return false;
	}
	if (!write_indices(b, s)) return false;
	if (s->flags & BERYL_SNAP_FLAG_HAS_LIGHT) {
		if (!buf_raw(b, s->light, BERYL_SECTION_VOL)) return false;
	}
	return true;
}

/* ------------------------------------------------------------- fixtures ---- */
static uint16_t g_indices[BERYL_SECTION_VOL];
static uint8_t  g_light[BERYL_SECTION_VOL];

/** Fills the lower half of the section with palette entry 1 and the rest with 0. */
static void fill_half(uint16_t *indices) {
	for (int v = 0; v < BERYL_SECTION_VOL; v++) {
		int ly = v / BERYL_SECTION_AREA;
		indices[v] = ly < 8 ? 1 : 0;
	}
}

static const PaletteSpec AIR_SPEC = {
	"minecraft:air", 0, 0, 0, BERYL_MODEL_AIR, 0
};
static const PaletteSpec STONE_SPEC = {
	"minecraft:stone", BERYL_BFLG_SOLID | BERYL_BFLG_OPAQUE, 0, 0, BERYL_MODEL_FULL_CUBE, 0
};

static SnapshotSpec base_spec(void) {
	static const PaletteSpec palette[2] = { { "minecraft:air", 0, 0, 0, BERYL_MODEL_AIR, 0 },
	                                        { "minecraft:stone", BERYL_BFLG_SOLID | BERYL_BFLG_OPAQUE,
	                                          0, 0, BERYL_MODEL_FULL_CUBE, 0 } };
	SnapshotSpec s;
	memset(&s, 0, sizeof(s));
	s.sx = 3; s.sy = 4; s.sz = -5;
	s.world_id = 0xABCDull;
	s.revision = 77;
	s.pack_generation = 12;
	s.flags = BERYL_SNAP_FLAG_HAS_LIGHT;
	s.bits_per_entry = 1;
	s.palette = palette;
	s.palette_count = 2;
	s.indices = g_indices;
	s.light = g_light;
	(void)AIR_SPEC; (void)STONE_SPEC;
	return s;
}

/* ---------------------------------------------------------------- tests ---- */
static void test_roundtrip(void) {
	beryl_blocks_init();
	fill_half(g_indices);
	for (int v = 0; v < BERYL_SECTION_VOL; v++) {
		g_light[v] = (uint8_t)(0x0F | ((v & 0xF) << 4));
	}

	SnapshotSpec spec = base_spec();
	Buf buf;
	CHECK(build_snapshot(&buf, &spec), "the builder must produce a buffer");

	BerylSnapshot snap;
	int rc = beryl_snapshot_parse(buf.bytes, buf.len, &snap);
	CHECK(rc == BERYL_OK, "a well-formed snapshot must parse (rc=%d)", rc);
	if (rc == BERYL_OK) {
		CHECK(snap.section_x == 3 && snap.section_y == 4 && snap.section_z == -5,
		      "section coordinates must survive the round trip (%d,%d,%d)",
		      snap.section_x, snap.section_y, snap.section_z);
		CHECK(snap.world_id == 0xABCDull, "the world id must survive (0x%llx)",
		      (unsigned long long)snap.world_id);
		CHECK(snap.revision == 77, "the revision must survive (%llu)",
		      (unsigned long long)snap.revision);
		CHECK(snap.pack_generation == 12, "the pack generation must survive (%llu)",
		      (unsigned long long)snap.pack_generation);
		CHECK(snap.palette_count == 2, "palette count (%u)", snap.palette_count);
		CHECK(snap.bits_per_entry == 1, "bits per entry (%u)", snap.bits_per_entry);
		CHECK(beryl_snapshot_has_light(&snap), "the light flag must be honoured");
		CHECK(snap.encoded_size == buf.len,
		      "the whole buffer must be consumed (%zu of %zu)", snap.encoded_size, buf.len);

		int solid = 0;
		for (int v = 0; v < BERYL_SECTION_VOL; v++) {
			if (snap.indices[v] == 1) solid++;
			CHECK(snap.light[v] == (uint8_t)(0x0F | ((v & 0xF) << 4)),
			      "light byte %d must survive (%u)", v, snap.light[v]);
			if (snap.light[v] != (uint8_t)(0x0F | ((v & 0xF) << 4))) break;
		}
		CHECK(solid == 8 * BERYL_SECTION_AREA,
		      "the lower half of the section must be palette entry 1 (%d voxels)", solid);

		/* Voxel order must match the engine's own, or apply() writes a scrambled
		 * section that still parses cleanly and looks wrong in game. */
		CHECK(snap.indices[beryl_snapshot_voxel(0, 0, 0)] == 1, "y=0 must be solid");
		CHECK(snap.indices[beryl_snapshot_voxel(15, 7, 15)] == 1, "y=7 must be solid");
		CHECK(snap.indices[beryl_snapshot_voxel(0, 8, 0)] == 0, "y=8 must be air");

		int fallbacks = beryl_snapshot_resolve(&snap);
		CHECK(fallbacks == 0, "stone and air are both known blocks (%d fallbacks)", fallbacks);
		CHECK(snap.palette[0].resolved == BERYL_BLOCK_AIR, "air must resolve to air");
		CHECK(snap.palette[1].resolved == BERYL_BLOCK_STONE,
		      "minecraft:stone must resolve to stone, got %d", (int)snap.palette[1].resolved);
		CHECK(snap.palette[1].resolved_by_name, "stone must resolve by name");

		beryl_snapshot_free(&snap);
	}
	buf_free(&buf);
}

static void test_namespace_and_unknown_names(void) {
	beryl_blocks_init();

	/* A bare name must resolve too: the producer is free to send either form. */
	BerylSnapshot s;
	memset(&s, 0, sizeof(s));
	s.palette_count = 1;
	s.palette = (BerylBlockDesc *)calloc(1, sizeof(BerylBlockDesc));
	CHECK(s.palette != NULL, "allocation");
	if (s.palette) {
		s.palette[0].name = strdup("grass_block");
		s.palette[0].name_len = (uint16_t)strlen("grass_block");
		s.palette[0].flags = BERYL_BFLG_SOLID | BERYL_BFLG_OPAQUE;
		s.palette[0].model_kind = BERYL_MODEL_FULL_CUBE;
		beryl_snapshot_resolve(&s);
		CHECK(s.palette[0].resolved == BERYL_BLOCK_GRASS_BLOCK,
		      "a bare registry name must resolve (%d)", (int)s.palette[0].resolved);
		beryl_snapshot_free(&s);
	}

	/* The case that matters: a block the engine has never heard of. It must come out
	 * as something that occupies space, not as air. */
	memset(&s, 0, sizeof(s));
	s.palette_count = 6;
	s.palette = (BerylBlockDesc *)calloc(6, sizeof(BerylBlockDesc));
	CHECK(s.palette != NULL, "allocation");
	if (s.palette) {
		struct { const char *name; uint32_t flags; uint8_t emission; uint8_t model; } cases[] = {
			{ "create:shaft",        BERYL_BFLG_SOLID | BERYL_BFLG_OPAQUE, 0, BERYL_MODEL_FULL_CUBE },
			{ "some_mod:force_field", BERYL_BFLG_TRANSLUCENT,              0, BERYL_MODEL_FULL_CUBE },
			{ "some_mod:molten_iron", BERYL_BFLG_SOLID,                    0, BERYL_MODEL_LIQUID    },
			{ "some_mod:wire",        BERYL_BFLG_CUTOUT,                   0, BERYL_MODEL_CROSS     },
			{ "some_mod:lamp",        BERYL_BFLG_SOLID | BERYL_BFLG_OPAQUE | BERYL_BFLG_EMITLIGHT,
			  15, BERYL_MODEL_FULL_CUBE },
			{ "some_mod:void",        0,                                   0, BERYL_MODEL_AIR       },
		};
		for (int i = 0; i < 6; i++) {
			s.palette[i].name = strdup(cases[i].name);
			s.palette[i].name_len = (uint16_t)strlen(cases[i].name);
			s.palette[i].flags = cases[i].flags;
			s.palette[i].light_emission = cases[i].emission;
			s.palette[i].model_kind = cases[i].model;
		}
		int fallbacks = beryl_snapshot_resolve(&s);
		CHECK(fallbacks == 5, "five of the six names are unknown to the engine (%d)", fallbacks);

		CHECK(s.palette[0].resolved == BERYL_BLOCK_STONE,
		      "an unknown solid opaque block must stay solid, not become air (%d)",
		      (int)s.palette[0].resolved);
		CHECK(s.palette[0].resolved != BERYL_BLOCK_AIR,
		      "an unknown block must never resolve to air -- that deletes modded worlds");
		CHECK(s.palette[1].resolved == BERYL_BLOCK_GLASS,
		      "an unknown translucent block must stay translucent (%d)", (int)s.palette[1].resolved);
		CHECK(s.palette[2].resolved == BERYL_BLOCK_WATER,
		      "an unknown liquid must stay a liquid (%d)", (int)s.palette[2].resolved);
		CHECK(s.palette[3].resolved == BERYL_BLOCK_TORCH,
		      "an unknown cross-model cutout must stay cutout (%d)", (int)s.palette[3].resolved);
		CHECK(s.palette[4].resolved == BERYL_BLOCK_GLOWSTONE,
		      "an unknown light-emitting block must keep emitting (%d)", (int)s.palette[4].resolved);
		CHECK(s.palette[5].resolved == BERYL_BLOCK_AIR,
		      "an unknown block with no occupancy flags is legitimately air (%d)",
		      (int)s.palette[5].resolved);
		for (int i = 0; i < 6; i++) {
			CHECK(!s.palette[i].resolved_by_name, "none of these names are in the registry");
		}
		beryl_snapshot_free(&s);
	}
}

static void test_rejects_malformed(void) {
	beryl_blocks_init();
	fill_half(g_indices);
	memset(g_light, 0x0F, sizeof(g_light));

	SnapshotSpec spec = base_spec();
	Buf good;
	CHECK(build_snapshot(&good, &spec), "the builder must produce a buffer");

	BerylSnapshot snap;

	/* --- every truncation of a valid buffer must be refused ---
	 * A parser that reads a length before checking it against the buffer would
	 * run off the end here. Every prefix shorter than the whole is tried. */
	int truncation_failures = 0;
	for (size_t n = 0; n < good.len; n++) {
		memset(&snap, 0, sizeof(snap));
		int rc = beryl_snapshot_parse(good.bytes, n, &snap);
		if (rc != BERYL_ERR_INVALID) {
			truncation_failures++;
			beryl_snapshot_free(&snap);
		}
	}
	CHECK(truncation_failures == 0,
	      "%d truncated buffers were accepted instead of refused", truncation_failures);

	/* --- the whole buffer must still parse after all that --- */
	memset(&snap, 0, sizeof(snap));
	CHECK(beryl_snapshot_parse(good.bytes, good.len, &snap) == BERYL_OK,
	      "the untruncated buffer must still parse");
	beryl_snapshot_free(&snap);

	/* --- bad magic --- */
	Buf bad = good;
	bad.bytes = (uint8_t *)malloc(good.len);
	CHECK(bad.bytes != NULL, "allocation");
	if (bad.bytes) {
		memcpy(bad.bytes, good.bytes, good.len);
		bad.bytes[0] ^= 0xFF;
		memset(&snap, 0, sizeof(snap));
		CHECK(beryl_snapshot_parse(bad.bytes, bad.len, &snap) == BERYL_ERR_INVALID,
		      "a bad magic must be refused");
		memcpy(bad.bytes, good.bytes, good.len);

		/* --- bad version --- */
		bad.bytes[4] = 99;
		memset(&snap, 0, sizeof(snap));
		CHECK(beryl_snapshot_parse(bad.bytes, bad.len, &snap) == BERYL_ERR_INVALID,
		      "an unsupported format version must be refused");
		memcpy(bad.bytes, good.bytes, good.len);

		/* --- invalid bits per entry (byte 52) --- */
		bad.bytes[52] = 3;
		memset(&snap, 0, sizeof(snap));
		CHECK(beryl_snapshot_parse(bad.bytes, bad.len, &snap) == BERYL_ERR_INVALID,
		      "a bit width that cannot pack the palette must be refused");
		memcpy(bad.bytes, good.bytes, good.len);

		/* --- zero palette entries (bytes 48..51) --- */
		memset(bad.bytes + 48, 0, 4);
		memset(&snap, 0, sizeof(snap));
		CHECK(beryl_snapshot_parse(bad.bytes, bad.len, &snap) == BERYL_ERR_INVALID,
		      "an empty palette cannot describe 4096 voxels");
		memcpy(bad.bytes, good.bytes, good.len);

		/* --- an out-of-range voxel index: entry 0 becomes 1 at 4 bits --- */
		Buf wide_spec_buf;
		SnapshotSpec wide = spec;
		wide.bits_per_entry = 4;
		uint16_t wide_indices[BERYL_SECTION_VOL];
		memcpy(wide_indices, g_indices, sizeof(wide_indices));
		wide_indices[0] = 9;   /* palette only has 2 entries */
		wide.indices = wide_indices;
		CHECK(build_snapshot(&wide_spec_buf, &wide), "the 4-bit builder must produce a buffer");
		memset(&snap, 0, sizeof(snap));
		CHECK(beryl_snapshot_parse(wide_spec_buf.bytes, wide_spec_buf.len, &snap) == BERYL_ERR_INVALID,
		      "a voxel index outside the palette must be refused");
		beryl_snapshot_free(&snap);
		buf_free(&wide_spec_buf);
	}
	free(bad.bytes);

	/* --- a repeated palette index --- */
	Buf dup;
	buf_init(&dup);
	buf_u32(&dup, BERYL_SNAPSHOT_MAGIC);
	buf_u32(&dup, BERYL_SNAPSHOT_VERSION);
	buf_u32(&dup, BERYL_SNAP_FLAG_HAS_LIGHT);
	buf_u32(&dup, 0); buf_u32(&dup, 0); buf_u32(&dup, 0);
	buf_u64(&dup, 1); buf_u64(&dup, 1); buf_u64(&dup, 1);
	buf_u32(&dup, 2);
	buf_u8(&dup, 1);
	buf_u8(&dup, 0); buf_u8(&dup, 0); buf_u8(&dup, 0);
	{
		PaletteSpec p = STONE_SPEC;
		write_palette_entry(&dup, &p, 0);
		write_palette_entry(&dup, &p, 0);   /* same index twice */
	}
	write_indices(&dup, &spec);
	buf_raw(&dup, g_light, BERYL_SECTION_VOL);
	memset(&snap, 0, sizeof(snap));
	CHECK(beryl_snapshot_parse(dup.bytes, dup.len, &snap) == BERYL_ERR_INVALID,
	      "a repeated palette index must be refused, not silently overwritten");
	beryl_snapshot_free(&snap);
	buf_free(&dup);

	/* --- NULL and short inputs --- */
	memset(&snap, 0, sizeof(snap));
	CHECK(beryl_snapshot_parse(NULL, 0, &snap) == BERYL_ERR_INVALID, "a NULL buffer must be refused");
	CHECK(beryl_snapshot_parse(good.bytes, good.len, NULL) == BERYL_ERR_INVALID,
	      "a NULL output must be refused");

	buf_free(&good);
}

static void test_staleness(void) {
	beryl_blocks_init();
	fill_half(g_indices);
	memset(g_light, 0x0F, sizeof(g_light));

	SnapshotSpec spec = base_spec();
	Buf buf;
	CHECK(build_snapshot(&buf, &spec), "the builder must produce a buffer");

	BerylSnapshot snap;
	CHECK(beryl_snapshot_parse(buf.bytes, buf.len, &snap) == BERYL_OK, "must parse");

	/* The render thread asks this before uploading a finished mesh. Every one of
	 * these must be refused: a mesh built from any of them describes a world, a
	 * section or a resource pack that is no longer the one on screen. */
	CHECK(beryl_snapshot_is_current(&snap, 0xABCDull, 77, 12),
	      "an unchanged world, section and pack must still be current");
	CHECK(!beryl_snapshot_is_current(&snap, 0xABCEull, 77, 12),
	      "a different world must be rejected");
	CHECK(!beryl_snapshot_is_current(&snap, 0xABCDull, 78, 12),
	      "a section that moved on during the build must be rejected");
	CHECK(!beryl_snapshot_is_current(&snap, 0xABCDull, 76, 12),
	      "a revision that does not match must be rejected even going backwards");
	CHECK(!beryl_snapshot_is_current(&snap, 0xABCDull, 77, 13),
	      "a resource-pack reload during the build must be rejected");
	CHECK(!beryl_snapshot_is_current(NULL, 0xABCDull, 77, 12),
	      "a missing snapshot must be rejected");

	beryl_snapshot_free(&snap);
	buf_free(&buf);
}

static void test_apply_to_world(void) {
	beryl_blocks_init();

	BerylWorldDesc d;
	memset(&d, 0, sizeof(d));
	d.seed = 4242;
	d.radius_sections = 64;
	BerylWorld *world = beryl_world_new(&d);
	CHECK(world != NULL, "the world must be created");
	if (!world) return;

	/* A distinctive pattern: solid below y=8, air above, and light that varies per
	 * voxel so a scrambled write would be obvious. */
	for (int v = 0; v < BERYL_SECTION_VOL; v++) {
		int ly = v / BERYL_SECTION_AREA;
		g_indices[v] = ly < 8 ? 1 : 0;
		g_light[v] = (uint8_t)((ly & 0xF) | (((v / BERYL_SECTION_SIDE) & 0xF) << 4));
	}

	SnapshotSpec spec = base_spec();
	spec.sx = 2; spec.sy = 3; spec.sz = 1;
	Buf buf;
	CHECK(build_snapshot(&buf, &spec), "the builder must produce a buffer");

	BerylSnapshot snap;
	CHECK(beryl_snapshot_parse(buf.bytes, buf.len, &snap) == BERYL_OK, "must parse");
	beryl_snapshot_resolve(&snap);

	int rc = beryl_snapshot_apply(world, &snap);
	CHECK(rc == BERYL_OK, "applying a valid snapshot must succeed (rc=%d)", rc);

	BerylSection *sec = beryl_world_section(world, 2, 3, 1, false);
	CHECK(sec != NULL, "the section must now exist");
	if (sec) {
		CHECK(beryl_section_get_state(sec, 0, 0, 0) == BERYL_BLOCK_STONE,
		      "y=0 must be stone, got %d", (int)beryl_section_get_state(sec, 0, 0, 0));
		CHECK(beryl_section_get_state(sec, 15, 7, 15) == BERYL_BLOCK_STONE,
		      "y=7 must be stone");
		CHECK(beryl_section_get_state(sec, 0, 8, 0) == BERYL_BLOCK_AIR,
		      "y=8 must be air");
		CHECK(beryl_section_get_sky_light(sec, 0, 5, 0) == 5,
		      "sky light must land in the right voxel (%d)",
		      beryl_section_get_sky_light(sec, 0, 5, 0));
		CHECK(!sec->light_dirty, "light arrived with the snapshot, so it is not dirty");
		CHECK(sec->dirty, "a freshly written section must need a remesh");
		CHECK(sec->non_air == 8 * BERYL_SECTION_AREA,
		      "derived data must be recomputed (%d non-air)", sec->non_air);
		CHECK(!sec->all_air, "a half-full section is not all air");
	}

	/* Out-of-range section coordinates must be refused rather than written. */
	BerylSnapshot bad = snap;
	bad.section_y = BERYL_CHUNK_SECTIONS;
	CHECK(beryl_snapshot_apply(world, &bad) == BERYL_ERR_INVALID,
	      "a section above the world must be refused");
	bad.section_y = -1;
	CHECK(beryl_snapshot_apply(world, &bad) == BERYL_ERR_INVALID,
	      "a section below the world must be refused");
	CHECK(beryl_snapshot_apply(NULL, &snap) == BERYL_ERR_INVALID,
	      "a NULL world must be refused");
	CHECK(beryl_snapshot_apply(world, NULL) == BERYL_ERR_INVALID,
	      "a NULL snapshot must be refused");

	beryl_snapshot_free(&snap);
	buf_free(&buf);

	/* A snapshot carrying no light must leave the section light-dirty, so the light
	 * engine refills it instead of baking whatever the section held before. */
	SnapshotSpec no_light = base_spec();
	no_light.flags = 0;
	no_light.light = NULL;
	no_light.sx = 4; no_light.sy = 2; no_light.sz = 4;
	Buf buf2;
	CHECK(build_snapshot(&buf2, &no_light), "the builder must produce a lightless buffer");
	BerylSnapshot snap2;
	CHECK(beryl_snapshot_parse(buf2.bytes, buf2.len, &snap2) == BERYL_OK, "must parse");
	CHECK(!beryl_snapshot_has_light(&snap2), "the light flag must be absent");
	beryl_snapshot_resolve(&snap2);
	CHECK(beryl_snapshot_apply(world, &snap2) == BERYL_OK, "applying must succeed");
	BerylSection *sec2 = beryl_world_section(world, 4, 2, 4, false);
	CHECK(sec2 != NULL, "the second section must exist");
	if (sec2) {
		CHECK(sec2->light_dirty, "a snapshot with no light must leave the section light-dirty");
	}
	beryl_snapshot_free(&snap2);
	buf_free(&buf2);

	beryl_world_free(world);
}

static void test_bit_widths(void) {
	beryl_blocks_init();

	/* One, two, four, eight and sixteen bits per entry all have to unpack to the
	 * same voxels. Sixteen is the interesting one: it is the only width where an
	 * entry spans more than one byte. */
	static const PaletteSpec palette[3] = {
		{ "minecraft:air",   0, 0, 0, BERYL_MODEL_AIR,       0 },
		{ "minecraft:stone", BERYL_BFLG_SOLID | BERYL_BFLG_OPAQUE, 0, 0, BERYL_MODEL_FULL_CUBE, 0 },
		{ "minecraft:dirt",  BERYL_BFLG_SOLID | BERYL_BFLG_OPAQUE, 0, 0, BERYL_MODEL_FULL_CUBE, 0 },
	};
	uint8_t widths[] = { 1, 2, 4, 8, 16 };

	for (int w = 0; w < 5; w++) {
		/* One bit can only express two of the three palette entries. */
		int modulus = widths[w] == 1 ? 2 : 3;
		for (int v = 0; v < BERYL_SECTION_VOL; v++) {
			g_indices[v] = (uint16_t)(v % modulus);
		}
		memset(g_light, 0x1F, sizeof(g_light));

		SnapshotSpec spec = base_spec();
		spec.palette = palette;
		spec.palette_count = 3;
		spec.bits_per_entry = widths[w];

		Buf buf;
		CHECK(build_snapshot(&buf, &spec), "the builder must produce a %u-bit buffer", widths[w]);

		BerylSnapshot snap;
		int rc = beryl_snapshot_parse(buf.bytes, buf.len, &snap);
		CHECK(rc == BERYL_OK, "%u bits per entry must parse (rc=%d)", widths[w], rc);
		if (rc == BERYL_OK) {
			int mismatched = 0;
			for (int v = 0; v < BERYL_SECTION_VOL; v++) {
				if (snap.indices[v] != (uint16_t)(v % modulus)) mismatched++;
			}
			CHECK(mismatched == 0, "%u bits per entry unpacked %d voxels wrongly",
			      widths[w], mismatched);
			beryl_snapshot_free(&snap);
		}
		buf_free(&buf);
	}

	/* A width that cannot pack anything must be refused outright. */
	uint8_t bad_widths[] = { 0, 3, 5, 7, 9, 15, 17, 32, 255 };
	for (int i = 0; i < 9; i++) {
		for (int v = 0; v < BERYL_SECTION_VOL; v++) g_indices[v] = 0;
		SnapshotSpec spec = base_spec();
		spec.palette_count = 1;
		spec.bits_per_entry = bad_widths[i];
		spec.flags = 0;
		spec.light = NULL;
		Buf buf;
		build_snapshot(&buf, &spec);
		BerylSnapshot snap;
		memset(&snap, 0, sizeof(snap));
		CHECK(beryl_snapshot_parse(buf.bytes, buf.len, &snap) == BERYL_ERR_INVALID,
		      "%u bits per entry must be refused", bad_widths[i]);
		beryl_snapshot_free(&snap);
		buf_free(&buf);
	}
}

void test_snapshot(void) {
	test_roundtrip();
	test_namespace_and_unknown_names();
	test_rejects_malformed();
	test_staleness();
	test_apply_to_world();
	test_bit_widths();
}
