# VALUES-V2 - identity when it is needed: the value model of the machine, revisited

Author: agent U2, 2026-11-24. Status: DESIGN + PROTOTYPE on a private copy (`work/u2/tree/rlvm-machine`, the main tree
of `rlvm-machine` as copied under the sandbox lock at 19:04:34Z); nothing of it is in the main tree. It revises
`ISA.md` section 2.5 (hash-consing discipline) and fills the last row of the table of ISA section 10 ("non-interned
cells ... v2 experiment"). Authorities, in this order: `OWNER-NOTES.md` (notes 3, 5, 6: the JIT is for the checking
process; 8: storage strategies; 11: official verdicts; 12: warm wall clock with 8 workers), `RECONCILIATION.md`,
`ISA.md`. Evidence it starts from: `rlvm-truffle/NOTES-T3.md` sections 5 and 7 (the tier has nothing to remove
because every value is interned at once), `docs/research/profiles/X1.md` (census of every record), `P8.md` 4.6 (2.6
times the leader's instructions per step), `rlvm-machine/NOTES-M1.md` (the store), `review/soundness-machine.md`.
Living summary and raw data: `work/status/prof-U2.md`, `work/u2/`.

Numbers in this document are exact counts of one run unless a table says "warm wall clock"; every timing was
taken inside a `bench/quiet.py` window (section 10 says which).

## 0. The answer in one page

TBD-0

## 1. What identity is used for today

Today (ISA 2.5, I-ID) every record is entered into `vtab` (and every FRAME / REST run into `rtab`) in the operation
that makes it: a hash over its key, a load of a table slot that is cold once a session has outgrown the caches
(NOTES-M1 5.3: four fifths of a probe on reachability), a comparison on a hit, a table store and later one more probe
at the session reset on a miss. Identity then serves the following mechanisms. The last two columns say what each of
them needs from a value: nothing but "equal refs denote equal values" (the direction that hash-consing gives for
free and that a ref to ANY record gives), or the full I-ID "equal values have equal refs" (canonical ids).

| # | Mechanism | Keyed by | A hit gives | Needs canonical ids for a right verdict? | What non-canonical ids cost |
|---|---|---|---|---|---|
| 1 | `CONV` c0, `a == b` (the official `quick_is_def_eq` / `is_eqp`) | the two refs | T without work | no: unequal refs fall through to the complete procedure, which answers T on equal values (section 8.1) | the structural test `Same` (D28, bounded), or the full comparison of two equal values |
| 2 | pair table POS / NEG / FAILED (the official `m_success` and failure caches) | the unordered pair of refs | T / F without work | no: in plain mode the official answer is a function of the pair, so a recomputation gives it again; under eager reduction the machine declines (S-F5) | a comparison done again |
| 3 | memo words of an application / constant / projection: `WHNF`, `CORE`, `UNF`, `TYPE`, flags `PROOF`, `PROPT`, `COREFULL` | the record | the result of a reduction or inference | no: a memo word of any record is a fact about the value that record denotes | **the reduction done again for every duplicate**: this is where gruszka's ground machine loses a factor of 10 (R05 2.11) and where mode 3 below loses 26 % ticks on reachability |
| 4 | `VAL` / `TYPE` / `TSTAMP` of a suspension = tabling of `EVAL`, `INFER`, `CHECK` | `(term, PRUNE(frame), ls)`: the frame's slots are refs | the value / type of a term under bindings | no | a term evaluated again for every spelling of its bindings; a shared node of a DAG is the exponential case |
| 5 | closure identity `(term, frame, ls)` and its memo words `DOM`, `COD`, `TYPE` (PII), `SLVL`, `CODE` | as 4 | the same closure for the same bindings | no | as 4 |
| 6 | free variables `FVAR(type, k)`, `FV0`, the scope protocol (ISA 2.8) | the ref of the type | the canonical variable of a binder | no (two spellings of one type give two families of variables; I-FV2 still holds), but see 8.1 item 6 | memo hits under binders are lost |
| 7 | direct-mapped caches: `CHEAD`, sorts, `IC`, `LSUB`, `SIGT`, `UNDEC`, closed-term memo, APPLY memo, REST chain | refs or (ref, site) | one probe saved | no (every cell is a hint or carries its full key) | misses |
| 8 | the OPEN bit table, the record's flag bits | the record | - | no (functions of the value, I-FLAG) | - |
| 9 | warm sessions | records of earlier declarations | everything above across declarations | no | - |
| 10 | tier and convention equivalence in the gates (NOTES-M8) | record words, run words, pairs, ticks | "two executions did the same" | not a verdict; the comparison is word for word today | a new named mode needs its own definition (section 7) |

Reading: **no verdict needs canonical ids; every use of them is sharing** - which is not a small thing: rows 3, 4
and 5 are what make the machine terminate in seconds on proof terms that are DAGs. The question is therefore not
"which values may lose identity" but "for which values is the probe paid before it is known that anything will ever
look them up", and the census answers it: applications (section 9).

## 2. The model

### 2.1 Three kinds of values

| Kind of value | Identity | Why |
|---|---|---|
| immediates (Nat below 2^31), `NONE`, `HOLE` | the ref is the value | no record |
| atoms: SORT, CONST, FVAR, NBIG, STR | canonical at birth (through `CHEAD`, the sort cache, `FV0`, the literal import) | a handful per declaration, found by a direct-mapped cache; FVAR is identity by definition |
| keys by purpose: the tabling record of `EVAL` / `INFER` / `CHECK` (plain SUSP), frames, PROJ and NSUC cells | canonical at birth | they exist to be looked up; `gated`, `bodies` and X1's `once` decide WHETHER one is made, not this document |
| **applications (NEU)** | **private at birth at a lazy site, identified on demand** | 19-59 % of the records, found again 3-20 % of the time (X1 C2, P2); built by the hundred thousand as constructor arguments, types and delayed redexes that nothing looks up |
| closures and delayed arguments (LAM, PI, SUSP made by `MKSUSP`) and their frames | canonical at birth in the prototype; stage D of section 11 (design only) | two thirds of their probes hit (X1: LAM 63-66 %, delayed arguments 62 %): the opposite regime, see 9.3 |

### 2.2 Life cycle of an application

```
            mkAppLazy                      ident: key absent                 (session reset)
  (nothing) ---------> PRIVATE ------------------------------> IDENTIFIED ----------------> gone
                          |                                        ^
                          |   ident: key present                   |  forward (word c)
                          +---------------------------> DUPLICATE -+
```

- **PRIVATE**: record and spine are written in the ordinary slabs (`vh`, `rs`); no hash is computed, no table is
  touched; word c is 0; one bit in `Values.Ext.privBits` says "in no table". Every reader works on it unchanged:
  kind, flags, head, spine, memo words, `FIELDS`, `SWITCH`, `PEEK`, `Same`, `Quote`, `Show`.
- **IDENTIFIED**: the record is in `vtab` under its key; all its arguments are identified (2.3 V2). An application
  made by an interning constructor is born here, as today. A private one gets here IN PLACE: same record, same ref.
- **DUPLICATE**: when a private application is identified and its key is already in the table, the record stays what
  it is (a valid value for whoever holds its ref) and word c holds the ref of the canonical record; the operation
  that asked stores the canonical ref. A duplicate is never in a table and never becomes a component of a key.
- There is no fourth state "shared / published": values never leave a session (what workers share is terms, fact
  records and code objects). "Published" is "identified".
- Nothing is reclaimed before the session reset (section 4.4 says why not yet).

### 2.3 Invariants

- **V1 (a ref means one value)**: for the life of a session a ref denotes one value. The identity words of a record
  are written once, with two exceptions that keep the value: an argument slot of a private application may be
  replaced by the canonical ref of the same value, and word c of an application goes 0 -> its hash (identified) or
  0 -> the canonical ref (duplicate).
- **V2 (keys are canonical)**: every component of an identified record or of an interned run is identified:
  arguments of an identified application, slots of a FRAME / REST run, the struct of a PROJ, the base of an NSUC,
  the type of an FVAR. Hence for identified values I-ID of ISA 2.5 holds as it is written: two identified refs are
  equal iff they are the same construction.
- **V3 (one direction for everybody)**: equal refs denote equal values (V1). Unequal refs prove nothing unless both
  are identified. No judgment may answer "different" from unequal refs (none does today: 8.1).
- **V4 (flags and memo words)**: the flags of a private application are those of its interned twin (computed by the
  same rule from head and arguments); a memo word of any record is a fact about its value and may be used, but it
  is shared only between holders of that record. A memo that must be shared is read through the canonical record
  (2.4, class M).
- **V5 (tables)**: a record is in `vtab` iff its private bit is clear; growth and reset of `vtab` skip set bits;
  `Hash.ofRecord` is called for table members only (word c of a private record is not a hash).
- **V6 (determinism)**: which records are made, which are private, when each is identified and every ref are
  functions of the sequence of operations: nothing depends on time, on the host depth bound or on the worker.
  Convention equivalence (NOTES-M8) therefore holds word for word inside the mode.

### 2.4 What forces identity, and why each is necessary

`Values.ident(v)`: `v` unless `v` is a private application; a duplicate: its canonical ref; else: identify the
arguments (deep, iteratively - a private nest is as deep as a list is long), probe the key; absent: enter the record
itself; present: make it a duplicate. Cost: one probe per private record reached, i.e. what was not paid at birth.

| Class | Operation | What is identified | Necessary because | If it were omitted |
|---|---|---|---|---|
| K (keys) | an interning constructor of an application (`mkAppKey`, `mkNeu`; extension of an application) | every argument (and the copied prefix) | V2: the key of an identified record | no wrong verdict; the application has a second id per spelling of its arguments, and everything keyed on it (rows 3-5 of section 1) misses |
| K | `PRUNE`, `FRAME`, the REST chain, `closureAt` (closures, delayed arguments, tabling records) | every binding the frame keeps | V2: frames are the keys of tabling and of closure identity | tabling and closure hits become spelling-dependent: unbounded re-evaluation on shared terms |
| K | `MKPROJ` that stays a PROJ cell, `NSUCC` that makes an NSUC cell | the struct, the base | V2 | as the first row |
| K | `OPEN(type)`, `mkFVar` | the type | V2, and row 6 of section 1: one family of variables per type | memo hits under binders are lost; two closures over the "same" binder type open different variables |
| M (memo keys) | `WHNF` of a private application whose head may reduce (flag ACTIVE), when its own memo word is empty | the application | the WHNF memo is the hit that makes repeated reduction free; it belongs to the canonical record | **measured: +25.6 % ticks on reachability with lazy code blocks, +143 % with every site lazy** (mode 3, 7 of section 10.1) |
| M | (design; not in the prototype) `CORE`, `DELTA`, `VTYPE`, `ISPROP` / `PIRR` of a private application | the application | the same for `CORE`, `UNF`, `TYPE`, `PROOF` | re-reduction / re-inference per duplicate; in the prototype this is the residual +0.2 to +0.4 % ticks of mode 11 |
| C (comparison) | `CONV(a, b)` after the test `a == b` (prototype: bit 32) | both sides | row 2 of section 1: the pair table is keyed by refs; and it restores `a == b` for equal values | **measured: +38 % ticks on the Cedar slice with every site lazy** (mode 15 against 47); nothing measurable when only code blocks are lazy |
| - | a site that is known to make a memo key: `KCONST ; MKAPP d ; WHNF _, d` (the scrutinee of a case tree) | the application, at its birth (it is not a lazy site) | T3's count for U1: 464,637 such applications per pass on reachability, 92,000 distinct, four of five answered by the WHNF memo | a private record made and thrown away as a duplicate four times out of five |

What does NOT force identity: reading (`FIELDS`, `SWITCH`, `BKIND`, the projection of a constructor application,
`PEEK`), passing (arguments of `APPLY` / `CALL` / `TAILAPPLY`, results, registers, environment slots, scratch),
storing as a memo VALUE (`VAL`, the result word of `WHNF` / `UNF` / `CORE`, an inline-cache value), extension
(the prefix is copied), the structural test `Same`, readback. A private application that is only built, passed,
matched and dropped never costs a probe: that is the whole gain.

## 3. Lazy sites: which constructions are born private

A lazy site calls `Values.mkAppLazy`; every other caller of `Values.mkApp` interns as today. The mode bits
(system property `rlvm.u2`, the named mode is `lazyid`; 0 = ISA 2.5 as it is):

| Bit | Name | Meaning |
|--:|---|---|
| 1 | DATA | a lazy site builds an application whose head is weak-head normal (constructor, inductive type, axiom, free variable: flag WHNF) privately |
| 2 | REDEX | ... and an application whose head may reduce (flag ACTIVE: definition, theorem, recursor, quotient, projection, Nat function), except the scrutinee of the next `WHNF` |
| 4 | ALL | every `Values.mkApp` is a lazy site (the generic `EVAL` walk, the rules, string expansion); without it only `MKAPP` of code blocks is |
| 8 | WHNF | class M of 2.4: `WHNF` identifies a private application before it reduces it |
| 32 | CONV | class C of 2.4 |
| 64 | ADAPT | adaptive sites (below) |
| 16 | STAT | counters (process-wide, printed at exit) |

The modes that were measured: **1** (data built by blocks), **11** = 1 + 2 + 8 (everything blocks build, memo
shared), **43** = 11 + 32, **47** = 43 + 4 (every site), **107** / **111** = 43 / 47 with adaptive sites, and the
deliberately broken ones, **3** and **7** / **15** (redexes without class M, every site without class C), which exist
to show what classes M and C are worth.

**Adaptive sites (bit 64).** Whether a site's applications are ever identified is a property of the site, and it can
be learned - the owner's "use whatever specialization / JIT trick": an inline cache of the site's demand for
identity. The prototype's site is the constant at the head (its declaration row, direct-mapped into 2^13 pairs of
saturating byte counters per machine: made / identified; both are halved when one saturates). A private record of
an adaptive site carries its site in word c (`-(site + 1)`: negative = private with a site, 0 = private, positive =
forward) and reports to its site when it is identified. A site with at least 32 samples whose records were
identified three times in four interns at birth again, except for one construction in 16, which stays lazy as a
sample (so a site can come back). The decision changes which records are private, never a value; it is a function
of the machine's history, so V6 holds per machine and per schedule, not across worker counts (as warm sessions
already make ticks depend on which declarations a session saw). A site per block instruction (the code index of
the `MKAPP`) and per term node of the generic walk would be sharper; not built.

