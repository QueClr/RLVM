# rlvm-admit

Declaration admission of RLVM (work package D1, `docs/design/ARCHITECTURE.md` section 4): the structural
checks every declaration passes before an engine sees it, the environment pass that admits inductive
blocks and the quotient package and writes all derived facts, the per-unit flow a worker runs, the prelude
guards and the policies. Package `rlvm.admit`; depends on `rlvm-store`, `rlvm-api`, `rlvm-inductive` and
on no engine, loader or driver (test scope only: see "Tests").

## Public API

| Class | What it does |
|---|---|
| `Policy` | the knobs of ARCHITECTURE 4.9; `Policy.arena()` = every well-typed axiom admitted, admission in dependency order (`ForwardRefs.REORDER`, see "Admission in dependency order"), unconfirmed rejections declined, L1 prelude guards on |
| `StructuralChecks` | `checkConstant(cid)`, `checkRoot(term, rank, lparams, ownKindMask)` (and the old boolean form), `distinct(nameList)`, `lastMessage()`; constants `OWN_NONE`, `OWN_TYPES`, `OWN_ALL` |
| `PreludeGuards` | `evaluate(which, cid)` -> `Specials.VALID` / `INVALID`; constructor `(Store, Terms, LevelOps)` (the 2-argument form has no level operations and fails closed) |
| `EnvBuilder` | `advanceTo(unit)`, `run()`, `cancel()`, `envUnits()`, `failedUnit()`, `failureReason()`, `failureMessage()`, `waitingForLoad()`, `setCoverage(IntSupplier)`, `Listener` |
| `Admission` | `admit(unit, session, budget)`, `lastReason()`, `lastMessage()`; constructors `(ctx, policy)` and `(ctx, policy, env)` |

Package-private: `Claims` (layout of the claims blob), `BlockOps` (seam over the final classes of
`rlvm-inductive`), `StoreAccess` (the store calls settled after the first skeleton), `ReplayState` (the
outcomes of the units the environment pass admitted in dependency order, found by a worker's `Admission`
through the store), `OfficialOrder` (a simulation of the order in which the official checker adds the
declarations of a file).

### StructuralChecks (ARCHITECTURE 4.3; R03 checklist 4-8, 11, 12)

O(1) per root from the node summaries: `looseRange(root) == 0`, `lpSet(root)` within the declared universe
parameters, no `F_UNRESOLVED` and `depMax(root) < rank`. Only when the last test fails an exact walk runs
(iterative DFS with an explicit stack and a visited set, one visit per DAG node, sub-DAGs that the summary
clears are skipped). It decides every CONST, PROJ and literal node the summary could not clear:

| Finding | Result |
|---|---|
| name dropped as unsafe / partial | REJECT `UNKNOWN_CONSTANT` (tutorial 140, 141); DECLINE `FORWARD_REFERENCE` while the file is still loading (a later inductive record may make the kernel generate the name) |
| name declared nowhere in the file | REJECT `UNKNOWN_CONSTANT`; DECLINE `FORWARD_REFERENCE` while the file is still loading |
| name without a usable record that the official kernel generates itself: `Quot`, `Quot.mk`, `Quot.lift`, `Quot.ind` when the file has a quot record that the official checker replays (any name but the last three); `I.rec` / `I.rec_N` when `I` is an inductive type of the file | DECLINE `UNSUPPORTED_INPUT` (generated constants are deferred; the official checker accepts such files: mutants tutorial 127 / 128 / 131 `drop_decl`, probes `work/integration/probe/q13*`). Integrator, 2026-11-24 |
| wrong number of universe levels | REJECT `LEVEL_ARITY_MISMATCH` (bugs/constlevels) |
| constant of the unit itself whose kind is not exposed | REJECT `UNKNOWN_CONSTANT` (tutorial 014) |
| constant of a later unit | DECLINE `FORWARD_REFERENCE` (file order; in dependency-order mode the environment pass admits that unit first) |
| unbound node (`~nameId`) whose name is declared by now | DECLINE `FORWARD_REFERENCE` ("late-bound"; resolved by name in dependency-order mode) |
| literal whose prerequisite constants are not all of smaller rank | DECLINE `UNSUPPORTED_PRELUDE` / `FORWARD_REFERENCE` |

Before the roots: the name of the constant is still its own (`DUPLICATE_DECLARATION`, defence in depth
behind `NameTable.claim`), the universe parameters are distinct (`DUPLICATE_LEVEL_PARAM`, tutorial 019,
048). A reject found anywhere wins over a decline (the official checker replays dependencies first and
then checks every sub-term). `ownKindMask` says which kinds of the unit's own constants are visible:
none for def / thm / axiom / opaque and inductive headers, the block's types for constructor types.

`collectRoot(term, unit, lparams, ownKindMask, sink)` (package-private) is the same set of checks for a
unit admitted in dependency order: nothing is decided from ranks; every CONST / PROJ of the root is
resolved by constant id or by name and reported to the sink unless it names a member of the unit itself
(allowed or rejected by the mask); literals are reported too.

