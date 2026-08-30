/* bcore.c -- logging, assertions, counters, timing. */
#include "bcore.h"

#include <stdatomic.h>
#include <stdlib.h>
#include <stdarg.h>
#include <stdio.h>
#include <string.h>
#include <time.h>

static atomic_bool   g_sink_set = ATOMIC_VAR_INIT(false);
static beryl_log_fn  g_sink     = NULL;
static void         *g_sink_user;
static int           g_level    = BERYL_LOG_INFO;

void beryl_log_set_sink(beryl_log_fn fn, void *user) {
	g_sink = fn;
	g_sink_user = user;
	atomic_store(&g_sink_set, fn != NULL);
}

void beryl_log_set_level(BerylLogLevel level) { g_level = (int)level; }

static const char *level_name(BerylLogLevel l) {
	switch (l) {
		case BERYL_LOG_DEBUG: return "debug";
		case BERYL_LOG_INFO:  return "info ";
		case BERYL_LOG_WARN:  return "warn ";
		default:              return "error";
	}
}

void beryl_log_write(BerylLogLevel level, const char *fmt, ...) {
	char buf[1024];
	va_list ap;
	if ((int)level < g_level) {
		return;
	}
	va_start(ap, fmt);
	vsnprintf(buf, sizeof(buf), fmt, ap);
	va_end(ap);

	if (g_sink) {
		g_sink(g_sink_user, level, buf);
	} else {
		fprintf(stderr, "[beryl %s] %s\n", level_name(level), buf);
	}
}

void beryl_assert_fail(const char *expr, const char *file, int line, const char *fmt, ...) {
	char detail[512];
	va_list ap;
	va_start(ap, fmt);
	vsnprintf(detail, sizeof(detail), fmt, ap);
	va_end(ap);
	fprintf(stderr, "\n[beryl] ASSERTION FAILED: %s\n          at %s:%d\n          %s\n",
	        expr, file, line, detail);
	fflush(stderr);
	abort();
}

/* ------------------------------------------------------------- counters ---- */
static _Atomic int64_t g_counters[BERYL_CTR_COUNT];

void beryl_ctr_add(BerylCounter c, int64_t n) {
	BERYL_ASSERT((int)c >= 0 && (int)c < BERYL_CTR_COUNT, "counter out of range: %d", (int)c);
	atomic_fetch_add_explicit(&g_counters[c], n, memory_order_relaxed);
}

int64_t beryl_ctr_get(BerylCounter c) {
	return atomic_load_explicit(&g_counters[c], memory_order_relaxed);
}

void beryl_ctr_reset(void) {
	for (int i = 0; i < BERYL_CTR_COUNT; i++) {
		atomic_store_explicit(&g_counters[i], 0, memory_order_relaxed);
	}
}

/* ------------------------------------------------------------------ time --- */
uint64_t beryl_time_ns(void) {
	struct timespec ts;
clock_gettime(BERYL_CLOCK_ID, &ts);
	return (uint64_t)ts.tv_sec * 1000000000ull + (uint64_t)ts.tv_nsec;
}

double beryl_time_ms(void) { return (double)beryl_time_ns() * 1e-6; }
