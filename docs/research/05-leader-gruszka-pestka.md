# 05 - Leaders: Gruszka (NbE, #2), Pestka (substitution), Susz (binary export)

Scope: source-level deep dive of three repositories by the same author, read (not built, not run):

| repo | clone (refs/) | arena rev | clone HEAD read | language |
|---|---|---|---|---|
| gruszka | `refs/gruszka` | `ebc80dc` (0.1.2) | `88aca33` (2026-11-23) | Rust, 19.2 k lines in `src/` |
| pestka | `refs/pestka` | `cea66e6` | `f42d35e` (2026-11-24) | Aver (compiles to Rust / wasm-gc), 32.9 k lines |
| susz | `refs/susz` | crate 0.1.4 | `9fe3950` (0.3.0, same file format "Susz 1") | Rust |

All line numbers are at the clone HEAD. What HEAD has that the arena revs do not: gruszka milestone 52
(loop states), milestone 53 (derived rules, `src/tc/rules.rs`) and the `blits` fix; pestka decisions
`FieldReducedFirst` and `LossesCountAsHits`. Everything else described is in the arena revisions.
"Measured" numbers are quoted from the authors' docs, commit messages and `decision` blocks; I ran
nothing. Items I could not verify are tagged **[unverified]**.

---

## 0. Decision summary (read this first)

1. **Gruszka's whole design is "integers in flat arrays, nothing is ever built, everything is hash-consed,
   memos live inside the value record".** No heap objects per term or value, no reference counting, no GC:
   a worker frees memory by truncating arrays at the end of an 8 MiB *session*. Mathlib: 369 G instructions
   on 1 worker, 372 G on 4, 2.0-2.4 GB (M2 Pro, PGO); 445 G / 2.47 GB / 25.7 s on the arena's x86 runner
   including the ndjson->Susz conversion (`refs/gruszka/README.md:227-248`).
2. **The export is converted once to a binary columnar file and two thirds of it is never materialised
   globally.** Of mathlib's 105 M expressions, 67 M are reachable from exactly one theorem's proof; they
   stay as bytes and are decoded into a worker-local scratch arena only when that theorem is checked.
   Conversion of the 6.18 GB ndjson costs 32-35 G instructions (about 5.5 instructions/byte). Decoding
   the *whole* Susz file through the reader's visitor costs 13.6 G; Gruszka loads only MAIN (38 M
   expressions) in 1.24 s wall, instruction count not published
   (`refs/susz/README.md:69-76,305-313`, `refs/gruszka/README.md:153-156`).
3. **The same author's substitution kernel (Pestka) with every trick applied needs 1.07-1.11 T instructions
   for mathlib; the NbE kernel needs 0.37 T.** Same machine, same input, same "what not to compute" list.
   That 3x is the measured value of NbE + hash-consed values over locally-nameless substitution.
4. **Pestka compiled to wasm-gc on V8 (every term, map and closure a GC object) needs 5.78 T instructions
   and 11.3 GB for mathlib, 5.2x the native build** (`refs/pestka/docs/benchmarks.md:99-125`). This is the
   best available estimate of what an "objects everywhere" managed-runtime kernel costs on this workload.
   RLVM must not be that design.
5. The "what not to compute" list is cheap to implement and each item is worth 1-10 % (section 6):
   unread binder arguments never evaluated, proof arguments not compared, proofs recognised syntactically
   before inferring, casesOn/matcher go straight to the branch, congruence check after every delta step,
   failures cached.
6. **Cache reuse across declarations is the second big lever**: sessions that only move forward in file
   order, free variables named by their type so re-opened binders hit the caches, declarations grouped by
   namespace, contiguous chunks per worker. Pestka measured -9 % (namespace order), -18..20 % and a further
   -4..5 % (variable naming) on mathlib slices; Gruszka measured -5 % for contiguous parts vs round robin.
7. The elaborate machinery for pathological declarations (regions, ghost keys, ground machine, the race of
   lazy-delta strategies, deep-work trampoline) exists for CFSG-scale certificates and adversarial tests.
   On Init/mathlib it is within 0.2-0.7 % either way. RLVM should implement the reference order plus the
   cheap shortcuts first, and add budgets later only where the arena's perf tests require them.
8. Build-level: PGO trained on Init at 1 worker is worth roughly -11 % for Gruszka (419.9 G plain at
   0.1.2 vs 372 G PGO in the README; the two figures come from different milestones, so approximate) and
   -12..15 % for Pestka (stated directly); `lto = "fat"`, `codegen-units = 1`, `target-cpu=native`, mimalloc.

Budget implied for RLVM (x86 arena runner, instructions): 445 G total for the #2 place = about 35 G
ndjson->Susz conversion + about 410 G for Gruszka itself (load + check; the split is not published).
To beat it RLVM has about 400 G for *everything including JIT and GC*.

Where each requested topic is covered:

| topic | Gruszka | Pestka |
|---|---|---|
| architecture map, data flow | 2.1 | 3.1 |
| term / level / name representation, hash-consing | 2.2, 2.3 | 3.1, 3.2 |
| export parsing, intermediate format | 1 (Susz), 2.1 | 1, 3.1 |
| evaluation strategy, reductions, compiled forms | 2.4, 2.11 (rules) | 3.3 |
| definitional equality order, lazy delta differences | 2.5, 2.6 | 3.5 |
| type inference, caches | 2.7 | 3.4, 3.6 |
| declaration admission | 2.8 | 3.1, 3.7 |
| parallelism, sessions, stamping | 2.9, 2.10 | 3.7 |
| memory management | 2.11 | 3.6 |
| build tricks | 2.12 | 3.1, 3.8 |
| optimisation history with numbers | 2.13 | 3.3-3.8 |
| what not to compute + soundness arguments | 4 | 4 |
| ranked tricks + JVM realisation | 5 | 5 |
| fragile / soundness-sensitive | 6 | 6 |

---

## 1. Susz: the binary export (`refs/susz/docs/spec.md`)

### 1.1 Layout (Susz 1, normative sections 3-6)

```
file      = header body trailer
header    = "SUSZ\r\n\x1a\n" major:u16=1 reserved:u16 reserved:u32            ; 16 bytes
body      = MAIN [PROOFS] [INDEX] SOURCE      ; sections tile the body exactly, no gaps
trailer   = names:u32 levels:u32 exprs:u32 decls:u32          ; counts, hints only until verified
            section-count:u32 { id:u16 flags:u16 reserved:u32 offset:u64 length:u64 }*   ; 24 B each
            trailer-length:u32 "ZSUS"
```

Primitives: unsigned LEB128 varints (must be minimal, at most 10 bytes), `bytes` = varint length + data,
`nat` = minimal little-endian base-256 digits (maps directly to bignum limbs; decimal would be quadratic),
strings are strict UTF-8.

MAIN is a stream of tagged records; ids are implicit and dense per kind (names from 1, levels from 1,
expressions from 0, declarations from 0). Name and level fields hold ids; **an expression field holds a
back distance `d`** (the referenced expression is `next_id - d`), so references are small varints and can
only point backwards.

| tag | record | fields |
|---|---|---|
| 1 / 2 | name str / num | prefix name id, component (string / u64 varint) |
| 3 4 5 6 | level succ / max / imax / param | level ids / name id |
| 7 | bvar | index |
| 8 | sort | level |
| 9 | const | name, list of levels |
| 10 | app | fn back, arg back |
| 11 / 12 | lam / forallE | binder enum, name, type back, body back |
| 13 | letE | name, type, value, body |
| 14 / 15 | natVal / strVal | nat / string |
| 16 | proj | struct name, field index, expr back |
| 18 | declaration | kind 0 axiom, 1 definition, 2 theorem, 3 opaque, 4 quot, 5 inductive block, 6 theorem with proof block |
| 19 | frontier entry | kind (name/level/expr), back distance |

Dropped by packing: `mdata` (references are redirected to the wrapped expression), `letE.nondep`, the
export's own numeric ids. Declaration order is preserved exactly; it alone defines "declared before".

### 1.2 How "terms are never built" works

- **Ownership split (packer).** An expression reached only from one theorem's value (directly or through
  other such expressions) is written into *that theorem's proof block* in section PROOFS; every other
  expression stays in MAIN. The packer computes this with one backward walk over an `owner:u32` column
  (`refs/susz/src/pack/write.rs:1-10`). Mathlib: 38 M MAIN expressions, 67 M block expressions in 452 k blocks.
- **Frontier.** A block may reference MAIN items only through the *frontier*: tag-19 records in MAIN list,
  right after the item they name, every name/level/expression some block needs. Inside the block of theorem
  T: a name or level field is a frontier index; an expression reference `r` from the block's expression `k`
  means block expression `k - r` if `1 <= r <= k`, otherwise frontier expression `r - k - 1`. Frontier
  indices must have been listed before T's declaration record. So a block is decodable with only the three
  frontier tables and is position independent.
- **Kind-6 declaration record** carries `block-length` and `block-exprs`; blocks are laid out consecutively
  in PROOFS in declaration order, so a block's offset is a prefix sum computed while reading MAIN.
