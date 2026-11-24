# NOTES-INT - the machine integrator (after work packages M1-M8)

From 2026-11-24T11:15Z the whole module has one owner (the integrator); the sandboxes are no longer used:
work in the main tree, `$MVN -o -q -pl rlvm-machine install` from the project root. The log with every run and
number is `work/status/machine-integration.md`; tools are in `work/machine-int/` (`m.py` = one measured run in
the arena-shaped container, `batch*.sh`, `family.py`, `prof/prof.sh` + `prof/byclass.py`).

## 1. Wiring (rlvm-main, parent pom, scripts)

- `pom.xml`: `<module>rlvm-machine</module>`. The module is last in the list; Maven orders by dependency.
- `rlvm-main` does NOT depend on `rlvm-machine`: `rlvm-machine -> rlvm-testkit (test) -> rlvm-main (test)`, so a
  dependency would be a reactor cycle. Engines are found through `ServiceLoader`; the jar only has to be on the
  class path.
- `scripts/build.sh`: a tree build (`dist-tree`, the arena build) packages every reactor module, so the machine
  jar is in `lib/`; a `--published` build (`scripts/dev.sh dist`, `dist-native`) now copies the published
  rlvm-machine jar from `.toolchain/m2` into `lib/` and lists it in BUILD-INFO.txt.
- `-X engine=machine` selects the machine as primary engine; the reference engine is the driver's re-check engine
  (cold re-check of a REJECT, fallback for a DECLINE). Without `-X engine=...` the reference engine runs:
  `MachineProvider.priority()` stays 10 (`Engines.DEFAULT_ENGINE_MIN_PRIORITY` is 100) until the orchestrator
  records the gates.
- The CE native image builds with the machine inside (`scripts/build.sh --published --no-pgo`, 72 s) and passes the
  build's verification; no reflection or build-time configuration was needed.

## 2. Deviations from ISA.md made by the integrator

| # | ISA | What | Why |
|---|---|---|---|
| I1 | 1.6 "Cap and sizes" | Base sizes of a session are 2^14 records, 2^15 `vtab` slots, 2^16 run words, 2^14 `rtab` slots, 2^12 pair entries, 2^10 `plog` entries (ISA: 2^18 / 2^19 / 2^20 / 2^18 / 2^17 / 2^16). `-Drlvm.machine.sizes=isa` restores the ISA sizes, six numbers set others | CPU time. A declaration makes about 860 records (Init); with 1-2 MiB tables every probe was a cache miss. Native image, arena container, one worker, Init / Cedar slice / Std: ISA sizes 13.54 / 3.98 / 21.79 s, 2^12-based 10.11 / 3.84 / 16.18 s, 2^14-based 9.27 / 3.70 / 15.80 s, 2^16-based 9.26 / 3.74 / 15.39 s. M1 had measured the direction and requested it |
| I2 | 1.6 "Reset is O(used)" | A table of base size is cleared by one `Arrays.fill` when it has at most 32 slots per entry; otherwise by the per-record probe of the ISA. Still O(used) | a fill is sequential; the probe recomputes each record's hash and is a random access |
| I3 | 1.6 "the arrays return to the base size at the next reset" | The record slab and the run slab keep the capacity they grew to, up to the ISA base sizes (2^18 records, 2^20 run words = 12 MiB per worker); only larger slabs return to the base. `Machine.Sizes.keepRecords / keepRunWords` (0 in `Sizes.tiny()`) | slabs are filled by a bump pointer: capacity costs no locality, and shrinking them made every larger declaration copy them again |
| I4 | 13.3 (`Hash`, frozen by M6) | `Hash.record` has three multiplications instead of six (the form M1 measured in `M1ProbeBench.hashAlt`), `Hash.fin` one instead of two | M1's request: 67 -> 46 instructions per probe on a hit. Only table positions change; identity does not depend on the hash |
| I5 | 1.6 | Intern tables that grew are pooled by size (up to 2^20 slots each for `vtab` and `rtab`), zeroed when they are released, and reused by the next declaration that grows; `reset()` swaps the grown table for the base one. Invariant: every pooled table is all zero (`Machine.tablesClean()`, `MachineStateTest.pooledTablesAreZeroAfterGrowthAndReset`) | no allocation per larger declaration. Measured: no CPU change (within 1-3%), JVM peak RSS on Init 866 -> 590 MiB |

What the measurements say about ISA 1.6 and the staging table (details in the log):
- the session cap of warm sessions should be about **4 MiB**, not 16 (16 MiB is slower than no warm session);
  `-X session-mib=4` sets it, the driver's default is still 16 MiB;
- the platform of the machine is the **CE native image built with `-O3`** (`scripts/build.sh --opt 3`): 10% less CPU than
  `-O2`, and 35-60% less than the JVM launcher on Init, Std and the Cedar slice, whose C2 warm-up costs 3.7 s of
  compiler time and is paid once per worker thread in slow code.

Tests changed: `MachineStateTest.footprintIsBytesInUseNotCapacity` and `M6FacadeTest.footprint...` asserted
"capacity is 8 MiB" literally; they now assert that the capacity is many times the bytes in use (the property
under test: the footprint is not the capacity).