Guarantee to engines for a root that passed: closed; universe parameters declared; every CONST / PROJ bound
(`a > 0`) to a constant of smaller rank (or an exposed member of the unit) with the right number of levels;
literal prerequisites declared earlier. A root with an unbound node is never handed to an engine.

### EnvBuilder (ARCHITECTURE 4.4, 4.6, 4.7, 4.8)

`advanceTo(u)` processes the units `[envUnits, min(u, loadedUnits))` in order on the calling thread:

- def / thm / axiom / opaque: binds the special (if the name is one of the 34) with its guard state, moves
  the unit status `LOADED -> ENV_READY`. A record of these kinds named `Quot.mk`, `Quot.lift`, `Quot.ind`
  is rejected (`QUOT_MISMATCH`).
- quot: reserved name required (else DECLINE), `Eq` declared earlier (absent: REJECT; later: DECLINE),
  `Quot` admitted before `Quot.mk` / `lift` / `ind` (else DECLINE), `KernelOps.begin`, `QuotAdmission.admit`,
  then kind and universe-parameter names (`[u]`, `[u, v]` for lift) are re-stated, the special is bound.
- inductive: claims blob -> `BlockInput` (block order = the `all` list; constructors in the order of each
  type's `ctors` list); item consistency; distinct universe parameters; structural checks of every type
  root (`OWN_NONE`) and constructor-type root (`OWN_TYPES`); `KernelOps.begin(rank, lparams)`;
  `InductiveAdmission.generate`; `RecordCompare.compare`; every derived recursor mapped to its exported
  record by name and the scalar fields of checklist items 49, 50 re-stated; commit with the array forms of
  `DeclTable.setInductive / setConstructor / setRecursor` (rule right-hand sides are the exported shared
  terms, identical to the derived ones); claims blob dropped; specials of the members bound.
  An `inductive` record without types is decided completely here: REJECT if no inductive type can
  derive one of its constructor / recursor records, else DECLINE (a type of another record may).
- then `Store.publishEnv(u + 1)`; units decided here get `ACCEPTED` and `Listener.unitAdmitted`.

A failed unit gets `REJECTED` / `DECLINED`, `Listener.unitFailed(unit, reason, message)` (after one
`frontierAdvanced` if the frontier moved in that call), and the frontier stays there for good: every later
call returns the same value without doing anything. `KernelException` -> its reason (REJECT),
`ResourceException` -> its reason (DECLINE), `OverlayFullException` -> `BUDGET_MEMORY`,
`UnsupportedOperationException` -> `UNSUPPORTED_INPUT`, any other `RuntimeException` -> `INTERNAL_ERROR`;
`Error`s are not caught.

The pass keeps no overlay term, level or list id between two `advanceTo` calls: the owner of the session
behind `ops` may reset it between calls. `run()` = `advanceTo` to the end of a completely loaded file.

### Admission (ARCHITECTURE 4.4)

`admit(unit, session, budget)`: skipped unit -> ACCEPT; axiom / def / thm / opaque ->
`StructuralChecks.checkConstant` (a proof-block value is decoded with `Overlay.blockRoot` and its root
checked), then `Session.check(cid, budget)`; an engine outcome whose reason does not match is turned into
DECLINE `INTERNAL_ERROR`; with `allowAllAxioms = false` an accepted axiom other than `propext`,
`Quot.sound`, `Classical.choice`, `sorryAx` is declined. Inductive and quot units return the status the
environment pass recorded. Without an attached pass the unit must be below `Store.envUnits()` (else
DECLINE, never checked). With `Admission(ctx, policy, env)` the worker drives the pass lazily
(`env.advanceTo(unit + 1)`): the failed unit reports the pass's reason, later units `CANCELLED`.
`Admission` never writes the unit status.

### PreludeGuards (ARCHITECTURE 4.8, R03 section 8 level L1)

Pattern matching on stored nodes, no reduction, no engine; levels compared with `LevelOps.isEquiv`. The
matrix is in the class comment. Bodies of the accelerated functions are not validated (level L2 is future
work). With `Policy.preludeGuards = false` (level L0, official) every bound special is VALID.

### Admission in dependency order (`Policy.ForwardRefs.REORDER`, ARCHITECTURE 4.2; the arena policy)

The official checker does not check a file in file order: `Lean.Replay` puts every record into one map and
adds a declaration to the kernel after the declarations its type and value mention
(`refs/lean4-replay/src/Lean/Replay.lean`). A file whose declarations are not in declaration order - a
declaration line moved behind its users, a hand-made file - is therefore accepted officially, and several
irregular records are decided only by that replay. Such files were declined until wave 4.

**When.** The environment pass works in file order, exactly as before, until the first unit that file
order cannot decide: a unit one of whose kernel-checked roots mentions something that was not declared
when the loader wrote it (`depMax(root) > rank`: one word per root; for the value of a theorem that is a
proof block the word is `ProofBlocks.hasUnresolved(block)`, and only a block that has something unresolved
is decoded to see whether its root reaches it - wave 7; before, every proof block of a file with at most
2^20 block records was decoded here and again by the worker, and larger files were not looked at), or an
inductive / quot unit
that file order declines (`FORWARD_REFERENCE`, `UNSUPPORTED_INPUT`, `UNSUPPORTED_PRELUDE`). The whole
file must be loaded (`Store.loadComplete()`). Files in declaration order never get there: exporter output
is admitted by the same code as before (environment pass on Init: 49 - 67 ms thread CPU with the new test
per plain unit, 61 - 70 ms without, warm JVM, loaded host; `EnvPassBench`).

**What.** From that unit on the pass decides EVERY unit itself, with the judgments of its own `KernelOps`
(the reference kernel), dependencies first:

1. `collect`: the structural checks of the unit's roots (`StructuralChecks.collectRoot`: closed,
   universe parameters declared, every CONST / PROJ resolved by constant id or by name, right number of
   levels, no reference to the unit itself) and the list of the constants of other units they mention.
   Literals add the units of their prerequisites (needed by the engine, not by the official checker).
