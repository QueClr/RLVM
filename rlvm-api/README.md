# rlvm-api — engine contracts and the bindings to rlvm-level / rlvm-nat

Packages `rlvm.api` (contracts, no implementation logic) and `rlvm.api.bind` (the adapter
implementations). Depends on `rlvm-store`; `rlvm.api.bind` additionally on `rlvm-level` and `rlvm-nat`.
Never depends on an engine, the driver, the loader or Truffle.

```
. ./env.sh && $MVN -o -q -pl rlvm-api test        # 31 tests, about 1 s
```

## What is implemented

| Type | Role |
|---|---|
| `Engine`, `Session`, `EngineFactory`, `StatsSink`, `RunSignals` | the facade between driver and engines (unchanged from the skeleton) |
| `EngineProvider` | the service through which an engine is found. Added (wave 4, both `default`): `optionKeys()` = the `-X` keys the engine understands, `factory(Map<String,String> options)` = the engine configured by them. `rlvm -X KEY=VALUE` with a declared key reaches the engine without a change in launcher, wiring or driver; an undeclared key or a refused value (`IllegalArgumentException`) is a usage error, status 64 |
| `KernelOps` | infer / whnf / def-eq on term ids for admission code (unchanged) |
| `Outcome`, `Reason`, `KernelException`, `ResourceException` | verdict vocabulary. `Reason.CANCELLED` (decline) added; `Reason.isRetryable()` |
| `Budget` | step / byte / depth limits. `warm()` has **no step limit**; `strict(steps, bytes)` is the only step-limited budget; `cold(bytes)`; `with*` |
| `EngineConfig` | engine options; `strict` documented as in RECONCILIATION section 2; `of(profile)`, `defaults()`, `with*` |
| `MachineErrors` | the machine's `E_*` codes as ints, with `reason(code)`, `outcome(code)`, `isKernelError / isLimit / isFatal / isRecoverableInTry`, `name(code)` |
| `LevelOps` | universe levels by id. Added: `mkSubst`, `substParams`, `substLevels`, `instantiate(level, subst)`, `instantiateList(list, subst)`, `kind / succOf / lhs / rhs / paramName / hasParam / isZero`, `isEquivOfficial`, `isGeqOfficial`, `undeclaredParam`, `toString`, `footprintBytes` |
| `NatOps` | Nat-literal operations and string-literal access by term id. Added: `checkLiteralSize`, `literal(long)`, `isSmall`, `smallValue`, `natToString`, `reset`, `footprintBytes` |
| `WorkerContext` | what a worker owns outside its engine; `reset()` and `footprintBytes()` implemented |
| `bind.LevelBinding` | `LevelOps`, `IntLists` **and** the store's `rlvm.store.LevelInfo` over one `rlvm.level.LevelStore` (base or worker overlay) |
| `bind.NatBinding` | `NatOps` over `rlvm.nat` (`Nat`, `NatStore`) through a literal bridge |
| `bind.LitAccess`, `bind.StoreLitAccess` | the literal bridge and its implementation over `Terms` / `Overlay` / `LitPool` |
| `bind.NameOracles` | `rlvm.level.NameOracle` from `NameTable.compare`; a late-bound variant |
| `bind.Bindings` | per-run factory: base level store (+ the run's list store and `LevelInfo`), empty number base, `freeze()`, `newWorkerContext(...)` |

Every method added to an interface after the first skeleton is a `default` method, so fakes written
against the skeleton still compile (`ContractTypesTest.addedInterfaceMethodsHaveDefaults`); the defaults
of methods that have no sensible generic answer throw `UnsupportedOperationException` naming the method.
No existing signature changed.

## How a run uses the bindings (batch mode)

```java
Bindings b = new Bindings(expectedLevels);          // base LevelStore + its IntListStore, empty NatStore base
Store store = new Store(inputBytes, b.baseLists(), b.baseLevelInfo());   // the run's ONE list store and level view
b.bindNames(store.names());                         // Lean's name order (NameTable.compare) for level normal forms
// loader thread: b.baseLevels() interns the export's levels and lists (plain constructors)
b.freeze();                                         // once, at end of file, before any worker context exists
WorkerContext ctx = b.newWorkerContext(id, store, signals, overlayMaxBytes);   // per worker / per lane
...
ctx.reset();                                        // with Session.reset(): overlay terms, levels, lists, numbers
```

`newWorkerContext` builds: a `LevelBinding` over `levelBase.newOverlay()` (it is `ctx.levels`, `ctx.lists`
and the overlay's `LevelInfo`), a worker literal pool `new LitPool(store.lits())` (`ctx.lits`), an
`Overlay(store, lists, levelInfo, lits, maxBytes)`, `Terms(store.terms(), overlay)` and a `NatBinding`
over `natBase.newOverlay()` and `StoreLitAccess(terms, overlay, lits)`.

`LevelBinding` as `LevelInfo`: `lhs`, `rhs` and `paramName` are total (`lhs` of a successor is its operand,
a missing operand is 0, `paramName` of a non-parameter is -1); this agrees with `LevelOps` wherever
`LevelOps` defines them.

## Invariants

- **Substitution ids** (RECONCILIATION B1). `mkSubst(paramNames, levels)`: 0 = identity or empty, -1 =
  different lengths (caller rejects with `LEVEL_ARITY_MISMATCH`), else a positive id; equal pairs give
  equal ids within a session. `instantiate(level, paramNames, levels)` and its list form throw
  `KernelException(LEVEL_ARITY_MISMATCH)` on different lengths. Regression test with two constants sharing
  a body under swapped parameter lists: `LevelBindingTest.substitutionIdsDistinguishSwappedParameterLists`.
- **One id space for levels and lists.** A `LevelBinding` is the `IntLists` of its level store.
  `unionSorted` returns an operand when it already is the union and memoises other results per session;
  `unionSorted` and `subsetOf` belong to the thread that owns the binding (the loader thread for the base).
- **The base list store is read-only after `freeze()`**: `intern` of an existing list returns its id, of a
  new list throws `IllegalStateException`. Worker overlays are private copies made after the freeze.
- **Numbers** (RECONCILIATION "Literal identity", with the store's `LitPool` design). A literal below
  2^64 is always a word literal (`Overlay.mkNatLit`). Every larger number, exported or computed, lives in
  the literal pools: `NatBinding` interns a big result in the worker's pool (`LitPool.addNat`, by
  content; a value the shared pool holds keeps the shared index) and builds the node with
  `Overlay.mkNatLitBig`. The worker's `rlvm.nat.NatStore` is only a cache of pool contents where the
  arithmetic runs: operands are imported on first use, the cache is emptied above
  `setCacheLimitBytes` (default 32 MiB) and at `reset()`; no term refers to a `NatStore` id.
  `natEquals` never answers "different" from ids alone (word/word by value, big/big by content when the
  references differ, word/big different by representation).
- **Official guards are rejections, own limits are declines.** `NatBinding` throws
  `KernelException(NAT_LITERAL_TOO_BIG)` exactly where `type_checker.cpp` (v4.36) throws: result of
  add / sub / mul / succ above 128 MiB, `Nat.pow` exponent above 2^32 - 1 (also for base 0 and 1), non-zero
  `Nat.shiftLeft` with a count above 2^32 - 1, the two size guards; `0 <<< n` is 0 for every n. Its own
  limit (`setWorkLimitBytes`, off by default) throws `ResourceException` and is evaluated after the
  official guards.
- **Lifetime.** `NatBinding.reset()` must accompany the reset of the worker's literal pool
  (`WorkerContext.reset()` does both, pool first): cache entries are keyed by pool references. A
  *truncation* of the pool (`LitPool.truncateTo`, `Overlay.resetTo`) is noticed by the binding itself
  (wave 4): it compares the pool's entry count (`LitAccess.workerNatCount()`) with the count after its
  own last addition before it consults or fills the cache and empties the cache on a difference - exact
  for callers that make literals only through `NatOps`; code that adds to a worker pool itself and
  truncates must call `NatOps.reset()`. With assertions on, every cache hit is compared with the pool.
- **Pipelined loading** (wave 4). `Bindings.bindLevelSnapshots(supplier)` replaces `freeze()`: worker
  views are overlays of the newest frozen snapshot of the loader's level chain
  (`rlvm.load.LevelChain.current().levels`); `WorkerContext.reset()` re-bases the level and list view on
  the newest snapshot (`LevelBinding.rebase`), and `WorkerContext.coveredUnits()` is the store's
  frontier as read *before* that snapshot was taken (`Integer.MAX_VALUE` in a batch run): the driver
  resets a context before it hands it a unit at or above that number.
- `MachineErrors`: REJECT exactly for kernel errors (`E_TYPE_EXPECTED` .. `E_LOOSE_BVAR`, which include
  `E_NATSIZE`); unknown codes map to `INTERNAL_ERROR` (decline) and are fatal.

## Deviations from the design documents, with reasons

- `Budget.warm()` no longer has the 50 M step limit of ARCHITECTURE 6.5 (RECONCILIATION section 3).
- `NatOps` documents "0 when the kernel does not reduce" for an oversized `Nat.pow` exponent; in v4.36
  the official kernel throws there, and so does `NatBinding` (the 0 result is kept in the contract for
  other profiles).
- `Bindings` creates the level store before the name table exists (the store needs the list store
  first), with a late-bound name order. If a `max` of two parameters were normalised before
  `bindNames`, the order used would be by id (`nameOrderUsedUnbound()` reports it); the loader only
  interns, so this does not happen.
- `LevelLimitException` (nesting above 2^24 - 1) and other exceptions of `rlvm-level` are not translated:
  an engine's generic handler turns them into a decline.

## Known gaps

- Integration-tested against the real `rlvm-store` (`StoreIntegrationTest`): `Bindings.newWorkerContext`,
  `StoreLitAccess`, `WorkerContext.reset / footprintBytes`, `lpSet` of a SORT node and
  `TermOps.instantiateLevelParams` through `LevelBinding`. End to end (through `rlvm-main`): the 230
  published tests. `NatBinding`'s arithmetic paths are tested against a BigInteger model of `reduce_nat`
  on an array-backed fake bridge (`FakeLits`); on the real store only a handful of operations are.
- `NameOracles.of(NameTable)` (Lean's name order through the real name table) is exercised only by the
  end-to-end runs, not by a unit test of this module.
- `Bindings.snapshot()` still throws `UnsupportedOperationException` (kept for callers of the first
  skeleton): the snapshots of a pipelined load are made by the loader's level chain, and the factory is
  told where they are with `bindLevelSnapshots`. Tested here against a chain built by hand and against
  the real store; against `rlvm.load.LevelChain` in `rlvm-main` (`MainWiringLoadTest`).
- Import of a pool literal into the number cache goes through `BigInteger` (no raw-limb import in
  `rlvm-nat` yet; requested).
- All computed big numbers of a session stay in the worker's literal pool until the session is reset
  (the pool has no partial release): `perf/gf3-matrix-product` puts about 2.6 GB of intermediates into
  one declaration. It passes with the launcher's 13 GB heap and fails with 4 GB. See
  `work/status/requests.log` for the options.
- `LevelBinding.footprintBytes()` is an estimate (16 bytes per level and list created).
- The base binding's `unionSorted` memo is not thread-safe: only the thread that owns a binding may call
  `unionSorted` / `subsetOf` on it (workers use their own).
