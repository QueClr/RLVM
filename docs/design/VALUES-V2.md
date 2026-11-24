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

**The model in ten lines.**

1. A ref denotes one value for the life of a session, and equal refs are equal values. That is all a verdict needs.
2. Canonical refs ("equal values have equal refs", I-ID) are SHARING, and sharing is needed in three places only: the
   subject of a memoised judgment (class M: WHNF, CORE, UNF, TYPE and the PROOF / PROPT flags of an application; VAL
   of a suspension), the components of a key (class K: frame slots = tabling and closure identity, arguments of an
   interned application, PROJ / NSUC / FVAR), and the two sides of a comparison (class C: the pair table).
3. Atoms, frames, closures, suspensions and tabling records keep their identity from birth: they are keys by
   purpose and most of their probes hit.
4. An APPLICATION built at a lazy site is born PRIVATE: record and spine are written into the ordinary slabs, one bit
   says "in no table"; no hash, no probe. Lazy site = `MKAPP` of a code block, except the application that the next
   instruction reduces (a memo key that hits four times in five).
5. It is IDENTIFIED - arguments first, iteratively; one probe; entered in place under its own ref, or made a
   forwarding duplicate of the record that already has the key - at the moment it enters class K, M or C. Reading,
   matching, projecting, passing, returning, storing it as a memo value do not identify it.
6. Invariant: every component of an identified record or interned run is identified. So I-ID holds for identified
   values exactly as ISA 2.5 states it, and with the three classes complete the machine makes the judgments it makes
   today, tick for tick (the completeness test: ticks with the mode on = ticks with the mode off).
7. A probe that is not made is worth a cache miss where a session has outgrown the cache and less than its
   bookkeeping where it has not: lazy sites are lazy only in sessions of 2^17 records and more, and a site whose
   applications are identified anyway learns to intern at birth (adaptive sites).
8. Nothing is reclaimed before the session reset; a private record is a record (its footprint counts).
9. To a JIT a private application is a reserved ref plus a virtual object, written only where the ref leaves the
   compilation unit; both tiers give every record the same ref; tier equivalence compares identified records.
10. Soundness: nothing answers "different" from unequal refs; a forward is set only after the whole key compared
    equal against a table member; no ref is reused. Gates inside the mode are word for word as today.

**Sizing (census, before building).** Block instructions make 70 % of reachability's records and `MKAPP` alone
47 %; half of its executions find an existing record (T3). Applications are 24-59 % of the records and are found
again 3.5-21 % of the time; closures and delayed arguments hit two times in three (X1). A per-activation or
per-judgment nursery has nothing to release (the hot blocks return what they build). So: applications, lazily,
until they are a key.

**What the prototype measured** (private tree, one binary, `-Drlvm.u2=N`; 10.1, 10.2). Applications never
identified = probes not made: reachability 3.04 M per run (63 % of the mode-off records, 36 % of all record-table
probes), Cedar slice 0.29 M (6 %), Init 1.95 M (12 %), Mathlib slice 1.17 M (13 %), Std 2.90 M (10 %), at -0.1 to
+1.6 % ticks and +4 to +36 % records (block sites, every session). One session per declaration: ticks within
0.02-0.2 % of today's. Warm wall clock, JVM, `WarmMain`: reachability -13 % at one worker and -8 % at eight (block
sites); with the large-session trigger and adaptive sites **-16.7 % at one worker (888 against 1066 ms, ranges
disjoint) and -10.0 % at eight (460 against 511 ms)**; the Cedar slice +4 % at one worker when every session is
lazy (not resolved), +9 % with every site lazy (resolved), and unchanged - counters identical to the mode-off run -
with the trigger. Native image: reachability -11.9 % at one worker (1185 against 1345 ms, ranges disjoint), **no gain at eight workers** (+3 %, inside the scatter; workers' CPU -1 %); Cedar slice unchanged with the trigger.

**Gates.** M8's full gate (differential per declaration against the reference engine on tutorial, bugs, the 230
tests, Init, Std, reachability, the Cedar slice; tier, convention and audit lanes; the real driver) with every site
lazy (mode 47), with block sites (43) and with the first form (11): PASS WITH DECLINES each, SOUNDNESS 0,
COMPLETENESS 0, 0 differences in every lane, the declines being the tree's eager-reduction declines. The hostile
suite of the soundness review with mode 47 on every machine: 32 switch sets x 329 exports, 0 unexpected verdicts.
Own hostile cases: `U2ValuesTest`, 6 tests. The recommended mode (235) passes the same gate; with it and with the mode off the module's default test group is green (451 tests).

