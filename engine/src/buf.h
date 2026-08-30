/* buf.h -- 16-byte-aligned growable byte buffer, the only allocator the
 * mesher and encoder use. Over-allocation is amortised (1.5x) and every buffer
 * keeps a persistent capacity so a rebuilt section reuses its storage instead of
 * mallocing per frame -- the single biggest allocation win for chunk meshing. */
#ifndef BERYL_BUF_H
#define BERYL_BUF_H

#include "bcore.h"
#include <stdlib.h>
#include <string.h>
#include <errno.h>

typedef struct BerylBuf {
	uint8_t *data;
	size_t   size;   /* bytes in use */
	size_t   cap;    /* bytes allocated */
	int      err;    /* sticky OOM: callers check once at the end */
} BerylBuf;

static inline void beryl_buf_init(BerylBuf *b) { b->data = NULL; b->size = 0; b->cap = 0; b->err = 0; }
static inline void beryl_buf_free(BerylBuf *b) { free(b->data); b->data = NULL; b->size = b->cap = 0; }
static inline void beryl_buf_reset(BerylBuf *b) { b->size = 0; }

static inline int beryl_buf_reserve(BerylBuf *b, size_t extra) {
	size_t need = b->size + extra;
	if (need <= b->cap) return 0;
	size_t cap = b->cap ? b->cap : 256;
	while (cap < need) cap += cap >> 1;
	cap = (cap + 15u) & ~(size_t)15u;
	uint8_t *p = (uint8_t *)realloc(b->data, cap);
	if (!p) { b->err = ENOMEM; return -1; }
	b->data = p;
	b->cap = cap;
	return 0;
}

static inline void beryl_buf_put(BerylBuf *b, const void *src, size_t n) {
	if (beryl_buf_reserve(b, n)) return;
	memcpy(b->data + b->size, src, n);
	b->size += n;
}
static inline void beryl_buf_put_u8(BerylBuf *b, uint8_t v) {
	if (beryl_buf_reserve(b, 1)) return;
	b->data[b->size++] = v;
}
static inline void beryl_buf_put_u32(BerylBuf *b, uint32_t v) {
	if (beryl_buf_reserve(b, 4)) return;
	memcpy(b->data + b->size, &v, 4);
	b->size += 4;
}
static inline int beryl_buf_ensure_zeroed(BerylBuf *b, size_t n) {
	if (beryl_buf_reserve(b, n)) return -1;
	memset(b->data + b->size, 0, n);
	b->size += n;
	return 0;
}

#endif /* BERYL_BUF_H */
