# PG1 - Can the arena binary get its warm-up in advance? Profile-guided optimisation (Oracle GraalVM, measurement only), and the same question for the JVM

Agent PG1, 2026-11-25, 00:00Z - 01:41Z. A measurement report. No module code was changed or published, no default
was changed, `scripts/` was not touched, nothing was downloaded: the Oracle GraalVM 25.0.4 that was already unpacked
under `.toolchain/oracle-graalvm-25` was used as it was. Everything of mine is under `work/pg1/` (builds, profiles,
reports, logs, scripts); living summary `work/status/prof-PG1.md`. (Owner note 15 wants every note in the journal;
this file is where its brief put it and moves with the others.)

The owner's question:

> "is it possible to release with warm up config jsons so graal warmup on start up and we bench directly the hot
> path in arena?"

**Every table of this report is headed "Oracle GraalVM - measurement only, not a default build".** Oracle GraalVM is
licensed under the GraalVM Free Terms and Conditions (GFTC), not an open-source licence; the default build, the arena
build and every default script use GraalVM Community Edition only (owner notes 11 and 16). Rows named "CE" or "JVM
launcher" are the Community Edition and stand beside the Oracle rows for comparison. All numbers are aarch64
(Cortex-X925); the arena's runner is x86-64, where nothing of this was measured.

## 0. The answer

1. **Yes, and the mechanism is profile-guided optimisation (PGO).** A binary that is compiled ahead of time has no
   warm-up phase to move; what a training run can give it is what a JIT learns while warming up (which branches are
   taken, which calls are hot), as `.iprof` files (JSON) that `native-image --pgo=...` compiles with. Only Oracle
   GraalVM has it (GraalVM CE 25: "Unrecognized option '--pgo-instrument'"); owner note 16 makes such a build an
   option, never the default.
2. **The three numbers** - Mathlib, the machine with the candidate set `default,eager,skipargs,congruence`, 8 workers,
   one fresh process in the arena-shaped container; CE image as shipped -> Oracle GraalVM without a profile -> Oracle
   GraalVM with the Init profile (in brackets the two other training sets): **instructions 1,687.6 G -> 1,705.6 G -> 1,483.2 G (1,528.2 G; 1,542.8 G), i.e. -12.1 % (-9.4 %; -8.6 %)**; **cold wall
   clock 17.82 s -> 16.73 s -> 15.57 s (15.81 s; 16.65 s)**; **warm wall clock (passes 2-4 of one process) 17.11 s -> 17.49 s -> 15.18 s (16.20 s; 15.49 s)**.
3. **On Mathlib a profile is worth 9 to 12 % of the instructions and about a tenth of the wall clock; Oracle's
   compiler without one is worth nothing in instructions** (+-1 %; 0 to 6 % less CPU time). Over all eight corpora:
   instructions -11 to -15 % with the Init profile and -9 to -12 % with Std + cedar; CPU time -8 to -15 % and cold wall clock -7 to -16 % with Std + cedar, timed on all eight. The same with the machine's plain default set (Mathlib 2,806.0 G -> 2,511.7 G, -10.5 %) and with the reference engine (Mathlib 8,560.8 G -> 7,503.7 G, -12.3 %; -12 to -14 % on the other seven, from a profile
   that was recorded on the machine).
4. **"Bench directly the hot path": the image already does.** A cold Mathlib process takes 17.82 s, a later pass
   in the same process 17.11 s; in instructions the second pass of a two-pass process costs 1,697.9 G where a cold process costs 1,689.7 G. PGO makes the image's code better; it has no start-up
   phase to remove. The JVM is where the two differ: a warm Mathlib pass costs 1,180 to 1,240 G there, a cold process 1,369 to
   1,425 G.
5. **The cheapest training set is the best**: Init alone (21 s of training; what nano-lean, gruszka and pestka
   train on in their arena builds) gives 1,483.2 G (-12.1 %) on Mathlib, Std + cedar 1,542.8 G (-8.6 %), two Mathlib slices + Std 1,528.2 G (-9.4 %),
   Mathlib itself 1,506.7 G (-10.7 %) (not an admissible entry, and not better), init-prelude alone 1,587.0 G (-6.0 %). A profile is 15 to 27 MB
   of JSON, **2.6 to 4.8 MB gzip-compressed**.
6. **Correctness**: on each of the arena's 270 tests the PGO image gives the status and the exit code of the CE
   image - with the shipped run line and with the candidate run line: 148 / 148 good accepted, 92 / 92 bad
   rejected, the two known `either` declines. Every image accepted all eight corpora in every configuration.
7. **A stale profile degrades gently and silently**: a profile recorded 4.3 hours earlier (7 of 14 jars republished since) keeps a half to three quarters of the gain (Mathlib -4.1 % against the CE image instead of -8.0 % for a fresh profile of the same kind), a 17-hour-old profile of the reference engine three fifths (Init -8.9 % instead of -15.3 %); no stale or foreign profile made any measured cell slower than the CE image. The build output does not tell a stale profile from
   a fresh one.
8. **The JVM cannot be handed its hot path with what JDK 25 has** (the AOT cache carries classes and method
   profiles, no compiled code; compiler-directive files and the Graal / Truffle options of the Community Edition
   steer compilation, they do not replace it: section 4). **But "a JIT cannot win the arena's metric" is wrong at
   Mathlib size**: the JVM launcher as shipped (Community Edition, no profile, compiler threads counted) needs
   1,368.7 G on eight quiet cores - less than the CE image and less than every PGO image, in the same wall clock
   (16.66 s) - and 1.5 to 3.8 times the image's instructions on everything short.
9. **Shipping it** (section 5) takes an Oracle GraalVM download in the build that asks for it (GFTC), the Init
   profile in the repository (2.6 MB) or three minutes of training at build time as the leading arena entries do,
   a refresh of the profile with every release, and it buys **no place on the board**: pestka needs 1,339.6 G on
   this host, the PGO image 1,483 G, the JVM launcher 1,355 to 1,425 G. Neither image is reproducible bit for bit
   (two builds from identical inputs have the same size and 81 to 86 % different bytes, the CE image as well as the PGO image; the two PGO builds agree to 0.4 % in instructions).
10. **Not established**: anything on x86-64, the arena's architecture; whether a profile recorded on aarch64 serves
    an x86-64 build; timing differences below 5 % (one run per cell). Section 6.

## 1. Tables

### 1.1 Mathlib

**Oracle GraalVM - measurement only, not a default build.** Mathlib (6.33 GB export): one fresh process per row, the arena run line with `--threads 8`, in the arena-shaped container (8 performance cores, 16 GB). Instructions = user space, all threads. CPU s and cold wall from a `bench/quiet.py` window (one Mathlib process per window); warm = one process with `-X passes=4` in a window of its own, median of passes 2-4. "-" = not measured.

| configuration | image | instructions | vs CE | CPU s | vs CE | cold wall s | vs CE | warm wall s | vs CE | passes 1-4 s | peak RSS MiB |
|---|---|--:|--:|--:|--:|--:|--:|--:|--:|---|--:|
| machine, candidate set | CE image (as shipped) | 1,687.6 G |  | 127.5 |  | 17.82 |  | 17.11 |  | 17.67 17.11 17.84 17.00 | 4,087 |
| machine, candidate set | Oracle, no profile | 1,705.6 G | +1.1 % | 121.1 | -5.0 % | 16.73 | -6.1 % | 17.49 | +2.2 % | 16.75 17.19 17.49 18.39 | 4,373 |
| machine, candidate set | Oracle, no profile, ML inference off | 1,741.0 G | +3.2 % | - | - | - | - | - | - | - | 4,261 |
| machine, candidate set | Oracle PGO A (Std + cedar) | 1,542.8 G | -8.6 % | 114.3 | -10.3 % | 16.65 | -6.6 % | 15.49 | -9.5 % | 15.60 15.38 15.60 15.49 | 4,355 |
| machine, candidate set | Oracle PGO B (Init) | 1,483.2 G | -12.1 % | 111.4 | -12.6 % | 15.57 | -12.7 % | 15.18 | -11.3 % | 15.59 15.04 15.18 15.67 | 4,495 |
| machine, candidate set | Oracle PGO C (2 Mathlib slices + Std) | 1,528.2 G | -9.4 % | 113.2 | -11.2 % | 15.81 | -11.3 % | 16.20 | -5.3 % | 16.33 16.20 16.85 15.64 | 4,531 |
| machine, candidate set | Oracle PGO P (init-prelude) | 1,587.0 G | -6.0 % | - | - | - | - | - | - | - | 4,180 |
| machine, candidate set | Oracle PGO M (Mathlib itself) | 1,506.7 G | -10.7 % | - | - | - | - | - | - | - | 4,228 |
| machine, plain default set | CE image (as shipped) | 2,806.0 G |  | 203.5 |  | 28.44 |  | - |  | - | 4,165 |
| machine, plain default set | Oracle, no profile | 2,822.0 G | +0.6 % | 196.1 | -3.6 % | 26.87 | -5.5 % | - | - | - | 4,668 |
| machine, plain default set | Oracle PGO A (Std + cedar) | 2,575.9 G | -8.2 % | 181.1 | -11.0 % | 26.63 | -6.3 % | - | - | - | 4,793 |
| machine, plain default set | Oracle PGO B (Init) | 2,511.7 G | -10.5 % | - | - | - | - | - | - | - | 4,109 |
| machine, plain default set | Oracle PGO C (2 Mathlib slices + Std) | 2,557.4 G | -8.9 % | - | - | - | - | - | - | - | 4,252 |
| reference engine | CE image (as shipped) | 8,560.8 G |  | 618.4 |  | 91.23 |  | - |  | - | 7,715 |
| reference engine | Oracle, no profile | 8,274.2 G | -3.3 % | 612.4 | -1.0 % | 92.90 | +1.8 % | - | - | - | 5,489 |
| reference engine | Oracle PGO A (Std + cedar) | 7,503.7 G | -12.3 % | 575.9 | -6.9 % | 89.90 | -1.5 % | - | - | - | 6,562 |

Reading. Instruction counts repeat to 0.3 % for the machine and 1.2 % for the reference engine between a run
inside a window and a run of the same cell in a suite (section 2); CPU seconds and wall clock are single runs, and two identical 8-worker runs on this host differ by up
to 5 % (J1.md, section 1), so "-6 %" against "-12 %" of wall clock between two PGO images is not a finding - "PGO is
faster than CE by about a tenth" is. Peak RSS is 7 to 11 % higher with the Oracle images for the machine (4.3 to 4.4
GiB against 4.0) and lower for the reference engine (5.4 to 6.4 against 7.5 GiB). The Oracle image without a profile
shows both, so it is not the profile; the two GraalVM versions differ (25.0.4 against 25.5.5.0.3).

