# NOTES-T3 - the Truffle tier after T2 (owner: T3, 2026-11-24)

Continues `NOTES-T2.md` (what the tier is, its deviations from ISA 8.4, its correctness record). This file:
the measurements T2 could not take, what the tier costs and gains on each platform and why, what was
changed, and the go / no-go. Scratch, scripts, logs and raw rows: `work/t3/` (`warm.tsv`, `logs/`).
Section 10 is the second task of the same day: the prototype of U1, the CALL instruction (NO-GO).

## 1. Verdict: NO-GO as a default; what the tier is worth, and under which policy

**The Truffle tier does not pay on Cedar-level work and cannot under the present value model; on closed
evaluation (reachability) it is worth 4 - 5% of the warm wall clock in the native image and 7 - 10% on the
JVM, with a promotion policy that compiles only the dozen blocks that carry the run. On the Cedar slice it
is neutral in the image and a loss on the JVM. `t2` stays off by default.** Nothing here argues for switching
it on in the launcher. Nothing argues for deleting it either: it is correct (section 8), and it is the
place where U1 / U2 of section 7 would pay.

Warm wall clock per pass in ms: median of the later half of the warm passes of all runs of the
configuration in the window (in brackets, where it matters, the range of those passes). `K1` = `default,code,casetrees`; tier = the same
+ `t2`, `-Drlvm.t2.mode=warm`, without the rejected run-ahead. Every row is one `bench/quiet.py` window, K1
and tier back to back on the same distribution (`work/t3/warm.tsv`, `launcher.tsv`; labels in the tables).

**Table A - the final tree and the final policy** (promotion at 20000 entries and 2% of all activations;
fixes C7 / C8 of section 4 in; machine jar `8a8ea5644b24`):

| Workload | Platform, tool | Workers | K1 | K1 + t2 | blocks compiled | window |
|---|---|--:|--:|--:|--:|---|
| reachability | JVM, WarmMain | 1 | 1118 (2 runs) | 1006 (**-10.0%**) | 12 | J9 |
| reachability | JVM, WarmMain | 8 | 572 (2 runs) | 529 (**-7.5%**) | 12 | J9 |
| reachability | JVM, LAUNCHER, kept-engine passes | 8 | 439 (2 runs) | 394 (**-10.2%**) | 11 | L1 |
| reachability | JVM, LAUNCHER, kept-engine passes | 1 | 1036 | 1020 (-1.5%) | 12 | L1 |
| reachability | native, WarmMain | 1 | 1448 (2 runs) | 1392 (**-3.8%**) | 12 | N5 |
| reachability | native, WarmMain | 8 | 737 (2 runs) | 701 (**-4.8%**) | 12 | N5 |
| Cedar slice | JVM, WarmMain | 1 | 1426 (1417-1504) | 1487 (**+4.3%**; 1484-1491) | 2 | J9 |
| Cedar slice | JVM, WarmMain | 8 | 220 (211-227) | 286 (**+30%**; 272-343) | 2 | J9 |
| Cedar slice | native, WarmMain | 1 | 1808 (1800-1812) | 1802 (-0.3%; 1796-1814) | 2 | N5 |
| Cedar slice | native, WarmMain | 8 | 316 (2 runs, 293-348) | 324 (+2.4%; 296-353): inside the scatter | 2 | N5 |
| full cedar | JVM, WarmMain | 8 | 1469 (1274-1696) | 1355 (1345-1546): no difference to be seen | 12 | J9 |
| full cedar | JVM, LAUNCHER | 8 | 1574 (1543-1578) | 1664 (+5.7%; 1622-1693, passes still falling) | 12 | L1 |
| Init | JVM, WarmMain (C7 only) | 8 | 551 (536-628; all five passes 536-718) | 632 (581-641; all five 581-722): no gain; five passes are not a steady state here | 10 | J8 |
| Std | JVM, WarmMain | 8 | 949 (895-1036; passes still falling) | 1004 (920-1042; still falling): no gain | 12 | J8 |

The Cedar slice at 8 workers on the launcher did not reach a steady state in 7 passes (K1's own two runs:
398-928 ms) and is not in the table.

**Table B - the promotion policy** (earlier windows, before C7 / C8, machine jar `4bc20d3e46ef`; C7 keeps
one machine per session reachable in the tier runs, so the 8-worker rows are biased against the tier by an
amount I did not separate; they are here for the comparison of the policies with each other):

| Workload | Platform | Workers | K1 | tier, at 1000 entries, unbounded | tier, at 20000 entries, at most 96 blocks | windows |
|---|---|--:|--:|--:|--:|---|
| reachability | JVM | 1 | 1015 (4 runs: 986-1058) | 973 (-4.2%; 212 blocks) | 949 (-6.5%; 26 blocks) | J6 |
| reachability | JVM | 8 | 520 | 529 (+1.7%; 177 blocks) | - | J4 |
| reachability | native | 1 | 1475 (2 runs) | 1370 against 1424 in its window (-3.8%; 252 blocks) | 1392 (-5.6%; 26 blocks) | N3, N2 |
| reachability | native | 8 | 761 (2 runs) | 779 (+2.3%; 182 blocks) | 707 (-7.1%; 28 blocks) | N3 |
| Cedar slice | JVM | 1 | 1359 (2 runs) | 1489 against 1438 in its window (+3.5%; 804 blocks) | 1462 (+7.6%; 96 blocks) | J6, J3 |
| Cedar slice | JVM | 8 | 232 | 321 (+38%; 686 blocks) | - | J4 |
| Cedar slice | native | 1 | 1869 | - | 1888 (+1.0%; 88 blocks) | N3 |
| Cedar slice | native | 8 | 319 | 391 (+23%; 713 blocks) | 351 (+10%; 96 blocks) | N3 |
| full cedar | JVM | 8 | 1315 | 1827 (+39%; 1,820 blocks, 52.6 s of compilation) | - | J4 |

