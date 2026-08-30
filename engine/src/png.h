/* png.h -- a PNG writer with no zlib dependency.
 *
 * DEFLATE with a real LZ77 matcher and the fixed-Huffman block type: enough
 * compression that a 1280x720 screenshot lands in the tens of kilobytes for
 * flat-shaded voxel art, without dragging in a third-party build. The encoder is
 * deliberately small: per-row "Sub"/"Up" adaptive filtering plus one match
 * finder is all the win that matters for this kind of image.
 */
#ifndef BERYL_PNG_H
#define BERYL_PNG_H

#include "bcore.h"

/* Writes an RGBA8 image. Returns 0 on success. */
int beryl_png_write_rgba8(const char *path, int width, int height, const uint8_t *rgba);
/* Same, to memory. `out` is malloc'd; caller frees. */
int beryl_png_encode_rgba8(int width, int height, const uint8_t *rgba, uint8_t **out, size_t *out_len);
/* RGB24 convenience: converts then encodes. */
int beryl_png_write_rgb8(const char *path, int width, int height, const uint8_t *rgb);

#endif /* BERYL_PNG_H */
