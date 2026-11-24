# NOTES-T2 - the Truffle tier (owner: T2)

Module `rlvm-truffle`, package `rlvm.truffle` (+ `rlvm.machine.T2Bridge`). What it is and how to use it:
`README.md`. This file: deviations from ISA 8.4 / 8.5, what the tests showed, the measurements, every
experiment kept or rejected, and the go / no-go. All of 2026-11-24.

## 1. As built against ISA 8.4 / 8.5

| # | ISA | As built | Reason |
|---|---|---|---|
| D1 | "one final `Instr` subclass per opcode ... calls the `Ops` fast method" | own nodes for 23 opcodes; `BUNREAD`, `MATCH`, `TARG` and any unknown opcode run through the stepper's own body (`Tier.step`, behind a boundary) | their bodies hold logic of M7's (term walks, the site registers of a telescope); executing the body itself keeps one semantics and makes every future opcode executable without a change here |
| D2 | registers are static `int` frame slots | so, except the registers pinned to the window: in a `TELE` block the telescope variables and the 8 site registers; in a block with an unknown opcode all of them | the stepper's body reads and writes the window |
| D3 | "written to the `ACT` frame in `W` only when the root suspends" | on a suspension the slot registers that are LIVE at the resume point are written to the register window (`RS`); the frame is written by `Tier.suspend` | under convention H the registers of an activation live in the window `RS[rb..]`, not in the frame (NOTES-M7 D1); liveness keeps the write-back to what the rest of the block reads |
| D4 | `selectEntry(m.entryPc)`, `loadRegisters` | entries = the block's entry table + every resume point (successor of a class-j instruction); an entry loads its live slot registers; the entry number, `rb`, `d` travel in fields of the per-machine `T2Tier`, which is the root's one argument | no allocation per call; `rlvm.t2.resume=false` leaves resumes to the stepper |
| D5 | calls between promoted blocks: `DirectCallNode`, at most 64 deep | none: a block applies a closure through `Eval.apply` (behind a boundary), which reaches the callee's block through the stepper's hook | `APPLY` does not know its callee statically; a direct call would need the fast path of `Eval.apply` (tick, call event, depth test, binder arithmetic of `applyFrom`) a second time inside the node. This is the main thing the tier does NOT have (section 6) |
| D6 | cut of `W`: every nested compiled activation propagates `UNWIND` | a signal (`>= Op.SIG`) returned by an operation is returned at once by the node and by the root, as the stepper does (convention H has no UNWIND status: SUSP / ABORT / FATAL are return values) | H3 |
| D7 | inline caches: the `IC` table shared with the base tier | `KVAL` probes the same cell through `Code.icGet`; a miss runs the stepper's body, which fills it | one cache |
| D8 | governor: RENT = 2 C / sigma = 210 ms, compile CPU <= 1% of process CPU, promoted < 64 | `spent(b) >= rent * cost(b)` with `cost(b)` = c0 + c1 * instructions, learned from the compilations; share 2%, max 256 (profile `cpu`); profile `warm` for OWNER-NOTES 12 | the fixed 18 ms of the spike is not the cost of a block here (4 ms for 2 instructions, 90 ms for 27); `spent` is the machine's own heat accounting (M7), self time without nested blocks |
| D9 | `heat[block]` summed over the workers every 4096 entries | `Tier.Heat` (M7): per session counters, listener every `period` entries (1024 / 256) | M7 owns the counters |
| D10 | `compiler.MaximumGraalGraphSize=20000` | not set; blocks of more than 256 instructions are not promoted | Graal's own limit showed up as "code is too large" on one block of 846 words; the instruction cap avoids the wasted compilation |
| D11 | - | a promoter thread builds roots and submits them; workers never touch Truffle except to call valid code | workers do not wait; the Truffle context is entered on one thread only |
| D12 | "No-go: `rlvm-truffle` is not built and the Truffle runtime is not linked" (0.5) | the module is built, tested and off by default (staging switch `t2`) | the brief of this wave; the Truffle runtime was already linked into the image for the launcher's probe |