Compilation: a block takes about 30 ms of compiler CPU on the JVM (7.6 s for 252 blocks of reachability,
27 s for 804 of the Cedar slice) and about 20 ms in the image (5.4 s for 252). Deoptimisations: 0 in every
measured run (the nodes have no profile and no assumption; `engine.MaximumCompilations=1`). Failed
compilations: 0 in every run of the tier without the run-ahead; with it 1 of 804, 2 of 692 and 6 of 1,821
blocks (they stay in the stepper).

What the numbers say:

1. **Closed evaluation gains, and only with few blocks.** Reachability is 83% inside code blocks and three
   blocks have two thirds of its 2.5 M activations per pass. Compiling the 11-14 blocks that pass the
   final policy gives -7.5 to -10% on the JVM (WarmMain and the launcher, 8 workers) and -3.8 to -4.8% in
   the image; 26 blocks gave -5.6 to -7.1% (both platforms, table B); 180-250 blocks give less (-4%) or
   nothing (8 workers: +2%). The ceiling is the
   stepper's own share of such a run, 17% (section 5); the rest is interning, which compiled code repeats
   call for call. One row does not fit: the launcher at one worker (-1.5%), one run.
2. **Cedar-level work has nothing to gain and, on the JVM, something to lose.** The Cedar slice spends 14%
   of its time inside blocks at all, spread over hundreds of them. With the final policy two blocks are
   compiled, 45 k compiled activations per pass. In the image that is neutral (-0.3% at one worker, +2.4% at
   eight, inside the scatter). On the JVM the pass is 60 ms slower at one worker (+4.3%) and at eight (+30%
   of 220 ms, at unchanged CPU time of the workers): more than 1 microsecond per compiled activation, which
   compiled code cannot cost. C7 and C8 were found while looking for the cause and are not it (J7 before
   them: +27% with one block); section 9, point 3 has what I know.
   With an unbounded count everything is compiled in the end, because the count is cumulative: 804 blocks on
   the slice, 1,820 on full cedar, the compiler threads busy through every measured pass and, with 8
   workers on 8 cores, taking cores from them: +23 to +39% (table B).
3. **T2's "55-80% loss on the native image"** was the heat accounting and the measurement, not the code
   (section 3).
4. **Platform**: K1 itself is 20-30% faster on the JVM than in the native image in steady state, same
   machine jar (`4bc20d3e46ef`): reachability 1.02 s against 1.42-1.48 s, Cedar slice 1.36-1.44 s against
   1.78-1.87 s (one worker), Cedar slice with 8 workers 232 ms against 298-319 ms. That is not a tier
   result, but under OWNER-NOTES 12 it is the larger number in this file. (WarmMain, not the driver: no
   loading, no admission pipeline.)

**Promotion policy** (what `mode=warm` now does; `rlvm.t2.at`, `.hot`, `.max`): the tier counts, per machine,
the activations it does not take; a block is promoted when the engine has seen 20000 of them AND the block
has at least 2% of ALL activations of the engine so far (compiled ones included), at most 256 blocks; the
machine's heat accounting is never switched on; two compiler threads. In a warm-up-is-free world the
threshold could be lower - but a cumulative count below "this block carries the run" is what produces
table B's Cedar rows, so the test that matters is the share, not the count.

**If the integrator wants it on for an evaluation-heavy run anyway**: `-X machine.switches=default,code,casetrees,t2
-Drlvm.t2.mode=warm`, with `rlvm-truffle`'s jar on the class path (module not in the parent pom: README
"Build, test"); with `-X passes=N -X passes-mode=recheck -X passes-keep=engines` (rlvm-driver `150aed4bf1bf`)
the launcher shows it.

## 2. How the numbers were taken

- **Tool**: `rlvm.truffle.WarmMain` (test tree): one process, ONE machine engine kept over all passes; pass 1
  cold with the environment pass, every later pass checks all units again with fresh sessions of the same
  engine on W worker threads (chunks of 16 units from a shared counter). This is what "warm" has to mean for
  this tier: until 18:04Z the launcher's `-X passes=N -X passes-mode=recheck` built new engines per pass
  (T2's finding of 16:20Z), so no launcher run could credit a code store or compiled blocks. Not the
  driver: no admission pipeline, no re-check, no pipelined loading.
- **Launcher rows** (label L1, `work/t3/launchrun.py`, `launcher.tsv`): `bin/rlvm-jvm --threads W FILE -X
  engine=machine -X passes=N -X passes-mode=recheck -X passes-keep=engines` with rlvm-driver `150aed4bf1bf`
  (P7, 18:04Z): the real driver, the engine instances of pass 1 kept; the number read is `check_ms` of the
  later passes.
- **Quiet**: every number in a table below comes from inside `bench/quiet.py -- work/t3/wN.sh` (one
  measurement at a time on the 8 reserved performance cores; `quiet.log` has each window). Runs of one
  window are back to back; a configuration is repeated inside the window where the table says so. Rows:
  `work/t3/warm.tsv` (column `quiet` = the window), complete outputs `work/t3/logs/`.
- **What is read**: warm WALL clock per pass (OWNER-NOTES 12). With one worker the worker thread's CPU
  time is within 1-3% of it; compiler threads run on the other measurement cores.
- **Platforms**: JVM = the launcher's flags (`-XX:+UseSerialGC -XX:-UseJVMCICompiler -XX:CICompilerCount=2`,
  C2 for the host, libgraal for Truffle), `-Xmx6g`; native = CE native image `-O3 --gc=serial` of
  `WarmMain` (`work/t3/build-dist.sh --warm`).
- **K1** = `-X machine.switches=default,code,casetrees` (the default set of 16:24Z plus the K1 blocks and
  case trees); **K1 + t2** = the same plus `t2`.
- **Machine jars**: `rlvm-machine` is republished every few minutes. A distribution of mine is a snapshot
  (`BUILD-INFO.txt`); K1 and K1 + t2 of one table row are always the same distribution, rows of different
  distributions are not compared.

