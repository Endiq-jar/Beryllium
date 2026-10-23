/* snapshot.c -- parsing, resolving and applying Minecraft section snapshots.
 *
 * See snapshot.h for the format and for why it looks the way it does. The two
 * things worth repeating here, because they shape every function below:
 *
 *   - The buffer arrives from another process's worth of complexity (a modded
 *     Minecraft client), so every length is checked against the buffer before it
 *     is read. A malformed snapshot is rejected, never partially trusted.
 *   - A block the engine has never heard of must still be drawn approximately
 *     right. Resolving an unknown name to air would delete it; resolving it from
 *     its own material flags keeps it solid, translucent or cutout as measured.
 */
#include "snapshot.h"

#include "bcore.h"
#include "chunk.h"
#include "world.h"

#include <stdlib.h>
#include <string.h>

/* ------------------------------------------------------------ bit reader -- */
typedef struct {
	const uint8_t *bytes;
	size_t len;
	size_t pos;      /* current byte  */
	unsigned bit;    /* 0..7 within that byte */
} BitReader;

static bool br_init(BitReader *r, const uint8_t *bytes, size_t len) {
	if (!bytes && len > 0) return false;
	r->bytes = bytes;
	r->len = len;
	r->pos = 0;
	r->bit = 0;
	return true;
}

/** How many bits remain unread. */
static size_t br_remaining_bits(const BitReader *r) {
	if (r->pos >= r->len) return 0;
	return (r->len - r->pos) * 8 - r->bit;
}

/** Reads up to 16 bits, little-endian within the stream. */
static bool br_read(BitReader *r, unsigned bits, uint32_t *out) {
	if (bits == 0) { *out = 0; return true; }
	if (bits > 16) return false;
	if (br_remaining_bits(r) < bits) return false;

	uint32_t value = 0;
	for (unsigned i = 0; i < bits; i++) {
		if (r->pos >= r->len) return false;
		uint32_t b = (uint32_t)((r->bytes[r->pos] >> r->bit) & 1u);
		value |= b << i;
		r->bit++;
		if (r->bit == 8) { r->bit = 0; r->pos++; }
	}
	*out = value;
	return true;
}

/* ------------------------------------------------------------ byte reader -- */
typedef struct {
	const uint8_t *bytes;
	size_t len;
	size_t pos;
} Reader;

static bool rd_init(Reader *r, const uint8_t *bytes, size_t len) {
	if (!bytes && len > 0) return false;
	r->bytes = bytes; r->len = len; r->pos = 0;
	return true;
}

static bool rd_need(const Reader *r, size_t n) {
	/* Guards against n wrapping when added to pos. */
	return n <= r->len && r->pos <= r->len - n;
}

static bool rd_u8(Reader *r, uint8_t *out) {
	if (!rd_need(r, 1)) return false;
	*out = r->bytes[r->pos++];
	return true;
}

static bool rd_u16(Reader *r, uint16_t *out) {
	if (!rd_need(r, 2)) return false;
	*out = (uint16_t)(r->bytes[r->pos] | ((uint16_t)r->bytes[r->pos + 1] << 8));
	r->pos += 2;
	return true;
}

static bool rd_u32(Reader *r, uint32_t *out) {
	if (!rd_need(r, 4)) return false;
	*out = (uint32_t)r->bytes[r->pos]
	     | ((uint32_t)r->bytes[r->pos + 1] << 8)
	     | ((uint32_t)r->bytes[r->pos + 2] << 16)
	     | ((uint32_t)r->bytes[r->pos + 3] << 24);
	r->pos += 4;
	return true;
}

static bool rd_i32(Reader *r, int32_t *out) {
	uint32_t v;
	if (!rd_u32(r, &v)) return false;
	*out = (int32_t)v;
	return true;
}

static bool rd_u64(Reader *r, uint64_t *out) {
	uint32_t lo, hi;
	size_t start = r->pos;
	if (!rd_u32(r, &lo)) return false;
	if (!rd_u32(r, &hi)) { r->pos = start; return false; }
	*out = (uint64_t)lo | ((uint64_t)hi << 32);
	return true;
}

static bool rd_skip(Reader *r, size_t n) {
	if (!rd_need(r, n)) return false;
	r->pos += n;
	return true;
}

