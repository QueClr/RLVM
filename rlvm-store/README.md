# rlvm-store - the persistent data of a run

Package `rlvm.store`, work packages A1, A2, A3 and `TermOps` of `docs/design/ARCHITECTURE.md` 10.2.
Depends on nothing but the JDK (the two dependencies in `pom.xml`, `rlvm-export` and `rlvm-level`, are
test scope). Everything is `int` ids into flat primitive arrays; id 0 is "none"; no object per term, name,
level or literal; no reflection, no `java.util` collections, no lambdas on any path.

```
. ./env.sh && $MVN -o -q -pl rlvm-store test                      # 68 tests, 1 opt-in (init.ndjson)
$MVN -o -q -pl rlvm-store test -Dtest='RealExportTest#initWithProofBlocks' -Drlvm.store.heavy=true
bench/arena-docker.sh --arena-limits rlvm-store/bench/store-bench.sh [--c2] [MODE[:SIZE] ...]
rlvm-store/bench/real-export.sh [-XX:-UseJVMCICompiler] work/exports/init.ndjson [--decode-rounds 8 | --layout]
```

## What is implemented

| Class | Content |
|---|---|
| `Node`, `Kind` | 8-int node layout, id regions, header bits, sharing flags `F_SHARED / F_BODY / F_INVARIANT` |
| `TermStore` | export region (flat `int[]`, grows 1.25x, volatile publication), sharing-flag bookkeeping of the open unit, `setBinderHint`; the generated region (`appendGenerated`) |
| `NodeCtx` (package) | the one derivation of a node from its children, used by every writer; `lpSet` of levels and level lists |
| `Overlay` | per-worker terms: hash-consed `mk*`, free variables (also let-bound), decoded blocks, `mark / resetTo / reset`, growth, cap; `resetTo` gives memory back (node array, index, literals) |
| `Terms` | accessors over shared and overlay ids, exact loose range, spine helpers, `regionOf` |
| `TermOps` | `instantiate1 / instantiate / instantiateRev`, `abstractFVars`, `liftLoose / lowerLoose`, `instantiateLevelParams`, `hasLooseBVar(s)`, `beta / headBeta / cheapBetaReduce`, `mkPiOver / mkLambdaOver`, `eqv`, `mentionsAny`, `consumeTypeAnnotations` |
| `NameTable` | structural interning, Lean's `name::cmp`, flat-namespace guard |
| `LitPool` | big naturals and strings interned by content; worker pools with negative indices; `mark / truncateTo` (released indices are retired until `reset`) |
| `DeclTable`, `DeclKind` | constant rows, unit rows (+ first term of the segment), atomic unit status, derived side tables, claims blobs |
| `Specials` | the 34 special names: loader half (cid, rank, literal ranks) and environment half (guard states) |
| `ProofBlocks` | writer, decoder into an overlay, promotion |
| `Store`, `StoreBuilder`, `SharedFacts` | the aggregate with the two frontiers; the loader's writer; racy per-term facts |
| `GeneratedBuilder`, `GeneratedTermException` | the environment thread's writer of generated constants: late names, shared copies of overlay terms, constant rows |
| `StoreMemory` | `Store.memory()`: allocated / used bytes and entity counts per region |
| `IntLists`, `IntListOps`, `ArrayIntLists`, `LevelInfo` | adapters to the level module, and a self-contained list store |
| `StoreLimitException` | a representation limit was hit (the loader maps it to a decline) |

Nothing throws `UnsupportedOperationException` any more. The generated region is implemented (see
"Generated constants" below); it stays empty until admission uses it. Pipelined loading is prepared (every
array a reader touches is published through a volatile reference, the stress test passes); generated
constants need the complete file (`Store.generated()` throws `IllegalStateException` before).

## Public API beyond the skeleton

Every skeleton signature still exists, with one exception: `StoreBuilder.dropUnsafe` now returns `boolean`
(requested by the loader; it broke the loader jar published at that moment, fixed by its republication).

- **Adapters.** `Store(long inputBytes, IntLists lists, LevelInfo levels)` and `Store.bindLevels`,
  `Store.levelInfo`. `LevelInfo` is six one-line delegations to `rlvm.level.LevelStore` (`hasParam`,
  `paramName`, `lhs`, `rhs`, `instantiate`, `instantiateList`); the store walks levels itself to derive
  `lpSet`. `IntLists.unionSorted / subsetOf` have default implementations (`IntListOps`).
  `IntLists.isSharedList` and `LevelInfo.isSharedLevel` (default: not shared) tell the generated region which
  ids every thread can read.
  `Overlay(store, lists, levelInfo, litPool, maxBytes)`, `TermOps(terms, lists, levelInfo)`,
  `TermOps.setLitPool`.
- **End of load**: `StoreBuilder.finish()` publishes the last units and gives the promotion memo, the
  sparse record index and the coarse record-to-block index back (`releaseLoaderState()`, idempotent):
  `promote` and a further block throw `IllegalStateException` afterwards. A load that ends without
  `finish()` keeps the state.
- **`StoreBuilder.touch(sharedId)`** (wave 7): a hint of the loader that the export node will be named as
  a child of a node appended soon; it loads the node's first and last word (a node can straddle two cache
  lines) and changes nothing. An id that is not an export term is ignored.
