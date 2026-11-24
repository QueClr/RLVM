# rlvm-level — universe levels

Package `rlvm.level`. Hash-consed universe levels as `int` ids in flat arrays, the official level
algebra of the Lean 4 kernel (`refs/lean4/src/kernel/level.cpp`) ported operation by operation, and a
complete (semantic) comparison on top. No dependencies.

| class | role |
|---|---|
| `LevelStore` | everything: levels, lists, substitutions, official and complete operations, freeze / overlay |
| `IntListStore` | interned `int` sequences (level lists, parameter name lists, substitutions) |
| `NameOracle` | what the name store must provide: Lean's name order and printing |
| `LevelLimitException` | depth above 2^24-1 (the official kernel panics there): reject the input |

## Ids

- Level id 0 = none, `ZERO` = 1, `ONE` = 2 in every store; other ids dense, in creation order.
- Equal structure <=> equal id (the official `==`). Kinds are numbered like the official
  `level_kind`: `KIND_ZERO 0, KIND_SUCC 1, KIND_MAX 2, KIND_IMAX 3, KIND_PARAM 4`.
- `param(nameId)`: name ids are opaque; the caller guarantees "same Lean name <=> same id".
- Lists: id 0 = empty list. A substitution is an id from `mkSubst`; 0 = identity.

## API (all on `LevelStore`)

| group | methods |
|---|---|
| raw constructors (exact structure, what the export contains) | `zero succ max imax param succN ofNat` |
| official smart constructors | `mkMax mkIMax` |
| accessors | `kind lhs rhs succOf paramName depth hasParam hasIMax isExplicit offsetBase offset` (`to_offset` in O(1)) |
| zero tests (official, and exact) | `isZero` (syntactic), `isNeverZero` (= `is_not_zero`), `isAlwaysZero` (= `normalizes_to_zero`, the "is Prop" test) |
| official comparison | `normalize`, `isEquivOfficial`, `isGeqOfficial` |
| complete comparison | `canonId`, `canonRep`, `isEquiv`, `isGeq`, `isLeq`, `listsEquiv` |
| lists | `internList listLength listGet lists() listHasDuplicates listToString` |
| instantiation | `mkSubst(paramNames, levels)` (-1 = wrong number of levels: reject), `substParams substLevels`, `instantiate(level, subst)`, `instantiate(level, paramNames, levels)`, `instantiateList(list, subst)` |
| declared parameters | `undeclaredParam(level, paramNames)` (0 or the offending `param` level), `paramsDeclared`, `undeclaredParamInList` |
| printing | `toString(level)`, `appendTo` (`max (u+1) v`, `imax u v`, `(max u v)+1`) |
| freezing | `freeze isFrozen newOverlay reset isOverlay size baseSize listCount baseListCount` |

Raw constructors validate their operands (`IllegalArgumentException`); accessors and comparisons do
not (hot paths). Any `Throwable` from this module must be treated as "reject".

## Official versus complete, and where identity of normal forms is enough

- `normalize` is the official normal form. It is **not canonical** under `imax`, so
  `isEquivOfficial` (`a == b || normalize(a) == normalize(b)`) and `isGeqOfficial` are sound but
  incomplete. Examples, all taken from the answers of leanchecker v4.36.0:
  `imax u 1` vs `max u 1` rejected, `imax u (v+1)` vs `max u (v+1)` accepted,
  `imax u v` vs `imax (imax u v) v` rejected, `is_geq((imax u v)+1, imax u v)` false.
- `canonId(l)` is a canonical form of the *denotation* (Géran's sublevel form, also used by
  lean4lean; the argument is in the comment above `canonId` in `LevelStore.java`): for ids > 0,
  `canonId(a) == canonId(b)` **iff** `a` and `b` are equal under every assignment. So for the
  semantic relation identity of canonical forms is necessary and sufficient, and since the official
  relation is sound, everything the official kernel accepts has equal `canonId`s.
- The one exception: `canonId` is `CANON_UNKNOWN` (-1) for a level with more than 64 distinct
  parameters, more than 256 sublevels, or more than 4096 visited nodes. No level of init, std, cedar,
  cslib or mathlib comes near (largest: 19 parameters). Callers do not need their own fallback:
  `isEquiv` / `isGeq` then decide that question with the official procedure. A caller that compares
  `canonId`s or `canonRep`s directly must treat -1 as "unknown" and call `isEquiv`.
