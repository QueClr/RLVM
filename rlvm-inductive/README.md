# rlvm-inductive

Admission of inductive blocks and of the quotient package: a port of
`refs/lean4/src/kernel/inductive.cpp` and `quot.cpp` (v4.36.0) as specified in
`docs/research/03-kernel-declarations.md` (R03). Work packages I1 and I2 of `ARCHITECTURE.md` 10.2.
Package `rlvm.inductive`; depends on `rlvm-store` and `rlvm-api` only (test scope: `rlvm-load`,
`rlvm-export` and `rlvm-ref`, for the integration tests).

Regenerate and compare: from the inputs of a block (universe parameters, parameter count, names and
types of the types and of their constructors) every other fact and every recursor is derived; each
exported constructor and recursor record must be identical to the derived one. Nothing exported is
trusted; comparison is structural (`TermOps.eqv`), never definitional.

## Public API

| Class | What it does |
|---|---|
| `BlockInput` | inputs of a block + the export's claims (names stay names, numbers stay `long`). Filled by `rlvm-admit`. |
| `InductiveAdmission(store, ops)` `.generate(in, out)` | the official `add_inductive`: R03 6.1 - 6.12, checklist 27 - 48 (and the name half of 51). Throws `KernelException` (reject) or `ResourceException` (decline). |
| `GeneratedBlock` | derived facts: `numNested`, `isRec`, `isReflexive`, `elimOnlyAtZero`, `k`, indices per type, fields and index per constructor, per recursor name / type / indices / rules / major's inductive type, `recLevelParams`, `recNumMotives`, `recNumMinors`; `declineReason`, `declineDetail`. Terms are overlay ids. |
| `RecordCompare(store, ops)` `.compare(in, gen)` | checklist 49 - 51; `RECORD_MISMATCH` on the first difference; then `ResourceException(gen.declineReason)` if that is set; returns the set of derived recursors that have an exported record (first 64). |
| `QuotAdmission(store, ops)` `.admit(cid)`, `.canonicalType(kind)` | R03 section 5, checklist 18 - 24. |

Calling convention (what `rlvm-admit` does): `ops.begin(rank, lparams)`, `generate`, `compare`, commit.
`generate` re-opens the scope (same rank) with the recursors' universe parameters where the official
code checks with them (`check_recursors`, the re-check of restored recursors) and leaves it so. It
never calls `exposeUnitMembers`.

Additions to the skeleton API (all additive): `BlockInput.recRuleCount` (optional; if null the rule
arrays must have exact lengths), `GeneratedBlock.recRuleCount`, `GeneratedBlock.declineReason`.
Wave 4 (all additive, null / unused = the old behaviour):

- `BlockInput.rank` is the rank of the SCOPE the block is admitted in (the unit's own rank in file
  order; larger when `rlvm-admit` admits in dependency order and the block uses declarations placed
  later in the file). The container of a nested occurrence must have a smaller rank than that.
- `BlockInput.ctorUnsafe[i]`: the constructor record is flagged unsafe inside the safe block. The
  official checker does not replay unsafe constants, so the record is never compared with the
  generated constructor; its name and type are still the block's inputs. `RecordCompare` skips its
  claims; if its universe parameters are not the block's, `declineReason` is set (the row of the
  constant would differ from the generated one).
- `BlockInput.recUnsafe[r]`: the recursor record is flagged unsafe. Officially ignored. A record that
  is identical to the derived recursor is treated like any other; one that differs, or names no
  derived recursor, is not a rejection: `declineReason` / `ResourceException(UNSUPPORTED_INPUT)`
  (its row is not the constant the kernel generates). It does not count as "an exported recursor"
  for the rejection "no exported recursor has the elimination universe first".
- `QuotAdmission.admit(cid, scopeRank)`: as `admit(cid)` with `Eq`, `Quot`, `Quot.mk` counted as
  declared when their rank is below `scopeRank`. A quot record whose type has an unbound reference
  and differs from the canonical type is `QUOT_MISMATCH` once the file is completely loaded (the
  comparison is by name; the official post-check compares whatever the order of the file) and
  `FORWARD_REFERENCE` only while it is loading.

