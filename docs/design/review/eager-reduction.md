# Eager reduction on the machine (package E1)

2026-11-24. Prototype and evidence: `work/e1/` (tree `work/e1/tree/rlvm-machine`, patches `work/e1/patches/`,
hostile exports `work/e1/exports/` with official verdicts in `work/e1/manifest.tsv`, living summary
`work/status/prof-E1.md`). The main tree is unchanged: the mechanism is a patch behind the switch `eager`
(`Op.SW_EAGER`, bit 26; off = the published rule M6-F26, a declaration that enters eager reduction is declined).

Ground truth everywhere: the official checker (`leanchecker --from-export`, Lean v4.36.0, in the arena Docker
image, `work/e1/official.sh`), and `refs/lean4/src/kernel/type_checker.cpp` for the reading of the procedure.

## 1. Result in one page

RESULT-PLACEHOLDER

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
6. **Finding E1-F1 (independent of this work, in the published default engine):** the machine gives a `let`
   variable the type of its value, the official `infer_let` the declared type. `theorem S.bad : d h = 5 := let q :
   d h = d h := Eq.refl (d intro); q` (package S's non-transitive triple) is ACCEPTED by the machine in v0 and in
   the default set (`work/int6/dist-b`, default engine, exit 0), REJECTED by the official checker and by the
   reference engine. Exports `e1-app-type-is-domain-let-bad`, `-let-under-lambda-bad`; the beta-redex forms are
   judged correctly. Owner: M5 (the let rule of `Infer`) / M2. It also means that the machine's plain phase can
   ask queries the official kernel does not ask.

## 7. What was tested, and the numbers

TESTED-PLACEHOLDER

## 8. Patches

PATCHES-PLACEHOLDER
