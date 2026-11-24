# Are `skipargs` and `congruence` sound with respect to Lean's type theory? (reviewer S2)

2026-11-24, 16:45Z - [[END]]Z. Adversarial review of the two non-official options of the machine's conversion checker
(`-Drlvm.machine.m4=congruence,skipargs`; owner M4: `Conv.java`, `Cargs.java`; masks: `Facts.java`, `FactStore.java`,
owner M5). No production code was changed. Everything of this review is under `work/s2/` (generators `gen/`,
exports `attacks/`, `attacks-dat/`, `attacks/fuzz/`, `attacks/mut-mathlib/`, results `out/`, tools `judge.py`,
`campaign.sh`, `stats.sh`, `corpus-shortcheck.sh`, `leaders.sh`, a private copy of the module with seeded faults
`mcopy/`, an optional patch `patches/`).

Code reviewed: the main tree as published until 17:25Z. Runs: the JVM launcher of two private distributions built
from the published jars (`scripts/build.sh --published --jvm-only`): `work/s2/dist` (machine jar `ba557722c184`,
published 16:45:07Z; the first run of the hand-written attacks) and `work/s2/dist2` (machine jar `54eeb7cc50dd`,
published 17:16:51Z; everything else). Between the two only `Conv.sameShape` (switch `same`) changed; the
publication of 17:25Z changed comments of `Conv.java` / `Cargs.java` only (checked by `diff` against my copy).
Official verdicts: `rlvm-testkit/bin/testkit verdict` (`leanchecker --from-export`, Lean 4.36.0).

The question: **is every equation the two options assume a valid definitional equality of well-typed terms, so that
they can never accept a declaration that is not derivable; and are the known exports that they accept and the
official checker rejects all cases of the official ALGORITHM's incompleteness?**

## 0. Verdict

[[VERDICT]]

## 1. The shortcuts, exactly as the code implements them

Notation: `c.{ls} a_0 .. a_(n-1)` is a NEU record with a CONST head (row `c`, level list `ls`) and a spine of
`n` arguments; "telescope of `c`" = the leading SYNTACTIC `Pi` binders of the type term `decls.type(c)` (no
reduction, no instantiation); `arity(c)` their number; `lamArity(c)` the number of leading syntactic lambdas of
the value term.

### 1.1 The fact record of a constant (`Facts.sigd`, `syntaxSlow`, `sigLoop`; one immutable record per constant and engine)

| Field | How it is computed | What it claims |
|---|---|---|
| `arity` | count of leading `Pi` nodes of the type term (stops at anything else: `let`, a constant that unfolds to a Pi, ..); a telescope longer than 65,535 gets no facts | - |
| `dep[i]`, `i < min(arity, 64)` | the free de Bruijn indices of the domain TERM of binder `i`, from the node's `fvMask` (`rlvm-store` `NodeCtx`: bit `j < 63` = index `j` occurs free, bit 63 = some index >= 63; under a binder `mask >>> 1`, plus bit 63 when the body's loose range exceeds 64) translated to binder numbers (`Facts.binders`) | a SUPERSET of the binders the domain mentions |
| `manifest` bit `i`, `slvl[i]` | `Ti = INFER(A_i, env, ONLY)` under canonical variables for binders `0 .. i-1`; the bit is set iff the value RETURNED is a SORT record (no WHNF, no ENSORT), `slvl[i]` = its level over the constant's own universe parameters, stored as a structural encoding (zero / succ / max / imax / param NAME) | the domain of binder `i` has type `Sort slvl[i]` |
| `rlvl` | the same for the term after the last `Pi` | used by B7 only (static PROOF; official-exact, not under review) |
| `lamArity`, `unread` | only for definitions, theorems and opaque constants whose value is a term of the export region (a theorem kept as a proof block has none): strip the leading `lam` nodes; `read` = `binders(fvMask(body), lamArity)`; `unread = ~read` restricted to the first `min(lamArity, 64)` positions. With 64 or more lambdas every index >= 63 marks ALL far binders read | bit `i` set: the body term does not mention parameter `i` (through lets, nested binders, binder types, projections: purely syntactic occurrence). The mask errs only towards "read" |
| `F_NONE` | the type part ran out of `B_SIGD` = 65,536 ticks or met a kernel error | no facts: nothing is skipped |

