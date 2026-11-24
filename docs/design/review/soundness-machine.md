# Independent soundness review of the machine (package S)

2026-11-24, 13:20Z - 15:15Z. Reviewed: `rlvm-machine` as published at 12:47Z (jar `608b84b2b142`, the integrator's
final state: distribution copy `work/soundness/dist`, every run "through the launcher" below), and again
in-process against the main tree as published until 15:05Z (after M4's fix of S-F1 / S-F2 at 14:30Z and M6's fix
of S-F4 at 14:46Z). Ground truth everywhere: the official checker (`leanchecker --from-export`, Lean 4.36.0,
through `rlvm-testkit/bin/testkit verdict`), never the reference engine alone.

"Wrong ACCEPT" below means: the process accepts an export that the official checker rejects (owner note 11:
resemble official). By my analysis all five findings accept statements that are true in the declarative theory
(proof irrelevance plus congruence; a complete level relation); I found no export on which the machine proves
`False`. They are verdicts the official checker does not give, which is what the arena scores.

## 1. Verdict

Five findings; four were wrong ACCEPTs of the machine as published at 12:47Z, one of them in v0. All four were
fixed by their owners during the review and the fixes are verified below; the fifth (universe levels) is the
known project-wide difference and is still open.

State of the main tree as published by 15:16Z (machine: M4 14:30Z, M6 14:46Z / 15:07Z, M5 15:01Z, M7 15:10Z):

| Configuration | Verdict | Why |
|---|---|---|
| **v0 (no switch)** | no open finding of the machine itself; **S-F3 (levels) open** | S-F5 (wrong ACCEPT in v0: canonical free variables + pair table + eager reduction) is closed by M6's rule M6-F26: a declaration that enters eager reduction is DECLINED by the machine and checked by the reference engine (0.30 % of Init's, 0.20 % of Std's declarations apply `eagerReduce`). 329 hostile exports: official verdict or a decline by design |
| `warm,gated,expect` (candidate default of step 1) | no open finding of its own; inherits S-F3 | S-F4 (POS pairs survived a declaration) is closed by the same rule. `gated` and `expect` had no finding at any time |
| `probes` | no open finding; what is left is order-only | S-F1: B1 / B12 / B2 answered T by congruence where the official procedure is not a congruence. M4 removed them from every switch set (pass B, quick refutation, UNDEC, debt remain) |
| `facts` | no open finding; what is left in conversion is B7 | S-F2: B3 / B4 / the Prop mask of structure eta skipped positions the official procedure compares and fails on. Removed from every switch set |
| `tele`, `code`, `casetrees` (K1 roles), `inert` | no finding at any time | 61 exports built to pass the rent-to-own threshold (and `-Drlvm.machine.elabAt=1` through the launcher): case trees on Nat literals / offsets / families / enumerations, CASESON, leaves, over-application, TELE domain patterns with levels and closures. Shape recognition is structural, never by name |
| `lazyeval` (new, 14:00Z) | no finding in the matrix (section 5); argument in 4.3 | order-only by construction; reviewed lightly |
| universe levels (every configuration, and the reference engine) | **NOT the official relation (S-F3)** | both engines accept the two arena corner cases the official checker rejects; owner decision 13:40Z, M1 / rlvm-level / rlvm-ref in progress (`levels-complete` is registered as the opt-in; the default was still the complete relation in my last run, 15:16Z) |

**Which switch sets are safe to be on by default.** On the evidence of this review (section 5) and with the
fixes above: `warm,gated,expect` gives the official verdict wherever v0 does; I have no objection to it as the
default once (1) S-F3 is closed (it is a wrong ACCEPT of both engines today) and (2) the differential gates on
the four corpora and the mutant corpora have been re-run on the fixed tree - I did not run them, and M4's, M6's,
M5's and M7's publications of 14:30Z - 15:10Z changed behaviour (among other things nearly every declaration that
applies `eagerReduce` now goes to the reference engine: expect about 164 declines on Init and 203 on Std, by
design; a gate that reads `machine.declined == 0` must subtract `machine.eagerDeclines`). `probes`, `facts`,
`lazyeval` and the K1 roles show no finding as published now, but two of them were unsound four hours into a wave
that had measured them "clean on everything": I would keep them opt-in until their owners' NOTES state the
argument for what is left and the gates are green on the fixed tree. Nothing in this review argues against v0.

