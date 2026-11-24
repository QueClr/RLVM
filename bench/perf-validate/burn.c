/* Deterministic CPU burner used by bench/perf-validate.py to check that
 * perf's instruction counts include threads and child processes.
 *
 *   burn single N           one thread, N loop iterations
 *   burn threads T N        T threads, N iterations each
 *   burn fork F N           F forked child processes, N iterations each
 *
 * The loop is a fixed number of instructions per iteration, so the expected
 * count is (workers * N * k) + a small constant.
 */
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/wait.h>
#include <unistd.h>

static uint64_t spin(uint64_t n)
{
    uint64_t x = 88172645463325252ULL;
    for (uint64_t i = 0; i < n; i++) {
        x ^= x << 13;
        x ^= x >> 7;
        x ^= x << 17;
    }
    return x;
}

static void *worker(void *arg)
{
    uint64_t n = *(uint64_t *)arg;
    return (void *)(uintptr_t)spin(n);
}

int main(int argc, char **argv)
{
    if (argc == 3 && strcmp(argv[1], "single") == 0) {
        printf("%llu\n", (unsigned long long)spin(strtoull(argv[2], NULL, 10)));
        return 0;
    }
    if (argc == 4 && strcmp(argv[1], "threads") == 0) {
        int t = atoi(argv[2]);
        uint64_t n = strtoull(argv[3], NULL, 10);
        pthread_t *ids = calloc((size_t)t, sizeof *ids);
        uint64_t sum = 0;
        for (int i = 0; i < t; i++)
            if (pthread_create(&ids[i], NULL, worker, &n) != 0) return 3;
        for (int i = 0; i < t; i++) {
            void *ret;
            pthread_join(ids[i], &ret);
            sum += (uint64_t)(uintptr_t)ret;
        }
        printf("%llu\n", (unsigned long long)sum);
        return 0;
    }
    if (argc == 4 && strcmp(argv[1], "fork") == 0) {
        int f = atoi(argv[2]);
        uint64_t n = strtoull(argv[3], NULL, 10);
        for (int i = 0; i < f; i++) {
            pid_t pid = fork();
            if (pid < 0) return 3;
            if (pid == 0) {
                volatile uint64_t x = spin(n);
                (void)x;
                _exit(0);
            }
        }
        for (int i = 0; i < f; i++) wait(NULL);
        return 0;
    }
    fprintf(stderr, "usage: burn single N | threads T N | fork F N\n");
    return 2;
}
