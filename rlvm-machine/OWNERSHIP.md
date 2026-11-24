# rlvm-machine - ownership

**This module (`rlvm-machine/`, Java package `rlvm.machine`) is created and owned by the workflow
`rlvm-wave4-machine` as of 2026-11-24.**

Any agent that belongs to another workflow MUST NOT create, modify, move or delete anything under
`rlvm-machine/`, under `work/sandbox/`, or the scripts `scripts/machine-sandbox.sh` and
`scripts/machine-sandbox-selftest.sh`. An agent of another workflow that was asked to "create the machine
skeleton" reads this file, verifies, and reports only (ISA 13.1, orchestrator note). Requests from outside
the workflow go to `work/status/requests.log` as a line `from -> machine (M6): request`.

Inside the workflow every file belongs to exactly one work package. The machine-readable table is
`rlvm-machine/OWNERS.tsv`; `scripts/machine-sandbox.sh` enforces it (a package can publish only the files
it owns). The packages, the sandbox workflow and the rules are in `WORK-PACKAGES.md`.

## File-to-package table

Main tree (`src/main/java/rlvm/machine/`):

| Package | Files | Role |
|---|---|---|
| M1 | `Values`, `Runs`, `Lits`, `Levels`, `Scope` | value records and interning, runs and environments, literal bridge, levels and substitutions, binders |
| M2 | `Eval` | family EV: `EVAL`, `FORCE`, `APPLY`, `INST`, `ARGV`, `DOM` |
| M3 | `Reduce`, `NatRed`, `StrRed`, `Rules` | family RD: `DELTA`, `CORE`, `WHNF`, `IOTA`, `QUOTR`; `NATOP`, `NATFOLD`, `OFFSET`; `STRVIEW`; case trees |
| M4 | `Conv`, `Cargs`, `Pairs` | family CV: `CONV` and its sub-judgments; `CARGS`; the pair table and `UNDEC` |
| M5 | `Infer`, `Decl`, `Facts`, `FactStore` | family TY: `INFER`, `CHECK`, `VTYPE`, `ENSORT`, `ENPI`, `ISPROP`; the `DECL` driver; static facts |
| M6 | `Layout`, `Op`, `Hash`, `Machine`, `Frames`, `Ops`, `MachineProvider`, `MachineEngine`, `MachineSession`, `MachineOps`, `Debug`, `Show`, `package-info`; `pom.xml`; `src/main/resources/META-INF/services/rlvm.api.EngineProvider`; `README.md` | shared definitions (frozen), machine state and control primitives, facade, debug modes, diagnostics printer, module build |
| M7 | `Elab`, `Code`, `CodeStore`, `Match`, `Cong` | K1 elaborator, code stepper (family CODE), block store, `MATCH`, comparison plans |

Test tree (`src/test/java/rlvm/machine/`):

| Package | Files |
|---|---|
| M6 | `Fixture`, `Sexp`, `Prelude`, `Toy`, `TestTables`, `ControlTest`, `MachineStateTest`, `FixtureTest`, `StepSizeTest` (shared test support and the tests of the shared code) |
| M8 | `Quote`, `TierEq`, `Diff` |
| M1 .. M8 | by naming convention: test classes `M<k>*.java`, test resources `src/test/resources/m<k>/`, notes `NOTES-M<k>.md` |

Owned by nobody (edited in the main tree only, never published from a sandbox): `OWNERS.tsv` (through
`scripts/machine-sandbox.sh claim WP PATH`, or by M6 under the lock), `OWNERSHIP.md`, `WORK-PACKAGES.md`.

A new main class is claimed before it is published: `scripts/machine-sandbox.sh claim M3
src/main/java/rlvm/machine/WhnfLoop.java`.

## Outside the module

| Path | Owner |
|---|---|
| `scripts/machine-sandbox.sh`, `scripts/machine-sandbox-selftest.sh` | M6 |
| `work/sandbox/<WP>/` | the package `<WP>`; `work/sandbox/.lock`, `.backup/`, `publish.log` belong to the script |
| `work/status/machine-<WP>.log` | the package `<WP>` (the publish command appends to it) |
| `docs/design/ISA.md` sections 1.3, 8.2, 13 and the change log of 15 | consistency edits by M6 only |
| parent `pom.xml` | not ours: the line `<module>rlvm-machine</module>` is added by the orchestrator |