2. Every unit in that list that is not decided yet is decided first (iterative depth-first search).
3. `decide`: a unit that depends on a rejected / declined unit inherits the outcome; a cycle of mentions
   is `UNKNOWN_CONSTANT` (the official replay adds some member of the cycle while the next one is still
   pending; probes `E3_cycle3`, `F_cycle_block`); a mention of a quot record under a foreign name is
   `UNKNOWN_CONSTANT` (never a constant officially; probe `J2`). Otherwise the unit is checked in a scope
   whose rank is the smallest one above every rank in its dependency closure (`max(own rank,
   closureMax + 1)`): axiom / definition / theorem / opaque by `KernelOps.check / ensureSort / isProp /
   isDefEq` (the body of the official `add_*`), an inductive block by `rlvm-inductive` on roots whose
   unbound references were bound (`EnvBuilder.rebind`), a quot record by `QuotAdmission.admit(cid, scope)`.
4. The outcome of a plain unit is recorded in `ReplayState`; `Admission.admit` returns it without calling
   an engine (the driver's worker still writes the board, and its re-check protocol gets the same answer
   from every lane). Inductive and quot units are reported through the listener when the frontier reaches
   them, as before; facts of a unit decided early are committed early (nobody but the pass reads them:
   units in file order below the first one never mention a later unit).

**Why it is sound.** A unit is admitted only after everything in its dependency closure was admitted, by
a search that rejects cycles, so the order of admission is a valid declaration order; the engine sees
exactly the ranks below the unit's scope, and what it looks up is in the closure (or a special whose
guard state says VALID).

**The official order.** The official order between independent declarations is the order of
`Name.quickCmp` on their names (a hash order). The official verdict depends on it when a declaration
mentions a constant whose record is not replayed, so that nothing forces the declaration that generates
it to come first: a constructor or recursor record flagged unsafe, `Quot.mk` / `Quot.lift` / `Quot.ind`
(set aside by `LeanChecker`), and what only another type's header of a mutual block mentions.
`presence()` first looks for an order-independent reason: a replayed mention of the unit provides the
constant or depends on its provider (through declarations that were admitted, which therefore had
everything they mention). Where there is none, `OfficialOrder` decides: it simulates `Lean.Replay` on the
names of the whole file (no type checking: `Name.hash` = `mixHash` over MurmurHash64A of the components,
the ascending iteration of the name sets, the erasure of a block's types, the constructor pass, `Eq`
before the quotient package) and says whether the provider is added before the unit. If it is, the unit
is admitted; if not, the official checker fails there with "unknown constant" and so does RLVM
(`UNKNOWN_CONSTANT`). `crossCheck()` requires the simulation to agree with the dependency analysis for
every admitted unit (a disagreement declines). Where the simulation is not available - an inductive
record of the file whose lists are not its members, a tie of two name hashes, a file beyond 2^25 node
visits - the unit is DECLINED ("the official verdict depends on the replay order").

**Files in declaration order are subject to the official order too.** Tutorial test 130 mentions
`Quot.lift` and `Quot.mk` but not `Quot`: the official checker accepts it only because `Quot` precedes
`quotLiftReduction` in the hash order, and rejects the same file with the theorem renamed to `thm0`
(probes `h130_*`: 6 rejected, 6 accepted, in file order and reordered alike; `work/rlvm-admit/probe3`:
the last declaration of tutorial 126 - 131 renamed to 20 random names each, all 120 files in declaration
order: leanchecker rejects 16, all of them variants of 130; the rlvm-admit jar of 07:02Z accepts all 120,
this one agrees on all 120). So once a file is loaded,
is small (at most 2^14 units, 2^19 shared nodes, 2^19 block records, 2^20 node visits) and can be
order-sensitive at all (it has a `Quot.mk` / `Quot.lift` / `Quot.ind` record or a mutual block: decided
without looking at a term), the pass simulates the official order once; if a unit is added officially
before something it mentions, the pass leaves file order at that unit and rejects it there. Cost: none
for the other files; 3 ms (init-prelude), 10 - 22 ms (the 4.5 - 10 MB perf tests that carry a prelude)
of environment-thread CPU, warm. Larger files are not looked at (see "Known gaps").

