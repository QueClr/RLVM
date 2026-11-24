# rlvm-truffle - the T2 tier of the machine

Owner: T2. Package `rlvm.truffle` (+ one bridge class in package `rlvm.machine`, see "Bridge").
Design: `docs/design/ISA.md` sections 0.5, 8.1, 8.3 - 8.6. Hook: `rlvm.machine.Tier` (M7).
Measurements, experiments and the go / no-go: `NOTES-T2.md`.

RLVM is "a GraalVM/Truffle based Lean kernel". Until this module existed, Truffle was linked into the
image and compiled nothing. This module is the Truffle tier for real: the guest language `rlvm` whose
programs are the **code blocks of the machine** - the judgment chains of constants (`TELE(c)`: application
inference, `BODY(c)` / `RULE(r)` / `RULES(c)`: reduction, and the blocks of binders), not Lean terms
(OWNER-NOTES 5). A block that the governor promotes becomes one Truffle `RootNode`, is compiled by Graal
on a background thread, and from then on its activations run as machine code that calls the SAME
operations as the stepper (one semantics).

It is **off by default** and changes nothing unless selected:

```
rlvm -X engine=machine -X machine.switches=code,casetrees,t2 FILE.ndjson       # the tier is the staging switch `t2`
```

Without this jar on the class path, without the switch, in strict mode, or without an optimising Truffle
runtime, the machine runs exactly as before. Whatever fails inside the tier (no runtime, a compilation
Graal gives up on, a block the tier cannot decode) leaves the block in the stepper: the tier can change
speed, never a verdict or a value id.

## Build, test

The module is not in the parent pom's `<modules>` yet (line to add after `rlvm-machine`:
`<module>rlvm-truffle</module>`; `scripts/build.sh` copies engine jars that `rlvm-main` does not depend on
in its loop `for eng in rlvm-machine`, which would become `for eng in rlvm-machine rlvm-truffle`).

```
. ./env.sh
$MVN -o -q -f rlvm-truffle/pom.xml test                                    # default group
$MVN -o -q -f rlvm-truffle/pom.xml test -Dgroups=fast                      # 3 tests, a few seconds
$MVN -o -q -f rlvm-truffle/pom.xml test -Dgroups=slow -DexcludedGroups= -Dtest='TierEquivalenceTest#files' \
     -Dt2.files=work/exports/reachability.ndjson                           # tier equivalence on a corpus
$MVN -o -q -f rlvm-truffle/pom.xml install -DskipTests                     # the jar into .toolchain/m2
work/t2/build-dist.sh [--native] [--warm] NAME                             # a distribution with the tier in work/t2/NAME
```

The Truffle artifacts carry `${graalvm.version}` of the parent pom (= `GRAALVM_VERSION` of the JDK), as
in `rlvm-cli` and the spikes; `truffle-runtime` is a compile dependency because promotion uses
`OptimizedCallTarget.compile(true)` / `isValid()` / `callIndirect` and the runtime's compilation
listener (docs/research/10 section 5.4: internal API of the optimising runtime).

## How it is put together

```
rlvm.machine.Tier (M7)            the hook: Code.go / Code.run offer every activation to the machine's tier
  T2Factory                       the service (META-INF/services/rlvm.machine.Tier$Factory): one T2Tier per machine with switch t2
  T2Tier extends Tier             per machine: run(m, blk, pc, rb, d) -> compiled code, or Op.MISS (one array load)
  T2Engine                        per machine engine: heat listener, governor, table of installed blocks, report
  T2Runtime                       per process: the promoter thread, the one Truffle engine, compile + listener
  Decoded                         a block taken apart: instructions, successors, registers read / written, liveness
  BlockRoot extends RootNode      one per promoted block: entries, the exploded loop over the instructions
  Instr.*                         one node class per instruction kind (own code), Instr.Step (the stepper's body)
  rlvm.machine.T2Bridge           accessors + the operations behind @TruffleBoundary
  RlvmLanguage                    TruffleLanguage "rlvm"
```

### Hand-over (the hook)

`Tier.Factory.create(m)` is asked once per machine that runs with switch `t2`. `T2Tier.run` looks the
block up in the engine's table of installed blocks; no entry, or no compiled entry at the offered pc, or
code that is no longer valid: `Op.MISS`, the stepper runs the activation. Otherwise the root is called
with the `T2Tier` itself as the only argument: it carries the entry registers (`entry`, `rb`, `d`) and
receives `result`, so entering compiled code allocates nothing. A result (not a signal) pops the window,
as `RET` does.

### Promotion: the governor (ISA 8.5, in CPU time)

The machine's heat accounting (`Tier.Heat`: entries per block and session; one entry in 64 timed, self
time = without the blocks entered inside) calls `T2Engine.hot` on the worker thread every `period`
entries of a block by that worker. A block is promoted iff

