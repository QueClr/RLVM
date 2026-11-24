## Warm wall clock

Owner note 12: the target is the wall clock of checking in steady state - the same process checks the same export
again (`rlvm -X passes=N`, rlvm-main/README.md "Passes"; tool `bench/warm.sh`, bench/README.md "Warm wall clock").
Headline rows of `work/status/warm.md` (first warm table, 2026-11-24; distribution `work/warm/dist`: rlvm-main
`2c4a55f5a8ae`, rlvm-driver `c0aeb3288f46`, rlvm-machine `7eb33a7a4ad7`, rlvm-ref `035f31e854d9`; CE native image
`-O3`, JVM launcher as shipped: HotSpot C2, SerialGC). Every pass is a complete run (load pipelined with checking, new
store and engines; `-X passes-mode=reload`); warm = median of passes 2..N of one process (best in brackets), N = 5
(3 for con-leche, navier-stokes-euler, cslib, mathlib and for one worker; 10 where a row says so); `--threads 8` on the 8 measurement cores inside a `bench/quiet.py`
window, on the host (not in the arena container); heap 6 GB for Init, Std, cedar and reachability, 13 GB otherwise.
Baselines: the arena's run line with 8 (and 1) threads in the arena container on the same cores, second of two runs.
Not the arena's configuration and not comparable with the cold tables above cell by cell (those are batch loads in
the container at the arena's thread counts). What the numbers say, every pass, recheck mode (check-only passes), 6 / 7
workers, JVM variants, host conditions: `work/status/warm.md`.

