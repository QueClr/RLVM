# Platform: which launcher ships, with which flags, with how many workers

Two parts. **Part I** (wave 7, 2026-11-24 13:30Z to 14:25Z) is the platform gate of RECONCILIATION
section 3: measured with the **machine** as engine (`-X engine=machine`), the engine that will ship,
and it ends with the decision. **Part II** (wave 4, the same day, 08:00Z to 11:50Z) is the rehearsal
with the reference engine: launchers, host JIT, collectors and heap sizes in more detail than Part I
repeats; where the two parts disagree, Part I is the newer measurement of the engine that matters.

Order of the columns everywhere (OWNER-NOTES 10, RECONCILIATION 0): **CPU time** (task-clock over all
threads), wall clock, peak RSS, instructions.

# Part I - the machine as engine (wave 7)

## I.0 The answer

**Read this first.** This gate was planned and measured under owner note 10 (CPU time of a run first,
then wall clock): every run below is a FRESH process on a batch load, so the JVM's warm-up is part of
every figure. While it was being written the owner changed the target (OWNER-NOTES item 12, read
here at 14:37Z):
*wall clock after warm-up, with pipelined loading, is the goal; warm-up cost is not charged; the
platform question is decided by warm wall clock*. By that criterion the four points below are the
answer for a COLD process - which is what an arena run is - and **the platform question itself is
open again**: section I.7 has the first indication (the machine's warm passes on HotSpot need less
CPU time than the image, as the reference engine's long runs do) and what is needed to decide it.

For a fresh process per export (the arena's run), by CPU time, then wall clock:

1. **The CE native image is the launcher of a cold run** (`work/dist/bin/rlvm`, GraalVM CE 25,
   `-O3`), and the arena definition stays on it. With the machine as
   engine it needs **0.68 / 0.46 / 0.37 times the JVM launcher's CPU time with 1 / 4 / 8 workers**
   (medians of 8 paired runs each on Init, Std, the Cedar slice and reachability; the image wins all
   24 pairs, 0.24 to 0.88), 0.62 times its wall clock with four workers, and a third to a half of its
   memory. Init, four workers: 8.1 s CPU / 2.9 s wall / 330 MB against 18.4 s / 4.9 s / 770 MB. The
   JVM pays about 3.7 s of C2 compilation per run and runs every worker slowly until it is done; a
   run of the machine is too short to earn that back. The JVM launcher remains the cheaper platform
   for long runs of the *reference* engine (mathlib, eight workers: 500.5 s against 624.6 s CPU),
   which is the fallback engine and not what ships. `scripts/arena/rlvm.yaml` already names the image:
   nothing to switch.
2. **Flags: the image as it is built today** - `-O3 --gc=serial -R:MaxHeapSize=13g
   -R:+ExitOnOutOfMemoryError`, no nursery flag, no `-march`. `-O3` (10 to 18 % less CPU time than
   `-O2` for the machine) was the arena build's level and is now also the default of
   `scripts/build.sh`. The serial collector is the only CE collector that collects; the heap limit is
   a limit, not a reservation (RSS 250 MB to 1.9 GB on the four corpora). The JVM launcher keeps its
   flags (`-XX:+UseSerialGC -XX:-UseJVMCICompiler -XX:CICompilerCount=2 -Xms13g -Xmx13g -Xmn1g`); it is
   the launcher of the guards, not of the arena.
3. **Workers: `--threads 4` by CPU time, 8 by wall clock; the run line was left at 4.** With four
   workers the image's CPU time is that of one worker (1.02, 1.01 and 0.98 times on Init, Std and
   the Cedar slice) at 2.5 to 2.7 times the speed; eight workers cost 1.07 times the CPU time of
   one on all three for 18 % less wall clock than four on Init and Std and nothing on the other two
   corpora. Under note 10 that reads "four"; under note 12 (wall clock is the goal, CPU time a
   diagnostic) it reads "eight, or six once it is measured" (section I.3). The run line was not
   changed: all 230 published tests under the arena's limits with another count could not be run
   in this session.
4. **What this rests on, and what it does not settle.** Two repeats of the matrix (48 runs,
   13:33Z to 13:49Z, host load 26 to 95). The third repeat, the six-worker runs and the `bench/track`
   entries of 14:13Z to 14:21Z were taken while the host was thrashing (load 230 to 266, 40 GB of
   swap; orchestrator, `work/status/requests.log` 14:32Z) and are discarded; their repetition waited
   for the measurement lock behind eight other queues and was withdrawn at 14:34Z. Not settled: x86-64 (the
   runner's architecture; no such machine here); the machine on mathlib-sized input on either
   launcher (section I.4); the machine's staging switches and its compiled tier, which its own
   workflow is changing today (measured here: jar `608b84b2b142`, default switches).

## I.1 What was measured, and how

- **Distribution** (frozen for the whole series): `work/steward/w7/dist`, built 13:30:43Z by

  ```
  scripts/build.sh --published --no-pgo --opt 3 --dist work/steward/w7/dist
  ```

  from the jars published at that moment (`BUILD-INFO.txt` there): the pipeline jars of the end of
  wave 4 / 5 (driver `eb0944caa630`, store `45df9b63e7af`, ref `035f31e854d9`, admit `a817cd0c9d35`,
  load `30617170f7a3`, api `46e5486d1a02`) and the machine jar **`608b84b2b142`** (the wave-4
  integrator's final jar; the wave-6 machine workflow published newer ones from 13:39Z on, which are
  not in these numbers). `bin/rlvm` = CE native image, GraalVM CE 25.5.5.0.3, `-O3 --gc=serial
  -R:MaxHeapSize=13g`, 64.9 MB: exactly what `scripts/arena/build.sh` builds. `bin/rlvm-jvm` = the same
  jars on HotSpot with the launcher's flags (`-XX:+UseSerialGC -XX:-UseJVMCICompiler
  -XX:CICompilerCount=2 -Xms13g -Xmx13g -Xmn1g`).
- **Engine**: `-X engine=machine` with the machine's default switches (v0: one session per
  declaration; the staging switches `warm,gated,expect,probes` of `work/status/machine-integration.md`
  are off). The reference engine re-checks what the machine declines; on the four corpora below it
  declines nothing (section I.6), so every figure is the machine's own.
- **One run** = the arena run line with the binary and the worker count replaced, in a fresh
  arena-shaped container (`bench/arena-docker.sh --arena-limits --no-network`: 8 performance cores,
  16 GB), measured like the arena measures (`bench/measure.sh`: `perf stat` + GNU time around
  `sh -c`):

  ```
  RLVM_STATUS_BASE=10 DIST/bin/BINARY --threads N "$IN" -X engine=machine -X load=batch; case $? in ...
  ```

  One-worker runs are in the same eight-core container (not the two-core container of Part II).
- **Matrix**: launcher {`jvm`, `native`} x workers {1, 4, 8} x corpus {Init 348 MB, Std 584 MB, the
  Cedar slice `cedar-hot-ax` 133 MB, reachability 19 MB}, the two launchers of a group back to back.
  **Two repeats are valid** (48 runs, 13:33Z to 13:49Z). A third repeat and three runs with six workers
  for the image (36 runs, 14:13Z to 14:20Z) fell into the forty minutes in which the host was out of
  memory and thrashing; by the orchestrator's ruling they are discarded and kept apart
  (`w7-machine-thrashing.jsonl`), and section I.3 quotes them only to say what they cannot show. `scripts/platform-matrix.py` (`work/steward/w7/queue.sh` and
  `queue2.sh` have the exact calls); raw records with the host's load average before and after every run in
  `work/steward/platform/results/w7-*.jsonl`.
- **Lock**: every run was made under the measurement lock `work/perf-history/.ladder.lock` (the first
  six groups with a lock per group, the rest in holds of 4 to 8 minutes). The lock was the bottleneck
  of the day: up to ten queues behind it, waits of 20 minutes, once held for more than ten minutes
  by a native-image build that had inherited it (my line of 14:02Z in `work/status/requests.log`).
- **The host was not quiet.** The 1-minute load average of the host (20 CPUs) was 26 to 95 during
  the two valid repeats: thirty agents were testing, building and measuring. Other owners' processes
  are not confined to the cores outside the container, so CPU time is inflated by an amount that
  changes from minute to minute (Cedar slice, image, one worker: 3.60 and 3.64 s here against 3.17 s
  at load 7 to 10 two hours earlier, with the same 46.4 G instructions; Init 7.94 s against 7.77 s).
  The figure of a row is the **minimum** over its repeats (median in parentheses); the `Load` column
  is the highest 1-minute load average at the end of a run of the row. Instruction counts do not
  depend on the load; compare launchers by the paired ratios.

## I.2 Launcher x workers: Init, Std, Cedar slice, reachability

<!-- BEGIN table:w7-machine:-:1,4,8 -->
| Corpus | Workers | Configuration | CPU s | Wall s | Peak RSS | Instructions | Collections (young / full; pause) | Runs | Load |
|---|--:|---|--:|--:|--:|--:|---|--:|--:|
| init | 1 | `jvm` | 12.75 (12.75) | 8.18 (8.19) | 590 MB | 161.8 G | 0 / 0; 0 ms | 2 | 75 |
| init | 1 | `native` | **7.94 (7.97)** | 7.97 (7.99) | 252 MB | 136.6 G | n.r. | 2 | 74 |
| init | 4 | `jvm` | 18.41 (20.01) | 4.90 (5.08) | 771 MB | 177.7 G | 0 / 0; 0 ms | 2 | 74 |
| init | 4 | `native` | **8.08 (8.11)** | 2.89 (2.95) | 334 MB | 137.1 G | n.r. | 2 | 75 |
| init | 8 | `jvm` | 24.30 (29.31) | 6.15 (6.17) | 1.18 GB | 198.4 G | 1 / 0; 105 ms | 2 | 76 |
| init | 8 | `native` | **8.32 (8.56)** | 2.36 (2.92) | 487 MB | 137.2 G | n.r. | 2 | 76 |
| std | 1 | `jvm` | 17.35 (17.75) | 12.72 (12.77) | 1.16 GB | 227.0 G | 1 / 0; 95 ms | 2 | 80 |
| std | 1 | `native` | **13.20 (13.37)** | 13.23 (13.78) | 330 MB | 219.5 G | n.r. | 2 | 84 |
| std | 4 | `jvm` | 22.90 (24.94) | 6.80 (7.90) | 1.23 GB | 253.6 G | 1 / 0; 98 ms | 2 | 86 |
| std | 4 | `native` | **13.21 (13.46)** | 4.43 (5.20) | 450 MB | 220.9 G | n.r. | 2 | 85 |
| std | 8 | `jvm` | 34.17 (35.56) | 6.32 (7.40) | 1.24 GB | 262.7 G | 1 / 0; 105 ms | 2 | 85 |
| std | 8 | `native` | **13.82 (14.24)** | 3.60 (4.35) | 567 MB | 221.0 G | n.r. | 2 | 86 |
| cedar-hot-ax | 1 | `jvm` | 7.89 (8.11) | 4.13 (4.34) | 981 MB | 86.9 G | 1 / 0; 30 ms | 2 | 94 |
| cedar-hot-ax | 1 | `native` | **3.60 (3.62)** | 3.64 (3.66) | 190 MB | 46.4 G | n.r. | 2 | 95 |
| cedar-hot-ax | 4 | `jvm` | 9.01 (9.96) | 2.73 (3.06) | 1.07 GB | 88.2 G | 1 / 0; 57 ms | 2 | 95 |
| cedar-hot-ax | 4 | `native` | **3.52 (3.54)** | 1.41 (1.48) | 319 MB | 46.4 G | n.r. | 2 | 95 |
| cedar-hot-ax | 8 | `jvm` | 12.26 (12.44) | 3.04 (3.23) | 1.10 GB | 92.1 G | 1 / 0; 70 ms | 2 | 94 |
| cedar-hot-ax | 8 | `native` | **3.84 (3.86)** | 1.41 (1.43) | 404 MB | 46.5 G | n.r. | 2 | 94 |
| reachability | 1 | `jvm` | 9.51 (9.59) | 7.20 (7.38) | 2.03 GB | 75.1 G | 5 / 0; 338 ms | 2 | 91 |
| reachability | 1 | `native` | **8.12 (8.23)** | 8.21 (8.34) | 1.32 GB | 58.5 G | n.r. | 2 | 88 |
| reachability | 4 | `jvm` | 11.29 (11.69) | 5.31 (5.51) | 2.91 GB | 79.6 G | 5 / 0; 541 ms | 2 | 85 |
| reachability | 4 | `native` | **8.58 (8.73)** | 5.44 (7.10) | 1.74 GB | 58.5 G | n.r. | 2 | 86 |
| reachability | 8 | `jvm` | 11.51 (12.00) | 7.15 (7.31) | 1.73 GB | 86.1 G | 5 / 0; 232 ms | 2 | 85 |
| reachability | 8 | `native` | **8.38 (8.47)** | 6.18 (7.31) | 1.89 GB | 58.5 G | n.r. | 2 | 87 |
<!-- END table:w7-machine:-:1,4,8 -->

Paired ratios, CE native image against the JVM launcher (the two runs of a group are seconds apart),
all worker counts, then per worker count:

<!-- BEGIN ratio:w7-machine:jvm -->
| Configuration | CPU time / `jvm` (median; range) | Wall / `jvm` | Peak RSS / `jvm` | Instructions / `jvm` | Pairs |
|---|--:|--:|--:|--:|--:|
| `native` | 0.473 (0.243 - 0.878) | 0.735 | 0.42 | 0.752 | 24 |
<!-- END ratio:w7-machine:jvm -->

One worker:

<!-- BEGIN ratio:w7-machine:jvm:-:1 -->
| Configuration | CPU time / `jvm` (median; range) | Wall / `jvm` | Peak RSS / `jvm` | Instructions / `jvm` | Pairs |
|---|--:|--:|--:|--:|--:|
| `native` | 0.677 (0.437 - 0.878) | 1.006 | 0.35 | 0.823 | 8 |
<!-- END ratio:w7-machine:jvm:-:1 -->

Four workers:

<!-- BEGIN ratio:w7-machine:jvm:-:4 -->
| Configuration | CPU time / `jvm` (median; range) | Wall / `jvm` | Peak RSS / `jvm` | Instructions / `jvm` | Pairs |
|---|--:|--:|--:|--:|--:|
| `native` | 0.464 (0.323 - 0.760) | 0.621 | 0.39 | 0.759 | 8 |
<!-- END ratio:w7-machine:jvm:-:4 -->

Eight workers:

<!-- BEGIN ratio:w7-machine:jvm:-:8 -->
| Configuration | CPU time / `jvm` (median; range) | Wall / `jvm` | Peak RSS / `jvm` | Instructions / `jvm` | Pairs |
|---|--:|--:|--:|--:|--:|
| `native` | 0.368 (0.243 - 0.744) | 0.567 | 0.42 | 0.681 | 8 |
<!-- END ratio:w7-machine:jvm:-:8 -->

Reading:

- **The CE native image needs less CPU time than the JVM launcher in every group** - every corpus,
  every worker count, both repeats (24 pairs). Median of the paired ratios: 0.68 with one worker,
  0.46 with four, 0.37 with eight; the best case for the JVM is reachability with one worker (0.85
  and 0.88), the worst Init with eight workers (0.24 and 0.34).
- **The JVM's price is its JIT, and it is paid per worker.** A run of the machine on Init is 8 s of
  CPU in the image. On HotSpot the same run is 12.8 s with one worker, 18.4 to 21.6 s with four and
  24 to 34 s with eight: C2 needs about 3.7 s to compile the machine's 800 methods
  (`work/status/machine-integration.md`), and until it is done every worker runs interpreted and C1
  code. The image's CPU time hardly moves with the workers (7.9 / 8.1 / 8.3 s).
- **Wall clock**: equal with one worker (median ratio 1.01: the JVM's compiler and collector threads
  run beside its one worker), 0.62 times with four workers and 0.57 with eight. Init with four
  workers: 2.9 s against 4.9 s; Std: 4.4 s against 6.8 s.
- **Peak RSS**: 0.35 to 0.42 times the JVM's (medians; Init, four workers: 330 MB against 770 MB; Std:
  450 MB against 1.26 GB). **Instructions**: 0.68 to 0.82 times.
- **This is the opposite of Part II's result for mathlib with the reference engine**, where HotSpot's
  code is better than the CE compiler's by 11 to 26 % in CPU time and a run lasts ten minutes, so the
  warm-up is amortised. With the machine the runs are an order of magnitude shorter and its hot code
  is a few flat loops over `int` arrays, which the ahead-of-time compiler handles as well as C2:
  reachability, whose two heavy declarations run for seconds each (the closest thing to a long,
  hot run among the four), is still 0.73 to 0.88 times in the image's favour. Whether the JVM catches
  up on a mathlib-sized run of the machine is in section I.4.

## I.3 Worker count

CPU time and wall clock by worker count (minimum over the two valid repeats), and what more workers
cost and buy against one worker **of the same repeat** (median of the repeats, range in
parentheses; the runs of a repeat are minutes apart): `CPU N w / 1 w` above 1.00 is the price in the
primary metric, `Wall 1 w / N w` the speed-up. The repeats were taken at host loads of 26 to 95
(first) and 74 to 87 (second).

<!-- BEGIN scaling:w7-machine:-:1:1,4,8 -->
| Corpus | Configuration | CPU s, 1 w | CPU s, 4 w | CPU s, 8 w | Wall s, 1 w | Wall s, 4 w | Wall s, 8 w | CPU 4 w / 1 w (median; range) | CPU 8 w / 1 w (median; range) | Wall 1 w / 4 w | Wall 1 w / 8 w | Peak RSS, 8 w | Runs |
|---|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| init | `jvm` | 12.75 | 18.41 | 24.30 | 8.18 | 4.90 | 6.15 | 1.57 (1.44 - 1.70) | 2.30 (1.91 - 2.69) | 1.61 | 1.33 | 1.18 GB | 2/2/2 |
| init | `native` | 7.94 | 8.08 | 8.32 | 7.97 | 2.89 | 2.36 | 1.02 (1.01 - 1.02) | 1.07 (1.04 - 1.11) | 2.71 | 2.84 | 487 MB | 2/2/2 |
| std | `jvm` | 17.35 | 22.90 | 34.17 | 12.72 | 6.80 | 6.32 | 1.40 (1.32 - 1.49) | 2.00 (1.97 - 2.04) | 1.65 | 1.76 | 1.24 GB | 2/2/2 |
| std | `native` | 13.20 | 13.21 | 13.82 | 13.23 | 4.43 | 3.60 | 1.01 (1.00 - 1.01) | 1.07 (1.05 - 1.08) | 2.69 | 3.24 | 567 MB | 2/2/2 |
| cedar-hot-ax | `jvm` | 7.89 | 9.01 | 12.26 | 4.13 | 2.73 | 3.04 | 1.23 (1.14 - 1.31) | 1.54 (1.47 - 1.60) | 1.43 | 1.34 | 1.10 GB | 2/2/2 |
| cedar-hot-ax | `native` | 3.60 | 3.52 | 3.84 | 3.64 | 1.41 | 1.41 | 0.98 (0.97 - 0.98) | 1.07 (1.07 - 1.07) | 2.48 | 2.56 | 404 MB | 2/2/2 |
| reachability | `jvm` | 9.51 | 11.29 | 11.51 | 7.20 | 5.31 | 7.15 | 1.22 (1.17 - 1.27) | 1.25 (1.21 - 1.29) | 1.34 | 1.01 | 1.73 GB | 2/2/2 |
| reachability | `native` | 8.12 | 8.58 | 8.38 | 8.21 | 5.44 | 6.18 | 1.06 (1.06 - 1.06) | 1.03 (1.03 - 1.03) | 1.24 | 1.17 | 1.89 GB | 2/2/2 |
<!-- END scaling:w7-machine:-:1:1,4,8 -->

Reading (the image; the JVM launcher pays 1.2 to 2.7 times its one-worker CPU time for eight
workers and is not a candidate):

- **More workers cost CPU time although they add no work.** The image executes the same
  instructions at every worker count (Init: 136.6 / 137.1 / 137.2 G with 1 / 4 / 8 workers). Four
  workers need +0 to +3 % CPU time against one on Init, Std and the Cedar slice (Init +1.1 / +2.5 %,
  Std +0.1 / +1.4 %, slice -1.5 / -3.2 % in the two repeats) and eight need +4 to +11 % (Init +4.1 /
  +10.7 %, Std +4.7 / +8.4 %, slice +6.6 / +6.9 %): the workers share the memory system, and the
  machine's time is hash-consing probes and array growth (the same effect as with the reference
  engine, `rlvm-driver/README.md`). **The busier the host, the dearer a worker**: on Init and Std the
  repeat at the higher load (75 to 86 against 26 to 56) is the dearer one.
- **What they buy.** Four workers: 2.5 to 2.7 times the speed of one on Init, Std and the slice
  (2.8 and 3.0 on Init and Std in the quieter repeat). Eight workers: 18 to 19 % less wall clock than
  four on Init and Std at their best (2.36 against 2.89 s, 3.60 against 4.43 s), nothing on the Cedar
  slice (1.41 s both), and reachability does not scale beyond 1.5 times at any count (two heavy
  declarations decide its wall clock; the scheduler's claim cut, `work/status/integration.md` "What
  is open").
- **The gate of `rlvm-driver/README.md`** ("the largest N <= 6 whose CPU time exceeds that of one
  worker by at most 5 %"): four workers pass on Init, Std and the slice (1.02, 1.01, 0.98) and are
  just above it on reachability (1.06 in both repeats); eight fail on Init, Std and the slice (1.07
  each) and pass on reachability (1.03).
- **Six workers** (the driver's proposal for `--threads auto`) have no valid measurement. The three
  runs per corpus that this series made with six workers (14:18Z to 14:20Z) are among the discarded
  ones: the host was at load 240 to 266 and swapping, Init with eight workers took 6.2 s of wall
  clock there against 2.36 s an hour earlier. For the record only, CPU seconds of those runs (minimum
  of three) beside the discarded third repeat's 1 / 4 / 8 workers of the same minutes: Init 8.57
  (8.23 / 8.67 / 9.01), Std 14.57 (13.46 / 14.57 / 16.42), Cedar slice 3.83 (3.32 / 4.00 / 4.45),
  reachability 8.35 (8.43 / 8.72 / 8.74) - six cost what four cost and less than eight, on a host
  where four already cost +5 to +21 % against one. That neither speaks against six nor confirms it.
- **Reading under the two criteria.** CPU time first (owner note 10, in force when this was
  measured): four is the largest measured count at which the image's CPU time is still that of one
  worker; eight buy 18 % of wall clock on two of four corpora for 4 to 11 % more CPU time on three of
  them - keep `--threads 4`. Wall clock first (owner note 12, since 14:37Z): the same numbers say
  eight - less wall clock on Init and Std, the same on the Cedar slice, no worse on reachability
  within its scatter, and CPU time is only a diagnostic. **The run line was left at `--threads 4`
  (and `threads: 4`, the CPU slots the arena reserves)**: a change needs all 230 published tests under
  the arena's time and memory limits with the new count (the wave-5 check ran them with four), which
  did not fit into this session. Six is the candidate the
  driver's owner proposes (with the reference engine he measured 12 to 25 % less wall clock than
  with four on Init, Std and cedar, `rlvm-driver/README.md`); it needs a quiet host and a mathlib
  run with the machine to be judged (his `threads-gate.sh --native --engine
  machine` on mathlib held the lock from 14:21Z on). Changing it later is two numbers in
  `scripts/arena/rlvm.yaml`.

## I.4 mathlib (reference engine), one run per launcher

The reference engine did not change between the wave-5 check and this series (the same pipeline
jars; `rlvm-main` differs only by the run-defaults plumbing of section I.8, which no engine uses), so
the mathlib runs of 12:56Z and 13:03Z are the runs of this gate: one per launcher, eight workers, the
`-O3` image, the same container, through `bench/track/track.py` (`work/perf-history/mathlib.jsonl`,
note `wave5-check`; distribution `work/pipeline-check/dist`), at a host load of 9 to 14 - quieter
than anything this afternoon offered (load 26 to 266).

| Corpus | Workers | Launcher | CPU s | Wall s | Peak RSS | Instructions | Load |
|---|--:|---|--:|--:|--:|--:|--:|
| mathlib | 8 | `jvm` | **500.5** | 97.4 | 12.85 GiB | 5,971 G | 14 |
| mathlib | 8 | `native` | 624.6 | 120.3 | 7.15 GiB | 8,533 G | 9 |
| mathlib, `-X load=pipelined` | 8 | `jvm` | **501.0** | 73.2 | 12.90 GiB | 5,895 G | 13 |
| mathlib, `-X load=pipelined` | 8 | `native` | 605.2 | 90.9 | 6.76 GiB | 8,479 G | 13 |

- **For the reference engine on mathlib the JVM launcher still needs less CPU time**: 0.80 times
  the image's (0.83 pipelined), 0.81 times the wall clock, with 5.7 GiB more memory (its heap
  setting). The other large corpora of the same check: navier-stokes-euler 0.82 (175.3 against
  214.6 s), cslib 0.93 (125.2 / 134.1 s), con-leche 1.01 (60.2 / 59.8 s). Part II measured 0.80 to
  0.89 on mathlib with this morning's jars: the picture held while the engine became twice as fast.
- **What that decides: nothing for the shipped binary.** The reference engine is the fallback and the
  re-check engine; with the machine as primary engine it checks the inductive blocks and what the
  machine declines, and on every corpus it was run on to the end here the machine declines nothing
  (section I.6). A mathlib run of the reference engine is not what the arena will see.
- **A four-worker pair** (the run line's count) was queued (`queue2.sh B`) and never reached the
  measurement lock; Part II section 2.3 has the four-worker pair of this morning's jars (JVM
  945.8 s, image 1,068.6 s: 0.89). **No mathlib run of the machine was made in this series** (it was
  queued as `queue2.sh D` / `F`; the acceptance run of section I.6 is not a measurement).

## I.5 Heap and collector flags

**The image** (what ships): `-O3 --gc=serial --no-fallback -R:+ExitOnOutOfMemoryError
-R:MaxHeapSize=13g`, no `-march`, no nursery flag - what `scripts/build.sh` and
`scripts/arena/build.sh` build today.

- `-O3`: measured by the machine's integrator in this container two hours earlier (`-O2` against
  `-O3`, one worker): Init 9.61 -> 7.84 s CPU, Std 15.70 -> 12.82, Cedar slice 3.77 -> 3.21,
  reachability 9.15 -> 7.75 (-15 to -18 %; an earlier pair of his series, on a busier host: Init
  10.11 -> 9.08, Std 16.18 -> 14.85, slice 3.84 -> 3.59, -7 to -10 %); reference engine Init 15.41 ->
  13.82. `-march=native`: no change. It was the arena build's level already; since this wave it is
  `build.sh`'s default too.
- Collector: CE offers the image `serial` and `epsilon`. How much there is to collect is visible on
  the JVM launcher, whose runs are logged (column "Collections" in I.2; 1 GB nursery): zero to one
  young collection per run on Init, Std and the Cedar slice, five on reachability, never a full one,
  30 to 520 ms of pauses in runs of 8 to 37 CPU-seconds. The machine's working memory is `int` arrays
  that live as long as a session. The image's own collections were not recorded; `epsilon` (never
  collect) could save at most that order of magnitude and turns a mathlib-class input into an
  out-of-memory risk: not built, not measured.
- Heap limit: `-R:MaxHeapSize=13g` is a limit, not a reservation - the image's RSS is what it uses
  (Init 250 to 490 MB, Std 330 to 570 MB, reachability 1.3 to 1.9 GB above; mathlib with the reference
  engine 7.15 GiB). 13 GB leaves 3 GB of the runner's 16 GB and is above the memory account's 12 GiB
  decline, so an input that does not fit is declined, not killed.
- Nursery (`-Xmn256m`, `1g`, `2g` against the default): queued (`queue2.sh D`), not measured - it
  never reached the measurement lock.

**The JVM launcher** (not shipped; the launcher of guards and of `scripts/dev.sh dist`): flags
unchanged, `-XX:+UseSerialGC -XX:-UseJVMCICompiler -XX:CICompilerCount=2 -Xms13g -Xmx13g -Xmn1g`.
Part II sections 3 to 5 measured the alternatives with the reference engine (Serial against Parallel
and G1: no CPU difference, more memory; C2 against Graal: none; HotSpot's own compiler thread count:
0.92 times the CPU time with eight workers; a 10 GB heap: no CPU cost). None of them can bring the
JVM launcher near the image for the machine (0.37 to 0.68 above), so they were the last use planned
for the lock (`queue2.sh E`) and were not measured with the machine.

## I.6 What the machine accepted in these runs

The verdicts and the machine's own counters, taken outside the timed runs (`work/steward/w7/accept.sh`:
the image of the measured distribution, `-X engine=machine -X stats-file=...`, four workers, on the
host's efficiency cores; `work/steward/w7/stats/*.json`): on the eight corpora that ran to the end the
machine decided every unit it was given and the reference engine re-checked none, so the figures of
this part contain no fallback work. The mathlib run was stopped after 23 minutes on the thrashing
host without a result.

| Corpus | Verdict | Units given to the machine | Accepted | Rejected | Declined | Reference re-checks | Driver disagreements |
|---|---|--:|--:|--:|--:|--:|--:|
| Init | accept | 57,938 | 57,938 | 0 | 0 | 0 | 0 |
| Std | accept | 100,096 | 100,096 | 0 | 0 | 0 | 0 |
| Cedar slice (`cedar-hot-ax`) | accept | 8,064 | 8,064 | 0 | 0 | 0 | 0 |
| reachability | accept | 4,224 | 4,224 | 0 | 0 | 0 | 0 |
| cedar | accept | 144,098 | 144,098 | 0 | 0 | 0 | 0 |
| con-leche | accept | 36,274 | 36,274 | 0 | 0 | 0 | 0 |
| navier-stokes-euler | accept | 124,850 | 124,850 | 0 | 0 | 0 | 0 |
| cslib | accept | 397,608 | 397,608 | 0 | 0 | 0 | 0 |
| mathlib | not run to the end in this series | - | - | - | - | - | - |

The remaining units of a file (inductive blocks, quotient declarations) are checked by the
environment pass with the reference kernel, as before. All 48 valid timed runs of the matrix (and
the 36 discarded ones) ended with exit status 0 (accept).

## I.7 Owner note 12: warm wall clock - what this part does not answer, and a first indication

Note 12 asks every benchmark for, in this order: warm wall clock with 8 workers (pipelined), warm
wall clock with 1 worker, cold wall clock of a fresh process (for information), CPU time, peak RSS,
instructions; "warm" = the same process checks the same export again after a first pass. Against
that:

- **Everything above is the third and fourth item of that list** (cold wall clock and CPU time of a
  fresh process, batch load). It stays true for what the arena does - one process per export - and
  it no longer decides which platform is the fast one.
- **The launcher cannot make a warm pass**: `rlvm` checks one export and exits. A `--passes N` (same
  process, per-pass wall clock and CPU time in the statistics, the passes must agree) belongs in
  `rlvm-cli` / `rlvm-main`; it changes the launcher's fail-closed path and could not be guarded in
  what was left of this session, so it is not there.
- **A first indication, not a measurement of record** (`work/steward/w7/warm/Warm.java`: a harness
  that calls `RlvmChecker.checkExport` several times in one JVM; `work/steward/w7/warm/init-1w.txt`).
  Machine, Init, ONE worker, the two spare performance cores (CPUs 18, 19; not the container), 14:38Z
  to 14:42Z at a host load of 135 to 155 - the cores were shared, so the wall clock of these runs
  (17 to 62 s) says nothing and only the CPU time per pass is quoted:

  | Platform | Pass | CPU s |
  |---|--:|--:|
  | JVM launcher's flags (C2, Serial, 6 GB heap), one process | 1 (cold) | 15.62 |
  | | 2 | 8.49 |
  | | 3 | 8.23 |
  | | 4 | 7.08 |
  | CE native image `-O3`, fresh process | - | 9.81 |
  | CE native image `-O3`, fresh process | - | 9.41 |

  **Once warm, HotSpot needs 0.72 to 0.87 times the image's CPU time for the same check** (passes 3
  and 4 against the image's two runs in the same minutes on the same cores), where its cold pass
  needs 1.6 times. That is the reference engine's picture on mathlib (0.80) repeated for the
  machine: the JIT's steady-state code is better than the CE ahead-of-time compiler's. One corpus,
  one worker, disturbed cores (the image's run costs 7.94 s in the container; here contention
  inflated everything by about a fifth), the image's two runs made right after the JVM's passes and
  not interleaved with them, process CPU time including the compiler and collector threads: an
  indication.
- **What decides it**: warm passes of the machine in the container, 8 workers and 1, pipelined
  load, on Init, Std, a Cedar-level corpus and mathlib, for the JVM launcher and the image (for the
  image "warm" removes only page faults and table growth). If the indication holds, the JVM launcher
  is the faster platform by the owner's criterion, at two to three times the memory - and the arena
  run, a cold process, still favours the image by the numbers of section I.2. Both cannot be had
  from one launcher today; the machine's compiled tier (rlvm-truffle) moves the same question into
  the image.

## I.8 What was changed, what was not measured, how to repeat

**Changed in this wave** (all in the pipeline owner's modules):

- `scripts/build.sh`: `--opt` defaults to **3** (it was 2), so `scripts/dev.sh dist-native` builds the
  image the arena build builds (request of the machine integrator; measured by him: -10 to -18 % CPU for
  the machine, about -10 % for the reference engine; the build takes 2.5 minutes and 900 CPU-seconds
  instead of 1 to 2 minutes and 500). `scripts/arena/build.sh` has used `--opt 3` since wave 4.
- `scripts/build.sh`: **Oracle GraalVM is used only on request** (`--pgo`; OWNER-NOTES item 11). Until
  today a default build made the PGO image whenever `.toolchain/oracle-graalvm-25` existed. No script
  downloads it. New `--train-arg ARG` passes arguments to the PGO training runs (`-X engine=machine`).
- `scripts/build.sh`: the shared distribution `work/dist` is **versioned**: each build is installed as
  `work/dist.d/<UTC time>-<sha256 prefix of the RLVM jars>-<jvm|native>` and `work/dist` is a symbolic
  link switched by one `rename(2)`. `scripts/gate.sh`, `scripts/slice.sh`, `bench/track/track.py`,
  `ladder.py` and `scripts/platform-matrix.py` resolve the link once at their start. Old versions are
  deleted when they are neither among the newest four nor younger than 90 minutes.
  `scripts/dev.sh dist-versions` lists them. (The shared `work/dist` itself is still the plain
  directory of 12:17Z, `-O2`: the build that would have converted it was mine, ran without a host
  slot when the orchestrator introduced them, and was stopped; the next shared build converts it.
  The scheme was exercised on a scratch distribution and on a clean copy of the tree.)
- `scripts/gate.sh`, `scripts/dev.sh dist-native` / `dist-tree`: they take the host-wide slot of
  `bench/slot.sh` themselves (`heavy`, respectively `ni`: at most two native-image builds on the
  host) unless the caller already holds one; `gate.sh` runs four files at a time by default (it was
  8 and 10). The orchestrator's rule of 14:32Z, after the host had run out of memory.
- `scripts/platform-matrix.py` finds slices (`cedar-hot-ax`); `scripts/platform-report.py` has the
  worker-scaling table (`scaling:TAG`). `bench/track/benchmarks.py` writes one RLVM column per engine
  and, since owner note 12, the cold wall clock before CPU time.
- `rlvm-api` / `rlvm-main`: `EngineProvider.runDefaults(Map)` - the primary engine may prefer run
  settings (the machine's session cap, request of the machine integrator); applied under the command
  line. No provider declares one yet, so no run changed.

**Not changed**: the default engine (reference; the machine registers with priority 10 and its
workflow raises it), the parent pom's module list, the JVM launcher's flags, the image's collector
and heap limit.

**Not measured, and why**

- **Warm passes in the container** (owner note 12; section I.7), **the machine on mathlib** (either
  launcher), **the machine on the JVM launcher beyond Std size**, and **x86-64** (no such machine
  here).
- **Oracle GraalVM PGO with the machine**: it needs three more image builds under the build lock
  (`work/build/.lock`), for which six builds of other agents were waiting at 13:50Z and ten at 13:53Z.
  Part II has the PGO image for the reference engine (small corpora: 0.82 and 0.91 times the JVM
  launcher's CPU time with eight workers and with one, where the CE image needs 0.92 and 1.03; mathlib
  +2 % against the JVM launcher, the CE image +12 %). It is a measurement only in any case (owner's
  decision: nothing that ships uses Oracle GraalVM).
- **The machine's staging switches** (`warm,gated,expect`, `probes`: -3 to -9 % CPU in
  `work/status/machine-integration.md`): they are the machine workflow's to turn on; the soundness
  review found wrong accepts under `probes` and `facts` (13:48Z) and under `warm` (14:38Z;
  `work/status/requests.log`, findings S-F1, S-F2, S-F4), so v0 is the configuration that could
  ship today.
- **A quiet host, and a third repeat.** The load average was 26 to 95 during the two valid repeats;
  the absolute CPU times are a few percent above the wave-4 integrator's (Init, one worker: 7.94 s
  here, 7.77 s at load 7 to 10), the ratios inside a group are what the decision rests on. The third
  repeat, six workers, the nursery sizes, the JVM flags, the machine on the large corpora and the
  four-worker mathlib pair were all queued; after 13:50Z the host was out of memory, and the
  repetition (`work/steward/w7/queue3.sh R`, inside a host-wide slot as the orchestrator now
  requires) waited behind eight other queues for the lock and was withdrawn at 14:34Z, when it
  could no longer finish in this session.
- **`scripts/dev.sh arena-test`** (the arena definition built and run from a clean copy in the
  container) was not run after the changes to `scripts/build.sh`: it needs the same lock. Instead: a
  clean copy of the tree built by `scripts/build.sh --jvm-only` with the default (versioned)
  distribution, and the run line of `rlvm.yaml` with `bin/rlvm-jvm` substituted on three published
  tests through the `work/dist` link (accept / reject / accept as expected).

**To repeat** (about 45 minutes of container time on a host that is not thrashing):

```
bench/slot.sh -c ni -n 2 -- scripts/build.sh --published --no-pgo --opt 3 --dist work/steward/w7/dist   # frozen distribution
bench/slot.sh -- work/steward/w7/queue2.sh A B C D E F G   # the holds of the measurement lock (see the script)
work/steward/w7/assemble.py                    # this part: part1.md + scripts/platform-report.py --update
work/steward/w7/benchmarks.sh                  # docs/BENCHMARKS.md
```

# Part II - the rehearsal with the reference engine (wave 4)

Steward, wave 4, 2026-11-24. Engine: the **reference engine** (`-X engine=reference -X load=batch`), the
only engine in the distribution at the time. This part was the rehearsal of the platform gate with the
engine that existed; its scripts are the ones Part I ran again with the machine (section 7).

## 0. Summary

1. **Launchers** (reference engine, jars of 07:54Z).
   - **mathlib: the JVM launcher needs the least CPU time.** Eight workers, the three launchers back to
     back: JVM 1,057 s, Oracle PGO image 1,082 s (+2 %), CE native image 1,187 s (+12 %); over the day
     the JVM took 926 to 1,057 s in six runs and the CE image 1,168 to 1,203 s in three (no overlap).
     Four workers: 946 s against 1,069 s (+13 %). One worker: 886 s against 983 s (PGO, +11 %) and
     1,112 s (CE, +26 %). Instructions: 6.3 to 6.5 T (JVM), 7.6 T (PGO), 9.0 T (CE).
   - **Init, Std, cedar, reachability (12 to 55 s of CPU): within a tenth to a fifth of each other**, and
     the order depends on the worker count. CE image against JVM: 0.92 times the CPU time with eight
     workers (16 pairs), 1.03 times with one (12 pairs). PGO image: 0.82 and 0.91 times.
   - **Memory**: the images need a third to a half of the JVM's RSS on the small corpora; on mathlib
     8.7 GiB (CE) and 8.1 GiB (PGO) against 13.0 GiB with eight workers, 4.65 and 3.9 GiB against
     11.8 GiB with one.
2. **JVM, host JIT**: C2 against Graal makes no difference worth a flag (0.97 and 1.01 times); C1 alone
   costs 22 %. More compiler threads than the launcher's two look useful with eight workers (0.92
   times the CPU time on the small corpora, eight pairs, range crossing 1.0): not changed, to be
   measured at the gate.
3. **JVM, collector**: Serial, Parallel and G1 are within the noise in CPU time on the small corpora
   (zero to four young collections per run); Parallel and G1 need 17 to 89 % more memory there, and on
   mathlib Parallel doubles the pauses and G1 has the highest RSS. Serial stays.
4. **JVM, heap**: the process's RSS is its heap limit, not its need (the old generation fills with
   dead arrays before the first full collection; neither a growing heap nor a lower
   `MinHeapFreeRatio` changes that). With the jars of 07:54Z the live set on mathlib with eight workers
   is 5.5 to 9.1 GB: a 10 GB heap passed twice at no CPU cost (10.1 GiB RSS, six full collections of
   0.1 to 0.3 s instead of three), a 9 GB heap ran out of memory - the native image's 8.7 GiB was out
   of reach. With the jars of 10:56Z (the store of 10:33Z) the live set is 3.1 to 4.5 GB: mathlib,
   con-leche, cslib, navier-stokes-euler and all 230 tests pass with **8 GB** (mathlib: 7.9 GiB RSS,
   seven full collections), which is the native image's range.
5. **Decisions.** **No launcher default was changed.** The arena definition stays on the CE native
   image (description rewritten: reference engine; machine in progress); the JVM launcher keeps
   `-XX:+UseSerialGC -XX:-UseJVMCICompiler -XX:CICompilerCount=2 -Xms13g -Xmx13g -Xmn1g`. Reasons and
   what to do at the platform gate: section 6.3.
6. **Caveat.** The host ran at three to nine times its capacity during the series (section 1.1), and
   during the wave the store's owner made the reference engine about 40 % cheaper on mathlib (jar of
   11:17Z, its owner's measurement), which is not in the measured distribution. Ratios between launchers were
   not re-measured with it.

## 1. What was measured, and how

- **Distribution.** A frozen copy with the three launchers built from the same jars,
  `work/steward/platform/dist` (`BUILD-INFO.txt` there lists every jar with its sha256 prefix):

  ```
  scripts/build.sh --published --pgo --opt 3 --dist work/steward/platform/dist
  ```

  | Launcher | Configuration name | What it is |
  |---|---|---|
  | `bin/rlvm-jvm` | `jvm` | the jars on HotSpot of GraalVM CE 25.5.5.0.3 (JDK 25.0.5) with the launcher's default flags: `-XX:+UseSerialGC -XX:-UseJVMCICompiler -XX:CICompilerCount=2 -XX:+ExitOnOutOfMemoryError -Xms13g -Xmx13g -Xmn1g` |
  | `bin/rlvm` | `native` | native image, GraalVM CE 25.5.5.0.3, `-O3 --gc=serial -R:MaxHeapSize=13g`, 60 MB: what `scripts/arena/build.sh` builds |
  | `bin/rlvm-pgo` | `pgo` | native image, **Oracle GraalVM 25.0.4**, `-O3 --gc=serial` + profile-guided optimisation, trained on `init-prelude` and `init.ndjson` with one worker, 42 MB. **A measurement only**: Oracle GraalVM is under the GFTC licence, and whether the project may build with it is an open question with the project owner |

  Jars: the integrator's final state of 07:35Z (driver `b966222a51a9`, ref `9089b0a558f3`, store
  `90b8cf9432cb`, load `43a3adf4faaf`, admit `d89a73d8a715`, inductive `db8ac9802b19`, api
  `a956fdf3f0f4`) with rlvm-cli `f2af2657a704` and rlvm-main `080e6bcb2d6a` of 07:54Z (public
  `UsageException`; no difference on the measured path). Later publications of this wave are not in it.
- **Containers.** Eight workers: `bench/arena-docker.sh --arena-limits --no-network`, 8 performance
  cores (Cortex-X925, CPUs 5-9, 15-17 of this 20-core aarch64 host) and 16 GB, like the arena runner
  (x86-64, 8 vCPU, 16 GB). One worker: a container with the two remaining performance cores (CPUs 18,
  19) and 16 GB - one worker thread, the loader before it and two JIT threads fit there, and these runs
  could then go on while other people measured in the eight. A one-worker row is therefore not
  "arena-shaped" in its core count; its CPU time is what it is about.
- **Units.** Peak RSS is printed in units of 2^20 and 2^30 bytes ("MB", "GB" in the tables are MiB
  and GiB); G instructions are 10^9.
- **One run** = the arena run line of `scripts/arena/rlvm.yaml` with the binary replaced, under
  `bench/measure.sh` (`perf stat` + GNU time around `sh -c`, exactly the arena's measurement):

  ```
  RLVM_STATUS_BASE=10 [RLVM_JAVA_OPTS='...'] DIST/bin/BINARY --threads N "$IN" -X engine=reference -X load=batch; case $? in ...
  ```

  A JVM configuration other than `jvm` replaces **all** VM flags through `RLVM_JAVA_OPTS`; the flags of
  every configuration are listed where it is used (`scripts/platform-matrix.py --list` prints all).
  Every JVM run also writes `-Xlog:gc` to a file; collection counts and pause totals come from it.
- **Tools.** `scripts/platform-matrix.py --configs A,B,.. --corpora .. --threads .. --repeat K --tag T`
  runs a matrix and appends one JSON line per run (with the complete command) to
  `work/steward/platform/results/T.jsonl`; `scripts/platform-all.sh STEP...` is the series as run;
  `scripts/platform-report.py --update docs/PLATFORM.md` regenerates every table below from the
  result files. GC logs: `work/steward/platform/gclogs/`.
- **Interleaving and the lock.** For each repeat, corpus and worker count, every configuration runs
  once, back to back, in one container (a *group*), while the measurement lock of
  `bench/track/ladder.py` (`work/perf-history/.ladder.lock`) is held.

### 1.1 The host was not quiet - how to read the numbers

Sixteen agents shared the host during the whole series (unit tests, guards, mutant campaigns, other
measurements, other projects' jobs). The 1-minute load average is in the last column of every table:
**60 to 190 on 20 cores** for most runs (08:30Z to 11:00Z), 25 to 80 for the first mathlib run of the
JVM launcher (08:01Z) and for the last groups (after 11:00Z). Other processes are not confined to
the cores outside the containers, and other owners' measurement containers ran in the same eight
cores without the lock during several groups (recorded per group in `results/T.groups.jsonl`: among
them the back-to-back mathlib group, both heap groups and the four-worker mathlib group).
Consequences:

- **CPU time is inflated**, by an amount that changes from minute to minute (shared caches, memory
  bandwidth, clock frequency under an all-core load). The same binary on the same file: mathlib on the
  JVM launcher took 926 s of CPU at load 26 and 1,030 to 1,057 s at load 130 to 190 (+11 to +14 %)
  with instruction counts 1.4 % apart. The figure of a row is the **minimum** over its runs, the
  median is in parentheses.
- **Compare configurations by the paired ratios**: runs of one group are minutes apart at most and saw
  nearly the same load. The ratio tables give the median over all groups of
  (configuration / reference configuration) and the range.
- **Wall clock** of eight-worker runs measures the host as much as the checker: it is listed, and it
  is not a basis for a decision here.
- **Peak RSS, collection counts, exit status and instructions** do not depend on the load.

For scale, on a quieter host (integration log, 07:35Z, same jars, container): Init JVM 15.1 s CPU /
12.4 s wall with one worker, 20.6 s / 3.9 s with eight; mathlib JVM 841.8 s CPU / 139.2 s wall /
13.1 GB with eight workers.

## 2. The three launchers

### 2.1 Init, Std, cedar, reachability - eight workers

Three repeats in the order JVM, CE image, PGO image, and a fourth in the opposite order (a
configuration that always runs first in its container could be at a disadvantage):

<!-- BEGIN table:platforms -->
| Corpus | Workers | Configuration | CPU s | Wall s | Peak RSS | Instructions | Collections (young / full; pause) | Runs | Load |
|---|--:|---|--:|--:|--:|--:|---|--:|--:|
| init | 8 | `jvm` | 23.77 (25.88) | 4.60 (6.51) | 1.44 GB | 221.7 G | 2 / 0; 160 ms | 4 | 125 |
| init | 8 | `native` | 18.48 (20.65) | 4.43 (5.33) | 581 MB | 250.7 G | n.r. | 4 | 123 |
| init | 8 | `pgo` | **16.91 (17.35)** | 3.67 (4.56) | 545 MB | 211.2 G | n.r. | 4 | 122 |
| std | 8 | `jvm` | 40.03 (42.03) | 7.68 (11.56) | 1.62 GB | 348.1 G | 3 / 0; 222 ms | 4 | 122 |
| std | 8 | `native` | 33.56 (38.32) | 6.82 (12.35) | 791 MB | 422.5 G | n.r. | 4 | 121 |
| std | 8 | `pgo` | **31.36 (34.58)** | 6.29 (9.98) | 820 MB | 355.4 G | n.r. | 4 | 120 |
| cedar | 8 | `jvm` | 52.94 (54.20) | 8.91 (14.56) | 1.90 GB | 426.5 G | 4 / 0; 285 ms | 4 | 118 |
| cedar | 8 | `native` | 47.19 (54.20) | 7.98 (16.11) | 832 MB | 532.1 G | n.r. | 4 | 124 |
| cedar | 8 | `pgo` | **42.06 (47.65)** | 7.17 (13.57) | 908 MB | 447.8 G | n.r. | 4 | 126 |
| reachability | 8 | `jvm` | 14.74 (15.85) | 9.30 (10.82) | 2.79 GB | 102.4 G | 4 / 0; 596 ms | 4 | 126 |
| reachability | 8 | `native` | 13.58 (14.69) | 9.44 (13.86) | 1.68 GB | 112.5 G | n.r. | 4 | 128 |
| reachability | 8 | `pgo` | **12.13 (13.28)** | 8.97 (9.72) | 1.55 GB | 95.8 G | n.r. | 4 | 127 |
<!-- END table:platforms -->

Paired ratios against the JVM launcher:

<!-- BEGIN ratio:platforms:jvm -->
| Configuration | CPU time / `jvm` (median; range) | Wall / `jvm` | Peak RSS / `jvm` | Instructions / `jvm` | Pairs |
|---|--:|--:|--:|--:|--:|
| `native` | 0.917 (0.778 - 1.032) | 1.018 | 0.43 | 1.186 | 16 |
| `pgo` | 0.815 (0.657 - 0.919) | 0.886 | 0.46 | 0.999 | 16 |
<!-- END ratio:platforms:jvm -->

### 2.2 The same, one worker (two-core container), three repeats

<!-- BEGIN table:platforms-1w -->
| Corpus | Workers | Configuration | CPU s | Wall s | Peak RSS | Instructions | Collections (young / full; pause) | Runs | Load |
|---|--:|---|--:|--:|--:|--:|---|--:|--:|
| init | 1 | `jvm` | 23.23 (24.24) | 21.25 (22.81) | 1.08 GB | 207.8 G | 1 / 0; 58 ms | 3 | 118 |
| init | 1 | `native` | 23.34 (23.99) | 23.57 (24.18) | 312 MB | 250.0 G | n.r. | 3 | 120 |
| init | 1 | `pgo` | **20.79 (20.92)** | 21.08 (21.44) | 325 MB | 210.6 G | n.r. | 3 | 118 |
| std | 1 | `jvm` | 40.02 (40.75) | 38.56 (39.00) | 1.30 GB | 330.1 G | 2 / 0; 126 ms | 3 | 124 |
| std | 1 | `native` | 43.56 (43.83) | 44.29 (44.43) | 408 MB | 421.5 G | n.r. | 3 | 123 |
| std | 1 | `pgo` | **38.43 (38.44)** | 38.84 (39.02) | 517 MB | 354.4 G | n.r. | 3 | 121 |
| cedar | 1 | `jvm` | 50.11 (52.64) | 49.81 (51.63) | 1.43 GB | 417.9 G | 3 / 0; 158 ms | 3 | 108 |
| cedar | 1 | `native` | 53.92 (54.31) | 54.78 (54.79) | 552 MB | 531.2 G | n.r. | 3 | 117 |
| cedar | 1 | `pgo` | **48.25 (48.60)** | 48.99 (49.03) | 575 MB | 446.9 G | n.r. | 3 | 135 |
| reachability | 1 | `jvm` | 13.41 (13.50) | 12.61 (12.87) | 1.95 GB | 96.8 G | 3 / 0; 425 ms | 3 | 136 |
| reachability | 1 | `native` | 13.61 (13.91) | 13.84 (14.17) | 996 MB | 111.8 G | n.r. | 3 | 134 |
| reachability | 1 | `pgo` | **11.94 (12.15)** | 12.12 (12.31) | 940 MB | 94.8 G | n.r. | 3 | 129 |
<!-- END table:platforms-1w -->

<!-- BEGIN ratio:platforms-1w:jvm -->
| Configuration | CPU time / `jvm` (median; range) | Wall / `jvm` | Peak RSS / `jvm` | Instructions / `jvm` | Pairs |
|---|--:|--:|--:|--:|--:|
| `native` | 1.027 (0.986 - 1.095) | 1.100 | 0.34 | 1.231 | 12 |
| `pgo` | 0.913 (0.863 - 0.988) | 0.971 | 0.37 | 1.036 | 12 |
<!-- END ratio:platforms-1w:jvm -->

### 2.3 mathlib

Eight workers, one group (the three runs back to back, 09:09Z to 09:26Z; another owner's container
was in the same cores when the group started):

<!-- BEGIN table:mathlib-paired -->
| Corpus | Workers | Configuration | CPU s | Wall s | Peak RSS | Instructions | Collections (young / full; pause) | Runs | Load |
|---|--:|---|--:|--:|--:|--:|---|--:|--:|
| mathlib | 8 | `jvm` | **1056.8** | 287.0 | 13.03 GB | 6,470 G | 85 / 3; 4.6 s | 1 | 184 |
| mathlib | 8 | `native` | 1187.3 | 337.1 | 8.68 GB | 9,025 G | n.r. | 1 | 188 |
| mathlib | 8 | `pgo` | 1082.3 | 326.6 | 8.06 GB | 7,626 G | n.r. | 1 | 125 |
<!-- END table:mathlib-paired -->

<!-- BEGIN ratio:mathlib-paired:jvm -->
| Configuration | CPU time / `jvm` (median; range) | Wall / `jvm` | Peak RSS / `jvm` | Instructions / `jvm` | Pairs |
|---|--:|--:|--:|--:|--:|
| `native` | 1.123 (1.123 - 1.123) | 1.174 | 0.67 | 1.395 | 1 |
| `pgo` | 1.024 (1.024 - 1.024) | 1.138 | 0.62 | 1.179 | 1 |
<!-- END ratio:mathlib-paired:jvm -->

Earlier single runs, **not** comparable with each other (the JVM run at load 26, the two images an hour
later at load 119 to 135); kept for the record and for the instruction counts:

<!-- BEGIN table:platforms-mathlib:-:-:-:plain -->
| Corpus | Workers | Configuration | CPU s | Wall s | Peak RSS | Instructions | Collections (young / full; pause) | Runs | Load |
|---|--:|---|--:|--:|--:|--:|---|--:|--:|
| mathlib | 8 | `jvm` | 925.9 | 177.9 | 12.97 GB | 6,382 G | 84 / 3; 4.5 s | 1 | 26 |
| mathlib | 8 | `native` | 1202.8 | 340.5 | 9.03 GB | 9,025 G | n.r. | 1 | 135 |
| mathlib | 8 | `pgo` | 1109.0 | 412.5 | 8.05 GB | 7,627 G | n.r. | 1 | 119 |
<!-- END table:platforms-mathlib:-:-:-:plain -->

Four workers (the worker count of the arena run line):

<!-- BEGIN table:mathlib-t4 -->
| Corpus | Workers | Configuration | CPU s | Wall s | Peak RSS | Instructions | Collections (young / full; pause) | Runs | Load |
|---|--:|---|--:|--:|--:|--:|---|--:|--:|
| mathlib | 4 | `jvm` | **945.8** | 265.7 | 13.03 GB | 6,263 G | 79 / 1; 3.4 s | 1 | 62 |
| mathlib | 4 | `native` | 1068.6 | 295.4 | 7.48 GB | 8,897 G | n.r. | 1 | 37 |
<!-- END table:mathlib-t4 -->

One worker (two-core container):

<!-- BEGIN table:platforms-mathlib-1w -->
| Corpus | Workers | Configuration | CPU s | Wall s | Peak RSS | Instructions | Collections (young / full; pause) | Runs | Load |
|---|--:|---|--:|--:|--:|--:|---|--:|--:|
| mathlib | 1 | `jvm` | **885.5** | 886.3 | 11.81 GB | 6,111 G | 77 / 0; 2.7 s | 1 | 160 |
| mathlib | 1 | `native` | 1111.9 | 1124.5 | 4.65 GB | 8,889 G | n.r. | 1 | 169 |
| mathlib | 1 | `pgo` | 982.6 | 997.2 | 3.88 GB | 7,499 G | n.r. | 1 | 142 |
<!-- END table:platforms-mathlib-1w -->

<!-- BEGIN ratio:platforms-mathlib-1w:jvm -->
| Configuration | CPU time / `jvm` (median; range) | Wall / `jvm` | Peak RSS / `jvm` | Instructions / `jvm` | Pairs |
|---|--:|--:|--:|--:|--:|
| `native` | 1.256 (1.256 - 1.256) | 1.269 | 0.39 | 1.455 | 1 |
| `pgo` | 1.110 (1.110 - 1.110) | 1.125 | 0.33 | 1.227 | 1 |
<!-- END ratio:platforms-mathlib-1w:jvm -->

## 3. JVM: the host JIT

<!-- BEGIN legend:jit+jit-1w -->
- `jvm-c2`: C2 host JIT, 2 compiler threads (= launcher default, spelled out) - `-XX:+UseSerialGC -XX:-UseJVMCICompiler -XX:CICompilerCount=2 -Xms13g -Xmx13g -Xmn1g`
- `jvm-graal`: Graal (libgraal) host JIT, 2 compiler threads - `-XX:+UseSerialGC -XX:+UseJVMCICompiler -XX:CICompilerCount=2 -Xms13g -Xmx13g -Xmn1g`
- `jvm-c2-auto`: C2 host JIT, HotSpot's own compiler thread count - `-XX:+UseSerialGC -XX:-UseJVMCICompiler -Xms13g -Xmx13g -Xmn1g`
- `jvm-graal-auto`: Graal host JIT, HotSpot's own compiler thread count - `-XX:+UseSerialGC -XX:+UseJVMCICompiler -Xms13g -Xmx13g -Xmn1g`
- `jvm-c1`: C1 only (no optimising host JIT) - `-XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xms13g -Xmx13g -Xmn1g`
<!-- END legend:jit+jit-1w -->

Eight workers, two repeats:

<!-- BEGIN table:jit -->
| Corpus | Workers | Configuration | CPU s | Wall s | Peak RSS | Instructions | Collections (young / full; pause) | Runs | Load |
|---|--:|---|--:|--:|--:|--:|---|--:|--:|
| init | 8 | `jvm-c2` | 24.25 (25.80) | 4.93 (5.28) | 1.43 GB | 220.5 G | 1 / 0; 129 ms | 2 | 84 |
| init | 8 | `jvm-graal` | 23.64 (24.60) | 4.68 (5.46) | 1.44 GB | 241.6 G | 2 / 0; 184 ms | 2 | 83 |
| init | 8 | `jvm-c2-auto` | **22.45 (22.99)** | 4.26 (5.19) | 1.51 GB | 214.0 G | 2 / 0; 194 ms | 2 | 82 |
| init | 8 | `jvm-graal-auto` | 23.15 (24.07) | 4.38 (4.91) | 1.52 GB | 249.8 G | 2 / 0; 208 ms | 2 | 80 |
| std | 8 | `jvm-c2` | 40.20 (41.15) | 8.34 (9.02) | 1.70 GB | 345.8 G | 3 / 0; 241 ms | 2 | 74 |
| std | 8 | `jvm-graal` | 39.00 (39.10) | 7.81 (9.60) | 1.63 GB | 381.8 G | 3 / 0; 230 ms | 2 | 73 |
| std | 8 | `jvm-c2-auto` | **36.18 (37.79)** | 7.05 (7.93) | 1.66 GB | 341.6 G | 3 / 0; 242 ms | 2 | 72 |
| std | 8 | `jvm-graal-auto` | 38.16 (38.33) | 7.53 (7.96) | 1.65 GB | 379.9 G | 3 / 0; 216 ms | 2 | 73 |
| cedar | 8 | `jvm-c2` | 56.45 (57.43) | 12.24 (13.35) | 1.79 GB | 436.9 G | 4 / 0; 296 ms | 2 | 86 |
| cedar | 8 | `jvm-graal` | 53.72 (55.30) | 11.42 (12.19) | 1.87 GB | 486.0 G | 4 / 0; 306 ms | 2 | 85 |
| cedar | 8 | `jvm-c2-auto` | **50.39 (51.27)** | 10.94 (11.51) | 1.86 GB | 417.1 G | 4 / 0; 308 ms | 2 | 85 |
| cedar | 8 | `jvm-graal-auto` | 51.16 (52.29) | 12.85 (13.38) | 1.86 GB | 468.8 G | 4 / 0; 322 ms | 2 | 86 |
| reachability | 8 | `jvm-c2` | 15.26 (15.35) | 8.68 (8.69) | 2.86 GB | 100.0 G | 4 / 0; 634 ms | 2 | 79 |
| reachability | 8 | `jvm-graal` | 16.14 (16.22) | 8.60 (10.44) | 2.74 GB | 117.4 G | 3 / 0; 395 ms | 2 | 76 |
| reachability | 8 | `jvm-c2-auto` | 16.51 (16.57) | 9.43 (9.64) | 3.10 GB | 104.7 G | 4 / 0; 645 ms | 2 | 75 |
| reachability | 8 | `jvm-graal-auto` | **14.85 (15.04)** | 8.57 (8.73) | 2.80 GB | 113.9 G | 4 / 0; 592 ms | 2 | 74 |
<!-- END table:jit -->

<!-- BEGIN ratio:jit:jvm-c2 -->
| Configuration | CPU time / `jvm-c2` (median; range) | Wall / `jvm-c2` | Peak RSS / `jvm-c2` | Instructions / `jvm-c2` | Pairs |
|---|--:|--:|--:|--:|--:|
| `jvm-graal` | 0.972 (0.931 - 1.068) | 0.971 | 1.02 | 1.114 | 8 |
| `jvm-c2-auto` | 0.918 (0.821 - 1.082) | 0.901 | 1.05 | 0.986 | 8 |
| `jvm-graal-auto` | 0.932 (0.846 - 1.031) | 0.965 | 1.04 | 1.100 | 8 |
<!-- END ratio:jit:jvm-c2 -->

One worker (two-core container), two repeats:

<!-- BEGIN table:jit-1w -->
| Corpus | Workers | Configuration | CPU s | Wall s | Peak RSS | Instructions | Collections (young / full; pause) | Runs | Load |
|---|--:|---|--:|--:|--:|--:|---|--:|--:|
| init | 1 | `jvm-c2` | **23.16 (23.45)** | 21.15 (21.47) | 1.08 GB | 206.1 G | 1 / 0; 59 ms | 2 | 128 |
| init | 1 | `jvm-graal` | 23.62 (24.03) | 19.87 (20.13) | 1.12 GB | 248.6 G | 1 / 0; 60 ms | 2 | 126 |
| init | 1 | `jvm-c1` | 28.27 (28.45) | 28.57 (28.75) | 1.07 GB | 288.0 G | 1 / 0; 55 ms | 2 | 124 |
| std | 1 | `jvm-c2` | 40.12 (40.77) | 37.83 (38.41) | 1.30 GB | 330.2 G | 2 / 0; 138 ms | 2 | 127 |
| std | 1 | `jvm-graal` | **39.95 (40.05)** | 35.32 (35.45) | 1.34 GB | 387.4 G | 2 / 0; 127 ms | 2 | 130 |
| std | 1 | `jvm-c1` | 48.99 (49.31) | 49.19 (49.53) | 1.29 GB | 482.7 G | 2 / 0; 114 ms | 2 | 144 |
| cedar | 1 | `jvm-c2` | **48.34 (48.52)** | 46.45 (46.61) | 1.43 GB | 407.5 G | 3 / 0; 193 ms | 2 | 154 |
| cedar | 1 | `jvm-graal` | 50.49 (50.68) | 43.97 (44.15) | 1.46 GB | 499.1 G | 3 / 0; 156 ms | 2 | 153 |
| cedar | 1 | `jvm-c1` | 61.59 (62.18) | 61.77 (62.39) | 1.41 GB | 609.5 G | 3 / 0; 145 ms | 2 | 154 |
| reachability | 1 | `jvm-c2` | 13.25 (13.40) | 12.65 (12.97) | 1.95 GB | 98.7 G | 3 / 0; 308 ms | 2 | 150 |
| reachability | 1 | `jvm-graal` | **13.12 (13.15)** | 11.76 (12.13) | 1.37 GB | 114.6 G | 2 / 0; 130 ms | 2 | 148 |
| reachability | 1 | `jvm-c1` | 16.09 (16.11) | 16.32 (16.33) | 1.94 GB | 126.8 G | 3 / 0; 325 ms | 2 | 144 |
<!-- END table:jit-1w -->

<!-- BEGIN ratio:jit-1w:jvm-c2 -->
| Configuration | CPU time / `jvm-c2` (median; range) | Wall / `jvm-c2` | Peak RSS / `jvm-c2` | Instructions / `jvm-c2` | Pairs |
|---|--:|--:|--:|--:|--:|
| `jvm-graal` | 1.008 (0.969 - 1.052) | 0.937 | 1.03 | 1.182 | 8 |
| `jvm-c1` | 1.217 (1.183 - 1.289) | 1.323 | 0.99 | 1.425 | 8 |
<!-- END ratio:jit-1w:jvm-c2 -->

On mathlib the host JIT variants were not measured (the host had no room left for two more groups
of mathlib runs); `scripts/platform-all.sh jvm-mathlib` and `jit8-1w` are the steps for it.

## 4. JVM: the collector

<!-- BEGIN legend:gc -->
- `jvm-c2`: C2 host JIT, 2 compiler threads (= launcher default, spelled out) - `-XX:+UseSerialGC -XX:-UseJVMCICompiler -XX:CICompilerCount=2 -Xms13g -Xmx13g -Xmn1g`
- `jvm-parallel-2`: Parallel GC capped at 2 collector threads - `-XX:+UseParallelGC -XX:ParallelGCThreads=2 -XX:-UseJVMCICompiler -XX:CICompilerCount=2 -Xms13g -Xmx13g -Xmn1g`
- `jvm-g1-2`: G1 capped at 2 parallel + 1 concurrent collector threads - `-XX:+UseG1GC -XX:ParallelGCThreads=2 -XX:ConcGCThreads=1 -XX:-UseJVMCICompiler -XX:CICompilerCount=2 -Xms13g -Xmx13g -Xmn1g`
- `jvm-parallel-auto`: Parallel GC, HotSpot's own thread count (8 on 8 vCPUs) - `-XX:+UseParallelGC -XX:-UseJVMCICompiler -XX:CICompilerCount=2 -Xms13g -Xmx13g -Xmn1g`
- `jvm-g1-auto`: G1, HotSpot's own thread counts (8 + 2 on 8 vCPUs) - `-XX:+UseG1GC -XX:-UseJVMCICompiler -XX:CICompilerCount=2 -Xms13g -Xmx13g -Xmn1g`
<!-- END legend:gc -->

Init, Std, cedar, reachability, eight workers, two repeats (the collectors are judged with parallel
workers, OWNER-NOTES 2):

<!-- BEGIN table:gc -->
| Corpus | Workers | Configuration | CPU s | Wall s | Peak RSS | Instructions | Collections (young / full; pause) | Runs | Load |
|---|--:|---|--:|--:|--:|--:|---|--:|--:|
| init | 8 | `jvm-c2` | 27.24 (27.81) | 6.46 (7.15) | 1.49 GB | 226.6 G | 2 / 0; 156 ms | 2 | 72 |
| init | 8 | `jvm-parallel-2` | 25.60 (25.70) | 5.64 (6.04) | 1.63 GB | 219.8 G | 2 / 0; 179 ms | 2 | 72 |
| init | 8 | `jvm-g1-2` | 25.35 (25.82) | 5.47 (6.52) | 1.59 GB | 223.2 G | 0 / 0; 0 ms | 2 | 71 |
| init | 8 | `jvm-parallel-auto` | **24.09 (25.25)** | 5.94 (6.21) | 2.48 GB | 217.9 G | 2 / 0; 179 ms | 2 | 71 |
| init | 8 | `jvm-g1-auto` | 27.04 (27.57) | 6.30 (6.62) | 1.59 GB | 222.2 G | 0 / 0; 0 ms | 2 | 73 |
| std | 8 | `jvm-c2` | 45.15 (45.17) | 10.60 (11.49) | 1.63 GB | 365.1 G | 3 / 0; 244 ms | 2 | 86 |
| std | 8 | `jvm-parallel-2` | 40.05 (42.32) | 8.81 (9.73) | 1.91 GB | 347.4 G | 3 / 0; 176 ms | 2 | 86 |
| std | 8 | `jvm-g1-2` | **38.68 (39.11)** | 8.79 (8.90) | 2.08 GB | 336.5 G | 1 / 0; 22 ms | 2 | 84 |
| std | 8 | `jvm-parallel-auto` | 40.32 (40.70) | 9.59 (9.74) | 3.39 GB | 344.4 G | 3 / 0; 297 ms | 2 | 81 |
| std | 8 | `jvm-g1-auto` | 40.38 (40.55) | 8.40 (9.20) | 2.14 GB | 350.8 G | 1 / 0; 17 ms | 2 | 81 |
| cedar | 8 | `jvm-c2` | 52.82 (53.31) | 12.32 (12.34) | 1.84 GB | 429.1 G | 4 / 0; 269 ms | 2 | 79 |
| cedar | 8 | `jvm-parallel-2` | **50.75 (52.27)** | 11.75 (11.97) | 2.09 GB | 421.1 G | 4 / 0; 248 ms | 2 | 77 |
| cedar | 8 | `jvm-g1-2` | 56.69 (57.00) | 12.64 (13.30) | 2.17 GB | 432.1 G | 2 / 0; 32 ms | 2 | 76 |
| cedar | 8 | `jvm-parallel-auto` | 51.90 (56.34) | 10.11 (13.77) | 3.74 GB | 431.5 G | 4 / 0; 356 ms | 2 | 75 |
| cedar | 8 | `jvm-g1-auto` | 54.36 (58.92) | 10.48 (13.44) | 2.22 GB | 438.6 G | 2 / 0; 30 ms | 2 | 76 |
| reachability | 8 | `jvm-c2` | **14.13 (14.72)** | 12.22 (12.85) | 2.13 GB | 96.3 G | 3 / 0; 449 ms | 2 | 69 |
| reachability | 8 | `jvm-parallel-2` | 16.46 (16.87) | 8.42 (9.05) | 2.84 GB | 103.4 G | 3 / 0; 335 ms | 2 | 69 |
| reachability | 8 | `jvm-g1-2` | 15.57 (15.76) | 7.30 (8.26) | 3.23 GB | 97.8 G | 0 / 0; 0 ms | 2 | 70 |
| reachability | 8 | `jvm-parallel-auto` | 15.84 (15.98) | 8.68 (11.11) | 4.43 GB | 99.6 G | 3 / 0; 473 ms | 2 | 70 |
| reachability | 8 | `jvm-g1-auto` | 14.17 (14.59) | 8.10 (8.71) | 2.95 GB | 98.1 G | 0 / 0; 0 ms | 2 | 72 |
<!-- END table:gc -->

<!-- BEGIN ratio:gc:jvm-c2 -->
| Configuration | CPU time / `jvm-c2` (median; range) | Wall / `jvm-c2` | Peak RSS / `jvm-c2` | Instructions / `jvm-c2` | Pairs |
|---|--:|--:|--:|--:|--:|
| `jvm-parallel-2` | 0.965 (0.886 - 1.165) | 0.848 | 1.17 | 0.982 | 8 |
| `jvm-g1-2` | 0.991 (0.857 - 1.130) | 0.838 | 1.24 | 0.992 | 8 |
| `jvm-parallel-auto` | 0.957 (0.884 - 1.130) | 0.865 | 1.89 | 0.997 | 8 |
| `jvm-g1-auto` | 0.991 (0.893 - 1.180) | 0.867 | 1.23 | 0.988 | 8 |
<!-- END ratio:gc:jvm-c2 -->

On mathlib the collectors were measured with a growing heap; they are in the heap table below
(`jvm-parallel-2-max-13g`, `jvm-g1-2-max-13g`).

## 5. Heap

<!-- BEGIN legend:heap+heap2 -->
- `jvm-heap-max-13g`: Serial, heap grows up to 13 GB, 1 GB nursery - `-XX:+UseSerialGC -XX:-UseJVMCICompiler -XX:CICompilerCount=2 -Xmx13g -Xmn1g`
- `jvm-heap-fixed-10g`: Serial, fixed 10 GB heap, 1 GB nursery - `-XX:+UseSerialGC -XX:-UseJVMCICompiler -XX:CICompilerCount=2 -Xms10g -Xmx10g -Xmn1g`
- `jvm-heap-fixed-9g`: Serial, fixed 9 GB heap, 1 GB nursery - `-XX:+UseSerialGC -XX:-UseJVMCICompiler -XX:CICompilerCount=2 -Xms9g -Xmx9g -Xmn1g`
- `jvm-g1-2-max-13g`: G1 (2 + 1 threads), heap grows up to 13 GB - `-XX:+UseG1GC -XX:ParallelGCThreads=2 -XX:ConcGCThreads=1 -XX:-UseJVMCICompiler -XX:CICompilerCount=2 -Xmx13g`
- `jvm-parallel-2-max-13g`: Parallel GC (2 threads), heap grows up to 13 GB - `-XX:+UseParallelGC -XX:ParallelGCThreads=2 -XX:-UseJVMCICompiler -XX:CICompilerCount=2 -Xmx13g -Xmn1g`
- `native-max-10g`: native image CE, -Xmx10g - `-Xmx10g`
- `jvm`: JVM launcher, its default flags - the launcher's defaults (`work/steward/platform/dist/BUILD-INFO.txt`, line `jvm flags`)
- `jvm-heap-track-15`: Serial, 8 GB initial heap growing to 13 GB, 15 % kept free after a full collection - `-XX:+UseSerialGC -XX:-UseJVMCICompiler -XX:CICompilerCount=2 -Xms8g -Xmx13g -Xmn1g -XX:MinHeapFreeRatio=15 -XX:MaxHeapFreeRatio=30`
- `jvm-heap-track-25`: Serial, 8 GB initial heap growing to 13 GB, 25 % kept free after a full collection - `-XX:+UseSerialGC -XX:-UseJVMCICompiler -XX:CICompilerCount=2 -Xms8g -Xmx13g -Xmn1g -XX:MinHeapFreeRatio=25 -XX:MaxHeapFreeRatio=40`
<!-- END legend:heap+heap2 -->

mathlib, eight workers, one run each:

<!-- BEGIN table:heap+heap2:-:-:jvm,jvm-heap-max-13g,jvm-heap-track-25,jvm-heap-track-15,jvm-heap-fixed-10g,jvm-heap-fixed-9g,jvm-g1-2-max-13g,jvm-parallel-2-max-13g,native-max-10g:plain -->
| Corpus | Workers | Configuration | CPU s | Wall s | Peak RSS | Instructions | Collections (young / full; pause) | Runs | Load |
|---|--:|---|--:|--:|--:|--:|---|--:|--:|
| mathlib | 8 | `jvm` | 1035.4 | 266.1 | 13.13 GB | 6,412 G | 84 / 3; 5.3 s | 1 | 131 |
| mathlib | 8 | `jvm-heap-max-13g` | 1041.7 | 358.0 | 12.99 GB | 6,336 G | 86 / 3; 5.5 s | 1 | 169 |
| mathlib | 8 | `jvm-heap-track-25` | 931.6 | 180.3 | 12.72 GB | 6,270 G | 85 / 4; 5.2 s | 1 | 142 |
| mathlib | 8 | `jvm-heap-track-15` | 974.1 | 187.5 | 13.01 GB | 6,403 G | 84 / 4; 5.2 s | 1 | 139 |
| mathlib | 8 | `jvm-heap-fixed-10g` | 939.6 (966.0) | 200.3 (221.7) | 10.11 GB | 6,361 G | 85 / 6; 5.1 s | 2 | 172 |
| mathlib | 8 | `jvm-heap-fixed-9g` | FAILED (exit 3) | 337.7 | 9.02 GB | - | - | 1 | 172 |
| mathlib | 8 | `jvm-g1-2-max-13g` | 989.0 | 214.4 | 13.24 GB | 6,343 G | 37 / 0 (+26); 680 ms | 1 | 166 |
| mathlib | 8 | `jvm-parallel-2-max-13g` | 1058.6 | 320.8 | 13.02 GB | 6,405 G | 155 / 4; 10.4 s | 1 | 134 |
| mathlib | 8 | `native-max-10g` | 1168.2 | 291.1 | 7.93 GB | 9,030 G | n.r. | 1 | 112 |
<!-- END table:heap+heap2:-:-:jvm,jvm-heap-max-13g,jvm-heap-track-25,jvm-heap-track-15,jvm-heap-fixed-10g,jvm-heap-fixed-9g,jvm-g1-2-max-13g,jvm-parallel-2-max-13g,native-max-10g:plain -->

What the GC logs say (mathlib, eight workers, Serial collector):

- **The live set.** After a full collection the heap holds 4.1 to 4.4 GB at the end of the load (the
  two 10 GB runs, whose first full collection falls there) and 5.5 to 9.1 GB while eight workers
  check (42 full collections in nine Serial runs; above 8 GB four times: 8.1, 8.1, 8.7 and 9.1 GB, in
  four different runs). With one worker the native image's whole RSS is 4.65 GiB, with eight 8.7 GiB:
  about half a gigabyte of reachable arrays per worker comes on top of the store. The driver's memory
  account of such a run reported 3.9 GB (integration log): the rest is array capacity that is
  allocated and not in use, and worker memory outside the account - a figure for the store's and the
  driver's owners (the driver's jar of 09:39Z counts allocated capacity and gives memory back; it is
  not in the measured distribution), not something a VM flag changes.
- **RSS equals the heap limit.** The process touches the whole heap whatever the live set is: young
  collections promote dead arrays, and the Serial collector expands the old generation up to `-Xmx`
  before it runs its first full collection. So `-Xms13g -Xmx13g` (today), `-Xmx13g` alone
  (`jvm-heap-max-13g`) and a lower `MinHeapFreeRatio` (`jvm-heap-track-15`, `-25`: full collections
  at 87 s, 110 s, 131 s, each from 12.6 to 13.0 GB) all end at 13.0 to 13.1 GiB. With **one** worker
  the JVM reaches 11.8 GiB without a single full collection, where the native image needs 4.65 GiB.
  The only setting that lowers the peak is the limit itself.
- **10 GB was enough twice, 9 GB is not.** `-Xms10g -Xmx10g`: accepted in both runs, 10.1 GiB RSS,
  six full collections instead of three, 0.09 to 0.34 s each (1.3 s per run), CPU time inside the
  spread of the 13 GB runs (940 and 992 s against 926 to 1,057 s). `-Xms9g -Xmx9g`:
  `OutOfMemoryError` after 337 s (exit status 3; ten full collections, the last two left 7.7 GB of
  the 8 GB old generation in use and an array did not fit). The 9.1 GB live set of the
  `jvm-heap-track-25` run would not have fitted into the 9 GB old generation of a 10 GB heap either.
- **A full collection is cheap here** (0.07 to 0.47 s for 4 to 9 GB of live primitive arrays: nothing
  to trace, little to move), which is why a smaller heap costs no measurable CPU time. Young
  collections: 81 to 86 per run, 3.8 to 4.8 s in total, whatever the heap size.
- **Other collectors on mathlib.** Parallel (2 threads): 155 young and 4 full collections, 10.4 s of
  pauses, same RSS, CPU time at the upper end of the Serial runs. G1 (2 + 1 threads): 0.7 s of pauses,
  no full collection, wall clock among the best of the hour - and 13.2 GiB RSS, the highest of all:
  it lets the heap fill before it reclaims the dead humongous arrays. Neither lowers RSS or CPU time.
- **The native image** needs 8.7 to 9.0 GiB with its default 13 GB limit and 7.9 GiB with `-Xmx10g`
  (same CPU time).

So, with the measured distribution (jars of 07:54Z): the smallest heap that did not cost CPU time on
mathlib with eight workers is 10 GB (10.1 GiB RSS), and it has no margin: 9 GB fails, and the peaks
of the live set move with the schedule (up to 9.1 GB seen, which a 10 GB heap cannot hold); a heap
that is safe for every schedule seen needs 11 GB or more, and the native image's 8.7 to 9.4 GiB
cannot be reached.

### 5.1 The same question with the jars of 10:56Z

During the wave the store's owner published a store whose overlays give memory back (10:33Z) and the
driver's owner a memory account of allocated capacity with give-back (09:39Z). The store's owner
reported mathlib accepted with `-Xms8g -Xmx8g -Xmn1g` at 8.24 GB RSS (requests.log, 11:00Z). Checked
here with the distribution frozen at 10:56Z (`work/steward/platform/dist-1100`: store `720f505913d7`,
driver `d8bebed464b5`, ref `85ed3ee7a85a`, admit `8d7092222f70`, load `30617170f7a3`), JVM launcher,
Serial, eight workers. These runs were made on the **host** (efficiency cores and CPUs 18, 19, beside
other people's jobs, because the container's cores were taken): they are pass / fail, RSS and GC-log
results, **not timings**.

| Input | Heap | Result | Peak RSS | Collections (young / full) | Live after a full collection |
|---|---|---|--:|---|---|
| mathlib | `-Xms10g -Xmx10g -Xmn1g` | accept | 10.02 GiB | 132 / 4 | 3.2 to 4.4 GB |
| mathlib | `-Xms8g -Xmx8g -Xmn1g` | accept | 7.86 GiB | 126 / 7 (0.04 to 0.38 s each, 1.2 s) | 3.1 to 4.5 GB |
| con-leche | 8 / 10 / 13 GB | accept | 7.41 / 7.29 / 7.28 GiB | - | - |
| cslib | 8 / 10 / 13 GB | accept | 3.73 / 3.40 / 3.48 GiB | - | - |
| navier-stokes-euler | 8 / 10 / 13 GB | accept | 2.50 / 2.36 / 2.48 GiB | - | - |
| all 230 published tests (one worker) | 8 GB and 10 GB | 230 / 230 correct, 0 declined (verdict runs) | - | - | - |

(`work/logs/steward/heap-current-jars.log`, `heap-current-jars-mathlib.log`,
`work/steward/slice-all-heap-8g.md`, `slice-all-heap-10g.md`, GC logs
`work/steward/platform/gclogs/host-current-jars-*`.) con-leche is the input with the largest need:
7.3 GiB whatever the limit (one declaration, unit 24018).

**Answer to the two questions of the task.** The smallest heap that costs no CPU time: 10 GB with the
jars of this morning (no margin), 8 GB with the jars of 10:56Z (everything above passes; the extra
full collections cost about a second on mathlib). The native image's RSS (8.7 to 9.4 GiB on mathlib):
out of reach this morning, reached with `-Xmx8g` now (7.9 GiB). **The default was not lowered**: the
smallest safe heap moved by 2 GB within three hours as the store changed, it will move again when
the machine is the engine, an `OutOfMemoryError` is a lost verdict while RSS is not ranked, and with
13 GB the process stays below 13.2 GiB on a 16 GB runner. The setting to take to the platform gate
is `-Xms10g -Xmx10g -Xmn1g`, verified against the engine that is shipped then.

## 6. Reading and recommendation

### 6.1 Launchers

- **mathlib decides for the JVM launcher on CPU time.** Eight workers, back to back: JVM 1,057 s, PGO
  image 1,082 s (+2 %), CE image 1,187 s (+12 %). Over the whole day the JVM launcher with a 13 GB
  Serial heap took 926 to 1,057 s in six eight-worker runs (940 and 992 s with a 10 GB heap) and the CE
  image 1,168 to 1,203 s in three: the two ranges do not overlap, at any load. One worker: 886 s
  against 983 s (PGO, +11 %) and 1,112 s (CE, +26 %). Instruction counts, which do not depend on the
  load: 6.3 to 6.5 T against 7.6 T and 9.0 T (CE: +41 %). This is decision 12 of ARCHITECTURE.md
  ("the JIT produces better code than the CE AOT compiler") measured on the real pipeline: the
  advantage is 11 to 26 % in CPU time for the reference engine, and it is larger with one worker
  than with eight, where the JVM's own threads (JIT, collector) and its warm-up are multiplied.
- **On the small corpora the three are within a tenth to a fifth of each other**, and the order turns
  around with the worker count: the CE image needs 0.92 times the JVM's CPU time with eight workers
  (median of 16 pairs; the JVM warms up eight threads at once and compiles while they run) and 1.03
  times with one. The PGO image needs 0.82 and 0.91 times. A run of 15 to 55 s is where warm-up and
  steady state balance for this engine. The order of the runs inside a group is not what makes the
  difference: with the JVM first the medians are 0.93 and 0.83 (12 pairs), with the JVM last 0.86
  and 0.78 (4 pairs, at a lower host load).
- **Four workers on mathlib** (the arena run line's count): JVM 946 s CPU / 266 s wall / 13.0 GiB, CE
  image 1,069 s (+13 %) / 295 s / 7.5 GiB.
- **Memory is the image's side**: a third to a half of the JVM's RSS on the small corpora; on mathlib
  8.7 GiB (CE) and 8.1 GiB (PGO) against 13.0 GiB, and 4.65 and 3.9 GiB against 11.8 GiB with one
  worker. Section 5: with these jars the JVM cannot go below about 10 GiB on mathlib with eight
  workers; with the store of 10:33Z it can (7.9 GiB with an 8 GB heap).
- **PGO** is the best of both where it was trained (Init) and close to the JVM on mathlib with eight
  workers, at the image's memory. It needs Oracle GraalVM (GFTC licence): not a candidate until the
  project owner has decided; the numbers are here so that the decision has them.
- The 230 small tests are not ranked and have no tight limits; the image answers them in
  milliseconds, the JVM launcher in 0.2 to 0.4 s each. `perf/app-lam`, the test closest to its limit,
  is the other way round: 9.3 s on the JVM launcher against 16 s for the image (integration log).

### 6.2 JVM flags

- **Host JIT.** Graal (libgraal) instead of C2 as the JVM's own compiler changes little: 0.97 times
  C2's CPU time with eight workers (eight pairs, range 0.93 to 1.07) and 1.01 with one (eight pairs,
  0.97 to 1.05), with 11 to 18 % more instructions. C1 alone costs 22 % more CPU time (one worker).
  **C2 stays.** The number of compiler threads is the one flag that showed an effect: with HotSpot's
  own choice (four on eight CPUs) instead of the launcher's `-XX:CICompilerCount=2`, eight workers
  leave the interpreter and the C1 code earlier, and the run needs 0.92 times the CPU time and 0.90
  times the wall clock (median of eight pairs; Init, Std and cedar 7 to 11 % less in both repeats,
  reachability 8 % more; range 0.82 to 1.08). The flag dates from the time when instructions were the
  target ("compiler threads are billed", `scripts/build.sh`); by CPU time with parallel workers it
  looks wrong for runs of this size. **Not changed today**: eight pairs on a loaded host with a range
  that crosses 1.0 are not unambiguous, the one-worker case and mathlib were not measured, and the
  JVM launcher is not the shipped binary. It is the first thing to measure at the gate.
- **Collector.** On Init, Std, cedar and reachability with eight workers a run sees
  zero to four young collections and no full one, whatever the collector (the nursery is 1 GB; G1
  often collects nothing at all), so the collector has little to decide: Parallel with two threads
  needs 0.97 times Serial's CPU time (median of eight pairs, range 0.89 to 1.17), G1 with 2 + 1
  threads 0.99 (0.86 to 1.13), the uncapped variants 0.96 and 0.99 - ranges that are the noise of
  this host. Every alternative needs more memory: +17 % RSS (Parallel, two threads), +23 to +24 %
  (G1), +89 % (Parallel uncapped: eight collector threads' buffers and an adaptive nursery). On
  mathlib (section 5) Parallel doubles the collections and the pause time, and G1 has the highest
  RSS of all configurations. **Serial stays.** (The wall-clock ratios of that table, 0.84 to 0.87
  for all four alternatives alike, are not explained by the pause totals, which are a few hundred
  milliseconds either way; the Serial configuration ran first in every group. The launcher
  comparison, repeated in both orders, shows no advantage of a later position, so this stays
  unexplained and is not used.)
- **Heap.** Section 5. `-Xms13g -Xmx13g -Xmn1g` stays for now: it is the limit under which no
  schedule of any jar set of today ran out of memory, and the process cannot grow beyond about
  13.2 GiB with it. `-Xms10g -Xmx10g -Xmn1g` is the candidate for the gate (no CPU cost; 2 GB above
  what today's jars need), 8 GB the floor with today's jars.

### 6.3 Recommendation

1. **Do not switch the arena definition now; switch it at the platform gate if the picture holds.**
   The definition keeps the CE native image. For mathlib-class inputs the JVM launcher is the faster
   platform for the reference engine by a margin that the noise of this host does not explain away
   (non-overlapping ranges, 11 to 26 %, instructions 29 % lower), and by the project's order of
   criteria (CPU time, wall clock, memory) it would be the one to ship for mathlib. What speaks
   against switching today: (a) the binding gate is defined for the machine (RECONCILIATION section 3:
   "run when the machine first accepts Init"), whose balance of compiled code against substitution
   and interning is a different one - the reference engine is not what will be submitted; (b) every
   number here is aarch64 on a host at five to nine times its capacity; the runner is x86-64 and
   quiet; (c) the measured distribution is this morning's - the store published at 11:17Z makes the
   reference engine about 40 % cheaper on mathlib (its owner's measurement), and nobody has measured
   how the launchers compare with it; (d) the JVM's 13 GiB on a 16 GB runner leaves less room than
   the image's 9 GiB (the one arena failure on record that was a kill by the VM, rpylean, exit 137,
   happened at 14.65 GB, docs/research/01), and the heap that brings the JVM to 8 to 10 GiB is safe
   only since this morning's store change (section 5.1); (e)
   `bench/rlvm/run.sh` (not the steward's) finds the binary in the run line by the text
   `work/dist/bin/rlvm ` and would need a change with it. The switch itself is one word in
   `scripts/arena/rlvm.yaml` (`bin/rlvm` to `bin/rlvm-jvm`; the build step already produces both).
2. **Re-run this series when the machine passes its gates**, with the machine as the engine
   (`RLVM_PLATFORM_ENGINE=machine`), on a quiet host, mathlib three times per launcher, and once on an x86-64
   machine: `scripts/platform-all.sh` + `scripts/platform-report.py --update docs/PLATFORM.md`
   (section 7). Decide then by CPU time, wall clock, memory, in that order.
3. **Until then, measure and guard on the JVM launcher** (what `scripts/dev.sh dist` builds in four
   seconds; lowest CPU time on the large corpora) and keep the image for the arena definition and the
   small tests.
4. **Memory is the engine's problem before it is the platform's.** This morning: 4 GB of store plus
   half a gigabyte per worker of reachable arrays, of which the memory account saw 3.9 GB; no heap
   flag brought the JVM below 10 GiB. After the store's change of 10:33Z: 3.1 to 4.5 GB live, and
   8 GB of heap are enough. The heap limit only decides how much dead memory the process keeps.

## 7. What was changed, what was not, and how to repeat this

**Launcher defaults: none was changed.** Not the launcher of the arena definition (CE native image,
`--threads 4`), not the JVM launcher's flags (`scripts/build.sh`: Serial, C2, two compiler threads,
`-Xms13g -Xmx13g -Xmn1g`), not the image's (`-O2` for `dev.sh dist-native`, `-O3` for the arena build,
`--gc=serial`, `-R:MaxHeapSize=13g`). The candidates for a change at the platform gate, in the order
of the evidence for them: the JVM launcher for mathlib-class inputs (6.3), a 10 GB heap for it (5.1),
HotSpot's own compiler thread count (6.2).

Changed in the tools (all in `scripts/`, the steward's):

- `scripts/build.sh --published --pgo` works: the Oracle variant compiles `rlvm-cli` from the snapshot
  as well, so that its Truffle jars follow the Oracle GraalVM's version (it used to pick the CE
  Truffle jars through the published `rlvm-cli` pom, and `native-image` failed in `TruffleBaseFeature`).
  The verification of every launcher now also checks the usage status (64).
- New: `scripts/platform-matrix.py` (the matrix; `--list` for the configurations, `--cpuset` for the
  one-worker container, records the host load and other containers on its CPUs, stops its container
  when killed), `scripts/platform-all.sh` (the series), `scripts/platform-report.py` (the tables of
  this file; `--update docs/PLATFORM.md`). `scripts/dev.sh platform ...` and `platform-report`.
- `scripts/arena/rlvm.yaml`: description and state comment rewritten (reference engine; the machine
  is in progress and is not in the binary); the run line is unchanged. `scripts/arena/test-in-docker.sh`
  holds the measurement lock while it builds and runs. `scripts/dev.sh arena-test --reuse --strict`
  passes with the tree of 10:26Z (work/status/steward.log).

Not measured, and why:

- **x86-64.** No such machine here. The baselines document (docs/research/12) has aarch64 / x86-64
  ratios for other checkers, none for HotSpot against a native image.
- **A quiet host.** See 1.1. The absolute CPU times are 10 to 25 % above what the same binaries need
  on an idle host; the ratios between configurations of one group are the result.
- **`-march=native`** for the image (ARCHITECTURE 7.5 calls it admissible), **`-O2` against `-O3`**
  (the integration log's `-O2` image needed 10,207 G instructions on mathlib, the `-O3` image here
  9,025 G: -12 %; no CPU-time pair), **the epsilon collector** and **G1 for the image** (Oracle only):
  each needs another image build of 700 CPU-seconds, which this host could not give today.
- **ZGC / Shenandoah** on the JVM: concurrent collectors could hold the heap near the live set at the
  price of collector threads; one mathlib run each would tell.
- **Three repeats of mathlib per launcher** (ARCHITECTURE 7.1 asks for three at the gate): one
  back-to-back group plus the single runs listed; the heap and collector rows are single runs.
- **Four workers** on the small corpora, and the default worker count itself: the driver's owner
  measures that (`rlvm-driver/bin/threads-gate.sh`).

To repeat everything (about three hours of an idle host):

```
scripts/build.sh --published --pgo --opt 3 --dist work/steward/platform/dist              # frozen distribution
scripts/platform-all.sh small8 mathlib8 heap heap2 jit8 jit8-1w gc8 mathlib4 jvm-mathlib  # arena-shaped container
scripts/platform-all.sh small1 jit1 mathlib1                                              # one worker, CPUs 18,19
scripts/platform-report.py --update docs/PLATFORM.md                                      # the tables
```

With the machine: `RLVM_PLATFORM_ENGINE=machine scripts/platform-all.sh ...` (the machine's jar must be
in the distribution, i.e. the module in the parent pom; for the JVM launcher alone `RLVM_EXTRA_CP` is
enough). On a host with other core numbers: `RLVM_SPARE_CPUS=a,b` for the one-worker container.