- **Rule P**: every `const`/`proj` name inside T's block must be declared before T (compared by name
  value). This is what makes out-of-order (namespace-grouped) checking sound without per-block marks.
- **Reader contract**: nothing in the file is trusted; no hashes, loose-bvar ranges or flags are stored;
  every count/offset is re-derived. Declared names must be unique (structural rule).
- **Gruszka's use**: MAIN expressions become rows of a 16-byte node column whose index *is* the Susz id
  (section 2.2). A kind-6 theorem keeps only a `BlockRef`. When a worker checks the theorem (or unfolds it)
  it calls `susz::read::decode_block` with a closure that appends the block's nodes to the worker's block
  arena at ids `m + len` (`refs/gruszka/src/tc.rs:5000-5040`), computes range and used-mask per node from
  its children, and uses the last node as the proof term. When the session ends the arena is truncated.
  No expression is ever interned, hashed or copied; the "term" a closure refers to is a `u32`.

### 1.3 The ndjson packer (what a competitive parser looks like)

`refs/susz/src/pack/{lines,par,pk,write}.rs`:
- Reads the file in 16 MiB chunks cut at newlines; never holds the text.
- **Positional parsing of the canonical line shapes lean4export writes**, generic JSON parser only as a
  fallback: 8-byte unaligned loads compared against literal key strings (`lit`), SWAR digit parsing 8
  digits at a time (`digits8`, `num`), strings scanned 8 bytes at a time for `"`, `\`, newline
  (`plain_str`), and **the line's own id is compared against the expected next id as ASCII digits instead
  of being parsed** (`Dg`, `lines.rs:11-200`).
- **Parallel**: the file is cut into `threads` ranges at newlines; each worker parses its range *assuming
  ids are dense* and writes columns at global ids into one shared allocation; a merge verifies the
  assumption range by range and falls back to a single-threaded parse if it fails (`par.rs:1-10`).
- State held: 1 kind byte + two u32 children + one u32 owner per expression (13 bytes x 105 M for
  mathlib; peak footprint 1.6-1.8 GB, max RSS 2.0-2.3 GB); finished columns are `madvise`d away as it goes.
- Cost, mathlib 6.18 GB -> 637 MB (M2 Pro): 1 thread 5.1 s / 34.0 G instr; 4 threads 2.2 s / 34.4 G;
  `--no-index` 31.9-32.4 G; cold cache 29-32 s. Susz is 10x smaller than ndjson.
- Reader fast paths (0.1.1): varints of 2-8 bytes with one 8-byte load, common records read from a fixed
  window with unchecked loads and re-read by the checked reader only when a check fails; `verify` of
  mathlib 8.1 G instr, `decode` with a visitor 13.6 G (5 G of it the visitor).

---

## 2. Gruszka

### 2.1 Architecture map

| file | lines | content |
|---|---|---|
| `src/main.rs` | 826 | CLI, mmap, namespace order, worker threads, verdict |
| `src/env.rs` | 1304 | Susz -> columns, constants table, known names, address-space regions, `Slab` |
| `src/level.rs` | 423 | level interner (global + per-worker layer), `max`/`imax` simplification, normalize, equiv, geq, subst |
| `src/tc.rs` | 9210 | `Worker`: values, eval, whnf, defeq, infer, sessions, declarations |
| `src/tc/spines.rs` | 319 | spines as slices, hash-consed neutral values |
| `src/tc/frames.rs` | 164 | pruned environments ("frames") hash-consed by content |
| `src/tc/names.rs` | 369 | "names": substituted-term ids, only in heavy declarations |
| `src/tc/region.rs` | 1498 | in-declaration memory give-back |
| `src/tc/ground.rs` | 2337 | call-by-need machine for closed computations past 2^26 values |
| `src/tc/rules.rs` | 867 | derived rules (HEAD only) |
| `src/induct.rs` | 1553 | inductive blocks, nested inductives, recursor regeneration |
| `src/quot.rs` | 183 | quotient package |
| `src/nat.rs` | 113 | thin layer over `num-bigint` |

Data flow: `scripts/arena-run` runs `susz pack --no-index $IN tmp` (exit 1 -> rejected, other failure ->
declined), then `gruszka tmp 4`. `main`: mmap file -> `env::load_part` (one pass of `susz::read::decode`
with a visitor that pushes nodes; blocks are *not* opened) -> level interning, level-list canonicalisation,
name->row table (parallel chunks) -> `Region::layout` -> `remap` (re-mmap the file over itself so the
pages copied into columns stop counting as RSS; repeated once a second while workers read blocks,
`main.rs:60-84,587-604`) -> namespace order -> worker threads (`check_decl` per declaration under
`catch_unwind`) -> first non-OK status in file order is the verdict (`main.rs:802-825`).

### 2.2 Term / level / name representation

**Term node** (`env.rs:36-61`), 16 bytes, `repr(C)`: `a:u32, b:u32, p:u32, rng:u16, kind:u8, pad:u8`.

| kind | a | b | p |
|---|---|---|---|
| BVAR 0 | - | - | de Bruijn index |
| SORT 1 | - | - | global level id |
| CONST 2 | name id | - | offset of level list in side table (0 = empty) |
| APP 3 | fn | arg | - |
| LAM 4..7, PI 8..11 (kind + binder info) | type | body | binder name id |
| LET 12 | type | value | index into `lets` side table (name, body) |
| NAT 13 / STR 14 | - | - | literal table index |
| PROJ 15 | struct expr | field index | structure name id |

Per node, parallel columns: `rng` = 1 + largest loose bvar index (0 = closed; >= 65535 declines) and a
**used mask** `u64` (bit i = loose bvar #i occurs, i < 63; bit 63 = some index >= 63), plus a second word
only for range > 63 covering #63..#125 (`env.rs:471-506`). Both are computed bottom-up while pushing
(`push_expr`, `env.rs:516-617`); `used_bind` shifts the mask when leaving a binder. The mask being *exact*
below 63 is what the skip rules (2.4, 6) rely on.

De Bruijn indices throughout; no locally-nameless instantiation ever happens. Binder names/infos are kept
in the node but never read for typing (binder info is read only by a heuristic that decides whether an
argument probe is worth trying, `explicit_mask`).

**One address range for all terms** (`env.rs:63-215`): 64 GiB (nodes) + 32 GiB (masks) + 32 GiB are
reserved with `mmap(MAP_NORESERVE)`; node `t` is at `base + 16 t` for MAIN *and* every worker's block
arena, so a term read is one unchecked load. Ids: MAIN `[0, main_len)`, then two overflow arenas, then two
slabs (session, scratch) per worker, all below 2^31.

**Names** stay Susz name ids; `st.name_canon[id]` gives a canonical id (equal by value); a dense
`row_of_name: Vec<u32>` maps any name id to a constant row. Known names (`Nat`, `Nat.zero`, `Nat.succ`,
14 Nat operations, `Nat.log2`, `String`, `String.ofList`, `List.*`, `Char`, `Char.ofNat`, `Bool.*`, `Eq`,
`Eq.refl`, `Quot.*`) are found once by path lookup (`env.rs:1192-1263`). `nat_op: Vec<u8>` per constant row.

**Levels** (`level.rs`): triples `(kind:u8, a:u32, b:u32)` interned in a global table at load, with a
per-worker layer on top; `max`/`imax` constructors simplify as Lean's `mk_max`/`mk_imax`; `normalize`
memoised per level id; `equiv` = equal ids or equal normal forms; `zeroish` = syntactic "is Prop for every
assignment". Level lists of CONST nodes are canonicalised by content at load so a list is named by its
side-table offset (`env.rs:1119-1157`).

**Universe instantiation is lazy.** A closure carries `ls: u32`, an interned pair (parameter-name class of
the constant, level list); `ls = 0` is the identity. Levels are substituted only when a SORT or CONST node
is evaluated (`lsub`, direct-mapped 2^12 cache + map, `tc.rs:2369-2414`). The identity substitution
(constant used at its own parameters) is mapped to `ls = 0` so those terms share everything known at
`ls = 0` (`mk_ls`, `tc.rs:2318-2352`).

**Constants**: `ConstInfo { name, lparams, ty, decl, group, kind }` in a `Vec`; visibility test at every
lookup and delta step: `c.decl < cur_decl || (c.decl == cur_decl && c.group < self_vis)` (`tc.rs:3215`).

### 2.3 Values, environments, spines and their hash-consing

```rust
type V = u32;                                   // index into the worker's `vals` slab
enum Head { FVar(u32), Const(row, level_list_ref), Stuck(V) }
enum Val {                                      // 16 bytes
    Sort(level), Lit(lit_index),
    Clo  { term, env, ls },                     // lambda or Pi term + environment + level subst
    CloI { term, env, ls },                     // "the type of this lambda" (lazy Pi, see 2.6)
    Neu  { head, spine },                       // fvar / constant / stuck value applied to elims
    Thunk{ term, env, ls },                     // delayed argument
}
struct Memo { whnf: u32,   // thunk: forced value; other: whnf
              core: u32,   // whnf_core result; bit 30 = "full" (else cheap-projection form)
              infer: u32,  // type of the value
              uf: u32 }    // bits 0..29 unfolding (NONE / "does not unfold"), bits 30..31 has_fvar tri-state
