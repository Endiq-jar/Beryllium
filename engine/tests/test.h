/* test.h -- the whole test framework.
 *
 * Deliberately tiny: no fixtures, no registration magic, no dependencies. Every
 * test is a `void f(void)` that reports through CHECK/CHECK_NEAR, and the runner
 * in test_main.c prints one line per test. That is enough for a C11 project that
 * must build with nothing but a compiler and libm.
 */
#ifndef BERYL_TEST_H
#define BERYL_TEST_H

#include <stdio.h>
#include <math.h>

extern int beryl_test_checks;
extern int beryl_test_failed;

/* CHECK prints and keeps going, so one run reports every failure instead of
 * stopping at the first -- much faster to debug in CI. */
#define CHECK(cond, ...)                                                     \
	do {                                                                 \
		beryl_test_checks++;                                         \
		if (!(cond)) {                                               \
			beryl_test_failed++;                                 \
			fprintf(stderr, "\n  FAIL  %s:%d\n        ", __FILE__, __LINE__); \
			fprintf(stderr, __VA_ARGS__);                          \
			fprintf(stderr, "\n");                                 \
		}                                                            \
	} while (0)

#define CHECK_NEAR(a, b, eps, ...)                                           \
	do {                                                                 \
		double _va = (double)(a), _vb = (double)(b);                 \
		beryl_test_checks++;                                         \
		if (!(fabs(_va - _vb) <= (double)(eps))) {                   \
			beryl_test_failed++;                                 \
			fprintf(stderr, "\n  FAIL  %s:%d  %.9g != %.9g (%s)\n", \
			        __FILE__, __LINE__, _va, _vb, #a);             \
			fprintf(stderr, "        ");                           \
			fprintf(stderr, __VA_ARGS__);                          \
			fprintf(stderr, "\n");                                 \
		}                                                            \
	} while (0)

/* Each suite exposes exactly one entry point. */
void test_basics(void);
void test_world(void);
void test_soft_render(void);
void test_gl_backend(void);

#endif /* BERYL_TEST_H */
