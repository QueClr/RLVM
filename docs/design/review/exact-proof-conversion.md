# Can the machine compare proofs cheaply and still give the official verdict? (agent C1)

2026-11-24, 18:35Z - 21:30Z. Prototype work on a PRIVATE copy of `rlvm-machine` (taken under the sandbox lock at
18:37:51Z; `work/c1/tree`, base copy `work/c1/base`, the copy without my instrumentation `work/c1/clean`). No
production file was changed and nothing was published. Everything of this report is under `work/c1/`; the living
summary is `work/status/prof-C1.md`; the patch is `work/c1/patches/0001-fused-two-sided-lazy-step.M4.patch`.

The question (profile P8, sections 0 and 4.1 / 4.2): 40 % of the machine's instructions on Mathlib are spent
comparing proofs the official way (types of proof arguments and proof fields, projection functions unfolded
before their arguments are looked at). The two shortcuts that remove it (`-Drlvm.machine.m4=congruence,skipargs`)
are sound in the theory but are not the official algorithm's verdict on hostile exports. **How much of the 40 %
can be removed while every verdict stays exactly the official one?**

## 0. Answer

**Almost none of it by a shortcut, and a part of it by making the same steps cheaper.** The work the shortcuts
remove is not redundant work of the official algorithm: on these comparisons the official verdict IS the result
of the unfoldings, and every way of answering without them that I examined is either what the complete judgment
already does, or is refuted by a hostile export, or needs a meta-theorem about the official algorithm
(stability of `is_def_eq` under substitution) that nobody has and that I could not prove.

