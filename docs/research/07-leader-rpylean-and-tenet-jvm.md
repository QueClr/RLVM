# 07 — rpylean (RPython, #4 at 96.3 s) deep-dive and tenet-jvm (Java, 27.2 min) post-mortem

Sources (read-only clones, nothing built or run):

- `refs/rpylean` — https://github.com/Julian/rpylean at `bbf354d3adbfe84d297ea2159cc707b48d96a90a` (the arena pin, v2026.11.11, HEAD of the clone; 718 commits of history read). ~21 k lines of RPython, of which `rpylean/machine.py` (12 879 lines) is the kernel.
- `refs/tenet-jvm` — https://github.com/keithadler/tenet-jvm. The arena pins `4cc6e20` (v0.1.5); the clone's HEAD is `53cf8a4` (3 commits later). Unless stated otherwise, tenet-jvm line numbers refer to **`4cc6e20`** (read with `git show 4cc6e20:<path>`), because that is the revision that scored 27.2 min / 11.8 GB.
- `refs/lean4` (branch at `611c117`, v4.36 line) was used to check two claims about the official kernel.

All paths below are relative to `refs/`. Bare file names in §1 (`machine.py`, `objects.py`, `parser.py`, `_eval.py`, `cli.py`, `environment.py`, `_inductive.py`, `_stack.py`) live in `rpylean/rpylean/`; `targetrpylean.py` and `justfile` in `rpylean/`. Bare Java file names in §2 live in `tenet-jvm/src/main/java/tenet/`. "Commit `abc1234`" means a commit in the respective repository; quoted numbers are the authors' own measurements from commit messages (rpylean: Apple M4, ARM64 instruction counts) unless marked otherwise. Items I could not verify are marked **[unverified]**; my own inferences are marked **[inference]**.

---

## 0. Decision-relevant findings first

1. **rpylean is not a JIT-driven checker.** The translated binary is built with `--opt=jit` (`rpylean/justfile:59`), but the *only* `JitDriver` in the code base sits in `rpylean/rpylean/_eval.py:1421` and drives a call-by-need CEK machine that is entered solely when a *closed compound term is compared with a `Bool`/`Nat` scalar* (`machine.py:11743-11761`, `machine.py:8361-8389`). Everything Mathlib spends time on — parsing, import, substitution, whnf, infer, def_eq — is RPython translated ahead-of-time to C. Measured effect of the JIT machine on library corpora: "init: ~0% delta, std +1.3%, cedar +1.6%, cslib −0.1%" (commit `dd8949e`).
2. **The authors tried a tracing JIT on the kernel loops and removed it.** With `JitDriver`s on `whnf`, `_def_eq_core`, `_instantiate` and the app-spine loop (commits `83555d3`, `78ca705`, `5479807`): "init.ndjson runs 136.7s with --jit off vs 162.4s with the JIT on … Setting the compile threshold absurdly high reproduces the slow times, so the cost is not poor trace quality but the merge-point bookkeeping itself … ~15% of wall time on a megamorphic tree-walk the tracer can't speed up" (commit `1ba28a8`); removed in `c2be56a`. **Consequence for RLVM: do not put Truffle partial evaluation on the kernel's term-walking loops; reserve it for evaluation of closed Lean code.**
3. **The single largest win was moving the working set out of the GC heap**: terms as 16-byte records in unmanaged `u32` arrays addressed by integer handles, hash-consed per declaration, with all caches as parallel integer arrays. init: 752 B → 184 B instructions, 3478 → 963 MB; std: 9.66 T → 348 B (28×) (commits `0866ac8`, `2ec1abf`). Stated motivation: GC tracing of retained reduction products was "~45% of the run" on the std tail (commit `d3e30e2`). On the JVM the exact analogue is primitive `int[]`/`long[]` arrays (the collector never scans their contents).
4. **rpylean's kernel is substitution-based (de Bruijn + locally-nameless FVars), not NbE.** Its speed comes from (a) hash-consing ⇒ handle identity ⇒ O(1) equality and array-indexed memo slots, (b) memoised, allocation-free multi-substitution, (c) aggressively *not doing work*: skipping proof arguments via per-constant Prop masks, comparing un-instantiated patterns against targets instead of instantiating (`inst_eq`), importing only what is needed from the parsed AST, warm caches across declarations. Closures were tried in the old object kernel and abandoned because fresh closure objects defeated identity-keyed caches (commit `4c45e81`); see §4.
5. **Budget reference.** Arena: 96.3 s × 6e9 = **5.78e11 instructions** for Mathlib, single-threaded, peak 4.4 GB. Own numbers on M4 at the pinned revision: init 28.7 G, std 50.2 G, cedar 68.8 G, cslib 115.5 G, navier-stokes-euler 193.6 G instructions (commit `1f30f95`). The last Mathlib figure recorded in git is 1285.5 G at `ff75c0a` (2026-11-21); the later commits cut NSE by 2.15× (415.6 G → 193.6 G), which extrapolates to ≈600 G **[inference]**, consistent with the arena's 578 G. Mathlib has ~670 k declarations (commit `a31ca02`) and ~107 M expression nodes in the export (commit `291ae24`), so the bar is **≈0.85 M instructions per declaration / ≈5.4 k instructions per exported expression node, everything included**.
6. **tenet-jvm costs 9.8e12 instructions (17× rpylean) and 11.8 GB** for structural reasons that are all avoidable: a `LinkedHashMap<String,Object>` JSON tree per line, `HashMap<Expr,Expr>` caches with structural `equals`, non-interned `Name`s as `HashMap` keys, fresh FVars on every binder opening, closure-based generic `replace`, `ArrayList` spines, and a launcher (`-XX:+UseParallelGC -XX:MaxRAMPercentage=75`) that lets the heap grow to 12 GB on the 16 GB runner while all GC worker threads' instructions are billed. What is worth copying: the fail-closed verdict protocol, the large-stack checker thread, derive-then-compare for inductives, and its catalogue of Lean-version-dependent rules (§2.6).

---

## 1. rpylean

### 1.1 Architecture map

| File (under `rpylean/rpylean/`) | Lines | Role |
|---|---|---|
| `parser.py` | 2837 | NDJSON reader: SWAR chunk fast path + general cursor parser; refcount prescan; streaming hook per declaration |
| `objects.py` | 6391 | Parsed term language (`W_Expr`, `Name`, `W_Level`), declarations and their admission checks, interning tables, Nat ops, static Prop-mask / ctor-body analyses |
| `machine.py` | 12 879 | The kernel: `TermStore` (records, consing, substitution, import/export), `Machine` (whnf, infer, def_eq, check_value), raw `IntMap`/`IntSet`/`U32Map`/`PairMap`, warm-machine `acquire`/`release` |
| `_eval.py` | 2031 | Call-by-need CEK machine over compiled `CodeNode`s for closed terms; the only JIT-traced loop |
| `_inductive.py` | 1234 | Regenerates recursors of an inductive block (mutual + nested) for comparison |
| `environment.py` | 612 | `Environment` (name → decl), `TypeChecker` (per-declaration limits, ordering/safety rules), `type_check_one` |
| `cli.py` | 1139 | `check` command, streaming checker, prescan orchestration, exit codes |
| `_stack.py` | 120 | Re-exec with a larger `RLIMIT_STACK` |
| `targetrpylean.py` (repo root) | 94 | Translation target; patches the GC nursery floor |

Data flow for `./rpylean-c check FILE` (`cli.py:532-587`):

```
open FILE unbuffered ──► pass 1: prescan_refcounts (raw bytes → RefCountTable u32[exprIdx])   parser.py:2827, cli.py:491
        seek(0)      ──► pass 2: _consume_stream: 256 KiB chunks, SWAR fast path per line       parser.py:2722-2816
                              │ names/levels/exprs → GC objects (W_Expr AST), pool slot freed when refcount hits 0
                              ▼ each declaration line → EnvironmentBuilder.register_declaration → hook.on_declaration
                         _StreamingChecker.on_declaration (cli.py:274) → Environment.type_check_one (environment.py:406)
                              │ TypeChecker(env, decl) → acquire(warm Machine)                  machine.py:12809
                              ▼ decl.w_kind.type_check → Machine.check_value(type, value, prop) machine.py:12690
                         on success: decl.w_kind.drop_checked_value()  (theorem/opaque bodies freed) cli.py:363
exit 1 if any failure else 0 (cli.py:184)
```

Single-threaded; declarations are checked strictly in file order, interleaved with parsing. No decline exit code exists: deep recursion, per-declaration limits and malformed lines are all rejections (exit 1); an uncaught RPython exception aborts the process.

### 1.2 Two term representations

**(A) Parsed AST — GC objects, immutable, persistent** (`objects.py`). Used only as the *source* from which the kernel store imports.

- `W_Expr` base: two words, `_packed` and `_cache` (`objects.py:2808-2850`).
  - `_packed`: bits 0-31 content hash; bit 32 `has_fvar`; bits 33-61 `loose_bvar_range`; bit 62 `has_level_param`. Computed in each constructor (`W_App.__init__`, `objects.py:4314-4324`).
  - `_cache`: `(generation << 32) | handle` — an *intrusive, generation-stamped import memo* (see §1.4).
- `W_App{fn,arg}`, `W_Lambda/W_ForAll{binder,body}` with `Binder{name,type,_pos_style,_fvar}`, `W_Let{name,type,value,body}`, `W_Proj{struct_name,field_index,struct_expr}`, `W_Const{name,levels,_canonical}`, `W_Sort{level}`, `W_BVar{id}`, `W_FVar{id,binder,_uid}`, `W_LitNat{val: rbigint}`, `W_LitStr{val}`.
- Interning policy (`objects.py:1024-1047`): `Name`s globally hash-consed in append-only open-addressed tables storing `(hash31 << 32) | (entryIdx+1)` in a pointer-free int list (`objects.py:961-1100`) ⇒ name equality is pointer identity; levels hash-consed in per-kind dicts; `W_Const` in a fixed 65 536-slot direct-mapped cache (`_CONST_CACHE`); `W_BVar`/small `W_LitNat` in 1024-entry arrays; `W_Sort`, `W_LitStr` interned. **`W_App`/`W_Proj`/`W_Let`/binders are deliberately *not* globally interned** — "lean4export already DAG-shares parsed terms by `ie` index and `TermStore` hash-conses them per declaration; keeping strong refs in global dicts pinned dead theorem proofs across Mathlib".
- Per-`Name` inline cache of the declaration lookup: `get_decl` is two field reads (`name._decl_dict is declarations → name._decl_cached`, `objects.py:47-64`); −4.8% instructions on a grind workload (commit `7073bce`).
- Levels: parsed verbatim (no simplification at parse, commit `eed3160`); `eq` = identity, else syntactic equality of cached normal forms, else `leq` both ways (`objects.py:2196-2208`); `normalize()` cached on the level object. Constant and sort leaves are canonicalised to normal-form levels on import so `C.{max u v}` and `C.{max v u}` become the same leaf (`machine.py:517-552`; commit `c147f1d`: one declaration 22 s / 4.87 M def_eq calls → 1.2 s / 200 calls).

