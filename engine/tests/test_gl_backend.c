/* test_gl_backend.c -- the OpenGL backend, exercised without a GPU.
 *
 * There is no GL driver in the container this engine was written in, and "it
 * compiles" is not a sufficient claim for a backend. So the backend's entire
 * command stream is checked here instead: the mock loader implements the 60-odd
 * entry points with the real prototypes, records every call by name, and the
 * assertions are about the GL that would have reached a driver -- targets,
 * sizes, usage hints, texture dimensionalities, shader sources, attribute
 * layouts, state transitions, draw counts.
 *
 * What this proves: the GL path issues the right commands with the right
 * arguments, given the same mesh bytes the software backend rasterizes.
 * What it cannot prove: that a driver produces the same image. That needs a GPU;
 * README states this plainly.
 */
#include "test.h"
#include "rhi.h"
#include "rhi_gl.h"
#include "mesh_format.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#if defined(__unix__) || defined(__APPLE__)
#include <dlfcn.h>
#endif

#if !BERYL_WITH_OPENGL
void test_gl_backend(void) { CHECK(1, "this build has no OpenGL backend to test"); }
#else

/* ------------------------------------------------------------ the recorder -- */
typedef struct {
	const char *name;
	int         n;
	uintptr_t   a0, a1, a2;
	size_t      size;
	const char *str;             /* captured pointer, valid for the test */
} MCall;

#define M_MAX_SLOTS 128
typedef struct {
	MCall   call[M_MAX_SLOTS];
	int     n;
	int     fail;              /* make a named entry point "missing" */
	const char *fail_name;
	uint8_t *pixels;           /* what ReadPixels will write         */
	int     px_w, px_h;
	/* Aggregating one slot per name cannot answer "what did the fifth call look
	 * like", so the few things that must be checked per-argument get their own
	 * capture space here, exactly where a driver would notice them. */
	struct { int loc, size, type; size_t offset; } attr[8];
	int     attr_n;
	int     enables[24], disables[24], enable_n, disable_n;
	int     texparams[24][2], texparam_n;
	struct { beryl_gl_uint id; int type; } shader[8];
	int     shader_n;
	char    src_vs[12288], src_fs[12288];
	size_t  ubo_size;
	int     ubo_uploads;
	int     draw_total;
} Mock;

static Mock g;

static MCall *rec(const char *name) {
	for (int i = 0; i < g.n; i++)
		if (strcmp(g.call[i].name, name) == 0) return &g.call[i];
	if (g.n >= M_MAX_SLOTS) return &g.call[M_MAX_SLOTS - 1];
	MCall *c = &g.call[g.n++];
	memset(c, 0, sizeof(*c));
	c->name = name;            /* string literals live forever */
	return c;
}
static int calls(const char *name) {
	for (int i = 0; i < g.n; i++)
		if (strcmp(g.call[i].name, name) == 0) return g.call[i].n;
	return 0;
}
static MCall *last(const char *name) {
	for (int i = 0; i < g.n; i++)
		if (strcmp(g.call[i].name, name) == 0) return &g.call[i];
	{
		static MCall none;
		memset(&none, 0, sizeof(none));
		none.name = name;
		return &none;          /* never NULL: the CHECKs then report a count of 0 */
	}
}

/* Stubs. Each has the exact prototype of the entry point it replaces, so the
 * backend is called through its real function-pointer types. */
