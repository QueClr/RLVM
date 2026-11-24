# NOTES-M2 - family EV: EVAL, FORCE, APPLY, INST, ARGV, DOM (owner: M2)

File: `src/main/java/rlvm/machine/Eval.java`. Tests: `M2EvalTest` (rows of ISA 4.2 / 4.3.1, fast), `M2EquivTest`
(convention and inert-loop equivalence, fast), `M2DeepTest` (stack safety; the full sizes are group `slow`), helper
`M2`. Spec: ISA 4.2, 4.3 (FORCE, APPLY, INST), 4.3.1, 4.4.3 (DOM), 2.4, 2.6, 3.1, 5.1-5.2; convention H1-H12.

## 1. What a caller must know

| Point | Rule |
|---|---|
| A result may be a suspension | `EVAL`, `APPLY`, `INST` return a value **or a SUSP**: a delayed argument that the term passes on (a variable bound to a suspension, a field taken out of a constructor application, a let variable). Call `FORCE` where the value is needed. `DOM` always returns a forced value. `FORCE` never returns a SUSP |
| `VAL` is never a SUSP | ISA 2.6. `FORCE` forces the result of its `EVAL` again if that is a suspension; a tabled `EVAL` whose result is still a suspension after `PEEK` is **not tabled** (D1) |
| Variables are `PEEK`ed | `EVAL` / `ARGV` of a variable is `PEEK(GET)`: a plain suspension whose value is known is replaced by the value before it enters a spine or a register; a LET suspension is kept (ISA 2.1, 2.8) (D8) |
| `let` | A `let` inside an evaluated term is zeta by sharing with **plain** values (D9). Only INFER makes LET suspensions (the let variable of `infer_let`); `EVAL` keeps one when it reads it through a variable, and what is built from it is OPEN |
| HOLE | An argument of `APPLY` / `INST` may be HOLE iff the binder is not read. `APPLY` raises `fault(E_HOLE)` if a HOLE argument would enter a spine (`MKAPP`); reading HOLE through a variable is `fault(E_HOLE)` (in `Runs.get`) |
| Scratch and environments | `APPLY(f, xb, n)` reads `X[xb .. xb + n)` and leaves it to the caller. Every judgment restores `xp` and `ep` when it returns a result; the saved `ep` / `xb` are payload words of its frames (H7) |
| Inert nests | The evaluation of an inert application that `ARGV` performs at once (constant head, or a variable head bound to a neutral) is an iteration of the inert-nest loop of the enclosing `EVAL`: **one tick per application node, no call event**. A whole nest of constant-headed applications is therefore one `EVAL` call event. `SW_INERT_LOOP` no longer selects anything (D12) |
| Ticks | One per `EVAL` of an application, let or projection node that is not a memo hit (inert nodes included); one per `FORCE` of a pending suspension, per `APPLY` of a closure or pending suspension, per `INST` without `COD`, per `DOM` without `M_DOM`. Leaves, closures, `MKAPP` of a neutral, `MKSUSP` and memo hits are free |
| `Eval.Ext` (`m.x2`) | `body`, `en`, `amOpen`: second results of helpers (like `m.envBase`); `ownFirst` (gated tabling); `cm` (closed-term memo), `am` (APPLY memo), `seg` (segment cache); `xsw`, `depthK` (measurement bits, section 6); statistics `hits`, `lazy`, `amHits` (only with `m.stats`) |
| Switches of this package | `SW_GATED_TABLING` (`gated`): tabling by node flags, with the depth rule (D14, D21). `SW_M2A` (`m2a`): bodies without a tabling record, projection selector (D19, D22). `SW_M2B` (`m2b`): the APPLY memo (D23). Without a switch, changing no result: the closed-term memo (D20), the argument picker (D22). Section 6 |
| Debug hooks (ISA 5.5) | scopeaudit is in `Runs.get` (M1), which every variable read of this family goes through; maskaudit before every `PRUNE` made here (closures, suspensions, tabling records), sharecheck when the walk enters an untabled node (gated tabling), memocheck on `VAL`, `COD`, `M_DOM` (D16) |

Frames (the payload is the parameter list of the continuation step):

