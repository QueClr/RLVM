# 13 - Workload characterization: what the kernel does on Cedar-level corpora

Question (owner, on the static heat spike of `spikes/machine`): "You need to check Cedar level workload. Init
may not be interesting enough." How hot do the code objects of the machine (`TELE`, `BODY`, `RULE`, ISA §3.2)
get on realistic corpora, and which specialisations does the data argue for?

Evidence tags: **[meas]** measured here (tools and raw data in section 8), **[est]** my estimate with the
arithmetic shown, **[src]** read in a checker's source. Numbers without a tag are measured. Instruction counts
are user-mode instructions on this aarch64 host (cedar: 71 per byte here, 70 published for x86).
Corpora: `work/exports/{init,std,cedar,reachability,con-leche,navier-stokes-euler,cslib,mathlib}.ndjson`.

## 0. Decision summary

1. **Cedar is not more reduction-heavy than Init; it is less.** Share of nano-lean's checking instructions spent
   under a reduction (whnf, delta, iota, projection, including the evaluation and interning it triggers):
   Init 38%, cedar's own 22,454 declarations 17%, NSE's own 17%, cslib's own 24%, con-leche's own 34%,
   reachability's own 99%. What distinguishes Cedar-level declarations is **size and conversion**: 1.02 M
   instructions per declaration against 0.38 M for Init, 30% of their instructions under `def_eq` against 16%.
   Real kernel computation exists in exactly three places: reachability (5 `decide` proofs = 92.5% of the file),
   con-leche (one 5.8 G declaration, 20 reduction-heavy proofs in its top 50) and the arena's `perf/` tests.
2. **The corpora contain their libraries.** By instructions cedar's export is 39.5% Init, 14.8% Std, 5.9%
   Batteries/Mathlib and 39.8% Cedar; cslib is 70% Mathlib and 5.4% Cslib; NSE is 72% project, con-leche 94%,
   reachability 93% (section 3.2). A per-corpus number is mostly a statement about Lean's core library.
3. **Dynamic heat is low in absolute terms and never reaches the promotion rent.** Whole cedar (64 G
   instructions): 4.15 M delta unfoldings, 1.24 M iota steps, 1.06 M projections of constructors, 7.5 M
   application inferences. No `BODY`, `RULE` or projection object is executed 10^6 times in any corpus,
   mathlib included (hottest: `PProd.mk.0` 723 k, `DFunLike.coe` 685 k, `List.rec` cons rule 582 k); one `TELE`
   object (`Eq`: 3.1 M on mathlib, 1.4 M on cslib) is. Objects above 10^5 executions: cedar 7 bodies, 3 rules,
   3 projections, 10 telescopes; mathlib 40, 11, 17, 61 (section 3.4). At 3-4 k instructions per reduction
   step [est] the ISA's rent of 3.3 G needs about 10^6 executions of one block: **no corpus reaches it; two
   `perf/` tests do** (`unfold-before-literal-fallback`, `proj-applied-struct-arg-bad`: 3.15 M `Nat.rec`
   steps and 1.57 M `HAdd.hAdd`/`Add.add` unfoldings each).