### 1.2 The other corpora

**Oracle GraalVM - measurement only, not a default build.** Instructions of a cold process (user space, all threads), `--threads 8`, arena-shaped container. PGO B and PGO C with the plain default set and the reference engine: count runs outside a window (Mathlib only for the reference engine).

| corpus | configuration | CE image (as shipped) | Oracle, no profile | Oracle PGO A (Std + cedar) | Oracle PGO B (Init) | Oracle PGO C (2 Mathlib slices + Std) | Oracle PGO A (Std + cedar) / CE | Oracle PGO B (Init) / CE | Oracle, no profile / CE |
|---|---|--:|--:|--:|--:|--:|--:|--:|--:|
| mathlib | machine, candidate set | 1,687.6 G | 1,705.6 G | 1,542.8 G | 1,483.2 G | 1,528.2 G | 0.914 | 0.879 | 1.011 |
| mathlib | machine, plain default set | 2,806.0 G | 2,822.0 G | 2,575.9 G | 2,511.7 G | 2,557.4 G | 0.918 | 0.895 | 1.006 |
| mathlib | reference engine | 8,560.8 G | 8,274.2 G | 7,503.7 G | - | - | 0.877 | - | 0.967 |
| cedar | machine, candidate set | 204.1 G | 204.9 G | 181.7 G | 174.7 G | 180.1 G | 0.890 | 0.856 | 1.004 |
| cedar | machine, plain default set | 219.3 G | 219.7 G | 195.3 G | 188.5 G | 193.9 G | 0.890 | 0.859 | 1.002 |
| cedar | reference engine | 533.2 G | 515.7 G | 466.1 G | - | - | 0.874 | - | 0.967 |
| std | machine, candidate set | 140.5 G | 141.8 G | 126.7 G | 121.9 G | 125.3 G | 0.902 | 0.868 | 1.009 |
| std | machine, plain default set | 152.7 G | 153.4 G | 137.4 G | 132.7 G | 136.2 G | 0.900 | 0.869 | 1.005 |
| std | reference engine | 415.8 G | 402.4 G | 362.8 G | - | - | 0.872 | - | 0.968 |
| init | machine, candidate set | 81.2 G | 82.0 G | 73.3 G | 70.3 G | 72.5 G | 0.903 | 0.866 | 1.010 |
| init | machine, plain default set | 85.0 G | 85.7 G | 76.6 G | 73.8 G | 76.0 G | 0.902 | 0.868 | 1.009 |
| init | reference engine | 251.8 G | 244.2 G | 219.5 G | - | - | 0.872 | - | 0.970 |
| reachability | machine, candidate set | 16.3 G | 16.2 G | 14.8 G | 14.5 G | 14.7 G | 0.906 | 0.891 | 0.991 |
| reachability | machine, plain default set | 16.4 G | 16.3 G | 14.8 G | 14.6 G | 14.8 G | 0.904 | 0.892 | 0.992 |
| reachability | reference engine | 116.2 G | 110.3 G | 100.4 G | - | - | 0.864 | - | 0.949 |
| navier-stokes-euler | machine, candidate set | 527.2 G | 520.3 G | 463.3 G | 450.7 G | 459.8 G | 0.879 | 0.855 | 0.987 |
| navier-stokes-euler | machine, plain default set | 1,023.1 G | 1,019.3 G | 924.1 G | 900.0 G | 924.3 G | 0.903 | 0.880 | 0.996 |
| navier-stokes-euler | reference engine | 3,191.9 G | 3,088.7 G | 2,791.7 G | - | - | 0.875 | - | 0.968 |
| cslib | machine, candidate set | 569.3 G | 575.5 G | 518.8 G | 499.7 G | 515.2 G | 0.911 | 0.878 | 1.011 |
| cslib | machine, plain default set | 768.3 G | 773.1 G | 701.6 G | 682.6 G | 695.7 G | 0.913 | 0.888 | 1.006 |
| cslib | reference engine | 2,211.6 G | 2,147.1 G | 1,943.2 G | - | - | 0.879 | - | 0.971 |
| con-leche | machine, candidate set | 477.4 G | 474.0 G | 424.5 G | 416.0 G | 423.6 G | 0.889 | 0.871 | 0.993 |
| con-leche | machine, plain default set | 198.6 G | 195.5 G | 170.9 G | 164.9 G | 170.0 G | 0.861 | 0.830 | 0.984 |
| con-leche | reference engine | 848.9 G | 810.5 G | 725.8 G | - | - | 0.855 | - | 0.955 |

**Oracle GraalVM - measurement only, not a default build.** CPU seconds of a cold process (quiet windows only; "-" = no timed run).

| corpus | configuration | CE image (as shipped) | Oracle, no profile | Oracle PGO A (Std + cedar) | Oracle PGO B (Init) | Oracle PGO C (2 Mathlib slices + Std) | Oracle PGO A (Std + cedar) / CE | Oracle PGO B (Init) / CE | Oracle, no profile / CE |
|---|---|--:|--:|--:|--:|--:|--:|--:|--:|
| mathlib | machine, candidate set | 127.49 | 121.13 | 114.34 | 111.44 | 113.18 | 0.897 | 0.874 | 0.950 |
| mathlib | machine, plain default set | 203.45 | 196.10 | 181.13 | - | - | 0.890 | - | 0.964 |
| mathlib | reference engine | 618.42 | 612.37 | 575.85 | - | - | 0.931 | - | 0.990 |
| cedar | machine, candidate set | 13.85 | 13.37 | 12.15 | - | - | 0.877 | - | 0.965 |
| cedar | machine, plain default set | 14.78 | 14.21 | 12.94 | - | - | 0.876 | - | 0.962 |
| cedar | reference engine | 32.26 | 31.43 | - | - | - | - | - | 0.974 |
| std | machine, candidate set | 9.86 | 9.45 | 8.55 | - | - | 0.868 | - | 0.959 |
| std | machine, plain default set | 10.58 | 10.09 | 9.29 | - | - | 0.878 | - | 0.954 |
| std | reference engine | 24.82 | 24.13 | - | - | - | - | - | 0.972 |
| init | machine, candidate set | 5.41 | 5.24 | 4.73 | - | - | 0.874 | - | 0.968 |
| init | machine, plain default set | 5.53 | 5.30 | 4.86 | - | - | 0.878 | - | 0.958 |
| init | reference engine | 14.61 | 14.20 | - | - | - | - | - | 0.972 |
| reachability | machine, candidate set | 1.57 | 1.57 | 1.38 | - | - | 0.877 | - | 0.999 |
| reachability | machine, plain default set | 1.59 | 1.52 | 1.47 | - | - | 0.924 | - | 0.958 |
| reachability | reference engine | 13.40 | 13.16 | - | - | - | - | - | 0.982 |
| navier-stokes-euler | machine, candidate set | 35.01 | 33.61 | 29.92 | - | - | 0.855 | - | 0.960 |
| navier-stokes-euler | machine, plain default set | 66.89 | 63.62 | 58.03 | - | - | 0.867 | - | 0.951 |
| navier-stokes-euler | reference engine | 214.16 | 212.22 | - | - | - | - | - | 0.991 |
| cslib | machine, candidate set | 45.20 | 44.78 | 39.80 | - | - | 0.881 | - | 0.991 |
| cslib | machine, plain default set | 55.97 | 54.57 | 50.48 | - | - | 0.902 | - | 0.975 |
| cslib | reference engine | 137.35 | 134.23 | - | - | - | - | - | 0.977 |
| con-leche | machine, candidate set | 51.78 | 48.79 | 47.71 | - | - | 0.921 | - | 0.942 |
| con-leche | machine, plain default set | 12.10 | 11.71 | 10.57 | - | - | 0.874 | - | 0.969 |
| con-leche | reference engine | 66.07 | 66.40 | - | - | - | - | - | 1.005 |

**Oracle GraalVM - measurement only, not a default build.** Cold wall clock in seconds, fresh process, 8 workers (quiet windows only).

| corpus | configuration | CE image (as shipped) | Oracle, no profile | Oracle PGO A (Std + cedar) | Oracle PGO B (Init) | Oracle PGO C (2 Mathlib slices + Std) | Oracle PGO A (Std + cedar) / CE | Oracle PGO B (Init) / CE | Oracle, no profile / CE |
|---|---|--:|--:|--:|--:|--:|--:|--:|--:|
| mathlib | machine, candidate set | 17.82 | 16.73 | 16.65 | 15.57 | 15.81 | 0.934 | 0.873 | 0.939 |
| mathlib | machine, plain default set | 28.44 | 26.87 | 26.63 | - | - | 0.937 | - | 0.945 |
| mathlib | reference engine | 91.23 | 92.90 | 89.90 | - | - | 0.985 | - | 1.018 |
| cedar | machine, candidate set | 2.09 | 1.93 | 1.75 | - | - | 0.837 | - | 0.924 |
| cedar | machine, plain default set | 2.18 | 2.12 | 1.92 | - | - | 0.878 | - | 0.969 |
| cedar | reference engine | 4.80 | 4.61 | - | - | - | - | - | 0.960 |
| std | machine, candidate set | 1.49 | 1.36 | 1.24 | - | - | 0.835 | - | 0.918 |
| std | machine, plain default set | 1.70 | 1.52 | 1.43 | - | - | 0.847 | - | 0.898 |
| std | reference engine | 3.76 | 3.61 | - | - | - | - | - | 0.962 |
| init | machine, candidate set | 0.79 | 0.77 | 0.72 | - | - | 0.908 | - | 0.970 |
| init | machine, plain default set | 0.82 | 0.81 | 0.74 | - | - | 0.896 | - | 0.987 |
| init | reference engine | 2.18 | 2.09 | - | - | - | - | - | 0.958 |
| reachability | machine, candidate set | 0.64 | 0.62 | 0.54 | - | - | 0.845 | - | 0.964 |
| reachability | machine, plain default set | 0.66 | 0.67 | 0.58 | - | - | 0.884 | - | 1.012 |
| reachability | reference engine | 5.20 | 4.99 | - | - | - | - | - | 0.959 |
| navier-stokes-euler | machine, candidate set | 5.12 | 4.95 | 4.32 | - | - | 0.843 | - | 0.966 |
| navier-stokes-euler | machine, plain default set | 9.54 | 8.70 | 8.07 | - | - | 0.846 | - | 0.912 |
| navier-stokes-euler | reference engine | 29.73 | 33.19 | - | - | - | - | - | 1.117 |
| cslib | machine, candidate set | 6.44 | 6.35 | 5.54 | - | - | 0.859 | - | 0.985 |
| cslib | machine, plain default set | 7.92 | 7.58 | 7.18 | - | - | 0.906 | - | 0.956 |
| cslib | reference engine | 20.46 | 20.41 | - | - | - | - | - | 0.998 |
| con-leche | machine, candidate set | 41.31 | 38.20 | 38.46 | - | - | 0.931 | - | 0.925 |
| con-leche | machine, plain default set | 2.93 | 2.61 | 2.22 | - | - | 0.757 | - | 0.892 |
| con-leche | reference engine | 37.88 | 39.00 | - | - | - | - | - | 1.030 |

