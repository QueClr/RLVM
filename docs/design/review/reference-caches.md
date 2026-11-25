# The caches of the reference engine against the official kernel's (R2)

2026-11-24, 21:28Z - 2026-11-25, 02:5xZ. Owner of `rlvm-ref` after L. Reason for this audit: finding S3-F2 (reviewer S3,
21:21Z), a wrong ACCEPT of the reference engine, the oracle of every gate. Official source read:
`refs/lean4/src/kernel/` at v4.36.0 (`type_checker.cpp/.h`, `expr_eq_fn.cpp`, `expr_maps.h`, `replace_fn.cpp`,
`instantiate.cpp`, `inductive.h/.cpp`, `environment.cpp`, `local_ctx.cpp`, `level.cpp`), the reader
`refs/lean4-leanexport/src/LeanExport/Parse.lean` and `Lean/Replay.lean`. Official verdicts: `leanchecker
--from-export` of Lean v4.36.0 inside the arena's Docker image (`work/r2/official.sh`, outputs in
`work/r2/official/`), never an engine of ours. Everything of this audit is under `work/r2/` (generators `gen/`,
exports `exports/` with `batch1.tsv`, `batch2.tsv`, scripts `bin/`, the one-JVM judge `src/RBatch.java`, the patch for
the loader `patches/`, raw results `out/`, `data/corpora.tsv`, `logs/`).

## 0. Result

1. **S3-F2 is not a kernel cache.** `rlvm-load` drops `mdata` records (the wrapped expression stands for the
   record; the store has no such node), so `a = mdata b` reaches the kernel as the term `a = b`. Officially an
   `mdata` node is a term of its own for every cache and hides what is under it from the kernel's syntactic tests.
   Same cause, other direction: `s3-mdata-eager-hidden-ok` (official accept) was a wrong REJECT, and so were the
   four exports of **R2-F4** (`r2-mdata-beta-*-ok`: `cheap_beta_reduce` behind a node; section 6.1). That direction
   is closed for the whole class by the launcher's safety net (section 6.1): a practical REJECT on a file with a
   dropped node is not reported, the strict verdict is.
2. **R2-F1** (new, same class): the `nondep` flag of `letE` is part of the official term identity and the store
   does not keep it. `r2-id-let-nondep-bad`: official reject, reference accept.
3. **R2-F2** (new): **the official verdict can depend on object identity** - on whether a subterm is one record of
   the export or two - through the pointer test `is_eqp(t_n_n, t_n)` after the second `whnf_core` of
   `is_def_eq_core`. `r2-ptr-other-record-bad` (official reject) and `r2-ptr-same-record-ok` (official accept) are
   THE SAME TERMS. No store that identifies equal terms can reproduce this; the published reference engine accepts
   both.
   **R2-F3** (new, admission, both engines): the official `add_inductive` runs every judgment in a fresh checker;
   RLVM's admission keeps the kernel's caches through a phase. `r2-adm-param-success-bad`: official reject, RLVM
   accept.
4. Everything else that decides what "the same term" is agrees with the official kernel (binder names and binder
   infos ignored, two records with one content one term, levels by spelling, literals by value), and every cache is
   read, written and cleared where the official one is, with two corrections made here that change no verdict I
   could construct (the key of the entry that follows an unfolding; `insert` never replaces).
5. All four findings are **kind T** (OWNER-NOTES item 13): the statement the engine accepted is derivable and the
   official checker itself accepts the same proof term in another cache state or with another sharing of records
   (certificates: the twins `-ok` of each export, section 6).
6. **Policy** (OWNER-NOTES item 13, "practical"): the reference engine now has two modes.
   `-X ref.exact=off` (the default; what the practical policy uses for re-checks and fallbacks): never declines
   for the sake of agreement on adversarial files; behaves as before on the three findings.
   `-X ref.exact=official` (the strict mode; `-X policy=strict` sets it): the official verdict or none. Object
   identity is handled exactly where it can be (a probe, then a check of the declaration under both possibilities)
   and declined only where the verdict really depends on it; the judgments of admission start with empty caches;
   `mdata` and `nondep = true` are declined by the loader's strict switch (`LoadOptions.strictMetadata`: LP2's
   publication of 22:55Z for `mdata`; for `nondep` mine, with the modules handed to me at 23:1xZ:
   `rlvm-load` b23a88e4c30b, `rlvm-export` dc8c4f1ef79a).
   **Cost of the strict declines on the eight corpora: zero declarations** (section 7).

## 1. What the official kernel takes for the same term

All official caches are keyed by `expr` with `std::equal_to<expr>` = `is_equal` = `expr_eq_fn<false>`
(`expr_maps.h`, `expr_eq_fn.cpp`): STRUCTURAL equality, pointer equality only as a shortcut. The pair sets
`m_success` / `m_failure` use `expr_pair_eq` (both components `==`), stored and looked up in hash order, i.e. as
unordered pairs. What that equality sees, what the official reader makes of an export, and what our store does:

