# 11 - Memory management, in-place reuse and parallelism: design options for RLVM

Scope: Part A the reussir idea (reference counting with reuse, few-bit uniqueness) and what survives of it
in a JVM-hosted lazy evaluator; Part B JVM memory management under 16 GB when GC instructions are charged;
Part C parallelism under the instruction metric. Companion documents: `09-nbe-hoas-tricks.md` (evaluation
core, the two normalization-bench repositories), `04`/`05`/`06`/`07` (leader deep-dives), `08` (Truffle).

Evidence tags: **[src]** read in source, path given (external repositories relative to `refs/`; reussir
paths relative to `/home/queclr/Documents/`); **[instr]** exact user-space instruction counts measured here
with the project's `bench/arena-docker.sh --arena-limits bench/measure.sh` (perf stat `instructions:u`, all
threads, 8 performance cores, 16 GB; every figure is a whole JVM process); **[cpu]** CPU-time measured here,
pinned to the same 8 cores; **[lit]** from the literature, not re-verified here; **[est]** my estimate.
Programs: `spikes/memory/src/*.java`; drivers `spikes/memory/run_instr.sh`, `run_all.sh`; raw output
`spikes/memory/results/`. JVM: GraalVM CE 25.0.5 (Graal JIT), aarch64.

## 0. Decisions in one page

1. **Do not build dynamic uniqueness bits.** On HotSpot an in-place append guarded by a "unique" flag costs
   95 instructions per appended argument against 52 for an immutable snoc cell and 43 for building the
   argument array once at its final size [instr, A.4]. Reusing a constructor cell in a loop that dies young
   saves 16 instructions per cell, 21% of a loop that does nothing else [instr]. The flag must be maintained
   at every reference duplication of a lazy evaluator, by hand, in Java; one missed mark is an unsound
   in-place write, i.e. a possible wrongly accepted proof (ranking criterion 1).
2. **Take from reussir what is static or structural**: (a) flex -> freeze: objects are mutable only while
   one activation owns them (builders, argument stacks, frames under construction) and are frozen by being
   returned or interned; (b) regions: per-declaration / per-session arenas of primitives that are reset in
   bulk; (c) specialisation on statically known facts (`.unique` clones in reussir; here per-binder bits
   computed at load: dead, strict, captured, shared-in-DAG) (A.5-A.7).
3. **The cost model that matters** [instr, B.4]: an object that dies before the next young collection costs
   nothing to reclaim (16 GB of garbage: 54.3 G instructions with a 64 MB, 512 MB or 4 GB nursery alike). An
   object that survives costs about **10 instructions per byte** to promote (325 per 34-byte node) and
   about **380 instructions per object per full collection** afterwards. Allocating it cost 12-20. Design so
   that almost nothing survives, and so that what must live long is not an object.
4. **Export DAG in primitive columns** (`byte[] kind`, `int[] a, b, ...`), never one object per node:
   17 B/node against 34 B (28 B with compact headers), zero GC work against 6.5 G instructions to promote
   20 M nodes plus 7.6 G per full collection, equal instructions per traversal step [instr, B.3]. Avoid
   `MemorySegment` on hot paths (1.9x the instructions of `int[]`), avoid chunked `int[][]` where a flat
   array can be sized up front (+25%).
5. **Collector: SerialGC**, `-Xms = -Xmx`, young generation 2-4 GB, fixed. ParallelGC is 2-6% cheaper on
   nursery survivors and 1.5% dearer on pure allocation, but +36% on full collections and +33% when a deep
   stack is scanned; G1 costs +16% on pure allocation (barriers) and 3-6x on survivor-heavy work; ZGC +11%
   and 17 G kernel instructions; Epsilon saves 6% (no card marks) and cannot hold the allocation volume
   [instr, B.10].
6. **Keep the Java stack shallow while allocating**: every live frame costs about **680 instructions per
   young collection** with SerialGC (1 M frames: 0.68 G per collection) [instr, B.11]. Loops and explicit
   primitive work stacks for whnf/defeq/infer; depth "hand-up" as in gruszka.
7. **Hash-consing tables: open addressing over ids or objects, no `java.util` maps, no weak references**:
   120-130 instructions per intern against 360-480 [instr, B.5]; clear by generation stamp or bulk reset.
