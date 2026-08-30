/* png.c -- PNG container + DEFLATE encoder. */
#include "png.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* ------------------------------------------------------------------- crc --- */
static uint32_t crc_table[256];
static bool crc_ready = false;

static void crc_init(void) {
	for (uint32_t i = 0; i < 256; i++) {
		uint32_t c = i;
		for (int k = 0; k < 8; k++) c = (c & 1) ? 0xEDB88320u ^ (c >> 1) : c >> 1;
		crc_table[i] = c;
	}
	crc_ready = true;
}
static uint32_t crc32_buf(uint32_t crc, const uint8_t *p, size_t n) {
	if (!crc_ready) crc_init();
	crc = ~crc;
	for (size_t i = 0; i < n; i++) crc = crc_table[(crc ^ p[i]) & 0xFF] ^ (crc >> 8);
	return ~crc;
}

static void chunk_put(uint8_t **dst, const char *type, const uint8_t *data, size_t len) {
	uint8_t *p = *dst;
	p[0] = (uint8_t)(len >> 24); p[1] = (uint8_t)(len >> 16);
	p[2] = (uint8_t)(len >> 8);  p[3] = (uint8_t)len;
	memcpy(p + 4, type, 4);
	if (len) memcpy(p + 8, data, len);
	uint32_t c = crc32_buf(0, p + 4, len + 4);
	p[8 + len] = (uint8_t)(c >> 24); p[9 + len] = (uint8_t)(c >> 16);
	p[10 + len] = (uint8_t)(c >> 8); p[11 + len] = (uint8_t)c;
	*dst = p + 12 + len;
}

/* ---------------------------------------------------------------- deflate -- */
typedef struct BitWriter {
	uint8_t *out;
	size_t   cap;
	size_t   pos;      /* bytes written */
	uint32_t bits;
	int      nbits;
	int      err;
} BitWriter;

static void bw_init(BitWriter *w, uint8_t *out, size_t cap) {
	w->out = out; w->cap = cap; w->pos = 0; w->bits = 0; w->nbits = 0; w->err = 0;
}
static inline void bw_bits(BitWriter *w, uint32_t value, int count) {
	w->bits |= (value & ((1u << count) - 1u)) << w->nbits;
	w->nbits += count;
	while (w->nbits >= 8) {
		if (w->pos >= w->cap) { w->err = 1; return; }
		w->out[w->pos++] = (uint8_t)(w->bits & 0xFF);
		w->bits >>= 8;
		w->nbits -= 8;
	}
}
/* Huffman codes go out MSB-first. */
static inline void bw_code(BitWriter *w, uint32_t code, int len) {
	uint32_t rev = 0;
	for (int i = 0; i < len; i++) rev = (rev << 1) | ((code >> i) & 1u);
	bw_bits(w, rev, len);
}
static void bw_flush_bits(BitWriter *w) {
	if (w->nbits > 0) {
		if (w->pos >= w->cap) { w->err = 1; return; }
		w->out[w->pos++] = (uint8_t)(w->bits & 0xFF);
		w->bits = 0; w->nbits = 0;
	}
}

/* Fixed Huffman code lengths, straight out of RFC1951 3.2.6. */
static int fixed_len_lit(unsigned sym) {
	if (sym < 144) return 8;
	if (sym < 256) return 9;
	if (sym < 280) return 7;
	return 8;
}
static unsigned fixed_code_lit(unsigned sym) {
	if (sym < 144) return 0x30 + sym;
	if (sym < 256) return 0x190 + (sym - 144);
	if (sym < 280) return sym - 256;
	return 0xC0 + (sym - 280);
}

typedef struct LenCode { uint16_t sym; uint8_t extra; uint16_t base; } LenCode;