Wave 7 (additive): **every decline of this module states its cause.** `ResourceException` (rlvm-api)
carries only the reason, so the words are kept here: `InductiveAdmission.lastDeclineDetail()`,
`RecordCompare.lastDeclineDetail()`, `QuotAdmission.lastDeclineDetail()` return the cause of the
`ResourceException` the last call of `generate` / `compare` / `admit` / `canonicalType` raised (null
if it returned or rejected, or if the exception came from the kernel operations - a budget, the stack),
and `GeneratedBlock.declineDetail` goes with `declineReason`. `rlvm-admit` puts the text into the
message of the unit ("gave up: UNSUPPORTED_INPUT (the export has no record for the recursor 'T.rec',
which the official kernel generates ...)"); before, 14 of the 20 non-metadata declines of the tutorial
mutant corpus said only "gave up: UNSUPPORTED_INPUT". No check and no outcome changed.

Conventions the skeleton left open: `BlockInput.ctorClaimInduct`, `recRuleCtorName` and the elements
of `recAll` are NAME ids; `typeCtorStart/Count` ranges must not overlap; constructor records that no
type lists come after the ranges; `GeneratedBlock.ctorIndex/ctorNumFields` are -1 for those;
`BlockInput.consistent == false` is declined.

## What is implemented

Every step of the official code, in the official order:

1. `check_no_nested_aux` (reserved root `_nested` in constants and projection structure names).
2. `check_uniform_ind_occs` (every occurrence of a block type applied to the block's levels and the
   constructor's parameter variables; a shared subterm is visited once per binder offset, as
   officially: the verdict below a binder depends on the offset even for a closed subterm).
3. `elim_nested_inductive_fn`: parameters of the first header and of every constructor must be
   syntactic Pis; pre-order replacement, function before argument, no descent into a replaced node;
   structural lookup of `(I Ds)[As := P]`; the container's whole mutual block is copied; universe
   instantiation through `TermOps.instantiateLevelParams` (simplifying, trap T3); "parameters cannot
   contain local variables"; a container constructor whose name does not extend the container's name
   is the official `check_name` failure.
4. `check_inductive_types` (check, parameter telescope from the first type with annotation-stripped
   domains, `whnf` between binders, index count, result sort, universes equivalent), `is_rec`,
   `is_reflexive`.
5. `check_constructors` (check, parameter domains def-eq, field universe `<=` result or result always
   zero, positivity with full `whnf`, valid result) and field counts.
6. `elim_only_at_universe_zero`, `init_elim_level` (`u`, `u_1`, ... first), `init_K_target`.
7. `mk_rec_infos`, `declare_recursors`, `mk_rec_rules` (each constructor field is classified once and
   the minor premise and the rule are built from that one result: R03 decision 11).
8. `check_recursors` (recursor types checked under the recursor's universe parameters; each rule
   type-preserving).
9. Nested blocks: `restore_nested` for constructor types, recursor types and rules, names
   `<first type>.rec_j`, rules of auxiliary recursors naming the container's constructors, the
   mandatory check of every `I Ds` in the final environment and the defensive re-check of everything
   restored. The restored constructor type must equal the declared one (`RECORD_MISMATCH` otherwise:
   it is what the official checker compares).

Level tests are the official ones: `is_not_zero`, `normalizes_to_zero` (both exact and complete) and
the official, incomplete `is_equivalent` / `is_geq` (`LevelOps.isEquivOfficial / isGeqOfficial`), so
that a block is rejected exactly where the official kernel rejects; the complete relations are used
only if a `LevelOps` does not implement the official ones.

`RecordCompare` and `QuotAdmission` are complete for what v1 accepts (see the declines).

## Levels the official kernel respells (finding L-F1, 2026-11-24)

