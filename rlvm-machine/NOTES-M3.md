# NOTES-M3 - reduction: delta, core, whnf, iota, quot, Nat, String (owner: M3)

Files: `Reduce`, `NatRed`, `StrRed`, `Rules`; tests `M3ReduceTest`, `M3NatStrTest`, `M3TierTest`, `M3NestTest`, `M3K2Test`
(fast), `M3ArenaTest` (slow); `M3Prof` (a main for profiling, not a test).
Specification: ISA 4.3 (4.3.2 - 4.3.5), 2.9, 4.4 (`OFFSET`), 3.6 (case trees, step 4); official rules R02 section 7
and 8.4. This file lists every deviation from `docs/design/ISA.md` (revision 2) with its reason, then what is
not done.

## Deviations

| # | Where | Deviation | Reason |
|---|---|---|---|
| D1 | `Reduce.whnfLoop`, `whnfDone` (ISA 4.3.4 "done") | m0 is written on `v`, on the last CORE result and on the CORE results of the **two** iterations before it, not on "up to 4 intermediate values"; the unfolded values between them (results of `DELTA`) get no WHNF memo | the loop method must stay below 325 bytecodes with four call sites (H10) and the frames WHNF[2..5] carry the intermediates as payload. A CORE result is what another chain most often starts from; a `DELTA` result is reached again through the UNF memo of its predecessor and the CORE memo of itself (two loads) |
| D2 | `Reduce.core`, `coreLoop`, `coreStep` (ISA 4.3.3) | CORE is a loop over single `whnf_core` steps (`coreStep`: force, projection, iota, quotient, PROJ head) instead of the recursive "`res = call CORE(r, cheap)`"; m1 is written on `v` and on the final result only. The loop's frame is CORE[6](v, mode, cur); the steps that call a sub-judgment have frames CORE[2..5] of their own, so a suspended CORE may be two frames | the official `whnf_core` calls itself in tail position; under convention H a recursive CORE would use one host level (and beyond D = 64 one frame) per head reduction. As a loop a chain of any length runs at constant depth: 50,000 iota steps write no frame (`M3NatStrTest.deepReductionNeedsNoHostStack`). The intermediate values are not memoised: each is reached again only through the value before it, whose memo answers |
| D3 | - | (withdrawn) WHNF used to look at flag-WHNF applications of two arguments with an AXIOM head, because the official `reduce_nat` matches the 14 binary Nat functions by name whatever their declaration kind (R02 7.6) and M1 flagged every axiom / opaque head WHNF. M1 took the flag rule (publication of 08:45Z: a constant with a binary nat-op code is ACTIVE and never WHNF), so the test is gone | `M3ReduceTest.natTableSmall` runs on the opaque stand-ins of the test prelude and fails if the flag rule is lost; `M3NatStrTest.invalidGuardDeclinesWhereTheExtensionWouldFire` covers the INVALID guard |
| D4 | - | (withdrawn) `NatRed.unsucc` is `Values.npeel` since M1 offers it | - |
| D5 | `NatRed.natop` | the result is an `rlvm.nat` handle; failure is `Nat.isError(r)` with `m.err = E_NATSIZE`, not "a negative value" (stub comment) | a number of 2^63 or more has the handle `Nat.BIG or id`, which is a negative `long` |
| D6 | `NatRed.guard`, all Nat / String instructions (ISA 2.9) | as ISA 2.9: VALID fires, INVALID is `fault(E_PRELUDE)`, ABSENT does not fire. Two refinements: `Bool.true` / `Bool.false` not declared is `fault(E_PRELUDE)` (the official kernel answers with a constant RLVM cannot represent; the reference kernel declines there too); `STRVIEW` needs all five constants VALID, ABSENT included, else `fault(E_PRELUDE)` | the reference kernel declines on every state but VALID (`requireValid`); ABSENT for a declared constant cannot occur behind the environment frontier, only in hand-built stores, where "does not fire" is what M1 does for `Nat.succ` as well |
| D7 | `Reduce.k_iota9`, `pushEtaArgs` (ISA 4.3.5 structure eta) | the eta-expanded major `I.mk params (w.0) ..` is not built; only its last nfields arguments are pushed (parameters of the type and `MKPROJ` records) | only those arguments enter the rule application (R02 7.4); one NEU record and one spine less per step. Same reduct |
| D8 | `Reduce.iotaRule` (ISA 4.3.5 "`call RULE(rec, j)`") | generic tier: the rule's right-hand side is evaluated with `EVAL(rhs term, empty, ls)` at every step (M2 tables it on the SUSP record) and applied to all arguments (parameters, motives, minors, fields, arguments after the major) in **one** `APPLY` | v0 has no code objects; one APPLY is the composition of the two of the ISA text and saves the intermediate value |
| D9 | `Reduce.deltaHead` | besides head class DEFN the declaration kind (DEFN or THM) and the level count are tested once per head; the substitution `-1` that M1 stores for a wrong level count also means "does not unfold" | robustness; R02 section 10 row 14 |
| D10 | `Reduce.iota` | the level-count test is made before K and before the major is normalised (as ISA 4.3.5 writes it); the official kernel makes it after (R02 7.4) | the structural checks of admission never pass such a constant, so the orders cannot be told apart; the early test avoids work |
| D11 | `NatRed.offsetFast` | two positive literals are decided inline by content | the official step compares the predecessors with `is_def_eq_core`, which for two literals is the comparison of their values (R02 8.2 "Lit"): exact |
| D12 | ticks | CORE, WHNF: one tick for the judgment and one per further iteration of its loop; DELTA, IOTA, QUOTR, NATFOLD, OFFSET: one tick when they do work (after their inline answers) | ISA 1.4: "one per generic frame started, one per iteration of the WHNF loop"; the CORE loop is this file's (D2) |
| D13 | `Rules.unfold`, `Rules.rules` | the delta step of the WHNF loop is `Rules.unfold(v)` = `Reduce.delta(v)` unless `SW_CASETREES` is on; then `Code.rules(v)` (M7's complete RULES judgment: tree, leaf, over-application) is tried first and NONE hands over to DELTA. The frame of the hand-over is RULES[1](v); the tree itself runs in M7's ACT frames | one place where the case trees of step 4 enter; M7 owns elaboration and execution of the trees (requests.log 08:58Z), so `Rules` holds no interpreter of its own. Nothing changes in v0 or in strict mode |
| D16 | `NatRed.nestRootSlow`, `nestPure`, `nestEval` (ISA 4.3 `NATFOLD`, 2.9; section 10 "reuse") | in a session whose numbers hold 16 MiB or more (`Reduce.Ext.nestMinBytes`), a fold whose operands are, all the way down, applications of the 12 number-valued binary functions to literals (or to values with a literal WHNF memo) is computed as plain arithmetic on `rlvm.nat` handles in a scratch `NatStore` of this package; only the root's result is interned in the session's store and becomes a value. Inner applications get no WHNF memo; shared sub-nests are computed once per scratch epoch (a lossy side memo of 1,024 slots); the scratch store is emptied at the next root once it holds more than 64 MiB. Anything that is not such a nest (an operand without a literal memo, a predicate inside, a guard that is not VALID, more than 4,096 nodes or 48 levels) is handed to the judgment before any arithmetic. Ticks: one per node evaluated | `perf/gf3-matrix-product` (CFSG's certificates): every intermediate 609,961-bit number of a matrix product became an NBIG record whose limbs stay until the session ends: 3.8 GB for three products, as in the official kernel's caches (the test's description: "a checker that keeps more intermediates than needed shows it here"); the machine declined under every unit budget below that and the driver checked the unit twice. With the nest path the unit is accepted in one attempt with 0.36 GB of numbers (the 4,669 root results) + at most 64 MiB of scratch (request of M6, 09:12Z / 09:46Z). Same numbers: the same functions on the same operands under the same guards; an error inside the nest is the `E_NATSIZE` the official kernel raises while normalising the operands. Below the threshold nothing differs from the plain judgment, and strict mode (the cold re-check, ISA 14.2) never takes the path (`M3NestTest`) |
| D15 | `Reduce.deltaBlock`, `iotaBlock` (step 4, `SW_CODE`) | DELTA asks `Code.blockFor(row, BODY)` at every unfolding that misses the UNF memo (that call is the rent-to-own counter of ISA 3.1, so there is no `uses[row]` in this package) and runs the block on the spine run; IOTA asks `Code.blockFor(rec, RULE)` and runs entry j on params + motives + minors + fields in scratch; the arguments after the arity / after the major are applied to the block's result with APPLY. A head without a block (not yet elaborated, proof-block value, spine shorter than the arity) takes the generic path | ISA 4.3.2 / 4.3.5 as written, with M7's entry points. Value ids are those of the generic walk (`M3TierTest.deltaAndIotaThroughBlocksGiveTheSameIds`) |
| D14 | `Reduce.projField` | an NSUC struct is treated as the application `Nat.succ x` (field 0 of structure `Nat` is the predecessor) | exactness of `reduce_proj_core` on values (R02 7.2); only an ill-typed projection can reach it |

| D17 | `Rules` K2 (ISA 3.6 "K2: derived trees"; RECONCILIATION section 2 "K2 and T2"), behind `SW_K2`, OFF by default | built as the class comment of `Rules` says, with four differences from the ISA text. (1) **Derivation variables are free variables of a reserved type** (`FVAR(T0, k)`, `T0` = a Nat literal record that no binder can have as its type), not records of kind 0. (2) A type-directed judgment on such a variable does not abandon the derivation by itself: it gets the answer "no" (`VTYPE` = the literal: not a proof, not unit-like, no eta, K and structure eta not applicable) or it fails (`ENSORT` / `ENPI` / a projection of the literal), and a failure (ABORT, or a FATAL that is not `E_MEM` / `E_CANCEL` / `E_AUDIT` / `E_STACK`) abandons the NODE that was being derived; a `U` of a probe inside K only means "K not applied"; no rule is made or used under eager reduction (`m.ER`), and a node whose derivation entered it is dropped. (3) Trees are **grown lazily**: a node is derived when an application first reaches it, and a branch is made for the constructor (row, level list, argument count) the actual major has; nothing is explored that no application needs. (4) Rules are **session-local** (keyed by the head's CONST record, dropped at reset), never published; a template may therefore hold session values (closed sub-values of the leaf) and closures over export or generated terms; a closure over a term of the worker's overlay ends the leaf. Not from the ISA: K-like majors are never split (the generic IOTA does not normalise them); an open application of one of the 14 binary Nat functions is not unfolded while deriving and the functions themselves get no rule (the leaf keeps `Nat.add x y` folded, so the firing meets NATFOLD); a leaf that is the application itself is no leaf; an application of a bound value in argument position becomes the delayed beta step `SUSP(body, frame + arguments)` (what the generic walk suspends), in root position `APPLY`; a split variable that occurs in a leaf as a whole is bound to the ORIGINAL argument (as the generic walk passes it on), its fields to the arguments of the normalised major. Budget: 1 / 512 of the session's ticks (ISA: 0.2 %), 4,096 ticks per node, at most 256 rules, 48 nodes per rule, 6 levels, 16 branches per split, 192 instructions per leaf, 4 MiB of rule code | (1), (2): a new value kind needs changes in `Values`, `Eval`, `Conv`, `Infer` and `Layout` (five packages; today kind 0 is `fault(E_INTERNAL)` in every judgment, which would decline the declaration, and it cannot be the head of an application, so no matcher alternative `h_i x̄` could be a leaf). With a free variable of a type nobody else has, every judgment already does the right thing (a stuck neutral), the memos and pair entries written while deriving sit on values that no real value can equal, and the binder discipline (`Scope.open` / `close`) reproduces the same variables for every derivation of the session. The purpose of the condition in RECONCILIATION - a judgment must not read a slot's type and act on it - holds: the only type a judgment can read is one that makes type-directed rules say "no" or fail; no positive answer depends on it (argument in the comment of `Rules`; a universe level is never taken from it because `ENSORT` of a literal fails). (3): levels and parameters of a constructor are not known from a stuck projection; the actual major has them, and 8 constructors x 3 levels of unused branches cost derivation ticks. (4): the publication rule (B2) is for shared artefacts; a session-local tree needs none of it and costs about 25 ticks per node to rebuild. The letter of RECONCILIATION section 2 ("a dedicated value kind", "any type-directed judgment ... abandons") is NOT met; the switch stays off until the orchestrator accepts this form or the kind is built |
| D18 | `Reduce.iotaDirect`, `directSlow` (ISA 4.3.5 "`r = call RULE(rec, j)(..)`; `len(v) > majorIdx + 1: r = call APPLY(r, args[majorIdx + 1 ..])`"; owner note 7 "`Nat.rec` / `casesOn` on a literal in one step"), behind the experiment switch `SW_M3A` (`m3a`) | when the right-hand side of the rule is, as a term, `fun params motives minors fields => minor_j fields (rec params motives minors field_a) ..` (a type without indices, one motive, hypotheses of the same recursor at the same levels; verified once per recursor constant, rule and session), IOTA applies the minor premise to fields ++ hypotheses ++ the arguments after the major in ONE `APPLY`; a hypothesis the minor's body does not read is not built (B6). Any other rule takes the block / the right-hand side | a recursor whose motive is a function type (structural recursion with further arguments: `Nat.rec (motive := fun _ => Nat -> Nat) z s n a`) made, per step, a block activation, the closure of the partially applied minor and a generic (tabled) EVAL of its body: the minor took its last arguments in a second beta step, for which the closure of the rest has no code. In one APPLY the minor's own block entry for all its binders runs. Same reduct (the same minor on the same arguments; R02 7.4); the hypothesis is the record the right-hand side builds |

## Not done

- Step 4 is wired (D13, D15) and tested for equivalence on the hand-built prelude (`M3TierTest`: 80 random
  case-tree applications, 29 reduction rows, id equality of DELTA / IOTA through blocks, D = 0) and on the
  tutorial and `bugs/` tests with `-Dm8.switches=code,casetrees` (DIFF-CLEAN); it is OFF by default and its CPU
  effect is not measured under arena conditions (reachability in-process, loaded host: ticks 35.1 M -> 17.0 M,
  EVAL calls 8.35 M -> 0.94 M, DELTA 3.48 M -> 1.19 M, IOTA 1.70 M -> 0.49 M, 4.43 M block entries).
- K2 (D17): no rule in lazy delta (as ISA 3.6 says); no rule for theorems or proof-block values; a string
  literal major is a miss; far frames (more than 63 slots) and LET suspensions end a leaf; rules are not shared
  between sessions or workers (every heavy declaration derives its own, about 25 ticks per node).
- No cycle detection: a reduction that cycles (only ill-typed terms and the `corner-cases` tests do) runs until
  the step budget or the cancellation poll ends it; the reference kernel declines such a loop at once.
- Results of `NATOP` that are roots of a fold still stay in the session's `NatStore` until the reset (D16 frees
  the intermediates of a nest, not the results a proof passes on: 0.36 GB on `perf/gf3-matrix-product`).

## Wave 6 (2026-11-24, 13:25Z - ): measurements and experiments

Tools of this wave are in `work/m3/` (not in the module): `m.py` (one measured run = one row of `measure.tsv`;
the integrator's tool), `batch*.sh` (each under ONE hold of `work/perf-history/.ladder.lock`), `perf.py` (the
`perf/` tests through a launcher on the host with the machine's statistics), `mkdist.sh` (a distribution whose
machine jar is this sandbox, every other jar as in `work/machine-int/dist-g-native`), `diff-big.sh` (`testkit
diff` on the four corpora; **launcher settings go after `--`**), `prof-run.sh` (perf profile in the container),
`show.py` (prints declarations of an export). Native image CE `-O3`, arena-shaped container, ONE worker, CPU
seconds first. The host ran at load 25 - 260 from the other agents for most of the wave (other packages'
builds and test runs), so CPU seconds are comparable INSIDE a batch only; instruction counts and the machine's
own counters (ticks, records, run words) do not depend on the load.

### W1. Code objects and case trees ON (priority 1): KEEP, ready

Published jar 608b84b2b142 (`work/machine-int/dist-g-native`), no code change of mine.

| corpus | v0 | `code,casetrees` | `warm,gated,expect,probes` (4 MiB) | the same + `code,casetrees` |
|---|--:|--:|--:|--:|
| reachability, quiet host (batch 0, 1 run) | 7.44 s / 59.4 G / 1348 MiB | **2.54 s** / 29.3 G / 426 MiB | 7.14 s / 60.8 G | 2.52 s / 29.3 G / 481 MiB |
| Cedar slice, quiet host (batch 0, 1 run) | 3.05 s / 47.0 G | 3.16 s / 46.3 G | 2.90 s / 41.8 G | 2.56 s / 40.6 G |
| Init (batch 1, median of 3, load 25 - 90) | 8.19 s / 139.0 G | 6.89 s / 118.7 G | 7.64 s / 106.6 G | 6.90 s / 92.8 G |
| Std (batch 1) | 13.65 s / 223.2 G | 12.07 s / 194.7 G | 13.24 s / 177.8 G | 12.04 s / 158.0 G |
| Cedar slice (batch 1) | 3.45 s / 47.0 G | 3.44 s / 46.3 G | 3.02 s / 41.8 G | 3.03 s / 40.6 G |
| reachability (batch 1) | 8.56 s / 59.4 G | 3.08 s / 29.3 G | 8.06 s / 60.8 G | 3.14 s / 29.3 G |

(CPU s / instructions G / peak RSS.) reachability 0.34x, as M7 measured in-process; Init -16 %, Std -12 %, Cedar
slice unchanged (-0.3 % / +0.3 % CPU, -1.5 % / -2.9 % instructions). Gates on that jar (`testkit diff` through
the JVM launcher, the switches verified in the statistics): `code,casetrees` and
`warm,gated,expect,probes,code,casetrees`: Cedar slice 8,064, reachability 4,224, Init 57,938, Std 100,096 units,
all agree, 0 differences, 0 declines in all eight runs; all 230: 15,844 / 15,845 agree with 1 decline
(`loop-unfolded-once`, E_MEM, as v0) and 15,845 / 15,845 with 0 declines. Reports `work/m3/diff-*-g-k1.txt`,
`diff-*-g-all.txt`.

### W2. K2, derived trees (priority 2; D17): behind `k2`, gates green, see "open" before turning it on

Counters, published state (jar a795a6f85f94 = `work/m3/dist-k2n`), base = `warm,gated,expect,probes,code,casetrees`
at 4 MiB, host (not the container; CPU = user + sys of one run under load, the counters are exact):

| workload | base | base + `k2` | base + `k2,m3a` |
|---|--:|--:|--:|
| reachability: CPU s / ticks M / records M / run words M / frames | 4.17 / 16.50 / 8.45 / 48.1 / 348,827 | 3.02 / 13.95 / 7.37 / 38.0 / 129,783 | 2.90 / 13.42 / 7.37 / 38.0 / 149,475 |
| `perf/magma-list-deep-n36`: CPU s / ticks M / records M / RSS MiB | 14.77 / 53.5 / 10.28 / 1822 | - | **1.30 / 7.24 / 1.08 / 227** |
| `perf/magma-list-pair-n21` | 6.40 / 24.0 / 14.26 / 1884 | - | 4.24 / 14.9 / 9.11 / 1353 |
| `perf/ite-taken-branch` | 3.83 / 14.0 / 7.95 / 653 | - | 1.36 / 5.26 / 3.99 / 331 |
| `perf/short-circuit-and` | 3.48 / 13.8 / 8.92 / 644 | - | 2.52 / 9.64 / 7.29 / 620 |
| `perf/unfold-before-literal` | 7.02 / 42.5 / 15.73 / 1210 | - | 5.51 / 28.3 / 12.59 / 1062 |
| `perf/proj-applied-struct` | 6.00 / 42.5 / 15.73 / 1318 | - | 5.18 / 28.3 / 12.60 / 1206 |
| all 43 `perf/` files, CPU s | 155.2 | - | 122.3 |

Locked batch 2 (`work/m3/batch2.out`; native `-O3`, container, one worker, 2 runs per row, host load 120 - 135;
`work/m3/dist-k2f` = the K2 of 14:45Z without `m3a`; CPU s of both runs / instructions G):

| corpus | `code,casetrees` | + `k2` | `warm,gated,expect,probes,code,casetrees` | + `k2` |
|---|--:|--:|--:|--:|
| reachability | 3.08, 3.09 / 29.4 | 2.47, 2.66 / 23.7 | 3.14, 3.12 / 29.4 | **2.54, 2.52 / 23.1** |
| Cedar slice | 3.44, 3.25 / 46.3 | 3.45, 3.28 / 46.2 | 2.91, 2.87 / 40.6 | 2.89, 3.02 / 40.5 |
| Init | 7.00, 7.21 / 118.8 | 7.05, 7.01 / 119.1 | 7.16, 6.85 / 92.8 | 7.13, 6.80 / 92.9 |
| Std | 12.37, 12.20 / 194.8 | 12.43, 12.48 / 195.3 | 12.78, 12.06 / 158.1 | 11.99, 12.26 / 158.1 |

reachability -19 % CPU (-21 % instructions; on the quiet host of batch 0 the base row was 2.52 s, so about 2.0 s
with `k2`: at nano-lean's 2.08 s); the Cedar slice, Init and Std do not move (instructions -0.2 % .. +0.3 %:
the per-unfolding count in the one-declaration sessions of v0; with warm sessions +0.0 % .. +0.1 %). By the
rule of this wave (lower on one corpus, none raised by more than 1 %): KEEP.

Locked batch 3 (`work/m3/batch3.out`; the published state of 15:05Z = jar a795a6f85f94 = `work/m3/dist-k2n`, with
the other packages' publications of the afternoon; base = `warm,gated,expect,probes,code,casetrees` at 4 MiB;
3 runs per row, host load 100 - 157; CPU s of the runs / instructions G):

| corpus | base | + `k2` | + `k2,m3a` | + `k2,m3a`, `elabAt` 1 |
|---|--:|--:|--:|--:|
| reachability | 2.84, 3.00, 2.96 / 27.9 | 2.30, 2.63, 2.55 / 21.9 | 2.50, 2.50, 2.42 / 20.7 | 2.39, 2.32, 2.53 / 20.6 |
| Cedar slice | 2.61, 2.61, 2.82 / 37.5 | 2.60, 2.74, 2.83 / 37.5 | 2.48, 2.73, 2.60 / 37.3 | 2.46, 2.75, 2.63 / 37.2 |
| Init | 6.44, 6.70, 6.49 / 90.1 | 6.78, 6.88, 6.86 / 90.2 | 6.22, 6.90, 6.87 / 89.4 | 6.28, 6.84, 6.80 / 89.3 |
| Std | 11.67, 12.53, 11.50 / 154.9 | 11.91, 12.32, 11.49 / 155.3 | 12.50, 12.33, 11.57 / 154.1 | 12.76, 11.39, 12.44 / 153.9 |

reachability also: host depth 512 2.41, 2.39 s and 2048 2.57, 2.37 s (20.7 G both: no CPU difference
established); `lazyeval` on top 2.24, 2.33 s (20.7 G); v0 8.95, 8.61 s (58.3 G); the reference engine 13.15, 12.78 s.
So reachability: -16 % CPU / -26 % instructions with `k2,m3a` (median 2.96 -> 2.50 s; 0.28x of v0 in the same
batch); `m3a` is worth -0.5 % .. -0.9 % of the instructions on Init, Std and the Cedar slice and -5 % on
reachability. **Init is the open point**: its three `k2` rows are 5 % above its three base rows in CPU (worker
CPU 5.97 - 6.04 s against 5.65 - 5.73 s) at +0.1 % instructions and equal ticks; batch 2 had shown no difference
(7.16, 6.85 against 7.13, 6.80). Not resolved under this load. To make the question moot, K2 was then ARMED
PER DECLARATION (published 16:06Z): nothing of it runs (no count, no table probe, no firing) until the current
declaration has run 16,384 ticks, which no light declaration reaches; an interleaved batch (base, `k2,m3a`,
4 pairs per corpus, `work/m3/batch4.sh`) is queued behind the measurement lock.

On reachability 0.92 M of the WHNF loop's unfoldings fire a rule (hit rate 97 %); a node costs about 25 ticks
to derive (74 - 148 nodes in each of the three heavy declarations, 0.05 % of their ticks). The lazy-delta path
(most of a `decide` proof's top-level chain) takes no rule, which bounds the gain there; `magma-list-deep-n36`
is the shape the rules are for (`List.get!Internal._f` through `brecOn`: one firing per element instead of 4
unfoldings, 2 recursor steps and the closures of a matcher's alternatives): nano-lean's 10x there is reproduced.
Gates (published code, `work/m3/diff-*-h-*.txt`, statistics `work/m3/tmp/diffstats-*-h-*.json`):
`code,casetrees,k2,m3a` and `warm,gated,expect,probes,code,casetrees,k2,m3a`: the four corpora, all units agree,
0 differences, 0 declines (rules fired 23 k / 916 k / 54 k / 67 k and 24 k / 921 k / 148 k / 216 k times);
STRESS mode (`-Drlvm.machine.k2Hot=1 -Drlvm.machine.k2Share=0`: a rule at the first unfolding, no cap):
tutorial + bugs + other + corner-cases + init-prelude 3,102 units, reachability 4,224 (977 k firings), Cedar
slice 8,064 (107 k firings): all agree, 0 differences, 0 declines; all 230 with `code,casetrees,k2,m3a`:
15,844 / 15,845 agree, 1 decline (`loop-unfolded-once`, as v0).
Two defects of the first version, both found by the stress run before anything was published: a rule for a
binary Nat function (its leaf was the application itself: the WHNF loop did not end; now no rule for them and
no leaf equal to its application), and rules retired while they waited for the derivation allowance.

### W3. Direct recursor rules (priority 4; D18): behind `m3a`

`perf/unfold-before-literal`, `code,casetrees,k2` (JVM launcher, counters): ticks 33.06 M -> 28.34 M, block
activations 3.15 M -> 9.6 k, APPLY 6.29 M -> 4.72 M, records unchanged (14.17 M). The records stay because the
minor premises of `g` are walked: `g` is unfolded three times and BODY(g) is elaborated at the 10th request;
with `-Drlvm.machine.elabAt=1` the same run makes 7.88 M records and EVAL falls from 4.72 M calls to 297 (user
time 7.9 s -> 4.1 s on the JVM): request to M7 (requests.log 15:06Z).

### W4. Deep evaluation (priority 3): measured, a request to M6, nothing of mine changed

Frames on reachability are host recursion beyond the bound, not a pattern of the reduction family: with
`k2,m3a` D = 128 writes 149,475 frames (1.5 % of the call events), D = 512 63,672, D = 2048 2,278 (0.02 %, the
0.1 % bound of H12 holds), D = 8192 none; ticks and call events are identical under all four. The derived
rules alone take the frames from 348,827 to 130 k - 150 k (fewer nested judgments per step). The `perf/`
literal loops are 2^19 deep by construction (`g n a = ih (ih a)`: the nesting of the delayed arguments);
their 3.1 M frames do not depend on D (measured 128 / 2048 / 32768). CPU on the host: 3.02 / 2.84 / 2.96 /
2.77 s for D = 128 / 512 / 2048 / 8192 (one run each, loaded host: no difference established).

### Rejected or not kept

- K2 with the allowance test at 256 ticks and allowance misses counted against a rule: rules were retired
  before their first node could be derived (reachability: 675 k of 686 k unfoldings met a retired head, 3 k
  firings); replaced by the form above before publication.

## Observations for other packages

- A value ref below 2 (`NONE`, `HOLE`) reaching WHNF / CORE is `fault(E_HOLE)` / `fault(E_INTERNAL)`.
- `NatRed.natValue(m, handle)` turns a result handle of `rlvm.nat` into a value or the `E_NATSIZE` abort;
  `NatRed.litExt(m, v)` is `lit_ext`; `NatRed.boolConst(m, b)`; `NatRed.guard(m, which)`.
- `Reduce.pushSpine(m, neu)` / `copyArgs` copy a spine to scratch for an `APPLY`.
