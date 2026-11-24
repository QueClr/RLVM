# Eager reduction on the machine (package E1)

2026-11-24. Prototype and evidence: `work/e1/` (tree `work/e1/tree/rlvm-machine`, patches `work/e1/patches/`,
hostile exports `work/e1/exports/` with official verdicts in `work/e1/manifest.tsv`, living summary
`work/status/prof-E1.md`). The main tree is unchanged: the mechanism is a patch behind the switch `eager`
(`Op.SW_EAGER`, bit 26; off = the published rule M6-F26, a declaration that enters eager reduction is declined).

Ground truth everywhere: the official checker (`leanchecker --from-export`, Lean v4.36.0, in the arena Docker
image, `work/e1/official.sh`), and `refs/lean4/src/kernel/type_checker.cpp` for the reading of the procedure.

## 1. Result in one page

- **It can be done, and the prototype does it.** With the switch `eager` the machine keeps every declaration of
  Init, Std, cedar, reachability and Mathlib that enters eager reduction: machine declines 164 / 203 / 423 / 8 /
  6,711 units -> 0 on all five (launcher, 8 workers; section 7), at +2.5 % to +4.4 % of the machine's ticks. The
  differential gate against the reference engine per declaration is clean (SOUNDNESS 0, COMPLETENESS 0, 0 declines)
  on tutorial, bugs, the Cedar slice, reachability, Init, Std, the 16 Mathlib slices (436,408 units) and all 230 published tests.
- **How** (section 4): the comparison of an `eagerReduce _ _` argument is DEFERRED to the end of the declaration, so
  that no plain judgment ever reads an answer derived under eager reduction; the plain phase records every point
  where the two modes would take different branches and at the end compares the two answers of each such point
  (a difference declines the declaration: these are exactly the hostile pairs of S-F4 / S-F5); an eager-only event
  inside the comparison of the K rule declines; a session is not shared across such a declaration when it could
  hold a mode-dependent answer.
