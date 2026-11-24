# 03 - Declaration admission in the official Lean 4 kernel (v4.36.0): implementation-grade specification

Scope: everything a checker must verify before a declaration enters the environment - `environment::add`,
`Quot`, inductive types (headers, constructors, positivity, elimination universe, K, recursor types and
rules, nested inductives), and how the constructor/recursor/quot records of a `lean4export` file must be
treated. The type checker core (`whnf`, `is_def_eq`, levels, `infer_proj`) is specified in
`docs/research/02-kernel-typechecker.md` and referenced as "02 §x". All paths are relative to
`refs/`; line numbers refer to `lean4` at tag `v4.36.0` (commit `611c117`).
"reject" = kernel exception = exit code 1. `==` is structural equality ignoring binder names and binder
infos (02 §2.3); `local(b)` = fresh free variable for binder `b`, see 6.1.

## 0. Sources

| Source | Role |
|---|---|
| `lean4/src/kernel/{environment,inductive,quot,declaration}.{h,cpp}` | the specification (`inductive.cpp` is 1405 lines, read completely) |
| `lean4/src/LeanChecker.lean`, `lean4-leanexport/src/LeanExport/{Parse,Basic}.lean`, `lean4-replay/src/Lean/{Replay,Declaration,Expr}.lean` (own sparse clone of v4.36.0) | how the arena's `official` checker (`leanchecker --from-export`) drives the kernel; what the bundled exporter writes |
| `lean4lean/Lean4Lean/{Environment,Primitive,Quot,Replay}.lean`, `Inductive/Add.lean`, `divergences.md` | Lean re-implementation; the only checker that validates prelude primitives |
| `type_checking_in_lean4/src/declarations/*.md`, `lean-type-theory/axioms.tex` | background only; less precise than the code |
| `lean-kernel-arena/tests/{bugs,other,corner-cases}/*`, `tutorial/Tutorial.lean`, `README.md` | every test that concerns declarations (section 10) |

Empirical cross-check: the generated records quoted in 6.13/6.14 were printed with the host toolchain
(Lean 4.33.0, `work/agent03-decl/Probe.lean`, `Probe2.lean`, not committed). Nothing from the external
repositories was built or run.

## 1. Decision summary for RLVM (most decision-relevant first)

1. **Regenerate, never trust.** The only inputs of an inductive block are `levelParams`, `numParams`, and
   the (name, type) pairs of the types and of their constructors, in the order of the `ctors` lists. Every
   other field of the `inductive` JSON item is derived by the kernel. The official checker adds the
   regenerated constants and then requires every exported constructor and recursor record to be *identical*
   to the regenerated one (section 2). This one mechanism rejects 18 of the arena's bad tests (section 10).
2. **Compare structurally, never up to definitional equality.** Record equality uses `==` on expressions
   (alpha-equivalence, binder info ignored, universe levels compared syntactically). Comparing with
   `is_def_eq` is unsound through proof irrelevance (`bugs/rec-rule-proof-irrel`, `rec-type-proof-irrel`).
   Consequence: RLVM must reproduce the kernel's generated terms *syntactically*; the traps are in 6.13.
3. **An exported constructor/recursor with no regenerated counterpart is a rejection**, whatever its `induct`
   / `all` fields say (`bugs/orphan-rec`, `other/extra-rec`, `other/orphan-ctor`, tutorial `misnamed_rec`).
4. **Skip `unsafe` and `partial` records entirely** (not checked, not added). The official replay does this
   (`Replay.lean:182`) and the bundled exporter does emit `partial` definitions (`LeanExport/Basic.lean:255`
   filters only `isUnsafe`). These are the compiler's `X._unsafe_rec` copies: self-referential, so checking
   one as an ordinary definition would wrongly reject. Measured on the host (Lean 4.33, `import Lean`): 3001
   partial constants, 0 references to them from safe constants. A safe declaration that mentions a skipped
   constant fails with "unknown constant" (tutorial `falseFromUnsafe`, `falseFromPartial`).
5. **`Quot` is synthesised, not read.** On a `quot` record: require `Eq` of the canonical shape, add the four
   canonical constants, and require exported records named `Quot`, `Quot.mk`, `Quot.lift`, `Quot.ind` to
   equal them (`bugs/quot-bad-type`). Section 5 gives the four types in de Bruijn form.
6. **Theorems must have a type in `Prop`** (`environment.cpp:200`); axioms, definitions and opaques only need
   a type whose type is a sort. All five kinds share `check_constant_val` (section 4).
7. **One flat namespace.** A name may be declared once across all kinds, including generated names
   (`I.rec`, `I.rec_N`, the four `Quot` names). The tutorial's 8 name-collision tests check exactly this.
8. **The informational fields of an exported inductive type (`numIndices`, `isRec`, `isReflexive`,
   `numNested`) are ignored by the official checker and recomputed.** Recompute them exactly as the kernel
   does (`isRec` is syntactic and block-wide) because struct-eta reads them; do not reject on a mismatch.
9. **Admission is cold code.** Run it on plain de Bruijn terms with a substitution-based reducer; use the
   hot NbE engine only for yes/no judgements (`is_def_eq`, "is a sort"). The syntactic output of `whnf`
   matters in three places only (6.13 T4); there it is expected to be the identity on elaborator output
   (not verified on Mathlib, section 12).
10. **Intern expressions modulo binder names and binder info.** Then `==` is reference equality, record
    comparison is O(1) per expression, and every syntactic test in `inductive.cpp` (`m_params[i] != args[i]`,
    the nested-occurrence table lookup) is a pointer compare.
11. **Classify each constructor field once** (recursive or not, its telescope) and build both the minor
    premise and the rule from that single result, as lean4lean does. The C++ kernel recomputes it per pass and
    needed a cache redesign plus a defensive check to make the passes agree (`bugs/rec-missing-ih`).
12. **Prelude primitives are not validated by the official kernel** (section 8). Minimal sound policy: shape
    guards on `Nat`, `Bool` and the signatures of the accelerated functions, and *decline* (exit 2) rather
    than accept or reject when a guard fails.
13. **Order.** The official checker loads the whole file and replays by dependency; the exporter writes
    dependencies first. Process in file order, but park a declaration whose dependencies are missing instead
    of rejecting it immediately (section 9.4).

## 2. How the official checker consumes an export

`leanchecker --from-export FILE` (`lean4/src/LeanChecker.lean:60-90`):

1. **Parse** the whole file into `HashMap Name ConstantInfo` (`LeanExport/Parse.lean`). Failures (all are
   uncaught exceptions, exit code 1): a declaration name seen twice ("Duplicate declaration", `:100-102`,
   across *all* record kinds, including the members of an `inductive` item); a name/level/expr index used
   before definition or defined twice (`:56-97`); unknown record shape. `mdata` payload is dropped
   (`.mdata {} e`, `:254`). The grouping into `inductive` items is forgotten: types, ctors and recs become
   independent map entries (`:458-464`).
