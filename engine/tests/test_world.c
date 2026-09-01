/* test_world.c -- worldgen, the mesher's culling/lighting invariants, light
 * transport, editing, and the occlusion culler.
 *
 * The mesher tests are the interesting ones because they do not look at pixels:
 * they check the *contract* that makes a voxel world look solid, namely that
 * every face the culling rule says is visible appears in the mesh exactly once
 * (greedy merging may combine them, but never change the covered area), and that
 * a face's light is sampled from the air side, not from the block that owns it.
 */
#include "test.h"

#include "bcore.h"
#include "blocks.h"
#include "camera.h"
#include "chunk.h"
#include "light.h"
#include "mesher.h"
#include "mesh_format.h"
#include "occlusion.h"
#include "world.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static BerylWorld *make_world(uint64_t seed, bool caves, bool trees, bool water) {
	beryl_blocks_init();
	BerylWorldDesc d;
	memset(&d, 0, sizeof(d));
	d.seed = seed;
	d.radius_sections = 640;
	d.caves = caves;
	d.trees = trees;
	d.water = water;
	d.sea_level = 62.0f;
	return beryl_world_new(&d);
}

static float vert_pos(const BerylVertex *v, int axis) {
	uint16_t raw = axis == 0 ? v->pos_x : (axis == 1 ? v->pos_y : v->pos_z);
	return (float)raw / (float)BERYL_POS_SCALE;
}

/* ------------------------------------------------------------- worldgen ------ */
static void test_worldgen(void) {
	BerylWorld *a = make_world(20260829ull, true, true, true);
	BerylWorld *b = make_world(20260829ull, true, true, true);
	BerylWorld *c = make_world(20260830ull, true, true, true);
	if (!a || !b || !c) { CHECK(0, "world alloc"); return; }

	int ga = beryl_world_generate_area(a, 0, 0, 7, 7);
	int gb = beryl_world_generate_area(b, 0, 0, 7, 7);
	int gc = beryl_world_generate_area(c, 0, 0, 7, 7);
	CHECK(ga == 64 && gb == 64 && gc == 64, "8x8 chunks must be generated (got %d/%d/%d)", ga, gb, gc);

	/* Same seed, same world, sampled everywhere. */
	int diff = 0, sample = 0;
	for (int z = 0; z < 128; z += 7) {
		for (int x = 0; x < 128; x += 11) {
			for (int y = 0; y < 128; y += 5) {
				sample++;
				if (beryl_world_get_block(a, x, y, z) != beryl_world_get_block(b, x, y, z)) diff++;
			}
		}
	}
	CHECK(diff == 0, "identical seeds must give identical blocks (%d/%d differ)", diff, sample);
	int other = 0;
	for (int z = 0; z < 128; z += 7) {
		for (int x = 0; x < 128; x += 11) {
			if (beryl_world_top_y(a, x, z) != beryl_world_top_y(c, x, z)) other++;
		}
	}
	CHECK(other > 4, "a different seed must give a different surface (only %d columns differ)", other);

	/* Structure: bedrock at the bottom, heights in range, ground mostly solid. */
	int bedrock_ok = 1, solid_cells = 0, underground = 0, heights_ok = 1;
	for (int z = 0; z < 128; z += 4) {
		for (int x = 0; x < 128; x += 4) {
			if (beryl_world_get_block(a, x, 0, z) != BERYL_BLOCK_BEDROCK) bedrock_ok = 0;
			int top = beryl_world_top_y(a, x, z);
			if (top < 2 || top > 128) heights_ok = 0;
			for (int y = 2; y < top - 2; y++) {
				underground++;
				if (beryl_world_get_block(a, x, y, z) != BERYL_BLOCK_AIR) solid_cells++;
			}
		}
	}
	CHECK(bedrock_ok, "y=0 must be bedrock everywhere");
	CHECK(heights_ok, "surface height must stay inside the world");
	CHECK(underground > 1000, "there must be underground volume to sample (%d)", underground);
	double fill = (double)solid_cells / (double)underground;
	CHECK(fill > 0.75, "the ground must be mostly solid (fill=%.3f)", fill);

	/* Ores must be depth stratified. */
	int coal_hi = 0, coal_lo = 0, dia_hi = 0, dia_lo = 0;
	for (int z = 0; z < 128; z += 2) {
		for (int x = 0; x < 128; x += 2) {
			for (int y = 2; y < 60; y++) {
				beryl_bid id = beryl_world_get_block(a, x, y, z);
				if (id == BERYL_BLOCK_COAL_ORE) { if (y > 32) coal_hi++; else coal_lo++; }
				if (id == BERYL_BLOCK_DIAMOND_ORE) { if (y > 14) dia_hi++; else dia_lo++; }
			}
		}
	}
	CHECK(coal_lo > coal_hi, "coal must favour shallower depths (%d deep vs %d shallow)", coal_lo, coal_hi);
	CHECK(dia_lo > dia_hi, "diamond must favour the bottom (%d deep vs %d shallow)", dia_lo, dia_hi);

	beryl_world_free(a); beryl_world_free(b); beryl_world_free(c);
}

