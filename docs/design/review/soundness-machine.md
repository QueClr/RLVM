# Independent soundness review of the machine (package S)

2026-11-24, 13:20Z - 15:10Z. Reviewed: `rlvm-machine` as published at 12:47Z (jar `608b84b2b142`, the integrator's
final state: distribution copy `work/soundness/dist`), and again in-process against the main tree of 14:44Z
(after M4's fix of S-F1 / S-F2). Ground truth everywhere: the official checker (`leanchecker --from-export`, Lean
4.36.0, through `rlvm-testkit/bin/testkit verdict`), never the reference engine alone.

"Wrong ACCEPT" below means: the process accepts an export that the official checker rejects (owner note 11:
resemble official). All five findings accept statements that are TRUE in the declarative theory; none proves
`False`. They are verdicts the official checker does not give, which is what the arena scores.

## 1. Verdict

| Configuration | Verdict | Why |
|---|---|---|
| **v0 (no switch): the machine as the default engine** | **NOT SAFE as published** | S-F5: one declaration is accepted that the official checker rejects (canonical free variables + pair table + eager reduction). Open |
| `warm` (so `warm,gated,expect`, the candidate default of step 1) | **NOT SAFE as published** | S-F4 in addition to S-F5: POS pairs (and reductions that depend on them) survive a declaration. Open |
| `gated`, `expect`, `inert` | no finding of their own | semantic no-ops (which nodes are tabled; one loop instead of recursion) or an admissible rule (B9); 326 exports + 602 targeted mutants + 2,692 mutants of `bugs/` without a difference. They inherit S-F5 |
| `probes` | was NOT SAFE (S-F1); **fixed by M4 at 14:30Z**: only the order-only parts are left (pass B, quick refutation, UNDEC, debt) | B1 / B12 / B2 are no longer reachable through a switch; the 9 + 3 reproducers are strict regression rows now and pass. Inherits S-F5 |
| `facts` | was NOT SAFE (S-F2); **fixed by M4 at 14:30Z**: only B7 (static PROOF) is left in conversion | B3 / B4 / the Prop mask of ETAS are no longer reachable through a switch; the 4 reproducers are strict rows and pass. Inherits S-F5 |
| `tele`, `code`, `casetrees` (K1 roles) | no finding | 61 exports built to pass the rent-to-own threshold (and `-Drlvm.machine.elabAt=1` through the launcher): case trees on Nat literals / offsets / families / enumerations, CASESON, leaves, over-application, TELE domain patterns with levels and closures: official verdict under every set. Shape recognition is structural, never by name. Inherit S-F5 |
| `lazyeval` (new, 14:00Z) | no finding in the matrix (section 5); argument in 4.3 | order-only by construction; inherits S-F5 |
| universe levels | NOT the official relation (S-F3, known, project-wide) | both engines accept the two arena corner cases the official checker rejects; owner decision 13:40Z, M1 / rlvm-level in progress (`levels-complete` is registered as the opt-in) |

**What must happen before the machine may be the default engine, in any configuration:** S-F5 (and with it S-F4)
must be closed. The cheapest exact fix is in section 2 (decline, or isolate, the 0.2 - 0.3 % of declarations that
apply `eagerReduce`). After that, on today's evidence, v0 and `warm,gated,expect` have no other finding.

## 2. Findings

| Id | Severity | Switch sets | Reproducer (`work/soundness/exports/`, gzip under `rlvm-machine/src/test/resources/s/`) | Owner | State |
|---|---|---|---|---|---|
| **S-F5** | wrong ACCEPT | **every set, v0 included** | `canon-fvar-pos-across-binders-eager-bad`, `krule-memo-across-binders-eager-bad` | M4 (Pairs, Conv), M1 (Scope), M5 / M6 (policy) | OPEN, reported 14:41Z |
| **S-F4** | wrong ACCEPT | every set with `warm` | `warm-pos-across-declarations-eager-bad`, `krule-eager-alone-bad`, `krule-memo-across-declarations-eager-bad` | M6 (sessions), M4 | OPEN, reported 14:41Z |
| S-F1 | wrong ACCEPT | every set with `probes` | `probe-b1-rec-proof-major-bad` (+ flipped), `probe-b12-{closure,let,lam-closure}-proof-slot-bad`, `probe-b1-abbrev-proof-arg`, `probe-b1-theorem-head`, `probe-b1-rec-under-{ctor,axiom}-bad`; also the three `facts-*-noncongruent-*` files and `warm-eager-pair-alone-bad`, `*-eager-first-bad` | M4 | FIXED 14:30Z (verified in-process on the main tree of 14:44Z) |
| S-F2 | wrong ACCEPT | every set with `facts` | `facts-agreement-noncongruent-app-bad`, `-samedef-bad`, `facts-etas-prop-mask-noncongruent-bad`, `facts-unread-noncongruent-bad` | M4 | FIXED 14:30Z (verified likewise) |
| S-F3 | wrong ACCEPT (known) | every set, and the reference engine | `arena-imax-right-successor`, `arena-imax-nested-absorption` (arena class `either`) | M1 (Levels), rlvm-level, rlvm-ref | in progress (owner decision 13:40Z) |
| P1 | decline where the official checker accepts (pipeline, not the machine) | - | an inductive block without recursor records (`work/soundness/out/norec.ndjson`) | rlvm-admit | by design (docs/research/01 2.11); noted |
| P2 | decline where the official checker rejects (pipeline) | - | `str-lit-missing-prerequisites-bad`: two different string literals in an export without `String.ofList` / `Char.ofNat` | rlvm-admit | by design (UNSUPPORTED_PRELUDE) |
| P3 | decline where the official checker accepts (pipeline) | - | mutant `nat-lit-sub--BETA_WRAP-2` (`work/soundness/mut1/`): a beta-redex wrapped into the body of a Nat prelude function | rlvm-admit | by design (prelude guard) |

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