- **Builder protocol** (`StoreBuilder`): `beginUnit(recordKind)`, `reserve(kind, name, lparams)` -> cid or 0,
  `complete(cid, type, value, hint)`, `unitClaims(blob, len)`, `endUnit()`; the same per kind as
  `beginDeclaration / endDeclaration`, `beginBlock / claimMember / setMemberType / endBlock`; `markRoot`,
  `bindName`, `dropName`. `declare` and `member` still work but claim the name after the terms exist.
- **`DeclTable`**: `setType / setValue / setHint`, `special(cid)`, `unitFirstTerm(unit)`,
  `claims / unitClaims / dropClaims`, `firstUnitWithNestedRootName()`; array forms
  `setInductive(cid, numParams, numIndices, int[] ctors, n, int[] all, n, flags, numNested, recCid)` and
  `setRecursor(cid, numParams, numIndices, numMotives, numMinors, flags, int[] ruleTriples, n, majorInduct)`
  with readers `inductCtor(cid, i)`, `inductAllCount / inductAllAt`, `inductRec`, `recRule*`.
- **`Overlay`**: `mkConstByName`, `mkFVarLet`, `refresh`, `setLevels`, `store`, `allocatedBytes`.
  **`Terms`**: `flags`, `head`, `hasFVar`, `hasLevelParam`, `isNatBig`, `looseRangeField`, `regionOf`.
- **`TermOps`**: `instantiate` (forward), `lowerLoose`, `hasLooseBVar(s)`, `beta`, `headBeta`,
  `cheapBetaReduce`.
- **`LitPool`**: `LitPool(shared)` (worker pool), `publish`, `natLimb`, `natEquals`, `stringBytes`,
  `mark / truncateTo`. **`NameTable`**: `isBigNumeric`, `hasPrivateRoot`. **`Specials`**: `rank`,
  `indexOfName`, `setState`, `annotationName`, `nameString`. **`ProofBlocks`**: `rootRecord`,
  `recordCount`, `promotedCount`, `hasUnresolved(block)` (something in the block was unresolved when it was
  written; false guarantees a resolved decoded root), `loaderStateReleased`. **`TermStore`**: `segmentStart`, `setBinderHint`.

## What the machine reads (ISA.md 12, R1 and R1')

- One dispatch per node: `int[] r = terms.regionOf(id); int w = Node.wordOf(id);` then `r[w + Node.W_*]`
  (`Node.kind / flags / looseRange` of `r[w]`). The array of an overlay id is valid until the overlay makes a
  node, decodes a block, or is reset or cut back (`resetTo` may replace the array when it gives memory back).
- Block hint: `store.terms().setBinderHint(id, value)` writes the reserved `c` word of an export or
  generated LAM / PI node; lossy, racy, read with `terms.c(id)`; nothing in the store reads it.
- Sharing flags `F_SHARED / F_BODY / F_INVARIANT` in the header; `DeclTable.unitFirstTerm(unit)`.
- `MachineAccessTest` covers all of it (including a hint writer racing with a growing region).

## Generated constants (ARCHITECTURE 2.2, 4.2, 4.6)

`GeneratedBuilder g = store.generated()`: environment thread, after `Store.loadComplete()` (from then on it
is the single writer of names and constant rows, so there is no concurrent late-name table; a pipelined run
lets a unit that needs a generated constant wait for the end of the load).

| Call | Effect |
|---|---|
| `g.name(prefix, utf8, off, len)`, `g.nameNum(prefix, n)`, `g.isFree(name)` | late names: the id of a name the export never mentions; "already declared" test |
| `g.term(terms, overlayTerm)` | a shared copy in the generated region (`Node.GEN_BASE \| i`), node for node, children first, same derivation of summaries, all `F_SHARED`; the caller's `Terms` is refreshed |
| `g.constant(unit, kind, name, lparams, type)`, `g.setType(cid, type)` | a row after the loader's constants, `CF_GENERATED`, unit and rank of the unit being admitted, side row for INDUCT / CTOR / REC, special bound for the quotient names; 0 if the name is taken |
| `DeclTable.generatedCount() / generatedCid(i) / isGenerated(cid)` | enumeration |
| `TermOps.rebind(term)` | an overlay copy in which every CONST / PROJ that was unbound when the loader wrote it, and whose name has a constant now, is bound |

`term` refuses (`GeneratedTermException`, nothing added): a free or loose variable, an unbound or
wrong-arity reference, a literal of a worker pool, a level or level list that is not shared
(`LevelInfo.isSharedLevel`, `IntLists.isSharedList`; both default to "no"), and a set of universe
parameters that the shared list store cannot supply. The last point is the practical limit: a frozen base
refuses a set it never saw, so a recursor with a universe parameter of its own needs the level module to
let the environment thread extend the shared base.

## Invariants

1. **A published datum is immutable**, with two documented exceptions: the unit status (CAS) and the
   reserved `c` word of export LAM/PI nodes (`setBinderHint`, a lossy hint nothing in the store reads).
   Readers must read the frontier (`Store.loadedUnits / envUnits`) before the data it covers.