| Part of a term | Official `==` | Official reader (`Parse.lean`) | RLVM (`rlvm-load`, overlay of `rlvm-store`, `RefKernel.canon`) | Same? | Export (official verdict) |
|---|---|---|---|---|---|
| binder name, binder info of `lam` / `forallE` | ignored (`CompareBinderInfo = false`) | kept in the object | not stored | yes | `r2-id-binder-name-ok`, `-info-ok`, `-name-def-ok` (accept; ours accept) |
| two records with one content | one key | two objects (`exprMap : id -> Expr`) | one id after `canon` (the loader merges nothing: LP2, 22:05Z) | yes | `r2-id-dup-record-ok`, `-def-ok` (accept; ours accept) |
| universe levels of `sort` / `const` | structural (`level ==`: kind, hash, depth, children) | as written | interned structurally, no normal form at load | yes | `r2-id-level-spelling-bad`, `-imax-spelling-bad` (reject; ours reject), `-spelling-ok` |
| Nat / String literals | by value (`lit_value ==`) | one object per record | below 2^64 in the node; above: `LitPool`, interned by content | yes (see D5) | `r2-id-big-literal-dup-ok`, `-small-literal-dup-ok` (accept; ours accept), `-literal-vs-offset-bad` (reject) |
| `mdata` node | part of the term (`mdata_expr`, `mdata_data` compared) | `.mdata {} e` | **dropped by the loader** | **no: D1** | `s3-wc-pos-mdata-bad` (reject; ours ACCEPT), `s3-mdata-eager-hidden-ok` (accept; ours REJECT) |
| `nondep` of `letE` | part of the term (`let_nondep(a) == let_nondep(b)`; not in the hash) | kept | **not stored** (`Kind.LET`) | **no: D2** | `r2-id-let-nondep-bad`, `-def-bad` (reject; ours ACCEPT), `-ok`, `-same-ok` |
| a beta redex and its reduct, a literal and `Nat.succ ..` | different terms | - | different ids | yes | `r2-id-beta-control-bad`, `s3-wc-pos-beta-bad` (reject; ours reject) |
| free variables | by name; a fresh name per binder opened (`m_ngen` of the checker) | - | fresh FVAR node per binder, never hash-consed | yes | S3's `s3-wc-canon-fvar-*` |
| WHICH OBJECT a term is | not seen by `==`; seen by `is_eqp` | one object per record id; `add_theorem` runs `sharecommon` over value and type (one object per structure INCLUDING binder names), `add_definition` / `add_opaque` do not | not represented: one id per term | **no: D3** | `r2-ptr-other-record-bad` (reject) / `r2-ptr-same-record-ok` (accept): the same terms |

The probe behind most of these exports is S3's: one declaration `And.intro W (And.intro W0 W2)` with
`W = use (a = b) (Eq.refl a)` (true while `m_whnf_core` is empty: both projections stay stuck and
`lazy_delta_proj_reduction` compares `f intro` with `f h`), `W0 = use (a = 5) (Eq.refl 5)` (the full `whnf_core`
of `a` is cached: 5) and `W2 = use (a = b') (Eq.refl a)` for a variant `b'` of `b`. If `b'` is the same term as
`b` for the caches, `m_success` answers `W2` (accept); if not, the cheap `whnf_core` of `a` answers 5 and `W2`
fails (reject).

## 2. The audit, cache by cache

`RefKernel` is a port of `type_checker.cpp` over canonical ids: every term that enters the kernel is copied into
the worker's overlay through hash-consing constructors (`canon`), so structural equality is identity of ids and
every table below is an `int` table keyed by id. "W" = written, "R" = read.

| Official cache | Key, equality | Read | Written | Cleared | `rlvm-ref` | Divergence |
|---|---|---|---|---|---|---|
| `m_infer_type[0]` (check) | term, structural | `infer_type_core` entry, every kind of term | at the end of `infer_type_core`; a prefix `f a1..ai` of an application when the recursive `infer_app` returns | with the `type_checker`: one per `add_decl` | `inferCheckCache`; the type of a prefix is kept as a pending pair (remaining type, pending arguments) and built on first look-up: the arguments are closed, the term is the official one | none |
| `m_infer_type[1]` (infer only) | as above | as above (`infer`, `is_prop`, proof irrelevance, eta, unit-like, K, structure eta, `infer_proj` of a constructor type) | as above | as above | `inferOnlyCache` | none |
| `m_whnf_core` | term (App, Let, Proj only), structural | by EVERY `whnf_core` call after the easy cases - also the cheap ones (`cheap_proj = true`: step 3 of `is_def_eq_core`, every lazy delta step) | only by full calls, and only by the frames that end in `r = ..`: a projection (reduced or stuck: `(e, e)`), a beta step, an application whose head changed, a let. NOT by an iota / quotient step (`return whnf_core(*r)`), a stuck application (`return e`), a let variable. `insert` never replaces | per declaration | `whnfCoreCache`: the same frames (the tail calls are a loop; the frames' keys are kept in a list and written at the end). **Corrected here**: the write does not replace an entry (D7) | D3 (which OBJECT a stuck projection's entry returns), D4, D7 |
| - its entry after an unfolding | the term `value a1 .. an` (`unfold_definition` builds it, `whnf_core` looks it up and, if full, stores it) | lazy delta steps (cheap), `whnf` (full) | `whnf` only | per declaration | `unfoldWhnfCache`, keyed by `c a1 .. an` (the intermediate term is not built when the value is a lambda). **Corrected here** (D4): one logical table with `whnfCoreCache` | D4 |
| `m_whnf` | term (not BVar, Sort, Pi, Lit, non-let FVar; mdata is stripped first), structural | `whnf` entry | at each exit of the `whnf` loop | per declaration | `whnfCache` | none |
| `m_success` | unordered pair of terms | `quick_is_def_eq` (first line), i.e. at the entry of `is_def_eq_core`, after the cheap `whnf_core`, and at the end of every lazy delta step | `is_def_eq` (not `is_def_eq_core`) on success | per declaration | `success` (`PairSet`); `is_def_eq(t, t)` returns before the set is touched (the pair `(t, t)` is never needed) | none |
| `m_failure` | unordered pair of terms | `lazy_delta_reduction_step`, same regular head on both applications, before the arguments are compared | there, when levels or arguments are not equal | per declaration | `failure` | none |
| `m_unfold` | constant with a non-empty level list | `unfold_definition_core` | there | per declaration | `unfoldCache`; a function of the key | none |
| the equivalence manager | - | - | - | - | does not exist in v4.36.0 (`type_checker.h`: "plain pair sets, not a union-find structure"); nothing transitive is kept here either | none |
| `instantiate`, `instantiate_rev`, `instantiate_lparams`, `abstract`, `replace_fn` | per call, by POINTER of shared nodes (`is_shared` / `is_likely_unshared`: reference counts) | - | - | per call | `TermOps` on hash-consed nodes; `instCache` (stored term, CONST node) for level instantiation: functions of the terms | only through D3: they decide which results are one object |
| `m_eager_reduce` | a flag, set while the argument of an application is `eagerReduce _ _` | `is_def_eq_core` (Bool.true), `lazy_delta_reduction` (Nat folding with free variables) | `infer_app` | - | `eagerReduce`; the caches are shared between the two modes, as officially (S3's `s3-mdata-eager-plainform-bad`, package S's S-F5 agree) | none |
| lifetime | one `type_checker` per `add_decl` (`Replay.lean`: one `addDecl` per constant; a theorem's type and value share the checker); `add_inductive`: a FRESH checker for every judgment (`tc()`), except three groups | | | | `Session.check`: `begin` clears everything, the overlay is cut back after the declaration; admission (`KernelOps`): one scope per phase | D6 (admission, not this module) |

The official `is_def_eq` is not a function of its arguments: `m_whnf_core` is read by cheap calls and written by
full ones, so what was reduced earlier in the declaration changes what a later comparison sees (S3, 21:21Z). The
port has the same reads and writes; S3's 423 exports (23:00Z) give the official verdict except the two with `mdata`.

## 3. Object identity (R2-F2, D3)

`type_checker.cpp` uses pointer equality on terms in two places, both in `is_def_eq_core`:

```cpp
expr t_n = whnf_core(t, false, true);  expr s_n = whnf_core(s, false, true);
if (!is_eqp(t_n, t) || !is_eqp(s_n, s)) { r = quick_is_def_eq(t_n, s_n); ... }          // (P1)
...
expr t_n_n = whnf_core(t_n);  expr s_n_n = whnf_core(s_n);
if (!is_eqp(t_n_n, t_n) || !is_eqp(s_n_n, s_n)) return is_def_eq_core(t_n_n, s_n_n);     // (P2)
```

`whnf_core(e)` returns an object that is structurally `e` but not `e` itself in exactly one situation: `e` is a
stuck projection, `m_whnf_core` has its entry `(e', e')`, and `e'` (the object that was the argument of the full
call that wrote it) is another object than `e`. (Stuck applications return their argument and are never cached;
every other cached value differs structurally from its key, or the reduction would not terminate.)

- **(P1)** repeats `quick_is_def_eq` on structurally the same terms. It gives the same answer: the easy cases
  look at the structure and at `m_success`, and nothing the cheap `whnf_core` of the other side can record (K
  tests compare types whose head is an inductive type) is the pair in hand. No divergence.
- **(P2)** runs the whole of `is_def_eq_core` a second time on the same terms. This "second pass" recomputes what
  the first did with the caches the first left, and computes what the first did not: proof irrelevance on the
  terms lazy delta produced (`infer_type`, `is_prop`: full `whnf_core` of types), `lazy_delta_proj_reduction`
  again, a failed K test again. What it leaves in `m_whnf_core` changes later cheap calls.

When is `t_n` not the entry's object? Every projection that a full `whnf_core` returns, and every one a cache hit
returns, is its entry's object. A CHEAP call that finds a projection stuck by itself returns its own argument and
writes nothing ("fresh"). So (P2) fires iff a fresh stuck projection in hand (from step 3 or from a lazy delta
step) has no entry when it is produced and has one, written by a full `whnf_core` on another occurrence, when the
second `whnf_core` asks. (If `try_unfold_proj_app` runs the full `whnf_core` on the object itself, or the second
`whnf_core` is the first full call, the object writes its own entry and nothing happens.) Whether "another
occurrence" is another OBJECT is decided by the record ids of the export (reader), by `sharecommon` (theorems
only), and by the kernel's own allocation (`instantiate` copies what contains a bound variable; `replace_fn`
caches by reference count): not by the terms.

