# 12 — Baselines on this machine: the numbers RLVM has to beat

Measured 2026-11-24 on the project host (aarch64, Cortex-X925 + Cortex-A725, 20 cores, 121 GB) inside the
`rlvm-arena` container, with the arena's own checker builds (`bench/baselines/`, arena revision `99195b6b`) on
the arena's own exports (`work/exports/`, byte-identical to the published ones). Everything here is a
measurement made for this document; nothing is taken from the checkers' READMEs.

- Machine-readable results: `bench/baselines.json` (every run, medians, ratios, published numbers).
- Raw runs: `work/baselines/big/runs.jsonl`, logs in `work/baselines/big/logs/`.
- Per-declaration profiles of mathlib: `work/baselines/mathlib-profile.json`, `mathlib-profile.top200.tsv`,
  `mathlib-profile.<profile>.tsv.gz` (every declaration, most expensive first), `mathlib-profile.by-name.tsv.gz`
  (one row per declaration name with its cost in nano-lean, gruszka, rpylean and sokonanoda).
- Scripts: `bench/baselines/big.sh`, `big-all.sh`, `big-summary.py`, `profile-mathlib.sh`, `instrtap.c`,
  `profile-summary.py`; commands in section 8 and in `bench/README.md`.

The tables between `<!-- BEGIN ... -->` and `<!-- END ... -->` markers are generated from those files by
`bench/baselines/render-tables.py docs/research/12-baselines.md`; do not edit them by hand.

---

## 0. Summary

1. **The number to beat on this machine is 361.3e9 instructions = 60.2 virtual seconds on mathlib**
   (nano-lean, median of 3 runs, spread 0.04 %). Then gruszka 418.7e9 (69.8 s, 1.16 x),
   sokonanoda 504.3e9 (84.1 s, 1.40 x) and rpylean 544.6e9 (90.8 s, 1.51 x): the arena's
   top four, in the arena's order. After a gap: mathgraph 880.4e9, pestka 1.34e12, nanoda 5.53e12,
   tenet-jvm 9.31e12 (25.9 virtual minutes with C2; 11.96e12 with the Graal JIT) and the official kernel
   11.42e12 (31.7 virtual minutes). All ten accept all three corpora.
2. **aarch64 counts are not the published ones.** Native checkers execute 3-10 % fewer instructions here
   than on the arena's x86-64 runner (nano-lean 0.915, gruszka 0.906, sokonanoda 0.956, rpylean 0.942, official
   0.945); the JVM checker has no stable ratio (1.03 on init, 1.05 on std, 0.951 on mathlib with C2).
   RLVM's own ratio is unknown, so "ahead of nano-lean on the arena" is only safe below about 355e9 here
   (59 virtual s); section 3.
3. **Instruction counts repeat, times do not.** Spread over 3 runs: at most 0.3 % for the fast checkers on
   mathlib, 0.7 % on init and std; up to 5 % for tenet-jvm on init. Wall time of the same command varied by
   up to a factor of 2 with the load of the host (gruszka on mathlib: 18.1 s to 35.3 s).
4. **Worker threads are free in the metric.** 1, 2, 4 and 8 workers cost nano-lean 361.0e9, 361.2e9, 361.3e9 and
   361.9e9 instructions; gruszka +1.0 % and sokonanoda +3.5 % from 1 to 4 workers; section 4.
5. **Reading the export is 7-11 % of a leader's run**: 37-41e9 instructions for 6.3 GB, about 6 per byte;
   section 5.2.
6. **Checking has no hot spot.** Measured per declaration with instruction counters: the most expensive
   declaration of mathlib is 0.35 % of nano-lean's checking cost, the top 200 are 5.6 %, the top 1,000 are
   12 %, the top 10,000 are 36 %; half the cost is in 23,301 declarations and the other half in 715,866.
   gruszka, sokonanoda and rpylean show the same curve. Theorems are 89 % of the cost, definitions 10 %,
   inductive types under 1 %; the compiler-generated `_proof_N` theorems alone are 18.5 %; section 5.
7. **A checker that wants to be first must average under 434,000 instructions per constant** (median
   78,000) after reading 112 million lines at about 365 instructions each. Per-declaration fixed costs and
   run-time compilation have to fit into that; section 6.
8. **Instruction count is not time.** The JVM baseline with the Graal JIT instead of C2 takes 1.4 % more CPU
   time on mathlib and 28.4 % more instructions (7.0 % more on init). Choices for RLVM have to be compared by
   instruction count, on mathlib.

---

## 1. What was measured, and how

**Checkers.** The ten baselines built by `bench/baselines/build-checker.sh` from the arena's yaml recipes
(`bench/README.md`, "Baseline checkers"), run through the generated wrappers `bench/baselines/NAME.sh`, i.e. the
yaml's `run:` line through `sh -c` with cwd = the build tree, `$IN` = the export, `LC_ALL=C`:

| baseline | version | run line: workers | note |
|---|---|---|---|
| nano-lean | v0.4.2 `5cd4a25` | `nl-fast -q -j 4`: 4 | arena #1 on mathlib |
| gruszka | `ebc80dc` | `scripts/arena-run "$IN" 4`: `susz pack`, then 4 workers | arena #2 |
| sokonanoda | `9a021b6` | `--threads 4`: 4 | arena #3 |
| rpylean | v2026.11.11 `bbf354d` | `./rpylean-c check`: 1 | arena #4; RPython, meta-tracing JIT |
| mathgraph | `flash` `78c7502` | `num_threads` 2 | accepts 8 bad proofs on the small tests, so not a ranking competitor |
| pestka | `cea66e6` | `bin/arena-run "$IN" 4` | |
| nanoda | `0da9f98` | `num_threads` 4 | |
| tenet-jvm-c2 | 0.1.5 `4cc6e20` | `bin/tenet-jvm`: 1 + GC and JIT threads | the project's GraalVM CE 25 with `-XX:-UseJVMCICompiler` (C2): the closest to the arena's stock OpenJDK |
| tenet-jvm | 0.1.5 `4cc6e20` | the same | the same JVM with its default Graal JIT; not what the arena runs, but what a GraalVM-hosted checker gets |
| official | Lean v4.35.0 | `leanchecker --from-export`: 1 | the C++ kernel |

