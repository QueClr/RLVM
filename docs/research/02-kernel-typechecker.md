# 02 - Official Lean 4 kernel type checker core (v4.36.0): implementation-grade specification

Scope: everything in the official C++ kernel except declaration admission (inductive checking,
recursor generation, replay order), which the declaration-admission document covers. All paths are relative to
`/home/queclr/Documents/rabdos-lean-vm/refs/`. Line numbers refer to `lean4` at tag `v4.36.0`
(commit `611c117`). "throw" always means a kernel exception, i.e. the whole declaration is rejected.

Notation: `==` structural equality (binder names/infos ignored, see 2.3); `=p` pointer equality;
`whnf_core(e, cheap_proj)` = `whnf_core(e, cheap_rec=false, cheap_proj)`.

## 0. Sources and version facts (read first)

| Source | What it is |
|---|---|
| `lean4/src/kernel/{type_checker,level,expr,instantiate,abstract,replace_fn,expr_eq_fn,local_ctx,quot,declaration,environment}.{h,cpp}`, `inductive.h` | the specification |
| `lean4/src/LeanChecker.lean`, `git show v4.36.0:src/Lean/{Replay,Declaration,Expr,Level}.lean` | how the arena's "official" checker drives the kernel (`leanchecker --from-export`) |
| `lean4lean/divergences.md`, `lean4lean/Lean4Lean/TypeChecker.lean` (toolchain v4.36.0) | independent Lean re-implementation, lists every deliberate difference |
| `lean-kernel-arena/tests/{bugs,corner-cases,other,perf}/*.yaml`, `tutorial/Tutorial.lean` | the tests RLVM is ranked on |

Version facts, verified by diffing the tags `v4.30.0`, `v4.33.0`, `v4.35.0`, `v4.36.0` and master (`7e19442`, 2026-11-23):

- Arena tests are generated with toolchain v4.36.0 (`lean-kernel-arena/tests/lean-toolchain`); the arena's
  `official` checker is v4.35.0. `git diff v4.35.0 v4.36.0 -- src/kernel` is deletions of dead code only,
  so 4.35 and 4.36 behave identically.
- v4.30 -> v4.33: `is_prop` tests the level with `normalizes_to_zero` instead of `== Prop` (PR 14613);
  projection index bounded to 32 bits (`to_proj_idx`); `reduce_proj_core` and proj/proj comparison check the
  structure name (PR 14631/14632); local context extended only after the binder domain is checked.
- v4.33 -> v4.35: (a) `Lean.reduceBool`/`Lean.reduceNat` native reduction REMOVED from the kernel;
  (b) 128 MB bound on Nat numerals, `Nat.pow` now throws instead of staying stuck above 2^24 (PR 14849);
  (c) `is_prop` throws if the type's type does not reduce to a sort (PR 14807/14843);
  (d) the union-find `equiv_manager` was REPLACED by a plain set of successful pairs (PR 14806).
  Anything you read elsewhere about `equiv_manager`, `reduce_native` or `ReducePowMaxExp` is obsolete.
- master after v4.36 (section 12; the arena also lists an `official-nightly` checker, nightly-2026-11-14,
  which may already contain these, not verified): proof irrelevance moved before `whnf_core`; struct-eta on
  recursor majors decided by large elimination instead of `is_prop`; proof majors no longer whnf'ed;
  `try_eta_struct` eta-expands partially applied constructors.

## 1. Decision summary for RLVM

1. `is_def_eq` is a sound, incomplete, order-sensitive semi-decision procedure. Mathlib is only known to
   check under the official step order (section 8) with the exported reducibility hints. Reproduce the order
   of *strategy* decisions (which side to unfold, when to compare arguments, when to fold Nat literals);
   being *more complete* (accepting more true equalities) is safe, being less complete rejects Mathlib.
2. Soundness-critical restrictions that must be reproduced exactly: projection restrictions on Prop
   structures (6.8), `is_prop` hard failure (6.10), K-like reduction conditions (7.4), struct-eta conditions
   (7.4, 8.6), unit-like conditions (8.8), level arity checks, loose-bvar rejection, literal semantics.
3. Reducibility hints come from the export and are used verbatim by the official checker; they only steer
   lazy delta. Do not recompute heights. All inductive-derived data (`numFields`, `k`, `isRec`, rules, ...)
   is recomputed by the official kernel and must not be trusted (section 9).
4. Seven maps exist (section 5): infer x2 (check / infer-only), whnf_core, whnf, def-eq success pairs,
   same-head-args failure pairs, unfold (instantiated values). All are keyed by structural expression equality and
   live for one declaration. There is NO union-find and NO general failure cache in v4.36.
5. Level equality is `l1 == l2 || normalize(l1) == normalize(l2)` with an imax normal form that is NOT
   canonical. A complete semantic comparison is sound and accepts a superset; use the official normal form
   as fast path only (section 3.6).
6. Theorems are delta-unfoldable (hint "opaque" for ordering purposes only); `opaque` constants never unfold.
7. The Nat GMP extension covers exactly 15 functions (7.6); in `is_def_eq` it only fires on fvar-free
   terms (or under `eagerReduce`), in `whnf` it fires always. Let-bound variables are fvars, which is why
   `let` bodies are not eagerly evaluated.
8. A stuck projection/recursor keeps its *unreduced* form (`whnf_core` returns the original `e`), and
   def-eq first runs `whnf_core` with `cheap_proj=true`. Both are needed to keep folded heads for the
   argument-comparison shortcut (sections 7.2, 11).

## 2. Data model and invariants

### 2.1 Level (`lean4/src/kernel/level.h:30`)
`Zero | Succ l | Max l l | IMax l l | Param name | MVar name` (enum order matters, 3.5). Cached per node:
32-bit hash, `depth` (zero/param = 0, succ = d+1, max/imax = max(d1,d2)+1; panic if > 2^24-1,
`level.cpp:42-50`), `hasParam`, `hasMVar`. `l1 == l2` is purely structural (`level.cpp:118-143`). MVar never
occurs in checked declarations.

### 2.2 Expr (`lean4/src/kernel/expr.h:68-82`)
`BVar idx | FVar name | MVar name | Sort level | Const name levels | App f a | Lambda n bi dom body |
Pi n bi dom body | Let n type value body nondep | Lit (Nat bignum | String) | MData kv e | Proj sname idx e`.

Cached 64-bit data word per node (`expr.cpp:96-117`, `git show v4.36.0:src/Lean/Expr.lean` l.119-127, 473-513):
hash (32 bits), approxDepth (8 bits, saturating at 255), hasFVar, hasExprMVar, hasLevelMVar, hasLevelParam,
looseBVarRange (20 bits; panic "too many bound variables" above 1048575).
- `looseBVarRange`: `bvar i` -> i+1; binders -> `max(range(dom), range(body)-1)`; let ->
  `max(range(type), range(value), range(body)-1)`; app -> max; const/sort/fvar/lit -> 0.
  `has_loose_bvars(e)` = range > 0 is O(1) and is used as a fast exit everywhere.
- `hasFVar` is true for let-bound fvars too.
- The hash ignores binder names, binder infos, the mdata payload and the let `nondep` flag.

### 2.3 Structural equality `==` (`expr_eq_fn.cpp:44-113`, `expr_eq_fn<false>`)
Pointer-equal -> true; hash differs -> false; kind differs -> false; then by kind. Binder names and binder
infos are NOT compared; mdata payload and let `nondep` ARE compared; const compares name and levels with
level `==`; Lit compares values. Shared sub-pairs (RC > 1) are memoised per call. Every `expr_map` cache
and every `t == s` in the type checker uses this relation.

### 2.4 Invariants the type checker enforces or assumes
- Every expression given to `infer_type_core` is closed w.r.t. bound variables: `has_loose_bvars(e)` ->
  throw "type checker does not support loose bound variables" (`type_checker.cpp:326-327`). Binders are
  opened with fresh fvars (locally nameless). Arena test `bugs/loose-bvar-value`.
- No metavariables (`type_checker.cpp:342`); declarations are pre-checked for mvars and fvars
  (`environment.cpp:86-99`).
