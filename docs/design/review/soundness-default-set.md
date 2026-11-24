# Adversarial review of the machine's default switch set, of `k2`, and of the candidate default (reviewer S3)

2026-11-24, 21:06Z - 22:50Z. Two passes. **Pass 1** (sections 0 - 9): `rlvm-machine` as published at 20:28Z (jar
`15cb3777e598`; my private copy `work/s3/tree` packages to the same hash), through the JVM launcher of `work/s3/dist`
(the published jars of the shared distribution of 21:02Z: machine `15cb3777e598`, reference `ca1f9bd918bb`), `-Xmx4g`,
one worker. **Pass 2** (section 10, after the owner's decision "practical", OWNER-NOTES note 13): the CANDIDATE
default - the default set + `eager` + the proof-comparison shortcuts `skipargs` and `congruence` - on machine jar
`df36be3c8cc9` (E1's publications of 21:26Z typed bindings, 21:57Z `eager`, 22:12Z; my copy `work/s3/tree2` packages
to the same hash; distribution `work/s3/dist-cand`), attacked for a FALSE accept only. No production file was
changed; everything of this review is under `work/s3/`. Ground truth everywhere: the official checker (`leanchecker
--from-export`, Lean v4.36.0, inside Docker: `work/s3/official.sh`), never the reference engine.

Switches reviewed: `same`, `pchk`, `bodies`, `match`, `joint`, `lazycases`, `directrules`, `lazyeval`, `code`,
`casetrees` (the ten that entered `Op.SW_DEFAULT` at the wave-6 integration without an adversarial review) and `k2`
(off). `warm` is in the default set and is touched by two findings; `expect`, the binder / let / application rules
and eager reduction are E1's.

Words used below. **Kind T**: the machine accepts a statement that is derivable in Lean's type theory and that the
official checker rejects only through its own incompleteness, non-transitivity or cache order (evidence: the
official checker accepts the same statement in another order, or with the missing step written out). **Kind F**: the
accepted statement is not derivable. A machine cell `M/P` is the verdict of the PRIMARY machine engine under the
switch set (from `engine.primary.machine.*`) and the exit status of the pipeline.

## 0. Verdict

**No kind F finding, in the default set and in the candidate (section 10).** Six findings of kind T (certificates in
section 3): one in v0 and therefore in every switch set (S3-F1), one in the REFERENCE engine (S3-F2, the default
pipeline), one in `warm` (S3-F3), and three that are specific to a switch: `lazyeval` (S3-F4), `pchk` (S3-F5), `k2`
(S3-F6, with its default thresholds). One finding that is no wrong verdict: `code` declines valid units with an
internal fault (S3-F7). Under the owner's policy of 22:25Z (kind T tolerated and documented, kind F absolute) the
kind T findings are the documented divergences of the default configuration, not blockers. They have one
root that none of the gates could see (section 2): **the official `is_def_eq` is not a function of its two arguments,
not even in plain mode** - the cache `m_whnf_core` is read by the cheap `whnf_core` calls and written only by the
full ones - so every memo of the machine that answers a comparison, a checked type or a cheap CORE is exact only if
it mirrors the official per-declaration, term-keyed caches, and none of them does.

| Switch | What it assumes | Own difference from the official verdict (beyond v0) | Verdict |
|---|---|---|---|
| (v0, for comparison) | the pair table POS, the tabled CHECKED types and the full-CORE memos are what the official `m_success`, `m_infer_type[0]`, `m_whnf_core` hold | wrong ACCEPT and wrong REJECT, kind T: S3-F1 (9 rows marked `cache`) | not exact |
| `same` | two values that read back to ONE expression are convertible for the official procedure | none found (0 rows differ from v0) | exact relative to v0 as coded; inherits S3-F1 |
| `pchk` | "a closed term without level parameter that passed check mode once passes it in every later declaration" | **wrong ACCEPT, kind T: S3-F5** (4 rows; v0 rejects them) | **not exact**: `s3-pchk-def-reused-bad` |
| `bodies` | the value of a beta body does not depend on having a tabling record; `(fun .. x .. => x.i) .. a ..` is `MKPROJ(a, i)` | none found | exact relative to v0 as coded |
| `match` | an argument type that equals the domain pattern under the telescope, up to equivalent levels, is convertible with the built domain | none found | exact relative to v0 as coded |
| `joint` | leading binders of type and value with the SAME domain term can be opened once | none found | exact relative to v0 as coded |
| `lazycases` | no state of the official lazy-delta loop lies between the unfolding of a `casesOn`-shaped definition and the reduct of its recursor | none found | exact relative to v0 as coded (shape CASESON only; a MATCHER tree in the lazy step is NOT exact: row `s3-lazycases-matcher-over-regular-cases-ok`, seeded fault 10) |
| `directrules` | the rule's right-hand side has the plain form (verified on the term): one APPLY of the minor is the same reduct | none found | exact relative to v0 as coded |
| `lazyeval` | "order-only": a one-sided unfolding against a weak-head normal side may run as one WHNF | **wrong ACCEPT, kind T: S3-F4** (v0 rejects) | **not exact**: `s3-wc-inner-caseson-lazy-bad` |
| `code`, `casetrees` | a compiled body / case tree computes the reduct of DELTA + rule + beta | no wrong verdict found; they change which full-CORE memos exist - in my rows always TOWARDS the official verdict (two v0 wrong accepts become rejects of the primary machine); **S3-F7: `code` declines valid units (internal fault)** | no difference of their own found; not provably exact (see 4.9); S3-F7 costs a decline |
| `warm` (not on my list) | everything a session keeps is declaration-independent | wrong ACCEPT and wrong REJECT, kind T: S3-F3 | not exact |
| `k2` (off) | "the leaf is a reduct of `c x̄` by official steps, and reduction commutes with substitution" | **wrong ACCEPT with the default thresholds, kind T, and a wrong REJECT: S3-F6**; with test thresholds also the memo class of S3-F1 | **not exact; must stay off** (section 7) |

What follows from it for the default set: under the strict reading of owner note 11 (the official verdict on every
input) v0 itself is not a candidate, with or without the ten switches; under the reading "official verdict on real
inputs, never a kind F accept" I found nothing against the ten switches - the two that add kind T accepts of their
own are `pchk` and `lazyeval`, and `warm` adds its own as well.

One thing of the PIPELINE, not of a switch: with `-X engine=machine` a rejection of the warm machine is re-checked by
the strict machine (v0) first and an ACCEPT there is final (E1 saw it for E1-F3). Rows `s3-wc-inner-caseson-bad` and
`s3-wc-inner-matcher-bad`: the default set REJECTS (the case tree leaves the right memo), v0 accepts, exit status 0.
A wrong accept of v0 is a wrong accept of the pipeline whatever the switch set does.

## 1. Method and tools (all kept under `work/s3/`)

| What | Where |
|---|---|
| exports (422, NDJSON; 160 of them random programs) and the generators' row lists | `work/s3/exports/s3-*.ndjson`, `exports/batch*.tsv` |
| manifest in the form of `rlvm-machine/src/test/resources/s/manifest.tsv` with one more column: name, OFFICIAL verdict, KIND (`T` or `strict`), marks, attack | `work/s3/manifest.tsv` (`mkmanifest.py`) |
| generators (on package S's builder `tools/lk.py` and E1's `common.py`) | `work/s3/gen/s3lib.py`, `batchA.py` (cache, pair table, sessions, mdata), `batchC.py` (reduction routes against the cache), `batchD.py` (pchk), `batchE.py` (shapes and shortcuts), `batchF.py` (rows for the seeded faults), `batchG.py` (kind F hunt in the case trees), `batchK.py` (k2), `batchR.py` (random programs with a Python evaluator), `batchH.py` (reductions of S3-F7), `batchN.py`, `batchP.py` (the candidate: eager, the shortcuts' masks, admission) |
| official verdicts (Docker), cached by content hash | `work/s3/official.sh`, `work/s3/official/NAME.{out,verdict}` |
| judge: official + reference + machine under named switch sets, cells `M/P`, flags | `work/s3/judge.py` |
| the matrices: pass 1 (358 exports x 13 switch sets + reference: `matrix.tsv`, `matrix3.tsv`), k2 with test thresholds (`matrix-k2test.tsv`), the candidate jar (`cand-plain.tsv`, `cand-plain2.tsv`: none / default / default,eager; `cand-full.tsv`: the candidate and the candidate without `eager`; `cand-sx.tsv`: package S's, E1's and S2's rows under the candidate) | `work/s3/out/` |
| private copies: pristine (`tree`, `tree2`: jar hashes = the published ones), with seeded faults (`seeded`: 1 - 11 on the first jar; `seeded2`: 12 - 15 on the candidate jar; `-Ds3.bug=N`), their distributions | `work/s3/tree`, `tree2`, `seeded`, `seeded2`, `dist-tree`, `dist-cand`, `dist-seeded`, `dist-seeded2`, `mkdist.sh`, `seeded.py` |

Reproduce: `python3 work/s3/gen/batchA.py` (and C, D, E, F, G, K; `batchR.py 40 1`), then
`bench/slot.sh -- python3 work/s3/judge.py -j 3 --sets v0,step1,same,pchk,bodies,mj,lazyeval,direct,code,ct,ctl,default,def+k2 work/s3/exports/s3-*.ndjson`.
k2 with its test thresholds: `S3_JVM_EXTRA="-Drlvm.machine.k2Hot=1 -Drlvm.machine.k2Share=0"`. Seeded faults:
`python3 work/s3/seeded.py`.

The three gadgets every row is built from (A = `And True True`, `intro` its constructor application, `h : A` an axiom):

- package S's triple: `d x := And.rec (fun _ _ => 5) x` (regular). `d intro = d h` and `d intro = 5` are accepted
  officially, `d h = 5` is rejected.
- **the box** (new): `structure S.B where n : Nat`, `f x := S.B.mk (And.rec (fun _ _ => 5) x)` (regular),
  `a = (f intro).1`, `b = (f h).1`. The cheap `whnf_core` leaves both projections stuck and
  `lazy_delta_proj_reduction` compares `f intro` with `f h`: same head, arguments by proof irrelevance: `a = b` is
  TRUE. The full `whnf_core` gives `5` for `a` and the stuck `And.rec .. h` for `b`: FALSE. Which of the two the
  official kernel computes depends on whether its cache has an entry for the term `a`.
- **the substitution-unstable pair** (new, for k2): `t x = Nat.rec M Z S x intro`, `s x = Nat.rec M Z S x h` with
  `M = fun _ => A -> Nat`, `Z = fun q => And.rec (fun _ _ => 5) q`, `S = fun _ _ q => And.rec (fun _ _ => 5) q`:
  convertible officially while `x` is a variable (two stuck recursors, arguments pairwise), not convertible for any
  numeral `x`.

## 2. The root: the official `is_def_eq` depends on the declaration's history

`refs/lean4/src/kernel/type_checker.cpp` (v4.36.0), `whnf_core`: after the easy cases, "check cache"
(`m_st->m_whnf_core.find(e)`) is done for EVERY call, also with `cheap_proj = true`; the insert at the end happens
only `if (!cheap_rec && !cheap_proj)`. `is_def_eq_core` starts with `whnf_core(t, false, true)` and every lazy-delta
step is `whnf_core(unfold_definition(t), false, true)`; the full call comes in step 7 of `is_def_eq_core`
(`whnf_core(t_n)`), in `whnf` (every term its loop passes, and the recursion of `whnf_core` inserts every
intermediate term: the instantiated body of an unfolded definition, the applied minor premise, a projection reached
at the head), in `reduce_nat`, in the major premise of a recursor, in `ensure_pi` / `ensure_sort` / `infer_proj`, the
K rule, eta and unit-like. All caches of `type_checker::state` are keyed by TERMS (structural equality: an `mdata`
node, a beta redex, another free variable are other keys) and live for ONE declaration.

So `whnf_core(e, cheap_proj = true)` of a projection `e = p.i` whose structure needs delta is `e` itself while the
cache has no entry for the term `e`, and the reduced field once any full `whnf_core(e)` ran. With the box:

| Export (one theorem; `use : forall (P : Prop), P -> True` an axiom, the kernel checks arguments left to right) | Official | Reference | Machine v0 |
|---|---|---|---|
| `s3-wc-proj-alone-ok`: `Eq.refl a : a = b` | accept | accept | accept |
| `s3-wc-proj-after-full-bad`: `And.intro (use (a = 5) (Eq.refl 5)) (use (a = b) (Eq.refl a))` | **reject** | reject | reject |
| `s3-wc-proj-before-full-ok`: the same two arguments in the other order | accept | accept | accept |

The other caches freeze answers of an earlier cache state: `m_infer_type[0]` (a term that was checked is not checked
again in this declaration), `m_success` (a TERM pair that succeeded answers T again; `s3-wc-pos-otheruse-bad` is
accepted officially for that reason), `m_failure` (only for the same-head argument comparison). Nothing else in plain
mode depends on history (`m_whnf`, `m_infer_type[1]`, `m_unfold` cache deterministic results). The direction is
monotone inside a declaration: more entries can only turn a T into an F for a NEW term pair; an F never becomes T.

What the machine has in their place: the full-CORE memo on a projection value (`Reduce.core`: "a full result answers
both modes, a cheap one only cheap queries" - the designers knew the quirk), the pair table POS / NEG, the tabled
CHECKED types with stamps, the PCHK bits. They differ from the official caches in four ways, and each way is a row
below: keyed by VALUES (beta, zeta, `mdata` are gone), free variables are canonical (`FVAR(type, depth)`: another
binder instance is the same key), a `warm` session and PCHK keep them across declarations, and some reduction routes
do not write the full-CORE memo where the official kernel writes its entry.

## 3. Findings

All verdicts: official from Docker; machine and reference through the launcher of `work/s3/dist`. `W = use (a = b)
(Eq.refl a)`, `W0 = use (a = 5) (Eq.refl 5)`.

| Id | Kind | Where | Export (`work/s3/exports/`) | Official | Reference | Machine |
|---|---|---|---|---|---|---|
| **S3-F1** | T | v0 and every switch set: POS pair by VALUE answers a new TERM pair | `s3-wc-pos-beta-bad`: `And.intro W (And.intro W0 (use (a = (fun x => (f x).1) h) (Eq.refl a)))` | reject | reject | **accept** (every set) |
| | T | canonical free variables (the plain-mode sibling of S-F5) | `s3-wc-canon-fvar-bad`: `And.intro (fun p => W p) (fun p => snd (W0 p) (W p))` | reject | reject | **accept** (every set) |
| | T | the full-CORE memo is not written when iota continues inside one CORE | `s3-wc-inner-rec-minor-bad` (the minor of a direct `Nat.rec` is the projection) | reject | reject | **accept** (every set, also the default pipeline) |
| | T | the same | `s3-wc-inner-caseson-bad`, `s3-wc-inner-matcher-bad` | reject | reject | **accept** under none, step 1, `bodies`, `lazyeval`, `directrules`, `code`, default minus the case trees; with `casetrees` the primary machine rejects and the pipeline still exits 0 (strict re-check = v0) |
| | wrong REJECT | canonical free variables keep the full-CORE memo of another binder instance | `s3-wc-canon-fvar-full-first-ok`: `And.intro (fun p => W0 p) (fun p => W p)` | accept | accept | **reject** (every set; the pipeline repairs it) |
| **S3-F2** | T | the REFERENCE engine: `mdata` is transparent for its caches | `s3-wc-pos-mdata-bad`: as `-pos-beta-bad` with `use (a = mdata b) (Eq.refl a)` | reject | **accept** | accept (every set) |
| | wrong REJECT | the reference engine: `mdata` hides `eagerReduce` from the official `is_eager_reduce` | `s3-mdata-eager-hidden-ok`: the argument `mdata (eagerReduce (X = X) (Eq.refl X))` is compared in PLAIN mode officially | accept | **reject** | declines (eager), pipeline rejects |
| **S3-F3** | T | `warm`: the tabled CHECKED type / the POS pair of an earlier declaration | `s3-wc-warm-term-reused-bad`, `s3-wc-warm-pair-reused-bad`, `s3-wc-warm-term-reused-reset-bad` | reject | reject | none: reject; `warm`, step 1, default: **accept** |
| | wrong REJECT | `warm`: the full-CORE memo of an earlier declaration | `s3-wc-proj-two-decls-ok`: `S.a : a = 5`, then `S.ok : a = b` | accept | accept | none: accept; `warm`, step 1, default: **reject** (repaired by the strict re-check) |
| **S3-F4** | T | `lazyeval` alone and the default set without the case trees | `s3-wc-inner-caseson-lazy-bad`: `W0' = use (gc 0 intro = 5) (Eq.refl 5)`, `gc n x := Nat.casesOn n (f x).1 (fun _ => 0)` | reject | reject | none, step 1, default: reject; `lazyeval`, `default,-code,-casetrees,-lazycases`: **accept** |
| **S3-F5** | T | `pchk` alone, and through `warm` in the default set | `s3-pchk-def-reused-bad` (`def S.a : True := W`, then `def S.bad := And.intro W0 W`), `-reset-bad` (an eager unit between: the session is reset, the bit stays), `s3-pchk-type-reused-bad` (the bit is set by an axiom's type), `s3-pchk-history-pass-bad` | reject | reject | none: reject; `pchk`, step 1, default, `default,-pchk`: **accept** |
| **S3-F6** | T | `k2`, default thresholds | `s3-k2-krule-subst-hot-bad` | reject | reject | none, default: reject; `default,k2`, all + `k2`: **accept** |
| | T | `k2`, test thresholds (`k2Hot=1`, `k2Share=0`) | `s3-k2-krule-subst-bad`; and the memo class: `s3-wc-inner-natfold-bad`, `-major-bad`, `-select-bad` (accepted by `k2` alone, rejected by v0) | reject | reject | `k2`, `k2,directrules`, `default,k2`: **accept** |
| | wrong REJECT | `k2` alone: WHNF answers through the rule, lazy delta does not | `s3-k2-krule-subst-hot-ok` | accept | accept | `k2`, `k2,directrules`: **reject** (repaired) |
| **S3-F7** | no wrong verdict (a decline) | `code` (default set, both jars): `Code.raw` faults (`E_INTERNAL`) when a closure built in a compiled body captures a variable that a let or a beta redex binds to a numeral below 2^31 | `s3-code-capture-let-literal-ok`, `-beta-literal-ok`, `-let-literal-casesOn-ok`, `-let-literal-succ-arm-ok`; found by the random programs (`s3-r2-005/006/010-ok`, `s3-r3-001-ok`) | accept | accept | none: accept; `code`, default, candidate: the primary machine DECLINES, the reference engine decides (exit 0) |

Kind and certificates. S3-F1 .. F5: every wrongly accepted declaration has the type `True /\ True` (or `True`) and is
built from the two sub-proofs `W0` and `W`; the certificate is the twin of each row - the same declaration with the
arguments in the other order - which the official checker accepts (`*-ok`), and `s3-wc-proj-alone-ok`. S3-F6:
`s3-k2-krule-subst-certificate-ok` (official accept): `Nat.add (c 0) 0 = 7` with the step written out
(`S.lem : forall b (e : t 0 = b), Eq.rec 7 e = 7` by `Eq.rec` on `e`). Why they cannot be kind F by this mechanism: a
memo that answers T was written by a T of the complete procedure, and an official T is a valid conversion; the
machine re-uses a valid conversion where the official kernel would have recomputed and failed.

Causes, one paragraph each.

**S3-F1.** `Conv.pairHit` answers POS for the value pair `(a, b)`. Officially the third argument compares a new term
pair (`a` against a beta redex whose value is `b`), `m_success` has no entry, the cheap `whnf_core(a)` is answered
`5` by the cache and `5` against `(f h).1` is false. The same with two binders: officially the second lambda has a
new free variable and empty caches for its terms; the machine has one `FVAR(Nat, 0)` and answers from the first
scope (tabled type of `W p`, or the pair). The third form is a missing memo: when a full CORE runs through iota (the
generic rule, or the direct rule) and the applied minor premise is a projection, CORE goes on inside the same call
and the projection value gets no full-CORE memo; officially the recursion of `whnf_core` inserts it. Where the
projection is reached through DELTA, beta or zeta, CORE is a call of its own and the memo is written
(`s3-wc-inner-natfold / -onesided / -major / -select / -beta / -let`: right in every set).

**S3-F2** (owner R2). The reference engine, or the loader in front of it, treats `a = mdata b` as the term `a = b`
and answers from its infer / success cache; the official caches see another term. The second row: officially
`is_eager_reduce(app_arg(e))` looks at `get_app_fn` of the `mdata` node, which is the node, so the comparison is
plain; the reference engine enters eager reduction. Both are verdicts of the DEFAULT pipeline.

**S3-F3.** A warm session keeps what the official kernel forgets at the end of a declaration: the CHECKED type of
`W` (so `S.bad` does not check `W` again after `W0`), the POS pair of its types, and the full-CORE memo of `a` (so
a later declaration's cheap query gets `5` where the official cache is empty). The FAILED mark of the same-head
argument comparison is kept as well (not attacked).

**S3-F4.** Officially the one-sided lazy delta unfolds `gc`, `Nat.casesOn`, iota with the CHEAP `whnf_core`, stands
at the stuck projection `a` and step 7 runs the full `whnf_core(a)`: entry. v0 does the same (LSTEP = DELTA + cheap
CORE, then c7) and writes the memo. `Conv.evalT / evalS` take `WHNF(gc 0 intro)` at once; inside that WHNF the
projection is reached through iota (the hole of S3-F1) and gets no memo. `lazyeval` therefore changes which
full-CORE memos exist; "order-only" (soundness-machine 4.3) does not hold. In the other direction it can write
memos the official loop never makes when that loop leaves early through `m_success` (not demonstrated).

**S3-F5.** `Infer.inferMiss / inferKnown`: a PCHK hit infers the term in ONLY mode. Officially the term is checked
again in every declaration, in that declaration's cache state, and `W` fails after `W0`; the fourth row sets the
bit for a term that passed only because its pair was in `m_success`. Theorem VALUES are not affected (they are
overlay terms and have no bit); definition values and types are. Not a finding: unsafe / partial constants - the
loader drops such declarations and a safe user is rejected as `UNKNOWN_CONSTANT` before any engine
(`s3-pchk-unsafe-const-bad`, `-partial-const-bad`, `-unsafe-in-type-bad`: official reject, pipeline reject).

**S3-F6.** Section 7.

## 4. The switches one by one

For each: what the code does, the fact it assumes, the E1 question (where can a stand-in, a cached answer, a skipped
comparison, another order decide a verdict), the rows, the result. "No row differs" refers to the matrix
`work/s3/out/matrix.tsv` (318 exports; the later rows were judged under the same sets in `matrix3.tsv` and on the candidate jar).

### 4.1 `same` (`Same.same`, `Conv.sameShape`, `Conv.convStart`)

As coded: at the start of CONV, after identity and the pair table missed and before any forcing, for a pair of two
SUSP / LAM / PI / PII / PROJ values or two NEU values with the same ACTIVE head that is not a regular definition and
the same number of arguments, a bounded walk (256 / 96 nodes, depth 40) compares the two values structurally: records
by kind and parts, closures by their terms under their frames (a variable is replaced by its slot, constants and
sorts by row and level under `ls`, binder variables by index, far frames through the REST chain). T adds a POS pair;
F means "not seen" and the complete procedure runs.

Assumes: two values with the same read-back are convertible for the official procedure at this point. That is the
assumption of v0's identity test (`a == b`), extended to closures that are two records. A slot holds either a
suspension of the argument TERM (compared as a term) or the value of an inert argument, so the walk sees the official
instantiated terms up to beta / zeta / projection-of-constructor / literal canonicalisation, and the official
procedure is reflexive up to these (whnf_core at the head, argument by argument below).

E1 question: (a) a T where the official terms differ and the official comparison fails - not constructible by my
analysis (no type is read, no two different sub-terms are ever equated) and not found; (b) a T that skips official
work with side effects on the cache (the official comparison of two syntactically different terms may run a full
`whnf_core`): the class of S3-F1, not `same`-specific - I could not build a row where `same` skips it and v0 does not.

Rows (`s3-same-*`, 16): closures equal up to their frames (accept), frames that differ (`3` against `4`; `intro`
against `h` under an abbrev head: reject, with the accepting twin), binder indices (`fun x y => x` against `fun x y
=> y`), domains (`Nat -> Nat` against `Bool -> Nat` as Pi closures; the twin through a beta redex), projection
indices, levels of constants inside closures, one closure term under two level substitutions (directly and as an
argument), far frames (two closures under 70 binders reading the outermost / the second outermost variable). All have
the official verdict under `same` alone and in the default set; no row of the matrix differs between `none` and
`same`, or between `default` and `default,-same`.

### 4.2 `pchk` (`Infer.inferMiss`, `pchkKnown`, `pchkSet`, `inferKnown`, `storeChecked`)

As coded: a term of the export region that is closed and has no level parameter gets the store's CHECKED bit when a
CHECKED inference of it completes (not after the declaration compared under eager reduction); a later CHECKED
inference of the term, in any declaration on any worker whose roots passed the summaries, runs in ONLY mode.

Assumes: passing check mode is a property of the term and the environment below it. Officially it is a property of
the term AND the declaration's cache state: S3-F5 (kind T). What I checked for kind F: the bit needs a closed term
(row `s3-pchk-open-term-bad`: one shared open body under two binder types; caught by seeded fault 3), no level
parameter (the trusted regime masks a fault there), safety (not the machine's: the loader). By reading: the bit is
set only by a completed CHECKED inference (`k_infer1`), never by an ONLY one; a PCHK hit of a lambda still compares
its PII with the expected type (`check` falls through to `infer` + `k_check1`).

### 4.3 `bodies` (`Eval.evalBody`, `evalNT`, `betaProj`)

As coded: the body of a beta step / INST and the domains and results of a telescope are evaluated without a tabling
record (`X_NOBODY`); a body that is a projection of one of the variables bound by this step is `MKPROJ` of that
argument (`X_SELECT`). Assumes: same values. The record that is not made is the EVAL memo of a second application of
the same closure to the same arguments; INFER / CHECK make their own record; the full-CORE memo sits on the value
record, not on the tabling record (`s3-wc-inner-select-*`: right in every set). Rows `s3-bodies-select-*` (4: the
right argument, the right field, a stuck argument). No row of the matrix differs between `none` and `bodies`.

### 4.4 `match` (`Infer.twMatchDom`, `tmatch`, `tmNode`, `tmApp`, `tmSame`)

As coded: in telescope mode the argument's inferred type is matched against the domain PATTERN term under the
arguments: a variable against the stored argument (same record through PEEK), a constant by row and EQUIVALENT
levels (`Levels.listsEq`), a sort by equivalent levels, an application against a NEU argument by argument, a binder
against the closure EVAL would return. T: no domain is built, no CONV. F = unknown: the complete procedure.

Assumes: a type that equals the built domain up to level equivalence is convertible with it officially - true for
the official procedure, which compares levels by `is_equivalent` wherever it meets them. It never equates two
different sub-terms (the "congruence" of its comment is identity up to levels), so the non-transitive triple cannot
enter (`s3-match-proof-arg-nontransitive-*`). The skipped comparison has no cache effect: a structurally equal pair
is answered by `t == s` officially. Rows `s3-match-*` (9): dependent domains, another argument, another level in an
argument, another level of the pattern's own head constant (a phantom level; this row was missing until seeded
fault 5). Package S's `tele-*` (14) stand as well.

### 4.5 `joint` (`Decl.joint`, `jointBinder`, `jointRest`)

As coded: while the declared type is a Pi and the value a lambda with the SAME domain term id, the domain is checked
once, one variable is opened for both, then INFER (rest of the type), the theorem's Prop test, CHECK (rest of the
value against the value of the rest of the type). Assumes: with identical domain terms the official
`is_def_eq_binding` compares no domain and the bodies under one variable. Officially the type, the value and the
comparison each have their own free variable; v0 has one canonical variable for the three anyway, so `joint` shares
nothing v0 does not (the canonical-variable rows of S3-F1 behave identically with and without it). Rows
`s3-joint-*` (6): a stuck body type, a theorem that is not a proposition, the wrong body type behind a second binder,
another domain (seeded fault 6), a convertible but different domain term (no joint binder).

### 4.6 `lazycases` (`Rules.unfoldLazy`, `Code.rulesCasesOn`)

As coded: in the unfolding step of lazy delta a head whose case tree has shape CASESON (its value is one direct
`I.rec` over its own parameters with eta-expanded minors) runs the tree; every other head, every miss: DELTA.
Assumes: `whnf_core(unfold_definition(t))` of such a head reduces the recursor at once, so no state of the official
loop lies between. Correct for this shape (the same-head argument comparison happens in `lstepBoth` before any
unfolding, whatever the hints of the definition). Note: `And.casesOn` of `init-prelude` is `fun .. t intro => intro
t.1 t.2` (projections, no recursor; `Prod.casesOn`, `Nat.casesOn`, `List.casesOn`, `Eq.casesOn` are direct
recursor applications): `dc h = 5` with `dc x := And.casesOn x (fun _ _ => 5)` is ACCEPTED officially
(`s3-ct-proof-major-axiom-ok`; my expectation was wrong) and by every set, while package S's `d` over `And.rec`
is stuck on `h`.

A MATCHER tree must not run there, and the reason is stronger than the comment in `Code.rulesCasesOn` says: between
the unfolding of the matcher and the reduct the official loop can stand at a REGULAR cases function and compare its
arguments. Row `s3-lazycases-matcher-over-regular-cases-ok` (official accept): `S.cz` is `Nat.casesOn` under another
name with regular hints, `S.m3` an abbrev matcher over it, `m3 M 0 A0 A1 intro = m3 M 0 A0 A1 h` under a function
motive: both sides stand at `S.cz .. intro` / `S.cz .. h`, same head, arguments by proof irrelevance: true; the
tree's reduct is `5` against a stuck recursor. Seeded fault 10 (every shape in the lazy step) is caught by it.
Rows `s3-lazycases-*` (5), `s3-ct-proof-major-*` (3). No row differs between `code,casetrees` and
`code,casetrees,lazycases`.

### 4.7 `directrules` (`Reduce.direct`, `directSlow`, `iotaDirect`, `pushHyps`)

As coded: if the term of a recursor rule is `fun params motive minors fields => minor_j fields (rec params motive
minors field_a) ..` (one motive, no indices; checked on the term once per recursor constant, rule and session), IOTA
is ONE APPLY of the minor to fields ++ hypotheses ++ the arguments after the major; a hypothesis the minor's body
does not read is not built. Assumes: the same reduct as the right-hand side applied and beta-reduced. The lambda
count, the minor's position, the field order and every hypothesis (same recursor, same levels, parameters / motive /
minors in order, a field as last argument) are verified; admission regenerates the rules, so a hostile rule term does
not reach it. It inherits the missing full-CORE memo of the generic iota (`s3-wc-inner-rec-minor-bad`, S3-F1; the
same verdict with and without the switch). Rows `s3-dr-*` (9): a function motive with an argument after the major,
an unread hypothesis, two recursive fields with the second hypothesis read (and the value the first would give),
a neutral minor. No row differs between `none` and `directrules`.

### 4.8 `lazyeval` (`Conv.lstepOnlyT / lstepOnlyS`, `evalT / evalS`)

As coded: when only one side unfolds and the other is weak-head normal by flag or memo and not a projection, the
unfolding side is replaced by its WHNF in one step, then the quick test. Assumes: the official loop would unfold that
side step by step with the other fixed, no same-head state can occur, an intermediate term is never identical to a
weak-head normal one. The verdict of the comparison itself is the same by that argument (rows `s3-lazyeval-*`, the
natfold / onesided rows). What the argument misses is the SIDE EFFECT: S3-F4. Seeded fault 7 (the one WHNF also when
both sides unfold) is caught by three rows.

### 4.9 `code`, `casetrees` (`Code`, `Elab`, `Rules.unfold`, `Code.rules`)

As coded: BODY / RULE / LEAF blocks compiled from the terms at the first request; in the WHNF loop a head with a
case tree (CASESON, or MATCHER with the matchers it calls inlined) runs the tree on its first lamArity arguments and
applies the alternative to the rest; NONE: DELTA. Assumes: the tree computes the reduct of DELTA + rule + beta on a
constructor major, no K-like or structure-like major, shape recognised by structure. Lazy delta does not use them
(except 4.6).

Value (kind F hunt): 29 rows `s3-ct-*` / 29 rows `s3-g-*` with a true claim and the value a slip would give -
swapped minors, swapped and duplicated fields, a non-unit alternative argument, a matcher nested in an alternative
of itself reading the outer field, the same variable split twice, a major that is an expression, numerals at 2^31,
2^32, 2^63, 2^64, a tree that computes a type, two nested enumerations, a list with its parameter, `Nat.succ` of a
variable and offsets, a stuck major, over- and under-application, a function motive whose alternatives split again,
structure eta in iota, projections and lets in leaves - and 40 random exports (batch R: 240 random definitions, 800
true claims whose values a Python evaluator computed; the official checker accepts all 40). Every row has the
official verdict under `code`, `code,casetrees`, `code,casetrees,lazycases` and the default set.

Cache: a tree's leaf and a compiled body hand their result back to the loop, so a projection at the head is reduced
by a CORE of its own and gets its memo - which is why the two v0 wrong accepts `s3-wc-inner-caseson-bad` /
`-matcher-bad` are rejected by the primary machine with `casetrees`. I found no row where the blocks lose a memo
that v0 writes; I cannot exclude one (a block that reduces a projection inside itself would), and the general
statement of section 5 applies: which memos exist is part of the verdict.

### 4.10 `warm` (not on my list)

S3-F3. Package S reviewed `warm` against the eager mode (S-F4) and judged the plain mode a function of the pair
("everything a session keeps is declaration-independent"); section 2 shows it is not.

## 5. The cache-history class: what an exact treatment would need (input for `official-divergence.md`)

The official verdict is a function of the pair AND of the set Q of terms whose full `whnf_core` ran in this
declaration (it matters for projection terms whose structure is not a constructor application under cheap
reduction), plus the frozen answers `m_success` / `m_infer_type[0]` for identical terms.

| Machine table | Official counterpart | Larger than it (wrong reject; wrong accept through a frozen T) | Smaller (wrong accept) |
|---|---|---|---|
| full-CORE memo on a PROJ value | Q | value-keyed (beta / zeta / mdata); canonical variables (`s3-wc-canon-fvar-full-first-ok`); `warm` (`s3-wc-proj-two-decls-ok`); `lazyeval` when the official loop leaves early | iota inside one CORE (`-inner-rec-minor`, `-caseson`, `-matcher`); `lazyeval` (S3-F4); `k2` rules (test thresholds) |
| pair table POS | `m_success` | value-keyed (`-pos-beta`, `-pos-mdata`); canonical variables (`-canon-fvar-bad`); `warm` (`-warm-pair-reused`) | - |
| tabled CHECKED type, PCHK bit | `m_infer_type[0]` | canonical variables; `warm` (`-warm-term-reused`); `pchk` (S3-F5) | `gated` (an untabled node is checked again; not attacked) |
| NEG, FAILED | - / `m_failure` | harmless inside a declaration (an F stays F); `warm`: wrong reject only | - |

Exact options, cheapest first (none built, no cost measured - I was asked for no timings):

1. **Two-answer rule (a decline, no mirror).** A cheap CORE of a projection whose structure is not a constructor
   under cheap reduction has two candidate answers, the stuck projection and its full CORE. Where a comparison used
   one of them, decide it for the other as well; if the two verdicts differ, DECLINE the unit (the reference engine
   decides). Concretely: (a) a projection pair decided T through the struct comparison is also compared through
   the fields; (b) a comparison that ended F after a cheap query was answered from a full memo is repeated with the
   stuck answer. Honest terms agree, so nothing real is declined; the cost is the field comparison after every
   successful struct comparison of `p.i =?= q.i` - the unfolding `lazy_delta_proj_reduction` exists to avoid.
   Frozen T answers (POS, CHECKED, PCHK) need the same question asked when they are WRITTEN: was any struct-route T
   used below this answer; if so the answer is marked and a marked answer is not re-used across a change of Q.
2. **Mirror Q where it is cheap.** Write the full-CORE memo on EVERY projection value a full CORE reduces at its
   head, also inside its own loop after iota (closes the "smaller" column for v0, `directrules`, `lazyeval`); no
   cross-declaration use of POS / CHECKED / full-CORE (`warm` keeps only what is not an answer: values, WHNF
   memos of non-projections) and no PCHK. This still leaves value keys and canonical variables (the "larger" column
   inside one declaration): not exact.
3. **The official caches themselves**: term-keyed, per declaration, a fresh variable per binder instance - that is
   the reference engine.

## 6. Seeded faults

`work/s3/seeded` = the published sources + 11 faults behind `-Ds3.bug=N` (`S3.java`; the patched lines are marked
`S3.BUG`), distribution `work/s3/dist-seeded`. For each fault: every export under the switch alone and under the
default set, with the fault and with `-Ds3.bug=0`; CAUGHT = the primary machine's verdict changes to one that is not
the official one (`work/s3/seeded.py`, outputs `work/s3/out/seeded.out`, `seeded2.out`).

| N | Switch | The fault | Caught by (verdict without -> with the fault) |
|--:|---|---|---|
| 1 | `same` | binder domains of two closures are not compared | `s3-same-pi-domain-bad`: R -> A (wrong accept), `same` alone and default |
| 2 | `same` | the level substitutions of two closures of ONE term are not compared | first run: NOT CAUGHT (`s3-same-ls-differs-bad` is decided by the binder case of the quick test); `s3-same-ls-in-arg-bad` (added): R -> A |
| 3 | `pchk` | the bit is set and read for open terms too | `s3-pchk-open-term-bad`: R -> A |
| 4 | `bodies` | SELECT takes the projection of the wrong argument | `s3-bodies-select-bad`: R -> A; `s3-bodies-select-ok`: A -> R |
| 5 | `match` | a constant of the pattern matches whatever its levels | first run: NOT CAUGHT (`s3-match-const-level-only-bad`: the levels sit in the arguments, which are compared by record identity); `s3-match-phantom-level-bad` (added): R -> A |
| 6 | `joint` | every leading Pi / lambda pair is a joint binder | `s3-joint-other-domain-bad`: R -> A |
| 7 | `lazyeval` | the one WHNF also when both sides unfold and the hints choose one | `s3-lazyeval-step-samehead-ok`, `s3-lazycases-intermediate-samehead-ok`, `s3-lazycases-caseson-step-ok`: A -> R (wrong reject) |
| 8 | `directrules` | every induction hypothesis is built from the first field | `s3-dr-two-hypotheses-bad`: R -> A; `-ok`: A -> R |
| 9 | `casetrees` | the arguments after the arity of a case tree are dropped | `s3-ct-over-application-ok`: A -> R (default set; with `code,casetrees` alone the row is not affected) |
| 10 | `lazycases` | every case tree runs in the lazy step, not only shape CASESON | first run: NOT CAUGHT; `s3-lazycases-matcher-over-regular-cases-ok` (added): A -> R |
| 11 | `code` | MKPROJ of a block reads the neighbouring field | 20 cells of 10 rows, e.g. `s3-bodies-select-stuck-bad`: R -> A, `s3-bodies-select-ok`: A -> R |

A suite that cannot catch a seeded fault has not tested the switch: three of the eleven were NOT caught by the suite
as it stood at the first run (2, 5, 10). Each pointed at a path I had not exercised - `same` is not reached for two
closures that the binder case of the quick test compares first; a level difference in an ARGUMENT of a pattern is
found by record identity, only a level of the pattern's own constants reaches `Levels.listsEq`; a matcher tree in the
lazy step differs from the official loop only if a regular cases function lies between - and the rows that catch
them were added (`s3-same-ls-in-arg-*`, `s3-match-phantom-level-*`, `s3-lazycases-matcher-over-regular-cases-*`).

## 7. `k2`: recommendation

**Keep it off (decided by the orchestrator, 21:58Z). As built it cannot be made exact by tuning.**

What it does: a definition without a K1 tree that the WHNF loop unfolds 32 times in a session, inside a declaration
that ran 16,384 ticks, gets a rule: `WHNF(c x̄)` is run once with `x̄` free variables of a reserved type, the result
becomes a SPLIT (stuck on a variable as a non-K major or a projected struct) or a LEAF (a template of the normal
form), and a firing substitutes the actual arguments.

The argument in `Rules` / NOTES-M3 D17 is "the leaf is a reduct of `c x̄` by official steps, and reduction commutes
with substitution". The second half is false for every step that is decided by a COMPARISON, because the official
`is_def_eq` is not stable under substitution (the pair `t x` / `s x` of section 1). **S3-F6**: for the axiom
`E : forall x, t x = s x` and `c x := Eq.rec (motive := fun _ _ => Nat) 7 (E x)` the K rule fires in the derivation
(INFER answers the type of `E x`, CONV compares `t x = s x` with `t x = t x` under the variable: true), the leaf is
the constant `7`, and the rule answers `c 0 = 7`; officially the K comparison fails for `0` and `c 0` is stuck.
`s3-k2-krule-subst-hot-bad` (ONE theorem: 200 walks of 400 recursor steps for the ticks, 40 comparisons
`Nat.add (c k) 0 = c k` that make `c` hot without being decided by its value, then `Nat.add (c 0) 0 = 7`): official
reject, `default,k2` ACCEPT with the default thresholds. The same family gives a wrong REJECT of `k2` alone
(`-hot-ok`: WHNF answers `7` through the rule, lazy delta uses no rule and finds `c 0` stuck - the machine
disagrees with itself). With the test thresholds the rules also skip the full-CORE memos of the generic walk
(`s3-wc-inner-natfold-bad`, `-major-bad`, `-select-bad`: accepted by `k2`, rejected by v0).

What a next attempt needs, in this order:

1. **No step of a derivation may be decided by a comparison or a type-directed test that mentions a derivation
   variable.** Today only K-like MAJORS are excluded from SPLIT; K itself fires inside the derivation. The node must
   be abandoned (or made a leaf at the term before that step) whenever the derivation's WHNF calls CONV, the proof
   test, unit-like, eta or structure eta with a variable in an operand. This is what RECONCILIATION section 2 asked
   for ("a dedicated value kind; any type-directed judgment abandons"): the free variable of a reserved type answers
   exactly the queries that should have stopped the derivation.
2. With 1, what is left is beta, delta, iota on constructor forms, projection of a constructor and literal folds on
   closed operands: these do commute with substitution. The leaf is then a reduct; the official WHNF of the instance
   is reached from it by the generic loop.
3. A rule must not change WHICH answers lazy delta and WHNF give for one value (the wrong reject above), and it must
   leave the full-CORE memos the generic walk leaves, or the policy of section 5 must cover it.
4. The rows of `batchK.py` as strict rows, and a new adversarial pass, before any gate is read. Nothing in this
   review says the gains (reachability -4.7 % CPU, `perf/magma-list-deep-n36`) cannot be had with 1 - 3.

## 8. Rows for the hostile suite (reclassified: official verdict AND kind)

`work/s3/manifest.tsv` (`mkmanifest.py`): MANIFEST_COUNTS, five columns: name, OFFICIAL verdict, kind, marks, what the
export attacks.

- **kind `T`** (MANIFEST_T rows, all `reject`): the declaration is derivable AS WRITTEN in the declarative theory -
  every comparison its proof term needs is a valid conversion (proof irrelevance, congruence, delta / iota) - and the
  official checker rejects it through cache order or non-transitivity. Expected from the machine: the official verdict
  OR accept. These are the rows of S3-F1 / F3 / F4 / F5 / F6 and the claims of the shape `d h = 5` over the
  non-transitive triple (`s3-joint-same-domain-bad`, `s3-lazyeval-step-samehead-bad`, `s3-lazycases-*-samehead-bad`,
  `s3-lazycases-matcher-over-regular-cases-bad`, `s3-match-proof-arg-nontransitive-bad`,
  `s3-same-closure-proof-slot-bad`, `s3-mdata-eager-plainform-bad`). Note the definition: a row whose STATEMENT is
  derivable but whose proof term is ill-typed (`s3-joint-second-binder-domain-bad`) is `strict`.
- **kind `strict`**: every other row. A `reject` row of this kind accepted by the machine would be kind F; an `accept`
  row must be accepted by the pipeline (the reference engine repairs a rejection or a decline of the machine).
- marks: what the matrices showed - `cache` (S3-F1: differs in v0), `warm` (S3-F3), `pchk` (S3-F5), `lazyeval`
  (S3-F4), `k2` (S3-F6), `ref` (S3-F2: the reference engine differs), `code-decline` (S3-F7), `eager-decline`.

To make them rows of `rlvm-machine/src/test/resources/s/`: gzip the exports, append the manifest lines (the kind
column is new there). `s3-wc-pos-mdata-bad` and `s3-mdata-eager-hidden-ok` are R2's (the strict mode). The 160 random
exports (`s3-r1-*`, `s3-r2-*`, `s3-r3-*`, 20 - 65 KB each) are regenerated by `batchR.py 40 1`, `40 2`, `20 3`; keeping
the four that hit S3-F7 and a handful of the others is enough.

## 9. Not established

- **Costs.** No timing of anything (the exact options of section 5, what `pchk` / `lazyeval` are worth without
  their kind T accepts).
- **E1's jar.** Everything ran on machine jar `15cb3777e598`; the typed bindings published after 21:26Z change
  `Infer` / `Eval` and were not re-run. The findings do not depend on a binder's type (no let, no redex with a
  declared domain other than its argument's type, except the row `-pos-beta-bad`, whose redex binds `x : A` to `h`).
- **Parallel workers, native image.** One worker, JVM launcher. PCHK across workers and the publication of code
  blocks under contention were not attacked.
- **More history sources.** I claim (section 2) that `m_whnf_core` read by cheap calls is the only plain-mode source
  and the other caches only freeze answers; that is by reading the official source, with three confirming exports,
  not a proof. The FAILED mark across declarations and `gated` (an untabled node is re-checked where the official
  infer cache answers) follow from the analysis and have no row.
- **`same` as a skipped comparison** (4.1 (b)) and **a block that loses a memo** (4.9): looked for, not found, not
  excluded.
- **Kind F.** Not found; the hunt covered value routes (case trees, bodies, rules, SELECT), the syntactic shortcuts
  (`same`, MATCH, joint) and PCHK's side conditions. Not covered: strings, quotients, mutual and nested inductive
  types in trees, `Acc.rec`, structure eta of a scrutinee beyond `Prod`, K-like recursors in trees (package S's
  `krule-*` / `k-eq-rec-*` stand), admission (forged recursor / constructor metadata).
- Package S's 329 and E1's 67 rows were not re-run by me; INT6's and E1's matrices stand for them.
