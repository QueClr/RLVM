# rlvm-testkit

Tools for the testing strategy of `docs/design/ARCHITECTURE.md` section 9 (work package T1). Not part
of the shipped binary; nothing may depend on this module except tests and developer launchers.

| Piece | What it is |
|---|---|
| `Leanchecker` | runs the official checker of the pinned toolchain (`leanchecker --from-export`, Lean 4.36.0) on an export; verdict, stage, message, wall and CPU time, peak RSS; results cached by SHA-256 |
| `OfficialVerdicts` | the official-verdict cache and the manifest of expected outcomes for a directory of arena tests |
| `ExportMutator`, `ExportModel` | seeded, deterministic, structure-preserving edits of NDJSON exports (22 kinds: the 21 of the first corpus plus `WRAP_MDATA`, added by the integrator) |
| `MutantCorpus` | generates mutants, classifies them with the official checker, lays them out as an arena-style suite, and judges any checker against the classification |
| `DiffEngine`, `DiffProvider` | an `Engine` that runs two engines on every unit and records every difference in verdict; `-X engine=diff` |
| `KernelSampler`, `OpsFactory` | the `KernelOps` sampler: `infer` / `whnf` / `whnfCore` / `isDefEq` / `isProp` / `ensurePi` answers of two engines compared on sampled sub-terms of real declarations |
| `DiffRun` (`testkit diff`) | the differential campaign: the real launcher with `-X engine=diff` over exports, every difference with unit, both outcomes and a reproduction command |
| `BrokenEngine`, `BrokenProvider` | a deliberately broken wrapper engine (eight kinds of lies) that a differential run must catch: the test of the test |
| `Json` | a small JSON tree whose writer reproduces lean4export's bytes |
| `Main`, `bin/testkit` | the command line |

## Differential campaign (`testkit diff`, `-X engine=diff`)

```bash
# the machine against the reference kernel (classes that are not in the distribution go to --cp)
rlvm-testkit/bin/testkit diff --engine machine --cp rlvm-machine/target/classes -j 4 work/arena-site/tests
rlvm-testkit/bin/testkit diff --engine machine --cp rlvm-machine/target/classes --threads 8 --fail-on-decline \
    work/exports/init.ndjson work/exports/std.ndjson
# with the KernelOps sampler on about one accepted unit in 20 (the machine's sessions have no ops(): name a factory)
rlvm-testkit/bin/testkit diff --engine machine --cp rlvm-machine/target/classes:rlvm-machine/target/test-classes \
    --sample 20 --ops rlvm.machine.SomeOpsFactory work/workloads/slices/cedar-hot-ax.ndjson
# the harness itself: two reference engines agree; the broken engine is caught
rlvm-testkit/bin/testkit diff --engine reference --sample 1 -j 5 work/arena-site/tests
rlvm-testkit/bin/testkit diff --engine broken --expect-differences --sample 5 -j 5 work/arena-site/tests
```

Every export is one run of `work/dist/bin/rlvm-jvm -X engine=diff --threads N EXPORT` (reader, loader,
environment pass, driver, workers) with the test kit's provider on the class path (`RLVM_EXTRA_CP` =
`rlvm-testkit/spi`, the test kit, and `--cp`). The primary engine of that run is a `DiffEngine`: the
engine under test and the reference kernel check every unit an engine checks (definitions, theorems,
axioms, opaques; inductive and quot units belong to the environment pass and are not compared).

Output per export: `same` / `DIFFERENT` / `FAILED`, the run's verdict, the number of checks; then per
difference the kind, the unit, the constant, both outcomes with reason and message, `persistent` or
`warm-only` (both sides are re-run in fresh sessions), and `reproduce: rlvm-testkit/bin/testkit diff ...`;
declines of the engine under test are listed with unit, constant, reason and message and counted by
reason (a declined unit was not compared; `--fail-on-decline` makes any of them a failure: that is
the machine's gate, because the driver's fallback to the reference kernel hides declines from the exit
status). A decline of the oracle is listed as "not compared" too and is never a difference. An oracle
that ran out of a budget (bytes, steps, depth) on a unit the engine under test decided is first run
once more, in a fresh session, with no step limit and at least 6 GiB (`-X diff.oracle-retry-mib=N`,
0 = never): a warm attempt's byte budget is the unit's share of the run's memory, and a unit such as
`perf/app-lam` (1.2 GB) would otherwise go uncompared whenever several workers run. Exit status: 0 no difference; 1 a difference; 3 no difference but a failed run or nothing
compared. The exit status of the single runs is always the reference kernel's verdict (the driver
confirms rejections and re-checks declines with it): the report is the result, not the status.
Reports: `work/diff-reports/STAMP/NNNN.jsonl` (one JSON line per difference or decline, a `SUMMARY`
line per engine instance), `NNNN.log`, `summary.json`.

