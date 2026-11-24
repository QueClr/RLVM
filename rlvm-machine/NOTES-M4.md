# NOTES-M4 - conversion (owner: M4)

Files: `Conv`, `Cargs`, `Pairs`, `Same` (claimed 2026-11-24, wave 6); tests `M4PairsTest`, `M4ConvTest`,
`M4ProbeTest`, `M4FactsTest`, `M4SameTest`, `M4OfficialTest` (fast; the latter also has a slow matrix), `M4ArenaTest`
(slow); test resources `src/test/resources/m4/official/` (the conversion exports of the soundness review).

**READ FIRST (wave 6, 2026-11-24): section "Wave 6" at the end of this file.** Findings S-F1 / S-F2 of the soundness
review changed what the switches `facts` and `probes` mean in this package: the shortcuts B1, B2, B12 (congruence)
and B3, B4, the Prop mask of ETAS (skipped positions) are NOT reachable through any switch set any more. What the
tables below say about them describes code that now sits behind the non-official system property
`-Drlvm.machine.m4=congruence,skipargs` (measurement only).
References: ISA = `docs/design/ISA.md` (revision 2), R02 = `docs/research/02-kernel-typechecker.md`,
H = `docs/design/review/stepper-convention.md`.

## What is implemented

| Judgment | Entry step | Frames | ISA | Official rule (R02) |
|---|---|---|---|---|
| CONV | `Conv.conv`, inline part `convFast` | `CONV[0..10]`, `CONV2[0..8]` (6-8: steps 5 and 2) | 4.4.1 c0..c13, rec | `is_def_eq`, `is_def_eq_core` 8.1, `quick_is_def_eq` 8.2 |
| CBIND | `Conv.cbind` | `CBIND[0..6]` | 4.4.3 | `is_def_eq_binding` 8.2 |
| PIRR | `Conv.pirr` | `PIRR[0..3]` | 4.4.1 c4 | `is_def_eq_proof_irrel` 8.3 |
| LAZYD | `Conv.lazyd` | `LAZYD[0..5]` | 4.4.2 l0, l1 | `lazy_delta_reduction` 8.4 |
| LSTEP | `Conv.lstep` | `LSTEP[0..8]` (`Op.LSTEP` = 0x3C) | 4.4.2 l2, l3 | `lazy_delta_reduction_step` 8.4 |
| PROJD | `Conv.projd` | `PROJD[0..3]` | 4.4 | `lazy_delta_proj_reduction` 8.5 |
| CARGS | `Cargs.cargs` | `CARGS[0..3]` (2: pass B, 3: SIGD) | 6.1 | `is_def_eq_args` 8.4, `is_def_eq_app` 8.6 |
| CCLO (step 5) | `Conv.cclo` | `CCLO[0..1]` | 4.4.1 CCLO, B12 | none: congruence of substitution (R05 section 4, S11) |
| ETA | `Conv.eta` | `ETA[0..8]` | 4.4 | `try_eta_expansion_core` 8.6 |
| ETAS | `Conv.etas` | `ETAS[0..5]` (5: SIGD) | 4.4 | `try_eta_struct_core` 8.6 |
| UNITL | `Conv.unitl` | `UNITL[0..3]` | 4.4 | `is_def_eq_unit_like` 8.8 |
| STRX | `Conv.strx` | `STRX[0..1]` | 4.4 | `try_string_lit_expansion` 8.7 |
| pair table, UNDEC | `Pairs.status / add / undec / setUndec` | - | 1.5, 1.6, 5.4 | `m_success`, `m_failure` (section 5) |
| SAME (wave 6, `m4a`) | `Same.same` (a leaf: no frame, no tick of its own) | - | none (D28) | `is_equiv` at the start of `quick_is_def_eq` 8.2 |

Stages. **v0** (all switches off; strict mode): the complete procedure of ISA 6.3 rule 3 and nothing else: no
speculative segment, no fact, no plan, every `CARGS` position SAME. **Step 2** (`Op.SW_FACTS`): static
PROOF at c4 (B7), the comparison plan of `CARGS` as a skip mask (B3, B4, the agreement rule), the prop
mask of `ETAS`. **Step 5** (`Op.SW_PROBES`): B12 (`CCLO`), B1, B2, the quick refutation of pass A, pass B,
`UNDEC`. Both switches are off by default and change nothing of v0 when off (each use is behind a test
of `m.sw`).

How the three loops are written under convention H: the restart of `is_def_eq_core` after the second
`whnf_core` (c7), the lazy delta loop and the binder telescope are Java loops (`convLoop`, `lazydLoop`,
`bindLoop`) whose body is a chain of steps; a step that wants the next iteration returns the private
result `Conv.AGAIN` with the loop variables in `m.A2` / `m.A3` (and `Conv.Ext.r3`); a body resumed from
a frame returns into `convBack` / `lazydBack` / `bindBack`, which re-enter the loop. A resumed loop costs
one host frame, not one per iteration. `LSTEP` also returns `AGAIN` (= Continue) to its two callers.

## Deviations from ISA.md, each with its reason

