# rlvm-machine - switches (owner: M6; the ONE list)

Every optimisation of the machine that changes what is computed sits behind a staging switch
(`Op.SW_*`, `Machine.sw`, `m.on(Op.SW_X)`); a strict engine (`EngineConfig.strict`, the driver's re-check
engine) runs with none, whatever is configured. This file is the single list: name, bit, owner, state,
evidence, blockers. A package that changes the state or the meaning of a switch sends M6 one line
(`work/status/requests.log`) and M6 edits this file; experiment switches are described by their owners in
their NOTES file and summarised here.

## How switches are set

| Where | Form |
|---|---|
| launcher | `-X machine.switches=LIST` (or the system property `rlvm.machine.switches=LIST`; `-X` wins) |
| tests | `Fixture.switches("LIST")`, `Debug.parseSwitches("LIST")` |
| in code | `m.on(Op.SW_X)` |

`LIST` is read from left to right, starting from the empty set: `name` or `+name` adds a switch, `-name`
removes it, `none` empties the set, `default` adds `Op.SW_DEFAULT`, `all` adds the staging switches of ISA
section 11 except `k2` (bits 0-10; not `t2`, `lazyeval`, `levels-complete`, no experiment switch).
Without the setting an engine runs `Op.SW_DEFAULT`. Examples: `none` = v0; `default,probes`;
`default,-warm`; `all,-tele`; `default,m2a`.

Related settings (not switches): `-X machine.unit-ticks=N` (the machine's own tick bound per unit, default 2^27, NOTES-M6 F31), `-X machine.session-kib=N` (the machine's own cap on a warm session,
section "Sessions" below), `-X machine.declines=FILE` (one line per non-ACCEPT outcome of the machine),
`-X machine.debug=...` (audits, README), `-X machine.hostDepth=N`, `-Drlvm.machine.sizes=...` (base table
sizes, NOTES-INT I1), `-Drlvm.machine.keep=R,W,S` and `-Drlvm.machine.pool=off` (kept and pooled session
arrays, NOTES-M6 F25).

## The default set

`Op.SW_DEFAULT` = **`warm,gated,expect`** (staging step 1), since 2026-11-24 16:24Z, with the machine's own
session cap of 1 MiB (section "Sessions"). **v0 is `-X machine.switches=none`.**

`MachineProvider.priority()` is still 10: the machine is selected with `-X engine=machine`. The module's
tests are not affected: `pom.xml` gives the test JVM `rlvm.machine.switches=none`, and a `Fixture` engine
has no switch unless the test names some. In force in EVERY configuration, v0 included, is the
eager-reduction rule (section "Sessions": a declaration that enters eager reduction is declined) - a
correctness rule, not an optimisation.

Evidence for the set (all on jars that contain the eager-reduction rule unless said otherwise):

| Gate | Result |
|---|---|
| differential campaign, all 230 tests + Cedar slice + reachability + Init + Std (`work/m6/diff.sh`: DiffEngine through the launcher, both engines on every unit), `warm,gated,expect`, machine cap 1 MiB (`work/m6/diff/s1m1-*.txt`) | all 230: 15,839 of 15,845 units agree (both accept 15,799, both reject 40), 6 not compared (eager-reduction declines and `loop-unfolded-once`); Cedar slice 8,053 of 8,064 (11 eager declines); reachability 4,216 of 4,224 (8); Init 57,774 of 57,938 (164); Std 99,893 of 100,096 (203). **SOUNDNESS 0, COMPLETENESS 0** everywhere |
| the same before the eager-reduction rule, cap 4 MiB, jar 584eab427250 (`work/m6/diff/s1cap4-*.txt`) | 15,844 of 15,845; 8,064; 4,224; 57,938; 100,096: 0 differences |
| launcher gate, tutorial + bugs, jar 7eb33a7a4ad7 (`work/m6/gate-tb-s1.txt`) | CLEAN: 175 verdicts right, machine 1,311 accepted / 30 rejected / 0 declined + 2 for eager reduction, 0 anomalies |
| launcher gate, all 230, PIPELINED loading (`work/m6/gate-all-pipelined-s1.txt`) | 230 verdicts right; machine 15,803 / 41 / 2 declined (`loop-unfolded-once`), 0 anomalies |
| soundness review (package S: 17 switch sets x 329 hostile exports, main tree of 15:20Z) | 0 unexpected verdicts; S-F4 (a wrong ACCEPT under `warm`) and S-F5 (v0) are closed by the eager-reduction rule; open only S-F3 (levels, both engines) |
| the driver's invariance suite with the set (16,928 mutants x 9 schedules, jar f82074b207f1) | 0 wrong, 0 schedule differences, 0 MACHINE-INCOMPLETE, 0 WARM-ANOMALY |
| CPU time against v0 in ONE batch (D1: jar 09bef1d6276f, native `-O3`, arena container, load 57, two rounds, mean) | one worker: Init 7.37 -> 7.11 s (-3.5 %), Cedar slice 2.69 -> 2.70 (+0.4 %), reachability 8.18 -> 7.96 (-2.6 %); eight workers: Init 8.35 -> 8.03 (-3.8 %), Cedar slice 3.46 -> 3.24 (-6.4 %), Std 14.59 -> 14.36 (-1.6 %), reachability 8.78 -> 8.33 (-5.0 %). Wall clock at eight workers was noise on that host (one row: 3.4 and 13.0 s) |

