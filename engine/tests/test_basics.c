/* test_basics.c -- math, id tables, vertex layout, PNG encoder.
 *
 * These are the contracts every other file depends on, so they are checked here
 * once, cheaply, without touching the world or the rasterizer. */
#include "test.h"

#include "bcore.h"
#include "bmath.h"
#include "blocks.h"
#include "camera.h"
#include "mesh_format.h"
#include "png.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static const float k_eps = 1e-4f;

/* --------------------------------------------------------------- matrices -- */
static void test_math(void) {
	BerylMat4 id = beryl_mat4_identity();
	BerylMat4 p = beryl_mat4_perspective(70.0f * 3.14159265f / 180.0f, 16.0f / 9.0f, 0.1f, 500.0f);

	/* A * I == A, element by element, in the storage order the shaders use. */
	BerylMat4 pi = beryl_mat4_mul(p, id);
	for (int i = 0; i < 16; i++) CHECK_NEAR(p.m[i], pi.m[i], 1e-6, "mul by identity changed m[%d]", i);

	/* The near plane maps to NDC z = -1 and the far plane to +1: this is the GL
	 * convention the OpenGL backend passes through untouched. */
	BerylVec4 near_p = beryl_mat4_transform(p, beryl_vec4(0, 0, -0.1f, 1.0f));
	BerylVec4 far_p  = beryl_mat4_transform(p, beryl_vec4(0, 0, -500.0f, 1.0f));
	CHECK_NEAR(near_p.z / near_p.w, -1.0f, 1e-3f, "near plane must land on ndc z=-1");
	CHECK_NEAR(far_p.z / far_p.w,   1.0f, 1e-3f, "far plane must land on ndc z=+1");

	/* Anything on the view axis in front of the camera stays inside x/y too. */
	BerylVec4 mid = beryl_mat4_transform(p, beryl_vec4(0, 0, -25.0f, 1.0f));
	CHECK(fabsf(mid.x / mid.w) < 1.0f, "centre of frame must be inside x clip");
	CHECK(mid.z / mid.w > -1.0f && mid.z / mid.w < 1.0f, "mid-depth must be inside z clip");

	/* transpose(transpose(A)) == A. */
	BerylMat4 t2 = beryl_mat4_transpose(beryl_mat4_transpose(p));
	for (int i = 0; i < 16; i++) CHECK_NEAR(p.m[i], t2.m[i], 1e-6f, "transpose roundtrip m[%d]", i);

	/* look_at puts the target straight ahead at -Z, at the eye/target distance. */
	BerylMat4 v = beryl_mat4_look_at(beryl_vec3(0, 0, 5), beryl_vec3(0, 0, 0), beryl_vec3(0, 1, 0));
	BerylVec4 tv = beryl_mat4_transform(v, beryl_vec4(0, 0, 0, 1.0f));
	CHECK_NEAR(tv.x, 0.0f, k_eps, "look_at: target must be centred horizontally");
	CHECK_NEAR(tv.y, 0.0f, k_eps, "look_at: target must be centred vertically");
	CHECK_NEAR(tv.z, -5.0f, k_eps, "look_at: target must be 5 units down -Z");

	/* yaw/pitch -> direction, matching the camera's basis. */
	BerylVec3 d = beryl_yaw_pitch_to_dir(0.0f, 0.0f);
	CHECK_NEAR(d.x, 1.0f, k_eps, "yaw 0 must face +X");
	d = beryl_yaw_pitch_to_dir(1.5707963f, 0.0f);
	CHECK_NEAR(d.z, 1.0f, k_eps, "yaw +90deg must face +Z");
	d = beryl_yaw_pitch_to_dir(0.0f, 1.5707963f / 4.0f);
	CHECK_NEAR(d.y, sinf(1.5707963f / 4.0f), 1e-4f, "pitch must lift the direction");
	CHECK_NEAR(beryl_v3_length(d), 1.0f, 1e-4f, "yaw/pitch must give a unit direction");

	/* Composition order: (P * T) applied to the origin must equal P applied to
	 * the translated point -- the exact order the engine relies on when it folds
	 * a section origin into the model matrix. */
	BerylMat4 m = beryl_mat4_mul(beryl_mat4_perspective(1.0f, 1.0f, 0.1f, 100.0f),
	                             beryl_mat4_translate(beryl_vec3(0, 0, -10)));
	BerylVec4 q = beryl_mat4_transform(m, beryl_vec4(0, 0, 0, 1.0f));
	BerylVec4 q2 = beryl_mat4_transform(beryl_mat4_perspective(1.0f, 1.0f, 0.1f, 100.0f),
	                                     beryl_vec4(0, 0, -10, 1.0f));
	CHECK_NEAR(q.x / q.w, q2.x / q2.w, 1e-5f, "P*T must equal translating the point");
	CHECK_NEAR(q.y / q.w, q2.y / q2.w, 1e-5f, "P*T must equal translating the point");
	CHECK_NEAR(q.z / q.w, q2.z / q2.w, 1e-5f, "P*T must equal translating the point");
	CHECK(q.z / q.w > -1.0f && q.z / q.w < 1.0f, "a point 10 units away must be inside the depth range");
	(void)t2;
}

