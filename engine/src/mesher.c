/* mesher.c -- greedy coplanar meshing with baked AO + baked light.
 *
 * One sweep per axis. For axis d we visit the 17 boundaries between the 16 voxel
 * planes plus the two neighbour planes; at each boundary, cell A sits on the low
 * side (index s-1) and cell B on the high side (index s), and both may own a
 * visible face lying in that shared plane. Each side gets its own 16x16 mask so
 * the greedy merge never has to carry a "which way does it face" bit inside the
 * entry -- same mask value means same block and same face, which is precisely the
 * condition under which merging is texture-, light- and AO-safe.
 */
#include "mesher.h"

#include <stdlib.h>
#include <string.h>

#define SLICE_SIDE (3 * BERYL_SECTION_SIDE)

/* Centre-section-local coord [-16,32) -> slice coord [0,48). Faces are only
 * emitted for the centre section, so quad corners land on [0,16] -> the slice
 * indices used below are always in [16,32]. */
#define SL(x) ((x) + BERYL_SECTION_SIDE)

typedef struct MeshCtx {
	const BerylSlice *slice;
	BerylMeshStats    stats;
} MeshCtx;

static BerylMeshStats g_last_stats;

typedef struct LayerBuf {
	BerylVertex *verts;  size_t vert_len, vert_cap;
	uint32_t    *idx;    size_t idx_len,  idx_cap;
	int          err;
} LayerBuf;

static int layerbuf_grow_verts(LayerBuf *l, size_t need) {
	if (need <= l->vert_cap) return 0;
	size_t cap = l->vert_cap ? l->vert_cap : 128;
	while (cap < need) cap *= 2;
	BerylVertex *p = (BerylVertex *)realloc(l->verts, cap * sizeof(BerylVertex));
	if (!p) { l->err = 1; return -1; }
	l->verts = p; l->vert_cap = cap;
	return 0;
}
static int layerbuf_grow_idx(LayerBuf *l, size_t need) {
	if (need <= l->idx_cap) return 0;
	size_t cap = l->idx_cap ? l->idx_cap : 256;
	while (cap < need) cap *= 2;
	uint32_t *p = (uint32_t *)realloc(l->idx, cap * sizeof(uint32_t));
	if (!p) { l->err = 1; return -1; }
	l->idx = p; l->idx_cap = cap;
	return 0;
}
static void layerbuf_free(LayerBuf *l) {
	free(l->verts); free(l->idx);
	memset(l, 0, sizeof(*l));
}

/* ------------------------------------------------------------- sampling ---- */
static inline beryl_bid at(const MeshCtx *c, int x, int y, int z) {
	return beryl_slice_get_state(c->slice, x, y, z);
}
static inline bool at_opaque(const MeshCtx *c, int x, int y, int z) {
	if (!beryl_slice_has(c->slice, x, y, z)) return false;
	return beryl_block_is_opaque(beryl_slice_get_state_unchecked(c->slice, x, y, z));
}
/* AO/light taps are always inside the slice by construction (see emit_rect), so
 * the unchecked form is both faster and NULL-safe at the unloaded frontier,
 * where a missing neighbour reads as unlit air. */
static inline uint8_t at_light(const MeshCtx *c, int x, int y, int z) {
	if (!beryl_slice_has(c->slice, x, y, z)) return 0;
	return beryl_slice_get_light_unchecked(c->slice, x, y, z);
}

/* Bakes AO level + packed light for one corner of a (possibly merged) quad.
 *
 * corner (cu, cv) is a vertex coordinate of the rectangle in slice coords; the
 * four cells touching that corner in the *air-side* plane p_out are the samples.
 * The cell that belongs to the quad itself is `base`: the three others are the
 * two sides and the diagonal. Vanilla's rule applies -- if both side cells are
 * solid the corner is fully shadowed regardless of the diagonal -- and light is
 * the average of all four air cells, which is what makes an open doorway fall off
 * smoothly across a merged face instead of stepping per block. */