What the four fixed findings have in common: the machine answered from something that is true declaratively
(congruence; a pair accepted in another scope or mode), and the official `is_def_eq` is neither a congruence, nor
transitive, nor a function of the pair alone. Every future shortcut needs the question "does the OFFICIAL
procedure give this answer here", not "is this answer true".

## 2. Findings

| Id | Severity | Switch sets | Reproducer (`work/soundness/exports/`, gzip under `rlvm-machine/src/test/resources/s/`) | Owner | State |
|---|---|---|---|---|---|
| **S-F5** | wrong ACCEPT | **every set, v0 included** (jar `608b84b2b142` and the main tree until 15:07Z) | `canon-fvar-pos-across-binders-eager-bad`, `krule-memo-across-binders-eager-bad` | M6 (`Machine.enterER`, the facade), M5, M7 (callers) | CLOSED by a decline rule: M6-F26 published 15:07Z, armed by M5 (15:01Z) and M7 (15:10Z); verified in-process on the main tree of 15:16Z |
| S-F4 | wrong ACCEPT | every set with `warm` | `warm-pos-across-declarations-eager-bad`, `krule-eager-alone-bad`, `krule-memo-across-declarations-eager-bad` | M6 (sessions) | FIXED 14:46Z (verified in-process on the main tree of 15:05Z) |
| S-F1 | wrong ACCEPT | every set with `probes` | `probe-b1-rec-proof-major-bad` (+ flipped), `probe-b12-{closure,let,lam-closure}-proof-slot-bad`, `probe-b1-abbrev-proof-arg`, `probe-b1-theorem-head`, `probe-b1-rec-under-{ctor,axiom}-bad`; also the three `facts-*-noncongruent-*` files and `warm-eager-pair-alone-bad`, `*-eager-first-bad` | M4 | FIXED 14:30Z (verified in-process on the main tree of 14:44Z) |
| S-F2 | wrong ACCEPT | every set with `facts` | `facts-agreement-noncongruent-app-bad`, `-samedef-bad`, `facts-etas-prop-mask-noncongruent-bad`, `facts-unread-noncongruent-bad` | M4 | FIXED 14:30Z (verified likewise) |
| S-F3 | wrong ACCEPT (known) | every set, and the reference engine | `arena-imax-right-successor`, `arena-imax-nested-absorption` (arena class `either`) | M1 (Levels), rlvm-level, rlvm-ref | in progress (owner decision 13:40Z) |
| P1 | decline where the official checker accepts (pipeline, not the machine) | - | an inductive block without recursor records (`work/soundness/out/norec.ndjson`) | rlvm-admit | by design (docs/research/01 2.11); noted |
| P2 | decline where the official checker rejects (pipeline) | - | `str-lit-missing-prerequisites-bad`: two different string literals in an export without `String.ofList` / `Char.ofNat` | rlvm-admit | by design (UNSUPPORTED_PRELUDE) |
| P3 | decline where the official checker accepts (pipeline) | - | mutant `nat-lit-sub--BETA_WRAP-2` (`work/soundness/mut1/`): a beta-redex wrapped into the type of a declaration the literal prelude guard inspects (UNSUPPORTED_PRELUDE) | rlvm-admit | by design (prelude guard) |

### S-F5: canonical free variables + pair table + eager reduction (v0)

The official `is_def_eq` is not a function of the pair alone. (a) It has a mode: `infer_app` sets `m_eager_reduce`
while it compares the type of an argument of the form `eagerReduce _ _` with its domain; under it
`lazy_delta_reduction` folds Nat operations although a free variable is present. (b) Its caches (`m_success`, the
WHNF caches) live for one declaration and are keyed by terms whose free variables are unique per binder instance.