**Recommendation.** Adopt a restricted form in stages (section 11): stage 0 the plumbing (no behaviour change);
stage 1 the staging switch `lazyid` = lazy `MKAPP` of blocks in large sessions, classes K / M / C, adaptive sites;
stage 2 complete classes M and C until the completeness test reads +0.000 %, then widen; stage 3 the tier
("reserve, do not write", with T3's CALL). Keep the present model for closures, suspensions and frames.

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
| M (memo keys) | `WHNF`, `CORE`, `DELTA` of a private application whose memo word is empty, and `VTYPE` of a private application | the application | the memo (`WHNF`, `CORE`, `UNF`, `TYPE`) is the hit that makes repeated reduction and inference free; it belongs to the canonical record | **measured: +25.6 % ticks on reachability with lazy code blocks, +143 % with every site lazy** (modes 3 and 7 of section 10.1, WHNF alone); without CORE / DELTA / VTYPE: +1.8 % ticks on Init where WHNF alone was identified, +1.1 % with them |
| M | (design; not in the prototype) the two flag memos: `ISPROP` (`PROPT`) and `PIRR` (`PROOF`) of a private application | the application | the flags are memo words too | the type of a duplicate is asked "is it a proposition" again: the visible residue of the completeness test with every site lazy (10.1: `ISPROP` +226 k on Init) |
| C (comparison) | `CONV(a, b)` after the test `a == b` (prototype: bit 32) | both sides (design, not in the prototype: also the argument pairs that `CARGS` and the lazy-delta step compare by ref without entering `CONV`) | row 2 of section 1: the pair table is keyed by refs; and it restores `a == b` for equal values | **measured: +38 % ticks on the Cedar slice with every site lazy** (mode 15 against 47); nothing measurable when only code blocks are lazy |
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
| 8 | MEMO | class M of 2.4: `WHNF`, `CORE`, `DELTA`, `VTYPE` identify a private application before they compute |
| 32 | CONV | class C of 2.4 |
| 64 | ADAPT | adaptive sites (below) |
| 128 | BIG | lazy only in a large session (below) |
| 16 | STAT | counters (process-wide, printed at exit) |

The modes that were measured: **1** (data built by blocks), **11** = 1 + 2 + 8 (everything blocks build, memo
shared), **43** = 11 + 32, **47** = 43 + 4 (every site), **107** / **111** = 43 / 47 with adaptive sites, **171** /
**235** = 43 / 107 in large sessions only, and the deliberately broken ones, **3** and **7** / **15** (redexes without class M, every site without class C), which exist
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

**Lazy only in a large session (bit 128).** A probe that is not made is worth a cache miss where the tables have
outgrown the cache and next to nothing where they have not - and in a small session the bit tests of the
key-forming loops and the duplicate records are all that is left (10.2: the Cedar slice at one worker, +2 to +4 %).
With this bit a lazy site interns as ever until the session holds 2^17 records (`rlvm.u2.big`; 4 MiB of records, a
`vtab` of 1 MiB); from then on it is lazy. A warm session is reset at a footprint of 1 MiB, so only a single
declaration that is itself large gets there: the mode is inert on library corpora (the Cedar slice: 0.01 M private
applications, every counter the mode-off value) and keeps 88 % of the probes it avoids on reachability (2.67 M of
3.04 M never identified), whose four giant declarations make 93 % of its records (X1 C8). This is the session-level
storage strategy: the store changes its discipline when its contents no longer fit the representation that was
cheap for them.

## 4. Representation

### 4.1 In the stepper (as built)

```
vh[8r .. 8r+7]   w0 = NEU | flags | n     a = head     b = spine run     c = 0, or -(site + 1), or the forward (> 0)
                 m0 .. m3 = memo words: its own until it is identified (class M identifies before they are used)
rs[run ..]       header (SPINE, n, OPEN) | 0 | a1 .. an        a spine never was in rtab
privBits         bit r = 1: record r is in no table (private, or a forwarding duplicate)
registers, environment slots, scratch, spines of private records, memo VALUES:   the ref r, as for any value
```

There is no young region and no second kind of ref: a private application lives where every record lives, and what
distinguishes it is one bit that only the key-forming loops, the memoised judgments and `CONV` read.

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
  `internRun`; `Scope.open`; `Reduce.whnf`, `core`, `delta`, `Infer.vtype` (bit 8); `Conv.conv` (bit 32). A forced binding is written back into
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

**Reussir's bits, once more.** X1 found that the one-bit "unique" of Reussir has nothing to bite on in a store where
every cell is shared by definition. Private applications are the first population of this machine that is NOT
shared by definition - so the question comes back in a narrower form: could a private application whose only holder
is the instruction that just took it apart (`FIELDS` after `SWITCH`) be rewritten in place for the next
construction? In the stepper: no - "only holder" is not known (registers, environment slots and memo words hold
refs without counting them), and what would be saved is a bump of ten words, not a probe. In compiled code the
question answers itself: a virtual object that does not escape is never written at all (section 5), which is reuse
with the compiler's ownership analysis instead of bits. That is where the owner's idea lands in this design.

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
   the lossy caches cannot make ticks tier-dependent. The reservation itself is two additions, the private bit and
   one store (word 0 = kind 0, so that a walker of the slab - an audit, a digest, the session-end census - sees
   "no record" at a reserved id that was never written).
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

- **Ticks, and the completeness test of the force points.** With classes K, M and C complete, every
  identity-dependent mechanism of section 1 sees canonical refs: the key of a memo is canonical (K, M), both sides
  of a comparison are canonical and so are all their components (C, V2). The machine then makes exactly the
  judgments it makes today; only the probes of values that never reach a key, a memo or a comparison are not made.
  So **the tick count with the mode on must EQUAL the tick count with the mode off** - a tick difference is a path
  that reads a memo word or compares without identifying (never a verdict difference: it is lost sharing). Two
  things blur this in a warm session and must be taken out of the test: the machine's session cap is reached
  earlier (more records), and a reset forgets every memo; and adaptive sites make the set of private records depend
  on the history. The test is therefore run with one session per declaration (`default,-warm`): 10.1 has the
  result and the residue of the prototype.
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
  extension and growth; flags; deep nests; clean tables after a reset); (7) the completeness test, as a gate once
  classes M and C are complete (stage 2); (8) the unit tests that assert K0 = K1 BY REF (`M3TierTest`, `M7TierTest`,
  `M7TierHookTest`: decision 6 "both tiers give the same record") compare `Values.ident` of both sides in a mode
  whose sessions are lazy: a block's application equals the generic tier's after identification, not at birth
  (10.3). With the large-session trigger they pass unchanged, because a unit test's session never gets there.

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
main files, 667 added and 9 removed lines, one test class). One binary for before and after: the mode is the system
property `rlvm.u2`; the pristine twin (`work/u2/base`) is measured beside it to show that the mode-off prototype is
the tree it was copied from. Switches everywhere: the default set of that tree (`gated, pchk, expect, warm, code,
casetrees, bodies, directrules, same, match, joint, lazycases, lazyeval`).

### 10.1 Counts (JVM launcher, ONE worker, statistics file; `work/u2/counts/`, table by `work/u2/scripts/tab.py`)

One run per row (jar `16542003a4cc`; rows 107 and 111: the jar with adaptive sites, `879b493003e3`, which is the
same code with bit 64 off). Deltas are against the mode-off row of the corpus. "never identified" = private
applications that were in no table when their session ended: each is a probe of `vtab` (hash, table load,
comparison or insert) and a table entry that the mode-off machine paid for and nothing ever used.