static void bake_corner(const MeshCtx *c, int axis, int u_ax, int v_ax, int p_out,
                        int cu, int cv, int base_u, int base_v,
                        int *ao_out, uint8_t *light_out) {
	int cell_u[4] = { cu - 1, cu, cu - 1, cu };
	int cell_v[4] = { cv - 1, cv - 1, cv, cv };

	int base = 0;
	int found = -1;
	for (int i = 0; i < 4; i++) {
		if (cell_u[i] == base_u && cell_v[i] == base_v) { found = i; break; }
	}
	base = found < 0 ? 0 : found;

	int p[4][3];
	for (int i = 0; i < 4; i++) {
		p[i][axis] = p_out;
		p[i][u_ax] = cell_u[i];
		p[i][v_ax] = cell_v[i];
	}

	int side1 = -1, side2 = -1, diag = -1;
	for (int i = 0; i < 4; i++) {
		if (i == base) continue;
		bool du = p[i][u_ax] != p[base][u_ax];
		bool dv = p[i][v_ax] != p[base][v_ax];
		if (du && !dv) side1 = i;
		else if (dv && !du) side2 = i;
		else if (du && dv) diag = i;
	}
	int s1 = side1 >= 0 ? (int)at_opaque(c, p[side1][0], p[side1][1], p[side1][2]) : 0;
	int s2 = side2 >= 0 ? (int)at_opaque(c, p[side2][0], p[side2][1], p[side2][2]) : 0;
	int sd = diag  >= 0 ? (int)at_opaque(c, p[diag][0],  p[diag][1],  p[diag][2])  : 0;

	int ao = (s1 && s2) ? 0 : (3 - (s1 + s2 + sd));

	int sky = 0, blk = 0;
	for (int i = 0; i < 4; i++) {
		uint8_t lv = at_light(c, p[i][0], p[i][1], p[i][2]);
		sky += lv & 0xF;
		blk += (lv >> 4) & 0xF;
	}
	*ao_out = ao;
	*light_out = (uint8_t)((((blk + 2) >> 2) << 4) | (((sky + 2) >> 2) & 0xF));
}

/* The six faces grouped by axis: for axis d, `side` 0 is the negative face and
 * 1 the positive one, matching beryl_face_axis()/beryl_face_sign(). */
static const int k_face_neg[3] = { BERYL_FACE_WEST,  BERYL_FACE_DOWN, BERYL_FACE_NORTH };
static const int k_face_pos[3] = { BERYL_FACE_EAST,  BERYL_FACE_UP,   BERYL_FACE_SOUTH };

/* Emits one quad (4 vertices, 6 indices) for a merged rectangle.
 *   d, u, v : axis triple; s : boundary plane (slice coord); side: 0 = A face
 *   (normal -d, the block is at s-1) / 1 = B face (normal +d, block at s).
 *   i0, j0, w, h : rectangle in in-plane cell coords (section-local 0..16). */
/* Face index for axis `d` at boundary plane `s`: faces owned by the low side
 * (cell s-1) point +d, faces owned by the high side (cell s) point -d. */
static inline int boundary_face(int d, int from_low_side) {
	return from_low_side ? k_face_pos[d] : k_face_neg[d];
}

/* `from_a` is 1 when the quad belongs to the cell at slice s-1 (normal +d) and
 * 0 when it belongs to the cell at slice s (normal -d). */