struct Rec { v: Val, m: Memo }                  // 32 bytes: value and its memos in one cache line
```
(`tc.rs:523-541, 600-618, 691-699`.) Value ids are < 2^28 per session.

- **Every value is hash-consed** (`intern`, `tc.rs:2518-2596`): key = two u64 words packing
  `(tag, a, b, c)` (`vkey`); table `vmap: HashMap<(u64,u64), V>` with FxHash. Fast paths before the map:
  sorts through `stab[level]`, constants without arguments through `ktab[row]` (both generation-stamped
  direct tables). One `entry()` probe finds or inserts.
- **Neutral values with a spine** are hash-consed as a whole in `Spines` (`spines.rs`): spine storage is
  one `u32` slab of `[len|flags, elim...]`, an elim is a value id or `0x8000_0000 | proj_index`; the table
  is open addressing with linear probing over `(hash tag, value id)`; head + all elims are hashed once
  ("one probe per neutral, where cons cells took one probe per cell"). Flags cache "has a projection" and
  "an argument has a free variable".
- **Environments** (`tc.rs:2629-2816`): env id -> `(start, len, frame_mask)` in `eloc`; entries live in one
  `eslots: Slab<u32>` oldest first; `env_get(env, i) = eslots[start + len - 1 - i]` (one load). `push_env`
  is hash-consed by key `(value << 32 | parent_env)`; if the parent's run ends at the top of `eslots` it is
  extended in place, otherwise the run is copied to the top first.
- **Pruning** (`prune`, `tc.rs:2708-2802`): before a closure or thunk is made, its environment is cut to
  the entries its term reads (`used_mask & ((1 << rng) - 1)`); unread entries become `HOLE`; the frame is
  hash-consed *by content* (`FrameTab`), with a direct-mapped `(env, mask) -> frame` cache in front. A
  closed term gets `EMPTY`. Consequence: two closures over environments that differ only where the term
  never looks are the same value id.
- **Canonical first-order applications** (`canon_app`, `tc.rs:3454-3580`): a delayed argument that is an
  application whose head is a visible constant or a bound variable holding a neutral value is not made a
  `Thunk`; it is made the `Neu` value it would evaluate to (no reduction is involved), arguments
  recursively. Equal substituted terms reached through different bodies become one id. Direct-mapped
  memo `cdm` keyed `(term, env, ls)`, 2^14 slots.
- **What sharing buys**: `defeq(a, b)` starts with `a == b`; every route to a computation shares its
  forced value, whnf, whnf_core, unfolding and type. The doc states that without it "two unfoldings of the
  same definition produce equal but separate graphs ... In Init, that was the difference between seconds
  and divergence" (`docs/architecture.md:105-113`).
- **Direct-mapped, generation-stamped side tables** (lossy; reset in O(1) by bumping `cgen`,
  `tc.rs:1981-1997`): `cdm` 2^14, `pdm` 2^14, `udm` 2^14 (used-entry ids), `tdm` 2^13 (structural
  comparison pairs), `lam_dm` 2^12, `cll_dm` 2^12, `lsub_dm` 2^12, `tnp_dm` 2^12. Sized for L2: shrinking
  `cdm` from 2^16 to 2^14 gave cycles -4..-5 % for +0.2..0.3 % instructions at 4 workers (`tc.rs:9131-9135`).

### 2.4 Evaluation (`tc.rs:3222-3758`)

Call-by-need NbE with defunctionalised closures. Pseudo-code of the hot loop:

```
eval(t, env, ls):                         # iterative in tail positions, one tick per iteration
  BVAR i   -> env_get(env, i)
  SORT l   -> intern(Sort(lsub(l, ls)))
  CONST c  -> decline if not visible; intern(Neu(Const(row, const_ll(t, ls)), EMPTY))
  LAM/PI   -> intern(Clo(t, prune(t, env), ls))
  NAT/STR  -> literal value (small Nats shared by value)
  PROJ     -> proj_val(eval(struct), idx)          # appends a projection elim to a neutral spine
  LET      -> env' = push_arg(value, env, ls, env, body); continue with body   # zeta by sharing
  APP      -> push all argument *term ids* on a shared argstack, f = head
              fv = eval(f)
              loop over args:
                fv = force(fv)
                if fv is Clo(lam):        # walk the lambda telescope as (term, env) pairs:
                    e2 = push_arg(arg_i, env, ls, cenv, body)   # NO closure value per lambda
                    if body is a lambda: continue with (body, e2)
                    if last arg: tail-call eval(body, e2, cls)
                    else fv = eval(body, e2, cls)
                elif fv is Neu:  return neu(head, spine ++ thunks of remaining args)   # one value
                else: fv = apply(fv, thunk(arg_i))

push_arg(a, aenv, ls, env, body):         # tc.rs:2619
  if rng(body) == 0:            return EMPTY          # closed body: no environment at all
  if used_mask(body) & 1 == 0:  return push_env(HOLE, env)   # argument never read: never evaluated
  return push_env(thunk(a, aenv, ls), env)

thunk(t, env, ls):                        # tc.rs:3381
  BVAR -> env_get;  leaves and binders -> eval_node (already values)
  APP  -> canon_app(...) if applicable else intern(Thunk(t, prune(t, env), ls))
