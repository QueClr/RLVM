# bench/track - performance tracking (work package T2)

One command per corpus: run RLVM in the arena-shaped container, append the measurement to a history
file, print the comparison with the baselines measured on this host (wall clock first), and apply the
regression rule - which is on WALL CLOCK since 2026-11-24 (OWNER-NOTES 12): the warm wall clock of an
entry made with `--passes N`, else the wall clock of the fresh process.

```bash
bench/track/track.py init                      # work/dist/bin/rlvm with the arena's run line, 3 runs (cold entry)
bench/quiet.py -- bench/track/track.py init --launcher jvm --threads 8 --passes 5   # WARM entry: the target metric
bench/track/track.py init-prelude | std | cedar | con-leche | cslib | reachability | navier-stokes-euler | mathlib
bench/track/track.py cedar-hot-ax              # a slice: work/workloads/slices/NAME.ndjson, work/exports/slices/good/NAME.ndjson
bench/track/track.py perf/magma-list-pair-n21  # a published test; a bad one (perf/unfold-before-literal-bad) must be REJECTED
bench/track/track.py init --launcher jvm --threads 8 --engine reference
bench/track/track.py init --launcher jvm --engine machine --extra-cp rlvm-machine/target/classes   # an engine outside the distribution
bench/track/track.py init --phases             # + per-phase table (load / env / check)
bench/track/track.py init --show               # history and comparison, nothing measured
bench/track/track.py init --checker gruszka    # a baseline through the same machinery
bench/track/selftest.py [--docker]             # checks of the tracker itself

bench/track/ladder.py                          # ALL corpora, JVM + native, reference engine, 8 workers; missing baselines
bench/track/ladder.py --tier small --engine machine --launcher jvm --extra-cp rlvm-machine/target/classes
bench/track/ladder.py --tier small --launcher jvm --passes 5      # warm entries for a tier
bench/track/ladder.py --list                   # what is recorded for the ladder; measures nothing
bench/track/benchmarks.py --tsv work/perf-history/warm.tsv --labels bench   # docs/BENCHMARKS.md: one table per metric, owner's order
bench/track/benchmarks_cold.py --stdout        # the cold tables from the history (superseded form of that document)
```

| File | What |
|---|---|
| `track.py` | one corpus, one configuration: measure, record, compare, apply the rule (host; starts containers; Python stdlib only) |
| `ladder.py` | `track.py` over all corpora for one engine and one or more launchers, then the baselines that this host has no measurement of; one measurement in the container at a time (lock `work/perf-history/.ladder.lock`, taken per measurement) |
| `benchmarks.py` | generates `docs/BENCHMARKS.md` from rows of `bench/warm.sh` (and the history for instruction counts): corpus x {RLVM machine, RLVM reference} x {JVM, native} + gruszka + nano-lean, one table per metric in the owner's order (warm wall 8 workers, warm wall 1 worker, cold wall, CPU, RSS, instructions), and for every cell the row, its `bench/quiet.py` window and its jars; the text around the tables is `benchmarks-notes.md` |
| `benchmarks_cold.py` | the generator of that document until 2026-11-24T23Z: cold tables (CPU time, wall clock, RSS, instructions of a fresh process on a batch load) from the tracker's history; superseded as the statement of where RLVM stands, kept as a tool |
| `selftest.py` | 77 checks of the rule, the pass lines, the summaries, phase differencing, baseline lookup, the command line, the ladder and both generators on the host (independent of what is built in `work/dist`); `--docker` adds 10 checks of the whole command with a fake checker in the container |
| `fake-burner.sh` | the fake checker of the self-test (burns a chosen amount of CPU, exits with a chosen status) |

## The ladder

