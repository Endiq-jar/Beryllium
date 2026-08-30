# Beryllium Engine -- no configure step, no external dependencies.
#
#   make            the demo binary (build/beryl) and the test runner
#   make test       build + run the whole test suite
#   make bench      meshing/render benchmark on a generated region
#   make ANDROID=1  phone-class build profile (see "Build profiles" below)
#   make clean
#
# The engine is C11 + libc + pthread + dl only. Set ENGINE_CC to use another
# compiler, EXTRA_CFLAGS to add flags (e.g. -fsanitize=address,undefined).

ENGINE_CC  ?= cc
BUILD      ?= build
OBJ        := $(BUILD)/obj
BIN        := $(BUILD)/beryl
TESTBIN    := $(BUILD)/beryl_tests
TOOLS_BIN  := $(BUILD)/tools

# Optional GPU backends. Compiled when the source is present; when it is not (or
# when a driver is missing) the engine falls back to the software rasterizer and
# says so, instead of failing to link or crashing.
BACKEND_SRCS := $(wildcard src/rhi_gl.c) $(wildcard src/rhi_vk.c src/spirv.c)
BACKEND_DEFS := $(if $(wildcard src/rhi_gl.c),-DBERYL_WITH_OPENGL=1,)$(if $(wildcard src/spirv.c),-DBERYL_WITH_VULKAN=1,)

WARN       := -Wall -Wextra -Wshadow -Wpointer-arith -Wcast-align -Wstrict-prototypes \
              -Wmissing-prototypes -Wno-unused-parameter -Wno-format-nonliteral
OPT        ?= -O3
CFLAGS     += -std=c11 $(OPT) $(WARN) -g -D_POSIX_C_SOURCE=200809L -Isrc -I$(BUILD)/gen $(BACKEND_DEFS)
CFLAGS     += $(EXTRA_CFLAGS)
LDFLAGS    += -lm -lpthread -ldl $(EXTRA_LDFLAGS)

# --- Build profiles -------------------------------------------------------------
#
#   make                                     desktop / container default
#   make ANDROID=1                           phone-class ARM: LTO, section
#                                            gathering, hidden visibility, the
#                                            mobile runtime defaults (BERYL_MOBILE)
#   make ANDROID=1 NDEBUG=1                  the above minus the asserts, for a
#                                            shipping build
#
# ANDROID=1 only changes flags, never code paths, so the same `make test` proves
# it. With an NDK this is the whole build:
#
#   make ANDROID=1 BUILD=build-and \
#        ENGINE_CC=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/clang \
#        EXTRA_CFLAGS="--target=aarch64-linux-android21 -fPIC -D__ANDROID__"
#
# and without one the flags still compile and run the suite here, which is how
# they stay honest. -DNDEBUG is deliberately NOT part of ANDROID=1: the test
# binary is built with the same flags, and asserts are the only internal
# checks the suite can lean on.
PROFILE_CFLAGS := -flto -fno-semantic-interposition -fvisibility=hidden -fno-plt \
                  -ffunction-sections -fdata-sections $(if $(NDEBUG),-DNDEBUG,)
PROFILE_LDFLAGS := -flto -Wl,--gc-sections
ifdef ANDROID
CFLAGS  += $(PROFILE_CFLAGS) -DBERYL_MOBILE=1
LDFLAGS += $(PROFILE_LDFLAGS)
endif

# --- Optional backends are selected by which sources exist; see BACKEND_DEFS.
# There is no Vulkan device backend in tree (see README), so nothing here needs
# a loader or SDK headers. ---
CORE_SRCS := \
	src/bcore.c src/bmath.c src/blocks.c src/chunk.c src/world.c src/worldgen.c \
	src/light.c src/mesh_format.c src/mesher.c src/pool.c src/camera.c src/occlusion.c \
	src/rhi.c src/rhi_soft.c src/png.c src/perf.c src/engine.c
SRCS := $(CORE_SRCS) $(BACKEND_SRCS)
MAIN_SRC := src/main.c
TEST_SRCS := $(wildcard tests/*.c)

OBJS      := $(patsubst %.c,$(OBJ)/%.o,$(SRCS))
MAIN_OBJ  := $(OBJ)/src/main.o
TEST_OBJS := $(patsubst %.c,$(OBJ)/%.o,$(TEST_SRCS))

# Generated: shader sources embedded as C arrays, so the binary needs no data
# files next to it. tools/embed.c is compiled and run at build time.
EMBED     := $(BUILD)/tools-embed
SHADER_HDR:= $(BUILD)/gen/shaders_terrain.h
SHADERS   := $(wildcard shaders/*.glsl)

.PHONY: all test check bench clean vulkan gl run tools distclean

all: $(BIN) $(TESTBIN) tools

$(EMBED): tools/embed.c
	@mkdir -p $(dir $@)
	$(ENGINE_CC) -std=c11 -O1 -o $@ $<

$(SHADER_HDR): $(SHADERS) $(EMBED)
	@mkdir -p $(dir $@)
	$(EMBED) $(SHADERS) $@

$(OBJ)/%.o: %.c $(SHADER_HDR)
	@mkdir -p $(dir $@)
	$(ENGINE_CC) $(CFLAGS) -MMD -MP -c $< -o $@

$(BIN): $(OBJS) $(MAIN_OBJ)
	@mkdir -p $(dir $@)
	$(ENGINE_CC) $^ -o $@ $(LDFLAGS)
	@echo "built $@"

$(TESTBIN): $(OBJS) $(TEST_OBJS)
	@mkdir -p $(dir $@)
	$(ENGINE_CC) $^ -o $@ $(LDFLAGS)

test check: $(TESTBIN)
	@$(TESTBIN)

bench: $(BIN)
	@$(BIN) --frames 4 --benchmark --quiet --screenshot $(BUILD)/bench.png --generate 8

tools: $(TOOLS_BIN)
$(TOOLS_BIN):
	@true

run: $(BIN)
	@$(BIN) --orbit --frames 1 --screenshot $(BUILD)/preview.png

clean:
	rm -rf $(BUILD)

distclean: clean
	rm -f frame.png *.obj