- fvar names come from one counter per `type_checker::state` (`_kernel_fresh.N`), are never reused, and
  each has a fixed type (and optional value) in `m_lctx`. This is the soundness argument for keying all
  caches on open terms (comment at `type_checker.h:33-39`; arena `bugs/open-term-cache-context`).
- `is_def_eq`, `whnf`, and `infer_type` in infer-only mode assume well-typed inputs. Never run them on
  unchecked export data (arena `bugs/rec-rule-proof-irrel`: proof irrelevance on an unchecked term).

## 3. Levels

### 3.1 Smart constructors (`level.cpp:52-116`)
```
is_explicit(l)   := l = succ^k zero
to_offset(l)     := (base, k) with l = succ^k base and base not a succ
is_not_zero(l)   := Zero,Param -> false | Succ -> true | Max a b -> nz(a) || nz(b) | IMax a b -> nz(b)
normalizes_to_zero(l) := Zero -> true | Param,Succ -> false | Max a b -> ntz(a) && ntz(b) | IMax a b -> ntz(b)

mk_max(a, b):
  if explicit(a) && explicit(b): return depth(a) >= depth(b) ? a : b
  if a == b: return a
  if a is Zero: return b
  if b is Zero: return a
  if b = Max x y and (x == a or y == a): return b
  if a = Max x y and (x == b or y == b): return a
  (ba,ka) = to_offset(a); (bb,kb) = to_offset(b)
  if ba == bb: return ka > kb ? a : b
  return Max a b

mk_imax(a, b):
  if is_not_zero(b): return mk_max(a, b)
  if b is Zero: return Zero
  if a is Zero or a == succ Zero: return b
  if a == b: return a
  return IMax a b
```
`mk_succ` never simplifies. Levels arriving from the export are NOT run through these constructors; only
levels built by the kernel are (`infer_pi`, `instantiate`, `normalize`).

### 3.2 `is_not_zero` vs `normalizes_to_zero`
`is_not_zero(l)`: l is nonzero under EVERY assignment (false for params). `normalizes_to_zero(l)`: l is zero
under every assignment and syntactically so (false for params). A bare `Param u` satisfies neither.
Arena `bugs/large-elim-param` breaks checkers where `is_not_zero(Param) = true`.

### 3.3 Instantiating level parameters (`level.cpp:277-300`, `261-275`)
```
instantiate(l, ps, ls):                      # ps names, ls levels, same length
  if !hasParam(l): return l
  Param n      -> ls[i] if ps[i] == n for the first such i, else l   # unknown params stay
  Succ a       -> a' =p a ? l : Succ a'
  Max a b      -> (a' =p a && b' =p b) ? l : mk_max(a', b')          # SIMPLIFYING constructor
  IMax a b     -> (a' =p a && b' =p b) ? l : mk_imax(a', b')
```
Consequence: instantiation is not plain substitution. `imax u v [v := succ w]` becomes `max u' (succ w)`,
`imax u v [v := 0]` becomes `0`. Because 3.5 is not canonical, a checker that substitutes without these
simplifications and then uses the official `normalize` can fail on pairs the official kernel accepts.

### 3.4 Where levels are compared, and how
| Site | Relation | Ref |
|---|---|---|
| `Sort l1 =?= Sort l2` | `is_equivalent` | `type_checker.cpp:806-807` |
| const/const level lists (same-head shortcut, bare constants) | pairwise `is_equivalent`, equal length | `785-795`, `1001`, `1166-1167` |
| `t == s`, all cache keys, `f == Nat.add` tests | syntactic `==` | 2.3 |
| "is this a Prop" (`is_prop`, K target, struct-eta guard) | `normalizes_to_zero` | `379-385` |
| number of levels of a constant vs its lparams | exact length | `101-108`, `551-559`, `inductive.h:103` |
| constructor-field universe <= result universe, elimination | `is_geq`, `is_not_zero` (admission only) | `inductive.cpp:470,280` |
There is no cumulativity: `Sort u` vs `Sort v` is always an equality test.

### 3.5 `normalize`, `is_equivalent`, `is_geq` (`level.cpp:355-504`)
```
is_norm_lt(a, b):                       # total order used for sorting max-arguments
  if a =p b: return false
  (l1,k1) = to_offset(a); (l2,k2) = to_offset(b)
  if l1 != l2:
     if kind(l1) != kind(l2): return kind(l1) < kind(l2)   # Zero < Succ < Max < IMax < Param < MVar
     Param/MVar: return name(l1) < name(l2)                # lexicographic name order
     Max/IMax:   return lhs(l1) != lhs(l2) ? is_norm_lt(lhs(l1), lhs(l2)) : is_norm_lt(rhs(l1), rhs(l2))
  else return k1 < k2

normalize(l):
  (r, k) = to_offset(l)
  case r of
   Zero, Param: return l
   IMax a b:    return succ^k( mk_imax(normalize(a), normalize(b)) )      # offset stays OUTSIDE
   Max _ _:
     todo = flatten_max(r)                                  # all leaves of nested Max
     args = concat [ flatten_max(normalize(x)) | x in todo ]
     sort args by is_norm_lt                                # explicit levels first, same bases adjacent
     i = 0
     if explicit(args[0]):
        while i+1 < n && explicit(args[i+1]): i++           # args[i] = largest explicit, offset K
        if exists j > i with offset(args[j]) >= K: i++      # explicit subsumed by some succ^K'(x), K' >= K
     out = [args[i]]; prev = to_offset(args[i]); i++
     for ; i < n; i++:
        cur = to_offset(args[i])
        if cur.base == prev.base: (if prev.k < cur.k: replace last of out by args[i]; prev = cur)
        else: out.push(args[i]); prev = cur
     out = [ succ^k(x) | x in out ]                         # offset DISTRIBUTED over max
     return mk_max(out[0], mk_max(out[1], ... mk_max(out[m-2], out[m-1])))   # right fold

is_equivalent(a, b) := a == b || normalize(a) == normalize(b)

is_geq(a, b) := is_geq_core(normalize(a), normalize(b))
is_geq_core(l1, l2):                    # every recursive call below is is_geq (re-normalizes)
  if l1 == l2 or l2 is Zero: return true
  if l2 = Max x y:  return is_geq(l1, x) && is_geq(l1, y)
  if l1 = Max x y and (is_geq(x, l2) or is_geq(y, l2)): return true
  if l2 = IMax x y: return is_geq(l1, x) && is_geq(l1, y)
  if l1 = IMax x y: return is_geq(y, l2)
  (b1,k1) = to_offset(l1); (b2,k2) = to_offset(l2)
  if b1 == b2 or b2 is Zero: return k1 >= k2
  if k1 == k2 and k1 > 0:    return is_geq(b1, b2)
  return false
```

### 3.6 Pitfalls (all verified by hand-evaluating the code above)
- `bugs/level-imax-normalization`: `normalize(imax 0 v) = v` but `normalize(succ(imax 0 v)) = succ v`. The
  successor offset is re-applied around whatever `mk_imax` returns; a normaliser that decomposes
  `imax u (param v)` and drops the pending offset equates them and proves False.
- `bugs/level-imax-leq`: `is_geq(imax u v, succ(imax u v))`: not equal, rhs not max/imax, lhs is imax ->
  `is_geq(v, succ(imax u v))` -> offsets (v,0) vs (imax u v,1), bases differ, k differ -> false. A leq that
  compares the imax arguments structurally while ignoring an accumulated offset returns true.
- The normal form is not canonical under imax. `normalize(succ(imax u 1)) = succ(max u 1)` (the max produced
  by `mk_imax` is neither sorted nor distributed) while `normalize(succ(max u 1)) = max 2 (succ u)`. So the
  official kernel REJECTS `corner-cases/imax-right-successor` and `corner-cases/imax-nested-absorption`
  (`imax u v` vs `imax (imax u v) v`); both tests have outcome "either".
