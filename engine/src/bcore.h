/* bcore.h -- Beryllium Engine: fundamental types, config knobs, logging, counters.
 *
 * Dependency policy for this whole engine: libc + libpthread + libdl only.
 * Everything else (Vulkan entry points, GL entry points, PNG encoding, math)
 * is implemented in-tree, because the target of this engine is "runs anywhere a
 * JVM-based launcher runs", including ancient Android/GL-ES sandboxes where a
 * package manager is not an option.
 */
#ifndef BERYL_BCORE_H
#define BERYL_BCORE_H

/* Timing uses clock_gettime(). The Makefile passes -D_POSIX_C_SOURCE=200809L so
 * the prototype is visible under -std=c11 (strict ISO mode hides POSIX). */
#if defined(__linux__) || defined(__APPLE__) || defined(__unix__)
#define BERYL_HAVE_CLOCK 1
#define BERYL_CLOCK_ID CLOCK_MONOTONIC
#else
#define BERYL_HAVE_CLOCK 0
#endif
#include <time.h>
#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>

/* ------------------------------------------------------------------ version */
#define BERYL_ENGINE_NAME    "Beryllium Engine"
#define BERYL_ENGINE_VERSION "0.1.0"

/* ------------------------------------------------------------------- world */
/* Minecraft's storage granularity: a section is a 16x16x16 cube and is the unit
 * of meshing, lighting and culling. A chunk is a full-height column of sections. */
#define BERYL_SECTION_SIDE      16
#define BERYL_SECTION_AREA      (BERYL_SECTION_SIDE * BERYL_SECTION_SIDE) /* 256 */
#define BERYL_SECTION_VOL       (BERYL_SECTION_AREA * BERYL_SECTION_SIDE) /* 4096 */
#define BERYL_SECTION_VOL_SHIFT 12
#define BERYL_CHUNK_SECTIONS    8                            /* 128 blocks tall   */
/* A chunk column is one section wide and CHUNK_SECTIONS tall, so block
 * coordinates become chunk coordinates with this shift (and section coordinates
 * with log2(SECTION_SIDE)). Keeping both spellings here avoids the "/16 then
 * >>4" double conversion that once pointed the mesh loader at the origin. */
#define BERYL_CHUNK_SHIFT       4
#define BERYL_WORLD_MIN_Y       0
#define BERYL_WORLD_MAX_Y       (BERYL_CHUNK_SECTIONS * BERYL_SECTION_SIDE)

/* Block coordinates are wrapped into [-2^23, 2^23) like vanilla, which keeps
 * "block pos minus camera pos" exactly representable in float32. */
#define BERYL_coord_shift 26
#define BERYL_coord_half_range (1 << BERYL_coord_shift)

/* ------------------------------------------------------------------- types */
typedef uint8_t  beryl_bid;      /* block id (registry index), 0 = air       */
typedef uint16_t beryl_state;    /* stored in the chunk: currently just bid  */

typedef struct BerylVec3i { int32_t x, y, z; } BerylVec3i;

/* Index helpers for section-local addressing (x = east, y = up, z = north),
 * matching vanilla's y|x<<4|z<<8 layout so light/mask data can be swapped. */
static inline int beryl_section_index(int x, int y, int z) {
	return (y << 8) | (z << 4) | x;
}
#define BERYL_SEC_IDX(i, x, y, z) ((i) = beryl_section_index((x), (y), (z)))

/* --------------------------------------------------------------- section id
 * A section is addressed by (chunkX, sectionIndexY, chunkZ) packed into one
 * 64-bit key for the hash maps. y is unbounded-ish (world height is bounded),
 * so 16 bits each for x/z and 8 for y is plenty. */
static inline uint64_t beryl_section_key(int cx, int sy, int cz) {
	uint64_t kx = (uint64_t)(uint32_t)cx & 0xFFFFFFu;
	uint64_t kz = (uint64_t)(uint32_t)cz & 0xFFFFFFu;
	uint64_t ky = (uint64_t)(uint8_t)sy;
	return kx | (ky << 24) | (kz << 32);
}
static inline void beryl_section_key_unpack(uint64_t key, int *cx, int *sy, int *cz) {
	*cx = (int32_t)((uint32_t)(key & 0xFFFFFFu) << 8) >> 8;
	*sy = (int32_t)(uint8_t)((key >> 24) & 0xFFu);
	*cz = (int32_t)((uint32_t)((key >> 32) & 0xFFFFFFu) << 8) >> 8;
}