2. **Replay** (`lean4-replay/src/Lean/Replay.lean:176-195`): `remaining` = all constants that are neither
   `isUnsafe` nor `isPartial`. For each, depth-first over `getUsedConstantsAsSet` (constants of type and
   value; ctor names for an inductive; `all` for a recursor), then by kind (`:74-136`):
   - `defnInfo`/`thmInfo`/`axiomInfo`/`opaqueInfo`: `addDeclCore` with the record as is (hints verbatim).
   - `inductInfo info`: `lparams := info.levelParams`, `nparams := info.numParams`; types := the records named
     in `info.all`, each with the ctor records named in its `ctors` (name and type only); all of them are
     removed from `remaining`; ctor dependencies are replayed first; then
     `addDecl (inductDecl lparams nparams types (isUnsafe := false))`. Nothing else of the record is read.
   - `ctorInfo`, `recInfo`: only remembered as *postponed*.
   - `quotInfo _`: `replayConstant Eq`, then `addDecl quotDecl` (the record's content is not used).
   A name that is not in `remaining` is silently skipped as a dependency, so a missing, unsafe or partial
   dependency surfaces as the kernel's "unknown constant".
3. **Postponed check** (`:148-164`): for every postponed ctor/rec name, the kernel environment must contain
   a constant of the same kind with `info == info'`, else "No such constructor/recursor" or "Invalid
   constructor/recursor". The derived `BEq` compares every field (table in section 3); `Expr` fields with
   `Expr.eqv` = `expr_eq_fn<false>` (`lean4/src/kernel/expr_eq_fn.cpp:23-125`), names and level-parameter
   lists exactly.
4. **Quot post-check** (`LeanChecker.lean:70-90`): `Quot.mk`, `Quot.lift`, `Quot.ind` are removed from the
   map before replay; afterwards each of `Quot`, `Quot.mk`, `Quot.lift`, `Quot.ind` that the export contains
   must be present in the kernel environment and equal (`!=` on `ConstantInfo`) to the exported record.

`==` for records (02 §2.3 for expressions): binder names and binder infos are ignored; everything else is
syntactic: de Bruijn indices, literal values, constant names, universe levels *without normalisation*,
projection (name, index), `mdata` nodes with payload, the `nondep` flag of `let`. The exporter strips `mdata`
and writes `nondep = false` everywhere unless `--export-mdata` is given (`LeanExport/Basic.lean:147-170`;
the arena does not pass it, `lean-kernel-arena/lka.py:776`), so an RLVM that drops `mdata` and ignores
`nondep` at parse time differs from the official checker only on hand-written files, in the accepting
direction, and only on semantically irrelevant data.

## 3. Records: fields and where they come from

`lean4/src/kernel/declaration.h:56-394` mirrors `Lean/Declaration.lean`. "in" = input of admission,
"gen" = computed by the kernel, "cmp" = exported value must equal the generated one, "ign" = exported value
ignored by the official checker.

| Record | Field | Status | Computed as |
|---|---|---|---|
| all | `name`, `levelParams`, `type` | in (checked) | - |
| axiom | `isUnsafe` | in | unsafe -> skipped |
| def | `value` | in (checked) | - |
| def | `hints` | in, untrusted, never verified | strategy only (02 §8.4) |
| def | `safety` | in | `unsafe`/`partial` -> skipped |
| def/thm/opaque | `all` | ign | - |
| inductive type | `numParams`, `all`, `ctors` | in | - |
| inductive type | `numIndices` | ign / gen | Pi binders of the type beyond `numParams`, with `whnf` between binders (6.4) |
| inductive type | `numNested` | ign / gen | number of auxiliary types (6.3) |
| inductive type | `isRec`, `isReflexive` | ign / gen | block-wide syntactic tests (6.5) |
| constructor | `type` | in | (for nested blocks: restored type, equal to the input) |
| constructor | `induct`, `cidx`, `numParams`, `numFields`, `isUnsafe` | cmp | owner type; position in its `ctors`; block `numParams`; syntactic Pi count minus `numParams`; false |
| recursor | `name` | cmp | `I.rec`; auxiliary: `<first type of block>.rec_<j>`, j = 1.. |
| recursor | `levelParams` | cmp | `[u*] ++ lparams` or `lparams` (6.8) |
| recursor | `type`, `rules[i].rhs` | cmp with `==` | 6.11, 6.12 |
| recursor | `all` | cmp | the *user-declared* types of the block (never auxiliary names) |
| recursor | `numParams`, `numIndices` | cmp | block `numParams`; indices of the type this recursor eliminates |
| recursor | `numMotives`, `numMinors` | cmp | types incl. auxiliary ones; constructors of all of them |
| recursor | `rules[i].ctor`, `.nfields` | cmp | constructors of the eliminated type in order (auxiliary: the container's ctor names); field count |
| recursor | `k`, `isUnsafe` | cmp | 6.9; false |
| quot | `kind`, `levelParams`, `type` | cmp for the four reserved names | section 5 |

## 4. `environment::add` and the simple kinds

Dispatch: `environment.cpp:270-281`. Entry point `lean_add_decl` (`:286-294`) always checks.

### 4.1 Checks common to every declaration with a `ConstantVal` (`check_constant_val`, `:126-132`)
```
checkConstantVal(env, v, tc):
  require !env.contains(v.name)                        # :101-104 already_declared
  require v.levelParams has no duplicates              # :110-120
  require !hasMVar(v.type) && !hasFVar(v.type)         # :86-99; unrepresentable in an export
  s := tc.check(v.type, v.levelParams)                 # full type check with universe-parameter checking
  tc.ensureSort(s)                                     # s, or whnf(s), must be a Sort (02 §6); else "type expected"
```
`tc.check(e, lps)` (`type_checker.cpp:364-367`, 02 §6) is where the remaining well-formedness rules live:
- loose bound variables anywhere in `e` -> reject (`:326-327`; `bugs/loose-bvar-value`);
- `Sort l` / `Const c ls`: every `Level.param` must be in `lps` (`:85-91`, `:118-120`, `:346`);
- `Const c ls`: `c` must be in the environment (`env.get` throws), `|ls| == |c.levelParams|` (`:105-108`,
  `bugs/constlevels`), and the safety rule of 4.4;
- nat literals larger than 128 MB -> reject (`:315-321`).
The check visits every subterm, so "all universe parameters declared" and "all constants known" are
properties of the whole expression. RLVM trick: cache per interned node the loose-bvar range and the set of
universe-parameter names (small bitset or interned set id); both rules become O(1) tests at the root.

### 4.2 Per kind
```
addAxiom(v):        checkConstantVal(v, mode = v.isUnsafe ? unsafe : safe)                          # :151-157
addDefinition(v):                                                                                   # :159-189
  if v.safety == unsafe:  checkConstantVal(v, unsafe); env' := env + v;   # recursive: body sees itself
                          check value in env' (mode unsafe); require isDefEq(T', v.type)
  else (safe AND non-mutual partial):  mode := safe (the default of type_checker)
       checkConstantVal(v); require closed(v.value)
       T' := tc.check(v.value, v.levelParams); require tc.isDefEq(T', v.type)   # inferred type first
       env + v                                                    # added only after the check: no recursion
addTheorem(v):                                                                                      # :191-208
  (value and type are hash-consed first: sharecommon_persistent_fn, :196-198)
  checkConstantVal(v); require tc.isProp(v.type)                   # theorem_type_is_not_prop
  require closed(v.value); T' := tc.check(v.value, v.levelParams); require tc.isDefEq(T', v.type)
addOpaque(v):   exactly addDefinition's safe branch (mode safe even when v.isUnsafe)                # :210-222
addMutual(vs):                                                                                      # :224-268
  require vs nonempty; s := vs[0].safety; require s != safe
  for v in vs: require v.safety == s, v.levelParams == vs[0].levelParams, name not repeated in the block
               checkConstantVal(v, mode s)                 # headers against the OLD environment
  env' := env + all vs; for v in vs: check value in env' (mode s); require isDefEq(T', v.type)
```
`isProp(T)` = `normalizes_to_zero(level of ensureSort(infer(T)))` (`type_checker.cpp:379-385`, 02 §6.10): it
throws when the type's type does not reduce to a sort, and treats `imax 1 0` as zero. Argument order of the
final test is `isDefEq(inferredType, declaredType)`.

After admission: definitions *and theorems* unfold by delta (`constant_info::has_value`,
`declaration.h:447`; a theorem's hints count as `opaque`, `declaration.cpp:219-224`); `opaque` constants,
axioms, quot constants, inductive types, constructors and recursors never unfold. `hints` are taken from
the export verbatim; the kernel never recomputes definitional height (`environment.cpp:159-189`).

### 4.3 Tutorial tests on this section
`badDef` (isDefEq fails), `forallSortBad` (binder domain is not a sort, inside `tc.check` of the value),
`nonTypeType`, `nonTypeAxiom` (`ensureSort` of the type's type), `nonPropThm` (`isProp`), `selfProof`
(a theorem is not in the environment while it is checked -> unknown constant), `tut06_bad01` (duplicate
universe parameter), `inductive-undeclared-universe` (the same universe rule applied to an inductive header).

### 4.4 Safety, and what unsafe/partial records mean for an external checker
Kernel rule (`type_checker.cpp:109-117`), applied only in check mode: with checker mode M, a reference to a
constant `c` is an error if `c.isUnsafe && M != unsafe`, or if `c` is a `partial` definition and
`M == safe`. `isUnsafe` (`declaration.cpp:226-238`): axiom/opaque/inductive/ctor/rec flag, definition with
`safety == unsafe`; theorems and quot constants never.

For an export checker the rule degenerates, because the official replay never adds unsafe or partial
constants (`Replay.lean:179-183`): they are absent, and a reference to one is "unknown constant". RLVM:
- drop every record with `isUnsafe == true` or `safety in {unsafe, partial}` at load time; remember the name
  only to give a precise error message;
- an `inductive` item whose type records are unsafe is dropped as a whole; an unsafe-flagged ctor/rec record
  inside a safe block is neither compared nor added (the generated, safe one is in the environment);
- `mutualDefnDecl` therefore never has to be implemented: it exists only for unsafe/partial blocks. Safe
  definitions with `all.length > 1` are independent, non-recursive kernel definitions.
The default `lean4export`/`leanexport` omits unsafe constants (`LeanExport/Basic.lean:255`); the tutorial
tests are exported with `--export-unsafe` (`tutorial/Tutorial/Export.lean`), so unsafe and partial `def`
records do occur in arena inputs and must be skipped, not rejected.

## 5. Quot

`environment::add_quot` (`quot.cpp:47-104`); takes no data from the declaration.
```
addQuot(env):
  if env.quotInit: return env                              # idempotent, no checks at all the second time
  checkEqType(env)                                         # quot.cpp:19-45
  require Quot, Quot.mk, Quot.lift, Quot.ind are all absent from env     # :53-56
  add the four constants below as QuotVal{kind}; env.quotInit := true
checkEqType:
  E := env["Eq"] (unknown constant -> reject); require E is an inductive type
  require |E.levelParams| == 1 and |E.ctors| == 1
  require E.type          == Pi(Sort u, Pi(#0, Pi(#1, Sort 0)))            # u = E.levelParams[0]
  require env[E.ctors[0]].type == Pi(Sort u', Pi(#0, Eq.{u'} #1 #0 #0))    # u' = first level param of the ctor
```
Canonical constants (de Bruijn, binder names/infos irrelevant for `==`); `A -> B` is a Pi whose body does not
use the bound variable (shift accordingly):

| Name | kind | levelParams | type |
|---|---|---|---|
| `Quot` | `type` | `[u]` | `Pi(Sort u, Pi(Pi(#0, Pi(#1, Sort 0)), Sort u))` |
| `Quot.mk` | `ctor` | `[u]` | `Pi(Sort u, Pi(Pi(#0, Pi(#1, Sort 0)), Pi(#1, Quot.{u} #2 #1)))` |
| `Quot.lift` | `lift` | `[u, v]` | `Pi(Sort u, Pi(Pi(#0, Pi(#1, Sort 0)), Pi(Sort v, Pi(Pi(#2, #1), Pi(S, Pi(Quot.{u} #4 #3, #3))))))` with `S = Pi(#3, Pi(#4, Pi(#4 #1 #0, Eq.{v} #4 (#3 #2) (#3 #1))))` |
| `Quot.ind` | `ind` | `[u]` | `Pi(Sort u, Pi(Pi(#0, Pi(#1, Sort 0)), Pi(Pi(Quot.{u} #1 #0, Sort 0), Pi(Pi(#2, #1 (Quot.mk.{u} #3 #2 #0)), Pi(Quot.{u} #3 #2, #2 #0)))))` |

Readable: `Quot.{u} {α : Sort u} (r : α → α → Prop) : Sort u`; `Quot.mk.{u} {α} (r) (a : α) : Quot r`;
`Quot.lift.{u,v} {α} {r} {β : Sort v} (f : α → β) : (∀ a b, r a b → f a = f b) → Quot r → β`;
`Quot.ind.{u} {α} {r} {β : Quot r → Prop} : (∀ a, β (Quot.mk r a)) → ∀ q, β q`. The universe parameter
names are literally `u` and `v` and are compared.

Facts: (a) `Eq` must exist *before* the quot declaration and have that exact type and constructor type;
its `numParams` is not inspected. (b) Quot reduction (02 §7.5) is enabled only once `quotInit` is set and
matches the three names; before that a user constant called `Quot.lift` is an ordinary constant, and
afterwards the names are taken. (c) RLVM handling of a `quot` record: ensure `Eq` is admitted, run `addQuot`
once, then require the record's name to be one of the four and (kind, levelParams, type) to equal the table
(official: a quot record with another name is simply never added; rejecting it is stricter and harmless).

## 6. Inductive types (`kernel/inductive.cpp`)

### 6.1 Pipeline and conventions
```
addInductive(env, d = {lparams, nparams, types[{name, type, ctors[{name, type}]}], isUnsafe}):     # :1223-1338
  for T in d.types, and for every ctor c of T:                                                    # :1225-1235
      require no mvar/fvar in the type; require noNestedPrefix(type)           # 6.2
  checkUniformOccs(d)                                                          # 6.2
  (aux, nestedAux, P) := elimNested(env, d)                                    # 6.3; aux.types = d.types ++ auxiliary
  E := AddInductiveFn(env, aux, nnested = |nestedAux|).run()                   # 6.4 - 6.11
  if nnested == 0: return E else return restoreNested(env, E, d, nestedAux, P) # 6.12
AddInductiveFn.run():                                                          # :856-868
  require lparams has no duplicates
  checkInductiveTypes(); declareInductiveTypes(); checkConstructors(); declareConstructors()
  initElimLevel(); initKTarget(); mkRecInfos(); declareRecursors(); checkRecursors()
```
- `nparams` must fit a machine word (`:123`, `:196`); `d.types` must be nonempty (`:1141`).
- `local(b)`, for a Pi binder `b` (`mk_local_decl_for`, `:213-216`): a fresh fvar carrying the binder's name
  and info and the type `consumeTypeAnnotations(b.domain)` (6.13 T2). `inst(body, x)` is plain substitution.
  `mkPi(xs, e)` / `mkLambda(xs, e)` abstract the fvars back, using the stored (stripped) types.
- Every `whnf` / `isDefEq` / `infer` / `check` call in this class constructs a *new* `type_checker`
  (`tc()`, `:202`): no cache is shared between calls. The environment grows during `run()`: types are added
  before constructors are checked, constructors before recursors are built.
- Unsafe blocks skip only the positivity check (`:474`); RLVM never sees them (4.4).

### 6.2 Pre-checks on the declaration as written
```
noNestedPrefix(e):                                                            # :1210-1221
  no Const whose name has first component "_nested", and no Proj whose structure name has it
checkUniformOccs(d):                                                          # :122-152
  names := names of d.types; lvls := [Param p | p in lparams]
  for every ctor type, traverse all subterms t pre-order with offset = number of binders entered
  (Pi/lambda body and let body add 1; both children of an App are visited):
     (fn, args) := spine(t)
     if fn is not a Const named in names: continue into children
     if |args| > nparams: continue into children          # the nparams-prefix is itself a visited subterm
     require |args| == nparams && offset >= nparams && fn.levels == lvls (syntactic)
             && for i < nparams: args[i] is BVar(offset - 1 - i)
     do not descend
```
Meaning: every occurrence of a type of the block, anywhere in a constructor type (also inside the
arguments of a nested container, inside index positions and inside beta-redexes), must be applied to the
block's universe parameters and to the constructor's own parameter variables, in order. A bare or partially
applied occurrence is rejected when `nparams > 0`. Tutorial `inductWrongCtorParams`,
`inductWrongCtorResParams`, `inductWrongCtorResLevel` already fail here. Note that `_nested` is reserved
only inside inductive declarations; a definition may be called `_nested.X` (`bugs/nested-name-capture`).

### 6.3 Nested occurrences -> auxiliary mutual block (`elim_nested_inductive_fn`, `:973-1168`)
```
elimNested(env, d):
  newTypes := copy of d.types; nestedAux := []          # ordered list of (I Ds over canonical params P, auxName)
  P := for i < nparams: the type of d.types[0] must be a SYNTACTIC Pi; fresh fvar (name, raw domain, info)  # :1126-1134
  q := 0; counter := 1
  while q < |newTypes|:                                 # the queue grows while it is processed
     for each ctor c of newTypes[q], in order:
        (As, body) := peel nparams SYNTACTIC Pis of c.type into fresh fvars    # else "incorrect number of parameters"
        body := replace(body, e -> replaceIfNested(As, e))   # pre-order; a replaced node is not descended into
        c.type := mkPi(As, body)
     q++
  return ({lparams, nparams, newTypes, isUnsafe}, nestedAux, P)

replaceIfNested(As, e):                                                       # :1011-1043, :1054-1119
  (fn, args) := spine(e); if e is not an App, or fn is not a Const I with env[I] an admitted inductive type: none
  np := env[I].numParams; if |args| < np: none
  nested := some args[i], i < np, contains a Const named like a member of newTypes
  if !nested: none
  if some args[i], i < np, has loose bound variables: reject   # "parameters cannot contain local variables"
  key := (fn args[0..np))[As := P]
  if (key', aux) in nestedAux with key' == key: return Const(aux, lvls) As args[np..]
  for J in env[I].all, in order:                        # the whole mutual block of the container is copied
     auxJ := "_nested." ++ J ++ "_" ++ counter; counter++   (repeat while that name is in env)   # :989-995
     ty := instLevelParams(env[J].type, env[J].levelParams, fn.levels)     # simplifying, 6.13 T3
     ty := strip np SYNTACTIC Pis from ty and instantiate them with args[0..np)    # else "ill-formed"
     nestedAux.push(((Const(J, fn.levels) args[0..np))[As := P], auxJ))
     ctors := for each ctor Jc of J: (Jc.name with prefix J replaced by auxJ,
                                      mkPi(As, same two steps applied to env[Jc].type))
     newTypes.push({auxJ, mkPi(As, ty), ctors})
  return Const(auxI, lvls) As args[np..]                # the entry created for J == I
```
- Detection is purely syntactic. A container hidden behind a definition (`abbrev L := List`) or a
  partial application is not recognised and later fails positivity. Two occurrences that differ by a
  beta-redex are different keys and yield two auxiliary types (`corner-cases/nested-redex-aux`).
- Only the container's *parameter* arguments are searched for block types; indices `args[np..]` are kept.
- Auxiliary constructor types still mention the container (`List (Tree α)`); they are rewritten when the
  queue reaches the auxiliary type, which is how `Array (T)` discovers `List (T)`.
- Order of auxiliary types = order of discovery = (type index, ctor index, pre-order position; in an App the
  function part before the argument). It fixes `motive_k`, the minor-premise order and the `rec_j` numbering.
- The auxiliary names never reach the final environment; RLVM may use any private identity for them.

### 6.4 Type formers (`check_inductive_types`, `:242-293`)
```
levels := [Param p | p in lparams]
for (k, T) in enumerate(types):
  require T.name and T.name ++ "rec" are absent from env           # nothing of the block is added yet
  tc.check(T.type, lparams)                                        # closed, constants known, universes declared
  ty := whnf(T.type); i := 0; nidx := 0
  while ty is Pi:
     if i < nparams:
        if k == 0: p := local(ty); params.push(p); ty := inst(ty.body, p)
        else: require isDefEq(ty.domain, typeOf(params[i]))        # "parameters ... must match"
              ty := inst(ty.body, params[i])
        i++
     else: ty := inst(ty.body, local(ty)); nidx++
     ty := whnf(ty)
  require i == nparams                                             # "number of parameters mismatch"
  Sort l := ensureSort(ty)                                         # "type expected" (tutorial inductBadNonSort*)
  if k == 0: resultLevel := l; isNotZero := is_not_zero(l)         # 02 §3.2
  else: require is_equivalent(l, resultLevel)                      # "must live in the same universe"
  nindices[k] := nidx; indConst[k] := Const(T.name, levels)
```
The parameter fvars (names, infos, annotation-stripped types) come from the *first* type only and are
reused for every type, constructor, motive and recursor of the block.

### 6.5 `isRec`, `isReflexive`, adding the types (`:296-363`)
```
isRec       := exists T, ctor c, a binder on the SYNTACTIC Pi spine of c.type (parameters included, no
               instantiation, no whnf) whose domain contains a Const named like a type of the block
isReflexive := exists T, ctor c, a binder (walking with local()) whose domain is syntactically a Pi and hasIndOcc
for (k, T): require T.name absent; add InductiveVal{T.name, lparams, T.type, numParams = nparams,
            numIndices = nindices[k], all = names of types, ctors = names, numNested = nnested,
            isRec, isUnsafe, isReflexive}
```
Both flags are computed on the auxiliary block (after 6.3), are the same for all its types, and a nested
block is therefore always `isRec` (verified: `Tree` with `List (Tree α)` has `isRec = true, numNested = 1`).
`isRec` does not follow reduction (`corner-cases/vanishing-occurrence`: flag true, positivity sees nothing).

### 6.6 Constructors (`check_constructors`, `:444-485`; positivity `:369-440`)
```
for (k, T): seen := {}
  for c in T.ctors:
     require c.name not in seen                                    # "duplicate constructor name"
     require c.name absent from env; tc.check(c.type, lparams)     # env contains the block's TYPES, not its ctors
     t := c.type; i := 0
     while t is Pi:                                                # SYNTACTIC: the ctor type itself is never whnf'ed
        if i < nparams: require isDefEq(t.domain, typeOf(params[i]))   # "does not match ... parameters"
                        t := inst(t.body, params[i])
        else: Sort lf := ensureSort(infer(t.domain))
              require is_geq(resultLevel, lf) || normalizes_to_zero(resultLevel)     # "universe ... too big"
              checkPositivity(t.domain)
              t := inst(t.body, local(t))
        i++
     require isValidIndApp(t, k)                                   # "invalid return type"

hasIndOcc(e)       := e contains a Const named like a type of the (auxiliary) block; levels are not looked at
isValidIndApp(t,k) := (fn, args) := spine(t); fn == indConst[k]  (name AND levels == [Param lparams..])
                      && |args| == nparams + nindices[k] && for i < nparams: args[i] == params[i]   (that fvar)
                      && for i >= nparams: !hasIndOcc(args[i])     # lean4#2125
isValidIndApp(t)   := the first k with isValidIndApp(t, k), else none
checkPositivity(t):
  t := whnf(t)                                                     # full whnf, delta included
  if !hasIndOcc(t): ok                                             # non-recursive field
  elif t is Pi: require !hasIndOcc(t.domain)                       # "non positive occurrence"
                checkPositivity(inst(t.body, local(t)))
  elif isValidIndApp(t) != none: ok                                # recursive field
  else reject                                                      # "non valid occurrence"
recArg(t): t := whnf(t); while t is Pi: t := whnf(inst(t.body, local(t))); return isValidIndApp(t)   # :414-421
```
Consequences worth a test each: the constructor type must be a manifest Pi telescope (tutorial
`reduceCtorType`) but each field type is weak-head normalised (tutorial `reduceCtorParam*`, accept); the
normalisation is only at the head, so an occurrence in the domain of an arrow inside a field is negative even
if it would reduce away (tutorial `indNegReducible`; `corner-cases/positivity-whnf` is "either"); the
result's parameters must be the parameter variables themselves, not something convertible
(`corner-cases/proof-param-swap`, official rejects); Prop-valued blocks accept fields of any universe.

### 6.7 Adding the constructors (`:487-507`)
`arity` = number of syntactic leading Pis of `c.type`; add `ConstructorVal{c.name, lparams, c.type,
induct = T.name, cidx = position in T.ctors, numParams = nparams, numFields = arity - nparams, isUnsafe}`
after requiring the name to be absent (this is the check that catches a name shared by two types' ctors or by
a ctor and a type).

### 6.8 Elimination universe (`:510-580`)
```
elimOnlyAtZero():
  if isNotZero: return false                 # never a Prop: large elimination
  if |types| > 1: return true                # mutual, and ALSO any nested block (auxiliary types count)
  n := |types[0].ctors|
  if n > 1: return true
  if n == 0: return false                    # empty predicate: large elimination (False)
  t := the single ctor type; i := 0; toCheck := []
  while t is Pi:                             # syntactic
     x := local(t)
     if i >= nparams: Sort lf := ensureSort(infer(t.domain)); if !normalizes_to_zero(lf): toCheck.push(x)
     t := inst(t.body, x); i++
  return exists x in toCheck with x not in appArgs(t)      # == on fvars: the argument must BE the field
elimLevel   := Zero if elimOnlyAtZero() else Param(u*), u* = first of "u", "u_1", "u_2", ... not in lparams
recLParams  := [u*] ++ lparams   (just lparams when elimLevel is Zero);  recLevels likewise
```
The test is "could be a Prop" (`!is_not_zero`), not "is a Prop": `inductive MyBool.{u} : Sort u | tt | ff`
eliminates into `Prop` only (`bugs/large-elim-param`). A non-Prop field is allowed only if it appears
*verbatim* as an argument of the result type (tutorial `sortElimPropRec` large, `sortElimProp2Rec` with
`id b1` small; `Exists` small; `Eq`, `Acc`, `And`, `True`, `False` large). Verified shapes: `PUnit.rec`
has `[u_1, u]`, `Eq.rec` `[u, u_1]`, a nested Prop (`N ∧ N → N`) has no extra universe.

### 6.9 K (`init_K_target`, `:582-604`)
`k := |types| == 1 && normalizes_to_zero(resultLevel) && exactly one ctor && that ctor has exactly nparams
syntactic Pis (no fields)`. Here the test is "is definitely a Prop". All recursors of the block get the same
flag (so only single-type blocks can have it). `Eq.rec`, `True.rec`: true; `Acc.rec`, `And.rec`,
`PUnit.rec`, `Nat.rec`: false (`bugs/nat-rec-k-lie`, `bugs/rec-k-lie`, tutorial `ruleKAcc`).

### 6.10 Motives and minor premises (`mk_rec_infos`, `:620-705`)
```
for (j, T):                                                 # types of the (auxiliary) block
  idx_j := walk T.type exactly as in 6.4 (whnf between binders), instantiating params, local() per index
  major_j := fresh fvar "t" : indConst[j] params idx_j
  C_j := fresh fvar "motive" (or "motive_<j+1>" if |types| > 1) : mkPi(idx_j, mkPi(major_j, Sort elimLevel))
for (j, T), for c in T.ctors (block order):
  walk c.type syntactically: params instantiated; fields b := [local(binder) ...];
                             u := [x in b | recArg(domain of x's binder) != none]
  t := the result type; (r, ridx) := (its type index, its index arguments = args[nparams..])
  for x in u:  ty := whnf(typeOf(x)); xs := []
               while ty is Pi: y := local(ty); xs.push(y); ty := whnf(inst(ty.body, y))
               (r', idx') := decompose ty as above
               ih_x := fresh fvar (name(x) ++ "_ih") : mkPi(xs, C_{r'} idx' (x xs))
  minor_c := fresh fvar : mkPi(b, mkPi(ihs, C_r ridx (Const(c.name, levels) params b)))
```
Layout of a minor premise: all fields first, then all induction hypotheses (one per recursive field, in
field order). A recursive field keeps its declared (annotation-stripped) type; its hypothesis is built
from the weak-head-normalised type.

### 6.11 Recursors, rules and the defensive check (`:736-854`)
```
Cs := [C_0 .. C_{n-1}]; minors := all minor premises in block order; m := 0
for (j, T):
  type_j := mkPi(params, mkPi(Cs, mkPi(minors, mkPi(idx_j, mkPi(major_j, C_j idx_j major_j)))))
            (then infer_implicit: changes binder infos only, irrelevant for ==)
  rules_j := for c in T.ctors:
     b, u as in 6.10
     v := for x in u: xs, r', idx' as in 6.10;
              mkLambda(xs, Const(types[r'].name ++ "rec", recLevels) params Cs minors idx' (x xs))
     rhs := mkLambda(params, mkLambda(Cs, mkLambda(minors, mkLambda(b, minors[m] b v)))); m++
     RecursorRule{ctor = c.name, nfields = |b|, rhs}
  require T.name ++ "rec" absent
  add RecursorVal{T.name ++ "rec", recLParams, type_j, all = names of types, numParams = nparams,
                  numIndices = nindices[j], numMotives = |types|, numMinors = |minors|, rules_j, k, isUnsafe}
checkRecursors():                                             # :817-854, defensive since lean4#14808
  for (j, T): tc.check(type_j, recLParams)
     for c in T.ctors: lhs := T.rec.{recLevels} params Cs minors ridx (c.{levels} params b)
                       require isDefEq(infer(whnf(lhs)), infer(lhs))     # the rule preserves types
```
RLVM should build `b`, `u`, `xs`, `idx'` once per constructor and use them for both 6.10 and the rules
(decision 11); `checkRecursors` is then redundant but cheap, keep it on.

### 6.12 Restoring nested blocks (`:875-964`, `:1179-1205`, `:1241-1337`)
```
restoreNested(env0, E, d, nestedAux, P):
  orig := names of d.types; auxNames := E[d.types[0].name].all[|d.types|..]       # creation order
  rename: auxNames[j] ++ "rec"  ->  d.types[0].name ++ "rec_" ++ (j+1)
  new := env0
  for T in d.types:
     add E[T.name] with all := orig                                   # name must be absent
     for c in T.ctors: add E[c.name] with type := restore(type)       # == the input type
     addRec(T.name ++ "rec")
  for a in auxNames: addRec(a ++ "rec")
  addRec(r): R := E[r]; n := renamed(r) or r; require n absent
     add RecursorVal{n, R.lparams, restore(R.type), all := orig, R.numParams, R.numIndices, R.numMotives,
                     R.numMinors, k = R.k, rules := for rule in R.rules:
                       (ctor := if n != r then rule.ctor with prefix auxJ replaced by J else rule.ctor,
                        rule.nfields, restore(rule.rhs))}
  with tc over new and the fvars P: for (I Ds, _) in nestedAux: tc.check(I Ds, lparams)        # MANDATORY
  with tc over new: check every restored ctor type, recursor type and rule rhs                 # defensive
restore(e):
  peel |P| leading binders of e (all Pi or all lambda) into fresh fvars As
  rewrite pre-order, not descending into rewritten nodes:
     Const r, r an auxiliary recursor name     ->  Const(renamed r, same levels)
     t = Const(auxJ, _) a   (auxiliary type)   ->  (J.{ls} Ds)[P := As]  a[|P|..]
     t = Const(auxJ.c, _) a (auxiliary ctor)   ->  Const(J.c, ls)  Ds[P := As]  a[|P|..]
  rebind As with the original binder kind
```
The check of `I Ds` in the final environment is the fix for `bugs/nested-unused-param`: the container's
parameter arguments are absent from the auxiliary block and would otherwise never be type-checked.
A recursor of an auxiliary type keeps the block's `numParams`, while its rules name constructors of the
container, whose own parameter count is different; iota therefore takes the fields as the *last* `nfields`
arguments of the major premise (`inductive.h:106-112`). The major premise of `T.rec_1` has the container
type (e.g. `List (T α)`), so struct-eta on that major is decided by the container's records
(`get_major_induct`, `declaration.cpp:117-126`; e.g. a block nested through `Prod` eta-expands majors of
`rec_1`). K is never set for a nested block (6.9).

### 6.13 Exactness traps (what makes a regenerated record syntactically equal to the kernel's)
- **T1 Binder names and binder infos do not matter**, including `motive_k`, `x_ih`, the minor-premise names
  and the implicit binders computed by `infer_implicit`.
- **T2 `consumeTypeAnnotations`** (`lean4-replay/src/Lean/Expr.lean:1738-1744`): every binder domain the
  kernel turns into a local loses its *outermost* annotations, repeatedly: `optParam A d -> A`,
  `autoParam A t -> A` (head constant with exactly 2 arguments), `outParam A -> A`, `semiOutParam A -> A`
  (exactly 1 argument); matched by constant name only, not through `mdata`, not under binders. Affected:
  block parameters (from the first type header), indices, constructor fields, hypothesis telescopes.
  Constructor *types* keep the annotations. Verified: for `class OP (α : outParam (Type u)) (γ : Type v)`
  the recursor has `{α : Type u}`; for `structure AP where x : Nat; h : x = x := by rfl` the constructor has
  `autoParam (x = x) _` and the recursor's minor premise and rule have `(h : x = x)`. `Init.Prelude` already
  needs this (`HAdd`, `Membership`, `Coe`); Mathlib needs it thousands of times.
- **T3 Universe instantiation simplifies** (02 §3.3): 6.3 instantiates the container's types with
  `mk_max`/`mk_imax`, and the result ends up in recursor types. Verified: container field
  `PUnit.{succ (max a b)} → α` instantiated at `a = b = u` appears in the recursor as `PUnit.{succ u}`.
- **T4 Output of `whnf` is observable in three places**: the index binders of a type header (6.4/6.10), the
  telescope `xs` of a recursive field, and the index arguments `idx'` of its conclusion. On elaborator
  output `whnf` should be the identity there (a Pi, or an application of a constant without a value;
  not verified on Mathlib). It is not the
  identity on hand-written input (tutorial `reduceCtorParamRefl2`: field `α → constType (T α) α`, hypothesis
  `∀ a, motive (x a)`); those domains must then be the *unreduced, substituted* subterms the kernel's `whnf`
  exposes, which a normalise-and-quote implementation would not give.
- **T5 Universe names**: `u`, else `u_1`, `u_2`, ... and it is the *first* parameter of the recursor.
- **T6 Motive codomain** is `Sort (Param u*)` or `Sort Zero`; constructor constants carry
  `[Param lparams..]`, recursor constants `recLevels`; no simplification is applied to these.
- **T7** Recursors of nested blocks: `all` lists only user types, `numMotives`/`numMinors` count auxiliary
  types/constructors, and rules of `rec_j` name the container's constructors.
- **T8** The exported `recs` array is not ordered (`bugs/nested-name-capture` lists `rec_1` before `rec`);
  match by name.

### 6.14 Reference outputs (use as unit tests)
```
Nat.rec.{u} : Pi(Pi(Nat, Sort u), Pi(#0 Nat.zero, Pi(Pi(Nat, Pi(#2 #0, #3 (Nat.succ #1))), Pi(Nat, #3 #0))))
  numParams 0, numIndices 0, numMotives 1, numMinors 2, k false
  rule Nat.zero, 0 fields:  λ motive zero succ. #1
  rule Nat.succ, 1 field:   λ motive zero succ (n : Nat). #1 #0 (Nat.rec.{u} #3 #2 #1 #0)
Eq.rec.{u, u_1}: {α : Sort u_1} {a : α} {motive : (b : α) → a = b → Sort u} (refl : motive a (Eq.refl a)) {b : α} (t : a = b) : motive b t
  numParams 2, numIndices 1, k true;   rule Eq.refl, 0 fields: λ α a motive refl. refl
Acc.rec.{u_1, u}: minor  (x : α) (h : ∀ y, r y x → Acc r y) (h_ih : ∀ y (a : r y x), motive y (h y a)) : motive x (Acc.intro x h)
  rule Acc.intro, 2 fields: λ α r motive intro x h. intro x h (λ y a. Acc.rec.{u_1,u} α r motive intro y (h y a))
Tree.{u} (α : Type u) | node : α → List (Tree α) → Tree α          (numNested 1, isRec true)
  Tree.rec.{u_1,u}, Tree.rec_1.{u_1,u}: numParams 1, numMotives 2, numMinors 3, all [Tree]
  binders: {α} {motive_1 : Tree α → Sort u_1} {motive_2 : List (Tree α) → Sort u_1} (node) (nil) (cons) ...
  node : (a : α) (as : List (Tree α)) (as_ih : motive_2 as) : motive_1 (Tree.node a as)
  Tree.rec   rule Tree.node, 2 fields: λ α m1 m2 node nil cons a as. node a as (Tree.rec_1 α m1 m2 node nil cons as)
  Tree.rec_1 rules List.nil (0 fields), List.cons (2 fields):
             λ ... head tail. cons head tail (Tree.rec ... head) (Tree.rec_1 ... tail)
Rose α | node : α → Array (Rose α) → Rose α: numNested 2; motive_2 for Array (rec_1), motive_3 for List (rec_2)
Mutual Prop pair EvenT/OddT: both recursors have levelParams [] (Prop only), numMotives 2, numMinors 3, k false
```

## 7. Where the reducer and type checker read these records

| Consumer | Fields used | Reference |
|---|---|---|
| iota (02 §7.4) | rec: `numParams+numMotives+numMinors+numIndices` = major index, `k`, `rules` (lookup by head constant of the major, `nfields`), `levelParams` | `inductive.h:74-119` |
| K | `k`, `numParams`, inductive of the major (from the recursor type), first ctor of that inductive | `inductive.h:28-48`, `inductive.cpp:76-85` |
| struct-eta of a major | `isNonRecStructure(I)` := `|ctors| == 1 && numIndices == 0 && !isRec`; ctor `numParams`, `numFields`; skipped if the major's type is a Prop | `inductive.cpp:28-33`, `:87-100`, `inductive.h:60-72` |
| struct-eta in `is_def_eq` | other side is a ctor application with exactly `numParams + numFields` arguments; `isNonRecStructure(induct)` | `type_checker.cpp:852-868` |
| unit-like | `isNonRecStructure`, ctor `numFields == 0` | `type_checker.cpp:1116-1126` |
| `Expr.proj` typing | see below | `type_checker.cpp:237-292` |
| `Expr.proj` reduction | head is a ctor with `induct == structName`; argument `numParams + idx` | `type_checker.cpp:416-437` |

Validity of `proj S i e` (02 §6.8): `i < 2^32`; `T := whnf(infer(e))` has head `Const S ls` with `S` an
inductive type with exactly one constructor and `|args(T)| == numParams + numIndices` (indices and
recursive types are allowed; only eta needs `isNonRecStructure`); walk the ctor type instantiated with
`ls` and the parameter arguments; for each earlier field `j < i` whose binder is used by later binders:
if `T` is a Prop the field's type must be a Prop, and the variable is replaced by `proj S j e`; the
`i`-th binder must exist; if `T` is a Prop its type must be a Prop. `isProp` is the throwing,
level-normalising test of 4.2. Tutorial `projOutOfRange`, `projNotStruct`, `projProp2/4/5/6`,
`projIndexData*`; `bugs/proj-non-structure`, `proj-of-imax-prop`, `proj-of-stuck-prop`, `proj-of-subst-prop`.
The structure-ness of `S` is therefore decided entirely by regenerated data (`ctors`, `numParams`,
`numIndices`, `isRec`, ctor `numFields`); a lie in the export cannot reach it (`bugs/ctor-num-fields`).

## 8. Literals, special constants and `Init.Prelude`

Names hard-wired in the kernel (`type_checker.cpp:1241-1266`, `inductive.cpp:1371-1390`, `quot.cpp:106-115`):
`Nat`, `String` (types of literals), `Nat.zero`, `Nat.succ`, `Nat.add`, `Nat.sub`, `Nat.mul`, `Nat.pow`,
`Nat.gcd`, `Nat.mod`, `Nat.div`, `Nat.beq`, `Nat.ble`, `Nat.land`, `Nat.lor`, `Nat.xor`, `Nat.shiftLeft`,
`Nat.shiftRight`, `Bool.true`, `Bool.false`, `String.ofList` (the string hook in v4.36, a `def`, not a
constructor), `Char`, `Char.ofNat`, `List.nil.{0}`, `List.cons.{0}`, `eagerReduce`, `Eq`, `Quot*`.

What the official kernel checks about them: **nothing**, except `Eq` when `Quot` is declared. `infer_lit`
returns `Const Nat` / `Const String` without looking them up; the Nat extension fires on the *names* of the
functions; a string literal expands to `String.ofList (List.cons.{0} Char (Char.ofNat c) ...)` over Unicode
scalar values. The arena promises (README "On `Init.Prelude`") that prelude declarations are either absent or
taken from an official release, that checkers may check more, and that tests violating this would be
labelled and declined by the official kernel.

What is needed for soundness, by feature:
- *Literal typing and literal <-> constructor conversion*: only `Nat.zero : Nat`, `Nat.succ : Nat → Nat`,
  resp. the well-typedness of the expansion at type `String`. A literal is then an abbreviation.
- *Binary acceleration*: `Nat` must be the free inductive type on `zero`/`succ`, and each accelerated
  function must compute the standard function on numerals; `Nat.beq`/`ble` additionally need `Bool` with
  `false`, `true`. This is a semantic property of the definitions.

Stricter checkers: lean4lean (`Lean4Lean/Primitive.lean`, `Environment/Basic.lean:34-63`) reserves 24 names
(`Bool`, `Bool.false`, `Bool.true`, `Nat`, `Nat.zero`, `Nat.succ`, the 14 functions above plus `Nat.pred`
and `Nat.bitwise`, `String.ofList`, `Char.ofNat`); any declaration using one must pass a recogniser:
`Nat`/`Bool` must be exactly `inductive Nat : Type | zero | succ : Nat → Nat` / `Bool : Type | false | true`
(`:588-604`); each function must be a safe, universe-monomorphic definition of the expected type whose
defining equations hold by `is_def_eq` on variables, e.g. `add x 0 ≡ x`, `add x (succ y) ≡ succ (add x y)`;
`mul x (succ y) ≡ add (mul x y) x`; `pow x (succ y) ≡ mul (pow x y) x`; four cases for `beq`/`ble`;
`shiftLeft x (succ y) ≡ shiftLeft (mul 2 x) y`; `land`/`lor`/`xor` must be `Nat.bitwise f` with the right
truth table; `mod`/`div`/`gcd`/`bitwise` are recognised through their well-founded/fuel encodings, which is
tied to one prelude version (`:319-563`). `Char.ofNat : Nat → Char` and `String.ofList : List Char → String`
are checked for their types only (`:481-499`).

Minimal sound policy for RLVM (three levels; implement L1 first):
- **L0 = official**: no checks. Equal to the official verdict on every current test.
- **L1 shape guards** (cheap, release-independent): (a) a block declaring `Nat` or `Bool` must have exactly
  the shape above, else the literal/Nat extension is unusable; (b) a declaration named like an accelerated
  function must be a safe definition without universe parameters whose type is `Nat → Nat → Nat`
  (`Nat → Nat → Bool` for `beq`/`ble`); (c) before the first string literal is typed: `String.ofList : List Char → String`,
  `Char.ofNat : Nat → Char`, `List.nil.{0} Char : List Char`, `List.cons.{0} Char : Char → List Char → List Char`; (d) a reserved
  name declared with another kind fails the guard. On a failed guard: **exit 2 (decline)**, not 0 and not 1 -
  the test is then scored neither as wrongly accepted nor as wrongly rejected.
- **L2 semantic recognisers** as in lean4lean, for the functions with simple recursion equations; needed
  only if the arena adds labelled forged-prelude tests. `bugs/nat-rec-rules` and `bugs/nat-rec-k-lie` (a
  wrong `Nat.rec`) are already caught by record comparison, not by this.

## 9. Policy for the records of an export (RLVM)

### 9.1 `inductive` item
```
admitInductiveItem(item = {types, ctors, recs}):
  if a type record is unsafe: drop the item (4.4)
  require types nonempty; all type records agree on levelParams and numParams;
          every T.all == [names of item.types in order]                      # stricter than official, see below
  for T in types: Cs(T) := the ctor records named in T.ctors, in that order   # each must exist in item.ctors
  G := addInductive(env, {lparams, nparams, [(T.name, T.type, [(c.name, c.type) | c in Cs(T)])], false})
  for every safe ctor record c in item.ctors:  g := G.ctor[c.name] or reject "No such constructor"
        require c.{levelParams, induct, cidx, numParams, numFields} == g's and c.type == g.type
  for every safe rec record r in item.recs:    g := G.rec[r.name] or reject "No such recursor"
        require r.{levelParams, all, numParams, numIndices, numMotives, numMinors, k} == g's,
                r.type == g.type, |r.rules| == |g.rules| and pairwise (ctor, nfields, rhs ==)
  commit G (all names were checked absent during generation)
```
- The official checker keys everything by name in one flat map, so a constructor may sit in a different
  `inductive` item than its type; `lean4export` never does that. Requiring the item to be self-contained and
  the `all` lists to agree is stricter only on hand-made files.
- The official checker does not require a generated constructor/recursor to be *present* in the export;
  missing records are harmless because the generated ones are used.
- Exported `numIndices`, `numNested`, `isRec`, `isReflexive` are not compared (decision 8).
- Optional completeness fallback, off by default: if a `==` comparison fails, fully type-check the exported
  recursor type / rule and only then accept it if it is definitionally equal to the generated one *and* the
  two inferred types are definitionally equal. The arena descriptions of `rec-rule-proof-irrel` and
  `rec-type-proof-irrel` name "type check before comparing" as the sound alternative. It would mask an RLVM
  bug in 6.13, so log every use.

### 9.2 `quot` record: section 5(c).

### 9.3 `axiom`, `def`, `thm`, `opaque`
Section 4. Axioms are admitted unconditionally once their type checks; the official checker has no axiom
whitelist (exit 0 even with `sorryAx` or `Lean.trustCompiler`; declining such inputs is allowed but costs a
"declined").

### 9.4 Order, duplicates, forward references
- Duplicate declaration name anywhere in the file -> reject (parse-time in the official checker).
- `lean4export` emits every dependency (of types, values, ctor types, recursor rules) before its user and
  the four quot records right after `Eq` (`Basic.lean`, `dumpDeps`), so file order is a valid admission
  order for generated files. The official checker accepts any order. Recommended: streaming admission with a
  "parked" set keyed by the first missing constant; at end of file a parked declaration is an unknown
  constant (reject). A declaration can never depend on itself (tutorial `selfProof`).
- Before a declaration's value is made available for unfolding by others, it must have passed the
  root-level closedness test (4.1): `bugs/loose-bvar-value` hangs checkers that unfold an unchecked value
  containing a loose bound variable. If bodies are checked concurrently, publish (header checked) ->
  check body later is sound for the final verdict only if the reducer is total on ill-typed closed terms.

## 10. Bad tests about declarations -> the check that rejects them

`#n` refers to the checklist in section 11. Tests of pure reduction/def-eq/level algebra are listed last.

| Test | Forgery / defect | Rejected by |
|---|---|---|
| `bugs/ctor-num-fields` | ctor record says `numFields = 0` | #49 ctor record != generated |
| `bugs/rec-k-lie`, `bugs/nat-rec-k-lie` | recursor record says `k = true` | #50 (`k` from 6.9) |
| `bugs/nat-rec-rules`, `unit-rec-rule`, `rec-rule-wrong-arg` | wrong rule right-hand side | #50 rule `rhs ==` |
| `bugs/nested-rec-rule-nfields` | rule `nfields` of a nested block | #50 rule `nfields` |
| `bugs/rec-rule-proof-irrel`, `rec-rule-proof-irrel-nested` | ill-typed rule equal only up to proof irrelevance | #50, structural comparison, also for `rec_N` |
| `bugs/rec-type-proof-irrel` | recursor type altered inside a proof argument | #50 type `==` |
| `bugs/large-elim-param`, `large-elim-prop-bool` | recursor with a motive universe it must not have | #44 then #50 (`levelParams`, type) |
| `bugs/orphan-rec`, `other/extra-rec`, tutorial `BogusRecursor` | recursor nobody generates / wrong type | #51 / #50 |
| `other/orphan-ctor` | ctor of a non-existent type inside the item | #51 |
| `bugs/nested-name-capture` | rule calls `_nested.Cov_1.rec`, defined later as a `def` | #50 (generated rule calls `Shadow.rec_1`); aux names never enter the environment |
| `bugs/nested-unused-param` | ill-typed container parameter that vanishes from the auxiliary block | #48 check of `I Ds` in the final environment |
| `bugs/rec-missing-ih` | exported recursor whose rule omits an induction hypothesis | #41 (invalid occurrence) or #50; single classification (decision 11) |
| `bugs/quot-bad-type` | `Quot` record with type `False` | #24 |
| `bugs/loose-bvar-value` | definition value is a loose `#0` | #11 |
| `bugs/constlevels` | constant applied to the wrong number of universe levels | #8/#12 (and a guard in delta-unfolding) |
| `other/inductive-undeclared-universe` | header uses `Sort u`, `u` undeclared | #33 |
| tutorial `inductBadNonSort`, `inductBadNonSort2` | header does not end in a sort | #35 |
| tutorial `inductLevelParam`, `tut06_bad01` | duplicate universe parameter | #28, #5 |
| tutorial `inductTooFewParams` | `numParams` exceeds the Pi prefix | #31/#34 |
| tutorial `inductWrongCtorParams`, `inductWrongCtorResParams`, `inductWrongCtorResLevel` | wrong parameter domain / swapped parameters / swapped levels | #30 (also #39, #42) |
| tutorial `inductInIndex` | block type inside an index of the result | #42 |
| tutorial `indNeg`, `indNegReducible`, `reflOccLeft` | negative occurrence | #41 |
| tutorial `reflOccInIndex` | occurrence in an index of a recursive field | #41 (non valid occurrence) |
| tutorial `reduceCtorType` | ctor type is not a manifest Pi telescope / application | #42 |
| tutorial `typeWithTooHighTypeField` | field universe above the type's | #40 |
| tutorial `dup_defs`, `dup_ind_def`, `dup_ctor_def`, `dup_rec_def`, `dup_rec_def2`, `dup_ctor_rec`, `DupConCon` | one name declared twice (def/type/ctor/rec in any combination) | #2, #4, #32, #37, #43, #46 |
| tutorial `misnamed_rec` | recursor exported as `I.not_rec` | #51 (and unknown constant in its user) |
| tutorial `nonTypeType`, `nonTypeAxiom`, `nonPropThm`, `badDef`, `forallSortBad`, `selfProof` | section 4.3 | #10, #10, #16, #14, #13, #12 |
| tutorial `falseFromUnsafe`, `falseFromPartial` | theorem uses an unsafe / partial constant | #3 then #12 (unknown constant) |
| tutorial `projOutOfRange`, `projNotStruct`, `projProp2/4/5/6`, `projIndexData*`; `bugs/proj-non-structure`, `proj-of-imax-prop`, `other/proj-of-prop` | invalid projection | #13 with the rules of section 7 on regenerated records |
| tutorial `ruleKbad`, `ruleKAcc`, `accRecNoEta`, `indexedUnitEta`, `indexedStructEta`; `bugs/k-rec-conv` | def-eq must not use K / eta here | #14 with regenerated `k`, `numIndices`, `isRec` |
| `bugs/proj-of-stuck-prop`, `proj-of-subst-prop`, `rec-of-subst-prop` | a sort that is stuck after substitution | `isProp` must throw (4.2), 02 §6.10 |
| `bugs/level-imax-leq`, `level-imax-normalization`, `closure-level-subst`, `pi-closure-context`, `open-term-cache-context`, `eta-ctor-alias`, `str-lit-utf8-bad`, `other/nat-lit-add-bad` | core type checker, not admission | 02 |

"Either" tests touching admission and the official answer: `corner-cases/large-elim-maybe-prop` (official
rejects: recursor differs), `proof-param-swap` (rejects, #30/#42), `positivity-whnf` (rejects, #41),
`vanishing-occurrence` (accepts), `nested-redex-aux` (accepts, two auxiliary types),
`nested-nonuniform-param` (rejects, #30), `proj-maybe-prop*` (accepts). `corner-cases/proof-param-ok` must
be accepted.

## 11. Admission checklist, in execution order

Load time (per record):
1. The line parses; every referenced name/level/expr index is already defined; no index is defined twice.
2. The declaration name is not already used by any earlier record of any kind (flat namespace).
3. Records flagged unsafe/partial are dropped (4.4); they are not admitted and not compared.

Every admitted constant (`checkConstantVal`):
4. Name absent from the environment (this includes generated names such as `I.rec`, `Quot.mk`).
5. `levelParams` has no duplicates.
6. Type is closed (no loose bound variables; no fvar/mvar by construction).
7. Every universe parameter occurring in the type is in `levelParams`.
8. Every constant in the type is admitted and applied to as many levels as it has parameters.
9. The type type-checks.
10. The type of the type reduces to a sort.

Definition, theorem, opaque (after 4-10; axioms stop at 10):
11. Value is closed.
12. Universe parameters and constants of the value satisfy 7 and 8.
13. Value type-checks, giving `T'`.
14. `isDefEq(T', declared type)`.
15. (definition) `hints` are stored verbatim and never trusted for correctness; `all` is ignored.
16. (theorem, before 11) `isProp(type)`: the type's sort level normalises to zero; a stuck sort is an error.
17. The constant becomes visible only after 4-16 (no self-reference).

Quot (first `quot` record):
18. `Eq` is admitted and is an inductive type.
19. `Eq` has exactly one universe parameter and exactly one constructor.
20. `Eq`'s type is `Pi(Sort u, Pi(#0, Pi(#1, Sort 0)))`.
21. Its constructor's type is `Pi(Sort u, Pi(#0, Eq.{u} #1 #0 #0))`.
22. `Quot`, `Quot.mk`, `Quot.lift`, `Quot.ind` are absent.
23. Add the four canonical constants; set `quotInit` (enables quot reduction).
24. Every exported quot record is one of the four names with identical kind, `levelParams` and type.

Inductive item:
25. Item consistency: nonempty; type records agree on `levelParams`, `numParams`, `all`; each `ctors` entry
    resolves to a ctor record of the item.
26. `numParams` fits a machine integer.
27. No constant or projection structure name with first component `_nested` in any type or ctor type.
28. `levelParams` has no duplicates.
29. (types and ctor types have no mvar/fvar - vacuous for exports.)
30. Uniform occurrences: every occurrence of a block type in a ctor type is applied to the block's levels
    and the ctor's parameter variables (6.2).
31. Nested elimination: first type and every ctor have at least `numParams` *syntactic* Pis; parameters of
    a nested container contain no local variables; container types strip their own parameters syntactically.
32. For each (auxiliary-block) type: `I` and `I.rec` absent.
33. Each type header type-checks under `levelParams` (closed, constants known, universes declared).
34. Header telescope: later types' parameter domains def-eq to the first type's; exactly `numParams` consumed.
35. Header ends (after `whnf`) in a sort.
36. All result sorts are `is_equivalent` to the first one.
37. Type names pairwise distinct; per type, ctor names distinct; each ctor name absent from the environment.
38. Each ctor type type-checks in the environment extended by the block's types.
39. Ctor parameter binder domains def-eq to the block parameters.
40. Each field's type is a type, and its universe is `<=` the result universe unless the result is a Prop.
41. Each field is strictly positive (after head normalisation); a recursive field ends in a valid `I params idx`.
42. Ctor result is syntactically `I_k params idx` with the block's levels, the parameter variables, the
    right arity and no block type inside the indices.
43. Ctor names absent when added (catches clashes across types and with type names).
44. Elimination universe (6.8).
45. K flag (6.9).
46. Recursor types and rules built; each recursor name absent when added (catches a ctor named `rec`).
47. Defensive: generated recursor types type-check and each rule preserves types.
48. Nested blocks: restore; every restored name absent; every nested application `I Ds` type-checks in the
    final environment (mandatory); restored ctor types, recursor types and rules type-check (defensive).
49. Every exported safe ctor record equals the generated constant of that name (all fields, type by `==`).
50. Every exported safe recursor record equals the generated constant of that name (all fields; type and
    rule right-hand sides by `==`; `k`; rule count and order).
51. An exported ctor/recursor name without a generated constant of the same kind is rejected.
52. Commit the generated constants (not the exported ones) to the environment.

End of file:
53. No parked declaration remains (missing dependency = unknown constant).
54. Exit 0 only if every record passed; exit 1 on any failure; exit 2 only by explicit policy (section 8).

## 12. Not verified / open points

- Not read: the Mathlib export itself. Whether it contains a non-identity `whnf` at the positions of T4, or
  containers whose instantiation exercises T3, is inferred from the exporter and kernel code, not observed.
  That exports contain `partial` definitions is inferred from the exporter's filter plus a count of partial
  constants in a live Lean 4.33 environment (`work/agent03-decl/Probe3.lean`).
- The level-simplification and annotation claims were observed on Lean 4.33.0 output, read in v4.36.0 code.
- "The official checker ignores `numIndices`/`isRec`/..." is from `Replay.lean`; the arena's pinned
  `official` checker is v4.35.0 (per 02 §0, same kernel); its `Replay.lean` was not diffed against v4.36.0.
- Exit codes of `leanchecker` on parse failures (uncaught `IO` exception) were not observed by running it.
- `lean4-leanexport` and `lean4-replay` are sparse clones of the same tag; `Init/Prelude.lean` was read only
  for the declarations named in section 8.
- PR numbers are quoted from source comments, test descriptions and `lean4lean/divergences.md`.
