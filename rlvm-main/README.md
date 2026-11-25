# rlvm-main — the kernel as a plug-in of the launcher (wiring)

Package `rlvm.main`. No kernel logic: it turns the launcher's options into a `RunConfig`, finds the
engines, supplies the driver with what the driver cannot construct (store, load, worker contexts) and
maps the driver's outcome to the launcher's `Verdict`. Nothing depends on this module.

```
. ./env.sh && $MVN -o -q -pl rlvm-main test                       # unit tests
$MVN -o -q -pl rlvm-main test -Drlvm.e2e=tutorial                 # + the 141 tutorial tests in-process (steward's gate)
scripts/dev.sh dist && scripts/dev.sh run FILE.ndjson -- --stats  # the same through the distribution
scripts/dev.sh slice                                              # table for module owners: work/status/slice.md
```

## Classes

| Class | Role |
|---|---|
| `RlvmChecker implements rlvm.cli.Checker` | name `rlvm`, priority 100 (the launcher's default); registered in `META-INF/services/rlvm.cli.Checker`. `check(Path, Options)`, plus the skeleton's `checkExport(...)` and `configure(...)` |
| `MainOptions` | the `-X KEY[=VALUE]` settings (table below); an unknown or malformed one is a `UsageError`, never ignored |
| `Engines` | `ServiceLoader.load(EngineProvider.class)`: `primary(name)` (never the differential harness by default), `reference()`, `names(...)`, `optionKeys(...)`, `factory(provider, settings)`, `underTest(...)` (the engine `-X engine=diff` compares with the reference kernel) |
| `MainWiring implements rlvm.driver.Wiring` | `newStore` (`new Store(bytes, baseLists, baseLevelInfo)`), `load`, `loadReport`, `newWorkerContext`, on top of `rlvm.api.bind.Bindings` and the loader's own glue (`rlvm.load.ExportLoader`, `LevelStoreInterner`, `NatStoreDecoder`) |
| `Pipeline` | the one place that constructs `rlvm.driver.Run` (`Run(config, wiring, primary, reference)`, diagnostics to the launcher's stderr) |
| `UsageError` | an `IllegalArgumentException` for bad settings |
| `Passes`, `ProcessClock` | `-X passes=N` with N > 1 (warm passes, owner note 12): reload passes are made here (a complete run each, `System.gc()` between passes outside the timed region), recheck passes are the driver's `RunConfig.passes`; one line per pass, the `passes` table of the statistics, the verdict rule (section "Passes" below). Never runs for a plain check |

## Settings (`-X`, ARCHITECTURE 7.4)

| Key | Meaning | Default |
|---|---|---|
| `policy=practical\|strict` | what the run promises (owner note 13; section "Policy" below). `strict` = the reference engine plus every exactness-only option the jars of the run have; `practical` = the default engine by priority and none of them | `practical` |
| `engine=NAME` | `reference`, `machine`, or any registered provider name | the non-reference engine with the highest priority **of at least 100** (`Engines.DEFAULT_ENGINE_MIN_PRIORITY`); the reference kernel if there is none. An engine with a lower priority (the machine registers with 10 until it has passed its gates) is in the distribution as a candidate: it runs when named or compared, never by default |
| `engine=diff` | differential run. With the test kit's provider `diff` on the class path (`scripts/dev.sh diff`): its `DiffEngine` is the primary engine (engine under test and oracle per unit; the driver's own differential mode stays off). Without it: the best non-reference engine is the primary engine and the driver runs the reference kernel beside it on every unit (`RunConfig.differential`); a usage error (64) if the reference kernel is the only engine | - |
| `load=batch\|pipelined\|auto` | how units are published. `pipelined`: the store is built on the loader's `LevelChain`, the driver loads on the calling thread and checks what is published meanwhile; inputs below the driver's `pipeline-min-mib` (8 MiB) are loaded in one piece | `auto` (since 2026-11-24, owner note 12): **`pipelined` when two or more workers run and the input is a file of at least `pipeline-min-mib`** and no `stop-after` phase is asked for, else `batch` (`MainOptions.resolveLoadDefaults`; section "Load defaults and the automatic worker count"). It was `batch` |
| `order=file\|namespace` | order of ready units | the driver's (`file`) |
| `session-mib=N`, `memory-mib=N` | session reset threshold, memory account limit | the driver's, unless the primary engine prefers another (below) |
| `scan-threads=K\|auto` | scanner threads of the reader: they tokenise and pack file ranges (rlvm-load "packed runs") and run only on mapped input | `auto`: **1 for inputs of 256 MiB and more**, none below (`MainOptions.SCAN_THREADS_MIN_BYTES`, `DEFAULT_SCAN_THREADS`; 1 since 2026-11-25, it was 2: beside eight workers on eight cores one scanner thread is as fast or faster on every corpus with the machine, journal `dec-EGMG`) |
| `io=buffered\|mapped\|auto` | how the reader brings the file into memory | `auto` (mapped if a regular file) when scanner threads run, whoever asked for them; `buffered` (plain `read`; ARCHITECTURE 1.4) otherwise. It was `buffered` |
| `stop-after=load\|env` | stop after a phase | - |
| `stats-file=F` | statistics as JSON | - |
| `no-recheck`, `no-truffle`, `no-proof-blocks` | flags | off |
| `profile=4.36\|4.37` | official kernel release to imitate | 4.36 |
| `passes=N` (1..1000) | check the export N times in this process and report every pass (section "Passes") | 1 = a plain run |
| `passes-mode=reload\|recheck` | what a later pass is: `reload` = a complete run (new store, loader, engines); `recheck` = the driver's check-only pass over the loaded store. No effect with `passes=1` | `reload` |
| `engine.KEY=VALUE` | Truffle engine options | handed to the engines with the engine settings; no engine reads them today |
| any `KEY[=VALUE]` an engine declares | **engine settings**: every key some `EngineProvider` on the class path returns from `optionKeys()` is accepted and handed to `EngineProvider.factory(Map)` of the run's primary and reference engines (the reference factory also makes the environment pass's and the re-checks' kernels). The engine parses the value; `IllegalArgumentException` is a usage error. Example: the reference kernel's `levels=...` once `rlvm-ref` declares it | the engine's |

| any `KEY[=VALUE]` the driver knows | **driver settings** (`RunConfig.knows(key)` / `config.set(key, value)`; today `unit-mib`, `keep-mib`, `gc-after-mib`, `chunk`, `env-batch`, `spill-ms`, `publish-units`, `publish-mib`, `pipeline-min-mib`, `no-fallback`; the list and the meaning are the driver's, `rlvm-driver/README.md`). A new knob of the driver needs no change here | the driver's |

**Run defaults of the primary engine** (wave 7; request of the machine integrator, a session cap of
4 MiB for the machine's warm sessions): `EngineProvider.runDefaults(Map engineOptions)` returns
`-X` settings of the wiring or the driver (for example `session-mib` -> `4`) that the engine prefers
when it is the PRIMARY engine of the run. `RlvmChecker` applies them as if typed before the command
line, so an explicit `-X KEY=VALUE` always wins (`RlvmChecker.withRunDefaults`, `Engines.runDefaults`);
`--stats` names the ones in effect (`engine-defaults={session-mib=4}`). A provider may not default
`engine`, `engine.*` or an engine's own key, and a default the checker refuses is the provider's
fault: both are an internal error (status 3), never a usage error and never a verdict. No provider
declares a default today (the reference kernel prefers nothing; the machine's provider is its
workflow's to change), so nothing moved for any run.

An unknown key, a malformed value, an engine name without a provider, a value an engine or the driver
refuses and `engine=diff` without a second engine all end the run with status **64** before the export
is opened. Order of ownership of a key: the wiring's own keys first, then the engines' declared keys,
then the driver's.

### Passes (`-X passes=N`, `-X passes-mode=reload|recheck`; owner note 12)

The goal is the wall clock of checking in steady state, so one process can check the same export N
times and report every pass. ONE option for both kinds of later pass (the key `passes` is the
wiring's; the driver's `RunConfig.passes` is set by this module for `recheck` only):

- **`reload` (default)**: every pass is a complete run - new settings and configuration, new engine
  factories, new `MainWiring` (store, loader, bindings), new `Run` (engines, threads, lanes,
  sessions). Only the process is warm (JIT-compiled code, sized heap, touched pages). Between two
  passes nothing of the previous one is reachable and `System.gc()` runs, outside the timed region:
  the heap does not grow with the number of passes (`heap_kb` of a pass line = what was reachable when
  it started). It is the default because it is the owner's metric: a warm pass that loads and checks,
  with the load pipelined when `-X load=pipelined` is given.
- **`recheck`**: pure checking in steady state = rlvm-driver's passes (`rlvm-driver/README.md`,
  "Passes"), to which `Passes` delegates: one `Run` with `RunConfig.passes = N`. Pass 1 is the run
  itself; if it accepts, each later pass checks every def / thm / axiom / opaque unit of the loaded
  store again with NEW engine instances, sessions, scheduler and board; inductive blocks and quot
  records keep their recorded outcome; nothing is loaded and nothing is collected between passes. So
  what an engine instance holds (the machine's shared code objects and facts) is rebuilt in every
  pass. If pass 1 does not accept, the driver makes no later pass; the remaining passes are then
  reload passes and one line says so.

Per pass, on stderr (not with `--quiet`), the same shape in both modes, -1 = not known in this mode:

```
rlvm: pass 2/5 mode=reload wall_ms=1234 cpu_ms=5678 gc_ms=12 gc_count=3 load_ms=400 check_ms=1100 units=57938 accepted=57938 workers=8 pipelined=1 heap_kb=6100 rss_kb=812345 peak_rss_kb=901234 settle_ms=35 verdict=ACCEPT
```

`wall_ms`: the pass (reload: from before its configuration is built to the return of `Run.execute()`;
recheck: the driver's `Run.passResults()`); `cpu_ms`: CPU time of the whole process in the pass
(`/proc/self/stat` utime + stime, 10 ms steps, so it works in the native image); `gc_ms`,
`gc_count`: collector time and collections in the pass (the runtime's collector beans; reload only);
`load_ms`: `Wiring.load` on the calling thread; `check_ms`: from the first worker context (the
checking threads have started) to the end of the run - pipelined, it overlaps `load_ms`; `units`: units
of the store; `accepted`: accepted or skipped on the board; `heap_kb`: heap in use at the start of the
pass (after the collection between passes); `rss_kb` / `peak_rss_kb`: resident set after the pass /
peak of the process so far; `settle_ms`: the collection before the pass. In recheck mode the driver
prints its own `rlvm: pass K of N: ...` lines as the passes end, and the lines above follow when the
run has returned.

Statistics: `-X stats-file=F` gets the values `passes.requested`, `passes.done`, `passes.mode`,
`passes.verdict` and the table `passes` (one row per pass: outcome, wallMs, cpuMs, gcMs, gcCount,
loadMs, checkMs, units, accepted, workers, pipelined, recheck, heapStartKb, rssKb, peakRssKb,
settleMs) on top of the last run's statistics; a reload pass K also writes `F.passK` exactly as a
plain run writes F. `--stats`: the checker's line ends with `passes=done/N passes-mode=...` and its
`wall_ms` is the sum.

Verdict: the exit status is pass 1's. A later pass with another verdict prints
`rlvm: PASSES DISAGREE: pass 1 ended with ..., pass K (...) with ...` (also with `--quiet`) and the run
DECLINES (exit 2); in reload mode the remaining passes are not made. With `passes` absent or 1
nothing of this runs: output, exit status and statistics are those of the plain run
(`PassesTest.onePassIsThePlainRun`). Tool: `bench/warm.sh` (bench/README.md, "Warm wall clock").

Launcher options: `--threads N` becomes `RunConfig.workers`; automatic (`--threads auto`, `0`, or no
option) is decided HERE since 2026-11-24 (`RlvmChecker.autoWorkers`: one worker per CPU, at most 8)
and passed on like an explicit count; the driver still caps it at one worker per 128 units, and its
own automatic rule (CPUs - 2, at most 6) is no longer consulted. The arena run line names its count;
`--stack-size` becomes `deepStackBytes` and caps `workerStackBytes`; `--quiet` turns `explain` off;
`--stats` prints one line (`outcome, engine, reference, policy, workers, load, scan-threads, io,
[engine-defaults,] wall_ms`; `(auto)` behind a value = decided by the defaults, not given) on stderr.
`rlvm --help` ends with this checker's settings (`RlvmChecker.usage()` through `rlvm.cli.Checker.usage()`),
`rlvm --version` names the default engine and ends with `default: ...`, what a run without options
does (`RlvmChecker.defaults()` through `rlvm.cli.Checker.defaults()`: the engine by priority, the
policy, the automatic worker count and the load rule, from the constants that decide them; an engine
adds its own defaults through `EngineProvider.defaultsNote()`).

## Policy (`-X policy=practical|strict`, owner note 13)

One option selects the policy; **practical is the default**. The contract of the default
configuration (OWNER-NOTES 13):

- (a) never accept a statement that is not derivable in Lean's type theory ("kind F"): absolute;
- (b) the official verdict on every real input: everything lean4export writes (the corpora), the
  arena's good and bad tests, the mutant corpora with their stored official verdicts;
- (c) everything the official checker accepts is accepted (a rejection is always confirmed by the
  reference engine; a unit the primary engine declines goes to it);
- (d) accepting a derivable statement that the official checker rejects only through its own
  incompleteness, non-transitivity, cache order or object identity ("kind T") is tolerated; the
  known classes are written down with one export each (`docs/design/review/official-divergence.md`).

| | `policy=practical` (default) | `policy=strict` |
|---|---|---|
| engine | the default engine by priority (`Engines.primary(null)`: the non-reference engine with priority >= 100, else the reference kernel; `rlvm --version` prints it as `default engine: NAME`), or the one `-X engine=NAME` names | **the reference engine**, whatever the priorities say. `-X engine=reference` with it is allowed; another engine or `engine=diff` with it is a usage error (64): the strict mode is one engine |
| engines' exactness-only settings | none is passed: each engine's own default (off) holds | set to the value of `MainOptions.STRICT_ENGINE_SETTINGS` when a provider on the class path declares the key and the command line did not set it: **`ref.exact=official`** (rlvm-ref, owner R2: where the official verdict depends on object identity the kernel probes the official second pass and declines the declaration if the two continuations can differ) |
| loader | metadata nodes around a term are transparent (the wrapped term stands for them) | **`LoadOptions.withStrictMetadata(true)`** (rlvm-load, owner LP2): a declaration whose type or value carries a metadata node is declined, per unit |
| the key `policy` for engines | an engine that declares the key `policy` in `optionKeys()` receives `policy=practical` | ... and `policy=strict`: a further engine can follow the policy without a change here |

"When the jars have it": both exactness options were being gated by their owners when this option
was published (22:20Z to 23Z on 2026-11-24). The reference kernel's key is found through
`EngineProvider.optionKeys()`, the loader's method by name (`MainWiring.strictLoad`, reflection on a
constant name, so that this module compiles and runs against a loader without it); with jars that
have neither, `policy=strict` is the reference engine alone. A strict run says what it had:
`--stats` prints `policy=strict[ref.exact=official,metadata]` (`[]` = none), a practical run
`policy=practical`. The JVM launcher's heap rule follows the policy (strict = the reference engine's
heap). Neither policy can turn a rejection into an acceptance of something underivable: strict only
ADDS declines.

**The safety net of the practical policy** (`RlvmChecker.strictRecheck`; contract (c); finding R2-F4,
`docs/design/review/reference-caches.md` 6.1). The practical loader drops metadata nodes; the official
kernel keeps them, and some of its syntactic tests stop at one. So a REJECT of a practical run on a
file in which the loader dropped a metadata node (`LoadReport.exprMData` > 0) is not reported: the
file is checked once more as a plain run under `policy=strict` (fresh stores; the command line
without `engine`, `passes`, `passes-mode` and the exactness-only engine settings), and that verdict
is the run's - accept, reject or decline. An ACCEPT, a DECLINE and every file without a metadata
record are untouched (no real input has one). The diagnostics say so (`.. NOT REPORTED ..`, the
practical explanation labelled `practical, not reported:`), and the `--stats` line of the reported
run ends with `net=strict-recheck(practical=reject,metadata=N,practical_wall_ms=T)` (the statistics
file: the value `net`). Price: a correct reject becomes a decline where the strict load declines a
declaration with a node before it reaches the rejected one. Test: `SafetyNetTest`.

Tests: `PolicyTest` (both values and the default; an unknown value, a bare `-X policy`, strict with
`engine=diff` or with an engine that is not the reference kernel: status 64 before anything is read;
strict selects the reference engine with a machine of priority 100 on the class path; the engine
settings each provider receives; through the real launcher an accepted and a rejected export under
both policies; the metadata decline where the loader has it; `rlvm --help` and `--version`).

## Load defaults and the automatic worker count (owner note 12)

The target configuration is parallel workers with the load pipelined; since 2026-11-24 that is what
a run does when the command line says nothing (requests P7-F4 and of the load path's owner; the
driver's side was gated by its owner: 0 pipelined-only declines over 16,928 mutants x 9 schedules).

| Left open on the command line | Decided by `MainOptions.resolveLoadDefaults` / `RlvmChecker.autoWorkers` |
|---|---|
| `-X load` | `pipelined` if workers >= 2 and the input is a regular file of at least the driver's `pipelineMinBytes` (`-X pipeline-min-mib`, 8 MiB) and there is no `stop-after`; else `batch`. These are exactly the runs in which the driver really pipelines, so a batch run keeps the store it always had (no level chain) |
| `-X scan-threads` | 1 if the input is at least 256 MiB (2 until 2026-11-25), else 0 - in either load mode |
| `-X io` | `auto` if scanner threads run (given or defaulted), else `buffered` |
| `--threads` | `min(CPUs, 8)`, at least 1 |

A given setting always wins and is never changed. Nothing changes for one worker, for an input below
8 MiB (every published test but the corpora; every mutant) or of unknown size (not a regular file).
`-X scan-threads=K` alone now gets mapped input (it used to do nothing without `-X io=mapped`).
Tests: `LoadDefaultsTest` (the rule as a table; the run configuration for sparse files of 8 MiB and
256 MiB; through the real launcher: a run with four workers and no `-X load` is pipelined for real,
gives the batch run's verdict for an accepted and a rejected export, and says `load=pipelined(auto)`).
Measurements and gates: `docs/PLATFORM.md`, sections 0.5 to 0.7.

## Invariants

- **Fail closed.** `Verdict.ACCEPT` is returned in one place, for the driver's exact `Outcome.ACCEPT`;
  any other number throws (launcher status 3). An `UnsupportedOperationException` from a part of the
  pipeline that is deferred or not yet published makes the run DECLINE with
  `rlvm: declined: not implemented: ...` on stderr; it is never a verdict about the input. `Error`s are
  not caught. No `System.exit`.
- No compile-time dependency on an engine: `rlvm-ref` is a `runtime`-scope dependency (it must be in
  every distribution and on this module's test class path); engines arrive as `EngineFactory`.
- Exactly one reference engine must be on the class path (`IllegalStateException` otherwise). When the
  reference kernel is the primary engine, the *same* `EngineFactory` object is passed to the driver as
  primary and as reference (the driver then confirms a rejection with one fresh cold run, not two).
- The driver prints the explanation line; this module adds a line only if a non-ACCEPT run has none.
- Batch load: the level base is frozen by `MainWiring.load` after the loader returned
  (`LevelStoreInterner(base, freezeOnPublish = false)`), together with the number base; worker contexts
  exist only after that.
- Pipelined load (`-X load=pipelined`): `newStore` creates the store on a `rlvm.load.LevelChain`
  (`new Store(bytes, chain.lists(), chain.info())`) and binds the factory to its snapshots
  (`Bindings.bindLevelSnapshots`); `load` hands the driver's `LoadOptions` to the loader unchanged; worker
  contexts are overlays of the newest snapshot and follow it at `WorkerContext.reset()`
  (`coveredUnits()` says how far a context reaches). The base is never frozen by this class then.
- **A load-level decline is per unit**: `load` always sets `LoadOptions.withPublishOnDecline(true)` and
  freezes the level base on the `UnsupportedExportException` path too, so the driver checks the units
  that were loaded completely before the record the loader cannot represent. A rejection among them is
  the verdict (the official checker rejects such a file as well); otherwise the run declines; the store
  stays incomplete, so it is never accepted (`LoadDeclineTest`).

## Deviations from the design documents, with reasons

- (Resolved 2026-11-24, wave 4.) An unknown `-X` key, a malformed value or an unknown engine name ends
  the run with launcher status **64**: `rlvm.cli.UsageException` is public and `RlvmChecker.check`
  throws it for every `UsageError` (all of them are raised while the run is configured, before the
  export is opened).
- The reader runs in `BUFFERED` mode by default instead of its own default (mapped + `madvise`), as
  ARCHITECTURE 1.4 asks; `-X io=mapped` selects the reader's measured-best mode.
- The loader's adapters live in `rlvm-load` (`LevelStoreInterner`, `NatStoreDecoder`, `ExportLoader`),
  not here; this module only passes them the run's base stores.

## Packaging and steward scripts (`scripts/`)

| Command | What it does |
|---|---|
| `scripts/dev.sh dist` | `build.sh --published --jvm-only`: `rlvm-main` compiled from the tree, every other RLVM module as its owner last published it in `.toolchain/m2`; `work/dist/bin/rlvm-jvm`; a few seconds. Since wave 7 the shared `work/dist` is **versioned**: every build is installed as `work/dist.d/<UTC time>-<jars sha256 prefix>-<jvm\|native>` and `work/dist` is a symbolic link switched to it by one `rename(2)`; `gate.sh`, `slice.sh`, `bench/track` and the platform matrix resolve the link once at their start, so a rebuild can no longer change the build under a running guard. Old versions are deleted when they are neither among the newest 4 nor younger than 90 minutes (`RLVM_DIST_KEEP`, `RLVM_DIST_MIN_AGE_MIN`). Copy it with `cp -aL` |
| `scripts/dev.sh dist-native` | `build.sh --published --dev`: the same plus the executable, the CE native image `work/dist/bin/rlvm` at **`-O3`** (wave 7: the default of `build.sh --opt`, the arena's flags; 2.5 min wall, about 900 s CPU on this host; `--opt 2` halves the build and costs 10-18 % CPU at run time, `work/status/machine-integration.md`) |
| `scripts/dev.sh dist-versions` | the versions in `work/dist.d/` and the one `work/dist` names |
| `scripts/dev.sh dist-tree` | `build.sh --dev`: a development distribution with everything compiled from the working tree. Plain `scripts/build.sh` (what `scripts/arena/build.sh` runs) builds THE RELEASE layout instead: one executable `bin/rlvm`, no jars, no JVM launcher (journal: `design-packaging`) |
| `scripts/dev.sh release`, `scripts/package.sh` | the release archive `rlvm-<version>-linux-<arch>.tar.gz` with exactly `rlvm`, `LICENSE`, `README.md`; `scripts/dev.sh release-oracle` / `profiles`: the OPTIONAL build with Oracle GraalVM (GFTC licence) and the profiles shipped in `profiles/`, and their regeneration. GraalVM CE is the only compiler of every default build |
| `scripts/dev.sh suite [--jvm]` | `bench/rlvm/suite.sh`: `bench/run-suite.py` over the 230 published tests with the distribution (uses the JVM launcher automatically when no native image is built) |
| `scripts/dev.sh slice [--all] [--native\|--jvm] [--probe]` | `scripts/slice.sh`: tutorial tests (or all 230), reference engine, one worker, batch load; writes `work/status/slice.md` / `slice-all.md` (summary, wrong verdicts, failures grouped by message with a guess at the owning module, every test); `--probe` runs the driverless probe instead (`slice-probe*.md`) |
| `scripts/dev.sh diff ARGS`, `oracle`, `judge`, `mutate`, `corpus`, `track` | pass-throughs to `rlvm-testkit/bin/testkit` and `bench/track/track.py`; `diff` is the test kit's differential campaign; `diff-launcher EXPORT` runs the JVM launcher once with the test kit's `diff` engine provider on `RLVM_EXTRA_CP` |
| `scripts/dev.sh gate [--dist DIR] [--corpus NAME\|all] [--load pipelined] [--engine NAME] [-X K=V] [--pin CPUS]` | `scripts/gate.sh` (wave 4): the end-to-end guards in one command - distribution, the 230 published tests through the JVM launcher, the mutant campaign(s) with `scripts/check-light.sh`, the differential self-test. Pinned to the efficiency cores by default so that guards do not spoil measurements (a pinned gate checks verdicts without the arena's per-test time limits). `--dist` guards a distribution built before publication: `scripts/build.sh --published --jvm-only --modules MODULE,rlvm-main --dist work/ME/dist-pre` |
| `scripts/slice.sh --dist DIR`, `--no-arena-timeouts` | the table on another distribution; a verdict run without the arena's time limits (for loaded hosts; the table says so in its header) |
| `scripts/dev.sh platform ...`, `platform-report`, `scripts/platform-all.sh STEP...` | the platform comparison of `docs/PLATFORM.md`: `scripts/platform-matrix.py` (configurations x corpora x worker counts x repeats in the arena-shaped container, under the measurement lock), the series as run, and the tables |
| `scripts/dev.sh machine ARGS` | pass-through to `scripts/machine-sandbox.sh` (the machine workflow's script) |
| `scripts/dev.sh steward-install MODULE...` | `scripts/steward-install.sh`: copies the module's sources, runs its suite on the copy, installs the jar built from that copy (`--if-changed` for refreshes) |

`work/dist/BUILD-INFO.txt` lists every RLVM jar of the distribution with its sha256 prefix and the time
its owner published it, and the distribution's default engine. Since 2026-11-24T22:41Z the JVM
launcher is the WARM platform (`docs/PLATFORM.md`, Part 0): `-XX:CICompilerCount=8
-XX:+UseTransparentHugePages`, VM messages on standard error, a sorted explicit class path, and the heap
BY ENGINE - the machine `-Xmx7g -Xmn1g`, every other engine `-Xms13g -Xmx13g -Xmn1g` (ARCHITECTURE 7.2),
where the engine is `-X engine=NAME`, else the distribution's default engine, and `-X policy=strict` is
the reference engine; an explicit `-Xmx` / `-Xms` / `-Xmn` replaces the heap flags. Image:
`-R:MaxHeapSize=13g`, the machine's H10 inline lists; `-R:MaxNewSize=1g` is not baked into the image
because a run-time `-Xmx` below 1g then stops the image at start-up with status 1. The test kit is
never part of a distribution (22 jars in a tree build and in a `--published` build alike). Since 11:18Z a
`--published` build also copies the published `rlvm-machine` jar into `lib/` (the machine workflow's
integrator added the module to the parent pom and that block to `scripts/build.sh`); the machine
registers with priority 10 and is therefore never the default engine (`Engines`).

## What runs today

The whole pipeline: `rlvm FILE` loads the export (`rlvm-export` reader, `rlvm-load`, `rlvm-store`), runs
the environment pass and admission (`rlvm-admit`, `rlvm-inductive`) and checks every unit with the
reference engine (`rlvm-ref`) under `rlvm-driver`, with 1..N workers. Results of 2026-11-24 (tables with
the exact jars: `work/status/slice.md`, `slice-all.md`; history in `work/status/steward.log`):

| run (jars as published by 06:05Z) | correct | wrong | declined | CPU (sum over tests) | wall (8 at a time) |
|---|---|---|---|---|---|
| tutorial, native image, `--threads 1` | 141 / 141 | 0 | 0 | below 10 ms per test | 0.4 s |
| tutorial, JVM launcher, `--threads 1` (jars of 05:49Z) | 141 / 141 | 0 | 0 | 28.6 s (0.2 s JVM start per test) | 3.2 s |
| all published tests, native image, `--threads 1` | 230 / 230 | 0 | 0 | 276 s | 65 s |
| all published tests, native image, arena run line (`--threads 4`, `scripts/dev.sh suite`) | 230 / 230 | 0 | 0 | 264 s | 70 s |

Every test ended within its arena timeout; the closest is `perf/app-lam` (26 s of 70 s; 4 GB RSS).
`scripts/dev.sh arena-test --reuse` (the arena definition built from the working tree in the
container, `-O3`) passes.

Larger corpora, native image, reference engine, batch load (all ACCEPTED):

| corpus | threads | CPU time | wall | peak RSS | instructions | where |
|---|---|---|---|---|---|---|
| Init (348 MB, 57,938 units) | 1 | 24.9 s | 24.9 s | 286 MB | 333.1 G | arena-shaped container (`scripts/dev.sh measure`) |
| Init | 8 | 24.8 s | 6.4 s | 581 MB | 331.7 G | container |
| Std (584 MB) | 1 | 54.4 s | 56.7 s | 438 MB | not measured | host, GNU time, loaded machine |
| Std | 8 | 63.7 s | 13.0 s | 703 MB | not measured | host |
| reachability (19 MB) | 8 | 16.5 s | 15.8 s | 1.09 GB | not measured | host |
| cedar (905 MB) | 8 | 77.8 s | 12.6 s | 790 MB | not measured | host |
| mathlib (6.3 GB, 739k constants) | 8 | 1,486.8 s | 363.3 s | 9.27 GB | 12,404 G | container, one run |

These are numbers of the *reference* engine (jars of 05:52Z), for scale only: gruszka needs 2.8 s CPU on
Init, 5.7 s on Std and 62.4 s CPU / 18.1 s wall / 2.53 GB on mathlib on this host. M3's bound for the
reference engine on Init is 300 G instructions (333 G measured).

init-prelude: 0.11 s CPU / 0.11 s wall / 33 MB with the native image, 0.97 s CPU / 0.43 s wall /
106 MB with the JVM launcher (one worker, host).

Tests of this module: unit tests of options, engine lookup and registration; `MainWiringTest` and
`MainWiringLoadTest` (all 230 files through reader + loader + store + worker contexts);
`SliceE2ETest` (opt-in, `-Drlvm.e2e=tutorial|all`: the published tests through the real launcher
in-process); `ProbeSliceTest` + `ProbeRun` (opt-in, `scripts/slice.sh --probe`): a driverless
sequential pipeline in the test tree - wiring load, one `EnvBuilder`, one `Admission`, two reference
sessions - used as an early integration check and as an independent cross-check of the driver.

## Integration results (integrator, 2026-11-24 07:35Z; supersede the tables above)

Full log, measurements and decline lists: `work/status/integration.md`. With the final jars
(`work/dist/BUILD-INFO.txt`), reference engine, batch load:

| run | correct | wrong | declined | where |
|---|---|---|---|---|
| tutorial, JVM launcher, `--threads 1` | 141 / 141 | 0 | 0 | host |
| all published tests, JVM launcher, `--threads 1` | 230 / 230 | 0 | 0 | host; 208 s CPU summed |
| all published tests, JVM launcher, arena run line (`--threads 4`) | 230 / 230 | 0 | 0 | arena-limits container; 182.5 s CPU, 1,210 G instructions, max RSS 6.0 GB |
| all published tests, native image, `--threads 1` | 230 / 230 | 0 | 0 | host; 189.3 s CPU |

Init in the arena-limits container: JVM 15.1 - 19.4 s CPU / 12.4 - 16.8 s wall / 1.11 GB / 209 G instructions
with one worker, 20.6 s CPU / 3.9 s wall with eight; native image 18.6 s CPU / 18.6 s wall / 310 MB /
286 G with one worker, 16.3 s CPU / 3.2 s wall with eight. mathlib, eight workers, same container: JVM
841.8 s CPU / 139.2 s wall / 13.1 GB / 6,374 G; native 1,027.6 s CPU / 178.0 s wall / 9.4 GB / 10,207 G.
Std, cedar, cslib, con-leche, reachability and navier-stokes-euler are accepted (JVM, eight workers, host).
Mutant corpora (16,949 mutants in four corpora): no soundness and no completeness difference.

## Wave 4 (steward, 2026-11-24; details in `work/status/rlvm-main.log` and `steward.log`)

With the jars published by 10:56Z (`work/dist/BUILD-INFO.txt`), reference engine:

| run | result |
|---|---|
| all published tests, JVM launcher, `--threads 1`, batch | 230 / 230 correct, 0 wrong, 0 declined (`work/status/slice-all.md`; a verdict run, see its header) |
| all published tests, `--threads auto` (the driver's count) | 230 / 230 (08:19Z jars) |
| all published tests, `-X load=pipelined` | 230 / 230 |
| Init, Std, reachability, `-X load=pipelined`, 1 and 6 workers | accepted |
| mutant corpora (17,775 in four corpora, `scripts/dev.sh gate --corpus all`) | SOUNDNESS 0, COMPLETENESS 0, agree 15,473, declined 2,302 (`work/status/mutants.md`) |
| `-X levels=official` on the two `either` corner cases | rejected, as the official checker (default `complete`: accepted) |
| `-X engine=diff` with the test kit's broken engine | the difference is caught (`scripts/dev.sh gate`, last step) |
| arena definition end to end (`scripts/dev.sh arena-test --reuse --strict`) | PASS (tree of 10:26Z) |

Platform comparison (JVM launcher, CE native image, Oracle PGO image; host JIT, collectors, heap):
`docs/PLATFORM.md`.

## State of 2026-11-25 (packaging owner; supersedes what the sections below say about defaults)

- The release is ONE executable (journal: `design-packaging`); `rlvm --version` ends with `default: ...`,
  this checker's own statement of what a run without options does (`RlvmChecker.defaults()`).
- One scanner thread instead of two for inputs of 256 MiB and more (`MainOptions.DEFAULT_SCAN_THREADS`;
  journal: decision `dec-EGMG`); the worker rule (one per CPU, at most 8) and the load rule (pipelined
  from 8 MiB) were measured again with the machine as engine and stay.

## State of 2026-11-24, 23Z (steward B2; the scanner-thread count below is superseded by the section above)

- Published: `rlvm-main` `21150203e39d` (54 tests: 51 run green, 3 opt-in), `rlvm-cli` `cac1433b287a` (49).
- Defaults of a run without options: load `auto` (pipelined for two or more workers on a file of 8 MiB or
  more; 2 scanner threads on mapped input from 256 MiB), `--threads auto` = one worker per CPU up to 8
  (decided here, not by the driver), policy `practical`, the default engine by priority (the reference
  engine today).
- Gate of that publication (`scripts/gate.sh --dist work/b2/dist-c --defaults --corpus all`, the
  distribution exactly as a user runs it): 230 / 230; 17,775 mutants SOUNDNESS 0 / COMPLETENESS 0 (agree
  16,092, declined 1,683); self-test caught; the driver's invariance suite on the published tests PASS
  with 0 pipelined declines; the arena's complete test set (270) with `--threads 8`: no wrong verdict;
  the eight corpora accepted; `scripts/dev.sh arena-test` PASS. Measurements: `docs/PLATFORM.md` 0.5, 0.6.

## Known gaps

- `-X order=namespace` ends as a decline (`UnsupportedOperationException` in the driver: deferred).
  `-X load=pipelined` runs end to end since the driver's jar of 09:39Z (guards in
  `work/status/rlvm-main.log`); batch was the default until 2026-11-24T23Z (now: `auto`, above). `-X engine=diff` was run end to end with the
  test kit's provider (reference against reference: tutorial tests and init-prelude); the driver's own
  differential mode has no second engine in the distribution yet.
- `engine.*` options are parsed and handed to the engines, none of which reads them; `-X stats-file`
  is passed to the driver, which owns the statistics (not checked here); `-X scan-threads` with mapped
  input was not measured by this module (the loader's owner reports 11.3 s instead of 14.4 s wall for
  the mathlib load with two scanner threads).
- `--threads auto` and N > 1: all 230 published tests and the tutorial mutant corpus pass with automatic
  workers (wave 4 guards, `work/status/steward.log`); the driver owner's schedule-independence runs are in
  `work/status/rlvm-driver.log`.
- `SliceE2ETest -Drlvm.e2e=all` runs inside the test JVM (4 GB heap, parent pom): 229 of 230 pass and
  `good/perf/gf3-matrix-product` ends with launcher status 3 (OutOfMemoryError: its intermediate big
  numbers need more than 4 GB; see `rlvm-api/README.md`). Through the distribution (13 GB heap) it
  passes. `-Drlvm.e2e=tutorial` passes 141 / 141.
- The steward scripts are not covered by tests of their own. (`bench/rlvm/run.sh --selftest` expected the
  arena run line to *decline* the smoke export; the integrator changed that expectation to accept, and
  `scripts/dev.sh selftest` passes 29 / 29 for both launchers.)