## 2. Defects found while building it (all in this module; none changed a verdict)

1. **An instruction node executed on a loop-exit path** (first compilation attempt): `return instr[i].exec(..)`
   inside the exploded loop is code after the loop exit, where the instruction index is a phi, not a
   constant: partial evaluation could not inline the virtual call and the virtual frame escaped
   ("should not be materialized"). Every compilation failed, i.e. the tier ran nothing. Fixed: `EXIT`
   instructions leave through one shared exit state inside the loop.
2. **Native image: setter helpers** (`T2Bridge.setReg / setX / setXp / pop`): "reachable during compilation,
   but was not seen during Bytecode parsing: ..%%D". A method whose body is one store is inlined before
   the image's analysis, and its deoptimisation target is then missing. Reproduced with a 60-line program
   (GraalVM CE 25.5.5.0.3, `-O3`). Fixed by not having such methods: array stores at the call site,
   pointer writes behind the operations' boundaries and in `T2Tier.run`.
3. **Native image: the compilation listener** threw `IllegalAccessError` on
   `com.oracle.truffle.compiler.TruffleCompilerListener$CompilationResultInfo` (the Truffle jars are
   modules in an image), which Truffle reported as a failed compilation: no block was ever installed in
   the first image. Fixed: `--add-exports` for that package too, and every listener callback catches what
   its bookkeeping throws.
4. **Lost promotions**: the runtime's compile queue holds a call target weakly. Nothing else referenced a
   root between submission and compilation, so a collection in that window dropped the task silently: 17
   of 89 promoted blocks of reachability (the earliest, i.e. the hottest) were never compiled and stayed
   "pending". Found because the warm tool waited for them. Fixed: the promotion record holds the root until
   it is installed or failed; a dequeued compilation is a failed promotion.

## 3. Correctness (all with the final nodes; JVM unless stated)

Tier equivalence = two runs of one export, tier off and on, ONE machine session each, compared after
every unit: outcome, ticks, and a digest of every record word (identity AND memo words) and every run
word of the session (`T2Bridge.digest`). Equal digests = the same value ids with the same contents.