**Corpora.** `work/exports/init.ndjson` (348 MB, 59,979 constants), `std.ndjson` (584 MB, 103,885 constants),
`mathlib.ndjson` (6,326,141,213 bytes, 112,081,718 lines, 107,037,712 expressions, 739,167 constants; sha256
`f749dfd084fc…`, equal to the arena's).

**One run** = one fresh container, nothing else in it:

```bash
bench/arena-docker.sh --arena-limits --no-network bench/measure.sh --timeout T \
    --in work/exports/CORPUS.ndjson -o RAW.json -c 'bench/baselines/NAME.sh "$IN"'
```

- `--arena-limits`: 8 performance cores (CPUs 5-9,15-17) and 16 GB, the size of the arena runner;
  `--no-network`.
- `bench/measure.sh` is the arena's measurement (`lka.py`, `measure_perf_with_fallback`): `perf stat` over the
  whole process tree, all threads and children, user-space instructions; `virtual time = instructions / 6e9`,
  the arena's ranking metric; CPU time = perf's task-clock; wall time = perf's duration_time; max RSS from GNU
  time.
- Timeout `T` = 2 x the arena's (init 370 s, std 600 s, mathlib 4120 s); no run came near it.
- Runs of the series were made one after the other (`bench/baselines/big-all.sh`): 3 runs of every baseline on
  init and std; on mathlib 3 runs of the six fast checkers and 1 run of the four slow ones. The single run of
  the official kernel on mathlib (single-threaded, deterministic to 0.05 %) ran next to the series on two
  performance cores of its own (CPUs 18,19, 16 GB), to save half an hour.
- The figure quoted for a (checker, corpus) is the **median** over its runs; `spread` = (max - min) / median.

**Caveat on times.** Other agents were using the host during the whole series (1-minute load average between
5 and 35 on 20 cores, at times on the same 8 cores). Instruction counts do not depend on
that (the three mathlib runs of nano-lean differ by 0.04 % while their wall time went from 25.5 s to 42.1 s);
wall and CPU times do. The wall column therefore gives the minimum and the median; read it as "no better than".

---

## 2. Results per corpus

`x fastest` = instructions over those of the fastest checker on that corpus. `arena x86-64 virtual` is the
published figure (results of 2026-11-23, `work/arena-site/results.json`); `aarch64/x86-64` = our count over the
published count (section 3).

<!-- BEGIN big:corpora -->
### init  (348 MB, 6,495,296 lines, arena timeout 370 s)

| checker | runs | status | instructions | virtual time | x fastest | spread | CPU s | wall s (min / median) | max RSS MiB | arena x86-64 virtual | aarch64/x86-64 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| nano-lean | 3 | accepted | 22.3e9 | 3.71 s | 1.00 | 0.18 % | 4.5 | 1.3 / 1.3 | 956 | 4.05 s | 0.915 |
| gruszka | 3 | accepted | 22.6e9 | 3.76 s | 1.01 | 0.57 % | 2.8 | 0.8 / 0.8 | 379 | 4.14 s | 0.909 |
| sokonanoda | 3 | accepted | 27.7e9 | 4.62 s | 1.25 | 0.72 % | 4.5 | 1.3 / 1.4 | 764 | 4.81 s | 0.962 |
| rpylean | 3 | accepted | 30.2e9 | 5.03 s | 1.36 | 0.00 % | 4.9 | 4.6 / 4.9 | 365 | 5.42 s | 0.927 |
| mathgraph | 3 | accepted | 43.2e9 | 7.20 s | 1.94 | 0.50 % | 8.1 | 3.9 / 4.2 | 838 | 7.44 s | 0.967 |
| pestka | 3 | accepted | 58.6e9 | 9.77 s | 2.63 | 0.02 % | 11.5 | 3.4 / 3.4 | 538 | 10.91 s | 0.896 |
| nanoda | 3 | accepted | 215.4e9 | 35.89 s | 9.67 | 0.06 % | 26.0 | 8.5 / 8.6 | 529 | 37.30 s | 0.962 |
| tenet-jvm-c2 | 3 | accepted | 230.9e9 | 38.48 s | 10.37 | 4.95 % | 32.3 | 26.1 / 28.5 | 4,550 | 37.28 s | 1.032 |
| tenet-jvm | 3 | accepted | 247.0e9 | 41.17 s | 11.10 | 1.81 % | 35.5 | 25.4 / 30.6 | 4,639 | 37.28 s | 1.105 |
| official | 3 | accepted | 381.2e9 | 63.53 s | 17.12 | 0.06 % | 35.4 | 35.1 / 35.4 | 604 | 66.92 s | 0.949 |

### std  (584 MB, 10,611,507 lines, arena timeout 600 s)

| checker | runs | status | instructions | virtual time | x fastest | spread | CPU s | wall s (min / median) | max RSS MiB | arena x86-64 virtual | aarch64/x86-64 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| gruszka | 3 | accepted | 36.5e9 | 6.08 s | 1.00 | 0.23 % | 5.7 | 1.8 / 1.9 | 484 | 6.72 s | 0.905 |
| nano-lean | 3 | accepted | 37.0e9 | 6.16 s | 1.01 | 0.17 % | 9.2 | 3.3 / 3.6 | 1,158 | 6.71 s | 0.918 |
| sokonanoda | 3 | accepted | 46.4e9 | 7.73 s | 1.27 | 0.74 % | 8.8 | 3.1 / 3.3 | 887 | 8.06 s | 0.959 |
| rpylean | 3 | accepted | 51.2e9 | 8.54 s | 1.40 | 0.01 % | 10.7 | 10.2 / 10.7 | 520 | 9.19 s | 0.929 |
| mathgraph | 3 | accepted | 72.2e9 | 12.04 s | 1.98 | 0.24 % | 14.9 | 7.6 / 7.7 | 1,013 | 12.45 s | 0.967 |
| pestka | 3 | accepted | 96.9e9 | 16.15 s | 2.65 | 0.01 % | 21.6 | 6.7 / 6.7 | 727 | 17.95 s | 0.899 |
| nanoda | 3 | accepted | 332.4e9 | 55.40 s | 9.11 | 0.08 % | 46.4 | 15.1 / 15.5 | 764 | 57.60 s | 0.962 |
| tenet-jvm-c2 | 3 | accepted | 379.0e9 | 63.16 s | 10.38 | 0.83 % | 53.4 | 44.8 / 47.3 | 4,996 | 60.20 s | 1.049 |
| tenet-jvm | 3 | accepted | 393.3e9 | 65.56 s | 10.78 | 0.83 % | 60.9 | 54.8 / 54.9 | 4,963 | 60.20 s | 1.089 |
| official | 3 | accepted | 632.0e9 | 105.3 s | 17.32 | 0.04 % | 68.5 | 65.9 / 68.6 | 956 | 110.9 s | 0.950 |

### mathlib  (6,326 MB, 112,081,718 lines, arena timeout 4120 s)

| checker | runs | status | instructions | virtual time | x fastest | spread | CPU s | wall s (min / median) | max RSS MiB | arena x86-64 virtual | aarch64/x86-64 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| nano-lean | 3 | accepted | 361.3e9 | 60.22 s | 1.00 | 0.04 % | 107.2 | 25.5 / 36.9 | 5,157 | 65.81 s | 0.915 |
| gruszka | 3 | accepted | 418.7e9 | 69.78 s | 1.16 | 0.30 % | 75.9 | 18.1 / 22.3 | 2,592 | 77.00 s | 0.906 |
| sokonanoda | 3 | accepted | 504.3e9 | 84.05 s | 1.40 | 0.11 % | 113.2 | 30.4 / 35.7 | 5,228 | 87.90 s | 0.956 |
| rpylean | 3 | accepted | 544.6e9 | 90.77 s | 1.51 | 0.05 % | 131.8 | 117.9 / 132.2 | 4,545 | 96.35 s | 0.942 |
| mathgraph | 3 | accepted | 880.4e9 | 146.7 s | 2.44 | 0.01 % | 156.3 | 78.5 / 80.6 | 6,543 | 152.0 s | 0.966 |
| pestka | 3 | accepted | 1.340e12 | 223.3 s | 3.71 | 0.01 % | 348.4 | 89.0 / 125.9 | 3,627 | 245.9 s | 0.908 |
| nanoda | 1 | accepted | 5.533e12 | 922 s (15.4 min) | 15.31 | - | 973.4 | 290.8 | 6,053 | 962 s (16.0 min) | 0.958 |
| tenet-jvm-c2 | 1 | accepted | 9.313e12 | 1552 s (25.9 min) | 25.77 | - | 1144.5 | 1064.0 | 12,134 | 1633 s (27.2 min) | 0.951 |
| official | 1 | accepted | 11.424e12 | 1904 s (31.7 min) | 31.62 | - | 1752.2 | 1757.2 | 10,167 | 2016 s (33.6 min) | 0.945 |
| tenet-jvm | 1 | accepted | 11.960e12 | 1993 s (33.2 min) | 33.10 | - | 1160.5 | 1082.8 | 12,558 | 1633 s (27.2 min) | 1.221 |
<!-- END big:corpora -->

Reading the tables:

- **The target.** nano-lean checks mathlib in 361.3e9 instructions = 60.2 virtual seconds here (65.8 s
  published). gruszka needs 1.16 x that, sokonanoda 1.40 x, rpylean 1.51 x. These four are the arena's
  top four, in the same order, with nearly the same gaps (section 3).
- **Init and std are not mathlib in small.** gruszka and nano-lean are within 1.5 % of each other on init and
  std (gruszka ahead on std) but 16 % apart on mathlib; sokonanoda is 1.25-1.27 x the fastest on the small
  corpora and 1.40 x on mathlib, rpylean 1.36-1.40 x and 1.51 x. Decide on mathlib (or its slices), not
  on Init.
- **Scale.** mathlib costs nano-lean 16.2 times its Init count, for 18.2 times the bytes and 12.3 times the
  constants.
- **The JVM baseline is far away.** tenet-jvm with C2 costs 10.4 x the fastest on init, 10.4 x on std and
  25.8 x on mathlib (9.31e12, 25.9 virtual minutes). Its max RSS is
  4.4-4.9 GiB on init and std and 11.9 GiB on mathlib, where the Rust leaders need 2.5-5.1 GiB.
- **Graal JIT against C2**, same bytecode, same JVM, default settings of `bin/tenet-jvm`: +7.0 % instructions
  on init (247.0e9 against 230.9e9), +3.8 % on std and +28.4 % on mathlib (11.96e12 against
  9.31e12; one run each there, a JVM run varies by 1-2 %). On mathlib the two runs took about the same CPU
  time (1,160 s and 1,144 s) and wall time (1,083 s and 1,064 s): the Graal run retired 28 % more instructions
  in 1.4 % more CPU time (10.3 against 8.1 instructions per nanosecond). The arena ranks by instructions, so by its
  metric the Graal JIT is the worse choice for this checker although the clock sees no difference. The cause
  was not investigated.
- **The official kernel** needs 11.42e12 (31.7 virtual minutes, 1757 s of wall time on one X925 core, 9.9 GiB):
  31.6 x nano-lean. nanoda, the reference Rust port of the same algorithm, 5.53e12 (15.3 x).
- **Wall time** of the least-disturbed mathlib run with 4 workers on 8 cores: gruszka 18 s, nano-lean 26 s,
  sokonanoda 30 s; rpylean (one thread) 118 s.

---

## 3. aarch64 against the published x86-64 numbers

The arena runs on x86-64, so its published counts are not ours. The ratio ours/published per checker, on the
230 small tests (totals of `bench/baselines/validate.sh`, previous infra step) and on the three big corpora:

<!-- BEGIN big:arch -->
### aarch64 (this host) / x86-64 (arena) instruction counts

| checker | 230 small tests | init | std | mathlib |
|---|---:|---:|---:|---:|
| nano-lean | 0.934 | 0.915 | 0.918 | 0.915 |
| gruszka | 0.926 | 0.909 | 0.905 | 0.906 |
| sokonanoda | 0.933 | 0.962 | 0.959 | 0.956 |
| rpylean | 1.006 | 0.927 | 0.929 | 0.942 |
| mathgraph | 0.991 | 0.967 | 0.967 | 0.966 |
| pestka | 0.873 | 0.896 | 0.899 | 0.908 |
| nanoda | 0.933 | 0.962 | 0.962 | 0.958 |
| tenet-jvm-c2 | 0.892 | 1.032 | 1.049 | 0.951 |
| tenet-jvm | 1.539 | 1.105 | 1.089 | 1.221 |
| official | 0.944 | 0.949 | 0.950 | 0.945 |
<!-- END big:arch -->

- **Native checkers: 0.90 to 0.97, stable per checker across the three big corpora** (nano-lean 0.915-0.918,
  gruszka 0.905-0.909, sokonanoda 0.956-0.962, nanoda 0.958-0.962, official 0.944-0.950, mathgraph 0.966-0.967,
  pestka 0.896-0.908, rpylean 0.927-0.942). The same program executes 3-10 % fewer instructions on aarch64;
  compilers, PGO profiles and `-C target-cpu=native` differ too (`bench/README.md`, "Differences from the
  arena's build").
- **The ranking carries over.** gruszka / nano-lean on mathlib is 1.159 here and 1.170 published; sokonanoda /
  nano-lean 1.396 and 1.336; rpylean / nano-lean 1.507 and 1.464.
- **The JVM checker has no stable ratio**: tenet-jvm with C2 is 1.03 on init, 1.05 on std and 0.951 on
  mathlib. The comparison is not clean either: the arena's JVM is the stock OpenJDK of its nix flake, ours is
  GraalVM CE 25 with its Graal compiler switched off, and what GC and JIT threads execute depends on timing
  and on the heap the JVM chooses. It is the only JVM data point there is, and it does not support assuming
  the native checkers' 0.92 for RLVM. (The 0.892 and 1.539 in the small-tests column are one test,
  `bugs/nat-shift-huge`, where tenet-jvm runs out of memory; without it 0.96 and 1.09.)
- **Consequence for RLVM's target.** nano-lean's published count is 394.9e9. If RLVM's ratio (count here /
  count on the arena) is anywhere between the lowest and the highest seen above on a big corpus for a baseline
  in the arena's configuration (0.90 to 1.05), nano-lean's arena count corresponds to between 355e9 and 415e9 here.
  Until RLVM has been measured on an x86-64 machine with these same scripts, read **355e9 here
  (59 virtual s) as "ahead of nano-lean whatever the ratio"** and 361e9 as "ahead on this machine".

