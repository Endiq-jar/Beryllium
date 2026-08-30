/* rhi_gl.h -- the OpenGL entry-point contract the GL backend is built on.
 *
 * The engine must not need libGL at link time: it builds and runs on machines
 * with no GL, no display and no driver (headless CI), and it must stay testable
 * there. So every GL function the backend uses is a pointer in a loader struct.
 * The production loader dlopen()s libGL and dlsym()s the names -- Mesa's libGL
 * exports the whole core profile, so no extension loader is required for 3.3
 * core; an embedder that creates its context through EGL/WGL supplies its own
 * `get_proc` and hands it in.
 *
 * A test hands in a recording loader instead, which is how the GL command
 * stream gets verified without a GPU: same vertex layout, same 288-byte uniform
 * block, same draw calls, checked symbol by symbol.
 */
#ifndef BERYL_RHI_GL_H
#define BERYL_RHI_GL_H

#include <stddef.h>
#include <stdint.h>
#include <stdbool.h>

/* Types GL prototypes need. <GL/gl.h> is deliberately not included anywhere in
 * this backend, so there is no header version to fight with. */
typedef unsigned int  beryl_gl_enum;
typedef unsigned int  beryl_gl_uint;
typedef int           beryl_gl_int;
typedef unsigned char beryl_gl_boolean;
typedef unsigned int  beryl_gl_bitfield;
typedef ptrdiff_t     beryl_gl_sizeiptr;
typedef void          beryl_gl_void;

/* Subset of the constants used, with the official numeric values so a real
 * driver sees exactly what it expects. */
