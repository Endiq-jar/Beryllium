/* bmath.c -- see math.h for the conventions this file implements. */
#include "bmath.h"

#include <float.h>

BerylVec3 beryl_v3_normalize(BerylVec3 a) {
	float len = beryl_v3_length(a);
	if (len < 1e-12f) {
		return beryl_vec3(0.0f, 0.0f, 0.0f);
	}
	return beryl_v3_scale(a, 1.0f / len);
}

BerylVec3 beryl_yaw_pitch_to_dir(float yaw, float pitch) {
	/* yaw measured from +X towards +Z, pitch positive = looking up. */
	float cy = cosf(yaw), sy = sinf(yaw);
	float cp = cosf(pitch), sp = sinf(pitch);
	return beryl_vec3(cp * cy, sp, cp * sy);
}

BerylMat4 beryl_mat4_identity(void) {
	BerylMat4 r = { { 1, 0, 0, 0,  0, 1, 0, 0,  0, 0, 1, 0,  0, 0, 0, 1 } };
	return r;
}

BerylMat4 beryl_mat4_mul(BerylMat4 a, BerylMat4 b) {
	BerylMat4 r;
	for (int c = 0; c < 4; c++) {
		for (int row = 0; row < 4; row++) {
			float sum = 0.0f;
			for (int k = 0; k < 4; k++) {
				sum += a.m[k * 4 + row] * b.m[c * 4 + k];
			}
			r.m[c * 4 + row] = sum;
		}
	}
	return r;
}

BerylMat4 beryl_mat4_perspective(float fov_y_rad, float aspect, float znear, float zfar) {
	BerylMat4 r = { 0 };
	float f = 1.0f / tanf(fov_y_rad * 0.5f);
	r.m[0] = f / (aspect != 0.0f ? aspect : 1.0f);
	r.m[5] = f;
	float denom = znear - zfar;
	if (denom == 0.0f) denom = -FLT_MIN;
	r.m[10] = (zfar + znear) / denom;
	r.m[11] = -1.0f;
	r.m[14] = (2.0f * zfar * znear) / denom;
	return r;
}

BerylMat4 beryl_mat4_ortho(float l, float r, float b, float t, float n, float f) {
	BerylMat4 m = beryl_mat4_identity();
	m.m[0] = 2.0f / (r - l);
	m.m[5] = 2.0f / (t - b);
	m.m[10] = 2.0f / (n - f);
	m.m[12] = (l + r) / (l - r);
	m.m[13] = (b + t) / (b - t);
	m.m[14] = (n + f) / (n - f);
	return m;
}

BerylMat4 beryl_mat4_look_at(BerylVec3 eye, BerylVec3 center, BerylVec3 up) {
	BerylVec3 z = beryl_v3_normalize(beryl_v3_sub(eye, center));
	if (beryl_v3_length_sq(z) == 0.0f) {
		z = beryl_vec3(0.0f, 0.0f, 1.0f);
	}
	BerylVec3 x = beryl_v3_normalize(beryl_v3_cross(up, z));
	if (beryl_v3_length_sq(x) == 0.0f) {
		/* eye/up degenerate: pick any perpendicular axis. */
		x = beryl_v3_normalize(beryl_v3_cross(beryl_vec3(0.0f, 1.0f, 0.0f), z));
	}
	BerylVec3 y = beryl_v3_cross(z, x);

	BerylMat4 r = beryl_mat4_identity();
	r.m[0] = x.x; r.m[4] = x.y; r.m[8]  = x.z;
	r.m[1] = y.x; r.m[5] = y.y; r.m[9]  = y.z;
	r.m[2] = z.x; r.m[6] = z.y; r.m[10] = z.z;
	r.m[12] = -beryl_v3_dot(x, eye);
	r.m[13] = -beryl_v3_dot(y, eye);
	r.m[14] = -beryl_v3_dot(z, eye);
	return r;
}

BerylMat4 beryl_mat4_translate(BerylVec3 t) {
	BerylMat4 r = beryl_mat4_identity();
	r.m[12] = t.x; r.m[13] = t.y; r.m[14] = t.z;
	return r;
}

BerylMat4 beryl_mat4_scale(float s) {
	BerylMat4 r = beryl_mat4_identity();
	r.m[0] = r.m[5] = r.m[10] = s;
	return r;
}

BerylMat4 beryl_mat4_transpose(BerylMat4 a) {
	BerylMat4 r;
	for (int c = 0; c < 4; c++) {
		for (int row = 0; row < 4; row++) {
			r.m[c * 4 + row] = a.m[row * 4 + c];
		}
	}
	return r;
}