With `G x := And.rec (fun _ _ => 0) x` (regular), `X := Nat.add (G (And.intro ..)) 0`, `Y p := Nat.add (G p) 0`:

- plain mode, `p` a free variable: no fold; `Nat.add` and `G` compare their arguments; `And.intro ..` against `p` is
  proof irrelevance: `X = Y p` is **T**;
- eager mode: `X` folds to `0` first; `0` against `Nat.add (G p) 0` unfolds to `0` against the stuck `And.rec .. p`:
  **F** (confirmed with the official checker: `work/soundness/probe/h2`, `h3` reject, `h4` accepts).

`canon-fvar-pos-across-binders-eager-bad` is ONE theorem `And.intro (fun p => Eq.refl X) (fun p => (fun h => h)
(eagerReduce (X = X) (Eq.refl X))) : (forall p, X = Y p) /\ (forall p, X = Y p)`. Officially the second lambda has a
new free variable, the success cache has no entry for it, the eager comparison fails: REJECT. In the machine both
binders are `FVAR(And True True, 0)`, the pair has POS from the first scope, and `Conv.pairHit` answers POS also
while ER is set: ACCEPT, in v0. `krule-memo-across-binders-eager-bad` is the same through the K rule (the indices
of an opaque proof of `X = Y p`; the recursor reduces in the second scope where the official kernel leaves it stuck).
The twins `canon-fvar-pos-across-binders-plain-ok`, `warm-pos-same-declaration-eager-ok` (plain first, then eager in
ONE scope: the official success cache answers, ACCEPT) and `...-eager-first-bad` are judged correctly by v0.

Soundness condition that is broken: "a POS pair (and any memo whose value depends on a comparison) answers a query
only if the official kernel would answer T for it in the current mode and history". It holds for plain mode (the
official plain procedure is a function of the pair), it does not hold across the mode switch.

M6 adopted fix (1) below at 15:07Z in its dynamic form: `Machine.enterER()` marks the declaration and
`MachineSession.check` answers DECLINE for it in every regime; the reference engine decides (statistics
`machine.eagerDeclines`). The 11 exports of the suite that enter eager reduction carry the note `eager-decline`.
Taking these declarations back to the machine is fix (2), not started.

Fixes as proposed (sent 14:38Z and 15:06Z): eager reduction is the only mode switch of the official kernel
and occurs in 164 of Init's 55,401 and 203 of Std's 99,235 def / thm / opaque / axiom declarations (0.30 % / 0.20 %,
counted on the exports). (1) Exact and cheapest: at `Machine.enterER()`, if this declaration has already compared
anything in plain mode, give up with a DECLINE (the machine cannot know whether the official success cache holds
the pair: same scope yes, other scope no); the reference engine decides. In practice nearly every declaration
that applies `eagerReduce` is then declined. (2) Keep such declarations on the machine: canonical variables off
for them (every OPEN a fresh identity), or stamps on the pair entries and on every comparison-dependent memo
(WHNF / CORE words that went through the K rule) and a flush at the mode switch. (3) Not sufficient: "do not read
POS while ER is set" alone (the K rule's memo), and a fresh session (the two scopes are in one declaration). The
order "eager first, plain later" needs no rule for soundness: a T computed under eager reduction from an empty
history is also T in plain mode (folding anticipates what unfolding computes; the K rule fires in fewer cases).

### S-F4: POS pairs survive a declaration (`warm`; fixed)

The same pair in two declarations: `S.a : forall p, X = Y p := fun p => Eq.refl X` (plain), then `S.bad` needs it
under `eagerReduce`. v0 rejected (fresh session), every set with `warm` accepted (jar `608b84b2b142`).
`krule-eager-alone-bad` shows that the declaration that seeds the table need not look related (the opaque proof
`e` itself seeds it). M6's fix of 14:46Z (`Machine.enterER`, `erTainted`, `erSeen`): a declaration that enters
eager reduction while the session holds earlier state is checked again in a fresh session, and nothing it derived
is seen by a later declaration. (First seen from another side by M5 at 14:25Z: `memocheck` with `warm` on
`bad/bugs/rec-of-subst-prop`, a POS pair of an earlier declaration that the re-computation under eager reduction
does not reproduce.)

