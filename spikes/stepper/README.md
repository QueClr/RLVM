# spikes/stepper - the host-level control convention of the RLVM machine

Evidence for RECONCILIATION B4 (critic finding C2.1): how should Java execute the worklist transition
system of ISA 1.3 with the least CPU time? The decision and the rules derived from it are in
*agentic-note*: rep-stepper-convention; this file describes the experiment and how to repeat it.
Result tables: *agentic-note*: rep-spike-stepper (generated). Nothing from `refs/` is built or run.

**Result.** Bounded host recursion with lazy materialisation (variant H, depth bound 64) is chosen:

- CPU time over plain recursion R (geometric mean of four workloads, by runtime): H64 **+1% to +6%** on
  native image CE, Graal JIT and C2 alike; the fused-loop worklist W1 **+0% to +3% on the Graal JIT, +10% to
  +13% in the native image, +14% to +17% on C2**; the convention of ISA 1.3 as first written (W0) **+28% to
  +57%**. Target: +15%. Table in section 5.
- Frames written per judgment: W0 0.19, W1 0.064, H64 0.0001; H64 runs 1,000,000-deep terms on a 512 KiB
  stack; budgets and rollback are exact under H (same outcomes and tick counts for depth bounds 0, 64, none).
- All 15 variants return the same normal form, value and verdict as R for all 115,807 roots of Init and all
  292,275 roots of Cedar; run alone they intern the same records under the same ids, with the same number of
  hash-consing probes.

## 1. What is compared

One operation library, three judgments, and the same judgments written under several control conventions.

**Data.** Hash-consed values in one flat `int[]` with an open-addressing table (`Vals.java`, taken from
`spikes/machine`): neutral application cells, projections, sorts, constants, literals, environment cells,
closures `(term, environment)`. Terms are the export's expression table in columns (`Terms.java`); normal
forms are appended to it through a second hash-consing table. No Java object per term or value.

**Judgments** (specified by `Rec.java`, 135 lines):

| judgment | what it does |
|---|---|
| `EVAL(t, env)` | NbE evaluation: beta, zeta, no delta; closures for binders |
| `READBACK(v, depth)` | normal form of a value as a hash-consed term; opens binders with fresh variables and evaluates the bodies |
| `CONV(a, b, depth)` | structural conversion: identity first, application spines pairwise, projections, closures by opening the binder and comparing domains and bodies |

**Workload.** For every root term: `v = EVAL(root)`, `nf = READBACK(v)`, `w = EVAL(nf)`, `ok = CONV(v, w)`.
The normal form lives in a different term region than the root, so `w` and `v` are different closures and
the conversion has to walk; it must answer true for every root, which checks the three judgments against
each other. Roots: the type of every constant and the value of every definition, theorem and opaque constant
of `work/exports/init.ndjson` and of the whole of `work/exports/cedar.ndjson`. The walk is a tree walk (no
tabling), so roots whose normalisation needs more than 100,000 judgments are left out (list written by the
`heavy` command into `<cache>.skip`):

| corpus | constants | roots used | left out | judgments per pass (types + values) | judgments per pass (types only) |
|---|--:|--:|--:|--:|--:|
| init | 59,979 | 115,807 | 2,106 (1.8%) | 302,910,762 | 20,167,490 |
| cedar | 151,772 | 292,275 | 3,582 (1.2%) | 615,869,900 | 53,634,353 |

Of the term nodes evaluated, 82% (Cedar, types only) to 95% (Init, types + values) lie in *inert nests*
(applications whose heads are constants, with variables, sorts, constants, literals and binders as leaves),
which need no reduction. One judgment costs
about 150 instructions and 10 ns in R, most of it the hash-consing probes: this is the weight against which
a convention's overhead is measured.

Two memory regimes: *sessions* (all values and normal forms are dropped whenever their footprint passes
16 MiB, the default of RECONCILIATION section 3; this is the regime of every table unless stated) and
*one arena* for a whole pass (the hash-consing probes then miss the caches).

**Conventions** (each is a class; all of them call the library in the same order as `Rec`):