## 3. T2's tier as it was left: parity on the JVM, a small loss on the native image, and why

Warm wall clock per pass in ms (median of the later passes; in brackets the range of all warm passes);
one worker unless stated; `quiet.log` windows of 16:46Z, 17:00Z (JVM, distribution `work/t3/j1`, machine
jar `a7e37a04cf90`) and 17:09Z (native, `work/t3/n1`, machine jar `ba557722c184`).

| Workload, platform | K1 | K1 + t2, as T2 left it (`mode=warm`, heat timing 4096) | K1 + t2, promote at 2000 entries, heat counting only | tier attached, NOTHING promoted, heat counting only | the same, heat timing 4096 |
|---|--:|--:|--:|--:|--:|
| reachability, JVM (window 1) | 1027 / 980 (962-1103) | 1031 / 971 (910-1061) | - | - | 1138 (1095-1187) |
| reachability, JVM (window 2) | 1049 (993-1113) | 1023 (980-1095) | 1023 (970-1070) | 1074 (1052-1190) | - |
| reachability, JVM, 8 workers | 552 (496-623) | - | 552 (496-699) | - | - |
| Cedar slice, JVM | 1443 (1369-1559) | - | 1505 (1407-1581) | - | - |
| reachability, native | 1399 (1358-1501) | 1449 (1393-1475) | 1445 (1356-1480) | 1470 (1454-1490) | 1518 (1457-1617) |
| Cedar slice, native | 1844 (1838-1851) | - | 1879 (1843-1934) | - | - |

Blocks installed / compile CPU / compiled activations per warm pass: reachability 105-148 blocks, 2.0-2.7 s,
2.5 M (JVM) and 107-138, 1.7-2.1 s, 2.6 M (native); Cedar slice 576 blocks, 15.5 s, 0.88 M (JVM) and 468,
14.1 s, 0.87 M (native) - on the Cedar slice blocks were still being promoted in the last measured pass.
Deoptimisations: 0 everywhere.

What the table says:

1. **The machine's heat accounting is most of what the tier costs.** Counting every entry (`Tier.count`):
   +2.4% (JVM), +5.0% (native). Timing on top, even at one sampled entry in 4096 (a sampled entry times
   every block entered beneath it): +11% (JVM), +8.5% (native) against K1. T2's tier switched it on because
   its governor rents by measured time.
2. **Compiled blocks win back about that much and no more**: against "tier attached, nothing promoted" they
   gain 4.7% (JVM) and 1.7% (native) on reachability. Net against K1: -2.5% to parity on the JVM, +3.3% on
   the native image, +2 to +4% on the Cedar slice.
3. **The cause of T2's "55-80% loss on the native image"** (its row Tn1): not the compiled code and not a
   deoptimisation loop. In a quiet window the tier costs 3-4% there (points 1 and 2). T2's row was process
   CPU on the open host with the heat timing at 64 or 4096: it adds the compiler threads (0.25-0.4 s per
   pass while blocks are still promoted: process CPU 1640-1870 ms against 1392-1480 ms of wall clock in
   the same passes here) and the host's own factor of 1.5-2 between runs minutes apart.
4. The JVM's K1 is faster than the image's in these windows too (1.03-1.05 s against 1.40 s on
   reachability), but the two distributions hold different machine jars; the same-jar comparison is
   section 1, point 4.

## 4. What was changed in the tier (all inside `rlvm-truffle`; nothing in `rlvm-machine`)

| # | Change | Where | State |
|---|---|---|---|
| C1 | **Promotion by the tier's own entry counters, without the machine's heat accounting** (`rlvm.t2.count`, default in `mode=warm`; policy C9). `T2Tier.run` counts the activations it does NOT take (per machine, reported to the engine in batches, C8); an activation that compiled code takes is counted and timed by nobody. `Tier.heat(engine)` is not called, so `Tier.exec` is a plain call of the tier. `mode=cpu` keeps T2's rent-to-own governor on the heat accounting | `T2Tier`, `T2Engine.bump`, `Settings` | kept; removes the 2.4-11% of section 3 |
| C2 | **Pure-read helpers compiled into the block** (`rlvm.t2.inline`, a class constant, now 1): `PEEK` of an operand, the constructor test of `SWITCH`, the argument base of `FIELDS`, the inline-cache probe of `KVAL`, the cache of CONST heads in front of `KCONST`. Everything that can write or grow a store stays a call behind a boundary | `Instr`, `T2Bridge.*Inline / *Hit` | kept |
| C3 | **`KCONST` with level parameters uses the site's inline cache as the stepper does** (`Code.kConst`: one probe; miss: `CHEAD(D, LSUB(LL, ls))` and fill). T2's node called `lsubList` + `mkConst` every time (the stepper's cache is of wave 6, NOTES-M7; the mirror did not have it) | `Instr.Konst`, `T2Bridge.kConstHit / kConstFill` | kept (a drift, not a wrong value) |
| C4 | **Nat literals natively** (owner note 7): `NSUCC` of an immediate below the limit is `v + 1` in the block (overflow guard: the operation), the predecessor view of `FIELDS` is `v - 1`, `SWITCH` on an immediate is a compare | `Instr.Unary1 / Fields / Switch` | kept; the value model already has the immediates, so this removes two calls, not a boxing |
| C5 | Run-ahead of the interning probes (`rlvm.t2.ahead`, section 6) | `BlockRoot.ahead`, `Instr.*.ahead`, `T2Bridge` | **rejected by measurement, default off** |
| C6 | No one-call forwarders in code that partial evaluation reaches (`T2Bridge.peekInline` and the hash functions are written out; `T2Bridge.hashOk()` compares them with the machine's once per engine and switches the inline-cache probes back to the boundary operations if they ever differ) | `T2Bridge` | needed for the native image: a method whose body is one call is inlined before the image's analysis and its deoptimisation target is then missing - the rule T2 found for one-store setters holds for forwarders too |
| C7 | **The engine keeps the tiers' counters, not the tiers.** `T2Engine` had a list of every `T2Tier` it ever made (for the report), a tier references its machine, and an engine outlives its sessions: every machine that ever ran in an engine stayed reachable, with its arenas. With fresh sessions per pass (WarmMain; the driver's kept engines) the heap grew by the workers' arenas each pass, for the tier runs only | `T2Tier.Counts`, `T2Engine.register` | fixed 18:30Z (a defect since T2's first version). The rows of sections 1 and 3 taken before it (labels J1-J7, N1-N3) carry it; with one worker it is one machine per pass. Rows after it: J8, J9, L1, N5 |
| C8 | **Count mode reports per 256 stepped activations of a block** (was 64) and carries the machine's compiled activations along, in one short critical section (before: a second lock and a loop over all tiers at every report of a block above the threshold) | `T2Tier.miss`, `T2Engine.bump` | fixed 18:34Z; eight workers met in that section often enough to show (section 1, rows J7 against J9) |
| C9 | **Promotion policy of `mode=warm`**: at 20000 entries AND 2% of all activations of the engine (`rlvm.t2.hot`), at most 256 blocks (was: at 1000 entries, at most 4096) | `Settings`, `T2Engine.bump` | section 1 |
| C10 | `T2Engine.retire()`: an engine whose harness closes cancels its queued compilations (tools and tests with several engines in one process) | `T2Engine`, `T2Runtime.promote`, `Harness.close` | section 8 |