- Recommendation: compare with the official algorithm first (cheap, interned), and on mismatch fall back to
  a complete procedure (case split each parameter occurring on an imax right-hand side into `0` and
  `succ p`, then compare max-plus normal forms). lean4lean does exactly this (`lean4lean/divergences.md`,
  "normalize'" entry) and therefore "accepts declarations the kernel rejects ..., never the other way".
- Good cases every checker must accept (`tutorial/Tutorial.lean:90-186`): `Sort (imax 1 0) : Sort 1`,
  `imax 0 1`, `imax 2 1`, `imax u 0 = 0`, `imax u u = u`, max commutativity, associativity, idempotence,
  absorption `max u (max u v) = max u v`.

## 4. Expression helpers

All traversals go through `replace(e, f)` (`replace_fn.cpp:16-84`): pre-order, `f(node, binder_offset)`
may return a replacement; per-call memo keyed by (node pointer, offset) for nodes with RC != 1; unchanged
children keep pointer identity (`update_*` in `expr.cpp:263-317`).

| Function | Exact semantics | Ref |
|---|---|---|
| `instantiate(e, s, n, subst)` | loose `bvar (s+i)` -> `subst[i]` for i < n (lifted by the local binder offset), loose bvars >= s+n lowered by n. Fast exit if `s >= range(e)` or n = 0. | `instantiate.cpp:15-37` |
| `instantiate(e, v)` | `instantiate(e, 0, 1, [v])` | `:41` |
| `instantiate_rev(e, n, subst)` | loose `bvar i` -> `subst[n-1-i]` (the last pushed fvar is bvar 0); bvars >= n lowered by n | `:98-117` |
| `abstract(e, n, fvars)` | fvar equal to `fvars[i]` (searched from the last) -> `bvar (offset + n-1-i)`. Fast exit if `!hasFVar` | `abstract.cpp:14-32` |
| `lift_loose_bvars(e, s, d)` / `lower_loose_bvars(e, s, d)` | loose bvars >= s get +d / -d (lower requires s >= d) | `expr.cpp:373-423` |
| `has_loose_bvar(e, i)` | pruned by the cached range | `expr.cpp:345-365` |
| `cheap_beta_reduce(e)` | if `e = (fun x1..xk => b) a1..an`: strip i = min(k,n) lambdas; if `b` has no loose bvars -> `b a_{i+1}..a_n`; if `b` is `bvar j` -> `a_{i-j} a_{i+1}..a_n`; otherwise return `e` unchanged | `instantiate.cpp:195-214` |
| `instantiate_lparams(e, ps, ls)` | on nodes with `hasLevelParam`: Const -> levels mapped with 3.3; Sort -> 3.3 | `:216-230` |
| `instantiate_type_lparams(info, ls)` | panic if arity differs; returns the stored type unchanged if `ls` empty or type has no params; no cache of its own (the infer cache keyed on the `Const` node covers it) | `:232-238` |
| `instantiate_value_lparams(info, ls)` | same for the value; cached by `m_unfold` (5) | `:240-248` |
| `local_ctx::mk_pi(fvars, body, remove_dead_let)` | `abstract` all fvars at once, then wrap from the last: let-decl fvar -> `Let` binder (dropped via `lower_loose_bvars` if `remove_dead_let` and unused), else `Pi` with the decl's name/info and type abstracted over the earlier fvars | `local_ctx.cpp:66-98` |