Fixes (the owners' decision; sent 14:41Z): eager reduction is the only mode switch of the official kernel and
occurs in 164 of Init's 55,401 and 203 of Std's 99,235 def / thm / opaque / axiom declarations (0.30 % / 0.20 %,
counted on the exports). (1) Exact and cheapest: a declaration whose type or value contains an application of the
constant `eagerReduce` is declined by the machine (the reference engine checks it) and no session state crosses it.
(2) It runs in a session of its own with canonical variables off (every OPEN a fresh identity). (3) Not sufficient:
"do not read POS while ER is set" alone (the K rule's memo; and the same-scope twin must stay ACCEPT or become a
refuted machine REJECT, never the reverse).

### S-F4: POS pairs survive a declaration (`warm`)

The same pair in two declarations: `S.a : forall p, X = Y p := fun p => Eq.refl X` (plain), then `S.bad` needs it
under `eagerReduce`. v0 rejects (fresh session), every set with `warm` accepts. `krule-eager-alone-bad` shows that
the declaration that seeds the table need not look related (the opaque proof `e` itself seeds it). Closed by any
fix of S-F5 that keeps session state from crossing an eager declaration.

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
  inductive types that the official checker accepts for seven shapes). `gen/batch1..7.py`: 346 hostile exports.
- `tools/judge.py`: the official verdict, the reference engine and the machine through the launcher under up to
  17 switch sets (suffixes: `@1` = `-Drlvm.machine.elabAt=1`, `!d0` = host depth 0, `#t4` = four workers); flags
  WRONG-ACCEPT / WRONG-REJECT / M-INCOMPLETE / M-DECLINE / REF-DIFFERS.
- `tools/mutate.py`: seven mutation kinds that edit ONE OCCURRENCE inside ONE declaration (the path from the root
  is copied, so a shared sub-term keeps its meaning elsewhere): `LEVEL_ARG`, `SWAP_UNIV_PARAMS`, `SIBLING_ARG` (the
  argument of another application of the same function), `LIT_SUCC_EQ` / `LIT_SUCC_NE`, `BETA_WRAP`, `LET_WRAP`.
  The existing kinds of `rlvm-testkit` edit records in place; the request to adopt these is in `rlvm-testkit.log`.
