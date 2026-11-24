# rlvm-machine - work packages M1..M8

Written by the skeleton agent (workflow `rlvm-wave4-machine`, 2026-11-24). It adjusts ISA 13.6 to the host
convention that was decided after ISA revision 2 (`docs/design/review/stepper-convention.md`, rules
H1-H12) and to the sandbox workflow. Order of authority: `OWNER-NOTES.md` > `RECONCILIATION.md` > `ISA.md`
/ `stepper-convention.md` > this file. Deviations of the skeleton from the ISA are listed in
`NOTES-M6.md`, section "Skeleton", and in the change log of ISA section 15.

Contents: 1 what exists - 2 the sandbox workflow - 3 how a judgment is written - 4 shared definitions -
5 the staging rule - 6 testing and the differential test - 7 the packages - 8 who calls whom.

## 1. What exists

Module `rlvm-machine`, package `rlvm.machine`, engine provider `machine`. Everything is package-private
static code over one `Machine` object per session; tests live in the same package.

| Kind | Files | State |
|---|---|---|
| shared definitions (frozen) | `Layout`, `Op`, `Hash` | complete |
| machine state and control | `Machine` (arrays and registers of ISA section 1, growth, `reset()` in O(used), footprint, `sigN` / `spillN` for payloads 0..12, `sigX`, `drive`, ticks, polls, `fail` / `fault`, `tryEnter` / `saveTry`, `beginDecl` / `abandon`), `Frames` (`k_try`, `k_erf`, family CTL), `Scope` (`OPEN` / `CLOSE`) | complete, tested (`ControlTest`, `MachineStateTest`) |
| facade | `Ops`, `MachineProvider`, `MachineEngine`, `MachineSession`, `MachineOps`, `Debug`, `Show` | `Ops` delegates; engine and session work and decline everything; `MachineOps` and the audits of `Debug` are stubs |
| stubs | every other class of ISA 13.2 | entry steps and operations with their final signatures; bodies throw `UnsupportedOperationException("Mk: Class.method is not implemented yet")`; the state-0 cases of every `resume` switch are in place |
| delivered implemented inside package files | `Values.mkFVar / kind / flags / a / b / c / aux / memo / setMemo`, `Runs.len / arg / fget / get`, all of `Scope` | yours to keep or replace (M1); signatures frozen |
| tests | `Fixture` (+ `Sexp`, `Prelude`), `Toy`, `TestTables`, `ControlTest`, `MachineStateTest`, `FixtureTest`, `StepSizeTest`; stubs `Quote`, `TierEq`, `Diff` | 38 tests green |

End to end today: `RLVM_EXTRA_CP=<rlvm-machine jar> work/dist/bin/rlvm-jvm -X engine=machine FILE` runs,
the machine declines every unit and the driver falls back to the reference kernel (section 6).

## 2. The sandbox workflow (mandatory)

The judgments are mutually recursive, so the packages cannot be Maven sub-modules. Each package works in
a private copy of the module and publishes only the files it owns. **Never edit the main tree
`rlvm-machine/` directly, never run Maven on it** (the publish command does, under the lock).

```
scripts/machine-sandbox.sh init M3              # once: work/sandbox/M3/rlvm-machine (+ a copy of the parent pom)
scripts/machine-sandbox.sh test M3              # compile + test your copy   (extra Maven arguments allowed:
scripts/machine-sandbox.sh test M3 -Dtest='M3*' #   -Dtest=..., -Dgroups=fast, -Dgroups=slow -DexcludedGroups=)
scripts/machine-sandbox.sh pull M3              # refresh every file you do NOT own from the main tree
scripts/machine-sandbox.sh publish M3           # under work/sandbox/.lock: your owned files -> main tree,
                                                #   compile main, run its fast tests; failure = main restored,
                                                #   exit 1; success = jar installed, line in work/status/machine-M3.log
scripts/machine-sandbox.sh status               # who published what and when; who holds the lock
scripts/machine-sandbox.sh changed M3           # your owned files that differ from the main tree
scripts/machine-sandbox.sh claim M3 src/main/java/rlvm/machine/WhnfLoop.java    # a NEW file becomes yours
scripts/machine-sandbox.sh owner PATH           # who owns a file
```

Rules:

1. **Ownership** is `rlvm-machine/OWNERS.tsv` (pattern -> package, first match wins). By naming convention
   you also own the test classes `src/test/java/rlvm/machine/M<k>*.java` (e.g. `M3WhnfTest.java`), the
   resources `src/test/resources/m<k>/`, and `NOTES-M<k>.md`. A new main class needs `claim` first. Files
   nobody owns (`OWNERS.tsv`, `OWNERSHIP.md`, this file) are never published from a sandbox.
2. **Publish early and often**: as soon as a coherent part compiles in the main tree and its fast tests are
   green. Before a publish: `pull`, `test -Dgroups=fast`. Other packages see your code after their next
   `pull`. A publish that fails changed nothing: read the output it prints, `pull`, fix, try again.
