# rlvm-ref - the reference kernel

Package `rlvm.ref`. A straight port of the official Lean 4 type checker (v4.36.0,
`refs/lean4/src/kernel/type_checker.cpp`, `inductive.h`, `quot.h`, `instantiate.cpp`, `local_ctx.cpp`,
`environment.cpp`; specified in `docs/research/02-kernel-typechecker.md`) over term ids of `rlvm-store`.
It is a complete engine: the judgments of inductive and quotient admission, the confirmation of every
rejection, the oracle of the differential tests, and the primary engine until the machine exists.

## What is implemented

| Official function | Here (`RefKernel`) |
|---|---|
| `infer_type_core`, `infer_constant / lambda / pi / app / let / proj / lit`, `check`, `ensure_sort_core`, `ensure_pi_core`, `is_prop`, `check_level` | `inferCore` and `infer*`, `ensureSortCore`, `ensurePiCore`, `isPropInt`, `checkLevel` |
| `whnf_core` (beta, zeta, let variables, projections, iota, quotients; cheap-projection mode), `reduce_proj(_core)`, `reduce_recursor`, `inductive_reduce_rec`, `to_cnstr_when_K`, `to_cnstr_when_structure`, `quot_reduce_rec`, `nat_lit_to_constructor`, `string_lit_to_constructor` | `whnfCore`, `reduceProj(Core)`, `reduceRecursor`, `inductiveReduceRec`, `toCnstrWhenK`, `toCnstrWhenStructure`, `quotReduceRec`, `natLitToConstructor`, `stringLitToConstructor` |
| `whnf`, `is_delta`, `unfold_definition(_core)`, `reduce_nat` (the 15 accelerated functions through `rlvm.api.NatOps`) | `whnfFull`, `deltaCid`, `unfoldDefinition`, `unfoldCore`, `reduceNat` |
| `is_def_eq`, `is_def_eq_core` in the official order, `quick_is_def_eq`, `is_def_eq_binding`, `is_def_eq_proof_irrel`, `lazy_delta_reduction(_step)` with hints, `is_def_eq_offset`, `lazy_delta_proj_reduction`, `is_def_eq_args`, `is_def_eq_app`, `try_eta_expansion`, `try_eta_struct`, `try_string_lit_expansion`, `is_def_eq_unit_like`, `eagerReduce` | `isDefEqInt`, `isDefEqCore`, `quick`, `isDefEqBinding`, `isDefEqProofIrrel`, `lazyDeltaReduction`, `lazyDeltaStep`, `isDefEqOffset`, `lazyDeltaProjReduction`, `isDefEqArgs`, `isDefEqApp`, `tryEtaExpansionCore`, `tryEtaStructCore`, `tryStringLitExpansionCore`, `isDefEqUnitLike` |
| the seven caches (`m_infer_type[2]`, `m_whnf_core`, `m_whnf`, `m_success`, `m_failure`, `m_unfold`) | open-addressing `int` tables (`IntIntMap`, `PairSet`, `LongIntMap`; cleared in O(entries)), cleared in `begin` |
| `add_axiom / add_definition / add_theorem / add_opaque` | `checkConstant(cid)` |

`RefEngine` / `RefEngine.RefSession` implement `rlvm.api.Engine` / `Session`; the provider
`RefEngine.Provider` ("reference", `isReference() = true`) is registered in
`META-INF/services/rlvm.api.EngineProvider`.

## Settings

| Launcher | System property | Values | Meaning |
|---|---|---|---|
| `-X levels=` | `rlvm.levels` | `official` (default), `complete` | how `is_def_eq` compares universe levels (sorts, level lists of constants): `LevelOps.isEquivOfficial`, the official kernel's test (identity of the official normal forms, sound and incomplete), or with `complete` `LevelOps.isEquiv` / `listsEquiv`, the complete relation, which accepts a superset. The default follows the project owner's decision (OWNER-NOTES item 11, "resemble official by default"): `corner-cases/imax-right-successor` and `imax-nested-absorption` are rejected, as the official checker rejects them; `-X levels=complete` accepts them. `is_prop` is `normalizes_to_zero` in both modes, as in the official kernel. See "Universe levels: official by default" below. |
| `-X ref.imports=` | `rlvm.ref.imports` | `off` (default), `on`, `verify` | the session's table of imported constant terms (below). Off by default: it took 17 % off Init's CPU time with the wave-2b store, and costs CPU time with the wave-4 store (mathlib, 8 workers: 629.7 s with it, 565.3 s without). `verify` recomputes every answer of the table and fails on a difference (tests) |
| - | `rlvm.ref.importBytes` | bytes, default 4194304 | with `ref.imports=on`: overlay a session may spend on kept imports |
| - | `rlvm.ref.importMinUses` | default 8 | with `ref.imports=on`: a term is kept once this many declarations of the session had to copy it |
| - | `rlvm.ref.deepDepth`, `rlvm.ref.chainLimit`, `rlvm.ref.deepStackMB` | | depth limit, chain limit, helper stack (unchanged) |

The two `-X` keys are declared by `RefEngine.Provider.optionKeys()` and reach the engine through
`EngineProvider.factory(Map)`; primary, strict and reference instances of a run get the same map, so
`-X levels=complete` also governs re-checks and the environment pass. A `-X` setting wins over the
system property; an unknown value ends the launcher with status 64. `RefOptions` holds the parsed
settings (`RefEngine.factory(RefOptions)`, `new RefKernel(ctx, config, options)` for tools and tests).