| variant | class | convention |
|---|---|---|
| R | `Rec` | plain host recursion, one method per judgment; the baseline. Thread stack as large as needed |
| W0 | `W0` | ISA 1.3 as first written: `run()` switches on the op of the top frame and calls `X.step(m)`, which switches on the frame's state; registers `W`, `wp`, `A`, `xp` are machine fields; a judgment owns a frame of 5 payload words + header for its whole life; `call J(x) => k` tries `J.fast` and otherwise pushes; spines and tail positions are loops inside one frame |
| W1 | `W1` | candidate (i) of ISA 8.2: one fused loop per family (EVAL; READBACK; CONV) with `W`, `wp`, `xp`, `A` in locals; the operands of the running judgment are locals; a frame is written only for a suspended judgment (its live variables + header `(op << 4 or st) << 16 or n`); one dense dispatch on the header when a result is returned; a same-family sub-judgment is a `continue`; a sub-judgment of another family pushes a fresh frame and returns to `run()`; inert application nests are evaluated by one loop over a scratch buffer (`Inert.java`) |
| W1n | generated | W1 without the inert-nest loop |
| H&lt;D&gt; | `Hyb` (template) | bounded host recursion with materialisation. A judgment is a chain of step methods; a sub-judgment is a host call; a judgment that finds more than D judgments below it on the host stack writes its own frame and returns the signal SUSP, and every step above it writes the frame of its continuation and returns. The frames go innermost-first into a side buffer that grows downwards and are moved onto `W` in one copy; `drive` then runs `W` like a worklist (pop a frame, call its step method with the result). Generated for D = 0, 4, 8, 32, 64, 256 and "inf" (never materialises) |
| H0 | generated | H with D = 0: every sub-judgment that is not a leaf becomes a frame. The same source running as the worklist machine of the specification |
| Ri, Hi64 | generated | R and H64 with the inert-nest loop of W1 |
| HT&lt;D&gt; | generated | H with one tick charged per judgment and a `TRY` operation (budget, rollback) |

`scripts/build.sh` generates the variants that differ from a template in a compile-time constant
(`D`, `INERT`, `TICKS`) by `sed`, so that every variant is compiled with its constants folded.

**What "identical results" means here**, and how it is checked:

1. `verify`: R and every variant run on every root in *one* arena; because everything is hash-consed, the
   normal form id, the value id and the verdict must be equal integers.
2. Every variant alone: the checksum over `(nf, w)` of all roots, the number of values, the arena size and
   (JVM, `-Drlvm.stats=true`) the number of value probes and term probes are the same as R's. Ids depend on
   the order of interning, so this also shows that the library calls happen in the same order.
3. `try`: with a budget, the outcome (T / F / U) and the ticks charged per root are the same for D = 0, 64
   and infinity.

## 2. Layout

| path | what |
|---|---|
| `src/rlvm/spike/stepper/Terms.java`, `Vals.java` | term table (export reader, binary cache, hash-consed normal forms, inert flags); value arena and hash-consing |
| `Rec.java`, `W0.java`, `W1.java`, `Hyb.java` | the conventions; `Fast.java` (inline fast paths of the worklist variants), `Inert.java` (inert-nest loop) |
| `Engine.java`, `Stat.java` | interface of a convention (`decl(root)`, `tryDecl(root, budget)`); counters |
| `Main.java` | commands `parse`, `run`, `duel`, `verify`, `try`, `deep`, `heavy` |
| `Deep.java` | 1,000,000-deep synthetic terms |
| `scripts/build.sh`, `build-native.sh`, `build-images.sh` | javac build with generated variants; native images |
| `scripts/make-batches.py`, `run-all.sh`, `pbatch.sh`, `in-container.sh`, `fmt.py` | measurement batches, one arena-shaped container per batch, every run through `bench/measure.sh` |
| `scripts/verify.sh`, `stats.sh`, `counters.sh`, `tables.py` | verification, frame counters, hardware counters, `results/tables.md` |
| `results/` | formatted logs of every batch (`duel-*`, `duel2-*`, `proc-*`, `deep-*`), `verify.txt`, `stats.txt`, `counters.txt`, `native-build.txt`, `tables.md` |

Build products, caches and raw logs are in `work/stepper-out/` (`$OUT`).

## 3. Commands