/* length code table: sym 257..285 */
static const LenCode k_len[29] = {
	{257,0,3},{258,0,4},{259,0,5},{260,0,6},{261,0,7},{262,0,8},{263,0,9},{264,0,10},
	{265,1,11},{266,1,13},{267,1,15},{268,1,17},{269,2,19},{270,2,23},{271,2,27},{272,2,31},
	{273,3,35},{274,3,43},{275,3,51},{276,3,59},{277,4,67},{278,4,83},{279,4,99},{280,4,115},
	{281,5,131},{282,5,163},{283,5,195},{284,5,227},{285,0,258}
};
/* distance code table: sym 0..29 */
static const LenCode k_dist[30] = {
	{0,0,1},{1,0,2},{2,0,3},{3,0,4},{4,1,5},{5,1,7},{6,2,9},{7,2,13},{8,3,17},{9,3,25},
	{10,4,33},{11,4,49},{12,5,65},{13,5,97},{14,6,129},{15,6,193},{16,7,257},{17,7,385},
	{18,8,513},{19,8,769},{20,9,1025},{21,9,1537},{22,10,2049},{23,10,3073},{24,11,4097},
	{25,11,6145},{26,12,8193},{27,12,12289},{28,13,16385},{29,13,24577}
};

static void emit_length(BitWriter *w, unsigned len) {
	int idx = 0;
	for (int i = 28; i >= 0; i--) {
		if (len >= k_len[i].base) { idx = i; break; }
	}
	unsigned sym = k_len[idx].sym;
	bw_code(w, fixed_code_lit(sym), fixed_len_lit(sym));
	if (k_len[idx].extra) bw_bits(w, len - k_len[idx].base, k_len[idx].extra);
}
static void emit_distance(BitWriter *w, unsigned dist) {
	int idx = 0;
	for (int i = 29; i >= 0; i--) {
		if (dist >= k_dist[i].base) { idx = i; break; }
	}
	bw_code(w, k_dist[idx].sym, 5);
	if (k_dist[idx].extra) bw_bits(w, dist - k_dist[idx].base, k_dist[idx].extra);
}

#define LZ_WINDOW   32768
#define LZ_MIN_MATCH 3
#define LZ_MAX_MATCH  258

/* Hash-chain match finder: head/two-byte index plus a single chain per
 * position. Cheap to build (one pass) and good enough on 4-byte-per-pixel data. */
static int deflate_encode(const uint8_t *in, size_t n, uint8_t *out, size_t out_cap, size_t *out_len) {
	BitWriter w;
	bw_init(&w, out, out_cap);
	bw_bits(&w, 1, 1);   /* BFINAL */
	bw_bits(&w, 1, 2);   /* BTYPE = 01: fixed Huffman */

	const int HASH_BITS = 15;
	const int HASH_SIZE = 1 << HASH_BITS;
	int32_t *head = (int32_t *)malloc(sizeof(int32_t) * (size_t)HASH_SIZE);
	int32_t *prev = (int32_t *)malloc(sizeof(int32_t) * n);
	if (!head || !prev) { free(head); free(prev); return -1; }
	for (int i = 0; i < HASH_SIZE; i++) head[i] = -1;

	size_t i = 0;
	while (i < n) {
		int best_len = 0, best_dist = 0;
		if (i + LZ_MIN_MATCH <= n) {
			uint32_t h = ((uint32_t)in[i] * 19777u ^ (uint32_t)in[i + 1] * 2789u ^ (uint32_t)in[i + 2]) & (HASH_SIZE - 1);
			int32_t cand = head[h];
			int chain = 32;
			while (cand >= 0 && chain-- > 0) {
				if ((int32_t)(i - (size_t)cand) > LZ_WINDOW) break;
				int len = 0;
				while (len < LZ_MAX_MATCH && i + (size_t)len < n &&
				       in[cand + len] == in[i + len]) len++;
				if (len > best_len) {
					best_len = len;
					best_dist = (int)(i - (size_t)cand);
					if (len >= LZ_MAX_MATCH) break;
				}
				cand = prev[cand];
			}
			prev[i] = head[h];
			head[h] = (int32_t)i;
			/* Index the interior positions too, so a later match can start there. */
			for (int k = 1; k < best_len && i + (size_t)k < n - 2; k++) {
				uint32_t hh = ((uint32_t)in[i + k] * 19777u ^ (uint32_t)in[i + k + 1] * 2789u ^
				               (uint32_t)in[i + k + 2]) & (HASH_SIZE - 1);
				prev[i + (size_t)k] = head[hh];
				head[hh] = (int32_t)(i + (size_t)k);
			}
		}

		if (best_len >= LZ_MIN_MATCH) {
			emit_length(&w, (unsigned)best_len);
			emit_distance(&w, (unsigned)best_dist);
			i += (size_t)best_len;
		} else {
			unsigned sym = in[i];
			bw_code(&w, fixed_code_lit(sym), fixed_len_lit(sym));
			i++;
		}
	}
	bw_code(&w, 0, 7);   /* end of block: symbol 256 in fixed coding is 0000000 */
	bw_flush_bits(&w);
	free(head);
	free(prev);
	if (w.err) return -1;
	*out_len = w.pos;
	return 0;
}

