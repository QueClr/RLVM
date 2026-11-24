# Design review: critics' findings (verbatim)

Three independent critics reviewed `ISA.md` and `ARCHITECTURE.md` (first complete drafts). Their findings are
reproduced here unedited. The orchestrator's binding resolutions are in `../RECONCILIATION.md`.


## Critic 1: coherence and implementability

**Verdict.** Not ready to staff as written; one reconciliation pass is needed first. The nine skeleton modules compile cleanly (73 files, javac -Xlint:all into a temp dir, imports match pom dependencies), and both documents are careful in their own scope. Three blockers: ISA has no module skeleton, work packages or acceptance gates, while ARCHITECTURE's 17 packages absorb every agent; closure identity uses a bare level list where a (parameter names, levels) substitution is required; process-wide code objects and facts would hold worker-local level and term ids. Vision: the worklist mechanization is real and K1 is supported by the spike, but Truffle specialises mostly definition bodies, is deferred to v2, and never pays on Init in the spike. Research doc 10 and the spike README did not exist; I read the raw spike results.


### C1.1 [blocker] ISA.md 2.2, 2.7, 4.3.2 (`ls`); rlvm-api `LevelOps`

**Problem.** `ls` is defined as a level-list id and is part of LAM/PI/SUSP identity, but instantiating a level needs the owning constant's parameter names, which the generic `EVAL(t, env, ls)` does not have. Read literally, a closed term shared by `c1.{u,v}` and `c2.{v,u}` gets one record for both at levels [1,2], so its DOM/VAL/TYPE memo answers with a value computed under the other meaning: a wrong verdict in either direction. gruszka, the cited source (R05 2.2), keys on a (parameter names, level list) pair.

**Proposed fix.** Define `ls` as `LevelStore.mkSubst(lparams(row), levels)`, which already exists (0 = identity, equal pairs give equal ids). Add `mkSubst`, `instantiate(level, subst)` and `instantiateList(list, subst)` to `rlvm.api.LevelOps`. Optionally restrict the substitution to `lpSet(t)` for better hit rates.


### C1.2 [blocker] ISA.md 1.1, 3.2-3.6, 12 (R6); ARCHITECTURE.md 2.4, 2.8, 3.5

**Problem.** Code objects and static facts are process-wide, but part of their content is worker-local: `slvl`/`rlvl` are derived levels created in one worker's level overlay, `BODY` of a block-valued theorem holds overlay term ids, and K2 templates are rebuilt from session values. `LevelStore` overlay ids are private and reissued after `reset()`, so a published `slvl` id denotes a different level in another worker, and in the same worker after its next session reset (also with `-j 1`). A wrong prop mask skips a real comparison in `CARGS`: a wrong accept.

**Proposed fix.** State one rule in both documents: a published artefact contains only export or generated term ids, cids, name ids and base level/list ids. Store derived levels structurally (postfix ints over name ids in the chunk) and re-intern them per session through an import cache cleared at reset. Constants with `CF_BLOCK_VALUE`, and any derivation that touches an overlay id, stay at K0. Debug builds assert the rule at publication.


### C1.3 [blocker] ISA.md 11-12; ARCHITECTURE.md 10.1-10.2

**Problem.** The machine cannot be staffed. ISA.md has no module skeleton (`rlvm-machine` does not exist), no work packages, no file ownership and no acceptance criteria; opcode numbers, frame-layout constants, `Ops` signatures and the E_* codes exist only as prose. ARCHITECTURE defers to ISA ("the machine's own packages are in ISA.md") and its own 17 packages take all 14-16 agents. M1-M3 are reference-kernel milestones, so the machine has no gate before M4.

**Proposed fix.** Before the wave, one agent writes the `rlvm-machine` skeleton (machine state, layout and opcode constants, memo-slot indices, `Ops` signatures, error enum, a test fixture builder) and a table of about eight packages: values and runs; eval/apply; reduction; conversion; inference and DECL; K1 elaborator and stepper; facade, budgets and debug modes; quote and tier-equivalence tests. Gates: v0 diff-clean on tutorial and bugs by M2, v1 on Init and Std by M3. Assign at least six agents; defer P1, H1, L2.


### C1.4 [major] ISA.md 3.5, 12 (R6), examples 7a and 7e; ARCHITECTURE.md 4.1 (I2), 4.4

**Problem.** `SIG(c)` (`slvl`, `rlvl`, hence prop masks and the static PROOF flag) is recorded only when the machine's `DECL` accepts `c`. Inductive types, constructors and recursors are admitted by the reference kernel, so `Eq`, `Eq.refl`, `And.intro` and every structure constructor never get one, although examples 7a and 7e rely on `rlvl(Eq)`. Under the optimistic environment a user of `c` may also run before `c` is accepted, so instruction counts depend on the schedule.

**Proposed fix.** Derive `SIG(c)` on demand, by any worker, with an ONLY-mode `INFER` over the type telescope of `c` under canonical variables. That is a function of the store alone, so drop "published only for accepted constants" and publish it under the rule of the previous finding. A fault during derivation records "no facts".


### C1.5 [major] ISA.md 3.1, 12 (R1'); ARCHITECTURE.md 2.1, 2.6, 3.3

**Problem.** The tabling gate needs SHARED and BODY node flags and `unitFirstTerm(unit)`. Nothing provides them: `appendExport` takes no flags, the block format has no bit, a one-pass forward decoder cannot know later referrers, and promotion appends to the export region out of file order, so "outside the unit's segment" is not an id range. Under-approximating sharing walks a DAG as a tree (`perf/app-lam`).

**Proposed fix.** Assign to A1, A3 and L1. The loader counts referrers per segment record in the declaration sweep and sets the header bit on shared-part nodes. The block decoder sets a SEEN bit at the first reference to a child and SHARED at the second (the third reserved bit). Promoted nodes are always SHARED. The store records each unit's first export id. Property-test against a naive count; v0 tables every non-leaf node.


