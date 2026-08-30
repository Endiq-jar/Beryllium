/* test_main.c -- the whole test runner.
 *
 * Usage: beryl_tests [substring]     run only tests whose name matches
 *
 * Exits 0 when every check passed, 1 otherwise, so CI can just run `make test`.
 */
#include "test.h"

#include "bcore.h"

#include <stdio.h>
#include <string.h>
#include <time.h>

int beryl_test_checks = 0;
int beryl_test_failed = 0;

static double now_ms(void) {
	struct timespec ts;
	clock_gettime(CLOCK_MONOTONIC, &ts);
	return (double)ts.tv_sec * 1000.0 + (double)ts.tv_nsec / 1e6;
}

#define RUN(fn)                                                              \
	do {                                                                 \
		if (filter && !strstr(#fn, filter)) break;                     \
		int checks_before = beryl_test_checks;                         \
		int fails_before = beryl_test_failed;                          \
		double t0 = now_ms();                                          \
		printf("%-16s ", #fn);                                         \
		fflush(stdout);                                                  \
		fn();                                                            \
		double ms = now_ms() - t0;                                       \
		int ok = beryl_test_failed == fails_before;                      \
		printf("%s  %4d checks  %6.0f ms\n", ok ? "ok" : "FAILED",       \
		       beryl_test_checks - checks_before, ms);                     \
	} while (0)

int main(int argc, char **argv) {
	const char *filter = argc > 1 ? argv[1] : NULL;
	beryl_log_set_level(BERYL_LOG_ERROR);       /* the suites report their own results */
	printf("beryllium engine tests\n");
	printf("----------------------\n");

	RUN(test_basics);
	RUN(test_world);
	RUN(test_pool);
	RUN(test_perf);
	RUN(test_soft_render);
	RUN(test_gl_backend);

	printf("----------------------\n");
	printf("%d checks, %d failed\n", beryl_test_checks, beryl_test_failed);
	if (beryl_test_failed) { printf("RESULT: FAIL\n"); return 1; }
	printf("RESULT: pass\n");
	return 0;
}