```
EVAL[0](t, eb, en, ls)                   the entry step (spilled)
EVAL[NB](t, eb, en, ls)                  the entry step of a body evaluated without a tabling record (SW_M2A), spilled
EVAL[IN](t, lo, u, eb, en, ls)           an inert application met by ARGV, beyond the depth bound (no call event);
                                         lo = first id of the parent's export segment (-1: a root), u = depth in the nest
EVAL[HEAD](s, xb, n, eb, en, ls)         waits for the value of the head          s = tabling record, or -t (t is not
                                         tabled); X[xb - 1] = the depth of the node in its inert nest
EVAL[HEADF](s, xb, n, eb, en, ls)        waits for the forced head
EVAL[ARG](s, f, xb, n, i, eb, en, ls)    waits for argument i (an inert application); f = the forced head
EVAL[APP](s, xb)                         waits for APPLY
EVAL[BODY](s, ep0)                       waits for the body of a let chain
EVAL[PROJ](t, s)                         waits for the struct of a projection
EVAL[LET](t, s, eb, en, ls, ep0)         waits for the value bound by let node t (an inert application)
FORCE[0](s)   FORCE[1](s, ep0)   FORCE[2](s)
APPLY[0](f, xb, n)   APPLY[1](xb, n, k)   APPLY[2](xb, n, k, ep0)          k = arguments consumed
APPLY[3](xb, n, k, ep0, g, j)                                               as APPLY[2]; the step applied closure g to j
                                                                            arguments and is recorded in the APPLY memo (SW_M2B)
INST[0](pi, x)   INST[1](memo, ep0)   INST[2](memo, xb)                     memo = the record whose COD is written, or 0;
                                                                            INST[2]: the closure's block (SW_CODE)
DOM[0](v)   DOM[1](v, ep0)   DOM[2](v)
ARGV[0](t, eb, en, ls)                   never written (D7)
```

## 2. Deviations from ISA.md, with the reason