| # | Where | Deviation | Reason |
|---|---|---|---|
| D1 | 4.4.1 c3, c7; 4.4.2 l3 ("quick(tn, sn) = the tests of c0 without the tables") | `Conv.quick` reads `POS` of the exact pair (never `NEG`) after the cheap `CORE`, at the restart after c7 and after every lazy delta step | the official `quick_is_def_eq` consults `m_success` at each of these places (R02 8.2). The results differ only where a pair entered the success cache under `ER` and is met again outside it; mirroring the official kernel there costs one probe per changed side / per unfolding |
| D2 | 13.3 (ops) | new frame op `LSTEP` = 0x3C: one `lazy_delta_reduction_step` as a judgment | `LAZYD` and `PROJD` both run the step (R02 8.4, 8.5) and a frame names one continuation (H2); registered in `Op` by M6 |
| D3 | 4.4.1 c8 ("both NEU, same length, call CONV(head, head)") | the heads are compared first, then the lengths | the official `is_def_eq_app` evaluates `is_def_eq(t_fn, s_fn) && t_args.size() == s_args.size()` in this order (R02 8.6); a kernel error raised by the head comparison must stay a verdict (R02 8.10) |
| D4 | 6.1 (CARGS precondition "the same length n") | under `SAMEDEF` spines of different length are compared from the last argument, pair by pair, and the answer is `F` at the end; under `APP` the answer is `F` before any comparison | this is what `is_def_eq_args` does (R02 8.4: "arity must be equal" is tested after the loop), and `LAZYD` does not test the lengths before it calls it; an error raised by one of those comparisons is a verdict |
| D5 | 6.1 pass A, WORK-PACKAGES "v0 = pass A + pass C" | v0 has no quick refutation: pass A is only the identity test of each pair (made inline, it is the first line of `CONV`) | 6.3 rule 3 ("CARGS pass C only"). The quick refutation answers `F` for a later pair before an earlier pair of the official order was compared; if that earlier pair raises a kernel error the official verdict is REJECT. It belongs to the steps that reorder (2, 5) |
| D6 | 4.4 ETAS ("b is NEU with CTOR head") | a bare CONST constructor with no parameter and no field (`Unit.unit`) also has the shape | official: `get_app_num_args(s) == nparams + nfields` holds with 0 arguments (R02 8.6); `x =?= Unit.unit` is answered by step 13, before the unit-like rule |
| D7 | 4.4.3, 4.4 ETA ("INST(t, x)" on a LAM) | the body of a LAM at `x` is `Eval.apply(lam, [x])` with `x` in one scratch word; `Eval.inst` is used for PI / PII only | the INST row of 4.3 has the precondition "PI or PII" |
| D8 | 4.4.1 c0 | a pair of two SORT records is never recorded in the pair table and `convFast` does not probe for it | c0 says "SORT / SORT: ret LVLEQ" (not "goto rec"); the probe would always miss |
| D9 | 4.4.1 c0 (order "PAIR, TICK, SORT") | unchanged; but the pair that is recorded is the pair of FORCED values (a SUSP side is forced before the lookup) | c0 "a side is SUSP: FORCE both, restart" |
| D10 | 4.4.1 c4 | PIRR is a judgment with frames of its own, called by c4; the test `PROOF(tn) == 1` (and "an immediate is not a proof") is made inline before the call, so the common case costs no call | H2; the ISA lists `PIRR` as an instruction that is "state c4" |
| D11 | 4.4.2 | `LAZYD` and `LSTEP` answer `UNDEF` inline (no tick, no event, no frame) when no state can do anything with the pair: `lazyInert` (no delta head, no Nat-like pair, nothing foldable) / neither side unfolds | H4: leaf cases return before the tick. The filter is conservative: a CONST of head class CTOR counts as Nat-like (it may be `Nat.zero`) |
| D12 | 4.4.2 l1 | the unary case of `reduce_nat` (`Nat.succ a`) is `WHNF` of the NSUC cell, taken as a result iff it is a literal; `NATFOLD` is called only for a NEU with exactly 2 arguments whose head has a binary nat-op code | `NatRed.natFold` is specified for the binary functions (4.3); 4.3.4 defines `WHNF` of an NSUC as exactly the official unary fold |
| D13 | 4.4.1 c2 | the test "s is CONST Bool.true" is `DeclTable.special(row) == BOOL_TRUE` with an empty level list, without a prelude guard | the shortcut is sound whatever the constant named `Bool.true` is (it only uses `whnf(t) == s`); the reference kernel has no guard here either |
| D14 | 4.4.1 c6 | official step 8 (the same free variable) has no code | identical refs are answered by every quick test before c6 is reached |
| D15 | 4.4.1 c8 | an NSUC cell is not treated as an application | two NSUC cells never reach c8 (`OFFSET` is definitive); against any other head `is_def_eq_app` compares `Nat.succ` with a non-unfoldable head, which is `F` without an error |
| D16 | 1.4 (ticks) | one tick per `CONV` that is not answered inline, one more per restart after c7, one per `LSTEP` that unfolds (the "one tick per iteration" of `LAZYD` / `PROJD`), one per started `CBIND`, `PIRR`, `LAZYD`, `PROJD`, `ETA`, `ETAS`, `UNITL`, `STRX`, `CARGS` with a pair to compare | "one per generic frame started, one per iteration of the LAZYD loop" |
| D17 | 4.4.1 c0 | `CONV` of NONE or HOLE is `fault(E_INTERNAL)` | fail closed: neither is ever a side of a comparison |
| D18 | 4.4.2 l2 | `is_delta` = head class DEFN and word c of the head's CONST record is not -1 (the level count matches, `Values.mkConst`); if `DELTA` then answers NONE the step is `fault(E_INTERNAL)` | the two tests must agree; a disagreement is a broken invariant, never a verdict |