/* ---------------------------------------------------------------- parsing -- */
static bool bits_per_entry_valid(uint8_t b) {
	return b == 1 || b == 2 || b == 4 || b == 8 || b == 16;
}

int beryl_snapshot_parse(const uint8_t *bytes, size_t len, BerylSnapshot *out) {
	if (!out) return BERYL_ERR_INVALID;
	memset(out, 0, sizeof(*out));
	if (!bytes || len < BERYL_SNAPSHOT_HEADER_BYTES) return BERYL_ERR_INVALID;

	Reader r;
	if (!rd_init(&r, bytes, len)) return BERYL_ERR_INVALID;

	uint32_t magic = 0, version = 0, flags = 0, palette_count = 0;
	uint8_t bits = 0, reserved[3] = {0, 0, 0};

	if (!rd_u32(&r, &magic) || magic != BERYL_SNAPSHOT_MAGIC) return BERYL_ERR_INVALID;
	if (!rd_u32(&r, &version) || version != BERYL_SNAPSHOT_VERSION) return BERYL_ERR_INVALID;
	if (!rd_u32(&r, &flags)) return BERYL_ERR_INVALID;
	if (!rd_i32(&r, &out->section_x)) return BERYL_ERR_INVALID;
	if (!rd_i32(&r, &out->section_y)) return BERYL_ERR_INVALID;
	if (!rd_i32(&r, &out->section_z)) return BERYL_ERR_INVALID;
	if (!rd_u64(&r, &out->world_id)) return BERYL_ERR_INVALID;
	if (!rd_u64(&r, &out->revision)) return BERYL_ERR_INVALID;
	if (!rd_u64(&r, &out->pack_generation)) return BERYL_ERR_INVALID;
	if (!rd_u32(&r, &palette_count)) return BERYL_ERR_INVALID;
	if (!rd_u8(&r, &bits)) return BERYL_ERR_INVALID;
	for (int i = 0; i < 3; i++) {
		if (!rd_u8(&r, &reserved[i])) return BERYL_ERR_INVALID;
	}

	if (!bits_per_entry_valid(bits)) return BERYL_ERR_INVALID;
	/* An empty palette cannot describe 4096 voxels, and a palette larger than the
	 * number of cells it indexes is either corrupt or a producer that never
	 * deduplicated -- either way it is not a snapshot this engine should accept. */
	if (palette_count == 0 || palette_count > BERYL_SECTION_VOL) return BERYL_ERR_INVALID;

	out->format_version = version;
	out->flags = flags;
	out->palette_count = palette_count;
	out->bits_per_entry = bits;

	/* --- palette --- */
	out->palette = (BerylBlockDesc *)calloc(palette_count, sizeof(BerylBlockDesc));
	if (!out->palette) return BERYL_ERR_OOM;

	/* Indices already seen, so a repeated paletteIndex is rejected rather than
	 * silently overwriting one descriptor with another. */
	uint8_t *seen = (uint8_t *)calloc(palette_count, 1);
	if (!seen) { beryl_snapshot_free(out); return BERYL_ERR_OOM; }

	for (uint32_t i = 0; i < palette_count; i++) {
		BerylBlockDesc d;
		memset(&d, 0, sizeof(d));

		uint32_t pindex = 0, engine_id = 0, dflags = 0;
		uint8_t emission = 0, attenuation = 0, filter = 0, layer = 0, kind = 0, res[3] = {0,0,0};
		uint16_t name_len = 0;
		uint32_t custom_len = 0;

		bool ok = rd_u32(&r, &pindex)
		       && rd_u32(&r, &engine_id)
		       && rd_u32(&r, &dflags)
		       && rd_u8(&r, &emission)
		       && rd_u8(&r, &attenuation)
		       && rd_u8(&r, &filter)
		       && rd_u8(&r, &layer)
		       && rd_u8(&r, &kind)
		       && rd_u8(&r, &res[0]) && rd_u8(&r, &res[1]) && rd_u8(&r, &res[2])
		       && rd_u16(&r, &name_len)
		       && rd_need(&r, name_len);
		if (!ok) { free(seen); beryl_snapshot_free(out); return BERYL_ERR_INVALID; }

		if (pindex >= palette_count || seen[pindex]) {
			free(seen); beryl_snapshot_free(out); return BERYL_ERR_INVALID;
		}
		seen[pindex] = 1;

		if (name_len > 0) {
			d.name = (char *)malloc((size_t)name_len + 1);
			if (!d.name) { free(seen); beryl_snapshot_free(out); return BERYL_ERR_OOM; }
			memcpy(d.name, r.bytes + r.pos, name_len);
			d.name[name_len] = '\0';
			d.name_len = name_len;
		}
		if (!rd_skip(&r, name_len)) {
			free(d.name); free(seen); beryl_snapshot_free(out); return BERYL_ERR_INVALID;
		}

		if (!rd_u32(&r, &custom_len) || !rd_need(&r, custom_len)) {
			free(d.name); free(seen); beryl_snapshot_free(out); return BERYL_ERR_INVALID;
		}
		if (custom_len > 0) {
			d.custom_model = (uint8_t *)malloc(custom_len);
			if (!d.custom_model) {
				free(d.name); free(seen); beryl_snapshot_free(out); return BERYL_ERR_OOM;
			}
			memcpy(d.custom_model, r.bytes + r.pos, custom_len);
			d.custom_model_len = custom_len;
		}
		if (!rd_skip(&r, custom_len)) {
			free(d.custom_model); free(d.name); free(seen);
			beryl_snapshot_free(out); return BERYL_ERR_INVALID;
		}

		d.palette_index = pindex;
		d.engine_block_id = engine_id;
		d.flags = dflags;
		d.light_emission = emission;
		d.light_attenuation = attenuation;
		d.skylight_filter = filter;
		d.render_layer = layer;
		d.model_kind = kind;
		out->palette[pindex] = d;
	}
	free(seen);

	/* --- voxel indices --- */
	size_t index_bits = (size_t)BERYL_SECTION_VOL * bits;
	size_t index_bytes = (index_bits + 7) / 8;
	if (!rd_need(&r, index_bytes)) { beryl_snapshot_free(out); return BERYL_ERR_INVALID; }

	BitReader br;
	if (!br_init(&br, r.bytes + r.pos, index_bytes)) {
		beryl_snapshot_free(out); return BERYL_ERR_INVALID;
	}
	for (int v = 0; v < BERYL_SECTION_VOL; v++) {
		uint32_t idx = 0;
		if (!br_read(&br, bits, &idx) || idx >= palette_count) {
			beryl_snapshot_free(out); return BERYL_ERR_INVALID;
		}
		out->indices[v] = (uint16_t)idx;
	}
	if (!rd_skip(&r, index_bytes)) { beryl_snapshot_free(out); return BERYL_ERR_INVALID; }

	/* --- light --- */
	if (flags & BERYL_SNAP_FLAG_HAS_LIGHT) {
		if (!rd_need(&r, BERYL_SECTION_VOL)) {
			beryl_snapshot_free(out); return BERYL_ERR_INVALID;
		}
		memcpy(out->light, r.bytes + r.pos, BERYL_SECTION_VOL);
		if (!rd_skip(&r, BERYL_SECTION_VOL)) {
			beryl_snapshot_free(out); return BERYL_ERR_INVALID;
		}
	}

	out->encoded_size = r.pos;
	return BERYL_OK;
}