**Oracle GraalVM - measurement only, not a default build.** Peak RSS in MiB (cold process).

| corpus | configuration | CE image (as shipped) | Oracle, no profile | Oracle PGO A (Std + cedar) | Oracle PGO B (Init) | Oracle PGO C (2 Mathlib slices + Std) | Oracle PGO A (Std + cedar) / CE |
|---|---|--:|--:|--:|--:|--:|--:|
| mathlib | machine, candidate set | 4,087 | 4,373 | 4,355 | 4,495 | 4,531 | 1.066 |
| mathlib | machine, plain default set | 4,165 | 4,668 | 4,793 | 4,109 | 4,252 | 1.151 |
| mathlib | reference engine | 7,715 | 5,489 | 6,562 | - | - | 0.851 |
| cedar | machine, candidate set | 805 | 836 | 837 | 817 | 811 | 1.040 |
| cedar | machine, plain default set | 899 | 980 | 921 | 971 | 1,039 | 1.025 |
| cedar | reference engine | 824 | 1,012 | 871 | - | - | 1.057 |
| std | machine, candidate set | 626 | 581 | 600 | 622 | 592 | 0.958 |
| std | machine, plain default set | 774 | 819 | 761 | 737 | 786 | 0.984 |
| std | reference engine | 653 | 727 | 633 | - | - | 0.969 |
| init | machine, candidate set | 423 | 454 | 424 | 412 | 415 | 1.003 |
| init | machine, plain default set | 449 | 468 | 472 | 473 | 461 | 1.052 |
| init | reference engine | 473 | 536 | 459 | - | - | 0.969 |
| reachability | machine, candidate set | 656 | 495 | 573 | 631 | 540 | 0.874 |
| reachability | machine, plain default set | 554 | 598 | 516 | 559 | 535 | 0.931 |
| reachability | reference engine | 2,498 | 2,490 | 2,169 | - | - | 0.868 |
| navier-stokes-euler | machine, candidate set | 1,228 | 1,333 | 1,377 | 1,378 | 1,332 | 1.121 |
| navier-stokes-euler | machine, plain default set | 1,493 | 1,629 | 1,707 | 1,567 | 1,480 | 1.143 |
| navier-stokes-euler | reference engine | 1,284 | 1,553 | 1,311 | - | - | 1.021 |
| cslib | machine, candidate set | 1,845 | 1,842 | 1,955 | 1,928 | 1,926 | 1.060 |
| cslib | machine, plain default set | 1,738 | 2,002 | 1,975 | 1,812 | 1,787 | 1.137 |
| cslib | reference engine | 2,181 | 1,921 | 1,911 | - | - | 0.876 |
| con-leche | machine, candidate set | 8,406 | 6,517 | 6,603 | 7,179 | 6,760 | 0.785 |
| con-leche | machine, plain default set | 976 | 1,046 | 994 | 965 | 1,018 | 1.019 |
| con-leche | reference engine | 4,640 | 4,179 | 4,143 | - | - | 0.893 |

con-leche with the candidate set is one unit that is evaluated until the machine's memory bound stops it, twice
(finding INT7-F1 on the board: 38 to 41 s and 6.4 to 8.2 GiB where the plain default set needs 3 s and 1 GiB); the row
is kept because the arena has the test, not because it says anything about PGO.

### 1.3 Warm passes (the owner's metric)

**Oracle GraalVM - measurement only, not a default build.** Warm passes: one process with `-X passes=4`, 8 workers, the load as the checker decides (pipelined), `bench/warm.sh` with the image as native launcher, each Mathlib process in a window of its own, the cedar rows in one window.

| corpus | image | pass 1 s | passes 2-4 s | median of 2-4 s | vs CE | best of 2-4 s | CPU of the median pass s | peak RSS MiB | verdict |
|---|---|--:|---|--:|--:|--:|--:|--:|---|
| mathlib | CE image (as shipped) | 17.67 | 17.11 17.84 17.00 | 17.11 |  | 17.00 | 124.55 | 4148 | ACCEPT |
| mathlib | Oracle, no profile | 16.75 | 17.19 17.49 18.39 | 17.49 | +2.2 % | 17.19 | 125.85 | 4369 | ACCEPT |
| mathlib | Oracle PGO A (Std + cedar) | 15.60 | 15.38 15.60 15.49 | 15.49 | -9.5 % | 15.38 | 115.56 | 4459 | ACCEPT |
| mathlib | Oracle PGO B (Init) | 15.59 | 15.04 15.18 15.67 | 15.18 | -11.3 % | 15.04 | 112.48 | 4933 | ACCEPT |
| mathlib | Oracle PGO C (2 Mathlib slices + Std) | 16.33 | 16.20 16.85 15.64 | 16.20 | -5.3 % | 15.64 | 115.24 | 4611 | ACCEPT |
| cedar | CE image (as shipped) | 1.93 | 1.96 1.98 1.95 | 1.96 |  | 1.95 | 13.81 | 846 | ACCEPT |
| cedar | Oracle, no profile | 1.93 | 1.85 1.90 1.88 | 1.88 | -4.1 % | 1.85 | 13.38 | 845 | ACCEPT |
| cedar | Oracle PGO A (Std + cedar) | 1.72 | 1.69 1.69 1.64 | 1.69 | -13.8 % | 1.64 | 12.15 | 879 | ACCEPT |
| cedar | Oracle PGO B (Init) | 1.64 | 1.67 1.65 1.69 | 1.67 | -14.8 % | 1.65 | 11.96 | 878 | ACCEPT |
| cedar | Oracle PGO C (2 Mathlib slices + Std) | 1.71 | 1.69 1.66 1.69 | 1.69 | -13.8 % | 1.66 | 12.06 | 864 | ACCEPT |

### 1.4 The hot path alone, in instructions

**Oracle GraalVM - measurement only, not a default build.** The hot path alone: instructions of ONE WARM PASS over Mathlib = (a process that checks the export twice, `-X passes=2`) minus (a process that checks it once); candidate set, 8 workers. The two processes of a row ran under the same conditions: both in a quiet window on eight cores, or both as count runs beside other containers (squeezed onto two cores for most of their time).

| image / launcher | conditions | one pass (cold process) | two passes | the second, warm pass | warm pass / cold process | warm pass vs CE image |
|---|---|--:|--:|--:|--:|--:|
| CE image (as shipped) | count runs | 1,689.7 G | 3,387.6 G | 1,697.9 G | 1.005 |  |
| JVM launcher (CE, HotSpot C2) | quiet window | 1,368.7 G | 2,613.3 G | 1,244.6 G | 0.909 | -26.7 % |
| JVM launcher (CE, HotSpot C2) | count runs | 1,418.1 G | 2,594.7 G | 1,176.5 G | 0.830 | -30.7 % |

### 1.5 Builds and profiles

**Oracle GraalVM - measurement only, not a default build.** Builds (this host: aarch64, 12 CPUs visible to the builder, other builds and measurements running beside them; times are of a loaded host).