| D19 | 4.4.1 c1 ("[B1] both NEU, same DEFN row") | step 5: the same-head probe also runs for two applications of the same recursor or quotient eliminator (budget 1024), not only of the same definition | row B1 of 6.3 reads "same-head argument probe for any unfoldable head". Measured need: `perf/loop-unfolded-once` compares `Nat.rec M Z S1 m` with `Nat.rec M Z S2 m` (S1, S2 convertible closures of one term, m a literal): without the probe both sides are iota-reduced and the comparison descends 10^6 levels; with it the arguments are compared first (S1 against S2 by B12) and the answer is T by congruence. An F of such a probe enters `FAILED` like any completed same-head comparison (it then only suppresses another probe: the complete procedure reads `FAILED` for delta heads only) |
| D20 | 4.4.1 c0 ([B12]) | step 5: a T of B12 on two SUSP records writes `POS` for the pair of suspensions, and B12 reads that entry before it starts a segment | c0 looks the pair up after forcing, which a T of B12 avoids; without the entry the same pair of suspensions would run `CCLO` again each time |
| D21 | 6.1 pass B | step 5: no done-mask: a pair that pass B answered T is answered by `POS` in pass C; pass B uses the official order (last to first), not the plan's probe ranks; `UNDEC` is consulted before a pair is probed and written after a U | `CONV` records `POS` itself; probe ranks are a `Cong` block (M7), which `Cargs` does not read: the plan is computed from the fact record directly (D22) |
| D22 | 6.1 ("plan = CONG(c)") | step 2: the plan is a 64-bit skip mask computed by `Cargs.skipMask` from `Facts.propMask`, `Facts.unreadMask` and `Facts.dep` at the start of each comparison; the `Cong` block (classes TYPE, probe ranks) is not read | the two classes that change what is compared (UNREAD, PROOF / PROOFL) are exactly these masks; TYPE and the ranks only order pass B. One dependency fewer (the code store), same skips |
| D23 | 6.1 pass A, 3.5 | step 2: when the head's fact record does not exist yet `CARGS`, `ETAS` and c4 call `Facts.sigd` (a judgment: frames `CARGS[3]`, `ETAS[5]`, `CONV2[8]`) | "derived lazily, by any worker, at the first request for a fact of c" (3.5); pass A is then not frame-free on the first comparison per constant |
| D24 | 5.5 shortcheck | a static PROOF answer is re-derived by `ISPROP(VTYPE(tn))` with all switches off (`Conv.staticProofCheck`, through `Debug.memoEnter` / `memoExit`); a `CARGS` / `ETAS` T that skipped positions and a T of B1 / B12 by `Debug.shortCheck` (the complete `CONV` of the pair) | `Debug.shortCheck` re-derives CONV answers; a PROOF flag is not a CONV answer |

| D25 | 6.2 (budgets) | step 5: a per-declaration probe debt (`Conv.Ext.debt`): every segment that ends in U adds its budget, every segment that ends in T halves the debt; at 2^17 ticks no further segment (B12, B1, pass B) is started in this declaration | not in the ISA. Without it a probe that fails at every level of a deep descent costs its budget at every level (measured 1,350 ticks per level against 45). The debt is counted in ticks and reset at `beginDecl`, so outcomes and costs stay functions of the declaration alone; suppressing a probe only hands over to the complete procedure |

## Measured (2026-11-24, host under load average 100-160; ticks and call counts are deterministic)

- Main tree, v0, in-process against the reference engine (M8 `report.sh`): tutorial 141 files / 395 units and
  `bugs/` 34 files / 1428 units DIFF-CLEAN; all 230 files: 227 clean, 0 wrong, 3 declines by `E_MEM` under
  the harness budget of 2 GiB (`perf/beta-ladder`: no CONV involved; `perf/gf3-matrix-product`: big
  literals; `perf/loop-unfolded-once`: conversion, see D19); Init: 58,547 units agree, 0 declined.
- Init through the launcher (`-X engine=machine`, v0, one worker): 57,938 units accepted, 0 declined;
  call events: CONV 2,490,397 (+ 1,308,774 pair-table hits: 34%), PIRR 1,510,479, LAZYD 1,236,329,
  LSTEP 3,950,827, CARGS 1,175,521, CBIND 784,317, PROJD 16,382, UNITL 13,471, ETAS 2,832, ETA 1,339;
  VTYPE 3,034,150 in total. 61% of the comparisons that are not answered inline pay the dynamic
  proof-irrelevance test (VTYPE + ISPROP): the target of B7.
- `perf/loop-unfolded-once` unit 1365: v0 `E_MEM` after 44.4 M ticks (487,000 nested levels, 45 ticks and
  about 4 KB of records per level); with `probes`: ACCEPT in 481 ticks (reference kernel 0.15-0.26 ms).
- Step 5 on a descent where every probe fails (the same test before D19): 1,350 ticks per level against
  45 in v0: a failing probe at every level of a deep structural descent costs its budgets (B12 1024,
  pass B 256). Bounded since by the probe debt (D25).
- With `-Dm8.switches=facts` and with `probes` (sandbox, per declaration against the reference engine):
  tutorial and `bugs/` DIFF-CLEAN in both configurations. All 230 with `probes`: 227 files clean, 0 wrong;
  `loop-unfolded-once` and `beta-ladder` clean; 3 declines: `gf3-matrix-product` (`E_MEM`, literals) and the two
  `magma-list-*` (the harness's 60 s limit under a host load of 160; run alone with a 600 s limit
  `magma-list-pair-n21` is accepted in 28.67 M ticks, v0 28.72 M).
- Init, per declaration against the reference engine, `facts,probes` with the debug mode `shortcheck`:
  DIFF-CLEAN, 58,547 units, 0 declined, no audit failure (`machine-diff-init-m4-facts-probes-shortcheck.txt`).