## 4. Representation

### 4.1 In the stepper (as built)

- `Machine.addRecordFree(w0, a, b, c)`: the bump of `addRecordAt` without the table store; `Machine.enterRecordAt`
  enters an existing record; `Machine.vtabUsed` replaces `vp - vbase` in the load test (a private record does not
  occupy a slot), in the reset heuristics and in the footprint.
- `Values.neuPriv`: the miss branch of `neu` / `neuExt` (flags inherited from the head or the extended application,
  OPEN = or over the arguments through the OPEN bit table, spine written by a loop) without hash and probe.
- `Values.Ext.privBits`: one bit per record (1/256 of the record slab), cleared at the reset in O(used / 64);
  `privMade` (private records of this session) is the one word every key-forming loop tests: 0 = the loops are the
  loops of today.
- Forwarding: word c of the duplicate (positive). No side table. Word c of a private record is 0, or negative: the
  adaptive site it reports to.
- `Values.ident` / `identSlow`: explicit stack of (record, next argument) pairs in `Values.Ext.istk`; no host
  recursion (test: a nest 300,000 deep).
- Force points (2.4): `Values.neu`, `neuExt`, `closureAt`, `mkProj`, `nsuccSlow`, `mkFVar`; `Runs.pruneNear`,
  `internRun`; `Scope.open`; `Reduce.whnf` (bit 8); `Conv.conv` (bit 32). A forced binding is written back into
  the environment slot / scratch word it came from, so the hash and the comparison that follow see canonical refs
  and a second capture of the same slot costs one bit test.