## Universe levels: official by default

Project owner's decision of 2026-11-24 (docs/design/OWNER-NOTES.md item 11, "resemble official by
default"): both engines compare universe levels as the official Lean kernel does, with its incomplete
algorithm, and the complete relation is an opt-in switch only. For this engine:

| | default (`-X levels=official`) | opt-in `-X levels=complete` |
|---|---|---|
| two sorts (`quick`, the official `is_def_eq(level, level)`) | `a == b` or `LevelOps.isEquivOfficial`: identity of the official normal forms (`is_equivalent`, level.cpp) | `LevelOps.isEquiv`: equal under every assignment |
| level lists of two references to one constant (`levelsEquiv`: the same-head step of lazy delta reduction and the constant case of `is_def_eq_core`) | equal length and pairwise the same test | `LevelOps.listsEquiv` |
| `is_prop` (theorem types, proof irrelevance, projections, K) | `normalizes_to_zero`, which is exact | the same |
| universe instantiation of a constant's type / value / rule | the official `instantiate` (rebuilds changed nodes through `mk_max` / `mk_imax`) | the same |
| everything else (caches, `t == s`, `Sort` and `Const` nodes) | structural, i.e. identity of ids | the same |

The setting reaches every instance of a run (primary, strict re-check, environment pass), so the
inductive and theorem checks that call this engine through `KernelOps` follow it. The machine reads
the same key since its jar of 15:22Z (`-X levels=complete` turns its switch `levels-complete` on;
before that jar `-X engine=machine -X levels=complete` left the machine official and the run
reported `MACHINE-INCOMPLETE` where the two relations differ). Two things the setting does not reach:
`rlvm-inductive` compares levels itself where `add_inductive` does (`is_equivalent` of the mutual
types' universes, `is_geq` of a constructor field's universe) and always uses the official
functions; `rlvm-admit`'s prelude guards use the complete relation (they decide no verdict of the
official kernel).

What the official relation does not identify (all answers of leanchecker v4.36.0): `imax u 1` and
`max u 1`; `(imax u (v+1))+1` and `(max u (v+1))+1`; `imax u v` and `imax (imax u v) v`;
`imax u (max v w)` and `max (imax u v) (imax u w)`. In rlvm-level's golden set the official kernel
says "different" to 1,982 of 3,280 semantically true equations.

### What changed (2026-11-24)

- `RefOptions`: `DEFAULT_LEVELS = "official"`; `-X levels=official|complete`, system property
  `rlvm.levels` as the fallback, anything else an `IllegalArgumentException` (launcher status 64).
- `RefKernel` / `RefEngine`: comments only; the comparison code (`levelEquiv`, `levelsEquiv`) is the
  one published at 10:37Z, where `official` was the opt-in.
- Tests: `LevelsTest` (default, opt-in, every way an engine is built), `LevelsGoldenTest` (new: 7,297
  official answers through `is_def_eq`), `corpus.CorpusTest.imaxCornerCasesFollowTheOfficialKernelByDefault`,
  `corpus.CorpusRunner --levels official|complete`.

### Verdicts that changed

Only where the two relations differ, and only towards the official verdict:

| test | before (default complete) | now (default official) | official checker |
|---|---|---|---|
| `either/corner-cases/imax-right-successor` | accept | **reject** (`imaxRightOne`: `Sort (imax u 1)+1` vs `Sort (max u 1)+1`) | reject |
| `either/corner-cases/imax-nested-absorption` | accept | **reject** (`imaxAbsorbRight`: `Sort (imax u v)+1` vs `Sort (imax (imax u v) v)+1`) | reject |
| level corpus (below), 1,762 of 6,300 files | accept | **reject** | reject |

With `-X levels=complete` all of them are accepted again. No published test, no mutant and no
declaration of Init, Std or cedar changes its verdict.

### Evidence (2026-11-24; private distribution = this module's jar ca1f9bd918bb + every other module as published at 14:33Z; JVM launcher, real pipeline)

Ground truth is `leanchecker --from-export` of Lean v4.36.0 (cached for the arena tests in
`work/oracle/`). Scripts, files and raw results: `work/prof/L/` (`bin/either.sh`, `bin/lbatch.sh` =
many small exports through `rlvm.main.RlvmChecker` in one JVM, `bin/levelcorpus.py`, `data/`, `levels/`).