2. **One derivation.** Export nodes, overlay nodes, decoded block nodes and promoted nodes are written by
   the same code (`NodeCtx`), so a term has the same summaries wherever it lives:
   - `looseRange` exact below 0xFFFF; saturation is sticky and flagged `F_LOOSE_SAT`;
     `Terms.looseRange / isClosed` are exact always (iterative walk over the saturated part).
   - `fvMask` is a superset of the free indices, exact when no sub-term has a loose index >= 63.
   - `depMax` = largest rank mentioned; `DEP_UNRESOLVED` for an unbound CONST/PROJ, a wrong number of
     levels, or a literal whose prerequisites were not all declared when the node was written.
     `F_HAS_CONST` iff `depMax != 0`.
   - `lpSet` = exact sorted set of universe-parameter names; `F_HAS_LPARAM` iff `lpSet != 0`.
3. **Ranks are 1-based** (`rank = unit + 1`), so `depMax == 0` means "no constant" and visibility is
   `depMax(root) < rank`.
4. **Sharing flags never under-approximate.** `F_SHARED`: two or more references from records of the node's
   own segment (also set on every promoted and every hash-consed overlay node). `F_BODY`: binder body or
   declaration root. `F_INVARIANT`: the first referrer has a different free-variable mask. Nodes of earlier
   units are never touched; everything below `DeclTable.unitFirstTerm(unit)` counts as shared.
5. **Id equality implies structural equality, never the converse** (shared regions and decoded blocks have
   no hash index). `TermOps.eqv` compares constants and projections by name, levels by id, literals by
   content.
6. **Names**: equal ids iff equal names; `claim` fails on any earlier claim of the name, by a constant or
   by a dropped (unsafe/partial) record, as the official reader's "Duplicate declaration".
7. **Literals**: equal indices mean equal contents; a pooled natural below 2^64 is always stored in the
   node (`natBig`, `mkNatLitBig` and the block writer canonicalise). A worker pool gives a value the
   shared index only if the shared pool published it, so consumers still compare contents on a mismatch.
   **A worker pool never issues an index twice between two `reset()` calls**: `truncateTo` frees the
   payload and retires the indices, and reading a released entry throws `IllegalStateException`. A cache
   keyed by pool index (`rlvm.api.bind.NatBinding`) therefore cannot be handed another number under a
   key it holds.
8. **An overlay must be reset whenever the worker's level or list overlay is reset** (level ids, list ids
   and `lpSet` words in overlay nodes belong to them). `Overlay.reset` is O(used).
   **`Overlay.resetTo(mark)` keeps every node below the mark under its id and hands out ids from the mark
   upward again**; it returns the node array to 32 MiB and the hash-consing index to a size proportional
   to the nodes it holds, and releases the worker pool's literals made after the mark (the last 32 marks
   are remembered). A caller must have dropped every id at or above the mark, and re-read `array()`.
9. **Traversals are iterative** with heap stacks and a per-call memo keyed by (term, binder depth):
   depth is unbounded, DAGs cost linear time (tests: 300,000-deep terms, a 2^64-path DAG).

## Deviations from the design documents

| Design | Here | Why |
|---|---|---|
| `Store(long)` only; lists and levels "of rlvm-level" | adapters `IntLists` + `LevelInfo` passed in | the store may not depend on `rlvm-level`, yet `lpSet` needs the parameters of a level |
| `declare(...)`, `member(...)` take the term ids (A 1.3) | names are claimed first: `beginUnit / reserve / complete / endUnit` | A 3.3 itself requires "claim before the sweep"; otherwise every inductive block is full of unbound references |
| rank = unit index (A 4.1) | rank = unit + 1 | `depMax` 0 is "none" (A 2.1); with rank 0 the first unit could not be told apart |
| the loader counts referrers (RECONCILIATION 3) | the store does it: at append for export nodes, at decode for block records | one place, no extra pass, the loader passes no flags (`LoadTarget.wantsSharingFlags() == false`) |
| `Specials` written by the environment thread (A 2.7) | split (RECONCILIATION "Literal depMax"): cid/rank bound at claim by the loader, guard states by the environment; the 34 names are interned at store creation, so `nameOf` is never 0 | literal `depMax` must not depend on how far the environment pass is |
| `which(cid)` "one array load" | special index + 1 in bits 10..15 of the constant row's word 0 (`DeclTable.special`); `flags(cid)` is bits 4..9 | no extra array, same cache line as the kind |
| `LitPool` without interning, naturals in one array | interned by content (RECONCILIATION "Literal identity"); naturals in chunks that never move | a declaration can compute gigabytes of naturals (`perf/gf3-matrix-product`); a doubling array needed 3x the payload |
| `ctors` / `all` / rule tables as `IntLists` ids (A 2.5) | stored in the table (array setters); list-id setters copy | critic C1.13: the environment thread cannot intern into frozen list stores |
| constructor row w5 = cid of its type | 0; use `ctorInduct` | the value would be written by a second thread into rows the loader may reallocate |
| block record format (A 2.6) | + small-field bits: CONST "wrong arity", NATLIT/STRLIT "prerequisites unresolved at write time"; + a sparse offset index (every 16th record, 0.25 B/record, loader only) | deterministic decode without list lookups; promotion proportional to what it copies |
| promotion = "copy" (A 3.3) | promoted CONST/PROJ are re-bound and literals re-ranked at promotion time | a promoted node is written for the unit that references it, in that unit's environment |
| block storage chunks of 1 MiB | first chunk 64 KiB | stores of small files |
| `NameTable.claim` compare-and-set, `dropUnsafe` void | plain test-and-set by the one writer; `dropUnsafe / dropName` return boolean | names have one writer at a time (below); duplicates of dropped names are rejections |
| generated constants and late names through a concurrent table shared by loader and environment thread (A 4.2) | `GeneratedBuilder` works only after `loadComplete()`; the environment thread then is the single writer of names and constant rows | no second writer, no table; a pipelined run already retries load-dependent outcomes of the environment pass after the load |
| overlay "returns to the base size at the next reset" (A 2.2) | also at `resetTo`, and the hash-consing index follows its content (floor 2^15 slots, 4 hash bits per entry) | the reference engine resets to a mark after every declaration and never reached `reset` with a full index: the index stayed at its high-water mark |
| `LitPool` marks reuse indices | released indices are retired until `reset` | a cache keyed by pool index must never see a second number under one key |
| overlay "32 MiB, shrunk at reset" | starts at 32 KiB, doubles to the cap, cut back to 32 MiB at reset | many small overlays in tests and tools; same steady state |
| append <= 60, `mk*` <= 150, decode <= 60 instructions (A 10.2) | not met in instructions, see below | CPU time is the metric (owner note 10); the JVM pays bounds checks per word |

