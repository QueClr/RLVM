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

Which engine a plain run checks with is decided by the engines themselves and printed by `rlvm --version`.
Today it is the reference engine: the machine runs with `-X engine=machine` until its integrator publishes it
as the default engine (it needs a half to a fifth of the reference engine's wall clock on the library corpora).

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

What stands behind this today (2026-11-25, reference engine as default; *agentic-note*: design-gate):

- gated, through the executable and through the jars: the 230 published arena tests (230 correct, none
  declined); 17,775 mutants of them against the stored verdicts of the official checker (no accepted bad mutant,
  no rejected good one; 1,683 declined); 1,189 hostile exports with their official verdicts (no wrong accept, no
  wrong reject; 6 accepts of kind (d), 4 declined). Earlier builds: the arena's complete test set of 270 by the
  arena's own procedure (no wrong verdict; two `either` tests on which the official kernel does not terminate
  are declined); Init, Std, cedar, reachability, con-leche, navier-stokes-euler, cslib and mathlib accepted; the
  verdict does not depend on the number of workers or on how loading and checking interleave;
- not established: (a) is supported by tests and adversarial review, not by a proof; the machine has open review
  findings and is not the default engine yet; a hand-made export with a metadata node in the function position of
  a redex is rejected where the official checker accepts it (a repair is in progress); nothing was ever run on
  x86-64 (the arena's architecture; the build and the release workflow are prepared for it and untested there);
  no release has been published; the checker definition for the arena (`scripts/arena/rlvm.yaml`) is a draft that
  has not been submitted.

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

An optional build uses **Oracle GraalVM** and profile-guided optimisation from the profiles shipped in
`profiles/`: `scripts/build.sh --oracle-pgo` (measured on this project's corpora: 11 to 15 % fewer instructions and 7 to 16 % less wall clock than the default executable). Oracle GraalVM is not open source: it is licensed
under the GraalVM Free Terms and Conditions, the option says so, and it downloads Oracle GraalVM only when
`--accept-oracle-licence` is given too. The default build, the release workflow and CI use GraalVM Community
Edition only.

For development: `. ./env.sh && $MVN test` runs the unit tests of every module, and `scripts/dev.sh --help`
lists the rest (development distributions that also run the jars on a JVM, the gates, the benchmarks).

## Numbers

<!-- table: bench/track/benchmarks.py --headline (the rows of bench/warm.sh; *agentic-note*: dec-EGMG) -->
| One run, 8 cores: wall clock in s (warm pass) | init (348 MB) | std (584 MB) | cedar (905 MB) | reachability (19 MB) | mathlib (6.33 GB) |
|---|--:|--:|--:|--:|--:|
| **`rlvm FILE`** (the executable, default configuration: reference engine) | 2.10 (1.98) | 3.56 (3.38) | 4.51 (4.41) | 5.10 (4.74) | 92.00 (91.76) |
| `rlvm -X engine=machine FILE` | 0.82 (0.83) | 1.59 (1.50) | 2.16 (2.10) | - | 27.59 (28.18) |
| gruszka | 0.71 (0.71) | 1.40 (1.28) | 2.74 (1.73) | - | 13.28 (14.20) |
| nano-lean | 1.05 (1.03) | 1.75 (1.74) | 2.65 (2.64) | - | 18.68 (18.66) |
| *rlvm / gruszka* | 2.96x | 2.54x | 1.65x | - | 6.93x |
| *rlvm / nano-lean* | 2.00x | 2.03x | 1.70x | - | 4.93x |
<!-- end table -->

Conditions: one host (aarch64, Cortex-X925), 8 cores reserved for one measurement at a time while the other 12
cores were busy, 2026-11-24 and 2026-11-25; each figure is one fresh process of the executable without any option
(8 workers, the file checked while it is read), in brackets the median later pass of one process that checks the
export several times; gruszka and nano-lean with their arena run lines and 8 threads on the same cores, first
(second) of two runs. Two identical rows differ by up to 5 %. Not the arena's metric (instructions) and not its
architecture (x86-64). All tables, per metric and with the provenance of every cell: the journal (*agentic-note*: dec-EGMG, and
`docs/BENCHMARKS.md` until it has moved there).

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