| what | reference engine, default | `-X levels=complete` | `-X engine=machine` (machine jar 01084ca09707, default) |
|---|---|---|---|
| `imax-right-successor`, `imax-nested-absorption` | reject, reject | accept, accept | reject, reject |
| the 30 `either` tests | 28 official verdicts; the two tests on which leanchecker does not terminate (`eta-struct-major-no-whnf`, `eta-struct-major-and-no-whnf`) are declined in under a second | 26 (the two imax tests accepted) | 28 official verdicts; the two non-terminating tests ran into the time limit (600 s; one of them alone: 1,500 s), no decline |
| the 230 published tests (`scripts/gate.sh`, slice) | 230 correct = 230 official verdicts, 0 declined | - | - |
| level corpus: 6,300 generated exports, one level question each, asked through eleven parts of the checker (two sorts; level lists of a constant bare, under an application, after delta, with a phantom parameter; theorem value type; `is_prop` of a theorem type; instantiated constant type; constructor field universe; mutual types in one universe); official: 2,455 accept, 3,845 reject, 2,081 of the rejects semantically true | 6,300 official verdicts, 0 declined | 4,538 (1,762 accepted that the official checker rejects; the 319 inductive ones stay rejected, see above) | 6,300 official verdicts |
| 600 more exports where inductive admission asks the level question of this engine (`inductive T (a : Sort l1) : Type` with the constructor `mk : (a : Sort l2) -> T a`; official: 163 accept, 437 reject) | 587 official verdicts; 13 accepted that the official checker rejects, all of them the known difference below (published jar before the change: 272) | 328 | 587, the same 13 |
| the 699 `SWAP_LEVEL` mutants of the four mutant corpora (official: 25 accept, 674 reject) | 699 official verdicts | 699 | 699 |
| differential engine (`-X engine=diff -X diff.engine=machine`, test kit) on the 6,300 + 1,600 level exports | 0 difference records | 0 difference records on the two imax files with the jars published now; with the machine jar of 14:30Z, which did not read `-X levels`, 4 COMPLETENESS records per imax file (reference complete, machine official) | - |
| Init / Cedar slice (`cedar-hot-ax`) / Std / cedar, 4 workers | accept (23.9 / 9.8 / 34.6 / 44.6 s CPU) | - | accept (28.1 / 9.7 / 26.0 / 34.9 s CPU), no `MACHINE-INCOMPLETE` line |

Guards on exactly the published jar (`scripts/gate.sh --dist work/prof/L/dist-pre --corpus all -j 4`
inside `bench/slot.sh`, 14:35 - 16:03Z, log `work/prof/L/logs/gate-step2.log`): the 230 published
tests 230 correct, 0 wrong, 0 declined; mutants 17,775 in four corpora: SOUNDNESS 0, COMPLETENESS 0,
agree 16,092, declined 1,683, errors 0 (tutorial 12,260: 11,847 / 413; bugs / other / corner-cases
2,692: 2,627 / 65; prelude 847: 839 / 8; mdata 1,976: 779 / 1,197) - exactly the counts of the gate
of 12:20Z, before the change; differential self-test passed. Unit tests: 136 (135 run green, 1
opt-in).

Mathlib was not run with this jar (the session's permission check refused a 13 GB run on the
overloaded host). It was accepted with `-X levels=official` by the previous publication (12:20Z,
same comparison code), and the machine, whose level comparison is the same
`LevelOps.isEquivOfficial`, accepted all 714,712 declarations of mathlib with 0 `MACHINE-INCOMPLETE`
in M8's runs of 15:44Z and 15:57Z (also con-leche, cedar, cslib, navier-stokes-euler).

The level checks of the table were repeated on the jars other owners published meanwhile: at
15:30 - 15:41Z on a second private distribution (admit 143aff8926eb, inductive 5f8165d2ceed, driver
c0aeb3288f46, store f85c730603b8, load 26242def2701, main 6957058c0e9e, machine 904d8ff41d25, which
reads `-X levels`; `work/prof/L/logs/final-pre2.log`), and after the publication, at 16:04 - 16:16Z,
on a distribution assembled from the published jars alone (this jar, machine 1a901658969c, main
2c4a55f5a8ae; `work/prof/L/logs/final-published.log`): the same numbers in every row both times;
with `-X levels=complete` the machine and the reference engine give the same verdict on all 6,300
level exports (4,217 accept) and the differential engine reports nothing; Init / Cedar slice / Std
accepted by both engines; the 230 published tests on the published set: 230 correct, 0 declined
(`work/status/gate/slice-all-L-dist-final.md`).

### Known difference from the official kernel that involves levels (not in this module)

The official `instantiate` keeps a level node only when its operands come back pointer-equal, and
`add_inductive` applies the block's own constants with parameter objects it makes itself when it
type-checks the recursor it generated (`check_recursors` in v4.36.0). There every level node above
a parameter is rebuilt through `mk_max` / `mk_imax`, and the official kernel then compares the
rebuilt spelling with the declared one: `inductive T.{u} (a : Sort (imax u 1)) : Sort (imax u 1) |
mk : a -> T a` is rejected by leanchecker ("application type mismatch": `Sort (imax u 1)` against
`Sort (max u 1)`). RLVM's inductive admission instantiates with the identity and accepts it, with
both engines and with either level relation: 170 of 1,600 generated declarations (and the 13 of the
row above). The rule (`isEquivOfficial(L, rebuild(L))`) is pinned against 1,600 official answers in
`rlvm-level` (`OfficialRebuildGoldenTest`, README section "Pointer equality") and was reported to
the owner of `rlvm-inductive` (requests.log, 14:57Z and 15:25Z). It is not a soundness problem
(both spellings denote the same level) and no real declaration is affected: the rebuild breaks the
official equivalence of none of the level records of Init, Std, cedar, cslib and mathlib.

## Public API

```java
final class RefKernel implements rlvm.api.KernelOps {
    RefKernel(WorkerContext ctx, EngineConfig config);       // LEAN_4_36 only; settings from system properties
    RefKernel(WorkerContext ctx, EngineConfig config, RefOptions options);
    // KernelOps: begin, exposeUnitMembers, infer, check, whnf, whnfCore, isDefEq, ensureSort, ensurePi,
    //            isProp, mkLocal, terms, termOps, levels, whnfIsSyntactic() = true, steps
    void setBudget(Budget budget);     // steps (relative to now), bytes, depth = min(maxDepth, 2^21)
    void checkConstant(int cid);       // begin(rank(cid), lparams(cid)) + the official add_* body; throws
    void endDeclaration();             // forget what the declaration learned; the session's imports stay
    void promoteImports(boolean ok);   // after the caller cut the overlay back: keep the queued imports
    void reset();                      // forget everything (session reset, overlay reset)
    long tableBytes(); int maxDepthSeen(); String describe(int term);
    long[] retention(int firstIndex);  // diagnostics: what the official caches keep alive
}
final class RefEngine implements rlvm.api.Engine {
    static final EngineFactory FACTORY;                      // default settings
    static EngineFactory factory(RefOptions options);
    RefOptions options();
}
final class RefOptions { static RefOptions parse(Map<String, String> xSettings); ... }
```