void beryl_snapshot_free(BerylSnapshot *s) {
	if (!s) return;
	if (s->palette) {
		for (uint32_t i = 0; i < s->palette_count; i++) {
			free(s->palette[i].name);
			free(s->palette[i].custom_model);
		}
		free(s->palette);
	}
	memset(s, 0, sizeof(*s));
}

/* ------------------------------------------------------------- resolution -- */
beryl_bid beryl_snapshot_fallback_id(const BerylBlockDesc *d) {
	if (!d) return BERYL_BLOCK_AIR;
	if (d->model_kind == BERYL_MODEL_AIR) return BERYL_BLOCK_AIR;

	uint32_t occupies = BERYL_BFLG_SOLID | BERYL_BFLG_OPAQUE
	                  | BERYL_BFLG_CUTOUT | BERYL_BFLG_TRANSLUCENT;
	if ((d->flags & occupies) == 0) {
		/* Nothing about this state says it takes up space. Drawing it as a cube
		 * would invent geometry; drawing nothing is the honest answer. */
		return BERYL_BLOCK_AIR;
	}

	if (d->flags & BERYL_BFLG_TRANSLUCENT) return BERYL_BLOCK_GLASS;
	if (d->model_kind == BERYL_MODEL_LIQUID) return BERYL_BLOCK_WATER;
	if (d->flags & BERYL_BFLG_CUTOUT) {
		/* A cutout full cube behaves like leaves; anything else like a torch. */
		return d->model_kind == BERYL_MODEL_FULL_CUBE ? BERYL_BLOCK_OAK_LEAVES
		                                              : BERYL_BLOCK_TORCH;
	}
	if (d->light_emission > 0) return BERYL_BLOCK_GLOWSTONE;
	return BERYL_BLOCK_STONE;
}

