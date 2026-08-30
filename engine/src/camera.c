/* camera.c */
#include "camera.h"
#include <string.h>

void beryl_camera_init(BerylCamera *c, int width, int height) {
	memset(c, 0, sizeof(*c));
	c->pos = beryl_vec3(0.0f, 80.0f, 0.0f);
	c->yaw = -0.7f;
	c->pitch = -0.35f;
	c->fov_y = 70.0f * (3.14159265358979f / 180.0f);
	c->width = width > 0 ? width : 1;
	c->height = height > 0 ? height : 1;
	c->aspect = (float)c->width / (float)c->height;
	c->znear = 0.0625f;              /* 1/16 block: no clipping when your nose is in a wall */
	c->zfar = 256.0f;
	c->fog_start = 160.0f;
	c->fog_end = 250.0f;
	beryl_camera_update(c);
}

void beryl_camera_update(BerylCamera *c) {
	c->aspect = (float)c->width / (float)(c->height ? c->height : 1);
	c->dir = beryl_yaw_pitch_to_dir(c->yaw, c->pitch);
	/* Right/up from the view direction, with world Y as the reference up vector.
	 * Pitch is clamped to +-89.9 degrees by the caller chain (see engine.c) so
	 * the cross product never degenerates. */
	c->right = beryl_v3_normalize(beryl_v3_cross(c->dir, beryl_vec3(0.0f, 1.0f, 0.0f)));
	if (beryl_v3_length_sq(c->right) < 1e-12f) c->right = beryl_vec3(1.0f, 0.0f, 0.0f);
	c->up = beryl_v3_normalize(beryl_v3_cross(c->right, c->dir));

	BerylVec3 target = beryl_v3_add(c->pos, beryl_v3_scale(c->dir, 1.0f));
	c->view = beryl_mat4_look_at(c->pos, target, c->up);
	c->proj = beryl_mat4_perspective(c->fov_y, c->aspect, c->znear, c->zfar);
	c->view_proj = beryl_mat4_mul(c->proj, c->view);
	beryl_frustum_from_view_proj(&c->frustum, c->view_proj);

	/* Distance-based fog range follows the view distance so the horizon never
	 * shows a hard chunk edge. */
	if (c->fog_end > c->zfar) c->fog_end = c->zfar;
	if (c->fog_start >= c->fog_end) c->fog_start = c->fog_end * 0.5f;
}

void beryl_camera_orbit(BerylCamera *c, BerylVec3 target, float radius, float yaw, float pitch) {
	c->yaw = yaw;
	c->pitch = pitch;
	BerylVec3 dir = beryl_yaw_pitch_to_dir(yaw, pitch);
	c->pos = beryl_v3_sub(target, beryl_v3_scale(dir, radius));
	beryl_camera_update(c);
}

BerylVec3 beryl_camera_project(const BerylCamera *c, BerylVec3 world, float *out_w) {
	BerylVec4 clip = beryl_mat4_transform(c->view_proj, (BerylVec4){ world.x, world.y, world.z, 1.0f });
	if (out_w) *out_w = clip.w;
	float inv = clip.w != 0.0f ? 1.0f / clip.w : 0.0f;
	float ndc_x = clip.x * inv;
	float ndc_y = clip.y * inv;
	BerylVec3 out;
	out.x = (ndc_x * 0.5f + 0.5f) * (float)c->width;
	out.y = (1.0f - (ndc_y * 0.5f + 0.5f)) * (float)c->height;   /* y down */
	out.z = clip.z * inv;
	return out;
}

BerylVec3 beryl_camera_ray_dir(const BerylCamera *c, float pixel_x, float pixel_y) {
	float ndc_x = (pixel_x / (float)c->width) * 2.0f - 1.0f;
	float ndc_y = 1.0f - (pixel_y / (float)c->height) * 2.0f;
	/* Reconstruct from the basis instead of inverting the matrices: cheaper and
	 * exact for the perspective case we care about. */
	float tan_y = tanf(c->fov_y * 0.5f);
	float tan_x = tan_y * c->aspect;
	BerylVec3 d = beryl_v3_add(beryl_v3_add(c->dir,
	                     beryl_v3_scale(c->right, ndc_x * tan_x)),
	                     beryl_v3_scale(c->up, ndc_y * tan_y));
	return beryl_v3_normalize(d);
}