---

## 4. Thread scaling

nano-lean, gruszka and sokonanoda run with 4 workers on the arena. The same three on mathlib with 1, 2 and 8
workers (one run each; the 4-worker row is the median of the three arena-configuration runs):

<!-- BEGIN big:threads -->
### thread scaling on mathlib

| checker | threads | instructions | virtual time | vs 1 thread | CPU s | wall s | max RSS MiB |
|---|---:|---:|---:|---:|---:|---:|---:|
| nano-lean | 1 | 361.0e9 | 60.16 s | 1.000 | 90.5 | 89.9 | 5,157 |
| nano-lean | 2 | 361.2e9 | 60.20 s | 1.001 | 82.5 | 43.7 | 5,157 |
| nano-lean | 4 | 361.3e9 | 60.22 s | 1.001 | 107.2 | 36.9 | 5,157 |
| nano-lean | 8 | 361.9e9 | 60.31 s | 1.002 | 99.1 | 17.9 | 5,452 |
| gruszka | 1 | 414.6e9 | 69.10 s | 1.000 | 61.3 | 58.1 | 2,211 |
| gruszka | 2 | 417.0e9 | 69.50 s | 1.006 | 71.7 | 36.8 | 2,301 |
| gruszka | 4 | 418.7e9 | 69.78 s | 1.010 | 75.9 | 22.3 | 2,592 |
| gruszka | 8 | 421.6e9 | 70.26 s | 1.017 | 68.2 | 14.4 | 2,831 |
| sokonanoda | 1 | 487.2e9 | 81.19 s | 1.000 | 95.2 | 94.4 | 4,630 |
| sokonanoda | 2 | 497.2e9 | 82.86 s | 1.021 | 90.3 | 48.1 | 4,759 |
| sokonanoda | 4 | 504.3e9 | 84.05 s | 1.035 | 113.2 | 35.7 | 5,228 |
| sokonanoda | 8 | 510.7e9 | 85.12 s | 1.048 | 106.6 | 19.7 | 5,607 |
<!-- END big:threads -->

- **More workers cost almost nothing in the arena's metric.** From 1 to 4 workers nano-lean's count rises by
  0.09 % (361.0e9 to 361.3e9), gruszka's by 1.0 %, sokonanoda's by 3.5 %; from 1 to 8 workers by 0.24 %, 1.7 %
  and 4.8 %. The instruction count of these checkers is, to within a few percent, their single-threaded work.
- **What the workers buy is wall time**, which the arena shows but does not rank by: nano-lean 90 s with one
  worker, 18 s with eight; gruszka 58 s and 14 s; sokonanoda 94 s and 20 s (a busy host; see section 1).
- Memory grows little: nano-lean 5.0 GiB with 1 to 4 workers and 5.3 GiB with 8; gruszka 2.2 to 2.8 GiB;
  sokonanoda 4.5 to 5.5 GiB.
- The three leaders declare 4 threads on an 8-vCPU runner. With 8 workers they finished mathlib here in
  14-20 s instead of 18-30 s (least-disturbed runs), for 0.2-1.3 % more instructions than with 4.
- For RLVM: checking declarations in parallel is free in the ranking metric **if** idle workers sleep instead
  of spinning and workers do not repeat each other's work. Section 5.6 shows that this holds per declaration,
  not only in total. What is not free for a JVM is the runtime's own parallelism: GC and JIT compiler threads
  are sized by the CPUs the JVM sees, and every instruction they execute is counted.

---

## 5. Where the instructions go on mathlib

### 5.1 How the profiles were obtained

Four of the baselines can print something per declaration. None of them can count instructions per declaration
on Linux, so the counts were added from outside, without changing the checkers:

| checker | switch (found in its source) | what it prints |
|---|---|---|
| nano-lean | `nl-fast --trace` (`src/fast.rs`) | `start IDX NAME` and `end IDX elapsed T arena N bytes` on stderr around every declaration |
| nano-lean | `nl-fast --import-only` | stops after reading the export |
| gruszka | `SK_DECL_INSTR=1` (`src/main.rs`) | `DI<TAB>index<TAB>name<TAB>instructions<TAB>ns<TAB>ticks …` after every declaration; the instruction field is 0 on Linux (it is read from a macOS-only API) |
| sokonanoda | `--verbose`, `--parse-only` (`src/main.rs`, `src/report.rs`) | one line per declaration with kind and wall time in ms (buffered on stdout) |
| rpylean | `check --print jsonl` (`rpylean/cli.py`) | one JSON object per declaration: `elapsed`, `gc_elapsed`, `bytes_allocated`, `live_memory`, `peak_growth` |
| official, nanoda, pestka, mathgraph | none for exports | — |
| tenet-jvm | `TENET_TRACE=1` prints the name before each item, no cost | not used |

`bench/baselines/instrtap.c` is a small `LD_PRELOAD` library that interposes `write` and `fwrite`: whenever
the checker starts one of those lines, it first prints `#I <tid> <count>`, the calling thread's user-space
instruction count from its own `perf_event_open` counter (INST_RETIRED, user mode, one event per PMU of this
big.LITTLE host, summed). That is the counter `perf stat -e instructions:u` uses, per thread. The difference
of two such lines of a thread is the cost of what the thread did in between. Self-test
(`profile-mathlib.sh selftest`): a loop of 1,200,000,000 instructions between a `start` and an `end` line reads
as 1,200,000,522 on each of 4 threads.