static void m_GenBuffers(beryl_gl_int n, beryl_gl_uint *o) {
	MCall *c = rec("glGenBuffers"); c->n++; c->a0 = (uintptr_t)n;
	for (int i = 0; i < n; i++) o[i] = (beryl_gl_uint)(1000 + g.n * 4 + i);
}
static void m_DeleteBuffers(beryl_gl_int n, const beryl_gl_uint *id) { (void)id; MCall *c = rec("glDeleteBuffers"); c->n++; c->a0 = (uintptr_t)n; }
static void m_BindBuffer(beryl_gl_enum t, beryl_gl_uint id) { MCall *c = rec("glBindBuffer"); c->n++; c->a0 = t; c->a1 = id; }
static void m_BufferData(beryl_gl_enum t, beryl_gl_sizeiptr size, const void *d, beryl_gl_enum usage) {
	MCall *c = rec("glBufferData"); c->n++; c->a0 = t; c->a1 = usage; c->size = (size_t)size; c->str = (const char *)d;
	if (t == BERYL_GL_UNIFORM_BUFFER) { g.ubo_size = (size_t)size; g.ubo_uploads++; }
}
static void m_BufferSubData(beryl_gl_enum t, beryl_gl_sizeiptr off, beryl_gl_sizeiptr size, const void *d) {
	MCall *c = rec("glBufferSubData"); c->n++; c->a0 = t; c->a1 = (uintptr_t)off; c->size = (size_t)size; c->str = (const char *)d;
	if (t == BERYL_GL_UNIFORM_BUFFER) { g.ubo_size = (size_t)size; g.ubo_uploads++; }
}
static void m_BufferStorage(beryl_gl_enum t, beryl_gl_sizeiptr size, const void *d, beryl_gl_bitfield f) {
	MCall *c = rec("glBufferStorage"); c->n++; c->a0 = t; c->a1 = f; c->size = (size_t)size; c->str = (const char *)d;
}
static void m_GenVertexArrays(beryl_gl_int n, beryl_gl_uint *o) { rec("glGenVertexArrays")->n++; o[0] = 77u; (void)n; }
static void m_DeleteVertexArrays(beryl_gl_int n, const beryl_gl_uint *i) { (void)n; (void)i; rec("glDeleteVertexArrays")->n++; }
static void m_BindVertexArray(beryl_gl_uint v) { MCall *c = rec("glBindVertexArray"); c->n++; c->a0 = v; }
static void m_EnableVertexAttribArray(beryl_gl_uint i) { MCall *c = rec("glEnableVertexAttribArray"); c->n++; c->a0 = i; }
static void m_VertexAttribPointer(beryl_gl_uint loc, beryl_gl_int sz, beryl_gl_enum t, beryl_gl_boolean norm,
                                beryl_gl_int stride, const void *ofs) {
	MCall *c = rec("glVertexAttribPointer"); c->n++; c->a0 = loc; c->a1 = (uintptr_t)sz; c->a2 = t;
	c->size = (size_t)(uintptr_t)ofs;
	rec("glVertexAttrib.stride")->n = stride;
	rec("glVertexAttrib.norm")->n = norm;
}
static void m_VertexAttribIPointer(beryl_gl_uint loc, beryl_gl_int sz, beryl_gl_enum t, beryl_gl_int stride,
                                 const void *ofs) {
	MCall *c = rec("glVertexAttribIPointer"); c->n++; c->a0 = loc; c->a1 = (uintptr_t)sz; c->a2 = t;
	c->size = (size_t)(uintptr_t)ofs;                       /* the byte offset */
	rec("glVertexAttribI.stride")->n = stride;
	if (loc < sizeof(g.attr) / sizeof(g.attr[0])) {
		g.attr[loc].loc = (int)loc;
		g.attr[loc].size = sz;
		g.attr[loc].type = (int)t;
		g.attr[loc].offset = (size_t)(uintptr_t)ofs;
		if ((int)loc >= g.attr_n) g.attr_n = (int)loc + 1;
	}
}
static void m_BindBufferRange(beryl_gl_enum t, beryl_gl_uint i, beryl_gl_uint b, beryl_gl_sizeiptr off, beryl_gl_sizeiptr size) {
	MCall *c = rec("glBindBufferRange"); c->n++; c->a0 = t; c->a1 = i; c->size = (size_t)size; c->a2 = (uintptr_t)off;
}
static beryl_gl_int m_GetUniformBlockIndex(beryl_gl_uint p, const char *n) {
	MCall *c = rec("glGetUniformBlockIndex"); c->n++; c->str = n; (void)p;
	return 0;
}
static void m_UniformBlockBinding(beryl_gl_uint p, beryl_gl_uint i, beryl_gl_uint b) {
	MCall *c = rec("glUniformBlockBinding"); c->n++; c->a0 = i; c->a1 = b; (void)p;
}
static void m_GenTextures(beryl_gl_int n, beryl_gl_uint *o) { rec("glGenTextures")->n++; for (int i = 0; i < n; i++) o[i] = (beryl_gl_uint)(2000 + i + g.n); }
static void m_DeleteTextures(beryl_gl_int n, const beryl_gl_uint *i) { (void)n; (void)i; rec("glDeleteTextures")->n++; }
static void m_BindTexture(beryl_gl_enum t, beryl_gl_uint id) { MCall *c = rec("glBindTexture"); c->n++; c->a0 = t; c->a1 = id; }
static void m_ActiveTexture(beryl_gl_enum u) { MCall *c = rec("glActiveTexture"); c->n++; c->a0 = u; }
static void m_TexImage3D(beryl_gl_enum t, beryl_gl_int lvl, beryl_gl_int internal, beryl_gl_int w, beryl_gl_int h,
                       beryl_gl_int d, beryl_gl_int border, beryl_gl_enum f, beryl_gl_enum ty, const void *px) {
	MCall *c = rec("glTexImage3D"); c->n++;
	c->a0 = t; c->a1 = (uintptr_t)d; c->a2 = (uintptr_t)w; c->size = (size_t)h;
	rec("glTexImage3D.internal")->n = internal;
	rec("glTexImage3D.border")->n = border;
	rec("glTexImage3D.type")->n = ty;
	(void)lvl; (void)f; (void)px;
}
static void m_TexImage2D(beryl_gl_enum t, beryl_gl_int lvl, beryl_gl_int internal, beryl_gl_int w, beryl_gl_int h,
                       beryl_gl_int border, beryl_gl_enum f, beryl_gl_enum ty, const void *px) {
	MCall *c = rec("glTexImage2D"); c->n++;
	c->a0 = t; c->a1 = (uintptr_t)w; c->a2 = (uintptr_t)h; c->size = (size_t)internal;
	(void)lvl; (void)border; (void)f; (void)ty; (void)px;
}
static void m_TexSubImage3D(beryl_gl_enum t, beryl_gl_int lvl, beryl_gl_int xo, beryl_gl_int yo, beryl_gl_int zo,
                         beryl_gl_int w, beryl_gl_int h, beryl_gl_int d, beryl_gl_enum f, beryl_gl_enum ty, const void *px) {
	MCall *c = rec("glTexSubImage3D"); c->n++;
	c->a0 = t; c->a1 = (uintptr_t)zo;          /* the layer being written */
	c->a2 = (uintptr_t)w; c->size = (size_t)h;
	rec("glTexSubImage3D.d")->n = d;
	rec("glTexSubImage3D.type")->n = ty;
	(void)xo; (void)yo; (void)lvl; (void)f; (void)px;
}
static void m_TexParameteri(beryl_gl_enum t, beryl_gl_enum p, beryl_gl_int v) {
	MCall *c = rec("glTexParameteri"); c->n++; c->a0 = p; c->a1 = (uintptr_t)v; (void)t;
	if (g.texparam_n < (int)(sizeof g.texparams / sizeof g.texparams[0])) {
		g.texparams[g.texparam_n][0] = (int)p;
		g.texparams[g.texparam_n][1] = (int)v;
		g.texparam_n++;
	}
}
static void m_PixelStorei(beryl_gl_enum p, beryl_gl_int v) { MCall *c = rec("glPixelStorei"); c->n++; c->a0 = p; c->a1 = (uintptr_t)v; }
static beryl_gl_uint m_CreateShader(beryl_gl_enum t) {
	MCall *c = rec("glCreateShader"); c->n++; c->a0 = t;
	beryl_gl_uint id = (beryl_gl_uint)(3000 + g.shader_n);
	if (g.shader_n < (int)(sizeof g.shader / sizeof g.shader[0])) {
		g.shader[g.shader_n].id = id;
		g.shader[g.shader_n].type = (int)t;
		g.shader_n++;
	}
	return id;
}
static void m_ShaderSource(beryl_gl_uint sh, beryl_gl_int n, const char *const *src, const beryl_gl_int *len) {
	MCall *c = rec("glShaderSource"); c->n++; c->a0 = sh; c->a1 = (uintptr_t)n;
	c->str = (n > 0 && src) ? src[0] : NULL;
	(void)len;
	if (!c->str) return;
	/* The backend frees its source buffer as soon as the call returns, so the
	 * stub has to take a copy -- which is also what lets us check each stage
	 * separately instead of only the last one. */
	int type = 0;
	for (int i = 0; i < g.shader_n; i++)
		if (g.shader[i].id == sh) type = g.shader[i].type;
	char *dst = (type == BERYL_GL_FRAGMENT_SHADER) ? g.src_fs : g.src_vs;
	size_t cap = (type == BERYL_GL_FRAGMENT_SHADER) ? sizeof(g.src_fs) : sizeof(g.src_vs);
	snprintf(dst, cap, "%s", c->str);
}
static void m_CompileShader(beryl_gl_uint s) { MCall *c = rec("glCompileShader"); c->n++; c->a0 = s; }
static void m_GetShaderiv(beryl_gl_uint s, beryl_gl_enum p, beryl_gl_int *out) {
	rec("glGetShaderiv")->n++;
	if (p == BERYL_GL_COMPILE_STATUS) *out = 1;
	else *out = 0;
	(void)s;
}
static void m_GetShaderInfoLog(beryl_gl_uint s, beryl_gl_int max, beryl_gl_int *len, char *log) {
	rec("glGetShaderInfoLog")->n++;
	if (max > 0) log[0] = 0;
	if (len) *len = 0;
	(void)s;
}
static beryl_gl_uint m_CreateProgram(void) { MCall *c = rec("glCreateProgram"); c->n++; return (beryl_gl_uint)(4000 + g.n); }
static void m_AttachShader(beryl_gl_uint p, beryl_gl_uint s) { MCall *c = rec("glAttachShader"); c->n++; c->a0 = p; c->a1 = s; }
static void m_LinkProgram(beryl_gl_uint p) { MCall *c = rec("glLinkProgram"); c->n++; c->a0 = p; }
static void m_UseProgram(beryl_gl_uint p) { MCall *c = rec("glUseProgram"); c->n++; c->a0 = p; }
static void m_GetProgramiv(beryl_gl_uint p, beryl_gl_enum what, beryl_gl_int *out) {
	rec("glGetProgramiv")->n++;
	if (what == BERYL_GL_LINK_STATUS) *out = 1;
	else *out = 0;
	(void)p;
}
static void m_GetProgramInfoLog(beryl_gl_uint p, beryl_gl_int max, beryl_gl_int *len, char *log) {
	rec("glGetProgramInfoLog")->n++;
	if (max > 0) log[0] = 0;
	if (len) *len = 0;
	(void)p;
}
static beryl_gl_int m_GetUniformLocation(beryl_gl_uint p, const char *n) {
	MCall *c = rec("glGetUniformLocation"); c->n++; c->str = n; (void)p;
	if (n && strcmp(n, "uTexArray") == 0) return 3;
	if (n && strcmp(n, "uLightmap") == 0) return 4;
	return -1;
}
static void m_Uniform1i(beryl_gl_int loc, beryl_gl_int v) {
	MCall *c = rec("glUniform1i"); c->n++; c->a0 = (uintptr_t)loc; c->a1 = (uintptr_t)v;
}
static beryl_gl_int m_GetAttribLocation(beryl_gl_uint p, const char *n) {
	MCall *c = rec("glGetAttribLocation"); c->n++; c->str = n; (void)p;
	if (!n) return -1;
	if (!strcmp(n, "aPosXY")) return 0;
	if (!strcmp(n, "aPosZ")) return 1;
	if (!strcmp(n, "aUV")) return 2;
	if (!strcmp(n, "aPack0")) return 3;
	if (!strcmp(n, "aPack1")) return 4;
	return -1;
}
static void m_DeleteShader(beryl_gl_uint s) { MCall *c = rec("glDeleteShader"); c->n++; c->a0 = s; }
static void m_DeleteProgram(beryl_gl_uint p) { MCall *c = rec("glDeleteProgram"); c->n++; c->a0 = p; }
static void m_GenFramebuffers(beryl_gl_int n, beryl_gl_uint *o) { rec("glGenFramebuffers")->n++; (void)n; if (o) o[0] = 9001u; }
static void m_DeleteFramebuffers(beryl_gl_int n, const beryl_gl_uint *i) { (void)n; (void)i; rec("glDeleteFramebuffers")->n++; }
static void m_BindFramebuffer(beryl_gl_enum t, beryl_gl_uint f) { MCall *c = rec("glBindFramebuffer"); c->n++; c->a0 = t; c->a1 = f; }
static void m_FramebufferTexture(beryl_gl_enum t, beryl_gl_enum a, beryl_gl_uint tex, beryl_gl_int lvl) {
	MCall *c = rec("glFramebufferTexture"); c->n++; c->a0 = a; c->a1 = tex; (void)t; (void)lvl;
}
static beryl_gl_enum m_CheckFramebufferStatus(beryl_gl_enum t) { rec("glCheckFramebufferStatus")->n++; (void)t; return BERYL_GL_FRAMEBUFFER_COMPLETE; }
static void m_GenRenderbuffers(beryl_gl_int n, beryl_gl_uint *o) { rec("glGenRenderbuffers")->n++; (void)n; if (o) o[0] = 9002u; }
static void m_DeleteRenderbuffers(beryl_gl_int n, const beryl_gl_uint *i) { (void)n; (void)i; rec("glDeleteRenderbuffers")->n++; }
static void m_BindRenderbuffer(beryl_gl_enum t, beryl_gl_uint r) { MCall *c = rec("glBindRenderbuffer"); c->n++; c->a0 = t; c->a1 = r; }
static void m_RenderbufferStorage(beryl_gl_enum t, beryl_gl_enum internal, beryl_gl_int w, beryl_gl_int h) {
	MCall *c = rec("glRenderbufferStorage"); c->n++; c->a0 = internal; c->a1 = (uintptr_t)w; c->a2 = (uintptr_t)h; (void)t;
}
static void m_FramebufferRenderbuffer(beryl_gl_enum t, beryl_gl_enum a, beryl_gl_enum f, beryl_gl_uint r) {
	MCall *c = rec("glFramebufferRenderbuffer"); c->n++; c->a0 = a; c->a1 = r; (void)t; (void)f;
}
static void m_Enable(beryl_gl_enum cap) {
	MCall *c = rec("glEnable"); c->n++; c->a0 = cap;
	if (g.enable_n < (int)(sizeof g.enables / sizeof g.enables[0])) g.enables[g.enable_n++] = (int)cap;
}
static void m_Disable(beryl_gl_enum cap) {
	MCall *c = rec("glDisable"); c->n++; c->a0 = cap;
	if (g.disable_n < (int)(sizeof g.disables / sizeof g.disables[0])) g.disables[g.disable_n++] = (int)cap;
}
static int was_enabled(int cap) {
	for (int i = 0; i < g.enable_n; i++) if (g.enables[i] == cap) return 1;
	return 0;
}
static int was_disabled(int cap) {
	for (int i = 0; i < g.disable_n; i++) if (g.disables[i] == cap) return 1;
	return 0;
}
static int texparam_value(int pname) {
	for (int i = g.texparam_n - 1; i >= 0; i--) if (g.texparams[i][0] == pname) return g.texparams[i][1];
	return -1;
}
static void m_DepthFunc(beryl_gl_enum f) { MCall *c = rec("glDepthFunc"); c->n++; c->a0 = f; }
static void m_DepthMask(beryl_gl_boolean v) { MCall *c = rec("glDepthMask"); c->n++; c->a0 = v; }
static void m_CullFace(beryl_gl_enum m) { MCall *c = rec("glCullFace"); c->n++; c->a0 = m; }
static void m_FrontFace(beryl_gl_enum m) { MCall *c = rec("glFrontFace"); c->n++; c->a0 = m; }
static void m_BlendFuncSeparate(beryl_gl_enum s, beryl_gl_enum d, beryl_gl_enum sa, beryl_gl_enum da) {
	MCall *c = rec("glBlendFuncSeparate"); c->n++; c->a0 = s; c->a1 = d; c->a2 = sa; (void)da;
}
static void m_ClearColor(float r, float gr, float b, float a) {
	MCall *c = rec("glClearColor"); c->n++;
	c->a0 = (uintptr_t)(r * 1000.0f); c->a1 = (uintptr_t)(gr * 1000.0f);
	c->a2 = (uintptr_t)(b * 1000.0f); c->size = (size_t)(a * 1000.0f);
}
static void m_ClearDepth(double d) { MCall *c = rec("glClearDepth"); c->n++; c->a0 = (uintptr_t)(d * 1000.0); }
static void m_Clear(beryl_gl_enum m) { MCall *c = rec("glClear"); c->n++; c->a0 = m; }
static void m_Viewport(beryl_gl_int x, beryl_gl_int y, beryl_gl_int w, beryl_gl_int h) {
	MCall *c = rec("glViewport"); c->n++; c->a0 = (uintptr_t)w; c->a1 = (uintptr_t)h; (void)x; (void)y;
}
static void m_DrawElements(beryl_gl_enum mode, beryl_gl_int count, beryl_gl_enum type, const void *ofs) {
	MCall *c = rec("glDrawElements"); c->n++; g.draw_total++;
	c->a0 = mode; c->a1 = (uintptr_t)count; c->a2 = type; c->size = (size_t)(uintptr_t)ofs;
}
static void m_ReadPixels(beryl_gl_int x, beryl_gl_int y, beryl_gl_int w, beryl_gl_int h, beryl_gl_enum f,
                       beryl_gl_enum t, void *dst) {
	MCall *c = rec("glReadPixels"); c->n++; c->a0 = (uintptr_t)w; c->a1 = (uintptr_t)h; (void)f; (void)t;
	(void)x; (void)y;
	if (g.pixels && dst && w == g.px_w && h == g.px_h)
		memcpy(dst, g.pixels, (size_t)w * (size_t)h * 4u);
}
static const unsigned char *m_GetString(beryl_gl_enum n) {
	rec("glGetString")->n++;
	if (n == BERYL_GL_RENDERER) return (const unsigned char *)"Mock GL Renderer";
	if (n == BERYL_GL_VERSION) return (const unsigned char *)"3.3.0 mock";
	return (const unsigned char *)"GLSL 3.30 mock";
}
static void m_GetIntegererv(beryl_gl_enum p, beryl_gl_int *v) {
	rec("glGetIntegererv")->n++;
	if (p == BERYL_GL_MAX_TEXTURE_SIZE) *v = 16384;
	else if (p == BERYL_GL_MAX_ARRAY_TEXTURE_LAYERS) *v = 2048;
	else *v = 0;
}
static beryl_gl_enum m_GetError(void) { rec("glGetError")->n++; return BERYL_GL_NO_ERROR; }
static void m_Flush(void) { rec("glFlush")->n++; }