| artefact | GraalVM | native-image flags beyond `--gc=serial --no-fallback -R:+ExitOnOutOfMemoryError -R:MaxHeapSize=13g -H:+ReportExceptionStackTraces` | compiler line of the build output | build wall s | build CPU s | builder peak RSS MB | binary bytes |
|---|---|---|---|--:|--:|--:|--:|
| `bin/rlvm`: CE image (as shipped) | CE 25.5.5.0.3 | `-O3` + the H10 inline lists | optimization level: 3 | 125 | 989 | 5627 | 68,159,608 |
| instrumented image (training only) | Oracle 25.0.4 | `-O3 --pgo-instrument` | optimization level: 2, PGO: instrument | 164 | 862 | 5827 | 130,943,096 |
| `bin/rlvm-pgo`: Oracle PGO A (Std + cedar) | Oracle 25.0.4 | `-O3 --pgo=<3 files>` | optimization level: 3, PGO: user-provided | 161 | 613 | 4067 | 46,991,480 |
| Oracle, no profile | Oracle 25.0.4 | `-O3` | optimization level: 3, PGO: ML-inferred | 295 | 1124 | 6163 | 75,630,712 |
| Oracle, no profile, ML inference off | Oracle 25.0.4 | `-O3 -H:-MLProfileInference` | optimization level: 3, PGO: off | 243 | 986 | 4480 | 69,470,328 |
| Oracle PGO B (Init) | Oracle 25.0.4 | `-O3 --pgo=<2 files>` | optimization level: 3, PGO: user-provided | 113 | 609 | 4224 | 46,663,800 |
| Oracle PGO C (2 Mathlib slices + Std) | Oracle 25.0.4 | `-O3 --pgo=<4 files>` | optimization level: 3, PGO: user-provided | 146 | 680 | 4167 | 47,122,552 |
| Oracle PGO P (init-prelude) | Oracle 25.0.4 | `-O3 --pgo=<2 files>` | optimization level: 3, PGO: user-provided | 440 | 690 | 3608 | 46,401,656 |
| Oracle PGO B, from gzip-compressed profile files | Oracle 25.0.4 | `-O3 --pgo=<2 .iprof.gz files>` | optimization level: 3, PGO: user-provided | 249 | 732 | 3807 | 46,663,800 |
| Oracle PGO BR (Init: the machine's run + a reference-engine run) | Oracle 25.0.4 | `-O3 --pgo=<3 files>` | optimization level: 3, PGO: user-provided | 191 | 628 | 4072 | 46,991,480 |
| Oracle PGO M (Mathlib itself) | Oracle 25.0.4 | `-O3 --pgo=<2 files>` | optimization level: 3, PGO: user-provided | 193 | 751 | 3953 | 46,860,408 |
| Oracle PGO Bd (Init, trained with the plain default set) | Oracle 25.0.4 | `-O3 --pgo=<2 files>` | optimization level: 3, PGO: user-provided; profile applicability 28.10 %, relevance 99.02 % | 216 | 721 | 3893 | 46,729,336 |
| Oracle PGO S2 (STALE: Init, profile of the jars of 19:41Z) | Oracle 25.0.4 | `-O3 --pgo=<2 files>` (stale profile) | optimization level: 3, PGO: user-provided; profile applicability 27.06 %, relevance 98.72 % | 170 | 706 | 3969 | 46,532,728 |
| Oracle PGO R (init-prelude + Init, trained with the reference engine) | Oracle 25.0.4 | `-O3 --pgo=<3 files>` | optimization level: 3, PGO: user-provided; profile applicability 25.55 %, relevance 98.82 % | 220 | 712 | 3897 | 46,401,656 |
| Oracle PGO S1 (STALE: the profile of 07:5xZ, reference engine) | Oracle 25.0.4 | `-O3 --pgo=<3 files>` (stale profile) | optimization level: 3, PGO: user-provided; profile applicability 23.17 %, relevance 97.95 % | 487 | 738 | 3572 | 46,008,440 |
| instr-old | Oracle 25.0.4 | `-O3 --pgo-instrument` (jars of 19:41Z) | optimization level: 2, PGO: instrument | 285 | 964 | 5881 | 130,549,880 |

**Oracle GraalVM - measurement only, not a default build.** The profiles (`.iprof`: JSON, one file per training process; `native-image --pgo` also reads them gzip-compressed).

| profile | training runs (instrumented image) | training wall s | files | raw bytes | gzip -9 bytes |
|---|---|--:|--:|--:|--:|
| P | `--truffle-probe`; init-prelude - machine, candidate set | 6 | 2 | 14,440,954 | 2,538,436 |
| B | `--truffle-probe`; Init - machine, candidate set, 8 workers | 21 | 2 | 14,781,540 | 2,599,655 |
| A | `--truffle-probe`; Std; cedar - machine, candidate set, 8 workers (inside `build.sh --pgo`: 49.5 s) | 49.5 | 3 | 21,008,555 | 3,754,719 |
| C | `--truffle-probe`; Mathlib.Analysis.Complex.CauchyIntegral cone; Mathlib.FieldTheory.Galois.Basic cone; Std - machine, candidate set | 38 | 4 | 26,884,718 | 4,835,047 |
| M | `--truffle-probe`; Mathlib - machine, candidate set (instrumented: 116.6 s wall, 482 s CPU) | 119 | 2 | 14,902,646 | 2,633,734 |
| Bd | `--truffle-probe`; Init - machine, plain default set | 6 | 2 | 14,753,842 | 2,594,486 |
| BR | the two files of B + the Init run of R (no new training): the machine's run and a reference-engine run on Init | - | 3 | 20,440,612 | 3,645,987 |
| S2 | `--truffle-probe`; Init - machine of 19:15Z with its default set, instrumented image of the jars of 19:41Z | 5 | 2 | 14,568,258 | 2,552,725 |
| R | `--truffle-probe`; init-prelude; Init - reference engine, 8 workers | 15 | 3 | 19,743,536 | 3,526,524 |
| S1 | `--truffle-probe`; init-prelude; Init - reference engine of 06:54Z, one worker, batch load (the build of 07:5xZ) | - | 3 | 18,979,143 | 3,393,811 |

The whole `build.sh --pgo` run of variant A (Maven, CE image, Oracle jars, instrumented image, training on Std +
cedar, PGO image, verification of every launcher) took 517 s. Building the PGO image from profiles that already
exist is no slower than building the CE image (113 to 193 s of wall clock for the first four, on a host that was
loaded differently from build to build, and 610 to 750 CPU seconds, against 125 s and 990 CPU seconds): what costs
is the instrumented image (164 s) and the training (21 s for Init, 119 s for Mathlib), and they are needed only
when the profile is made. The PGO binary is 47 MB, the CE binary 68 MB, Oracle's without a profile 76 MB (69 MB
without its branch-probability model).

### 1.6 Correctness: the arena's 270 tests

**Oracle GraalVM - measurement only, not a default build.** The arena's complete test set (270 tests: 148 good incl. the eight corpora, 92 bad, 30 either) by the arena's procedure: one arena-shaped container per row, tests one after the other, the arena's time limit per test, `perf stat` around the run line.

| image | run line | good: accepted / rejected / declined, error, timeout | bad: rejected / accepted / other | either: accepted / rejected / declined | wrong verdicts | instructions, all 270 | of them the 259 tests below 8 MiB | status or exit code different from the CE image |
|---|---|---|---|---|--:|--:|--:|---|
| CE image (as shipped) | as shipped (reference engine) | 148 / 0 / 0 | 92 / 0 / 0 | 9 / 19 / 2 | **0** | 17,360.5 G | 1,288.59 G |  |
| Oracle PGO A (Std + cedar) | as shipped (reference engine) | 148 / 0 / 0 | 92 / 0 / 0 | 9 / 19 / 2 | **0** | 15,271.8 G | 1,124.84 G | none (270 of 270 equal) |
| CE image (as shipped) | `-X engine=machine -X machine.switches=default,eager,skipargs,congruence` | 148 / 0 / 0 | 92 / 0 / 0 | 11 / 17 / 2 | **0** | 4,355.6 G | 623.54 G |  |
| Oracle PGO A (Std + cedar) | `-X engine=machine -X machine.switches=default,eager,skipargs,congruence` | 148 / 0 / 0 | 92 / 0 / 0 | 11 / 17 / 2 | **0** | 3,923.9 G | 553.53 G | none (270 of 270 equal) |
| Oracle PGO B (Init) | `-X engine=machine -X machine.switches=default,eager,skipargs,congruence` | 148 / 0 / 0 | 92 / 0 / 0 | 11 / 17 / 2 | **0** | 3,812.9 G | 554.69 G | none (270 of 270 equal) |
| JVM launcher (CE, HotSpot C2) | `-X engine=machine -X machine.switches=default,eager,skipargs,congruence` | 148 / 0 / 0 | 92 / 0 / 0 | 11 / 17 / 2 | **0** | 5,019.9 G | 1,168.40 G | none (270 of 270 equal) |

Every configuration of every image and launcher accepted the corpus it was given in every run of this report
(201 measured runs of a corpus, none with a non-zero status). The two declined `either` tests are
`corner-cases/eta-struct-major-no-whnf` and `eta-struct-major-and-no-whnf` (the official kernel does not terminate
there; AR1.md 0.1). The `either` verdicts differ between the two run lines (9 / 19 against 11 / 17 accepted /
rejected: the candidate set accepts two `either` tests that the reference engine rejects, the tolerated kind of
owner note 13 (d)), not between the images.
The suite containers ran beside quiet windows and were parked on two cores for most of their time: their
instruction counts are valid, their times are not evidence. Even so no test used more than 22 % of its arena time
limit (`perf/gf3-matrix-product`, 15.3 s of 70 s, reference engine; with the candidate run line 7 %; through the JVM
launcher 17 %: `perf/ite-taken-branch-bad`, 19.1 s of 110 s). "Below 8 MiB" are 259 of the 270 tests.

## 2. Method

**Jars.** The published jars as they were at 2026-11-25T00:02:50Z (`work/pg1/logs/m2-jars-before-build.txt`):

```
rlvm-admit      398a117d4ad8   published 2026-11-24T18:01:13Z
rlvm-api        6ff40825ca1d   13:34:23Z
rlvm-cli        cac1433b287a   22:47:24Z   (Oracle variant: 10c4d6ccf815, compiled from the tree against Truffle 25.0.4;
                                            class files identical, only rlvm/cli/build.properties differs)
rlvm-driver     31fa08d9ce61   18:27:41Z
rlvm-export     dc8c4f1ef79a   23:47:05Z
rlvm-inductive  c15b14fffdd3   18:01:07Z
rlvm-level      75bb0db242c5   04:53:25Z
rlvm-load       b23a88e4c30b   23:47:10Z
rlvm-machine    b3adf60ca905   23:45:51Z
rlvm-main       21150203e39d   compiled from the tree by --published; byte-identical to the published jar
rlvm-nat        b1dda7562adb   06:53:51Z
rlvm-ref        a20c24a26b7c   22:58:43Z
rlvm-store      30979ee659b7   22:55:50Z
rlvm-truffle    ad3256d0751d   18:16:25Z
collections, jniutils, nativeimage, polyglot, truffle-api, truffle-compiler, truffle-runtime, word:
                25.5.5.0.3 (CE variant) / 25.0.4 (Oracle variant)
```

Default engine of the distribution: reference; the machine's
provider priority is 10.

**Build.** `work/pg1/build.sh` is `scripts/build.sh` as it was at 00:00Z with two lines changed: its own location, and
a private build directory (`work/pg1/build` instead of `work/build`), so that a 9-minute PGO build did not hold the
team's build lock. (`scripts/build.sh` itself was rewritten by its owner while this report was measured: the option
is now `--oracle-pgo` and builds the one executable from profiles in `profiles/`. The command lines below are those of
the copy.)

```bash
bench/slot.sh -c ni -n 2 -- work/pg1/build.sh --published --pgo --opt 3 --dist work/pg1/dist \
    --train work/exports/std.ndjson --train work/exports/cedar.ndjson \
    --train-arg --threads --train-arg 8 --train-arg -X --train-arg engine=machine \
    --train-arg -X --train-arg machine.switches=default,eager,skipargs,congruence
```

It builds, from the same jars: `bin/rlvm` (CE 25.5.5.0.3, `-O3 --gc=serial`, the machine's H10 inline lists: the image
as shipped), the instrumented image (Oracle 25.0.4, `--pgo-instrument`), the profile A and `bin/rlvm-pgo` (`--pgo=`).
The other Oracle images were built by `work/pg1/scripts/oracle-image.sh` from the same Oracle-variant jars with
exactly the arguments `build.sh` gives its Oracle images (`-O3 --gc=serial --no-fallback -R:+ExitOnOutOfMemoryError
-R:MaxHeapSize=13g -H:+ReportExceptionStackTraces`) plus `--pgo=<files>`, or nothing (the control), or
`-H:-MLProfileInference` (the second control: Oracle GraalVM infers branch probabilities with a built-in model when
it has no profile, "PGO: ML-inferred" in its build output). **The Oracle images have no H10 inline lists** (`build.sh`
gives them to the CE image only); the control therefore differs from the CE image in the compiler, its version and
the lists.

