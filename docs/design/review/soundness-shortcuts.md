# Are `skipargs` and `congruence` sound with respect to Lean's type theory? (reviewer S2)

2026-11-24, 16:45Z - 18:30Z. Adversarial review of the two non-official options of the machine's conversion checker
(`-Drlvm.machine.m4=congruence,skipargs`; owner M4: `Conv.java`, `Cargs.java`; masks: `Facts.java`, `FactStore.java`,
owner M5). No production code was changed. Everything of this review is under `work/s2/` (generators `gen/`,
exports `attacks/`, `attacks-dat/`, `attacks2/`, `attacks/fuzz/`, `attacks/mut-mathlib/`, certificates `certify/`,
results `out/`, tools `judge.py`, `campaign.sh`, `campaign2.sh`, `stats.sh`, `corpus-shortcheck.sh`, `leaders.sh`,
`leaders-summary.py`, `seeded-summary.py`, a private copy of the module with seeded faults `mcopy/` and its
distribution `dist3/`, an optional patch `patches/`).

Code reviewed: the main tree as published until 17:25Z. Runs: the JVM launcher of two private distributions built
from the published jars (`scripts/build.sh --published --jvm-only`): `work/s2/dist` (machine jar `ba557722c184`,
published 16:45:07Z; the first run of the hand-written attacks) and `work/s2/dist2` (machine jar `54eeb7cc50dd`,
published 17:16:51Z; everything else). Between the two only `Conv.sameShape` (switch `same`) changed; the
publication of 17:25Z changed comments of `Conv.java` / `Cargs.java` only (checked by `diff` against my copy); the
jar published at 18:12Z was not run: `Conv`, `Cargs`, `Facts`, `FactStore`, `Pairs`, `Infer`, `Reduce`, `Values`
and `Levels` are unchanged in the tree at 18:27Z.
Official verdicts: `rlvm-testkit/bin/testkit verdict` (`leanchecker --from-export`, Lean 4.36.0).

The question: **is every equation the two options assume a valid definitional equality of well-typed terms, so that
they can never accept a declaration that is not derivable; and are the known exports that they accept and the
official checker rejects all cases of the official ALGORITHM's incompleteness?**

## 0. Verdict

**Theory-sound as implemented; I could not break either option.** Every equation that `skipargs` (B3: proof
positions of CARGS; B4: unread positions; the Prop mask of structure eta) and `congruence` (B1: same-head probe;
B12: closure congruence; B2: proofs by type before reduction) assume is derivable in Lean's type theory from
delta, beta, zeta, structure eta, proof irrelevance and congruence, under ONE precondition that the options do
not establish themselves and that the machine's inference establishes for them: both sides of a comparison are
well-typed terms (2.1). The masks are computed soundly (a proof position needs a manifest sort of its domain that
is always zero AFTER instantiation at the levels of the head being compared; an unread position comes from a
superset of the occurrences in the body and is used only where both sides unfold; a proof position whose domain
mentions an unread position that differs is compared, a precaution neither leader takes). No refutable or
underivable claim was accepted under any configuration, and the exports that the options accept against the
official verdict are all cases of the official ALGORITHM's incompleteness, not of a false equation: for each of
them the official checker itself accepts a proof of the same statement once the congruence step is written out
as a term (section 4). **A named opt-in profile with the two options is as sound as the machine's inference is;
it is not the official verdict on exports of the kind of section 4, exactly as gruszka's is not.**

The evidence, in numbers:

- False claims REJECTED under every configuration with the options (sections 3.1 - 3.3, 3.7): 95 hand-written
  ones under nine configurations and their 95 non-literal twins; 6 of a second batch (`Acc.rec`, `Quot`,
  unit-like, structures in Prop); 362 random ones; 358 targeted mutants of a Mathlib slice; none accepted of the
  2,040 officially rejected mutants of the arena's `bugs` / `other` tests (1,977 rejected, 63 declined by the
  pipeline); the 289 officially rejected exports of package S outside the class of section 4; the arena's 92
  `bad` tests.
- On nine exports up to Mathlib's `Scheme` slice, 474,228 T answers of the shortcuts were re-derived by the
  complete official procedure without one disagreement (3.6).
- The suite does catch seeded faults of the masks: an off-by-one position, an off-by-one de Bruijn index, a wrong
  universe instantiation (3.4).
- Accepted with an option, rejected officially: 14 of the 304 officially rejected exports of package S (the 13
  rows of S-F1 / S-F2 and `open-flag-closed-bad`, which was in no list) and one `Acc.rec` analogue written for
  this review. All 15 statements are proved for the official checker by `Eq.rec` over an `Eq.refl` between two
  proofs (`work/s2/certify/`, 15 of 15 accepted officially).
- With the options OFF nothing differs from the official verdict (`v0` on 1,235 exports, `rich` on 603: the
  official verdict or a decline of the pipeline on every one).

What the owner should weigh before a named opt-in profile uses them:

1. **They change verdicts, by design, and only towards accepting theorems.** Exports exist that the official
   checker rejects and the profile accepts (section 4). Both leaders accept exports of the same class (section
   5): gruszka accepts all 14 exports of package S that the profile accepts, nano-lean 6 of them, and both accept
   three more that the machine rejects even with the options. The arena's 92 `bad` tests are ALL rejected under
   `skip`, `cong`, `both` and `bothrich` (as under `v0`), including the two whose statements are true in the
   theory and that the arena nevertheless requires to be rejected (`125_accRecNoEta`, `101_ruleKAcc`): the
   options never build a constructor form out of a proof. An arena test of the kind of section 4 would be
   accepted by the profile - and by gruszka.