BerylVec4 beryl_mat4_transform(BerylMat4 m, BerylVec4 v) {
	BerylVec4 r;
	r.x = m.m[0] * v.x + m.m[4] * v.y + m.m[8]  * v.z + m.m[12] * v.w;
	r.y = m.m[1] * v.x + m.m[5] * v.y + m.m[9]  * v.z + m.m[13] * v.w;
	r.z = m.m[2] * v.x + m.m[6] * v.y + m.m[10] * v.z + m.m[14] * v.w;
	r.w = m.m[3] * v.x + m.m[7] * v.y + m.m[11] * v.z + m.m[15] * v.w;
	return r;
}

BerylVec3 beryl_mat4_transform_point(BerylMat4 m, BerylVec3 p) {
	BerylVec4 r = beryl_mat4_transform(m, beryl_vec4(p.x, p.y, p.z, 1.0f));
	return beryl_vec3(r.x, r.y, r.z);
}

BerylVec3 beryl_mat4_transform_dir(BerylMat4 m, BerylVec3 d) {
	BerylVec4 r = beryl_mat4_transform(m, beryl_vec4(d.x, d.y, d.z, 0.0f));
	return beryl_vec3(r.x, r.y, r.z);
}

/* ------------------------------------------------------------- frustum ---- */
void beryl_frustum_from_view_proj(BerylFrustum *out, BerylMat4 vp) {
	/* Row vectors of the combined matrix, per Gribb & Hartmann. m[col*4+row]. */
	const float *m = vp.m;
	BerylVec4 planes[6] = {
		/* left   */ { m[3] + m[0], m[7] + m[4], m[11] + m[8],  m[15] + m[12] },
		/* right  */ { m[3] - m[0], m[7] - m[4], m[11] - m[8],  m[15] - m[12] },
		/* bottom */ { m[3] + m[1], m[7] + m[5], m[11] + m[9],  m[15] + m[13] },
		/* top    */ { m[3] - m[1], m[7] - m[5], m[11] - m[9],  m[15] - m[13] },
		/* near   */ { m[3] + m[2], m[7] + m[6], m[11] + m[10], m[15] + m[14] },
		/* far    */ { m[3] - m[2], m[7] - m[6], m[11] - m[10], m[15] - m[14] }
	};
	for (int i = 0; i < 6; i++) {
		BerylVec3 n = beryl_vec3(planes[i].x, planes[i].y, planes[i].z);
		float len = beryl_v3_length(n);
		if (len < 1e-20f) {
			out->plane[i] = planes[i];
			continue;
		}
		float inv = 1.0f / len;
		out->plane[i].x = n.x * inv;
		out->plane[i].y = n.y * inv;
		out->plane[i].z = n.z * inv;
		out->plane[i].w = planes[i].w * inv;
	}
}

/* p-vertex test: for each plane pick the corner most in the direction of the
 * normal; if it is behind the plane the whole box is, and one rejection is
 * enough. Never rejects a box that intersects (may accept a missed box). */
bool beryl_frustum_test_aabb(const BerylFrustum *f, BerylAabb box) {
	for (int i = 0; i < BERYL_PLANE_COUNT; i++) {
		const BerylVec4 *p = &f->plane[i];
		float px = p->x >= 0.0f ? box.max.x : box.min.x;
		float py = p->y >= 0.0f ? box.max.y : box.min.y;
		float pz = p->z >= 0.0f ? box.max.z : box.min.z;
		if (p->x * px + p->y * py + p->z * pz + p->w < 0.0f) {
			return false;
		}
	}
	return true;
}

bool beryl_frustum_test_point(const BerylFrustum *f, BerylVec3 p) {
	for (int i = 0; i < BERYL_PLANE_COUNT; i++) {
		const BerylVec4 *pl = &f->plane[i];
		if (pl->x * p.x + pl->y * p.y + pl->z * p.z + pl->w < 0.0f) {
			return false;
		}
	}
	return true;
}

/* ------------------------------------------------------------------ misc --- */
float beryl_lerp(float a, float b, float t) { return a + (b - a) * t; }

float beryl_smoothstep(float edge0, float edge1, float x) {
	if (edge1 == edge0) return x < edge0 ? 0.0f : 1.0f;
	float t = BERYL_CLAMP((x - edge0) / (edge1 - edge0), 0.0f, 1.0f);
	return t * t * (3.0f - 2.0f * t);
}