| # | Where | Deviation | Reason |
|---|---|---|---|
| D1 | 4.2 `tabled(t)`: "`VAL(s) = result`" | The result is `PEEK`ed; if it is still a SUSP (pending, or a LET suspension) it is returned and **not** stored. Evaluating the node again redoes one level; its sub-terms are tabled | ISA 2.6: `VAL` is never a SUSP, and `PEEK` / `forceFast` / every consumer rely on that. Forcing instead would make `EVAL` strict where the ISA and the official `instantiate` are lazy |
| D2 | 4.3 `FORCE`: "`call EVAL(term, frame, ls)`, `VAL <- A`" | `VAL <- FORCE(A)`: the result of the `EVAL` is forced again while it is a suspension (frame `FORCE[2]`) | same invariant; `(fun x => x) a` with an active `a` evaluates to the suspension of `a` |
| D3 | 4.3 `FORCE` | A plain suspension is evaluated by `evalAt`, the `EVAL` judgment without the tabling probe: the suspension *is* the tabling record of its term. A LET suspension is a record of its own (M1: aux 1) and goes through `eval`, so the plain record is tabled as usual | saves `PRUNE` + one probe per `FORCE`; same call events (`FORCE`, `EVAL`), same ticks |
| D4 | 4.4.3 / 2.6 `DOM`: "memo m1, else call `EVAL(domain term, frame, ls)`" | `DOM` forces the value of the domain term; `M_DOM` never holds a suspension | the domain is the type of the variable `OPEN` picks (2.8) and is compared at once by every caller; a memo word should hold a value. A top-level LET suspension loses nothing by being forced (`CONV` forces a suspension before it looks at flags; `OPEN`-ness matters for arguments inside a spine only) |
| D5 | 11 "v0: every non-leaf node tabled" | Lambda and Pi nodes have no tabling record in `EVAL`: their value is the closure, whose interning is the memo (class p: no tick, no event). Application, let and projection nodes are tabled | a SUSP record per binder node would double the probes for nothing; `INFER` (M5) keeps its own record for a lambda node |
| D6 | 4.2 `EVAL` app / 4.3.1 `APPLY` | `EVAL` forces the head **before** it builds the arguments (`EVAL[HEAD]`, `EVAL[HEADF]`), and an argument whose binder the forced closure does not read is not built at all: its scratch slot is HOLE. `APPLY` then substitutes HOLE for unread binders again (for callers that pass values) | decision 8 "never evaluated when the callee's binder is unread", 4.1 `BUNREAD`, B6. Same values; fewer suspensions made. Only the first group of leading lambdas is known before the arguments are built; later groups get built arguments and HOLE in `APPLY` |
| D7 | 13.7 "ARGV has a frame op of its own" | `ARGV` never ticks and never becomes a frame: atoms and `MKSUSP` are inline, and its one sub-judgment is `evalIn`, whose frame is `EVAL[IN]`. `K_ARGV` has its `resume` case and is never written | under H a judgment without work of its own before its only tail call needs no frame |
| D8 | 4.2 "variable -> GET" | `PEEK(GET)` | ISA 2.1 "a SUSP whose value is known is never stored where its value could be stored (PEEK, 4.2)": a variable's value is copied into spines and registers. **Consequence for M7 / tier equivalence**: operands of modes R / A / C must be `PEEK`ed when they enter a spine or a frame, or K0 = K1 holds only up to `PEEK` |
| D9 | 4.2 `let`: "env = PUSH(env, MKSUSP(value, env, LET)) ... always a SUSP (2.8)"; 2.8 "`let` never creates a variable: the bound value is always a SUSP with flag LET"; 3.4 "let -> MKSUSP(value, LET) always" | **EVAL's let is zeta by sharing without the LET flag**: the bound value is what the argument of a beta step is (`ARGV`): an atom, an inert application built at once (frame `EVAL[LET]`), or a *plain* suspension of an active term; an unread let variable gets HOLE (B6). LET suspensions are made by INFER only (M5: the let variable of `infer_let`); EVAL keeps them when it meets them (`PEEK`) | **The ISA's rule is wrong for evaluation and costs a factor of 1000 on Cedar.** Officially a let variable is a free variable only inside `infer_let` (R02 6.6; `perf/let-fold` is that case); `whnf_core` reduces a `let` of a term it evaluates by `instantiate(body, value)` (R02 7.1): no variable, no `hasFVar`. Flagging the lets of unfolded definition bodies made every value built from them OPEN, which switches off the Nat literal folding of lazy delta (R02 8.4) and the `Bool.true` shortcut (8.1 step 2); the machine then unfolded `Nat.div` / `Nat.mod` / `gcd` on literals near 2^63 by their fuel recursion. Measured on `cedar-hot-ax` unit 7008 (`BitVec.neg_toInt_eq_neg_64._proof_1_1`, an `omega` certificate whose definitions bind `let`s): 73.7 M ticks and E_MEM before, **60,569 ticks and 38 ms after** (reference engine: 42 ms). ISA 2.3 allowed OPEN to over-approximate because it "only steers" those guards; it steers them into non-termination. To be applied to K1 as well (M7: `MKSUSP .. LET` in BODY / RULE blocks) |
| D10 | 4.3.1 `APPLY`: neutral -> `MKAPP` | `fault(E_HOLE)` if a HOLE is among the arguments that go into the spine; `APPLY` of HOLE itself is `fault(E_HOLE)`, of any other non-function `fault(E_ILLTYPED)` | a HOLE inside a NEU could be compared and found "identical"; fail closed |
| D11 | 4.3 `INST` | (a) the result is `PEEK`ed and may be a suspension; `COD` is written only for a value. (b) a PII whose lambda body does not read its variable also gets HOLE and `COD` (ISA writes `PUSH(frame, x)` for PII; 2.6 lists `COD` for PI and PII). (c) `INST` / `DOM` of a record of another kind: `fault(E_INTERNAL)` | (a) D1; (b) the type of a body that does not mention the variable cannot depend on it; (c) fail closed |
| D12 | 8.2 "inert evaluation is one EVAL frame looping over scratch"; `Op.SW_INERT_LOOP` | **Wave 6: the loop over scratch is removed; `SW_INERT_LOOP` selects nothing** (the nest runs by host recursion, beyond the depth bound as the frame `EVAL[IN]`; the 913,500-element list literal and the 1,000,000-deep nests of `M2DeepTest` pass on a 512 KiB stack under D = 64 and 0). It was measured twice as not faster (section 5), and every change of the tabling gate had to be written twice. Before: two implementations of the inert-nest loop with **identical observables** (records and runs word by word, memo words, ticks, call events): default = host recursion (`evalIn`; beyond the depth bound the frame `EVAL[IN]`), `SW_INERT_LOOP` = one loop over scratch (`inertLoop`). In both the nested inert applications cost a tick each and are not call events | skeleton note "behind SW_INERT_LOOP only if results stay identical"; convention H8 item 3 leaves the choice to a per-platform measurement. `M2EquivTest` compares the two word by word |
| D13 | H4 `spillN` | `EVAL[IN]` is written with `m.fuel++; m.sig4(Op.SUSP, K_EVAL_IN, ..)` instead of `spill4` | `spillN` also takes back a call event, and an inner inert evaluation has none |
| D14 | 3.1 `tabled(t)` (`SW_GATED_TABLING`, step 1, off) | (`Eval.gate`; with the depth rule of D21.) SHARED / BODY / INVARIANT, or an export id below the unit's first one, or a generated id. Overlay nodes go by their flags only (the decoded block of *another* unit's theorem is not recognised as foreign). **In addition a flat inert application** (constant head; every argument a variable, constant, sort or literal) **is never tabled** under the switch, whatever its flags | (a) the machine cannot tell whose block an overlay node belongs to; hash-consed overlay nodes are SHARED and decoded blocks carry their segment's flags, so sharing is never under-approximated. (b) evaluating a flat application is one `MKAPP` of atoms: cheaper than its tabling probe (`PRUNE` + SUSP probe; on a miss a frame run and a 32-byte record), and there is no sub-term whose walk a memo could save, so no DAG is walked as a tree. On Init through the launcher: 10% fewer records and 7% fewer run words than gating by flags alone, 5% more ticks (re-evaluations are ticks); CPU time could not be measured (host load 170+) |
| D15 | 2.4 mask bit 63 | A binder whose de Bruijn index in the body is 63 or more counts as read when bit 63 of the body's mask is set (no HOLE) | bits 0..62 are per index; bit 63 only says "some index >= 63" |
| D16 | 5.5 `memocheck` | `VAL` (at `FORCE` and at every tabled `EVAL` hit) and `COD` are checked **up to PEEK**: `Eval.checkVal` / `checkCod` re-compute with the entry bypassed and accept a result that `Eval.samePeekedSlow` finds structurally equal with known `VAL`s followed; `M_DOM` uses M6's one-line check (id-stable). A LET suspension's `VAL` is not checked separately (it is the plain record's) | the re-evaluation meets suspensions whose value became known after the entry was written (`PEEK`, `MKSUSP` "known VAL") and builds `NEU(c, [VAL a])` where the first run built `NEU(c, [SUSP a])`: the same value, another id. Test `M2EquivTest.aMemoCheckOfValIsUpToPeek` |
| D17 | 2.6 / 4.3.1 `CODE` | Staging step 4, behind `SW_CODE` (off in v0): `APPLY` of a LAM closure and `INST` of a PI closure look the block of the binder term up once (`Code.closureBlock`, memo word `CODE`, `NOCODE` if none) and call `Code.callClosure` for `j = min(leading lambdas, arguments, arity of the block)` binders if the block has that entry; frame `INST[2](memo, xb)` for the scratch word of the argument. With the switch off the memo word is never read or written | M7's request of 08:58Z; tested on M7's sub-blocks of `Nat.add` and of two Pi-making definitions (`M2CodeTest`: same value ids as the walk, call trace `APPLY, ACT` / `INST, ACT`, under D = 64 and 0) |
| D18 | results that are no value | `EVAL` / `FORCE` raise `fault(E_HOLE)` / `fault(E_INTERNAL)` when a result is HOLE / NONE (a field of a constructor application that some caller built with a HOLE) instead of storing it in `VAL` | fail closed |
| D19 | 3.1 `tabled(t)`: "BODY(t): body of a binder"; 5.1 (`SW_M2A`, off) | **The body of a beta step (`APPLY`) and of `INST` of a PI closure is evaluated without a tabling record**, whatever its flags (`evalNT`, frame `EVAL[NB]`). The body's own inert arguments keep the gate of D14 | A body is entered once per `APPLY` / `INST`: its key `SUSP(body, PRUNE(frame + arguments))` is new for every new argument tuple, so the probe is a `PRUNE`, a frame run and a 32-byte record for a memo that is asked again only when the same closure meets the same read arguments (measured hit rate at this site, step 1: Init 16 %, Cedar slice 23 %, reachability 0.8 %; two thirds of Init's hits are projection bodies, which cost less than the probe). It was 39 % of all tabling probes on Init, 30 % on the Cedar slice and **81 % on reachability**. No DAG is walked as a tree by this: `EVAL` descends strictly only into the head, the struct of a projection, a let body and the inert arguments of an inert application; a body is reached through `APPLY` (one per application node evaluated, per forced suspension, per reduction step of a value, each of which is memoised by identity), never through the fan-out of an inert nest, so the number of body evaluations is bounded by the number of beta steps, with and without the record |
| D20 | 4.2 `KVAL` "closed-term cache"; 5.1 (no switch: no result changes) | **Closed-term memo** `Ext.cm`: 4,096 cells `(term, epoch, value)` direct-mapped by the term id, read by the inline part of `EVAL` / `ARGV` for a closed application, let or projection under ls' = 0 (no level parameter, or the identity substitution) and written where `VAL` is written (`done`). Where the node is tabled the entry is a copy of `VAL(SUSP(t, EMPTY, 0))`: the memo saves the probe and changes no id, record, run or tick (`M2EconomyTest.theClosedMemoChangesNothing`). Where the node is not tabled (gated: flat applications, untabled nests) it is the only memo, and saves the evaluation. Bypassed under the debug modes that own a memo hit or a variable read | the task's "direct-mapped memo indexed by term id for closed terms under the identity level substitution"; closed nodes are 11 % (Init) to 17 % (Cedar slice) of the tabling probes of v0, 80-94 % of them hits |
| D21 | 3.1 "the test must never under-approximate sharing" (`SW_GATED_TABLING`) | **Depth rule**: an inert application that lies `depthK` = 4 or more inert levels below the root of its nest is tabled whatever its flags (`gate`, argument `u`; the depth travels in `X[xb - 1]` and in `EVAL[IN]`) | **Gating by flags alone is quadratic on an unshared dependent nest.** `INFER` evaluates the argument of every dependent application (`TWALK`: `ARGV(t_i)`), bottom-up; each of these evaluations walked the whole untabled nest below it again. Measured on `c (c (.. x))` with `c : (x : T) -> G x`, 4,000 applications under one binder (`work/m2/gen/depnest.py`; resource `m2/depnest-open-300.ndjson`): v0 44,030 ticks, `gated` as published in wave 4 **8,038,031 ticks**, `gated` with the rule 60,017. `sharecheck` reports such walks (it fails on 196 units of init-prelude under `gated`, before and after this wave: an untabled node is legitimately visited once per evaluation root above it); with the rule each node is visited at most `depthK` + 1 times. On the corpora the rule costs nothing measurable (Cedar slice: +0.5 % records, -6 % ticks together with D20) |
| D22 | 4.3.1 `APPLY` (argument picker: no switch; projection selector: `SW_M2A`) | A beta step whose body is one of the variables it binds returns that argument, PEEKed (`pick`: what `EVAL` of the variable returns; HOLE is `fault(E_HOLE)`), without building the environment: no record, run or tick differs. With `SW_M2A` a body that is a projection of a bound variable is `MKPROJ` of the argument (`betaProj`): no environment, no tick, no call event of the body | selector patterns of the task (item 2). Bodies at `APPLY` on Init: projection of a variable 10.6 %, argument 3.1 % (reachability 4.8 % / 5.6 %) |
| D23 | 4.3.1 `APPLY` (`SW_M2B`, off) | **APPLY memo** `Ext.am`: 2,048 cells `(closure, a0, a1, a2, epoch, j, value)`, direct-mapped and lossy, for a beta step of j <= 3 binders; the key holds the arguments as `bind` binds them (HOLE for a binder the body does not read), so it is the key of the body's tabling record without the `PRUNE`. Written when the step completes (frame `APPLY[3]` on the worklist path, so D = 0 and D = 64 fill it alike) | gives back the memo that D19 removes, for 2 probes of 32 bytes instead of a `PRUNE` and two records: repeated steps are 17 % (Init), 23 % (Cedar slice), 1.2 % (reachability) of all beta steps |

