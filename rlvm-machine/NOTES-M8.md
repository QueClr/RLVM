# NOTES-M8 - quote, tier and convention equivalence, differential tests (owner: M8)

What M8 delivers, where it deviates from `docs/design/ISA.md` / `WORK-PACKAGES.md` and why, what was
measured, and what is not done. The tools are in the test tree (`Quote`, `TierEq`, `Diff`, `M8*`), the
scripts in `src/test/resources/m8/`, the reports in `work/status/`.

## 1. Commands (one per report)

`rlvm-machine/src/test/resources/m8/report.sh --help` lists them. Every command measures the **main tree
as published**: the script copies the module in a moment without a publication (checked against
`work/sandbox/.lock.holder` and `publish.log` before and after the copy; it does not queue behind the
publications), compiles the copy and runs `rlvm.machine.M8Main` in a JVM of its own (`-Xss2m`, 6g heap,
`-Xmn1g`, inside one host-wide slot of `bench/slot.sh`, pinned to the CPUs outside the measurement
container; wave 6, section 7). It never runs Maven on the main tree and never writes into it.
`--tree work/sandbox/Mk/rlvm-machine` measures a sandbox instead (your files before you publish them).

**Wave 6 added the three commands everybody uses now (section 7): `report.sh gate`, `report.sh mutants`,
`ladder.py measure`, and `big.py` for the large corpora.**

| Command | Report in `work/status/` | What (ISA section 11; stepper-convention H12) |
|---|---|---|
| `report.sh tutorial` / `bugs` / `all` / `init` (`gates` = these four) | `machine-diff-NAME.txt` | machine against the reference engine, per declaration, in-process: the v0 gate. First lines: `GATE NAME: DIFF-CLEAN / NOT CLEAN / WRONG VERDICTS`, counts, what stopped the machine how often (stub, error code), then one line per file and the first difference of every file with its reproduction command. Exit 1 = a wrong verdict |
| `report.sh std` / `cedar` / `reachability`, `report.sh diff NAME PATH..` | the same | v1 gate, the Cedar-level slices, any corpus |
| `report.sh launcher tutorial` / `bugs` / `all` | `machine-launcher-NAME.txt` | the gate in its own form: every test through `work/dist/bin/rlvm-jvm --threads 1 -X engine=machine` with the published jar on `RLVM_EXTRA_CP`; the verdict against the arena's classification AND `values.engine.primary.machine.declined == 0`, `units.declined == 0` from the statistics (the exit status alone proves nothing) |
| `report.sh conv NAME` | `machine-conv-NAME.txt` | convention equivalence (H12 item 1): every declaration under host depth bounds `Op.HOST_DEPTH` (128), 64, 0 and unbounded: outcome, reason, ticks, call events by op, the frame trace; after an ACCEPT every record word (ids, flags, memo words), every run word, the pair entries |
| `report.sh frames NAME` | `machine-frames-NAME.txt`, `machine-calls-NAME.tsv` | call events and frames written per declaration under the bound in force; the bound of H12 item 2 (frames below 0.1% of the calls, on Init); the per-declaration call table is compared with the previous one (+2% alert of ISA section 11) |
| `report.sh sample NAME` | `machine-sample-NAME.txt` | judgment level: `infer`, `whnf`, `whnfCore`, `isDefEq`, `isProp`, `ensurePi` of the machine on sampled sub-terms (binders opened on the way), read back with `Quote` and judged by the reference kernel up to its `isDefEq` |
| `report.sh tiers NAME` | `machine-tiers-NAME.txt` | K0 against the tier `-Dm8.k1=facts,tele,code,casetrees`: DELTA, WHNF, VTYPE give the same value and CONV the same answer on generated applications of every constant |
| `report.sh cost --stage LABEL [--runs N] [CORPUS..]` | `machine-cost.md`, `machine-cost.tsv` | the per-stage table CPU / wall / RSS / instructions (in that order): machine and reference engine through the launcher in the arena-shaped container (`bench/measure.sh`), ratio to the reference and to gruszka. A machine row whose engine checks fell back to the reference kernel is marked FALLBACK |
| `convcost.sh micro`, `convcost.sh corpus CORPUS..` | `machine-convcost-micro.txt`, rows `convcost` in `machine-cost.md` | CPU cost of `m.hostDepth` as a field and of the masked TICK line (section 4) |

In a sandbox the same code runs as tests (`scripts/machine-sandbox.sh test Mk ...`):

```
-Dgroups=slow -DexcludedGroups= -Dtest='M8DiffReportTest#tutorial'        (#bugs #all #init #std #cedar #custom)
-Dgroups=slow -DexcludedGroups= -Dtest='M8SlowTest#convTutorial'          (#convBugs #framesInit #sampleInit #tiersValuesTutorial ..)
-Dtest=M8DiffOneTest -Dm8.file=FILE [-Dm8.unit=N] [-Dm8.all=true] [-Dm8.trace=true]   one file in detail: both outcomes,
                                                                          reasons, ticks, call events by op, frames, trace
-Dtest='M8ConventionTest#oneFile' -Dm8.file=FILE                          convention equivalence of one file
-Dtest='M8SamplerTest#oneFile' -Dm8.file=FILE [-Dm8.unit=U] [-Dm8.seed=S] one sampled difference again
```

Options everywhere (system properties): `-Dm8.hostDepth=0`, `-Dm8.switches=warm,gated`, `-Dm8.debug=..`,
`-Dm8.maxSteps=N`, `-Dm8.checkSeconds=N`, `-Dm8.fileSeconds=N`, `-Dm8.suffix=-d0` (report name),
`-Dm8.seed=N`, `-Dm8.perConstant=N`, `-Dm8.every=N`, `-Dm8.k1=..`.

Guard of every publication (fast group): `M8GateTest` = no SOUNDNESS / COMPLETENESS difference on a fixed
sample of tutorial and `bugs/` files (declines pass). It fails the publication of whoever introduces a
wrong verdict and prints the reproduction command.

## 2. What the pieces are

- `M8Harness`: one loaded export, the reference engine, and any number of machine configurations
  ("lanes": host depth, switches, debug modes) run over its units in lockstep. Every lane has its own
  worker context over the same store; an observing `Session` sits between admission and
  `MachineSession.check` and records outcome, ticks, call events by op, frames, the trace, and after an
  ACCEPT digests of records, runs and pairs; every check has a deadline that the machine sees through
  its 4096-tick poll.
- `Diff`: the corpus runner and the report.
- `TierEq`: `conventionEquivalence(path)`, `compare(machineA, machineB)` (word-for-word comparison of two
  machines, with the first difference printed), `traceDifference`, the reports `conv` and `frames`,
  `tierEquivalence(path, seed)` (declaration outcomes under two switch sets).
- `Quote`: readback of a value into the overlay. Closures and suspensions by substitution of the frame
  and of `ls` (no judgment runs); a PII through `DOM` / `OPEN` / `INST`; in-scope FVARs as the loose
  variables of their binders, or as a term given with `Quote.of(m).local(fvar, term)`; a HOLE slot that
  the term turns out to read is reported (mask contract, ISA 2.4).
