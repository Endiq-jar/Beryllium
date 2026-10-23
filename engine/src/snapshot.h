/* snapshot.h -- the Minecraft-to-engine section snapshot contract.
 *
 * THE RULE THIS EXISTS TO ENFORCE
 * -------------------------------
 * A native worker thread must never read a live Minecraft world object. Not
 * because it is slow, but because those objects are mutated by the game thread
 * while a mesh build is running, and a build that reads a half-updated section
 * produces geometry that does not correspond to any state the world was ever in.
 *
 * So Minecraft hands over an *immutable, versioned* snapshot of one 16x16x16
 * section. Workers mesh the snapshot. The render thread then validates the
 * result -- against the world, the section revision and the resource-pack
 * generation it was built from -- and only then uploads and draws it. A build
 * whose inputs moved underneath it is rejected, not drawn.
 *
 * THE FORMAT
 * ----------
 * Little-endian throughout. Offsets are from the start of the buffer.
 *
 *   Header (56 bytes)
 *     0   u32  magic          'B','R','S','1' = 0x31535242
 *     4   u32  formatVersion  currently 1
 *     8   u32  flags          bit 0: light block present
 *    12   i32  sectionX       section coordinate (block origin = sectionX * 16)
 *    16   i32  sectionY
 *    20   i32  sectionZ
 *    24   u64  worldId        identity of the level the snapshot was taken from
 *    32   u64  revision       the section's revision at capture time
 *    40   u64  packGeneration resource-pack generation at capture time
 *    48   u32  paletteCount   number of distinct block states in this section
 *    52   u8   bitsPerEntry   1, 2, 4, 8 or 16 -- width of one voxel index
 *    53   u8   reserved[3]
 *
 *   Palette (paletteCount entries, variable length)
 *     u32  paletteIndex   must be < paletteCount; entries may be in any order
 *     u32  engineBlockId  the producer's idea of the engine id, 0 if unresolved.
 *                         Advisory only: the engine re-resolves from the name,
 *                         because a Java-side id table and a C-side enum drift.
 *     u32  flags          BERYL_BFLG_* bits, as measured in Minecraft
 *     u8   lightEmission      0..15
 *     u8   lightAttenuation   0..15
 *     u8   skylightFilter     0..15
 *     u8   renderLayer        0 opaque, 1 cutout, 2 blend
 *     u8   modelKind          see BerylModelKind
 *     u8   reserved[3]
 *     u16  nameLen
 *     u8   name[nameLen]       UTF-8 registry name, e.g. "minecraft:stone".
 *                              Not NUL-terminated in the buffer.
 *     u32  customModelLen
 *     u8   customModel[customModelLen]
 *
 *   Voxel indices
 *     ceil(4096 * bitsPerEntry / 8) bytes, little-endian bit packing, voxel
 *     order x + z*16 + y*256 (matching beryl_section_index).
 *
 *   Light (only when flags bit 0 is set)
 *     4096 bytes, one per voxel in the same order: low nibble sky, high nibble
 *     block. Identical packing to BerylSection.light.
 *
 * WHY THE PALETTE CARRIES MATERIALS AND NOT JUST IDS
 * --------------------------------------------------
 * The engine's built-in block registry is a frozen uint8 enum of about thirty
 * entries -- stone, dirt, a handful of ores. Minecraft has tens of thousands of
 * block states. A contract that sent only an id would either be limited to those
 * thirty blocks or would silently map everything else to one of them, which is
 * how a modded world turns into a landscape of stone.
 *
 * So each palette entry carries the properties that actually decide how geometry
 * is produced and drawn: solidity, opacity, cutout/translucency, light emission
 * and attenuation, render layer, and a model kind with room for an opaque model
 * blob. That is enough for the mesher to do the right thing for a block it has
 * never heard of, and it is the seam a later phase widens into real baked-model
 * translation rather than a rewrite.
 */
#ifndef BERYL_SNAPSHOT_H
#define BERYL_SNAPSHOT_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "bcore.h"
#include "blocks.h"
/* BERYL_OK / BERYL_ERR_* are the engine's shared status codes, and they live in
 * rhi.h rather than bcore.h. world.h is included so BerylWorld is a complete
 * type at file scope -- forward-declaring a struct inside a parameter list gives
 * it a scope that ends with the declaration. */
#include "rhi.h"
#include "world.h"

#define BERYL_SNAPSHOT_MAGIC   0x31535242u /* 'BRS1', little-endian */
#define BERYL_SNAPSHOT_VERSION 1u

#define BERYL_SNAPSHOT_HEADER_BYTES 56
#define BERYL_SNAP_FLAG_HAS_LIGHT   0x1u

/** How a block state's geometry should be produced. */
typedef enum BerylModelKind {
	BERYL_MODEL_AIR = 0,      /* nothing to draw                              */
	BERYL_MODEL_FULL_CUBE = 1,/* six faces, standard cube                     */
	BERYL_MODEL_CROSS = 2,    /* two crossed quads: flowers, grass, torches   */
	BERYL_MODEL_LIQUID = 3,   /* cube with a lowered top face and no bottom   */
	BERYL_MODEL_CUSTOM = 4    /* geometry described by the customModel blob   */
} BerylModelKind;