| Corpus | mode | ticks | records | run words | bytes in use at the resets, MiB | session resets | private applications | entered as themselves | duplicates | **never identified** (share of the private ones; of the mode-off records) | accepted / declined |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| reachability | 0 | 9.10 M | 4.83 M | 24.45 M | 263 | 68 | - | - | - | - | 4216 / 16 |
|  | 1 | 9.10 M (+0.0%) | 5.91 M (+22.6%) | 29.30 M (+19.9%) | 313 | 68 | 2.06 M | 0.11 M | 0.53 M | **1.41 M** (69%; 29%) | 4216 / 16 |
|  | 3 | 9.14 M (+0.4%) | 6.56 M (+36.0%) | 32.46 M (+32.8%) | 341 | 68 | 3.90 M | 0.32 M | 0.30 M | **3.28 M** (84%; 68%) | 4216 / 16 |
|  | 11 | 9.11 M (+0.1%) | 6.55 M (+35.8%) | 32.41 M (+32.6%) | 341 | 68 | 3.89 M | 0.53 M | 0.31 M | **3.05 M** (78%; 63%) | 4216 / 16 |
|  | 43 | 9.10 M (-0.0%) | 6.55 M (+35.8%) | 32.40 M (+32.6%) | 341 | 68 | 3.89 M | 0.53 M | 0.32 M | **3.04 M** (78%; 63%) | 4216 / 16 |
|  | 47 | 9.16 M (+0.7%) | 6.85 M (+42.1%) | 33.75 M (+38.1%) | 355 | 79 | 5.20 M | 1.39 M | 0.58 M | **3.23 M** (62%; 67%) | 4216 / 16 |
|  | 107 | 9.10 M (-0.0%) | 6.33 M (+31.2%) | 31.52 M (+28.9%) | 331 | 68 | 3.20 M | 0.07 M | 0.10 M | **3.03 M** (95%; 63%) | 4216 / 16 |
|  | 111 | 9.10 M (+0.0%) | 6.37 M (+32.1%) | 31.70 M (+29.7%) | 333 | 69 | 3.49 M | 0.16 M | 0.14 M | **3.19 M** (91%; 66%) | 4216 / 16 |
| Cedar slice | 0 | 9.99 M | 5.13 M | 56.32 M | 410 | 201 | - | - | - | - | 8053 / 22 |
|  | 1 | 9.99 M (+0.1%) | 5.21 M (+1.6%) | 56.65 M (+0.6%) | 414 | 203 | 0.17 M | 0.03 M | 0.05 M | **0.09 M** (53%; 2%) | 8053 / 22 |
|  | 11 | 10.00 M (+0.1%) | 5.34 M (+4.1%) | 57.37 M (+1.9%) | 420 | 204 | 0.61 M | 0.20 M | 0.11 M | **0.30 M** (49%; 6%) | 8053 / 22 |
|  | 43 | 9.98 M (-0.1%) | 5.34 M (+4.1%) | 57.37 M (+1.9%) | 420 | 204 | 0.61 M | 0.20 M | 0.12 M | **0.29 M** (47%; 6%) | 8053 / 22 |
|  | 47 | 10.15 M (+1.6%) | 7.00 M (+36.5%) | 64.76 M (+15.0%) | 500 | 231 | 3.05 M | 0.94 M | 1.69 M | **0.42 M** (14%; 8%) | 8053 / 22 |
|  | 107 | 9.99 M (+0.1%) | 5.25 M (+2.5%) | 57.01 M (+1.2%) | 416 | 204 | 0.36 M | 0.05 M | 0.04 M | **0.28 M** (76%; 5%) | 8053 / 22 |
|  | 111 | 10.02 M (+0.3%) | 5.39 M (+5.1%) | 57.63 M (+2.3%) | 423 | 207 | 0.63 M | 0.16 M | 0.17 M | **0.31 M** (48%; 6%) | 8053 / 22 |
| Init | 0 | 39.33 M | 16.30 M | 75.46 M | 904 | 1538 | - | - | - | - | 57774 / 328 |
|  | 1 | 39.56 M (+0.6%) | 17.13 M (+5.0%) | 78.95 M (+4.6%) | 944 | 1567 | 1.47 M | 0.13 M | 0.43 M | **0.92 M** (62%; 6%) | 57774 / 328 |
|  | 11 | 40.07 M (+1.9%) | 18.25 M (+11.9%) | 84.24 M (+11.6%) | 999 | 1603 | 5.14 M | 1.97 M | 1.12 M | **2.06 M** (40%; 13%) | 57774 / 328 |
|  | 43 | 39.77 M (+1.1%) | 18.24 M (+11.8%) | 84.18 M (+11.5%) | 996 | 1601 | 5.13 M | 1.97 M | 1.21 M | **1.95 M** (38%; 12%) | 57774 / 328 |
|  | 47 | 41.66 M (+5.9%) | 24.90 M (+52.7%) | 113.16 M (+50.0%) | 1314 | 1855 | 16.50 M | 6.53 M | 7.02 M | **2.96 M** (18%; 18%) | 57774 / 328 |
|  | 107 | 39.57 M (+0.6%) | 17.30 M (+6.1%) | 80.06 M (+6.1%) | 951 | 1575 | 2.56 M | 0.30 M | 0.43 M | **1.84 M** (72%; 11%) | 57774 / 328 |
|  | 111 | 39.74 M (+1.0%) | 17.93 M (+10.0%) | 82.85 M (+9.8%) | 979 | 1594 | 4.22 M | 0.94 M | 0.95 M | **2.33 M** (55%; 14%) | 57774 / 328 |
| Mathlib slice (`Mathlib.AlgebraicGeometry.Scheme.cone`, 21,127 units) | 0 | 23.00 M | 9.17 M | 42.32 M | 507 | 517 | - | - | - | - | 21099 / 56 |
|  | 1 | 23.09 M (+0.4%) | 9.50 M (+3.6%) | 43.68 M (+3.2%) | 524 | 527 | 0.67 M | 0.04 M | 0.19 M | **0.44 M** (65%; 5%) | 21099 / 56 |
|  | 11 | 23.54 M (+2.4%) | 10.62 M (+15.8%) | 48.92 M (+15.6%) | 578 | 560 | 3.03 M | 0.97 M | 0.81 M | **1.25 M** (41%; 14%) | 21099 / 56 |
|  | 43 | 23.36 M (+1.6%) | 10.61 M (+15.6%) | 48.87 M (+15.5%) | 577 | 558 | 3.03 M | 0.98 M | 0.87 M | **1.17 M** (39%; 13%) | 21099 / 56 |
|  | 47 | 24.35 M (+5.9%) | 14.76 M (+60.9%) | 68.30 M (+61.4%) | 780 | 694 | 9.99 M | 3.85 M | 4.52 M | **1.62 M** (16%; 18%) | 21099 / 56 |
|  | 107 | 23.08 M (+0.4%) | 9.58 M (+4.5%) | 44.32 M (+4.7%) | 527 | 528 | 1.26 M | 0.21 M | 0.20 M | **0.85 M** (68%; 9%) | 21099 / 56 |
|  | 111 | 23.24 M (+1.1%) | 10.01 M (+9.1%) | 46.16 M (+9.1%) | 547 | 549 | 2.03 M | 0.53 M | 0.49 M | **1.02 M** (50%; 11%) | 21099 / 56 |
| Std | 0 | 64.28 M | 27.87 M | 134.94 M | 1570 | 2280 | - | - | - | - | 99893 / 406 |
|  | 1 | 64.62 M (+0.5%) | 29.13 M (+4.5%) | 140.26 M (+3.9%) | 1633 | 2317 | 2.33 M | 0.26 M | 0.66 M | **1.41 M** (60%; 5%) | 99893 / 406 |
|  | 11 | 65.36 M (+1.7%) | 30.84 M (+10.7%) | 148.35 M (+9.9%) | 1716 | 2382 | 7.97 M | 3.18 M | 1.72 M | **3.07 M** (39%; 11%) | 99893 / 406 |
|  | 43 | 64.93 M (+1.0%) | 30.83 M (+10.6%) | 148.30 M (+9.9%) | 1712 | 2378 | 7.97 M | 3.19 M | 1.87 M | **2.90 M** (36%; 10%) | 99893 / 406 |
|  | 47 | 67.53 M (+5.0%) | 41.41 M (+48.6%) | 196.10 M (+45.3%) | 2223 | 2741 | 26.18 M | 10.51 M | 11.25 M | **4.42 M** (17%; 16%) | 99893 / 406 |
|  | 107 | 64.64 M (+0.6%) | 29.33 M (+5.2%) | 141.59 M (+4.9%) | 1639 | 2326 | 3.86 M | 0.56 M | 0.61 M | **2.69 M** (70%; 10%) | 99893 / 406 |
|  | 111 | 64.98 M (+1.1%) | 30.28 M (+8.7%) | 146.07 M (+8.2%) | 1684 | 2358 | 6.21 M | 1.49 M | 1.40 M | **3.33 M** (54%; 12%) | 99893 / 406 |