Options: `--engine NAME` (default: the best non-reference engine on the class path, else the
reference against itself), `--cp PATH`, `--threads N` (per run, default 1), `-j N` (runs at a time),
`--sample N`, `--sample-count K`, `--sample-unit U`, `--seed S`, `--ops CLASS`, `--policy
decline|follow-oracle`, `--broken FAULTS`, `--only GLOB`, `--launcher FILE`, `--out DIR`,
`--timeout S`, `--max-print N`, `--fail-on-decline`, `--expect-differences`; arguments after `--` go
to `rlvm`. An argument that is neither a file nor a directory is a corpus name (`init`, `std`,
`cedar`, `mathlib`, ...: `work/exports/NAME.ndjson`; `cedar-hot-ax`: `work/workloads/slices/`).

Without the harness: `RLVM_EXTRA_CP=... scripts/dev.sh diff FILE --threads N -X diff.engine=machine
-X diff.report=F.jsonl -X diff.sample=20`. The provider declares the keys `diff.engine`, `diff.policy`,
`diff.report`, `diff.label`, `diff.recheck`, `diff.repro`, `diff.sample`, `diff.sample.count`,
`diff.sample.unit`, `diff.seed`, `diff.sample.proofs`, `diff.sample.more`, `diff.ops`, `diff.declines`,
`diff.oracle-retry-mib` (`rlvm.api.EngineProvider.optionKeys`); each can also be given as `-Drlvm.diff.KEY` or as the
environment variable `RLVM_DIFF_KEY` (`testkit diff` passes its own environment on, so
`RLVM_DIFF_SAMPLE_MORE=false rlvm-testkit/bin/testkit diff ...` switches the `isProp` / `whnfCore` /
`ensurePi` comparisons off), and both engines receive the launcher's other `-X` settings.
When the test kit's provider is not on the class path, `-X engine=diff` is the driver's own
differential mode (`RunConfig.differential`: stops at the first disagreement with a DECLINE).

### The `KernelOps` sampler

On about one unit in N that both engines accepted, `KernelSampler` compares the engines' judgments on
sub-terms of the constant's type and value (value three times out of four). A sub-term is reached by
a seeded random walk; the binders on the way are opened as the kernel opens them: one local per
`fun` / `Pi`, made by each engine with its own `mkLocal`, the value substituted for a `let`. Terms are
translated between the two engines' locals by the sampler (`abstractFVars` + `instantiateRev`), so
an engine never sees a foreign local. With the reference kernel as the judge:

| Judgment | What must hold | Difference |
|---|---|---|
| `infer` | the two types are definitionally equal | `SAMPLE_INFER` |
| `whnf` (not on proofs) | the result is definitionally equal to the term, and its head symbol is the head symbol of the oracle's weak head normal form (sort, Pi, fun, the same constant, the same local; a literal against a constructor head passes) | `SAMPLE_WHNF_DEFEQ`, `SAMPLE_WHNF_HEAD` |
| `isDefEq` | the same yes / no on: the type of an argument against the domain of its function (yes), a term against its weak head normal form (yes), a type against the sort it lives in (no) | `SAMPLE_DEFEQ` |
| `isProp` | the same yes / no on the type of the sub-term | `SAMPLE_ISPROP` |
| `whnfCore` (not on proofs) | as `whnf`, against the oracle's `whnfCore` | `SAMPLE_WHNF_DEFEQ`, `SAMPLE_WHNF_HEAD` |
| `ensurePi` | on the type of the function of an application: a Pi whose domain is definitionally equal to the oracle's | `SAMPLE_ENSURE_PI` |
| any | no exception other than `KernelException` / `ResourceException` | `SAMPLE_CRASH` |

A `ResourceException` leaves the sub-problem undecided (counted). Both `KernelOps` work on the context
of the session under test; a sampled unit ends with the overlay cut back, `NatOps.reset()` and
`Session.reset()` of both sides, so sampling makes the engine under test a little colder. The
sub-problems of a unit are a function of (seed, constant id): `--sample-unit U --seed S` replays them.
The sampler has no step budget of its own: a pathological weak head normal form runs as long as the
oracle needs (none seen on the 230 tests and Init).

An engine whose sessions return `ops() == null` (the machine in its release configuration) names a
factory: `public final class X implements rlvm.testkit.OpsFactory { public KernelOps open(Session s,
WorkerContext ctx) { ... } }` with a public no-argument constructor, `--ops X` / `-X diff.ops=X`.

### What was run (2026-11-24, reference engine of the distributions built 07:21Z and 08:19Z)