- **How exact** (sections 5, 6): the official verdict under the machine's standing assumption that the official
  procedure on terms and the machine's on values ask the same queries ("official up to evaluation"). 396 hostile
  exports (package S's 329 and 67 new ones with official verdicts from Docker) under 33 switch sets: no wrong
  verdict of the mechanism; 20 rows are declined by design. One gap is not closed and not demonstrated (section 6
  item 1): a K reduction whose plain and eager computation the machine both skips by value identity.
- **Found on the way, not about eager reduction, and worse** (section 6 item 6; `work/e1/patches/let/`,
  `work/e1/patches/E1-F2-F5-design.md`): five wrong ACCEPTs of the machine as published, in v0 and / or the default
  set: a convertible type is used in place of the official one (the type of a let variable, of a redex's bound
  variable, of a lambda's bound variable under comparison, B9's comparison variable and codomain memo), and the
  official relation is not transitive. A gated patch exists for the let rule (E1-F1) only.
- The eager patches are relative to the main tree of 20:19:58Z + the let patch (section 8).

## 2. What the official kernel does

`infer_app` in check mode (`type_checker.cpp:173-187`): for an argument of the form `eagerReduce _ _`
(`is_eager_reduce`, a constant named `eagerReduce` applied to two arguments) the comparison
`is_def_eq(a_type, d_type)` runs with `m_eager_reduce = true` (`flet`, line 180). `is_def_eq` never calls check-mode
inference (`infer_type` is infer-only), so the flag is true for exactly the dynamic extent of that one comparison
and comparisons under it do not nest. The flag is read in two places:

- **P1**, `lazy_delta_reduction` (line 1056): `if ((!has_fvar(t_n) && !has_fvar(s_n)) || m_eager_reduce)`: try
  `reduce_nat(t_n)`, then `reduce_nat(s_n)`; if one succeeds with `v`, the answer is `is_def_eq_core(v, s_n)` (or
  `(t_n, v)`). In plain mode a pair with a free variable skips this and goes on with `lazy_delta_reduction_step`;
- **P2**, `is_def_eq_core` (line 1138): `if ((!has_fvar(t) || m_eager_reduce) && is_constant(s, Bool.true))`:
  `whnf(t)` is `Bool.true`: true; else fall through to the common path.

Nothing else depends on the mode. `whnf`, `whnf_core` and infer-only `infer_type` depend on it only through the K
rule (`inductive_reduce_rec`: `to_cnstr_when_K` calls `is_def_eq(app_type, new_type)`): the only place where the
ANSWER of a comparison changes the RESULT of a reduction. Everywhere else an answer T is used positively (a
conjunction goes on, or the comparison ends with T) and F means "try the next rule", so the procedure is monotone
in the answers of its sub-comparisons except through K.

The caches (`m_infer_type[2]`, `m_whnf_core`, `m_whnf`, `m_success`, `m_failure`, `m_unfold`) belong to one
`type_checker`, i.e. one declaration, are keyed by terms whose free variables are unique per binder instance, and
are read and written in BOTH modes: `quick_is_def_eq` answers T for a pair in `m_success` (written by every
`is_def_eq` that returned true, line 1205) whatever the mode; `whnf_core` looks its cache up even in the cheap modes
(line 487). So the official answer for a query is not a function of the query: it depends on the mode and on what
the declaration computed before, in which mode, under which binder instance.

Definitions used below. A **query** is a comparison `(t, s)` or a reduction / inference of a term. Its **plain
value** and its **eager value** are what the procedure computes for it with empty caches in plain mode / under the
flag. A query is **mode-dependent** if the two differ. Example (package S, S-F5): `X := Nat.add (G (And.intro ..)) 0`,
`Y p := Nat.add (G p) 0`, `G x := And.rec (fun _ _ => 0) x`, `p` free: plain T (no fold, same head, arguments by
proof irrelevance), eager F (`X` folds to `0`, `0` against `Nat.add (G p) 0` ends at a stuck recursor).

If no query that a declaration asks in BOTH modes is mode-dependent, every cache entry holds what its reader would
have computed, so every official answer is the cache-free value of the mode that asks, whatever was asked first.
This is the property the mechanism establishes, or declines.

## 3. Why the machine's tables could not reproduce it

The machine computes on hash-consed values with canonical free variables (`FVAR(type, k)`), and its memos are
keyed by values and live for a session:

| Table | What goes wrong across the mode switch |
|---|---|
| pair table, POS | a T of plain mode for `(X, Y p)` is read under ER, where the official kernel computes F unless ITS success cache holds the pair: same binder instance yes, another binder (the same canonical variable) or another declaration (warm session) no: S-F5, S-F4 |
| pair table, FAILED | the same for a same-head argument comparison that failed in one mode |
| pair table, NEG | machine-only memo of a complete F; never written or read under ER (ISA 5.4), but a later plain read does not see that the official success cache got the pair under ER in between |
| `WHNF` / `CORE` memo words, `TYPE`, `PROOF` / `PROPT` flags | through the K rule: `Eq.rec .. (e p)` with indices `X`, `Y p` reduces in plain mode and is stuck under ER (`krule-*`) |
| canonical variables of closed scopes | make the entries of one binder instance answer for another one |
| warm sessions | make the entries of one declaration answer in another one |
| shared facts (`PCHK`), code blocks | not affected: PCHK is not set once a declaration entered eager reduction (M5), blocks contain no comparison |

Designs: **(A) decline** (published, M6-F26): exact, costs the reference engine on these units (section 7).
**(B) stamps** on pairs and on every comparison-dependent memo word, flush at the mode switch: every memo word of
every record kind and three flag pairs would need a stamp and every reader a test, and it still would not say
whether the official cache holds a pair (same binder instance or not). **(C) a scratch session** for the eager
part: needs the two operands (values with closures, frames, variables in scope) copied into another session, and
shares the open question of (B). What both miss is the criterion of section 2: the machine cannot know WHICH
entries the official cache holds, but it can check that it does not matter. **(D), built here**: order the work
so that no table read crosses the modes in the harmful direction without that check.

## 4. The mechanism (switch `eager`)

1. **Deferral.** The comparison `CONV(Tt, D)` of an `eagerReduce _ _` argument with its domain (`Infer.twD`,
   `Code.targConv`; `Tt != D`) is not run where it stands: `Machine.erSite` records `(Tt, D)` and the open binders
   and the plain phase goes on as if the answer were T. When every plain judgment of the declaration has
   completed (`Decl.accepted`), `Machine.erDischarge` re-opens the binders of each recorded site (binders close in
   LIFO order per type, so `Scope.open` gives the same variables; checked) and runs the comparison under ER. F is
   `E_APP_MISMATCH`, the official error of `infer_app`. Consequence: **no plain judgment ever reads an answer
   derived under ER**; the comparisons under ER read plain answers.
2. **Recorded points.** The plain phase of such a declaration runs *aware* (`Machine.erAware`): at P1 with a free
   variable present it runs the two `reduce_nat` attempts as a probe, at P2 it computes `whnf(t)`. If the lifted
   branch would have answered, the point is recorded (`Machine.erNote`: the pair in hand, the folded literal, which
   branch, the open binders; once per pair, pair status bit `NOTED`) and the plain branch goes on. A probe only
   writes memos that are true in every mode (`WHNF` of an argument). At the end, before the deferred comparisons:
   the plain value of every recorded pair (`CONV` in plain mode), then, under ER, the value of its lifted branch
   (`CONV(v, sn)` / `CONV(tn, v)` / T). **A difference declines the declaration** (`Machine.erSensitive`,
   UNSUPPORTED_INPUT, statistic `machine.eager.declined.plainFold / plainBoolTrue`): the official answer for that
   pair under ER would depend on whether the official cache holds it. These are exactly the pairs of S-F4 / S-F5.
3. **K rule.** While the comparison of the K rule runs under ER, `Machine.ER` carries a nesting count
   (`Op.ER_K`, `Reduce.k_iota3 / k_iota4`; a cut or a failure restores the saved value as before). An eager-only
   event (P1 or P2 lifted for an operand with a free variable, the lifted branch answers) inside it declines the
   declaration (`machine.eager.declined.kFold / kBoolTrue`): the result of a REDUCTION would depend on the mode.
4. **Sessions.** The session flag `Machine.erSens` (cleared by `reset`) is set by a cheap, over-approximate test
   on the plain path of every declaration: P1 with a free variable and a side that `reduce_nat` could fold (a Nat
   operation or `Nat.succ` cell whose arguments are literals or not yet weak-head normal), P2 with a free
   variable. While it is false every answer the session holds is the same in both modes. A declaration that
   reaches its first `eagerReduce` argument with the flag set is run again by the facade in a fresh session,
   aware from the start (`Machine.erRestart`, statistic `machine.eager.restarts`); with the flag clear it becomes
   aware on the spot. After a declaration that recorded a point or saw an eager-only event the session is
   forgotten (`machine.eager.forgets`), so nothing derived under such an event is seen by a later declaration.
5. Unchanged: strict mode and a bare machine (tests of the judgments) keep the old path; with the switch off
   the behaviour is the published one (`Machine.erStop` is what `erSeen && erDeclines` was).

Statistics (`machine.eager.*`): `kept` (units that entered eager reduction and were decided by the machine),
`restarts`, `forgets`, `sites`, `notes`, `events`, `declined.*`; `machine.eagerDeclines` counts every decline for
eager reduction as before.

## 5. Why every table read is valid in the mode that reads it

Assume a declaration that the machine keeps (no decline). Write PLAIN for the judgments before `erDischarge`
(step 1 of it included) and EAGER for the comparisons under ER.

- **PLAIN reads only plain-derived entries.** By deferral nothing was derived under ER when PLAIN runs. Entries
  of earlier declarations are in the session only if `erSens` was false at the first site (else the run was
  repeated in a fresh session): none of them passed a point where the modes differ, so they are plain values and
  eager values at once. The machine's plain procedure on plain-derived tables is the published v0 argument
  (the plain procedure is a function of the pair).
- **EAGER reads plain-derived entries whose two values agree.** A plain-derived entry (POS, NEG is not read
  under ER, FAILED, a `WHNF` / `CORE` / `TYPE` word, a `PROOF` flag) belongs to a query whose plain computation
  either passed no point where the modes differ, then its eager computation is the same computation; or passed
  recorded points. Take the first one on its path: the plain computation and the eager computation of the
  enclosing query are identical up to it, and from it the eager one returns the value of the lifted branch while
  the plain one returns the plain value of the pair in hand (a restart of `is_def_eq_core` on the pair in hand
  does what the loop goes on to do: the pair is `whnf_core`-normal, proof irrelevance was already undecided).
  Step 2 checked that the two agree for every recorded pair. The check itself reads plain entries of other
  recorded pairs; by induction on the depth of the cache-free eager computation (finite, or the official kernel
  does not terminate either) all of them are right once all checks passed. A reduction that went through K did
  so through a comparison, which is a query like any other. So every plain-derived entry EAGER can read holds
  the eager value.
- **EAGER reads eager-derived entries** of the same declaration: the eager procedure on eager-derived tables is
  a function of the pair (the same argument as for plain mode; canonical variables rename consistently).
- **The official run** asks the plain queries in plain mode and the deferred comparisons under ER, interleaved,
  with caches that cross the modes. A plain query can meet an eager-derived entry only for a query that it also
  asks itself; if that query is mode-dependent it has a recorded point with two different values and the machine
  declined; otherwise the entry holds the plain value. An eager query that meets a plain-derived entry: the same
  with the roles exchanged. So the official answers are the cache-free values, which is what the machine
  computed, in another order. F of a deferred comparison rejects in both orders (the official kernel at the
  site, the machine at the end; a plain error in between rejects in both).
- **After the declaration** the session is forgotten if anything eager-only happened; otherwise every entry it
  wrote is a plain value.

What this argument assumes is listed in section 6.

## 6. Assumptions, and what is not covered

1. **The evaluation abstraction** (inherited from v0): the official procedure on terms and the machine's on
   values ask the same queries up to instantiation, beta and zeta; two terms with one value are answered alike.
   In plain mode alone this is harmless where it is wrong in detail (the plain procedure does not look at its
   history). Across the modes it leaves one gap that the machine cannot close by itself: **a computation the
   official kernel performs and the machine skips because the two operands are one value** (`a == b`, or
   `Tt == D` at a site). The official kernel compares two beta-variants by reducing both, and what it caches on
   the way (a `whnf_core` result that went through K, the pairs of the K comparison) is plain-derived or
   eager-derived knowledge the machine never sees.
   - Witnessed half, covered: `e1-skip-krule-plain-redex-then-eager-ok` (the K reduction of `R p` happens in plain
     mode only inside the comparison of `R p` with `(fun z => z) (R p)`; then `R p = 7` is needed under ER; the
     official kernel reads 7 from its WHNF cache and ACCEPTS): the machine reaches the K comparison under ER, an
     eager-only event occurs inside it, rule 3 declines. Without rule 3 the machine would reject (K stuck).
     `e1-skip-identity-both-bad` (official REJECT: `R p` is 7 from the cache, the beta-variant `R' p` is computed
     under ER and stays stuck) is declined by the same rule; without it the machine would ACCEPT (both stuck,
     compared argument by argument). Rule 3 is therefore not optional.
   - Not covered, not demonstrated: both the plain and the eager computation of such a recursor are skipped by
     value identity (the eager comparison is between two values that are one record although the official terms
     differ in a K recursor's index or major premise). I could not produce such an export (in
     `e1-skip-identity-both-bad` the two forms are different records because their arguments are different
     suspensions) and I cannot exclude one: identity depends on when a suspension was forced. Design (B)
     and the reviewer's "unique variables in a session of its own" have the same gap; only an engine on terms
     (the reference engine) is free of it.
2. **Fold and unfold agree** for the Nat operations of the prelude: used nowhere in the argument (a recorded point
   whose two values differ declines), but it is why real declarations are kept.
3. `facts` (not in the default set): static PROOF reads manifest sorts only; not analysed further. The hostile
   matrix runs with `facts` and `all`. `k2` is not used under ER (`Rules`), as before.
4. A declaration is declined although the official kernel decides it when (a) a recorded pair has two values
   but is never asked under ER (`e1-bad-pair-plain-only-with-other-eager-ok`), (b) rule 3 fires although the K
   comparison has one value (`e1-krule-benign-eager-alone-ok`), (c) more than 4096 points are recorded.
5. Resource behaviour: a probe computes `whnf` of an argument that the official plain procedure does not
   compute at that point; a deferred comparison runs after plain work that the official kernel would not have
   reached had the comparison failed. Both can only turn into a decline (budget), never into a verdict.
6. **Findings E1-F1 .. E1-F5 (independent of this work, in the published default engine):** five wrong ACCEPTs,
   all of one pattern: a type that is CONVERTIBLE with the official one is used in its place, and the official
   relation is not transitive (package S's triple). F1: a `let` variable gets the type of its value (v0, default;
   gated patch `work/e1/patches/let/`). F2: the bound variable of a beta redex whose type is inferred again without
   checking gets the type of the argument (v0, default). F3: a lambda's bound variable gets the domain of the Pi it
   is compared with (v0; through the driver's strict re-check also the default pipeline). F4: under `expect` the
   body of a lambda is compared with the expected codomain under a variable of the lambda's own domain. F5: under
   `expect` the expected codomain is stored as the lambda's codomain. Reproducers with official verdicts:
   `work/e1/exports/e1-app-type-*`, `e1-let-*`, `e1-lam-*`, `e1-redex-*`, `e1-expect-*` (rows of the S manifest: the
   let rows strict, F2 .. F5 with the known-finding mark `bind`); board lines of 20:10Z - 20:35Z; what I think is
   needed: `work/e1/patches/E1-F2-F5-design.md`. For this document they mean: the machine's plain phase can ask
   queries the official kernel does not ask (and the reverse), so "official up to evaluation" holds for the eager
   mechanism only as far as it holds for the plain machine.

## 7. What was tested, and the numbers

All runs on trees under `work/e1/` (never the main tree); JVM (C2), loaded host unless said otherwise. The gated
tree is `work/e1/frozen/f7` = the main tree of 20:19:58Z + the let patch + the eager patches (`work/e1/tree2`).

### 7.1 Gates

| Gate | Result |
|---|---|
| fast test group of the module (`work/e1/out/test-fast-f7.out`; incl. `SHostileEagerTest`, 3 tests) | 415 tests, 0 failures, 3 skipped |
| differential, machine `default,eager` against the reference engine per declaration (M8's harness; reports `work/e1/root/work/status/machine-diff-*-f7.txt`) | tutorial 395 of 395, bugs 1,428 of 1,428 (was 1,427: `rec-of-subst-prop` unit 128 is REJECTED by the machine itself now), Cedar slice 8,418 of 8,418, reachability 4,413 of 4,413, Init 58,547 of 58,547, Std 101,146 of 101,146: **DIFF-CLEAN, machine declined 0, SOUNDNESS 0, COMPLETENESS 0**; the 16 Mathlib slices `work/exports/slices/good/Mathlib.*.ndjson` 436,408 of 436,408 units, 16 files clean; all 230 published tests 17,493 of 17,493, 230 files clean (without the switch: 5 declines in 2 files) |
| the same with `warm,gated,expect,eager` (tree `f5`, before the let merge) | tutorial, bugs, Cedar slice: DIFF-CLEAN, 0 declines |
| the same with the switch off (`default`, tree `f5`): the published rule | declines 11 / 8 / 164 / 203 on Cedar slice / reachability / Init / Std, nothing else differs |
| hostile exports, in-process (`E1Hostile` = M8's `Diff.compare` per export: loader, reference engine, one machine lane), tree `f7`: package S's 329 rows + 67 rows `e1-*`, under every switch set of `SHostileSlowTest` with `,eager` and `default,eager` (33 sets; `work/e1/out/hostile-f7.txt`) | 13,068 judgments. Flagged: 150, all on the eight rows of E1-F2 .. E1-F5 (mark `bind`; they are flagged without the switch too). No wrong verdict on a row that enters eager reduction. Machine declines: 21 per set (20 rows with the note `eager-hard`, 1 `str-lit-missing-prerequisites-bad`) |
| the same on the tree before the let merge (`f4`), 372 rows: `default,eager`, `eager`, `all,eager`; with `-Drlvm.machine.elabAt=1` (blocks compiled at first use, `Code.targConv`): `facts,tele,code,casetrees,eager`, `all,eager`, `default,eager`, `default,tele,eager`; with host depth 0: `eager`, `default,eager`, `all,eager` | in every lane: flagged only the two `let` rows (E1-F1, fixed since) |
| package S's five rows of S-F4 / S-F5 (`warm-pos-across-declarations-eager-bad`, `krule-eager-alone-bad`, `krule-memo-across-declarations-eager-bad`, `canon-fvar-pos-across-binders-eager-bad`, `krule-memo-across-binders-eager-bad`) | no wrong verdict under any set; all five are DECLINED by the new rules (a recorded point with two answers: their beta-redex form makes the machine ask `X = Y p` in plain mode; or the K rule), the reference engine gives the official REJECT |

Of the 67 new rows (official: 29 accept, 38 reject; every expectation of mine was confirmed by leanchecker): 32 do
not enter eager reduction (the E1-F1 .. F5 family and twins); 35 do: 26 are DECIDED by the machine with the official
verdict under `default,eager` (deferred comparisons in every order with plain ones, binder scopes: `e1-scope-capture-bad`
would be accepted by a deferred comparison that ran with its binder closed; nested `eagerReduce`; a site in the type of
an axiom; let variables; the Bool.true test; sessions across declarations; the K rule after a plain reduction), 9 are
declined by design (5 a recorded point with two answers, 4 the K rule). The S manifest carries them with the notes
`eager-decline` (enters eager reduction) and `eager-hard` (declined also with the switch).

### 7.2 Declines before / after (launcher `-X engine=machine`, 8 workers, `-Xmx6g`; `work/e1/out/counts.tsv`)

Distribution `work/e1/dist-b` = INT6's `work/int6/dist-b` with the machine jar of the prototype (tree `f4`); one
binary, switch off (`default`) and on (`default,eager`). "Declined" counts the driver's cold retry too (2 per unit).

| Corpus | declined, off | units | declined, on | kept | repeated runs | sessions forgotten | deferred comparisons | recorded points | eager-only events | machine ticks off -> on |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|---|
| Init | 328 | 164 | **0** | 158 | 132 | 11 | 729 | 131 | 6 | 39.47 M -> 40.44 M (+2.5 %) |
| Std | 406 | 203 | **0** | 200 | 168 | 32 | 853 | 347 | 6 | 64.77 M -> 67.24 M (+3.8 %) |
| cedar (whole export) | 846 | 423 | **0** | 419 | 275 | 53 | 3,706 | 573 | 20 | 84.74 M -> 88.22 M (+4.1 %) |
| reachability | 16 | 8 | **0** | 8 | 5 | 3 | 37 | 15 | 0 | 9.16 M -> 9.15 M |
| Mathlib | 13,422 | 6,711 | **0** | 6,629 | 1,999 | 162 | 124,364 | 1,383 | 69 | 1,249.2 M -> 1,303.8 M (+4.4 %) |

Verdict ACCEPT, `driver.declined` 0, `machineIncomplete` 0, `warmAnomalies` 0 in all ten runs; `machine.eager.declined.*`
(the new rules) 0 everywhere: on these corpora no recorded point has two answers and no eager-only event falls into a K
comparison. "kept" is smaller than "units" where a warm session answers a closed sub-term that was checked, with its
deferred comparison, by an earlier declaration of the session (6 units on Init).

### 7.3 The formerly declined units: machine against reference engine

In-process (M8's harness, one pass, thread CPU clock, loaded host: orientation, not a timing; `work/e1/units.py`,
logs `work/e1/out/units-*-{on,off}.tsv`, `ref-*-on.tsv`; tree `f5`):

| Corpus | units | machine ticks, on | machine thread CPU, on | reference thread CPU, same units | machine CPU before its decline, off |
|---|--:|--:|--:|--:|--:|
| Cedar slice | 11 | 1.67 M | 0.42 s | 0.70 s | 0.08 s |
| reachability | 8 | 26 k | 0.012 s | 0.015 s | 0.006 s |
| Init | 164 | 0.86 M | 0.15 s | 0.13 s | 0.03 s |
| Std | 203 | 3.08 M | 0.48 s | 1.62 s | 0.16 s |

Std unit 23249 (`Std.Tactic.BVDecide.BVExpr.bitblast.goCache_Inv_of_Inv._mutual`, the unit that bounds the 8-worker
wall clock of Std): reference 1,100 ms, machine 154 ms (1.10 M ticks, both runs of the repeated check included); unit
22250: 202 ms -> 117 ms. Most of these units are checked twice (the run is repeated in a fresh session: 133 of 158 on
Init, 167 of 199 on Std).

### 7.4 Warm wall clock, 8 workers

One `bench/quiet.py` window (2026-11-24T20:57Z - 21:00Z; `work/e1/warm-batch.sh`: `bench/warm.sh --no-quiet` inside
the window, so the rows of `work/e1/out/warm.tsv`, label `e1q ..`, say "loaded" although they were taken on the quiet
cores). JVM launcher, `-X engine=machine`, 8 workers, `-Xmx6g`, 10 check-only passes (`recheck`) per process,
distribution `work/e1/dist-d` (INT6's `dist-b` with the machine jar of the gated tree `f7`), the switch off and on
alternating, two rounds. Seconds; best and median of passes 2..10; CPU of the median pass:

| Corpus | Round | `default` best / median / CPU | `default,eager` best / median / CPU | median, on against off |
|---|---|---|---|---|
| Std | 1 | 0.89 / 0.93 / 6.80 | 0.79 / 0.83 / 6.36 | -11 % |
| Std | 2 | 0.96 / 1.00 / 7.44 | 0.75 / 0.78 / 6.03 | -22 % |
| cedar | 1 | 1.21 / 1.22 / 9.23 | 1.09 / 1.13 / 8.72 | -7 % |
| cedar | 2 | 1.21 / 1.24 / 9.39 | 1.10 / 1.12 / 8.90 | -10 % |

All eight processes ACCEPT. Walls of every pass are in the TSV (Std, round 1, off: 4.28 1.28 1.04 0.97 0.94 0.93 0.91
0.90 0.89 0.89; on: 5.62 1.26 0.96 0.90 0.82 0.81 0.81 0.84 0.79 0.83). Two rounds only: the two Std rounds differ by
8 % with the switch off, so the Std gain is between a tenth and a fifth; cedar's two rounds agree within 2 %.
Not measured: Mathlib, native image, 1 worker, reload mode with the switch on.

### 7.5 Measured and rejected

Running EVERY declaration aware from its start while the session is clean (`-Drlvm.machine.eager.early=true`, left in
the patch as an experiment, off): repeated runs fall (Init 129 -> 25, Std 167 -> 28, cedar 280 -> 91) but the probes
run in all declarations: machine ticks x 4.0 / x 4.6 / x 2.7 and 2 / 10 / 7 new declines (launcher, 8 workers,
`work/e1/out/early-run1.log`). NO-GO; the cheap session flag with a repeated run stays.

### 7.6 Not done

- Native image; instruction counts; mutant corpora with the switch; 4 workers through the launcher on the hostile
  exports; `facts` beyond the hostile matrix.
- No export forces a cut (budget exhaustion inside a TRY) while points are recorded.
- The gap of section 6 item 1 is argued, not demonstrated.
- Timing of Mathlib (one counts run per switch position only, cold JVM, loaded host: 90 s / 82 s process wall,
  not evidence) and of the native image.

## 8. Patches

`work/e1/patches/` (apply from the repository root with `patch -p1`):

| File | Owner | What |
|---|---|---|
| `let/LET-M1.patch`, `let/LET-M5.patch`, `let/LET-S.patch`, `let/S-resources/*.ndjson.gz` (67), `let/README.md` | M1, M5, S | finding E1-F1 (the let variable carries its declared type); the 67 rows `e1-*` of the S manifest with the official verdicts, the generator `gen/batchE.py`, the known-finding mark `bind`. Base: the main tree as copied at 20:19:58Z. Gated separately (README) |
| `eager/E1-M6.patch` | M6 | `Op.SW_EAGER` (bit 26, name `eager`), `Op.ER_K`, `Layout.NOTED`, `Debug` (switch name), `Machine` (the mechanism: `erSite`, `erNote`, `erDischarge`, `erSensitive`, state), `MachineSession` (repeated run, forgetting, statistics, the measurement aid `-Drlvm.machine.eager.units`), `MachineEngine` (statistics `machine.eager.*`) |
| `eager/E1-M4.patch` | M4 | `Conv`: the two points P1 / P2 (`lazydFold`, `convBoolTrue`): probe in an aware run, session flag otherwise; `folded`, `boolTrue`, `modeEvent`, `foldCandidate` |
| `eager/E1-M5.patch` | M5 | `Infer.twD`: the site; `Decl.accepted`: `erDischarge` |
| `eager/E1-M7.patch` | M7 | `Code.targConv`: the site |
| `eager/E1-M3.patch` | M3 | `Reduce.k_iota3 / k_iota4`: the K nesting count in `Machine.ER` under ER |
| `eager/E1-S.patch` | S | `SHostileEagerTest` (fast group), six `,eager` sets in `SHostileSlowTest`, the note `eager-hard` on 20 manifest rows |
| `E1-F2-F5-design.md` | - | findings E1-F2 .. E1-F5: reproducers, cause, what I think is needed (no patch) |

The eager patches are relative to the main tree + the let patch (`work/e1/let-tree`); together with it they reproduce
`work/e1/tree2/rlvm-machine`, except my harness change in `M8Harness.java` (the reference engine's per-unit CPU log)
and my tool `E1Hostile.java`, which are not delivered. With the switch off nothing changes but the name of one
condition (`Machine.erStop` is what `erSeen && erDeclines` was); the switch is not part of `all` or of the default
set. To make it the default: `Op.SW_DEFAULT |= SW_EAGER` after the owners' gates (mutants, native image, timing).
