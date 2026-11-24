# RLVM ISA - the instruction set for Lean 4 NbE and def_eq

Revision 2. It applies `OWNER-NOTES.md` (notes 1-10) and `RECONCILIATION.md` (B1-B4, sections 2-4) to the
first draft; the change log (section 15) maps every change to the finding it answers. Order of authority:
`OWNER-NOTES.md` > `RECONCILIATION.md` > this document > research documents.

Scope: everything that happens inside one worker while it checks declarations: machine state, values,
code objects, instructions, memoisation, tiers, and the module that implements them (`rlvm-machine`,
section 13). The term store, loader, declaration table, admission pipeline, reference kernel, inductive
checker, scheduler and packaging belong to `ARCHITECTURE.md`; section 12 lists what this document requires
from that side and section 14 is the facade through which that side uses the machine.

References: `R02 §x` = `docs/research/02-kernel-typechecker.md` section x (the official rule; C++ line
numbers are given there), likewise `R01`..`R13`; `R13` (`docs/research/13-workload-characterization.md`)
is the dynamic profile of the Cedar-level corpora, taken with an instrumented nano-lean and counted in
instructions. Evidence tags: **[meas]** measured on this host (`spikes/machine/results/`, file named),
**[R13]** measured there, **[src]** read in a leader's source via the research documents, **[est]** an
estimate with the arithmetic shown. Export data used in the examples was dumped from
`work/arena-site/tests/good/init-prelude.ndjson` and `generated/init.ndjson`.

Notation: `T`/`F`/`U` = true / false / undecided (budget ran out); `I` = one hash-consing probe; `P` = push
+ pop + dispatch of one worklist frame; `D` = fetch + decode of one code instruction. Section 0.3 gives
what was measured for them. The cost columns of sections 4 and 7 are **instruction estimates** made with
`I` = 120, `P` = 25, `D` = 12; they rank alternatives inside one mechanism and are not CPU-time
predictions (0.3 shows a factor of 8 between instructions per nanosecond on a cached and on a 116 MB
working set).

## 0. Decisions in one page

### 0.1 Metric and yardstick

- **CPU time** (task-clock summed over all threads, compiler and collector threads included) is the
  primary metric; wall clock with parallel workers is the second; peak RSS the third. Instruction counts
  are recorded because the arena's board uses them; they are not optimised. Every table lists CPU time,
  wall clock, RSS, instructions, in that order, where the source has them.
- Targets, measured on this host under the arena's limits (RECONCILIATION §0): mathlib gruszka 62.4 s CPU
  / 18.1 s wall / 2.53 GB, nano-lean 85.6 s / 25.5 s, sokonanoda 101.4 s / 30.5 s; Init gruszka 2.8 s
  CPU; Std 5.7 s CPU. Machine gates on Init: at most 7.0 s CPU (2.5x) after staging step 1 and 4.2 s
  (1.5x) after step 4 (section 11).
- **Cedar-level corpora** (cedar, con-leche, reachability, navier-stokes-euler, cslib, then mathlib) are
  the yardstick for every tiering, heat and compilation decision; Init and Std are regression corpora.
  The fast benchmarks are `work/workloads/slices/cedar-hot.ndjson` (the cone of cedar's 172 most expensive
  own declarations: inference and conversion) and `work/exports/reachability.ndjson` (closed evaluation),
  with `perf/unfold-before-literal-bad` as the depth and Nat-literal probe [R13 §6]. What `R13` found
  (instrumented nano-lean, one thread; **instruction counts**: no CPU-time profile of these corpora
  exists yet, the first machine gate supplies it):

| [R13 §3] | cedar | reachability | con-leche | navier-stokes-euler |
|---|--:|--:|--:|--:|
| declarations; instructions | 151,772; 64.1 G | 4,846; 11.1 G | 38,246; 61.9 G | 129,845; 103 G |
| phase shares: inference / reduction / conversion / import | 42 / 25 / 20 / 9 % | 4 / 93 / 1 / 1 % | 38 / 31 / 21 / 7 % | 54 / 16 / 19 / 9 % |
| `infer` calls (misses) | 42.0 M (13.7 M) | 0.71 M (0.22 M) | 39.4 M (11.3 M) | 77.5 M (19.1 M) |
| `eval` calls (misses) | 94.6 M (50.3 M) | 11.0 M (6.5 M) | 94.3 M (46.4 M) | 195 M (91.4 M) |
| `def_eq` calls (not answered by identity or the pair cache) | 36.2 M (5.66 M) | 0.55 M (0.06 M) | 33.9 M (4.05 M) | 85.5 M (11.5 M) |
| delta / derived-rule firings / iota / projection of a constructor / Nat operations | 2.77 M / 0.69 M / 0.86 M / 1.06 M / 0.12 M | 0.14 M / 0.89 M / 1.05 M / 0.50 M / 0.07 M | 2.79 M / 0.56 M / 1.45 M / 0.95 M / 0.08 M | 3.82 M / 0.58 M / 0.28 M / 2.03 M / 0.06 M |
| values made | 34.8 M | 6.2 M | 28.1 M | 53.0 M |
| declarations without any reduction step (their share of the instructions) | 57.5% (9.0%) | 64.0% (1.5%) | 47.6% (4.9%) | 42.0% (3.6%) |
| declarations with 10^4 reduction steps or more (their share) | 32 (5.9%) | 6 (93.4%) | 44 (18.5%) | 11 (7.7%) |
| code objects (bodies + rules + projections + telescopes) executed 10^4 / 10^5 / 10^6 times or more | 175 / 23 / 0 | 39 / 10 / 0 | 176 / 22 / 0 | 307 / 18 / 0 |
| nano-lean's derived rules off against on | 1.04x | 1.89x | 1.05x | 1.00x |

  Further [R13 §0, §3.1, §5]: by what the code does, cedar spends 33% of its instructions walking terms
  and environments, 21% building and interning values (+4% in the hash tables), 18% in inference proper,
  7% in conversion proper; 12.9% is evaluation *under inference*, the work of `TELE`. Cedar's own
  declarations are less reduction-heavy than Init's (17% against 38% of the instructions) and larger
  (1.02 M against 0.38 M instructions each). 93.6% of cedar's unfoldings are of constants declared in
  Init; by role `casesOn` 21.5%, structure projection functions 19.8%, matchers 14.2%. On mathlib 1,073 /
  129 / 1 objects reach 10^4 / 10^5 / 10^6 executions; the one above 10^6 is the telescope of `Eq` (3.1 M);
  no body, rule or projection reaches 10^6 in any corpus.

  Reading: at this level the checker mostly *walks typed terms* and makes values; reduction is a thin,
  heavy tail of declarations and one corpus (reachability). Consequences: (a) the roles that specialise
  *inference and conversion* to a constant (`TELE`, `CONG`), matching instead of building, and the session
  layout carry the common case; hash-consing, a quarter of the work, is removed by no code tier; (b) the
  reduction roles carry the tail, and three shapes (`PROJARG`, `CASESON`, `MATCHER`) cover 55% of cedar's
  unfoldings; (c) derived rules matter on closed evaluation only; (d) compiled code has no target on the
  library corpora (0.5).

### 0.2 Decisions

1. **The machine is a worklist machine** in the sense of Zhao, Oliveira, Schrijvers (ICFP 2019). Their
   worklist is `Γ ::= · | Γ, a | Γ, x:A | Γ ⊩ ω` with judgment chains `ω ::= A ≤ B | e ⇐ A | e ⇒_a ω |
   A • e ⇒⇒_a ω`; an inference judgment carries its continuation `ω` and binds its result `a` in it; a
   variable is dropped when it reaches the top; the algorithm is `Γ ⟶ Γ'` and succeeds when `Γ ⟶* ·`.
   RLVM: a stack `W` of frames; every frame is a judgment waiting for the result of the frame above it;
   the result is bound through register `A`; binders are entries of the same order; a declaration is
   accepted iff `W` reduces to its bottom sentinel without `FAIL`. This transition system is the
   **specification** (1.3). How the host executes it (a loop over materialised frames, or bounded host
   recursion that keeps the top frames in Java locals) is a separate, measured choice (8.2).
2. **The guest language is the judgment language.** Terms and values are data. A constant is elaborated
   into the *residual judgment chains obtained by specialising the kernel's judgments to that constant*.
   The primary roles are the judgment-specialised ones: `TELE(c)` (application inference `c • ā ⇒⇒ T`),
   `CONG(c)` (conversion `c ā ≡ c b̄`: a per-position comparison plan, 6.1) and `RULES(c)` (weak head
   normalisation `c ā ⇝ v` by case analysis on the arguments: the casesOn and matcher shapes in K1, derived
   trees in K2). `BODY(c)` / `RULE(r, j)` are the general form of the reduction role, and `FACTS(c)` is the
   record of classification facts all of them read.
3. **Run-once terms are not elaborated.** A proof or a declaration type is walked from the term table by
   generic judgments (`EVAL`, `INFER`, `CHECK`), which are the unspecialised form of the same chains. A
   role is elaborated rent-to-own, at about its 10th use (3.1): elaboration costs 3-4 µs per telescope and
   pays back after 22-35 uses [meas].
4. **Values** are 32-bit refs: small Nat immediates (31 bits) or indices of 32-byte records in a flat
   `int[]`. Kinds: SORT, NBIG, STR, LAM, PI, PII, SUSP, FVAR, CONST, NEU, PROJ, NSUC; kinds 13-15 are
   reserved for packed representations (owner note 8). Every record is hash-consed; four memo words live
   in the record.
5. **A neutral is `(head, run)`**, a closure is `(binder term, frame run, ls)`, a suspension is
   `(term, frame run, ls)`, where `ls` is a **level-substitution id** `mkSubst(lparams(row), levels)` (2.7).
   A *run* is an immutable, interned sequence of value refs (spines and captured frames); working
   environments live in a separate non-interned slab.
6. **Closure identity is tier-independent**: `(lambda term id, frame pruned by the node's mask, ls)`.
   Generic walk and elaborated code must produce *the same value ids*; this is a test invariant.
7. **Constants stay folded**; `EVAL` does beta, zeta and projection-of-constructor only; delta is the
   `DELTA` judgment, memoised on the value, driven by `WHNF` and lazy delta in the official order.
8. **Arguments**: never evaluated when the callee's binder is unread; evaluated at once when inert
   (cannot beta/zeta/project); delayed as a hash-consed suspension when active.
