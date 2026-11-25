# rlvm-machine - switches and the default configuration (the ONE list)

> **STATE, 2026-11-24T21:00Z: THE MACHINE IS NOT THE DEFAULT ENGINE.** It was from 19:41Z to 20:35Z (the
> integration described below) and was taken back by the orchestrator (`MachineProvider.priority()` = 10, jar
> `15cb3777e598`): E1's hostile exports (`work/e1/exports/e1-*.ndjson`, 67 exports with official verdicts)
> show a family of WRONG ACCEPTS against the official checker, **E1-F1 .. E1-F5**, three of them in the
> generic tier v0: a bound variable is typed by what its environment slot holds (the let value, the argument of
> a re-inferred beta redex, the other side's comparison variable) or an expected type is kept as the lambda's
> own, and such a type is only CONVERTIBLE with the declared one - the official relation is not transitive
> (`work/status/requests.log` 20:10Z - 20:36Z; E1's patch for F1: `work/e1/patches/let/`, design for F2 - F5:
> `work/e1/patches/E1-F2-F5-design.md`). None of the gates below could see it: they compare the machine with the
> reference engine on real corpora and mutants, where the two types are the same, and package S's 329 exports
> have no row of this shape. Everything below is the state of the machine as an engine that runs with
> `-X engine=machine`: its default SWITCH SET, cap and bounds stand, and so do the measurements. The machine
> becomes the default engine again when E1-F1 .. F5 are fixed, E1's rows are strict rows of the hostile suite,
> and the gates of this file have been run again.

Every optimisation of the machine that changes what is computed sits behind a staging switch
(`Op.SW_*`, `Machine.sw`, `m.on(Op.SW_X)`); a strict engine (`EngineConfig.strict`, the driver's re-check
engine) runs with none, whatever is configured. This file is the single list: name, bit, owner, state,
the gate and the measurement that justify the state. Owner of the file: M6 until 2026-11-24 17:19Z, then
the integrator of wave 6 (`work/status/machine-integration.md`, heading "Wave 6": every run named here,
with its command and its report file).

## How switches are set

| Where | Form |
|---|---|
| launcher | `-X machine.switches=LIST` (or the system property `rlvm.machine.switches=LIST`; `-X` wins) |
| tests | `Fixture.switches("LIST")`, `Debug.parseSwitches("LIST")` |
| in code | `m.on(Op.SW_X)` |

`LIST` is read from left to right, starting from the empty set: `name` or `+name` adds a switch, `-name`
removes it, `none` empties the set, `default` adds `Op.SW_DEFAULT`, `all` adds the staging switches of ISA
section 11 except `k2` (bits 0-10; no experiment bit old or new, not `t2`, `lazyeval`, `levels-complete`).
Without the setting an engine runs `Op.SW_DEFAULT`. Examples: `none` = v0; `default,probes`;
`default,-same`; `default,k2`. A switch that needs another one brings it, whatever the order of the list
(`Op.implied`, applied to the final set and again by `MachineEngine`): `skipargs` brings `facts`, `congruence`
brings `probes` - `default,skipargs,-facts` still runs `facts`. A strict engine (`-X policy=strict`, a strict
`EngineConfig`) has no switch at all.

Related settings (not switches): `-X machine.session-kib=N` (the machine's own cap on a warm session,
section "Sessions"), `-X machine.unit-ticks=N` (the machine's own tick bound per unit, default 2^27,
NOTES-M6 F31), `-X machine.hostDepth=N` (host depth bound, default `Op.HOST_DEPTH` = 128),
`-X machine.eager-probe=N` (with `eager`: the tick budget of one probe of the plain phase, default 2^22, 0 = none;
finding INT7-F1 below the table of named switches), `-X machine.cong-spend=N` (with `congruence`: the ticks its
segments may use in one declaration, default 2^18, 0 = no cap; below the table), `-X machine.declines=FILE` (one line per non-ACCEPT outcome of the machine), `-X machine.debug=...` (audits,
README), `-Drlvm.machine.sizes=...` (base table sizes, NOTES-INT I1), `-Drlvm.machine.keep=R,W,S` and
`-Drlvm.machine.pool=off` (kept and pooled session arrays, NOTES-M6 F25).

## The default configuration (integration of wave 6, 2026-11-24; the switch set of 2026-11-25)

| What | Default | Decided by |
|---|---|---|
| engine | **the reference engine** (`MachineProvider.priority()` = 10 since 20:35Z; 100 = the default engine from 19:41Z to 20:35Z). `-X engine=machine` runs the machine as primary engine: the reference engine then re-checks every rejection and decides every unit the machine declines | NOT the machine while E1-F1 .. E1-F5 are open (the note at the top); the gates below and step 4 of the integration (eight corpora, DiffEngine) were green |
| switch set `Op.SW_DEFAULT` | **`warm,gated,expect,bodies,pchk,match,joint,code,casetrees,lazycases,directrules,lazyeval,same`** (`Op.SW_WAVE6`, the set of 2026-11-24) **+ `eager,skipargs,congruence`** (2026-11-25; `skipargs` brings `facts`, `congruence` brings `probes`; with the probe budget `-X machine.eager-probe` = 2^22 and the cap `-X machine.cong-spend` = 2^18): section "The default set of 2026-11-25" | gates and measurements below, one line per switch in "Named switches" |
| session cap `MachineEngine.DEFAULT_SESSION_CAP` | **1 MiB** (`-X machine.session-kib=1024`) | section "Sessions": 512 KiB and 1 MiB are equal within 2 % everywhere, 2 and 4 MiB cost 3-4 % CPU and wall clock on mathlib |
| host depth bound `Op.HOST_DEPTH` | **128** | 512 and 2048 measured: no gain (section "Host depth bound") |
| Truffle tier (`t2`, module `rlvm-truffle`) | **off**; the jar is in every distribution and is used only under the switch | T3's quiet numbers: parity on reachability, +38 % wall at 8 workers on the Cedar slice, +39 % on cedar (section "Truffle tier") |
| universe levels | the official relation (`levels-complete` off) | owner note 11; both engines; the two arena imax corner cases are rejected |
| platform | CE native image `-O3` (the arena's binary); the JVM launcher is the faster platform once warm (table in `docs/BENCHMARKS.md`) | docs/PLATFORM.md, `work/status/warm.md` |

**v0 is `-X machine.switches=none`.** The module's tests are not affected by the default set: `pom.xml`
gives the test JVM `rlvm.machine.switches=none`, and a `Fixture` engine has no switch unless the test names
some. In force in EVERY configuration, v0 included, is the eager-reduction rule (section "Sessions": a
declaration that enters eager reduction is declined) - a correctness rule, not an optimisation.

### The default set of 2026-11-25 (INT7): wave 6 + `eager` + `skipargs` + `congruence`

Decided by warm wall clock on all eight corpora under the owner's notes 12 (warm wall clock, 8 workers, pipelined),
13 (the "practical" policy: kind T accepts are tolerated) and 16 (the fastest default), by the orchestrator's rule
of 2026-11-25 00:17Z: primary = the geometric mean of the eight warm JVM pass times; guard = no corpus more than 3 %
slower than the set without the switch in question, over at least three interleaved rounds. JVM launcher, 8 workers,
pipelined reload passes, every row in a `bench/quiet.py` window, the five sets interleaved round by round inside ONE
window per corpus (rows `work/int7/warm.tsv`, table and the stages `work/status/machine-int7.md`). A cell = the mean
over the rounds of the median later pass, s [rounds; min..max]; change against `default,eager` (here `default` is the
set of wave 6). con-leche and reachability with the final settings (probe budget 2^22, cap 2^18).

| corpus | `default` | `default,eager` | `default,eager,skipargs` | `default,eager,congruence` | `default,eager,skipargs,congruence` | eager against default |
|---|--:|--:|--:|--:|--:|--:|
| init | 0.573 (+0.0 %) [3; 0.56..0.58] | 0.573 [3; 0.57..0.58] | 0.567 (-1.2 %) [3; 0.55..0.58] | 0.570 (-0.6 %) [3; 0.57..0.57] | 0.567 (-1.2 %) [3; 0.55..0.58] | -0.0 % |
| std | 1.143 (+12.1 %) [3; 1.12..1.16] | 1.020 [3; 0.99..1.06] | 1.017 (-0.3 %) [3; 1.01..1.02] | 1.010 (-1.0 %) [3; 1.00..1.03] | 1.027 (+0.7 %) [3; 1.01..1.04] | -10.8 % |
| cedar | 1.507 (+5.4 %) [3; 1.48..1.54] | 1.430 [3; 1.41..1.44] | 1.457 (+1.9 %) [3; 1.43..1.50] | 1.427 (-0.2 %) [3; 1.41..1.44] | 1.433 (+0.2 %) [3; 1.43..1.44] | -5.1 % |
| reachability | 0.383 (-1.7 %) [6; 0.36..0.41] | 0.390 [6; 0.37..0.42] | 0.388 (-0.4 %) [6; 0.36..0.42] | 0.372 (-4.7 %) [6; 0.36..0.39] | 0.397 (+1.7 %) [6; 0.38..0.41] | +1.7 % |
| con-leche | 1.230 (-2.1 %) [3; 1.17..1.30] | 1.257 [3; 1.22..1.28] | 1.287 (+2.4 %) [3; 1.18..1.38] | 1.273 (+1.3 %) [3; 1.24..1.30] | 1.240 (-1.3 %) [3; 1.20..1.31] | +2.2 % |
| cslib | 5.650 (+7.3 %) [2; 5.54..5.76] | 5.265 [2; 4.99..5.54] | 4.875 (-7.4 %) [2; 4.79..4.96] | 4.780 (-9.2 %) [2; 4.72..4.84] | 4.555 (-13.5 %) [2; 4.40..4.71] | -6.8 % |
| navier-stokes-euler | 6.137 (+0.9 %) [3; 5.89..6.48] | 6.080 [3; 5.98..6.19] | 4.037 (-33.6 %) [3; 3.92..4.18] | 3.793 (-37.6 %) [3; 3.68..4.01] | 3.217 (-47.1 %) [3; 3.14..3.26] | -0.9 % |
| mathlib | 20.490 (+13.7 %) [1; 20.49..20.49] | 18.020 [1; 18.02..18.02] | 13.390 (-25.7 %) [1; 13.39..13.39] | 12.950 (-28.1 %) [1; 12.95..12.95] | 12.720 (-29.4 %) [1; 12.72..12.72] | -12.1 % |
| **total** (8 corpora) | **37.11** (+9.0 %) | **34.03** | **27.02** (-20.6 %) | **26.17** (-23.1 %) | **25.16** (-26.1 %) | -8.3 % |
| **geometric mean** (8 corpora) | **2.065** (+4.3 %) | **1.980** | **1.801** (-9.1 %) | **1.758** (-11.2 %) | **1.720** (-13.2 %) | -4.1 % |

`default,eager,skipargs,congruence` has the lowest geometric mean (1.720 s; `default,eager` 1.980 s; the set of wave 6
2.065 s) and is at most 1.8 % slower than `default,eager` on any corpus (reachability); `eager` is at most 2.2 %
slower than the set without it (con-leche, after the repair of INT7-F1 below). The one borderline cell: `skipargs` on
top of `congruence` on reachability, +3.7 % by the median of an eight-round window (12 ms on a pass measured in steps
of 10 ms; +2.1 % by the mean of its warm passes), against -17 % on navier-stokes-euler and -4.7 % on cslib.
Adversarial pass with official verdicts for exactly this set: reviewer S3, no kind F
(`docs/design/review/soundness-default-set.md` section 10); the shortcuts are theory-sound as implemented (S2,
`soundness-shortcuts.md`). Gates of the set (frozen tree = this jar; reports `work/status/machine-gate-int7-tF-default-tree.md`,
`machine-mutants-tree.md`): M8 full gate PASS WITH DECLINES (all 230 tests 15,804 accepted / 41 rejected / 0 declined;
Init 57,938 / 0; Std 100,095 / 1 declined by the E1-F6 rule; reachability 4,224 / 0; Cedar slice 8,064 / 0; SOUNDNESS
0, COMPLETENESS 0); mutants 17,775 for v0 and for the set: SOUNDNESS 0, COMPLETENESS 0; `SHostileSlowTest` 56 switch
sets x 1,196 rows; DiffEngine through the launcher 1,141,195 checks (230 tests, Init, Std, cedar, cslib, 16 Mathlib
slices), 0 differences; the eight corpora accepted with 8 and 4 workers.

### Gates of the default set (all on the main tree of 2026-11-24 19:10Z; reports in `work/status/`, logs in `work/int6/`)

| Gate | Result |
|---|---|
| soundness review, every finding of severity wrong ACCEPT / wrong REJECT (`docs/design/review/soundness-machine.md`) | S-F1, S-F2 (congruence shortcuts of `probes` / `facts`): removed from every switch set by M4; S-F4, S-F5: closed by the eager-reduction decline rule; S-F3 (levels): closed, both engines compare officially by default. `SHostileSlowTest`: 32 switch sets x 329 hostile exports = 10,528 judgments against the OFFICIAL verdict: **0 unexpected, 0 known finding still reproduces** (the strict test `noKnownFindingIsLeft` passes for v0, `warm`, step 1, `probes`, `facts`, `all`, the default set with and without `same`); every new switch alone, on step 1 and in the default set is among the 32 |
| M8's gate, generic tier (`report.sh gate`: `machine-gate-v0-int6.md`) | **PASS WITH DECLINES**: differential SOUNDNESS 0 / COMPLETENESS 0 on tutorial, bugs, all 230 (15,845 engine checks: 15,797 accepted, 40 rejected, 8 declined), Init (57,774 / 0 / 164), Std (99,893 / 0 / 203), reachability (4,216 / 0 / 8), Cedar slice (8,053 / 0 / 11); convention equivalence 0 different; audits 0 failures; pipeline 230 / 230 |
| M8's gate, the default set (`machine-gate-default-int6.md`) | **PASS WITH DECLINES** (1,697 s): differential SOUNDNESS 0 / COMPLETENESS 0: all 230 (15,845 engine checks: 15,800 accepted, 40 rejected, 5 declined), Init (57,774 / 0 / 164), Std (99,893 / 0 / 203), reachability (4,216 / 0 / 8), Cedar slice (8,053 / 0 / 11); tier equivalence with the generic tier: 0 different outcomes or reasons, values of K1 judgments on 175 + 1,486 constants equal; convention equivalence (host depth 0 and unbounded against 128): 0 different; audits (`poison,scopeaudit,maskaudit`; `memocheck,shortcheck`): 0 outcome differences, 0 `E_AUDIT`; pipeline 230 / 230 and the four corpora, 0 MACHINE-INCOMPLETE, 0 WARM-ANOMALY. Every decline is eager reduction |
| mutant corpora through the real driver with `-X engine=machine`, v0 and the default set (`report.sh mutants`: `work/int6/machine-mutants-run1.md`) | 17,775 mutants per set: **SOUNDNESS 0, COMPLETENESS 0**, agree 16,092, declined 1,683 (= the reference pipeline's), MACHINE-INCOMPLETE 0, WARM-ANOMALY 0; per declaration (1,008,568 per set) machine accepts / reference rejects 0, machine rejects / reference accepts 0; the machine declined 29 (v0) / 31 (default set) declarations, all eager reduction |
| DiffEngine through the JVM launcher, default configuration (`work/int6/diff/b-default-*.txt`; the same switch list given explicitly on the jar before the default changed: `a-cand-*.txt`) | 0 differences on 760,839 units: 230 tests 15,840 of 15,845 (the two heaviest perf files in a 12 GiB JVM of their own), Cedar slice 8,053 of 8,064, reachability 4,216 of 4,224, Init 57,774 of 57,938, Std 99,893 of 100,096, con-leche 36,186 of 36,274, cedar 143,675 of 144,098, cslib 395,202 of 397,608; every unit not compared is an eager-reduction decline of the machine |
| **E1's hostile exports** (67, official verdicts; found after the publication of 19:41Z) | **FIVE OPEN FINDINGS, wrong ACCEPTs**: E1-F1 (let: v0 and the default set), E1-F2 (beta redex with an open domain, re-inferred in ONLY mode: v0 and the default set), E1-F3 (lambda body re-inferred under the comparison variable: v0, and through the driver's strict re-check the default pipeline), E1-F4 and E1-F5 (`expect`: the comparison variable carries the lambda's own domain; the expected codomain is stored as the lambda's). The machine is not the default engine until they are closed |
| the driver's invariance suite with the machine as primary engine, default set (`rlvm-driver/bin/invariance.sh --no-compile --engine machine`, 20:55Z) | published 231 files x 9 schedules and init-prelude / Init / Std / cedar x 9 schedules (1, 2, 4, 8 workers, 1-byte session cap, pipelined with a publication every 1 / 7 / 64 units): 0 wrong, 0 declined, 0 schedule differences, 0 pipelined declines |
| the eight corpora, machine as primary engine, 8 workers, native image (`work/int6/w5.out`) | all ACCEPT, machine rejected 0, declined for eager reduction only: Init 164, Std 203, Cedar slice 11, reachability 8, con-leche 88, cedar 423, cslib 2,406, navier-stokes-euler 251, mathlib 6,711 of 714,712; 0 warm anomalies, 0 MACHINE-INCOMPLETE, 0 disagreements |

The two findings of the integration itself:

- **M8-F1 (fixed, `Code.kval` / `Code.resume`).** A `KVAL` whose value arrived through a frame (a suspended
  evaluation of the closed term) did not fill the site's inline cache; a later execution of the site evaluated
  the term again, and since `gated` no longer tables it that cost a tick the unsuspended run did not pay: the
  tick count depended on the host depth bound (2 of 8,064 declarations of the Cedar slice). The frame now
  carries `DST_KVAL` and the fill happens at the resume.
- **The gate's lanes shared two things they must not** (harness, not the machine): the store's PCHK bits
  (every lane has a table of its own now, `M8Harness.Lane.build`) and the binder hints of the code store
  (`CodeStore.addSub` -> `TermStore.setBinderHint`: lanes are engines of their own over ONE store; an audit lane
  elaborates in another order and its hints made the compared lanes walk terms their blocks would have run).
  `M8Lanes` now runs lane 0 and the convention lanes first, then the generic tier and the audits. Before that
  the gate of the default set reported 270 to 45,821 "different" declarations on Std and the Cedar slice,
  never the same twice; without `code,casetrees,lazycases` or with the lanes of one convention group alone it
  was clean (bisection in `work/int6/bis-*.out`). A real run has one machine engine per store; a foreign hint
  is validated and ignored (`CodeStore.hint`), so the effect was on ticks only.

### Measurements of the default set

Native image `-O3` (`work/int6/dist-a`, jars as published 18:16Z, machine `8a8ea5644b24`), 8 workers,
pipelined load, every row inside a `bench/quiet.py` window; wall clock of the median later pass of
`-X passes=4` (mathlib: 2 passes) / process CPU of that pass; `work/int6/warm.tsv`, windows `w1` - `w4`.

| Configuration | Init | Std | Cedar slice | reachability | cedar | mathlib |
|---|--:|--:|--:|--:|--:|--:|
| `none` (v0) | 1.11 s / 7.53 s | 1.93 / 13.16 | 0.54 / 2.98 | 2.58 / 7.76 | 2.56 / 17.74 | - |
| `warm,gated,expect` (the default of 16:24Z) | 1.09 / 7.14 | 1.84 / 12.59 | 0.55 / 3.00 | 2.49 / 7.37 | 2.45 / 17.29 | 30.68 / 215.7 |
| the default set without `same` | 0.82 / 5.32 | 1.52 / 10.32 | 0.53 / 2.75 | 0.61 / 1.60 | 2.44 / 14.78 | - |
| **the default set** (cap 1 MiB) | **0.81 / 5.26** | **1.51 / 10.09** | **0.46 / 2.40** | **0.62 / 1.64** | **2.09 / 13.99** | **29.28 / 204.8** |
| the default set, cap 512 KiB | 0.82 / 5.22 | 1.53 / 9.92 | 0.43 / 2.36 | 0.66 / 1.58 | 2.10 / 13.73 | 29.20 / 202.6 |
| the default set, cap 2 MiB | 0.82 / 5.49 | 1.53 / 10.48 | 0.47 / 2.45 | 0.61 / 1.61 | 2.15 / 14.51 | 30.55 / 211.8 |
| the default set, cap 4 MiB | 0.85 / 5.58 | 1.62 / 10.84 | 0.52 / 2.61 | 0.67 / 1.62 | 2.46 / 14.92 | 30.26 / 212.2 |
| default set / `warm,gated,expect`, CPU | -26 % | -20 % | -20 % | -78 % | -19 % | -5 % |

Two more readings of the default set in window `w4` (interleaved with the H10 image): Init 0.84, 0.81 s /
5.38, 5.37 s CPU; Std 1.52, 1.51 / 10.23, 10.19; Cedar slice 0.46, 0.46 / 2.50, 2.51; reachability 0.61, 0.58 /
1.60, 1.55; cedar 2.06, 2.11 / 14.01, 14.25: the rows above repeat to 2-4 % in CPU. The table of the final
distribution against the reference engine, gruszka, nano-lean and the official checker is
`docs/BENCHMARKS.md`.

## Named switches

| Name | Bit | Owner | What it turns on | State | Evidence |
|---|---|---|---|---|---|
| `gated` | 0 `SW_GATED_TABLING` | M2 | EVAL tables a node only if its flags say it is shared, a binder body or invariant, or it lies 4+ inert levels below its nest root (off: every non-leaf node is tabled) | **ON by default** (16:24Z) | part of step 1; the quadratic cliff on unshared dependent nests is fixed (NOTES-M2 D21: 4,000 levels 8,038,031 -> 60,017 ticks) |
| `pchk` | 1 `SW_PCHK` | M5 | shared check facts: a closed, level-parameter-free export term that passed check mode is inferred in ONLY mode by later declarations and workers (never after an eager-reduction comparison) | **ON by default** (wave-6 integration) | M5: -1.5 to -1.8 % instructions on Init / Std alone, -3.7 / -4.3 % with `match,joint`; 9,110 tutorial mutants 0 / 0; the gates above |
| `expect` | 2 `SW_EXPECT` | M5 | B9: lambdas and applications are checked against the expected type | **ON by default** (16:24Z) | part of step 1 (NOTES-M5) |
| `warm` | 3 `SW_WARM` | M6, M5 | a machine session survives an ACCEPT; forward in file order only; reset on every other outcome and at the caps | **ON by default** (16:24Z) | part of step 1; safe because of the machine's own cap and the eager-reduction rule |
| `facts` | 4 `SW_FACTS` | M5, M4 | fact records; of the consumers only B7 (static PROOF) since M4's fix of S-F2 | **ON by default** since 2026-11-25, brought by `skipargs` | official-exact, but +1.0 to +2.1 % CPU at -0.1 % instructions on Init, Std and the Cedar slice (M4 batch b3): pays only for itself |
| `tele` | 5 `SW_TELE` | M7, M5 | K1 TELE blocks, MATCH (B5), the IC table | off | M7, three native batches: no corpus gains, the Cedar slice loses 2-6 % (10 % fewer records on Init, 5.4 M more activations) |
| `code` | 6 `SW_CODE` | M7, M3 | K1 BODY / RULE blocks, LEAF blocks (B11), elaborated at the first request | **ON by default** (wave-6 integration) | M7 batch 3 (with `casetrees`, against v0, one worker): reachability 8.79 -> 1.56 s CPU, Init -16 %, Std -14 %, Cedar slice -5 %; hostile exports `k1-*` (43), `quot-*`; the gates above |
| `casetrees` | 7 `SW_CASETREES` | M3, M7 | CASESON / MATCHER case trees (B8), with the matchers they call inlined | **ON by default** (wave-6 integration) | as `code`; inlining alone: reachability 3.03 -> 1.56 s |
| `probes` | 8 `SW_PROBES` | M4 | pass B of CARGS, the quick refutation, UNDEC and the probe debt (order only) since M4's fix of S-F1 | **ON by default** since 2026-11-25, brought by `congruence` | official-exact, but +1.4 to +3.2 % CPU on Init, Std and the Cedar slice (M4 batch b3) |
| `k2` | 9 `SW_K2` | M3 | derived rule trees of `Rules` (ISA 3.6) for hot definitions without a K1 tree | off | gates green and small gains (M3 batch 4: reachability -4.7 % CPU, others -0.5 to -1 %; `perf/magma-list-deep-n36` 12.6 -> 1.4 s), hostile matrix clean with it, BUT its derivation variables are free variables of a reserved type and not the dedicated value kind of RECONCILIATION section 2 (NOTES-M3 D17): needs the orchestrator's decision and a review before it can be a default |
| `inert` | 10 `SW_INERT_LOOP` | M2 | nothing (the scratch loop was removed in wave 6; the bit is dead) | off | - |
| `bodies` | 13 `SW_BODIES` (= `m2a`) | M2 | the body of a beta step / of INST and a telescope's domains and results are evaluated without a tabling record; a body that is a projection of a bound variable is MKPROJ of the argument | **ON by default** (wave-6 integration) | M2 batch 2 (on step 1 + probes, one worker): CPU -10.5 % Cedar slice, -37.6 % reachability, -10.4 % Init, -9.8 % Std; RSS on reachability 1,136 -> 774 MiB; 0 differences on all 230 and the four corpora |
| `m2b` | 14 `SW_M2B` | M2 | direct-mapped APPLY memo with a governor | off, experiment | undecided: Cedar slice -1.8 % instructions, reachability +2.3 % CPU in two readings (M2 batch 2); needs three quiet repeats |
| `directrules` | 15 `SW_DIRECT_RULES` (= `m3a`) | M3 | a recursor rule of the plain form is ONE APPLY of the minor premise; unread induction hypotheses are not built | **ON by default** (wave-6 integration) | M3 batches 3 / 4: reachability 21.9 -> 20.7 G instructions, Init / Std -1 %; `M3K2Test` (400 random applications against the generic reduction) |
| `same` | 17 `SW_SAME` (= `m4a`) | M4 | the official `is_equiv` at the start of CONV: a bounded structural walk of two values (256 nodes, 40 levels; closures, suspensions, projections, same-head applications of a non-regular head; far frames), no judgment, no type | **ON by default** (wave-6 integration) | window `w2`: Cedar slice 0.53 -> 0.46 s wall, 2.75 -> 2.40 s CPU; cedar 2.44 -> 2.09 s wall, 14.78 -> 13.99 s CPU; Init, Std, reachability within 2.5 %; it makes the machine accept `perf/loop-unfolded-once` (332 M ticks and an E_MEM decline -> 266 k ticks). Soundness: see below |
| `match` | 19 `SW_MATCH` (= `m5a`) | M5 | MATCH of a telescope domain against the argument's inferred type instead of building the domain (unknown falls through to the complete procedure) | **ON by default** (wave-6 integration) | as `pchk`; hostile exports `tele-*` |
| `joint` | 20 `SW_JOINT` (= `m5b`) | M5 | joint telescope check of a declaration's type and value | **ON by default** (wave-6 integration) | as `pchk` |
| `lazycases` | 21 `SW_LAZY_CASES` (= `m7a`) | M7 | the casesOn tree as the unfolding step of lazy delta (`Code.rulesCasesOn`) | **ON by default** (wave-6 integration) | M7 batch 4: Init -1.7 %, Std -1.1 % CPU, instructions lower on all four |
| `t2` | 23 `SW_T2` | T2 / T3 (`rlvm-truffle`) | hot K1 blocks are offered to the Truffle tier a `Tier.Factory` installs | off | section "Truffle tier" |
| `lazyeval` | 24 `SW_LAZY_EVAL` | M4 | a one-sided lazy-delta step against a weak-head normal side is one WHNF instead of DELTA + cheap CORE per LSTEP | **ON by default** (wave-6 integration) | M4 batch b3, on top of `code,casetrees`: Init 6.35 -> 5.88 s, Std 12.29 -> 11.47 s CPU; order-only by construction (soundness review 4.3) |
| `levels-complete` | 25 `SW_LEVELS_COMPLETE` | M1 (`Levels`) | universe levels compared by the complete relation instead of the official kernel's algorithm | off **by owner decision** (OWNER-NOTES item 11) | never a default |
| `eager` | 26 `SW_EAGER` | E1 (M4, M5, M6, M3) | a declaration that enters eager reduction (`eagerReduce _ _`) stays on the machine instead of being declined (M6-F26): the eager comparison is deferred to the end of the declaration and run under the official mode; the mode-dependent points of the plain phase are recorded and verified there (a difference declines); an eager-only event inside the K rule's comparison declines; such a declaration does not share a warm session (`docs/design/review/eager-reduction.md`) | **ON by default** (2026-11-25, INT7; section "The default set of 2026-11-25") | E1: declines Init 164 / Std 203 / cedar 423 / Mathlib 6,711 -> 0; differential `default,eager` clean on every corpus; 18 hostile rows declined by design (`eager-hard`); warm wall clock 8 workers JVM: Std -11 % .. -22 %, cedar -7 % .. -10 % |
| `convexpect` | 27 `SW_CONV_EXPECT` | E1 (M5) | B9 (`expect`) also when the lambda's domain is only convertible with the expected one, as before the fix of E1-F4 (off: only when they are one value); accepts the kind T rows `e1-lam-fvar-type-in-conv-*` (`docs/design/review/official-divergence.md`) | off | E1: ticks -2.4 % on the Cedar slice, -0.3 % on Std; no gain in warm wall clock on cedar / Std (native, 8 workers) |
| `skipargs` | 28 `SW_SKIPARGS` | M4 (`Conv.skipArgs`, `Cargs.plan` / `skipMask`, `Conv.k_etas3`; masks in `Facts`) | CARGS and ETAS do not compare the positions the fact record of the head allows: proof positions under the agreement rule (B3), positions the definition does not read under SAMEDEF (B4), the Prop mask of structure eta. **Brings `facts`** (`Op.implied`: it reads the fact records). The answer is derivable but not always the official verdict: KIND T (finding S-F2; OWNER-NOTES 13). Never in `all`; off in strict mode like every switch. Until 2026-11-25 the option `-Drlvm.machine.m4=skipargs` (still read: the same shortcut without the implication) | **ON by default** (2026-11-25, INT7; section "The default set of 2026-11-25") | theory-sound as implemented (S2, `docs/design/review/soundness-shortcuts.md`; optional patch H1 taken: `Cargs.plan` tests the level lists of the two heads itself); no kind F in `default,eager` + both shortcuts on 987 hostile rows (S3, `soundness-default-set.md` section 10); with `congruence`: Mathlib -24.6 % native warm (25.4 -> 19.2 s), cslib -15 %, navier-stokes-euler about -50 % of the checks, con-leche +5.6 % CPU (P9) |
| `congruence` | 29 `SW_CONGRUENCE` | M4 (`Conv.congruence`, `convB1`, `convB12`, `convCore1`; `Same`, `Cong`) | the same-head probe B1 (arguments of two applications of one head compared before either side is unfolded), closure congruence B12 (two closures of one term: slots by CONV) and proofs by type B2, as speculative segments under the probe budget and debt. **Brings `probes`** (`Op.implied`). KIND T (finding S-F1). Never in `all`; off in strict mode. Until 2026-11-25 the option `-Drlvm.machine.m4=congruence` | **ON by default** (2026-11-25, INT7; section "The default set of 2026-11-25") | as `skipargs`; the same-head probe costs on con-leche's hot unit (P9, P8) |

**`eager`: the probes of the plain phase run under a budget (finding INT7-F1, 2026-11-25).** A declaration that may
defer eager comparisons checks, in its plain phase, the two points where the official kernel answers differently
under eager reduction - a Nat folding of an open pair and the test `X = Bool.true` on an open `X` - by running the
eager branch as a probe (`Conv.lazydProbe`, `Conv.convBoolProbe`). The official plain procedure never starts that
evaluation, and it can be of any size: with `eager` as published on 2026-11-24 the machine evaluated one unit of
con-leche (`ConLeche.lpOfData_pack`) for 28.6 M ticks into the hard limit of a session (`E_MEM`, 3.3 GB), twice
(cold retry), before the reference engine decided it in under a millisecond: 14.0 s instead of about 3 s for the
warm pass of that corpus (JVM, 8 workers), and 8.6 to 11 GB resident on the native image. A probe is now a
speculative segment with a budget of 2^22 ticks (`-X machine.eager-probe`; 2^23 until the measurement below); cut at it, the plain procedure goes on as
the official one does, no further probe is started in the declaration, and the declaration is DECLINED if it has
entered or enters eager reduction (`machine.eager.declined.probe`; the reference engine decides it; the cold retry is
answered at once). Probes that complete on the eight corpora (`machine.eager.probeMaxTicks`): 63,654 ticks on Init /
Std / cedar, 193 on con-leche, 102 on reachability, about 400 on navier-stokes-euler, 2,752,617 on cslib and
mathlib (one unit of Mathlib, 4.14 M ticks in all; a budget of 2^21 declines it, 2^20 two more units). What the
one cut probe costs con-leche (JVM, warm, 8 workers, three interleaved rounds): 1.287 s without `eager`; with it
1.403 s at 2^23 (+9.1 %), 1.307 s at 2^22 (+1.6 %), 1.283 s at 2^21 and 2^20 - hence 2^22. Regression tests:
`M6FacadeTest.aProbeThatIsCutDeclinesTheUnitIfItEntersEagerReduction`,
`SHostileEagerTest.aBudgetOfOneTickForTheProbesNeverChangesAVerdict`.

**`congruence`: a cap on what its segments use in one declaration (INT7, 2026-11-25).** The probe debt (NOTES-M4
D25) bounds what FAILING probes cost; a same-head probe (B1) or a closure congruence (B12) that answers T can still
cost more than the unfolding it replaces, when the arguments are large closures that are walked slot by slot (P9
section 4.7). con-leche's `ConLeche.Cached.reduceNatI._mutual.eq_def`, the heaviest unit of that corpus, needs
3.84 M ticks without the switch and 7.21 M with it, and the corpus's warm pass was 4.3 % slower with `congruence` and
8.5 % with both shortcuts. `Frames.k_try` now reports what a segment used (`Machine.tryUsed`), `Conv.Ext.spend` sums
it over the congruence segments of the declaration (a segment inside a segment counts twice), and at
`-X machine.cong-spend` (default 2^18 ticks, 0 = no cap) `Conv.congruence` starts no further segment in that
declaration: the unit needs 4.17 M ticks. What the cap costs where the switch wins, in ticks of the whole corpus with
both shortcuts: navier-stokes-euler 144.4 M -> 149.5 M (548.2 M without the shortcuts), cslib 217.6 M -> 217.6 M
(350.2 M), con-leche 80.1 M -> 76.7 M (84.4 M). Test: `M6FacadeTest.theCongruenceSegmentsOfADeclarationHaveATickCap`.

The experiment bits that are still free or undecided: `m1a`, `m1b` (unused in published code), `m2b`
(above), `m3b`, `m4b`, `m7b` (free). `Op.SW_EXPERIMENTS` names exactly these six; the six that entered the
default set keep their old names as second names (`m2a` = `bodies`, ...).

### `same`: why it is on although the soundness reviewer (package S) had ended before attacking it

`Same.same(a, b)` answers T only when it has SEEN the two values read back to the same expression, up to
the normalisations the value model applies everywhere (beta and zeta of EVAL, canonical literals, PEEK); it
calls no judgment and looks at no type, so it is not one of the congruence shortcuts of finding S-F1 (it
never asks whether two DIFFERENT sub-terms are convertible). That is the condition under which value
identity itself (`a == b` is T, in v0) gives the official verdict. Evidence beyond M4's (shortcheck on the
four corpora: every T re-derived by the complete procedure; `M4SameTest`, `M4OfficialTest`): the hostile
matrix above with `same` alone, on step 1 and in the default set (329 official verdicts each); the gate of
the default set, whose audit lane re-derives every T of `same` under `shortcheck` on all 230 tests and the
four corpora; 17,775 mutants; DiffEngine on the corpora. It has had no independent adversarial review:
`-X machine.switches=default,-same` is the way back, at the cost above on Cedar and of the decline of
`perf/loop-unfolded-once`.

## Sessions (the cap)

A warm session (`warm`) ends: on every outcome other than ACCEPT; when the next declaration is not later
in file order; at the driver's cap (`-X session-mib`, default for the machine 4 MiB); and at **the machine's
own cap**, `-X machine.session-kib=N` (default **1024** = 1 MiB, 0 = none; `MachineEngine.DEFAULT_SESSION_CAP`):
before a declaration the machine forgets everything once `Machine.engineBytes()` exceeds it (statistic
`machine.capResets`). The caps compose: the smaller one resets first.

Why so small: the instruction count FALLS as the cap grows (more memo hits) while CPU time and wall clock
RISE (the tables leave the cache). M6's sweeps for `warm,gated,expect` are in NOTES-M6 (F24); for the
default set the rows "cap 512 KiB / 2 MiB / 4 MiB" of the table above: 512 KiB and 1 MiB are within 2 % of
each other on every corpus (one row each, so no ranking), 2 MiB costs 3-4 % CPU on Init, Std, cedar and
mathlib, 4 MiB 4-7 % and 17 % of wall clock on cedar. The orchestrator's question of 16:52Z (P8: +17 % ticks
on a Mathlib slice at 1 MiB against 4 MiB) is answered by wall clock on ALL of mathlib: 29.3 s per warm
pass at 1 MiB against 30.6 / 30.3 s at 2 / 4 MiB (8 workers): the cap stays.