| Run | Result |
|---|---|
| reference against reference, the 230 published tests, sampler on every unit, `-j 5` | verdicts 138 accept / 92 reject as expected; 15,845 units compared (15,804 both accept, 41 both reject), **0 differences**; 196,194 sampled judgments, **0 differences**, 2 undecided; 5.5 min |
| reference against reference, Init, 4 workers, `--sample 20` | 57,938 units, 0 differences; 2,909 units sampled, 35,156 judgments, 0 differences; 49 s wall, 123 s CPU (a plain run on the same cores: 43 s CPU) |
| the broken engine (`all` faults), the 230 tests, `--sample 5` | **caught**: 41 SOUNDNESS (exactly the 41 units the reference rejects), 146 COMPLETENESS, 21,829 sampler differences (10,718 DEFEQ, 9,974 INFER, 1,137 WHNF_HEAD); run verdicts still 138 / 92 |
| the machine skeleton of 08:24Z (`--engine machine --cp rlvm-machine/target/classes`) | 9 of 9 units `declined by machine: INTERNAL_ERROR ... Decl.check is not implemented yet`, verdicts right, exit 3 (nothing compared) |
| the machine as its tree was at 10:00Z, the 141 tutorial tests, `-j 4` | 75 runs completed: 114 units compared (107 both accept, 7 both reject), **0 differences, 0 declines of the machine**; 66 runs FAILED (exit 64 / 3: "no engine 'machine' on the class path") because `rlvm-machine/target/classes` was rebuilt by its owners during the run; exit 3 (incomplete). Give `--cp` a jar or a copy of the classes that nobody rebuilds meanwhile |
| reference against reference, tutorial + bugs + other + corner-cases, sampler on every unit, with `isProp` / `whnfCore` / `ensurePi` | 1,392 units, 27,680 sampled judgments, 0 differences |
| reference against reference, `cedar-hot-ax` (the Cedar slice), 4 workers, `--sample 10` | 8,064 units, 0 differences; 793 units sampled, 10,273 judgments, 0 differences; 31 s wall |

## Results produced (2026-11-24, this host)

| File | Content |
|---|---|
| `work/oracle/verdicts.json` | official verdict, exit status, stage, message (when not accepted), wall and CPU time, peak RSS, SHA-256 for the 230 published tests and the 30 `either` tests of `work/exports/all` |
| `work/oracle/manifest.json` | expected outcome per test = the arena's classification cross-checked with the official verdict; `flagged` lists every contradiction or undecided test |
| `work/oracle/cache/` | one JSON file per checked input (by SHA-256): the verdict cache shared by all tools |
| `work/mutants/` | corpus `tutorial`: 12,260 classified mutants of the 141 tutorial tests (22 kinds, 5 per source and kind): 3,599 accepted, 8,661 rejected; `index.jsonl`, `SUMMARY.md`, `SUMMARY.json`, `suite/{good,bad,either}/` |
| `work/mutants-bugs-other/` | corpus `bugs`: 2,692 classified mutants of the 45 `bugs/`, `other/` and `corner-cases/` tests (literals, strings, nested and mutual inductives, hand-formatted files; 3 per source and kind): 187 accepted, 2,505 rejected, none undecided, no anomaly |
| `work/mutants-prelude/` | corpus `prelude`: 847 classified mutants of `init-prelude` (40 per kind): 358 accepted, 489 rejected |
| `work/status/mutants.md` | one summary table per `testkit judge --all` run (SOUNDNESS / COMPLETENESS / agree / declined per corpus) |
| `work/mutants-mdata/` | corpus `mdata`: 1,976 `WRAP_MDATA` mutants of the 186 tutorial, `bugs/`, `other/` and `corner-cases/` tests (one expression reference wrapped in an `mdata` record): 461 accepted, 1,515 rejected (1,420 kernel, 31 quotient post-check, 64 reader). The official checker accepts metadata in definition, theorem and axiom roots (all 247 such mutants of good sources) and rejects it in constructor types (87 of 87), recursor types (66 of 66), rule right-hand sides (80 of 80) and quot types (15 of 15). Integrator, 2026-11-24 |

The 230 published tests: 138 accepted and 92 rejected (86 by the kernel, 6 by the export reader) by the
official checker 4.36.0, **no disagreement with the arena's good/bad classification** (179.6 s of CPU
in total; the heaviest is `perf/magma-list-deep-n36` with 28 s of CPU and 7.8 GiB peak RSS). The 30 `either` tests: 9 accepted, 19 rejected, 2 without an answer
(`corner-cases/eta-struct-major-no-whnf` and `-and-no-whnf` do not terminate; 120 s timeout), which is
also what the arena published for its official v4.35.0.

`WRAP_MDATA` is one of the kinds of every default corpus since the rebuild of 2026-11-24T08:27Z
(`testkit corpus --all`, 36 s with the verdict cache): the 21 earlier kinds regenerated byte for
byte (all 14,973 old mutants of the three corpora have the same name, SHA-256 and verdict), 655 + 131
+ 40 `WRAP_MDATA` mutants were added (260 accepted, 566 rejected by the official checker).

The tutorial corpus without the `WRAP_MDATA` mutants (seed 1, up to 5 mutants per source and kind, 31
byte-identical duplicates dropped):
3,384 mutants accepted and 8,221 rejected by the official checker (5,586 by the kernel, 2,569 by the
export reader, 66 by the quotient post-check), none undecided. None of the 2,909 mutants of the five
kinds that must keep the verdict (hint, renumbering, reformatting, declaration order, binder
annotations) changed it. 103 mutants of *rejected* tests are *accepted* (the offending declaration was
dropped, became an axiom, ...): they are the "never accept" cases turned around, a checker that still
rejects them is wrong too.