/* Uncompressed DEFLATE blocks: the fallback when the match finder cannot make
 * the image smaller, which is a legal (if chatty) encoder. */
static void deflate_stored(const uint8_t *in, size_t n, uint8_t *out, size_t cap, size_t *out_len) {
	size_t pos = 0, wpos = 0;
	while (pos < n || pos == 0) {
		size_t block = BERYL_MIN(n - pos, (size_t)65535);
		if (wpos + 5 + block > cap) { *out_len = 0; return; }
		int last = (pos + block >= n) ? 1 : 0;
		out[wpos++] = (uint8_t)last;
		out[wpos++] = (uint8_t)(block & 0xFF);
		out[wpos++] = (uint8_t)(block >> 8);
		out[wpos++] = (uint8_t)(~block & 0xFF);
		out[wpos++] = (uint8_t)((~block >> 8) & 0xFF);
		memcpy(out + wpos, in + pos, block);
		wpos += block;
		pos += block;
		if (last) break;
	}
	*out_len = wpos;
}

static uint32_t adler32_buf(const uint8_t *p, size_t n) {
	uint32_t a = 1, b = 0;
	for (size_t i = 0; i < n; i++) {
		a = (a + p[i]) % 65521u;
		b = (b + a) % 65521u;
	}
	return (b << 16) | a;
}

/* Filter selection per row: None / Sub / Up, cheapest wins. Keeps real images
 * small without the cost of the Paeth search. */
static uint8_t *make_filtered(const uint8_t *rgba, int w, int h, size_t *out_size) {
	size_t stride = (size_t)w * 4u;
	uint8_t *f = (uint8_t *)malloc((stride + 1) * (size_t)h);
	if (!f) return NULL;
	for (int y = 0; y < h; y++) {
		const uint8_t *row = rgba + (size_t)y * stride;
		const uint8_t *up = y > 0 ? rgba + (size_t)(y - 1) * stride : NULL;
		uint8_t *dst = f + (size_t)y * (stride + 1);
		int best = 0;
		size_t best_sum = (size_t)-1;
		for (int t = 0; t < 3; t++) {
			size_t sum = 0;
			for (size_t x = 0; x < stride; x++) {
				int v;
				if (t == 0) v = row[x];
				else if (t == 1) v = (int)row[x] - (int)(x >= 4 ? row[x - 4] : 0);
				else v = up ? (int)row[x] - (int)up[x] : (int)row[x];
				if (v < 0) v = -v;
				sum += (size_t)v;
			}
			if (sum < best_sum) { best_sum = sum; best = t; }
		}
		dst[0] = (uint8_t)best;
		for (size_t x = 0; x < stride; x++) {
			int v;
			if (best == 0) v = row[x];
			else if (best == 1) v = (int)row[x] - (int)(x >= 4 ? row[x - 4] : 0);
			else v = up ? (int)row[x] - (int)up[x] : (int)row[x];
			dst[1 + x] = (uint8_t)v;
		}
	}
	*out_size = (stride + 1) * (size_t)h;
	return f;
}