**Training** (`build.sh`, section "PGO", step c; `work/pg1/scripts/train.sh` for the further variants): the
instrumented image runs `--truffle-probe` and then checks each training export with `RLVM_EXIT=exit`, `--stats`,
`--threads 8 -X engine=machine -X machine.switches=default,eager,skipargs,congruence` (the load is pipelined by the
checker's own rule); each process writes one `.iprof`. A = Std + cedar, B = Init, C = two Mathlib module cones
(`work/exports/slices/good/Mathlib.Analysis.Complex.CauchyIntegral.cone.ndjson`, `...FieldTheory.Galois.Basic.cone...`)
+ Std, P = init-prelude, M = Mathlib itself. C contains declarations of the benchmark (their cones are part of
Mathlib) and M is the benchmark: both are here to see what training data can do, not as candidates.

**Measurement.** One run = one fresh arena-shaped container (`bench/arena-docker.sh --arena-limits --no-network`: the
8 performance cores, 16 GB) and in it `bench/measure.sh` = the arena's `perf stat -e instructions:u ... time ... sh -c
RUN` with `$IN`; RUN is the `run:` line of `scripts/arena/rlvm.yaml` as it was at 00:05Z (`--threads 8`, the status
mapping), executed verbatim with a working directory whose `work/dist/bin/rlvm` is the image under test, with the
engine options inserted after `--threads 8`:

- "candidate set": `-X engine=machine -X machine.switches=default,eager,skipargs,congruence`
- "plain default set": `-X engine=machine`
- "reference engine": `-X engine=reference` (in the suites: the line as shipped, which runs the reference engine)

Instruction counts need no quiet window; every CPU-seconds and wall-clock figure was taken inside a
`bench/quiet.py` window (`work/pg1/scripts/window.sh`: one measurement at a time on the host, the rest of the session
off the measurement cores; one Mathlib process per window, no window above five minutes): 27 windows between
00:11Z and 01:37Z, in turn with another agent's. Warm passes: `bench/warm.sh --launcher native --passes 4 --load
default` in windows of their own (`work/pg1/scripts/warm.sh`; the process runs on the host, pinned to the same eight
cores). Runs outside a window ("count runs", and the suites) give instructions and peak RSS only; they shared the
eight cores with each other and were moved onto two spare cores during every window of anybody. The "JVM launcher"
rows are `bin/rlvm-jvm` of the same distribution behind a two-line wrapper named `rlvm` (with `-XX:-EnableJVMCI
-XX:AOTCache=FILE` for the AOT rows, `-XX:-TieredCompilation` for the untiered row), so that the same run line runs
them. A cell that was
measured more than once shows the count of the quiet run; the largest difference between two counts of one cell of
a native image is 0.3 % with the machine (17 cells with two or more runs) and 1.2 % with the reference engine (9 cells), whose work depends more on how its workers interleave.

**Host.** aarch64, 10 Cortex-X925 + 10 Cortex-A725, 121 GB, shared with about ten other agents for the whole
session (load average 25 to 60). The heap cap of every image is its built-in 13 GB (`-R:MaxHeapSize=13g`); no `-Xmx`
was given, as in the arena.

## 3. Training data, foreign profiles, stale profiles

### 3.1 The choice of training data

**Oracle GraalVM - measurement only, not a default build.** What the choice of training data does: instructions of a cold process with the candidate set. Every profile = `--truffle-probe` + the exports named, trained with the machine (candidate set, 8 workers) unless the row says otherwise.

| image | trained on | profile files | raw bytes | gzip -9 bytes | Mathlib | vs CE | cedar | vs CE | Init | vs CE | navier-stokes-euler | vs CE |
|---|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| CE image (as shipped) | - | - | - | - | 1,687.6 G |  | 204.1 G |  | 81.2 G |  | 527.2 G |  |
| Oracle, no profile | - (ML-inferred branch probabilities, Oracle's default) | - | - | - | 1,705.6 G | +1.1 % | 204.9 G | +0.4 % | 82.0 G | +1.0 % | 520.3 G | -1.3 % |
| Oracle, no profile, ML inference off | - (`-H:-MLProfileInference`) | - | - | - | 1,741.0 G | +3.2 % | 209.0 G | +2.4 % | 83.3 G | +2.6 % | - |  |
| Oracle PGO P (init-prelude) | init-prelude (3.8 MB) | 2 | 14,440,954 | 2,538,436 | 1,587.0 G | -6.0 % | 192.3 G | -5.8 % | 75.6 G | -6.9 % | 518.5 G | -1.7 % |
| Oracle PGO B (Init) | Init (348 MB) | 2 | 14,781,540 | 2,599,655 | 1,483.2 G | -12.1 % | 174.7 G | -14.4 % | 70.3 G | -13.4 % | 450.7 G | -14.5 % |
| Oracle PGO A (Std + cedar) | Std + cedar (584 + 905 MB) | 3 | 21,008,555 | 3,754,719 | 1,542.8 G | -8.6 % | 181.7 G | -11.0 % | 73.3 G | -9.7 % | 463.3 G | -12.1 % |
| Oracle PGO A, second build | the same profile files, built a second time | 3 | 21,008,555 | 3,754,719 | 1,548.9 G | -8.2 % | 181.5 G | -11.1 % | 73.2 G | -9.9 % | - |  |
| Oracle PGO C (2 Mathlib slices + Std) | two Mathlib module cones (313 + 174 MB) + Std | 4 | 26,884,718 | 4,835,047 | 1,528.2 G | -9.4 % | 180.1 G | -11.7 % | 72.5 G | -10.7 % | 459.8 G | -12.8 % |
| Oracle PGO M (Mathlib itself) | Mathlib itself (the benchmark: not an admissible entry) | 2 | 14,902,646 | 2,633,734 | 1,506.7 G | -10.7 % | 178.9 G | -12.3 % | 72.0 G | -11.3 % | 454.6 G | -13.8 % |

Reading.

- **Any real export gives most of it, and the smallest was the best.** The three admissible sets end 8.6 to
  12.1 % below the CE image on Mathlib and 4 % apart from each other (1,483 G with Init, 1,528 G and 1,543 G with
  the larger sets). That order is not build noise: a second image built from the same profile files as A differs
  from the first by 0.4 % on Mathlib (1,548.9 against 1,542.8 G) and by 0.1 to 0.3 % on cedar and Init. With Init the
  image is 11 to 15 % below the CE image on all eight corpora, with Std + cedar 9 to 12 % (table 1.2). Why Init
  does better than the larger sets was not investigated; it is an observation on five profiles, not a rule.
- **Training on the benchmark itself is not better.** The profile recorded on Mathlib gives 1,506.7 G, -10.7 % against the CE image on
  Mathlib: between the Init profile and the larger sets, and behind Init on cedar, Init and navier-stokes-euler as
  well. So the choice of training data has no ceiling above the Init profile to offer, and nothing is given away
  by not training on the ranking corpus.
- **init-prelude alone** (1,840 declarations, 3.8 MB - a training input small enough to keep in the repository,
  one second of training): 1,587.0 G on Mathlib (-6.0 %), 192.3 G on cedar (-5.8 %), 75.6 G on Init (-6.9 %).
- **Without a profile, Oracle's compiler gives the machine nothing in instructions** (+-1 % on the eight corpora
  with its built-in branch-probability model; +3.2 % on Mathlib, +2.4 % on cedar, +2.6 % on Init with the model switched off) and 0 to 6 % less CPU time; for
  the reference engine it is worth 3 to 5 % of the instructions. The control is not a pure compiler comparison:
  the CE image has the machine's inline lists, the Oracle images do not (section 2).

### 3.2 A profile of another configuration or another engine, and a profile of older code

**Oracle GraalVM - measurement only, not a default build.** Foreign and stale profiles: instructions of a cold process. "vs fresh" compares with the image whose profile was trained on the same export with today's jars and the configuration of the row.

| configuration | image | Mathlib | vs CE | vs fresh | cedar | vs CE | vs fresh | Init | vs CE | vs fresh |
|---|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| machine, candidate set | CE image (as shipped) | 1,687.6 G |  |  | 204.1 G |  |  | 81.2 G |  |  |
| machine, candidate set | Oracle, no profile | 1,705.6 G | +1.1 % |  | 204.9 G | +0.4 % |  | 82.0 G | +1.0 % |  |
| machine, candidate set | Oracle PGO B (Init) | 1,483.2 G | -12.1 % |  | 174.7 G | -14.4 % |  | 70.3 G | -13.4 % |  |
| machine, candidate set | Oracle PGO BR (Init: the machine's run + a reference-engine run) | 1,526.0 G | -9.6 % | +2.9 % | 179.7 G | -11.9 % | +2.9 % | 72.2 G | -11.0 % | +2.7 % |
| machine, candidate set | Oracle PGO Bd (Init, trained with the plain default set) | 1,552.1 G | -8.0 % | +4.6 % | 181.7 G | -11.0 % | +4.0 % | 73.0 G | -10.1 % | +3.8 % |
| machine, candidate set | Oracle PGO S2 (STALE: Init, profile of the jars of 19:41Z) | 1,618.9 G | -4.1 % | +9.1 % | 195.4 G | -4.3 % | +11.9 % | 76.4 G | -5.9 % | +8.6 % |
| machine, plain default set | CE image (as shipped) | 2,806.0 G |  |  | 219.3 G |  |  | 85.0 G |  |  |
| machine, plain default set | Oracle, no profile | 2,822.0 G | +0.6 % |  | 219.7 G | +0.2 % |  | 85.7 G | +0.9 % |  |
| machine, plain default set | Oracle PGO Bd (Init, trained with the plain default set) | 2,544.9 G | -9.3 % |  | 192.8 G | -12.1 % |  | 75.4 G | -11.2 % |  |
| machine, plain default set | Oracle PGO B (Init) | 2,511.7 G | -10.5 % | -1.3 % | 188.5 G | -14.1 % | -2.3 % | 73.8 G | -13.2 % | -2.2 % |
| machine, plain default set | Oracle PGO S2 (STALE: Init, profile of the jars of 19:41Z) | 2,618.0 G | -6.7 % | +2.9 % | 207.1 G | -5.6 % | +7.4 % | 78.9 G | -7.2 % | +4.6 % |
| reference engine | CE image (as shipped) | 8,560.8 G |  |  | 533.2 G |  |  | 251.8 G |  |  |
| reference engine | Oracle, no profile | 8,274.2 G | -3.3 % |  | 515.7 G | -3.3 % |  | 244.2 G | -3.0 % |  |
| reference engine | Oracle PGO R (init-prelude + Init, trained with the reference engine) | - |  |  | 448.5 G | -15.9 % |  | 213.2 G | -15.3 % |  |
| reference engine | Oracle PGO BR (Init: the machine's run + a reference-engine run) | - |  |  | 447.8 G | -16.0 % | -0.2 % | 212.7 G | -15.5 % | -0.2 % |
| reference engine | Oracle PGO A (Std + cedar) | 7,503.7 G | -12.3 % |  | 466.1 G | -12.6 % | +3.9 % | 219.5 G | -12.8 % | +2.9 % |
| reference engine | Oracle PGO S1 (STALE: the profile of 07:5xZ, reference engine) | - |  |  | 487.8 G | -8.5 % | +8.8 % | 229.3 G | -8.9 % | +7.5 % |

Reading.

- **Train with the configuration that ships; it covers the smaller one too.** The Init profile recorded with
  the machine's plain default set, used with the candidate set, gives -8.0 % on Mathlib where the candidate
  set's own profile gives -12.1 % (cedar -11.0 against -14.4 %, Init -10.1 against -13.4 %): a third of the gain
  is lost. The other way round nothing is: with the plain default set the candidate set's profile is 1 to 2 %
  *better* than the default set's own (Mathlib -10.5 against -9.3 %) - the candidate set runs the default set's
  code and more.
- **A profile of the other engine still helps.** Profile A was recorded with the machine; with the reference
  engine its image needs 7,503.7 G on Mathlib where the CE image needs 8,560.8 G (-12.3 %; -12 to -14 % on the
  other seven corpora; a profile recorded with the reference engine itself on init-prelude + Init: -15 to -16 % on
  Init, Std and cedar): the two engines share the loader, the store, the admission of inductive types and the
  arithmetic, and no engine got slower from a profile that was not its own. The mis-training hazard of
  `docs/research/10-graalvm-platform.md` 7.4 (an image slower than the untrained one on a path the training
  never took) did not show in any cell of this report.
- **A profile with a run of each engine.** The two files of B plus the reference engine's run on Init (row BR; the shape of the profile that is in `profiles/` now): the reference engine gets all that its own profile gives (Init -15.5 %, cedar -16.0 %), and the machine gives up a fifth of its gain (Mathlib -9.6 % against the CE image instead of -12.1 %, cedar -11.9 % instead of -14.4 %, Init -11.0 % instead of -13.4 %). The machine's run alone still gives the reference engine 12 to 13 %. So for a binary whose checking engine is the machine the machine's run alone is the better profile (by 2.9 % of Mathlib's instructions); both runs are the better profile while the reference engine is the default engine.
- **A stale profile: the machine.** The instrumented image was built from the jars of the distribution of 19:41Z (machine `78c74ca92785`; 7 of the 14 RLVM jars were republished before 00:02Z, the machine's at least six times), trained on Init with that machine and its default set, and the profile was given to today's jars. Against a fresh profile recorded the same way (Init, plain default set) it keeps a half to three quarters of the gain: with the plain default set Mathlib -6.7 % against the CE image instead of -9.3 %, cedar -5.6 % instead of -12.1 %, Init -7.2 % instead of -11.2 %; with the candidate set Mathlib -4.1 % instead of -8.0 %, cedar -4.3 % instead of -11.0 %, Init -5.9 % instead of -10.1 %. No cell became slower than the CE image.
- **A stale profile: the reference engine.** The profile of the PGO build of 07:5xZ (`docs/PLATFORM.md`, Part II: init-prelude + Init with the reference engine of 06:54Z; since then the RLVM code in a profile grew from 1,468 to 3,329 methods) on today's jars, against a profile recorded today the same way: Init -8.9 % against the CE image instead of -15.3 %, Std -9.3 % instead of -15.6 %, cedar -8.5 % instead of -15.9 %. Seventeen hours and a doubled code base left three fifths of the gain.
- **Nothing warns.** The build output of the stale profiles reads like that of a fresh one ("profile relevance"
  98.7 % and 97.9 % against 99.0 % and 98.8 %; "applicability" 27.1 % and 23.2 % against 28.1 % and 25.6 %: table
  1.5), and by method identity and bytecode index 87 % of the branch contexts of a fresh profile are also in the profile that is 4.3 hours old (94 % by executed count) and 71 % in the one that is 17 hours old, while two fresh profiles of the same jars recorded with different switch sets share 95 % (`work/pg1/scripts/iprof-overlap.py`). The counters of an
  edited method stay attached to its old bytecode indices; the compiler cannot know.

### 3.3 What a profile is tied to

- **The compiler's profile format.** `native-image` has `-H:±PGOIgnoreVersionCheck` ("Prevent PGO run from failing
  if the profile version is not correct, and apply profiles on a best-effort basis"): so, by the option's own
  description, a profile whose format version (`"version": "1.0.0"` in the file) is not the compiler's fails the
  build by default. Not tested: the profiles here were written and read by the same Oracle GraalVM 25.0.4.
- **The names and the bytecode of the code it describes.** A profile is a list of types and methods by name and
  signature and of counters keyed by a calling context: `method:bytecode index < caller:bytecode index < ...`. A
  method that was renamed or removed loses its entries (they are dropped: "relevance" in the build output of
  `-H:+PGOPrintProfileQuality`); a method whose body was edited keeps them, attached to whatever now sits at the old
  bytecode indices. So the build output of a stale profile looks like that of a fresh one (table 1.5: relevance
  98.7 % against 99.0 %), and the only test is a measurement.
- **The JDK and Truffle classes of the GraalVM version** (four in ten of a profile's branch contexts and more than
  nine in ten of the methods it names are not RLVM code).
- **Not the architecture, by its format** (names and bytecode indices; nothing of the machine code). Whether an
  x86-64 build takes an aarch64 profile with the same effect could not be tested here.
- The instrumented image is compiled at `-O2` whatever level is asked for (its build output says so). Whether a
  profile depends on other flags of the final build (the inline lists, `-march`) was not tested.

## 4. The same question for the JVM

### 4.1 What exists (verified on the JDK of `.toolchain/graalvm-ce-25`, 25.0.5; the wall-clock measurements are J1's)

- **JDK 25's AOT cache** (JEP 483 / 514 / 515: `-XX:AOTCacheOutput=FILE` in a training run, `-XX:AOTCache=FILE`
  afterwards) is the JVM's "warm-up configuration": the classes of the training run, loaded and linked, and its
  method profiles, so that the JIT compiles from a ripe profile at once. **It carries no compiled code** (J1.md 3.1:
  the cache's code region is 0 bytes; this JDK has only the diagnostic switches `AOTAdapterCaching` and
  `AOTStubCaching`, both off, for adapters and stubs). It works only with `-XX:-EnableJVMCI`, is tied to the JDK
  build and to the jars (size, modification time, class path order: J1.md 3.4) and is 20.4 MB here (Std x 4 passes:
  106 s on the loaded host; Init x 4: 71 s). J1 measured it by wall clock: 0.4 to 1.1 s off the cold wall, every
  later pass 5 to 9 % slower in 11 of 11 comparisons; by the owner's metric it does not belong in the launcher.
- **Compiler-directive files** (`-XX:CompilerDirectivesFile=FILE`, JSON; a *diagnostic* option in this JDK, it
  needs `-XX:+UnlockDiagnosticVMOptions`) and `-XX:CompileCommandFile` give C1 and C2 instructions per method:
  exclude it, force or forbid an inlining, log, switch an intrinsic, compile in the foreground. They say how a
  method is compiled, not that it need not be: no profile and no code is in such a file, and the compiler threads
  do the same work. (J1.md 2.2: no inlining flag moves the warm pass of the machine either.)
- **Graal as the JIT** (`-XX:+UseJVMCICompiler`, Community Edition): none of its 74 options
  (`-XX:+JVMCIPrintProperties`) saves or loads profiles or code. (The image builder's `-H:SaveProfiles` /
  `-H:LoadProfiles`, in CE as well, replay the profile of ONE compilation for debugging; that is not PGO.)
- **Truffle's engine cache** (`engine.CacheStore` / `engine.CacheLoad`: a file with the engine's code, so that a
  later process starts with it): checked, not assumed. The shipped image refuses it ("Native image does not support
  auxilliary engine caching. Enable with -H:+AuxiliaryEngineCache at image build time"). **GraalVM CE 25.5.5.0.3 has
  that build option**, and a CE image built with `-H:+AuxiliaryEngineCache -H:ReservedAuxiliaryImageBytes=1073741824`
  (both experimental) stored the engine of `rlvm --truffle-probe` (81 kB) and loaded it in a second process
  ("Loaded image ... Engine from image successfully patched with new options") - so it is not an Oracle-only
  feature in this version, as far as storing and loading go. Whether compiled code comes back was not shown: the
  probe builds its root node anew and compiled it again. It is moot for the release: the cache holds
  Truffle-compiled code, and the machine's Truffle tier (switch `t2`) is off because it does not pay in steady
  state (`rlvm-truffle/NOTES-T3.md`, section 1), not because of its warm-up.

So no: nothing in the Community Edition lets a JVM process start with its hot path compiled. The compilation
happens in every process, and the arena counts the compiler threads' instructions.

### 4.2 The number that was missing: a cold JVM process in the arena's metric

Nobody had counted the instructions of the JVM launcher with the machine on Mathlib (PLATFORM.md, Part I, has the
counts below Mathlib only). They are here, with the launcher as shipped, without its C1 tiers, and with an AOT
cache trained with the candidate set (`work/pg1/scripts/aot-train.sh`):

**Oracle GraalVM - measurement only, not a default build.** The JVM launcher (GraalVM CE only: HotSpot C2, the launcher line as shipped, heap by engine) beside the images: instructions of a COLD process, JIT compiler and collector threads included, as the arena counts them. CPU s and wall from quiet windows where a row has them.

| corpus | configuration | image / launcher | instructions | vs CE image | CPU s | cold wall s | peak RSS MiB | runs (quiet) | every count of the cell, G |
|---|---|---|--:|--:|--:|--:|--:|--:|---|
| mathlib | machine, candidate set | CE image (as shipped) | 1,687.6 G |  | 127.5 | 17.82 | 4,087 | 2 (1) | 1687.6 / 1689.7 |
| mathlib | machine, candidate set | Oracle PGO B (Init) | 1,483.2 G | -12.1 % | 111.4 | 15.57 | 4,495 | 2 (1) | 1483.2 / 1487.0 |
| mathlib | machine, candidate set | JVM launcher (CE, HotSpot C2) | 1,368.7 G | -18.9 % | 125.1 | 16.66 | 5,800 | 3 (1) | 1368.7 / 1411.0 / 1425.2 |
| mathlib | machine, candidate set | JVM launcher, `-XX:-TieredCompilation` | 1,458.3 G | -13.6 % | - | - | 5,919 | 1 (0) | 1458.3 |
| mathlib | machine, candidate set | JVM launcher + AOT cache (Std x 4) | 1,396.9 G | -17.2 % | - | - | 5,881 | 1 (0) | 1396.9 |
| mathlib | machine, candidate set | JVM launcher + AOT cache (Init x 4) | 1,364.8 G | -19.1 % | 123.3 | 16.35 | 5,380 | 2 (1) | 1364.8 / 1376.8 |
| mathlib | machine, candidate set | JVM launcher + AOT cache (Init x 4), `-XX:-TieredCompilation` | 1,355.5 G | -19.7 % | 118.3 | 21.16 | 5,395 | 2 (1) | 1355.5 / 1357.2 |
| mathlib | machine, plain default set | CE image (as shipped) | 2,806.0 G |  | 203.5 | 28.44 | 4,165 | 1 (1) | 2806.0 |
| mathlib | machine, plain default set | Oracle PGO B (Init) | 2,511.7 G | -10.5 % | - | - | 4,109 | 1 (0) | 2511.7 |
| mathlib | machine, plain default set | JVM launcher (CE, HotSpot C2) | 2,197.3 G | -21.7 % | - | - | 6,033 | 1 (0) | 2197.3 |
| cslib | machine, candidate set | CE image (as shipped) | 569.3 G |  | 45.2 | 6.44 | 1,845 | 2 (1) | 569.2 / 569.3 |
| cslib | machine, candidate set | Oracle PGO B (Init) | 499.7 G | -12.2 % | - | - | 1,928 | 1 (0) | 499.7 |
| cslib | machine, candidate set | JVM launcher (CE, HotSpot C2) | 579.4 G | +1.8 % | - | - | 2,985 | 2 (0) | 579.4 / 579.5 |
| navier-stokes-euler | machine, candidate set | CE image (as shipped) | 527.2 G |  | 35.0 | 5.12 | 1,228 | 2 (1) | 527.2 / 528.1 |
| navier-stokes-euler | machine, candidate set | Oracle PGO B (Init) | 450.7 G | -14.5 % | - | - | 1,378 | 1 (0) | 450.7 |
| navier-stokes-euler | machine, candidate set | JVM launcher (CE, HotSpot C2) | 546.0 G | +3.6 % | - | - | 2,437 | 2 (0) | 532.3 / 546.0 |
| con-leche | machine, candidate set | CE image (as shipped) | 477.4 G |  | 51.8 | 41.31 | 8,406 | 2 (1) | 477.4 / 477.4 |
| con-leche | machine, candidate set | Oracle PGO B (Init) | 416.0 G | -12.9 % | - | - | 7,179 | 1 (0) | 416.0 |
| con-leche | machine, candidate set | JVM launcher (CE, HotSpot C2) | 380.6 G | -20.3 % | - | - | 4,674 | 2 (0) | 380.6 / 408.3 |
| cedar | machine, candidate set | CE image (as shipped) | 204.1 G |  | 13.9 | 2.09 | 805 | 2 (1) | 204.1 / 204.1 |
| cedar | machine, candidate set | Oracle PGO B (Init) | 174.7 G | -14.4 % | - | - | 817 | 1 (0) | 174.7 |
| cedar | machine, candidate set | JVM launcher (CE, HotSpot C2) | 340.0 G | +66.6 % | - | - | 1,551 | 2 (0) | 306.6 / 340.0 |
| cedar | machine, candidate set | JVM launcher + AOT cache (Std x 4) | 251.2 G | +23.1 % | - | - | 1,468 | 1 (0) | 251.2 |
| cedar | machine, candidate set | JVM launcher + AOT cache (Init x 4) | 243.9 G | +19.5 % | - | - | 1,454 | 1 (0) | 243.9 |
| std | machine, candidate set | CE image (as shipped) | 140.5 G |  | 9.9 | 1.49 | 626 | 2 (1) | 140.2 / 140.5 |
| std | machine, candidate set | Oracle PGO B (Init) | 121.9 G | -13.2 % | - | - | 622 | 1 (0) | 121.9 |
| std | machine, candidate set | JVM launcher (CE, HotSpot C2) | 236.2 G | +68.1 % | - | - | 1,286 | 2 (0) | 236.2 / 250.1 |
| init | machine, candidate set | CE image (as shipped) | 81.2 G |  | 5.4 | 0.79 | 423 | 2 (1) | 81.2 / 81.2 |
| init | machine, candidate set | Oracle PGO B (Init) | 70.3 G | -13.4 % | - | - | 412 | 1 (0) | 70.3 |
| init | machine, candidate set | JVM launcher (CE, HotSpot C2) | 196.8 G | +142.4 % | - | - | 782 | 2 (0) | 185.0 / 196.8 |
| init | machine, candidate set | JVM launcher + AOT cache (Std x 4) | 121.5 G | +49.6 % | - | - | 710 | 1 (0) | 121.5 |
| init | machine, candidate set | JVM launcher + AOT cache (Init x 4) | 118.3 G | +45.7 % | - | - | 717 | 1 (0) | 118.3 |
| init | reference engine | CE image (as shipped) | 251.8 G |  | 14.6 | 2.18 | 473 | 2 (1) | 250.3 / 251.8 |
| init | reference engine | JVM launcher (CE, HotSpot C2) | 207.1 G | -17.8 % | - | - | 1,353 | 1 (0) | 207.1 |
| reachability | machine, candidate set | CE image (as shipped) | 16.3 G |  | 1.6 | 0.64 | 656 | 2 (1) | 16.3 / 16.3 |
| reachability | machine, candidate set | Oracle PGO B (Init) | 14.5 G | -10.9 % | - | - | 631 | 1 (0) | 14.5 |
| reachability | machine, candidate set | JVM launcher (CE, HotSpot C2) | 59.2 G | +263.0 % | - | - | 1,371 | 2 (0) | 59.2 / 62.4 |

Reading.

- **On everything short the JIT is the bill.** With the candidate set a cold JVM process needs 185 to 197 G on
  Init where the image needs 81 G, 307 to 340 G against 204 G on cedar, 59 to 62 G against 16 G on reachability;
  the two meet at the size of navier-stokes-euler and cslib (532 to 546 G against 527 G; 579 against 569 G).
- **On Mathlib the bill is paid back inside the run.** 1,368.7 G on eight quiet cores, against 1,687.6 G for the CE
  image and 1,483.2 G for the best admissible PGO image - with the same CPU time (125.1 against 127.5 s) and a
  second less wall clock (16.66 against 17.82 s), and with the plain default set as well (2,197 against 2,806 G).
  C2 compiles the machine better than the ahead-of-time compiler does even when that has a profile: one warm
  Mathlib pass costs 1,180 to 1,240 G on the JVM and 1,698 G in the image (table 1.4). The price is in the last column: **the
  JVM's count depends on how its compiler threads are scheduled** - 1,411 and 1,425 G in two runs that were
  squeezed onto two cores beside other containers, 4 % above the quiet run, where an image repeats to 0.1 %.
- **The AOT cache halves the JVM's handicap on short runs and does nothing on Mathlib**: Init 196.8 -> 121.5 G,
  cedar 340.0 -> 251.2 G (it removes the phase in which C1 code collects the profile, not the C2 compilations);
  Mathlib 1,364.8 against 1,368.7 G in quiet windows, 1,377 and 1,397 against 1,411 and 1,425 G in count runs.
  Without the C1 tiers (`-XX:-TieredCompilation`: interpreter and C2 only) a cold Mathlib process needs 1,458.3 G (a count run); with the Init cache as well 1,355.5 G in a quiet window (1,357.2 G in a count run) - the lowest count of this report - at the price of the wall clock: 21.16 s.
- **So "a JIT cannot win the arena's metric" is true of the short tests and false of the one that ranks** (and of
  con-leche: 381 to 408 G against 477 G). All 270
  tests through the JVM launcher, under the arena's time limits: the same status and exit code as the CE image on
  every test, no timeout (the closest: 19 s of 110 s in a container squeezed onto two cores), 0.22 s per small
  test where the image needs 0.014 s, 1.87 times the image's instructions over the tests below 8 MiB (table 1.6).
- **What it does not give**: a place (1,355 to 1,369 G is 1 to 2 % above pestka's 1,339.6 G on this host: sixth,
  like the image), one executable (owner note 16: the JVM launcher is a script, 22 jars and the JDK the build
  downloads), a count that repeats, or the image's memory (5,400 to 5,800 MiB against 4,100). And it is no more "the hot
  path directly" than before: a cold JVM process still spends a tenth to a sixth of its instructions getting warm
  (table 1.4), and JDK 25 has nothing that hands a fresh process the compiled code.

## 5. What shipping it would take (for the owner to decide)

Owner note 16 (2026-11-24, recorded while this report was measured) already sets the frame: the Oracle GraalVM build
with a shipped profile is an OPTION of the build, never its default, and the maintainer accepts the licence on the
development machine for experiments. The list below is what the measurements add to that.

1. **What would be in the repository: a profile, about 2.6 MB.** For the Init profile (variant B) two files:
   `probe.iprof` (8.65 MB of JSON, 1.47 MB gzip-compressed: the profile of `--truffle-probe`, i.e. of the Truffle
   runtime compiler inside the image) and `train-1.iprof` (6.13 MB, 1.13 MB compressed: the checker on Init); together
   14.8 MB raw, 2.60 MB compressed. `native-image --pgo=` reads gzip-compressed profiles (its help text says so, and an image built here from the two compressed files reports "PGO: user-provided" and has the size of the image built from the plain files, 46,663,800 bytes; it needs 70.4 G on Init and 174.6 G on cedar, the plain-file image 70.3 G and 174.7 G). Std + cedar: 3.75
   MB compressed; three training exports: 4.84 MB. A profile is not a configuration a user can read or edit, and
   every refresh adds its 2.6 MB to the history of the repository.
2. **Which profile: Init, recorded with the configuration that ships.** It is the best of the admissible ones on
   Mathlib and on each of the other seven corpora in this measurement, its training takes 21 s, it does not
   contain the ranking corpus, and it is what the three arena entries that use PGO train on. A profile recorded
   with another switch set than the one that ships loses a third of the gain (section 3.2). (While this report
   was measured, the owner of the packaging added the optional build `scripts/build.sh --oracle-pgo` and a first
   `profiles/` directory, id `88f067bfcb35`: `--truffle-probe`, Init with the default configuration and Init with the
   candidate set, 3.6 MB compressed, with a provenance file. That build was not measured here; row BR of table 3.2
   has the same shape of training.)
3. **A refresh with every release, and a guard.** A profile is tied to the bytecode of the jars and to the Oracle
   GraalVM version (section 3.3). Measured (section 3.2): a profile that was 4.3 hours old kept a half to three quarters of its gain, one that was 17 hours old three fifths, and neither made anything slower than the CE image - at this project's pace of change, which a released code base will not have. Nothing in the build output tells a fresh profile from a stale one, so
   the profile needs its own provenance file (the jar hashes it was recorded with, the GraalVM version, the
   training command) and the optional build should say when the jars it compiles are not the jars of the profile.
   Regenerating costs one instrumented image (164 s here) and the training (21 s).
4. **The Oracle GraalVM download, in the build that asks for it.** The archives are 380.5 MB (linux-x64) and
   351.6 MB (linux-aarch64), downloadable without a login from version-pinned URLs with published checksums
   (`docs/research/10-graalvm-platform.md`, section 7.3; not fetched again for this report). The build also needs
   the Truffle jars of that GraalVM's version (25.0.4 instead of 25.5.5.0.3): eight more third-party jars to pin by
   checksum, and `rlvm-cli` compiled against them (its class files come out identical).
5. **The licence, in one sentence** (no legal advice): the GraalVM Free Terms and Conditions permit, free of
   charge, internal use of the unmodified program "for the purposes of developing, testing, prototyping and
   demonstrating your applications, and running the Program for Your own personal use or internal business
   operations" and its redistribution without a fee, and deem the parts of it that a Native Image output contains
   an unmodified program under the same terms - it is not an open-source licence. Text:
   <https://www.oracle.com/downloads/licenses/graal-free-license.html> (the same text is `LICENSE.txt` in the
   archive). Owner note 16 accepts it on the development machine for experiments. **In the arena the build would be run,
   and the licence accepted, by the arena's operators.** That is a question to put to them before a definition with
   such a build line is submitted; nothing in this repository can answer it.
6. **What the arena's rules say.** The arena's README (`refs/lean-kernel-arena/README.md`) has no rule about
   downloads in the build step or about prebuilt artefacts; it says "Checkers are not run in a sandbox. We assume
   good faith from all contributors", and the build time is not measured (`docs/research/01-arena-and-export-format.md`,
   "Build"). Precedent, from the definitions in `refs/lean-kernel-arena/checkers/`: **the entries ranked 1, 2 and 5
   are PGO builds that train at build time on Init** - nano-lean (`scripts/pgo.sh`: a sha256-pinned `init.ndjson.gz`
   from a release asset, `-Cprofile-generate`, one run over Init, `-Cprofile-use`), gruszka and pestka (a
   sha256-pinned `init.susz` from a dataset host, `scripts/build-pgo.sh`, `-C target-cpu=native`); kefir-wasm installs
   a toolchain with curl; RLVM's own definition downloads GraalVM CE and Maven. None of them ships a recorded
   profile, none ships a prebuilt binary, and all of their compilers are open source.
7. **So there are two ways to build it, and the arena's precedent is the other one.** (a) Owner note 16: the
   profile is in the repository, the optional build needs no training, costs no more time than the CE build
   (table 1.5) and can be stale. (b) As the leaders do: the build makes the instrumented image, trains on a pinned
   Init export that it downloads (348 MB; the repository has none) and builds again - about 3 minutes more on this
   host, never stale, nothing recorded in the repository but a checksum. The measurements of this report are the
   same for both.
8. **The image is not reproducible bit for bit.** Two builds from the same jars, the same profile files and the same command line have the same size and different bytes - the PGO image (46,991,480 bytes, 81 % of them different) and the CE image (68,159,608 bytes, 86 % different) alike, so it is the image builder, not the profile. The two PGO builds agree to 0.4 % in instructions on Mathlib and 0.1 to 0.3 % on cedar and Init. A release can publish the hash of the binary it built;
   nobody can confirm that hash by building again.
9. **What it buys on the arena's board: 12 %, and no place.** In this host's counts the candidate set stands at
   1,687.6 G with the CE image; the next entry ahead is pestka, which needs 1,339.6 G on this host (three runs,
   `bench/baselines.json`; 1,475.3 G published on x86-64), the next behind is kefir (2,577.3 G published). With the
   Init profile the image needs 1,483.2 G: 144 G (10 %) short of pestka, the same sixth place by AR1's projection
   (AR1.md 0.3; fifth only at the favourable end of its conversion range). The JVM launcher stands at 1,355 to 1,369 G on eight quiet cores (as shipped; with an AOT cache; with the cache and without its C1 tiers): 1 to 2 % short of pestka, with a count that moves by 4 % with the scheduling - the same place.
10. **x86-64.** Every number here is aarch64. The arena's runner is x86-64: the size of the gain, the
    reproducibility and whether a profile recorded on aarch64 serves an x86-64 build have to be measured there
    before anything is promised (a profile is architecture-neutral by its format, section 3.3; that is not a test).

## 6. What could not be established

- **x86-64**: nothing. No such machine here; the Oracle GraalVM under `.toolchain/` is the aarch64 build.
- **A profile recorded on one architecture, used on the other**; **a profile recorded with another Oracle GraalVM
  version** (the build has a version check and an option to ignore it; only 25.0.4 was at hand).
- **Wall clock and CPU time below about 5 %.** Every timed cell is ONE run in a quiet window on a host whose other
  twelve cores were busy (load average 25 to 85); J1 found up to 5 % between two identical 8-worker runs. The
  instruction counts are exact to the spreads shown.
- **How much of "Oracle without a profile against CE" is the H10 inline lists.** The CE image is built with the
  machine's lists, the Oracle images without (as `build.sh` builds them); an Oracle image with the lists was not built.
- **Cells that have a count and no clock, or nothing**: the windows were shared with eight to twelve other
  measurements in the queue, so only the CE image, the Oracle image without a profile and PGO A were timed on all
  eight corpora (PGO A with the reference engine on Mathlib only); PGO B and PGO C were timed on Mathlib and
  cedar and counted elsewhere; PGO B and PGO C were not run with the reference engine at all; the reference
  engine's own fresh and stale profiles (R, S1) were counted on Init, Std and cedar, not on Mathlib.
- **One worker**, and any worker count other than 8.
- **A stale profile at the distance of a release.** Two distances were measured, both inside this project's first
  two days, in which the code more than doubled within a day: 4.3 hours and 17 hours. A profile exactly one publication old was not measured: no jar was published between 00:02Z and the end of the series.
- **Whether the engine cache of Truffle returns compiled code in the Community Edition** (section 4): it stores and
  loads; the probe compiles its root node again after the load.
- **`--pgo-sampling`** (profiles from `perf` samples instead of an instrumented image) and **G1** (Oracle GraalVM
  only; `docs/research/10-graalvm-platform.md` 7.4 found no instruction gain from it) were not tried.
- **The JVM launcher's instruction count on the arena's runner**: it depends on how the compiler threads are
  scheduled beside the workers (the counts of one cell differ by up to 10.9 % here - on cedar; 4 % on Mathlib - against 0.3 % for an
  image with the machine),
  and on x86-64 it is unmeasured like everything else.
- **Whether the arena's maintainers accept a build line that downloads Oracle GraalVM**: not a measurement.

## 7. Files and reproduction

```bash
# images (5 + 9 minutes; one of the host's two native-image slots each)
bench/slot.sh -c ni -n 2 -- work/pg1/build.sh --published --pgo --opt 3 --dist work/pg1/dist --train ... (section 2)
work/pg1/scripts/oracle-image.sh ora                                  # Oracle, no profile (the control)
work/pg1/scripts/train.sh B init                                      # a further profile: work/pg1/profiles/B
work/pg1/scripts/oracle-image.sh pgoB --profiles work/pg1/profiles/B  # ... and its image: work/pg1/oimg/pgoB/rlvm
work/pg1/scripts/mkimg.sh pgoB work/pg1/oimg/pgoB/rlvm                # a checkout directory for the run line
# measurements
work/pg1/scripts/window.sh TAG pgoB cand mathlib                      # one quiet window: instructions, CPU, wall, RSS
work/pg1/scripts/warm.sh cand 4 mathlib pgoB                          # warm passes in a quiet window
work/pg1/scripts/count.sh TAG pgoB mdef cedar pgoB mdef init          # instruction counts, no window
work/pg1/scripts/suite.sh pgoA cand                                   # the 270 tests by the arena's procedure
python3 -I work/pg1/scripts/cmp-suites.py work/pg1/reports/suite.ce.cand.json work/pg1/reports/suite.pgoA.cand.json
python3 -I work/pg1/scripts/iprof-overlap.py work/pg1/profiles/Bd work/pg1/profiles/S2
# the JVM's counterpart of a training run, and the launcher with the cache under the run line's name
work/pg1/scripts/aot-train.sh init4 init 4 && work/pg1/scripts/window.sh TAG jvmaot-init4 cand mathlib
# this document: the tables, the quoted numbers, the text
python3 -I work/pg1/scripts/report.py                                 # every table
python3 -I work/pg1/scripts/tokens.py                                 # the numbers the prose quotes
python3 -I work/pg1/scripts/report.py --fill work/pg1/PG1.template.md docs/research/profiles/PG1.md
```

Files: `work/pg1/reports/IMAGE.CONFIG.CORPUS.TAG.json` (one measured run each: exit code, instructions, CPU, wall,
RSS, whether it was inside a window, the run line), `suite.IMAGE.CONFIG.json` (270 tests each), `work/pg1/warm.tsv`
and `warm-logs/`, `work/pg1/profiles/*/` (the `.iprof` files, the training logs, `INFO.txt`), `work/pg1/oimg/*/`
(`build.log`, `info.txt`, the binaries), `work/pg1/dist` (the distribution of `build.sh --pgo`: CE image, PGO image A,
JVM launcher, jars, `BUILD-INFO.txt`), `work/pg1/logs/` (window, chain and suite logs, `repro-*.txt`,
`m2-jars-before-build.txt`), `work/pg1/aot/` (the JDK AOT caches and their training logs), `work/pg1/stale-0756Z/`
(the profiles of the PGO build of 07:5xZ), `work/pg1/scripts/` (everything above), `work/pg1/PG1.template.md` and `work/pg1/sec/` (the text of this document).