The rule for entering the default set (brief of wave 6): all 230 published tests and the four corpora
(Init, Std, reachability, the Cedar slice) are diff-clean against the reference engine with the switch at
the default session cap (`testkit diff --engine machine`: 0 SOUNDNESS, 0 COMPLETENESS, and no decline that
v0 does not have), the soundness review (package S, `work/soundness/`) has no open finding against it, and
it lowers warm wall clock or CPU time on at least one of the four corpora without raising any by more than
1% (native image `-O3`, arena-shaped container, measurement lock, one batch).

## Named switches

| Name | Bit | Owner | Stage (ISA 11) | What it turns on | State | Evidence / blockers |
|---|---|---|---|---|---|---|
| `gated` | 0 `SW_GATED_TABLING` | M2 | 1 | EVAL tables a node only if its flags say it is shared, a binder body or invariant (off: every non-leaf node is tabled) | **ON by default** (since 16:24Z) | part of the default set. The quadratic cliff M2 found on unshared dependent nests (4,000 levels: 44,030 -> 8,038,031 ticks) is fixed since 14:54Z |
| `pchk` | 1 `SW_PCHK` | M5 | 1 | shared check facts PCHK (implemented 2026-11-24; a fact is not shared once the declaration entered eager reduction) | off; READY per owner (15:31Z) together with `match` and `joint` | M5: tutorial + bugs + all 230 + four corpora diff-clean on `warm,gated,expect,pchk,match,joint`, 9,110 tutorial mutants SOUNDNESS 0 / COMPLETENESS 0; -4 % CPU Init, -3 to -8 % Std, the small corpora inside the scatter (one worker, loaded host). Not yet measured against v0 in one batch at 8 workers by M6 |
| `expect` | 2 `SW_EXPECT` | M5 | 1 | B9: lambdas and applications are checked against the expected type | **ON by default** (since 16:24Z) | part of the default set (M5: gates and mutants in NOTES-M5) |
| `warm` | 3 `SW_WARM` | M6 (facade), M5 (generation rule) | 1 | a machine session survives an ACCEPT (v0: one session per declaration); forward in file order only; reset on every other outcome, at the driver's cap and at the machine's own cap | **ON by default** (since 16:24Z) | part of the default set. Two rules make it safe to leave on: the machine's own cap ("Sessions") and the eager-reduction rule (below) |
| `facts` | 4 `SW_FACTS` | M5, M4, M7 | 2 | fact records (M5); of the consumers only B7 (static PROOF at c4) since 14:30Z - B3, B4 and the ETAS Prop mask were removed from every switch set | off | soundness finding S-F2 (wrong ACCEPT through skipped proof positions) FIXED by M4 14:30Z and verified by S (strict rows of `s/manifest.tsv`). What is left is neutral in instructions (-0.1 % Init / Std, -0.3 % Cedar slice; M5): no reason to turn it on |
| `tele` | 5 `SW_TELE` | M7, M5 | 3 | K1 TELE blocks, MATCH (B5), the IC table | off | integrator (wave 5): no CPU gain measured on the JVM; to be re-measured on the native image by M7 |
| `code` | 6 `SW_CODE` | M7, M3 | 4 | K1 BODY / RULE blocks, LEAF blocks (B11) | off | M7: 0.34x on reachability in its own bench; no corpus diff gate recorded for the launcher path yet |
| `casetrees` | 7 `SW_CASETREES` | M3, M7 | 4 | CASESON / MATCHER case trees (B8) | off | as `code`; `M7HookedTest.all230` (slow group) ends one perf file as CRASH under `tele,code,casetrees` (integration log, open) |
| `probes` | 8 `SW_PROBES` | M4 | 5 | pass B of CARGS, the quick refutation, UNDEC and the probe debt (order only: every answer is an answer of the complete CONV); B1, B2, B12 (congruence shortcuts) were removed 14:30Z | off | soundness finding S-F1 (wrong ACCEPT by same-head congruence where the official procedure is not a congruence) FIXED by M4 and verified by S. No measured gain is left (M4); `loop-unfolded-once` is declined again without the removed shortcuts (`same` is M4's exact replacement) |
| `k2` | 9 `SW_K2` | M3 | later | the derived trees of `Rules` (ISA 3.6); off while `ER != 0`, a node whose derivation entered ER is dropped | off | M3's log |
| `inert` | 10 `SW_INERT_LOOP` | M2 | tuning | the inert-nest loop of EVAL as a scratch loop instead of host recursion; observables identical (NOTES-M2 D12) | off | a per-platform tuning decision; not measured on the native image |
| `t2` | 23 `SW_T2` | T2 (`rlvm-truffle`), hook in `Code` (M7) | experiment | hot K1 blocks are offered to the tier a `Tier.Factory` installs; without `rlvm-truffle` on the class path nothing changes | off | go / no-go by warm wall clock (owner note 12): T2's log |
| `lazyeval` | 24 `SW_LAZY_EVAL` | M4 | - | a one-sided lazy-delta step against a weak-head normal side is one WHNF instead of DELTA + cheap CORE per LSTEP | off; READY per owner (15:26Z) | M4: gates green (NOTES-M4 D26); S: no wrong verdict on 329 hostile exports, alone and on top of step 1, with the argument why it is order-only (`docs/design/review/soundness-machine.md` 4.3) |
| `match` | 19 `SW_MATCH` (= `m5a`) | M5 | - | MATCH of a telescope domain against the argument's type instead of building the domain (ISA 4.5.4, B5 on the generic tier) | off; READY per owner | as `pchk` |
| `joint` | 20 `SW_JOINT` (= `m5b`) | M5 | - | joint telescope check of a declaration's type and value (ISA 4.5.2) | off; READY per owner | as `pchk` |
| `same` | 17 `SW_SAME` (= `m4a`) | M4 | - | the official `is_equiv` test at the start of CONV: a bounded structural walk (256 nodes, 40 levels), no judgment (`Same`, NOTES-M4 D28) | off; gates green, waiting for S's attack | M4 15:24Z |
| `levels-complete` | 25 `SW_LEVELS_COMPLETE` | M1 (`Levels`) | - | universe levels compared by the complete relation (canonical forms) instead of the official kernel's algorithm | off **by owner decision** (OWNER-NOTES item 11: resemble the official kernel by default; the arena tests `imax-right-successor`, `imax-nested-absorption` must be rejected) | never a default |

## Experiment switches

Two bits per package, usable without a request (`Op.SW_M1A` ... `Op.SW_M7B`, bits 11-22; names `m1a`,
`m1b`, `m2a`, `m2b`, `m3a`, `m3b`, `m4a`, `m4b`, `m5a`, `m5b`, `m7a`, `m7b`). Never in `all`, never in the
default set (`M6FacadeTest.switchListsAreReadFromLeftToRight` asserts both). When an experiment stays, its
owner asks M6 for a name; when it is rejected, the bit is free again. What each bit guards today is in
the owner's NOTES file; the table below is M6's copy of what the owners announced in `requests.log`.

| Bit | Owner | Guards (as announced) |
|---|---|---|
| `m1a`, `m1b` | M1 | see NOTES-M1 (wave 6: frame / PRUNE experiments) |
| `m2a`, `m2b` | M2 | see NOTES-M2 |
| `m3a` | M3 | `Reduce.iotaDirect`: a recursor rule of the plain form applied as ONE APPLY of the minor premise (NOTES-M3 D18); name `directrules` if it stays |
| `m3b` | M3 | free |
| `m4a` | M4 | = `same` (above) |
| `m4b` | M4 | free (an experiment with `Same` in the quick test was measured and removed) |
| `m5a`, `m5b` | M5 | = `match`, `joint` (above) |
| `m7a`, `m7b` | M7 | see NOTES-M7 |

## Sessions (the cap)

A warm session (`warm`) ends: on every outcome other than ACCEPT; when the next declaration is not later
in file order; at the driver's cap (`-X session-mib`, default for the machine 4 MiB since driver
50adc1f555bd, 16 MiB before); and at **the machine's own cap**, `-X machine.session-kib=N` (default
**1024** = 1 MiB, 0 = none; `MachineEngine.DEFAULT_SESSION_CAP`): before a declaration the machine forgets
everything once `Machine.engineBytes()` exceeds it (statistic `machine.capResets`). The caps compose: the
smaller one resets first, so the machine is at its best cost under any cap of the driver.