### S-F1 / S-F2: congruence shortcuts where the official procedure is not a congruence (fixed)

B1 (same-head probe: recursors, quotient eliminators, abbrevs, theorems), B12 (closure congruence), B3 (skipped
proof positions), B4 (skipped unread positions) and the Prop mask of structure eta answered T from "the arguments /
slots are pairwise convertible". The official `is_def_eq` reduces before it compares: `And.rec f (And.intro ..)`
becomes `5`, `And.rec f h` (h an axiom or variable) stays stuck (no structure eta for a proposition), and `5` against
a stuck recursor is false - although `And.intro .. = h` by proof irrelevance. The official kernel compares arguments
first only for a same-head definition with a REGULAR hint (`probe-b1-def-proof-arg-ok` is accepted officially,
`probe-b1-abbrev-proof-arg` and `probe-b1-theorem-head` are rejected). It is also not transitive
(`nontransitive-*`: `d intro = d h` and `d intro = 5` are accepted, `d h = 5` is rejected; the machine does not close
them). M4 removed B1, B12, B2, B3, B4 and the ETAS mask from every switch set at 14:30Z (system property
`-Drlvm.machine.m4=congruence,skipargs` for measurements only); `M4OfficialTest` and the rows of package S guard it.

## 3. Method and tools (all kept)

- `work/soundness/tools/lk.py`: builds exports on top of `init-prelude` (imports the dependency cone of the
  constants a test names, structural interning as the exporter does, a recursor generator for non-mutual
  inductive types that the official checker accepts for seven shapes). `gen/batch1..8.py`: 349 hostile exports.
- `tools/judge.py`: the official verdict, the reference engine and the machine through the launcher under up to
  17 switch sets (suffixes: `@1` = `-Drlvm.machine.elabAt=1`, `!d0` = host depth 0, `#t4` = four workers); flags
  WRONG-ACCEPT / WRONG-REJECT / M-INCOMPLETE / M-DECLINE / REF-DIFFERS.
- `tools/mutate.py`: seven mutation kinds that edit ONE OCCURRENCE inside ONE declaration (the path from the root
  is copied, so a shared sub-term keeps its meaning elsewhere): `LEVEL_ARG`, `SWAP_UNIV_PARAMS`, `SIBLING_ARG` (the
  argument of another application of the same function), `LIT_SUCC_EQ` / `LIT_SUCC_NE`, `BETA_WRAP`, `LET_WRAP`.
  The existing kinds of `rlvm-testkit` edit records in place; the request to adopt these is in `rlvm-testkit.log`.
- Permanent regression: `rlvm-machine/src/test/resources/s/` (329 exports, gzip, manifest with the official
  verdict and the attack; 45 accepted, 284 rejected by the official checker), `SHostileTest` (fast group, at every
  publication of every package: v0 and `warm,gated,expect` on every export, the staged switches on their targets),
  `SHostileSlowTest` (17 switch sets x every export; strict `noKnownFindingIsLeft`). The rows of the four fixed
  findings are strict for everybody; the two rows of S-F3 are marked `levels` and printed until it is fixed.

## 4. Shortcuts and memos reviewed

Condition = what must hold for the shortcut to give the official verdict. Evidence = the exports of
`manifest.tsv` by prefix (all judged against the official checker under the switch sets of section 5).

### 4.1 Identity, levels, closures