int beryl_next_pow2(int v) {
	int p = 1;
	while (p < v) p <<= 1;
	return p;
}

/* ------------------------------------------------------------------ noise --- */
uint32_t beryl_hash_u32(uint32_t x) {
	x ^= x >> 16;
	x *= 0x7feb352dU;
	x ^= x >> 15;
	x *= 0x846ca68bU;
	x ^= x >> 16;
	return x;
}

uint32_t beryl_hash3(int32_t x, int32_t y, uint32_t seed) {
	uint32_t h = seed * 0x9e3779b9U;
	h ^= (uint32_t)x * 0x85ebca6bU;
	h ^= (uint32_t)y * 0xc2b2ae35U;
	return beryl_hash_u32(h);
}

uint32_t beryl_hash4(int32_t x, int32_t y, int32_t z, uint32_t seed) {
	uint32_t h = seed * 0x9e3779b9U;
	h ^= (uint32_t)x * 0x85ebca6bU;
	h ^= (uint32_t)y * 0xc2b2ae35U;
	h ^= (uint32_t)z * 0x27d4eb2fU;
	return beryl_hash_u32(h);
}

static float hash_to_unit01(uint32_t h) {
	return (float)(h >> 8) * (1.0f / 16777216.0f);
}

static float quintic(float t) { return t * t * t * (t * (t * 6.0f - 15.0f) + 10.0f); }

float beryl_noise2(float x, float y, uint32_t seed) {
	int xi = (int)x, yi = (int)y;
	if (x < 0.0f) xi = (int)x - 1;
	if (y < 0.0f) yi = (int)y - 1;
	float tx = quintic(x - (float)xi);
	float ty = quintic(y - (float)yi);

	float a = hash_to_unit01(beryl_hash3(xi,     yi,     seed));
	float b = hash_to_unit01(beryl_hash3(xi + 1, yi,     seed));
	float c = hash_to_unit01(beryl_hash3(xi,     yi + 1, seed));
	float d = hash_to_unit01(beryl_hash3(xi + 1, yi + 1, seed));

	float u = beryl_lerp(beryl_lerp(a, b, tx), beryl_lerp(c, d, tx), ty);
	return u * 2.0f - 1.0f;
}

float beryl_noise3(float x, float y, float z, uint32_t seed) {
	int xi = (int)x, yi = (int)y, zi = (int)z;
	if (x < 0.0f) xi--;
	if (y < 0.0f) yi--;
	if (z < 0.0f) zi--;
	float tx = quintic(x - (float)xi);
	float ty = quintic(y - (float)yi);
	float tz = quintic(z - (float)zi);

	float corners[8];
	int i = 0;
	for (int dz = 0; dz < 2; dz++) {
		for (int dy = 0; dy < 2; dy++) {
			for (int dx = 0; dx < 2; dx++) {
				corners[i++] = hash_to_unit01(
					beryl_hash4(xi + dx, yi + dy, zi + dz, seed));
			}
		}
	}
	float x00 = beryl_lerp(corners[0], corners[1], tx);
	float x10 = beryl_lerp(corners[2], corners[3], tx);
	float x01 = beryl_lerp(corners[4], corners[5], tx);
	float x11 = beryl_lerp(corners[6], corners[7], tx);
	float u = beryl_lerp(beryl_lerp(x00, x10, ty), beryl_lerp(x01, x11, ty), tz);
	return u * 2.0f - 1.0f;
}

float beryl_fbm2(float x, float y, uint32_t seed, int octaves, float lacunarity, float gain) {
	float sum = 0.0f, amp = 1.0f, norm = 0.0f, fx = x, fy = y;
	for (int o = 0; o < octaves; o++) {
		sum += amp * beryl_noise2(fx, fy, seed + (uint32_t)o * 0x1234u);
		norm += amp;
		amp *= gain;
		fx *= lacunarity;
		fy *= lacunarity;
	}
	return norm > 0.0f ? sum / norm : 0.0f;
}

float beryl_fbm3(float x, float y, float z, uint32_t seed, int octaves, float lacunarity, float gain) {
	float sum = 0.0f, amp = 1.0f, norm = 0.0f, fx = x, fy = y, fz = z;
	for (int o = 0; o < octaves; o++) {
		sum += amp * beryl_noise3(fx, fy, fz, seed + (uint32_t)o * 0x2345u);
		norm += amp;
		amp *= gain;
		fx *= lacunarity;
		fy *= lacunarity;
		fz *= lacunarity;
	}
	return norm > 0.0f ? sum / norm : 0.0f;
}
