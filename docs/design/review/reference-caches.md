# The caches of the reference engine against the official kernel's (R2)

2026-11-24, 21:28Z - @@END@@. Owner of `rlvm-ref` after L. Reason for this audit: finding S3-F2 (reviewer S3,
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
   Same cause, other direction: `s3-mdata-eager-hidden-ok` (official accept) was a wrong REJECT.
2. **R2-F1** (new, same class): the `nondep` flag of `letE` is part of the official term identity and the store
   does not keep it. `r2-id-let-nondep-bad`: official reject, reference accept.
3. **R2-F2** (new): **the official verdict can depend on object identity** - on whether a subterm is one record of
   the export or two - through the pointer test `is_eqp(t_n_n, t_n)` after the second `whnf_core` of
   `is_def_eq_core`. `r2-ptr-other-record-bad` (official reject) and `r2-ptr-same-record-ok` (official accept) are
   THE SAME TERMS. No store that identifies equal terms can reproduce this; the published reference engine accepts
   both.
4. Everything else that decides what "the same term" is agrees with the official kernel (binder names and binder
   infos ignored, two records with one content one term, levels by spelling, literals by value), and every cache is
   read, written and cleared where the official one is, with two corrections made here that change no verdict I
   could construct (the key of the entry that follows an unfolding; `insert` never replaces).
5. All three findings are **kind T** (OWNER-NOTES item 13): the statement the engine accepted is derivable and the
   official checker itself accepts the same proof term in another cache state or with another sharing of records
   (certificates: the twins `-ok` of each export, section 6).
6. **Policy** (OWNER-NOTES item 13, "practical"): the reference engine now has two modes.
   `-X ref.exact=off` (the default; what the practical policy uses for re-checks and fallbacks): never declines
   for the sake of agreement on adversarial files; behaves as before on the three findings.
   `-X ref.exact=official` (the strict mode; `-X policy=strict` sets it): the official verdict or none. Object
   identity is handled exactly where it can be (a probe, then a check of the declaration under both possibilities)
   and declined only where the verdict really depends on it; `mdata` and `nondep = true` are declined by the
   loader's strict switch (LP2: `LoadOptions.strictMetadata`; `nondep` needs patch
   `work/r2/patches/0001-strict-nondep-decline.patch`).
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
port has the same reads and writes; S3's 105 cache-order exports of 21:20Z give the official verdict except the two
with `mdata`.

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
| D2 | `nondep` of `letE` not stored (R2-F1) | yes | `r2-id-let-nondep-bad`, `-def-bad` reject (ours accept) | T | strict: declined by the loader with patch `work/r2/patches/0001-strict-nondep-decline.patch` (tested on `work/r2/dist-nd`; with LP2). Exact: a header bit of the LET node in its identity (LP2's design of 22:05Z; six modules; not started). practical: as before |
| D3 | object identity at (P2) (R2-F2) | yes | `r2-ptr-other-record-bad` reject (ours accept); twin accept | T | strict: probe, both-ways check, decline where the verdicts differ (this module). practical: as before |
| D4 | key of the `m_whnf_core` entry after an unfolding: `c a1..an` instead of `value a1..an`. Missed: an explicit redex `value a1..an` in the declaration, another constant with the same value, the same constant at other levels with the same instantiated value | not found: a miss recomputes cheaply and meets the entries of the same reduction chain (every projection frame of the full run wrote one), so the result is the cached one; only a K test that failed is run again | none | - | fixed in both modes: `valueOwner` (one representative reference per lambda value), `explicitLam` + look-up of the built term; the two tables are one logical table |
| D5 | a literal above 2^64 that a worker computes twice could get two pool indices (shared pool looked up first, with the limit "published now": pipelined load) | only through cache keys, and only by thread timing | none (not deterministic) | - | LP2: the worker pool looks in its own table first (in the store candidate `30979ee659b7`) |
| D6 | admission: the official `add_inductive` makes a fresh checker for EVERY judgment (`tc()`), except (i) the four judgments per constructor of `check_recursors`, (ii) the nested-application checks, (iii) the final re-check; RLVM's admission runs the judgments of a phase in one cache scope of this kernel | in principle (a full `whnf_core` of one judgment changes a cheap one of the next: wrong reject; the other direction not excluded) | not constructed (the recursor type is checked by one official checker, which blocks the obvious construction) | - | not this module: the owner of `rlvm-inductive` / `rlvm-admit` decides where a scope ends (`KernelOps.begin`, or `exposeUnitMembers(0)`, clears the judgment caches) |
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
- **The environment pass** (admission of inductive blocks through `KernelOps`): one scope per phase (D6).

## 6. Certificates (kind T)

For each export the engine accepts against the official verdict, the official checker accepts the same proof term
of the same statement elsewhere:

| Accepted against the official verdict | The official checker accepts |
|---|---|
| `s3-wc-pos-mdata-bad`: `use (a = mdata b) (Eq.refl a)` after `W0` | `s3-wc-pos-mdata-ok`: the same term without `W0` |
| `r2-id-let-nondep-bad`, `-def-bad`: `use (a = L2) (Eq.refl a)` after `W0` | `r2-id-let-nondep-ok`: the same term without `W0` |
| `r2-ptr-other-record-bad`, `-def-bad`: `needs c0` (i.e. `U = U'`) after the second pass | `r2-ptr-same-record-ok`: the same declaration with one record shared; `r2-ptr-alone-ok`, `-order-ok` |

`a = b`, `U = U'` hold by proof irrelevance (`h` against `And.intro ..`) and iota. Nothing false is accepted: every
accept path of the kernel is a derivation; the caches hold facts, and an entry that is "there too early" or "one
object too many" changes which true facts the incomplete procedure finds.

The one wrong REJECT of the practical mode: `s3-mdata-eager-hidden-ok` (the loader drops the `mdata` around
`eagerReduce`, the kernel enters eager reduction, where the official kernel itself rejects the statement:
`s3-mdata-eager-plainform-bad`). Only a file with `mdata` records can do this; lean4export writes them only with
`--export-mdata`, and none of the corpora or tests has one.

## 7. What the strict mode costs on real input

@@COST@@

## 8. Gates

@@GATES@@

## 9. Not established

@@OPEN@@
