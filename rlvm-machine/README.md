# rlvm-machine

The RLVM machine: an instruction set for Lean 4 normalisation by evaluation and definitional equality
(`docs/design/ISA.md`), registered as engine `machine` beside `reference`.

| Read | For |
|---|---|
| `OWNERSHIP.md`, `OWNERS.tsv` | who owns which file; the module belongs to the workflow `rlvm-wave4-machine` |
| `WORK-PACKAGES.md` | the work packages M1..M8, the sandbox workflow, the step template, the staging rule, differential testing |
| `NOTES-M<k>.md` | deviations from `ISA.md`, per package |
| `docs/design/review/stepper-convention.md` | the host control convention (rules H1-H12), normative |

```
. ./env.sh
$MVN -o -q -f rlvm-machine/pom.xml test                    # main tree (only under the publish lock, or read-only)
scripts/machine-sandbox.sh init M3                         # your private copy: work/sandbox/M3/rlvm-machine
scripts/machine-sandbox.sh test M3 -Dtest='M3*'            # compile + test the copy
scripts/machine-sandbox.sh pull M3                         # take the other packages' published files
scripts/machine-sandbox.sh publish M3                      # your files into the main tree (lock, fast tests, install)
```

## Running the machine

The machine is NOT the default engine (`MachineProvider.priority()` is 10; the launcher's default needs
100 = `Engines.DEFAULT_ENGINE_MIN_PRIORITY`): name it. It was the default from 2026-11-24T19:41Z to 20:35Z
(integration of wave 6) and was taken back because of the wrong accepts E1-F1 .. E1-F5 against the official
checker (`SWITCHES.md`, the note at its top). With `-X engine=machine` it runs its default switch set of
`SWITCHES.md`, the reference engine re-checks every rejection and decides what the machine declines;
`-X machine.switches=none` is the generic tier v0.

```
scripts/dev.sh dist                                         # work/dist/bin/rlvm-jvm from the published jars
JAR=.toolchain/m2/rlvm/rlvm-machine/0.1.0-SNAPSHOT/rlvm-machine-0.1.0-SNAPSHOT.jar
RLVM_EXTRA_CP=$JAR work/dist/bin/rlvm-jvm -X engine=machine -X stats-file=/tmp/s.json FILE.ndjson; echo $?
rlvm-machine/src/test/resources/m6/launcher-gate.sh tutorial      # 141 tests, judged by the statistics (below)
rlvm-machine/src/test/resources/m6/launcher-gate.sh all -X machine.debug=memocheck,poison,scopeaudit
```

Settings (`-X KEY=VALUE`, or the system property `rlvm.KEY`):

| Key | Values | Default |
|---|---|---|
| `machine.switches` | staging switches, read left to right: `name` / `+name` adds, `-name` removes, `none` = v0, `default` = `Op.SW_DEFAULT`, `all` = the switches of ISA section 11 but `k2`. **`SWITCHES.md` is the one list** (name, bit, owner, state, evidence). Ignored by a strict engine | `Op.SW_DEFAULT` = `warm,gated,expect` since 2026-11-24 16:24Z (`none` = v0) |
| `machine.session-kib` | the machine's own cap on a warm session, in KiB of `Machine.engineBytes()`; 0 = none. The driver's `-X session-mib` applies as well; the smaller cap resets first | 1024 |
| `machine.unit-ticks` | the machine's own tick bound per unit (0 = none): beyond it the unit is declined (`BUDGET_STEPS`) and the reference engine decides it | 2^27 |
| `machine.declines` | a file that receives one line per non-ACCEPT outcome of the machine: outcome, error code, unit, rank, units accepted in the session before it, name, message | none |
| `levels` | the launcher's `official` / `complete` (shared with the reference engine): `complete` turns the switch `levels-complete` on | official |
| `machine.debug` | debug modes of ISA 5.5: `memocheck`, `shortcheck`, `poison`, `scopeaudit`, `maskaudit`, `sharecheck`, `pubcheck`, `trace`, or `all` (all but `trace`) | none |
| `machine.hostDepth` | host depth bound D of the control convention; 0 = every frame materialised (the worklist machine) | 128 |
| `machine.memocheck.every`, `machine.memocheck.budget` | re-compute one memo hit in N; tick budget of one re-computation | 1; 65536 |

## The facade (ISA section 14; owner M6)

- `MachineSession.check(cid, budget)` never throws for a property of the input. ACCEPT only from
  `Decl.check` returning 0; a kernel error is REJECT (confirmed by the driver with the reference kernel);
  everything else is DECLINE: limits, fatal faults, failed audits, cancellation, a `RuntimeException` of a
  library limit or of a judgment that is not implemented yet. An `Error` is never caught.
- Reasons: `rlvm.api.MachineErrors.reason(code)`, the table of ISA 14.3 (`M6FacadeTest` has one row per code).
- Sessions: v0 = one declaration per machine session (reset before every declaration and after every
  outcome other than ACCEPT); strict (`EngineConfig.strict`) = no switch, reset before and after; warm
  (`-X machine.switches=warm`) = the session survives an ACCEPT, moves forward in file order only, and
  follows the generation rule of ISA 5.3. `footprintBytes()` is bytes in use (never capacity), O(1).