/* The lookup hook: hands the backend exactly the stubs above, by name. */
static void *mock_get_proc(const char *name, void *user) {
	Mock *m = (Mock *)user;
	(void)m;
	if (g.fail_name && !strcmp(name, g.fail_name)) return NULL;
#define M_MAP(fn) if (!strcmp(name, "gl" #fn)) return (void *)m_##fn;
	M_MAP(GenBuffers) M_MAP(DeleteBuffers) M_MAP(BindBuffer) M_MAP(BufferData) M_MAP(BufferSubData)
	M_MAP(BufferStorage) M_MAP(GenVertexArrays) M_MAP(DeleteVertexArrays) M_MAP(BindVertexArray)
	M_MAP(EnableVertexAttribArray) M_MAP(VertexAttribPointer) M_MAP(VertexAttribIPointer)
	M_MAP(BindBufferRange) M_MAP(GetUniformBlockIndex) M_MAP(UniformBlockBinding)
	M_MAP(GenTextures) M_MAP(DeleteTextures) M_MAP(BindTexture) M_MAP(ActiveTexture)
	M_MAP(TexImage3D) M_MAP(TexImage2D) M_MAP(TexSubImage3D) M_MAP(TexParameteri) M_MAP(PixelStorei)
	M_MAP(CreateShader) M_MAP(ShaderSource) M_MAP(CompileShader) M_MAP(GetShaderiv) M_MAP(GetShaderInfoLog)
	M_MAP(CreateProgram) M_MAP(AttachShader) M_MAP(LinkProgram) M_MAP(UseProgram) M_MAP(GetProgramiv)
	M_MAP(GetProgramInfoLog) M_MAP(GetUniformLocation) M_MAP(Uniform1i) M_MAP(GetAttribLocation)
	M_MAP(DeleteShader) M_MAP(DeleteProgram) M_MAP(GenFramebuffers) M_MAP(DeleteFramebuffers)
	M_MAP(BindFramebuffer) M_MAP(FramebufferTexture) M_MAP(CheckFramebufferStatus)
	M_MAP(GenRenderbuffers) M_MAP(DeleteRenderbuffers) M_MAP(BindRenderbuffer)
	M_MAP(RenderbufferStorage) M_MAP(FramebufferRenderbuffer)
	M_MAP(Enable) M_MAP(Disable) M_MAP(DepthFunc) M_MAP(DepthMask) M_MAP(CullFace) M_MAP(FrontFace)
	M_MAP(BlendFuncSeparate) M_MAP(ClearColor) M_MAP(ClearDepth) M_MAP(Clear) M_MAP(Viewport)
	M_MAP(DrawElements) M_MAP(ReadPixels) M_MAP(GetString) M_MAP(GetIntegererv) M_MAP(GetError) M_MAP(Flush)
#undef M_MAP
	return NULL;
}