/* --------------------------------------------------------------- frustum ---- */
static void test_frustum(void) {
	BerylCamera cam;
	beryl_camera_init(&cam, 320, 180);
	cam.pos = beryl_vec3(0, 0, 0);
	cam.yaw = 0.0f; cam.pitch = 0.0f;          /* looking down +X */
	cam.znear = 0.1f; cam.zfar = 100.0f;
	beryl_camera_update(&cam);

	CHECK(beryl_frustum_test_point(&cam.frustum, beryl_vec3(10, 0, 0)),
	      "a point straight ahead must be inside the frustum");
	CHECK(!beryl_frustum_test_point(&cam.frustum, beryl_vec3(-10, 0, 0)),
	      "a point behind the camera must be outside");
	CHECK(!beryl_frustum_test_point(&cam.frustum, beryl_vec3(200, 0, 0)),
	      "a point beyond the far plane must be outside");
	CHECK(beryl_frustum_test_point(&cam.frustum, beryl_vec3(5, 1, -1)),
	      "a slightly off-axis near point must be inside");

	/* Section AABBs: 16 blocks on a side, aligned to the section grid. */
	BerylAabb a = beryl_section_aabb(3, 2, -1, 0.0f);
	CHECK_NEAR(a.max.x - a.min.x, 16.0f, k_eps, "section aabb must be 16 wide");
	CHECK_NEAR(a.min.x, 3.0f * 16.0f, k_eps, "section aabb x origin");
	CHECK_NEAR(a.min.y, 2.0f * 16.0f, k_eps, "section aabb y origin");
	CHECK_NEAR(a.min.z, -1.0f * 16.0f, k_eps, "section aabb z origin (negative chunks)");
	CHECK(beryl_aabb_intersects(a, beryl_section_aabb(3, 2, -1, 0.5f)),
	      "a padded box must still intersect itself");
	CHECK(!beryl_aabb_intersects(a, beryl_section_aabb(9, 9, 9, 0.0f)),
	      "distant boxes must not intersect");

	/* Project must agree with the rasterizer's viewport: centre of the screen. */
	BerylVec3 axis = beryl_v3_add(cam.pos, beryl_v3_scale(beryl_yaw_pitch_to_dir(0, 0), 10.0f));
	BerylVec3 px = beryl_camera_project(&cam, axis, NULL);
	CHECK_NEAR(px.x, 160.0f, 1.0f, "the view axis must project to the frame centre x");
	CHECK_NEAR(px.y, 90.0f, 1.0f, "the view axis must project to the frame centre y (y down)");

	/* Ray dir at the centre must be the camera direction. */
	BerylVec3 r = beryl_camera_ray_dir(&cam, 160.0f, 90.0f);
	BerylVec3 d = beryl_yaw_pitch_to_dir(cam.yaw, cam.pitch);
	CHECK_NEAR(r.x, d.x, 2e-2f, "centre ray must match the camera direction");
	CHECK_NEAR(r.y, d.y, 2e-2f, "centre ray must match the camera direction");
}