What an interval contains differs per checker, which matters for the small declarations:

- **nano-lean**: `start` to `end` of the same declaration: the check and nothing else (a few hundred
  instructions of printing). This is the cleanest profile and the reference below.
- **gruszka**: from one `DI` line to the next of the same worker: the check plus printing the previous `DI`
  line, which is long. The same run without `SK_DECL_INSTR` and without the library is
  22.8e9 instructions cheaper, i.e. about 31,600 per declaration; that
  constant is subtracted from every declaration (raw values are kept in the `.tsv.gz`). A "declaration" of
  gruszka is an export record: an inductive block counts once (721,845 records against 739,167 constants).
- **rpylean** reads and checks declaration by declaration, so an interval is "read this declaration's lines,
  check it", plus whatever the runtime does in between: collections, table growth, JIT compilation. A second
  view uses rpylean's own `elapsed` (wall time of the check alone).
- **sokonanoda**: wall time only (its lines are buffered, so the library cannot attribute them). Inductive
  types, constructors and recursors are reported with 0 ms.

All profile runs used one worker (4-worker runs in 5.6) and were measured as a whole with `bench/measure.sh`
as well; the instrumented single-worker runs cost 1.1 % more than the
plain single-worker runs for nano-lean (364.9e9 against 361.0e9), 0.9 % for sokonanoda (491.4e9 against
487.2e9), 1.9 % for rpylean (554.8e9 against 544.6e9) and 5.9 % for gruszka (407.8e9 against 385.0e9 for
`gruszka export.susz 1` alone).

### 5.2 Reading the export against checking

<!-- BEGIN profile:split -->
#### Instructions spent reading mathlib.ndjson, and what is left for checking

| checker | arena run (4 workers) | reading the export | share | checking | how measured |
|---|---:|---:|---:|---:|---:|
| nano-lean | 361.32e9 | 40.88e9 | 11.3 % | 320.44e9 | `nl-fast -q -j 4 --import-only` |
| gruszka | 418.66e9 | 37.49e9 = pack 29.60e9 + load 7.90e9 | 9.0 % | 381.16e9 | `susz pack --no-index` measured alone; load = the 1-worker run outside declarations |
| sokonanoda | 504.31e9 | 36.91e9 | 7.3 % | 467.40e9 | `sokonanoda --parse-only --threads 4` |
| rpylean | 544.61e9 | interleaved | 19.0 % of wall time | - | wall 124 s, of which checking 100 s (GC inside checks 3.1 s) |
<!-- END profile:split -->

- **Reading mathlib costs the three Rust leaders 37-41e9 instructions**: 7-11 % of their totals, 5.8-6.5
  instructions per byte of NDJSON, 330-365 per line (6,326,141,213 bytes, 112,081,718 lines). gruszka's packer
  alone (`susz pack`, NDJSON to its binary Susz format) is 29.6e9 = 4.7 per byte and 264 per line; loading the
  packed file into the environment is another 7.9e9. The parts add up: pack + `gruszka export.susz 4` =
  29.60e9 + 389.11e9 = 418.71e9 against 418.66e9 for the arena run line.
- At nano-lean's level, reading is one ninth of the whole budget. A reader that costs 20 instructions per
  byte would spend 126e9 instructions on mathlib, a third of nano-lean's whole run.
- nano-lean reads the whole file before it checks anything (5.2 s on one thread, 1.2 GB/s) and reaches its
  maximum RSS there: `--import-only` already peaks at 5,157 MiB, the same as the full run.
- rpylean interleaves reading and checking; 19 % of its wall time lies outside the per-declaration checks
  (reading, collections between declarations, table growth); inside the checks rpylean itself reports 3.1 s
  of collection time.

### 5.3 The distribution is flat

<!-- BEGIN profile:distribution -->
#### Cumulative distribution: share of the per-declaration total in the N most expensive declarations

| profile | metric | declarations | total | top 1 | top 10 | top 100 | top 200 | top 1,000 | top 10,000 | top 100,000 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| nano-lean.j1 | instructions | 739,167 | 321.55e9 | 0.35 % | 1.25 % | 4.1 % | 5.6 % | 12.2 % | 36.0 % | 77.5 % |
| gruszka.w1 | instructions | 721,845 | 377.10e9 | 0.50 % | 1.49 % | 4.2 % | 5.7 % | 12.1 % | 34.6 % | 75.4 % |
| rpylean | instructions | 739,167 | 516.35e9 | 0.58 % | 2.36 % | 7.0 % | 8.5 % | 14.4 % | 35.5 % | 73.2 % |
| rpylean (check wall time) | wall time | 739,167 | 100.5 s | 0.88 % | 1.66 % | 3.5 % | 4.6 % | 9.8 % | 28.8 % | 70.0 % |
| sokonanoda.t1 | wall time | 739,167 | 80.9 s | 1.06 % | 3.23 % | 5.5 % | 6.7 % | 11.8 % | 31.2 % | 71.8 % |

#### Declarations needed for a share of the total, and cost per declaration

| profile | 50 % | 80 % | 90 % | 95 % | 99 % | mean | median | p90 | p99 | p99.9 | max |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| nano-lean.j1 | 23,301 | 114,626 | 212,183 | 316,765 | 531,766 | 0.44e6 | 77,674 | 0.83e6 | 6.04e6 | 22.61e6 | 1.12e9 |
| gruszka.w1 | 26,022 | 127,997 | 231,773 | 335,826 | 524,676 | 0.52e6 | 0.11e6 | 1.01e6 | 6.77e6 | 25.77e6 | 1.89e9 |
| rpylean | 27,011 | 144,712 | 262,317 | 381,596 | 596,527 | 0.70e6 | 0.17e6 | 1.28e6 | 8.30e6 | 31.86e6 | 3.00e9 |
| rpylean (check wall time) | 37,786 | 159,777 | 267,493 | 370,665 | 558,522 | 0.14 ms | 0.04 ms | 0.29 ms | 1.54 ms | 5.78 ms | 886.52 ms |
| sokonanoda.t1 | 33,210 | 149,337 | 254,458 | 354,184 | 535,215 | 0.11 ms | 0.03 ms | 0.22 ms | 1.27 ms | 4.45 ms | 860.14 ms |
<!-- END profile:distribution -->

<!-- BEGIN profile:classes -->
#### nano-lean.j1: declarations by cost class

| instructions per declaration | declarations | share of declarations | share of cost |
|---|---:|---:|---:|
| 1e+03..1e+04 | 46,732 | 6.32 % | 0.1 % |
| 1e+04..1e+05 | 364,127 | 49.26 % | 4.5 % |
| 1e+05..1e+06 | 266,630 | 36.07 % | 27.0 % |
| 1e+06..1e+07 | 58,362 | 7.90 % | 46.5 % |
| 1e+07..1e+08 | 3,273 | 0.44 % | 19.1 % |
| 1e+08..1e+09 | 42 | 0.01 % | 2.4 % |
| 1e+09..1e+10 | 1 | 0.00 % | 0.3 % |

#### gruszka.w1: declarations by cost class

| instructions per declaration | declarations | share of declarations | share of cost |
|---|---:|---:|---:|
| 0e+00..1e+03 | 463 | 0.06 % | 0.0 % |
| 1e+03..1e+04 | 59,884 | 8.30 % | 0.1 % |
| 1e+04..1e+05 | 276,774 | 38.34 % | 3.4 % |
| 1e+05..1e+06 | 312,066 | 43.23 % | 27.2 % |
| 1e+06..1e+07 | 68,692 | 9.52 % | 46.2 % |
| 1e+07..1e+08 | 3,915 | 0.54 % | 20.1 % |
| 1e+08..1e+09 | 50 | 0.01 % | 2.6 % |
| 1e+09..1e+10 | 1 | 0.00 % | 0.5 % |
<!-- END profile:classes -->

- **There is no hot spot.** The most expensive declaration of mathlib is 0.35 % of nano-lean's checking cost
  (1.12e9 instructions, 0.19 virtual seconds). The top 10 are 1.25 %, the top 200 are 5.6 %, the top 1,000 are
  12.2 %, the top 10,000 are 36 %. Half of the cost is in the 23,301 most expensive declarations (3.2 % of
  them), 90 % in 212,183 (29 %).
- **All four checkers see the same shape**, whatever their design: the top 200 are 5.7 % for gruszka, 6.7 % for
  sokonanoda (wall time) and 4.6 % for rpylean (its own check time).