enum {
	BERYL_GL_FALSE = 0, BERYL_GL_TRUE = 1,
	BERYL_GL_NO = 0, BERYL_GL_YES = 1,
	BERYL_GL_DEPTH_BUFFER_BIT = 0x00000100, BERYL_GL_COLOR_BUFFER_BIT = 0x00004000,
	BERYL_GL_TRIANGLES = 0x0004,
	BERYL_GL_UNSIGNED_INT = 0x1405, BERYL_GL_UNSIGNED_SHORT = 0x1403,
	BERYL_GL_FLOAT = 0x1406, BERYL_GL_HALF_FLOAT = 0x140B,
	BERYL_GL_UNSIGNED_BYTE = 0x1401, BERYL_GL_UNSIGNED_INT_2_10_10_10_REV = 0x8C37,
	BERYL_GL_INT = 0x1404,
	BERYL_GL_RGBA8 = 0x8058, BERYL_GL_RGBA = 0x1908, BERYL_GL_RED = 0x1903,
	BERYL_GL_R8 = 0x8229,
	BERYL_GL_DEPTH_COMPONENT24 = 0x81A6, BERYL_GL_DEPTH_COMPONENT = 0x1902,
	BERYL_GL_TEXTURE_2D = 0x0DE1, BERYL_GL_TEXTURE_2D_ARRAY = 0x8C1A,
	BERYL_GL_TEXTURE0 = 0x84C0,
	BERYL_GL_TEXTURE_MIN_FILTER = 0x2800, BERYL_GL_TEXTURE_MAG_FILTER = 0x2801,
	BERYL_GL_TEXTURE_WRAP_S = 0x2802, BERYL_GL_TEXTURE_WRAP_T = 0x2803,
	BERYL_GL_TEXTURE_WRAP_R = 0x8072,
	BERYL_GL_NEAREST = 0x2600, BERYL_GL_LINEAR = 0x2601,
	BERYL_GL_CLAMP_TO_EDGE = 0x812F, BERYL_GL_REPEAT = 0x2901,
	BERYL_GL_ARRAY_BUFFER = 0x8892, BERYL_GL_ELEMENT_ARRAY_BUFFER = 0x8893,
	BERYL_GL_UNIFORM_BUFFER = 0x8A11, BERYL_GL_COPY_WRITE_BUFFER = 0x8F4F,
	BERYL_GL_STREAM_DRAW = 0x88E0, BERYL_GL_STATIC_DRAW = 0x88E4,
	BERYL_GL_DYNAMIC_DRAW = 0x88E8,
	BERYL_GL_VERTEX_SHADER = 0x8B31, BERYL_GL_FRAGMENT_SHADER = 0x8B30,
	BERYL_GL_COMPILE_STATUS = 0x8B81, BERYL_GL_LINK_STATUS = 0x8B82,
	BERYL_GL_INFO_LOG_LENGTH = 0x8B84,
	BERYL_GL_FRAMEBUFFER = 0x8D40,
	BERYL_GL_RENDERBUFFER = 0x8D41,
	BERYL_GL_COLOR_ATTACHMENT0 = 0x8CE0, BERYL_GL_DEPTH_ATTACHMENT = 0x8D00,
	BERYL_GL_FRAMEBUFFER_COMPLETE = 0x8CD5,
	BERYL_GL_DEPTH_TEST = 0x0B71, BERYL_GL_CULL_FACE = 0x0B44, BERYL_GL_BLEND = 0x0BE2,
	BERYL_GL_BACK = 0x0405, BERYL_GL_FRONT = 0x0404,
	BERYL_GL_CCW = 0x0901, BERYL_GL_CW = 0x0900,
	BERYL_GL_LEQUAL = 0x0203, BERYL_GL_LESS = 0x0201, BERYL_GL_ALWAYS = 0x0207,
	BERYL_GL_SRC_ALPHA = 0x0302, BERYL_GL_ONE_MINUS_SRC_ALPHA = 0x0303, BERYL_GL_ONE = 1,
	BERYL_GL_VERSION = 0x1F02, BERYL_GL_RENDERER = 0x1F01, BERYL_GL_VENDOR = 0x1F00,
	BERYL_GL_SHADING_LANGUAGE_VERSION = 0x8B8C,
	BERYL_GL_MAX_TEXTURE_SIZE = 0x0D33, BERYL_GL_MAX_ARRAY_TEXTURE_LAYERS = 0x88FF,
	BERYL_GL_MAX_COLOR_ATTACHMENTS = 0x8CDF, BERYL_GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS = 0x8B4D,
	BERYL_GL_PIXEL_UNPACK_ALIGNMENT = 0x0CF5,
	BERYL_GL_NO_ERROR = 0,
	BERYL_GL_MAP_WRITE_BIT = 0x0002, BERYL_GL_MAP_PERSISTENT_BIT = 0x0040,
	BERYL_GL_MAP_COHERENT_BIT = 0x0080, BERYL_GL_DYNAMIC_STORAGE_BIT = 0x0200
};

/* The function pointers the backend calls. Signatures match the GL 3.3 core
 * spec; only the 40-odd entry points this engine needs are declared. */