| Tier | Corpora | Runs |
|---|---|--:|
| `small` | init, std, cedar, reachability, `cedar-hot-ax` (the Cedar slice of `docs/research/13`: Cedar's 172 most expensive declarations over a statements-only library) | 3 |
| `perf` | the evaluation-heavy `perf/` tests of `docs/research/13` section 4: `unfold-before-literal-fallback`, `magma-list-pair-n21`, `gf3-matrix-product`, `magma-list-deep-n36` (accept) and `proj-applied-struct-arg-bad`, `proj-applied-struct-bad`, `unfold-before-literal-bad`, `ite-taken-branch-bad` (reject) | 3 |
| `large` | con-leche, navier-stokes-euler, cslib, mathlib | 1 |

`ladder.py [--tier small,perf,large | --only NAMES] [--launcher jvm,native] [--threads 8] [--engine NAME]
[--extra-cp PATH] [--dist DIR] [--baselines gruszka,nano-lean,official | none] [--no-rlvm] [--runs N]`.
The default configuration is the reference engine with 8 workers (the runner's vCPUs); `--threads 0`
uses the thread count of the arena run line (`scripts/arena/rlvm.yaml`). Each call of `track.py`
applies the regression rule to its configuration; the ladder's exit status is the worst of them
(2 invalid, 1 regression, 0 clean). The whole ladder with both launchers of the reference engine takes
about 25 minutes of container time (mathlib alone: 6 minutes), the perf tier 10, the missing
baselines of the non-arena corpora 25 more, once (the official checker: cslib and navier-stokes-euler
about 10 minutes each).

The shared distribution is versioned since 2026-11-24T14Z (`scripts/build.sh`: `work/dist` is a symbolic
link into `work/dist.d/`, switched atomically by every build): `track.py` and `ladder.py` resolve the
link once at their start and measure that version to the end, so a rebuild during a ladder no longer
gives entries of mixed builds. An old version is deleted when it is neither among the newest four nor
younger than 90 minutes; for anything that must outlive that, measure a private build
(`scripts/build.sh --published --dist work/ME/dist-STAMP`) or a copy (`cp -aL work/dist work/ME/dist-STAMP`;
`-L`, because `work/dist` is a link), as the entries of 2026-11-24 do (`work/rlvm-testkit/dist-0721Z`).

## What is measured

Every run is one fresh container sized like the arena runner, with nothing else in it, exactly as
`bench/baselines/big.sh` measured the baselines:

```bash
bench/arena-docker.sh --arena-limits --no-network bench/measure.sh --require-perf \
    --timeout T --cwd ROOT --in EXPORT -o RAW.json -c 'RUN LINE'
```

- `RUN LINE` is the arena's own run line of RLVM (`scripts/arena/rlvm.yaml`, through
  `bench/rlvm/run.sh --print`), with the launcher (`--launcher native|jvm|pgo`), the thread count
  (`--threads`), the engine (`--engine NAME` = `-X engine=NAME`), extra arguments (`--rlvm-arg`) and,
  for the JVM launcher, extra class path entries (`--extra-cp`, i.e. `RLVM_EXTRA_CP`) replaced on request.
- A published bad test is a valid measurement only when it is rejected (exit status 1); every other
  corpus must be accepted.
- `T` = twice the arena's timeout of the corpus (`--timeout-factor`; 3600 s where the arena has none).
- Recorded per run and listed in this order (OWNER-NOTES 12): **warm wall clock** (only with
  `--passes N`), **wall clock** of the fresh process, **CPU time** (perf task-clock over all threads
  and children), **peak RSS**, **user-space instructions** (plus kernel instructions, counter
  coverage, host load average, number of other containers).
- **Warm passes** (`--passes N`, N >= 2; RLVM only): the run line gets `-X passes=N`, the process
  checks the export N times and prints one line per pass (`rlvm: pass K/N mode=.. wall_ms=..
  cpu_ms=.. verdict=..`, rlvm-main `Passes`). The tracker reads the pass lines from the run's log
  (also when `-X passes=N` came through `--rlvm-arg`): `passes` (every pass), `pass1_wall_s`,
  `warm_wall_s` = the MEDIAN LATER PASS of the run (a real pass: its CPU time is `warm_cpu_s`),
  `warm_best_s`. A run in which a pass ends with another verdict than expected is invalid. The
  wall clock, CPU time, RSS and instructions of such a run are those of the whole process (all N
  passes). Take warm entries inside a `bench/quiet.py` window.

The figures of an entry are **minima over its runs** (default 3; for CPU time the median and maximum
are kept). Other containers share the eight performance cores, and whatever they do can only add
time, never remove any. Measured on this host: gruszka on mathlib took 62.4, 75.9 and 81.9 s of CPU
in three runs whose instruction counts are 0.3 % apart. The entry's warm wall clock is the lowest
median later pass over its runs; peak RSS is the maximum, instructions the median. The baselines
are summarised the same way.

## History

`work/perf-history/CORPUS.jsonl`, one JSON line per invocation:

```
utc, corpus, export, export_bytes, checker, config ("rlvm/native/t4[/extra args]"), command, launcher, threads,
warm_wall_s, warm_wall_s_best_pass, warm_cpu_s, pass1_wall_s, passes                 (with --passes N; else null)
cpu_time_s (min), cpu_time_s_median, cpu_time_s_max, cpu_spread_pct, wall_time_s (min), wall_time_s_median,
max_rss_bytes, instructions, instructions_kernel,
valid, invalid_reason,
metric ("warm_wall_s" | "wall_time_s"), threshold_pct, reference_utc, reference_value, delta_pct,
reference_cpu_time_s, cpu_delta_pct, instructions_delta_pct                          (the last three: diagnostics)
regression, improvement, accepted, reference, accept_reason,
build {binary, binary_sha256, binary_mtime_utc, built, modules, rlvm_jars_sha256, commit}, note,
runs [ the complete output of bench/measure.sh per run + started_utc, host_loadavg_start/end, other_containers, log,
       passes [ {pass, of, wall_s, cpu_s, gc_ms, load_s, check_s, verdict, pipelined} ], warm_wall_s, ... ],
phases {load, env, check, total}, phase_runs            (with --phases)
```

Raw measurements and the checker's output of every run are in `work/perf-history/raw/CORPUS/`.

## Regression rule

**The rule is on wall clock** (OWNER-NOTES 12; until 2026-11-24 it was on the CPU time of a cold
process, which note 12 no longer makes the target). The figure of an entry is its **warm wall
clock** when it was made with `--passes N` (the median later pass), else the **wall clock of the
fresh process** - which note 12 reports "for information": a cold entry is judged by it because it
has nothing else, and the tool says that the target metric needs `--passes`. The figure is compared
with the **reference** of the entry's configuration (checker, launcher, threads, extra arguments;
`-X passes=N` is one of them, so warm and cold entries never share a reference): its last accepted
entry that was made the reference and has that figure.