Errors: `KernelException(reason)` = the official kernel raises (REJECT); `ResourceException(reason)` =
no verdict (DECLINE): `STACK_DEPTH`, `BUDGET_STEPS` (also: a reduction that cycles), `BUDGET_MEMORY`,
`CANCELLED`, `UNSUPPORTED_PRELUDE`.
`Session.check` never throws for a property of the input.

## Invariants

- **Official control flow.** Every strategy decision is the official one (step order of `is_def_eq_core`,
  which side lazy delta unfolds, when arguments are compared, when Nat literals are folded, what each cache
  stores and when it is consulted). No shortcut the official kernel lacks.
- **Canonical terms.** The official caches and every `t == s` are structural. Every term that enters the
  kernel (API arguments, types and values of constants, recursor rules, types of free variables) is first
  copied into the worker's overlay with the hash-consing constructors (`canon`, iterative, memoised per
  scope). Everything the substitution algebra builds from canonical terms is canonical again, so
  structural equality is id equality and all caches are keyed by id. Late-bound constant references
  (`~nameId`) are resolved by name during that copy. Soundness never depends on canonicity: a pair that is
  missed by the id test is decided by the rest of the procedure. Two literal nodes with the same value
  may have different ids and are compared by value (`NatOps.natEquals`, `stringEquals`).
- **Results.** `whnf`, `whnfCore` and `ensurePi` return the argument's own id when nothing changed, and a
  canonical overlay term otherwise; `whnf` output is the official term (`whnfIsSyntactic() = true`).
- **Scope.** `begin(rank, lparams)` clears all caches. A constant is visible iff its rank is below the
  scope's rank, or it belongs to the scope's own unit and its kind was exposed with `exposeUnitMembers`
  (which clears the judgment caches, as the official `add_inductive` uses a fresh checker per phase).
  Derived facts of inductive types, constructors and recursors are read from `DeclTable` on demand and
  never cached: admission must have written them before it exposes a member whose facts the kernel may
  need. The scope belongs to unit `rank - 1` (ranks are 1-based): its judgments poll
  `RunSignals.firstFailure()` every 1024 steps and end with `ResourceException(CANCELLED)` once an
  earlier unit has failed; `Session.check` uses the constant's own unit. A rank of
  `Integer.MAX_VALUE` ("everything is visible", tests and tools) is never cancelled.
- **Nothing a declaration learned survives it.** `RefSession.check` clears the kernel's caches
  (`endDeclaration`), forgets the overlay terms the declaration built (`Overlay.resetTo(mark)`) and the
  literals it computed (`LitPool.truncateTo`, then `NatOps.reset()` because the number cache of
  `NatBinding` is keyed by pool reference), so a verdict is a function of the store and the constant.
  With `-X ref.imports=on` a non-strict session additionally keeps its table of imported constant
  terms (next item), which holds copies of stored terms and no judgment.
- **Tables that fit the cache.** The kernel's caches are open-addressing `int` tables that are emptied
  in O(entries) per declaration. A table that grew beyond 2^16 slots is dropped at once; new in wave 4,
  a table that held less than 1/16 of its slots (or nothing) is replaced by one of 4 - 8 times its
  entries, so that the small declarations after a large one do not probe twelve half-megabyte tables.