```
spent(b) >= rent * cost(b)                              spent(b) = entries(b) * selfNanos(b) / timed(b), all workers
                                                        cost(b)  = expected compile CPU: cost0 + cost1 * instructions,
                                                                   after 3 compilations the measured CPU per instruction
compileCpu + pending + cost(b) <= share * processCpu    compilation never exceeds a set share of the process CPU time
promoted < max, instructions <= maxInstr, role allowed, the block is decodable
```

i.e. rent-to-own: a block is compiled after it has consumed its compile cost in the base tier. Promotion
runs on the promoter thread (a daemon: builds the root, `compile(true)`); Graal compiles on its own
background thread(s); the listener installs the block when its code is valid. Workers never wait
(`rlvm.t2.sync=true` exists for tests). Nothing of Truffle is initialised before the first promotion.

### A root

- **Nodes**: one node per instruction with the operands decoded into final fields; under partial
  evaluation the opcode dispatch, the operand modes, register numbers and successor pcs are constants.
  Own code: `MOV, PEEK, NSUCC, MKPROJ, MKAPP, KCONST, KSORT, KLIT, MKLAM, MKPI, MKSUSP, KVAL, FIELDS, BKIND,
  BEQ, BNE, SWITCH, RET, TRET, APPLY, TAILAPPLY, WHNF, FORCE`; each mirrors the instruction body of
  `Code.java` statement by statement and calls the same operations. Through the stepper's own body
  (`Instr.Step` = `Tier.step`, one instruction on the window): `BUNREAD`, `MATCH`, the four phases of `TARG`,
  the slow paths of `KVAL` (cache miss) and `FIELDS` (Nat views), and **any opcode this module does not
  know** - so a new instruction of the machine is executable at once; a block that has one keeps all its
  registers in the window.
- **Registers** are static `int` frame slots (values of the compiled code), except the ones pinned to
  the machine's window: in a `TELE` block the telescope variables and the eight site registers, which
  `TARG` / `MATCH` read and write through the stepper's body. A backward liveness analysis gives, per
  instruction, the slot registers that are live.
- **Entries**: one per entry of the block's entry table and one per resume point. An entry loads the
  slot registers that are live there from the window.
- **Suspension / resume (ISA 8.4)**: a class-j instruction (`APPLY`, `WHNF`, `FORCE`, `KVAL`, `TARG`, ..) whose
  judgment answers a signal writes the slot registers that are live at its resume point back to the
  window, writes the frame `ACT [blk, pc, dst, ..]` (`Tier.suspend`) and returns the signal. The driver
  later stores the awaited result into the window and offers the activation at the resume pc - which is
  an entry of the root (or, with `rlvm.t2.resume=false`, an activation the stepper continues: the window
  is the complete state either way). Tested with host depth 0, where every judgment suspends.
- **The loop**: `BlockRoot.run` is a loop over the instruction index exploded with merging
  (`MERGE_EXPLODE`); the block's control flow graph becomes the control flow graph of the compiled code,
  backward jumps included. An `EXIT` instruction leaves through one shared exit state, because code after
  a loop exit no longer has a constant instruction index.
- **No deoptimisation** (ISA 8.4): no profile, no assumption, no node rewriting, no
  `transferToInterpreter`; every kind test is a branch, every rare path a call; a boundary passes
  exceptions on. `engine.MaximumCompilations=1`. The report counts deoptimisations (0 in every run so far).

### Bridge

`rlvm-machine` has no Truffle dependency, so it cannot say where partial evaluation stops; without a
boundary PE would inline the whole machine into every block. The boundary is therefore drawn here:
`rlvm.machine.T2Bridge` (shipped in this jar, in package `rlvm.machine` because the operations and the
fields of `Machine` are package-private; agreed with M7 and M6, requests.log 13:36Z - 13:40Z) has
accessors that PE inlines and the operations behind `@TruffleBoundary`. Its three composite operations
(`closure`, `ctor`, `kvalHit`) and `mkApp` / `apply` (which claim and release the scratch operands) are the
bodies of `Code.mkClosure`, `Code.switchOn`, `Code.kval`, `Code.mkApp`, `Code.apply` without their operand
decoding. The signatures it relies on (`Code.val / raw / isNat / ctorIndex / icGet / susp`, the
operations of `Values`, `Runs`, `Levels`, `Lits`, `Eval`, `Reduce`) are part of the hook: M7 announces a
change in requests.log; the tier-equivalence tests catch one that slips through.

### Native image

- `META-INF/native-image/rlvm/rlvm-truffle/native-image.properties`: build-time initialisation of
  `rlvm.truffle`, and `--add-exports` of `com.oracle.truffle.runtime` and `com.oracle.truffle.compiler`
  (in an image the Truffle jars are modules).
