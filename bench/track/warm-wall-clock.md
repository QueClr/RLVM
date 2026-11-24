## Warm wall clock

Owner note 12: the target is the wall clock of checking in steady state - the same process checks the same export
again (`rlvm -X passes=N`, rlvm-main/README.md "Passes"; tool `bench/warm.sh`, bench/README.md "Warm wall clock").
Every pass is a complete run (load pipelined with checking, new store and engines; `-X passes-mode=reload`); warm =
median of passes 2..N of one process (best in brackets); `--threads 8` on the 8 measurement cores inside a
`bench/quiet.py` window, on the host (not in the arena container); heap 6 GB for the small corpora, the launcher's
own 13 GB for the large ones. Baselines: the arena's run line with 8 (and 1) threads in the arena container on the
same cores, second of two runs. Not the arena's configuration and not comparable with the cold tables above cell by
cell (those are batch loads in the container). Two tables: the machine of the wave-6 integration, and
the first warm table of 17:37Z (kept: it has the reference engine, the generic tier and the recheck rows).

### The machine with its default switch set of wave 6 (windows of 20:04Z - 20:30Z)

Distribution `work/int6/dist-b` (machine `78c74ca92785`: in THIS distribution the machine is the default engine - it
was from 19:41Z to 20:35Z and is not any more, see `rlvm-machine/SWITCHES.md`; the rows are what `-X engine=machine` gives
today - with the switch set `warm,gated,expect,bodies,pchk,match,joint,code,casetrees,lazycases,directrules,lazyeval,same`, cap 1 MiB;
rlvm-driver `31fa08d9ce61`, rlvm-load `492a436c2c95`, rlvm-ref `ca1f9bd918bb`; CE native image `-O3`; JVM launcher as
shipped: HotSpot C2, SerialGC), no `-X engine` and no switch list. N = 5 on the native image (3 for navier-stokes-euler
and cslib, 2 for mathlib; 3 for one worker), on the JVM launcher 10 for Init, 6 otherwise, 3 for mathlib. gruszka and
nano-lean were measured in the SAME windows. Rows: `work/perf-history/warm.tsv`, label `int6-final`; scripts
`work/int6/wA.sh`, `wB.sh`.

| Corpus | Checker | Warm wall, 8 workers pipelined (s): median (best) | Warm wall, 1 worker (s) | Cold wall, fresh process, 8 workers (s) | CPU of the median warm pass, 8 workers (s) | Peak RSS, 8 workers (MiB) | passes (8 workers) |
|---|---|--:|--:|--:|--:|--:|---|
| init (348 MB) | RLVM machine (default switch set), native image | **0.81** (0.80) | **4.40** (4.40) | 0.82 | 5.30 | 377 | 0.81 0.82 0.81 0.80 0.88 |
| init (348 MB) | RLVM machine (default switch set), JVM launcher | **0.72** (0.66) | - | - | 5.32 | 729 | 4.80 2.02 1.15 0.92 0.75 0.72 0.71 0.69 0.66 0.70 |
| init (348 MB) | gruszka | **0.66** (0.66) | **3.07** (3.07) | 0.64 | 3.59 | 532 | 0.64 0.66 |
| init (348 MB) | nano-lean | **1.04** (1.04) | **4.58** (4.58) | 1.03 | 5.80 | 1452 | 1.03 1.04 |
| std (584 MB) | RLVM machine (default switch set), native image | **1.51** (1.44) | **8.68** (8.68) | 1.66 | 10.11 | 771 | 1.58 1.57 1.44 1.51 1.56 |
| std (584 MB) | RLVM machine (default switch set), JVM launcher | **2.92** (1.43) | - | - | 22.11 | 1767 | 5.21 3.70 3.21 2.92 1.99 1.43 |
| std (584 MB) | gruszka | **1.06** (1.06) | **4.51** (4.51) | 1.15 | 6.06 | 668 | 1.15 1.06 |
| std (584 MB) | nano-lean | **1.74** (1.74) | **8.20** (8.20) | 1.70 | 9.79 | 1682 | 1.70 1.74 |
| cedar (905 MB) | RLVM machine (default switch set), native image | **2.08** (2.07) | - | 2.21 | 14.03 | 822 | 2.08 2.08 2.15 2.10 2.07 |
| cedar (905 MB) | RLVM machine (default switch set), JVM launcher | **2.14** (1.85) | - | - | 16.11 | 1946 | 6.51 4.08 2.84 2.14 1.85 1.91 |
| cedar (905 MB) | gruszka | **1.65** (1.65) | - | 1.72 | 9.44 | 786 | 1.72 1.65 |
| cedar (905 MB) | nano-lean | **2.69** (2.69) | - | 2.70 | 15.48 | 2001 | 2.70 2.69 |
| reachability (19 MB) | RLVM machine (default switch set), native image | **0.61** (0.58) | **1.44** (1.44) | 0.63 | 1.60 | 555 | 0.67 0.64 0.63 0.58 0.61 |
| reachability (19 MB) | RLVM machine (default switch set), JVM launcher | **0.56** (0.48) | - | - | 2.26 | 1410 | 1.88 0.95 0.63 0.56 0.48 0.51 |
| reachability (19 MB) | gruszka | **2.36** (2.36) | - | 2.49 | 3.80 | 1234 | 2.49 2.36 |
| reachability (19 MB) | nano-lean | **0.75** (0.75) | - | 0.93 | 1.82 | 1369 | 0.93 0.75 |
| cedar-hot-ax (133 MB) | RLVM machine (default switch set), native image | **0.45** (0.45) | **1.91** (1.91) | 0.48 | 2.42 | 461 | 0.47 0.47 0.45 0.45 0.48 |
| cedar-hot-ax (133 MB) | RLVM machine (default switch set), JVM launcher | **0.46** (0.40) | - | - | 2.96 | 1178 | 2.06 0.73 0.60 0.46 0.42 0.40 |
| cedar-hot-ax (133 MB) | gruszka | **0.32** (0.32) | - | 0.33 | 1.89 | 428 | 0.33 0.32 |
| cedar-hot-ax (133 MB) | nano-lean | **0.48** (0.48) | - | 0.49 | 3.15 | 1376 | 0.49 0.48 |
| con-leche (702 MB) | RLVM machine (default switch set), native image | **1.77** (1.77) | - | 1.90 | 10.56 | 801 | 1.75 1.83 1.81 1.77 1.77 |
| navier-stokes-euler (1.56 GB) | RLVM machine (default switch set), native image | **8.35** (8.35) | - | 8.70 | 62.40 | 1284 | 8.48 8.52 8.35 |
| cslib (2.50 GB) | RLVM machine (default switch set), native image | **7.61** (7.61) | - | 7.81 | 54.91 | 1658 | 7.82 7.76 7.61 |
| mathlib (6.33 GB) | RLVM machine (default switch set), native image | **27.43** (27.43) | - | 27.61 | 197.28 | 4292 | 27.34 27.43 |
| mathlib (6.33 GB) | RLVM machine (default switch set), JVM launcher | **21.23** (21.23) | - | - | 162.46 | 8958 | 30.94 22.35 21.23 |
| mathlib (6.33 GB) | gruszka | **12.32** (12.32) | - | 12.43 | 73.72 | 2834 | 12.43 12.32 |
| mathlib (6.33 GB) | nano-lean | **18.52** (18.52) | - | 17.96 | 109.06 | 5511 | 17.96 18.52 |