`Facts.propMask(c.{ls})` (cached per CONST record and session epoch): bit `i` iff `manifest[i]` and
`isAlwaysZero(slvl[i][ls])`, where `[ls]` is the simultaneous substitution of the head's own level list
(`Values.mkConst`: 0 = identity, i.e. the levels ARE the parameters by name) with the official smart
constructors, and `isAlwaysZero` is the syntactic, exact test (zero; `max` of two such; `imax _ l` with `l` such).
So "position `i` is a proof" is established by: a manifest sort of the domain UNDER THE TELESCOPE, then
instantiation of the universe parameters at the levels of the head that is being compared. `Sort u` at `u := 0` is
a proof position, at `u := 1` or at a level parameter it is not; `Sort (imax u v)` follows `v`.

### 1.2 `skipargs`: the comparison plan of CARGS (B3, B4) - `Cargs.plan`, `skipMask`, `cargsFrom`

CARGS compares the spines of two NEU records. It is called from exactly three places:

| Call | Heads | Policy | When |
|---|---|---|---|
| c8, `Conv.k_conv2_1` (official `is_def_eq_app`) | any two heads for which `CONV(head, head) = T` and equal spine lengths; both sides are weak-head normal and stuck (lazy delta returned "unknown", full `whnf_core` changed nothing) | APP, first to last | every comparison of two applications that nothing else decided |
| lazy delta, `Conv.lstepBoth` (official `is_def_eq_args` in `lazy_delta_reduction_step`) | the SAME definition with a REGULAR hint on both sides, level lists `listsEq`, pair not in FAILED | SAMEDEF, last to first | before both sides are unfolded |
| B1, `Conv.convB1` (only with `congruence`) | the SAME constant, level lists `listsEq`, that constant a definition (any hint) or a theorem with matching level count, or a recursor, or `Quot.lift` / `Quot.ind`; equal spine lengths | SAMEDEF, under `TRY(budget)` | at the start of every pass of CONV, before anything is reduced |

With `facts` + `skipargs`, equal spine lengths and both heads CONST records of ONE row, `plan` computes a skip
mask over positions `i < min(n, arity(c), 64)` from left to right (a fact record that does not exist yet is
derived on the spot; `F_NONE` or no record: mask 0):

1. (B4) policy SAMEDEF and `n >= lamArity(c)` and `unread[i]`: SKIP; if the two arguments are not the identical
   record, `i` enters the set `differ`.
2. (B3) else if `propMask(head of side a)[i]` and `dep[i]` is disjoint from `differ`: SKIP.
3. else: compare (complete CONV).

Positions from 64 on and positions beyond the syntactic telescope are always compared. The answer is T iff every
compared pair is T. With `probes`, the quick refutation of pass A (two different literals, two different
constructors of non-proofs) runs BEFORE the plan and answers F; pass B probes the non-skipped pairs under
`TRY(256)`.

Which constants can have positions skipped:

| Head | B3 (proof positions) | B4 (unread positions) |
|---|---|---|
| definition, regular hint | yes: lazy delta same-head step; B1 | yes: the same two places |
| definition, abbrev / opaque hint; theorem (value in the export region) | only in B1 (they are unfolded before c8 is reached) | only in B1 |
| theorem whose value is a proof block | only in B1 | never (no value facts) |
| `opaque` constant, axiom, `Quot`, `Quot.mk`, inductive type, constructor | yes: c8 | never (the row has an unread mask for `opaque`, but SAMEDEF is never used for it; S: `facts-unread-opaque-head-bad`) |
| recursor, `Quot.lift`, `Quot.ind` | yes: c8 when stuck; B1 always | never (no value) |
| projection function, matcher, `casesOn`, `recOn`, `brecOn` | they are definitions: as above | as above |
| a free variable, a projection or a stuck term as head | never (no CONST head) | never |
| `unsafe` / `partial` | not in the environment (dropped by the loader; a reference is rejected) | - |

### 1.3 `skipargs`: the Prop mask of structure eta - `Conv.etasFields`

`ETAS(a, b)`: `b` is a constructor applied to exactly `nparams + nfields` arguments, its inductive type a
non-recursive structure without indices (recomputed flags). The types are compared FIRST
(`CONV(VTYPE(a), VTYPE(b)) = T`, as officially). Then for each field `i`: if
`propMask(head of b)[nparams + i]` the field is not compared; else `CONV(proj_i(a), b_(nparams+i))`. No
`differ` set (nothing else is skipped).

### 1.4 `congruence`: B1, B12, B2