## Measurements

Arena container (`--arena-limits`: 8 performance cores, 16 GB), GraalVM CE 25 JVM, Serial GC,
`rlvm-store/bench/store-bench.sh`: each mode runs a warm-up of N operations and then N and 2N operations
under `perf stat`; a cell is (run 2N - run N) / N, all threads of the process. The method cancels start-up
but compiler threads make single cells noisy (about +/- 30 % between repetitions; instructions per
operation differ by up to 3x between the two JIT compilers on the same loop). CPU time first.

| Operation | Graal JIT: CPU ns | instr | HotSpot C2 (`--c2`): CPU ns | instr |
|---|--:|--:|--:|--:|
| export node appended, children nearby (`append`) | 15.2 | 228 | 19.1 | 205 |
| export node appended, one child anywhere in 640 MB (`append-far`) | 73.3 | 194 | 91.9 | 203 |
| overlay `mkApp` that creates a node, 16 MiB sessions (`mk`) | 40.3 | 271 | 37.2 | 168 |
| overlay `mkApp` that finds a node (`mk-hit`) | 8.4 | 139 | 9.3 | 148 |
| block record decoded, synthetic, half the references shared (`decode`) | 27.9 | 211 | 17.9 | 196 |
| session of 64 nodes + `reset` (`session`) | 338 | 11,300 | 290 | 10,800 |
| `new Overlay` + `new Terms` + first node (`overlay-new`) | 3,045 | 4,480 | 3,310 | 5,570 |
| distinct name interned, 2.1 M..4.3 M names, generator subtracted (`names` - `names-gen`) | 106 | 307 | 107 | noisy |
| `instantiate1`, about 150 node visits (`instantiate`) | 3,340 | 28,000 | 3,560 | 29,800 |

On real data (host JVM, `rlvm-store/bench/real-export.sh work/exports/init.ndjson --decode-rounds 8`): Init's
5,071,208 block records decode at 24 ns CPU per record with the Graal JIT (31 with C2), sessions of 16 MiB;
about 0.7 references per block record point into the shared region, and those loads dominate. Loading Init
through the test sweep (reader of `rlvm-export` + sweep + store, cold JVM) takes 1.2 s CPU.

Layout question of WP-A1 (interleaved 8-int nodes against parallel columns), measured on 4.0 M decoded
nodes of Init's proofs with the real access pattern (walk each block from its root, read kind, a, b and both
mask words, plus the header of each shared child; `real-export.sh ... --layout`): interleaved 7.2 ns per visit,
columns 9.3 (Graal JIT); 6.9 against 7.0 (C2). The interleaved layout stays. Not measured in the native
image.

Sizes on Init: 6,137,092 expression records become 1,659,805 shared nodes (53 MB) and 5,071,208 block
records in 42,695 blocks, 22.1 MB = 4.36 bytes per record (target <= 6); 593,921 records were promoted by
250,743 calls. Of the decoded block nodes 12.6 % are `F_SHARED`, 9.4 % `F_BODY`, a further 26.3 %
`F_INVARIANT` only: 52 % need no tabling.

### Wave 4: memory by region, and what the overlay cost

Commands (host, GraalVM CE 25 JVM, `-XX:+UseSerialGC -Xms13g -Xmx13g -Xmn1g` as the launcher sets them;
`perf` is not usable on the host, so instruction counts are missing from these tables; the host was shared
with about fifteen other agents, load average 8 to 190, so absolute times are inflated and only numbers of
the same round are comparable):

```
work/rlvm-store/variant.sh NAME                 # frozen copy of the main sources, compiled
work/rlvm-store/ab.sh "base NAME .." EXPORT ROUNDS -j 1     # alternating runs of the published
        # distribution with a variant's store classes in front; worker CPU = CPU time of the rlvm-worker
        # threads from the driver's statistics file, cpu = user + sys of the process (GNU time)
java .. -cp work/rlvm-store/scratch/classes:<variant>:work/dist/lib/* StoreReport EXPORT    # Store.memory()
work/rlvm-store/diag.py <variant>               # variant that samples all overlays every 5 s
```

**The store on mathlib** (6.33 GB, 721,845 units; `Store.memory()` after a batch load), against the budget
of ARCHITECTURE 2.9:

| Region | Allocated MiB | Used MiB | Entities | Budget (GB) |
|---|--:|--:|--:|--:|
| export terms | 1,340.7 | 1,182.2 | 38,739,557 nodes (28.8 M written by the sweep, 9.9 M promoted) | 1.41 |
| proof blocks | 366.1 | 361.2 | 78,207,172 records in 475,132 blocks, 4.62 B / record | 0.37 |
| loader state (promotion memo, sparse index) | 288.0 | 288.0 | 9,909,017 promoted records | not budgeted |
| names | 229.0 | 164.6 | 4,273,914 | 0.15 |
| declarations | 84.0 | 46.6 | 739,167 constants | 0.04 |
| literals | 6.5 | 3.5 | 21,519 | 0.03 (with levels and lists) |
| shared facts | 0 | 0 | the reference engine writes none | 0.04 |
| **total** | **2,314.3** | **2,046.2** | | 2.04 |
| after `StoreBuilder.finish()`, which releases the loader state | 2,026.3 | 1,758.2 | | |

The heap after the load and a full collection holds 2,358 MiB (2,070 after the release): the store is all
of it but 44 MiB. So the shared store is within 5 % of its budget once the loader state is released
(`StoreBuilder.finish()` does that since this wave, at the loader owner's request: nothing is promoted
after the end of the input); the
export region is 88 % full (the `inputBytes / 144` estimate holds), names and declarations are over-sized
by their growth policy (65 + 37 MiB of slack).

**Where the rest of the 13 GB goes** (mathlib, JVM, 8 workers, reference engine, published jars of 07:21Z).
The resident set is the heap setting: the old generation fills with dead arrays until the first full
collection, so RSS reaches 13.1 GB whatever is live. What is live, read from the three full collections of
a run, and the store's own worker-side arrays, sampled every 5 s:

| | Live after a full collection | Overlay node arrays (10-11 overlays) | Overlay hash indices | CPU s (workers / process) | Wall s | Peak RSS |
|---|--:|--:|--:|--:|--:|--:|
| before (store of 07:16Z) | 5.86 / 6.25 / 8.45 GB | 352 .. 1,418 MB | 40 MB growing to 1,352 MB, never shrinking | 975 / 1,014 | 169 (load 20-50) | 13.09 GB |
| after (this wave) | 4.50 / 5.04 / 6.90 / 6.39 GB | 320 .. 1,580 MB | 11 .. 283 MB, 12-100 MB typically | 771 / 815 | 1,049 (load 140-190) | 13.13 GB |
| after, heap `-Xms8g -Xmx8g -Xmn1g` | 3.22 / 3.61 / 4.51 / 3.48 / 3.17 / 5.30 / 4.52 GB | | | 702 / 734 | 296 (load 70) | 8.24 GB |

A pair made back to back under the same load (about 70), both with the 13 GB heap
(`work/rlvm-store/run.sh base|final work/exports/mathlib.ndjson --threads 8`):

| mathlib, 8 workers | CPU s (process) | Worker CPU s | Wall s | Peak RSS | Live after full collections |
|---|--:|--:|--:|--:|--:|
| store of 07:16Z | 1,083.5 | 1,053.2 | 358.1 | 12.98 GB | 5.75 / 4.20 GB |
| this wave | 648.9 | 617.8 | 190.8 | 13.03 GB | 3.51 / 3.21 GB |

The last row of the table above is the same run with a smaller heap (`RLVM_HEAP="-Xms8g -Xmx8g -Xmn1g" work/rlvm-store/run.sh
final work/exports/mathlib.ndjson --threads 8`): accepted, seven full collections of 0.1-0.3 s, 8.2 GB
resident. With the store of 07:16Z up to 8.45 GB were live, so that heap would not have been enough.

