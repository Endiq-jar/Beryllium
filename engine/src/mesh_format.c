/* mesh_format.c */
#include "mesh_format.h"

#include <stdlib.h>

static const BerylAttribDesc k_attribs[BERYL_ATTRIB_COUNT] = {
	{ 0, 2,  0, 2, "aPosXY" },
	{ 1, 1,  4, 2, "aPosZ"  },
	{ 2, 2,  6, 2, "aUV"    },
	{ 3, 2, 10, 1, "aPack0" },   /* x = ao|face, y = light            */
	{ 4, 2, 12, 1, "aPack1" }    /* x = tile (array layer), y = flags */
};

const BerylAttribDesc *beryl_attrib_desc(BerylAttrib a) {
	return &k_attribs[(int)a];
}

void beryl_section_mesh_init(BerylSectionMesh *m, int32_t cx, int32_t csy, int32_t cz) {
	for (int i = 0; i < BERYL_LAYER_COUNT; i++) {
		m->layer[i].verts = NULL;
		m->layer[i].vert_count = 0;
		m->layer[i].indices = NULL;
		m->layer[i].index_count = 0;
		m->layer[i].quad_count = 0;
	}
	m->source_revision = 0;
	m->quad_total = 0;
	m->cx = cx; m->csy = csy; m->cz = cz;
	m->valid = false;
	m->uploaded = false;
	m->bounds.min = beryl_vec3(0, 0, 0);
	m->bounds.max = beryl_vec3((float)BERYL_SECTION_SIDE, (float)BERYL_SECTION_SIDE, (float)BERYL_SECTION_SIDE);
}

void beryl_section_mesh_free(BerylSectionMesh *m) {
	for (int i = 0; i < BERYL_LAYER_COUNT; i++) {
		free(m->layer[i].verts);
		free(m->layer[i].indices);
		m->layer[i].verts = NULL;
		m->layer[i].indices = NULL;
		m->layer[i].vert_count = 0;
		m->layer[i].index_count = 0;
	}
	m->valid = false;
	m->uploaded = false;
}

void beryl_section_mesh_reset(BerylSectionMesh *m) {
	for (int i = 0; i < BERYL_LAYER_COUNT; i++) {
		m->layer[i].vert_count = 0;
		m->layer[i].index_count = 0;
		m->layer[i].quad_count = 0;
	}
	m->quad_total = 0;
	m->valid = false;
	m->uploaded = false;
}

size_t beryl_section_mesh_vertex_count(const BerylSectionMesh *m) {
	size_t n = 0;
	for (int i = 0; i < BERYL_LAYER_COUNT; i++) n += m->layer[i].vert_count;
	return n;
}

size_t beryl_section_mesh_index_count(const BerylSectionMesh *m) {
	size_t n = 0;
	for (int i = 0; i < BERYL_LAYER_COUNT; i++) n += m->layer[i].index_count;
	return n;
}

bool beryl_section_mesh_index_range_ok(const BerylSectionMesh *m) {
	size_t verts = beryl_section_mesh_vertex_count(m);
	if (verts > 0xFFFFFu) return false;
	for (int i = 0; i < BERYL_LAYER_COUNT; i++) {
		const BerylMeshLayer *l = &m->layer[i];
		for (size_t k = 0; k < l->index_count; k++) {
			if (l->indices[k] >= l->vert_count) return false;
		}
	}
	return true;
}