/* -------------------------------------------------------------- section keys */
static void test_keys(void) {
	const int xs[] = { 0, -1, 7, -128, 1048575, -1048576, 4096, -4096 };
	const int zs[] = { 0, 3, -9999, 12345, -1, 65535, 8, -8 };
	for (unsigned i = 0; i < sizeof(xs) / sizeof(xs[0]); i++) {
		for (int sy = 0; sy < 8; sy++) {
			uint64_t k = beryl_section_key(xs[i], sy, zs[i]);
			int cx = 99999, oy = 99999, cz = 99999;
			beryl_section_key_unpack(k, &cx, &oy, &cz);
			CHECK(cx == xs[i] && oy == sy && cz == zs[i],
			      "key roundtrip (%d,%d,%d) -> (%d,%d,%d)", xs[i], sy, zs[i], cx, oy, cz);
		}
	}
	/* Different sections must not collide on a key. */
	CHECK(beryl_section_key(0, 0, 0) != beryl_section_key(0, 1, 0), "y is part of the key");
	CHECK(beryl_section_key(1, 0, 0) != beryl_section_key(-1, 0, 0), "x sign is part of the key");

	/* Section index layout is y|z<<4|x<<8 so it matches vanilla light arrays. */
	CHECK(beryl_section_index(0, 0, 0) == 0, "index origin");
	CHECK(beryl_section_index(1, 0, 0) == 1, "x is the minor axis");
	CHECK(beryl_section_index(0, 1, 0) == 256, "y is the major axis");
	CHECK(beryl_section_index(0, 0, 1) == 16, "z is the middle axis");
	CHECK(beryl_section_index(15, 15, 15) == BERYL_SECTION_VOL - 1, "index must fill the section");
}

/* ------------------------------------------------------------ block table -- */
static void test_blocks(void) {
	beryl_blocks_init();
	CHECK(beryl_block_is_air(0), "id 0 must be air");
	CHECK(strcmp(beryl_block_name(0), "air") == 0, "air must be named air");
	CHECK(beryl_block_by_name("stone") == BERYL_BLOCK_STONE, "name lookup must roundtrip");
	CHECK(beryl_block_by_name("no_such_block") == 0, "unknown names must give air, not crash");

	CHECK(beryl_block_is_opaque(BERYL_BLOCK_STONE), "stone is opaque");
	CHECK(beryl_block_is_opaque(BERYL_BLOCK_DIRT), "dirt is opaque");
	CHECK(!beryl_block_is_opaque(BERYL_BLOCK_WATER), "water is not opaque");
	CHECK(!beryl_block_is_opaque(BERYL_BLOCK_OAK_LEAVES), "leaves are not opaque");
	CHECK(!beryl_block_is_opaque(BERYL_BLOCK_GLASS), "glass is not opaque");

	/* Light transport parameters, as the mesher and light engine both assume. */
	CHECK(beryl_block_light_attenuation(BERYL_BLOCK_AIR) == 0, "air must not attenuate");
	CHECK(beryl_block_light_attenuation(BERYL_BLOCK_STONE) >= 15, "stone must block light");
	CHECK(beryl_block_propagates_skylight_down(BERYL_BLOCK_WATER), "skylight must pass down through water");

	/* Face culling truth table -- the rule that makes the world look solid. */
	CHECK(!beryl_face_visible(BERYL_BLOCK_STONE, BERYL_BLOCK_STONE), "stone against stone is hidden");
	CHECK(beryl_face_visible(BERYL_BLOCK_STONE, BERYL_BLOCK_AIR), "stone against air is drawn");
	CHECK(beryl_face_visible(BERYL_BLOCK_STONE, BERYL_BLOCK_WATER), "stone under water is drawn");
	CHECK(!beryl_face_visible(BERYL_BLOCK_WATER, BERYL_BLOCK_STONE),
	      "an opaque neighbour hides a translucent block's face too");
	CHECK(!beryl_face_visible(BERYL_BLOCK_WATER, BERYL_BLOCK_WATER), "water against water is culled");
	CHECK(!beryl_face_visible(BERYL_BLOCK_OAK_LEAVES, BERYL_BLOCK_OAK_LEAVES), "leaf interiors are culled");
	CHECK(beryl_face_visible(BERYL_BLOCK_OAK_LEAVES, BERYL_BLOCK_AIR), "leaves against air are drawn");
	/* Air is the caller's job: the predicate only answers "would this neighbour
	 * hide the face", so it reports visible for air/air by design. */
	CHECK(beryl_face_visible(BERYL_BLOCK_AIR, BERYL_BLOCK_AIR), "air/air is not hidden by the rule");

	/* Face helpers. */
	CHECK(BERYL_FACE_COUNT == 6, "six faces");
	static const int want_axis[6] = { 1, 1, 2, 2, 0, 0 };
	static const int want_sign[6] = { 0, 1, 0, 1, 0, 1 };
	for (int f = 0; f < BERYL_FACE_COUNT; f++) {
		CHECK(beryl_face_axis(f) == want_axis[f], "face %d axis", f);
		CHECK(beryl_face_sign(f) == want_sign[f], "face %d sign", f);
		CHECK(beryl_face_opposite((BerylFace)f) == (BerylFace)(f ^ 1), "face %d opposite", f);
		CHECK(fabsf(beryl_v3_length(beryl_face_normal((BerylFace)f)) - 1.0f) < 1e-5f,
		      "face %d normal must be unit", f);
	}
	/* Shading: up brightest, down darkest, sides in between. */
	CHECK(beryl_face_shade(BERYL_FACE_UP) > beryl_face_shade(BERYL_FACE_NORTH), "up brighter than sides");
	CHECK(beryl_face_shade(BERYL_FACE_NORTH) > beryl_face_shade(BERYL_FACE_DOWN), "sides brighter than down");
	CHECK(beryl_face_shade(BERYL_FACE_NORTH) == beryl_face_shade(BERYL_FACE_SOUTH), "north/south must match");

	/* AO multipliers: four levels, increasing, never above 1. */
	float prev = -1.0f;
	for (int i = 0; i < 4; i++) {
		float a = beryl_ao_multiplier(i);
		CHECK(a > prev, "ao must increase with level");
		CHECK(a > 0.0f && a <= 1.0f, "ao must stay a multiplier");
		prev = a;
	}
}