What it says. (1) In steady state with 8 threads on the same cores the machine (native image) is at 1.23 times gruszka
on Init (0.81 against 0.66 s), 1.42 on Std, 1.26 on cedar, 1.41 on the Cedar slice, 0.26 on reachability and 2.23 on
mathlib (27.4 against 12.3 s; the JVM launcher: 21.2 s = 1.72 times); against nano-lean 0.78 / 0.87 / 0.77 / 0.94 /
0.81 / 1.48 (JVM launcher on mathlib: 1.15). (2) Against the first warm table below (`warm,gated,expect`, other
pipeline jars): native image Init 1.07 -> 0.81 s, Std 1.88 -> 1.51, cedar 2.62 -> 2.08, reachability 2.66 -> 0.61,
con-leche 2.18 -> 1.77, navier-stokes-euler 9.29 -> 8.35, cslib 9.06 -> 7.61, mathlib 35.3 -> 27.4; JVM launcher mathlib
25.6 -> 21.2. (3) The JVM launcher is not steady within these pass counts on Std and cedar (its walls still fall in
the last passes: read its "best", and the 10-pass rows of the first table); once steady it is the faster platform
on Init, reachability and mathlib, as before. (4) One worker (native image): Init 4.40 s, Std 8.68, reachability 1.44,
Cedar slice 1.91; gruszka with one thread 3.07 and 4.51 on Init and Std, nano-lean 4.58 and 8.20.

### The first warm table (2026-11-24T17:37Z; machine = `warm,gated,expect`, the default of 16:24Z)

Headline rows of `work/status/warm.md` (distribution `work/warm/dist`: rlvm-main `2c4a55f5a8ae`, rlvm-driver
`c0aeb3288f46`, rlvm-machine `7eb33a7a4ad7`, rlvm-ref `035f31e854d9`); N = 5 (3 for con-leche, navier-stokes-euler,
cslib, mathlib and for one worker; 10 where a row says so). What the numbers say, every pass, recheck mode
(check-only passes), 6 / 7 workers, JVM variants, host conditions: `work/status/warm.md`.

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
| mathlib (6.33 GB) | RLVM reference, JVM launcher | **68.58** (68.58) | - | 73.59 | 502.16 | 13455 | - |
| mathlib (6.33 GB) | gruszka, 8 / 1 threads | **12.49** (first run 12.86) | - | 12.86 | 75.61 | 2769 | - |
| mathlib (6.33 GB) | nano-lean, 8 / 1 threads | **20.71** (first run 18.09) | - | 18.09 | 106.43 | 5488 | - |
