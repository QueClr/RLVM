# 06 - Leaders: the nanoda lineage (sokonanoda #3, mathgraph, nanoclo, nanobruijn, nanoda_lib)

Scope: source-level deep dive, read only (nothing built, nothing run). All paths are relative to `refs/`.

| repo | clone | arena rev | clone HEAD read | arena mathlib | = instructions | threads |
|---|---|---|---|---|---|---|
| sokonanoda | `sokonanoda` | `9a021b6` | `15f5eaa` (differs from arena rev only by 62 deleted test lines) | 87.9 s / 5.0 GB | 527 G | 4 |
| mathgraph | `mathgraph-lean-kernel` (branch `flash`) | `78c7502` | same | 2.5 min | ~900 G | 2 |
| nanoclo | `nanoclo` | `b8cefd0` | same | 8.4 min | ~3.0 T (own figure 2.78 T) | 4 |
| nanobruijn | `nanobruijn` | `2712fa6` | same | 13.1 min | ~4.7 T (own figure 4.53 T) | 4 |
| nanoda_lib | `nanoda_lib` | `0da9f98` | same | 16 min | ~5.8 T | 4 |

"Instructions" = arena virtual time x 6e9. Numbers called *measured* are quoted from the authors'
READMEs, commit messages, PLAN.md or experiment manifests; I measured nothing. Anything inferred or not
checkable in source is tagged **[unverified]** or **[inferred]**.

Lineage (from README history, `git show f1c4b12:README.md` in sokonanoda):
`nanoda_lib` (ammkrn; faithful Rust port of the C++ kernel: locally nameless, substitution, per-declaration
hash-consed DAG) -> `sonanoda` (fewer type comparisons in proof irrelevance, later made conservative again)
-> `still-nanoda` (interning tweaks) -> **sokonanoda** (2026-06-12: conversion and
later inference rewritten as NbE on closures, "as in smalltt"). `mathgraph` = sokonanoda at commit `28c03d0`
(2026-09-12) + 5 micro-optimisations. `nanoclo` = nanoda_lib + own closure machine + sokonanoda's value
conversion as of August + sokonanoda's parser fast path. `nanobruijn` = nanoda_lib with pure de Bruijn terms and
shift-carrying pointers, still substitution based.

---

## 0. Decision summary (read this first)

1. **Sokonanoda is an AST interpreter `eval(env, expr) -> value-id` over a hash-consed value graph stored in
   flat arrays; nothing is substituted and no type is ever read back to a term during type checking.** A value
   is a 40-byte record addressed by a `u32`; structurally equal constructions always get the same id, so most
   conversion questions end at an integer comparison. Memo results (one-step reduct, WHNF, type) live *inside*
   the record. Same design family as gruszka (doc 05); independently arrived at.
2. **Call-by-need thunks were tried and deleted.** Sokonanoda created thunks for non-trivial arguments until
   2026-08-14 (`9b4ea12` "eagerness in some places") and removed the dead machinery on 2026-09-30 (`1fd5693`).
   Laziness now comes from three other places: (a) a constant application `c a1..an` evaluates to a *folded
   neutral* (`Unfold`) whose unfolding is computed on demand and memoised in the node - every such node is a
   shared memoised thunk; (b) lambdas evaluate to closures in O(1); (c) recursors only fire during evaluation
   when the major premise is already a constructor/literal. Argument evaluation itself is strict and memoised in
   `(pruned env, expr) -> value` tables. Implication for RLVM: do not build a general thunk mechanism first.
3. **The dominant cost is evaluation and environment keying, not conversion.** Mathgraph's profile of the
   September code: `eval` self 29.8 %, `prune_env_cold` self 11.6 %, `infer_value` self 9.7 %, `unify_general`
   self 1.6 %, universe `leq` ~1.1 % (`mathgraph-lean-kernel/experiments/flash_closure_v1_evidence.json`,
   event `current-cost-atlas`). 28.8 % of all `eval` entries are a bare variable lookup. Scale: about 1.7 G
   `eval` entries per run, i.e. roughly 500 instructions per entry all-in at 900 G **[derived from their census,
   section 3]** - that is the per-node budget a JVM interpreter has to match.
4. **Cache keys are made insensitive to irrelevant bindings by pruning the environment to the free-variable
   mask of the expression** (sokonanoda "sparse environments", nanoclo "read sets"). This is the single most
   important idea for making caches work in a closure world; nanoclo measured -10 % instructions on its mathlib
   corpus from one application of it, and on one mathlib declaration inference cache misses fell from 131 K to
   12 K (a subterm reached under 543 telescopes is inferred once instead of 543 times).
5. **Work is shared across declarations.** Caches and the value arena survive from one declaration to the next
   inside a *session* (reset when the value arena exceeds 9 MiB); a thread-lifetime set records closed export
   subterms already type-checked; a thread-lifetime store keeps WHNFs of closed terms as expressions, verified
   exactly on reuse. nanoclo paid +47 % instructions on its BitVec corpus (126.6 G -> 186.4 G) when it had to
   give up cross-declaration caches for memory reasons (`5c911e1`).
6. **Type inference returns values and is lazy in the codomain.** The type of `fun x => b` is a Pi value whose
   codomain closure means "infer `b` under this environment and context" and is only run when applied. In
   `InferOnly` mode a lambda costs O(1). Application spines walk the head's Pi telescope syntactically and never
   materialise intermediate function types; non-dependent arguments are not evaluated at all.
7. **Exported recursors, `numFields`, `isRec`, `k`, `numIndices` and quotient types are ignored and re-derived**
   (`4fe08fc` "ignore ndjson poisoned pawn"). This closes a whole class of arena `bugs/` tests by construction.
8. **Parsing is ~4 % of a run** (measured by nanoclo on a BitVec corpus, not on mathlib) thanks to exact-byte
   matching of lean4export's canonical line shapes, SWAR digit parsing, no hash-consing of export expressions,
   and no binder names or binder info in memory.
9. **Per-thread caches mean parallelism costs instructions.** Workers share only the immutable export. Mathgraph
   dropped to 2 threads; for an instruction-count metric RLVM should default to one checking thread
   **[inferred; neither repo publishes instructions vs. thread count]**.
10. **nanobruijn is the negative result:** delaying *shifts* (pointer+offset, canonical shift normal form,
    depth-bucketed caches) while keeping substitution-based reduction gains ~20 % over nanoda on mathlib and
    costs months of fragility (pointer equality missed alpha-equivalent terms -> 37 800x slowdown on one lemma).
    Delaying *substitution* (closures) is what pays.
11. **nanoclo is the cautionary middle:** closures + interned environments + skew-binary lookup, but types are
    still expressions that must be reified at every lambda, caches are reset per declaration, and it is 5.7x
    slower than sokonanoda. Its wrapper fusion (inlining `match_n`/`casesOn`/`brecOn` into definition bodies)
    is a 9x win on evaluation-heavy tests but *doubles* mathlib if always on.
12. **Three soundness bugs in sokonanoda were found by differential testing and are now arena tests**
    (closures compared without their "eval vs infer" mode; empty environments compared without their universe
    substitution; unit-eta without comparing types). Every one is a hash-consing/fast-path identity that forgot
    a component. Section 8 lists the invariants RLVM must encode in its value identity.

---

## 1. Why 16 min -> 88 s: attribution

| step | evidence | effect |
|---|---|---|
| nanoda_lib -> still-nanoda: probe the per-declaration intern table before the huge export table; skip the export probe for terms that contain local nodes | README at `06a07b7` | "5 %" + "another 10 %" on mathlib (measured by authors) |
| 4-byte aligned `Ptr` (dag marker in bit 31) | nanoda_lib commit `4219437` | -12.6 % / -12.3 % cycles on two arena tests |
| NbE with glued constants replaces WHNF-by-substitution in conversion | README at `f1c4b12` | "another 35 % speedup in Mathlib ... 40 % more peak memory" |
| July-Aug: mimalloc, own parser, sessions, level-substitution environments, infer closures, persistent WHNF store, relevance signatures for proof irrelevance, pointer equality on spines/levels | commits `6e2dd3f`..`ba5814a`; README at `0668a6f` | "about 9x faster than the official kernel, measured on mathlib" (official is 33.7 min -> ~3.7 min) |
| Aug-Sep: probe budget, streaming parser, no thunks, binder names dropped, sparse environments, interned env/app nodes | `b38b875`..`28c03d0` | mathgraph (this state plus single-digit-percent tweaks, section 3) runs 2.5 min on the arena **[inferred]** |
| Oct: session-scoped level caches, own hash map (`PtrMap`), infer telescope, signature templates, reader thread, near-63 pruning, check memos, arena reuse, index-based 40-byte value records, literal reduction strategy | `f6a74e8`..`869f4b6` | 2.5 min -> 87.9 s, i.e. ~1.7x **[inferred from arena numbers; commit messages carry no measurements]** |