Facts about the official checker that the corpus established (`work/mutants/SUMMARY.md` has the table
per edited field):

- It does not read `numIndices`, `numNested`, `isRec`, `isReflexive` of an inductive type record, nor
  `isUnsafe` of constructor and recursor records, nor `all` of a definition, hints, binder names, binder
  info, `nondep`, nor anything in the meta line (but line 1 must exist and is skipped whatever it is).
  Mutants forging these are **accepted**; RLVM must not reject them (declining is allowed).
- It does compare `numParams` of the type, `cidx` / `numFields` / `numParams` of constructors, every
  count, `k`, and every rule (constructor, `nfields`, right-hand side, order, number) of recursors.
- A third way to reject besides kernel error and reader exception: the **quotient post-check**
  (`Lean default kernel accepts the solution` followed by `Quotient post-check rejects the solution`,
  exit 1) when a `quot` record differs from the kernel's own constant.
- A declaration line moved behind a later declaration (its user, when it has one) does not change the
  verdict (replay is by dependency): all 226 such mutants of accepted tests are accepted, all 86 of
  rejected tests stay rejected.
- Ids above 2^32 and above 2^64 do not change the verdict (110 `RENUMBER_IDS` mutants of accepted
  tests in these two styles, all accepted).

## Command line

`rlvm-testkit/bin/testkit COMMAND ...` (runs the test kit's jar published in `.toolchain/m2`, so
that a campaign does not load classes from a directory that a build of the module rewrites
meanwhile; `RLVM_TESTKIT_TREE=1` uses `rlvm-testkit/target/classes` instead, for work on the test
kit itself; the other RLVM jars come from `.toolchain/m2`).

```bash
# official verdicts for a tests directory -> work/oracle/{verdicts,manifest}.json (runs accumulate by test name)
rlvm-testkit/bin/testkit oracle -j 6                                   # the 230 published tests, 33 s
rlvm-testkit/bin/testkit oracle --tests work/exports/all --bucket either --timeout 120
rlvm-testkit/bin/testkit verdict FILE.ndjson ...                       # single files, cached

# mutants of one export
rlvm-testkit/bin/testkit mutate work/arena-site/tests/good/tutorial/070_nRec.ndjson forge_rule 5 work/tmp/m --seed 1
rlvm-testkit/bin/testkit mutate SOURCE all 3 OUTDIR                    # every kind

# a classified corpus (regenerates bit for bit; the classification comes from the cache the second time: 2 s)
rlvm-testkit/bin/testkit corpus --per-kind 5 -j 6 --timeout 30         # tutorial tests -> work/mutants, 77 s
rlvm-testkit/bin/testkit corpus --only '{bugs,other,corner-cases}/*' --out work/mutants-bugs-other --per-kind 3

rlvm-testkit/bin/testkit corpus --all -j 8                             # the four default corpora, each rebuilt next to
                                       # the old one and swapped in (a judge running meanwhile loses no file)

# the guard of every publication: all default corpora, one summary table, appended to work/status/mutants.md
rlvm-testkit/bin/testkit judge --all -j 12 -- scripts/check-light.sh '"$IN"'     # RLVM_DIST=DIR for another distribution

# "never accept what the official checker rejects": any checker over one corpus
rlvm-testkit/bin/testkit judge -j 8 -- "$(bench/rlvm/run.sh --jvm --print)"   # RLVM's arena run line (from the project root;
                                       # --native | --jvm | --pgo: a launcher that is built in work/dist);
                                       # exit 1 on any SOUNDNESS or COMPLETENESS difference
rlvm-testkit/bin/testkit judge --kind FORGE_RULE --limit 200 -- CMD '"$IN"'
bench/run-suite.py --tests work/mutants/suite -j 8 -- CMD '"$IN"'      # the same corpus, scored like the arena
```

`judge --all` is the mutant guard of a publication: the four default corpora, one table, appended to
`work/status/mutants.md` with the distribution's build stamp (`scripts/gate.sh --corpus all` calls it).
Results of 2026-11-24 (reference engine, one worker, `scripts/check-light.sh`, 12 jobs on the 12 CPUs
outside the measurement container):

| Distribution | Mutants | SOUNDNESS | COMPLETENESS | agree | declined | error | Wall |
|---|--:|--:|--:|--:|--:|--:|---|
| jars published by 08:28Z | 17,775 | 0 | 0 | 15,238 | 2,537 | 0 | 27.5 min (host load average 80 to 120) |
| jars published by 09:04Z | 17,775 | 0 | 0 | 15,330 | 2,445 | 0 | 54 min (host load average 160 to 185) |