- `isEquiv` and `isGeq` therefore accept a superset of the official functions.
- **Which family a kernel uses** (project owner's decision of 2026-11-24, OWNER-NOTES item 11,
  "resemble official by default"): both engines and inductive admission compare levels with the
  `...Official` functions by default, so that RLVM rejects where the official kernel rejects
  (`corner-cases/imax-right-successor`, `imax-nested-absorption`). The complete relation is an
  opt-in: `-X levels=complete` for the reference engine (`rlvm-ref`), the switch `levels-complete`
  for the machine. `isEquiv` / `isGeq` / `canonId` stay the tools for everything that is not a
  verdict of the official kernel (canonical representatives, tests, diagnostics, the opt-in).
- `isNeverZero` and `isAlwaysZero` are the official syntactic tests and are semantically exact.

Structure matters in two places, both ported exactly: `mkMax`/`mkIMax`, and `instantiate`, which
rebuilds changed `max`/`imax` nodes through them (`imax u v [v := w+1]` = `max u (w+1)`,
`[v := 0]` = `0`) and returns an unchanged node as is.

**Pointer equality.** The official `instantiate` keeps a node when its operands come back
*pointer*-equal; here "pointer-equal" is "structurally equal". The two agree whenever structurally
equal levels are one object, which holds for levels read from a lean4export file. They differ when
the official kernel substitutes freshly allocated parameter objects, as `add_inductive` does: there
it rebuilds `imax u 1` into `max u 1` and then fails its own `is_equivalent` on the two spellings
(observed: an inductive type with a parameter of sort `imax u 1` is rejected by leanchecker v4.36.0
with "application type mismatch"). This module returns the level unchanged, which is semantically
the same level.