| Corpus | Checker | Warm wall, 8 workers pipelined (s): median (best) | Warm wall, 1 worker (s) | Cold wall, fresh process, 8 workers (s) | CPU of the median warm pass, 8 workers (s) | Peak RSS, 8 workers (MiB) | 1w / (8 x 8w) |
|---|---|--:|--:|--:|--:|--:|--:|
| init (348 MB) | RLVM machine (warm,gated,expect), JVM launcher | **0.96** (0.88) in 10 passes; 5 passes: 1.60 (1.07) | **5.13** (5.13) | 3.96 | 6.07 | 893 | 0.67 |
| init (348 MB) | RLVM machine (warm,gated,expect), native image | **1.07** (1.07) | **6.26** (6.26) | 1.10 | 7.30 | 455 | 0.73 |
| init (348 MB) | RLVM machine (no switch), JVM launcher | **1.05** (1.03) | - | 4.32 | 6.73 | 866 | - |
| init (348 MB) | RLVM machine (no switch), native image | **1.08** (1.06) | - | 1.19 | 7.47 | 481 | - |
| init (348 MB) | RLVM reference, JVM launcher | **1.70** (1.61) | - | 3.98 | 12.94 | 1712 | - |
| init (348 MB) | RLVM reference, native image | **2.34** (2.22) | - | 2.53 | 14.61 | 596 | - |
| init (348 MB) | gruszka, 8 / 1 threads | **1.01** (first run 0.96) | **3.15** (first run 2.83) | 0.96 | 4.14 | 498 | 0.39 |
| init (348 MB) | nano-lean, 8 / 1 threads | **1.23** (first run 1.11) | **5.03** (first run 5.34) | 1.11 | 5.89 | 1385 | 0.51 |
| std (584 MB) | RLVM machine (warm,gated,expect), JVM launcher | **1.72** (1.53) in 10 passes; 5 passes: 1.68 (1.57) | **9.68** (9.68) | 5.81 | 10.93 | 1774 | 0.70 |
| std (584 MB) | RLVM machine (warm,gated,expect), native image | **1.88** (1.87) | **11.19** (11.19) | 1.95 | 13.28 | 745 | 0.74 |
| std (584 MB) | RLVM machine (no switch), JVM launcher | **1.71** (1.61) | - | 6.35 | 11.44 | 1768 | - |
| std (584 MB) | RLVM machine (no switch), native image | **1.84** (1.81) | - | 1.96 | 13.02 | 739 | - |
| std (584 MB) | RLVM reference, JVM launcher | **2.74** (2.70) | - | 5.80 | 20.18 | 1964 | - |
| std (584 MB) | RLVM reference, native image | **3.92** (3.75) | - | 4.13 | 24.92 | 837 | - |
| std (584 MB) | gruszka, 8 / 1 threads | **1.13** (first run 1.37) | **5.33** (first run 5.40) | 1.37 | 6.11 | 667 | 0.59 |
| std (584 MB) | nano-lean, 8 / 1 threads | **1.79** (first run 1.96) | **8.57** (first run 8.54) | 1.96 | 10.06 | 1661 | 0.60 |
| cedar (905 MB) | RLVM machine (warm,gated,expect), JVM launcher | **2.21** (2.01) in 10 passes; 5 passes: 2.26 (2.23) | **12.48** (12.48) | 8.51 | 14.96 | 2067 | 0.71 |
| cedar (905 MB) | RLVM machine (warm,gated,expect), native image | **2.62** (2.62) | **14.97** (14.97) | 2.86 | 18.20 | 882 | 0.71 |
| cedar (905 MB) | RLVM machine (no switch), JVM launcher | **2.18** (2.16) | - | 7.77 | 15.96 | 2040 | - |
| cedar (905 MB) | RLVM machine (no switch), native image | **2.58** (2.51) | - | 2.78 | 17.80 | 896 | - |
| cedar (905 MB) | RLVM reference, JVM launcher | **3.62** (3.51) | - | 6.63 | 25.87 | 2252 | - |
| cedar (905 MB) | RLVM reference, native image | **4.86** (4.84) | - | 4.94 | 32.21 | 875 | - |
| cedar (905 MB) | gruszka, 8 / 1 threads | **2.07** (first run 1.81) | **8.92** (first run 7.74) | 1.81 | 10.00 | 792 | 0.54 |
| cedar (905 MB) | nano-lean, 8 / 1 threads | **2.95** (first run 2.72) | **12.62** (first run 14.00) | 2.72 | 15.63 | 1992 | 0.53 |
| reachability (19 MB) | RLVM machine (warm,gated,expect), JVM launcher | **1.88** (1.86) | **5.28** (5.28) | 4.29 | 5.94 | 4436 | 0.35 |
| reachability (19 MB) | RLVM machine (warm,gated,expect), native image | **2.66** (2.62) | **7.83** (7.83) | 2.82 | 7.92 | 2764 | 0.37 |
| reachability (19 MB) | RLVM machine (no switch), JVM launcher | **1.94** (1.92) | - | 4.51 | 6.43 | 4410 | - |
| reachability (19 MB) | RLVM machine (no switch), native image | **2.74** (2.66) | - | 2.89 | 8.33 | 2871 | - |
| reachability (19 MB) | RLVM reference, JVM launcher | **3.96** (3.77) | - | 5.33 | 11.05 | 4429 | - |
| reachability (19 MB) | RLVM reference, native image | **4.83** (4.80) | - | 5.04 | 12.80 | 2693 | - |
| reachability (19 MB) | gruszka, 8 / 1 threads | **2.57** (first run 2.49) | **3.61** (first run 3.92) | 2.49 | 4.04 | 1162 | 0.18 |
| reachability (19 MB) | nano-lean, 8 / 1 threads | **0.96** (first run 0.96) | **1.99** (first run 1.88) | 0.96 | 2.06 | 1421 | 0.26 |
| con-leche (702 MB) | RLVM machine (warm,gated,expect), JVM launcher | **1.71** (1.71) | - | 6.34 | 12.50 | 2118 | - |
| con-leche (702 MB) | RLVM machine (warm,gated,expect), native image | **2.18** (2.18) | - | 2.26 | 14.08 | 910 | - |
| navier-stokes-euler (1.56 GB) | RLVM machine (warm,gated,expect), JVM launcher | **8.45** (8.45) | - | 15.67 | 61.09 | 3116 | - |
| navier-stokes-euler (1.56 GB) | RLVM machine (warm,gated,expect), native image | **9.29** (9.29) | - | 9.73 | 69.64 | 1313 | - |
| cslib (2.50 GB) | RLVM machine (warm,gated,expect), JVM launcher | **7.62** (7.62) | - | 12.56 | 56.63 | 3311 | - |
| cslib (2.50 GB) | RLVM machine (warm,gated,expect), native image | **9.06** (9.06) | - | 9.34 | 66.06 | 1645 | - |
| mathlib (6.33 GB) | RLVM machine (warm,gated,expect), JVM launcher | **25.63** (25.63) | - | 38.59 | 187.58 | 11509 | - |
| mathlib (6.33 GB) | RLVM machine (warm,gated,expect), native image | **35.34** (35.34) | - | 35.06 | 240.62 | 4362 | - |
| mathlib (6.33 GB) | gruszka, 8 / 1 threads | **12.49** (first run 12.86) | - | 12.86 | 75.61 | 2769 | - |
| mathlib (6.33 GB) | nano-lean, 8 / 1 threads | **20.71** (first run 18.09) | - | 18.09 | 106.43 | 5488 | - |