| # | Idea | Official-exact? | What it recovers (Mathlib slice `Scheme.cone`, ticks; CPU in section 6) |
|---|---|---|---|
| 1 | memo / fusion of the unfold-to-structure steps | yes, where it only removes bookkeeping; built as switch `m4b` (the fused two-sided lazy step, `Conv.convFuse`) | `m4b` alone -2.9 % of the ticks (31.63 M -> 30.73 M), with every record, DELTA, CONV, PIRR and VTYPE count unchanged; on the heavy units 6 % of what the shortcuts remove. The complete judgment already had the two larger parts: the CORE memo takes the restart of `is_def_eq_core` out of a repeated chain, and M2's `bodies` makes the unfolding of a projection function one MKPROJ |
| 1' | instance-specialised rules (a derived rule per projection chain AND instance) | would be exact under the conditions of 3.4; NOT built | bounded by the reduction part of the heavy units (61 % of their ticks); my estimate of what a rule mechanism reaches is a third of that. It is M3's K2 extended into lazy delta, a package of its own |
| 2 | lockstep walk of one proposition under two frames | exact only where the official order compares arguments before it unfolds: rigid heads, the same REGULAR definition, binder domains - and the complete judgment does exactly that already. Not exact for abbreviations (every projection function), recursors, projections, closures | nothing beyond the complete judgment: at most 12 % of a heavy unit's ticks are in nodes of that kind, and the walk would still have to evaluate what lies below them |
| 3 | caches the official kernel has and the machine uses worse | there are none on these comparisons | 8 of 2,227 comparisons of the traced unit repeat a pair of records; see 5 for the expression-level count |
| - | the official-exact switches that exist (`facts` = B7; the rich set of the owners: `probes,code,casetrees,lazyeval,k2,same,m2a,m3a,m7a`) | yes (their owners' gates) | `facts` -4.7 %; the rich set -25.8 %; rich set + `m4b` -28.2 % of the ticks. On the units where the shortcuts save half or more, the rich set + `m4b` recovers 34 % of the ticks the shortcuts remove |
| - | the two NON-official shortcuts, for comparison (same binary) | no (15 known exports + 2 new ones of this report) | -43.4 % of the ticks on the step-1 set, -56.9 % on top of the rich set |

So the honest size of the official-exact route is: **the per-step cost of the same computation**, where the
owners' switches already hold the larger part, `m4b` adds 3 % of the ticks, and a rule mechanism in lazy delta
(1') is the one item that is still open and worth a package. The comparisons themselves cannot be skipped.

## 1. What the official algorithm does on these comparisons

Unit traced: `AlgebraicGeometry.Spec.sheafedSpaceMap_hom_c_app` (P8's unit), step-1 switches, JVM, one worker;
private hooks (`C1Trace`, one tagged line per hook; `work/c1/data/seq-base.txt` is the sequential trace, 16,553
events; tools `work/c1/spans.py`, `view.py`).

- 65,403 ticks: EVAL 7,858 + 6,896 memo hits, APPLY 8,519, CORE 6,850 + 3,352, DELTA 5,359 + 1,242, VTYPE 3,419,
  LSTEP 2,743, CONV 2,227 + 1,868 pair hits, PIRR 2,086, WHNF 1,614 + 272, DOM 1,519, LAZYD 1,384, INST 1,354,
  CARGS 1,011, CBIND 554, PROJD 359. 61 % of the ticks are reduction and evaluation (EVAL, APPLY, CORE, DELTA,
  WHNF), 22 % the comparison judgments themselves.
- **Every comparison succeeds**: 2,212 of the 2,227 comparisons that are not answered inline end in T at their
  own level, none of the top-level ones fails. The work is not search; it is the confirmation of equations that
  the theory gives for free.
- 59,736 of the 65,403 ticks are ONE comparison: `RingHom A B iA iB =?= RingHom A' B' iA' iB'`, where
  `A = CommRingCat.carrier (F.obj U)` and `A' = (F ⋙ forget).obj U` are the same ring behind two wrappers. The two
  instance arguments cost 27,263 and 27,373 ticks. Each is `Semiring.toNonAssocSemiring A s =?= Semiring.
  toNonAssocSemiring A' s'`: an abbreviation whose body is a constructor application (a parent that is not a
  sub-object is re-packed field by field), so both sides unfold and the constructors are compared field by field:
  `Semiring.left_distrib ..` (a proof: its type, 2,266 ticks), `Semiring.zero_mul ..` (3,374), `Monoid.toOne ..`
  (10,279, through a REGULAR definition whose last argument is the proof `SubsemiringClass.toSemiring._proof_8`:
  6,251 ticks for its type), ...
- Inside a law, every operation is unfolded on both sides down to the concrete structure (here a subring of a
  product of localisations): `HMul.hMul` -> `Mul.mul` -> `Subtype.mk (..) proof` (166 ticks the first time the
  instance is normalised, 28 to 30 ticks for each later occurrence), then the product (a lambda, CBIND), then the
  localisation (`LocalizedModule.mul`, regular: its arguments are compared), and the proof inside the `Subtype.mk`
  is compared by ITS type (`Membership.mem ..` -> `Set.Mem` -> the predicate: three nested `Exists`). The depth of
  the tower times the number of operations in the laws is the cost.
- The leaders answer the top comparison by comparing the arguments of the projection functions first (10
  unfoldings: `carrier (of X)` against `X` a few times) and never look at a proof.

Which of this is the official algorithm's own choice (`refs/lean4/src/kernel/type_checker.cpp`, lines given):

| Official rule | Consequence here |
|---|---|
| `is_def_eq_proof_irrel` (895): the two inferred types are compared | every proof field and proof argument costs the comparison of two instances of a law |
| `lazy_delta_reduction_step` (962): the arguments are compared before unfolding ONLY for the same definition with a REGULAR hint (995); for equal hints otherwise both sides unfold | every structure projection function (`HMul.hMul`, `Mul.mul`, `Semiring.toMonoid`, `Subtype.val`: abbreviations) is unfolded on both sides, whatever its arguments are |
| `whnf_core(.., cheap_proj = true)` in the lazy step, then the second `whnf_core` and the restart of `is_def_eq_core` (1181) | one operation = two or three passes of `is_def_eq_core`, each with its own proof-irrelevance test |
| `is_def_eq_app` (874) only for terms that nothing reduces | congruence exists only at rigid heads (constructors, inductive types, variables, axioms, stuck eliminators) |

## 2. What "official-exact" permits

Outside eager reduction (a declaration that enters it is declined by the machine, M6-F26) the official
`is_def_eq(t, s)` is a function of the two expressions: its caches (`m_success`, `m_failure`, the `infer` and
`whnf` caches) are memos of deterministic functions. An implementation is therefore official-exact iff it
computes that function; the sequence of steps, the cache contents and the order of sub-comparisons are NOT
observable in a verdict. Three things remain observable and bound every shortcut:

1. **Decision points.** A state of the official algorithm at which an answer can be given: the quick test
   (identical expressions, the success cache, two binders, two sorts, two literals), the `Bool.true` shortcut,
   proof irrelevance (definitive in BOTH directions), the Nat offset and Nat folding states of lazy delta, the
   argument comparison of the same regular definition, and the end of the lazy loop. A step that skips a state must
   show that no decision point of that state can fire, or that it fires with the answer the continuation gives.
2. **Cache transparency.** The quick test can answer T from the success cache in the middle of the lazy loop. A
   step that does not look there must be able to rely on "the continuation finds the same T". The machine already
   relies on it (NOTES-M4 D1, D26: `lazyeval`), and so does everything below. It holds because "is a proof" is
   invariant along an unfolding (the top-level `is_def_eq_core` of a cached pair tests proof irrelevance first, the
   loop that reaches the pair does not; `is_prop` normalises the level of the sort, lines 379-385, and a term and
   its reduct have convertible types).
3. **Errors.** An exception thrown by a sub-comparison is a verdict; an order that poses other sub-comparisons can
   meet other errors (NOTES-M4 D5). None of the mechanisms below reorders.

What is NOT available as an argument: congruence (`a = b` implies `C[a] = C[b]`), transitivity, and stability
under substitution (`t[x] = s[x]` for a variable implies `t[a] = s[a]`). The official function has none of the
three (review S-F1 / S-F2, NOTES-M4 W2); section 4 shows where each of them would be needed.

## 3. Idea 1: the unfold-to-structure steps

### 3.1 What the complete judgment already does (found by tracing, not by me)

- **The restart is already skipped on a repeated chain.** `Reduce.core` answers a CHEAP query from a memo that
  holds a FULL result ("a full result answers both modes", `Reduce.java` 564-568; ISA 4.3.3 COREFULL). The official
  `whnf_core` reads its cache only for the full mode. Effect: the first time `(instHMul A m).1 x y` is met the
  lazy loop ends with "unknown", the second `whnf_core` gives `Mul.mul A m x y` and `is_def_eq_core` restarts
  (quick test, `Bool.true`, proof irrelevance, a new LAZYD); every later application of the same projection is
  reduced to `Mul.mul A m x' y'` inside the lazy step, and the loop goes on with the next unfolding without a
  restart. In the trace of `fuse-chain-nested-ok` (two nested operations): the first operation costs PIRR, LSTEP,
  PIRR, LSTEP, PIRR, LSTEP; the second PIRR, LSTEP, LSTEP, LSTEP. This is the largest part of idea 1 and it is in
  the published machine. It is exact by point 2 of section 2 (the skipped proof-irrelevance test cannot fire); section 8
  says what that rests on.
- **The unfolding of a projection function is one step** with M2's `bodies` (`m2a`, `Eval.betaProj`: the body
  `self.i` of the closure is MKPROJ of the argument; no environment, no EVAL): slice records 14.37 M -> 11.56 M
  (-20 %), ticks -0.9 %.
- B7 (`facts`) answers "is it a proof" for a saturated constant application statically: PIRR 807 k -> 259 k,
  VTYPE 1.39 M -> 0.77 M, ticks -4.7 %.

### 3.2 Built: the fused two-sided lazy step (`m4b`, owner M4, `Conv.convFuse`)

What is left of the bookkeeping is the pair of judgments LAZYD / LSTEP around every two-sided unfolding. The
step runs those iterations inside CONV:

- **Shape** (`fuseShape(tn, sn)`, tested at c5, after proof irrelevance answered "not proofs"): both sides are
  applications of a definition or theorem with the right number of levels (`is_delta`); neither head is one of the
  binary Nat functions; `compare(hints) == 0`; and it is not the same definition with a regular hint.
- **Iteration**: `tn' = CORE(unfoldLazy(tn), cheap)`, `sn' = CORE(unfoldLazy(sn), cheap)` - the four calls of
  `lstepBoth` (k_lstep4 .. k_lstep7), in that order - then the quick test of `lquick`: T / F: the answer (recorded
  as CONV[7] records the answer of LAZYD); two binders: CBIND, definitive; otherwise the next iteration if the
  new pair has the shape again, else the complete judgment LAZYD is called with the pair in hand (it is a function
  of the pair: the same loop).
- **Not run**: the tick and event of LAZYD and LSTEP, and the states l0 (Nat offset) and l1 (Nat folding) of each
  iteration.

**Why the outcome is the official one.** For a pair of the shape, the official `lazy_delta_reduction` iteration
is: `is_def_eq_offset` needs a zero / successor form on both sides - a definition-headed application is neither,
so "undef"; `reduce_nat` needs one of the Nat functions at the head - excluded; `lazy_delta_reduction_step`: both
`is_delta`, `compare(hints) == 0`, not (same definition and regular): the branch that unfolds both sides (lines
1009-1010), then `quick_is_def_eq`. That is the iteration above, call for call. The step never compares arguments,
never consults a type, never skips a quick test; the values it computes, the memo words it fills (UNF, CORE) and the
pair entries it writes are those of the complete judgment, in the same order. Evidence that this is literally so:
on the slice the runs with and without `m4b` make the same 14,374,194 records, 2,186,171 DELTA, 1,146,099 CONV,
807,213 PIRR and 1,388,832 VTYPE events; only LSTEP (1,437,395 -> 837,831) and LAZYD differ. Under host depth 0
every sub-judgment writes its frame (four new continuation states `CONV2[9..12]`).

**Size**: -2.9 % of the ticks on the step-1 set, -3.0 % with `facts`, -3.3 % on the rich set (section 6). The ticks
it removes are the cheapest ones of the machine.

### 3.3 The first form of the step, withdrawn

My first version went from the two unfoldings directly to c7 when both results were "inert" (a weak-head normal
application, or a projection-headed application whose struct `whnf_core(cheap)` leaves alone), on the argument
that the cheap `whnf_core` and the second iteration do nothing then. That argument describes the OFFICIAL
algorithm correctly (41 exports, official verdict in every configuration), but not the machine: the cheap CORE of
a repeated chain is NOT a no-op (3.1), so the first form restarted `is_def_eq_core` where the complete judgment
continues the loop, and paid a proof-irrelevance test per level: PIRR 807 k -> 1,027 k, ticks only -1.0 %. It is
the more official of the two (it tests proof irrelevance where the official kernel does), and it is slower. I kept
the form that is the complete judgment minus its bookkeeping.

### 3.4 Not built: a derived rule per projection chain and instance (idea 1 in its strong form)

What it would be: for a partial application `P = HMul.hMul A A A (instHMul A m)`, remember that `P x y` reaches
`g x y` (the field of the weak-head normal form of `m`, here through the eta-expanded field `fun a b => Mul.mul A
m a b` of `instHMul`), and build `g x y` directly: one APPLY instead of two DELTA, two CORE, two APPLY and four
records per side.

Conditions under which firing the rule on BOTH sides is the official outcome (worked out, not implemented):

1. every head of the chain is an abbreviation or opaque (never regular: the argument comparison is a decision
   point) and none is a Nat function;
2. both sides fire rules with the same sequence of heads, so that the official loop would have unfolded both
   sides at every step (`compare(hints) == 0` at every level); a chain that leaves the lockstep ends the rule;
3. the intermediate values that the official algorithm passes at a restart (`Mul.mul A m x y`) are not proofs.
   This must be known WITHOUT building them: B7's static test on (constant, levels) gives it for a saturated
   application whose result sort is manifest and never zero; anything else ends the rule at that level;
4. the intermediate quick tests can only answer T (the values are applications), and by cache transparency the
   continuation finds that T; identity of an intermediate pair implies identity of the final pair.

Size: bounded by the reduction share of the heavy units (61 % of the traced unit's ticks are EVAL, APPLY, CORE,
DELTA, WHNF). The two-level chains `HMul.hMul -> Mul.mul`, `HAdd.hAdd -> Add.add` are 1,920 of the unit's 5,359
unfoldings; halving their cost and that of the single levels is about a fifth of the unit's ticks. On Mathlib as a
whole I would expect 5 to 10 % of the ticks, less in CPU time. It is M3's K2 (session-local derived trees, today
"no rule in lazy delta", NOTES-M3 D17) keyed additionally by the instance arguments and fired in lockstep; I did not
build it because it is a package of its own (rule derivation with derivation variables, templates, budgets) and its
prize is a tenth of what was asked for. The conditions above are what its exactness review would have to check.

## 4. Idea 2: the lockstep walk

### 4.1 Where the official order compares arguments before it unfolds

| Place | Official rule | In the machine |
|---|---|---|
| applications that nothing reduces (rigid heads; stuck recursor, projection, variable heads), first to last | `is_def_eq_app` after the second `whnf_core` changed nothing | c8, CARGS policy APP |
| the SAME definition with a REGULAR hint, last to first, guarded by `m_failure` and the level lists | `lazy_delta_reduction_step` 995-1007 | `lstepBoth`, CARGS policy SAMEDEF, FAILED |
| binder domains, then the bodies under one variable | `is_def_eq_binding` | CBIND |
| predecessors of two successor forms | `is_def_eq_offset` | OFFSET |
| the structs of two projections `a.i`, `b.i`: unfolded lazily in lockstep, then the fields of two constructors or the structs themselves | `lazy_delta_proj_reduction` | PROJD |
| proofs: never the arguments, always the two types | `is_def_eq_proof_irrel` | PIRR |

Everywhere else the official algorithm reduces first: abbreviations and opaque definitions (all structure
projection functions and all theorems), recursor applications whose major premise can be normalised, projections
of something that normalises to a constructor, beta and let.

### 4.2 The walk that is exact by construction is the complete judgment

A walk over the shared term of two closures can answer T without unfolding at a slot only if the official
algorithm, on its way to that slot, passes only nodes of the first three rows: binders, sorts, applications of an
inductive type (never a proof, so proof irrelevance says "not proofs"), and compares the two slot values
themselves there. The complete judgment does this, node for node, and reads the pair table at the slots. A walk
on the term would save the evaluation of the shell (INST, DOM, the CONV and CARGS of the rigid nodes): in the
traced unit that shell is at most 554 CBIND + 503 CARGS-APP nodes with their CONV, PIRR, DOM and INST, about 8,000
of 65,403 ticks (12 %) if every one of them cost nothing - and the first operation below the `Eq` is an
abbreviation, where the walk must hand over. Not built: nothing to gain.

### 4.3 Why the walk cannot be extended to projection functions

The comparison that matters is `P p s e =?= P p' s' e'` for a projection function `P` (body `self.i`) with
`is_def_eq(s, s') = T`, `is_def_eq(e, e') = T`. The official path: both unfold to `s.i e`, `s'.i e'`; if the
projections are stuck for `whnf_core(cheap)`, "unknown"; then

- no further argument (`Semiring.toMonoid A s`): `lazy_delta_proj_reduction(s, s')`, which is the lazy loop of
  `is_def_eq(s, s')` itself. Here "the structs are convertible implies the projections are" does hold on every path
  I followed, and it gains nothing: the official path costs what `is_def_eq(s, s')` costs.
- with arguments (`HMul.hMul A A A i x y`): the second `whnf_core` normalises the instance. If it is stuck (an
  abstract instance), `is_def_eq_app` compares the heads (PROJD again, cached after the first time) and then
  exactly `x =?= x'`, `y =?= y'`: congruence holds and is what the complete judgment does - cheaply. If it is a
  constructor (a concrete instance: the heavy case), the comparison continues with `f x y =?= f' x' y'` for the
  two fields. What is known about them is `f =?= f'`, i.e. `body[a, b] =?= body'[a, b]` under two fresh variables.
  To conclude `body[x, y] =?= body'[x', y']` one needs stability of the official `is_def_eq` under substitution
  and congruence in the substituted positions. It has neither: `body = fun a => R a` with `R a = And.rec f a`,
  `x = And.intro ..`, `x' = h` (an axiom) is the review's `probe-b12-closure-proof-slot-bad`; with `P` an
  abbreviation it is `probe-b1-abbrev-proof-arg`, and my `fuse-abbrev-samehead-proofarg-bad` /
  `fuse-opaque-samehead-proofarg-bad` (official: reject; `congruence`: accept; every official-exact
  configuration: reject).

So the lemma "pair-table T at the slots, same term, implies T" is false for abbreviations as it stands, and the
restricted lemma one would want - "... if the arguments are IDENTICAL or related without proof irrelevance and eta,
and no reduction on the way inspects a proof" - needs (a) a way to know how a pair-table entry was derived, down
to its sub-derivations, and (b) the meta-theorem that the official lazy strategy on two instances of one term
under such frames stays in lockstep. M4 could not close (b) (NOTES-M4 D27: the types of the differing leaves are
consulted by K, structure eta, unit-like and proof irrelevance of enclosing terms, and the types of two terms
that are convertible by reduction alone can be convertible only through proof irrelevance). I could not either,
and (a) fails on the hot comparisons anyway: the two instance structures of the traced unit ARE equal only up to
proof irrelevance (their proof fields are different terms).

Two more candidates that fail the same way: normalising the wrapper (`carrier (of X)` to `X`) when the value is
built would make the two sides one record, but "replacing a subterm by its reduct does not change the official
answer" is the same missing invariance (`G I =?= G h` for a regular `G` is T by the arguments and F after
unfolding: `fuse-regular-samehead-proofarg-ok`). And skipping a proof position when the EARLIER positions are
identical does not follow either: both proofs then have types convertible with one expected type, and the official
relation is not transitive (`nontransitive-*` of package S).