The rule behind that rejection, pinned against the real kernel by `OfficialRebuildGoldenTest`
(1,600 answers of leanchecker v4.36.0 in `official-golden-rebuild.tsv`, 170 of them rejections):
`add_inductive` refers to the block's types, constructors and recursors with parameter objects it
makes itself (`lparams_to_levels`), and since v4.36 it type-checks the recursor it generated
(`check_recursors`). The type of each such reference is the declared type with **every level node
above a parameter rebuilt** bottom-up (`succ` as it is, `max` through `mkMax`, `imax` through
`mkIMax`, a level without parameters untouched), and the official kernel compares it with the
declared spelling. It accepts the declaration iff `isEquivOfficial(L, rebuild(L))` for the levels `L`
in the positions it compares (binder domains of the block's types and constructors): all 1,600
answers follow this rule, for a level in a parameter's sort and for a level argument of a constant
in a constructor field. The rebuild changes the spelling of 1,188 of the 1,600 levels and breaks the
official equivalence for 170. `rebuild` needs nothing new from this module (`hasParam`, `kind`,
`succOf`, `lhs`, `rhs`, `succ`, `mkMax`, `mkIMax`; the test has it in a dozen lines). RLVM's inductive
admission does not apply it today, so it accepts those 170 declarations where the official checker
rejects them (reported to its owner, 2026-11-24); no declaration of a real export is affected, since
the official kernel accepted them all.

**Name order.** `normalize` sorts parameters with `NameOracle.compareNames`, which must be Lean's
`name::cmp`. It matters for exact agreement: with the order reversed, 35 of 8,189 official answers in
the golden set change (never unsoundly). `isEquiv`/`isGeq` do not depend on it.

## Frozen base and overlays

```java
LevelStore base = new LevelStore(nameOracle);   // loader thread: raw constructors, internList
base.freeze();                                  // normal forms + canonical forms of every level
LevelStore w = base.newOverlay();               // one per worker thread
... w.instantiate(...), w.isEquiv(...) ...
w.reset();                                      // session boundary: forget everything w created
```

- Base ids keep their meaning in every overlay; new ids start at `baseSize()` / `baseListCount()`.
- A frozen store is immutable: accessors, lookups of existing objects and memoised answers work from
  any thread; anything that would create an object throws `IllegalStateException`.
- `reset()` costs time proportional to what the overlay created. Memo slots of reissued ids are
  fresh; cache entries that involve overlay ids die with an epoch counter; entries made only of base
  ids survive.
- **Deviation from the design frame, on purpose:** an overlay does not probe a shared base and then
  its own table. It works on a private copy of the base arrays and hash table, made once in
  `newOverlay()` (0.5-1.9 ms for mathlib). Hot paths then have one array and no "base or overlay?"
  branch, and workers share no memory at all after creation. The cost is memory per worker
  (estimated at a few MB for mathlib, not measured) and that overlays must be reused, not created
  per declaration. If the architects want the shared-base variant, the public API does not change.
- `freeze()` adds the official normal forms to the store so that `normalize` of a base level is a
  memo hit in overlays: mathlib grows from 48,248 to 70,610 levels (37 ms).
- Not supported: partial truncation (marks), a base that grows while workers run.

## Limits

- Depth above 16,777,215 throws `LevelLimitException`.
- Operations recurse on the `max`/`imax` nesting depth (not on `succ` chains). Hostile nesting can
  end in `StackOverflowError`; that is fail-closed but **not tested**.
- The instantiate caches are direct-mapped with 4096 entries each and lossy.

## Tests

`. ./env.sh && $MVN -o -q -pl rlvm-level test`: 47 tests in 8 classes, 0 failures, 1 skipped
(`BasicsTest.depthLimitOfTheOfficialKernel`, 16.7 M nodes; it passed when run once with
`-Dtest=BasicsTest -Drlvm.level.heavy=true`).

| class | what |
|---|---|
| `OfficialPortTest` | randomised differential tests against `RefLevel`, a line-by-line tree port of level.cpp in the test tree: flags, `mkMax`, `mkIMax`, `normalize`, `isEquivOfficial`, `isGeqOfficial`, `instantiate`, `undeclaredParam` (about 240,000 random cases) |
| `SemanticsTest` | brute-force evaluator over all assignments to small naturals: `isEquiv`, `isGeq`, `canonId`, `canonRep`, zero tests agree with the semantics in both directions; official operations never contradict it; `instantiate` is substitution |
| `OfficialGoldenTest` | answers of the real official kernel (below) |
| `OfficialRebuildGoldenTest` | the official verdict on 1,600 inductive declarations whose levels `add_inductive` rebuilds (see "Pointer equality"): accepted iff `isEquivOfficial(L, rebuild(L))`, on `LevelStore` and on `RefLevel`; `instantiate` under the identity substitution keeps the declared spelling |
| `CuratedCasesTest` | the cases of docs/research/02 section 3.6, `bugs/level-imax-leq`, `bugs/level-imax-normalization`, `bugs/large-elim-param`, `bugs/proj-of-imax-prop`, the two imax corner cases, tutorial 015-027 |
| `OverlayTest` | freeze, overlay ids, reset, reissued ids, cache and memo validity across reset, growth, 8 concurrent workers plus 2 readers of the frozen base |
| `RealExportTest` | level records of the 230 arena tests (1,554 records) and of init, std, cedar, cslib, mathlib |
| `BasicsTest` | ids, hash-consing, lists, substitution ids, printing, the `CANON_UNKNOWN` fallback, depth limit |

**Official golden set.** `bench/modules/rlvm-level/gen_golden.py` asks `leanchecker --from-export`
(v4.36.0) 11,128 questions, one generated export each: 4,358 `is_equivalent`, 3,831 `is_geq` (through
the constructor-field universe check of an inductive type), 2,939 `instantiate` followed by
`is_equivalent`. `LevelStore` and `RefLevel` reproduce all 11,128 answers. The set is deliberately
rich in true-but-hard questions: the official kernel says no to 1,982 semantically true equalities
and 183 true orderings in it; `isEquiv`/`isGeq` agree with the brute-force semantics on all of them.
128 further generated `is_geq` questions are not in the set because the official kernel fails earlier
(see "Pointer equality").

**Mutation check** (done by hand, not part of the build): 24 single bugs injected into
`LevelStore`/`IntListStore` (dropped offset around `imax`, wrong `imax` case in `is_geq`, unguarded
`imax` products, cache stamps, memo slots, table cleanup on reset, name order, ...); 23 made the
suite fail, 1 was an equivalent mutant (it only changes which of two identical entries is kept).

Limits of the tests: for levels with more than 7 parameters (mathlib has some, up to 19) the semantic
check samples 3,000 assignments instead of enumerating; hierarchical and numeric parameter names are
compared with a test-side implementation of `name::cmp` written from `name.cpp`, only
single-component ASCII names were checked against the official binary; concurrency is covered by one
test, without a race detector; native-image compatibility is by construction, not built.

## Real data

`RealExportTest` on slices made by `bench/modules/rlvm-level/extract_levels.py` (in
`work/rlvm-level/`); every level has a canonical form, the reference port gives the same normal form,
and the classes are exactly the semantic ones.

| export | level records | with imax | max depth | max params | semantic classes | classes the official normal form splits |
|---|---:|---:|---:|---:|---:|---:|
| init | 571 | 178 | 10 | 7 | 375 | 35 |
| std | 953 | 226 | 10 | 7 | 572 | 43 |
| cedar | 1,029 | 238 | 10 | 7 | 641 | 41 |
| cslib | 12,713 | 1,031 | 15 | 9 | 3,858 | 151 |
| mathlib | 48,246 | 3,542 | 24 | 19 | 14,638 | 489 |

A split class means: the export contains two spellings of the same level that the official kernel
would not identify if it ever compared them. `normalize(normalize(l)) == normalize(l)` held for
every level. Universe parameter names are not all simple: mathlib's include multi-component and
numeric names.

## Instruction counts

`bench/modules/rlvm-level/run_bench.py` (needs `$MVN -o -q -pl rlvm-level test-compile` first) runs
`rlvm.level.LevelBench SCENARIO N` in `bench/arena-docker.sh --arena-limits bench/measure.sh` for two
values of N and reports `(instr(N2) - instr(N1)) / (N2 - N1)`, all threads counted. aarch64, GraalVM
CE 25 JIT, default flags, synthetic small levels, on an overlay. "over ref" is the difference to the
`baseline` loop, whose body adds the two operand ids; so a value near 0 means "as cheap as that add
(about 2 instructions)", not "free". Repeatability about +-0.1 (first group), +-0.3 (second),
+-1.5 (cold). Raw data: `work/rlvm-level/bench.json`.

| operation | instr/iteration | over ref |
|---|---:|---:|
| baseline loop | 14.9 | |
| `isEquiv(l, l)`, identical ids | 14.8 | -0.2 |
| `instantiate(l, identity)` | 15.0 | 0.1 |
| `isEquiv`, different ids, canonical forms memoised (true / false) | 31.5 / 31.6 | 16.6 / 16.7 |
| `isEquivOfficial`, different ids, normal forms memoised | 29.4 | 14.5 |
| `normalize` / `canonId`, memo hit | 20.9 / 21.0 | 6.0 / 6.1 |
| `instantiate(closed level, subst)` | 20.7 | 5.7 |
| `instantiate(l, subst)`, cache hit | 41.5 | 26.5 |
| `instantiateList(list, subst)`, cache hit | 36.5 | 21.6 |
| `undeclaredParam`, memo hit | 26.5 | 11.6 |
| `mkMax` / `mkIMax`, result exists | 115.8 / 118.1 | 100.9 / 103.2 |
| `isGeq` on two stored canonical forms | 471.9 | 457.0 |
| build a new small level (1-2 new nodes), reset every 16 | 398.2 | 383.3 |
| + `normalize` of it (cold) | | 751.7 |
| + `canonId` of it (cold) | | 1,877.3 |
| + `isEquiv` with a second new level (cold) | | 2,657.1 |
| two `instantiate` cache misses | | 1,175.5 |

Wall / CPU time of the longer run of some scenarios (same container): `isEquiv` identical ids,
2.5e9 iterations: 1.20 s / 1.20 s; cache-hit `instantiate`, 2.5e9: 4.35 s / 4.35 s; cold `canonId`,
1e8: 7.71 s / 8.58 s. On real exports (host, wall): mathlib levels load 31-61 ms, `freeze()` 37 ms,
`newOverlay()` 0.5-1.9 ms.

## Known gaps

1. The cold canonical form is the expensive path (about 1,900 instructions for a new small level,
   2.5 times the official `normalize`). There is no fast path for `imax`-free levels. How often a
   checker reaches it is unknown until a kernel exists.
2. Overlay = private copy of the base (see above); per-worker memory not measured.
3. No marks / partial truncation; no growing base.
4. Deep-nesting behaviour (`StackOverflowError`) untested.
5. `new LevelStore(names, expectedLevels)` sizes the list stores proportionally to
   `expectedLevels`; fine for real exports, wasteful for huge values.
6. `canonRep` is "the first member of the class whose form was computed", not a normalised term.
7. `target/surefire-reports/` still holds the report of the removed placeholder `SmokeTest`.
