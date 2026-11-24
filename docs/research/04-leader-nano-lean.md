# 04 — Leader deep-dive: nano-lean v0.4.2 (arena #1 on mathlib)

Subject: https://github.com/sdiehl/nano-lean, arena pin `v0.4.2` = `5cd4a25de7cdd8a5fadde3fec53e41a6a8693138`
(arena config: `lean-kernel-arena/checkers/nano-lean.yaml`; build `scripts/pgo.sh`; run
`target/release/nl-fast -q -j 4 "$IN"`; `threads: 4`).

Sources read (paths relative to `refs/`):

- `nano-lean-v0.4.2/` — plain `git archive` extract of the pinned revision. **All `file:line` citations below
  refer to this tree.**
- `nano-lean/` — the git clone (154 commits, complete history), currently at `v0.4.3` (`556670f`), used only for
  history (`git log`, `git show <tag>:README.md`). v0.4.3 is NOT what the arena runs.

Citation convention: paths are relative to `nano-lean-v0.4.2/src/`. A bare file name out of
`mod.rs, value.rs, intern.rs, tables.rs, eval.rs, whnf.rs, derive.rs, conv.rs, infer.rs, decl.rs, session.rs`
means `value_checker/<file>`; `fast.rs`, `reorder.rs`, `resource.rs`, `bin/nl-fast.rs` are at `src/` level;
everything else is written with its directory (`term/…`, `import/…`, `checker/…`, `kernel/…`).

Nothing was built or executed. Statements marked **[inference]** or **[unverified]** are mine, not the source's.
Arena figure used throughout: 65.8 s virtual time = 65.8 x 6e9 ≈ **395 G user instructions** for all of mathlib,
including NDJSON parsing, with 4 workers, peak 5.0 GB.

---

## 0. Executive summary

1. `nl-fast` is **not** the "hash-consing + de Bruijn substitution" checker its arena blurb describes. The
   default engine ("value core", `src/value_checker/`, ~5 kLoC) is a **first-order-closure NbE** over the
   imported de Bruijn DAG: values are `Sort | Pi(dom, clo) | Lam(lazyDom, clo) | Neu(head, spine) | Nat | Str`;
   conversion and inference work directly on values; **there is no quote/readback anywhere**. The older
   substitution engine ("term core", `src/checker/`) is only used for inductive blocks and quotient declarations.
2. Its speed comes from **making pointer identity mean semantic identity and then memoising everything on
   identity**: closures are trimmed to their free variables, environments, neutrals, lambdas and literals are
   hash-consed, and `eval`, `infer`, `whnf`, `unfold`, `type_of`, `def_eq`, Pi-instantiation all have caches
   keyed by pointers.
3. Those caches **survive across declarations** inside a per-worker "session" (dropped only when the session
   arena passes 64 MiB or a declaration fails), and declarations are **re-ordered at load time so each one
   follows soon after its dependencies**, which keeps the caches warm. Closed sub-terms of the export are
   evaluated / type-checked once per session, not once per use.
4. Delta is never performed by `eval`: constants stay folded as `Neu(Const c us, args)` and the unfolded/whnf
   form is memoised on the node (a lazily "glued" value). Lazy delta in `def_eq` uses **budgeted same-head
   argument probes** (16384 / 1024 steps) with a failure cache instead of Lean's unbounded argument comparison.
5. Hot definitions (unfolded >= 32 times) get a **derived reduction rule**: a constructor decision tree with
   pre-reduced leaf templates, so `f (C x̄)` jumps over the matcher/casesOn/rec chain in one substitution.