Not done, and why: direct calls between compiled blocks (section 7, U1).

## 5. Where the time of a block goes (why removing the dispatch buys so little)

JFR execution samples of warm K1 passes on the JVM (one worker; open host, so SHARES only;
`work/t3/jfr/*.top.txt`):

| | reachability | Cedar slice |
|---|--:|--:|
| inside code blocks, inclusive (`Code.enter`) | 83% | 14% |
| the stepper's own code (`Code.*` self: dispatch, operand decoding, register traffic, `go`) | 16.8% | 6.9% |
| value construction BY BLOCK INSTRUCTIONS, inclusive: `Code.mkApp` / `Code.mkClosure` / `Code.mkProj` | 42% / 14% / 4% | 3% / 3% / 0.3% |
| self time of the probes and inserts, whoever calls them (`Values.neu`, `closureProbe`, `Runs.internRun`, `Values.rec`, `neuExt`, `Machine.addRecordAt`, `growVtab`, `growRtab`) | 57% | 26% |
| generic judgments (`Infer.check` inclusive) | 8% | 59% |

A compiled block calls the same operations as the stepper (one semantics, the same records in the same
order - the tier-equivalence rule). What partial evaluation can remove is the second row; the third and
fourth rows are identical work in both tiers. That bounds the gain of ANY compilation of single blocks at
17% on reachability and 7% on the Cedar slice before its own entry costs; measured on reachability: 4-10%
(section 1).

The hottest blocks of reachability (activations per warm pass; `work/t3/logs/listing-reach-t2-0.log`):

```
570 k  RULE(List.rec)        BUNREAD A3, 2/3, r1, L1 ; KCONST r0, List.rec ; MKAPP r1, r0, A0, A1, A2, A3, A5 ; L1: TAILAPPLY A3, A4, A5, r1
560 k  BODY(#913)            (entry 3) BKIND C1 .. ; MKSUSP r0 ; MKSUSP r3 ; KCONST ; MKAPP r5 (List.below C0 C1 A1) ; KCONST ; MKAPP r7 (PProd r3 r5) ;
                             MKSUSP r8 ; KCONST ; MKAPP r12 (PProd.mk r0 r7 r8 A2) ; RET r12        - 3 frames + 3 suspensions + 3 applications interned
500 k  RULES(List.elem._f)   WHNF r0, A3 ; SWITCH r0, List ; FIELDS r2, r0, 2 ; KCONST r4, BEq.beq ; MKAPP r5, r4, A0, A1, A2, r2 ; WHNF r6, r5 ; SWITCH r6, Bool ; ..
 40 k  RULES(instDecidableEqFin)  KCONST x3 ; MKAPP r3 (Fin.val A0 A1) ; MKAPP r5 (Fin.val A0 A2) ; MKAPP r7 (decEq Nat inst r3 r5) ; WHNF r8, r7 ; SWITCH ; FIELDS ;
                             MKAPP (Fin A0) ; MKAPP (Eq ..) ; MKAPP (.._proof_1 A0 A1 A2 r9) ; MKAPP (Decidable.isFalse ..) ; RET
```

A block is "build the graph, then ask a generic judgment to reduce it": the redex `decEq Nat inst a b` is an
interned record before `WHNF` looks at it, the types `List.below ..` / `PProd ..` and the proof terms are
interned although nothing ever inspects them, and the callee is reached through `Reduce.whnf` /
`Eval.apply`, never called. There is no arithmetic and no branch a JIT could specialise beyond what the
stepper already does: `Nat` literals are immediates in the value model (owner note 7 is satisfied by the
machine itself; the tier now adds the native paths for `NSUCC` and the predecessor view of `FIELDS`).

## 6. Experiment REJECTED: run-ahead of the interning probes (`rlvm.t2.ahead`, default 0)

**Idea.** The cost that dominates a block is interning, and M1 had measured on the generic tier (v0, native,
perf annotate; requests.log 15:58Z) that 76-83% of the samples of a probe are ONE instruction, the load of
the table slot of a 64-256 MiB table. A block issues its probes one after the other (each is a call that
ends before the next starts, and often needs the previous result: `MKSUSP` = the frame's probe, then the
record's probe over the frame's id). A compiled block, unlike the stepper, has its whole future in front of
it as constants, so it could load the slots EARLY and let the cache misses overlap.