For comparison, same-input ablations published by nanoclo (August, instruction counts on its "mathlib" corpus,
2.3-2.9 T total): thunks shared across environments that agree on the read set -276 G (-10.2 %, `a54f726`);
exact wide read sets -109 G (`f9b137f`); view memo -70.6 G (`fffe293`); app/proj spine interning split -40 G
(`f07f79e`); canonical-line parser -9.4 % on a 200 G corpus (`97f5cb9`).

---

## 2. sokonanoda in detail

14.7 k lines of Rust, one crate. Files: `src/frontend/parser.rs` (parse), `src/term/{expr,level,name,intern,id}.rs`
(term store), `src/checker/value.rs` (value/env/ctx stores), `eval.rs` + `eval/{environment,reduction,whnf_cache}.rs`
(evaluator), `conv.rs` (conversion), `infer.rs` (inference), `relevance.rs` (proof-relevance signatures),
`quote.rs` (read-back, used only by the WHNF store and inductive checking), `cache.rs` + `ptrmap.rs` (caches),
`tc.rs` (driver, sessions, threads), `inductive/*` + `quot.rs` (admission), `context.rs` (per-thread context).

### 2.1 Data flow

```
stdin --reader thread (4 MiB blocks cut at '\n')--> Parser.run_over (1 thread)
   -> Terms {names, levels, level lists, exprs, strings, bignums}  +  declars: IndexMap<NameId, Declar>
   -> Parser.finish: build name cache, then check_inductives()        [serial, dedicated 2 GiB-stack thread]
        for every inductive block / Quot in export order: validate, DERIVE ctors' numFields, numIndices, isRec,
        recursors, rules, K flag, Quot types; replace the parsed declarations by the derived ones;
        promote the terms built on the way into the global store (inductive/persist.rs)
   -> ExportFile (immutable, shared by reference)
   -> check_all_declars: N worker threads; each claims chunks of 64 consecutive declaration indices from an
      atomic counter and checks Axiom/Definition/Theorem/Opaque (inductives etc. are already done)
   -> exit 0 accept / 1 reject / 2 decline / 3 internal error (src/main.rs:17-20)
```

Rejection is implemented as a typed panic (`reject!` -> `panic_any(Rejection)`, `src/outcome.rs:74-79`) caught per
declaration by `Reporter::check`; a first failure sets `stopped`, other workers stop at their next chunk claim.

### 2.2 Terms, levels, names

