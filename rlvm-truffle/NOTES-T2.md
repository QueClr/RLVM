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
| tutorial + bugs (175 files), `code,casetrees,tele`, ELAB_AT 1 | the same | TUTORIAL_BUGS_DIGEST |
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