| | Trigger | Assumes | Budget / on failure |
|---|---|---|---|
| B1 `convB1` | both sides NEU, same constant head as in 1.2 | CARGS(SAMEDEF) = T (with the plan of 1.2 when `skipargs` is also on) implies the two applications are convertible | `TRY(16,384)` for a regular definition, `TRY(1,024)` otherwise. T: the answer (POS recorded). F: the pair enters FAILED (lazy delta then unfolds at once), U: UNDEC; in both cases the complete procedure continues at c2. A per-declaration probe debt (2^17 ticks) stops further probes |
| B12 `convB12` / `cclo` | at c0, before forcing: both SUSP, or both LAM, or both PI, or both PII, with the SAME term node and level substitution, near frames | the frames' slots are pairwise identical or `CONV = T` (HOLE only against HOLE) implies the two closures are convertible | `TRY(1,024)`. T: the answer. F / U: forced and compared by the complete procedure |
| B2 `convCore1` | side `a` has the flag PROOF = yes (from an earlier dynamic `is_prop(infer a)` or the static B7) | proofs are compared by type BEFORE `whnf_core`: `CONV(VTYPE(a), VTYPE(b))` is the answer, T or F | no budget; the F is final (as the official F of `is_def_eq_proof_irrel`, but on the unreduced terms: a completeness difference only) |

## 2. Preconditions: what makes each equation a theorem, and where the code establishes it

"Theorem of the theory" below means: derivable with the conversion rules of Lean's type theory as the official
kernel implements them - delta (definitions and theorems), beta, zeta, eta, structure eta for non-recursive
structures without indices, iota, proof irrelevance (two terms whose common type is a proposition), congruence -
which is the theory of Carneiro's thesis (consistent relative to ZFC with inaccessibles) plus structure eta.

| # | Equation assumed | Precondition | Established by the code? |
|---|---|---|---|
| E1 (B3) | `c a_0 .. a_(n-1) = c b_0 .. b_(n-1)` when every non-skipped pair is convertible | (P1) both applications are well typed; (P2) each skipped `i` lies in the syntactic telescope, so `a_i : D_i[a_<i]`, `b_i : D_i[b_<i]`; (P3) `D_i : Sort 0` at the levels of BOTH heads; (P4) `D_i[a_<i] = D_i[b_<i]` | P2: yes (`i < min(n, arity, 64)`). P3: yes for side a (manifest sort + instantiation); for side b by the callers, who compare the level lists (lazy delta, B1) or the heads (c8) before CARGS - the plan itself does not test it (optional patch H1). P4: yes, by induction from left to right: the domain reads only binders in `dep[i]`, each of them is identical, compared equal, or itself a skipped proof position with the same property; a domain that reads an unread-and-different position is compared like data. P1: **assumed** (section 2.1) |
| E2 (B4) | `c a = c b` when the pairs at the read positions are convertible, `c` a definition or theorem | (P5) `n >= lamArity`: both sides delta-reduce and beta-reduce to the SAME body term under frames that differ only in slots the body does not mention; (P6) the mask never calls a mentioned parameter unread | P5: yes. P6: yes (the mask is the complement of a superset; boundary cases attacked in section 3). The head is unfoldable by construction of the call sites (SAMEDEF only for definition / theorem rows with matching level count) |
| E3 (ETAS mask) | `a = S.mk p f_1 .. f_k` when the types agree and the non-proof fields agree with the projections of `a` | structure eta `a = S.mk p a.1 .. a.k` at the common type, then E1 for the constructor with `differ` empty | types: compared first; shape: `etasShape`; the rest as E1 |
| E4 (B1) | `c a = c b` from CARGS = T | congruence of application (same constant, equivalent levels), plus E1 / E2 for skipped positions | yes (`b1Budget`: same row, `listsEq`, same length) |
| E5 (B12) | `t[f] = t[f']` from slot-wise convertibility | congruence of substitution; a HOLE slot is a slot the term does not read (PRUNE by the same `fvMask`) | yes (same term node, same level substitution; near frames only) |
| E6 (B2) | `a = b` for a proof `a` whose type is convertible with the type of `b` | proof irrelevance | yes: the flag is `is_prop(infer a)` (dynamic) or a manifest always-zero result sort of a saturated constant (B7) |

### 2.1 The one assumption: both sides of a comparison are well-typed terms

E1 and E3 replace the official type comparison of two proofs by an argument about the SIGNATURE of the head:
"both arguments inhabit the instantiated domain". That is true only if both applications are well typed. The
official `is_def_eq_proof_irrel` does not need it: it infers both types and compares them, so for an ill-typed
proof argument it answers F. The same holds for B4 (an ill-typed argument in an unread position is never looked at).

Where the machine guarantees it (read, and attacked in section 3 group X):