- Budgets: `Budget.maxSteps` is the declaration's tick budget (unlimited in `Budget.warm()`),
  `Budget.maxBytes` the bytes the unit may add; both are seen at a poll. Every 4096 ticks the machine
  polls `RunSignals`: a failed unit below this one is `E_CANCEL` (DECLINE, `Reason.CANCELLED`), the memory
  watermark `E_MEM`; both are fatal, also inside a speculative segment.
- `dumpStats` (with `EngineConfig.stats`, e.g. `-X stats-file=F`), under `values.engine.primary.machine.`:
  `units / accepted / rejected / declined`, `declinedBy.E_x`, `rejectedBy.E_x`, `firstFailures.0..3` (the
  first non-ACCEPT messages), `calls.<OP>` (call events), `hits.<OP>` and `hitRate.<OP>`, `records`,
  `runWords`, `pairs`, `resets`, `bytesAtReset`, `framesWritten`, `framesPerCall`, `spills`, `polls`,
  `probes.Bk.T/F/U`, `code.blocks`, `code.entered`, `code.elaborated.<ROLE>`, `code.stayedGeneric.<ROLE>`,
  `code.icHits`, `code.icMisses`, `factsDerived`, `factsNone`, `audits`, `memoChecks`, `memoInconclusive`,
  `memoByConv`.