- `M8Sampler`: glue between `rlvm.testkit.KernelSampler`, M6's `MachineOps` (with `Quote::quote` as its
  quoter) and a `RefKernel` on the same worker context.
- `M8Tiers`: tier equivalence on values, by a structural fingerprint that includes closure identity.
- `M8ConvCost`: the micro-benchmark of section 4.

## 3. Deviations

| # | Where | Deviation | Reason |
|---|---|---|---|
| D1 | `Diff.run`, the reports | the corpus runs go through `M8Harness` (own loader wiring, one worker context per lane, an observing `Session`), not through `Fixture.diffAgainstReference()` as WORK-PACKAGES M8 says | a report over hundreds of files needs per-declaration observation, a deadline per check, a hard limit per file and several machines over one store; the fixture is a frozen shared file. The counting rule is the fixture's (`M8HarnessTest.theHarnessCountsAsTheFixtureDoes`), except D8. `Diff.file(path)` still is the fixture's comparison |
| D2 | `Diff.directory` | returns the rendered report; `Diff.run` / `Diff.report` return the structured one | the stub's `String` signature is kept |
| D3 | convention equivalence | after an outcome other than ACCEPT only outcome, reason, ticks, call events and the trace are compared, not records and memo words | `MachineSession.check` resets the machine before it returns such an outcome (ISA 14.1); the comparison goes through the real facade on purpose |
| D4 | convention equivalence | the pair table is compared as a set of `(lo, hi, status)`; another insertion order is counted as a note | ISA 1.3 names "memo and pair-table contents", not the order of the insertion log |
| D5 | lane without a depth bound | a `StackOverflowError` in the unbounded lane makes that declaration "not compared" (counted); under a bound it is not caught | plain host recursion is a test configuration (H9); under a bound an overflow is a defect and must stay loud (ISA 12 R11) |
| D6 | the reports' JVM | `-Xss2m` like surefire's; the conv run uses one 64 MiB thread for its three lanes | the machine must not need a deep host stack (H9); only the unbounded lane does |
| D7 | deadlines | a machine check that runs longer than `m8.checkSeconds` (default 120) is cancelled through `RunSignals.firstFailure()` at the next 4096-tick poll and counts as a decline (CANCELLED); a file beyond `m8.fileSeconds` (default 1800) is reported as TIMEOUT | a report must survive a looping judgment. A loop that never ticks is only caught by the file limit (the line says so). On a loaded host a slow, correct check can be cancelled: read the reason column before reading a decline as a defect |
| D8 | a unit the reference engine DECLINES | not SOUNDNESS / COMPLETENESS whatever the machine says; counted as "reference declined"; the run goes on behind it; the file is settled by the official verdict (the arena's good / bad classification, confirmed by the official checker for all 230 tests): `CLEAN*` if the machine's verdict of the file is the official one, `WRONG` if it is the opposite. The reference engine gets the driver's budget (min(6 GiB, 60% of the heap)) | M5's request of 08:57Z: `perf/app-lam` and `perf/magma-list-deep-n36` exhaust a 4 GiB budget of the reference engine; without this the `all` gate could never be clean and a real soundness line would hide among such lines. The shared fixture follows since 09:31Z (`DiffReport.referenceDeclined`, M6); one difference remains: the fixture's `clean()` is false for a file with such a unit, the harness settles it by the official verdict |
| D9 | `Quote` | closures and suspensions are read back by substitution (`TermOps.instantiate` of the frame's readbacks, then `instantiateLevelParams` by `ls`), not by evaluating the body under a fresh variable; the memo is keyed by (value, number of open binders) for OPEN values and dropped when the machine was reset, the overlay shrank, the open binders changed or a local changed | the term is what the value *means* (ISA 2.2), independent of how well the machine evaluates: the right thing to hand to the reference kernel, and no judgment runs (a readback must not perturb what it observes more than necessary). Only a PII has no term of its own and is read back through `INST` |
| D10 | `Quote` | a NatStore literal is read back as `b.termOf(Nat.transfer(m.nats, handle, b.store()))` | M6's request of 08:48Z (the machine's number overlay) |
| D11 | sampler | the comparison is `rlvm.testkit.KernelSampler` (not a second walker in this module); it needs `MachineOps` (M6). A judgment that ends in a stub is counted by stub, not as a difference; the report then says NO DATA, not AGREE. `Tally.more` (isProp / whnfCore / ensurePi) is read reflectively: the published test-kit jar is older than its source | one sampler for the launcher campaign (`testkit diff --sample`) and for this module |
| D12 | tier equivalence | "the same value id" across K0 and K1 is tested as "the same structural fingerprint" (kind, components, and for closures the term id, `ls` and the fingerprints of the frame slots incl. HOLE and the REST run) in two sessions of one machine; results equal up to PEEK are counted, not failed | a memo written by one tier must not answer the other, so the tiers cannot share a session; with canonical ids a common fingerprint is a common id (I-ID). The up-to-PEEK relaxation follows M2's note of 08:56Z |
| D14 | report names | the plain names (`machine-diff-tutorial.txt`, ..) are written only by runs on the main tree; a run on a sandbox (`report.sh --tree ..`, or a slow M8 test inside a sandbox) gets the suffix `-Mk` unless `-Dm8.suffix` is given and says "MEASURED: THE SANDBOX .., NOT THE MAIN TREE" in its header | other packages ran the reports on their sandboxes (as intended) and overwrote the gate reports the orchestrator reads |
| D15 | convention lanes | `TierEq.BOUNDS` = `Op.HOST_DEPTH` (128 since 10:29Z, M6's decision on the frame counts of section 5), 64, 0, unbounded; the first is the lane the others are compared with | H12 item 1 and the acceptance of M8 name D = 64 and D = 0; the bound in force moved |
| D13 | `M8GateTest` | a fast test of M8 asserts something about all packages together: no wrong verdict on 25 files | WORK-PACKAGES section 6: soundness + completeness must be 0 at every commit. It never asserts that a stub throws |

## 4. Measured

Host: aarch64, 20 cores, shared with the other 15 agents of the wave (load average 100-150 while these
were taken); figures are minima over rounds where stated.

**Cost of the skeleton's two deviations from the measured convention** (NOTES-M6 S3, S4), requested by
the skeleton. `convcost.sh micro` (`work/status/machine-convcost-micro.txt`): toy judgments that differ
only in the depth test and the TICK line, CPU time per judgment, minimum of 25 interleaved rounds.

| JIT | workload | CP: constant bound, `--m.fuel < 0` (the convention) | FP: field bound | CM: masked TICK | FM: as built | as built / convention |
|---|---|--:|--:|--:|--:|--:|
| C2 (the launcher today) | a judgment that only makes two sub-calls | 1.42 ns | 1.45 ns | 1.74 ns | 1.76 ns | 1.24 |
| C2 | the same with one intern probe per judgment | 5.37 ns | 5.36 ns | 5.69 ns | 5.70 ns | 1.06 |
| Graal JIT | two sub-calls | 1.74 ns | 1.81 ns | 1.73 ns | 1.74 ns | 1.00 |
| Graal JIT | with one intern probe | 6.09 ns | 6.10 ns | 6.21 ns | 6.23 ns | 1.02 |

Reading: `m.hostDepth` as a field is free (at most 0.03 ns, inside the noise). The masked TICK line
costs about 0.3 ns per ticking judgment under C2 and about 0.1 ns under the Graal JIT: a quarter of a
judgment that does nothing, 6% of one that interns one record.

**The same on the real machine** (`convcost.sh corpus --runs 6 init`, rows `convcost` of
`work/status/machine-cost.md`): three variant jars built by `sed` from the main tree of 10:04Z (35 depth
tests, 41 TICK lines replaced), Init through the launcher (C2, one worker, arena-shaped container), minimum
CPU time of six runs each; all four accept all 57,938 declarations:

| variant | CPU s (6 runs) | CPU s (first series, 2 runs) | instructions G |
|---|--:|--:|--:|
| as built (field bound, masked TICK) | 15.01 | 15.36 | 151.1 |
| constant bound | 15.51 | 16.31 | 157.3 |
| plain TICK (`--m.fuel < 0`, no poll) | 15.19 | 15.92 | 154.8 |
| constant bound and plain TICK (the convention) | 14.88 | 14.63 | 153.5 |

No consistent order: the variant with only the constant bound is slower than the machine as built, the one
with both changes is 0.9% faster. The resolution of this host (load average 130-180 during the runs) is
about 3%, and the differences are inside it. The micro-benchmark bounds the effect from above: 0.33 ns x
94 M ticks = 31 ms of 15 s = 0.2%. **Conclusion: neither deviation has a measurable CPU cost on Init.**
If it ever matters, the plain line can keep the poll: let `fuel` count down a chunk of at most 4096 ticks
and let `tickSlow()` refill it from a reserve after polling (`ticks() = tickBase - fuel - reserve`); the
TICK line is then the convention's `--m.fuel < 0`. Not requested: the measured cost does not justify
touching every judgment.

## 5. State of the gates on 2026-11-24 (regenerate with the commands of section 1)

Last regeneration: main tree as published 10:46-10:55Z, `Op.HOST_DEPTH = 128` (every report names the
publication it measured). Earlier states are in `work/status/machine-M8.log`.

| Gate / acceptance item | Result | Report |
|---|---|---|
| tutorial (141 files), in-process | **DIFF-CLEAN**: 195 engine checks = 175 accepted + 20 rejected, 0 declined, 0 wrong | `machine-diff-tutorial.txt` |
| tutorial, through the launcher (09:06Z) | **PASSED** 141 / 141: verdicts right, `machine.declined == 0`, `units.declined == 0` | `machine-launcher-tutorial.txt` |
| `bugs/` (34 files), in-process / launcher (09:04Z) | **DIFF-CLEAN** (1136 accepted + 11 rejected) / **PASSED** 34 / 34 | `machine-diff-bugs.txt`, `machine-launcher-bugs.txt` |
| all 230, in-process | **no wrong verdict; 229 files clean**, 1 with one decline: `good/perf/loop-unfolded-once` unit 1365 `loop_unfolded_once` (`E_MEM`: 2^28 run words per session; M2's diagnosis in requests.log 09:24Z). 15,803 accepted + 41 rejected + 1 declined | `machine-diff-all.txt` |
| all 230, through the launcher | **229 / 230 clean, 0 wrong**; the same test falls back to the reference kernel | `machine-launcher-all.txt` |
| Init, in-process / launcher (10:04Z) | **DIFF-CLEAN**: 58,547 units, 57,938 engine checks, all accepted / **PASSED** | `machine-diff-init.txt`, `machine-launcher-init.txt` |
| convention equivalence per declaration, lanes D = 128 (in force), 64, 0, unbounded (11:0xZ; before 10:29Z the same with 64 in force) | **EQUIVALENT** on tutorial (195 declarations, 175 accepted), `bugs/` (1147, 1136) and **Init (57,938, all accepted; 77.8 M ticks, 63.5 M call events)**: outcome, reason, ticks, call events by op and their sequence, every record word incl. memo words, every run word, the pair entries; no pair-order note; the unbounded lane wrote no frame and never overflowed (64 MiB thread); frames on Init: D128 32,274, D64 180,960, D0 145.5 M | `machine-conv-{tutorial,bugs,init}.txt` |
| frames written under the host depth bound on Init, bound 0.1% of the calls | D = 64 (the bound until 10:29Z): **not met, 0.234%** (185,128 frames for 79.2 M call events; 1992 spills; 603 declarations with a frame, one of them with 49% of the frames). D = 128: 0.041%, D = 256: 0.017%; ticks and call events identical under all bounds. On this data M6 set `Op.HOST_DEPTH = 128`; **with it the bound is met: 32,274 frames for 63.5 M call events = 0.051%** | `machine-frames-init.txt` (128), `-init-d128.txt`, `-init-d256.txt` (the D = 64 figures are in the log), `machine-calls-init.tsv` |
| readback of `whnf` and `infer` against the reference kernel on sampled sub-terms (10:02Z) | **AGREE** on Init: 1486 constants, 5944 sub-terms (1253 proofs): 5944 infer, 4691 whnf, 7240 isDefEq, 11,442 isProp / whnfCore / ensurePi answers, 0 differences, 0 undecided; tutorial (175 constants) and `bugs/` (1136 constants, 2 undecided) AGREE too | `machine-sample-{init,tutorial,bugs}.txt` |
| K0 = K1 on values | **EQUIVALENT** on Init against `facts,tele,code,casetrees` as published: 1486 constants, 5944 results (DELTA, WHNF, VTYPE fingerprints incl. closure identity; CONV), none equal up to PEEK only, none skipped; tutorial (175 constants) likewise. (M7's own `M7TierTest.initPrelude` reported differences at 10:42Z after M2's `let` change; this test did not see one on its sample: it applies constants to variables and one-level constructors, M7's test drives block bodies directly) | `machine-tiers-{init,tutorial}.txt` |
| Std (v1 gate corpus), in-process, first run (10:36Z) | **no wrong verdict, not clean**: 100,096 engine checks = 100,088 accepted + 8 declined, all `E_MEM: run words: 2^28 per session` after 50-54 M records each; they make the run slow (1.34 G ticks; 943 s in-process against 137 s for the reference engine on the loaded host) | `machine-diff-std.txt` |
| Cedar slice `cedar-hot-ax`, `reachability`, in-process (10:37Z, 10:39Z) | both **DIFF-CLEAN**: 8064 and 4224 engine checks, all accepted | `machine-diff-cedar.txt`, `machine-diff-reachability.txt` |

**Cost, stage v0** (`machine-cost.md`: launcher `rlvm-jvm --threads 1` = HotSpot C2, machine jar on
`RLVM_EXTRA_CP`, arena-shaped container; best CPU time over the runs of a row, 2-3 runs per measurement):

| corpus | machine CPU / wall / RSS / instructions | reference engine | machine / reference (CPU) | machine / gruszka (CPU) |
|---|---|---|--:|--:|
| Init | 14.96 s / 10.9 s / 516 MiB / 155-160 G | 15.92 s / 13.8 s / 1253 MiB / 207.9 G | 0.94x | 5.3x (2.8 s; gate after step 1: 2.5x) |
| `cedar-hot-ax` | 8.62 s / 4.7 s / 689 MiB / 87.8 G | 6.38 s / 4.6 s / 1024 MiB / 71.0 G | 1.35x (slower) | - |
| `reachability` | 9.41 s / 7.5 s / 2071 MiB / 76.7 G | 11.99 s / 11.3 s / 2669 MiB / 101.1 G | 0.78x | - |

The host was shared with the other agents of the wave: CPU time moved by up to 50% with their load (the
reference engine on Init: 23.6 s at load average 150, 15.9 s at 70, the same instruction count). A first
statement in the log ("0.63x the reference on Init") compared runs under different load and is withdrawn
there. The table keeps every measurement and shows the best per row.

Found by these tools so far: one bug, in M8's own `Quote` (D9: the level substitution reached into the
bindings; ten "differences" of the sampler on `bugs/`, fixed 09:34Z). No difference in a judgment of the
machine, no convention difference, no wrong verdict of the published main tree. (The three "WRONG" files
in sandbox reports of 08:55-08:58Z were reference-engine declines counted as SOUNDNESS: D8.)

## 6. Not done, not verified

- **K0 = K1 against a real K1**: `M8Tiers` and `TierEq.tierEquivalence` run and are tested K0 against
  K0 and against whatever the switches `facts,tele,code,casetrees` do today; they were not validated
  against an elaborator bug planted on purpose. "Random argument values" are fresh variables and
  one-level constructor applications of index-free inductive types, not arbitrary closed terms.
- **`Quote` of a PII** is tested through `INFER` of two lambdas only; a PII whose body type needs a
  failing judgment raises `QuoteFailure` (untested path beyond the cleanup of binders).
- **Loops that never tick** inside a judgment are only caught by the per-file limit; the hung thread is
  abandoned (daemon) and dies with the JVM.
- **Native image**: nothing of M8 was run on a native image; the micro-benchmark is JVM only (C2 and
  Graal JIT).
- The cost table measures the JVM launcher with the machine jar on `RLVM_EXTRA_CP`, one worker thread;
  the H10 inlining flags are not set (M6 has not wired them).
- `report.sh frames` keeps one previous call table (`.prev`), not a history.

## 7. Wave 6: the gate, the mutants, the scoreboard (2026-11-24, 13:25Z onwards)

M8 is the gatekeeper and the scorekeeper of the wave. Everything below is test-tree code and scripts; no
file of another package was touched. The numbers of a run are in section 8; this section is what exists
and why it is built the way it is.

### 7.1 Commands

| Command | Report | What |
|---|---|---|
| `report.sh gate [--switches S] [--tree SANDBOX] [CORPUS..]` | `work/status/machine-gate-NAME.md` (one page) + `.problems.txt` | THE GATE for one switch set (none = the generic tier): (1) every declaration against the reference engine on tutorial, bugs, all 230, Init, Std, reachability, the Cedar slice; (2) tier equivalence: the generic tier gives the same outcome for every declaration (another *reason* for the same outcome is a note), and for a set with K1 switches the values of `M8Tiers` on the tutorial tests and Init; (3) convention equivalence: host depth 0 and unbounded against `Op.HOST_DEPTH`: outcome, reason, ticks, call events by op, the frame trace, after an ACCEPT every record word, run word and pair; (4) the debug audits as two lanes, `poison,scopeaudit,maskaudit` and `memocheck,shortcheck`: the same outcomes and no `E_AUDIT`; (5) the real driver in the same JVM with `-X engine=machine` on the 230 tests (1 worker) and the corpora (4 workers): verdict, machine declines, MACHINE-INCOMPLETE, WARM-ANOMALY, disagreements. NAME = `v0` or the switches joined by `+`; a sandbox gets `-Mk`; fewer corpora or parts get `-partial` and the page says "A PARTIAL RUN, NOT THE GATE". Exit 1 = a wrong verdict, a difference, a crash or a timeout; declines alone are `PASS WITH DECLINES` |
| `report.sh mutants --switches S1 --switches S2 .. [--corpus NAME] [--limit N] [--launcher-sample N]` | `work/status/machine-mutants.md` | the four mutant corpora (17,775 exports classified by the official checker) per switch set: the final verdict of the real pipeline against the official checker (SOUNDNESS, COMPLETENESS), against the same pipeline with the reference engine, and the machine's own answers: from the driver's statistics (MACHINE-INCOMPLETE, WARM-ANOMALY, disagreements, declines) and, per declaration, in-process against the reference engine (the driver trusts an ACCEPT of the machine: only this sees a wrong one). `--launcher-sample N` runs every N-th mutant through the real launcher (a JVM per mutant, `scripts/check-light.sh`) and holds the in-process verdict against it |
| `ladder.py measure [--sets 'v0;warm,gated,expect;..']`, `ladder.py render`, `ladder.py status` | `work/status/machine-ladder.md`, `.tsv` | THE SCOREBOARD: the machine jar published now (named by the hash of its code, so that a test-only publication is the same jar), CE native image `-O3` of the published jars, arena-shaped container, ONE hold of the measurement lock per batch: reference engine, gruszka (its arena run line) and every switch set on Init, Std, the Cedar slice, reachability with 1 and 4 workers: CPU s / wall s / RSS MiB / instructions G, ratios to the reference and to gruszka of the same batch. Each batch is announced in `work/status/machine-M8.log` |
| `big.py [--sets ..] [--corpora ..] [--with-reference]` | `work/status/machine-big.md`, `.tsv` | the large corpora (con-leche, cedar, cslib, navier-stokes-euler, mathlib) through the native launcher with `-X engine=machine`, 4 workers: `agree` = machine accepted == engine checks, nothing rejected or declined, no MACHINE-INCOMPLETE; on a corpus every unit of which the reference engine accepts that is the per-declaration differential (the argument is in the header of the script and of the report) |

`report.sh` options since wave 6: `--jobs N` (passes / files / mutants at a time, default 4), `--heap` (default
6g), `--levels official|complete` (the level relation of the reference engine, default official: owner note
11), `--no-pin`, `--no-slot`. `report.sh tutorial|bugs|all|conv ..` run on the same parallel runner.

### 7.2 How the gate stays inside the heap (the two slow tests that ran out of memory)

`M8SlowTest.convAll` (four machine lanes and the reference kernel in lockstep, 6 GB) died with
`OutOfMemoryError` on perf/app-lam, gf3-matrix-product, magma-list-deep-n36, magma-list-pair-n21;
`M7HookedTest.all230` ended perf/loop-unfolded-once as CRASH in its K1 lane (`OutOfMemoryError` in
`Machine.growRecords` under `Diff.run`). Causes: (a) lockstep keeps the reference kernel's overlay (1.2 GB
used, up to three times that allocated, after app-lam) and every lane's machine alive at the same time;
(b) the frame trace was kept as an `int[]` per lane and copied per declaration (4 bytes per call event:
hundreds of MB for one perf declaration); (c) `Machine.heapAllows` counts the free bytes of the young
generation, which a large array cannot use (SerialGC: a third of the heap by default), so a growth it
allowed could still fail.

What was built (`M8Lanes`, the pass runner; `M8Harness` keeps lockstep for the single-file tools):
- **passes**: the reference engine runs over the whole file first and its session and context are emptied;
  then every lane runs alone over the same units and lets go of its machine, session and context when it
  ends; per unit only words are kept (outcome and reason of admission and engine, ticks, call events and a
  digest by op, the length and a digest of the trace, the sizes and digests of records, runs, pairs). The
  comparisons (lane against reference = `Diff.FileResult` with the fixture's counting rule; lane against
  lane = `CONV` everything observable, or `OUTCOME`) are made from those words. Peak memory is one engine
  on one declaration. A corpus that is one big file runs its lanes on one thread each (they share the
  frozen store and the finished environment pass; `EnvBuilder.advanceTo` returns at once for decided units).
- **a check the Java heap ends is not a verdict**: `OutOfMemoryError` inside `Session.check` (and a growth
  that `Machine.heapAllows` refused) is a DECLINE flagged `memory`; the lane gets a new machine and context
  after the unit; the comparisons skip the unit; the file is flagged and `M8Gate.runAll` runs it again on
  its own after everything else. A file that still runs out of heap alone is reported as declined for
  memory, never as a difference. Tested with a seam (`Config.simulateHeapExhaustionAtCheck`).
- **scheduling**: `jobs` passes at a time (a semaphore); big files (>= 16 MB) one per 5 GB of heap with
  their lanes in parallel; heavy files (`work/m8/heavy.txt` remembers every file whose lane or reference
  session held more than 256 MiB or that ran out of heap beside others; without that memory the perf tests)
  a few at a time, in a heap below 10 GB one at a time after everything else.
- `report.sh` runs the JVM with `-Xmn1g` so that `heapAllows` is right about large arrays.
- **warm sessions** (`Config.incrementalDigest`): digesting a 4 MiB session after each of Init's 58,000
  declarations would cost more than the checks, so after an ACCEPT only what the declaration added is
  digested, and the whole session once at every reset and at the end of the file.
- a check that its wall-clock deadline ended (`Config.checkMillis`, 300 s in the gate; the host is shared)
  is a decline and is not compared between lanes (it would be a difference made by the load).

### 7.3 Deviations (continuing the table of section 3)

| # | Where | Deviation | Reason |
|---|---|---|---|
| D16 | the gate, the corpus reports | lanes are compared from digests recorded per unit, not on live machines; the first difference is named by kind ("records made: 12 against 13"), the words themselves are shown by `M8ConventionTest#oneFile` (lockstep) | memory (7.2) |
| D17 | tier equivalence at declaration level, audit lanes | the same outcome for another reason (another error of a wrong declaration met first under `expect`) is counted as a note, not as a difference; under a host convention (CONV) the reason must be identical | found on bad/bugs/rec-of-subst-prop unit 128: generic TYPE_EXPECTED, `warm,gated,expect` APP_TYPE_MISMATCH, the reference rejects too |
| D18 | the gate's part 5, the mutants | the real pipeline is called in the report's JVM (`rlvm.main.RlvmChecker.checkExport` by reflection; `report.sh` puts the published rlvm-main and rlvm-cli jars behind the tree's classes), not started as a launcher process | 17,775 mutants x (sets + 1) runs: a JVM each is about 20 minutes per set, a call is milliseconds. Checked against the launcher by `--launcher-sample` and by the baseline table (the in-process reference pipeline must reproduce the published `testkit judge` counts) |
| D19 | every report | the reference engine compares universe levels as the official kernel does (`-Drlvm.levels=official`; `--levels complete` for the old relation) | owner note 11; the machine does since 14:22Z (M1), the published rlvm-ref jar still defaults to `complete`, and without this every level corner case would show as "the machine rejects, the reference accepts" |
| D20 | the scoreboard | a machine jar is named by a hash of its classes (names and CRCs), not of the file | a publication of tests only installs a jar with other time stamps and the same code |
| D21 | large corpora | "differential" = the machine's own statistics on a corpus the reference engine accepts unit for unit, not both engines on every unit | it is the same statement at a quarter of the CPU time on a host that was out of memory; `testkit diff --engine machine` stays the tool for a corpus with rejections |
| D22 | resources | every report holds a host-wide slot, 4 passes, 6 GB, pinned outside the measurement cpuset | the orchestrator's rule of 14:32Z after the host thrashed; my first full gate (12 passes, 24 GB) was one of the jobs that pushed it into the swap and was killed after 25 minutes without a result |

## 8. Wave 6: what was run and what it showed

Host during all of this: shared with up to thirty agents, load average 75 to 270, out of memory and
swapping between 13:55Z and 14:35Z (the orchestrator stopped a wave of profilers and introduced the
host-wide slots). All M8 jobs after 14:35Z ran inside a slot with 4 passes / mutants at a time and 6 GB.
Wall-clock figures below say how long a tool takes under that load, nothing about the machine.

### 8.1 Mutants with the machine (priority 2)

Campaign 1, main tree as copied at 14:42Z (after M1's official levels 14:22Z and M4's fix of the soundness
findings S-F1 / S-F2 14:30Z), reference engine with `levels=official`, `report.sh mutants --switches v0
--switches warm,gated,expect --switches warm,gated,expect,probes --launcher-sample 100`; 1158 s wall
(`work/m8/machine-mutants-run1-1442Z.md`):

| switch set | mutants | SOUNDNESS | COMPLETENESS | agree | declined | final verdict not the reference pipeline's | machine accepted / rejected / declined (engine checks) | MACHINE-INCOMPLETE / WARM-ANOMALY / disagreements | declarations compared in-process | machine accepts, reference rejects | machine rejects, reference accepts | machine declines | launcher sample differs / run |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| `v0` | 17,775 | 0 | 0 | 16,092 | 1,683 | 0 | 863,982 / 1,875 / 0 | 0 / 0 / 0 | 1,008,568 | 0 | 0 | 0 | 0 / 179 |
| `warm,gated,expect` (4 MiB) | 17,775 | 0 | 0 | 16,092 | 1,683 | 0 | 863,982 / 1,875 / 0 | 0 / 0 / 0 | 1,008,568 | 0 | 0 | 0 | 0 / 179 |
| `warm,gated,expect,probes` (4 MiB) | 17,775 | 0 | 0 | 16,092 | 1,683 | 0 | 863,982 / 1,875 / 0 | 0 / 0 / 0 | 1,008,568 | 0 | 0 | 0 | 0 / 179 |
| baseline: the pipeline with `-X engine=reference`, in-process | 17,775 | 0 | 0 | 16,092 | 1,683 | - | - | - | - | - | - | - | - |

Per corpus (every set and the baseline alike): tutorial 12,260 = 11,847 agree + 413 declined; bugs 2,692 =
2,627 + 65; prelude 847 = 839 + 8; mdata 1,976 = 779 + 1,197: exactly the published `testkit judge` table of
the reference engine through the launcher (`work/status/mutants.md`, 12:42Z), which is the check of the
in-process method (D18). The declines are the loader's and admission's, not the machine's (the machine
declined no engine check). **No case in which the machine's own answer differs from the reference
engine's**, per declaration or in the driver.

Campaign 2, main tree as copied after M4's publication of 15:24:08Z (with M6's rule of 15:07Z that the
machine declines a declaration that enters eager reduction), four sets, 859 s wall
(`work/status/machine-mutants-k1.md`):

| switch set | SOUNDNESS | COMPLETENESS | agree | declined | final verdict not the reference pipeline's | machine accepted / rejected / declined (engine checks) | MACHINE-INCOMPLETE / WARM-ANOMALY / disagreements | machine accepts, reference rejects | machine rejects, reference accepts | machine declines (declarations, in-process) |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| `warm,gated,expect` | 0 | 0 | 16,092 | 1,683 | 0 | 863,982 / 1,846 / 58 | 0 / 0 / 0 | 0 | 0 | 29 |
| `code,casetrees` | 0 | 0 | 16,092 | 1,683 | 0 | 863,982 / 1,846 / 58 | 0 / 0 / 0 | 0 | 0 | 29 |
| `warm,gated,expect,probes,code,casetrees` | 0 | 0 | 16,092 | 1,683 | 0 | 863,982 / 1,846 / 58 | 0 / 0 / 0 | 0 | 0 | 29 |
| `all` (= gated,pchk,expect,warm,facts,tele,code,casetrees,probes,inert) | 0 | 0 | 16,092 | 1,683 | 0 | 863,982 / 1,846 / 58 | 0 / 0 / 0 | 0 | 0 | 29 |

The 29 declines (bugs corpus 24, mdata 5; the driver counts both attempts: 58) are the declarations that
enter eager reduction; the reference kernel decides them and the final verdict is the reference pipeline's.
Seven switch sets in two campaigns: no machine bug found by the mutants. Not covered: `k2`, `m3a`, `m4a/b`,
`m5a/b`, `m7a/b`, `lazyeval`, `t2`, `levels-complete`.

### 8.2 The large corpora (priority 4; coordinated with M6 in requests.log 15:06Z / 15:22Z)

M6 has the 8-worker v0 rows (13:30Z: all five ACCEPT, machine declined 0). `big.py`, native image `-O3` of
the jars published at 14:41Z (machine code `767442aced60`), 4 workers, one slot per run
(`work/status/machine-big-partial.md`, `machine-big-mathlib.md`, rows in `machine-big.tsv`):

| corpus | units | engine checks | `v0`: accepted / rejected / declined | `warm,gated,expect`: accepted / rejected / declined | result |
|---|--:|--:|--:|--:|---|
| con-leche | 36,747 | 36,274 | 36,274 / 0 / 0 | 36,274 / 0 / 0 | agree |
| cedar | 146,058 | 144,098 | 144,098 / 0 / 0 | 144,098 / 0 / 0 | agree |
| cslib | 401,985 | 397,608 | 397,608 / 0 / 0 | 397,608 / 0 / 0 | agree |
| navier-stokes-euler | 126,468 | 124,850 | 124,850 / 0 / 0 | 124,850 / 0 / 0 | agree |
| mathlib | 721,845 | 714,712 | 714,712 / 0 / 0 | see the log | agree (v0) |

MACHINE-INCOMPLETE, WARM-ANOMALY and disagreements are 0 in every run. "agree" is the per-declaration
differential on these corpora because the reference engine accepts each of them unit for unit (D21).
Orientation only (no lock, load average about 100): CPU s `v0` -> `warm,gated,expect`: con-leche 37 -> 29,
cedar 41 -> 33, cslib 154 -> 108, navier-stokes-euler 207 -> 142; mathlib `v0` 368 s CPU, 2.7 GB RSS.

### 8.3 The scoreboard (priority 3)

`work/status/machine-ladder.md`. The queue for the measurement lock was longer than an hour during the
wave (five packages' batches), so the first rows are a PROBE (`ladder.py probe`: no lock, CPUs 18 and 19,
1 worker): instruction counts are comparable, CPU time is not. Machine code `767442aced60` (jars of 14:41Z:
M1's kept-slot PRUNE of 14:22Z, M4's official `probes`), instructions G (ratio to the reference engine):

| | Init | Std | Cedar slice | reachability |
|---|--:|--:|--:|--:|
| reference engine | 246.8 | 407.8 | 54.8 | 112.6 |
| machine `v0` | 132.1 (0.54x) | 211.2 (0.52x) | 38.9 (0.71x) | 57.4 (0.51x) |
| machine `warm,gated,expect` | 105.2 (0.43x) | 177.2 (0.43x) | 39.5 (0.72x) | 59.1 (0.52x) |
| machine `warm,gated,expect,probes` | 106.7 (0.43x) | 179.6 (0.44x) | 39.7 (0.72x) | 59.1 (0.52x) |
| machine `v0` at the end of wave 5 (integrator, jar 608b84b2b142) | 139.0 | 223.2 | 47.0 | 59.4 |

So the first publications of wave 6 took 5% of the instructions off Init and Std, 17% off the Cedar slice
and 3% off reachability in `v0`. A locked batch (CPU s, 1 and 4 workers, gruszka in the same batch) was
queued at 15:10Z; its rows, when it got the lock, are in `machine-ladder.md` and announced in
`work/status/machine-M8.log`.

**The locked batch got the lock at 16:44Z** (batch `2026-11-24T16:44Z`, host load 39-47; machine code
`767442aced60` = jars of 14:41Z; reference engine and gruszka in the same batch), CPU s with 1 worker
(4 workers: CPU s / wall s):

| | Init | Std | Cedar slice | reachability |
|---|--:|--:|--:|--:|
| reference engine | 14.39 (15.14 / 8.33) | 24.19 (24.96 / 13.75) | 4.10 (4.55 / 1.50) | 12.55 (12.49 / 8.32) |
| gruszka (arena run line, 4 threads) | 3.34 | 5.54 | 1.80 | 4.01 |
| machine `v0` | 7.41 (7.80 / 4.43) | 12.11 (12.58 / 5.43) | 2.58 (2.75 / 0.94) | 8.11 (8.17 / 5.89) |
| machine `warm,gated,expect` (4 MiB) | 7.33 (7.85 / 3.62) | 12.04 (13.22 / 7.79) | 2.67 (2.83 / 0.98) | 7.79 (7.70 / 5.56) |
| machine `warm,gated,expect,probes` | 7.16 (7.63 / 3.24) | 12.38 (13.14 / 7.02) | 2.72 (2.93 / 1.25) | 7.83 (7.78 / 7.61) |
| best machine / gruszka | 2.14x | 2.17x | 1.43x | 1.94x |

(Wall with 4 workers was taken on a loaded host: the reference engine's 8.33 s on Init was 4.38 s on the
quiet host of wave 5.) Two readings for the owners: (a) against wave 5's final batch (`v0` 7.77 / 13.32 /
3.17 / 7.95 s) the first wave-6 publications are worth 5% of the CPU time on Init, 9% on Std, 19% on the
Cedar slice in `v0`; (b) **`warm,gated,expect` takes 20% of the instructions off Init and 16% off Std and
1% of the CPU time**: the process no longer runs at a constant number of instructions per second (v0
17.8 G/s, warm 14.4 G/s on Init), so on this machine jar the instruction count is NOT a proxy for CPU time
across switch sets. The CPU differences between the three machine rows are 1-4% in either direction on one
run per row: inside the band in which the rule of the wave asks for three runs, so this batch neither
shows a gain of the step-1 switches nor a loss (Cedar slice 2.58 -> 2.67 s, reachability 8.11 -> 7.79 s);
`ladder.py measure --runs 3 --sets 'none;warm,gated,expect'` is the batch that would settle it.