```sh
. spikes/stepper/env.sh
scripts/build.sh                                   # javac -> $OUT/stepper.jar, $OUT/inline.cmd
J="$JAVA_HOME/bin/java -XX:+UseSerialGC -Xmx24g -cp $OUT/stepper.jar rlvm.spike.stepper.Main"
$J parse $DATA/init.ndjson  $OUT/init.bin          # binary caches (1 s and 2 s)
$J parse $DATA/cedar.ndjson $OUT/cedar.bin
java -XX:+UseSerialGC -Xmx24g -Drlvm.stats=true -Drlvm.maxnodes=100000 -Drlvm.writeskip=1 \
     -cp $OUT/stepper.jar rlvm.spike.stepper.Main heavy $OUT/init.bin    # writes init.bin.skip; same for cedar
scripts/build-images.sh                            # native images stepper-ce and stepper-ce-fi (40 s each)
scripts/verify.sh                                  # results/verify.txt   (CPUS=0-4 pins it)
scripts/stats.sh                                   # results/stats.txt
scripts/run-all.sh                                 # every batch in Docker, then the tables under work/spikes/ (scratch)
scripts/run-all.sh duel-nce duel-jvm               # chosen batches
scripts/counters.sh                                # results/counters.txt (perf stat: branches, branch misses)
python3 -I scripts/tables.py > ../../work/spikes/stepper/tables.md

# single runs
S="-Drlvm.session=16777216 -Drlvm.tbits=20 -Drlvm.hcap=4000000 -Drlvm.ttbits=20 -Drlvm.textra=1000000"
java $S -Drlvm.passes=3 -cp $OUT/stepper.jar rlvm.spike.stepper.Main run $OUT/init.bin H64
$OUT/ni/stepper-ce $S -Drlvm.roots=types duel $OUT/init.bin R,W1,H64 20
$OUT/ni/stepper-ce -Drlvm.stackkb=512 deep H64 1000000
java -cp $OUT/stepper.jar rlvm.spike.stepper.Main try $OUT/init.bin HT64 1000
```

Runtimes of the campaign (`scripts/make-batches.py`):

| label | runtime |
|---|---|
| nce | native image, GraalVM CE 25.5.5.0.3 (`--no-fallback --initialize-at-build-time=rlvm.spike.stepper`, -O2, serial GC), default inlining |
| nfi | the same with `-H:+UnlockExperimentalVMOptions -H:PriorityForceInline=<continuation steps of Hyb*> -H:PriorityNeverInline=<sig*, save*, spill*>` |
| jvm | HotSpot of the GraalVM CE 25 JDK, Graal JIT (default), `-XX:+UseSerialGC` |
| c2 | the same with `-XX:-UseJVMCICompiler` (C2) |
| jfi, cfi | jvm, c2 with `-XX:CompileCommandFile=$OUT/inline.cmd` (`inline` for the continuation steps, `dontinline` for the helpers) |

## 4. How CPU time is measured

Everything runs in the `rlvm-arena` container with the arena limits (`bench/arena-docker.sh --arena-limits`:
8 performance cores, 16 GB) through `bench/measure.sh`.

The host is shared with the other agents' jobs: the same pass of the same binary varied by 10-20% from
minute to minute during the campaign, more than the differences under study, while the instruction counts
of the native image are exact. Two consequences:

- **CPU-time ratios come from "duels"** (`Main.duel`): all variants take turns inside one process, one pass
  each per round, the starting variant rotating. Passes that are seconds apart see the same host. Two
  estimators per variant: `min(variant) / min(R)` over all rounds (disturbance only adds time), and the
  median over the rounds of `pass(variant) / pass(R)`. Pass CPU is the CPU time of the worker thread
  (`/proc/thread-self/schedstat`). In JVM mode the first rounds (interpreter, JIT) are left out.
- **Whole processes** (one variant per process, as the arena would run a checker) supply the CPU time of all
  threads including the JIT, wall clock, peak RSS and instructions; their CPU and wall columns carry the noise.

## 5. Results

All tables: *agentic-note*: rep-spike-stepper. Summary of its section 1 (geometric mean over the four workloads - Init and
Cedar, types only and types + values, 16 MiB sessions - of `min(variant) / min(R)`; in brackets the same mean
of the median paired ratio):

