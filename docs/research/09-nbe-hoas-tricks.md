# 09 - HOAS / NbE implementation tricks for the RLVM evaluation core

Scope: what the two `normalization-bench` repositories, smalltt, elaboration-zoo and the closure-based Lean
Kernel Arena checkers teach about building a fast normaliser/conversion checker, and what RLVM should build
on GraalVM. Paths are relative to `/home/queclr/Documents/rabdos-lean-vm/refs/`. Everything marked **[src]**
was read in the cited file; **[measured-here]** was measured on this host for this document; **[lit]** is
from the literature/general knowledge and was *not* verified against source here; **[hyp]** is my
interpretation.

Repositories read (cloned `--depth 1`, nothing built or run): `normalization-bench-guest0x0` (ed3a61c),
`normalization-bench-kovacs` (306dad0), `smalltt` (ea99b0f), `elaboration-zoo`, `sokonanoda` (15f5eaa),
`gruszka` (88aca33), `nanoclo`, `lazylean` (HEAD, README says 0.5.1), `cadenza` (e2b66e2), `rpylean`,
`tenet-jvm`, `nano-lean` (README only), `lean-kernel-arena/checkers/*.yaml`.

## 0. Decisions in one page

1. **Every one of the fastest arena checkers is NbE over hash-consed values with per-value memo slots**
   (nano-lean 65.8 s, gruszka 77.0 s, sokonanoda 87.9 s, mathgraph, lazylean HEAD claims 0.48e12
   instructions single-threaded). They converged on one design; RLVM should start from it, not from HOAS and
   not from smalltt's un-shared object graph. Evidence: section 11.
2. **HOAS / "compile every lambda to a host closure" buys a small constant on pure beta-reduction and loses
   every caching lever.** OCaml: compiled-native HOAS is 0.77-1.0x the time of the plain closure
   interpreter, but compiling costs 0.1-16 s per term (section 3). GHC: interpreter is 1.6-3.3x compiled
   HOAS (section 2). JVM, measured here on GraalVM CE 25: HOAS with Java lambdas is 0.66-0.87x the CPU of a
   syntax interpreter in steady state, identical when cold, and 1.0-1.6x *slower* without a top-tier JIT
   (section 4). Host closures are opaque: no structural identity, no `(term, env)` cache key, no environment
   pruning, no pointer-equality shortcut in conversion.
3. **Conversion short-cuts matter more than evaluation speed.** smalltt (fast evaluator, no hash-consing, no
   conversion cache) needs 3.3 s for `TreeConv23`; Lean's "slow" substitution kernel answers every TreeConv
   in 0.001 s because equal terms are the same node (section 2.3). In gruszka, hash-consing values was "the
   difference between seconds and divergence" on Init (section 8.5).
4. **Constants stay folded during evaluation** (glued evaluation without the thunk): `eval` does
   beta/zeta/projection/iota-on-constructor only; delta happens in conversion under Lean's
   definitional-height order, memoised once per hash-consed application node (section 8.2). This is what
   makes strict or semi-strict argument evaluation safe.
5. **Types are values and the type of a lambda is an "infer closure"**; type inference never quotes. Quoting
   is needed only by the inductive/recursor checker and for persisting facts across arena resets (section
   9).
6. **Environment = hash-consed linked list, pruned by a 64-bit free-variable mask** stored on every syntax
   node; the pruned id is both the closure environment and the cache key (section 6). Linked lists beat AVL
   maps and skew lists at realistic depths (section 3.2).
7. **JVM runtime configuration is a first-order effect on the ranking metric** [measured-here]: for the same
   NbE workload total process CPU was 2.06 s with SerialGC, 7.46 s with ParallelGC and 10.0 s with G1 on
   this 20-core host; nursery 32 MB vs 2 GB is another 2.7x; Graal JIT threads add 0.1-0.3 s per process, C2
   0.04-0.1 s (section 4). A value heap made of primitive arrays (ids, not objects) removes GC tracing
   altogether.
8. **Deep recursion must be designed out**: both Kovacs (Scala needs `-Xss1000M`, JVM Nat-10M times blow up
   7-10x for 2x input) and gruszka (1 GiB stack still declined a million-step lazy accumulator until
   "hand-up" restarts were added) hit it (section 10).
9. **Truffle belongs in the closed-computation engine, behind a step budget**, as rpylean (meta-tracing JIT
   entered only on recursor back-edges) and lazylean/gruszka (separate call-by-need machine for declarations
   that "compute") do. Mathlib is ~700k small declarations with almost no computation; its instruction count
   is decided by the constant cost of the hash-consed checker, not by a JIT (section 1.6).

## 1. Recommended evaluation core for RLVM

### 1.1 Candidate designs, scored (5 = best)

| # | Design (exemplars) | Instruction count | Allocation rate | Cacheability | Truffle/JVM risk | Lazy-delta compatibility |
|---|---|---|---|---|---|---|
| A | **Substitution + term caches** (official C++ kernel 33.7 min, tenet-jvm 27.2 min, pestka 4.1 min) | 1-2. Guest0x0: 2.5x to >7000x slower than NbE; lazylean's own substitution kernel took 5.5e12 instr on Mathlib vs 0.48e12 after the NbE rewrite (lazylean/README.md) | 1. every beta/instantiate rebuilds term spines | 4. terms hash-cons well, but caches are per-context (fvars, loose bvars) | 5. plain Java, no JIT dependence | 5. it *is* the reference algorithm |
| B | **HOAS / compiled closures**: each definition and lambda body becomes a host closure tree or Truffle AST, values are host closures (Coq native_compute, Guest0x0 `compiled.*`, Kovacs HOAS, cadenza) | 3. best steady-state beta (0.66-0.87x of interpreter on GraalVM, section 4) but compile/JIT cost dominates one-shot terms; conversion and inference are not helped | 3. closure objects + boxed args + arg arrays (cadenza README: "Packed argument arrays remain the dominant allocation") | 1. closures are opaque: no ids, no `(term, env)` keys, no pruning, must apply to compare | 1-2. megamorphic call sites, JIT threads charged as instructions, code-cache growth, deopts, Java-stack recursion | 2. delta must be suspended explicitly (glued pair per call); cannot compare two closures without running them |
| C | **Object-graph NbE, smalltt style**: closure = (env, term), linked env, glued `VUnfold` thunk, no hash-consing | 3. fast eval, but conversion cannot short-circuit (TreeConv23 3.3 s vs 0.001 s) | 2. JVM object headers; survivors copied by young GC; GC was 10-65% of CPU in section 4 depending on nursery | 2. pointer identity only; no canonical keys; caches die with the GC graph | 4. ordinary Java; deep recursion remains | 4. rigid/flex/full maps onto heights with small changes |
| D | **Hash-consed arena NbE** (sokonanoda, gruszka, lazylean `nbe.cpp`, nano-lean): values, spines, environments are ids in flat tables; folded constants; per-value memo slots; pruned environments; sessions | 5. all top arena entries | 4. 32-40 B flat records, bulk reset, no tracing; one hash probe per node | 5. O(1) equality, memo slots, `(pruned env, term)` keys, cross-declaration sessions | 3. not Truffle-idiomatic (int-indexed heap, nothing for PE to virtualise) but no JIT dependence; bounds checks on every access | 5. demonstrated: heights + same-head budgeted probe (sokonanoda/src/checker/conv.rs) |
| E | **Call-by-need abstract machine with explicit stack** (Krivine/CEK: lazylean machine, rpylean `_eval.py`, gruszka `ground.rs`, Crégut machines in Guest0x0) | 4 on closed computation, 2 on ordinary declarations (heap continuations cost 1.0-3.5x vs recursive NbE in Guest0x0; without hash-consing repeated work is redone: gruszka SuzData 677 G in the machine vs 68 G in the kernel) | 3. thunks + frames, but garbage dies young and can be reused in place | 2. sharing only through thunk identity | 4. closed code with hot loops is exactly Truffle's case (rpylean precedent) | 2. produces whnf of closed terms only; conversion stays outside |
| **F** | **Hybrid: D for all checking + E (optionally Truffle-JIT) for closed computations past a budget** (what lazylean 0.5 and gruszka do) | 5 | 4 | 5 | 3 | 5 |

Recommendation: **F**. Build D first (it alone decides the Mathlib number), add E when the arena
`perf`/`decide`-style tests demand it.

### 1.2 Data layout (design D on the JVM)

Syntax (owned by the export-storage design, summarised here because the evaluator depends on three per-node
facts):

```
expr node: kind:u8, a:u32, b:u32, c:u32,
           loose:u16      // number of loose bvars (max index + 1); 0 = closed
           fvMask:u64     // bit i (i<63): de Bruijn index i occurs free; bit 63: some index >= 63
fvMask rules (sokonanoda/src/term/expr.rs:168-268):
  var i          -> 1 << min(i, 63)
  app f a        -> f.mask | a.mask
  lam/pi T b     -> T.mask | under(b)         under(b) = (b.mask >> 1) | (b.loose > 64 ? 1<<63 : 0)
  let T v b      -> T.mask | v.mask | under(b)
  proj s         -> s.mask
  ignoresBinder(body) = (body.mask & 1) == 0   // expr.rs:483
```

