/* terrain.frag.glsl -- terrain fragment stage. Mirrors beryl_terrain_frag() in
 * src/rhi.c line for line: the software backend is the reference implementation
 * of this file, and tests/test_shader_parity.c checks that both produce the same
 * colour for the same inputs. */

in vec2  vUV;
in float vSky;
in float vBlk;
in float vAO;
in float vTint;
in float vTile;
in float vFog;
flat in int vFace;
flat in int vFlags;

out vec4 fragColor;

uniform sampler2DArray uTexArray;
uniform sampler2D uLightmap;

layout(std140) uniform Terrain {
	mat4  uMVP;
	vec4  uSection;
	vec4  uCamPos;
	vec4  uFog;
	vec4  uFogColor;
	vec4  uTint[8];
	vec4  uParams;
	vec4  uPad;
} Terrain;

/* Face shading, vanilla's ramp: top, then N/S, then E/W, then bottom. */
const float FACE_SHADE[6] = float[6](0.50, 1.00, 0.80, 0.80, 0.60, 0.60);

const int MODE_NORMAL = 0;
const int MODE_LIGHTMAP = 1;
const int MODE_TINT = 2;
const int MODE_WIREFRAME = 3;
const int MODE_FOG_NEAR = 4;

void main() {
	int mode = int(Terrain.uParams.z) & 7;

	vec4 tex = vec4(1.0);
	if (mode != MODE_LIGHTMAP) {
		tex = texture(uTexArray, vec3(fract(vUV), vTile + 0.5));
	}
	if (tex.a < 0.5 && (vFlags & 1) != 0) {
		discard;                       /* alpha-tested cutout (leaves, torch) */
	}

	vec3 rgb = tex.rgb;
	float alpha = tex.a;

	if (mode == MODE_LIGHTMAP) {
		rgb = vec3(vSky / 15.0, vBlk / 15.0, 0.25);
		alpha = 1.0;
	} else if (mode == MODE_FOG_NEAR) {
		float f = clamp(vFog / 64.0, 0.0, 1.0);
		rgb = vec3(1.0 - f, 0.6, 0.1);
		alpha = 1.0;
	} else {
		if (mode != MODE_TINT) {
			/* Biome tint (grass/foliage/water) then the light LUT then AO then
			 * face shading -- the same order vanilla uses, because the order is
			 * visible: tint before light keeps torch-lit grass green. */
			vec3 tint = Terrain.uTint[int(vTint)].rgb;
			rgb *= tint;

			vec2 lm = (vec2(vBlk, vSky) + 0.5) / 16.0;
			rgb *= texture(uLightmap, lm).rgb;

			rgb *= vAO;
			rgb *= FACE_SHADE[clamp(vFace, 0, 5)];
		}
	}

	float fog_t = 0.0;
	if (Terrain.uFog.y > Terrain.uFog.x) {
		fog_t = clamp((vFog - Terrain.uFog.x) / (Terrain.uFog.y - Terrain.uFog.x), 0.0, 1.0);
	}
	rgb = mix(rgb, Terrain.uFogColor.rgb, fog_t);

	if ((vFlags & 2) != 0) {
		alpha = max(Terrain.uParams.w, 0.65);
	}
	fragColor = vec4(rgb, alpha);
}