2. **They remove a redundancy.** The official comparison of two proofs compares their TYPES, which also catches
   an ill-typed proof argument that inference let through; `skipargs` does not look at a proof or unread
   position at all. With the options, soundness rests on inference alone. I found no path on which an unchecked
   term reaches a comparison (2.1, group `ctx` of the attacks), and a seeded inference fault shows the difference
   concretely (3.5: v0 rejects, the options accept). A profile with the options should therefore run where the
   inference side has the evidence it has today (the differential gates), not as a place to try inference
   shortcuts.
3. **No patch is needed.** One optional line of defence in depth is offered
   (`work/s2/patches/H1-cargs-plan-levels.diff`, owner M4, applies and compiles on a private copy): the plan of
   CARGS would test the equivalence of the two heads' level lists itself instead of relying on its three callers.
4. **One row is missing from the guards**: `open-flag-closed-bad` is accepted under `congruence` and is neither
   in `M4OfficialTest`'s list nor marked in package S's manifest (it was written after M4's fix). It is of the
   same class and changes nothing in the verdict; a profile's list of expected differences should contain it.

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
| `opaque` constant, axiom, `Quot`, `Quot.mk`, inductive type, constructor | yes: c8 | never (SAMEDEF is never used for these heads; `syntaxSlow` would compute an unread mask for an `opaque` row, in the runs of this review such a row had none; S: `facts-unread-opaque-head-bad`) |
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

Where the machine guarantees it (read, and attacked in section 3, group `ctx`):

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

I found no path on which a comparison meets a term that inference did not check (section 3, group `ctx`, 8 exports;
all rejected under every configuration). This is the same invariant the official kernel relies on for its own
argument-wise comparisons; what changes with `skipargs` is that a violation would no longer be caught by the type
comparison of proof irrelevance (section 0, point 2; section 3.5).

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

All exports claim their equation by `Eq.refl`. A `-bad` export claims a closed equation that is refutable (two
different numerals, Booleans, constructor fields: `False` is one `noConfusion` away) or at least not derivable
(an axiom applied to different data); a `-true` export claims a derivable one and shows that the shortcut under
attack is reached. Configurations (`work/s2/judge.py`; A = exit 0, R = exit 1, D = exit 2 of the launcher):

| name | switches | options |
|---|---|---|
| `v0` | `none` | - |
| `rich` | `warm,gated,expect,facts,probes,tele,code,casetrees,lazyeval,same` | - |
| `skip` / `skip0` | `warm,gated,expect,facts` / `facts` | `skipargs` |
| `cong` | `warm,gated,expect,facts,probes` | `congruence` |
| `both` / `both0` / `both4` | `warm,gated,expect,facts,probes` / `facts,probes` / the first with 4 workers | `congruence,skipargs` |
| `bothrich` | the `rich` set | `congruence,skipargs` |
| `bothall` | `all,lazyeval,same,match,joint,k2`, `-Drlvm.machine.elabAt=1` | `congruence,skipargs` |
| `bothcap` | as `both`, `-X machine.session-kib=1` (a session reset before nearly every declaration) | `congruence,skipargs` |

**Outcome of all of them: no export that the official checker rejects for a FALSE (underivable) claim is accepted
under any configuration. No accept exists with the options off that the official checker does not give.**

### 3.1 Hand-written attacks (`work/s2/gen/attacks.py`, `work/s2/attacks/`, 134 exports: 96 `-bad`, 38 `-true`)

Judged under all nine configurations of the table (`work/s2/out/attacks-judged.tsv`, jar `ba557722c184`) and
again under `skip`, `both`, `bothall`, `bothcap` on jar `54eeb7cc50dd` (`attacks-judged2.tsv`): the same verdicts.

| Group | Exports | What is attacked | Machine, every configuration | Official |
|---|--:|---|---|---|
| `lvl-*` | 17 bad, 2 true | the Prop mask and universe levels. `f.{u} : (A : Sort u) -> A -> Nat` as an axiom and as a definition with hint regular / abbrev / opaque; after `f.{0} True p1 = f.{0} True p2` (true; fills the mask cache at `u = 0`) the claim `f.{1} Nat 1 = f.{1} Nat 2`; the same under the theorem's own level parameter (`thm.{u} : forall A a b, f.{u} A a = f.{u} A b`, identity substitution), under a renamed parameter and `max u 0`; `Sort (imax v u)` with the parameters listed in the other order; a domain `True -> A` of sort `imax 0 u`; both instances in ONE declaration; `Box.{u} (A : Sort u) := (val : A)` through structure eta (`Box.mk.{1} Nat (succ b.val) = b`) and through constructor heads | bad: REJECT; true: ACCEPT | the same |
| `rec-*`, `quot-*` | 5 bad, 1 true | stuck recursors and B1 on recursor / quotient heads: `Eq.rec.{1,1}` with different minor premises on an axiom `h : a = b` after a pair at `u = 0` where the minor is a proof; different indices; `And.rec`, `Nat.rec` with different minors; `Quot.lift.{1,1}` with different functions after a pair at `v = 0` (the function is a proof there) | bad: REJECT; true: ACCEPT | the same |
| `unread-*` | 48 bad, 34 true | B4. The parameter occurs only in a minor premise of a recursor / as a let value / in a let body / as the argument of a beta redex / below a projection / under an inner lambda / in a binder domain; under-application and over-application; an alias type hiding the Pis; an abbrev and an opaque-hint definition (B1). de Bruijn arithmetic: definitions with 3, 63, 64, 65, 70, 130 parameters whose body is parameter `j` for `j` at every boundary (0, 1, 62 .. 69, 127 .. 129): the `-bad` twin changes exactly `j`, the `-true` twin changes every other parameter; nested binders below 66 unread parameters; a theorem head next to a data position; the `differ` rule (true and refutable variants) | bad: REJECT; true: ACCEPT | the same |
| `prop-*` | 8 bad, 1 true (+ 1 declined) | what counts as a proposition: a domain that IS `Prop`; a Bool position after a proposition; data after a proof; a function type; over-application through a definition that unfolds to a Pi; a sort hidden behind a definition; 70 positions with data at 64 and 65; data at 63 between proofs; `@Eq.{0} True p1 p2 = @Eq.{0} True p3 p4` (true). `prop-mdata-domain-bad` is declined by the pipeline in every configuration, v0 included (mdata around a definition's lambdas; nothing to do with the options) | bad: REJECT (1 DECLINE); true: ACCEPT | the same (the declined one: reject) |
| `etas-*` | 5 bad | the ETAS mask: the data field after two proof fields; a field of type `Prop` before its proof; a proof PARAMETER; structure eta below structure eta | REJECT | reject |
| `cong-*` | 4 bad | B12 / B1 / B2 with data: one closure body under frames `[1]` and `[2]`; a lambda closure below a constant; frames whose slots are proofs of different propositions; a theorem head whose applications prove different propositions below a function that keeps the proposition | REJECT | reject |
| `ctx-*` | 8 bad | section 2.1: an ill-typed proof argument that conversion would skip - in the statement, in the value (`Eq.refl (f 1 (pf 2))` for the statement `f 1 (pf 1) = f 1 (pf 1)`), in a lambda argument checked against an expected type (B9), in an unread position of a definition, in a let value, in the type of a definition, in a later declaration after six warm ones with the well-typed twin of the node, on one side of a data claim | REJECT (by inference) | reject |

The same 134 with every Nat literal written `Nat.add k 0` (`work/s2/attacks-dat/`, `S2_DAT=1`), so that the quick
refutation of pass A cannot answer before the plan in `both` (with literal arguments the plan paths of B4 were
reached under `skip` only): under `v0`, `skip`, `both`, `bothall` the 95
`-bad` exports are REJECTED, the 38 `-true` exports ACCEPTED, the mdata one is declined
(`work/s2/out/attacks-dat-judged.tsv`); the official verdicts are those of the literal twins.

### 3.2 Second batch (`work/s2/gen/attacks2.py`, `work/s2/attacks2/`, 11 exports)

Base for the `Acc` exports: the arena's `124_accRecReduction` (it declares `Acc`, `Eq`, `Bool`). Configurations `v0`,
`rich`, `skip`, `cong`, `both`, `bothrich`, `bothall` (`work/s2/out/attacks2-judged.tsv`).

| Export | Claim | Machine | Official |
|---|---|---|---|
| `acc-rec-minor-bad` | after a stuck pair of `Acc.rec.{0,1}` with two axioms as minor premises (proofs), `Acc.rec.{1,1} (fun _ _ _ => true) h = Acc.rec.{1,1} (fun _ _ _ => false) h` | REJECT, all 7 | reject |
| `acc-rec-intro-vs-axiom-minor-bad` | `Acc.rec (fun _ _ _ => true) (Acc.intro true k) = Acc.rec (fun _ _ _ => false) h` | REJECT, all 7 | reject |
| `acc-rec-major-true` | `Acc.rec F h = Acc.rec F h2`, two axioms of `Acc R true` | ACCEPT, all 7 | accept |
| `acc-rec-intro-vs-axiom` | `Acc.rec F (Acc.intro true k) = Acc.rec F h`: the `Acc` analogue of S-F1, TRUE (section 4) | REJECT under `v0`, `rich`, `skip`; **ACCEPT under `cong`, `both`, `bothrich`, `bothall`** (B1 on the recursor head) | reject |
| `acc-rec-no-eta-axiom` | the arena's `125_accRecNoEta` on an axiom: `Acc.rec (fun _ _ _ => true) h = true`; true in the theory, `bad` for the arena | REJECT, all 7 | reject |
| `quot-mk-data-bad` | `Quot.mk r 1 = Quot.mk r 2` | REJECT, all 7 | reject |
| `quot-lift-mk-vs-axiom` | `Quot.lift f h (Quot.mk r 1) = Quot.lift f h q`, `q` an axiom (DATA: not derivable) | REJECT, all 7 | reject |
| `quot-lift-proof-true` | `Quot.lift f h1 q = Quot.lift f h2 q` | ACCEPT, all 7 | accept |
| `unitlike-family-bad` | `W.mk 1 (UF.mk 1) = W.mk 2 (UF.mk 2)`, `UF n` unit-like | REJECT, all 7 | reject |
| `prop-struct-arg-true` | `g (PS.mk 1 r1) = g (PS.mk 2 r2)`, `PS : Prop` with a Nat field | ACCEPT, all 7 | accept |
| `prop-struct-data-wrapper-bad` | `k 1 (PS.mk 1 r1) = k 2 (PS.mk 2 r2)`, `k n p := n` | REJECT, all 7 | reject |

### 3.3 Random exports (`work/s2/gen/fuzz.py`, seeds 11 - 14, `work/s2/attacks/fuzz/`, 498 exports)

One random constant per export - an axiom (125 exports), a definition with hint regular (151) / abbrev (56) /
opaque (63), or the constructor of a random structure (103) - over a random telescope of 2 to 8 positions drawn from:
a sort `Sort u_k` (level parameters shared or not), an element of an earlier sort (a proof when that sort is
instantiated at 0, a Nat or Bool at 1), a Nat, a proof of `Q n` for an earlier Nat, a proposition, a proof of an
earlier proposition, a proof of `True`, a member of an axiom family `Fam n`, a function, a proof of a Pi into
Prop. A definition's body reads a random subset through axioms into Nat, each read wrapped at random in a let, a
beta redex, an inner lambda or a `Nat.rec`. Each export makes 4 to 12 TRUE claims (the sides differ in proofs,
in the representation of equal data, and in unread parameters, at several level vectors); a `-bad` export ends
with one FALSE claim that changes the value of one data position that matters. The official verdict was the
predicted one for all 498.

| | exports | `v0` | `skip` | `both` | `skip0` | official |
|---|--:|---|---|---|---|---|
| `-bad` | 362 | 362 R | 362 R | 362 R | 362 R | 362 reject |
| `-true` | 136 | 136 A | 136 A | 136 A | 136 A | 136 accept |

### 3.4 Do the attacks have teeth? Seeded faults (`work/s2/mcopy/`, `work/s2/dist3`, `-Ds2.bug=N`)

A private copy of the module with one fault at a time, `skip` configuration, on the `-bad` exports of 3.1, of
half of 3.3 and of package S's `facts-*` / `mask-*` (301 exports: 96 + 183 + 22). "Caught" = at least one false claim is
ACCEPTED by the faulty build (so the suite would have shown the fault).

| Fault | What is broken | False claims ACCEPTED that the unmodified build rejects | Caught by |
|---|---|--:|---|
| 1 | `propMask`: a manifest sort that MAY be zero (a level parameter) counts as a proposition | 8 | `lvl-*-generic-bad`, `-renamed-bad` (8). The random exports cannot catch it: their claims are closed |
| 5 | `propMask` shifted by one position | 41 | fuzz 35, `etas` 2, S's `facts` 2 and `mask` 1, `prop` 1 |
| 2 | `unreadMask` shifted by one position | 18 | `unread` 11, fuzz 6, S's `facts` 1 |
| 7 | de Bruijn arithmetic of the unread mask off by one (`binders(mask, lam + 1)`) | 40 | `unread` 21, fuzz 13, `prop` 3, S's `facts` 3 |
| 4 | ETAS mask indexed by the field number instead of `nparams + field` | 2 | `etas-param-proof-bad`, one random structure |
| 6 | the unread mask also under APP | 0 | no effect to catch: no head that reaches c8 has an unread mask (the one candidate, an `opaque` constant, has no value facts: `facts.use.unreadSet` stays 0) |
| 3 | the `differ` rule dropped (a proof position is skipped whatever its domain reads) | 0 | consistent with 2.2: I could not build a false claim from the absence of the rule, and none of the 301 is one |

So a mask that is wrong by a position, by a de Bruijn index or by a universe instantiation IS shown by the suite
(`work/s2/out/seeded-bug*.tsv`, `work/s2/seeded-summary.py`); the suite is weakest on the ETAS index (2 exports).

### 3.5 What the options remove: a seeded fault of INFERENCE

Fault 10 of the private copy makes `Infer.twD` skip the comparison of an argument's type with its domain (an
inference bug). `ctx-wrong-proof-in-value-bad` - statement `f 1 (pf 1) = f 1 (pf 1)`, value
`Eq.refl (f 1 (pf 2))`, `pf 2 : Q 2` where `Q 1` is required - is then still REJECTED by v0, because the official
comparison of the two proof arguments compares their types (`Q 2` against `Q 1`), and ACCEPTED under `skip` and
`both`, which do not look at a proof position. With the options, conversion is no longer a second check of what
inference let through.

### 3.6 Real corpora under `shortcheck` (`work/s2/corpus-shortcheck.sh`, `both`, one worker)

Every T that skipped a position (CARGS, ETAS) or came from B1 / B12 is re-derived at once by the complete
official procedure with all switches off; a T that the complete procedure does not find is an audit failure
(`E_AUDIT`, the unit is declined). A wrong mask would show here as soon as a comparison that the official
procedure answers F is answered T (failed same-head comparisons of unequal data are frequent in these files).

| Export | units | accepted by the machine | declined | T answers of a shortcut that were re-derived (`audits`) | not found by the complete procedure (`E_AUDIT`) | re-derivation out of budget (inconclusive) | B1 T | B3 plans | B4 plans | B12 T |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| `init-prelude` (arena test) | 1,710 | 1,710 | 0 | 324 | **0** | 0 | 139 | 124 | 67 | 3 |
| `Mathlib.Algebra.Group.Basic.cone` | 1,724 | 1,720 | 4 | 5,204 | **0** | 2 | 2,880 | 133 | 2,185 | 16 |
| `Mathlib.Data.Nat.Basic.cone` | 2,711 | 2,701 | 10 | 5,656 | **0** | 14 | 3,706 | 200 | 1,501 | 271 |
| `Mathlib.Topology.Basic.cone` | 3,318 | 3,316 | 2 | 6,371 | **0** | 23 | 3,530 | 692 | 2,025 | 276 |
| `Mathlib.Data.Fintype.Basic.cone` | 4,520 | 4,492 | 28 | 10,033 | **0** | 41 | 5,917 | 1,128 | 2,821 | 366 |
| `Mathlib.Data.List.Basic.cone` | 4,278 | 4,204 | 74 | 10,460 | **0** | 23 | 6,307 | 1,069 | 2,745 | 458 |
| `Mathlib.Order.Basic.cone` | 7,663 | 7,655 | 8 | 64,058 | **0** | 574 | 44,339 | 1,705 | 18,321 | 380 |
| `Mathlib.GroupTheory.Sylow.cone` | 13,663 | 13,619 | 44 | 70,112 | **0** | 1,072 | 41,396 | 4,508 | 25,001 | 638 |
| `Mathlib.AlgebraicGeometry.Scheme.cone` (P8's slice) | 21,155 | 21,099 | 56 | 317,642 | **0** | 13,883 | 185,816 | 24,089 | 119,640 | 4,301 |

All 226 declines are the eager-reduction rule (`E_LIMIT`, `eagerDeclines`), none is an audit failure. On these
files every T of a shortcut whose re-derivation completed is also a T of the complete official procedure
(489,860 audits; 15,632 of them = 3.2 % ran out of the audit budget of 65,536 ticks and are NOT confirmed):
the masks never turned an official F into a T. (The control: the same script on S's
`facts-agreement-noncongruent-app-bad` reports the two `E_AUDIT` declines it should.) This is evidence for the
masks on real signatures, not a proof: a wrong mask bit on a position whose arguments never differ in these files
would not show.

### 3.7 Mutants: a Mathlib slice and the official `bugs` corpus

The official mutant corpus `work/mutants-bugs-other` (mutants of the arena's `bugs`, `other`, `corner-cases` tests,
22 kinds, classified by the official checker): the 2,040 mutants that the official KERNEL rejects, under `skip`
and `both` (`work/s2/out/mutants-bugs-judged.tsv`): **1,977 REJECT, 63 DECLINE (declines of the pipeline: never an
accept), 0 ACCEPT.**

**Targeted mutants of a Mathlib slice** (`work/s2/gen/mutate.py` = S's mutator restricted to the kinds `SIBLING_ARG`,
`LIT_SUCC_NE`, `LEVEL_ARG`):

360 mutants of `Mathlib.Algebra.Group.Basic.cone` (1,724 units; each mutant edits ONE occurrence inside ONE
declaration: the argument of an application replaced by the argument of another application of the same function,
a Nat literal `n` by `Nat.succ n`, one universe argument of a constant by another level; 174 in a declared type,
186 in a value). `work/s2/out/mut-mathlib-judged.tsv`; the 360 files (1.9 GB) were deleted after the run, their
descriptions and SHA-256 sums are in `work/s2/attacks/mut-mathlib/` and the command of section 7 regenerates them:

| kind | mutants | official reject | of them rejected under `skip` and `both` | official accept (accepted under both) |
|---|--:|--:|--:|--:|
| `SIBLING_ARG` | 120 | 118 | **118** | 2 |
| `LIT_SUCC_NE` | 120 | 120 | **120** | 0 |
| `LEVEL_ARG` | 120 | 120 | **120** | 0 |

In a sample of 12 of them the machine's rejection is `E_APP_MISMATCH` (the type of an argument against its domain: a
comparison) in 9 and a declaration-level type mismatch in 1 more: these mutants are decided by comparisons of the
kind the options shorten, on Mathlib's own signatures (instance arguments, proof fields).

## 4. The known exports that the options accept and the official checker rejects

Notation: `I = And.intro True True True.intro True.intro`; `h`, `h2` axioms of `And True True`;
`R x = And.rec (motive := fun _ => Nat) (fun _ _ => 5) x`; PI = proof irrelevance; "cong" = congruence of
application / of substitution. Verdicts of this review's run (jar `54eeb7cc50dd`, `work/s2/out/known-judged.tsv`):
A = accepted, R = rejected; the official checker rejects every row.

| Export | Claim | `skip` | `cong` | `both` | Derivation in the theory | Class |
|---|---|:-:|:-:|:-:|---|---|
| `probe-b1-rec-proof-major-bad`, `-flipped-bad` (S-F1; pinned by `M4OfficialTest`) | `R I = R h` | R | A | A | `h = I` by PI (both inhabit the proposition `And True True`); cong under `And.rec .. _`. (Then `R I = 5` by iota, beta: the official procedure reduces the left side and cannot move the right) | TRUE; official incompleteness |
| `probe-b12-closure-proof-slot-bad` (S-F1; pinned) | `(fun x => g (R x)) I = (fun x => g (R x)) h` | R | A | A | beta on both sides, then the row above under `g` | TRUE |
| `probe-b12-let-proof-slot-bad` | `(let x := I; g (R x)) = (let x := h; g (R x))` | R | A | A | zeta, then as above | TRUE |
| `probe-b12-lam-closure-proof-slot-bad` | `(fun x => g (fun n => R x)) I = (..) h` | R | A | A | beta; cong under the lambda (xi) and `g`; `R I = R h` | TRUE |
| `probe-b1-abbrev-proof-arg` | `F I = F h`, `F := fun x => R x` an abbrev | R | A | A | `I = h` by PI, cong under `F` (or delta on both sides and the first row). Officially an abbrev head is unfolded without comparing arguments | TRUE |
| `probe-b1-theorem-head` | the same, `F` with hint opaque | R | A | A | the same | TRUE |
| `probe-b1-rec-under-ctor-bad` | `Nat.succ (R I) = Nat.succ (R h)` | R | A | A | the first row under `Nat.succ` | TRUE |
| `probe-b1-rec-under-axiom-bad` | `g (R I) = g (R h)` | R | A | A | the first row under `g` | TRUE |
| `facts-agreement-noncongruent-app-bad` (S-F2; pinned) | `w I p1 = w h p2`, `w : (x : And True True) -> Q (R x) -> Nat`, `p1 : Q (R I)`, `p2 : Q (R h)` | A | A | A | `I = h` by PI; so `Q (R I) = Q (R h)` by cong; `p1`, `p2` are then proofs of one proposition: `p1 = p2` by PI; cong under `w` | TRUE |
| `facts-agreement-noncongruent-samedef-bad` | the same, `w` a regular definition | A | A | A | the same (or delta first) | TRUE |
| `facts-etas-prop-mask-noncongruent-bad` | `S.mk I p1 s.3 = s`, `s` an axiom of the structure | A | A | A | structure eta: `s = S.mk s.1 s.2 s.3`; `s.1 = I` by PI; `s.2 : Q (R s.1) = Q (R I)`, so `s.2 = p1` by PI; cong under `S.mk` | TRUE |
| `facts-unread-noncongruent-bad` | `d 0 I = d 1 h`, `d u x := R x` | A | R | R | delta, beta: `d 0 I = R I`, `d 1 h = R h`; the first row. (`both` rejects only because the quick refutation of pass A answers F on `0` / `1` before the plan; the equation is the same) | TRUE |
| `open-flag-closed-bad` (package S, batch 8; NOT in the list of S-F1 / S-F2, it was written after M4's fix) | `Nat.add (G I) 0 = Nat.add (G h) 0`, `G x := And.rec (fun _ _ => 0) x` regular, both sides closed | R | A | A | `I = h` by PI, cong under `G` and `Nat.add _ 0`. Officially both sides are closed, so the left side is folded to `0` before any same-head comparison, and `0` against the stuck right side is false; B1 compares the arguments of the two `Nat.add` first | TRUE |
| `acc-rec-intro-vs-axiom` (new, `work/s2/attacks2/`) | `Acc.rec F (Acc.intro true k) = Acc.rec F h`, `h` an axiom of `Acc R true` | R | A | A | `h = Acc.intro true k` by PI; cong under `Acc.rec F _` | TRUE |

**The classification is checked by the official checker itself** (`work/s2/gen/certify.py`, `work/s2/certify/`,
15 exports, one per row above, both directions of the first): each proves the SAME statement not by `Eq.refl`
but by the derivation of the table written as a term - `Eq.rec` (congruence) over `Eq.refl x : x = y` for the two
proofs `x`, `y`, an equation the official checker accepts by proof irrelevance; for the agreement and structure
eta rows the motive quantifies over the dependent proof (`fun x _ => forall (p : Q (R x)), w I p1 = w x p`).
**The official checker accepts all 15** (so do the machine under `v0`, `rich`, `both` and both leaders). What this
certifies: every statement that the options accept against the official verdict is a THEOREM for the official
checker, so its acceptance is not the acceptance of a false statement. That the equation is moreover
DEFINITIONAL in the theory is the derivation of the table: proof irrelevance and congruence are conversion rules
of the theory, the certificate uses nothing else (`Eq.rec` on `Eq.refl` is how one writes them as a term). The
official `is_def_eq` fails to FIND these equations - it reduces one side before it compares, and it does not
compare the arguments of an abbrev, a recursor or a closure -; none of them is false.

The whole run (`work/s2/out/known-judged.tsv`, configurations `v0`, `rich`, `skip`, `cong`, `both`, `bothrich`):

| Set | exports | official reject | of them accepted under an option | rejected under every configuration | official accept (accepted under every configuration) |
|---|--:|--:|--:|--:|--:|
| package S (`work/soundness/exports/`) | 351 | 304 | **14** (the rows above without the last) | 289 (+ 1 declined by the pipeline in every configuration, `str-lit-missing-prerequisites-bad`) | 47 |
| the arena's `bad` tests (`work/arena-site/tests/bad/`: 32 bugs, 5 other, 8 perf, 47 tutorial) | 92 | 92 | **0** | **92** | - |

No export is accepted under `v0` or `rich` that the official checker rejects.

Rejected by the options although TRUE in the theory (so the options are not complete for the theory either, and
do not need to be): `nontransitive-regular-def-bad`, `nontransitive-same-decl-bad` (`d h = 5`: delta, the first
row, iota; nothing in the machine closes pairs transitively), the arena's `125_accRecNoEta` and `101_ruleKAcc`
(`Acc.rec (fun _ _ _ => p) h = p` for a variable `h`: TRUE by PI against `Acc.intro` + iota; the arena lists them
as `bad`; no shortcut synthesises a constructor form from a proof, every configuration rejects), and
`acc-rec-no-eta-axiom` of this review.

Not in this class (false or not derivable; rejected by every configuration): the other 289 + 92 officially
rejected exports of package S and of the arena, among them the eager-reduction rows (declined by the machine by
rule M6-F26, rejected by the reference engine) and `100_ruleKbad` (`Eq.rec a (h : true = false) = a`: not
derivable, `h` is no `Eq.refl`).

## 5. The leaders

Read: `refs/gruszka/src/tc.rs` (`spines_defeq_skip` 8573, `unused_args` 8603, `prop_positions` / `surely_prop`
8641-8721, `defeq_app` 8770, `try_eta_struct` 8823, `closure_congr` 6792, the same-head step of lazy delta 8180,
`race_k` 7831, `probe_args` 8414), `refs/nano-lean/src/value_checker/conv.rs` (`def_eq_core` 246, `relevant` 389,
`prop_type` 433, `args_eq` 455, `probe_args` 359, `def_eq_app` 587, `eta_struct` 628, `same` 107). Both are the
same family of shortcuts (gruszka names its source: Pestka's ProofArgsSkipped, unusedArgs, EtaProofFieldsSkipped).

| | machine (`skipargs`, `congruence`) | gruszka | nano-lean |
|---|---|---|---|
| "position is a proof" | INFER (infer-only) of the domain under the telescope returns a SORT record; level instantiated at the head's levels; exact always-zero test | syntactic: after the domain's own Pis, a variable or constant applied to as many arguments as its type has Pis, that type ending in a sort; level instantiated at the head's levels (`zeroish`) | syntactic, the same shape; the sort must be always zero AS WRITTEN (zero, `max`, `imax _ 0`): no instantiation of universe parameters (`Sort u` at `u := 0` is compared) |
| proof position whose domain mentions a skipped, different unread position | compared (`differ` rule) | skipped | skipped |
| unread positions | definitions and theorems, spine >= lamArity, superset mask up to 65,535 lambdas, first 64 positions | definitions only (not theorems), at most 64 lambdas, spine >= lambdas | definitions and theorems, also for a spine shorter than the lambdas (the rest of the lambdas counts as body) |
| where the masks are used | c8 (proof positions), same-head step of lazy delta for a regular definition, B1 | `defeq_app` (proof positions), same-head step for a regular definition, budgeted probes (`race_k`, `probe_args`, refutation) | `def_eq_app`, `probe_args` before `whnf_core` for every unfoldable same head (budget) |
| same-head probe on recursors / `Quot.lift` | yes (B1, NOTES-M4 D19) | none in the code I read (it accepts the `And.rec` rows of section 4 by a route I did not trace) | no |
| structure eta: proof fields | skipped | skipped | compared (proof irrelevance) |
| closure congruence | slots by the complete CONV (B12) | slots by identity and by pairs ALREADY found equal (`same_val`), never a comparison of its own | structural identity only (`same`, fuel 512) |
| proofs by type before `whnf_core` | B2, when the value is already known to be a proof | no | yes, always (`proof_irrel` precedes `whnf_core`) |

Precautions the machine takes and the leaders do not: the `differ` rule; exact level instantiation with a
semantic sort (nano-lean is MORE conservative here: no instantiation). Precautions the leaders take and the
machine does not: none that bears on soundness. gruszka restricts unread positions to definitions and to 64
lambdas, nano-lean compares proof fields in structure eta and never compares closure slots by conversion: all
three are restrictions of completeness / cost, not conditions of the equations. I could not construct a false
equation from the absence of the `differ` rule (section 2.2: the two proofs then have types that the consumer's
side forces to agree, or the pair is never asked), so I do not claim the leaders are unsound there; the machine's
rule is the more careful one and costs a mask intersection.

Run (Docker, no network, the arena's run lines; `work/s2/leaders.sh`, `work/s2/out/leaders.tsv`):

| official | gruszka | nano-lean | exports (872: all of 3.1 with their non-literal twins, 3.2, 3.3, the certificates of section 4, S's conversion groups `facts-*`, `probe-*`, `mask-*`, `pirr-*`, `nontransitive-*`, `eta-*`, `unitlike-*`, `k-eq-rec-*`, `krule-*`, `open-flag-*`, the arena's `100_ruleKbad`, `101_ruleKAcc`, `125_accRecNoEta`) |
|---|---|---|--:|
| accept | accept | accept | 253 |
| reject | reject | reject | 600 |
| reject | **accept** | **accept** | 9 |
| reject | **accept** | reject | 10 |

- Neither leader accepts any FALSE claim of this review (the 96 hand-written and 362 random `-bad` exports) or
  of package S.
- **gruszka accepts all 14 exports of package S** that the machine accepts under the options (the 13 of S-F1 / S-F2
  and `open-flag-closed-bad`); **nano-lean accepts 6 of them** (`facts-agreement-noncongruent-app-bad`,
  `-samedef-bad`, `facts-unread-noncongruent-bad`, `probe-b1-abbrev-proof-arg`, `probe-b1-theorem-head`,
  `open-flag-closed-bad`): its probe is for unfoldable heads only and it has no closure congruence and no ETAS mask.
- Both leaders accept three more officially rejected exports that the machine REJECTS also with the options:
  `krule-eager-alone-bad`, `krule-memo-across-binders-eager-bad`, `krule-memo-across-declarations-eager-bad`
  (by package S's description the same class, inside the K rule under eager reduction; the machine declines such
  a declaration, the reference engine rejects it). gruszka also accepts `nontransitive-regular-def-bad` and
  `nontransitive-same-decl-bad` (`d h = 5`, true in the theory; by which route I did not trace).
- Neither leader accepts `acc-rec-intro-vs-axiom` (B1 on a recursor head is the machine's alone; why gruszka
  accepts the `And.rec` rows and not this one I did not trace), nor the arena's `125_accRecNoEta`,
  `101_ruleKAcc`, `100_ruleKbad`. Both accept the 15 certificates of section 4, as the official checker does.

So "as the leaders do" is literally true for gruszka: on these 872 exports gruszka differs from the official
verdict on 19, nano-lean on 9, the machine on 15 under some configuration with an option (14 of package S and the
`Acc` analogue; 14 of the 15 under `both`), on 4 with `skipargs` alone, on none without the options.

## 6. Not established

- **No proof.** The verdict rests on reading the code, on the argument of section 2 and on the attacks; nothing is
  mechanised. The induction of 2.2 uses the standard meta-theory of the official algorithm (subject reduction,
  uniqueness of types up to conversion, soundness of the official rules), which is itself not mechanised for
  all of Lean 4.
- **The precondition of 2.1** (every comparison is between well-typed terms) was established by reading the call
  sites of `Conv.conv` / `Cargs.cargs` and by the `ctx` attacks, not exhaustively. Not read line by line: the
  K1 code objects (`Code`, `Elab`, case trees, BODY / RULE / LEAF blocks), `Rules` (`k2`), `Tier` (`t2`),
  `Same` (the `same` switch; it was ON in `rich` / `bothrich` / `bothall` runs, but it is M4's official-exact
  claim and S's to attack). They reduce and identify; a fault there is a fault of the machine without the options.
- **Inference is now the only line of defence** for proof arguments and unread arguments (section 0, point 2).
  How likely a fault of INFER is that the type comparison of proof irrelevance would have caught, I cannot say;
  one seeded example shows the mechanism, no real one is known.
- **Parallel workers**: the publication of fact records (`FactStore`: one compare-and-set per row, immutable
  records) was reviewed by reading; one configuration with four workers ran the 134 hand-written attacks only. No
  export targets a race.
- **Native image**: every run is the JVM launcher.
- **Scale**: `shortcheck` ran on the slices listed in 3.6, not on all of Mathlib, Std or Init (Init: M4, before
  D27, NOTES-M4). The whole-Mathlib runs of P8 show that every unit is accepted, which says nothing about
  soundness.
- **Shapes without a hand-written attack**: mutual and nested inductive types, string literals (`String` is a
  structure with a proof field in this toolchain: the ETAS mask applies to it), `Nat` literal views. They occur in
  the Mathlib slices of 3.6 / 3.7 only.
- **Positions from 64 on** are always compared; nothing was skipped there to attack. Telescopes above 65,535
  binders get no facts (not exercised).
- **The leaders** were run on the hostile exports only as black boxes; their sources were read for the shortcuts,
  not audited.
- **Universe levels** (finding S-F3 of package S, the official incomplete relation against the complete one) are
  outside this review; every run used the default (official) relation.
- **Eager reduction**: a declaration that enters it is declined by the machine in every configuration, with the
  options as without; the options were therefore never exercised under ER.

## 7. Reproduction

```bash
python3 -I work/s2/gen/attacks.py                      # work/s2/attacks/*.ndjson (134) + attacks.tsv
S2_DAT=1 python3 -I work/s2/gen/attacks.py             # work/s2/attacks-dat/ (the same, no literal arguments)
python3 -I work/s2/gen/attacks2.py                     # work/s2/attacks2/ (Acc.rec, Quot, unit-like, Prop structures)
python3 -I work/s2/gen/certify.py                      # work/s2/certify/cert-*.ndjson: section 4 as terms for the official checker
for f in work/s2/certify/cert-*.ndjson; do rlvm-testkit/bin/testkit verdict $f; done     # 15 x accept
python3 -I work/s2/gen/fuzz.py SEED 100 work/s2/attacks/fuzz      # seeds 11..14
python3 -I work/s2/gen/mutate.py work/s2/attacks/mut-mathlib --per 120 --seed 7 work/exports/slices/good/Mathlib.Algebra.Group.Basic.cone.ndjson
scripts/build.sh --published --jvm-only --dist work/s2/dist2
bench/slot.sh -- python3 -I work/s2/judge.py --manifest work/s2/attacks/attacks.tsv --out OUT.tsv -j 3   # one line per export
bench/slot.sh -- work/s2/campaign.sh                   # known exports, leaders (Docker), corpora with shortcheck, mutants, seeded faults
bench/slot.sh -- work/s2/campaign2.sh                  # attacks2, certificates under the machine, leaders on them
python3 -I work/s2/leaders-summary.py                  # official / gruszka / nano-lean on every hostile export
work/s2/stats.sh congruence,skipargs warm,gated,expect,facts,probes FILE     # one run with the shortcut counters
```