/* ------------------------------------------------------------------ tests --- */
static void test_gl_loader_contract(void) {
	BerylGLLoader l;
	memset(&l, 0, sizeof(l));
	l.get_proc = mock_get_proc;
	l.user = &g;
	CHECK(beryl_gl_loader_resolve(&l), "a full loader must resolve");
	CHECK(l.ok, "and set the ok flag");
	CHECK(beryl_gl_loader_entry_count() >= 50, "the loader must cover the whole contract (%d entry points)",
	      beryl_gl_loader_entry_count());
	int bad = 0;
	for (int i = 0; i < beryl_gl_loader_entry_count(); i++)
		if (strncmp(beryl_gl_loader_entry_name(i), "gl", 2) != 0) bad++;
	CHECK(bad == 0, "every entry point name must be a GL symbol (%d not)", bad);
	/* The stubs above must cover every required name, or this file is lying. */
	int unbacked = 0;
	for (int i = 0; i < beryl_gl_loader_entry_count(); i++) {
		const char *nm = beryl_gl_loader_entry_name(i);
		void *p = mock_get_proc(nm, &g);
		if (!p && strcmp(nm, "glBufferStorage") != 0) unbacked++;
	}
	CHECK(unbacked == 0, "%d required entry points have no mock (add a stub!)", unbacked);
	(void)l;

	BerylGLLoader partial;
	memset(&partial, 0, sizeof(partial));
	partial.get_proc = mock_get_proc;
	partial.user = &g;
	g.fail_name = "glDrawElements";
	CHECK(!beryl_gl_loader_resolve(&partial), "a missing required entry point must fail resolution");
	CHECK(partial.missing && strcmp(partial.missing, "glDrawElements") == 0,
	      "and name it (got %s)", partial.missing ? partial.missing : "(null)");
	g.fail_name = NULL;

	/* The real libGL probe: reported honestly as unavailable in a container. */
	BerylGLLoader real;
	if (beryl_gl_loader_default(&real)) {
#if defined(__unix__) || defined(__APPLE__)
		if (real.handle) dlclose(real.handle);
#endif
		CHECK(real.ok, "if libGL loaded it must be fully resolved");
	} else {
		char why[128] = { 0 };
		CHECK(!beryl_backend_available(BERYL_BACKEND_OPENGL, why, sizeof(why)),
		      "with no libGL the availability probe must say so");
		CHECK(strstr(why, "libGL") != NULL, "and name the missing library (%s)", why);
	}
}

