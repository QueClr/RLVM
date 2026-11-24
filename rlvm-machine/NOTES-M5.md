# NOTES-M5 - inference, checking, the DECL driver, facts (owner: M5)

Deviations from `docs/design/ISA.md` (revision 2) and things a caller should know, each with its reason.

## Inference and checking (v0)

| # | Where | Deviation / decision | Reason |
|---|---|---|---|
| I1 | `Infer` layout | INFER is one judgment (one tick, one call event `Op.INFER`); the node kinds run as internal chains with frames of their own ops: app = `TWALK[1..7]`, pi = `INFER2[1..5]`, lam (CHECKED) = `INFER2[6..9]`, let = `INFER2[10..15]`, proj = `IPROJ[1..14]`; `INFER[1](s, t, mode)` lies below the chain's frames and stores TYPE / TSTAMP when the chain returns. TWALK and IPROJ charge no tick and raise no event of their own | ISA 1.3 lists `INFER [t, env, ls, mode, x1, x2]` with at most 16 states; the telescopes and the projection chain need about 40 resume points. One judgment may own several frames under convention H (frames are private to their judgment, NOTES-M6 S11) |
| I2 | IPROJ registers | the projection chain keeps its ten registers (type, structure row, index, struct value, struct term, env, ls, is-prop, nparams) in scratch `X[xb ..]`; its frames carry `xb` and at most two more words | H7 allows scratch owned by a judgment with its base in the payload; the alternative is 12-word frames in every one of 12 states |
| I3 | IPROJ, generic tier | no `FIELDS(S)` block (step 3): the constructor's type is walked as a value, as the official `infer_proj`: `VTYPE(CONST ctor at the levels of the type)`, INST with the parameters, then per field. "Field j is read later" is the node mask of the Pi's body (superset); **where a set bit can reject** (the type is a proposition) it is first confirmed by `TermOps.hasLooseBVar(body, 0)` (ISA 4.5.5 last comment). Where it cannot reject, a spuriously set bit only instantiates with `MKPROJ` a variable nothing reads | exactness where it matters, no walk elsewhere |
| I4 | index beyond `nfields` | no explicit test: after `nfields` fields the rest of the constructor type is the inductive type, not a Pi, which is `fail(E_PROJ)` in the official order (R02 6.8). The error order of the official kernel is kept (`is_prop(type)` may fail first with E_TYPE_EXPECTED; both are REJECT) | RECONCILIATION "Projections": `i >= nfields` fails with E_PROJ - it does |
| I5 | lam, ONLY mode | answered inline before the tabling probe: `PII(t, PRUNE(env, mask t), ls')`, no SUSP record, no tick | the PII is a function of the same key; a SUSP record would only repeat it |
| I6 | lam, CHECKED | the PII is made before the telescope is checked and returned after it | saves two payload words (frame, ls') in four frames; a record made early is just a value |
| I7 | `VTYPE(SORT l)` | memoised in word m2 of the SORT record (ISA 2.6 gives SORT no memo word) | m2 is unused on SORT; saves two probes per query |
| I8 | `VTYPE(SUSP)` without a stored TYPE | `VTYPE(FORCE(v))`, tail call; nothing is written into the tabling record | the value-route type and the term-route type are convertible but need not be the same record; only INFER writes TYPE |
| I9 | let-bound variable | `INFER(variable)` = `VTYPE(LET suspension)` = the infer-only type of the value (the LET suspension is another record than the tabling record, NOTES-M1), not the declared type of the official let-fvar | ISA 4.5.1 "variable i: VTYPE(GET(env, i))"; in CHECKED mode the two were compared with CONV when the let was checked |
| I10 | literals | a Nat / String literal whose type constant is not declared or not visible is `fail(E_UNKNOWN_CONST)`; guard not VALID: `fault(E_PRELUDE)`. The numeral bound of a pooled literal is asked of `ctx.nats.checkLiteralSize` (a `KernelException` on that error path becomes `fail(E_NATSIZE)`) | the reference engine does the same (`inferNatLit`); the official kernel builds `Const Nat []` unchecked. The structural checks make the first case unreachable |
| I11 | unbound nodes | CONST with `a <= 0`, PROJ with `c <= 0`: `fault(E_INTERNAL)` (DECLINE), never a REJECT | ISA 4.2 KCONST, section 9 |
| I12 | loose bound variables | not tested per node: `Decl.check` rejects a root that is not closed with `E_LOOSE_BVAR` (O(1) from the header); a direct judgment on an open term faults in `GET` (E_INTERNAL) | ISA 1.4: the machine relies on the structural checks |
| I13 | ER | TWALK sets `m.ER = 1` for the comparison of an `eagerReduce _ _` argument and keeps the old value in its own frame `TWALK[5]`; no separate `ERF` frame | under convention H the frame of the caller already exists whenever the comparison is suspended |
| I14 | ENPI / ENSORT / ISPROP on a SUSP | no FORCE of their own: a SUSP is not a SORT / PI, so the WHNF they call forces it; ISPROP sets PROPT on the record it was given | types may be suspensions (`EVAL` of a variable bound to a delayed argument) |
| I16 | COD of a PII | when a **single** lambda was checked and the type of its body under the canonical variable is a closed value (flag OPEN clear, not a SUSP), INFER writes that type into `M_COD` of the lambda's PII: the codomain at every argument. M2's `INST` returns it inline | RECONCILIATION 3 "the body type of a closed lambda-type closure is memoised" (gruszka `lam_dm`). It is the official `instantiate(binding_body(f_type), arg)` for a body type that does not mention the bound variable, and it keeps `(fun x => b) a` ladders linear (`perf/beta-ladder`: E_MEM after 367 k ticks before, 18 k ticks after). `M_COD` is M2's memo word: its `memocheck` audit (`Eval.checkCod`) must not recompute such an entry with HOLE (requested). Telescopes of two or more lambdas are not covered (the inner PII of an application has another frame) |
| I15 | TWALK, telescope mode | ISA 4.5.4 "while the codomain is a syntactic Pi keep (body, PUSH(frame, g_i)) instead of a PI record" is implemented as a mode of TWALK: when the prefix's type is a PI record whose binder is read and whose codomain is again a syntactic Pi, the following arguments instantiate Pi NODES; the telescope's environment `ENTER(F0) ++ g[K0 .. i]` is **not kept on `es`**: the values `g_j` overwrite the argument-term slots in scratch, and the environment is built on top of `es` only for one EVAL (a domain in CHECKED mode, the codomain where the syntactic telescope ends) and cut back after it; closed terms are evaluated under the empty environment and a variable domain is read from its slot. A binder that is not read stays with `INST` (answered from the COD memo). TWALK's registers (n, site, mode, node, frame, ls, first index) are in scratch `X[xb ..]`, its frames carry `xb` and at most four words. `VTYPE` of a NEU does the same per group of syntactic Pis (`neuGroup`: one environment, one EVAL) | keeping the telescope's environment on `es` would bury the environment of the argument terms, and every binder opened while an argument is inferred would copy it (the quadratic trap of ISA 2.4). Measured on Init (in-process, one thread): ticks 93.6 M -> 78.2 M, call events 79.2 M -> 63.8 M, INST 13.5 M -> 5.1 M, DOM 6.9 M -> 2.9 M; thread CPU of the check phase: 13.3 - 17.0 s before; 15.1 - 15.3 s after in the first run (host load 160-180, inside the noise) and 10.95 - 11.91 s in a later run of the same code with VTYPE grouping (load 125-140). One run each: the tick and call counts are exact, the CPU gain is roughly a fifth and not better established than that. Results are the same values: the gates and the convention-equivalence report were re-run |

| I17 | binder domains are forced | `Infer.evalDom`: the domain term of a Pi / lambda binder is evaluated and, if the value is still a suspension, forced before `OPEN` (pi and lambda telescopes, B9, SIGD); frame `VTYPE2[7]()` if the EVAL is suspended | advice of M2: `EVAL` may return a delayed argument, while `DOM` (CBIND, ETA) is forced; without the FORCE the same binder type gives two different variables and the identity and PAIR hits between INFER and CONV are lost (never a wrong answer). ISA 4.5.1 writes `x = OPEN(call EVAL(A, env))` |
| I18 | TELE hooks | `inferApp` and VTYPE of a NEU call M7's `Code.blockFor(row, ROLE_TELE)` / `Code.tele` / `Code.teleValues` behind `SW_TELE` (off), then continue the generic walk in value mode for the arguments beyond the block's arity | staging step 3; the call sites are in M5's file, the blocks and their stepper are M7's (tier equivalence is M7's / M8's test) |
| I19 | memocheck hooks | tabled INFER hit: TYPE and TSTAMP are zeroed, INFER is re-run under `Debug.memoEnter` / `memoExit`, the entry is put back; VTYPE: `Debug.memoCheck(Op.VTYPE)` for M_TYPE of CONST / NEU / PROJ / LAM, the generic form for M_SLVL; ISPROP: `Debug.memoCheck(Op.ISPROP_F)`. `M_COD` of I16 is not written while `memocheck` is on (temporary, until `Eval.checkCod` re-computes at the caller's argument instead of HOLE) | ISA 5.5; request of M6 |

## B9, expected-type checking (staging step 1, switch `expect`; off by default)

| # | Decision | Reason |
|---|---|---|
| E1 | `CHECK` of a lam node (frames `CHECK2[1..8]`): tabling probe (a stored checked type answers by `CONV(TYPE(s), V)`), `W = WHNF(V)`; not a PI / PII: the generic rule; else `ENSORT(INFER(A, CHECKED))`, `D = EVAL(A)`, `CONV(D, DOM(W))` or `fail(E_TYPE_MISMATCH)`, `x = OPEN(D)`, `CHECK(body, env + x, INST(W, x))`, CLOSE; then `TYPE(s) = PII(t, frame of s, ls')` with the check stamp. One lambda per CHECK (the recursion on the body takes the next) | ISA 4.5.2 as written |
| E2 | `CHECK` of a let node: the bindings as INFER.let in CHECKED mode (the same frames `INFER2[10..14]`, whose mode word carries the expected type), then `CHECK(body, env', V)`; nothing is stored for the let node | ISA 4.5.2 "let"; the type of the body is not computed |
| E3 | TWALK: a lambda argument is checked against the binder's domain (`TWALK[8..9]`); a mismatch there is `E_TYPE_MISMATCH`, where the generic rule raises `E_APP_MISMATCH` (both REJECT) | ISA 4.5.4 "t_i is a lam node: call CHECK(t_i, env, D)" |
| E4 | A closed expected body type becomes `M_COD` of the lambda's PII, as I16 | the lambda has the type `(x : D) -> B` with B closed |
| E5 | Not done: `app with constant head: CALL TELE(c) on the site with expected = V` | step 3 (M7's TELE blocks) |
| E6 | Measured (`M5Bench`: in-process, one thread, thread CPU of the check phase, second of two rounds, ONE run per configuration, host load average 125-180; differences below about 10% are not established) | Init: v0 10.95 s, `gated` 9.58 s, `gated,expect` 8.39 s (CBIND 0.78 M -> 0.48 M, CONV 2.49 M -> 2.19 M). cedar-hot-ax (8,064 engine checks): v0 4.98 s, `expect` 3.90 s, `gated` 3.28 s, `gated,expect` 3.17 s; `expect` cuts CBIND 970 k -> 697 k, CONV 1.38 M -> 1.11 M, INST 1.98 M -> 1.55 M, DOM 2.57 M -> 1.99 M in both regimes. So the call counts fall by 20-28%; the CPU gain is clear without gated tabling and NOT established on top of it on the Cedar slice (3%, inside the noise). Frames written rise (cedar 3,848 -> 60,839: one CHECK level per lambda instead of one loop per telescope). Every configuration accepts all units, 0 declined |

## Tabling and check stamps (ISA 5.1, 5.3)

| # | Decision | Reason |
|---|---|---|
| T1 | The regime of 5.3 is decided **per declaration by `Decl.check` itself**: `trusted` iff both roots pass `depMax < rank`, no `F_UNRESOLVED`, `lpSet` within the declared parameters (O(1) per root). Trusted: CHKCONST / CHKLVL are no-ops. Otherwise (direct judgment tests, `MachineOps`, a root that only the exact walk of admission could clear) they run per node | fail closed without relying on the caller; the test costs two header reads per declaration |
| T2 | Stamp of a CHECKED result: PERM iff the term is closed and has no level parameter, else `gen` - **also in the trusted regime**. `gen` stays while consecutive declarations are trusted (ISA: "gen never advances") or while the level parameters of the new declaration contain those of the previous one; otherwise `gen++` | one rule for both regimes; a session that mixes them stays sound (a PERM stamp never depends on trust) |
| T3 | An ONLY completion never overwrites a stored TYPE | it could only replace a check stamp by 0 |
| T4 | `tabled(t)` under `SW_GATED_TABLING`: sharing flags of the node, or an export id below `DeclTable.unitFirstTerm(unit)`, or a generated id. v0: every non-leaf node on which INFER is called. Spine prefixes are not tabled separately (the official check-mode cache has an entry per prefix); a shared prefix is re-walked, its arguments are tabled | the judgment is INFER of the spine; correct, and at most quadratic on prefix chains |
| T5 | CHECK (v0) has no table of its own: it is the CHECKED INFER (tabled) followed by CONV; the expected type is never stored | ISA 4.5.2 "generic"; B9 is step 1 |

## DECL (ISA 4.5.6)

| # | Decision | Reason |
|---|---|---|
| D1 | theorem: "the type is a proposition" is `isAlwaysZero` of the level phase 1 returned (`ENSORT(INFER(type, CHECKED))`), tested before the value is looked at; no `ISPROP(V)` | the official `is_prop(type)` re-infers the same sort in infer-only mode; same level, one inference less. Order as `add_theorem` |
| D2 | `Decl.check` returns `E_INTERNAL` (DECLINE) instead of ACCEPT if a phase completed but left a machine stack non-empty (`!m.idle()`) | I-STACKS / I-SCOPE are assumptions of every later judgment; an accept on a machine in an unknown state is not fail-closed |
| D3 | a row without a value where one is needed, or without a type: `E_INTERNAL` | the loader never writes such a row |

## Facts (staging step 2, switch `facts`; off by default)

| # | Decision | Reason |
|---|---|---|
| F1 | `Facts.facts(m, row)` is the pure query (published index or 0); the record is made by the **judgment** `Facts.sigd(m, row, d)` (frames `SIGD[1..4]` and one TRY of `Op.B_SIGD` ticks) | ISA 13.4 "facts .. triggers SIGD through Frames": under convention H a derivation is a judgment call with its own signal test; an accessor cannot start one |
| F2 | Every level ref of a record is `POOL + offset` (a structural encoding by `Levels.encodeLevel`); `BASE + id` is never written | the record is then word-for-word the same whichever worker derives it and whatever the level snapshot of that worker is (pipelined loading); a session pays one decode per (record, slot) through `LIMP`. The test "six sessions = one session, word for word" relies on it |
| F3 | The store keeps the record length in the word before `f0` (`FactStore.lengthOf`) | the layout of ISA 3.5 has no length; the tests and `pubcheck` need it |
| F4 | `FactStore` publishes with ONE `compareAndSet` of the row's slot in an `AtomicIntegerArray` chunk; data chunks and slot chunks hang off `AtomicReferenceArray` directories and are installed by compare-and-set; a publication that finds a record returns the winner (`lostRaces`), and counts `lostDifferent` if both derivations were complete and the words differ (must be 0) | ISA 3.3 "one release store"; ISA 12 R6. `java.util.concurrent.atomic` is the only non-array machinery; cap 1,024 data chunks (256 MiB) |
| F5 | The syntactic part (arity, `reads`, `dep[i]`, lamArity, `unread`, shape) is computed without the machine, before the TRY; the TRY segment only infers the sorts. Positions >= 64: binders are opened, sorts are not inferred | a derivation that ends in U still knows the counts; they are published with F_NONE and not used |
| F6 | Value part only for a value that is an export term (`DeclTable.value > 0`); a value kept as a proof block has `lamArity = 0` | ISA 3.3: nothing of an overlay is published; and a theorem is opaque to lazy delta |
| F7 | Shapes: only `SHAPE_PICK` (operand = parameter index) is recognised. PROJARG, CTORBODY, CASESON, MATCHER stay `SHAPE_NONE` | step 4 (their operand encoding is M7's / M3's to agree on) |
| F8 | `staticProof`: TRI_NO for SORT, PI, PII and literals; for a constant applied to exactly `arity` arguments TRI_YES / TRI_NO from `rlvl[ls]` (`isAlwaysZero` / `isNeverZero`); everything else TRI_UNKNOWN. All three answers are TRI_UNKNOWN / 0 while the switch is off or the session is strict | ISA 3.5 table, 14.2 |
| F9 | `Decl` derives the record of a declaration it accepted (`Facts.onAccept`); a signal out of that derivation is dropped (the stacks are restored) and the declaration stays accepted | ISA 4.5.6: "an optimisation only" |
| F10 | Manifest mutation tests (`M5ManifestTest`): the three exports, each rebuilt (a) with the family `Owner` as axioms in place of the inductive type with the stuck sort, (b) with the sort's definition replaced by an axiom, (c) variant (a) without the last steps of the exploit; verdicts compared with `leanchecker --from-export` with the switch on and off | ISA 3.5 says "rebuilt with an axiom family in place of the stuck sort" without fixing the construction; (a) makes the machine itself check the family and derive its facts, (b) makes the sort stuck everywhere, (c) gives one mutant the official checker ACCEPTS (`proj-of-subst-prop`). As long as no consumer reads the prop mask or the static PROOF (M4's step 2), the verdict rows cannot fail because of a fact; the record rows (`rlvl` of the constructor is UNKNOWN) test the rule itself |

## Wave 6 (2026-11-24): where inference spends, and four shortcuts

Tools of this section: `work/m5/` (`bench.sh` = `M5Bench` in-process on the sandbox classes with counters; `attr.sh` = the same
on an INSTRUMENTED COPY, `work/m5/attr`, that attributes records and run words to the call sites of `Infer` - never published;
`prof.sh` + `tools/jfrsite.py` = JFR samples by the nearest enclosing judgment; `nbuild.sh` = a CE native image `-O3` with the
sandbox's machine jar and the integrator's pipeline jars; `m.py` + `batch*.sh` = measured rows in the arena-shaped container
under the measurement lock, `work/m5/measure.tsv`). Counts are exact (in-process, one thread); CPU times are from the container.

### What the measurements say first (Init, `warm,gated,expect`, 4 MiB sessions)

| Question | Answer |
|---|---|
| Who makes the records? (443 per unit) | 59% are made inside `CONV` calls started by argument checks (`TWALK`'s `CONV(Tt, D)`: 246 per unit; `CHECK`'s 14). 78.8% of those conversions make no record; 5.4% of them (83,000 calls: `decide`, `omega` reflection, `Nat.le` unfolding - kernel work no inference rule can avoid) make 90% of it and half of all ticks of the run. Inference proper makes about 180 per unit: tabling records of INFER 47, telescope results (codomain EVAL / INST) 41, telescope domains 17, argument values 16, `CHECK` lambda `INST` 11, PII / PI closures 8, CONST 6, FVAR 4 |
| Where is the CPU? (JFR on the JVM, nearest enclosing judgment) | IN 42% of the samples, RD 36%, CV 11%, loader etc. 11%. Inside IN: EVAL of telescope domains and results (`twEval`) 11.9%, ARGV of arguments 6.0%, the tabling probe of INFER (PRUNE 3.4% + SUSP record 3.0%), spine copy 2.7%, `CHECK` lambda `INST` 1.7%, `inferConst` 1.5% |
| How often is `Tt == D`? | 11.24 M argument checks: 88.6% identical records. 2.79 M of the domains are open non-leaf terms that needed an environment and an EVAL; a structural MATCH against `Tt` answers 2.13 M of them (97% of the identical ones) |
| Does the tabling probe pay? | Yes: 6.24 M probes on APP nodes in CHECKED mode, 4.11 M hits (66%); but each hit costs a PRUNE and a record probe |
| What can a shared check fact save? | CHECKED inference of closed, level-parameter-free terms of EARLIER units: 97,088 outermost calls, 1.13 M ticks (2.0% of the run), 0.59 M records |
| What does a lazy ARGV save? | Little: of 6.16 M argument values, 3.10 M are atoms; of the 3.06 M others only 0.71 M are read by later domains alone (the result reads the rest) |

So the target "records per declaration down by a third" cannot come from inference rules: inference proper owns 41% of the
records and the rest is conversion work started by the official comparison of an argument's type with its domain.

### M: MATCH instead of building a telescope domain (switch `m5a`; ISA 4.4.4, 4.5.4, B5 of 6.3; R07 1.8 `inst_eq`)

| # | Decision | Reason |
|---|---|---|
| M1 | In TWALK's telescope mode (I15), CHECKED: before the domain of binder i is built, `tmatch(dom_i, g, Tt)` decides "`EVAL(dom_i, ENTER(F0) ++ g[K0 .. i), PLS)` is convertible with `Tt`" over the registers of the running TWALK; the environment is never built. T: the argument is accepted for this binder. Unknown (never a refutation): the domain is built and compared as before (`Tt == D`, else `CONV`), so `E_APP_MISMATCH` is raised exactly where it was | the complete procedure stays the fall-through (6.3 rule 3); the match is M7's `Match` generalised to an environment that has a frame part (`F0`) and to suspensions |
| M2 | Rules of `tmatch` (pattern node against value, the value seen through PEEK): variable: the binding (through PEEK) is the same record; sort: equivalent levels after LSUB; constant: same row, equivalent level lists; small literal: the immediate; application: a NEU with as many arguments whose head matches (a constant by row and levels, a variable by identity with the NEU's head) and whose arguments match pairwise; lam / pi node: the closure of that very node, ls' and a near frame that agrees with the environment on the node's mask; an unevaluated suspension: the suspension of that very term under an agreeing frame. At most 64 pattern nodes, nesting 16; HOLE bindings, far frames, NSUC, a NEU that EVAL would extend: unknown | T by congruence (R02 8.6) or by I-ID (the record EVAL would return). No judgment is called, no record is made, only levels are instantiated |
| M3 | Only non-leaf domain terms (app, pi, lam) are offered; a variable domain is read from its slot and a constant is one probe, as before. Value mode keeps `DOM(P)` (a memo word) | measured: 3.19 M domains offered on Init, 2.20 M answered |
| M4 | `shortcheck` (ISA 5.5): a T is audited by building the domain and `Debug.shortCheck(Tt, D, T)` (the complete CONV with the switches off) | a failed audit is `E_AUDIT` |
| M5 | Not done: the expected-type return for an application (ISA 4.5.4 "untabled t: MATCH(res, g, V) succeeds: ret V without building R"). Built and removed: it never fires. `CHECK` is only called on declaration roots and on bodies of binders, and the gate (T4) tables every BODY node, so no `CHECK` of an untabled application exists (0 of 1.09 M `CHECK` calls on Init). The form that would fire is pattern against pattern for an untabled application ARGUMENT (rpylean's `inst_eq_inst`): a probe on Init finds 383,000 result builds it would save (29% of the application results built at telescope ends, 4% of all EVAL calls). Not built | recorded as the next step; the gain is bounded by those numbers |

### P: shared check facts (switch `pchk`; ISA 5.1 last-but-one row, 5.3; the store's `SharedFacts.CHECKED` bit)

| # | Decision | Reason |
|---|---|---|
| P1 | Set: when a CHECKED INFER of a term completes (`INFER[1]`, which a signal never reaches) and its stamp is PERM (closed, no level parameter) and the term is in the export region (`0 < t < GEN_BASE`), the bit of `t` is set. Overlay terms (decoded proof blocks) and generated terms have no bit. The byte is read before it is written (a set bit dirties no shared cache line) | ISA 5.1 "closed shared term passed check mode"; the publication rule (only export ids) |
| P2 | Use: a CHECKED INFER of such a term whose bit is set, in a declaration whose roots passed the summaries (`Ext.trusted`), runs the node's chain in ONLY mode (same value: both modes return the same record) and stores the type with stamp PERM. A lambda returns its PII at once. `CHECK` of a lam / let node whose bit is set takes the generic rule (INFER + CONV) instead of B9 | the conditions check mode adds are declaration-relative (5.3): level parameters (none here) and visibility, which the root summary of the using declaration discharges (`depMax` of a sub-term never exceeds its root's; a literal's `depMax` covers its prerequisite names, RECONCILIATION section 2) |
| P3 | Never read in strict mode (no switch) or in an untrusted declaration; `shortcheck` runs the CHECKED chain after all and turns a kernel error in it into `E_AUDIT` | fail closed |
| P4 | Measured potential and effect on Init: 60,390 bits set, 117,875 CHECKED inferences answered in ONLY mode; ticks 56.03 M -> 54.87 M (-2.1%), records 442.8 -> 433.8 per unit. The bit only helps terms of the export region: theorem values are proof blocks decoded into the worker's overlay and have no shared id | the upper bound was 1.13 M ticks |

### J: the joint telescope check of DECL (switch `m5b`; ISA 4.5.2 last comment, R07 1.8)

| # | Decision | Reason |
|---|---|---|
| J1 | `Decl.joint`: while the declared type is a Pi node, the value a lam node and both have the SAME domain term (node id): `u_i = ENSORT(INFER(A_i, CHECKED))`, `x_i = OPEN(EVAL(A_i))`, once for both telescopes. Then `u = ENSORT(INFER(rest of the type, CHECKED))`, the sort of the type is `mkImax(u_1, .. mkImax(u_k, u))`, a theorem's must be always zero (before the value is looked at, as `add_theorem`), then `CHECK(rest of the value, env, EVAL(rest of the type, env))`, CLOSE all. The telescope state is in the driver's locals (H6) | the type part is INFER.pi verbatim; the value part is B9's lam rule with `D == DOM(W)` by construction and `INST(W, x)` = EVAL of the rest of the Pi under `env + x` (mask contract). Officially Pi against Pi is decided binder by binder (R02 8.1), so with identical domains the comparison is `typeof(v)` against `T` under the same variables. With no joint binder the calls are those of `phases` |
| J2 | Only identical node ids count as "the same domain" (the loader shares equal terms; hand-built fixtures do not, so `M5Step1Test.initPrelude` tests it on a real export) | no comparison is made; anything else is left to `CHECK` |
| J3 | Measured on Init: 220,353 joint binders (3.8 per declaration with a value); ticks 56.03 M -> 55.12 M (-1.6%), records 442.8 -> 430.1 per unit (-2.9%), CHECK 1.09 M -> 0.87 M, INST 1.19 M -> 0.97 M, DOM 1.48 M -> 1.29 M | each joint binder saves a tabling probe of the lambda and of its domain, `WHNF(V)`, `DOM(W)`, the PI closure of the rest of the telescope and its frame |

### C: INFER's front cache (no switch yet: system property `rlvm.machine.m5ic=true`; ISA 12 open question 6)

| # | Decision | Reason |
|---|---|---|
| C1 | A direct-mapped, lossy table (2^10 cells of 8 words; `rlvm.machine.m5ic.log2` for sweeps) in front of INFER's tabling probe. A cell holds (term, session epoch, frame, ls', TYPE, TSTAMP) of a tabling record whose TYPE is stored; its index is a hash of the term and of the binding of the innermost variable the term reads. A query whose term, ls' and epoch match and whose environment agrees with the cell's frame on every index of the term's mask (popcount comparisons, no hash) is answered under the validity rule of 5.3; anything else takes PRUNE and the record probe as before. Cells are written when INFER stores a type and when the probe hits | a memo hit cost a PRUNE (hash of the whole frame, probe, comparison) and a record probe (ISA "a memo hit costs almost what it saves"). The cache is exact: the frame comparison does not depend on how the environment was built. Far masks are not cached. Off in strict mode and in every debug mode (the audits see the canonical path) |
| C2 | It changes no result and no record. Inside one session it changes no tick either (`M5Step1Test.frontCacheChangesNothing`); in warm sessions its bytes count in the footprint (32 KiB), so resets move slightly | honest footprint |
| C3 | Measured on Init (2^12 cells): 4.13 M of the 4.14 M tabling hits are answered by the cache | the hit rate is not the limit; whether it pays is a CPU measurement (below) |

### F: facts are visible (ISA 3.5; `facts`)

| # | Decision | Reason |
|---|---|---|
| F11 | Cost: `Infer.Ext.sigdTicks` = ticks between the start of a SIGD derivation and its publication (the stamp travels in the record buffer, two words), lazy derivations and those of DECL alike; `Machine.factsDerived` / `factsNone` are now written (they were never incremented, so the statistics always showed 0) | "make their derivation cost visible" |
| F12 | Use: `Ext.factUse[Facts.USE_*]`, counted while `m.stats`: prop-mask queries / with a bit, unread-mask queries on a derived record / with a bit, static-PROOF queries on a constant head / answered YES / answered NO | "their use measurable"; requested of M6 for `dumpStats` |
| F13 | Measured on Init (`warm,gated,expect,facts`): 58,576 records derived in 121,541 ticks = **0.23% of all ticks** (the bound is 2%), 0 F_NONE; static PROOF 1.08 M queries: 3,678 yes, 947,262 no (87.6% definite); prop mask 945,213 queries, 26,405 with a bit; unread mask 314,944 queries, 6,507 with a bit. Effect of the consumers (M4): PIRR 1.15 M -> 0.17 M, VTYPE 1.65 M -> 0.62 M, ISPROP 83 k -> 33 k, ticks 56.03 M -> 53.35 M (-4.8%), records 442.8 -> 437.6 per unit | the derivation is cheap because the type telescope was just checked in the same session (every INFER is a memo hit) |

### Eager reduction (M6-F26, S-F4 / S-F5; 2026-11-24)

| # | Decision | Reason |
|---|---|---|
| I20 | `Infer.twD` sets the mode with `m.enterER()` (M6's request of 14:46Z), never by assigning `m.ER` | the facade must learn that the declaration compared under eager reduction. M6's rule since 15:07Z (F26, after S-F5): such a declaration is DECLINED by the machine (`E_LIMIT`, UNSUPPORTED_INPUT; `machine.eagerDeclines`) and checked by the reference engine. On Init 155 declarations enter the mode (718 comparisons, 1.1% of the machine's ticks before the rule); none of their `eagerReduce _ _` arguments has a type identical to its domain. In-process tools that stop at the first decline (`Fixture.checkAll`, so `M5Bench`) end at unit 2,718 of Init since then: the counters of this file were taken before, on one code state, and the measured rows go through the launcher |
| P5 | PCHK is not set once the declaration has entered eager reduction (`m.erSeen`) | the official answer of a comparison under `eagerReduce` depends on the declaration's history (its caches live for one declaration), so nothing derived after such a comparison is shared. A term whose bit is set was checked without any ER comparison, and it is closed, so its types are the same records in every declaration |
| M6 | MATCH is not restricted for `eagerReduce _ _` arguments | a T of MATCH is identity of the two values up to PEEK and the official level equivalence: the case in which TWALK compares nothing (`Tt == D`) and never enters the mode. An argument whose type does not match goes through `enterER` + CONV as before |

### Measurements of wave 6, batch 1: each switch on top of step 1

Native image CE `-O3`, arena-shaped container, ONE worker, under the measurement lock, one batch (`work/m5/batch1.sh`, rows
`b1-*` of `work/m5/measure.tsv`; image `work/m5/dist-a2` = this package's sandbox of 14:05Z with the integrator's pipeline
jars; two interleaved rounds per configuration). **The host was heavily loaded (load average 73-165 during the batch), so CPU
and wall seconds scatter by about +-5% and decide nothing below that; the instruction counts are exact (every repeat gave the
same number) and are the signal here.** Base = `warm,gated,expect`, `session-mib=4`. Every row: all units accepted, 0 declined.

| corpus | configuration | CPU s (two rounds) | wall s | RSS MiB | instructions G | against base | ticks |
|---|---|--:|--:|--:|--:|--:|--:|
| Init | v0 (no switch) | 8.29 | 8.35 | 252 | 139.2 | | 77.83 M |
| Init | base | 7.96 / 7.80 | 7.96 | 239 | 109.9 | | 56.03 M |
| Init | + m5a | 8.25 / 7.80 | 8.07 | 239 | 108.4 | -1.4% | 53.93 M |
| Init | + pchk | 7.81 / 8.14 | 8.09 | 240 | 107.9 | -1.8% | 54.87 M |
| Init | + m5b | 8.11 / 7.67 | 7.95 | 237 | 108.4 | -1.4% | 55.12 M |
| Init | + m5a,pchk,m5b | 8.05 / 7.97 | 8.05 | 240 | 105.7 | **-3.8%** | 52.36 M |
| Init | base + probes | 8.14 | 8.20 | 225 | 106.8 | | 52.97 M |
| Init | base + probes + m5a,pchk,m5b | 7.92 | 7.97 | 228 | 102.5 | -4.0% (of probes) | 49.15 M |
| Std | v0 | 13.76 | 13.87 | 329 | 223.6 | | 122.25 M |
| Std | base | 13.89 / 14.58 | 14.35 | 307 | 185.6 | | 94.06 M |
| Std | + m5a | 14.60 / 14.72 | 14.76 | 307 | 182.5 | -1.7% | 90.16 M |
| Std | + pchk | 14.50 / 13.43 | 14.11 | 310 | 182.8 | -1.5% | 92.37 M |
| Std | + m5b | 13.50 / 13.32 | 13.51 | 308 | 182.4 | -1.7% | 92.07 M |
| Std | + m5a,pchk,m5b | 13.10 / 13.07 | 13.14 | 312 | 177.5 | **-4.4%** | 87.04 M |
| Std | base + probes | 13.19 | 13.27 | 314 | 178.3 | | 86.71 M |
| Std | base + probes + m5a,pchk,m5b | 12.95 | 13.01 | 311 | 169.8 | -4.8% (of probes) | 79.51 M |
| Cedar slice | v0 | 3.40 | 3.44 | 190 | 47.1 | | 18.10 M |
| Cedar slice | base | 3.39 / 3.58 | 3.54 | 169 | 46.0 | | 18.71 M |
| Cedar slice | + m5a | 3.59 / 3.63 | 3.84 | 169 | 45.8 | -0.4% | 18.39 M |
| Cedar slice | + pchk | 3.31 / 3.34 | 3.36 | 170 | 45.6 | -0.9% | 18.46 M |
| Cedar slice | + m5b | 3.34 / 3.35 | 3.38 | 169 | 45.9 | -0.2% | 18.65 M |
| Cedar slice | + m5a,pchk,m5b | 3.28 / 3.54 | 3.45 | 169 | 45.3 | **-1.5%** | 18.09 M |
| Cedar slice | base + probes | 3.00 | 3.04 | 171 | 41.9 | | 15.67 M |
| Cedar slice | base + probes + m5a,pchk,m5b | 3.17 | 3.23 | 172 | 41.3 | -1.4% (of probes) | 15.08 M |
| reachability | base | 8.17 / 8.21 | 8.32 | 1341 | 60.9 | | 35.44 M |
| reachability | + m5a | 8.54 / 8.74 | 8.79 | 1341 | 60.8 | -0.2% | 35.36 M |
| reachability | + pchk | 8.27 / 8.50 | 8.57 | 1341 | 60.9 | 0.0% | 35.43 M |
| reachability | + m5b | 8.21 / 8.30 | 8.38 | 1341 | 60.8 | -0.2% | 35.39 M |
| reachability | + m5a,pchk,m5b | 8.15 / 8.19 | 8.30 | 1341 | 60.8 | **-0.2%** | 35.31 M |

Reading. The three switches together take 3.8% (Init), 4.4% (Std), 1.5% (Cedar slice), 0.2% (reachability) of the instructions
and are neutral on memory. Each alone is 1.4-1.8% on the libraries and below 1% on the Cedar slice. In CPU seconds the Std rows
show the same direction (13.1 s against 13.9 - 14.6 s), the others are inside the scatter of a loaded host; the one row pair
that looks worse, reachability + m5a (two runs 0.4 s above the base at the same instruction count and the same ticks), can only
be the host: inference is 1% of that corpus. The warm in-process A/B on the JVM (`work/m5/ab.sh`: `M5Bench` alternates the two
configurations in ONE process, thread CPU of the check phase; Init, eight rounds, host load 140-170): base 8.99 / 9.11 / 7.83 /
9.09 s, with m5a,pchk,m5b 8.50 / 8.66 / 8.61 / 7.81 s: -4% in the mean, also inside the scatter.

Why so little, when MATCH answers 2.2 M of 3.2 M domains and EVAL call events fall by 18%: the domains it answers were cheap (a
flat application under the gate is one NEU probe, a tabled one is a memo hit), and the conversions that make 59% of the
records are untouched.

The two numbers the brief asked for, on Init, in-process and exact (one code state: the sandbox of 14:30Z, before the
eager-reduction rule):

| | v0 | `warm,gated,expect` | + m5a,pchk,m5b | + facts,m5a,pchk,m5b and the front cache |
|---|--:|--:|--:|--:|
| records per unit | 853.3 | 442.8 | 423.6 | 417.6 |
| run words per unit | 3,867.6 | 2,290.4 | 2,247.4 | 2,218.3 |
| INFER + VTYPE call events | 7.33 M | 5.07 M | not taken | 3.91 M |
| EVAL call events | 13.62 M | 11.29 M | not taken | 8.73 M |
| ticks | 77.83 M | 56.03 M | 52.36 M | 49.62 M |

"Records down by a third" and "INFER + VTYPE down by a third" hold against v0 (-51% and -47% with every step-1 switch, facts
and this wave's switches) and do NOT hold against step 1 (-5.7% and -23%; without `facts` -4.3% of the records): what inference
itself can still remove is bounded by the 41% of the records it makes, and the VTYPE calls that remain after `facts` are
conversions asking for the type of a neutral term.

### Measurements of wave 6, batch 2: the front cache, facts, everything together

Same method, one hold of the lock (15:18:05Z-15:26:33Z, load average 80-114), `work/m5/batch2.sh`, rows `b2-*`; image
`work/m5/dist-a3` = the sandbox of 14:50Z, which has M1's cheaper PRUNE of 14:22Z (so its base is lower than batch 1's) and is
from before the eager-reduction rule. "new" = `m5a,pchk,m5b`; "ic" = `-Drlvm.machine.m5ic=true`. Two rounds; instructions
repeat exactly. Every row: all units accepted, 0 declined.

| corpus | configuration | CPU s (two rounds) | wall s | RSS MiB | instructions G | against base | ticks |
|---|---|--:|--:|--:|--:|--:|--:|
| Init | v0 | 7.58 | 7.66 | 244 | 132.6 | | 77.83 M |
| Init | base (`warm,gated,expect`) | 7.47 / 7.48 | 7.66 / 7.65 | 225 | 105.6 | | 56.03 M |
| Init | + ic | 7.25 / 7.74 | 7.48 / 8.05 | 224 | 105.3 | -0.3% | 56.09 M |
| Init | + new | 7.06 / 7.26 | 7.10 / 7.48 | 227 | 101.7 | **-3.7%** | 52.36 M |
| Init | + new + ic | 7.10 / 7.53 | 7.22 / 7.66 | 227 | 101.2 | -4.2% | 52.30 M |
| Init | + facts | 7.54 / 7.65 | 7.73 / 7.79 | 233 | 105.5 | -0.1% | 53.91 M |
| Init | + facts + new + ic | 7.25 / 7.28 | 7.29 / 7.41 | 236 | 101.0 | -4.4% | 50.17 M |
| Std | v0 | 12.60 | 12.88 | 352 | 212.0 | | 122.25 M |
| Std | base | 12.94 / 13.01 | 13.08 / 13.07 | 325 | 177.9 | | 94.06 M |
| Std | + ic | 12.86 / 12.90 | 13.12 / 13.05 | 328 | 177.8 | -0.1% | 94.28 M |
| Std | + new | 12.52 / 12.57 | 13.21 / 12.68 | 328 | 170.2 | **-4.3%** | 87.04 M |
| Std | + new + ic | 13.12 / 13.40 | 13.25 / 13.53 | 329 | 169.7 | -4.6% | 87.00 M |
| Std | + facts | 13.06 / 13.11 | 13.24 / 13.21 | 344 | 177.8 | -0.1% | 90.15 M |
| Std | + facts + new + ic | 12.72 / 13.42 | 12.88 / 13.54 | 350 | 169.4 | -4.8% | 83.04 M |
| Cedar slice | v0 | 2.66 | 2.90 | 179 | 39.0 | | 18.10 M |
| Cedar slice | base | 2.75 / 2.94 | 2.80 / 3.00 | 173 | 39.6 | | 18.71 M |
| Cedar slice | + ic | 2.73 / 2.75 | 2.74 / 2.81 | 173 | 39.7 | +0.3% | 18.72 M |
| Cedar slice | + new | 2.76 / 2.99 | 2.79 / 3.02 | 174 | 39.0 | **-1.5%** | 18.09 M |
| Cedar slice | + new + ic | 2.80 / 2.72 | 2.90 / 2.77 | 174 | 39.0 | -1.5% | 18.08 M |
| Cedar slice | + facts | 2.97 / 2.78 | 3.11 / 3.30 | 190 | 39.5 | -0.3% | 18.21 M |
| Cedar slice | + facts + new + ic | 2.98 / 2.75 | 3.08 / 2.78 | 175 | 38.9 | -1.8% | 17.59 M |
| reachability | v0 | 8.41 | 8.48 | 1151 | 57.4 | | 34.60 M |
| reachability | base | 8.22 / 8.07 | 8.33 / 8.17 | 1144 | 59.1 | | 35.44 M |
| reachability | + ic | 8.15 / 8.15 | 8.22 / 8.25 | 1144 | 59.1 | 0.0% | 35.44 M |
| reachability | + new | 8.33 / 8.61 | 8.44 / 9.08 | 1144 | 59.0 | **-0.2%** | 35.31 M |
| reachability | + new + ic | 8.09 / 8.24 | 8.15 / 8.37 | 1145 | 59.0 | -0.2% | 35.31 M |
| reachability | + facts + new + ic | 8.02 / 8.23 | 8.19 / 8.42 | 1145 | 59.0 | -0.2% | 35.27 M |

Reading.
- **m5a,pchk,m5b: kept.** Instructions -3.7% (Init), -4.3% (Std), -1.5% (Cedar slice), -0.2% (reachability) on the newer base
  as on the older one; CPU -4.2% on Init and -3.3% on Std with both rounds below both base rounds (batch 1: Std -8%, Init level);
  Cedar slice and reachability move by less than the scatter in either direction (the reachability rows of "+ new" are above
  the base in this batch and equal to it in batch 1, and "+ new + ic", the same work, is level here). Memory: +2 to +3 MiB.
  A confirmation on a quiet host is still owed for the two small corpora.
- **The front cache: rejected (stays off, behind its property).** It answers nearly every tabling hit (4.13 M of 4.14 M on
  Init), but since M1's PRUNE hashes only the kept slots the probe it replaces is cheap: -0.3% to -0.5% of the instructions on
  the libraries, +0.3% on the Cedar slice, and no CPU gain in any pair of rows (on Std "+ new + ic" is 0.6-0.8 s ABOVE "+ new"
  in both rounds). Not worth a table in every session.
- **facts (after M4's fix of S-F1 / S-F2: static PROOF only): neutral.** -0.1% of the instructions on Init and Std, -0.3% on the
  Cedar slice, CPU inside the scatter; the derivation (0.23% of the ticks) is paid for by the PIRR / VTYPE calls it removes and
  no more. The derivation cost stays far below the 2% bound.
- Not this package's, but visible in both batches: on the Cedar slice and on reachability v0 needs FEWER instructions than
  `warm,gated,expect` since M1's PRUNE (39.0 against 39.6 G; 57.4 against 59.1 G).
- With the eager-reduction rule armed (after these batches) the machine declines the declarations that compare under
  `eagerReduce`: 155 on Init (1.1% of its ticks) but 11 on the Cedar slice that were 18% of the machine's ticks there
  (18.09 M -> 14.79 M in the final gate): what that costs through the reference engine is not in these tables.

### Experiments of wave 6 that were not kept, or not built (with their numbers)

| Experiment | Result |
|---|---|
| Expected-type return for an untabled application under `CHECK` (ISA 4.5.4 last lines) | built, removed: fires 0 times on Init (M5 above) |
| Pattern against pattern for an untabled application argument (R07 `inst_eq_inst`) | not built; probe on Init: 632,317 untabled APP arguments reach MATCH, 437,944 are answered, 382,705 of them right after their result type was built: that many of 1.32 M result builds (and as many MATCH walks) would go, about 4% of the EVAL call events |
| Lazy ARGV (the value of an argument only when a domain or the result is built) | not built; of 6.16 M argument values on Init 3.10 M are atoms and only 0.71 M of the others are read by domains alone |
| PCHK for closed terms WITH level parameters (allowed by `SharedFacts`: `lpSet` within the declaration's parameters) | probe on Init: 89,448 bits instead of 60,390, 132,040 hits instead of 117,875 (+12%); not adopted: about a quarter of a per cent of the ticks, and ISA 5.3 gives PERM only to terms without level parameter |
| Telescope domains and results evaluated without a tabling record (M2's `evalNT`, as for the body of INST under `m2a`) | probe in a private copy (`work/m5/evtop`), Init, on top of `warm,gated,expect,m5a,m2a`: records 358.8 -> 345.6 per unit (-3.7%), run words 1,767.7 -> 1,709.3 (-3.3%), ticks +0.4%. Needs `Eval.evalNT` to be callable from the package: requested of M2 (15:17Z) |
| INFER's front cache (C1-C3) | built, measured (batch 2), REJECTED: -0.3% to -0.5% instructions on the libraries, +0.3% on the Cedar slice, no CPU gain. The code stays behind `-Drlvm.machine.m5ic=true` (off) for a re-measurement on a quiet host; if that shows nothing either, it should be deleted |
| A record-free INFER memo (the front cache alone, no SUSP record) | not built: a lossy memo is not safe against a DAG that is walked as a tree (RECONCILIATION C3.10: CHECK is tabled) |

### Switches of this package (for `SWITCHES.md`)

| Switch | Guards | Gates passed | Measured |
|---|---|---|---|
| `expect` | B9: CHECK of lam / let nodes against the expected type (E1-E6) | wave 4 / integrator | E6, integrator |
| `pchk` | P1-P5: shared check facts | tutorial + bugs (alone, with step 1, with probes, with facts, audits), the four corpora, all 8,661 officially rejected tutorial mutants + 449 accepted ones through the launcher (together with `m5a`, `m5b`; one difference, caused by the official level relation against an older reference jar, present without any switch) | batches 1 and 2: kept |
| `m5a` | M1-M6: MATCH of a telescope domain against the argument's type | the same | batches 1 and 2: kept |
| `m5b` | J1-J3: joint telescope check in DECL | the same | batches 1 and 2: kept |
| `-Drlvm.machine.m5ic=true` | C1-C3: INFER's front cache (no switch; rejected by measurement, off) | tutorial + bugs, the four corpora (with the three switches) | batch 2: no gain |
| `facts` | F1-F13: derivation and accessors (the consumers are M4's) | M4's and S's gates | F13 |