### C1.6 [major] ARCHITECTURE.md 6.4, `Worker`, `Session.footprintBytes`; ISA.md 1.1, 2.5, 11

**Problem.** Session size is defined three ways. ARCHITECTURE resets when overlay plus engine bytes in use exceed 32 MiB (the `Worker` pseudo-code adds the overlay twice); ISA sizes `vh` alone at 32 MiB and its tables at 85-96 MiB per worker. If `footprintBytes()` returns capacity, every unit resets the session. ISA's reset is not O(used): `vtag` and the 16 MiB `POS`/`NEG`/`FAILED` tables have no stamp. A full clear per reset is about 18 MiB, which v0's one-declaration sessions pay about 60,000 times on Init (about 1 TB).

**Proposed fix.** Define footprint as bytes in use (records, run slots, table entries), counted once. Size every session table from `sessionBytes`. Require reset in O(used): clear intern slots by rehashing the used records and stamp the pair tables with the epoch. State the per-worker capacity that ARCHITECTURE 2.9 should budget.


### C1.7 [major] ISA.md 2.1, 2.9, 12 (R3); ARCHITECTURE.md 2.1, 2.4, `NatOps`, `LitPool`; rlvm-nat as built

**Problem.** Four incompatible Nat boundaries. ISA: immediates below 2^31, NBIG "big ids" above. rlvm-nat: long handles below 2^63, `NatStore` ids above. Term nodes: inline below 2^64, `LitPool` index above. `NatOps` works on NATLIT term ids, so each machine operation would allocate overlay nodes. There are two bignum stores, `NatStore.freeze()` conflicts with pipelined loading, and `LitPool` strings are not content-canonical although `CONV` c0 answers F for two distinct STR refs.

**Proposed fix.** Give the bridge one owner (A2 with the rlvm-nat author). In the machine, an NBIG record holds lo/hi words below 2^63 and a `NatStore` overlay id above; the worker's `NatStore` has an empty base and imports shared literals at `KLIT`. `LitPool` keeps export literals only and interns strings by content. The machine calls `rlvm.nat.Nat` directly; `NatOps` stays for the reference kernel.


### C1.8 [major] ISA.md 9, 12 (R7, R9); rlvm-api `EngineConfig`, `Reason`, `Session`, `RunSignals`

**Problem.** Facade concepts that ARCHITECTURE requires are absent from ISA: `EngineConfig.strict` (the cold re-check), `KernelProfile`, how a cancelled unit returns. There is no E_* to `Reason` table. `E_NATSIZE` is a decline in ISA, but `NAT_LITERAL_TOO_BIG` is a reject reason and `ResourceException` refuses reject reasons. Strict is fixed at engine creation, so the re-check needs a second machine engine, and whether it shares code and facts with the first is undefined.

**Proposed fix.** Add an ISA section on the facade. Strict means K0 only, the complete procedure, only the exact shortcuts (B6, B10, B11), a fresh session, no `PCHK`, no shared facts or code. Add the mapping table and `Reason.CANCELLED`. Decide the Nat guard once: mirror the official REJECT, which the reference kernel confirms anyway. `Run` creates the strict engine with `withStrict(true)`.


### C1.9 [major] ISA.md 4.4.1 c1, 4.4.2, 5.4, 6.3 (B1); ARCHITECTURE.md 8.2, M4

**Problem.** The same-head probe stores a budget-exhausted `U` in `FAILED`, the table the complete procedure reads in `LAZYD`. This contradicts ISA 5.4 ("U is never stored as F") and rule 1 of 6.3: for such a pair the official unbounded argument comparison is skipped and both sides are unfolded, so the warm machine is less complete than the official kernel. The outcome is a spurious REJECT, a full strict re-run and a WARM-ANOMALY, which M4 forbids.

**Proposed fix.** Keep `FAILED` for comparisons that completed with F. Record budget-exhausted probes in a separate table (or `UNDEC`) that only suppresses another probe, so `LAZYD` still runs the official same-head comparison once.


### C1.10 [major] Vision fidelity: ISA.md 0, 3.2, 8, 11; spikes/machine/results (exp1, hot, exp2, exp4)

**Problem.** Truffle does not specialise the checking process. `BODY` and `RULE` are compiled lambda terms; conversion has no code object (`CONG` is two masks); `CONV`, `LAZYD`, `WHNF`, `INFER` stay hand-written Java; T2 is v2 with an expected gain under 1%. The spike agrees: compiling 184 hot telescope roots costs 8.4 G (no inlining) to 59.6 G for 0.25-0.67 G saved per Init pass, and no threshold beats never compiling (6.49 G). K1 does pay (Init 6.38 vs 7.59 G, Std 10.98 vs 17.19 G). A plain worklist costs +33% over recursion on eval and readback of Init's types, so the 8-10% estimate rests on unmeasured fast paths.

**Proposed fix.** Say this in ISA section 0. Make the judgment-specialised roles primary: `TELE`, a real `CONG(c)` block (a per-position comparison plan), and K2 `RULES`, whose design should move ahead of T2. Treat T2 as an optional experiment with a go/no-go number and remove its constraints from K1. Add a regression test on frame count and instructions per frame to hold the worklist overhead.


### C1.11 [major] ARCHITECTURE.md 10.2, Appendix A; parent pom.xml

**Problem.** Parallel work collides. Four packages (A1, A2, A3, R1) edit `rlvm-store` and three (R1-R3) edit the single mutually recursive class `RefKernel`, while the build rule is one owner per module. M1 needs about twelve packages in a chain (A1, A3, L1, R1, R2, R3, I1, D1, D2, C1) before any end-to-end test runs. The parent pom still lists four modules, so `scripts/dev.sh build rlvm-store` rejects the module name.