- Init through the launcher, one worker, all 57,938 units accepted and 0 declined in every row (CPU of
  the whole process = user + sys, of the worker thread from the statistics; host load 100-160, one run
  each, so the times are indicative only; ticks and calls are exact):

  | switches | process CPU s | worker CPU s | ticks | CONV calls (pair hits) | PIRR | VTYPE | SIGD | CCLO |
  |---|--:|--:|--:|--:|--:|--:|--:|--:|
  | none (v0) | 20.98 | 13.36 | 78,233,834 | 2,490,484 (1,308,774) | 1,510,479 | 3,034,150 | - | - |
  | facts | 17.95 | 11.15 | 74,645,066 | 2,446,038 (1,283,673) | 231,156 | 1,604,389 | 58,578 | - |
  | probes | 19.82 | 12.29 | 76,845,667 | 2,836,343 (2,080,941) | 1,146,274 | 2,617,453 | - | 7,872 |
  | facts,probes | 18.24 | 11.93 | 73,686,539 | 2,728,560 (1,908,177) | 148,375 | 1,499,403 | 58,598 | 6,827 |

  B7 removes 85% of the dynamic proof tests (PIRR 1.51 M -> 0.23 M) and half of the VTYPE calls. The
  machine is not near its targets (7.0 s CPU on Init after step 1): sessions are still one declaration
  long (57,937 resets) and the step 1 switches were off in these runs.

- Cedar hot slice (`work/workloads/slices/cedar-hot-ax.ndjson`, owner note 9) through the launcher, one
  worker, main tree of 10:30Z (after erratum E1 of RECONCILIATION section 5), all 8,064 engine checks
  accepted, 0 declined in both rows (one run each, host load above 100):

  | switches | process CPU s | worker CPU s | ticks | CONV calls (pair hits) | CBIND | CARGS | LAZYD | LSTEP | PIRR | VTYPE | CCLO |
  |---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|
  | none (v0) | 9.12 | 4.13 | 18,104,024 | 1,384,581 (416,497) | 970,380 | 330,584 | 157,668 | 318,084 | 299,077 | 455,468 | - |
  | facts,probes | 9.72 | 4.20 | 14,707,760 | 1,193,389 (661,472) | 755,020 | 342,793 | 73,940 | 235,174 | 10,922 | 157,596 | 24,037 |

  On Cedar conversion is mostly binder comparison: 0.7 `CBIND` per `CONV` (an inferred lambda type, PII,
  against the expected Pi). The switches cut ticks by 19% and the dynamic proof tests by 96%; the CPU
  times of the two rows are inside the noise of this host.
- The Cedar blow-up of 10:13Z (five declarations at 73.7 M ticks each) was not conversion's order: the
  LET flag on `let` values of unfolded bodies set OPEN on closed values, so `LAZYD` l1 refused the Nat
  fold that the official kernel performs and unfolded `Nat.div` / `Nat.mod` successor by successor
  against literals near 2^63 (trace: `CONV(SUSP .., 9223372036854775222)`, `..221`, ..). Fixed in `Eval`
  by its owner (erratum E1); found independently from the conversion trace. Lesson for this package: the
  guard of l1 and c2 reads OPEN as the official `has_fvar`; any over-approximation of that flag is a
  cost cliff here.

## Not implemented (stated, not hidden)

- Probe ranks and the TYPE class of the `CONG` block (pass B order), see D21, D22.
- The delta loops (`LAZYD`, `PROJD`) have no cycle detection: where the official kernel does not
  terminate (`corner-cases/eta-struct-major-*-no-whnf`; the reference kernel declines there by Brent's
  scheme) the machine runs until its step budget, the poll or the memory watermark ends the declaration.
- Nested failing probes are only bounded per declaration (D25), not avoided.
- B2 is implemented but fires only for values whose PROOF flag is already 2 (after a dynamic or static
  test of that very value); it is not observable in the tests beyond agreement with v0.

## Assumptions about the neighbours (also in `work/status/requests.log`)

- M1: NEU = (a head, b spine run, aux length, HCLASS of the head); CONST = (a row, b level list, c
  substitution id, -1 = wrong level count; aux nat-op code); PROJ = (a struct, b structure row, c field);
  closures a = binder term; HCLASS DEFN for definitions and theorems; OPEN on everything that contains
  an FVAR or a LET suspension. `Conv` writes the PROOF flag bits of a record itself (`k_pirr2`).
- M2: `apply` / `inst` accept HOLE for an unread binder and may return a SUSP (CONV forces); `dom`
  returns a forced value; M_DOM is read inline.
- M3: `core` never returns a SUSP and returns `v` for a WHNF-flagged `v` (the call is skipped then);
  `offset` answers UNDEF for a constructor that is not `Nat.zero`; `natFold` answers NONE when the
  official `reduce_nat` does not fire; `delta` unfolds whenever `is_delta` (D18) holds.
- M5: `vtype` for every value kind; `isprop` fails on a stuck sort (never `F`); `m.ER` is set and
  restored by TWALK / the ERF frame, `Conv` only reads it.

## Wave 6 (2026-11-24, 13:23Z - ): what changed, why, and what was measured

Tools of this session are in `work/m4/` (`nbuild.sh`: a private CE native image -O3 whose machine jar is a tree's
classes and whose other jars are those of `work/machine-int/dist-g-native`; `m.py` / `batch.sh`: the integrator's
`m.py`, one batch = one hold of the measurement lock, the configurations interleaved, rows in `work/m4/measure.tsv`;
`gate.sh`: `testkit diff` through the JVM launcher of such a distribution; `gate-short.sh`: `report.sh --tree` with
debug mode `shortcheck`; `ticks.sh` / `tickdiff.py`: ticks per published test under two configurations;
`profpatch*.py`: a PROFILING COPY of the module, never the sandbox). The host ran 30 agents (load average 170-250),
so **CPU seconds of this session are noisy (about +-5 % between runs of one row) and the instruction count is the
reliable column**; every comparison is inside one batch and, where possible, inside ONE binary (the switch off is
the baseline).

