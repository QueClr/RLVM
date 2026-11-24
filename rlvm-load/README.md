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

## Pipelined loading (work package L2, ARCHITECTURE.md 3.5)

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

**Shared artefacts in a pipelined run** (publication rule B2 of RECONCILIATION.md, "only base level
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

## Non-canonical and hostile input (ARCHITECTURE.md 3.4)

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
| unsafe / partial record | dropped, name remembered (official: a user fails with "unknown constant") | `SplitTest.unsafeAndPartialRecordsAreDropped...` |
| dropped record named `Quot`, `Quot.mk`, `Quot.lift`, `Quot.ind` | reject (official: "Could not find quotient constant in final kernel env") | `droppedRecordsWithAQuotientNameAreRejected` |
| inductive block, all records unsafe | dropped as a whole (official: accepted) | `unsafeInductiveBlocks` |
| unsafe type records with a safe constructor / recursor record | unsafe records dropped, the safe ones loaded as a unit without types; `rlvm-admit` rejects it when no safe type of the file generates the name (official: "No such constructor / recursor") and declines otherwise | same |
| safe and unsafe type records in one block | decline (not probed) | same |
| safe types, unsafe constructor / recursor record | loaded, entry flagged `F_UNSAFE` (official: accepted; `rlvm-admit` declines such blocks in v1) | same |
| `{"inductive":{"types":[],"ctors":[],"recs":[]}}` | declares nothing, no unit (official: accepted) | `anEmptyInductiveLineDeclaresNothing` |
| `mdata` | wrapped expression; inside an inductive or quot record: decline (deviation 7), per unit with `publishOnDecline` | `SplitTest.mdataIsDropped...`, `CatalogueTest.metadataInsideInductiveAndQuotRecordsIsDeclined`, `PipelinedLoadTest.aDeclineIsTheStatusOfTheFirstUnitThatWasNotLoaded` |
| segment longer than `LoadOptions.maxSegmentRecords` (default 2^26 = 1 GiB of buffer) | decline | `aSegmentBeyondTheLimitIsDeclined` |
| more than 2^31 - 2^29 expression records, any `StoreLimitException` (2^28 - 1 shared nodes, 2 GiB pools, ...) | decline | only the exception mapping is tested (`BindingsTest.storeLimitsAreDeclines`); no input of that size was built |
| level deeper than 2^24 - 1 (`LevelLimitException`) | decline (see deviations) | not tested (needs 16.7 M records) |

## Deviations from the design documents

1. **Promotion is a step of its own, between the claim and the sweep** (ARCHITECTURE.md 3.3 does it
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
7. `mdata` is dropped and `nondep` ignored, as ARCHITECTURE.md 2.1 decides, **except that an inductive
   or quot record whose roots carry metadata is declined at load** (integrator, 2026-11-24). The
   official checker compares those records with the kernel's own declarations by `Expr.eqv`, which
   compares metadata, and tests their spines syntactically; four hand-written probes
   (`work/integration/probe/mdata-*.ndjson`) were accepted by RLVM and rejected by leanchecker 4.36
   before this rule. The loader keeps one bit per expression ordinal ("wrapped by or contains
   metadata"), allocated at the first `mdata` record, so files without metadata (every lean4export
   file) pay one null test per record. A record wrapped by an `mdata` record counts as carrying
   metadata even when it is referenced directly (over-approximation; it can only decline). Metadata in
   definitions, theorems, axioms and opaques is accepted: it is invisible to type checking.
   `nondep` and binder names / info are not compared by the official checker either (test kit).
   `LoadReport.exprMData` counts the records. Test: `CatalogueTest.metadataInsideInductiveAndQuotRecordsIsDeclined`.
   The decline is for the rest of the file from that record on: with `publishOnDecline` the units
   before it are still checked (see "A decline is per unit"), so a file with an ill-typed earlier
   declaration is rejected, not declined. Keeping metadata exactly (MDATA nodes) would need a node
   kind in the store and in every engine; not done.
8. **The header decides nothing** (ARCHITECTURE.md 3.4 and 4.9 decline another format major version).
   The official reader skips line 1 unread, so a file is what its records are; the decline is kept as
   an option (`LoadOptions.strictFormatVersion`) for a caller that wants it.
9. **Level ids of a pipelined load are not those of a batch load** (see "What differs from a batch
   load"). ARCHITECTURE.md 3.5 asks for a snapshot of `rlvm-level`; the chain builds snapshots from
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
  table of ARCHITECTURE.md 4.2 in `rlvm-store` (`NameTable.claim` as a compare-and-set). The loader
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
or quot record; an inductive record that mixes safe and unsafe type records; a projection index above
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

Reading: (1) against ARCHITECTURE.md 3.6 (12 instructions per input byte at M3, 8 at M5, "minus process
start") the steady state is at 15.7; a cold JVM process is at 31-38 on these small inputs, and that gap
is the platform question of ARCHITECTURE.md 7.1, not loader code. (2) "reader alone" is a lower bound
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
   byte against the budget of ARCHITECTURE.md 3.6 (12 at M3, 8 at M5): not met. The whole load is a
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
   (`withPublishEvery`) trades the same way. An in-place "rebase" of an empty worker overlay onto a
   newer snapshot (copy only the new ids) would remove the copies; that is an `rlvm-level` feature.
5. What pipelining buys is the load's share of the wall clock: with the reference engine on mathlib
   (139 s wall, of which the load is 12-14 s) about 10 %; with an engine near the leaders' 18 s it is
   the difference between "load, then check" (about 14 s + check) and max(load, check).

## Observations for the next work packages

- The reader needs an id translation table for names with the real store (`LoadReport.readerIdTables`):
  the store pre-interns the 34 special names, so the name ids the loader returns are not consecutive.
  One table load per name reference; not measured separately. Expression ordinals stay table-free.
- `Loader.sweep` reads `loc` only for records of the segment; references to earlier records read it
  once each, at the declaration line (wave 7; before, when the record arrived). On mathlib `loc` is
  428 MB of random-access memory (ARCHITECTURE.md 2.9); see "Wave 7" under "Loading cost".
- A promoted record stays in its block as well (12-13 % of the block records): a per-block "frontier"
  or an exact prescan would remove the copies; ARCHITECTURE.md 3.3 decided against both.
