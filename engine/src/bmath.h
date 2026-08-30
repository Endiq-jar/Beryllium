/* bmath.h -- the engine's entire linear algebra + geometry kit.
 *
 * Column-major 4x4 matrices, right-handed, OpenGL-convention clip space
 * (z in [-1, 1] for GL, remapped to [0, 1] by the Vulkan backend at submit
 * time — see rhi_vk.c). No external math library is used: only add/mul/sqrt/
 * sin/cos/fmod, so the software rasterizer and the GPU backends compute the
 * *identical* transform and pixel results can be diffed test-for-test.
 */
#ifndef BERYL_BMATH_H
#define BERYL_BMATH_H

#include "bcore.h"
#include <math.h>

typedef struct BerylVec2  { float x, y; } BerylVec2;
typedef struct BerylVec3  { float x, y, z; } BerylVec3;
typedef struct BerylVec4  { float x, y, z, w; } BerylVec4;
typedef struct BerylMat4  { float m[16]; } BerylMat4; /* m[c*4+r]: column c */
typedef struct BerylAabb  { BerylVec3 min, max; } BerylAabb;

/* 6 frustum planes, packed as vec4 (a,b,c,d) with a*x+b*y+c*z+d >= 0 inside. */
typedef struct BerylFrustum { BerylVec4 plane[6]; } BerylFrustum;

enum {
	BERYL_PLANE_LEFT = 0, BERYL_PLANE_RIGHT, BERYL_PLANE_BOTTOM,
	BERYL_PLANE_TOP, BERYL_PLANE_NEAR, BERYL_PLANE_FAR, BERYL_PLANE_COUNT = 6
};

/* ------------------------------------------------------------------- vec3 -- */
static inline BerylVec3 beryl_vec3(float x, float y, float z) {
	BerylVec3 v = { x, y, z }; return v;
}
static inline BerylVec4 beryl_vec4(float x, float y, float z, float w) {
	BerylVec4 v = { x, y, z, w }; return v;
}
static inline BerylVec3 beryl_v3_add(BerylVec3 a, BerylVec3 b) {
	return beryl_vec3(a.x + b.x, a.y + b.y, a.z + b.z);
}
static inline BerylVec3 beryl_v3_sub(BerylVec3 a, BerylVec3 b) {
	return beryl_vec3(a.x - b.x, a.y - b.y, a.z - b.z);
}
static inline BerylVec3 beryl_v3_scale(BerylVec3 a, float s) {
	return beryl_vec3(a.x * s, a.y * s, a.z * s);
}
static inline float beryl_v3_dot(BerylVec3 a, BerylVec3 b) {
	return a.x * b.x + a.y * b.y + a.z * b.z;
}
static inline BerylVec3 beryl_v3_cross(BerylVec3 a, BerylVec3 b) {
	return beryl_vec3(a.y * b.z - a.z * b.y,
	                   a.z * b.x - a.x * b.z,
	                   a.x * b.y - a.y * b.x);
}
static inline float beryl_v3_length_sq(BerylVec3 a) { return beryl_v3_dot(a, a); }
static inline float beryl_v3_length(BerylVec3 a)    { return sqrtf(beryl_v3_length_sq(a)); }
BerylVec3 beryl_v3_normalize(BerylVec3 a);

/* Minecraft-facing angle convention: yaw 0 = +Z ... we use the common engine
 * convention (yaw 0 = -Z is vanilla; this engine uses 0 = +X) — see README. */
BerylVec3 beryl_yaw_pitch_to_dir(float yaw, float pitch);

/* ------------------------------------------------------------------- mat4 -- */
BerylMat4 beryl_mat4_identity(void);
BerylMat4 beryl_mat4_mul(BerylMat4 a, BerylMat4 b);          /* a * b */
BerylMat4 beryl_mat4_perspective(float fov_y_rad, float aspect, float znear, float zfar);
BerylMat4 beryl_mat4_ortho(float l, float r, float b, float t, float n, float f);
BerylMat4 beryl_mat4_look_at(BerylVec3 eye, BerylVec3 center, BerylVec3 up);
BerylMat4 beryl_mat4_translate(BerylVec3 t);
BerylMat4 beryl_mat4_scale(float s);
BerylMat4 beryl_mat4_transpose(BerylMat4 a);
BerylVec4 beryl_mat4_transform(BerylMat4 m, BerylVec4 v);
BerylVec3 beryl_mat4_transform_point(BerylMat4 m, BerylVec3 p);
BerylVec3 beryl_mat4_transform_dir(BerylMat4 m, BerylVec3 d);

/* ---------------------------------------------------------------- frustum -- */
/* Extracts the six planes from a view-projection matrix (Gribb/Hartmann). */
void beryl_frustum_from_view_proj(BerylFrustum *out, BerylMat4 vp);
bool beryl_frustum_test_aabb(const BerylFrustum *f, BerylAabb box);
bool beryl_frustum_test_point(const BerylFrustum *f, BerylVec3 p);

/* ------------------------------------------------------------------- misc -- */
static inline bool beryl_aabb_intersects(BerylAabb a, BerylAabb b) {
	return a.min.x <= b.max.x && a.max.x >= b.min.x &&
	       a.min.y <= b.max.y && a.max.y >= b.min.y &&
	       a.min.z <= b.max.z && a.max.z >= b.min.z;
}
static inline BerylAabb beryl_aabb_from_min_max(BerylVec3 mn, BerylVec3 mx) {
	BerylAabb b; b.min = mn; b.max = mx; return b;
}
/* Section AABB in world space for a section at (sx, sy, sz) section coords. */
static inline BerylAabb beryl_section_aabb(int sx, int sy, int sz, float pad) {
	BerylVec3 mn = beryl_vec3((float)(sx * BERYL_SECTION_SIDE) - pad,
	                          (float)(sy * BERYL_SECTION_SIDE) - pad,
	                          (float)(sz * BERYL_SECTION_SIDE) - pad);
	BerylVec3 mx = beryl_vec3(mn.x + BERYL_SECTION_SIDE + 2 * pad,
	                          mn.y + BERYL_SECTION_SIDE + 2 * pad,
	                          mn.z + BERYL_SECTION_SIDE + 2 * pad);
	return beryl_aabb_from_min_max(mn, mx);
}

float beryl_lerp(float a, float b, float t);
float beryl_smoothstep(float edge0, float edge1, float x);
int   beryl_next_pow2(int v);

/* Deterministic 32-bit integer hash + float noise field, used by worldgen and
 * by the procedural texture atlas. Murmur3-style finalizer; identical on every
 * platform (no libm transcendental in the hot path). */
uint32_t beryl_hash_u32(uint32_t x);
uint32_t beryl_hash3(int32_t x, int32_t y, uint32_t seed);
uint32_t beryl_hash4(int32_t x, int32_t y, int32_t z, uint32_t seed);
/* Value noise in [-1, 1] with quintic interpolation. */
float beryl_noise2(float x, float y, uint32_t seed);
float beryl_noise3(float x, float y, float z, uint32_t seed);
/* Fractal Brownian motion, normalized to roughly [-1, 1]. */
float beryl_fbm2(float x, float y, uint32_t seed, int octaves, float lacunarity, float gain);
float beryl_fbm3(float x, float y, float z, uint32_t seed, int octaves, float lacunarity, float gain);

#endif /* BERYL_BMATH_H */