static void emit_rect(LayerBuf *l, MeshCtx *c, const BerylBlockInfo *bi, int tile,
                      int d, int u, int v, int s, int from_a,
                      int i0, int j0, int w, int h) {
	uint8_t flags = beryl_vertex_pack_flags(bi->tint_index,
	                                       (bi->flags & BERYL_BFLG_CUTOUT) != 0,
	                                       (bi->flags & BERYL_BFLG_TRANSLUCENT) != 0);

	/* The quad's own coordinate along the axis: both sides of a shared boundary
	 * lie in the same plane. */
	int quad_p = s;
	/* Air-side plane the AO/light samples come from. */
	int p_out = from_a ? s : s - 1;

	int gu0 = SL(i0), gu1 = gu0 + w;
	int gv0 = SL(j0), gv1 = gv0 + h;

	int cu[4] = { gu0, gu1, gu1, gu0 };
	int cv[4] = { gv0, gv0, gv1, gv1 };

	BerylVertex tmp[4];
	memset(tmp, 0, sizeof(tmp));
	for (int k = 0; k < 4; k++) {
		int cell_u = (cu[k] == gu0) ? gu0 : gu1 - 1;
		int cell_v = (cv[k] == gv0) ? gv0 : gv1 - 1;

		int pos[3];
		pos[d] = quad_p;
		pos[u] = cu[k];
		pos[v] = cv[k];

		int ao; uint8_t light;
		bake_corner(c, d, u, v, p_out, cu[k], cv[k], cell_u, cell_v, &ao, &light);

		int face = boundary_face(d, from_a);

		/* Section-local, 8.8 fixed point (see mesh_format.h): the shader adds the
		 * section origin in float32, so positions stay exact anywhere in the world. */
		tmp[k].pos_x = (uint16_t)((pos[0] - BERYL_SECTION_SIDE) * BERYL_POS_SCALE);
		tmp[k].pos_y = (uint16_t)((pos[1] - BERYL_SECTION_SIDE) * BERYL_POS_SCALE);
		tmp[k].pos_z = (uint16_t)((pos[2] - BERYL_SECTION_SIDE) * BERYL_POS_SCALE);
		/* Grid-aligned in-plane texture coordinates, in 1/16 block units. The
		 * shader takes fract() of these, so tiling repeats once per block on any
		 * merged size and stays aligned with the neighbouring section. */
		tmp[k].uv_s = (uint16_t)(cu[k] * BERYL_UV_SCALE);
		tmp[k].uv_t = (uint16_t)(cv[k] * BERYL_UV_SCALE);
		tmp[k].ao_face = (uint8_t)((ao & BERYL_AO_LEVEL_MASK) | ((face & 7) << BERYL_FACE_SHIFT));
		tmp[k].light = light;
		tmp[k].tile = (uint8_t)tile;
		tmp[k].flags = flags;
	}

	/* Winding: (0,1,2,3) above is counter-clockwise when viewed from +d, because
	 * (u, v, d) is a right-handed triple for our axis table. Back-facing quads
	 * swap the two "u" corners instead of reversing the order, which keeps the
	 * texture upright on the far side of the block. */
	int order[4] = { 0, 1, 2, 3 };
	if (!from_a) {
		order[0] = 1; order[1] = 0; order[2] = 3; order[3] = 2;
	}

	if (layerbuf_grow_verts(l, l->vert_len + 4)) return;
	if (layerbuf_grow_idx(l, l->idx_len + 6)) return;
	size_t base = l->vert_len;
	for (int k = 0; k < 4; k++) l->verts[base + (size_t)k] = tmp[order[k]];
	static const uint32_t k_tris[6] = { 0, 1, 2, 0, 2, 3 };
	for (int k = 0; k < 6; k++) l->idx[l->idx_len++] = (uint32_t)(base + k_tris[k]);
	l->vert_len += 4;
}

