# rlvm-load - the loader (work packages L1 and L2)

Package `rlvm.load`. Turns the record stream of a lean4export NDJSON file (`rlvm.export.ExportSink`
events from `rlvm-export`) into the shared store (`rlvm-store`): names, levels, literals, shared term
nodes, packed proof blocks, declaration rows, the claims blob of every inductive block. Two modes:
**batch** (one publication at the end of the file) and **pipelined** (the units are handed over while
the file is still being read, so that checking overlaps loading; section "Pipelined loading").

Depends on `rlvm-store`, `rlvm-export`, `rlvm-level`, `rlvm-nat`. Never on an engine, admission or the
driver. No kernel logic: the loader validates only what the official *reader* validates.

## Entry points

```java
LevelStore levels = ...;                                  // the run's base level store (rlvm.api.bind.Bindings.levelBase())
Store store = new Store(bytes, lists, levelInfo);         // rlvm-store; lists / levelInfo are adapters over `levels`
Loader loader = new Loader(store, LoadOptions.batch(bytes), new LevelStoreInterner(levels, freezeOnPublish), new NatStoreDecoder());
LoadReport r = ExportLoader.read(file, loader);           // reader -> loader -> finish(), on the calling thread
// or in one call:  ExportLoader.load(file, store, levelInterner, natDecoder)

// pipelined: the level store is a chain of snapshots, the store sits on the chain's views
LevelChain chain = new LevelChain(levels);                // `levels` unfrozen; never freeze it yourself
Store store = new Store(bytes, chain.lists(), chain.info());
LoadOptions o = LoadOptions.pipelined(bytes).withListener((units, complete) -> wakeTheWorkers(units));
LoadReport r = ExportLoader.read(file, new Loader(store, o, chain, new NatStoreDecoder()));   // on the loader thread
// meanwhile, any thread:  n = store.loadedUnits();  LevelChain.Snapshot s = chain.current();  s.levels.newOverlay()
```

| Class | Role |
|---|---|
| `Loader` | `implements rlvm.export.ExportSink` (and the deprecated mirror `RecordSink`); `finish()`, `report()`, `options()`, `publishCompleted()`, `locationOf(ordinal)`, `exprCount()` |
| `ExportLoader` | `read(Path or byte[], Loader[, ExportReader.Options])`, `load(Path, Store, LevelInterner, NatDecoder)`, `translate(ExportException)` |
| `LoadOptions` | `batch(bytes)`, `pipelined(bytes)` (hand-off every 4,096 units or 32 MiB), `withPublishEvery(units, bytes)`, `withListener(l)`, `withPublishOnDecline(b)`, `withScanThreads(n)` (`-X scan-threads`), `withStrictFormatVersion(b)`, `withProofBlocks(false)` (`-X no-proof-blocks`), `withSharingFlags(false)`, `withMaxSegmentRecords(n)`, `withMode(m)` |
| `LoadListener` | `published(int loadedUnits, boolean complete)`: the hand-off signal, called on the loader thread after every publication |
| `LevelChain` | `LevelInterner` for a pipelined load that publishes frozen **snapshots** of the level store (`current()` -> `Snapshot { levels, units, generation, last }`), with the store adapters `lists()` / `info()`; works on the unchanged `rlvm-level` API. Also usable in batch mode (one snapshot at the end, bit-identical store) |
| `LoadReport` | counters per record kind, shared / block / promoted nodes, dropped records, header strings, `publications`, `publishedUnits`, `scanThreads`, `formatVersionSupported`, `toString()` for the statistics dump |
| `MalformedExportException` | the official checker rejects this input: **reject**. Carries the 1-based line |
| `UnsupportedExportException` | outside what RLVM represents: **decline** |
| `LevelInterner`, `NatDecoder` | adapter interfaces; `LevelInterner` gained three default methods (`publish(int units)`, `finish(int units)`, `attach()`); bindings `LevelStoreInterner` (batch, over `rlvm.level.LevelStore`), `LevelChain`, `NatStoreDecoder` (over `rlvm.nat`) |
| `LevelStoreViews`, `NameTableOracle` | the adapters `rlvm.store.Store` and `rlvm.level.LevelStore` need from each other (`IntLists`, `LevelInfo`, `NameOracle`), for tools and tests; a run uses `rlvm.api.bind` |
| `BlockClaims` | layout (version 1) of the claims blob of an inductive unit, with readers |
| `LoadTarget` | the narrow interface through which the loader writes; production implementation `StoreTarget` (package-private, over `StoreBuilder`); the unit tests use an in-memory one |
| `tools.LoadTool` | command line: load a file, print the report; `--target store|null`, `--no-proof-blocks`, `--repeat N`, `--pipelined`, `--publish-units N`, `--publish-mib N`, `--scan-threads N`, `--followers N` (threads that keep up with the frontier as a worker must), `--strict-format` |

Failures: `ExportLoader` turns the reader's `ExportException` into `MalformedExportException` /
`UnsupportedExportException` and the store's `StoreLimitException` into `UnsupportedExportException`.
A caller that drives `ExportReader.read(file, loader)` itself must do the same three mappings (and call
`Loader.publishCompleted()` before it lets a decline out, see "A decline is per unit"). Any other
`RuntimeException` (`IllegalStateException`, `IllegalArgumentException`) is an internal error of reader,
loader or store, never a verdict about the input.

## Pipelined loading (work package L2, *agentic-note*: design-architecture-document, 3.5)

**What happens at a hand-off** (after a unit ends, every `publishEveryUnits` units or
`publishEveryBytes` input bytes, counted as lines of 56 bytes because the sink has no byte positions),
on the loader thread, in this order:

1. `LevelInterner.publish(units)`: the levels and lists created so far become readable by other threads
   (`LevelChain`: a new frozen snapshot, published through one volatile field);
2. `Store.publishLoaded(units, false)` (`StoreBuilder.publish()`): literals, blocks, then the volatile
   frontier. From here on everything reachable from units `[0, units)` is immutable;
3. `LoadListener.published(units, false)`.

At the end of the file the same with `LevelInterner.finish(units)`, `publishLoaded(units, true)` and
`published(units, true)`. So a thread that reads `Store.loadedUnits() == n` finds the level snapshot
that covers units `[0, n)` already published. The loader publishes only complete units (the store's
frontier is its count of closed units, and the loader cross-checks its own count against it).

**Level snapshots (`LevelChain`).** `rlvm.level.LevelStore` is single-threaded until frozen and a
frozen store accepts nothing new; the chain needs nothing else. The loader interns into the open store;
`publish` freezes it (that store is the snapshot) and continues in `newOverlay()` of it, which keeps
all ids. A publication that created no level and no list reuses the previous snapshot's store and only
raises its `units`. `chain.lists()` and `chain.info()` are the store's adapters: on the loader thread
they follow the open store; called from any other thread they answer from the current snapshot and
throw `IllegalStateException` for an id it does not contain or for a call that would create something,
so a stray `store.lists()` or `store.levelInfo()` on a worker can never return an unpublished datum.

**Reader protocol** (what a driver does per worker; `LoadTool --followers` and the test readers do
exactly this): read `n = store.loadedUnits()` first; then `s = chain.current()` (`s.units >= n`); a view
(`s.levels.newOverlay()`, the term overlay and the session over it) serves units below `s.units`; before
touching a later unit read the frontier and `current()` again and rebuild the view only if `s.levels`
is another store.

