# AR1 - The arena's own procedure on today's published jars, and where RLVM would stand on the board

Agent AR1, 2026-11-24, 18:28Z - 19:40Z. No module code was changed or published. Everything of mine is under
`work/ar1/` (distributions, reports, logs, scripts); living summary `work/status/prof-AR1.md`.

**Framing (owner note 12).** The arena ranks by user-space instructions on Mathlib. That is the arena's metric,
not the project's: the owner's target is warm wall clock (`work/status/warm.md`). This report says where the
published code stands by the arena's rule and what the rule would reward; it is information, not a target.
Instruction counts are exact and repeat to 0.2 %; every wall-clock and CPU figure that is quoted as evidence was
taken inside a `bench/quiet.py` window and is marked as such.

## 0. Tables first

### 0.1 Verdicts: the complete arena test set (270 tests: 148 good incl. the 8 corpora, 92 bad, 30 either)

One arena-shaped container per configuration (8 performance cores, 16 GB, no network), tests one after the
other, the arena's time limit per test, `perf stat` + GNU time around the run line (`bench/run-suite.py --perf
--arena-timeouts`).

| configuration (run line = `scripts/arena/rlvm.yaml` + the options shown) | good: accepted / rejected / declined / error / timeout | bad: rejected / accepted / declined | either: accepted / rejected / declined | **wrong accepts** | **wrong rejects** | declined + error + timeout |
|---|---|---|---|--:|--:|--:|
| **(a)** as it is today: **reference engine, `--threads 4`** | 148 / 0 / 0 / 0 / 0 | 92 / 0 / 0 | 9 / 19 / 2 | **0** | **0** | 2 |
| **(b)** `-X engine=machine` (default switches `warm,gated,expect`) | 147 / 0 / 0 / 0 / **1** | 92 / 0 / 0 | 9 / 19 / 2 | **0** | **0** | 3 |
| **(c\*)** `-X engine=machine -X machine.switches=gated,pchk,expect,warm,code,casetrees,bodies,directrules,same,match,joint,lazycases,lazyeval` (INT6's CANDIDATE default; see the note) | @@C_GOOD@@ | @@C_BAD@@ | @@C_EITHER@@ | **@@C_WA@@** | **@@C_WR@@** | @@C_DECL@@ |
| (d) extra, NOT official-exact: `-X engine=machine -X machine.switches=default,facts,probes -Drlvm.machine.m4=congruence,skipargs` | 148 / 0 / 0 / 0 / 0 | 92 / 0 / 0 | 11 / 17 / 2 | **0** | **0** | 2 |

Every test that is not "correct" or a verdict on an `either` test, by name:

| configuration | test | expected | got | cause |
|---|---|---|---|---|
| (a), (b), (c\*), (d) | `corner-cases/eta-struct-major-no-whnf`, `corner-cases/eta-struct-major-and-no-whnf` | either | declined (exit 2) | `BUDGET_STEPS ...: the reduction returns to a term it started from; the official kernel does not terminate here` (the published `official` row is a timeout on both). (a): 5 and 17 ms, 0.01 G instructions. (b), (c\*), (d): 0.88 s and 24.0 G each - the machine first runs into its own bound of 2^27 ticks per unit, then the reference engine declines |
| (b) | `perf/loop-unfolded-once` | accept | **timeout** in the suite run (killed at the arena's 40 s); **accepted in 31.6 s** when repeated alone in a quiet window (199.2 G instructions, 31.3 s CPU, **8,686 MiB**) | not a wrong verdict. The machine works on one unit until its own tick bound cuts it, then the reference engine accepts the file (the reference engine alone: 76 ms, 1.5 G, 60 MiB; with the shortcuts of (d): 58 ms, 0.85 G). 31.6 s of a 40 s limit on this host's cores; the arena's runner is slower (nano-lean's Mathlib wall: 29.5 s here, 36.8 s published; gruszka 20.0 s and 32.5 s), so **on the arena this test is a likely timeout with the machine as it is** |
@@C_ROWS@@

No wrong accept and no wrong reject in any configuration. With (a) RLVM gives the published verdict of `official`
(v4.35.0) on 268 of the 270 tests; the two others are the non-terminating `either` files above.

Note on (c\*): at the time of the runs (19:14Z) the integrator's log (`work/status/machine-INT6.log`, entry of
19:06:35Z) calls NO switch set beyond the default "gated": it reports a candidate default with hostile exports
and mutants clean, the full gate failing only on a harness artefact and re-gated on cedar + Std. Strictly, (c)
is therefore skipped; (c\*) is that candidate, run so that its arena figure exists when the integrator decides.
It is not a gated configuration.

### 0.2 Mathlib: instructions, wall clock, CPU time, peak RSS - RLVM and the leaders, same container, same session

One fresh arena-shaped container per run, `bench/measure.sh` (= the arena's `perf stat ... time ... sh -c RUN`).
All rows inside a `bench/quiet.py` window unless marked. "second sample" = the Mathlib run inside the suite of
0.1 (container parked on two cores during other agents' windows: its times are not evidence, its count is).

| checker / configuration | status | **instructions** | virtual s (/6e9) | x nano-lean | wall s | CPU s | peak RSS MiB | second sample |
|---|---|--:|--:|--:|--:|--:|--:|--:|
| RLVM (a) reference engine, 4 workers | accepted | **8,427.1 G** | 1,404.5 | 23.33 | 177.7 | 622.4 | 5,513 | 8,408.3 G (-0.22 %) |
| RLVM (b) machine `warm,gated,expect`, 4 workers | accepted | **2,978.5 G** | 496.4 | 8.25 | 61.3 | 205.1 | 2,886 | 2,980.5 G (+0.07 %) |
| RLVM (c\*) machine, INT6's candidate set, 4 workers | @@C_ML_STATUS@@ | **@@C_ML_INSTR@@** | @@C_ML_VIRT@@ | @@C_ML_X@@ | @@C_ML_WALL@@ | @@C_ML_CPU@@ | @@C_ML_RSS@@ | @@C_ML_SECOND@@ |
| RLVM (d) machine + `facts,probes` + `congruence,skipargs` (not official-exact) | accepted | **1,977.0 G** | 329.5 | 5.47 | not quiet | not quiet | 2,921 | 1,976.7 G (-0.02 %) |
| nano-lean v0.4.2 (local build of the arena's definition, 4 threads) | accepted | 361.2 G | 60.2 | 1.00 | 29.5 | 102.0 | 5,157 | published x86-64: 394.9 G; here / published 0.915 |
| gruszka (4 workers) | accepted | 419.1 G | 69.9 | 1.16 | 20.0 | 71.5 | 2,525 | 462.0 G; 0.907 |
| sokonanoda (4 threads) | accepted | 505.6 G | 84.3 | 1.40 | 33.7 | 114.9 | 5,171 | 527.4 G; 0.959 |
| rpylean (1 thread) | accepted | 544.4 G | 90.7 | 1.51 | 125.8 | 125.4 | 4,537 | 578.1 G; 0.942 |

Same machine, earlier today (`bench/baselines.json`, 04:23Z - 05:33Z, not repeated): pestka 1,339.6 G, nanoda
5,533.3 G, tenet-jvm with C2 9,313.0 G, official v4.35.0 11,423.7 G.

### 0.3 Projected board position (published board of `work/arena-site/results.json`: 37 checkers, 21 of them with no wrong verdict and an accepted Mathlib run)

A PROJECTION: RLVM has never been measured on x86-64. Its aarch64 count is divided by the ratio "count here /
published count" of a leader measured in this session; the last two columns are the extremes that any baseline
in the arena's configuration has shown on a big corpus (`docs/research/12-baselines.md` section 3).

| configuration | here | via nano-lean (0.915) | via gruszka (0.907) | via sokonanoda (0.959) | lowest ratio seen (0.90) | highest ratio seen (1.05) | **place** |
|---|--:|--:|--:|--:|--:|--:|--:|
| (a) reference engine | 8,427.1 G | 9,212 G | 9,289 G | 8,791 G | 9,363 G | 8,026 G | **15** in every column (after pestka-wasm 7,015.4 G, before tenet-jvm 9,798.4 G) |
| (b) machine, default switches | 2,978.5 G | 3,256 G | 3,283 G | 3,107 G | 3,309 G | 2,837 G | **8** in every column (after palladium 2,833.0 G, before con-ron 3,473.9 G); at the 1.05 edge it is 0.15 % behind palladium |
| (c\*) machine, candidate set | @@C_ML_INSTR_SHORT@@ | @@C_P1@@ | @@C_P2@@ | @@C_P3@@ | @@C_P4@@ | @@C_P5@@ | **@@C_PLACE@@** |
| (d) machine with the shortcuts | 1,977.0 G | 2,161 G | 2,179 G | 2,062 G | 2,197 G | 1,883 G | **6** in every column (after pestka 1,475.3 G, before kefir 2,577.3 G) |

Board (x86-64 G instructions): 1 nano-lean 394.9, 2 gruszka 462.0, 3 sokonanoda 527.4, 4 rpylean 578.1, 5 pestka
1,475.3, 6 kefir 2,577.3, 7 palladium 2,833.0, 8 con-ron 3,473.9, 9 kefir-wasm 3,496.0, 10 con-agua 3,925.9, 11
nanobruijn 4,704.7, 12 nanoda 5,773.3, 13 con-leche 6,133.9, 14 pestka-wasm 7,015.4, 15 tenet-jvm 9,798.4, 16
official-nightly 9,815.1, 17 eink0rn 10,611.2, 18 official 12,095.3.

What costs places: nothing but the Mathlib count. The sort key is (bad tests accepted, good tests rejected,
Mathlib instructions, declined count); RLVM has 0 / 0 in every configuration, so its place is its Mathlib count,
and the 2 or 3 declines / timeouts only break an exact tie in instructions. A single wrong reject would put
the entry behind all 27 checkers that have neither (place 28), a single wrong accept behind the 29 that
have no wrong accept (place 30), whatever the speed.

### 0.4 What it would take, in instructions (aarch64 counts of this host, whole process, 4 workers)

Places are projected with nano-lean's ratio (0.915); "range" = the 0.90 .. 1.05 extremes. Thresholds in this
host's counts: place 7 below 2,592 G (2,550 .. 2,975), place 6 below 2,358 G (2,320 .. 2,706), place 5 below
pestka's 1,339.6 G measured here, place 4 below rpylean's 544.4 G, place 3 below 505.6 G, place 2 below 419.1 G,
place 1 below 361.2 G.

| # | item | what it is today | instructions saved | basis | total after it | projected place |
|--:|---|---|--:|---|--:|---|
| 0 | start: the definition as it is, (a) | reference engine checks everything | - | measured | 8,427.1 G | 15 |
| 1 | the machine becomes the default engine (provider priority >= 100; today 10) | (b) | **-5,448.6 G** (-64.7 %) | measured | 2,978.5 G | **8** |
| 2 | INT6's candidate switch set instead of `warm,gated,expect` | (c\*) | @@C_SAVED@@ | measured | @@C_ML_INSTR_SHORT@@ | @@C_PLACE_SHORT@@ |
| 3 | units that enter eager reduction stay on the machine (prototype E1) | 6,711 units (0.94 %) are declined twice by the machine and checked by the reference engine: about 290 - 350 G of (b) are reference-engine work on worker threads (section 6.3), plus about 15 G of abandoned machine work (P8) | about -150 .. -210 G | estimate: fallback minus what the machine would need at its own ratio to gruszka on those units (6.5 x 23.9 G = 155 G) | about 2,770 - 2,830 G | 8 (7 only if RLVM's ratio is 0.98 or more) |
| 4 | proof-comparison shortcuts `congruence,skipargs` (+ `facts,probes`), owner's decision, review S2 says theory-sound, NOT the official algorithm | (d) | **-1,001.5 G** against (b) (-33.6 %) | measured | 1,977.0 G | **6** |
| 5 | the same official-exact (prototype C1) | unknown share of item 4 | 0 .. -1,000 G | not built | - | 8 .. 6 |
| 6 | loader at the leaders' cost | load 133.2 G = 21 instructions per byte; leaders 37 - 41 G = 6 per byte | up to -93 G | measured bound | - | no place by itself |
| 7 | admission (environment pass) | 25.1 G; inductive types cost the leaders under 3 G | up to -22 G | measured bound | - | none |
| 8 | garbage collection | 0.6 G in (b), 8.7 G in (a): 0.0 % and 0.1 % | 0 | measured (profile) | - | none |
| 9 | items 1 + 2 + 3 + 6 + 7 together (everything official-exact that exists or is being prototyped) | - | - | sum of the above | about 2,420 - 2,480 G | **7** (below the 2,592 G of palladium; 6 only at the 1.05 edge of the range) |
| 10 | items 1 + 3 + 4 + 6 + 7 (+ the part of item 2 that survives the shortcuts) | - | - | sum | about 1,500 - 1,650 G | **6**; place 5 needs 1,339.6 G: another 160 - 310 G (10 - 19 %) that nothing in sight provides |
| 11 | place 4 (rpylean: 544.4 G here) | with a leader-class loader and admission (43 G) the checking would have to cost 500 G = 1.3 x gruszka's 381 G; the machine's checking is 2,820 G in (b) (7.4 x) and 1,819 G in (d) (4.8 x) | about -1,000 G after row 10 | - | 544 G | 4: needs the cost per machine operation (2,133 instructions per tick against gruszka's 823, 1.5 x its ticks; P8 section 4.6) to fall by a factor of about 3 ON TOP of the shortcuts |

Reading: making the machine the default is worth seven places (15 -> 8). After that the only single step
that moves a place is the shortcut policy (8 -> 6); everything official-exact that is in sight is worth
at most one place (8 -> 7, and only in part of the projection range). Places 5 and better are not reachable by
switching on anything that exists: they need the per-operation cost.

### 0.5 The arena definition (`scripts/arena/rlvm.yaml`): what the integrator has to update

Checked, fine: valid against the arena's schema; the run line works under `sh -c` (dash) with `$IN` and maps
10 / 11 / 12 to 0 / 1 / 2 (exercised 270 x 4 times here); `threads: 4` = `--threads 4`; `version:
"0.1.0-SNAPSHOT"` = the first line of `rlvm --version`; the build uses GraalVM CE only (`--no-pgo`; builder
banner "GraalVM CE 25.5.5.0.3+1.1"; the cached tarball has the pinned sha256 `41741c06...`); the eight
third-party jars on the image class path match `scripts/arena/deps.sha256` in both builds of this session; the
`build:` line run in a clean copy of the tree **without network** succeeds (section 8).

To do before submission:

1. `url`, `ref`, `rev` are placeholders (`https://example.invalid/rlvm.git`, `main`, `"0000000"`): set them to the
   public repository, its branch and the full commit hash. The arena fetches exactly that commit with depth 1,
   so the commit must contain `pom.xml`, `env.sh`, `scripts/` and all 15 modules.