The official kernel refers to the members of the block it is admitting with level-parameter objects it
makes itself: `m_levels = lparams_to_levels(m_lparams)` for `m_ind_cnsts`, `mk_constant(cnstr_name,
m_levels)` and `get_rec_levels()` in `add_inductive_fn`, `m_lvls` for the auxiliary types in
`elim_nested_inductive_fn`. The official `instantiate` (level.cpp: `replace_level_fn`, `update_succ`,
`update_max`) keeps a level node only when its operands come back POINTER-equal, and a fresh parameter
object is never pointer-equal to the one in the declared type. So the type the official type checker
infers for such a reference (`infer_constant` -> `instantiate_type_lparams`) is the declared type with
EVERY LEVEL NODE ABOVE A PARAMETER OF THE BLOCK REBUILT through `mk_succ` / `mk_max` / `mk_imax`:
`imax u 1` becomes `max u 1`, and the official `is_equivalent` (equality of official normal forms) does
not always identify the two spellings. The kernel then meets
the mismatch in its own application checks (`check_constructors` for auxiliary types, `check_recursors`
for everything, the re-check of a restored nested block) and rejects with "application type mismatch":

    inductive T.{u} (a : Sort (imax u 1)) : Sort (imax u 1) | mk : a -> T a          -- T a
    axiom F.{u} : Type   inductive T.{u} : Type | mk : F.{imax u 1} -> T             -- T.mk x

A reference WRITTEN IN THE EXPORT (a block type inside a constructor type) carries the level objects
of the declared type and gets the declared type as it is. Both spellings denote the same level, so
this is the official kernel's incompleteness, not a soundness matter; under `OWNER-NOTES` item 11
RLVM gives the official verdict.