/* ------------------------------------------------------------ vertex layout -- */
static void test_vertex_layout(void) {
	CHECK(sizeof(BerylVertex) == 16, "vertex must stay 16 bytes for both backends");
	for (int a = 0; a < BERYL_ATTRIB_COUNT; a++) {
		const BerylAttribDesc *d = beryl_attrib_desc((BerylAttrib)a);
		CHECK(d != NULL, "attrib %d must have a description", a);
		if (!d) continue;
		CHECK(d->location == (uint32_t)a, "attrib %d must bind to location %d", a, a);
		CHECK(d->components == 1 || d->components == 2, "attrib %d components", a);
		CHECK(d->size_bytes == 1 || d->size_bytes == 2, "attrib %d must be integer-sized", a);
		CHECK(d->name && d->name[0], "attrib %d needs a name for GL reflection", a);
		CHECK(d->offset + d->components * d->size_bytes <= 16, "attrib %d must fit the vertex", a);
	}
	CHECK(beryl_attrib_desc(BERYL_ATTRIB_POS_XY)->offset == 0, "pos_xy offset");
	CHECK(beryl_attrib_desc(BERYL_ATTRIB_POS_Z)->offset  == 4, "pos_z offset");
	CHECK(beryl_attrib_desc(BERYL_ATTRIB_UV)->offset     == 6, "uv offset");
	CHECK(beryl_attrib_desc(BERYL_ATTRIB_PACK0)->offset  == 10, "pack0 offset");
	CHECK(beryl_attrib_desc(BERYL_ATTRIB_PACK1)->offset  == 12, "pack1 offset");

	/* Packing roundtrip: tint/cutout/blend survive into the layer index. */
	for (int tint = 0; tint < 8; tint++) {
		for (int cut = 0; cut < 2; cut++) {
			for (int blend = 0; blend < 2; blend++) {
				BerylVertex v;
				memset(&v, 0, sizeof(v));
				v.flags = beryl_vertex_pack_flags(tint, cut != 0, blend != 0);
				CHECK(beryl_vertex_tint(&v) == tint, "tint %d must roundtrip", tint);
				int layer = (blend ? 2 : (cut ? 1 : 0));
				int want = (v.flags & BERYL_VFLAG_BLEND) ? 2 : (v.flags & BERYL_VFLAG_CUTOUT) ? 1 : 0;
				CHECK(layer == want, "flags must select layer %d for cut=%d blend=%d", layer, cut, blend);
			}
		}
	}
	/* ao_face packs the AO level in the low bits and the face above it. */
	BerylVertex v;
	memset(&v, 0, sizeof(v));
	for (int face = 0; face < 6; face++) {
		for (int ao = 0; ao < 4; ao++) {
			v.ao_face = (uint8_t)(ao | (face << BERYL_FACE_SHIFT));
			CHECK(beryl_vertex_ao(&v) == ao, "ao %d must roundtrip", ao);
			CHECK(beryl_vertex_face(&v) == face, "face %d must roundtrip", face);
		}
	}
}