How long it takes depends on the host: the work is 17,775 JVM starts, about 70 CPU-minutes on the
CPUs outside the container (measured on samples: 0.22 s of CPU per tutorial mutant, 0.50 s per
`init-prelude` mutant). That is an estimated 7 to 10 minutes with 12 jobs when those CPUs are free
(not measured: they never were that day), and what the table shows when a dozen other guards run at
the same time.

`judge` runs the command with `sh -c`, the export in `$IN`, exit 0 accept / 1 reject / 2 decline (the
arena's contract). SOUNDNESS = accepted although the official checker rejects; COMPLETENESS = rejected
although it accepts; declines, errors and timeouts are counted, never wrong. It prints one line per
difference with the edit and a command to reproduce it, and writes a JSON report
(`work/mutants-reports/judge-*.json`). Runs made so far: the official checker on a sample of 400
mutants and, through `bench/run-suite.py`, on 922 (0 differences); the native placeholder launcher of
2026-11-24 04:00Z on all 11,605 (9 s; all declined, 0 differences); the JVM launcher of 05:40Z on a
sample of 60 (all declined). No RLVM build that answers anything but "decline" existed. A launcher
that is missing makes the run line exit with 3, which `judge` counts as an error, not as a difference;
check the `error` column before reading "0 differences" as good news.

The same commands are available as `scripts/dev.sh oracle | verdict | mutate | corpus | judge`.

Environment: `RLVM_LEAN_TOOLCHAIN` (default `~/.elan/toolchains/leanprover--lean4---v4.36.0`),
`RLVM_ORACLE_CACHE` (default `work/oracle/cache`), `RLVM_ROOT`.

## Public API (`rlvm.testkit`)

```java
final class Leanchecker {
    Leanchecker(Path toolchainHome, Path cacheDir);        // cacheDir null = no cache
    static Leanchecker locate();                            // RLVM_LEAN_TOOLCHAIN / elan default, work/oracle/cache
    Leanchecker withMemoryLimit(long bytes);                // prlimit --as; for mutants
    boolean available();
    Result check(Path export, Duration timeout);            // never throws for a property of the input
    enum Verdict { ACCEPT, REJECT, TIMEOUT, ERROR }
    final class Result { int exitCode; String message; Verdict verdict; String stage;   // kernel | reader | post-check | resource
                         String output; double wallSeconds, cpuSeconds; long maxRssKb; boolean cached; String sha256;
                         boolean accepted(), rejected(), decided(); }
}
final class OfficialVerdicts {                              // discover / annotateFromArena / collect / verdictsJson / manifestJson
    final class Test { name, bucket, arenaOutcome, file, size, official; String expected(); boolean disagreement(), undecided(); }
}
final class ExportMutator {
    enum Mutation { SWAP_CHILD, SWAP_LEVEL, FORGE_COUNT, FORGE_FLAG, FORGE_RULE, CHANGE_HINT, RENUMBER_IDS, REFORMAT_JSON,
                    REORDER_DECL, DUPLICATE_DECL, CHANGE_BINDER_TYPE, SWAP_NAME, SWAP_DECL_ROOT, CHANGE_INDEX, CHANGE_LITERAL,
                    CHANGE_DECL_KIND, CHANGE_BINDER_INFO, DROP_RECORD, DROP_DECL, DUPLICATE_RECORD, CHANGE_META;
                    boolean verdictPreserving; }
    ExportMutator(long seed);
    List<Path>   mutate(Path source, Mutation kind, int count, Path outDir);
    List<Mutant> mutants(Path source, Mutation kind, int count, Path outDir);   // + line, description, sha256
}
final class MutantCorpus {
    static List<Entry> build(Options, Leanchecker, PrintStream progress);
    static List<Entry> readIndex(Path corpus);
    static List<Judgement> judge(Path corpus, List<Entry>, String command, Path cwd, int jobs, Duration timeout, PrintStream);
}
final class DiffEngine implements Engine {
    DiffEngine(Store, EngineConfig, EngineFactory underTest, EngineFactory oracle[, Options]);
    static EngineFactory of(EngineFactory underTest, EngineFactory oracle[, Options]);
    enum Policy { DECLINE, FOLLOW_ORACLE }
    interface ContextFork { WorkerContext fork(WorkerContext primary); }
    final class Options { policy, fork, recheckCold, oracleBudget, oracleRetryBytes, maxRecorded, maxDeclines, label, reportFile, reproduce;
                          int sampleEvery, sampleUnit; KernelSampler.Options sampler; OpsFactory underTestOps;
                          final List<Disagreement> found;                 // of every engine made with these options
                          final List<KernelSampler.Difference> foundSamples;  final KernelSampler.Tally sampled;
                          static fromSystemProperties(); static fromSettings(Map<String,String> xOptions); }
    List<String> disagreements();  List<Disagreement> disagreementRecords();   // Disagreement.reproduce = the command
    long disagreementCount(), soundnessDisagreements(), completenessDisagreements(), agreements(), checks(),
         declinedByUnderTest(), declinedByOracle(), sampledUnits(), sampleDifferenceCount(); String sampleUnavailable();
}
final class DiffProvider implements EngineProvider { name() = "diff"; priority() = Integer.MIN_VALUE;
                                                     optionKeys() = diff.*; factory(Map) }
final class KernelSampler {
    KernelSampler(Store, WorkerContext shared, KernelOps underTest, KernelOps oracle, Options);
    final class Options { seed, perConstant, maxBinders, maxDepth, infer, whnf, defEq, whnfProofs, printNodes; }
    List<Difference> sample(int cid);          // Difference: kind, cid, unit, constant, root, path, problem, underTest, oracle, seed
    Tally tally();                             // constants, problems, infer, whnf, defEq, differences, undecided, oracleErrors, ...
}
interface OpsFactory { KernelOps open(Session session, WorkerContext ctx); }
final class BrokenEngine implements Engine {   // BrokenEngine.of(factory, Faults.parse("accept-rejected,reject-every=7,..."))
    final class Faults { acceptRejected, rejectEvery, warmRejectAfter, defEqTrue, defEqFalse, whnfIdentity, inferProp, isPropFalse; }
}
final class BrokenProvider implements EngineProvider { name() = "broken"; }   // service file only in rlvm-testkit/spi-broken
```

### Mutation kinds

| Kind | Edit | Must keep the official verdict |
|---|---|---|
| `SWAP_CHILD` | a child id of an expression record becomes another earlier expression | |
| `CHANGE_BINDER_TYPE` | the binder type of a lam / forallE / letE becomes another earlier expression | |
| `SWAP_LEVEL` | a level reference (`sort`, `const.us[i]`, operands of level records) becomes another earlier level | |
| `SWAP_NAME` | a name reference (constant, projection type, level parameter, declared name, `induct`, `ctors`, `all`, name prefix) becomes another earlier name | |
| `SWAP_DECL_ROOT` | type or value of a declaration, constructor, recursor or inductive type becomes another earlier expression | |
| `CHANGE_INDEX` | a de Bruijn index or a projection field index changes | |
| `CHANGE_LITERAL` | a Nat literal (plus or minus one, zero, 2^64, a leading zero, 40 digits, ...) or a String literal (append, drop, non-ASCII, astral, newline) changes | |
| `FORGE_COUNT` | `numFields`, `numParams`, `cidx`, `numIndices`, `numMinors`, `numMotives`, `numNested`, `nfields` | |
| `FORGE_FLAG` | `k`, `isRec`, `isReflexive`, `isUnsafe`, `safety` | |
| `FORGE_RULE` | a rule's right-hand side or constructor is redirected; a rule is dropped, duplicated, or two are swapped | |
| `CHANGE_DECL_KIND` | def / thm / opaque become one another or an axiom; the kind of a quot record changes | |
| `DROP_RECORD`, `DUPLICATE_RECORD` | a name, level or expression record is dropped, or bound twice | |
| `DROP_DECL`, `DUPLICATE_DECL` | a declaration line is dropped or duplicated (in place or at the end) | |
| `CHANGE_META` | the meta line is dropped, replaced, duplicated, moved to the end, or its versions are edited | |
| `CHANGE_HINT` | `hints` of a definition | yes |
| `RENUMBER_IDS` | all ids of the three spaces renamed: sparse, shuffled, sometimes above 2^32 or 2^64 | yes |
| `REFORMAT_JSON` | other key order, whitespace, `\uXXXX` escapes (basic plane only), CRLF, no final newline | yes |
| `REORDER_DECL` | a declaration line moved behind a later declaration (one that uses it, when there is one) | yes |
| `CHANGE_BINDER_INFO` | binder info, binder name, `nondep` | yes |

"Must keep the verdict" refers to the official checker; RLVM may decline such a mutant (a forward
reference, an id it cannot represent) but must never answer differently.

## Invariants

- **A tool failure is never a verdict.** `Leanchecker` reports an unreadable file, a missing or broken
  toolchain, a signal, an out-of-memory abort and a timeout as `ERROR` / `TIMEOUT`, although the
  official binary exits with 1 for several of them, and never caches a failure of the tool. Kernel
  errors that mean "out of a resource" (`deep recursion detected`, ...) are rejections with stage
  `resource`; the corpus files them under `either/`.
- **Determinism.** Mutant `i` of (seed, source file name, kind) is a function of these alone
  (SplitMix64, FNV-1a); the first `n` mutants of a larger request equal a request for `n`. A corpus
  rebuild gives the same `index.jsonl` byte for byte.
- **Well-formed output.** Every line of every mutant is one JSON value; an edited line is written in
  lean4export's canonical form (`Json.write(Json.parse(line))` equals `line` for all 11,658 lines of
  the tutorial tests), all other lines are copied unchanged.