## 3. Why the two inert-nest implementations were the same machine (historic: the loop was removed in wave 6, D12)

Per node of a nest both do, in this order: tabling probe (`PRUNE`, SUSP record; hit: the value), tick, spine into
scratch, head value (`KCONST`, or the variable's neutral), the arguments left to right (atom / `MKSUSP` of an active
term / the nested inert application, recursively), `MKAPP`, `VAL := result`. Records and runs are appended in that
order, so the session arrays are equal word by word. Scratch layout differs (recursion: the arguments only; loop: a
5-word header `[prev, s, f, n, i]` per node) and is not observable.

## 4. Open points

- **Gated tabling** (D14) and the sharecheck hook are implemented and smoke-tested (`gatedTablingGivesTheSameValues`);
  they are step 1 and have not been run through a checker on a corpus (the EV workload of section 5 runs with them).
- **`K_ARGV`** stays in `Op` and in the `resume` switch although no such frame is written (D7).
- **Callers and suspensions**: `Infer` opens binders with `OPEN(EVAL(A))`, which may be a suspension, while `CBIND` /
  `ETA` use `DOM` (forced): the same binder can get two different variables (never a wrong answer; advice sent to M5).
- **Tier equivalence** (M7 / M8, step 4): K1 operands must be `PEEK`ed where K0 does (D8), or the comparison is up to
  `PEEK` (`Eval.samePeekedSlow` is that relation).