8. **Parallelism buys wall clock only and costs instructions**: the same allocation workload on 4 mutator
   threads costs +7.2% instructions (SerialGC, 4 GB nursery; +9.8% with 1 GB) because each collection finds
   four working sets alive [instr, C.5]; gruszka (no GC) pays +0.8% for 4 workers, +1.6% for 8 [src].
   **Arena default: 1 checker worker, 1 JIT compiler thread, SerialGC, declare `threads: 2`**; keep the
   worker pool (gruszka's scheme, C.2) behind a flag and switch to 4 only if measured mathlib instructions
   rise by less than 1% (C.9).
9. **Per-worker hot mutable state must be primitive arrays.** Reference arrays of different workers that
   are promoted next to each other false-share the card table: 4 mutators took 13.8-19.3 s of CPU
   instead of 7.1-7.4 s for 10% more instructions; `-XX:+UseCondCardMark` cures it under C2 and has no
   reliable effect under the Graal JIT [cpu, B.12]. This is a wall-clock (timeout) hazard, not a ranking
   one.
10. **Kernel time is free, user time is not** (the metric is `instructions:u`): let the kernel do work
    where there is a choice (plain `read` into a reused buffer, blocking waits), and never busy-wait in
    user space (spinning GC workers, spin locks, polling loops).

---

# Part A - The reussir idea, its neighbours, and what transfers to a JVM

## A.1 What reussir implements [src: `reussir/`, HEAD ada89a69]

Reussir is an MLIR-based compiler for reference-counted functional programs whose claim is that reuse
should be explicit in the IR (`reussir/README.md:28-46`).

**Object model.** `rc<T, capability, atomicKind>`: an owning pointer to a box with an `i32` count in front
of the payload (`crates/reussir-rt/src/rc.rs:8-13`); `ref<T>` a non-owning view; `token<align, size>`
storage without an object in it; `nullable<..>` for "token or nothing". Capabilities: `shared`
(ordinary RC), `flex` (mutable, region-local, not counted), `rigid` (frozen out of a region, counted per
SCC), `field` (`include/Reussir/IR/ReussirAttrs.td:90-96`, `ReussirOps.td:663-702`).

**Decrement yields storage.** `rc.dec` expands to (`lib/Conversion/RcDecrementExpansion/RcDecrementExpansion.cpp:95-235`):

```
c = load count                      (atomic box: c = fetch_sub(1), then restore 1 on the unique path)
if likely(c == 1 [&& !is_immediate(p)]):     // unique: this was the last reference
    drop the children (destructuring dec: only the members the match did NOT bind; bound ones transfer)
    yield token(p)                           // the box itself, reinterpreted as reusable storage
else:
    store count = c - 1                      // shared
    [destructuring dec: re-retain the bound members]
    yield null
```

**Token reuse** (`lib/Transformation/TokenReuse/TokenReuse.cpp:55-112`): one pass over structured control
flow keeps the set of available tokens (persistent set; a token must dominate the use); a construction
takes the best compatible one: exact layout -> `token.ensure` (allocate only if the token is null), same
allocator bin or dynamic size -> `token.realloc`; branches intersect their sets; tokens left over are freed
at the join, at loops and at calls (tail calls always flush). It is deliberately intra-procedural.

**Specialising code on RC state.** (i) `RcDispatchFusion` turns "match, retain bound fields, release
scrutinee" into one *destructuring decrement* so that the unique path moves the fields instead of
retain+release (`lib/Transformation/RcDispatchFusion/RcDispatchFusion.cpp:12-58`, Koka's `dropn_reuse`).
(ii) `UniqueCarryingRecursionAnalysis` proves that a self-recursive function returns values that are
fresh or carried unshared from its arguments, and emits a clone `f.unique` whose entry asserts
`rc.assume_unique` (an `llvm.assume(count == 1)`), so the whole recursion runs the unique fast paths with
no checks (`lib/Transformation/UniqueCarryingRecursionAnalysis/UniqueCarryingRecursionAnalysis.cpp:12-110`).
A value is disqualified by any retaining use or a second consuming use. (iii) Nullary constructors are
immediates, never allocated (`lib/Transformation/SpecialPointerTag/SpecialPointerTag.cpp:31-45`).

**Closures are partial applications updated in place.** `closure.apply` writes the argument into the
closure's payload; a non-unique closure is first cloned (`closure.uniqify` = `rc.is_unique` test + vtable
clone); the fused `closure.eval ... with args` does one uniqueness resolution for the whole argument pack
(`include/Reussir/IR/ReussirOps.td:2271-2348`, `docs/design/thread-safety.md` section 3.2).

**Regions: flex -> freeze -> rigid** (`crates/reussir-rt/src/region/mod.rs`). Objects built in a region
have a three-word header `{status, next, vtable}` (lines 146-150), are freely mutable and may be cyclic.
`freeze` (lines 268-306) walks from the escaping root and turns the reachable part into immutable
reference-counted SCCs with a union-find over the headers; the status word is the "packed RC state" (lines
13-56): a parent pointer (sign bit clear) or, with the sign bit set, two tag bits `00 Unmarked`,
`01 Disposing`, `10 Rank(n)` (on the freeze stack), `11 Rc(n)` (count of the SCC, kept at its root).
`release` decrements the root and disposes whole SCCs (lines 220-267). Everything not frozen dies with the
region.

**Threads: colouring at birth.** A box is `normal` or `atomic` for life; `Arc<X>` requires every member
to be `Sync` already, recursive groups are promoted as a whole, and nothing is re-coloured at run time
(`docs/design/thread-safety.md` sections 1-3). The document contrasts this with Koka (dynamic marking on
first share) and it is also unlike Lean 4 (`lean_mark_mt` walks the graph and negates the counts,
`lean4/src/runtime/object.cpp:654-700`).

**A measurement from reussir's own notes that matters here**: its NbE benchmark ran 1.74x slower than Koka
*at fewer instructions*; the whole gap was node size (32 B uniform cells against 16/24 B per constructor)
through cache misses (`docs/design/per-constructor-box-sizing.md:3-13`). Under the arena's instruction
metric that gap would not have shown at all: **cache misses are not charged, instructions are**. Several
conclusions below differ from wall-clock intuition for this reason.

## A.2 Published neighbours

| Work | Mechanism | Run-time state | What transfers |
|---|---|---|---|
| Lean 4, "Counting Immutable Beans" (Ullrich, de Moura 2019) [lit] | `inc`/`dec` inserted on an IR with owned and *borrowed* parameters (borrow inference: a parameter stays borrowed unless it is reset, returned, stored, or passed owned); `reset x` + `reuse y in ctor` recycles a dead unique cell of the same size | signed count: `> 0` single-threaded, `< 0` shared between threads (atomic), `0` persistent; sticky values [src `lean4/src/runtime/object.cpp:651,667-670`]; `lean_array_push` writes in place when `lean_is_exclusive` [src `object.cpp:2784-2788`] | the kernel itself uses "shared" only as a **cache-gating heuristic**: `replace_fn` and `expr_eq_fn` consult their caches only for nodes with count > 1 [src `lean4/src/kernel/replace_fn.cpp:99`, `expr_eq_fn.cpp:34`]. This is a use of a one-bit sharing fact with no soundness risk (A.7, U3) |
| Koka: Perceus (Reinking, Xie, de Moura, Leijen 2021), frame-limited reuse (2022), FP2 (2023) [lit] | precise `dup`/`drop`, drop specialisation, reuse analysis, reuse specialisation (write only changed fields), FBIP; `fip` functions are statically guaranteed in-place | count; negative = thread-shared | the *static* guarantee (`fip`) is the part worth copying: uniqueness established by construction, not tested |
| One-bit reference counts: Wise, Friedman 1977; Stoye, Clarke, Norman 1984; Roth, Wise 1998 [lit] | one sticky bit "unique / shared"; Stoye keeps the bit **in the pointer**, so copying a pointer sets it without touching the object, and a cell reached through a unique pointer is recycled at once | 1 bit, never reset | a JVM reference has no spare bits; an `int` value id has (A.4) |
| Clean uniqueness types (Barendsen, Smetsers 1993-96) [lit] | a type attribute; unique values can be coerced to shared, never back; destructive update is licensed at compile time | none | zero run-time cost and zero run-time risk: the model for A.5 mechanism M1 |
| GHC cardinality analysis (Sergey, Vytiniotis, Peyton Jones 2014) [lit] | "used at most once" thunks are not updated, one-shot lambdas | none | the lazy-language version of linearity; shows why "one syntactic occurrence" needs care under binders (A.6) |

## A.3 Why the economics change on a JVM

Reuse pays in an RC runtime because `malloc`/`free` cost tens of instructions each, the count operations
are being paid anyway, and reused memory is cache-hot. On HotSpot:

| | RC runtime (reussir, Lean, Koka) | HotSpot |
|---|---|---|
| allocate a small object | allocator call, 20-60 instr [est] | TLAB pointer bump + header + fields: 12-20 instr [instr: 16 saved per reused cell] |
| reclaim a dead object | `dec`, test, `free` (or token) | nothing (not visited by a copying collector) |
| know that an object is unique | free: the count is there | not available; a bit must be *maintained* at every duplication |
| store into an existing object | plain store | store + card mark (about 3 instr) + array store check; old-to-young pointers make the collector scan that card |
| cache warmth of reused memory | large wall-clock effect | not part of the metric |
| object survives | nothing special | about 10 instr/byte to promote, about 380 instr/object per full GC (B.4) |

So in-place mutation can only win on the JVM by (1) removing an O(n) copy, (2) keeping a long-lived
structure from being re-created and re-promoted, or (3) never putting the data on the GC heap at all.
Removing a single small allocation is worth at most 16-20 instructions.

## A.4 Measurements

**Spine / partial-application growth** [instr, `results/instr-extra.txt`, `instr-spine.txt`;
`src/SpineAppend.java`]. 4 M spines, arguments appended one at a time, 5% of spines aliased half-way and
extended again, one read pass; every intermediate spine escapes. Instructions per appended argument are
slopes between 10 and 30 repetitions (mean length 3.5) or totals minus baseline (mean length 7.5).

| strategy | bytes/arg (len 3.5 / 7.5) | instr/arg, len 3.5 | instr/arg, len 7.5 | CPU ns/arg (3.5 / 7.5) |
|---|--:|--:|--:|--:|
| `exact`: all arguments known, array allocated once | 9.6 / - | **43** | 44 | 5.0 / - |
| `cons`: immutable snoc cell `{prev, arg, len}` | 24 / 24 | **52** | 55 | 3.9 / 4.2 |
| `flag`: `{Object[] buf; int len; boolean shared}`, append in place when `!shared` | 22 / 17 | 95 | 117 | 4.5 / 4.5 |
| `claim`: immutable `{buf, len}` view over `{Object[] a; int used}`; append claims slot `len` when `used == len` | 48 / 44 | 95 | 126 | 6.8 / 6.4 |
| `copy`: `Arrays.copyOf(n+1)` per append | 33 / 48 | 143 | 201 | 6.4 / 8.6 |

Reading: the flag scheme allocates the fewest bytes and is within 15% of the snoc list in CPU time, yet it
executes 1.8-2.1x the instructions (flag test, capacity test, array store check, card mark, length store,
growth copies). Under the arena metric it loses to the persistent list and to n-ary construction.

**Constructor reuse** [instr, `results/instr-extra.txt`, `instr-reuse.txt`; `src/CtorReuse.java`]. A
`List.map`-shaped loop: consume a cons cell, produce one with a freshly computed boxed payload; all cells
unique (best case).

| regime | fresh allocation | reuse when `unique` | saving |
|---|--:|--:|---|
| list of 1000 lives and dies in the nursery (build + map) | 76.6 instr/elem, 80 B | 60.5 instr/elem, 56 B | 16 instr per reused cell (21% of this loop) |
| 10 M-element list in the old generation, mapped 10 times | 614 instr/elem (9 collections, 4.0 s) | 181 instr/elem (3 collections, 1.0 s) | 3.4x, all of it survivor copying of the *result* |

Reuse matters when the result is long-lived (the fresh version makes the collector copy 400 MB per round).
A lazy kernel rarely produces such results: a whnf step yields one constructor whose arguments are thunks,
and the scrutinee is still referenced by the thunk that produced it, so it is not unique anyway.

## A.5 Three mechanisms for in-place mutation on a JVM

**M1 - ownership by construction (flex -> freeze).** An object is mutable only between its allocation and
the moment the creating activation publishes it (returns it, stores it in an environment, thunk, table or
another thread's view). No bit, no test. Invariant: the creating method holds the only reference until
publication; publication is the last write. This is Clean/`fip`/reussir-flex made a coding rule. Debug
builds may carry a `frozen` boolean with `assert !frozen` on every write.

**M2 - monotone claim.** An append-only buffer shared by immutable prefix views (`claim` above): a view
`{buf, len}` may write slot `len` iff `buf.used == len`, and then bumps `used`. No "mark shared" is ever
needed, because a second extension from the same view finds `used != len` and copies. Thread rule: either
the buffer is worker-local, or `used` is advanced with a CAS. As measured it is not cheaper than a snoc
list, so it is only of interest where O(1) indexing of the result is required.

**M3 - sticky shared bit + static linearity.** One bit per eligible object, `0 = unique`, `1 = shared`,
never cleared (there is no decrement in a tracing-GC world, so sharing is permanent even after the other
reference dies). A write is allowed iff the bit is 0 and the writer holds the reference. "Mark shared"
must run **before a second reference becomes reachable**, i.e. at exactly the places where an RC compiler
inserts `inc`:

```
1. variable lookup            env slot keeps v and v is returned  -> mark, unless this is the statically
                              last use of the slot AND the frame itself is unique (not captured)
2. read through a shared parent   field/slot of an object whose bit is 1 -> mark the child on read
                              (sharing is inherited lazily, as Lean/Koka `dup` on projection)
3. closure / thunk creation   captures the frame -> mark the frame
4. thunk force                the memo keeps the result and returns it -> mark (always)
5. memo / cache / intern insertion, publication to another thread -> mark (permanently frozen)
6. any Java local that is used twice with the object still writable (the interpreter's own code)
```

Rule 6 is the problem: the evaluator would have to be written in ownership-passing style without any
compiler support, and rule 2 turns reads into writes (cache-line dirtying, and data races between
workers on shared structures).

## A.6 Candidate by candidate

| Candidate | Mechanism if built | Where "mark shared" would be needed | Hash-consing / caches | Threads | Expected benefit | Risk | Verdict |
|---|---|---|---|---|---|---|---|
| **Spine arrays of neutrals** | M3 append in place; or M2 | on every env lookup / thunk force that returns the neutral, on capture | an interned neutral is shared by definition: its key is its content; never appendable | worker-local values only | negative in instructions (95 vs 52/43) | unsound append corrupts a term seen elsewhere | **No.** Flatten application chains at load into `App(f, args[])`, collect arguments on the evaluator's argument stack and build the spine once (`exact`), or use snoc cells if spines are hash-consed cell by cell (design D of doc 09) |
| **Partial applications** | accumulate arguments in the PAP's buffer in place | same | PAPs are rarely worth interning | worker-local | saves one copy of <= arity references per saturated call | same | **M1 only**: allocate the argument buffer at full arity on the first partial application; the saturating call passes that buffer on as the callee's frame (it is never written again). No flag: a PAP that is applied twice copies (detect with M2's `used` counter) |
| **Environment frames** ("extend in place when the owning closure is entered linearly") | push the argument onto the captured frame | closure duplication (lookup, force), frame capture | hash-consed environments (doc 09 section 6) are immutable | worker-local | none beyond what telescopes give | high (frames are the most aliased objects) | **No.** Enter binder telescopes n at a time: one frame `{parent, slots[n]}` or n cons cells per call; direct beta `(fun x => b) a` without creating a closure; frames of bodies that create no closure and no thunk (a static bit) live on a per-worker scratch stack (M1) |
| **Thunk update** | write `value`, clear `term`/`env` | none: single assignment, idempotent, needs no uniqueness | memo slots of interned values are the same thing | worker-local: plain writes; shared: publish an immutable result through a `final` field or `setRelease` (C.3) | essential (call-by-need) | low | **Yes, already part of any design.** Also drop the captured environment on update (space, fewer survivors) |
| **Constructor reuse in iota loops** (Nat/List recursion producing the same shape) | M3 on constructor cells | every lookup/force | constructor applications are neutrals: interned in design D | worker-local | 16 instr per reused cell when it fires; it fires rarely under call-by-need (the forcing thunk still holds the scrutinee) | high | **No.** The real lever for these loops is not allocating: Nat literals with `long`/BigInteger arithmetic instead of `Nat.succ` chains, and the closed-computation machine of doc 09 (engine E), where reuse can be revisited with real data |
| **Substitution / instantiation buffers, argument and work stacks** | M1: per-worker growable primitive or reference stacks, reset per call | none | not interned | one per worker | removes most temporary arrays; primitive stacks cost the collector nothing | low | **Yes, first.** |
| **Truffle specialisations keyed on linearity + a dynamic shared flag** | static: node fields fixed at load; dynamic: guard `!v.shared` | dynamic part: as M3 | - | ASTs are shared between workers; static bits are immutable, dynamic guards add polymorphism | static part: real (A.7); dynamic part: negative | static: none; dynamic: as M3 | **Static bits yes, dynamic flag no** |

If the project still wants to test M3, the cheapest decisive experiment is **shadow-bit profiling**, not an
implementation: in a debug build keep the bit, follow rules 1-5 conservatively (mark on every lookup and
force), never mutate, and count at each append/extend/construct site how often the object is still unique
*after* the structural tricks above. Decide from the counts on Init + Std.

## A.7 Static sharing and linearity bits computed at load

All of these are functions of the immutable export DAG, need no run-time maintenance, and are
soundness-neutral (they select between two correct evaluation orders or decide whether to consult a cache).

```
per binder (lam / pi / let), from its body b:
  DEAD      bvar 0 does not occur in b                (doc 09: (b.fvMask & 1) == 0)
  STRICT    b's weak-head evaluation certainly forces bvar 0: b = bvar 0 | app spine with head bvar 0 |
            proj of bvar 0 | recursor/casesOn application whose major premise is bvar 0   (syntactic)
  CAPTURES  b contains a lam/pi/let or a non-trivial argument (anything that will become a closure or a
            thunk holding the frame)
per node:
  TRIVIAL   bvar, const, sort, literal, lambda: evaluating it allocates at most one small object
  SHARED    in-degree >= 2 in the export DAG, or root of a declaration (count parents in one pass)
```

Uses:

- **U1 DEAD**: do not evaluate, do not allocate a thunk for, the argument; store a shared dummy. This is
  gruszka/pestka's "an argument its binder never reads is never evaluated" [src
  `lean-kernel-arena/checkers/gruszka.yaml`]. (The argument is still type-checked by `infer`.)
- **U2 TRIVIAL or STRICT argument**: evaluate now, no thunk object. Not "one syntactic occurrence": a
  single occurrence under a lambda can be forced many times, and a value read once from a frame can still
  be reached twice through the result. STRICT + eager evaluation is the sound core of linearity here.
- **U3 SHARED gates the caches**, as the official kernel does with `is_shared`: probe and fill
  `(term, env)` evaluation caches, inference caches and instantiate caches only for SHARED nodes. An
  unshared node is reached only through its unique parent chain, so the nearest SHARED ancestor's entry
  answers first (a heuristic: a hit is lost when one closure body is evaluated twice in the same
  environment and applications are not memoised at the value level). Each probe avoided is 120+
  instructions (B.5).
- **U4 !CAPTURES**: the frame of this telescope can be a slice of the worker's scratch stack and is popped
  on return (M1).
- **Truffle**: these bits are `final` fields of the node, so `@Specialization(guards = "dead")` etc. fold
  away in the interpreter's profile and in compiled code; no dynamic guard on values is introduced.
- **Int-id values** (design D of doc 09): an id has spare bits, so Stoye's bit-in-the-pointer is expressible
  for free (bit 31 = "this reference is the only one"). It is only meaningful for records that are not
  hash-consed; with full hash-consing there are none, and the analogue of token reuse is **LIFO arena
  reuse**: a record allocated at the arena top and not yet interned or referenced can be popped or
  overwritten, and a region is rolled back wholesale (gruszka's regions, doc 05 section 2.11). That, not
  per-object uniqueness, is the reussir idea in an arena design.

## A.8 Micro-benchmark plan

Done here: spine strategies, constructor reuse, object versus primitive storage, hash-consing tables,
collector matrix, thread scaling, stack depth (all in `spikes/memory`, rerun with
`sh spikes/memory/run_instr.sh`). To do on the real evaluator, in this order: (1) shadow-bit profile
(A.6) if M3 is still wanted; (2) instructions with U1-U4 switched on one by one on Init/Std; (3) snoc-cell
spines against `App(f, args[])` records inside the real hash-consing scheme; (4) scratch-stack frames
against heap frames.

---

# Part B - JVM memory management under 16 GB when GC instructions are charged

## B.1 Budget

Mathlib as seen by the leaders: 38 M environment expression nodes plus 67 M nodes inside proofs, about
0.70-0.72 M declarations [src `gruszka/docs/architecture.md:8-11`, `gruszka/README.md:226`,
`tenet-jvm/docs/status.md:69`]. Peak memory is not ranked; only the 16 GB limit matters, and the arena
reports GNU time's maximum resident set.

| Item | Size [est unless tagged] |
|---|---|
| JVM itself (code cache, metaspace, collector tables, JIT arenas) | 0.2-0.5 GB; an idle JVM run is 108-127 MB RSS [instr runs "none"] |
| all 105 M expression nodes in columns at 17-28 B (B.3) | 1.8-2.9 GB (gruszka: 13 B/node for the 38 M environment nodes) |
| names, levels, declaration table, literals | 0.1-0.3 GB |
| file read buffer (streamed, reused) | 16-64 MB. Do not `mmap` the whole multi-GB export: touched file pages are resident |
| young generation (touched completely, so it counts in RSS) | 2-4 GB |
| old generation in use: session data, global tables | 0.5-2 GB |
| **peak** | **5-9 GB with `-Xmx8g`** (primitive columns are part of the Java heap) |

Consequence: there is room to keep **every** expression node in columns for the whole run. Keeping proofs
as raw bytes until checked (gruszka, pestka) saves memory, not instructions, in a full check: every proof
is decoded exactly once either way. It needs a trustworthy "which nodes are proof-local" partition, which
lean4export's NDJSON does not provide (expression ids are global and later declarations may reuse a node
first emitted for an earlier proof; Susz's converter computes that partition and still has "foreign
block" references [src `gruszka/docs/architecture.md:775,1016`]). Recommendation: decode once into
columns, drop nothing; revisit only if RSS approaches 10 GB.

## B.2 Object layout [cpu-independent, exact: `results/layout.txt`, `src/Layout.java`]

Bytes per object (thread-allocated bytes over 1 M allocations). Default = 12-byte header, compressed
references; compact = `-XX:+UseCompactObjectHeaders` (8-byte header, product flag in JDK 25, works with the
Graal JIT here); last column = uncompressed references (heap above 32 GB, not our case).

| Shape | default | compact | uncompressed |
|---|--:|--:|--:|
| empty object | 16 | 8 | 16 |
| 1 ref / 2 refs / 3 refs / 4 refs | 16 / 24 / 24 / 32 | 16 / 16 / 24 / 24 | 24 / 32 / 40 / 48 |
| 1 int / 2 ints / 3 ints / 1 long | 16 / 24 / 24 / 24 | 16 / 16 / 24 / 16 | 16 / 24 / 24 / 24 |
| 2 refs + int | 24 | 24 | 32 |
| 2 refs + 2 ints (hash-consed application node with hash and flags) | 32 | 24 | 40 |
| 2 refs + byte / 3 refs + byte | 24 / 32 | 24 / 24 | 32 / 40 |
| thunk `{int term, ref env, ref value, byte state}` | 32 | 24 | 40 |
| closure `{int term, ref env, ref levels}` | 24 | 24 | 32 |
| environment cons `{ref val, ref parent}` | 24 | 16 | 32 |
| `Object[n]`, n = 1, 2, 3, 4, 8 | 24, 24, 32, 32, 48 | 16, 24, 24, 32, 48 | 24, 32, 40, 48, 80 |
| `int[n]`, n = 1, 2, 3, 4 | 24, 24, 32, 32 | 16, 24, 24, 32 | same as default |
| `Long` / `Integer` (boxed) | 24 / 16 | 16 / 16 | 24 / 16 |
| `BigInteger` holding one word | 64 | 48 | 64 |
| lambda capturing one reference | 16 | 16 | 24 |

Rules: header 12 B (compact: 8 B), arrays +4 B length, 8-byte alignment, references 4 B. Packing advice:
put the 32-bit hash, a 16-bit loose-bvar count and 16 bits of flags into one `int` pair next to two
references (32 B default, 24 B compact); a third reference is free in the default layout when there is one
`int` (24 B), a fourth field pair pushes to 32 B. Compact headers changed total instructions by -5% to +6%
in the experiments below (no clear sign); they are a memory lever (-17% to -33% on small nodes), to be
measured on the real checker.

## B.3 The immutable export DAG: objects versus primitives

20 M nodes (var / const / app / lam / pi) with a 32-bit hash and a 32-bit meta word, children mostly
recent ids [instr `results/instr-store.txt`; cpu `results/store.txt`; `src/TermStore.java`]. Columns relative to
the 5-column layout; traversal = explicit-stack DFS, 240 M node visits.

| Representation | B/node | build, instr/node vs columns | promotion with a 256 MB nursery | two full collections | traversal instr/visit | traversal ns/visit, 20 M nodes / 100 K nodes |
|---|--:|--:|--:|--:|--:|--:|
| one object per node | 33.9 | +14 | **+6.5 G** (325/node, 9.6/byte) | **15.2 G** (380/node each) | 32.9 | 12.4 / 1.7 |
| objects, compact headers | 28.0 | +28 | n.m. | 16.1 G | n.m. | 13.3 / n.m. |
| 5 parallel columns: `byte[] kind`, `int[] a, b, hash, meta` | 17.0 | 0 | 0 | 0.2 G | **29.2** | **7.8 / 1.4** |
| one `int[]`, stride 4 | 16.0 | -14 | 0 | 0.6 G | 30.5 | 12.8 / 1.6 |
| `int[][]` chunks of 2^18 nodes | 16.2 | +53 | 0 | about 0 | 36.6 | 14.3 / 2.4 |
| off-heap `MemorySegment` (FFM) | 16 | +92 | 0 | 0.4 G | 56.3 | 12.7 / 1.5 |

Other collectors on the object version (build + two full collections, total process): Serial 20.8 G,
Parallel 26.3 G, G1 36.2 G.

Conclusions:

1. Objects and flat arrays cost the same instructions to *read*; objects cost 325 instructions each the
   first time they survive and 380 each at every full collection. For 105 M nodes that is 34 G to promote
   and 40 G per full collection, against a total budget of 400-600 G.
2. Flat columns need no chunking if they are sized before filling. Size them from the file length (a
   record is at least about 12 bytes of NDJSON, so `nodes <= fileBytes / 12` [est; verify on a real export])
   and let untouched pages stay uncommitted, or grow by doubling with a chunk directory only above 2^31
   elements. Chunked access costs +25% instructions on access-heavy code.
3. `MemorySegment` is 1.9x the instructions of `int[]` per access here. Use it, if at all, only for bulk
   byte scanning of a mapped file, never for node fields.
4. Parallel columns beat an interleaved `int[]` in CPU time in this workload (cause not established; it
   reproduces with C2) and tie in instructions. Gruszka and tenet-jvm both use columns [src
   `gruszka/docs/architecture.md:44-48`, `tenet-jvm/docs/design.md:24`].

Recommended record (per expression id, columns): `byte kind` (binder info folded in), `int a`, `int b`,
`int c` (third child / side-table index for let, proj, const levels), `int hash`, `short loose`,
`long fvMask` (doc 09 section 1.2) and one `byte` of the static bits of A.7: 28 B/node, 2.9 GB for 105 M
nodes; drop `hash` (recomputable) or `fvMask` for proof-only nodes if memory matters. Allocate these
arrays first so that they sit at the bottom of the old generation and are never moved by a compaction.

Lazy materialisation: nothing needs to be an object until the evaluator wants one. Per-constant derived
data (unfolded body entry point, definitional height, recursor rules, compiled call target) goes into
per-constant slots filled on first use (C.3). A `Term` in the kernel is an `int`.

## B.4 What the collector charges [instr `results/instr-gc.txt`, `instr-store.txt`, `instr-reuse.txt`; `src/GcCost.java`]

Workload: 16 GB of 24-byte nodes with two references each; the baseline process (start-up, JIT, 1 GB of
the same work) is 5.3 G.

| Scenario | Serial, nursery 64 MB | 256 MB | 512 MB / 1 GB | 4 GB | Parallel (8 / 2 / 1 threads), 1 GB | G1 | ZGC |
|---|--:|--:|--:|--:|--:|--:|--:|
| S1 pure churn, nothing survives | 54.3 | - | 54.5 | 54.3 | 55.1 / - / 55.3 (512 MB) | 62.9 | 60.4 |
| S2 a "declaration" allocates 64 MB and keeps 10% of its nodes (and what they reach) until it ends | 112.4 | 66.3 | 60.0 | 56.0 | 56.3 / 58.6 / 58.9 | 65.3 | 64.6 |
| S3 = S2 + a static 1 GB graph of 36 M old objects, 1 GB nursery | - | - | 84.1 | - | 91.8 / 98.7 / - | 108.2 | - |

(G instructions, whole process; the "512 MB / 1 GB" column is 512 MB for S1 and 1 GB for S2 and S3;
Parallel in S1 used a 512 MB nursery. S3's baseline with the old graph built and compacted once is 30.0 G, so the
graph cost 24.8 G to create and nothing afterwards: 84.1 - 30.0 = 54.1 G for the 15.5 GB of work, the same
as S2.)

The model, for SerialGC:

```
GC instructions  ~=  N_young * ( L_young * 10..16 /byte  +  frames * 680 )  +  N_full * L_old_objects * 11 /byte
N_young = allocated bytes / eden size          L_young = bytes of young objects alive at a collection
dead objects: 0        old objects not written to: 0 per young collection        primitive arrays: 0
```

- **Nursery size is irrelevant when nothing survives** (S1: 320 collections cost the same as 5) and decisive
  when working sets survive (S2, against about 55 G without collector work: 2.0x with 64 MB, +20% with
  256 MB, +9% with 1 GB, +2% with 4 GB).
  The rule is *eden >> the largest working set that is alive across a collection*, times the number of
  workers.
- **Old objects are free to keep but expensive to make and to compact** (S3). The static environment must
  not be 36-105 M objects.
- An explicit promotion costs as much as allocating 20-30 small objects (9.6 instr/byte measured on the
  node graph, 13 on linked cells). Tenuring policy therefore matters for data that lives for a few
  collections: with the default `MaxTenuringThreshold=15` a session's objects are copied between survivor
  spaces at every collection they live through; with a threshold of 0 or 1 they are copied once and die in
  the old generation, which then needs occasional full collections (cheap if little is alive: cost is per
  live object). Both ends must be measured (B.10).
- Worked example [est]: 60 GB allocated over mathlib, 3.2 GB eden: 19 collections; with 30 MB alive at
  each, 19 * 30 MB * 13 = 7.4 G instructions (1.5% of 500 G); with a 1 GB nursery 29 G (6%); with a
  value heap in primitive arenas, about nothing.

Per-declaration scratch data dies young by construction if the nursery is large; **session** data (values
and memo tables kept across declarations, the leaders' main trick, docs 04/05) is exactly the kind of
medium-lived data a generational collector handles worst. Two ways out: (i) sessions in primitive arenas
(`int[]` records + open-addressing tables), reset in bulk: no tracing at all (design D of doc 09); (ii)
object values with sessions kept small relative to the nursery: a collection then finds at most one
session per worker alive and copies it once, 0.2-0.8 G instructions for a 16-64 MB session at 13
instr/byte [est]. (i) is the instruction-optimal choice; (ii) is acceptable only with measurements.

## B.5 Hash-consing and caches without weak references [instr `results/instr-extra.txt`; cpu `results/hashcons.txt`; `src/HashCons.java`]

10 M intern requests, 5 M distinct `(kind, a, b)` nodes.

| Table | instr / intern | CPU ns / intern | B / distinct node | cost at a full collection |
|---|--:|--:|--:|---|
| `int[]` open addressing over ids, keys in columns | **120** | 72 | 9 + 8 (slot at load 0.5) | none |
| `Node[]` open addressing over objects (`==` on children) | 129 | 93 | 32 + 8 | 2.4 G for 5 M nodes (470/node) |
| `HashMap<Node, Node>` | 483 | 118 | 32 + 40 | higher (entry objects) |
| `WeakHashMap<Node, WeakReference<Node>>` | 360 | 176 | 32 + 80 | highest (reference processing: 2.4 then 1.3 s CPU) |

Design:

```
table: int[] slots, power of two, load <= 0.5; slot = id + 1 (0 = empty); linear probing
key:   compared against the arena/column words of the candidate id (no key objects, no boxing)
hash:  one 32-bit mix of (kind, a, b[, c]); store it in the record only if rehashing is frequent
grow:  double and re-insert from the records (sequential scan); never per-element remove
reset: session end = Arrays.fill(slots, 0) on the used prefix, or bump a generation number that is stored
       in the high bits of every slot (O(1) reset; nano-lean and gruszka use stamps for the tables that
       are cleared per declaration [docs 04 T9, 05 section 2.9])
```

No weak references anywhere: a weak canonicalising map costs 3x per lookup, holds an entry object and a
reference object per node, and adds reference-processing work to every collection. Lifetime is managed by
scope instead: global tables (names, levels, export nodes) live for the run; session tables die with the
session; scratch tables die with the declaration.

Bounded caches: direct-mapped, lossy, fixed size (2^16..2^20 slots), key and value in primitive arrays,
generation stamp for O(1) clear; a collision overwrites. Sizes in the leaders: nano-lean environment-push
cache 2^16 slots, lossy; gruszka `WIDE_CAP = 2^20` entries for facts about MAIN terms, session tables
8 MiB, 4 MiB kept across sessions (`KEEP_BYTES`), every cross-declaration cap divided when there are more
than 4 workers [src `gruszka/docs/architecture.md:68-92`; doc 04 section 4.4].

## B.6 Boxing, identity hashes, primitive maps

- Never key a map by `Long`/`Integer`/record objects on a hot path: a `HashMap<Long, V>` entry is 32 B +
  24 B key + the value against 12 B in `long[] keys; int[] vals`. Pair keys pack into a `long`
  (`(a << 32) | b`, ordered when symmetric).
- `System.identityHashCode` / `IdentityHashMap` / default `Object.hashCode()`: the first call per object is
  a runtime slow path and the value is random per run [lit], so probe sequences, and with them the
  instruction count, differ between runs. Use ids or an explicit `int hash` field computed from content.
- Lambdas and varargs allocate (16 B per capturing lambda); none on hot paths in the interpreter tier.
- Reference stores are not free: each store into a heap object or `Object[]` carries a card mark (about 3
  instructions; an Epsilon run of pure churn, which has no barrier, was 6% cheaper: 27.4 G against 29.0 G
  for 8 GB [instr]). `int` stores carry none.

## B.7 Big numbers

Nat literals: `long` fast path (non-negative, `Math.addExact`/`multiplyExact` overflow checks, truncated
subtraction, div/mod/gcd/shift/bit operations on `long`), `java.math.BigInteger` beyond 2^63. A
`BigInteger` holding one word is 64 B (48 B compact), so never create one for a small value. GMP through
the FFM API is not worth building for mathlib: literals there are small, each downcall has a fixed cost and
needs the magnitude copied in and out, and `BigInteger` already switches to Karatsuba / Toom-Cook /
Burnikel-Ziegler for large operands [lit]. It would only matter for corpora that compute on 10^5-bit numbers
(the CFSG certificates in gruszka's notes), which the arena's mathlib number does not contain. Big literals
are interned by content per session (gruszka: "a big Nat result equal to one already in the session is
that value" [src `docs/architecture.md:305-309`]).

## B.8 Names, levels, strings

Names stay export ids (`int`); a `nameId -> constant row` `int[]` replaces every string-keyed map; a name's
text is materialised only for an error message. Universe levels are interned once in a global table (ids),
level lists and level substitutions are interned per worker and carried by closures rather than applied to
terms (docs 04 T7, 05 section 2.2). String literals are `(offset, length)` into one `byte[]`.

## B.9 How the leaders keep memory low

| Checker (mathlib virtual time / peak) | Mechanism [src: `lean-kernel-arena/checkers/*.yaml` and the cited files] |
|---|---|
| nano-lean (65.8 s / 5.0 GB) | bump arenas per worker session, reset when the session arena passes 64 MiB (`nano-lean-v0.4.2/src/fast.rs:31,305-309`), 4096 MiB arena budget (`fast.rs:23`), 256 MiB worker stacks; hash-consed 32 B expressions built at import; mimalloc, import buffers returned after load (`fast.rs:112-125`) |
| gruszka (77.0 s / 2.3 GB) | "terms are never built": environment terms in columns, proof blocks as bytes until checked; values are ids in per-worker arenas, hash-consed; a session ends at 8 MiB of tables; regions roll back inside heavy declarations; every kept table is capped (`gruszka/docs/architecture.md:8-11,44-92,115-131,143-200`) |
| sokonanoda (87.9 s / 5.0 GB) | per-thread bump arena, hash-consed value nodes (`sokonanoda/src/checker/tc.rs:78`, doc 06) |
| pestka (4.1 min) | proof read from the Susz file only when checked; "caches are bounded by default (low-memory mode)" |
| tenet-jvm (27.2 min / 11.8 GB) | expressions in chunked primitive arrays (10.6 B/node), objects built on demand into a per-declaration memo; but `-XX:+UseParallelGC -XX:MaxRAMPercentage=75` (`tenet-jvm/bin/tenet-jvm`): a 12 GB heap on the 16 GB runner, which the JVM then fills |

## B.10 Collector configuration

Measured ranking for this kind of workload [instr, B.3, B.4, B.11; independently the platform spike,
`spikes/platform/results/gc-matrix-instr.txt`: Serial 54-63 G, Parallel 68-75 G, G1 204-312 G on a
200 M-node transform]:

| Collector | Pure allocation | Survivors | Full collection | Deep stack | Verdict |
|---|---|---|---|---|---|
| Serial | baseline | baseline | baseline | baseline | **default** |
| Parallel, 1-2 GC threads | +1.5% | -2% to -6% (better scavenger) | +36% | +33% with 8 threads (idle workers spin), -6% with 1 | second candidate, only if full collections are designed out |
| G1 | +16% (barriers) | +9% to 5x | worse | +41% per collection | no |
| generational ZGC | +11%, 17 G kernel instructions, 7.7 GB RSS | +8% to +15% | n.m. | best per collection | no |
| Epsilon | -6% | n/a | n/a | n/a | infeasible: RSS = everything ever allocated (9.3 GB for 8 GB) |

Candidates to run on the real checker (mathlib prefix, `perf stat` instructions):

```
A  -XX:+UseSerialGC -Xms8g -Xmx8g -Xmn3g                       (default proposal)
A1 A with -Xmn2g / -Xmn4g                                      (nursery sweep)
A2 A with -XX:MaxTenuringThreshold=0 | 1 | 4 | 15              (copy-once versus age in survivor spaces)
A3 A with -XX:SurvivorRatio=4 | 8 | 16                         (survivor overflow promotes prematurely)
A4 A with -XX:+UseCompactObjectHeaders
B  -XX:+UseParallelGC -XX:ParallelGCThreads=1 -XX:-UseAdaptiveSizePolicy -Xms8g -Xmx8g -Xmn3g
```

Always: `-Xms == -Xmx` (no resizing), no `System.gc()`, no `-XX:+AlwaysPreTouch` (it only raises RSS),
worker threads created with an explicit stack size, long-lived primitive arrays allocated before anything
else. The JVM's fixed cost is 0.41 G instructions for start and exit (0.07 s of virtual time) [instr
`results/instr-reuse.txt`, first line]; JIT budget is the subject of doc 08 section 1.7.

## B.11 Stack depth is a collector cost [instr `results/instr-deep.txt`; cpu `results/deep.txt`; `src/DeepStackGc.java`]

Recursion to depth D (three live references per frame), then 40 young collections while that stack is live.

| Collector | depth 1 000 | depth 1 000 000 | extra per collection | per frame per collection |
|---|--:|--:|--:|--:|
| Serial | 32.6 G | 59.9 G | 0.68 G | **680 instr** |
| Parallel, 8 threads | 33.0 G | 69.4 G | 0.91 G | 910 |
| Parallel, 1 thread | 32.8 G | 58.4 G | 0.64 G | 640 |
| G1 (16 collections) | 29.5 G | 44.8 G | 0.96 G | 960 |
| ZGC (34 collections) | 33.2 G | 43.0 G | 0.29 G | 290 |

CPU view, SerialGC: 0.3-0.6 ms per collection at depth 1 000, 6 ms at 100 000, 36 ms at 1 000 000, 103 ms at
5 000 000. HotSpot's Serial/Parallel/G1 collections decode every frame of every thread at every
collection; nothing is remembered between collections. This is the mechanism behind Kovacs' observation
that Scala degrades sharply from Nat 5M to Nat 10M [src `normalization-bench-kovacs/README.md`,
"perhaps there is some issue with deep stacks"] and confirms the hypothesis in doc 09 section 10.1.
At ordinary depths (hundreds of frames) the cost is negligible (0.3 M instructions per collection at depth
500); at 10^5 frames it is 68 M per collection. Rules: whnf, spine walks, let chains and list-shaped
recursion in infer/defeq must be loops over explicit stacks; those stacks should be `int[]` (free for the
collector) or reference arrays (scanned through the card table, only the written part); recursion that
remains gets a depth counter and the hand-up protocol (doc 05 section 2.11, doc 09 section 10.1).

## B.12 Card-table false sharing between workers [cpu `results/scale.txt`]

Four mutator threads, 1 GB nursery, SerialGC: each thread's small hot reference array (a 256-slot window
written at every step) is promoted, lands next to the other threads' arrays in the old generation, and
from then on every store dirties card bytes in a cache line shared by all four threads.

| Configuration (same work) | mutator CPU | instructions |
|---|--:|--:|
| 4 threads, 4 GB nursery (arrays stay young) | 7.1-7.4 s | 60.2 G |
| 4 threads, 1 GB nursery, Graal JIT | 13.8-19.3 s (four runs) | 66.4 G |
| same + `-XX:+UseCondCardMark`, Graal JIT | 10.7-18.0 s: no reliable effect | n.m. |
| same, C2 (`-XX:-UseJVMCICompiler`) | 11.6-18.0 s | n.m. |
| same + `-XX:+UseCondCardMark`, C2 | **5.7-6.7 s** | n.m. |

The slowdown is cache-line ping-pong, so it is invisible in the instruction count and affects only wall
clock, but it can double it. The fix that does not depend on the JIT: per-worker structures that are
written at high frequency (work stacks, argument stacks, direct-mapped caches, memo slots) are `int[]` /
`long[]` (no card mark), and per-worker reference arrays are either reallocated per declaration (stay
young) or over-allocated so that two workers' arrays are at least 32 KB apart (one card-table cache line
covers 64 * 512 B).

## B.13 What the two normalization-bench repositories add on memory

(Full treatment of both repositories: doc 09 sections 2-4.)

- Kovacs [src `normalization-bench-kovacs/README.md`, "General comments"]: "Most solutions benefit from
  throwing more memory at GC, some dramatically"; GHC and smalltt gained 3-6x from `-A1G`, "Scala and
  nodejs performance becomes dismal without free RAM", and the JVM runs needed `-Xss1000M` and degraded
  sharply on the deep Nat benchmarks. B.4 and B.11 are the instruction-level explanation: a large nursery
  removes survivor copying, and each live frame is re-scanned at every collection.
- Guest0x0 [src `normalization-bench-guest0x0/README.md`, "memorized NBE", "hash-consing term
  representation", "abstract machine variants"]: a memo slot stored *inside* the value block (variant v3)
  costs almost nothing while a separate memo cell (v1, v2) costs a constant factor (one more indirection
  per access); sharing only the leaf nodes (pre-allocated de Bruijn index/level nodes) is a small reliable
  win, full hash-consing through a generic hash table has "a significant overhead" except where sharing
  is extreme; list environments beat balanced trees and skew lists; a continuation stack whose frames are
  inlined into one ADT beats a list of frames and a big array. For RLVM: memo words live in the value
  record (doc 09 section 1.2), leaves (bvar, small literals, sorts) are pre-interned ids, and the
  hash-consing table must be the 120-instruction kind of B.5, not a library map.

---

# Part C - Parallelism under the instruction metric

## C.1 Unit of work and the invariants that keep the verdict sound and deterministic

Unit: one declaration record of the export, index `d` in file order (an inductive block with its
constructors and recursors is one unit).

- **I1 visibility.** While checking `d`, a constant resolves only if the record that introduces it has
  index `< d`; compare at every lookup and delta step, not once per declaration. (Gruszka: "*Declared
  before* is enforced at every constant lookup and delta step" [src `gruszka/docs/architecture.md:984-987`].)
- **I2 optimistic environment.** Workers read the *statements* of earlier declarations (type, value,
  recursor rules) before those declarations have been checked. This is sound because the file is accepted
  only if every declaration is; a wrong earlier declaration can only make a later check fail, loop or
  crash, hence I3 and I4.
- **I3 first failure in file order.** `firstFail` is an `AtomicInteger` updated with `min`. The verdict is
  the status of the lowest-index declaration that is not accepted; the exit code comes from that
  declaration alone. A worker never starts, and abandons at its next poll (every few thousand ticks;
  gruszka: 4096), a declaration with index `> firstFail`; declarations below it run to completion.
- **I4 totality on garbage.** Every id read is bounds-checked; every reduction loop ticks a per-declaration
  budget; depth is guarded; every `Throwable` (including `StackOverflowError` and `OutOfMemoryError`) is
  caught at the declaration boundary and becomes "declined" for that declaration. The process starts in the
  "error" state and exits 0 only after all declarations are accepted (tenet-jvm's fail-closed rule [src
  `lean-kernel-arena/checkers/tenet-jvm.yaml`]).
- **I5 kept facts only travel forward.** A fact derived while checking `d` (it depends on the environment
  prefix `< d`) may be used for `d'` only if `d < d'`: the environment only grows and definitions never
  change. Either a session only moves forward and is reset when the next declaration is not later than its
  last one, or every kept fact carries the stamp `d` and is used only when `stamp < current` [src
  `gruszka/docs/architecture.md:115-139,988-1009`]. Never kept: errors, unknown constants, budget
  exhaustion, anything computed under cancellation.
- **I6 worker-count independence.** Budgets and arena limits must not depend on the number of workers or
  on what a warm session already holds: nano-lean re-checks in a fresh session when a warm one ran out
  [src `nano-lean-v0.4.2/src/fast.rs:261-281`]; gruszka's overflow arenas have a size independent of the
  worker count "so neither does the verdict" [src `gruszka/docs/architecture.md:34-40`].

## C.2 Work distribution

What the leaders do [src; paths below are inside `gruszka/`, `nano-lean-v0.4.2/`, `sokonanoda/`]:

| Checker | Order | Claiming | Session reset | Stack | Notes |
|---|---|---|---|---|---|
| gruszka (`src/main.rs:482-517`) | grouped by the first two namespace components, groups by first appearance, file order inside | each worker owns a contiguous slice, takes chunks of 32 from its front; an idle worker takes the **back half of the largest slice**; a declaration longer than 20 ms spills the rest of its chunk | 8 MiB of tables, or the next declaration is not later than the last one | 1 GiB | contiguous slices: -5% instructions at 8 workers against round-robin chunks; 369 / 372 / 375 G for 1 / 4 / 8 workers (`README.md:230-234`) |
| nano-lean (`src/fast.rs:163-333`) | re-ordered at load so that neighbours share constants | shared atomic cursor; a claim is `max(1, left / (4 * workers))` consecutive declarations (runs shrink towards the end) | session arena above 64 MiB, or any failure | 256 MiB | import: several scanner threads on fixed chunks, one thread builds in order (`src/import/mod.rs:116-177`) |
| sokonanoda (`src/checker/tc.rs:13,133-168`) | file order | atomic cursor, chunks of 64 | per-thread bump arena | 2 GiB (`src/lib.rs:15`) | inductive blocks are checked first on one thread (`src/checker/context.rs:44-49`); default threads = min(cores, 4) |
| pestka, mathgraph, tenet-jvm | namespace groups / file / file | 4 / 2 / 1 workers | bounded caches / - / per declaration | | (yaml descriptions) |

RLVM scheduler (works unchanged for one worker):

```
order[]  : declaration indices; default grouped by namespace (first two components, groups in order of
           first appearance, file order inside); option: file order
parts[w] : [lo_w, hi_w), W contiguous slices of order[]                    // guarded by one small lock
take(w):
    if parts[w] is empty:
        v = worker with the largest remaining part; if none: return null
        mid = (|parts[v]| <= CHUNK) ? lo_v : lo_v + |parts[v]| / 2
        parts[w] = [mid, hi_v);  parts[v].hi = mid                         // steal the back half
    return the next CHUNK = 32 entries from the front of parts[w]
worker(w):                                                                  // owns Worker state, a Session
    while (chunk = take(w)) != null:
        for d in chunk:
            if d > firstFail.get(): cancelled++; continue                   // I3
            if d <= session.last: session.reset()                           // I5
            st = checkDeclaration(d)          // catches Throwable; polls firstFail every 4096 ticks
            status[d] = st                    // byte[]; one writer per index
            if st != OK: firstFail.accumulateAndGet(d, Math::min); session.reset()
            else if session.bytes() > SESSION_LIMIT: session.reset()        // 8-64 MiB in the leaders
            if elapsed > 20 ms and chunk has more: push the rest on the shared spill stack
verdict: i = smallest d with status[d] != OK (after all workers joined); exit code of status[i], else 0
```

`take` is called once per 32 declarations (about 22 000 times for mathlib), so a plain lock is fine there;
nothing else in the loop takes a lock.

## C.3 Sharing immutable data without locks

- **Export columns** are written by the loader and read by workers. If loading finishes before checking
  starts, publication is the thread start (a happens-before edge). If the loader is pipelined (C.6), it
  publishes a high-water mark `loadedDecls` with a `volatile` / `VarHandle.setRelease` write *after* the
  rows are complete, and a worker reads it (acquire) before touching rows below it. Columns must not be
  reallocated under readers: size them up front, or use a chunk directory whose slots are written before
  the mark moves.
- **Lazily computed per-constant facts** (unused-argument masks, definitional height, "is abbreviation",
  recursor rule index): one shared primitive array per fact, `0 = not computed`; any worker computes the
  same value and stores it with a plain write; a reader that sees 0 recomputes. Array elements are written
  atomically and independently (no word tearing), so the race is benign [lit: JLS 17.6]; use `int`/`byte`,
  not `long`/`double`, for racy slots. This is what gruszka does with atomics ("any worker that asks first
  computes the same value" [src `gruszka/docs/architecture.md:76-80`]).
- **Lazily materialised objects** (a decoded structure, a compiled call target): publish only objects whose
  fields are all `final` (safe under a data race, including arrays reachable through a `final` field and
  filled in the constructor) or publish through `VarHandle.setRelease` / read with `getAcquire`. A bare
  array written through a racy field can be observed with zero elements.
- **Exactly-once things** (`firstFail`, id counters shared between workers): `AtomicInteger` /
  `compareAndSet`. Nothing else needs CAS.
- **Thunks and memo slots** are worker-local in session data: plain writes. A thunk reachable from two
  workers (only through shared per-constant data) must hold a published immutable result or be forced
  before publication (doc 08 section 1.3).
- No `ThreadLocal` and no static mutable state on hot paths: every mutable structure hangs off a `Worker`
  object that is passed explicitly.

## C.4 Per-worker state

Everything a check writes belongs to one worker: value arena or heap graph, hash-consing tables, memo and
conversion caches, level tables, argument/work stacks, tick counters, the session. Shared and read-only:
export columns, name/level tables built at load. Shared and written: `status[]`, `firstFail`, the lazily
computed per-constant facts, optionally a stamped table of checked closed facts (gruszka's
`Shared.checked`; not for a first version). Caps on kept tables are per run, not per worker: gruszka
divides them by `workers / 4` so that all workers together keep what four kept
[src `gruszka/docs/architecture.md:85-92`].

## C.5 What parallelism costs in instructions

Same total work split over T mutator threads [instr `results/instr-scale.txt`; workload S2 of B.4]:

| Collector, nursery | T = 1 | T = 2 | T = 4 | T = 8 |
|---|--:|--:|--:|--:|
| Serial, 4 GB | 56.1 G | 57.2 (+1.9%) | 60.2 (+7.2%) | 60.9 (+8.6%) |
| Serial, 1 GB | 60.4 | - | 66.4 (+9.8%) | - |
| Parallel (8 GC threads), 4 GB | 57.2 | - | 61.8 (+8.0%) | 57.3 |
| Parallel (2 GC threads), 4 GB / 1 GB | - | - | 62.4 / 71.4 | - |
| G1 / ZGC | - | - | 70.6 / 78.9 | - |

For comparison, gruszka (Rust, no collector): 369 / 372 / 375 G for 1 / 4 / 8 workers (+0.8%, +1.6%).

Where the extra instructions come from, and the remedy:

1. **Survivors scale with T**: the number of collections is fixed by the allocation volume, but each one
   finds T working sets alive. Remedy: nursery proportional to T, or session data in primitive arenas
   (then the collector sees almost nothing and this term vanishes).
2. **Duplicated cache misses**: with per-worker sessions a fact found by one worker is found again by
   another. Remedy: contiguous namespace slices (gruszka: -5% at 8 workers) and steal-the-back-half.
3. **JIT warm-up** [est]: until compiled code is installed, T workers run T times as much work in the slow
   tier. None with ahead-of-time compilation.
4. **Spinning**: idle ParallelGC workers (+33% on a deep stack, B.11), spin-waits, polling. Remedy:
   SerialGC, blocking hand-offs (parking is kernel time, which is not charged).
5. **Locks and safepoints**: every collection stops all workers; no remedy needed beyond few collections.
   No `synchronized` on shared objects in hot paths.
6. Not instruction costs, but wall-clock ones: memory bandwidth (4 threads: mutator CPU time +5% to +10%
   for the same code) and card-table false sharing (B.12).

Single-thread efficiency is kept intact by construction if the worker code is the same for T = 1 and
T > 1: no atomics, volatile reads or locks inside the evaluator; the only shared reads are plain array
loads from immutable columns; cancellation is a tick-counter test that already exists for budgets.

## C.6 Pipelining the parser with the checker

- Instructions: no gain; the same bytes are parsed and the same declarations checked. Wall clock: parse
  and check overlap, which is worth having with one checker worker (wall = max instead of sum).
- Loader thread: reads the file with plain `read` calls into one reused 1-8 MB buffer (the kernel's copy
  is not charged; no `mmap`, no `String` per line), parses records into the columns, and publishes
  `loadedDecls` after each batch (C.3).
- Hand-off in batches (every few hundred declarations or few MB), with `LockSupport.park/unpark` or a
  blocking queue of batch numbers; about a thousand hand-offs for mathlib. Never per declaration, never
  spinning.
- A worker may only take a declaration below `loadedDecls`; by I1 it needs nothing above it.
- nano-lean's parallel import (scanner threads + ordered builder, [src `nano-lean-v0.4.2/src/import/mod.rs:116-177`])
  also only buys wall clock; skip it until wall clock matters.

## C.7 Collector threads, TLABs, compiler threads on 8 vCPUs

- SerialGC collects on the VM thread while the workers are parked: no extra instructions, and with a
  collector share of a few percent the wall-clock loss at 4 workers is small.
- If ParallelGC is ever used: `-XX:ParallelGCThreads=1` or `2`. The default of 8 spins idle workers during
  serial phases (root scanning of one deep stack).
- TLABs: leave the adaptive default (a share of eden per thread; megabytes with at most 5 threads and a
  3 GB eden). Do not allocate arrays larger than a TLAB on hot paths (they take the shared slow path).
- JIT: one compiler thread (`-XX:CICompilerCount` at its minimum for the chosen tiers; Truffle
  `engine.CompilerThreads=1`); see doc 08 section 1.7. With `--arena-limits` the JVM would otherwise pick
  4 JIT threads and 8 GC threads [src `bench/README.md`].

## C.8 Truffle specifics

[lit: Truffle documentation and API; option defaults from `spikes/platform/results/engine-options-25.5.5.0.3.tsv`]

- One engine and one context for all workers; ASTs, `CallTarget`s and compiled code are shared. The
  language must allow multi-threaded access (`isThreadAccessAllowed` returning true; the runtime then
  invalidates single-threaded assumptions once, in `initializeMultiThreading`). Each worker enters the
  context once for its lifetime, not per call.
- Nodes are shared, so per-worker state cannot live in node fields: pass the `Worker` as the first call
  argument (or a `ContextThreadLocal`), and keep node state to profiles, caches of immutable data and the
  static bits of A.7. Specialisation state is the union over workers; rewrites take the AST lock and stop
  after warm-up.
- One compilation queue serves all workers: `engine.CompilerThreads` defaults to -1 (scaled with cores),
  `engine.BackgroundCompilation` to true, `engine.FirstTierCompilationThreshold` to 400. Set the thread
  count to 1; counters are per call target, so T workers reach a threshold after the same amount of work,
  not sooner.
- Cancellation and budgets use the worker's own tick counter; do not depend on safepoint actions.
- A deoptimisation or invalidation caused by one worker affects all; assumptions should therefore cover
  only load-time facts.

## C.9 Thread count for the arena run

- The ranking uses instructions (criterion 3); wall clock only matters through timeouts, which are scored
  as declines (criterion 4, behind the mathlib number) [src `lean-kernel-arena/README.md:210-218`].
- Single-thread wall clock is far from the mathlib timeout: 500 G instructions at 2-3 G instructions per
  second is 170-250 s against 4120 s [est].
- Every extra worker costs instructions on a JVM (C.5): +7% to +10% in the allocation-only upper bound,
  an unknown but positive amount in the real checker, against +0.8% in gruszka.
- `threads:` in the checker yaml is only a reservation of CPU slots by the runner [src
  `lean-kernel-arena/schemas/checker.json:67-71`]. nano-lean, gruszka, sokonanoda and pestka declare or
  use 4, mathgraph 2, tenet-jvm 1.

**Recommendation: one checker worker, one pipelined loader thread, one JIT compiler thread, SerialGC;
declare `threads: 2`.** Build the scheduler of C.2 from the start (it is 100 lines and the invariants I1-I6
are needed anyway), expose `-j N`, and measure mathlib instructions for N = 1, 2, 4 on x86: switch the
arena entry to 4 only if the increase is below 1%, which requires primitive-arena sessions. A second arena
entry with `-j 4` for wall clock is an option (several projects have two entries, e.g. `pestka` /
`pestka-wasm`, `metalean-fast` / `metalean-slow`).

---

# Prioritised design decisions for the architect

| # | Decision | Evidence | Cost |
|--:|---|---|---|
| 1 | Export DAG in flat primitive columns indexed by export id; a kernel `Term` is an `int`; allocate these arrays first | B.3: 34 G to promote + 40 G per full collection avoided, half the memory | none (simpler loader) |
| 2 | SerialGC, `-Xms8g -Xmx8g -Xmn3g`, never `System.gc()`; test matrix of B.10 on the first end-to-end run | B.4, B.10, platform spike | none |
| 3 | Value/session data must not survive collections as objects: either primitive arenas with bulk reset (doc 09 design D) or sessions much smaller than eden; decide with one spike measuring instructions | B.4: 10-16 instr per surviving byte | the main open design choice |
| 4 | whnf / defeq / infer as loops with explicit primitive work stacks; depth counter + hand-up for the rest | B.11: 680 instr per frame per collection | moderate (control-flow discipline) |
| 5 | Hash-consing and caches: open addressing over ids, direct-mapped lossy caches with generation stamps, no library maps, no weak references, no identity hashes | B.5 | small |
| 6 | Static bits at load (DEAD, STRICT, TRIVIAL, CAPTURES, SHARED) and their uses U1-U4; n-ary application and telescope entry; direct beta | A.4, A.7; gruszka/pestka "what not to compute" | one pass over the columns |
| 7 | Mutation discipline M1 only: builders and per-worker scratch stacks are the only mutable structures; thunk/memo update is the only write to a published value | A.4-A.6 | none |
| 8 | No dynamic uniqueness bit; if revisited, shadow-bit profiling first | A.4: 95 vs 52 vs 43 instr; 16 instr saved per reused cell | - |
| 9 | One checker worker by default; scheduler of C.2 with `-j N`; invariants I1-I6 enforced from day one | C.5, C.9 | small |
| 10 | Per-worker hot mutable structures are `int[]`/`long[]`; all mutable state hangs off a `Worker` passed explicitly | B.12, C.3 | none if done from the start |
| 11 | Loader: streaming `read` into a reused buffer, no `mmap`, publishes a high-water mark; pipelined with the checker | C.6, item 10 of section 0 | small |
| 12 | `long` fast path + `BigInteger`; no GMP | B.7 | none |
| 13 | Compact object headers, tenuring threshold, survivor ratio: measure, do not assume | B.2, B.4, B.10 | one afternoon of runs |
| 14 | All measurements in instructions (`bench/arena-docker.sh --arena-limits bench/measure.sh`), never CPU time: the two disagree by up to 2x in both directions here (flag append: +15% time, +83% instructions; false sharing: +150% time, +10% instructions) | A.4, B.12 | - |

## Risks

- Every number here is from synthetic programs on aarch64 with the Graal JIT in steady state; the arena
  runs x86-64, and mathlib checking is mostly cold code. Ratios between collectors and representations
  should carry over; absolute instructions per operation will not.
- Whole-process counts include JIT compilation; slopes were used where it mattered (A.4), not everywhere.
- The survivor cost range (10-16 instr/byte) comes from three different object graphs; the real value
  graph may sit outside it.
- Sessions as Java objects are the worst case for a generational collector and the leaders' most important
  trick at once; choosing wrongly between objects and arenas (decision 3) is the largest performance risk
  in this document.
- An int-arena value heap forgoes Truffle's partial-evaluation benefits for values (nothing to virtualise)
  and needs manual bounds/lifetime discipline: a stale id is a soundness bug of the same class as an
  unsound in-place write (gruszka poisons id spaces in tests to catch it).
- The optimistic-environment argument (I2) relies on I3-I4 being implemented exactly; a swallowed exception
  or a cancelled declaration recorded as accepted would be a wrongly accepted proof.
- `UseCompactObjectHeaders` and the tenuring flags interact with the JIT and GC version; re-measure after
  any toolchain change.

## Open questions

- Real allocation volume and live working-set size per declaration and per session on mathlib (decides
  nursery size and decision 3). Needs the first working evaluator.
- How many NDJSON bytes per expression record does a mathlib export have (sizing of flat columns), and
  how large is the file the arena provides?
- Does a native-image (SubstrateVM) build change the layout and collector findings (its serial collector
  and object header differ from HotSpot's)? Not examined here; see the platform spike.
- Why do five parallel columns beat one interleaved `int[]` in CPU time (B.3)? Irrelevant for the ranking,
  relevant for wall clock.
- Is ParallelGC with one thread and no full collections better than SerialGC on the real workload (it was
  2% better on survivors here)?
- Instruction overhead of 2 and 4 workers on the real checker with primitive-arena sessions; and whether a
  shared stamped fact table (gruszka's `Shared.checked`) pays for its synchronisation.
- Does the Graal JIT in this build implement conditional card marking at all, and is there another switch
  for it?