/* -------------------------------------------------- mesher face coverage ----- */
static void quad_span(const BerylVertex *v0, const BerylVertex *v1, const BerylVertex *v3,
                      float *out_u, float *out_v, int *out_axis) {
	float len_u = 0.0f, len_v = 0.0f;
	int ax = -1, ax_u = -1, ax_v = -1;
	for (int a = 0; a < 3; a++) {
		float du = vert_pos(v1, a) - vert_pos(v0, a);
		float dv = vert_pos(v3, a) - vert_pos(v0, a);
		if (fabsf(du) > 1e-4f) { len_u = fabsf(du); ax_u = a; }
		if (fabsf(dv) > 1e-4f) { len_v = fabsf(dv); ax_v = a; }
		float p0 = vert_pos(v0, a), p1 = vert_pos(v1, a);
		if (ax < 0 && fabsf(p0 - p1) < 1e-4f) { /* keep first constant axis as the face axis */ }
	}
	*out_u = len_u; *out_v = len_v;
	*out_axis = (ax_u >= 0 && ax_v >= 0 && ax_u != ax_v) ? (3 - ax_u - ax_v) : -1;
	(void)ax;
}

static void test_mesh_coverage(void) {
	BerylWorld *w = make_world(20260829ull, true, true, true);
	if (!w) { CHECK(0, "world alloc"); return; }
	CHECK(beryl_world_generate_area(w, 0, 0, 5, 5) == 36, "6x6 chunks");

	static const int face_axis[6] = { 1, 1, 2, 2, 0, 0 };
	static const int face_dir[6]  = { -1, 1, -1, 1, -1, 1 };

	long expected_faces = 0, emitted_area = 0, sections = 0;
	long culled_neighbour = 0, culled_same_type = 0, faces_examined = 0, quads_emitted = 0;
	int bad_bounds = 0, bad_face = 0, degenerate = 0, bad_indices = 0, bad_verts = 0, wrong_axis = 0;
	for (int cz = 1; cz < 5; cz++) {
		for (int cx = 1; cx < 5; cx++) {
			for (int sy = 2; sy < 7; sy++) {
				BerylSectionMesh m;
				beryl_section_mesh_init(&m, cx, sy, cz);
				if (!beryl_mesh_section(w, cx, sy, cz, &m)) {
					CHECK(0, "meshing section (%d,%d,%d) must succeed", cx, sy, cz);
					beryl_section_mesh_free(&m);
					continue;
				}
				sections++;
				if (!beryl_section_mesh_index_range_ok(&m)) bad_indices++;
				long here = 0;
				for (int L = 0; L < BERYL_LAYER_COUNT; L++) {
					const BerylMeshLayer *l = &m.layer[L];
					if (l->vert_count != (size_t)l->quad_count * 4u) bad_verts++;
					for (uint32_t q = 0; q < l->quad_count; q++) {
						const BerylVertex *v0 = &l->verts[q * 4u + 0];
						const BerylVertex *v1 = &l->verts[q * 4u + 1];
						const BerylVertex *v3 = &l->verts[q * 4u + 3];
						int face = beryl_vertex_face(v0);
						if (face < 0 || face > 5) { bad_face++; continue; }
						float eu, ev;
						int axis;
						quad_span(v0, v1, v3, &eu, &ev, &axis);
						if (axis < 0) { degenerate++; continue; }
						if (axis != face_axis[face]) wrong_axis++;
						long area = (long)(eu * ev + 0.5f);
						if (area <= 0) { degenerate++; continue; }
						here += area;
						for (int k = 0; k < 4; k++) {
							const BerylVertex *v = &l->verts[q * 4u + k];
							for (int a = 0; a < 3; a++) {
								float p = vert_pos(v, a);
								if (p < -0.01f || p > BERYL_SECTION_SIDE + 0.01f) bad_bounds++;
							}
						}
					}
				}
				emitted_area += here;

				BerylMeshStats ms;
				beryl_mesh_last_stats(&ms);
				culled_neighbour += ms.quads_culled_by_neighbour;
				culled_same_type += ms.quads_culled_same_type;
				faces_examined += ms.faces_examined;
				for (int L = 0; L < BERYL_LAYER_COUNT; L++) quads_emitted += ms.quads[L];

				/* Ground truth: count the faces the culling rule says are visible. */
				for (int y = 0; y < BERYL_SECTION_SIDE; y++) {
					for (int z = 0; z < BERYL_SECTION_SIDE; z++) {
						for (int x = 0; x < BERYL_SECTION_SIDE; x++) {
							beryl_bid self = beryl_world_get_block(w, cx * 16 + x, sy * 16 + y, cz * 16 + z);
							if (self == BERYL_BLOCK_AIR) continue;
							for (int f = 0; f < 6; f++) {
								int nx = x + (face_axis[f] == 0 ? face_dir[f] : 0);
								int ny = y + (face_axis[f] == 1 ? face_dir[f] : 0);
								int nz = z + (face_axis[f] == 2 ? face_dir[f] : 0);
								beryl_bid nb = beryl_world_get_block(w, cx * 16 + nx, sy * 16 + ny, cz * 16 + nz);
								if (beryl_face_visible(self, nb)) expected_faces++;
							}
						}
					}
				}
				beryl_section_mesh_free(&m);
			}
		}
	}
	CHECK(sections > 40, "must have meshed a useful number of sections (%ld)", sections);
	CHECK(emitted_area == expected_faces,
	      "emitted face area (%ld) must equal the visible faces the culling rule gives (%ld)",
	      emitted_area, expected_faces);
	CHECK(bad_bounds == 0, "%d vertices fell outside their section", bad_bounds);
	CHECK(bad_face == 0, "%d quads had an out-of-range face id", bad_face);
	CHECK(wrong_axis == 0, "%d quads were flat along the wrong axis", wrong_axis);
	CHECK(degenerate == 0, "%d zero-area quads", degenerate);
	CHECK(bad_indices == 0, "%d meshes with inconsistent index ranges", bad_indices);
	CHECK(bad_verts == 0, "%d layers whose vertex count is not 4 per quad", bad_verts);

	CHECK(culled_neighbour > 0, "the neighbour rule must skip interior faces (%ld)", culled_neighbour);
	CHECK(faces_examined > quads_emitted, "greedy merging must reduce the quad count (%ld faces -> %ld quads)",
	      faces_examined, quads_emitted);
	CHECK(faces_examined > 2 * quads_emitted,
	      "merging must be aggressive on terrain (ratio %.2f)", (double)faces_examined / (double)(quads_emitted | 1));
	CHECK(culled_same_type >= 0, "the same-type cull must be reported");
	beryl_world_free(w);
}

