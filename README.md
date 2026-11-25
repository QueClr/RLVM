# RLVM - Rabdos Lean VM

RLVM is a checker for the Lean 4 kernel: it reads a [lean4export](https://github.com/leanprover/lean4export)
NDJSON file and accepts, rejects or declines it. It is **one executable**, `rlvm`: a native image built with
GraalVM Community Edition from Java 25 sources, with no JDK and no other file needed to run it.

```sh
rlvm EXPORT.ndjson        # exit status 0 = accepted, 1 = rejected, 2 = declined
```

Two engines are in the executable:

- the **machine**, a worklist VM whose instructions are the judgments of the kernel algorithm (type inference,
  weak-head normalisation, definitional equality), with the environment's constants elaborated into code for
  it; it is the fast engine;
- the **reference engine**, a port of the official kernel's algorithm to hash-consed terms; it confirms every
  rejection, decides every declaration the machine declines, and alone it is the strict mode.

A plain run checks with the machine (`rlvm --version` prints the default engine and its switch set); the
reference engine re-checks every rejection and decides what the machine declines. `-X engine=reference` or
`-X policy=strict` checks with the reference engine alone.

**Status: a research prototype.** It is developed and measured by coding agents under the direction of the
maintainer; the design notes, decisions, measurements and the changelog are the journal
(<https://queclr.github.io/RLVM/>, sources in `journal/`). What is gated and what is not is said below; read it
before relying on a verdict.

## Run

A run without options is the fastest configuration, chosen by measurement (*agentic-note*: design-packaging):
`rlvm --version` prints how the executable was built and what a plain run does - the engine, one worker per
CPU up to 8, the file checked while it is read, the heap settings baked in.

| Exit status | Meaning |
|--:|---|
| 0 | accepted: the whole export was read and every declaration checked |
| 1 | rejected (a rejection is reported only after the reference engine has confirmed it) |
| 2 | declined: input RLVM cannot represent or decide exactly; never a guess |
| 3 | internal error (an exception, out of memory, a stack overflow on any thread) |
| 64 | usage error (a bad option, an unknown `-X` setting) |

| Option | Meaning |
|---|---|
| `--threads N` | worker threads (default `auto`: one per CPU, at most 8) |
| `-X policy=strict` | the strict policy (below); default `practical` |
| `-X engine=machine` / `reference` | name the engine instead of taking the default one |
| `-X passes=N` | check the export N times in one process and report every pass (warm wall clock) |
| `--stats` | one statistics line on standard error |
| `-Xmx8g` | the executable's own memory options, written without a blank: maximum heap (also `-Xms`, `-Xmn`) |

`rlvm --help` lists all of them. The export can also be given in the environment variable `IN`, as the Lean
Kernel Arena does.

## What the default configuration promises

The policy of the default configuration is "practical" (the maintainer's standing policies;
*agentic-note*: standing-policies):

- (a) it never accepts a statement that is not derivable in Lean's type theory;
- (b) it gives the official verdict on every real input: what lean4export writes, the Lean Kernel Arena's good and bad tests, the mutant corpora;
- (c) everything the official checker accepts is accepted (a rejection is confirmed by the reference engine, a decline goes to it);
- (d) a derivable statement that the official checker rejects only through its own incompleteness, non-transitivity, cache order or object identity may be accepted (*agentic-note*: design-official-divergence).

`-X policy=strict` checks with the reference engine alone and declines input whose official verdict depends
on the official implementation's object identity (a metadata node around a term, for example) instead of
accepting it.

What stands behind this today (2026-11-25, the machine as default engine; *agentic-note*: design-gate):

- gated on the launcher without any option, through the jars and through the executable: the 230 published arena
  tests (230 correct, none declined); 17,775 mutants of them against the stored verdicts of the official checker
  (no accepted bad mutant, no rejected good one; agree 15,741, declined 2,034); 1,196 hostile exports with their
  official verdicts (no wrong accept, no wrong reject; agree 1,124, 68 accepts of kind (d), 4 declined); Init,
  Std, cedar, reachability, con-leche, navier-stokes-euler, cslib and mathlib accepted; the verdict does not
  depend on the number of workers or on how loading and checking interleave;
- not established: (a) is supported by tests and adversarial review, not by a proof; the arena's complete test
  set of 270 by the arena's own procedure was run on earlier builds only (no wrong verdict there; two `either`
  tests on which the official kernel does not terminate are declined); on x86-64 (the arena's architecture) only
  the unit tests have run (continuous integration): the executable, the corpora and the release workflow have
  never run there; no release has been published; the checker definition for the arena
  (`scripts/arena/rlvm.yaml`) is a draft that has not been submitted.

## Get it

A release is one archive per architecture, `rlvm-<version>-linux-<arch>.tar.gz`, with exactly three files:
`rlvm`, `LICENSE` and this `README.md`. Unpack it and run `rlvm`; it needs a Linux with glibc and zlib.

To build it (Linux, x86-64 or aarch64; `curl`, `tar`, a C compiler and the zlib headers):

```sh
scripts/fetch-toolchain.sh     # GraalVM Community Edition 25 and Maven into .toolchain/ (checksum-pinned)
scripts/build.sh --online      # -> work/dist/bin/rlvm, the executable (a few minutes)
scripts/package.sh             # -> work/release/rlvm-<version>-linux-<arch>.tar.gz
```

Nothing outside the checkout is touched (no `~/.m2`); `--online` lets Maven download the dependencies the
first time.

An optional build uses **Oracle GraalVM** and profile-guided optimisation from the profiles in `profiles/`
(recorded as the last step of a release; `scripts/dev.sh profiles` records them): `scripts/build.sh --oracle-pgo`.
Measured with the profiles of the repository, that executable needs 0.85 to 0.89 times the default executable's
wall clock on the eight corpora.
Oracle GraalVM is not open source: it is licensed under the GraalVM Free Terms and Conditions, the option says
so, and it downloads Oracle GraalVM only when `--accept-oracle-licence` is given too. The default build, the
release workflow and CI use GraalVM Community Edition only.

For development: `. ./env.sh && $MVN test` runs the unit tests of every module, and `scripts/dev.sh --help`
lists the rest (development distributions that also run the jars on a JVM, the gates, the benchmarks).

## Numbers

<!-- table: bench/track/benchmarks.py --headline (the rows of bench/warm.sh; *agentic-note*: rep-benchmarks) -->
| One run, 8 cores: wall clock in s (warm pass) | init (348 MB) | std (584 MB) | cedar (905 MB) | reachability (19 MB) | con-leche (702 MB) | navier-stokes-euler (1.56 GB) | cslib (2.50 GB) | mathlib (6.33 GB) |
|---|--:|--:|--:|--:|--:|--:|--:|--:|
| **`rlvm FILE`** (the executable, default configuration: machine engine) | 0.74 (0.71) | 1.33 (1.21) | 1.86 (1.81) | 0.63 (0.56) | 1.78 (1.65) | 4.41 (4.26) | 6.09 (5.72) | 16.31 (15.46) |
| `rlvm -X engine=reference FILE` | 2.07 (1.96) | 3.53 (3.44) | 4.60 (4.43) | 4.94 (4.63) | 39.15 | 27.71 | 18.93 | 85.76 |
| gruszka | 0.71 (0.71) | 1.40 (1.28) | 2.74 (1.73) | 2.38 (2.42) | 1.36 (1.22) | 2.86 (3.11) | 4.77 (4.63) | 13.28 (14.20) |
| nano-lean | 1.05 (1.03) | 1.75 (1.74) | 2.65 (2.64) | 0.90 (0.75) | 2.20 (2.16) | 4.13 (4.14) | 6.52 (6.48) | 18.68 (18.66) |
| *rlvm / gruszka* | 1.04x | 0.95x | 0.68x | 0.26x | 1.31x | 1.54x | 1.28x | 1.23x |
| *rlvm / nano-lean* | 0.70x | 0.76x | 0.70x | 0.70x | 0.81x | 1.07x | 0.93x | 0.87x |
<!-- end table -->

Conditions: one host (aarch64, Cortex-X925), 8 cores reserved for one measurement at a time while the other 12
cores were busy, 2026-11-25 (four of the baseline columns 2026-11-24); each figure is one fresh process (8 workers, the file
checked while it is read), in brackets the median later pass of one process that checks the export several times.
The first row is the executable without any option; the second is the reference engine alone; gruszka and
nano-lean with their arena run lines and 8 threads on the same cores, first (second) of two runs; "-" = not
measured. Two identical rows differ by up to 5 %. Not the arena's metric (instructions) and not its architecture
(x86-64). All tables, per metric and with the provenance of every cell: the journal (*agentic-note*:
rep-benchmarks); how the settings of a plain run were chosen: *agentic-note*: dec-EGMG.

## Where things are

| Where | What |
|---|---|
| `journal/` (<https://queclr.github.io/RLVM/>) | every note of the project: the maintainer's standing policies, design notes, decisions, measured findings, open problems, the changelog |
| `<module>/README.md` | what a module is and how to build and test it: `rlvm-main` (the wiring and all `-X` settings), `rlvm-driver`, `rlvm-load`, `rlvm-ref`, `rlvm-machine`, ... |
| `scripts/`, `bench/` | the build, the package, the gates; the measurement tools (`bench/README.md`) |
| `docs/` | documents from before the journal; they are being moved into it |

## Licence

The project is dual-licensed:

- Apache License, Version 2.0
- MIT License

You may use the project under either license. The full license text is in [LICENSE](LICENSE).

The journal under `journal/` is documentation and is licensed separately under the GNU Free Documentation
License, Version 1.3 or later ([journal/LICENSE](journal/LICENSE)).
