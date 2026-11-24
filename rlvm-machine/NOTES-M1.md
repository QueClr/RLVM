# NOTES-M1 - values, runs, literals, levels, scope (owner: M1)

What M1 delivers, the deviations from `docs/design/ISA.md` (revision 2) with their reasons, what callers
have to know, and what is not done. Files: `Values`, `Runs`, `Lits`, `Levels`, `Scope` (main tree);
`M1ValuesTest`, `M1RunsTest`, `M1LitsTest`, `M1ScopeTest`, `M1ProbeBench`, `src/test/resources/m1/` (tests).

## 1. Deviations

| # | Where | Deviation | Reason |
|---|---|---|---|
| D1 | `Values.mkSusp`, ISA 2.2 / 2.5 | A SUSP made by a `let` is a **different record** from the plain SUSP of the same `(term, frame, ls)`: its aux is 1 (`Values.SUSP_LET`), so aux is part of the key of SUSP as it is of NBIG and CONST. ISA 2.5 lists the key as `(kind, a, b, c)` | ISA 2.5 I-FLAG: flags are functions of identity, and LET (with the OPEN it implies) is a flag. With one record, a tabling record that some `let` later also makes would change its flags after other values had already inherited "not OPEN" from it. Cost: one extra record per let value; forcing the LET record evaluates through the plain record's tabling, so nothing is evaluated twice. `Values.suspRecord(m, term, frame, ls)` is the plain record itself, never its VAL (the tabling record of ISA 5.1; asked for by M5 and M2) |
| D2 | `Runs`, ISA 1.6 run header | Bit 3 of a run header (`Runs.RUN_OPEN`, one of the five reserved zero bits) = some slot of the run, or its REST run, holds a value with flag OPEN. Written when the run is made; a function of the content, not compared by the probe | OPEN of a closure or suspension is the "or" over its frame (ISA 2.3). v0 tables every non-leaf node, i.e. makes one SUSP record per visited node over the same few frames; without the bit every such record would walk its frame and load every slot's record. With it: one load of the header. Adopted by M6 as `Layout.RUN_OPEN` (08:47Z; `TestTables.internRun` and `Show` know it) |
| D3 | `Values.mkNat`, `Lits.natHandle`, ISA 2.2 NBIG | An NBIG over a NatStore id has words `b` = the store's content hash of the number and `c` = its limb count (ISA: unused). `Lits.natHandle` verifies them and throws `IllegalStateException` (-> DECLINE, ISA 14.1) if the id no longer names that number | `Machine.nats` is the store inside `rlvm.api.bind.NatBinding`, which that class treats as a cache and empties (`NatStore.reset`) after its own operations and at `NatOps.reset()`. Ids are then reissued to other numbers. The driver gives the reference kernel its own context, so this cannot happen in a run; it can with a shared context (fixtures, `DiffEngine` without an oracle context, `MachineOps`) and warm sessions. A reissued id must never read as another literal (a wrong ACCEPT). Both words are functions of the number, so identity is unchanged. Proper fix requested from M6 (an overlay owned by the machine) |
| D4 | `Values.mkApp` / `nsucc`, ISA 4.2 MKAPP, 2.9 | `Nat.succ x` (the constant NAMED `Nat.succ`, no levels, exactly one argument): guard of `Nat.succ` VALID -> `nsucc`; INVALID -> `fault(E_PRELUDE)` **at construction**; ABSENT (never evaluated: hand-built stores) -> an ordinary NEU. `nsucc(Nat.zero)` = literal 1 needs the guard of `Nat.zero` VALID (INVALID: `E_PRELUDE`; ABSENT: `Nat.zero` is an ordinary base) | ISA 2.9: INVALID is `fault(E_PRELUDE)`; ABSENT means the extension does not fire. The reference kernel declines later, at the moment the extension would be used (`requireValid`); declining at construction is stricter (a file whose `Nat.succ` has a non-standard shape is declined as soon as it applies it to one argument) and keeps one invariant for M3 / M4: **with a VALID guard `Nat.succ x` is never a NEU**. No published test has an INVALID `Nat.succ` that is applied (to be confirmed by the gate runs; a difference would show as a machine decline, never as a wrong verdict) |
| D5 | `Values.mkConst`, ISA 2.2 CONST aux, 2.3 | The nat-op code is the `rlvm.nat.NatOp` code (SUCC 1 .. SHIFT_RIGHT 15) of a constant that is bound to one of the 15 special NAMES and has **no levels**; it does not depend on the guard state. A constant with the code of one of the 14 binary functions is ACTIVE and never WHNF, whatever its declaration kind | The official test is `f == Const Nat.add []` by name, before delta (R02 7.6), so the code must not depend on the kind; the guard can only be judged where the extension fires (M3: `m.specials.state`). WHNF must not short-cut `reduce_nat` (M3's request of 08:27Z) |
| D6 | `Values.mkConst`, ISA 2.7 | Word c of a CONST whose number of levels is not the row's number of parameters is **-1** (`mkSubst`'s arity error) and the record exists | ISA 4.3.2 needs such a head as a value ("#levels != #lparams: does not unfold", R02 section 10 row 14). `Levels.lsub(.., -1)` is `fail(E_LEVEL_ARITY)`, so nothing is ever instantiated with it |
| D7 | `Values.closure`, ISA 2.7 | `ls' = HAS_LPARAM(term) ? ls : 0` is applied **inside** `mkLam / mkPi / mkPii / mkSusp / suspRecord` (one header load of the term when `ls != 0`) | I-ID must not depend on every caller remembering the rule; a caller that probes for a record itself must apply it too |
| D8 | `Runs.prune`, ISA 2.4 | (a) A mask with bit 63 on an environment of at most 63 slots gives the near frame of its low bits (the bit is dropped); (b) a far frame always has exactly 63 near slots; (c) a set bit at or beyond the environment's length is `fault(E_INTERNAL)` | (a) no index >= 63 can be read there (GET would fault), and the saturated summaries of the store can set the bit; (b) keeps `FGET` and `ENTER` one formula; (c) ISA 2.4 "GET is bounds-checked in every build": the same for the read that PRUNE does |
| D9 | `Values`, `Runs`, `Levels`: signals as operands | A constructor that receives a signal (>= `Op.SIG`) as a frame, level, level list, struct or base returns it unchanged; `Levels.lsub / lsubList` return `ABORT` for `ls = -1` in place of a level id | Lets callers compose (`mkLam(m, t, prune(..), ls)`, `mkSort(m, lsub(..))`) with one test at the end; ids and refs never reach `Op.SIG` (H5) |
| D10 | `Values.mkNat` | Accepts the error handles of `rlvm.nat`: `TOO_BIG`, `COUNT_TOO_BIG` -> `fail(E_NATSIZE)`; `NONE`, `MALFORMED` -> `fault(E_INTERNAL)` | so that `Values.mkNat(m, Nat.add(m.nats, a, b))` is the whole NATOP; the official kernel throws at exactly these results (R02 7.6) |
| D11 | `Values.mkProj`, ISA 4.2 | The constructor must be a constructor **of the structure** (`ctorInduct(head) == structRow`); the official `reduce_proj_core` does not look at the type | as ISA 4.2 / 4.3.3 write it. On a checked term the two agree (the struct of a checked projection has the structure's type). On an unchecked foreign body the cell stays stuck, which cannot turn into an ACCEPT |
| D12 | `Levels.importLevel`, `encodeLevel`, `decodeLevel` (step 2) | Implemented with an encoding M1 chose: a POOL level ref's payload is an offset from the record's first word to `[n, n postfix words]`; `importLevel(m, record, slot)` reads `FactStore.chunkOf(record)[offsetOf(record) + slot]` | ISA 3.3 / 3.5 give the postfix alphabet but not the framing. Unit-tested for the codec round trip (`M1LevelsTest`); M5's `Facts` writes its records with `encodeLevel` and reads them with `importLevel` (`M5FactsTest`: `slvl` / `rlvl` of `Eq`, `Eq.refl` through the real `FactStore`), M7's `Cong` reads through it too |
| D14 | `Runs`, ISA 2.4 "REST run" | The REST bindings of a far frame are a **chain of chunks**: the outer bindings are cut from the outermost into REST runs of 64 slots plus a shorter tail; each chunk but the first has the far bit and its predecessor's id in the REST word; the id of the last chunk is the id of the sequence and is what the far frame holds. ISA 2.4 has one REST run with all the bindings | One run per far PRUNE is O(depth) words for every distinct depth: `perf/beta-ladder` (2,000 nested redexes) made 4.03 M run words (16 MB, the whole session cap) for 18,017 records; with chunks 0.41 M. Identity is unchanged (equal sequences give equal chains by induction; a difference in any chunk changes every later id). `Runs.rest(frame)` is the last chunk, `Runs.restLen` / `envLen` walk the chain, `Runs.enter` copies it; `Debug.scopeAudit` and `Show` follow the far bit generically and need no change. Time per far PRUNE stays O(depth) (hash and compare of every outer binding) |
| D15 | `Values.Ext.openBits`, `Values.openOf`; ISA 2.3 | A second copy of flag OPEN: two bits per record (known, OPEN) in a `long[]` of `Values.Ext`, written when `Values` makes a record, cleared at reset in O(used / 32), counted in the footprint (1/128 of the records). `openOf(v)` answers from it and falls back to the record's own flag when there is no entry (a record made by other code through `Machine.internRecord`). The flag in the record stays the definition (I-FLAG); nothing else reads the table | "OPEN = or over the components" is evaluated once per new frame and per new application, over values scattered through `vh`; section 3.2 |
| D13 | `Runs.get` | calls `Debug.scopeAudit` when `DBG_SCOPEAUDIT` is on | M6's request of 08:25Z (ISA 2.8: I-FV3 audited at every GET) |
| D16 | `Runs`, ISA 1.6 run header | Bits 4..7 of the header of a FRAME or REST run (`Layout.RUN_CNT_SHIFT / RUN_CNT_MASK`, adopted by M6 at 14:00Z) = the number of slots that are not HOLE, saturating at 15. A function of the content, not part of the identity (`Layout.RUN_DERIVED` = OPEN + count is what a comparison of headers masks) | It makes a probe that looks only at the KEPT slots exact: a candidate of the same length whose kept positions agree with the environment and whose count equals the number of kept bindings that are not HOLE has nothing but HOLE elsewhere. A saturated count proves nothing: such a candidate (15 and more bindings) is compared slot by slot. Section 5.2 |
| D17 | `Runs`, `Values`; ISA 13.3 `Hash.seed / step / run / neu` | The content hash of FRAME and REST runs and the hash of a NEU are private to `Runs` / `Values`: `Hash.fin` of the SUM over the slots that are not HOLE (over the arguments) of `mix((v + i * C1) * C2)`, `i` = de Bruijn index (argument position), plus the REST run / the head. `Hash.seed / step / run / neu` are no longer used by M1 (they remain for `TestTables`, whose runs are self-consistent and must not be mixed with `Runs`' in one session). `Machine.growRtab / growVtab / reset` read the stored hash words and are unaffected. The hash word of a stand-alone spine (`Runs.spine`) is 0 | PRUNE can hash the kept slots alone, and no multiplication waits for the previous one (the chained `Hash.step` was one dependent multiplication per slot of the frame's LENGTH: the hottest loop of the machine). Probe-sequence lengths on Init and the Cedar slice are those of the old hash within 0.1% (section 5.2) |
| D18 | `Levels.lvlEq / listsEq`, ISA 4.4 LVLEQ | Levels are compared as the OFFICIAL kernel compares them by default: `a == b \|\| LevelOps.isEquivOfficial(a, b)` (identity of the official normal forms; sound, incomplete), lists pairwise. The complete relation (`LevelOps.isEquiv / listsEquiv`) only with the switch `levels-complete` (`Op.SW_LEVELS_COMPLETE`, never in strict mode) | Owner decision of 2026-11-24 (OWNER-NOTES item 11; orchestrator's request of 13:40Z): both engines resemble the official kernel by default; the arena's `corner-cases/imax-right-successor` and `imax-nested-absorption` are rejected. Same code as `RefKernel.levelEquiv / levelsEquiv` with `officialLevels` |
| D19 | `Runs.restOf`, ISA 2.4 | A far PRUNE finds its REST chain through a direct-mapped cache of 64 cells `(eb, n, epoch) -> chain` in `Values.Ext`; a cell is used only after the chain it names was compared with the environment slot by slot (`Arrays.equals` per chunk) | Every far PRUNE under one deep environment needs the same chain; finding it by content hashed and compared every outer binding again (Cedar slice: 2.35 M far PRUNEs, 102 M slots). The cell is a hint verified by content, so a slab that was cut and rewritten costs a comparison and nothing else (no invariant about who writes `es`) |
| D20 | `Values.mkLamAt / mkPiAt / suspRecordAt / mkSuspAt` (new), ISA 4.2 MKLAM / MKPI / MKSUSP | The closure or suspension of a term over a working environment in ONE operation: exactly the record of `mkX(m, t, Runs.prune(m, eb, en, fvMask(t)), ls)`, but the vtab slot of the record that a NEW frame would give is loaded before rtab is probed (the id of a new frame is the top of the run slab; a record over a frame that did not exist cannot exist). A 256-word table of recently looked-up frame hashes (`Values.Ext.seen`) only predicts "exists, do not speculate" | Section 5.4: in large sessions the load of the table slot IS the probe (79% of PRUNE on reachability), and the two probes of a closure wait for each other. Identity is unchanged (`M1ValuesTest.closuresOverAnEnvironmentAreTheTwoStepRecords`). `Eval.closure / table / suspend` call it since M2's publication of 16:48:45Z; `Infer`'s five PRUNE + constructor pairs (M5) do not yet |

Not deviations, but choices the ISA leaves open:

- **CHEAD** (ISA 1.5 "direct array for the empty and the identity list, else the intern table") is one
  direct-mapped cache of 2^12 cells `(row, level list, epoch, record)` in `Values.Ext` (64 KiB), in front of
  the table. A per-row array would be 4 bytes per constant per session (2.4 MiB on mathlib) and needs a
  used-list for the O(used) reset.
- **Sort cache**: 64 pairs `(level id, record)`, cleared at reset (128 words).
- **Head classes**: axiom, opaque, `Quot`, `Quot.mk` -> AXIOM; definition, theorem -> DEFN (a theorem has
  hint class OPAQUE: it unfolds in `whnf`, R02 7.6, and counts as opaque in lazy delta, R02 8.4); inductive
  -> IND; constructor -> CTOR; recursor -> REC; `Quot.lift`, `Quot.ind` -> QUOT. `Quot.mk` is not CTOR
  because the constructor side tables (`ctorInduct`, `ctorNumParams`) do not exist for it.
- **NSUC flags**: WHNF and OPEN are copied from the base when the cell is made; an NSUC over a SUSP or a
  reducible application has no WHNF flag (RECONCILIATION section 2 "Nat.succ"). PROOF is left unknown (the
  static "NSUC is never a proof" belongs to stage 2, B7).
- **Visibility** (`E_VIS`) is tested when a CONST record is made (a CHEAD miss): `rank(row) < m.decl`.

## 2. What callers have to know

- Every operation returns a value ref / run id / level id, or a signal (`>= Op.SIG`, never SUSP); none throws
  for a property of its operands. The only exception thrown on purpose is `Lits.natHandle` on a stale
  NatStore id (D3). `Runs.push` / `enter` return -1 when `es` is full (the caller faults `E_MEM`), as the
  skeleton's signatures say.
- `Values.mkApp` applies the canonical forms (extends a NEU, `Nat.succ`); `Values.mkNeu` does not: it is the
  raw record of a head and arguments. Neither reduces. A LAM or SUSP head is `fault(E_INTERNAL)` (apply or
  force it), a sort / Pi / literal / NSUC head `fault(E_ILLTYPED)`.
- `Values.mkSusp(.., false)` returns the VAL when it is known (ISA 4.2); `Values.suspRecord` never does.
- `Values.nsuccK(m, v, k)`, `npred`, `npeel` are the successor views (IOTA field, OFFSET, the fold `n + k`).
- A record that another package makes itself (`Machine.internRecord`) is correct but has no entry in the OPEN
  bit table (D15): prefer the constructors of `Values`.
- A frame given to a closure must be a FRAME run made by `Runs.prune` / `Runs.frame` (or 0); the OPEN flag
  of the closure is the frame's header bit.
- `Runs.len(frame)` of a far frame is 63; `Runs.envLen(frame)` is the number of bindings `enter` produces.
- Never keep `m.vh`, `m.rs`, `m.es`, `m.X` in a local across a constructor call: they may be reallocated.
- A session reset must reset the worker context's level overlay and NatStore together with the machine
  (the driver does): substitution ids (word c of CONST, `ls` of closures) and NatStore ids live there.

## 3. Costs (measured)

### 3.1 Probe cost (ISA section 11: "ns and instructions per mkApp hit; target at most 60 instructions")

`src/test/resources/m1/probe-cost.sh` runs `M1ProbeBench` twice per mode (N1 = 2e8, N2 = 8e8 iterations)
under `perf stat` in the arena-shaped container, under the ladder lock, and reports the differences per
operation, net of the bare loop. Graal JIT, SerialGC, one thread, 4096 existing values, the session tables
of ISA 1.6 (vtab 2 MiB); table of 2026-11-24T10:47:57Z, the published code (`work/status/machine-M1-probe.txt`;
`probe-cost.sh --out` rewrites it; a first run at 10:08Z, before the OPEN bit table, agrees within 0.3 ns
and 12 instructions on every row):

| Operation (hit unless said) | CPU ns | Instructions |
|---|--:|--:|
| `mkApp`, 1 / 2 / 3 arguments (head a variable) | 4.0 / 5.4 / 7.2 | 96 / 114 / 145 |
| `mkApp` extending a 1-argument NEU by 2 | 11.4 | 202 |
| `mkLam`: the fixed-size record probe | 3.8 | 67 |
| the same with a 3-multiplication hash (experiment `lamx`) | 2.3 | 46 |
| `mkLam` in a dependent chain (each key depends on the previous result) | 10.7 | 61 |
| the same with the 3-multiplication hash | 8.5 | 48 |
| `Runs.prune`, 3 slots | 4.9 | 71 |
| `prune` + `mkLam` (closure over a 3-slot environment; two dependent probes) | 11.6 | 143 |
| `mkSort` through the sort cache | 0.9 | 23 |
| `mkApp` of a NEW 2-argument application, sessions of 2^17 values + the O(used) reset | 16.1 | 198 |
| the same in sessions of 1,024 values (the v0 regime) | 8.6 | 201 |

**The target of 60 instructions is not met for `mkApp`** (96 with one argument); the fixed-size probe is at
67. What the instructions are: the frozen `Hash.record` / `Hash.neu` (6 multiplications and their mixing:
about 21 for a record, n + 5 multiplications for a NEU), the head dispatch of `mkApp` (about 15), the
probe (about 25: table word, tag, three key words, each with a bounds check), one hash step and one
comparison per argument (about 13 each, plus loop set-up). The spike's 38 / 45 were a binary application
keyed by two words with a 3-multiplication hash; with that hash our fixed-size probe is at 46 (row
`lamx`), and 39% faster. The hash is a shared frozen definition: proposed to M6 with these numbers
(requests.log 10:50Z), not changed. Instruction counts on the JVM move by about 10 per operation between
runs (the JIT's own work is in the process total); C2 gives 127 / 150 / 227 for `mkApp` with 1 / 2 / 3
arguments and 50-70 for the fixed-size probe. No native-image measurement was made.

### 3.2 Where M1's time goes on Init (the whole machine, `-X engine=machine`, one worker)

JFR samples of the worker thread (launcher run, host under load; 1,000-1,600 samples per run): `Runs` 23%,
`Values` 18-30%, `Machine.reset` + `addRecordAt` 7%: **about half of the worker's CPU is constructing and
interning values and frames**, as `R13` predicted for a hash-consing machine. Counts on Init (a sandbox
build with counters): `prune` 47.2 M calls, 19.0 M new frames (6.0 slots on average, 13 K far frames);
closures and suspensions 58.6 M probes, 30.0 M new; NEU 18.0 M probes, 15.8 M new (3.3 arguments);
`enter` 22.4 M (2.1 slots); `push` 24.6 M, none of them a copy; steps beyond the first slot of a probe
sequence: 1.7% (records), 3% (NEU).

Three findings from it:

- The first profile had ONE line at 17.5% of the worker: the loop that loaded the record header of every
  kept slot of a new frame to compute the frame's OPEN bit (and the same loop over the arguments of a new
  NEU, 9%). Now: only kept slots, innermost first, stop at the first OPEN one, and the test reads a dense
  bit table (`Values.openOf`: two bits per record made by `Values`, 1/128 of the records' size; a record
  made by other code is read from `vh`). A/B on Init, thread CPU of `checkAll`, alternated: header loads
  12.1 s and 12.6 s; bit table 10.7 s and 12.1 s. The host was under a load average above 100, so this is
  an indication (about -6%), not a number.
- **The base table sizes cost about 20% on Init in the v0 regime** (session = one declaration: 57,937
  resets, 944 records per session): standard sizes 10.9 s and 12.8 s, base sizes of 2^12 / 2^11 / 2^11 /
  2^13 (vtab / rtab / records / run words) 9.1 s and 9.6 s. `Machine.Sizes` is M6's: requested with these
  numbers (requests.log 10:50Z).
- The tables are not clustered (the percentages above), so the remaining cost of a probe is its memory
  traffic and the hash, not the probe sequence.

**The measurement item of ISA 2.4 (C2.12), on Init**: frame length (highest read index + 1) against the
number of read slots. Over the 47.2 M `prune` calls: 282.2 M slots of length, 134.0 M kept: ratio 2.11;
over the 19.0 M frames actually stored: 99.8 M against 65.0 M: ratio 1.54 (5.3 slots, 3.4 kept). Lengths:
1: 5.4 M, 2: 6.2 M, 3: 5.1 M, 4: 7.1 M, 5-8: 15.0 M, 9-16: 6.0 M, 17-32: 2.0 M, 33-63: 0.37 M. Calls by
floor(length / kept): 1: 26.1 M, 2: 10.5 M, 3: 3.8 M, 4: 2.2 M, 5: 1.1 M, 6: 0.8 M, 7 and more: 2.7 M.
Reading: the ISA's threshold for sparse frames ("above 2") is reached on calls and not on stored frames.
Sparse storage `(mask, kept values)` would save no memory (the mask costs two words where 1.8 HOLE words
are saved per frame) and about half of the slot iterations of a PRUNE's hash and comparison. Not done.

## 5. Wave 6 (2026-11-24): the value store measured and reworked

Tools: `work/m1/mkdist.sh TAG [--native] [--tree DIR]` (a private distribution: the pipeline jars of the integrator's
distribution + the machine jar of a tree; nothing is written to the main tree), `work/m1/m.py` + `work/m1/batch.sh`
(the integrator's `m.py`: one row per run in `work/m1/measure.tsv`, a batch under ONE hold of the measurement lock,
distributions interleaved), `work/m1/q.py` (the same run WITHOUT the lock on CPU 18, an X925 core outside the arena
cpuset: instruction counts are exact there, CPU time is indicative; `work/m1/quick.tsv`), `work/m1/perf/` (perf
record + annotate of a native image with symbols), `Runs.Stat` (counters; a compile-time constant switches them
on), `work/m1/fake-eval.py` (the local fake of `Eval` for D20). Everything below is the CE native image `-O3`,
one worker, v0, unless said.

### 5.1 Distributions (instrumented JVM run, one worker, v0; `work/m1/stat/*.txt`)

| | Init | Cedar slice | reachability |
|---|--:|--:|--:|
| PRUNE calls (found / new frame) | 47.2 M (28.2 / 19.0) | 14.3 M (10.1 / 4.19) | 21.1 M (8.4 / 12.6) |
| mean frame length / kept slots, per call | 5.98 / 2.84 | 22.1 / 3.13 | 4.97 / 3.19 |
| the same over new frames | 5.25 / 3.42 | 19.4 / 4.63 | 4.90 / 3.76 |
| far PRUNEs (outer bindings re-hashed, before D19) | 13 K (0.2 M) | 2.35 M = 16% (102.3 M slots, 43.6 each) | 0 |
| calls by kept slots: 1 / 2 / 3 / 4 / 5 and more | 27 / 27 / 18 / 12 / 16 % | 30 / 28 / 16 / 9 / 17 % | 20 / 19 / 19 / 15 / 27 % |
| closure and suspension probes (new) | 58.6 M (30.0) | 17.9 M (7.65) | 23.9 M (14.3) |
| of them SUSP / LAM / PI / PII | 74 / 20 / 5 / 1 % | 67 / 15 / 14 / 4 % | 61 / 38 / 1 / 0 % |
| by kept slots of their frame: 0 / 1 / 2 / 3 / 4 / more / far | 19 / 22 / 22 / 14 / 10 / 13 / 0 % | 18 / 22 / 20 / 11 / 6 / 9 / 14 % | 12 / 17 / 17 / 17 / 13 / 24 / 0 % |
| NEU probes (new) | 19.7 M (15.8) | 3.1 M (1.98) | 8.2 M (7.9) |
| spine length 1 / 2 / 3 / 4 / 5 / 6 / more | 11 / 24 / 20 / 18 / 12 / 12 / 3 % | 12 / 25 / 30 / 12 / 9 / 7 / 5 % | 2 / 11 / 8 / 27 / 32 / 17 / 2 % |
| run words made: frames / spines / REST | 137.8 / 88.6 / 0.02 M | 90.3 / 12.2 / 0.95 M | 87.0 / 51.0 / 0 M |
| run words per declaration | 3,908 | 12,832 | 32,700 |
| ENTER calls (slots copied) | 22.4 M (47.0 M) | 7.2 M (135.3 M) | 9.7 M (19.1 M) |

Readings. (a) A frame is 2.1 times (Init) to 7 times (Cedar slice) longer than what it keeps: everything that
iterates over the LENGTH is wasted work - that was the hash and the comparison of PRUNE (5.2). (b) On the Cedar
slice the far frames are the elephant: 16% of the calls, 15% of the new frames, but 46% of the frame words (66
words each) and, before D19, 102 M re-hashed outer bindings. (c) "PRUNE returns the frame that was just entered"
(same base, same length, the idea of the wave-5 report) is 0.6% of the calls on Init and 1.7% on the Cedar slice:
not worth a test, not built. (d) A direct-mapped cache keyed by (top of the environment, mask) in front of PRUNE,
emptied at every PUSH / ENTER (the only invalidation `Runs` can know without help), would answer 23.5% of the calls
on Init, 17% on the Cedar slice, 10% on reachability; not built, because of what 5.3 found.

### 5.2 Stage A: kept-slot PRUNE, sum hashes, REST cache (published 14:22:54Z; kept)

- PRUNE: the hash is `Hash.fin` of the sum of one independent term per kept binding (D17), the probe compares the
  kept positions and the header count (D16), the miss writes HOLE over the length and then the kept slots. The loop
  runs over the set bits of the mask (`b &= b - 1`), never over the length.
- far PRUNE: the REST chain comes from the verified cache (D19); the near part is hashed like any frame.
- NEU: the same sum hash over the arguments (D17); the two `arraycopy` calls of a new spine are a loop for at most 8
  words (and none for an empty prefix); ENTER of at most 8 slots is a loop.
- CHEAD index: two independent multiplications and the top bits instead of `Hash.cell` (two in sequence + `fin`).
- OPEN bit table: the reset range is `[word of the session's first record, word of the last record made]` (records
  are appended), so `mark` no longer keeps a minimum and a maximum.

Identity: the counters of 5.1 are IDENTICAL before and after (frames found / new, records, run words, ticks, on Init
and the Cedar slice), probe steps beyond the home slot too (frames 6,638,165 -> 6,643,160 on Init, 5,604,412 ->
5,608,534 on the Cedar slice; NEU 4,390,620 -> 4,395,034 and 1,446,145 -> 1,452,037: the sum hash spreads as the
chained one did). Tests: `M1RunsTest.keptSlotProbeKeepsIdentityExactly` (6,000 random environments and masks against
a model keyed by the dense content: HOLE at kept positions, saturated counts, far frames, FRAME over the dense
slots, ENTER round trip, neighbours that must differ), `restCacheIsVerifiedByContent`,
`hashesSpreadConsecutiveIdsAndShiftedPositions`.

Measured, base (the main tree of 13:20Z) against stage A, both built from the same foreign files:

| corpus | instructions G | CPU s / wall s, median of two interleaved runs (locked batch A2, 16:05-16:07Z, load average 58-79) |
|---|--:|---|
| Init | 139.0 -> 131.2 (-5.6%) | 7.99 / 8.08 -> 7.36 / 7.46 (-7.9% CPU) |
| Std | 223.2 -> 209.9 (-6.0%) | 13.40 / 13.51 -> 12.30 / 12.42 (-8.2%) |
| Cedar slice | 47.0 -> 38.8 (-17.4%) | 3.38 / 3.67 -> 2.69 / 2.71 (-20.4%) |
| reachability | 59.4 -> 56.8 (-4.4%) | 8.19 / 8.31 -> 8.12 / 8.24 (-0.9%; the two pairs: 7.98 -> 7.88, 8.39 -> 8.36) |

(A first locked batch at 14:28-14:34Z gave Init 8.18 -> 7.69 s CPU, Std 13.96 -> 12.52, Cedar slice 3.50 -> 2.76,
reachability 8.68 -> 8.38, but it fell into the window in which the host was thrashing; the orchestrator declared the
timings of that window void.) Profile after stage A (`work/m1/perf/*-a2.txt`): `Runs.pruneNear` 11.6% -> 9.0% of the
process on Init, 19.1% -> 11.4% on the Cedar slice (with `sameMasked` 3.7% -> `sameKept` 1.0%, `restChain` + `same`
5.1% -> `sameChain` + `restOf` 3.0%), 23.7% -> 23.6% on reachability.

### 5.3 What the probe costs now: the load of the table slot

`perf annotate` of the three probe functions on reachability (one or two giant declarations, tables of 64-256 MiB):

| function (share of the process) | the load of the table slot | the frame / record load after it |
|---|--:|--:|
| `Runs.pruneNear` (23.6%) | 79% (`rtab[slot]`) | 11% |
| `Eval.table` = the inlined record probe (11.9%) | 76% (`vtab[slot]`) | 14% |
| `Values.neu` (9.7%) | 83% (`vtab[slot]`) | 5% |

About 39% of reachability is waiting for intern-table slots that miss the cache; hashing, comparing and writing
are noise there. On Init (tables of 64 + 128 KiB against a 64 KiB first-level cache) the same load is 37% of
`pruneNear`, and the whole process has 0.8% mispredicted branches (229.6 M) and 0.84% first-level misses (312 M), about
a tenth of the time each. Experiments that follow from it:

| experiment | result | verdict |
|---|---|---|
| smaller base tables (`-Drlvm.machine.sizes`, records / vtab / run words / rtab / pairs / plog), Init CPU s, two runs each | 14,15,16,14,12,10 (default): 7.55 / 7.47; 12,13,14,12,10,8: 8.02 / 7.91; 11,..: 8.13 / 8.13; 10,..: 8.27 / 8.27; Cedar slice 2.62-2.72 in all four | REJECTED (growth costs more than the misses; M6's sizes stand) |
| front cache of recent frames (256 cells `(hash, epoch, frame)`, verified by content) in PRUNE | answers 48% of all frame lookups on Init (81% of the found ones), 51% on the Cedar slice, 68% of the found ones on reachability - and costs: Init 7.45-7.50 -> 7.59 s, 132.1 -> 133.5 G instructions; reachability 8.36-8.47 -> 8.69 | REJECTED |
| the same + front cache of closure / suspension records (1,024 pairs; in the simulation it answers 83% of the records that are found on Init, 76% on the Cedar slice, 88% on reachability) | Init 7.40-7.50 -> 7.63 s, 134.6 G; reachability 8.30-8.45 -> 8.60 | REJECTED |
| PRUNE + record in one operation, the record's slot loaded early ALWAYS | reachability 8.29 -> 8.20 s (8.36 / 8.23 / 8.29 against 8.35 / 8.10 / 8.20) | not enough alone |
| the same, but no early load for a frame whose hash was looked up a moment ago (D20) | reachability 8.29 -> 7.69 s (7.81 / 7.66 / 7.69): -7.2%; Init, Std, Cedar slice within +-0.5% | KEPT as an operation (5.4) |

Why the front caches lose: a lookup that a small recency cache can answer is a lookup whose table slot is still in
the cache - the misses are the NEW keys (60% of the frames, 96% of the applications on reachability) and the old
ones. A cache in front of a table only pays where it replaces work, not where it replaces a load that would have
hit. The same argument retires the (environment, mask) cache of 5.1 (d). And why the early load must be
predicted: when the frame exists, the speculative load fetches a cold line for nothing; the 256 recent hashes
tell "exists" for 68-81% of the found frames at the price of one load and one store.

### 5.4 The combined operation (D20; published 15:42:25Z; called by `Eval` since 16:48:45Z)

`Values.mkLamAt / mkPiAt / suspRecordAt / mkSuspAt(m, t, eb, en, ls)`. Measured and gated BEFORE M2 adopted it,
with a local fake of `Eval` that makes exactly the three calls M2 then published. Gates with the fake
(`work/m1/fake-eval.py`): fast group green, tutorial 395 units and bugs 1,428 units DIFF-CLEAN, the Cedar slice 8,418, reachability 4,413, Init 58,547 and Std 101,146 units DIFF-CLEAN with 0 declines (`work/status/machine-diff-*-M1b.txt`).
Locked batch D (16:24-16:27Z, load average 60-64; median of two interleaved runs), a2 = stage A, e9 = a2 + the combined operation called by the fake:

| corpus | CPU s / wall s / instructions G: a2 -> e9 |
|---|---|
| Init | 7.39 / 7.51 / 132.1 -> 7.34 / 7.47 / 132.1 (-0.7% CPU) |
| Std | 12.23 / 12.42 / 211.2 -> 12.27 / 12.39 / 211.2 (+0.3%) |
| Cedar slice | 2.57 / 2.62 / 38.9 -> 2.55 / 2.68 / 39.1 (-0.8%) |
| reachability | 7.92 / 8.07 / 57.4 -> 7.46 / 7.58 / 57.4 (-5.8% CPU, -6.1% wall; the two pairs: 7.92 -> 7.56, 7.93 -> 7.35) |

The instruction counts do not move: the gain is cache misses that overlap, which is what owner note 12 asks to
look at. KEPT (reachability lower, nothing higher by more than the noise of 1%).

Eight workers (locked batch F, 17:10-17:12Z, load average 45-58; cold process, v0; median of two runs; base ->
stage A -> stage A + combined operation):

| corpus | CPU s (all threads) | wall s |
|---|---|---|
| Init | 8.88 -> 8.28 -> 8.10 | 4.98 -> 4.59 -> 4.10 |
| Std | 14.50 -> 13.70 -> 13.45 | 6.50 -> 7.46 -> 6.71 (unusable: 4.6 to 8.5 s for ONE binary) |
| Cedar slice | 3.58 -> 2.92 -> 2.89 | 0.88 -> 0.77 -> 0.78 |
| reachability | 8.29 -> 8.02 -> 7.49 | 7.28 -> 6.84 -> 6.39 (one giant declaration: wall is one thread) |

The CPU totals repeat the one-worker result (Init -6.8% / -8.8%, Std -5.5% / -7.2%, Cedar slice -18.4% / -19.3%,
reachability -3.3% / -9.7% against the base). The wall clock of eight workers on this host, with 45-58 runnable
processes beside the container, scatters by a factor of 1.8 between two runs of the same binary on Std; I do not
draw a conclusion from it beyond "not slower".

With the step-1 switches (locked batch E, 17:21-17:24Z; `warm,gated,expect,probes`, session cap 4 MiB; one worker;
median of two; CPU s / wall s):

| corpus | base | stage A (a2) | a2 + combined operation (e9) |
|---|--:|--:|--:|
| Init | 7.50 / 7.56 | 7.10 / 7.13 | 6.94 / 6.98 (-2.3% against a2) |
| Std | 12.84 / 12.91 | 12.30 / 12.37 | 12.00 / 12.04 (-2.4%) |
| Cedar slice | 2.92 / 2.94 | 2.40 / 2.41 | 2.38 / 2.42 (-0.8%) |
| reachability | 8.09 / 8.22 | 7.87 / 7.97 | 7.32 / 7.40 (-7.0%) |

a2 against e9 is a clean pair (same foreign files). base against a2 is not: a2 also contains what the other
packages published between 13:20Z and 14:22Z (M6's pooled arrays among them); for stage A alone the clean pair is
batch A2 above (v0). With warm sessions the tables are larger than a declaration's, and the combined operation
gains on Init and Std too. Tick counts are identical in every pair (v0: 77,832,218 / 122,252,150 / 18,104,024 /
34,599,254; `warm,gated,expect`: Init 56,026,060, Cedar slice 18,712,121).

### 5.5 Storage strategies (owner note 8): what the distributions support

- **Inline frames of 1 or 2 kept slots in the closure record** (no run, one probe): 44% of the closure probes on
  Init and 42% on the Cedar slice have such a frame. NOT BUILT, for two measured reasons. (1) The second probe is
  cheap exactly where sessions are small (after stage A a PRUNE of a small frame is a few kept-slot terms, one
  table word and one comparison), and where sessions are large the two loads overlap with D20; my ESTIMATE for
  inlining is about 1% of Init (not measured: it cannot be tried without the other packages). (2) The frame word of a
  closure is read directly in M2, M4, M5, M6 and M7 (`Runs.enter(m, vh[o + B])`, `Match`, `Code` capture slots,
  `Debug`, `Show`): every one of them would have to go through an accessor that tests the strategy.
- **Inline arguments of a NEU with 1 or 2 arguments**: 35% of the applications on Init. Not built: spines are read
  in place by M3, M4 and M7 (`rs[run + RUN_H + j]`), and word c of a NEU is its hash, which `Hash.ofRecord` reads.
- **Sparse frames `(mask, kept values)` for long environments**: this is the strategy the Cedar slice supports
  (length 19.4 against 4.6 kept for new frames; far frames 66 words each). Estimate from the histograms: frame words
  90.3 M -> about 36 M (4 words of header and mask + the kept slots), i.e. 12,832 -> about 6,100 run words per
  declaration. The target of 4,000 cannot be reached by a representation: the kept slots (19.4 M) and the spines
  (12.2 M) alone are 3,920 words per declaration - below that only fewer frames help (M2: tabling). NOT BUILT in
  this wave: after stage A the time of PRUNE no longer depends on the length, so what is left to gain is the
  memory (page faults of grown slabs, 8 workers' cache footprint), against a layout change with direct readers in
  M6 (`Debug`, `Show`), M7 (`Match`, `Code`) and M4 (`Conv.ccloFrom` through `Runs.len / arg`). The cheapest first
  step would be far frames only (46% of the Cedar slice's frame words; nobody but `Runs` reads their near part by
  index): header, hash, REST word, the mask as three immediates (so that the generic walkers of `Debug` / `Show`
  see harmless literals), the kept values.

## 4. Not done / open

- **Far frames are still O(depth) per PRUNE and per ENTER, but cheaply**: since D19 a far PRUNE no longer hashes
  the outer bindings, it compares them with the cached chain (`Arrays.equals`, 44 slots on average on the Cedar
  slice; `Runs.sameChain` + `restOf` were 3.0% of that corpus before the comparison was vectorised) and ENTER
  copies them. O(1) would need to know that the prefix of the slab was not rewritten; `Eval.bind` and
  `MachineOps.in` write `es` directly and every judgment truncates `m.ep`, so `Runs` cannot know without a
  rule for the other packages. Measured frequency (the item of ISA 2.4): 16% of the PRUNEs of the Cedar slice,
  0.03% of Init's, none on reachability.
- **Sparse frames for long environments** (the Cedar slice's 12,832 run words per declaration): designed and
  estimated in 5.5, not built. The target "under 4,000" is out of reach of any representation (kept slots + spines
  are 3,920).
- **The young set** (5.3; requests.log 15:58Z to M6): the way to remove the table-slot misses of giant sessions
  rather than overlap them. A `Machine` design.
- **D20 in `Infer`**: five places of `Infer` (M5; lines 333, 933, 1226, 1612, 1971 at 15:43Z) still PRUNE and then
  probe; requested 15:43Z, M5's call. The LET suspension (`mkSusp(.., true)`) has no combined form.
- **D20 was measured with my fake of `Eval`, not with M2's published `Eval`** (same three calls; M2's own corpus
  runs were in progress at 16:49Z). With M2's switch `m2a` most tabling probes of reachability disappear (M2:
  records 20.6 -> 13.5 M), so the share that D20 can overlap is smaller there.
- **`m.nats` ownership** (D3): the guard detects a reissued id with the store's 32-bit content hash and the
  limb count; it is a detection, not a proof. (The machine has had its own overlay since M6's 09:45Z.)
- **The warm figures of owner note 12** (second pass in one process, 8 workers, pipelined): `-X passes=N` did not
  exist when this was measured; 5.2 and 5.4 give cold wall clock beside CPU time, one worker and (batch F) eight.
- The JVM-only probe table of 3.1 predates stage A and was not repeated (the native profile of 5.3 replaces it).
