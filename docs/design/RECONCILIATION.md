# Reconciliation of the design review (binding)

Written by the orchestrator after three critics reviewed the first complete drafts of `ISA.md` and
`ARCHITECTURE.md`. The critics' full text is in `review/critic-findings.md`; ids such as `C3.2` refer to it.
Order of authority: `OWNER-NOTES.md` > this file > `ISA.md` / `ARCHITECTURE.md` > research documents.
Where this file changes a design document, implement this file and say so in your module README; the
documents are being revised to match.

## 0. Metric (owner note 10)

CPU time (task-clock over all threads) is the primary metric, wall clock with parallel workers the
second. Instruction counts are recorded for the arena board and are not the optimisation target.
Every benchmark table lists CPU time, wall clock, peak RSS, instructions, in that order. Targets measured
on this host (aarch64, Docker, arena limits): mathlib gruszka 62.4 s CPU / 18.1 s wall / 2.53 GB,
nano-lean 85.6 s / 25.5 s, sokonanoda 101.4 s / 30.5 s; Init gruszka 2.8 s CPU, Std 5.7 s CPU.
Heat and JIT questions are judged on Cedar-level workloads (`docs/research/13`, in progress), not on Init.

## 1. Blockers

| # | Finding | Resolution |
|---|---|---|
| B1 | C1.1, C3.1: `ls` is a bare level list | `ls` is a **substitution id**: `mkSubst(lparams(row), levels)` (identity = 0). It is what closures, suspensions, `LSUB` keys, inline-cache cells and block calls carry. `rlvm.api.LevelOps` gains `mkSubst`, `instantiate(level, subst)`, `instantiateList(list, subst)`. Regression test: two constants sharing a body term under swapped parameter lists. |
| B2 | C1.2, C3.2: published artefacts hold worker-local ids | **Publication rule**: anything visible to more than one session (code objects, static facts, shared tables) contains only export or generated term ids, constant ids, name ids and *base* level/list ids. Derived levels are stored structurally and re-interned per session through an import cache cleared at reset. A role whose source term lives in an overlay (theorem proof blocks, terms a worker built) is never published: it stays at the generic tier or in a session-local code area dropped at reset. Debug builds assert the rule at publication. |
| B3 | C1.3: the machine has no skeleton, packages or gates | The ISA revision adds section 13 (module `rlvm-machine`, layout/opcode constants, `Ops` signatures, error enum, test fixture builder, about eight work packages with file ownership and acceptance tests) and a skeleton agent creates the module before the machine wave. Gates: v0 diff-clean against the reference engine on the tutorial and `bugs/` tests; v1 on Init and Std. |
| B4 | C2.1: stepper overhead measured at +33% over host recursion in its best form | The control convention is fixed by measurement before any judgment is written (spike `spikes/stepper/`): candidates are (i) fused loops per hot family with registers in locals and a dense `op<<4|st` dispatch, (ii) the same plus bounded host recursion, the host stack acting as a cache of the worklist top with frames materialised into `W` beyond a depth bound or when a budget, `TRY` or suspension needs them. Both keep the worklist transition system as the specification. Acceptance: at most +15% CPU time over plain recursion on eval + readback of Init's and Cedar's declaration types, and a regression test on frames per declaration. |

## 2. Soundness resolutions (all accepted)

- **Static Prop facts** (C1.4, C2.3, C3.3, C3.5): `SIG(c)` is derived lazily, by any worker, from the
  store alone (infer-only over the type telescope under canonical variables); recording at `DECL`
  acceptance is only an optimisation. `slvl[i]` / `rlvl` are recorded only when the inferred sort is
  *manifest* (a `SORT` record before any weak-head normalisation); otherwise "unknown": the position is
  always compared and the dynamic `ISPROP` runs. All facts of a constant form one immutable record
  published by a single store of its index; 0 means "no facts". No fact is read from separately
  published words. A failed derivation records "no facts".