- **Eager reduction** (NOTES-M6 F26): a declaration that enters eager reduction (an argument `eagerReduce _ _`)
  is DECLINED in every configuration (`E_LIMIT`, `UNSUPPORTED_INPUT`; `machine.eagerDeclines`): conversion under
  it depends on the mode and on the official kernel's per-declaration caches, which the machine's tables do not
  reproduce. `Machine.enterER()` is the only way to enter the mode (`M6GuardTest`). The reference engine decides
  these units (164 of Init's 57,938 engine checks).
- More statistics (wave 6): `capResets`, `sessionCapBytes`, `eagerDeclines`, `alloc.grows / growBytes / resets /
  resetBytes` (session arrays allocated after the machine was created), and every counter a package's session
  state reports through `Machine.Counters` (`machine.<name>`).
- **The exit status alone proves nothing**: the driver re-checks a unit the machine declines with the
  reference kernel. A gate reads `machine.declined == machine.eagerDeclines` (the machine
  gave up on nothing but eager reduction), `driver.declined == 0`, `driver.warmAnomalies == 0`,
  `driver.machineIncomplete == 0`; `launcher-gate.sh` does (`-Xmx4g` per test, four at a time).
- `Session.ops()` is null (admission uses the reference kernel). `MachineOps` serves `KernelOps` for
  differential tests, with the test tree's `Quote` as its readback.

## Debug modes (ISA 5.5; `Debug.java`)

A failed audit is `fault(E_AUDIT)`: the unit is declined and `lastMessage()` says what was found.

| Mode | What it does | Hook |
|---|---|---|
| `poison` | every reset shifts the ids of the next session and zeroes the dead session's records and runs, so that a ref kept across a reset is kind 0 / an empty run | none (`Machine.reset`); `Debug.stale` for assertions |
| `memocheck` | a memo hit is re-computed with its entry bypassed and compared | the hit branch of each memoised judgment (`Debug.memoCheck`, `memoEnter` / `memoExit`) |
| `scopeaudit` | every FVAR reachable from a value read through a variable is bound by an open binder (I-FV3) | at GET (`Debug.scopeAudit`) |
| `maskaudit` | a node's mask covers the indices a naive traversal finds free | before PRUNE (`Debug.maskAudit`) |
| `sharecheck` | an untabled node is not walked twice under one frame | EVAL's walk, stage 1 (`Debug.shareCheck`) |
| `shortcheck` | a shortcut's answer is re-derived by the complete procedure | shortcuts, stage 2+ (`Debug.shortCheck`) |
| `pubcheck` | published records hold only export / generated term ids, constant ids and base level ids | publication of facts and blocks (`Debug.pubTerm` ...) |
| `trace` | records the call events of the frame trace in `Machine.trace` | `Machine.call` |

## State (2026-11-24, 13:05Z; the integrator's runs; log `work/status/machine-integration.md`, notes `NOTES-INT.md`)

The module is in the reactor (`<module>rlvm-machine</module>`) and in every distribution (`scripts/build.sh`: a tree
build has it as a reactor module, a `--published` build copies the published jar; the CE native image builds with
it). `-X engine=machine` selects it; the reference engine re-checks every rejection and takes every decline.
Default group: 386 tests green; slow group: see the log.

v0 (generic tier, complete procedure, no switch), machine jar 608b84b2b142:

| Corpus | Through | Result |
|---|---|---|
| tutorial, 141 tests | `launcher-gate.sh` (JVM launcher, statistics) | CLEAN: 141 verdicts right; machine 175 accepted / 20 rejected / **0 declined**; 0 MACHINE-INCOMPLETE |
| all 230 published tests | `launcher-gate.sh`; the same with `-X machine.debug=poison,scopeaudit,maskaudit`; the native image (`work/machine-int/native-gate.sh`) | 230 verdicts right, **0 wrong**; machine 15,803 accepted / 41 rejected / 2 declined (both attempts of `good/perf/loop-unfolded-once`, `E_MEM`) |
| all 230 | `testkit diff --engine machine` (DiffEngine: both engines on every unit) | 15,844 of 15,845 units agree (15,803 accept, 41 reject), **0 differences**, 1 unit not compared (the decline) |
| Init / Std / Cedar slice `cedar-hot-ax` / reachability | `testkit diff`, 6 workers | 57,938 / 100,096 / 8,064 / 4,224 units agree; 0 differences, 0 declines |

With `-X machine.switches=warm,gated,expect -X session-mib=4` (staging step 1) and with `...,probes` (step 5) the same
runs are clean as well; with `probes` `loop-unfolded-once` is accepted: 15,845 of 15,845, 0 declines on all 230.
(State of 13:05Z. Since 16:24Z `warm,gated,expect` is the default set, see `SWITCHES.md`; `probes` lost its
congruence shortcuts after the soundness finding S-F1. Since 19:10Z the default SWITCH SET is the one of the wave-6
integration (`SWITCHES.md`, `work/status/machine-integration.md` "Wave 6"); `MachineProvider.priority()` is 10 again
since 20:35Z.)

CPU time, CE native image `-O3`, arena-shaped container, one worker (four workers: same CPU, wall / 3):

| | reference engine | machine v0 | step 1 (`warm,gated,expect`, 4 MiB) | step 1 + `probes` | gruszka |
|---|--:|--:|--:|--:|--:|
| Init | 13.82 s | 7.77 s | 7.09 s | 7.01 s | 2.8 s |
| Std | 24.31 s | 13.32 s | 12.98 s | 12.15 s | 5.7 s |
| Cedar slice | 3.90 s | 3.17 s | 3.08 s | 2.81 s | |
| reachability | 12.19 s | 7.95 s | 7.96 s | 7.78 s | |

Where the time goes (perf on the native image, v0): frames (`Runs`, PRUNE) 17-31%, `Eval` 15-26%, value records
(`Values`) 12-20%, `Infer` 8-11% (not on reachability), conversion 4%, reduction 1-3%. By judgment family (JFR): EV
55-81% of the worker. The staging switches are described above; base sizes: `-Drlvm.machine.sizes=...` (NOTES-INT I1).

## State, wave 6 (2026-11-24, M6; logs `work/status/machine-M6.log`, rows `work/m6/measure.tsv`)

Gates and reports of this wave (M8): `src/test/resources/m8/report.sh gate [--switches S] [--tree SANDBOX]` (the
one-page gate, `work/status/machine-gate-NAME.md`), `report.sh mutants --switches S`, `m8/ladder.py measure`
(`work/status/machine-ladder.md`), `m8/big.py` (`work/status/machine-big.md`); the soundness review's hostile
exports and their official verdicts are `src/test/resources/s/` (`SHostileTest`, `SHostileSlowTest`).

The machine on the large corpora, through the launcher, CE native image `-O3`, arena-shaped container, EIGHT
workers, v0 (`work/m6/big.sh`; first run 13:29Z-13:32Z on jar 608b84b2b142, quiet host):

| Corpus | Verdict | Units (by the machine) | Machine accepted / rejected / declined | Wall s | CPU s | Peak RSS MiB | Instructions G |
|---|---|--:|---|--:|--:|--:|--:|
| mathlib | ACCEPT | 721,845 (714,712) | 714,712 / 0 / 0 | 42.31 | 205.98 | 2,855 | 3,225 |
| cslib | ACCEPT | 401,985 (397,608) | 397,608 / 0 / 0 | 12.80 | 57.67 | 1,210 | 943 |
| navier-stokes-euler | ACCEPT | 126,468 (124,850) | 124,850 / 0 / 0 | 14.88 | 90.11 | 1,062 | 1,480 |
| con-leche | ACCEPT | 36,747 (36,274) | 36,274 / 0 / 0 | 4.51 | 17.81 | 923 | 289 |
| cedar | ACCEPT | 146,058 (144,098) | 144,098 / 0 / 0 | 5.77 | 20.06 | 729 | 315 |

(Reference engine, same kind of image, 8 workers, mathlib: 624.6 s CPU / 120.3 s wall.) That jar predates the
eager-reduction rule. Repeat on jar 09bef1d6276f (v0 + the rule; 16:15Z, host load 60): mathlib ACCEPT, machine
accepted 708,001 / rejected 0 / declined 6,711 declarations, ALL for eager reduction (decline log
`work/m6/declines/big2-v0-mathlib.tsv`; 6,485 of them `_private.*` declarations of Mathlib modules), the
reference engine checked those 6,711 units, 0 anomalies; 251.5 s CPU, 3,251 MiB, 3,291 G instructions; 134 s
wall on the loaded host (each worker ran 24 % of the wall time), not comparable with the quiet-host 42.3 s.
The other rows of the repeat and the warm per-pass rows (`-X passes`): `work/status/machine-M6.log`.