**Export** (`work/r2/gen/batch2.py`): `cX := X`, `cS := Nat.succ X'` with `X = S.q.1` a stuck projection and
`X'` either the same record or a second record with the same content. `D cX =?= D cS` compares the arguments
first; lazy delta unfolds both constants in one step (equal heights) to `X` (fresh) and `Nat.succ X'`; the next
round folds `Nat.succ X'` (closed terms): `whnf(X')` writes the entry; nothing else applies; the second
`whnf_core(X)` is answered with `X'`. If `X'` is another object, the second pass runs: its proof-irrelevance step
infers the type `c0` of `X'` and asks `is_prop(c0)`; `infer_type(c0) = U = (F intro).1` is not a sort, `whnf(U)`
runs the full `whnf_core` and caches `Type`. The comparison fails either way and `D` is unfolded (`5 = 5`). Later
`needs c0` compares `U` with `U' = (F h).1`: true without the entry (`lazy_delta_proj_reduction`, proof
irrelevance), false with it.

| Export | `X'` | Official | Reference before | `ref.identity=same` | `ref.identity=other` | strict |
|---|---|---|---|---|---|---|
| `r2-ptr-other-record-bad`, `-def-bad` | second record | **reject** | accept | accept | reject | decline |
| `r2-ptr-same-record-ok`, `-def-ok` | same record | **accept** | accept | accept | reject | decline |
| `r2-ptr-other-record-order-ok` (`needs c0` first) | second record | accept | accept | accept | accept | **accept** (both ways) |
| `r2-ptr-alone-ok` (no hazard) | - | accept | accept | accept | accept | accept |

The two columns `same` / `other` are the two official continuations; each reproduces the official verdict on the
files where it is the truth and the opposite one where it is not, which is the evidence that the model of (P2)
is the official behaviour.

**What the engine does** (`RefKernel.isDefEqCore` step 10, `whnfCore`, `tryUnfoldProjApp`, `lazyDeltaStep`):
the result of every `whnf_core` carries whether it is a fresh projection object (`wcFresh`); an "event" is a fresh
projection that an entry answers at step 10 with both terms unchanged.

- practical (`ref.exact=off`, the default): go on as if the occurrence were the object in hand (the engine as it
  was). Never a decline. Kind T accepts possible (the two `-bad` rows).