## 5. Idea 3: caches

- Pair table against the official success cache: in the traced unit 8 of the 2,227 comparisons that miss the table
  pose a pair of records that was posed before (all eight are `Sort (max 1 (u+1)) =?= Sort (u+1)`, which is never
  recorded, NOTES-M4 D8); 179 of 2,743 lazy steps and 57 of 359 PROJD calls meet an in-hand pair again (the
  official kernel does not cache those either).
- Frame identity: frames are interned by content after pruning, so "equal but distinct frames" are frames whose
  slots are different records of one expression. M4's `same` (D28) answers those at c0; on this slice it is
  -5.3 % of the ticks (NOTES-M4 W4) and is part of the rich set of section 6.
- The other direction: the machine's variables are `FVAR(type, k)`, the same record for the k-th variable of a
  type in every binder; the official kernel makes a fresh variable for every binder it opens. So two laws over
  the same carrier share their sub-comparisons in the machine (1,868 pair hits in the unit) and not in the official
  kernel. The machine's table hits MORE often than the official caches would.
- Expression-level repeats (a comparison whose two sides are, as expressions by `Same.same`, a pair already
  answered T in the unit): see the row "struct" of `work/c1/data/` (section 6; the estimator is
  `C1Trace.structural`, quadratic, traced units only).

