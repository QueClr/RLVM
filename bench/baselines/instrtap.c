/* bench/baselines/instrtap.c - per-declaration instruction counts for an
 * unmodified checker that prints a line per declaration.
 *
 *   gcc -O2 -shared -fPIC -o libinstrtap.so instrtap.c
 *   LD_PRELOAD=$PWD/libinstrtap.so INSTRTAP_PREFIXES='start |end ' checker --trace ... 2> trace.txt
 *
 * The library interposes write(2) and fwrite(3). Whenever the program starts
 * a line on file descriptor INSTRTAP_FD (default 2, stderr) with one of the
 * prefixes in INSTRTAP_PREFIXES (separated by '|'; "\t" stands for a tab), it
 * first writes
 *
 *   #I <tid> <instructions>
 *
 * to the same descriptor: the number of user-space instructions the calling
 * thread has retired since its counter was opened (at the thread's first
 * marked line). The difference between two such lines of one thread is the
 * instruction count of whatever that thread did in between, e.g. checking
 * the declaration between a "start" and an "end" line. The counter is the
 * one `perf stat -e instructions:u` uses (perf_event_open, INST_RETIRED, user
 * mode only, this thread only, not inherited), so the numbers add up to what
 * the arena measures, minus what happens outside marked intervals.
 *
 * On a big.LITTLE arm64 host there is one PMU per core type and an event
 * counts only on the cores of its PMU; one event per PMU is opened and the
 * counts are added, as bench/measure.py does with `perf stat --no-scale`.
 *
 * Needs the right to open a perf event (CAP_PERFMON in the rlvm-arena
 * container). If the counter cannot be opened the lines read "#I <tid> -1".
 *
 * Cost: two or three system calls per marked line (kernel time, not counted)
 * and about a hundred user instructions, which land in the following
 * interval.
 */
#define _GNU_SOURCE
#include <dirent.h>
#include <dlfcn.h>
#include <linux/perf_event.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/syscall.h>
#include <unistd.h>

#define MAX_PMU 4
#define MAX_PREFIX 8

static int pmu_type[MAX_PMU];
static uint64_t pmu_config[MAX_PMU];
static int n_pmu;
static char prefix_buf[256];
static const char *prefix[MAX_PREFIX];
static size_t prefix_len[MAX_PREFIX];
static int n_prefix;
static int tap_fd = 2;

static __thread int ev_fd[MAX_PMU];
static __thread int ev_state;          /* 0 not opened, 1 open, -1 unavailable */
static __thread int my_tid;
static __thread char at_line_start = 1;

static int read_int_file(const char *path) {
  FILE *f = fopen(path, "r");
  int v = -1;
  if (f) {
    if (fscanf(f, "%d", &v) != 1) v = -1;
    fclose(f);
  }
  return v;
}

__attribute__((constructor)) static void instrtap_init(void) {
  const char *p = getenv("INSTRTAP_PREFIXES");
  const char *fd = getenv("INSTRTAP_FD");
  if (fd && *fd) tap_fd = atoi(fd);
  if (p && *p) {
    /* copy, translating "\t" and splitting on '|' */
    size_t o = 0;
    prefix[0] = prefix_buf;
    n_prefix = 1;
    for (size_t i = 0; p[i] && o + 1 < sizeof prefix_buf; i++) {
      if (p[i] == '\\' && p[i + 1] == 't') { prefix_buf[o++] = '\t'; i++; }
      else if (p[i] == '|' && n_prefix < MAX_PREFIX) { prefix_buf[o++] = 0; prefix[n_prefix++] = prefix_buf + o; }
      else prefix_buf[o++] = p[i];
    }
    prefix_buf[o] = 0;
    for (int i = 0; i < n_prefix; i++) prefix_len[i] = strlen(prefix[i]);
  }
  /* one event per CPU PMU (arm64: armv8_pmuv3_N, raw event 0x08 INST_RETIRED) */
  DIR *d = opendir("/sys/bus/event_source/devices");
  if (d) {
    struct dirent *e;
    while ((e = readdir(d)) && n_pmu < MAX_PMU) {
      if (strncmp(e->d_name, "armv8_", 6) != 0) continue;
      char path[512];
      snprintf(path, sizeof path, "/sys/bus/event_source/devices/%s/type", e->d_name);
      int t = read_int_file(path);
      if (t < 0) continue;
      pmu_type[n_pmu] = t;
      pmu_config[n_pmu] = 0x08;
      n_pmu++;
    }
    closedir(d);
  }
  if (n_pmu == 0) {  /* x86 and other single-PMU hosts */
    pmu_type[0] = PERF_TYPE_HARDWARE;
    pmu_config[0] = PERF_COUNT_HW_INSTRUCTIONS;
    n_pmu = 1;
  }
}

static void open_counters(void) {
  my_tid = (int)syscall(SYS_gettid);
  ev_state = 1;
  for (int i = 0; i < n_pmu; i++) {
    struct perf_event_attr a;
    memset(&a, 0, sizeof a);
    a.size = sizeof a;
    a.type = (uint32_t)pmu_type[i];
    a.config = pmu_config[i];
    a.exclude_kernel = 1;
    a.exclude_hv = 1;
    ev_fd[i] = (int)syscall(SYS_perf_event_open, &a, 0, -1, -1, PERF_FLAG_FD_CLOEXEC);
    if (ev_fd[i] < 0) ev_state = -1;
  }
}

static void emit(void) {
  if (ev_state == 0) open_counters();
  uint64_t total = 0;
  int ok = ev_state == 1;
  if (ok) {
    for (int i = 0; i < n_pmu; i++) {
      uint64_t v = 0;
      if (syscall(SYS_read, ev_fd[i], &v, sizeof v) != (long)sizeof v) ok = 0;
      total += v;
    }
  }
  char line[64];
  char *q = line + sizeof line;
  *--q = '\n';
  if (ok) {
    do { *--q = (char)('0' + total % 10); total /= 10; } while (total);
  } else {
    *--q = '1'; *--q = '-';
  }
  *--q = ' ';
  unsigned t = (unsigned)my_tid;
  do { *--q = (char)('0' + t % 10); t /= 10; } while (t);
  *--q = ' '; *--q = 'I'; *--q = '#';
  syscall(SYS_write, tap_fd, q, (size_t)(line + sizeof line - q));
}

static int marked(const void *buf, size_t count) {
  for (int i = 0; i < n_prefix; i++)
    if (count >= prefix_len[i] && memcmp(buf, prefix[i], prefix_len[i]) == 0) return 1;
  return 0;
}

ssize_t write(int fd, const void *buf, size_t count) {
  if (fd == tap_fd && n_prefix > 0 && count > 0) {
    if (at_line_start && marked(buf, count)) emit();
    at_line_start = ((const char *)buf)[count - 1] == '\n';
  }
  return syscall(SYS_write, fd, buf, count);
}

/* For programs that print through stdio (rpylean): what the stream has
 * buffered is flushed first, so that the "#I" line lands before the marked
 * line in the output. */
size_t fwrite(const void *ptr, size_t size, size_t nmemb, FILE *stream) {
  static size_t (*real)(const void *, size_t, size_t, FILE *);
  if (!real) real = (size_t (*)(const void *, size_t, size_t, FILE *))dlsym(RTLD_NEXT, "fwrite");
  size_t count = size * nmemb;
  if (n_prefix > 0 && count > 0 && fileno(stream) == tap_fd) {
    if (at_line_start && marked(ptr, count)) { fflush(stream); emit(); }
    at_line_start = ((const char *)ptr)[count - 1] == '\n';
  }
  return real(ptr, size, nmemb, stream);
}