**(B) Kernel store — `TermStore`, unmanaged memory, per declaration (warm-reused)** (`machine.py:83-152`, `1114-1272`).

- **Handle** = tagged int. bit 0 set ⇒ leaf with index `h >> 1`; bit 0 clear and ≠ 0 ⇒ record with index `(h >> 1) − 1`; `0` = invalid/empty marker of every table.
- **Record** = 4 × `u32` (16 bytes) in `recs`:
  - word 0: `(meta << 3) | kind`, `meta = (loose_bvar_range << 1) | has_fvar`. Hence `word0 < 16 ⇔ no loose bvars` — a one-compare "nothing to substitute here" test used everywhere (`machine.py:2500`).
  - word 1 `a`, word 2 `b`: child handles. `APP(fn,arg)`, `LAMBDA/FORALL(binderType, body)`, `PROJ(struct, (nameId << 10) | fieldIdx)`, `LET(type, PAIR(value, body))`, `PAIR`.
  - word 3: `(sizeEnc:8 << 24) | info:24`. `sizeEnc` = saturating tree size (255 = "heavy", ≥ 256 nodes), computed on cons as `ea + eb + 2` (`machine.py:1632-1639`); `info` = index into a GC side list holding the `Binder` (name, style — not part of identity) or the projection/let `Name`.
- **Identity**: `(kind, a, b)`. Binders are consed on (type, body) only ⇒ alpha-equal binders share a record. Projection identity includes the structure name (bug fixed in `2ec1abf`: `PProd.0 x` and `LE.0 x` had shared a record).
- **Memo slots**: 6 × `u32` per record in `memos` (`machine.py:145-158`): `M_WHNF_CORE`, `M_WHNF`, `M_INFER`, `M_CHECKED_GEN`, `M_WHNF_CORE_CHEAP` (also holds the one-step delta unfold of a delta-headed app, tagged with bit 31), `M_EQV` (union-find parent). 0 = unknown. Plus one `i64` `call_slot` per record (scratch memo of the substitution in progress, §1.5).
- **Consing tables** (open addressing, linear probing, load ≤ 50%, single multiply-xorshift hash `_spread`, `machine.py:329-336`):
  - `app_table`: one `i64` per slot = `(fn:21 | arg:21 | recIdx+1:21)`. A hit needs **one probe of one array and no load from `recs`** (`cons_app`, `machine.py:1589-1605`). When any handle ≥ 2²¹ the table is promoted to a 16-byte `(key64, handle)` layout (`_promote_apps_to_wide`).
  - `table` for the other kinds: `(kind:3 | a:20 | b:20 | recIdx+1:20)`, promoted above 2²⁰.

  ```
  cons_app(fn, arg):                                   # machine.py:1589-1647
    if (fn | arg) >= 2^21: return cons_app_wide(fn, arg)
    pair = (fn << 21) | arg
    i = spread(pair) & mask                            # spread(k): x = (k ^ k>>>30) * 0xbf58476d1ce4e5b9; x ^= x>>>27
    loop:
      e = app_table[i]
      if (e >> 21) == pair: return (e & (2^21-1)) << 1  # hit: handle = (recIdx+1) << 1, no load from recs
      if e == 0: break
      i = (i + 1) & mask
    # miss (out of line): append record
    meta = max(looseRange(fn), looseRange(arg)) << 1 | (hasFVar(fn) | hasFVar(arg))
    size = min(255, sizeEnc(fn) + sizeEnc(arg) + 2)     # leaves count 0
    recs[4*n .. 4*n+3] = [(meta << 3) | APP, fn, arg, size << 24];  n += 1
    if ++napps * 2 > tableSize: rehash_apps() else app_table[i] = (pair << 21) | n
    return n << 1
  ```
- **Leaves** (constants, sorts, bvars, fvars, literals) stay GC objects in a list `leaves[j]`, canonicalised on entry (`leaf_for`, `machine.py:2144-2219`: bvars by index, fvars by identity, the rest by content after level normalisation). Parallel unmanaged per-leaf arrays: `leaf_meta`, `leaf_whnf`, `leaf_infer`, `leaf_checked`, `leaf_delta` (imported unfolded value), `leaf_delta_body` (stripped body + arity, or a tagged fast-path encoding), `leaf_eqv`, `leaf_head_kind` (classification flags `_LF_RECURSOR | _LF_QUOT_LIFT | _LF_DELTA | _LF_CTOR | _LF_NAT_BINOP | _LF_ITE | _LF_SHORT_BOOL | _LF_NAT_SUCC | _LF_NAT_ZERO` + the reducibility hint in the high 16 bits), `leaf_prop_mask` (`machine.py:1166-1190`, flags at `427-439`). A head constant is classified once per store; afterwards "is this head a recursor / unfoldable / a ctor / a native Nat op, and what is its height" is one array load.
- **Spine buffer**: `_spine_buf` (`u32[]`) with a stack discipline — `mark = top; head = unapp_push(h); n = top − mark; …; unapp_pop(mark)` (`machine.py:2335-2358`). Application arguments are never materialised as lists; beta, iota, delta, infer and def_eq all read arguments from this buffer.
- Size per record of capacity ≈ 16 (recs) + 24 (memos) + 8 (call_slot) + 16 (app_table at 2 slots/record) ≈ 64 bytes; no per-node object header, nothing traced by the GC.

### 1.3 Export parsing

- Two passes over the file when it is seekable (`cli.py:546-557`):
  1. **Prescan** (`_prescan_chunk_fast`, `parser.py:1353-1548`): raw-byte scan that, per expression index, counts how many later lines reference it, into a pre-sized unmanaged `u32` `RefCountTable` (size hint `fileSize/50`). Name and level lines are skipped with a SWAR newline search (`_find_nl`, `parser.py:604-615`).
  2. **Parse**: every reference goes through `ref_expr`, which decrements the count and nulls the pool slot at zero (`parser.py:256-277`). Together with dropping theorem/opaque bodies after their check this bounds the live AST to "declarations still in play": cslib 14.8 → 8.6 GB and 1.5× faster; "mathlib live heap collapses from ~8 GB to ~1.5 GB as it checks"; "~68% of cslib's distinct expressions are reachable only from theorem values" (commit `1f6d220`).
- I/O: unbuffered `read(262144)`; chunks are parsed **in place**; only the line straddling a chunk boundary is concatenated (`parser.py:2727-2816`). Before this, `memmove` was "59% of all L1D store misses"; fixing it cut footprint 48-57% and init wall time 10.8% (commit `291ae24`).
- Fast path = word-at-a-time (SWAR) matching of the *canonical lean4export key order* with unaligned 64/32/16-bit loads compared against compile-time packed literals (`parser.py:427-522`): `{"app":{"arg":A,"fn":F},"ie":E}` ("~73% of all lines", `parser.py:1381`), `{"ie":E,"lam":{"binderInfo":…,"body":B,"name":N,"type":T}}`, `{"forallE":{…},"ie":E}`; a second tier (`_compact_rare`) handles const/sort/bvar/letE/proj/natVal/strVal/names/levels/def/thm/opaque; anything else (different key order, whitespace) falls back to a general cursor parser (`parse_cursor_general`, `parser.py:1570`). No JSON tree, no per-line object except the AST node itself. Gain of the SWAR rewrite: −5.1…−6.0% of *total* instructions on init/std/cedar/cslib, −3.0% on NSE (commit `437cefa`).
- No mmap, no parallel parsing, no binary cache format. Parse share of the total is not reported anywhere **[unverified]**; the −5…6% figure above is a lower bound on what it was before the rewrite.
- Robustness rules worth copying: metadata line validated (`EXPORT_VERSION = "3.1.0"`, a missing version is tolerated, `parser.py:381-395`); a malformed line is an `ExportError` ⇒ rejection, never a crash (commits `f9ccf0e`, `3041b64`); UTF-16 surrogate pairs in `\u` escapes are combined and string literals are expanded per Unicode scalar, not per byte (commit `c7a68d7`, arena `bugs/str-lit-utf8*`).

### 1.4 Import: AST → store, fused with level and argument substitution

This is the part with no counterpart in the Rust checkers and worth understanding precisely.