What the rows say:

- **Verdicts**: accepted and declined are the mode-off numbers in every row (the declines are the tree's own
  eager-reduction declines); the machine rejects nothing in these corpora.
- **Closed evaluation (reachability)**: with block sites lazy (mode 43) 3.04 M applications per run are never
  identified - 78 % of what blocks build lazily and 63 % of ALL records of the mode-off run - at +0.0 % ticks. The
  price is 1.72 M more records (+36 %): the constructions that interning would have answered with an existing record
  (T3's 48 % "found") are records now, and 0.85 M of them are identified later after all. Adaptive sites (107) keep
  the 3.03 M and cut the wasted constructions from 0.85 M to 0.17 M.
- **Library corpora**: the population is smaller and more of it is identified. Never identified with block sites
  (43): 0.29 M on the Cedar slice (6 % of its records), 1.95 M on Init (12 %), 1.17 M on the Mathlib slice (13 %),
  2.90 M on Std (10 %); 47-64 % of the lazily built applications ARE identified there, mostly because INFER asks for
  their type (`VTYPE`, class M) or CONV compares them. With every site lazy and no adaptation (47) four of five
  private applications are identified (Cedar slice 86 %, Init 82 %): records +37 to +61 %, ticks +1.6 to +5.9 %:
  **every-site laziness without adaptation is a loss in counts on library corpora.** With adaptive sites (111) the
  same sites cost +5 to +10 % records and +0.3 to +1.1 % ticks and still avoid 0.31 M / 2.33 M / 1.02 M / 3.33 M
  probes.
- **What classes M and C are worth** (first build, before the scrutinee rule; `work/u2/counts-v1/`): redexes lazy
  without class M (mode 3): +25.6 % ticks on reachability; every site lazy without M (mode 7): +143 % ticks, +222 %
  records; every site lazy without class C (mode 15): +38 % ticks and +174 % pair entries on the Cedar slice. The
  scrutinee rule alone brings mode 3 from +25.6 % to +0.4 % on reachability: nearly every memo hit of a block-built
  redex is the hit of `MKAPP ; WHNF`.
- **Footprint**: bytes in use at the resets +30 % on reachability (263 -> 341 MiB), +2 % on the Cedar slice, +10 %
  on Init (mode 43); session resets +0 to +4 % (the 1 MiB cap is reached earlier).

**The completeness test of section 7** (one session per declaration, `-X machine.switches=default,-warm`;
`work/u2/counts-nowarm/`; ticks against the mode-off run of the same configuration):

| Corpus | mode 11 (K, M) | mode 43 (K, M, C) | mode 47 (every site) | mode 111 (every site, adaptive) | where the residue is (call events that differ most) |
|---|--:|--:|--:|--:|---|
| reachability (9,440,509 ticks) | +0.129 % | **+0.023 %** (+2,132) | +0.420 % | +0.070 % | 43: `CARGS` +851, `LSTEP` +628; 47: `EVAL` +24 k, `PIRR`, `ISPROP` |
| Cedar slice (10,466,918) | +0.250 % | **+0.060 %** (+6,270) | +1.413 % | +0.193 % | 43: `CARGS` +2.4 k, `LSTEP` +1.2 k; 47: `EVAL` +74 k, `PIRR` +18 k, `ISPROP` +18 k |
| Init (50,797,296) | - | **+0.197 %** (+99,831) | +2.297 % | - | 43: `CARGS` +61 k, `LSTEP` +18 k; 47: `EVAL` +650 k, `ISPROP` +226 k |

Reading: with classes K, M (WHNF, CORE, DELTA, VTYPE) and C the block-site mode is within 0.02-0.2 % of the tick
count of today's machine - what is left is argument pairs that `CARGS` and the lazy-delta step compare by ref
without going through `CONV`'s entry (class C is applied there only), and the two flag memos `PROOF` / `PROPT`
(`PIRR`, `ISPROP`), which the prototype does not treat as memo keys; with every site lazy the flag memos are the
visible residue (`ISPROP` re-inferred per duplicate type, each with its `EVAL`). They are the two rows "M (design)"
of 2.4: a complete implementation has four more force points and a tick count equal to today's.

**Session-end census** (counting mode; every record of the ended sessions; from the mode-111 runs - suspensions and
closures are built as in the mode-off machine):

| Corpus | plain suspensions | of them never used as a memo (neither `VAL` nor `TYPE`) | LAM | PI + PII | applications | with an ACTIVE head | of those never reduced (no `WHNF` / `CORE` / `UNF` memo) |
|---|--:|--:|--:|--:|--:|--:|--:|
| reachability | 0.53 M | 0.18 M (34 %) | 0.36 M | 0.12 M | 4.85 M | 2.70 M | 0.86 M (32 %) |
| Cedar slice | 1.75 M | 0.03 M (1.7 %) | 0.46 M | 1.29 M | 1.46 M | 0.87 M | 0.36 M (41 %) |
| Init | 4.29 M | 0.24 M (5.7 %) | 0.85 M | 1.36 M | 9.88 M | 6.48 M | 1.81 M (28 %) |
| Mathlib slice | 2.22 M | 0.05 M (2.4 %) | 0.43 M | 0.75 M | 5.46 M | 3.99 M | 1.21 M (30 %) |
| Std | 7.29 M | 0.37 M (5.1 %) | 1.73 M | 2.63 M | 15.55 M | 10.09 M | 3.16 M (31 %) |

This sizes stage D (lazy suspensions): a suspension that is forced, tabled or inferred needs its identity (its memo
is the point of it), so the population a lazy suspension could spare is the second column: 2-6 % of the suspensions
on library corpora and 0.18 M records on reachability (two probes each, against 3.0 M application probes). **Lazy
closures and suspensions are not worth their mechanism (private frames) today.**

### 10.2 Warm wall clock

Tool: `rlvm.truffle.WarmMain` (T3's: one process, ONE engine kept over all passes, pass 1 cold, every later pass
checks all units again with fresh sessions on W worker threads; no admission pipeline, no pipelined loading), run by
`work/u2/scripts/warmrun.py` (T3's `warmrun.py` with this directory's paths), every window inside `bench/quiet.py`
(`work/perf-history/quiet.log`: `work/u2/scripts/q1.sh` 19:40Z, `q2.sh` 19:56Z, `q3.sh` 20:12Z, `qa.sh` 20:37Z, `nb.sh` 21:05Z).
JVM = the launcher's flags (SerialGC, C2, `-Xmx6g`); native = CE native image `-O3 --gc=serial` of `WarmMain` over the same jars (`work/u2/scripts/build-native.sh`). Two processes per configuration, back to back with the others
of the window; the number is the median of the later half of the warm passes of both processes, the range is over
those passes; "against k0" compares with the SAME jar with the mode off, in the SAME window. `base` = the pristine
twin's jar. Rows: `work/u2/warm.tsv`; complete outputs: `work/u2/wlogs/`; table: `work/u2/scripts/wtab.py`.
Jars: J1 `8735622ae972` (scrutinee rule, class M = WHNF only), J2 / J3 `16542003a4cc` (class M complete), JA and NB
`f62325f62b53` (+ adaptive sites, + the large-session trigger; the modes 0 / 43 / 47 are the same code in all three).

| window | corpus | platform | workers | configuration | processes | cold pass ms | warm wall ms: median (range) | against k0 | workers' CPU ms: median | against k0 | ranges disjoint from k0's |
|---|---|---|--:|---|--:|--:|--:|--:|--:|--:|---|
| J1 | reach | jvm | 1 | base | 2 | 2972 | 997 (902-1084) | -3.8% | 990 | -3.2% | no |
| J1 | reach | jvm | 1 | k0 | 2 | 2776 | 1036 (1004-1074) | - | 1023 | - | - |
| J1 | reach | jvm | 1 | u1 | 2 | 2829 | 1006 (901-1029) | -2.9% | 982 | -4.0% | no |
| J1 | reach | jvm | 1 | u11 | 2 | 2847 | 902 (881-927) | -12.9% | 887 | -13.3% | yes |
| J1 | reach | jvm | 1 | u47 | 2 | 2764 | 799 (770-812) | -22.9% | 776 | -24.1% | yes |
| J2 | reach | jvm | 8 | base | 2 | 3053 | 569 (505-627) | +9.0% | 1084 | +5.7% | no |
| J2 | reach | jvm | 8 | k0 | 2 | 2710 | 522 (505-569) | - | 1026 | - | - |
| J2 | reach | jvm | 8 | u11 | 2 | 2693 | 492 (452-513) | -5.7% | 915 | -10.9% | no |
| J2 | reach | jvm | 8 | u43 | 2 | 2804 | 480 (450-509) | -8.1% | 899 | -12.4% | no |
| J2 | reach | jvm | 8 | u47 | 2 | 2815 | 479 (441-528) | -8.2% | 893 | -13.0% | no |
| J3 | cedarslice | jvm | 1 | base | 2 | 3995 | 1117 (1059-1222) | +1.7% | 1116 | +3.1% | no |
| J3 | cedarslice | jvm | 1 | k0 | 2 | 3947 | 1098 (1056-1145) | - | 1082 | - | - |
| J3 | cedarslice | jvm | 1 | u11 | 2 | 4017 | 1124 (1099-1188) | +2.4% | 1123 | +3.8% | no |
| J3 | cedarslice | jvm | 1 | u43 | 2 | 3924 | 1142 (1114-1153) | +4.0% | 1127 | +4.2% | no |
| J3 | cedarslice | jvm | 1 | u47 | 2 | 3968 | 1198 (1166-1235) | +9.1% | 1194 | +10.3% | yes |
| J3 | cedarslice | jvm | 8 | base | 2 | 3941 | 211 (183-252) | +1.4% | 1184 | +0.4% | no |
| J3 | cedarslice | jvm | 8 | k0 | 2 | 3816 | 208 (181-237) | - | 1179 | - | - |
| J3 | cedarslice | jvm | 8 | u11 | 2 | 3868 | 204 (185-260) | -1.9% | 1256 | +6.5% | no |
| J3 | cedarslice | jvm | 8 | u43 | 2 | 3876 | 212 (189-249) | +1.9% | 1195 | +1.4% | no |
| J3 | cedarslice | jvm | 8 | u47 | 2 | 3809 | 220 (191-252) | +6.0% | 1280 | +8.6% | no |
| JA | cedarslice | jvm | 1 | k0 | 2 | 3923 | 1104 (1088-1124) | - | 1094 | - | - |
| JA | cedarslice | jvm | 1 | u43 | 2 | 4045 | 1151 (1092-1171) | +4.3% | 1128 | +3.2% | no |
| JA | cedarslice | jvm | 1 | u107 | 2 | 3973 | 1124 (1101-1156) | +1.8% | 1119 | +2.3% | no |
| JA | cedarslice | jvm | 1 | u171 | 2 | 4019 | 1103 (1012-1155) | -0.1% | 1094 | +0.0% | no |
| JA | cedarslice | jvm | 8 | k0 | 2 | 3938 | 214 (195-255) | - | 1201 | - | - |
| JA | cedarslice | jvm | 8 | u107 | 2 | 3870 | 215 (193-262) | +0.7% | 1241 | +3.3% | no |
| JA | cedarslice | jvm | 8 | u171 | 2 | 3889 | 201 (188-212) | -5.8% | 1183 | -1.5% | no |
| JA | reach | jvm | 1 | k0 | 2 | 2815 | 1066 (940-1126) | - | 1048 | - | - |
| JA | reach | jvm | 1 | u43 | 2 | 2828 | 930 (849-962) | -12.8% | 916 | -12.6% | no |
| JA | reach | jvm | 1 | u107 | 2 | 2744 | 914 (795-963) | -14.3% | 900 | -14.1% | no |
| JA | reach | jvm | 1 | u111 | 2 | 2756 | 890 (833-934) | -16.5% | 877 | -16.4% | yes |
| JA | reach | jvm | 1 | u171 | 2 | 2744 | 891 (855-954) | -16.5% | 874 | -16.6% | no |
| JA | reach | jvm | 1 | u235 | 2 | 2630 | 888 (860-934) | -16.7% | 863 | -17.7% | yes |
| JA | reach | jvm | 8 | k0 | 2 | 2802 | 511 (492-553) | - | 993 | - | - |
| JA | reach | jvm | 8 | u171 | 2 | 2751 | 495 (435-537) | -3.1% | 926 | -6.7% | no |
| JA | reach | jvm | 8 | u235 | 2 | 2636 | 460 (439-505) | -10.0% | 888 | -10.5% | no |
| NB | reach | native | 1 | k0 | 2 | 2294 | 1345 (1273-1419) | - | 1345 | - | - |
| NB | reach | native | 1 | u43 | 2 | 2280 | 1271 (1242-1305) | -5.5% | 1271 | -5.5% | no |
| NB | reach | native | 1 | u171 | 2 | 2190 | 1235 (1161-1333) | -8.2% | 1234 | -8.2% | no |
| NB | reach | native | 1 | u235 | 2 | 2183 | 1185 (1161-1245) | -11.9% | 1184 | -12.0% | yes |
| NB | reach | native | 1 | u239 | 2 | 2219 | 1271 (1167-1373) | -5.5% | 1271 | -5.5% | no |
| NB | reach | native | 8 | k0 | 2 | 2074 | 619 (587-652) | - | 1256 | - | - |
| NB | reach | native | 8 | u43 | 2 | 2221 | 659 (622-696) | +6.5% | 1251 | -0.4% | no |
| NB | reach | native | 8 | u171 | 2 | 2145 | 641 (611-690) | +3.6% | 1226 | -2.4% | no |
| NB | reach | native | 8 | u235 | 2 | 2138 | 638 (588-692) | +3.1% | 1244 | -1.0% | no |
| NB | reach | native | 8 | u239 | 2 | 2107 | 613 (580-649) | -0.9% | 1194 | -4.9% | no |
| NB | cedarslice | native | 8 | k0 | 2 | 2896 | 231 (220-252) | - | 1463 | - | - |
| NB | cedarslice | native | 8 | u43 | 2 | 2950 | 244 (233-268) | +5.8% | 1557 | +6.4% | no |
| NB | cedarslice | native | 8 | u171 | 2 | 2908 | 232 (217-253) | +0.4% | 1471 | +0.5% | no |

Reading (a difference is called resolved only where the two ranges do not overlap; with two processes per row that
is thin, and the scatter between two processes of one configuration is up to 8 % on the JVM - J1 and J2 put the
pristine jar 4 % below and 9 % above the mode-off prototype):

- **Reachability, JVM.** One worker: block sites lazy (43) -13 % in two windows (J1 mode 11: 902 against 1036 ms,
  ranges disjoint; JA: 930 against 1066); in large sessions only (171) -16.5 %; with adaptive sites (235) **-16.7 %
  (888 against 1066 ms, ranges disjoint)**; every site (47, J1) -23 %, every site adaptive (111, JA) -16.5 %. Eight
  workers: 43: -8.1 % (480 against 522 ms; the workers' CPU -12.4 %); **235: -10.0 % (460 against 511 ms; CPU
  -10.5 %)**; the ranges touch (439-505 against 492-553). With eight workers the wall clock of this corpus is its
  largest declaration, so the workers' CPU is the better witness of the mechanism.
- **Cedar slice, JVM.** Block sites lazy in every session (43): +4.0 % and +4.3 % at one worker in two windows
  (ranges overlapping both times), +1.9 % at eight; every site lazy (47): **+9.1 % at one worker (resolved)**,
  +6.0 % at eight. In large sessions only (171): -0.1 % at one worker, -5.8 % at eight (inside the scatter:
  188-212 against 195-255) - and its counters ARE the mode-off counters (10.1), so "no change" is by construction.
- **Where the gain comes from, and where the loss.** The same mechanism on two corpora: reachability's records are
  made in four sessions of millions of records (intern tables of tens of MiB; NOTES-M1 5.3: four fifths of a probe
  is the cache miss of the table slot), the Cedar slice's in sessions that are reset at 1 MiB (tables in the
  cache). A probe that is not made is worth a cache miss in the first and almost nothing in the second, where the
  bit tests of the key-forming loops and the duplicate records are what remains. Hence bit 128.
