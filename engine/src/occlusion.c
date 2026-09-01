/* occlusion.c -- section-graph visibility walk. See occlusion.h for the rules. */
#include "occlusion.h"
#include <stdio.h>
#include <stdlib.h>

#include <stdlib.h>
#include <string.h>

/* Face order must match BerylFace: DOWN, UP, NORTH, SOUTH, WEST, EAST. */
static const int k_delta[6][3] = {
	{ 0, -1, 0 }, { 0, 1, 0 }, { 0, 0, -1 }, { 0, 0, 1 }, { -1, 0, 0 }, { 1, 0, 0 }
};
static const BerylVec3 k_normal[6] = {
	{ 0.0f, -1.0f, 0.0f }, { 0.0f, 1.0f, 0.0f },
	{ 0.0f, 0.0f, -1.0f }, { 0.0f, 0.0f, 1.0f },
	{ -1.0f, 0.0f, 0.0f }, { 1.0f, 0.0f, 0.0f }
};

#define VISIT_TABLE_BITS 15

void beryl_visible_set_init(BerylVisibleSet *v) {
	memset(v, 0, sizeof(*v));
	int cap = 1024;
	v->entries = (BerylVisibleEntry *)calloc((size_t)cap, sizeof(BerylVisibleEntry));
	v->capacity = cap;
	v->visit_keys = (uint64_t *)calloc(1u << VISIT_TABLE_BITS, sizeof(uint64_t));
	v->visit_stamp = (uint32_t *)calloc(1u << VISIT_TABLE_BITS, sizeof(uint32_t));
	v->visit_mask = (1 << VISIT_TABLE_BITS) - 1;
	v->frame = 1;
}

void beryl_visible_set_free(BerylVisibleSet *v) {
	free(v->entries); free(v->visit_keys); free(v->visit_stamp);
	memset(v, 0, sizeof(*v));
}

static void push_entry(BerylVisibleSet *v, int32_t cx, int32_t sy, int32_t cz,
                       uint8_t faces, float dist_sq, bool empty) {
	if (v->count >= v->capacity) {
		int nc = v->capacity * 2;
		BerylVisibleEntry *p = (BerylVisibleEntry *)realloc(v->entries, (size_t)nc * sizeof(*p));
		if (!p) return;
		v->entries = p;
		v->capacity = nc;
	}
	BerylVisibleEntry *e = &v->entries[v->count++];
	e->cx = cx; e->csy = sy; e->cz = cz;
	e->key = beryl_section_key(cx, sy, cz);
	e->faces = faces;
	e->distance_sq = dist_sq;
	e->empty = empty;
}

static bool visit_mark(BerylVisibleSet *v, uint64_t key) {
	uint32_t h = (uint32_t)((key * 0x9E3779B97F4A7C15ull) >> 32);
	uint32_t i = h & (uint32_t)v->visit_mask;
	for (int probe = 0; probe < 64; probe++) {
		uint64_t at = v->visit_keys[i];
		uint32_t stamp = v->visit_stamp[i];
		if (stamp != v->frame) {
			v->visit_keys[i] = key;
			v->visit_stamp[i] = v->frame;
			return true; /* first time this frame */
		}
		if (at == key) return false;
		i = (i + 1u) & (uint32_t)v->visit_mask;
	}
	return true; /* table saturated: treat as unvisited, never wrong for culling */
}

typedef struct Node {
	int32_t cx, csy, cz;
} Node;