How it is reproduced (`InductiveAdmission`, section "the official rebuild of levels"): `rebuildLevel` is
the official `instantiate(l, lparams, fresh)` (a level without a parameter of the block is kept, every
other node is rebuilt by `LevelOps.succ` / `mkMax` / `mkIMax`), `rebuildTerm` the official
`instantiate_lparams` (every `Sort` and every level list of a constant). The stand-in of a member that
ONLY the kernel refers to has the REBUILT type: constructors (`cAtom`, and `cFinal` in the final
environment of a nested block), auxiliary types (`tAtom[k]`, `k >= nUser`), the recursors the rules of a
nested block are re-checked with (`recFinalAtom`). A USER type is also referred to by the constructor
types of the export, and which type such a reference gets officially depends on the type checker at
work: every official `type_checker` has an infer cache keyed by STRUCTURAL equality, and the kernel's
own `T.{m_levels}` is structurally the export's `T.{u, v}`. While the constructor types are checked
(`check_constructors`) the kernel has made no reference of its own: declared header. While a recursor
type is checked (`check_recursors`, one type checker per recursor) the motives come first, with the
kernel's reference `t : T params indices`: the rebuilt header is cached and EVERY occurrence of `T` in
that recursor type has it, the export's references in the field types of the minor premises included.
In the final environment of a nested block one type checker checks the restored constructor types
first: declared header. So the stand-in of a user type keeps the declared header, and `checkRecursors`
checks each recursor type on a copy in which the user types the rebuild respells (and the constructor
stand-ins whose types mention them) are renamed to stand-ins of the rebuilt header. Every comparison
is then the engine's own application check - type of the argument against the binder domain of the
(rebuilt) type, the official sides - through `KernelOps.check`, so a constant whose level list differs
is still equal when it unfolds to the same term (a phantom universe parameter), as officially. With
`-X levels=complete` the engine's relation identifies the two spellings and these comparisons hold. For a block in which the rebuild changes no spelling (`lastRebuildChanged()
== false`: every one of the 17,377 blocks of Init, Std, reachability, con-leche, cedar,
navier-stokes-euler, cslib and mathlib) the stand-ins are exactly those of before and nothing else is
computed than one memoised walk over the constructor types.

Evidence, all against leanchecker v4.36.0: the 2,200 files of the finding (`work/prof/L`; 183 were
accepted here and rejected officially, all 2,200 get the official verdict now, with both engines);
`OfficialLevelRebuildTest`: 1,468 fixtures of twelve further shapes (index domain, the export's own
reference to the type inside a minor premise, constructor result against the major premise, recursive
field, second type of a mutual block, mutually recursive types, auxiliary type of a nested block and its
index domain, the final environment of a nested block, definitions with a phantom universe, K-like
predicates, the result sort alone), each with the records a kernel that keeps the declared spelling
derives, so that the official answer on the file is the kernel's answer on the block: 655 accepted, 813
rejected officially (778 of them only because of the rebuild), the same verdict here on the reference
kernel and on the test kernel, no decline.

NOT COVERED (reported, `requests.log` finding A2-F1): an export with two level RECORDS for one universe
parameter. The official reader makes two objects of them; a reference in a constructor type that uses
the other record is then "pointer-different" from the declared type like the kernel's own references,
and the official kernel rebuilds there too. Levels are interned by structure before admission sees
them; `lean4export` never writes such a file.

## Stand-ins (the one structural deviation from the design documents)

`ARCHITECTURE.md` 4.1 / `KernelOps.exposeUnitMembers` foresee that the engine sees the block's own
types, then constructors, then recursors as constants while the block is admitted. This module never
calls `exposeUnitMembers`. Every member of the block in flight - user types, auxiliary types,
constructors, recursors - is a free variable (`KernelOps.mkLocal`) whose type is the member's type;
terms handed to the engine contain only constants of smaller rank. Generated recursor types and
rules are turned into constants at the very end by renaming atoms.

Why: (a) the auxiliary types of a nested block have no constant id at all, and in the auxiliary
block the user constructors have other types than their rows (`List (T a)` replaced), so the row of
a member cannot be what the engine reads; (b) the derived facts of the block are not in the side
tables while it is admitted, so an engine reading them sees zeros; (c) it makes the module
independent of how an engine treats half-declared constants.

Why it is the official behaviour: while a block is admitted the official environment contains its
types without usable constructors, then its constructors without recursors; such a constant is an
opaque symbol of its type, exactly what a free variable is, for `infer`, `whnf` and `is_def_eq`. The
places where the official type checker would consult inductive facts of a member in flight:

- structure eta / unit-like: need `!is_rec`; in a non-recursive block no type of the block occurs in
  any binder domain or index, so no term of such a type is ever compared;
- projections on a block type: the official `infer_proj` fails (the constructor is not in the
  environment yet); here a projection whose structure is a member of the unit is rejected up front;
- iota / K: only in `check_recursors`, on `rec params Cs minors idx (ctor params fields)`; the single
  iota step is done by hand (the result is `minor fields ihs`, where the official `whnf` stops too);
- occurrences of a block type always carry exactly the block's levels (step 2), so one atom per
  type loses nothing.

Evidence: `OfficialDifferentialTest` and the corpora below.

## Declines (`ResourceException`), all narrower-than-official, never a wrong verdict

| Situation | Reason | Official |
|---|---|---|
| a derived recursor has no exported record and its name is free (generated constants are deferred with the generated region) | `UNSUPPORTED_INPUT` | accepts |
| no exported recursor at all although the block eliminates into `Sort u` (the name id of `u` is taken, verified, from an exported recursor) | `UNSUPPORTED_INPUT` | accepts |
| `BlockInput.consistent == false` | `UNSUPPORTED_INPUT` | depends on the replay order |
| a block type named `outParam`, `semiOutParam`, `optParam`, `autoParam` (the official major premise loses its type through `consume_type_annotations`) | `UNSUPPORTED_INPUT` | rejects (observed) |
| nested block in a file that has a constant, or a member of the block, with a name under `_nested` (the official auxiliary names `_nested.<J>_<n>[.c / .rec]` are checked against the environment, and the official replay order is not the file order) - after the comparison | `UNSUPPORTED_INPUT` | accepts or rejects, by replay order |
| nested block whose container arguments `Ds` mention a constructor / recursor / projection of the block (they are checked in the final environment, where those reduce; atoms cannot stand for that) - after the comparison | `UNSUPPORTED_INPUT` | accepts if well typed (observed) |
| annotation-stripped type of a recursive field does not end in a block type (official: undefined behaviour) | `UNSUPPORTED_INPUT` | undefined |
| engine with `whnfIsSyntactic() == false` and a `whnf` that changes the term at a T4 position | `UNSUPPORTED_INPUT` | - |
| quot record under a name other than the four (its row would carry a quot kind) | `UNSUPPORTED_INPUT` (`rlvm-admit` in dependency-order mode admits it itself when the file also has the record `Quot`, and rejects every mention of its name) | accepts if unused |
| constructor / recursor record flagged unsafe that differs from what the kernel generates (see `ctorUnsafe`, `recUnsafe`) | `UNSUPPORTED_INPUT` | ignores the record |
| quot record but no record named `Quot` / no `Quot.mk` record when `Quot.ind` is admitted | `UNSUPPORTED_INPUT` | accepts (generated) |
| `Eq`, `Quot` or `Quot.mk` with a rank at or above the scope of the record that needs it (`admit(cid)`: declared later in the file; `rlvm-admit` in dependency-order mode admits them first and passes a higher scope) | `FORWARD_REFERENCE` | accepts (replay by dependency) |

"After the comparison": `generate` completes with all derived facts and sets
`GeneratedBlock.declineReason`; `RecordCompare.compare` throws it only when every exported record
is identical, so a forged record is still a rejection (`bugs/nested-name-capture`).

Rejections that rest on an argument rather than on the official order (all are rejections of the
official checker too, earlier or later): (1) exported recursor records exist but none has the
elimination universe as its first universe parameter: `RECORD_MISMATCH` before recursors are built;
(2) own-unit references in a header, leftover own-unit constants in a constructor type:
`UNKNOWN_CONSTANT` by a scan of this module (the official `tc.check` fails there because the constant
is not in the environment yet); (3) an exported recursor record that names no derived recursor, or a
constructor record that no type lists: `RECORD_MISMATCH` at the end of `generate` (checklist 51;
tutorial `misnamed_rec`, `dup_rec_def2`, `bugs/orphan-rec`, `other/extra-rec`, `other/orphan-ctor`);
(4) a derived recursor without a record whose name is held by another constant:
`DUPLICATE_DECLARATION`.

## Invariants

- Derives from inputs only. Of the claims, `generate` reads the NAMES of exported recursor records
  (constant ids for the `Const` nodes of the rules; item 51) and the first universe-parameter name of
  each (name id of `u`), both only after checking that they are the derived names. `RecordCompare`
  then compares everything.
- Comparison is `TermOps.eqv` (constants by name, levels by id, binder info absent).
- Output terms contain no free variable and no loose bound variable (checked).
- No recursion on term structure: traversals (`TermTools`) and telescopes are iterative. Nothing is
  sized by `numParams` before the first header is known to have that many binders.
- One instance per thread and per store; an instance is reusable after any exception.

## Known gaps

- Generated constants (a recursor without an exported record) and generated quot constants: declined
  (deferred with the generated region and late names).
- Batch loading is assumed in two places: the scan for names under `_nested` sees the constants
  loaded so far, and the name lookup for a missing recursor record (`NameTable.findStr`) is only made
  when `Store.loadComplete()`. With pipelined loading a later declaration under `_nested` or named
  like a generated recursor must be caught by the late-name mechanism of the loader / driver.
- `RecordCompare.compare` reports presence bits for the first 64 recursors only.
- The "type-check then def-eq" fallback for non-identical records is not implemented (by design).

## Tests (`mvn -o -q -pl rlvm-inductive test`: 85 tests, 1 of them opt-in)

The answers of the official checker are STORED (`OfficialGolden`,
`src/test/resources/rlvm/inductive/official-golden.tsv`: SHA-256 of the export the test writes, exit
status, output; 1,603 answers of leanchecker v4.36.0): no checker binary is run by the tests, and a
fixture whose export changes (other derived records) FAILS until its answer is added:

    $MVN -o -q -pl rlvm-inductive test -Dtest='Official*' -Drlvm.inductive.golden.dump=$PWD/work/a2/golden-dump
    rlvm-inductive/src/test/scripts/official-verdicts.sh work/a2/golden-dump work/a2/golden-new.tsv   # one Docker container
    cat work/a2/golden-new.tsv >> rlvm-inductive/src/test/resources/rlvm/inductive/official-golden.tsv

`src/test`: `FakeKernel` (a small recursive substitution checker with beta / delta / zeta; set
`-Drlvm.inductive.kernel=ref` to run the same fixtures on `RefKernel`), `TermParser` (named-binder
notation), `Env` (a real store filled through `StoreBuilder`, the worker view of
`rlvm.api.bind.Bindings`, a miniature environment pass), `RealEnv` (export file through reader,
loader, store and `RefKernel`), `Ndjson` (writes a fixture as a `lean4export` file).

- `ReferenceOutputsTest`: R03 6.14 - Nat (also in the printed de Bruijn form), List, Eq, Acc, Prod /
  And, the mutual pair EvenT / OddT, Tree (nested through List), Rose (through Array, which finds List).
- `ElimAndExactnessTest`: elimination universe and K table of 6.8 / 6.9, flags, traps T2, T4, T5.
- `RejectionTest`: one block per official error of 6.2 - 6.6 and 6.12, and the declines.
- `RecordCompareTest`: every compared field forged in turn; unordered recursor records; orphans;
  records flagged unsafe (not compared; a difference declines, never rejects).
- `QuotAdmissionTest`: canonical types (de Bruijn forms of R03 section 5), forgeries, missing `Eq`,
  a scope above declarations that come later in the file.
- `OfficialDifferentialTest`: 86 fixtures written out as export files whose recursor records are the
  DERIVED ones, admission run once on `RefKernel` and once on `FakeKernel` (which compares levels by the
  official relation, like the engines). 47 admitted here are accepted by the official checker (so the
  derived facts, recursor types and rules are the official ones, including nested blocks, T2 / T3 / T4
  cases, `corner-cases/nested-redex-aux`, levels the kernel respells equivalently); 36 rejected here
  are rejected by the official kernel while it replays that block (8 of them finding L-F1); 3 declined
  here are reported with the official verdict (`target/official-differential-report.txt`).
- `OfficialLevelRebuildTest`: finding L-F1, 1,468 fixtures (twelve shapes over the 125 levels and 93 level
  pairs of `level-rebuild-pool.tsv`), see "Levels the official kernel respells"; report in
  `target/official-level-rebuild-report.txt`.
- `InitBlocksTest`: with the real pipeline, all 605 inductive blocks of `init.ndjson` (and the 126 of
  `init-prelude.ndjson`) regenerate identically and the quot records are canonical. Opt-in
  `-Drlvm.inductive.corpora=std.ndjson,...` for the other exports of `work/exports`.
- `ArenaInductiveTest`: the 230 published arena tests through loader and environment pass: every
  good test passes; every bad test about inductive blocks, constructor / recursor records or quot
  (36 tests, R03 section 10) is rejected by the loader or by this module.

Opt-in corpus run (2026-11-24, published jars, JVM with JIT, one thread, corpora in this order in one
process; CPU time of the calling thread in `generate` + `compare`, the reference kernel's judgments
included): std 1,046 blocks 0.10 s; reachability 185 blocks 0.01 s; con-leche 469 blocks 0.44 s;
cedar 1,956 blocks (38 nested, 112 auxiliary types) 0.12 s; navier-stokes-euler 1,614 blocks 2.3 s;
cslib 4,373 blocks (45 nested, 123 auxiliary types) 0.38 s; mathlib 7,129 blocks (14 mutual, 42
nested, 121 auxiliary types, 7,283 recursors) 1.7 s; init 605 blocks 0.02 s warm (0.15 s as the first
corpus of a process). All 17,377 blocks regenerate identically. Not measured in Docker / with
instruction counts (the module is not a process of its own).