- Lazy site: `Code.mkApp` (with the scrutinee test of 2.4).

### 4.2 What was considered instead

| Alternative | Why not |
|---|---|
| ref bit 30 `REF_EPH` (ISA 2.1, reserved for exactly this) with a nursery slab of its own | 27,000 lines read `vh[v << 3]`; every one would need the test. The bit table gives the same information to the few places that need it (the key-forming loops) and costs the readers nothing |
| a young intern table in front of `vtab` (M1's "young set") | keeps I-ID, removes the cold load only if hits are recent; X1 C7: 56-87 % of the hits on the Cedar slice and reachability come 2,048 ticks or more after the record was made, and M1's front caches lost time (NOTES-M1 5.3) |
| region rollback per judgment (X1 b1) | releasing costs a probe per record, needs an undo log at every memo store; X1: no-go as a speed-up |
| Reussir-style reuse bits (X1 a, c) | no-go by census (reuse cases 0.4-7 %, the source always referenced) |
| one global "is this value shared" bit maintained at every copy of a ref (`REF_UNQ`) | ISA section 10: one missed mark is an unsound write; nothing here needs to know uniqueness, only "is in the table" |

### 4.3 Storage strategies (owner note 8)

The model leaves the room the note asks for and uses the first instance of it: "private application" IS a storage
strategy in the GraalJS sense - a value keeps a cheaper representation (no table entry, no hash) while what is done
with it allows it, and transitions to the general one (identified) when an operation needs it; the transition is
`ident`, it is one-way, and the strategy tag is the bit of `privBits`. Packed lists / arrays / strings (kinds 13-15)
would enter the same way: born packed and private, unpacked by the constructor view, identified by `ident` (which
then needs a canonical key for the packed form - not designed here).

### 4.4 Why nothing is reclaimed

A private application that was never identified is garbage at the latest when no register, environment slot, memo
word or private record holds its ref - which nobody tracks (ISA section 10: no counts). The prototype keeps it
until the session reset, like every record. Consequences: the footprint of a session GROWS in this mode (the
duplicates that interning would have folded: section 10.1, records +23 to +44 % on reachability), the machine's own
session cap is reached earlier, and a giant declaration needs more memory, not less. A nursery that is released at
the return of the activation that made it would need the escape information of 5 and is the memory half of stage E.

## 5. What a private value is to a JIT

Owner notes 5 and 6: the JIT compiles the checking process, i.e. blocks. NOTES-T3 section 7: a block builds values
and asks judgments; compiled code must repeat every probe, so escape analysis has no object. With lazy sites the
block-built applications are no longer global side effects at birth - but the stepper still WRITES them (record and
spine in the slab), and a write into a shared array is an escape for the compiler too. Three steps make them
removable, in this order:

1. **Reserve, do not write.** The id of a private application and its spine are fixed by two bumps (`vp`, `rsp`);
   the words can be written later. In a compiled block `MKAPP` at a lazy site reserves the id and keeps head and
   arguments in a virtual object (`PApp(id, head, a1..an)` in a frame slot); every instruction of the same
   compilation unit that READS it (`FIELDS`, `SWITCH`, `BKIND`, extension) reads the object's fields;
   **materialisation** - writing the eight words and the spine at the reserved id - happens where the ref leaves
   the unit: an operand of a boundary call (a judgment, an interning constructor, `ident`), a store into a machine
   register that outlives the unit, the result. Escape analysis then removes object and write for every
   application that is consumed inside the unit. Because the id was reserved, **both tiers give every record the
   same ref**, written or not, so everything keyed by refs (hashes, direct-mapped cells) behaves identically and
   the lossy caches cannot make ticks tier-dependent.
2. **Calls (T3's U1).** `CALL` passes and returns refs as they are (T3, requests.log 19:32Z: "a private application
   can be an argument and a result with no rule on my side"). With direct call nodes the unit of step 1 grows to
   caller + inlined callee: the callee's `PProd.mk a b c d` returned to a caller that takes one field is never
   materialised - NOTES-T3 7.3's "arguments nobody inspects" become dead code. The scrutinee of a `CALL` that takes
   the generic path is a memo key and is interned at birth (2.4, last row; T3's finding).
3. **A nursery (later).** Reserved-but-never-written ids cost address space, not memory traffic; where even that
   matters (giant sessions), the reservation can come from a per-activation range that is popped at the return if
   nothing was materialised. This needs "no ref of the range survives", which step 1's escape information gives
   for compiled code only.

The stepper keeps the simple form (write at birth): it is the reference semantics, and its write is the
materialisation "at the first instruction".

**Tier equivalence in this mode** (the rule T3 confirmed is enough for its nodes): two tiers are equivalent iff,
unit by unit, they give the same outcome and reason, the same ticks and call events, the same pair entries, and
**the same identified records with the same refs and words, in the same order of identification**; a private record
is compared only through what holds it: its ref (reserved identically in both tiers) and, once it is materialised
in both, its words. A record that only one tier materialised is not a difference. `M8Tiers` (values by structural
fingerprint) needs no change: a fingerprint follows arguments, private or not.

## 6. How the rest of the machine works with it

| Subject | Rule |
|---|---|
| session reset | `vtab` is emptied by fill or by one probe per IDENTIFIED record (private ones are skipped: an absent entry would cost a scan of the whole table); `privBits` is cleared in O(used / 64) and falls back to its base size after a large session; `vtabUsed = 0` |
| table growth | `growVtab` re-enters identified records only, in id order (X1's argument for deletions still holds: a record identified in place is entered later than records with larger ids, which is harmless for open addressing without deletions) |
| footprint, session cap | `engineBytes` counts every record and run word (private or not) and the occupied table slots (`vtabUsed`); `Values.Ext.bytes` counts the bit table. A warm session therefore reaches the machine's 1 MiB cap earlier (resets: section 10.1) |
| budgets, ticks | identification is part of the operation that asks for it, as a probe is today: no tick, no call event, no frame; it cannot be suspended (no judgment runs inside it) |
| tabling (`gated`, `bodies`; X1's `once`) | unchanged: the key is `(term, PRUNE(frame), ls)` and PRUNE identifies what it keeps (class K), so a tabling hit is exactly today's. `once` hashes the kept bindings before `table`: it must hash them AFTER they are identified (one call of `Values.identKept` in `firstVisit`), else the filter sees two spellings of one key and evaluates once more - a tick difference, never a value |
| `Same` (D28, the official `is_equiv`) | structural by construction ("false is not seen, never different"): it walks private applications like any other. With lazy sites it is consulted for pairs that `a == b` answered before; its fuel (256 nodes, 40 levels) bounds what it sees |
| pair table | keyed by refs as they are; canonical under class C. An entry made for a private ref stays valid (V1) |
| inline caches, `CHEAD`, closed-term memo, APPLY memo | hold refs as values: a private ref is a valid value. Cells keyed by a ref miss for a duplicate |
| free variables | `OPEN` identifies the type first; the scope protocol is untouched |
| literals | not applications; `Nat.succ x` goes through `nsucc` as before (an NSUC cell identifies its base) |
| readback, `Show`, debug audits | read records: unchanged. An audit that enumerates `vtab` or expects every record in it must skip private bits (the gate's audit lanes are clean with the prototype: section 10.3) |
| warm sessions | a private record lives across declarations like any record (I-SESSION: every constant it mentions was visible when it was made) and may be identified by a later declaration |
| eager reduction (S-F5 / F26) | the decline rule is untouched |
| the T2 tier | must mirror the lazy site and the scrutinee test in its `MKAPP` node (NOTES-T3 9.5: the tier follows `Code.java` by hand), then step 1 of section 5 |
| strict mode | `lazyid` is an optimisation that changes what is computed (records): off in strict mode like every staging switch |

## 7. Ticks, records, gates

- **Ticks.** Identical to the mode-off run exactly when every memoised judgment sees canonical refs. With classes K,
  M (WHNF) and C the prototype's residue is +0.0 % (reachability, mode 43) to +2.6 % (Std, mode 11, which has no
  class C); without class M or C the tick count shows the repeated work (10.1). A tick difference inside the mode is
  never a verdict difference; it is the measure of lost sharing and is reported as such.
- **Records.** More, not fewer: every lazy construction that interning would have answered with an existing record is
  now a record of its own (a duplicate once identified, or never looked at again). What falls is the number of
  PROBES and of table entries.
- **Convention equivalence** (host depth 0 / 128 / unbounded: outcome, reason, ticks, call events, trace, every
  record word, run word and pair): unchanged, by V6. The prototype passes it (10.3).
- **Tier equivalence K0 against K1** (declaration outcomes; `M8Tiers` values): unchanged.
- **A new named mode needs its own gate**, defined as: (1) the differential gate per declaration against the
  reference engine with the mode on; (2) the same outcome, reason and ticks ... as the mode-off run is NOT required
  (records differ by design; ticks may differ by the residue above); required instead: **the same outcome and
  reason per declaration as the mode-off run, and no decline that the mode-off run does not have**; (3) convention
  equivalence inside the mode, word for word; (4) the audit lanes inside the mode; (5) `SHostileSlowTest` with the
  mode on in every switch set; (6) `U2ValuesTest` (I-ID of identified values under random construction, capture,
  extension and growth; flags; deep nests; clean tables after a reset).

## 8. Soundness

### 8.1 Two equal values look different (a private application and its twin, or two private twins)

1. **`CONV`.** `a == b` fails; `convFast` finds no pair; `Same` (default set) sees them as one expression within its
   fuel and answers T - the official `is_equiv`. Beyond the fuel the complete procedure runs on two structurally
   equal values. It answers T: both sides take the same deterministic steps (the same head, the same hints, so lazy
   delta unfolds both or compares the arguments of both; the same `WHNF` results up to V3), every sub-comparison is
   again between structurally equal values, and the official procedure answers T on identical terms without
   looking (so T is the official answer). What can differ is cost: a comparison the official kernel answers by
   pointer could run into the unit's tick bound here and DECLINE (the reference engine then decides) - never a
   wrong verdict. `perf/loop-unfolded-once` is the known member of this family today, without this mode. Not seen
   in any gate run with the mode (10.3: the declines are those of the mode-off run).
2. **Pair table.** A miss recomputes. Plain mode: the official `is_def_eq` is a function of the pair (review 4.3),
   so the recomputation is the official answer. Eager mode: the machine never compares (decline rule). NEG and
   FAILED entries are facts about refs, hence about values (V1).
3. **Memo words** (`WHNF`, `CORE`, `UNF`, `TYPE`, `PROOF`, `PROPT`): recomputed per record; each is a function of the
   value in plain mode. The K-rule memo that depended on the mode is S-F5's subject and stays under the decline rule.
4. **Tabling, closure identity.** Never affected: frames keep identified bindings only (V2).
5. **Flags.** Functions of the value, computed by the same code (`U2ValuesTest.flagsOfPrivateApplicationsAreThoseOfInternedOnes`).
6. **Free variables.** The type is identified before `FV0` / `mkFVar`, so a binder type has one family of variables
   as today. (Were it not: two spellings of a type would open `FVAR(T', 0)` and `FVAR(T'', 0)`, different records;
   I-FV2 "no two open binders hold the same variable" would still hold, a comparison of two closures opens ONE
   variable for both sides, and only memo hits under binders would be lost. The prototype does not rely on this.)
7. **Projections, iota, K, structure eta, Nat offsets, string expansion**: all read head and spine; none looks up an
   application by its ref.
8. **Level comparison, literals**: not applications.
9. **`Nat.succ`, canonical forms**: `mkAppLazy` applies the `Nat.succ` rule before anything else, as `mkApp` does.

### 8.2 Two different values look equal

Only four things could do that, and none exists:

1. **A ref reused while somebody holds it.** Nothing is reclaimed before the session reset; the reset clears the
   bit table together with the slabs; a ref kept across a reset reads kind 0 as today (poison mode).
2. **A wrong forward.** Word c of a duplicate is set only after the probe compared the whole key - head, length,
   every argument - against a table member, with all arguments identified first; by V2 and induction over the
   structure the two records are the same construction. (`U2ValuesTest.identifiedValuesHaveOneIdPerConstruction`:
   6,000 random constructions per seed, lazy, interned, extended, captured, across table growth: one canonical id
   per structure and one structure per canonical id.)
3. **A wrong in-place rewrite.** The only rewrite replaces an argument by the canonical ref of the same value (the
   result of item 2 or the argument itself).
4. **A stale hash.** Word c of a private record is 0 and is never read as a hash (V5). Had it been: a hash only
   chooses a probe sequence; a hit compares the key.

### 8.3 Where the official outcome depends on sharing or caching (review 4.1-4.4)

| Case | With `lazyid` |
|---|---|
| S-F5 / S-F4: the official success cache and WHNF caches under eager reduction | the machine declines such declarations in every regime; unchanged |
| S-F1 / S-F2: congruence where the official procedure is not a congruence | removed from every switch set; nothing here adds a congruence: `Same` is structural identity of expressions, not convertibility of arguments |
| `nontransitive-*` | no transitive closure is introduced: a forward is identity of construction, not convertibility |
| canonical free variables | types identified (8.1 item 6) |
| the official `is_equiv` shortcut that ends loops (`loop-unfolded-once`) | `Same` within its fuel; beyond it a decline at the tick bound, as today |
| warm sessions | everything a session keeps is still declaration-independent; a private record is no exception |

## 9. Sizing from the census (before anything was built)

### 9.1 What the two censuses say per class

X1's census (`docs/research/profiles/X1.md` C2, P2, and the per-run tables under `work/prof/X1/census/tables/`): the
generic tier with X1's `evc + once`, i.e. after the tabling records of `EVAL` are gone - the population that the
value model decides about. "probes that hit" = share of the constructor calls of the class that found a record.

| Class | Cedar slice: records (share) / found again / probes that hit | Init | reachability |
|---|--:|--:|--:|
| applications (NEU) | 1.80 M (24 %) / 21 % / - | 15.64 M (47 %) / 15 % / - | 7.93 M (59 %) / 3.5 % / 4.4 % |
| LAM closures | 0.90 M (12 %) / 32 % / 63-66 % | 4.23 M (13 %) / 31 % / 63-66 % | 3.13 M (23 %) / 1.3 % / 65.4 % |
| PI closures | 1.46 M (19 %) / 23 % | 2.30 M (7 %) / 12 % | 0.27 M (2 %) / 6 % / 14.5 % |
| delayed arguments (SUSP) | 0.08 M (1 %) / 15 % | 1.05 M (3 %) / 17 % | 0.76 M (6 %) / 3 % / 62.2 % |
| tabling records of INFER / CHECK | 1.64 M (22 %) / 34 % | 4.30 M (13 %) / 35 % | 0.20 M (2 %) / 31 % |
| records that nothing found again and that only such records refer to (TE), all kinds | 56.7 % | 40.0 % | 17.7 % |

T3's census of the block instructions (requests.log 19:32Z; the default set of the tree of 19:04Z = `gated, pchk,
expect, warm, code, casetrees, bodies, directrules, same, match, joint, lazycases, lazyeval`; one warm pass, one
worker; "new / found" = executions that made a record / that found one):

| Corpus | records per pass | `MKAPP` new / found | `MKLAM` + `MKPI` + `MKSUSP` new / found | `MKPROJ` new | made by block instructions |
|---|--:|--:|--:|--:|--:|
| reachability | 4.82 M | 2.27 M / 2.11 M (48 % found) | 0.71 M / 0.92 M (56 %) | 0.41 M | 70 % |
| Init | 16.19 M | 3.39 M / 1.80 M (35 %) | 0.94 M / 1.25 M (57 %) | 0.33 M | 29 % |
| Cedar slice | 5.03 M | 0.39 M / 0.21 M (35 %) | 0.29 M / 0.71 M (71 %) | - | 14 % |

### 9.2 What each candidate rule can reach

| Rule | Bound from the census | Decision |
|---|---|---|
| private per ACTIVATION, identified at the return (the brief's first cut) | nothing: the hot blocks return what they build (NOTES-T3 section 5: `BODY(#913)` builds three suspensions, three applications and returns the outermost; `RULE(List.rec)` passes its application to `TAILAPPLY`); every block-built value leaves its activation as an argument or as the result | NOT BUILT: by inspection it would identify everything it makes, one instruction later |
| private per EVAL JUDGMENT, released at its return (X1 b1) | 25-43 % of the records are dead at the return of the judgment that made them, but they are the tabling records that `once` / `bodies` no longer make; after them 2.3-5.3 % (X1 P1) | no (X1) |
| **applications private until they are a key** (this document) | the `MKAPP` executions: 4.38 M per pass on reachability (of about 8.5 M probes of block instructions), 5.19 M on Init, 0.60 M on the Cedar slice; with every site lazy also the generic applications. The price: every "found" becomes a record (reachability +2.11 M = +44 % of all records) until proven otherwise by an identification. What the census cannot say is how many are never identified - TE (17.7-56.7 %) is a lower bound because it counts a record as used when anything that refers to it was found again | BUILT; measured: 64-83 % are never identified (10.1) |
| closures and delayed arguments private until forced or applied | `MKLAM` + `MKPI` + `MKSUSP`: 1.63 M executions per pass on reachability, 2.19 M on Init, 1.00 M on the Cedar slice, each two probes (frame and record) - but 56-71 % of them FIND their record: a lazy closure site would make 2.3 to 3.4 times the closure records and frames it makes today, and a duplicate suspension that is forced must be identified first (its `VAL` is the memo) | NOT BUILT (stage D); the session-end census of 10.1 says how many suspensions are never forced |
| everything lazy, no table at all (gruszka's ground machine) | all probes | no: rows 3-5 of section 1; measured here as mode 7: +143 % ticks on reachability |

## 10. The prototype: what was measured

Private tree `work/u2/tree/rlvm-machine` = the main tree of 19:04:34Z + the patches of `work/u2/patches/` (eight
main files, 610 added and 5 changed lines, one test class). One binary for before and after: the mode is the system
property `rlvm.u2`; the pristine twin (`work/u2/base`) is measured beside it to show that the mode-off prototype is
the tree it was copied from. Switches everywhere: the default set of that tree (`gated, pchk, expect, warm, code,
casetrees, bodies, directrules, same, match, joint, lazycases, lazyeval`).

### 10.1 Counts (JVM launcher, ONE worker, statistics file; `work/u2/counts/`, table by `work/u2/scripts/tab.py`)

TBD-10.1

### 10.2 Warm wall clock

TBD-10.2

### 10.3 Gates

TBD-10.3

## 11. Recommendation and stages

TBD-11

## 12. Not established

TBD-12