Head beta inside `whnf_core` (`type_checker.cpp:507-520`): with spine `f a1..an` and `f` reduced to a lambda,
consume m = min(#leading syntactic lambdas, n) arguments with ONE `instantiate(body, [a_m..a_1])`, re-apply
the rest, recurse. It does not look through `Let` or `MData` when counting lambdas.

## 5. Type checker state and caches (`type_checker.h:25-49`)

One `type_checker` (and so one cache set) per declaration: `environment.cpp:159-222` builds a fresh checker
for each definition/theorem/opaque/axiom; type and value share it. Theorem type and value are hash-consed
first (`sharecommon_persistent_fn`, `environment.cpp:196-198`).

| Cache | Key -> value | Written | Read | Soundness condition |
|---|---|---|---|---|
| `m_infer_type[0]` (check mode) | expr -> type | end of every `infer_type_core(e, false)` | check mode only | term was fully checked |
| `m_infer_type[1]` (infer-only) | expr -> type | end of every `infer_type_core(e, true)` | infer-only only | valid only for well-typed terms; never satisfies a check-mode request |
| `m_whnf_core` | expr -> expr | only when `cheap_rec == cheap_proj == false`, and not on the two early `return`s of the App case (iota result, stuck app) | every non-trivial `whnf_core` call, cheap or not | a full result is a legal answer to a cheap request |
| `m_whnf` | expr -> expr | every non-trivial `whnf` | same | fvar values fixed per fvar |
| `m_success` | unordered pair (ordered by hash; both orders probed on a hash tie) | `is_def_eq` (public wrapper) returning true | `quick_is_def_eq` | exact pair only, no transitive or congruence closure |
| `m_failure` | unordered pair | only the failed same-head argument comparison in `lazy_delta_reduction_step` | only there | skipping the shortcut is always safe |
| `m_unfold` | `Const c us` expr (only if `us` non-empty) -> instantiated value | `unfold_definition_core` | same | pure function of (c, us) |

Why not union-find: `is_def_eq` is not transitive (arena `corner-cases/alg-conv-trans-*`); the old
`equiv_manager` closed successes transitively but consulted the closure only for hash-equal terms, so a
comparison's outcome depended on hashes of surrounding terms, and recursor generation could be made to
disagree with itself (`bugs/rec-missing-ih`, `bugs/proj-of-stuck-prop`). The local context `m_lctx` is saved
and restored around binder traversals (`flet`), the caches are not.

## 6. infer_type

Entry points: `infer(e)` = `infer_type_core(e, infer_only=true)`; `check(e, lparams)` =
`infer_type_core(e, false)` with `m_lparams = lparams` (`type_checker.cpp:360-372`).

### 6.1 Dispatcher (`:325-358`)
```
infer_type_core(e, infer_only):
  if has_loose_bvars(e): throw
  if cache[infer_only] has e: return it
  r = case e of
    Lit      -> infer_lit(e)                                  # 6.9
    MData    -> infer_type_core(inner, infer_only)
    Proj     -> infer_proj(e, infer_only)
    FVar     -> type from m_lctx, else throw "unknown free variable"
    MVar     -> throw
    BVar     -> unreachable (caught by the loose bvar test)
    Sort l   -> if !infer_only: check_level(l);  Sort (Succ l)            # Succ not simplified
    Const    -> infer_constant ; Lambda -> infer_lambda ; Pi -> infer_pi ; App -> infer_app ; Let -> infer_let
  cache[infer_only][e] = r; return r
ensure_sort_core(t, _): if t is Sort return t; t' = whnf(t); if t' is Sort return t' else throw "type expected"
ensure_pi_core(t, _):   same with Pi, throw "function expected"
check_level(l): if m_lparams set and l mentions a Param not in it: throw       (:85-91)
```

### 6.2 Const (`:101-123`)
`info = env.get(name)` (throw if unknown). `length(levels) != length(info.lparams)` -> throw, in BOTH modes.
Check mode only: throw if `info` is unsafe and the checker is not in unsafe mode; throw if `info` is a
`partial` definition and the checker is in `safe` mode; `check_level` on every level. Result:
`instantiate_type_lparams(info, levels)`.

### 6.3 Lambda (`:125-142`)
```
fvars = []; e = lam
while e is Lambda:
   d = instantiate_rev(dom(e), fvars)
   if !infer_only: ensure_sort_core(infer_type_core(d, false), d)
   fvars.push(fresh fvar : d)            # context extended AFTER the domain is checked
   e = body(e)
r = infer_type_core(instantiate_rev(e, fvars), infer_only)
r = cheap_beta_reduce(r)
return m_lctx.mk_pi(fvars, r)            # lctx restored on exit
```
The whole lambda telescope is opened once (one `instantiate_rev` per domain and one for the body).

### 6.4 Pi (`:144-166`)
Same telescope walk; for every domain `us.push(sort_level(ensure_sort_core(infer_type_core(d, infer_only), d)))`
(computed in both modes); body sort level `r`; result `Sort (mk_imax(us[0], mk_imax(us[1], ... mk_imax(us[n-1], r))))`
built from the inside out with the simplifying `mk_imax`.

### 6.5 App (`:173-206`)
```
check mode (one App node at a time, so every spine prefix gets its own cache entry):
   f_type = ensure_pi_core(infer_type_core(fn(e), false))
   a_type = infer_type_core(arg(e), false)
   d      = dom(f_type)
   ok = is_def_eq(a_type, d)             # with m_eager_reduce = true for the duration of this one call
                                         # iff arg(e) is `eagerReduce _ _` (Const eagerReduce applied to 2 args)
   if !ok: throw app type mismatch
   return instantiate(body(f_type), arg(e))
infer-only mode (whole spine, no checks):
   f_type = infer_type_core(head, true); j = 0
   for i in 0..n-1:
      if f_type is Pi: f_type = body(f_type)                         # syntactic, NOT instantiated yet
      else: f_type = ensure_pi_core(instantiate_rev(f_type, args[j..i))); f_type = body(f_type); j = i
   return instantiate_rev(f_type, args[j..n))
```

### 6.6 Let (`:208-230`)
```
while e is Let:
   type = instantiate_rev(let_type(e), fvars); val = instantiate_rev(let_value(e), fvars)
   if !infer_only:
      ensure_sort_core(infer_type_core(type, false), type)
      if !is_def_eq(infer_type_core(val, false), type): throw
   fvars.push(fresh let-fvar : type := val); e = let_body(e)
r = cheap_beta_reduce(infer_type_core(instantiate_rev(e, fvars), infer_only))
return m_lctx.mk_pi(fvars, r, remove_dead_let = true)
```
The body is checked with the variable as an fvar that carries its value (zeta happens lazily in
`whnf_core`, 7.1). The resulting type keeps a `let` binder for every let-variable the type mentions and
drops the others. `corner-cases/let-value-type-mismatch` (outcome "either"): official rejects.

### 6.7 MData
Transparent in `infer`, `whnf`, `whnf_core`; `quick_is_def_eq` on two MData nodes compares the inner terms.

### 6.8 Proj (`:237-292`) - soundness critical
```
infer_proj(proj S idx c, infer_only):
  type = whnf(infer_type_core(c, infer_only))        # in check mode the struct argument IS checked
  if idx does not fit in unsigned 32 bit: throw
  (I, args) = spine(type)
  if I not Const, or name(I) != S, or I not an inductive type: throw
  if #ctors(I) != 1 or len(args) != nparams(I) + nindices(I): throw
  r = instantiate_type_lparams(ctor(I), levels(I))
  for i < nparams:  r = whnf(r); if r not Pi: throw; r = instantiate(body(r), args[i])
  is_prop_type = is_prop(type)                         # 6.10, may throw
  for i < idx:
     r = whnf(r); if r not Pi: throw
     if has_loose_bvars(body(r)):                      # a later field type depends on field i
        if is_prop_type && !is_prop(dom(r)): throw
        r = instantiate(body(r), proj S i c)
     else r = body(r)
  r = whnf(r); if r not Pi: throw
  r = dom(r)
  if is_prop_type && !is_prop(r): throw
  return r
```
Rules encoded: (1) `S` must be the head of the struct's whnf'd type; (2) exactly one constructor (indices
and recursion are allowed); (3) out of a Prop-valued structure only proof fields may be projected, and no
earlier field on which a later field depends may be data. Tutorial `projProp1..6`, arena
`bugs/proj-non-structure`, `other/proj-of-prop`, `bugs/proj-of-imax-prop`, `perf/prop-struct-projections`
(the dependency test is the O(1) cached range of the body after the parameters were instantiated).
`corner-cases/proj-maybe-prop`: for `S.{u} : Sort u` official allows the projection (`is_prop` is false for
a bare param); lean4lean uses "not never-zero" instead and rejects; outcome "either".

### 6.9 Lit (`:315-321`)
Nat literal larger than the numeral bound (7.6) -> throw. Type is `Const Nat []` or `Const String []`; the
kernel does not check that these constants exist or have the expected shape.

### 6.10 `is_prop(e)` (`:379-385`)
`s = ensure_sort_core(infer_type(e))` (whnf; THROWS "type expected" if not a sort) then
`normalizes_to_zero(level(s))`. Two separate fixes are encoded: zero up to normalization (`Sort (imax 1 0)` is
Prop, `bugs/proj-of-imax-prop`) and hard failure on a stuck sort (`bugs/proj-of-stuck-prop`,
`bugs/proj-of-subst-prop`, `bugs/rec-of-subst-prop`). Never answer "not a proposition" for a type whose sort
does not reduce.

## 7. Reduction

### 7.1 `whnf_core(e, cheap_rec, cheap_proj)` (`:464-547`) - no delta, no Nat extension
```
case e of
  BVar, Sort, MVar, Pi, Const, Lambda, Lit -> return e
  MData -> return whnf_core(inner)
  FVar  -> if no value in m_lctx: return e            (else continue)
if m_whnf_core has e: return it                       # consulted for cheap calls too
case e of
  FVar (let-bound) -> return whnf_core(value)                           # zeta, not cached
  Proj  -> r = (m = reduce_proj(e)) ? whnf_core(m) : e                  # stuck: the ORIGINAL e, struct untouched
  Let   -> r = whnf_core(instantiate(body, value))                      # zeta by substitution
  App   -> f0 = head(e); f = whnf_core(f0)
           if f is Lambda: multi-argument beta (section 4), r = whnf_core(result)
           elif f == f0:   if (x = reduce_recursor(e)) return whnf_core(x) else return e      # not cached
           else:           r = whnf_core(f applied to the same args)
if !cheap_rec && !cheap_proj: m_whnf_core[e] = r
return r
```

### 7.2 Projections (`:416-450`)
```
reduce_proj(proj S idx c): if idx not 32-bit: none
   c' = cheap_proj ? whnf_core(c, cheap_rec, cheap_proj) : whnf(c)
   return reduce_proj_core(c', S, idx)
reduce_proj_core(c, S, idx):
   if c is a String literal: c = whnf(string_lit_to_constructor(c))
   (mk, args) = spine(c); if mk not a Const naming a constructor: none
   if induct(mk) != S: none
   return nparams(mk) + idx < len(args) ? args[nparams + idx] : none
```

### 7.3 Flags: where each mode is used
| Call site | cheap_rec | cheap_proj | Ref |
|---|---|---|---|
| `whnf` main loop | false | false | `:727` |
| first normalisation of both sides in `is_def_eq_core` | false | true | `:1151-1152` |
| after every delta step in lazy delta | false | true | `:979-1010` |
| `try_unfold_proj_app`, second normalisation in `is_def_eq_core` | false | false | `:949`, `:1181-1182` |
| `whnf_core_cheap` (true, true) | - | - | declared `type_checker.h:165`, no caller in v4.36 |
`cheap_proj` only changes how a projection's struct is normalised (no delta). With `cheap_rec=false` a
recursor's major premise is always normalised with full `whnf`, and quotient reduction always uses full `whnf`.

### 7.4 Recursor (iota) reduction (`inductive.h:74-119`, called from `type_checker.cpp:389-403`)
```
reduce_recursor(e): if quot initialised and (r = quot_reduce_rec(e)): return r     # 7.5, tried first
inductive_reduce_rec(e):
  rec = head Const naming a recursor, else none;  args = spine args
  major_idx = nparams + nmotives + nminors + nindices;  if major_idx >= len(args): none
  major = args[major_idx]
  if rec.k: major = to_cnstr_when_K(major)                 # BEFORE any reduction of the major
  major = whnf(major)
  if major is Nat lit n:   major = (n == 0) ? Const Nat.zero : App (Const Nat.succ) (lit (n-1))
  elif major is Str lit:   major = whnf(string_lit_to_constructor(major))
  else:                    major = to_cnstr_when_structure(major_induct(rec), major)
  rule = the rule of rec whose ctor name == head constant of major, else none
  if rule.nfields > #args(major): none
  if #levels(rec const) != #lparams(rec): none
  rhs = instantiate_lparams(rule.rhs, rec.lparams, levels)
  rhs = rhs  args[0 .. nparams+nmotives+nminors)           # params, motives, minors; indices are dropped
  rhs = rhs  (the LAST rule.nfields arguments of major)    # ctor params may differ in number (nested inductives)
  rhs = rhs  args[major_idx+1 ..]                          # extra arguments after the major
  return rhs

to_cnstr_when_K(e):                                         (inductive.h:28-48)
  T = whnf(infer_type(e)); if head(T) is not Const major_induct: return e
  c = (first ctor of the inductive){levels of head(T)} applied to the first nparams args of T
  if !is_def_eq(T, infer_type(c)): return e                 # indices must match definitionally
  return c

to_cnstr_when_structure(I, e):                              (inductive.h:60-72)
  if !is_non_rec_structure(I) or head(e) is ANY constructor constant: return e
  T = whnf(infer_type(e)); if head(T) is not Const I: return e
  if is_prop(T): return e                                   # no struct eta for proofs; is_prop may throw
  return I.mk{levels of head(T)} (first nparams args of T) (proj I 0 e) .. (proj I (nfields-1) e)

is_non_rec_structure(I) := I inductive && #ctors == 1 && nindices == 0 && !isRec     (inductive.cpp:28-33)
major_induct(rec) := head constant of the major premise's binder type in rec's type    (declaration.cpp:117-126)
```
K flag (recomputed at admission, `inductive.cpp:582-603`): not mutual, result sort `normalizes_to_zero`,
exactly one constructor, constructor has zero fields. Tutorial `ruleK` (accept), `ruleKbad`, `ruleKAcc`
(reject); arena `bugs/k-rec-conv`, `bugs/rec-k-lie`, `bugs/nat-rec-k-lie`, tutorial `accRecNoEta`.

### 7.5 Quotients (`quot.h:39-70`)
Only if the environment has Quot initialised. `Quot.lift`: major at arg 5, `f` at arg 3. `Quot.ind`: major
at arg 4, `f` at arg 3. Needs more than `mk_pos` args. `mk = whnf(args[mk_pos])` must be `Quot.mk` applied
to exactly 3 args. Result: `f (last arg of mk)` followed by the args after `mk_pos`.

### 7.6 `whnf` and the Nat extension (`:551-739`)
```
whnf(e): trivial for BVar, Sort, MVar, Pi, Lit; MData -> inner; FVar without value -> e
  if m_whnf has e: return it
  t = e
  loop: t1 = whnf_core(t)
        if (v = reduce_nat(t1)):          m_whnf[e] = v;  return v        # BEFORE delta, regardless of fvars
        elif (t2 = unfold_definition(t1)): t = t2
        else:                              m_whnf[e] = t1; return t1
is_delta(e): head is Const c, c in env, c is a definition or THEOREM (not opaque/axiom/ctor/rec/...),
             and #levels == #lparams                                              (:551-559, declaration.h:447)
unfold_definition(e): if is_delta: instantiate_value_lparams (via m_unfold) re-applied to the args; no beta here
```
`reduce_nat(e)` (`:668-699`). `lit_ext(x)` := x is a Nat literal or `x == Const Nat.zero []`. Function
constants are matched as `Const name []` (no levels). Arguments are normalised with full `whnf` and must
be `lit_ext`, otherwise the result is "none" and reduction continues with delta.

| Shape | Result | Guards (throw = declaration rejected) |
|---|---|---|
| `Nat.succ a` (exactly 1 arg) | `lit (a+1)` | size check on result |
| `Nat.add a b`, `Nat.sub a b`, `Nat.mul a b` | exact; sub truncates at 0 | size check on result |
| `Nat.pow a b` | `a^b` | throw if `b > 2^32-1` (even for a <= 1); throw if `a > 1 && b != 0 && bytes(a) > MAX / b` (integer division) |
| `Nat.gcd a b` | gcd (gcd 0 b = b) | none |
| `Nat.mod a b` | `a % b`, `a % 0 = a` | none |
| `Nat.div a b` | floor, `a / 0 = 0` | none |
| `Nat.beq a b`, `Nat.ble a b` | `Const Bool.true []` / `Const Bool.false []` | none |
| `Nat.land`, `Nat.lor`, `Nat.xor` | bitwise | none |
| `Nat.shiftLeft a b` | `a * 2^b`; `0 <<< b = 0` for ANY b | if a != 0: throw if `b > 2^32-1`; throw if `bytes(a) + b/8 + 1 > MAX` |
| `Nat.shiftRight a b` | `floor(a / 2^b)`; 0 for any huge b | none |
`MAX` = 128 MiB (134217728; env `LEAN_NAT_MAX_SIZE`), `bytes(n)` = 8 if n < 2^63, else 8 * ceil(bitlen/64)
(`:294-313`, `runtime/object.cpp:1671-1675`). Consequence: with a word-sized base >= 2 the exponent limit is
2^24. Not accelerated: `Nat.log2`, `Nat.blt`, `Nat.decEq`, `Nat.pred`, anything on `Int`.
Arena: `other/nat-lit-{add,sub,ble}`, `other/nat-lit-add-bad`, `bugs/nat-shift-huge` (shift counts >= 2^64
must not overflow or allocate), `perf/gf3-matrix-product` (600 kbit operands).

Literal facts: `Nat.zero` and `lit 0` are both normal forms (`whnf` does not rewrite one into the other);
`whnf(Nat.succ (lit 4)) = lit 5`; a literal is converted to constructor form one layer at a time and only
as a recursor major (7.4) or in `is_def_eq_offset` (8.4). Literals are never expanded to unary.

String literals: `string_lit_to_constructor("...")` = `String.ofList (List.cons.{0} Char (Char.ofNat (lit c1))
(... (List.nil.{0} Char)))` with one element per Unicode scalar value (UTF-8 decoded), `inductive.cpp:1356-1368`.
The constant is `String.ofList` (already so at v4.30; the book
`type_checking_in_lean4/src/type_checking/definitional_equality.md:181-187` still describes the older
`String.mk`). Used in `reduce_proj_core`, recursor majors, and 8.7. Arena `bugs/str-lit-utf8(-bad)`.

There is no other kernel extension in v4.36 (`Lean.reduceBool`/`reduceNat` are ordinary constants).

## 8. is_def_eq

`is_def_eq(t, s)` (`:1205-1210`) = `is_def_eq_core(t, s)`, and on true `m_success.insert({t, s})`.
Calls marked CORE below bypass the success insertion.

### 8.1 Exact step order of `is_def_eq_core(t, s)` (`:1128-1203`)
```
 1. r = quick_is_def_eq(t, s);  if r != undef: return r
 2. if (!hasFVar(t) || m_eager_reduce) && s == Const Bool.true:          # only s on the RIGHT is tested
        if whnf(t) is Const Bool.true: return true                       # otherwise fall through
 3. tn = whnf_core(t, cheap_proj=true); sn = whnf_core(s, cheap_proj=true)
 4. if tn !=p t || sn !=p s:  r = quick_is_def_eq(tn, sn); if r != undef: return r
 5. r = is_def_eq_proof_irrel(tn, sn); if r != undef: return r           # definitive true AND false
 6. r = lazy_delta_reduction(tn, sn);  if r != undef: return r           # updates tn, sn in place
 7. if tn, sn both Const, same name, levels pairwise is_equivalent: return true
 8. if tn, sn both FVar with the same name: return true
 9. if tn = proj S i a, sn = proj S i b (same S, same i):
        if lazy_delta_proj_reduction(a, b, S, i): return true            # false -> continue below
10. tnn = whnf_core(tn); snn = whnf_core(sn)                             # full: struct of a proj via whnf
    if tnn !=p tn || snn !=p sn: return is_def_eq_core(tnn, snn)         # CORE, restart
11. if is_def_eq_app(tn, sn): return true
12. if try_eta_expansion(tn, sn): return true                            # both directions
13. if try_eta_struct(tn, sn): return true                               # both directions
14. r = try_string_lit_expansion(tn, sn); if r != undef: return r
15. if is_def_eq_unit_like(tn, sn): return true
16. return false
```

### 8.2 `quick_is_def_eq(t, s)` (`:798-822`)
`t == s` or `{t,s} in m_success` -> true. Otherwise if kinds are equal: Lambda/Pi -> `is_def_eq_binding`
(definitive); Sort -> `is_equivalent` (definitive); MData -> `is_def_eq(inner, inner)` (definitive);
Lit -> literal values equal (definitive, so two different literals are immediately unequal). Everything
else -> undef.
```
is_def_eq_binding(t, s):          # both Lambda or both Pi                    (:748-775)
  subst = []
  repeat:
     ds = none
     if dom(t) != dom(s):                                    # syntactic test on the UNinstantiated domains
        ds = instantiate_rev(dom(s), subst)
        if !is_def_eq(instantiate_rev(dom(t), subst), ds): return false
     if has_loose_bvars(body(t)) || has_loose_bvars(body(s)):
        subst.push(fresh fvar : (ds or instantiate_rev(dom(s), subst)))      # typed with s's domain
     else subst.push(dummy constant)                         # no fvar allocated for non-dependent binders
     t = body(t); s = body(s)
  while t and s both still have the original kind
  return is_def_eq(instantiate_rev(t, subst), instantiate_rev(s, subst))
```

### 8.3 Proof irrelevance (`:895-902`)
`T = infer_type(tn)`; if `!is_prop(T)` -> undef; else return `is_def_eq(T, infer_type(sn))` as the final
answer. `is_prop` whnf's the sort (`proofIrrelevanceWhnf`: `p : id Prop`). A false answer here ends the
comparison (`perf/irrelevance-refutes`). It runs after cheap `whnf_core` and before any delta.

### 8.4 Lazy delta reduction (`:962-1071`)
```
lazy_delta_reduction(tn, sn): loop
   r = is_def_eq_offset(tn, sn); if r != undef: return r
   if (!hasFVar(tn) && !hasFVar(sn)) || m_eager_reduce:
       if (v = reduce_nat(tn)): return is_def_eq_core(v, sn)            # CORE
       elif (v = reduce_nat(sn)): return is_def_eq_core(tn, v)          # CORE
   case lazy_delta_reduction_step(tn, sn) of Continue -> loop | Unknown -> return undef | Equal -> true | Diff -> false

is_def_eq_offset(t, s):                                                 (:1021-1048)
   zero(x) := x == Const Nat.zero || x is lit 0
   pred(x) := lit n, n > 0 -> lit (n-1) | `Nat.succ a` (exactly 1 arg) -> a | else none
   if zero(t) && zero(s): true
   if pred(t) and pred(s) both exist: return is_def_eq_core(pred(t), pred(s))    # CORE, definitive
   undef

lazy_delta_reduction_step(tn, sn):
   dt = is_delta(tn); ds = is_delta(sn);  unfold(x) := whnf_core(unfold_definition(x), cheap_proj=true)
   neither:   return Unknown
   only dt:   if head(sn) is a Proj and whnf_core(sn) != sn: sn = whnf_core(sn)       # full whnf_core
              else tn = unfold(tn)
   only ds:   symmetric
   both:      c = compare(hints(dt), hints(ds))
              c < 0: tn = unfold(tn);  c > 0: sn = unfold(sn)
              c == 0:
                 if tn, sn both App && dt, ds are the same constant && hints regular && {tn,sn} not in m_failure:
                     if levels pairwise is_equivalent && is_def_eq_args(tn, sn): return Equal
                     else m_failure.insert({tn, sn})
                 tn = unfold(tn); sn = unfold(sn)
   case quick_is_def_eq(tn, sn) of true -> Equal | false -> Diff | undef -> Continue

compare(h1, h2):                                                        (declaration.cpp:19-44)
   same kind:  regular: equal heights -> 0; h1 higher -> -1 (unfold t); else +1 (unfold s)
               opaque/opaque or abbrev/abbrev -> 0 (unfold both)
   different:  h1 opaque -> +1; h2 opaque -> -1; h1 abbrev -> -1; h2 abbrev -> +1
hints(c) := the definition's hints; every non-definition (theorems!) counts as opaque  (declaration.cpp:219-224)

is_def_eq_args(t, s): compare arguments from the LAST to the first with is_def_eq; arity must be equal  (:826-834)
```
So: the higher definition is unfolded first; abbrevs before regulars; a theorem (or a definition whose hint
is `opaque`) is unfolded only when the other side is not delta or also counts as opaque; the argument
shortcut applies only when both heads are the same *regular* definition and both sides are applications.

### 8.5 Projections (`:1080-1097`)
```
lazy_delta_proj_reduction(a, b, S, i): loop
   case lazy_delta_reduction_step(a, b) of
     Continue -> loop;  Equal -> return true
     Unknown, Diff:
        if reduce_proj_core(a, S, i) = x and reduce_proj_core(b, S, i) = y: return is_def_eq_core(x, y)
        return is_def_eq_core(a, b)
```
The structs are unfolded lazily and in lockstep; as soon as neither unfolds further (or they are known
different) the fields are compared if both are constructor applications, else the structs themselves
(`perf/proj-lazy-struct`, `perf/proj-cheap-struct`). Applied projections `a.i x =?= b.i y` do not take this
path; they reach step 10 and are fully reduced (`perf/proj-app-lazy-delta`, leanprover/lean4#16613).

### 8.6 Application congruence and eta (`:837-891`)
```
is_def_eq_app(t, s): both App; is_def_eq(head t, head s) && same arity && args pairwise is_def_eq, FIRST to last
try_eta_expansion_core(t, s): t is Lambda, s is not:
    T = whnf(infer_type(s)); must be Pi; return is_def_eq(t, Lambda (name,info,dom of T) (App s (bvar 0)))
try_eta_struct_core(t, s):      # s must be a fully applied constructor
    head(s) = Const ctor; #args(s) == nparams + nfields; is_non_rec_structure(induct(ctor))
    is_def_eq(infer_type(t), infer_type(s))
    for each field i: is_def_eq(proj I i t, args(s)[nparams + i])
```
Eta is only attempted when exactly one side is a lambda after normalisation; partially applied constructors
and recursors are not eta-expanded (`corner-cases/eta-ctor`, `eta-rule-k`, `eta-rec-struct`: "either";
`bugs/eta-ctor-alias`: must reject).

### 8.7 String literal expansion (`:1100-1113`)
If one side is a String literal and the other is `App (Const String.ofList []) x`:
return `is_def_eq_core(whnf(string_lit_to_constructor(lit)), other)` as the final answer.

### 8.8 Unit-like types (`:1116-1126`)
`T = whnf(infer_type(tn))`, head must be a `is_non_rec_structure` inductive whose single constructor has
0 fields; then return `is_def_eq_core(T, infer_type(sn))`. Indexed families and structures with fields are
excluded (tutorial `unitEta1-3` accept, `indexedUnitEta` reject; `corner-cases/eta-unit-like-*` "either";
`bugs/ctor-num-fields`: `numFields` must not be trusted from the export).

### 8.9 `eagerReduce`
`m_eager_reduce` is true only while comparing the type of an argument of the form `eagerReduce _ _` with
the expected domain (6.5). It lifts the `hasFVar` guards of step 2 and of the Nat folding in 8.4. It does
not change `whnf`.

### 8.10 Exceptions raised inside reduction and def-eq
`whnf`, `infer` and `is_def_eq` can throw from deep inside a comparison, and nothing catches it: `is_prop`
on a non-sort ("type expected"), `infer_proj` failures and "function expected" from infer-only inference
(proof irrelevance, eta, unit-like and K all call `infer`), the Nat numeral bounds of 7.6, unknown constants
or fvars, and stack exhaustion ("deep recursion was detected"; replay calls `addDeclCore 0 0`, i.e. no
recursion-depth limit, so only the native stack bounds it). Each of them rejects the declaration; none may
be turned into "not definitionally equal, try the next rule".

## 9. What may be trusted from the export

`leanchecker --from-export` parses the export into `ConstantInfo`s and calls
`Kernel.Environment.replay` (`git show v4.36.0:src/Lean/Replay.lean`): declarations are added in dependency
order with `addDeclCore 0 0` (no heartbeat limit, no recursion-depth limit), unsafe and partial constants
are skipped, constructors and recursors are not sent to the kernel but compared for equality afterwards
with the ones the kernel generated, and the `Quot` constants are regenerated and compared
(`lean4/src/LeanChecker.lean:60-88`).

| Export field | Official treatment | RLVM |
|---|---|---|
| `hints` (opaque / abbrev / regular h) of a definition | passed through unchanged, never recomputed (`Replay.lean` `defnDecl info`; `environment.cpp:159-189` has no height computation) | use verbatim; affects strategy only (8.4). A wrong hint can cost time, not soundness |
| definition/theorem/opaque value and type, level params | fully checked | check |
| `safety`, `all` of definitions | unsafe/partial skipped; `all` unused | - |
| inductive `numParams`, types, ctor types | input to admission | check (admission document) |
| `numIndices`, `isRec`, `isReflexive`, `numNested`, ctor `numParams/numFields/cidx/induct`, recursor `numParams/numIndices/numMotives/numMinors/k/rules/type` | recomputed; exported values must be identical | recompute; the core reads these from the recomputed environment (7.2, 7.4, 8.6, 8.8) |
| `Quot`, `Quot.mk`, `Quot.lift`, `Quot.ind` | regenerated (`quot.cpp:19-104`), needs `Eq` of the expected shape | regenerate and compare (`bugs/quot-bad-type`) |
| primitives `Nat`, `Nat.add` ..., `String.ofList`, `Char.ofNat`, `List.cons/nil`, `Bool.true/false`, `eagerReduce` | NOT validated (`lean4lean/divergences.md`, first entry: "required for soundness, but Lean ... ships its prelude") | optional hardening as in `lean4lean/Lean4Lean/Primitive.lean`; see section 13 |

## 10. Subtle points where independent implementations diverge

Direction: A = accepts a wrong proof if violated, R = rejects valid terms if violated.

| # | Rule | Source | Arena / tutorial test | Dir |
|---|---|---|---|---|
| 1 | `normalize(succ^k(imax a b)) = succ^k(mk_imax(..))`; never drop the offset | `level.cpp:423-427` | `bugs/level-imax-normalization` | A |
| 2 | `is_geq` with imax: rhs imax needs both parts, lhs imax uses only its rhs, offsets compared last | `level.cpp:483-501` | `bugs/level-imax-leq` | A |
| 3 | `is_not_zero(Param) = false` | `level.cpp:149-161` | `bugs/large-elim-param` | A |
| 4 | Prop-ness is tested up to level normalisation (`imax 1 0`) | `type_checker.cpp:379-385` | `bugs/proj-of-imax-prop` | A |
| 5 | `is_prop` throws when the sort is stuck; never "not Prop" | `:380` | `bugs/proj-of-stuck-prop`, `proj-of-subst-prop`, `rec-of-subst-prop` | A |
| 6 | Projection out of a Prop structure: target field and every depended-upon earlier field must be proofs | `:274-290` | tutorial `projProp1-6` | A/R |
| 7 | Projection requires exactly one constructor, name match, 32-bit index, full arity of the type | `:249-264` | `bugs/proj-non-structure` | A |
| 8 | In check mode the projected struct is checked, not inferred | `:248` | `other/proj-of-prop` | A |
| 9 | `reduce_proj_core` refuses a constructor of another inductive | `:427-431` | - | A |
| 10 | K: only with the recomputed `k` flag, and only if the major's type is defeq to the type of the nullary constructor application | `inductive.h:28-48` | `ruleKbad`, `ruleKAcc`, `bugs/k-rec-conv`, `bugs/rec-k-lie` | A |
| 11 | K never inspects or reduces the major itself | `inductive.h:88-91` | `ruleK` | R |
| 12 | Struct eta on a recursor major: non-recursive, no indices, not Prop-typed, not already any constructor app | `inductive.h:60-72` | `accRecNoEta`, `corner-cases/eta-struct-major-and` | A/R |
| 13 | Recursor fields are the last `nfields` args of the major; `nfields` comes from the recomputed rule | `inductive.h:102-112` | `bugs/nested-rec-rule-nfields` | A |
| 14 | Wrong number of universe levels: error in infer, "not delta" in unfold, "no reduction" in iota | `:105`, `:555`, `inductive.h:103` | `bugs/constlevels` | A |
| 15 | Loose bound variable anywhere in a checked term is an error | `:326` | `bugs/loose-bvar-value` | A |
| 16 | Level params must be declared (check mode only) | `:85-91`, `:118`, `:346` | `other/inductive-undeclared-universe` | A |
| 17 | Def-eq cache = exact pairs, no transitive closure | `type_checker.h:33-41` | `bugs/rec-missing-ih` | A |
| 18 | Any cache on open terms must distinguish binder types (official: globally unique fvars) | `type_checker.h:33-39` | `bugs/open-term-cache-context` | A |
| 19 | Closure-based checkers: closure identity must include the universe instantiation and the body's role (value vs type) | n/a (official has no closures) | `bugs/closure-level-subst`, `bugs/pi-closure-context` | A |
| 20 | String literal elements are Unicode scalar values, not bytes | `inductive.cpp:1356-1368` | `bugs/str-lit-utf8(-bad)` | A/R |
| 21 | Nat arithmetic exact; `x/0 = 0`, `x%0 = x`, truncated sub; huge shifts of 0 / to 0 | `:610-699` | `other/nat-lit-*`, `bugs/nat-shift-huge` | A/R |
| 22 | Unit-like and struct eta use recomputed `numFields`, exclude indexed and recursive types | `:852-868`, `:1116-1126` | `bugs/ctor-num-fields`, `indexedUnitEta`, `indexedStructEta` | A |
| 23 | Function eta only against a lambda; type of the other side whnf'ed to a Pi | `:837-849` | `funEta`, `funEtaDep`, `funEtaBad`, `bugs/eta-ctor-alias` | A/R |
| 24 | Proof irrelevance needs `is_prop(type)` via whnf of the sort, and defeq of both types | `:895-902` | `proofIrrelevance`, `proofIrrelevanceBad`, `proofIrrelevanceWhnf`, `proofIrrelevanceUnderBinder` | A/R |
| 25 | Theorems unfold in `whnf` and lazy delta; `opaque` never | `declaration.h:447`, `:551-559` | (well-founded recursion, `Acc.rec` on theorem-headed proofs) | R |
| 26 | Let-bound fvars zeta-reduce in `whnf_core`; syntactic `Let` reduces by substitution | `:405-413`, `:538-540` | tutorial `letRed`, `letTypeDep` | R |
| 27 | Type of a `let` is a let-telescope over the body type with unused lets dropped | `:227-229` | `letType` | R |
| 28 | `lit 0` vs `Nat.zero`, `lit (n+1)` vs `Nat.succ x` via `is_def_eq_offset` | `:1021-1048` | tutorial `natLitEq` | R |
| 29 | Recursor on a literal major: one constructor layer (`Nat.succ (lit (n-1))`) | `inductive.h:92-93` | `nRecReduction`, `bugs/nat-rec-rules` | R |
| 30 | `Sort` and constant levels compared with `is_equivalent`, not syntactically | `:806`, `:1166` | `levelMaxComm`, `levelMaxAssoc`, `levelMaxIdem`, `levelMaxAbsorb` | R |
| 31 | Binder names, binder infos irrelevant everywhere; mdata transparent | `expr_eq_fn.cpp:98-110`, `:474`, `:708` | - | R |
| 32 | `infer_pi` level = right-nested `mk_imax` of domain levels | `:158-165` | `imax1`, `imax2` | A/R |
| 33 | Binder domains / declaration types may need `whnf` to become a sort | `:62-71` | `forallSortWhnf` | R |
| 34 | Extra arguments after a recursor major / `Quot.mk` argument are re-applied | `inductive.h:113-117`, `quot.h:66-68` | `quotLiftReduction` | R |
| 35 | Level instantiation uses simplifying `mk_max`/`mk_imax` | `level.cpp:261-300` | (interacts with 30) | R |
| 36 | `Bool.true` shortcut and Nat folding only on fvar-free terms unless `eagerReduce` | `:1138`, `:1056` | `perf/let-fold` | perf |
| 37 | `Nat.pow` with exponent > 2^32-1, or result bound exceeded, is an error, not a stuck term | `:626-641` | - | (mirror) |
| 38 | Safe declarations may not mention unsafe or partial constants | `:109-117` | tutorial lines 1454-1486 | A |
| 39 | Literal vs literal is decided immediately by value | `:817-818` | `other/nat-lit-add-bad` | A/R |
| 40 | Let value type must be defeq to the annotation (check mode) | `:217-220` | `corner-cases/let-value-type-mismatch` (either) | - |

## 11. Performance-relevant behaviour (what Mathlib and the perf tests rely on)

| Behaviour | Where | What goes wrong without it | Arena perf test |
|---|---|---|---|
| Structural equality / success-pair test before any reduction | `quick_is_def_eq` | identical nested terms unfolded pairwise, exponential | `identical-nesting`, `repeated-subproblem` |
| Same regular head: compare arguments (last to first) before unfolding; cache the failure | 8.4 | Theta(n^2) instead of Theta(n); without the failure cache the same failed argument comparison repeats at every level | `args-before-unfold`, `proj-stuck-struct` |
| Unfold the definition with the greater height; abbrev before regular; never a theorem while a definition is available | `compare` | evaluating the cheap-to-match side to normal form | `unroll-versus-evaluate`, `loop-unfolded-once` |
| Proof irrelevance before delta, with a definitive negative answer | 8.3 | evaluating proofs (e.g. well-founded recursion witnesses) | `irrelevance-refutes`, `irrelevance-before-evaluation` |
| Cheap projection mode first; lazy lockstep unfolding under equal projections | 8.1 step 3, 8.5 | reducing a structure to a constructor when its arguments already agree | `proj-cheap-struct`, `proj-lazy-struct` |
| Stuck projection keeps the folded struct term | 7.1 | the folded head is lost and recursion trees unroll, exponential | `proj-stuck-struct` |
| When only one side is delta and the other is a stuck projection application, reduce the projection instead | 8.4 "only dt" | lazily unfolding an expensive term against an instance projection | (comment at `type_checker.cpp:968-975`) |
| Nat folding in def-eq only on fvar-free sides; let variables are fvars | 8.4, 6.6 | evaluating a let-bound closed term that never needed evaluation | `let-fold` |
| Infer cache on shared sub-DAGs, separate check/infer-only | 5 | 2^n re-inference | `app-lam` |
| Telescoped binder traversal (one instantiation per binder group), multi-argument beta, infer-only spine walk | 6.3-6.6, section 4 | Theta(n^2) substitution on ladders | `beta-ladder`, `let-ladder`, `shift-cascade`, `app-lam` |
| O(1) loose-bvar range as dependency test in `infer_proj` | 6.8 | Theta(N^3) for wide Prop structures | `prop-struct-projections` |
| GMP arithmetic on literals, binary literals never unary | 7.6 | infeasible | `gf3-matrix-product`, `other/nat-lit-*` |
| `m_unfold`: instantiated bodies of universe-polymorphic constants reused | 5 | re-instantiating large bodies at every unfold | - |
| Hash-consing of theorem type/value before checking | `environment.cpp:196-198` | pointer-keyed traversal memo and `=p` fast paths miss | - |

Known weak spots of the official strategy (RLVM may do better without changing verdicts): arguments are
compared before unfolding even if the head ignores them (`discarded-argument`, `short-circuit-and`: about
14 s); Nat folding is tried before unfolding on closed terms (`unfold-before-literal`: about 20 s); the
higher side is unfolded even when one step on the lower side would finish (`ite-taken-branch`); applied
projections are reduced eagerly (`proj-applied-struct`, `proj-app-lazy-delta`); whnf caches retain every big
literal until the declaration ends (`gf3-matrix-product`: 3.5 GiB). The official kernel needs about 33.7
minutes of virtual time for Mathlib, so mirroring it literally (substitution-based, per-declaration caches)
is not the performance target; mirroring its *decisions* is.

Implications for an NbE/closure design (design guidance, not verified facts):
- Neutral values need the folded form (constant + argument thunks) next to the unfolded continuation, so
  that 8.4 can compare arguments and consult hints before forcing ("glued" evaluation).
- Proof irrelevance and unit eta need the type of an arbitrary value; either carry types on neutrals or
  quote and re-infer. They must be consulted before delta unfolding.
- The `hasFVar` guard needs a cheap "closed" bit on values/thunks; let-bound variables count as open.
- A cache on open values must include the environment (levels included) and the mode, see rows 18-19.
- The official recursion depth is bounded only by the native stack; Mathlib terms nest deeply, so the JVM
  checker threads need a large explicit stack size or an explicit work stack.

## 12. Changes on master after v4.36.0 (diff `v4.36.0..7e19442`)

1. `is_def_eq_core`: `is_def_eq_proof_irrel(t, s)` moved in front of the first `whnf_core`.
2. `inductive_reduce_rec`: `if (!is_prop(infer_type(major))) major = whnf(major)`; proof majors are no longer
   normalised (related tests: `corner-cases/eta-struct-major-no-whnf`, `-and-no-whnf`).
3. `to_cnstr_when_structure`: the Prop test is replaced by "recursor is not large-eliminating"
   (`#lparams(rec) == #lparams(first inductive of the block)` -> no eta), enabling eta for `And`-like majors.
4. `try_eta_struct_core`: a partially applied constructor of a structure is eta-expanded with fresh fvars
   taken from the constructor application's own type (the variant that `bugs/eta-ctor-alias` demands).
5. Projection indices are `size_t`; open-addressing pointer-pair sets replace `std::unordered_set` in
   `expr_eq_fn`/`for_each_fn`; `replace_fn` rewritten. No change to levels.
Changes 1, 3 and 4 let the kernel accept more (or answer earlier); the affected arena tests have outcome
"either". Change 2 also makes it accept LESS (inference from the code, not run): a recursor of a
non-structure Prop inductive, e.g. `Acc.rec`, no longer fires when its major is a proof that must be
unfolded to become a constructor application (as in `WellFounded.fix` through `WellFounded.apply`). Keeping
the v4.36 behaviour (whnf of every major) therefore accepts a superset of master here, at the price of
non-termination on the two `-no-whnf` corner cases (60 s timeout, outcome "either").

## 13. Not verified, and open questions

- Which hint kind the elaborator assigns to instances and structure projection functions (only the kernel
  side of hints was read).
- Whether the Mathlib v4.36.0 export contains `mdata` nodes, `nondep` lets or `partial` constants (export
  format is covered by another document).
- PR numbers are quoted from the arena test descriptions and `lean4lean/divergences.md`; only the resulting
  code at the four tags and master was inspected.
- Behaviour of the official checker on `Nat.pow`/`Nat.shiftLeft` guard violations beyond reading the code
  (no binary was run).
- Open: primitive validation. An export that declares `Nat.add` (or `Nat`, `Bool.true`, `String.ofList`)
  with a non-standard body makes the literal extension disagree with delta unfolding; by the code read here
  and by `lean4lean/divergences.md` the official checker accepts such a file (not run). Validating the definitional behaviour of the 15 Nat functions and of the literal
  constants (as lean4lean does) closes this, at the cost of rejecting exports the official checker accepts.
- Open: whether guard violations of 7.6 (`Nat.pow` exponent above 2^32-1, or above 2^24 for a word-sized
  base; numerals above 128 MiB) should be mirrored as "reject" or reported as "decline". None of the arena
  test descriptions read for this document exercises them.
- Open: caches that outlive one declaration are not what the official kernel does. For closed terms they are
  sound (the environment only grows and constants never change), but memory then has to be bounded
  explicitly; the official kernel frees everything after each declaration.