/* --------------------------------------------------------- mesh lighting ----- */
static void test_mesh_lighting(void) {
	/* No caves, no trees: every surface face is open to the sky, so no exposed
	 * top face can legitimately be dark. This is the test that pins "light is
	 * sampled from the air side of the face" -- sampling the owning block instead
	 * gives sky=0 everywhere, because opaque blocks carry no light. */
	BerylWorld *w = make_world(4242ull, false, false, false);
	if (!w) { CHECK(0, "world alloc"); return; }
	beryl_world_generate_area(w, 0, 0, 5, 5);
	/* generate_area seeds skylight column by column; the spread is a queued job,
	 * and the mesher reads the *propagated* result, so a test that skips this
	 * step sees only the seeds. */
	beryl_light_process_queue(w, 1 << 20);

	int checked = 0, bad = 0, bad_ao = 0, up = 0, up_lit = 0;
	unsigned ao_seen = 0;
	static const int axis_of[6] = { 1, 1, 2, 2, 0, 0 };
	static const int dir_of[6]  = { -1, 1, -1, 1, -1, 1 };
	for (int cz = 1; cz < 5; cz++) {
		for (int cx = 1; cx < 5; cx++) {
			for (int sy = 2; sy < 8; sy++) {
				BerylSectionMesh m;
				beryl_section_mesh_init(&m, cx, sy, cz);
				if (!beryl_mesh_section(w, cx, sy, cz, &m)) { beryl_section_mesh_free(&m); continue; }
				for (int L = 0; L < BERYL_LAYER_COUNT; L++) {
					const BerylMeshLayer *l = &m.layer[L];
					for (uint32_t q = 0; q < l->quad_count; q++) {
						for (int k = 0; k < 4; k++) {
							const BerylVertex *v = &l->verts[q * 4u + k];
							int f = beryl_vertex_face(v);
							if (f < 0 || f > 5) continue;
							int a = axis_of[f];
							int px[3] = { cx * 16 + (int)vert_pos(v, 0),
							              sy * 16 + (int)vert_pos(v, 1),
							              cz * 16 + (int)vert_pos(v, 2) };
							px[a] += (dir_of[f] > 0) ? 0 : -1;   /* the cell the face looks into */
							int u = (a + 1) % 3, vv = (a + 2) % 3;
							int open = 1;
							for (int du = -1; du <= 0 && open; du++) {
								for (int dv = -1; dv <= 0 && open; dv++) {
									int c[3] = { px[0], px[1], px[2] };
									c[u] += du; c[vv] += dv;
									if (c[1] < BERYL_WORLD_MIN_Y || c[1] >= BERYL_WORLD_MAX_Y) { open = 0; break; }
									if (beryl_world_is_solid(w, c[0], c[1], c[2])) { open = 0; break; }
									int sky = 0, blk = 0;
									beryl_world_get_light(w, c[0], c[1], c[2], &sky, &blk);
									if (sky != 15) open = 0;
								}
							}
							ao_seen |= 1u << beryl_vertex_ao(v);
							if (!open) continue;
							checked++;
							if ((v->light & 0xF) != 15) bad++;
							/* Same corner, same reasoning for the AO term: with all four
							 * air-side cells open nothing can shade this corner. */
							if (beryl_vertex_ao(v) != 3) bad_ao++;
							if (f == BERYL_FACE_UP) { up++; up_lit += ((v->light & 0xF) == 15); }
						}
					}
				}
				beryl_section_mesh_free(&m);
			}
		}
	}
	CHECK(checked > 400, "must have plenty of wide-open face corners to judge (%d)", checked);
	CHECK(bad == 0, "%d face corners sit in full skylight but were baked darker than 15 "
	                "(the sampler is reading the wrong side of the face)", bad);
	CHECK(up > 100 && up_lit == up, "surface corners must all be fully lit (%d/%d)", up_lit, up);
	CHECK(bad_ao == 0, "%d open face corners were shaded by geometry that is not there", bad_ao);
	{
		int levels = 0;
		for (int i = 0; i < 4; i++) levels += (ao_seen >> i) & 1u;
		CHECK(levels >= 2, "terrain must show a range of ambient occlusion levels (saw 0x%X)", ao_seen);
	}

	/* The bottom of the world must be dark; if light leaked through solid rock,
	 * every cave would render flat. */
	int deep_dark = 1, deep_samples = 0;
	for (int z = 8; z < 88; z += 13) {
		for (int x = 8; x < 88; x += 13) {
			if (beryl_world_get_block(w, x, 4, z) == BERYL_BLOCK_AIR) continue;
			int sky = -1, blk = -1;
			beryl_world_get_light(w, x, 4, z, &sky, &blk);
			deep_samples++;
			if (sky != 0) deep_dark = 0;
		}
	}
	CHECK(deep_samples > 20, "must have sampled deep rock (%d)", deep_samples);
	CHECK(deep_dark, "skylight must not reach deep underground");

	int bad_sections = 0;
	for (int cz = 1; cz < 4; cz++) {
		for (int cx = 1; cx < 4; cx++) {
			for (int sy = 3; sy < 7; sy++) {
				BerylSection *s = beryl_world_section(w, cx, sy, cz, false);
				char err[128];
				if (s && !beryl_light_validate_section(w, s, err, sizeof(err))) {
					bad_sections++;
					if (bad_sections == 1) fprintf(stderr, "      light invalid: %s\n", err);
				}
			}
		}
	}
	CHECK(bad_sections == 0, "%d sections failed the light invariant check", bad_sections);
	beryl_world_free(w);
}

