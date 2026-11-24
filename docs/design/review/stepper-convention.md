# The host-level control convention of the RLVM machine

Decision record for RECONCILIATION B4 (critic finding C2.1), from the measurements of `spikes/stepper`
(README and `results/tables.md` there). Sections 2 and 3 are written as rules for the implementers of
`rlvm-machine`; they replace the "Decision" paragraph of ISA 8.2 and refine its candidate (ii).

## 1. Decision

**The machine runs judgments by bounded host recursion and writes frames lazily (convention H, host depth
bound 64).** A sub-judgment is a Java call while at most 64 judgments are on the host stack; a judgment is
written once, as a chain of small step methods whose parameter lists are the frame payloads of ISA 1.3; a
frame is written only when the bound is passed (one judgment in 9,000 on Init, one in 4,000 on Cedar), and
from then on `W` is run like any worklist. With the bound set to 0 the same source *is* the worklist machine
of ISA 1.3. No fused family loops, no reserved frame slots, no frame for a `TRY` that completes.

CPU time relative to plain host recursion R, same operations, same results (geometric mean over four
workloads: Init and Cedar, declaration types only and types + definition values, 16 MiB sessions; first
number: ratio of the minimum pass times; in brackets: median of the per-round paired ratios; aarch64, Docker
with the arena limits; the same code measured twice differs by up to 3 points, see section 6):

| convention | native image CE | native CE, steps force-inlined | JVM, Graal JIT | Graal JIT, steps force-inlined | JVM, C2 | C2, steps force-inlined |
|---|--:|--:|--:|--:|--:|--:|
| W0: ISA 1.3 as first written (`run()` -> `X.step(m)` -> `switch (st)`, registers in fields) | 1.35 (1.32) | | 1.31 (1.28) | | 1.57 (1.50) | |
| W1: fused loop per family, registers in locals, dense dispatch, inert nests in one loop (candidate (i) of ISA 8.2) | 1.11 (1.10) | 1.13 (1.12) | 1.03 (1.02) | 1.00 (1.02) | 1.17 (1.14) | 1.16 (1.15) |
| **H64: bounded host recursion, D = 64** | **1.04 (1.06)** | **1.05 (1.04)** | **1.05 (1.06)** | **1.03 (1.04)** | **1.02 (1.02)** | **1.03 (1.01)** |
| Hi64: H64 with the inert-nest loop of W1 | 1.02 (1.05) | 1.06 (1.07) | 1.01 (1.02) | 0.99 (1.02) | 1.04 (1.03) | 1.05 (1.02) |
| H8 / H32 / H256 / no bound | 1.14 / 1.07 / 1.08 / 1.07 | 1.14 / 1.04 / 1.03 / 1.05 | 1.13 / 1.05 / 1.03 / 1.03 | 1.12 / 1.03 / 1.06 / 1.01 | 1.12 / 1.00 / 1.02 / 1.08 | 1.13 / 1.04 / 1.02 / 1.02 |

- The target of RECONCILIATION B4 (at most +15% CPU over plain recursion on the declaration types of Init and
  Cedar) is met by H on every runtime and both corpora: +0% to +7% over the 24 estimates (six runtime
  configurations, two corpora, two estimators; per-workload tables in `spikes/stepper/results/tables.md`,
  section 2). W0 misses it everywhere (+26% to +51%). W1 meets it on the Graal JIT (+1% to +4%), is at +10%
  to +18% on the native image and at +15% to +18% on C2.
- H is the only candidate whose cost does not depend on which compiler runs it, which matters because the
  platform gate (JVM with JIT against native image) is still open.
- Instruction counts overstate every candidate: W1 executes 20% more instructions than R and H64 15% more
  (6% with the steps inlined) in the native image, for +10% and +6% CPU. The instructions a convention adds
  are cheap ones; what costs is the data-dependent dispatch of an explicit stack. Reading the Init types
  workload with `perf stat` (`results/counters.txt`, net of set-up): W1 takes 25% more branch misses than R,
  W0 27%, the inert-nest loop alone 14%, H64 none.
- Frames written per judgment: W0 0.19, W1 0.064, H64 0.00011 (Init, types + values).
- 1,000,000-deep application, binder and mixed nests run under H64 on a 512 KiB thread stack (64 KiB is
  enough in the native image, 128 KiB on a cold JVM) with the same results as R, which needs a 256 MiB stack
  for them (128 MiB overflows).