- **The bulk is in mid-size declarations.** For nano-lean, 58,362 declarations of 1e6 to 1e7 instructions
  carry 46.5 % of the checking cost, 266,630 of 1e5 to 1e6 carry 27.0 %, and 3,273 of 1e7 to 1e8 carry 19.1 %.
  The 410,859 declarations below 1e5 instructions (56 % of all) carry 4.6 %; the 43 above 1e8 carry 2.7 %.
- Mean cost per constant 435,000 instructions, median 78,000 (nano-lean); 522,000 and 114,000 for gruszka's
  721,845 records.
- mathlib is therefore 739,167 small, independent jobs, and **per-declaration fixed cost is a first-order
  term**: 10,000 instructions of set-up or tear-down per declaration are 7.4e9, 2 % of nano-lean's total; the
  median declaration costs nano-lean only eight times that.

### 5.4 By kind and by name

<!-- BEGIN profile:kinds -->
#### Cost by kind of declaration

| kind | nano-lean.j1: declarations | nano-lean.j1: share | nano-lean.j1: mean | gruszka.w1: declarations | gruszka.w1: share | gruszka.w1: mean | sokonanoda.t1: declarations | sokonanoda.t1: share | sokonanoda.t1: mean |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| theorem | 524,582 | 88.76 % | 0.54e6 | 524,582 | 89.35 % | 0.64e6 | 524,582 | 89.96 % | 0.14 ms |
| def | 187,271 | 10.51 % | 0.18e6 | 187,271 | 9.77 % | 0.20e6 | 187,271 | 10.03 % | 0.04 ms |
| inductive | 7,162 | 0.70 % | 0.31e6 | 7,129 | 0.87 % | 0.46e6 | 7,162 | 0.00 % | 0.00 ms |
| opaque | 2,855 | 0.01 % | 17,126 | 2,855 | 0.01 % | 18,761 | 2,855 | 0.02 % | 0.01 ms |
| ctor | 10,006 | 0.01 % | 3,627 | - | - | - | 10,006 | 0.00 % | 0.00 ms |
| recr | 7,283 | 0.01 % | 3,528 | - | - | - | 7,283 | 0.00 % | 0.00 ms |
| quot | 4 | 0.00 % | 0.10e6 | 4 | 0.00 % | 29,823 | 4 | 0.00 % | 0.00 ms |
| axiom | 4 | 0.00 % | 12,030 | 4 | 0.00 % | 48,745 | 4 | 0.00 % | 0.00 ms |
<!-- END profile:kinds -->

<!-- BEGIN profile:names -->
#### nano-lean.j1: cost by last name component, for the suffixes Lean generates (numbers as N)

| last component | declarations | share of cost | mean instructions |
|---|---:|---:|---:|
| `(other)` | 533,189 | 75.60 % | 0.46e6 |
| `_proof_N` | 100,876 | 18.46 % | 0.59e6 |
| `noConfusion` | 9,864 | 1.22 % | 0.40e6 |
| `_unary` | 459 | 0.81 % | 5.70e6 |
| `match_N` | 14,320 | 0.61 % | 0.14e6 |
| `eq_def` | 767 | 0.57 % | 2.41e6 |
| `congr_simp` | 5,389 | 0.50 % | 0.30e6 |
| `eq_N` | 17,854 | 0.41 % | 73,592 |
| `injEq` | 4,380 | 0.33 % | 0.24e6 |
| `_mutual` | 19 | 0.28 % | 48.10e6 |
| `noConfusionType` | 4,588 | 0.28 % | 0.20e6 |
| `casesOn` | 6,990 | 0.27 % | 0.12e6 |
| `inj` | 4,415 | 0.19 % | 0.14e6 |
| `sizeOf_spec` | 5,552 | 0.11 % | 65,517 |

#### nano-lean.j1: cost by first name component

| namespace | declarations | share of cost |
|---|---:|---:|
| `_private` | 109,275 | 18.49 % |
| `CategoryTheory` | 79,060 | 12.97 % |
| `Std` | 41,881 | 3.68 % |
| `AlgebraicGeometry` | 7,788 | 2.66 % |
| `Algebra` | 3,743 | 1.98 % |
| `MeasureTheory` | 12,159 | 1.92 % |
| `Lean` | 54,168 | 1.16 % |
| `Polynomial` | 5,101 | 1.10 % |
| `LinearMap` | 3,688 | 1.09 % |
| `Matrix` | 5,146 | 1.04 % |
| `Module` | 3,195 | 0.97 % |
| `List` | 9,171 | 0.87 % |
| `Ideal` | 2,764 | 0.83 % |
| `TensorProduct` | 1,268 | 0.80 % |
<!-- END profile:names -->

- **Theorems are 89 % of the checking cost** in all three profiles, definitions 10 %, everything else together
  under 1 %. Inductive types with their constructors and recursors are 0.7 % for nano-lean and 0.9 % for gruszka
  (sokonanoda does not time them): the speed of the inductive-type machinery does not show in the ranking, only
  its correctness does.
- An average theorem costs nano-lean 0.54e6 instructions, an average definition 0.18e6.
- **Compiler-generated proofs are a large share**: the 100,876 `_proof_N` constants (proof terms that Lean
  abstracts out of definitions and tactic blocks) are 18.5 % of the cost. `noConfusion` is 1.2 %; the other
  generated families (`match_N`, `eq_N`, `injEq`, `sizeOf_spec`, `casesOn`, `congr_simp`, ...) are below 1 %
  each, about 5 % together.
- By namespace: `_private` (mostly those `_proof_N` and private lemmas) 18.5 %; `CategoryTheory` 13.0 % with
  10.7 % of the declarations; `Std` 3.7 %; `AlgebraicGeometry` 2.7 % with 1.1 % of the declarations. Lean's
  own `Lean.*` (54,168 declarations) is 1.2 %.

### 5.5 The most expensive declarations

The full lists (200 per profile, with share and cumulative share) are in
`work/baselines/mathlib-profile.json` (`profiles.<name>.top`) and side by side in
`work/baselines/mathlib-profile.top200.tsv`; every declaration is in `mathlib-profile.<name>.tsv.gz`.

<!-- BEGIN profile:top -->
#### The 40 most expensive declarations for nano-lean (1 worker), with their place elsewhere