4. **The hot set is Lean's core library, and it is the same everywhere.** 93.6% of cedar's delta unfoldings
   and 94.9% of its iota steps are of constants declared in Init; Cedar's own definitions receive 2.9%
   (con-leche's: 6.5%). The 300 bodies that are hottest on Init cover 86% of cedar's unfoldings, 82% of
   con-leche's, 74% of reachability's, but only 31-51% on the Mathlib-based corpora, whose heat is in
   Mathlib's structure projections (`DFunLike.coe`, `AddCommMonoid.toAddMonoid`, `CategoryTheory.Functor.obj`).
   By role (cedar): `casesOn` 21.5%, structure projection functions 19.8%, matchers 14.2%, `brecOn`/`below`
   6.4%, compiler auxiliaries (`_f`, `.go`) 8.8%, other definitions 26.5%.
5. **Derived reduction rules (nano-lean's, the model of K2) are the one specialisation with a measured large
   effect, and only on evaluation**: switching them off costs 1.9x on reachability, 10.3x on
   `magma-list-deep-n36`, 3.2x on `ite-taken-branch-bad`, 1.6x on `magma-list-pair-n21`, but 1.04-1.06x on
   init, std, cedar, con-leche and 1.00-1.02x on NSE, cslib, mathlib.
6. **Where instructions go on Cedar-level work** (whole cedar, nano-lean): inference 42%, reduction 25%,
   conversion 20%, parsing 9%. By what the code is doing: walking terms and environments (`eval`/`apply`/`trim`)
   33%, building and interning values 21% (+4% hash tables), inference proper 18%, conversion proper 7%; on
   the evaluation tests interning is 33-49%. Hash-consing is the part no code tier removes.

Conclusions for the machine (details in section 5):

- (i) `BODY`/`RULE` heat is far more concentrated than `TELE` heat (cedar: 100 bodies = 74%, 10 rules = 85%,
  100 telescopes = 62%; Init static telescopes: 100 = 72%) but 10-100 times smaller in volume than the rent
  assumes. Objects at >= 10^4 / 10^5 / 10^6 / 10^7 executions (bodies + rules + projections + telescopes):
  cedar 175 / 23 / 0 / 0, reachability 39 / 10 / 0 / 0, con-leche 176 / 22 / 0 / 0, NSE 307 / 18 / 0 / 0,
  cslib 463 / 42 / 1 / 0, mathlib 1073 / 129 / 1 / 0.
- (ii) Compiled (Truffle) code has no target on init, std, cedar, NSE, cslib, mathlib. Its only candidates are
  closed evaluation loops: the `perf/` evaluation tests, reachability's `decide` proofs, a handful of
  con-leche proofs. Elaborated code (K1) for `TELE` and for the Init hot set pays everywhere because a third of
  all instructions is term walking; K2 pays on evaluation only. 57% of cedar's declarations perform no
  reduction step at all and 81% at most ten: for them the generic walk (K0) and the memo tables are the
  whole machine.
- (iii) Argued for by frequency: instance-projection shortcuts (structure projection function + projection of
  a constructor: 20% of unfoldings on cedar, 37-49% on Mathlib corpora), `casesOn`/matcher decision trees (36%
  on cedar, 68% on reachability), derived rules for structural recursion (`brecOn`: 6% of unfoldings, and
  `PProd.mk.0` alone is 28% of all projections). Argued for only by the `perf/` tests: Nat immediates (94-99.8%
  of Nat literal operations fit 31 bits, but a whole corpus performs 54 k-481 k of them). Not argued for by any
  corpus: packed `String`/`Array`/`BitVec` (at most 4.3 k string literal expansions per corpus, longest 19
  characters), packed `List` (lists are built by evaluation, not given as literals).
- (iv) Fast benchmarks (section 6): `work/workloads/slices/cedar-hot-ax.ndjson` (133 MB, 13.6 G, 3.4 s in
  nano-lean: Cedar's 172 most expensive own declarations over a statements-only library; inference 47%,
  conversion 35%, reduction 13%) for Cedar-level work, `reachability.ndjson` (19 MB, 11 G, 3.4 s) for
  evaluation, `perf/unfold-before-literal-bad` (5.3 G, 184 MiB of native stack) for depth and Nat literals.

## 1. Method, and what the numbers are

| Source | What it gives | How |
|---|---|---|
| `bench/workloads/static-shape.rs` (ours) | static shape; reproduces the spike's HEAT lines exactly on init | one pass over the NDJSON, host |
| nano-lean v0.4.2, instrumented copy (`work/workloads/build/nano-lean-instrumented`) | per-declaration instructions; per-constant delta, iota, projection, telescope counts; Nat/String literal events; whnf chains; stack depth; phase split | Docker; three builds: plain + per-declaration `perf_event` counter (`di`), `vstats` (nano-lean's own counters + ours), frame pointers (`perf record`) |
| rpylean `check --stats --print jsonl` (unmodified binary) | delta per constant, iota per recursor, native Nat ops, def_eq totals, time per declaration | leader's own facility |
| gruszka `--features knobs`, `SK_DECL_INSTR=1` (unmodified source, rebuilt) | ticks, whnf misses, unfolds, def_eq, infer misses per declaration; ground-machine runs | leader's own facility |

Why nano-lean: arena #1, the architecture RLVM follows (folded constants, memo on values, hash-consed
closures, derived rules), per-declaration counters already there, an 80 s rebuild. The patch adds counters
only (`src/wl.rs`, `wl!` sites); the `di` build has identical checking code. The leaders' costs on the `perf/`
tests are read from the baseline team's `work/baselines/small/*.json`.

Definitions (all counted where nano-lean's memo misses, i.e. real executions). **BODY** = a definition's body
is applied to a spine (delta), from `whnf` or from lazy delta; with derived rules on, a **rule firing**
replaces the unfolding and the iota steps under it. **RULE** = a recursor rule is applied, keyed `(recursor,
rule index)`. **PROJ** = projection of a constructor application, keyed `(constructor, field)`. **TELE** = an
application spine with a constant head is inferred, or a neutral value with a constant head is typed.
**Phase** of an instruction sample = the innermost enclosing function among inference, conversion,
reduction, import (frame-pointer call graphs, one sample per 2,000,003 user instructions; `eval`, `apply` and
interning helpers are charged to the phase that called them).

Caveats. (a) One checking thread and no PGO: totals are 10% above the arena build (mathlib 399 G against the
baseline team's 361 G). (b) Counts are nano-lean's: its memo tables, budgeted argument probes and declaration
order decide how often a body runs; rpylean (substitution kernel) and gruszka (NbE) agree on the order of
magnitude (section 3.7), the official kernel was not instrumented. (c) Wall times are from a loaded host.
(d) "Origin" of a name is the first of the init, std, mathlib exports that declares it; everything else is
"project" (for cedar, "Mathlib" means Batteries and the part of Mathlib it imports).

## 2. Static shape

Declarations by kind, literal counts and incremental sizes are in `work/exports/STATS.md`; full tables of this
section in `work/workloads/tables/static.md`. Static heat of constant-headed application spines (the spike's
HEAT table; a spine = an `app` node referenced from a non-function position):

| corpus | spines | heads used | top1 | top10 | top100 | top1000 | N50% | N80% | N90% | N95% | >=1e4 | >=1e5 |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| init | 2.09M | 39,783 | 11.1% | 37.9% | 71.7% | 89.3% | 23 | 246 | 1,133 | 3,549 | 33 | 2 |
| std | 3.12M | 69,712 | 11.3% | 33.8% | 66.8% | 85.2% | 30 | 448 | 2,349 | 7,054 | 50 | 3 |
| cedar | 4.61M | 102,722 | 13.2% | 34.1% | 66.0% | 84.1% | 29 | 530 | 2,954 | 9,676 | 55 | 5 |
| reachability | 103k | 4,436 | 14.4% | 37.7% | 70.8% | 93.7% | 21 | 220 | 602 | 1,246 | 1 | 0 |
| con-leche | 4.41M | 36,363 | 11.7% | 35.7% | 72.7% | 92.0% | 22 | 201 | 716 | 1,838 | 70 | 3 |
| navier-stokes-euler | 8.44M | 117,266 | 7.6% | 31.2% | 64.5% | 85.1% | 37 | 466 | 2,520 | 8,287 | 102 | 11 |
| cslib | 12.0M | 278,800 | 8.9% | 27.0% | 55.2% | 77.5% | 65 | 1,389 | 7,012 | 25,144 | 133 | 17 |
| mathlib | 30.1M | 523,983 | 7.8% | 24.9% | 52.4% | 75.4% | 83 | 1,819 | 10,027 | 36,008 | 306 | 33 |

Who receives it (share of spines):

| | init | std | cedar | reach | con-leche | NSE | cslib | mathlib |
|---|--:|--:|--:|--:|--:|--:|--:|--:|
| theorems (`Eq.trans`, `congrArg`, ...) | 32.8% | 29.0% | 24.8% | 22.2% | 15.7% | 26.0% | 22.1% | 19.8% |
| inductive types (`Eq`, `And`, ...) | 18.1% | 19.7% | 23.6% | 24.5% | 21.4% | 10.9% | 18.4% | 16.5% |
| structure projection functions | 16.3% | 15.8% | 13.4% | 17.6% | 14.5% | 33.4% | 23.1% | 28.9% |
| other definitions (instances excluded: 1.2-4.3%) | 22.4% | 23.8% | 24.5% | 20.3% | 28.8% | 23.4% | 24.4% | 24.1% |
| constructors | 6.6% | 7.5% | 8.9% | 8.0% | 13.2% | 2.4% | 6.0% | 4.7% |
| matchers + `casesOn` + `brecOn`/`below` + recursors | 2.1% | 2.5% | 3.1% | 4.5% | 4.7% | 0.5% | 1.6% | 1.1% |
| declared in Init / Std / Mathlib / project | 100/0/0/0 | 90/10/0/0 | 85/5/2/8 | 95/0/5/0 | 76/1/0/24 | 49/0/43/8 | 63/2/34/2 | 49/1/50/0 |

Differences from Init: cedar shares 23 of its 30 hottest static heads and 82 of its 100 with Init; the
non-Init entries of its top 100 are `Cedar.Thm.InstanceOfType`, `Std.DTreeMap.Internal.Impl.inner`,
`Cedar.SymCC.Term.WellFormed`, `Cedar.Data.Map.find?`, `Cedar.Thm.Residual.WellTyped`,
`Cedar.Validation.typeOf` (type formers and plain definitions, no matcher or recursor). con-leche differs most
among the non-Mathlib corpora (16 of 30 shared; `PSum.inr` 5.0%, `PSigma.mk` 1.7%, `List.filter.match_1` 1.2%,
`ConLeche.Semantics.interp` 0.9%): its proofs carry packed mutual/well-founded arguments. The Mathlib corpora
replace the equality lemmas by projections of the algebraic hierarchy (`HMul.hMul` 7.6% on NSE, `DFunLike.coe`
2.3% on mathlib).

Evaluation-related occurrences (static spines headed by the pattern; literals as node counts):

| pattern | init | std | cedar | reach | con-leche | NSE | cslib | mathlib |
|---|--:|--:|--:|--:|--:|--:|--:|--:|
| recursors `*.rec` | 7.2k | 12.2k | 17.5k | 1.2k | 8.3k | 6.8k | 31.5k | 56.3k |
| `*.casesOn` | 15.2k | 29.4k | 44.5k | 1.2k | 68.1k | 32.0k | 94.7k | 188k |
| matchers `*.match_N` | 19.3k | 31.3k | 70.6k | 1.1k | 123k | 4.0k | 47.0k | 74.8k |
| `*.brecOn` / `*.below` | 4.5k | 5.7k | 8.5k | 1.2k | 10.7k | 3.0k | 14.7k | 19.1k |
| `WellFounded.fix`, `fixF`, `Acc.rec` | 238 | 339 | 451 | 23 | 84 | 65 | 604 | 741 |
| `Decidable.decide` | 2.9k | 3.7k | 7.2k | 216 | 2.9k | 674 | 5.0k | 6.4k |
| `OfNat.ofNat` | 1.7k | 2.0k | 2.3k | 335 | 720 | 24.0k | 45.7k | 117k |
| structure projection functions | 359k | 518k | 646k | 19.0k | 663k | 2.89M | 2.88M | 9.01M |
| natVal nodes (references to them) | 285 (3.9k) | 453 (7.6k) | 707 (14.7k) | 60 (404) | 291 (4.5k) | 1.1k (50.6k) | 849 (44.9k) | 5.7k (150k) |
| strVal nodes (references) | 1.8k (3.0k) | 3.1k (5.4k) | 7.4k (29.8k) | 37 (46) | 574 (1.7k) | 98 (130) | 16.7k (30.6k) | 19.7k (35.3k) |

Proof sizes (expression records first written for the declaration): theorem median 31-71 nodes in every
corpus, p99 984 (reachability) to 7,493 (con-leche), maximum 26 k (init), 86 k (cedar), 373 k (con-leche),
641 k (NSE), 92 k (mathlib); the largest 100 declarations hold 12.7% of cedar's nodes, 22.7% of con-leche's,
2.6% of mathlib's. **93-95% of all expression nodes are private to one declaration** in every corpus (never
referenced by a record of a later declaration; theorem segments 94-96%, definition segments 89-92%), which
confirms ISA decision 3 (run-once terms are walked, not elaborated) on every corpus. Declarations whose
`(type, value)` ids equal an earlier declaration's (private copies such as `_sparseCasesOn_N`): 15,042 on
cedar = 1.26% of its checking instructions, 0.06-1.0% elsewhere.

## 3. Dynamic profile

### 3.1 Totals and where the instructions go

| corpus | decls | instr (process) | instr/byte | in declarations | rules off / on | inference | reduction | conversion | import |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| init | 59,979 | 24.6G | 71 | 90.1% | 1.06 | 39.3% | 34.5% | 15.0% | 9.0% |
| std | 103,885 | 41.1G | 70 | 89.7% | 1.05 | 40.2% | 30.3% | 18.1% | 9.3% |
| cedar | 151,772 | 64.1G | 71 | 89.8% | 1.04 | 42.2% | 25.0% | 20.4% | 9.3% |
| reachability | 4,846 | 11.1G | 581 | 98.6% | 1.89 | 4.1% | 93.0% | 1.2% | 1.2% |
| con-leche | 38,246 | 61.9G | 88 | 92.8% | 1.05 | 38.0% | 31.2% | 20.7% | 6.8% |
| navier-stokes-euler | 129,845 | 103G | 66 | 90.7% | 1.00 | 53.8% | 15.8% | 19.3% | 8.7% |
| cslib | 413,186 | 144G | 58 | 87.8% | 1.02 | 46.4% | 23.8% | 16.0% | 11.1% |
| mathlib | 739,167 | 399G | 63 | 89.6% | 1.02 | 47.0% | 23.9% | 17.5% | 9.6% |

(`instr`: `di` build, whole process. `rules off / on`: `vstats` build with nano-lean's derived rules disabled
over enabled. Phases: share of all samples; the rest is inductive checking 0.3-1.0% and driver 0.2-2.8%.)

The same samples by what the code is doing (self instructions, share of all samples):

| | init | std | cedar | reach | con-leche | NSE | cslib | mathlib |
|---|--:|--:|--:|--:|--:|--:|--:|--:|
| term and environment walking (`eval`, `apply`, `inst`, `trim`) | 33.8% | 33.5% | 32.7% | 32.4% | 35.3% | 33.6% | 32.2% | 32.9% |
| value construction and interning (`neu`, `mk`, `frame`, `push`) | 20.0% | 18.7% | 20.8% | 35.1% | 23.4% | 16.9% | 17.6% | 19.8% |
| hash tables (memo and intern probes, rehash) | 3.9% | 4.3% | 4.3% | 7.3% | 4.1% | 4.3% | 3.8% | 3.9% |
| inference proper (`infer_slow`, `infer`, `type_of`) | 17.4% | 17.4% | 17.6% | 1.8% | 14.1% | 20.2% | 19.7% | 18.6% |
| conversion proper (`def_eq_*`, `lazy_delta`, `args_eq`) | 6.8% | 7.3% | 6.9% | 0.4% | 7.1% | 8.7% | 6.6% | 6.7% |
| reduction control (`whnf`, `whnf_core`, `unfold`, `reduce_*`) | 3.5% | 3.4% | 2.6% | 6.2% | 1.7% | 2.3% | 2.9% | 2.5% |
| substitution into derived-rule templates and zeta | 1.9% | 1.7% | 1.2% | 11.7% | 1.2% | 0.5% | 0.7% | 0.7% |

Reading for RLVM: the first line is what elaborated code replaces (operands in registers instead of a DAG
walk with environment lookups); the second and third are what no tier removes while every value is interned.
Inside the reduction phase of cedar (25.0%): walking 11.6%, construction 8.3%, control 2.6%, templates 1.1%.
Inside inference (42.2%): inference proper 17.6%, walking 12.9% (evaluation of arguments and instantiation
of Pi codomains, i.e. the `TELE` work), construction 8.7%.

### 3.2 The corpora contain their libraries: the project's own declarations

| corpus | part | decls | share of checking instr | instr/decl | infer misses | conversions | delta | rule firings | iota | reductions per infer miss |
|---|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| init | Init | 59,979 | 100% | 369k | 4.60M | 1.89M | 1.48M | 364k | 491k | 0.64 |
| cedar | Init | 59,978 | 39.5% | 379k | 4.58M | 1.90M | 1.60M | 388k | 523k | 0.69 |
| cedar | Std | 30,717 | 14.8% | 278k | 2.00M | 1.15M | 392k | 65k | 73k | 0.32 |
| cedar | Batteries/Mathlib | 38,623 | 5.9% | 88k | 1.00M | 257k | 153k | 26k | 66k | 0.29 |
| cedar | **Cedar** | 22,454 | **39.8%** | **1.02M** | 6.10M | 2.34M | 631k | 214k | 196k | **0.20** |
| reachability | **project** | 125 | **93.4%** | 81.5M | 6.7k | 3.7k | 113k | 882k | 1.05M | 378 |
| con-leche | Init + Std | 9,821 | 6.0% | 349k | 714k | 302k | 222k | 44k | 78k | 0.61 |
| con-leche | **ConLeche** | 28,424 | **94.0%** | **1.90M** | 10.6M | 3.74M | 2.57M | 513k | 1.37M | **0.50** |
| NSE | Init + Mathlib | 70,241 | 27.6% | 369k | 6.94M | 2.96M | 1.25M | 89k | 116k | 0.29 |
| NSE | **project** | 59,604 | **72.4%** | **1.14M** | 12.1M | 8.51M | 2.58M | 488k | 162k | **0.38** |
| cslib | Init + Std + Mathlib | 397,712 | 94.6% | 302k | 29.7M | 12.5M | 6.91M | 763k | 1.16M | 0.39 |
| cslib | **Cslib** | 15,474 | **5.4%** | 445k | 1.79M | 585k | 316k | 73k | 150k | 0.36 |
| mathlib | Mathlib (beyond Init, Std) | 635,283 | 89.4% | 503k | 69.7M | 33.4M | 17.4M | 1.98M | 2.51M | 0.43 |

(reductions = delta + firings + iota + projections in `whnf_core`; derived rules on.)

Phase split of the project's own declarations only (`nl-fast --only`, import excluded, share of the rest):

| own declarations of | inference | conversion | reduction | other |
|---|--:|--:|--:|--:|
| Init (whole export) | 43.2% | 16.5% | 37.9% | 2.4% |
| cedar | 47.2% | 30.3% | 16.9% | 5.6% |
| con-leche | 40.1% | 22.3% | 33.7% | 3.9% |
| navier-stokes-euler | 58.7% | 21.5% | 16.6% | 3.2% |
| cslib | 44.5% | 14.9% | 24.1% | 16.5% |
| reachability | 0.2% | 0.1% | 99.5% | 0.2% |

### 3.3 Per-declaration cost

Concentration (share of the summed per-declaration instructions; exact):

| corpus | top1 | top10 | top100 | top1000 | N50% | N90% | median | p99 | max |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| init | 1.8% | 7.3% | 20.6% | 49.4% | 1,037 | 14,484 | 55k | 5.5M | 397M |
| cedar | 0.7% | 5.1% | 20.3% | 44.1% | 1,552 | 31,031 | 47k | 5.3M | 409M |
| cedar, own decls only | 1.8% | 12.2% | 42.7% | 75.9% | 172 | 2,951 | | | |
| reachability | 32.5% | 93.5% | 95.6% | 98.7% | 2 | 4 | 39k | 2.1M | 3.55G |
| con-leche | 10.1% | 14.0% | 29.7% | 62.0% | 493 | 5,494 | 93k | 23.2M | 5.79G |
| navier-stokes-euler | 2.4% | 7.5% | 12.6% | 28.4% | 4,147 | 32,394 | 104k | 9.1M | 2.25G |
| cslib | 0.3% | 1.4% | 4.9% | 15.7% | 15,074 | 133,236 | 67k | 4.0M | 395M |
| mathlib | 0.3% | 1.2% | 4.0% | 12.2% | 23,288 | 208,281 | 85k | 6.7M | 1.19G |

Theorems carry 88-98% of the checking instructions, definitions 1.5-11.6%, inductive blocks 0.2-2.0%.
The three checkers agree on which declarations are expensive: of nano-lean's 50 most expensive declarations
per corpus, 33-43 are also in gruszka's top 50 by time and 14-37 in rpylean's (`tables/cross.md`).
**The 50 most expensive declarations of each corpus**, with counters and a one-line reason each, are in
`work/workloads/tables/top50-<corpus>.md`. Their classes (count and share of the corpus's checking instructions):

| corpus | top 50 hold | large term, inference and conversion | reduction-heavy inside a large term | kernel evaluation on a small term | other (mixed, inductive blocks) |
|---|--:|--:|--:|--:|--:|
| init | 15.4% | 20 (4.9%) | 4 (2.6%) | 1 (1.8%) | 25 (6.1%) |
| cedar | 14.2% | 32 (9.9%) | 3 (0.9%) | 1 (0.7%) | 14 (2.7%) |
| reachability | 94.8% | 3 (0.3%) | 0 | 5 (92.5%) | 42 (2.1%) |
| con-leche | 23.3% | 27 (8.0%) | 20 (4.8%) | 0 | 3 (10.5%) |
| navier-stokes-euler | 10.6% | 45 (10.0%) | 1 (0.1%) | 1 (0.1%) | 3 (0.3%) |
| cslib | 3.4% | 28 (1.7%) | 7 (0.7%) | 1 (0.3%) | 14 (0.8%) |
| mathlib | 2.9% | 25 (1.3%) | 15 (0.8%) | 4 (0.5%) | 6 (0.2%) |

What the expensive declarations are:

| corpus | declaration(s) | cost | what makes it expensive |
|---|---|--:|---|
| cedar | `Cedar.Thm.checked_eval_entity_reachable._unary`, `level_based_no_dne_expr._unary`, `level_based_slicing_is_sound_expr._unary` | 409M, 283M, 223M | well-founded recursion proofs of 30-50 k nodes; 44-66 k infer misses, 51-88 k conversions driven by lazy delta (21-36 k iterations), 2-3 k reductions |
| cedar | `Cedar.Spec.call.match_5.eq_23` (6 private copies), `Cedar.Validation.typeOfCall.match_1.eq_23` (3), `Cedar.Spec.apply₂.match_1.eq_18` (3), `compileApp₂.match_1.eq_18` (3) | 260M, 143M, 112M, 120M each | matcher equation lemmas (`eq_18`, `eq_23`: matches with at least that many alternatives): 5 k-node terms inferred under many contexts (66 k infer misses, 19 k conversions, 800 reductions); each is a `_private` copy per module, not shared by expression id |
| cedar | `Cedar.Thm.Duration.parseDuration?_eq_duration?_of_wf`, `Cedar.Thm.Op.mkName.neq` | 315M, 229M | 86 k and 61 k nodes; the second expands 2,112 string literals (12,672 characters) and fires 16 k derived rules |
| cedar | `String.fromUTF8Lossy_surrogate_append._proof_1_10` (Init) | 397M | the only evaluation in cedar's top 50: a 65-node `decide` over `UInt32`/`BitVec`: 66 k rule firings, 8.5 k iota, 10.9 k Nat ops; 1.46x without derived rules |
| cedar | `Array.extract_append._proof_1_1`, `Vector.extract_append._proof_1` (Init) | 195M, 187M | `omega` certificates: 19 k delta + 9 k iota on `List.casesOn`, `List.zipWithAll`, `List.map._f`, `instDecidableEqList` inside 24 k-node terms |
| reachability | `Bench.Arena.{dag,path,grid}_reachable._proof_1`, `path_unreachable._proof_1`, `grid_shortest._proof_1` | 3.55G, 2.63G, 1.24G, 2.53G, 141M | `decide` on 19-96-node terms; the four large ones: 124-273 k rule firings, 128-361 k iota, 5-46 k Nat `beq`; hottest `List.elem._f` (166 k), `Nat.decEq`, `instDecidableEqFin`; 1.45-2.4x without derived rules |
| con-leche | `ConLeche.Cached.reduceNatI._mutual.eq_def` | 5.79G | 372,576-node unfolding lemma of a mutual well-founded definition: 1.14 M infer misses, 220 k conversions, 292 k `PSum.casesOn` unfoldings + 292 k `PSum.rec` iota in lazy delta |
| con-leche | `ConLeche.Frontend.keyAt_eq`, `keyOf_105 .. keyOf_118` | 331M, 165-172M each | closed list and string computation inside 17-75 k-node proofs: 17 k delta, 9 k iota (`List.filter.match_1`, `List.find?._f`, `Bool.casesOn`), up to 130 string literal expansions each |
| con-leche | `ConLeche.Model.Rules.{prop_pt_io,red_sound,defeq_sound,infer_sound}.match_2` | 162-249M | matchers of 9-32 k nodes with at most 9 reductions: pure inference (19-29 k misses) and conversion (9-13 k) |
| NSE | `NavierStokes.{FiveProfileMoments,TerminalCompensation}.quadraticBilin._proof_{6,9}` | 2.25G, 1.46G, 1.29G, 879M | 130-641 k-node proof terms over `Matrix.vecCons` vectors: 199-476 k infer misses, 88-237 k conversions, 15-39 k delta (`Fin.induction`, `Nat.brecOn`, `Matrix.vecCons`) |
| NSE | `EulerSourcePotentialCoefficient.potentialTimePath_translation` | 141M | 366 nodes: 23.5 k unfoldings of instance projections (`DFunLike.coe` 5.1 k, `AddHom.toFun` 3.0 k) in lazy delta |
| cslib | Init's `fromUTF8Lossy` and `extract_append` proofs; own: `Cslib.Automata.NA.loop_fin_run_exists._proof_*` | 395M, 202M; 154M | the corpus's top 10 are Init, Std and Mathlib declarations; Cslib's own most expensive proof is a 52 k-node term with 14 k reductions |
| mathlib | `WeierstrassCurve.Affine.Point.sym2x_add_sub_eq_addSubMap_sym2x._proof_1_8` | 1.19G | `decide`-style evaluation on 4 k nodes: 159 k rule firings, 116 k iota, 30 k Nat ops; 2.5x without derived rules |

### 3.4 Dynamic heat per constant

Coverage in the form of the static HEAT table (derived rules off = every delta runs a body, the K1 view;
cslib, std and `PROJ` rows in `tables/dyn-summary.md`):

| series | corpus | executions | objects | top1 | top10 | top30 | top100 | top1000 | N50% | N80% | N90% | N95% |
|---|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| BODY | init | 2.27M | 6,463 | 10.4% | 37.4% | 62.8% | 83.2% | 98.3% | 18 | 82 | 167 | 330 |
| BODY | cedar | 4.15M | 18,962 | 7.9% | 31.2% | 54.0% | 73.8% | 94.9% | 25 | 150 | 383 | 1,016 |
| BODY | reachability | 3.42M | 1,042 | 13.9% | 78.4% | 97.1% | 99.3% | 100% | 4 | 11 | 18 | 25 |
| BODY | con-leche | 4.10M | 6,936 | 13.6% | 44.6% | 69.2% | 85.7% | 98.2% | 13 | 64 | 145 | 286 |
| BODY | NSE | 4.47M | 20,147 | 3.1% | 15.8% | 32.2% | 58.8% | 91.3% | 67 | 369 | 859 | 1,833 |
| BODY | mathlib | 24.6M | 81,809 | 2.8% | 15.4% | 28.3% | 48.8% | 85.5% | 107 | 632 | 1,629 | 3,822 |
| RULE | init | 675k | 551 | 49.3% | 94.0% | 98.6% | 99.6% | 100% | 2 | 6 | 9 | 11 |
| RULE | cedar | 1.24M | 2,807 | 37.5% | 85.2% | 92.8% | 97.0% | 99.4% | 3 | 8 | 17 | 52 |
| RULE | reachability | 1.69M | 59 | 54.9% | 99.8% | 100% | 100% | 100% | 1 | 2 | 3 | 4 |
| RULE | con-leche | 1.82M | 314 | 49.1% | 91.1% | 98.4% | 99.6% | 100% | 2 | 5 | 9 | 16 |
| RULE | mathlib | 4.52M | 6,760 | 12.9% | 70.9% | 91.9% | 98.6% | 99.4% | 6 | 15 | 26 | 41 |
| TELE | init | 2.97M | 39,785 | 10.0% | 34.4% | 51.4% | 69.6% | 89.9% | 28 | 262 | 1,015 | 2,873 |
| TELE | cedar | 7.51M | 102,714 | 12.0% | 29.2% | 45.1% | 61.7% | 84.9% | 42 | 546 | 2,249 | 6,762 |
| TELE | con-leche | 7.59M | 36,342 | 10.9% | 36.5% | 54.7% | 72.9% | 93.4% | 23 | 182 | 555 | 1,399 |
| TELE | NSE | 12.9M | 117,264 | 6.7% | 27.5% | 42.2% | 62.2% | 86.6% | 48 | 432 | 1,827 | 5,924 |
| TELE | mathlib | 46.8M | 523,964 | 6.7% | 20.3% | 32.8% | 48.3% | 76.4% | 113 | 1,497 | 7,010 | 23,970 |

Dynamic `TELE` heat is the static heat times 1.3-1.7 with the same shape (cedar: 7.51 M executions against
4.61 M static spines; top 100 61.7% against 66.0%): applications are inferred about once each, plus re-inference
under other environments. The spike's static table was a good proxy for `TELE`, and says nothing about
`BODY`/`RULE`, which are far more concentrated (cedar, objects for 90%: 383 bodies, 17 rules, 2,249 telescopes).

Objects with at least N executions (count; in parentheses the share of the series' executions that happens
after the object crossed N, i.e. what a tier entered at N can touch):

| series | N | init | cedar | reach | con-leche | NSE | cslib | mathlib |
|---|---|--:|--:|--:|--:|--:|--:|--:|
| BODY | 1e4 | 40 (51%) | 62 (51%) | 31 (88%) | 51 (65%) | 88 (37%) | 152 (45%) | 391 (57%) |
| BODY | 1e5 | 1 (6%) | 7 (8%) | 6 (53%) | 8 (20%) | 1 (1%) | 8 (7%) | 40 (16%) |
| RULE | 1e4 | 10 (79%) | 11 (78%) | 5 (96%) | 14 (87%) | 10 (49%) | 21 (78%) | 39 (86%) |
| RULE | 1e5 | 1 (35%) | 3 (30%) | 3 (75%) | 3 (57%) | 0 | 7 (28%) | 11 (49%) |
| PROJ | 1e4 | 9 (66%) | 12 (66%) | 2 (92%) | 10 (77%) | 47 (48%) | 60 (55%) | 147 (70%) |
| PROJ | 1e5 | 1 (15%) | 3 (22%) | 1 (72%) | 1 (42%) | 1 (2%) | 5 (17%) | 17 (26%) |
| TELE | 1e4 | 48 (43%) | 90 (49%) | 1 (6%) | 101 (60%) | 162 (57%) | 230 (50%) | 496 (59%) |
| TELE | 1e5 | 3 (7%) | 10 (16%) | 0 | 10 (23%) | 16 (21%) | 22 (19%) | 61 (29%) |

At 10^6: one telescope (`Eq`) on cslib and on mathlib and nothing else; at 10^7: nothing. Who receives the
`BODY` executions (derived rules off):

| | init | std | cedar | reach | con-leche | NSE | cslib | mathlib |
|---|--:|--:|--:|--:|--:|--:|--:|--:|
| `casesOn` | 21.5% | 20.3% | 21.5% | 35.2% | 32.6% | 5.8% | 11.5% | 8.7% |
| matchers | 14.8% | 13.8% | 14.2% | 32.3% | 18.8% | 4.4% | 8.1% | 6.4% |
| structure projection functions | 21.1% | 21.7% | 19.8% | 1.3% | 11.2% | 49.0% | 36.9% | 42.8% |
| `brecOn` / `below` | 6.2% | 6.2% | 6.4% | 1.7% | 5.8% | 2.5% | 4.1% | 2.8% |
| compiler auxiliaries (`_f`, `.go`, `_unary`) | 8.9% | 8.0% | 8.8% | 13.6% | 14.0% | 2.6% | 4.9% | 3.8% |
| instances | 1.6% | 2.6% | 1.9% | 6.1% | 1.1% | 5.3% | 4.4% | 4.6% |
| other definitions | 25.6% | 27.2% | 26.5% | 9.9% | 16.2% | 30.3% | 29.8% | 30.6% |
| declared in Init | 100% | 95.5% | 93.6% | 98.9% | 93.4% | 40.7% | 61.3% | 47.5% |
| declared in Std / Mathlib | 0 | 4.5% | 3.6% | 1.0% | 0.1% | 55.3% | 38.3% | 52.5% |
| declared in the project | | | 2.9% | 0.0% | 6.5% | 4.1% | 0.4% | |

`RULE`: 94.9-100% of iota steps are on recursors declared in Init in every corpus (project recursors: cedar
3.5%, con-leche 3.9%). `PROJ`: Init constructors 98.6% on cedar, 99.4% on con-leche; Mathlib constructors
62.5% on NSE, 65.3% on mathlib. `TELE`: project constants receive 12.6% on cedar, 24.4% on con-leche, 7.0% on
NSE. A hot set prepared on Init carries over: share of a corpus's executions received by the K objects
hottest on Init (derived rules off):

| hot set from Init | init | std | cedar | reach | con-leche | NSE | cslib | mathlib |
|---|--:|--:|--:|--:|--:|--:|--:|--:|
| BODY top 300 | 94.5% | 88.8% | 86.1% | 73.5% | 81.6% | 30.7% | 50.7% | 34.7% |
| BODY top 1000 | 98.3% | 93.0% | 90.8% | 80.0% | 91.4% | 35.8% | 58.1% | 43.2% |
| RULE top 100 | 99.6% | 98.3% | 94.3% | 94.1% | 95.7% | 96.3% | 96.9% | 96.9% |
| PROJ top 100 | 99.2% | 97.3% | 97.5% | 99.4% | 98.8% | 34.2% | 49.9% | 34.2% |

Hottest names (count; the top 30 of every series and corpus are in `work/workloads/tables/heat-<corpus>.md`):

- **cedar BODY** `List.casesOn` 329k, `Int.casesOn` 130k, `HAdd.hAdd` 129k, `Add.add` 129k, `Nat.casesOn` 106k,
  `List.brecOn` 103k, `Bool.casesOn` 101k, `List.brecOn.go` 92k, `List.zipWithAll.match_1` 89k,
  `List.zipWithAll._f` 89k, `Option.casesOn` 84k, `instDecidableEqList.match_1` 78k, `List.map._f` 73k,
  `Decidable.decide` 67k, `dite` 57k. First Cedar name: `Cedar.SymCC.Term.casesOn`, rank 189, 2.5k.
  **RULE** `List.rec` cons 466k, `Nat.rec` succ 107k, `Int.rec` ofNat 100k, `List.rec` nil 91k, `Bool.rec`
  false 84k; first project recursor `Cedar.Spec.Value.rec` 2.1k (rank 28). **PROJ** `PProd.mk.0` 299k,
  `HAdd.mk.0` 120k, `Add.mk.0` 118k, `Decidable.intro.0` 63k. **TELE** `Eq` 904k, `HAdd.hAdd` 185k, `Eq.trans`
  183k, `congrArg` 170k, `List.cons` 164k, `And` 140k (98-99% in check mode).
- **reachability BODY** `Bool.casesOn` 476k, `List.casesOn` 463k, `List.getLast?.match_1` 446k,
  `List.filter.match_1` 384k, `List.elem._f` 384k, `Decidable.casesOn` 260k, then `Nat.decEq`, `decEq`,
  `instDecidableEqFin` at 68k each. **RULE** `List.rec` cons 929k, `Bool.rec` false 474k, `Decidable.rec`
  160k + 100k. **PROJ** `PProd.mk.0` 455k (91.6%).
- **con-leche BODY** `List.casesOn` 556k, `PSum.casesOn` 314k, `Bool.casesOn` 153k. **RULE** `List.rec` cons
  895k, `PSum.rec` inr 303k. **TELE** `PSum.inr` 824k, `Eq` 740k, `And` 268k.
- **NSE / mathlib BODY** `DFunLike.coe` 139k / 685k, `HAdd.hAdd` 85k, `CategoryTheory.Functor.obj` - / 488k.
  **RULE** (mathlib) `List.rec` cons 582k, `Bool.rec` true 499k, `Nat.rec` succ 387k. **PROJ** `DFunLike.mk.0`
  133k / 647k, `PProd.mk.0` 87k / 723k. **TELE** NSE `HMul.hMul` 873k, `Eq` 692k; mathlib `Eq` 3.12M,
  `DFunLike.coe` 917k, `congrArg` 908k.

Derived rules (nano-lean derives a constructor decision tree for a definition unfolded 32 times; RLVM's K2):

| corpus | rules derived | firings | delta on / off | iota on / off | eval misses on / off | instructions off / on |
|---|--:|--:|--:|--:|--:|--:|
| init | 282 | 364k | 1.48M / 2.27M | 491k / 675k | 20.6M / 23.8M | 1.06 |
| cedar | 553 | 693k | 2.77M / 4.15M | 857k / 1.24M | 50.3M / 55.7M | 1.04 |
| reachability | 142 | 886k | 143k / 3.42M | 1.05M / 1.69M | 6.47M / 22.0M | 1.89 |
| con-leche | 380 | 557k | 2.79M / 4.10M | 1.45M / 1.82M | 46.4M / 52.6M | 1.05 |
| navier-stokes-euler | 668 | 577k | 3.82M / 4.47M | 278k / 393k | 91.4M / 93.4M | 1.00 |
| cslib | 878 | 836k | 7.23M / 8.81M | 1.31M / 1.79M | 133M / 139M | 1.02 |
| mathlib | 2,240 | 2.60M | 19.8M / 24.6M | 3.22M / 4.52M | 375M / 393M | 1.02 |

### 3.5 Calls, hit rates, declarations without reduction

Whole cedar (other corpora: `tables/dyn-summary.md` D3): 42.0 M infer calls (13.7 M misses), 94.6 M eval calls
(50.3 M misses), 36.2 M `def_eq` calls (5.66 M not answered at once), 2.85 M `whnf` calls (1.09 M misses), 2.77 M
delta, 693 k rule firings, 857 k iota, 1.06 M projections, 117 k Nat operations; 34.8 M values made for 7.5 M
inferred applications (4.6 each, all phases included). Hit rates (cedar; the other library corpora are
within 5 points): infer memo 67%, eval memo 47%, `def_eq` answered by pointer identity or the equality cache
84% (rpylean: 85.5% identity + 5.4% union-find; gruszka counts only non-identical pairs and hits its cache on
37%), whnf memo 62%, unfold memo 14%, Pi-instantiation memo 60%.

Declarations by number of reduction steps (delta + firings + iota + projection + Nat ops + quotient; share
of checked declarations / share of their instructions):

| corpus | 0 steps | <= 10 | <= 100 | >= 10,000 steps |
|---|--:|--:|--:|--:|
| init | 55.8% / 9.2% | 78.9% / 18.6% | 94.3% / 37.0% | 24 decls / 10.5% |
| cedar | 57.5% / 9.0% | 81.3% / 18.2% | 95.8% / 37.4% | 32 / 5.9% |
| reachability | 64.0% / 1.5% | 84.9% / 2.7% | 97.8% / 4.7% | 6 / 93.4% |
| con-leche | 47.6% / 4.9% | 68.3% / 10.4% | 86.9% / 27.0% | 44 / 18.5% |
| navier-stokes-euler | 42.0% / 3.6% | 64.3% / 10.1% | 89.1% / 33.1% | 11 / 7.7% |
| mathlib | 44.9% / 6.5% | 69.2% / 15.2% | 91.6% / 38.0% | 100 / 3.1% |

### 3.6 Evaluation-style work

| corpus | Nat literal ops | <= 31 bits | >= 2^63 | max bits | by operation | iota on a Nat literal | String literal -> constructors (chars; longest) | K-rule | structure eta in iota | Quot |
|---|--:|--:|--:|--:|---|--:|--:|--:|--:|--:|
| init | 54.0k | 96.8% | 720 | 65 | gcd 30%, beq 18%, ble 15%, succ 12% | 17.5k | 10 (0; 0) | 242 | 1.4k | 216 |
| cedar | 117k | 97.1% | 1.7k | 129 | beq 28%, gcd 19%, ble 12%, succ 9% | 37.6k | 2,380 (13.5k; 17) | 2.3k | 9.0k | 4.8k |
| reachability | 74.3k | 99.8% | 0 | 39 | beq 92%, add 3% | 1.8k | 0 | 898 | 15 | 3.6k |
| con-leche | 79.2k | 94.4% | 1.2k | 66 | gcd 47%, beq 23%, land 6%, shr 6% | 25.1k | 4,320 (18.5k; 15) | 1.2k | 9.4k | 129 |
| navier-stokes-euler | 58.2k | 99.7% | 12 | 2,231 | add 29%, mul 19%, sub 14%, beq 13% | 33.0k | 0 | 7.2k | 1.5k | 5.0k |
| cslib | 166k | 98.9% | 612 | 65 | beq 31%, gcd 13%, ble 13%, add 12% | 77.6k | 41 (209; 17) | 5.8k | 10.9k | 16.7k |
| mathlib | 481k | 98.7% | 3.0k | 3,603 | beq 34%, add 19%, ble 16%, mul 7% | 278k | 96 (698; 19) | 26.1k | 24.7k | 37.6k |

Chains and depth. A non-memoised `whnf` call takes one iteration (a `whnf_core` and at most one delta or
Nat step) in 46% of the cases on cedar, 2-7 in 51%, 8-63 in 3.3%, 64 or more in 205 calls; the longest chain
is 194 on cedar, 325 on con-leche, 307 on reachability (1,498 with derived rules off), 630 on mathlib (3,149
off). The deepest native recursion of nano-lean in any corpus declaration is 952 KiB of stack (mathlib,
`Real.sin_pos_of_pos_of_le_one._proof_1_3`; 937 KiB on reachability): no corpus declaration needs 1 MiB, the
`perf/` tests need 184 MiB (section 4). Closed terms: 56% of cedar's delta steps and 68% of its `whnf_core`
steps have no free variable in the subject (reachability 84% / 99%, NSE 29% / 34%, mathlib 22% / 35%).

Closed-term engines of the leaders. gruszka's ground machine (call-by-need, entered when a declaration's
arena passes 2^26 values) ran **0 times on all eight corpora** (its own counter `gr`, knobs build).
rpylean's CEK machine has no counter. Its `--stats` reports 25 k delta unfoldings on reachability against
1.69 M iota and 31 M beta steps (nano-lean: 3.42 M delta with rules off, gruszka 2.29 M), consistent with the
six `Bench.Arena.*._proof_1` theorems (`Decidable.decide p = true`) running inside the machine or in untraced
fast paths [inferred; src: `eval_closed_scalar` is called only when a closed term meets a `Bool`/`Nat` scalar].

### 3.7 Cross-checks with rpylean and gruszka

| corpus | rpylean def_eq calls | identity | beta | delta (constants) | iota (recursors) | Nat ops | gruszka ticks | whnf misses | unfolds | def_eq calls | infer misses |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| cedar | 34.2M | 85.5% | 65.9M | 1.55M (18,896) | 2.43M (1,687) | 345k | 67.7M | 723k | 2.17M | 7.57M | 11.4M |
| reachability | 577k | 87.3% | 31.2M | 25k (1,037) | 1.69M (25) | 75k | 13.5M | 905k | 2.29M | 119k | 221k |
| con-leche | 35.2M | 87.4% | 74.7M | 894k (6,946) | 3.03M (92) | 269k | 58.1M | 751k | 2.16M | 5.52M | 8.95M |
| navier-stokes-euler | 89.1M | 80.8% | 151M | 8.22M (20,091) | 884k (67) | 224k | 132M | 1.36M | 3.58M | 17.1M | 18.5M |
| mathlib | 239M | 85.5% | 350M | 17.8M (81,339) | 7.35M (4,722) | 1.03M | 464M | 7.21M | 18.2M | 54.7M | 73.0M |

Three independent implementations agree: a library corpus needs one delta step per 200-600 bytes of export
and 5-20 times as many inference and conversion events (cedar delta: rpylean 1.55 M, gruszka 2.17 M, nano-lean
2.77 M + 0.69 M firings). rpylean's iota is concentrated like nano-lean's (cedar: `List.rec` 946k, `Int.rec`
517k, `Bool.rec` 249k, `Option.rec` 216k, `Nat.rec` 181k; top 10 recursors = 91.7%); its hottest delta entry is
a type-level wrapper (`outParam`, 59.5k on cedar, 630k on mathlib): telescopes run trivial bodies too.

## 4. The arena's `perf/` evaluation tests

Ranking by cost (teammate's baseline suite; instructions in G; `!` = timeout):

| test | nano-lean | gruszka | sokonanoda | rpylean | official |
|---|--:|--:|--:|--:|--:|
| unfold-before-literal-fallback | 14.65 | 19.92 | 14.32 | ! | 82.1 |
| proj-applied-struct-arg-bad (reject) | 14.66 | 5.79 | 4.78 | 13.32 | 26.7 |
| magma-list-pair-n21 | 12.60 | 17.19 | 28.46 | 6.28 | 103.8 |
| gf3-matrix-product | 6.59 | 16.92 | 24.01 | 19.48 | 13.9 |
| proj-applied-struct-bad / unfold-before-literal-bad (reject) | 4.89 / 4.88 | 5.79 | 4.77 / 4.80 | 4.15 / 4.14 | 27.8 / 27.6 |
| magma-list-deep-n36 | 2.33 | 50.95 | 22.26 | 1.37 | 200.1 |
| ite-taken-branch-bad (reject) | 1.50 | 3.41 | 6.00 | 173.5 | 38.5 |

Dynamic numbers of the five most expensive plus the two with the largest spread (instrumented nano-lean):

| test | instr | heavy decls | delta / firings | iota (on Nat literal) | proj | Nat ops (size) | longest whnf chain | stack | rules off / on | reduction phase | interning / walking (self) |
|---|--:|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| unfold-before-literal-fallback | 15.4G | 3 x 5.1G | 301 / 3.15M | 3.15M (3.15M) | 3.15M | 1.57M add (<= 22 bits) | 6 | 184 MiB | 0.88 | 99.6% | 41% / 28% |
| proj-applied-struct-arg-bad | 16.0G | 3 x 5.3G | 390 / 3.15M | 3.15M (3.15M) | 3.15M | 1.57M add | 8 | 184 MiB | 0.88 | 99.6% | 33% / 30% |
| magma-list-pair-n21 | 13.2G | `countermodel` 99.8% | 196k / 1.43M | 204k (127) | 411k | 197k beq (<= 5 bits) | 11 | 69 KiB | 1.60 | 99.6% | 46% / 26% |
| gf3-matrix-product | 5.44G | `Gf3Bench.big._proof_1_1` 99.5% | 892 / 4.7k | 2.6k (2.4k) | 263 | 52.4k, 97% > 4096 bits, max 1,219,921 | 1,571 | 6 KiB | 1.01 | 99.1% | bignum 86% (24% hashing literals) |
| unfold-before-literal-bad | 5.35G | 1 x 5.3G | 106 / 1.05M | 1.05M (1.05M) | 1.05M | 524k add | 6 | 184 MiB | 0.88 | not profiled | not profiled |
| magma-list-deep-n36 | 2.60G | `countermodel` 98.5% | 3.3k / 892k | 4.8k | 881k | 11.8k add, mod | 1,296 (5,188 off) | 583 KiB | 10.25 | 98.0% | 49% / 6% (+17% templates) |
| magma-string-pair-n9 | 2.83G | `countermodel` 57.7% | 99k / 270k | 70k | 217k | 49k add, mod, mul | 756 | 317 KiB | 1.63 | 73.2% | 26% / 27% |

Hot objects: `unfold-before-literal-*` and `proj-applied-struct-*` run one loop per theorem, 2^19 iterations
and 2^19 deep: per iteration two `Nat.rec` steps on a literal (nano-lean builds a `Nat.succ` cell each time),
two rule firings (`HAdd.hAdd`, `Add.add`), two projections and one native add; derived rules make it slower
(0.88). `magma-list-pair-n21`: `Nat.decidableBallLT._f`, `Bool.casesOn`, `Decidable.decide`, `Nat.decEq`,
`instDecidableEqFin` at 194-204k each. `magma-list-deep-n36`: `List.get!Internal._f` 841k (list indexing by
structural recursion; with derived rules off 3.44 M unfoldings and 1.70 M `List.rec` steps).
`magma-string-pair-n9`: one 179-character literal expanded once, then `List.concat._f` 31k, `List.get._f` 16k.
These are the only inputs on which code objects exceed 10^6 executions (2 bodies and 2 rules in the first
two tests; 12 bodies and 3 rules above 10^5 in `magma-list-pair-n21`), and on which the leaders differ by
10-100x; they are decided by algorithmic choices (literal folding order, derived rules, a call-by-need
machine without interning: rpylean is best on both magma-list tests) before any code quality matters.

## 5. Conclusions for the machine

**(i) Heat and promotion thresholds.** Counts per corpus are in section 3.4. Translating executions into the
ISA's rent: reduction-phase instructions per reduction step are 16.0 G / 5.4 M = 3.0 k on cedar and 10.3 G /
2.6 M = 4.0 k on reachability [est: phase share x process instructions / (delta + firings + iota + proj);
nano-lean's cost, including the evaluation and interning under the step]. A block that owns 3 k instructions
per execution reaches RENT = 3.3 G after 1.1 M executions. Nothing in a corpus does; the hottest body of
mathlib (`DFunLike.coe`, 685 k) has consumed at most about 2 G by the end of the run. A threshold that would
let T2 see anything on cedar has to sit at 10^4-10^5 executions (62 / 7 bodies, 11 / 3 rules), where the
remaining work is 51% / 8% of unfoldings, i.e. at most 25% x 0.51 = 13% (10^4) or 2% (10^5) of cedar's
instructions [est: reduction share x share after crossing], of which compiled code removes only the walking
part (11.6 / 25.0). An upper bound for T2 on cedar is therefore about 6% at a 10^4 threshold, before
compilation cost (0.4-0.5 G per root x 73 roots = 29-37 G, half of cedar's total) [est]. **T2 cannot pay on
library corpora under any threshold.** ISA §8.5 expects the hottest telescopes and projection bodies of
mathlib to run 10^6-10^7 times; measured with one worker: `Eq` 3.1 M, the next telescope 0.92 M, the hottest
projection body 0.69 M, the hottest rule 0.58 M - and a quarter of that per worker with four workers.

**(ii) Which tier for which workload.**

| workload | where | K0 generic walk | K1 elaborated code | K2 derived rules | T2 compiled |
|---|---|---|---|---|---|
| large inference/conversion proofs | all library corpora; 57-81% of declarations do <= 10 reductions | carries 60-75% of instructions (inference + conversion phases) | `TELE` of the top 100-1000 constants (62-85% of 7.5 M executions on cedar); `BODY` of the Init hot set | 1.00-1.06x | nothing to compile |
| reduction-heavy proofs inside large terms (`omega`/`grind` certificates, con-leche `keyOf_*`, mathlib `decide`) | 3-20 of a corpus's top 50; 5-18% of instructions | | `BODY`/`RULE` of 100-300 Init objects | 1.1-1.5x per declaration, 2.5x at most (measured `nod x`) | no: 10^4-10^5 steps per declaration |
| closed evaluation (reachability's 5 proofs, `magma-*`, `unfold-before-literal*`, `proj-applied-struct*`, `ite-taken-branch-bad`) | 1 corpus + about 15 `perf/` tests | | all of the work | 1.6-10x (0.88x on the literal loops) | the only candidate: 2-17 blocks at >= 10^5-10^6 executions; bounded by interning (33-49% of instructions) |
| big-number arithmetic (`gf3-matrix-product`) | 1 test | | | | no: 86% in the bignum library, 24% hashing literals |

For the first row the levers are the ones the leaders already use and RLVM's design already has: memo hit
rates (infer 67%, eval 47%, def_eq 84%), not evaluating unread arguments, cheap interning. One third of all
instructions is term walking in every corpus, which is the argument for elaborating **telescopes** (12.9% of
cedar's instructions are evaluation under inference) at least as much as bodies.

**(iii) Specialisations, with the measured frequency of each pattern.**

| specialisation | pattern frequency | verdict |
|---|---|---|
| instance-projection shortcut (a structure projection function applied to a constructor: `HAdd.hAdd α α α inst a b` -> field) | structure projection functions are 19.8% of unfoldings on cedar, 49.0% on NSE, 42.8% on mathlib; projections of constructors 1.06 M on cedar, 9.6 M on mathlib; `HAdd.mk.0` + `Add.mk.0` = 22% of cedar's | do it in K1 (the ISA's `PROJARG` LEAF shape): the most frequent reduction pattern of every Mathlib-based corpus |
| `casesOn` / matcher decision trees (case of known constructor without building motive and minors) | `casesOn` + matchers = 35.7% of unfoldings on cedar, 67.5% on reachability, 51.4% on con-leche, 15% on mathlib; each is followed by an iota step (1.24 M on cedar) | do it (K2 `RULES`): this is what nano-lean's rules buy, 1.9x on reachability |
| derived rules for structural recursion (`brecOn`/`below`, `PProd` tables, `_f` auxiliaries) | `brecOn`/`below` 6.4% + auxiliaries 8.8% of cedar's unfoldings; `PProd.mk.0` = 28.2% of cedar's projections, 91.6% of reachability's | do it together with the previous line; removes most `PProd` traffic |
| packed mutual/well-founded arguments (`PSum`, `PSigma`, `WellFounded.fix`) | con-leche: `PSum.casesOn` 314 k unfoldings, `PSum.rec` 303 k iota, `PSum.inr` 824 k telescope executions (one declaration is 10% of the corpus); `WellFounded.fix`/`Acc.rec`: 451 static spines on cedar | cover `PSum`/`PSigma` `casesOn` by the decision-tree rule; nothing special for `WellFounded.fix` |
| Nat small-integer fast path (owner note 7) | 94.4-99.8% of Nat literal operations have operands <= 31 bits, but a corpus executes only 54 k-481 k of them (cedar: 117 k in 64 G instructions) and 17 k-278 k iota steps on literals; `perf/`: 1.57 M adds + 3.15 M literal iota in one test, 197 k `beq` in another | cheap and right, but invisible on corpora; it is a `perf/` feature (literal `Nat.rec` without a `Nat.succ` cell is the measurable part). Big operands (>= 2^63: 0-3.0 k operations per corpus; 50.8 k on 1.2 Mbit numbers = 86% of `gf3-matrix-product`) need only a good bignum library and cheap hashing of big literals |
| packed `String` / `Char` | string literal expansions: 0-4,320 per corpus, at most 19 characters, 13-18 k characters in total on cedar and con-leche; `magma-string-pair-n9`: one 179-character literal | not justified |
| packed `List` / `Array` / `BitVec` storage strategies (owner note 8) | `List.rec` is the hottest rule everywhere (cedar 45% of iota, reachability 56%) but the lists are produced by evaluation (`List.map._f`, `List.filter`), not literals; `Array.rec` 2.4 k, `ByteArray.rec` 3.6 k, `BitVec.ofFin.0` 6.8 k on cedar | not justified by any corpus; keep the tag space, build nothing |
| not interning transient values | construction + interning + hash tables = 25% of instructions on cedar, 42% on reachability; construction alone 33-49% on the evaluation tests; rpylean's CEK machine (no interning) is 2x faster than nano-lean on `magma-list-pair-n21` and 1.7x on `magma-list-deep-n36` | the largest lever on evaluation workloads, and the one a compiled tier needs (escape analysis cannot remove a hash-cons probe); design question for ISA §10 |

## 6. Fast benchmarks for iterating on the machine

`bench/workloads/slice-export.py` cuts an export down to the dependency cone of named declarations and
renumbers the ids; with `--axiomatize`, library theorems are kept as axioms (statement only), so that only
the named declarations and the definitions they need are checked. All three slices below are accepted by the
official `leanchecker --from-export` (4.36.0) and by nano-lean.

| benchmark | file | size | decls | nano-lean `di` | phases (inference / conversion / reduction / import) | use |
|---|---|--:|--:|--:|---|---|
| Cedar's 172 most expensive own declarations (50% of Cedar's own cost), statements-only library | `work/workloads/slices/cedar-hot-ax.ndjson` | 133 MB | 9,747 | 13.6 G, 3.4 s | 43.7 / 32.3 / 11.9 / 6.7% | **the Cedar-level benchmark**: 91.6% of its checking instructions are in the 172 proofs; official checker 20 s |
| the same with the full cone (library proofs checked) | `slices/cedar-hot.ndjson` | 294 MB | 24,176 | 25.0 G, 7.4 s | 44.1 / 26.9 / 17.9 / 7.6% | arena-faithful variant (no added axioms); 50% in the 172 proofs |
| every 16th of Cedar's own declarations by cost rank, full cone | `slices/cedar-s16.ndjson` | 337 MB | 30,301 | 26.6 G, 8.5 s | 43.3 / 25.4 / 20.0 / 8.2% | unbiased sample of Cedar proofs (1,403 targets = 1.5 G), dominated by its library cone |
| evaluation | `work/exports/reachability.ndjson` | 19 MB | 4,846 | 11.1 G, 3.4 s | 4.1 / 1.2 / 93.0 / 1.2% | `casesOn`/matcher/`brecOn` on `List`, `Bool`, `Decidable`; 68 k Nat `beq`; derived rules worth 1.9x |
| depth and literals | `work/arena-site/tests/bad/perf/unfold-before-literal-bad.ndjson` | 65 kB | 59 | 5.3 G, 1.4 s | reduction 99.6% (measured on the `-fallback` variant) | one `Nat.rec` loop on a literal, 2^19 deep, 524 k native adds; expected verdict: reject |

Cedar's own declarations as a whole are 47 / 30 / 17% inference / conversion / reduction (section 3.2);
`cedar-hot-ax` is 47 / 35 / 13% of its checking instructions, in 3.4 s instead of 30 s and 133 MB instead of
905 MB; the full-cone slice has cedar's heat shape (`BODY`, rules on: top 100 = 72%, cedar 70%). Not
recommended: `init.ndjson` (38% reduction, more than Cedar's own declarations), con-leche (one declaration
is 10% of it), cslib (94.6% of it is Mathlib).

## 7. What this changes in the design documents

- ISA §8.5 (promotion): the expectation for mathlib does not hold (section 5 (i)). Either T2 is scoped to
  closed evaluation, entered by a per-declaration signal such as "10^4 reduction steps in this declaration"
  (reached by 0.01-0.14% of declarations: 2-18% of a library corpus's instructions, 93% of reachability's),
  or it is dropped from v1 without loss on any library corpus.
- ISA §3.6 (K2 trigger at 32 executions): workable as it is (nano-lean derives 553 rules on cedar, 2,240 on
  mathlib with it); rules are not free: the `unfold-before-literal` family is 12% slower with them.
- ISA §3.1 ("about 80% of all nodes are executed once"): 93-95% of nodes are private to one declaration, and
  infer misses per node are about 1 (cedar: 13.7 M misses for 14.7 M nodes).
- A prepared hot set: because 82-91% of cedar's and con-leche's `BODY` executions fall on the 300-1000 bodies
  hottest on Init and 94-96% of their `RULE` executions on Init's 100 hottest rules, elaboration (and K2
  derivation) of those objects can be done eagerly when their declarations are admitted, without heat
  counters, for every corpus that contains Init - which is all of them. Mathlib-based corpora need the same
  for Mathlib's structure projections, which a generic rule for structure projection functions covers.

## 8. Reproduction and raw data

Scripts (`bench/workloads/`): `static-shape.rs` + `static-report.py` (section 2); `build-nano-lean-instrumented.sh`,
`nl-run.sh VARIANT NAME` (`di` | `stats` | `nod`), `nl-perf-tests.sh`, `nl-perf-record.sh` + `perf-phases.py`
(phases), `dyn-report.py` (sections 3-4), `origin-split.py` (3.2), `portable-hot-set.py` (3.4),
`rpylean-stats.sh`, `build-gruszka-knobs.sh`, `gruszka-stats.sh`, `cross-report.py` (3.7), `slice-export.py`
(section 6). All third-party code ran in `bench/arena-docker.sh` containers pinned to CPUs 0-4 and 10-12 (one
PMU, outside `--arena-limits`). Raw data (`work/workloads/`):

| path | content |
|---|---|
| `static/<corpus>/{heat.txt,consts.tsv,segs.tsv,summary.txt}` | HEAT lines in the spike's format; per-constant kind, flags, static heat; per-declaration nodes and private nodes |
| `dyn/nl/<corpus>.di.tsv`; `.stats.tsv`, `.nod.tsv` | per declaration: instructions and ns (uninstrumented checking code); 58 counters and the hottest unfolded constants and recursors (derived rules on / off) |
| `dyn/nl/<corpus>.dump.tsv`, `.nod.dump.tsv` | per constant: `C` delta (whnf, lazy, closed), firings, telescope executions; `I` iota per rule; `PW`/`PE` projections; `N` Nat ops by size; `S` string expansions; `W` whnf chain histogram |
| `dyn/nl-perf/`, `dyn/nl-slices/` | the same for 17 `perf/` tests and the three slices |
| `dyn/rpylean/<corpus>.{stats.txt,decls.jsonl}`, `dyn/gruszka/<corpus>.di.tsv` | the leaders' own statistics |
| `perf/<name>.phases.txt`, `.folded.gz` | phase and self-instruction split, collapsed stacks (`project-*` = own declarations only, `perf-*` = perf tests, `slice-*` = slices) |
| `tables/`, `slices/` | `static.md`, `dyn-summary.md`, `origin.md`, `portable.md`, `cross.md`, `top50-<corpus>.md`, `heat-<corpus>.md`, `perf/`, `slices/`; name lists and the sliced exports of section 6 |
| `build/nano-lean-instrumented/` (+ `.patch`), `build/gruszka-knobs/` | the instrumented copy and its diff against v0.4.2 (`src/wl.rs` and the `wl!` call sites, 10 files), and the unmodified gruszka tree built with `--features knobs` |

## 9. Not measured, and limits

- The official kernel's own unfold counts (it cannot be instrumented); sokonanoda and pestka were not profiled;
  lengths of list literals (cons chains in the export) were not measured, only `List.rec` steps.
- Instructions were attributed to phases by sampling and to declarations exactly, **not** to individual constants: "cost per execution" figures are phase averages [est].
- nano-lean's `-j 1` session sees every declaration; with the arena's 4 workers each worker's counts are
  about a quarter, which makes every threshold of section 3.4 harder to reach, not easier.
- rpylean's CEK entries are inferred from its counters, not counted; mathlib's top-50 list was inspected by
  hand only for the first 16 entries; roles are classified by name (`*.casesOn`, `*.match_N`, `*.brecOn`,
  `inst*`, `_f`) and by the shape `fun .. self => self.i`, so unusual names fall under "other definitions".