**As built** (`BlockRoot.ahead`, `Instr.*.ahead`, `T2Bridge.neuFind / recordFind / runFind / *Hash`; reads of
the machine only, one store into a field of the tier object to keep the loads; tier equivalence is not
touched, and the differential gate of section 8 ran with it on). At an entry of a root, and again after
every instruction that called a judgment, the block walks the stretch of value construction that follows
with PREDICTED registers: `MOV`, `PEEK`, `KCONST` / `KVAL` (cache hits), `FIELDS`, `BKIND` / `BEQ` / `SWITCH` on
known values, and for `MKAPP`, `MKLAM`, `MKPI`, `MKSUSP` the hash of the key exactly as `Values.neu` /
`Runs.internRun` / `Values.closureProbe` compute it and the load of the table word where the probe will
start. A key that is not in the table is predicted to get the next record id (`vp`) or run offset (`rsp`),
so that the keys built on it can be hashed before it exists. Two forms: "assume new" (never waits for a
loaded word) and exact (`rlvm.t2.aheadExact`: the loaded word is read - empty = new, the key = the existing
record; first four slots of the probe sequence; the frame / application predicted new a moment ago is
remembered for sibling closures). `rlvm.t2.aheadCheck=true` counts the predictions: on reachability the
exact form names the record the operation then returns for 90.3% of 13.6 M records per run; "assume new"
28% - HALF of the records that reachability's blocks build exist already.

**Result** (quiet windows J3 of 17:55Z, JVM, and N2 of 18:00Z, native; promotion at 1000 entries; warm wall
clock per pass in ms, median of the later half of the passes of both runs, the two runs' own medians in
brackets; the same tier with and without the run-ahead):

| Workload | K1 | tier, no run-ahead | tier + run-ahead, exact | tier + run-ahead, "assume new" | compile CPU without / with (exact) |
|---|--:|--:|--:|--:|--:|
| reachability, JVM, 1 worker | 1069 (1022, 1105) | 1021 (1012, 1041) | 1116 (1082, 1120) | 1147 (1167, 1109) | 7.6 s / 36-37 s (252 blocks) |
| reachability, native, 1 worker | 1424 (1408, 1442) | 1370 (one run) | 1484 (1500, 1462) | - | 5.4 s / 14.5 s |
| Cedar slice, JVM, 1 worker (one run each) | 1438 | 1489 | 1742 | - | 27 s / 140 s (804 blocks) |
| Cedar slice, native, 1 worker (one run each) | 1775 | - | 2061 | - | - / 56 s |

Against the same tier without it, the run-ahead is 8-12% SLOWER on reachability and far slower on the
Cedar slice (where the compiler threads are then busy through every measured pass). JFR on reachability
with the exact form (`work/t3/jfr/t3-reach.top.txt`; against the K1 profile of section 5): `Values.neu` has
15.5% of the samples instead of 22%, and the run-ahead itself has 17% (`MkApp.ahead` 10.3%): the waiting
moves into the run-ahead, it does not overlap. Why, as far as I can
say (readings, not measurements): (1) the probes of one stretch depend on each other - in the hottest block
(41) nine probes form chains of depth four (frame -> suspension -> application over it -> application over
that) - so most of the chain is as long as before, whoever walks it first; (2) M1's profile was of v0; with
`code,casetrees` the same corpus needs 5.6 times less CPU (M7: 8.79 -> 1.56 s), half the block-built keys
exist already, and a hit is not one load but three (table, record, spine): "one cache miss per probe" is
not the cost model of these blocks; (3) the root's code doubles (block 41: 4,638 -> 11,481 graph nodes,
31 -> 74 KB, measured with the checking counters compiled in).

**Kept in the tree, off**, because the differential and equivalence gates ran over it and the next owner
may want the predictor (`aheadCheck`) as an instrument; deleting it is `Instr.*.ahead`, `BlockRoot.ahead /
aheadInit / aheadKind / probes`, the run-ahead block of `T2Bridge` and the four `rlvm.t2.ahead*` settings. It is NOT a
candidate for the stepper either: the result above is an upper bound for a generic version of the same walk.

## 7. Design finding: what a JIT for the checking process needs from the machine (for the owners, M1, M3, M6, M7)

The Truffle tier is correct and it is fed the right programs (the judgment chains of constants, owner note
5), and still there is almost nothing in them for a compiler to remove. The reason is the value model, not
the tier:

1. **Every intermediate value is a globally interned record** (I-ID: identity = structure). A block that
   builds a value pays a hash, a probe of a table far larger than the cache, and usually a new record and a
   new spine or frame run. On reachability that is 57% of the warm time in the probes and inserts
   themselves (section 5); M1 measured that 96% of the application probes of the generic tier are for NEW
   keys (15:58Z); of the records that reachability's blocks build, about half exist already (section 6) -
   either way the block cannot know without the probe. Tier equivalence (same records, same order) obliges
   compiled code to repeat every one of them. Escape analysis, scalar replacement, unboxing - the
   optimisations a JIT lives on - have no object to work on: nothing a block makes is ever private to it.
2. **A block builds the redex and asks a generic judgment to reduce it.** `MKAPP r7 (decEq Nat inst a b)`,
   `WHNF r8, r7`: the callee is known when the block is elaborated, yet control reaches it through
   `Reduce.whnf` -> `Rules.unfold` -> `Code.rules` -> `Code.enter` -> the hook, with the arguments unpacked
   again from the record's spine. There is no call for Truffle to inline, so "one constant's code object
   inlined into its caller" cannot happen whatever the tier does. `APPLY` is the same through `Eval.apply`.
3. **Arguments nobody inspects are built eagerly**: the motive and the `List.below` / `PProd` types of every
   structural-recursion step, the proof terms of `Decidable.isTrue / isFalse` (block 41 and
   `instDecidableEqFin` of section 5). In a strict machine they would be dead code; here they are the
   components of an interned record, so they must exist and be interned first.

What would unlock a compiler (and, each of them, also the stepper):

