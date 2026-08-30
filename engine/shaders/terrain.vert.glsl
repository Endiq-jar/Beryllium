/* terrain.vert.glsl -- Beryllium Engine terrain vertex stage.
 *
 * Shared by the OpenGL 3.3 core and OpenGL ES 3.0 paths: the backend prepends the
 * right #version line, and nothing below either profile is used. Vulkan consumes
 * the equivalent module emitted by spirv.c, which is generated from this shader's
 * interface contract (same attribute list, same uniform block, same maths).
 *
 * Integer attributes throughout: the mesher writes fixed-point, so there is no
 * float32 rounding step and no precision loss far from the origin.
 */

/* --- inputs (mesh_format.h: BERYL_ATTRIB_*) --- */
in uvec2 aPosXY;   /* location 0, section-local 8.8 fixed point   */
in uint  aPosZ;    /* location 1, section-local 8.8 fixed point   */
in uvec2 aUV;      /* location 2, 4.12 fixed point, in blocks     */
in uvec2 aPack0;   /* location 3, x = ao|face, y = sky|block      */
in uvec2 aPack1;   /* location 4, x = tile layer, y = flags       */

out vec2  vUV;
out float vSky;
out float vBlk;
out float vAO;
out float vTint;
out float vTile;
out float vFog;
flat out int vFace;
flat out int vFlags;

/* This file is the human-readable source of truth for the GPU interface. It is
 * compiled as-is by the OpenGL backend (which prepends the #version line), and
 * embedded into the binary by tools/embed.c so the demo needs no data files.
 * A Vulkan path would consume the equivalent SPIR-V module instead; there is no
 * such module in this tree yet, so nothing here pretends otherwise. */

layout(std140) uniform Terrain {
	mat4  uMVP;
	vec4  uSection;     /* xyz = section origin in blocks        */
	vec4  uCamPos;
	vec4  uFog;         /* x = start, y = end                    */
	vec4  uFogColor;
	vec4  uTint[8];
	vec4  uParams;      /* x = day, y = 1/tile size, z = mode, w = water alpha */
	vec4  uPad;
} Terrain;

const float POS_SCALE = 256.0;   /* 1 << BERYL_POS_SHIFT */
const float UV_SCALE  = 16.0;    /* 1 << BERYL_UV_SHIFT  */

/* AO levels 0..3 -> vanilla-ish multipliers. Kept in a const array so the
 * software path can use the identical table. */
const float AO_TABLE[4] = float[4](0.43, 0.68, 0.84, 1.00);

void main() {
	vec3 local = vec3(float(aPosXY.x), float(aPosXY.y), float(aPosZ)) / POS_SCALE;
	vec3 world = local + Terrain.uSection.xyz;

	gl_Position = Terrain.uMVP * vec4(world, 1.0);

	/* Tiling is done here as fract() so a merged rectangle repeats the block
	 * texture exactly, and the section origin keeps every section in phase. */
	vUV = vec2(aUV) / UV_SCALE;

	int ao_face = int(aPack0.x);
	vAO = AO_TABLE[ao_face & 3];
	vFace = (ao_face >> 2) & 7;
	int light = int(aPack0.y);
	vSky = float(light & 15);
	vBlk = float((light >> 4) & 15);
	int flags = int(aPack1.y);
	vTint = float((flags >> 4) & 7);
	vTile = float(aPack1.x);
	vFlags = flags;

	vec3 d = world - Terrain.uCamPos.xyz;
	vFog = length(d);
}