| Shortcut / memo | Condition | Where established | Evidence |
|---|---|---|---|
| value identity (`a == b` is T; hash-consed closures `(term, PRUNE(frame), ls)`) | equal ids denote terms that are equal after instantiation, beta, zeta | `Values.closure`, `Runs.prune` (mask contract: a clear bit is definite) | `chk-open-term-shared-*`, `chk-same-lambda-two-types`, 99 exports of batch 1 |
| level substitution id (B1 of RECONCILIATION) | `ls` is `mkSubst(lparams(row), levels)`, applied simultaneously; identity only for the declaration under check | `Values.mkConst`, `Levels.lsub` | `lvl-swapped-params-*`, `lvl-shared-closed-subterm-*`, `lvl-simultaneous-subst-*`, `lvl-param-shadow-*`; mutants `LEVEL_ARG` (182), `SWAP_UNIV_PARAMS` (43) |
| level comparison | the official `is_equivalent` | `Levels.lvlEq` uses the complete relation | **S-F3** |
| theorem type is a proposition | the official `normalizes_to_zero` on the inferred sort | `Decl.phases` (`isAlwaysZero`) | `thm-type-*` (6) |
| canonical free variables `FVAR(type, k)` | binders close in LIFO order; nothing mode- or history-dependent is keyed by a variable | `Scope.open / closeTo`, the cut of TRY | scopes: batch 1; **was broken for the pair table and the K rule under eager reduction: S-F5; holds now because the machine never compares under eager reduction** |

### 4.2 Inference and checking

| Shortcut / memo | Condition | Where established | Evidence |
|---|---|---|---|
| INFER / CHECK tabling with check stamps (TSTAMP, generations, PERM) | a CHECKED answer was derived in CHECKED mode for the same `(term, frame, ls)`; an ONLY answer never answers a checking query; a stamp of an earlier declaration is valid only if ranks and level parameters are monotone | `Infer.hit`, `k_infer1`, `stamp`; `Decl.regime` | `chk-*` (30: ill-typed terms that evaluation erases, unused arguments and lets, proof arguments), `lvl-warm-lparam-reuse-bad`, `chk-warm-same-lambda-two-types-bad` |
| trusted regime (CHKCONST / CHKLVL are no-ops) | the root summaries prove them: `depMax < rank`, no UNRESOLVED (covers level arity), `lpSet` within the parameters | loader flags; `StructuralChecks` runs before any engine; `Decl.rootOk` | `lvl-arity-in-proof-bad`, `lvl-undeclared-in-proof-bad`; 2,692 `bugs/` mutants |
| B9 expected-type propagation (`expect`) | the lambda's domain is checked to be a sort and converted with the expected domain before the body is checked; the stored type is the lambda's own PII, never the expected type | `Infer.checkExpect`, `k_ck1..8`, `twX` | `chk-lam-*` (4), `chk-arg-lam-*` (6), `chk-def-lam-illtyped-domain-bad` |
| B6 unread binder gets HOLE | the argument is still checked in CHECKED mode | `Infer.twArg` (always INFER in CHECKED mode) | `chk-unused-arg-bad`, `chk-let-unused-*`, mutants `BETA_WRAP` / `LET_WRAP` (536) |
| IPROJ | the official `infer_proj`: one constructor, parameters + indices, Prop restrictions with exact occurrence test | `Infer.inferProj`, `bodyReads0ExactSlow` | `chk-proj-*` (4), `proj-*` (8: indexed families, Prop witness, dependent proof) |
| TELE with domain patterns (`tele`) | MATCH answers T only for structural identity up to level equivalence; else the domain is built and CONV runs | `Match.matchIn` | `tele-*` (14: wrong index, wrong level, swapped parameters, closure patterns, over-application) |

### 4.3 Conversion