- CONV is called by inference only on (inferred type, domain) / (inferred type, expected type) / (type of a
  let value, its annotation) / (lambda domain, expected domain): `Infer.twD`, `k_check1`, `k_ck5`, the let rule,
  `Code.targConv`. In CHECKED mode every argument, every domain, every let value is inferred BEFORE the
  comparison that involves it, with `expect` (B9: the lambda's domain is inferred and ENSORTed, then compared),
  with `tele` / `code` (TARG: INFER then CONV), with `joint` (identical domain nodes are checked once), with
  `pchk` (the bit says the closed term passed CHECKED once; with the options on it says "is well typed in the
  theory", which is all the argument needs).
- Reduction calls CONV for the K rule (`Reduce.k_iota3`: type of the major against the type of the constructor
  application it builds) on types of well-typed terms; structure eta and eta build projections / applications of
  well-typed terms after their types were compared.
- The terms CONV itself builds (`proj_i(a)`, `b x`, constructor forms of literals) are well typed.
- Inductive types, constructors, recursors and their rules are NOT checked by the machine: admission uses the
  reference kernel, and exported recursor records are compared structurally (`RecordCompare`: "deliberately no
  fallback to definitional equality"). So no unchecked term enters the environment through a comparison that a
  shortcut could shorten.
- A definition whose check is still running on another worker may be unfolded; if it is ill typed its own check
  rejects it and the run is REJECT. Fact records are derived from the TYPE of a constant that is visible to the
  declaration being checked; a record derived from an ill-typed type only matters if that constant is accepted,
  which it is not.

I found no path on which a comparison meets a term that inference did not check (section 3, group X, 8 exports;
all rejected under every configuration). This is the same invariant the official kernel relies on for its own
argument-wise comparisons; what changes with `skipargs` is that a violation would no longer be caught by the type
comparison of proof irrelevance (see "what the options remove" in section 0).

### 2.2 Why "T on well-typed terms" is closed under the shortcuts (the induction, in short)

Claim: if CONV answers T for two well-typed terms then they are convertible in the theory and their types are
convertible. Every official rule preserves it (reduction preserves types; binder domains are compared; proof
irrelevance, unit-like and structure eta compare the types; an application rule compares the heads, whose types
then agree, so the argument types agree pairwise from left to right). E1 preserves it by P4. E2 preserves it
because both sides reduce to instances of ONE body term whose mentioned slots are convertible; the types of the two
sides are then types of convertible terms. E4 / E5 are congruences; E6 is proof irrelevance with the types
compared. Note that conversion is only ever ASKED about types (and, inside, about arguments whose expected types
agree by this induction); two proofs are never the top of a query, which is why even a skip without the `differ`
rule (what both leaders do, section 5) has no false consequence that I could construct.

## 3. Attacks

All exports claim their equation by `Eq.refl`, so an ACCEPT of a `-bad` export is the acceptance of a refutable
closed statement (the proof of `False` is one `Nat.noConfusion` / `absurd` away). Configurations (`work/s2/judge.py`):

| name | switches | options |
|---|---|---|
| `v0` | `none` | - |
| `rich` | `warm,gated,expect,facts,probes,tele,code,casetrees,lazyeval,same` | - |
| `skip` / `skip0` | `warm,gated,expect,facts` / `facts` | `skipargs` |
| `cong` | `warm,gated,expect,facts,probes` | `congruence` |
| `both` / `both0` / `both4` | `warm,gated,expect,facts,probes` / `facts,probes` / the first with 4 workers | `congruence,skipargs` |
| `bothrich` | the `rich` set | `congruence,skipargs` |
| `bothall` | `all,lazyeval,same,match,joint,k2`, `-Drlvm.machine.elabAt=1` | `congruence,skipargs` |

[[ATTACKS]]

## 4. The known exports that the options accept and the official checker rejects

[[KNOWN]]

## 5. The leaders

[[LEADERS]]

## 6. Not established

[[NOT]]

## 7. Reproduction

```bash
python3 -I work/s2/gen/attacks.py                      # work/s2/attacks/*.ndjson (134) + attacks.tsv
S2_DAT=1 python3 -I work/s2/gen/attacks.py             # work/s2/attacks-dat/ (the same, no literal arguments)
python3 -I work/s2/gen/fuzz.py SEED 100 work/s2/attacks/fuzz      # seeds 11..14
python3 -I work/s2/gen/mutate.py work/s2/attacks/mut-mathlib --per 120 --seed 7 work/exports/slices/good/Mathlib.Algebra.Group.Basic.cone.ndjson
scripts/build.sh --published --jvm-only --dist work/s2/dist2
bench/slot.sh -- python3 -I work/s2/judge.py --manifest work/s2/attacks/attacks.tsv --out OUT.tsv -j 3   # one line per export
bench/slot.sh -- work/s2/campaign.sh                   # known exports, leaders (Docker), corpora with shortcheck, mutants, seeded faults
work/s2/stats.sh congruence,skipargs warm,gated,expect,facts,probes FILE     # one run with the shortcut counters
```
