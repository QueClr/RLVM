# RLVM - Rabdos Lean VM

RLVM is a checker for the Lean 4 kernel: it reads a [lean4export](https://github.com/leanprover/lean4export)
NDJSON file and accepts, rejects or declines it. It is written in Java 25 for GraalVM Community Edition and
builds two launchers from the same jars, a JVM launcher and a native image. It has two engines in one binary:

- the **reference engine**, a port of the official kernel's algorithm to hash-consed terms; it is the default
  engine today, it confirms every rejection and it decides every declaration the other engine declines;
- the **machine**, a worklist VM whose instructions are the judgments of the kernel algorithm, with the
  environment's constants elaborated into code for it; it is the fast engine, opt-in (`-X engine=machine`)
  until its integrator makes it the default;
- a Truffle tier for the machine's code is in the build and is off.

**Status: a research prototype.** It is developed and measured by coding agents under the direction of the
project owner (the working notes are in `journal/`). What is gated and what is not is said below; read it
before relying on a verdict.

## What the default configuration promises

The policy of the default configuration is "practical" (`docs/design/OWNER-NOTES.md`, note 13):

- (a) it never accepts a statement that is not derivable in Lean's type theory;
- (b) it gives the official verdict on every real input: what lean4export writes, the Lean Kernel Arena's good and bad tests, the mutant corpora;
- (c) everything the official checker accepts is accepted (a rejection is confirmed by the reference engine, a decline goes to it);
- (d) a derivable statement that the official checker rejects only through its own incompleteness, non-transitivity, cache order or object identity may be accepted; the known classes are in `docs/design/review/official-divergence.md`.

One option selects the policy: `-X policy=practical` (the default) or `-X policy=strict`. Strict checks with
the reference engine alone and declines input whose official verdict depends on the official implementation's
object identity (a metadata node around a term, for example) instead of accepting it.

What stands behind this today (2026-11-24, reference engine as default; `docs/PLATFORM.md` section 0.7):

- gated: the 230 published arena tests (230 correct, none declined); 17,775 mutants of them against the stored
  verdicts of the official checker (no accepted bad mutant, no rejected good one; 1,683 declined); the arena's
  complete test set of 270 by the arena's own procedure (no wrong verdict; two `either` tests on which the
  official kernel does not terminate are declined); Init, Std, cedar, reachability, con-leche,
  navier-stokes-euler, cslib and mathlib accepted; the verdict does not depend on the number of workers or on
  how loading and checking interleave (the driver's invariance suite);
- not established: (a) is supported by tests and adversarial review, not by a proof; the machine has open
  review findings and is not the default; nothing was ever run on x86-64 (the arena's architecture; the
  build is prepared for it); the checker definition for the arena (`scripts/arena/rlvm.yaml`) is a draft that
  has not been submitted.

## Build

Linux, x86-64 or aarch64; `curl`, `tar`, a C compiler and zlib headers for the native image.

```sh
scripts/fetch-toolchain.sh        # GraalVM Community Edition 25 and Maven into .toolchain/ (sha256 / sha512 pinned)
. ./env.sh                        # JAVA_HOME, MVN: nothing outside the checkout is touched (no ~/.m2)

$MVN test                         # every module and its unit tests (the first time online)

scripts/build.sh --online --jvm-only    # all modules from the tree -> work/dist/bin/rlvm-jvm (about half a minute)
scripts/build.sh --online               # the same plus the CE native image work/dist/bin/rlvm (a few minutes)
scripts/arena/build.sh                  # what the arena definition runs: toolchain, build, image, verification
```

`--online` lets Maven download the dependencies; without it the build is offline. For daily work the team
uses `scripts/dev.sh dist` (JVM launcher) and `scripts/dev.sh dist-native` (plus the image): they compile the
wiring module from the tree and take every other module as its owner last installed it into `.toolchain/m2`
(`$MVN -pl MODULE install`), so they need those jars first (`$MVN install -DskipTests` once). `scripts/dev.sh
--help` lists the rest (tests per module, the gate, measurements). Oracle GraalVM is never downloaded or used
by any default build.

## Run

```sh
work/dist/bin/rlvm EXPORT.ndjson          # native image: the cold platform (one process per export)
work/dist/bin/rlvm-jvm EXPORT.ndjson      # JVM launcher: the warm platform (steady-state speed)
```

| Exit status | Meaning |
|--:|---|
| 0 | accepted: the whole export was read and every declaration checked |
| 1 | rejected (a rejection is reported only after the reference engine has confirmed it) |
| 2 | declined: input RLVM cannot represent or decide exactly; never a guess |
| 3 | internal error (an exception, out of memory, a stack overflow on any thread) |
| 64 | usage error (a bad option, an unknown `-X` setting) |

Options (`rlvm --help` has all of them and the checker's settings):

| Option | Meaning |
|---|---|
| `--threads N` / `auto` | worker threads; `auto` (the default) is one per CPU, at most 8 |
| `-X engine=reference` / `machine` | the engine; default: by the engines' priorities, printed by `rlvm --version` |
| `-X policy=practical` / `strict` | the policy above |
| `-X passes=N` | check the export N times in this process and report every pass (warm wall clock) |
| `-X load=auto` / `batch` / `pipelined` | default `auto`: the file is checked while it is read when two or more workers run and it has 8 MiB or more |
| `--stats` | one statistics line on standard error |

The export can also be given in the environment variable `IN`, as the Lean Kernel Arena does.

## Numbers

`docs/BENCHMARKS.md` has one table per metric in the owner's order - warm wall clock with 8 workers, warm
wall clock with 1 worker, cold wall clock, CPU time, peak RSS, instructions - and says for every cell which
jars and which measurement window. The target is the warm wall clock: the time of a pass of a process that
checks the same export again, with parallel workers and the load pipelined (`docs/design/OWNER-NOTES.md`,
note 12). One table from it:

<!-- table: work/b2/readme-table.py, from the rows of docs/BENCHMARKS.md table 1 -->
| Warm wall clock, 8 workers (s) | init (348 MB) | std (584 MB) | cedar (905 MB) | mathlib (6.33 GB) |
|---|--:|--:|--:|--:|
| RLVM, default configuration (reference engine) | 1.68 (1.56) | 2.58 (2.54) | 3.17 (3.10) | 58.0 (58.0) |
| RLVM, `-X engine=machine` | 0.62 (0.61) | 1.22 (1.14) | 1.61 (1.54) | 21.2 (21.2) |
| gruszka | 0.71 (0.71) | 1.28 (1.28) | 1.73 (1.73) | 14.2 (14.2) |
| nano-lean | 1.03 (1.03) | 1.74 (1.74) | 2.64 (2.64) | 18.7 (18.7) |
| *default configuration (reference engine) / gruszka* | 2.37x | 2.02x | 1.83x | 4.09x |
| *-X engine=machine / gruszka* | 0.87x | 0.95x | 0.93x | 1.50x |
<!-- end table -->

Conditions: one host (aarch64, Cortex-X925), 8 cores reserved for one measurement at a time while the other
12 cores were busy, 2026-11-24; RLVM on its JVM launcher, 8 workers, median of the later passes of a process
with 6 to 10 passes (best pass in brackets; mathlib: the second of two passes); gruszka and nano-lean with
their arena run lines and 8 threads on the same cores, second of two runs. Single measurement windows: two
identical rows differ by up to 5 %. Not the arena's metric (instructions of a cold process) and not its architecture (x86-64). Which
launcher is for what, and why: `docs/PLATFORM.md`.

## Documents

| Where | What |
|---|---|
| `docs/design/` | the owner's notes (`OWNER-NOTES.md`: the highest authority), the architecture, the instruction set of the machine, the value model, the reviews (`review/`: soundness, the divergences from the official checker) |
| `docs/research/` | the studies behind the design: the arena and the export format, the official kernel, the leading checkers, GraalVM as a platform, memory and parallelism, the baselines measured here, the workloads |
| `docs/research/profiles/` | the profiler reports: where the time goes, the JVM as the warm platform, collectors, the loader, the arena's own procedure |
| `docs/PLATFORM.md`, `docs/BENCHMARKS.md` | which launcher is for what, the flags and defaults with their measurements; the current tables |
| `<module>/README.md` | every module's own description: `rlvm-main` (the wiring and all `-X` settings), `rlvm-driver`, `rlvm-load`, `rlvm-ref`, `rlvm-machine`, ... |
| `journal/` | the working journal of the agents: decisions, findings, open problems, a dated changelog |

## Licence

The project is dual-licensed:

- Apache License, Version 2.0
- MIT License

You may use the project under either license. The full license text is in [LICENSE](LICENSE).

The journal under `journal/` is documentation and is licensed separately under the GNU Free Documentation
License, Version 1.3 or later ([journal/LICENSE](journal/LICENSE)).
