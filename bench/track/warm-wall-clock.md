## Warm wall clock

Owner note 12: the target is the wall clock of checking in steady state - the same process checks the same export
again (`rlvm -X passes=N`, rlvm-main/README.md "Passes"; tool `bench/warm.sh`, bench/README.md "Warm wall clock").
Headline rows of `work/status/warm.md` (first warm table, 2026-11-24; distribution `work/warm/dist`: rlvm-main
`2c4a55f5a8ae`, rlvm-driver `c0aeb3288f46`, rlvm-machine `7eb33a7a4ad7`, rlvm-ref `035f31e854d9`; CE native image
`-O3`, JVM launcher as shipped: HotSpot C2, SerialGC). Every pass is a complete run (load pipelined with checking, new
store and engines; `-X passes-mode=reload`); warm = median of passes 2..N of one process (best in brackets), N = 5
(3 for the corpora above 1 GB and for one worker); `--threads 8` on the 8 measurement cores inside a `bench/quiet.py`
window, on the host (not in the arena container); heap 6 GB for Init, Std, cedar and reachability, 13 GB otherwise.
Baselines: the arena's run line with 8 (and 1) threads in the arena container on the same cores, second of two runs.
Not the arena's configuration and not comparable with the cold tables above cell by cell (those are batch loads in
the container at the arena's thread counts). What the numbers say, every pass, recheck mode (check-only passes), 6 / 7
workers, JVM variants, host conditions: `work/status/warm.md`.

| Corpus | Checker | Warm wall, 8 workers pipelined (s): median (best) | Warm wall, 1 worker (s) | Cold wall, fresh process, 8 workers (s) | CPU of the median warm pass, 8 workers (s) | Peak RSS, 8 workers (MiB) | 1w / (8 x 8w) |
|---|---|--:|--:|--:|--:|--:|--:|
| init (348 MB) | RLVM machine (warm,gated,expect), JVM launcher | **1.60** (1.07) | - | 3.96 | 12.37 | 900 | - |
| init (348 MB) | RLVM machine (warm,gated,expect), native image | **1.07** (1.07) | - | 1.10 | 7.30 | 455 | - |
| init (348 MB) | RLVM machine (no switch), JVM launcher | **1.05** (1.03) | - | 4.32 | 6.73 | 866 | - |
| init (348 MB) | RLVM reference, JVM launcher | **1.70** (1.61) | - | 3.98 | 12.94 | 1712 | - |
| init (348 MB) | RLVM reference, native image | **2.34** (2.22) | - | 2.53 | 14.61 | 596 | - |
| std (584 MB) | RLVM machine (warm,gated,expect), JVM launcher | **1.68** (1.57) | - | 5.81 | 11.94 | 1782 | - |
| std (584 MB) | RLVM machine (warm,gated,expect), native image | **1.88** (1.87) | - | 1.95 | 13.28 | 745 | - |
| std (584 MB) | RLVM machine (no switch), JVM launcher | **1.71** (1.61) | - | 6.35 | 11.44 | 1768 | - |
| std (584 MB) | RLVM reference, JVM launcher | **2.74** (2.70) | - | 5.80 | 20.18 | 1964 | - |
| std (584 MB) | RLVM reference, native image | **3.92** (3.75) | - | 4.13 | 24.92 | 837 | - |
| cedar (905 MB) | RLVM machine (warm,gated,expect), JVM launcher | **2.26** (2.23) | - | 8.51 | 16.46 | 1832 | - |
| cedar (905 MB) | RLVM machine (warm,gated,expect), native image | **2.62** (2.62) | - | 2.86 | 18.20 | 882 | - |
| cedar (905 MB) | RLVM machine (no switch), JVM launcher | **2.18** (2.16) | - | 7.77 | 15.96 | 2040 | - |
| cedar (905 MB) | RLVM reference, JVM launcher | **3.62** (3.51) | - | 6.63 | 25.87 | 2252 | - |
| cedar (905 MB) | RLVM reference, native image | **4.86** (4.84) | - | 4.94 | 32.21 | 875 | - |
| reachability (19 MB) | RLVM machine (warm,gated,expect), JVM launcher | **1.88** (1.86) | - | 4.29 | 5.94 | 4436 | - |
| reachability (19 MB) | RLVM machine (warm,gated,expect), native image | **2.66** (2.62) | - | 2.82 | 7.92 | 2764 | - |
| reachability (19 MB) | RLVM machine (no switch), JVM launcher | **1.94** (1.92) | - | 4.51 | 6.43 | 4410 | - |
| reachability (19 MB) | RLVM reference, JVM launcher | **3.96** (3.77) | - | 5.33 | 11.05 | 4429 | - |
| reachability (19 MB) | RLVM reference, native image | **4.83** (4.80) | - | 5.04 | 12.80 | 2693 | - |