/** One distinct block state appearing in a section. */
typedef struct BerylBlockDesc {
	uint32_t palette_index;
	uint32_t engine_block_id;   /* advisory; see the header comment          */
	uint32_t flags;             /* BERYL_BFLG_* as measured in Minecraft     */
	uint8_t  light_emission;
	uint8_t  light_attenuation;
	uint8_t  skylight_filter;
	uint8_t  render_layer;      /* 0 opaque, 1 cutout, 2 blend               */
	uint8_t  model_kind;        /* BerylModelKind                            */

	char    *name;              /* heap, NUL-terminated for convenience      */
	uint16_t name_len;

	uint8_t *custom_model;      /* heap, may be NULL                         */
	uint32_t custom_model_len;

	/* Filled in by beryl_snapshot_resolve. */
	beryl_bid resolved;         /* the engine id this descriptor maps to     */
	bool      resolved_by_name; /* true if the name matched a built-in block */
} BerylBlockDesc;

/** A parsed snapshot. Owns heap memory; release with beryl_snapshot_free. */
typedef struct BerylSnapshot {
	uint32_t format_version;
	uint32_t flags;
	int32_t  section_x, section_y, section_z;
	uint64_t world_id;
	uint64_t revision;
	uint64_t pack_generation;

	uint32_t palette_count;
	uint8_t  bits_per_entry;

	BerylBlockDesc *palette;      /* palette_count entries, in buffer order    */

	uint16_t indices[BERYL_SECTION_VOL]; /* unpacked, one per voxel            */
	uint8_t  light[BERYL_SECTION_VOL];   /* valid only when has_light()        */

	size_t   encoded_size;        /* bytes consumed from the input buffer      */
} BerylSnapshot;

/**
 * Parses and validates a snapshot buffer.
 *
 * Every length in the format is checked against the buffer before it is used, so
 * a truncated or corrupt buffer is rejected rather than read past. Nothing is
 * copied out of `bytes` except through bounds-checked reads.
 *
 * @return BERYL_OK, BERYL_ERR_INVALID (bad magic, version, lengths or palette
 *         indices), or BERYL_ERR_OOM. On failure `out` is left zeroed and does
 *         not need freeing.
 */
int beryl_snapshot_parse(const uint8_t *bytes, size_t len, BerylSnapshot *out);

/** Releases everything beryl_snapshot_parse allocated. Safe on a zeroed struct. */
void beryl_snapshot_free(BerylSnapshot *s);

static inline bool beryl_snapshot_has_light(const BerylSnapshot *s) {
	return s && (s->flags & BERYL_SNAP_FLAG_HAS_LIGHT) != 0;
}

/** Voxel index within the section, matching beryl_section_index's order. */
static inline int beryl_snapshot_voxel(int lx, int ly, int lz) {
	return lx + lz * BERYL_SECTION_SIDE + ly * BERYL_SECTION_AREA;
}

/**
 * Maps every palette entry onto an engine block id.
 *
 * Resolution order: the registry name first, because it is the one identifier
 * both sides agree on and it cannot drift with an enum reorder. An entry whose
 * name is not known falls back to a built-in block chosen from the descriptor's
 * own material flags, so an unknown block still occludes, still emits its light
 * and still lands in the right render layer.
 *
 * That fallback is the important half. Mapping unknown blocks to air -- the
 * obvious thing to do, and what an id-only contract forces -- would delete most
 * of a modded world instead of drawing it approximately.
 *
 * @return how many entries could not be resolved by name and used the fallback
 */
int beryl_snapshot_resolve(BerylSnapshot *s);

/**
 * The conservative engine id for a block the registry does not know, chosen from
 * its material flags so that visibility behaviour survives the substitution.
 */
beryl_bid beryl_snapshot_fallback_id(const BerylBlockDesc *d);

/**
 * Whether a finished mesh built from this snapshot may still be used.
 *
 * The render thread calls this before uploading. A build takes time; the world,
 * the section and the resource pack can all move during it, and a mesh describing
 * a section that has since changed would draw stale geometry over current
 * geometry. Rejecting is always safe -- the section is still dirty and gets
 * rebuilt -- whereas accepting a stale mesh is a visible artefact.
 *
 * @param expected_world    the level now being rendered
 * @param current_revision  the section's revision now
 * @param current_pack      the resource-pack generation now
 */
bool beryl_snapshot_is_current(const BerylSnapshot *s,
                               uint64_t expected_world,
                               uint64_t current_revision,
                               uint64_t current_pack);

/**
 * Writes the snapshot's voxels and light into the world.
 *
 * The section must already have been resolved with beryl_snapshot_resolve.
 * Takes the world's write lock itself.
 *
 * @return BERYL_OK, or BERYL_ERR_INVALID if coordinates fall outside the world
 *         or the palette was never resolved
 */
int beryl_snapshot_apply(BerylWorld *world, const BerylSnapshot *s);

#endif /* BERYL_SNAPSHOT_H */