- Permanent regression: `rlvm-machine/src/test/resources/s/` (326 exports, gzip, manifest with the official
  verdict and the attack), `SHostileTest` (fast group: v0 and `warm,gated,expect` on every export, the staged
  switches on their targets), `SHostileSlowTest` (17 switch sets x every export; strict `noKnownFindingIsLeft`).

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
| canonical free variables `FVAR(type, k)` | binders close in LIFO order; nothing mode- or history-dependent is keyed by a variable | `Scope.open / closeTo`, the cut of TRY | scopes: batch 1; **broken for the pair table and the K rule under eager reduction: S-F5** |

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
| pair table POS / NEG | T / F is what the official kernel answers for the pair in the current mode and history | `Conv.rec`, `pairHit` (NEG not read under ER; no NEG for a LAM side) | plain mode: `nontransitive-*` (no transitive closure), batch 1; **eager mode: S-F5, S-F4** |
| proof irrelevance, PROOF flag, static PROOF (B7, `facts`) | the flag is `is_prop(infer_type(v))`; a static YES / NO only for a manifest, level-instantiated sort and a spine within the telescope | `Conv.pirr`, `k_pirr2`, `Facts.staticProof` | `pirr-*` (8: polymorphic and imax result sorts, over-application), `facts-static-proof-wrong-type-bad` |
| B3 / B4 argument masks, ETAS Prop mask (`facts`) | (were:) skipped positions are convertible for the OFFICIAL procedure | `Cargs.skipMask` | **S-F2, removed**; `mask-*` (14), `facts-*` (14) |
| B1, B12, B2 (`probes`) | (were:) congruence is the official answer | `Conv.convB1`, `cclo` | **S-F1, removed**; `probe-*` (11) |
| pass B, quick refutation, UNDEC, FAILED, debt (`probes`) | order-only: every answer is an answer of the complete CONV on a pair; U never enters FAILED; a fatal fault is never turned into U | `Cargs.passB`, `Frames.k_try` | code review; matrix of section 5 |
| TRY segments | a cut restores scopes, scratch, environment, registers, ER; memos are written only by completing steps; a capped budget passes ABORT on | `Frames.k_try`, `trySignalSlow` | code review (no export reaches a cut deliberately) |
| eta, structure eta, unit-like, K, structure eta in iota | the official side conditions: Pi type after whnf; `is_non_rec_structure` (recomputed flags); no eta for propositions; K only when the type of the major converts with the constructor's | `Conv.eta`, `etas`, `unitl`, `isNonRecStructure`; `Reduce` | `eta-*` (9, of them 6 structure eta, incl. a forged `isRec` flag), `unitlike-*` (6), `iota-struct-*` (3), `k-eq-rec-*` (4), `krule-*` (4) |
| lazy delta, Nat fold and offsets, `Bool.true` shortcut | the official order; fold only when both sides are closed or ER | `Conv.lazydLoop`, `lazydFold` | `nat-*` (117), `k1-*` (43) |
| `lazyeval` (one-sided unfolding by one WHNF) | the other side is weak-head normal, so the official loop can only leave early through its success cache; the final pair is the same | `Conv.lstep` (M4) | argument: quick refutes only WHNF forms, OFFSET needs constructor / literal forms, fold is what WHNF does; matrix of section 5 |

### 4.4 Literals, reduction, K1

| Shortcut / memo | Condition | Where established | Evidence |
|---|---|---|---|
| Nat immediates below 2^31, NBIG below 2^63, NatStore above; identity by content | no truncation; equal content is equal whatever the ids | `Lits`, `Conv.litEq`, `NatRed` | `nat-boundaries-ok` (true identities across 2^31 / 2^63 / 2^64, equal literals with different ids), 32 false arithmetic claims at the boundaries, 30 claims that two different literals are equal, 9 false Bool claims |
| `Nat.succ` offsets (NSUC) | the official `is_def_eq_offset` and `reduce_nat` | `Values.nsucc`, `NatRed.offset` | `nat-succ-*` (45) |
| string literals | expansion to `String.ofList` of code points; identity by content | `StrRed`, `Conv.strx` | `str-lit-*` (4 kept in the suite of 26 generated, about 600 KB each; all 26 judged) |
| case trees, CASESON, LEAF shapes, BODY blocks (`code`, `casetrees`) | the block computes what DELTA + the recursor rule + beta compute; no K-like or structure-like major; no minor reads an induction hypothesis; shape by structure | `Elab.caseTree`, `tree`, `casesOnInfo`, `recOk` | `k1-*` (43: Nat literals and offsets as majors, 2^31 / 2^32 / 2^64, three levels, families with parameters and indices, over-application), `quot-*` (4) |
| warm session | everything a session keeps is declaration-independent; the overlay, level and number overlays are reset together with it (stack discipline of `Overlay.resetTo` in admission checked) | `MachineSession.check`, `Worker.resetWarm`, `giveBack` | code review; `warm-*`; **S-F4** |
| resource exhaustion | ACCEPT only from `Decl.check` returning 0; every limit, exception and failed audit is DECLINE | `MachineSession.check`, `Decl.accepted` | code review |

## 5. Campaign results

| Campaign | Size | Result |
|---|--:|---|
| hostile exports, through the launcher (official + reference + machine jar `608b84b2b142`), 4 to 17 switch sets each | 346 | 99 of batch 1 clean under 17 sets; S-F1 (9 + 3), S-F2 (4), S-F4 (3), S-F5 (2), S-F3 (2); every other row has the official verdict under every set judged; 2 expectations of mine were wrong and corrected by the official verdict |
| the same in-process on the main tree of 14:44Z (`SHostileSlowTest`, 17 sets x 326 rows) | 5,542 judgments | see `work/soundness/out/slow-matrix.txt`: open are exactly `eager` (2 rows, every set), `warm` (3 rows, sets with `warm`), `levels` (2 rows, every set); `probes` / `facts` rows pass (strict) |
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