- **U1 - call instructions.** `CALL d, c, a1..an` = WHNF of the application of constant `c` to the registers:
  enter `c`'s RULES / BODY block directly, intern the application only if the call is stuck or its result is
  to be tabled. One operation shared by the stepper and the tier, so tier equivalence is kept; the redex
  record, its spine run, the unpacking and the generic dispatch in between disappear for both, and the tier
  gets a call it can inline (`DirectCallNode`, T2's D5).
- **U2 - records that are not interned until they have to be** (M1's "young set", 15:58Z, is the table-side
  half of the same idea): a value made by a block lives in a per-activation or per-session nursery without
  a table entry and gets an identity only when it is stored into an interned record, used as a memo key,
  compared by identity or returned to a generic judgment. Then the values of point 3 are never interned,
  and in compiled code they are not even allocated. This changes I-ID from "always" to "once tenured" and
  therefore the tabling keys and the tick counts: it is a machine design decision, not a tier feature, and
  the equivalence rule between tiers has to become "same verdicts, same tenured records".
- **What does NOT work without them**: leaving the work and only overlapping its cache misses (section 6,
  measured: a loss).

Without U1 or U2 the upper bound of any compiled tier on closed evaluation is the stepper's own share
(17% on reachability; measured gain 4-10%), and on library corpora (Cedar: 14% of the time inside blocks at
all) it is nothing or a loss.

**CORRECTION (20:30Z, after U1 was prototyped and measured: section 10).** Point 2 describes the code
correctly and its weight wrongly, and U1 does not pay. The redex of `MKAPP ; WHNF` is a memo key that hits:
on reachability 77% of those WHNFs are answered by the memo of the application's record, so "do not build
the redex" means "compute it again" (ticks +8.3%). With the record kept as the key (the tabled form) CALL
changes no count by more than 0.3% and no time outside the scatter, in the stepper and in the tier; on
library corpora such sites are 1-3% of the activations. Of the two unlocks only U2 is left, and the census
of section 10.3 says where it can and cannot reach.

## 8. Tests and gates on the final tree

On machine jar `4bc20d3e46ef` (the one in `.toolchain/m2` from 17:30Z to about 18:20Z) unless the row names
another. "Final tree" = the sources as they are now; the rows say which of the changes of section 4 a run
did not have yet. The tutorial + bugs gate was NOT repeated after C7 - C10 (they do not touch a node: C7
which object the engine lists, C8 how often a machine reports its counts, C9 which blocks are promoted,
C10 cancelling queued compilations).

| Gate | Configuration | Result |
|---|---|---|
| fast group, `$MVN -o -q -f rlvm-truffle/pom.xml test -Dgroups=fast` (`work/t3/test-fast-2.txt`, 17:25Z) | 4 tests: T2's three + `countMode` | 4 / 0 failures / 0 errors |
| default group (`work/t3/test-default-2.txt`, 18:00Z, tree of 17:48Z: `rlvm.t2.ahead` still 2 by default, so the run-ahead was on in every test) | 7 tests: T2's five + `countMode` + `perfTestsWarmMode` | 7 / 0 failures / 0 errors / 0 skipped; 0 deoptimisations |
| default group, tree of 18:22Z (before C7 / C8; `work/t3/test-default-3.txt`, 18:23Z; machine jar `8a8ea5644b24`, published while it was queued) | the same 7 tests; run-ahead off, final policy defaults | 7 / 0 failures / 0 errors / 0 skipped |
| tier equivalence (heap digest, ticks, outcome at every unit), `TierEquivalenceTest#files`, production promotion (`mode=warm`, at 200 entries, background), `default,code,casetrees`, tree of 18:00Z (before C7 - C10; `work/t3/tiereq-corpora-2.txt`) | Cedar slice / Init / reachability | identical: 215 blocks, 177 k compiled activations / 811 blocks, 6.36 M / 113 blocks, 1.83 M |
| differential gate against the reference engine through the launcher (`testkit diff --engine machine --launcher work/t3/j3/bin/rlvm-jvm -j 4`), tutorial + bugs, 175 exports, `code,casetrees,tele,t2`, ELAB_AT 1, EVERY block compiled at its first entry (count mode, synchronous), run-ahead ON (`work/t3/gates/diff-tb-at1.txt`) | 1,342 checks | 1,341 compared (1,311 both accept, 30 both reject), **0 differences**; 1 not compared (the machine's eager-reduction decline of `Issue.forallFalse`, tier or no tier) |
| the same harness, reachability + Cedar slice + Init + Std, `--threads 4`, launcher `work/t3/j4/bin/rlvm-jvm` (tree of 18:00Z: before C7 - C9), `default,code,casetrees,t2`, `mode=warm`, at 200 entries (`work/t3/gates/diff-corpora.txt`) | 170,322 checks | 169,936 compared, all both accept, **0 differences**; 386 not compared (eager-reduction declines); Std: 723 blocks installed, 8.55 M compiled activations, 0 deoptimisations |
| the same on the FINAL tree: launcher `work/t3/j7/bin/rlvm-jvm` (C7 - C10 in; machine jar `8a8ea5644b24`, driver `150aed4bf1bf`), `mode=warm`, at 200 entries, `hot=0`, `max=4096` so that many blocks are compiled (`work/t3/gates/diff-corpora-final.txt`, 18:38-18:41Z) | 170,322 checks | 169,936 compared, all both accept, **0 differences**; 386 not compared; Std: 691 blocks installed, 8.79 M compiled activations, 0 failed, 0 deoptimisations |
| **default group on the FINAL tree** (C7 - C10 in; `work/t3/test-default-5.txt`, 18:36Z, machine jar `8a8ea5644b24`; it contains the four tests of the fast group) | the 7 tests | 7 / 0 failures / 0 errors / 0 skipped |

Not covered: the native image under a differential gate (the images `work/t3/n2`, `n3`, `n5` are the
measuring tool only; no `bin/rlvm` of this tree was built); `tele` blocks under the production promotion; 8 workers under the
equivalence test (it is single-session by construction; the launcher gate ran with 4 threads).

A defect of the test tooling found on the way (not of the tier): with background compilation, a second
engine in the same process got nothing compiled, because the compiler threads were still working through
the first engine's queue (T2's `perfTestsBackground` printed "blocks compiled 0" for its second file on this
tree, and so did the first equivalence run for the Cedar slice and Init - rows that proved nothing and are
not in the table). `T2Engine.retire()` (called by `Harness.close`) cancels an engine's queued compilations;
`perfTestsWarmMode` asserts that compiled code ran in each file.

## 9. Open points, requests, next step

1. **Most rows are `WarmMain`, not the launcher.** P7 published kept-engine passes at 18:04Z
   (`-X passes-keep=engines`); one launcher window was taken with them (L1: reachability and full cedar). The
   Cedar slice on the launcher was not steady in 7 passes; Init, Std and the native launcher were not run
   through it. No request of mine is open with anybody.
2. **Not measured**: con-leche, cslib, mathlib, navier-stokes-euler; `tele` blocks under the tier in a timing
   run; the native image at Init / Std / full cedar; the launcher's native `bin/rlvm` with this tree (only the
   measuring tool was built as an image: `work/t3/n5` is the one of the final tree).