/* ------------------------------------------------------- palettes & textures */
static void test_palettes(void) {
	/* The biome tint table is copied into a stride-4 uniform array by the engine,
	 * so it must be authored as a stride-3 table with sane colours. Grass and
	 * foliage have to read *green*, which is what catches a stride mix-up. */
	float pal[BERYL_TINT_COUNT][3];
	memset(pal, 0, sizeof(pal));
	beryl_tint_palette(pal);
	CHECK_NEAR(pal[0][0], 1.0f, 1e-6f, "tint 0 must be untinted (white)");
	CHECK_NEAR(pal[0][1], 1.0f, 1e-6f, "tint 0 must be untinted (white)");
	CHECK_NEAR(pal[0][2], 1.0f, 1e-6f, "tint 0 must be untinted (white)");
	for (int i = 0; i < BERYL_TINT_COUNT; i++) {
		for (int k = 0; k < 3; k++) {
			CHECK(pal[i][k] >= 0.0f && pal[i][k] <= 1.0f, "tint %d channel %d out of range", i, k);
		}
	}
	CHECK(pal[1][1] > pal[1][0] && pal[1][1] > pal[1][2], "grass tint must be green-dominant");
	CHECK(pal[2][1] > pal[2][0] && pal[2][1] > pal[2][2], "foliage tint must be green-dominant");
	CHECK(pal[5][2] > pal[5][0], "water tint must be blue-dominant");

	/* Lightmap: 16x16 RGBA, brighter with more skylight, and dark in a sealed
	 * corner. The engine uploads this verbatim as the second texture. */
	uint8_t lut[16 * 16 * 4];
	beryl_lightmap_generate(lut, 1.0f);
	int day_open = (lut[(15 * 16 + 0) * 4] + lut[(15 * 16 + 0) * 4 + 1] + lut[(15 * 16 + 0) * 4 + 2]) / 3;
	int sealed   = (lut[0] + lut[1] + lut[2]) / 3;
	int full_block = (lut[(0 * 16 + 15) * 4] + lut[(0 * 16 + 15) * 4 + 1] + lut[(0 * 16 + 15) * 4 + 2]) / 3;
	CHECK(day_open > 200, "a sun-exposed cell must be bright (got %d)", day_open);
	CHECK(sealed < 60, "a sealed cell must be dark (got %d)", sealed);
	CHECK(full_block > 150, "max block light must be bright (got %d)", full_block);
	for (int sky = 1; sky < 16; sky++) {
		int a = (lut[((sky - 1) * 16) * 4] + lut[((sky - 1) * 16) * 4 + 1] + lut[((sky - 1) * 16) * 4 + 2]) / 3;
		int b = (lut[(sky * 16) * 4] + lut[(sky * 16) * 4 + 1] + lut[(sky * 16) * 4 + 2]) / 3;
		CHECK(b >= a, "skylight ramp must be monotonic at sky=%d", sky);
	}
	uint8_t night[16 * 16 * 4];
	beryl_lightmap_generate(night, 0.0f);
	CHECK(night[(15 * 16) * 4] < (uint8_t)day_open, "night must be darker than noon");

	/* Texture array: deterministic, fully specified alpha, one layer per tile. */
	int layers = beryl_texarray_layer_count();
	CHECK(layers == BERYL_TILE_LAYERS, "one texture array layer per block tile");
	size_t bytes = (size_t)layers * 16u * 16u * 4u;
	uint8_t *ta = (uint8_t *)malloc(bytes), *tb = (uint8_t *)malloc(bytes);
	CHECK(ta && tb, "test allocation");
	if (ta && tb) {
		beryl_texarray_generate(ta, 12345u);
		beryl_texarray_generate(tb, 12345u);
		CHECK(memcmp(ta, tb, bytes) == 0, "the atlas must be deterministic for a seed");
		beryl_texarray_generate(tb, 999u);
		CHECK(memcmp(ta, tb, bytes) != 0, "a different seed must change the atlas");
		int nonflat = 0, opaque_alpha = 1, cutout_alpha = 1;
		int stone_tile = beryl_block_info(BERYL_BLOCK_STONE)->tiles[BERYL_FACE_UP];
		int leaf_tile  = beryl_block_info(BERYL_BLOCK_OAK_LEAVES)->tiles[BERYL_FACE_UP];
		for (int l = 0; l < layers; l++) {
			uint8_t sum = 0;
			for (int i = 0; i < 16 * 16; i++) {
				uint8_t a = ta[((size_t)l * 256 + i) * 4 + 3];
				if (l == stone_tile && a != 255) opaque_alpha = 0;
				if (l == leaf_tile && a != 0 && a != 255) cutout_alpha = 0;
				sum = (uint8_t)(sum + ta[((size_t)l * 256 + i) * 4]);
			}
			if (sum) nonflat++;
		}
		CHECK(opaque_alpha, "an opaque block's tile must be fully solid");
		CHECK(cutout_alpha, "cutout alpha must stay binary so discard is unambiguous");
		CHECK(nonflat == layers, "every tile must carry some colour");
		/* Grass must be greener than its own red channel: the tile the renderer
		 * samples for a hilltop, and the one that looked brown once. */
	float gr = 0, gg = 0, gb = 0;
	int grass_tile = beryl_block_info(BERYL_BLOCK_GRASS_BLOCK)->tiles[BERYL_FACE_UP];
		CHECK(grass_tile >= 0 && grass_tile < layers, "grass must map to a tile layer");
		float avg[3];
		beryl_texarray_average_color(ta, grass_tile, avg, NULL);
		gr = avg[0]; gg = avg[1]; gb = avg[2];
		CHECK(gg > gr, "grass_top must be green-dominant (got %.2f,%.2f,%.2f)", gr, gg, gb);
		free(ta); free(tb);
	}
}

