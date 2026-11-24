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

## 3. Wave 6 (integrator of wave 6, 2026-11-24 17:57Z - 21:1xZ): the default switch set; default engine for 54 minutes

**Outcome.** The machine was made the default engine at 19:41Z on the gates of section 3.1 of the log and was
taken back at 20:35Z by the orchestrator: E1's hostile exports show wrong accepts against the official checker
(E1-F1 .. E1-F5: a bound variable typed by what its slot holds instead of by its binder's declared type, an
expected type kept as a lambda's own), three of them in v0. `MachineProvider.priority()` is 10; the default
SWITCH SET below, the cap and the measurements stand for `-X engine=machine`. The integrator's mistake to
record: every gate it ran compares the machine with the reference engine (or with exports written before this
family was known), so "0 differences on 760,839 units" said nothing about verdicts that need a declared type to
differ from a convertible one; a review with NEW hostile exports belongs before a default-engine change, not
after it.

The log with every run, count and command is `work/status/machine-integration.md`, heading "Wave 6"; the list of
switches with the gate and measurement of each is `SWITCHES.md`; tools and outputs are in `work/int6/`.

### 3.1 What the integrator changed

- **Wiring.** `rlvm-truffle` is a module of the parent pom and its jar is in every distribution (`scripts/build.sh`:
  tree builds have it as a reactor module, `--published` builds take the published jar). The tier runs only under
  `t2`, which is off. `MachineProvider.priority()` was 100 from 19:41Z to 20:35Z (without `-X engine=` a distribution
  then checks with the machine; the reference engine re-checks every rejection and decides what the machine
  declines) and is 10 again (the outcome above). Nothing in `rlvm-main` has to change for either state
  (`Engines.DEFAULT_ENGINE_MIN_PRIORITY`).
- **Default set.** `Op.SW_DEFAULT` = `warm,gated,expect,bodies,pchk,match,joint,code,casetrees,lazycases,directrules,
  lazyeval,same`; `bodies` / `directrules` / `lazycases` are the new names of `m2a` / `m3a` / `m7a` (same bits; the old
  names are read). Cap 1 MiB, host depth bound 128: unchanged, both re-measured with the new set.
- **M8-F1** (`Code.kval`, `Code.resume`, `Code.kvalFill`; deviation from the letter of ISA 4.2, which has no frame form
  of `KVAL`): the frame of an activation that waits for the value of a `KVAL` is `ACT[blk, pc + 2, DST_KVAL |
  DST_KVAL_LS? | reg, ..]`, and the resume stores the value into the register AND into the inline cache cell of the
  site `(blk << 12 | pc, key)`, exactly the words the unsuspended path writes. Without it the tick count was a
  function of the host depth bound (the cache was empty after a suspended evaluation, and under `gated` the closed
  term is not tabled, so the next execution paid its ticks again).

### 3.2 What the gate's harness taught (test tree; relevant to anybody who runs several engines over one store)

Two pieces of state live in the STORE, not in the engine, and are therefore shared by every `MachineEngine` made
over it: the PCHK bits (`Store.facts()`, by design: workers share them) and the **binder hints** of the code store
(`CodeStore.addSub` writes the sub-block id of a binder into the node's reserved `c` word through
`TermStore.setBinderHint`; `CodeStore.hint` validates the id against the reader's own directory and answers 0 for a
foreign one, i.e. "walk the term"). One engine per store - the real pipeline - is unaffected. M8's gate runs up to
six engines over one store: lanes that elaborate in the same order assign the same ids and agree, an audit lane
(memo re-computations) does not, and its hints silently turned block activations of the other lanes into walks:
tick differences without outcome differences, different in every run. `M8Lanes` now runs the compared lanes before
the others and `M8Harness` gives every lane a PCHK table of its own. `-X passes=N -X passes-mode=recheck` (new
engines per pass over one store) has the same property in a milder form: a new engine finds stale hints, walks,
and overwrites them when it elaborates the parent again.

### 3.3 Kept and rejected, with numbers (native `-O3`, 8 workers, pipelined, `bench/quiet.py` windows; wall s / CPU s of a warm pass)

| Experiment | Init | Std | Cedar slice | reachability | cedar | mathlib | Decision |
|---|---|---|---|---|---|---|---|
| `warm,gated,expect` -> the new default set | 1.09 / 7.14 -> 0.81 / 5.26 | 1.84 / 12.59 -> 1.51 / 10.09 | 0.55 / 3.00 -> 0.46 / 2.40 | 2.49 / 7.37 -> 0.62 / 1.64 | 2.45 / 17.29 -> 2.09 / 13.99 | 30.68 / 215.7 -> 29.28 / 204.8 | KEPT (the default) |
| the set without `same` -> with it | 0.82 / 5.32 -> 0.81 / 5.26 | 1.52 / 10.32 -> 1.51 / 10.09 | 0.53 / 2.75 -> 0.46 / 2.40 | 0.61 / 1.60 -> 0.62 / 1.64 | 2.44 / 14.78 -> 2.09 / 13.99 | - | KEPT; reachability +2.5 % CPU in one row, inside the spread of two more readings (1.60, 1.55) |
| cap 1 MiB -> 512 KiB | 5.26 -> 5.22 CPU | 10.09 -> 9.92 | 2.40 -> 2.36 | 1.64 -> 1.58 | 13.99 -> 13.73 | 204.8 -> 202.6 (29.28 -> 29.20 s) | NOT ADOPTED: 1-2 % in one row each, wall equal; needs three rounds |
| cap 1 MiB -> 2 MiB / 4 MiB | 5.49 / 5.58 | 10.48 / 10.84 | 2.45 / 2.61 | 1.61 / 1.62 | 14.51 / 14.92 | 211.8 / 212.2 (30.55 / 30.26 s) | REJECTED |
| host depth 128 -> 512 / 2048 | - | - | 2.50 -> 2.53 / 2.43 CPU | 1.58 -> 1.64 / 1.66 | 14.13 -> 14.47 / 14.18 | - | REJECTED (no gain) |
| H10 inline lists (same jars, two interleaved rounds) | 5.38, 5.37 -> 5.29, 5.32 | 10.23, 10.19 -> 10.22, 10.06 | 2.50, 2.51 -> 2.39, 2.40 | 1.60, 1.55 -> 1.60, 1.59 | 14.01, 14.25 -> 13.91, 13.97 | - | MEASURED, not wired into the build (SWITCHES.md) |
| the Truffle tier (`t2`) | | | +38 % wall (T3, JVM, 8 workers) | parity | +39 % | | OFF |

Not built in this pass (and why): X1's `once` filter for EVAL tabling (measured only against sets without `bodies`
and the K1 blocks; a change of `Eval` after the gates would have needed all of them again); an exact treatment of
eager reduction (the declined declarations are 0.2-0.9 % of the units and about a tenth of the workers' time on
mathlib and Std: the first item of "what is left").