- **Expected outcome = the arena's classification**; the official verdict cross-checks it and every
  contradiction is flagged, never resolved silently. A decline is always permitted.
- **DiffEngine never turns a disagreement into an ACCEPT of what the oracle rejects**, under either
  policy; an exception in a side is a decline of that side; `Error`s are not caught; a side is reset
  after any outcome of its own other than ACCEPT, as the driver would do.

## DiffEngine

```
under test  oracle   recorded            outcome (Policy.DECLINE, default)   outcome (Policy.FOLLOW_ORACLE)
ACCEPT      ACCEPT   agreement           ACCEPT                               ACCEPT
REJECT      REJECT   agreement           REJECT, the oracle's reason          REJECT, the oracle's reason
ACCEPT      REJECT   SOUNDNESS           DECLINE(ENGINE_DISAGREEMENT)         REJECT, the oracle's reason
REJECT      ACCEPT   COMPLETENESS        DECLINE(ENGINE_DISAGREEMENT)         ACCEPT
DECLINE     x        decline (side)      x                                    x
```

Each record (`Disagreement`, also one JSON line in `Options.reportFile`) has: kind, constant (cid,
unit, name), both engines' names, outcomes, reasons and messages, the worker, the budget, strict or
not, how many checks the session under test had made since its last reset, and the outcomes of a
**cold re-run** of both sides, which separates a `persistent` difference from a `warm-only` one (a
stale cache). `dumpStats` writes the counters under `diff.*` and forwards each side's statistics
under `diff.under_test.*` / `diff.oracle.*`.