typedef struct BerylGLLoader {
	void *handle;
	/* If set, used instead of dlsym() -- this is the test/embedder hook. */
	void *(*get_proc)(const char *name, void *user);
	void *user;
	const char *missing;            /* first required symbol that was not found */
	bool  ok;
	bool  owns_handle;

	/* --- buffers --- */
	void (*GenBuffers)(beryl_gl_int n, beryl_gl_uint *out);
	void (*DeleteBuffers)(beryl_gl_int n, const beryl_gl_uint *ids);
	void (*BindBuffer)(beryl_gl_enum target, beryl_gl_uint buf);
	void (*BufferData)(beryl_gl_enum target, beryl_gl_sizeiptr size, const void *data, beryl_gl_enum usage);
	void (*BufferSubData)(beryl_gl_enum target, beryl_gl_sizeiptr off, beryl_gl_sizeiptr size, const void *data);
	/* optional (GL 4.4 / ARB_buffer_storage): immutable storage, which removes the
	 * reallocation stall when a section grows between frames */
	void (*BufferStorage)(beryl_gl_enum target, beryl_gl_sizeiptr size, const void *data, beryl_gl_bitfield flags);
	/* --- VAO / attributes --- */
	void (*GenVertexArrays)(beryl_gl_int n, beryl_gl_uint *out);
	void (*DeleteVertexArrays)(beryl_gl_int n, const beryl_gl_uint *ids);
	void (*BindVertexArray)(beryl_gl_uint vao);
	void (*EnableVertexAttribArray)(beryl_gl_uint index);
	void (*VertexAttribPointer)(beryl_gl_uint index, beryl_gl_int size, beryl_gl_enum type,
	                           beryl_gl_boolean norm, beryl_gl_int stride, const void *offset);
	void (*VertexAttribIPointer)(beryl_gl_uint index, beryl_gl_int size, beryl_gl_enum type,
	                            beryl_gl_int stride, const void *offset);
	/* --- UBO --- */
	void (*BindBufferRange)(beryl_gl_enum target, beryl_gl_uint index, beryl_gl_uint buf,
	                        beryl_gl_sizeiptr off, beryl_gl_sizeiptr size);
	beryl_gl_int (*GetUniformBlockIndex)(beryl_gl_uint program, const char *name);
	void (*UniformBlockBinding)(beryl_gl_uint program, beryl_gl_uint index, beryl_gl_uint binding);
	/* --- textures --- */
	void (*GenTextures)(beryl_gl_int n, beryl_gl_uint *out);
	void (*DeleteTextures)(beryl_gl_int n, const beryl_gl_uint *ids);
	void (*BindTexture)(beryl_gl_enum target, beryl_gl_uint tex);
	void (*ActiveTexture)(beryl_gl_enum unit);
	void (*TexImage3D)(beryl_gl_enum target, beryl_gl_int level, beryl_gl_int internal,
	                   beryl_gl_int w, beryl_gl_int h, beryl_gl_int d, beryl_gl_int border,
	                   beryl_gl_enum fmt, beryl_gl_enum type, const void *data);
	void (*TexSubImage3D)(beryl_gl_enum target, beryl_gl_int level, beryl_gl_int xo, beryl_gl_int yo,
	                      beryl_gl_int zo, beryl_gl_int w, beryl_gl_int h, beryl_gl_int d,
	                      beryl_gl_enum fmt, beryl_gl_enum type, const void *data);
	void (*TexImage2D)(beryl_gl_enum target, beryl_gl_int level, beryl_gl_int internal,
	                   beryl_gl_int w, beryl_gl_int h, beryl_gl_int border, beryl_gl_enum fmt,
	                   beryl_gl_enum type, const void *data);
	void (*PixelStorei)(beryl_gl_enum pname, beryl_gl_int param);
	void (*TexParameteri)(beryl_gl_enum target, beryl_gl_enum pname, beryl_gl_int param);
	/* --- shaders --- */
	beryl_gl_uint (*CreateShader)(beryl_gl_enum type);
	void (*ShaderSource)(beryl_gl_uint sh, beryl_gl_int count, const char *const *str, const beryl_gl_int *len);
	void (*CompileShader)(beryl_gl_uint sh);
	void (*GetShaderiv)(beryl_gl_uint sh, beryl_gl_enum pname, beryl_gl_int *params);
	void (*GetShaderInfoLog)(beryl_gl_uint sh, beryl_gl_int max, beryl_gl_int *len, char *log);
	beryl_gl_uint (*CreateProgram)(void);
	void (*AttachShader)(beryl_gl_uint prog, beryl_gl_uint sh);
	void (*LinkProgram)(beryl_gl_uint prog);
	void (*UseProgram)(beryl_gl_uint prog);
	void (*GetProgramiv)(beryl_gl_uint prog, beryl_gl_enum pname, beryl_gl_int *params);
	void (*GetProgramInfoLog)(beryl_gl_uint prog, beryl_gl_int max, beryl_gl_int *len, char *log);
	beryl_gl_int (*GetUniformLocation)(beryl_gl_uint prog, const char *name);
	void (*Uniform1i)(beryl_gl_int loc, beryl_gl_int v);
	beryl_gl_int (*GetAttribLocation)(beryl_gl_uint prog, const char *name);
	void (*DeleteShader)(beryl_gl_uint sh);
	void (*DeleteProgram)(beryl_gl_uint prog);
	/* --- framebuffer objects (offscreen capture, the headless path) --- */
	void (*GenFramebuffers)(beryl_gl_int n, beryl_gl_uint *out);
	void (*DeleteFramebuffers)(beryl_gl_int n, const beryl_gl_uint *ids);
	void (*BindFramebuffer)(beryl_gl_enum target, beryl_gl_uint fb);
	void (*FramebufferTexture)(beryl_gl_enum target, beryl_gl_enum attachment, beryl_gl_uint tex, beryl_gl_int level);
	beryl_gl_enum (*CheckFramebufferStatus)(beryl_gl_enum target);
	void (*GenRenderbuffers)(beryl_gl_int n, beryl_gl_uint *out);
	void (*DeleteRenderbuffers)(beryl_gl_int n, const beryl_gl_uint *ids);
	void (*BindRenderbuffer)(beryl_gl_enum target, beryl_gl_uint rb);
	void (*RenderbufferStorage)(beryl_gl_enum target, beryl_gl_enum internal, beryl_gl_int w, beryl_gl_int h);
	void (*FramebufferRenderbuffer)(beryl_gl_enum target, beryl_gl_enum attachment, beryl_gl_enum fmt, beryl_gl_uint rb);
	/* --- state + draw --- */
	void (*Enable)(beryl_gl_enum cap);
	void (*Disable)(beryl_gl_enum cap);
	void (*DepthFunc)(beryl_gl_enum fn);
	void (*DepthMask)(beryl_gl_boolean flag);
	void (*CullFace)(beryl_gl_enum mode);
	void (*FrontFace)(beryl_gl_enum mode);
	void (*BlendFuncSeparate)(beryl_gl_enum s, beryl_gl_enum d, beryl_gl_enum sa, beryl_gl_enum da);
	void (*ClearColor)(float r, float g, float b, float a);
	void (*ClearDepth)(double d);
	void (*Clear)(beryl_gl_enum mask);
	void (*Viewport)(beryl_gl_int x, beryl_gl_int y, beryl_gl_int w, beryl_gl_int h);
	void (*DrawElements)(beryl_gl_enum mode, beryl_gl_int count, beryl_gl_enum type, const void *offset);
	void (*ReadPixels)(beryl_gl_int x, beryl_gl_int y, beryl_gl_int w, beryl_gl_int h,
	                   beryl_gl_enum fmt, beryl_gl_enum type, void *pixels);
	const unsigned char *(*GetString)(beryl_gl_enum name);
	void (*GetIntegererv)(beryl_gl_enum pname, beryl_gl_int *params);
	beryl_gl_enum (*GetError)(void);
	void (*Flush)(void);
} BerylGLLoader;

/* Production loader: dlopen("libGL.so.1"...) then dlsym each name. `missing` is
 * set when a required symbol is absent. Returns false if libGL is not present
 * at all, which is the normal outcome in a headless container. */
bool beryl_gl_loader_default(BerylGLLoader *l);
/* Fills the loader from l->get_proc (or dlsym when no handle is given). */
bool beryl_gl_loader_resolve(BerylGLLoader *l);
/* A GL RHI built on the given loader. width/height size the offscreen target.
 * `platform_hint` may be NULL: the backend never creates a context itself --
 * it records and issues commands against whatever context is current, which is
 * also what makes the mock loader possible. */
struct BerylRhi;
struct BerylRhi *beryl_rhi_new_gl(int width, int height, BerylGLLoader *loader);
/* Name of the i-th entry point the loader needs (for tests + diagnostics). */
int  beryl_gl_loader_entry_count(void);
const char *beryl_gl_loader_entry_name(int i);
/* The GLSL source the backend compiles, shared with the software rasterizer's
 * behaviour. Exposed so the test can assert the two never drift apart. */
const char *beryl_gl_vertex_source(void);
const char *beryl_gl_fragment_source(void);

#endif /* BERYL_RHI_GL_H */