So of 5.9-8.5 GB live, 2.3 GB was the shared store, 1.2-2.8 GB the overlays (most of it the index, which
stayed at the size of the largest declaration each worker had ever seen: up to 256 MB per worker), and
2.3-3.4 GB everything else (the reference kernel's tables, level and number overlays). The second run was
made under three times the machine load of the first; its CPU time is still 20 % lower.

**CPU time of the reference engine** (one worker; worker CPU s, minimum / median of the rounds):

| Workload | Rounds | Store of 07:16Z | Index sized to its content | + 4 hash bits per entry |
|---|--:|--:|--:|--:|
| Init | 3 | 21.68 / 21.92 | 14.46 / 14.63 | 14.22 / 14.44 |
| Cedar slice `cedar-hot-ax` | 4, then 5 | 5.11 / 5.44 | 4.08 / 4.50, then 4.42 / 4.53 | 3.87 / 4.04 |
| `perf/app-lam` (one declaration, 38 M nodes) | 4 | - | 12.30 / 14.10 | 8.62 / 9.96 |
| Std, 8 workers (sum of the workers) | 3 | 36.99 / 37.94 | | 26.92 / 27.53 |
| Cedar, 8 workers (sum of the workers) | 3 | 50.46 / 53.96 | | 35.90 / 35.99 |

Process CPU (user + sys, with JIT and collector) of the two 8-worker rows: Std 42.5 -> 32.3 s, Cedar 57.4 ->
42.7 s; wall 21.4 -> 20.0 s and 33.9 -> 19.8 s; peak RSS 1.63 -> 1.61 GB and 1.89 -> 1.81 GB.

Index floor after a reset (`KEEP_TABLE`), Init, one quieter round of 3: 2^10 13.69 / 13.98, 2^13 13.52 /
13.98, 2^15 12.75 / 12.80, 2^17 11.90 / 12.96, 2^20 17.19 / 18.49, never shrinking 18.30 / 18.83. Kept:
2^15 slots (128 KiB), and the tags. Not tried: entries that carry the key words (16 bytes per slot): the
index would be four times larger, and after the two changes above a probe of a typical declaration no
longer leaves the second-level cache.

Tried and not kept: a bitmap "record promoted" in front of the promotion memo and a cache of the last
block in `ProofBlocks.promote` (suggested from the loader's profile: promotion is 29 % of its thread on
mathlib). Loader thread CPU of a mathlib load, three alternating rounds: 14.29 / 13.95 / 13.40 s without,
14.17 / 13.87 / 13.76 s with.

Peak RSS of the single-declaration tests is unchanged by this wave (`perf/app-lam` 5.3 GB for 1.28 GB of
overlay nodes, `perf/gf3-matrix-product` 4.2 GB for 3.3 GB of literals): it is the copy-on-growth of the
node array and of the index (old and new array alive together, the old ones garbage until a collection
that a 13 GB heap never needs). A chunked node array would remove it and was not done: every reader's
address arithmetic changes, and CPU time comes first.

### Wave 7: the loader's side of the store (promotion, block writer, hints)

Profile first (CE native image `-O3` with local symbols, `perf record -F 4000` in the arena container,
`rlvm --threads 1 FILE -X engine=machine -X stop-after=load`; `work/pipeline-w7/perf/base-load-*.txt`): of the
load phase of mathlib (66,246 samples = 16.6 s) `ProofBlocks` promotion was 20.4 % (`pending` 6.2, `seek`
4.8, `promote` 3.2, `blockOfRecord` 2.9, `promoPut` 1.9, `parse` / `rd` / `promoGet` 1.4), `WriterImpl.ref`
8.0 %, `NodeCtx.writeApp` 6.7 %; on Init (3,260 samples = 0.82 s) 18.2 %, 9.3 % and 4.8 %. Three changes,
none of which changes a word of the store that is built (the differential digest of
`work/pipeline-w7/digest` compares the stores of the published and the new jars):

1. **Promotion memo = a direct map** `global record number -> shared id` in chunks of 4,096 records that
   are allocated at the first promotion inside them, instead of an open-addressing hash table of the
   promoted records. A promotion asks about a record and the earlier records of its block: neighbours,
   which now share cache lines (the hash scattered them over a 256 MB table, one miss per question, and
   doubled by re-insertion). Memory: at most 4 bytes per block record (mathlib 313 MB if every chunk were
   touched; the hash table ended at 268 MB), part of the loader state that `finish()` gives back.
2. **Block of a record** through a coarse index (one int per 512 records: the block that holds that
   record, written when a block is finished) and a binary search over the few rows between two entries,
   instead of a binary search over all block rows (19 steps on mathlib, most of them cache misses).
   `blockOfRecord` (any thread) is unchanged; `ProofBlocksTest` compares the two for every record.
3. **`seek` keeps the byte positions of the group of 16 records it walked last**, so a walk down a chain
   of neighbours skips each record of a group once instead of once per visited record.
4. **`WriterImpl.ref` no longer loads the shared node it refers to.** It read the node's `depMax` for every
   reference from a block record to a shared term (0.7 per record), only to learn whether the node is
   unresolved: a load of the 1.2 GB export region per reference. `TermStore` now keeps the smallest id of
   an export node with `depMax == DEP_UNRESOLVED` (`firstUnresolved()`, MAX_VALUE in an export whose
   references all bind) and the writer reads the node only at or above it. The answer is the same.
5. `StoreBuilder.touch(sharedId)`: the loader announces the earlier shared children of the nodes it is
   about to append (see `rlvm-load/README.md`, "Wave 7").

CPU time of the load phase, `perf stat` task-clock in the arena container, alternating runs in one hold
of the measurement lock (13:44Z, load average 60-90 on the host: only the pairs compare), store changes
1-4 alone against the published jars (`work/pipeline-w7/loadcpu.txt`):

| Load phase (`-X stop-after=load`, native `-O3`) | published (store 45df9b63e7af) | changes 1-4 | |
|---|--:|--:|--:|
| Init, CPU ms (3 alternating runs) | 897 / 800 / 797 | 709 / 682 / 693 | -13 % |
| Std, CPU ms (3 runs) | 1,338 / 1,373 / 1,504 | 1,150 / 1,156 / 1,287 | -15 % |
| mathlib, CPU s (1 run) | 16.65 | 13.77 | -17 % |
| instructions, Init / Std / mathlib (G) | 7.44 / 12.47 / 132.3 | 7.17 / 12.03 / 128.2 | -3 % |

With the loader's two changes of the same wave on top (`rlvm-load/README.md`, "Wave 7"; one hold of the
lock at 15:15Z, load average 107-111): Init 844 / 885 / 892 -> 681 / 673 / 667 ms, Std 1,377 / 1,410 ->
1,102 / 1,136 ms, mathlib 17.18 -> 12.40 s, at the same instruction counts.