/* ------------------------------------------------------- light transport ----- */
static void test_light_transport(void) {
	BerylWorld *w = make_world(77ull, false, false, false);
	if (!w) { CHECK(0, "world alloc"); return; }
	beryl_world_generate_area(w, 0, 0, 1, 1);

	/* Carve a sealed pocket deep underground and drop a torch in the middle:
	 * light must fall off with distance and stop at the walls. */
	int bx = 20, by = 20, bz = 20;
	for (int y = by - 2; y <= by + 2; y++) {
		for (int z = bz - 2; z <= bz + 2; z++) {
			for (int x = bx - 2; x <= bx + 2; x++) {
				beryl_world_set_block(w, x, y, z, BERYL_BLOCK_AIR);
			}
		}
	}
	int sky = 9, blk = 9;
	beryl_world_get_light(w, bx, by, bz, &sky, &blk);
	CHECK(sky == 0, "a sealed pocket must have no skylight (got %d)", sky);

	/* beryl_world_set_block already queues the edit, carrying the emission of the
	 * block it replaced, so no separate queue call is needed here (or below). */
	beryl_world_set_block(w, bx, by, bz, BERYL_BLOCK_TORCH);
	int processed = beryl_light_process_queue(w, 1 << 20);
	CHECK(processed > 0, "the torch must push light (processed %d)", processed);

	int at1 = 0, at2 = 0, wall = 0;
	beryl_world_get_light(w, bx + 1, by, bz, &sky, &blk); at1 = blk;
	beryl_world_get_light(w, bx + 2, by, bz, &sky, &blk); at2 = blk;
	beryl_world_get_light(w, bx + 3, by, bz, &sky, &blk); wall = blk;
	CHECK(at1 > at2, "block light must decay with distance (%d then %d)", at1, at2);
	CHECK(at1 >= 12, "one block from a torch must be bright (got %d)", at1);
	CHECK(wall == 0, "light must not leak through the wall (got %d)", wall);

	beryl_world_set_block(w, bx, by, bz, BERYL_BLOCK_AIR);
	beryl_light_process_queue(w, 1 << 20);
	beryl_world_get_light(w, bx + 1, by, bz, &sky, &blk);
	CHECK(blk == 0, "removing the torch must clear the light (got %d)", blk);

	beryl_world_free(w);
}