- `import_term(e)` (`machine.py:5989-6164`): memoised by `e._cache == (store_gen << 32) | handle`; iterative along application spines (explicit `_import_spine` stack), recursive elsewhere with a stack check every 32 frames.
- `import_const_term(const, decl, target)` (`machine.py:6555-6587`): imports `target` (a declaration's type, value or recursor-rule RHS) **with `decl.levels := const.levels` substituted during the walk**, never building an instantiated AST. Sub-terms without level params (`has_level_param` bit) reuse the plain memo. The memo for the level-instantiated walk uses a *different generation number* per (params, levels) pair, obtained from a 512-slot direct-mapped table (`_level_cache_gen`, `machine.py:6489-6553`), so one `_cache` word per AST node serves "this node under this level instantiation in this store epoch". Identity instantiations (`C.{u}` inside a declaration polymorphic in `u`) short-circuit to the plain import.
- **Never import what beta will discard** (commit `90bc723`, NSE −3.4%): for an applied definition, the leading lambdas of the value are peeled *on the AST* and only the body is imported; `leaf_delta_body[j] = (arity << 32) | bodyHandle`. Same for recursor rules (`_rule_rhs_stripped`, `machine.py:9386`).
- **First-use fusion** (commit `e974694`; `_try_unfold_first_use`, `machine.py:8133-8270`): the first time a definition is unfolded in a store, level substitution *and* argument substitution are done in one walk of the AST (`import_const_subst_buf`), producing the beta-reduct directly; only the second unfold imports the stripped body into the store ("single-use definitions are ~40-55% of `_delta_body` records").
- **Delta fast paths by body shape** (commit `769f42b`; `_unfold_fast`, `machine.py:8058-8130`): if the value is `λ x₁…xₘ. BVar b` (argument picker: `outParam`, `inferInstance`, `id`-likes) or `λ x₁…xₘ. Proj(S, i, BVar b)` (structure/class parent projections), the unfold is computed from the spine without any import or instantiate.
- Analogous AST-direct paths exist for inference of constant applications on first use (`_infer_app_const_ast_buf`, `machine.py:10898`; "single-use constants are ~78% of `_infer_leaf` records", `machine.py:463-468`) and for projection field types (`W_Inductive.init_proj_cache`, `objects.py:5900-5925`).

### 1.5 Substitution engine

All of `instantiate`, `instantiate_multi*`, `shift`, `bind_fvar(s)` (`machine.py:2451-5950`) follow one scheme:

```
instantiate(h, sub, depth):
  if looseRange(h) <= depth: return h                      # one load + compare
  if leaf(h): bvar hit → sub (shifted by depth unless sub closed); else bvar(id-1)
  r = persistentMemo[(h, sub, depth)]  (packed 25|25|13 bits into one i64 key; capped table) ; if r: return r
  epoch = ++callEpoch
  r = walk(h)        # recursive; per record ri visited:
                     #   if callSlot[ri] >> 32 == (epoch << 8 | depth): return low 32 bits   ← O(1) one-shot memo
                     #   rebuild children; if unchanged return h itself (no cons)
                     #   left spines of APP are unrolled iteratively onto _subst_stack
                     #   callSlot[ri] = ((epoch << 8 | depth) << 32) | result
  persistentMemo[(h, sub, depth)] = r
```

- The **one-shot memo** (`call_slot`, `machine.py:1253-1255`, `2502-2505`, `2659`) makes substitution linear in the DAG with no hashing: one `i64` array indexed by record, validated by a per-call epoch. A record seen at a second depth within one call falls back to a small `IntMap` (`_call_memo`). Replacing per-call `IntMap` hashing by this: "−10% to −15% cycles & instructions across the board" (commit `53c4861`, together with 32-bit arrays and the spine buffer).
- **Multi-substitution** takes its substitutes directly from a slice of the spine buffer (`instantiate_multi_buf(h, start, count)`), so an n-ary beta/iota/delta step is one walk with zero list allocation; specialised variants exist for depth 0 and for "all substitutes closed" (no shifting) (`machine.py:3891-3920`; commits `ff75c0a`, `7e11d57`).
- The persistent memos (`_inst_memo`, `_shift_memo`, `_bind_memo`, `_multi_memo`) are capped (`_MEMO_LIMIT = 1 << 18` slots, `machine.py:503-506`) and wiped when full: "a substitution memo only pays for itself while a lookup is cheaper than redoing the work, which stops being true once the table has outgrown the caches" (commit `19c0af8`).
- **Telescopes are opened at once** (commit `6087a53`): a k-binder lambda is instantiated with k fvars in one multi-substitution and re-abstracted in one `bind_fvars` pass; before, "a k-binder lambda cost k walks of each"; the abstraction memo of the slowest Mathlib declaration shrank 53 M → 5 M entries.

### 1.6 Reduction

`whnf_core(h, cheap_proj)` (`machine.py:7728-7911`) — beta, zeta, proj-of-ctor, iota, `Quot.lift`; never delta. Memo slot per mode; a result known in `M_WHNF` also answers `whnf_core`. One step (`_whnf_core_step`):

- `APP`: walk to the head without materialising the spine.
  - head is a record `LAMBDA` → push spine, **multi-arg beta** (`_beta_spine_buf`: strip up to n lambdas, one multi-instantiate, re-apply leftovers).
  - head is a leaf: one flag load; unless `_LF_RECURSOR`/`_LF_QUOT_LIFT`, **stuck in O(1)**.
  - recursor → `_try_iota_spine` (below); `Quot.lift` with ≥ 6 args → whnf the 6th, must be `Quot.mk` with 3 args → `f a extra…` (`machine.py:9146-9164`).
  - head is `PROJ`/`LET` → reduce head, re-apply (beta directly if it became a lambda).
- `LET` → instantiate body with value. `PROJ` → `try_proj`.

`whnf(h)` (`machine.py:7913-7943`): `loop { h = whnf_core(h); if APP and try_reduce_nat(h) → continue; u = try_unfold_head(h); if u == 0 break; h = u }`, memoised in `M_WHNF` / `leaf_whnf`. One delta layer per iteration; the one-step unfold of an app is itself memoised (commit `cb628e6`).

**Iota** (`_try_iota_spine`, `machine.py:9166-9384`):
1. `Nat.rec` on a literal major: done in one step without constructing `Nat.succ` (builds `succ_case pred (Nat.rec … pred)` with `pred` a literal).
2. `major = whnf(major)`; if the recursor is K-like, `_to_cnstr_when_k` (infer + whnf the major's type, build the nullary-field ctor applied to the type's params, require `def_eq(type, infer(ctor))`; `machine.py:9404-9446`).
3. literal major → expose one constructor; otherwise try structure-eta of a stuck major (`_to_cnstr_when_structure`, see §1.12 for a deviation).
4. head must be a ctor; rule looked up by ctor name (`rec.rule_for_ctor`).
5. **Rule fast paths** (commits `769f42b`, `b486214`): `W_RecRule.simple_minor` (`objects.py:4498-4585`) recognises, once per rule, the standard shape `λ params motives minors fields ⇒ minor_i fields (rec … field_r)…` and records the minor index and the list of recursive field positions. Then iota is computed straight from the spine: pick the minor, push fields and the IH applications `rec_prefix field_r` (re-using the `rec params motives minors` prefix record of the redex), and beta in one step — the rule RHS is never imported or instantiated. Otherwise the stripped RHS is imported once per (recursor leaf, ctor leaf) and applied by one multi-instantiate.

**Projection** (`try_proj` / `_try_proj_fused`, `machine.py:8997-9144`; commits `cdc2edb`, `70a43a3`, `6d4475f`): nested projections are peeled onto an index stack; when the struct reduces to an application of a *definition whose body is a constructor application* (statically analysed once: `W_Definition.init_ctor_body` → `_CtorBody{m, num_params, let_vals, args, delegate_const}`, `objects.py:5114-5338`, including wrappers delegating to another such definition up to depth 6), **only the projected field is imported and instantiated**, with only the arguments it mentions; fields that are a bare `BVar` are read from the spine. This is the type-class-instance fast path (`instFoo.toBar.op` without materialising the whole instance).

**Nat literals** (`machine.py:8361-8505`, ops in `objects.py:3500-3579`): `add sub mul pow gcd mod div beq ble land lor xor shiftLeft shiftRight` on `rbigint`; `pow`/`shiftLeft` decline when the exponent/shift exceeds 2²⁴. `try_reduce_nat` whnf's both arguments (so it evaluates) and is therefore called only from the evaluating `whnf` loop; lazy delta uses `try_reduce_nat_cheap` (arguments must already be syntactic numerals) and only on fvar-free terms (commit `2c7d435` explains the blow-up otherwise). Arguments that are closed non-literals go to the CEK machine first (`_whnf_nat_arg`).
**String literals**: expanded lazily to `String.ofList [Char.ofNat n, …]` (per code point) only when projected, matched by a recursor, or as the last resort of def_eq (`objects.py:3156-3165`, `machine.py:12369-12380`).

### 1.7 Definitional equality — exact order

`def_eq(h1, h2)` (`machine.py:11591-11622`):

```
1. h1 == h2                                             → true      (hash-consing: syntactic equality)
2. eqvCount > 0 and find(h1) == find(h2)                → true      (union-find in M_EQV / leaf_eqv, path compression)
3. symmetric pair key in _neq                           → false     (negative cache: IntSet, 8 B/slot, capped at 4M slots)
4. both APP and eqvCount > 0: _spine_cheap_eq           → union; true
      lockstep walk down both spines; every arg pair identical or in the same UF class,
      heads identical or same class; no recursion, no reduction             (machine.py:11682-11701)
5. r = _def_eq_uncached(h1, h2);  r ? union(h1,h2) : _neq.add(key)
```

`_def_eq_uncached` (`machine.py:11740-11813`):

```
a. if one side is a Bool/Nat scalar leaf and the other a closed compound term (not a Nat.succ chain):
       v = eval_closed_scalar(other); if v == scalar → true; else continue with v     (§1.10)
b. h1 = whnf_core(h1, cheap_proj=true); h2 likewise; identical → true
c. proof irrelevance (before any delta): t1 = infer(h1); s1 = infer(t1) (whnf only if not literally Prop);
       if Prop: same for h2; if def_eq(t1, t2) → true          (falls through on failure)
d. Nat offset: both literals → compare; both Nat.zero → true; peel matching Nat.succ/literal layers in a loop
e. if one side is the leaf Bool.true: whnf(other) == Bool.true → true
f. status = lazy_delta(h1, h2)  → true, or continue with the advanced forms; identical → true
g. _def_eq_proj_lazy: two projections (bare or applied heads) with the same (struct name, index):
       bounded comparison of the structs (shared budget of 8 unfold steps, recursion depth 3), guarded by _failed
h. n1 = whnf_core(h1) (full, projections evaluated), n2 likewise; if either changed → def_eq(n1, n2)
i. _def_eq_core:
     leaves: sorts → level eq; consts → same name and level-wise eq; distinct literals/fvars → fall through
     APP/APP → _def_eq_app;  binders of same kind → _def_eq_binders;  PROJ/PROJ same field → def_eq(structs)
     eta (lambda vs non-lambda whose whnf'd type is a Pi) in both directions
     structure eta in both directions (ctor app with exactly params+fields args; def_eq of the types; field-wise def_eq with projections)
     unit-like (type is a non-recursive, index-free structure whose ctor has 0 fields; def_eq of the types)
     Nat literal → one constructor step; String literal → String.ofList expansion
```

**Lazy delta** (`_try_lazy_delta`, `machine.py:12059-12285`), per iteration (cap 100 000):

```
1. identical → true
2. peel Nat offsets (as in d); if peeled, cheap whnf_core both
3. if neither side has fvars: try_reduce_nat_cheap on either APP → continue
4. d1/d2 = head has _LF_DELTA.  neither → return UNDEF(h1,h2)
5. exactly one unfoldable: unfold it (native Nat op first if closed); if the *other* head is a PROJ,
   try _def_eq_proj_lazy on the cheap-whnf'd result before full whnf_core
6. both unfoldable:
   a. exactly one is @[reducible]/abbrev → unfold that one
   b. exactly one is a fully applied ite/dite whose Decidable instance is closed → unfold that one   ← not in Lean
   c. same constant (name equal, levels eq) with a *regular* hint: unless it is Bool.and/or with a
      reducible first argument (short-circuit, not in Lean), compare arguments pairwise left to right —
      skipping positions in the head's Prop mask — guarded by the _failed pair cache; all equal → true
   d. exactly one head is a native Nat binop → unfold the *other* side first                         ← not in Lean
   e. greater height unfolds; equal heights → unfold both, cheap whnf_core, identical or proj-lazy → true
```

Deliberate differences from the official kernel that matter for performance:

- **Prop-argument masks** (`_prop_mask_for`, `machine.py:7585-7665`; static analysis `W_Declaration.init_prop_mask`, `objects.py:4843-4967`). For a head constant, a 62-bit mask of argument positions whose domain is a proposition. In `_def_eq_app`, lazy delta's same-head comparison and `_proj_lazy_eq`, once all earlier arguments agreed, an unequal argument pair at a masked position is **skipped** (proof irrelevance without inferring anything). Computed from the declared type without the store for "~99.7% of constant heads": binder domain is `∀…, Sort l` (never a proof position); or `BVar` applied to as many args as an earlier binder's `∀…, Sort l` arity (position is Prop iff `l` is zero); or `C args` with `C : ∀ (m args), Sort l` fully applied (Prop iff the instantiated `l` normalises to zero). Level-dependent cases get a small "plan" evaluated per level instantiation; the trailing result type must provably not unfold to further Prop-domain Pis; anything else falls back to a dynamic computation with fvars and `infer`. Active only while a declaration is being checked. **Effect: NSE −24.4% instructions (339.3 → 256.7 G), proof-irrelevance fallbacks −99.8% (1.41 M → 3.3 K), beta reductions −54.4%** (commit `c87c5b4`).
- **Negative cache `_neq` for every def_eq** (the reference kernel caches only the same-head failure). Sound because a handle pair determines the answer within a store (fvars are canonical, §1.8).
- **Spine congruence pre-pass** (step 4; "modeled on nanobruijn's spec_app_congruence", −1.4% instructions and −16% peak memory on init, commit `b249a47`).
- **Lazy projection equality** and **closed-ite / Bool short-circuit / Nat-binop ordering** heuristics (steps g, 6b-6d).
- Stats from the earlier object kernel on init, still indicative of where def_eq calls end: "83% of the 129M def_eq calls resolve by pointer identity, the eqv union-find hits 86% of the calls that get past it"; the failed-pair cache had only 952 hits in 286 k probes but each "is insurance against re-exploring a pathologically expensive same-head args refutation" (commit `4746484`).

### 1.8 Type inference

`infer(h, check)` (`machine.py:9570-9607`), two modes as in Lean:

- `check = false` (everything reached from inside def_eq/whnf): only computes; memo in `M_INFER` / `leaf_infer`. An application's type is obtained by walking the head type's Pi telescope **without instantiating**, keeping a count `plen` of pending arguments and substituting them all at once only when a non-Pi is hit or at the end (`_infer_app_only_buf`, `machine.py:11134-11172`).
- `check = true` (only the declaration's own type and value): additionally argument-type-vs-domain def_eq, binder domains are sorts, let values, universe params declared, and the reference rules (declared earlier, not less safe). The memo is the same `M_INFER` slot, validated by `M_CHECKED_GEN >= machine.check_gen` — one generation counter instead of a second cache.

Tricks:

- **Canonical free variables** (`fvar_for_domain`, `machine.py:9505-9534`): opening binder record `h` whose instantiated domain is `d` always yields the same fvar leaf, via the map `(h, d) → fvar`. Re-opening a shared lambda therefore produces the *same* instantiated body handle, so every memo (infer, whnf, def_eq) hits. The fvar's type lives in `leaf_infer`. (For top-level telescopes a second map `(position, domain) → fvar` gives consecutive declarations with a common `variable` prefix the same fvars, `_top_fvar`, `machine.py:9546`.)
- **`inst_eq`: compare instead of instantiate** (`inst_eq_buf`, `machine.py:3120-3125`; commits `d749f43`, `489f178`). When checking argument *i* of `f a₁…aₙ`, the domain is `Aᵢ[a₁…aᵢ₋₁]`. Instead of building it, the inferred argument type (a handle) is compared structurally against the *pattern* `Aᵢ` + the pending arguments in the spine buffer (budget 512 steps). Only on mismatch is the domain instantiated and `def_eq` called.

  ```
  inst_eq(pat, args[0..n), target, depth = 0, budget = 512) -> bool      # "instantiate(pat, args) == target ?" without consing
    if looseRange(pat) <= depth: return pat == target                     # closed part: handle identity
    if leaf(pat):  # a loose bvar
        k = bvarIndex(pat) - depth                                        # k >= 0 here
        if k < n:  return args[k] == target and (depth == 0 or looseRange(args[k]) == 0)   # no shifting ⇒ else give up
        return target == bvar(bvarIndex(pat) - n)
    if --budget < 0 or leaf(target) or kind(target) != kind(pat): return false
    APP:           iterate down both spines: inst_eq(arg_p, arg_t) for each, then heads
    LAMBDA/FORALL: inst_eq(type_p, type_t, depth) and inst_eq(body_p, body_t, depth + 1)
    PROJ:          same (name, index) and inst_eq(struct_p, struct_t)
  # false means "unknown": the caller instantiates and runs the full def_eq
  ```

  The same test against an *expected type* lets `_infer_app` return the expected handle without instantiating the result type (`infer_expected`, `machine.py:9609`), and a pattern-vs-pattern variant (`inst_eq_inst_buf`) handles nested applications; there is even an AST-direct variant that never imports the constant's type (`_ast_inst_eq`, `machine.py:3137`).
- **Joint telescope check** (`_check_value_joint_ast`, `machine.py:12600-12688`; commit `8d7da87`: init −4.7%, cslib −5.9%, NSE −7.3% instructions): for `def/theorem c : ∀ xs, T := λ xs, v` with identical leading domains, each domain is checked once, the fvars are shared between type and value, and `v` is inferred with `T` as expected type; then `def_eq(T, typeof v)`.
- **Inference without substitution for heavy terms** (`_infer_open*`, `machine.py:10249-10450`; commit `1f0405e`): once a declaration's store exceeds 2¹⁷ records, a term of ≥ 256 nodes under binders is inferred against a *context* (list of fvars / let values, plus a consed "chain" handle used as cache key) and sub-terms are materialised only when light or when a type comes to depend on them. `perf/app-lam`: 32 B → 0.43 B instructions, 10 GB → 86 MB. **Important counter-datum for an environment-machine design: "Inferring everything this way cost init 4%, and probing every binder for heaviness 6%"**, because what is inferred of a closed (fvar-instantiated) term is shared across all occurrences at any depth, whereas what is inferred of an open term under an environment is not.
- Projection types: pre-stripped field domain per structure; a field type that is a bare parameter is read off the spine (`machine.py:11421-11462`).
- `_cheap_beta` on inferred lambda body types (only when the beta is free: body closed or a bare variable, `machine.py:11302`).

### 1.9 Declaration admission

- **def/theorem/opaque**: `check_value` (`machine.py:12690-12727`): value closed; type's type whnf's to a sort (Prop for theorems); `def_eq(type, infer_expected(value, type))`. Theorem and opaque values are dropped after a successful check (`objects.py:5411-5430`, `5385`).
- **Ordering and safety** (`TypeChecker.check_reference`, `environment.py:182-206`; commit `463a310`): each declaration gets an `index`; a constant may refer only to earlier indices, except within its own group (an inductive block; an unsafe definition with its mutual block; a partial mutual block), with staged scoping inside inductive blocks (`STAGE_PRE_BLOCK`, `STAGE_IND_ONLY`); a referenced declaration may not be less safe. Arena tests fixed by this: `014_selfProof`, `141_falseFromUnsafe`, `142_falseFromPartial`.
- **Inductives**: recursors are **regenerated and compared** (`_inductive.py`: `collect_members` incl. nested occurrences as extra block members, `check_types`, `check_constructors` with strict positivity and universe checks, `init_elim_level`, `is_k_target`, `recursors`; entry `recursors_of`, `_inductive.py:1201-1234`). A declared recursor must have a name the kernel would generate, the same universe params, counts and K flag, and type and rule RHSs `alpha_eq` to the generated ones, falling back to "well-typed and def_eq" (`objects.py:6199-6345`). On init-prelude, Std and Mathlib all recursors were alpha-equal without the fallback (commit `a31ca02`). A constructor must be listed on its inductive (`objects.py:6066-6080`).
- **Quot**: names, universe arities, prerequisites (`Quot` before `mk/lift/ind`; `Eq` with `Eq.refl` of the prelude shape for `lift`) and def_eq of each type against the canonical signature (`objects.py:5485-5750`; commit `076fbf8`, arena `bugs/quot-bad-type`).
- **Axioms**: accepted if the type is a sort (`objects.py:5455-5466`); no allow-list. `Lean.reduceBool`/`reduceNat`/`trustCompiler` are not special-cased anywhere (grep finds no occurrence) ⇒ such constants are opaque and proofs needing native reduction fail def_eq and are rejected.
- Loose bvars and undeclared universe params are rejected (`LooseBoundVariable`, `UndeclaredLevelParam`; commit `8b20c3a`).

### 1.10 The closed-term CEK machine and the JIT (`_eval.py`)

- **Trigger**: only steps (a) of §1.7 and Nat-op argument evaluation; disabled under tracing. Any stuck neutral, opaque constant, free variable, K-recursor on a non-constructor, or unsupported shape raises `_EvalBail` and the ordinary machine continues (`_eval.py:2003-2031`).
- **Compilation**: a per-`Environment` `CodeCache` compiles `W_Expr`/handles once into immutable `CodeNode` graphs (`CVal, CBVar, CLam, CApp(fn, args[]) (flattened spine), CLet, CProj, CCtor, CFullCtor, CDef (lazy body), CRec (rules pre-resolved), CNatOp, CQuotLift, COpaque`; `_eval.py:481-703`, `969-1389`). Types and constructor *parameters are erased* at compile time; 0-field constructors are shared singletons; 1- and 2-field constructors have dedicated classes without arrays; the 14 Nat ops are first-class native closures so "typeclass-dispatched arithmetic never unfolds to unary `Nat.brecOn`"; small Nats are unboxed machine words (`VSmallNat`).
- **Machine**: state `(code, env, args, cont)`; `Env` = immutable linked list of slots (de Bruijn); a slot is a `Val` or a `Thunk{code, env, val}` updated in place when forced (call-by-need); continuations `KUpdate, KProj, KRecMajor, KNatOp1/2, KNatSucc, KQuotLift` (`_eval.py:314-476`, loop `1568-1998`). `Nat.rec` on a literal builds a lazy `RecNatThunk` for the IH.
- **JIT**: `JitDriver(greens=['rec_edge', 'code'], reds=['steps','env','args','cont','cache','tc'])` (`_eval.py:1421-1426`); `jit_merge_point` at the top of the loop (`1578`); **`can_enter_jit` only on true recursive back-edges** — after an iota step on a recursive rule or `Nat.rec` on `n > 0` lands on the minor premise (`rec_edge` flag set at `1827-1850`, `1894`; consumed at `1654-1658`, `1762-1776`; `can_enter_jit` at `1994`). `jit.promote` on the code pointer of forced thunks, closure lambdas, constructor nodes, recursor nodes, projection indices (`1753-1980`); `_immutable_fields_` everywhere, quasi-immutable `body?`/`is_compiled?` on `CDef`; `@jit.elidable` on `CRec.rule_index`; `@jit.unroll_safe` on the small arg/field loops; `@jit.dont_look_inside` on compilation, bigint ops and environment lookups deeper than 8. No virtualizables; the linked `Env`/`ArgNode`/`Cont` objects rely on the tracer's allocation removal. No thresholds or trace limits are set in the source (RPython defaults apply; `--jit` passes user parameters through, `_rlib/rcli.py:52-57`). Warm-up cost is kept small structurally: nothing enters the machine unless a closed term meets a scalar, and nothing is counted as hot except recursor back-edges.
- **Measured** (commit `dd8949e`): `perf/magma-list-deep-n36` 113.8 B instr / 64.5 s / 13.5 GB → 1.81 B / 0.11 s / 94 MB, of which "12.4× from the JIT alone vs 22.4B with `--jit off`"; `perf/beta-ladder` 3.51 B → 0.28 B; `perf/grind-ring-5` 5.90 B → 5.08 B. The interpreter-level change (call-by-need machine, erased params, native Nat) is worth ~5× and the JIT a further ~12× on these loop-heavy tests; neither moves library totals.

### 1.11 Scheduling, warm reuse, memory management

- No parallelism. The unit of work is one declaration, in file order.
- **One machine reused for all declarations** (commit `7af8244`, −5% instructions on init/std): reset cost is proportional to what was used (zero the used prefix of memo/leaf arrays; delete consing entries record by record unless > 1/16 full, then memset; `machine.py:1306-1433`), tables grown by one huge declaration are given back above `_STORE_KEEP_CAP = 2¹⁸` records.
- **Warm cross-declaration caches** (`acquire`, `machine.py:12809-12867`; commits `b9a7d66`: NSE −25.8% wall; `ea2606b`: thresholds doubled, NSE −8.0%; `85a2dc4`: −2.3%): for consecutive standalone def/theorem/opaque declarations of the same environment, arriving in increasing index order, the store is **not reset** while it stays below `_WARM_MAX_RECS = 196 608` records, 65 536 leaves and 131 072 eqv/neq/failed entries (`machine.py:452-457`, `7496-7507`). Pure memos (whnf, whnf_core, infer in non-check mode, union-find, neq, import) stay valid because the environment only grows. Check-mode results are re-validated by bumping `check_gen`, except (i) consecutive *safe* declarations whose universe-parameter set includes the previous one's share a generation, and (ii) closed, level-param-free, safe sub-terms referencing only earlier declarations get the permanent stamp `_CHECKED_PERM` (`machine.py:458-462`, `10238-10246`, `10469-10476`). A rejection under a warm machine is **re-checked on a cold machine** before being reported (`environment.py:480-487`).

  ```
  acquire(decl):                                                     # machine.py:12809-12867
    warmable = decl is standalone def/thm/opaque (group < 0, has an index) and no tracing and no memory limit
    if warmable and spare machine exists and same Environment and decl.index >= machine.lastIndex
       and store.nrecs < 196608 and nleaves < 65536 and eqv/neq/failed < 131072 each:
         if decl is safe and previous cohort was safe and prev.levelParams ⊆ decl.levelParams:
              keep check_gen                                         # verified subterms stay verified
         else check_gen += 1                                         # re-run reference/level/safety checks lazily
         can_perm = decl is safe;  decl_start_recs = store.nrecs;  open_memo.clear()
         return machine                                              # whnf / infer / eqv / neq / import memos stay
    else: machine.reset() (cost ∝ used entries);  check_gen = 1;  return machine
  release(machine, failed): if failed or not warm-ready: reset
  ```
- Memory: live GC heap = names, levels, types of all declarations, values of definitions, plus AST nodes not yet released by the refcount protocol; the kernel working set is unmanaged and bounded by the caps above except for a single huge declaration. Bounded caches: `_neq`/`_failed` 4 M slots × 8 B ("cutting mathlib peak memory footprint from 11.02 GiB to 6.53 GiB", commit `aeae8c5`), substitution memos 2¹⁸ slots, `_open_memo` capped. Removing the process-global `W_App`/`W_Proj`/`W_Let` intern dicts: "full Mathlib now peaks at 9.61 GiB / 6.31 GiB RSS" (commit `8b20c3a`); arena reports 4.4 GB at the pin.
- GC: RPython incminimark (generational, incremental major). The nursery estimate is floored at **32 MB** at translation time (`targetrpylean.py:14-46`): "cuts wall time ~29% on our benchmarks vs the 4 MB auto-estimate, and plateaus from there". `PYPY_GC_*` variables are used only for diagnostics in the history (`PYPY_GC_MAJOR_COLLECT=1.1` for true-live probes, commits `abcef98`, `f724408`); the arena run command sets none.
- Stack: at start the process raises `RLIMIT_STACK` towards 1 GiB (as far as the hard limit allows) and re-`exec`s itself once, then sizes RPython's stack budget and shadow stack from the real limit (`_stack.py`, `targetrpylean.py:84-88`). Recursive walkers call `stack_almost_full()` every 32 frames (`(rdepth & 31) == 31`) and raise `W_DeepRecursion` — reported as a *rejection* of the declaration.

Thresholds in one place (all from `machine.py` unless noted):

| Constant | Value | Meaning |
|---|---|---|
| `_WARM_MAX_RECS` / `_WARM_MAX_LEAVES` / `_WARM_MAX_EQV,NEQ,FAILED` | 196 608 / 65 536 / 131 072 | store stays warm below these (`:452-457`) |
| `_STORE_KEEP_CAP` | 2¹⁸ records | capacity kept across resets (`:452`) |
| `_OPEN_RECORDS`, `_HEAVY` | 2¹⁷ records since declaration start, 256 nodes | switch to substitution-free inference (`:497-501`) |
| `_MEMO_LIMIT` | 2¹⁸ slots | persistent substitution memos, wiped when full (`:506`) |
| `_neq`, `_failed` limit | 2²² slots × 8 B | negative caches, wiped when full (`:7467-7469`) |
| app / other consing key width | 21 / 20 bits per handle | above: promote table to wide entries (`:354-357`) |
| `_LGEN_SIZE` | 512 | direct-mapped (level params, levels) → generation cache (`:362`) |
| `_BVAR_ARR_LEN` | 256 | bvar leaves by index (`:361`) |
| Prop mask width | 62 argument positions | (`objects.py:4595`) |
| `inst_eq` budget | 512 steps | (`:3125`) |
| `_proj_lazy_eq` budget | 8 unfolds, depth 3 (2 for extra args) | (`:11943-11945`, `:11982`) |
| `_LAZY_DELTA_MAX_ITER` | 100 000 | (`:7443`) |
| stack probe period | every 32 recursive frames | (`:2506`, `:7765`) |
| `_REDUCE_POW_MAX_EXP` | 2²⁴ | native `Nat.pow` / `shiftLeft` decline above (`objects.py:3425`) |
| `_CONST_CACHE_SIZE` | 65 536 | direct-mapped `W_Const` cache (`objects.py:1033`) |
| parse chunk | 262 144 bytes | (`parser.py:2732`) |
| GC nursery floor | 32 MB | (`targetrpylean.py:38`) |
| stack wanted | 1 GiB via `RLIMIT_STACK` + re-exec | (`_stack.py:18`) |

### 1.12 Soundness-sensitive or fragile short-cuts — do not copy blindly

1. **Theorems are never delta-unfolded; their values are freed after checking.** The official kernel can unfold theorems (`constant_info::has_value` is `is_theorem() || is_definition()`, `lean4/src/kernel/declaration.h:447-449`, used by `is_delta`, `lean4/src/kernel/type_checker.cpp:551-559`). rpylean tried theorem delta (commit `fb4c26a`) and reverted it (commit `f484d44`) because it broke other Mathlib proofs in *its* strategy. Outcome can only be a wrong rejection, not a wrong acceptance, and Mathlib passes — but a proof that needs a theorem's body unfolded would be rejected. RLVM should keep theorem bodies re-materialisable (tenet-jvm does: lazily rebuilt from its compact store).
2. **Structure eta of a recursor's stuck major is applied even when the structure is a proposition** (`_to_cnstr_when_structure`, `machine.py:9448-9496`, no Prop test; commit `f484d44` "drops the Prop guard"), whereas the reference at v4.36 returns early for propositions (`lean4/src/kernel/inductive.h:61-72`). This is how rpylean reduces `And.casesOn h (…) : Rat` without unfolding the theorem `h`. It manufactures projections `h.i` out of Prop structures; illegal ones (data field of a Prop structure) raise in `_infer_proj` if their type is ever inferred. Per tenet-jvm's notes the reference itself changed this area in 4.37 (§2.6) **[unverified against lean4 master]**. Treat as version-dependent behaviour to be decided deliberately.
3. **Prop-argument masks.** Sound only if (i) the mask is exactly right for the instantiated head type, (ii) both applications are well-typed, (iii) all earlier arguments were found def-eq (so the two proofs have def-eq types). rpylean enforces (iii) by left-to-right order and (ii) by enabling masks only while checking a declaration; (i) rests on ~130 lines of syntactic analysis plus level "plans" (`objects.py:4651-4967`). An over-approximate mask is a soundness hole (unequal data arguments skipped). If copied: derive the mask by actual inference with fvars (rpylean's dynamic fallback, `machine.py:7604-7665`) or validate the static analysis against it in tests.
4. **Warm caches across declarations.** Equivalence classes, negative results and inferred types computed while checking declaration *n* are trusted for *n+1*. Sound because def_eq/whnf/infer(non-check) depend only on already-admitted declarations and handles are canonical — but it requires that *nothing* declaration-specific leaks into those memos: universe-parameter validity, reference ordering and safety are exactly the things re-validated per `check_gen`. `_CHECKED_PERM` additionally assumes monotone declaration order. Only failures are cross-checked cold; a wrong acceptance from a stale memo would go unnoticed.
5. **Canonical fvars shared between binders with identical (record, domain)** — sound because a closed binder record cannot contain itself and the fvar's type is the domain; the old object kernel had a real bug of this family when binders were interned ("`∀ p p, Eq p p` checks even where the declared signature was `∀ p a, Eq p a`", `objects.py:1342-1349`).
6. **Negative def_eq cache** and **bounded heuristics** (`_proj_lazy_eq` budget 8/depth 3, `inst_eq` budget 512, lazy-delta iteration cap 100 000): each must fail *open* (fall through to the complete procedure). They do in the code read; the iteration cap returns UNDEF, not false.
7. **Untyped CEK evaluation**: parameters and types are erased and K-like reduction is not performed there (bail instead). Any divergence between its reduction rules and the kernel's is a soundness risk confined to `decide`-style goals; rpylean returns the scalar as a *handle that then goes through the normal comparison*.
8. Resource failures are verdicts: deep recursion ⇒ reject (exit 1). For the arena a wrongly rejected good proof is ranking criterion 2, so RLVM should prefer not to convert resource exhaustion into "reject" **[inference; scoring details owned by the arena research doc]**.
9. Historical bugs that the arena caught in rpylean and that are cheap to test for: level order accepted `1 ≤ imax (u+1) v` (commit `6a33667`); `imax` not collapsing to `max` when the right side is never zero (9 Mathlib false rejections, commit `c126c31`); recursors checked for shape only (commit `a31ca02`); argument checks performed inside def_eq-internal inference rejected valid terms with beta-redex domains (13+ Mathlib declarations, commit `68de64f`); `is_prop` compared the sort syntactically instead of after whnf (commit `cf55c56`); the same-head args shortcut must be limited to *regular* definitions, not abbrevs (commit `8072e12`); failing a same-class structural comparison must still try eta/struct-eta (commit `34dc110`).

### 1.13 Build-level

`pypy rpython --opt=jit --output=rpylean-c targetrpylean.py` (`rpylean/justfile:59`); the arena build clones PyPy for the toolchain (`lean-kernel-arena/checkers/rpylean.yaml`). No PGO, LTO or target-cpu flags appear; the C is whatever RPython's default makefile produces **[flags not inspected]**. The micro-tuning that replaces PGO is manual: `@always_inline` on accessors, `@dont_inline` on every miss/slow path so that hit paths compile to leaf functions without callee-saved spills (commits `aeae8c5`, `90d0b58`: "`Machine.def_eq` (8 inst), `Machine.whnf` (16 inst)"), packing live values to cut ARM64 stack spills (commit `7e11d57`: 115 → 62 spills, −1.1% instructions), `zero=False` allocation of write-before-read arrays, tuple-free returns through out-fields.

### 1.14 Optimisation history with measured gains

| Date | Commit | Change | Measured |
|---|---|---|---|
| 2026-05-16 | `b6f3ab1` | GC nursery floor 32 MB | −29% wall |
| 2026-06-10 | `0a9a45d` | whnf_core/delta split, lazy delta one layer per step, UF eq cache, failure cache | `assemble₄._proof_3`: 4 M whnf steps / 23 GB / > 120 s → 5 948 steps / 2.5 s; 25 init decls > 30 s now pass |
| 2026-06-10 | `8072e12` | same-head args shortcut only for regular hints | last init timeouts → ~3 s |
| 2026-06-10 | `3bfeb58` | hash-cons reduction-built binders per declaration | init 381 → 162 s, peak 12.5 → 4.9 GB |
| 2026-06-10 | `1ba28a8` | tracing JIT off for kernel loops | init 162.4 → 136.7 s |
| 2026-06-10 | `4746484` | drop low-hit persistent intern probe | init 136.7 → 107.3 s |
| 2026-07-11 | `1f08589` | lazy cache side objects, flat intern tables | init 105 → 91 s, 5.22 → 4.31 GB |
| 2026-07-12 | `9ef360d` | explicit per-node id instead of GC identity hash | cedar 163 → 129 s (identity-hash machinery was ~14% of samples) |
| 2026-07-13 | `1f6d220` | refcount prescan + drop theorem values | cslib 14.8 → 8.6 GB and 1.5× faster |
| 2026-09-02 | `0866ac8`, `2ec1abf` | **raw TermStore machine** | init 752 B → 184-200 B instr, 3478 → 963 MB; cedar 1.24 T → 426 B; std 9.66 T → 348 B |
| 2026-09-02 | `c147f1d` | canonical levels on leaves; same-name heads | one decl 22 s → 1.2 s; a 71-min decl's file → 12.8 s |
| 2026-09-03 | `455e163` | object kernel deleted | Mathlib 850 s CPU |
| 2026-09-19 | `7af8244` | reuse one machine | −5% instr init/std |
| 2026-11-20 | `dd8949e` | CEK + JIT for closed scalars | deep-reduction tests 12-60×; libraries ±0 |
| 2026-11-21 | `b9a7d66` | warm cross-declaration caches | NSE −25.8% wall; Mathlib 173.5 s / 1.99 T |
| 2026-11-21 | `53c4861` | 32-bit arrays, one-shot subst memo, spine buffer | −10…−15%; Mathlib 1.73 T |
| 2026-11-21 | `bae6a21`…`ff75c0a` | packed consing tables, U32Map, intrusive import memo, level-gen caches, depth-0 subst | Mathlib 1577 → 1526 → 1439 → 1335 → 1285.5 G; 130.8 s |
| 2026-11-21 | `aeae8c5` | cap neq/failed caches | Mathlib peak 11.02 → 6.53 GiB |
| 2026-11-21 | `291ae24` | in-place chunk parsing, raw refcounts | footprint −48…57%; init −10.8% wall |
| 2026-11-21 | `c87c5b4` | **Prop-argument masks** | NSE −24.4% instr |
| 2026-11-21 | `90bc723`, `769f42b` | skip discarded binder types; BVar/Proj delta and simple-minor iota fast paths | NSE −3.4%, then 248.7 → 242.4 G |
| 2026-11-21 | `437cefa` | SWAR chunk parser | −5.1…−6.0% instr (NSE −3.0%) |
| 2026-11-22 | `ea2606b` | warm thresholds ×2, level-gen table 128 → 512 | NSE −8.0%, std −5.1% |
| 2026-11-22 | `8d7da87` | joint telescope check, expected types | NSE −7.3%, cslib −5.9%, init −4.7% |
| 2026-11-22 | `85a2dc4`, `489f178`, `ecb00ac`, `e28f6b9`, `949fc0a`, `1f30f95`, `a264931`, `7e11d57` | check_gen reuse, pattern matching of expected types, fewer fvar openings, compact name tables, register-pressure work | 0.3-2.3% each |

Net: init 752 B (object kernel, Sept) → 28.7 B (pin): **26×**, of which ≈4× from the store, the rest from caching policy and work avoidance.

### 1.15 RPython mechanism → Truffle/Graal equivalent

| RPython (where used in rpylean) | Purpose | Truffle / Graal / HotSpot equivalent |
|---|---|---|
| `JitDriver(greens=[rec_edge, code], reds=[…])`, `jit_merge_point` (`_eval.py:1421, 1578`) | meta-trace the CEK loop keyed on the Lean code position | One Truffle `RootNode`/`CallTarget` per compiled Lean definition or rule RHS; `CodeNode` → `Node` subclasses with `execute`; compilation is per call target (method-based partial evaluation) rather than per trace |
| `can_enter_jit` only at recursor back-edges (`_eval.py:1994`) | count hotness only where loops are | Call-count / loop-count thresholds (`engine.FirstTierCompilationThreshold`, `engine.LastTierCompilationThreshold`), `LoopNode` + OSR for self-recursion through IHs; keep cold code in the interpreter |
| `jit.promote(x)` (`_eval.py:1753-1980`) | make a runtime value a guarded trace constant | Inline cache: `@Specialization(guards = "code == cachedCode", limit = "N")` with `@Cached`; `DirectCallNode` vs `IndirectCallNode`; `CompilerDirectives.isPartialEvaluationConstant` for assertions |
| `_immutable_fields_`, `fields[*]` | let the JIT fold reads from constant objects | `final` fields, `@CompilationFinal`, `@CompilationFinal(dimensions = 1)` arrays, `@Child`/`@Children` |
| quasi-immutable `'body?'`, `'is_compiled?'` (`_eval.py:607`), `'tracer?'` (`environment.py:281`) | field treated as constant; a write invalidates compiled code | `@CompilationFinal` + `CompilerDirectives.transferToInterpreterAndInvalidate()` on write, or an `Assumption` checked in compiled code |
| `@jit.elidable` (`_eval.py:659`, `objects.py:67`) | pure call folded when arguments are constants | methods on PE-constant receivers fold automatically; otherwise `@TruffleBoundary` + cache in a `@CompilationFinal` field |
| `@jit.unroll_safe` (`_eval.py:1464-1531`) | unroll loops with small constant trip counts | `@ExplodeLoop` |
| `@jit.dont_look_inside` | residual call, opaque to the tracer | `@TruffleBoundary` |
| allocation removal of `Env`/`ArgNode`/`Cont` (no virtualizables are used) | keep interpreter state in registers | Graal partial escape analysis; `VirtualFrame` slots for locals instead of linked environments |
| `lltype.malloc(flavor='raw')` arrays (`machine.py:186-326`) | data the GC never traces or moves | `int[]`/`long[]` (a primitive array has no references, so the collector never scans its contents; it may still be copied while young, so allocate the big ones once and keep them) or off-heap `MemorySegment` |
| `@always_inline` / `@dont_inline` hit/miss splitting | leaf-function fast paths | small hot methods + out-of-line slow paths. HotSpot C2 inlines by bytecode size (`MaxInlineSize=35`, `FreqInlineSize=325` defaults); the Graal compiler uses its own heuristics, so verify with compilation logs. `HostCompilerDirectives.InliningCutoff` for Truffle host code |
| nursery floor via patched `estimate_best_nursery_size` (`targetrpylean.py`) | fewer minor GCs | `-Xmn` / `-XX:NewSize` |
| `rstack.stack_almost_full()` + re-exec with larger `RLIMIT_STACK` | deep recursion | checker thread with explicit large stack size (tenet-jvm `Main.java:31-41`); catch `StackOverflowError` at the declaration boundary |
| `rbigint`, unboxed `VSmallNat` | Nat literals | `long` fast path with `Math.*Exact`, `BigInteger` slow path |
| identity dicts on interned `Name`; inline cache fields `Name._decl_cached` | no hashing on hot lookups | put the data in a field of the interned object; avoid `HashMap`/`IdentityHashMap` |
| `compute_identity_hash` was 14% of samples (commit `9ef360d`) | — | avoid `System.identityHashCode` in hot paths; explicit `int id` field |
| weak-dict interning, maps/shapes | **not used** in rpylean | — |

### 1.16 What RLVM cannot get from Truffle here

The kernel's hot loops (`cons_app`, `_inst_*`, `whnf_core`, `def_eq`) operate on *data* (handles whose kinds vary unpredictably), not on a program with a stable position to specialise on. rpylean's measurement (finding 2) says a trace/PE compiler has nothing to fold there and its bookkeeping costs ~15-19%. On the JVM these loops should be ordinary Java, kept monomorphic (switch on an `int` kind, no virtual dispatch, no boxing), compiled once by the top-tier compiler — and since compiler threads are billed, the size and number of hot methods is a budget item. Truffle belongs where rpylean put its JIT: evaluating closed Lean code (`decide`, `Nat.rec`/`List.rec` loops, recursor-heavy reflection), behind an explicit boundary, entered rarely.

---

## 2. tenet-jvm post-mortem (what not to do on the JVM)

### 2.1 Facts

- Arena: 27.2 min virtual = **9.79e12 instructions**, 11.8 GB, single checker thread (`lean-kernel-arena/checkers/tenet-jvm.yaml`: `build: make`, `run: bin/tenet-jvm "$IN"`). Its README reports 30.1 min wall on the arena runner and 14-19 min on an Apple-silicon laptop with `-Xmx9g`.
- ~3 500 lines of plain Java, no dependencies, compiled by `javac` (`Makefile`). Launcher (`bin/tenet-jvm:5`): `java ${JAVA_OPTS:--XX:+UseParallelGC -XX:MaxRAMPercentage=75} -cp out tenet.Main`.
- It is a faithful port of the reference algorithm (substitution-based, locally nameless, `HashMap` caches), by design: "matching its decisions is what makes an independent implementation useful as a cross-check" (`docs/design.md`).

### 2.2 Architecture (at `4cc6e20`)

`ExportReader` (line → `Json.parse` → `Map`) → `ExprStore` (flat `byte[] kind`, `int[] a,b,c,d`: ~17 B/node; expressions become `Expr` objects on demand via a memo) → `Kernel.add` → `TypeChecker` (infer / whnfCore / whnf / isDefEq / lazy delta) and `InductiveChecker` + `NestedInductive` (regenerate recursors, compare). Theorem values are built into a scratch memo and released after checking but can be rebuilt from the store (`Decl.Thm.value()`, `Decl.java:44-72`), so theorems remain unfoldable.

### 2.3 Where instructions and memory go (structural; no profile is published, so shares are **[unverified]**)

Parsing — every one of the >100 M lines:
1. `BufferedReader.readLine()` decodes UTF-8 into a fresh `String` (`ExportReader.java:101`).
2. `Json.parse(line)` builds a generic tree: a `LinkedHashMap<String,Object>` per object (two per `app` line), a `StringBuilder` + new `String` **for every key** ("app", "fn", "arg", "ie" are re-allocated and re-hashed on every line), boxed `Long` per number, `ArrayList` per array (`Json.java:40, 75, 99`).
3. Dispatch by a chain of `containsKey` probes: an `app` line pays "meta", "in", "il", "ie", then "bvar", "sort", "const", "app" (`ExportReader.java:120-170`).
Contrast: rpylean handles the same line with three 8-byte compares and three integer parses.

Term objects and equality:
4. `Expr` nodes are heap objects with five header fields (`kind` enum ref, `hash`, `looseBVarRange`, two booleans) plus children (`Expr.java:14-30`); "roughly 45 bytes" each by the author's estimate (`ExprStore.java` class comment).
5. Sharing is a *lossy* direct-mapped table of 2¹⁸ entries (`Expr.java:162-188`), so pointer equality is only probabilistic; every cache is therefore keyed by **structural** `equals`: `hash` compare, then a recursive walk with a 256-step budget, then a second walk allocating a `Pair` per node into a `HashSet` using `System.identityHashCode` (`Expr.java:194-240`, `293-339`).
6. `Name` is not interned; `equals` walks the component chain comparing strings (`Name.java:35-47`); `Env.get` is `HashMap<Name,Decl>.get` (`Env.java:8, 30`) and is called for every head constant in `whnfCore`, `deltaInfo`, `reduceRecursor`, `reduceProj`, structure-eta.

Checker caches:
7. Seven hash containers per declaration: `Map<Expr,Expr>` ×5 (+ `deltaStepCache`), `Set<List<Expr>>` ×2 (`TypeChecker.java:33-39, 527`); each entry is a `HashMap.Node` (≈32-48 B) and each pair lookup allocates `List.of(t, s)` (`TypeChecker.java:635, 642, 901`). They are re-allocated per declaration when larger than 256 entries (`TypeChecker.java:43-63`). The author notes that caching cheap `whnfCore` results "grows to tens of millions of entries on large Mathlib declarations and the checker ends up in GC" (comment at `TypeChecker.java:302-304`) — the JVM-specific failure mode of object-per-entry caches.
8. No union-find over proven equalities ("EquivManager reverted", commit `07e3d63`); at the pinned revision def_eq failures are cached only for the same-head case. The later commit `53cf8a4` added a general failure cache after one 226-node proof ran "25 min at 104 GB".
9. Fresh free variables on every binder opening: `long id = ++fvarCounter; lctx.put(id, new Local(…))` with `Map<Long, Local>` (boxing) (`TypeChecker.java:26, 31, 69-73`). Instantiated bodies are therefore new terms each time and no cache entry from a previous opening can hit (rpylean's canonical fvars exist precisely to avoid this).

Substitution and spines:
10. One generic `replace(e, Replacer f)` taking a lambda (`Expr.java:371-474`); all of instantiate / lift / lower / abstract / level-instantiate go through the same megamorphic `f.apply(e, off)` call site. Small terms are walked without a memo; after 4 096 steps a preallocated exception restarts the walk with a `HashMap<Key,Expr>` (a `Key` object and `identityHashCode` per node) — the first 4 096 steps are wasted.
11. `getAppArgs()` allocates an array *and* an `ArrayList` per call (`Expr.java:356-364`) and is called on every `whnfCore` of an application, every recursor/projection reduction and every `isDefEqApp`; `args.subList(…).toArray(new Expr[0])` in inference (`TypeChecker.java:180-214`). Level instantiation of a definition body is cached per constant (`unfoldCache`) but of a constant's *type* is not (`inferConst` re-instantiates, `TypeChecker.java:136-146`).

Memory and GC:
12. At the pin, types and definition bodies stay materialised as `Expr` objects for the whole run in `keptMemo` (`ExprStore.java:153-158`) on top of the flat store.
13. `-XX:MaxRAMPercentage=75` ⇒ a 12 GB heap limit on the 16 GB runner; with a throughput collector the heap simply grows to the limit before full collections reclaim it, so the reported 11.8 GB is the *limit*, not the need (the author measures a live heap of 2.86 GB for a 42.3 M-expression Mathlib slice at the pinned design and 1.16 GB after commit `f210e4d`). `-XX:+UseParallelGC` runs one GC worker per CPU; under the arena metric every worker's instructions, including work-stealing spin, are billed **[inference; the GC share was not measured]**.
14. Recursion: a 4 GiB-stack thread (`Main.java:41`); `StackOverflowError` inside one declaration is caught and counted as a *rejection* of that declaration (`Main.java:75-82`); `OutOfMemoryError` ⇒ exit 3.

What happened after the pin (HEAD `53cf8a4`): chunked store at ~10.6 B/node, declarations hold ids, object memo dropped beyond 2²¹ nodes (commit `f210e4d`: live heap 2.86 → 1.16 GB, 282 → 263 s on the slice), failure cache with cold re-check (`53cf8a4`). The direction — ids and primitive arrays instead of objects — is the same one rpylean took, but the checker itself still works on `Expr` objects and `HashMap`s.

### 2.4 Worth copying

- **Fail-closed verdict** (`Main.java:29-44`): exit code starts at 3; set to 0 only by a normal return after the whole file (non-empty, with a metadata record) was processed; every `Throwable` maps to non-zero; an empty input or a first line that is not the metadata record is a rejection (`ExportReader.java:113, 116`).
- **Large-stack checker thread**: `new Thread(null, runnable, "checker", 4L << 30)`; `main` only joins and exits. (Stack pages are committed on touch; a deep excursion stays resident afterwards.)
- **Derive, then compare** for inductives, including bookkeeping flags (`isRec`, `isReflexive`, `numNested`, `k`, `cidx`, `numFields`) and nested inductives via auxiliary types translated back (`docs/design.md`); counts and indices beyond 32 bits are refused rather than truncated (`ExportReader.java:85-90`); a loose bvar in an inductive declaration is refused up front.
- **Cold re-check of any rejection** when heuristics that can only cause false negatives were in play (HEAD) — same policy as rpylean's warm-machine re-check.
- **Explicit heap sizing rather than a percentage**: decide `-Xmx` from the measured live set. For RLVM: young generation sized explicitly (rpylean's nursery lesson), old generation small because the working set lives in primitive arrays, GC thread count fixed and small **[recommendation, to be validated by instruction counts]**.

### 2.5 Not worth copying

`-XX:MaxRAMPercentage=75`; ParallelGC with default thread count; `String`-per-line parsing; any `Map<String,Object>`; object-per-term with structural-equality caches; `HashMap`s created per declaration; boxed keys; lambda-parameterised traversals on hot paths; `List<Expr>` argument lists; mapping `StackOverflowError` to "rejected".

### 2.6 Kernel-rule notes from tenet-jvm that RLVM must track (from `docs/divergences.md`, not independently verified against lean4 master)

- Lean **4.37.0-rc1 changed three def_eq rules**, selected in tenet-jvm from the export's metadata version (`LeanVersion.kernelHasEta437`): (i) proof irrelevance is tried *before* the first `whnfCore` (lean4#15103); (ii) a recursor major that is a proof is not reduced, and structure-eta on the major is skipped only when the recursor has as many universe params as its inductive (so `And.rec` gets eta) (lean4#15371); (iii) structure eta against a *partially applied* constructor by applying both sides to fresh variables (lean4#15373). "Neither direction is the lenient one"; test exports `tests/exports/eta437.ndjson` and `acc436.ndjson` hold one direction each.
- `Nat` literal arithmetic must **not** refuse terms mentioning free variables inside `reduce_nat` (only the lazy-delta call site has that test): otherwise Lean 4.37's `Nat.trailingZeros_rec_eq_bits` compares two copies of 2⁶⁴ unarily.
- "Is this level zero" must be decided up to equivalence (`Sort (imax 1 0)` is `Prop`; lean4#14613), and a type whose sort does not reduce to a `Sort` is an error rather than "not a proposition" (lean4#14807).
- Level comparison bug class: `u+2 ≥ 1` decided false / `3 ≥ u+1` decided true by comparing offsets in the wrong case (commit `21307e3`) — found by the arena on navier-stokes-euler.
- String literals expand through `String.ofList` when the environment has it, else `String.mk`.
- `isDefEqApp` must compare arguments first-to-last ("a Mathlib declaration that ran for over 10 minutes now checks in about 3 s", commit `7a40037`).
- Cheap and full `whnfCore` results must not be mixed in one cache (found on `Int32` range instances in Init).

---

## 3. Ranked tricks and their JVM/Truffle realisation

Ranking is by measured effect in rpylean's history, then by how directly tenet-jvm's cost profile is the absence of the trick.

| # | Trick | Evidence | Realisation in RLVM (allocation cheap; pointer chasing, megamorphism, boxing, GC tracing and JIT work are the costs) |
|---|---|---|---|
| 1 | Hash-consed term store in primitive arrays, int handles, per declaration | init 4.1×, std 28× fewer instructions, 3.6× less memory (`0866ac8`, `2ec1abf`); GC tracing was ~45% before (`d3e30e2`) | `int[] recs` (4 ints/record), `long[] appTable` with the packed `(fn|arg|idx)` entry, handles as `int` with the low-bit leaf tag. No `Expr` objects in the kernel. Growth by doubling+copy is fine below the warm cap; pre-size from the previous declaration |
| 2 | Memo slots parallel to records; union-find inside them; negative cache as a packed-`long` open-addressed set | 83% identity hits, 86% UF hits of the rest (`4746484`); capped sets cut Mathlib peak 11.0 → 6.5 GiB (`aeae8c5`) | `int[] memos` (6 per record), `long[]` sets; clearing = `Arrays.fill` of the used prefix. Zero objects per cache entry (tenet-jvm's `HashMap.Node` + `List.of` per lookup is the anti-pattern) |
| 3 | Prop-argument masks: skip proof arguments in same-head comparisons | NSE −24.4% instructions (`c87c5b4`) | one `long` per declaration (+ level-dependent plans); compute by real inference first, add the static analysis only with a cross-check (§1.12 item 3) |
| 4 | Warm caches across consecutive declarations with check generations; reuse of one machine | NSE −25.8% wall, then −8%, −2.3% (`b9a7d66`, `ea2606b`, `85a2dc4`); −5% (`7af8244`) | keep arrays; bump an `int` generation instead of clearing; caps sized to stay cache-resident (rpylean: 196 608 records ≈ 13 MB). With parallel workers each worker has its own store ⇒ warm reuse per worker; file-order locality then matters for chunking |
| 5 | Lazy def_eq discipline: `whnf_core` never deltas; one delta layer per step; proof irrelevance before delta; same-head args only for regular hints; failure cache | `0a9a45d`, `8072e12`: 25 init declarations from > 30 s to seconds | algorithmic; language-independent |
| 6 | Fused import from the parsed AST: level substitution during import, generation-stamped intrusive memo, strip lambdas beta will discard, first-use level+argument fusion, shape-specialised unfolds (`λ…BVar`, `λ…Proj`) and iota (`simple_minor`) | Mathlib −7.2% (`5c6b8fe`); NSE −3.4% (`90bc723`), −5.2% cycles (`769f42b`) | parsed terms as a compact persistent int store (tenet-jvm's `ExprStore` layout is right for this); per-worker import memo `int[]` indexed by export id + generation stamp instead of a field on a shared object; per-declaration precomputed "unfold plan" objects |
| 7 | One-shot epoch-stamped substitution memo + multi-argument instantiate from a spine buffer | −10…−15% (`53c4861`) | `long[] callSlot`; `int[] spine` used as an explicit stack; no `List`/varargs; recursion depth guard by counter |
| 8 | Compare-instead-of-instantiate (`inst_eq`), expected-type propagation, joint ∀/λ telescope check, pending-argument Pi walks | NSE −7.3%, cslib −5.9% (`8d7da87`); `d749f43`, `489f178` | same code shape; in a closure/NbE design this is "conversion-check a closure body against a value without quoting" — keep the idea whichever evaluator is chosen |
| 9 | Streaming parse interleaved with checking; refcount prescan to free terms at last use; drop (but keep re-materialisable) theorem bodies | cslib 14.8 → 8.6 GB, 1.5× faster (`1f6d220`) | if the parsed export is kept as packed int arrays (≈11-17 B/node ⇒ ~1.2-1.8 GB for 107 M nodes) the prescan may be unnecessary; decide by peak-memory target |
| 10 | Fixed-shape SWAR parser for the three hot line shapes with a general fallback; in-place chunks | −5…−6% of total instructions (`437cefa`); footprint −50% (`291ae24`) | `byte[]` chunks or a mapped `MemorySegment`; 8-byte little-endian loads via `VarHandle`/`ByteBuffer.getLong` compared with constants; hand-rolled integer parse; no `String` per line |
| 11 | Call-by-need CEK machine with compiled code for closed terms, JIT only there, entered only against Bool/Nat scalars | 12-63× on deep-reduction tests, ±0 on libraries (`dd8949e`) | the Truffle part of RLVM: nodes per `CodeNode` kind, call targets per definition/rule, thunks, erased params, unboxed small Nats, native Nat ops; high compilation thresholds; `_EvalBail` ⇒ fall back to the kernel |
| 12 | No JIT bookkeeping on kernel loops | init 162.4 → 136.7 s when removed (`1ba28a8`) | kernel as plain Java behind `@TruffleBoundary`; measure total instructions including compiler threads; consider ahead-of-time compilation of the kernel if JIT cost is material **[to be measured]** |
| 13 | Young-generation sizing | −29% wall from a 32 MB nursery (`b6f3ab1`) | `-Xmn` explicit; keep survivors rare by not retaining per-declaration objects |
| 14 | Per-name inline cache of the declaration; head classification flags + height in one int per leaf | −4.8% (`7073bce`); `5af5b15` | `Decl` reference in the interned `Name`/constant object; `int flags` per leaf |
| 15 | Canonical fvars per (binder record, domain) | prerequisite for memo hits under binders; absent in tenet-jvm (§2.3 item 9) | `long`-keyed open-addressed map `(binder, domain) → fvarLeaf` |

---

## 4. What this says about the HOAS/NbE question

- rpylean reaches #4 **without** NbE: substitution on a hash-consed store, with memoisation making each substitution linear in the DAG and with most substitutions avoided (§1.4, §1.8). The leaders above it are one substitution-based hash-consing checker (nano-lean) and one NbE checker with hash-consed values (gruszka), so on Mathlib the evaluator family is not the discriminator; cache identity and work avoidance are **[inference from the leaderboard and the gain table]**.
- rpylean's history contains a negative result about *closures without canonical identity*: `W_Closure` (deferred substitution, commits `27874fd`, `0debdae`, `86da6e8`, `4619582`) gave batched beta, but "the fresh identity-hashed closures it allocates per beta poison downstream `W_App` identity checks" and it was withdrawn (commit `4c45e81`); forcing the same closure repeatedly had to be memoised (commit `e4f3ede`: `_instantiate_multi` was ~45% of a profile; 43.5 → 25.4 s) and the walk inside a force too (`7fe61f7`: → 8.0 s). **An NbE/closure design must give values a canonical identity (hash-consed environments/values, or caches keyed on `(code, env)` handles); otherwise whnf/infer/def_eq memoisation — which resolves the large majority of calls — stops working.**
- A second datum: environment-based inference (no substitution, `_infer_open`) was 4% *slower* than substitution on init for ordinary terms because results on open terms are not shareable across depths (commit `1f0405e`); it wins by 70× only on deeply nested heavy terms. A hybrid (environment for deep/heavy terms, instantiate-and-share for light ones) is what rpylean ships.
- Where rpylean does use an abstract machine with thunks (closed terms, §1.10), call-by-need plus erasure plus native Nat is worth ~5× before any JIT and the JIT ~12× more — exactly the workload where Truffle can pay for itself.
- Many of rpylean's late optimisations (`inst_eq`, pending-argument Pi walks, first-use fusion, never importing discarded binder types) are substitution-world emulations of what closures provide natively (a body plus an environment that is never materialised unless needed). An NbE kernel gets these for free but must then solve the identity/caching problem above.

---

## 5. Unverified / open

- Parse share of rpylean's Mathlib total and the final Mathlib instruction count on the authors' machine are not recorded (extrapolated ≈600 G on ARM64; arena 578 G on x86-64).
- The C compiler flags RPython uses for the translated binary were not inspected.
- tenet-jvm's split between parsing, GC, JIT and checking is not published; §2.3 lists mechanisms, not shares.
- The lean4 4.37 rule changes (§2.6) are taken from tenet-jvm's documentation; `refs/lean4` is on the 4.36 line, where `to_cnstr_when_structure` still returns early for propositions.
- Whether rpylean's Prop-mask static analysis is exactly equivalent to its inference-based fallback was not proven here; only its structure was read.