| Run | Promotion | Result |
|---|---|---|
| init-prelude, `code,casetrees,tele`, ELAB_AT 1 | every block at its first entry, compiled synchronously | 2,121 constants; 3,937 blocks compiled, 1 refused by Graal ("code is too large", 846 words: stays in the stepper); 33,401 compiled activations; digests, ticks, outcomes identical at every unit; 0 deoptimisations |
| tutorial + bugs (175 files), `code,casetrees,tele`, ELAB_AT 1 | the same | 169 files identical at every unit (25,907 compiled activations); the other 6 are refused by the loader before any engine runs; a file whose declarations never enter a block has no tier and is compared all the same (work/t2/gates/tiereq-tutorial-bugs.txt) |
| 4 tutorial files; 2 of them under host depth 0; variants (registers in the window, all instructions through the stepper's body, no resume entries) | the same | identical (fast group) |
| perf/: magma-list-pair-n7, proj-app-lazy-delta, discarded-argument-match, fueled-chain, shared-subterm, folded-constant-first, args-before-unfold; two of them under host depth 0 | the same | identical; e.g. proj-app-lazy-delta: 64 blocks, 800,219 compiled activations, 204,726 frames written |
| reachability (4,413 units), perf/unfold-before-literal-fallback, proj-applied-struct, magma-list-pair-n21, `code,casetrees`, ELAB_AT 10 | production path: rent 0.05, background compilation | identical; reachability 78 blocks / 3.05 M compiled activations / 348,827 frames; unfold-before-literal-fallback 6.2 M compiled activations / 3.3 M frames (deep recursion: the suspension and resume protocol at work) |
| Init (58,547 units), `code,casetrees`, ELAB_AT 10 | the same | identical; 331 blocks, 7.2 M compiled activations |

Differential gate against the reference engine with the tier on (`testkit diff --engine machine
--launcher work/t2/dist-n2/bin/rlvm-jvm`, i.e. the real launcher and driver):

| Corpus | Switches, promotion | Units compared | Differences |
|---|---|---|---|
| tutorial + bugs, 175 exports, `-j 4` | `code,casetrees,tele,t2`, ELAB_AT 1, every block compiled at first entry | 1,342 of 1,342 (1,311 both accept, 31 both reject), 0 declines | **0** (SOUNDNESS 0, COMPLETENESS 0) |
| reachability, cedar-hot-ax, Init, Std, `--threads 4` | `code,casetrees,t2`, rent 0.05, background (Std: 559 blocks promoted, 391 installed when the run ended, 7.7 M compiled activations, 0 deoptimisations) | 170,322 of 170,322, 0 declines | **0** |

Reports: `work/t2/gates/`. Fail-safe paths exercised: no optimising runtime
(`-Dtruffle.UseFallbackRuntime=true`: one warning, the tier disables itself, exit status and verdict
unchanged); a compilation Graal refuses; an export that is rejected with the tier on.

## 4. How the measurements were taken, and what they can show

Two kinds of rows, kept apart everywhere below.

- **Container rows** (`work/t2/measure.tsv`, tool `work/t2/m.py`, batches `work/t2/batch-cold.sh` /
  `batch-warm.sh` / `container-all.sh`): arena-shaped container (`bench/arena-docker.sh --arena-limits`),
  `perf stat` (CPU time, instructions:u) + GNU time, each batch under ONE hold of
  `work/perf-history/.ladder.lock`. On 2026-11-24 the lock queue was 6-10 batches long and waits of 30-60
  minutes were normal; several containers ran at once all afternoon, so even these rows are not from a
  quiet host. Instruction counts are the reliable column.
- **Host rows** (`work/t2/host-warm.tsv`, tool `work/t2/host-warm.py`): the multi-pass tool
  `rlvm.truffle.WarmMain` (test tree; `bin/warm` of a `build-dist.sh --warm` distribution, or the same class
  on the JVM) run directly on the host, configurations interleaved, NOT under the lock: one process, one
  engine, pass 1 cold, later passes warm with fresh sessions. The number to read is the process CPU time of
  a warm pass (`/proc/self/stat`, 10 ms resolution). The host was at load average 70-250 throughout.
  **What these rows can show is very little.** Inside one process the same configuration varies by about
  +-15% from pass to pass (JVM, reachability, base tier, passes 3-8: 1700 - 2220 ms); ACROSS processes
  minutes apart the CPU time of identical work moves by a factor of 1.5 - 2 (native `dist-n2`, base tier,
  reachability: cold / warm 3920 / 3470-3930 ms at 15:44Z and 6040 / 5540 ms at 16:04Z; `dist-n3`: 3510 /
  2830-3110 ms at 16:00Z and 2060 / 1880-2350 ms for a configuration that does strictly more work two
  minutes later), and the load average does not predict the direction; part of it was my own host runs
  overlapping. Rows of one label were taken sequentially over many minutes, so a difference between two
  configurations of a host run is NOT evidence about the configurations. Only back-to-back pairs (label
  `Pn`, below) are read as comparisons, and only for their sign.

`WarmMain` is not the driver: no admission pipeline, no re-check, no pipelined loading; the workers of a
warm pass claim chunks of 16 units from a shared counter. It exists because the driver had no `-X passes=N`
when these runs were made (requested by the orchestrator at 14:08Z).

Distributions: `work/t2/dist-n2` (machine jar of 14:46Z; tier WITH defect 4 of section 2: about one promoted
block in five was never compiled) and `work/t2/dist-n3` (the same sources plus the fix of defect 4, the
governor profiles and `minInstr`). The tree is two small changes ahead of `dist-n3`: the default of
`rlvm.t2.timing` (64 in the image, 4096 in the tree) and the model for a block without a timed sample.
Every tier row of `dist-n3` passes `-Drlvm.t2.timing=4096` explicitly, so the rows describe the tree's
default timing; the model fallback was not in any measured image.

**Discarded**: every tier row measured before 15:54Z (labels without a timing argument: the first JVM and
native host runs, `Hj1`, the first `Mj1` rows, `BenchTest` runs). They carry the cost of the heat
accounting's timing (next section) mixed with whatever the tier itself costs, so they cannot be used for
either. That does not mean the tier is neutral: nothing measured on the host so far says whether compiled
blocks are faster or slower than the stepper.

`dist-n2` and `dist-n3` contain different machine jars (`a089fd2aef4b` of 14:51Z, `a5313e0b6c32` of 15:38Z).
The newer one declines 8 of the 4,413 units of reachability by itself (`UNSUPPORTED_INPUT`, "eager
reduction (an argument 'eagerReduce _ _')", first at unit 2373), in the base tier, without `t2`: rows of
the two distributions measure different work and are never compared.

**Which machine jar the correctness runs of section 3 used.** `rlvm-machine` was republished about forty
times between 14:00Z and 16:08Z (`work/sandbox/publish.log`), so there is no single "machine jar of this
wave":

| Run | Machine jar |
|---|---|
| both differential gates (through `dist-n2`) | `a089fd2aef4b`, staged 14:51:59Z - one known jar |
| tier equivalence, init-prelude, every block compiled (ended 14:40Z) | the jar published when the run started, about 14:23Z; hash not recorded |
| tier equivalence, reachability + heavy perf/ (ended 15:05Z) | as published about 15:01Z; hash not recorded |
| tier equivalence, Init (ended 15:35Z) | as published about 15:30Z; hash not recorded |
| tier equivalence, tutorial + bugs digests (ended 15:49Z) | as published about 15:37Z; hash not recorded |
| default test group on the final tree (15:58:51Z - 16:01:25Z) | as published 15:42Z or 15:59Z (a publication landed while the run started); hash not recorded |

Inside one run the tier-on and the tier-off machine are the same jar, so each row is a valid tier
equivalence result for its snapshot; the tier was thus compared against five different states of the
machine. None of them is the jar in `.toolchain/m2` at the time of writing (`4023ec9211dd`, 16:07:38Z),
and the differential gates were NOT repeated on a jar later than 14:51Z.

## 5. Experiments (kept / rejected, with numbers)

1. **Heat timing rate 64 -> 4096: KEPT (default of the tier), size of the effect NOT measured.** The tier switches the machine's heat
   accounting on (`Tier.heat(engine)`); at its default of one timed entry in 64, a timed entry also times
   every block entered beneath it. Host rows, native image `dist-n2`, reachability, `code,casetrees`, one
   worker, process CPU of warm passes 2-4 (label `Tn1`):

   | configuration | warm passes, ms |
   |---|---|
   | base tier, no heat accounting | 3470, 3800, 3930 |
   | tier attached, nothing promoted (`rlvm.t2.max=0`), timing 64 | 5590, 5880, 4540 |
   | the same, timing 4096 | 3980, 4250, 3760 |
   | tier promoting (134 blocks installed, 8.0 M compiled activations), timing 64 | 4810, 6710, 4830 |
   | tier promoting (122 blocks installed, 15.5 M compiled activations), timing 4096 | 7060, 6100, 5940 |

   **Read this table for its mechanism, not its numbers.** The five rows were taken between 15:44Z and
   16:01Z, 2-17 minutes apart, under the host conditions described in section 4. I first read rows 1-3 as
   "the accounting costs 30-50%" and reported that to M7 (15:56Z), then read row 5 as "the promoting tier
   is 55-80% slower"; neither magnitude is established, and the first was withdrawn in requests.log at
   16:06Z. A second set on `dist-n3` (label `Vn1`, 16:00-16:02Z) shows base 2830-3110 ms, timing 64
   1910-2700 ms, timing 4096 1880-2350 ms while the host was emptying: no usable difference. What stands
   is the reading of `Tier.exec`: while a sampled entry is on the stack, every block entered beneath it is
   timed (two clock calls), so the share of timed entries grows with nesting depth, 1 - (63/64)^depth. The
   tier asks for timing 4096 because that cannot hurt; whether 64 hurts, and how much, needs instruction
   counts from the container.
2. **Promotion record holds the root: KEPT** (defect 4): without it 17 of 89 promoted blocks of
   reachability were never compiled (native, trace of 15:33Z).
3. **No setter helpers in compiled code: KEPT** (defect 2): the precondition for an image to build at all.
4. **Minimum block size (`rlvm.t2.minInstr`): implemented, default 0, NOT measured.** The idea: entering
   compiled code has a fixed cost, so a block of two instructions may lose where a block of twenty gains.
   The only direct observation is from the heat accounting's samples of three tiny LEAF blocks in a native
   run (2 instructions each: 45 -> 55, 45 -> 54, 82 -> 87 ns per entry, stepper -> compiled), which points
   that way; the host rows with `minInstr` 8 / 16 are unusable (section 4). No default was chosen from data.
5. **Probes inlined (`rlvm.t2.inline` = 1 / 2): implemented behind a class constant, default 0, NO RESULT.**
   Partial evaluation continues into `Values.peek` / `Code.ctorIndex` (1) and `Values.mkApp` /
   `Values.mkConst` (2) instead of stopping at a boundary. One attempt on the JVM (`perfTests` with every
   block compiled at its first entry, `inline=2`) ran for 27 minutes without finishing its first file and
   was stopped; it was never built into an image and never run with the production promotion. Whether it
   compiles in reasonable time, whether an image builds with it, and what it gains are all open.
6. **Variants `rlvm.t2.slots=false`, `rlvm.t2.nodes=false`, `rlvm.t2.resume=false`: tested for
   equivalence (fast group), NOT measured for speed.** Compile cost with resume entries is visible in the
   reports (a `TELE` block of 16 instructions has 12 entries and 7.3 KB of code) but was not compared
   against `resume=false`.
7. **Process clock read moved out of the listener's common path: KEPT, effect not measured.**
   `T2Engine.consider` read `/proc/self/stat` (the governor's process CPU time) before the rent test, i.e.
   on the worker thread at every listener call: every `period` entries of every block that is not promoted
   yet. It is now read only for a block that has passed the rent test. Found by reading the code while
   planning a measurement, 16:13Z; in the tree, not in `dist-n3`.
8. **Compile cost model: `rlvm.t2.cost1` 0.15 -> 2.5 ms per instruction: KEPT.** The model is used until
   three blocks have been compiled; measured costs are an order of magnitude above the old default:

   | Log (compiler thread's CPU clock from start to success of each compilation) | Blocks | median | p90 | max | per instruction | code per instruction | instructions / entries per block |
   |---|--:|--:|--:|--:|--:|--:|--:|
   | JVM (libgraal), init-prelude, every block at first entry, `code,casetrees,tele`, 14:27-14:40Z, host load 200+ (`/tmp/claude-t2/prelude-all.log`) | 3,937 | 12.9 ms | 64.9 ms | 1,125 ms | 2.16 ms | 849 B | 12.9 / 6.9 |
   | native `dist-n2`, reachability, `code,casetrees`, rent 0.05, 15:33Z, host load 90-140 (`/tmp/claude-t2/warm-trace.log`) | 72 | 15.0 ms | 42.7 ms | 126.6 ms | 3.05 ms | 1,184 B | 6.9 / 1.4 |

   The two rows are different populations of blocks (a library prelude with telescopes against the hot
   blocks of closed evaluation) on a contended host: they give the order of magnitude of one compilation
   (about 15 ms, 2-3 ms per instruction, about 1 KB of machine code per instruction), not a comparison of
   the platforms. A resume point is an entry: the prelude's blocks have 6.9 entries on average.