Verification of the simulation against leanchecker 4.36.0: `work/rlvm-admit/probe2/order_*`, `xorder_*`
(84 files that differ only in three names - string, numeric, non-ASCII, nested, 2^64 and beyond: all 81
inside the model predicted, 3 outside it reported unavailable), `h130_*` (12 of 12 through the whole
pipeline), the 3,678 tutorial / bugs / other tests and tutorial mutants that load and that the official
checker accepts (no declaration ever reported missing), the 13 officially rejected mutants in which one
is (the official checker fails at that very declaration), `work/rlvm-admit/probe3` (120 of 120), and
the whole of Init, Std, con-leche, cedar and reachability with an unlimited budget (`OfficialOrderMain
-Drlvm.admit.orderVisits=4000000000`: 347,000 declarations of real libraries, none reported missing;
0.9 s, 1.6 s, 1.6 s, 2.0 s, 0.07 s of thread CPU - which is why admission simulates small files only).
`leanhash.py` in `probe2` is the hash in Python.

Records the official replay treats differently from "one block per `inductive` record" (exact mode only):

| Record | Official | Here |
|---|---|---|
| constructor record flagged unsafe, listed by its (safe) type | name and type are inputs of the block; the record is never compared (probe `A_unsafe_ctor_forged`) | the same: claims not compared, derived facts committed; DECLINE if its universe parameters are not the block's (the row would differ from the generated constant) |
| recursor record flagged unsafe | ignored; the kernel generates the recursor | identical to the derived one: admitted; different or not derived: DECLINE (needs a generated constant) |
| unsafe constructor record that no type lists | ignored | DECLINE |
| `all` of a type names something without a record, and no other type record lists the type | the kernel gets a placeholder type `[anonymous] : _inhabitedExprDummy` and rejects | REJECT `INDUCTIVE_INVALID`; DECLINE if the file declares `_inhabitedExprDummy` |
| a type that is in no `all` list (its own included), with a safe constructor record naming it or a safe recursor record named after it | never declared: "No such constructor / recursor" | REJECT `RECORD_MISMATCH` |
| any other `all` that is not the record's types; type records that disagree on `levelParams` / `numParams` | by replay order | DECLINE |
| a constructor name of a type without a record at all | placeholder constructor, rejected | REJECT `INDUCTIVE_INVALID` (same proviso) |
| ... that is an axiom / definition / theorem / opaque, or an inductive type | "already declared" (or a constructor whose type ends in a sort) | REJECT `DUPLICATE_DECLARATION` |
| ... that is a safe recursor record, or a safe constructor record of another type | post-check: "No such recursor" / "Invalid constructor" | REJECT `RECORD_MISMATCH` |
| ... that is a constructor record claiming this type but sitting in another `inductive` record | adopted | DECLINE |
| quot record under a foreign name, with a quot record `Quot` in the file | adds the quotient package (once), never a constant of that name (probes `J`, `J3`) | admitted with the outcome of the `Quot` record |
| `Quot.mk` / `Quot.lift` / `Quot.ind` records in a file without any quot record that makes the kernel add the package | "Could not find quotient constant" | REJECT `QUOT_MISMATCH` |
| quot record whose type differs from the canonical one and has an unbound reference | post-check mismatch | REJECT `QUOT_MISMATCH` (file order: DECLINE while the file is loading) |

### Pipelined loading

While `Store.loadComplete()` is false, a unit of the environment pass that ends with a non-reject reason
is not recorded at all (no status, no listener call, `failedUnit()` stays -1): `advanceTo` returns the
unit and `waitingForLoad()` answers true; the caller calls `advanceTo` again after the load. Under
`REORDER` the same holds for a plain unit one of whose shared roots mentions something that is not
declared yet: the pass does not move past it while the file is loading (a worker could only decline it;
after the load, dependency order admits it if the session covers the file). Rejections
issued while loading are the batch ones (a reference to a dropped unsafe / partial name is undecided
until the end of the file, because a later inductive record can make the kernel generate the name). A
nested inductive block admitted before the end of the file is followed up: a later unit that declares a
constant under the reserved root `_nested` is declined when the pass reaches it (batch: the block is).

**Coverage.** Dependency order reads and admits units BEYOND the argument of `advanceTo` (the
dependencies of the unit at the frontier; the whole file for the official order), with the session the
caller gave the pass. In a pipelined run a session's level and list view covers only the units below
`WorkerContext.coveredUnits()`, so the pass enters dependency order (and simulates the official order)
only when its session covers the whole, completely loaded file: by default if `Store.loadComplete()` was
already true when the `EnvBuilder` was created (a batch run; a pass created while the file is loading
stays in file order for good and a forward reference ends as the `FORWARD_REFERENCE` decline of the
worker), or, when the caller says what its session covers (`setCoverage(ctx::coveredUnits)`), as soon as
that is every loaded unit. As a guard against a context created just before the end of a load, the lists
the pass is about to read through the session must read the same through `Store.lists()`. (Found by the
driver's owner with the first wave-4 jar, 10:34Z: in a pipelined run with a hand-off after every unit a
reorder mutant was REJECTED - the `all` list of a later inductive record read through a stale list view;
the jar of this README declines or accepts such a file, never rejects: `testkit judge` over the
REORDER_DECL, FORGE_FLAG and SWAP_NAME mutants in that mode, 1,614 files, SOUNDNESS 0, COMPLETENESS 0.)
A new `EnvBuilder` on a store retires what an earlier pass recorded; `Admission` looks the recorded
outcomes up at every unit.