/* ------------------------------------------------------------------ limits */
#define BERYL_MAX_SECTIONS_PER_FRAME 16384
#define BERYL_MAX_DRAW_COMMANDS        4096

/* ------------------------------------------------------------------ logging */
typedef enum BerylLogLevel {
	BERYL_LOG_DEBUG = 0,
	BERYL_LOG_INFO  = 1,
	BERYL_LOG_WARN  = 2,
	BERYL_LOG_ERROR = 3
} BerylLogLevel;

typedef void (*beryl_log_fn)(void *user, BerylLogLevel level, const char *msg);

void  beryl_log_set_sink(beryl_log_fn fn, void *user);
void  beryl_log_set_level(BerylLogLevel level);
void  beryl_log_write(BerylLogLevel level, const char *fmt, ...)
#if defined(__GNUC__)
	__attribute__((format(printf, 2, 3)))
#endif
	;

#define BERYL_LOGD(...) beryl_log_write(BERYL_LOG_DEBUG, __VA_ARGS__)
#define BERYL_LOGI(...) beryl_log_write(BERYL_LOG_INFO,  __VA_ARGS__)
#define BERYL_LOGW(...) beryl_log_write(BERYL_LOG_WARN,  __VA_ARGS__)
#define BERYL_LOGE(...) beryl_log_write(BERYL_LOG_ERROR, __VA_ARGS__)

/* Assertions that stay in release builds: engine invariants (a corrupt chunk
 * must not silently produce garbage meshes) are checked unconditionally, and
 * the abort is cheap compared to a wrong frame. */
void  beryl_assert_fail(const char *expr, const char *file, int line, const char *fmt, ...);
#define BERYL_ASSERT(expr, ...) \
	do { if (!(expr)) beryl_assert_fail(#expr, __FILE__, __LINE__, __VA_ARGS__); } while (0)

/* ---------------------------------------------------------------- counters */
/* Coarse counters, exported to the overlay/telemetry. Incremented from worker
 * threads, hence relaxed atomics; a slightly stale count is fine, a torn one
 * is not. */
typedef enum BerylCounter {
	BERYL_CTR_SECTIONS_GENERATED = 0,
	BERYL_CTR_SECTIONS_MESHED,
	BERYL_CTR_SECTIONS_UPLOADED,
	BERYL_CTR_VERTICES,
	BERYL_CTR_INDICES,
	BERYL_CTR_QUADS,
	BERYL_CTR_QUADS_CULLED_FACE,     /* faces dropped by neighbour occlusion   */
	BERYL_CTR_QUADS_CULLED_LEAVES,   /* faces dropped by leaf interior culling */
	BERYL_CTR_QUADS_CULLED_FRUSTUM,  /* sections skipped by frustum           */
	BERYL_CTR_SECTIONS_CULLED_OCCL,  /* sections skipped by occlusion graph    */
	BERYL_CTR_SKYLIGHT_VISITS,
	BERYL_CTR_BLOCKLIGHT_VISITS,
	BERYL_CTR_DRAW_CALLS,
	BERYL_CTR_COUNT
} BerylCounter;

void     beryl_ctr_add(BerylCounter c, int64_t n);
int64_t  beryl_ctr_get(BerylCounter c);
void     beryl_ctr_reset(void);

/* ------------------------------------------------------------------ time */
uint64_t beryl_time_ns(void);      /* monotonic */
double   beryl_time_ms(void);

/* ----------------------------------------------------------------- helpers */
#define BERYL_MIN(a, b) ((a) < (b) ? (a) : (b))
#define BERYL_MAX(a, b) ((a) > (b) ? (a) : (b))
#define BERYL_CLAMP(v, lo, hi) ((v) < (lo) ? (lo) : ((v) > (hi) ? (hi) : (v)))
#define BERYL_ARRAY_LEN(a) ((int)(sizeof(a) / sizeof((a)[0])))
#define BERYL_KB(x) ((size_t)(x) * 1024u)

#endif /* BERYL_BCORE_H */