| Shortcut / memo | Condition | Where established | Evidence |
|---|---|---|---|
| pair table POS / NEG | T / F is what the official kernel answers for the pair in the current mode and history | `Conv.rec`, `pairHit` (NEG not read under ER; no NEG for a LAM side) | plain mode (the official plain procedure is a function of the pair): `nontransitive-*` (no transitive closure), batch 1; **eager mode: S-F5, S-F4, closed by the decline rule** |
| proof irrelevance, PROOF flag, static PROOF (B7, `facts`) | the flag is `is_prop(infer_type(v))`; a static YES / NO only for a manifest, level-instantiated sort and a spine within the telescope | `Conv.pirr`, `k_pirr2`, `Facts.staticProof` | `pirr-*` (8: polymorphic and imax result sorts, over-application), `facts-static-proof-wrong-type-bad` |
| B3 / B4 argument masks, ETAS Prop mask (`facts`) | (were:) skipped positions are convertible for the OFFICIAL procedure | `Cargs.skipMask` | **S-F2, removed**; `mask-*` (14), `facts-*` (14) |
| B1, B12, B2 (`probes`) | (were:) congruence is the official answer | `Conv.convB1`, `cclo` | **S-F1, removed**; `probe-*` (11) |
| pass B, quick refutation, UNDEC, FAILED, debt (`probes`) | order-only: every answer is an answer of the complete CONV on a pair; U never enters FAILED; a fatal fault is never turned into U | `Cargs.passB`, `Frames.k_try` | code review; matrix of section 5 |
| TRY segments | a cut restores scopes, scratch, environment, registers, ER; memos are written only by completing steps; a capped budget passes ABORT on | `Frames.k_try`, `trySignalSlow` | code review (no export reaches a cut deliberately) |
| eta, structure eta, unit-like, K, structure eta in iota | the official side conditions: Pi type after whnf; `is_non_rec_structure` (recomputed flags); no eta for propositions; K only when the type of the major converts with the constructor's | `Conv.eta`, `etas`, `unitl`, `isNonRecStructure`; `Reduce` | `eta-*` (9, of them 6 structure eta, incl. a forged `isRec` flag), `unitlike-*` (6), `iota-struct-*` (3), `k-eq-rec-*` (4), `krule-*` (4) |
| lazy delta, Nat fold and offsets, `Bool.true` shortcut | the official order; fold only when both sides are closed or ER; OPEN is the official `has_fvar` (let variables of INFER included, erratum E1) | `Conv.lazydLoop`, `lazydFold` | `nat-*` (117), `k1-*` (43), `open-flag-*` (3: a variable that beta discards, a let variable) |
| `lazyeval` (one-sided unfolding by one WHNF) | the other side is weak-head normal, so the official loop can only leave early through its success cache; the final pair is the same | `Conv.lstep` (M4) | argument: quick refutes only WHNF forms, OFFSET needs constructor / literal forms, fold is what WHNF does; matrix of section 5 |

### 4.4 Literals, reduction, K1

| Shortcut / memo | Condition | Where established | Evidence |
|---|---|---|---|
| Nat immediates below 2^31, NBIG below 2^63, NatStore above; identity by content | no truncation; equal content is equal whatever the ids | `Lits`, `Conv.litEq`, `NatRed` | `nat-boundaries-ok` (true identities across 2^31 / 2^63 / 2^64, equal literals with different ids), 32 false arithmetic claims at the boundaries, 30 claims that two different literals are equal, 9 false Bool claims |
| `Nat.succ` offsets (NSUC) | the official `is_def_eq_offset` and `reduce_nat` | `Values.nsucc`, `NatRed.offset` | `nat-succ-*` (45) |
| string literals | expansion to `String.ofList` of code points; identity by content | `StrRed`, `Conv.strx` | `str-lit-*` (4 kept in the suite of 26 generated, about 600 KB each; all 26 judged) |
| case trees, CASESON, LEAF shapes, BODY blocks (`code`, `casetrees`) | the block computes what DELTA + the recursor rule + beta compute; no K-like or structure-like major; no minor reads an induction hypothesis; shape by structure | `Elab.caseTree`, `tree`, `casesOnInfo`, `recOk` | `k1-*` (43: Nat literals and offsets as majors, 2^31 / 2^32 / 2^64, three levels, families with parameters and indices, over-application), `quot-*` (4) |
| warm session | everything a session keeps is declaration-independent; the overlay, level and number overlays are reset together with it (stack discipline of `Overlay.resetTo` in admission checked: every cut is to a mark taken above what the session holds) | `MachineSession.check`, `Worker.resetWarm`, `giveBack` | code review; `warm-*` (7), `krule-*`; **S-F4, fixed** |
| resource exhaustion | ACCEPT only from `Decl.check` returning 0; every limit, exception and failed audit is DECLINE | `MachineSession.check`, `Decl.accepted` | code review |