So the gain is cache misses, not instructions (owner note 10). After it (mathlib, 53,022 samples): promotion
10.0 % (`seek` 4.4: the first touch of a cold block's bytes; `promote` 3.3), `WriterImpl.ref` 2.8 %,
`NodeCtx.writeApp` 8.2 %, `Loader.exprApp` 12.1 % (the `loc` reads, moved by the loader's change of the same
wave), reader 22 %, kernel (page faults) 10.7 %.

Also in wave 7: `Overlay.remapBlocks` sized the rebuilt block map with 64 slots whatever number of blocks
stayed below the mark; with 64 or more of them (and more than eight times as many above) `putBlock` never
ended. Reported by rlvm-admit (requests.log, 14:02Z); the map is now sized for the blocks that stay
(`OverlayTest.resetToRebuildsTheBlockMapForTheBlocksBelowTheMark`).

### LP2: packed runs and the record index of promotion (2026-11-24)

Nothing here changes a word of what a load builds (differential digest, `work/lp2/digest`).

1. **`ProofBlocks.Writer.run`**: a run of APP / LAM / PI / LET records handed over in PACKED form - the
   record bytes as they will stand in the block (kind byte, references as LEB128) with some
   references OPEN, one length byte per record, and the open references as `(offset, value)` pairs
   (`> 0` a shared term, `< 0` an earlier record of the block). It is what a scanner thread of the
   reader prepares (`rlvm-export` "Parallel packing"; `rlvm-load` links it). The writer walks the
   length bytes once: the bytes between two open references are copied as they are, an open
   reference is encoded where it belongs (with the bookkeeping of `ref`: unresolved flag, sharing
   counters of the open unit), every record gets its entry in the sparse index. A reference that is
   present in the bytes and names a shared term must name a term of an EARLIER unit, for which that
   bookkeeping does nothing; the caller states the largest such id (`maxEarly`), and if it could be
   an unresolved node or a node of the open unit the writer falls back to the interface default,
   which makes the `count` calls of `app` / `lam` / `pi` / `let` one by one. `PackedRunTest`: 300
   random blocks written record by record, through the default and through the writer's own `run`
   give the same export region, block rows, decoded blocks and promotions.
2. **A byte per block record for promotion** (`within`, loader state, given back by `finish()` with
   the sparse index): the offset of the record from the sparse offset of its group of 16. `seek` is
   two loads; it skipped up to 15 records before (`skip`: 6 % of the loader thread on Std after the
   packing, with `seek` 1.8 %). The sparse index and the block rows are what they were. Cost: one
   byte per block record while loading (mathlib 78 MB).

### Exactness and allocation items taken on 2026-11-24 (LP2)

- **`LitPool.addNat` / `addString` on a worker pool look in the worker's own table first** (R2): a
  value a worker has already interned keeps its index for the rest of the declaration. Before, the
  shared pool was asked first, with the limit "published now": under a pipelined load a value could
  get the worker's negative index at its first use and the shared index at the second, when the
  loader thread had published it in between - two node ids for one literal inside one declaration,
  depending on thread timing. Nothing changes for the loader's own pool.
- **V1's two patches** (`work/v1/patches/rlvm-store-LP2`, findings F-V1-S1 / S2; worker side only):
  the overlay's index tables go round a small pool instead of being allocated at every `resetTo` and
  doubling (2.4 GB of the 13.8 GB that 8 workers allocate in one pass over mathlib), and
  `TermOps`' rewriter memo keeps the scratch of `widen` (1.5 GB).
- **Not in the store yet: the `nondep` flag of a LET** (finding R2-F1): it is part of the official
  term identity and the store does not keep it; the plan agreed with R2 (header bit 12 on LET nodes,
  tag bit of the block record, `Overlay.mkLet` with the flag, a second tag for the packed runs) and
  how such a change is gated are in `work/status/requests.log`, LP2's answer of 22:1xZ.

## Tests

68 tests in 12 classes (`src/test/java/rlvm/store`), all green; one of them opt-in (it was run and passes).

| Class | What |
|---|---|
| `DeriveTest` (7) | summaries of export, overlay and decoded nodes against naive recursive definitions on 900 random DAGs; sticky saturation and exact range (70,001 binders); masks under binders; DAG-shaped and 200,000-deep levels; fail-closed without `LevelInfo` |
| `TermOpsTest` (13) | every operation against a naive model on random DAGs (shared and overlay inputs); inverse laws; `eqv` against structural digests incl. mutants; depth 300,000; a DAG with 2^64 paths; overflow |
| `OverlayTest` (7) | hash-consing of every constructor; reset; random marks with blocks and free variables in between; growth to the cap with two readers; **`resetTo` gives back node array and index** (three ways of cleaning the index); **an id below a mark is never handed out again** (400 random rounds against a model); **`resetTo` releases the declaration's literals**, keeps the ones made before the newest mark, drops stale marks |
| `ProofBlocksTest` (6) | random files (definitions, theorems, cross references, interleaved segments): round trip, promotion copies exactly the reachable unpromoted records, block table, chunk limits, every record encoding; **sharing flags against a naive referrer count**; **promotion over 150 blocks and more than 12,288 records** (several chunks of the promotion memo, many entries of the coarse record index, 1,500 promotions in random order, the loader's block lookup against the binary search for every record) |
| `NameTableTest` (5) | interning, big numerals, queries, claims, **`compare` against a reference of Lean's order on 10^5 pairs**, growth |
| `LitPoolTest` (5) | interning by content, code points of 1..4-byte scalars, worker pools and publication, chunked big naturals; **marks against a model: no index is issued twice in a session, a released entry cannot be read, memory follows the live payload** (naturals and strings) |
| `MachineAccessTest` (5) | the one-dispatch accessor against the field accessors in every region; `regionOf` after growth and reset; the binder hint (only export and generated binders, nothing else changes, a writer racing with a growing region); `unitFirstTerm` |
| `GeneratedTest` (5) | shared copies node for node against the model (40 seeds), every refusal leaves the region as it was, lists and parameter sets against a frozen list store, generated constants + late names + `rebind` + a second thread reading them, region growth |
| `MemoryTest` (1) | `Store.memory()` adds up to `sharedBytes()` and follows the data; `finish()` releases the loader state, decoding is unaffected, promotion and block writing are over |
| `DeclStoreTest` (8) | rows, units, statuses, claim-first protocol, duplicates and dropped records, side tables, specials and literal ranks, frontiers, shared facts, growth |
| `ConcurrencyTest` (2) | frozen base with 8 workers (own overlay, list overlay, literal pool; decode, instantiate, `eqv`, racy shared facts); 6 readers following the loaded frontier while the loader writes 3,000 units and every array grows |
| `RealExportTest` (4) | real exports read with `rlvm-export`, levels in `rlvm-level`, loaded by a test sweep through `StoreBuilder`: every shared node and every record of every decoded block equals the export's record (independent digests); every root of a good file is closed, bound, visible and within its universe parameters. Runs on init-prelude (with and without blocks), all good arena tests, the bad arena tests (round trip only), and with `-Drlvm.store.heavy=true` on `init.ndjson` |

16 hand-made mutants of the main code were run against the suite: mask rule, both sharing counters,
substitution index, binder depth, index removal, `depMax`, name order, promotion, pool publication, exact
range, `hasLooseBVar` shortcut, rank base, pool accounting, decoded-block map: 15 killed. The survivor
(one limb wasted per pool truncation) does not change behaviour.

Wave 4: 46 mutants of the new code (`work/rlvm-store/mut/campaign.sh`: hash tags, the three ways `resetTo`
cleans the index, shrinking of array and index, the mark log, retired literal indices, every check of
`appendGenerated`, generated rows, `rebind`, loader-state release, the memory report). First run: 39
killed; the 7 survivors showed four holes in the tests (index shrink after a partial reset, the list
check, a free variable under a binder, marks surviving `reset`) and two tests the harness had not been
given; after closing them 45 are killed. The last one (`rebind` without its early return) is equivalent.
Seven more for `ProofBlocks.hasUnresolved` and the promotion experiment: all killed.

## Known gaps and costs

- Generated terms need every level, level list and parameter set in the shared (frozen) level and list
  store: a recursor with a universe parameter of its own is refused until the level module can share
  levels after the freeze; in a pipelined run the steward's bindings answer "not shared" for everything.
- Peak RSS of one huge declaration is 2 to 4 times its nodes (copy-on-growth of the overlay's node array
  and index; `perf/app-lam` 5.3 GB, `perf/magma-list-pair-n21` 6.1 GB). A chunked node array is the fix.
  Still not done in wave 7 (asked for again): `Overlay.array()` is read directly by every engine, so the
  change is a change of all their accessors and needs their owners.
- A node is 32 bytes at offset 16 + 32 i of its `int[]`: if the array's first element is 64-byte aligned
  plus 16 (the usual object layout), every second node straddles two cache lines (`StoreBuilder.touch`
  therefore reads the first and the last word). Not measured; one padding node in front of the region would
  settle it, at the price of the address arithmetic of every reader.
- Promotion still starts with a miss on the block's bytes (cold: 361 MB on mathlib) and on its rows per
  call; the loader calls it once per referenced record. X5's finding (requests.log 14:04Z: on exporter
  output the private records of a theorem are an id range) would remove four fifths of the promotions.
- `Overlay` keeps a 32 MiB node array per overlay (10-11 overlays in an 8-worker run: 0.35 GB).
- Instruction budgets of A 10.2 are exceeded by a factor of 2 to 4 (table above). CPU time per node is
  15..40 ns. The remaining cost of decoding is loading the shared children of block records; a record
  format that carries the few facts needed from a shared child would avoid those loads.
- `Terms.looseRange` on a saturated node (a loose index >= 65,534 somewhere below) walks the saturated part
  of the term on every call; no published test has such a term.
- `SharedFacts` is allocated at the first write and extended by copying; a fact written during an
  extension can be lost (allowed: facts are idempotent hints).
- `LitPool` strings still live in one growing array (mathlib: 3 MB); only naturals are chunked.
- The official reader's "name declared twice" is enforced through `claim`. There is no concurrent
  late-name table (A 4.2): names and constant rows have one writer at a time, the loader until
  `loadComplete()`, the environment thread (`GeneratedBuilder`) afterwards.
- `F_INVARIANT` of an export node whose first referrer is a proof-block record is never set (the writer
  has no masks for block records); such a node is a declaration root in practice and carries `F_BODY`.
- The block decoder trusts its input (blocks are written by `ProofBlocks.Writer` only).