3. **OPEN: why the Cedar slice on the JVM is 60 ms per pass slower with TWO compiled blocks** (J9: +4.3%
   at one worker, +30% at eight, CPU time of the workers unchanged at eight). What I know: the two blocks
   are `RULE(List.rec)` (5 instructions, the hottest block of reachability too, where it gains) and
   `RULE(Bool.rec)` (2 instructions), 45 k compiled activations per pass, compiled in 107 ms, 0
   deoptimisations of the roots; it is not the retention of machines (C7) and not the report lock (C8):
   the row was +27% before them. JFR of a slice run with 96 blocks (`work/t3/jfr/t3-cedarslice.top.txt`,
   open host): the hook's own frames (`Tier.offer` 1.0%, `T2Tier.run` 0.5%, `OptimizedCallTarget.doInvoke`
   0.5%) are about 2% of the samples - the path `Code.go -> Tier.exec -> Tier.offer -> T2Tier.run` that EVERY
   activation takes once the switch is on, compiled or not - and the host's C2 compiler thread still has
   9% of the thread load in late passes. In the image the same workload is neutral with the same two
   blocks (N5) and +1.0% with 88 (N3). So part of the one-worker figure may be the price of the hook on a
   workload with a million stepped activations per pass; the rest, and the 8-worker figure, is not
   explained. First things to try: the run with `rlvm.t2.max=0` on the final tree (the hook alone);
   `-XX:+PrintCompilation` for `Code.resumeOne` (the body that `BUNREAD` of `RULE(List.rec)` runs through;
   only the tier calls it, 45 k times per pass); the driver's per-unit times (`-X unit-trace`) for the unit
   on the critical path.
4. **`rlvm.t2.inline=1` against 0**: 1021 ms (two runs) against 974 (one run), reachability, JVM, window J3:
   no difference that the per-process variance (8% between two K1 runs of that window) lets one see. Kept at 1
   because it removes calls and never showed a loss; the image is built with 1.
5. **The tier follows the machine by hand.** `Instr.*` mirrors `Code.java` statement by statement; the stepper
   answers `KCONST` of a level-polymorphic constant from the site's inline cache (NOTES-M7: "since wave 6"),
   T2's node did not (C3). The tier-equivalence tests do not see such a drift, because the values are the
   same. As long as `rlvm-machine` is republished every few minutes, an owner of this module has to diff
   `Code.java` against `Instr.java` before every measurement.