**The official order in a pipelined run.** The simulation needs the complete file, so it runs at the
frontier the pass has reached by then. If the official order rejects a unit BEHIND that frontier (it
was passed in file order while the file was loading), the pass rejects at the frontier
(`UNKNOWN_CONSTANT`, naming that unit): the file is rejected officially, whatever unit says so.

## Invariants

- The environment pass is the only writer of the side tables and of the guard states; it writes a unit's
  facts before it publishes `envUnits` past the unit.
- `envUnits` never passes a failed unit.
- Nothing exported is trusted: side-table values are the derived ones; exported terms are used only where
  `RecordCompare` found them identical and they are resolved shared terms.
- Exact or decline: a REJECT is issued only where the official checker certainly rejects; everything
  else that is not accepted is a DECLINE. The cases were probed with `leanchecker --from-export` 4.36.0
  (`work/rlvm-admit/probe/*.ndjson`).
- No recursion on term depth; no state kept across units except scratch buffers.

## Deviations from the design documents

| Design | Here | Why |
|---|---|---|
| 4.3: a root with late-bound names passes ("ok") | DECLINE (`FORWARD_REFERENCE`, or `UNSUPPORTED_INPUT` for a member of the unit itself) | engines and `rlvm-inductive` are promised bound nodes only. Since `rlvm-load` writes a record as a shared node only when a root of its unit reaches it and binds promoted records after the referencing unit claimed its names, an unbound node in a root means a genuine forward reference, an unknown name or a dropped name; the four hand-written arena files that used to produce late-bound roots are bound now |
| 4.2: `ForwardRefs.DECLINE` rejects a cycle | only the self-reference is rejected; any other forward reference declines | detecting a cycle needs a dependency walk over later units; a decline is never wrong (official: accepts acyclic forward references, rejects cycles; probes `d_forward`, `e_cycle`). `REORDER` rejects every cycle |
| 4.2: `REORDER` "takes ranks from a topological sort" | ranks stay the loader's; from the first unit that is out of order the environment pass admits every unit itself in dependency order, in a scope above the unit's dependency closure | ranks are in every node summary (`depMax`) and cannot be changed after the load; engines enforce visibility by rank, so a unit that depends on a later one cannot be checked by `Session.check` (scope = own rank). The cost is that such files are checked on one thread by the reference kernel |
| 4.2: generated constants "the environment thread adds them" | still declined | waits for the store's generated region (API under discussion in `work/status/rlvm-store.log`, 2026-11-24 08:52Z) and shared levels for it |
| 4.7: a quot record under another name is rejected | file order: DECLINE, which takes the pass to dependency order; there: admitted when the file has the record `Quot` (the record only makes the kernel add the package), every mention of its name `UNKNOWN_CONSTANT`; DECLINE without a record `Quot` | the official checker accepts such a file and never adds a constant of that name (probes `b_foreign_quot`, `b2_foreign_only`, `J*`) |
| 4.6 / R03 9.1: record consistency "stricter than official" | file order: type records that disagree, `all` that is not the record's types, constructor or recursor records of a type in another record, unsafe members of a safe block: DECLINE, which takes the pass to dependency order; there the official outcome where it is certain (table "Records the official replay treats differently"), DECLINE otherwise | the official checker keys records by name and accepts a constructor record in another record than its type (probe `j_cross_item_ctor`) |
| 4.6: generated constants for recursors the export does not list | DECLINE `UNSUPPORTED_INPUT`; REJECT `DUPLICATE_DECLARATION` if another declaration already has the name | generated region deferred (RECONCILIATION section 3); official accepts the missing record (probe `k_no_rec_record`) |
| 4.6: derived lists through `IntLists` | array forms of the `DeclTable` setters | the base list store is frozen after the load (critic C1.13) |
| 4.4: quot "names are the four reserved ones" | in addition: def / thm / axiom / opaque named `Quot.mk`, `Quot.lift`, `Quot.ind` are rejected; a dropped (unsafe / partial) record named `Quot`, `Quot.mk`, `Quot.lift`, `Quot.ind` is rejected at the first unit processed (the loader rejects it earlier); a quot record `Quot.mk/lift/ind` without an earlier quot record `Quot` is declined in file order and admitted after `Quot` in dependency order; without any quot record that makes the kernel add the package it is rejected | the official quotient post-check (probes `a_def_quot_mk`, `n_unsafe_quot_mk`, `m_only_mk_lift_ind`, `w_quot_order`) |
| checklist 49-51 belong to `RecordCompare` | the scalar fields are re-stated here after it, every exported recursor must have a derived twin, constructor records no type lists are decided here (REJECT, or DECLINE if `induct` names an inductive type of another record) | fail closed; the exact-or-decline rule for records outside their item |
| 4.8: guard table | adds guards for `Eq` / `Eq.refl` (informational) and compares universe levels with `isEquiv` | cheap; `Sort (max 1 1)` is still the standard `Type` |
| unit status: "any worker" | the environment pass writes `ENV_READY` for worker-checked units and the final status of the units it decides (quot, inductive); `Admission` writes none | one writer per unit; the driver owns the status of worker-checked units |