int beryl_visible_set_compute(BerylVisibleSet *v, BerylWorld *w, const BerylCamera *cam,
                              int radius_sections) {
	v->count = 0;
	v->visited_sections = 0;
	v->culled_by_occlusion = 0;
	v->culled_by_frustum = 0;
	v->frame++;
	if (v->frame == 0) {
		memset(v->visit_stamp, 0, sizeof(uint32_t) * (size_t)(v->visit_mask + 1));
		v->frame = 1;
	}

	const int R = BERYL_MAX(radius_sections, 1);
	BerylVec3 p = cam->pos;
	int32_t cam_sx = (int32_t)floorf(p.x / (float)BERYL_SECTION_SIDE);
	int32_t cam_sy = (int32_t)floorf(p.y / (float)BERYL_SECTION_SIDE);
	int32_t cam_sz = (int32_t)floorf(p.z / (float)BERYL_SECTION_SIDE);

	Node *stack = (Node *)malloc(sizeof(Node) * (size_t)(2 * R + 3) * (size_t)(2 * R + 3) *
	                                  (size_t)(2 * R + 3));
	if (!stack) return 0;
	size_t sp = 0;
	stack[sp++] = (Node){ cam_sx, cam_sy, cam_sz };
	visit_mark(v, beryl_section_key(cam_sx, cam_sy, cam_sz));

	while (sp > 0) {
		Node n = stack[--sp];
		if (n.cx < cam_sx - R || n.cx > cam_sx + R ||
		    n.csy < cam_sy - R || n.csy > cam_sy + R ||
		    n.cz < cam_sz - R || n.cz > cam_sz + R) {
			continue;
		}
		v->visited_sections++;

		BerylSection *s = beryl_world_section(w, n.cx, n.csy, n.cz, false);

		/* The camera's own section and everything touching it are always kept: a
		 * cull that pops while you are standing inside a solid block is the worst
		 * possible artifact, and the cost is at most 27 sections. */
		bool adjacent_to_camera =
		    (n.cx >= cam_sx - 1 && n.cx <= cam_sx + 1) &&
		    (n.csy >= cam_sy - 1 && n.csy <= cam_sy + 1) &&
		    (n.cz >= cam_sz - 1 && n.cz <= cam_sz + 1);

		float cs = (float)BERYL_SECTION_SIDE;
		BerylVec3 centre = beryl_vec3(((float)n.cx + 0.5f) * cs,
		                              ((float)n.csy + 0.5f) * cs,
		                              ((float)n.cz + 0.5f) * cs);
		float ddx = p.x - centre.x, ddy = p.y - centre.y, ddz = p.z - centre.z;
		float dist_sq = ddx * ddx + ddy * ddy + ddz * ddz;

		if (!s || s->all_air) {
			/* Nothing to draw. Still expand through it: an empty section can never
			 * occlude, and skipping the expansion would hide geometry behind air. */
			if (!s) {
				/* Unloaded: do not expand, so the frontier does not explode into
				 * the whole world; the loader will bring it in and it gets visited
				 * from its own side then. */
				continue;
			}
			push_entry(v, n.cx, n.csy, n.cz, 0x3F, dist_sq, true);
		} else {
			BerylAabb box = beryl_section_aabb(n.cx, n.csy, n.cz, 0.0f);
			if (!adjacent_to_camera && !beryl_frustum_test_aabb(&cam->frustum, box)) {
				v->culled_by_frustum++;
				continue;
			}
			uint8_t faces = 0;
			for (int f = 0; f < 6; f++) {
				if (beryl_v3_dot(beryl_vec3(ddx, ddy, ddz), k_normal[f]) > 0.0f) {
					faces |= (uint8_t)(1u << f);
				}
			}
			push_entry(v, n.cx, n.csy, n.cz, faces, dist_sq, false);
		}

		for (int f = 0; f < 6; f++) {
			Node m = { n.cx + k_delta[f][0], n.csy + k_delta[f][1], n.cz + k_delta[f][2] };
			uint64_t mk = beryl_section_key(m.cx, m.csy, m.cz);

			/* Decide the seal cull *before* marking the neighbour. Marking first
			 * means a culled section stays marked even though it was never pushed
			 * onto the stack, so every other path into it is deduplicated away and
			 * it vanishes from the set -- even when it is adjacent to the camera,
			 * whose 27-section neighbourhood is supposed to be unconditionally
			 * visible. The order this triggers in depends on which neighbour the
			 * walk happens to reach first (empty ring sections from a padded
			 * relight change it), which is exactly the kind of traversal-order
			 * dependence a cull must never have. */
			bool sealed = s && (s->sealed_faces & (1u << f));
			if (sealed) {
				/* The guarantee is for the neighbour: a section that touches the
				 * camera is never culled no matter which side it was reached
				 * from. Only reject when the eye really is behind the sealing
				 * face: if the camera already sits past it, rays to the neighbour
				 * never cross this section at all, so the mask says nothing. */
				bool m_adjacent =
				    (m.cx >= cam_sx - 1 && m.cx <= cam_sx + 1) &&
				    (m.csy >= cam_sy - 1 && m.csy <= cam_sy + 1) &&
				    (m.cz >= cam_sz - 1 && m.cz <= cam_sz + 1);
				if (!m_adjacent) {
					BerylVec3 plane_point = beryl_vec3(
						((float)n.cx + 0.5f) * cs + k_normal[f].x * (cs * 0.5f),
						((float)n.csy + 0.5f) * cs + k_normal[f].y * (cs * 0.5f),
						((float)n.cz + 0.5f) * cs + k_normal[f].z * (cs * 0.5f));
					BerylVec3 to_cam = beryl_v3_sub(p, plane_point);
					if (beryl_v3_dot(to_cam, k_normal[f]) > 0.0f) {
						v->culled_by_occlusion++;
						continue;
					}
				}
			}
			if (!visit_mark(v, mk)) continue;
			stack[sp++] = m;
		}
	}

	free(stack);
	return v->count;
}

bool beryl_visible_set_contains(const BerylVisibleSet *v, int32_t cx, int32_t csy, int32_t cz) {
	uint64_t k = beryl_section_key(cx, csy, cz);
	for (int i = 0; i < v->count; i++) {
		if (v->entries[i].key == k) return true;
	}
	return false;
}