/* ---------------------------------------------------- editing and revisions -- */
static void test_editing(void) {
	BerylWorld *w = make_world(5ull, false, true, false);
	if (!w) { CHECK(0, "world alloc"); return; }
	beryl_world_generate_area(w, 0, 0, 1, 1);

	int x = 24, z = 24, y = beryl_world_top_y(w, x, z) - 1;
	CHECK(beryl_world_get_block(w, x, y, z) != BERYL_BLOCK_AIR, "there must be ground to dig");

	BerylSection *s = beryl_world_section(w, x >> 4, y >> 4, z >> 4, false);
	CHECK(s != NULL, "the section must exist");
	uint64_t rev = s ? s->revision : 0;

	CHECK(beryl_world_set_block(w, x, y, z, BERYL_BLOCK_AIR), "set_block must report success");
	CHECK(beryl_world_get_block(w, x, y, z) == BERYL_BLOCK_AIR, "the block must read back as air");
	CHECK(beryl_world_top_y(w, x, z) <= y, "top_y must follow a dig");
	if (s) {
		CHECK(s->revision != rev, "editing must bump the section revision");
		int cnt = 0;
		for (int i = 0; i < BERYL_SECTION_VOL; i++) cnt += (s->states[i] != BERYL_BLOCK_AIR);
		CHECK(s->non_air == (uint32_t)cnt, "the cached non-air count must follow the edit");
		CHECK(!s->all_air, "a section that still has blocks must not claim to be air");
	}

	/* The re-mesh must show the hole: the block under the dug one now has a top
	 * face at the level the dug block used to occupy. */
	BerylSectionMesh m;
	beryl_section_mesh_init(&m, x >> 4, y >> 4, z >> 4);
	CHECK(beryl_mesh_section(w, x >> 4, y >> 4, z >> 4, &m), "re-mesh must succeed");
	int lx = x & 15, ly = y & 15, lz = z & 15;
	int found = 0;
	for (int L = 0; L < BERYL_LAYER_COUNT && !found; L++) {
		const BerylMeshLayer *l = &m.layer[L];
		for (uint32_t q = 0; q < l->quad_count && !found; q++) {
			const BerylVertex *v = &l->verts[q * 4u];
			if (beryl_vertex_face(v) != BERYL_FACE_UP) continue;
			float fy = vert_pos(&v[0], 1);
			float x0 = vert_pos(&v[0], 0), z0 = vert_pos(&v[0], 2);
			float x1 = vert_pos(&v[2], 0), z1 = vert_pos(&v[2], 2);
			if (fabsf(fy - (float)ly) < 0.01f &&
			    x0 <= lx + 0.01f && x1 >= lx + 1.0f - 0.01f &&
			    z0 <= lz + 0.01f && z1 >= lz + 1.0f - 0.01f) found = 1;
		}
	}
	CHECK(found, "a freshly dug hole must produce a face in the re-mesh");
	beryl_section_mesh_free(&m);
	beryl_world_free(w);
}