- strict (`ref.exact=official`):
  1. **probe** (`secondPassRepeatsNothing`): the second pass is run with the caches FROZEN - any new entry of
     `m_whnf_core`, any new pair of the success or failure set, a K test that no cache answers, a nested event
     ends the probe; function memos (inferred types, `m_whnf` of terms whose reduction needed no such entry) may
     be written, they hold what any later computation finds. If the probe reaches its own step 10 without deciding
     anything, the second pass is a repetition of cache hits and both continuations are the one in hand: go on.
  2. otherwise a **hazard**: the session (`RefSession.decide`) checks the declaration again from the start with
     this hazard decided each way (and further hazards of each branch likewise, at most 63 checks of one
     declaration). If all verdicts agree, that is the official verdict whatever the objects are. If they differ,
     the declaration is declined (UNSUPPORTED_INPUT, the message names both verdicts).
  3. One more situation is declined outright (never seen): a full `whnf_core` that finds, when it stores a stuck
     projection, an entry that a nested call stored for the same term.
  Judgments of admission through `KernelOps` have no session to decide for them: a hazard there is a decline.

## 4. Divergences

| | What | Can change a verdict? | Export, official verdict | Kind | Fix / mode |
|---|---|---|---|---|---|
| D1 | `mdata` dropped by the loader (S3-F2) | yes, both directions | `s3-wc-pos-mdata-bad` reject (ours accept); `s3-mdata-eager-hidden-ok` accept (ours reject) | T / T-reject | strict: declined by the loader (`LoadOptions.strictMetadata`, LP2, candidate jar `7e3f85cecdaa`). practical: transparent, as before. Exact: an MDATA node in the store (not planned) |
| D2 | `nondep` of `letE` not stored (R2-F1) | yes | `r2-id-let-nondep-bad`, `-def-bad` reject (ours accept) | T | strict: a declaration that contains such a let is declined by the loader (`Loader.exprLet` marks the record like metadata under `LoadOptions.strictMetadata`; `RunPacker` never packs such a record, so the flag always reaches the loader; `rlvm-load` b23a88e4c30b, `rlvm-export` dc8c4f1ef79a; patches `work/r2/patches/0001`, `0002`). Exact: a header bit of the LET node in its identity (LP2's design of 22:05Z; six modules; not started). practical: as before; the store is unchanged (differential digest, section 8) |
| D3 | object identity at (P2) (R2-F2) | yes | `r2-ptr-other-record-bad` reject (ours accept); twin accept | T | strict: probe, both-ways check, decline where the verdicts differ (this module). practical: as before |
| D4 | key of the `m_whnf_core` entry after an unfolding: `c a1..an` instead of `value a1..an`. Missed: an explicit redex `value a1..an` in the declaration, another constant with the same value, the same constant at other levels with the same instantiated value | not found: a miss recomputes cheaply and meets the entries of the same reduction chain (every projection frame of the full run wrote one), so the result is the cached one; only a K test that failed is run again | none | - | fixed in both modes: `valueOwner` (one representative reference per lambda value), `explicitLam` + look-up of the built term; the two tables are one logical table |
| D5 | a literal above 2^64 that a worker computes twice could get two pool indices (shared pool looked up first, with the limit "published now": pipelined load) | only through cache keys, and only by thread timing | none (not deterministic) | - | LP2: the worker pool looks in its own table first (in the store candidate `30979ee659b7`) |
| D6 | admission (**R2-F3**): the official `add_inductive` makes a fresh checker for EVERY judgment (`tc()`), except (i) the four judgments per constructor of `check_recursors`, (ii) the nested-application checks, (iii) the final re-check; RLVM's admission runs the judgments of a phase in one cache scope of this kernel | yes (both engines: admission is shared) | `r2-adm-param-success-bad` reject (ours ACCEPT: the parameter comparison of a constructor is answered by the success pair its type check left); twins `-ok`, `-plain-ok` accept | T | strict: every judgment asked through `KernelOps` in a `begin` scope starts with empty judgment caches (`RefKernel.freshJudgments`; this module) - exact outside the three groups, fresher than the official kernel inside them (the owner of `rlvm-inductive` would have to say where a group begins and ends). practical: as before |
| D7 | `whnfCoreCache.put` replaced an entry that a nested call had written for the same key | not found (the two values are the same unless the nested call saw another cache state) | none | - | fixed in both modes: write if absent |
| D8 | (P1) is not repeated on equal ids | no (section 3) | - | - | none needed |
| D9 | a reduction that returns to a state it was in is declined (the official kernel does not terminate) | no verdict exists officially | `corner-cases/eta-struct-major-*-no-whnf` | - | as before |

## 5. Lifetimes

- **A declaration**: `Session.check` -> `RefKernel.checkConstant` -> `begin` clears every table; after the
  verdict the tables are cleared again and the overlay and the literal pool are cut back to their marks. The
  official order is kept: type (check mode), `ensure_sort`, for a theorem `is_prop(type)` (infer-only mode), value,
  `is_def_eq(value type, type)`; type and value share the caches, with fresh free variables per binder opened
  (S3's `s3-wc-type-then-value-ok`).
- **Across declarations**: nothing (`ref.imports` is off; with `on` only canonical copies of stored terms, no
  judgment). S3's `s3-wc-proj-two-decls-ok` and the three `s3-wc-warm-*` exports get the official verdict.
- **Re-checks and fallbacks of the driver** are `Session.check` calls of another session: the same isolation. In
  strict mode a both-ways check (section 3) repeats `Session.check`'s body up to 63 times; each repetition starts
  from the cleared state.
- **The environment pass** (admission of inductive blocks through `KernelOps`): one scope per phase in the
  practical mode (D6 / R2-F3); in the strict mode every judgment starts with empty judgment caches (`infer` x2,
  `whnf_core`, `whnf`, success, failure), which is the official lifetime except inside the three groups of row D6.

## 6. Certificates (kind T)

For each export the engine accepts against the official verdict, the official checker accepts the same proof term
of the same statement elsewhere:

| Accepted against the official verdict | The official checker accepts |
|---|---|
| `s3-wc-pos-mdata-bad`: `use (a = mdata b) (Eq.refl a)` after `W0` | `s3-wc-pos-mdata-ok`: the same term without `W0` |
| `r2-id-let-nondep-bad`, `-def-bad`: `use (a = L2) (Eq.refl a)` after `W0` | `r2-id-let-nondep-ok`: the same term without `W0` |
| `r2-ptr-other-record-bad`, `-def-bad`: `needs c0` (i.e. `U = U'`) after the second pass | `r2-ptr-same-record-ok`: the same declaration with one record shared; `r2-ptr-alone-ok`, `-order-ok` |
| `r2-adm-param-success-bad`: the constructor parameter `Q2 5 b` against the type's `Q2 a a` | the same comparison inside the constructor's type check of the same file (`T p` with `p : Q2 5 b` is well typed for the official checker: the error is raised by the next judgment); `r2-adm-param-success-ok` (`Q2 a b`) |

`a = b`, `U = U'` hold by proof irrelevance (`h` against `And.intro ..`) and iota. Nothing false is accepted: every
accept path of the kernel is a derivation; the caches hold facts, and an entry that is "there too early" or "one
object too many" changes which true facts the incomplete procedure finds.

### 6.1 Metadata in the practical mode: transparent; a rule around `eagerReduce`; a safety net behind every reject

Under the practical policy a dropped `mdata` node may make RLVM accept more than the official checker (kind T,
tolerated), but it must not make RLVM REJECT what the official checker accepts (OWNER-NOTES 13 (c)).
`s3-mdata-eager-hidden-ok` did: the node stands around `eagerReduce (X = X) (Eq.refl X)`, the official
`is_eager_reduce` (`infer_app`: `is_const(get_app_fn(arg), eagerReduce) && get_app_num_args(arg) == 2`) looks AT the
node, not through it, so the official kernel checks the argument without eager reduction and accepts; with the node
dropped the kernel entered eager reduction, where the official kernel itself rejects the statement
(`s3-mdata-eager-plainform-bad`).

**Rule (`rlvm-load` 58b0056c0417, both policies).** A declaration is DECLINED if it contains a record around which
a metadata node stood and which is - or cannot be shown not to be - the constant `eagerReduce` applied to at most
two arguments (the node is then on the path from an argument `eagerReduce _ _` to its head: the argument itself, the
partial application, or the constant). `Loader.exprMData` looks at the wrapped record in the segment being read
(`mayBeEagerReduce`); a second bit per expression ordinal (`hidden`, a subset of the metadata bit, allocated at the
first such record) is propagated to every record that contains it, and `declare` declines the axiom / definition /
theorem / opaque that carries it. The rule can only fire in a file that has a metadata record AND names
`eagerReduce`; there the loader fails closed where it cannot read a record back (an application that belongs to an
earlier declaration or lies in a packed run counts as "may be"). Metadata inside the two arguments, around an
application with three arguments, or anywhere else stays transparent. Cost: one `len == 11` test per name record,
one compare per `const` record; files without metadata are loaded as before (store digest identical).

**Which other official tests on un-normalised terms does a dropped node fool, and in which direction?**

| Official test | With the node | With the node dropped | Can an official ACCEPT become our REJECT? |
|---|---|---|---|
| `is_eager_reduce(app_arg(e))` (infer_app, check mode) | not eager | eager | **yes, directly** (Nat folding with free variables decides `false` early): closed by the rule above |
| `cheap_beta_reduce(r)` on the inferred type of a lambda / let body (`is_app`, `get_app_fn`, `is_lambda`, the loop over the lambdas, `is_bvar` of the body) | the type stays `(mdata (fun x => C)) a` or `mdata ((fun x => C) a)`; a node between two lambdas or around the bound-variable body stops the reduction too | reduced to `C` | not by itself (the two types are convertible and every comparison strips the node first). **Through the caches, yes: built, finding R2-F4 below.** Officially this occurrence and an explicit occurrence of the same term are one key of `m_success`; here one is `C` and the other the redex, so a pair recorded for the first does not answer the second, which is then recomputed in a later cache state |
| `is_pi(f_type)` / `ensure_pi_core` (infer_app, both modes), `ensure_sort_core`, the binder loops of `infer_lambda` / `infer_pi` / `infer_let`, `get_app_args` of infer-only `infer_app` | `whnf` strips the node, or the telescope is split in two | the direct path | no: the same types and the same judgments; only the place of an instantiation and function-memo entries differ |
| `quick_is_def_eq`: `t == s`, the success set, the kind switch (`MData` against `MData` compares the bodies; mixed kinds fall through to `whnf_core`, which strips) | another key | the key of the bare term | no: more hits, i.e. accepts more (S3-F2, kind T); the mixed-kind path reaches the same binder / sort / literal comparison one step later |
| the `Bool.true` shortcut (`is_constant(s, Bool.true)`) | no shortcut for `mdata Bool.true`; lazy delta reaches the same constant | shortcut | no: accepts as much, earlier |
| `is_def_eq_binding`: `binding_domain(t) != binding_domain(s)`, the telescope loop | the domains are compared, the telescope stops at the node | equal ids, one telescope | no: fewer comparisons here, the same answer |
| everything after a `whnf_core` / `whnf` (`is_delta`, `is_def_eq_offset`, `reduce_nat`, `is_def_eq_args` / `_app`, eta, structure eta, `infer_proj`, the recursor and quotient rules) | the head and the spine have been stripped: `whnf_core` rebuilds an application whose head changed | - | no difference |
| `add_inductive` (the syntactic tests on inductive and constructor types) | - | - | metadata in an inductive or quot record is declined in both modes (LP2) |

So one more official test (`cheap_beta_reduce`) can turn an official accept into a reject, together with a
cache-history construction; everything else can only make the practical mode accept more.

**R2-F4: `cheap_beta_reduce` behind a node, built** (2026-11-25; generator `work/r2/gen/batch4.py`, rows
`work/r2/exports/batch4.tsv`, in the hostile manifest `work/r2/exports/manifest.tsv` with the mark `mdata`). The
world is S3's probe: `A1 = (F intro).1` (a full `whnf_core` gives `Nat`), `B1 = (F h).1` (stuck), and `A1 = B1`
holds only while `m_whnf_core` has no entry for `A1`. `v : T1` with `T1 = (mdata (fun x => A1)) 0`. Officially
`infer_lambda` leaves the type of the body of `fun y => v` alone (`get_app_fn` stops at the node), so its
comparison with `True -> B1` records the pair `(T1, B1)` in `m_success`; after `A1` was cached by a full
`whnf_core`, the explicit `T1` against `B1` is answered by that pair. With the node dropped the kernel reduces the
body type to `A1`, records `(A1, B1)`, and later meets the redex `(fun x => A1) 0` against `B1` as a new comparison
in a cache that answers `Nat` for `A1`: false.

| Export | Where the node is | official | practical before | practical now | strict |
|---|---|---|---|---|---|
| `r2-mdata-beta-hidden-ok`, `-hidden-def-ok` | on the head path of a redex written in the export | accept | **REJECT** | decline | decline |
| `r2-mdata-beta-arg-hidden-ok` | around a lambda in ARGUMENT position: `g : (m : Nat -> Type) -> m 0`, term `g (mdata (fun x => A1))`; the redex exists only after the kernel instantiated the codomain | accept | **REJECT** | decline | decline |
| `r2-mdata-beta-bvar-hidden-ok` | around a BOUND VARIABLE in function position: `g : (m : Nat -> Type) -> (mdata m) 0`, term `g (fun x => A1)` | accept | **REJECT** | decline | decline |
| `r2-mdata-beta-nofill-ok` | as the first, without the cache fill | accept | accept | accept | decline |
| `r2-mdata-beta-plainform-bad`, `-arg-plainform-bad` | the twins without a node | reject | reject | reject | reject |

("practical" = the default configuration with the reference engine as default engine; the machine c2e04f2294ef
accepts the four `-ok` files as primary engine.)

*Census.* No file of real code has a metadata record: 0 in the eight corpora, 0 in the 270 arena tests
(`work/exports/all`), 0 in `work/arena-site/tests` (230). Whatever is done about metadata declines nothing real.

*Why no position rule.* The rule that covers the mechanism is "a node around a term whose head is a lambda or a
bound variable" (the loop of `cheap_beta_reduce` also stops at a node between two lambdas and at a node around
the bound-variable body; instantiation moves a wrapped lambda, or a lambda under a wrapped variable, into function
position). Read back exactly from the files (`work/r2/census/mdata_heads.py`; a loader that fails closed can only
decline more) it would turn **234 of the 779 agreements** of the metadata mutant corpus into declines (123 of the
421 accepted mutants, 111 of the 358 rejected ones). The narrow rule ("a node on the head path of a redex
written in the export") costs 2 agreements and leaves the `-arg-` and `-bvar-` files rejected. And position and
wrapped kind cannot tell those two files from agreeing mutants (`bugs/open-term-cache-context` `wrap_mdata.s1.0000`
wraps a lambda argument, `.s1.0006` a bound variable in function position): what makes the files hostile is the
cache history, which a loader does not see. No rule was published; the loader stays 58b0056c0417.

**The safety net** (`rlvm-main` @@MAINJAR@@, `RlvmChecker.strictRecheck`; decision of the orchestrator, 01:38Z). In
the practical mode a REJECT on a file in which the loader dropped at least one metadata node
(`LoadReport.exprMData` > 0 when the run ends) is not reported: the file is checked once more as a plain run
under `-X policy=strict` (fresh stores, the reference engine, `ref.exact=official`, the loader's metadata decline;
the command line without `engine`, `passes` and the exactness-only settings), and that verdict is the run's. An
ACCEPT or a DECLINE of the practical run and every file without a metadata record are untouched. A wrong reject
on such a file now needs a wrong reject of the strict mode, which never normalises a node away. The diagnostics
say `.. NOT REPORTED ..` and quote the practical explanation; the `--stats` line of the reported run ends with
`net=strict-recheck(practical=reject,metadata=N,practical_wall_ms=T)`. `-X passes=N` has the net too (one strict
run after the passes). The counter counts records READ: a node in an inductive record (declined by both
policies) or in a record no declaration uses also triggers the second run, which then ends as the first did.

The three counts (published jars, reference engine, one JVM per set: `work/r2/net/`, `lists.py`, `run.sh`,
`tables.py`):

- **(a) Practical REJECTs on files with a metadata record, and what strict says.** The 92 bad arena tests: none
  has a metadata record (nor has any of the 270). The four mutant corpora (17,775 files, 2,802 with a metadata
  record): 485 practical REJECTs on such files, every one a correct reject (official reject) - tutorial 71, bugs
  56, mdata 358, prelude 0. Strict: **351 become declines** (tutorial 67, bugs 37, mdata 247), 134 stay rejects
  (the rejected declaration stands before the first declaration with a node). The hostile exports (1,245): 4
  practical REJECTs on files with a metadata record, the four `-ok` files above, all official ACCEPT: strict
  declines the four. No accepted file changes: the 657 accepted files with a metadata record (655 mutants, 2 hostile exports) are
  never run twice.
- **(b) Caught by the net alone?** Yes, all of them: `r2-mdata-beta-hidden-ok`, `-hidden-def-ok`,
  `-arg-hidden-ok`, `-bvar-hidden-ok` (practical reject, strict decline), and `s3-mdata-eager-hidden-ok` with the
  loader before the rule of this section (b23a88e4c30b: practical reject, strict decline; `judge-cand3-*`).
- **(c) Cost.** @@COST@@
- **A wider trigger** (not implemented). The `nondep` flag of a let, the loader's only other practical-only
  normalisation: 7 files of the sets above have such a let (2 tutorial mutants, 1 prelude mutant, 4 hostile
  exports); the practical mode accepts all seven, so the wider trigger would change nothing there. "Every
  practical REJECT is checked again under strict" (which would also cover the kernel's practical-only
  behaviours): no further decline on these sets - strict rejects all 809 rejected hostile exports and all 482
  rejected prelude mutants without a metadata record, its additional declines on the tutorial and bugs corpora
  (260 + 48, section 8) are exactly the files with a node or such a let, and it declines none of the 230 tests -
  at the price of a second check of every rejected file.

*Judgement.* The net is worth having, and it is the closure of the class; the position rule for `eagerReduce`
is now redundant for contract (c) and only turns one decline-after-two-runs into a decline at load. The net
costs nothing on any accepted file or any real input, and its price - 351 correct rejects of metadata mutants
reported as declines - is paid only on files that no exporter writes without `--export-mdata`.

## 7. What the strict mode costs on real input

**No declaration of the eight corpora is declined in the strict mode, and every corpus is accepted in both
modes** (JVM launcher of `work/r2/dist-cand3`, `-X engine=reference`, one worker; practical = no option, strict =
`-X policy=strict`, i.e. the kernel's `ref.exact=official` and the loader's `strictMetadata`; rows
`work/r2/data/corpora.tsv`, labels `final-practical` / `final-strict`; mathlib with the launcher's 13 GB heap):

| Corpus | practical | strict | identity events (first check of a declaration) | cleared by the probe | hazards decided both ways (declarations) | declined |
|---|---|---|--:|--:|--:|--:|
| Init | accept | accept | 0 | 0 | 0 | 0 |
| Std | accept | accept | 5 | 4 | 1 (1) | 0 |
| cedar | accept | accept | 6 | 6 | 0 | 0 |
| reachability | accept | accept | 0 | 0 | 0 | 0 |
| navier-stokes-euler | accept | accept | 50 | 50 | 0 | 0 |
| cslib | accept | accept | 123 | 115 | 8 (8) | 0 |
| con-leche | accept | accept | 1 | 1 | 0 | 0 |
| mathlib (714,712 declarations) | accept | accept | 263 | 252 | 11 (11) | 0 |

- **Object identity**: the event is in real exports (448 comparisons in the eight corpora), so declining at every
  event would not do (a first version of the rule declined Std, cedar, navier-stokes-euler, cslib and con-leche).
  The probe shows for 428 of them that the official second pass repeats nothing; the other 20 hazards, in 20
  declarations, are decided by checking the declaration both ways, and both ways give the same verdict every time.
  (Events are counted in the practical run, hazards in the strict run; a strict run counts the events of a
  declaration again each time it checks it.)
  The one hazard of Std: `Entrypoint.aig (mkFullAdderOut ..)).0` against `(FullAdderOutput.aig (mkFullAdder ..)).0`
  - the same projection of two structures; the second pass would run `lazy_delta_proj_reduction` again and write
  a new `m_whnf_core` entry, i.e. the official kernel's state after this comparison does depend on whether the
  two occurrences are one object; its verdict on the declaration does not.
- **`mdata`**: 0 records in the eight corpora and in the arena tests. **`nondep = true`**: 0 of 550,621 `letE`
  records (grep over `work/arena-run/_build/tests`). Both declines of the strict loader cost nothing there.
- **Admission with fresh judgment caches**: all inductive blocks of the eight corpora are admitted as before.
- **CPU** (quiet window, one worker): `-X ref.exact=official` and `-X policy=strict` are within the noise of the
  practical mode (section 8: Init 14.7 s, Std 22.0 - 23.3 s in every configuration). On the loaded host the
  mathlib run took 582 s (practical) and 498 s (strict) of CPU with one worker.
- **On adversarial files** the strict mode declines where the practical mode accepts or rejects against the
  official verdict: 14 of the 1,239 review exports (section 8), among them the exports of this audit whose
  official verdict really depends on object identity.

## 8. Gates

The published jars: `rlvm-ref` a20c24a26b7c, `rlvm-load` b23a88e4c30b, `rlvm-export` dc8c4f1ef79a (`rlvm-store`
30979ee659b7 as LP2 published it: unchanged). Everything below ran on the private distribution
`work/r2/dist-cand3` = exactly these jars + the other modules as published at 23:08Z (`rlvm-main` 21150203e39d,
`rlvm-cli` cac1433b287a, `rlvm-machine` 97d17e57e51b, ..), through the JVM launcher, `-X engine=reference`.

- **Module suites**: `rlvm-ref` 148 tests (147 run green, 1 opt-in), `rlvm-load` 80 green, `rlvm-export` 76
  (75 run green, 1 opt-in). New: `corpus.ExactnessGoldenTest` (30 exports with official verdicts, both modes),
  `UnfoldKeyTest`, `StrictScopeTest`; `SplitTest.aDeclarationWithANondepLetIsDeclinedInStrictMode`; `PackedRunsTest`
  (every `nondep = true` let reaches the sink as a single record).
- **Differential store digest** (`work/lp2/digest` tooling, run from `work/r2/digest`; the loader / reader change
  must not alter the store): IDENTICAL to the reference digests in all 14 reader configurations (17,158 small
  files, 847 prelude mutants, Init / Std / Cedar slice / reachability, cedar, con-leche, navier-stokes-euler,
  cslib, mathlib; one thread, and scanner threads with packed runs: the one prelude mutant with a
  `nondep = true` let has one record fewer in a run, same store). With `strictMetadata` on, against LP2's strict
  digests: the 1,084 + 33 files declined for metadata are declined as before, 2 + 1 files are newly declined -
  exactly the mutants with a `nondep = true` let - and nothing else differs, with one thread and with scanner
  threads.
- **`scripts/gate.sh --dist work/r2/dist-cand3 --corpus all`** (practical, the default): the 230 published tests
  230 correct, 0 wrong, 0 declined; mutants 17,775: SOUNDNESS 0, COMPLETENESS 0, agree 16,092, declined 1,683,
  errors 0 (tutorial 11,847 / 413, bugs 2,627 / 65, prelude 839 / 8, mdata 779 / 1,197: the published record);
  differential self-test passed. The same gate had passed its 230-test step on the two earlier candidates of the
  reference jar.
- **The same gate with `-X policy=strict`** (`--corpus tutorial --corpus bugs`): PASS. The 230 published tests 230
  correct, 0 wrong, 0 declined; mutants tutorial 12,260: SOUNDNESS 0, COMPLETENESS 0, agree 11,587, declined 673;
  bugs / other / corner-cases 2,692: SOUNDNESS 0, COMPLETENESS 0, agree 2,579, declined 113; self-test passed. The
  strict mode declines 260 + 48 mutants more than the practical one and agrees on all the others; the additional
  declines were not classified one by one (LP2's strict digest of the same files shows the loader's part: 1,084
  small files with a metadata node in a declaration, 2 with a `nondep = true` let).
- **Review exports** (one JVM, real pipeline; `work/r2/bin/judge-all.sh`, results `work/r2/out/judge-cand3-*`),
  1,239 exports with official verdicts:

  | Set | exports | practical: official verdict / declined / opposite | strict: official verdict / declined / opposite |
  |---|--:|---|---|
  | R2 (`work/r2/exports`) | 25 | 20 / 0 / 5 (the kind T rows: nondep x2, identity x2, admission) | 17 / 8 / **0** (nondep 4, identity 4) |
  | S3 (`work/s3/exports`, 23:00Z) | 423 | 421 / 0 / 2 (`s3-wc-pos-mdata-bad` kind T, `s3-mdata-eager-hidden-ok` T-reject) | 420 / 3 / **0** (mdata) |
  | E1 (`work/e1/exports`) | 92 | 92 / 0 / 0 | 92 / 0 / **0** |
  | S2 (`work/s2/attacks*`; 10 more have no stored verdict) | 269 | 267 / 2 / 0 | 267 / 2 / **0** |
  | first review (`rlvm-machine/src/test/resources/s`) | 419 | 418 / 1 / 0 | 418 / 1 / **0** |

  The declines outside the R2 and mdata rows are older ones (metadata inside an inductive record, twice; a String
  literal without its prerequisites). Before this work the published engine gave 4 opposite verdicts on the R2 set
  as it stood then and 2 on S3's; in strict mode there is none now.
- **The loader with the rule of section 6.1** (`rlvm-load` 58b0056c0417, published 01:2xZ on 2026-11-25; private
  distribution `work/r2/dist-cand4` = the jars above with this loader): suite `rlvm-load` 81 green (new:
  `SplitTest.metadataAroundAnApplicationOfEagerReduceIsDeclinedUnderBothPolicies`, ten variants); store digest
  IDENTICAL in all 14 reader configurations (the rule fires on none of those files) and the strict digests
  identical to the previous loader's; `scripts/gate.sh --corpus all`: PASS, 230 / 230, 0 declined, mutants 17,775
  with exactly the counts above - in particular the metadata mutant corpus keeps agree 779 / declined 1,197: no
  agreement becomes a decline; the 1,239 review exports: one verdict changes, `s3-mdata-eager-hidden-ok` reject
  -> DECLINE (practical; strict: declined as before), so the practical mode has 6 opposite verdicts left, all
  "official reject, accepted" (kind T), and none of the kind "official accept, rejected"; Init, Std and
  reachability accepted through this loader (the digest shows the same store for all eight corpora).
- **Corpora**: section 7.
- **Cost** (`bench/quiet.py` window, one worker, five alternating runs, CPU = user + sys, median [min, max];
  "before" = the same distribution with the published reference jar ca1f9bd918bb):

  | Corpus | before | now (practical) | now, `-X ref.exact=official` |
  |---|---|---|---|
  | Init | 14.37 s [14.16, 14.68] (wall 10.43) | 14.70 s [14.28, 14.87] (wall 10.83) | 14.71 s [14.58, 14.91] (wall 10.79) |
  | Std | 22.61 s [21.28, 23.37] (wall 18.00) | 21.99 s [21.63, 23.16] (wall 17.60) | 22.48 s [21.84, 23.43] (wall 17.82) |

  The ranges overlap in every pair: +2 % on Init, -3 % on Std, i.e. nothing that five runs resolve. Peak RSS
  unchanged (723 / 819 MB).

## 9. Not established, not done

- **`nondep` is declined in the strict mode, not represented.** The exact solution (the flag as part of a LET
  node's identity: LP2's design of 22:05Z - a header bit that `Overlay.mkLet` hashes, a tag bit of the block
  record, a second letE tag of the packed runs, every rebuild of a LET in `TermOps`, the machine, the admission)
  is not started: it changes the store format and six modules for a flag that no letE record of the corpora or
  tests has, and the decline costs nothing there.
- **`mdata` and `nondep` are declined, not represented**: a strict run of a file with them gives no verdict. An
  export of real code written with `--export-mdata` is such a file.
- **Admission in the strict mode is fresher than the official kernel in three places** (D6): the four judgments
  per constructor of `check_recursors`, the nested-application checks and the final re-check share one official
  checker; the strict kernel gives each judgment empty caches. Whether RLVM's admission asks those judgments at all
  in the official order is the admission's matter (finding L-F1 of today is in the same code). Other divergences of
  the admission from `add_inductive` were not audited here.
- **The second pass inside admission**: an object-identity hazard in a judgment asked through `KernelOps` is
  declined at once in the strict mode (no session to check both ways). Not seen on the corpora.
- **`sharecommon`** (`add_theorem` shares the value and the type before checking) was read in the source, not
  shown by an export: every construction of a hazard I have needs a term that lazy delta produces from ANOTHER
  declaration's value, which `sharecommon` never touches.
- **No export for D4, D5, D7.** For D4 and D7 I could not construct a verdict that depends on them (the argument
  is in section 4); they are corrected because the official tables are what they are. D5 is a matter of thread
  timing.
- **The both-ways check assumes that the two continuations at a hazard are the only official behaviours** and
  that everything else in the kernel is the official procedure. The evidence for the first is section 3's table
  (each continuation reproduces the official verdict exactly where it is the truth); for the second, the gates.
  A hazard is decided by at most 63 checks of the declaration; more would be a decline (not seen).
- **The safety net of section 6.1 covers metadata only.** A wrong reject of the practical mode on a file WITHOUT
  a metadata record is outside it (none is known: the hostile exports have no "official accept, rejected" row);
  the `nondep` flag does not trigger it (no reject on such a file exists in the sets). A correct reject of a file
  with a node becomes a decline wherever the strict load declines a declaration before the rejected one (351 of
  485 such mutants). The net's second run uses the heap of the first (the JVM launcher sizes it by the default
  engine).
- The rule of section 6.1 fails closed in a file that names `eagerReduce`: there a metadata node around ANY record
  of an earlier declaration (or of a packed run) declines the declaration, because the loader cannot read such a
  record back.
- Mathlib: see section 7 for what was run.