## Deferred

- `EnvBuilder.run()` while the file is still loading (pipelined mode): `UnsupportedOperationException`
  (`advanceTo` works at any time; see "Pipelined loading").
- `Policy.trustMatchingRecursors`: no effect (needs an entry point in `rlvm-inductive`).
- Generated constants, late names: declined as described above.
- The official order of large files (see "Known gaps").

## Wave 7 (2026-11-24): cost with the machine as engine, causes of declines

**What admission costs.** `perf record` (4 kHz; mathlib 1 kHz, 8 workers) of the symbolised CE native
image `-O3` in the arena-shaped container, `-X engine=machine`, one worker, jars of 11:57Z (the state
before this wave; `work/admit7/perf/*-base.byclass.txt`, `*-base.syms.txt`; scripts `work/admit7/perf/`):

@@SHARE@@

So the environment pass and the per-unit admission together are about half a percent of the process: they
are not what the rest of the pipeline costs (reader, loader, proof-block decoding: 13 - 21 %). Thread CPU
time of the whole environment pass (JVM, `EnvPassBench`, real loader + `rlvm-inductive` + reference
kernel, warm, driven one unit per call as the driver does, session reset at 16 MiB):

@@ENVPASS@@

**What was removed.**

1. *Derived terms of a block are given back at once* (`EnvBuilder.blockUnit`: `Overlay.mark()` before
   `generate`, and after the commit `KernelOps.begin(MAX, [])` + `Overlay.resetTo(mark)`, the protocol
   `checkPlain` already used for a declaration). The rows hold exported shared terms and numbers, so
   nothing derived is needed afterwards; without the release the derived terms of all blocks stayed in
   the environment session until the driver reset it at its cap (16 MiB), the hash-consing index grew
   with them and every `mk*` probe of the next block missed the cache (owner's note 10). This is the
   -25 % to -35 % of the table above. `-Drlvm.admit.releaseBlocks=false` restores the old behaviour for
   measurements.
2. *No second decoding of proof blocks.* The test "does a root of this plain unit mention something
   undeclared" decoded the proof block of every theorem in files with at most 2^20 block records (all
   the arena's small tests, the Cedar slice), threw the nodes away, and the worker decoded the block
   again. It is one word of the block's row now (`ProofBlocks.hasUnresolved`, rlvm-store 11:16Z; exact
   when false; a block with something unresolved is still decoded to see whether the ROOT reaches it).
   The size limit is gone with it (Known gaps).
3. *The official-order simulation holds one decoded block at a time* (`OfficialOrder.blockMentions`:
   mark / `resetTo` per block) instead of every proof block of the file until its end (160 MB for Init).

Not removed, and why: the regeneration of recursors and the comparison with the exported records are the
check itself (`RecordCompare` is 13 % of the pass, the official `check_recursors` 10 %): comparing "by
id" needs the derived term to BE the exported node, and the overlay's hash-consing does not cover
exported nodes (no index over the export exists; building one would cost more than the 0.07 % the
comparison takes). The root checks of `StructuralChecks` were O(1) already (0.03 - 0.04 % of the process).

**Statistics.** `EnvBuilder.blockNanos()` / `blockUnits()`: wall-clock time and number of the inductive
and quot units decided in file order (always counted: two clock reads per such unit; for the driver's
statistics file). `-Drlvm.admit.stats=true`: the pass and each worker's `Admission` time themselves and
print one line each to standard error at the end of the file
(`rlvm-admit-stats: environment pass N units in T us (inductive / quot units: B in Tb us; file order)`,
`rlvm-admit-stats: structural checks of this worker: N units in T us`).

**Every decline has a cause.** A `ResourceException` of `rlvm-inductive` used to be reported as "gave
up: UNSUPPORTED_INPUT" (14 of the 20 non-metadata declines of the tutorial mutant corpus). The message
now carries the cause `rlvm-inductive` states (`BlockOps.declineDetail()`), or, for an exception of the
kernel operations, what the reason means for the environment session. The 20 by cause
(`work/admit7/campaign-tutorial-1.txt`): 8 "the inductive block eliminates into every universe but the
export has no recursor record ... (generated constants are deferred)", 2 "a recursor record flagged unsafe
names no recursor of the block", 4 "the type of 'Quot.ind' mentions 'Quot.mk', for which the file has no
usable record", 3 "constant 'Quot.ind' / 'Quot.lift' has no usable record, but the official kernel
generates it", 1 quot record under a foreign name without `Quot`, 2 Nat literals without the constants
behind them. Same counts as before (SOUNDNESS 0, COMPLETENESS 0, agree 11,847, declined 413).