How a harness learns the result. The driver creates the engines itself (one for the warm sessions and
a strict one for re-checks) and re-checks every non-ACCEPT outcome with the reference kernel, so the
exit status of a differential run is the reference's verdict under either policy. The result of the
run is the set of disagreements: `options.found` (shared by all engines made from one
`DiffEngine.of(...)` factory), the JSON lines in `options.reportFile`, and the `diff.disagree.*`
counters in the statistics. A differential run passes when that set is empty.

Wiring. `DiffEngine.of(underTest, oracle, options)` is an `EngineFactory`. To give the oracle its own
`WorkerContext`, set `options.fork = ctx -> wiring.newWorkerContext(ctx.workerId, ctx.store,
ctx.signals, cap)` (the oracle is another engine instance, so the same worker id is unique there).
A launcher that cannot depend on this module puts `rlvm-testkit/spi` (a service file naming
`DiffProvider`) and the test kit on the class path and selects `-X engine=diff` (`scripts/dev.sh diff`
and `testkit diff` do exactly that through `RLVM_EXTRA_CP`); the engine under test is then
`-X diff.engine=NAME` or the best non-reference provider (never the broken one), the oracle the
reference provider, and the options come from the `-X diff.*` settings, the `rlvm.diff.*` system
properties or the `RLVM_DIFF_*` environment variables (section "Differential campaign").
`rlvm-main` switches the driver's own differential pass off in that case, so a unit costs the engine
under test plus one reference run.

## Deviations from the design documents

- **Outcome of a disagreement.** The skeleton's comment had "outcome of the oracle"; ARCHITECTURE 8.3
  has `DECLINE(ENGINE_DISAGREEMENT)`. The default follows 8.3 (fail closed); the skeleton's table is
  `Policy.FOLLOW_ORACLE`, for campaigns that want every disagreement of a file in one run.
- **Contexts.** The skeleton promised each side its own `WorkerContext`. A context can only be made by
  the wiring (`rlvm.driver.Wiring` / `rlvm.api.bind.Bindings`), which the `EngineFactory` signature
  does not carry, so the fork is an option; without it the two sessions share the caller's context
  and run strictly in sequence.
- **Cache location.** `work/oracle/cache/` (next to `verdicts.json`), not `work/leanchecker-cache/`.
- **`Result.message`** is the whole message on one line (declaration and kernel error), not the last
  line of the output, which for a kernel error is just the tail of a pretty-printed term.
- **`DiffProvider` is not registered** in the jar's `META-INF/services`: a provider that is neither
  the machine nor the reference must not become the default engine by being on the class path.
- **More mutation kinds** than the skeleton's ten (11 added); `REORDER_DECL` is marked
  verdict-preserving because the official checker replays by dependency.
- **`scripts/dev.sh`** belongs to the steward, who has wired `oracle | verdict | mutate | corpus |
  judge | diff | track | gate`. `scripts/dev.sh diff FILE` is one launcher run with the test kit's
  provider; the campaign (several exports, report, exit status) is `rlvm-testkit/bin/testkit diff`.
- **The reproduction command of a difference repeats the whole export** (`--threads 1`): the driver
  has no way to check one unit without the units before it. A sampler difference is replayed on its
  unit alone (`--sample-unit`).

## Tests

`. ./env.sh && $MVN -o -q -pl rlvm-testkit test`: 87 tests, all green.