| variant | native CE | native CE, forced inlining | JVM Graal JIT | JVM Graal JIT, forced inlining | JVM C2 | JVM C2, forced inlining |
|---|--:|--:|--:|--:|--:|--:|
| R | 1.000 (1.000) | 1.000 (1.000) | 1.000 (1.000) | 1.000 (1.000) | 1.000 (1.000) | 1.000 (1.000) |
| Ri | 1.042 (1.019) | - | 0.979 (0.993) | - | 1.047 (1.029) | - |
| W0 | 1.348 (1.322) | - | 1.313 (1.277) | - | 1.572 (1.501) | - |
| W1n | 1.199 (1.186) | - | 1.070 (1.073) | - | 1.348 (1.317) | - |
| W1 | 1.114 (1.099) | 1.127 (1.117) | 1.031 (1.023) | 1.000 (1.016) | 1.173 (1.138) | 1.163 (1.151) |
| H8 | 1.135 (1.134) | 1.142 (1.145) | 1.130 (1.139) | 1.118 (1.136) | 1.124 (1.096) | 1.125 (1.113) |
| H32 | 1.074 (1.063) | 1.040 (1.054) | 1.050 (1.071) | 1.027 (1.034) | 1.003 (1.009) | 1.044 (1.030) |
| H64 | 1.038 (1.061) | 1.054 (1.035) | 1.052 (1.062) | 1.032 (1.040) | 1.016 (1.022) | 1.030 (1.013) |
| H256 | 1.081 (1.063) | 1.029 (1.021) | 1.034 (1.048) | 1.056 (1.060) | 1.016 (1.022) | 1.022 (1.003) |
| Hinf | 1.067 (1.068) | 1.051 (1.044) | 1.025 (1.034) | 1.006 (1.044) | 1.078 (1.041) | 1.017 (0.999) |
| Hi64 | 1.016 (1.049) | 1.056 (1.071) | 1.006 (1.017) | 0.988 (1.018) | 1.036 (1.029) | 1.047 (1.023) |
| HT64 | 1.075 (1.071) | 1.052 (1.054) | 1.086 (1.092) | 1.047 (1.056) | 1.073 (1.037) | 1.034 (1.017) |

native CE = native image with default inlining; "forced inlining" = the continuation steps of H inlined by
build flag (it does not concern R and W1; their columns show how far two measurements of the same code
differ). W0, W1n and Ri were measured with default inlining only. R, W0, W1, H64, Hi64 and HT64 pool two
series of duels (50 rounds on Init's types, 25 on Cedar's, 5 to 7 on the types + values workloads; a little
fewer in JVM mode, where the warm-up rounds are dropped); the other variants have the first series only
(20, 10, 5 and 3 rounds).

How to read it:

- The same code measured twice differs by up to 3 points: H32, H64, H256 and Hinf are one code path with a
  negligible number of materialisations and still spread by up to 5 points inside a column. Differences of
  that size are not findings.
- **W0** misses the target everywhere. It executes 66% more instructions than R in the native image.
- **W1** depends on the compiler: within 3% of R on the Graal JIT, +10% to +13% in the native image, +14% to
  +17% on C2. Without the inert-nest loop (W1n) it is +7%, +19% and +32% to +35%.
- **H** is between +1% and +6% on every runtime (on the types workloads, which have the most rounds: +0% to
  +7% over all runtimes and both corpora). Neither compiler inlines the continuation steps of H on its own
  (Graal: "not worth inlining according to the cost-benefit analysis"); forcing them
  (`-XX:CompileCommand=inline`, `-H:PriorityForceInline`) removes 8 points of instructions in the native
  image and 0 to 3 points of CPU on the native image and the Graal JIT, nothing on C2.
- **The depth bound**: D = 8 writes 0.13 frames per judgment and costs 7 to 10 points; from D = 32 on the cost
  is not measurable. H0 (every non-leaf sub-judgment a frame, i.e. the worklist machine) costs 1.4x (Graal
  JIT) to 2.1x (native image) the CPU time of R (single processes, section 3 of the tables).
- **Instructions are not CPU time** (native image, Init, types + values, per pass): R 45.1 G; W1 54.2 G
  (+20%); H64 51.8 G (+15%), with forced inlining 48.0 G (+6%); W0 74.9 G (+66%). The CPU ratios are about half
  of the instruction ratios: the added instructions are cheap, and what an explicit stack really costs is
  its data-dependent dispatch (`results/counters.txt`, `scripts/counters.sh`: net of set-up, W1 takes 25%
  more branch misses than R, W0 27%, the inert-nest loop alone (Ri) 14%, H64 none; the cycle counts in that
  file carry the host noise).
- **An explicit stack is not faster than the host stack even in its tightest form**: R with the inert-nest
  loop (Ri) is equal to R on the Graal JIT and 2 to 4 points slower in the native image and on C2.
- **Ticks**: charging one tick per judgment (HT64 against H64) costs 1 to 4 points (5% instructions).
- **Memory**: peak RSS does not depend on the convention (native image: 208 MB on Init, 480 MB on Cedar,
  nearly all of it the term table; the worklist never outgrows its initial 4,096 words except on the deep
  terms).

### Frames (Init, types + values: 302.9 M judgments, of which 146.5 M are not leaves)

