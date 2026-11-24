# NOTES-T3 - the Truffle tier after T2 (owner: T3, 2026-11-24)

Continues `NOTES-T2.md` (what the tier is, its deviations from ISA 8.4, its correctness record). This file:
the measurements T2 could not take, what the tier costs and gains on each platform and why, what was
changed, and the go / no-go. Scratch, scripts, logs and raw rows: `work/t3/` (`warm.tsv`, `logs/`).

SECTIONS 1 AND 7 ARE WRITTEN LAST - see the end of the file for the state of the work if they are missing.

## 2. How the numbers were taken

- **Tool**: `rlvm.truffle.WarmMain` (test tree): one process, ONE machine engine kept over all passes; pass 1
  cold with the environment pass, every later pass checks all units again with fresh sessions of the same
  engine on W worker threads (chunks of 16 units from a shared counter). This is what "warm" has to mean for
  this tier: the launcher's `-X passes=N -X passes-mode=recheck` builds new engines per pass (T2's finding
  of 16:20Z; request to P7 open), so no launcher run can credit a code store or compiled blocks yet. Not
  the driver: no admission pipeline, no re-check, no pipelined loading.
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
4. **The JVM is the faster platform in steady state, tier or no tier**: K1 on reachability 1.03-1.05 s (JVM)
   against 1.40 s (native), on the Cedar slice 1.44 s against 1.84 s (one worker): 22-26%.

## 5. Where the time of a block goes (why removing the dispatch buys so little)

JFR execution samples of warm K1 passes on the JVM (one worker; open host, so SHARES only;
`work/t3/jfr/*.top.txt`):

| | reachability | Cedar slice |
|---|--:|--:|
| inside code blocks, inclusive (`Code.enter`) | 83% | 14% |
| the stepper's own code (`Code.*` self: dispatch, operand decoding, register traffic, `go`) | 16.8% | 6.9% |
| interning called by block instructions, inclusive: `Values.mkApp` / `Values.closure` + `Runs.frame` / `Values.mkProj` | 47% / 14% / 4% | 7% / 16% / 0.2% |
| self time of the probes and inserts (`Values.neu`, `closureProbe`, `Runs.internRun`, `Values.rec`, `neuExt`, `Machine.addRecordAt`, `growVtab`, `growRtab`) | 57% | 27% |
| generic judgments outside blocks (`Infer.check` inclusive) | - | 59% |

A compiled block calls the same operations as the stepper (one semantics, the same records in the same
order - the tier-equivalence rule). What partial evaluation can remove is the second row; the third and
fourth rows are identical work in both tiers. That bounds the gain of ANY compilation of single blocks at
17% on reachability and 7% on the Cedar slice before its own entry costs; measured: 2-5%.

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