### W1. Where conversion's cost is (profile, step 1 switches; `work/m4/stats/prof*-{init,cedar}.out`)

Exclusive attribution of ticks, records and run words to what runs while a `CONV` is on the stack:

| made inside conversion | Init | Cedar slice |
|---|--:|--:|
| share of ALL records / run words | 64 % / 70 % | 50 % / 43 % |
| by `Reduce.core` (almost all of it the `whnf_core` after a lazy-delta unfolding) | 38 % / 41 % | 16 % / 9 % |
| by `Reduce.delta` (lazy delta) | 21 % / 22 % | 11 % / 8 % |
| by `APPLY` + `INST` + `DOM` of `CBIND` | 3.4 % / 5.4 % | 21 % / 24 % |
| by `VTYPE` + `ISPROP` of `PIRR` | 0.7 % / 0.4 % | 0.3 % / 0.3 % |

`LSTEP` by case on Init (2.43 M): **only one side unfolds 1.90 M (78 %)**, both with different hints 0.39 M, same
regular head 44 k (38 k answered by `CARGS`), same non-regular head 17 k, different heads with equal hints 80 k.
Most unfolded: `List.casesOn` 318 k, `HAdd.hAdd` 136 k, `Add.add` 135 k, `List.zipWithAll._f` / `.match_1` 127 k
each, `Int.casesOn` 118 k, `List.brecOn` 82 k. Heavy top-level comparisons on the Cedar slice by head: `Eq .. =?=
Eq ..` 48,661 calls / 3.70 M ticks (`Eq Bool true true =?= Eq Bool (decide p) true`: evaluation), `PI =?= PI`
191,647 calls / 2.81 M ticks. Lazy delta is used as an evaluator (decide, omega's reflection), and the K1 code of
step 4 did not reach it (`Rules.unfold` is called by `WHNF` only). Consequence for this package's targets: the
PIRR / VTYPE targets are met by B7 (below) but are worth almost no CPU (a `VTYPE` is a memo read); the CPU of
conversion is the one-sided lazy-delta loop and the binder comparison.

### W2. Findings S-F1 / S-F2: `facts` and `probes` gave verdicts the official checker does not give (FIXED)

The soundness review (requests.log 13:48Z; `docs/design/review/soundness-machine.md`) showed exports that the
official checker and the reference engine REJECT and the machine ACCEPTED with `probes` (B1, B12) and with `facts`
(B3 with the agreement rule, the Prop mask of ETAS; B4 by my own counter-example, confirmed as
`facts-unread-noncongruent-bad`). Cause, one sentence: **the official `is_def_eq` is not a congruence where a
reduction inspects a proof** - `And.rec f (And.intro ..)` reduces to `5`, `And.rec f h` (h an axiom) is stuck, and
`And.intro .. =?= h` holds by proof irrelevance; so "the arguments / frame slots / earlier positions are pairwise
convertible" does not imply that the official procedure equates the two applications, closures, or the types of two
proofs. It is not transitive either (`d intro = d h` by the same-head comparison, `d intro = 5` by unfolding,
`d h = 5` false), so "both proofs were accepted at one domain" does not help. Under owner note 11 ("resemble
official by default") the declaratively valid T is a wrong verdict.

| # | Decision (published 14:30:18Z) | Reason |
|---|---|---|
| D27 | `Op.SW_FACTS` runs only **B7** in this package (static PROOF at c4; `PIRR` still compares the types). `Op.SW_PROBES` runs only **pass B, the quick refutation of pass A, UNDEC and the probe debt**. B1, B2, B12 (`Conv.congruence`) and B3, B4, the ETAS mask (`Conv.skipArgs`) need the system property `-Drlvm.machine.m4=congruence,skipargs` (`Conv.Ext.opts`; not a staging switch on purpose: no switch set, `all` included, can turn them on; never in strict mode) | B7 answers exactly what `is_prop(infer_type t)` answers (manifest sorts), and the comparison of the types is still made. Pass B and the refutation only reorder: every answer is an answer of the complete `CONV` on one pair, and an F of one pair is an F of `is_def_eq_args`. The other six equate terms by an argument about the THEORY; the official ALGORITHM is not closed under that argument |
| | `M4OfficialTest` (fast at every publication): the 29 conversion exports of the review (`m4/official/manifest.tsv`) must get the OFFICIAL verdict under the richest switch set of this package; slow: under 11 sets. One test pins down that the two non-official options do accept three of the attacks, so that nobody takes them for a default | the gate the corpora cannot give: they contain no attack |
| | Not done: "pure" B1 / B12 (a segment in which proof irrelevance may not decide and only pairs derived in such a segment are read). I worked the argument through and could not close it: reductions of the context can ask for the TYPES of the differing leaves (K, structure eta in iota, unit-like, proof irrelevance of an enclosing term), and the types of two terms that are convertible by reduction alone can still be convertible only through proof irrelevance (a constant and its unfolding whose declared type was accepted through a conversion that used it). `Same` (D28) is what remains of B1 / B12 when only what the official kernel itself tests is kept | when a judgment is not sure it declines |

Verification: `judge.py --manifest batch2.tsv / batch3.tsv / batch6.tsv` (official checker, reference engine, machine
under 6 switch sets incl. `all@1`) on the published code: 11 + 12 + 6 exports, 0 flagged; the review confirmed it on
the main tree of 15:05Z (17 switch sets x 329 exports; the 16 rows of S-F1 / S-F2 are strict rows of its manifest
now). Findings S-F4 / S-F5 / M6-F26 (eager reduction: the official answer depends on the mode and on per-declaration
caches keyed by terms with unique free variables; a POS of one binder scope answered under ER in another) are
handled by M6 in the facade (a declaration that enters eager reduction is declined: `Machine.enterER`,
`erDeclines`); `Pairs` and the NEG rule are unchanged. Measured for that decision (profiling copy, step 1): a
comparison under ER occurs in 150 of Init's 57,938 engine checks, 188 of Std's 100,096, 11 of the Cedar slice's
8,064, 5 of reachability's 4,224, and those comparisons are 0.5 % / 0.5 % / 0.4 % / 0.02 % of the ticks.

### W3. New deviations

| # | Where | Deviation | Reason |
|---|---|---|---|
| D26 | 4.4.2 l2 (`Conv.lstepOnlyT / lstepOnlyS`, `evalT / evalS`); switch `lazyeval` (`Op.SW_LAZY_EVAL`, registered by M6 at my request) | when exactly ONE side of a lazy-delta step unfolds, the other side is not a projection and is weak-head normal (`Reduce.whnfFast` answers itself: flag or memo), the unfolding side is reduced by ONE `Reduce.whnf` and the pair continues at the step's quick test (frames `LSTEP[5]` / `LSTEP[7]`, which already carry exactly that continuation). A WHNF that does not move the side (never for a delta head) falls back to the official single step | the official loop would unfold that side step by step, `whnf_core(unfold_definition(tn))`, until it no longer unfolds, with the other side fixed. No same-head state can occur (the other side has no delta head: this is the reason ISA 3.6 gives for keeping single DELTA steps, and it does not apply), an intermediate form (delta-headed) is never identical to a weak-head normal form, `quick` refutes only WHNF forms, OFFSET needs constructor / literal forms, and the Nat fold is what WHNF does; so the loop can leave early only through a pair-table hit, which states a T that the completed loop finds again. The composition is `whnf`: reduction steps of the official kernel on one side, no congruence, no type, no skipped comparison. The work then runs in M3's WHNF loop: its memo, and BODY blocks / case trees / (later) derived rules when step 4 is on. Reviewed by the soundness review (15:06Z: no wrong verdict on 329 exports; its reading in `soundness-machine.md` 4.3) |
| D28 | 4.4.1 c0 (`Conv.convStart`, `Same`); switch `same` (= experiment bit `m4a`; named by M6 15:40Z) | after the tick and before anything is forced, for a pair of two suspensions, two closures of one kind, two projections, or two applications of the SAME head and length whose head may reduce (flag ACTIVE), the bounded walk `Same.same(a, b)` (256 nodes, 96 for applications; 40 levels; no judgment, no forcing, no reduction, no type) decides whether the two VALUES stand for the same EXPRESSION: terms under frames in lockstep, a frame variable replaced by its slot (far frames: the REST chain), constants and sorts by their levels under `ls`, binder variables by index, an application node against a NEU, a binder node against a closure. `true`: T, POS for the pair (under `shortcheck` re-derived by the complete procedure first). `false` ("not seen", never "different"): the complete procedure. A declaration whose failed walks visited 2^16 nodes starts no further walk (`Conv.Ext.sameWaste`) | the official `is_def_eq` starts with `is_equiv(t, s)` (structural equality of the two expressions, R02 8.2) and repeats it after `whnf_core` and after every lazy-delta step. The machine had it only as identity of refs, which is not complete for closures and suspensions: `SUSP(t, frame)` stands for `t[frame]`, and two suspensions of different terms (the same sub-term at two binder depths), or of one term under frames whose slots are different records of one expression, are one expression officially. `perf/loop-unfolded-once` is exactly that (R04: nano-lean `same`, fuel 512; R05: gruszka `closure_congr` / `same_val`). It is NOT one of the congruence shortcuts of S-F1: it never asks whether two different sub-terms are convertible; its T states what `is_equiv` states, up to the normalisations the value model applies everywhere (beta / zeta of EVAL, canonical literals, PEEK). Which pairs are walked was chosen by measurement (W4): applications of a rigid head are left to c8, which compares their arguments one by one and meets the walk again on every closure among them; a suspension against a value never was the same expression in the corpora. The probe events are counted under B12 (T = seen, U = not seen) |
| D29 | 4.4.2 l2 (`Conv.unfoldT / unfoldS`) | the delta step is `Rules.unfoldLazy` (M3), which is `Reduce.delta` unless `casetrees,m7a` are on; then a head of shape CASESON on a constructor major gives the alternative at once | request of M7 (14:17Z) and M3 (15:05Z): for that shape the iota belongs to the `whnf_core` of the same official step, so no state of the official loop is skipped. Their measurement (Init -2.6 % instructions on top of `code,casetrees`), their gate; this package only calls it |

### W4. Measurements

Baseline b0 (the code as published before this session, jar 608b84b2b142; ONE run per row, load 26-42), CPU s /
instructions G: Cedar slice step 1 (`warm,gated,expect`, 4 MiB) 3.54 / 45.9, + `facts` 3.36 / 45.9, + `probes`
3.04 / 41.8, both 3.28 / 41.9; Init 8.23 / 109.6, 7.68 / 109.0, 8.22 / 106.6, 7.82 / 106.1; reachability 8.45 /
60.9, 8.16 / 60.9, 8.22 / 60.8, 8.51 / 60.8; Std 14.68 / 185.1, 14.65 / 182.6, 13.11 / 177.8, 12.66 / 176.5. In
instructions the OLD `facts` (B3, B4, B7) was worth -0.5 % on Init and -1.4 % on Std, nothing on the Cedar slice and
on reachability, and the OLD `probes` (B1, B12) -2.7 %, -3.9 %, -8.9 % (Cedar slice): those are the numbers the
non-official shortcuts were worth.

Batch b1, `lazyeval` (binary `work/m4/dist-e1`, three runs per row interleaved, load 200-250; switches
`warm,gated,expect,facts,probes` of the pre-fix semantics), mean CPU s / instructions G, without -> with:

| corpus | step 1 + facts,probes | the same + code,casetrees |
|---|---|---|
| Init | 8.01 / 106.1 -> 7.84 / 105.3 (-2.1 % / -0.8 %) | 6.76 / 92.5 -> 6.53 / 86.9 (-3.4 % / **-6.1 %**) |
| Std | 14.01 / 176.5 -> 13.80 / 175.5 (-1.5 % / -0.6 %) | 12.50 / 157.0 -> 12.19 / 149.7 (-2.5 % / **-4.6 %**) |
| Cedar slice | 3.14 / 41.9 -> 3.18 / 41.8 (+1.3 % / -0.2 %) | 2.88 / 40.7 -> 2.90 / 40.2 (+0.7 % / -1.2 %) |
| reachability | 8.44 / 60.8 -> 8.41 / 60.8 | 3.14 / 29.4 -> 3.13 / 29.4 |

Counters (deterministic): LSTEP per declaration Init 42.0 -> 17.4, Cedar slice 25.4 -> 12.9; with `code,casetrees`
Init records 17.92 M -> 16.33 M, ticks 42.5 M -> 38.7 M. The 230 published tests: 266.07 M ticks -> 266.07 M, the
largest change of one test +12 ticks (no cost cliff; `work/m4/ticks/e1-{off,on}.tsv`). KEPT (lower instruction
count on all four, CPU lower on Init and Std; the Cedar slice's +1.3 % CPU is inside the noise of that batch and its
instruction count fell).

`Same`, first version (published 15:24Z: near frames only; every pair with a suspension, two closures, two
applications of one length, two projections; 256 nodes), on top of `warm,gated,expect,facts,probes,lazyeval`
(counters, JVM launcher): Init ticks 55.69 M -> 52.70 M (-5.4 %), records 25.79 M -> 25.19 M, `CBIND` 415,589 ->
164,511 (-60 %); walks 256,122 seen / 908,265 not seen. Cedar slice ticks 18.38 M -> 16.40 M (-10.7 %), records 8.62
M -> 8.10 M, `CBIND` 697,516 -> 512,061 (-27 %); walks 115,233 / 300,078. The 230 published tests (native image):
642.7 M ticks -> 310.9 M, every test equal or lower; `perf/loop-unfolded-once` 332.0 M ticks and two E_MEM declines
(444 s of wall under load) -> **272,629 ticks, accepted by the machine, 1.2 s**: the open item 7 of the integrator's
list is closed without a congruence probe.

**But it did not pay in instructions.** Batch b2 (binary `work/m4/dist-same`: that first version; six configurations
in one binary, three runs each interleaved, load 80-150; mean CPU s / instructions G; `work/m4/batch-b2.out`):

| corpus | A = step 1 | F = A + facts,probes | L = F + lazyeval | S = L + same (first version) | K = A + code,casetrees | KS = K + facts,probes,lazyeval,same |
|---|--:|--:|--:|--:|--:|--:|
| Init | 7.44 / 105.3 | 7.75 / 106.5 | 7.79 / 105.6 | 7.91 / 108.9 | 6.62 / 91.1 | 6.36 / 90.1 |
| Std | 13.51 / 177.3 | 13.76 / 179.3 | 13.97 / 178.3 | 13.26 / 181.3 | 11.86 / 156.3 | 11.94 / 154.9 |
| Cedar slice | 2.89 / 39.5 | 2.93 / 39.6 | 2.84 / 39.6 | 2.72 / 40.7 | 2.68 / 38.9 | 2.69 / 39.6 |
| reachability | 8.06 / 59.1 | 8.20 / 59.1 | 8.11 / 59.1 | 8.20 / 59.1 | 2.98 / 28.7 | 3.02 / 28.7 |

Reading: (1) `facts,probes` in their exact form COST instructions (Init +1.1 %, Std +1.1 %): the fact records are
derived for every constant (58,566 `SIGD` on Init) and have one consumer left, and pass B pays a TRY per pair; (2)
`lazyeval` gives back 0.6-0.9 % on Init / Std; (3) the first `Same` cost +3.1 % instructions on Init, +1.7 % on Std,
+2.8 % on the Cedar slice although it removed 5-11 % of the ticks: the walks that do not see identity were not
free. Profile of the walks by shape (profiling copy, `work/m4/stats/sm-{init,cedar}.out`, step 1 + lazyeval + same;
seen / not seen): Init `NEU / NEU` same head 125,879 / 520,152 (of which inductive-type heads 95,416 / 232,473,
definition heads 21,585 / 266,686 with 17,574 walks that ran out of 256 nodes), `NEU / NEU` other head 176 /
57,320, a suspension against a value 154 / 178,000, `PI / PI` of different terms 106,136 / 25,809, `LAM / LAM`
20,956 / 29,278; Cedar slice `PI / PI` of different terms 171,048 / 6,878, `LAM / LAM` 41,625 / 9,620, `NEU / NEU`
same head 28,678 / 72,110. And every frame comparison called the value comparison once per slot, 63 times for a
frame that is HOLE almost everywhere.

`Same`, second version (D28 as it stands; changes: far frames walked - on the Cedar slice half of the closures that
are one expression have more than 63 bindings in scope -; a suspension against a value and applications of a rigid
or of different heads no longer walked; 96 nodes for applications; identical slots of two runs compared in a tight
loop, eight for one unit of fuel). Counters, the same switch set as above (JVM launcher; both rows with M6's
eager-reduction declines): Cedar slice walks 257,325 seen / 66,604 not seen (first version with far frames 243,199
/ 126,888; without 115,233 / 300,078), ticks 18.38 M -> **11.10 M (-40 %)**, call events 15.44 M -> 9.10 M, records
8.62 M -> 6.58 M (-24 %), run words 94.6 M -> 69.0 M (-27 %); Init walks 244,547 / 380,087 (first version 257,196 /
905,059), ticks 55.69 M -> 51.97 M (part of it the declined declarations). `perf/loop-unfolded-once`: 266,315
ticks, accepted. Batch b3 (binary `work/m4/dist-tune`, CPU and instructions): table in
`work/status/machine-M4.log` (line "BATCH b3").

### W5. Rejected or not done, with numbers

- **`Same` also in the quick test after `whnf_core` and after every lazy-delta step** (tried as `m4b`): Init ticks
  52.703 M -> 52.705 M, Cedar slice 16.404 M -> 16.455 M, failed walks 0.91 M -> 1.52 M on Init. No gain: the pairs
  it would find are found by the walk at c0 of the argument comparisons. REJECTED, code removed.
- **`Same` on every pair of applications and on a suspension against a value** (the first version): +3.1 % / +1.7 % /
  +2.8 % instructions on Init / Std / Cedar slice (batch b2). REJECTED in that form; replaced by the selection of
  D28.
- **`lazyeval` also when the fixed side is a stuck recursor application without the WHNF flag or memo**: measured
  how often the condition of D26 fails (profiling copy): Init 414,035 one-sided steps taken by WHNF, 13,206 not
  taken because the fixed side is such an application, 6,769 an NSUC cell, 1,166 a suspension (PROJD); Cedar slice
  32,001 / 5,139 / 19 / 35. Not worth a second condition; NOT DONE.
- **CONG(c) read by CARGS (task item 2): NOT WIRED, on purpose.** The only classes of a plan that change what is
  compared are UNREAD and PROOF / PROOFL, i.e. B4 and B3, which S-F2 showed are not the official verdict; TYPE and
  the probe ranks only order pass B. With `skipargs` off a plan has nothing to say to pass A or pass C. Its cost
  side is small too: with the old `facts` the whole of B3 + B4 + B7 was worth 0.6 G of Init's 109.6 G instructions.
  If a proof position is ever to be used again it must be as "compare by type without ISPROP" (what PROOF = 2 of B7
  already does for the value at c4), not as a skip.
- **Pair table "one probe per new pair" (task item 4): NOT DONE, bound computed instead.** On Init (step 1) 2.13 M
  pairs are added, 1.9 M `CONV` reach the table without a hit, and `quick` probes about 1.7 M times after
  reductions: about 7.5 M probes of roughly 25 instructions = 0.19 G of 109.6 G (0.17 %). Remembering the empty slot
  of the miss until the `add` can save at most the 2.1 M insert probes (0.05 %), and only when no other pair was
  added in between, which is rare for a comparison that was not answered inline. Not measurable in CPU on this host;
  not worth a second code path in the one table every memo of conversion depends on. Entry size (16 bytes, base
  2^12 entries = 64 KiB; about 5,900 pairs per 4 MiB session on Init: one or two doublings) is adequate.
- **LSTEP cost (item 4):** D26 halves the LSTEP count; D29 serves the two-sided steps.
- `B2` survives only inside `congruence`: it compares the types of the UNREDUCED terms, the official kernel those
  of the `whnf_core` results.

### W6. Switches of this package: state and recommendation

| Switch | Guards (this package) | Evidence | Recommendation |
|---|---|---|---|
| `facts` | B7 only | exact by construction (manifest sorts); review rows strict; diff gates | **not for the default set on this package's account**: PIRR -92 % and VTYPE -69 % calls on Init, but together with `probes` +1.1 % instructions on Init and Std (batch b2; b3 separates the two): the records cost more to derive than B7 saves |
| `probes` | pass B, quick refutation, UNDEC, debt | order-only; review rows strict; diff gates | **not for the default set**: without B1 / B12 it has no gain (b2, b3) |
| `lazyeval` | D26 | diff gates: 230 tests + four corpora, with and without `code,casetrees`, 0 differences, 0 declines; `shortcheck` runs; review 15:06Z; no cost cliff on the 230 tests | **ready to be on by default** (best together with step 4) |
| `same` (= `m4a`) | D28 (`Same`) | diff gates of both versions: 230 tests + four corpora + the subst-prop mutants, 0 differences, no decline but M6's eager-reduction ones; `shortcheck` (first version) on Cedar slice, reachability, Init, Std: DIFF-CLEAN; review exports (29) official under every set with it; `M4SameTest` (6 tests incl. far frames) | **for review by S; then on by default if batch b3 confirms it in CPU** (see the log): it removes the last machine decline of the 230 tests that is not an eager-reduction decline (`loop-unfolded-once`) and 40 % of the Cedar slice's ticks |
| `-Drlvm.machine.m4=congruence,skipargs` | B1, B2, B12, B3, B4, ETAS mask | S-F1, S-F2: wrong verdicts | **never a default**; kept only to measure |