static BerylRhi *make_rhi(int w, int h, BerylGLLoader *l) {
	memset(l, 0, sizeof(*l));
	l->get_proc = mock_get_proc;
	l->user = &g;
	BERYL_ASSERT(beryl_gl_loader_resolve(l), "loader");
	return beryl_rhi_new_gl(w, h, l);
}

static void test_gl_resources(void) {
	memset(&g, 0, sizeof(g));
	BerylGLLoader l;
	BerylRhi *r = make_rhi(160, 90, &l);
	CHECK(r != NULL, "the device must be created against the mock loader");
	if (!r) return;
	CHECK(strcmp(r->vt->name, "opengl") == 0, "the vtable must identify itself (%s)", r->vt->name);
	CHECK(calls("glGenVertexArrays") == 1, "one VAO for the engine (%d)", calls("glGenVertexArrays"));

	/* Static geometry buffer: ARRAY_BUFFER + STATIC_DRAW + the exact size. */
	static uint8_t payload[4096];
	for (size_t i = 0; i < sizeof(payload); i++) payload[i] = (uint8_t)i;
	BerylBufferDesc bd = { .size = 2048, .dynamic = false, .initial = payload, .debug_name = "verts" };
	BerylBuffer vb = BERYL_HANDLE_NONE;
	CHECK(r->vt->create_buffer(r, &bd, &vb) == BERYL_OK && vb != BERYL_HANDLE_NONE, "a static buffer must be created");
	MCall *bd_call = last("glBufferData");
	CHECK(bd_call->n == 1, "one glBufferData for it (%d)", bd_call->n);
	CHECK(bd_call->size == 2048, "with the requested size (%zu)", bd_call->size);
	CHECK(bd_call->a0 == BERYL_GL_ARRAY_BUFFER, "bound as ARRAY_BUFFER (%u)", (unsigned)bd_call->a0);
	CHECK(bd_call->a1 == BERYL_GL_STATIC_DRAW, "with STATIC_DRAW usage (%u)", (unsigned)bd_call->a1);

	/* Indices: same target, but they are re-bound as ELEMENT_ARRAY_BUFFER later. */
	BerylBuffer ib = BERYL_HANDLE_NONE;
	BerylBufferDesc id_desc = { .size = 1024, .dynamic = false, .initial = payload, .debug_name = "idx" };
	CHECK(r->vt->create_buffer(r, &id_desc, &ib) == BERYL_OK && ib != vb, "an index buffer must be created");

	/* A dynamic buffer may use immutable storage when the driver has it. */
	BerylBuffer dyn = BERYL_HANDLE_NONE;
	BerylBufferDesc dd = { .size = 288, .dynamic = true, .initial = payload, .debug_name = "ubo" };
	CHECK(r->vt->create_buffer(r, &dd, &dyn) == BERYL_OK, "a dynamic buffer must be created");
	if (l.BufferStorage) {
		MCall *bs = last("glBufferStorage");
		CHECK(bs->n == 1, "and go through BufferStorage (%d)", bs->n);
		CHECK(bs->a0 == BERYL_GL_COPY_WRITE_BUFFER, "on COPY_WRITE (%u)", (unsigned)bs->a0);
		CHECK((bs->a1 & BERYL_GL_MAP_WRITE_BIT) != 0, "with MAP_WRITE");
		CHECK((bs->a1 & BERYL_GL_DYNAMIC_STORAGE_BIT) != 0, "and DYNAMIC_STORAGE");
	}

	/* Uploads, and the bounds a caller must not be able to violate. */
	CHECK(r->vt->upload_buffer(r, vb, payload, 64, 0) == BERYL_OK, "an in-range upload must work");
	MCall *sub = last("glBufferSubData");
	CHECK(sub->size == 64, "for the requested bytes (%zu)", sub->size);
	CHECK(r->vt->upload_buffer(r, vb, payload, 64, 2048 - 8) == BERYL_ERR_INVALID,
	      "an upload past the end must be rejected");
	CHECK(r->vt->upload_buffer(r, 99999, payload, 4, 0) == BERYL_ERR_INVALID, "an unknown handle must be rejected");
	CHECK(r->vt->upload_buffer(r, vb, NULL, 4, 0) == BERYL_ERR_INVALID, "a null source must be rejected");

	/* A 32-layer tile array: 2D array target, RGBA8, one sub-image per layer. */
	static uint8_t tiles[32 * 16 * 16 * 4];
	BerylTextureDesc td = { .width = 16, .height = 16, .layers = 32, .nearest = true,
	                        .wrap = false, .initial = tiles, .debug_name = "atlas" };
	BerylTexture tex = BERYL_HANDLE_NONE;
	CHECK(r->vt->create_texture(r, &td, &tex) == BERYL_OK, "the tile array must be created");
	MCall *ti = last("glTexSubImage3D");
	CHECK(ti->n == 32, "one upload per layer (%d)", ti->n);
	MCall *bt = last("glBindTexture");
	CHECK(bt->a0 == BERYL_GL_TEXTURE_2D_ARRAY, "as a 2D array texture (%u)", (unsigned)bt->a0);
	CHECK((size_t)last("glTexSubImage3D.d")->n == 1, "one layer at a time (%d)", last("glTexSubImage3D.d")->n);
	CHECK(last("glTexSubImage3D.type")->n == BERYL_GL_UNSIGNED_BYTE, "RGBA8 (unsigned bytes)");
	/* Nearest + no wrap: point sampling on a tile array is the whole trick. */
	CHECK(texparam_value(BERYL_GL_TEXTURE_MAG_FILTER) == BERYL_GL_NEAREST,
	      "a nearest texture must set GL_NEAREST (%d)", texparam_value(BERYL_GL_TEXTURE_MAG_FILTER));
	CHECK(texparam_value(BERYL_GL_TEXTURE_MIN_FILTER) == BERYL_GL_NEAREST, "on both filters");
	CHECK(texparam_value(BERYL_GL_TEXTURE_WRAP_S) == BERYL_GL_CLAMP_TO_EDGE,
	      "and CLAMP_TO_EDGE, since the shader tiles with fract() (%d)",
	      texparam_value(BERYL_GL_TEXTURE_WRAP_S));
	CHECK(texparam_value(BERYL_GL_TEXTURE_WRAP_T) == BERYL_GL_CLAMP_TO_EDGE, "on both axes");
	CHECK(texparam_value(BERYL_GL_TEXTURE_WRAP_R) == BERYL_GL_CLAMP_TO_EDGE,
	      "and the layer axis too, or a tile index off-by-one bleeds into the neighbour");

	/* A single-layer 2D texture (the lightmap) must not go through the array path. */
	static uint8_t light[16 * 16 * 4];
	BerylTextureDesc ld = { .width = 16, .height = 16, .layers = 1, .nearest = true,
	                        .wrap = false, .initial = light, .debug_name = "lightmap" };
	BerylTexture lm = BERYL_HANDLE_NONE;
	int before = calls("glTexImage3D");
	int before_sub = calls("glTexSubImage3D");
	CHECK(r->vt->create_texture(r, &ld, &lm) == BERYL_OK && lm != tex, "the lightmap must be created");
	CHECK(calls("glTexSubImage3D") == before_sub, "a 1-layer texture must not use the array upload path");
	(void)before;
	CHECK(r->vt->upload_texture_layer(r, tex, 31, tiles) == BERYL_OK, "layer 31 must be addressable");
	CHECK(r->vt->upload_texture_layer(r, tex, 32, tiles) == BERYL_ERR_INVALID, "layer 32 must not be");
	CHECK(r->vt->upload_texture_layer(r, lm, 1, light) == BERYL_ERR_INVALID, "a 2D texture has no layer 1");

	/* Stats must reflect what happened, or the overlay is lying. */
	CHECK(r->vt->stat(r, BERYL_STAT_BUFFER_UPLOADS) >= 3, "buffer uploads are counted (%llu)",
	      (unsigned long long)r->vt->stat(r, BERYL_STAT_BUFFER_UPLOADS));
	CHECK(r->vt->stat(r, BERYL_STAT_BUFFER_BYTES) >= 2048, "bytes are counted");
	CHECK(r->vt->stat(r, BERYL_STAT_TEXTURE_UPLOADS) >= 33, "texture uploads are counted (%llu)",
	      (unsigned long long)r->vt->stat(r, BERYL_STAT_TEXTURE_UPLOADS));
	r->vt->reset_stats(r);
	CHECK(r->vt->stat(r, BERYL_STAT_BUFFER_BYTES) == 0, "and reset_stats clears them");

	BerylRhiInfo info;
	memset(&info, 0, sizeof(info));
	r->vt->get_info(r, &info);
	CHECK(strstr(info.backend, "3.3") != NULL, "the info must name the profile (%s)", info.backend);
	CHECK(!strcmp(info.renderer, "Mock GL Renderer"), "and pass through the driver strings (%s)", info.renderer);
	CHECK(info.max_texture_size == 16384, "and query limits (%d)", info.max_texture_size);
	CHECK(info.api_major == 3 && info.api_minor == 3, "and the version pair");

	r->vt->destroy_buffer(r, vb);
	r->vt->destroy_texture(r, tex);
	CHECK(calls("glDeleteBuffers") >= 1, "destroy must delete the GL objects (%d)", calls("glDeleteBuffers"));
	CHECK(calls("glDeleteTextures") >= 1, "both kinds (%d)", calls("glDeleteTextures"));
	/* A second destroy of the same handle must be a no-op, not a double free. */
	r->vt->destroy_buffer(r, vb);
	r->vt->destroy_texture(r, tex);
	r->vt->destroy(r);
	g.pixels = NULL;
}