- **Faults inside `TRY`** (C3.4): two classes. Recoverable (kernel `fail`, budget, `E_NATSIZE`,
  `E_LIMIT`) become `U`. Fatal (`E_HOLE`, `E_VIS`, `E_INTERNAL`, audit failure, first-failure signal,
  memory watermark) cut to `DECL` whatever `tryp` is: DECLINE and session reset.
- **`FAILED`** (C1.9) holds only comparisons that completed with `F`. Budget-exhausted probes go to a
  separate table that only suppresses another probe.
- **Projections** (C3.6): `readsField[j]` = the variable of field j occurs in any later field domain or in
  the constructor's result type. `i >= nfields` fails with `E_PROJ`.
- **`Nat.succ`** (C3.7): `NSUCC` maps `Nat.zero` to literal 1; `WHNF(NSUC(b, k))` is the literal `n + k`
  when `WHNF(b)` is a literal `n` or `Nat.zero`, else itself; the WHNF flag is set on `NSUC` only for a
  non-literal weak-head-normal base.
- **Literal identity** (C1.7, C3.8): one bridge, one owner (store tables with the `rlvm-nat` author).
  Machine: immediates below 2^31; an `NBIG` record holds the value in two words below 2^63 and a
  `NatStore` overlay id above. `LitPool` keeps export literals only and interns naturals and strings by
  content. Until canonical ids are guaranteed end to end, conversion compares content on an id mismatch
  before answering `F`. The machine calls `rlvm.nat` directly; `NatOps` serves the reference kernel.
- **Literal `depMax`** (C3.9): `Specials` is split. The loader binds prerequisite names to constant id and
  rank when the name is claimed; literal `depMax` is the largest rank of the prerequisite names. Guard
  states stay with the environment pass.
- **`CHECK` is tabled** (C3.10) as specified in the finding; the expected type is never stored.
- **Frames with far variables** (C3.12): `runRest` is part of the interning key.
- **Verdict rule** (C3.13): the verdict is the status of the first non-ACCEPT unit in file order
  (REJECT only when confirmed by the reference kernel); later units' statuses never change it.
- **K2 and T2** (C3.11) stay off until: derivation slots are a dedicated value kind and any
  type-directed judgment or `U` on a slot abandons the derivation; templates reference only export or
  generated terms; compiled activations propagate an `UNWIND` status when `W` is cut.
- **Facade** (C1.8): ISA gains a facade section. Strict mode = generic tier only, the complete
  procedure, only the exact shortcuts, a fresh session, no shared facts or code. `Reason.CANCELLED`
  exists. The Nat-size guard mirrors the official REJECT (the reference kernel confirms it).

## 3. Performance and structure resolutions