## 6. Numbers

Counters: JVM launcher, one worker, the private tree with the tap `c1tap` (P8's per-unit tap), statistics on,
`work/c1/run.sh TAG scheme SWITCHES`; slice `Mathlib.AlgebraicGeometry.Scheme.cone` (21,155 engine checks; 21,099
accepted and 56 declined by the eager-reduction rule in EVERY row). `rich` = `warm,gated,expect,probes,code,
casetrees,lazyeval,k2,facts,same,m2a,m3a,m7a`.

| switches | ticks | vs step 1 | records | DELTA | CONV | PIRR | LSTEP | VTYPE | traced unit |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| `warm,gated,expect` (step 1) | 31,630,908 | | 14,374,194 | 2,186,171 | 1,146,099 | 807,213 | 1,437,395 | 1,388,832 | 65,403 |
| + `m4b` | 30,727,488 | -2.9 % | 14,374,194 | 2,186,171 | 1,146,099 | 807,213 | 837,831 | 1,388,832 | 62,677 |
| + `m2a` | 31,351,447 | -0.9 % | 11,560,882 | 2,116,825 | 1,121,896 | 789,195 | 1,390,046 | 1,348,123 | 65,920 |
| + `facts` | 30,152,550 | -4.7 % | 14,200,568 | 2,182,312 | 1,144,335 | 258,909 | 1,434,568 | 771,521 | 62,128 |
| + `facts,m4b` | 29,250,967 | -7.5 % | 14,200,568 | 2,182,312 | 1,144,335 | 258,909 | 836,309 | 771,521 | 59,402 |
| rich | 23,479,898 | -25.8 % | 9,303,450 | 1,602,961 | 1,064,195 | 218,193 | 1,082,110 | 663,861 | 57,971 |
| rich + `m4b` | 22,695,373 | -28.2 % | 9,308,505 | 1,604,781 | 1,062,148 | 218,063 | 551,133 | 663,894 | 54,673 |
| step 1 + `facts,probes` + `congruence,skipargs` (NOT official) | 17,898,001 | -43.4 % | 8,422,489 | 926,239 | 771,229 | 44,386 | 717,031 | 307,439 | 3,866 |
| rich + `congruence,skipargs` (NOT official) | 13,624,753 | -56.9 % | 5,434,986 | 542,506 | 703,781 | 39,516 | 336,508 | 286,930 | 3,052 |

By how much the shortcuts save in a unit (ticks of the step-1 run divided by the ticks with the shortcuts):

| ratio | units | step 1 | share | with the shortcuts | rich | rich recovers | `facts` recovers |
|---|--:|--:|--:|--:|--:|--:|--:|
| below 1.25 | 16,215 | 12.02 M | 38.0 % | 11.76 M | 8.50 M | (more than the shortcuts) | |
| 1.25 .. 2 | 3,222 | 5.46 M | 17.3 % | 3.56 M | 4.53 M | 49 % | 17 % |
| 2 .. 4 | 1,113 | 4.22 M | 13.3 % | 1.65 M | 3.29 M | 36 % | 10 % |
| 4 .. 16 | 464 | 5.88 M | 18.6 % | 0.80 M | 4.55 M | 26 % | 7 % |
| 16 and more | 113 | 4.04 M | 12.8 % | 0.12 M | 2.60 M | 37 % | 5 % |

The units at 2 and more (1,690 units, 44.7 % of the ticks): the shortcuts remove 11.58 M of 14.14 M ticks, the rich
set 3.70 M (32 %), `facts` 0.82 M (7 %), `m4b` on top of the rich set about another 2 points.

Where the shortcuts' 13.7 M ticks sit, by operation (step 1 -> with the shortcuts; M events and memo hits):
EVAL 9.38 -> 6.42, CORE 4.13 -> 1.26, APPLY 3.65 -> 1.81, DELTA 2.92 -> 1.44, VTYPE 1.39 -> 0.31, PIRR 0.81 -> 0.04,
LSTEP 1.44 -> 0.72, CONV 1.93 -> 1.56, LAZYD 0.60 -> 0.22. The rich set moves EVAL (5.73), APPLY (0.89, the code
blocks run instead: 2.30 M ACT) and VTYPE / PIRR (0.66 / 0.22), and leaves CORE (3.41), DELTA (2.21) and CONV (2.03)
where they are: the unfoldings and the comparisons are still made.

CPU time and wall clock (bench/quiet.py; native -O3 of the clean private tree, `work/c1/dist-n`): **TIMING-TABLE**

## 7. Tests

### 7.1 The differential gate (M8, `report.sh gate`) on the clean private tree

**GATE-TABLE**

### 7.2 Hostile exports

`work/c1/judge.py`: every export through the launcher classes of the clean private tree with `-X engine=machine`;
official verdicts from the owners' records (package S's manifest, S2's judged tables) and, for the new exports,
from leanchecker 4.36.0 run in the arena container (`work/c1/official.sh`). Switch sets: `a4` = `warm,gated,
expect,m4b`; `f4` = `a4` + `facts,probes`; `rich4` = `warm,gated,expect,facts,probes,tele,code,casetrees,lazyeval,
same,m2a,m3a,m7a,k2,m4b`; `all4` = `all,lazyeval,same,match,joint,k2,m2a,m3a,m7a,m4b` with `elabAt=1`; `only4` =
`m4b` alone; `cap4` = session cap 1 KiB; `d0` = host depth 0.

**HOSTILE-TABLE**

The 41 new exports (`work/c1/hostile/`, generator `work/c1/gen/hostile.py`, manifest with the official verdicts
`work/c1/hostile/manifest.tsv`; my prediction of the official verdict was right for all 41):

| Group | Exports | What must hold |
|---|--:|---|
| same REGULAR definition, arguments equal only by proof irrelevance (`G I = G h`, both orders) | 2 ok | accepted: the arguments are compared first; a fused both-unfold would reject |
| same ABBREV / OPAQUE definition, the same arguments | 2 bad | rejected: both unfold, `And.rec f I` is 5, `And.rec f h` is stuck (accepted by `congruence`) |
| unequal hints (abbrev over regular, regular over abbrev, height 2 over height 1) | 3 ok | accepted: one side unfolds first and meets the other; unfolding both would reject |
| two regular definitions of one height | 1 ok, 1 bad | both unfold without an argument comparison |
| projection functions with hint abbrev / opaque / regular over two structure definitions; two projection functions; constructor struct; struct equal by structure eta on one side; two axioms; arguments by unfolding | 9 ok, 7 bad | the chain unfold, stuck projection, second `whnf_core`, restart |
| a projection function of a PROOF field applied to an argument | 1 ok, 1 bad twin | the application is a proof: by its type, never unfolded |
| a parameter that differs as a term and is dropped by the unfolding | 1 ok | identical after the step: the quick test |
| the shape of `HMul.hMul` over `instHMul` over `Mul.mul`, also nested | 2 ok, 2 bad | two fused levels, the CORE memo on the second occurrence |
| Nat functions, closed | 1 ok, 1 bad | folded, not unfolded (the shape excludes them) |
| wrappers that unfold to recursor applications / to constructor applications (also differing in a proof field) | 3 ok, 1 bad | a result that the next state can still change goes to the complete judgment |
| the restart: a pair that is compared by proof irrelevance only after unfolding, once / twice in two declarations / twice in one | 3 ok | the proof-irrelevance test of the restarted `is_def_eq_core` (see 8) |
| `a = b` for two axioms of `Q : Sort (imax 1 0)` and of `Q.{1} : Sort (imax u 0)` | 2 ok | probes of the base: the official checker treats such a sort as `Prop` (accept) and so do both engines |

## 8. Not established

- **A mechanised argument for the CORE memo's answer to a cheap query (3.1).** It lets the lazy loop continue
  where the official algorithm restarts `is_def_eq_core`, so the proof-irrelevance test and the `Bool.true` test of
  the restarted pair are not made on a repeated chain. That is exact if "is a proof" cannot change along an
  unfolding. In the pinned kernel it cannot, by the standard meta-theory: `is_prop` is `normalizes_to_zero` of the
  sort of the type (`type_checker.cpp` 379-385, not a syntactic comparison with `Prop`), the types of a term and of
  its reduct are convertible (subject reduction), hence their sorts carry equivalent levels. I tried to separate the
  two paths with a type of sort `Sort (imax 1 0)` behind a definition of declared type `Prop` (three
  `restart-proof-*` exports, two `isprop-imax-*` probes): the official checker treats both as propositions and every
  configuration agrees with it. No counter-example, no mechanised proof. This concerns the published machine with
  every switch set, not `m4b` (which follows the complete judgment); owners: M3 (`Reduce.core`), M4, package S.
- **A proof of the meta-theorem of 4.3.** I could not prove it and found no export that refutes its restricted
  form (identical arguments, data only); so "a lockstep walk that is exact for propositions without a
  proof-inspecting reduction" is neither established nor refuted. What I can say is that it would not apply to
  the hot comparisons (their leaves are equal only up to proof irrelevance).
- **The size of 3.4** is an estimate from counters; nothing of it was built.
- **TIMING-CAVEATS**
- Parallel workers: every counter run and the hostile runs are one worker (the judge has no 4-worker row).
- The official kernel's own cost on these units was not measured (P8: 30 times gruszka's instructions on Mathlib).