3. **The fast group** is what guards the main tree: every test class or method tagged
   `@Tag("fast")` (JUnit 5), from all packages. Keep your fast tests below about 5 s in total and make
   them independent of files outside the module, except through `Fixture.exists(..)` + `assumeTrue`.
   Tag corpus runs (arena directories, Init) `@Tag("slow")`: they run with
   `test Mk -Dgroups=slow -DexcludedGroups=`. Untagged tests run in the default `test` but not at publish.
   A fast test must not assert that *another* package's stub still throws.
4. **Foreign files in your copy** may be edited as a temporary local fake (for example a two-line body for
   a stub you need today); they are never published and the next `pull` replaces them. Say in your status
   log which fakes you run with.
5. **Requests**: a change to a file you do not own - a signature, a constant, a `Fixture` method - is a
   line `M3 -> M6: request` in `work/status/requests.log`; log progress in `work/status/machine-M3.log`
   (the publish command appends there too); read the logs of the packages you depend on every few
   minutes. Do not wait idle for more than about 15 minutes in total: work around it locally.
6. Deviations from `ISA.md` go into your `NOTES-M<k>.md` with the reason.
7. Leave no background process; do not run git commands that modify the repository.

Self-test of the script: `scripts/machine-sandbox-selftest.sh` (two fake packages publishing concurrently,
rollback of a publish that does not compile or whose fast tests fail; 18 checks).

## 3. How a judgment is written (normative)

`stepper-convention.md` sections 2 and 3 are the rules; `src/test/java/rlvm/machine/Toy.java` is the
smallest complete example (recursion, two sub-calls, a memo, a loop, binders, TRY segments, a 12-word
frame) and `ControlTest` runs it under D = 0, 1, 2, 7, 64 and unbounded. The template, with the two
lines that differ from the convention document (NOTES-M6 S3-S5):

```java
/** CONV(a, b) (ISA 4.4.1; official is_def_eq, R02 8.1). Entry frame: CONV[0](a, b). */
static int conv(Machine m, int a, int b, int d) {
    if (a == b) return Layout.T;                                    // 1. inline answers (identity, flag, memo,
    int s = Pairs.status(m, a, b);  ...                             //    cache): return at once. No tick, no
                                                                    //    event, no frame.
    if ((--m.fuel & Op.POLL_MASK) == Op.POLL_MASK && m.tickSlow()) return m.sig;      // 2. TICK
    if (m.stats) m.call(Op.CONV);                                                     // 2b. call event
    if (d > m.hostDepth) return m.spill2(Op.K_CONV, a, b);          // 3. too deep: become the frame CONV[0](a, b)
    int da = Eval.dom(m, a, d + 1);                                 // 4. body: call DOM(a) => 1
    return da >= Op.SIG ? m.sig2(da, K_CONV_1, a, b) : k_conv1(m, a, b, da, d);
}
static final int K_CONV_1 = Op.K_CONV | 1;                          // your own state keys, constants in your file
static int k_conv1(Machine m, int a, int b, int r, int d) { ... }   // continuation step: frame CONV[1](a, b); r = dom a
```

- **Entry step** `J(m, operands.., d)`: lines 1, 2, 2b, 3, then the body, in this order. Leaf cases that
  need no sub-judgment return before line 2 or between 2b and 3 and can never be frames. The operands of
  the entry step are the payload of `J[0]` and the arguments of its `resume` case (already in your stub).
- **Continuation step** `k_Jk(m, payload.., r, d)`: the payload words of frame `J[k]`, then the result
  register `r`, then `d`. The prefix `k_` is mandatory (inlining flags, `StepSizeTest`).