force(v): if Thunk: memo.whnf or (eval(term, env, ls) then store)            # memoised, shared by id
```

- `beta_from` (`tc.rs:3732`) applies a lambda to already-made values the same way (used by iota and
  unfolding): lambdas are peeled as (term, env) pairs, only the result is a value (Pestka's
  `RuleLambdasAtOnce`).
- **Delta** (`unfold`, `tc.rs:5063-5133`): memoised in the value record. Unfolds definitions of any hint
  and theorems (a theorem's block is decoded on demand, `decl_value`), never opaques/axioms; requires as
  many levels as parameters and no projection in the spine. Before plain beta of the body it tries
  `cases_direct` then `match_direct`. A Nat operation without free variables is computed instead of
  unfolded.
- **casesOn direct** (`cases_shape`, `cases_direct`, `tc.rs:4596-4846`): a definition is recognised *by the
  shape of its value*, not its name: `np + 1 + ni + 1 + nm` lambdas over the recursor (one motive, at the
  definition's own levels) applied to them in recursor order, each minor wrapped as
  `fun fields ihs => minor_j fields`. Then: bring only the major premise to a constructor (`rec_rule`),
  apply that constructor's minor to the fields and the remaining arguments. The motive and the other
  minors are never touched. K-like recursors are excluded. The per-constant result is cached in a shared
  atomic byte array.
- **Matcher direct** (`match_shape`, `match_direct`, `tc.rs:4722-4822`): a definition whose body under its
  lambdas is an application of a casesOn-shaped constant: build the environment from the arguments,
  evaluate *only* the major-premise argument term and, after `rec_rule`, *only* the chosen alternative.
- **whnf_core** (`tc.rs:4333-4475`): loop of head steps: projection (structure reduced by full `whnf`, or by
  `whnf_core` only in the *cheap* mode used first by defeq; a structure that does not reach a constructor
  leaves the projection untouched), recursor iota, `Quot.lift`/`Quot.ind`. Result memoised (cheap and full
  share one word; a full result answers a cheap query, never the reverse).
- **iota** (`iota`, `rec_rule`, `tc.rs:4515-4588`): major index = params + motives + minors + indices;
  K-like: `to_ctor_when_k` (infer major's type, whnf, build the nullary constructor at the type's params,
  check its type is defeq); else structure eta *before* reducing the major (`to_ctor_when_structure`,
  only when the recursor eliminates into every sort, lean4#15351/#15371); then `whnf(major)`; Nat literal
  -> `Nat.zero` / `Nat.succ (n-1)`; string literal -> `String.ofList [Char.ofNat ..]`; rule looked up by
  constructor name; `rhs` beta-applied to params, motives, minors, the last `nfields` constructor args and
  the arguments after the major.
- **whnf** (`tc.rs:5138-5192, 5487-5511`): `loop { c = whnf_core(t); if reduce_nat(c) -> done; match
  unfold(c) { Some(u) => t = u, None => done } }`, result memoised on the original value.
- **Nat literals** (`reduce_nat`, `tc.rs:4277-4327`): 14 binary operations + `succ` + `log2` on `BigUint`
  after `whnf` of the operands; first operand must be a literal before the second is forced; results over
  128 MiB reject (Lean's `LEAN_NAT_MAX_SIZE`). Small Nats are interned by value (`nat_map`), big ones by a
  content key (length + 3 limbs, confirmed by comparison).
- No special compiled form for `brecOn`/`below`/well-founded recursion at the arena rev; HEAD adds derived
  rules (2.11).

### 2.5 Definitional equality: exact order

`defeq(a, b)` (`tc.rs:6509-6754`):
1. `a == b` -> true (inlined at call sites).
2. tick; same `(term, env, ls)` identity for thunks/closures -> true.
3. inside a budgeted attempt: if the pair is marked "undecided with at least this much budget" -> stop the
   attempt now (never an answer).
4. `eq_cache` hit: `HashMap<ordered (V,V) as u64, u8>` holding **both outcomes** for the session.
5. both sides closed MAIN thunks/closures: cross-declaration table `eqp` (true results only, stamped with
   the declaration that found it, used only if `stamp < cur_decl`).
6. `closure_congr`: bounded structural walk (`same_val`, fuel 16384 nodes) comparing terms node by node
   with bound variables looked up in the environments, neutral spines elementwise, forced thunks by
   value; only `true` decides; decided structural differences are cached as a separate bit. Skipped for
   two lambdas of different terms (cost 0.4-0.75 % and decided little) and, after 400 k wasted nodes in a
   declaration, for same-head neutral pairs.
7. heavy declarations only (1 M values or 10 k unfoldings): "names" (2.9).
8. `defeq_core`; cache the outcome.

`defeq_core(a, b)` (`tc.rs:7224-7292`):
1. force both; `quick`: sorts by level equivalence; literals by content; lambda/lambda or Pi/Pi by
   `binder_defeq` (domains, then bodies under one shared fresh variable) - final.
2. *ProofsComparedBeforeReduced*: if either side is a recursor applied at least to its major whose motive
   ends in `Sort 0` at these levels (read syntactically from the recursor's type), answer
   `defeq(infer(a), infer(b))` - final.
3. `whnf_core` with cheap projections on both; if anything changed: `quick` again, then structural
   `quick_parts` (only true decides).
4. proof irrelevance (`proof_irrel`, `tc.rs:7499`): skipped if `a` is a sort, literal or Pi, or
   `surely_not_proof(a)` (syntactic: the head constant's/variable's declared type past `n` Pis ends in a
   sort, or in a family whose sort is never zero at any levels; projections read the field's type in the
   constructor); otherwise infer `a`'s type, and if it is a Prop the answer is `defeq(type a, type b)`.
5. `lazy_delta` (2.6) -> decided, or the stuck pair `(a2, b2)`.
6. `proj_eq`: both sides end in the same projection field: with trailing arguments, `whnf_core` first and
   compare the results if either changed, else compare projection prefixes then arguments; bare
   projections: `lazy_delta_proj` on the two structures, then **only the asked field** (constructor field
   vs constructor field, or field vs projection of the other side).
7. otherwise `defeq_app`: same fvar / same constant with equivalent levels / stuck heads, spines pairwise
   (with proof positions skipped for constant heads).
8. full `whnf_core` of both; if anything changed, restart `defeq`.
9. lambda eta (both directions); structure eta (`try_eta_struct`: types equal, then fields against
   projections, proof fields skipped); string literal vs `String.ofList` application; unit-like types
   (structure with zero fields: equal iff types equal); else `false`.

Deviation from the reference order as I recall it **[unverified against lean4 source here]**: the reference
retries full `whnf_core` before application congruence; Gruszka tries congruence first. Sound (a `true`
from congruence is a `true`; a `false` falls through).

### 2.6 Lazy delta: the reference loop and every deliberate difference

Reference loop R (`lazy_delta_loop`, `delta_step`, `tc.rs:8043-8229`), per iteration:
1. both sides `Nat.succ x` / non-zero literal -> compare predecessors (final); both zero -> true.
2. if **neither side has a free variable** (per-value memoised `has_fvar`, exact: counts only environment
   entries the term reads): `reduce_nat(a)` -> `defeq(result, b)` final; same for `b`.
3. `delta_step`: neither unfolds -> stuck. Only one unfolds: if the *other* is a projection application
   that `whnf_core` reduces, do that instead; else unfold + cheap `whnf_core`. Both unfold: abbrev first,
   then the greater height, opaque (theorems) last. Equal hints and same constant:
   - regular definition: unless this pair's arguments were already found different (`EQ_ARGS_NO` bit),
     levels equivalent and `args_refuted` false -> `spines_defeq_skip(unfold = true)` -> equal; else record
     the failure;
   - non-regular (abbreviation/instance/projection function): `probe_args` under 1024 ticks, only when
     `probe_worth` (shape heuristics: projection functions and instances yes; eliminator-like bodies only
     when every differing pair is neutral/neutral or closure/closure);
   then unfold both, cheap `whnf_core`.
4. `quick`, then `quick_parts`; loop.

Differences from the reference (all change order or add shortcuts; none adds a reduction rule):

| # | where | what | status of a negative result |
|---|---|---|---|
| D1 | `defeq_core` step 2 | recursor-into-Prop proofs compared by type before `whnf_core` | final (same as proof irrelevance) |
| D2 | `lazy_delta` (`tc.rs:7637`) | **race**: round 0 = R alone under `RACE_B0 = 4096` ticks. If it runs out: rounds with doubling budgets of K, M, N, then R resumed where it stopped. Once K and M are out R continues unbudgeted | only R may answer "different" |
| D2-K | `race_k` | common head: each differing argument pair under an equal share (last first), then all in order | "different" only marks arguments different |
| D2-M | `race_m` | *meeting*: the two sides take one step in turn (projection step, closed Nat op on literals, or delta + cheap whnf_core); each new form is tested against the other side's origin and last 8 forms by identity, pair table, structure, then common-head arguments under a doubling allowance | out when a side is stuck |
| D2-wait | `m_step` | *NatFoldWaits*: a closed Nat operation whose operands are not literals yet does not fold while the other side can still step; two waiting sides with the same operation compare operands | fold runs once the other side cannot move |
| D2-N | `race_n` | closed computation vs data by the ground machine (heavy declarations only) | undecided -> kernel |
| D3 | `args_refuted` (`tc.rs:8390`) | before the same-regular-head argument comparison each pair gets 256 ticks, last argument first; a pair found different skips the shortcut | only skips the shortcut |
| D4 | `probe_args` | arguments-first for the same *non-regular* head (the reference unfolds both at once) | failure -> unfold both |
| D5 | `spines_defeq_skip` | proof positions and (with `unfold`) positions the body never reads are not compared | see section 6 |
| D6 | `unfold_raw` (`tc.rs:5087`) | a Nat operation without free variables is computed, not unfolded, even when the other side has free variables (the reference would unfold `Nat.add`'s definition there) | - |
| D7 | nesting | a comparison inside a round races too, but only one round past round 0 (doubling at every level multiplied) | - |

Budget mechanics (`attempt`, `tc.rs:2150-2194`): one global tick counter; an attempt sets a stop tick;
stops form a stack combined by minimum; `Stop::Budget` unwinds to the attempt that owns the stop; a pair an
attempt left undecided is tagged with `ceil(log2 remaining) + 1` so a later attempt with no more budget
gives up immediately; nothing budgeted is ever cached as `false`.

Measured: `RACE_B0` sweep (con-leche / Init, 1 worker, G): off 45.72 / 22.49, 256: 50.59 / 24.93, 1024:
46.47 / 22.57, **4096: 45.40 / 22.47**, 16384: 45.47 / 22.48. So on ordinary libraries the race is worth
under 1 %; its purpose is three CFSG "walls" that the official kernel also fails (past 100 GB / deep
recursion), which drop to 0.05-2.5 G.

### 2.7 Type inference (`tc.rs:5540-5978, 6357-6466`)

`infer(term, env, ls, check) -> V`. Types are values; nothing is quoted back.
- BVAR: the type of the environment value (memo in the value record; free variables carry their type in
  `fv_ty`).
- APP: arguments on the argstack; infer the head; then **walk the function type as a syntactic Pi
  `(term, env, ls)` while it is one** (no closure value per argument); in check mode infer the argument
  and `defeq` it with the domain thunk; push the argument with `push_arg` (so an argument the codomain
  never mentions is never evaluated); only when the codomain is not syntactically a Pi is it evaluated
  and `whnf`ed.
- LAM (check mode): for each lambda infer the domain, require a sort, `open_var(domain)`, extend the
  environment, infer the body; **the result is `CloI { term, env, ls }`**, a lazy Pi whose codomain at `x`
  is `infer(body, env + x)` (`bind_body`), so no Pi term is built. In infer-only mode the lambda is not
  entered at all. A direct-mapped `lam_dm` remembers the body type of a lambda in head position when
  that type has no free variable at all (so it cannot mention the bound one), and `(fun x => b) a`
  re-uses it instead of inferring `b` again at `a` (fixes a quadratic "beta ladder").
- PI: open the telescope, infer body sort, fold `imax`.
- LET: check mode checks type and value; the value is pushed as a thunk (never as a free variable).
- CONST: visibility, safety, level count; in check mode every level parameter must be declared; type =
  `eval(c.ty, EMPTY, mk_ls(row, levels))`.
- PROJ: `proj_type` follows the reference, including the Prop-structure restriction.
- **infer-only vs check**: one cache entry per key holds `(type, level-param-list id or NONE)`; a checked
  result answers an infer-only query, never the reverse (`icache_hit`, `tc.rs:572-588`).
- **Cache keys** (`infer_full`, `tc.rs:5599-5729`): a closed term is keyed `(term, EMPTY, ls)`; an open term
  under at most 63 entries is keyed `(term, used_env_id, ls)` where `used_env_id` interns *the mask and
  the values at its set bits* - so the result is shared by every environment that agrees on what the
  term reads. Wider environments use the plain key.
- **Cross-declaration facts** (`Shared.checked`, one `AtomicU64` per MAIN expression, `tc.rs:38-64`):
  (a) "closed MAIN term `t` passed check-mode inference at `ls = 0` in safe declaration `d` under level
  parameter list `g`": reused by a later declaration (`d < cur_decl`) whose parameter list is a superset;
  the type is then re-derived in infer-only mode. (b) "open facts": the lambda body and the Pi codomain
  of every checked safe definition; reused when the current declaration's type telescope has the same
  domain *term ids* (equation lemmas restate a definition's body and type).
- `infer_chain`: a closed right-nested chain of at least 256 applications of one constant (list/string
  literals) is inferred innermost first, iteratively.

### 2.8 Declaration admission (`check_decl_in`, `tc.rs:9066-9127`)

- All: duplicate universe parameter names reject.
- Axiom: type is a closed well-formed type. No whitelist (axioms are assumptions).
- Definition / opaque: `check_type`, then `infer(value, check)` and `defeq` with `eval(type)`. Unsafe
  definitions may see themselves; unsafe/partial ones run in the scratch arena and add no session facts.
- Theorem: decode the block (or take the MAIN value), `check_type`, **type must be a Prop**, check value.
- Quotient (`quot.rs`): the four names are reserved (anything else under those names rejects at load,
  `env.rs:1264-1283`); each record's type is compared structurally against the expected shape after
  `Eq`'s shape is checked.
- Inductive block (`induct.rs`): parameters, universes, nested occurrences (auxiliary types are never
  built: an auxiliary type *is* the triple `(J, levels, Ds)`), constructor fields' universes, strict
  positivity, return types; then the facts the file states (`isRec`, `isReflexive`, `numNested`, K flag,
  large elimination, recursor universe parameters) are recomputed and compared; each recursor type is
  walked against the expected telescope and each rule must be the canonical one. Nothing in the record
  is trusted. Runs in the scratch arena.
- `reduceBool` / `ofReduceBool` / `trustCompiler`: no special handling anywhere in either kernel (grep);
  they are ordinary constants.
- Nat operations and literal constructors are recognised **by canonical name only** (`env.rs:1253-1263`).
  I found no check that `Nat.add` etc. have the expected definitions **[unverified whether the reference
  checks this either]**.

### 2.9 Sessions, stamping, scratch, names

- **Session** (`reset`, `tc.rs:1851-1917`): a worker keeps *all* arena state (block arena, values, spines,
  environments, free variables and every cache keyed by their ids) across consecutive declarations. It
  ends (everything truncated/cleared at once, direct tables invalidated by a generation bump) when:
  session tables exceed `SESSION_MIB = 8` MiB of entries; the next declaration is not strictly later in
  file order than the last one checked (`d <= sess_last`); the level tables pass their bound; the previous
  declaration failed/panicked; or a Bool/Nat constructor needed by a literal was not declared yet
  (`known_miss`, because a `false` found then could become `true` later).
- **Why it is sound**: every constant mentioned by a value made in declaration D is declared before D;
  the environment only grows and definitions never change; so reduction, typing and (non-)equality of
  those values are the same in any later declaration. Check-mode results carry the level-parameter list.
- **Facts that outlive a session** are only: `Shared.checked` (2.7), `eqp` (true equalities between closed
  MAIN thunks/closures), closed-name pair outcomes, pure level memos, per-constant file properties. Each
  is stamped with the declaring index and used only when `stamp < cur_decl`: nothing travels backwards
  whatever order the workers take declarations in.
- **Free variables** (`open_var` / `pick_var`, `tc.rs:2944-3005`): a binder takes *the first variable of
  its type (by type value id) that is not in scope*; `fv_busy` marks scope; closing releases it. A binder
  opened again therefore gets the same variable id and every cache keyed under it hits. Keying by the
  type's structural hash instead was tried and rejected (Init +4.4 %, std +8 %).
- **Scratch arena**: inductive blocks, quotient records and unsafe/partial declarations swap in an empty
  set of tables and swap the session back afterwards.
- **Names** (`names.rs`): past 1 M values or 10 k unfoldings in one declaration, values are additionally
  mapped to an id of "the substituted, unreduced term they stand for" (hash-consed node table, closed MAIN
  terms are leaves); whnf/infer/defeq are then also cached under names. This is the substitution kernel's
  identity, paid for only in heavy declarations, with a cost model that switches it off when hits do not
  pay for the name walks.

### 2.10 Parallelism and scheduling (`main.rs:482-768`)

- Unit: one declaration. Order: declarations grouped by the first two components of their namespace
  (private-name prefixes stripped; a declaration goes with the names under it), groups in order of first
  appearance, file order inside a group (`ns_order_fast`, stable counting sort).
- Each of W workers starts with a contiguous `1/W` slice of that order and takes chunks of 32 from its
  front; an empty worker takes **the back half of the largest remaining part**. A declaration that ran
  longer than 20 ms spills the rest of its chunk to a shared stack for other workers.
- Shared between workers: the read-only environment, `Shared.checked` and per-constant property arrays
  (relaxed atomics; any worker computes the same value), an interned list of level-parameter lists behind
  a mutex, `first_fail: AtomicU32`. Everything else is per worker.
- Determinism: a worker cancels declarations after `first_fail`; workers poll it every 4096 ticks; the
  verdict is the lowest-index non-OK status. Panics are caught and become a decline of that declaration.
- Scaling cost in instructions (mathlib): 1 worker 369 G, 4: 372 G (+0.8 %), 8: 375 G (+1.6 %).
- Each worker thread has a 1 GiB stack; stack use is measured every 256 ticks; past the limit -> decline.

### 2.11 Memory management and the heavy-declaration machinery

Normal operation: per-worker `Slab`s (reserved address range, pages committed on first write, never
moved), truncated at session end; pages above `KEEP_BYTES = 4 MiB` per table are returned
(`mmap(MAP_FIXED)`); per-worker caps divided when more than 4 workers. Mathlib peak 2.0 GB (1 worker),
2.4 GB (4); by my estimate mostly MAIN columns (38 M x 24 B = 0.9 GB) plus the per-MAIN-term `u64`
facts (0.3 GB, pages touched lazily) and name/constant tables.

Heavy declarations (all thresholds are per session):

| mechanism | trigger | what it does | measured |
|---|---|---|---|
| Regions (`region.rs`) | arena >= 2^22 values or 64 MiB big literals | every `whnf` miss notes table lengths and logs writes that could retain new ids (`Undo`); on return, if >= 256 values were made and the result (plus logged memos, pinned roots, a few closed thunks) reaches <= 1/4 of them, copy the survivors out, undo the log, truncate | one CFSG certificate: 286 GB -> bounded |
| Ghost/pin | a rolled-back region root that cost >= 256 ticks is remembered by content key; if the same work is opened again it is pinned | prevents redoing dropped work | p6: 517 G -> 15.4 G |
| Checkpoints / late regions | a whnf or lazy-delta loop that made 2^21 values since its region opened closes and reopens it with only its current term | long tail loops give memory back | Fi24P7-a0: 11.4 GB of literals held before; 0.18 GB / 216 G after |
| Deep hand-up (`trampoline`, `tc.rs:5230`) | whnf nested 8192 deep (doubling) or past half the stack; closed inference/comparison past half the stack | `Stop::Deep` unwinds to the outermost whnf / the declaration, which computes the deep job first from a shallow stack and retries (memo hit) | 1 M-step lazy accumulator: decline -> 81 G |
| Ground machine (`ground.rs`) | arena >= **2^26 values** | closed whnf and "computation vs data" comparisons run in a separate call-by-need machine: `Rc` objects, thunks updated in place, environment dropped after forcing, environments trimmed to what a delayed term reads, **no hash-consing**; K, quotients, stuck cases go back to the kernel one step at a time; answers `true` alone only for equal constructors/literals | removes O(n^2) retention of lazy `Array.push` chains; but 10x slower where work repeats (677 G vs 68 G), hence the high threshold |
| Loop states (HEAD) | inside regions | whnf-loop states made only of older values get the loop's result as memo and survive rollbacks | magma-list-deep-n36: 56.7 -> 22.8 G |
| Derived rules (HEAD, `rules.rs`) | a definition reaching a recursor on one of its lambdas, unfolded 32 times in a session | apply to opaque fresh variables, whnf, split variables a non-K non-structure recursor waits on into constructors (3 deep, 8 ctors, 64 forms); keep the pattern tree with result *templates* (no arena ids); fire in whnf's loop only | n36: 23.0 -> 3.0 G; arena's 236 local tests 53.2 -> 30.6 G; Init -1.0 %, mathlib -0.1 % |

In a JVM most of the "regions" row is what a tracing collector gives for free - *provided* the interning
tables do not hold everything strongly. The ground machine is what a naive object-graph evaluator looks
like, and its 10x loss on repeated work is the price of dropping hash-consing.

### 2.12 Build (`Cargo.toml`, `scripts/build-pgo.sh`)

`codegen-units = 1`, `lto = "fat"`, `panic = "unwind"` (panics are caught to decline), `debug = 1`;
mimalloc as global allocator (glibc malloc: RH run 3:32 vs 3:13); `-C target-cpu=native`; **PGO**:
instrumented build checks `init.susz` at 1 worker, `llvm-profdata merge`, rebuild with `-Cprofile-use`.
A `release-da` profile keeps debug assertions on for the invariants behind the unchecked array reads; a
`knobs` feature turns every constant into an environment variable for sweeps (off in release so hot paths
test nothing). Adversarial vectors, a region gate that poisons dead ids, and Pestka parity runs gate every
change.

### 2.13 Measured gains quoted in the Gruszka sources

| change | effect | source |
|---|---|---|
| PGO (Init profile) | mathlib w4 419.9 G (plain, 0.1.2) vs 372 G (PGO, README, earlier milestone): about -11 %, approximate | commit `d70f1bf` vs README |
| contiguous namespace parts per worker vs round-robin chunks | mathlib w8 -5 % instr | `main.rs:483-487` |
| `cdm` 2^16 -> 2^14 slots | cycles -4..-5 %, instr +0.2..0.3 % | `tc.rs:9131` |
| `KEEP_BYTES` 16 / 4 / 1 / 0.25 MiB | 419.5 / 419.4 / 421.5 / 434.0 G | `tc.rs:147-152` |
| re-growing tables each session (room divided) | +3.5 % | `tc.rs:161-164` |
| bounds check in `Slab::push` | +0.5 % (Init), so moved to the slow tick | `env.rs:255` |
| `Stop` as 1 byte (result fits registers) | (motivation: every `R<_>` was 32 B through memory) | `tc.rs:88-90` |
| value + memo in one 32-byte record | "one index, one cache line" | `tc.rs:692-694` |
| lambda body types in a map instead of direct-mapped | +1 % (cedar, con-leche) | `tc.rs:9173` |
| outermost-inference trampoline vs declaration-level | +1.7 % / +0.5 % vs +0.25 % (Init) | arch doc 394-397 |
| variables keyed by structural hash of type | +4.4 % Init, +8 % std (rejected) | `tc.rs:2967-2970` |
| walking two different lambdas structurally at defeq entry | 0.4-0.75 % wasted (removed) | `tc.rs:6794-6803` |
| malachite instead of num-bigint | 10-23 % faster on Nat-heavy certificates, flat elsewhere (not taken, LGPL) | commit `dfae0d7` |
| reference points | Init 20.5 G, std 34.5 G (w4, PGO) | commit `d70f1bf` |

---

## 3. Pestka

### 3.1 Architecture and representation

- Aver source -> Rust (`aver compile`, then `cargo build --release`, PGO via `bin/build-pgo`, mimalloc,
  `panic = "abort"`). Pure functional: the checker state `St` is a record threaded through every call;
  maps are updated in place only because there is always a single owner (persistent tries cost +17 % on
  Init; `decision MapCaches`, `core/cache.av`).
- Modules: `export/` (Susz reader, own packer, ndjson reader), `core/` (names, levels, exprs with
  metadata word, substitution, caches), `check/` (`state`, `tc` 8979 lines, `decl`, inductive files,
  `ground`).
- **Terms**: an algebraic type `Expr = BVar | FVar | Sort | Const | App | Lam | Pi | Let | NatLit | StrLit |
  Proj`, each node carrying one 62-bit metadata word: structural hash (bits 31+), loose range (14 bits),
  **10 "variable bits"** (which free variables may occur, one of ten bits per variable by serial number),
  a has-universe-parameter bit, and a saturating size (6 bits) (`core/exprs.av:283-322,1202-1210`).
  Locally nameless: binders are opened by substituting a free variable and closed by abstraction. Terms
  are **not** hash-consed; caches are maps from the hash word to buckets compared structurally
  (one-entry buckets stored inline).
- Binder names and infos are not stored at all (`BindersUnnamed`: -2..3 %); equality is alpha-equality.
- Environment: one map; each constant carries its *place* (number of constants before it); a declaration
  sees only constants placed before it (`PlacedConstants`), instead of a persistent environment version
  per declaration (which cost 3.2 GB on mathlib).
- Proof blocks are read from the file by the worker just before the theorem is checked and dropped
  afterwards (`BlockReadForItsCheck`: peak -0.32 GB; mathlib reads 452,619 blocks plus 702 more that later
  declarations unfold).

### 3.2 Free variables named by what they are (`decision CountedVariables`, `check/state.av:592`)

A free variable is identified by `(type term, optional let-value, k)` where `k` is the number of
variables of the same type and value currently open. Rules:
- two binders open at once with the same type/value get different `k`, so they are distinct;
- two binders that get the same variable are never open at once and have the same type and value, so any
  fact about a term containing one holds for the other;
- with more than 1023 variables open, a variable is fresh and never reused;
- counts per kind are kept in a map (`KindsCounted`), not recounted.

Effect: a binder opened again - or "a type and its instance that are the third binders of one theorem and
the seventh of the next" - gets the same variable, so **open-term cache entries are reusable across
binders and across declarations**. Measured: variables named by telescope position: -18..20 % on mathlib
slices; by kind instead: a further -4..5 % (qb9 194.4 -> 184.7 G, cslib 2.35 -> 2.27 T).

### 3.3 Reduction

- `whnf` runs a **lazy abstract machine** over terms (`lcore`, `check/tc.av`): arguments wait as closures
  (term + environment) and are materialised to terms only when needed, at most once; the loop has five
  arms; "what to do at a constant" (rule, branch, unfolding target) is computed by plain functions and
  the machine takes one step (`UnfoldPicked`: loop 24 -> 11 arms, perf tests -6 %; two extra arms in
  that loop cost Init/std/con-leche about 1 % by themselves, `LiteralCases`).
- `VariableIsItsClosure`: an argument that is a bound variable reuses the closure already in the
  environment (perf tests -5 %).
- `RuleLambdasAtOnce`: a recursor rule's lambdas are consumed in one pass (perf tests -5 %).
- `CasesOnDirect` (-5 % perf, reachability 70.1 -> 65.8 G), `RecHeadedDirect` (the compiler's sparse
  casesOn), `LiteralCases` (Nat.casesOn on a literal goes to its branch without building `Nat.succ`).
- `MatchersInlined`: the body a definition unfolds to is rewritten *once* with the matcher at its head
  unfolded into it and the resulting redexes on atoms reduced (delta + beta only); kept in the unfolding
  cache: magma-list-deep-n36 124.3 -> 92.4 G, Init -1.5 %, std -1.1 %, con-leche -2.5 %.
  `BetasShared`: that rewrite must memoise per DAG node (one Std body: 2.35 M tree nodes over 850 distinct).
- `DerivedRules` / `RulesThatSave` / `RulesCarried` (`check/tc.av:3321-3377`): a definition whose body
  reaches a recursor on a parameter and is unfolded 32 times in a declaration is reduced once on fresh
  variables, split on the constructors its recursor waits on (3 levels, 64 forms, fuel 4096 unfoldings);
  the pattern tree is its rule, kept across declarations; not for K-like/structure recursors or
  kernel-computed Nat operations; kept only when the derivation unfolded more than once per form:
  reachability 26.9 -> 23.7 G, perf 49.9 -> 41.9 G, n36 12.5 -> 7.0 G, mathlib -0.3 %.
- `ValuesInTheKey`: an operand of Nat arithmetic is also cached under "the operation applied to the
  *values* of its closed arithmetic arguments": n36 65.9 -> 17.1 G, arena perf tests 124.6 -> 69.3 G.
- `ArgumentsByNeed`, `ClosedHeadsKnown`, `GroundByNeed` (opt-in `--ground`): CFSG-only.
- `ZeroAtTheLevels`, `EquivByMaxima`: "is this sort Prop at these levels" and level equivalence without
  building instantiated/normalised levels (levels without `imax` are equivalent iff per-parameter maximal
  successor counts and the dominating numeral agree): mathlib -5 % instr, -10 % peak together with
  related level work.

### 3.4 Type checking shortcuts

- `DomainsNotBuilt`: an application spine is checked from its head; each argument's type is compared with
  the domain *as the earlier arguments would instantiate it* without building it (`fitsUnder`); 9 of 10
  comparisons succeed that way; previously a quarter of all nodes built were such instantiated rests.
- `TelescopeOnce` / `DomainsCheckedWithType` / `DeclaredBodyUnbuilt`: when a value's lambda domains are
  syntactically the declared type's Pi domains (99.8 % of cases) the body's type is compared with the
  declared codomain under the same variables; no Pi is built or re-opened; the domains are not checked
  twice (etale 51.0 -> 49.3 G).
- `ParamsInTheKey`: checked types of terms with universe parameters are cached under the term tagged with
  the declaration's parameter list.
- `BodiesCopied` (negative result): inferring bodies under an environment *inside the substitution
  design* was built and measured: etale 31.8 -> 59.0 G, Init 78.5 -> 190.8 G. NbE has to be the design
  from the start (Gruszka), not a retrofit.

### 3.5 Definitional equality deviations (all bounded by `meetFirst = 4096` ticks, reference path afterwards)

`ProofsComparedBeforeReduced`; `ArithmeticWhenWanted` (a Nat operation on non-literal arguments waits
while the other side can still unfold; same operation: compare arguments first); `HeadsMetFromBelow` ->
`SidesRace` / `RaceInLazyFrom` (two definitions of different names: reference under a probe, then both
sides step in turn looking for a form the other had); `SidesMeet` / `ProjectionsRaced` /
`ProjectionAppsCompared` (two applications of one projection: heads and arguments first, then steps by
turns; mathlib 1987.6 -> 1921.8 G); `FieldNotStructure` (compare only the requested field);
`ZetaBeforeDelta`; `LiteralRecursionsComputed` (two `Nat.rec` on one literal computed bottom-up).
`CongruenceAfterSteps` ("after each reduction step, are both sides made of parts already known equal?"):
the heaviest mathlib declaration 198 s / 4.9 GB -> 12 s / 0.5 GB, **all of mathlib 31 -> 16 minutes**.
`StepRemembered`, `CoreResultsKept`: lazy-delta steps and full whnfCore results cached. Failures are
cached per declaration (one std proof: 211 s -> 3 s).

### 3.6 Bounded caches (`check/state.av:82-130`, `check/decl.av:1471`)

- Every cache carried across declarations has a limit and is **emptied** (not evicted) when it passes it.
  Limits were derived from measured *reuse distances*: for the checked-types cache, 112 M hits, 4.8 M
  uses of already-dropped keys at distances 2^17..2^22 puts, 19.5 k instructions each to recompute (4 %
  of mathlib), 848 instructions to drop and about 470 B to keep an entry.
- Unit `u = 262144` hashes for one worker (shared budget only 1/8 larger for any number of workers,
  `FrugalWorkers`: peak stays near one worker's). Fast mode: checked types `3u`, equal pairs `1.5u`,
  others `0.75u`, closed-term facts unbounded. **Low-memory mode (default, used on the arena)**: checked
  types `u`, infer/whnf/core/equal pairs/constant types/closed facts `u/4`, variables/failures/unfoldings
  `u/8`: mathlib +4.0..5.4 % instructions for 5.5-6.2 GB -> 3.9 GB.
- After namespace ordering "a GB buys 0.4-0.8 % where it bought 1-3 %".
- Within a declaration the whnf cache is two generations (young becomes old past a weight; hits in old
  are re-stored young) plus a separate two-generation store for *dear* results (whose computation stored
  >= 1024 results).

### 3.7 Parallelism

Environment built in file order (inductive blocks and the quotient checked while reading); remaining
declarations checked on 4 workers; namespace grouping (-9 % on mathlib: 1475.9 -> 1347.9 G; two namespace
components beat one and beat grouping by the conclusion's constants); a literal prefix (everything up to
the declaration placing the last of `Nat`, `String`, `String.ofList`, `List`, `Char`, `Char.ofNat`) is
checked in file order first; runs cut by proof size, 64 runs per worker. 4 workers cost +3.7 % instructions
(each worker refills its own caches).

### 3.8 Pestka's bottom line

| build | mathlib instructions | peak | wall |
|---|---|---|---|
| native, PGO, 1 worker, high-memory | 1074 G | 5.5 GB | 175 s |
| native, PGO, 4 workers, low-memory (arena config) | 1174 G | 3.9 GB | 67 s |
| wasm-gc on V8 (Node 26), 1 thread | 5780 G | 11.3 GB | 403 s |
| Pestka reading ndjson itself (own Aver packer) | 1516 G | 6.2 GB | 126 s |

Init: 42-49 G (vs Gruszka 20.5 G). PGO from `init-prelude` alone: -12..15 %; from all of Init: 1-1.5 % more.

---

## 4. "What not to compute": the list, with the soundness argument for each

| # | rule | implementation | soundness argument | measured |
|---|---|---|---|---|
| S1 | An argument whose binder body never reads it is never evaluated | `push_arg` / `push_for`: bit 0 of the body's used mask clear -> push `HOLE` (`tc.rs:2602-2628`); also in `infer` for dependent codomains | the mask is exact for index < 63 and bit 0 is "no subterm reads entry 0"; evaluation cannot observe the entry. Type checking of the argument itself is *not* skipped (check mode infers and compares it before `push_arg`). An over-approximated mask is safe; an under-approximated one would be unsound | (part of milestone 25; adopted from nano-lean) |
| S2 | Closed binder bodies get no environment | `rng(body) == 0 -> EMPTY` | closed terms do not read environments | - |
| S3 | Proof arguments of one constant are not compared | `prop_positions(row, levels)`: bitmask of argument positions whose declared domain is *surely* a proposition (domain's head is a bound variable or constant whose own type, past as many Pis as it has arguments, ends in a sort that is zero at these levels; a Pi counts if its codomain does); `spines_defeq_skip` skips them (`tc.rs:8573-8721`) | both applications are well typed with the same head at equivalent levels; by induction over positions, once all earlier arguments are definitionally equal the two domains at position j are equal; both arguments are proofs of that proposition; proof irrelevance | Pestka: etale 59.2 -> 53.9 G, qb9 109.4 -> 103.3 G, "about 9 % on a mathlib declaration" |
| S4 | Same *definition* on both sides in lazy delta: arguments the body never reads are not compared | `unused_args(row)`: bitmask over the leading lambdas of the value (`tc.rs:8603-8634`), applied only when the spine covers all leading lambdas | both sides unfold to the same body with those variables absent; equality of the remaining arguments gives equality of the unfoldings by congruence | - |
| S5 | Structure eta skips proof fields | `try_eta_struct` with `prop_positions` of the constructor (`tc.rs:8823-8852`) | types of both sides were compared first; earlier fields equal => the field's proposition is the same; proof irrelevance | etale 24.31 -> 24.14 G (683 comparisons, 1.5 % of steps) |
| S6 | "Is it a proof?" answered without inference | `surely_not_proof` before `proof_irrel` (`tc.rs:7297-7429`) | purely a negative filter: a value whose declared result sort can never be zero is not a proof, so proof irrelevance cannot apply; when unsure, infer | Pestka: qb9 30 k projections cost 2.6 G (90 k instr each); etale 53.9 -> 52.4 G |
| S7 | Proofs headed by a recursor into Prop are compared by type before being reduced | `proof_by_rec` (`tc.rs:7436-7460`) | such a term's type is the motive applied, a proposition; `whnf_core` preserves types up to defeq; the reference asks the same proof-irrelevance question after `whnf_core` | avoids forcing K-like majors (a whole Schreier-Sims run in CFSG) |
| S8 | casesOn reduces only its major premise | `cases_direct` | the result is exactly what delta, the recursor rule and beta produce; recognised from the value's shape; K excluded | Pestka: perf tests -5 % |
| S9 | Matchers evaluate only the major and the chosen alternative | `match_direct` | same; other alternatives are never forced by the reference either, only allocated | Pestka: n36 -26 %, Init -1.5 % |
| S10 | A call walks its function's lambdas without making closures | `eval` APP arm, `beta_from` | intermediate partial applications are unobservable | Pestka: perf -5 % |
| S11 | Congruence of closures: same term + level subst and pairwise equal *read* environment entries => equal | `closure_congr` / `same_term` | congruence of substitution; only `true` decides | Pestka `CongruenceAfterSteps`: mathlib 31 -> 16 min |
| S12 | Negative results cached | `eq_cache` both outcomes per session; `EQ_ARGS_NO` | inside a session a value id denotes one computation and the visible environment only grows monotonically w.r.t. what the value mentions | Pestka: 211 s -> 3 s on one proof |

**Caveat on S3 + S4 combined (Gruszka only).** `spines_defeq_skip(unfold = true)` ORs the two masks. The
induction for S3 assumes all *earlier* arguments are equal, but an earlier position skipped by S4 may
differ, and a later proof position's domain may depend on it. I believe the result is still sound (every
use of the proof variable inside a body that does not mention the earlier variable is typed against a
type not mentioning it), but neither repository states this argument, and Pestka does **not** combine the
two (its same-definition path uses unused flags only, `check/tc.av:8245-8252`; it reports that adding
proof skipping there "gave nothing"). RLVM should either not combine them, or skip a proof position only
when its domain does not depend on an S4-skipped position.

---

## 5. Ranked tricks and how to realise them on the JVM / Truffle

Ranking is by evidence of effect on mathlib-class input. "JVM" notes assume the metric counts GC and JIT
instructions.

1. **Flat integer representation of terms and values; no object per node.**
   Evidence: Gruszka 0.37 T vs Pestka-native 1.1 T vs Pestka-wasm-gc 5.8 T on identical input.
   JVM: MAIN as primitive arrays (`int[]` stride 4 for `a, b, p, kind|rng`, `long[]` used masks) or one
   off-heap `MemorySegment`; ids are `int`. Values as an `int[]` slab with stride 8 (4 words value, 4 words
   memo) per worker. The collector never traces or moves any of it if allocated once, large, and reused.
   Session reset = set `length = 0`. This removes GC from the instruction count almost entirely and removes
   megamorphic dispatch (a `switch` on a kind byte instead of virtual calls on node classes).
2. **Hash-consing of values, environments and spines with memo words in the record.**
   JVM: open-addressing tables in `int[]`/`long[]` (no boxing, no `HashMap<Long,Integer>`); two-word key
   `(tag<<32|a, b<<32|c)`; one probe that finds or inserts. Keep generation-stamped direct-mapped caches
   (2^12-2^14 entries, sized to L2) in front of exact tables; invalidate by incrementing an `int`.
3. **Lazy, single-owner proof decoding from a binary/columnar intermediate.**
   JVM options: (a) run `susz pack` in the arena build/run command exactly as Gruszka does and read Susz
   in Java (varint decode loop over a mapped segment; spec section 5-6 is small); (b) port the positional
   ndjson parser. Either way: compute "single owner" once, keep only 38 M shared nodes resident, decode a
   proof into a per-worker `int[]` scratch arena when its theorem is checked. Reference costs in Rust:
   35 G to convert, 13.6 G to decode the whole Susz file through a visitor.
4. **Skip lists as per-constant 64-bit masks** (S1, S3, S4, S5, S6, S8, S9): prop positions per
   `(constant, level list)`, unused leading-lambda mask, explicit-binder mask, casesOn/matcher shape.
   JVM: `long[]` indexed by constant row, filled lazily; benign races are fine (idempotent). The per-node
   used mask is one `long` per MAIN node (304 MB for mathlib) and is also what makes environment pruning
   and used-entry cache keys possible - it is worth its memory.
5. **Sessions + forward-only stamping** for cross-declaration reuse. JVM: identical; a session is the
   lifetime of the per-worker slabs. Stamp the few long-lived facts with the declaration index.
6. **Free-variable reuse by type** (Pestka: -18..25 % cumulative; Gruszka: `pick_var` by type value id).
   JVM: `open(typeId)`: probe `(typeId, k)` for k = 0, 1, ... until a variable not in scope; `boolean[]`
   in-scope flags. Requires hash-consed types so that "same type" is an int comparison.
7. **Namespace-grouped, contiguous work distribution** (-9 % Pestka, -5 % Gruszka at 8 workers) with
   "steal the back half of the largest part" and a 20 ms spill. JVM: plain threads, one mutex.
8. **Environment pruning + used-entry cache keys** (hash-consing precision; inference cache hits across
   environments). JVM: same arrays; `Long.numberOfTrailingZeros` loop over the mask.
9. **Canonical first-order applications** (`canon_app`): make neutral values eagerly instead of thunks.
   Cheap and increases sharing; no JVM-specific issue.
10. **Lazy universe substitution** (`ls` id carried by closures; identity substitution = 0). Avoids all
    level instantiation of terms. JVM: an `int` per closure and a small `(level, ls)` cache.
11. **Types as values; lambda types as lazy `CloI`; Pi telescopes walked syntactically** in `infer`.
    Avoids building any term during inference. JVM: same.
12. **casesOn / matcher / (later) derived rules.** For mathlib the effect is small (-0.1..-2.5 %); for the
    arena's perf tests it is 2x-7x. Truffle angle: a per-constant "unfold plan" node (plain delta,
    casesOn-direct, matcher-direct, rule tree) selected once and cached in the constant's row is the
    natural specialisation point; keep it a data-driven `switch`, not a class hierarchy.
13. **Budgeted attempts / race.** Under 1 % on libraries; needed only against pathological inputs.
    Implement the single tick counter and `Budget` unwinding early (cheap), add strategies later.
    JVM: use a preallocated exception without stack trace or an explicit status return; do not allocate.
14. **Deep-recursion handling.** 1 GiB native stacks + stack-depth polling + hand-up trampoline.
    JVM: create worker threads with an explicit large `stackSize`; count depth explicitly; implement the
    hand-up (memoise the deep job, unwind, retry) rather than relying on `StackOverflowError`. Kovacs'
    normalization-bench (`refs/normalization-bench-kovacs/README.md`) reports JVM and GraalVM HOAS
    evaluators degrading sharply on deep-stack workloads while being competitive on tree-shaped ones.
15. **PGO, LTO, native CPU, allocator** (-11..15 %). JVM analogue: ahead-of-time compilation with profiles
    if the toolchain allows it **[toolchain question, not verified here]**; otherwise keep the hot loop
    small enough that JIT cost stays far below the 400 G budget.

Why *not* host-language HOAS closures for RLVM, given the above: Gruszka's closures are defunctionalised
triples `(term id, env id, ls id)` precisely so that they can be hash-consed, compared structurally
(S11), keyed in caches, pruned to their free variables and given memo words. A Java lambda or Truffle
`CallTarget` closure offers none of these.

---

## 6. Soundness-sensitive or fragile points RLVM must not copy blindly

1. **Used masks must never under-approximate.** S1, pruning, frame hash-consing, `has_fvar` and
   used-entry cache keys all trust them. Gruszka declines terms with >= 65535 loose variables and keeps a
   second word past index 62 after a precision bug (milestone 51: masks saturated under > 63 binders).
   A `HOLE` must be unreadable (Gruszka asserts in its debug-assertion build).
2. **S3 + S4 combination** (section 4 caveat).
3. **`prop_positions` is syntactic and must stay conservative**: it reads at most 64 positions, only heads
   that are bound variables or constants, and requires the sort to be zero *at the given levels* (levels
   are substituted first). A mutant "read the motive's sort without the levels" is one of their own test
   mutants.
4. **Cached negative equalities across declarations of a session** are valid only because (a) sessions
   move strictly forward in file order, (b) every constant in a value was visible when the value was
   made, (c) a session ends when a literal needed a constructor that was not declared yet. If RLVM keeps
   any cache longer than one declaration it needs all three.
5. **Free-variable reuse**: sound only with the invariant "no two binders in scope share a variable, and
   a variable has exactly one type". Budget unwinding must restore the open-binder depth (`vdepth_to`).
   Gruszka substitutes let values (no let-variables); Pestka includes the value in the variable's kind.
6. **Budgeted work must never be cached as `false`**, and a budget stop must propagate to the attempt that
   owns it (Pestka: swallowing inner stops made a test run forever).
7. **`Shared.checked`**: a check-mode success is reusable only under a superset of the level parameters
   and only from an earlier, *safe* declaration; the type itself is re-derived in infer-only mode. The
   "open facts" variant compares domain *term ids* of telescopes - correct only because ids denote
   identical terms in the same file.
8. **Proof blocks and theorem unfolding**: theorems *are* unfolded by whnf (Acc.rec-style reductions);
   a design that frees proof terms after checking must be able to re-read them (Pestka re-reads the
   block; a "free theorem values" mode was tried and had to decline Init and etale).
9. **Out-of-order checking** relies on rule P, unique declared names (a re-declaration after a skipped
   unsafe declaration would otherwise leak facts) and visibility by declaration index at *every* constant
   lookup and delta step.
10. **Name-based built-ins**: both kernels recognise `Nat.add`, `Nat.succ`, `String.ofList`, ... by name;
    the quotient names are reserved and checked; I found no shape check for the Nat operations.
11. **Structure eta before reducing the major** applies only to non-recursive single-constructor,
    index-free inductives whose recursor eliminates into every sort (Lean issues #15351/#15371); the
    projection of a stuck structure must stay a projection.
12. **Derived rules** must not split under K-like or structure recursors (they fire without forcing the
    major), must run under a budget, and must be disabled for kernel-computed Nat operations.
13. The converter is outside the trust base only because the reader re-validates everything; if RLVM
    reads Susz it must implement the reader's checks (bounds on every back distance, frontier index,
    name/level id, block length and count, UTF-8, minimal varints, unique names, rule P).
14. `Gruszka`'s statement "where the kernel deliberately reduces less than Lean it can only reject" refers
    to an older treatment of big literals in open Nat operations; milestone 50 changed that code. I could
    not determine whether any such case remains.

---

## 7. Open points for implementers

- Per-phase instruction split of Gruszka's 372 G (eval vs defeq vs infer vs table probes) is not
  published; the `cnt` feature counts probes per value but no numbers are in the repository.
- Gruszka's `STATUS.md` and `data/` (milestone reports, sweeps) are excluded from the public repository;
  only the numbers copied into comments and `docs/architecture.md` are available.
- The arena revision of Gruszka reaches #2 without derived rules; the HEAD numbers for the arena's perf
  tests (53.2 -> 30.6 G) suggest those tests, not mathlib, are where rules matter.