- **Sharing flags** (C1.5, C2.10): the loader keeps a saturating 2-bit referrer counter per segment record
  and sets SHARED / BODY in node header bits; the block decoder sets them from a SEEN bit; promoted nodes
  are SHARED; bit 15 marks INVARIANT nodes (free-variable mask strictly smaller than the single parent's),
  which are tabled too. The store records each unit's first export id. Until then every non-leaf node is
  tabled.
- **Sessions** (C1.6, C2.6): footprint = bytes in use, counted once. Default cap 16 MiB, swept over
  8 / 16 / 32 / 64 MiB by CPU time once the machine runs Std. Reset is O(used) (epoch stamps, rehash of
  used records). Locality: run header inline in the run slab (run id = offset); working environments in a
  separate non-interned slab; tag bits packed into the intern-table word; `POS` / `NEG` / `FAILED` merged
  into one pair table with status bits; inline caches in one direct-mapped lossy table.
- **Scheduler** (C2.4, C2.5, C1.12): each publication batch is split into W contiguous runs, one per
  worker, with stealing of the back half of the largest remaining run. No step limit in warm sessions
  (memory watermark and first-failure polling stay); step budgets only in strict and differential modes.
  The heavy lane is for memory retries and reference-kernel re-checks.
- **Elaboration threshold** (C2.8): a role is elaborated rent-to-own, about the 10th use (sweep 4 / 8 / 16 /
  32); static facts are derived independently of code elaboration.
- **Shapes and shortcuts** (C2.9): `CASESON` and `MATCHER` are K1 shape facts (evaluate only the major
  premise and the chosen alternative; K-like recursors excluded); closure congruence at the start of
  conversion under a `TRY`; structure eta applies the constructor's Prop mask after the type comparison;
  the body type of a closed lambda-type closure is memoised.
- **Vision fidelity** (C1.10): the judgment-specialised roles are primary: `TELE`, a real `CONG(c)` block
  (a per-position comparison plan, not two masks) and derived `RULES`. Truffle compilation (T2) is an
  experiment with a go/no-go number measured on Cedar-level workloads; nothing in K0/K1 may be constrained
  for its sake.
- **Staging with measured gates** (C2.7, C2.2): (1) generic tier + sessions + sharing flags + shared
  check facts; (2) static masks with lazy facts; (3) K1 `TELE` + pattern matching of domains; (4) K1
  `BODY` / `RULE` with the casesOn and matcher shapes; (5) speculative probes; (6) parallel workers. After
  each step: CPU time, wall, RSS and instructions on Init, Std and a Cedar slice. Machine targets on Init:
  at most 2.5x gruszka's CPU time after step 1 and 1.5x after step 4. The platform gate (JVM with JIT,
  native image CE, native image with PGO) is run when the machine first accepts Init and is decided by
  CPU time and wall clock.
- **First goal is a vertical slice** (C1.11): batch load, one worker, reference engine, tutorial tests end
  to end. Pipelined loading, level snapshots, namespace ordering, scan helpers, late names and the
  generated region are deferred until that slice and the 230 small tests pass (C1.15).
- **Ownership**: one build owner per Maven module. `TermOps` stays in `rlvm-store` and belongs to the
  store owner, who delivers it first. `RefKernel` is one owner's file set.

## 4. Owner notes 7-9 in the design

- Nat (note 7) is already specialised in the ISA (immediates, offset cells, guarded native arithmetic,
  constructor views); the fixes above make it agree with the official `Nat.succ` behaviour.
- Storage strategies (note 8) remain an open, measured experiment after correctness: the value model
  reserves kinds 13-15 and the candidates are ranked by the frequencies reported in `docs/research/13`.
- Cedar-level workloads (note 9) are the yardstick for every tiering decision and supply the fast
  benchmark slice used by the staging gates.

## 5. Errata found by implementation (binding; they correct `ISA.md`)

- **E1 (2026-11-24, evaluation owner; found on the Cedar slice).** ISA 2.3, 2.8, 3.4 and 4.2 made every
  `let` value a LET suspension, so every value built from an evaluated `let` carried the OPEN flag. That is
  the official behaviour only for the let variable of `infer_let`. For a `let` inside an evaluated term (the
  body of an unfolded definition, a forced argument) the official `whnf_core` instantiates the body with
  the value: no variable. With the ISA's rule, lazy delta skipped Nat literal folding and conversion skipped
  the `Bool.true` shortcut exactly where the official kernel folds, and the machine unfolded `Nat.div`,
  `Nat.mod` and `gcd` on large literals by their fuel recursion (five Cedar declarations at about 1,000
  times the official work). **Rule now:** `EVAL` treats a `let` as zeta by sharing with a plain value (like
  a beta argument); only `INFER`'s let variable is a LET suspension. OPEN on a value means what the
  official `has_fvar` means. The statement in ISA 2.3 that over-approximating OPEN is harmless is wrong: it
  steers into non-termination. Code objects must emit a let value as an ordinary argument operand.
  Regression: Cedar slice unit 7008 (73.7 M steps and a memory decline before, 60,569 steps after).
