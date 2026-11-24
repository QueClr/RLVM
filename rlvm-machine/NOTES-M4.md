# NOTES-M4 - conversion (owner: M4)

Files: `Conv`, `Cargs`, `Pairs`; tests `M4PairsTest`, `M4ConvTest`, `M4ProbeTest`, `M4FactsTest` (fast),
`M4ArenaTest` (slow).
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