- **Imports that outlive a declaration** (opt-in: `-X ref.imports=on`; `ImportCache`,
  `RefKernel.importSlow` / `promoteImports`). Every declaration needs the types, values and recursor rules of the constants it mentions as
  canonical overlay terms under the universe levels of the reference. Such a copy is a function of the
  store and the level list alone. A session counts, per `(constant, which term, level list)`, the
  declarations that had to build the copy; at the 8th, after the declaration has ended and the overlay
  was cut back, the copy is built once more, below the mark of every later declaration, and from then
  on the lookup answers. Kept copies are ordinary hash-consed nodes, so a kept id is exactly the id a
  fresh copy would get (`-X ref.imports=verify` checks that on every answer) and the kernel does the
  same steps with the table on or off (asserted by `ImportsTest`, and on Init / Std / mathlib by equal
  step counts). Not kept: terms that live in a decoded proof block (theorem values), terms with a
  late-bound reference (`F_UNRESOLVED`), anything in strict engines (`EngineConfig.strict`: re-checks
  and the environment pass), anything queued by a declaration that was not accepted. The table dies
  with `Session.reset()`; it is also dropped when the overlay turns out to have been reset or cut below
  the kept copies behind the session's back: the topmost kept node is a sentinel literal with a random
  64-bit payload that is checked at every entry into the kernel. Budget: 4 MiB of overlay per session
  (it counts in `Session.footprintBytes()`, so the driver's session reset sees it).
- **Depth.** Recursive like the original, except that the tail calls of `whnf_core` (the chain of head
  reductions; the terms whose stack frames would write `m_whnf_core` are kept in a list and written at the
  end) and of `is_def_eq_core`, and the walk down an application spine in check mode, are loops; lambda,
  Pi and let telescopes are loops as in the original. `inferCore`, `whnfCore`, `whnfFull` and
  `isDefEqCore` count depth and raise `ResourceException(STACK_DEPTH)` at `min(Budget.maxDepth, 2^21)`
  (system property `rlvm.ref.deepDepth`); a single chain of head reductions is limited to 2^22 steps. A
  `StackOverflowError` is caught at the public entry points as a backstop (caches are dropped, the
  caller must reset the worker context).
- **Deep stack.** `RefSession.check` first runs on the calling thread. If that thread's stack is too
  small for the declaration (the backstop fired), the worker context is reset and the declaration runs
  again on a helper thread with a 1 GiB stack reservation (`DeepStack`, daemon, one per session, created
  on first use, ended by `Session.close`). A verdict therefore does not depend on the caller's stack.
- **Literal extensions** use `Specials`: a special that is not declared or not visible behaves as in the
  official kernel (the extension does not fire; a Nat or String literal then has an unknown type and is
  rejected with UNKNOWN_CONSTANT); a special whose shape guard is not VALID makes the judgment decline
  with UNSUPPORTED_PRELUDE at the moment the extension would be used.
- Hot paths use only `int` ids, primitive arrays and open-addressing tables; no boxing, collections,
  lambdas or reflection.

## Deviations from the design documents

- ARCHITECTURE 5 says the reference kernel runs only on 1 GiB threads. `RefSession` additionally owns a
  deep-stack helper thread so that the engine is depth-safe whatever thread calls it (tests, tools, a
  worker with a small stack). The driver's deep threads make it idle.
- ARCHITECTURE 5 lists `eqv` "where the official code compares structurally"; canonical ids replace it
  (O(1), and the caches hit exactly where the official structural keys hit).
- Level-parameter instantiation of terms is done here on `LevelOps` (`canonInst`: the canonical copy of a
  constant's stored type, value or recursor rule and the official `instantiate_lparams` in one pass,
  cached per CONST node) instead of through `TermOps.instantiateLevelParams`, and `mk_pi` with let
  variables (`mkPiOver`, `remove_dead_let`) is written here on `TermOps.abstractFVars`.
- `unfold_definition` is always followed by `whnf_core` in the official kernel (`whnf`,
  `lazy_delta_reduction_step`). When the unfolded value is a lambda, `unfoldWhnfCore` does both without
  building the intermediate term `value a1 .. an`: the lookup and the store that the official
  `whnf_core` performs in `m_whnf_core` under that intermediate term are performed under `c a1 .. an`
  itself (`unfoldWhnfCache`), of which the intermediate term is a function. Same results, same cache
  hits, n application nodes fewer per unfolding.
- **Cycles.** The delta loops (`whnf`, `lazy_delta_reduction`, `lazy_delta_proj_reduction`) remember an
  earlier state (Brent's scheme, two comparisons per step). If a loop comes back to it, the official
  kernel does not terminate (the next state is a function of the current one); the judgment is declined
  with `BUDGET_STEPS` instead of spinning without allocating until the arena's timeout. Together with
  the depth limit and the memory budget this makes every judgment end (ARCHITECTURE 4.1, I4).
  `corner-cases/eta-struct-major-no-whnf` and `-and-no-whnf` (official: timeout) decline in 0.2 s. In
  lazy delta reduction a repeated pair could in principle be answered by the success cache on a later
  round; such a comparison is declined here, never answered wrongly.
- `is_def_eq(t, t)` returns before `is_def_eq_core` is entered (the official code answers it in
  `quick_is_def_eq`), and the predecessor literal of `is_def_eq_offset` is built only when both sides
  have one (the official code builds and frees it).
- **Types of application prefixes are built on demand.** In check mode the official `infer_app`
  instantiates the whole remaining function type once per argument and stores it as the type of that
  prefix in `m_infer_type`. `inferAppCheck` keeps the arguments that are not substituted yet pending,
  as the official infer-only branch does: an argument is compared with its own domain under the
  pending arguments, the result type is built once, and the cache entry of a prefix is a pending pair
  (remaining type, number of trailing arguments) that `forcePrefix` turns into the term the first time
  the prefix is looked up. The arguments are closed terms, so every term compared, returned or read
  from the cache is the official one; the judgments and their order are unchanged (same step counts).
- Universe levels are compared as the official kernel compares them (`LevelOps.isEquivOfficial`), so the
  engine answers "different" for some levels that are equal under every assignment; the complete
  relation is the opt-in `-X levels=complete` (section "Universe levels: official by default").
- `EngineConfig.strict` and `memoCheck` are ignored (the reference kernel is always strict; there is no
  memo to cross-check). Only `KernelProfile.LEAN_4_36` exists; `LEAN_4_37` throws
  `UnsupportedOperationException` at construction.
- `SharedFacts` is neither read nor written.

## Tests

`. ./env.sh && $MVN -o -q -pl rlvm-ref test` (136 tests, about 12 s on a quiet host):

| Class | What |
|---|---|
| `InferTest` (31) | one fixture per inference rule of R02 section 6: sorts, constants (instantiation, arity, visibility, universe parameters), lambda / Pi / let telescopes, application in check and infer-only mode (prefix types built on demand: asked for afterwards, continued by another application, pending while an error is found, function types that become a Pi only after substitution), literals, projections with every restriction, loose bound variables, free variables, `ensureSort` / `ensurePi` / `isProp` |
| `WhnfTest` (26) | beta, zeta, delta (definitions, theorems; not opaques, axioms, wrong level arity), iota on constructors and literals, extra arguments, K with and without matching indices, structure eta on majors (not for proofs), projections (stuck ones keep the original term), `Quot.lift` / `Quot.ind`, the Nat functions and their guards, cache hits |
| `DefEqTest` (25) | each step of `is_def_eq_core`: quick cases, levels, binders, literals, `Bool.true`, proof irrelevance, lazy delta, same-head arguments, opaques, Nat offsets and folding, projections, congruence, function eta, structure eta, unit-like types, string literals, `eagerReduce`, propagation of errors |
| `ScopeTest` (14) | visibility by rank, `exposeUnitMembers` (and that it drops stale normal forms), late-bound references, results that keep the argument's id, cache sharing between structurally equal terms, overlay reset, prelude guards, cyclic reductions, step / memory / depth budgets, cancellation (also in `begin` scopes, and CANCELLED wins when the memory signal is up as well) |
| `EngineTest` (13) | `Session.check` on definitions, theorems, axioms, opaques (accept and every reject reason), a 100,000-deep value (stack overflow on the caller's thread, re-run on the deep stack), depth beyond the limit (decline), budgets, session accounting, provider registration |
| `LevelsTest` (7) | the default is the official comparison (`RefOptions.DEFAULT_LEVELS`, every way an engine or kernel is built, strict engines, the system property as a fallback only) and `levels=complete` is the opt-in: the declarations of `corner-cases/imax-right-successor` and `imax-nested-absorption`, equations both relations decide, level lists of constants, through `Session.check`, through `KernelOps`, through `Provider.factory(Map)` (unknown value refused) |
| `LevelsGoldenTest` (2) | the answers of leanchecker v4.36.0 to the 4,358 `is_equivalent` and 2,939 `instantiate` questions of rlvm-level's golden set, replayed through the engine's `is_def_eq` with default settings: two sorts, the level lists of one constant (constant case and same-head lazy delta step), the instantiated type of a constant; `levels=complete` never answers "different" where the official kernel answers "equal" and differs on more than 1,000 comparisons |
| `ImportsTest` (10) | the session's import table: off unless asked for; kept copies are served (with every answer recomputed), same verdicts and same step count with the table on and off, nothing kept in strict engines, the byte budget, a rejected declaration promotes nothing, `Session.reset()` drops the table, an overlay that was reset or cut below the kept copies behind the session's back is noticed (the ids are never used), `KernelOps` judgments read but never fill the table, literals of a declaration are released when it ends |
| `TablesTest` (3) | `IntIntMap`, `LongIntMap`, `PairSet` against the library's collections through sequences of large and small declarations; a table does not stay large after a declaration that hardly used it |
| `corpus.CorpusTest` (5) | the published arena tests at kernel level (see below); the two imax corner cases rejected by default and accepted with `levels=complete`; the perf group with `-Drlvm.ref.corpus.perf=true` |

The fixtures are hand-made stores (`Fixture`, `Prelude`, the term language `Sexp`) written through the real
`StoreBuilder`, with the derived facts of inductive types set by hand. Hand-made mutants that the new
tests kill: sentinel check disabled, official mode using the complete relation, literal truncation
removed, prefix type suspended with one argument too few, prefix arguments in the wrong order, domain
compared without the pending arguments.

Test tools (not unit tests): `corpus.CorpusRunner` checks every axiom / definition / theorem / opaque of an
export with the reference engine over a store filled by `corpus.TrustingLoader`, which trusts the file's
inductive records (no admission, no structural checks, no proof blocks; types of inductive members
carry late-bound references there, so the import table keeps fewer terms than in the pipeline).
`corpus.RetentionProbe` reports, per declaration, how many overlay nodes the kernel built and how many
of them the official caches still refer to when the declaration ends. `DepthProbe` measures the stack
used per recursion level. `work/rlvm-ref/w4/` holds the measurement scripts of wave 4 (`pipe.sh`: the
pipeline with a given rlvm-ref jar; `cab.sh`: alternating measured runs in the arena-shaped container;
`sum.py`; `either.py`).

## Integration status (2026-11-24, wave 4)

Real pipeline = the launcher on the JVM with the published jars of the other modules and this module
built from the tree (`scripts/build.sh --published --jvm-only --modules rlvm-ref,rlvm-main --dist
work/rlvm-ref/dist-pre`), reference engine, batch load. For the jar of publication 2 (035f31e854d9),
with the other modules as published at 11:22Z (store 45df9b63e7af):

- `scripts/gate.sh --dist work/rlvm-ref/dist-pre --corpus all` before publication, and again after
  it on `work/dist` rebuilt from the published jars (12:00 - 12:20Z, with the admission jar
  a817cd0c9d35 that had appeared meanwhile): the 230 published arena tests 230 correct, 0 wrong, 0
  declined, 0 errors, 0 timeouts; mutants: tutorial 12,260, bugs / other / corner-cases 2,692,
  prelude 847, mdata 1,976 - SOUNDNESS 0 and COMPLETENESS 0 on all 17,775 (agree 16,092, declined
  1,683); differential self-test passed;
- the same 230 tests with `-X levels=official`: 230 correct; Init and Std accepted with it;
- the 30 `either` corner cases (`work/rlvm-ref/w4/either.py`): with `levels=complete` (default) the
  official verdict on 26 (`imax-nested-absorption` and `imax-right-successor` accepted where the official
  checker rejects; the two `eta-struct-major-*-no-whnf` tests declined where it does not terminate);
  with `-X levels=official` the official verdict on 28 (only the two non-terminating tests differ);
- all eight corpora accepted with 8 workers on the published wave-4 set (one run each, container;
  CPU / wall / peak RSS): Init, Std and mathlib (table below; mathlib also with `-X levels=official`:
  537.1 s / 142.9 s), con-leche 63.1 s / 35.7 s / 7.55 GB, reachability 14.2 s / 5.8 s / 4.19 GB, cedar
  38.7 s / 8.6 s / 1.84 GB, navier-stokes-euler 189.0 s / 35.3 s / 2.47 GB, cslib 134.7 s / 38.2 s /
  3.75 GB;
- with the jar of publication 1 and a private build that additionally recomputes every application
  type the official way: the 230 tests with `-X ref.imports=verify`, 0 differences.

### CPU time

CPU time first, then wall clock, peak RSS, instructions. Arena-shaped container (8 performance cores,
16 GB, `perf stat` through `bench/measure.py`), JVM launcher flags of the distribution, runs of the
compared jars alternating (`work/rlvm-ref/w4/cab.sh`), median [min, max]. The host was shared with 15
other agents (load average 40 - 170): absolute times are high, only the comparison counts.

**Against the published wave-4 set** (store 720f505913d7, whose hash-consing index follows its
content; driver d8bebed464b5). "Before" = the reference jar of wave 2b (9089b0a558f3) on the same set.

| Corpus, workers | Jar | Runs | CPU s | Wall s | Peak RSS | Instructions |
|---|---|--:|--:|--:|--:|--:|
| Init, 1 | before | 8 | 16.44 [15.88, 16.94] | 13.66 | 1,252 MB | 212.8 G |
| Init, 1 | now | 8 | 14.80 [14.10, 15.31] | 12.15 | 1,201 MB | 202.4 G |
| Cedar slice (`work/workloads/slices/cedar-hot.ndjson`), 1 | before | 8 | 11.85 [11.23, 12.09] | 9.25 | 1,219 MB | 144.8 G |
| Cedar slice, 1 | now | 8 | 11.32 [11.08, 12.03] | 8.54 | 1,197 MB | 143.8 G |
| Std, 1 | before | 8 | 26.67 [26.05, 27.12] | 23.54 | 1,364 MB | 335.3 G |
| Std, 1 | now | 8 | 23.30 [22.59, 23.83] | 20.43 | 1,340 MB | 314.3 G |
| mathlib, 8 | before | 1 | 633.1 | 155.8 | 13.14 GB | 6,488 G |
| mathlib, 8 | now | 3 | 549.1 [544.4, 565.3] | 132.8 [115.7, 162.0] | 13.17 GB | 5,951 G |
| mathlib, 8 | now with `-X ref.imports=on` | 3 | 585.6 [584.6, 629.7] | 125.7 | 13.19 GB | 5,751 G |

So today: -10 % CPU on Init, -4.5 % on the Cedar slice (the ranges overlap), -13 % on Std, -13 % on
mathlib. What is kept and why:

- **Application prefix types on demand** (Deviations): with the wave-2b store it took a further 12 %
  off all three corpora on top of the import table; with the wave-4 store, alone, 2 % (Init), 0 %
  (Cedar slice), 8 % (Std) (3 runs each).
- **Tables that fit the cache** (Invariants): 3 - 7 % on each of the three corpora, with the import
  table on and off (3 runs each, 6 comparisons, all in favour).
- **The import table is off by default.** It was built and measured against the wave-2b store, whose
  index stayed as large as the largest declaration of the session: there it took 17 % (Init), 13 %
  (Cedar slice), 21 % (Std) off the CPU time by itself (9 runs each) and, with prefix types on demand,
  27 % / 23 % / 31 % and 23 % on mathlib (1,043.7 -> 807.0 s, one run each). With the wave-4 store
  the index returns to its floor between declarations unless kept copies sit in it: the table still
  saves 3 - 8 % of the instructions, but in CPU time nothing measurable on Init (1 - 2 %), 1 - 5 % on
  the Cedar slice, it costs up to 3 % on Std and 7 % on mathlib (three pairs: 544 / 549 / 565 s
  without, 585 / 586 / 630 s with). `-X ref.imports=on` remains for experiments.

Tried and not kept (measured against the wave-2b store): keeping a constant again at its first use
after a session reset (same instructions, 5 - 15 % more CPU), thresholds 2 / 4 / 16 instead of 8, an
8 MiB budget. Larger session caps of the driver (`-X session-mib=64` / `256`) and an experiment that
releases the decoded proof block before the engine runs (a patched copy of one admission class) both
made the import table last longer and the run slower (Init 17.2 -> 19.6 -> 20.5 s; 17.2 -> 21.7 s):
in this kernel the CPU time follows the size of the tables that are probed per term, not the number
of instructions.

With 8 workers on the short corpora (publication 1, wave-2b store, 3 runs): Init 26.45 -> 24.74 s,
Cedar slice 19.45 -> 18.80 s, Std 43.73 -> 38.46 s; those runs last 6 - 13 s and most of their CPU
time is eight workers running code the JIT has not compiled yet.

Kernel level (`CorpusRunner`, inductive records trusted): all 138 good arena tests accepted, every bad
test whose fault a type checker can see is rejected (the 20 others are listed in `CorpusTest`);
mathlib with the published jar: 714,712 declarations accepted, none rejected or declined, in
1,603,710,091 steps, exactly the step count of the wave-2b kernel (prefix types on demand, the table
policy and the import table do not change what the kernel does), 483 s of CPU on one thread on the
loaded host (wave 2b: 681 - 713 s), largest declaration 915 MB; Init 48,788,220 steps in every
configuration.

## Memory per declaration (measured, wave 4)

`corpus.RetentionProbe` (kernel level, kernel of wave 2b): overlay nodes a declaration builds, and how
many of them the official caches (infer x2, whnf_core, whnf, unfold, success, failure) still refer to
when it ends, i.e. what a kernel with reference counting keeps as well.

| Test, declaration | Nodes built | Kept alive by the official caches | Not referenced |
|---|--:|--:|--:|
| `perf/app-lam`, `dag_app_binder` | 40.0 M | 100.0 % | 0.0 % |
| `perf/magma-list-deep-n36`, `countermodel` | 34.7 M | 92.6 % | 7.4 % |
| `perf/magma-list-pair-n21`, `countermodel` | 54.5 M | 92.1 % | 7.9 % |
| `perf/gf3-matrix-product` (whole file) | 0.45 M (plus 50,490 big literals) | 95.8 % | 3.5 % |
| con-leche, `ConLeche.Verify.whnfTelescope_ext._f` | 65.8 M | 68.0 % | 30.7 % |
| con-leche, whole file | 265.8 M | 75.1 % | 23.3 % |

So there is next to nothing to release inside a declaration on the perf tests, and what is not
referenced is scattered through the overlay, not a suffix that `Overlay.resetTo` could cut. The
kernel releases what it can: the terms and, new, the computed literals of a declaration when it ends.
Peak RSS of one declaration is 3 - 4 times the bytes in use because of how the memory is allocated,
not because of what is retained. For `app-lam` (1.28 GB of nodes, 5.33 GB RSS) the sizes add up as
follows, by calculation from the growth policies, not measured piece by piece: the node array grows
by doubling (2.15 GB at the end, another 2.15 GB of earlier arrays that a 13 GB heap has no reason to
collect) and so does the hash-consing index (0.54 + 0.54 GB); the kernel's own tables are 2 MB there
(measured) and 240 - 365 MB on the magma and con-leche declarations (measured).

| Test (pipeline, JVM launcher, 1 worker, host, GNU time) | Peak RSS before | Peak RSS now (published wave-4 set) | Bytes in use (driver account) |
|---|--:|--:|--:|
| `perf/app-lam` | 5.33 GB | 5.33 GB | 1.28 GB |
| `perf/magma-list-deep-n36` | 5.78 GB | 5.78 GB | 1.36 GB |
| `perf/magma-list-pair-n21` | 5.60 GB | 5.60 GB | 2.02 GB |
| `perf/gf3-matrix-product` | 4.16 GB | 4.16 GB | 3.33 GB (official checker: 3.65 GB RSS) |
| con-leche, 8 workers (container) | 10.24 GB | 7.55 GB (the driver of wave 4 no longer checks its heaviest unit twice) | - |

Depth: 211 bytes of stack per recursion level on HotSpot (`DepthProbe`), so a 1 GiB stack holds about 5
million levels; the limit is 2^21. `perf/unfold-before-literal(-bad)` and `perf/proj-applied-struct*` need
524,300 levels (2^19 nested additions; the official kernel runs them on its 1 GB thread stacks).

## Known gaps

- Proof-block values (`DeclTable.value < 0`, `Overlay.blockRoot`) are exercised only through the real
  pipeline (every theorem of the arena tests, Init and Std), not by a unit test of this module.
- The `StackOverflowError` backstop and the deep-stack re-run are tested on HotSpot only; the stack use
  per level in a native image has not been measured (the limit `rlvm.ref.deepDepth` may need lowering
  there, or the image a larger stack).
- Peak RSS of a heavy declaration is 3 - 4 times the bytes in use (table above). The remedy is in the
  overlay's growth policy and the launcher's heap settings, not in the kernel.
- The import table (`-X ref.imports=on`) does not pay with the wave-4 store and is off. When on, it is
  rebuilt after every session reset of the driver (13 times on Init, 292 times on mathlib with 8
  workers, where it answers 40 % of the imports; 75 % on Init); the resets come from decoded proof
  blocks, which admission's structural check leaves in the overlay below the session's mark.
- Every declaration copies the types and values of the constants it uses, and its own type and value,
  into canonical form (a fifth of the kernel's samples on Init in the profile taken with the wave-2b
  store; not profiled again with the wave-4 store), and substitution with hash-consing
  (`TermOps.instantiateRev` under delta and beta reduction, `Overlay.find`) was more than half of the
  kernel's time in the same profile.
- The measurements of wave 4 were taken on a host with a load average of 40 - 170; differences below
  about 5 % between two jars are not resolved by them.
- The check of every lazily built application type against the official sequence exists only in a
  private build (`work/rlvm-ref/w4/vsrc`), not in `-X ref.imports=verify` of the published jar.
- `KernelProfile.LEAN_4_37` is not implemented (deferred, throws at construction).
- `EngineConfig.memoCheck` turns on the verification of the import table (when that is on); it has no
  other meaning here.
