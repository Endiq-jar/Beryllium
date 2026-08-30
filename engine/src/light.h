/* light.h -- skylight + block light, baked into the section light arrays.
 *
 * Same model as Minecraft, because the model is what makes the mesher's job
 * cheap: skylight flows down unimpeded through "sky transparent" blocks and
 * spreads sideways losing 1 per step plus the neighbour's attenuation; emitters
 * (torch, glowstone, lava) inject block light. Both are stored in one packed
 * byte per voxel (sky in the low nibble, block in the high nibble), so the
 * mesher can copy light into the vertex stream with a single load.
 */
#ifndef BERYL_LIGHT_H
#define BERYL_LIGHT_H

#include "world.h"

/* Full recompute of the lighting for the chunk box [x0,x1] x [z0,z1].
 * Used after generation and by tests; it is O(voxels * a few) and needs no
 * history, so it is the reference the incremental path is validated against. */
void beryl_light_relight_area(BerylWorld *w, int32_t x0, int32_t z0, int32_t x1, int32_t z1);

/* Incremental update after beryl_world_set_block(). Queued so that a burst of
 * edits (tree placement, world-gen decoration) coalesces into one pass. */
void beryl_light_queue_edit(BerylWorld *w, int wx, int wy, int wz);
/* Same, but records the light emission of the block that *was* there. Removing a
 * lamp must retract the light it cast, and by the time the queue runs the voxel is
 * already empty, so the information has to travel with the edit. */
void beryl_light_queue_edit_source(BerylWorld *w, int wx, int wy, int wz, int old_emission);
/* Drains the queue; returns the number of positions processed. Bounds keep a
 * single edit from doing unbounded work inside a frame; leftover positions stay
 * queued and finish on the next call (progress, never staleness). */
int  beryl_light_process_queue(BerylWorld *w, int max_positions);

/* Debug/validation helpers. */
bool beryl_light_validate_section(BerylWorld *w, BerylSection *s, char *err, size_t err_len);

#endif /* BERYL_LIGHT_H */