## 5. Campaign results

| Campaign | Size | Result |
|---|--:|---|
| hostile exports, through the launcher (official + reference + machine jar `608b84b2b142`), 4 to 17 switch sets each | 349 | 99 of batch 1 clean under 17 sets; S-F1 (9 + 3), S-F2 (4), S-F4 (3), S-F5 (2), S-F3 (2); every other row has the official verdict under every set judged; 4 expectations of mine were wrong and were corrected by the official verdict (the manifest holds the official verdict, never my expectation) |
| the same in-process on the main tree (`SHostileSlowTest`, 17 sets incl. `lazyeval` x 326 rows, 14:50Z; `work/soundness/out/slow-matrix.txt`) | 5,542 judgments | 0 unexpected, 0 declines of the machine; open then: `eager` (2 rows, every set), `warm` (3 rows, the 5 sets with `warm`), `levels` (2 rows, every set); the 16 rows of S-F1 / S-F2 pass under every set |
| strict re-run of the marked rows on the main tree of 15:05Z (`noKnownFindingIsLeft`, 7 sets) | 329 rows in the suite | the 3 `warm` rows pass (M6's fix of 14:46Z); open: `eager` (2 rows, every set), `levels` (2 rows) |
| **final: the whole matrix on the main tree of 15:20Z** (`SHostileSlowTest`, 17 sets x 329 rows; `work/soundness/out/slow-matrix-final.txt`) | 5,593 judgments | **0 unexpected, 0 unexpected declines; the only rows that still differ from the official verdict are the 2 `levels` rows (S-F3), under every set.** The 11 exports that enter eager reduction are declined by design (or rejected before they reach it) |
| targeted mutants (`mutate.py`, 7 kinds) of the 101 good tutorial / bugs / other tests and 33 accepted hostile exports | 602 judged of 997 generated | 0 wrong verdicts under v0, `warm,gated,expect`, `all@1` (205 of them also under `warm,gated,expect,probes`); 2 with a decline (P3; a cancellation after the first failure) |
| official mutant corpus `bugs` (`work/mutants-bugs-other`) through the machine with `warm,gated,expect` | 2,692 | SOUNDNESS 0, COMPLETENESS 0, agree 2,627, declined 65 (the pipeline's own declines) |
| official mutant corpus `tutorial` with `warm,gated,expect` | 12,260 | stopped at about 5,000 when the host was oversubscribed (lane limit of 14:02Z): **no result** |

## 6. Not done, not verified

- The four large corpora were not re-run by me (no behaviour of the machine was changed by package S); the
  integrator's and M4's gates stand for them.
- Native image: every run of mine is the JVM launcher or in-process.
- Parallel workers: one switch set of batch 1 ran with four workers (clean); no hostile export targets the
  scheduler, publication of facts or code under contention.
- Speculative segments: no export forces a cut (budget exhaustion inside a TRY) on purpose; the review of
  `Frames.k_try` and of what is written before a cut is by reading.
- K2 / T2 are off and were not reviewed. `pchk` has no code. The experiment switches `m1a .. m7b`, `t2` registered
  at 14:00Z were not reviewed.
- `Nat.gcd`, bitwise operations, shifts and `log2` are not in `init-prelude`; no hostile export covers them.
- A second mode switch of the official kernel was looked for and not found (definition safety and the local
  context do not change an answer for a pair); if one exists, S-F5 has a sibling.

## 7. Addendum of the wave-6 integrator (2026-11-24, 18:15Z - 19:40Z; not part of package S's review)

State of the findings on the main tree of 19:10Z (machine jar `78c74ca92785`, reference jar `ca1f9bd918bb`):

| Id | State | Evidence |
|---|---|---|
| S-F1, S-F2 | fixed (M4, 14:30Z); the removed shortcuts are reachable only through `-Drlvm.machine.m4=congruence,skipargs`, never through a switch set | the 16 rows pass under all 32 switch sets below |
| S-F4, S-F5 | closed by the decline rule (a declaration that enters eager reduction is declined; the reference engine decides) | the 5 rows and the 11 `eager-decline` rows under all 32 sets; the rule declines 164 / 203 / 6,711 declarations of Init / Std / mathlib (statistic `machine.eagerDeclines`) |
| **S-F3** | **closed**: both engines compare universe levels as the official kernel does by default (`Levels` of the machine since 14:22Z, rlvm-ref `ca1f9bd918bb` since 16:03Z); `levels-complete` / `-X levels=complete` is the opt-in | `arena-imax-right-successor`, `arena-imax-nested-absorption` are REJECTED under every set; the strict test `SHostileSlowTest#noKnownFindingIsLeft` passes and the mark `levels` is gone from `s/manifest.tsv` |

The matrix was run again with the switches that did not exist or had no name when the review ended
(`SHostileSlowTest`, in-process, the manifest's official verdicts): 32 switch sets x 329 exports = 10,528
judgments, **0 unexpected verdicts, 0 known findings that still reproduce**. The 15 new sets: `bodies`, `same`,
`match`, `joint`, `pchk`, `directrules`, `code,casetrees,lazycases`, `k2,directrules`; step 1 with `bodies`, with
`same`, with `match,joint,pchk`; the new default set (`warm,gated,expect,bodies,pchk,match,joint,code,casetrees,
lazycases,directrules,lazyeval,same`) with and without `same` and with `k2`; `all` plus every new switch.

What this does NOT replace: an adversarial review of the new switches. Section 6 lists `pchk`, the experiment
switches and K2 as not reviewed, and `same` (M4's request of 15:26Z) was never attacked by package S. They are
in the default set on the strength of the matrix above, M8's gate (differential, tier equivalence, convention
equivalence, the audits `memocheck` and `shortcheck`, which re-derive every answer of `same` and of PCHK by the
complete procedure, on all 230 tests and the four corpora), 17,775 mutants and the DiffEngine runs on six
corpora (`rlvm-machine/SWITCHES.md`). The general lesson of section 1 applies to each of them: the question is
whether the OFFICIAL procedure gives the answer, not whether the answer is true. For `same` the argument is
that it answers T only for two values that read back to the same expression up to beta, zeta and literal
canonicalisation - the condition under which value identity (v0) is the official answer; for PCHK that a
closed, level-parameter-free term that passed check mode once has the same type everywhere its declarations
are visible, and the bit is never set after an eager-reduction comparison. A next review should attack both,
and `bodies` / `match` / `joint` / `lazycases` / `directrules`, with exports of its own.

**21:00Z, after this addendum was written: FIVE NEW FINDINGS (E1-F1 .. E1-F5), wrong ACCEPTs, three of them in
v0.** Agent E1 wrote hostile exports of a shape neither package S nor this addendum had (`work/e1/exports/`, 67
exports with the official verdicts): over package S's non-transitive triple (`d intro = d h` and `d intro = 5`
accepted officially, `d h = 5` rejected) a bound variable must have the binder's DECLARED type, and the machine
gives it a type that is merely convertible with it: the type inferred for a let VALUE (F1), for the ARGUMENT of
a beta redex whose codomain is re-inferred in ONLY mode (F2), the other side's domain when a lambda is compared
with a Pi behind a first binder (F3); with `expect` the comparison variable carries the lambda's own domain (F4)
and the expected codomain is stored as the lambda's (F5). The matrix above did not contain such a row, and the
corpus gates compare with the reference engine on terms whose types coincide. The machine was the default
engine from 19:41Z to 20:35Z on the evidence of this addendum and is not any more (`MachineProvider.priority()`
= 10) until these are fixed (E1: a gated patch for F1, a design for F2 - F5) and E1's rows are strict rows of
`s/manifest.tsv`. Section 6's "not reviewed" list was the warning; this addendum should not have been read as
a review.