- **Reachability, native image** (NB). One worker: 43: -5.5 % (1271 against 1345 ms, ranges overlapping), 171:
  -8.2 %, **235: -11.9 % (1185 against 1345 ms; 1161-1245 against 1273-1419: disjoint)**. **Eight workers: no
  gain** - 43: +6.5 %, 171: +3.6 %, 235: +3.1 %, 239: -0.9 %, every one inside the scatter of the mode-off rows
  (587-652 ms); the workers' CPU is -0.4 to -4.9 %. On the JVM the same modes are -8 to -10 % at eight workers
  with the workers' CPU -10 to -12 %. So the owner's first number - warm wall clock with eight workers - moves on
  the JVM (at the edge of the scatter of two processes) and does not move in the image; the gain that is resolved
  on both platforms is the one-worker gain. Why eight workers of the image lose what one worker gains is not
  known (candidates, not measured: the record and run slabs grow by a third and are grown by copying, which costs
  the serial collector of the image more when eight threads allocate; memory bandwidth shared by eight workers
  writing a third more).
- **Cedar slice, native image, eight workers**: 43: +5.8 % (workers' CPU +6.4 %), 171: +0.4 %: as on the JVM.
- **Not resolved by these windows**: the difference between 43 and 171 / 235 on reachability (171 should be 43
  minus the lazy constructions of small sessions; it measures 4 points better at one worker and 5 worse at eight:
  scatter); why every-site laziness is that much better at one worker on reachability (J1) although it identifies
  more records than block-site laziness.

### 10.3 Gates

All on the private tree, reports under the private root `work/u2/root/work/status/` (never the project's
`work/status/`), M8's harness started by `work/u2/scripts/m8.sh` (`rlvm.machine.M8Main gate -` with `-Drlvm.u2=N`,
so that EVERY machine of the gate's JVM - all six lanes, the generic tier lane and the driver of part 5 - runs the
mode), switch set = the tree's default.