**Proposed fix.** Add the Appendix A lines now. Split `rlvm-store` ownership by file with A1 owning the build, move `TermOps` to `rlvm-ref`, and split `RefKernel` into infer, whnf and def-eq files behind a frozen state class. Add a first-wave vertical slice: batch load, `-X no-proof-blocks`, `-j 1`, lazy `advanceTo`, reference engine, tutorial tests.


### C1.12 [minor] ARCHITECTURE.md 6.5, `Budget.warm()`

**Problem.** A unit that exceeds 5 x 10^7 warm steps is abandoned and restarted cold with unlimited steps, so the heaviest declarations are paid for twice (at ISA's 50 instructions per tick, about 2.5 G wasted per unit). The heavy lane's deep stack is needed only by the reference kernel; the machine has no host recursion. Nobody has measured how many mathlib units cross the limit.

**Proposed fix.** For the machine, keep only the byte budget in warm sessions and let the unit run on; cancellation already works through `firstFailure` polling. Keep the heavy lane for reference-kernel re-checks. If a step limit stays, set it from the measured step distribution on Init and Std.


### C1.13 [minor] ISA.md 1.1, 3.3 (publication of facts); ARCHITECTURE.md 2.5, 2.8, 4.6

**Problem.** Shared tables have unstated owners. The machine's per-row fact and code tables must grow while the loader is still adding constants, and multi-word facts written "racily" can be read half-written. On the other side, the environment thread must intern rule lists and derived levels, but the list and level stores belong to the loader thread and are frozen in batch mode.

**Proposed fix.** Make per-row engine tables chunk directories published by compare-and-set; write a fact completely, publish it with one release store, read it with acquire. Put recursor rule triples into an environment-owned array of `DeclTable` instead of `IntLists`. Generated constants stay a v1 decline.


### C1.14 [minor] Assorted: ISA.md 1.2, 2.4-2.6, 4.4, 12 (R1); ARCHITECTURE.md 2.1, 4.1 (I4), 6.3, 6.6, 10.2; `LevelStore`

**Problem.** Smaller mismatches that will each cost a question. ISA says `fvMask` bits 0..62 are exact, but `maskUnder` over-approximates. The `CODE` memo depends on which tier made the closure, against I-FLAG, while the reserved `c` word of LAM/PI nodes is unused. `LAZYD` returns three results through one register. `blockRoot` may reallocate the overlay mid-unit although `Terms.refresh()` is per unit. `giveBack` triggers on 20 ms. I4 catches every Throwable, 6.6 and rlvm-cli forbid catching Error, `LevelStore` signals depth by StackOverflowError. WP names T1/T2 collide with tier T2.

**Proposed fix.** One reconciliation list owned by both architects: the mask contract is "superset, deterministic"; the term-to-block mapping lives in the node's `c` word; multi-result judgments write into the caller's frame; the machine reloads arrays after `blockRoot`; `giveBack` counts steps; one rule for throwables (catch Exception only, depth counters in rlvm-level); rename the work packages.


### C1.15 [minor] Over-engineering: ARCHITECTURE.md 3.5, 4.2, 6.2-6.5, 7.1; ISA.md 2.2, 8, 10, 11

**Problem.** Several mechanisms are specified in full before anything runs: pipelined loading with level snapshots (blocked on an API that does not exist), namespace-window ordering, scan helpers, the late-names table and generated region (for files v1 declines), give-back, three launchers. In ISA: T2 suspend and resume, the EPH/UNQ tag bits, strategy kinds 13-15, and a v0 that already carries HOLE pruning, canonical free variables, NSUC and zero-copy spines.

**Proposed fix.** Freeze a first-version cut: batch load, file order, CE native image, one claim rule, no heavy lane for the machine. ISA v0 keeps canonical ids but tables every node and drops layout choices made for T2. Everything else moves behind its measurement gate with an owner.


## Critic 2: performance and feasibility

**Verdict.** The design is coherent and implementable, and the spike confirms its core bet: code objects cut telescope instantiation by 27–52%. The 4 GB memory budget holds, apart from the inline-cache array. The design does not yet support its own goal. No document adds up a mathlib total; measured and budgeted parts already use about 30% of the leader's 395 G before any conversion. My low-confidence estimate is 500–800 G on the CE image. One convention must be settled before fan-out: the stepper, whose best-case overhead measured +33%, against 8–10% planned. Five further defects cost several percent or wall clock each and are cheap to fix now: masks missing for constructor and inductive heads, round-robin work distribution, heavy declarations run twice, a 128 MiB per-worker working set, and no measured machine checkpoint before everything lands. K2 and Truffle are worth under 1% on mathlib.


### C2.1 [blocker] ISA.md §0 (vision cost table), §1.3 (stepper), §8.2

**Problem.** ISA budgets the worklist at P = 25 per frame, 8–10% of nano-lean's total. Spike exp2 (eval + readback of all Init types, same operation library) measures +33% for the worklist machine against host recursion with interning on (2.48 vs 1.87 G on the CE image, +30% on the JVM in steady state) and +53% with interning off. That spike machine is the best case: one method, registers in locals, 2–3 word frames, one dispatch. The ISA stepper costs more per frame: run() switches on op and calls X.step(m), which switches on st; every nested judgment returns to run(); registers live in Machine fields; frames carry a header plus 4–9 payload words. About 25 state machines will be written against this convention, so changing it later means rewriting all of them.

**Proposed fix.** Settle the convention by measurement before fan-out (about one day): extend exp2 with ISA-shaped frames and two-level dispatch, and require at most +20% over recursion. Specify: (a) one fused loop per hot family (EVAL/APPLY/FORCE/INST; WHNF/CORE/DELTA/IOTA; CONV/CARGS/LAZYD) with W, wp and A in locals, where a same-family push is a `continue` and only cross-family calls return to run(); (b) one dense dispatch on op<<4|st; (c) inert evaluation (constant-headed application nests) as a single frame looping over scratch X, as MATCH and STRVIEW already do; (d) each step method under 8,000 bytecodes so the JVM launcher of the platform gate can compile it. Replace the 8–10% line in §0 with the measured figure.


### C2.2 [major] ISA.md §0 and §7; ARCHITECTURE.md §3.5, §3.6, §7.1, §10.1 (M5)

**Problem.** Neither document derives a mathlib total; ARCHITECTURE uses 375 G and '325 G on six workers' as working assumptions. Known parts already take about 30% of nano-lean's 394.9 G before any conversion: load 38–51 G (the 5.4 instr/byte target is Susz's Rust figure), environment pass 11 G, block decode 4–7 G, telescope instantiation about 62 G scaled from Std (spike exp4: K1 5.7 G, 14% of the leaders' 40.3 G Std total; generic walk 11.9 G). That leaves at most 265 G for all conversion, reduction, binders, tabling and frames. This is less than I estimate gruszka's Rust+PGO kernel spends on the same work, while the CE image pays 96–192 instructions per intern probe plus worklist frames. My estimate is 500–800 G on the CE image (low confidence), with 25–42 s wall. Research doc 10 and spikes/machine/README.md never appeared; figures are from spikes/machine/results/*.txt.

**Proposed fix.** Add a per-phase budget table to ARCHITECTURE §9.4 (load ≤ 50, env ≤ 11, decode ≤ 6, walk ≤ 90, conversion and reduction ≤ 240 G) and track instructions per expression record on Init and Std against the leaders (24.3–24.8 G and 40.3 G) from the first machine run. Move the platform gate from M5 to the point where the machine first accepts Init. The machine spike confirms it is the largest lever: JIT steady state is 0.6–0.8x of the CE image (code-object pass 2.25 vs 2.91 G), -O3 saves 9–12%, Oracle PGO 13–17%. Measure the reader's instr/byte at work package L1, not M3. Track probe cost as a number, with a target of 60 instructions or fewer.


### C2.3 [major] ISA.md §3.5 (slvl, rlvl, prop mask, PROOF), §4.5.6, §12 R6

**Problem.** slvl/rlvl are 'recorded when the type of c was checked at admission' by the machine's DECL frame. Inductive types, constructors, recursors and quot constants are admitted by the reference kernel on the environment thread, so the machine never records them. Prop-position skipping (B3) and the static not-a-proof filter (B7) are therefore unavailable for exactly the heads where they pay most (Subtype.mk, And.intro, Exists.intro, Eq, recursor applications), although worked examples 7a and 7e assume them. The same hole exists for a constant another worker is still checking. These masks were worth −24% on navier-stokes-euler for rpylean and about 9% on a mathlib declaration for pestka. Without them every such value pays VTYPE + ISPROP (about 1,000–1,500 instructions) in CONV c4.

**Proposed fix.** Compute slvl/rlvl/dep lazily at first need by infer-only inference over the type telescope under canonical variables, on any worker, with a racy idempotent publish; recording at DECL acceptance becomes an optimisation. This is sound under the optimistic-environment rule (ARCHITECTURE I2): a fact about an ill-typed constant only exists in a run that is not accepted. A fact that cannot be computed (inference throws) stays 0, meaning compare everything. Add a test that SIG exists for every constructor and recursor of Init.


### C2.4 [major] ARCHITECTURE.md §6.3 (claim rule), §6.2, §6.8

**Problem.** claim() hands every worker the next min(32, max(1, backlog/(2W))) units from one global front. That is round-robin chunking, the variant gruszka measured 5% worse on mathlib at 8 workers than contiguous per-worker parts; §6.2 cites that figure but §6.3 implements the losing scheme. Under pipelined loading the loader is roughly as fast as six workers (17 s vs 18 s by the document's own model), so the backlog stays small and claims degenerate towards single units: each session then sees every sixth declaration. This probably fails the 2% parallel-overhead gate of §6.8, which would cut the worker count and the wall clock with it.

**Proposed fix.** Split each publication batch (4,096 units or 32 MiB of input) into W contiguous runs, one per worker. A worker takes chunks of 32 from the front of its own run; an idle worker steals the back half of the largest remaining run (gruszka's scheme, applied per batch so the pipeline is kept). When the loader starves the workers, park the surplus workers rather than interleave single units. Measure -j 1/2/4/6 on Std and a mathlib slice as soon as the reference engine runs.


### C2.5 [major] ARCHITECTURE.md §6.5 (Budget.warm, heavy lane), rlvm-api Budget.warm()

**Problem.** A warm attempt is cut at 5×10^7 steps (about 2.5 G instructions at ISA's 50 per tick). The unit is then restarted from scratch in a fresh session on one of 0–2 heavy-lane threads. Every legitimately heavy mathlib declaration is therefore checked twice, and the heavy ones run serially at the end. Pestka's heaviest mathlib declaration alone costs about 12 s, so I expect roughly 5–30 declarations above the limit: 12–75 G wasted (3–19% of the leader's total) plus a wall-clock tail of tens of seconds. The step limit buys nothing for verdicts: a warm ACCEPT is final, a warm REJECT is re-checked anyway, and loops caused by an ill-typed earlier unit are ended by firstFailure polling.

**Proposed fix.** Give the machine no step limit in warm sessions (keep the memory watermark and firstFailure polling), or on exhaustion continue the same frames in place under the cold budget instead of restarting. Keep step budgets for strict and differential test modes only. Reserve the heavy lane for memory retries and reference-kernel re-checks.


### C2.6 [major] ISA.md §1.1, §1.5, §2.4, §2.5, §8.4 (IC); ARCHITECTURE.md §2.9, §6.4

**Problem.** (1) The two documents define the session differently. ARCHITECTURE resets at 32 MiB of overlay plus engine arenas; ISA sizes vh alone at 32 MiB, giving 85–96 MiB of arenas and about 128 MiB per worker with the overlay. The two differ by a factor of 3–4 in session length. (2) 128 MiB per worker is twice nano-lean's arena. The small-working-set leaders retire more instructions per CPU-second in the arena results (gruszka 4.2 G/s at 8 MiB, rpylean 4.5 at about 13 MB, against nano-lean 3.25 at 64 MiB); this is suggestive, since the programs differ. (3) Layout costs cache lines: a run is spread over rs plus four parallel arrays; vtag sits beside vtab; POS, NEG and FAILED are three tables on one key; ENV runs share the slab with interned runs, so in-place PUSH fails after any intern and each PUSH burns 16 B of side tables. (4) IC is a per-worker array indexed by global site number: with a TARG site per telescope parameter this is tens of MB per worker on mathlib, not the 4 MiB budgeted.

**Proposed fix.** Adopt one definition: reset when overlay bytes + engine bytes used exceed sessionBytes; default 16 MiB, swept over 8/16/32/64 on both clocks when the machine first runs Std; size every table from the cap. Store the run header (length, kind, hash) inline in rs, with run id = offset. Represent ENV as a (start, length) pair in the frame over a separate non-interned slab. Pack a 4-bit tag into the vtab word. Merge POS/NEG/FAILED into one pair table with three status bits (one probe instead of three per new CONV pair). Make IC a direct-mapped lossy table of 2^14 cells keyed by site id.


### C2.7 [major] ISA.md §11 (v0/v1/v2), §3.6, §8.5; ARCHITECTURE.md §10.1

**Problem.** v0 has no sessions (one declaration per session), so its Init numbers are unrepresentative. v1 then lands sessions, K1, MATCH, TRY, masks, inline caches and parallel workers together. There is no measured machine checkpoint between 'passes the tutorial' and 'everything', and no machine instruction target before M5. K2 and T2 occupy a full stage but are worth under 1% on the ranked metric: derived rules gave gruszka −0.1% and pestka −0.3% on mathlib, and the T2 governor (3.3 G per block per worker) cannot fire on mathlib, where the hottest telescope (Eq) consumes about 0.5 G per worker. The perf tests need neither to pass their timeouts.

**Proposed fix.** Re-sequence with a measured gate on Init, Std and two mathlib slices after each step: (1) K0 + sessions + SHARED/BODY gating + PCHK; (2) static masks B3/B4/B6/B7 with lazily computed facts; (3) K1 TELE + MATCH; (4) K1 BODY/RULE plus casesOn and matcher shapes; (5) TRY probes; (6) parallel workers. Add machine targets on Init: at most 2.5x the leaders at step 1, 1.5x after step 4. Start K2 and T2 only after mathlib is accepted, and only if a gate shows more than 1% on mathlib or a perf test would otherwise time out.


### C2.8 [major] ISA.md §0 decision 3, §3.1, §3.4 (ELAB_AT = 2, 80 instructions per node)

**Problem.** The spike measures elaboration at 15.6 k instructions per constant on Init (0.62 G for 39,783 telescopes) and 21.9 k on Std (1.53 G for 69,712). That is about 220 instructions per code word, and on Std 27% of the whole K1 telescope cost and 3.8% of the leaders' Std total. ISA assumed 80 per node and 0.8% of mathlib. The saving per use is 880 (Init) to 2,400 (Std) instructions per spine, so break-even is at 9–18 uses, not the third. The heat data agree: threshold 10 keeps 93–94% of instantiations while elaborating 14–15% of the used constants (Init 6,002 of 39,783).

**Proposed fix.** Set ELAB_AT to about 10, swept over 4/8/16/32 on Std, and make it rent-to-own (elaborate once the role has spent its own elaboration cost in the generic walk). Derive SIG facts (reads, dep, slvl, unread) independently of code elaboration so that masks exist for cold constants. Re-derive the BODY, RULE and K2 elaboration budgets from the measured 220 per code word, and profile the elaborator itself.


### C2.9 [major] ISA.md §3.6, §4.4.1 (c0), §4.4 ETAS, §4.5.4 (TWALK), §5.1; trick lists of R04–R07

**Problem.** Most ranked tricks are realised. Missing or deferred: (a) casesOn-direct and matcher-direct exist only through K2 (v2); in K1, example 7b costs 11,000 instead of 965 instructions per structural-recursion step. Gruszka has both in its arena revision; pestka measured −1.5% on Init and −2.5% on con-leche. (b) Closure congruence (gruszka defeq step 6, S11) is absent: two closures or suspensions over the same term with different frames are compared by evaluating both bodies. (c) Structure eta compares proof fields (S5). (d) A lambda-headed application re-infers its body per enclosing level; gruszka's lam_dm and nano-lean's T4(e) avoid this quadratic ladder. (e) Nothing but PCHK survives a session (gruszka keeps eqp and open facts, sokonanoda a closed-WHNF store).

**Proposed fix.** (a) Add CASESON and MATCHER as K1 shape facts beside PICK/PROJARG/CTORBODY: evaluate only the major premise and the chosen alternative, with K-like recursors excluded. (b) Add shortcut B12 at c0: same term and ls on both sides gives a TRY-bounded pairwise CONV of the non-HOLE frame slots; only T decides. (c) Apply the constructor's prop mask in ETAS after the type comparison. (d) Memoise the body type on the PII when it is closed (m0 is free). (e) Measure session warm-up cost first; add a stamped process-wide table of closed-term facts only if it exceeds 2%.


### C2.10 [major] ISA.md §3.1, §12 R1' and open question 3; ARCHITECTURE.md §2.1, §3.3, §2.6

**Problem.** ISA v1 depends on SHARED (two or more referrers inside the segment) and BODY node flags. ARCHITECTURE reserves header bits 13..15, but neither the loader sweep, the block writer nor the decoder computes referrer counts, and the skeleton has no such flag. Without them every non-leaf proof node is tabled: about 120–300 instructions each, 8–13 G on mathlib. The gate as specified also drops nanoclo's measured case (−10% on its corpus): an unshared subterm that reads fewer variables than its parent is re-inferred under every environment of the parent.

**Proposed fix.** Assign it to the loader: keep a saturating 2-bit referrer counter per segment record, incremented when a later record of the same segment names it as a child (under 10 instructions per record), counted before the shared/block split. Store SHARED and BODY in header bits 13/14 for shared nodes and in two tag bits of APP/LAM/PI block records; the decoder copies them. Use bit 15 for INVARIANT (fvMask is a strict subset of the single parent's) and table those nodes too. Add a debug check that a walk never visits a non-tabled node twice under one frame.


### C2.11 [minor] ISA.md §8.3, §8.5 (heat, RENT, governor), §3.6 (K2 trigger)

**Problem.** Planning constants differ from the spike. A promoted telescope root costs 45 M instructions to compile without inlining of the operation library and gains 13%; with inlining it costs 324 M and gains 33% (ISA assumed 0.5 G and 30%). Heat is per worker, so with six workers a block needs six times the rent. K2 may spend up to 256 derivations of 65,536 ticks per worker (about 0.8 G each, 5 G in total) for a mathlib gain the leaders measured at 0.1–0.3%.

**Proposed fix.** Sum heat across workers at the promotion test (a racy read every 4,096 entries is enough). Use two rent levels from the measured costs: about 0.5 G for a no-inlining compile, about 2 G for an inlining recompile. Publish K2 trees process-wide and cap K2 derivation at 0.2% of executed instructions for the run.


### C2.12 [minor] ISA.md §2.8 and §4.1 (OPEN cost 25), §2.4 (PRUNE, FRAME length), ARCHITECTURE.md §1.3 (Terms accessors)

**Problem.** (1) OPEN probes FVAR(T, k) for k = 0, 1, ..; each probe is a hash-consing lookup (110 instructions measured), so OPEN costs 110–440, not 25, on every binder. (2) A FRAME is HOLE-padded up to the highest read index, so each tabling key and closure capture costs 45 per slot up to that index; under deep telescopes that is the binder depth, not the number of variables read. Sokonanoda's equivalent is 11.6% self time, and gruszka keys its infer cache on the mask plus the values at set bits. (3) Terms.kind/a/b/c each repeat the region dispatch, about five times per node visit.

**Proposed fix.** (1) Link the variables of one type through a free memo word of the FVAR record, keep the open count there, and put a small direct-mapped type-to-first-variable cache in front. (2) Measure highest-index+1 against popcount for tabled nodes on Init; above a ratio of 2, key tabling records on sparse (mask, values) frames. (3) Add a one-dispatch accessor (region array plus word offset) to Terms and use it in the generic judgments.


## Critic 3: soundness

**Verdict.** Not ready to implement as written: two blockers, both with small fixes. (1) `ls` is a positional level list without the parameter names, so shared terms of constants with different universe-parameter lists are conflated. (2) Published code objects can embed per-worker overlay term ids (theorem bodies). The admission pipeline, canonical free variables (I-FV1..3 hold on every path I traced), the T/F/U discipline, NEG/POS scope and the reject-confirmation protocol look sound, and every arena reject category has a check that catches it. The main soundness-margin issues are static Prop facts derived through WHNF under generic variables, internal faults swallowed inside TRY, and racy multi-word static facts. Four divergences would wrongly fail valid terms: NSUC folding, big-literal identity, untabled CHECK, literal depMax timing. Nothing was run; research doc 10 and spikes/machine/README.md did not exist, and the raw spike results are performance-only. Documents: docs/design/{ISA,ARCHITECTURE}.md.


### C3.1 [blocker] ISA.md §2.7 (also §2.2 closure/SUSP word c, §1.5 LSUB key, §4.3.2 DELTA "ls = levels of the head")

**Problem.** `ls` is defined as a bare, positional level-list id. Export terms are shared across declarations, so one lambda/Pi term can belong to two constants whose universe-parameter lists differ (for example [u,v] and [v,u]). `c1.{1,2}` and `c2.{1,2}` then give the same closure/SUSP id and the same `LSUB(level, ls)` cache key although they denote different instantiations: a wrong `T` by identity, the `bugs/closure-level-subst` family. A closure applied in K0 also has no way to find the parameter names.

**Proposed fix.** Define `ls` as the substitution id from `rlvm-level` `mkSubst(lparamNames(row), levelList)` (pairs names with levels, identity = 0, -1 = arity error) in every closure, SUSP, LSUB key, KVAL cell and block call. Add a regression export with two constants that share a body term under swapped parameter lists.


### C3.2 [blocker] ISA.md §3.1, §3.3 (pool and T operands), §3.4, §4.3.2; ARCHITECTURE.md §2.6

**Problem.** A theorem's value exists only as a proof block decoded into one worker's overlay (ids 0x40000000|i, recycled at session reset). `BODY(c)` is elaborated at the second unfold and published in the process-wide code table, so its `MKLAM T` and pool operands are overlay ids of the elaborating session. Any other session, or the same worker after a reset, executes whatever its own overlay holds at those indices: garbage terms and arbitrary verdicts. Init already needs theorem unfolding (R05 §6.8).

**Proposed fix.** v1: a role whose source term is not in the export or generated region is never published; it stays K0 or lives in a session-local code area dropped at reset. Assert at publication that every term operand is below OVERLAY_BIT. Later, let the environment thread promote hot theorem blocks into the generated region. Also elaborate only constants whose unit is ACCEPTED or ENV_READY.


### C3.3 [major] ISA.md §3.5 (`slvl`, `rlvl`, prop mask, PROOF), §4.4.1 c2/c4, §6.1, §6.3 B2/B3/B7

**Problem.** `slvl[i]` and `rlvl` come from `ENSORT(INFER(A_i))`, that is after WHNF under generic telescope variables, justified by "the substitution lemma". Lean's algorithmic def-eq is not substitutive: in the `bugs/proj-of-subst-prop` / `rec-of-subst-prop` family a sort K-reduces to Prop for a variable and is stuck for a closed argument, where the official `is_prop` throws. RLVM would skip argument pairs (B3) or treat a term as a proof (PROOF=2) where the official kernel rejects. R06 §8.5 warns against this and gruszka's S3 requires a syntactic sort. I found no way to derive False from it, since every "not a Prop" decision stays dynamic. Separately, `slvl` is never recorded for constructors, recursors and inductive types (no machine `DECL`), so the mask is empty for the heads policy APP targets.

**Proposed fix.** Record `slvl[i]` / `rlvl` only when the inferred type of the domain or result is a SORT record before any WHNF; otherwise "unknown" (position always compared, PROOF stays 0). State that c4 runs the dynamic `ISPROP` unless the fact is manifest. Compute SIG lazily by `INFER(type, ONLY)` for any constant below `envUnits`. Add mutation tests built from the three `*-subst-prop` exports with an axiom family.


### C3.4 [major] ISA.md §1.3 (`fault`), §1.4 (poll), §9 last paragraph

**Problem.** Inside a TRY every `fault` becomes `U` and checking continues. That includes E_HOLE (evidence of an under-approximated mask), E_VIS, failed scope audits and E_INTERNAL, so a declaration can still end in ACCEPT after a soundness invariant was seen broken. The firstFail and memory-watermark signals are not exempted either, so an abandon can be swallowed repeatedly.

**Proposed fix.** Two classes. Recoverable (kernel `fail`, budget, E_NATSIZE, E_LIMIT) become `U`. Fatal (E_HOLE, E_VIS, E_INTERNAL, audit failure, firstFail, memory) cut to `DECL` whatever `tryp` is: DECLINE and session reset.


### C3.5 [major] ISA.md §1.1 "Static facts", §3.5, §12 R6

**Problem.** Per-row fact arrays use `0 = not computed` with racy plain writes, but several facts span words and 0 is a meaningful, unsafe value: `dep[i] = 0` means "domain reads nothing" and `lamArity = 0` means "any spine covers". A reader that sees `unread` or prop data before `lamArity` or `dep` skips arguments of a partial application (`c A ≡ c B` for `c := fun α (x : α) => 5` is false) or a proof position that depends on a differing unread one. 64-bit masks in racy slots also contradict R11 C.3.

**Proposed fix.** Write all facts of a constant into one immutable record and publish it with a single release store of its index, as for code blocks. Readers load that index first (acquire) and treat 0 as "no facts". No fact is read from separately published words.


### C3.6 [major] ISA.md §4.5.5 IPROJ (and §3.2 `readsField`)

**Problem.** The Prop rule is worded "field j is read by a later field". The official test is `has_loose_bvars(binding_body(r))`: the rest of the constructor type, including the indices of the result type. As written, `x.1` on `inductive P : N → Prop | mk (n : N) (p : True) : P n` (the intent of tutorial `projIndexData2`) is accepted while the official kernel rejects. The case `i >= nfields` (tutorial 087) has no explicit failure either.

**Proposed fix.** Define `readsField[j]` as: the variable of field j occurs in any later field domain or in the constructor's result type (the `reads` fact of TELE). Add `i >= nfields → fail(E_PROJ)`. Put both cases in the per-instruction tests.


### C3.7 [major] ISA.md §2.1, §2.3 (WHNF flag), §4.2 NSUCC, §4.3 NATFOLD, §4.3.4

**Problem.** NSUC carries the WHNF flag and NATFOLD handles only binary operations. The official `whnf` turns `Nat.succ a` into a literal whenever `whnf(a)` is a literal or `Nat.zero` (the unary case of `reduce_nat`). In RLVM `Nat.succ (f 2)` and `Nat.succ Nat.zero` stay NSUC for good, so `Nat.mod (Nat.succ (f 2)) 7` is not folded and falls to delta unfolding of `Nat.mod` / `div` / `gcd` / `pow`: timeouts or a false `F` on valid closed computations.

**Proposed fix.** NSUCC maps `Nat.zero` to literal 1. WHNF(NSUC(b,k)) is the literal n+k if WHNF(b) is a literal n or `Nat.zero` (with the size check), else itself, memoised in m0. Set the WHNF flag on NSUC only when the base is a non-literal WHNF. Tests: `Nat.ble (Nat.succ (f 2)) 7` and the same with `Nat.mod`.


### C3.8 [major] ISA.md §4.4.1 c0, §6.1 pass A, §12 R3; ARCHITECTURE.md §2.4 `LitPool`, §1.4; rlvm-nat `NatStore`

**Problem.** "Two literal refs differ, so F" (definitive, cached in NEG) needs content-canonical ids. Three designs coexist. ISA assumes canonical rlvm-nat ids from 2^31. ARCHITECTURE's `LitPool` has `addNat` / `addString` without interning and a second worker pool with negative indices. rlvm-nat's `NatStore` interns but only over a frozen base (overlay ids start at `base.nextId`), which pipelined loading violates and for which no snapshot rule exists. Result: equal numbers with different ids (false F on proofs like `2^64 = 18446744073709551616`; mathlib has 1,820 such literals), or colliding ids over an unfrozen base (false T).

**Proposed fix.** One owner: `NatStore` base plus per-worker overlay for all values from 2^31 and for strings, under the same snapshot/reset rule ARCHITECTURE §3.5 applies to levels (batch mode until then). Remove the naturals from `LitPool` or make it that store. Until this is guaranteed, c0 compares content on an id mismatch before answering F.


### C3.9 [major] ARCHITECTURE.md §2.1 (literal `depMax`), §2.7, §2.8, §4.8

**Problem.** The loader computes `depMax(natlit) = specials.natLiteralRank()` when it writes the node, but `Specials` is written by the environment thread, which runs behind the loader (in batch mode, after the whole load). Shared literal nodes are therefore stamped UNRESOLVED for good, depending on timing. Every root containing them takes the exact-walk slow path, batch and pipelined runs differ, and ISA §5.4's argument that `depMax` covers a literal's prerequisites loses its premise.

**Proposed fix.** Split `Specials`. The loader binds name to cid and rank when the name is claimed (published with `loadedUnits`); literal `depMax` is the largest rank of the prerequisite names. Guard states stay with the environment thread (published with `envUnits`); INVALID declines at use.


### C3.10 [major] ISA.md §4.5.2 CHECK, §4.5.4 TARG (B9)

**Problem.** `CHECK` of a lambda or of a constant-headed application neither reads nor fills the SUSP `TYPE` memo; only `INFER` is tabled. A shared argument node is re-checked at every use: 2^n on `perf/app-lam` (shared lambda arguments, depth 19,999) and on any DAG-shaped proof whose shared nodes are reached with an expected type.

**Proposed fix.** For `tabled(t)`: on a check-stamped `TYPE(s)` hit, return `CONV(TYPE(s), V)`. After a successful CHECK store `PII(t, frame)` for a lambda and the telescope's own result `R` for an application, with the PERM stamp. Never store the expected type `V`: it may mention variables outside t's frame (I-FV3).


### C3.11 [major] ISA.md §3.6 (K2), §8.4 (T2): to settle before v2 is enabled

**Problem.** (a) Derivation runs the real WHNF over "opaque slots", but only K, structure eta and Nat folding are named as disabled. Other steps that read a slot's type (VTYPE, ISPROP, proof irrelevance, eta, unit-like), writes to POS/NEG/memos, and a `U` outcome are unspecified. (b) K2 blocks are published process-wide although derived from one session's values. (c) A cut of `W` (exhaust, fail, abandon) raised while compiled roots are on the host stack has no unwind status, so compiled code would keep writing above `wp`.

**Proposed fix.** (a) Slots are a dedicated value kind; any type-directed judgment on a value containing a slot, and any `U`, abandons the derivation; no pair-table writes while deriving (nano-lean S9, R05 §6.12). (b) Templates may reference export or generated terms only. (c) Add an UNWIND return status that every nested compiled activation propagates before the stepper continues.


### C3.12 [minor] ISA.md §2.4 PRUNE / FRAME, §5.1

**Problem.** For masks with bit 63 the frame keeps `runRest`, but "interned by content" does not say that `runRest` is part of the key. If only the 63 near slots are hashed, closures and SUSP memo entries that differ in a variable at index 63 or above are identified.

**Proposed fix.** State I-ID for far frames: key = (near slots, `runRest` run id). ENV runs referenced as `runRest` are never reclaimed within a session.


### C3.13 [minor] ARCHITECTURE.md §0.6 versus §6.6 and §4.1 I6

**Problem.** Two verdict rules are given: "REJECT iff some rejection was confirmed" and "the first failure in file order". A final DECLINE of unit 5 cancels later units, so whether a rejecting unit 9 was already checked depends on timing. The same bad file can end in REJECT or DECLINE with different worker counts. Neither answer is wrong, but the verdict is not schedule-independent.

**Proposed fix.** Pick one deterministic rule. For example: only a confirmed REJECT or an input failure cancels later units, a DECLINE does not; the verdict is REJECT if any unit rejects, else DECLINE if any declines.


### C3.14 [minor] ISA.md §1.4 TRY frame, §1.5 pair tables, §4.2 EVAL let, §4.4.1 c0/c1, §6.3; ARCHITECTURE.md §2.1, §4.2, §11

**Problem.** Small gaps. (1) `EngineConfig.strict`, required by the re-check protocol, is not defined in ISA. (2) NEG is read under ER although it is written without it. (3) A probe that ended `U` through `fail` still enters FAILED, so the official same-head comparison that would re-raise the error is skipped. (4) TRY does not save `xp`. (5) Pair keys `(min << 32) | max` with immediates (bit 31 set) need unsigned widening, or all pairs with one literal collide. (6) Unbound CONST nodes (`~nameId`, late-bound) are not handled by the machine. (7) `looseRange` saturation must be sticky. (8) Inert let values lose the LET/OPEN mark (`perf/let-fold`).

**Proposed fix.** (1) strict = K0, the complete procedure of §6.3 rule 3, one-declaration sessions, no PCHK, no published code or facts. (2) Skip the NEG lookup when ER is set. (3) FAILED only for F or a budget `U`. (4) Add savedXp. (5) Mask with 0xFFFFFFFFL and unit-test it. (6) Resolve through `NameTable.constOf` with a rank test, or DECLINE; never REJECT. (7) LOOSE_SAT roots take the exact walk, and `GET` is bounds-checked in all builds. (8) Wrap every let value in a LET-flagged SUSP.