/** Looks a registry name up, tolerating the "minecraft:" namespace Minecraft uses. */
static beryl_bid lookup_name(const char *name) {
	if (!name || !*name) return BERYL_BLOCK_AIR;
	beryl_bid id = beryl_block_by_name(name);
	if (id != BERYL_BLOCK_AIR) return id;

	const char *colon = strchr(name, ':');
	if (colon && colon[1] != '\0') {
		return beryl_block_by_name(colon + 1);
	}
	return BERYL_BLOCK_AIR;
}

int beryl_snapshot_resolve(BerylSnapshot *s) {
	if (!s || !s->palette) return 0;
	int fallbacks = 0;
	for (uint32_t i = 0; i < s->palette_count; i++) {
		BerylBlockDesc *d = &s->palette[i];
		beryl_bid by_name = lookup_name(d->name);
		if (by_name != BERYL_BLOCK_AIR) {
			d->resolved = by_name;
			d->resolved_by_name = true;
		} else {
			d->resolved = beryl_snapshot_fallback_id(d);
			d->resolved_by_name = false;
			if (d->model_kind != BERYL_MODEL_AIR) fallbacks++;
		}
	}
	return fallbacks;
}

bool beryl_snapshot_is_current(const BerylSnapshot *s,
                               uint64_t expected_world,
                               uint64_t current_revision,
                               uint64_t current_pack) {
	if (!s) return false;
	if (s->world_id != expected_world) return false;
	if (s->pack_generation != current_pack) return false;
	/* The section may legitimately have moved *forward* while the build ran; any
	 * difference means the mesh describes a state that is no longer the current
	 * one, and the section is still dirty so it will be rebuilt anyway. */
	return s->revision == current_revision;
}

/* ------------------------------------------------------------------ apply -- */
int beryl_snapshot_apply(BerylWorld *world, const BerylSnapshot *s) {
	if (!world || !s || !s->palette) return BERYL_ERR_INVALID;
	if (s->section_y < 0 || s->section_y >= BERYL_CHUNK_SECTIONS) return BERYL_ERR_INVALID;

	beryl_world_lock_write(world);

	/* A chunk column is one section wide in this engine, so section and chunk
	 * coordinates coincide on X and Z. */
	BerylSection *sec = beryl_world_section(world, s->section_x, s->section_y, s->section_z, true);
	if (!sec) {
		beryl_world_unlock_write(world);
		return BERYL_ERR_INVALID;
	}

	for (int v = 0; v < BERYL_SECTION_VOL; v++) {
		uint16_t idx = s->indices[v];
		if (idx >= s->palette_count) {
			beryl_world_unlock_write(world);
			return BERYL_ERR_INVALID;
		}
		sec->states[v] = (beryl_state)s->palette[idx].resolved;
	}

	if (beryl_snapshot_has_light(s)) {
		memcpy(sec->light, s->light, BERYL_SECTION_VOL);
		sec->light_dirty = false;
	} else {
		/* No light arrived with the snapshot, so whatever the section held
		 * before is not about to be right. Marking it light-dirty is what makes
		 * the light engine refill it instead of baking stale values. */
		sec->light_dirty = true;
	}

	sec->revision++;
	sec->dirty = true;
	beryl_section_recompute_derived(sec);

	beryl_world_unlock_write(world);
	return BERYL_OK;
}