| Suite | Tests | What |
|---|---:|---|
| `JsonTest` | 8 | byte-exact round trip of fixtures and of all tutorial tests; escapes; number text; malformed input |
| `ExportMutatorTest` | 16 | every kind on five fixtures: well-formed NDJSON, distinct, different from the source; same seed same bytes; prefix property; one-line edits; renumbering is an isomorphism; reformatting keeps every line's JSON value and never escapes astral characters; reorder keeps the multiset of lines; all 141 tutorial tests yield well-formed mutants of every applicable kind |
| `LeancheckerTest` | 13 | interpretation of the official output (accept, kernel, reader, post-check, resource, tool failures, signals); caching, timeout, crash with a fake checker; the real checker on fixtures; **classification is reproducible**: for the mutants of a fixture (two per kind) two uncached runs of the real checker agree on verdict, exit status, stage and message |
| `OfficialVerdictsTest` | 3 | verdicts, manifest, flags (DISAGREEMENT, UNDECIDED), arena metadata, accumulation |
| `MutantCorpusTest` | 9 | layout, index, buckets, anomalies; rebuild is identical; refuses to empty a foreign directory; `judge` with accept-all / reject-all / decline-all / crashing / slow checkers and with the classifier itself; `WRAP_MDATA` is a default kind; a rebuilt corpus is swapped in whole and the old mutants come back byte for byte; `judge --all` writes one summary table per run, fails on a wrong checker, skips a corpus that is not built |
| `DiffEngineTest` | 15 | the outcome table under both policies (fake engines); an oracle that ran out of a budget is retried cold when the engine under test gave a verdict; never an ACCEPT against the oracle; record contents; warm-only detection; resets mirror the driver; exceptions; `Error`s propagate; forked context; oracle budget; statistics; report file; the list shared by a warm and a strict engine of one factory; four worker threads |

| `DiffEndToEndTest` | 11 | **`-X engine=diff` end to end and in process** (`rlvm.main.RlvmChecker`: the real reader, loader, environment pass, driver, workers; the provider found through `ServiceLoader`): two reference engines agree on four fixtures with the sampler on every unit, on a rejection, and with four workers; the broken engine is caught as SOUNDNESS with unit, both outcomes, reason and reproduction command while the run's verdict stays the reference's REJECT; as COMPLETENESS while the run still accepts; a fault that needs a warm session is classified `warm-only`; wrong `infer` / `isDefEq` / `whnf` / `isProp` answers are caught by sampling; settings arrive as `-X diff.*` launcher options; the sampler replays one unit with the same seed |
| `DiffRunTest` | 7 | `testkit diff` with a scripted launcher: what is passed to the launcher (class path, settings, reproduction command), de-duplication of the records the driver's re-check protocol produces, sampler differences, declines listed and `--fail-on-decline`, failed runs and "nothing compared" are not success, `--expect-differences`, directories and `--only` |
| `DiffEngineIntegrationTest` | 5 | DiffEngine over the **real** `rlvm-ref` engine, `rlvm-store` and `Bindings` contexts on a hand-made store (three definitions over sorts, one ill-typed): reference against itself with forked and with shared contexts (accept, reject with the kernel's reason, resets); an accept-everything engine is caught as SOUNDNESS with the constant's name and unit from the store and a persistent cold re-run; a reject-everything engine gives COMPLETENESS records and FOLLOW_ORACLE carries on; `KernelSampler` used directly on two `KernelOps` of one context (agreement, lies caught with constant and place, same seed same sub-problems, overlay left as found) |

Tests that need the pinned toolchain or `work/arena-site/tests` skip themselves when those are absent.
`rlvm-ref` and `rlvm-main` are test-scope dependencies of this module (for the integration and the
end-to-end tests only; nothing of the test kit is a dependency of `rlvm-main`).

## Known gaps

- **The differential campaign covers what engines check**: definitions, theorems, axioms, opaques.
  Inductive and quot admission is the environment pass (reference kernel through `KernelOps`) and is
  not compared; an engine that takes it over needs a comparison of its own.
- **Forked oracle contexts** (`Options.fork`) are only reachable from code that has the wiring (a
  JUnit test with `Bindings`); through the launcher both sides share the worker's context, which is
  what the tests and the campaign runs above exercise. A reference session that overflows its stack
  resets the shared context under the engine under test (not seen in any run; workers have deep stacks).
- **The sampler has no step budget of its own** and compares no `ensureSort` / `check` answers;
  negative `isDefEq` samples are only "a type against its sort".
- The machine has not been run through the campaign beyond its skeleton (every unit declined).
- The invariance suite of 9.2 (`-j 1/2/6`, session cap, proof blocks) needs the driver; not here.
- `ExportModel` keeps ids in a `long`: a file whose ids exceed 2^63 (a `RENUMBER_IDS` mutant of the
  "above 2^64" style) cannot be mutated again.
- `CHANGE_LITERAL` has few sites in the tutorial tests (10 mutants); the second corpus covers
  literals and strings.
- Mutants are single edits; no grammar-based fuzzing of malformed JSON (work package H1).