| Gate | Mode | Result |
|---|---|---|
| M8's full gate (`machine-gate-default-u47-u47.md`, 750 s, final classes) | **47** (every site lazy, K + M + C) | **PASS WITH DECLINES.** (1) differential per declaration against the reference engine: SOUNDNESS 0, COMPLETENESS 0 on tutorial (395 units), bugs (1,428), all 230 tests (17,493 units; 15,800 accepted, 40 rejected, 5 declined), Init (57,774 accepted, 164 declined), Std (99,893 / 203), reachability (4,216 / 8), the Cedar slice (8,053 / 11); (2) tier equivalence with the generic tier: 0 declarations with another outcome, 0 with another reason; `M8Tiers` values 700 + 5,944 results, 0 different; (3) convention equivalence, host depth 0 and unbounded against 128 (outcome, reason, ticks, call events, trace, every record word, run word and pair): 0 different; (4) audit lanes `poison,scopeaudit,maskaudit` and `memocheck,shortcheck`: 0 different outcomes, 0 E_AUDIT; (5) the real driver: 230 / 230 verdicts right, the four corpora accepted with 4 workers, MACHINE-INCOMPLETE 0, WARM-ANOMALY 0, disagreements 0. Every decline is the tree's own eager-reduction decline (S-F5's rule) |
| the same gate (`machine-gate-default-u11-u11.md`, 856 s; classes of 19:36Z: scrutinee rule, class M = WHNF only) | **11** (block sites, K + M) | **PASS WITH DECLINES**, the same zeros in all five parts, the same declines |
| the same gate, final classes | **43** (block sites, K + M + C: the form recommended for stage 1) | **PASS WITH DECLINES** (`machine-gate-default-u43-u43.md`, 915 s): differential SOUNDNESS 0 / COMPLETENESS 0 on every corpus (the accepted / rejected / declined counts of the mode-47 row), tier equivalence 0 / 0 and 0 different values, convention equivalence 0 different, audits 0 / 0, pipeline 230 / 230 right, MACHINE-INCOMPLETE 0, WARM-ANOMALY 0 |
| M8's full gate (`machine-gate-default-u235-u235.md`, 586 s; the classes the timings were taken with) | **235** (block sites, K + M + C, adaptive sites, large sessions only: stage 1) | **PASS WITH DECLINES**, the same zeros in all five parts; the ticks of reachability (9,104,125), Init and Std in its differential part are the mode-off ticks |
| the same gate on the tree of the patches (force points outlined; `machine-gate-default-u235r-u235r.md`) | **235** | **PASS WITH DECLINES** (698 s): the same zeros in all five parts, and the tick totals of its differential part are those of the row above in every corpus (tutorial 4,705, bugs 86,551, all 230 tests 264,223,400, Init 39,328,357, Std 64,280,298, reachability 9,104,125, Cedar slice 9,987,067): the outlining changed nothing that is counted |
| the hostile suite of the soundness review: `SHostileSlowTest` (32 switch sets x 329 exports = 10,528 judgments, each against the official verdict of the manifest; the strict `noKnownFindingIsLeft`) and `SHostileTest`, test JVM with `-Drlvm.u2=47` (`work/u2/logs/hostile-u47.out`) | **47** | **0 unexpected verdicts, 0 known findings open, 0 declines of the machine** under every switch set |
| the hostile suite on the tree of the patches, `-Drlvm.u2=235 -Drlvm.u2.big=8` (threshold 2^8 records, so that the mode engages in exports of a few hundred records; `work/u2/logs/hostileR-u235.out`) | **235** | 32 switch sets x 329 exports: **0 unexpected verdicts, 0 declines**; `SHostileTest` 6 / 6 |
| `U2ValuesTest` (own hostile cases, 6 tests; patch 0007 as `M1LazyIdTest`) | 3, 15, 47, 3 + 64 | green: (a) 6,000 random constructions per seed - lazy, interned, extended, captured into frames, across table growth, with and without adaptive sites: after identification one canonical id per structure and one structure per canonical id, no private component in any key, `vtabUsed` = table entries = records that are not private, clean tables after the reset; (b) flags of private applications = flags of interned ones; (c) nests 300,000 deep identified without host recursion, the second nest a duplicate of the first at every level; (d) twins are convertible and near twins (one innermost argument differs, 3,000 levels down) are not, with and without class C, with and without `same`; (e) twin redexes are reduced once (class M); (f) two spellings of one binder type have one family of free variables |
| differential, first build, mode 15 (every site, no class C): tutorial 395 / 395, bugs 1,427 / 1,428 | 15 | no SOUNDNESS, no COMPLETENESS |
| the module's default test group (449 tests of M1 .. M8, S; `work/u2/scripts/suite.sh`) with `-Drlvm.u2=N` for EVERY machine of the test JVM | 0 / 43 / 47 / 235 | mode 0: **451 tests, 0 failures, 4 skipped** on the tree of the patches (before the force points were outlined: 1 failure, `StepSizeTest` - six methods had grown beyond the 325 bytecodes of H10; a convention failure of the first form of the patch, fixed by outlining, counters identical: `work/u2/counts-final/`). Mode 235: **451 / 0 / 4** (unit-test sessions never reach the threshold). Mode 43 (first form): `StepSizeTest` + 5 tests of `M3TierTest`, `M7TierTest`, `M7TierHookTest` that assert K0 = K1 by REF: a block's application is equal to the generic tier's after identification, not at birth - in a mode whose sessions are lazy these tests need `Values.ident` on both sides. Mode 47: 19 failures in 10 classes, all of them assertions about identity or record counts AT CONSTRUCTION (`M1ValuesTest` "two constructions, one id", `M1RunsTest` / `M2EconomyTest` record counts, `M4ConvTest` the pair entry on the refs CONV was called with, `M3ReduceTest` a memo word on the record the test built): the list of tests that encode I-ID at birth |

How the gates treat what the mode changes: nothing in M8's gate compares the mode-on machine with the mode-off
machine; every comparison is inside the mode (lane against lane, lane against reference engine), where V6 makes
record words and ticks comparable word for word. The mode-on / mode-off comparison is the completeness test of
10.1 (ticks) and the equal accepted / rejected / declined counts of every counter run.

## 11. Recommendation and stages

**Adopt a restricted form, in stages; keep the present model for everything but applications.** The evidence: no
verdict needs canonical refs (sections 1, 8; three full gates and the hostile suite); applications are the one class
whose probes are mostly wasted (9, 10.1); the gain exists where sessions are large (reachability: -17 % warm wall
clock at one worker, -10 % at eight on the JVM) and the price where they are small (the Cedar slice: +4 % at one
worker when every session is lazy); a rule that is lazy only where it pays leaves the library corpora exactly as
they are.

| Stage | What | Owners (patch) | Evidence / condition to enter |
|---|---|---|---|
| **0. Plumbing, no behaviour change** | records may be outside `vtab`: `vtabUsed`, `addRecordFree`, `enterRecordAt`, growth and reset skip private bits; `Values.ident` and the force points of class K behind `privMade != 0` | M6 (0002), M1 (0001) | with no lazy site every counter and every word is today's (the mode-off rows of 10.1; the module's default test group with the mode off: 451 tests, 0 failures) |
| **1. `lazyid` as a staging switch, off in strict mode** = mode 235: `MKAPP` of code blocks lazy except the scrutinee of the next `WHNF`; classes K, M (WHNF, CORE, DELTA, VTYPE), C; adaptive sites; lazy only in sessions of 2^17 records and more | M7 (0003), M3 (0004), M4 (0005), M5 (0006), M1 (0001, 0007), M6 (the switch, `SWITCHES.md`, counters into the statistics) | reachability, JVM, warm: 888 against 1066 ms at one worker (-16.7 %, ranges disjoint), 460 against 511 ms at eight (-10.0 %; workers' CPU -10.5 %); 2.66 M of 8.4 M record-table probes per pass not made; ticks identical to the mode-off run (9,104,125); Cedar slice, Init: counters identical to the mode-off run (the sessions never reach the threshold), Cedar slice wall clock -0.1 % (mode 171). Gate: M8's full gate with mode 235 PASSES WITH DECLINES (`machine-gate-default-u235-u235.md`, 586 s: SOUNDNESS 0, COMPLETENESS 0, 0 differences in the tier, convention and audit lanes, pipeline 230 / 230; ticks of reachability, Init and Std in the gate = the mode-off ticks). Native image: -11.9 % at one worker (ranges disjoint), nothing at eight (+3.1 %, inside the scatter). To enter the default set: M8's gate on the main tree; **an eight-worker measurement with more than two processes per row on the platform that ships** (the JVM row is at the edge of its scatter and the image row shows no gain); one large corpus with giant declarations (Mathlib, not run here) |
| **2. Complete the classes, then widen** | class M for the two flag memos (`ISPROP` / `PROPT`, `PIRR` / `PROOF`); class C for the argument pairs of `CARGS` and the lazy-delta step; the completeness test (`default,-warm`: ticks equal) becomes a gate. Only then: lower the session threshold, and every site with adaptive sites (mode 239) | M4, M5, M3; M8 (the test) | today +0.02 to +0.2 % ticks (block sites), +0.07 to +0.2 % (every site, adaptive); every-site laziness measured -16.5 % on reachability at one worker (111), but +9 % on the Cedar slice without adaptation (47): it must not enter without the trigger and the adaptive rule |
| **3. The tier** | `rlvm-truffle` mirrors the lazy site and the scrutinee test; "reserve, do not write" (section 5); with T3's `CALL` and direct call nodes the unit over which a private application can stay virtual is caller + callee | T3 (U1 first), M1 (reservation), M8 (tier equivalence over identified records) | design only. T3's U1 alone measured nothing outside the scatter (machine-T2.log 20:01Z); this stage is where its direct calls would have an object to remove |
| **not recommended** | every site lazy without adaptation (47); lazy closures / suspensions / frames (stage D of 9.2); reclamation of private records in the stepper | - | 10.1 (records +37 to +61 %, ticks +1.6 to +5.9 % on library corpora), 10.2 (+9 %), the session-end census (2-6 % of the suspensions are never used) |

What stage 1 costs if it is wrong: it is one bit test per key-forming call while a large session has private
records and nothing otherwise; switching it off is `rlvm.u2=0`.

## 12. Not established

- **Eight workers.** The gain is resolved at one worker on both platforms; at eight workers it is -8 to -10 % on the JVM with ranges that touch, and absent in the native image, and the reason for the difference is not known (10.2).
- **The native image beyond one window**: reachability at one and eight workers and the Cedar slice at eight; no Cedar slice at one worker, no adaptive every-site mode beyond 239 on reachability.
- **Init, Std, full cedar, Mathlib, con-leche, cslib, navier-stokes-euler in a timing window.** Counters only for
  Init, Std and the Mathlib slice; the windows for Init / Std and full cedar with 8 workers were queued behind the
  quiet lock (waits of 13-27 minutes per window) and cancelled when the session ended; with the large-session trigger
  the counters of Init are the mode-off counters, which bounds what a timing could show.
- **More than two processes per row.** The scatter between two JVM processes of one configuration is up to 8 %; the
  resolved rows are reachability at one worker (two windows agree) and the Cedar slice with every site lazy.
- **The timings are of the form before the force points were outlined** (jar `f62325f62b53` and earlier); the
  patches are the outlined form (`7e07c389b70b`), whose counters are identical on six runs and whose methods are
  smaller, but which was not timed.
- **The launcher path.** Every timing is `WarmMain` (one engine kept, fresh sessions per pass, no admission pipeline,
  no pipelined loading), not `bin/rlvm -X passes=N -X passes-keep=engines`.
- **The tier.** `rlvm-truffle` does not know the lazy site (its `MKAPP` node interns); section 5 is design. T3's U1
  and this mode were not run together.
- **Closures, suspensions, frames** (stage D): census only (9.2, 10.1).
- **Reclamation / nursery**: design only (4.4, 5 step 3); the footprint of a session grows in this mode and no run
  here was near a memory wall, so what the growth costs on the giant declarations of Mathlib is not known.
- **`once`** (X1) together with this mode: argued (section 6), not run - the tree of 19:04Z does not contain it.
- **Why the gain is what it is.** No profile was taken; "the probes that are not made" is the mechanism by
  construction, the share of the time it explains is not measured.
- **Mutants.** The four mutant corpora (17,775 exports) were not run with the mode; the hostile suite, the 230 tests
  and the corpora were.
- **A site per instruction / per term node** for the adaptive rule (the prototype's site is the head constant).