| # | declaration | instructions | share | cumulative | gruszka.w1 | sokonanoda.t1 (wall) | rpylean (wall) |
|---|---:|---:|---:|---:|---:|---:|---:|
| 1 | `_private.Mathlib.AlgebraicGeometry.EllipticCurve.Affine.AddSubMap.0.WeierstrassCurve.Affine.Point.sym2x_add_sub_eq_addSubMap_sym2x._proof_1_8` | 1.12e9 | 0.348 % | 0.35 % | #1 1.89e9 | #1 860 ms | #1 887 ms |
| 2 | `Std.Tactic.BVDecide.BVExpr.bitblast.goCache_Inv_of_Inv._mutual` | 0.53e9 | 0.164 % | 0.51 % | #5 0.42e9 | #9 89 ms | #3 140 ms |
| 3 | `_private.Init.Data.String.Lemmas.Lossy.0.String.fromUTF8Lossy_surrogate_append._proof_1_10` | 0.38e9 | 0.118 % | 0.63 % | #3 0.43e9 | #2 412 ms | #10 48 ms |
| 4 | `CategoryTheory.Functor.IsDenseSubsite.isIso_ranCounit_app_of_isDenseSubsite` | 0.33e9 | 0.102 % | 0.73 % | #6 0.41e9 | #15 43 ms | #84 14 ms |
| 5 | `CategoryTheory.Limits.colimitLimitToLimitColimit_surjective` | 0.31e9 | 0.097 % | 0.83 % | #2 0.71e9 | #14 45 ms | #77 15 ms |
| 6 | `Std.Tactic.BVDecide.BVExpr.bitblast.goCache_decl_eq._mutual` | 0.31e9 | 0.095 % | 0.92 % | #15 0.21e9 | #16 39 ms | #12 45 ms |
| 7 | `_private.Mathlib.Combinatorics.SimpleGraph.Regularity.Chunk.0.SzemerediRegularity.edgeDensity_star_not_uniform._proof_1_10` | 0.30e9 | 0.093 % | 1.02 % | #7 0.39e9 | #7 101 ms | #7 65 ms |
| 8 | `_private.Mathlib.NumberTheory.EllipticDivisibilitySequence.0.IsEllipticNet.atomRel_of_even_odd._proof_1_15` | 0.30e9 | 0.092 % | 1.11 % | #4 0.42e9 | #8 93 ms | #9 49 ms |
| 9 | `_private.Mathlib.LinearAlgebra.RootSystem.Finite.G2.0.RootPairing.EmbeddedG2.isOrthogonal_short_and_long_aux._proof_1_1` | 0.23e9 | 0.073 % | 1.18 % | #14 0.22e9 | #21 31 ms | #26 26 ms |
| 10 | `Ideal.Quotient.stabilizerHom_surjective_of_profinite` | 0.23e9 | 0.072 % | 1.25 % | #17 0.20e9 | #24 29 ms | #160 11 ms |
| 11 | `_private.Mathlib.NumberTheory.EllipticDivisibilitySequence.0.IsEllipticNet.atomRel_of_even_odd._proof_1_13` | 0.22e9 | 0.068 % | 1.32 % | #11 0.26e9 | #11 62 ms | #11 46 ms |
| 12 | `CategoryTheory.StrictlyUnitaryLaxFunctor.ext` | 0.21e9 | 0.066 % | 1.39 % | #8 0.36e9 | #23 29 ms | > 200 |
| 13 | `Algebra.IsInvariant.exists_smul_of_under_eq_of_profinite` | 0.21e9 | 0.066 % | 1.45 % | #13 0.25e9 | #39 21 ms | #60 17 ms |
| 14 | `AlgebraicGeometry.isIso_pushoutSection_of_iSup_eq` | 0.19e9 | 0.060 % | 1.51 % | #12 0.25e9 | #20 32 ms | #4 93 ms |
| 15 | `AlgebraicGeometry.Proj.lift_awayMapₐ_awayMapₐ_surjective` | 0.19e9 | 0.058 % | 1.57 % | #9 0.31e9 | #30 24 ms | #29 25 ms |
| 16 | `Algebra.exists_etale_isIdempotentElem_forall_liesOver_eq_aux` | 0.18e9 | 0.056 % | 1.63 % | #20 0.17e9 | #13 46 ms | #42 20 ms |
| 17 | `_private.Init.Data.Array.Extract.0.Array.extract_append._proof_1_1` | 0.18e9 | 0.054 % | 1.68 % | #24 0.15e9 | #19 33 ms | #24 27 ms |
| 18 | `_private.Init.Data.String.Lemmas.Pattern.String.ForwardSearcher.0.String.Slice.Pattern.Model.ForwardSliceSearcher.Invariants.isValidSearchFrom_toList` | 0.16e9 | 0.050 % | 1.73 % | #39 0.11e9 | #29 25 ms | > 200 |
| 19 | `Std.Tactic.BVDecide.BVExpr.bitblast.blastAdd.go_denote_eq._unary` | 0.16e9 | 0.050 % | 1.78 % | #54 96.88e6 | #66 15 ms | > 200 |
| 20 | `CategoryTheory.Bicategory.mateEquiv_vcomp` | 0.16e9 | 0.049 % | 1.83 % | #16 0.21e9 | #48 19 ms | #108 13 ms |
| 21 | `Ideal.Quotient.stabilizerHomSurjectiveAuxFunctor._proof_1` | 0.16e9 | 0.049 % | 1.88 % | #23 0.16e9 | #40 20 ms | > 200 |
| 22 | `_private.Init.Data.Vector.Extract.0.Vector.extract_append._proof_1` | 0.16e9 | 0.048 % | 1.93 % | #98 72.19e6 | #17 37 ms | #13 42 ms |
| 23 | `_private.Mathlib.Data.Int.Fib.Lemmas.0.Int.fib_add_sq_sub_fib_mul_fib_add_two_mul._proof_1_2` | 0.15e9 | 0.046 % | 1.97 % | #18 0.19e9 | #22 30 ms | #14 37 ms |
| 24 | `_private.Mathlib.AlgebraicGeometry.EllipticCurve.Affine.AddSubMap.0.WeierstrassCurve.addSubMapCoeff_condition._proof_1_3` | 0.15e9 | 0.046 % | 2.02 % | #10 0.27e9 | #10 77 ms | #31 24 ms |
| 25 | `Ideal.Quotient.stabilizerHomSurjectiveAuxFunctor._proof_3` | 0.14e9 | 0.045 % | 2.07 % | #29 0.14e9 | #108 12 ms | > 200 |
| 26 | `_private.Mathlib.RingTheory.Etale.QuasiFinite.0.Algebra.exists_etale_completeOrthogonalIdempotents_forall_liesOver_eq'` | 0.14e9 | 0.044 % | 2.11 % | #31 0.13e9 | #28 25 ms | #16 36 ms |
| 27 | `String.firstDiffPos_loop_eq._unary` | 0.14e9 | 0.042 % | 2.15 % | #80 79.80e6 | #92 13 ms | > 200 |
| 28 | `_private.Mathlib.NumberTheory.Height.EllipticCurve.0.WeierstrassCurve.Affine.approx_parallelogram_law._proof_1_3` | 0.13e9 | 0.041 % | 2.19 % | #26 0.15e9 | #12 49 ms | #28 26 ms |
| 29 | `Algebra.exists_etale_isIdempotentElem_forall_liesOver_eq` | 0.13e9 | 0.040 % | 2.23 % | #58 94.55e6 | #33 23 ms | #49 18 ms |
| 30 | `CategoryTheory.MonoidalCategory.ofBifunctor.Pentagon.secondMap` | 0.13e9 | 0.040 % | 2.27 % | > 200 | > 200 | > 200 |
| 31 | `Std.IterM.stepAsHetT_filterMapWithPostcondition` | 0.12e9 | 0.039 % | 2.31 % | #27 0.14e9 | #71 14 ms | > 200 |
| 32 | `AlgebraicGeometry.exists_appTop_π_eq_of_isLimit` | 0.12e9 | 0.037 % | 2.35 % | #30 0.14e9 | #42 20 ms | #15 37 ms |
| 33 | `Field.finSepDegree_eq_finrank_of_isSeparable` | 0.12e9 | 0.037 % | 2.38 % | #104 70.10e6 | #75 14 ms | > 200 |
| 34 | `_private.Mathlib.NumberTheory.EllipticDivisibilitySequence.0.IsEllipticNet.atomRel_of_even_odd._proof_1_16` | 0.12e9 | 0.036 % | 2.42 % | #33 0.12e9 | #26 27 ms | #19 33 ms |
| 35 | `WeierstrassCurve.variableChange_Δ` | 0.12e9 | 0.036 % | 2.46 % | #19 0.18e9 | #107 12 ms | > 200 |
| 36 | `_private.Init.Data.Vector.Extract.0.Vector.extract_extract._proof_1` | 0.11e9 | 0.036 % | 2.49 % | #45 0.11e9 | #35 22 ms | #27 26 ms |
| 37 | `_private.Mathlib.NumberTheory.EllipticDivisibilitySequence.0.IsEllipticNet.atom_mul_atomRel._proof_1_2` | 0.11e9 | 0.035 % | 2.53 % | #22 0.16e9 | #18 33 ms | #46 19 ms |
| 38 | `AlgebraicGeometry.Scheme.exists_π_app_comp_eq_of_locallyOfFinitePresentation` | 0.11e9 | 0.034 % | 2.56 % | #21 0.17e9 | #106 12 ms | #47 19 ms |
| 39 | `_private.Mathlib.Analysis.ODE.ExistUnique.0.ODE_solution_unique_univ._proof_1_2` | 0.11e9 | 0.034 % | 2.60 % | #42 0.11e9 | #49 18 ms | #20 32 ms |
| 40 | `Std.Iter.atIdxSlow?_intermediateZip` | 0.11e9 | 0.033 % | 2.63 % | #49 0.10e9 | #82 13 ms | > 200 |
<!-- END profile:top -->

<!-- BEGIN profile:agreement -->
#### Agreement between checkers: declarations in common among the N most expensive

| pair | top 10 | top 50 | top 200 |
|---|---:|---:|---:|
| nano-lean.j1 / gruszka.w1 | 7 | 38 | 153 |
| nano-lean.j1 / sokonanoda.t1 | 5 | 32 | 130 |
| nano-lean.j1 / rpylean (check wall time) | 5 | 28 | 85 |
| nano-lean.j1 / rpylean | 1 | 13 | 95 |
| gruszka.w1 / sokonanoda.t1 | 6 | 33 | 131 |
| gruszka.w1 / rpylean (check wall time) | 5 | 26 | 84 |
| gruszka.w1 / rpylean | 1 | 12 | 90 |
| sokonanoda.t1 / rpylean (check wall time) | 6 | 28 | 102 |
| sokonanoda.t1 / rpylean | 1 | 14 | 98 |
| rpylean (check wall time) / rpylean | 3 | 18 | 128 |

gruszka / nano-lean, same declarations, 1 worker each: 1.173 in total; per declaration (the 61,678 above 1e6 instructions in nano-lean): p05 0.414, p25 0.898, median 1.164, p75 1.373, p95 1.739.
<!-- END profile:agreement -->