**What differs from a batch load.** `freeze()` adds the official normal forms of the levels to the
store it freezes. A batch load adds them once, after all levels of the export; the chain adds them at
every snapshot, so the *numbering of levels* differs (and with it the id of a list whose numbers
happen to coincide with another list's). Everything else is identical: term ids, constant ids, name
ids, literal indices, every word of every node except `SORT.a` and the contents of level lists, every
row, every block record, the location of every expression record. A batch load through the chain is
bit-identical to a batch load through `LevelStoreInterner` (one snapshot, at the end).

**Shared artefacts in a pipelined run** (publication rule B2 of *agentic-note*: design-reconciliation-document, "only base level
and list ids"). In a batch run every worker has the same base. In a pipelined run two workers can be
on different snapshots, and an id that exists in snapshot g but not in an older snapshot g' is, for a
worker still on g', the id of nothing or of one of its own private overlay objects. So a level or list
id may travel in something that several workers read only if every possible reader has it in its base:

- ids that occur in the store data of a unit (the export's levels and lists, `lpSet` lists) are safe
  for every reader that may read that unit: the reader protocol puts the unit under its snapshot;
- any other base id (a normal form that a later `freeze()` added, a list a later snapshot interned)
  is safe only if the artefact says which snapshot it needs (`Snapshot.generation`, or the snapshot's
  `levels.size()` / `listCount()` as bounds) and a reader on an older snapshot ignores the artefact or
  takes the newer snapshot first. Storing levels structurally and re-interning them per session, as
  B2 prescribes for derived levels, is always safe.

**A decline is per unit** (`LoadOptions.publishOnDecline`; always on in pipelined mode, opt-in in
batch mode because the caller must then be able to create worker views after a failed load). When the
load ends with `UnsupportedExportException` (metadata inside an inductive or quot record, a mixed
safe/unsafe block, a projection index above 2^31 - 1, a reader or store limit, ...) the loader first
publishes the units that were completely loaded and signals the listener; the load is never complete
(`Store.loadComplete()` stays false, so the run cannot be accepted). The driver's
`board.inputFailure(published(store), UNSUPPORTED_INPUT, ..)` then makes the decline the status of the
first unit that was not loaded: a rejected unit before it gives REJECT, as the official checker says;
otherwise DECLINE. A rejection (`MalformedExportException`) publishes nothing more: the official reader
fails on the whole file before it checks any declaration, so it must win over every unit outcome.

**Parallel scanning.** `LoadOptions.withScanThreads(n)` -> `ExportLoader.readerOptions(options)` ->
`ExportReader.Options.threads`: n helper threads tokenise 4 MiB chunks of the mapped file, the loader
thread replays them in file order (identical sink calls, verified in `rlvm-export`). It shortens only
the reader's share; the sink thread (loader + store) stays the bottleneck, so two threads are as good
as four (table below).

**Not in this module** (the loader side is done; these need their owners): the driver's pipelined
mode (`rlvm-driver`), the wiring (`rlvm-main`, `rlvm.api.bind.Bindings.bindLevelSnapshots`), parking
units whose outcome depends on the rest of the file (`rlvm-admit`), late names for constants the kernel
generates (store + admission; nothing is needed from the loader in batch mode, see "Generated
constants").

## What the loader does

Ids returned to the reader: store name ids (structurally interned), level ids of the level store, and
for expressions the **record ordinal** 1, 2, 3, ... (an `mdata` record returns the ordinal of the
expression it wraps and gets none of its own).

**Segment.** Expression records are buffered since the last admitted declaration, 4 ints per record:
kind, a 2-bit saturating referrer counter, a BODY bit, a MARK bit, and the payload. A child is stored
as its 1-based index in the segment, or, when it is an earlier record, as a *pending* reference (its
ordinal) whose slot is remembered in a list. `loc[ordinal]` (4 bytes per expression record, the only
per-record state that outlives the segment) says where a record of a finished segment went: a shared
id, or `-(global block record + 1)`. Since wave 7 the record path never reads `loc`: the locations of
all earlier records a segment refers to are read at the declaration line, in one tight loop (step 2
below). `loc` is far larger than the caches (428 MB on mathlib); read while records are scanned, one
miss at a time is outstanding, read in that loop the misses overlap. The store that results is
bit-identical (locations of earlier records change only by promotion, which happens in that loop and
after it; the promotions are requested in the same order as before).

**Declaration line.** In this order:

1. the name(s) are claimed (`LoadTarget.reserve`): from here on a CONST / PROJ written with one of them
   binds to the new constant;
2. the pending references of the segment are resolved: the location of each earlier record is read;
   a shared record gives its id, an earlier block record is promoted first (`StoreBuilder.promote`
   copies it and what it reaches in its block into the export region; `loc` is updated); the same
   for roots that are earlier block records;
3. one backward pass marks what the unit's **roots** reach inside the segment. Roots: the type; the
   value of a definition; all member types and all rule right-hand sides of an inductive block. The
   value of a theorem or opaque is *not* a root (unless `withProofBlocks(false)`). The same pass
   announces to the target every earlier shared term that is a child of a marked record
   (`LoadTarget.touch`, a hint; `StoreTarget` -> `StoreBuilder.touch` loads the node), so that the
   node is in the cache when the sweep writes the parent;
4. one forward pass writes the segment: marked records become shared nodes, all others go to one proof
   block. A theorem's / opaque's value is that block (`-(block+1)`) if its root is in it, else a shared
   term. For the other kinds the block, if there is one, is ownerless (root -1): it holds records that
   no root reached, i.e. records of later declarations that the file wrote early;
5. the rows are completed and the unit is closed.

Two paths of the loader thread take records in stretches instead of one by one, with the store and
every failure unchanged (*agentic-note*: rep-OPT-B): `Loader.exprApps` appends a stretch of `app`
records to the segment in one loop (the reader's `ExportSink.exprApps`; refused whenever the
record-by-record path has something else to do: metadata, referrer counting, the segment limit), and
the sweep hands a stretch of consecutive proof-private APP / LAM / PI / LET records to the target in
one call (`LoadTarget.blockRecords`, default: the single `block*` calls; `StoreTarget`:
`ProofBlocks.Writer.records`).

Consequences: references to the unit's own names bind (members of an inductive block see each other; a
theorem that mentions itself gets `depMax = own rank`); a record is never written to the shared region
before a declaration that reaches it exists, so a hand-written file that puts all its expression
records in front still gets bound nodes (each unit promotes its records out of the ownerless block of
the first unit, after its own names are claimed); the sweep never reads `loc` for records outside the
segment; promotion never interleaves with the writing of a block.

**Dropped records** (`safety` unsafe/partial, `isUnsafe`): the name is claimed as
`NameTable.DROPPED_UNSAFE`, nothing else is stored; the record's expression records stay in the segment
and are written with the next admitted declaration (into its block, unless its roots reach them). An inductive
record whose type records are all unsafe: the types and every unsafe constructor / recursor record are
dropped; if a **safe** constructor or recursor record remains, those records are loaded as a unit
without types (claims `numTypes = 0`), because the official replay remembers every safe constructor
and recursor record and requires a kernel-generated constant of that name in the end ("No such
constructor / recursor" otherwise). Admission decides such a unit; it is never accepted by itself.

**Inductive blocks.** Rows hold name, universe parameters and exported type of every member (types,
then constructors, then recursors, the order of the official reader); everything else the record says
goes into the claims blob (`BlockClaims`, numbers as 64-bit pairs, never truncated), including the
inputs `numParams`, `all`, `ctors`. Rule right-hand sides are shared terms and are named as roots.

**Sharing flags** (RECONCILIATION section 3). The loader counts, per segment record and before the
shared/block split, the references from later records of the segment and from the declaration's roots,
and derives SHARED (two or more), BODY (binder body or root) and, through
`LoadTarget.singleReferrer`, INVARIANT candidates (a non-leaf node with exactly one referrer in a
non-binding position). See "Deviations" for who uses them.

## Invariants

- One loader thread (plus the reader's scanner threads, which never call the loader). Nothing recurses on term depth (segment passes are loops; `perf/app-lam` and a
  60,000-deep proof are in the tests).
- A shared node is written after its children; a block record refers only to earlier records of its
  block or to shared terms; a shared node never refers to a block record.
- A record becomes a shared node of unit U only if a root of U reaches it (or through promotion, when a
  later unit refers to it); both happen after the names of that unit are claimed.
- Every expression record ends in exactly one place: the shared region, one proof block, or (records
  after the last admitted declaration) nowhere. Each block record is promoted at most once.
- Only two things make the loader reject: a declaration name declared twice (structural name equality,
  across all kinds and including dropped records and block members), and a dropped record named `Quot`,
  `Quot.mk`, `Quot.lift` or `Quot.ind` (the official quotient post-check fails on it and no unit exists
  that admission could blame). Everything else that is wrong with a file is either the reader's
  rejection or admission's business.
- `MalformedExportException.line()` is exact: the loader derives it from its record counters.
- Publication: a unit is published only when it is complete; the level snapshot that covers a
  frontier is published before the frontier; after a failure nothing is published except, for a
  decline with `publishOnDecline`, the complete units; `Store.loadComplete()` becomes true only in
  `finish()`.

## Non-canonical and hostile input (*agentic-note*: design-architecture-document, 3.4)

Verified against `leanchecker --from-export` v4.36.0 where marked (official); probe files in
`work/rlvm-load/official/`.

| Input | Handling | Test |
|---|---|---|
| other key order, whitespace, `\uXXXX`, CRLF, no final newline | reader's general path; same store | `CatalogueTest.nonCanonicalJsonLoadsToTheSameStore` |
| sparse / out-of-order ids | reader's id tables; ordinals stay dense | `sparseAndOutOfOrderIdsNeverReachTheLoader`, arena `other/sparse-name-index`, `level-index-out-of-order` |
| id used before definition, bound twice, blank line, bad `natVal`, unknown shape, bvar >= 2^20 - 1 | reader: reject | `readerLevelErrorsAreRejections` |
| name declared twice (any kinds, two name ids for one name, block members, dropped records) | loader: reject, "Duplicate declaration: N" with the line (official: same message) | `duplicateDeclarationNamesAreRejectedStructurally`, `SplitTest.aDroppedNameCannotBeDeclaredAgainAndViceVersa`, arena tutorial 132-135, 138, 139 |
| garbage first line; header without format version | never interpreted (official: accepted) | `theHeaderDecidesNothing` |
| no header line | first record swallowed: reject (official: "Expr not found 0") | same |
| header with a format version whose major is not 3 | read like any other file (official: it never reads line 1; 95 `CHANGE_META` mutants of the test kit); `LoadReport.formatVersionSupported` is false; `LoadOptions.withStrictFormatVersion(true)` declines | same |
| every expression record before the first declaration line (4 arena tests) | records wait in the ownerless block of the first unit; each unit promotes what its roots need after claiming its names: no unbound root | `StoreIntegrationTest.handWrittenArenaFilesHaveNoLateBoundRoots`, `recordsWrittenBeforeEarlierDeclarationsBindWhenTheyAreUsed` |
| two expression ids for one term | both kept | `namesAreInternedAndDuplicateTermsAreBothKept` |
| projection index above 2^31 - 1 | decline (official: accepted while the record is unused) | `numbersOutOfRange` |
| ids of 2^63 and above (also above 2^64) | ids like any other (`rlvm-export` keys them by their digits; official: ids are arbitrary naturals; 77 `RENUMBER_IDS` mutants) | same |
| numeric name components up to and beyond 2^64, hint heights modulo 2^32 | stored exactly / as the official reader reduces them | same |
| 30,000-digit numerals, 3 MB string literal, every writer escape, U+0000, non-BMP | loaded | `longLinesNumeralsAndStrings` |
| loose bound variable, wrong universe arity, unknown or later constant, duplicate universe parameter | loaded; flagged by the store, decided by admission | `looseVariablesWrongArityAndUnknownConstantsLoad` |
| two level records for one universe parameter (another index, or another name id of the same name), with a level above the parameter whose official respelling is not officially equivalent to it (`imax u 1`) | decline (finding A2-F1, section "Two level records for one universe parameter"; official: a constant used with the other record gets its declared type rebuilt, `Sort (max u 1)` against `Sort (imax u 1)`: rejected in 5 of 5 probes) | `DoubledParamsTest` |
| unsafe / partial record | dropped, name remembered (official: a user fails with "unknown constant") | `SplitTest.unsafeAndPartialRecordsAreDropped...` |
| dropped record named `Quot`, `Quot.mk`, `Quot.lift`, `Quot.ind` | reject (official: "Could not find quotient constant in final kernel env") | `droppedRecordsWithAQuotientNameAreRejected` |
| inductive block, all records unsafe | dropped as a whole (official: accepted) | `unsafeInductiveBlocks` |
| unsafe type records with a safe constructor / recursor record | unsafe records dropped, the safe ones loaded as a unit without types; `rlvm-admit` rejects it when no safe type of the file generates the name (official: "No such constructor / recursor") and declines otherwise | same |
| safe and unsafe type records in one block | decline (not probed) | same |
| safe types, unsafe constructor / recursor record | loaded, entry flagged `F_UNSAFE` (official: accepted; `rlvm-admit` declines such blocks in v1) | same |
| `{"inductive":{"types":[],"ctors":[],"recs":[]}}` | declares nothing, no unit (official: accepted) | `anEmptyInductiveLineDeclaresNothing` |
| `mdata` | dropped: the wrapped expression stands for it (practical policy, the default). Inside an inductive or quot record: decline (deviation 7), per unit with `publishOnDecline`. With `LoadOptions.strictMetadata` (strict policy): every declaration whose type or value carries it is declined; a `letE` with `nondep = true` counts as metadata there (deviation 7) | `SplitTest.mdataIsDropped...`, `CatalogueTest.metadataInsideInductiveAndQuotRecordsIsDeclined`, `PipelinedLoadTest.aDeclineIsTheStatusOfTheFirstUnitThatWasNotLoaded` |
| segment longer than `LoadOptions.maxSegmentRecords` (default 2^26 = 1 GiB of buffer) | decline | `aSegmentBeyondTheLimitIsDeclined` |
| more than 2^31 - 2^29 expression records, any `StoreLimitException` (2^28 - 1 shared nodes, 2 GiB pools, ...) | decline | only the exception mapping is tested (`BindingsTest.storeLimitsAreDeclines`); no input of that size was built |
| level deeper than 2^24 - 1 (`LevelLimitException`) | decline (see deviations) | not tested (needs 16.7 M records) |

## Parallel packing: packed runs (LP2, 2026-11-24)

**Why.** After wave 7 the loader thread did, for every expression record, everything itself: the
reader's replay, the segment record, the sweep, the block record. On exporter output four fifths of
the expression records are proof-private (scout X5: the records of a `thm` segment with an id above
the type's), and for those all of that work except resolving a reference to an EARLIER record can be
done without the loader's state. Now the reader's scanner threads do it (`rlvm-export` "Parallel
packing"; `-X scan-threads=N` with `-X io=mapped`), and the loader thread links.

**What the loader thread still does for a packed run** (`Loader.exprRun`, taken in place of the
`count` calls `exprApp` / `exprLam` / `exprForall` / `exprLet`):

1. *At arrival*: one row in the run table (the run occupies its records' places in the segment
   without being written there) and a marker in the pending list, so that its references to earlier
   records are resolved where the records stand in the file. Counters as for the single records.
2. *At the declaration line, before the pending references are resolved*: every run is decided
   (`decideRuns`). A run all of whose locally encoded children lie above `max(type root, last record
   before the segment)` of a theorem or opaque is LINKED: no root of the unit reaches a record above
   the type root (a record refers only to earlier records), so the run's records and every record
   between them and those children are unmarked, hence consecutive records of the unit's block, and
   the distances the scanner thread encoded are block distances. Any other run (a definition, an
   inductive block, a type root above the run, a segment that began later than the scanner thread
   could see) is EXPANDED into the segment records its calls would have written.
3. *In the pending loop, at the run's place*: its OPEN children - references to earlier records that
   the scanner thread could not encode - are resolved as `resolvePending` resolves a single one: the
   location is read, a block record is promoted first. Same reads, same promotions, same order.
4. *In the sweep*: the open children that are records of this segment get their places; then
   `LoadTarget.blockRun` (`StoreTarget`: `ProofBlocks.Writer.run`) writes the records: the bytes the
   scanner thread prepared are copied, the open references encoded where they belong; the
   locations of the run's records are filled in one loop.

**Early references.** A reference from a proof to an earlier record is, two times out of three, a
reference to a shared term that was finished long ago (Std: 33 % of all references of `app` records
reach back more than 430,000 records; the scanner threads are at most a few chunks ahead of the
loader). The loader publishes its location table to the scanner threads
(`ExportReader.EarlyRefs`, `Loader.runFormat()`): a scanner thread that finds a POSITIVE location
for a child - a shared term id - writes the reference into the record bytes; everything else stays
open. This is exact because a location that is a shared id never changes again (a record is swept
once; promotion replaces a block location by a shared id, never a shared id), so the loader thread
would read the very same value whenever it resolved the child itself; a stale or missing view only
leaves more children open. Such a term belongs to an earlier unit, for which the block writer's
bookkeeping (sharing counters of the open unit) does nothing; the "names an unresolved node" flag
is kept exact by `ProofBlocks.Writer.run` (it goes record by record when the largest id a scanner
thread wrote could be an unresolved node).

**THE INVARIANT: the store is word for word the store of the record-by-record path, on every file;
a file that fails does so with the same exception, message and line.** How it is kept:

- The scanner threads decide nothing. A run on the tape is an offer in front of the ordinary
  entries; the reader passes it on only if its records could not fail (dense ids with a known delta,
  the run's first id is the next id, every binder name bound), otherwise the records are delivered
  one by one. The loader refuses a run whenever the record-by-record path has something to do that
  a run cannot express: metadata was seen (`mdata` bits), the segment limit would be reached (the
  record that exceeds it is then found one by one, with its line), referrer counting is on, proof
  blocks are off (`LoadOptions.packedRuns` / `withPackedRuns(false)` switch runs off altogether).
- Linking is justified by the loader's own state at the declaration line (rule 2 above), not by
  what the scanner thread assumed. A run that was packed for the wrong segment - a declaration in
  another spelling that the scanner threads did not see, a type root in the middle of the records -
  is expanded; the expansion writes exactly the segment records of the calls.
- The order of everything that numbers shared nodes is unchanged: promotions happen in the pending
  loop in file order, shared nodes in the sweep.

**Evidence.** `PackedRunsLoadTest` (10 tests): loads with scanner threads, small chunks and runs
against loads with the reader's defaults - `Loaded.assertIdenticalStore` (every name, literal, export
node word, row, claims blob, decoded block record, location of every expression record), the same
failure (class, message, line) and the same partial store after a failure, the same counters - on the
230 arena files (batch and pipelined with a hand-off after every unit), 600 random exports, 150
proof-shaped files with definitions, dropped records, inductive blocks, types in the middle of the
records or from earlier declarations, 300 hostile ones (metadata, records in another spelling,
forward references, declarations the scanner threads cannot see), the segment limit, Init and Std
(Init: 5,010,446 of 6,137,092 expression records through 80,691 runs, none expanded; Std 7,709,869
of 9,928,514). The differential digest (`work/lp2/digest`: `all.sh`) compares the published jars
with the candidate in every reader configuration: section "Loading cost".

## Two level records for one universe parameter (finding A2-F1, 2026-11-24)

The official reader makes one level OBJECT per level record, and the official `instantiate` keeps a
level node only when its operands come back pointer-equal. So in a file with two records
`{"il":1,"param":u}` and `{"il":4,"param":u}` a constant `A.{u}` used as `A.{u'}` (the other record)
gets the levels of its declared type rebuilt through `mk_succ` / `mk_max` / `mk_imax` at that use site:
`Sort (imax u 1)` becomes `Sort (max u 1)`, which the official relation does not identify with the
declared spelling, and leanchecker v4.36.0 rejects `axiom B.{u} : (x : Sort (imax u 1)) -> A.{u'} x`.
RLVM interns levels by structure (both records are one id, instantiating `u` by `u` is the identity)
and accepted 5 of 5 such files (`work/a2/item4/files/E4, E5, E6, E11, E12`; found by A2). lean4export
shares levels: no file it writes has two records for one parameter (0 in the eight corpora and the
230 published tests).

**The rule** (`DoubledParams`, called from `Loader.levelParam` and for every succ / max / imax
record): the export is DECLINED (`UnsupportedExportException`, per unit like every decline: the units
before the line are still checked) when a parameter `p` has two level records - the same name,
structurally, whatever the indices and name ids - AND some level record `l` of the export that
mentions `p` is not officially equivalent (`LevelStore.isEquivOfficial`) to `rebuild_p(l)`, the level
with every node above `p` rebuilt through the official smart constructors (`rlvm-inductive`'s
`rebuildLevel` restricted to `p`). The record that completes the condition is where the load stops,
wherever it lies: the second record after the level, or the level after the second record. With
several doubled parameters the rebuild above all of them together is tested too; more than 8 doubled
parameters are declined without looking (bound on the work a hostile file can ask for). The reason A2
gives for the narrow form: at such a use site the official `instantiate` is the identity on names, so
only the spelling of the levels of the declared type changes, and if every level of the file is
officially equivalent to its respelling no comparison changes its answer. It only ever declines.

**What it costs.** Nothing on a file without a second record: one list append per level record and
one set probe per parameter record. The rule is evaluated in a private level arena with the run's
name order, so the run's level store is never written by it and a file that is not declined loads to
exactly the store it had before (differential digest: identical on all 18,005 small files, the
corpora and mathlib). `LevelInterner.structure()` (new, default null = rule off) is how the loader
reads the levels; `LevelStoreInterner` and `LevelChain` return their store.

**Evidence.** `DoubledParamsTest` (8 tests): the fixtures `src/test/resources/a2f1` = A2's twelve files
and thirteen probes of `work/lp2/a2f1/genq.py`, each with the answer of leanchecker v4.36.0
(`official.tsv`, taken in the arena Docker image): the five files the official checker rejects
because of the second record are declined, in batch and pipelined mode; `max u u`, `imax u 0`,
`succ u` above a doubled parameter are loaded (official: accepted; both engines accept); the 20
mutants of the test kit's corpora that repeat a parameter record (`work/a2/item4/dup-mutants.txt`)
keep their outcome. Two files are declined that the official checker accepts or rejects for another
reason: `Q6` (`imax u 1` above a doubled `u` in a file that uses no constant at a level with a
parameter: accepted officially; the rule does not look at use sites) and `Q7` (a wrong unit BEFORE
the second record: it is still checked, so the run rejects, as officially).

**Not established.** The rule rests on the official kernel observing a level only through its
normal form (`is_equivalent`, `is_geq`) and through tests that are semantic (`is_not_zero`, the Prop
test: probes `Q2`, `Q3`, `R1` - `R5` show that proof irrelevance and the theorem check accept
`Sort (imax u 0)` and `Sort (max 0 0)` as propositions). A level that the rebuild respells to an
EQUIVALENT but structurally different level (`max u u` to `u`, `imax u 0` to `0`) is therefore let
through; no probe found a verdict that depends on such a respelling, and none was proved impossible.
The stricter rule "decline when the rebuild changes ANY level above `p`" needs no such argument and
would decline 3 of the 20 mutants (two of which the official checker accepts).

## Deviations from the design documents

1. **Promotion is a step of its own, between the claim and the sweep** (*agentic-note*: design-architecture-document, 3.3 does it
   inside the sweep). Same result and the same `loc` reads; it removes the interleaving of promotion
   and block writing. A record referenced only by records that end up as garbage is promoted too.
   Related: for definitions, axioms, quot and inductive records the pseudo-code of 3.3 writes the whole
   segment as shared nodes, while its "Consequences" send records that no root reaches to the block.
   The loader implements the consequence, for every kind: with the pseudo-code four hand-written arena
   tests (`bad/bugs/large-elim-param`, `level-imax-leq`, `level-imax-normalization`, `nat-rec-rules`,
   all expression records in front) got roots with unbound CONST nodes and were declined by admission.
2. **Sharing flags: the store derives them, the loader's counters are the cross-check.** RECONCILIATION
   section 3 gives the referrer counter to the loader. The store owner implemented the derivation
   inside `TermStore` (it sees every reference when a node or block record is appended; block records
   get theirs at decode) and `StoreBuilder` takes no flags, so in production
   `StoreTarget.wantsSharingFlags()` is false and the loader skips its counting (about 0.5 instructions
   per input byte saved). The loader's implementation is complete, is what any other `LoadTarget`
   receives, and is tested two ways: against a naive recount on all corpora, and against the store's
   flags node by node (`StoreOracle.verifyStoreFlags`: BODY equal, the store's SHARED implies the
   loader's, "tabled" = any of the three flags equal, promoted copies SHARED). The two differ in one
   harmless detail: the loader counts a declaration root as a referrer, the store does not (such a
   node is BODY, hence tabled, on both sides).
3. **`RecordSink` is kept**, deprecated, next to `ExportSink` (the skeleton asked to delete it).
4. **Units of dropped records.** `LoadReport.units` counts the units of admitted declarations. The
   store's `dropUnsafe` additionally creates a constant-less unit with status `ST_SKIPPED` per dropped
   record (one for a dropped inductive block), so `DeclTable.unitCount()` = `LoadReport.units` + the
   number of dropped records.
5. **A level beyond the official depth limit is declined, not rejected** (the level module's README says
   reject; the official reader only panics there and what it answers afterwards was not established).
6. `LoadReport.nonCanonicalLines` cannot mean "hand-written file": the reader's byte templates do not
   cover levels, `sort`, `bvar`, literals or non-ASCII names, so exporter output always has
   general-path lines. The exact number is in `generalPathLines`.
7. `mdata` is dropped and `nondep` ignored, as *agentic-note*: design-architecture-document, 2.1 decides, **except that an inductive
   or quot record whose roots carry metadata is declined at load** (integrator, 2026-11-24). The
   official checker compares those records with the kernel's own declarations by `Expr.eqv`, which
   compares metadata, and tests their spines syntactically; four hand-written probes
   (`work/integration/probe/mdata-*.ndjson`) were accepted by RLVM and rejected by leanchecker 4.36
   before this rule. **The strict exactness policy (`LoadOptions.strictMetadata` /
   `withStrictMetadata(true)`, default OFF; OWNER-NOTES 13) extends the decline to every declaration**:
   an axiom, definition, theorem or opaque whose type or value carries the bit is declined
   (`Loader.declare`, "metadata (mdata) node in the type or value of a declaration"), because
   officially an `mdata` node is a term of its own for every cache of the kernel (`a = mdata b` is not
   `a = b` there) and hides what is under it from the kernel's syntactic tests (eager reduction,
   `is_pi`, `get_app_fn`): with the node dropped the reference engine accepted an export the official
   checker rejects (`work/s3/exports/s3-wc-pos-mdata-bad.ndjson`, finding S3-F2, traced by R2) and
   rejected one it accepts (`s3-mdata-eager-hidden-ok`). Under the practical policy, the default, the
   node stays transparent: it has no meaning in the theory, the accepts it causes are tolerated, and
   nothing lean4export can write (`--export-mdata`; the eight corpora have no `mdata` record) is
   declined for it. With the option off the loader is, record for record, the loader without it. The loader keeps one bit per expression ordinal ("wrapped by or contains
   metadata"), allocated at the first `mdata` record, so files without metadata (every lean4export
   file) pay one null test per record. A record wrapped by an `mdata` record counts as carrying
   metadata even when it is referenced directly (over-approximation; it can only decline). Metadata in
   definitions, theorems, axioms and opaques is accepted by default: it is invisible to type checking
   in the theory (strict policy: declined, above).
   **One exception to the transparency, under BOTH policies** (R2, 2026-11-25; OWNER-NOTES 13 (c): the
   practical mode may accept more than the official checker, it must not reject what that accepts): a
   declaration is declined if a metadata node stood around a record that is, or cannot be shown not to
   be, the constant `eagerReduce` applied to at most two arguments. Officially the node hides the
   application from `is_eager_reduce` (`infer_app`), so the argument is checked without eager reduction;
   with the node dropped the kernel entered eager reduction and rejected
   `work/s3/exports/s3-mdata-eager-hidden-ok.ndjson`, which the official checker accepts.
   `Loader.exprMData` looks at the wrapped record (`mayBeEagerReduce`: the records of the segment being
   read; an application of an earlier declaration or of a packed run cannot be read back and counts as
   "may be"), a second bit per ordinal (`hidden`) travels with the metadata bit, `declare` declines
   ("metadata (mdata) node around an application of eagerReduce"). It needs a metadata record and the
   name `eagerReduce` in the file; files without metadata pay one length test per name record and one
   compare per const record. Test: `SplitTest.metadataAroundAnApplicationOfEagerReduceIsDeclinedUnderBothPolicies`.
   The other official tests that a dropped node changes are listed in
   *agentic-note*: rep-reference-caches, section 6.1.
   Binder names and binder infos are not part of the official term identity (`expr_eq_fn<false>`).
   **The `nondep` flag of a `letE` is** (R2, finding R2-F1, 2026-11-24): `expr_eq_fn` compares
   `let_nondep(a) == let_nondep(b)` and the hash does not see it, so `let x := v; b` with and without
   the flag are two keys of every cache of the official kernel and ONE term for the store, which does
   not keep the flag (`work/r2/exports/r2-id-let-nondep-bad.ndjson`: official reject, accepted). A
   single edit of the flag does not change an official verdict (test kit), and it does not occur in
   real exports (0 of 550,621 letE records of the eight corpora and the arena tests have
   `nondep = true`). **With `LoadOptions.strictMetadata` a `letE` record with `nondep = true` is treated
   like a metadata node** (`Loader.exprLet` marks it; the reader's `RunPacker` never packs such a
   record, so the flag always reaches the loader): the declaration, inductive or quot record that
   contains it is declined. With the option off nothing changes (the flag is ignored as before; the
   store digest is identical in all reader configurations). Test:
   `SplitTest.aDeclarationWithANondepLetIsDeclinedInStrictMode`. The exact treatment - the flag as
   part of a LET node's identity (LP2's design on the board, 22:05Z) - is not implemented.
   `LoadReport.exprMData` counts the records. Test: `CatalogueTest.metadataInsideInductiveAndQuotRecordsIsDeclined`.
   The decline is for the rest of the file from that record on: with `publishOnDecline` the units
   before it are still checked (see "A decline is per unit"), so a file with an ill-typed earlier
   declaration is rejected, not declined. Keeping metadata exactly (MDATA nodes) would need a node
   kind in the store and in every engine; not done.
8. **The header decides nothing** (*agentic-note*: design-architecture-document, 3.4 and 4.9 decline another format major version).
   The official reader skips line 1 unread, so a file is what its records are; the decline is kept as
   an option (`LoadOptions.strictFormatVersion`) for a caller that wants it.
9. **Level ids of a pipelined load are not those of a batch load** (see "What differs from a batch
   load"). *agentic-note*: design-architecture-document, 3.5 asks for a snapshot of `rlvm-level`; the chain builds snapshots from
   `freeze()` + `newOverlay()` instead, at the price of that renumbering and of one copy of the level
   arrays per snapshot (loader) and per rebuilt worker view.

## Generated constants, late names, forward references: what the loader does and does not do

- Forward and unknown references are loaded unbound (`~nameId`, `F_UNRESOLVED`) and left to
  admission (`Policy.ForwardRefs`); the loader keeps no forward-reference table. In a pipelined run
  "unknown" is decidable only when the load is complete; `rlvm-admit` tests `Store.loadComplete()`.
- Constants the official kernel generates without a record (a recursor whose record is missing, the
  `Quot` constants of a file that lacks one of the four records): their rows and terms are written by
  the environment thread (generated region of the store, deferred there). In batch mode nothing is
  needed from the loader: a reference to such a name is an unbound node that admission resolves by
  name, and a later record of that name is found by admission ("already declared"). In pipelined mode
  the loader and the environment thread can claim the same name concurrently; that needs the late-name
  table of *agentic-note*: design-architecture-document, 4.2 in `rlvm-store` (`NameTable.claim` as a compare-and-set). The loader
  already makes every claim through the store (`StoreBuilder.reserve` / `dropName`) and rejects on a
  failed claim, so it needs no change when the store offers it. Until then such files are declined by
  admission, in both modes.
- Not present: namespace ordering.

## Tests

`. ./env.sh && $MVN -o -q -pl rlvm-load test`

61 tests in 8 classes, all green (`BigFilesTest`, parts of `StoreIntegrationTest` and of
`PipelinedLoadTest` are skipped when `work/exports/{init,std}.ndjson` or `work/arena-site/tests` are
absent). About 100 s on a quiet machine.

| Class | Target | What |
|---|---|---|
| `SplitTest` (13) | in-memory | the ownership split on hand-made files: block / shared, promotion, ownerless blocks, interrupted declarations, self references, dropped records, `mdata`, trailing records, a 60,000-deep proof, referrer counters |
| `CatalogueTest` (18) | in-memory | the table above, row by row, through reader and loader |
| `RandomExportTest` (1) | in-memory | 1,500 random exports (roots anywhere, garbage, dropped records between admitted ones, arbitrary inductive members) x {proof blocks, none} |
| `ArenaFilesTest` (2) | in-memory | all 230 published tests, with and without proof blocks |
| `BigFilesTest` (3) | in-memory | `init.ndjson`, `std.ndjson` (exact record counts), `init-prelude` without proof blocks |
| `BindingsTest` (3) | - | `NatStoreDecoder` against `BigInteger`, `LevelStoreInterner`, exception mapping |
| `PipelinedLoadTest` (10) | **real store, published jar** | L2. (a) *Identical store*: batch against pipelined, record by record (`Loaded.assertSameStore`: every name, literal, export node word by word, constant row, unit row, claims blob, every decoded record of every proof block, the location of every expression record; levels by structure with a bijection between the two numberings, lists by content), on all 230 files with a hand-off after every unit (and by bytes for the larger ones), on Init (every 16 units; the default interval) and on Std (every 256 units / 1 MiB); the counters of the two reports agree; a failed load fails identically. (b) *No unpublished datum*: 3 to 6 reader threads follow the frontier with the reader protocol while the loader runs and compute a structural digest (`StoreDigest`: rows, names, universe parameters, every node word of the type and value DAGs, the decoded proof block, literals, claims) of every unit the moment it is published, half of them newest unit first; every digest must equal the digest of that unit in a batch load: 17,630 units of the 230 files, 58,547 of Init, 101,146 of Std, each read by every reader. (c) batch through `LevelChain` is bit-identical to batch through `LevelStoreInterner` (230 files + Init); the chain's snapshots, id stability and thread rules; the hand-off signal; dropped records as units of the frontier; publication before a decline and none before a rejection; scanner threads from the options |
| `StoreIntegrationTest` (11) | **real store, published jar** | all 230 files (x2: with the loader's flags for the cross-check, and in the production configuration), all 230 without proof blocks, `init.ndjson`, `std.ndjson`, 400 random exports x2, name claiming / `depMax` / `F_UNRESOLVED`, records written before earlier declarations, the four hand-written arena files (no unbound root), dropped records and duplicates, `ExportLoader.load` |

The oracle (`Oracle`, `StoreOracle`): the same file is read into `rlvm.export.ColumnarStore` (every
record as written) and through the loader; both sides are reduced to structural 64-bit hashes (names,
levels, literals by content) and compared: record counts, every constant (kind, name, universe
parameters, type, value, hint, unit), every claims blob, and the location of **every** expression
record (`Loader.locationOf`), plus the sharing flags against a naive recount. On the real store the
loaded side is read back through `Terms`, `Overlay.blockRoot` (every proof block is decoded),
`DeclTable` and `LitPool`. Not covered by this oracle: the node summaries the store derives
(`looseRange`, `fvMask`, `depMax`, `lpSet`), which are the store's own tests' business.

Record counts were also compared by hand with `rlvm.export.tools.ExportStats` on Init and Std
(`work/rlvm-load/stats/`): names, levels, expressions per kind, declaration lines and constants per kind
are identical, and they equal the independently counted numbers of docs/research/01 section 2.12.

Mutation check of the L2 tests (by hand): publishing the store frontier before the level snapshot,
and reusing a snapshot although new lists exist, are both caught on the 230 files and on Init (a reader
reports "the frontier is at 6 units, the level snapshot covers 5 units"; a digest differs).

Mutation check (by hand, not part of the build): 13 single bugs injected into `Loader` (swapped block
references, saturation at 1, missing BODY mark, block distance off by one, `let` body not marked, wrong
value root, binder body reported as single referrer, stale shared id for an earlier record, a
definition's value not a root, rule right-hand sides not roots, a pending reference resolved to the
wrong record, marking started one record too low, the whole segment shared); all 13 made the suite fail.

Which files are rejected at load: exactly tutorial 132, 133, 134, 135, 138, 139, the files on which the
official checker stops with "uncaught exception: Duplicate declaration". Tutorial 136 and 137 get past
the official reader (kernel errors) and load here too. Nothing is declined.

What is still declined at load, on hand-written and mutated input only: metadata inside an inductive
or quot record (and, with `LoadOptions.strictMetadata`, in the type or value of any declaration); two level records for one universe parameter under a level that the official
`instantiate` respells inequivalently (finding A2-F1); an inductive record that mixes safe and unsafe type records; a projection index above
2^31 - 1; counts of 2^63 or more and ill-formed UTF-8 (reader); a segment above `maxSegmentRecords`;
the store's and the level store's representation limits.

## What the load produces (published jars, 2026-11-24)

| | init.ndjson | std.ndjson |
|---|--:|--:|
| input bytes / lines | 347,772,063 / 6,495,296 | 584,315,500 / 10,611,507 |
| expression records | 6,137,092 | 9,928,514 |
| written as shared nodes by the sweep | 1,065,880 (17.4 %) | 2,120,243 (21.4 %) |
| written to proof blocks | 5,071,212 in 37,601 blocks | 7,808,271 in 64,517 blocks |
| block bytes (per record) | 22.1 MB (4.37 B) | 34.3 MB (4.39 B) |
| promotion requests / nodes copied | 250,747 / 593,925 | 393,622 / 1,015,436 |
| export nodes in the end | 1,659,805 (27.0 %) | 3,135,679 (31.6 %) |
| constants / units | 59,979 / 58,547 | 103,885 / 101,146 |
| ownerless blocks (records no root reached) | 1 (4 records) | 1 (2 records) |
| largest segment | 26,418 records | 66,890 records |
| store, all shared structures | 140 MB | 229 MB |

The one-pass split plus promotion ends at the exact global ownership split: `ExportStats` (two passes
over the whole file) reports 1,660,489 shared records for Init, the load ends with 1,659,805 export
nodes. The price is that 11.7 % (Init) / 13.0 % (Std) of the block records exist twice.

## Loading cost

Measured like the arena measures a checker (`perf stat` in the `rlvm-arena` container, 8 performance
cores, 16 GB; `rlvm-load/bench/measure-load.sh LABEL FILE [LoadTool options]`, raw lines in
`work/rlvm-load/measure/runs.jsonl`). JVM with the launcher's flags (`-XX:+UseSerialGC
-XX:-UseJVMCICompiler -XX:CICompilerCount=2`, 4 GiB heap), one cold process per row, ONE run each (no
repetitions, so differences of a few percent between rows mean nothing), published jars of 2026-11-24
(rlvm-load 05:56Z, rlvm-store 05:43Z).

| File | Configuration | CPU s | wall s | peak RSS MB | instr G | instr / input byte |
|---|---|--:|--:|--:|--:|--:|
| init (347.8 MB) | reader alone (`ExportLoad --sink null`) | 0.49 | 0.32 | 65 | 4.70 | 13.5 |
| init | reader + loader, target that stores nothing (`--target null --no-flags`) | 0.81 | 0.45 | 93 | 8.51 | 24.5 |
| init | **reader + loader + store** (`LoadTool --target store`) | **1.37** | **0.84** | **266** | **13.33** | **38.3** |
| init | same, `--no-proof-blocks` | 1.29 | 0.92 | 1,032 | 11.24 | 32.3 |
| std (584.3 MB) | reader alone | 0.66 | 0.46 | 64 | 6.14 | 10.5 |
| std | reader + loader, target that stores nothing | 1.08 | 0.78 | 113 | 10.56 | 18.1 |
| std | **reader + loader + store** | **2.01** | **1.42** | **393** | **18.17** | **31.1** |
| std | same, `--no-proof-blocks` | 1.87 | 1.55 | 1,384 | 14.15 | 24.2 |

A cold JVM dominates these numbers: on Init the JIT compilers alone take 0.58 s of CPU time in the
store configuration (`-XX:+CITime`, measured on the host before the last loader change: C2 0.50 s), 0.37 s with the null
target and 0.19 s for the reader alone. Steady state, from six
loads in one process minus one (Init, per load):

| Layer | CPU s | instr G | instr / input byte |
|---|--:|--:|--:|
| reader (null sink) | 0.21 | 1.83 | 5.3 |
| + loader (production path: no flag counting) | + 0.13 | + 1.32 | + 3.8 |
| + store (nodes with derived facts, block packing, promotion) | + 0.39 | + 2.31 | + 6.6 |
| total | 0.73 | 5.46 | 15.7 |
| (loader's own flag counting, when a target wants it) | + 0.00 | + 0.35 | + 1.0 |

Reading: (1) against *agentic-note*: design-architecture-document, 3.6 (12 instructions per input byte at M3, 8 at M5, "minus process
start") the steady state is at 15.7; a cold JVM process is at 31-38 on these small inputs, and that gap
is the platform question of *agentic-note*: design-architecture-document, 7.1, not loader code. (2) "reader alone" is a lower bound
that flatters the reader: with a sink that ignores its arguments the JIT can drop work, so part of the
3.8 attributed to the loader may be the reader's (not separated). (3) Before the store's second publication
`ProofBlocks.promote` re-parsed the block prefix on every call (141.8 M record parses for 250,743 calls
on Init): Init cost 2.46 s CPU / 26.0 G instructions and Std 3.97 s / 40.6 G. The store owner replaced
it by a sparse offset index and a walk over the reachable records after the measurement was reported
(`work/rlvm-load/measure/runs-store-publication-1.jsonl` has the old runs;
`runs-before-reachability-split.jsonl` the runs made before the split by reachability for all kinds). (4) RSS without proof
blocks is the export region growing by copy inside a 4 GiB heap that is never collected, not live data
(196 MB of nodes for Init).

### Mathlib: batch, parallel scan, pipelined (work package L2)

`mathlib.ndjson`: 6,326,141,213 bytes, 112,081,718 lines, 107,037,712 expression records, 721,845 units,
739,167 constants; the load ends with 38,739,557 export nodes, 78,207,172 block records in 475,132
blocks (9,909,017 promoted), 2.42 GB of shared structures. Same method as above (arena container, 8
performance cores, 16 GB, `perf stat`, one cold JVM per run with the launcher's flags and a 9 GiB heap:
`work/rlvm-load/measure/l2-matrix.sh work/exports/mathlib.ndjson mathlib 2` and `l2-breakdown.sh`; raw
lines in `work/rlvm-load/measure/runs.jsonl`, labels `mathlib-*`). Two runs per row, both shown; the
layer rows are single runs. Measured 2026-11-24 08:27-08:40Z with the loader of 08:21Z, store 07:16Z,
reader 06:52Z. **Caveat:** the steward's platform matrix was using the same cpuset during these runs and
I did not hold the ladder lock (`work/perf-history/.ladder.lock`; I learnt of it afterwards), so
differences below about 0.5 s of CPU time between rows are noise.

| Configuration (`LoadTool --target store ...`) | CPU s | wall s | peak RSS MB | instr G | instr / input byte |
|---|--:|--:|--:|--:|--:|
| reader alone (`ExportLoad --sink null`) | 4.12 | 3.53 | 100 | 38.4 | 6.1 |
| reader + loader, target that stores nothing (`--target null --no-flags`) | 7.55 | 6.36 | 604 | 70.9 | 11.2 |
| **batch** | **15.80 / 15.52** | **14.82 / 13.93** | **3,802 / 3,807** | **110.0 / 114.2** | **17.4 / 18.1** |
| batch, `--no-proof-blocks` | 15.11 | 13.93 | 8,094 | 102.1 | 16.1 |
| batch, `--scan-threads 2` | 16.32 / 16.41 | 11.26 / 11.38 | 3,857 / 3,854 | 121.2 / 124.4 | 19.2 / 19.7 |
| batch, `--scan-threads 4` | 18.66 / 16.73 | 11.66 / 11.32 | 3,842 / 3,825 | 125.1 / 123.0 | 19.8 / 19.4 |
| **`--pipelined`** (hand-off every 4,096 units / 32 MiB: 204 snapshots) | **15.45 / 16.30** | **13.89 / 14.70** | **4,183 / 4,175** | **112.9 / 112.9** | **17.8 / 17.8** |
| `--pipelined --scan-threads 4` | 16.85 / 16.91 | 11.28 / 11.47 | 4,218 / 4,221 | 123.0 / 122.2 | 19.4 / 19.3 |
| `--pipelined --followers 8` | 17.43 / 17.57 | 14.27 / 14.98 | 4,799 / 4,896 | 118.8 / 118.3 | 18.8 / 18.7 |
| `--pipelined --scan-threads 4 --followers 8` | 18.07 / 18.57 | 11.99 / 12.35 | 4,904 / 4,901 | 124.9 / 125.2 | 19.7 / 19.8 |

Reading:

1. **Where the CPU time goes** (batch, 15.7 s): the reader 4.1 s, the loader's own work 3.4 s, the store
   8.1 s (nodes with derived facts, block packing, promotion; `rlvm-store`). 17.4 instructions per input
   byte against the budget of *agentic-note*: design-architecture-document, 3.6 (12 at M3, 8 at M5): not met. The whole load is a
   quarter of the best checker's total CPU time on this file (62.4 s), so it is worth a work package of
   its own (store and loader together); this package did not reduce it.
2. **Parallel scanning** takes 3 s off the wall clock (14.4 s -> 11.3 s, -21 %) for about 0.7 s more CPU
   time (+4 %) and 10 G more instructions with two scanner threads; four are no better, because the
   sink thread (loader + store, about 11 s) is the limit. In a pipelined run on a busy runner the
   scanner threads compete with the workers; in a batch run they are free.
3. **Pipelined publication itself costs nothing measurable** (CPU time within noise of batch, +0.8 G
   instructions: 204 freezes of the new levels and 204 copies of the level arrays). Peak RSS is 0.37 GB
   higher: dead snapshot stores that the serial collector had not yet reclaimed, not live data.
4. **Keeping up with the frontier** costs a worker one level view (a copy of the level arrays, plus a
   term overlay) per snapshot it has to cross: 8 followers that rebuild at every one of the 204
   snapshots add 1.5 s of CPU time in total (0.9 ms per view) and 0.7 GB of garbage. A real worker
   lags behind the loader and crosses many snapshots with one rebuild; a longer hand-off interval
   (`withPublishEvery`) trades the same way. Since 2026-11-25 that copy is gone for the workers: they
   read the frozen level store in place and rebase their overlay onto a newer snapshot for the price
   of a reset (*agentic-note*: rep-OPT-C); the figures of this item are those of the copying layout.
5. What pipelining buys is the load's share of the wall clock: with the reference engine on mathlib
   (139 s wall, of which the load is 12-14 s) about 10 %; with an engine near the leaders' 18 s it is
   the difference between "load, then check" (about 14 s + check) and max(load, check).

### Wave 7: the load phase in the native image, profiled and attacked (2026-11-24)

Method: CE native image `-O3` with local symbols, arena container, `rlvm --threads 1 FILE -X engine=machine
-X stop-after=load` = reader + loader + store and nothing else; `perf record -F 4000` for the shares
(`work/pipeline-w7/perf/*-load-*.txt`), `perf stat` task-clock for the CPU time (`work/pipeline-w7/loadcpu.sh`,
alternating runs inside one hold of the measurement lock).

Where the load of the published jars went (mathlib 66,246 samples = 16.6 s; Init 3,260 = 0.82 s):

| Share of the load phase | mathlib | Init | What it is |
|---|--:|--:|---|
| `ProofBlocks` promotion (`pending`, `seek`, `promote`, `blockOfRecord`, `promoPut`, ..) | 20.4 % | 18.2 % | memo probes, block search, record skipping: cache misses (store) |
| reader (`Scanner.num` 6.6, `Mem.u64` 4.0, `ExportReader.sequential` 3.8, `Linker`, copy) | 20.3 % | 24.0 % | `rlvm-export`; `Mem.u64` and `Scanner.num` are out-of-line calls in the image |
| `Loader.exprApp` (+ `exprLam` / `exprForall`) | 12.2 % | 10.6 % | the read of `loc[ordinal]` for a reference to an earlier record |
| `ProofBlocks$WriterImpl` (`ref` 8.0) | 9.3 % | 11.2 % | `ref` loaded the shared node it names (store) |
| `NodeCtx` (`writeApp` 6.7) | 9.2 % | 6.3 % | reads of the children's nodes |
| kernel | 8.3 % | 12.4 % | page faults of the mapped input and of fresh arrays |
| names (`Loader.nameStr`, `NameTable.internNum`) | 7.1 % | 5.8 % | hash probes into a table far larger than the caches |
| `Loader.sweep`, `ref`, `markReachable` | 5.2 % | 6.0 % | |

Changes of this wave (the store's are in `rlvm-store/README.md`, "Wave 7"): the promotion memo as a direct
map, a coarse record-to-block index, positions of the last group in `seek`, no load of the shared node in
the block writer; in the loader **deferred location reads** (section "What the loader does": the record
path no longer reads `loc`, all references of a segment to earlier records are resolved in one loop at
the declaration line) and the **touch hint** from the mark pass. The store that is built is identical
word for word: `work/pipeline-w7/digest` (`DigestAll` + the loader's `StoreDigest`: outcome or exception,
every word of the export region, every block row, every word of every decoded block, every name, the
structural digest of every unit) gives the same line with the published jars (store 45df9b63e7af, load
30617170f7a3) and with the new ones for all 230 arena tests, all 16,928 mutants of the tutorial, bugs and
mdata corpora, the 847 prelude mutants, Init, Std, the Cedar slice, reachability and mathlib (721,845
units, 38,739,557 export nodes, 78,207,172 block records, 9,909,017 promoted).

CPU time of the load phase (`perf stat` task-clock, arena container, alternating runs in one hold of the
measurement lock; `work/pipeline-w7/loadcpu.txt`, 13:44Z, host load average 60-90, so only the pairs
compare):

| Load phase, native `-O3`, one thread | published jars | with the store's changes | |
|---|--:|--:|--:|
| Init (348 MB), CPU ms, 3 alternating runs | 897 / 800 / 797 | 709 / 682 / 693 | -13 % |
| Std (584 MB), CPU ms, 3 runs | 1,338 / 1,373 / 1,504 | 1,150 / 1,156 / 1,287 | -15 % |
| mathlib (6.33 GB), CPU s, 1 run | 16.65 | 13.77 | -17 % |
| instructions, Init / Std / mathlib (G) | 7.44 / 12.47 / 132.3 | 7.17 / 12.03 / 128.2 | -3 % |

The published state of the wave (store f85c730603b8 + loader 26242def2701; the native image
`work/pipeline-w7/dist-b` holds the loader as published and the store without the unrelated
`Overlay.remapBlocks` fix) against the jars before it, and the two loader changes switched off one at a
time on the same binary (`RLVM_LOAD_EAGER_LOC=1`, `RLVM_LOAD_NO_TOUCH=1`), all in ONE hold of the
measurement lock, alternating (15:15-15:17Z, host load average 107-111; `work/pipeline-w7/final-load.txt`;
CPU time = wall clock here, the load is one thread; not taken through `bench/quiet.py`, which came later):

| Load phase, native `-O3`, CPU ms | before the wave | published | `loc` read at arrival (old way) | no touch hint |
|---|--:|--:|--:|--:|
| Init, 3 alternating rounds | 844 / 885 / 892 | **681 / 673 / 667** | 688 / 740 / 684 | 669 / 661 / 681 |
| Std, 2 rounds | 1,377 / 1,410 | **1,102 / 1,136** | 1,152 / 1,189 | 1,158 / 1,148 |
| mathlib, 1 run | 17,182 | **12,396** | 13,352 | 12,868 |
| instructions (G), Init / Std / mathlib | 7.44 / 12.47 / 132.3 | 7.42 / 12.47 / 133.1 | 7.28 / 12.22 / 130.5 | 7.37 / 12.36 / 131.8 |

Reading: (1) the wave takes 24 % off the load of Init (885 -> 673 ms, medians), 20 % off Std, 28 % off
mathlib (17.2 -> 12.4 s), with the SAME number of instructions: all of it is cache misses that no longer
happen or that overlap. (2) Deferring the `loc` reads is worth 2 % on Init (lower in each of the three
rounds), 4 % on Std (both rounds), 7 % on mathlib (one run) and costs 2 % more instructions; it grows
with the file because `loc` outgrows the caches. (3) The touch hint is not established: Init +0.6 %
(noise), Std -3 % (two rounds), mathlib -3.7 % (one run), +0.7 % instructions; it never lost on the two
larger files and stays on, but it has not had its three repeats there.

A whole check with the machine as engine, one worker, same hold of the lock, one run each
(`final-full.txt`; the two images differ in the machine jar, 608b84b2b142 against f82074b207f1, with equal
instruction counts): Init 8.07 -> 7.91 s CPU (load phase by the driver's clock 0.82 -> 0.67 s), Std 13.74
-> 13.34 s. By classes (`perf record`, `work/pipeline-w7/perf/base-init*`, `final-b-init*`): what is neither
a machine class nor the kernel is 19.9 % = 1.59 s of Init before and 19.1 % = 1.51 s after; the classes of
reader, loader, store writers and block decoding 0.87 -> 0.78 s. **The targets of the wave (0.5 s on
Init for everything that is not the engine; 8 s of CPU for the mathlib load) are not reached.**

The two diagnostic switches of that wave (`RLVM_LOAD_EAGER_LOC`, `RLVM_LOAD_NO_TOUCH`, environment
variables read when a `Loader` was created) are gone since LP2: the loader reads no environment
variable and no system property. Locations are always read at the declaration line (the packed runs
depend on it); the touch hint is on (LP2's measurement of it: "LP2: warm measurements" below).

What is left, by the profile of the published state (mathlib, load phase, 49,559 samples = 12.4 s;
`work/pipeline-w7/perf/final-b-load-mathlib.txt`): the reader 26 % (`Scanner.num` 9.3 %, `Mem.u64` 5.7 %,
`ExportReader.sequential` 5.0 %, `Linker` 3.3 %; `Mem.u64` and `Scanner.num` are out-of-line calls in the
image: `-H:PriorityForceInline=rlvm.export.Mem.u64` in the image build is the first thing to try, not
measured), kernel 11 % (page faults), promotion 11 % (`seek` 4.8, `promote` 3.7), the `loc` reads 7.3 %
(`resolvePending` 5.1 + `exprApp` 2.2; they were 12.1 % in `exprApp`), children's nodes 9.4 %
(`NodeCtx.writeApp` 5.1 + `TermStore.touch` 4.3: the hint moves the miss more than it removes it), names
8.4 %, `Loader.sweep` 4.6 %, the block writer 5.2 %. The 8 s target for mathlib is not reached. The structural step that
would reach it is X5's finding (requests.log, 14:04Z): on exporter output the private records of a theorem
are an id range, so the loader need not parse, pack or promote them serially at all (four fifths of the
expression records), and the workers parse their own proofs.

### LP2: warm measurements of the load path (2026-11-24)

Method (OWNER-NOTES 12: warm wall clock, 8 workers, loading pipelined with checking). Everything
below was taken inside `bench/quiet.py` windows on the 8 measurement cores. Passes: `bench/warm.sh`
(`rlvm -X passes=N`, mode reload, machine engine with its default switches, `-X load=pipelined`,
heap 6 GB for Std and cedar, the launcher's 13 GB for mathlib); "loader" is the pass line's `load_ms`,
the wall clock of the loading thread; rows in `work/lp2/warm.tsv`, logs in `work/lp2/logs`
(`work/lp2/passes.py` prints them). "Published" = the jars before LP2 (`work/lp2/dist-base2`: export
392c19f95db4, store f85c730603b8, load 492a436c2c95), "packing" = `work/lp2/dist-pack2` with
`-X io=mapped -X scan-threads=N` (the published LP2 code plus a measurement switch; every other jar
equal to dist-base2's, machine 8a8ea5644b24). The default reader is `-X io=buffered` on one thread.

**1. The load alone** (JVM, the launcher's VM flags, `rlvm.load.tools.LoadTool`, later loads of one
process = warm; wall clock of the whole load; `work/lp2/batches.log`, lines `LOAD`):

| Load alone, quiet cores, s | Std | cedar | mathlib |
|---|--:|--:|--:|
| published, one thread | 0.92 | 1.36 | 9.2 - 9.6 |
| published, `scan-threads` 2 (tokenising only) | 0.65 | 1.02 | 7.2 - 7.5 |
| packing, `scan-threads` 2 | 0.48 - 0.56 | 0.73 | **5.8 - 6.2** |
| packing, `scan-threads` 3 | 0.50 - 0.55 | 0.73 | 5.7 - 6.0 |

The load path is 1.8 times (Std, cedar) and 1.6 times (mathlib) shorter than the published
one-thread load and 1.2 - 1.4 times shorter than the published load with scanner threads. Mathlib
with two scanner threads is under the 7 s target, also in the first load of a process (6.8 s). A third
scanner thread buys nothing on quiet cores: the linking thread is the limit again.

**2. Warm passes, 8 and 7 workers on 8 cores** (pass wall / loader thread, s; JVM: median of passes
2 - 7; native image: of passes 2 - 5; mathlib: the better of passes 2 and 3; each cell one row, a
second row of the same configuration after a comma):

| Pass wall / loader, s | Std | cedar | mathlib |
|---|--:|--:|--:|
| JVM, 8 workers, published, default reader | 1.67 / 1.09, 1.61 / 1.11 | 2.06 / 1.70, 2.17 / 1.72 | 20.56 / 12.84 |
| JVM, 8 workers, published, `scan-threads` 2 | 1.67 / 1.25, 1.60 / 1.18 | 2.04 / 1.65, 2.12 / 1.79 | - |
| JVM, 8 workers, packing, 2 scanner threads | 1.64 / 1.04, 1.68 / 1.02 | 2.09 / 1.77, 2.10 / 1.61 | 21.05 / 11.11 |
| JVM, 8 workers, packing, 3 scanner threads | 1.64 / 0.90 | 2.08 / 1.64 | - |
| JVM, 7 workers, published, default reader | 1.49 / 1.08 | 2.24 / 1.59 | - |
| JVM, 7 workers, packing, 2 scanner threads | 1.56 / 0.97 | 1.94 / 1.37 | 22.66 / 10.75 |
| JVM, 7 workers, packing, 3 scanner threads | 1.52 / 0.89 | 2.10 / 1.35 | - |
| native, 8 workers, published, default reader | 1.81 / 1.37 | 2.50 / 2.20 | 27.38 / 15.37 |
| native, 8 workers, packing, 2 scanner threads | 1.85 / 1.08 | 2.51 / 2.05 | 28.23 / 13.84 |
| native, 8 workers, packing, 3 scanner threads | 1.81 / 1.27 | 2.44 / 1.74 | - |
| native, 7 workers, published, default reader | 1.88 / 1.28 | 2.45 / 1.90 | - |
| native, 7 workers, packing, 2 scanner threads | 1.92 / 0.94 | 2.56 / 1.82 | 31.03 / 13.73 |

Seven passes are too few for the packing rows on the JVM: the new code is compiled by two compiler
threads that share the cores with eight workers, and the loader's time still falls until pass 6.
Twelve passes of one process (the JFR runs of point 4, loader thread per pass, ms): Std published
1700 1499 1250 1085 1218 1216 1253 1166 1072 1137 1112 1130, packing 1816 1509 1261 878 826 763 833
758 789 765 751 780; cedar published 2713 1930 1866 1734 1953 1810 1786 1733 1720 1617 1737 1695,
packing 2585 1642 1721 1803 1661 1471 1303 1170 1143 1283 1151 1282. Steady state beside 8 workers:
**Std 1.13 -> 0.77 s, cedar 1.70 -> 1.2 s**.

**3. Spare cores** (4 workers on the 8 cores, JVM, median of passes 2 - 7): the loader thread has a
core of its own and its wall clock is its work:

| 4 workers: pass wall / loader, s | Std | cedar |
|---|--:|--:|
| published, default reader | 2.25 / 0.89 | 3.17 / 1.37 |
| published, `scan-threads` 2 | 2.18 / 0.67 | 3.01 / 1.10 |
| packing, 2 scanner threads | 2.21 / **0.46** | 3.08 / **0.78** |
| packing, 3 scanner threads | 2.27 / 0.47 | 2.92 / 0.78 |

**4. Where the loader thread's time goes** (JFR, CPU-time samples every 1 ms, warm JVM passes with 8
workers on quiet cores, recording from the 14th / 18th second on; `work/lp2/jfr/*-q-*.jfr`,
`work/lp2/jfrsum.py`; share of the loading thread's CPU time by class):

| Loading thread, 8 workers | Std published | Std packing | cedar published | cedar packing |
|---|--:|--:|--:|--:|
| reader: `Scanner` + `read` (+ `Tape` replay with scanner threads) | 32.1 % | 4.5 % | 31.3 % | 4.4 % |
| `Loader` (segment, pending references, sweep, runs) | 15.6 % | 16.6 % | 16.3 % | 17.1 % |
| `ProofBlocks` (promotion) | 12.6 % | 18.9 % | 11.4 % | 17.4 % |
| `NameTable` | 10.2 % | 15.7 % | 13.8 % | 19.7 % |
| block writer (`ProofBlocks$WriterImpl`) | 9.3 % | 14.4 % | 7.7 % | 11.9 % |
| shared nodes (`TermStore`, `NodeCtx`) | 10.2 % | 18.6 % | 10.6 % | 17.9 % |
| `IdSpace`, `Linker` | 3.4 % | 4.9 % | 3.2 % | 5.7 % |
| CPU samples of the loading thread in the recording | 7,723 | 4,723 | 11,485 | 4,894 |
| CPU samples of the two scanner threads | - | 4,607 | - | 4,459 |

The loading thread needs 0.6 times the CPU it needed on Std and 0.43 times on cedar; the scanner
threads take as much again between them. What is left on the loading thread is what numbers shared
structures and therefore has to stay in file order: names, promotion, shared nodes, the references
of proofs that the scanner threads could not resolve, the records that are not proof-private.

**5. Reading.**

- On quiet cores, and whenever the loading thread has a core (4 workers), the packing nearly
  halves the load: the loader is busy for 21 % (Std) and 25 % (cedar) of a pass instead of 40 - 43 %.
- **With 8 workers on 8 cores it changes the pass by nothing measurable** (Std 1.64 - 1.68 against
  1.61 - 1.67 s; cedar 2.09 - 2.10 against 2.06 - 2.17; mathlib 21.05 against 20.56, one row each).
  Such a pass is bound by its total CPU time (CPU / wall 7.1 - 7.4 of 8 cores), of which the whole
  load is a tenth, and the packing moves that tenth between threads, it does not remove it. The
  loader thread's own wall clock there is set by the share of a core it gets and by the caches it
  shares with eight workers (its CPU time beside them is 1.2 - 1.35 times its time alone): steady
  state Std 1.13 -> 0.77 s (47 % of the pass), cedar 1.70 -> 1.2 s (57 %); the per-pass values of one
  row scatter between 0.55 and 1.33 s on Std. The target "less than half of a warm 8-worker pass" is
  reached on Std and missed on cedar; with 7 workers it is 0.62 / 0.70 of the pass in the 7-pass
  rows. Mathlib: 12.8 -> 11.1 s of a 21 s pass on the JVM (0.53), 15.4 -> 13.8 s of 28 s in the
  image (0.49).
- So the loader is no longer what bounds a pass in which checking leaves a core free, and it is not
  what bounds today's 8-worker passes either: those wait for less checking CPU.
- Published scanner threads without packing do not help beside 8 workers (Std 1.09 -> 1.18 - 1.25 s
  of loader time, cedar 1.70 -> 1.65 - 1.79): two more threads on full cores for a quarter less work
  on the loading thread.

**6. The smaller questions.**

- *Touch hint* (wave 7, published unmeasured): KEPT. Three alternating pairs of processes on Std and
  on cedar and one on mathlib, load alone, hint on against off (medians of the later loads, s): Std
  0.51 / 0.48 / 0.51 against 0.53 / 0.53 / 0.52; cedar 0.77 / 0.71 / 0.76 against 0.79 / 0.79 / 0.82;
  mathlib 5.8 - 6.0 against 6.0 - 6.2. On is faster in every pair, by 2 - 10 %. Its switch is removed.
- *`-X io=mapped` against the default reader, one thread, published jars, 8 workers* (first windows,
  `work/lp2/dist-base`): Std 1.54 / 1.06 against 1.64 / 1.21 s, cedar 2.10 / 1.68 against 2.18 / 1.64;
  with 7 workers 1.51 / 1.02 against 1.62 / 1.12 and 2.15 / 1.65 against 2.10 / 1.63: no difference
  beyond the spread between rows.
- *Publication interval* (packing, 2 scanner threads, 8 workers; default 4,096 units / 32 MiB): 1,024
  units / 8 MiB Std 1.54 / 0.93, cedar 1.90 / 1.37; 16,384 units / 128 MiB Std 1.55 / 0.91, cedar
  2.14 / 1.35. One row each in one window; against the default rows of another window (1.64 - 1.68,
  2.09 - 2.10) the differences are of the size of the spread between windows: NOT RESOLVED, the
  default stays.
- *7 workers against 8 on 8 cores*: no winner on Std and cedar (JVM 1.49 - 1.56 against 1.61 - 1.68
  and 1.94 - 2.24 against 2.04 - 2.17), 8 better on mathlib (21.05 against 22.66 s) and in the image
  (cedar 2.51 against 2.56, mathlib 28.2 against 31.0): 8 workers stay.

- *Native image, `Mem.u64` / `Scanner.num` out of line* (wave 7's first thing to try): an image of the
  published jars built with `-H:DirectedInline` for every call of the two from `Scanner`
  (`work/lp2/inline-rules.txt`, `work/lp2/dist-pack2i`) against the image of the same jars without it
  (`work/lp2/dist-final-n`), 8 workers, median of passes 2 - 6, pass wall / loader: default reader Std
  1.48 / 1.31 against 1.54 / 1.36 s, cedar 2.06 / 1.97 against 2.09 / 2.01; two scanner threads Std
  1.52 / 1.33 against 1.50 / 1.31, cedar 2.05 / 1.96 against 2.07 / 1.94. No difference beyond the
  spread between rows: NOT adopted (whether the rule really inlined the calls was not verified: the
  images have no symbols).
- *The loading thread in the image* (`perf record` in the arena container, warm passes with 8
  workers, Std; the images have no symbols, so only the shares per thread, `work/lp2/perf`): published
  jars, default reader: loading thread 9.2 % of all samples (12.8 % of them in the kernel); packing
  with two scanner threads: loading thread 5.1 % (16.6 % kernel), scanner threads 5.8 % together;
  mathlib with packing: 3.6 % and 3.5 %.

**7. The published state with a faster machine** (window of 22:01Z: the jars published at 20:29Z in a
distribution with the machine jar of 19:15Z, `78c74ca92785`, against the same distribution with the
three jars of before LP2, `work/lp2/dist-prev`; JVM launcher, 8 workers, 12 passes, median of passes
6 - 12, two rows each):

| 8 workers, pass wall / loader, s (CPU of the pass) | Std | cedar |
|---|--:|--:|
| jars before LP2, default reader | 1.30 / 1.04 (9.2), 1.18 / 1.02 (8.7) | 1.65 / 1.59 (11.5), 1.67 / 1.62 (12.1) |
| published, `io=mapped`, 2 scanner threads | 1.31 / 0.98 (9.4), 1.15 / 0.87 (8.6) | 1.68 / 1.31 (12.1), 1.54 / 1.46 (11.5) |

With that machine the passes are a quarter shorter than in the tables above and the loader thread
runs for 80 - 97 % of them with the old jars - and still the packing does not shorten the pass (Std
1.24 -> 1.23 s, cedar 1.66 -> 1.61 s, means of two rows): the pass is at its CPU bound (8.6 - 9.4 s and
11.5 - 12.1 s of CPU on 8 cores = 1.1 - 1.2 s and 1.45 - 1.5 s), every thread ends when the cores run out
of work, and the load's TOTAL CPU (loading thread plus scanner threads: about 1.3 s on Std against
1.1 s on one thread) is what counts there. **What would shorten such a pass is less CPU in the load
as a whole**, not a shorter loading thread: see "Observations".

## Observations for the next work packages

- (LP2) On 8 saturated cores the load costs a pass its total CPU time, now 13 - 14 % of a Std or
  cedar pass. Candidates, by the profiles above: on the scanner threads the tape is written twice
  for a window (entries, then the copy with run headers: reserve the header in place instead), ids
  are parsed where they could be compared with the expected text (the reader's rejected trick: it
  costs instructions and saves time, and time is the metric now), and the lookups for early
  references are cache misses that the loading thread no longer pays but the scanner threads do; on
  the loading thread names (16 - 20 % of it), promotion (17 - 19 %) and the shared nodes (18 %).

- The reader needs an id translation table for names with the real store (`LoadReport.readerIdTables`):
  the store pre-interns the 34 special names, so the name ids the loader returns are not consecutive.
  One table load per name reference; not measured separately. Expression ordinals stay table-free.
- `Loader.sweep` reads `loc` only for records of the segment; references to earlier records read it
  once each, at the declaration line (wave 7; before, when the record arrived). On mathlib `loc` is
  428 MB of random-access memory (*agentic-note*: design-architecture-document, 2.9); see "Wave 7" under "Loading cost".
- A promoted record stays in its block as well (12-13 % of the block records): a per-block "frontier"
  or an exact prescan would remove the copies; *agentic-note*: design-architecture-document, 3.3 decided against both.
