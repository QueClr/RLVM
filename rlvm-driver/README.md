# rlvm-driver

Package `rlvm.driver`, work package D2 of *agentic-note*: design-architecture-document, 10.2: runs one check of one export
from a loaded (or to be loaded) store to a verdict. Threads, scheduler, sessions' lifecycle, re-check
protocol, verdict, memory account, statistics. It never calls `System.exit`, never sees an engine class
(engines arrive as `EngineFactory`), and never sees the reader (`rlvm-export`), the level or the number
module: those come through `Wiring`, implemented by `rlvm-main`.

Authority: *agentic-note*: owner-notes > *agentic-note*: design-reconciliation-document > *agentic-note*: design-architecture-document, sections 3.5, 4.1, 6, 8. Where this
module follows *agentic-note*: design-reconciliation-document instead of *agentic-note*: design-architecture-document, or deviates from both, the table at the end
says so.

## What is implemented

| Area | State |
|---|---|
| Batch load through `Wiring` on the calling thread, or a store the caller loaded | done |
| **Pipelined load** (`RunConfig.loadMode = PIPELINED`, `-X load=pipelined`): the calling thread loads, the environment thread and the workers check what is published | done; unit-tested with a fake loader, integration-tested with the real loader (`rlvm.load.LevelChain`, `LoadListener`); **not the default** (see "Pipelined loading") |
| One worker (`-j 1`), batch: strict file order, environment pass driven lazily by the worker | done |
| N workers, and every pipelined run: environment thread ahead of the workers, contiguous runs per worker, stealing, give-back of slow claims | done |
| **Automatic worker count** (`--threads auto`, `RunConfig.workers = 0`): `Run.autoWorkers(processors) = max(1, min(processors - 2, 6))` | done; gate measurable by one command (see "Worker count") |
| Re-check protocol (8.2, 6.5, 6.6), inline on the worker thread, fresh sessions, cold budget | done |
| **Memory retries one at a time** (the heavy lane of RECONCILIATION section 3, as a gate instead of a thread) | done |
| Verdict rule of RECONCILIATION section 2, fail-closed chain of 8.1 | done |
| Cancellation on first failure; **memory account by allocated capacity, one victim at the watermark** | done |
| Sessions reset by footprint, after any outcome but ACCEPT, when the rank steps back, **before a unit beyond the lane's snapshot, and when a lane's unused capacity grew (give-back)** | done |
| **Session cap per engine** (`RunConfig.sessionBytes = 0`, the default: 4 MiB for the machine, 16 MiB otherwise; a configured value wins) | done (wave 7; see "Session cap") |
| **A slow unit returns the rest of its claim while it is still running** (at a poll of its session; before: after it had ended) | done (wave 7; see "Scheduler" and "Slow units") |
| **`-X passes=N`**: check the loaded export N times in one process, per-pass wall clock / CPU time / verdict (owner note 12: warm wall clock) | done (wave 7; see "Passes") |
| Statistics (`RunStats`, JSON), explanation line on stderr | done |
| **Slice mode of the scheduler** (`RunConfig.sliceUnits = 256`, `-X slice=N`): a worker without work takes the next slice of the ready units for itself; the workers move through the file together | done (wave 8; see "Scheduler") |
| **Parking rule for pipelined loading** (`RunHost.unitNeedsCompleteFile`): the environment frontier waits below a unit that may depend on the rest of the file; the environment pass is kept over the end of the load | done (wave 8; see "Pipelined loading") |
| **Time account** in the statistics: per worker on-CPU / run queue / parked by cause / heavy gate / end of load / resets, the environment thread's account, time stamps, time series, slowest units, per-unit trace (`-X unit-trace`), statistics per later pass | done (wave 8; see "Where the wall clock goes") |
| **`-X shard=K/N`** (measurement only: one shard of the units is checked, the run declines) | done (wave 8; the hardware ceiling in "Measured") |
| **A large file is published in larger pieces** (`RunConfig.publishScaleMax` = 4, `-X publish-scale=N`: the pipelined hand-off interval times the input's size in GiB, at most 4) | done (wave 8; see "Pipelined loading"; mathlib pipelined with 8 workers -8 %) |
| **`-X passes-keep=engines`**: the later passes of `passes=N` use the engine instances of pass 1 (`KeptEngines`) | done (wave 8; see "Passes") |
| Differential mode (`RunConfig.differential`); **`RunConfig.referenceFallback = false` (`no-fallback`)**: the engine under test alone | done |
| **A declaration is not checked twice** (`RunConfig.skipRepeats`, `-X repeats=skip\|check`): a unit that the loader marked as the record of an earlier unit under another name (`DeclTable.repeatOf`) goes through its admission, and the primary engine is not run on it if the earlier unit is ACCEPTED on the board at that moment; otherwise it is checked as before, and only if that check does not accept it does the worker wait for the earlier unit's status (ACCEPTED: the unit is admitted as it would have been a moment later, so no status depends on the schedule). Off when the primary engine is the reference engine (so under the strict policy). Statistics `driver.repeatsSkipped`, `driver.repeatsAwaited` | done (*agentic-note*: rep-OPT-I) |
| Namespace ordering (`OrderPolicy.Kind.NAMESPACE_WINDOW`, `RunConfig.order`) | **deferred**: `OrderPolicy.of`, `Scheduler` constructor and `Run.execute()` throw `UnsupportedOperationException` |
| Heavy-lane threads | **not used**: re-checks run on the worker's own deep-stack thread; `Scheduler.defer` / `claimHeavy` work as a plain queue that nothing in `Run` feeds |
| `Run(RunConfig, EngineFactory, EngineFactory)` (the skeleton's constructor) | throws `UnsupportedOperationException`: a run needs a `Wiring` |
| Scan helpers (`RunConfig.scanThreads`), `RunConfig.truffleTier`, `proofBlocks` | passed on (`proofBlocks` to `LoadOptions`, `truffleTier` to `EngineConfig`); `scanThreads` is the wiring's business (`-X scan-threads` reaches the reader through `rlvm-main`) |

## Public API

```java
interface Wiring   { Store newStore(long inputBytes); void load(Store, LoadOptions) throws Exception;   // BATCH or PIPELINED options
                     LoadReport loadReport(); WorkerContext newWorkerContext(int workerId, Store, RunSignals, long overlayMaxBytes); }
final class Run    { Run(RunConfig, Wiring, EngineFactory primary, EngineFactory reference);          // load + check
                     Run(RunConfig, Wiring, Store loaded, EngineFactory primary, EngineFactory reference);  // check only
                     Run(RunConfig, RunHost, EngineFactory primary, EngineFactory reference);         // fakes
                     void inputFailure(Reason, String);  void setMinUnitsPerWorker(int);  void setDiagnostics(PrintStream);
                     int execute();                       // Outcome code; rethrows the first Error of any thread
                     VerdictBoard verdicts(); RunStats stats(); MemoryAccount account(); Worker[] workers(); int workerCount();
                     boolean pipelined();
                     static int resolveWorkers(int requested); static int autoWorkers(int processors); }
final class RunConfig     { arena defaults; see the fields. Settings by name: static keys(), knows(key); set(key, value), setAll("k=v,k=v") }
final class VerdictBoard  { accept(u), skip(u), reject(u, reason, msg), decline(u, reason, msg), inputFailure([unitsLoaded,] reason, msg),
                            internalFailure(msg), stopped(msg), complete(units), firstFailure(), status(u), decidingUnit(),
                            exitCode(), explanation(), warmAnomalies(), machineIncomplete(), disagreements(), events() }
final class Scheduler     { Scheduler(Frontier, OrderPolicy, workers, chunk, VerdictBoard); Scheduler(Frontier, OrderPolicy, workers, chunk, sliceUnits, VerdictBoard);
                            Scheduler(Store, OrderPolicy, RunConfig, VerdictBoard);
                            int claim(int worker, int[] out); int claim(int[] out) /* worker 0 */; int tryClaim(int worker, int[] out);
                            void giveBack(int[], int from, int n); void retire(int worker); void unitsReady(); void unitsReady(int units);
                            void close(); void wake(); void defer(int unit); int claimHeavy(); void closeHeavy(); counters }
interface   OrderPolicy   { void arrange(int[] units, int from, int n); FILE_ORDER; static of(Kind, Store); }
final class Worker        { Worker(int id, Run, Engine primary, Scheduler, VerdictBoard); run(); unitsDone(); sessionResets(); unitsCancelled();
                            rechecks(); giveBacks(); }
final class MemoryAccount { MemoryAccount(limitBytes, slots); setShared(bytes); report(slot, bytes); setExempt(slot); setLastResort(slot);
                            exhausted(); exhausted(slot); exhaustedAlone(slot); exhaustedLastResort(slot);
                            accountedBytes(); peakBytes(); peakSessionBytes(); victimCount(); }
final class RunStats      implements rlvm.api.StatsSink { phaseStart/phaseEnd, threadCpu/threadTimes (CPU and run-queue wait), unit, put, table, toJson, writeJson }
interface   RunHost       { the seam below the driver: unit table, frontiers, Lane (context + admission; coveredUnits(), slackBytes()),
                            EnvPass, retryEnvUnit(unit), unitNeedsCompleteFile(unit) (parking rule), unitWeight(unit) (statistics) }
final class StoreHost     implements RunHost over Store + rlvm.admit (Admission, EnvBuilder) + Wiring
```

How `rlvm-main` uses it: `new Run(config, wiring, primary, reference).execute()`. Pass **the same
`EngineFactory` object twice** when the reference kernel is the primary engine (`-X engine=reference`): the
driver then knows that "strict primary" and "reference" are the same thing and confirms a rejection with one
fresh run instead of two.

`Wiring.newWorkerContext` is called on the thread that uses the context; in a pipelined run while `load`
runs on the loading thread, never before the loader's first publication. `workerId` is unique among the
sessions of one engine instance, not among all contexts: worker `w` uses `w` for its warm lane (primary
engine), its strict lane (strict primary engine) and its reference lane (reference engine); the environment
lane (reference engine) uses the worker count. Engines are created with `EngineConfig.workers` = workers
(primary, strict) and workers + 1 (reference).

### Settings by name

`RunConfig.sessionBytes` is not in this list: `-X session-mib=N` is a key of `rlvm-main` (it sets the field; 0 /
absent = the per-engine default below).

`RunConfig.set(key, value)` / `setAll("key=value,key=value")` / `knows(key)` cover the driver's own knobs, so
that the launcher needs no code per knob (`rlvm-main` is asked to pass unknown `-X` keys through). Until it
does, `Run` reads the same list from the environment variable `RLVM_DRIVER` (only when it has a `Wiring`;
a faulty value is reported on stderr and ignored). None of the numeric settings can change a verdict.

| Key | Field | Default |
|---|---|--:|
| `unit-mib=N` | `unitBytes`: warm byte budget of a unit | 0 = the whole headroom |
| `keep-mib=N` | `keepBytes`: give-back threshold of a lane | 256 |
| `gc-after-mib=N` | `gcAfterBytes`: ask for a collection when the heap holds N MiB of unaccounted bytes | 0 = never |
| `chunk=N` | `chunkUnits` | 32 |
| `slice=N` | `sliceUnits`: units a worker without work takes as its run (0 = one run per worker and batch, the scheme until wave 7) | 256 |
| `env-batch=N` | `envBatchUnits` | 4096 |
| `spill-ms=N` | `spillNanos`: a unit running longer returns the rest of its claim | 20 |
| `publish-units=N`, `publish-mib=N` | pipelined hand-off interval | 4096 units, 32 MiB |
| `publish-scale=N` | `publishScaleMax`: both intervals times the input's size in GiB, at most N (1 = off): a large file is published in larger pieces | 4 |
| `pipeline-min-mib=N` | `pipelineMinBytes`: smaller inputs are loaded in batch mode | 8 |
| `no-fallback` | `referenceFallback = false` | off |
| `passes=N` | `passes`: check the export N times in one process (see "Passes") | 1 |
| `passes-keep=engines\|none` | `passesKeepEngines`: the later passes use the engine instances of pass 1 | none |
| `shard=K/N` | `shardIndex`, `shardCount`: measurement only, check shard K of N (blocks of 1,024 units, block number mod N); the run's verdict is DECLINE | off |
| `unit-trace` | `unitTrace`: with a statistics file, one line per checked unit in `STATSFILE.units.tsv` (unit, worker, start, nanoseconds, steps, outcome, size) | off |
| `repeats=skip\|check` | `skipRepeats`: a definition, theorem or opaque whose record repeats an ACCEPTED earlier unit word for word (`RunHost.unitRepeatOf`) is admitted and not checked again; never with the reference engine as primary engine (*agentic-note*: rep-OPT-I) | skip |

## How a run goes

```
execute
  batch (default; also -X load=pipelined for an input below pipelineMinBytes, and stop-after runs):
    load: Wiring.newStore, Wiring.load (LoadOptions BATCH) on the calling thread
        MalformedExportException            -> REJECT at once, nothing is checked
        UnsupportedExportException, IOException, any other Exception -> never ACCEPT; published units are still checked
    W = min(configured workers, units / 128), at least 1        (verdict-neutral; small files get one worker)
    W = 1: one worker thread; before unit u it advances the environment pass to u + 1   -> strict file order
    W > 1: an environment thread advances the pass ahead of the workers: runs of plain units in batches of
           envBatchUnits, every inductive block / quot record alone after announcing everything below it
  pipelined:
    Wiring.newStore; Wiring.load(store, LoadOptions.pipelined(bytes) with the hand-off interval and the driver's
        LoadListener) on the calling thread
    first publication that is not the end of the file: start the environment thread and W = configured workers
        (a file that is published in one piece is a batch run from here on)
    environment thread: follows the loaded frontier; parks when it has caught up, is woken by the next publication
    end of the load (complete or failed): frontier final, outcomes obtained from now on are final
  workers claim units from the Scheduler and admit them (Worker, below); join
  VerdictBoard.complete(units) only if the load was complete and the store's unit count equals the
      loader's count of declaration records (LoadReport.declRecords) or the caller's (RunConfig.expectedUnits)
  statistics, one explanation line on stderr unless ACCEPT; return VerdictBoard.exitCode()
```

Worker threads and the environment thread get `max(workerStackBytes, deepStackBytes)` (1 GiB reservation):
the reference kernel runs on them (as primary engine, for re-checks, for the environment pass). A worker
creates its lane and session at its first claim, not at thread start.

### Per unit (`Worker`)

```
u > firstFailure            -> skipped (cancelled)
inductive / quot unit       -> decided by the environment pass (its listener told the board)
dropped record (size 0)     -> SKIPPED, counts as accepted
u >= lane's covered units   -> reset the warm session first (pipelined: the lane's level / list / literal snapshot)
rank(u) <= last rank        -> reset the warm session first (I5)
o = admit(u) in the warm session; budget: no step limit, unitBytes, no depth limit
ACCEPT                      -> final. Reset the session if Session.footprintBytes() > the session cap (sessionBytes,
                               or the primary engine's default: machine 4 MiB, else 16 MiB), or if the lane's
                               unused capacity grew by more than keepBytes and the last units did not need it (give-back)
anything else               -> reset the session; if u is still <= firstFailure: re-check now, on this thread
at every poll of the unit's session (RunSignals.memoryExhausted, every few thousand steps): if the unit has been
                               running for more than spillNanos, give the unstarted rest of the claim back
```

### Passes (`-X passes=N`, owner note 12)

The target is warm wall clock: what a pass over an export costs once the process is warm (JIT, grown tables,
touched pages). `passes=N` makes one process check the export N times and report each pass:

```
rlvm: pass 1 of 3: accept, wall 1.244 s (check 0.790 s), cpu 0.500 s
rlvm: pass 2 of 3: accept, wall 0.262 s (check 0.238 s), cpu 0.140 s
rlvm: pass 3 of 3: accept, wall 0.221 s (check 0.220 s), cpu 0.100 s
```

(stderr; `driver.passes` and `driver.pass.K.{verdict,wallNanos,checkWallNanos,cpuNanos}` in the statistics;
`Run.passResults()`; the example is init-prelude, machine, 4 workers, JVM launcher.) Pass 1 is the run
itself, load included (pipelined: overlapping), and **its verdict is the verdict and the exit status of the
run**. A later pass is a check-only run of its own over the same loaded store: new engines, new sessions, new
scheduler and verdict board, the configured worker count; every def / thm / axiom / opaque unit is checked
again, inductive blocks and quot records keep the outcome the environment pass of pass 1 recorded. Later
passes are made only when pass 1 accepted (a failing run's time is that of a cancellation); a later pass that
does not accept is reported in its line and changes nothing. CPU time is the process's (`/proc/self/stat`,
10 ms steps). With `passes=1`, the default, none of this code runs.

At 15:27Z the launcher's working tree (`rlvm-main`, with its new owner) had a `-X passes=N` of its own
(`passes-mode=reload|recheck`, lines `pass K/N mode=...`). A key of the wiring wins over a driver key of the
same name, so with that launcher `-X passes` is the wiring's and the driver's stays at 1; the driver's is then
reached as `RunConfig.passes` by callers of `Run` and as `RLVM_DRIVER=passes=N`.

**`-X passes-keep=engines`** (wave 8; asked for by the Truffle tier's and the code store's owners,
requests.log 16:31Z / 16:57Z): every pass works with the engine instances of pass 1 instead of new ones
(`KeptEngines`, a caching `EngineFactory` around the caller's; the idea and first implementation are the
agent "warm"'s). What an engine keeps per instance - the machine's code store, compiled blocks, published
facts - is then warm in the later passes; sessions, scheduler and verdict board are new in every pass. The
engines are closed after the last pass, and their statistics (`engine.*`, written by `Engine.dumpStats` once
per engine) appear in the statistics of the last pass, summed over all passes. Default `none`: new engine
instances per pass, as before.

Every later pass writes its complete statistics, time account included, to `STATSFILE.pass2`, `.pass3`, ...
(wave 8).

What it does not do: a later pass does not load, so it has no pipelined overlap and no environment pass (the
workers start with the whole export ready); for warm passes that load, `rlvm-main` has `-X passes=N` in its
default mode `reload` (its key shadows this one; `-X passes-mode=recheck` hands the count to the driver).

### Session cap (`sessionBytes`)

`RunConfig.sessionBytes = 0` (the default) = automatic: the cap of a session is the default of the engine it
belongs to, `RunConfig.defaultSessionBytes(Engine.name())`: **4 MiB for the machine** (requested by the machine
integrator and confirmed by the sweep in "Measured": with its warm sessions 16 MiB is slower than no warm
session at all), 16 MiB for every other engine (RECONCILIATION section 3). The workers' warm sessions use the
primary engine's cap (`Run.sessionBytes()`, statistic `driver.sessionBytes`), the environment lane the
reference kernel's. A positive value (`-X session-mib=N`) applies to every session.

Who provides the default: `rlvm.api.Engine` has no method for it, so the table is in the driver, keyed by the
engine's name. Since 13:34Z `rlvm.api.EngineProvider.runDefaults(options)` exists (the wiring applies a
provider's preferred `-X` settings, e.g. `session-mib=4`, unless the command line names the key): an engine
that states its cap there overrides this table, because the wiring then sets `sessionBytes`. The machine also
has a cap of its own inside the session (`-X machine.session-kib`, off by default); the three compose: the
smallest one that is in force resets first.

### Re-check protocol (ARCHITECTURE 8.2 / 6.5 / 6.6)

Every stage runs in a freshly reset session with `Budget.cold(memoryLimitBytes)`; after every stage the unit
is abandoned without a record if it now lies above the first failure (the stage may have been cancelled).

| Warm outcome | Stages | Final status |
|---|---|---|
| REJECT | 1. strict primary: ACCEPT | accepted, WARM-ANOMALY |
| | 2. reference: REJECT | REJECTED (confirmed; the reference kernel's reason and message) |
| | reference: ACCEPT | accepted, MACHINE-INCOMPLETE |
| | reference: DECLINE or exception | `Policy.unconfirmedReject`: DECLINED(UNCONFIRMED_REJECT) (default) or REJECTED |
| DECLINE | 1. primary, cold budget: ACCEPT | accepted |
| | primary cold: REJECT | as "REJECT" above |
| | 2. reference: ACCEPT / REJECT / DECLINE | accepted / REJECTED / DECLINED (its reason) |
| RuntimeException | session and lane discarded; reference: ACCEPT / REJECT / DECLINE | accepted / REJECTED / DECLINED |
| | reference throws too | DECLINED(INTERNAL_ERROR) |
| invalid outcome code | treated as DECLINE(INTERNAL_ERROR) at every stage | |

primary == reference (same factory object): the stages "strict primary" and "primary, cold budget" do not
exist; one fresh cold run of the reference kernel decides, and a warm REJECT that the fresh run accepts is a
WARM-ANOMALY. `recheckRejections = false` makes a REJECT of the primary engine final (measurement only).
An ACCEPT of the primary engine is never re-checked. Structural rejections and rejections of the environment
pass need no confirmation; a structural rejection simply repeats in the stages at no cost.

**Memory retries.** A stage that ends with `BUDGET_MEMORY` is repeated while the worker holds the heavy gate
(below); a warm decline for memory takes the gate before its first cold stage. The outcome table is
unchanged; only "declined for memory" now means "does not fit into the limit by itself".

**`referenceFallback = false`** (`no-fallback`; the gate of an engine under test, requested by the machine
workflow): the reference kernel never turns a non-ACCEPT of the primary engine into an ACCEPT. A unit the
primary engine declines (after its own cold attempt) or throws on is DECLINED with its reason, without
asking the reference kernel; a rejection that the reference kernel accepts is DECLINED(ENGINE_DISAGREEMENT)
with the MACHINE-INCOMPLETE event. Rejections are still confirmed (REJECT only when confirmed) and the
environment pass stays the reference kernel's. The run's exit status is then ACCEPT iff the primary engine
accepted every def / thm / axiom / opaque unit by itself.

### Verdict (`VerdictBoard`)

The verdict is the status of the first unit in file order that is not ACCEPTED or SKIPPED: REJECTED gives
REJECT, DECLINED or "no status" gives DECLINE; later units never change it (RECONCILIATION section 2,
C3.13). Malformed input gives REJECT regardless of units. ACCEPT needs all of: `complete(n)` was called, no
input failure, not stopped (`stop-after`), no internal failure, every unit below `n` ACCEPTED or SKIPPED, no
status at or above `n`. A unit status is written once (compare-and-set from "none"); a second report for a
unit is an internal inconsistency and turns the verdict into DECLINE. `firstFailure()` is the smallest unit
with a recorded REJECTED / DECLINED status, -1 once the whole run is cancelled (malformed input, internal
failure). A rejection is only ever recorded when confirmed.

### Scheduler (RECONCILIATION section 3)

Units `[0, ready)` may be handed out (the environment thread announces them); `[0, split)` have been handed
out as runs. **Slice mode (the default since wave 8, `sliceUnits` = 256):** a worker without a run takes the
next slice of the ready units for itself, `(ready - split) / W` units, at least a chunk and at most
`sliceUnits`; nothing is put into another worker's queue. A worker takes chunks of `chunkUnits` (32) from the
front of its run. A worker without a run when nothing is left to slice steals the back half of the largest
remaining run (the whole run if it is no longer than a chunk). A unit that runs longer than `spillNanos`
(20 ms) makes its worker give back the unstarted rest of its claim (see "Slow units"); given-back units are
served before anything else, except that a worker never takes them ahead of a lower unit waiting in its own
run. Units above the first failure are never handed out. One lock, taken once per claim; idle workers wait on
its monitor.

Why slices: the workers then move through the file together, lowest units first, and a unit starts when the
common front reaches it, whoever is free. Until wave 7 (`slice=0` still does it) every batch of ready units
was cut into one contiguous run per worker (`min(W, |batch| / chunk)` runs, appended to the workers' queues).
A worker that fell behind kept receiving its share of every later batch, and nobody else touched that backlog
while anything was left to split; a heavy unit in it started late, and its end was the end of the run. Std has
such a unit (23249: the largest proof of the file, 0.7 s of a 1.5 s run with 8 workers): a later pass
(`passes=N`: the whole file is one batch, which was cut into 8 contiguous eighths) took 2.15 s where slices
take 1.65 s, and pass 1 had three samples of nineteen at 2.2 s to 2.4 s. Numbers and their limits in
"Measured", wave 8. What slices give up is nothing that was
measured: a session is reset every 75 units or so anyway (the machine's 4 MiB cap), so contiguity beyond a
slice buys no warmth.

**Slow units.** A claim is up to `chunkUnits` (32) units that nobody else can take. Until wave 7 a worker
returned the rest of its claim only after the slow unit had ended, so a heavy unit kept up to 31 units waiting
behind it, the next heavy one among them, while the other workers had nothing to do (reachability: units 4137,
4139, 4145 and 4158 carry 93 % of the steps and lie inside one or two claims). Now the rest goes back while
the unit is still running: `Worker.sample()`, which every session calls through `RunSignals.memoryExhausted()`
every few thousand steps (the machine every 4,096 ticks, the reference kernel every 1,024 steps), looks at the
clock when the claim still has a rest and gives it back once the unit has run for `spillNanos`; a worker also
gives the rest back before it waits for the heavy gate, and, as before, when the unit has ended or is deferred
to the end of the load. Everything happens on the worker's own thread through the existing `Scheduler.giveBack`:
the scheduler and its lock are unchanged, and the exhaustive interleaving test got the new move (give back
while the unit is unfinished). The time only decides who checks a unit. Statistic:
`driver.scheduler.restsReleasedInUnit`. A unit that does not poll (none known) keeps its claim until it ends.
Not done: stealing from the tail of a busy worker's claim by an idle worker (it would also balance the last
claims of a batch of fast units, at the price of a shared claim cursor per unit and more sessions that step
back in rank and are reset; the heavy case, which is what costs wall clock, is covered without it).

**Progress.** Warm sessions have no step limit, so a unit behind an ill-typed unit may never finish; it
ends when the failure of the ill-typed unit is recorded and its session polls `firstFailure`. This needs
the lowest undecided unit to be reached whatever the other workers are stuck in: (a) a worker's units
ascend while it has a run of its own (that is why given-back units do not jump ahead of it), so the
lowest undecided unit of a queue is next for its owner or is stolen by repeated halving; (b) the
environment thread announces everything below an inductive block or quot record before it starts that
record; (c) the environment pass itself is cancelled (`LaneSignals.guard`; the reference kernel also polls
`firstFailure` in `KernelOps` mode since its jar of 06:37Z); (d) the heavy gate goes to the lowest waiting
unit and a holder gives way to a lower one; (e) a worker never waits for the loader while it holds the
gate or a claim's unstarted rest. With one worker in batch mode the order is the file order and the
question does not arise.

## Memory

### Account (`MemoryAccount`, `LaneSignals`)

Shared store bytes + one slot per checking thread. A thread's slot is the sum over its sessions of
`Session.footprintBytes()` (bytes in use) **plus `Lane.slackBytes()`**: what the lane's context has
allocated beyond that (for `StoreHost`: `Overlay.allocatedBytes() - usedBytes()`, i.e. the node array's
unused capacity and the hash index). The reference session forgets everything after each unit
(`Overlay.resetTo`), so its footprint between units is near zero while the arrays a heavy unit made grow
stay allocated: before this change the account saw 3.9 GB at the peak of mathlib where 4.9 GB to 5.4 GB
were held (its peak now, and what a full collection finds reachable). A thread reports at unit boundaries and at every `RunSignals.memoryExhausted()` poll of its
sessions, when its total moved by 1 MiB.

Above `memoryLimitBytes` (12 GiB) not every session gives up:

- the thread that holds the most is the **victim**: `memoryExhausted()` is true for its sessions only. Its
  unit is declined warm, the session is reset, and the unit is checked again holding the gate;
- above the limit plus a margin (1/8 of the limit, at most 1 GiB), or when the largest holder has nothing to
  give back, every thread gives up (the former behaviour, as a backstop);
- the holder of the heavy gate is exempt: it gives up only when the shared store plus its own sessions exceed
  the limit;
- the environment thread is the last resort (nothing checks its units again): it gives up only above the
  margin, or when it does not fit by itself.

### Heavy gate (`HeavyGate`): memory retries one at a time

RECONCILIATION section 3: "the heavy lane is for memory retries". Here it is mutual exclusion on the
worker's own deep-stack thread instead of a thread: one memory retry at a time, the holder exempt from the
watermark, everybody else giving way. A unit is therefore declined for memory only if it does not fit into
the limit **by itself**, whatever the other workers were doing: the exception to verdict invariance that the
former README listed ("declines caused by the global memory watermark") is gone, and the warm byte budget
could become the whole headroom (below). The gate goes to the lowest waiting unit, and a holder gives way to
a lower unit that starts waiting (its stage ends with a decline that is not recorded; it enters again), so
the lowest undecided unit never waits for a higher one. Waiters leave when their unit comes to lie above the
first failure. Counters: `driver.heavy.entries / waits / yields`, per worker `gatedStages`.

### Warm byte budget (`unitBytes`)

0 = automatic = `max(256 MiB, memoryLimitBytes - shared)`: the whole headroom, whatever the worker count. The
former share `headroom / (2 W)` (654 MiB on mathlib with 8 workers, 789 MiB on con-leche) cut three units
short that fit easily and made them run twice; the account's victim rule and the gate are what protects the
run now. See "Measured" for the sweep.

### Give-back after a heavy declaration (`keepBytes`)

A lane whose unused capacity grew by more than `keepBytes` (256 MiB) since its last reset is reset after
the unit (`Session.reset()` + `WorkerContext.reset()`: the overlay falls back to its base arrays) once the
capacity was not needed by 8 units in a row (a unit "needs" it when its sampled peak footprint is above a
quarter of the growth): a heavy declaration's arrays go back to the collector a few units later, a run of heavy
declarations keeps them (no thrashing between growing and giving back). Re-check lanes are reset after every
stage anyway. Counters per worker: `giveBacks`, `givenBackBytes`, `maxSlackBytes`.

### Collections (`gcAfterBytes`, off by default)

The JVM's serial collector collects the old generation only when the heap is full, and the launcher's heap
is 13 GB: there the peak resident set of a long run is the heap size, not what the run holds; the native
image's collector is more eager but still leaves 1 GB to 2 GB of garbage at the peak (see "Where mathlib's
memory goes"). `gc-after-mib=N` makes a worker ask for a collection (`System.gc()`) between two claims when the
heap holds N MiB more than the account knows of (measured against what was left unaccounted after the
previous collection). It trades CPU time and wall clock for resident memory, which is the wrong direction
for the project's order of goals unless the trade is nearly free: the numbers are in "Measured".

## Pipelined loading (ARCHITECTURE 3.5, 6.1)

Agreed interface (requests.log 07:56Z - 08:50Z): no new `Wiring` method. The driver hands
`LoadOptions.pipelined(bytes).withPublishEvery(units, bytes).withProofBlocks(..).withListener(l)` to
`Wiring.load(store, options)`; the wiring passes them to `rlvm.load.Loader` unchanged; the loader calls
`l.published(units, complete)` on the loading thread after `LevelInterner.publish(units)` and
`Store.publishLoaded(units, complete)`. `rlvm-main` (published 08:47Z) builds the store on a
`rlvm.load.LevelChain` for `-X load=pipelined`; worker contexts are overlays of the newest level snapshot
and tell how far they reach (`WorkerContext.coveredUnits()`).

What the driver adds:

- **Threads.** loader = the thread that called `execute()`; environment thread; W workers. They are started
  by the first publication that is not the end of the file, so a file that arrives in one piece (smaller
  than the hand-off interval: 4,096 units and 32 MiB) is a batch run with the batch worker count.
- **A large file is published in larger pieces** (wave 8, `publishScaleMax`): the hand-off interval is
  multiplied by the input's size in GiB, at least 1 and at most 4, so mathlib (5.9 GiB) is published every
  16,384 units / 128 MiB (about 50 publications instead of 205) and everything below 2 GiB as before. A
  publication is not free for the workers: each worker context builds a new level overlay at its first reset
  after a new snapshot (the level and list tables are copied), about 110 MB of garbage and 45 ms of worker
  CPU per publication on mathlib, and with 8 workers the collections that follow stop everybody (that was
  the layout until 2026-11-25: the workers now read the frozen level store in place and rebase their overlay
  for the price of a reset, *agentic-note*: rep-OPT-C; the hand-off interval was left as it is). The workers
  are never short of units on a file of that size (the loader delivers 19 CPU-seconds of work per second),
  whereas on the mid-size corpora they do wait for the loader, which is why the interval stays small there.
  Numbers in "Measured", wave 8.
- **Nobody polls.** The environment thread parks on the loaded frontier (`LoadFrontier`) and is woken by
  the listener; workers park in the scheduler as before. Parked threads look at the first failure every
  50 ms as a safety net against a lost wake-up (no CPU time to speak of).
- **Snapshots.** A lane is reset before the first unit at or beyond
  `min(Store.loadedUnits() read before the lane's last reset, WorkerContext.coveredUnits())` (worker
  counter `snapshotResets`; the environment lane likewise, between two `advanceTo` calls).
- **"Not loaded yet" is not a verdict.** While the file is loading, `rlvm-admit` declines what it could
  only decide with the whole file (a reference to a name that is not declared so far: forward reference or
  unknown constant?), and a rejection may rest on a name that a later record still provides. So **nothing
  but an ACCEPT is recorded for a unit that started before the end of the load**: the worker returns the
  rest of its claim, waits for the end of the load and runs the unit's protocol once more (`deferredUnits`);
  that outcome is recorded. The environment pass: a failure reported while loading is withheld and the unit
  is decided again after the load by a new pass (`RunHost.retryEnvUnit`; `driver.env.retries`; if the host
  cannot do that, the unit is DECLINED, never rejected); a pass that says nothing and waits
  (`EnvBuilder.waitingForLoad()`, `rlvm-admit` since 10:34Z) is advanced again after the load
  (`driver.env.waitsForLoadEnd`). The price is paid by failing runs only: their failing unit is checked
  twice, and the verdict comes after the end of the load.
- **After the end of the load the lane is reset; the pass is kept** (wave 8; until then it was replaced,
  `driver.env.renewals`, now always 0): `rlvm-admit`'s admission in dependency order
  (`Policy.ForwardRefs.REORDER`, the arena policy since 10:34Z) reads units beyond the frontier, which needs
  a lane whose level and list view reaches the end of the file. The pass is told how far the lane reaches
  (`EnvBuilder.setCoverage`, `rlvm-admit` 11:20Z) and enters dependency order by itself once the file is
  complete and the lane covers it. Replacing it was wrong in one case: a pass that had already left file
  order (the load ended while it was advancing over a lane that covered the last publication) has decided
  units ahead of the frontier and dropped their claims; its replacement, which starts at the frontier,
  declined them ("the inductive unit has no claims blob", requests.log 15:12:24Z). (History: advancing a pass
  over a stale lane view gave REJECT for reordered exports that batch mode accepts; the invariance suite
  caught it with the `rlvm-admit` jar of 10:34Z, and `setCoverage` is the fix.)
- **The parking rule** (wave 8; proposed by the load path, requests.log 14:35:27Z): the environment thread
  does not advance the pass past a unit for which `RunHost.unitNeedsCompleteFile(unit)` holds while the
  file is loading. `StoreHost` answers from the store's summaries, without decoding anything: a type root
  or shared value root whose `depMax` lies beyond the unit's own rank (something was not declared when the
  loader wrote the node), or a value kept as a proof block with `ProofBlocks.hasUnresolved`. The frontier
  stops below such a unit (`driver.env.parkedUnits`), the workers go on with everything below it, and after
  the end of the load the pass, over the complete file and a lane that covers it, decides the unit exactly
  as a batch run does (dependency order). Before the rule the pass moved past a unit whose proof block
  mentions a later declaration (it does not decode blocks while the file loads), and, in a window of a few
  microseconds, past one whose type does (the pass asks the store whether the file is complete, which
  becomes true before the driver's lane covers it); a worker could then only decline the unit. Exporter
  output never parks: 0 parked units on Init, Std and mathlib.
- **Fail closed where the driver cannot see.** If the failed attempt of the pass on the incomplete file
  had written down failures of other units as well (`RunHost.RETRY_TAINTED`: `StoreHost` erases them; not
  observed with the published `rlvm-admit`), the run records no rejection any more: what would be REJECTED
  is DECLINED(UNCONFIRMED_REJECT) (`driver.pipeline.rejectionsTainted`).
- **What is still different from batch mode**: nothing that the guards find. Until wave 8 a file that is
  not in declaration order could be DECLINED (FORWARD_REFERENCE) in a pipelined run where the batch run
  decides it (the invariance suite's "pipelined declines": 5 of 16,928 mutants with the machine as primary
  engine, 27 with the reference engine before `rlvm-admit` learnt to wait); with the kept pass and the
  parking rule the suite counts 0 with both engines. A failure of the environment pass that is reported
  while the file is loading is still withheld and decided again by a new pass (`retryEnvUnit`); should that
  pass have decided blocks ahead of the frontier before, the retry declines them (never observed; fail
  closed).
- **Failures of the load.** Malformed input: REJECT whatever was checked (the official reader rejects before
  any checking). Unsupported input, I/O errors: the published units are checked; REJECT if one of them is
  rejected (confirmed), else DECLINE; never ACCEPT. An `Error` in the loader cancels the run, the checking
  threads are joined, then it is rethrown.
- **Verdict.** The same as the batch run's, with the same deciding unit, or (the case above) a DECLINE where
  the batch run has a verdict: `InvarianceTest.sameVerdictWhateverThePipeline` (fakes, 1,600 runs),
  `ArenaIntegrationTest` (the 141 tutorial tests, real loader: equal) and the invariance suite below.

Not the default: `RunConfig.loadMode` is `BATCH` and `rlvm-main` sets `PIPELINED` only for
`-X load=pipelined`. See "Measured" and "Open" for what a default switch needs.

## Where the wall clock goes (statistics, wave 8)

Owner note 12 makes the wall clock of 8 workers the goal, so the statistics now say where it goes. Everything
below exists only with a statistics file (`-X stats-file=F`); without one the cost is the branches that were
there before. Nothing takes a shared lock per unit: each worker keeps its per-unit statistics (`UnitStats`)
to itself and hands them over when it ends (until wave 7: `RunStats.unit` under one lock, once per unit).

| In the JSON | What |
|---|---|
| table `driver.workerTimes`, one row per worker | `wallNanos`, `cpuNanos`, `runQueueNanos` (`/proc/thread-self/schedstat`: on a CPU, runnable without one); `unitNanos` (inside units), `claimNanos` (inside `Scheduler.claim`), of which parked `waitEnvNanos` (nothing announced by the environment lane), `waitLoaderNanos` (the lane itself was waiting for the loader), `waitTailNanos` (after the last announcement: only busy claims left); `gateWaitNanos`, `loadEndWaitNanos`, `openNanos`, `resetNanos` / `laneResetNanos` (warm session resets, the lane's part), `lazyEnvNanos` (one worker, batch: the environment pass it drives itself); `claims`, `parks`; `startAt`, `firstClaimAt`, `lastUnitEndAt`, `endAt` (nanoseconds since the start of the run) |
| `driver.env.*` | the environment thread: `wallNanos`, `advanceNanos` / `advances` (inside the pass), `announceNanos`, `waitLoaderNanos`, `waitLoadEndNanos`, `openNanos`, `parkedUnits`; table `driver.env.slowest` (the 12 longest steps: nanoseconds, first unit, units) |
| `driver.time.*` | `loadStart`, `loadEnd`, `firstPublication`, `checkStart`, `checkEnd`, `envStart`, `envClosed` (the frontier is complete), nanoseconds since the start of the run |
| tables `driver.series.worker-N`, `driver.series.env`, `driver.series.loader` | progress over time: per worker (time, accepted, nanoseconds in units, in `claim`) about every 100 ms; the environment frontier; the loader's publications |
| `units.slow`, `units.histogram` | the 50 slowest units by wall time (beside `units.top`, the 50 heaviest by steps), and the time by unit duration class |
| `F.pass2`, `F.pass3`, ... | the complete statistics of every later pass (`passes=N`) |
| `F.units.tsv` (`-X unit-trace`) | one line per checked unit: unit, worker, start, nanoseconds, steps, warm outcome, size (`RunHost.unitWeight`: block nodes + shared terms of the unit) |

What is not the driver's to see is the difference: `wall - cpu - runQueue - parks - gate - loadEnd` = blocked for
another reason, which in practice is a collection (every worker stops for one collector thread) or a page
fault. `rlvm-driver/bin/breakdown.py F.json [--series]` prints the table per worker, the shares of
`workers x check wall`, the environment thread, the tail ("frontier complete" to "last unit ended"), the
slowest units; `rlvm-driver/bin/gcsum.py LOG [--phase A B]` adds up the collections of a native-image run
started with `-XX:+PrintGC`.

`-X shard=K/N` (measurement only) makes the workers pass over every def / thm / axiom / opaque unit that is
not in shard K of N (blocks of 1,024 consecutive units, block number modulo N); load and environment pass are
complete, and the run's verdict is DECLINE ("only shard K of N was checked") whatever happens. N one-worker
processes over the N shards, each pinned to a core, against one process with N workers separate what the
hardware gives from what one process costs: the numbers are in "Measured", wave 8.

## Worker count (`--threads auto`)

`Run.resolveWorkers(0) = Run.autoWorkers(Runtime.availableProcessors()) = max(1, min(P - 2, 6))`: 6 on the
arena's 8-vCPU runner (ARCHITECTURE 6.8: two CPUs for the loader, the environment thread and the runtime's
compiler and collector threads). A batch run additionally caps it at one worker per 128 units. `rlvm-main`
passes `--threads auto` through since 08:19Z; the arena run line still names `--threads 4`.

The gate ("the largest N <= 6 whose total CPU time exceeds that of one worker by at most G %") is one
command:

```
rlvm-driver/bin/threads-gate.sh [--dist DIR] [--jvm|--native] [--threads 1,2,4,6,8] [--repeat K] [--gate 5]
                                [--set k=v,..] [-X KEY=VALUE] EXPORT.ndjson...
```

It runs the distribution's launcher in the arena-shaped container (8 performance cores, 16 GB, `perf stat`),
every single run under the ladder lock, and prints CPU time, wall clock, peak RSS, instructions, the verdict,
the CPU overhead against one worker and the N the gate selects. Results: "Measured".

## Invariants

- Fail closed: `exitCode()` is DECLINE until `complete(n)`; any `RuntimeException` at a unit boundary
  ends in the reference kernel's verdict or DECLINE(INTERNAL_ERROR); any throwable elsewhere records an
  internal failure (verdict DECLINE, everything cancelled); an `Error` is additionally rethrown by
  `execute()` on the calling thread, after all threads were joined and engines closed.
- The verdict and the deciding unit do not depend on the worker count, the chunk size, the environment
  batch size, the session cap, the give-back threshold, the warm byte budget or the load mode, provided
  engines honour `rlvm.api.Session`'s obligations (an ACCEPT is a semantic fact; a fresh session is
  deterministic) and poll `RunSignals` (both signals). A unit is declined for memory only if it does not fit
  into the limit by itself.
- No time-based decision affects a verdict (`spillNanos` only moves units between workers; the 50 ms
  re-checks of parked threads only bound how long a cancelled waiter stays parked).
- Sessions are created, used and closed on one thread. Engines are closed and `dumpStats` is called after
  the last session is closed.
- Nothing is allocated per unit on the accept path; statistics off = one predictable branch per unit.

## Tests and commands

`. ./env.sh; $MVN -o -q -pl rlvm-driver test` (106 tests, 3 of them opt-in; about 40 s on a free machine). All but
`ArenaIntegrationTest` use fake engines, a fake admission, a fake environment pass and a fake loader (`Fakes`).

- `VerdictBoardTest`: the status chain by enumeration (every assignment of 5 statuses to up to 4 units x
  input failure kinds and positions x `complete` x internal failure x stopped, reported in random order,
  against an independent statement of the rule); double reports; C3.13.
- `SchedulerTest`: runs, stealing, give-back, first-failure cut, blocking; **slice mode** (a worker takes the
  next slice for itself, few ready units are shared out, no stealing while units wait); every unit exactly
  once under contention (both modes); **all interleavings** of small cases (2-3 workers, 4-5 units, all 3^n
  scripts, pipelined publication, give-back; one run per worker and batch, and slices) by state-space search
  (`-Drlvm.driver.explore=big` adds 3.1 million states): verdict, first failure, no deadlock, no unit twice.
- `RecheckProtocolTest`: the protocol table by enumeration (2 x 2 x 2 x 4^4 = 2048 cases): final status,
  which engines ran, fresh sessions and cold budgets, events, reported reason.
- `InvarianceTest`: 400 random workloads (`-Drlvm.driver.invariance=N -Drlvm.driver.seed=S` for soak runs)
  with injected failures (true and wrong rejections, declines for memory and for other reasons, exceptions,
  session-dependent rejections, non-terminating units behind a failure) x 1 / 2 / 4 / 8 workers with random
  chunk, batch and session-cap settings: verdict and deciding unit equal the oracle. The same **pipelined**:
  1 to 6 publications at random points with random pauses, units that cannot be decided before the end of the
  file (worker units and environment units, both protocols of the environment pass); fake lanes report a
  violation when a unit is admitted beyond their snapshot.
- `PipelineTest`: a file in pieces, a file in one piece, malformed / unsupported / unreadable input in the
  middle of a load, units that need the rest of the file, **the parking rule** (the frontier stands below an
  out-of-order unit until the load has ended, the verdict is the batch run's; without the host's answer the
  same file is declined), an `Error` in the loader, a loader that returns early, the snapshot rule with a
  deterministic loader. `InvarianceTest`'s pipelined workloads contain out-of-order units too.
- `MemoryAndGateTest`: the account (victim, margin, exempt holder, last resort), the gate (order, giving way,
  cancellation), memory retries in runs, a non-terminating gate holder behind an ill-typed unit, give-back,
  the settings by name, the automatic worker count.
- `RunTest`: fail-closed paths (Error, exceptions outside a unit, incomplete load, count mismatch),
  environment failures, non-termination behind a failure, session reset rules, **the session cap per engine
  name**, memory watermark, load failures through a `Wiring`, stop-after, `no-fallback`, deferred features,
  diagnostics, statistics JSON; **slow units**: a heavy unit that ends only when the last unit of its own
  claim was checked (the run hangs unless the rest is given back while the unit runs; 2 / 4 / 8 workers), two
  heavy units in one claim, no give-back with one worker or with `spill-ms=0`; **passes**: three passes check
  every unit three times with fresh sessions, a rejected export gets one pass, the verdict is pass 1's;
  **kept engines** (`passes-keep=engines`: one set of engine instances for all passes, closed after the last);
  **shards** (only the shard is checked, the run declines, a failure inside the shard is the verdict's);
  **the time account** (tables, time stamps, series, statistics files of later passes, slowest units,
  histogram, a worker's own unit statistics merged).
- `ArenaIntegrationTest` (real reader, loader, store, admission, reference kernel; `TestWiring` mirrors
  `rlvm.main.MainWiring`, batch and pipelined): the 141 tutorial tests with one worker; the same verdict and
  deciding unit for 1 / 2 / 4 / 8 workers and a 1-byte session cap; the same when the file is checked while
  it loads (a publication after every unit and every third unit, 1 and 4 workers). `-Drlvm.it=all` adds the
  other published tests and `init-prelude`.

Commands (all in `rlvm-driver/bin/`, none touches another module):

| Command | What it does |
|---|---|
| `invariance.sh [CORPUS...]` | **the invariance suite as one command**, to run before every publication: every file 9 times (batch with 1 / 2 / 4 / 8 workers, 1-byte session caps with 1 and 4 workers, pipelined with a publication after every unit / 7 units / 64 units and 1 / 4 / 8 workers); verdict and deciding unit must be equal and the verdict the expected one. A pipelined run that DECLINES what the batch run decides is counted apart ("pipelined declines") and is not a failure; any ACCEPT / REJECT difference is. Corpora: `published` (231 files), `tutorial` (12,260 mutants), `bugs` (2,692), `mdata` (1,976), `prelude` (847), `big` (init-prelude, Init, Std, cedar), `all`. Pinned to the efficiency cores. Exit 0 = PASS |
| `threads-gate.sh EXPORT...` | the worker-count gate (above); `--engine machine [-X machine.switches=...]` for the machine |
| `measure-bench.sh EXPORT [DriverBench options]` | one measured run of `DriverBench` in the arena-shaped container (for what the launcher cannot run yet) |
| `freeze-cp.sh [TAG]` | a frozen copy of the test class path for long runs |
| `breakdown.py STATS.json... [--series]` | where the wall clock of a run went: per worker, per cause, environment thread, tail, slowest units (see "Where the wall clock goes") |
| `gcsum.py LOG... [--phase A B]` | collections and pause time of a native-image run started with `-XX:+PrintGC` |

`invariance.sh --engine machine [-X machine.switches=warm,gated,expect] CORPUS...` runs the same suite with the
published `rlvm-machine` jar as primary engine (the launcher's `-X engine=machine`; `freeze-cp.sh TAG machine`
adds the jar to the frozen class path).

`DriverBench` (a `main` in the test tree): any export or directory through the driver with a `Wiring` equal to
`MainWiring`: `-j N`, `--compare`, `--pipelined [--publish-units N]`, `--set k=v,..`, `--stats F`,
`--engine NAME [--engine-opt KEY=VALUE]` (an engine found through `ServiceLoader`, as `rlvm-main` does),
`--mem-trace F [--gc-every S]` (accounted, shared and heap bytes every second; with `--gc-every` what is
reachable).

## Measured

<!-- measured:begin -->
All numbers: reference kernel as primary engine, batch load unless noted, arena-shaped container (8
performance cores, 16 GB, `perf stat`; `rlvm-driver/bin/threads-gate.sh`, which runs the launcher of a
distribution built from this module's tree and the published jars: `work/rlvm-driver/dist-native`, built
09:10Z with rlvm-ref 9089b0a558f3 and rlvm-store 90b8cf9432cb, i.e. before the store and reference jars of
10:33Z / 10:37Z; the driver in it is publication 1 without the later pipelined fixes and with immediate
give-back). Columns in the project's order: CPU
time, wall clock, peak RSS, instructions. **One run each, on 2026-11-24 between 09:15Z and 11:30Z, while
the host's load average was 70 to 180** (sixteen agents running guards; other measurements shared the
container's eight cores, not all of them under the ladder lock): CPU time is inflated by up to 50 % against
a quiet machine and wall clock by more (the same Init run took 15.1 s CPU at 07:30Z and 23.9 s now), and
two runs of one configuration differ by 10 % and more. Instruction counts and peak RSS do not depend on the
load; where the two disagree with the CPU time, the instruction count is the one to believe. Raw data:
`work/rlvm-driver/gate/*.jsonl` (`threads-gate.sh --table FILE` prints a table again).

### Warm byte budget (task: "two mathlib units and one con-leche unit are re-checked cold")

con-leche (36,747 units; unit 24018 needs 2.49 GB of session memory), JVM launcher, 8 workers, back to back:

| `unit-mib` | CPU | wall | peak RSS | instructions | units checked twice |
|---|--:|--:|--:|--:|--:|
| 789 (the former automatic share, headroom / 2W) | 111.4 s | 67.8 s | 9.83 GB | 740.1 G | 1 |
| 0 = automatic = the whole headroom (now) | 80.6 s | 43.7 s | 7.52 GB | 637.7 G | 0 |

-27.7 % CPU time, -35.6 % wall clock, -23.5 % peak RSS, -13.8 % instructions. Any budget below what the unit
needs (2.49 GB) gives the first line and any budget above it the second, so there is nothing to tune between
them: the budget is the headroom, and the account's victim rule plus the heavy gate do what the share was
for. mathlib: units 113469 and 415533 (0.96 GB) are checked once (0 re-checks in every run of the new
driver; with the 654 MiB share they cost about 47 s of 829 s CPU, 5.7 %); instructions of the native image
at 8 workers 10,207 G -> 10,068 G (-1.4 %).

### Worker count (`--threads auto`)

`rlvm-driver/bin/threads-gate.sh --dist work/rlvm-driver/dist-native --jvm init.ndjson std.ndjson cedar.ndjson`
(JVM launcher; "CPU vs 1" is what the gate looks at):

| Corpus | workers | CPU | wall | peak RSS | instructions | CPU vs 1 worker | instructions vs 1 |
|---|--:|--:|--:|--:|--:|--:|--:|
| Init (58,547 units) | 1 | 23.91 s | 22.07 s | 1,107 MB | 205.7 G | | |
| | 2 | 23.59 s | 11.72 s | 1,082 MB | 212.0 G | -1.4 % | +3.1 % |
| | 4 | 26.37 s | 9.14 s | 1,153 MB | 224.8 G | +10.3 % | +9.3 % |
| | 6 | 25.30 s | 8.65 s | 1,156 MB | 228.7 G | +5.8 % | +11.2 % |
| | 8 | 28.70 s | 9.74 s | 1,471 MB | 226.1 G | +20.0 % | +9.9 % |
| Std (101,146 units) | 1 | 46.95 s | 44.94 s | 1,331 MB | 335.3 G | | |
| | 2 | 41.09 s | 20.49 s | 1,335 MB | 329.8 G | -12.5 % | -1.6 % |
| | 4 | 37.70 s | 10.37 s | 1,393 MB | 331.8 G | -19.7 % | -1.0 % |
| | 6 | 40.66 s | 8.22 s | 1,652 MB | 343.7 G | -13.4 % | +2.5 % |
| | 8 | 41.34 s | 7.88 s | 1,795 MB | 342.5 G | -11.9 % | +2.1 % |
| cedar (905 MB) | 1 | 54.39 s | 52.31 s | 1,460 MB | 412.1 G | | |
| | 2 | 54.54 s | 26.76 s | 1,502 MB | 421.6 G | +0.3 % | +2.3 % |
| | 4 | 46.48 s | 13.69 s | 1,593 MB | 407.0 G | -14.5 % | -1.2 % |
| | 6 | 51.50 s | 11.00 s | 1,725 MB | 425.6 G | -5.3 % | +3.3 % |
| | 8 | 48.46 s | 8.88 s | 1,855 MB | 416.5 G | -10.9 % | +1.1 % |

The same with the native image (`--native`; no JIT, so the instruction count shows what more workers add
by themselves: nothing):

| Corpus | workers | CPU | wall | peak RSS | instructions | CPU vs 1 worker | instructions vs 1 |
|---|--:|--:|--:|--:|--:|--:|--:|
| Init | 1 | 24.40 s | 24.43 s | 309 MB | 286.1 G | | |
| | 2 | 23.20 s | 12.11 s | 328 MB | 286.2 G | -4.9 % | +0.0 % |
| | 4 | 20.75 s | 6.15 s | 457 MB | 286.5 G | -14.9 % | +0.1 % |
| | 6 | 21.30 s | 5.41 s | 488 MB | 286.7 G | -12.7 % | +0.2 % |
| | 8 | 22.73 s | 5.61 s | 624 MB | 286.8 G | -6.8 % | +0.2 % |
| Std | 1 | 45.85 s | 45.96 s | 400 MB | 478.6 G | | |
| | 2 | 42.36 s | 22.30 s | 526 MB | 478.9 G | -7.6 % | +0.1 % |
| | 4 | 42.87 s | 12.72 s | 599 MB | 479.2 G | -6.5 % | +0.1 % |
| | 6 | 40.02 s | 10.21 s | 626 MB | 479.4 G | -12.7 % | +0.2 % |
| | 8 | 41.39 s | 10.84 s | 667 MB | 479.7 G | -9.7 % | +0.2 % |
| cedar | 1 | 57.70 s | 57.77 s | 545 MB | 603.4 G | | |
| | 2 | 54.75 s | 28.87 s | 548 MB | 603.6 G | -5.1 % | +0.0 % |
| | 4 | 49.86 s | 14.92 s | 630 MB | 603.9 G | -13.6 % | +0.1 % |
| | 6 | 50.30 s | 11.51 s | 718 MB | 604.1 G | -12.8 % | +0.1 % |
| | 8 | 52.56 s | 10.54 s | 790 MB | 604.5 G | -8.9 % | +0.2 % |

mathlib (721,845 units), 8 workers, the new driver: JVM launcher 953.5 s CPU / 185.0 s wall / 13.15 GB
(the heap) / 6,290 G instructions (wave 3: 841.8 s / 139.2 s / 13.08 GB / 6,374 G on a quieter host; the
workers of this run waited 382 s for a CPU); native image 1,267.8 s / 374.7 s / 6.80 GB / 10,068 G (wave 3:
1,027.6 s / 178.0 s / 9.42 GB / 10,207 G; 1,436 s of run-queue wait in this run). Instructions fell by 1.3 %
and 1.4 %; the CPU times of the two days cannot be compared.

mathlib, JVM launcher, worker counts (`threads-gate.sh --lock run --jvm --threads 8,6,4,2,1`; the runs with
8, 6 and 4 workers between 10:42Z and 11:12Z, the one-worker run last, when the host was quieter):

| workers | CPU | wall | peak RSS | instructions | CPU vs 1 worker | instructions vs 1 |
|--:|--:|--:|--:|--:|--:|--:|
| 1 | 874.1 s | 870.5 s | 11.3 GB | 6,171.2 G | | |
| 2 | 907.1 s | 459.0 s | 13.3 GB | 6,227.0 G | +3.8 % | +0.9 % |
| 4 | 907.1 s | 239.6 s | 13.1 GB | 6,196.9 G | +3.8 % | +0.4 % |
| 6 | 930.6 s | 226.3 s | 12.9 GB | 6,208.8 G | +6.5 % | +0.6 % |
| 8 | 953.5 s | 185.0 s | 13.1 GB | 6,290.1 G | +9.1 % | +1.9 % |

Here the CPU time does rise with the workers while the instructions do not (within 2 %): the same work
costs more per instruction when six or eight workers share the memory system (the reference kernel's time
is cache misses of its hash-consing probes, integration.md): +2.6 % from 4 to 6 workers, another +2.5 % from
6 to 8. (The wall clock of the 4- and 6-worker runs is the host's: 240 s and 226 s for the same CPU time.)

Reading: with the reference engine a session keeps nothing between units, so more workers add no work of
their own. The native image shows it exactly: +0.2 % instructions from 1 to 8 workers on all three corpora
(the scheduler, the environment thread, the per-worker contexts). CPU time is lower with several workers
than with one in 22 of the 24 rows of Init, Std and cedar, which is the load on the host and not a
property of the checker (a one-worker run is a long single thread among 100 runnable ones). On the JVM, Init is a 20 s run: with W
workers W times as much runs in the interpreter and the first compiler tier before the optimised code
arrives (+10 % instructions from 4 workers on, flat from 4 to 8); Std and cedar stay within 3 %. On wall
clock 6 workers take 5.4 / 10.2 / 11.5 s (native) and 8.7 / 8.2 / 11.0 s (JVM), 8 workers 5.6 / 10.8 /
10.5 s and 9.7 / 7.9 / 8.9 s: 8 is not reliably better than 6 while the loader, the environment thread and
the runtime's own threads compete for the same 8 CPUs.

**Proposed default: 6** (`Run.autoWorkers`: the CPUs minus two, at most 6), i.e. `--threads auto` instead
of the run line's `--threads 4`, **subject to one confirmation**. For it: the gate of ARCHITECTURE 6.8 as
written (instructions within 2 % of one worker) selects 6 on all four corpora and both launchers except
Init on the JVM (JIT warm-up of a 20 s run, not removed by fewer workers on longer inputs); a gate of 5 %
CPU time selects 6 on Init, Std and cedar; against 4 workers, 6 save 12 % to 25 % of the wall clock on
those corpora. Against it: on mathlib the CPU-time gate reads +6.5 % for 6 workers (4 workers: +3.8 %),
from single runs taken at different loads, whose noise is as large as the difference. The confirmation is
`rlvm-driver/bin/threads-gate.sh --lock run --repeat 3 --jvm --native work/exports/mathlib.ndjson` on a
quiet host (about 2.5 hours of lock time); if 6 workers stay above the gate there, the rule says 4, which
is what the arena run line names today. The gate must be run again in any case for the machine engine,
whose warm sessions are what ARCHITECTURE 6.8 expected to cost 0.8 % to 1.6 %.

### Session cap

Std, JVM launcher, 8 workers, `-X session-mib=N` (RECONCILIATION section 3 asks for a sweep over 8 / 16 /
32 / 64 MiB "once the machine runs Std"; with the reference engine it only shows that there is nothing to
tune):

| `session-mib` | CPU | wall | peak RSS | instructions | session resets per worker |
|---|--:|--:|--:|--:|---|
| 8 | 40.14 s | 9.63 s | 1,559 MB | 342.3 G | 4 to 12 |
| 16 (default) | 39.17 s | 11.06 s | 1,586 MB | 332.1 G | 2 to 7 |
| 64 | 42.61 s | 11.99 s | 1,704 MB | 349.2 G | 1 to 3 |

### Pipelined against batch

JVM launcher, 6 workers, `-X load=pipelined` (through `rlvm-main`'s wiring and the real loader; the
distribution is the one of the other tables, i.e. the driver before the last pipelined fixes, which do not
touch a file in declaration order):

| Corpus | load | CPU | wall | peak RSS | instructions |
|---|---|--:|--:|--:|--:|
| Init | batch | 25.18 s | 7.49 s | 1,180 MB | 218.2 G |
| Init | pipelined | 23.59 s | 5.25 s | 1,158 MB | 220.2 G |
| Std | batch | 37.77 s | 8.88 s | 1,590 MB | 336.7 G |
| Std | pipelined | 38.30 s | 8.83 s | 1,576 MB | 326.9 G |
| mathlib | batch (11:55Z) | 896.4 s | 173.0 s | 13.1 GB | 6,146.2 G |
| mathlib | pipelined (12:00Z, right after it) | 876.0 s | 156.5 s | 13.0 GB | 6,200.5 G |
| mathlib | pipelined (11:44Z, a quieter quarter of an hour) | 805.8 s | 140.9 s | 13.2 GB | 6,005.3 G |

Pipelining costs no CPU time and no instructions beyond the noise (the level snapshots are cheap: 205
publications on mathlib, 1 or 2 forced session resets per worker), and it takes the load out of the wall
clock: on mathlib the batch run is 14.2 s of loading followed by 157.7 s of checking (172.0 s in the
driver), the pipelined run right after it 17.4 s of loading inside 154.6 s of checking (155.2 s), i.e.
-9.8 % wall clock; the environment thread waited for the loader 170 times and nobody polled. On Init and
Std the load is 0.8 s and 1.4 s, less than what two single runs on this host differ by (Init -2.2 s, Std
-0.05 s). The load becomes the larger part of the wall clock as soon as the machine engine checks mathlib
in tens of seconds: ARCHITECTURE 3.5's model is 35 s batch against 20 s pipelined.

### Where mathlib's memory goes (8 workers)

JVM launcher flags, `DriverBench --mem-trace --gc-every 10` (a full collection every 10 s shows what is
reachable; 86 samples after the load; the run itself was slowed down by the collections and the host):

| Part | Bytes | Accounted |
|---|--:|---|
| shared store (`Store.sharedBytes()`) | 2.30 GB | yes |
| loader transients at the end of the load (gone a few seconds later) | about 0.6 GB | no |
| workers' and environment lane's sessions, allocated capacity | mean 1.23 GB, peak 2.30 GB | yes (new) |
| reachable but not accounted (level overlays of 9 contexts, literal pools, young objects, the runtime) | mean 0.36 GB, peak 1.16 GB | no |
| **reachable in total** | mean 3.9 GB, **peak 5.28 GB** | |
| garbage waiting for a collection | everything else up to the heap size | |

So the workers' share of what is reachable is 1.2 GB on average and 2.3 GB at the peak (the former account
counted nodes in use, about 0.2 GB between units, and reported 3.9 GB for the whole run where 4.6 GB to
5.3 GB were held). The peak resident set is something else: the JVM collected its old generation twice in
the whole run (208 ms and 100 ms), each time when the 13 GB heap was full, so it shows 13.4 GB whatever the
run holds; with a collection every 10 s the same run stays at 6.8 GB.

Peak RSS, mathlib, 8 workers, arena-shaped container:

| Launcher | Driver | peak RSS | instructions |
|---|---|--:|--:|
| native image | wave 3 (integration.md) | 9.42 GB | 10,207 G |
| native image | now (`keep-mib` 256, no collection requests) | **6.80 GB** | 10,068 G |
| native image | now, `keep-mib=64,gc-after-mib=1024` (9 collections requested, 66 give-backs) | 6.32 GB | 10,075 G |
| JVM launcher | wave 3 and now | 13.1 GB (the heap setting) | 6,374 G -> 6,290 G |

**The target of 6 GB is not reached**: 6.80 GB with the default settings, 6.32 GB with the two knobs. The
account's own peak in those two runs was 4.92 GB and 5.39 GB (shared store 2.42 GB + sessions 2.51 GB and
3.08 GB: the two units of 0.96 GB overlap or not, depending on the schedule; each holds 1.3 GB of overlay
capacity while it runs), so less than 1 GB of the resident set is garbage any more. What would bring it
under 6 GB: (1) the store's overlay growing without copies and without doubling (a unit of 0.96 GB would
hold 1.0 GB instead of 1.3 GB, and leave no garbage of the same size behind); (2) the heavy gate also for
units that are merely large, so that two of them never overlap (expected: 0.6 GB to 0.9 GB less at the
peak, at the price of a parked worker for the seconds two such units coincide; not done, see "Open");
(3) the machine engine, whose sessions are a different animal.

What reduced it from 9.42 GB: no unit is checked twice any more (a cold re-check grew a second lane to the
size of the first), and a lane returns its arrays after a heavy unit. `gc-after-mib=N` bounds the garbage
part; its effect by itself, on con-leche, native image, 8 workers (host, efficiency cores, GNU time):

| `gc-after-mib` | CPU (user) | peak RSS | collections requested |
|---|--:|--:|--:|
| 0 (default) | 194.9 s | 4.40 GB | 0 |
| 1024 | 184.1 s | 4.40 GB | 2 |
| 512 | 194.7 s | 3.72 GB | 2 |

(Std: no effect, 0.70 GB to 0.78 GB with and without; nothing there leaves 256 MiB of garbage behind.) On
mathlib the nine collections cost +0.07 % instructions. The setting stays off by default: what a full
collection costs in wall clock (it stops all workers) has to be measured on a quiet machine before it may
trade time for memory, the project's fourth goal.

### Guards and invariance (the final jar, eb0944caa630)

| Guard | Result |
|---|---|
| `$MVN -o -q -pl rlvm-driver test` | 96 tests, 93 run green, 3 opt-in |
| `scripts/dev.sh gate --dist work/rlvm-driver/dist-final --corpus tutorial` (this tree + the jars published until 11:20Z; JVM launcher, 1 worker, batch) | PASS: 230 / 230 published tests correct, 0 wrong, 0 declined; mutants tutorial 12,260: SOUNDNESS 0, COMPLETENESS 0, agree 11,847, declined 413, errors 0; differential self-test caught |
| `rlvm-driver/bin/invariance.sh published` (231 files x 9 runs) | PASS: 231 correct, 0 wrong, 0 declined, 0 schedule differences, 0 pipelined declines (38 minutes on the loaded host) |
| `rlvm-driver/bin/invariance.sh tutorial bugs mdata` (16,928 mutants x 9 runs: 152,352 runs) | PASS: 0 wrong verdicts, 0 schedule differences, 1,675 declined in every mode alike (413 + 65 + 1,197), 27 pipelined declines (out-of-order files, see "Pipelined loading"); 3 minutes |

Earlier states of the tree went through the same guards four times during the day (publication 1 at
09:39Z: 230 / 230 and SOUNDNESS 0 twice); the list is in `work/status/rlvm-driver.log`. With the arena's
time limits enforced the slice lost up to 9 `perf/*` tests to the host's load (load average 150 to 180);
without the limits, and in the invariance suite, each of them has the expected verdict.

### Wave 7: the machine as primary engine (2026-11-24, 13:30Z to 15:30Z)

All rows: `-X engine=machine`, CE native image `-O3`, arena-shaped container (8 performance cores, 16 GB,
`perf stat`), under the measurement lock, columns CPU time, wall clock, peak RSS, instructions. Tool:
`work/rlvm-driver/w7/m.py` (a copy of the machine integrator's `work/machine-int/m.py`; rows in
`work/rlvm-driver/w7/measure.tsv`, summary `work/rlvm-driver/w7/summary.py`). **The host's load average was 40
to 240 during all of it** (two other workflows were building, guarding and measuring; all twenty CPUs were
busy, the container's eight included, whenever sampled): CPU times are 6 % to 10 % above the machine
integrator's of 13:00Z for the same binary, single runs of one configuration differ by up to 5 % (the Cedar
slice by more), and wall clocks of runs with eight workers are up to twice what a quiet host gives.
Differences below that are not results.

**Owner note 12 (14:08Z) changed the metric while this section was being measured**: warm wall clock with 8
workers and pipelined loading comes first, CPU time is a diagnostic, and wall-clock numbers count as evidence
only when taken through `bench/quiet.py` (published 15:05Z). Nothing below was taken through it, and the
runs are cold processes. So: the session-cap sweep (one worker, wall = CPU to 0.5 %) and the placement facts
of "Slow units" stand as they are; every wall-clock figure with 4 or 8 workers (reachability before / after,
the worker-count series, pipelined against batch) shows a direction and has to be re-taken with
`bench/quiet.py -- ...` and `-X passes=2` before it decides a default. The commands are the ones named in each
part (`work/rlvm-driver/w7/sched.sh`, `sweep-cap2.sh`, `rlvm-driver/bin/threads-gate.sh --engine machine`).

#### Session cap (request of the machine integrator, requests.log 13:05Z)

`-X machine.switches=warm,gated,expect -X session-mib=N`, one worker, the distribution the integrator
measured (`work/machine-int/dist-g-native`: machine 608b84b2b142, driver eb0944caa630), three rounds (the Cedar
slice without a warm session: two), median
(range) of the CPU time in seconds; instructions in G:

| `session-mib` | Init CPU | instr | Std CPU | instr | Cedar slice CPU | instr |
|---|--:|--:|--:|--:|--:|--:|
| 2 | 7.71 (7.64 - 8.15) | 118.0 | 14.08 (13.49 - 14.17) | 196.5 | 3.32 (3.02 - 3.51) | 46.0 |
| **4** | **7.78** (7.73 - 7.83) | 109.6 | **13.80** (13.71 - 13.87) | 185.1 | **3.40** (2.97 - 3.59) | 45.9 |
| 8 | 8.23 (8.18 - 8.61) | 103.1 | 14.59 (14.25 - 15.33) | 175.6 | 3.58 (3.51 - 3.59) | 44.8 |
| 16 (the former default) | 9.23 (9.14 - 9.29) | 97.9 | 16.32 (16.05 - 17.23) | 167.2 | 4.15 (3.83 - 4.19) | 44.0 |
| no warm session (no switch) | 8.13 (8.07 - 8.27) | 139.0 | 13.72 (13.45 - 13.99) | 223.2 | 3.40 (3.37 - 3.42) | 47.0 |

Reading: 2 MiB and 4 MiB cannot be told apart (Init -0.9 %, Std +2.0 %, Cedar slice -2.4 % for 2 MiB: inside
the spread of the three runs); 8 MiB costs +5.3 % to +5.8 % on all three, 16 MiB +18 % to +22 %. **The default
for the machine is 4 MiB** (`RunConfig.MACHINE_SESSION_BYTES`; what the integrator asked for). The
instruction count goes the other way (Init 118.0 G at 2 MiB, 97.9 G at 16 MiB): a longer session re-uses more
and misses the cache more, so the cap is a locality setting and CPU time is the measure for it (OWNER-NOTES
10). For the machine's owners: on Std a warm session at its best cap is no faster than no warm session
(13.80 s against 13.72 s, with 17 % fewer instructions); on Init it saves 4 %.

#### Slow units inside a claim (reachability)

Where the four heavy units of reachability (4137, 4139, 4145, 4158: 32 M of the corpus's 35 M machine ticks,
inside 22 consecutive units) run, from the run statistics (`units.top[].worker`, CPU time per worker thread).
These facts do not depend on the host's load. Two native images from the same jars, differing only in the
driver jar (`work/rlvm-driver/w7/dist-B`: published eb0944caa630; `dist-native`: this tree, 50adc1f555bd),
`-X engine=machine -X machine.switches=warm,gated,expect -X session-mib=4`:

| Driver | workers | runs | heavy units on one worker | CPU of the busiest worker | workers with less than 0.5 s of CPU |
|---|--:|--:|---|--:|--:|
| before | 8 | 3 | three of the four in 2 runs (4137 + 4139 + 4145), two and two in 1 | 10.5 s, 10.2 s, 8.7 s | 6 of 8 |
| before | 4 | 3 | three of the four in all 3 runs | 10.6 s, 10.3 s, 10.5 s | 2 of 4 |
| after | 8 | 3 | none: four units on four workers in all 3 runs | 4.3 s, 4.3 s, 4.2 s | 4 of 8 |
| after | 4 | 3 | none: four units on four workers in all 3 runs | 4.3 s, 4.6 s, 4.4 s | 0 of 4 |

(These twelve runs were made on the host, on the efficiency cores at a load average above 200,
`work/rlvm-driver/w7/host-reach.sh`: their CPU figures are those of slower cores and their wall clock, 75 s to
203 s before and 32 s to 63 s after, says nothing beyond the direction; total CPU time is the same before and
after, 14.9 s to 15.5 s of user + system time.)

The measurement, in the container, one hold of the lock (`work/rlvm-driver/w7/sched.sh`, 14:45Z to 14:58Z,
load average 180 to 240; `-X machine.switches=warm,gated,expect -X session-mib=4` in both; median (range) of
the runs):

| Corpus | workers | runs | CPU before | CPU after | wall before | wall after | | peak RSS before -> after |
|---|--:|--:|--:|--:|--:|--:|--:|--:|
| reachability | 8 | 3 | 8.49 s (8.38 - 8.65) | 8.59 s (8.49 - 8.63) | 6.50 s (6.26 - 7.32) | **4.66 s** (4.35 - 5.36) | **-28 %** | 1,948 -> 2,751 MiB |
| reachability | 4 | 3 | 8.29 s (8.22 - 8.43) | 8.56 s (8.50 - 8.76) | 8.31 s (6.66 - 8.67) | **4.08 s** (3.93 - 4.78) | **-51 %** | 1,869 -> 2,392 MiB |
| con-leche | 8 | 2 | 18.34, 19.00 s | 18.59, 18.64 s | 8.44, 9.46 s | 8.00, 9.39 s | | 791 -> 718 MiB |
| con-leche | 4 | 2 | 16.71, 16.91 s | 16.41, 17.43 s | 7.80, 8.60 s | 7.65, 8.64 s | | 624 -> 628 MiB |
| Init | 8 | 3 | 9.05 s (8.89 - 9.42) | 8.91 s (8.88 - 9.08) | 3.25 s (3.02 - 5.38) | 3.65 s (3.43 - 3.94) | | 338 -> 346 MiB |
| Std | 8 | 3 | 15.47 s (15.39 - 15.74) | 15.57 s (15.22 - 16.02) | 6.08 s (5.76 - 7.26) | 5.93 s (5.01 - 7.39) | | 482 -> 498 MiB |
| mathlib | 8 | 1 | 278.4 s | 263.5 s | 89.4 s | 81.8 s | | 2,786 -> 2,815 MiB |
| mathlib | 4 | 1 | 244.9 s | 244.0 s | 77.3 s | 88.4 s | | 2,630 -> 2,550 MiB |
| mathlib, `-X load=pipelined` (after only) | 8 | 1 | | 278.3 s | | 80.0 s | | 3,387 MiB |
| mathlib, `-X load=pipelined` (after only) | 4 | 1 | | 247.6 s | | 68.1 s | | 2,988 MiB |

Reading:

- **reachability: -28 % wall clock with 8 workers, -51 % with 4**, in every one of the three pairs. In the
  after runs the four heavy units lie on four workers (statistics of the 8-worker run: units 4137 / 4139 /
  4158 / 4145 on workers 7 / 0 / 4 / 6, checking phase 4.3 s, 8 in-unit give-backs, 52 units given back).
  The price: +1.2 % CPU time with 8 workers and +3.3 % with 4 (three runs each, ranges touching), and
  0.5 GB to 0.8 GB more peak RSS: three or four heavy units now hold their arenas at the same time and share
  the memory system, which is what running them in parallel means (a "lucky" cut of the claims before gave
  the same: the 6.26 s and 6.66 s runs). Kept: the wall-clock gain is ten times the CPU cost, and the CPU
  cost exists only while heavy units overlap.
- con-leche (one heavy unit), Init, Std: no difference beyond the noise in CPU time or wall clock, as
  expected: nothing there waits behind a slow unit that an idle worker could have taken.
- mathlib: single runs under this load differ by 10 % in wall clock in both directions (8 workers 89.4 ->
  81.8 s, 4 workers 77.3 -> 88.4 s): no result. Its heaviest unit takes about 1 s; the statistics of the
  after run show what the load did (each worker 29 s to 32 s on a CPU during 66 s of checking: the
  container's cores were shared; the machine's owners measured 42.3 s wall, of which 26.6 s checking, at
  13:30Z).
- Pipelined loading with the machine: with 4 workers 68.1 s against 77.3 s / 88.4 s batch; with 8 workers
  80.0 s against 81.8 s: there the loader, the environment thread and eight workers share eight (contended)
  cores and the load itself takes 54 s instead of 15.5 s (the environment thread waited for the loader 196
  times). It confirms the rule of ARCHITECTURE 6.8 for pipelined runs (leave CPUs for the loader); the
  size of the gain needs a quiet host. Peak RSS +0.4 GB to +0.6 GB.

#### Worker count with the machine (`--threads auto`)

`rlvm-driver/bin/threads-gate.sh --dist work/rlvm-driver/w7/dist-native --native --engine machine -X
machine.switches=warm,gated,expect work/exports/mathlib.ndjson` (one hold of the lock, 14:22Z to 14:31Z, **load
average 180 to 240**; raw data `work/rlvm-driver/gate/20261124T135329Z-w7-machine-step1.jsonl`; machine jar
f82074b207f1 of 13:39Z):

| workers | CPU | wall | peak RSS | instructions | CPU vs 1 worker | instructions vs 1 |
|--:|--:|--:|--:|--:|--:|--:|
| 1 | 231.8 s | 233.0 s | 2,671 MB | 2,908.4 G | | |
| 2 | 247.9 s | 133.3 s | 2,656 MB | 2,986.0 G | +6.9 % | +2.7 % |
| 4 | 241.6 s | 79.3 s | 2,578 MB | 2,987.3 G | +4.2 % | +2.7 % |
| 6 | 248.9 s | 73.6 s | 2,791 MB | 2,988.8 G | +7.4 % | +2.8 % |
| 8 | 267.4 s | 82.7 s | 2,828 MB | 2,993.2 G | +15.3 % | +2.9 % |

What can be read from it and what cannot. Independent of the load: a run with several workers executes 2.7 %
to 2.9 % more instructions than a run with one, **whatever their number** (2 workers +2.7 %, 8 workers
+2.9 %): the price is that of the multi-worker mode itself (runs and steals instead of one pass in file
order: more sessions that step back in rank or start cold), not a price per worker. Dependent on the load:
the CPU time per instruction rises with the worker count (+15 % CPU for 8 workers at +2.9 % instructions) and
8 workers are slower in wall clock than 6 (82.7 s against 73.6 s): the container's eight cores were shared
with other jobs during these runs (the machine's owners measured the same corpus with 8 workers at 42.3 s
wall and 206 s CPU at 13:30Z, load average below 20, without the switches). **So this series does not decide
the default**: read literally, the 5 % gate gives 4 (the arena run line's `--threads 4`); the instruction
counts say that 6 or 8 workers cost nothing beyond what 2 cost. `Run.autoWorkers` is unchanged (6 on 8 CPUs).
The gate is one command and about ten minutes of lock time; it has to be repeated on a quiet host, and the
native image is the platform to do it on (no compiler threads, so the two CPUs that ARCHITECTURE 6.8 leaves
free are needed only by a pipelined loader).

The same gate with the reference engine on mathlib (the confirmation asked for since wave 4: about 40 minutes
of lock time with the native image, 18 of them for the one-worker run) was **not run**: between 13:50Z and
14:50Z the measurement lock had 12 to 14 waiters and a wait of 25 to 30 minutes per turn.

#### Memory with the machine (mathlib, 8 workers)

Peak RSS 2,828 MB (the row above; 2,855 MiB in the machine owners' run of 13:30Z, no switch), **under the
4 GB target**; with the reference engine it was 7.15 GiB. The account of the owners' run
(`work/m6/stats/mathlib-v0-g.json`): shared store 2.12 GB at the start of the check, peak accounted 2.45 GB,
of which sessions and lanes of all nine checking threads 0.33 GB at their peak (reference engine: 2.5 GB to
3.1 GB). What is left on the driver's side: nothing that shows. Of the 0.33 GB, 0.15 GB is the initial
capacity of nine worker contexts (16 MiB of overlay each, `maxSlackBytes` 16.8 MB per worker; the wiring's
and the store's default, not the driver's); the driver's own structures are the verdict board (one status
word per unit) and the scheduler's runs. Thread stacks are reservations (1 GiB each, pages committed when
touched). The give-back rule did not fire once (`giveBacks` 0) and no unit needed the heavy gate.

#### Invariance and guards with the machine (final tree, driver jar 50adc1f555bd)

| Guard | Result |
|---|---|
| `$MVN -o -q -pl rlvm-driver test` | 101 tests, 98 run green, 3 opt-in (final jar c0aeb3288f46 = the jar of the rows below, 50adc1f555bd, plus `passes`) |
| `rlvm-driver/bin/invariance.sh --engine machine -X machine.switches=warm,gated,expect tutorial bugs mdata` (machine jar f82074b207f1 as primary engine; every file 9 times: batch with 1 / 2 / 4 / 8 workers, 1-byte session caps with 1 and 4 workers, pipelined with a publication every 1 / 7 / 64 units and 1 / 4 / 8 workers) | PASS: 16,928 files, 0 wrong, 0 schedule differences, 1,675 declined alike in every mode (413 + 65 + 1,197, the same files as with the reference engine), 5 pipelined declines (REORDER_DECL mutants), 0 MACHINE-INCOMPLETE, 0 WARM-ANOMALY |
| the same, `published` (231 files) and `big` (init-prelude, Init, Std, cedar) | published: 230 of 231 files correct in all 9 configurations, 0 schedule differences, 0 pipelined declines (193 in five shards; the sixth died with `OutOfMemoryError` in `Machine.growRecords` in the suite's 6 GB JVM on `perf/loop-unfolded-once`, where the machine grows to its own limit before it declines, and its other 37 files were repeated: all equal). `perf/loop-unfolded-once` alone with the launcher's heap (`-Xmx13g`): accepted in the first configuration (machine declines, reference kernel decides; 250 s on this host); the other eight configurations were not finished when the session ended. big: 4 files, all correct in all 9 configurations |
| `rlvm-driver/bin/invariance.sh tutorial bugs mdata` (reference engine as primary) | PASS: 16,928 files, 0 wrong, 0 schedule differences, 1,675 declined alike, 0 pipelined declines |
| `scripts/gate.sh --dist work/rlvm-driver/w7/dist-gate --corpus tutorial --corpus bugs --corpus mdata --corpus prelude` (the published jars of 13:46Z with this driver jar; JVM launcher, reference engine, 1 worker, batch) | PASS in every step: 230 / 230 published tests correct, 0 wrong, 0 declined; mutants tutorial 12,260 (agree 11,847, declined 413), bugs 2,692 (2,627 / 65), mdata 1,976 (779 / 1,197), prelude 847 (839 / 8): SOUNDNESS 0, COMPLETENESS 0, errors 0 in all four, the same counts as the wave-5 check; differential self-test caught. (The script was edited by its owner while the gate ran and stopped with a syntax error after the fourth corpus; the self-test was then run by `--no-slice --corpus none`.) |

### Wave 8: where the wall clock of 8 workers goes (2026-11-24, 15:30Z to 18:30Z; agent P7)

The full report is *agentic-note*: rep-P7; this is what concerns the driver. All rows: machine engine
`warm,gated,expect`, cap 4 MiB, CE native image `-O3`, **every wall-clock number through `bench/quiet.py`** (the
8 performance cores reserved; load average of the host 45 - 140, of which about 3 % - 7 % still reach the
measurement cores as run-queue time; single runs unless stated; differences below 3 % are not results; the same
binary gave 34.7 s and 38.1 s for mathlib pipelined with 8 workers in two windows). Distributions that differ
only in the driver jar (`work/p7/dist-b` .. `dist-e`: the published jars of 15:31Z + one driver jar each).

**Mathlib, wall clock (s), driver c0aeb3288f46 + time account:**

| workers | batch | of it load | check | pipelined | check-only pass 2 |
|--:|--:|--:|--:|--:|--:|
| 1 | 224.05 | 11.65 | 212.21 | 208.34 | |
| 2 | 113.40 | 11.77 | 101.39 | 104.89 | |
| 4 | 64.58 | 11.92 | 52.66 | 55.49 | 53.28 |
| 8 | 42.63 (41.45) | 12.06 | 30.57 (28.86) | 38.06 (34.71) | 28.74 (28.42) |

(in parentheses: the same binary one hour later, on a quieter host.) The check phase scales 6.9x - 7.4x from 8
workers; the whole pipelined run 5.5x - 6.0x against one worker pipelined.

**Where 8 workers x check wall goes (mathlib):**

| | batch (30.57 s) | batch, quieter host (28.86 s) | pipelined (37.61 s) | pipelined, quieter host (34.34 s) |
|---|--:|--:|--:|--:|
| on a CPU | 87.0 % | 91.0 % | 74.5 % | 80.9 % |
| runnable without a CPU | 7.5 % | 3.0 % | 13.6 % | 6.7 % |
| stopped: collections, page faults | 4.4 % | 5.2 % | 11.6 % | 12.2 % |
| parked: nothing announced | 0.7 % | 0.7 % | 0.1 % | 0.2 % |
| parked: tail | 0.4 % | 0.0 % | 0.3 % | 0.1 % |
| heavy gate, end of the load | 0 | 0 | 0 | 0 |

So the driver's own waits (frontier, tail, gate) are 1 % of the wall clock on mathlib; the serial part is the
collector (1.06 s of pauses per check pass whatever the worker count, 3.9 s in a pipelined run; the garbage
is the engines' and the store's: session arrays grown again after each of 14,000 resets per pass, reported as
P7-F1) and, while the file loads, ten threads on eight cores. No unit and no chain of units bounds mathlib
(slowest unit 2.15 s; the heaviest holds 0.2 % of the steps).

**What the driver changed, before / after:**

| Change | Std | cedar | mathlib |
|---|---|---|---|
| Shared bytes added up at most every 20 ms by the environment thread (was: after each of its steps; `Store.sharedBytes()` walks one slot per unit) | none (small tables) | none | environment thread 4.69 s CPU / 5.13 s wall -> 1.56 s / 1.67 s; frontier complete 3.5 s earlier; parks at the frontier 0.7 % -> 0.0 %; wall clock inside the noise (expected 0.4 s of 29 s) |
| Slices instead of one run per worker and batch | check-only passes with 8 workers 2.15 - 2.18 s -> 1.58 - 1.82 s, with 4 workers 3.37 - 3.53 s -> 2.92 - 3.14 s; pass 1 and pipelined: see the ten rounds below | no difference (2.2 s; no dominant unit) | no difference (41.4 / 28.4 / 34.7 s before, 41.5 / 27.9 / 35.9 s after: batch / check-only / pipelined, one window); steals 286 -> 3 |
| Kept pass + parking rule | pipelined declines of the invariance suite 5 -> 0 (machine), 0 (reference) | | 0 units parked on Init, Std, cedar, mathlib |
| **Publication interval times the input's GiB, at most 4** (`publish-scale`) | same interval, same time (2.04 / 2.04 s pipelined with 8 workers) | same interval (3.00 / 2.77 s: noise) | **pipelined, 8 workers, two interleaved rounds: 34.25 / 35.00 s -> 31.71 / 31.90 s (-8 %)**; 4 workers 55.71 -> 53.80 s (-3 %). 205 -> 52 publications, 36.9 -> 20.9 GB of garbage, pauses 3.75 -> 2.23 s, worker CPU 221.3 -> 214.8 s, peak RSS 4.1 -> 3.8 GB |

Std, 8 workers, ten rounds in one window (17:31Z): a split bounded to the environment batch but still one run
per worker (`dist-d`) against slices (`dist-e`): pass 1 check, median (range) 1.67 s (1.62 - 1.76) against
1.68 s (1.65 - 1.72); check-only passes (20 each) 1.63 s (1.59 - 1.70) against 1.67 s (1.64 - 2.03, two above
1.9); pipelined wall (5 each) 2.03 s (1.95 - 2.07) against 1.99 s (1.96 - 2.01). **So slices and a bounded
split are the same within the noise; what costs is cutting everything that is ready into W contiguous parts**
(the check-only passes of the former scheme: 2.15 - 2.18 s in four samples, -23 % now). In pass 1 the former
scheme gave 2.19 / 2.33 / 2.40 s in three of nineteen samples (all in one window, 16:52Z - 16:55Z; the others
1.62 - 1.85 s) and slices nothing above 1.72 s in twelve: too few to call it an effect. Slices are the
default because they need no bound to be right (a worker never holds units that somebody else could take
while it has not reached them), steals fall to nearly zero (mathlib: 286 -> 3) and fewer units lie beyond a
lane's snapshot in a pipelined run.

**Hardware ceiling** (`shard=K/8`): eight one-worker processes over the eight shards of mathlib, each pinned to one measurement core, all at once
(check-only passes): 23.7 s to 31.2 s per shard, mean 26.7 s, 211 - 213 s of CPU in total; the same shard alone
on its core is 5 % - 7 % faster (23.89 s against 25.14 s, 22.21 s against 23.71 s). One process with 8 workers:
27.9 - 28.7 s for 210 - 213 s of worker CPU. So one process costs no more CPU than eight, eight busy cores make
each other 5 % - 7 % slower (the hardware's: the worker CPU of 201 s with 2 workers and 213 s with 8), and the
driver's 8-worker pass is 5 % - 8 % above what a perfectly balanced split could reach on this machine (26.6 s);
that rest is the collections. A static split would end with its largest shard, after 31.2 s. Std: shards
1.13 - 2.21 s, mean 1.48 s, against 1.63 - 1.67 s for one process (the heavy unit); cedar: 1.66 - 2.77 s, mean
2.02 s, against 2.15 - 2.20 s; a shard alone is 4.5 % - 5.1 % faster on both.

**After the changes, mathlib with 8 workers** (jar 31fa08d9ce61): pipelined 31.7 - 31.9 s (on a CPU 85.6 %,
run queue 6.2 %, collections 7.8 %, parks 0.3 % of 8 workers x wall), batch 41.5 s (check 29.9 s), check-only
pass 27.9 s; against one worker pipelined (208.3 s) that is 6.5x, against one worker batch 7.0x.

**Guards.** Two publications: 150aed4bf1bf (18:03:49Z: everything but the publication scale) and
31fa08d9ce61 (18:27:41Z: + the publication scale, which changes nothing for inputs below 2 GiB).

| Guard | Result |
|---|---|
| `$MVN -o -q -pl rlvm-driver test` | 107 tests, 104 run green, 3 opt-in (150aed4bf1bf: 106 / 103) |
| `scripts/gate.sh --dist work/p7/dist-pre --corpus tutorial` (the tree before kept engines, jar 44cd88517795 + the published jars of 17:09Z; JVM launcher, reference engine, 1 worker, batch) | PASS: 230 / 230 published tests correct, 0 wrong, 0 declined; mutants tutorial 12,260: SOUNDNESS 0, COMPLETENESS 0, agree 11,847, declined 413, errors 0; differential self-test caught |
| the same with `--load pipelined --threads 4` and `RLVM_DRIVER=pipeline-min-mib=0,publish-units=7` (small files through the pipeline too) on `work/p7/dist-pre-pipe` | PASS: 230 / 230; mutants tutorial 12,260: SOUNDNESS 0, COMPLETENESS 0, agree 11,847, declined 413 (the same counts as batch); self-test caught |
| `scripts/gate.sh --corpus none` (slice + self-test) on `work/p7/dist-final` (tree with kept engines, 09668cada690) and on `work/p7/dist-pub` (the published 150aed4bf1bf itself) | PASS, 230 / 230, both |
| `rlvm-driver/bin/invariance.sh published` (231 files x 9 runs; reference engine; the code of 150aed4bf1bf) | PASS: 231 correct, 0 wrong, 0 declined, 0 schedule differences, 0 pipelined declines (28 minutes) |
| `rlvm-driver/bin/invariance.sh tutorial bugs mdata` (16,928 mutants x 9 runs), reference engine, on the code of 31fa08d9ce61 | PASS: 0 wrong, 0 schedule differences, 1,675 declined alike (413 + 65 + 1,197), 0 pipelined declines |
| the same with `--engine machine -X machine.switches=warm,gated,expect` (the machine jar published at 18:11Z as primary engine), on the code of 31fa08d9ce61 | PASS: 0 wrong, 0 schedule differences, 1,675 declined alike, **0 pipelined declines** (during the work: 5 before the kept pass and the parking rule, 1 while the rule covered proof blocks only) |
| the same, `big` (init-prelude, Init, Std, cedar), machine as primary engine (the code of 150aed4bf1bf) | PASS: 4 files correct in all 9 configurations |
| mathlib through the native launcher, 8 and 4 workers, batch and pipelined, every build of this section | accept, exit 0, 0 units parked |
| slice + self-test on a distribution with 31fa08d9ce61 (`work/p7/dist-pub2`), batch and pipelined forced | PASS both: 230 / 230, 0 wrong, 0 declined; differential self-test caught |
<!-- measured:end -->

## Deviations from the design documents

| Document says | This module does | Why |
|---|---|---|
| ARCH 6.3: one global claim front | a common front handed out in slices of up to 256 units per worker (wave 8), chunks of 32 inside a slice, stealing at the end; `slice=0`: per-worker contiguous runs per batch | RECONCILIATION section 3 (C2.4) asked for contiguous runs per worker; one run per worker and batch let a heavy unit start late (see "Scheduler"), and a slice is longer than the 75 units a session lives |
| ARCH 6.5: warm budget 5 x 10^7 steps | no step limit warm (`warmSteps`), no depth limit warm (`warmDepth`) | RECONCILIATION section 3 (C2.5); a depth limit only made deep units run twice, workers have deep stacks |
| ARCH 6.5: warm byte budget 1 GiB | the whole headroom, `memoryLimit - shared` (at least 256 MiB) | any smaller budget made units run twice (measured); the account's victim rule and the heavy gate bound the run instead |
| ARCH 6.4: `sessionBytes` 32 MiB, footprint = session + context | per engine: 4 MiB for the machine, 16 MiB otherwise; `Session.footprintBytes()` alone; the context's unused capacity is a separate trigger (`keepBytes`) | RECONCILIATION section 3 (C1.6): footprint = bytes in use, counted once; "swept by CPU time once the machine runs Std": done, see "Measured" |
| ARCH 6.7: above the limit `memoryExhausted()` is true (for everybody) | true for the largest holder only; for everybody above limit + margin | everybody giving up at once threw away the work of all workers and made the re-checks compete again |
| ARCH 6.1 / 6.5: heavy-lane threads (0-2) with deep stacks | re-checks run at once on the worker's own thread, which has a deep stack; memory retries one at a time through `HeavyGate` | a deferred re-check lets units behind an ill-typed unit run on (possibly forever, there is no step limit); a second thread only adds a hand-off. Costs W stack reservations of 1 GiB (virtual) |
| ARCH 0.6 / 6.6: "REJECT iff some rejection was confirmed" | status of the first non-accepted unit in file order | RECONCILIATION section 2 (C3.13) |
| ARCH 6.6: `Error` "not handled" | recorded, run cancelled, threads joined, then rethrown by `execute()` | the calling thread would otherwise wait for workers parked in the scheduler; the launcher still sees the `Error` |
| ARCH 8.1: status column of `DeclTable` is the unit status | `VerdictBoard` keeps its own column; `StoreHost.unitDecided` mirrors final statuses into `DeclTable` best effort | the verdict must not depend on who else writes that column (the environment pass does) |
| Skeleton `Run(RunConfig, EngineFactory, EngineFactory)` | needs a `Wiring` | the driver may not depend on `rlvm-export` / `rlvm-level` / `rlvm-nat` (agreed with rlvm-main) |
| `RunConfig.loadMode` default PIPELINED (ARCH 3.5: "the arena default") | BATCH | see "Open" |
| ARCH 3.5: a worker takes "the newest snapshot" when a unit lies beyond its own | the same, plus: a decline obtained while the file was loading is decided again after the load | `rlvm-admit` cannot tell a forward reference from an unknown constant before the file is complete |
| ARCH 6.8: `workers = max(1, min(processors, 8) - 2)`, W = 6 whatever the input | `max(1, min(processors - 2, 6))`; a batch run uses at most `units / 128` workers | the same number on 8 CPUs; on a larger machine still 6 (the gate is measured on 8 CPUs only); threads and sessions for a ten-unit test cost more than the check |

## Open, and things to know

- **Pipelined loading is still not the default, but nothing of the driver stands in the way any more**
  (wave 8). The three conditions of wave 7: (1) a pipelined run decides what the batch run decides: done by
  the kept pass and the parking rule (invariance suite: 0 pipelined declines with both engines; published
  tests 230 / 230 through the launcher with `--load pipelined --threads 4` and
  `RLVM_DRIVER=pipeline-min-mib=0,publish-units=7`, which forces the small files through the pipeline);
  (2) the end-to-end guards in that mode: see "Measured", wave 8; (3) the gain on quiet cores: mathlib 224.1
  -> 208.3 s with one worker, 113.4 -> 104.9 with 2, 64.6 -> 53.8 with 4, 41.5 -> 31.8 with 8;
  Std 2.71 -> 2.57 s, cedar 4.29 -> 2.78 s with 8. The switch itself is `rlvm-main`'s (`MainOptions.loadMode`;
  requested on the board); the driver already loads inputs below `pipeline-min-mib` (8 MiB) in batch mode.
- **A heavy unit that starts late is the end of the run** on the mid-size corpora (Std: unit 23249, 0.7 s of
  a 1.5 s run with 8 workers). Slices make all workers reach it together, which removes the bad cases of the
  old scheme, but file order still starts it at about half of the run. Starting large units first would
  need (a) a size known before the unit runs: `RunHost.unitWeight` (block nodes + shared terms) predicts
  well: on Std the two slowest units are the two largest, 9 of the 12 slowest are among the 40 largest,
  Spearman 0.83 against machine steps; (b) a scheduler rule that keeps the progress property ("the lowest
  undecided unit is always reached"): a worker that runs a unit ahead of lower unstarted ones may never get
  back to them, so at most W - 1 workers may be ahead, a worker that is ahead must own no run, and no run may
  be handed to it; the state-space test of `SchedulerTest` is the place to prove a rule. Expected: Std with 8
  workers pipelined 2.0 -> 1.7 s, nothing on mathlib (tail 0.3 %).
- **Stealing from the tail of a busy claim** was measured and is not worth its price: the tail (workers
  parked after the last announcement while others hold claims) is 0.0 % - 0.4 % of 8 workers x wall on mathlib
  and, on Std, is one unit, not a claim.
- **Collections**: the driver allocates nothing per unit, but its session resets are what makes the engines
  and the store's overlay grow their arrays again (P7-F1 in requests.log, 16:37Z). If the owners keep those
  arrays over a reset, the session cap should be swept again (`work/rlvm-driver/w7/sweep-cap2.sh`): part of
  what made 16 MiB slow may have been this.
- **Default worker count**: see "Measured". The arena run line names `--threads 4`; the steward decides.
- **Peak resident set**: 6.8 GB with the native image on mathlib (was 9.4 GB), not the 6 GB asked for (see
  "Where mathlib's memory goes"). Left to do, in the order of what they would bring: an overlay that grows
  without copying and doubling (the store: a heavy unit holds 1.3 times what it uses and leaves as much
  garbage behind; a unit of 2.5 GB needs about 7.5 GB of heap while its node array doubles); the heavy gate
  from a size threshold on, so that two large units never run at the same time (the driver: `LaneSignals`
  would take the gate in place when a warm unit passes, say, 512 MiB, and give way to a lower unit as the
  gate's holders do today; it needs the full guards and a quiet machine to see what the parked worker
  costs); the reference kernel's tables, which are reallocated per unit; and, for the JVM launcher, a heap
  setting or `gc-after-mib`, because its resident set is the heap size whatever the run holds.
- **A unit that grows without bound** ends the process with `OutOfMemoryError` (launcher status 3) before
  the account sees it: the overlay doubles its node array, so the last step from 4 GiB to 8 GiB needs
  12 GiB of heap at once. Capping a lane's overlay at a third of the heap would turn that into a decline; not
  done here (the store's growth policy is the store owner's).
- **Give-back waits for 8 units**: a worker that draws fewer than 8 more units after a heavy one, or a
  long unit right after it, keeps the capacity that long (con-leche: unit 24018 is the last one its worker
  checks, 2.7 GB stay allocated to the end). An immediate give-back for very large lanes would be the
  refinement.
- **Session cap**: per engine since wave 7 (machine 4 MiB by the sweep in "Measured", others 16 MiB; without
  effect with the reference engine, whose sessions keep nothing from one unit to the next). The table is keyed
  by `Engine.name()` because `rlvm.api.Engine` cannot state a cap; `EngineProvider.runDefaults` (rlvm-api
  13:34Z) lets a provider state `session-mib` through the wiring, which then wins. The sweep was made with the
  machine jar of 12:47Z; the best cap follows the size of the machine's tables and has to be swept again when
  those change (one command: `work/rlvm-driver/w7/sweep-cap2.sh`).
- **Batch splitting**: workers start as soon as the first units are announced, so the first batches of a
  file are small, and a worker's runs are `envBatchUnits / W` units long. Whether longer contiguous runs save
  CPU time is a question for an engine with warm sessions (`env-batch=N`).
- `Engine.environmentAdvanced` is called only by the environment thread, not in batch `-j 1` runs.
- With primary == reference a rejected unit is checked twice (warm, then the confirming fresh run): the
  failing unit of a `perf/*-bad` test costs double. In a pipelined run a unit that fails while the file is
  loading is checked once more after the load.
- Parked threads (heavy gate, loaded frontier) wake every 50 ms to look at the first failure; a wake-up
  hook on the board would make that unnecessary.
- The machine as primary engine is exercised by the invariance suite (`invariance.sh --engine machine`) and by
  the wave-7 measurements, not by the unit tests (fakes play that role there; `no-fallback` is tested with
  fakes only; `rlvm-machine` cannot be a test dependency of this module: reactor cycle through `rlvm-testkit`).
- Not implemented and not tested: namespace order, heavy-lane threads, native-image specifics (the code uses no
  reflection, no `java.management`, no `ThreadLocal`; `RunStats` reads `/proc/thread-self/schedstat`;
  `gc-after-mib` relies on `Runtime.totalMemory() - freeMemory()` and `System.gc()`).