| variant | frames written | per judgment | words per frame |
|---|--:|--:|--:|
| W0 | 58,279,305 | 0.192 | 6.0 |
| W1n | 62,120,173 | 0.205 | 4.7 |
| W1 | 19,294,372 | 0.064 | 4.6 |
| H0 | 290,870,554 | 0.960 | 2.9 |
| H8 | 40,187,677 | 0.133 | 2.7 |
| H32 | 714,909 | 0.0024 | 2.8 |
| H64 | 34,493 | 0.00011 | 2.9 |
| H256 | 1,031 | 0.000003 | 2.5 |

Cedar and the types-only workloads: *agentic-note*: rep-spike-stepper, section 4 (H64 on Cedar: 0.00025).

### Deep terms

`deep VARIANT 1000000` builds five closed terms with a 1,000,000-deep nest (applications nested in argument
position under a variable head; one spine of 1,000,000 arguments; 1,000,000 binders; applications and binders
alternating; one inert nest) and runs the same four steps on each. Every W and H variant with a bound returns
R's results on a **512 KiB** thread stack on all three runtimes, in times comparable to R's (table); the
worklist grows to 8 M words (32 MiB; W0: 16 M). R itself overflows 512 KiB at once, as does Hinf; R needs a
256 MiB stack for these terms (128 MiB overflows). Smallest stack that works for H: 64 KiB in the native
image (D = 64 and D = 256); on a cold JVM 128 KiB for D = 64 and 384 KiB for D = 256. Tables:
*agentic-note*: rep-spike-stepper, section 5; the stack minima are host runs of `deep` with `-Drlvm.stackkb=N` /
`-Drlvm.stackmb=N` and are not part of the batches.

### Budgets and TRY

`try CACHE HT<D> BUDGET` evaluates, reads back and re-evaluates every root without a budget and then runs
`CONV` under a `TRY` with the given budget of ticks (one tick per judgment started). With budgets of 10, 100,
1,000 and 100,000 ticks the numbers of T and U outcomes on Init are 8,121 / 107,686, 44,562 / 71,245,
97,395 / 18,412 and 115,807 / 0; the outcome and the ticks charged per root (checksum) and the number of
interned values are identical for D = 0 (every frame written; the driver cuts `W` down to the `TRY` frame),
D = 64 and no bound (host unwinding only). `results/verify.txt`.

## 6. Findings for the machine

1. Use bounded host recursion (H) with a depth bound of 64. Rules and a code template:
   *agentic-note*: rep-stepper-convention.
2. The worklist machine of ISA 1.3 stays the specification and is available as the D = 0 build of the same
   source, at 1.4x to 2.1x the CPU time: a test configuration, not the production convention.
3. Fused family loops (ISA 8.2, candidate (i)) are not needed and would make the cost depend on the compiler
   and on the family partition; the 8,000-bytecode limit stops being a design constraint (H steps are 30 to
   310 bytecodes; the spike's small W1 families are already 1,000 to 1,400).
4. Give the continuation steps a common name prefix and inline them by build flag on both platforms.
5. Keep the inert-nest loop as an optional fast path of `EVAL` (worth 2 to 5 points on the Graal JIT only).
6. Replace the "P = 25 instructions per frame, 8-10%" line of ISA section 0 by the measured cost of the
   convention: +1% to +6% CPU over recursion, +6% to +15% instructions.

## 7. Limits of this evidence

- Three small judgments without delta, tabling or universe levels; the real judgments do more work per call
  and call across families much more often. The first shrinks every convention's share; the second costs
  fused loops (a frame, a return to `run()`, a second dispatch per call) and costs H nothing, and it is
  almost absent here (0.9 M such calls per 303 M judgments under W1).
- Candidate (ii) of ISA 8.2 exactly as written (fused loops inside a family, host calls with reserved frame
  slots across families) was not built. W1 and H bracket it.
- aarch64 (Cortex-X925) only; the arena runs x86-64.
- Shared host: single-process CPU and wall times vary by 10-20%; the duel ratios are good to about 3 points.
- GraalVM CE only; no PGO image.
- `TRY`: one segment per root around `CONV`; nested segments, fatal faults and the restoring of binder and
  scratch stacks on a cut are specified in the convention document but not exercised here.
- Machine state is static (one worker). A per-worker machine object adds one argument to every step of R and
  H alike.
- Roots whose tree-walk normalisation exceeds 100,000 judgments are skipped (1.8% of Init's roots, 1.2% of
  Cedar's); they are the large proofs.
- `duel`, the TRY experiment and the forced-inlining runtimes were added after the first campaign was
  started and abandoned (its estimator, one variant per process, could not resolve differences below 15% on
  this host); every number in `results/` comes from the final sources.