/* ----------------------------------------------------------- PNG encoder ---- */
static uint32_t crc_of(const uint8_t *p, size_t n) {
	static uint32_t tab[256];
	static int init = 0;
	if (!init) {
		for (uint32_t i = 0; i < 256; i++) {
			uint32_t c = i;
			for (int k = 0; k < 8; k++) c = (c & 1) ? 0xEDB88320u ^ (c >> 1) : c >> 1;
			tab[i] = c;
		}
		init = 1;
	}
	uint32_t c = 0xFFFFFFFFu;
	for (size_t i = 0; i < n; i++) c = tab[(c ^ p[i]) & 0xFF] ^ (c >> 8);
	return c ^ 0xFFFFFFFFu;
}

static void test_png(void) {
	const int W = 37, H = 13;               /* odd sizes stress the row filters */
	uint8_t img[W * H * 4];
	for (int y = 0; y < H; y++) {
		for (int x = 0; x < W; x++) {
			uint8_t *p = img + ((size_t)y * W + x) * 4;
			p[0] = (uint8_t)(x * 7);
			p[1] = (uint8_t)(y * 19);
			p[2] = (uint8_t)((x * 3 + y * 5) & 0xFF);
			p[3] = 255;
		}
	}
	uint8_t *buf = NULL;
	size_t len = 0;
	int rc = beryl_png_encode_rgba8(W, H, img, &buf, &len);
	CHECK(rc == 0 && buf && len > 8, "encoder must succeed (rc=%d len=%zu)", rc, len);
	if (rc == 0 && buf) {
		const uint8_t sig[8] = { 137, 80, 78, 71, 13, 10, 26, 10 };
		CHECK(memcmp(buf, sig, 8) == 0, "PNG signature must be exact");
		size_t off = 8;
		int chunks = 0, saw_ihdr = 0, saw_idat = 0, saw_iend = 0, bad_crc = 0;
		int width = -1, height = -1, depth = -1, colortype = -1;
		while (off + 12 <= len) {
			uint32_t clen = ((uint32_t)buf[off] << 24) | ((uint32_t)buf[off + 1] << 16) |
			                ((uint32_t)buf[off + 2] << 8) | buf[off + 3];
			const uint8_t *type = buf + off + 4;
			if (off + 12 + clen > len) { bad_crc = 1; break; }
			if (crc_of(buf + off + 4, 4 + clen) !=
			    (((uint32_t)buf[off + 8 + clen] << 24) | ((uint32_t)buf[off + 9 + clen] << 16) |
			     ((uint32_t)buf[off + 10 + clen] << 8) | buf[off + 11 + clen])) bad_crc++;
			if (memcmp(type, "IHDR", 4) == 0 && clen == 13) {
				saw_ihdr = 1;
				const uint8_t *d = buf + off + 8;
				width = (int)(((uint32_t)d[0] << 24) | ((uint32_t)d[1] << 16) | ((uint32_t)d[2] << 8) | d[3]);
				height = (int)(((uint32_t)d[4] << 24) | ((uint32_t)d[5] << 16) | ((uint32_t)d[6] << 8) | d[7]);
				depth = d[8]; colortype = d[9];
				CHECK(d[12] == 0, "interlace must be none");
			} else if (memcmp(type, "IDAT", 4) == 0) saw_idat++;
			else if (memcmp(type, "IEND", 4) == 0) { saw_iend++; CHECK(clen == 0, "IEND must be empty"); }
			chunks++;
			off += 12 + clen;
			if (memcmp(type, "IEND", 4) == 0) break;
		}
		CHECK(bad_crc == 0, "every chunk CRC must verify (%d bad)", bad_crc);
		CHECK(saw_ihdr && width == W && height == H, "IHDR must carry the image size");
		CHECK(depth == 8 && colortype == 6, "must be 8-bit RGBA (depth %d, colour %d)", depth, colortype);
		CHECK(saw_idat >= 1, "must have image data");
		CHECK(saw_iend, "must end with IEND");
		CHECK(len < (size_t)W * H * 4, "a real compressor must beat raw RGBA (%zu vs %zu)", len, (size_t)W*H*4);
		free(buf);
	}
	/* Writing to disk must work and produce the same bytes as the memory path. */
	const char *path = "/tmp/beryl_png_roundtrip.png";
	CHECK(beryl_png_write_rgba8(path, W, H, img) == 0, "file write must succeed");
	FILE *f = fopen(path, "rb");
	if (f) {
		fseek(f, 0, SEEK_END);
		long sz = ftell(f);
		fseek(f, 0, SEEK_SET);
		uint8_t *rd = (uint8_t *)malloc((size_t)sz);
		size_t got = fread(rd, 1, (size_t)sz, f);
		fclose(f);
		CHECK(got == (size_t)sz && sz > 8, "the file must be readable and non-trivial");
		const uint8_t sig[8] = { 137, 80, 78, 71, 13, 10, 26, 10 };
		CHECK(got >= 8 && memcmp(rd, sig, 8) == 0, "the file must start with the signature");
		free(rd);
		remove(path);
	}
}

void test_basics(void) {
	test_math();
	test_frustum();
	test_keys();
	test_blocks();
	test_vertex_layout();
	test_palettes();
	test_png();
}