- **The checkers agree on what is expensive, less on the order.** nano-lean and gruszka share 153 of their 200
  most expensive declarations and 7 of their top 10; nano-lean and sokonanoda 130 and 5. The most expensive
  declaration is the same in all four: `…sym2x_add_sub_eq_addSubMap_sym2x._proof_1_8` (1.12e9 instructions for
  nano-lean, 1.89e9 for gruszka, 860 ms for sokonanoda, 887 ms for rpylean).
- **Neither leader dominates the other declaration by declaration.** Over the 61,678 declarations that cost
  nano-lean more than 1e6 instructions, gruszka's cost is 1.16 x nano-lean's at the median, but 0.41 x at the
  5th percentile and 1.74 x at the 95th. Each is fast where the other is not, so neither is at a floor.
- Going by their names (not checked against the terms), the heavy declarations are of a few families:
  auxiliary proofs of polynomial identities on elliptic curves and division sequences
  (`…EllipticCurve…._proof_1_N`, `IsEllipticNet.atomRel…`), bit-blasting lemmas of `Std.Tactic.BVDecide`
  (`…goCache…._mutual`), string and array lemmas of core (`fromUTF8Lossy…`, `extract_append`), and coherence
  proofs in category theory and algebraic geometry.
- **rpylean's instruction intervals need care.** 16 of its 25 largest intervals belong to declarations whose
  own check took under 4 ms; the interval then holds 0.4e9 to 0.96e9 instructions of runtime work that
  happened to fall between two declarations (my reading: major collections, whose cost grows with the heap;
  not verified). That is real cost (the 100 largest intervals hold 7.0 % of rpylean's per-declaration total,
  the 100 longest checks 3.5 % of its check time), but it is not the declaration's. Rank rpylean by
  `by_check_wall_time` in the JSON.

### 5.6 One worker against four, per declaration

<!-- BEGIN profile:workers -->
#### Per-declaration totals with 1 and 4 workers (instrumented runs)

| profile | workers | whole run | inside declarations | outside | top 200 share | median |
|---|---:|---:|---:|---:|---:|---:|
| nano-lean.j1 | 1 | 364.88e9 | 321.55e9 | 43.34e9 | 5.6 % | 77,674 |
| nano-lean.j4 | 4 | 365.40e9 | 322.07e9 | 43.33e9 | 5.6 % | 77,985 |
| gruszka.w1 | 1 | 407.84e9 | 399.95e9 | 7.90e9 | 5.7 % | 0.11e6 |
| gruszka.w4 | 4 | 412.08e9 | 404.16e9 | 7.92e9 | 5.7 % | 0.11e6 |
<!-- END profile:workers -->

- nano-lean: the sum over all declarations is 321.55e9 with one worker and 322.07e9 with four (+0.16 %); what
  lies outside declarations (import, reordering, start-up) is 43.3e9 in both. Four workers, each with caches of
  its own, do not repeat a measurable amount of work, and the median declaration costs the same (77,674 and
  77,985 instructions).
- gruszka: 399.95e9 and 404.16e9 (+1.1 %; raw intervals, printing included), 7.9e9 outside in both.
- sokonanoda has no per-declaration counts; its summed per-declaration wall time is 80.9 s with one worker and
  98.0 s with four on a busier host, which is not a clean comparison. Its instruction total rises by 3.5 %.

---

## 6. What this means for RLVM

1. **The ladder** (mathlib, this machine, instructions): 361.3e9 nano-lean, 418.7e9 gruszka,
   504.3e9 sokonanoda, 544.6e9 rpylean; then 880.4e9, 1.34e12, 5.53e12, 9.31e12 (the only JVM
   checker) and 11.42e12 (official). Below 544.6e9 is top four here; below about 355e9 is first whatever
   RLVM's aarch64/x86-64 ratio is (section 3).

2. **The budget of a 60-second checker**, as nano-lean spends it:

   | part | instructions | per unit |
   |---|---:|---|
   | reading the 6.3 GB export | 40.9e9 (11 %) | 6.5 per byte, 365 per line, 382 per exported expression |
   | checking 739,167 constants | 320.4e9 (89 %) | 434,000 per constant on average, 78,000 at the median; 2,990 per exported expression |
   | start-up, reordering, teardown | under 2.5e9 (0.7 %) | upper bound: the 1-worker trace run outside declarations minus the import-only run, tracing included |

   gruszka reads for 37.5e9 (pack 29.6e9 + load 7.9e9) and checks for 381e9; sokonanoda 36.9e9 and 467e9.

3. **No warm-up to hide behind.** The cost is spread over hundreds of thousands of declarations (5.3), and
   each declaration's own terms are used once. What is hot for the whole run is the kernel's generic machinery
   (weak-head normalisation, definitional equality, inference, substitution or evaluation, hash-consing), not
   anything derived from one declaration. A run-time compiler pays for itself only on that machinery, and what
   it spends is counted: the instructions of JIT compiler threads are part of the total.

   **Instructions and time are different currencies.** The data point here is tenet-jvm, the same bytecode
   on the same JVM: with the Graal JIT instead of C2 it executes +7.0 % instructions on init, +3.8 % on std
   and +28.4 % on mathlib, in 1.4 % more CPU time on mathlib. A compiler, a collector or a coding style that
   is equally fast on the clock can cost a quarter more in the ranking, and Init does not predict it. Compare
   RLVM's alternatives (native image or JVM, C2 or Graal, GC and heap settings, thread counts) by instruction
   count on mathlib itself.

4. **Per-declaration fixed cost is a first-order term.** 739,167 declarations: every 10,000 instructions of
   per-declaration set-up, cache reset, scheduling or bookkeeping are 7.4e9 = 2 % of the leader's total, and
   the median declaration only costs 78,000. gruszka's `DI` line, a single formatted line per declaration,
   cost it 31,600 instructions each, 5.9 % of its run.

5. **Theorems are the workload** (89 %), a fifth of it compiler-generated `_proof_N` terms; definitions 10 %;
   inductive types, constructors and recursors under 1 %. Everything that is neither a theorem nor a
   definition can be written for clarity and correctness with little regard to speed.

6. **Parallel checking is free, runtime threads are not.** nano-lean pays 0.09 % more instructions for 4
   workers than for 1 and per declaration nothing (5.6). For a JVM the comparable risk is elsewhere (not
   measured here): parallel GC and JIT threads sized by the 8 CPUs it sees. Measure with `--arena-limits` only.