- Budgets are exact and rollback works without materialising anything: `CONV` under a `TRY` gives the same
  outcome and the same tick count per root with D = 0, D = 64 and no bound (section 2, H11).


## 2. The convention, as rules

Names: `m` is the worker's `Machine`; a *judgment* is an operation of ISA section 4 that may need
sub-judgments (`EVAL`, `WHNF`, `CONV`, `INFER`, ...); an *operation* of the library `Ops` never does.
`D` is the constant `Op.HOST_DEPTH` (rule H9).

### H1. The specification does not change

The transition system of ISA 1.3 stays the specification: the frames, their payload layouts, `call`, `ret`,
`tail`, `open`, `close`, `try`, `cut`, the observables. What changes is *when a frame exists in memory*:
a judgment that is running on the host stack has no frame; its frame `op[k](payload)` is written at the
moment the judgment is suspended, and it then has exactly the layout of 1.3. `W` holds the suspended part
of the computation, the host stack the running top of it (at most `D + 2` judgments).

With `D = 0` every `call` of 1.3 writes its frame and the driver loop is the stepper: the same source is the
worklist machine. That build is a test configuration (H12), not a second implementation.

### H2. A judgment is a chain of step methods, written once

- One static method per state of the judgment: the *entry step* `J(m, operands.., d)` is state 0; there is
  one *continuation step* `k_Jk(m, payload.., r, d)` for every resume state `k` (the prefix `k_` is how the
  build finds them, H10).