**Generated constants: still declined.** The store's region (`GeneratedBuilder`) and the bindings'
`isSharedLevel` / `isSharedList` exist; what is missing is (1) a place for the level `u` of a generated
recursor and its lists after the level base is frozen (request to the steward in `requests.log`,
14:0xZ, with a proposal: 10 of the 18 declines need it) and (2) the calls from here for the quotient
constants (7 + 1 declines), which need the dependency analysis and a probe set against leanchecker.

## Tests (`$MVN -o -q -pl rlvm-admit test`: 86 tests, 85 run, 1 skipped)

| Suite | What |
|---|---|
| `StructuralChecksTest` (23) | every row of the table of 4.3 on hand-built stores (real `rlvm-store`, real level bindings): fast path, exact walk, own-unit kind masks, projections, literals, proof-block values, 200,000-deep and 2^60-path terms |
| `PreludeGuardsTest` (8) | the guard matrix: the standard prelude is VALID, 25 single deviations are INVALID exactly where expected |
| `EnvBuilderTest` (28) | fake `KernelOps` (every judgment throws), scripted `rlvm-inductive`, policy `ForwardRefs.DECLINE` (file order): frontier and statuses, commit and derived flags, `BlockInput` order, structural rejections inside blocks, item consistency, orphan records, scalar re-statement, quot pre-checks, reserved names, guard states, cancel / run, a decline while the file is loading (`waitingForLoad`), dependency order only with a session that covers the file (`setCoverage`), no move past an out-of-order unit while the file is loading |
| `ReplayTest` (4) | admission in dependency order through the real loader, `rlvm-inductive` and the reference kernel on 43 exports (`src/test/resources/replay`: 24 hand-made probes from `work/rlvm-admit/probe2` and `probe3`, 19 mutants of tutorial tests), each with the verdict of leanchecker 4.36.0: forward references, cycles, unsafe member records, inconsistent `all` / constructor lists, foreign quot records, files the official hash order decides (in declaration order too); RLVM gives the official verdict (34 files) or declines (9, pinned); once with the worker driving the pass, once with a separate `Admission` that finds the outcomes through the store (the driver's shape); in file order (`ForwardRefs.DECLINE`) the same files are declined or agree, except the two in declaration order that the official order rejects; the official order rejecting a unit behind the frontier (a pipelined run) |
| `OfficialOrderTest` (3) | the hash of the Lean runtime on known values; the simulated official order on 22 exports that differ only in names (`src/test/resources/order`), against the recorded verdicts of leanchecker 4.36.0; files outside the model are reported unavailable |
| `AdmissionTest` (8) | fake `Session`: structural failures never reach the engine, engine outcomes pass through, lazy environment pass, axiom policy |
| `ClaimsLayoutTest` (3) | the private copy of the claims layout equals `rlvm.load.BlockClaims`; shape validation |
| `ArenaStructuralTest` (5) | real loader, no engine, no `rlvm-inductive`: the 9 structural reject tests are rejected with their reason, 6 name clashes are rejected by the loader, no structural reject or decline anywhere in the 138 accept tests, in the other 77 reject tests and in `init-prelude` |
| `ArenaPipelineTest` (4, perf group skipped unless `-Drlvm.admit.perf=true`) | integration without the driver: real loader, real `rlvm-inductive`, reference kernel (`rlvm-ref`), one worker, the environment pass driven lazily by `Admission`, the session reset between units at 1 MiB: tutorial 141 / 141, bugs 34 / 34, other 10 / 10 with the expected verdict; reports in `work/rlvm-admit/pipeline-report-*.txt` |

`rlvm-load` and `rlvm-ref` are test-scope dependencies only. Development aids in the test tree (no
guards): `ArenaPipelineMain` (the same flow on any export), `MutantCampaignMain` (a mutant corpus of
`rlvm-testkit` through that flow in one JVM, compared with the official verdicts of its index: the
11,605 + 655 tutorial mutants in 10 - 20 s), `ReplayProbeMain` (which exports leave file order),
`EnvPassBench` (thread CPU time of the environment pass), `OfficialOrderMain` (the simulated official
order of any export).

## Measurements (host JVM, GraalVM CE 25, `-XX:+UseSerialGC`, not the arena's Docker set-up)

`java -Xss900m -Xmx20g -XX:+UseSerialGC -Drlvm.admit.sessionBytes=16777216 -cp $(cat work/rlvm-admit/cp.txt)
rlvm.admit.ArenaPipelineMain FILE` under `/usr/bin/time` (real loader, `rlvm-inductive`, reference kernel,
one worker, session reset at 16 MiB; jars of 2026-11-24 05:57Z):

| Export | Verdict | Units | Wall | CPU (user + sys) | Max RSS |
|---|---|--:|--:|--:|--:|
| `init-prelude.ndjson` | accept | 1,840 | 0.52 s | 2.88 + 0.20 s | 331 MB |
| `init.ndjson` | accept | 58,547 | 18.85 s | 27.23 + 0.68 s | 926 MB |
| `std.ndjson` | accept | 101,146 | 33.42 s | 42.46 + 0.58 s | 1,053 MB |

Phase split on `init.ndjson` (`-Drlvm.admit.split=true`): load 0.74 s, the whole environment pass 0.12 s
(58,547 units, 609 inductive / quot units, including `rlvm-inductive` and its reference-kernel judgments),
checks by the reference kernel 18.2 s. Admission itself is not a cost centre.

Wave 4 (2026-11-24, host with a load average above 100, so only thread CPU time is quoted; `EnvPassBench`,
4 - 8 repetitions in one JVM, the last ones): the environment pass on `init.ndjson` takes 49 - 67 ms of
thread CPU with the wave-4 code (one extra word read per root to notice a file that is not in declaration
order) against 61 - 70 ms with the jar of 07:02Z, i.e. no measurable difference; Init, Std, cedar, cslib,
con-leche, reachability, navier-stokes-euler and mathlib never leave file order (`ReplayProbeMain`) and
are too large for the official-order simulation, so their admission is the wave-2 code path. Small files
that can be order-sensitive pay the simulation once: init-prelude 11 - 13 ms against 8 - 9 ms warm
(19 - 21 ms extra in a cold JVM), `perf/grind-ring-5` (10 MB) 31 - 34 ms against 9 - 10 ms warm (52 - 55 ms
extra cold), `perf/ite-taken-branch` 14 - 18 ms against 4 - 5 ms (31 - 43 ms extra cold), tutorial 130
2.5 - 3 ms cold; 16 of the 230 published tests have quot records or a mutual block, the others pay
nothing. Whole runs through the JVM launcher of `work/dist` (10:34Z jars, 8 workers, reference engine,
batch, GNU time, host load average 70 - 100; CPU = user + sys, then wall, peak RSS): Init with this jar
22.3 / 26.0 / 27.7 s CPU, 7.1 / 10.4 / 8.9 s wall, 1.40 - 1.49 GB, against 24.7 / 24.8 / 29.2 s CPU,
7.7 / 9.3 / 21.4 s wall, 1.40 - 1.45 GB with the jar of 07:02Z in front of the same class path (three
alternating runs: no difference beyond the noise of the loaded host); reachability 13.7 s CPU / 16.8 s /
2.91 GB, cedar 51.9 s CPU / 22.2 s / 1.86 GB, con-leche 81.7 s CPU / 72.8 s / 7.88 GB, all accepted.
Not measured in the arena-shaped container and no instruction counts (other owners' gates held the
machine during this session).

## Known gaps

- THE OFFICIAL REPLAY ORDER is simulated only for small files (limits above). A LARGE file in
  declaration order in which a declaration mentions `Quot.mk` / `Quot.lift` / `Quot.ind` or a mutual
  block's type without (transitively) mentioning what provides it is admitted in file order, as before
  wave 4; if the official hash order adds that declaration first, RLVM accepts a file the official
  checker rejects (the file is a valid environment either way). The exporter's own output is accepted
  officially, so this concerns hand-made files of more than 2^14 units only.
- The prerequisites of literals are not part of that test: the official kernel converts a literal by
  name without looking the constants up, and whether a conversion's result is then inferred or unfolded
  (where a missing `String.ofList`, say, would stop the official kernel) is not visible to admission.
- Dependency-order mode is one thread and the reference kernel, through `KernelOps`: no deep-stack
  retry (a stack overflow declines the unit and every later one), no cold re-check with a larger
  budget, the kernel's messages are the bare reason. It is meant for hand-made files.
- A LARGE file that is out of order is admitted in dependency order since wave 7 (the proof-block flag
  removed the 2^20-record limit; two reorder mutants of `init.ndjson`, officially accepted, are accepted,
  one of them declined before): from its first out-of-order unit on, every unit is checked by the
  environment pass - one thread, the reference kernel. The official-order simulation of such a file has a
  budget of 2^25 node visits; beyond it a unit whose official verdict depends on the replay order is
  declined.
- Generated constants (a recursor or quot constant without a record), an unsafe member record that
  differs from the constant the kernel generates, an unsafe constructor record that no type lists,
  safe member records of an unsafe inductive record (declined by the loader; request in
  `requests.log`): declined.
- The prelude guards read a special constant's type as stored; they resolve unbound references by name,
  but a guard that needs facts of a unit decided later (none today) would answer INVALID.
- Not exercised by a test of this module: `EnvBuilder` on a dedicated environment thread next to several
  workers (checked end to end through the launcher with 1 and 4 threads); blocks with more than 64
  derived recursors (declined); exports of other Lean versions.
- The reason of a rejection raised by `rlvm-inductive` is reported as the bare reason name
  (`KernelException` carries terms, not text).
- `Policy.unconfirmedReject` is the driver's knob; nothing here reads it.
