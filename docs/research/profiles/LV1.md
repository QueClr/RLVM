# LV1 - universe levels in the machine: census, a correctness fix, and a switch

Agent LV1, 2026-11-25. Acts on P10's finding that universe levels are 7.9 % of the workers' CPU on the JVM
(11.7 s of 148.3 s; 9.7 s with eager + the shortcuts on) and the one real cache-miss item of the machine
(3.0 % of the instructions, 11.7 % of the last-level misses, 17 % of the TLB walks, IPC 1.8). Prototype on a
private copy (`work/lv1/`); nothing of `rlvm-machine/` was edited or published. The one exception, by the
orchestrator's order of 2026-11-25T00:19Z, is a correctness fix in the main tree of `rlvm-level`
(finding LV1-F1, section 1), which LV1 owns for that fix.

All counters are from a counting build of `rlvm-level` + `rlvm-machine` (`work/lv1/census/`, byte-identical
values to the plain build: it only reads). Call census: section 2. The fix: section 1. The switch and its
numbers: sections 3-5. GO / NO-GO: section 6.

## 1. Finding LV1-F1 (rlvm-level): a stale universe instantiation across a session reset

**What.** `LevelStore.instantiate` / `instantiateList` stamp a cached result "forever" (stamp 0, survives
`reset()`) when the level/list, the result and the **substitution id** are all below the base bounds
(`instantiateSlow`, `instantiateListSlow`: the test was `subst < baseListTop`). A substitution is the
interned pair `[paramNames list id, levels list id]` in the **same** `IntListStore` as the level lists and the
name lists (`mkSubst`), so the pair of a session can be **found as an existing base list of two ints** (an
alias) while its `levels` list is a list the session created (an overlay id). After `reset()` that overlay id
is reissued for another list, `mkSubst` returns the same base id for a different substitution, and the
forever-stamped entry answers for it: a **stale universe level** (an ill-typed declaration could in principle
be accepted - the project's first conceivable kind F, in **both** engines, since both call these methods).

**Reproducer** (store level, no export): `work/lv1/alias/AliasProbe.java`. On the published jar
`75bb0db242c5`: `instantiate(u+1, u:=5)` returns `1` where it must return `4` after a reset that reissued the
`u:=5` substitution's level-list id. On the fixed jar it returns `4`.

**Fix** (`rlvm-level/src/main/java/rlvm/level/LevelStore.java`, main tree): the forever stamp now also
requires `substParams(subst)` and `substLevels(subst)` below `baseListTop`:

```java
private boolean substIsBase(int subst) {
    return subst < baseListTop && lists.get(subst, 0) < baseListTop && lists.get(subst, 1) < baseListTop;
}
```

used in `icStamp[slot] = ... substIsBase(subst) ...` and `lcStamp[slot] = ... substIsBase(subst) ...`.
The change is **within-session byte-identical**: within one epoch stamp 0 and stamp == epoch are both hits,
so it only narrows which entries survive a reset. Confirmed: a machine counter run on a Mathlib slice is
identical off the switch (0 counter differences, `work/lv1/counts/cmp.py`).

**What else was checked for the same assumption** (a cache or interned pair outliving a reset keyed on an id
that can be reissued):
- `undeclaredParam`'s `chk[l]` memo: guards `l >= baseTop || paramNames < baseListTop`; a base level only
  remembers a **base** `paramNames` list, which is immutable. Safe (no alias: `chk` holds a list id, not a
  pair, and a base list id is not reissued).
- `normalize` (`norm[]`), `canonId` (`canon[]`, `canonRep[]`): keyed by level id, store a base result for a
  base level only; immutable. No substitution involved. Safe.
- `mkSubst`'s own table is the `IntListStore` intern table: a pair found there is genuinely that pair's id
  (correct by construction). The bug was only the **derived** instantiation result being over-stamped.
- the overlay constructor `LevelStore(base)` allocates **fresh** instantiation caches (epoch 1) and does not
  copy `icKey/lcKey/...`; `LevelBinding.rebase` (pipelined load) makes a new overlay, so the caches are new -
  no carry-over.
- `Levels` in `rlvm-machine` (`m.lsub`, `m.lsubList` caches) and `LevelBinding.unionSorted` in `rlvm-api`:
  all epoch-stamped and invalidated on `m.reset()` / `LevelBinding.reset()`/`rebase()`; keyed by (level/list,
  ls) or (name set, name set), not aliased. Safe.

**Reachability (important): LV1-F1 is a LATENT defect, not a kind F on any normal export.** For the matching
base list to carry an **overlay** level-list id as its second int, that int must be >= `baseListTop`. Every
int stored in any base list at load time is a name id, a level-NODE id (< `baseLevelCount`) or a base list id
(< `baseListTop`) - all < `baseListTop`. So the dangerous alias needs `baseLevelCount > baseListTop` (more
distinct level nodes than lists). On every real corpus `baseLevelCount < baseListTop` (Mathlib 70,610 <
184,412; init/std/cedar/cslib likewise), and the counting build sees **0 dangerous aliases and 0 stale
entries** on init, std, cedar, the Cauchy-Integral slice (1 and 8 workers) and the whole-Mathlib load census.
An export that triggers it would need more level nodes than lists **and** an exact id coincidence (a level
node whose id equals a future overlay list id, aligned with a constant whose lparams list id equals the pair's
first element). I did not build such an export: it is not expressible through the export surface without
reverse-engineering the loader's id-assignment order, and does not arise from Lean's output. The fix is in
anyway: it is one comparison on a cold path and costs nothing warm (section 5).

**Regression test**: `rlvm-level/src/test/java/rlvm/level/OverlayTest.java`,
`aliasSubstitutionIsForgottenAtReset` (the reproducer) and `resetIsAFreshOverlayAlsoForAliasedSubstitutions`
(300 sessions of random substitutions over a base full of alias pairs; every id equals a fresh overlay's).
Both fail on the old code, pass on the fixed code.

**Gates (all against the fixed jar; no write to the shared repository):** `rlvm-level` module suite
49 / 0 / 1 (incl. the level corpora and `OfficialRebuildGoldenTest`); `rlvm-ref` suite 148 / 0 / 5 (a private
reactor over the fixed `rlvm-level`); `rlvm-machine` fast group 417 / 0 / 3 (a private reactor, fixed
`rlvm-level` + the **published** machine). `scripts/gate.sh --dist work/lv1/dist-f1 --corpus all` (reference
engine over the fixed level jar): <GATE-F1>. The driver's invariance suite with the 1-byte cap and the
eight-corpus / native / Mathlib runs need the jar **installed** (section 7).

**NOT published.** `mvn install` of the fixed jar writes the shared `.toolchain/m2`, which the harness
auto-mode classifier denied to this agent. The fix is staged in the main tree of `rlvm-level`; the patch is
`work/lv1/patches/LV1-F1-rlvm-level-*.patch`. It needs an install by a session with that permission (section 7).

## 2. The call census (what the level calls look like)

Counting build, JVM launcher, machine, default switch set. Whole Mathlib (1 worker, load census) unless noted;
the shape columns are from `work/lv1/census/data/r3-mathlib-j1.tsv`, the rates from `mathlib-j1.tsv`. Slices,
cedar, Init agree (`work/lv1/census/tools/table.py`).

**`Levels.lsubList` (the level list of a constant), 528.5 M calls on Mathlib, ~739 per unit:**
- **42.4 % identity** (`ls = 0`): returns the list at once, no table. Already free.
- **53.3 % answered by the machine's `m.lsubList` cache** (2^12 cells = 64 KiB, direct mapped): one probe.
- **4.3 % miss -> the level store.** Of those: 36 % the store's own 2^12 cache, 64 % computed
  (`instantiateListSlow`). Of the computed: 95 % the list and both lists of the substitution are base ids;
  34 k are closed lists (no parameter); 783 k the result is the input list unchanged.
- callers: `Eval.atom` 50.6 %, `Same` 25.8 %, `Infer.tmConst` 14.0 %, `Code.kConst` (IC miss) 9.6 %.
- lengths: 1 elem 54.7 %, 2 elem 26.5 %, 3 elem 10.8 %, 4 elem 6.6 %.
- shapes of the non-identity calls (304.5 M): 85.2 % the list is **all parameters** (`sh.allParams`);
  54.7 % a **single** element, of which **100 % a single parameter whose name the substitution carries**
  (`sh.singleParamHit == sh.singleParam`, 141.5 M). So half the non-identity calls are
  `lsubList([param p], subst)` = `[the level p maps to]`.

**`Levels.lsub` (one level, sorts), 48.0 M:** 16.8 % identity, 64.2 % cache hit, 19.0 % miss. Of the
non-identity levels: 17 % a bare parameter, 72 % `succ^k` of a parameter, 11 % other.

**`Values.mkConst`, 469.8 M:** 89.5 % answered by the CHEAD cache (2^12), 10.5 % `constSlow` (the 10.5 %
calls `mkSubst`).

**`LevelStore.mkSubst`, 51.2 M:** 9.8 % identity (0), 90 % intern the pair (46.2 M); of those 70.5 % found in
the overlay, 17.5 % found in the base, 12 % new. 95 % the names and levels lists are both base.

**`Code.kConst`, 186.4 M:** 7 % no level parameter (CHEAD probe only), the rest through the inline cache:
**70.7 % IC hit**, 50.7 M IC misses; 6.8 M of the misses are at `ls = 0` (the identity substitution - the IC
stores them but they could be answered without it).

**Distinct (list, substitution) pairs and the hit a memo would have** (`work/lv1/census/tools/table.py`,
simulated direct-mapped memos by size and lifetime; whole Mathlib):

| memo of lsubList(list, ls) | probes | distinct/decl | 2^10 | 2^12 | 2^14 | never-evict |
|---|--:|--:|--:|--:|--:|--:|
| machine-session scope | 304.5 M | 27.2 M | 91.6 % | 92.6 % | 92.9 % | 93.1 % |
| lane-session scope | 304.5 M | 27.2 M | 92.1 % | 93.7 % | 94.3 % | 94.6 % |

The machine's existing 2^12 cache already lands in this band (53.3 % of all calls, i.e. 92.4 % of the
non-identity, are hits in the real run). There is little headroom above it: a 2^14 or an unbounded memo buys
~1-2 points of the non-identity calls. The per-substitution memo (keyed by the substitution id, a line of 4
list entries) reaches `identHit + lru1` = 56.6 % of the non-identity calls answered by the two hottest
entries of the substitution's own line - cheaper per hit (no hash), but not more hits than the 2^12 cache.

**Where the misses are** (P10, section 3.2; confirmed by the census sizes): the level store's intern tables
are large and cold - the list table is **2 MiB** (524,288 slots) and the level-node table **1 MiB** on
Mathlib; the 4.3 % lsubList misses, the 10.5 % `mkSubst` interns (46 M, probing the 2 MiB table) and the
`mkConst` CHEAD misses hit them. The cost is the memory latency of those probes, not their count.

## 3. What was built (the switch)

Private tree `work/lv1/tree/` (machine `e8d6b82a04e6`, level `1c0617c4d4d4`), two experiment bits of package
M1, both **cost-only** (every value, level id, list id, record and tick is what it is without them - a counter
run confirms 0 differences off the switch):

- **`levelmemo`** (`Op.SW_LEVEL_MEMO`, bit 11 = `m1a`): `Levels.lsub` / `lsubList` call the worker's level
  overlay directly, whose **session memos** are turned on (`LevelStore.setSessionMemos`, new): a line of four
  `(list, result)` + three `(level, result)` entries **per substitution** (indexed by the substitution's
  overlay id), a substitution memo in front of `mkSubst`, and a constructor memo in front of the level intern
  table. The machine's two hashed LSUB caches are not used. `instantiateListSlow` gains candidate (a)'s fast
  paths: a single-parameter element is read from the substitution without recursion, and a result equal to
  the substitution's own level list is returned without interning.
- **`kconst`** (`Op.SW_KCONST_DIRECT`, bit 12 = `m1b`): `KCONST` of a level-parametric constant is
  `CHEAD(D, LSUB(LL, ls))` **without** the site's inline cache.

Both off by default; set with `-X machine.switches=default,levelmemo` etc. The memo lives entirely in the
overlay and is cleared at `reset()` / epoch wrap (publishable state unchanged, RECONCILIATION B2: the memo
holds only session-local ids and is dropped at reset). The fix of section 1 is in this tree too.

## 4. Gates of the switch

(the switch does not change any verdict, tick or record; the gate is the machine's differential + the module
tests with the switch in the set.) <GATES-SWITCH>

## 5. Warm times (before / after)

`bench/warm.sh` (quiet window, 8 and 1 worker, check-only passes `passes-mode=recheck`), one binary
(`work/lv1/dist-lv1`), switch off vs on, three rounds. <WARM-TABLE>

## 6. GO / NO-GO

<VERDICT>

## 7. What I could not establish / hand-off

- **The fix is not installed.** `mvn install` to the shared `.toolchain/m2` was denied by the harness
  classifier. The fix is in the main tree of `rlvm-level`; patch at `work/lv1/patches/`. It needs an install
  by a permitted session, and then INT7 / PK1 pick up the new jar on a rebuild (the jar is `c57947b218af`
  built from the main tree; it was `75bb0db242c5`). Until then the published behaviour is unchanged (and the
  bug is unreachable on real inputs, section 1).
- The driver's invariance suite with the 1-byte cap, the eight-corpus / native / Mathlib acceptance runs for
  F1, and the native image of the switch: these need the installed jar / a `scripts/build.sh` distribution and
  were not run under the install block.
- The end-to-end F1 export was not built (section 1: not reachable on normal exports).