- **Calling a sub-judgment** `K`: `int r = K(m, x.., d + 1); return r >= Op.SIG ? m.sigN(r, K_J_k, live..)
  : k_Jk(m, live.., r, d);` where `live..` is exactly the parameter list of `k_Jk`. Every call of a
  judgment is followed by this test, or is a tail return (`return K(m, x.., d + 1);`, only if the
  judgment owns no open binder and no scratch at that point and returns K's result unchanged).
- **Loops** (spines, telescopes, the WHNF and LAZYD loops): the loop is a method whose parameters include
  the loop variables; inside it `if (r >= Op.SIG) return m.sigN(r, K_J_k, live.., i);`; the continuation
  step finishes the interrupted iteration and calls the loop method with the next index (`Toy.loopFrom` /
  `k_loop1`). One tick per iteration in WHNF and LAZYD (the TICK line, without 2b and 3).
- **Add one case per continuation step to your `resume` switch** (`case K_CONV_1: return k_conv1(m, w[p],
  w[p + 1], r, 0);`). A key without a case is `fault(E_INTERNAL)` at run time - and only when a frame is
  actually written, i.e. on deep terms. Test every judgment with `Fixture.hostDepth(0)` as well: then
  every non-leaf call is a suspension and every `sigN` / `resume` case runs.
- **Results and signals**: a step returns a result (value ref, level id, `Layout.T / F / U / UNDEF`,
  `Layout.NONE`) or a signal `Op.SUSP / ABORT / FATAL` (all `>= Op.SIG`). `fail(E)` is `return m.fail(E);`
  (kernel error or recoverable limit), `fault(E)` is `return m.fault(E);`. No Java exception on these
  paths. An operation of the library that can fail (`Values.mkApp`, `Scope.open`, `Runs.get`, ...) returns
  a signal too: test `>= Op.SIG` and return it (it is never SUSP).
- **Machine stacks** (H7): scratch `X` / `xp`, environments `es` / `ep`, registers `RS` / `rp`, binders
  `SC` / `ssp`, the mode `ER`. A step that returns a *result* has restored what its judgment pushed
  (I-STACKS, I-SCOPE); a step that returns SUSP leaves it in place (keep base indices such as `xb` in the
  payload); a step that returns ABORT or FATAL restores nothing - the TRY or the DECL driver does. Check
  capacity with `m.ensureScratch(n)`, `m.ensureEnv(n)`, `m.ensureRegs(n)` (false -> `fault(E_MEM)`).
  **Never keep a Java reference to `m.vh`, `m.rs`, `m.es`, `m.X` across a call that can allocate**: the
  arrays grow by reallocation; re-read the field.
- **Memos** (I-MEMO, ISA 5.4): written only by the step that completes the judgment. Nothing computed
  under a budget that ran out is in a memo, because a signal never reaches that step.
- **A segment under a budget** (step 5 only; not in v0): the template in the class comment of `Frames`.
  `m.tryEnter(budget)`, the call, then `m.saveTry(..)` on SUSP or `Frames.k_try(..)`; the result is T, F
  or U; a fatal fault passes through.
- **Two results** (LAZYD): one is returned, the others are left in `m.A2`, `m.A3`, which the continuation
  step of the caller reads before it calls anything.
- **Sizes** (H10, enforced by `StepSizeTest` for every judgment class): a continuation step at most 150
  bytecodes, every other method at most 325; exempt are `resume*` methods (at most 50 cases each: split
  by op) and methods whose name ends in `Slow` (out-of-line slow parts), both at most 2,500.
- **No allocation, boxing, `java.util` collection, lambda, stream or exception on a normal path**; values,
  runs, frames and tables are `int` ids in the flat arrays of `Machine`.
- **Every judgment and operation carries a comment citing the ISA section and the official rule** (the
  stubs already do; keep the comment when you fill the body).

## 4. Shared definitions and how they change

- `Layout`, `Op`, `Hash`, `Machine`, `Frames`, the signatures in `Ops`, `Fixture` and the other shared
  test files belong to M6 and are frozen: request a change (`Mk -> M6: ...`), do not work around a missing
  constant with a private copy of its value.
- The **entry-step signatures** in the stubs are the cross-package interface. You may add methods to your
  own classes at any time (other packages call them after your publish); changing an existing signature
  needs a request to its callers through the log.
- **Your own session state** (caches, counters, scratch) goes into the `Ext` class of your main file
  (`Values.Ext` = `m.x1`, `Eval.Ext` = `m.x2`, `Reduce.Ext` = `m.x3`, `Conv.Ext` = `m.x4`, `Infer.Ext` =
  `m.x5`, `Code.Ext` = `m.x7`): fields plus the hooks `reset(m)` (O(used)!), `beginDecl(m)`, `bytes()`
  (bytes in use, added to the footprint).
- Table formats: `vtab` / `rtab` words, run headers, pair entries, direct-mapped cache cells are in
  `Layout`; the hash of a record, a NEU, a run, a pair and a cache cell is in `Hash`. `Machine` appends
  (`addRecordAt`, `addRun`, `putRunAt`, `addPairAt`), grows and resets; the owner probes.
  `Machine.internRecord` and `TestTables` (test tree) are reference probes.
- Names that differ from the ISA text: the run-slab pointer is `m.rsp` (`rp` is the register stack);
  `Op.CHECKED` is INFER's check mode (`CHECK` is the op); `Op.OFFSET_F` / `STRX_F` / `ENSORT_F` /
  `ENPI_F` / `ISPROP_F` / `MATCH_F` are the frame ops of judgments that are also code instructions.

## 5. The staging rule

**v0 first.** v0 = generic tier (K0), the complete official procedure (ISA 6.3 rule 3), every non-leaf
node tabled, session = one declaration, only the exact shortcuts B6 and B10. Everything later is behind a
switch of `Op.SW_*` (`m.on(Op.SW_FACTS)`), and **every switch is off by default**; nothing of a later
stage may change what v0 computes when its switch is off, and strict mode (`m.strict`) forces all of
them off.

| Switch | Stage (ISA section 11) | Packages |
|---|---|---|
| `SW_WARM`, `SW_GATED_TABLING`, `SW_PCHK`, `SW_EXPECT` | 1: sessions that survive a declaration, tabling by node flags, shared check facts, B9 | M6, M2, M5 |
| `SW_FACTS` | 2: fact records, plans (B3, B4), static PROOF (B7), ETAS mask | M5, M4, M7 (`Cong`) |
| `SW_TELE` | 3: K1 TELE, MATCH (B5), IC | M7, M5 |
| `SW_CODE`, `SW_CASETREES` | 4: K1 BODY / RULE, LEAF, case trees (B8) | M7, M3 |
| `SW_PROBES` | 5: TRY, B1, B2, B12, pass B, UNDEC | M4 |
| `SW_K2` | later | - |
| `SW_INERT_LOOP` | tuning of EVAL (H8 item 3), results identical | M2 |

Run with switches: `-Drlvm.machine.switches=warm,gated` (launcher: `JAVA_TOOL_OPTIONS`; tests:
`Fixture.switches("warm,gated")`). Debug modes likewise: `-Drlvm.machine.debug=memocheck,trace`,
`Fixture.debug(..)`. Gates: **v0** = diff-clean against the reference engine on the tutorial and `bugs/`
tests through `-X engine=machine`, then on all 230 tests, then Init. Correctness first: a wrong verdict
is worse than anything else; when in doubt DECLINE (`m.fault(E_INTERNAL)` or a recoverable limit), never
guess.

## 6. Testing and the differential test against the reference engine

In a unit test (your `M<k>*Test.java`, same package):

```java
Fixture f = Fixture.prelude();                        // Nat, Bool, Eq, Prod, Unit, True, And, Or, Exists, Sig, List,
                                                      //   Quot, Nat.add, id, axioms ... (Prelude.java); or Fixture.empty()
f.inductive("W", "W.mk : (-> Nat W)");                // declarations BEFORE the first use; recursors are generated
f.def("three", "Nat", "(Nat.succ two)", Fixture.regular(2));       //   by rlvm-inductive with the reference kernel
int x = f.fvar("n", "Nat");                           // a free variable of the fixture's context (OPEN)
int t = f.term("(Nat.add (Nat.succ n) (lit 2))");     // Sexp term language (Sexp.java); n is a loose bound variable
int v = f.eval(t);  int w = f.whnf(v);  f.conv(v, w);  f.inferCheck(t);  f.vtype(v);  f.apply(fn, a, b);
f.expectFail(MachineErrors.E_APP_MISMATCH, () -> f.inferCheck(bad));
f.hostDepth(0);                                       // every frame materialised: run each row a second time
f.record(v); f.run2slots(r); f.pair(a, b); f.show(v); f.showTerm(t); Show.record(f.m, v); Show.worklist(f.m);
```

A judgment that ends with a signal throws `Fixture.MachineFailure` (code + signal); `f.m` is the machine;
`f.session` the `MachineSession`. Per judgment against the oracle: build the same term for
`new RefKernel(f.ctx, EngineConfig.defaults())` and compare through `Quote` (M8) or by `isDefEq`.

Whole files, in-process (reference engine and machine side by side, per declaration):

```java
Fixture g = Fixture.load("work/arena-site/tests/good/tutorial/001_basicDef.ndjson");   // real loader + environment pass
Fixture.DiffReport r = g.diffAgainstReference();      // agree / declined / SOUNDNESS / COMPLETENESS, one line each
Fixture.Verdict v = g.checkAll();                     // the machine alone, verdict rule of the driver
g.referenceVerdict();  g.frameTrace(cid);  g.close();
```

The gate value is `r.clean()` (no difference at all); `r.soundness + r.completeness` must be 0 at every
commit, also in the middle of the work (a decline is honest, a wrong verdict is not). `rlvm.testkit`
(`DiffEngine`, `OfficialVerdicts`, the mutant corpus) is on the test class path.

Through the launcher (the gate's form; the distribution does not contain the machine jar yet):

```
scripts/dev.sh dist                                             # once: work/dist/bin/rlvm-jvm from the published jars
JAR=.toolchain/m2/rlvm/rlvm-machine/0.1.0-SNAPSHOT/rlvm-machine-0.1.0-SNAPSHOT.jar      # installed by every publish
RLVM_EXTRA_CP=$JAR work/dist/bin/rlvm-jvm -X engine=machine -X stats-file=/tmp/s.json FILE.ndjson; echo $?
```

**The exit status alone proves nothing**: the driver re-checks a unit the machine declines with the
reference kernel, so the verdict is right even when the machine does nothing. Read the statistics:
`values.engine.primary.machine.declined` and `units.declined` must be 0 and
`values.engine.primary.machine.accepted` must equal the number of checked units. Always pass
`-X engine=machine` explicitly: with the jar on the class path and no `-X engine` the launcher picks the
machine by default. Official verdicts: `~/.elan/toolchains/leanprover--lean4---v4.36.0/bin/leanchecker
--from-export FILE`, cached in `work/oracle/`. CPU time, wall clock, RSS and instructions under the
arena's limits: `bench/README.md`, `bench/track/` (CPU time is the metric; report it first).

## 7. The packages

Common to all: read ISA sections 0-2 and 13-14, the convention document, and the sections named below;
`docs/research/02-kernel-typechecker.md` (R02) is the official rule for every judgment and `rlvm-ref`
(`RefKernel.java`, README) is the same rule already implemented over terms - read it for exact behaviour.

### M1 - values and runs with interning; levels; literals; scope

- **Owns**: `Values`, `Runs`, `Lits`, `Levels`, `Scope`; tests `M1*`; `NOTES-M1.md`.
- **Read**: ISA 2 (all), 1.5, 1.6, 4.2 (KSORT .. PEEK), 13.3; R05 for gruszka's value model; READMEs of
  `rlvm-level` (`mkSubst`, `instantiate(level, subst)`, `isEquiv`), `rlvm-nat` (`Nat`, `NatStore`),
  `rlvm-store` (`Terms`, `DeclTable`, `Specials`, `LitPool`).
- **Implements**: every stub of the four classes: constructors with canonical forms and flags (2.1-2.3),
  `CHEAD` and the sort cache, NEU interning with its spine in one probe (`Hash.neu`, hash in word c),
  `mkApp` with the `Nat.succ` rule, `mkProj`, `nsucc`, `peek`; `spine`, `frame` (key includes the REST run),
  `prune` (HOLE outside the mask, far frames), `push` (in place at the top of `es`), `enter`; `klit`,
  `sameContent`, Nat handles; `substOf`, `lsub`, `lsubList`, `lvlEq`, `listsEq`, `importLevel` (step 2).
- **May call**: `Machine` (`addRecordAt`, `addRun`, `putRunAt`, `internRecord`, caches, `fault`), `Layout`,
  `Hash`, `Op`; `m.terms`, `m.decls`, `m.specials`, `m.levels`, `m.lists`, `m.lits`, `m.nats`. No judgment.
- **Acceptance**: I-ID property tests on random records (two constructions, one id; PI against PII; `ls`
  in closure identity); intern and reset in O(used) (a session of one record does not touch a full
  table); far-frame identity with the REST run; a pair key with an immediate; literal content comparison
  of two NBIG / STR refs with different ids; the substitution regression of ISA 2.7 (`Prelude` has `c1.{u,v}`
  and `c2.{v,u}` with one body term); `OPEN` / `CLOSE` reproduce ids; closure identity independent of the
  path to the environment (same mask positions, HOLE elsewhere); `Nat.succ` rows (`Nat.zero` -> literal 1,
  NBIG at 2^31, WHNF flag only on a normal base); probe cost reported in ns and instructions per `mkApp`
  hit (target at most 60 instructions inlined).
- **Notes**: everybody waits for this package: publish constructors first (`mkSort`, `mkConst`, `mkNeu` /
  `mkApp`, `mkLam` / `mkPi` / `mkPii` / `mkSusp`, `prune`, `push`, `enter`, `lsub`), within the first
  hour, then literals and the rest. `Scope`, `mkFVar` and the accessors already work. Stage: v0;
  `importLevel` step 2.

### M2 - eval, force, apply, inst

- **Owns**: `Eval`; tests `M2*`; `NOTES-M2.md`.
- **Read**: ISA 4.2 (EVAL, ARGV), 4.3.1, 2.4, 2.6, 3.1 (`tabled`), 5.1-5.2; convention H8 (inert nests).
- **Implements**: `eval`, `force`, `apply`, `inst`, `argv`, `dom`, `forceFast`, `instFast`, the resume
  switch. v0 tables every non-leaf node on its SUSP record; let values are always LET suspensions; unread
  binders receive HOLE (B6, from the node mask); inert application nests are evaluated by one loop over
  scratch (no host recursion: a 913,500-element list literal must not recurse).
- **May call**: M1; `Infer.infer` (ONLY mode, for `INST` of a PII); `Code.run` only behind `SW_CODE`.
- **Acceptance**: per-instruction rows of ISA 4.2 and 4.3.1; closure identity independent of the path to
  the environment; HOLE for unread binders and `fault(E_HOLE)` on reading one; LET suspensions kept; inert
  nests give one call event (frame trace); a 913,500-element list literal (ISA section 9; build the term
  in a test) and a 1,000,000-deep application, binder and mixed nest on a 512 KiB thread stack under
  D = 64 and D = 0; identical value ids under D = 0 / 64.
- **Notes**: until `Infer.infer` is published, `inst` of a PII cannot be tested end to end: test PI
  closures first and say so in your log. Stage: v0; gating by node flags (`SW_GATED_TABLING`) step 1.

### M3 - reduction: delta, core, whnf, iota, quot, Nat, String

- **Owns**: `Reduce`, `NatRed`, `StrRed`, `Rules`; tests `M3*`; `NOTES-M3.md`.
- **Read**: ISA 4.3 (all), 2.9, 3.6 (case trees: step 4 only); R02 section 7 and section 10 (trap list);
  `rlvm-nat` README (the 15 operations and their guards).
- **Implements**: `delta`, `core` (cheap and full), `whnf`, `iota` (K, structure eta, literal views),
  `quotr`, `natop`, `natFold`, `offset` / `offsetFast`, `strView`, the `..Fast` queries, the resume switch;
  `Rules.rules` at step 4.
- **May call**: M1, M2; `Conv.conv` (K: `to_cnstr_when_K`; `offset`); `Infer.vtype`, `Infer.isprop`
  (`to_cnstr_when_structure`); `Facts` behind switches.
- **Acceptance**: rows of ISA 4.3 against R02 section 7; `bugs/k-rec-conv`, `nat-rec-k-lie`,
  `nat-shift-huge`, `str-lit-utf8*`, `other/nat-lit-*`; the `Nat.succ` rows of ISA section 11
  (`Nat.ble (Nat.succ (f 2)) 7`, the same with `Nat.mod`); a stuck projection returns the ORIGINAL cell;
  WHNF of an NSUC folds exactly when the official whnf does; case trees equal `DELTA` + `IOTA` on random
  arguments (step 4).
- **Notes**: theorem values kept as proof blocks are decoded into the overlay (`Overlay.blockRoot`) and
  the term arrays re-read (ISA 12 R4). Stage: v0; case trees step 4.

### M4 - conversion

- **Owns**: `Conv`, `Cargs`, `Pairs`; tests `M4*`; `NOTES-M4.md`.
- **Read**: ISA 4.4 (all), 6 (all), 5.4, 1.5; R02 section 8 (the state order is the official one).
- **Implements**: `Pairs` (status bits in one entry, unsigned key order, `UNDEC` stamped by
  `m.declStamp`); `conv` as the complete procedure (c0 without B12, c2 first line, c3..c13), `convFast`,
  `cbind`, `lazyd` (final pair in `m.A2`, `m.A3`), `projd`, `pirr`, `eta`, `etas`, `unitl`, `strx`;
  `Cargs.cargs` (v0: every position SAME; pass A and pass C in the official order); resume switch. Step 2:
  plans and the agreement rule; step 5: B1, B2, B12 (`cclo`), pass B, UNDEC, all under `TRY`.
- **May call**: M1-M3; `Infer.vtype`, `Infer.isprop`; `Scope`; `Facts` / `Cong` behind `SW_FACTS`.
- **Acceptance**: the state order c0-c13 against R02 section 8, one row per state and per LAZYD branch;
  `bugs/unit-rec-rule`, `eta-ctor-alias`, `rec-missing-ih`, `perf/irrelevance-refutes`,
  `proj-lazy-struct`; NEG not written under ER or for a LAM side and not read under ER; FAILED only for a
  completed F; a probe that ends in U leaves FAILED unset (step 5); the agreement rule on a crafted
  unread-then-proof telescope (step 2); `shortcheck` clean on Init (step 2+).
- **Notes**: stage v0 = complete procedure; plans step 2; probes step 5.

### M5 - inference, checking, the DECL driver, facts

- **Owns**: `Infer`, `Decl`, `Facts`, `FactStore`; tests `M5*`; `NOTES-M5.md`.
- **Read**: ISA 4.5 (all), 5.3, 3.5 (step 2), 14.1; R02 section 6, R03 section 4.
- **Implements**: `infer` (both modes, every term kind, TWALK, IPROJ internal), `check` (v0: generic rule
  + tabling; B9 behind `SW_EXPECT`), `vtype`, `ensort`, `enpi`, `isprop`, the `..Fast` queries, resume
  switch; `Decl.check(m, cid)` (returns a `MachineErrors` code, 0 = ACCEPT; each phase is
  `m.drive(J(m, .., 0))`; on ABORT / FATAL: `m.abandon()` and return `m.err`); step 2: `Facts`, `SIGD`,
  `FactStore`.
- **May call**: M1-M4.
- **Acceptance**: every `throw` of R02 section 6 as a row; the tutorial projection tests,
  `projIndexData2`, a field index beyond `nfields`, `x.1` on `P.mk (n : N) (p : True) : P n`; tabled CHECK
  on `perf/app-lam`; `theorem_type_is_not_prop`; check stamps: a type inferred without checking never
  answers a checking query; facts (step 2): one record per constant, published once, `SIG` for every
  constructor and recursor of Init, the manifest mutation tests, concurrent derivation by 6 threads gives
  identical records.
- **Notes**: `Decl.check` is the first thing every end-to-end test needs: publish a version that handles
  axioms and definitions as soon as INFER covers sort / const / app / lam / pi. Stage: v0; facts step 2.

### M6 - sessions, facade, budgets, debug modes; shared files; module build

- **Owns**: `Layout`, `Op`, `Hash`, `Machine`, `Frames`, `Ops`, `MachineProvider`, `MachineEngine`,
  `MachineSession`, `MachineOps`, `Debug`, `Show`, `package-info`, `pom.xml`, the service registration,
  `README.md`; shared tests (`Fixture`, `Sexp`, `Prelude`, `Toy`, `TestTables`, `ControlTest`,
  `MachineStateTest`, `FixtureTest`, `StepSizeTest`) and tests `M6*`; `NOTES-M6.md`. Answers the requests
  of the other packages for shared files quickly (that is the package's first duty).
- **Read**: ISA 1, 9, 14, 5.5; RECONCILIATION sections 2-3; the driver's and `rlvm-main`'s READMEs.
- **Implements**: the facade to completion (Reason table of 14.3, strict mode 14.2, cancellation 14.4,
  footprint and steps 14.5, `dumpStats` 14.5, `MachineOps` 14.6 with M8's `Quote`); the debug modes
  (`memocheck`, `poison`, `scopeaudit`, `maskaudit`, `sharecheck`, `shortcheck` with M4, `pubcheck` with
  M7); warm sessions (`SW_WARM`: reset rule, generation rule of 5.3 with M5, footprint cap); the inlining
  flag sets of H10 for the launchers; later the engine-wide tables under contention (step 6).
- **May call**: everything through `Ops` and the entry steps.
- **Acceptance**: `Session.check` on the 141 tutorial tests through `rlvm-main` with `-X engine=machine`
  and zero machine declines in the statistics (section 6); the Reason table; strict mode equals rule 3 of
  ISA 6.3; cancellation within 4096 ticks (unit-tested on toy judgments already; repeat on a real
  declaration); a fatal fault inside a TRY declines; footprint equals bytes in use and is not capacity;
  reset after every outcome other than ACCEPT; `memocheck`, `poison`, `scopeaudit` runs of Init.
- **Notes**: see `NOTES-M6.md` for what the skeleton decided and what it left undone. Stage: v0, step 1,
  step 6.

### M7 - K1 elaborator and code stepper

- **Owns**: `Elab`, `Code`, `CodeStore`, `Match`, `Cong`; tests `M7*`; `NOTES-M7.md`.
- **Read**: ISA 3 (all), 4.1, 4.4.4, 4.5.4, 6.1, 8.2 (code stepper), 8.3; convention H7 (register stack).
- **Implements**: nothing that runs in v0. In order: `Cong` (step 2, with M5's facts), `CodeStore` +
  `Elab` + `Code.run` for TELE with `Match` and the IC table (step 3), BODY / RULE and LEAF blocks (step
  4). All behind `SW_FACTS` / `SW_TELE` / `SW_CODE`.
- **May call**: everything; publication rule of ISA 3.3 (only export or generated term ids, constant ids,
  name ids, base level ids; derived levels structurally).
- **Acceptance**: the listings of ISA 3.7 reproduced; `pubcheck` on every block of Init and Std; theorem
  block values never elaborated; `MATCH` = build + `CONV` on sampled sites; two workers elaborating one
  role publish equal blocks; the rent-to-own counter; tier equivalence K0 = K1 with M8.
- **Notes**: starts after the v1 gate of step 1 (ISA 13.6). Before that: design review of the stepper
  against H7, an elaborator that only *prints* blocks for the listings (no execution), and help for M8.
  Stage: steps 2 (plans), 3, 4.

### M8 - quote, tier and convention equivalence, differential tests

- **Owns**: `Quote`, `TierEq`, `Diff` (test tree); tests `M8*`; `NOTES-M8.md`.
- **Read**: ISA section 11 (testing), 13.5, 14.6; convention H12; `rlvm-testkit` README; `bench/README.md`.
- **Implements**: `Quote` (readback of a value into the overlay, memoised by value and depth, never on a
  checking path); `Diff.directory` and the slow test classes that run `Fixture.diffAgainstReference` over
  the tutorial, `bugs/`, then all 230 tests, then Init, writing `work/status/machine-diff-*.txt`;
  `TierEq.conventionEquivalence` (D = 0 against D = 64: value ids, memo contents, ticks, outcomes, call
  trace, per declaration); judgment-level differential tests against `RefKernel` (`whnf` and `infer` read
  back and compared up to the reference's `isDefEq` on sampled sub-terms); frames per declaration and the
  0.1% bound on frames written under D = 64; the per-stage cost table (CPU, wall, RSS, instructions) by one
  command; later K0 = K1.
- **May call**: everything, from the test tree.
- **Acceptance**: the reports above exist and are regenerated by one command each; the gate reports are
  what the orchestrator reads.
- **Notes**: start with the report tooling on the current state (every unit declined, 0 wrong) so that the
  first judgments that land are measured the minute they are published. Stage: all.

## 8. Who calls whom (v0)

```
Decl.check (M5) -> Infer.infer / check / ensort / isprop (M5) -> Eval.eval / inst / dom (M2), Reduce.whnf (M3),
                                                                 Conv.conv (M4), Scope.open / close (M1)
Conv.conv (M4)  -> Reduce.core / whnf / delta / NatRed.natFold / offset (M3), Eval.force / inst / apply / dom (M2),
                   Infer.vtype / isprop (M5), Cargs.cargs (M4), Scope (M1), Pairs (M4)
Reduce.* (M3)   -> Eval.force / eval / apply (M2), Conv.conv (M4: K), Infer.vtype / isprop (M5: structure eta)
Eval.* (M2)     -> Values / Runs / Levels / Lits (M1), Infer.infer (M5: INST of a PII)
```

Expected order of first publications: M1 (constructors), M2 (`eval` without tabling subtleties), M3
(`whnf` on closed terms), M5 (`infer` for the simple kinds, `Decl.check`), M4 (`conv` identity .. lazy
delta), M6 / M8 alongside. Until a callee is published, its stub throws `UnsupportedOperationException`,
which `MachineSession.check` turns into DECLINE: the main tree is always sound.

## 9. Additions by M6 after the skeleton (2026-11-24)

- **`publish` is atomic also when it is interrupted** (`scripts/machine-sandbox.sh`, since 09:50Z; self-test: 23
  checks). SIGINT / SIGTERM / SIGHUP no longer stop it between "files replaced" and its verdict; if its
  Maven run is stopped it restores the main tree and reinstalls the jar; if the script itself is killed
  (SIGKILL), the next command that takes the lock finds the journal `work/sandbox/.inflight` and either
  completes the publication (Maven had ended with status 0) or undoes it (log line `publish Mk: DIED before
  its verdict, main tree restored` in `machine-Mk.log`: publish again). Do not wrap `publish` in `timeout`.
  The main tree's fast group takes about a minute now and the lock may add several: a publication can
  outlast a 2-minute command limit; let it run in the background and read `work/sandbox/publish.log`.
- **The gate through the launcher**: `rlvm-machine/src/test/resources/m6/launcher-gate.sh [tutorial | bugs |
  perf | other | corner-cases | all | FILE | DIR].. [-X KEY=VALUE].. [--jar CLASSES-OR-JAR]` runs the tests
  through `work/dist/bin/rlvm-jvm -X engine=machine`, reads the statistics and writes
  `work/status/machine-gate-<label>.txt` (verdicts, machine accepted / rejected / declined by error code,
  the first failure messages). `--jar work/sandbox/Mk/rlvm-machine/target/classes` runs your sandbox.
- **Engine settings on the command line**: `-X machine.debug=memocheck,poison,scopeaudit,maskaudit`,
  `-X machine.switches=warm,..`, `-X machine.hostDepth=0`, `-X machine.memocheck.every=N`,
  `-X machine.memocheck.budget=N` (also as system properties `rlvm.machine.*`).
- **Failure messages**: raise a mismatch with `m.fail(code, a, b)` and `Session.lastMessage()` shows the two
  values; a memory or stack decline names its limit (`Machine.limitDetail`) and the footprint by part; a
  failed audit carries its detail (`m.auditFail(text)`), a memo disagreement also the first difference of
  the two values. The statistics keep the first four messages (`machine.firstFailures.i`).
- **Records in use are `[m.vbase, m.vp)`, run words `[m.rbase, m.rsp)`** (the bases move under debug mode
  `poison`). **`m.nats` is the machine's own number overlay** (emptied at every reset, counted in the
  footprint); a handle of it crosses to the context's `NatBinding` only through `Nat.transfer`.
- **Debug hooks** (class comment of `Debug`): memo hits call `Debug.memoCheck` or `memoEnter` / `memoExit`
  (a re-computation is compared by id, then up to PEEK, then by `CONV`); `Debug.scopeAudit` at GET,
  `Debug.maskAudit` before PRUNE, `Debug.shareCheck` on untabled nodes, `Debug.shortCheck` for shortcuts,
  `Debug.pubTerm / pubLevel / pubList / pubConst` at publication. Statistics on the hit branch:
  `if (m.stats) m.hits[Op.J]++;`; probe outcomes `m.probe(Op.B1, Layout.T)`.
- **H10 inlining directives**: `rlvm-machine/h10/hotspot-inline.cmd` (`-XX:CompileCommandFile=`) and
  `h10/native-image-args.sh` (prints the `-H:PriorityForceInline=..` list from the class files); not wired
  into a launcher and not measured on the real machine yet.
- `Fixture.defShared(name, lparams, type, valueOfConstant, hint)`: a definition whose value is another
  constant's value node (one term id under two parameter lists; `c1` / `c2` of the prelude).
  `Fixture.diffAgainstReference` counts a unit the reference itself declines as "not judged".