Probes of the newest jar at the end of the session (code `22f54062df4d`, jars of 16:53Z; instructions G,
1 worker, no lock):

| | Init | Std | Cedar slice | reachability | machine declined (engine checks) |
|---|--:|--:|--:|--:|---|
| reference engine | 246.8 | 407.8 | 54.7 | 112.6 | - |
| machine `none` (the generic tier) | 132.0 | 218.1 | 39.9 | 56.9 | 328 / 406 / 22 / 16 |
| machine default = `warm,gated,expect` | 116.4 | 197.6 | 39.4 | 57.9 | 328 / 406 / 22 / 16 |
| machine `warm,gated,expect,probes,code,casetrees` | 96.5 | 169.7 | 38.5 | 17.4 | 328 / 406 / 22 / 16 |

Every machine row of this jar is a FALLBACK row: the declarations that enter eager reduction are declined
(M6's rule of 15:07Z) and checked by the reference kernel, whose instructions are in the totals.
Reachability with `code,casetrees` on top: 17.4 G instructions, 1.48 s of (unlocked) CPU against gruszka's
4.0 s. NOTE the labels: since M6's publication of 16:24Z the engine's default set is `warm,gated,expect`;
the generic tier is `-X machine.switches=none` (the tools say so explicitly since 17:18Z; four probe rows
that were taken without the setting are labelled "default" in the TSV).

### 8.4 The gate (priority 1)

Full gate of the generic tier, main tree as copied after 16:09:23Z (`work/m8/machine-gate-v0-1609Z.md`;
4 passes, 6 GiB, 64 minutes wall beside a second gate at load 45-70): **PASS WITH DECLINES**.

| corpus | engine checks | accepted | rejected | declined | SOUNDNESS | COMPLETENESS | convention differences (D0, unbounded) | audit differences / E_AUDIT | pipeline: verdict, MACHINE-INCOMPLETE, WARM-ANOMALY |
|---|--:|--:|--:|--:|--:|--:|--:|--:|---|
| tutorial (141 files) | 195 | 175 | 20 | 0 | 0 | 0 | 0 | 0 / 0 | right, 0, 0 |
| bugs (34) | 1,147 | 1,136 | 10 | 1 | 0 | 0 | 0 | 0 / 0 | right, 0, 0 |
| all 230 | 15,845 | 15,798 | 40 | 7 | 0 | 0 | 0 (13 not compared: host stack overflow without a bound) | 0 / 0 | 230 / 230 right, 0, 0 |
| Init | 57,938 | 57,774 | 0 | 164 | 0 | 0 | 0 | 0 / 0 | accept (4 workers), 0, 0 |
| Std | 100,096 | 99,893 | 0 | 203 | 0 | 0 | 0 | 0 / 0 | accept, 0, 0 |
| reachability | 4,224 | 4,216 | 0 | 8 | 0 | 0 | 0 | 0 / 0 | accept, 0, 0 |
| Cedar slice | 8,064 | 8,053 | 0 | 11 | 0 | 0 | 0 | 0 / 0 | accept, 0, 0 |

The declines: 386 + 5 declarations that enter eager reduction (UNSUPPORTED_INPUT; Init 164, Std 203,
reachability 8, Cedar slice 11, perf/grind-ring-5 4, bugs/rec-of-subst-prop 1), perf/loop-unfolded-once
(E_MEM at 2^28 run words, as in wave 5) and perf/magma-list-deep-n36 `countermodel` (the report's 6 GiB
heap: `Machine.heapAllows` refused a 1 GiB array; `--heap 12g` checks it). Frames written under D128:
Init 0.051%, Std 0.047%, Cedar slice 0.026%, reachability 1.14%.

Found by the gate on the way (quick form, tutorial + bugs + Cedar slice, `warm,gated,expect`, tree of
14:46Z): bad/bugs/rec-of-subst-prop unit 128 `Issue.forallFalse`: lane `memocheck,shortcheck` E_AUDIT
("CONV .. is memoised as HOLE/T but the re-computation gives NONE/F") and the generic tier rejecting for
another reason than the warm tier: the warm-session / eager-reduction defect that M5 and M6 reported in the
same quarter of an hour (F26); it led to the rule "a declaration that enters eager reduction is declined".

Full gate of `warm,gated,expect` (4 MiB session cap), the same tree (`work/m8/machine-gate-step1-1609Z.md`;
65 minutes wall): **PASS WITH DECLINES**, the declines being exactly those of the generic tier. Differential:
SOUNDNESS 0, COMPLETENESS 0 on every corpus (all 230: 15,798 accepted, 40 rejected, 7 declined; Init
57,774 / 164 declined; Std 99,893 / 203; reachability 4,216 / 8; Cedar slice 8,053 / 11). Tier equivalence
against the generic tier: 0 declarations with another outcome, 0 with another reason. Convention
equivalence with warm sessions: 0 differences (12 perf declarations not compared with the unbounded lane);
frames under D128: Init 0.072%, Std 0.064%, Cedar slice 0.41%, reachability 1.15%. Audits: 0 outcome
differences, 0 `E_AUDIT`. Pipeline: 230 / 230 verdicts right, the four corpora accepted with 4 workers,
MACHINE-INCOMPLETE 0, WARM-ANOMALY 0, disagreements 0.

**Finding M8-F1 (17:35Z-17:55Z, open; requests.log to M2 and M7).** The quick gate of
`warm,gated,expect,probes,code,casetrees` on the tree of about 17:30Z FAILS on convention equivalence and
on nothing else (differential 0 / 0, generic tier equal, tier test on values 175 constants / 700 results
equal, audits clean, pipeline right): on the Cedar slice two of 8,064 declarations (unit 6225
`Cedar.Thm.conversion_preserves_typedness._unary`, unit 7434
`Cedar.Thm.ofEnv_entities_valid_refs_for_wt_expr._unary`) use one tick more under host depth 0 than under
D128 (two with `probes`); the lane without a bound agrees with D128. Bisected with `report.sh gate
--switches S --parts conv cedar`: clean for `code`, `code,casetrees`, `expect,code,casetrees`,
`warm,code,casetrees`, `warm,gated,expect`, `warm,gated,expect,probes`; **not clean for
`gated,code,casetrees`**. A tick on a path that only runs when a judgment is suspended, in the interplay of
gated tabling and the K1 blocks.

### 8.5 Which switches the evidence supports (as of 17:55Z; M8's evidence only)

| switch set | mutants (17,775 x set) | gate | large corpora | CPU (locked batch 16:44Z, jar 14:41Z) | M8's reading |
|---|---|---|---|---|---|
| none (generic tier) | S 0 / C 0, machine's own answers never differ | full: PASS WITH DECLINES | 5 / 5 agree | the baseline | correct; 391 declarations declined for eager reduction since 15:07Z |
| `warm,gated,expect` | S 0 / C 0 twice (trees 14:42Z, 15:24Z) | full: PASS WITH DECLINES, same declines, tier / convention / audits clean | 5 / 5 agree | within 1-4% of the generic tier on all four corpora, one run each | CORRECT by every gate M8 has; the CPU gain is NOT shown (needs `--runs 3`); it is the engine's default since M6's publication of 16:24Z |
| `warm,gated,expect,probes` | S 0 / C 0 (tree 14:42Z, after M4's fix of S-F1) | not run | not run | no gain over `warm,gated,expect` | not ready: no gate, no gain |
| `code,casetrees` | S 0 / C 0 (tree 15:24Z) | not run | not run | not measured under the lock | not ready: no gate |
| `warm,gated,expect,probes,code,casetrees` | S 0 / C 0 (tree 15:24Z) | quick form (tutorial, bugs, Cedar slice): **FAIL, finding M8-F1** (convention equivalence: `gated` + `code,casetrees`) | not run | probe: Init 96.5 G, reachability 17.4 G instructions | the candidate with the largest effect; NOT ready: M8-F1, then the full gate and a locked batch |
| `all` | S 0 / C 0 (tree 15:24Z) | not run | not run | not measured | bug-hunting set only |

### 8.6 Tool experiments, kept and rejected (wall time of the tools, not CPU time of the machine)

| experiment | result | kept |
|---|---|---|
| pass runner (reference first, lanes one after the other, digests) instead of lockstep for corpus runs | perf/app-lam, gf3-matrix-product, magma-list-pair-n21 with five or six lanes CLEAN in a 6 GiB JVM (lockstep: OutOfMemoryError with four lanes); peak = one engine | kept |
| first full gate with 12 passes and a 24 GB heap (13:44Z) | Init / Std / reachability / Cedar slice lanes done after 271 s, then the host went into the swap (my JVM at 23.5 GB RSS among others) and a full collection never ended; killed after 25 minutes, no result | rejected: 6 GB, 4 passes, a slot |
| heavy files one at a time on one thread (no memory of earlier runs: all 43 perf tests) | on the loaded host that thread got 15% of a CPU: 48 minutes for the heavy phase without an end; killed | rejected: `work/m8/heavy.txt` (measured peaks), light perf tests in the pool, heavy ones two at a time, only sessions above a sixth of the heap alone |
| the same gate with that scheduling (16:10Z) | 64 minutes wall for 234 files x 5 lanes + pipeline, of which 25 minutes for the four corpora and about 30 for 6 heavy perf files and 4 heap retries; two gates ran side by side | kept; on a quiet host `--heap 12g --jobs 8` |
| mutants through the pipeline in one JVM instead of a JVM per mutant | 17,775 mutants x (3 sets + baseline) + per-declaration comparison in 19 minutes at 4 at a time; the launcher needs 13-27 minutes per set at 10-12 at a time; verdicts identical (baseline table, 537 launcher samples) | kept |
| incremental digest for warm lanes | Cedar slice, 6 lanes, warm: 8-20 s CPU per lane, convention equivalence still exact at resets | kept |
| scoreboard: slot first, then the measurement lock | held a host-wide slot idle for 25 minutes in the lock's queue | rejected: lock first, then a slot within two minutes, else the lock goes back |
| scoreboard probe without the lock (CPUs 18, 19) | instruction counts identical to the locked batch (132.1 / 105.2 / 106.7 G on Init) | kept as a stop-gap; CPU time only from locked batches |

### 8.7 Incidents

- 13:44Z-14:09Z: my first full gate (24 GB heap) was one of the processes on the host when it ran out of
  memory; I killed it by pid at 14:09Z. Since then every M8 job holds a slot and stays at 6 GB.
- 15:55Z: a `pgrep -f` pattern of mine matched the shell that issued it; that command ended my own shell
  and the slow-test script (intended); the test JVM it had started was then stopped by pid. Nothing of
  another agent was touched.
- The two slow tests (`M8SlowTest#convAll`, `M7HookedTest#all230`) were started in the sandbox's surefire
  JVM at 15:45Z and stopped after 9 minutes to give the slot and 5 GB back when the host was at 16 GB
  available: **they have not been run to completion under surefire since the change.** What stands in
  for them: the same code path (`M8Lanes` passes, heap failures as flagged declines) ran all 230 tests
  with five and six lanes in a 6 GiB JVM twice (the two gates above) without a crash, and the fast test
  `aCheckThatTheJavaHeapEndsIsFlaggedAndTheLaneGoesOnWithANewMachine`.

## 9. Not done, not verified (wave 6)

- The full gate for any set with K1 switches (`code`, `casetrees`, `tele`, `facts`), `probes`, `lazyeval`,
  `k2`, the experiment switches; the tier test on values inside the gate (`M8Gate.valueTiers`) has run only
  in the quick form.
- `M8SlowTest#convAll` and `M7HookedTest#all230` to completion under surefire (8.7).
- A locked scoreboard batch for a jar newer than 14:41Z, and three runs per row for differences below 5%.
- Large corpora with other sets than the generic tier and `warm,gated,expect`; `--with-reference` was not
  used (the premise "the reference engine accepts every unit" rests on the pipeline check of wave 5).
- The mutants with `k2`, `m3a`, `m4a/b`, `m5a/b`, `m7a/b`, `lazyeval`, `t2`, `levels-complete`.
- The gate at `--heap 12g`: in 6 GiB perf/magma-list-deep-n36 `countermodel` and (warm) perf/loop-unfolded-once
  are declined by the heap test of the report JVM, so those two declarations are not gated.
- The gate's pipeline part and the mutants run the driver in the report's JVM (D18); the launcher sample
  is 179 mutants per set, not all.