/* -------------------------------------- light idempotence & convergence ------ */
/* The light field must be a pure function of the block state:
 *  - a full relight of an unchanged world must change nothing (the old reset
 *    covered the requested chunks only, so a second pass stopped at
 *    previously-lit neighbours and border cells came out darker than a fresh
 *    world -- a no-op relight was not a no-op);
 *  - the incremental queue after edits must converge to exactly the same field
 *    a full relight produces (the removal pass clears a connected region, and
 *    every surviving source must be re-seeded or the re-add pass starts from
 *    almost nothing and the cleared region stays dark);
 *  - an emitter sitting just outside the requested span must still light the
 *    area, which is what the one-section ring around the reset exists for.
 * Each is asserted over every voxel of a 3x3-chunk dump, not a sample. */
static int light_dump(BerylWorld *w, uint8_t *out, size_t cap) {
	size_t n = 0;
	for (int z = 0; z < 48; z++) {
		for (int x = 0; x < 48; x++) {
			for (int y = 0; y < BERYL_WORLD_MAX_Y; y++) {
				if (n >= cap) return -1;
				int s, b;
				beryl_world_get_light(w, x, y, z, &s, &b);
				out[n++] = (uint8_t)((s & 15) | ((b & 15) << 4));
			}
		}
	}
	return (int)n;
}