7. **The leaders are not at a floor.** Declaration by declaration gruszka is between 0.41 x and 1.74 x
   nano-lean (5th to 95th percentile over the declarations above 1e6 instructions), and it is the cheaper of
   the two on 201,657 of the 721,844 declarations both name (28 %). Taking the cheaper of the two on every
   declaration gives 289.8e9 instructions of checking, against 321.5e9 for nano-lean and 377.1e9 for gruszka
   alone: 10 % below the leader with techniques that already exist in one of the two (computed from
   `mathlib-profile.by-name.tsv.gz`; gruszka's figures carry the uniform correction of 5.1).

8. **Memory.** Peak RSS on mathlib: gruszka 2.5 GiB, pestka 3.5, rpylean 4.4, nano-lean 5.0, sokonanoda 5.1,
   nanoda 5.9, mathgraph 6.4, official 9.9, tenet-jvm 11.9 GiB. The runner has 16 GB; nano-lean reaches its
   peak while reading (the import alone peaks at 5.0 GiB).

9. **What a JVM checker costs today.** tenet-jvm, a Lean kernel in plain Java: 10.4 x the leader on init,
   25.8 x on mathlib, 11.9 GiB. That is the distance between "a kernel on a JVM" and the leaderboard's top; it is
   not explained by the JVM's start-up (0.5-0.9e9 instructions per run on the smallest tests, 0.15-0.25 % of
   nano-lean's mathlib count).

---

## 7. How to measure RLVM the same way

All from the project root. RLVM's arena run line is the `run:` entry of `scripts/arena/rlvm.yaml`;
`bench/rlvm/run.sh --print` prints it (`--jvm`, `--pgo`, `--threads N` select another launcher or worker
count). Measure that command, not a wrapper around it.

```bash
# 0. build: work/dist/bin/rlvm (native image), rlvm-jvm, rlvm-pgo
scripts/build.sh

# 1. one run on mathlib exactly as the arena measures it: one JSON line with
#    exit_code, instructions, virtual_time_s (= instructions / 6e9), cpu_time_s, wall_time_s, max_rss_bytes
bench/arena-docker.sh --arena-limits --no-network bench/measure.sh --timeout 4120 \
    --in work/exports/mathlib.ndjson -c "$(bench/rlvm/run.sh --print)"

# 2. the series the baselines got: 3 runs each on init, std and mathlib, one fresh container per run
#    (records in work/rlvm/big/runs.jsonl, the checker's output in work/rlvm/big/logs/)
bench/baselines/big.sh --runs 3 --out work/rlvm/big --label rlvm --cwd . --cmd "$(bench/rlvm/run.sh --print)"

# 3. RLVM in the same tables as the baselines: instructions, virtual time, ratio to the fastest
bench/baselines/big-summary.py --no-json --runs work/baselines/big/runs.jsonl --runs work/rlvm/big/runs.jsonl

# other launchers and worker counts: give each its own label (a row of its own) ...
bench/baselines/big.sh --runs 3 --out work/rlvm/big --label rlvm-jvm --cwd . --cmd "$(bench/rlvm/run.sh --jvm --print)"
# ... or, for worker counts, a variant threads-N (they appear in the thread-scaling table)
for n in 1 2 8; do
  bench/baselines/big.sh --corpora mathlib --out work/rlvm/big --label rlvm --variant threads-$n --cwd . \
      --cmd "$(bench/rlvm/run.sh --threads $n --print)"
done
# (bench/rlvm/measure.sh --threads 1,4,8 --repeat 3 work/exports/mathlib.ndjson does a similar matrix in one container)

# 4. fast loop while developing: Init (3.7 virtual s for nano-lean) and the mathlib slices
bench/baselines/big.sh --corpora init --out work/rlvm/big --label rlvm --cwd . --cmd "$(bench/rlvm/run.sh --print)"
bench/arena-docker.sh --arena-limits --no-network bench/run-suite.py --perf --tests work/exports/slices \
    --timeout 1200 --label rlvm --cwd . -- "$(bench/rlvm/run.sh --print)"

# 5. a baseline again, for a same-day comparison (instruction counts repeat to well under 1 %)
bench/baselines/big.sh --corpora mathlib --runs 1 --out work/rlvm/big nano-lean gruszka
```

These command lines were run as written on 2026-11-24 against the RLVM build of that moment (step 1 and the
slices on small exports, step 2 on init-prelude and init), to check that they work; no RLVM result is recorded
here.

Rules that keep the numbers comparable with the tables above:

- Always `--arena-limits` (8 CPUs, 16 GB): a JVM, and a native image's serial or parallel GC, size their
  threads and heap by what they see, and their instruction count follows.
- Always the instruction count, never wall or CPU time, and always the whole command: start-up, reading the
  export, JIT compiler threads and GC threads are all counted by the arena.
- A JVM's count varies by 1-2 % per run on a workload of this size (5 % was seen on init for tenet-jvm), the
  native baselines' by less than 1 %: take the median of at least 3 runs before claiming a difference.
- The exit status must be 0: the arena takes the mathlib figure only from an accepted run, and a checker
  without one sorts after all that have one (`lka.py`, "Track mathlib performance metrics only if the test
  was accepted").
- Compare with the numbers in this document (aarch64), not with the arena's web page (x86-64); section 3 says
  how far apart they are.

**Per-declaration profile of RLVM.** If RLVM prints a line at the start and at the end of every declaration
on stderr (as `nl-fast --trace` does: `start <index> <name>` / `end <index> ...`), the same library gives its
per-declaration instruction counts, comparable declaration by declaration with
`work/baselines/mathlib-profile.nano-lean.j1.tsv.gz`:

```bash
bench/arena-docker.sh --arena-limits --no-network bench/baselines/profile-mathlib.sh build selftest
bench/arena-docker.sh --arena-limits --no-network sh -c '
  LD_PRELOAD=$PWD/work/baselines/profile/libinstrtap.so INSTRTAP_PREFIXES="start |end " \
  work/dist/bin/rlvm-jvm --threads 1 --trace work/exports/mathlib.ndjson 2> work/rlvm/mathlib.trace'
```

Checked with a plain Java program on the project's GraalVM in JVM mode
(`profile-mathlib.sh selftest-java`, `bench/baselines/InstrtapTest.java`): `System.err.println("start …")`
goes through libc's `write`, and a loop of 1.2e9 instructions between two lines reads as 1.2007e9 and
1.2026e9 once the JIT has compiled it (0.64e9 to 0.80e9 in the three rounds before, while it runs
on-stack-replaced code). The count is that of the Java thread alone: JIT compiler and GC threads are not in
it, which is what one wants when asking what the checker itself spends per declaration (the difference to the
whole run is then the runtime's share). Not tried with a native image; `--trace` is a suggestion, RLVM has no such switch yet.
`INSTRTAP_FD=1` taps stdout instead of stderr; `INSTRTAP_PREFIXES` takes any `|`-separated line prefixes.

---

## 8. Reproducing

```bash
cd .

# the baselines on init, std, mathlib (about 2 h; stages can be named one by one)
bench/baselines/big-all.sh                 # = mathlib-fast-1 small mathlib-fast-23 threads mathlib-slow mathlib-official
bench/baselines/big-summary.py             # tables + bench/baselines.json

# per-declaration profiles (about 10 min) and their summary (1 min)
bench/arena-docker.sh --arena-limits --no-network bench/baselines/profile-mathlib.sh build selftest \
    nano-lean-import nano-lean-j1 gruszka-pack gruszka-w1 gruszka-check-w1 sokonanoda-parse sokonanoda-v1 \
    rpylean-jsonl nano-lean-j4 gruszka-w4 gruszka-check-w4 sokonanoda-v4
bench/baselines/profile-summary.py         # work/baselines/mathlib-profile.{json,top200.tsv,by-name.tsv.gz,*.tsv.gz}

# the generated tables of this document and of bench/README.md
bench/baselines/render-tables.py docs/research/12-baselines.md bench/README.md
```

As run on 2026-11-24: the single-worker profile steps ran in a container on CPUs 18,19
(`--cpuset-cpus 18,19 --memory 16g` instead of `--arena-limits`) while the series was using the other eight
performance cores, the 4-worker steps under `--arena-limits` between two stages of the series, and the official
kernel's mathlib run as `OFFICIAL_CPUSET=18,19 bench/baselines/big-all.sh mathlib-official`. Per-thread
instruction counts do not depend on which performance core a thread runs on (`bench/README.md`, validation).

---

## 9. Caveats and what was not done

- **One machine, one day.** aarch64, not the arena's x86-64; Rust 1.100.0 / 1.97.1 from rustup, not the
  arena's nixpkgs toolchain; PGO profiles trained here. Section 3 quantifies the difference for the baselines;
  for RLVM it is unknown.
- **Wall and CPU times were taken on a shared host** (1-minute load average 5 to 35 during the runs; other
  agents' containers at times on the same eight cores). They are upper bounds. Instruction counts are not
  affected: see the spreads.
- **Single runs.** nanoda, tenet-jvm-c2, tenet-jvm and official have one mathlib run each; the 1-, 2- and
  8-worker rows of section 4 are one run each. The native ones repeat to 0.1 % on init and std; a JVM run
  varies by 1-2 % (5 % was seen on init). The +28 % of the Graal JIT over C2 on mathlib is one run against
  one run: far outside that noise, but not repeated and not explained.
- **The official kernel's mathlib run** was made on CPUs 18,19 with 16 GB instead of `--arena-limits`, next to
  the rest of the series. Its count (ratio to the published one 0.945; 0.949 and 0.950 on init and std) shows no
  effect of that.
- **tenet-jvm** runs on the project's GraalVM CE 25, not on the arena's OpenJDK; `tenet-jvm-c2` only switches
  the top-tier compiler to C2.
- **Profiles are from instrumented runs** (tracing switched on, the library preloaded), 1-6 % dearer than the
  plain runs. gruszka's per-declaration figures are corrected by a uniform 31,600 instructions; small
  declarations are therefore only roughly right for gruszka (463 come out at 0). rpylean's intervals include
  reading and runtime work between declarations. sokonanoda has wall times only. Kinds come from sokonanoda's
  listing joined by name; 18,349 of rpylean's names did not match (it prints some names differently).
- **Not profiled:** official, nanoda, pestka, mathgraph and tenet-jvm have no per-declaration switch for
  exports; nothing was patched to add one.
- **Not measured:** the other big corpora (cslib, cedar, con-leche, navier-stokes-euler, reachability); x86-64;
  energy or cache behaviour; `instrtap` with a native image.
- sokonanoda's bimodal count on `perf/magma-list-deep-n36` (previous infra step) did not show on the big
  corpora: 0.11 % spread over 3 mathlib runs.
- The "families" of expensive declarations in 5.5 are read off their names, not off their terms.