6. **Internal API**: as NOTES-T2 section 7.8 (`OptimizedCallTarget.compile / isValid / callIndirect /
   cancelCompilation`, the compilation listener; the "no one-store setter, no one-call forwarder in compiled
   code" rule of native-image CE 25.5.5.0.3).

**Next step, if anybody continues** (as written at 18:58Z): not more work inside this module. U1 of section
7 in the machine (a call instruction that does not build the redex; M7 / M3), measured in the stepper first
- it should pay there by itself - and then direct calls between compiled blocks on top of it, which is the
first point at which Truffle can do something the stepper cannot. **Done in the second task (section 10):
it does not pay there, and nothing is left for the tier on top of it.**

## 10. U1: the CALL instruction (second task, 19:00Z - 20:40Z): NO-GO in the stepper, nothing for `t2` on top

Proposed ISA section: `work/t3/isa-call.md` (design sections 1-8 written before the prototype, section 9
after it). Prototype: private tree `work/t3/tree-call/tree` (rlvm-machine + a copy of this module), switch
`call` (bit 26, off by default); patches per owner in `work/t3/patches-call/` (README.txt there). Nothing
of it is in the main tree and nothing in this module's sources.

### 10.1 Verdict

**U1 is a NO-GO: the instruction is correct, gated, and worth nothing measurable** - in the stepper and in
the Truffle tier, on the JVM and in the native image, on reachability and on Cedar-level work.

- The form I proposed (the application is never built: "form U") saves 1.9% of reachability's records and
  costs 8.3% more ticks and 21% more block activations, because 77% of the applications at CALL sites were
  answered by the WHNF memo of their record. On library corpora it saves 0.1-0.8% of the records at equal
  ticks.
- The form that keeps the application as the memo key ("form T", the prototype's default) changes no
  count by more than 0.3% on any corpus.
- Warm wall clock (bench/quiet.py, one engine kept): no row of either form is outside the scatter between
  two processes of the same configuration (10.4).
- For the tier there is nothing on top: `t2` and `t2 + call` are the same within the scatter, and the direct
  call node the task asked for was NOT built, because its object was counted first: 68 k calls per pass on
  reachability have a compiled caller and a compiled callee (4.5% of the compiled activations), none on
  the Cedar slice (10.5).

### 10.2 What was built

- **Elaboration** (`Elab`, switch `call` only): the major premise of an inlined case tree whose term is an
  application of a definition becomes `KCONST ; CALL r, h, args ; WHNF rM, r` instead of `KCONST ; MKAPP ;
  WHNF`; an application of a definition in tail position becomes `TAILAPPLY h, args` instead of `MKAPP ; RET`
  (the same record at the same ticks in value mode).
- **`CALLV`** (`Code.callLoop`, shared by stepper and tier): one tick per head; the head's RULES block (case
  tree) or BODY block is entered directly on the arguments in scratch, in CALL MODE (marker in the window
  word of the captured frame); in call mode a `TAILAPPLY` of a constant hands head and arguments back
  (`Op.TCALL`) and the loop continues with the next head; over-application of a constant result is again a
  call; a suspended block leaves a frame `K_CALL` that re-enters the loop.
- **The generic path** (the application is built and the complete WHNF runs on it), exactly: head not a
  CONST record; head class not DEFN (constructors, axioms, opaque constants, recursors, Quot); a Nat
  function; a wrong number of levels or "does not unfold"; no RULES and no BODY block or fewer arguments
  than the block's arity; eager reduction active. Theorem heads are called (class DEFN); hints do not
  matter (CALL exists only where WHNF is asked; lazy delta keeps single DELTA steps on records); budgets
  are kept by the tick per iteration; K and literals are CORE's and NATFOLD's in the WHNF that follows.
- **Two forms** (a static of `Code`, one per JVM). Form U (`-Drlvm.t3.callUntabled=true`): `CALL` is `CALLV`,
  the application exists only if the call is stuck. Form T (default): the application is interned first as
  the memo key; a WHNF flag or a WHNF memo answers; else `CALLV`, then the complete WHNF of its result,
  which is written into the application's WHNF memo (frames `K_CALLT_1`, `K_CALLT_2`).
- **Tier** (private copy of this module): `Op.CALL` is decoded like `APPLY`; `Instr.Apply` calls
  `T2Bridge.callv` (a boundary into `Code.callTabled` / `Code.callv`) and, for a `TAILAPPLY` of a constant in
  an activation in call mode, `T2Bridge.tailCall`. No direct call node (10.5).
- **Counters**: `call.entered / memo / tail / builtAtSite / builtAfterTail` (always), the census of block
  instructions and of record-table probes (`-Drlvm.t3.census=true`), CALLs in compiled code and where the
  callee runs (`-Drlvm.t3.callCount=true`); `WarmMain` prints them per pass.

### 10.3 Counts per warm pass (one worker; off / form T / form U)

| corpus | ticks | records built | record-table probes | block activations | blocks entered by CALL / answered by the memo |
|---|--:|--:|--:|--:|--:|
| reachability | 9,095,141 / 9,094,072 / **9,849,776 (+8.3%)** | 4,819,810 / 4,818,323 / 4,727,624 (-1.9%) | 8,369,951 / 8,391,415 / 8,650,323 (+3.4%) | 1,755,468 / 1,755,477 / 2,128,006 (+21%) | T: 92,151 / 382,961; U: 464,637 / - |
| Cedar slice | 9,772,239 / 9,765,614 / 9,777,052 | 5,031,495 / 5,029,993 / 5,025,412 (-0.12%) | 10,924,782 / 10,923,479 / 10,920,964 | 597,341 / 597,372 / 601,758 | T: 6,700 / 2,275; U: 9,197 / - |
| Init | 39,101,002 / 39,001,150 / 39,070,324 | 16,194,242 / 16,154,662 (-0.24%) / 16,080,771 (-0.70%) | 33,213,640 / 33,175,585 / 33,117,125 (-0.29%) | 4,310,644 / 4,312,342 / 4,347,413 | T: 106,605 / 29,626; U: 136,032 / - |
| Std (4 workers) | 64,816,271 / 64,748,034 / 64,690,161 | 28,212,804 / 28,184,641 / 27,986,777 (-0.80%) | - | 6,605,897 / 6,623,541 / 6,649,179 | T: 147,422 / 38,860; U: 185,000 / - |
| full cedar (4 workers) | 84,572,859 / 84,471,043 / 84,472,532 | 38,622,220 / 38,599,907 / 38,406,381 (-0.56%) | - | 7,750,183 / 7,757,163 / 7,795,745 | T: 155,505 / 43,955; U: 198,267 / - |

JVM and native image give the same counts; so do the stepper and the tier with form T on reachability
under the production policy (13 blocks, 1.53 M compiled activations per pass: ticks 9,094,072, records
4,818,323, run words 24,417,655 in both). With 4 workers the split of the units over the sessions moves the
counts by up to 1% from run to run; only the one-worker rows are exact.

**Census of what block instructions build** (switch off, `-Drlvm.t3.census=true`; new record / existing
record found, per pass):

| corpus | records per pass | `MKAPP` | `MKLAM` + `MKPI` + `MKSUSP` | `MKPROJ` | share of the records made by block instructions | `WHNF` instructions: flag / memo / computed |
|---|--:|--:|--:|--:|--:|--:|
| reachability | 4,819,810 | 2,274,137 / 2,114,595 | 706,202 / 922,985 | 408,525 / 65,382 | 70% | 536,216 / 387,018 / 378,619 |
| Cedar slice | 5,031,495 | 387,400 / 213,341 | 293,107 / 709,494 | 30,218 / 53,723 | 14% | 70,059 / 23,975 / 17,117 |
| Init | 16,194,242 | 3,389,093 / 1,796,109 | 944,506 / 1,254,409 | 331,269 / 895,017 | 29% | 412,820 / 283,317 / 207,742 |

Reading: the applications that cost are not redexes handed to WHNF but ARGUMENTS and RESULTS (they are
stored in other records and frames at once); half of reachability's `MKAPP` executions and most closure
constructions find an existing record; on Cedar-level work six of seven records are made by the generic
judgments, where no block instruction reaches. This is the table U2 needs (sent 19:32Z).

@@TIMES@@