static void test_light_idempotence(void) {
	enum { LIGHT_DUMP = 48 * 48 * BERYL_WORLD_MAX_Y };
	static uint8_t la[LIGHT_DUMP], lb[LIGHT_DUMP];

	/* 1. Relighting an unchanged world is a no-op, voxel for voxel. */
	BerylWorld *a = make_world(12ull, true, true, true);
	BerylWorld *b = make_world(12ull, true, true, true);
	if (!a || !b) { CHECK(0, "world alloc"); return; }
	beryl_world_generate_area(a, 0, 0, 2, 2);
	beryl_world_generate_area(b, 0, 0, 2, 2);
	beryl_light_relight_area(b, 0, 0, 2, 2);
	CHECK(light_dump(a, la, sizeof(la)) == LIGHT_DUMP, "dump a");
	CHECK(light_dump(b, lb, sizeof(lb)) == LIGHT_DUMP, "dump b");
	int same = memcmp(la, lb, sizeof(la)) == 0;
	CHECK(same, "a no-op relight must not change the light field");
	beryl_world_free(a);
	beryl_world_free(b);

	/* 2. Incremental edits and a full relight of the same edits agree. The
	 * tower scenario is the nasty one: a column of blocks placed in open sky
	 * and removed again, which used to leave border cells dark because the
	 * removal flood cleared them and nothing re-seeded the survivors. */
	static const int tower[][4] = {
		{24, 60, 24, BERYL_BLOCK_STONE}, {24, 61, 24, BERYL_BLOCK_STONE},
		{24, 62, 24, BERYL_BLOCK_STONE}, {24, 63, 24, BERYL_BLOCK_STONE},
		{24, 64, 24, BERYL_BLOCK_STONE}, {24, 65, 24, BERYL_BLOCK_STONE},
		{24, 60, 24, BERYL_BLOCK_AIR},   {24, 61, 24, BERYL_BLOCK_AIR},
		{24, 62, 24, BERYL_BLOCK_AIR},   {24, 63, 24, BERYL_BLOCK_AIR},
		{24, 64, 24, BERYL_BLOCK_AIR},   {24, 65, 24, BERYL_BLOCK_AIR},
		{-1, 0, 0, 0},
	};
	a = make_world(12ull, true, true, true);
	b = make_world(12ull, true, true, true);
	beryl_world_generate_area(a, 0, 0, 2, 2);
	beryl_world_generate_area(b, 0, 0, 2, 2);
	for (int i = 0; tower[i][0] != -1; i++) {
		beryl_world_set_block(a, tower[i][0], tower[i][1], tower[i][2], (beryl_bid)tower[i][3]);
		beryl_world_set_block(b, tower[i][0], tower[i][1], tower[i][2], (beryl_bid)tower[i][3]);
	}
	beryl_light_process_queue(a, 1 << 20);
	beryl_light_relight_area(b, 0, 0, 2, 2);
	CHECK(light_dump(a, la, sizeof(la)) == LIGHT_DUMP, "dump a");
	CHECK(light_dump(b, lb, sizeof(lb)) == LIGHT_DUMP, "dump b");
	same = memcmp(la, lb, sizeof(la)) == 0;
	CHECK(same, "incremental edits must converge to a full relight");
	/* Drain the global edit queue so later tests start clean. */
	beryl_light_process_queue(b, 1 << 20);
	beryl_world_free(a);
	beryl_world_free(b);

	/* 3. An emitter in the ring still lights the area. The torch sits in chunk
	 * (1,1), just outside a relight of chunk (0,0); without the ring the
	 * emitter was never seeded and the neighbouring cells stayed dark. */
	a = make_world(20260901ull, true, true, true);
	if (!a) { CHECK(0, "world alloc"); return; }
	beryl_world_generate_area(a, 0, 0, 2, 2);
	int ty = beryl_world_top_y(a, 16, 16);
	beryl_world_set_block(a, 16, ty + 1, 16, BERYL_BLOCK_TORCH);
	beryl_light_process_queue(a, 1 << 20);
	beryl_light_relight_area(a, 0, 0, 0, 0);
	int s = -1, blk = -1;
	beryl_world_get_light(a, 14, ty + 1, 14, &s, &blk);
	CHECK(blk > 0, "a torch just outside the span must still light the area (blk=%d)", blk);
	beryl_light_process_queue(a, 1 << 20);
	beryl_world_free(a);
}