## 9. Reproduction

```bash
C=work/c1
bench/slot.sh -- $C/build.sh                        # compile the private tree (with the trace hooks) and the tap
C1_ONLY=AlgebraicGeometry.Spec.sheafedSpaceMap_hom_c_app bench/slot.sh -- $C/run.sh base scheme warm,gated,expect
awk '/^--- first/{f=1;next} f' $C/data/trace-base-scheme.txt > $C/data/seq-base.txt
python3 -I $C/spans.py $C/data/seq-base.txt 9 1500 210      # nested spans with their ticks
python3 -I $C/view.py $C/data/seq-base.txt 3421 3640        # one proof's type comparison, event by event
bench/slot.sh -- $C/batch2.sh; python3 -I $C/sum.py scheme a a4 af af4 richx rich4 nx nx4 nxr   # the counter table
bench/slot.sh -- $C/mkclean.sh                      # the tree without the hooks, the tap, the patch per owner
python3 -I $C/gen/hostile.py; $C/official.sh        # the new hostile exports and their official verdicts (Docker)
bench/slot.sh -- $C/hostile-all.sh                  # S, S2 and C1 exports under the m4b switch sets
$C/gates.sh                                         # M8's gate on the clean tree (reports: $C/clean/work/status/)
bench/slot.sh -c ni -n 2 -- $C/nbuild.sh            # native -O3 of the clean tree: $C/dist-n
bench/quiet.py -- $C/quiet-cpu.sh scheme 2          # thread CPU of the checks, 1 worker
bench/quiet.py -- $C/quiet-wall.sh scheme 4         # wall clock of every pass, 8 workers, pipelined
```
