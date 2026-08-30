/* camera.h -- eye position, projection, and the view frustum the cullers use. */
#ifndef BERYL_CAMERA_H
#define BERYL_CAMERA_H

#include "bmath.h"

typedef struct BerylCamera {
	/* --- input --- */
	BerylVec3 pos;             /* eye, world blocks           */
	float     yaw, pitch;      /* radians                     */
	float     fov_y;           /* vertical FOV, radians       */
	float     aspect;          /* width / height              */
	float     znear, zfar;
	float     fog_start, fog_end;
	int       width, height;   /* framebuffer pixels          */

	/* --- derived by beryl_camera_update() --- */
	BerylVec3 dir, right, up;
	BerylMat4 view, proj, view_proj;
	BerylFrustum frustum;
	float     view_height;     /* eye height above ground, for underwater fog */
	bool      underwater;
} BerylCamera;

void beryl_camera_init(BerylCamera *c, int width, int height);
void beryl_camera_update(BerylCamera *c);
/* Places the eye on a sphere around `target` -- used by the demo's orbit mode and
 * by the image-based tests so a reference render does not depend on player input. */
void beryl_camera_orbit(BerylCamera *c, BerylVec3 target, float radius, float yaw, float pitch);

/* Projects a world point to pixel coordinates (y down, screen space). Returns the
 * clipped w so callers can reject points behind the eye. */
BerylVec3 beryl_camera_project(const BerylCamera *c, BerylVec3 world, float *out_w);
/* Ray through a pixel, for picking. */
BerylVec3 beryl_camera_ray_dir(const BerylCamera *c, float pixel_x, float pixel_y);

#endif /* BERYL_CAMERA_H */