2. **The arena build is a tree build and puts `rlvm-testkit` on the image class path** (23 jars), which no
   distribution that anybody gates or measures has (`scripts/build.sh --published`: 22 jars). The image still
   lists the engines `machine, reference*` and gives the right verdicts, but it is not the gated binary. Either
   leave the test kit out of the arena image or gate a tree-built image.
3. The header comment is stale: it describes the wave-5 state (14 modules; "nothing is checked with [the
   machine] yet" is still true, but the machine's default switch set, its own tick bound, the eager-reduction
   fallback, `rlvm-truffle` in the parent pom since 18:16Z and the versioned `work/dist` link are not in it).
   "All 230 published tests correct (0 declined)" should read: 270 of 270 without a wrong verdict, 2 `either`
   tests declined.
4. When the machine becomes the default engine: rewrite the second paragraph of `description` (it says the
   reference engine checks every declaration), say that units the machine declines are checked by the reference
   engine, and first settle `perf/loop-unfolded-once` (0.1: 31.6 s of a 40 s limit here, 8.7 GB) - as it is,
   the switch trades a correct verdict for a probable timeout on the arena's slower runner.
5. Not pinned by hash: the Maven plugins and their dependencies (an 88 MB local repository; only
   `--strict-checksums` against the repository's own checksum files). The description's sentence "verifies their
   checksums and those of the third-party jars that go into the image" is accurate, but the build is not
   hermetic. If that matters, add a hash list for the plugin set or vendor the repository.
6. Decide M6's request of 17:12Z (H10 inline lists in the native-image build, `rlvm-machine/h10/native-image-args.sh`):
   it is not in `scripts/build.sh`; if adopted, the build line of the arena image changes and needs its own
   end-to-end run.
7. `threads: 4` is a wall-clock choice only; it does not move the ranking metric measurably for the leaders
   (research 12, section 4) and was not re-measured for RLVM today. If the owner's 8 workers / pipelined load
   (P7-F4) become the launcher's default, change BOTH numbers and run this procedure again: the arena's limits
   are per test and `perf/` tests are single units.
8. Observation, not verified further: the pinned COMMUNITY tarball itself ships modules with "enterprise" in
   their names (`com.oracle.graal.graal_enterprise`, `com.oracle.svm.svm_enterprise`, ... in `jmods/` and in the
   `MODULES` line of `release`; `IMPLEMENTOR="GraalVM Community"`, GPL licence file). Nothing was downloaded or
   changed by me; the Oracle GraalVM directory `.toolchain/oracle-graalvm-25` is not touched by any build of this
   session.

### 0.6 What could not be established

- **RLVM's own aarch64 / x86-64 ratio.** Every place above is a projection. (b) is 8th for every ratio a baseline
  has shown, but at the 1.05 edge it is 4 G (0.15 %) behind palladium.
- **Wall clock of `perf/loop-unfolded-once` on the arena's runner** with the machine: 31.6 s here in a quiet
  window; the runner's speed is only known through the leaders' published wall times.
- **A gated configuration (c).** The integrator's log calls no set beyond the default gated; (c\*) is its
  candidate.
- **The size of item 3 and item 5** of table 0.4: E1 and C1 are prototypes; the fallback's share is an
  estimate from class names in a sampled profile (290 - 350 G), not a per-unit measurement.
- **The arena build on the arena's runner**: whether its nix shell has `curl` / `wget` and a C compiler that
  links `-lz` (the flake lists neither `zlib` nor `curl`; the build has a zlib-from-source path, which downloads).
  The online path (Maven Central, the GraalVM tarball) was not exercised by me: no downloads were allowed. The
  last online run is the steward's of 12:40Z.
- **Whether the arena counts kernel instructions too** (research 01, section 1.2). If it does: (a) +208.9 G
  (2.5 %), (b) +29.5 G (1.0 %), nano-lean +4.9 G; no place changes.
- **Thread count against instructions for RLVM** (4 against 8 workers, batch against pipelined): not measured.
- Wall and CPU of (d) on Mathlib: measured outside a window (162 s / 168 s on a parked container) and not quoted.

---

## 1. What was run

**Distribution** `work/ar1/dist`, built 2026-11-24T18:30:30Z from the published jars, offline, with the arena
build's flags:

```bash
bench/slot.sh -c ni -n 2 -- scripts/build.sh --published --no-pgo --opt 3 \
    --verify-deps scripts/arena/deps.sha256 --dist work/ar1/dist
```

GraalVM CE 25.5.5.0.3 (25.0.5.0.3+1-jvmci-25.5-b26); native-image flags `-O3 --gc=serial --no-fallback
-R:+ExitOnOutOfMemoryError -R:MaxHeapSize=13g -H:+ReportExceptionStackTraces`; 322 s wall, 898 s CPU, peak
4.2 GB; `bin/rlvm` 66,062,456 bytes, sha256 `26a5f96c59cd...`. `rlvm --version`: `rlvm 0.1.0-SNAPSHOT`,
engines `machine, reference*` (the reference engine is the default).

| jar | sha256 (first 12) | published |
|---|---|---|
| rlvm-admit | `398a117d4ad8` | 18:01:13Z |
| rlvm-api | `6ff40825ca1d` | 13:34:23Z |
| rlvm-cli | `f2af2657a704` | 07:54:30Z |
| rlvm-driver | `31fa08d9ce61` | 18:27:41Z |
| rlvm-export | `392c19f95db4` | 08:53:55Z |
| rlvm-inductive | `c15b14fffdd3` | 18:01:07Z |
| rlvm-level | `75bb0db242c5` | 04:53:25Z |
| rlvm-load | `26242def2701` | 14:52:21Z |
| rlvm-machine | `8a8ea5644b24` | 18:12:45Z |
| rlvm-main | `2c4a55f5a8ae` | compiled from the tree by `--published`; byte-identical to the published jar `2c4a55f5a8ae` |
| rlvm-nat | `b1dda7562adb` | 06:53:51Z |
| rlvm-ref | `ca1f9bd918bb` | 14:08:59Z |
| rlvm-store | `f85c730603b8` | 14:51:53Z |
| rlvm-truffle | `ad3256d0751d` | 18:16:25Z |
| collections, jniutils, nativeimage, polyglot, truffle-api, truffle-compiler, truffle-runtime, word 25.5.5.0.3 | as in `scripts/arena/deps.sha256` | - |

Full hashes: `work/ar1/logs/dist-jars.sha256`; the state of `.toolchain/m2` just before the build:
`work/ar1/logs/m2-jars-before-build.txt`. `work/dist` was not touched.

**Tests.** `work/exports/all`: the arena's complete test set (the 230 files of the published tarball, the two
`perf/magma-string-*` tests and the eight corpora that the tarball leaves out, the 30 `either` tests; 270 files,
byte-identical to the arena's by `manifest.json`), with the arena's outcome and time limit per test. Tests
without an arena limit ran with 1,800 s.

**Procedure**, per configuration (`work/ar1/scripts/suite.sh`):

```bash
bench/slot.sh -- bench/arena-docker.sh --arena-limits --no-network \
    bench/run-suite.py --perf --arena-timeouts --timeout 1800 --tests work/exports/all \
    --cwd work/ar1/src --report work/ar1/reports/LABEL.json -v -- 'RUN LINE'
```

`work/ar1/src/work/dist` is a link to `work/ar1/dist`, so the run line of `rlvm.yaml` is executed verbatim with
the checker's checkout as working directory, through `sh -c`, with `$IN` and `LC_ALL=C`, under `perf stat -e
instructions:u` (all threads and children) and GNU time, tests strictly one after the other. Mathlib was run a
second time per configuration in a container of its own inside a `bench/quiet.py` window
(`work/ar1/scripts/one.sh --quiet`); so were the leaders (`work/ar1/scripts/leaders.sh`) and the one test that
timed out.

Run lines:

- (a) `RLVM_STATUS_BASE=10 work/dist/bin/rlvm --threads 4 "$IN"; case $? in 10) exit 0;; 11) exit 1;; 12) exit 2;; *) exit 3;; esac`
  (the `run:` line of `rlvm.yaml`, sha256 of the file `390d58c2a289...`)
- (b) the same with `-X engine=machine` after `--threads 4`
- (c\*) (b) plus `-X machine.switches=gated,pchk,expect,warm,code,casetrees,bodies,directrules,same,match,joint,lazycases,lazyeval`
- (d) (b) plus `-X machine.switches=default,facts,probes -Drlvm.machine.m4=congruence,skipargs`

Times (UTC): suite (a) 18:36:52 - 18:52:12, (b) 18:52:21 - 19:01:15, (d) 19:07:26 - 19:13:51, (c\*) @@C_TIMES@@; quiet windows:
Mathlib (a) ended 18:49:17, (b) 18:58:41, the three Rust leaders 19:04:02, `loop-unfolded-once` 19:09:43,
rpylean about 19:18, (c\*) @@C_QTIME@@.

**A caveat that turned out not to matter.** The host was busy (load average 35 - 60 on 20 cores) and the quiet
lock was taken back to back by other agents, so my suite containers spent most of their time parked on the
two spare performance cores (the checker's own statistics show `processors=2`). Instruction counts of the
parked runs agree with the quiet-window runs to -0.22 % (a), +0.07 % (b) and -0.02 % (d); wall and CPU of the
suite runs are not evidence, and one arena time limit was hit only because of the parking
(`perf/loop-unfolded-once`, 0.1).

## 2. Verdicts in detail

`either` tests (free in the ranking; listed because they show the policy): (a), (b) and (c\*) give the same 28
verdicts - 9 accepted (`alg-conv-trans-acc-left`, `-acc-right`, `alg-conv-trans-quot-left-def`, `-quot-right`,
`nested-redex-aux`, `proj-maybe-prop`, `proj-maybe-prop-past`, `subject-reduction-redex`, `vanishing-occurrence`)
and 19 rejected, among them `imax-right-successor` and `imax-nested-absorption` (owner note 11) - and decline
the two non-terminating files. That is the published `official` verdict on every one of the 28. (d) accepts two
more, `alg-conv-trans-quot-left` and `eta-struct-major-and`: the verdicts of gruszka, sokonanoda and rpylean on
those files.@@C_EITHER_NOTE@@

Against the published results of others, configuration (a): `official` (v4.35.0) differs on 2 tests (the
non-terminating pair: timeout there, decline here); `official-nightly` on 9 `either` tests; nano-lean on 4 (its
decline of `bugs/nat-shift-huge`, which RLVM accepts, and 3 `either`); gruszka on 4 `either`; sokonanoda on 11;
rpylean on 8. Declines, errors and timeouts of the leaders on the published board: nano-lean 3, gruszka 0,
sokonanoda 6, rpylean 4, pestka 0, kefir 0, palladium 6.

## 3. Cost per corpus (suite runs; the instruction counts are exact, the times are of a parked container)

| corpus (arena limit) | (a): instructions / virtual s / RSS MiB | (b) | (c\*) | (d) | nano-lean, gruszka (published virtual s) |
|---|---|---|---|---|---|
| init (370 s) | 246.3 G / 41.0 / 350 | 115.2 G / 19.2 / 326 | @@C_init@@ | 112.4 G / 18.7 / 331 | 4.1, 4.1 |
| std (600 s) | 406.0 G / 67.7 / 468 | 195.5 G / 32.6 / 509 | @@C_std@@ | 188.7 G / 31.5 / 552 | 6.7, 6.7 |
| reachability (220 s) | 112.7 G / 18.8 / 1,929 | 57.3 G / 9.5 / 2,247 | @@C_reachability@@ | 57.2 G / 9.5 / 2,165 | 1.9, 2.4 |
| cedar (640 s) | 516.8 G / 86.1 / 529 | 263.7 G / 43.9 / 598 | @@C_cedar@@ | 251.6 G / 41.9 / 596 | 10.5, 10.3 |
| con-leche (920 s) | 809.7 G / 134.9 / 4,074 | 232.0 G / 38.7 / 684 | @@C_con-leche@@ | 231.6 G / 38.6 / 596 | 10.3, 9.2 |
| navier-stokes-euler (1,840 s) | 3,075.4 G / 512.6 / 813 | 1,151.1 G / 191.9 / 978 | @@C_navier-stokes-euler@@ | 507.9 G / 84.6 / 837 | 17.2, 21.8 |
| cslib (1,600 s) | 2,173.8 G / 362.3 / 2,030 | 852.3 G / 142.1 / 1,143 | @@C_cslib@@ | 689.2 G / 114.9 / 1,142 | 23.6, 27.4 |
| mathlib (4,120 s) | 8,408.3 G / 1,401.4 / 5,550 | 2,980.5 G / 496.7 / 2,824 | @@C_mathlib@@ | 1,976.7 G / 329.4 / 2,938 | 65.8, 77.0 |
| all 270 tests | 17,062.9 G | 6,589.0 G (without the test that timed out) | @@C_total@@ | 4,706.9 G | - |
| the 262 small tests | 1,314.0 G | 741.5 G | @@C_small@@ | 691.6 G | - |

All eight corpora are accepted in every configuration, far inside the arena's limits (the largest share of a
limit: Mathlib (a), 177.7 s of 4,120 s in the quiet window). The shortcuts of (d) are worth 33.6 % on Mathlib,
55.9 % on navier-stokes-euler, 19.1 % on cslib and 0 - 5 % on the other five corpora.

Small tests with the largest counts (instructions; wall of the parked suite run in brackets is NOT evidence):
`perf/magma-list-deep-n36` (limit 360 s) (a) 181.0 G, (b) 60.8 G; `perf/magma-list-pair-n21` (240 s) 115.4 G,
50.2 G; `perf/ite-taken-branch` (230 s) 109.6 G, 39.9 G; `perf/gf3-matrix-product` (70 s) 97.5 G, 19.4 G;
`perf/app-lam` (70 s) 55.3 G, 0.8 G; the four `*-bad` perf tests 52 - 72 G in (a) and 54 - 62 G in (b) (a
rejection is confirmed by the reference engine in either configuration). Peak RSS of a small test: 4,042 MiB
(`perf/app-lam`, (a)); with the machine 3,722 MiB (`perf/magma-list-deep-n36`) apart from the 8,686 MiB of
`perf/loop-unfolded-once`.

## 4. The leaders, same container, same session

Table 0.2. The local builds are those of `bench/baselines/` (arena revision `99195b6b`); each ran through its
generated wrapper (`bench/baselines/NAME.sh`, the arena's run line) in a container of its own inside a quiet
window (gruszka, nano-lean, sokonanoda in one window, rpylean in another). Their counts repeat this morning's
(361.3 / 418.7 / 504.3 / 544.6 G) to 0.03 - 0.26 %. Quiet wall clock with their 4 threads: gruszka 20.0 s, nano-lean
29.5 s, sokonanoda 33.7 s; rpylean (one thread) 125.8 s. RLVM with 4 workers, same windows: (a) 177.7 s, (b)
61.3 s@@C_WALL_NOTE@@ - 3.1 times gruszka and 2.1 times nano-lean for (b), a much smaller distance than in instructions
(7.1 and 8.2 times), because the machine retires about 14.5 G instructions per CPU second against gruszka's 5.9
and nano-lean's 3.5. (Cold process, batch load, 4 workers: the arena's configuration, not the owner's; the
owner's table is `work/status/warm.md`.)

## 5. The projection

`place` = 1 + the number of published checkers whose key (bad accepted, good rejected, Mathlib instructions,
declined) sorts before RLVM's (`work/ar1/board.py`, `work/ar1/scripts/mathlib-table.py`: the arena's
`compute_checker_stats` and `sort_key` recomputed from `results.json`; they reproduce the published order).
RLVM's x86-64 count is unknown; the columns of table 0.3 divide the count of this host by a ratio (count here /
published count) measured in this session for a leader, and by the two extremes any baseline has shown. A native
image of Java code is neither a Rust binary nor a JVM, so the truth can lie anywhere in that range; the places
of (a), (b) and (d) do not depend on where.

Same-machine cross-check without any ratio: (a) at 8,427 G lies between nanoda (5,533 G here, 12th) and
tenet-jvm with C2 (9,313 G here, 15th); (b) at 2,979 G and (d) at 1,977 G lie between pestka (1,340 G here, 5th)
and nanoda. kefir, palladium, con-ron (6th to 8th) are not built here, which is where the ratio is needed.

## 6. Where the instructions go on Mathlib (arena configuration)

### 6.1 By phase (`-X stop-after`, the measured image, `perf stat`)

| phase | instructions | per byte / line / constant | the leaders (research 12, section 5.2) |
|---|--:|---|---|
| start-up of the image | 0.003 G (a tutorial test end to end) | - | a JVM: 0.5 - 0.9 G |
| reading and loading the export (`stop-after=load`) | **133.2 G** | 21.1 per byte, 1,188 per line | nano-lean 40.9 G, gruszka 37.5 G, sokonanoda 36.9 G: 5.8 - 6.5 per byte |
| environment pass / admission (`stop-after=env` minus load) | **25.1 G** | 34,000 per constant | inductive types, constructors, recursors: under 3 G |
| checking, (a) | 8,268.8 G | 11.6 M per unit | nano-lean 320.4 G, gruszka 381.2 G |
| checking, (b) | 2,820.2 G | 3.9 M per unit | 7.4 x gruszka, 8.8 x nano-lean |
| checking, (d) | 1,818.7 G | 2.5 M per unit | 4.8 x gruszka |

The load is 1.6 % of (a), 4.5 % of (b), 6.7 % of (d), and 37 % of nano-lean's WHOLE run.

### 6.2 By thread and module (`perf record -e instructions:u -c N`, all threads, in the container)

Profile image `work/ar1/dist-sym`: the same jars and flags plus `-H:-DeleteLocalSymbols`; 167,969 samples of 50 M
instructions for (a), 149,103 samples of 20 M for (b). Native-image symbols carry no package, so classes are
mapped to their jar by simple name (`work/ar1/scripts/prof-class.py`); `IntIntMap` exists in two jars.

| | (a) reference engine: 8,398.5 G sampled | (b) machine: 2,982.1 G sampled |
|---|---|---|
| worker threads (checking) | 8,239.6 G (98.1 %) | 2,822.4 G (94.6 %) |
| main thread (reader + loader) | 133.1 G (1.6 %) | 133.1 G (4.5 %) |
| environment thread (admission) | 25.8 G (0.3 %) | 26.6 G (0.9 %) |
| `rlvm-machine` | - | 2,378.6 G (79.8 %): `Values` 855.0 G, `Eval` 776.7 G, `Infer` 274.9 G, `Conv` 119.4 G, `Runs` 97.1 G, `Levels` 60.6 G, `Machine` 60.6 G, `Reduce` 55.6 G, `Pairs` 37.6 G |
| `rlvm-store` (hash-consed terms: interning, substitution, memo tables) | 6,092.8 G (72.5 %): `Overlay` 2,588.7 G, `TermOps$Rewriter` 1,567.8 G, `Terms` 936.6 G, `TermOps$Memo` 690.1 G, `NodeCtx` 223.8 G | 378.0 G (12.7 %; 320.6 G on worker threads): `Overlay` 111.6 G, `Rewriter` 76.2 G, `Terms` 65.3 G, `NodeCtx` 31.3 G, `Memo` 26.4 G, `ProofBlocks` 37.7 G |
| `rlvm-ref` (+ `IntIntMap`) | 1,179.8 G + 824.5 G (23.9 %) | 43.7 G + 36.0 G (2.7 %) |
| `rlvm-export` + `rlvm-load` (reader, loader) | 88.9 G (1.1 %) | 85.4 G (2.9 %) |
| `rlvm-level`, `rlvm-api` | 178.7 G (2.1 %) | 40.0 G (1.3 %) |
| **garbage collector** (`com.oracle.svm.core.genscavenge`) | **8.7 G (0.1 %)** | **0.6 G (0.0 %)** |
| other Substrate VM runtime (array copies), JDK code, libc, vdso, no symbol | 21.2 G (0.3 %) | 15.4 G (0.5 %) |
| JIT compiler | none: the image is compiled ahead of time; no compiler thread exists | none |

Top symbols, (a): `Overlay.mkApp` 21.7 %, `TermOps$Rewriter.leaf` 9.9 %, `Rewriter.run` 8.7 %, `IntIntMap.get`
7.1 %, `TermOps$Memo.put` 5.8 %, `Terms.kind` 5.1 %; the kernel's own control code (`RefKernel.*`) is 13.7 %.
(b): `Values.neu` 8.0 %, `Values.closureProbe` 5.3 %, `Eval.argvFast` 4.3 %, `Eval.app` 4.0 %, `Eval.gate` 4.0 %,
`Values.frameProbe` 3.8 %, `Values.closureOverNew` 2.9 %, `Values.mkConst` 2.8 %. Full tables:
`work/ar1/prof/a.tables.md`, `b.tables.md`; raw sums per symbol: `a.syms.tsv`, `b.syms.tsv`.

In the arena's currency the collector is nothing (the stores are primitive arrays), there is no JIT to pay
for, and reading the file is the only fixed cost of any size. For the reference engine three quarters of all
instructions are interning and substitution in the term store; for the machine 80 % are its own value store and
evaluator, which is the per-operation cost of P8's section 4.6.

### 6.3 The eager-reduction fallback inside (b)

`-X machine.declines` of the profile run lists 13,422 declines = **6,711 units, each declined twice**
(`UNSUPPORTED_INPUT (E_LIMIT) ... eager reduction`), as P8 counted. Those units are checked by the reference
engine. On the worker threads of (b) the classes that only the reference engine uses (`RefKernel`, `PairSet`,
`LongIntMap`, `IntIntMap`, `Overlay`, `TermOps$Rewriter`, `TermOps$Memo`) sum to 287 G; in (a) the same classes are
83 % of the workers' instructions, so the fallback is about **290 - 350 G, 10 - 12 % of (b)**, for units that cost
gruszka 23.9 G (P8). This is an estimate from class names, not a per-unit count.

## 7. What it would take: how table 0.4 was made

- Items 1, 2 and 4 are measured end to end with the arena's procedure (tables 0.1 - 0.3).
- Item 3: fallback of 6.3 (290 - 350 G) plus the machine's abandoned attempts (15.3 G, P8 section 4.3) minus
  what the machine would spend on those units at its present ratio to gruszka (6.5 x 23.9 G = 155 G; 3.9 x with
  the shortcuts = 93 G).
- Item 5 (C1) has no number: it aims at the comparisons that item 4 removes while keeping the official verdict.
- Items 6 and 7 are bounds: today's count minus the leaders' for the same phase.
- Rows 9 and 10 add the items; savings measured on different switch sets do not add exactly, so they are
  rounded and carry the uncertainty of item 3.
- Row 11 uses research 12 (gruszka reads for 37.5 G and checks for 381 G) and P8 (after the shortcuts the machine
  is at 1.5 x gruszka's ticks and 2.6 x its instructions per tick).
- Not in the table because they are not instruction items: the worker count, pipelined loading, heap sizing
  (`-Xms`), the collector choice. They move wall clock, which the board shows and does not rank.

## 8. The arena definition, run end to end from a clean copy without network

`work/ar1/scripts/e2e.sh`: a clean copy of the tree as of 18:38:38Z (parent pom, `env.sh`, `scripts/`, the 15
modules without `target/`; 1,231 files) in `work/ar1/e2e/src`; its `.toolchain` = links to the pinned GraalVM CE
and Maven of the host and a COPY of the third-party part of `.toolchain/m2` (no `rlvm` artifact); then, in the
arena image with `--no-network`, 8 CPUs, 16 GB:

```
sh -c "scripts/arena/build.sh"        # the build: line of rlvm.yaml
```

Result: status 0 after 513 s. Maven built all 15 modules in 12.6 s (the whole tree compiles); the third-party
jars match `deps.sha256`; the JVM launcher and the image passed the build's verification (exit-status protocol,
Truffle probe); native-image `-O3` 496 s wall, 1,067 s CPU, peak 3.8 GB; `work/dist` is a link into
`work/dist.d/20261124T185419Z-d7be406ab8dd-native` and the run line resolves through it; `bin/rlvm` 66,062,456
bytes (sha256 `7ca470b6ae5a...`), dynamic dependencies libz, libc. Then (`work/ar1/scripts/e2e-check.sh`, same
image, no network): the yaml validates against `refs/lean-kernel-arena/schemas/checker.json`; the run line gives
accept on `init-prelude` (0.95 G instructions), `tutorial/001_basicDef`, `perf/app-lam` (55.3 G) and `init`
(245.1 G), reject on `tutorial/002_badDef` and `corner-cases/imax-right-successor`.

Tree against published jars (class files compared by name and CRC): identical for admit, api, cli, driver,
export, inductive, level, machine, main, nat, ref; **different for rlvm-load, rlvm-store and rlvm-truffle** (the
tree is ahead of the published jars: LP2 and T3 at work); `rlvm-testkit` only in the tree build. On `init` the
tree-built image needs 245.1 G against 246.3 G for the published jars (-0.45 %).

What this does and does not show: with the pinned toolchain and a Maven repository that already holds the
plugins, nothing else is fetched and the build is reproducible offline; that the downloads themselves work
was not tested here.

## 9. Known open items: can they show on the arena's test set?

| item (board, `work/status/requests.log`) | on today's 270 tests | if it showed |
|---|---|---|
| A2-F1: two level records for one universe parameter - RLVM accepts files that the official checker rejects | no: 0 of the 270 files and none of the eight corpora contain a repeated level record (A2's scanner over the 230 published tests and the corpora; the 30 `either` tests and the two extra `perf/` tests scanned for this report) | a hand-made `bad` test of that shape would be a WRONG ACCEPT: place 30 whatever the speed. The only open item that can cost the whole ranking, so it should be closed (decline is enough) before any submission |
| the `either` tests | all 30 answered or declined; verdicts = `official` on 28 | free: `either` never counts |
| the two non-terminating `either` files with the machine (orchestrator 16:14Z) | declined after 0.88 s and 24 G each (the machine's tick bound of 16:45Z, then the reference engine); the arena's limit is 60 s | closed for the arena |
| the machine's tick bound on a `good` test: `perf/loop-unfolded-once` | (b), (c\*): @@C_LOOP@@ | a timeout = one more "declined": no place lost, a red cell on a test that the reference engine passes in 76 ms |
| eager-reduction declines of the machine | unit-level only: the reference engine decides those units, no test is declined for it; 6,711 units on Mathlib | costs instructions (0.4, item 3), never a verdict |
| generated constants (admission declines an export that needs a kernel-generated constant without a record) | 0 of 270: no test is declined for it | a decline: tie-breaker only |
| the machine is not the default (`MachineProvider.priority()` = 10) | the definition as it is runs the reference engine: place 15 instead of 8 | seven places |

## 10. Reproduction and files

```bash
# distribution from the published jars, arena flags (5-6 min; one of the host's two native-image slots)
bench/slot.sh -c ni -n 2 -- scripts/build.sh --published --no-pgo --opt 3 \
    --verify-deps scripts/arena/deps.sha256 --dist work/ar1/dist