6. Import is a hand-written schema-specific scanner (SWAR digits, word compares, tight loop for `app` lines),
   with **no hash-consing of expressions at import** (it trusts the exporter's sharing).
7. Build: fat LTO, 1 codegen unit, `panic=abort`, `target-cpu=native`, 64-byte function alignment, mimalloc,
   and **PGO trained on an Init export with the exact arena command line**.
8. Inductive types are validated natively (positivity, universes, nested expansion); recursors are regenerated
   and compared structurally, falling back to a full type-check + def-eq when not identical. `Nat`/`String`
   primitive *bodies* are trusted (as in Lean's kernel); only their types are pattern-checked. No
   `reduceBool`/native evaluation exists.
9. Parallelism costs instructions: the project's own (old, v0.1.0) table shows +5.4 % instructions on mathlib
   going from 1 to 10 threads. Workers share only the immutable store; all caches are per worker.

---

## 1. Ranked tricks and their JVM/Truffle realisation

Ranking is by my reading of the code plus the changelog ordering; the repo publishes almost no per-trick
numbers (see §14 for every number that exists). "Evidence" cites what is actually written down.

### T1. Session-persistent identity-keyed memo tables + dependency-ordered scheduling
- What: `eval_closed[(expr, usub)]`, `infer_closed[(expr, usub)]`, `delta[(const, levels)]`,
  `const_ty`, recursor-rule values, `eq_cache`, `neq_cache`, hash-cons tables and the per-value memo
  (whnf / whnf_core / unfold / type) all outlive a declaration (`value_checker/tables.rs:141-187`,
  `session.rs:65-105`). `reorder.rs` renumbers declarations so that neighbours share constants.
- Evidence: CHANGELOG 0.3.0 lists "Memoize evaluation", "Memoize whnf and types on values", "Keep bounded tables
  across declarations" under "Cut Mathlib checking cost substantially"; 0.3.3 "Check large exports near each
  declaration's dependencies, keeping tables warm" and "`--session-mib` to keep worker tables across more
  declarations". No isolated number.
- JVM: expression ids are dense ints, so the closed-term memos can be **direct arrays indexed by expr id**
  (for the identity universe substitution) instead of hash tables: one bounds-checked load per probe. Use a
  generation stamp (`int[] stamp` parallel array, or stamp packed into the slot) for O(1) session reset.
  Non-identity universe substitutions go to a small open-addressing table keyed `(exprId, subId)`.
  Because the arena metric is instructions and not time, cache-missy but instruction-cheap big tables are
  nearly free for us — except that **GC tracing of reference tables is charged**, and a memo table by nature
  points from long-lived storage to freshly allocated values. Two realistic options: (a) values as `int`
  handles into `int[]`/`long[]` slabs with bump allocation and bulk reset (a literal port of nano-lean's
  arena; the GC never traces values or tables), or (b) Java objects with a young generation big enough to
  hold a whole session, and the session reset placed so that almost nothing is live when a young collection
  runs. This is the single most important design decision nano-lean's architecture forces on RLVM; it
  should be decided by measurement. **[inference]**
- Recommendation **[inference]**: check on one thread unless the wall-clock timeout forces otherwise; all
  caches are then shared by every declaration and none of the duplicated work of §15 is paid.

### T2. Canonical closures: free-variable trimming + hash-consed environments/values
- What: every `Expr` node carries `sup`, a 32-bit mask of the loose bvars < 32 it mentions (`term/expr.rs:184-221`),
  and `nlb` (max loose bvar + 1) in the pointer tag. Evaluating a `Lam`/`Pi` first **trims** the environment to
  the slots the binder reads (`eval.rs:457-523`), producing an interned flat frame; unused slots hold one
  canonical dummy value, so de Bruijn indices in the body need no renumbering. `push(env, v)` is interned by
  `(v ptr, env ptr)`. Hence two closures over the same body whose environments agree on the used variables are
  *the same pointer*; `def_eq` on them is a pointer compare, and `(expr, env)` memo keys hit.
- Evidence: commit "exact support masks and thin env" (b542977); CHANGELOG 0.3.1 "Index environment frames
  directly from node runs", "Collapse deep environment runs into frames" (near-linear beta ladders);
  0.3.2 "Keep long sparse environments untrimmed, so shift cascades check about 40% cheaper".
- JVM: do the free-variable analysis once per binder node at load (store a 32/64-bit mask per node in the
  node table). A closure's captured environment is a small `Object[]` (or slab frame) of size `maxUsed+1`.
  Flat frames avoid pointer chasing; interning them needs a precomputed hash field on every value
  (never `System.identityHashCode`). Keep linked "push" nodes only for short runs (nano-lean collapses at 64).

### T3. Folded neutrals with memoised whnf/unfold/type on the node ("lazy glued evaluation")
- What: `eval` never unfolds a constant; `whnf_core`, `whnf`, `unfold`, `type_of` results are cached in a
  `Memo` hanging off the value (`value.rs:7-42`, `mod.rs:144-163`). Comparison first tries the folded forms.
- Evidence: commit "memoize whnf and types on values" (724f2ea); README: "every value remembers its own normal form".
- JVM: plain fields on the `Neutral` object (`whnfCore`, `whnf`, `unfolded`, `type`), no separate memo object,
  no epoch needed if values die with the session. Cheapest trick in the list to port.

### T4. Not computing things
- (a) Non-dependent codomain: argument is **not evaluated** during inference; a dummy is pushed
  (`infer.rs:295-300`). (b) Lambda domains are lazy thunks (`Lazy`, `eval.rs:212-227`, `321-339`).
  (c) The type of a lambda is a Pi whose codomain is a *typed closure*: body type computed on demand by
  infer-only (`infer.rs:70-73`, `429-448`, `eval.rs:563-571`). (d) A lambda binder whose domain node is the
  same pointer as the declared type's Pi domain is not re-checked (`decl.rs:94-128`). (e) `(fun x => b) a`
  (a `have`) is typed by checking `b` once under a local and substituting into the *type* (`infer.rs:156-185`).
  (f) Infer-only mode for types of already-checked terms, in separate cache tables (`tables.rs:60-84`).
  (g) Proof-irrelevance test skipped when a term is syntactically not a proof (`conv.rs:312-345`).
  (h) Same-head argument comparison skips arguments the definition body does not use and arguments whose
  declared type is a `Prop` (`conv.rs:389-431`).
- JVM: all are algorithmic; (a)-(c) are natural Truffle node specialisations ("non-dependent Pi" vs
  "dependent Pi" node, decided once per node from the `sup` bit 0 of the codomain).

### T5. Lazy delta with budgets and failure caches (§9)
- Evidence: CHANGELOG 0.3.2 "Give argument probes under cheap heads a smaller budget, so lazy projections check
  about 35% cheaper"; 0.4.2 "Meet earlier unfoldings in lazy delta, fixing `loop-unfolded-once`"; roadmap items
  "Bound speculative congruence attempts and fall back after exhaustion", "Avoid repeated speculative
  comparisons under rigid heads".
- JVM: port as is. Budget accounting is one decrement per cache miss.

### T6. Derived reduction rules for hot definitions (§8)
- Evidence: first item of the 0.3.0 "cut Mathlib checking cost substantially" list; CHANGELOG 0.3.2 "Intern Pi
  values built by rule substitution, so deep `List` indexing runs about 12x cheaper" (a micro case).
- JVM: this is where Truffle fits best: a per-constant call node with an inline cache on the scrutinee's
  constructor, leaf = pre-reduced template. The portable version (value-level symbolic execution + template
  substitution) is ~300 lines. Must keep a debug oracle (`derivecheck` feature asserts rule result ≡ plain
  unfolding).

### T7. Universe substitution carried by closures (`Sub{ks,vs}`), never applied to terms
- What: closures and memo keys contain the pair (param list ptr, level-args ptr); levels are substituted only
  at `Sort`/`Const` leaves, with interning; the identity substitution has one normal form
  (`value.rs:78-90`, `mod.rs:265-271`, `eval.rs:182-189`).
- JVM: intern level-argument lists to small ints; `subId` is part of every eval/infer key. Mandatory for
  soundness to include it in closure identity (arena test `bugs/closure-level-subst` kills checkers that forget).

### T8. Import (§5): schema-specific scanner, no expression interning, names carry their declaration index
- Evidence: commits "scan import lines in one pass", "inline the ndjson scanner hot path"; CHANGELOG 0.3.1
  "Build runs of application lines straight into the import table".
- JVM: read the file into (or map as) byte storage, parse with 8-byte `VarHandle` loads; write nodes straight
  into primitive arrays indexed by the export's own ids. No per-node object, no hash table.

### T9. Arena memory discipline (§12): bump allocation, bulk reset, bounded direct-mapped tables with O(1) clear
- JVM: see T1. Direct-mapped lossy caches (key arrays + epoch stamp) port 1:1 and are GC-cheap if small.

### T10. Build-level (§13): PGO on the arena command line, LTO, abort-on-panic, alignment
- JVM analogue is JIT-budget control, not PGO. Out of scope here; see open questions.

### T11. Micro-optimisations of the hot paths
- Cache probes are inlined "frameless" fast paths; the miss path is a separate non-inlined function
  (`eval.rs:90-141`, `infer.rs:203-246`, `conv.rs:60-72`). Hash of a key is computed once and reused for the
  insert after the miss. Step counter ticks only on misses. Application spines up to 16 args are built on the
  stack. JVM: same shape helps the JIT inline the hit path; keep miss paths in separate methods.

---

## 2. Soundness-sensitive short-cuts (do not copy blindly)

| # | Short-cut | Where | Why it is fragile |
|---|-----------|-------|-------------------|
| S1 | **Canonical locals**: the local standing for a binder is looked up by `(binder depth, domain value ptr)`; sibling scopes and later declarations reuse the same local | `conv.rs:182-205`, used by `infer.rs:141-154`, `decl.rs:65-78,99-119` | Sound only because a local's identity then determines its type and (transitively) the types it depends on, and because depth strictly increases with nesting. Any code path that opens a binder without bumping `depth` (or restores it early) aliases two live variables. All durable open-term caches depend on this. |
| S2 | **Durable open-term caches** keyed by environment *pointer* (`eval_memo`, `infer_open`) or matched on the read slots only (`eval_proj`, `infer_proj`, `agree`) | `eval.rs:103-176,272-280,703-738`, `infer.rs:372-376,525-550` | Arena test `bugs/open-term-cache-context` is exactly a type cache for open terms that ignores binder types. Here the key includes every value the term can read; correctness depends on `sup`/support being exact or over-approximate. |
| S3 | **Dummy arguments**: when a closure body does not mention var 0, a constant placeholder is pushed instead of the argument/local | `conv.rs:232-236`, `infer.rs:124-131,295-299` | Relies on the `sup` mask (`uses_arg` may over-approximate, `reads_arg` is exact). A wrong mask substitutes garbage. |
| S4 | **Typed closures** (`Clo.typed`): same body/env, but instantiation *infers* instead of *evaluates* | `value.rs:62-69`, `eval.rs:563-571` | The flag must be part of closure identity and of structural equality (it is: `intern.rs:21-34`, `conv.rs:39-42`). Arena test `bugs/pi-closure-context` (sokonanoda accepted `False`). |
| S5 | **Persistent negative cache** `neq_cache` | `conv.rs:74-101` | Only definite inequalities may enter: probe budget exhaustion unwinds with `Stop` before the insert; not written while deriving rules; not written when either side is a lambda (reason not documented). A session is discarded on any failure and only moves to later declarations (`session.rs:170-173`). |
| S6 | **Check/Only split and universe scoping of cached types** | `tables.rs:60-91`, `infer.rs:216-244,496-503,552-563`, `mod.rs:296-328` | A type inferred without checking must never answer a checking query; a checked result that relied on the declaration's universe parameters is tagged with them and only admitted when they are in scope. |
| S7 | Skipping the lambda-domain check when `a == b` by pointer | `decl.rs:110-115` | Fine only because the declared type was fully checked first. |
| S8 | `relevant` masks in same-head argument comparison (skip unused and Prop-typed args) | `conv.rs:389-470` | Skipping a Prop argument is proof irrelevance applied without comparing the proofs' types; it is justified only because both applications are well typed with the same head and earlier relevant arguments are compared. |
| S9 | Derived rules computed over locals with placeholder types | `derive.rs`, `whnf.rs:107,324,351` | Type-directed steps (K, structure eta on the major) are disabled on open values while deriving; anything new that consults a local's type during `whnf` must also be gated on `deriving`. |
| S10 | Recursor accepted without type-checking when structurally identical to the regenerated one | `decl.rs:176-203` | Requires the generator to be right. The non-identical path type-checks **before** comparing (needed by arena tests `bugs/rec-rule-proof-irrel`, `bugs/rec-type-proof-irrel`). |
| S11 | Primitive bodies trusted | `import/primitives.rs:1-3`, `docs/TRUST.md` | A forged `Nat.add` body with the right type is accepted by default (`tests/fixtures/forged-nat-add.verdict`: all three cores "accepted"); `--prelude` closes it but the arena run does not pass it. TRUST.md states this is the official kernel's trust model too ("Like Lean's kernel … trusts their bodies"); not re-verified here. |
| S12 | Declaration re-ordering | `reorder.rs` | Verdict must not depend on it: it refuses to reorder if any recorded dependency points forward, and each check still only sees indices `< idx` (`mod.rs:331-336`). A dependency the recorder missed becomes a false "unknown constant" rejection, not an unsound accept. |
| S13 | Import does not hash-cons expressions | `import/importer.rs:409-412` | Pure performance assumption (exporter already shares). Structural comparisons that matter use `ExprPtr::same` (`term/expr.rs:135-180`), not pointer equality. |

Safety net they built (worth copying): a `memocheck` cargo feature recomputes **every memo hit** with the cache
bypassed and panics on disagreement (`value_checker/memocheck.rs`, `checker/memocheck.rs`); `derivecheck`
asserts fired rules agree with unfolding (`whnf.rs:205-212`); `nl-mutate` mutates exports and diffs the three
engines' verdicts; debug builds check every `sup` mask against a naive traversal (`eval.rs:630-635`).

---

## 3. Architecture map and data flow

Single crate `nano-lean`, four binaries (`README.md`):

| Binary | Role |
|--------|------|
| `nl-fast` (`src/bin/nl-fast.rs`) | The arena binary. Imports an export (NDJSON, `blean` binary, or `.olean`), optionally reorders, checks in parallel with the value core. `--term-core` selects the substitution engine; `--fallback` re-checks a declaration through the reference-kernel bridge when its arena budget runs out (off by default: `native_only = true`, `fast.rs:97`). |
| `nl-ref` | Reference kernel (`src/kernel/`, ~5.8 kLoC, built on the author's `unbound` binding library), also a parser/checker for a small core language (`.ltc`). Used as oracle and for a few bridged paths. |
| `nl-mutate` | Mutation tester: edits an export, runs reference / term / value engines, reports disagreements. |
| `nl-watch` | Lake project watcher with a content-addressed "accepted declarations" cache. |

Modules that matter for `nl-fast`:

```
src/import/{mod,importer,scan,json,blean,primitives,prelude}.rs   export -> Store
src/term/{expr,ptr,level,name,decl,intern,arena,ctx,ops}.rs       store data model, level algebra
src/reorder.rs                                                    dependency-order renumbering
src/fast.rs                                                       worker pool, sessions, exit code
src/value_checker/{mod,value,intern,tables,eval,whnf,derive,conv,infer,decl,session}.rs   value core
src/checker/{mod,whnf,defeq,contextual,inductive,native_inductive(+recursor),quot,nat,env}.rs  term core
src/kernel/...                                                    reference kernel (bridge target)
```

Data flow of `nl-fast -q -j 4 FILE.ndjson` (`bin/nl-fast.rs:200-335`):

1. `Arena::new()` (bumpalo). `import::import_with` → `Store { dag, declars: Vec<Declar>, blocks, names, deps }`.
2. `fast::release_import_memory(threads)`: `mi_collect(true)`; purge delay disabled when threads <= 16.
3. Optional `--prelude` / `--axioms` checks (not used by the arena).
4. `reorder::reorder(&mut store)` (default on).
5. `Checks::run`: spawn `min(threads, n)` scoped worker threads (256 MiB stacks). Each owns a `Session`
   (arena + durable tables) and claims contiguous index runs from an atomic counter. Per index:
   `Session::check(idx)`; `Quot/Ind/Ctor/Rec` go to the term core, `Axiom/Def/Thm/Opaque` to the value core.
6. Exit code: 0 all accepted; 1 if any rejection; 2 otherwise (decline/internal) (`fast.rs:379-383`).
   Import errors: `Invalid` → 1, `Unsupported`/`Internal` → 2 (`bin/nl-fast.rs:154-160`).
   `process::exit` is called without dropping the store ("pure teardown cost", line 333).

Each declaration `idx` is checked against the immutable store with visibility limit `idx`
(`Decls::declar`: `decl_idx < limit`), so declarations are independent and any order/parallelism gives the
same verdicts.

---

## 4. Data representation

### 4.1 Names, strings, levels (interned at import, global lifetime)

- `NameNode { kind: Anon | Str(prefix, StringPtr, hash) | Num(prefix, u64, hash), decl: AtomicU32, nat: AtomicU8 }`
  (`term/name.rs:80-84`). **The name node stores its declaration index and a Nat-primitive tag**, so constant
  lookup is `name.decl_idx()` + bounds check against `limit` — "the checker never hashes a name".
- `Level = Zero | Succ(l, h) | Max(a, b, h) | IMax(a, b, h) | Param(name, h)` with stored hash, hash-consed.
  Smart constructors pre-simplify: `max(l, l) = l`, `max(l, 0) = l`, `imax(l, 0) = 0`,
  `imax(l, succ r) = max(l, succ r)`, `imax(0, r) = r`, `imax(l, l) = l` (`term/ctx.rs`, fns `max`/`imax`).
- Level lists are interned slices; `LevelsPtr` packs the length in the top 16 bits of the pointer.
- Level comparison (`term/ops.rs:25-205`): `simplify` (memoised per level) then `leq_core(l, r, diff)` with
  case split on `imax(_, param)` (substitute the param by `0` and by `succ p`), distribution of `imax` over
  `max`/`imax`; returns `None` ("reject") on a shape normalisation should have removed.
  `level_eq = ptr-eq || (leq && geq)`.

### 4.2 Expressions of the store (`term/expr.rs:17-74`, 32 bytes each, asserted at line 182)

```
Var{idx:u16, hash} | Sort{level, hash} | Const{name, levels, hash}
App{fun, arg, hash, sup:u32} | Lam{ty, body, hash, sup} | Pi{ty, body, hash, sup}
Let{data:&LetData{ty,val,body,nondep}, hash, sup} | Proj{name, idx:u16, e, hash, sup}
NatLit{n:&BigUint, hash} | StrLit{s, hash} | Local{id:u32, ty, hash}   // Local: term core only
```

- Pure de Bruijn indices; binder names and binder info are dropped at import (validated, not stored); `mdata`
  nodes become aliases of their child (`import/json.rs:135-137`).
- `ExprPtr` = 64-bit tagged pointer: low 48 bits address, top 16 bits `Meta` = 15-bit `nlb` (number of loose
  bound variables, i.e. max index + 1) | 1 bit `HAS_LOCAL` (`term/ptr.rs:55-107`, `term/expr.rs:223-235`).
  So `closed()` is a test on the pointer, no memory access.
- `sup`: bitmask of loose bvars 0..31 occurring in the node; `WIDE = u32::MAX` means "unknown/more than 32".
  Computed bottom-up at node construction: `app = sup(f)|sup(a)`, binder = `sup(ty) | sup(body)>>1`.
  For `WIDE` nodes an exact sorted `[u16]` support is computed lazily and cached per node, capped at 256 slots
  (`eval.rs:629-685`).
- `hash` (64-bit, from child pointers) is only used by the term core's interner.

### 4.3 Values (`value_checker/value.rs`)

```
Val   { k: K, open: bool, memo: Cell<Option<&Memo>> }      // ~64 B [my estimate, not asserted in source]
K     = Sort(LevelPtr) | Pi(dom: V, Clo) | Lam(dom: &Lazy, Clo) | Neu(Head, &[V]) | Nat(&BigUint) | Str(&str)
Head  = Local(id:u32, ty: V, letValue: Option<V>) | Const(NamePtr, LevelsPtr) | Proj(structName, idx:u16, V)
Clo   { env: Env, sub: Sub, body: ExprPtr, typed: bool }
Lazy  { env, sub, e: ExprPtr, val: Cell<Option<V>> }       // memoised thunk, used only for lambda domains
Sub   { ks: LevelsPtr, vs: LevelsPtr }                     // delayed universe instantiation; id = (∅, ∅)
Memo  { epoch:u32, whnf_core, whnf, unfold: Option<Option<V>>, ty }   // allocated on first use
Env   = Option<&EnvObj>;  EnvObj (32 B, asserted) = Node{head: V, tail: Env, base: Env, depth:u32, open}
                                                 | Frame{vals: &[V], open}
```

- `open` = "may mention a local" (over-approximated through closure environments). Closed values are
  substitution-invariant; many paths short-cut on `!open`.
- Environment = a run of at most `RUN = 64` linked `Node`s on top of a flat `Frame`. `Node.base`/`depth` let
  a lookup of index `i >= depth` jump straight into the frame; `i < depth` walks `i` links (unrolled by 2).
  A push onto a run of 64 collapses everything into a new frame (`eval.rs:47-88`).
- Locals are neutrals with a unique `u32` id carrying their type (and value for `let`-bound Nat locals).
  No de Bruijn levels; no quoting, so no fresh-level bookkeeping at all.

### 4.4 Hash-consing: tables, scopes, lifetimes

| Scope | Table | Interned objects | Lifetime |
|-------|-------|------------------|----------|
| Store `Dag` (`term/intern.rs:99-106`) | hashbrown `HashTable<&T>`, hash stored in node | names, strings, levels, level lists, big nats. **Expressions are not interned at import.** | process |
| Session `Ctx.dag` (`term/ctx.rs:11-22`) | same | levels / level lists created while checking (looked up in the store first); term-core expressions | session |
| Value core `hc[closed\|open]` (`intern.rs:90-151`) | `Set` = table of `(hash, &Val)`, key recomputed from the value | `Sort`, `Lam` (key: lazy-dom ptr, env ptr, sub, body, typed), nullary `Const`/`Proj` neutrals, `Str`; `Pi` only via `mk_pi` (substitution) — plain `mk` skips the probe for `Pi` ("rarely recur, probing only costs") | session |
| `neus` | `(hash, &Val)`; hash over head + arg pointers, stored so a rehash never walks a spine | neutrals with a spine | session |
| `nats` | by value | `Nat` literals | session |
| `envs[closed\|open]` | direct-mapped, 2^16 slots, key `(v ptr, env ptr)` | pushed env nodes (lossy: a miss just allocates a duplicate) | session |
| `frames[closed\|open]` | hash map keyed by the pointer slice | flat frames | session |
| `lazies[closed\|open]` | key `(env ptr, sub, ty expr)` | lambda-domain thunks | declaration |

Hashing: Fx multiply-rotate over pointer words for value tables (`tables.rs:287-328`); a stronger
multiply-xorshift mixer for store nodes because "FxHasher is linear and collides on large arenas"
(`term/mod.rs:16-72`).

---

## 5. Export parsing

Entry: `import::import_with(arena, path, threads)` (`import/mod.rs:43-56`).

- `blean` (binary, mmap'ed via `memmap2`) if the file sniffs as such; otherwise NDJSON (lean4export format
  version must be exactly `"3.1.0"`, `export/mod.rs:20`).
- NDJSON threading: `threads = --import-threads`, default `2` only if the file is > 16 GiB **and** `-j > 1`,
  else `1` (`bin/nl-fast.rs:13,228-236`; comment: "Scanning helpers cost instructions, so only exports too big
  to read serially get them"). With `1`: files > 32 MiB are read by one extra I/O thread in 16 MiB blocks cut
  at the last newline, handed over a bounded channel (2 blocks ahead); the main thread parses
  (`import/mod.rs:179-244`). No mmap for NDJSON, no SIMD crate except `memchr` for newline search.
  With `>= 2`: helper threads `pread` 4 MiB chunks and pre-scan lines into `Line` records; the main thread
  builds in order (`import/mod.rs:116-177`).
- No lazy/deferred proof bodies for NDJSON: everything is materialised into the arena at import
  (lazy cone decoding exists only for the `--olean` path, `import/mod.rs:287-304`).
- No intermediate binary format is produced for NDJSON input.

Scanner (`import/scan.rs`), all specialised to lean4export's exact byte layout (sorted keys, no spaces);
anything that does not match falls back to `serde_json::from_slice` + a generic handler (`import/json.rs`):

- `fast(b)`: if at least 128 bytes remain, dispatch on byte 2: `'a'` → `app`, `'f'` → `forallE`, `'i'` → `lam`
  (`{"ie":N,"lam":…}`), each matched with 8-byte word compares against constant tags (`has`, `tag`, `ld`,
  lines 503-530); otherwise `other()` handles `thm`, `def` (only when the mutual group is the declaration
  itself and `safety` is `safe`), `app`, `bvar`, `const`, `forallE`, `lam`, `letE`, `sort`, `proj`,
  name `str`/`num`, level `succ`/`param`/`max`/`imax`.
- Integers: SWAR. XOR the 8-byte word with `0x30…`, find the first non-digit lane, fold digit pairs with two
  multiplies (`id`, `fold`, `above`, lines 619-666). Canonical decimals only (no leading zeros).
- `Importer::apps` (`import/importer.rs:276-310`): a dedicated loop that consumes a **run of consecutive `app`
  lines** as long as each new id equals `dense.len()` (ids arrive in order), with table and recorder moved into
  locals "to keep its constants in registers". Per line: 3 SWAR ints, 2 loads of child pointers, `mk::app`
  (hash, `sup`, `nlb`), one 32-byte bump allocation, one push, plus the dependency summary when recording.
- Id → node tables are dense `Vec<Option<Ptr>>` pre-sized from `file_len / 56` bytes per line
  (`BYTES_PER_LINE`), with a sparse hash-map escape for forged far ids (`Table`, lines 120-215).
- Expressions are **only allocated, never looked up** (line 409: "Exported nodes are already shared and
  checking never looks them up"). Names, strings, levels, level lists and nat literals are interned.
- Nat literals: decimal string → `BigUint`, at most 65536 digits else "unsupported" (line 118).
- Validation at import: duplicate declaration names, unknown/forward ids, `unsafe`/`partial` rejected,
  quotient names reserved, group metadata (`all`) consistency, primitive **types** pattern-matched
  (`import/primitives.rs:77-135`: `Nat`, `Nat.zero/succ`, `Nat.log2`, the 12 binary `Nat → Nat → Nat`
  operations, `Nat.beq/ble`, `Bool`, `Bool.true/false`, `Char`, `Char.ofNat`, `String`, `String.ofList`,
  `List`, `List.nil/cons`).
- Dependency recording (only for inputs >= 64 MiB, `RECORD_BYTES`): per expression id a 4-byte summary
  "no constant below / exactly declaration j / many (≙ the declaration during which this node was created)";
  when a declaration's line references a node created before the declaration started, the summary yields a
  dependency edge in O(1) (`Recorder`, lines 36-111). Result: CSR lists `Deps{ends, decls, names}` for §12.
  The v0.4.3 commit message (5dabccf) says this recording "took about a fifth of the builder's time, most of
  it stalled on a summary table far larger than any cache".

Cost share: not published for NDJSON. The only data point is the README table (blean, M5, `-j 1`, v0.1.0-era):
wall 57.4 s vs "kernel check" 53.5 s, i.e. ≈ 3.9 s (7 %) outside checking. **[unverified for NDJSON]**

---

## 6. Evaluation (`value_checker/eval.rs`)

Strategy: **strict (call-by-value) evaluation into closures and folded neutrals; no delta, no iota in `eval`.**
`eval` performs only beta, zeta (`let`), and projection-of-constructor. Arguments are evaluated eagerly, but
since constants never unfold this is cheap and every step is memoised. There are no general call-by-need
thunks; the only thunk is the lambda domain.

```
eval(env, sub, e):
  if e is Var i: return env[i]                         // inlined, reject if out of range
  closed = e.nlb == 0;  if closed: env = EMPTY
  key = closed ? (e, sub) : (e, sub, envPtr)
  hit eval_closed[key] (hash map)  /  eval_memo[key] (lossy 2^16)  -> return
  -- miss --
  guard stack; tick
  if e is an open App with sup != 0:                   // "projected" hit
      (env0, v) = eval_proj[(e, sub)];  if agree(env0, env, slots(e)): memo and return v
  v = case e of
    Sort l        -> intern Sort(subst(sub, l))
    Const c ls    -> intern Neu(Const(c, subst(sub, ls)), [])
    App           -> collect spine (<=16 args on stack, else SmallVec<32>)
                     head = (f is Const && sub is id) ? Head::Const(c, ls)        // no value built for the head
                                                      : eval(env, sub, f)
                     args = map eval
                     head is Head ? neu(head, [], args) : apply(headValue, args)
    Lam ty body   -> env' = trim(env, e);  Lam(lazy(env', sub, ty), Clo{env', sub, body, typed:false})
    Pi  ty body   -> env' = trim(env, e);  d = eval(env', sub, ty);  Pi(d, Clo{env', sub, body, false})
    Let           -> v = eval(val);  eval(push(env, v), sub, body)
    Proj S i s    -> proj(S, i, eval(s))               // picks the field if s is a constructor application
    NatLit/StrLit -> interned literal value
  insert into eval_closed / eval_memo (+ eval_proj for open apps)

apply(f, args):                                        // eval.rs:585-626
  loop over args:
    f = Lam(_, c) untyped: env = push(c.env, arg); while body is syntactically `Lam` and args remain:
                               env = push(env, nextArg); body = body.body      // multi-arg beta, no
                           f = eval(env, c.sub, body)                           // intermediate closures
    f = Lam(_, c) typed  : f = inst(c, arg)            // infer(push(env,arg), body, Only)
    f = Neu(h, sp)       : return neu(h, sp, restOfArgs)                        // interned, spine copied
    otherwise            : reject "expected a function"

trim(env, e):                                          // eval.rs:457-523
  slots = sup mask of e, or exact support list if WIDE (None if > 256 slots -> keep env)
  if slots empty -> EMPTY
  n = maxSlot + 1
  if all of 0..n used && env.size == n && env.depth <= 8 -> keep env
  if WIDE && n >= 128 && n > 4*|slots| -> keep env                 // sparse & long: frame would cost more
  if mask: probe prunes[(envPtr, mask)]
  buf = [dummy; n]; fill buf[j] = env[j] for j in slots (single walk)
  frame_in(buf)                                                    // interned flat frame
```

- `proj(S, i, v)` (`eval.rs:544-552`): if `v` is `Neu(Const ctor, args)` with `ctor` a constructor, return
  `args[numParams + i]`; else intern `Neu(Proj(S, i, v), [])`.
- `inst(clo, x)`: `push` then `eval` (or `infer … Only` for typed closures).
- `zeta/subst` (`eval.rs:341-454`): value-level substitution of local ids by values (used for Nat `let`s,
  `infer_redex`, and rule firing). Skips closed sub-values, memoises per call by value pointer, rebuilds
  neutrals/closures/envs through the interning constructors, re-applies when a substituted local was a head.

---

## 7. WHNF and reduction rules (`value_checker/whnf.rs`)

```
whnf_core(v):                                  // no delta
  not Neu, or plain local -> v;  memo hit -> return
  Const c ls, args  -> reduce_rec(c, ls, args) then recurse, else v
  Proj _ i s, args  -> c = whnf(s) (string literal -> ctor form); if ctor app: apply(field, args), recurse
  let-local x := d  -> apply(d, args), recurse
  store memo.whnf_core

whnf(v):
  Sort/Pi/Lam/Nat/Str/plain local -> v;  memo hit -> return
  loop: t1 = whnf_core(t)
        if reduce_nat(t1) = n -> result n
        match unfold(t1, rules = true): Some(u) -> t = u ; None -> result t1
  store memo.whnf on every value visited on the path
```

- **Delta** (`unfold_uncached`, lines 157-216): head must be a `Def` or `Thm` (theorems unfold, with hint
  `Opaque`; `Opaque` declarations and axioms do not) and the level count must match. The body is evaluated
  **once per `(constant, level args)`** into `delta` (a closure value) with a hit counter; unfolding is
  `apply(bodyValue, args)`. Result memoised on the neutral unless a derived rule fired.
- **Iota** (`reduce_ind_rec`, 265-318): major = `args[major_idx]`; if the recursor is K-like, first try
  `expose_ctor_when_k` (type of major whnf'd to `I params idx…`; build `C params`; accept if its type is
  def-eq to the major's type); `whnf(major)`; Nat literal → `Nat.zero` / `Nat.succ (n-1 literal)`; string
  literal → `String.ofList [Char.ofNat …]`; otherwise structure-eta on the major
  (`expose_ctor_when_structure`: non-recursive single-constructor, zero-index type, not a Prop: rebuild
  `C params (major.0) (major.1) …`). Then find the rule by constructor name, evaluate the rule RHS once per
  `(rec, rule index, levels)` (`rules` table) and `apply(rhs, params ++ motives ++ minors ++ ctorFields ++ extraArgs)`.
- **Quotient**: `Quot.lift` (major 5) / `Quot.ind` (major 4), only if the constants are declared as `Quot`
  declarations; major whnf must be `Quot.mk` with exactly 3 args; result `f a` applied to the rest (250-263).
- **Nat literals** (`reduce_nat`, 431-473; arithmetic in `checker/nat.rs`): only for **closed** values with 1
  or 2 args whose head name carries a Nat tag: `succ, log2` unary; `add, sub, mul, div, mod, gcd, beq, ble,
  land, lor, xor, shiftLeft, shiftRight, pow` binary. Args are `whnf`'d and must be a literal or `Nat.zero`.
  `pow`/`shiftLeft` decline (fall back to unfolding) when exponent > 2^24 or result would exceed 2^26 bits.
  Big multiplication has a sparse-limb path; nat hashing has a wide 8-lane path (0.4.0/0.4.1 changelog).
- **Nat `let`** (`infer.rs:470-482`): in checking mode, a `let x : Nat := v` binds `x` to a local that carries
  its value and unfolds only at the head, "so closed literal folding never forces the value" (mirrors Lean).
- **Strings**: literal kept as `Str`; expanded to constructor form only when a recursor/projection/def-eq needs it.
- No special compiled forms for matchers / `casesOn` / `brecOn` / well-founded recursion — they are ordinary
  definitions that unfold — except through the generic derived rules of §8.

---

## 8. Derived rules for hot definitions (`value_checker/derive.rs`)

Trigger (`whnf.rs:188-201`): in `whnf` (not in lazy delta), when a constant's unfold counter reaches
`HOT = 32`, it is not a Nat primitive or one of its auxiliaries, and fewer than 256 derivations were made by
this checker instance.

```
derive(f = body value, body expr):
  arity = number of leading syntactic lambdas of the body; require 1..=16
  budget = min(steps_left, 65536); deriving = true
  slots = arity fresh locals typed with a placeholder
  root = explore(depth 0);  on any failure/budget exhaustion -> Hot::Never (never retried)

explore(depth):
  r = whnf(apply(f, slots resolved to locals or constructor applications))
  leaf = Leaf(r) if "open size" of r <= 256 nodes else Leaf(None)
  if depth >= 3 -> leaf
  blocker = follow r: while r is `rec … major …` (or a projection's struct), up to 8 hops:
              m = whnf(major); if m is an unsplit slot local -> that slot; else continue into m
  none, or the inductive has > 8 constructors -> leaf      (> 64 nodes in this rule: abort the derivation)
  for each constructor C: bind slot := C fresh…(params+fields), node_C = explore(depth+1)
  Split{slot, branches[(C, levels, nargs, firstLocalId, node_C)]}

fire(rule, args):                    // args.len() >= arity
  bind = [(localId_i, args[i])]
  walk: Split -> v = whnf(bind[slot]); string -> ctor form; Nat literal matches zero / succ(pred literal);
                 constructor must match by name, level-list pointer and arg count; push its args into bind
        Leaf(t) -> result = subst(bind, t) applied to args[arity..]
        Leaf(None) / no match -> fall back to ordinary unfolding
```

Soundness argument given in the file header: "Reduction commutes with substitution, so a leaf instantiated at
matching arguments is a reduct of the call." While `deriving`: K and structure-eta are skipped for open
majors, open Nat-primitive applications are left folded, `neq_cache` is not written. Lazy delta calls
`unfold(v, rules = false)` "so it still sees the same-head states it compares by arguments".

---

## 9. Definitional equality (`value_checker/conv.rs`)

```
def_eq(t, s):
  ptr-eq or (t,s) in eq_cache -> true                                  // inlined fast path
  tick; (min,max) in neq_cache -> false
  same(t, s, fuel 512) -> true        // bounded structural compare of hash-consed values (closures: same
                                      // body/sub/typed and structurally same env)
  r = def_eq_core(t, s)
  r ? eq_cache += (t,s),(s,t) : (if !deriving && neither is Lam) neq_cache += (min,max)

def_eq_core(t, s):                                                      // exact order
  1. quick: ptr/eq_cache; Lam-Lam or Pi-Pi -> binder loop; Sort-Sort -> level_eq; Nat-Nat; Str-Str
  2. if s is the constant Bool.true and t is closed: whnf(t) is Bool.true -> true
  3. same-head probe: both `Const c` applications, same arg count > 0, c unfoldable, same name, levels equal:
        probe_args(t, s, budget = 16384 if hint Regular else 1024) -> true
  4. unless statically_not_proof(t): proof irrelevance — type_of(t) is a Prop ? return def_eq(type_of t, type_of s)
  5. tn = whnf_core(t), sn = whnf_core(s); if either changed: quick(tn, sn)
  6. lazy_delta(tn, sn)  -> decided, or stuck pair (tn, sn)
  7. Const-Const without args: name and levels;  Proj-Proj without args: same struct/index and def_eq structs
  8. whnf_core again; if changed -> def_eq_core(restart)
  9. def_eq_app: equal heads and arg counts; args pairwise (for Const heads through the `relevant` mask)
 10. eta(tn, sn), eta(sn, tn): one side Lam, other not: other's type whnf is Pi with def-eq domain;
                               compare body with `other x` under a binder local
 11. eta_struct both ways: one side a fully applied constructor of a structure-like type: types def-eq and
                           each field def-eq to the projection of the other side
 12. string_expand: Str vs `String.ofList …`
 13. unit_like: type whnf is a structure-like type with 0 fields -> compare the two types

lazy_delta(t, s): loop
  a. offset: both zero -> true; both successors (literal > 0 or `Nat.succ a`) -> def_eq(preds)
  b. both closed: reduce_nat(t) or reduce_nat(s) -> def_eq_core with the folded literal
  c. hints ht, hs = delta hints (None if not unfoldable)
  d. if "past" non-empty and met_past(t, s) -> true
  e. (None, None) -> stuck; one side only -> unfold it + whnf_core
     both: compare hints (Abbrev unfolds before Regular before Opaque; larger Regular height first)
        unequal -> remember the side's current form in `past` (max 4, Regular const applications), unfold it
        equal   -> if Regular, same constant, levels equal: probe_args(t, s, 16384) -> true
                   unfold both
  f. quick(t, s) decides -> return
```

- `probe_args` (356-387): if the pair is in `fail_cache` → false. Run `args_eq` under a temporarily reduced
  step budget; exhaustion or inequality records the pair in `fail_cache` (both orders) and returns false,
  i.e. "inconclusive, go unfold". Nested probes do not get their own budget. Probe steps are charged to the
  declaration.
- `met_past` (560-577): if the current form of one side has the same head constant as a form the *other*
  side had before it was unfolded, compare arguments with that earlier form (fix for arena test
  `perf/loop-unfolded-once`).
- `relevant(c, n)` (389-431), cached per `(constant, n)`: start all-true; strip up to `n` leading lambdas of
  the definition body, mark as irrelevant every parameter not in the body's support; then mark irrelevant
  every argument whose declared type (from the constant's Pi telescope) is syntactically a `Prop`-valued
  application (head constant or earlier parameter whose type ends in `Sort 0`).
- `statically_not_proof` (312-345): `Sort`, `Pi`, literals; a constant application whose result type (by
  syntactically stripping Pis of the constant's type) is a `Sort`, or is headed by a constant whose own result
  sort is syntactically positive (`Succ`, `max` with a positive side, `imax _ positive`); a bare local whose
  type is a `Sort` or such an application.
- Binder comparison (`def_eq_binding_in`, 211-241): iterative over nested binders; domains compared (lambda
  domains forced); identical closures (same body, sub, typed flag, env pointer) → true without instantiating;
  otherwise instantiate both with the canonical local for `(depth, domain)`, or with the dummy when neither
  body mentions the variable.
- No union-find. Equivalence classes are approximated by hash-consing + the symmetric pair set `eq_cache`.

Differences from the official kernel, checked against `lean4/src/kernel/type_checker.cpp:1128-1203`
(`is_def_eq_core`) and `:962-1010` (`lazy_delta_reduction_step`) of the clone in `refs/lean4` (611c117, v4.36
branch). Official order: quick → `Bool.true` hack → `whnf_core` with cheap projections → quick → proof
irrelevance → lazy delta → const / fvar / proj (`lazy_delta_proj_reduction`) → full `whnf_core` → restart →
app → eta → eta-struct → string literal → unit-like. nano-lean deviates as follows:

1. Same-head argument probe (step 3) runs **before** any `whnf_core` and before proof irrelevance, for any
   unfoldable head (the official kernel only tries arguments inside lazy delta, when both sides are the same
   `regular` definition), and it is **step-budgeted**; the official `is_def_eq_args` attempt is unbounded
   (it has the same kind of failure cache: `failed_before` / `cache_failure`).
2. Proof irrelevance is tested on the un-reduced terms, before `whnf_core`, and skipped by a syntactic test.
3. No "cheap projection" mode: `whnf_core` on a projection always fully `whnf`s the structure; consequently
   there is no `try_unfold_proj_app` and no `lazy_delta_proj_reduction` (stuck `Proj`-`Proj` just compares
   the structures with `def_eq`).
4. "Past forms" in lazy delta; argument relevance masks; derived rules in `whnf` (not in lazy delta).

---

## 10. Type inference (`value_checker/infer.rs`)

Two modes (`tables.rs:60-65`): `Check` (full checking) and `Only` (compute the type of a term already known
to be well typed; no argument/let/level checks). Results live in separate tables; a `Check` result may answer
an `Only` query, never the reverse.

```
infer(env, sub, e, mode):
  Var i: v = env[i]; plain local -> its type; else type_of(v)            // types of values are memoised
  closed e: infer_closed[Check][(e,sub)] hit if its recorded uparams are ∅ or == current uparams
  open e  : infer_open[Check][(e,sub,envPtr)] likewise; in Only mode also infer_open[Only]
  -- slow path --
  closed: retry with `admit` (recorded uparams ⊆ current); Only: infer_closed[Only]
  binders/let: env = trim(env, e) and re-probe with the trimmed env
  open App: infer_proj[(e,sub)] whose environment agrees on the slots e reads
  case e:
    Sort l     : Check: every param of l must be declared; type Sort(succ(subst l))
    Const c ls : declared below `limit`, level count matches, (Check) params declared; const_type(c, subst ls)
    App        : spine; if head is a literal Lam and mode = Check -> infer_redex for the first argument
                 ft = infer(head); for each arg: ft = whnf-to-Pi(ft)
                     Check: def_eq(infer(arg, Check), domain) else "application type mismatch"
                     x = codomain mentions var 0 ? eval(arg) : dummy
                     ft = inst_pi(piValue, x)                 // memoised by (pi ptr, arg ptr), lossy 2^16
    Lam ty b   : dom = eval(ty); Check: ty is a sort, infer(b) under the canonical local (result discarded)
                 result Pi(dom, Clo{env, sub, b, typed: true})
    Pi ty b    : imax(sort(ty), sort of b under the canonical local)
    Let        : Check: type is a sort, value's type def-eq to it; then infer(body) in push(env, eval(val))
                 (Nat-typed lets in Check mode: let-bound local + zeta of the resulting type)
    Proj S i s : Check: infer(s); type_of(Neu(Proj(S, i, eval s)))
    literals   : `Nat` / `String`, after checking that constant has type `Type`
  record Scoped{type, uparams = (mode == Only || no level check happened) ? ∅ : current}
```

- `type_of(value)` (46-95), memoised on the value: `Sort l → Sort(succ l)`; `Pi → Sort(imax …)`;
  `Lam → Pi(forced domain, typed closure)`; neutral: head type (local's type / `const_type` / `proj_type`)
  instantiated along the spine.
- `proj_type` (97-138): struct type whnf must be `S params` with exactly `numParams` args, `S` a
  single-constructor zero-index inductive; walk the constructor type; for each earlier field the later types
  depend on, substitute `proj(S, j, s)` (else the dummy); if the structure is a `Prop`, every field that is
  depended on, and the projected field itself, must be a `Prop` ("invalid projection").
- `const_type(c, ls)`: declared type evaluated once per `(c, ls)`.
- Caches, keys, scope: see §12 table. Eviction: none for hash maps within a session; lossy tables overwrite.

Declaration-level checks (`value_checker/decl.rs:19-128`):

```
Axiom: type is a sort.
Def/Opaque: check_telescope(type); check_value(value, type).
Thm: additionally the type's sort must be level-equal to 0.
check_telescope(ty): walk the Pi telescope under canonical locals (so the value check below finds the same
    environments in the caches), infer each domain in Check mode, fold sorts with imax, record the result in
    infer_closed.
check_value(v, ty): peel `Lam a . body` against `Pi b . rest` simultaneously:
    dom = eval(a); if a is not the same node as b: check a is a sort, remember (dom, env, b)
    push canonical local. Then vt = infer(body, Check); verify remembered domains def-eq; def_eq(vt, eval(rest)).
```

---

## 11. Declaration admission

- Routing (`session.rs:18-28,151-169`): `Quot`, `Ind`, `Ctor`, `Rec` are "bridged" to the term core `Tc`;
  everything else goes to the value core.
- **Inductive blocks** (`checker/inductive.rs:61-88`, `checker/native_inductive.rs:400-428`; bare line
  ranges in this bullet refer to the latter file): the whole block
  (types, constructors, recursors, as grouped in the export's `inductive` record) is validated when its first
  index is checked; the other members return OK immediately. Steps: distinct universe params; all types share
  params/universe/`all`; type telescopes checked, result sorts equal; constructors in Lean's order: metadata
  (`induct`, `cidx`, `numParams`, uparams), parameter domains def-eq to the type's, **strict positivity**
  (`positive`, 359-380: no occurrence in a Pi domain; occurrences only as `I params indices` with indices free
  of the family), field universes `<=` the inductive's universe unless it is a `Prop`, return type is the
  owner applied to the block params; `isRec`/`isReflexive`/`numNested` flags recomputed and compared.
  **Nested inductives** are expanded natively the way Lean's `replace_all_nested` does (auxiliary members are
  locals standing for `J Ds`; params of nested occurrences must be closed) (670-810). `outParam`,
  `semiOutParam`, `optParam`, `autoParam` gadgets are stripped from binder types to match Lean's generated
  recursors (209-237).
- Elimination level (`checker/native_inductive/recursor.rs:12-56`, `kernel/inductive/elim.rs:14-40`): let
  `z` = the result level with all universe params set to 0. If `z` is not zero (never a Prop): large
  elimination. Otherwise (may be a Prop): small if the expanded block has > 1 member or > 1 constructor;
  large if 0 constructors; with exactly one constructor, large iff every field is a Prop or occurs among the
  constructor's result indices. K-like iff the declared sort is `Prop`, one member, one constructor, zero fields.
  The recursor's universe params must be the type's (small) or one extra leading param not among them (large).
- **Recursors are regenerated** (motives, minors with induction hypotheses for recursive fields, rule RHS)
  and compared with the exported ones (`checker/native_inductive/recursor.rs:108-194`, `decl.rs:167-206`): metadata must
  match exactly; type and each rule: if structurally identical (`ExprPtr::same`) accept; otherwise type-check
  the exported term, then require def-eq with the generated one. Names must be `I.rec` (and `I0.rec_k` for
  nested auxiliaries). The obligations are checked by a value-core instance shared across the block; a value
  core rejection is re-decided by the term core, and disagreement between the two is an *internal* error.
- If native checking of a *nested* block rejects, the block is re-decided by the reference kernel
  (`check_existing`, `checker/inductive.rs:84-138`).
- **Quotient** (`checker/quot.rs`): `Eq` must be the expected inductive (one universe param, one constructor,
  exact type shapes); each of `Quot`, `Quot.mk`, `Quot.lift`, `Quot.ind` must have exactly the expected type
  (compared by def-eq with a hand-built term) and its prerequisites declared as quotient declarations.
- **Literals**: types of the primitive constants are checked at import; literal typing additionally checks
  `Nat : Type` / `String : Type`. Bodies of `Nat.add` etc. are not compared with anything unless `--prelude`.
- **Axioms**: any axiom is accepted (only its type is checked) unless `--axioms` restricts the list
  (not used by the arena).
- **reduceBool / trustCompiler**: no support of any kind (`grep -i reducebool src` is empty); `Lean.ofReduceBool`
  would just be an axiom and `Lean.reduceBool` an opaque constant that never reduces.
- `unsafe` / `partial` declarations and unknown declaration kinds are rejected/declined at import.

---

## 12. Scheduling, sessions, parallelism, memory

### 12.1 Re-ordering (`reorder.rs`)
Units = inductive blocks or single declarations. Everything up to the last unit containing a builtin name
stays in place ("pin"). For the rest: dependency edges from §5's recorder; if any edge points forward, or a
name is unresolved, leave the store untouched. Otherwise a Kahn topological sort with a **LIFO ready stack**
(initially the zero-indegree units in ascending index order; when a unit is emitted, units it unblocks are
pushed and therefore emitted next). Effect: a declaration is checked right after its last dependency.
`decl_idx` in the name nodes and the block table are rewritten. Disabled by `--no-reorder` or `--limit`.

### 12.2 Workers (`fast.rs:163-384`)
- Unit of work: one declaration index. `claim()`: `k = max(1, remaining / (4 * workers))`,
  `at = next.fetch_add(k)` → the worker checks indices `at..at+k` in order. Runs shrink geometrically
  (with 4 workers each claim takes 1/16 of what is left), "neighbours share constants, so runs keep a
  session's tables warm".
- Shared between workers: only the immutable `Store` (plus atomics for counters / exit code). No shared cache,
  no locks on the hot path.
- Determinism: verdict per declaration is independent of scheduling. Resource declines could depend on session
  warmth, so a declaration that exhausts the arena budget in a warm session (session arena > 1/16 of the
  budget) is retried once in a fresh session (`fast.rs:261-282`).
- Per declaration budgets: 200 M steps (ticks on cache misses), 4096 MiB arena growth, stack floor guard
  (decline instead of overflow; `resource.rs`). Exhaustion = decline (exit 2).

### 12.3 Session lifecycle (`value_checker/session.rs`, `tables.rs:140-270`)
```
Session { arena: Box<Arena>, state: Option<State>, spare tables }
check(idx):
  state usable iff state.last < idx (and local ids < 2^31)
  vc = Vc::new(...)            // takes emptied tables from a thread-local pool
  resume: vc.tables = durable tables; vc.ctx (level interner etc.), next_local, memo epoch restored
  result = vc.check(idx)
  ok  -> state = suspend(vc)   // everything except fail_cache and lazies
  err -> state dropped
worker, after each declaration: if err or session arena allocated > 64 MiB (`--session-mib`): session.reset()
  reset: tables emptied but their capacity kept (lossy tables: epoch bump, nothing written), arena reset
         (bump chunk dropped if > 16 MiB, else reused), all BigUints freed.
  a checker instance dropped after a failure returns its tables to a thread-local pool, shrunk to
         8192 entries / 65536 lossy slots (`tables.rs:190-270`).
```

### 12.4 All value-core tables (`tables.rs:102-138`)

| Table | Key → value | Kind / bound | Survives declaration |
|-------|-------------|--------------|----------------------|
| `eval_closed` | (expr, sub) → value | hash map | yes |
| `eval_memo` | (expr, sub, env ptr) → value | direct-mapped 2^16 | yes |
| `eval_proj` | (open app expr, sub) → (env, value) | direct-mapped 2^14 | yes |
| `infer_closed[Check\|Only]` | (expr, sub) → (type, uparams) | hash map | yes |
| `infer_open[Check\|Only]` | (expr, sub, env ptr) → (type, uparams) | hash map | yes |
| `infer_proj` | (open app expr, sub) → (env, type, mode) | direct-mapped 2^14 | yes |
| `insts` | (Pi value ptr, arg ptr) → codomain | direct-mapped 2^16 | yes |
| `delta` | (const, levels) → (body value, hot counter / rule id) | hash map | yes |
| `derived` | rule trees | vectors | yes |
| `const_ty` | (const, levels) → type value | hash map | yes |
| `rules` | (recursor, rule index, levels) → RHS value | hash map | yes |
| `support` / `arg_support` | expr → exact free-var list; (const, n) → relevance mask | hash maps | yes |
| `conv_locals` | (depth, domain ptr) → local | hash map | yes |
| `eq_cache` / `neq_cache` | value-pointer pairs | hash sets | yes |
| `fail_cache` | value-pointer pairs (probe gave up or failed) | hash set | **no** |
| `hc`, `neus`, `nats`, `envs`, `frames`, `prunes` | interning (§4.4) | — | yes |
| `lazies` | lambda-domain thunks | hash set | **no** ("measured not to pay for retention") |
| `Memo` on each value | whnf_core, whnf, unfold, type | fields | yes (epoch preserved) |

Two table gadgets worth copying: `Lossy` (direct-mapped, slots stamped with an epoch so clearing writes
nothing; grows by doubling from 1024 until the cap) and `Logged` (hash map that logs its first 16 keys so a
barely used table is emptied by 16 removals instead of clearing its capacity) (`tables.rs:413-571`).

### 12.5 Memory
- Store: 32 B per expression node in one bump arena; no expression hash table; import-time id tables
  (8 B/id) and the dependency summary (4 B/id) are freed after import, then `mi_collect(true)`.
- Checking memory is transient: per worker ≈ session arena (<= 64 MiB between declarations; one declaration
  may grow it up to the 4096 MiB budget) + table capacity.
- With <= 16 threads mimalloc's purge is disabled after import: freed memory stays mapped, "which saves
  instructions" (`fast.rs:112-125`); above 16 threads it purges so each worker does not pin its peak.
- Why 5 GB at `-j 4` on mathlib: store nodes dominate; workers add little. **[inference; the node count of the
  arena's mathlib export is not stated in the repo. README (blean, v0.1.0-era): 7.5 GB at `-j 1`.]**

---

## 13. Build-level tricks

- `Cargo.toml` `[profile.release]`: `opt-level = 3`, `lto = "fat"`, `codegen-units = 1`, `panic = "abort"`
  (changelog: "Abort on panic, safe since nothing catches unwinding panics"; commit "saves unwind instrs"),
  `overflow-checks = false`.
- `.cargo/config.toml`: `-C llvm-args=-align-all-functions=6` — every function on a 64-byte boundary, because
  otherwise "the value checker's hot loops move whenever unrelated code ahead of them changes size, which alone
  swung Mathlib wall time by 2%". `CFLAGS=-DMI_NO_GETENV` so mimalloc does not scan the environment at startup.
- Allocator: `mimalloc` as global allocator (`bin/nl-fast.rs:15-16`); bumpalo for arenas; hashbrown raw tables.
- `scripts/pgo.sh` (the arena build command): download `init.ndjson.gz` (a lean4export of `Init`, GitHub
  release asset `pgo-data-1`, sha256-pinned); build with `-Cprofile-generate`; run
  **`target/release/nl-fast -q -j 4 init.ndjson`** once (same flags as the arena run); `llvm-profdata merge`;
  rebuild with `-Cprofile-use`. Both builds add `-C target-cpu=native` and the alignment flag.
  Training therefore covers import + reorder + 4-worker checking of a real library, but only `Init`.
- Rust toolchain pinned to 1.97.1 with `llvm-tools`.
- Recursion: 256 MiB worker stacks; an inline stack-pointer comparison against a precomputed floor replaces
  the `stacker` crate (`resource.rs`, commit 9a32004).

---

## 14. Optimisation history (every number the repository contains)

Commit bodies are empty except the last two (post-pin); numbers come from `CHANGELOG.md` and old READMEs.

| Version / commit | Statement |
|------------------|-----------|
| README at v0.1.0 … v0.3.2 (table never updated afterwards; instruction rows deleted in v0.3.3) | Mathlib from blean: M5 `-j 1` 57.4 s wall, 53.5 s kernel check, **708.5 G instructions**, 247.7 G cycles, 7.5 GB. M5 `-j 10`: 16.7 s, **746.5 G**, 9.2 GB. M4 Max `-j 14`: 11.85 s, 742.3 G, 10.7 GB. |
| same | Navier–Stokes: `-j 1` 914.8 G → `-j 10` 971.5 G → `-j 14` 977.0 G → `-j 28` 984.8 G → `-j 64` 1011.8 G; memory 10.1 → 19.9 GB. CSLib: 258.0 G → 296.1 G (`-j 10`) → 309.2 G (`-j 64`). Quasi-Riemann: 227.4 G → 235.2 G (`-j 10`) → 242.6 G (`-j 64`). |
| 0.3.0 | "Cut Mathlib checking cost substantially": derive reduction rules for hot definitions; memoize evaluation incl. wide applications; memoize whnf and types on values; keep bounded tables across declarations; scan import lines in a single pass. "Scale cleanly across threads by claiming declarations in contiguous runs." |
| 0.3.1 | "Cut Mathlib checking cost by about a tenth": hash memo keys once + projected memo hits by lockstep environment walk; memoize Pi codomain instantiation across spines; index environment frames directly from node runs; applications of up to 16 args on the stack; runs of application lines built straight into the import table. Long beta ladders near linear by collapsing deep env runs into frames. |
| 0.3.2 | Intern Pi values built by rule substitution: deep `List` indexing "about 12x cheaper". Long sparse environments untrimmed: shift cascades "about 40% cheaper". Smaller probe budget under cheap heads: lazy projections "about 35% cheaper". Inductive blocks "about a quarter cheaper". Native nested inductives; recursors matching the generated ones accepted. No mimalloc env scan. |
| 0.3.3 | `--session-mib`; abort on panic; overlap export reads with parsing; dependency-near checking order. |
| 0.4.0 | Each big Nat literal stored once, "halving memory"; one-pass big-Nat hashing; skip zero limbs; default arena budget 4096 MiB; retry in a fresh session only after a warm one. |
| 0.4.1 / 0.4.2 | Faster big-Nat hash/multiply; parallel import for > 16 GiB; purge above 16 threads; past forms in lazy delta; PGO script. |
| `.cargo/config.toml` | Function alignment: removes a 2 % wall-time swing. |
| 5dabccf (v0.4.3) | Dependency recording ≈ 1/5 of import builder time. |
| 70869ed (v0.4.3) | Six lossy tables at 2^16 slots "do not fit in a core's share of the cache once every core runs a worker"; a smaller cap "recomputes more, which costs instructions, and waits on memory less". (Trade-off irrelevant under an instruction metric.) |

Net: 708.5 G (v0.1.0, blean, 1 thread, no PGO, import included) vs ≈ 395 G (arena, v0.4.2, NDJSON, 4 threads,
PGO) — about 1.8x fewer instructions across 0.3.0–0.4.2, but on different hardware/ISA, input format and
mathlib revision, so only indicative.

---

## 15. Answers to the extra questions

- **What is `nl-fast`?** §3. The parallel driver with the value core (NbE) as default engine, native
  inductive checking through the term core, no fallback by default.
- **How does PGO train?** §13: one run of `nl-fast -q -j 4` on an `Init` export between an instrumented and
  an optimised build.
- **How are the 4 threads used; overhead?** One importing thread (+ one I/O reader thread for files > 32 MiB;
  + scanner helpers only for > 16 GiB), then 4 checking workers with private sessions. The only measurements
  are the old README rows in §14: mathlib +5.4 % instructions at 10 threads vs 1; other corpora +3 % … +15 % at
  10 threads, up to +20 % at 64. Causes visible in the code: per-worker caches recompute shared closed terms,
  and warmth is lost at run boundaries. Overhead at `-j 4` for v0.4.2 is **[unverified]**.
- **Why faster than sokonanoda / gruszka?** Not established by this study (their code was not analysed here).
  From the arena configs only: gruszka is also PGO-built and run with 4 workers
  (`lean-kernel-arena/checkers/gruszka.yaml`); sokonanoda is a plain `cargo build --release` with
  `target-cpu=native`, run with 4 threads on the NDJSON stream (`checkers/sokonanoda.yaml`).
  Features of nano-lean that are unusual and plausibly decisive **[inference]**: cross-declaration caches with
  dependency ordering (T1), closure trimming + environment interning making memo keys hit (T2), derived rules
  (T6), not evaluating arguments of non-dependent applications during inference (T4a), the specialised
  importer without expression interning (T8).
- **Which caches persist across declarations?** §12.4: all but `fail_cache` and `lazies`, per worker, until
  the session arena exceeds 64 MiB, a declaration fails, or the next index is not larger.
- **How does it stay at 5 GB?** §12.5: 32-byte nodes without an intern table, import scaffolding freed and
  collected, 64 MiB session cap, bounded lossy tables, arenas reset in bulk.

---

## 16. nano-lean in HOAS / NbE terms

- It is evaluation-based conversion checking rather than textbook NbE: terms are evaluated into a semantic
  domain with **defunctionalised closures** `(env, usub, body, typed)`, conversion is decided directly on that
  domain, and nothing is ever read back to syntax (no normal forms are produced).
  Fresh variables for going under binders are unique-id locals, not de Bruijn levels.
- It cannot be HOAS (host-language closures): every important trick needs to *look inside* a closure — hash it,
  compare it by `(body ptr, env ptr)`, trim its environment, substitute locals inside it (`zeta_val`), measure
  it (`template_size`). For RLVM this argues for first-order closure objects even on Truffle; Truffle call
  targets can still be attached *to the body node* (shared by all closures over that body).
- Constants are kept folded with memoised unfoldings, giving the effect of smalltt-style glued evaluation
  without ever building the unfolded side eagerly.
- Evaluation is strict; laziness is obtained structurally instead (constants do not unfold; lambda domains
  are thunks; codomain/body types are typed closures; arguments of non-dependent applications are skipped at
  inference).
- Sharing is maximal and explicit: the term DAG is shared by the exporter, values by hash-consing, work by
  memo tables; `open` bits and `sup` masks bound what must be re-done.

---

## 17. Unverified items and open questions

- Size of `Val` (estimated 64 B), node count and file size of the arena's mathlib export, and therefore the
  exact import/check split of the 395 G instructions: not in the repository.
- lean4export does deduplicate expressions (`visitedExprs : HashMap Expr Nat`, `lean4export/Export.lean:38`),
  which is what "no interning at import" relies on. Not checked: whether that map's equality ignores binder
  names/info; if it does not, nodes differing only there are distinct pointers in nano-lean and miss its memos.
- Why `neq_cache` excludes lambda pairs (no comment in the source).
- Number of session resets and derived rules on mathlib (counters exist behind the `vstats` feature; no
  recorded output).
- v0.4.3 (not pinned by the arena): `--table-bits`, import prefetching and off-thread dependency recording,
  export format 3.2.0 with per-inductive feature flags gating projections and eta.

Open questions for RLVM raised by this study:

- No per-trick ablation exists upstream. Which of T1/T2/T6 carries most of the gain on mathlib has to be
  measured on our own implementation (suggested order of implementation = order of §1).
- Values as slab handles or as Java objects (T1)? Decides GC cost, table design and how much of Truffle's
  object model is usable.
- How much instruction overhead `-j 4` costs nano-lean today, and whether RLVM should check on one thread.
- JIT budget: nano-lean pays zero run-time compilation; its PGO profile is collected offline. What is the
  instruction cost of Graal compilation for the hot set (parser loop, `eval`, `infer`, `def_eq`, table probes)?
- Is a dependency-order re-numbering worth its import cost for us (it was ~20 % of nano-lean's import builder
  time before v0.4.3), or does a single thread with large tables make it unnecessary?