int beryl_png_encode_rgba8(int width, int height, const uint8_t *rgba, uint8_t **out, size_t *out_len) {
	if (width <= 0 || height <= 0 || !rgba || !out || !out_len) return -1;

	size_t raw_len = 0;
	uint8_t *raw = make_filtered(rgba, width, height, &raw_len);
	if (!raw) return -1;

	/* DEFLATE expands when nothing matches: budget 1.06x plus block headers. */
	size_t zcap = raw_len + raw_len / 16 + 65536;
	uint8_t *zbuf = (uint8_t *)malloc(zcap);
	size_t zlen = 0;
	if (!zbuf) { free(raw); return -1; }
	if (deflate_encode(raw, raw_len, zbuf, zcap, &zlen) != 0) {
		deflate_stored(raw, raw_len, zbuf, zcap, &zlen);
	}
	uint32_t adler = adler32_buf(raw, raw_len);
	free(raw);

	size_t idat_len = zlen + 6;
	uint8_t *idat = (uint8_t *)malloc(idat_len);
	if (!idat) { free(zbuf); return -1; }
	idat[0] = 0x78;                 /* CM=8, CINFO=7 (32KiB window)             */
	idat[1] = 0x01;                 /* FLEVEL=fastest, no preset dictionary     */
	memcpy(idat + 2, zbuf, zlen);
	idat[2 + zlen + 0] = (uint8_t)(adler >> 24);
	idat[2 + zlen + 1] = (uint8_t)(adler >> 16);
	idat[2 + zlen + 2] = (uint8_t)(adler >> 8);
	idat[2 + zlen + 3] = (uint8_t)adler;
	free(zbuf);

	size_t total = 8 + (12 + 13) + (12 + idat_len) + 12;
	uint8_t *buf = (uint8_t *)malloc(total);
	if (!buf) { free(idat); return -1; }
	uint8_t *p = buf;
	memcpy(p, "\x89PNG\r\n\x1a\n", 8);
	p += 8;

	uint8_t ihdr[13];
	ihdr[0] = (uint8_t)(width >> 24);  ihdr[1] = (uint8_t)(width >> 16);
	ihdr[2] = (uint8_t)(width >> 8);   ihdr[3] = (uint8_t)width;
	ihdr[4] = (uint8_t)(height >> 24); ihdr[5] = (uint8_t)(height >> 16);
	ihdr[6] = (uint8_t)(height >> 8);  ihdr[7] = (uint8_t)height;
	ihdr[8] = 8;    /* bit depth                        */
	ihdr[9] = 6;    /* colour type: RGBA                */
	ihdr[10] = 0;   /* deflate                          */
	ihdr[11] = 0;   /* no filter                        */
	ihdr[12] = 0;   /* no interlace                     */
	chunk_put(&p, "IHDR", ihdr, sizeof(ihdr));
	chunk_put(&p, "IDAT", idat, idat_len);
	chunk_put(&p, "IEND", NULL, 0);
	free(idat);

	*out = buf;
	*out_len = total;
	return 0;
}

int beryl_png_write_rgba8(const char *path, int width, int height, const uint8_t *rgba) {
	uint8_t *buf = NULL;
	size_t len = 0;
	if (beryl_png_encode_rgba8(width, height, rgba, &buf, &len) != 0) return -1;
	FILE *f = fopen(path, "wb");
	if (!f) { free(buf); return -1; }
	size_t w = fwrite(buf, 1, len, f);
	fclose(f);
	free(buf);
	return w == len ? 0 : -1;
}

int beryl_png_write_rgb8(const char *path, int width, int height, const uint8_t *rgb) {
	size_t n = (size_t)width * (size_t)height;
	uint8_t *rgba = (uint8_t *)malloc(n * 4);
	if (!rgba) return -1;
	for (size_t i = 0; i < n; i++) {
		rgba[i * 4 + 0] = rgb[i * 3 + 0];
		rgba[i * 4 + 1] = rgb[i * 3 + 1];
		rgba[i * 4 + 2] = rgb[i * 3 + 2];
		rgba[i * 4 + 3] = 255;
	}
	int r = beryl_png_write_rgba8(path, width, height, rgba);
	free(rgba);
	return r;
}