static bool build_from_slice(const BerylSlice *slice, BerylSectionMesh *out) {
	BerylSection *centre = slice->sec[1][1][1];
	if (!centre) return false;

	beryl_section_recompute_derived(centre);
	beryl_section_mesh_reset(out);

	MeshCtx ctx;
	memset(&ctx, 0, sizeof(ctx));
	ctx.slice = slice;

	LayerBuf bufs[BERYL_LAYER_COUNT];
	memset(bufs, 0, sizeof(bufs));

	static const int k_u[3] = { 1, 2, 0 };
	static const int k_v[3] = { 2, 0, 1 };

	int mask_a[BERYL_SECTION_AREA];  /* faces of the low-side cell, normal +d */
	int mask_b[BERYL_SECTION_AREA];  /* faces of the high-side cell, normal -d */
	uint8_t used[BERYL_SECTION_AREA];

	for (int d = 0; d < 3; d++) {
		const int u = k_u[d], v = k_v[d];
		for (int s = 0; s <= BERYL_SECTION_SIDE; s++) {
			int a_p = s - 1, b_p = s;

			memset(mask_a, 0, sizeof(mask_a));
			memset(mask_b, 0, sizeof(mask_b));
			/* A section only ever emits faces of blocks inside itself; the far
			 * border of the neighbour is produced by the neighbour's own build. */
			const bool a_inside = (s - 1) >= 0 && (s - 1) < BERYL_SECTION_SIDE;
			const bool b_inside = s >= 0 && s < BERYL_SECTION_SIDE;

			for (int j = 0; j < BERYL_SECTION_SIDE; j++) {
				for (int i = 0; i < BERYL_SECTION_SIDE; i++) {
					int pa[3], pb[3];
					pa[d] = SL(a_p); pb[d] = SL(b_p);
					pa[u] = pb[u] = SL(i);
					pa[v] = pb[v] = SL(j);
					beryl_bid a = at(&ctx, pa[0], pa[1], pa[2]);
					beryl_bid b = at(&ctx, pb[0], pb[1], pb[2]);

					int cell = j * BERYL_SECTION_SIDE + i;
					ctx.stats.faces_examined += 2;

					if (a != BERYL_BLOCK_AIR && a_inside && beryl_face_visible(a, b)) {
						mask_a[cell] = (int)a;
					} else if (a != BERYL_BLOCK_AIR && a_inside) {
						if (a == b && beryl_block_flag(a, BERYL_BFLG_SAME_TYPE_CULL)) {
							ctx.stats.quads_culled_same_type++;
						} else {
							ctx.stats.quads_culled_by_neighbour++;
						}
					}
					if (b != BERYL_BLOCK_AIR && b_inside && beryl_face_visible(b, a)) {
						mask_b[cell] = (int)b;
					} else if (b != BERYL_BLOCK_AIR && b_inside) {
						if (a == b && beryl_block_flag(b, BERYL_BFLG_SAME_TYPE_CULL)) {
							ctx.stats.quads_culled_same_type++;
						} else {
							ctx.stats.quads_culled_by_neighbour++;
						}
					}
				}
			}

			for (int pass = 0; pass < 2; pass++) {
				const int *mask = pass ? mask_b : mask_a;
				memset(used, 0, sizeof(used));
				for (int j = 0; j < BERYL_SECTION_SIDE; j++) {
					for (int i = 0; i < BERYL_SECTION_SIDE; i++) {
						int cell = j * BERYL_SECTION_SIDE + i;
						int bid = mask[cell];
						if (!bid || used[cell]) continue;

						int w = 1;
						while (i + w < BERYL_SECTION_SIDE && !used[cell + w] &&
						       mask[cell + w] == bid) {
							w++;
						}
						int h = 1;
						for (; j + h < BERYL_SECTION_SIDE; h++) {
							int row = cell + h * BERYL_SECTION_SIDE;
							bool ok = true;
							for (int k = 0; k < w; k++) {
								if (used[row + k] || mask[row + k] != bid) { ok = false; break; }
							}
							if (!ok) break;
						}
						for (int r = 0; r < h; r++) {
							for (int k = 0; k < w; k++) {
								used[(j + r) * BERYL_SECTION_SIDE + i + k] = 1;
							}
						}
						if (w * h > 1) ctx.stats.merged_cells += (uint32_t)(w * h - 1);

						const BerylBlockInfo *bi = beryl_block_info((beryl_bid)bid);
						int face = boundary_face(d, pass == 0);
						int tile = bi->tiles[face];
						int layer = bi->render_layer < BERYL_LAYER_COUNT ? bi->render_layer : 0;
						emit_rect(&bufs[layer], &ctx, bi, tile, d, u, v, SL(s),
						          pass == 0, i, j, w, h);
						if (out->quad_total != UINT32_MAX) out->quad_total++;
						ctx.stats.quads[bi->render_layer < BERYL_LAYER_COUNT ? bi->render_layer : 0]++;
					}
				}
			}
		}
	}

	/* Tighten the section bounds from the geometry actually produced; the
	 * culler uses this to reject slabs whose empty halves would otherwise keep
	 * the whole 16^3 box "visible". */
	bool any = false;
	float bmin[3] = { 1e30f, 1e30f, 1e30f };
	float bmax[3] = { -1e30f, -1e30f, -1e30f };

	bool err = false;
	for (int i = 0; i < BERYL_LAYER_COUNT; i++) {
		err |= bufs[i].err != 0;
		free(out->layer[i].verts);
		free(out->layer[i].indices);
		out->layer[i].verts = bufs[i].verts;
		out->layer[i].vert_count = bufs[i].vert_len;
		out->layer[i].indices = bufs[i].idx;
		out->layer[i].index_count = bufs[i].idx_len;
		out->layer[i].quad_count = ctx.stats.quads[i];
		bufs[i].verts = NULL;
		bufs[i].idx = NULL;

		for (size_t k = 0; k < out->layer[i].vert_count; k++) {
			const BerylVertex *v = &out->layer[i].verts[k];
			any = true;
			float px = (float)v->pos_x / (float)BERYL_POS_SCALE;
			float py = (float)v->pos_y / (float)BERYL_POS_SCALE;
			float pz = (float)v->pos_z / (float)BERYL_POS_SCALE;
			if (px < bmin[0]) bmin[0] = px;
			if (py < bmin[1]) bmin[1] = py;
			if (pz < bmin[2]) bmin[2] = pz;
			if (px > bmax[0]) bmax[0] = px;
			if (py > bmax[1]) bmax[1] = py;
			if (pz > bmax[2]) bmax[2] = pz;
		}
	}
	for (int i = 0; i < BERYL_LAYER_COUNT; i++) layerbuf_free(&bufs[i]);
	if (err) return false;

	float ox = (float)slice->ccx * BERYL_SECTION_SIDE;
	float oy = (float)slice->csy * BERYL_SECTION_SIDE;
	float oz = (float)slice->ccz * BERYL_SECTION_SIDE;
	if (any) {
		/* Pad by one block: merged quads can carry texture bleed-free UVs but a
		 * 1-block slop keeps the culler conservative under float rounding. */
		out->bounds.min = beryl_vec3(ox + bmin[0] - 0.05f, oy + bmin[1] - 0.05f, oz + bmin[2] - 0.05f);
		out->bounds.max = beryl_vec3(ox + bmax[0] + 0.05f, oy + bmax[1] + 0.05f, oz + bmax[2] + 0.05f);
	} else {
		out->bounds.min = beryl_vec3(ox, oy, oz);
		out->bounds.max = beryl_vec3(ox, oy, oz);
	}

	out->source_revision = centre->revision;
	out->valid = true;

	beryl_ctr_add(BERYL_CTR_QUADS, out->quad_total);
	beryl_ctr_add(BERYL_CTR_QUADS_CULLED_FACE, ctx.stats.quads_culled_by_neighbour);
	beryl_ctr_add(BERYL_CTR_QUADS_CULLED_LEAVES, ctx.stats.quads_culled_same_type);
	ctx.stats.merge_ratio = out->quad_total > 0
	    ? (float)ctx.stats.faces_examined * 0.5f / (float)out->quad_total
	    : 0.0f;
	g_last_stats = ctx.stats;
	return true;
}

bool beryl_mesh_slice(const BerylSlice *slice, BerylSectionMesh *out) {
	return build_from_slice(slice, out);
}

bool beryl_mesh_section(BerylWorld *w, int32_t cx, int32_t csy, int32_t cz,
                        BerylSectionMesh *out) {
	BerylSlice slice;
	if (!beryl_world_fill_slice(w, cx, csy, cz, &slice)) return false;
	return build_from_slice(&slice, out);
}

void beryl_mesh_last_stats(BerylMeshStats *out) { *out = g_last_stats; }