- No setter method may be called from compiled code: a method whose whole body is one store is inlined
  before the image's analysis and its deoptimisation target is then missing (build error "reachable
  during compilation, but was not seen during Bytecode parsing: ..setXp%%D", GraalVM CE 25.5; reproduced
  with a 60-line program). Stores into the machine's arrays are therefore written at the call site
  (`T2Bridge.regs(m)[rb + i] = v`), and the scratch pointer / register stack pointer are written behind
  the operations' boundaries and by `T2Tier.run`.
- Runtime compilation is the image's own (`TruffleFeature`; 1,816 runtime-compiled methods with the tier).

## Settings (system properties; `-Dkey=value` on either launcher)

| Property | Default | Meaning |
|---|---|---|
| `rlvm.t2.mode` | `cpu` | `cpu`: the governor above with rent 1.0, share 0.02, max 256, period 1024, one compiler thread. `warm`: the steady state is the metric and warm-up is not charged (OWNER-NOTES 12): rent 0.05, no share cap, max 4096, period 256, two compiler threads |
| `rlvm.t2.rent`, `.share`, `.max`, `.period`, `.threads` | by mode | the single knobs of the governor |
| `rlvm.t2.minInstr`, `.maxInstr` | 0, 256 | a block with fewer / more instructions is never promoted (entering compiled code costs about what the stepper spends on a few instructions; Graal refuses very large blocks) |
| `rlvm.t2.timing` | 64 | one entry in N is timed by the machine's heat accounting |
| `rlvm.t2.inline` | 0 | experiment, fixed at class initialisation (for an image: at image build): 1 = PEEK and the constructor test of SWITCH compiled into the block, 2 = also the probes of MKAPP / KCONST (NOTES-T2 section 5) |
| `rlvm.t2.cost0`, `.cost1` | 3.0, 0.15 | compile cost model before anything was compiled (CPU ms; per instruction) |
| `rlvm.t2.at` | 0 | N > 0: promote at N entries regardless of rent and governor (tests, sweeps) |
| `rlvm.t2.roles` | `tele,body,rule,rules,sub` | promotable roles |
| `rlvm.t2.slots` / `.nodes` / `.resume` | true | registers in frame slots / own code per instruction / resume points compiled as entries |
| `rlvm.t2.sync` | false | the worker that crosses the threshold waits for the compilation (tests) |
| `rlvm.t2.trace` | false | one line per promotion / compilation on stderr |
| `rlvm.t2.report` | - | FILE: the report (below), rewritten at every change and at exit (`RLVM_EXIT=exit`) |
| `rlvm.t2.opt.X` | - | option X of the Truffle engine, e.g. `rlvm.t2.opt.engine.TraceCompilation=true` |

Report: totals (promoted, installed, failed, compile CPU and its share of the process CPU, compiled
activations, deoptimisations, promotions the governor held back), then per block: compile CPU ms, code
bytes, instructions, entries before / after installation, and the self time per entry before and after
as the heat accounting sampled it. The last two columns are NOT a reliable saving: the self time of a
block includes the generic judgments it calls, whose cost changes over a run (memo hits); the break-even
numbers of NOTES-T2 come from whole-run differences instead.

## Tests

| Test | Group | Content |
|---|---|---|
| `TierEquivalenceTest.smallFiles` | fast | four tutorial files, every block compiled at its first entry (synchronously): equal outcome, equal heap digest (every record word incl. memo words, every run word), equal ticks at every unit, tier on against off |
| `.smallFilesWorklist` | fast | the same under host depth 0: every judgment a block calls suspends it; every class-j node writes its frame, every resume entry is entered |
| `.variants` | fast | registers in the window; every instruction through the stepper's body; no resume entries |
| `.perfTests`, `.perfTestsBackground` | default | evaluation-heavy `perf/` tests (magma-list-pair-n7, proj-app-lazy-delta, ..), all blocks compiled / the production promotion in the background |
| `.files` | slow (tool) | any export: `-Dt2.files=.. [-Dt2.switches=..] [-Dt2.depth=N] [-Dt2.variant="k v .."]` |
| `BenchTest.bench`, `.scan` | slow (tools) | interleaved warm passes, tier off / on, one worker; activations per file of a directory |
| `WarmMain` | (main class in the test tree) | the multi-pass measuring tool for both platforms: `bin/warm` of a `build-dist.sh --warm` distribution |

Differential gate against the reference engine with the tier on: `rlvm-testkit/bin/testkit diff --engine
machine --launcher work/t2/DIST/bin/rlvm-jvm .. -- -X machine.switches=..,t2 ..` (results in NOTES-T2).