Value heap: one record per value, stored in `int[]` chunks (stride 8 = 32 B, gruszka's `Rec` is 32 B:
`gruszka/src/tc.rs:532-700`; sokonanoda's `VNode` is 40 B: `sokonanoda/src/checker/value.rs:241-253`). A
value is an `int` id; id 0 is reserved.

```
word0: kind(4b) | flags(12b) | small(16b)        word4: memo WHNF  (forced value / weak head nf; 0 = unknown)
word1: a                                          word5: memo STEP  (one unfolding, or lambda domain; 0 = unknown, self = "does not step")
word2: b                                          word6: memo TYPE  (inferred type; 0 = unknown)
word3: c                                          word7: spare (digest / generation stamp)

kind      a            b             c            notes
SORT      levelId
NATLIT    bigId                                   STRLIT likewise
LAM       envId        lamExpr                    closure; domain evaluated lazily into STEP
PI        envId        piExpr        domVal       closure for the codomain
PI_INFER  envId        lamExpr       ctxId        "the type of this lambda": domain in STEP, codomain = infer(body) (sec. 9)
THUNK     envId        expr                       delayed argument; WHNF slot holds the forced value
FVAR      level        typeVal                    head; hash-consed by (level, type)
CONST     nameId       levelsId                   head; small = {AXIOM, CTOR, REC, QUOT, IND, UNFOLD}
APP       fnVal        argVal        headVal      spine cell; small/len = spine length
PROJ      structVal    structName    headVal      spine cell; small = field index
flags: CLOSED (no FVAR reachable), HEAD_ACTIVE (head is UNFOLD/REC/QUOT), SPINE_HAS_PROJ
```

A neutral term is its last spine cell (sokonanoda: "each application node is itself a value",
`value.rs:186-207`, `spine()` at `value.rs:580-597`); `f a b` and `f a` share the `f a` cell, so partial
applications, spines and neutrals are one mechanism and hash-consing `(fnVal, argVal)` gives maximal
sharing.

Environments: a second table, `env node = (val, parent, len, lsub, mask)`, hash-consed on `(parent, val)`;
a third, `ctx node = (typeVal, parent)`, lists the types of the bound variables for inference
(sokonanoda `Closure.ctx`, `value.rs:82-87`; `CtxNode`, `value.rs:366-369`).
The base node of a chain carries the universe-level substitution id (`lsub`) of the constant whose body is
being evaluated, so universe instantiation is never applied to terms (sokonanoda `EnvNode.lsub`,
`value.rs:320-329`; gruszka keeps `ls` in every closure instead, `tc.rs:532-541`).

Hash-consing table: open addressing, linear probing, power-of-two `int[]` of value ids; key = (word0 without
len, a, b, c) compared against the arena words directly (no boxed keys). sokonanoda: `intern`,
`value.rs:979-993`. Head constants are memoised by `(name, levels)` (`eval_const`,
`sokonanoda/src/checker/eval.rs:318-331`).

Why int ids rather than Java objects: (i) a hash-consed value is reachable from the intern table for the
whole session, so with objects every value survives its first young GC and is copied; with `int[]` chunks
the collector never traces it; (ii) memo-slot writes into old objects dirty GC cards; (iii) session reset is
a pointer reset plus table clear; (iv) pair keys pack into one `long` (`(min<<32)|max`, gruszka `pair_key`,
`tc.rs:649`) for boxing-free caches. Cost: a bounds check per field access. This choice is the largest open
question for RLVM and deserves a two-day spike (section 12).

### 1.3 Evaluation (pseudo-code)

```
eval(env, e):                                   // returns a value id; never unfolds a definition
  if e.kind == VAR: return lookup(env, e.idx)   // no allocation
  if e.loose == 0: return memoClosed[(e, lsub(env))] ?: evalNode(base(lsub(env)), e)
  if e.kind in {APP, PROJ, LET, PI, LAM}:
      env = prune(env, e.fvMask)                // canonical key, section 6.3
      return memoOpen[(env, e)] ?: evalNode(env, e)
  return evalNode(env, e)

evalNode(env, e) = switch e.kind:
  SORT l      -> mkSort(simplify(substLevel(l, lsub(env))))
  CONST c ls  -> constHead(c, substLevels(ls, lsub(env)))          // UNFOLD head if c is a def/thm
  NATLIT/STR  -> mkLit
  LAM         -> mkLam(prune(env, e.fvMask), e)
  PI          -> mkPi(prune(env, e.fvMask), e, eval(env, e.dom))
  LET         -> loop over the let-chain: env = cons(env, arg(env, val)); e = body      // iterative
  PROJ i s    -> proj(i, eval(env, s))
  APP         -> (head, a1..an) = peelSpine(e)                      // iterative, args on a small stack
                 f = eval(env, head)
                 if f is REC/QUOT head: evaluate all args, try to fire once (major already a ctor/literal)
                 k = 0
                 while k < n:
                   if f.kind == LAM:            // multi-argument beta without intermediate closures
                       benv = f.env; body = f.lamExpr
                       repeat: benv = cons(benv, arg(env, a[k++])); body = body.body
                       until k == n or body is not a lambda
                       f = eval(benv, body)
                   else f = app(f, arg(env, a[k++]))
                 return f

arg(env, a):                                    // argument policy, section 7
  if a.kind in {VAR, CONST, SORT, LIT, LAM}: return eval(env, a)   // already a value or O(1)
  if callee's binder ignores it (ignoresBinder): return HOLE        // never evaluated
  return mkThunk(prune(env, a.fvMask), a)       // hash-consed (expr, env): also the open-eval cache entry

app(f, x):
  switch f.kind:
    LAM            -> eval(cons(f.env, x), f.lamExpr.body)
    FVAR/CONST/APP/PROJ -> mkApp(f, x)          // hash-consed spine extension; Nat.succ on a literal folds
    THUNK          -> app(force(f), x)
    else           -> reject("ill-typed application")

force(v):  while v.kind == THUNK: { w = WHNF[v]; if w == 0 { w = eval(v.env, v.expr); WHNF[v] = w }; v = w }; return v
```

The multi-argument beta loop and the "walk a call's lambdas as (term, env) pairs, create a value only for
the result" rule are in `sokonanoda/src/checker/eval.rs:219-257, 427-450` and gruszka `docs/architecture.md`
("What is not computed"). The strict/thunk switch in `arg` should be a compile-time flag for the first
measurements: sokonanoda is fully strict (`eval.rs:238-256`), gruszka uses thunks (`Val::Thunk`,
`tc.rs:541`).

Weak-head normalisation is an iterative loop with an explicit stack of pending eliminators; recursion is
only used to evaluate bodies:

```
whnf(v):                                        // sokonanoda force_reducible, eval/reduction.rs:162-216
  if WHNF[v] != 0: return it
  cur = v; waiting = []                         // explicit stack: recursors/projections awaiting their major
  loop:
    cur = force(cur)
    if head(cur) is UNFOLD: nxt = unfold(cur); if nxt != cur { cur = nxt; continue }
    else if head(cur) is REC or QUOT (enough args):
        m = major(cur)
        if m is ctor app / literal: { cur = fire(cur, m); continue }
        if m can still reduce:   { waiting.push(cur); cur = m; continue }      // "Descend"
    else if cur is PROJ on a reducible struct: { waiting.push(cur); cur = struct; continue }
    // cur is canonical or stuck
    if waiting.empty: break
    p = waiting.pop(); r = fire(p, cur)
    if r != none { STEP[p] = r; cur = r } else { STEP[p] = p; cur = p }
  WHNF[v] = cur; return cur

unfold(v):                                      // sokonanoda unfold_value_go, eval/reduction.rs:373-441
  if STEP[v] != 0: return STEP[v]
  body = STEP[headConst] ?: eval(base(lsub = (uparams -> levels)), defValueExpr)   // once per (const, levels)
  r = applyMany(body, spine args and projections in order)                          // multi-arg beta
  STEP[v] = r; return r
```

### 1.4 Conversion (pseudo-code; Lean rules on values)

```
conv(d, x, y, RIGID):                           // d = current de Bruijn level; RIGID=false is "flex/probe" mode
  if x == y: return true                        // hash-consing: identity
  if cacheable(kind x or y): hit posCache[(min,max)] -> true
                             RIGID and neither is LAM: hit negCache -> false
  if probing: if budget-- == 0 { exhausted = true; return false }
  if natShortcut(x, y) decides: return it       // literal vs succ/zero forms
  r = direct(d, x, y, RIGID) or (RIGID and cold(d, x, y))
  record: true -> posCache; false -> negCache only if RIGID, no LAM, and not exhausted
  return r

direct:
  SORT/SORT        -> level equivalence          NATLIT/NATLIT, STRLIT/STRLIT -> same id
  FVAR-headed, same head                         -> spines(all positions)
  CTOR/IND/AXIOM-headed, same name, equivalent levels -> spines(skipping ignorable positions, 8.3)
  REC/QUOT-headed  -> RIGID: same head and spineProbe -> true; else proof-irrelevance; else iota either
                      side and retry; else same-head spines.      flex: same head -> spines, else false
  PI/PI, LAM/LAM   -> same (bodyExpr, env) and same domain -> true        // closure identity
                      else conv(domains) then conv(d+1, body1 @ fv, body2 @ fv) with fv = mkFVar(d, dom)
  UNFOLD/UNFOLD    -> flex: same head -> spines, else false
                      RIGID, same head: spineProbe (budget per argument pair) -> true
                                        else proof-irrelevance -> true; else unfold both, retry
                      RIGID, different heads: compare hints; unfold the side with the larger height
                                        (abbrev > regular(h) > opaque); equal heights: unfold both
  UNFOLD/other     -> RIGID: proof-irrelevance, else unfold the UNFOLD side and retry; flex: false
  stuck REC/other  -> RIGID: proof-irrelevance, iota, unfold the other side
cold (rare, kept out of the hot function as smalltt does with unifyCold):
  proof irrelevance (typeOf both, both types in Prop, types convertible)
  eta: LAM vs non-LAM -> compare body @ fv with app(other, fv) at d+1
  structure eta / unit-like: typeOf both, whnf type is a structure S: unit-like -> true;
                             one side is S.mk args -> conv(proj_i(other), arg_i) for every field
```

Sources: `sokonanoda/src/checker/conv.rs:86-450` (direct), `:501-535` (probe), `:690-838` (cold), hints
`sokonanoda/src/checker/env.rs:14-35`; smalltt's original three states `smalltt/src/Unification.hs:321-382`.

### 1.5 Thresholds found in the sources (starting values for RLVM)

| Constant | Value | Where |
|---|---|---|
| same-head speculative probe budget | 2048 conv steps per argument pair | sokonanoda `conv.rs:134` (`PROBE_CAP`) |
| same-head argument pre-probe | 256 ticks per pair, last argument first | gruszka architecture.md, milestone 36 |
| outer lazy-delta budget before trying other strategies | 4096 ticks, doubling per round | gruszka `HEAD_PROBE` / `RACE_B0` |
| smalltt speculation | exactly one flex attempt per path, then full | smalltt README "Approximate conversion checking" |
| free-variable mask width | 63 exact indices + 1 "far" bit | sokonanoda `expr.rs:168-169` |
| session size (arena + caches kept across declarations) | 8 MiB of tables (gruszka), 9 MiB budget (sokonanoda `cache.rs:13`), 128 MB (lazylean, Mathlib) | resp. docs |
| persistent closed-whnf store admission | value seen twice (`WHNF_ADMIT_THRESHOLD = 2`) | sokonanoda `eval/whnf_cache.rs:22` |
| "this declaration computes" switch to the lazy machine | > 1 M evaluation steps or > 64 MB of values (lazylean); session holds 2^26 values (gruszka `GROUND_AT`) | READMEs |
| reclaim inside a declaration | regions open at 2^22 values; roll back if result reaches <= 1/4 of >= 256 new values | gruszka "Regions" |
| nested-whnf depth before handing work up | 8192 (`DEEP_WHNF`) or half the stack | gruszka "Deep whnfs handed up" |
| derive a rewrite rule for a hot recursive definition | 32 unfoldings in a session (`RULE_HOT`) | gruszka "Derived rules" |

### 1.6 Where Truffle fits

- The checker (design D) is a data-structure program: its hot loops are hash probes, spine walks and
  memo-slot reads. Partial evaluation has no guest program to specialise on, because the "program" is a
  different small term for each of ~700k declarations. Run it as ordinary Java compiled by the host JIT;
  write it allocation-free (no boxed keys, no iterator objects, no lambdas in hot paths) so that JIT and GC
  threads stay idle.
- The closed-computation engine (design E) is where a Truffle AST or Bytecode-DSL interpreter pays: closed
  terms, hot recursive definitions, values that die young. Precedents: rpylean compiles each Lean expression
  once into an immutable `CodeNode` graph, uses `(rec_edge, code)` as JIT green variables and enters the JIT
  *only* on recursive recursor back-edges; a stuck neutral raises `_EvalBail` and the general machine takes
  over (`rpylean/rpylean/_eval.py:1-18, 73, 1421-1423, 1578, 1994`). cadenza (Truffle, Kotlin) represents
  neutrals as a `SlowPathException` subclass thrown out of compiled code
  (`cadenza/src/cadenza/data/NeutralException.kt:6`) and dispatches closure calls through a 3-entry
  `DirectCallNode` inline cache falling back to `IndirectCallNode`
  (`cadenza/src/cadenza/jit/dispatch.kt:24-41, 48-112`).
- Entry rule for E (from lazylean/gruszka/rpylean): the term is closed, the plain engine has already spent a
  step budget on it, and the consumer only needs a weak-head normal form or a comparison against a
  constructor/literal. Anything E cannot do returns the whole run to D.

## 2. Evidence A: AndrasKovacs/normalization-bench

Workload: untyped Church numerals (`n5M`, `n10M` built by `mul`) and full binary Church trees (`fullTree
n20..n22`, 2M-8M nodes); "normalization" = eval + quote, "conversion" = beta conversion check of two
differently-built equal values. Machine: i7-3770, 8 GB, Ubuntu 16.04. Scala 2.13.2 on OpenJDK 1.8.0_232 with
`-Xss1000M -Xmx4G`; "graalvm" = GraalVM 21.0.0.2, same options. GHC 8.8.1 `-O2 -fllvm`, `+RTS -A1G`. Times
in ms, average of 20 runs (Coq and smalltt: single run). Reproduced from
`normalization-bench-kovacs/README.md:94-105`:

| | GHC HOAS CBV | GHC HOAS CBN | GHC interp CBV | OCaml HOAS | nodejs HOAS | Scala HOAS | Scala HOAS graalvm | F# HOAS | Coq cbv | Coq lazy | Coq vm_compute | Coq native_compute | smalltt | luajit |
|:--|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|:--|
| Nat 5M conversion | 90 | 112 | 234 | 268 | 700 | 138 | 1013 | 1246 | N/A | 31663 | 1208 | 3359 | 500 | stack overflow |
| Nat 5M normalization | 101 | 108 | 167 | 131 | 976 | 374 | 2031 | 69592 | 1507 | 2604 | 4309 | 4790 | 411 | stack overflow |
| Nat 10M conversion | 208 | 224 | 695 | 610 | 1395 | 960 | 2145 | 4462 | N/A | OOM | 6965 | 5081 | 1681 | stack overflow |
| Nat 10M normalization | 227 | 269 | 439 | 965 | 3718 | 3908 | 5833 | too long | 3340 | 5780 | 11216 | 13181 | 1148 | stack overflow |
| Tree 2M conversion | 136 | 114 | 274 | 117 | 396 | 118 | 137 | 305 | N/A | 477 | 561 | 691 | 425 | 3576 |
| Tree 2M normalization | 86 | 76 | 163 | 244 | 323 | 85 | 84 | 1514 | 1248 | 702 | 960 | 1103 | 346 | 2533 |
| Tree 4M conversion | 294 | 229 | 588 | 150 | 827 | 271 | 250 | 630 | N/A | 646 | 1276 | 1302 | 1429 | OOM |
| Tree 4M normalization | 192 | 194 | 343 | 534 | 635 | 181 | 164 | 3119 | 1365 | 1488 | 1729 | 1983 | 745 | OOM |
| Tree 8M conversion | 723 | 457 | 1268 | 253 | 1726 | 625 | 516 | 1232 | N/A | 1279 | 2420 | 2901 | 2371 | OOM |
| Tree 8M normalization | 436 | 525 | 716 | 1298 | 1398 | 731 | 332 | 5930 | 3275 | 2871 | 3464 | 3497 | 1544 | OOM |

### 2.1 The variants

| Variant | What it is [src] |
|---|---|
| GHC HOAS CBV (`haskell/HOASCBV.hs`) | `data Val = VLam !(Val -> Val) \| VVar !Int \| VApp !Val !Val`; benchmark terms are written *as Haskell lambdas*, i.e. compiled HOAS with zero interpretive overhead; `VLam f $$ a = seq a (f a)`; values use de Bruijn levels, `quote` converts to indices |
| GHC HOAS CBN (`haskell/HOASCBN.hs`) | identical minus the `seq` and with a lazy argument field: call-by-need through GHC thunks |
| GHC interp CBV (`haskell/Interp.hs`) | syntax `Local Int \| App \| Lam \| Top Int`, top-level definitions in an array, closures `VLam Env Tm`, environment = linked list `Nil \| Skip Env \| Def Env Val`; module-wide `Strict` |
| OCaml / nodejs / F# / Scala HOAS | the same deep HOAS; Scala: `case class VLam(t: Val => Val)` (`scala/normalization-bench/HOAS.scala:20-23`) |
| Coq cbv / lazy / vm_compute / native_compute | typed impredicative Church encodings, conversion by `eq_refl` (`coq/Bench.v`); `cbv` and `lazy` are the kernel's abstract machines, `vm_compute` the ZINC-derived bytecode VM, `native_compute` compilation to OCaml [lit] |
| smalltt 0.2 | elaborator with glued evaluation (section 8.1), interpreted |

Caveat [src]: the OCaml tree workload is not the same term. `ocaml/HOASCBV.ml:39-40` defines `node` as `fun
t1 t2 l n -> n t1 t2`, whereas Haskell/Scala use `n (t1 l n) (t2 l n)`. The OCaml "Tree" columns therefore
measure a different computation and should not be compared.

### 2.2 Why the numbers look like this

- **Interpreter vs compiled HOAS is a small constant**: GHC interp/HOAS = 1.6-3.3x (234/90, 167/101,
  695/208, 274/136, 163/86, 716/436). Kovacs' conclusion: "plain hand-written interpreters ... have
  comparable performance to JIT HOAS" (`README.md:183-191`).
- **CBN vs CBV is noise in GHC** (0.63x-1.24x) because thunks are native there; on any other runtime a thunk
  is an object plus a check (Guest0x0 measures 1.1-2x, section 3.2).
- **JVM HOAS (C2) equals GHC on trees** (118 vs 136, 85 vs 86, 625 vs 723 ms) **but degrades super-linearly
  on deep numerals**: Nat conversion 138 -> 960 ms and normalization 374 -> 3908 ms for 2x input.
  `quote`/`conv` recurse once per `VApp`, i.e. 5-10 million frames deep. Kovacs: "perhaps there is some
  issue with deep stacks"; also "sometimes I got a sudden memory hike which resulted in out-of-memory
  process termination". [hyp] HotSpot's stop-the-world collectors scan the whole thread stack as roots at
  every young collection, so GC cost grows with stack depth times number of collections.
- **GraalVM (21.0) vs C2**: trees equal or better (Tree 8M normalization 332 vs 731 ms, the best of any
  system), deep numerals 1.5-7x worse (1013 vs 138 ms). So Graal's closure inlining/escape analysis helps
  wide shallow workloads and its deep-recursion behaviour was worse than C2's. Only JVM-on-Graal data point
  in either repository; it is JIT-compiled Scala, not a Truffle interpreter.
- **Memory headroom is a 3-6x factor**: "GHC solutions & smalltt had 3-6 times speedup from `-A1G`. Scala
  and nodejs performance becomes dismal without free RAM" (`README.md:157-161`). Confirmed for the JVM in
  section 4 (2.7x).
- **Bytecode VMs and "native" compilation in a proof assistant are not automatically fast**: Coq
  `vm_compute` is 3-50x slower than GHC HOAS here, and `native_compute` is not better than `vm_compute`
  (3359 vs 1208, 4790 vs 4309).
- **All JIT entries are CBV**; Kovacs warns that call-by-need "could possibly be a significant performance
  hit on JVM" and did not measure it.

### 2.3 smalltt's own tables (`smalltt/README.md:756-805`), seconds

| | smalltt | Agda | Coq | Lean 4 (2021-11) | Idris 2 |
|---|--:|--:|--:|--:|--:|
| NatConv 1M / 5M / 10M | 0.045 / 0.188 / 0.712 | 1.8 / 9.6 / 19.7 | stack overflow | 16.4 / 34.6 / 61.1 | 3.22 / 29.65 / 173.88 |
| TreeConv 20 / 23 | 0.408 / 3.325 | 1.7 / 13.7 | 0.618 / 4.699 | 0.001 / 0.001 | 16.65 / 25.38 |
| ForceTree 23 | 4.372 | 15.93 | vm 0.731, compute 5.407, lazy 9.664 | reduce 62.7, eval 5.52 | OOM |
| NfTree 23 | 3.023 | N/A | vm 5.996, compute 4.99, lazy 7.187 | N/A | N/A |

Reading: (1) NbE beats the substitution kernel by 85-360x when real reduction is needed (NatConv). (2) Lean
wins TreeConv by 3000x with no evaluation at all, because both sides are structurally shared and the kernel
compares/caches by node identity; smalltt has neither hash-consing nor a conversion cache and walks 2^23
nodes. A Lean kernel workload is far closer to TreeConv than to NatConv, hence decision 3. (3) "smalltt with
glued evaluation disabled would be 2x faster" on ForceTree (`README.md:809-812`): glued thunks are not free.

## 3. Evidence B: Guest0x0/normalization-bench (OCaml)

Framework: de Bruijn-index input terms, wall time of normalisation only, fresh process per run, 20 s timeout
(`MISSING`), laptop, GC untuned (author's caveats, `README.md:319-352`). Benchmarks (`bench.ml:68-130`,
`Common/Terms.ml`): `church_add n n`, `church_mul n n`, `exponential n` (`\x. t(n)`, `t(k+1) = (\y. y y)
t(k)`: normal form of size 2^n, fully shared), `parigot_add` (Parigot numerals, exponential normal form with
sharing), `random` (50 uniformly random terms of the given size), `self_interp_size` (Scott-encoded term,
structural recursion computing its size).

### 3.1 The normalisers [src: `Normalizers/*.ml`]

| Name | Technique |
|---|---|
| `subst.naive`, `subst.whead` | capture-avoiding substitution with explicit `shift`; strong-CBV order vs weak-head-first (`Subst.ml`) |
| `NBE.closure.list` / `.tree` | defunctionalised closures `VLam of value list * term`; env = OCaml list (`List.nth`) or AVL `Map` keyed by level (`NBE_Closure.ml`). **Reference baseline.** |
| `NBE.HOAS.list/tree/skew` | `VLam of (value -> value)`: host closures created *by the interpreter* at each `Lam`; env list / AVL / Okasaki skew-binary random-access list (`NBE_HOAS.ml`, `Common/Data.ml`) |
| `NBE.named.list/tree/ADT` | named variables, global fresh counter; env = assoc list / AVL / assoc list with pairs inlined into the cons cell (`NBE_Named.ml`) |
| `NBE.pushenter` | separate argument stack, closures allocated only when the stack is empty (`NBE_Pushenter.ml`); not in the result set |
| `NBE.lazy` | `Lazy.t` around every value: call-by-need everywhere (`NBE_Lazy.ml`) |
| `NBE.memo.v1..v4`, `.named` | closure NbE where every value has **one** memo slot for the term it quotes to at one level. v1: record + `(int*term) option` (2-3 indirections); v2: record with two mutable fields; v3: slot inlined in each constructor block (1 indirection); v4 = v3 without slots on variables; named: output is level-independent so the slot never misses (`NBE_Memo.ml`) |
| `NBE.HC.idx(.o)`, `.lvlidx(.o)` | pre-allocated arrays of the 10000 `Idx i` leaves (and `VLvl i`), i.e. leaf sharing only; `.o` = output only |
| `NBE.HC.hashcons` | fully hash-consed *terms* (input and quoted output) via `Hashtbl` with stored hashes; values not hash-consed, nothing memoised (`NBE_HashConsing.ml`) |
| `AM.Crégut.list/ADT/arr` | Crégut's strongly-reducing Krivine machine (CBN, no sharing); continuation stack as list of frames / frames with the tail inlined / preallocated array (`AbstractMachine.ml`) |
| `AM.Crégut.CBV` | author's CBV variant with heap continuations |
| `compiled.HOAS.{byte,native,O2}` | term printed as an OCaml program in tagged HOAS, compiled by `ocamlc`/`ocamlopt`, then run (`Compiled_HOAS.ml`) |
| `compiled.evalapply.*` | the same with n-ary `VLam1..VLam5` and `app1..app5` (eval/apply uncurrying) (`Compiled_Evalapply.ml`) |

### 3.2 Results (seconds; largest size per benchmark; raw data in `data/<combination>/*.dat`)

**Substitution vs NbE** (`data/subst-NBE`)

| benchmark (size) | subst.naive | subst.whead | NBE.closure.list |
|---|--:|--:|--:|
| church_add 400000 | 1.329 | 0.918 | 0.370 |
| church_mul 320 | 2.805 | 2.858 | 0.0120 |
| exponential 24 | 7.357 | 8.195 | 1.995 |
| parigot_add 10 (12) | 0.801 (15.60) | 7.732 (MISSING) | 0.397 (6.085) |
| random 8000 | 5.386 | 0.376 | 0.00115 |
| self_interp_size 2000 (4000) | 1.885 (12.52) | 16.46 (MISSING) | 0.00216 (0.00308) |

**NbE variants** (`data/NBE-variants`)

| benchmark (size) | HOAS.list | HOAS.tree | HOAS.skew | closure.list | lazy | AM.Crégut.CBV |
|---|--:|--:|--:|--:|--:|--:|
| church_add 100000 | 0.0371 | 0.0392 | 0.0462 | 0.0385 | 0.0619 | 0.0854 |
| church_add 400000 | 0.3817 | 0.3762 | 0.3768 | 0.3783 | 0.4154 | 0.3739 |
| church_mul 320 | 0.0115 | 0.0146 | 0.0162 | 0.0123 | 0.0185 | 0.0158 |
| exponential 24 | 1.891 | 1.939 | 1.924 | 1.920 | 1.984 | 0.000007 |
| parigot_add 12 | 6.413 | 7.620 | 6.620 | 6.154 | 7.645 | 6.842 |
| random 8000 | 0.00212 | 0.01132 | 0.00261 | 0.00169 | 0.00315 | 0.00591 |
| self_interp_size 6000 | 0.00923 | 0.07091 | 0.02022 | 0.00806 | 0.01790 | 0.01874 |

**de Bruijn vs named** (`data/DBI-named`)

| benchmark (size) | closure.list | named.list | named.tree | named.ADT |
|---|--:|--:|--:|--:|
| church_add 400000 | 0.3684 | 0.2446 | 0.2413 | 0.2367 |
| church_mul 320 | 0.0106 | 0.0118 | 0.0114 | 0.0117 |
| exponential 24 | 1.974 | 1.902 | 1.987 | 1.985 |
| parigot_add 12 | 6.033 | 8.204 | 7.491 | 6.175 |
| random 8000 | 0.00143 | 0.00713 | 0.01845 | 0.00719 |
| self_interp_size 6000 | 0.00928 | 0.01310 | 0.05478 | 0.00891 |

**Memoised quoting** (`data/NBE-memo`)

| benchmark (size) | closure.list | memo.v1 | memo.v2 | memo.v3 | memo.v4 | memo.named |
|---|--:|--:|--:|--:|--:|--:|
| church_add 200000 | 0.1189 | 0.2545 | 0.1983 | 0.1352 | 0.1734 | 0.1588 |
| church_mul 320 | 0.0114 | 0.0306 | 0.0287 | 0.0146 | 0.0146 | 0.0149 |
| exponential 24 | 1.934 | 0.000005 | 0.000004 | 0.000004 | 0.000004 | 0.000005 |
| parigot_add 12 | 5.976 | 0.774 | 0.715 | 0.696 | 0.725 | 0.000097 |
| random 8000 | 0.00156 | 0.00817 | 0.00347 | 0.00279 | 0.00280 | 0.00793 |
| self_interp_size 6000 | 0.00802 | 0.00995 | 0.01565 | 0.00876 | 0.00983 | 0.01373 |

**Sharing / hash-consing of terms** (`data/NBE-hashcons`)

| benchmark (size) | closure.list | HC.idx | HC.idx.o | HC.lvlidx | HC.lvlidx.o | HC.hashcons |
|---|--:|--:|--:|--:|--:|--:|
| church_add 400000 | 0.3686 | 0.2560 | 0.2594 | 0.2531 | 0.2598 | 0.5607 |
| church_mul 320 | 0.0119 | 0.0103 | 0.0090 | 0.0105 | 0.0100 | 0.0507 |
| exponential 24 | 1.979 | 1.507 | 1.509 | 1.519 | 1.578 | 0.4507 |
| parigot_add 12 | 5.922 | 4.993 | 4.897 | 5.222 | 5.184 | 2.489 |
| random 8000 | 0.00350 | 0.00494 | 0.00172 | 0.00499 | 0.00197 | 0.05394 |
| self_interp_size 6000 | 0.00748 | 0.00745 | 0.00849 | 0.00741 | 0.00803 | 0.01220 |

**Abstract-machine stack representation** (`data/AM-variants`, CBN machine)

| benchmark (size) | list | ADT (tail inlined) | array |
|---|--:|--:|--:|
| church_add 400000 | 0.2991 | 0.2343 | 0.2641 |
| church_mul 320 | 0.0241 | 0.0164 | 0.0235 |
| exponential 24 | 2.409 | 2.227 | 3.362 |
| parigot_add 12 | 6.697 | 6.830 | 9.074 |
| random 8000 | 0.00690 | 0.00712 | 0.00937 |

**Compiled NbE**: run time (`data/compiled-normalize`) and generate+compile time (`data/compiled-compile`)

| benchmark (size) | closure.list | HOAS.byte | HOAS.native | HOAS.O2 | evalapply.byte | evalapply.native | evalapply.O2 |
|---|--:|--:|--:|--:|--:|--:|--:|
| church_mul 320, run | 0.01201 | 0.02056 | 0.00928 | 0.00954 | 0.01398 | 0.00949 | 0.00943 |
| exponential 24, run | 1.908 | 2.500 | 1.906 | 1.908 | 2.438 | 2.200 | 2.328 |
| parigot_add 12, run | 5.777 | 7.781 | 5.431 | 5.468 | 7.569 | 5.870 | 5.388 |
| random 1000, run | 0.00044 | 0.00075 | 0.00148 | 0.00148 | 0.00065 | 0.00074 | 0.00074 |
| church_mul 320, compile | - | 0.0225 | 0.2024 | 0.2078 | 0.0213 | 0.1559 | 0.1471 |
| parigot_add 12, compile | - | 0.0129 | 0.1228 | 0.1209 | 0.0134 | 0.1135 | 0.1304 |
| random 1000, compile | - | 1.133 | 8.649 | 8.459 | 0.695 | 3.018 | 2.974 |
| random 4000, compile | - | 10.60 | MISSING | MISSING | 4.840 | 15.87 | 15.88 |

### 3.3 Why (author's analysis `README.md:354-761`, plus my reading of the code)

1. **Substitution loses by 2.5x to >7000x** because each beta step allocates a substituted copy; NbE defers
   and merges substitutions in the environment.
2. **Environment data structure**: plain list wins everywhere; AVL is up to 7.7x slower (self_interp 0.0709
   vs 0.0092), skew list 1.0-2.2x slower. Reasons: `cons` is the most frequent operation and is O(1)/one
   allocation only for the list; lookups mostly hit recent variables. Named variables with association lists are up to 5x slower than
   indices (random 8000: 0.0071 vs 0.0014). Author's caveat: environments in these benchmarks are shallow.
3. **Interpreter-created HOAS closures = defunctionalised closures** (within noise); the only difference is
   an indirect call instead of a direct call to `eval`.
4. **Laziness everywhere costs 1.1-2.0x** (church_add 100k 0.0619 vs 0.0385, random 0.00315 vs 0.00169) when
   it saves nothing.
5. **Heap-allocated continuations cost 1.0-3.5x** over using the host stack (`AM.Crégut.CBV` vs
   `closure.list`: church_add 100k 2.2x, random 3.5x). Inlining the stack tail into each frame beats a list
   of frames (one indirection per transition instead of two) and beats a bounds-checked array.
6. **The `AM.Crégut.CBV` exponential result (7 microseconds) is a sharing effect, and the machine is wrong
   in general** [my reading of `AbstractMachine.ml:125-163`; not executed]: neutral applications are turned
   into already-quoted `VNF term` values the moment they are built and are then passed around by reference,
   so the 2^24-node normal form is a 24-node DAG that is never traversed. But the de Bruijn indices inside a
   `VNF` are fixed at the level where it was created; using it under more binders (e.g. `\x. (\y. \z. y) (x
   x)`) would give wrong indices. Lesson kept: *emit shared DAGs, never trees*; lesson rejected: index-based
   results cannot be reused across levels without a shift or level-based output.
7. **One-slot quote memo**: with the slot inlined (v3) the overhead on sharing-free inputs is 1.1-1.8x and
   the gain on shared results is 8.6x (parigot_add 12) to 5e5x (exponential). Layout dominates: v1 (two
   extra indirections) costs 1.2-5.2x. Caching leaves too (v3 vs v4) is *faster* because it avoids
   allocation. With level-independent (named) output the memo never misses: parigot_add 12 in 97
   microseconds, 61000x faster than the baseline.
8. **Sharing leaf nodes** is a free 1.2-1.45x on church_add. **Full hash-consing of terms without any
   memoisation** costs 1.5-15x when there is no sharing (random 8000: 0.054 vs 0.0035; church_mul 4.3x) and
   wins only 2.4-4.4x when there is. Hash-consing pays when it is used as a key for memoisation and identity
   tests, not as a goal in itself.
9. **Compilation**: native compiled HOAS runs at 0.77-1.0x of the interpreter on the three benchmarks with
   real work and 3.4x *slower* on `random 1000` (bytecode 1.3-1.7x slower throughout); eval/apply uncurrying
   and `-O2` make no measurable difference. Native compile time is 0.1-0.2 s minimum and 3-16 s (bytecode
   0.7-10.6 s) for a 1000-4000-node random term whose interpretation takes 0.4-3.7 ms. A term must be
   evaluated >= 10^4 times before compiling it pays.

## 4. Evidence C: the same question on GraalVM CE 25 [measured-here]

Scratch program (not in the repository; `/tmp/rlvm-nbe-spike/Nbe.java`, ~140 lines) running Kovacs'
workloads (Tree 2M normalization / conversion, Nat 1M conversion) with three cores:

- **A = HOAS**: `VLam(Function<Val,Val>)`, terms written as Java lambdas (as in the Scala file).
- **B = syntax interpreter**: `eval(env, tm)` with an `instanceof` chain, `VLam(env, tm)`, linked `Env(val,
  next)`.
- **C = node tree ("closure generation")**: term precompiled once to objects with a virtual `exec(env)`
  (what a Truffle AST looks like before partial evaluation), same values.

Host: aarch64, 20 cores, `graalvm-ce-25` (JDK 25.0.5), `-Xms6g -Xmx6g`, worker thread with a 2 GB stack.
Metric: **total process CPU time in ms** (all threads: JIT + GC + mutator), read from
`OperatingSystemMXBean.getProcessCpuTime()`; `perf` is unavailable on this host (`perf_event_paranoid = 4`),
so this is a proxy for the arena's instruction count. One run per cell; a repeat of three cells agreed
within 3%.

| JIT configuration | core | treeNf x20 | treeConv x20 | natConv x20 | treeNf x1 | treeConv x1 | natConv x1 |
|---|---|--:|--:|--:|--:|--:|--:|
| Graal (default), SerialGC | A HOAS | 1420 | 1700 | 930 | 360 | 380 | 390 |
| | B interp | 1790 | 2570 | 1070 | 270 | 350 | 250 |
| | C node tree | 1450 | 1960 | 990 | 280 | 380 | 250 |
| C2 (`-XX:-UseJVMCICompiler`), SerialGC | A HOAS | 1530 | 1740 | 830 | 240 | 260 | 180 |
| | B interp | 1820 | 2570 | 1080 | 270 | 340 | 190 |
| | C node tree | 1940 | 2710 | 1160 | 260 | 350 | 210 |
| C1 only (`-XX:TieredStopAtLevel=1`), SerialGC | A HOAS | 3230 | 5080 | 1290 | 280 | 400 | 220 |
| | B interp | 2210 | 3170 | 1270 | 190 | 300 | 160 |
| | C node tree | 2220 | 3240 | 1240 | 210 | 280 | 170 |

JIT compiler time inside those totals: Graal 76-336 ms, C2 39-100 ms, C1 18-54 ms.

| Collector, core C, treeConv x20 | total CPU ms | reported GC ms |
|---|--:|--:|
| `-XX:+UseSerialGC` | 2060 | 176 |
| `-XX:+UseParallelGC -XX:ParallelGCThreads=1` | 2690 | 651 |
| `-XX:+UseParallelGC` (default thread count) | 7460 | 370 |
| `-XX:+UseG1GC` (JVM default) | 10020 | 509 |

| Young generation size, core B, x20, SerialGC | treeNf CPU (GC) ms | natNf CPU (GC) ms |
|---|--:|--:|
| `-Xmn32m` | 4770 (3076) | 2050 (1171) |
| `-Xmn256m` | 2640 (1344) | 1010 (216) |
| `-Xmn2g` | 1740 (162) | 940 (11) |

Findings:

1. With a top-tier JIT, HOAS costs 0.66-0.87x of the syntax interpreter and the node tree sits between them:
   the same order as OCaml (0.77-1.0x) and GHC (0.3-0.6x). It is a constant factor on the beta-reduction
   part only.
2. Cold (one pass over a 2M-node workload, about 0.1 s of mutator work) the three cores are
   indistinguishable and JIT threads are 25-65% of process CPU with Graal (13-40% with C2). Kernel checking
   of Mathlib is mostly cold code paths over fresh terms.
3. Without a top tier (C1 only) HOAS is 1.0-1.6x *slower* than the interpreter: the megamorphic
   `Function.apply` call is never inlined and lambda objects are not scalar-replaced.
4. Parallel and concurrent collectors multiply *total* CPU by 3.6-4.9x on a many-core host for identical
   mutator work. The arena charges every thread. Use SerialGC (or a single GC thread) and a large young
   generation, and prefer data that the GC does not trace.
5. Nursery size is a 2.2-2.7x factor (the JVM version of Kovacs' `-A1G` observation).
6. The Graal JIT's fixed cost is 2-3x C2's for this small program; both are negligible against a budget of
   a few hundred CPU-seconds *as long as the set of hot methods stays small* (a hand-written kernel), and not negligible if
   thousands of guest definitions were compiled.

## 5. HOAS on the JVM and on Truffle

How a host lambda executes on the JVM [lit, standard JVM behaviour]: each lambda expression becomes one
hidden class via `invokedynamic`/`LambdaMetafactory` (Scala >= 2.12 and Java); capturing lambdas allocate
one object per evaluation holding the captured variables (a flat closure); application is `invokeinterface
Function.apply`. A normaliser has one `ap` function through which *all* closures are applied, so that call
site sees every lambda class: beyond two receiver types C2 emits a vtable/itable call with no inlining;
Graal inlines through polymorphic sites more aggressively and scalar-replaces non-escaping closures, which
is consistent with its tree results (332 ms vs 731 ms for C2, section 2) and with section 4.

Three ways to host closures, and what each costs a *kernel*:

| | Host lambdas / generated classes | Tree of executable nodes ("closure generation", Truffle AST) | Syntax + environment (defunctionalised) |
|---|---|---|---|
| Creation cost per Lean lambda | class spin-up + JIT (ms each); 700k declarations make this prohibitive | one object per syntax node, once per definition | none |
| Application | megamorphic interface call | virtual `exec`, monomorphic per node after inlining; with Truffle a `DirectCallNode` inline cache per call site (cadenza uses limit 3, then `IndirectCallNode`) | direct call to `eval` + kind switch |
| Identity / hashing of a closure | none (opaque object) | `(node, env)` if envs are comparable | `(expr id, env id)`: an integer pair |
| Environment pruning, free-variable mask | no | possible (frame descriptor) | yes, a field read |
| Readback / quote | must apply to a fresh variable and re-run the body | same | same, but memoisable by `(value, level)` |
| Comparing two closures | must run both | must run both, or compare `(node, env)` | `bodyExpr == bodyExpr' && env == env'` then run |
| Partial evaluation benefit | - | large when the *same* guest function is hot and its callees are constants (closed computation) | none needed |

Truffle-specific facts relevant to the design:

- PE removes interpretive overhead only for call targets that become hot, and it virtualises frames/closures
  only if they do not escape. In NbE nearly every closure and environment escapes into the value heap, and
  open terms force a second path. cadenza's answer is to make closed evaluation the only compiled path and
  to *throw* neutrals out of it (`NeutralException extends SlowPathException`); rpylean's answer is
  `_EvalBail`.
- A closure in a Truffle language is `(RootCallTarget, captured env, partial args, arity)`
  (`cadenza/src/cadenza/data/Closure.kt:27-34`); calls need eval/apply-style arity dispatch
  (`dispatch.kt:48-112`: exact, over-applied, under-applied -> PAP, megamorphic fallback) and a tail-call
  trampoline. cadenza's README records that packed argument arrays are the dominant allocation of its
  recursive benchmark: the Truffle calling convention itself allocates.
- Compilation is asynchronous on compiler threads whose instructions the arena counts. Thresholds and thread
  counts are engine options and must be pinned (not verified here; see the Truffle platform document).
  rpylean restricts JIT entry to recursor back-edges so that "non-looping type checking pays negligible JIT
  bookkeeping overhead" (`_eval.py:13-17`).
- Readback is where HOAS hurts most: every quote or conversion under a binder re-executes the closure body
  on a fresh variable. With defunctionalised closures this can be cached by `(body, env + fresh var)`
  because fresh variables are hash-consed by `(level, type)` (section 6.4); with host closures it cannot.

Conclusion for RLVM: do not compile Lean lambdas to host closures in the checker. If a Truffle engine is
built, give it design E's job (section 1.6) and feed it definitions, not terms: a definition body is reused
across calls, a proof term is evaluated once.

## 6. Environment representations

### 6.1 Options and measurements

| Representation | extend | lookup i | closure capture | Evidence |
|---|---|---|---|---|
| Linked list (cons per binding, shared tails) | O(1), 1 node | O(i) | O(1): pointer | Fastest in Guest0x0 (3.2). smalltt `Env = ENil \| EDef Env ~Val` (`smalltt/src/CoreTypes.hs:32-35`), elaboration-zoo, Kovacs' interpreter, sokonanoda `Cons` nodes, gruszka |
| Linked list + Myers jump pointer | O(1), 1 node + 1 word | O(log n) | O(1) | nanoclo `EnvNode{entry,parent,len,next_level,jump}` (`nanoclo/src/closure.rs:36-44`); jump rule at `:416-426`: `jump = (len(p)-len(p.jump) == len(p.jump)-len(p.jump.jump)) ? p.jump.jump : p`; lookup at `:592-607` |
| Skew-binary random-access list | O(1) amortised, up to 2 nodes | O(log n) | O(1) | 1.0-2.2x slower than list in Guest0x0 |
| Balanced map keyed by level | O(log n) alloc | O(log n) | O(1) | up to 7.7x slower |
| Flat array, copy on extend | O(n) | O(1) | O(1) | not used by any arena checker for binder environments |
| Flat closure (copy only captured variables) | O(#free vars) at closure creation | O(1) | - | ZINC/Coq VM [lit]; cadenza capture layouts; sokonanoda "Framed" pruned frames: 64-bit mask + contiguous slots, index by `popcount(mask & ((1<<i)-1))` (`sokonanoda/src/checker/value.rs:858-888`) |
| Frame chain (array per function activation + parent link) | O(1) inside a frame | O(depth of nesting) | pointer | natural for a compiled engine (design E), where a definition's `n` lambdas are entered at once |

### 6.2 Lean-specific pressure

Lean terms have long lambda/let telescopes: lazylean reports a con-leche proof 73 binders deep and nanoclo a
Mathlib subterm reached under 543 different binder telescopes (their READMEs); the arena has `beta-ladder`
and `let-ladder` tests for exactly the O(n^2) behaviour of substitution on such spines. Yet all four
closure-based leaders use linked lists (nanoclo adds jump pointers; it is the slowest of them at 8.4 min,
cause not investigated). Recommendation: linked hash-consed list, specialise `lookup` for indices 0-2, add
the jump pointer only if profiles show long walks.

### 6.3 Pruning by free-variable mask (the key trick)

`prune(env, mask)` returns the canonical environment that agrees with `env` on exactly the variables an
expression reads. It is used (a) when building a closure or thunk, so garbage bindings are not retained and
equal closures get equal ids, and (b) as the cache key for `eval` and `infer`. lazylean: "Measured on Std,
pruning turned 41% of evaluation misses into hits"; nanoclo's 543-telescope example is inferred once instead
of 543 times.

```
prune(env, mask):
  if mask == 0: return base(lsub(env))
  if env.mask & ~mask == 0 and env.len <= highestBit(mask)+1: return env     // already pruned
  hit = pruneCache[hash(env, mask) & (2^14 - 1)]; if hit.key == (env, mask): return hit.val   // direct-mapped
  simple variant: k = highestBit(mask & ~FAR) + 1
     out = (mask & FAR) ? dropFirst(env, 63) : base(lsub(env))
     for i = k-1 downto 0: out = cons(out, bit(mask, i) ? lookup(env, i) : HOLE)
  compact variant (sokonanoda prune_env_cold, eval/environment.rs:123-214):
     one "frame" node = (mask, slots[popcount], cut, rest) interned by hash of its slots
  pruneCache[...] = ((env, mask), out); return out
```

The 14-bit direct-mapped memo is sokonanoda's (`checker/cache.rs:10-11`, `eval/environment.rs:75-89`). Masks
cover 63 indices; a term that reads further sets the "far" bit and keeps the tail unpruned. lazylean notes
that letting an unknown mask propagate upward was a real performance bug (a 73-binder proof went from 31 M
conversion steps to 200 k once exact masks were derived for terms whose own variables fit).

### 6.4 Levels for values, fresh variables, no weakening

- Terms use de Bruijn indices (stable under closure capture); values use de Bruijn *levels* (stable under
  context extension), so a value never needs shifting when it moves under a binder (all NbE sources).
- A fresh variable is `mkFVar(level = current depth, type)` and is **hash-consed by (level, type)**
  (`sokonanoda/src/checker/eval/environment.rs:14`, used at `conv.rs:274, 289`; lazylean README; nanoclo:
  "naming it by that level, rather than by a counter, makes two openings of the same binder produce the same
  variable"). Opening the same binder twice yields the same ids for everything built under it, so the eval,
  infer and conversion caches hit on re-entry. No global counter, no renaming.
- Depth-independent alternative: gruszka gives a binder "the first variable of its type that is not in
  scope" and pestka names a variable "by its type, value and how many of its kind are open", so the same
  subterm opened at two different depths still shares caches.
- The variable carries its type, so no typing context is needed to type a neutral (section 8.3).
- [hyp] Soundness of sharing such variables across unrelated binders: every cached fact (whnf, type,
  conversion) is a deterministic function of the value graph, and a variable's identity includes its type,
  whose own free variables are again (level, type) pairs; a fact about `FVar(l, T)` therefore holds for any
  variable of that type at that level. The arena checkers rely on this; RLVM must still fuzz it.

## 7. Call-by-need

| System | Policy [src] |
|---|---|
| smalltt | lazy: top-level and local definitions, Pi instantiation during elaboration, spines of top-level-headed applications; **everything else call-by-value** (`README.md:296-325`; `appCl'` strict vs `appCl` lazy, `Evaluation.hs:25-32`; `Let _ _ t u -> let ~vt = ...`, `:84`) |
| sokonanoda | **strict** arguments (`eval.rs:238-256`), definitions never unfolded by `eval`; iota fires in `eval` only when the major premise is already a constructor/literal (`eval.rs:219-300`) |
| gruszka | hash-consed `Thunk{term, env, ls}` "evaluated on demand and memoized"; an argument whose binder is never read is not evaluated; a casesOn reduces only its major premise |
| lazylean / rpylean / gruszka ground machine | call-by-need machine for closed computation: thunks overwritten on first force, environment dropped once the value is known |
| Guest0x0 `NBE.lazy` | lazy everywhere: 1.1-2.0x slower than strict when laziness saves nothing |
| GHC HOAS CBN vs CBV | 0.63-1.24x: free only where the runtime has native thunks |

Rules for RLVM:

1. **Thunk representation**: in design D a thunk is the hash-consed pair `(expr, pruned env)` whose WHNF
   slot receives the result. It costs one intern probe, the same probe a strict evaluator spends on its
   `(env, expr)` memo, so laziness is nearly free here, and two occurrences of the same delayed computation
   are one thunk (nanoclo: "`f x` reached under two environments that agree on `f` and `x` is one thunk").
   In design E a thunk is an object `{code, env} -> {value}` updated in place, with `env` cleared on update
   (gruszka: "the environment goes once the value is known") and the captured environment trimmed to the
   variables the delayed term reads ("the classic space leak").
2. **Do not thunk cheap arguments**: bound variables (already a value or thunk: pass the id), constants,
   literals, sorts, lambdas (closure creation is O(1) plus pruning). Thunk applications, projections of
   non-variables, lets, Pi types.
3. **Do not evaluate unused arguments at all**: `ignoresBinder(body)` is one bit of the free-variable mask;
   sokonanoda substitutes a placeholder (`infer.rs:293-297, 316-329`), gruszka does the same before
   unfolding a definition whose body never reads a parameter.
4. **Blackholing**: kernel terms have no recursive bindings, so a thunk can only depend on itself through a
   bug; gruszka's machine treats "a value that asks for itself" as a reason to give the run back. Keep a
   blackhole marker in debug builds. Per-worker arenas remove any need for atomic updates.
5. **Selector thunks**: a delayed `proj i t` should, when forced, reduce to the field if `t` evaluates to a
   constructor application and otherwise become a `PROJ` spine cell; the result goes into the thunk's slot
   so the structure is not retained through the thunk.
6. **Strictness that is safe for conversion checking**: because `eval` never performs delta (constants stay
   folded), eagerly evaluating an argument can only cost beta/zeta/projection work bounded by the syntax it
   traverses (and is shared through the caches). What must stay lazy is *unfolding* and *iota on unfolded
   majors*, which conversion drives. Strictness never changes an answer, only cost; the dangerous cases are
   closed computations (`decide`), which are precisely the ones routed to engine E.
7. **In-place reuse / uniqueness bits** (the reussir idea): impossible for hash-consed values (shared and
   immutable by construction), natural in engine E, where gruszka's machine uses reference-counted objects,
   in-place thunk update and frees without recursion.

## 8. Conversion checking on values

### 8.1 smalltt: glued evaluation and rigid/flex/full [src]

- Values (`smalltt/src/CoreTypes.hs:39-46`): `VLocalVar Lvl Spine | VFlex MetaVar Spine | VUnfold UnfoldHead
  Spine ~Val | VLam NameIcit Closure | VPi NameIcit VTy Closure | VU | VIrrelevant`. A top-level constant
  evaluates to `VUnfold head SId v` and application acts on both components: `VUnfold h sp v -> VUnfold h
  (SApp sp u i) (app ms v u i)` (`Evaluation.hs:34-40`), the second being a lazy thunk.
- Three unification states (`Unification.hs:321-382`, `forceCS` at `:19-23`): **Rigid** (start): same unfold
  head -> compare spines in Flex; on failure unfold both and continue in **Full**; different heads -> unfold
  both, stay Rigid. **Flex**: no unfolding allowed; same head -> spines, anything needing an unfolding
  fails. **Full**: always unfold first. Hence "at most one backtrack on any path to a sub-value"; the
  generalisation to N shots "did not show much difference" (`README.md:383-390`).
- `quote` takes an unfolding option (`UnfoldAll | UnfoldMetas | UnfoldNone`, `Evaluation.hs:162-180`): the
  same value can be read back small (head + spine) or normal.
- Paired values `G {g1, g2}` keep the least-reduced and the most-reduced form of a type so that neither
  forcing work nor small size is lost (`README.md:400-417`).
- Rare cases (`VIrrelevant`, eta) are moved to a `noinline` cold function (`:388-414`).

### 8.2 The Lean adaptation (sokonanoda, also lazylean, gruszka, nanoclo) [src]

Differences from smalltt that RLVM should copy:

1. **No thunk per application.** An unfoldable constant evaluates to a head node; applying it only extends
   the hash-consed spine (`eval.rs:403-425`). The unfolding is computed on demand by applying the constant's
   evaluated body to the whole spine at once and is memoised in the spine cell's STEP slot
   (`eval/reduction.rs:373-441`): "a constant unfolds at most once per instantiation". This removes the 2x
   glued-evaluation overhead smalltt reports.
2. **Heights instead of "unfold both".** Different heads: unfold the side whose hint is greater
   (`ReducibilityHint::is_lt`, `checker/env.rs:23-35`: opaque < regular(h) < abbrev); equal -> both. The
   first attempt for each side is a non-forcing unfold; only if neither moves is a forcing unfold tried
   (`conv.rs:330-365`).
3. **Budgeted same-head probe instead of all-or-nothing Flex.** For `f as =?= f bs`, each argument pair is
   compared with a budget of 2048 conversion steps (`PROBE_CAP`, `conv.rs:134, 501-535`); "exhausted" is a
   third outcome meaning *undecided*, never "false"; then proof irrelevance, then unfold both. gruszka: 256
   ticks per pair, last argument first, and an outer race with doubling budgets (architecture.md "Conversion
   order").
4. **Negative results under a budget go to a separate cache** (`conv_cache_neg_probe`, `conv.rs:94-131`) so
   that a truncated "false" is never reused as a definitive one.
5. **Recursors and projections are heads too**: stuck `rec` applications are compared by spine after an iota
   attempt (`unify_iota`, `conv.rs:551-589`).
6. **Nat literals**: comparison shortcuts for literal vs `succ`/`zero` forms (`conv_nat`,
   `conv.rs:840-858`); Nat binary operations are deferred while the other side can still unfold (gruszka
   "NatFoldWaits", sokonanoda `nat_red_defer`, `eval.rs:621-635`).

### 8.3 Rules that need types, and how to get them without a context

| Rule | Needs | How [src: `sokonanoda/src/checker/conv.rs:690-838`, `eval.rs:493-577`] |
|---|---|---|
| function eta (`\x. b` vs neutral `n`) | nothing: compare `b[x]` with `n x` under a fresh variable; the variable's type is the lambda's domain | `unify_cold`, `conv.rs:695-712` |
| proof irrelevance | type of each side, and that the type is a `Prop` | only tried when both sides are neutral (or lambdas); `value_type(v)` walks the head's type through the spine, memoised in the value's TYPE slot; `is_prop_type` = sort of the type is level 0; then the two types are compared |
| structure eta (`x` vs `S.mk a b`) | that the type whnf's to a structure `S` with one constructor | `try_struct_eta`: types of both sides, compare `proj_i(x)` with field `a_i` |
| unit-like types | the type has one constructor with zero fields | same function, returns true after the types agree |
| skipping proof arguments in a spine | which argument positions are proofs | relevance signature per `(constant, levels)`: bitmasks `prop_arg`, `absent_arg`, `prop_result` computed once from the constant's type (`checker/relevance.rs:13-52`); a proof position is skipped once the preceding arguments agree; `result_is_not_proof` lets proof irrelevance be skipped without inferring a type |

`value_type` requires *typed heads*: `FVAR(level, type)`, constants via the memoised `const_head_type(name,
levels)`, literals by their inductive type. Lambdas and Pis are never asked for their type on this path
(`value_type_opt`, `conv.rs:742-747`).

### 8.4 What can be memoised in an NbE world

| Cache | Key -> value | Source |
|---|---|---|
| closed-term evaluation | `expr` (plus level-substitution id) -> value | sokonanoda `eval.rs:41-55` |
| open-term evaluation | `(prune(env, mask(expr)), expr)` -> value, only for App/Proj/Let/Pi/Lambda nodes | `eval.rs:56-70`; gruszka's thunk identity is the same key |
| constant head, constant type, result sort | `(name, levels)` -> value | `eval.rs:318-373` |
| per-value slots | STEP (one unfolding / lambda domain), WHNF, TYPE | `value.rs:241-253`; gruszka `Memo{whnf, core, infer, uf}`, `tc.rs:601-618` |
| conversion | ordered id pair -> true (always) / false (rigid, non-lambda, not truncated) | `conv.rs:94-131` |
| inference | `(pruned env, expr)` -> type value + "checked under which universe parameters" | `infer.rs:164-170, 258-266` |
| quote | `(value, depth)` -> term | `quote.rs:10-13` |
| relevance signatures | `(name, levels)` | `relevance.rs:103-120` |
| persistent closed whnf store | digest of a closed value -> quoted result, admitted on second sighting | `eval/whnf_cache.rs:22-69` |
| sessions | the whole arena and all tables live across consecutive declarations | gruszka "Sessions"; lazylean "Sessions" |

Sessions are valid because the environment only grows and a value means the same thing in later
declarations; they must end when a declaration fails, when order goes backwards, and when a size budget is
reached (gruszka lists the exact conditions).

### 8.5 Hash-consing of values: the two opposing positions

- Kovacs (`smalltt/README.md:270-294`): skip it; it does not compress beta-normal forms (`oneMillion` as a
  Peano numeral is incompressible), and it has overhead. True for an *elaborator* whose conversion problems
  are mostly small and fresh.
- gruszka (architecture.md "Values"): "Without this sharing, two unfoldings of the same definition produce
  equal but separate graphs, and comparing them can take as long as running the definition. In Init, that
  was the difference between seconds and divergence." True for a *kernel*, whose input is a hash-consed DAG
  where the same types recur millions of times.
- Guest0x0 quantifies the overhead when nothing is shared: 1.5-15x for full hash-consing of terms, about
  1.1x for a single inlined memo slot.

For RLVM the kernel position applies; the overhead is contained by making hash-consing the allocation path
itself (flat records, one probe) rather than a wrapper around objects.

## 9. Type inference in NbE style

Signature (sokonanoda `infer_value`, `checker/infer.rs:94-268`; gruszka "Inference"): `infer(mode, depth,
env, ctx, expr) -> type value`, `mode in {Check, InferOnly}`, `env` = values of bound variables, `ctx` =
their types (sokonanoda keeps a parallel list; gruszka reads the type off the environment entry).

```
infer(mode, d, env, ctx, e):
  VAR i        -> ctx[i]
  SORT l       -> Sort(succ l)             CONST c ls -> constType(c, ls)      (memo per (c, ls))
  LIT          -> Nat / String
  cache: key = (prune(env, e.fvMask), e); a Check result answers InferOnly queries, not vice versa
  LAM x:A. b   -> dom = eval(env, A)
                  if mode == Check: inferSort(A); fv = mkFVar(d, dom); infer(Check, d+1, env+fv, ctx+dom, b)
                  return mkPiInfer(prune(env, e.fvMask), ctx, e) with STEP := dom   // codomain NOT computed
  PI x:A. B    -> l1 = inferSort(A); dom = eval(env, A); fv = mkFVar(d, dom)
                  l2 = inferSort(B) under (env+fv, ctx+dom); return Sort(imax l1 l2)
  LET x:A:=v;b -> if Check: inferSort(A); conv(eval A, infer v) or reject
                  infer(mode, d, env + arg(env, v), ctx + eval(env, A), b)          // no abstraction needed
  APP f a1..an -> fty = infer(f)
                  for each a_k: P = whnf(fty) must be a Pi (reject otherwise)
                       if Check: conv(P.dom, infer(a_k)) or reject
                       fty = instantiate(P, ignoresBinder(P.body) ? HOLE : arg(env, a_k))
  PROJ i s     -> whnf(infer s) must be `S params`; walk S.mk's type with params and proj_j(s), j < i
instantiate(P, v):  PI       -> eval(cons(P.env, v), P.body)
                    PI_INFER -> infer(InferOnly, d, cons(P.env, v), P.ctx + dom(P), P.lamExpr.body)
```

Tricks:

1. **Infer closures** (the central one): the type of `fun x => b` is a Pi whose codomain is "infer `b` when
   instantiated" (`Closure::mk_infer`, `infer.rs:190-215`; `apply_closure`, `eval.rs:452-474`; gruszka
   `Val::CloI`, `tc.rs:535-538`; lazylean: "the type of a lambda is a Pi whose body is inferred when it is
   instantiated"). No quoting, no abstraction over the fresh variable, and in InferOnly mode the body is not
   even visited. Contrast smalltt, which must quote: `valToClosure` = `Closure env (quote (lvl+1) UnfoldNone
   t)` (`smalltt/src/InCxt.hs:76-79`, used at `Elaboration.hs:177`).
2. **Telescope walking**: while the function type is a Pi whose codomain closure is a plain term, keep
   `(tenv, piExpr)` and step through nested Pi *syntax*, extending `tenv`, instead of building and forcing a
   Pi value per argument (`infer_app_v`, `infer.rs:270-337`). mathgraph adds "avoid forcing an inferred
   function type when it is already a Pi" (`lean-kernel-arena/checkers/mathgraph.yaml`).
3. **Arguments are evaluated lazily or not at all**: the codomain is instantiated with the argument's thunk,
   or with a placeholder when the codomain ignores its binder (non-dependent arrows: the common case).
4. **Two modes**: Check verifies argument types; InferOnly (used on already-checked terms, inside infer
   closures and for proof irrelevance) trusts them. Closed subterms checked once are recorded
   (`checked_closed`, `infer.rs:172-186, 252-254`) and gruszka shares them across workers stamped with the
   declaration that produced them.
5. **Caching under binders** works because the key is the pruned environment: the fresh variable for a
   binder is determined by (level, type), so re-entering the same binder with the same relevant bindings
   reproduces the same key.
6. **Top-level check**: `conv(infer(Check, value), eval(declaredType))` on values; nothing is quoted.

When quoting is unavoidable [src]: sokonanoda calls `quote` only from the inductive-type machinery
(`checker/inductive/{spec,recursor,restore,mod}.rs`, building constructor/recursor telescopes as terms to
compare with the export) and from the persistent closed-whnf store (`eval/whnf_cache.rs:59-63`). nanoclo:
"substitution into a body happens only when a type leaves the checker". Keep `quote` memoised by `(value,
depth)` and emit hash-consed terms.

## 10. Known pathologies and remedies

| Pathology | Evidence | Remedy |
|---|---|---|
| **Exponential readback / loss of sharing**: the value graph is a DAG, naive `quote` and naive `conv` walk it as a tree | Guest0x0 `exponential` and `parigot_add` (2-6 s vs microseconds with a memo); smalltt TreeConv | never quote on the main path; identity test first; memoise quote by `(value, depth)`, conversion by id pair; emit hash-consed terms. Level-dependent output defeats a one-slot memo (v3: 8.6x vs named: 61000x): key closed values without the depth |
| **Sharing lost across unfoldings** (two unfoldings of one definition give two graphs) | gruszka Init: "seconds vs divergence" | hash-cons values, environments, spines; memoise the unfolding in the spine cell |
| **Glued-evaluation overhead** (a thunk per application of an unfoldable head) | smalltt: 2x on ForceTree | head + spine only; unfold on demand, memoised |
| **Optimistic same-head comparison that backtracks exponentially** | smalltt README on unbounded optimism; gruszka trap `s2bad`: 21 G with a budget, 785 G with unbounded congruence | budgeted probe with a third "undecided" outcome; separate cache for truncated negatives; at most one speculative attempt per path, or doubling budgets |
| **Reference order of lazy delta computes what neither side needs** | gruszka "Conversion order": three CFSG proofs on which the official kernel exceeds 24-100 GB | change only the *order* of sound steps: Nat folds wait, look for a common head first, compare a recursor-headed proof by its type before reducing it |
| **Eta loops / non-termination** | arena `eta-struct-major-*-no-whnf` tests, on which the official kernel does not terminate (tenet-jvm README); gruszka cites lean4#15351 (structure eta before the major is reduced) | function eta only when exactly one side is a lambda; structure eta only when one side is a constructor application and after the types agree; unit-like check compares types only; step budgets on speculative paths |
| **Deep recursion / stack overflow** | Kovacs: every runtime except GHC needed stack tuning, Scala `-Xss1000M`, luajit could not run; tenet-jvm runs the checker in a large-stack thread; gruszka: 913 500-element list literal overflowed infer/defeq, million-step lazy accumulator declined at a 1 GiB stack | see 10.1 |
| **Memory proportional to work, not to live data** | lazylean motivation (Lean's kernel at 166-178 GB); gruszka 286 GB before regions | sessions with a size budget; gruszka regions (roll back the arena after a whnf whose result reaches <= 1/4 of what it built); route closed computations to engine E, where garbage dies |
| **Space leaks through environments** | gruszka ground machine: `f i` kept the accumulator alive | prune environments to the free-variable mask; clear a thunk's environment on update |
| **Compilation cost exceeding evaluation cost** | Guest0x0: 0.1-16 s compile vs milliseconds of work; section 4: JIT is 13-65% of CPU when cold | interpret; compile only definitions that are provably hot (gruszka derives rules after 32 unfoldings; rpylean JITs only recursor back-edges) |
| **GC and JIT threads inflating the metric** | section 4: 3.6-4.9x from the collector alone | SerialGC, large nursery, non-traced value heap, pinned compiler threads |
| **Stale ids after an arena reset** | gruszka `SK_POISON`, region gate | generation stamps on direct-mapped caches; a poison mode in tests that shifts id spaces at every reset |

### 10.1 Stack-safe design for the JVM

Facts: the default Java thread stack is 1-2 MB (`ThreadStackSize`; `ulimit -s` does not govern threads
created by the JVM) [lit]; a thread created with `new Thread(group, runnable, name, stackSize)` gets the
requested size on HotSpot/Linux: the scratch benchmark of section 4 ran 1M-deep recursion on a 2 GB stack
[measured-here]. `StackOverflowError` is catchable but leaves half-updated state unless every mutation is
idempotent.

Layers, in order of preference:

1. **Iterate over head steps.** `whnf` is a loop (beta, delta, iota, projection are tail steps); let-chains
   and application spines are peeled iteratively (`eval.rs:186-195, 219-257`); recursors waiting for their
   major premise go on an explicit stack (`waiting: Vec<V>`, `eval/reduction.rs:162-216`).
2. **Run each worker on a thread with a large stack** (256 MB-1 GB): cheap, and the stack pages are only
   touched if used.
3. **Depth counter with "hand-up"** for the remaining true recursion (nested forcing, infer and defeq on
   long cons-lists): when nesting passes a threshold (gruszka: 8192 nested whnf calls, or half the stack for
   infer/defeq), unwind with a preallocated control-flow exception carrying the value to work on; the
   outermost driver computes that value from a shallow stack (deepest first), stores it in its memo slot,
   and restarts the original call, which now hits the memo. Because results are memoised by id, the restart
   costs at most the threshold depth of repeated work (gruszka "Deep whnfs handed up", "Deep inferences and
   comparisons handed up").
4. **Explicit continuation stack everywhere** (the abstract-machine style) only in engine E: Guest0x0
   measures 1.0-3.5x overhead for heap continuations, so it is not the default for the checker.
5. Keep the mutator's stack shallow for the GC's sake as well ([hyp], section 2.2).

## 11. What the arena's closure-based checkers do (cross-reference)

| Checker (Mathlib virtual time) | Values | Arguments | Constants | Environment | Inference | Extra |
|---|---|---|---|---|---|---|
| nano-lean (65.8 s) | NbE, hash-consing, de Bruijn; "beta reduction is just an environment push and every value remembers its own normal form" (`nano-lean/README.md:9`) | not read | not read | not read | not read | derived rewrite rules (cited by gruszka), PGO build, 4 threads |
| gruszka (77.0 s) | hash-consed `Sort/Lit/Clo/CloI/Neu/Thunk`, 32 B record incl. 4 memo words | hash-consed thunks; unused ones never evaluated | folded, unfolded by lazy delta; rules derived for hot recursive definitions | hash-consed linked lists | values, infer closures | sessions (8 MiB), regions, ground machine past 2^26 values, 4 workers, PGO |
| sokonanoda (87.9 s), mathgraph (2.5 min) | hash-consed 40 B nodes, spine cell = value | strict | folded (`Unfold` head), STEP memo | cons + pruned frames, 64-bit masks | values, infer closures, telescope walking | relevance signatures, 2048-step probe |
| lazylean (HEAD README: 0.48e12 instr, 1 core) | hash-consed neutrals, envs, fresh vars | - | "constants stay folded ... the unfolding is stored in the value" | pruned by 64-bit mask | values, lambda type inferred on instantiation | sessions (128 MB), Krivine machine with thunks for declarations that compute |
| nanoclo (8.4 min) | sokonanoda's values and conversion | thunks named by the bindings they read | - | interned list + Myers jump pointers; substitution only at the boundary | cache keyed by the bindings a term reads | - |
| rpylean (96.3 s) | call-by-need CEK machine over persistent code nodes, RPython meta-tracing JIT, closed terms only with bail-out | thunks | - | - | (general `machine.py`, not studied here) | JIT entered only on recursor back-edges |
| kefir (7.2 min) | "environment-based evaluator with call-by-need sharing; anything it cannot confirm is rechecked by a substitution-based checker" (yaml) | | | | | |
| tenet-jvm (27.2 min, Java) | none: locally nameless substitution, lossy direct-mapped sharing table, ParallelGC, large-stack thread (`tenet-jvm/docs/design.md`, `bin/tenet-jvm`) | | | | | the only JVM entry; 25x behind the NbE leaders |

## 12. Verification status, risks, open questions

Not verified / not found:

- No Lean Zulip thread on "kernel vs NbE" was located by search; the closest primary source is
  `smalltt/README.md:165-168` ("Lean uses naive substitution in its kernel ... slower and more complicated
  than a straightforward NbE implementation").
- "sixty" (Olle Fredriksson) is credited by `elaboration-zoo/GluedEval.hs:5-7` as the origin of glued
  evaluation; its README only lists glued evaluation as a roadmap item. No "staged NbE" write-up relevant to
  a kernel was found.
- Agda's abstract machine (`Agda.TypeChecking.Reduce.Fast`: call-by-need environment machine with an STRef
  heap, compact case trees, memoised definition lookup) and Coq's VM / native_compute internals are quoted
  from documentation and papers, not from source.
- Truffle engine options (compilation thresholds, compiler thread count) were not checked.
- Section 4 measures CPU time, not instructions, on aarch64, one run per cell, on a toy workload. The
  collector and nursery effects are large enough to survive these caveats; the A/B/C ordering within 15% is
  not.
- nano-lean and rpylean's general checker were not read beyond their READMEs/headers.
- Arena virtual times are the figures given in the project brief, not re-derived.

Open questions that need a spike before the core is frozen:

1. Int-id arena vs Java objects for values on GraalVM (total CPU incl. GC, same algorithm).
2. Strict vs hash-consed-thunk arguments on a Mathlib sample (the `arg` switch of 1.3).
3. Simple HOLE-padded pruning vs sokonanoda's mask+slot frames.
4. (level, type) fresh variables vs gruszka's depth-independent naming: cache hit rates.
5. Whether design E is needed at all for the arena's test set, and if so whether a Truffle AST/Bytecode-DSL
   interpreter beats a plain Java machine once JIT threads are charged.