static void test_gl_pipeline_and_draw(void) {
	memset(&g, 0, sizeof(g));
	BerylGLLoader l;
	BerylRhi *r = make_rhi(320, 180, &l);
	if (!r) { CHECK(0, "device"); return; }

	BerylPipelineDesc pd = { .debug_name = "terrain", .shader_variant = 0, .blend = false,
	                         .depth_write = true, .depth_test = true, .cull_back = true,
	                         .alpha_test = false, .cull = BERYL_CULL_BACK, .depth = BERYL_DEPTH_LESS };
	BerylPipeline pipe = BERYL_HANDLE_NONE;
	CHECK(r->vt->create_pipeline(r, &pd, &pipe) == BERYL_OK && pipe != BERYL_HANDLE_NONE, "the pipeline must build");
	CHECK(calls("glCreateShader") == 2, "from exactly two shaders (%d)", calls("glCreateShader"));
	CHECK(calls("glCompileShader") == 2, "both compiled (%d)", calls("glCompileShader"));
	CHECK(calls("glLinkProgram") == 1, "one link (%d)", calls("glLinkProgram"));
	CHECK(calls("glDeleteShader") == 2, "shader objects released after linking (%d)", calls("glDeleteShader"));

	/* The source is the shared one, with the profile header prepended. The
	 * attribute names here are the contract with mesh_format.h. */
	CHECK(g.shader_n == 2, "one vertex and one fragment stage (%d)", g.shader_n);
	CHECK(strstr(g.src_vs, "#version 330 core") != NULL, "the vertex stage must be GLSL 330 core");
	CHECK(strstr(g.src_fs, "#version 330 core") != NULL, "and so must the fragment stage");
	CHECK(strstr(g.src_vs, "in uvec2 aPosXY") != NULL,
	      "the vertex source must declare the mesh_format position attribute");
	CHECK(strstr(g.src_vs, "in uvec2 aPack0") != NULL, "and the packed AO/light attribute");
	CHECK(strstr(g.src_vs, "uMVP") != NULL, "and consume the shared uniform block");
	CHECK(strstr(g.src_fs, "sampler2DArray uTexArray") != NULL,
	      "the fragment source must sample the tile array");
	CHECK(strstr(g.src_fs, "uLightmap") != NULL, "and the lightmap");
	CHECK(strstr(beryl_gl_vertex_source(), "gl_Position") != NULL, "the shared vertex program writes gl_Position");
	CHECK(strstr(beryl_gl_fragment_source(), "discard") != NULL,
	      "the shared fragment program must have a cutout path");

	CHECK(last("glGetUniformBlockIndex")->str && !strcmp(last("glGetUniformBlockIndex")->str, "Terrain"),
	      "the uniform block must be looked up by name (%s)",
	      last("glGetUniformBlockIndex")->str ? last("glGetUniformBlockIndex")->str : "(null)");
	CHECK(last("glUniformBlockBinding")->a1 == 0, "and bound to point 0");
	CHECK(calls("glUniform1i") == 2, "both samplers assigned to units (%d)", calls("glUniform1i"));
	CHECK(last("glUniform1i")->a1 == 1, "the lightmap to unit 1");
	/* Attribute locations are queried, not assumed. */
	CHECK(calls("glGetAttribLocation") == 5, "five attributes queried (%d)", calls("glGetAttribLocation"));

	/* Now a frame: pass -> bind -> draw -> readback, and the state it must set. */
	static BerylVertex verts[4];
	for (int i = 0; i < 4; i++) {
		verts[i].pos_x = (uint16_t)(i & 1 ? 4096 : 0);
		verts[i].pos_y = (uint16_t)(i & 2 ? 4096 : 0);
		verts[i].pos_z = 0;
		verts[i].uv_s = 0; verts[i].uv_t = 0;
		verts[i].ao_face = 3 | (1 << 2);
		verts[i].light = 0xFF;
		verts[i].tile = 5; verts[i].flags = 0;
	}
	uint32_t idx[6] = { 0, 1, 2, 2, 1, 3 };
	BerylBufferDesc vbd = { sizeof(verts), false, verts, "vb" }, ibd = { sizeof(idx), false, idx, "ib" };
	BerylBuffer vb = BERYL_HANDLE_NONE, ib = BERYL_HANDLE_NONE;
	CHECK(r->vt->create_buffer(r, &vbd, &vb) == BERYL_OK, "vertex buffer");
	CHECK(r->vt->create_buffer(r, &ibd, &ib) == BERYL_OK, "index buffer");

	BerylFrameDesc fd = { 0.0, 0 };
	CHECK(r->vt->begin_frame(r, &fd) == BERYL_OK, "begin_frame");
	BerylPassDesc pass = { 320, 180, { 0.45f, 0.66f, 0.92f, 1.0f }, 1.0f, true, true, BERYL_HANDLE_NONE };
	CHECK(r->vt->begin_pass(r, &pass) == BERYL_OK, "begin_pass must build the offscreen target");
	CHECK(calls("glGenFramebuffers") == 1, "one FBO (%d)", calls("glGenFramebuffers"));
	CHECK(last("glRenderbufferStorage")->a1 == 320 && last("glRenderbufferStorage")->a2 == 180,
	      "with a depth renderbuffer the size of the pass");
	CHECK(last("glViewport")->a0 == 320 && last("glViewport")->a1 == 180, "and a matching viewport");
	CHECK(last("glClear")->a0 == (BERYL_GL_COLOR_BUFFER_BIT | BERYL_GL_DEPTH_BUFFER_BIT), "clearing colour and depth");
	CHECK(last("glClearColor")->a0 == 450, "to the requested colour (%zu)", last("glClearColor")->a0);
	CHECK(r->vt->draw_indexed(r, 6) == BERYL_ERR_INVALID, "drawing before a bind must be refused");
	CHECK(g.draw_total == 0, "and no command may reach the driver (%d)", g.draw_total);

	BerylTerrainUniforms u;
	memset(&u, 0, sizeof(u));
	for (int i = 0; i < 16; i++) u.mvp[i] = (float)i;
	u.params[2] = (float)BERYL_MODE_NORMAL;
	BerylTexture tiles = BERYL_HANDLE_NONE, lightmap = BERYL_HANDLE_NONE;
	BerylTextureDesc td = { 16, 16, 32, true, false, NULL, "atlas" };
	BerylTextureDesc ld = { 16, 16, 1, true, false, NULL, "light" };
	CHECK(r->vt->create_texture(r, &td, &tiles) == BERYL_OK, "an empty tile array for the bind");
	CHECK(r->vt->create_texture(r, &ld, &lightmap) == BERYL_OK, "and an empty lightmap");
	BerylBindState bs = { 0 };
	bs.vertex_buffer = vb; bs.index_buffer = ib; bs.index_stride = 4;
	bs.uniforms = &u; bs.textures[0] = tiles; bs.textures[1] = lightmap; bs.texture_count = 2;
	bs.index_offset = 0; bs.index_count = 6;
	CHECK(r->vt->bind(r, pipe, &bs) == BERYL_OK, "bind must accept the section state");
	CHECK(calls("glVertexAttribIPointer") == 5, "five integer attributes, no float path (%d)",
	      calls("glVertexAttribIPointer"));
	CHECK(calls("glVertexAttribPointer") == 0, "and never the float-ising variant (%d)",
	      calls("glVertexAttribPointer"));
	CHECK(g.attr_n == 5, "all five locations recorded (%d)", g.attr_n);
	CHECK(g.attr[0].size == 2 && g.attr[0].type == BERYL_GL_UNSIGNED_SHORT && g.attr[0].offset == 0,
	      "aPosXY must be uvec2 of unsigned shorts at byte 0");
	CHECK(g.attr[1].size == 1 && g.attr[1].type == BERYL_GL_UNSIGNED_SHORT && g.attr[1].offset == 4,
	      "aPosZ must be a single unsigned short at byte 4");
	CHECK(g.attr[2].size == 2 && g.attr[2].offset == 6, "aUV at byte 6");
	CHECK(g.attr[3].size == 2 && g.attr[3].type == BERYL_GL_UNSIGNED_BYTE && g.attr[3].offset == 10,
	      "aPack0 must be uvec2 of bytes at byte 10");
	CHECK(g.attr[4].size == 2 && g.attr[4].type == BERYL_GL_UNSIGNED_BYTE && g.attr[4].offset == 12,
	      "aPack1 must be uvec2 of bytes at byte 12");
	CHECK(calls("glEnableVertexAttribArray") == 5, "all five enabled (%d)", calls("glEnableVertexAttribArray"));
	CHECK(last("glVertexAttribI.stride")->n == (int)sizeof(BerylVertex), "with the mesh_format stride");

	CHECK(was_enabled(BERYL_GL_DEPTH_TEST), "depth test on for an opaque terrain pass");
	CHECK(was_enabled(BERYL_GL_CULL_FACE), "backface culling on");
	CHECK(was_disabled(BERYL_GL_BLEND), "blending off unless the pipeline asks for it");
	CHECK(!was_enabled(BERYL_GL_BLEND), "and not enabled behind our back");
	CHECK(last("glFrontFace")->a0 == BERYL_GL_CCW, "front faces are CCW, like the mesher emits");
	CHECK(last("glCullFace")->a0 == BERYL_GL_BACK, "culling back faces");
	CHECK(last("glDepthFunc")->a0 == BERYL_GL_LESS, "depth func from the pipeline desc");
	CHECK(g.ubo_size == sizeof(BerylTerrainUniforms),
	      "the uniform block must be uploaded as exactly %zu bytes (got %zu)",
	      sizeof(BerylTerrainUniforms), g.ubo_size);
	CHECK(g.ubo_uploads == 1, "once, not once per attribute (%d)", g.ubo_uploads);
	CHECK(last("glBindBufferRange")->size == sizeof(BerylTerrainUniforms), "and bound for that many bytes");

	CHECK(r->vt->draw_indexed(r, 6) == BERYL_OK, "draw_indexed must go through");
	MCall *de = last("glDrawElements");
	CHECK(g.draw_total == 1, "one indexed draw so far (%d)", g.draw_total);
	CHECK(de->a1 == 6, "of 6 indices (%zu)", de->a1);
	CHECK(de->a0 == BERYL_GL_TRIANGLES, "as triangles");
	CHECK(de->a2 == BERYL_GL_UNSIGNED_INT, "with 32-bit indices");
	CHECK(r->vt->stat(r, BERYL_STAT_DRAW_CALLS) == 1, "one draw counted");
	CHECK(r->vt->stat(r, BERYL_STAT_TRIANGLES) == 2, "two triangles counted");
	CHECK(r->vt->stat(r, BERYL_STAT_VERTS) == 6, "six vertices consumed");

	/* Rebinding the same buffers must not re-specify the layout, and identical
	 * uniforms must not be re-uploaded: this is the per-frame cost Sodium's
	 * equivalent path is judged on. */
	int attribs_before = calls("glVertexAttribIPointer");
	int subdata_before = g.ubo_uploads;
	CHECK(r->vt->bind(r, pipe, &bs) == BERYL_OK, "rebind");
	CHECK(calls("glVertexAttribIPointer") == attribs_before, "no redundant attribute setup (%d)",
	      calls("glVertexAttribIPointer") - attribs_before);
	CHECK(g.ubo_uploads == subdata_before, "no redundant uniform upload (%d)", g.ubo_uploads - subdata_before);
	u.params[0] = 0.5f;
	CHECK(r->vt->bind(r, pipe, &bs) == BERYL_OK, "rebind after a uniform change");
	CHECK(g.ubo_uploads == subdata_before + 1, "and exactly one upload when it changed (%d)",
	      g.ubo_uploads - subdata_before);

	/* An offset draw must advance the byte offset by the stride, not the count. */
	bs.index_offset = 3;
	bs.index_stride = 4;
	CHECK(r->vt->bind(r, pipe, &bs) == BERYL_OK, "rebind with an index offset");
	CHECK(r->vt->draw_indexed(r, 3) == BERYL_OK, "draw the second triangle");
	CHECK(last("glDrawElements")->size == 12, "byte offset 12 for index offset 3 (%zu)",
	      last("glDrawElements")->size);
	bs.index_stride = 3;
	CHECK(r->vt->bind(r, pipe, &bs) == BERYL_ERR_INVALID, "a 3-byte index stride must be refused");
	bs.index_stride = 2;
	CHECK(r->vt->bind(r, pipe, &bs) == BERYL_OK, "16-bit indices are supported");
	r->vt->draw_indexed(r, 3);
	CHECK(last("glDrawElements")->a2 == BERYL_GL_UNSIGNED_SHORT, "and select GL_UNSIGNED_SHORT");

	/* Readback: the mock fills a two-row gradient; GL reads bottom-up, so the
	 * image the engine sees must have the rows in the other order. */
	g.px_w = 320; g.px_h = 180;
	g.pixels = (uint8_t *)malloc((size_t)320 * 180 * 4u);
	for (int y = 0; y < 180; y++)
		for (int x = 0; x < 320; x++) {
			uint8_t *p = g.pixels + ((size_t)y * 320 + x) * 4u;
			p[0] = (uint8_t)y; p[1] = (uint8_t)x; p[2] = 3; p[3] = 255;   /* row y == y */
		}
	int w = 0, h = 0;
	const uint8_t *rb = r->vt->readback(r, &w, &h);
	CHECK(rb && w == 320 && h == 180, "readback must return the frame (%dx%d)", w, h);
	if (rb) {
		/* Bottom row of the GL buffer (y=179) must become the top row here. */
		CHECK(rb[0] == 179, "readback must flip GL's bottom-up origin (top row = %d)", rb[0]);
		CHECK(rb[((size_t)179 * 320 + 0) * 4u] == 0, "and the bottom row must be GL's y=0 (%d)",
		      rb[((size_t)179 * 320 + 0) * 4u]);
	}
	free(g.pixels); g.pixels = NULL;

	r->vt->end_pass(r);
	CHECK(calls("glFlush") == 0, "end_pass must not flush by itself (%d)", calls("glFlush"));
	r->vt->end_frame(r);
	CHECK(calls("glFlush") == 1, "end_frame flushes once (%d)", calls("glFlush"));
	r->vt->destroy_pipeline(r, pipe);
	CHECK(calls("glDeleteProgram") == 1, "destroying the pipeline deletes the program (%d)",
	      calls("glDeleteProgram"));
	r->vt->destroy(r);
}