**Eager reduction** (not a switch: a rule of the facade in EVERY configuration; NOTES-M6 F26). A
declaration that enters eager reduction (an argument `eagerReduce _ _`) is DECLINED by the machine
(`E_LIMIT`, `UNSUPPORTED_INPUT`, statistic `machine.eagerDeclines`; the reference engine decides it), and
nothing it derived is kept. Reason: conversion under eager reduction depends on the mode and on the
official kernel's per-declaration caches, which tables keyed by canonical values cannot reproduce
(findings M6-F26, S-F4, S-F5). 164 of Init's declarations, 203 of Std's, 11 of the Cedar slice's, 8 of
reachability's, 6,711 of mathlib's (0.94 %; 11-12 % of the workers' CPU there, P8). `M6GuardTest` fails if
any class but `Machine` sets `ER` to a constant. Taking these declarations back is the first item of the
list "what is left" in the integration log.

## Host depth bound

`Op.HOST_DEPTH` = 128. Window `w4` (native, 8 workers, default set, warm pass wall / CPU): reachability
0.61, 0.58 s / 1.60, 1.55 s at 128, 0.60 / 1.64 at 512, 0.64 / 1.66 at 2048; Cedar slice 0.46 / 2.50 at 128,
0.50 / 2.53, 0.46 / 2.43; cedar 2.06, 2.11 / 14.01, 14.25 at 128, 2.18 / 14.47, 2.19 / 14.18. No corpus gains
(M3 had found the same on reachability with one worker); the bound stays, and with it the worker stack the
driver gives.

## Truffle tier

`rlvm-truffle` (owner T3 since 16:31Z) is a reactor module and its jar is in every distribution; the tier
runs only under `t2`. It is off: T2 found no gain; T3's quiet windows (NOTES-T3; warm wall clock per pass with
one engine kept over the passes, K1 = `default,code,casetrees` of 16:24Z) give parity on reachability (JVM,
8 workers: 520 against 529 ms), +38 % on the Cedar slice (232 -> 321 ms) and +39 % on cedar (1,315 -> 1,827
ms; 1,820 blocks, 52.6 s of compilation that takes cores from the workers), +2 to +4 % on the native image.
The time of a block is in the interning probes it calls, not in the dispatch the tier removes.