9. **Free variables** are `FVAR(type, k)`: a binder takes the first variable of its type that is not in
   scope, so a re-opened binder reproduces every id below it (gruszka's `pick_var`, R05 §2.9).
10. **The complete procedure is the official one** (R02 §6-8) transcribed to values, state for state.
    Every shortcut is either a fact derived from the store (3.5) or a speculative segment under a `TRY`
    frame whose three outcomes are `T`, `F`, `U`; `U` and any *recoverable* failure raised inside the
    segment fall through to the complete procedure; a *fatal* fault (an invariant seen broken, a
    cancellation, the memory watermark) ends the declaration with DECLINE whatever is on `W` (1.4).
    Nothing computed under a budget that ran out enters a memo.
11. **Tabling**: `EVAL`, `INFER` and `CHECK` are memoised on the SUSP record of `(term, pruned frame, ls)`;
    `WHNF`, `CORE`, `DELTA`, `VTYPE` in value slots; `CONV` in one pair table with status bits. Scope is
    the session; check-mode results carry a stamp (5.3).
12. **Nat is specialised in the ISA** (owner note 7): immediates, `NSUC` offset cells that fold to a
    literal exactly when the official `whnf` does, `NATOP` with an exact overflow guard, constructor views
    of literals for iota without allocation.
13. **Static facts** are one immutable record per constant, derived lazily by any worker from the store
    alone, published by a single store of its index. A sort level is recorded only when it is manifest
    (3.5). Everything visible to more than one session obeys the **publication rule** (3.3): only export
    or generated term ids, constant ids, name ids and base level ids.
14. **Sessions** are small: the footprint (bytes in use, counted once) is capped at 16 MiB by default,
    reset costs O(used), and the layout keeps a probe to one or two cache lines (1.6).
15. **Tiers**: K0 = generic judgments written in Java; K1 = code objects executed by the plain-Java code
    stepper, all calling one operation library `Ops`. K2 = derived case trees, the second level of the
    primary role `RULES`: designed (3.6), **off** until its soundness conditions hold. T2 = Truffle
    compilation of hot code objects: an **experiment**, off until the go/no-go of 0.5 is met. Nothing in
    K0 or K1 is shaped for the sake of either.
16. **Limits produce DECLINE, never ACCEPT**; a REJECT is returned to the driver for confirmation by the
    reference kernel; the Nat-size guards reject as the official kernel does (section 9).
17. **Strict mode** (the cold re-check): generic tier only, the complete procedure, only the exact
    shortcuts, a fresh session, no shared facts or code (section 14).
18. **Reuse verdict**: no reference-count or uniqueness bits on values. In-place mutation exists where
    ownership is structural: worklist frames, scratch buffers, the top of the environment slab, memo
    slots (section 10).

### 0.3 What the spikes measured

`spikes/machine/results/` (aarch64, container with the arena's limits; `nce` = native image of GraalVM CE
25, `jvm` = HotSpot with the Graal JIT, Serial GC; 2 runs on `nce`, 3 on `jvm`; ranges are over the runs).
"Pass" is the single-threaded elapsed time of the measured loop, i.e. the worker's CPU time.

| Quantity | CPU time | Instructions | File |
|---|---|---|---|
| `I`, hit: `mkApp` of an existing value, 4096 values, everything cached | 3.8 ns (`nce`), 2.9 ns (`jvm`); probe hand-inlined with the arrays in locals: 2.8 ns (`nce`) | 110 (`nce`), 38 (`jvm`); hand-inlined 45 | `micro.txt` |
| `I`, miss: `mkApp` of a new value into a growing arena | 55 ns (`nce`) | 156 | `micro.txt` |
| Telescope instantiation, whole Init: 2,088,996 constant-headed spines, 4.2 M values made, 64 MB table + 52 MB arena. First pass | generic walk with closed-term memo 810-904 ms; code objects 756-842 ms including 115-126 ms to elaborate all 39,783 used constants | 4.41 G; 3.20 G of which elaboration 0.62 G | `exp1-nce.txt` |
| The same, passes 2-3 (every probe hits; the working set stays 116 MB) | generic 910-1079 ms; code objects 689-705 ms | 2030 and 1167 per spine | `exp1-nce.txt` (`p3`) |
| The same on Std: 3,115,533 spines | generic 1621-1759 ms; code objects 1334-1463 ms including 281 ms of elaboration (69,712 constants) | 11.9 G; 5.7 G of which elaboration 1.53 G | `exp4-std-nce.txt` |
| Match mode: domains matched against existing values, only the result type built | 526-599 ms against 689-705 ms for building (`nce`); 509-513 against 633-650 ms (`jvm`, steady) | 1124 against 1167 per spine (`nce`) | `exp1-*.txt` (`bm`), `steady-jvm.txt` |
| Elaboration of one telescope | 3.0 µs (Init), 4.0 µs (Std) | 15.6 k, 21.9 k (about 220 per code word) | `exp1-nce.txt`, `exp4-std-nce.txt` |
| Saving of a code object per use | 86 ns (Init, first pass), 133 ns (Init, steady), 184 ns (Std) | 880 (Init), 2,400 (Std) | derived from the rows above |
| Worklist against host recursion: eval + readback of the 59,979 declaration types of Init, hash-consing on | recursive 399-464 ms; worklist `wl` 405-501 ms; worklist `wl2` 331-412 ms: the difference is inside the run-to-run spread (`nce`, 2 runs) | `wl` +32%, `wl2` +18% (`nce`); +31%, +39% (`jvm`, steady) | `exp2.txt`, `tables.md` |
| The same with hash-consing off (bump allocation) | recursive 70-78 ms; `wl` 85-89 ms (+18%); `wl2` 77-81 ms (+7%) | +51%, +29% | `exp2.txt` |
| Truffle-compiled telescope roots, whole Init, one pass, compilation in the background | none: 1.04 s CPU / 1.07 s wall; 184 roots call-threaded: 1.63 s / 1.06 s; 184 roots with probes inlined: 4.57 s / 1.65 s; 33 roots: 1.17 s / 1.07 s and 1.80 s / 1.00 s | 6.49 G; 15.1 G; 68.9 G; 8.26 G; 21.7 G | `exp1-nce.txt` |
| Hot subset (184 constants, 1,619,861 spines), pass in steady state | code stepper 413-422 ms; compiled call-threaded 400-414 ms; compiled with probes inlined 323-362 ms. Compilation: 3.5 ms CPU per root call-threaded, 18 ms with probes inlined | 1023, 865, 605 per spine; 47 M and 327 M per root | `hot-nce.txt` |
| Heat: share of instantiations that happen after a constant crossed a threshold of 10 uses | Init 93.8% with 6,002 of 39,783 used constants above it; Std 93.0% with 9,875 of 69,712 | | `heat-init.txt`, `heat-std.txt` |

Three readings that the rest of the document relies on:

- **Memory traffic, not instruction count, sets the CPU time.** The same probe code runs at 29
  instructions per ns on a cached table and at 3.5 per ns in the whole-Init pass (1167 instructions in 335
  ns per spine), where every probe hits but the working set is 116 MB [meas; the attribution to cache
  and TLB misses is an inference]. Hence small sessions, one-line probes and matching instead of
  building (owner note 10); hence also the 16 MiB default of 1.6.
- **Matching beats building by 15-25% of the pass time at equal instruction counts** [meas].
- **The worklist costs instructions, and so far no measurable CPU time when values are hash-consed.** The
  spike's machine is the best case (one method, registers in locals, frames of 2-3 words); frames of the
  shape of 1.3 are measured by `spikes/stepper` before any judgment is written (8.2).

### 0.4 What following the vision costs

| Vision item | Research recommendation | Cost | What it buys |
|---|---|---|---|
| judgments on a worklist, small-step | host recursion + hand-up trampoline (R09 §10.1) | +18% to +32% instructions on eval + readback of Init's types; in CPU time +7% to +18% without hash-consing and inside the noise with it [meas, 0.3]. The first draft's "8-10% of the leader's instructions" was an estimate of unmeasured fast paths and is withdrawn. Acceptance for the convention of 8.2: at most +15% CPU time over plain recursion on Init's and Cedar's declaration types (RECONCILIATION B4) | no stack limit and no 1 GiB thread stacks, exact budgets and rollback by cutting `W`, a GC-shallow Java stack, observable steps for differential testing |
| constants elaborated into code objects | interpret terms with `(pruned env, expr)` caches | 3-4 µs per elaborated telescope; with the threshold at 10 uses about 15% of the used constants are elaborated [meas]. Telescope pass: -7% CPU on Init and -17% on Std in the first pass when *every* used constant is elaborated, -28% in steady state [meas] | sequential code instead of DAG pointer chasing, static facts, matching of domains, the comparison plan |
| Truffle JIT on judgment chains | Truffle only for a separate closed-term engine (R08, R09 §1.6) | 3.5-18 ms CPU per compiled root for 0-3% (call-threaded) or 12-23% (probes inlined) of the pass time of the hot telescopes; on Init every promotion threshold that compiles anything costs more CPU time than never compiling [meas]. On the library corpora it has no target [R13 §5] | one semantics for open and closed terms; an experiment (0.5) |

What the spike does **not** cover: conversion, reduction, binders and tabling. No end-to-end CPU budget
for mathlib can be derived from it; the staging gates of section 11 produce those numbers.

### 0.5 The T2 experiment and its go/no-go number

Truffle specialises the *checking process* only where a judgment chain specialised to a constant is
executed often enough to repay its compilation. Measured: a compiled telescope root must run about
400,000 times after promotion to repay 18 ms of compilation at 44 ns saved per run (hot subset, probes
inlined) [meas]. `R13 §5` concludes that T2 cannot pay on the library corpora under any threshold (upper
bound 6% of cedar's instructions before a compilation cost of half of cedar's total; mathlib's hottest
body runs 685 k times, a quarter of that per worker with four workers) and that its only candidates are
closed evaluation loops: reachability's five `decide` proofs, a handful of con-leche proofs, about 15
`perf/` tests, where interning (33-49% of the instructions) bounds what compiled code can remove. The
expectation is therefore **no-go**, and the decision is taken by measurement, once, after staging step 4:

- **Go** iff, under the arena's limits, total CPU time (all threads, compiler included) is at least **5%
  lower** with T2 than with K1 and its case trees on `reachability.ndjson`, **or** at least 3% lower on
  cedar as a whole; and wall clock is not higher on either; and no corpus of the ladder (Init, Std, the
  Cedar-level corpora, a mathlib slice) loses more than 0.5% CPU time.
- **No-go** otherwise: `rlvm-truffle` is not built and the Truffle runtime is not linked into the image.

Until then T2 is not part of `rlvm-machine`, and no layout, opcode or frame rule in K0/K1 exists for its
sake (8.4 keeps the design sketch and the conditions under which it would be sound).

## 1. Machine state

### 1.1 Stores

| Store | Owner | Representation | Lifetime | Id |
|---|---|---|---|---|
| Terms `S` (export and generated regions, frozen, shared; overlay, per worker) | ARCHITECTURE | 8 ints per node: header, `a`, `b`, `c`, mask (2), `depMax`, `lpSet` | process; session | term id `t >= 1` |
| Declarations `D` | ARCHITECTURE | rows with recomputed inductive data | process | row (= constant id) |
| Levels, level lists, substitutions | `rlvm-level` | hash-consed ids: frozen base + worker overlay | process; session | level id, list id, substitution id |
| Big naturals, strings | `rlvm-nat` (`NatStore` overlay of the worker), `LitPool` (export literals) | ids | session; process | 2.9 |
| Values `V` | ISA | `int[] vh`, 8 words per record; `int[] vtab` intern table with the tag bits in the word | session | value ref |
| Runs `R` (spines, frames) | ISA | `int[] rs`: header words inline, then the slots; `int[] rtab` content table | session | run id = offset of the header, 0 = empty |
| Environments `E` | ISA | `int[] es`, a stack of value refs; not interned | declaration | pair `(base, length)` |
| Pair table, direct-mapped caches | ISA | 1.5, 1.6 | session | - |
| Code `C` | ISA | `int[]` chunks behind a block directory, written once by one worker, then published | process | block id |
| Facts | ISA | one immutable record per constant in the same chunks, published through a per-row slot (3.5) | process | row |
| Worklist `W`, scope stack `SC`, scratch `X` | ISA | `int[]` each | declaration | index |

All per-worker stores are primitive arrays allocated once per worker and reused; the collector never
traces them. `vh` and `rs` are flat arrays grown by doubling (R11: chunked `int[][]` costs +25%,
`MemorySegment` 1.9x; `spikes/machine` exp3: `MemorySegment` costs +20% pass time on the native image and
nothing on the JVM, `Unsafe` nothing on either [meas]). Section 1.6 gives the layout, the sizes and the reset rule.

### 1.2 Registers

| Reg | Meaning |
|---|---|
| `A` | result of the judgment that just completed: a value ref, a level id, or `T=1 / F=0 / U=2 / UNDEF=3 / NONE=0` (untyped, interpreted by the consumer) |
| `wp` | top of `W` |
| `ssp` | top of `SC`; `ssp` = number of open binders |
| `ep` | top of the environment slab `es` |
| `xp` | top of scratch `X` (argument buffers, spine collection); LIFO, owned by the frame that pushed |
| `tryp` | index of the innermost `TRY` frame, 0 = none |
| `fuel` | ticks left in the innermost budget (64-bit) |
| `ER` | 1 while the argument of an `eagerReduce _ _` application is compared with its domain (R02 §8.9) |
| `decl`, `gen` | rank of the declaration being checked (visibility bound); check generation (5.3) |
| `epoch` | session number; stamps the direct-mapped caches |
| `ticks` | ticks executed since the session was created (the facade's `steps()`) |

### 1.3 The worklist: the transition system

This subsection is the specification. It says nothing about Java: 8.2 describes the two host-level
conventions that implement it and how the choice between them is made.

**Configuration.** `⟨W, SC, A, ER, fuel, tryp⟩` over the stores of 1.1. `W` is a sequence of frames; a frame
is `op[st](p0 .. p(n-1))`: an operation, a resume state and `n` payload words. The stores only grow while
a declaration runs (records, runs, completed memo entries); `SC`, `es`, `X` are stacks owned by frames.

**Transitions** of the top frame `f = op[s](p)`; `J` ranges over judgments, `x` over operand tuples.

```
inline     f needs J(x) and J's fast path answers r (identity, flag, memo, cache):
               A := r;  f continues in the state that follows the call                 W unchanged
call       f needs J(x), no fast answer:
               f := op[k](p');  W := W, J[0](x)                    k = the state that reads the result
ret        f completes with result v:
               A := v;  W := W without f;  the new top frame continues in its saved state and reads A
tail       f needs J(x) as its last action and owns no open binder and no scratch:
               W := W without f, J[0](x)                           the result of J is the result of f
open       f opens a binder of type T:   x := the first FVAR(T, k) not in scope (2.8);  SC := SC, (x, f)
close      f closes its last binder:     SC := SC without its top entry, which must belong to f
try, exhaust, fail, fault, cut:          1.4
accept     W = DECL in its last phase:   the declaration is ACCEPTED;  W := ·
```

This is `e ⇒_a ω`: the frame for `e` lies on top of the frame `ω`, and `ω` reads `a` from `A` in state `k`.
In section 4, `call J(x)` abbreviates "inline, else call", and `ret v` is the rule above.

**Invariants.**

- I-FRAME: a frame reads its own payload, `A` when it resumes, and the stores. It never reads another
  frame, with one declared exception: a judgment with more than one result writes the extra results
  into named payload words of the frame directly below it (its caller). The only such judgment is
  `LAZYD` (the pair `tn`, `sn` of the `CONV` frame below it).
- I-SCOPE: the frame that opened a binder closes it before it returns, which is the rule `Γ, x:A ⟶ Γ`;
  cutting `W` to position `p` closes every binder whose owner lies at or above `p`.
- I-STACKS: `xp` and `ep` on return of a frame equal their values when it was pushed.
- I-MEMO: a memo is written only by the frame that owns the judgment, in the transition that completes
  it (5.4).

**What is observable**, and therefore identical under every host convention and every tier: the value
ids that judgments return, the memo and pair-table contents, the tick count, the outcome, and the
*frame trace*: the sequence of `call` and `ret` events (inline answers are not events). Debug builds can
log the trace; the regression test on frames per declaration (section 11) counts its `call` events.

**Frame classes** (logical layout: payload, then a header word on top; `Op.java` holds the numbers):

```
W[wp-1]              hdr = key:12 (31..20) | 0:4 | n:16        key = op << 4 | st;  n = payload words
W[wp-1-n .. wp-2]    payload p0 .. p(n-1)
```

| Class | Frames: `op [payload]` | Role |
|---|---|---|
| activation | `ACT [blk, pc, dst, argRun, capRun, ls, r0..r(nreg-1)]` | continuation of a code object; registers live in the frame; the resume state is the pc |
| generic judgment | `EVAL [t, env, ls, xb, i]`, `FORCE [s]`, `APPLY [f, xb, n, k]`, `INST [P, x]`, `DELTA [v]`, `CORE [v, mode, tmp]`, `WHNF [v, cur, t1, p0..p3]`, `IOTA [v, major, j]`, `QUOTR [v]`, `RULES [v, node]`, `NATF [v, x]`, `CONV [t, s, tn, sn, fl]`, `CARGS [t, s, i, hardLo, hardHi, differLo, differHi, pol]`, `CCLO [t, s, i]`, `CBIND [t, s, nOpen]`, `LAZYD [tn, sn]`, `PROJD [a, b, S, i]`, `PIRR [t, s, T]`, `ETA`, `ETAS [t, s, i, mask]`, `UNITL [t, s, i]`, `INFER [t, env, ls, mode, x1, x2]`, `CHECK [t, env, ls, T, x1]`, `TWALK [xb, n, i, P, env, ls, mode, exp]`, `VTYPE [v, i, P]`, `IPROJ [t, env, ls, mode, r, i]`, `SIGD [row, i, env]` | the unspecialised judgment chains, written in Java as state machines over `st` (at most 16 states per op; a longer machine is split into two ops) |
| budget | `TRY [prevTryp, savedFuel (2 words), given (2 words), savedSsp, savedER, savedXp, savedEp]` | speculative segment marker (1.4) |
| mode | `ERF [savedER]` | restores `ER` when the comparison above it returns |
| sentinel | `DECL [row, phase]` | bottom of `W`; drives the declaration (4.5.6) |

`env` in a payload is two words, `(base, length)` in `es` (2.4). `SIGD` derives the facts of a constant
(3.5); `CCLO` is the closure-congruence probe (4.4.1).

**Binder entries.** Logically the worklist is the merge of `W` and `SC` by creation time; `SC[k] = (fvar,
ownerFramePos)`. Keeping binders in a second array avoids moving an activation frame when its code opens
a binder; the order information is the same.

**As implemented under the decided host convention** (8.2 "Decision"; `review/stepper-convention.md` H1,
H2, H6, H7, H11; consistency note of the machine skeleton, 2026-11-24). The transition system above is
unchanged. What changes is when a frame exists in memory and three layouts:

- A judgment that runs on the host stack has no frame; its frame `op[k](payload)` is written when the
  judgment is suspended, and `W` holds only suspended frames. The payload of `J[k]` is the parameter list
  of the continuation step `k_Jk` (H2), the payload of `J[0]` the operand list of the entry step; the
  payload lists of the table above are indicative, the family classes hold the exact ones. Frames are
  private to the judgment that owns them (I-FRAME without its exception: `LAZYD` returns one result and
  leaves `tn`, `sn` in the result registers `A2`, `A3`).
- `ACT [blk, pc, dst, argRun, capRun, ls, rbase]`: the registers of an activation live in a register stack
  `RS` (pointer `rp`), not in the frame.
- `TRY [savedFuel (2 words), budget (2 words), savedSsp, savedXp, savedEp, savedRp, savedER]`, written only
  if the segment is suspended; `tryp` is not a register. `SC[k]` holds the variable only.
- Operations that call a sub-judgment are judgments with frames of their own: `ARGV`, `DOM`, `OFFSET`,
  `STRX`, `ENSORT`, `ENPI`, `ISPROP` have frame ops (13.3).
- The frame trace that is recorded is the sequence of `call` events; `ret` events are implied by nesting.

### 1.4 Budgets and failure classes

`TICK(n)`: `fuel -= n; ticks += n; if (fuel < 0) exhaust()`. Ticks are charged where work is done and not
on memo hits: one per generic frame started, one per block entry, one per iteration of the `WHNF` and
`LAZYD` loops.

```
TRY b ; call J ⇒ k:  push TRY [tryp, fuel, g = min(b, fuel), ssp, ER, xp, ep]; tryp = its index; fuel = g; push J
J returns r:         TRY on top: fuel = savedFuel - (g - fuel); tryp = prevTryp; ret r          // r is T or F
cut(used):           wp = tryp + 1 (tryp = index of the TRY header); close binders above savedSsp; ER = savedER
                     xp = savedXp; ep = savedEp; fuel = savedFuel - used; tryp = prevTryp; pop TRY
exhaust():           tryp == 0: DECLINE(E_STEPS), W is cut to DECL
                     else cut(g);  if (fuel == 0 && tryp != 0) exhaust()   // the budget was capped by the
                     else ret U                                           // enclosing one, which is now empty
```

`g = min(b, fuel)` makes the outer budget bound the inner one. At `tryp == 0`, `fuel` is the declaration's
step limit `Budget.maxSteps`: unlimited in warm sessions (`Budget.warm()`; RECONCILIATION §3: the memory
watermark and first-failure polling remain), finite in strict and differential modes (`Budget.strict`).
Rollback needs no trail: values and completed memo entries created inside the segment are facts about
values and stay; the mutable state besides `W` is `SC`, `ER`, `fuel`, `xp`, `ep`, all restored from the
frame.

Three classes of failure. The codes, their class and their `Reason` are defined once, in
`rlvm.api.MachineErrors` (published); the machine uses those constants directly.

| Class | Codes | At `tryp == 0` | Inside a `TRY` |
|---|---|---|---|
| kernel error `fail(E)`: the official kernel throws here | `E_TYPE_EXPECTED`, `E_FUN_EXPECTED`, `E_APP_MISMATCH`, `E_LET_MISMATCH`, `E_TYPE_MISMATCH`, `E_PROJ`, `E_UNKNOWN_CONST`, `E_LEVEL_ARITY`, `E_LEVEL_PARAM`, `E_NATSIZE`, `E_NOT_PROP` (`E_LOOSE_BVAR` exists for engines that test closedness themselves; the machine relies on the structural checks) | `REJECT(E)`, `W` cut to `DECL` | `cut(g - fuel)`, `ret U`: the speculative path may have raised an error the complete path never meets |
| recoverable limit | `E_STEPS` (budget), `E_LIMIT` (spine or binder count) | `DECLINE(E)` | the same: `U` |
| fatal `fault(E)`: an invariant was seen broken, or the run no longer wants or can afford this unit | `E_HOLE`, `E_VIS`, `E_ILLTYPED`, `E_INTERNAL` (also: environment bounds, an unbound constant node), `E_AUDIT` (a debug audit failed), `E_CANCEL` (first-failure signal), `E_MEM` (memory watermark, record or run limit), `E_STACK` (`W` limit), `E_PRELUDE` (an extension whose guard is INVALID was needed) | `DECLINE(E)` and the session is reset | **the same**: `W` is cut to `DECL` whatever `tryp` is; the fault is never turned into `U` |

Every 4096 ticks the machine polls `RunSignals.firstFailure()` (a unit earlier in file order failed:
`fault(E_CANCEL)`) and the memory watermarks (`fault(E_MEM)`). Both are fatal, so an abandon is not
swallowed by a speculative segment.

### 1.5 Memo tables (semantics in section 5)

| Table | Key -> value | Form | Scope |
|---|---|---|---|
| value slots `m0..m3` | per record (2.6) | in the record | session |
| `PAIR` | unordered value pair -> status bits `POS` (convertible), `NEG` (not convertible, by the complete procedure), `FAILED` (same-head argument comparison completed with `F`) | one open-addressing table, 4 ints per entry (1.6); one probe serves all three | session |
| `UNDEC` | pair -> log2 of the largest budget of a probe that ended in `U`; it only suppresses another probe, the complete procedure never reads it | direct-mapped 2^12, stamped | declaration |
| `CHEAD` | `(row, level list)` -> CONST record | direct array for the empty and the identity list, else the intern table | session |
| `SIGT` | CONST record -> prop mask of its row at its levels (64 bit) | direct-mapped 2^10, stamped | session |
| `LSUB` | `(level or list, ls)` -> level or list | direct-mapped 2^12, stamped, in front of `rlvm-level`'s own cache | session |
| `LIMP` | `(fact or code record, pool slot)` -> session level id of a structurally stored level (3.3) | direct-mapped 2^10, stamped | session |
| `FV0` | type value -> its `FVAR(type, 0)` record (2.8) | direct-mapped 2^10, stamped | session |
| `IC` | `(site, key)` -> value: all inline caches of code sites | **one** direct-mapped lossy table of 2^14 cells `(site, epoch, key, value)`; a site is `block << 12 or pc` | session |
| `PCHK` | shared term -> "this closed term passed check mode" (ARCHITECTURE `SharedFacts`, one bit; valid for every unit whose roots pass the structural checks) | shared, racy idempotent | process |

Pair keys: the two refs are ordered as **unsigned** 32-bit numbers (an immediate has bit 31 set), so a pair
that contains a literal is as distinct as any other; `Pairs` has a unit test for it.

### 1.6 Session layout, footprint and reset

**Layout** (all `int[]`; RECONCILIATION §3 "Sessions"):

```
vh      8 words per record (2.2)                               record r at vh[8r]
vtab    open addressing, load <= 0.5:  word = tag:4 (31..28) | record index:28;  0 = empty
        tag = 4 hash bits that are not used for the slot index; a tag mismatch costs no load of the record
rs      run slab:  rs[r] = len:24 | kind:2 (FRAME, SPINE, REST) | far:1 ;  rs[r+1] = content hash ;
        [far: rs[r+2] = id of the REST run] ;  then the slots.   run id = r, the offset of its header
rtab    open addressing over run ids, tag bits as in vtab (FRAME and REST runs; a SPINE is owned by its NEU)
es      environment slab: value refs, LIFO (2.4); nothing in a value points into it
pair    4 ints per entry:  lo ref | hi ref | status bits | 0                              (16 bytes, one line)
plog    slot indices of the pair entries inserted in this session (for the reset)
ic, lsub, limp, sigt, fv0, undec     direct-mapped, each cell carries the epoch
```

**Footprint** = bytes in use, counted once: `32 * records + 4 * (run words + pair words + plog entries)`
+ 4 bytes per occupied `vtab` / `rtab` slot + the bytes in use of the worker's term overlay and level and
Nat overlays. It is a sum of bump pointers and counters, O(1) to read. Capacity is never part of it, and
the driver must not add the overlay a second time. Direct-mapped caches, `W`, `SC`, `X` and `es` are
working storage of fixed or per-declaration size and are not counted.

**Cap and sizes.** The driver resets a session when the footprint exceeds `sessionBytes` between two
units; default **16 MiB**, to be swept over 8 / 16 / 32 / 64 MiB by CPU time and wall clock once the
machine runs Std. Initial capacities at the default [est: sized so that the cap is reached before a
table grows]: `vh` 2^18 records (8 MiB), `vtab` 2^19 slots (2 MiB), `rs` 2^20 words (4 MiB), `rtab` 2^18
(1 MiB), `pair` 2^17 entries (2 MiB), `plog` 2^16 (0.25 MiB), direct-mapped caches 0.5 MiB, `W` + `SC` + `X`
+ `es` 2 MiB: **about 20 MiB allocated per worker** besides the term overlay, which is the figure
ARCHITECTURE 2.9 should budget (it assumed 96 MiB). A declaration that needs more grows the array by
doubling; the arrays return to the base size at the next reset. The shared code and fact store is capped
at 0.4 GB; when the cap is reached further roles stay at the generic tier.

**Reset is O(used).** `vtab`: every used record is re-probed (its hash is recomputed from the record; a
NEU stores it in word `c`) and its slot is zeroed. `rtab`: the same walk over the run headers, which hold
the hash. `pair`: the slots listed in `plog` are zeroed. Direct-mapped caches: `epoch++`. Then `vp = 2`, `rp = 1`, `plog` empty. No table is cleared in full:
a session of one small declaration (the v0 regime) resets in time proportional to that declaration.

## 2. Value model

### 2.1 Value references

```
bit 31 = 1   small Nat literal n = bits 30..0            (0 <= n < 2^31; never has a record)
bit 31 = 0   bits 27..0 = record index r (address 8r in vh); 0 = NONE; 1 = HOLE (unreadable placeholder)
             bit 30 EPH, bit 29 UNQ: reserved (section 10), must be 0; bit 28 = 0
```

Canonical forms: a Nat literal below 2^31 is always an immediate; `Nat.succ` applied to a literal or to
`Nat.zero` is the literal + 1; `Nat.succ^k` of anything else is one NSUC cell; a SUSP whose value is known
is never stored where its value could be stored (`PEEK`, 4.2), **except** a SUSP with flag `LET`, which is
kept because it carries the official `hasFVar` of a let variable (2.8). Reading HOLE through a variable is
`fault(E_HOLE)`, a fatal fault.

### 2.2 Records

```
w0  kind:4 (31..28) | flags:12 (27..16) | aux:16 (15..0)
w1  a      w2  b      w3  c                      identity (with kind, and aux where stated)
w4  m0     w5  m1     w6  m2     w7  m3          memo, 0 = unknown; not part of identity
```

| Kind | a | b | c | aux | Meaning |
|---|---|---|---|---|---|
| 1 SORT | level id | | | | `Sort l` |
| 2 NBIG | low 32 bits | high 31 bits | | 0 | Nat literal in [2^31, 2^63): the value is the identity |
| 2 NBIG | `NatStore` id | | | 1 | Nat literal >= 2^63, in the worker's `NatStore` overlay (interned by content) |
| 3 STR | `LitPool` string index | | | | String literal (export literals only; the kernel creates none) |
| 4 LAM | lambda term | frame run | ls | | closure of `fun x : A => b` |
| 5 PI | pi term | frame run | ls | | closure of `(x : A) -> B`; codomain at `x` = `EVAL(B, frame + x)` |
| 6 PII | lambda term | frame run | ls | | "the type of this lambda": domain as LAM, codomain at `x` = `INFER(body, frame + x, ONLY)` |
| 7 SUSP | term | frame run | ls | | delayed `EVAL(term, frame, ls)`; also the tabling record of that term under that frame |
| 8 FVAR | type value | k | | | the k-th free variable of this type |
| 9 CONST | decl row | level list | substitution id of (row, level list) | nat-op code:5, hint class:2 | constant head without arguments |
| 10 NEU | head value | spine run | hash of (head, args) | spine length | `head a1..an`, n >= 1; head is FVAR, CONST or PROJ |
| 11 PROJ | struct value | structure row | field index | | `s.i`, a head for outer applications as in the official term language |
| 12 NSUC | base value | k >= 1 | | | `Nat.succ^k base`, base not a literal, not `Nat.zero`, not NSUC |
| 13-15 | | | | strategy tag | reserved for packed representations (owner note 8): a value that keeps a specialised layout until a recursor or projection asks for the constructor view. None in v1, and none is justified by a corpus so far: at most 4,320 string-literal expansions and a few thousand `Array` / `BitVec` steps per corpus, and lists are produced by evaluation, not given as literals [R13 §5]. The tag space is kept; a strategy is admitted only with a measurement and a shape guard on the library definition it short-cuts, like the Nat and String prerequisites |

Kind is part of identity, so PI and PII over the same term and frame are different values
(`bugs/pi-closure-context`). `ls` is part of closure identity (`bugs/closure-level-subst`), and because it
is a substitution (2.7) and not a bare level list, a term shared by `c1.{u,v}` and `c2.{v,u}` gives two
different records at the same levels.

### 2.3 Flags (w0 bits 27..16)

| Bits | Name | Meaning |
|---|---|---|
| 0 | `OPEN` | may contain an FVAR or a let suspension. Over-approximation is allowed: it only steers the Nat-folding and `Bool.true` guards of R02 §8.1 step 2 and §8.4 (the official `hasFVar`, which is true for let variables) |
| 1 | `WHNF` | already a weak head normal form: SORT, literals, LAM, PI, PII, FVAR, NEU/CONST whose head class is FVAR, CTOR, IND or AXIOM, and an NSUC whose base has the flag (a base is never a literal or `Nat.zero`) |
| 2 | `ACTIVE` | head may reduce: class DEFN, THM, REC, QUOT, PROJ |
| 3 | `COREFULL` | `m1` holds the full `whnf_core` result (else the cheap-projection result) |
| 4-5 | `PROOF` | 0 unknown, 1 not a proof, 2 a proof (memo of R02 §8.3's `is_prop(infer(t))`) |
| 6-7 | `PROPT` | for a type: 0 unknown, 1 not a proposition, 2 a proposition (memo of `ISPROP`) |
| 8 | `INSCOPE` (FVAR) / `LET` (SUSP) | variable is bound by an open binder / suspension was made by a `let` |
| 9-11 | `HCLASS` | head class of CONST, NEU: FVAR, AXIOM (also opaque), IND, CTOR, REC, QUOT, DEFN (definition or theorem), PROJ |

### 2.4 Runs and environments

```
run r in rs      header (1.6): H = 2 words, 3 for a far frame;  slots follow
ARG(r, j)      = rs[r + H + j]                         j-th argument of a spine, j-th captured slot
FGET(r, i)     = rs[r + H + len(r) - 1 - i]            de Bruijn index i of a frame; slot 0 is the outermost
environment    (eb, en): en value refs at es[eb ..];   GET(eb, en, i) = i < en ? es[eb + en - 1 - i]
                                                                               : fault(E_INTERNAL)
```

`GET` is bounds-checked in every build: a `looseRange` that saturated (`LOOSE_SAT`) or an elaborator bug
must not read a neighbouring environment.

| Operation | Result | Interning | Cost [est] |
|---|---|---|---|
| `SPINE(buf, n)` | run of the n refs in scratch | owned by the NEU record that is being interned (one probe for head + arguments, gruszka `spines.rs`) | 45n |
| `FRAME(buf, n, rest)` | canonical frame | by content in `rtab`; the key is `(slots, rest)` | 45n + `I` |
| `PUSH(e, v)` | environment `e` extended by `v` | none. If `eb + en == ep`: write one slot. Else copy `e` to the top of `es` first | 6 / 6 + 3 len |
| `ENTER(fr)` | environment holding the slots of frame `fr` (entering a closure body in the generic tier) | none: copy to the top of `es`; a far frame copies its REST run first | 3 len |
| `PRUNE(e, mask)` | the FRAME that agrees with `e` on the indices in `mask` (bits 0..62), HOLE elsewhere, length = highest set bit + 1; bit 63 (far): `rest` = the REST run that holds `e` without its 63 innermost slots, itself interned by content | by content | 45k + `I` |

- Environments are working storage. Nothing in a value, a run or a memo points into `es`; the frame that
  extended or copied an environment truncates `ep` when it completes (I-STACKS). In-place `PUSH` removes
  the O(depth) copy per opened binder, the quadratic trap of the ladder tests, and because `es` is not
  shared with interned runs an intervening `FRAME` or `SPINE` does not defeat it.
- Code objects do not use environments: an `ACT` frame addresses arguments in `argRun` (the spine run of
  the call, without copying, also when the spine is longer than the arity) and captured variables in
  `capRun` (the closure's frame).
- **Mask contract** (store invariant, re-derived by a naive traversal in debug builds): `mask(t)` is a
  deterministic function of the node and a **superset** of the indices `t` reads; it never
  under-approximates (R05 §6.1), it may over-approximate (the store's `under` rule). A closure therefore
  gets the same id however its environment was reached; an over-approximated bit costs a compared or
  captured slot, never a wrong identity.
- A far frame (mask bit 63) keeps the role of its term at the generic tier. Its REST run is an interned
  run of `rs`, part of the frame's identity, and lives as long as the session.
- Measurement item (C2.12): the ratio of frame length (highest read index + 1) to the number of read
  slots on the tabled nodes of Init. Above 2, frames are stored sparse, `(mask, values at the set bits)`,
  with the same identity; `ENTER` expands them, so `GET` is unaffected.

### 2.5 Hash-consing discipline

One table for all record kinds (layout in 1.6). Key = `(kind, a, b, c)` plus `aux` for NBIG and CONST;
for NEU the key is `(head, len, argument refs)` and `c` stores the hash so that growth and reset never walk
a spine. A probe that hits allocates nothing; a miss writes the record at `vp` and bumps it. Fast paths
in front: `CHEAD` for constants, a 64-entry table for sorts. Invariants:

- I-ID: two refs are equal iff the values are the same construction: same kind, same components, and for
  closures the same term, the same bindings at the mask's positions and the same `ls`. For a far frame
  the components are the 63 near slots and the id of the REST run.
- I-FLAG: flags and memo words are functions of identity (never of how the record was reached). Two
  declared exceptions, neither of which can change a result: the scope state of FVAR records (2.8) and
  the `CODE` hint of closures (2.6).
- I-SESSION: every constant mentioned by a record was visible when the record was made; a session only
  moves forward in file order; hence a record means the same in every later declaration of the session.

### 2.6 Memo words by kind

| Kind | m0 | m1 | m2 | m3 |
|---|---|---|---|---|
| CONST, NEU, PROJ | `WHNF` (self = normal) | `CORE` (+ flag COREFULL) | `TYPE` (infer-only type) | `UNF`: result of `DELTA`, self = does not unfold; for a bare CONST: the evaluated value of the constant at these levels |
| LAM | | `DOM` (evaluated domain) | `TYPE` (its PII) | `CODE` |
| PI, PII | `COD`: the codomain, when the body does not read its variable (bit 0 of the body's mask clear, in particular every closed body): `INST` is then independent of the argument and computed once | `DOM` | `SLVL` (level of its sort) | `CODE` |
| SUSP | `VAL` (never a SUSP) | | `TYPE` of the term under the frame | `TSTAMP`: 0 = infer-only, `PERM`, or check generation |
| STR | `CTORF` (its `String.ofList` form) | | | |
| NSUC | `WHNF` (the literal, or self) | | | |
| FVAR | `NEXT`, `OPENCNT`, `TOPV`: scope state (2.8), not memos | | | |
| SORT, NBIG | | | | |

`CODE` is a hint: the block of the closure's binder term (3.3) or `NOCODE`, looked up at the first `APPLY`
or `INST` of the record. Whether a closure runs its block or is walked does not change any result (decision
6), so the hint is outside I-FLAG and outside the tier-equivalence comparison.

### 2.7 Universe levels

`ls` is a **level-substitution id** of `rlvm-level`: `mkSubst(lparams(row), levels)`, the pairing of the
universe-parameter names of the constant whose type, value or rule a term belongs to with the levels it
is instantiated at. 0 is the identity (and the empty) substitution; equal `(names, levels)` pairs give
equal ids; a length mismatch gives -1, which cannot reach the machine (wrong level arity is rejected by
the structural checks, and by `CHKCONST` where they are absent). The substitution of a constant head is
word `c` of its CONST record, computed when the record is made. `ls` is what closures, suspensions,
`LSUB` keys, inline-cache keys and block calls carry.

Levels are substituted only where `EVAL`/`INFER` meet a `sort` or `const` node: `LSUB(l, ls)` calls
`LevelOps.instantiate(l, ls)` (the simplifying `mk_max`/`mk_imax` of R02 §3.3) behind a direct-mapped
cache; level lists go through `instantiateList`. Rules:

- A term whose node flag `HAS_LPARAM` is clear gets `ls = 0` in every closure and suspension made from it.
- The identity substitution is 0 (R05 §2.2, `mk_ls`). This is sound because a level parameter is
  identified by its name in the whole file.
- Level comparison is `LevelOps.isEquiv` (official normal form first, complete procedure on mismatch,
  R02 §3.6). `Sort` and constant level lists are compared with it, never by id alone.
- Regression test (RECONCILIATION B1): an export with two constants `c1.{u,v}` and `c2.{v,u}` whose values
  share one body term; `c1.{1,2}` and `c2.{1,2}` must give different closures and different `LSUB` results.
- Open: restricting the substitution to the parameters in `lpSet(t)` would raise hit rates; not in v1.

### 2.8 Free variables

`OPEN(T)`: `v0 = FVAR(T, 0)` through `FV0` (one probe on a miss); `n = OPENCNT(v0)` is the number of open
variables of type `T`, and binders close in LIFO order, so the open ones are exactly `k = 0 .. n-1` and the
variable to take is `FVAR(T, n)`: `x = TOPV(v0)` if known, else `n == 0 ? v0 : intern FVAR(T, n)`, linked as
`NEXT` of its predecessor. Then: set `INSCOPE`, `OPENCNT(v0) = n + 1`, `TOPV(v0) = NEXT(x)`, push `x` on
`SC`. `CLOSE`: pop `x`, clear `INSCOPE`, `OPENCNT(v0) = n - 1`, `TOPV(v0) = x`. Cost: about 12 instructions
when the variable exists, one `I` more the first time [est]; no probe sequence over `k` (C2.12).
Invariants:

- I-FV1: a variable has one type for its whole life (the type is part of its identity).
- I-FV2: no two open binders hold the same variable.
- I-FV3: every FVAR reachable from the operands of a running judgment is `INSCOPE`. It holds because a
  judgment's result mentions only variables of its operands (the type of a lambda is a PII that does not
  mention the bound variable; `CONV` returns a boolean), so a memo hit can only return values whose
  variables occur in the key. Under I-FV3 a newly picked variable cannot coincide with one captured in a
  closure under comparison. Debug builds audit it at every `GET`.
- `let` never creates a variable: the bound value is **always** a SUSP with flag `LET`, also when the
  value is inert (zeta by sharing). The flag sets `OPEN` on everything built from it, which is the
  official `hasFVar` of a let variable (`perf/let-fold`).

A fact about values that mention `FVAR(T, k)` holds for any binder that later receives that variable,
because the fact depends only on the value graph and the admitted environment (R09 §6.4).

### 2.9 Nat and String

| Form | Representation | Views |
|---|---|---|
| literal < 2^31 | immediate | as major premise: `0` -> `Nat.zero`, `n` -> `Nat.succ` with field `n-1`, no record |
| literal in [2^31, 2^63) | NBIG, value in two words | the same; arithmetic on the `long` |
| literal >= 2^63 | NBIG with a `NatStore` id | the same, through `rlvm.nat.Nat` |
| `Nat.succ^k x` | NSUC(x, k) | predecessor = NSUC(x, k-1) or x; `WHNF` folds it to a literal when `WHNF(x)` is a literal or `Nat.zero` (4.3.4) |
| `Nat.zero` | CONST | equal to literal 0 in `OFFSET` (R02 §8.4), not rewritten by `WHNF` (R02 §7.6 "literal facts"); `Nat.succ Nat.zero` is the literal 1 |
| string literal | STR | `STRVIEW`: `String.ofList (List.cons.{0} Char (Char.ofNat c) ..)`, one element per Unicode scalar, memoised in `CTORF` |

**Literal identity** (one bridge, owned by the store tables together with `rlvm-nat`; RECONCILIATION §2).
The machine calls `rlvm.nat.Nat` on `long` handles directly; `NatOps` of `rlvm-api` serves the reference
kernel. A handle below 2^31 is an immediate, below 2^63 a two-word NBIG, above that an id of the worker's
`NatStore`, which interns by content. `KLIT` imports an export literal once per session (inline node words,
or the `LitPool` limbs through `Nat.transfer`). `LitPool` holds export literals only and interns naturals
and strings by content. **Until canonical ids are guaranteed end to end, two literal refs that differ are
compared by content before `CONV` answers `F`** (`Lits.sameContent`: limbs for two NBIG records with store
ids, bytes for two STR records); two-word NBIG records and immediates are canonical by construction.

All Nat and String instructions consult the prelude guards of ARCHITECTURE (`Specials`: ABSENT, VALID,
INVALID per special constant; R03 §8 level L1), written `natOk`, `boolOk`, `stringOk` below. ABSENT: the
extension does not fire (the instruction returns NONE, as in the official kernel). INVALID:
`fault(E_PRELUDE)`, a decline, also inside a speculative segment (`rlvm.api.MachineErrors` classes it
with the faults).

## 3. The core elaboration language

### 3.1 Two layers, and why run-once terms are walked

| Layer | Code is | Executed by | Used for |
|---|---|---|---|
| T | the term table itself: a node `(kind, a, b, c, loose, mask, flags)` is a generic instruction | the generic judgments `EVAL`, `INFER`, `CHECK`, `TWALK` (Java state machines) | proofs, declaration types and values; every role below its elaboration threshold |
| K | code objects: linear `int[]` instruction sequences over registers | the code stepper (`ACT` frames) | types, bodies, rules and comparison plans of constants that are used again |

Per executed term node, in instructions [est; not measured per node]:

| | T (generic walk) | K1 (code object) |
|---|--:|--:|
| fetch and dispatch | 30 (node words, switch, mask) | `D` = 12 |
| variable read | `GET` = 8 | 4 (register) or 6 (argument, captured slot) |
| tabling probe (nodes with `tabled(t)`) | `PRUNE` + `I` on those nodes | 0 (the caller's memo covers the whole block) |
| value construction (interning) | equal | equal |

What was measured is the whole telescope role (0.3): a code object saves 86-184 ns per use and costs 3-4
µs to make, so it pays back after 22-35 uses; with matching of domains instead of building them a
further 15-25% of the pass time goes. Consequences:

- A run-once term is never elaborated: 93-95% of the nodes of an export are private to one declaration,
  and they are inferred about once (cedar: 13.7 M infer misses for 14.7 M nodes) [R13 §7]. The term table
  is their code; `EVAL`/`INFER`/`CHECK` are tabled only
  on nodes with `tabled(t)`:

  ```
  tabled(t) = SHARED(t)     two or more referrers inside its declaration's segment
           or BODY(t)       body of a binder, root of a declaration
           or INVARIANT(t)  its mask is a strict subset of its single parent's: the node is re-entered
                            under every environment of the parent with the same pruned frame
           or t is not a term of the unit being checked (export id below the unit's first export id,
              or generated region; the overlay nodes of the unit's own decoded block are its own)
  ```

  The three flags are node header bits 13, 14, 15, set by the loader (a saturating 2-bit referrer counter
  per segment record) and by the block decoder (from a SEEN bit); promoted nodes are SHARED
  (RECONCILIATION §3, requirement R1'). An untabled node costs no probe (R11 A.7 U3); the test must never
  under-approximate sharing, or a DAG is walked as a tree (`perf/app-lam`). **Until the store provides the
  flags every non-leaf node is tabled**, which is correct and slower. Debug builds check that a walk
  never visits an untabled node twice under one frame.
- A constant role (`TELE`, `BODY`, `RULE`, `CONG`) is elaborated **rent-to-own**: at its `ELAB_AT`-th
  request by any session (a per-row saturating counter, racy), `ELAB_AT = 10` to start with, swept over
  4 / 8 / 16 / 32 on Std and `cedar-hot` by CPU time. At 10, 15% of the used constants are elaborated
  and 93% of all instantiations run in code [meas, 0.3]; the measured break-even says the optimum is
  nearer 16-32. Facts (3.5) are derived independently of code elaboration, at first need, so the masks
  exist for cold constants too.
- The large factors are not in walking faster. They are in what the specialised roles make possible:
  static facts (3.5), `MATCH` against telescope patterns (measured, 0.3), comparison plans that never
  compare proofs and unread arguments (6.1), LEAF blocks that run without a frame, and the case-tree
  rules of 3.6.

### 3.2 What a constant elaborates into

Primary roles (each is one kernel judgment specialised to the constant):

| Role | Judgment it specialises | Content | Source |
|---|---|---|---|
| `TELE(c)` | `c • ā ⇒⇒ T` (application inference, R02 §6.5) | one `TARG` per syntactic Pi of the type: the domain as a pattern over the telescope variables, matched against the argument's type before anything is built; result code; entry points for partial application | type term |
| `CONG(c)` | `c ā ≡ c b̄` (argument comparison, R02 §8.4, §8.6) | a per-position plan: class of the position (compare, unread, proof, proof at these levels, type), its dependency mask, its rank among the speculative probes (6.1) | `FACTS(c)` |
| `RULES(c)` | `WHNF(c ā)` by case analysis on arguments | a case tree: which argument to normalise, a switch on its constructor, the alternative to apply. K1: the `CASESON` and `MATCHER` shapes read off the value term. K2: trees derived by partial evaluation (3.6) | value term |

Supporting roles:

| Constant | Code objects and facts | Source |
|---|---|---|
| any `c` | `FACTS(c)`: one record (3.5). Its type part `SIG(c)` = `arity`, `reads`, `dep[i]`, `slvl[i]`, `rlvl`; its value part = `lamArity`, `unread`, shape | type and value terms |
| definition, theorem | `BODY(c)`: arity m = leading lambdas; sub-blocks for inner lambdas; LEAF flag | value term (export or generated region only, 3.3) |
| recursor `r` | `RULE(r, j)` per constructor: arity = params + motives + minors + fields; facts from the *recomputed* record: major index, K, `nfields`, structure-eta flag | regenerated rule right-hand sides |
| constructor `C` of a one-constructor inductive `S` | `FIELDS(S)` = `TELE(C)` after the parameters, with `readsField[j]` = the variable of field j occurs in a later field domain **or in the constructor's result type** (the `reads` fact of `TELE(C)`), for `IPROJ` | type term |
| `Quot.lift`, `Quot.ind`, the 15 Nat functions | Java operations `QUOTR`, `NATOP`; the Nat functions also have `BODY` for open terms | built in |

### 3.3 Block encoding and the publication rule

```
block:   hdr0 = len:20 | nreg:8 | role:4      hdr1 = decl row     hdr2 = arity:12 | flags:12 | nentry:8
         hdr3 = facts index                    entry table (nentry words)   code   level pool
instr:   w0 = op:8 | d:8 | n:8 | x:8           then n operand words  mode:4 | payload:28
modes:   R register | A argument slot (argRun) | C captured slot (capRun) | K pool entry (closed term)
         I small Nat | V literal value ref (HOLE only) | T term id | L level ref | LL level-list ref
         D decl row | B block or label | M mask index
```

Registers are words of the activation frame; at most 255 per block, otherwise the role stays at the
generic tier. Blocks and fact records are immutable. They live in engine-wide `int[]` chunks behind a
block directory; the per-row tables (code by role, facts) and the directory are chunked arrays whose
chunks are installed by compare-and-set, so they can grow while the loader still adds constants.

**Publication rule** (RECONCILIATION B2). Anything visible to more than one session (blocks, fact records,
the per-row tables, the node hint below) contains only:

- term ids of the export or generated region (below the overlay bit), constant ids, name ids;
- level ids and level-list ids of the frozen base of `rlvm-level`;
- derived levels (anything a worker created, for example an inferred `slvl`) **stored structurally**: a
  postfix encoding `ZERO | SUCC | MAX | IMAX | PARAM nameId` in the record's level pool. A session
  re-interns such a level through the import cache `LIMP`, which is cleared at reset.

Never published: a value ref, a run id, an overlay term id, an overlay level, list or substitution id, a
`NatStore` id. Consequences:

- A role whose source term lives in an overlay is **not elaborated**: the value of a theorem or opaque
  kept as a proof block (`BLOCK_VALUE`), and any term a worker built. It stays at the generic tier.
  (A session-local code area dropped at reset is permitted by the rule and not planned for v1.)
- Pool entries (mode `K`) are closed export terms; their *values* are per-session inline-cache entries.
- A record is written completely, then published by **one release store** of its index (block directory
  entry, per-row slot); readers load that index with acquire semantics and treat 0 as "none". No fact is
  read from separately published words. Two workers may build the same record; the contents are equal
  and either index may win.
- Debug builds assert the rule at publication: every `T` operand below the overlay bit, every `L` / `LL`
  operand a base id or a pool offset.

**Finding the block of a closure.** The block of an inner lambda or Pi is recorded in the `c` word of its
binder node (reserved for the elaborator, ARCHITECTURE 2.1) as a block id, written through the store
accessor of requirement R1 after the block is in the directory. The word is a lossy hint: a reader that
sees 0, or whose directory lookup (acquire) returns 0, walks the term. This replaces the first draft's
"`CODE` set by the tier that made the closure".

### 3.4 Elaboration K1 (single pass, iterative)

```
elaborate(role, term):                 term must be an export or generated term (3.3); total on any closed term
  peel leading binders (lambdas for BODY/RULE, Pis for TELE): arity, argument slots A0..
  emit(e, ctx) -> operand, by case:
    variable            -> the register / A / C slot that holds it
    closed, trivial     -> KCONST / KSORT / KLIT operand (const, sort, literal)
    closed, non-trivial -> pool entry k (KVAL): evaluated once per session and ls by the generic walk
    lambda / pi         -> MKLAM / MKPI with capture list = for each index i up to the highest bit of
                           mask(e): operand of variable i if bit i is set, else HOLE; the body becomes a
                           sub-block (entry per number of applied lambdas) unless it exceeds 64 nodes
    app, constant head  -> operands of the arguments (recursively), MKAPP                    (inert)
    app, lambda head    -> static beta: bind the argument operands, continue in the body (syntactic redex)
    app, variable head  -> tail position: TAILAPPLY; otherwise BKIND on the head value: neutral -> MKAPP
                           (inert at run time), closure -> MKSUSP of the node (active)
    let                 -> MKSUSP(value, LET) always (2.8); continue in the body
    proj                -> MKPROJ over the struct operand (struct active: MKSUSP of the proj node)
    argument of an APPLY whose callee is a run-time value: preceded by BUNREAD so that it is not built
                           when the callee does not read it
  TELE: for each Pi i: TARG i with dom_i = closed ? pool : variable ? slot : pattern (the term itself) and
        the code that builds dom_i on demand; result code; TRET
  block flags: nreg, LEAF (no class-j instruction except KVAL, <= 8 instructions), HAS_FAR -> stay generic
```

The elaborator makes no assumption of well-typedness: under the optimistic environment it may be handed
the type or value of a constant that has not been checked yet, and its output is a function of the term
alone. Cost: one visit per node; measured 15.6 k (Init) to 21.9 k (Std) instructions and 3-4 µs per
telescope, about 220 instructions per emitted code word [meas]. The first draft's 80 per node was low
by a factor of 2-3; the elaborator is to be profiled before `BODY` and `RULE` elaboration is tuned.

### 3.5 Static facts: one record per constant

```
FACTS(c)   immutable words in the shared chunks; its index is published in the per-row facts slot
 f0     flags: F_NONE (derivation failed: consumers use no fact of this record) | shape:4
 f1     arity:16 (syntactic Pis of the type) | lamArity:16 (leading lambdas of the value; 0 if none)
 f2,f3  reads     bit i: binder i of the type occurs in a later domain or in the result
 f4,f5  unread    bit i: the innermost body of the value does not read parameter i   (i < lamArity)
 f6,f7  manifest  bit i: slvl[i] is recorded
 f8     rlvl      level ref, or UNKNOWN
 f9     shape operand (PICK j | PROJARG | CTORBODY | CASESON / MATCHER: offset of the case tree)
 f10..  n = min(arity, 64);  slvl[0..n) level refs;  dep[0..n) two words each;  level pool;  case tree
 level ref:  0 = UNKNOWN  |  BASE + base level id  |  POOL + offset of a postfix encoding (3.3)
```

All masks are 64-bit; positions >= 64 have no facts and are always treated as read, dependent, compared.
Readers go through `Facts` accessors that load the record index once (acquire); index 0 and `F_NONE`
both mean "no facts": no position is skipped, `PROOF` stays unknown, every spine is treated as shorter
than `lamArity`. Because the record is one unit, a reader can never combine a mask with a stale arity
(the hazard of C3.5).

**Derivation** (`SIGD`, lazily, by any worker, at the first request for a fact of `c`; `c` is visible to
the requesting declaration, so its unit is below the environment frontier):

```
value part (syntactic):  lamArity, unread (from the body's mask), shape (3.6)
type part, under a TRY of 65536 ticks, in ONLY mode over the leading Pis of the type:
   for i < n:  Ti = call INFER(A_i, env, ONLY)                    // the inferred type, as returned
               Ti is a SORT record: slvl[i] = its level, manifest bit i        // no ENSORT, no WHNF
               dep[i] = mask(A_i) restricted to binders 0..i-1;  x = OPEN(call EVAL(A_i, env));  env += x
   TR = call INFER(R, env, ONLY);  SORT record: rlvl = its level;  reads from the masks;  CLOSE all
   U or a kernel error inside: the record is published with F_NONE
publish with one release store.  The record is a function of the store alone, so racing workers agree;
only F_NONE-by-budget can differ between sessions, and it changes speed, never a result.
```

Recording at `DECL` acceptance is the same call made while the inferences of the type are still tabled:
an optimisation, not a second source. Inductive types, constructors, recursors and quotient constants
are admitted by the reference kernel and get their record by the lazy path like everything else. Test:
`FACTS` with a `SIG` part exists for every constructor and recursor of Init after a run.

| Fact | Derivation | Used by | Soundness condition |
|---|---|---|---|
| `arity`, `lamArity` | syntactic count of leading Pis / lambdas | `TELE`, `DELTA`, `CONG` | none (extra arguments go through the generic path) |
| capture list of a binder | the node's `mask`, bits 0..62; bit 63 keeps the role generic | `MKLAM`, `MKPI`, `MKSUSP` | mask never under-approximates (2.4) |
| `unread` | bit i of the body's mask under `lamArity` binders is clear | `BUNREAD`, `APPLY` (HOLE), `CONG` class UNREAD | S1/S4 of R05 §4: both sides unfold to the same body in which the variable is absent; applied only when the spine covers all leading lambdas. A clear mask bit is definite; a set bit only loses the shortcut |
| `reads`, `dep[i]` | masks of the domains and the result; supersets | `TARG` (argument value not built), `VTYPE`, the agreement rule | a variable that is not read cannot influence a type; a superset only costs work |
| `slvl[i]`, `rlvl` | the level of the type inferred for `A_i` / `R` in ONLY mode, **recorded only when that type is a SORT record as returned, before any weak-head normalisation** (a *manifest* sort); otherwise UNKNOWN | prop mask, static `PROOF` | Lean's algorithmic conversion is not substitutive: a sort that appears only after reducing under generic variables can be stuck for a closed argument, where the official `is_prop` throws (`bugs/proj-of-subst-prop`, `rec-of-subst-prop`). A manifest sort is stable under instantiation: `A_i[ā] : Sort slvl[i][ls]` with no reduction |
| prop mask at `ls` (bit i: argument i is a proof) | manifest bit i and `isAlwaysZero(LSUB(slvl[i], ls))`; cached in `SIGT`; constant per row when no `slvl` has a parameter | `CONG` classes PROOF, PROOFL; `ETAS` | S3: two well-typed arguments at a position whose domains agree are proofs of one proposition and are equal by proof irrelevance. The position is skipped only under the agreement rule of 6.1. UNKNOWN: the position is always compared |
| static `PROOF` of a saturated constant application | `rlvl` manifest: `isAlwaysZero(rlvl[ls])` -> 2; `isNeverZero` -> 1. Otherwise 0: the dynamic `ISPROP` of `CONV` c4 runs. Sorts, Pis, literals, NSUC: 1 | `CONV` c2', c4 | 1 is a negative filter (R05 S6); 2 states that the value's type is a proposition, which is what `is_prop(infer(t))` computes when it does not throw |
| closedness, `HAS_LPARAM` | store flags | `ls` canonicalisation, pool entries, `PERM` stamps | - |
| LEAF, shape `PICK j` (`RET Aj`), `PROJARG` (`MKPROJ; RET`), `CTORBODY` (`MKAPP ctor; RET`) | 3.4 | inline execution in `DELTA`, `APPLY`, `IOTA` | none: the same instructions, without a frame |

Mutation tests for the manifest rule: the three `*-subst-prop` exports rebuilt with an axiom family in
place of the stuck sort must be judged as the official checker judges them.

### 3.6 `RULES(c)`: case-tree reduction rules

**K1 shapes** (v1, staging step 4). A definition's value is a *case tree* over its parameters if, under
its `lamArity` lambdas, it is generated by

```
tree ::= I.casesOn params motive x minor_1 .. minor_k      x: a parameter, or a field bound by an enclosing arm
       | I.rec params motive minor_1 .. minor_k x          only if no minor reads its induction hypotheses
minor ::= fun fields => tree  |  fun fields => leaf
leaf ::= p ȳ                                               p: a parameter (an alternative); ȳ: fields, parameters,
                                                           closed terms
```

with `I.rec` not K-like and `I` not a structure whose major may need eta. `Facts` recognises it
syntactically and stores it in the record: shape `CASESON` when `c` is `I.casesOn` itself (one level, the
leaves are the minors), `MATCHER` otherwise (`Nat.add.match_1`, 37 nodes, is one level with two leaves).
`RULES(c)` on `c ā` with at least `lamArity` arguments:

```
node casesOn on x:  m = call WHNF(value of x);  Nat literal / NSUC / STR: constructor view as in IOTA (4.3.5)
                    m is a constructor application of I with a rule: bind its fields, go to that minor's tree
                    else ret NONE                                   // stuck major: the caller uses DELTA
leaf p ȳ:           ret call APPLY(value of p, values of ȳ ++ the arguments after lamArity)
```

Only the major premises on the taken path and the chosen alternative are evaluated; the motive and the
other alternatives are never built (S8, S9 of R05 §4). Soundness: the result is what `DELTA`, the
recursor rule and beta produce on the same arguments, a finite composition of official steps; reduction
commutes with substitution. `RULES` is used by `WHNF` only; lazy delta keeps single `DELTA` steps so that
it still meets the same-head states of the official order (R04 §8).

**K2: derived trees** (the second level of this role; designed here, implemented after staging step 4,
**off until the conditions below hold**). Trigger: 32 executions of `BODY(c)` in a session (`RULE_HOT`,
both leaders). K2 partially evaluates `WHNF(c ?x1 .. ?xm)` on opaque arguments, splits on the slot a stuck
recursor major or projection waits for (depth 3, at most 8 constructors, 64 tree nodes), and emits
`WHNF slot; SWITCH; FIELDS;` straight-line leaf templates. Evidence on its worth [R13 §3.1, §5]: nano-lean's derived
rules, the model of K2, are worth 1.9x on reachability and 1.6-10x on the evaluation tests of `perf/`, and
1.00-1.06x on Init, Std, cedar, con-leche, navier-stokes-euler, cslib and mathlib; two `perf/` tests are 12%
slower with them. The trigger of 32 executions derives 553 rules on cedar and 2,240 on mathlib. Most of the
frequency behind that gain is case analysis (`casesOn` + matchers: 36% of cedar's unfoldings, 68% of
reachability's), which the K1 case trees above take; what remains for K2 is structural recursion
(`brecOn` / `below`, the `PProd` tables: 6% + 9% of cedar's unfoldings). How much is left is measured at
the gate of step 4. Conditions (RECONCILIATION §2, C3.11):

- derivation slots are a dedicated value kind (kind 0 at a record index >= 2, present only while a
  derivation runs); any type-directed judgment (`VTYPE`, `ISPROP`, proof irrelevance, eta, unit-like, K)
  on a value that contains a slot, and any `U`, abandons the derivation; no pair-table write while
  deriving;
- templates reference only export or generated terms, so that trees can be published under 3.3;
- derivation is capped at 0.2% of the ticks executed by the run.

### 3.7 Listings (constants from `Init.Prelude`, as exported)

```
BODY(HAdd.hAdd)   arity 4, LEAF, shape PROJARG, unread {0,1,2}        value: fun α β γ self => self.0
   MKPROJ r0, HAdd, 0, A3 ;  RET r0
BODY(instHAdd)    arity 2, LEAF      value: fun α inst => HAdd.mk α α α (fun a b => Add.add α inst a b)
   MKLAM r0, T(fun a b => ..), B1, [A0, A1] ;  KCONST r1, HAdd.mk, {u_1,u_1,u_1}
   MKAPP r2, r1, A0, A0, A0, r0 ;  RET r2
   B1 (2 lambdas applied):  KCONST r0, Add.add, {u_1} ;  MKAPP r1, r0, C0, C1, A0, A1 ;  RET r1
BODY(Nat.casesOn) arity 4            value: fun motive t zero succ => Nat.rec motive zero (fun n ih => succ n) t
   MKLAM r0, T(fun n ih => ..), B1, [A3] ;  KCONST r1, Nat.rec, {u} ;  MKAPP r2, r1, A0, A2, r0, A1 ;  RET r2
RULES(Nat.casesOn)  shape CASESON (K1): major = parameter 1; zero -> leaf A2; succ n -> leaf A3 n
   WHNF r0, A1 ;  SWITCH r0, Lz, Ls, Lx
   Lz: RET A2          Ls: FIELDS r1, r0, 1 ;  TAILAPPLY A3, r1          Lx: RET NONE
RULE(Nat.rec, succ) arity 4          rhs: fun motive zero succ n => succ n (Nat.rec motive zero succ n)
   BUNREAD A2, 1, r0, L1 ;  KCONST r1, Nat.rec, {u} ;  MKAPP r0, r1, A0, A1, A2, A3
   L1: TAILAPPLY A2, A3, r0
TELE(congrArg)    n 6, reads 0b011111, slvl [u+1, v+1, u, u, imax u v, 0] all manifest, rlvl 0 manifest
   TARG 0, SORT u ;  TARG 1, SORT v ;  TARG 2, g0 ;  TARG 3, g0 ;  TARG 4, PI(T(α -> β), [g0, g1])
   TARG 5, pattern Eq.{u} g0 g2 g3
   APPLY r0, g4, g2 ;  APPLY r1, g4, g3 ;  KCONST r2, Eq, {v} ;  MKAPP r3, r2, g1, r0, r1 ;  TRET r3
CONG(congrArg)    6 positions; it is a theorem, so only policy APP-like comparison of stuck applications uses it
   0 TYPE dep {} ; 1 TYPE dep {} ; 2 SAME dep {0} ; 3 SAME dep {0} ; 4 SAME dep {0,1} ; 5 PROOF dep {0,2,3}
CONG(HAdd.hAdd)   6 positions, lamArity 4, unread {0,1,2}
   0 TYPE / UNREAD ; 1 TYPE / UNREAD ; 2 TYPE / UNREAD ; 3 SAME dep {0,1,2} ; 4 SAME dep {0} ; 5 SAME dep {1}
   (under SAMEDEF with a spine of at least 4: positions 0-2 are not compared; 3 is probed first)
```

`{u_1}` and the like are level-list operands; at run time they are instantiated by `LSUB(.., ls)` with the
activation's substitution.

## 4. The instruction set

### 4.0 Conventions

`⟦x⟧` = value of operand `x`; `d ← v` writes register `d`. Class p (pure): completes inline. Class j
(judgment): `call` as in 1.3; the row gives the inline fast path, otherwise the rule `call` applies and
`d ← A` when it returns. Class c: control. A generic judgment and the code instruction of the same name are the
same operation; the generic frames of 1.3 are the instruction with its state kept in `st`. Costs are
instruction estimates for the base tier [est], excluding `D`; "memo" means the fast path. `env` is the
pair `(base, length)` of 2.4; `empty`, also written `0`, is the environment of length 0; `PUSH(fr, v)` on a
frame run stands for `PUSH(ENTER(fr), v)`; `ls` is a substitution id (2.7), 0 for a declaration's own terms.

### 4.1 Control

| Instr | Operands | Precondition | Transition | Fails | Kernel rule | Cost |
|---|---|---|---|---|---|---|
| `MOV` | d, s | | `d ← ⟦s⟧` | | | 4 |
| `JMP` | L | | `pc ← L` | | | 2 |
| `BEQ`, `BNE` | a, b, L | | branch on equality of refs | | identity (I-ID) | 4 |
| `BKIND` | s, set, L | | branch if the kind of `⟦s⟧` is in `set` (immediates count as Nat literal) | | | 8 |
| `BUNREAD` | f, i, d, L | | if `⟦f⟧` is a LAM with more than i leading lambdas whose body does not read the i-th: `d ← HOLE; pc ← L` | | S1: unread binder; a clear mask bit is definite (2.4) | 15 |
| `SWITCH` | s, L0..Lk-1, Lx | `⟦s⟧` is a `WHNF` result | constructor index of the head (CTOR: `cidx`; Nat literal or NSUC: 0 if zero, else 1; STR: after `STRVIEW`) selects `Lj`; otherwise `Lx` | | rule selection by constructor (R02 §7.4) | 12 |
| `FIELDS` | d0, s, n | follows a constructor arm of `SWITCH` | `d0.. ← ` the last n arguments of `⟦s⟧`; Nat literal: `d0 ← n - 1`; NSUC: predecessor | fault if fewer than n | "the LAST nfields arguments" (§7.4) | 6 + 3n |
| `CALL` | d, B, ls, a1..an | B is a block | push `ACT(B)`, arguments in a scratch run; LEAF block: run inline; `d ← A` | | | `P` + 6n; LEAF 10 |
| `TAILCALL` | B, ls, a1..an | no open binder owned | overwrite the own frame with `ACT(B)` | | | 15 + 6n |
| `RET` | s | binders opened by this frame are closed | `ret ⟦s⟧` | | | 8 |
| `OPEN` | d, T | `⟦T⟧` is a type value | 2.8; `d ← ` the variable; `SC += (d, this frame)` | `E_LIMIT` (65536 open binders) | fresh fvar typed by the domain (§6.3), made canonical | 12; first use of (type, k): + `I` (2.8) |
| `CLOSE` | n | top n entries of `SC` belong to this frame | pop, clear `INSCOPE` | | restore of the local context | 6n |
| `TRY` | b | next instruction is class j | that instruction runs under a budget (1.4); its `d` receives `T`, `F` or `U` | | none: scaffolding of shortcuts | 20 |
| `REQUIRE` | s, E | | `⟦s⟧ != T`: `fail(E)` | `E` | the `throw` sites of §6 | 4 |
| `FAIL`, `FAULT` | E | | 1.4, by the class of E | | exceptions of §8.10 | |
| `TICK` | n | | 1.4 | exhaustion | | 4 |
| `MEMOGET` | d, v, slot, L | `⟦v⟧` is a record | `d ← m_slot(v)`; non-zero: `pc ← L` | | caches of §5 | 6 |
| `MEMOPUT` | v, slot, s | the result is complete (5.4) | `m_slot(v) ← ⟦s⟧` | | | 5 |
| `PAIRGET`, `PAIRPUT` | d, a, b, bits | | `d ←` the status bits of the pair's entry in `PAIR` / or `bits` into it (one probe; unsigned key order, 1.5) | | `m_success`, `m_failure` (§5) | 60 / 80 |

### 4.2 Value construction

| Instr | Operands | Precondition | Transition | Fails | Kernel rule | Cost |
|---|---|---|---|---|---|---|
| `KSORT` | d, L | | `d ← SORT(LSUB(L, ls))` | | level instantiation (§3.3) | 15 / 20 + `I` |
| `KCONST` | d, D, LL | row visible: guaranteed per root by ARCHITECTURE I1 (`depMax(root) < rank`), re-tested only in debug builds | `d ← CHEAD(D, LSUB(LL, ls))`; HCLASS, ACTIVE, WHNF and the nat-op code come from the row; word `c` = `mkSubst(lparams(D), levels)` | `fault(E_VIS)`; an unbound constant node (`a < 0`; the structural checks never pass such a root): `fault(E_INTERNAL)`, never a REJECT | `instantiate_lparams` on a constant (§4) | 15 / 20 + `I` |
| `KLIT` | d, literal | | immediate, NBIG or STR; an export literal >= 2^63 is imported into the worker's `NatStore` once per session (2.9) | | | 3 / `I` |
| `KVAL` (j) | d, k | pool entry k is a closed term | `IC[site] = (epoch, ls, v)` hit: `d ← v`; miss: `call EVAL(term_k, 0, ls)`, fill | as `EVAL` | closed-term cache | 10 / `EVAL` |
| `MKAPP` | d, h, a1..an | `⟦h⟧` is CONST, NEU, FVAR or PROJ | `d ← NEU(head h, args h ++ ⟦a⟧)`; `Nat.succ` with one argument: as `NSUCC`; flags: OPEN = or over head and arguments, WHNF / ACTIVE / HCLASS from the head | fault `E_ILLTYPED` (head is a sort, Pi, literal), `E_LIMIT` (> 65535 arguments) | folded application; no reduction (§11: folded heads are kept) | 45n + `I` |
| `MKLAM`, `MKPI` | d, T, B, c0..ck | operands = values of the variables T reads, HOLE elsewhere | `d ← LAM / PI(T, FRAME(c), ls')`, `ls' = HAS_LPARAM(T) ? ls : 0`; the block `B` is found again through the node hint (3.3) | | closure = delayed `instantiate` (§4) | 45k + 2`I`; closed: `I` |
| `MKSUSP` | d, T, c0..ck, let | T is active (3.4), or a let value | `d ← SUSP(T, FRAME(c), ls')`; without LET and with a known `VAL`: `d ← VAL`; with LET the record is kept (2.8) | | zeta by sharing (§6.6, §7.1) / delayed argument | 45k + 2`I` |
| `MKPROJ` | d, S, i, s | | `v = PEEK(s)`; NEU with CTOR head of S and `nparams + i < len`: `d ← ARG(v, nparams + i)`; else `d ← PROJ(v, S, i)` | | `reduce_proj_core` on a syntactic constructor (§7.2); the struct is not reduced | 25 / `I` |
| `NSUCC` | d, s | natOk | immediate n: n + 1 (NBIG at 2^31); NBIG: + 1 with the size check; CONST `Nat.zero`: the literal 1; NSUC(b, k): NSUC(b, k+1); other v: NSUC(v, 1), with flag WHNF iff v has it | `E_NATSIZE` | `Nat.succ` row of §7.6 on `lit_ext` operands (literals and `Nat.zero`); offset form of §8.4 | 6 / `I` |
| `PEEK` | d, s | | SUSP without flag LET and with known `VAL`: `d ← VAL`; else `d ← ⟦s⟧` | | | 6 |
| `EVAL` (j) | d, T, env | every loose variable of T is bound in env | generic walk below | `E_HOLE`, `E_VIS`, `E_ILLTYPED` | `instantiate` + beta + zeta, delayed | 30 per node + construction |

```
EVAL(t, env, ls):     fast: variable -> GET; const / sort / literal -> KCONST / KSORT / KLIT
  tabled(t):  s = SUSP(t, PRUNE(env, mask t), ls');  VAL(s) known: ret it;  else VAL(s) = result
  app:   collect the spine in X (head h, argument terms t1..tn), iteratively
         h is a constant: ret MKAPP(KCONST h, ARGV(t1)..ARGV(tn))
         else f = call EVAL(h); ret call APPLY(f, ARGV(ti)..)       // APPLY drops arguments f does not read
  lam / pi: ret LAM / PI(t, PRUNE(env, mask t), ls')
  let:   for each let of the chain: env = PUSH(env, MKSUSP(value, env, LET)); then the body      // always a SUSP (2.8)
  proj:  ret MKPROJ(S, i, call EVAL(struct))
ARGV(t):  variable -> GET;  const, sort, literal -> its value;  lam, pi -> closure                   (atoms)
          app with constant head, or with a variable head bound to a neutral -> EVAL now (builds NEUs only)
          everything else (lambda head, let, proj, variable bound to a closure) -> MKSUSP           (active)
```

### 4.3 Reduction

| Instr | Operands | Precondition | Transition | Fails | Kernel rule | Cost |
|---|---|---|---|---|---|---|
| `FORCE` | d, s | | not SUSP: `d ← ⟦s⟧`; `VAL` known: `d ← VAL`; else `call EVAL(term, frame, ls)`, `VAL ← A` | as `EVAL` | the delayed substitution is performed | 6 / `P` + `EVAL` |
| `APPLY`, `TAILAPPLY` | d, f, a1..an | n >= 1 | 4.3.1 | fault `E_ILLTYPED` | head beta with several arguments (§4) | neutral: 45 len + `I`; closure: `P` + body |
| `INST` | d, P, x | `⟦P⟧` is PI or PII | `COD` known (2.6): it. PI: `call EVAL(body, PUSH(ENTER(frame), reads0 ? x : HOLE), ls)`; PII: `call INFER(body, PUSH(frame, x), ls, ONLY)`; `CODE` present: its block | | `instantiate(body(f_type), arg)` (§6.5); type of a lambda body (§6.3) | `P` + body |
| `DELTA` | d, s | | 4.3.2; NONE if the head does not unfold | | `is_delta`, `unfold_definition` (§7.6) | memo 6; LEAF 30 + body; else `P` + body |
| `CORE` | d, s, cheap | | 4.3.3 | from `IOTA` | `whnf_core` with the flags of §7.3 | flag / memo 6 |
| `WHNF` | d, s | | 4.3.4 | `E_NATSIZE`, from `IOTA` | `whnf` (§7.6) | flag / memo 6 |
| `IOTA` | d, s | NEU with REC head | 4.3.5; NONE if no rule applies | from `ISPROP` | `inductive_reduce_rec`, K, structure eta (§7.4) | `P` + `WHNF` major + rule |
| `QUOTR` | d, s | NEU headed by `Quot.lift` (major 5) or `Quot.ind` (major 4), enough arguments, quot declared | `mk = call WHNF(arg major)`; not `Quot.mk` with exactly 3 arguments: NONE; `d ← APPLY(arg 3, [arg 2 of mk] ++ arguments after the major)` | | `quot_reduce_rec` (§7.5) | `P` + `WHNF` + `I` |
| `NATOP` | d, op, x, y | operands are literals or `Nat.zero` | immediates: 64-bit arithmetic, result < 2^31 immediate, else NBIG; else `rlvm-nat`; `beq`, `ble` yield `Bool.true` / `Bool.false` | `E_NATSIZE` under the guards of §7.6 (128 MiB, exponent > 2^32 - 1, shift bound) | the table of §7.6 | 12; big: library |
| `NATFOLD` | d, s | NEU with a nat-op head and exactly 2 arguments | `x = call WHNF(arg 0)`, not a literal: NONE; `y = call WHNF(arg 1)`, likewise; `d ← NATOP` | as `NATOP` | `reduce_nat` (§7.6) | 20 + `WHNF`s |
| `STRVIEW` | d, s | STR, stringOk | `CTORF` memo, else build `String.ofList (List.cons.{0} Char (Char.ofNat c) ..)` iteratively | fault `E_PRELUDE` | `string_lit_to_constructor` (§7.6) | 6 / 300 per scalar |
| `RULES` | d, s | a case tree exists for the head (K1 shape or K2 tree) and the spine covers `lamArity` | run it (3.6); NONE if no arm matches | | finite composition of `DELTA` and `CORE` steps | `WHNF` + 12 per split + leaf |

```
4.3.1  APPLY(f, a[0..n)):  k = 0
  loop: f = call FORCE(f)
        LAM(t, fr, ls):  j = min(leading lambdas of t, n - k);  env = ENTER(fr)
                         for i < j: env = PUSH(env, body_j(t) reads the i-th ? a[k+i] : HOLE)
                         k += j
                         f = CODE(f) ? call its block at entry j (capRun = fr) : call EVAL(body_j(t), env, ls)
                         k == n: ret f
        CONST, NEU, FVAR, PROJ:  ret MKAPP(f, a[k..n))
        otherwise:               fault(E_ILLTYPED)        // only reachable inside an unchecked foreign body

4.3.2  DELTA(v):  fast: UNF(v) known -> (UNF(v) == v ? NONE : UNF(v))
  row = head constant; class not DEFN or #levels != #lparams: UNF(v) = v; ret NONE          // R02 §10 row 14
  TICK(1);  m = lamArity(row);  n = len(v);  ls = word c of the head's CONST record = mkSubst(lparams(row), levels)
  n >= m and BODY(row) elaborated:  r = call BODY(row)(argRun = spine, ls)        // the block reads m arguments
                                    n > m: r = call APPLY(r, args[m..n))
  else: bv = UNF(head), or UNF(head) = call EVAL(value term, empty, ls);  r = n == 0 ? bv : call APPLY(bv, args)
        a value kept as a proof block is decoded into the overlay first and is never elaborated (3.3)
        ++uses[row] == ELAB_AT: request elaboration of BODY(row)               // export / generated values only
  UNF(v) = r;  ret r

4.3.3  CORE(v, cheap):  fast: immediate or flag WHNF -> v;  m1 known and (COREFULL or cheap) -> m1
  SUSP:            v = call FORCE(v), restart
  PROJ(s, S, i):   s' = call (cheap ? CORE(s, cheap) : WHNF(s));  STR: s' = call WHNF(STRVIEW(s'))
                   f = s' is NEU with CTOR head of S and nparams + i < len ? ARG(s', nparams + i) : NONE
                   res = f == NONE ? v : call CORE(f, cheap)                  // stuck: the ORIGINAL cell (§7.1)
  NEU, REC head:   r = call IOTA(v);   res = r == NONE ? v : call CORE(r, cheap)
  NEU, QUOT head:  r = call QUOTR(v);  likewise
  NEU, PROJ head:  h' = call CORE(head, cheap);  res = h' == head ? v : call CORE(call APPLY(h', args), cheap)
  otherwise:       res = v                                                    // DEFN heads: no delta here
  m1(v) = res;  COREFULL(v) = not cheap (a cheap result never replaces a full one);  ret res

4.3.4  WHNF(v):  fast: immediate or flag WHNF -> v;  m0 known -> m0;  SUSP -> FORCE first
  TICK(1);  cur = v
  loop: t1 = call CORE(cur, full)
        t1 is NSUC(b, k) (natOk):  w = call WHNF(b)                        // the unary case of reduce_nat (§7.6)
              w is a literal n, or CONST Nat.zero (n = 0): done(the literal n + k), size check E_NATSIZE
              else done(t1)                                                // the ORIGINAL cell; the base is not replaced
        t1 has a nat-op head with 2 arguments: r = call NATFOLD(t1); r != NONE: done(r)     // before delta (§7.6)
        case tree for the head (3.6): r = call RULES(t1); r != NONE: cur = r; continue
        t2 = call DELTA(t1);  t2 == NONE: done(t1);  cur = t2;  TICK(1)
  done(w): m0 of v, of t1 and of up to 4 intermediate values = w;  ret w

4.3.5  IOTA(v):  rec = facts of the head (recomputed);  len(v) <= majorIdx or #levels != #lparams: ret NONE
  major = arg majorIdx
  rec.K:  T = call WHNF(call VTYPE(major));  head of T is the major inductive:
              c = MKAPP(first constructor at the levels of T, the first nparams arguments of T)
              call CONV(T, call VTYPE(c)) == T: major = c                      // to_cnstr_when_K, before any reduction
  major = call WHNF(major)
  Nat literal n (natOk):  ctor = n == 0 ? Nat.zero : Nat.succ;  fields = [] or [n - 1]      // no record is built
  NSUC(b, k):             ctor = Nat.succ;  fields = [k == 1 ? b : NSUC(b, k - 1)]
  STR:                    major = call WHNF(STRVIEW(major))
  else, isNonRecStructure(I) and the head of major is not a constructor:                    // to_cnstr_when_structure
        T = call WHNF(call VTYPE(major));  head of T is I and not (call ISPROP(T)):
              major = MKAPP(I.mk at the levels of T, params of T ++ [MKPROJ(I, i, major) for each field i])
  head of major is not a constructor with a rule, or it has fewer than nfields arguments: ret NONE
  r = call RULE(rec, j)(args[0 .. np + nmotives + nminors) ++ the last nfields arguments of major, ls)
  len(v) > majorIdx + 1: r = call APPLY(r, args[majorIdx + 1 ..])
  ret r
```

### 4.4 Conversion

| Instr | Operands | Precondition | Transition | Fails | Kernel rule | Cost |
|---|---|---|---|---|---|---|
| `CONV` | d, a, b | both well typed | 4.4.1 | whatever the complete procedure throws | `is_def_eq` (§8.1) | identity 3; pair hit 60 |
| `LVLEQ` | d, l1, l2 | levels or level lists | equal ids: `T`; else `rlvm-level.isEquiv`, pairwise, equal length | | §3.4-3.6 | 3 / 40 / slow path |
| `MATCH` | d, p, g, v | p is a term over the telescope variables g | 4.4.4; `F` means unknown | none | shortcut for `instantiate(p, g) =?= v` (rpylean `inst_eq`) | 10-15 per pattern node |
| `CARGS` | d, a, b, policy | NEU, NEU, equal length, heads agree | section 6 | | `is_def_eq_args` (§8.4), `is_def_eq_app` (§8.6) | 12 per identical pair |
| `CBIND` | d, a, b | both LAM, or both PI/PII | 4.4.3 | | `is_def_eq_binding` (§8.2) | per binder: 2 `INST` + `CONV` |
| `PIRR` | d, a, b | | state c4 of 4.4.1 | from `ISPROP` | `is_def_eq_proof_irrel` (§8.3) | flag 4 |
| `LAZYD` | d, a, b | core-cheap normal forms | 4.4.2 | | `lazy_delta_reduction` (§8.4) | |
| `PROJD` | d, a, b, S, i | | loop: one step of 4.4.2 l2 on (a, b); Continue: loop; Equal: `T`; otherwise both constructor applications of S: `CONV(field i of a, field i of b)`, else `CONV(a, b)` | | `lazy_delta_proj_reduction` (§8.5) | |
| `OFFSET` | d, a, b | natOk | both zero (literal 0 or `Nat.zero`): `T`; both have a predecessor (literal > 0, NSUC): peel `min` layers at once, `CONV` the rest (definitive); else UNDEF | | `is_def_eq_offset` (§8.4), k single steps composed | 15 |
| `ETA` | d, a, b | a is LAM, b is not | `T = call WHNF(VTYPE(b))`, not PI/PII: `F`; `CONV(DOM a, DOM T)`; `OPEN x`; `CONV(INST(a, x), APPLY(b, x))`; `CLOSE` | | `try_eta_expansion_core` (§8.6) | |
| `ETAS` | d, a, b | b is NEU with CTOR head C, `len = nparams + nfields`, `isNonRecStructure(induct C)` | `CONV(VTYPE a, VTYPE b)` must be `T`; then for each field i that is not skipped `CONV(MKPROJ(I, i, a), ARG(b, nparams + i))`. Skipped: the field positions in the prop mask of `C` at the levels of the type, under the agreement rule of 6.1; the mask is applied only after the type comparison (S5 of R05 §4) | | `try_eta_struct_core` (§8.6) | |
| `UNITL` | d, a, b | | `T = call WHNF(VTYPE a)`; head must be `isNonRecStructure` with a 0-field constructor, else `F`; `d ← CONV(T, VTYPE b)` | | `is_def_eq_unit_like` (§8.8); the types are compared (`bugs/unit-rec-rule`) | |
| `STRX` | d, a, b | a is STR, b is `String.ofList x` | `d ← CONV(WHNF(STRVIEW a), b)`, definitive | | `try_string_lit_expansion` (§8.7) | |

```
4.4.1  CONV(t, s):
 c0   t == s: ret T.    both are literals: ret sameContent(t, s) ? T : F              // 2.9; §8.2 Lit
      [B12] both SUSP, or both LAM, or both PI, or both PII, with the same term and ls, neither frame far:
          r = TRY(1024) call CCLO(t, s);  r == T: goto rec                          // only T decides
      a side is SUSP: FORCE both, restart.    st = PAIR(t, s):  POS: ret T.    NEG and not ER: ret F.    TICK(1)
      SORT / SORT: ret LVLEQ.    LAM / LAM or PI,PII / PI,PII: r = call CBIND; goto rec        // definitive
 c1   [B1] both NEU, same DEFN row, same length, LVLEQ(levels), pair not FAILED, no UNDEC entry >= the budget:
          r = TRY(regular hint ? 16384 : 1024) call CARGS(t, s, SAMEDEF);  r == T: goto rec
          r == F: FAILED += pair.    r == U: UNDEC(pair) = log2(budget)             // U never enters FAILED
 c2   s is CONST Bool.true and (not OPEN(t) or ER):  call WHNF(t) == s: r = T, goto rec        // §8.1 step 2
      [B2] PROOF(t) == 2: tn = t, sn = s, goto c4                      // a known proof is not reduced first
 c3   tn = call CORE(t, cheap);  sn = call CORE(s, cheap)                                      // step 3
      a side changed: quick(tn, sn) = the tests of c0 without the tables; decided: goto rec    // step 4
 c4   PROOF(tn) != 1:  Tt = call VTYPE(tn);  p = call ISPROP(Tt);  PROOF(tn) = p ? 2 : 1       // step 5
                       p: r = call CONV(Tt, call VTYPE(sn)); goto rec                          // T and F final
 c5   r = call LAZYD(tn, sn);  r != UNDEF: goto rec          // step 6; LAZYD leaves the final pair in tn, sn (I-FRAME)
 c6   both CONST, same row, LVLEQ: r = T, goto rec                                             // steps 7-8
      both PROJ, same S and i:  call PROJD(struct tn, struct sn, S, i) == T: r = T, goto rec   // step 9
 c7   tnn = call CORE(tn, full);  snn = call CORE(sn, full)                                    // step 10
      a side changed: (tn, sn) = (tnn, snn); goto c0 (identity, literals, kinds; no table lookup)
 c8   both NEU, same length, call CONV(head tn, head sn) == T:  call CARGS(tn, sn, APP) == T: r = T, goto rec
 c9   exactly one side is LAM:  call ETA == T: r = T, goto rec                                 // step 12
 c10  call ETAS(tn, sn) == T or call ETAS(sn, tn) == T: r = T, goto rec                        // step 13
 c11  STR against `String.ofList x`: r = call STRX; goto rec                                   // step 14
 c12  call UNITL(tn, sn) == T: r = T, goto rec                                                 // step 15
 c13  r = F                                                                                    // step 16
 rec  r == T: POS += (t, s) and (tn, sn).    r == F, not ER, neither side LAM: NEG += (t, s).    ret r
 After the restart of c7 the states c0-c3 read (tn, sn) where they say (t, s).  An immediate has PROOF = 1.
 POS, NEG, FAILED are status bits of the pair's one entry in PAIR (1.5).
 CCLO(t, s):  for each slot i of the two frames (same term, hence same mask and length) that is not HOLE:
                  a_i != b_i and call CONV(a_i, b_i) != T: ret F
              ret T        // congruence of substitution (S11 of R05 §4); an F or U here is not an answer of CONV

4.4.2  LAZYD(tn, sn):  loop
 l0   r = OFFSET(tn, sn);  r != UNDEF: ret r
 l1   (not OPEN(tn) and not OPEN(sn)) or ER:   v = call NATFOLD(tn); v != NONE: ret call CONV(v, sn)
                                               v = call NATFOLD(sn); v != NONE: ret call CONV(tn, v)
 l2   dt, ds = the heads are DEFN with matching level arity (hint from the export; theorems count as opaque)
      neither:  ret UNDEF; (tn, sn) are written into the caller's frame
      only dt:  sn has a PROJ head and (x = call CORE(sn, full)) != sn ?  sn = x  :  tn = unfold(tn)
      only ds:  symmetric
      both:     c = compare(hint dt, hint ds)       // abbrev first, then greater height, opaque last (§8.4)
                c < 0: tn = unfold(tn).    c > 0: sn = unfold(sn)
                c == 0: both NEU, same row, regular hint, pair not in FAILED:
                            LVLEQ(levels) and call CARGS(tn, sn, SAMEDEF) == T: ret T
                            FAILED += pair              // completed with F; a cut frame writes nothing (5.4)
                        tn = unfold(tn);  sn = unfold(sn)
 l3   quick(tn, sn) decides: ret it.    TICK(1)
 unfold(x) = call CORE(call DELTA(x), cheap)

4.4.3  CBIND(t, s):  n = 0
  loop: Dt = DOM(t);  Ds = DOM(s)                          // memo m1, else call EVAL(domain term, frame, ls)
        Dt != Ds and call CONV(Dt, Ds) != T:  CLOSE n;  ret F
        x = neither body reads its variable ? HOLE : (n++, OPEN(Ds))       // typed with s's domain (§8.2)
        t = call INST(t, x);  s = call INST(s, x)
        t and s are again binders of the starting kind: continue
  r = call CONV(t, s);  CLOSE n;  ret r

4.4.4  MATCH(p, g, v) decides "EVAL(p, g, ls) is convertible with v" without building the left side:
  closed p:           KVAL(p) == v
  variable j:         g_j == v and g_j != HOLE
  sort:               v is SORT and LVLEQ
  const-headed app:   v is NEU with the same row, LVLEQ(LSUB(levels of p), levels of v), the same length, and
                      MATCH on every argument
  anything else:      F
  at most 64 pattern nodes, on an explicit scratch stack.  T is sound by congruence (§8.6) and §3.4.
```

### 4.5 Inference and checking

| Instr | Operands | Precondition | Transition | Fails | Kernel rule | Cost |
|---|---|---|---|---|---|---|
| `INFER` | d, T, env, mode | loose variables of T bound in env; mode CHECK or ONLY | 4.5.1 | every `throw` of §6 | `infer_type_core` (§6.1) | variable 12, constant 40 |
| `CHECK` | T, env, V | V is a type value | 4.5.2 | the same, `E_TYPE_MISMATCH` | admissible rule: `infer` + `is_def_eq` | |
| `VTYPE` | d, v | v is well typed | 4.5.3 | | infer-only `infer_type` of the term v stands for | memo 6 |
| `ENSORT` | d, T | | SORT: its level; else `call WHNF`; SORT: its level; else `fail(E_TYPE_EXPECTED)` | `E_TYPE_EXPECTED` | `ensure_sort_core` | 6 |
| `ENPI` | d, T | | PI/PII: itself; else `call WHNF`; not PI/PII: `fail(E_FUN_EXPECTED)` | `E_FUN_EXPECTED` | `ensure_pi_core` | 6 |
| `ISPROP` | d, T | T is a type | PROPT memo; else `l = ENSORT(call VTYPE(T))` (a stuck sort fails; never "not a proposition"); `d ← normalizesToZero(l)`; set PROPT | `E_TYPE_EXPECTED` | `is_prop` (§6.10) | 4 |
| `TARG` | i, flags, dom | in a TELE block | 4.5.4 | `E_APP_MISMATCH` | one argument of `infer_app` (§6.5) | 30-150 + sub-judgments |
| `TRET` | res | in a TELE block | 4.5.4 | `E_TYPE_MISMATCH` (with expected type) | final `instantiate` of §6.5 | |
| `IPROJ` | d, T, env, mode | T is a proj node | 4.5.5 | `E_PROJ` | `infer_proj` (§6.8) | |
| `CHKCONST` | D, LL | CHECK mode | row visible, `#LL = #lparams`, `CHKLVL` on every level (unsafe and partial constants do not exist: dropped at load). Discharged for a whole root by ARCHITECTURE's `StructuralChecks` (`depMax`, `lpSet`, flag UNRESOLVED): then a no-op | `E_UNKNOWN_CONST`, `E_LEVEL_ARITY`, `E_LEVEL_PARAM` | §6.2 | 0 / 25 |
| `CHKLVL` | L | CHECK mode | every parameter of L is declared by the current declaration; discharged per root as above | `E_LEVEL_PARAM` | `check_level` (§6.1) | 0 / 8 |

```
4.5.1  INFER(t, env, ls, mode):
  variable i:  ret VTYPE(GET(env, i))                             // an FVAR's type is a word of its record
  sort l:      CHECK: CHKLVL(l);  ret SORT(succ(LSUB(l, ls)))     // succ is not simplified (§6.1)
  const:       unknown row or wrong level count: fail (both modes);  CHECK: CHKCONST;  ret VTYPE(KCONST)
  literal:     Nat: numeral bound of §7.6 (E_NATSIZE);  ret CONST Nat / CONST String         (§6.9)
  tabled(t):   s = SUSP(t, PRUNE(env, mask t), ls');  TYPE(s) known and
               (mode == ONLY or TSTAMP(s) == PERM or TSTAMP(s) >= gen): ret it;  else store on completion (5.3)
  app:         spine in X.  Constant head with TELE(c): CALL TELE(c) on the site.  Otherwise TWALK (4.5.4)
  lam:         ONLY: ret PII(t, PRUNE(env, mask t), ls')          // nothing opened, nothing visited
               CHECK: for each nested lambda with domain term A:
                          ENSORT(call INFER(A, env, CHECK))       // the context grows after the check (§6.3)
                          D = call EVAL(A, env);  x = OPEN(D);  env = PUSH(env, x)
                      call INFER(body, env, CHECK);  CLOSE all;  ret PII(t, PRUNE(env0, mask t), ls')
  pi:          for each nested Pi:  u_k = ENSORT(call INFER(A_k, env, mode));  x = OPEN(call EVAL(A_k, env));
                                    env = PUSH(env, x)
               r = ENSORT(call INFER(body, env, mode));  CLOSE all
               ret SORT(mkImax(u_1, mkImax(.., mkImax(u_n, r))))                              (§6.4)
  let:         for each let (A, v) of the chain:
                   CHECK: ENSORT(call INFER(A, env, CHECK));  Tv = call INFER(v, env, CHECK)
                          Tv != EVAL(A, env) and call CONV(Tv, EVAL(A, env)) != T: fail(E_LET_MISMATCH)
                   env = PUSH(env, MKSUSP(v, env, LET))
               ret call INFER(body, env, mode)          // the body's type under the bound value; it is the
                                                        // official let-telescope type (§6.6) after zeta
  proj:        IPROJ

4.5.2  CHECK(t, env, ls, V):
  tabled(t):  s = SUSP(t, PRUNE(env, mask t), ls');  TYPE(s) known with a valid check stamp (5.3):
                  TYPE(s) == V or call CONV(TYPE(s), V) == T: ret;  else fail(E_TYPE_MISMATCH)
              after one of the cases below succeeded, store with the check stamp:
                  lam: TYPE(s) = PII(t, frame of s, ls').    app with constant head: TYPE(s) = R, the
                  telescope's own result, which is always built for a tabled t
              the expected type V is never stored: it may mention variables outside t's frame (I-FV3)
  lam:   W = call WHNF(V);  not PI/PII: generic.    A = domain term of t
         ENSORT(call INFER(A, env, CHECK));  D = call EVAL(A, env)
         D != DOM(W) and call CONV(D, DOM(W)) != T: fail(E_TYPE_MISMATCH)
         x = OPEN(D);  call CHECK(body, PUSH(env, x), ls, call INST(W, x));  CLOSE 1;  ret
  let:   bindings as INFER.let in CHECK mode;  call CHECK(body, env', ls, V)
  app with constant head:  CALL TELE(c) on the site with expected = V
  generic:  Tt = call INFER(t, env, CHECK);  Tt != V and call CONV(Tt, V) != T: fail(E_TYPE_MISMATCH)
  // admissible: if body : B[x] and D ≡ DOM(W) then (fun x : D => body) : W.  When V is not a Pi after WHNF
  // the generic rule is the official one.  The tabled INFER of A hits when the same domain node was
  // checked in the declared type (joint telescope check, R07 §1.8), with no special case.

4.5.3  VTYPE(v):  memo m2.   Nat literal, NSUC -> CONST Nat;  STR -> CONST String;  SORT l -> SORT(succ l)
  FVAR -> its type;   LAM(t, fr, ls) -> PII(t, fr, ls);   SUSP -> TYPE, else VTYPE(call FORCE(v))
  PI, PII -> SORT(SLVL), SLVL = mkImax(ENSORT(VTYPE(DOM)), ENSORT(VTYPE(INST(v, OPEN(DOM)))))
  CONST -> call EVAL(type term of the row, empty, word c of the record)       // the substitution of (row, levels)
  PROJ(s, S, i) -> IPROJ on the value s
  NEU(h, args) -> constant head with TELE(c): CALL TELE(c) in ONLY mode on the argument values
                  else P = VTYPE(h);  for each argument a: P = ENPI(P);  P = call INST(P, a)

4.5.4  site = (argument terms t_0..t_{n-1} in X, env, ls, mode, expected or NONE);  g = the telescope variables
  TWALK, generic: P = type of the head.              TARG i, dom_i in TELE(c): the same step with D from dom_i:
  for i < n:                                           closed: KVAL;  variable: g_j;  else the pattern's code
     P = ENPI(P);  D = DOM(P)                          i >= n: jump to the entry that returns the remaining Pi
     CHECK mode:
        t_i is a lam node:  call CHECK(t_i, env, D)                          // expected-type propagation
        else Tt = call INFER(t_i, env, CHECK)
             Tt != D  [TELE only: and not MATCH(dom_i, g, Tt)]:
                 ER = t_i is `eagerReduce _ _` (restored by an ERF frame);  call CONV(Tt, D) != T: fail(E_APP_MISMATCH)
     g_i = binder i is read later ? ARGV(t_i) : HOLE              // ONLY mode: nothing else is done per argument
     P = call INST(P, g_i)     // generic: while the codomain is a syntactic Pi keep (body, PUSH(frame, g_i))
                               // instead of a PI record (R09 §9 trick 2); TELE: no PI record at all
  R = P  (TRET: the result code over g)
  expected V given:  untabled t: MATCH(res, g, V) succeeds: ret V without building R
                     otherwise build R;  R == V or call CONV(R, V) == T, else fail(E_TYPE_MISMATCH);  ret V
  more arguments than syntactic Pis: continue with TWALK on R

4.5.5  IPROJ(proj S i s, env, mode):
  Ts = call WHNF(call INFER(s, env, mode))                        // CHECK mode checks the struct (§6.8)
  Ts is CONST/NEU headed by inductive row S with one constructor C, len(Ts) = nparams + nindices, i < 2^32,
       else fail(E_PROJ);     i >= nfields(C): fail(E_PROJ)
  run FIELDS(S) in ONLY mode with g = the first nparams arguments of Ts;  p = call ISPROP(Ts)
  for j < i:  readsField[j] (3.2):               p and not ISPROP(dom_j): fail(E_PROJ)
                                                g_{np+j} = MKPROJ(S, j, call EVAL(s, env))
              else g_{np+j} = HOLE
  R = dom_i;  p and not ISPROP(R): fail(E_PROJ);  ret R
  // readsField[j] is the official has_loose_bvars(binding_body(r)): the variable of field j occurs in the REST of
  // the constructor type, the result type and its indices included (`P.mk (n : N) (p : True) : P n`: `x.1` is
  // rejected).  It must be exact here, because a set bit can cause a rejection: a set mask bit is confirmed by
  // an occurrence walk when FIELDS(S) is built; a clear bit is definite.

4.5.6  DECL(row):   axiom:        ENSORT(call INFER(type, 0, CHECK))
                    def, opaque:  the same;  V = call EVAL(type, 0);  call CHECK(value, 0, V)
                    theorem:      the same, and ISPROP(V) must hold (`theorem_type_is_not_prop`, R03 §4.2)
                    inductive, quot, constructor and recursor records: reference kernel (ARCHITECTURE)
                    on ACCEPT: derive FACTS(row) if no record is published yet (3.5; an optimisation only)
```

## 5. Tabling

### 5.1 Memoised judgments

| Judgment | Key | Result stored in | Scope |
|---|---|---|---|
| `EVAL(t, env, ls) ⇒ v` | `SUSP(t, PRUNE(env, mask t), ls')`, only for nodes with `tabled(t)` (3.1) | `VAL` | session |
| `INFER(t, env, ls, mode) ⇒ T` | the same record | `TYPE`, `TSTAMP` | session; validity 5.3 |
| `CHECK(t, env, ls, V)` | the same record | `TYPE` = the type the term was found to have (the PII of a lambda, the telescope's result for an application), with a check stamp; a hit answers by `CONV(TYPE, V)`. The expected type `V` is never stored (4.5.2) | session; validity 5.3 |
| `VTYPE(v)` | v | m2 | session |
| `WHNF(v)`, `CORE(v, mode)`, `DELTA(v)` | v | m0; m1 + COREFULL; m3 | session |
| domain, codomain of a non-dependent binder, sort level of a closure | the closure | m1, m0, m2 | session |
| `ISPROP(T)`, "t is a proof" | T; t | PROPT, PROOF flag bits | session |
| `CONV(a, b)` | unordered pair | status bits `POS`, `NEG` of the pair's entry in `PAIR` | session |
| same-head argument comparison completed with `F` | unordered pair | status bit `FAILED` of the same entry | session |
| a budgeted probe ended in `U` | unordered pair | `UNDEC`: suppresses another probe of that size, nothing else | declaration |
| value, type, head and substitution of a constant at given levels | `(row, levels)` | `CHEAD`; `c`, m2, m3 of the CONST record | session |
| prop mask | CONST record | `SIGT` | session |
| level instantiation, normal forms, equivalence | level ids, substitution id | `LSUB`, `rlvm-level` | session / process |
| "closed shared term passed check mode" | term | `PCHK` (ARCHITECTURE `SharedFacts`, one bit) | process; usable by every unit whose roots pass the structural checks, since `depMax` and `lpSet` of a subterm never exceed its root's |
| code objects, fact records | row and role | per-row tables (3.3) | process |

SUSP is at once the delayed argument, the evaluation cache entry and the inference and checking cache
entry of `(term, read bindings, ls)`: one probe serves all of them.

### 5.2 Why the keys are sufficient

- The pruned frame contains every binding the term can read (the mask is a superset of what is read; a
  far frame includes its REST run), `ls` every level it can mention **together with the parameter names
  they are substituted for**, and each free variable carries its type; no typing context exists outside
  the key (`bugs/open-term-cache-context`, R06 §8 item 1).
- Value-keyed results (`WHNF`, `CORE`, `DELTA`, `VTYPE`, `CONV`) depend on the value graph and on the
  admitted declarations the graph mentions, all of which stay visible while the session lasts (I-SESSION).
- Overlay terms (proof blocks) key SUSP records by their term id; the store must not reuse an overlay
  term id inside a session (requirement R4 of section 12).

### 5.3 What is re-validated per declaration

Evaluation, reduction, conversion and infer-only typing do not depend on the declaration being checked.
Check mode adds declaration-relative conditions: level parameters are declared (`CHKLVL`), constants are
visible and have the right level arity (`CHKCONST`); safety does not arise, because unsafe and partial
records are dropped at load. Two regimes:

- **With root summaries** (ARCHITECTURE §2.1, §4.3: `depMax`, `lpSet`, flag UNRESOLVED on every node, tested
  once per declaration root before `DECL` starts). The conditions hold for every subterm of a root that
  passed, so `CHKCONST` and `CHKLVL` are no-ops, a check-mode result is valid in every later declaration
  of the session, `gen` never advances and every check-mode `TSTAMP` is `PERM`. This is the v1 regime.
- **Without them** (v0 on a store that lacks the summaries, and as the fallback rule): the generation
  stamp of rpylean (`check_gen`, R07 §1.11):

```
beginDecl(d):  decl = d;  gen stays if the level parameters of this declaration contain those of the
               previous declaration of the session;  otherwise gen++
TSTAMP on completion of a CHECK-mode INFER:
               PERM  if the term is closed and has no level parameter (HAS_LPARAM clear)
               gen   otherwise                            ONLY-mode INFER: 0
```

In both regimes a stored `TYPE` answers ONLY queries always and CHECK queries iff `TSTAMP == PERM` or
`TSTAMP >= gen`; a type inferred without checking never answers a checking query (R02 §5). Visibility
needs no stamp inside a session (I-SESSION).

### 5.4 Completion rule and negative results

- A memo is written by the frame that owns the judgment, in its final state (I-MEMO). A frame removed by
  a cut writes nothing, so no memo holds a budget-dependent answer.
- `U` is never stored as `F`, and never as `FAILED`. `FAILED` holds only same-head argument comparisons
  that **completed** with `F`; a probe that ran out of budget, or was cut by a kernel error raised inside
  it, is recorded in `UNDEC`, which only prevents another probe with the same or a smaller budget. The
  complete procedure never reads `UNDEC`, so `LAZYD` still runs the official same-head comparison once,
  unbounded, for such a pair (C1.9, C3.14).
- `NEG` is written only for an `F` computed by the complete procedure (it may run inside a `TRY`, as long
  as it finished), not under `ER`, and not when a side is a LAM (both leaders exclude lambdas, R04 S5,
  R06 §2.7; the cost of excluding them is one re-comparison). It is not read while `ER` is set either: a
  comparison under eager reduction may succeed where the plain one failed.
- `POS` holds exact pairs only: no union-find, no congruence closure (R02 §5, `bugs/rec-missing-ih`).
- A session ends (all session tables dropped) when a declaration fails or declines, when the next
  declaration is not later in file order, and at the footprint cap (1.6). The case "a literal needed a
  constant that is not declared yet, so an `F` could become `T` later" (gruszka `known_miss`) cannot
  arise: a literal's `depMax` is the largest rank of its prerequisite names, bound by the loader when
  each name is claimed (RECONCILIATION §2, C3.9), so a root that contains a literal is only checked after
  all of them.

### 5.5 Debug modes

`memocheck`: every memo hit is recomputed with the tables bypassed and compared (nano-lean).
`shortcheck`: every shortcut answer is re-derived by the complete procedure (6.3). `poison`: ids are
shifted at each session reset so that a stale id faults. `scopeaudit`: I-FV3 at every `GET`. `maskaudit`:
the node mask is re-derived by a naive traversal at every `PRUNE`. `sharecheck`: a walk must not visit an
untabled node twice under one frame (3.1). `pubcheck`: the publication rule is asserted on every block
and fact record (3.3). A failed audit is `fault(E_AUDIT)`, fatal.

## 6. Scheduling conjunctive obligations and speculation

### 6.1 `CARGS(a, b, policy)` and the comparison plan `CONG(c)`

The n obligations `a_i ≡ b_i` of a spine comparison are conjunctive; the result does not depend on their
order, the cost does. `CONG(c)` is the residual of `CARGS` for the constant head `c`: what is decided per
position when `c` is known. It is a block of role CONG built from `FACTS(c)` and the type term of `c`; it
contains no term, value or level id, so it obeys 3.3 trivially:

```
CONG(c):   hdr: npos = min(arity, 64) | lamArity | flags (PROP_STATIC: the prop mask does not depend on ls)
  per position i, in index order:   class:4 | probe rank:6 ;  dep[i] (2 words)
     SAME     compare                                      the default
     TYPE     compare; the domain A_i is a sort, or a Pi telescope that ends in one (the argument is a
              type or a type former): a mismatch is cheap to find and decides most later positions, so
              these are probed first
     UNREAD   not read by the value's body: skipped under SAMEDEF when the spine covers lamArity
     PROOF    slvl[i] manifest and closed zero: skipped under the agreement rule
     PROOFL   slvl[i] manifest with parameters: PROOF when isAlwaysZero(slvl[i][ls]) (answered by SIGT)
  a position may be UNREAD and something else: UNREAD applies under SAMEDEF, the other class otherwise
  probe rank: TYPE positions first, then by decreasing number of later positions whose dep contains i,
              then last to first
```

```
CARGS(a, b, policy):   a, b NEU with the same length n and heads that agree
policy APP     (CONV c8):          heads CTOR, IND, AXIOM, FVAR, REC, PROJ, and stuck definitions
policy SAMEDEF (CONV c1, LAZYD):   the same definition on both sides
plan = CONG(c) when the head is a constant whose facts exist (not F_NONE); otherwise every position is SAME

pass A (inline, no frame; positions in index order, by class):
   SAME, TYPE     same(i) = a_i == b_i;  not same: hard += i
   UNREAD         SAMEDEF and n >= lamArity: skipped;  not same(i): differ += i     // skipped, but may really differ
                  otherwise: by the position's other class
   PROOF, PROOFL  (dep[i] and differ) == 0: skipped (the agreement rule);  otherwise as SAME
   positions >= 64 or beyond the syntactic telescope: as SAME
   quick refutation of a hard pair: two literals of different content, or two constructor applications
   with different constructors that are both known not to be proofs (PROOF = 1): ret F
   hard empty: ret T
pass B (policy SAMEDEF only): each hard pair under TRY(256), in the plan's probe order;
   F: ret F;   T: done;   U: left for pass C
pass C: the remaining pairs with the caller's fuel, in the official order (last to first for SAMEDEF,
   first to last for APP);  F: ret F;  all T: ret T
```

Only the speculative pass reorders. In pass C a kernel error is a verdict, and an error met before a pair
that the official order would have refuted first would turn the official `F` into a REJECT; inside a
`TRY` the same error is a `U`.

The agreement rule is the soundness condition of S3 made explicit. When `CARGS` returns `T`, every
position outside `differ` agrees: it is identical, was compared equal, or is a skipped proof position
whose domain reads only agreeing positions (induction from left to right over the direct dependency
mask `dep`, a superset; a compared position has convertible types on both sides because its arguments are
convertible). A position skipped as unread that actually differs disqualifies exactly the proof
positions whose domains read it, which are then compared like data (the caveat of R05 §4). Positions
>= 64 or beyond the syntactic telescope are always compared. A position whose sort is not manifest is
never a proof position (3.5). Different constructors refute a pair only for non-proofs: two proofs built
with different constructors (`Or.inl _`, `Or.inr _`) are convertible.

Inside inference the obligations of a spine (`TARG` steps) run strictly left to right, depth first, as in
the official checker: a failure there is a verdict, and the reference kernel must report the same site.

### 6.2 Budgets

| Segment | Budget (ticks) | Source of the starting value |
|---|---|---|
| same-head probe, regular hint (c1) | 16384 | nano-lean `probe_args` |
| same-head probe, abbrev / opaque hint (c1) | 1024 | nano-lean |
| one argument pair in pass B | 256 | gruszka D3 |
| closure congruence (c0, B12) | 1024 | gruszka `closure_congr` bounds its walk at 16384 nodes; smaller here because a pair that fails falls to `CBIND` or to forcing [est] |
| `MATCH` | 64 pattern nodes | rpylean `inst_eq` uses 512 on terms |
| fact derivation `SIGD` | 65536 | [est] |
| K2 derivation (off) | 65536, and 0.2% of the run's ticks in total | nano-lean `derive`; C2.11 |
| declaration, warm session | none (2^62) | official: none; RECONCILIATION §3 |
| declaration, strict or differential mode | `Budget.maxSteps` | facade (section 14) |

Budgets are counted in ticks (steps), never in billed instructions or in time, so that a verdict cannot
depend on load (owner note 10; `rlvm.api.Budget`). Nested segments: the inner budget is capped by what
the outer one has left (1.4); a pair that ended in `U` is not probed again under a budget of the same or
a smaller size class (`UNDEC`).

### 6.3 The fall-through guarantee

| # | Shortcut | Sits at | When inconclusive | Soundness condition |
|---|---|---|---|---|
| B1 | same-head argument probe for any unfoldable head | `CONV` c1 | `F`: pair gets `FAILED`, continue at c2; lazy delta then unfolds both sides, which the official kernel also does after a failed comparison. `U`: pair into `UNDEC`, continue at c2; lazy delta runs the official comparison itself | it runs the complete `CONV` on the arguments; `T` is congruence |
| B2 | a known proof is compared by type before it is reduced | c2 | not applicable: it enters the official step c4 early | proof irrelevance holds wherever both sides are proofs of convertible propositions (master does this, R02 §12.1; gruszka S7) |
| B3 | proof positions not compared | `CARGS` pass A, `ETAS` | position compared | 3.5 prop mask (manifest sorts only) + agreement rule |
| B4 | unread positions not compared | `CARGS`, SAMEDEF | position compared | 3.5 `unread` |
| B5 | pattern match instead of building a domain or a result type | `TARG`, `TRET` | build, then `CONV` | 4.4.4 |
| B6 | unread binder receives HOLE | `APPLY`, `BUNREAD`, `TARG`, `INST` | - (exact) | a clear mask bit is definite; HOLE unreadable |
| B7 | proof-irrelevance test skipped | c4 | test runs | `PROOF = 1` only from 3.5 |
| B8 | case-tree rules | `WHNF` | NONE: `DELTA` | 3.6 |
| B9 | expected-type checking of lambdas and applications | `CHECK` | generic `INFER` + `CONV` | 4.5.2 |
| B10 | k offset layers peeled at once | `OFFSET` | - (exact) | composition of official steps |
| B11 | LEAF blocks without a frame, spine run as argument run | `DELTA`, `APPLY`, `IOTA` | - (exact) | the same instructions |
| B12 | closure congruence: same term and `ls`, frames compared slot by slot | `CONV` c0 | `F` or `U`: continue (`CBIND`, or force both) | congruence of substitution (S11 of R05 §4); only `T` decides |

Rules: (1) a shortcut returns `T`, or hands over to the state that the complete procedure would have
entered without it; it never returns a final `F` of its own, except pass A's quick refutation, which is
the complete procedure's own answer on those forms. (2) Inside a `TRY`, a kernel error and a recoverable
limit are `U`; a fatal fault ends the declaration with DECLINE (1.4). (3) The complete procedure is the
sequence c0 (without B12), c2 (first line), c3..c13 with `CARGS` pass C only and every position SAME,
`TWALK`, `DELTA` without case trees, the dynamic `ISPROP`; removing every row of the table leaves exactly
that. `shortcheck` runs both. Strict mode (section 14) is rule 3 plus the exact rows B6 and B10.

## 7. Worked examples

Costs are **instruction estimates** [est] with the planning numbers of the header (`I` = 120, `P` = 25,
`D` = 12), cold caches, everything elaborated to K1, to within about 50%. They are kept to show where the
work of a judgment goes. For CPU time the line "records, runs, bytes" of each total is the better
predictor: a new record costs about 55 ns and a hit about 4 ns [meas, 0.3]. None of the traces has been
measured. `n`, `m`, `a`, `b`, `f`, `h`, `x` are free
variables. All definitions are the exported ones (3.7 and `Init.Prelude`).

### 7a. Inferring `@congrArg.{1,1} Nat Nat a b f h` in CHECK mode against `TELE(congrArg)`

`a b : Nat`, `f : Nat -> Nat` (a PI value made from the binder type in the proof), `h : Eq.{1} Nat a b`.

| Step | Transition | Instr |
|---|---|--:|
| 1 | `INFER` app: node not SHARED, no probe; spine of 6 argument terms into `X` | 56 |
| 2 | `CHKCONST congrArg {1,1}`; push `ACT(TELE(congrArg))`, `ls = {u := 1, v := 1}` | 70 |
| 3 | `TARG 0`: `INFER(Nat)` = `Sort 1` (constant, memo); domain `SORT u[ls]` = the same record; `g0 = Nat` | 115 |
| 4 | `TARG 1`: the same for `β` | 115 |
| 5 | `TARG 2`: variable: `GET`, type word of the FVAR = `Nat`; domain `g0`: identical; `g2 = a` | 50 |
| 6 | `TARG 3`: likewise, `g3 = b` | 50 |
| 7 | `TARG 4`: type of `f` is a PI over another term than the telescope's, so `MATCH` says unknown; build `PI(α -> β, FRAME[g0, g1])` (330); `CONV`: `CBIND`: domains identical, neither body reads its variable, both codomains evaluate to `Nat`: `T`; `POS +=` | 700 |
| 8 | `TARG 5`: type of `h` is `NEU(Eq.{1}, [Nat, a, b])`; `MATCH` against `Eq.{u} g0 g2 g3`: head row, `LVLEQ`, three identical arguments; `h` is not read later: `g5 = HOLE` | 110 |
| 9 | result: `MKAPP(f, a)`, `MKAPP(f, b)` (165 each), `KCONST Eq {1}`, `MKAPP` with 3 arguments (255), `TRET`, pop | 700 |
| | **total**: 4 records, 3 runs, about 250 bytes | **about 1970** |

If the type of `f` had been made from the telescope's own Pi (or the pair is already in `POS`), step 7
costs about 60 and the total is about 1330. In ONLY mode steps 3-8 reduce to five `ARGV` reads: about 950.
The result is a proof by `rlvl = 0`, so a later comparison of two such applications goes to c4 at once.

### 7b. Unfolding and iota: `Nat.add n (Nat.succ m)`, and a `List.rec` step

`v0 = NEU(Nat.add, [n, NSUC(m, 1)])`. The exported chain is `Nat.add` -> `Nat.brecOn` -> `(Nat.brecOn.go ..).0`
-> `Nat.rec` (building `PProd` pairs) -> `Nat.add._f` -> `Nat.add.match_1` -> `Nat.casesOn` -> `Nat.rec`.

| Step | Transition (`WHNF(v0)`, K1) | Instr |
|---|---|--:|
| 1 | `CORE(v0)` = itself; `NATFOLD`: `WHNF(n)` is not a literal: NONE | 50 |
| 2 | `DELTA`: `BODY(Nat.add)` LEAF: `v1 = Nat.brecOn M (succ m) Nat.add._f n` (one `MKAPP`, 4 arguments; `M` from the pool) | 445 |
| 3 | `DELTA(v1)`: `BODY(Nat.brecOn)`: `MKAPP go`, `MKPROJ PProd 0` (struct is not a constructor: PROJ cell), over-application `MKAPP [n]`: `v2 = (go M (succ m) F).0 n` | 700 |
| 4 | `CORE(v2, full)`: PROJ head, so `WHNF(go M (succ m) F)`: `DELTA` runs `BODY(go)` (2 closures, base pair with 2 suspensions, `Nat.rec` application: 2135); `IOTA`: major `NSUC(m, 1)` is a successor view with field `m`; `RULE(Nat.rec, succ)` builds the hypothesis `Nat.rec .. m` and applies the step closure, whose block builds the `PProd.mk` pair (2420); field 0 is `Nat.add._f (succ m) IH`; `APPLY [n]` | 4950 |
| 5 | `DELTA`: `BODY(Nat.add._f)` LEAF: `Nat.add.match_1 mot n (succ m) alt1 alt2 IH` (6 arguments, 3 from the pool) | 560 |
| 6 | `DELTA`: `BODY(match_1)`: 2 closures, 1 suspension, `Nat.casesOn` application, extra argument | 2100 |
| 7 | `DELTA`: `BODY(Nat.casesOn)`: 1 closure, `Nat.rec` application, extra argument | 1060 |
| 8 | `CORE`: `IOTA`: successor arm; `BUNREAD` finds that the step `fun n ih => succ' n` does not read `ih`, so the hypothesis is not built; three betas reach `alt2 n m IH` = `Nat.succ (IH.0 n)`: PROJ cell, `MKAPP`, NSUC | 1060 |
| 9 | result `NSUC(NEU(PROJ(IH, PProd, 0), [n]), 1)` has flag WHNF; memo writes | 30 |
| | **total K1**: about 40 records, 25 runs, 2.3 KB | **about 11 000** |

With the K1 case trees (3.6) steps 6-8 become one `RULES(Nat.add.match_1)` step (the discriminant is
already a successor view, the second alternative is applied; the motive, the other alternative and the
`Nat.casesOn` and `Nat.rec` applications are never built): about 700 instead of 4 220, total about 7 500.
With K2 (off, 3.6), `RULES(Nat.add)` is a tree on the second argument: `WHNF A1; SWITCH`; zero arm `RET A0`;
successor arm `FIELDS p`; then the leaf template `NSUCC(MKAPP(MKPROJ(PProd, 0, MKAPP(Nat.rec, M', B', S', p)), A0))`
with `M'`, `B'`, `S'` closed pool values: 965 instructions and 4 records, 11x less; the remaining cost is
four probes. On literals nothing of this runs: `Nat.add 3 5` is `NATFOLD` + `NATOP` on immediates, about
60 instructions and no record.

`List.rec α motive nilc consc (List.cons α a as)`: `IOTA` 25; `WHNF(major)` is a flag test 6; rule
arguments `[α, motive, nilc, consc, a, as]` into scratch 36; `RULE(List.rec, cons)`: `BUNREAD consc, 2` 15,
`KCONST` 35, `MKAPP List.rec [α, motive, nilc, consc, as]` 345, `TAILAPPLY consc [a, as, ih]` 15, decode 48:
about 525 before the minor premise's own body; 145 when the minor premise does not read its induction
hypothesis (every `casesOn`-style minor), because the recursive application is then never built.

### 7c. A type-class instance projection chain

`v0 = HAdd.hAdd.{0,0,0} Nat Nat Nat (instHAdd.{0} Nat instAddNat) n m`, `I = instHAdd.{0} Nat instAddNat`.

| Step | Transition (`WHNF(v0)`) | Instr |
|---|---|--:|
| 1 | `CORE(v0)` = itself (DEFN head), no nat-op | 31 |
| 2 | `DELTA`: `BODY(HAdd.hAdd)` LEAF: `MKPROJ(HAdd, 0, I)`: `I` is not a constructor application, PROJ cell; extra arguments: `v1 = I.0 n m` | 425 |
| 3 | `CORE(v1, full)`: PROJ head: `WHNF(I)`: `DELTA` runs `BODY(instHAdd)` LEAF: closure + `HAdd.mk` application (750), a constructor; field 0 is the closure; `APPLY [n, m]` runs its block: `v2 = Add.add Nat instAddNat n m` (400) | 1285 |
| 4 | `DELTA(v2)`: `BODY(Add.add)` LEAF: `v3 = instAddNat.0 n m` | 425 |
| 5 | `CORE(v3, full)`: `WHNF(instAddNat)`: its value `Add.mk Nat Nat.add` (300 once per session); field 0 = `Nat.add`; `v4 = Nat.add n m` | 630 |
| 6 | `NATFOLD` no; `DELTA`, `DELTA` as 7b steps 2-3; `CORE`: `WHNF(go M m F)` reaches `Nat.rec .. m` with a variable major: `IOTA` = NONE, the projection is stuck and the original cell `(go M m F).0 n` is kept | 3475 |
| | **total**: steps 1-5 (instances only) about 2800 cold, about 1600 for another sum at the same instance (the instance values and their `WHNF` are memoised) | **about 6300** |

### 7d. Lazy delta on applications of different definitions: `x + y ≡ Nat.add x y`

`t = v0` of 7c, `s = NEU(Nat.add, [n, m])`. `CONV(t, s)`:

| State | Transition | Instr |
|---|---|--:|
| c0 | ids differ; `POS`, `NEG` miss; NEU / NEU | 145 |
| c1, c2 | different heads; `s` is not `Bool.true`; `PROOF(t)` unknown | 15 |
| c3 | `CORE` cheap: both DEFN heads, unchanged | 12 |
| c4 | `VTYPE(t)` by `TELE(HAdd.hAdd)` in ONLY mode = `Nat`; `ISPROP(Nat)` = no; `PROOF(t) = 1`; UNDEF | 220 |
| c5 l2 | both unfoldable, `HAdd.hAdd` is an abbrev, `Nat.add` is regular: unfold `t`: 7c step 2, then `CORE` cheap: the struct `I` has a DEFN head, cheap mode does not unfold it: `tn = I.0 n m` | 510 |
| l2 | `tn` has a PROJ head, only `sn` unfolds: the official exception applies: `CORE(tn, full)` changes it (7c step 3): `tn = Add.add Nat instAddNat n m` | 1306 |
| l2 | abbrev against regular: unfold `tn` (7c step 4), `CORE` cheap stuck: `tn = instAddNat.0 n m` | 506 |
| l2, l3 | PROJ head again: `CORE(tn, full)` (7c step 5) = `NEU(Nat.add, [n, m])`, the record of `s`: quick says `T` | 648 |
| rec | `POS += (t, s)` | 105 |
| | **total**; `Nat.add` is never unfolded; frames: `CONV`, `LAZYD`, and the `CORE` / `WHNF` / `DELTA` frames of 7c | **about 3500** |

### 7e. One theorem end to end

`theorem Nat.add_zero : ∀ n, n + 0 = n := fun n => rfl.{1} Nat (n + 0)` (type 25 DAG nodes, value 24; the
node `E` = `n + 0` = `HAdd.hAdd Nat Nat Nat (instHAdd Nat instAddNat) #0 (OfNat.ofNat Nat 0 (instOfNatNat 0))`
is shared by both). `DECL` phases:

| Phase | Transitions | Instr |
|---|---|--:|
| 1. `INFER(type, CHECK)` | pi: `Nat : Sort 1`; `OPEN x = FVAR(Nat, 0)`. `TELE(Eq)`: `Nat` ok; `E` is SHARED: `SUSP(E, [x])` made, `TELE(HAdd.hAdd)`: `α`, `β` ok (230); `γ`: domain `outParam (Sort 1)` against `Sort 1`: `MATCH` unknown, build, `CONV` unfolds the abbrev `outParam` (LEAF picker): `T` (650); `self`: `TELE(instHAdd)` returns `HAdd Nat Nat Nat`, matched (990), value not needed; `x` (50); `OfNat.ofNat Nat 0 (instOfNatNat 0)` through two telescopes (715); result `Nat`; stored in `TYPE(SUSP E)` with stamp `gen`. `E`'s value is not needed (`Eq`'s result does not read it). `x : Nat` ok. Result `Prop`; level `imax 1 0 = 0`; `ISPROP` ok | 3600 |
| 2. `EVAL(type)` | `V = PI(type, 0, 0)` | 240 |
| 3. `CHECK(value, V)` | lam against PI: domain node `Nat` checked and identical; `OPEN` gives the same `x`, `PUSH` the same environment. `INST(V, x)`: `B = Eq Nat E1 x`, where `E1 = VAL(SUSP E)` is built now (7 records) | 2270 |
| | `TELE(rfl)` with expected `B`: `Nat` ok; `INFER(E)`: `TYPE(SUSP E)` hit (stamp = `gen`); `g1 = E1` (VAL hit) | 470 |
| | `TRET`: `MATCH(Eq.{u} g0 g1 g1, B)` fails at the third argument; build `R = Eq Nat E1 E1`; `CONV(R, B)`: IND heads, not proofs (static), `LAZYD` UNDEF, c8: `CARGS` pass A leaves one hard pair `(E1, x)` | 630 |
| | `CONV(E1, x)`: c4 not a proof; `LAZYD`: unfold `E1` (cheap) -> stuck projection, UNDEF; c7 full `CORE` -> `Add.add ..`; again -> `Nat.add x Z` with `Z = OfNat.ofNat Nat 0 (instOfNatNat 0)` (3400, as 7d); `LAZYD`: two `DELTA` -> `(go M Z F).0 x` (1310); c7: `WHNF(go M Z F)`: `BODY(go)` (2135), `IOTA`: `WHNF(Z)` = literal 0 through `OfNat.ofNat` and `instOfNatNat` (665), zero view, `RULE(Nat.rec, zero)` is a LEAF picker, field 0, `APPLY [x]` = `Nat.add._f Nat.zero PUnit.unit x` (3210); `LAZYD`: `DELTA` x3 (`_f`, `match_1`, `casesOn`: 3720), `IOTA` on `Nat.zero`: the minor `alt1 x` is forced and applied to the unread argument: `x` (510); quick: identical to `x`: `T` | 12 500 |
| | `POS += (E1, x), (R, B)`; `CLOSE` | 190 |
| | **total**: about 75 records, 4 KB; 63% is the one conversion `n + 0 ≡ n`, about 80% of which is interning | **about 19 900** |

That is about 400 instructions per term node of the declaration, before decoding. With a K2 tree (off, 3.6) of
`Nat.brecOn.go` the `WHNF(go M Z F)` step drops from 3210 to about 1900. A later declaration that needs
`n + 0 ≡ n` in the same session meets the same `FVAR(Nat, 0)`, the same `E1`, and the pair in `POS`: 60.

## 8. Tiers, the host control convention, and the Truffle experiment

### 8.1 Tiers

| Tier | Code | Executed by | Entered | Status |
|---|---|---|---|---|
| K0 | term nodes | generic judgments: Java state machines, compiled ahead of time or by the host JIT | always available; the only tier for run-once terms, overlay terms and strict mode | v0 |
| K1 | code objects (3.4), comparison plans (6.1), case trees read off the value (3.6) | the code stepper, a plain-Java dispatch loop over `int[]` | rent-to-own, about the 10th use of a role (3.1) | staging steps 2-4 |
| K2 | derived case trees (3.6) | the same loop | 32 executions of a body in a session | off until the conditions of 3.6 hold |
| T2 | one Truffle `RootNode` per promoted block | Graal-compiled code | rent-to-own in CPU time (8.5) | experiment, off (0.5); not part of `rlvm-machine` |

A Truffle interpreter costs 1.6x (AST) or 3.4x (Bytecode DSL) of plain Java (R10), so no block would ever
be *interpreted* by Truffle; a block runs in the code stepper until compiled code is installed.

### 8.2 The host-level control convention

The transition system of 1.3 is the specification; this subsection is about how Java executes it.
RECONCILIATION B4 fixes two candidates and an acceptance test, and requires the choice to be made by
measurement before any judgment is written, because about 25 state machines are written against it.

**Common to both candidates.**

- One operation library `Ops`: static methods `(Machine, int...) -> int` over the arrays of 1.1; no
  allocation, no virtual call, no exception on a normal path. Each operation is split into an inlinable
  fast part (flag, identity, memo, cache hit; a few dozen bytecodes) and an out-of-line slow part (probe
  miss and insert, growth, `rlvm-level` and `rlvm-nat` slow paths). Generic judgments and the code
  stepper call the same methods; an instruction's semantics exists once.
- Judgments are grouped into **families**, each one Java method that is a fused loop:

  | Family | Ops |
  |---|---|
  | EV | `EVAL`, `FORCE`, `APPLY`, `INST` |
  | RD | `DELTA`, `CORE`, `WHNF`, `IOTA`, `QUOTR`, `RULES`, `NATF` |
  | CV | `CONV`, `CARGS`, `CCLO`, `CBIND`, `LAZYD`, `PROJD`, `PIRR`, `ETA`, `ETAS`, `UNITL` |
  | TY | `INFER`, `CHECK`, `TWALK`, `VTYPE`, `IPROJ`, `SIGD` |
  | CODE | `ACT` |
  | control | `TRY`, `ERF`, `DECL` |

  Inside the loop `W`, `wp` and `A` are Java locals; dispatch is one dense `switch` on
  `key = W[wp-1] >>> 20` (`op << 4 | st`, the ops of a family numbered contiguously). A `call` to a
  judgment of the same family writes the caller's state and pushes: a `continue`. A `ret` into a frame
  of the same family is a `continue`.
- Inert evaluation (a nest of constant-headed applications) is one `EVAL` frame looping over scratch `X`,
  not one frame per node, as `MATCH` and `STRVIEW` already are.
- Every family method stays under 8,000 bytecodes, the size above which HotSpot does not compile a
  method, so that the JVM platform candidate can compile it; slow paths are separate methods.
- Frame layouts (1.3), `Ops`, memo rules and tests do not depend on the candidate.

**Candidate (i): fused loops only.** Every frame is materialised in `W`. A `call` or `ret` that crosses
families stores `wp` and `A` back into the `Machine` and returns from the family method; the driver loop
re-dispatches on the family of the top frame:

```java
void run() {                                         // the only loop above the families
  for (;;) switch (Op.FAMILY[W[wp - 1] >>> 24]) {
      case Op.EV: Eval.run(this); break;     case Op.RD: Reduce.run(this); break;
      case Op.CV: Conv.run(this); break;     case Op.TY: Infer.run(this); break;
      case Op.CODE: Code.run(this); break;   case Op.CTL: if (Control.step(this)) return; } }
```

The host stack depth is constant (driver, family method, `Ops`). Cost per cross-family call: two method
returns/entries, reloading `W`, `wp`, `A` into locals, one extra dispatch.

**Candidate (ii): the same, plus bounded host recursion.** The host stack acts as a cache of the top of
the worklist. A cross-family `call` is a Java call, `status = Reduce.enter(m, key, operands, depth + 1)`:
the callee family runs its fused loop on the frames above its entry `wp` and returns when that level is
reached again. The caller's frame has its slot in `W` *reserved* (the header position is fixed and `wp`
is bumped when the frame starts) but its payload and state stay in Java locals. Three statuses:

```
DONE      A holds the result; the caller continues in its next state without having written its frame
SUSPEND   frames were materialised: the caller writes its own payload and state into its reserved slot
          and returns SUSPEND; when the driver loop of (i) is reached, W holds exactly the frames
          candidate (i) would hold, and execution continues from W
UNWIND    W was cut to a position p (exhaust, fail, fault): every host activation whose slot is at or
          above p returns UNWIND at once without writing; the activation that owns the frame below the
          cut continues with A (U, or the declaration's outcome)
```

Frames are materialised (`SUSPEND`) when `depth` reaches a fixed bound `HOST_DEPTH` (so that thread stacks
need no enlargement and depth never depends on the input), and whenever something must see them in `W`: a
`TRY` marker is always written, and the poll every 4096 ticks may request materialisation for a
cancellation. With `HOST_DEPTH = 0` candidate (ii) *is* candidate (i), which is the equivalence test
between them. Cost per cross-family call: one Java call and one status test; the frame write is avoided
when the callee answers without suspending.

**Acceptance** (both): at most **+15% CPU time** over plain host recursion on eval + readback of Init's
and Cedar's declaration types, with frames of the layout of 1.3; and a regression test on `call` events
per declaration (section 11).

**Decision (2026-11-24).** `rlvm-machine` uses **convention H: bounded host recursion with lazily written
frames, host depth bound 64**, as measured by `spikes/stepper` and specified in
`docs/design/review/stepper-convention.md`, whose rules H1-H12 and code template are normative for how
every judgment is written. It refines candidate (ii) in three points: no fused loop inside a family
(every sub-judgment is a host call, a judgment is a chain of step methods whose parameter lists are the
frame payloads); no reserved frame slot (frames reach `W` through a side buffer when a judgment is
suspended, so the normal path never touches `W`); a `TRY` marker is written only if its segment is
suspended, and the 4096-tick poll raises a fatal fault instead of requesting materialisation. Measured
cost against plain host recursion: +1% to +8% CPU by runtime (native image CE, Graal JIT, C2), frames
written per judgment 0.00011 on Init. With the bound set to 0 the same source is the worklist machine of
1.3 (the equivalence test). In the module the bound is the per-session field `Machine.hostDepth`
(default `Op.HOST_DEPTH = 64`), so that one build runs D = 0, 64 and unbounded; the per-tick line is
`if ((--m.fuel & Op.POLL_MASK) == Op.POLL_MASK && m.tickSlow()) return m.sig;` (exhaustion and the poll
share one counter). The items "common to both candidates" above lose their family-loop points (dense
per-family dispatch remains only in the driver's `resume` switches); the 8,000-bytecode limit is replaced
by the step sizes of H10 (entry step at most 325 bytecodes, continuation step at most 150, checked by a
unit test over the class files); the code stepper is entry and continuation step of `ACT` (H7). This
paragraph is the only place in this document that depends on the outcome: the semantics, layouts,
operation library, tests and work packages are the same, with the consistency notes at the end of 1.3
and in 13.7.

The code stepper, as a family method (shown for candidate (i); under (ii) the `return` of a class-j miss
becomes the host call above):

```java
static void run(Machine m) {                         // Code.run: the top ACT until it returns or calls out
  final int[] W = m.W;  int fp = m.wp - 1 - (W[m.wp - 1] & 0xFFFF);
  final int[] code = m.codeOf(W[fp + Op.ACT_BLK]);  int pc = W[fp + Op.ACT_PC];
  for (;;) { int w = code[pc];  switch (w >>> 24) {
      case Op.MKAPP: W[fp + Op.ACT_R0 + d(w)] = Ops.mkApp(m, code, pc, fp);  pc += 1 + n(w);  break;      // class p
      case Op.WHNF:  { int v = Ops.opnd(m, code[pc + 1], fp);  int r = Ops.whnfFast(m, v);                // class j
                       if (r == Ops.MISS) { W[fp + Op.ACT_PC] = pc + 2;  W[fp + Op.ACT_DST] = d(w);
                                            Frames.pushWhnf(m, v);  return; }
                       W[fp + Op.ACT_R0 + d(w)] = r;  pc += 2;  break; }
      case Op.RET:   m.A = Ops.opnd(m, code[pc + 1], fp);  m.wp = fp;  return;
      ... } } }
```

When a judgment returns into an `ACT`, the stepper first stores `A` into the register named by `ACT_DST`.

### 8.3 Heat

Three counters, none on a per-step path:

- per row and role, a shared saturating request counter (racy, 4 bits) that triggers elaboration at
  `ELAB_AT` (3.1);
- per worker and block, `heat[block]` = entries (`ACT` pushes and LEAF runs), a worker-local `int[]`
  written only when `EngineConfig.stats` or an experiment (K2, T2) is enabled; an experiment's promotion
  test sums it over the workers with racy reads every 4096 entries (C2.11);
- per session, counters of `call` events by op, for the frame regression test and `dumpStats`.

### 8.4 The T2 experiment: a Truffle root per promoted block (design sketch)

Not built unless 0.5 says go. Kept here so that the experiment starts from a design; **K0 and K1 make no
concession to it** (no resume-point tables in blocks, no site numbering, no reserved activation layout).

```java
final class BlockRoot extends RootNode {                 // one per promoted block, shared by all workers
  @CompilationFinal(dimensions = 1) final int[] code;    // the block (immutable)
  @Children final Instr[] instr;                         // one node per instruction; operands in final fields
  @Override public Object execute(VirtualFrame f) {
    Machine m = (Machine) f.getArguments()[0];           // worker state is an argument, never a node field
    int fp = m.entryFp;  int pc = selectEntry(m.entryPc);     // exploded compare chain: pc is a PE constant
    if (pc != 0) loadRegisters(f, m, fp);                // resume: registers of the ACT frame -> frame slots
    return run(f, m, fp, pc); }
  @ExplodeLoop(kind = LoopExplosionKind.MERGE_EXPLODE)
  Object run(VirtualFrame f, Machine m, int fp, int pc) {
    for (;;) { CompilerAsserts.partialEvaluationConstant(pc);
               pc = instr[pc].execute(f, m, fp);         // next pc; negative: a status
               if (pc < 0) return null; } }
}
```

| Aspect | Design |
|---|---|
| nodes | one final `Instr` subclass per opcode; `execute` reads operands from frame slots or from `m`, calls the `Ops` fast method, returns a constant successor pc |
| registers | static `int` frame slots; written to the `ACT` frame in `W` only when the root suspends |
| class-j instruction | fast hit: continue. Miss: exactly the `SUSPEND` protocol of candidate (ii): write the activation, push the generic frame, return to the stepper, which re-enters the root at the resume pc |
| calls between promoted blocks | `DirectCallNode`, at most 64 deep; a callee that returns `SUSPEND` makes the caller materialise and return `SUSPEND` |
| cut of `W` | **required before the experiment may run** (C3.11): every nested compiled activation propagates `UNWIND` before the stepper continues, so that no compiled code writes above `wp` |
| inline caches | the `IC` table of 1.5, shared with the base tier |
| no-deopt policy | no `transferToInterpreterAndInvalidate`, no rewriting specialisation; kind dispatch is an explicit `switch`; Nat overflow is a compare and a branch to the big path; `engine.MaximumCompilations = 1` |
| boundaries (`@TruffleBoundary`) | probe miss / insert / growth, `PRUNE`, `rlvm-level` and `rlvm-nat` slow paths, materialisation, elaboration |

What compiled code can remove: `D` per instruction, operand-mode decoding, register traffic through `W`,
the frame of calls between promoted blocks, and tag tests on Nat registers (owner note 7). What it
cannot remove: hash-consing probes and their cache misses, which dominate the CPU time (0.3). Measured
on telescope roots: 0-3% of the pass time call-threaded, 12-23% with the probes inlined [meas].

### 8.5 Compilation budget (for the experiment)

Budgets are in CPU time and steps, never in billed instructions (owner note 10).

```
promote(b) iff  an optimising Truffle runtime is present and the experiment is enabled
           and  spent(b) >= RENT = 2 * C / σ = 210 ms      // C = 18 ms per root, σ = 0.17 [meas, hot subset]
           and  compileCpu + C <= 0.01 * processCpu        // governor: compiler CPU time at most 1% of the run
           and  promoted < 64 and not t2Hostile(b)
spent(b) = (sum of heat[b] over the workers) * unit(b);  unit(b) = the block's measured mean time per entry,
           sampled with System.nanoTime on one entry in 1024
```

`engine.MultiTier=false`, `engine.CompilerThreads=1`, `engine.BackgroundCompilation=true`,
`engine.Splitting=false`, `engine.OSR=false`, `engine.DynamicCompilationThresholds=false`,
`engine.MaximumCompilations=1`, `compiler.MaximumGraalGraphSize=20000`,
`engine.CompilationFailureAction=Silent` (a failed compilation sets `t2Hostile`).

Why the expectation is no-go [meas; R13 §5]: at 257 ns per execution in the base tier a telescope root
reaches RENT after about 0.8 M executions and needs about 0.4 M more to repay its compilation at 44 ns
saved per execution. On mathlib with one worker one telescope (`Eq`, 3.1 M) runs that often; the next
runs 0.92 M times, the hottest projection body 0.69 M, the hottest rule 0.58 M, and heat divides by the
number of workers unless it is summed. Background compilation takes the compiler off the workers' wall
clock but not off the CPU-time total, which is the metric. Closed evaluation is where the experiment
could pay (`perf/unfold-before-literal-fallback` and `proj-applied-struct-arg-bad`: 3.15 M `Nat.rec` steps
and 1.57 M unfoldings each); there the bound is the interning share, which compiled code keeps.

### 8.6 Native image, and running without a compiler

- Closed world: no reflection, no class loading, no generated classes, plain `int[]` storage, no
  `Unsafe`. `rlvm-machine` has no Truffle dependency; an image without the Truffle runtime runs K0-K1 (and
  K2) as specified and produces the same value ids and the same verdicts.
- The collector is irrelevant to the machine: its working set is primitive arrays allocated once.
- The platform (JVM with JIT, native image CE, native image with PGO) is decided by CPU time and wall
  clock when the machine first accepts Init (section 11). The spike's hint [meas: Init, code objects,
  first pass elapsed / process CPU]: `nce` 756-842 ms / 1.08 s; Oracle image 654-729 ms / 0.91 s; `jvm`
  743-900 ms / 2.53 s. The JVM's steady code is as fast, but its JIT and start-up add about 1.5 s of CPU
  on this one-second workload; the instruction counts (3.2 G, 3.0 G, 21 G net) predict neither column.

## 9. Stack safety, limits, and what is reported

| Recursion in the algorithm | Where it lives |
|---|---|
| structure of terms in `EVAL`, `INFER`, `CHECK` (a 913 500-element list literal is 913 500 nested applications) | worklist frames |
| nested reduction (major premise of a major premise, struct of a struct, forced suspensions) | worklist frames |
| nested conversion, binders, eta | worklist frames, binders in `SC` |
| spines, let chains, telescopes, lazy-delta and whnf loops, inert application nests | loops inside one frame |
| elaboration, fact derivation walks, `MATCH`, `STRVIEW`, spine collection | loops with scratch stacks; `MATCH` bounded at 64 nodes |
| level algorithms, big-number arithmetic | `rlvm-level`, `rlvm-nat`: iterative or with a declared depth bound (R2, R3); a limit there surfaces as a `RuntimeException` that the facade turns into DECLINE |

Worklist frames are in `W`; under convention (ii) of 8.2 at most `HOST_DEPTH` of the top ones are held on
the host stack. Either way the host stack depth is bounded by a constant that does not depend on the
input. Worker threads need no enlarged stack and there is no hand-up trampoline.

| Limit | Value | Outcome |
|---|---|---|
| `W` | 2^28 words (1 GiB) | `DECLINE(E_STACK)` |
| value records, run words per session | 2^28; earlier: `Budget.maxBytes` and the run's memory watermark | `DECLINE(E_MEM)`; ARCHITECTURE retries a declaration that started in a warm session once in a fresh one |
| spine length, open binders | 65535 | `DECLINE(E_LIMIT)` |
| registers of a block, far frames | 255; mask bit 63 | not a failure: the role stays at the generic tier |
| Nat literal size, `Nat.pow` / `Nat.shiftLeft` guards | R02 §7.6 | `REJECT(E_NATSIZE)`: the official kernel throws here, and the machine mirrors it (RECONCILIATION §2); the reference kernel confirms the rejection like any other. Inside a `TRY`: `U` |
| declaration steps | none in warm sessions; `Budget.maxSteps` in strict and differential modes | `DECLINE(E_STEPS)` |
| prelude guard INVALID for an extension that is needed | 2.9 | `DECLINE(E_PRELUDE)` |
| an unbound constant node reaches the machine (the structural checks never pass such a root) | | `DECLINE(E_INTERNAL)`, never a REJECT |
| broken invariant (HOLE read, ill-typed application, invisible constant, failed audit, environment bounds) | | `DECLINE` with `E_HOLE`, `E_ILLTYPED`, `E_VIS`, `E_AUDIT` or `E_INTERNAL`, also inside a `TRY`; session reset |
| a unit earlier in file order failed | polled every 4096 ticks | `DECLINE(E_CANCEL)` |

The machine returns `ACCEPT`, `REJECT(E, site)` or `DECLINE(E)` per declaration. `ACCEPT` is produced only
by the `DECL` frame after its last phase; every other way out is a `REJECT` or a `DECLINE`. A `REJECT` is
handed to ARCHITECTURE, which confirms it with the reference kernel before it becomes the verdict. Inside
a `TRY` a kernel error or a recoverable limit does not terminate the declaration; a fatal fault does
(1.4). Section 14 maps the codes to `rlvm.api.Reason`.

## 10. In-place reuse and reference-count bits

| Candidate | Ownership | Mechanism | Verdict |
|---|---|---|---|
| hash-consed records (all values of section 2) | shared by definition: the key is the content | none possible | **no bits**: a record is never mutated except its memo words and flags |
| memo words, `VAL` of a SUSP | single assignment, idempotent, worker-local | plain store; `PEEK` shortens SUSP chains | **yes**: this is the thunk update |
| worklist frames | exactly one owner (the stepper); a frame is dead when popped | state, pc and registers are updated in place; `TAILCALL` / `TAILAPPLY` overwrite the frame | **yes**, by construction (R11 M1) |
| scratch `X` (spine collection, rule arguments, argument runs of `CALL`) | the frame that pushed, LIFO | bump and pop; a scratch run is copied into `rs` only if a closure or a NEU captures it | **yes** (M1) |
| working environments | the environment on top of `es` (2.4) | `PUSH` extends it in place when the parent ends at the top (monotone claim, M2); no flag, no mark: a second extension of the same parent finds the top moved and copies | **yes**: it removes the O(depth) copy per opened binder, which is the quadratic trap of the ladder tests |
| the record on top of `vh` | not yet interned | probe first, write only on a miss: no record is ever allocated and abandoned | **yes**, trivially |
| arena rollback after a speculative segment or a large `WHNF` (gruszka regions) | segment | needs an undo log of memo writes that point into the segment | **v2**, only if a memory wall is measured |
| non-interned cells in a closed-computation mode | one activation, until they escape | ref bit 30 EPH = record lives in a per-activation scratch arena and is in no table; ref bit 29 UNQ = this ref is the only one (Stoye's bit in the pointer), which would license in-place constructor reuse; a cell is frozen (interned) when stored into a run, a memo or a hash-consed record | **v2 experiment, default off** |

Honest accounting for the last row. What would be saved: one `I` = 120 per intermediate constructor
cell, replaced by a 15-instruction bump; reuse in place saves a further 16 per cell [meas, R11 A.4]. What
is lost: identity. A non-interned cell cannot hit `WHNF`/`CONV` memos, and gruszka's ground machine, which
is this design, is 10x slower than the hash-consed kernel when work repeats (677 G against 68 G, R05
§2.11). What the static route already gives without any bit: the case trees of 3.6 never build the
motive and the unchosen alternatives of a match, unread hypotheses are never built
(`BUNREAD`), Nat arithmetic never builds constructors. The dynamic UNQ bit must be maintained at every duplication of a ref by
hand-written Java; one missed mark is an unsound write, i.e. a wrongly accepted proof. Verdict: **no
reference-count or uniqueness bits in v1**; the two tag bits are reserved; the experiment is run in v2
only after a shadow-bit profile (R11 A.6) on the perf tests shows that cells are unique where it matters.

What `R13` adds: construction, interning and the hash tables are 25% of the instructions on cedar and 42% on
reachability, 33-49% on the evaluation tests, and rpylean's machine without interning is 1.7-2x faster than
nano-lean on two of them [R13 §5]. Not interning transient values is therefore the largest remaining lever
on closed evaluation, and it is the same experiment as the last row of the table: it stays behind the
measurement above, and it is the first of the later experiments of section 11 to run once the evaluation
tests are the bottleneck.

## 11. Staging and testing

Staging follows RECONCILIATION §3: a correctness gate first, then six steps, each followed by a
measurement of **CPU time, wall clock, peak RSS and instructions** on Init, Std and the two fast
Cedar-level benchmarks of `R13 §6` (`cedar-hot.ndjson`, `reachability.ndjson`) under
`bench/arena-docker.sh --arena-limits`. A step is merged when its gate is recorded; nothing of a later
step is started inside an earlier one.

| Stage | Machine subset | Gate |
|---|---|---|
| before any judgment | control convention (8.2) | `spikes/stepper`: at most +15% CPU over recursion |
| v0 | values and runs with interning and canonical ids (`FVAR(type, k)`, NSUC, HOLE pruning by the node mask); generic tier only; **every non-leaf node tabled**; `EVAL`, `FORCE`, `APPLY`, `INST`, `DELTA`, `CORE`, `WHNF`, `IOTA` (K, structure eta, literal views), `QUOTR`, `NATOP` / `NATFOLD`, `STRVIEW`; `CONV` as the complete procedure (6.3 rule 3); `INFER` / `CHECK` / `VTYPE` / `IPROJ` for every term kind; exact shortcuts B6, B10 only; session = one declaration; the facade with the Reason mapping, strict mode and cancellation | diff-clean against the reference engine on the 141 tutorial tests and on `bugs/`, then on all 230 published tests (inductive and quot records by the reference kernel) |
| 1 | sessions: footprint cap, O(used) reset, check stamps (5.3); tabling gated by SHARED / BODY / INVARIANT; shared check facts (`PCHK`); B9 | **v1 gate**: diff-clean on Init and Std. Init at most 2.5x gruszka's CPU time (7.0 s). The platform gate (JVM with JIT, native image CE, native image with PGO) is run here, on CPU time and wall clock |
| 2 | static masks with lazy facts: `FACTS` / `SIGD`, comparison plans with classes UNREAD, PROOF, PROOFL (B3, B4), static `PROOF` (B7), the `ETAS` mask | `SIG` exists for every constructor and recursor of Init; the three mutation tests of 3.5 |
| 3 | K1 `TELE` and `MATCH` of domains and results (B5); the `IC` table | session-cap sweep 8 / 16 / 32 / 64 MiB on Std |
| 4 | K1 `BODY` / `RULE`, LEAF blocks (B11), the `CASESON` and `MATCHER` case trees (B8) | Init at most 1.5x gruszka's CPU time (4.2 s); `ELAB_AT` sweep; tier equivalence K0 = K1; the T2 go/no-go of 0.5 may be measured from here |
| 5 | speculative probes: `TRY`, B1, B2, B12, pass B of `CARGS`, `UNDEC` | no WARM-ANOMALY and no MACHINE-INCOMPLETE event on Init, Std and the Cedar-level corpora |
| 6 | parallel workers (the driver's scheduler; engine-wide tables under contention) | wall clock and CPU overhead of 2 / 4 / 6 workers against 1 |
| later, each behind its own gate | K2 (3.6); `met-past` and Nat-fold-waits orderings (R04 §9, R05 D2); regions; packed strategies (kinds 13-15; no corpus argues for one, `R13 §5`); the EPH/UNQ experiment; T2 if 0.5 says go | |

Testing:

- **Per-instruction unit tests**: table-driven, one row = (machine state, instruction) -> (expected
  state or failure). The official rule cited in section 4 gives the cases; the arena tests named in
  R02 §10 give one regression row each (for example `bugs/level-imax-normalization` for `LVLEQ`,
  `bugs/k-rec-conv` for `IOTA`, `bugs/proj-of-stuck-prop` for `ISPROP`, `bugs/unit-rec-rule` for `UNITL`).
  Rows added by this revision: two constants sharing a body under swapped level parameters (2.7);
  `Nat.ble (Nat.succ (f 2)) 7` and the same with `Nat.mod` (4.3.4); `x.1` on `P.mk (n : N) (p : True) : P n`
  and a field index beyond `nfields` (4.5.5); a pair key with one immediate (1.5); two NBIG literals of
  equal content and different ids (2.9); `perf/app-lam` through the tabled `CHECK` (4.5.2); a HOLE read
  inside a `TRY` must end in DECLINE (1.4); a probe that ends in `U` must leave `FAILED` unset (5.4).
- **Tier equivalence**: for every constant of `init` and random argument values, K0 and K1 (and K2, T2
  when enabled) must return the same value id for `DELTA`, `WHNF`, `VTYPE` and the same answer for `CONV`
  (decision 6). This is cheap because ids are canonical, and it catches an elaborator bug as a mismatch,
  not as a wrong verdict.
- **Convention equivalence**: the frame trace (1.3) of a declaration is identical with frames always
  materialised and with the chosen host convention.
- **Differential testing against the reference kernel**: per declaration verdict on the 230 arena tests,
  `init` and `std`; per judgment on sampled sub-problems, comparing the reference kernel's `whnf` and
  `infer` on the term with the machine's value by reading the value back (a test-only `QUOTE`, memoised by
  value and depth, never on a checking path).
- **Against `leanchecker --from-export`**: whole-file verdicts, including the 92 bad tests.
- **Invariant modes** (5.5): `memocheck`, `shortcheck`, `poison`, `scopeaudit`, `maskaudit`, `sharecheck`,
  `pubcheck`; a mutation tool that edits exports and compares the machine, the reference kernel and
  `leanchecker`.
- **Cost regression**, CPU time first: per stage and per corpus the table CPU / wall / RSS / instructions;
  `call` events per declaration (the frame trace count) with a +2% alert; the probe cost as a number (ns
  and instructions per `mkApp` hit; target at most 60 instructions for the inlined probe, 45 measured in
  the spike); the instruction estimates of the section 7 traces as a sanity check of the model, not as a
  target.

## 12. Interfaces and open questions

Required from `ARCHITECTURE.md` and the modules:

| # | Requirement | Status |
|---|---|---|
| R1 | Per term node: `kind, a, b, c`, `looseRange` (saturation sticky, flag `LOOSE_SAT`), `fvMask` (a deterministic superset of the free indices in bits 0..62, bit 63 = some index >= 63; never under-approximate), `HAS_LPARAM`; the head of an application spine; `mdata` dropped. A one-dispatch node accessor (region array + word offset) for the generic judgments (C2.12). A store accessor that writes the `c` word of an export LAM/PI node (the block hint of 3.3) | provided except the two accessors: **requested** |
| R1' | Header bits 13 SHARED, 14 BODY, 15 INVARIANT set by the loader (saturating 2-bit referrer counter per segment record) and the block decoder (SEEN bit); promoted nodes SHARED; the store records each unit's first export id | assigned to the store and loader owners (RECONCILIATION §3); until delivered the machine tables every non-leaf node |
| R2 | `rlvm.api.LevelOps`: `mkSubst(paramNames, levels)` (0 = identity, -1 = arity error), `instantiate(level, subst)`, `instantiateList(list, subst)`, `isEquiv`, `isAlwaysZero` (the official `normalizes_to_zero`, written `normalizesToZero` in section 4), `isNeverZero`, `succ`, `mkIMax`; structural access to a level (kind, operands, name) for the postfix encoding of 3.3; base / overlay distinction of an id; no unbounded recursion (limits as a `RuntimeException`, never a `StackOverflowError`) | provided: `rlvm-api` has published `mkSubst`, `substParams` / `substLevels`, `instantiate(level, subst)`, `instantiateList(list, subst)` and the structural accessors (`kind`, `succOf`, `lhs`, `rhs`, `paramName`) over `rlvm.level.LevelStore` |
| R3 | `rlvm-nat` used directly (`rlvm.nat.Nat` on `long` handles, a per-worker `NatStore` overlay that interns by content, `Nat.transfer` for imports); the 15 operations with the guards of R02 §7.6 returned as a status; code points of a string. `LitPool`: export literals only, naturals and strings interned by content | bridge owned by the store tables with the `rlvm-nat` author (RECONCILIATION §2); until ids are canonical the machine compares content (2.9) |
| R4 | Proof blocks decoded into the worker's overlay; overlay term ids are not reused within a session; a theorem's block stays decoded (or is re-decodable) for `DELTA`. `Overlay.blockRoot` may reallocate the overlay array: the machine reloads its cached term arrays after every call that can grow the overlay | provided (§2.6); the reload is the machine's duty |
| R5 | Declaration rows with *recomputed* inductive data (`DeclTable`), `isNonRecStructure`, and `Specials` with guard states; a literal's `depMax` is the largest rank of its prerequisite names (C3.9) | provided (§1.3, §4.8); `Specials` split per RECONCILIATION §2 |
| R6 | The machine owns its engine-wide tables (per-row code and fact slots, block directory): chunk directories installed by compare-and-set, records published by one release store, read with acquire (3.3). Nothing of the machine is stored in `DeclTable`. Memory: 0.4 GB cap | supersedes the first draft's "slot where the machine stores `slvl` when `DECL` accepts" |
| R7 | Sessions: forward-only ranks, reset on any non-ACCEPT outcome and when `footprintBytes()` (bytes in use, overlay included once) exceeds `sessionBytes` (default 16 MiB); warm sessions get `Budget.maxSteps = UNLIMITED`; `Budget.maxDepth` is unused. ARCHITECTURE 2.9 budgets about 20 MiB of engine arenas per worker (1.6) | `Budget.warm()` has no step limit and `Budget.strict(steps, bytes)` exists (published); the 32 MiB default in ARCHITECTURE is superseded by RECONCILIATION §3 |
| R8 | `SharedFacts` as `PCHK`; `RunSignals.firstFailure()` and `memoryExhausted()` polled every 4096 ticks; `Reason.CANCELLED` (a decline reason) | provided (`Reason.CANCELLED`, `rlvm.api.MachineErrors`) |
| R9 | Reference-kernel confirmation of every `REJECT` (including `NAT_LITERAL_TOO_BIG`); cold strict re-run with a second engine created by `EngineConfig.withStrict(true)`; retry of a memory decline in a fresh session | provided (ARCHITECTURE §8.2) |
| R10 | `KernelOps` of the machine is optional and absent in release configurations (section 14); admission uses the reference kernel | matches ARCHITECTURE §11 |
| R11 | The driver never catches `Error`; the facade converts `RuntimeException`s of `rlvm-level` / `rlvm-nat` limits into DECLINE (C1.14) | one rule for both documents |

Open questions (each needs a measurement, none blocks v0):

1. Strict versus suspended active arguments (decision 8): the elaborator has the switch; measure on Std
   and `cedar-hot` (sokonanoda deleted its thunks, gruszka kept them).
2. `ELAB_AT` (start 10; the measured break-even points to 16-32), and a prepared hot set: 82-91% of
   cedar's and con-leche's body executions fall on the 300-1000 bodies hottest on Init and 94-96% of their
   rule executions on Init's 100 hottest rules [R13 §7], so those roles could be elaborated when their
   declarations are admitted, without counters.
3. Whether SHARED / BODY / INVARIANT gating loses hits that pruned keys would give on other unshared
   nodes (nanoclo's 543-telescope case).
4. `FVAR(type, k)` against `(depth, type)` naming: hit rates of `PAIR` and SUSP across declarations.
5. The 32-byte record against a 24-byte record with out-of-line memo words for kinds that rarely use them
   (CPU time on Std at a 16 MiB cap decides).
6. Dense against sparse frames (2.4), and whether a small cache in front of `PRUNE` pays now that
   environments are not interned.
7. The session cap (8 / 16 / 32 / 64 MiB), also with code objects present.
8. Whether `RULES` may also fire in lazy delta when the other side is already a weak head normal form.
9. Whether session warm-up costs more than 2% (C2.9 e); only then a stamped process-wide table of
   closed-term facts beyond `PCHK`.
10. Restricting `ls` to the parameters a term mentions (2.7).

## 13. Module and work packages

### 13.1 The module

`rlvm-machine`, package `rlvm.machine` (RECONCILIATION B3). A later `rlvm-truffle` is out of scope.

> **Orchestrator note (2026-11-24 07:20 UTC).** The host control convention is decided: convention H with
> host depth bound 64, as specified in `docs/design/review/stepper-convention.md` (rules H1-H12 and the code
> template there are normative for section 8.2). The module skeleton and the machine work packages are being
> created and implemented by the workflow "rlvm-wave4-machine". An agent that was asked to "create the machine
> skeleton" by any other workflow must NOT create or modify anything under `rlvm-machine/`: read
> `rlvm-machine/OWNERSHIP.md`, verify, and report only.

- Compile dependencies: `rlvm-store`, `rlvm-api`, `rlvm-level`, `rlvm-nat`. No Truffle artifact, no
  reflection, no `java.util` collection, lambda, stream or boxed value in any class but the facade's
  error reporting and the tests.
- Test-scope dependencies: `rlvm-load`, `rlvm-export`, `rlvm-ref`, `rlvm-inductive`, `rlvm-admit` (to load
  an export, run the environment pass and compare with the reference engine inside `Fixture`), and
  `rlvm-testkit` (`DiffEngine`, the mutant corpus, the official-verdict cache).
- Registration: `META-INF/services/rlvm.api.EngineProvider` names `rlvm.machine.MachineProvider`
  (`name() = "machine"`, `isReference() = false`).
- A skeleton agent creates the module before the machine wave: the files of 13.2 with the constants of
  13.3 filled in, every method of 13.4 throwing `UnsupportedOperationException` with its work package in
  the message, `Fixture` working against hand-built stores, and the install into the local repository.

### 13.2 Files and responsibilities

| File (`rlvm.machine`) | Responsibility | WP |
|---|---|---|
| `Layout` | constants only: ref tags, kinds, record and flag layout, memo-slot indices, run header, pair entry, fact record, result codes. (Error codes are `rlvm.api.MachineErrors`, not a file of this module) | skeleton |
| `Op` | constants only: op numbers, family table, states per op, frame payload offsets, instruction encoding, operand modes, roles | skeleton |
| `Machine` | the per-session state: every array and register of section 1, growth, `reset()` in O(used), `footprintBytes()`; the driver loop; no judgment logic | skeleton, M6 |
| `Ops` | the operation library: thin static entry points that delegate to the classes below (13.4) | skeleton |
| `Values`, `Runs`, `Lits`, `Levels` | records and interning, `CHEAD`, sort cache, flags; run slab, `SPINE` / `FRAME` / `PRUNE`, environment slab; literal bridge and content comparison; `LSUB`, `LVLEQ`, substitution ids, `LIMP` | M1 |
| `Frames` | pushing and popping frames, `TRY` / cut / `exhaust`, `OPEN` / `CLOSE` with the `FV0` chain, polling; the status protocol of the chosen host convention | M1 (scope), M6 (budgets) |
| `Eval` | family EV: `EVAL`, `FORCE`, `APPLY`, `INST`, `ARGV` | M2 |
| `Reduce`, `NatRed`, `StrRed`, `Rules` | family RD: `DELTA`, `CORE`, `WHNF`, `IOTA`, `QUOTR`; `NATOP`, `NATFOLD`, `OFFSET`; `STRVIEW`; case-tree execution | M3 |
| `Conv`, `Cargs`, `Pairs` | family CV: `CONV`, `CBIND`, `CCLO`, `LAZYD`, `PROJD`, `PIRR`, `ETA`, `ETAS`, `UNITL`, `STRX`; `CARGS` and plan execution; the pair table and `UNDEC` | M4 |
| `Infer`, `Decl`, `Facts`, `FactStore` | family TY: `INFER`, `CHECK`, `TWALK`, `VTYPE`, `IPROJ`, `ENSORT`, `ENPI`, `ISPROP`; the `DECL` driver; fact derivation (`SIGD`, shapes) and the engine-wide fact table | M5 |
| `MachineProvider`, `MachineEngine`, `MachineSession`, `MachineOps`, `Debug` | the facade of section 14; budgets; strict mode; the debug modes of 5.5 | M6 |
| `Elab`, `Code`, `CodeStore`, `Match`, `Cong` | K1 elaborator, code stepper (family CODE), block directory and publication, `MATCH`, comparison-plan builder | M7 |
| test tree: `Fixture`, `Quote`, `TierEq`, `Diff` | fixture builder, readback, tier and convention equivalence, differential runs | skeleton, M8 |

One owner per file. `Machine`, `Layout`, `Op` and the signatures in `Ops` change only through the
owner of M6, who also owns the module build.

### 13.3 Constants defined once

```
Layout   refs:    IMM = 1 << 31;  NONE = 0;  HOLE = 1;  first record index 2;  record index mask 0x0FFFFFFF
         record:  W0 = 0, A = 1, B = 2, C = 3, M0 = 4 .. M3 = 7;  kind = w0 >>> 28;  flags at bit 16;  aux = w0 & 0xFFFF
         kinds:   SORT 1, NBIG 2, STR 3, LAM 4, PI 5, PII 6, SUSP 7, FVAR 8, CONST 9, NEU 10, PROJ 11, NSUC 12
                  (0: K2 slot, unused; 13-15 reserved)
         flags:   OPEN 0, WHNF 1, ACTIVE 2, COREFULL 3, PROOF 4-5, PROPT 6-7, INSCOPE / LET 8, HCLASS 9-11
         hclass:  FVAR 0, AXIOM 1, IND 2, CTOR 3, REC 4, QUOT 5, DEFN 6, PROJ 7
         memo:    CONST/NEU/PROJ: M_WHNF 0, M_CORE 1, M_TYPE 2, M_UNF 3      LAM: M_DOM 1, M_TYPE 2, M_CODE 3
                  PI/PII: M_COD 0, M_DOM 1, M_SLVL 2, M_CODE 3              SUSP: M_VAL 0, M_TYPE 2, M_TSTAMP 3
                  STR: M_CTORF 0     NSUC: M_WHNF 0     FVAR: M_NEXT 0, M_OPENCNT 1, M_TOPV 2, M_FV0 3
         results: F 0, T 1, U 2, UNDEF 3;  MISS = 0x7FFFFFFC (= Op.MISS; not -1, which is the immediate
                  2^31 - 1);  NOCODE = -1;  TSTAMP PERM = 0x3FFFFFFF (not 0x7FFFFFFF, which is the signal SUSP)
         runs:    header H = 2 (+1 far);  kinds FRAME 0, SPINE 1, REST 2;  len = hdr >>> 8;  kind = (hdr >>> 1) & 3;
                  far = hdr & 1
         pair:    4 ints per entry; status POS 1, NEG 2, FAILED 4
         facts:   word offsets f0..f10 of 3.5;  F_NONE = 1;  level ref tags BASE = 1 << 30, POOL = 2 << 30
                  shapes: NONE 0, PICK 1, PROJARG 2, CTORBODY 3, CASESON 4, MATCHER 5
         CONG:    class (3 bits) SAME 0, TYPE 1, PROOF 2, PROOFL 3;  flag UNREAD = 8;  probe rank in bits 4..9
         levels in records: ZERO 0, SUCC 1, MAX 2, IMAX 3, PARAM 4 (+ name id)
Op       signals (convention H5): SUSP 0x7FFFFFFF, ABORT 0x7FFFFFFE, FATAL 0x7FFFFFFD, SIG = FATAL; a result r is a
              signal iff r >= SIG;  MISS 0x7FFFFFFC;  HOST_DEPTH 128 (64 until 2026-11-24, see section 15)
         frame header: key = hdr >>> 20 = op << 4 | st;  n = hdr & 0xFFFF;  at most 16 states per op
         additional frame ops (13.7): ARGV 0x14, DOM 0x15, OFFSET 0x27, STRX 0x3B, ENSORT 0x46, ENPI 0x47, ISPROP 0x48,
              INFER2 0x49, CHECK2 0x4A, VTYPE2 0x4B, IPROJ2 0x4C, MATCH 0x51;  family EXT 0x70..0x7F (tests)
         EV   EVAL 0x10, FORCE 0x11, APPLY 0x12, INST 0x13
         RD   DELTA 0x20, CORE 0x21, WHNF 0x22, IOTA 0x23, QUOTR 0x24, RULES 0x25, NATF 0x26
         CV   CONV 0x30, CONV2 0x31 (states c8..rec), CARGS 0x32, CCLO 0x33, CBIND 0x34, LAZYD 0x35,
              PROJD 0x36, PIRR 0x37, ETA 0x38, ETAS 0x39, UNITL 0x3A
         TY   INFER 0x40, CHECK 0x41, TWALK 0x42, VTYPE 0x43, IPROJ 0x44, SIGD 0x45
         CODE ACT 0x50        CTL  TRY 0x60, ERF 0x61, DECL 0x62
         code-only instructions (never frames):
              MOV 0x80, JMP 0x81, BEQ 0x82, BNE 0x83, BKIND 0x84, BUNREAD 0x85, SWITCH 0x86, FIELDS 0x87,
              CALL 0x88, TAILCALL 0x89, RET 0x8A, OPEN 0x8B, CLOSE 0x8C, TRYI 0x8D, REQUIRE 0x8E, FAIL 0x8F,
              FAULT 0x90, TICK 0x91, MEMOGET 0x92, MEMOPUT 0x93, PAIRGET 0x94, PAIRPUT 0x95,
              KSORT 0xA0, KCONST 0xA1, KLIT 0xA2, KVAL 0xA3, MKAPP 0xA4, MKLAM 0xA5, MKPI 0xA6, MKSUSP 0xA7,
              MKPROJ 0xA8, NSUCC 0xA9, PEEK 0xAA, TAILAPPLY 0xB0, NATOP 0xB1, NATFOLD 0xB2, STRVIEW 0xB3,
              LVLEQ 0xB4, MATCH 0xB5, OFFSET 0xB6, STRX 0xB7, ENSORT 0xB8, ENPI 0xB9, ISPROP 0xBA,
              TARG 0xBB, TRET 0xBC, CHKCONST 0xBD, CHKLVL 0xBE
         a judgment used as a code instruction has the op number of its frame
         payload offsets: only for the frames of the shared control code: ACT_BLK 0, ACT_PC 1, ACT_DST 2,
              ACT_ARGS 3, ACT_CAPS 4, ACT_LS 5, ACT_RBASE 6;  TRY_FUEL_HI 0 .. TRY_ER 8;  every other frame is the
              parameter list of its continuation step (1.3, last paragraph)
         instruction word: op = w >>> 24, d = (w >>> 16) & 255, n = (w >>> 8) & 255, x = w & 255
         operand: mode = o >>> 28 (R 0, A 1, C 2, K 3, I 4, V 5, T 6, L 7, LL 8, D 9, B 10, M 11); payload = o & 0x0FFFFFFF
         roles: TELE 1, BODY 2, RULE 3, FIELDS 4, CONG 5, RULES 6
         modes: ONLY 0, CHECK 1 (constant `Op.CHECKED`);  CORE: FULL 0, CHEAP 1;  policies: APP 0, SAMEDEF 1
         budgets: B_SAMEHEAD 16384, B_SAMEHEAD_ABBREV 1024, B_PAIR 256, B_CCLO 1024, B_SIGD 65536,
              MATCH_NODES 64, POLL_MASK 4095, ELAB_AT 10
         limits: W_MAX 1 << 28, SPINE_MAX 65535, BINDERS_MAX 65535, NREG_MAX 255;  FUEL_UNLIMITED 1 << 62
         staging switches SW_* (all off = v0) and debug modes DBG_* (5.5): one bit each, see `Op.java`
errors   rlvm.api.MachineErrors (published in rlvm-api; not redefined in the machine):
         kernel errors (REJECT at tryp == 0, U in a TRY):
              E_TYPE_EXPECTED 1, E_FUN_EXPECTED 2, E_APP_MISMATCH 3, E_LET_MISMATCH 4, E_TYPE_MISMATCH 5, E_PROJ 6,
              E_UNKNOWN_CONST 7, E_LEVEL_ARITY 8, E_LEVEL_PARAM 9, E_NATSIZE 10, E_NOT_PROP 11, E_LOOSE_BVAR 12
         limits (DECLINE):   E_STEPS 16, E_MEM 17, E_STACK 18, E_LIMIT 19, E_PRELUDE 20
              recoverable inside a TRY (isRecoverableInTry): the kernel errors, E_STEPS, E_LIMIT
         fatal (DECLINE and session reset, also inside a TRY):
              E_HOLE 24, E_VIS 25, E_ILLTYPED 26, E_INTERNAL 27, E_AUDIT 28, E_CANCEL 29, and E_MEM, E_STACK, E_PRELUDE
```

### 13.4 The operation library

`Ops` is the one entry point for generic judgments, the code stepper and tests. All methods are static,
take the `Machine` first and work on `int` refs; `xb`/`n` denote `n` refs in scratch `X` at `xb`; an
environment is `(eb, en)`. A `..Fast` method answers inline or returns `MISS`; the caller then performs
the `call` of 1.3 through `Frames`.

```java
// values (M1)
static int mkSort(Machine m, int level);                 static int mkConst(Machine m, int row, int levelList);
static int mkNat(Machine m, long natHandle);             static int mkStr(Machine m, int litIndex);
static int mkLam(Machine m, int term, int frame, int ls);   // mkPi, mkPii likewise
static int mkSusp(Machine m, int term, int frame, int ls, boolean let);
static int mkFVar(Machine m, int type, int k);           static int mkNeu(Machine m, int head, int xb, int n);
static int mkApp(Machine m, int f, int xb, int n);       // MKAPP: folded application, Nat.succ rule
static int mkProj(Machine m, int structRow, int field, int s);
static int nsucc(Machine m, int v);                      static int peek(Machine m, int v);
static int kind(Machine m, int v);                       static int flags(Machine m, int v);
static int memo(Machine m, int v, int slot);             static void setMemo(Machine m, int v, int slot, int x);
static boolean sameLiteral(Machine m, int a, int b);     // content comparison of 2.9
// runs and environments (M1)
static int spine(Machine m, int xb, int n);              static int frame(Machine m, int xb, int n, int rest);
static int prune(Machine m, int eb, int en, long mask);  static int arg(Machine m, int run, int j);
static int get(Machine m, int eb, int en, int i);        static int push(Machine m, int eb, int en, int v);  // new en; m.envBase holds the base
static int enter(Machine m, int frame);                  // copies the frame to es; returns en; base in m.envBase
// levels (M1)
static int substOf(Machine m, int constRef);             static int lsub(Machine m, int level, int ls);
static int lsubList(Machine m, int list, int ls);        static boolean lvlEq(Machine m, int a, int b);
static boolean listsEq(Machine m, int a, int b);         static int importLevel(Machine m, int record, int slot);
// scope and budgets (M1, M6)
static int open(Machine m, int type);                    static void close(Machine m, int n);
static void tick(Machine m, int n);                      static void fail(Machine m, int err);
static void fault(Machine m, int err);
// judgments: inline part (M2-M5)
static int forceFast(Machine m, int v);                  static int whnfFast(Machine m, int v);
static int coreFast(Machine m, int v, int mode);         static int deltaFast(Machine m, int v);
static int vtypeFast(Machine m, int v);                  static int convFast(Machine m, int a, int b);   // T, F or MISS
static int ispropFast(Machine m, int type);              static int instFast(Machine m, int pi, int x);
static long natop(Machine m, int op, long a, long b);    static int offsetFast(Machine m, int a, int b);
// facts (M5) and plans (M7)
static int facts(Machine m, int row);                    // record index, 0 = none; triggers SIGD through Frames
static long propMask(Machine m, int constRef);           static long unreadMask(Machine m, int row, int spineLen);
static int staticProof(Machine m, int neu);              static int codeOf(Machine m, int row, int role);
// code operands (M7)
static int opnd(Machine m, int operand, int fp);
```

`Frames` offers one `push<Op>(Machine, operands...)` per frame class of 1.3, `ret(Machine, int)`,
`tryBegin(Machine, long budget)`, and the status constants of the chosen host convention. (Superseded by
the decision of 8.2, see 13.7: frames are written by `Machine.sigN` / `spillN` only; a judgment is called
through its entry step `J(m, operands.., d)`; the `..Fast` methods above remain as pure inline queries
that return `Op.MISS` when they have no answer; `open`, `tick`, `fail`, `fault` return an `int` signal.)

### 13.5 Test fixture builder

`Fixture` (test tree) gives every work package a machine without the rest of the pipeline:

```java
Fixture f = Fixture.empty();                       // an empty Store through StoreBuilder, one WorkerContext, one Machine
int nat = f.inductive("Nat", "Nat.zero : Nat", "Nat.succ : Nat -> Nat");   // rows, constructors, recursor via rlvm-inductive
int add = f.def("Nat.add", "Nat -> Nat -> Nat", "fun n m => ..", /*hint*/ 1);
int t   = f.term("Nat.add (Nat.succ n) 2", f.fvar("n", "Nat"));            // a tiny term parser over de Bruijn terms
int v   = f.eval(t);     f.whnf(v);     f.conv(v, w);     f.inferCheck(t);  // run one judgment to completion
f.expectFail(MachineErrors.E_APP_MISMATCH, () -> f.inferCheck(bad));
Fixture g = Fixture.load("work/arena-site/tests/good/tutorial/001.ndjson"); // loader + environment pass
g.checkAll();   g.diffAgainstReference();   g.frameTrace(cid);   g.quote(v);
```

It also exposes the arrays for table-driven tests (`f.record(v)`, `f.run(r)`, `f.pair(a, b)`), a switch for
each debug mode, and `f.strict()`.

### 13.6 Work packages

Order: the skeleton and the convention decision first; M1; then M2-M5 in parallel against the frozen
constants (each with fakes of the others' `..Fast` methods until they land); M6 alongside; M7 after the
v1 gate of step 1; M8 from the start.

| WP | Owns | Needs | Acceptance tests | Stage |
|---|---|---|---|---|
| M1 values and runs with interning | `Values`, `Runs`, `Lits`, `Levels`, scope part of `Frames` | skeleton; `LevelOps.mkSubst` (R2) | I-ID property tests on random records (two constructions, one id); intern and reset in O(used) (a session of one record resets without touching a full table); far-frame identity with the REST run; pair key with an immediate; literal content comparison; substitution regression of 2.7; `OPEN` / `CLOSE` reproduce ids; probe cost reported in ns and instructions | v0 |
| M2 eval, apply, force, inst | `Eval` | M1 | per-instruction rows of 4.2 and 4.3.1; closure identity independent of the path to the environment; HOLE for unread binders; LET suspensions kept; inert nests in one frame (frame trace); 913,500-element list literal without host recursion | v0 |
| M3 reduction: delta, whnf, iota, quot, Nat, String | `Reduce`, `NatRed`, `StrRed`, `Rules` | M1, M2 | rows of 4.3 against R02 §7; `bugs/k-rec-conv`, `nat-rec-k-lie`, `nat-shift-huge`, `str-lit-utf8*`, `other/nat-lit-*`; the `Nat.succ` rows of section 11; case trees equal `DELTA` + `IOTA` on random arguments (step 4) | v0; case trees: step 4 |
| M4 conversion | `Conv`, `Cargs`, `Pairs` | M1-M3; `Facts` accessors (M5) from step 2 | the state order c0-c13 against R02 §8 (one row per state and per `LAZYD` branch); `bugs/unit-rec-rule`, `eta-ctor-alias`, `rec-missing-ih`, `perf/irrelevance-refutes`, `proj-lazy-struct`; `FAILED` / `UNDEC` discipline; agreement rule on a crafted unread-then-proof telescope; `shortcheck` clean on Init | v0 complete procedure; plans: step 2; probes: step 5 |
| M5 inference, checking, the `DECL` driver, facts | `Infer`, `Decl`, `Facts`, `FactStore` | M1-M4 | every `throw` of R02 §6 as a row; tutorial projection tests, `projIndexData2`, index beyond `nfields`; tabled `CHECK` on `perf/app-lam`; `theorem_type_is_not_prop`; facts: one record per constant, published once, `SIG` for every constructor and recursor of Init, manifest mutation tests, concurrent derivation by 6 threads gives identical records | v0; facts: step 2 |
| M6 sessions, facade, budgets, debug modes | `Machine`, `MachineProvider`, `MachineEngine`, `MachineSession`, `MachineOps`, `Debug`, budget part of `Frames`; module build | M1; M5 for end-to-end | `Session.check` on the 141 tutorial tests through `rlvm-main` with `-X engine=machine`; Reason table of section 14; strict mode equals rule 3 of 6.3; cancellation within 4096 ticks; fatal fault inside a `TRY` declines; footprint equals bytes in use and is not capacity; reset after every outcome other than ACCEPT; `memocheck`, `poison`, `scopeaudit` runs of Init | v0, step 1, step 6 |
| M7 K1 elaborator and code stepper | `Elab`, `Code`, `CodeStore`, `Match`, `Cong` | step 1 gate; M5 facts | listings of 3.7 reproduced; `pubcheck` on every block of Init and Std; theorem block values never elaborated; `MATCH` = build + `CONV` on sampled sites; two workers elaborating one role publish equal blocks; rent-to-own counter | steps 2 (plans), 3, 4 |
| M8 quote, tier and convention equivalence, differential tests | `Fixture`, `Quote`, `TierEq`, `Diff` | skeleton; grows with M1-M7 | readback of `whnf` and `infer` agrees with the reference kernel up to its `isDefEq` on sampled sub-terms of Init; K0 = K1 value ids for every constant of Init with random arguments; frame trace identical with frames always materialised; per-stage table CPU / wall / RSS / instructions produced by one command | all |

Gates (RECONCILIATION B3): **v0** = diff-clean against the reference engine on the tutorial and `bugs/`
tests; **v1** = diff-clean on Init and Std. The machine targets of section 11 apply after steps 1 and 4.

### 13.7 As built by the skeleton (2026-11-24)

The module exists (`rlvm-machine/`; ownership in `OWNERSHIP.md` and `OWNERS.tsv`, packages and rules in
`WORK-PACKAGES.md`, deviations in `NOTES-M6.md`). Where it differs from 13.2-13.6:

- **Files.** Added: `Hash` (the hash functions of `vtab`, `rtab`, `pair` and the cache cells: part of the
  frozen table format, because `Machine` grows and resets the tables in O(used) and the interning code
  must probe with the same functions), `Scope` (`OPEN` / `CLOSE`: the "scope part of `Frames`", owner M1),
  `Show` (diagnostics printer of terms, values, runs, frames; owner M6). `Frames` holds the control family
  only (`k_try`, `k_erf`). In the test tree: `Sexp`, `Prelude`, `Toy`, `TestTables` beside `Fixture`.
- **Owners.** One owner per file, enforced by `scripts/machine-sandbox.sh` (private copies per package,
  publication of owned files under a lock, main tree compiled and fast-tested at every publication):
  M1 `Values`, `Runs`, `Lits`, `Levels`, `Scope`; M2 `Eval`; M3 `Reduce`, `NatRed`, `StrRed`, `Rules`; M4
  `Conv`, `Cargs`, `Pairs`; M5 `Infer`, `Decl`, `Facts`, `FactStore`; M6 `Layout`, `Op`, `Hash`, `Machine`,
  `Frames`, `Ops`, the facade classes, `Debug`, `Show`, the build, and the shared test files (`Fixture`
  and its helpers); M7 `Elab`, `Code`, `CodeStore`, `Match`, `Cong`; M8 `Quote`, `TierEq`, `Diff`.
- **Interface between packages** = the entry steps of the judgments, `static int J(Machine m, operands..,
  int d)`, which return a result or a signal; each family class has `resume(m, key, w, p, r)` for the
  driver and an `Ext` class for its private session state. `Decl.check(m, cid)` returns a
  `MachineErrors` code (0 = ACCEPT).
- **Fixture** (13.5): terms are written in the S-expression language of the reference kernel's tests
  (`(Nat.add (Nat.succ n) (lit 2))`); `f.fvar(name, type)` opens a binder of the fixture's context;
  recursors of hand-built inductive types are generated by `rlvm-inductive` with the reference kernel;
  `Fixture.load(path)` runs the real loader and the environment pass; `diffAgainstReference()` compares
  per declaration.
- **Staging** is switched by `Machine.sw` (`Op.SW_*`, all off = v0; `-Drlvm.machine.switches=`), debug
  modes by `Machine.debug` (`Op.DBG_*`; `-Drlvm.machine.debug=`).

## 14. Facade

How the rest of RLVM uses the machine: `rlvm.api.Engine` / `Session`, nothing else.

### 14.1 Engine and session

- `MachineProvider` registers the factory under the name "machine". `MachineEngine` holds the `Store`, the
  `EngineConfig` and the engine-wide tables (`FactStore`, `CodeStore`), which are functions of the store
  alone and obey the publication rule (3.3); they are safe under concurrent sessions and no verdict
  depends on which session filled them. `environmentAdvanced` is ignored in v1.
- `MachineSession` owns one `Machine` and the `WorkerContext` it was given. It is confined to its thread.

```
check(cid, budget):
  given by rlvm-admit: cid is an axiom, definition, theorem or opaque of a unit below the environment
        frontier; the structural checks passed (closed roots, declared level parameters, every constant
        bound, visible and applied to the right number of levels; literal prerequisites declared)
  re-read the term arrays (the overlay may have been reallocated by blockRoot);  decl = rank(cid)
  fuel = budget.maxSteps  (unlimited in Budget.warm(); finite in Budget.strict(steps, bytes));  byte limit = budget.maxBytes
  push DECL [cid, 0];  run
  ACCEPT            -> Outcome.ACCEPT; nothing is allocated on this path
  REJECT(E)         -> lastReason = reason(E);  Outcome.REJECT      // the driver confirms it (ARCHITECTURE 8.2)
  DECLINE(E)        -> lastReason = reason(E);  Outcome.DECLINE;  a fatal E also resets the session at once
  a RuntimeException of rlvm-level or rlvm-nat (their declared limits) -> DECLINE(INTERNAL_ERROR), session reset
  an Error is never caught
```

`check` never throws for a property of the input. After any outcome other than ACCEPT the driver calls
`reset()` (1.6) before the session is used again; nothing derived for the failed unit survives.
`lastMessage()` renders the site (constant name, error code, the two types of a mismatch read back with
`Quote`) only when asked.

### 14.2 Strict mode

`EngineConfig.strict` is fixed at engine creation; the driver creates a **second** `MachineEngine` with
`withStrict(true)` for the cold re-check of a rejection. That engine shares nothing with the first but
the immutable store. In it:

- generic tier only: no code object is elaborated or executed, no fact record is derived or read (every
  `CARGS` position is SAME, `PROOF` comes only from the dynamic `ISPROP`), no case tree, no `MATCH`;
- the complete procedure, rule 3 of 6.3; of the shortcuts only the exact ones that need no fact: B6 (HOLE
  for a binder whose node mask shows it unread) and B10 (offset layers);
- no `TRY` segment, hence no `UNDEC`; `PAIR` and the value memos live for one declaration;
- a fresh session per declaration (`reset()` before and after); `PCHK` is neither read nor written;
- `Budget.maxSteps` applies.

A strict ACCEPT after a warm REJECT is a WARM-ANOMALY (a shortcut or cache produced a wrong rejection)
and fails test builds.

### 14.3 Reason mapping

`rlvm.api.MachineErrors.reason(code)` is the table; an unknown code maps to `INTERNAL_ERROR` (a decline,
never a rejection).

| Machine code | Outcome | `rlvm.api.Reason` |
|---|---|---|
| `E_TYPE_EXPECTED` | REJECT | `TYPE_EXPECTED` |
| `E_FUN_EXPECTED` | REJECT | `FUNCTION_EXPECTED` |
| `E_APP_MISMATCH` | REJECT | `APP_TYPE_MISMATCH` |
| `E_LET_MISMATCH` | REJECT | `LET_TYPE_MISMATCH` |
| `E_TYPE_MISMATCH` | REJECT | `DECL_TYPE_MISMATCH` (raised by `CHECK`, `TRET` and the root comparison of `DECL`) |
| `E_NOT_PROP` | REJECT | `THEOREM_NOT_PROP` |
| `E_PROJ` | REJECT | `INVALID_PROJECTION` |
| `E_UNKNOWN_CONST`, `E_LEVEL_ARITY`, `E_LEVEL_PARAM` | REJECT | `UNKNOWN_CONSTANT`, `LEVEL_ARITY_MISMATCH`, `UNDECLARED_LEVEL_PARAM` (only where the root summaries are absent, 5.3) |
| `E_NATSIZE` | REJECT | `NAT_LITERAL_TOO_BIG`: mirrors the official kernel; confirmed by the reference kernel like every rejection |
| `E_STEPS` | DECLINE | `BUDGET_STEPS` |
| `E_MEM` | DECLINE | `BUDGET_MEMORY` |
| `E_STACK` | DECLINE | `STACK_DEPTH` |
| `E_LIMIT` | DECLINE | `UNSUPPORTED_INPUT` |
| `E_PRELUDE` | DECLINE | `UNSUPPORTED_PRELUDE` |
| `E_HOLE`, `E_VIS`, `E_ILLTYPED`, `E_INTERNAL`, `E_AUDIT` | DECLINE | `INTERNAL_ERROR` |
| `E_CANCEL` | DECLINE | `CANCELLED` |

The reason of a confirmed rejection that the user sees is the reference kernel's; the machine's reason
is for logs and for the differential tests, which compare outcomes, not reasons.

### 14.4 Cancellation and memory

Every 4096 ticks (`ticks & 4095 == 0` in `TICK`, never per step) the machine reads
`RunSignals.firstFailure()` and `memoryExhausted()`. If a unit below the one being checked already has a
non-ACCEPT outcome, the machine raises `fault(E_CANCEL)`: fatal, so `W` is cut to `DECL` even inside
speculative segments, and `check` returns DECLINE with `Reason.CANCELLED`. The verdict is the status of
the first non-ACCEPT unit in file order, so a cancelled unit's outcome never changes it (RECONCILIATION
§2). `memoryExhausted()`, the unit's `Budget.maxBytes` and the array limits of section 9 give
`fault(E_MEM)`. Warm sessions have no step limit; a declaration that loops because an earlier unit is
ill-typed is ended by this poll.

### 14.5 Footprint and steps

- `footprintBytes()` returns the bytes in use of 1.6 (engine arenas plus the context's overlays, each
  counted once), in O(1). It is not capacity; the driver compares it with `sessionBytes` between units and
  must not add `WorkerContext.footprintBytes()` again.
- `steps()` returns `ticks`: monotone since the session was created, not cleared by `reset()`. It is the
  unit of `Budget.maxSteps` and of every budget in 6.2.
- `dumpStats` writes, when `EngineConfig.stats` is set: `call` events by op, memo and pair-table hit
  rates, records and runs made, resets and bytes at reset, elaborations by role, facts derived and
  `F_NONE` records, probe outcomes (`T` / `F` / `U`) by shortcut. Nothing is counted per step otherwise.

### 14.6 `KernelOps`

`Session.ops()` returns **null** in release configurations: admission of inductive types and quotients
uses the reference kernel (ARCHITECTURE §11), and the machine has no syntactic `whnf`. For differential
tests the test tree provides `MachineOps`:

| `KernelOps` method | Served by | Remark |
|---|---|---|
| `begin(rank, levelParams)` | sets `decl` and the generation rule of 5.3; closes all locals | |
| `isDefEq(a, b)` | `EVAL` of both terms, `CONV` | the exact yes/no |
| `isProp(type)` | `EVAL`, `ISPROP` | throws as the official `is_prop` |
| `check(term)`, `infer(term)` | `INFER` in CHECK / ONLY mode, then `Quote` into the overlay | equal to the official result up to definitional equality |
| `whnf`, `whnfCore`, `ensureSort`, `ensurePi` | `EVAL`, `WHNF` / `CORE`, `Quote` | not the official term: `whnfIsSyntactic()` is false |
| `mkLocal(type)` | an overlay FVAR node mapped to `OPEN(EVAL(type))`; open until the next `begin` | |
| `exposeUnitMembers` | `UnsupportedOperationException` | the machine does not run inductive admission |
| `terms()`, `termOps()`, `levels()`, `steps()` | the context's; `ticks` | |

A kernel error surfaces as `KernelException` with the reason of 14.3, a decline as `ResourceException`.

## 15. Change log (revision 2)

| Change | Where | Answers |
|---|---|---|
| CPU time is the metric; Cedar-level corpora the yardstick, with the findings of `R13`; cost notation marked as instruction estimates; every table CPU first | preamble, 0.1, 0.3, 4.0, 7, 11 | owner notes 9, 10; RECONCILIATION §0 |
| Cost table restated with the spike's numbers (probe, elaboration, match mode, worklist, compiled roots); the 8-10% worklist estimate and the 80-instruction elaboration estimate withdrawn | 0.3, 0.4, 3.1, 3.4 | C2.1, C2.2, C2.8, C1.10 |
| Judgment-specialised roles primary: `TELE`, `CONG(c)` as a per-position plan, `RULES` (K1 case trees, K2 derivation); T2 an experiment with a go/no-go number and no claim on K0/K1 | 0.2, 0.5, 3.2, 3.6, 6.1, 8.1, 8.4, 8.5 | C1.10, C2.7, C2.11, C1.15 |
| `ls` is a substitution id `mkSubst(lparams(row), levels)`; word `c` of CONST; regression test | 0.2, 2.2, 2.7, 4.3.2, 5.2, 12 R2 | B1: C1.1, C3.1 |
| Publication rule; structural levels with the import cache `LIMP`; overlay-sourced roles never elaborated; `pubcheck` | 3.3, 3.4, 4.3.2, 5.5 | B2: C1.2, C3.2 |
| Section 13: module, files, constants, `Ops`, fixture, eight work packages, gates | 13 | B3: C1.3 |
| Transition system stated without reference to Java; two host conventions described; choice delegated to `spikes/stepper` in one paragraph; fused families, dense `op << 4 or st` dispatch, inert nests in one frame, 8,000-bytecode limit | 0.2, 1.3, 8.2 | B4: C2.1 |
| Facts: one immutable record per constant, lazily derived from the store by any worker (`SIGD`), manifest-sort condition, single release store, `F_NONE`; derivation independent of elaboration | 0.2, 1.1, 3.5, 4.5.6, 12 R6 | C1.4, C2.3, C3.3, C3.5, C1.13, C2.8 |
| Three failure classes; fatal faults and cancellation cut to `DECL` inside a `TRY`; `TRY` saves `xp` and `ep` | 1.4, 6.3, 9 | C3.4, C3.14 (4) |
| `FAILED` only for comparisons that completed with `F`; `U` goes to `UNDEC`; `NEG` not read under `ER` | 1.5, 4.4.1, 4.4.2, 5.4, 6.3 | C1.9, C3.14 (2, 3) |
| Session layout: run header inline, non-interned environment slab, tag bits in the intern word, one pair table with status bits and an insertion log, one direct-mapped `IC`; footprint = bytes in use; 16 MiB default with a sweep; reset O(used); per-worker capacity stated | 1.1, 1.5, 1.6, 2.4, 2.5, 12 R7 | C1.6, C2.6 |
| No step limit in warm sessions; budgets in ticks | 1.4, 6.2, 9, 14 | C1.12, C2.5; owner note 10 |
| Elaboration rent-to-own at about the 10th use | 3.1, 12 | C2.8 |
| `CASESON` / `MATCHER` case trees in K1; closure congruence B12 under a `TRY`; structure eta applies the constructor's prop mask after the type comparison; `COD` memo for binders whose body does not read its variable | 2.6, 3.6, 4.3.4, 4.4, 4.4.1, 6.3, 7b | C2.9 (a-d); (e) is open question 9 |
| Sharing flags SHARED / BODY / INVARIANT from the loader and decoder; every non-leaf node tabled until then; `sharecheck` | 3.1, 5.5, 12 R1' | C1.5, C2.10 |
| `CHECK` is tabled; the expected type is never stored | 4.5.2, 4.5.4, 5.1 | C3.10 |
| `Nat.succ`: `NSUCC` of `Nat.zero` is the literal 1; `WHNF` of an NSUC folds when the base reduces to a literal; WHNF flag only on a normal base | 2.1, 2.3, 2.9, 4.2, 4.3.4 | C3.7 |
| Literal identity: two-word NBIG below 2^63, `NatStore` id above, `LitPool` for export literals, content comparison before `F` | 2.2, 2.9, 4.4.1, 12 R3 | C1.7, C3.8 |
| Projections: `readsField` includes the constructor's result type and is exact; index beyond `nfields` fails | 3.2, 4.5.5 | C3.6 |
| Far frames: the REST run is interned and part of the key | 2.4, 2.5 | C3.12 |
| Facade section: strict mode, Reason table (the codes of `rlvm.api.MachineErrors`), `CANCELLED`, footprint, steps, `KernelOps`; `E_NATSIZE` is a REJECT | 1.4, 9, 13.3, 14 | C1.8, C3.14 (1) |
| K2 and T2 preconditions (slot kind, export-only templates, `UNWIND`) | 3.6, 8.4 | C3.11 |
| `OPEN` without a probe sequence; sparse-frame contingency; one-dispatch node accessor requested | 2.4, 2.8, 12 R1 | C2.12 |
| Mask contract "superset, deterministic"; `CODE` as a hint through the node's `c` word; `LAZYD` writes into the caller's frame; overlay arrays reloaded after `blockRoot`; one rule for throwables; work packages named M1-M8 | 2.4, 2.6, 3.3, 1.3, 12 R4, R11, 13 | C1.14 |
| Pair keys ordered unsigned; unbound constant nodes decline; `GET` bounds-checked in all builds; every let value is a LET suspension | 1.5, 2.4, 2.8, 4.2, 9 | C3.14 (5-8) |
| Staging re-sequenced with measured gates and CPU targets; v0 tables every node; platform gate at the first Init acceptance | 11 | C2.7, C2.2, C1.15, C1.11 |
| Host convention decided: convention H, depth bound 64 (`review/stepper-convention.md`); frames exist only when a judgment is suspended; `ACT` registers in a register stack; `TRY` written only on suspension, without `tryp`; operations that call sub-judgments get frame ops; trace = `call` events | 1.3 (last paragraph), 8.2 "Decision", 13.3, 13.4, 13.7 | B4 decision; skeleton, 2026-11-24 |
| `MISS = 0x7FFFFFFC` instead of -1 (which is the immediate 2^31 - 1); `TSTAMP PERM = 0x3FFFFFFF` instead of 0x7FFFFFFF (which is the signal SUSP); run header bit positions fixed; FVAR memo word `M_FV0`; `Hash` as part of the frozen table format; signals, staging switches and debug bits in `Op` | 13.3, 13.7 | inconsistencies found while the constants were written into `Layout` / `Op` (skeleton) |
| `Op.HOST_DEPTH` = 128 instead of 64: on Init the machine wrote 0.234% of its call events as frames under D = 64 (bound of convention H12 item 2: 0.1%), 0.041% under 128, 0.017% under 256, with identical ticks, call events and outcomes; CPU time on Init does not distinguish 64 from 256 (arena-shaped container, two rounds). Record limit 2^27 per session (one `int[]`), array growth guarded against the Java heap (`E_MEM`), `Machine.vbase` / `rbase` for `poison`, the machine's own `NatStore` overlay, run-header bit `RUN_OPEN`, frame op `LSTEP 0x3C` | 13.3, 9, 1.6, 2.9 (`rlvm-machine/NOTES-M6.md` F1, F16-F22) | M6 and requests of M1, M4, M8, 2026-11-24 |
| Module as built: files `Hash`, `Scope`, `Show`; file ownership per package with the sandbox workflow; entry steps as the interface between packages; fixture language; `rlvm-testkit` as a test dependency | 13.1, 13.7 | skeleton, 2026-11-24 |
| **Eager reduction (wave 6, 2026-11-24)**: 5.2 and 5.3 say that conversion does not depend on the declaration being checked, and 5.4 lets `POS` be read while `ER` is set. Both are wrong where `eagerReduce` is involved: eager reduction is a MODE of the official `is_def_eq` (a pair can be T plain and F under ER, and the reverse), and the official success cache lives for one declaration and is keyed by terms with unique free variables, while the pair table and the memo words are keyed by canonical values and outlive binder scopes and (warm) declarations. Rule as built: a declaration that enters eager reduction is DECLINED by the facade in every configuration (`Machine.enterER`, `erSeen`; `E_LIMIT` / `UNSUPPORTED_INPUT`), the reference engine decides it. To take such declarations back: mode-tagged pair entries plus memo words that record whether a K-rule comparison was involved, or unique variables per `OPEN` in a session of their own | 4.4.1 c0, 4.5.4, 5.2, 5.3, 5.4, 14.2 | findings M6-F26, S-F4, S-F5 (arena `bad/bugs/rec-of-subst-prop`); `rlvm-machine/NOTES-M6.md` F26 |
| Session cap: the machine has a cap of its own (`-X machine.session-kib`, default 1 MiB) beside the driver's; pooled session arrays (kept slabs, pair tables, working stacks); growth allocations never raise `OutOfMemoryError` | 1.6, 9, 14.5 | measured: CPU time rises with the cap while instructions fall (NOTES-M6 F24, F25, F27) |
| Switches: lists read left to right (`none`, `default`, `all`, `-name`); two experiment bits per package; `t2`, `lazyeval`, `levels-complete`, `match`, `joint`, `same`; `rlvm-machine/SWITCHES.md` is the list of record. Run header bits 4..7 = count of non-HOLE slots (M1) | 11, 13.3, 2.4 | wave 6 (NOTES-M6 F28, F29) |
| Unchanged in substance: value kinds and canonical free variables, the instruction tables and transitions of section 4 apart from the rows named above, check stamps (5.3), the worked examples (instruction estimates), the reuse verdict (section 10) | | not contested |

Not resolved in this document, by design: (the choice between the two host conventions was resolved on
2026-11-24, see 8.2 "Decision"); CPU-time profiles of the Cedar-level corpora (`R13` counts instructions); the session
cap and `ELAB_AT` (sweeps of section 11); the two store accessors marked "requested" in section 12.
