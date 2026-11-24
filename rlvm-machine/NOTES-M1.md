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

## 4. Not done / open

- **Far frames are O(depth) TIME per PRUNE and per ENTER** (memory is linear since D14): every outer binding
  is hashed and compared at each far PRUNE and copied at each ENTER. A nest that reads a variable more than
  63 binders away at every level costs O(depth^2) slot operations (2,000 deep: 4 M, nothing; 10^5 deep:
  10^10, too slow). Making it O(1) amortised needs a cache keyed by the environment prefix with a way to
  know that the prefix was not rewritten; judgments truncate `m.ep` directly, so `Runs` cannot know today.
  Not done; it needs the measurement item of ISA 2.4 (how often far frames occur) first.
- **`m.nats` ownership** (D3): the guard detects a reissued id with the store's 32-bit content hash and the
  limb count; it is a detection, not a proof. The request to M6 stands.
- No native-image measurement of the probe (JVM only, section 3.1), and no quiet-host measurement of the
  two A/B experiments of section 3.2 (the host ran at a load average of 90-170 during this wave).
