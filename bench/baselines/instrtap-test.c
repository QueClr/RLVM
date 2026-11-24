/* bench/baselines/instrtap-test.c - self-test of instrtap.c.
 *
 *   instrtap-test THREADS ITERATIONS
 *
 * Each thread writes "start <k>" (in pieces, as Rust's eprintln! does), runs a
 * loop of a fixed number of instructions per iteration, and writes "end <k>".
 * Under LD_PRELOAD=libinstrtap.so with INSTRTAP_PREFIXES='start |end ' the
 * difference of the two "#I" lines of a thread must be the loop's count
 * (6 instructions per iteration on aarch64 with gcc -O2, see perf-validate).
 * Run by bench/baselines/profile-mathlib.sh selftest.
 */
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static uint64_t iterations;

/* noipa: otherwise gcc sees a pure function and moves the call past the
 * "end" line, and the marked interval is empty. */
__attribute__((noinline, noipa)) static uint64_t spin(uint64_t n) {
  uint64_t x = 88172645463325252ULL;
  for (uint64_t i = 0; i < n; i++) {
    x ^= x << 13;
    x ^= x >> 7;
    x ^= x << 17;
  }
  return x;
}

static void *worker(void *arg) {
  char num[16];
  int len = snprintf(num, sizeof num, "%d", (int)(intptr_t)arg);
  if (write(2, "start ", 6) < 0 || write(2, num, (size_t)len) < 0 || write(2, "\n", 1) < 0) return NULL;
  volatile uint64_t r = spin(iterations);
  if (write(2, "end ", 4) < 0 || write(2, num, (size_t)len) < 0 || write(2, "\n", 1) < 0) return NULL;
  return (void *)(uintptr_t)r;
}

int main(int argc, char **argv) {
  if (argc != 3) return 2;
  int t = atoi(argv[1]);
  iterations = strtoull(argv[2], NULL, 10);
  pthread_t ids[64];
  if (t < 1 || t > 64) return 2;
  for (int i = 0; i < t; i++)
    if (pthread_create(&ids[i], NULL, worker, (void *)(intptr_t)i) != 0) return 3;
  for (int i = 0; i < t; i++) pthread_join(ids[i], NULL);
  return 0;
}