/* The blend pass differs only in state, and the debug modes only in uniforms:
 * if that stops being true the two backends will drift apart silently. */
static void test_gl_pipeline_variants(void) {
	memset(&g, 0, sizeof(g));
	BerylGLLoader l;
	BerylRhi *r = make_rhi(64, 64, &l);
	if (!r) { CHECK(0, "device"); return; }
	BerylPipelineDesc opaque = { "terrain", 0, false, true, true, true, false, BERYL_CULL_BACK, BERYL_DEPTH_LESS };
	BerylPipelineDesc blended = { "terrain_blend", 1, true, false, true, true, true, BERYL_CULL_BACK, BERYL_DEPTH_LEQUAL };
	BerylPipeline p0 = 0, p1 = 0;
	CHECK(r->vt->create_pipeline(r, &opaque, &p0) == BERYL_OK, "opaque pipeline");
	CHECK(r->vt->create_pipeline(r, &blended, &p1) == BERYL_OK, "blended pipeline");
	CHECK(p0 != p1, "they must be distinct handles");
	CHECK(calls("glCreateProgram") == 2, "one program each (%d)", calls("glCreateProgram"));

	BerylBufferDesc bd = { 64, false, NULL, "v" };
	BerylBuffer vb = 0, ib = 0;
	CHECK(r->vt->create_buffer(r, &bd, &vb) == BERYL_OK, "a buffer");
	CHECK(r->vt->create_buffer(r, &bd, &ib) == BERYL_OK, "another");
	BerylFrameDesc fd = { 0, 0 };
	r->vt->begin_frame(r, &fd);
	BerylPassDesc pass = { 64, 64, { 0, 0, 0, 1 }, 1, true, false, BERYL_HANDLE_NONE };
	CHECK(r->vt->begin_pass(r, &pass) == BERYL_OK, "a pass without an FBO uses the default framebuffer");
	CHECK(calls("glGenFramebuffers") == 0, "and creates no offscreen target (%d)", calls("glGenFramebuffers"));
	CHECK(last("glBindFramebuffer")->a1 == 0, "binding framebuffer 0");
	BerylTerrainUniforms u;
	memset(&u, 0, sizeof(u));
	BerylBindState bs = { vb, ib, 4, &u, { 0, 0, 0, 0 }, 0, 0, 6 };
	CHECK(r->vt->bind(r, p1, &bs) == BERYL_OK, "bind the blend pipeline");
	CHECK(last("glEnable")->a0 == BERYL_GL_BLEND, "enabling blending");
	CHECK(last("glDepthMask")->a0 == BERYL_GL_FALSE, "with depth writes off (transparent geometry)");
	CHECK(last("glDepthFunc")->a0 == BERYL_GL_LEQUAL, "and LEQUAL so later fragments still shade");
	CHECK(r->vt->bind(r, p0, &bs) == BERYL_OK, "switch back to opaque");
	CHECK(last("glDisable")->a0 == BERYL_GL_BLEND, "disabling blending again");
	CHECK(last("glDepthMask")->a0 == BERYL_GL_TRUE, "and writing depth");
	r->vt->end_pass(r);
	r->vt->destroy(r);
}

void test_gl_backend(void) {
	test_gl_loader_contract();
	test_gl_resources();
	test_gl_pipeline_and_draw();
	test_gl_pipeline_variants();
}

#endif /* BERYL_WITH_OPENGL */
