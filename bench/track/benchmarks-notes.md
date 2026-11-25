## What the numbers are

- **Order (OWNER-NOTES 12)**: warm wall clock with 8 workers and the load pipelined, warm wall clock with 1 worker,
  cold wall clock of a fresh process (for information), CPU time, peak RSS, instructions. One table per metric;
  in every table **the first rows are the default configuration** - what `rlvm FILE` does with no option.
- **The engines, and the contract of the default configuration (OWNER-NOTES 13, policy "practical").** RLVM has two
  engines in one binary. The **reference engine** (`rlvm-ref`: the official kernel's algorithm on hash-consed terms)
  is the default engine today, the engine that confirms every rejection and decides every unit the other engine
  declines, and alone it is the strict mode (`-X policy=strict`). The **machine** (`rlvm-machine`: the worklist
  machine, the engine the project is named after) runs with `-X engine=machine`; it becomes the default engine when
  its gates and its adversarial review are clean (its provider's priority decides, nothing in the launcher), and
  the default rows of these tables are then its rows (`bench/track/benchmarks.py --default-engine machine`).
  Whatever engine is the default, the default configuration promises:
  - (a) never an accept of a statement that is not derivable in Lean's type theory;
  - (b) the official verdict on every real input: what lean4export writes (the corpora), the arena's good and bad tests, the mutant corpora;
  - (c) everything the official checker accepts is accepted (a rejection is confirmed by the reference engine, a decline goes to it);
  - (d) a derivable statement that the official checker rejects only through its own incompleteness, non-transitivity, cache order or object identity may be accepted (`docs/design/review/official-divergence.md`).
- **Platforms.** The release is ONE executable (*agentic-note*: design-packaging): **native image** = `bin/rlvm`, built
  with GraalVM CE at `-O3` with the serial collector and the heap settings its `rlvm --version` prints; it is what a
  user runs and what the arena definition runs, and its rows are the ones that count for a release. **JVM launcher**
  = `bin/rlvm-jvm` of a development distribution (HotSpot of GraalVM CE 25, C2, serial collector, 8 compiler
  threads, transparent huge pages; heap by engine: machine 7 GB, others 13 GB): the same jars on a JIT, the
  platform on which the warm metric was first measured and a tool of the repository, not part of a release.
- **Configuration of every RLVM row**: `--threads 8` (what `--threads auto` chooses on 8 cores) or `--threads 1`; the
  load and the reader as the build decides them (pipelined for two or more workers, two scanner threads for these
  file sizes); the machine with its default switch set. Heap below mathlib: 6 GB (`-Xmx6g -Xmn1g` for the machine on
  the JVM, `-Xms6g -Xmx6g -Xmn1g` for the reference engine, `-Xmx6g` for the image: the host's memory rule; it is a
  cap, none of these runs comes near it); on mathlib each launcher's own default.
- **Where**: this host (aarch64, Cortex-X925), the 8 measurement cores, every row inside a `bench/quiet.py` window
  (one measurement at a time; the other 12 cores were busy, load average 25 to 40, which costs memory bandwidth).
  RLVM runs on the host, gruszka and nano-lean in the arena container on the same 8 cores with their arena run
  lines and 8 (or 1) threads. Two identical 8-worker rows of one window differ by up to 5 %: smaller differences
  are not findings. The arena itself runs on x86-64: absolute numbers differ.
- **warm** = the same process checks the same export again (`rlvm -X passes=N`); every pass is a complete run: new
  store, new loader, new engines, the load pipelined with the checking. The JVM's first pass is its warm-up and is
  reported in table 3 and in the provenance, not charged in tables 1 and 2.

<!-- tables -->

## Superseded tables

The tables this document had until 2026-11-24T21:01Z are superseded and no longer printed here:

- the COLD tables per corpus (CPU time first, wall clock, RSS, instructions of one fresh process on a BATCH load in
  the arena-shaped container, native image, 8 workers; machine jar `78c74ca92785` of the wave-6 integration, the
  loader before packed runs, 17 corpora incl. the `perf/` tests and the official checker): they are regenerated on
  demand from the tracker's history by `bench/track/benchmarks_cold.py --stdout`; the same table is in
  `work/status/machine-integration.md`, "Wave 6";
- the warm tables of `bench/track/warm-wall-clock.md` (the wave-6 integrator's rows on `work/int6/dist-b`, windows of
  20:04Z to 20:30Z, and the first warm table of 17:37Z, in full in `work/status/warm.md`): older jars, the launcher
  line before 2026-11-24T22Z, `-X load=pipelined` given by hand.

Not re-measured in this round, so still only there: reachability, the Cedar slice, con-leche, navier-stokes-euler,
cslib, the evaluation-heavy `perf/` tests, the official checker, and one worker on mathlib.