- The parameters of a continuation step are the payload words of the frame `J[k]` in the order of 1.3, then
  the result register `r` (the ISA's `A`: the result of the sub-judgment that was awaited), then the host
  depth `d`. A step may therefore be entered from two places with no difference: by the step before it, on
  the host stack, or by the driver, from a frame.
- A step runs from its resume point to the next sub-judgment call or to the end of the judgment. It returns
  the judgment's result as an `int` (value ref, level id, truth value) or a signal (H5).
- A step reads its parameters, the stores and the machine stacks it owns (H7). It never reads `W`.

### H3. Calling a sub-judgment

```java
int r = K(m, x.., d + 1);                                      // call K(x) => k
return r >= SIG ? m.sigN(r, KEY_J_k, live..) : k_Jk(m, live.., r, d);
```

- `live..` are the payload words of `J[k]`: what the rest of the judgment needs. `m.sigN` (one per payload
  size N) is out of line: on SUSP it writes the frame `J[k](live..)` and returns SUSP; on any other signal
  it returns the signal unchanged.
- *Tail position* (the `tail` rule: the judgment owns no open binder and no scratch): `return K(m, x.., d + 1);`
  with no test.
- *Inside a loop* (spines, telescopes, the `WHNF` and `LAZYD` loops): the loop is a method whose parameters
  include the loop variables; the call site is `if (r >= SIG) return m.sigN(r, KEY_J_k, live.., i);`; the
  continuation step finishes the interrupted iteration and calls the loop method with the next index
  (template, `args` / `k_args1`). A resumed loop costs one host frame, not one per iteration.
- A call that the inline fast path of `K` would answer is still written as a call of `K`: the fast path is
  the first lines of `K`'s entry step (H4). Where a caller wants the answer without the call it tests the
  cheapest case itself (`x == y`, a flag bit) and calls otherwise.

### H4. The entry step: fast path, tick, depth test, body - in this order

```java
static int J(Machine m, int x, int d) {
    // 1. inline answers (identity, flag, memo, cache): return at once. No tick, no event, no frame.
    // 2. if (--m.fuel < 0) return m.exhaust();                       TICK (H11)
    // 3. if (d > D) return m.spillN(KEY_J_0, x..);                   too deep: become the frame J[0](x)
    // 4. the body (H3)
}
```

Step 1 is the `inline` transition of 1.3, steps 2-4 are the `call`. `spillN` gives the tick back
(`m.fuel++`): the frame is run later by the driver, which enters `J` again. The `call` counter of the frame
regression test (ISA section 11) is incremented between 2 and 3 in statistics builds; it does not depend
on `D`. Leaf cases that need no sub-judgment return before step 3 and can never be frames.

### H5. Signals

Three `int`s that no judgment returns as a result:

| signal | meaning | what a step does with it |
|---|---|---|
| `SUSP = 0x7FFF_FFFF` | the judgment just called is suspended; its frames are in the side buffer | writes its own continuation frame (`sigN`), returns SUSP |
| `ABORT = 0x7FFF_FFFE` | the budget of the innermost `TRY` ran out, or a kernel error / recoverable limit was raised (`m.err` holds the code) | returns it; writes nothing |
| `FATAL = 0x7FFF_FFFD` | `fault(E)` of ISA 1.4 | returns it; writes nothing; `TRY` does not intercept it |

`SIG = FATAL`; `r >= SIG` is the only test on the normal path. It is correct for the value encoding of ISA
2.1 (small naturals are negative `int`s, record refs are below 2^28, truth values are 0..3); `Op.java` must
assert that level, list and substitution ids stay below `SIG`. (The spike's values use the sign bit
differently, so its signals are the two smallest `int`s and its test is `r <= SIG`.)

### H6. Frames, the side buffer and the driver

- Frame layout: payload `p0 .. p(n-1)`, then the header `key:12 | 0:4 | n:16` with `key = op << 4 | st`,
  as in ISA 1.3. The payload of `J[k]` is the parameter list of `k_Jk` (H2).
- An unwind writes frames innermost first. `sigN` and `spillN` write them into the *side buffer* `m.S`,
  which grows downwards, so that its contents are in worklist order. A buffer of `(D + 3) * maxFrameWords`
  words never overflows.
- `Machine.drive(r)` is the only code that knows `W`. With `r` the outcome of a judgment called at depth 0:

```
drive(r):  loop:
    r is a result and W is at the caller's level:   return r
    r == SUSP:    W[wp ..] := S[sp ..];  wp += length;  sp := end of S          one arraycopy
    r == ABORT:   cut W to the innermost TRY frame (or to DECL: REJECT / DECLINE with m.err)
    r == FATAL:   cut W to DECL: DECLINE with m.err, session reset
    pop the top frame f = op[k](p);  r := Family(op).resume(m, key, W, base of p, r)     entered with d = 0
```

- `resume` is one `switch` on `key` per family; each case calls the step method with the payload words as
  arguments (template). The driver's own loop is a few lines.
- `DECL` is the Java method that calls `drive`; its locals are the declaration's phase state.

### H7. Registers and machine stacks

- The result register `A` is the Java return value; operands are Java parameters; `d` is a parameter. Hot
  code keeps nothing in `Machine` fields except what the library needs (store bases, `fuel`).
- `W`, `wp`, `S`, `sp` are fields used only by `drive`, `sigN`, `spillN`.
- `SC` / `ssp` (binders), `X` / `xp` (scratch), `es` / `ep` (working environments) and the mode register `ER`
  stay machine state owned by judgments, with the invariants I-SCOPE and I-STACKS read as: a step that
  returns a *result* has restored what its judgment pushed; a step that returns SUSP leaves it in place
  (the judgment is alive, in `W`; a base index the continuation needs, such as `xb`, is a payload word); a
  step that returns ABORT or FATAL restores nothing: the `TRY` or `DECL` that receives the signal restores
  `ssp`, `xp`, `ep`, `ER` from the values it saved (the `cut` of ISA 1.4). `SC` entries need no owner
  position.
- A judgment with two results (`LAZYD`) returns one and leaves the other in `m.A2`, which the continuation
  step of its caller reads before it calls anything.
- Activations of code objects (K1): the registers of an activation live in a register stack `m.RS` (a LIFO
  window per activation, `rp` saved by `TRY`), not in the frame, because a frame has no address until it is
  written. `Code.run(m, blk, pc, rbase, d)` is entry and continuation step at once (the state is the pc);
  a class-j instruction is rule H3 with the frame `ACT [blk, pc, dst, argRun, capRun, ls, rbase]`.
  This item is a consequence of the convention, not something the spike measured.

### H8. Fast paths

1. The fast path of a judgment is straight-line code at the head of its entry step (H4): identity, flag,
   memo slot, pair table, inline cache. There is no separate `J.fast`, no MISS value and no protocol.
2. Keep the entry step small enough to be compiled well: leaf answers inline, everything that allocates,
   probes on a miss, grows or reports behind a call of an out-of-line method (`Ops` slow parts).
3. Inert evaluation: an application nest whose heads are constants and whose leaves are variables, sorts,
   constants, literals and binders (closures) is evaluated by one loop over a scratch buffer
   (`Inert.eval` in the spike) called from `EVAL`'s entry step when the node carries the flag. 82% to 95% of
   the term nodes evaluated in Init and Cedar are in such nests. Measured effect of the
   loop on H (Hi64 against H64): 2 to 5 points faster on the Graal JIT, within the noise (-3 to +2 points) on
   the native image and on C2; on plain recursion (Ri against R) it is neutral on the Graal JIT and 2 to 4 points *slower* on
   the native image and C2. Keep it behind the node flag and decide per platform when `EVAL` is profiled on
   the real machine; it is not part of the control convention.
4. Loops that the algorithm has anyway (spine walks, let chains, telescopes, the `WHNF` loop) are Java loops
   inside one step; they do not recurse on the host and do not count against `D`.

### H9. The depth bound

- `D = Op.HOST_DEPTH = 64`. Every sub-judgment call passes `d + 1`, tail calls included (a Java tail call
  still occupies a host frame). The driver and `DECL` enter with `d = 0`.
- Only the entry step tests `d` (H4), once per judgment that is not answered by its fast path.
- Why 64. Frames written per judgment on Init (types + values): D = 8: 0.13; D = 32: 0.0024; D = 64: 0.00011;
  D = 256: 0.000003; Cedar: 0.13, 0.0034, 0.00025, 0.00001. CPU time: D = 8 costs 7 to 10 points more than
  D = 64; D = 32, 64, 256 and "no bound" cannot be told apart (they differ by less than the 3-point noise).
  Host stack: the 1,000,000-deep terms run with D = 64 on a 64 KiB thread stack in the native image and on
  128 KiB on a cold JVM (interpreter frames); D = 256 needs 384 KiB on a cold JVM. So 64 costs nothing
  measurable, leaves a default thread stack (1-2 MiB) almost entirely to the library below the judgments
  (`rlvm-level`, `rlvm-nat` and their declared recursion bounds), and keeps the collector's stack scan at
  about 200 frames (R11 B.11: 680 instructions per frame per young collection, here 0.14 M per collection).
- `D = 0` must keep working at every commit (H12).

### H10. Method sizes and inlining

Facts (this host, GraalVM CE 25; byte counts from `javap` on the spike's classes):

| compiler | limits that matter here |
|---|---|
| HotSpot, any JIT | a method above 8,000 bytecodes is never compiled (`DontCompileHugeMethods`) |
| C2 | inlines a callee of at most 35 bytecodes always (`MaxInlineSize`) and of at most 325 when the call site is hot (`FreqInlineSize`); one level of recursive inlining; depth 15 |
| Graal JIT (CE) | cost-benefit inliner; trivial below 10 nodes, never above 300 nodes (`MaximumInliningSize`); recursive inlining up to 5 levels. Observed with `-Djdk.graal.TraceInlining=true`: it does **not** inline the continuation steps on its own ("not worth inlining according to the cost-benefit analysis") |
| native-image CE | the same inliner without profiles; the continuation steps are not inlined either: H64 executes 15% more instructions than R, 6% when they are |

| code | bytecodes |
|---|--:|
| R: `eval`, `readback`, `conv` | 248 / 265 / 300 |
| H: entry steps `eval`, `rb`, `conv` | 276 / 311 / 250 |
| H: continuation steps | 28 to 94 |
| H: `drive` with 22 resume points in one `switch` | 877 |
| W1: fused loops of the spike's three small families | 1,049 / 1,279 / 1,418 |
| W0: `step` methods of the three judgments | 750 / 923 / 1,068 |

Rules:

1. An entry step stays below 325 bytecodes (C2's limit for inlining a hot callee, and a size both JITs
   compile well), a continuation step below 150. A step that grows beyond that is
   split at a sub-judgment call (that is a new state) or sheds its slow part into an `Ops` method.
2. The `resume` switch is per family (about 40 bytecodes per case; at most 50 cases per method); `drive`
   itself only dispatches on the family.
3. Continuation steps carry a common name prefix (`k_conv1`, `k_args1`, ...; entry steps and loop methods do
   not), so that one pattern selects them, and the build inlines them into their callers and keeps the cold
   helpers out of line:
   - JVM: `-XX:CompileCommandFile=<file>` with `inline rlvm/machine/*.k_*` and
     `dontinline rlvm/machine/Machine.sig*`, `dontinline rlvm/machine/Machine.spill*` (both JITs honour it);
   - native image: `-H:+UnlockExperimentalVMOptions -H:PriorityForceInline=rlvm.machine.*.k_*
     -H:PriorityNeverInline=rlvm.machine.Machine.sig*,rlvm.machine.Machine.spill*`.

   This is tuning, not correctness: measured gain 0 to 3 points of CPU on the native image and the Graal JIT,
   none on C2 (8 points of instructions in the native image). Without the flags the convention still meets the target on every runtime.
4. Never mark an entry step for inlining (they are recursive), and never inline `sigN` / `spillN`.
5. The fused-loop alternative needs every family in one method: the spike's three *small* families are
   already 1,000 to 1,400 bytecodes each; the real families (ISA 8.2: 4 to 10 operations of up to 16 states)
   would sit at the 8,000-bytecode limit that ISA 8.2 has to legislate against. Step methods are two orders
   of magnitude below it.

### H11. Budgets, `TRY` and failure classes

- `TICK`: `if (--m.fuel < 0) return m.exhaust();` in the entry step after the fast path (H4), and once per
  iteration in the `WHNF` and `LAZYD` loops. `exhaust()` records `E_STEPS` and returns ABORT. Every 4096
  ticks the slow part polls `firstFailure` and the memory watermarks and returns FATAL if they fire.
- `fail(E)` is `return m.fail(E)` (ABORT with `m.err = E`), `fault(E)` is `return m.fault(E)` (FATAL).
  There are no Java exceptions on these paths.
- `TRY b; call J(x) => k` is a step like any other (template, `tryConv` / `k_try`): it saves `fuel`,
  `ssp`, `xp`, `ep`, `ER` in locals, sets `fuel = min(b, fuel)`, calls `J`. If `J` returns a result the step
  settles the fuel and returns it. If `J` returns ABORT the step restores the saved stacks and fuel and
  continues with `U` (or passes ABORT on when its budget was capped by an enclosing budget that is now
  empty). If `J` returns SUSP the step writes the `TRY` frame of ISA 1.3 like any continuation frame.
- A `TRY` is therefore *not* written to `W` when it starts, and nothing is materialised for it. The
  rollback of a segment is: every step between the failing judgment and the `TRY` step returns at once (at
  most `D + 2` of them), and whatever part of the segment is in `W` is cut by the driver, which pops frames
  by their `n` down to the innermost `TRY` frame (cost proportional to the frames discarded, each of which
  cost more to create; `tryp` chaining is not needed).
- Exactness. A tick is charged per judgment that does work and per loop iteration, independent of `D` and of
  whether frames exist; a spilled judgment returns its tick (the spike charges every judgment it starts,
  leaves included). The outcome `T / F / U` and the fuel left are
  therefore functions of the judgment and the budget alone. Measured: `CONV` of every Init root under budgets
  of 10, 100, 1,000 and 100,000 ticks gives the same outcomes, the same ticks per root and the same number
  of interned values with `D = 0` (every frame written, `W` cut to the `TRY` frame), `D = 64` and no bound
  (host unwinding only) - `results/verify.txt`.
- I-MEMO holds by construction: a memo is written by the step that completes the judgment, which a signal
  never reaches.

### H12. Tests that belong to the convention

1. `D = 0` build: every declaration of the tutorial and `bugs/` tests gives the same value ids, memo
   contents, tick counts and outcomes as the `D = 64` build (this is the equivalence test of ISA 8.2 and the
   differential stepping mode: with `D = 0`, `drive` sees every `call`).
2. Frames per declaration: the `call` counter of H4 (regression on its value per declaration), and the
   number of frames written with `D = 64`, which must stay below 0.1% of the calls on Init.
3. Deep terms: 1,000,000-deep application, binder and mixed nests on a 512 KiB thread stack, and under a
   `TRY` with a budget that runs out inside the nest.
4. A static check (unit test over the class files) that no step method exceeds the size limits of H10 and
   that every `KEY` has exactly one `resume` case.

## 3. Template: one judgment family

The conversion family, reduced to what shows the convention (the real `CONV` has more cases; `Ops.*` and
`Eval.*` stand for the library and another family).

```java
final class Conv {                                   // results T = 1, F = 0, U = 2; signals SUSP, ABORT, FATAL >= SIG
  /** CONV(a, b): entry step = state 0. Its frame, if it is ever written: CONV[0](a, b). */
  static int conv(Machine m, int a, int b, int d) {
    if (a == b) return T;                                            // 1. inline answers: no tick, no frame
    int r = Ops.pairLookup(m, a, b);
    if (r != Ops.MISS) return r;
    if (--m.fuel < 0) return m.exhaust();                            // 2. TICK
    if (d > Op.HOST_DEPTH) return m.spill2(Op.CONV_0, a, b);         // 3. too deep: become a frame
    switch (Ops.kind(m, a)) {                                        // 4. body
      case Op.NEU: return args(m, a, b, 0, d);                       //    a loop of this judgment
      case Op.LAM: { int da = Eval.dom(m, a, d + 1);                 //    call DOM(a) => 1
                     return da >= SIG ? m.sig2(da, Op.CONV_1, a, b) : k_conv1(m, a, b, da, d); }
      default:     return Reduce.lazyDelta(m, a, b, d + 1);          //    tail position: no test, no frame
    }
  }
  static int k_conv1(Machine m, int a, int b, int r, int d) {        // state 1, frame CONV[1](a, b); r = dom a
    int db = Eval.dom(m, b, d + 1);                                  // call DOM(b) => 2
    return db >= SIG ? m.sig3(db, Op.CONV_2, a, b, r) : k_conv2(m, a, b, r, db, d);
  }
  static int k_conv2(Machine m, int a, int b, int da, int r, int d) { // state 2, frame CONV[2](a, b, da); r = dom b
    int c = conv(m, da, r, d + 1);                                   // call CONV(da, db) => 3
    return c >= SIG ? m.sig2(c, Op.CONV_3, a, b) : k_conv3(m, a, b, c, d);
  }
  static int k_conv3(Machine m, int a, int b, int r, int d) {        // state 3, frame CONV[3](a, b); r = domains?
    if (r != T) return r;
    int x = Ops.open(m, Ops.domOf(m, a));                            // SC entry owned by this judgment
    int c = bodies(m, a, b, x, d + 1);                               // call BODIES(a, b, x) => 4
    return c >= SIG ? m.sig0(c, Op.CONV_4) : k_conv4(m, c, d);       // a signal leaves the binder to H7
  }
  static int k_conv4(Machine m, int r, int d) { Ops.close(m, 1); return r; }   // state 4, frame CONV[4]()
  static int args(Machine m, int a, int b, int i, int d) {           // a loop inside the judgment, from pair i on
    for (int n = Ops.spineLen(m, a); i < n; i++) {
      int x = Ops.arg(m, a, i), y = Ops.arg(m, b, i);
      if (x == y) continue;                                          // cheapest case tested by the caller
      int r = conv(m, x, y, d + 1);                                  // call CONV(x, y) => 5
      if (r >= SIG) return m.sig3(r, Op.CONV_5, a, b, i);
      if (r != T) return r;
    }
    return T;
  }
  static int k_args1(Machine m, int a, int b, int i, int r, int d) { // state 5, frame CONV[5](a, b, i); r = pair i
    return r != T ? r : args(m, a, b, i + 1, d);                     // finish the iteration, re-enter the loop
  }
  static int tryConv(Machine m, int a, int b, long budget, int d) {  // TRY budget; CONV(a, b) => T, F or U
    long saved = m.fuel, given = Math.min(budget, saved);  int ssp = m.ssp, xp = m.xp;
    m.fuel = given;
    int r = conv(m, a, b, d + 1);
    return r == SUSP ? m.saveTry(saved, given, budget, ssp, xp) : k_try(m, saved, given, budget, ssp, xp, r);
  }
  static int k_try(Machine m, long saved, long given, long budget, int ssp, int xp, int r) {
    if (r == FATAL) return r;
    if (r != ABORT) { m.fuel = saved - (given - m.fuel); return r; } // T or F: charge what was used
    m.closeBindersAbove(ssp);  m.xp = xp;  m.fuel = saved - given;   // the segment is gone (host returns + cut of W)
    return given < budget && m.fuel == 0 ? ABORT : U;                // capped by an enclosing budget, now empty
  }
  static int resume(Machine m, int key, int[] w, int p, int r) {     // the family's part of the driver:
    switch (key) {                                                   // payload = the step's parameter list
      case Op.CONV_0: return conv(m, w[p], w[p + 1], 0);
      case Op.CONV_1: return k_conv1(m, w[p], w[p + 1], r, 0);
      case Op.CONV_2: return k_conv2(m, w[p], w[p + 1], w[p + 2], r, 0);
      case Op.CONV_3: return k_conv3(m, w[p], w[p + 1], r, 0);
      case Op.CONV_4: return k_conv4(m, r, 0);
      case Op.CONV_5: return k_args1(m, w[p], w[p + 1], w[p + 2], r, 0);
      default:        return m.fault(Err.E_INTERNAL);
    }
  }
}
```

## 4. Why this convention

**The host stack is the cheapest stack Java has.** A Java call pushes and pops without bounds checks, keeps
live values in callee-saved registers, and its return is predicted by the return-address stack. An explicit
stack in an `int[]` pays a capacity test and bounds checks per push, loads per pop, and one data-dependent
dispatch per returned result. The measurements show the second effect directly: the inert-nest loop, the
tightest explicit-stack loop of the spike (no frame header, no machine state), is no faster than recursion on
the Graal JIT and 2 to 4 points slower on the native image and on C2 (Ri against R), and it takes 14% more
branch misses; the fused loops of W1 cost 10 to 13 points on the native image and 14 to 17 on C2, and 0 to 3
on the Graal JIT, whose profile-guided layout suits a big `switch`. Bounded recursion keeps the host stack
for the judgments that are shallow, more than 99.9% of them, and pays an `int` compare per call for the
right to leave it.

**What H costs.** Per sub-judgment call: one parameter (`d`), one compare of the result against `SIG`; per
judgment that is not a leaf: one compare of `d`. Measured: +1% to +6% CPU by runtime; +15% instructions in
the native image, +6% with the steps inlined. One tick per judgment for budgets (`m.fuel`) costs a further
1 to 4 points (HT64 against H64; 5 points of instructions), under every convention alike.

**What the comparison leaves out, and whom that favours.** In the spike a sub-judgment of *another family* is
rare for W1 (READBACK and CONV call EVAL for a binder's domain and body: 0.9 M fresh frames per 303 M
judgments on Init), and each one costs a frame for the callee, a return to `run()`, a second dispatch and the
reload of the registers. In the real machine such calls are the common case (`INFER` -> `WHNF` -> `CONV` ->
`EVAL`, the families EV / RD / CV / TY of ISA 8.2). Under H a call is a call whatever the family. The spike
therefore understates W1 and not H. ISA 8.2's candidate (ii) as written (fused loops inside a family, host
calls with reserved slots across families) was not built; it lies between W1 and H, and the result for the
inert-nest loop says that the fused part is the part that costs.

**Other properties that decided between two candidates only a few points apart:**

| | H | W1 (fused loops) |
|---|---|---|
| a judgment is written | once, as steps that read like the recursive algorithm: 230 lines for the spike's three judgments (R: 110), plus 300 lines of machinery that exists once (frame helpers, driver, `TRY`) | as cases of a family loop with labelled `continue`s; multi-step sequences need a second `switch` on a local state or duplicated code: 735 lines for the same three judgments |
| cost depends on the compiler | no: +1% to +6% on native image, Graal JIT, C2 | yes: +0% to +3% (Graal JIT), +10% to +13% (native image), +14% to +17% (C2) |
| cost depends on the family partition | no | yes: every call across families goes through `run()` |
| method sizes | steps of 30 to 310 bytecodes | one method per family; 1,000 to 1,400 bytecodes for the spike's small families, near the 8,000 limit for real ones |
| frames written per judgment (Init) | 0.00011 | 0.064 |
| the worklist machine of ISA 1.3 | the same source with D = 0 (1.4x to 2.1x the CPU time of R; a test configuration) | is the implementation |
| host stack | bounded: at most D + 2 judgments, about 200 frames; no enlarged stacks | constant: about 3 frames |
| budgets, `TRY`, cut | exact; a completed `TRY` writes nothing; cut = signal returns + popping frames | exact; `TRY` frame always written; cut = set `wp` |
| deep terms | frames in `W` beyond the bound | frames in `W` |

**Costs and risks of H that the implementers take on.**

1. Every call of a judgment must be followed by the signal test or be a tail return. A forgotten test is a
   bug that only shows on deep terms or under a budget. Mitigation: the `D = 0` test configuration turns every
   non-leaf call into a suspension, so the tutorial tests exercise every call site's signal path; add a
   budget sweep (TRY with 1, 2, 3, ... ticks on a few declarations) for the ABORT path.
2. The host stack depth is bounded, not constant (ISA section 9 says "constant"): at most 66 judgments, each
   a few host frames. A library routine called from a judgment must have its own bound, as ISA section 9
   already requires of `rlvm-level` and `rlvm-nat`.
3. The inlining flags of H10 are per-platform tuning and one of them is an experimental `native-image`
   option. Without them H still meets the target.
4. Registers of code-object activations move to a register stack (H7); `tryp` and `ownerFramePos` disappear.

## 5. What changes in ISA.md

- 8.2, "Decision": candidate (ii) in the form of sections 2 and 3 above, `HOST_DEPTH = 64`. Three points
  differ from the text of (ii): no fused loop inside a family (every sub-judgment is a host call); no
  reserved frame slot (frames reach `W` through the side buffer, so the normal path never touches `W`); a
  `TRY` marker is not written unless the segment is suspended, and the 4096-tick poll raises FATAL instead
  of requesting materialisation. The list "common to both candidates" loses its family-loop items; the code
  stepper becomes entry and continuation step of `ACT`.
- 1.3: `ACT` registers live in the register stack (frame: `[blk, pc, dst, argRun, capRun, ls, rbase]`);
  `SC[k]` holds the variable only. 1.4: `tryp` is not a register; `cut` is "ABORT / FATAL returns, then the
  driver pops frames down to the `TRY` frame or `DECL`"; `TRY` saves `rp` as well.
- Section 0, cost of the vision ("worklist against host recursion"): replace the estimate by the measured
  +1% to +6% CPU (H64 against R, by runtime); section 9: "bounded by `HOST_DEPTH + 2` judgments" instead of
  "constant"; the frame regression test of section 11 counts `call` events (H4), which do not depend on `D`.
- Section 13: `Op.HOST_DEPTH`, `Op.SUSP / ABORT / FATAL / SIG`; `Machine.sig0..sigN`, `spill1..spillN`,
  `saveTry`, `drive`; one `resume(m, key, w, p, r)` per family file; the name prefix of continuation steps
  and the two inlining flag sets in the build.

## 6. Limits of the evidence

- The workload is three small judgments (evaluation without delta, readback, structural conversion) with
  hash-consing and no tabling. Real judgments do more work per call (the convention's share shrinks) and
  call across families far more often (which hurts fused loops only).
- aarch64 (Cortex-X925) only; the arena runner is x86-64. Branch prediction and call costs differ there.
- The host was shared: a pass varied by 10 to 20% between repetitions. Ratios come from interleaved passes
  in one process and are good to about 3 points: H32, H64, H256 and "no bound" are one code path and still
  spread by up to 5 points within a runtime. Differences of that size between H variants, and between Hi64
  and W1 on the Graal JIT, are not established.
- Candidate (ii) of ISA 8.2 exactly as written was not built (see section 4).
- `TRY` was exercised with one segment per root around `CONV` (D = 0, 64, unbounded). Nested `TRY`, FATAL,
  and the restoring of `SC` / scratch on a cut are specified above but not measured; the register stack for
  activations is a design consequence, not an experiment.
- Machine state is static in the spike; a per-worker `Machine` parameter adds one argument to every step,
  for R and H alike.
- Native image: GraalVM CE, no PGO (Oracle GraalVM was not measured here).