* All ids are `u32` newtypes; bit 31 = "local" (created by the checking thread, lives in the thread's `Terms`),
  clear = global (from the export) (`term/id.rs:4-41`). Two base pointers make `node(e)` branch-free
  (`checker/context.rs:249-260`).
* `ExprNode` is **32 bytes**: `expr: Expr` (16 B tagged union of up to three `u32`), `loose: u16` (number of
  loose bvars = max index + 1), `mask: u64` (`term/expr.rs:139-147`). Pure de Bruijn, no free-variable node
  at all. **Binder names and binder info are not stored** ("presentation metadata", `expr.rs:20`), `nondep`
  of `let` is stored but unused by the checker.
* `mask`: bit `i` (i < 63) set iff loose bvar `i` may occur; bit 63 (`FAR`) set iff some loose index >= 63
  occurs. Computed bottom-up: `app = f|a`; binder body `under_binder(m, loose) = (m >> 1) | (loose > 64 ? FAR : 0)`
  (`expr.rs:168-184,211-271`). It is an over-approximation only around the FAR boundary.
* Export expressions are **appended, not hash-consed**: `Parser::push_expr` calls `Interner::push`
  (`frontend/parser.rs:684-687`, `term/intern.rs:48-52`). The exporter already deduplicates. Only thread-local
  expressions built during checking are interned (`context.rs:375-379`). Names and levels are interned and a
  duplicate in the export is a rejection (`parser.rs:665-681`).
* Levels: `Zero | Succ(l) | Max(l,r) | IMax(l,r) | Param(name)`, interned, compared by id first. `simplify`
  memoised per level id for the whole thread; `leq` memoised per `(l,r)`; `subst_level` memoised per
  `(level, params, args)`; level-list substitution memoised per `(list, params, args)` (`term/level.rs:31-123,207-219`).
  The `leq` algorithm is nanoda's (`leq_core` with `leq_imax_by_cases`), unchanged.
* Declarations: `Axiom|Quot|Theorem|Definition{hint}|Opaque|Inductive|Constructor|Recursor`
  (`checker/env.rs:57-80`). Lookup is `decl_of[name_index] -> declaration index`, visible iff
  `index < cutoff` where cutoff = the index of the declaration being checked (`env.rs:264-276`). This is the
  whole ordering mechanism: a declaration can only see earlier declarations, whichever thread checks it.

### 2.3 Export parser (`frontend/parser.rs`)

* Input: `--stdin` with stdin redirected from the file. A reader thread reads 4 MiB chunks, cuts at the last
  newline and hands blocks through a bounded channel of 2, recycling buffers (`:271-327`). Streaming replaced
  mmap of the whole file "to reduce peak memory" (`84023dd`); the path-argument mode still mmaps
  (`src/config.rs:122-130`). File length (fstat on stdin) presizes `exprs` and the index tables at `len/48`
  entries (`:642-663`).
* One parser thread, line at a time, no intermediate JSON tree. `fast_line` (`:790-813`) tests `s[pos+2]=='a'`
  and matches the most common line by literal bytes; everything else dispatches on bytes 2..3 in `fast_body`
  (`:834-1053`). Exact shapes matched (keys are alphabetical, no spaces; any mismatch falls back to
  `serde_json` for that line, so the accepted language is unchanged):

```
{"app":{"arg":A,"fn":F},"ie":I}                        {"bvar":K,"ie":I}
{"const":{"name":N,"us":[L,..]},"ie":I}                {"forallE":{"binderInfo":"..","body":B,"name":N,"type":T},"ie":I}
{"ie":I,"lam":{"binderInfo":"..","body":B,"name":N,"type":T}}
{"ie":I,"letE":{"body":B,"name":N,"nondep":true|false,"type":T,"value":V}}
{"ie":I,"natVal":"digits"}   {"ie":I,"proj":{"idx":K,"struct":S,"typeName":N}}   {"ie":I,"sort":L}   {"ie":I,"strVal":".."}
{"il":I,"imax":[A,B]}  {"il":I,"max":[A,B]}  {"il":I,"param":N}  {"il":I,"succ":L}
{"in":I,"num":{"i":K,"pre":P}}   {"in":I,"str":{"pre":P,"str":".."}}
{"def":{"all":[..],"hints":"abbrev"|"opaque"|{"regular":H},"levelParams":[..],"name":N,"safety":"safe","type":T,"value":V}}
{"thm":{"all":[..],"levelParams":[..],"name":N,"type":T,"value":V}}
```
  Slow path (serde): `meta`, `axiom`, `opaque`, `quot`, `inductive`, strings containing a backslash, unsafe/partial
  definitions (declined), `mdata` (declined).
* Integers: SWAR, 8 digits per step (`:350-371,422-449`):
  `x = le64(p) ^ 0x3030303030303030; nd = ((x + 0x7676767676767676) | x) & 0x8080808080808080;
  run = nd==0 ? 8 : ctz(nd)/8; v = x << ((8-run)*8); t = v*10 + (v>>8);
  val = (((t & 0x000000FF000000FF) * 0x000F424000000064) + (((t>>16) & 0x000000FF000000FF) * 0x0000271000000001)) >> 32`.
  Newline search is SWAR too (`:373-395`).
* Export indices may be sparse or out of order: `exprs_by_idx: Vec<Option<ExprId>>` translates (dropped after
  parsing). A reference to an undefined index is a rejection.
* Per `app` line the work is: 3 integer parses, 2 table reads, `loose = max`, `mask = or`, one 32-byte append.
* Cost share: "Parsing was 13 % of the bitvec run against 4 % in sokonanoda, whose parser this mirrors"
  (nanoclo `97f5cb9`). Not parallel, not lazy: every expression including all proofs is materialised.

### 2.4 Value, spine, environment, context stores (`checker/value.rs`)

One `Vals` per worker thread, cleared per session. Everything is an index.

```
VNode (40 bytes, :241-255)
  kind: u8   Sort|NatLit|StrLit|BVar|Axiom|Ctor|Recursor|QuotConst|Inductive|Unfold|Lam|Pi   (Dummy at index 0)
  flags: u8  SPINE_PROJ=1 (some projection in spine) | HAS_BVAR=2 | HEAD_ACTIVE=4 | IS_APP=8 | IS_PROJ=16
  loose: u16 closure: loose-bvar count of the body;  proj node: field index
  a,b,c,d: u32   payload (below)
  step: u32  memo: one delta/iota step ("= self" means stuck); for Lam: the evaluated binder type
  whnf: u32  memo: weak head normal form
  ty:   u32  memo: type (a value)
  key:  u64  lazily computed structural digest; bit 63 = present, bit 0 = "closed" (no BVar, no infer closure)

payload by kind
  Sort: a=level        NatLit: a=bignum id        StrLit: a=string id
  Lam:  a=env, b=binder type ExprId (unevaluated), c=body ExprId
  Pi:   a=domain value, b=env, c=body ExprId, d=ctx+1 (0 = plain eval closure, else an INFER closure, 2.8)
  head: BVar: a=de Bruijn LEVEL, b=type value;   constants: a=name, b=level-list id
  application node (IS_APP):  a=function value (the spine prefix), b=argument value, c=head node, d=spine length,
                              kind copied from the head, flags inherited
  projection node (IS_PROJ):  a=struct value (prefix), b=structure name, c=head, d=length, loose=field index
```

* A neutral `h a1 a2 a3` is the left-nested chain `((h a1) a2) a3`; **the value id is the spine id**. Every
  prefix is itself a value and is shared. Head kind, head pointer and length are O(1) from any node.
* Hash-consing: applications in a dedicated table `apps: (f,a) -> id` (`:1038-1065`), everything else in one table
  keyed on the packed fields (`:979-994`). A fresh variable is `BVar(level, type)`: interned, so opening the same
  binder twice at the same depth with the same type yields the same value and everything built on it coincides.
* `Unfold` = a constant that has a body (Definition or Theorem), kept folded: smalltt's "glued" value. Its
  head node memoises the evaluated body in `step`; every application node memoises its own unfolding in `step`.
* Closure = `(env: E, ctx: Option<C>, body: ExprId, loose: u16)`; lives inside Lam/Pi nodes, not a separate object.
* Environments (`EnvNode`, 24 B, `:320-328`): `Nil(lsub)` | `Cons{v, parent}` (interned on `(parent, v)`) |
  `Framed{mask, start, cut, rest}` = a pruned frame: for indices `0..cut` the present ones (bits of `mask`) are
  stored densely in a side array `env_slots[start..]`; lookup of index `i < cut` is
  `slot[start + popcount(mask & ((1<<i)-1))]` (`:858-890`). No jump pointers: a `Cons` chain is walked linearly.
* **Universe instantiation is part of the environment**: the `Nil` node carries an optional `lsub` id naming
  `(params, args)`. `eval` of `Sort`/`Const` applies the substitution on the fly (memoised). Definition bodies,
  types and recursor rules are therefore *never copied per universe instantiation*
  (`eval/environment.rs:64-72`, `eval.rs:151-171`).
* Typing contexts (`CtxNode{ty, parent}`) are a plain linked list, not interned; used only by inference.
* Digest (`:687-795`): 64-bit structural hash through closures (body id + the first `loose-1` environment slots),
  used only for the cross-session WHNF store.

### 2.5 Evaluation (`checker/eval.rs`)

```
eval(env, e):                                   # :29-70
  Var i          -> env_lookup(env, i)          # no cache, no forcing (values are never thunks)
  loose(e) == 0  -> lsub(env) is None ? closed_eval_cache[e] : closed_lsub_eval_cache[(e, lsub)]
                    (computed under the bare Nil(lsub) environment)
  open App|Proj|Let|Pi|Lambda -> te = prune_env(env, mask(e)); open_eval_cache[(te, e)]   (computed under te)

eval_no_cache(env, e):                          # :72-207
  Sort l            -> Sort(simplify(subst(l)))
  Const c ls        -> const_head_value_cache[(c, subst(ls))]: Unfold head | Ctor | Recursor | Quot | Inductive | Axiom
                       (Opaque and unknown constants are Axiom heads)
  Lambda t b        -> Lam{env = prune_env(env, mask(e)), binder type t unevaluated, body b}       # O(1)
  Pi t b            -> Pi{domain = eval(env,t), closure(prune_env(env, mask(e)), b)}
  Let x := v; b ... -> env' = env + eval(env, v) for each nested let; eval(env', body)             # zeta = bind
  Proj S i s        -> do_proj(S, i, eval(env, s))                                                 # forces WHNF of s!
  App               -> peel the spine (16 inline args); f = eval(head); eval_spine(f, args)
     special case: argument is itself an application, e.g. succ (succ (.. x)) -> iterative loop, no recursion

eval_spine(f, args):                            # :219-257
  f is a bare Recursor/Quot head: evaluate all args; if the major is ALREADY a constructor / Nat / String
       literal -> fire the rule now (fire_saturated :259-301); else neutral
  f is Lam: extend the closure env with as many evaluated args as there are nested lambdas, eval the body once
  else: apply(f, a) = existing application node if (f,a) is interned and inert, else
        Lam -> eval(body, env+a) | Nat.succ on a literal -> literal | neutral application node
```

Consequences: beta, zeta and projection-of-constructor are done at evaluation time; **delta and iota are lazy**
(except iota on syntactic constructor majors); arguments are evaluated strictly but an argument that is a
constant application costs one interned node. There is no separate `whnf_core`.

`prune_env(env, mask)` (`eval/environment.rs:75-214`) returns an interned environment that agrees with `env`
on the indices in `mask` and has nothing else: `mask==0` -> bare `Nil(lsub)`; already minimal -> itself; else a
2^14-entry direct-mapped cache on `(env, mask)`; else build a `Framed` node from the first 63 positions and,
if `FAR` is set, keep the untouched tail as `rest`. Frames are interned by content.

### 2.6 Reduction (`eval/reduction.rs`)

* **Delta** `unfold_value` (`:373-441`): memo in `step`; evaluate the constant's body once per `(name, levels)`
  per session (`unfold_const_cache`), then apply it to the already evaluated spine in runs (`apply_many`
  extends the closure env by k arguments for k nested lambdas, `eval.rs:427-450`), interleaving projections.
* **WHNF** `whnf_head`/`force_all` (`eval.rs:579-619`, `reduction.rs:140-216`): loop {Unfold -> unfold;
  Recursor/Quot -> iota step} with the result memoised in `whnf`. `force_reducible` keeps an explicit stack
  of recursors waiting for their major premise instead of recursing.
* **Iota** `fire_recursor` (`:520-558`): order is (1) for K recursors, K *before* touching the major
  (`k_pre_reduce`: needs only the major's type); (2) WHNF of the major; (3) Nat literal major on `Nat.rec`:
  direct `zero`/`succ (n-1)` step without building constructors; (4) literal -> constructor; (5) K again;
  (6) structure eta: neutral major of a structure-like type becomes `mk params (major.0) .. (major.n)`;
  (7) pick the rule by constructor name, take its RHS value from `rec_rule_cache[(rule, levels)]` (evaluated
  once), apply to params+motives+minors, then the constructor fields, then the extra arguments.
* **K** (`try_k_reduce :639-671`): major must be neutral; build `ctor params` from the major's *type*, infer its
  type and require `conv(type(major), type(ctor params))` (run outside any probe budget).
* **Projection** `do_proj` (`:33-70`): takes the WHNF of the structure immediately; constructor -> field;
  literal -> constructor first; otherwise appends a `Proj` elimination to the *reduced* neutral. This is eager
  where the official kernel is lazy (`whnf_core` with cheap projections, lazy-delta on `s.i =?= t.i`).
* **Quot** `fire_quot` (`:720-754`): `Quot.lift` (major at 5) / `Quot.ind` (major at 4) on `Quot.mk _ _ a`.
* **Nat literals** (`:373-402,756-802`, `eval.rs:621-635`): `Nat.add/sub/mul/pow/div/mod/gcd/beq/ble/land/lor/xor/
  shiftLeft/shiftRight`, `Nat.succ`, plus `Nat.div.go`/`Nat.modCore.go` are recognised by name. They stay folded
  `Unfold` neutrals; when *unfolded* the checker first tries GMP-style arithmetic if both arguments reduce to
  literals. Two guards: (a) "symbolic" mode - when either side of the current comparison has a free variable,
  native arithmetic is not attempted and the unfolding is memoised in a separate table; (b) "defer" - for
  `add/sub/mul/pow` whose second argument is a literal wider than 8 bits, structural unfolding is refused unless
  the caller demands it (`force`), which avoids unary recursion against a big literal.
* **String literals**: converted to `String.ofList [Char.ofNat n, ..]` and evaluated only when a projection or
  recursor needs a constructor (`:681-686`).
* No compiled forms for matchers / `casesOn` / `brecOn` / well-founded recursion: they are ordinary `Unfold`
  constants. No `eagerReduce` gadget, no `reduceBool`/`reduceNat` (they are opaque constants, never reduce).

### 2.7 Definitional equality (`checker/conv.rs`) - exact order

Entry points: `def_eq_at(x,y) = proof_irrel(x,y) || unify(x,y)`; `conv_types_at(a,b) = a==b || unify(a,b)`; both
run "unbudgeted" (they suspend any enclosing probe, `:40-59`).

```
unify(x, y):
 0. x == y (ids)                                               -> true
 1. if either kind is Pi|Lam|Unfold|Recursor|Quot: pair caches, key = sorted (x,y)
      conv_cache_pos hit -> true;  conv_cache_neg hit (not if a side is Lam) -> false
      inside a probe: conv_cache_neg_probe hit -> mark probe exhausted, false
 2. inside a probe: budget==0 -> exhausted, false; else budget -= 1
 3. conv_nat: both zero -> true; both have a predecessor (succ n | literal>0) -> unify(pred, pred)
 4. same-shape cases (unify_direct :154-450)
    Sort/Sort           level antisymmetry (leq both ways, cached)
    lit/lit             id equality
    BVar/BVar           same level -> spines
    Ctor/Ctor, Inductive/Inductive, Axiom/Axiom   same name, levels equal -> spines with relevance signature
    Recursor|Quot pair  if same head: PROBE spines -> true;  proof_irrel;  one iota step on each side, recurse
                        if either moved;  else same head -> spines;  else false
    Pi/Pi               fast path: same body ExprId && same domain id && same ctx && envs equal -> true
                        else unify domains; fresh = BVar(depth, domain); apply both closures; unify at depth+1
    Lam/Lam             same, with the binder types evaluated lazily (memo in `step`)
    Unfold/Unfold       same head: PROBE spines -> true;  proof_irrel;  then
                          same head            -> unfold both, recurse
                          hint(x) < hint(y)    -> unfold y; if stuck unfold x; if stuck force-unfold y
                          hint(y) < hint(x)    -> symmetric;      equal hints -> unfold both
                        (Abbrev highest, then Regular(h) by height, Theorem/Opaque lowest: env.rs:27-34)
    Unfold/other        proof_irrel; unfold the Unfold side (then forced); recurse
    Recursor|Quot/other proof_irrel; iota on it; else iota/unfold the other side; else false
 5. otherwise (unify_cold :690-715): proof_irrel; eta (Lam vs non-Lam: apply both to a fresh variable);
    structure eta: if a side's type is structure-like: types must unify, then unit-like -> true,
    else compare `x.i` with the constructor fields of y (both directions)
 6. record the result in pos / neg (or neg_probe if the probe was truncated)
```

* **Spine comparison** (`unify_spine :614-661`) recurses to the common prefix first (id-equal prefixes stop
  it), then left to right. Arguments at *ignorable* positions are skipped.
* **Relevance signature** `Sig` per `(constant, levels)` (`relevance.rs:13-62,103-218`), computed once from the
  constant's type telescope (up to 64 arguments): `prop_arg` (argument i is a proof, i.e. its type's sort is 0
  under the level instantiation), `absent_arg` (for definitions: binder i is used neither in the value's body
  nor in the rest of the type), `prop_result`/`result_known` (after k arguments the result is / is not a proof).
  Uses: skip proof and absent arguments in spines; `statically_not_proof(v)` skips the whole proof-irrelevance
  test (which needs a type) when the head says the result cannot be a proof.
* **Proof irrelevance** (`:749-799`): both neutral, neither statically a non-proof, `type(x)` is a Prop,
  `type(y)` is a Prop, `conv(type x, type y)`. Under lambdas: unify domains, apply both to a fresh variable,
  recurse. Positive only (nanoda has a 3-valued version: proofs of non-convertible props are unequal).
  "Is a Prop" = `level_of_type(t)` is syntactically always zero; `level_of_type` reads the result sort of the
  head constant's telescope (`const_result_level`, cached) and only falls back to computing a type.
* **Probe = bounded speculation** (`:501-535`, `PROBE_CAP = 2048`): for two applications of the same constant
  (or recursor) each pair of relevant arguments is compared with a fresh budget of 2048 `unify` steps. All
  pairs equal within budget -> equal. A pair definitely unequal -> stop probing and unfold. Budget exhausted ->
  undecided, unfold. Negatives found by a truncated probe go to a quarantine set that is consulted only inside
  probes and cleared when a probe is exhausted; inside a probe "forced" unfolding is disabled. Anything that
  feeds a memoised *reduction* (K check, structure eta, proof irrelevance, inference) runs unbudgeted so that
  memo slots never contain budget-dependent answers.
* smalltt ideas actually present: glued neutrals with lazy unfolding; spine-first comparison with backtracking
  to unfolding; de Bruijn levels for fresh variables. smalltt's three unfolding modes survive only as a dead
  `const RIGID: bool` parameter that is `true` at every entry point; the budgeted probe replaced them.
  Not from smalltt: hash-consing of values, pair caches, pruning, signatures.
* Differences from the official kernel that are deliberate: no syntactic `whnf_core` phase; projections are
  eager; lazy delta compares spines *under a budget* before unfolding; lambdas compare binder types; proof and
  unused arguments are never compared; no `Bool.true` eager-evaluation special case; no union-find.

Checklist of what is *not* computed (each verified in the code above, effect sizes not published):
id equality at every entry; pair caches; Pi/Lam fast path without going under the binder; shared spine
prefixes; proof and unused arguments; the proof-irrelevance type when the head rules it out; unfolding when
the probe settles it; binder types of lambdas until two lambdas are compared; the major premise for K
recursors; unary recursion against big literals; intermediate function types in application spines;
non-dependent arguments in `InferOnly`; codomains of lambdas in `InferOnly`; re-checking closed export
subterms; universe-parameter checks of a leaf more than once per declaration.

### 2.8 Type inference (`checker/infer.rs`)

`infer_value(flag, depth, env, ctx, e) -> V`. `env` maps bvars to values (fresh `BVar` for binders, the
*value* for lets); `ctx` maps bvars to type values. Result is a value; nothing is quoted.

```
Var i     -> ctx_lookup(ctx, i)
Sort l    -> (Check: every Param of l is declared; memo per (expr, declaration) in a 4096-slot stamp table) Sort(l+1)
Const     -> (Check: same for its levels) const_head_type_cache[(name, levels)] = eval(type under lsub)
literals  -> Nat / String
App|Lam|Pi|Let|Proj:
   key = (prune_env(env, mask(e)), e);  type_cache hit if (flag == InferOnly || entry.checked_under == current uparams)
   if Check && e is closed && e is an export expression && (e, uparams) in checked_closed: downgrade to InferOnly
   Lam t b : dom = eval(t);  Check: t infers to a sort; infer b under env+BVar(depth,dom), ctx+dom (result kept
             only in type_cache, apart from one sharing special case for `A -> A`)
             result = Pi{dom, INFER closure(prune_env(env, mask(e)), ctx, b)}        # codomain not computed
   Pi t b  : l1 = sort of t; l2 = sort of b under a fresh variable; Sort(simplify(imax l1 l2))
   Let     : dom = eval(t); Check: t is a sort, conv(dom, infer v); env+eval(v), ctx+dom; infer body
   Proj    : infer s, WHNF, must be the named inductive, exactly one constructor, fully applied; walk the
             constructor telescope substituting params and earlier projections `s.j`; Prop structure: a
             dependent earlier field or the projected field must be a proof (rejects data out of Prop)
   App     : see below
   after a successful Check of a closed export expression: checked_closed += (e, uparams)
```

Applying an *infer closure* to a value `a` runs `infer_value(InferOnly, env+a, ctx+dom, body)` (`eval.rs:452-474`):
"substitution into the inferred type" is re-inference under an extended environment, memoised by `type_cache`.
When the body was just checked under the same fresh variable, that is a cache hit.

`infer_app_v` (`:270-337`): infer the head; then keep the function type as a pair `(tenv, T)` where `T` is a Pi
*expression*: for each argument, if `T = Pi dom body` syntactically: (Check: `conv(eval(tenv, dom), infer arg)`),
then `tenv += (body ignores its binder ? placeholder : eval(arg))`, `T = body` - no value is built for the
intermediate type. Only when `T` is not a syntactic Pi is it evaluated and forced to a Pi value. The final type is
`eval(tenv, T)`. The placeholder (one shared dummy value) for non-dependent binders both skips the argument's
evaluation in `InferOnly` mode and makes environments coincide.

What is skipped and why it is sound: `InferOnly` skips argument/let conversion checks and all binder-type
checks; it is used (a) for terms the checker itself built or already checked, (b) for closed export terms found
in `checked_closed`, which is keyed by the universe-parameter list and is only populated by declarations
checked in export order (`ordered_declaration`, `tc.rs:64`), so everything visible then is visible now.

### 2.9 Caches (all per worker thread; `checker/cache.rs:99-131`, `context.rs:184-202`)

| cache | key -> value | lifetime |
|---|---|---|
| value interning (`apps`, `table`, `env_table`, `frames`) | structure -> id | session |
| node memo slots `step`, `whnf`, `ty`, `key` | value id | session |
| `closed_eval_cache` / `closed_lsub_eval_cache` | `ExprId` / `(ExprId, lsub)` -> V | session |
| `open_eval_cache` | `(pruned E, ExprId)` -> V | session |
| `type_cache` | `(pruned E, ExprId)` -> (type V, checked-under scope) | session |
| `conv_cache_pos`, `conv_cache_neg`, `conv_cache_neg_probe` | sorted `(V,V)` | session (probe set: one probe) |
| `unfold_const_cache`, `const_head_type_cache`, `const_head_value_cache`, `const_result_level_cache`, `rec_rule_cache` | `(name, levels)` or `(rule ExprId, levels)` | session |
| `prune_dm` | direct-mapped 2^14 x `(mask, E) -> E` | session |
| `quote_cache`, `global_value_cache` | `(V, depth)` | session |
| `struct_eta_cache`, `arithmetic_delta_cache`, `level_subs` | `(V, name)`, `V`, `(ks, vs)` | session |
| `whnf_store` (+ two 64 Kbit filters + 4 M-entry admission counters) | digest -> (128-bit key, source ExprId, result ExprId) | thread |
| `checked_closed` | `(ExprId, uparams)` | thread |
| `sig_cache`, `sig_templates` | `(name, levels)`, `name` | thread |
| level caches (`simplify`, `leq`, `subst_level`, `subst_levels`) | level ids | thread |
| `leaf_checked` | 4096 slots `(ExprId, declaration stamp)` | declaration |

* **Session** (`tc.rs:83-118`): after each declaration, if the value store holds more than `SESSION_BUDGET = 9 MiB`
  (nodes x 40 + envs x 24 + ...), every session cache is reset; otherwise the next declaration starts with all of
  them warm. If the arena's capacity grew beyond 36 MiB it is freed instead of reused.
* **WHNF store** (`eval/whnf_cache.rs`): on the first WHNF of a value in a session that took >= 1 step and is
  closed: bump an admission counter; on the third sighting quote source and result to (interned, thread-local)
  expressions and store them. Lookup: head filter bit -> closed? -> digest filter bit -> table -> 128-bit key equal ->
  **`quote(v) == stored source ExprId`** (exact structural check: a digest collision can only cost a miss)
  -> `eval(result)`. An earlier shortcut that accepted two recursor applications as convertible when their
  64-bit structural hashes were equal was removed as unsound (`70bd739`).
* `PtrMap` (`checker/ptrmap.rs`): open addressing, linear probing, a separate 1-byte tag array (7 hash bits |
  0x80, 0 = vacant), Fibonacci hashing of 1-3 packed `u64` words, 75 % load, no deletion. `find` returns the
  vacant slot so the following insert does not probe again (guarded by an epoch counter). `reset` = fill the
  tag bytes; `reset_shrink` reallocates when the table is 4x larger than needed, so small declarations keep
  small tables.

### 2.10 Declaration admission

* Definitions/theorems/opaques (`infer.rs:425-450`): no duplicate universe params; type infers to a sort (Prop for
  theorems); `infer(Check, value)`; `def_eq(inferred, eval(type))`. Axioms: type only. All theorem proofs are
  fully checked.
* Inductives (`inductive/mod.rs:26-156`), per mutual block, against only the declarations before the block:
  check the types, uniform parameter occurrences, constructor types, nested occurrences (specialised to
  auxiliary types as the C++ kernel does), positivity, universe constraints; **generate** large-elimination level,
  motives, minors, recursor types and rules, K flag; type-check the generated recursors and that each rule's
  LHS and RHS have convertible types (`recursor.rs:326-369`); for nested blocks translate the auxiliary recursors
  back. The derived declarations replace the block. The parser does not even read `recs`, `numIndices`, `isRec`
  or constructor `numFields` (`parser.rs:253-259,1353-1416`).
* Quot: only the name is read; `Quot`, `Quot.mk`, `Quot.lift`, `Quot.ind` types are constructed by the checker
  after verifying `Eq`/`Eq.refl` have the expected shape (`quot.rs:48-111,114-293`).
* Axioms: arena runs with `--axiom-allow-all`; default policy allows `propext`, `Classical.choice`, `Quot.sound`
  and warns otherwise (`config.rs:13,49-65`). Unsafe/partial declarations: decline (exit 2).
* A loose bound variable in a declaration is not rejected but panics ("internal error", exit 3;
  arena `tests/bugs/loose-bvar-value.yaml`).

### 2.11 Parallelism, memory, build

* Workers: `--threads 4`; chunk = 64 consecutive declarations per atomic `fetch_add` (`tc.rs:13,134-167`); 2 GiB
  thread stacks, plain recursion (`lib.rs:15`). No shared mutable state except the counter and the reporter.
  Verdict is deterministic (all declarations must pass); which failure is reported first is not.
* Each worker owns: a `TcCtx` (thread-local `Terms` for expressions/levels/names it creates, level caches,
  signatures, `checked_closed`) and a `TcCache` (sessions). Nothing learned by one worker is visible to another.
* Memory: 32 B per export expression + 9-36 MiB of session arena per worker + 4 MiB admission table per worker.
  Peak 5.0 GB on the arena. No lazy loading; the streaming reader exists to keep the input file out of RSS.
* Build: `opt-level=3`, `lto="fat"`, `codegen-units=1`, `overflow-checks=false`, mimalloc as global allocator,
  arena adds `-C target-cpu=native` (`Cargo.toml:10-14`, `main.rs:14-15`). No PGO in the arena build (commit
  `792a5f9` "ignore *.profdata" shows it was tried). nanobruijn measured PGO < 1 % and `target-cpu=native` as a
  regression on its code; mathgraph uses PGO only to make its Callgrind comparisons deterministic.

---

## 3. mathgraph (delta to sokonanoda@`28c03d0`, the pointer-based September representation)

Source delta is 150 lines in `src/eval.rs`, `src/infer.rs`, `src/util.rs`, `src/flash.rs`; the rest of the 75
commits is an experiment-tracking harness (`experiments/*.py`, workflows). Flags in `src/flash.rs`:

| change | where | measured (their manifests) |
|---|---|---|
| `DIRECT_VAR_EVAL`: test "is this a bare variable" before any cache logic in `eval` | `eval.rs:607` | mathlib 1.020x |
| `DIRECT_FRAMED_PRUNE`: pruning an already-`Framed` environment selects slots by mask arithmetic instead of walking | `eval.rs:197` | mathlib 1.041x (re-run), -1.40 % (first run); std -4.5 % |
| `RIGID_INDUCTIVE_NEUTRAL`: applying an inductive-type head goes straight to the app intern table | `eval.rs:740` | not recorded in repo **[unverified]** |
| `APP_HC_PREALLOC`: app intern table starts at 2^16 | `util.rs:1114` | not recorded **[unverified]** |
| `INFER_PI_DEMAND_BYPASS`: `infer_app` does not call `force_all` when the function type is already a Pi | `infer.rs:178` | not recorded **[unverified]** |
| 2 worker threads instead of 4 | arena yaml | not recorded **[unverified]** |
| `ORDINARY_UNFOLD_NEUTRAL` (same shortcut for folded constants) | disabled | 1.027x in isolation, not promoted |

Rejected with numbers: general memo for universe-level equality (mathlib 3.2 % slower); two variants of
"skip the app table on first sight" (+2.6 %, +1.2 %); continuing lazy delta on the same head without
re-entering `unify` (regressed std/cedar/mathlib). Census of the September evaluator (run on the manifest's
target, presumably mathlib): 284.6 M "simple apply" events = 16.9 % of `eval` entries (so ~1.7 G `eval` entries
in total **[derived]**), 61 % of them with a folded-constant function, 28 % with an inductive head; app intern
table hit rate 44 %; arguments already canonical 90 %. Sokonanoda later absorbed all of this differently
(October rewrite); nothing in mathgraph is missing from sokonanoda HEAD except the 2-thread choice.

---

## 4. nanoclo

Design (README; `src/closure.rs`, `nbe*.rs`, `tc.rs`, `fuse.rs`):

* **Inference on closures `(ExprPtr, EnvId)`, values only for conversion.** Environment entry =
  `Val(expr, env)` (delayed argument) | `Neu(local)` (opened binder, a nanoda `Local` expression carrying its
  type) | `V(value id)`. Types are closures; `infer_lambda` must *reify* the body type (substitute the
  environment into the expression, memoised per `(expr, env, offset)`) and abstract the opened binders to build
  a Pi expression (`tc.rs:693-731`). `infer_app` threads `(body, env + arg closure)` and first tries a syntactic
  equality-modulo-environment test `eq_mod` before evaluating both types (`tc.rs:771-835`, `closure.rs:893-1008`).
* **Interned environments** (`closure.rs:36-44,382-429`): node `{entry, parent, len, next_level, jump}`,
  interned on `(parent, entry)`; entries are normalised first (variable chains chased, closed terms drop their
  env) so the same binding reached two ways gives the same environment.
* **Skew-binary (Myers) jump pointers**: `jump(new) = (len(p)-len(jump(p)) == len(jump(p))-len(jump(jump(p)))) ?
  jump(jump(p)) : p`; lookup of index `i` descends by `jump` when it does not overshoot, else by `parent`:
  O(log i) (`closure.rs:416-423,583-607`). Measured: beta ladder n = 32000, 0.93 s -> 0.12 s (`1ec0690`).
* **Read sets** (`closure.rs:76-99,434-489`): per expression `Dense | Mask(u64) | Wide(<= 8 words) | Deep`.
  A cache key is `(expr, env projected on the read set)`; for thunks an interned *view* (the list of read
  entries) replaces rebuilding an environment (`:633-717`). Past 512 indices the whole environment is the key,
  because exact projection at every node of an n-binder ladder is quadratic (measured, `554586c`).
* Values (`nbe.rs:66-99`) are sokonanoda's August design, *with* thunks (`Thunk{env, expr, forced}`), interned
  per kind in separate hash maps; the first stuck head takes all remaining arguments in one step.
* Conversion = sokonanoda's, plus **iterative deepening of the probe**: budget 4096 steps for a comparison and
  everything nested; an aborted probe records its spine pair (never probed again) and grants the next probe on
  the unfolding chain twice the budget, capped at 2^20 (`nbe_conv.rs:380-420`). "One comparison in the FLT export
  needs ~10^7 steps; restarting it from zero every 4096 steps kept the checker on one theorem for 13 hours. With
  the deepening the theorem checks in 0.25 s" (`4cdd12f`).
* **Wrapper fusion** (`fuse.rs`): rewrite a definition's body once: unfold matchers, `casesOn`, `recOn`,
  `brecOn`, `binductionOn`, `_sparseCasesOn*` and small abbreviations (<= 512 / 256 nodes), contract the exposed
  beta redexes when no costly argument is duplicated, fire recursors on syntactic constructors, project out of
  constructors. Measured: magma-list-deep-n36 67.4 G -> 7.3 G cycles and 4.2 -> 0.7 GB; Init -22 %, Std -10 %
  instructions (`35c2a76`). **But always-on fusion doubled mathlib (177 -> 339 s)** because conversion often
  succeeds by comparing two *folded* matcher/instance applications. Final policy: check without fusion, count
  wrapper unfoldings, past 20 000 abort and re-check the declaration with fused bodies; 6 mathlib declarations
  cross the gate (`e1307e1`).
* Caches are reset per declaration (`tc.rs:91-92`); only the serial driver keeps the scratch DAG. Memo eviction
  was tried and removed: evicting the WHNF memo mid-declaration turned 2.1 s into > 300 s on a nested-beta
  family ("a 250x cliff on one family is not worth 6 GB on another", `006d5aa`); two-generation maps are kept
  only for memos that are safe to drop.
* Misc measured: rustc-hash 1.x low-bit weakness on `(parent<<32 | x)` keys cost 2.5x on one test, fixed by
  upgrading (`add955e`); reserving the expression table from the file size cut peak RSS 6.8 -> 6.0 GB (`62671ff`);
  K-before-WHNF turned a > 500 s declaration into 33 ms (`3a0964a`); merging two specialised traversals into one
  generic one cost 0.6-0.9 % instructions and was reverted (`faa32e9`).

Why it is 5.7x behind sokonanoda **[inferred]**: reification at every lambda, no sessions/cross-declaration
reuse in the parallel driver, thunks and per-kind `FxHashMap` interning, linear scans of `Vec<Value>` enums, and
none of sokonanoda's October work.

---

## 5. nanobruijn

Idea: keep nanoda's substitution-based algorithm but never substitute on binder entry. `ExprPtr = (core, shift:u16)`;
`(p,k)` means "p with all free indices raised by k"; closed terms carry a sentinel shift. Every compound node is
kept in Outermost-Shift Normal Form (minimum child shift 0), so terms differing by a uniform shift share a core.
Caches are bucketed by `depth - shift`; closed terms live in a bucket that is never evicted; leaving a binder
hides its bucket and re-entering a binder of the same type revives it (PLAN.md "Design").

Results (PLAN.md "Results", "Current performance"): in the same binary nanoda's algorithm 320 s vs nanobruijn 345 s
on mathlib (1.08x *slower*) and 1.14x slower on Init; app-lam N=4000 8.3 s -> 10 ms. Later, with canonical
binders, 9.95 T -> 7.88 T, and with upstream's `def_eq` failure memo 4.72 T / 4.53 T. Individual wins: speculative
application congruence using only O(1) "already known equal" tests before any WHNF, **-16.7 % on mathlib**;
keeping lazy shifts in `infer_app`, -21 %; a 2-way 64 K-entry `mk_app` cache right-sized to L2, 14 % on Init.

Why it did not pay off:
* Reduction still substitutes (`inst_beta`), and shifted variants of the same term miss the caches: "50 % more
  infer calls and 56 % more def_eq calls" than nanoda for 2 % more nodes.
* Pointer equality is only as good as the canonical form. Until shifts were extracted through binders, lazy delta
  failed to recognise an unfolded term as identical to the other side and explored exponentially: one con-leche
  lemma took 25.2 min vs 40 ms (def_eq calls 484 M vs 9 278). The fix needed an exact per-node free-variable
  bitset and a formalised uniqueness proof (`canon.rs`, `Theory.lean`).
* Lazy zeta/let in inference diverged or thrashed (0.05 % WHNF cache hit rate) because each variable occurrence
  produced a differently shifted copy.

Transferable negative results (PLAN.md "Paths not taken"; all measured by them): union-find equivalence cache
produced 0.7 % more hits than a plain pair set and cost more than it saved (and makes `def_eq` non-monotone in
time, the root of exploits in arena `bugs/proj-of-stuck-prop`); reusing hash maps across declarations *without*
shrinking was a 2x regression, with shrinking 8 % (cache pressure from large tables on small declarations);
jemalloc -45 % vs glibc on their allocation pattern; direct-mapped caches help only when they fit L1/L2 (4 K
entries +3 %, 64 K entries -2 %); per-node eager free-variable sets cost +7 % on Init (what survived is a 64-bit
mask with a rare out-of-line tail); wall-clock on mathlib is noise at the 4-5 % level, "instruction counts are the
honest metric".

---

## 6. nanoda_lib baseline (what everyone started from)

* Locally nameless: a run of binders is opened by one `inst` pass that replaces the bound variables with
  `Local` nodes named by de Bruijn level, and closed by one `abstr` pass (`src/tc.rs:658-686`); every open/close
  is a traversal of the body. Expressions hash-consed in an `IndexSet` per declaration plus one for the export;
  ids are 4-byte `(dag bit, index)`.
* `infer` with check / no-check caches keyed by expression; `whnf` and `whnf_no_unfolding` caches; `def_eq` is the
  C++ algorithm: quick check, `whnf_core` with cheap projections, `Bool.true` eager evaluation for closed terms,
  proof irrelevance (now 3-valued), lazy delta by reducibility height with same-head argument comparison and a
  congruence-failure cache, then eta, structure eta, string literals, unit eta (`src/tc.rs:990-1042,1312-1352`).
  Positive pair set (union-find removed in `05024bd`) and a per-declaration failure memo keyed
  `(x, y, eager_mode)` (`0928383`).
* A fresh `TcCache` and DAG per declaration; threads pull single declarations from an atomic counter
  (`tc.rs:150-185`). Parser: `read_line` + `serde_json::from_str` per line, every name/level/expression
  interned in an `IndexSet` (`src/parser.rs:393,609-690`).
* Admission: recursors, rules, `numFields`, K etc. are generated and compared with the exported ones; a string of
  fixes in 2026 (`1283899`, `f04456a`, `8a327a1`, `2dbe93c`, `14a85b7`, `c1155e8`, PR #22) each closed an arena
  `bugs/` test where an exported field was trusted or compared too weakly.

---

## 7. Ranked tricks and how to realise them on the JVM / Truffle

Ranking is by evidence of effect on mathlib-scale input. "JVM" notes assume the metric counts GC and JIT
instructions and that pointer chasing, megamorphic calls and boxing are the expensive things.

| # | trick | evidence | JVM / Truffle realisation |
|---|---|---|---|
| 1 | Evaluate to a hash-consed value graph with glued (lazily unfolded) constant applications; memo slots in the node | lineage 16 min -> 88 s; "+35 %" at introduction; gruszka converged on the same (doc 05) | Do **not** allocate a Java object per value. Use parallel primitive arrays (`byte[] kind/flags`, `int[] a,b,c,d,step,whnf,ty`, `long[] key`) or one `int[]` with stride 10, ids are `int`. No object headers, no GC tracing, no boxing; a session reset is `top = 1`. Interning tables are open-addressed `long[]`/`int[]` with a `byte[]` tag array. |
| 2 | Prune environments to the free-variable mask before using them as cache keys or capturing them | sokonanoda "sparse environments"; nanoclo -10 % and 131 K -> 12 K inference misses on one declaration; `prune_env_cold` is still 11.6 % self time, so the *implementation* matters | Precompute `mask: long` and `loose: short` per expression at parse. `Long.bitCount`/`numberOfTrailingZeros` are intrinsics. Frames as slices of one `int[]` slot array. Add a direct-mapped `(env, mask) -> env` cache. Consider nanoclo's jump pointers for un-pruned chains (ladders). |
| 3 | Types are values; lambda codomain = lazy "infer closure"; application telescopes walked syntactically; placeholder for non-dependent arguments | sokonanoda vs nanoclo (which reifies at every lambda) is the largest design difference between a 88 s and a 8.4 min checker | A Pi node with a mode field (eval vs infer + context id) - and that field must be part of identity (section 8). `infer` and `eval` are two interpreters over the same expression arrays; keep them as two switch loops rather than one polymorphic node hierarchy. |
| 4 | Cross-declaration reuse: sessions with a byte budget, `checked_closed`, persistent closed-WHNF store verified by exact read-back | nanoclo +47 % on BitVec without cross-declaration caches (single caches +8..38 %, `8106d4f`); gruszka uses 8 MiB sessions too | Sessions are natural with array arenas (reset indices, `Arrays.fill` on tag bytes, or epoch-stamped slots for O(1) clear). `checked_closed` can be a bitset indexed by export expression id per universe-parameter list (most declarations have few distinct lists). Allocate the arrays once per thread and reuse them, so the collector never sees per-session garbage; keep the budget near L2/L3 size. |
| 5 | Relevance signatures: never compare proof arguments or unused arguments; decide "cannot be a proof" from the head | commit "Much better algorithm for proof irrelevance in spines" (`4f0bc57`); arena perf tests `irrelevance-*` | Two `long` bitmasks per `(constant, levels)`; compute once per constant as a template over level parameters. Pure integer work. |
| 6 | Budgeted speculative argument comparison before unfolding a shared head, with quarantined negatives and unbudgeted type-level checks; iterative deepening | nanoclo: 3076 M -> 56 M on `discarded-argument-match`, cost 0.04-0.47 % on corpora; FLT 13 h -> 0.25 s | A fuel counter in the conversion state; exceptions are not needed (return a tri-state). Make sure nothing written to a memo slot depends on fuel. |
| 7 | Universe substitution carried by the environment instead of instantiating expressions | no measurement; removes `subst_expr_levels` copying that nanoda memoises per `(expr, ks, vs)` | One `int lsub` in the environment root; `Sort`/`Const` evaluation looks up a small memo `(levelsId, lsub) -> levelsId`. |
| 8 | Parser: exact-byte fast path, SWAR integers, append-only expression store, no binder names/info, streaming input | parse 13 % -> 4 % of a run (nanoclo) | Read `byte[]` blocks (or a `MemorySegment`), `VarHandle` little-endian `long` reads for SWAR. The loop is small and hot: it must be compiled early (or AOT). Write straight into the primitive expression arrays. Reject loose bvars in declarations here: `loose(type)==0 && loose(value)==0` is O(1). |
| 9 | Derive, do not trust: ignore exported recursors, rules, `numFields`, `isRec`, `k`, Quot types | should close arena `bugs/{nat-rec-rules,unit-rec-rule,orphan-rec,ctor-num-fields,rec-k-lie,nat-rec-k-lie,rec-rule-proof-irrel*,rec-type-proof-irrel,nested-rec-rule-nfields}` and `other/{extra-rec,orphan-ctor}` by construction **[inferred from the test descriptions; I ran nothing]** | Language independent. Cost is one serial pass over inductive blocks. |
| 10 | Strict arguments, no thunks; laziness only at constant granularity; eager iota only on syntactic constructor majors | thunks deleted after measurement (`9b4ea12`, `1fd5693`); nanoclo kept thunks and needed read-set views to make them share | Fewer node kinds, no forcing checks on every variable read (28.8 % of eval entries are variable reads). If RLVM wants uniqueness/in-place reuse, note that hash-consing and in-place update are mutually exclusive for the same node. |
| 11 | K before reducing the major; structure eta on the major; rule RHS evaluated once per `(rule, levels)`; Nat-literal `Nat.rec` step without constructors | nanoclo: > 500 s -> 33 ms on one Std declaration | Straightforward. |
| 12 | Literal arithmetic that waits: stay folded, native only on closed operands, refuse structural unfolding against big literals unless demanded | arena perf tests `unfold-before-literal*`, `let-fold`; sokonanoda `869f4b6` | `BigInteger` only at the leaves; small literals as tagged ints inside the value id space to avoid boxing. |
| 13 | Compact purpose-built hash tables; one probe per construction; tables sized to the session | nanoda 4-byte ids -12 %; nanoclo one-probe interning -7 %, presizing -11 % on small tests; nanobruijn L2-sized caches | No `HashMap<Long,Integer>`: boxing and megamorphic `equals/hashCode` would dominate. Open addressing over primitive arrays, Fibonacci hash, tag bytes. |
| 14 | Wrapper fusion of matchers/`casesOn`/`brecOn` into definition bodies, gated | nanoclo 9x on evaluation-heavy tests, 2x *worse* on mathlib when always on | This is where Truffle specialisation/JIT belongs: only for declarations that cross an unfolding-count threshold, and only for the evaluator; conversion must still see folded applications first. |
| 15 | Few worker threads; contiguous chunks so sessions stay warm | mathgraph's 2 threads; per-thread caches everywhere | One checking thread unless wall-clock limits force more. Chunking by 64 keeps consecutive (related) declarations in one session. |

Expression storage on the JVM follows from #1 and #8: the export has on the order of 10^8 expression nodes
(nanoclo: 67 M table entries for its mathlib; nanobruijn: 60.9-87.4 M cores; doc 05 quotes 105 M for a newer
export). One Truffle node object per export expression is out of the question (>= 3-5 GB of traced objects).
Keep the export in primitive arrays (about 24-32 B per node as in sokonanoda) and interpret it with a switch
loop; materialise Truffle ASTs only for the bodies of constants that prove hot.

---

## 8. Soundness-sensitive and fragile points (do not copy blindly)

Bugs that actually shipped in this lineage, each now an arena test:

| defect | test | lesson |
|---|---|---|
| Two Pi closures with the same body, domain and environment were equal although one *evaluates* the body and the other *infers its type* | `bugs/pi-closure-context` (fix `918dcb1`) | closure mode/context is part of value identity, of the interning key and of every fast path |
| Two empty environments with different universe substitutions were equal | `bugs/closure-level-subst` (fix `fc50c18`) | anything carried by the environment root (level substitution) is part of environment identity |
| Unit-like eta returned true as soon as one side had a unit-like type, so a forged `PUnit.rec` rule compared equal to the derived one | `bugs/unit-rec-rule` (sokonanoda `fbcf100`; mechanism spelled out in nanoclo `ea32c35`) | compare the two types first |
| K-like reduction created its fresh variables at the wrong depth (`d245a52` "k-rec bug: thread the proper depth") | `bugs/k-rec-conv`, "regression test for sokonanoda" **[link between commit and test inferred]** | depth must be threaded through every reduction that opens binders |
| Equal 64-bit structural hashes of two recursor applications were accepted as convertible | removed in `70bd739` | a hash is never an equality proof; verify structurally |
| Probe-truncated "not equal" answers reached a memo used by reduction | nanoclo `a2aa37e` (mathlib declaration wrongly rejected) | budgeted results never enter memo slots |
| Trusting exported `numFields`, `k`, recursor rules, orphan recursors, Quot types, temporary nested names | nanoda fixes listed in section 6 | derive everything |

Design choices that are sound only under stated invariants:

1. **`type_cache` keyed by `(pruned env, expr)` without the typing context.** Sound because a fresh variable is
   `BVar(level, type-value)`, so the environment determines the context. A checker whose variables do not carry
   their types needs the context in the key (`bugs/open-term-cache-context`).
2. **Session-lifetime positive *and negative* conversion caches, and `checked_closed`.** Require that each thread
   checks declarations in increasing export order and that a constant's status never changes while a cache
   lives. Sokonanoda clears everything around inductive checking, where temporary declarations exist.
3. **Skipping "absent" arguments** relies on the definition's value and type really not mentioning the binder;
   it is computed from the free-variable masks, which over-approximate use (safe direction).
4. **Structure eta on a recursor's major premise is applied to every structure-like inductive, including
   propositions** (`reduction.rs:595-637` has no Prop test). The official kernel refuses it for Prop; the arena
   marks And-like cases "either" and says it "must not be done for propositions that eliminate only into
   Prop, like Exists" (`corner-cases/eta-struct-major-and`). nanoclo restricts it to large-eliminating structures
   (`b8cefd0`). RLVM should follow nanoclo's rule.
5. **Proof irrelevance decides "is a proof" from `level_of_type`**, which for a constant-headed type reads the
   result sort of the head's telescope *computed once under generic variables* (`const_result_level`,
   `eval.rs:333-358`) and does not look at the actual arguments. The exploits in `bugs/rec-of-subst-prop` and
   `bugs/proj-of-stuck-prop` are built on a result sort that reduces to `Prop` for a variable and is stuck for a
   closed argument; the official kernel was tightened to require the inferred type to reduce to a sort.
   Mirror the official rule. **[sokonanoda's verdict on these two tests was not checked]**
6. **Unchecked bodies are unfolded.** Inductives are checked before any definition, and workers unfold
   definitions that another worker has not checked yet. The verdict is still right (everything must pass) but an
   ill-typed body can crash the evaluator first: sokonanoda exits 3 instead of 1 on a loose bvar, and it has no
   step limit, so a diverging ill-typed body would run into the timeout **[divergence case inferred]**. RLVM
   needs evaluator robustness (loose-bvar check at parse, step/stack limits that degrade to "retry after
   dependencies are checked" or to rejection).
7. **Eager projection** forces the WHNF of every projected structure at evaluation time and compares stuck
   projections on reduced structures. Arena perf tests `proj-lazy-struct`, `proj-app-lazy-delta`,
   `proj-stuck-struct` are written against exactly this; I could not check how sokonanoda fares on them
   **[unverified]**.
8. Transitive equivalence caches (union-find) make `def_eq` answers depend on history; both nanoda and
   nanobruijn removed them. Plain pair sets only.
9. Nested inductives: the official kernel detects nested occurrences syntactically; normalising first changes
   the number of motives (`corner-cases/nested-redex-aux`, sokonanoda issue 12).
10. Memo eviction inside a declaration can turn linear work exponential (nanoclo `006d5aa`). Bound memory by
    session resets *between* declarations, not by evicting during one.

---

## 9. Relation to the HOAS / NbE trick space

The lineage settles on *defunctionalised* closures: a closure is `(body expression id, pruned interned
environment id)`, applied by re-entering the interpreter. That is what makes closures hashable, comparable in
O(1) (same body, same environment -> equal, no need to go under the binder) and usable as cache keys, and it is
why inference can use the same closure record with a different mode. A host-language closure (HOAS, a Java
lambda or a Truffle `CallTarget` with an opaque frame) gives none of this. If RLVM compiles hot bodies, the
compiled closure must still expose `(code id, captured value ids)` as its identity. The classic NbE recipe
"environments as arrays, evaluation to machine closures" therefore does not carry over unchanged: in a checker
whose cost is dominated by repeated sub-problems, cache keys need *interned, pruned* environments. The two normalization-bench repositories named in the request are not part of
this document's sources.

---

## 10. Not verified / open

* No instruction breakdown exists for sokonanoda HEAD; the only profile is mathgraph's of the September code.
* The October 1.7x is attributed to a batch of unmeasured commits; which of them matter is unknown.
* Instructions vs. number of threads is unpublished for all five checkers.
* Hit rates of `whnf_store` and `checked_closed` on mathlib are unpublished.
* Whether sokonanoda passes every current arena test at rev `9a021b6` was not checked (nothing was run).
* Export expressions that are equal up to binder names stay distinct ids in sokonanoda (no parse-time interning);
  how much sharing that loses is unknown.