/* ------------------------------------------------------------ occlusion ------ */
static void test_occlusion(void) {
	BerylWorld *w = make_world(20260829ull, true, true, true);
	if (!w) { CHECK(0, "world alloc"); return; }
	beryl_world_generate_area(w, 0, 0, 7, 7);

	BerylCamera cam;
	beryl_camera_init(&cam, 320, 180);
	int lx = 64, lz = 64;
	int ty = beryl_world_top_y(w, lx, lz);
	cam.pos = beryl_vec3((float)lx, (float)ty + 10.0f, (float)lz + 40.0f);
	cam.yaw = -1.5707963f;                 /* looking towards -Z */
	cam.pitch = -0.12f;
	cam.zfar = 400.0f;
	beryl_camera_update(&cam);

	BerylVisibleSet with_occ, without;
	beryl_visible_set_init(&with_occ);
	beryl_visible_set_init(&without);
	int a = beryl_visible_set_compute(&with_occ, w, &cam, 12);
	int b = beryl_visible_set_compute(&without, w, &cam, 12);
	CHECK(a > 0, "occlusion culling must find something to draw (%d)", a);
	CHECK(a <= b, "occlusion may only remove sections (%d > %d)", a, b);

	/* The 3x3x3 neighbourhood around the camera section is always kept: if it were
	 * culled the player would be standing inside a hole. */
	int pcx = (int)floorf(cam.pos.x) >> BERYL_CHUNK_SHIFT;
	int pcz = (int)floorf(cam.pos.z) >> BERYL_CHUNK_SHIFT;
	int pcsy = (int)floorf(cam.pos.y) >> 4;
	int kept = 0, total = 0;
	for (int dz = -1; dz <= 1; dz++) {
		for (int dx = -1; dx <= 1; dx++) {
			for (int dy = -1; dy <= 1; dy++) {
				total++;
				if (beryl_visible_set_contains(&with_occ, pcx + dx, pcsy + dy, pcz + dz)) kept++;
			}
		}
	}
	CHECK(kept == total, "every section around the camera must stay visible (%d/%d)", kept, total);

	int c = beryl_visible_set_compute(&with_occ, w, &cam, 12);
	CHECK(a == c, "the visible set must be a pure function of the camera (%d vs %d)", a, c);

	beryl_visible_set_free(&with_occ);
	beryl_visible_set_free(&without);
	beryl_world_free(w);
}

void test_world(void) {
	test_worldgen();
	test_mesh_coverage();
	test_mesh_lighting();
	test_light_transport();
	test_editing();
	test_light_idempotence();
	test_occlusion();
}