Why a cap of its own, and why so small: the instruction count FALLS as the cap grows (more memo hits) while
the CPU time RISES (the tables leave the cache). CPU seconds, native `-O3`, arena container, two rounds,
`warm,gated,expect` against v0 (`work/m6/measure.tsv`; host load 80-190, so compare inside a row only):

| workers, jar | corpus | v0 | cap 512 KiB | 1 MiB | 2 MiB | 4 MiB | 8 MiB | driver's 16 MiB only |
|---|---|--:|--:|--:|--:|--:|--:|--:|
| 1, 5618c1d2a2c4 (batch A) | Init | 8.30 / 8.16 | | **7.70 / 7.70** | 8.21 / 8.17 | 8.34 / 8.37 | 8.26 / 8.27 | 9.84 / 9.21 |
| | Cedar slice | 3.38 / 3.44 | | **3.24 / 3.22** | 3.28 / 3.35 | 3.59 / 3.45 | 3.63 / 3.72 | 4.21 / 3.97 |
| | reachability | 8.51 / 8.70 | | 8.32 / 8.40 | 8.75 / 8.40 | 8.24 / 8.50 | 8.28 / 8.80 | 8.15 / 8.44 |
| 8, 8be3fd082f56 (batch B) | Init | 8.15 / 8.20 | **7.95 / 7.98** | 8.23 / 8.06 | 8.48 / 8.35 | 8.75 / 8.59 | | 9.55 / 9.63 |
| | Cedar slice | 3.11 / 3.42 | 3.20 / 3.12 | 3.32 / 3.19 | 3.24 / 3.38 | 3.42 / 3.43 | | 4.01 / 3.86 |
| | Std | 14.05 / 13.71 | **13.47 / 13.23** | 13.64 / 13.55 | 14.29 / 13.74 | 15.50 / 15.12 | | 16.60 / 16.90 |
| instructions (G), 8 workers | Init | 132.6 | 127.2 | 120.1 | 113.1 | 105.3 | | 94.9 |
| 1, 09bef1d6276f (batch D1, with the eager rule) | Init | 7.41 / 7.33 | 7.19 / 7.15 | **7.10 / 7.12** | | 7.02 / 7.05 (driver's 4 MiB only) | | |
| | Cedar slice | 2.69 / 2.68 | **2.68 / 2.58** | 2.73 / 2.66 | | 2.78 / 2.73 (driver's 4 MiB only) | | |
| | reachability | 8.34 / 8.01 | 7.89 / 7.85 | 7.91 / 8.01 | | 8.03 / 7.89 (driver's 4 MiB only) | | |
| 8, 09bef1d6276f (batch D1) | Init | 8.19 / 8.50 | 7.84 / 8.29 | **7.77 / 8.28** | | 8.27 / 8.61 (driver's 4 MiB only) | | |
| | Cedar slice | 3.58 / 3.33 | 3.29 / 3.24 | 3.10 / 3.37 | | 3.59 / 3.31 (driver's 4 MiB only) | | |
| | Std | 14.68 / 14.50 | 14.71 / 12.87 | 14.27 / 14.45 | | 14.92 / 15.44 (driver's 4 MiB only) | | |
| | reachability | 8.88 / 8.67 | 8.63 / 8.29 | 8.48 / 8.18 | | 8.50 / 8.33 (driver's 4 MiB only) | | |

The driver measured the same shape on a quieter jar (2 / 4 / 8 / 16 MiB: Init 7.71 / 7.78 / 8.23 / 9.23 s
against 8.13 s without a warm session; `rlvm-driver` log 14:47Z). Warm wall clock (owner note 12) follows
the CPU column at eight workers; per-pass rows: `work/m6/m.py --passes 2` (driver c0aeb3288f46).

**Eager reduction** (not a switch: a rule of the facade in EVERY configuration; NOTES-M6 F26). A
declaration that enters eager reduction (an argument `eagerReduce _ _`) is DECLINED by the machine
(`E_LIMIT`, `UNSUPPORTED_INPUT`, statistic `machine.eagerDeclines`; the reference engine decides it), and
nothing it derived is kept. Reason: conversion under eager reduction depends on the mode and on the
official kernel's per-declaration caches, which tables keyed by canonical values cannot reproduce
(findings M6-F26, S-F4, S-F5; arena test `bad/bugs/rec-of-subst-prop`). 164 of Init's declarations, 203
of Std's; on the Cedar slice 11 declarations that are 18 % of the machine's ticks (M5). `M6GuardTest`
fails if any class but `Machine` sets `ER` to a constant.

## Changing a default

1. the owner sends the evidence (diff reports on all 230 + four corpora at the default cap, the
   measurement rows) to M6 in `requests.log`;
2. M6 re-runs the differential campaign on the published jar (`work/m6/diff.sh`), asks S for open
   findings, measures the candidate default set against the current one in ONE batch (`work/m6/m.py`);
3. M6 changes `Op.SW_DEFAULT`, this file, `M6FacadeTest`, and announces it; `-X machine.switches=none`
   stays the way back to v0, and the strict engine is unaffected.