mkdir -p work/ar1/src/work && ln -sfn ../../dist work/ar1/src/work/dist
RUN="$(sed -n 's/^run:[[:space:]]*//p' scripts/arena/rlvm.yaml | head -n 1)"
work/ar1/scripts/suite.sh a "$RUN"                                  # all 270 tests, report work/ar1/reports/a.json
work/ar1/scripts/one.sh --quiet mathlib-a-quiet work/exports/mathlib.ndjson 4120 "$RUN"   # Mathlib in a quiet window
bench/slot.sh -- bench/quiet.py -- work/ar1/scripts/leaders.sh gruszka nano-lean sokonanoda
python3 -I work/ar1/scripts/tables.py a b c d                        # verdict and cost tables
python3 -I work/ar1/scripts/mathlib-table.py                         # Mathlib table and projection
python3 -I work/ar1/board.py --row "rlvm:0:0:3.256e12:3"             # the board with a hypothetical row
# phases, profile
work/ar1/scripts/one.sh mathlib-stop-load work/exports/mathlib.ndjson 4120 'work/dist/bin/rlvm --threads 4 -X stop-after=load "$IN"'
bench/slot.sh -c ni -n 2 -- work/ar1/scripts/build-sym.sh            # the same jars with symbols: work/ar1/dist-sym
bench/slot.sh -- bench/arena-docker.sh --arena-limits --no-network work/ar1/scripts/prof-in.sh \
    $PWD/work/ar1/prof/b 20000000 -- $PWD/work/ar1/dist-sym/bin/rlvm --threads 4 -X engine=machine $PWD/work/exports/mathlib.ndjson
python3 -I work/ar1/scripts/prof-class.py work/ar1/prof/b.syms.tsv   # one perf record at a time (the ring buffers are per user)
# the arena build line from a clean copy, no network
work/ar1/scripts/e2e.sh && bench/arena-docker.sh --arena-limits --no-network work/ar1/scripts/e2e-check.sh
```

Files: `work/ar1/reports/{a,b,c,d}.json` (every test: status, exit code, instructions, times, RSS, the checker's
output), `tables-*.md`, `mathlib-*-quiet.json`, `leader-*.json`, `loop-b-quiet.json`, `mathlib-stop-*.json`;
`work/ar1/logs/` (suite logs, run lines, jar hashes, the offline build's log `e2e-build.log`, `e2e-check.log`);
`work/ar1/prof/` (profiles; `b.declines.txt`: the machine's declines on Mathlib).