- The depth of `samePeekedSlow` is 24; beyond it a memo check is inconclusive (accepted).

## 5. Measurements (not gates)

Host: the project machine (aarch64, 20 cores), **shared with 15 other agents running builds and tests**, JVM with the
Graal JIT (`-Xss2m -Xmx6g -XX:+UseSerialGC`, the module's surefire settings), no Docker, no arena limits. CPU time of
the worker thread (`ThreadMXBean`), minimum over the timed rounds after one warm-up round. Run-to-run spread on this
host was 10-15%, so differences below that are not established.

`M2PerfTest` on `work/exports/init.ndjson` (59,979 constants): for every constant `EVAL(type)` and the walk of its Pi
telescope (`DOM`, `OPEN`, `INST`, `FORCE` per binder); for every definition `EVAL(value)` and the walk of its lambda
telescope (`DOM`, `OPEN`, `APPLY`); then every suspension reachable from the body's value is forced. Sessions reset at
16 MiB. No environment pass (so no projection-of-constructor and no `Nat.succ` folding in this run).

| Configuration | CPU time | Ticks | Frames written (per call event) | Records / run words made |
|---|--:|--:|--:|--:|
| D = 64 (v0 default) | 180 ms | 746,071 | 198 (0.00032) | 716,442 / 1,809,042 |
| D = unbounded | 202 ms | 746,071 | 0 | the same |
| D = 64, `SW_INERT_LOOP` | 192 ms | 746,071 | 0 | the same |
| D = 0 (worklist machine) | 224 ms | 746,071 | 392,894 (0.63) | the same |
| D = 64, `SW_GATED_TABLING` | 177 ms | 754,647 | 198 | 677,864 / 1,770,339 |
| D = 64, gated + inert loop | 189 ms | 754,647 | 0 | the same |

A first run without the forcing phase gave 160 / 158 / 165 / 186 / 181 / 166 ms for the same six rows (742,097 ticks).
Call events in the D = 64 row: EVAL 78,767, FORCE 1,293, APPLY 55,465, INST 227,922, DOM 260,799; tabled-EVAL hits
206,273; 291,748 binders opened; 730,028 values visited by the forcing phase, of which 1,293 were pending suspensions.

Readings: (1) about 240 ns of CPU per tick including session resets, `OPEN` / `CLOSE` and 716 k new records (at the
55 ns per new record of ISA 0.3 that alone is 40 ms); (2) frames written per call event at D = 64 are 0.03%, below the
0.1% bound of convention H12; (3) D = 0 costs about +25% on this workload, D = 64 against unbounded is inside the
noise; (4) the inert-nest loop is not faster than bounded recursion here (inside the noise), as the stepper spike
found, so it stays off; (5) on Init's types and definition bodies under variables almost every argument is an atom
or inert: 1,293 pending suspensions among 730 k values; (6) gated tabling saves 5% of the records on this workload
(its nodes are mostly foreign to the unit under evaluation or flagged).

The whole machine on Init through the launcher (`work/dist/bin/rlvm-jvm -X engine=machine --threads 1
work/exports/init.ndjson`, HotSpot C2, published jar of 09:09Z, v0: a session per declaration): 57,938 declarations
accepted, 0 declined; 93.57 M ticks, 79.17 M call events, of which **46.5 M (59%) are family EV**: EVAL 13.69 M,
INST 13.51 M, APPLY 11.89 M, DOM 6.93 M, FORCE 0.55 M. 54.68 M records and 241.4 M run words were made. Worker CPU
time 12.7 s in the quietest run (host load about 100) and 16-20 s later (load 170-190): not a usable number, only
an order of magnitude (reference engine 54 s, gruszka 2.8 s). A JFR profile of that run (441 samples of the worker
thread; self time): `PRUNE` 21% (`Runs.pruneSlow` 12%, `pruneNear` 8%), `Machine.addRecordAt` 12.5%, `Values.rec`
7.5%, `Eval.eval` + `atom` + `node` + `app` 17%, `Values.neu` 4.5%, `Machine.reset` 3.6%. About two thirds of the
`PRUNE` time is under `Eval.table` (the tabling probe of every application, let and projection node), the rest
under closure construction.

Tabling variants on the same run (records / run words / ticks; all 57,938 accepted):

| Switches | Records | Run words | Ticks |
|---|--:|--:|--:|
| none (v0: every node tabled) | 54.68 M | 241.4 M | 93.57 M |
| `gated` by the node flags alone (before D14's flat rule) | 53.34 M | 239.6 M | 93.96 M |
| `gated` with the flat rule (published) | 48.17 M | 222.3 M | 98.38 M |
| `inert` | 54.68 M | 241.4 M | 93.57 M (frames written 168,809 instead of 185,128) |

Reading: the node flags alone gate little on Init because most evaluated nodes are foreign to the unit being
checked (types and bodies of earlier constants); what remains to be saved is in the `PRUNE` itself (M1: dense
frames, the OPEN-bit loads) and in sessions that survive a declaration (M6, step 1).

**Cedar level** (owner note 9): `work/workloads/slices/cedar-hot-ax.ndjson` through the launcher (`-X engine=machine
--threads 1`, v0 switches, C2, host load about 130), before and after the let fix of D9:

| Jar | Machine accepted / declined | Worker CPU | Ticks | Records | Run words | Reference re-checks |
|---|--:|--:|--:|--:|--:|--:|
| 09:59Z (every let value a LET suspension, as ISA 2.8 / 4.2 say) | 8,059 / 10 (E_MEM) | 168 s | 786 M | 554 M | 2.9 G | 5 units, 348,591 steps |
| 10:26Z (EVAL's let = zeta by sharing, plain values) | **8,064 / 0** | **4.63 s** | 18.1 M | 10.4 M | 103 M | 0 |

For scale: nano-lean 3.4 s, the official checker 20 s on this slice (R13 section 6). Five `omega` certificates carried
the first row: each ran 73.7 M ticks into E_MEM where the reference engine needs about 70,000 steps. After the fix the
call events are INFER 1.77 M, CONV 1.38 M, CBIND 0.97 M, EVAL 2.41 M (hit rate 66%), APPLY 2.26 M, INST 1.98 M, DOM
2.57 M, DELTA 0.52 M, LSTEP 0.32 M.

`work/exports/reachability.ndjson` (closed evaluation) with the same jar and settings: 4,224 engine checks accepted,
0 declined; worker CPU 7.3 s; 34.6 M ticks; EVAL 8.35 M, APPLY 8.20 M, FORCE 0.51 M, INST 0.13 M, DOM 0.17 M;
22.9 M records, 138 M run words, 356,561 frames written.

Stack safety (`M2DeepTest`, group `slow`, 38 s for all five): a 913,500-element list literal (913,501 ticks, one call
event), 1,000,000-deep application, binder and mixed nests and a 1,000,000-long chain of suspensions, each on a
512 KiB thread stack under D = 64 and D = 0, with and without the inert loop: identical value ids, records, runs
and ticks in the four configurations.