| Wall clock against the reference | Result | Exit |
|---|---|---:|
| more than 5 % above | **REGRESSION**: recorded with `accepted: false`; the reference stays | 1 |
| more than 5 % above, with `--accept "REASON"` | accepted on purpose; the reason is stored; becomes the reference | 0 |
| within 5 % | accepted; the reference stays where it is | 0 |
| more than 5 % below | improvement; accepted; becomes the reference | 0 |
| no reference yet | first valid entry: accepted; becomes the reference | 0 |
| a run with the wrong verdict, a timeout, no counters or partial counter coverage | **INVALID**: recorded, never compared, never a reference | 2 |

- The threshold is `--threshold` (percent, default 5: two identical 8-worker rows of one quiet window
  differ by up to 5 %, `docs/research/profiles/J1.md` section 1; it was 3 for CPU time).
- Entries inside the band do not move the reference, so small regressions cannot add up unnoticed: a
  drift of 2 % per step is flagged at the second step (`selftest.py`).
- All corpora are accept tests: a valid run exits with 0. The placeholder checker in today's
  `work/dist` declines everything, so its entries are INVALID, which is the intended behaviour.
- The CPU-time delta and the instruction delta are printed next to the verdict and are not part of
  the rule (owner note 12: CPU time is a diagnostic, instructions are the arena's metric). They help
  to read a flag: wall clock up, instructions unchanged and a busy host mean contention, and the
  tool says so; re-measure inside a `bench/quiet.py` window before acting.
- Entries written before the change have no `metric`; a reference among them serves a cold entry
  with its `wall_time_s`.
- `--no-record` measures and compares without touching the history; `--note TEXT` is stored.

## Comparison with the baselines

Sources, in this order per checker: `bench/baselines.json`; `work/baselines/big/runs.jsonl` (arena
configuration); `work/baselines/small/NAME.json` (the published small tests, one run each); entries of
the history made with `--checker NAME`. Only runs that accepted count.

```
corpus mathlib: work/exports/mathlib.ndjson (6,326,141,213 bytes); configuration rlvm/native/t4
  checker                    CPU s  (median)    wall s  RSS MiB   instr G runs   ours/their CPU ours/their wall
  gruszka                    62.43     75.93     18.13     2592     418.7    3
  nano-lean                  85.59     107.2     25.54     5157     361.3    3
  sokonanoda                 101.4     113.2     30.45     5228     504.3    3
  rpylean                    117.5     131.8     117.9     4545     544.6    3
  ...
```

With a measurement, the first row is RLVM (`> rlvm/native/t4`) and the last two columns are RLVM's
CPU time and wall clock divided by the baseline's (below 1 = RLVM is faster).

## Phases

`--phases` adds runs with `rlvm -X stop-after=load` and `-X stop-after=env` (they end with DECLINE by
design, exit status 2) and prints the table obtained by differencing:

```
load   = the run that stops after loading (process start, reading, loading)
env    = stop-after=env minus stop-after=load (environment pass)
check  = complete run minus stop-after=env (checking, verdict, exit)
```

## State (2026-11-24, 23Z: the rule follows owner note 12)

- `track.py` **reads the pass lines** (`--passes N`; `read_passes`) and its **regression rule is on
  wall clock**: warm for an entry with passes, cold otherwise; threshold 5 %. `ladder.py --passes N`
  makes warm entries for a tier. The comparison and history tables print warm wall, cold wall, CPU,
  RSS, instructions in that order.
- `benchmarks.py` is new: `docs/BENCHMARKS.md` is generated from the rows of `bench/warm.sh` (the tool
  that measures the owner's metric on the host, inside `bench/quiet.py` windows) and, for the
  instruction counts, from this history; one table per metric in the owner's order, a provenance row
  per cell (row, window, jars). The former generator is `benchmarks_cold.py`.
- `bench/warm.py` records the distribution and its build id in every row (`dist`, `build`), has
  `--load default` (no `-X load`: the checker's own rule) and `--cold-only`.
- Not done: the tracker itself still runs in the arena-shaped container and does not take a quiet
  window on its own (call it through `bench/quiet.py --`); warm entries of the baselines do not exist
  (a Rust checker has no passes: its wall clock is compared with RLVM's warm wall in the comparison
  table); `selftest.py --docker` was not extended to a run with passes (the fake checker prints none).
- `selftest.py`: 77 host checks.

## State (2026-11-24, wave 7)

- **Owner note 12 (wall clock after warm-up, pipelined loading, is the goal) arrived at the end of this
  wave.** The tracker still measures ONE FRESH PROCESS per run on a batch load and applies its
  regression rule to CPU time: by note 12 that is the cold picture (items three and four of its list).
  `benchmarks.py` now puts the cold wall clock first and says that the warm columns are missing. What
  the tracker needs next: a launcher that checks an export N times in one process (`rlvm --passes N`
  does not exist), `-X load=pipelined` in the default configuration, the warm pass as the figure of an
  entry, and the rule on warm wall clock.
- `benchmarks.py` writes **engine columns**: `RLVM machine | RLVM reference | gruszka | nano-lean | official`
  (`--engines machine,reference`, `--launcher native`, `--threads 8`), one row per corpus that has a
  machine result; a further table has the other launcher for both engines, and the corpora without a
  machine result are listed with the reference engine. `--engine NAME` gives the former one-engine form,
  `--note TEXT` adds a line to the legend (which machine jar was measured).
- Machine entries are made with `track.py CORPUS --engine machine --launcher native --threads 8`
  (configuration `rlvm/native/t8/-X engine=machine`). The machine's own counters (accepted / declined
  units) are not part of an entry: a declined unit is checked by the reference engine and its time is in
  the figure; `docs/PLATFORM.md` section I.6 has the counters of the measured distribution.
- **The machine entries in the history today** (Init, Std, reachability, `cedar-hot-ax`; image and JVM
  launcher, 8 workers) are the eight-worker runs of the platform matrix of 13:33Z to 13:49Z, entered by
  `work/steward/w7/import-matrix.py` (`imported_from` in the entry; the same `bench/measure.sh` run in
  the same container). The `track.py` entries made at 14:20Z (three runs each; also the full cedar
  corpus) fell into the forty minutes in which the host was out of memory and are marked invalid
  (`invalid_reason`), as the orchestrator ruled for every measurement of that window. A ladder run on
  a host that is not thrashing replaces them by the rule (a lower entry becomes the reference).
- `track.py` and `ladder.py` pin the version of a versioned distribution at their start (above).
- The measurement lock was the bottleneck of the day: thirty agents, up to ten queues behind
  `work/perf-history/.ladder.lock`, holds of minutes. `ladder.py --batch N` takes the lock once for N
  measurements; a queue that needs the lock for several tools holds it itself and calls `track.py`
  directly (`work/steward/w7/queue2.sh`). Never build while holding it.
- `selftest.py`: 55 host checks pass (two new: the engine columns, the one-engine form).

## State (2026-11-24, wave 4)

- The ladder was run for the reference engine of the distribution built at 07:21Z (a copy:
  `work/rlvm-testkit/dist-0721Z`; JVM launcher and CE native image, 8 workers). Histories are in
  `work/perf-history/`, the table in `docs/BENCHMARKS.md` (its provenance section lists every cell's
  runs, date and host load). Every recorded entry is valid (right verdict, full counter coverage).
- **The host was not quiet.** Other agents' guard runs kept the 1-minute load average between 7 and
  190 (20 CPUs; median 103 at the start of the 86 RLVM runs, 7 to 35 during the small and large tiers); they are pinned to the 12 CPUs outside the container's cpuset, and measurements
  share the lock `work/perf-history/.ladder.lock`, but not everybody took it. Three-run entries
  spread by up to 29 % in CPU time (median 7 %) with instruction counts within 3 %; mathlib measured
  921.6 s (JVM) and 1,198.6 s (native) of CPU here, against 841.8 s and 1,027.6 s for the same
  binaries with the same instruction counts earlier that day (`work/status/integration.md`). Read CPU
  differences below about 10 % between two entries of that day as noise, and re-measure on a quiet
  host: a lower figure becomes the reference by the rule (improvement of more than 3 %).
- Baselines of gruszka, nano-lean and the official checker now exist for cedar, reachability,
  `cedar-hot-ax`, con-leche, navier-stokes-euler and cslib as well (entries made with `--checker`);
  sokonanoda and rpylean were not measured on these six.
- `selftest.py` (53 host checks, ladder and generated table included) and `selftest.py --docker`
  (10 container checks) pass.

## Earlier state and gaps

- Tested end to end in the container with a fake checker (`selftest.py --docker`: first entry,
  regression, accepted regression, improvement, invalid verdict, phases, history contents, no
  container left behind). `selftest.py --docker` deletes and rewrites `work/perf-history-selftest/`
  every time; do not keep anything else there.
- Run with a real baseline and with the RLVM launcher of the day; the records are in
  `work/perf-history-demo/` (2026-11-24, host load average about 12):
  - `init --checker gruszka`, 3 runs: 2.87 s CPU (median 3.00, maximum 3.34), 0.86 s wall, 374 MiB,
    22.64 G instructions; accepted as the first reference of that configuration. The 16 % spread of
    CPU time over three runs with instruction counts 0.6 % apart is the noise the minimum is for.
  - `init-prelude --launcher jvm`, 1 run: exit status 2 (the JVM launcher built at 05:40Z declines
    every export: the driver is still a skeleton), 0.11 s CPU, 77 MiB, 0.56 G instructions; recorded
    as INVALID, as intended. The native placeholder that was in `work/dist` before 05:40Z behaved the
    same (declined; INVALID); that record was in the self-test's directory and is gone.
- **The default launcher is `native` (`work/dist/bin/rlvm`, the arena's binary).** A JVM-only build of
  the distribution (`scripts/dev.sh dist`, as of 05:40Z) has no `bin/rlvm`: the command then stops
  with exit status 70 and names the launchers that are built (`--launcher jvm`). `--dry-run` prints
  the commands anyway, with a warning.
- **Not yet run with an RLVM that accepts a corpus**: none exists. Nothing has been written to
  `work/perf-history/` (the directory is created by the first recorded run).
- **`--phases` with the real `-X stop-after`** has not been run (same reason); the differencing was
  tested with the fake checker (`--cmd ... --phase-cmd load=... --phase-cmd env=...`).
- Per-phase split from RLVM's own statistics (`-X stats-file`: steps, phase times inside one run) is
  not read yet; phases come from separate runs only.
- No "nightly ladder" script; call the command once per corpus (init-prelude, init and two slices per
  change; the rest nightly, ARCHITECTURE 9.4).
- CPU time needs a quiet host to be sharp. The tool warns when the load average is above 8 or when
  the runs of one entry differ by more than the threshold, but it cannot remove the noise; the
  minimum over three runs is the mitigation.

## Deviations from ARCHITECTURE 9.4

- The rule is on **wall clock with a 5 % threshold** (owner note 12: warm wall clock where the entry
  has passes; it was CPU time with 3 % under owner note 10), not "+1 % instructions". CPU time and
  instructions are recorded and shown.
- The history is `work/perf-history/CORPUS.jsonl` as specified; `scripts/dev.sh track CORPUS` is not
  added here (`scripts/` belongs to the steward; it should call `bench/track/track.py CORPUS "$@"`).