## Build flag measured, not adopted by the default build: the H10 inline lists

`rlvm-machine/h10/native-image-args.sh` (`-H:PriorityForceInline` of every continuation step, NeverInline
of the slow paths), image `work/int6/dist-a-h10` against `work/int6/dist-a` (same jars), window `w4`, two
interleaved rounds, CPU of the warm pass: Init 5.38, 5.37 -> 5.29, 5.32 (-1.3 %); Std 10.23, 10.19 -> 10.22,
10.06 (-0.7 %); Cedar slice 2.50, 2.51 -> 2.39, 2.40 (-4.4 %); reachability 1.60, 1.55 -> 1.60, 1.59 (+1.3 %,
inside its spread); cedar 14.01, 14.25 -> 13.91, 13.97 (-1.3 %); wall clock equal. A gain of 1-4 % by the
keep rule, except that reachability is not resolved. Not wired into `scripts/build.sh`: the lists are
generated with `javap` and `unzip` at build time, and the arena build must first be shown to have both
(`scripts/build.sh ... --ni-arg ARG` for every word the script prints builds such an image today).

## Changing a default

1. the owner sends the evidence (diff reports on all 230 + four corpora at the default cap, the
   measurement rows) in `requests.log`;
2. the integrator re-runs M8's gate (`report.sh gate --switches LIST`) and the mutants on the published
   tree, asks for open soundness findings, and measures the candidate against the current default in ONE
   quiet window (`bench/warm.sh`, cedar and mathlib included);
3. `Op.SW_DEFAULT`, this file and `M6FacadeTest` change together; `-X machine.switches=none` stays the way
   back to v0, and the strict engine is unaffected.
