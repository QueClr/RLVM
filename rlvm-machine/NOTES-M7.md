# NOTES-M7 - K1 elaborator, code stepper, code store, MATCH, comparison plans (owner: M7)

**Read section 8 first** (wave 6, 2026-11-24 afternoon): what code objects are for, measured on the native
image; the tier hook; case trees that inline matchers; which switches are ready. Sections 1 to 7 are the
state of wave 4 (the morning) and are kept as the description of the stepper, the blocks and the tests; where
section 8 supersedes a statement it says so.

Files: `Elab`, `Code`, `CodeStore`, `Match`, `Cong`, `CodeShow` (claimed: the listing printer), `Tier`
(claimed: the hook of the compiled tier and the heat accounting); tests `M7*.java`. **Nothing of this package runs in v0**: every entry point answers "walk the term" (0) while
its staging switch is off (`SW_FACTS` for plans, `SW_TELE` for telescopes, `SW_CODE` for bodies / rules /
closures, `SW_CASETREES` for RULES) and in strict mode (`M7ElabTest.nothingRunsInV0`); the callers in
`Eval`, `Reduce`, `Rules` and `Infer` test the same switches before they call (section 4).

## 1. The stepper under convention H7, and the other deviations from ISA.md

ISA 8.2 shows the stepper as a family loop over an `ACT` frame in `W` (candidate (i)). Under the decided
convention (stepper-convention.md H7) it is written as follows.

| # | ISA | As built | Reason |
|---|---|---|---|
| D1 | registers are words of the `ACT` frame; arguments are addressed in `argRun` "without copying" (1.3, 2.4, 3.3) | an activation is a window of the register stack `m.RS`: `RS[rb-3] = capRun`, `RS[rb-2] = ls`, `RS[rb-1] = xp` at entry, then the arguments, then the registers. The arguments are **copied** into the window at entry (`Code.enter`); operand modes A and R both index the window, mode C a slot of `capRun`. `argRun` of the frame is always 0 | a frame has no address until it is written (H7); the callers hold arguments in three places (a spine run for DELTA, scratch for APPLY and IOTA, nothing yet for TELE, whose `g_i` are produced one by one). One copy of `arity` words buys one addressing mode and makes the block independent of the caller's scratch, so the caller may suspend freely |
| D2 | chunk directories installed by compare-and-set (3.3) | readers are lock-free (acquire loads, one release store per block id and per row slot); writers serialise on the store's monitor | a publication is a copy of a few dozen words; the monitor gives the same reader-side guarantees and makes "one block per role" (`publish` is first-wins) and "one sub-block per binder" (`addSub` checks the node hint under the lock) true by construction, which is what makes two workers build word-identical blocks |
| D3 | `Code.run(m, blk, pc, rbase, d)` is entry and continuation step | kept. The dispatch loop is `Code.resumeAt` (a `resume*` name: a family's dense switch is exempt from the 325-bytecode step limit, H10 rule 2); every instruction body is a small method. The entry step (H4: tick, call event, window, depth test) is `begin` + `go`; a spilled entry is the frame `ACT[.., dst = DST_ENTRY, ..]` and is ticked again when the driver resumes it | H4 |
| D4 | `TARG i, flags, dom` is one instruction with several sub-judgments | phases of opcode `TARG` in the `x` byte, each calling at most one judgment: `TARG.INFER i, Lbind, Lnext` (Tt = INFER(t_i); ONLY mode: bind and skip); the domain code, preceded by `MATCH Tt, T(dom), i, Lbind` when the domain is an open constant application; `TARG.CONV i, D` (CONV with the ER rule of `eagerReduce`, else `fail(E_APP_MISMATCH)`); `TARG.BIND i` (g_i = read later ? ARGV(t_i) : HOLE). `TARG.EXPECT T(result), n` is the result-pattern shortcut of TRET (B5). The site (argument terms in X, env, ls, mode, expected) lives in 8 site registers after the arguments | under H the state of an activation is its pc. The frozen opcode set is unchanged |
| D5 | `CONG` is elaborated rent-to-own (3.1) | a plan is built at the first `Cong.planOf` once the fact record exists (the caller triggers `Facts.sigd`) | a few dozen instructions per position, no source term to walk |
| D6 | leaves of a case tree are `p ȳ` (3.6) | any term is a leaf, compiled in tail position | the soundness argument of 3.6 does not depend on the leaf's form; `casesOn`-style heads are recognised by analysing the head definition (`Elab.casesOnInfo`), so matchers over any non-K, non-structure inductive type are trees |
| D7 | `RULE(r, j)`: one block per rule | one block per recursor with one entry per rule (entry word 0 = that rule is walked); arity = the largest rule arity | one publication per recursor; the block is a function of the store |
| D8 | sub-block "entry per number of applied lambdas" (3.4) | entry j - 1 = "j binders applied"; j below the number of leading lambdas returns the closure of the rest, which is walked. Chains longer than 16 keep the first 16 in the block. The Pi that remains after a partial application of a TELE gets no sub-block. A body of more than 64 nodes gets none (3.4) | bounds the code per binder; a block per telescope suffix tripled the code of Init.Prelude's telescopes (12,884 blocks against 7,698) |
| D9 | a block's level pool (3.3) | absent: every level and level list is an operand L / LL holding the id found in the term's own node, a base id; `pubcheck` verifies it against `LevelStore.baseSize / baseListCount` | the elaborator consults no fact and derives no level |
| D10 | `KVAL d, k` with a pool index | the operand is the closed term (mode K); `x` bit 0 = the node stood in an argument position: the value is then `ARGV` of it (a suspension for an active term), as the generic walk builds it; `x` bit 1 = the term mentions a level parameter (the inline-cache key is then `ls`, else 0) | decision 6 |
| D11 | LEAF blocks run "without a frame" (B11) | LEAF is a header flag; under H no activation writes a frame unless a sub-judgment is suspended | H1 |
| D12 | operand reads | `Code.val` reads as the generic walk reads a variable, `PEEK(GET)` (`Eval.atom`), and faults `E_HOLE` on an argument or captured slot that holds HOLE; a capture list copies the binding raw (`Code.raw`), as `PRUNE` does. A register may hold HOLE (`BUNREAD`) and pass it to an APPLY | the frames and values of both tiers are then the same records (verified, section 5) |
| D13 | TAILAPPLY / TAILCALL overwrite the own frame | TAILAPPLY is a call followed by the pop of the window (continuation `DST_RET`) | its arguments are scratch of the activation (H3: a tail return may own no scratch) |
| D14 | `BUNREAD f, i, d, L` | `BUNREAD d, f, i, n, L`: n = the number of arguments the following APPLY passes | APPLY binds `j = min(leading lambdas, n)` binders and tests the body after j of them (4.3.1); the same test needs n |
| D15 | `SWITCH s, L0..Lk-1, Lx` | `SWITCH s, I, L0.., Lx`: the inductive type is an operand; a value that is not a constructor of I applied to exactly its parameters and fields goes to Lx (also a big-number or string literal, and a numeral when the Nat guards are not VALID) | an ill-typed or stuck major must take the DELTA path |
| D17 | `let -> MKSUSP(value, LET) always (2.8)` (3.4) | a let value is compiled in position ARG (an atom, an inert application built at once, a plain suspension of an active term); `MKSUSP` keeps its LET bit but the elaborator does not emit it | M2 changed the generic EVAL of a let to exactly that on 2026-11-24T10:26Z (NOTES-M2 D9: the LET suspension is INFER's; flagging the lets of an evaluated term made values OPEN and switched off Nat folding in lazy delta where the official kernel folds). The tier test showed it at once: 23 of 24,861 cases differed (a LET suspension against the closure in a captured slot) until the elaborator followed |
| D16 | probe `ELAB_AT` is `Op.ELAB_AT` | `CodeStore.elabAt` (reduction roles, default 1 since wave 6) and `CodeStore.teleAt` (TELE, default 10); system property `rlvm.machine.elabAt` (1..15) sets both | the sweep of ISA 3.1; section 8.4 |
| D18 | `tree ::= I.casesOn .. | I.rec ..`, leaves `p ȳ` (3.6) | a tree node may also be `D args` for a definition `D` that has a case tree of its own: `D`'s tree is compiled in place with its parameters bound to the argument terms (`Elab.inline`, section 8.3); an eta-reduced minor is a leaf | the hot shape of every definition by pattern matching; the same composition of official steps |
| D19 | RULES is used by WHNF only (3.6) | also by the unfolding step of lazy delta for shape CASESON (`Code.rulesCasesOn`, switch `m7a`): `I.casesOn` itself, where no state of the official loop lies between the DELTA and the iota of one step | section 8.5 |

Frames of this family: `ACT [blk, pc, dst, 0, capRun, ls, rbase]` (`Op.ACT_*`; `dst` < 256: the window
index that receives the awaited result; otherwise `DST_RET`, `DST_ENTRY`, `DST_NONE`, `DST_REQ_APP`) and
five continuations of judgments whose K1 half lives here: `ACT[1](v, k)`, `ACT[2](xb)` (RULES),
`ACT[3](v, ar)`, `ACT[4](v, xb)` (DELTA through BODY), `ACT[5](xb, n, total)` (IOTA through RULE).

## 2. Blocks and instructions as built

```
block   hdr0 = len:20 | nreg:8 | role:4     hdr1 = owner row (0 for a sub-block)
        hdr2 = arity:12 | flags:12 | nentry:8          flags: LEAF 1, SUB 4, shape << 4 (PICK, PROJARG, CTORBODY, CASESON, MATCHER)
        hdr3 = 0 (sub-block: its binder term; CONG: the facts index)
        entry table: BODY 1 entry; RULE one per rule; RULES 1; TELE arity + 1 (0 = first TARG, k = "return the
        remaining Pi after k arguments", arity = the result code); sub-block one per number of binders applied
instr   op:8 | d:8 | n:8 | x:8, then n operands mode:4 | payload:28 (Op.MODE_*)
        MKAPP d, h, a..        KCONST d, D, LL       KSORT d, L        KLIT d, T        KVAL d, K
        MKLAM / MKPI d, T, B, c0..ck      MKSUSP d, T, c0..ck (x: LET)      MKPROJ d, D, I, s
        MOV, PEEK, NSUCC d, s      FIELDS d0, s (x = n)      JMP L      BEQ / BNE a, b, L      BKIND s, I(set), L
        BUNREAD d, f, I(i), I(n), L      SWITCH s, D(I), L0.., Lx      MATCH s, T, I(n), L      RET s
        APPLY d, f, a..      TAILAPPLY f, a..      WHNF / FORCE d, s      TARG (four phases)      TRET s
CONG    hdr2 = npos | PROP_STATIC; word 4 = lamArity; per position: class | UNREAD | rank, dep low, dep high
```

The elaboration scheme is the comment of `Elab`: three positions (TAIL, EVAL, ARG) that mirror EVAL and
ARGV of ISA 4.2 as M2 implements them, so that both tiers build the same records.

## 3. Listings of ISA 3.7 as reproduced (`M7ListingTest`, from `init-prelude.ndjson`)

Differences from the text of 3.7:

- capture lists show every slot up to the highest bit of the node's mask: `[A0, _, _, A3]` for the minor
  premise of `Nat.casesOn` where the ISA printed `[A3]` (the lambda's second domain reads the motive);
- every entry of a sub-block is printed (`B1 (1 applied)`, `B1 (2 applied)`);
- `BODY(Nat.casesOn)` is LEAF by the rule of 3.4 (four class-p instructions); the ISA omitted the flag;
- `RULES(Nat.casesOn)` is the ISA's line with the inductive type as an operand of `SWITCH`;
  `RULES(Nat.add.match_1)` is the one-level tree of 3.6 (`TAILAPPLY A3, A1` / `FIELDS; TAILAPPLY A4, A1, r1`);
- `RULE(Nat.rec, succ)` is the ISA's line with the two registers numbered in emission order;
- `TELE(congrArg)`: `TARG` is the group of D4; the active arguments `f a₁`, `f a₂` of the result are
  `BKIND g4, neutral / MKSUSP / MKAPP` groups, the K0-equivalent form (decision 8), where the ISA printed
  `APPLY` (the strict form of open question 1; `Elab.State.strictArgs` emits it; off, it changes value ids);
- `CONG(congrArg)`: positions 2-4 are `PROOFL` (their sorts `u`, `u`, `imax u v` are manifest and can be
  zero: `congrArg.{0,0}` compares proofs there), which is what 6.1 and 3.5 specify; the listing printed
  `SAME`. `CONG(HAdd.hAdd)` is the listing's. Probe ranks follow the rule of 6.1 (for `HAdd.hAdd`
  position 5 is probed before 3; the remark under the listing says 3). Both plans are reproduced twice:
  from a hand-made record of the layout of 3.5 and from the record M5's `SIGD` derives;
- class TYPE: "the domain is a sort or a Pi telescope that ends in one" (6.1), **or** its sort is
  manifest and of the form `l + 2` (the domain lives two universes up: `outParam (Type w)` in
  `HAdd.hAdd`, which the listing classifies TYPE). Both are compare classes; only the probe order differs;
- the `slvl` / `rlvl` part of the TELE header is M5's fact record and is not printed.

## 4. How the other packages call K1 (all in the main tree since 2026-11-24T10:41Z)

| Caller (owner) | Call | Switch |
|---|---|---|
| `Reduce.delta` (M3): BODY on the spine, then APPLY of the arguments beyond the arity; `Reduce.iotaRule`: RULE on the rule's arguments | `Code.blockFor(row, ROLE_BODY / ROLE_RULE)`, `Code.arityOf`, `Code.hasEntry`, `Code.callRun`, `Code.callRule` | `SW_CODE` |
| `Rules.rules` / `Rules.unfold` (M3) | `Code.rules(m, v, d)`: the complete RULES judgment | `SW_CASETREES` |
| `Eval.applyFrom`, `Eval.instBody` (M2) | `Code.closureBlock` (kept in the CODE memo), `Code.arityOf`, `Code.hasEntry`, `Code.callClosure` | `SW_CODE` |
| `Infer.inferApp` (`teleApp`), `Infer.vtypeNode` (`teleNeu`) (M5) | `Code.blockFor(row, ROLE_TELE)`, `Code.tele`, `Code.teleValues`, `Code.arityOf`; the arguments beyond the block's arity continue in the generic TWALK / NEU loop | `SW_TELE` |
| `Cargs` (M4) | `Facts.sigd` (derive), then `Cong.planOf` + `Cong.words / base / positions / lamArity / classWord / dep / propStatic` | `SW_FACTS`; **not wired, and not to be wired as specified** (section 8.6: skipped positions are not the official verdict) |
| `Eval.codeArity` (M2, since 14:54Z) | `Code.entryFor(m, blk, n)`: the entry of a closure's block for n arguments | `SW_CODE` |
| `Conv.unfoldT / unfoldS` -> `Rules.unfoldLazy` (M4, M3, since 15:05Z / 15:3xZ) | `Code.rulesCasesOn(m, v, d)`: the CASESON tree as the unfolding step of lazy delta | `SW_CASETREES` + `m7a` |
| a compiled tier (`rlvm-truffle`, T2) | class `Tier` (section 8.2) | `t2` |

So `-Drlvm.machine.switches=code,tele,casetrees` runs K1 in the main tree. One rule for every caller of a
block, learnt the hard way while the TELE hook was being re-anchored on M5's new TWALK: **capture `m.ep`
(and any other stack mark) before the call**. A block that is suspended leaves the environments of its
sub-judgments on `es` (H7), so a continuation frame written with the `m.ep` read after the call restores a
wrong mark; the declaration then ends with a machine that is not idle and is declined (`E_INTERNAL`, 9
units of Init, all on terms deeper than the host bound; never a wrong verdict). M5's published hook does
it right; `M7BenchTest.init` asserts that every configuration accepts the same units.

## 5. Tests and what they showed

| Test | Group | Content | Result |
|---|---|---|---|
| `M7CodeStoreTest` | fast | directory, row slots, first-wins publication, chunk boundaries, oversized blocks, the rent-to-own counter (fires once, at the 10th request; saturates at 15), 8 threads publishing 2,000 roles with readers checking completeness | green |
| `M7ElabTest` | fast | bodies, rules, telescopes of the hand-built prelude; determinism (two elaborations word-identical); `pubcheck` accepts every block and finds each of 13 planted violations at its word; overlay / proof-block / generated sources refused; nothing runs with the switches off; rent-to-own through `Code.blockFor`; hint validation | green |
| `M7MatchTest` | fast | MATCH rows per form; "MATCH = build + CONV" on all pairs of 12 patterns and their values; the 64-node budget | green |
| `M7TierTest.handBuiltPrelude` | fast | K0 = K1 value ids, D = 64 and D = 0 | 425 cases identical |
| `M7ListingTest` | default | the listings of ISA 3.7 (section 3) | green |
| `M7CorpusTest` | default / slow | every role of every constant of init-prelude, Init, Std elaborated; `pubcheck` on every block; proof-block and opaque values never elaborated; two workers build word-identical blocks and publish one block per role | green; sizes in section 6 |
| `M7TierTest.initPrelude` | default | K0 = K1 value ids on init-prelude: 1,441 bodies x level variants x over-application, every recursor rule, every sub-block applied to 1..k arguments, 687 case-tree cases, about 15,000 TELE cases (ONLY / CHECKED / VALUES, partial applications) | 24,862 cases: 24,655 identical ids, 0 different, 0 failures of one tier (207 ill-typed over-applications fail in both), D = 64 and D = 0; MATCH answers T on 2,432 of 2,440 own domain values |
| `M7HookedTest` | default (init-prelude) / slow (tutorial, bugs, D = 0, all 230, Init) | the machine with `code,tele,casetrees` against the reference engine through M8's `Diff`, ELAB_AT 1 and 10, and against the K0 run file by file | section 6 |
| `M7ProfTest` | slow (a tool) | JFR execution samples of the checking thread for a switch set on an export | section 6 |
| `M7BenchTest` | default (init-prelude) / slow (Init, cedar-hot-ax, reachability) | thread CPU time of one machine session over a whole export under switch sets; every set must accept the same units | section 6 |

## 6. Measurements (host JVM, C2/Graal as configured by the build; not the arena container)

Elaboration of everything (`M7CorpusTest`; rent-to-own would elaborate about 15% of it):

| corpus | constants | blocks (incl. sub-blocks) | code | TELE | BODY | RULE | RULES |
|---|--:|--:|--:|--:|--:|--:|--:|
| init-prelude | 2,121 | 7,698 | 1.3 MiB | 1,975 | 1,441 | 125 | 104 |
| Init | 59,979 | 124,411 | 41.8 MiB | 56,154 (8.56 M words, 152 words each) | 17,284 (56 words each) | 603 | 1,400 |
| Std | 103,885 | 215,164 | 88.1 MiB | 97,062 | 30,068 | 1,046 | 2,209 |

Elaboration time was not measured in a way that supports a number: `M7CorpusTest` reports wall clock per
constant tried, and three runs of the same code on this host gave 1.6, 5.3 and 12.9 us per TELE on Init
(0.6 to 1.3 us per BODY); Std, which runs first in the JVM and pays the JIT warm-up, 14.7 to 41.8 us. The
ISA's spike measured 3.0 us per telescope; these runs neither confirm nor contradict it.

CPU time of the machine on Init with and without K1 (`M7BenchTest.init`: one session, one thread, 58,547
units, the CPU time of that thread from the JVM, a fresh engine per pass so elaboration is inside the
time, v0 sessions (reset per declaration), warm-up pass discarded; host JVM, load average 70 to 160 while
measuring, so a difference below about 10% is not resolved; every configuration accepted all 58,547
units). The generic tier changed twice while K1 was being measured, and the answer changed with it:

| generic tier of | switches | ELAB_AT | CPU | ratio | blocks | activations | ticks |
|---|---|--:|--:|--:|--:|--:|--:|
| 09:55Z (PI record + ENPI + DOM + INST per argument) | none (v0) | - | 11.9 s | 1.00 | 0 | 0 | 93.6 M |
| | `tele` | 10 | 10.8 s | 0.91 | 11,721 | 5.0 M | 79.4 M |
| | `code,casetrees` | 10 | 10.0 s | 0.84 | 6,425 | 10.5 M | 82.3 M |
| | `code,tele,casetrees` | 10 | 8.3 s | 0.69 | 17,997 | 15.9 M | 68.3 M |
| | `code,tele,casetrees` | 1 | 8.3 s | 0.69 | 79,733 | 16.2 M | 67.2 M |
| 10:55Z (M5's telescope-mode TWALK and grouped VTYPE, M2's let fix), best of 5 | none (v0) | - | 8.35 s | 1.00 | 0 | 0 | 77.8 M |
| | `tele` | 10 | 9.18 s | 1.10 | 11,652 | 5.0 M | 78.0 M |
| | `code,casetrees` | 10 | 8.39 s | 1.01 | 6,425 | 10.4 M | 66.5 M |
| | `code,tele,casetrees` | 10 | 7.98 s | 0.96 | 17,930 | 15.7 M | 67.0 M |
| | `code,tele,casetrees` | 1 | 7.56 s | 0.91 | 79,765 | 16.1 M | 66.6 M |

Reading. Against the generic tier of the morning K1 saved a third of the CPU time on Init. M5 then gave
the generic TWALK what the TELE role is made of (it walks the syntactic telescope, no PI record, no ENPI /
INST per argument), which took the generic tier from 11.9 s to 8.35 s; against that tier the K1 roles
together measured 4 to 9% less CPU on Init and the body / rule / case-tree roles alone no difference
(1.01x, ticks -14%). `tele` alone measured 1.10x in that run, which is at the edge of what this host
resolves and was not reproduced elsewhere (1.00x on the Cedar slice below, 0.92x to 0.99x on init-prelude,
ticks equal): what can be said is that **TELE shows no CPU gain over M5's telescope-mode TWALK**, not that
it costs 10%. Why it does not gain is not established. One conjecture, not tested: the generic walk's
tabled EVAL of a domain answers from its memo where the block builds the domain again. The profile below
rules out the stepper's own overhead as the main term. This is not what the ISA's telescope spike
predicted (-7% to -28% of an instantiation pass). Init is the regression corpus, not the yardstick (owner
note 9). The Cedar slice (`cedar-hot-ax.ndjson`, 8,418 units, the same harness, best of 2, generic tier of
10:55Z; every configuration accepted all units):

| switches | ELAB_AT | CPU | ratio | session memory made | blocks | activations |
|---|--:|--:|--:|--:|--:|--:|
| none (v0) | - | 3.02 s | 1.00 | 817 MiB (1.00) | 0 | 0 |
| `tele` | 10 | 3.03 s | 1.00 | 0.97 | 5,148 | 1.33 M |
| `code,casetrees` | 10 | 2.99 s | 0.99 | 0.92 | 5,091 | 1.15 M |
| `code,tele,casetrees` | 10 | 2.93 s | 0.97 | 0.88 | 10,183 | 2.65 M |
| `code,tele,casetrees` | 1 | 2.90 s | 0.96 | 0.87 | 27,367 | 2.81 M |

K1 makes 12 to 13% less session memory (records, runs; a count, not disturbed by the load). The CPU
differences of 1 to 4% are inside this host's noise: no CPU gain is established on the Cedar slice. On init-prelude a TELE's MATCH
answers T 2,371 times and "unknown" 239 times; the argument's type is the built domain itself 22,318 times
and CONV is called 3,328 times (`Code.Ext.matchT / matchF / targSame / targConv`).

Closed evaluation is where K1 pays: `reachability.ndjson` (4,413 units, the same harness, one measured
pass after the warm-up, generic tier of 11:10Z; every configuration accepted all units):

| switches | CPU | ratio | session memory made | ticks | blocks | activations |
|---|--:|--:|--:|--:|--:|--:|
| none (v0) | 6.68 s | 1.00 | 1,369 MiB (1.00) | 34.6 M | 0 | 0 |
| `code` | 3.65 s | 0.55 | 0.60 | 29.1 M | 1,721 | 7.97 M |
| `code,casetrees` | 2.26 s | **0.34** | 0.38 | 16.5 M | 1,770 | 4.43 M |
| `code,tele,casetrees` | 2.25 s | 0.34 | 0.37 | 16.5 M | 3,290 | 4.63 M |

Bodies and rules as blocks halve the CPU time, the case trees (B8: the motive, the unchosen alternatives
and the `casesOn` / `rec` applications of a matcher are never built) take it to a third. This is the ISA's
prediction (0.1 "(b) the reduction roles carry the tail", "(c) derived rules matter on closed evaluation
only"; nano-lean's rules were worth 1.9x there).

Where the time goes with `tele` on Init (`M7ProfTest`: JFR samples of the checking thread, 2,056 samples;
`work/status/machine-M7-prof-init-tele.txt`): self time `Runs.pruneNear` 18%, `Values.closure` 17%,
`Values.neu` 13%, `Pairs.status` 6%, `Machine.reset` 5%; `Code.resumeAt` 1.2%, all of `Code` and `Match`
together about 2%. So about half of the samples are in interning, and the stepper's own code is a small
share. `Eval.table` (the tabling probe of the generic EVAL) is 21.5% inclusive. The callers of
`pruneNear` / `closure` / `neu` were not broken down, so how much of the interning belongs to the tabling
of proof terms (which no code role of a constant touches) and how much to values the blocks themselves
build is an inference, not a measurement.

Not measured: the arena container, Std, warm sessions (step 1), gated tabling (which removes most of that
tabling and will change these ratios again).

Verdicts with K1 (`code,tele,casetrees`, ELAB_AT 1 and 10, M8's differential runner):
tutorial 141 files and bugs 34 files / 1,147 checks DIFF-CLEAN, also under D = 0 (`M7HookedTest`);
all 230 published tests on the main tree of 11:10Z (`report.sh -Dm8.switches=tele,code,casetrees
-Drlvm.machine.elabAt=1 all`, `work/status/machine-diff-all-M7k1.txt`): no wrong verdict, 229 files
clean, 1 declined (`good/perf/loop-unfolded-once`, E_MEM: 2^28 run words; v0's report of 10:47Z declines
the same file the same way), 17,492 of 17,493 units agree, the machine rejects 41 declarations and the
reference rejects the same ones; Init, the Cedar slice and reachability: every unit accepted by every
configuration of the tables above; M8's
convention report with K1 on tutorial: EQUIVALENT across D = 64 / 0 / unbounded; M8's tiers report on
tutorial: EQUIVALENT (175 constants, 700 results).

## 7. Not done, shortcuts, open points

(Wave 4 list; section 8.6 is the current one.)

- `Cargs` does not read comparison plans yet (M4): `Cong` is tested on fact records only.
- `FIELDS(S)` (ISA 3.2, for IPROJ) is not elaborated; IPROJ stays generic.
- `B9` inside TELE (CHECK of a lambda argument against the domain) is not implemented: TARG always infers
  and converts (the admissible fallback).
- `TARG.EXPECT` (the result-pattern shortcut) is emitted and implemented, but no caller passes an expected
  type yet (the hook passes NONE), so it has only been exercised as a no-op.
- Terms of the generated region cannot be operands (28-bit payload): a role that mentions one stays generic.
- Tier equivalence of value ids is "up to PEEK" in principle (the generic walk itself is path-dependent
  when a suspension's value becomes known between two reads); on the corpora above every compared id was
  identical. A static-beta binder aliases its argument's operand instead of copying the PEEKed value.
- TELE blocks are large (152 words on Init): the partial-application entries are a third of that.
- The IC table (`m.ic`) is used by KVAL and, since wave 6, by KCONST of a level-polymorphic constant; a site
  beyond pc 4095 bypasses it.
- Heat counters per block (ISA 8.3): since wave 6 `Tier.heat(engine)` (section 8.2); off unless asked for.
- `dumpStats` of the engine does not print the code store's numbers (M6's file).

## 8. Wave 6 (2026-11-24, 13:25Z - 16:15Z): what code objects are for

Everything in this section was measured on the CE native image (`-O3`, built by `work/m7/mkdist.sh`: the pipeline
jars of the integrator's `work/machine-int/dist-g-native`, the machine classes of the M7 sandbox) through the
launcher, in the arena-shaped container (`work/m7/m.py`, a copy of the integrator's tool writing
`work/m7/measure.tsv`; batches `work/m7/batch{0,2,3}.sh`, each under one hold of the measurement lock). The host
ran 15 to 30 other agents' builds and runs the whole time (load average 150 to 230 from 14:00Z on), so CPU seconds
are comparable inside one batch only; instructions per run (`m.py --free`: the same container without the lock
and without the pinned cores) are the stable signal and are given beside them. One worker unless said otherwise.

### 8.1 Answer in one table

Batch 3 (15:57Z-16:04Z, load average 69 to 93; `work/m7/dist-f` = the main tree of 15:11Z; CPU seconds of the two
runs of each row, instructions per run in G; `work/m7/table.py b3-` prints it from `work/m7/measure.tsv`):

| configuration | workers | reachability | Cedar slice | Init | Std |
|---|--:|---|---|---|---|
| v0 (no switch) | 1 | 9.04, 8.53 (58.3 G) | 2.64, 2.61 (38.9 G) | 7.46, 7.49 (132.9 G) | 12.66, 12.30 (212.4 G) |
| `code,casetrees`, trees without inlining (`-Drlvm.machine.m7.inline=off`) | 1 | 3.08, 2.97 (27.6 G) | 2.46, 2.50 (38.1 G) | 6.55, 6.37 (111.0 G) | 10.89, 10.78 (181.7 G) |
| **`code,casetrees`** | 1 | **1.54, 1.58** (17.8 G) | **2.55, 2.46** (37.9 G) | **6.25, 6.27** (108.2 G) | **10.90, 10.65** (177.8 G) |
| `code,casetrees,tele` | 1 | 1.69, 1.56 (17.8 G) | 2.53, 2.50 (38.2 G) | 6.53, 6.31 (106.8 G) | 11.07, 10.78 (176.2 G) |
| step 1: `warm,gated,expect`, 4 MiB | 1 | 8.47, 8.14 (59.9 G) | 2.65, 2.68 (38.2 G) | 7.32, 7.21 (104.1 G) | 12.78, 12.20 (175.8 G) |
| step 1 + `code,casetrees` | 1 | 1.51, 1.55 (17.3 G) | 2.54, 2.60 (37.1 G) | 6.21, 6.02 (87.3 G) | 11.27, 11.08 (150.3 G) |
| v0 | 4 | 8.29 CPU, 8.13 wall | 2.87, 1.13 wall | 7.48, 2.53 wall | 12.40, 4.19 wall |
| `code,casetrees` | 4 | 1.62 CPU, **1.52 wall** | 2.66, **0.98 wall** | 6.31, **2.28 wall** | 10.73, **3.75 wall** |
| step 1 + `code,casetrees` | 4 | 1.73 CPU, 1.70 wall | 2.71, 1.00 wall | 6.22, 2.22 wall | 11.19, 3.88 wall |
| for comparison (CPU s, one worker, the integrator's quiet batch G and the brief): reference engine / gruszka / nano-lean | 1 | 12.19 / 3.97 / 2.08 | 3.90 / 1.91 / 2.95 | 13.82 / 2.63 / 4.46 | 24.31 / 5.62 / 8.86 |

Peak RSS with `code,casetrees` (one worker): reachability 311 MiB (v0: 1,151 to 1,348), Init 203 MiB, Std 348 MiB,
Cedar slice 182 MiB. Every row: the machine accepted every unit it was given, 0 declined (4,224 / 8,064 /
57,938 / 100,096).

Reading: the reduction roles (BODY, RULE, case trees, closure sub-blocks) are what code objects are for. With
the trees of 8.3 the machine checks reachability in 1.54 to 1.58 s of CPU (gruszka on this host: 3.97 s, nano-lean
2.08 s; the reference engine 12.2 s) and saves 16 % on Init and 14 % on Std; on the Cedar slice, where
82 % of the work is inference and conversion of open terms, they save 5 %. TELE as built gains nothing
(8.5). Nothing of it changes a verdict (8.7).

### 8.2 The tier hook and heat (class `Tier`; ISA 8.3, 8.4)

Published 13:39Z in the shape the Truffle agent asked for (requests.log 13:30Z):

- `public abstract class Tier { public interface Factory { Tier create(Machine m); } public static Factory
  factory(); public abstract int run(Machine m, int blk, int pc, int rb, int d); }`: one `Tier` per `Machine`,
  made by the factory found through `ServiceLoader` (`META-INF/services/rlvm.machine.Tier$Factory`), once, at the
  machine's first declaration, **only if the machine runs with switch `t2`** and never in strict mode
  (`Tier.beginDecl`); `Tier.set(m, tier)` / `Tier.of(m)` for tests and wrappers.
- The stepper offers every activation to the tier: in `Code.go` after the depth test (an activation that is too
  deep becomes the frame `ACT[.., DST_ENTRY, ..]` first and is offered when it is resumed) and in `Code.run`
  (every resume: the awaited result is already in the window word the frame named, so the window is the whole
  state). `run` returns the result or the signal with the effects of `Code.resumeAt`, or `Op.MISS` before it
  changed anything.
- What a tier may call: `Tier.interpret(m, code, blk, pc, rb, d)` (the stepper; `code` = `Tier.block(m, blk)`, a
  copy of the block with base 0), `Tier.step(..)` (ONE instruction through the stepper's own body
  `Code.resumeOne`: the next pc, or `Tier.DONE` with `Tier.result(m)`), `Tier.reg / setReg / operand / cap / ls /
  suspend / leave`, `Tier.blockCount / describe`. For a bridge class in package `rlvm.machine` the helpers
  `Code.val, raw, isNat, ctorIndex, icGet, susp` are package-private and their signatures are frozen (a change
  is announced in requests.log first).
- Heat is independent of a tier: `Tier.Heat h = Tier.heat(engine)` switches the accounting on for the engine's
  sessions: entries per block and session (`int[]`, no shared word written), one entry in 64 timed with
  `System.nanoTime` (inclusive, and "self" = without the blocks entered inside), `h.entries / nanos / selfNanos /
  timed / spentNanos / report(top)`, `h.listen((heat, m, blk) -> .., 4096)` on the worker thread (ISA 8.3).
- Cost when nobody asked: one field load and test per activation (`m.x7.hooked`). Batch 2: Init, code,casetrees:
  7.12 s on the integrator's image, 7.21 / 7.13 s with the hook; Std 12.38 against 12.25 / 12.47; no difference.
- Tests: `M7TierHookTest` (fast): a tier that steps every activation with `Tier.step`, one that takes every second
  activation and answers MISS on the others, and one that calls `Tier.interpret`, give the K0 value ids of
  `M7Tier` under D = 128 and D = 0; every activation is counted once; without `t2` and without `Tier.heat` a
  session is not hooked.
- `M7HeatTest` (a tool, slow group): the heat report of an export
  (`work/status/machine-M7-heat-<export>-<switches>.txt`). Init, code,casetrees, threshold 10 (before 8.3):
  6,425 blocks / 869 KiB; **293 blocks (34 KiB) take 92.8 % of the 10.4 M entries**, 885 blocks 98.3 %; 3,795 of
  the blocks are closure sub-blocks (440 KiB), 822 of them never entered. Reachability: 1,770 blocks / 213 KiB;
  43 blocks take 95.9 % of 4.43 M entries. The hot set is what R13 says: a few hundred blocks of the core
  library (`List.rec`, `List.casesOn`, `*.match_1`, the `._f` functionals, `Int.add`, `Decidable.casesOn`).

### 8.3 Case trees that inline the matchers they call (kept; part of `casetrees`)

The heat report showed the pattern behind most activations: a definition by pattern matching is
`f := fun .. => f.match_1 motive x (fun fields => rhs1) (fun fields => rhs2) ..` (also the `._f` functionals of
structural recursion, which are over-applied: `.. f`). K1 as delivered ran three activations per unfolding
(`BODY(f)`, `RULES(f.match_1)`, the alternative's sub-block) and built the motive closure, every alternative's
closure and the matcher application to throw all but one away.

`Elab.inline` (with `leaf`, `leafApply`, `betaLeaf`, `resolve`): a tree node `D a_1 .. a_n` whose head is a
definition with a case tree of its own (`fun p_1 .. p_q => tree`, `n >= q`) is compiled in place. `D`'s
parameters are bound to the argument TERMS (`DEFER` environment entries: term + the environment region it is a
term of + its owner), which are compiled only where the tree uses them: a major premise that is a parameter is
`ARGV` of its argument (compiled once at the node, where it dominates every arm); a leaf `p ȳ` whose `p` is
bound to a lambda of the caller is a static beta (ISA 3.4 "app, lambda head"): the right-hand side is compiled
in tail position, in a copy of the caller's environment, with its binders bound to the FIELDS registers (HOLE
for unread ones, B6), as a nested tree if it is one; arguments beyond `q` are applied to the leaves. An
eta-reduced minor (`Nat.casesOn x a b`) is a leaf `b fields`. From `D`'s own value only tree nodes and leaves
`p ȳ` (ȳ: fields, parameters, constants without level parameters) are compiled, never a term that depends on
`D`'s level parameters: the block runs under the substitution of the role. A definition that is not of that
form is not inlined and the caller's application is compiled as before (a failed attempt leaves nothing:
`inline` resets `bad`, the caller restores the emission state).

Soundness: ISA 3.6 unchanged - the result is the composition of DELTA(f), DELTA(D), the recursor rules and beta
on the same arguments; K-like and structure-like recursors are excluded as before; a stuck major leaves the
block with NONE and the caller takes the DELTA path. It is used where RULES is used: by WHNF
(`Rules.unfold`), not by the two-sided lazy-delta step.

| code,casetrees, one worker | ticks | records made | run words | activations | APPLY | RULES blocks |
|---|--:|--:|--:|--:|--:|--:|
| reachability, trees of ISA 3.6 | 16.48 M | 9.08 M | 50.2 M | 4.43 M | 2.36 M | 71 |
| reachability, with inlining | 10.14 M | 5.59 M | 27.3 M | 2.28 M | 0.71 M | 112 |
| Init, trees of ISA 3.6 | 66.5 M | 36.1 M | 159.1 M | 10.4 M | 4.63 M | 148 |
| Init, with inlining | 63.9 M | 35.3 M | 153.7 M | 9.5 M | 4.01 M | 231 |
| Cedar slice, trees of ISA 3.6 | 17.53 M | 9.30 M | - | 1.15 M | - | 84 |
| Cedar slice, with inlining | 17.35 M | 9.23 M | 96.2 M | 1.10 M | 1.65 M | 115 |

CPU and instructions: 8.1 (rows "without inlining" use `-Drlvm.machine.m7.inline=off`, the A/B toggle of
`CodeStore.inlineTrees`; it also switches the eta-reduced minors off).

Tier equivalence (`M7TierTest`, default group): the case-tree test now runs every tree on every combination of
constructors (and a stuck variable) for up to four major premises that are parameters, 120 combinations at most
per constant: init-prelude 10,324 RULES cases (722 before), 966 trees fired, **10,090 identical value ids, 0
different, 0 equal only up to PEEK**, 234 ill-typed applications failing in both tiers, D = 128 and D = 0.
(The tree now runs before the K0 normalisation of the same case: the K0 WHNF of `Nat.succ (suspension)` forces
the suspension to look for a literal, and a tree that ran after it was handed the value by MKSUSP where K0 itself
had built the suspension - two cases "equal up to PEEK" that were an artefact of the order, ISA 2.1.)

### 8.4 Other changes to the stepper and the store (kept)

| Change | Evidence |
|---|---|
| `Code.addressOf`: block id -> address kept per session (one plain load instead of two acquire loads per lookup; `hasEntry` did four) | Init code,casetrees 118.8 -> 118.5 G instructions; batch 2 CPU flat (7.21 / 7.13 -> 7.10 / 7.19 s) |
| operands of MKAPP / APPLY / TAILAPPLY read in the loop (registers and arguments without a call per operand); KCONST of a level-polymorphic constant answered by the site's inline cache (the IC table of KVAL) instead of the LSUB cache + the CHEAD cache; `Code.entryFor(m, blk, n)` for closures (M2 adopted it 14:54Z: no walk of the lambda chain per APPLY through a block) | together, against the image of one pull earlier (M6's pooling is in the difference): Init 118.5 -> 117.3 G, reachability 29.2 -> 28.5 G, Cedar slice 46.2 -> 45.9 G; tier test identical |
| rent-to-own thresholds per role: reduction roles at the FIRST request (`CodeStore.REDUCE_AT = 1`), TELE at the tenth; `-Drlvm.machine.elabAt=N` sets both | instructions per run at threshold 10 / 1 (image of 15:11Z): reachability 17.8 / 17.8 G, Cedar slice 37.9 / 37.8, Init 108.2 / 108.1, Std 177.8 / 177.7; CPU s in batch 3: reachability 1.54, 1.58 / 1.68, 1.57; Cedar slice 2.55, 2.46 / 2.43, 2.48; Init 6.25, 6.27 / 6.25, 6.47; Std 10.90, 10.65 / 10.72, 10.60 (inside the noise of that host; batches 4 and 5 - m7a and three more runs of the threshold on the main tree of 15:39Z - were still queued behind the measurement lock at 16:12Z: section 8.8 if they arrived); code on Init 0.92 -> 2.2 MiB; M3: perf/unfold-before-literal 7.9 -> 4.1 s; gate at threshold 1 clean (8.7) |
| `Code.rules` / `rulesCasesOn`: a head with the wrong number of levels answers NONE (DELTA says "does not unfold") instead of running a block under ls = -1 | fail-open; no input reaches it after INFER |
| `Code.targConv`: `m.enterER()` (M6-F26) | request of M6, 14:46Z |

### 8.5 Experiments

| Experiment | Result | Verdict |
|---|---|---|
| `tele` on the native image (batch 0, quiet host, single runs; batch 2 and 3, two runs) | on top of `code,casetrees`, CPU s without -> with: batch 0: reachability 2.64 -> 2.91, Cedar slice 2.96 -> 3.13, Init 6.69 -> 6.72, Std 11.26 -> 11.27 (on top of step 1 + probes + code,casetrees: 2.52 -> 2.70, 2.68 -> 2.82, 6.36 -> 6.22, 10.76 -> 10.34); batch 2: 3.05, 3.02 -> 3.03, 3.06; 3.28, 3.28 -> 3.27, 3.43; 7.10, 7.19 -> 7.00, 7.18; 12.41, 12.41 -> 12.47, 12.64; batch 3 (with 8.3): 1.54, 1.58 -> 1.69, 1.56; 2.55, 2.46 -> 2.53, 2.50; 6.25, 6.27 -> 6.53, 6.31; 10.90, 10.65 -> 11.07, 10.78 | rejected as a default: no corpus gains, the Cedar slice loses. With TELE the machine makes 10 % fewer records on Init (36.1 -> 32.5 M; EVAL calls 4.7 -> 2.4 M) but enters 5.4 M more activations, each a window + 4 to 6 dispatched instructions per argument where M5's telescope-mode TWALK is straight Java; in v0 sessions its KVAL inline caches are cold in every declaration (400 k misses on Init). The role stays behind `tele`, elaborated at the tenth request. Not tried: TELE for VTYPE only (`teleValues`, no INFER / CONV per argument) |
| `m7a`: the CASESON tree as the unfolding step of lazy delta (`Code.rulesCasesOn`; state-exact: for `I.casesOn` itself no state of the official loop lies between the DELTA and the iota of one `lazy_delta_reduction_step`) | with fakes of `Rules.unfoldLazy` / `Conv.unfoldT,S` (batch 2): Init 7.10, 7.19 -> 6.77, 6.88 s (-4.5 %; 118.5 -> 115.4 G), Std 12.41, 12.41 -> 12.20, 12.29 (-1.3 %; 194.3 -> 190.6 G), Cedar slice 3.28, 3.28 -> 3.24, 3.29, reachability 3.05, 3.02 -> 3.02, 3.06; gate clean on tutorial + bugs + four corpora | kept; behind `casetrees,m7a`; active since M3's `Rules.unfoldLazy` (15:05Z) and M4's `Conv.unfoldT / unfoldS` (15:24Z). On the main tree of 15:39Z (real call sites), instructions per run `code,casetrees` -> `+m7a`: Init 109.8 -> 107.1 G, Std 187.3 -> 184.0 G, Cedar slice 40.0 -> 39.8 G, reachability 17.9 -> 17.8 G; ticks on Init 63.5 M -> 59.7 M; gate 8.7 |
| elaboration threshold 1 / 3 / 10 for the reduction roles | instructions equal on Init, the Cedar slice and reachability (118.4 / 118.4 / 118.5 G; 46.2; 29.2); code on Init 2.2 / - / 0.9 MiB; M3: perf/unfold-before-literal 7.9 -> 4.1 s at 1 | 1 (8.4) |
| negative cache / marker for roles that stay generic; a denser opcode numbering; an operand mode "register known not to be a suspension" | not built: each is below 0.5 % of Init by the profile (`CodeStore.blockOf` 0.9 %, `Code.resumeAt` 1.0 %, `Code.val` 1.7 % of all cycles before 8.4) | - |

Profile of the stepper before 8.3 / 8.4 (perf, Init, code,casetrees, `work/m7/perf/init-k*.txt`): class `Code`
10.9 % of all cycles (`kConst` 1.8 %, `val` 1.7 %, `resumeAt` 1.0 %, `mkClosure` 1.0 %, `arityOf` 0.9 %), `Eval`
15.1 %, `Values` 14.2 %, `Runs` 12.4 %, `Infer` 11.5 %; reachability: `Values` 32.9 % (`neu` 17.4 %), `Runs` 16.6 %,
kernel 11.7 %, `Code` 11.5 %.

### 8.6 What was not done, and why

- **CONG plans read by CARGS**: not wired, and it should not be as specified: the soundness review (S-F2,
  13:48Z) showed that skipping a proof or unread position (classes PROOF / UNREAD) accepts pairs the official
  kernel rejects, and M4 took B3 / B4 out of every switch set (14:33Z). What remains of a plan is the probe
  order, and the probes that would use it are order-only now. `Cong` stays tested on fact records and unused.
- **FIELDS(S)** (IPROJ): not elaborated. It is an inference role, not a reduction role; IPROJ is not in the
  profile of any of the four corpora above 0.5 %.
- **B9 inside TELE, TARG.EXPECT with a caller**: not done; TELE is off (8.5).
- **Sub-block rent-to-own**: sub-blocks are still made with their parent (440 KiB of 869 KiB on Init at
  threshold 10, a fifth never entered). With the reduction roles at threshold 1 the whole code of Init is 2.2 MiB
  per engine; no need was measured.
- **K2 (derived rules for structural recursion)**: M3 has it (`k2`). What 8.3 leaves on reachability is exactly
  that: `RULE(List.rec)` and the minor premise of `List.brecOn.go` (0.47 M entries each).
- `M7HookedTest.all230` (slow group): the integrator saw the K1 lane end `perf/loop-unfolded-once` as CRASH
  where K0 declines it. Not reproduced: alone, in-process, with `tele,code,casetrees` at threshold 1 the machine
  declines it cleanly (`M7LoopProbeTest`: BUDGET_MEMORY, E_MEM, 34.5 M records); the crash needs the other 229
  files' garbage in the 6 GB test JVM.

### 8.7 Gates on the published state

All through the launcher (`work/m7/gate.sh`: `testkit diff --engine machine` against the reference engine, 4
lanes; reports `work/m7/gate-*.txt`), plus the module's tests:

| State | Switches | tutorial + bugs | all 230 | Cedar slice | reachability | Init | Std |
|---|---|---|---|---|---|---|---|
| main jar 608b84b2b142 (before this wave) | `code,casetrees` | 1,342 / 1,342 | 15,844 / 15,845, 1 decline (loop-unfolded-once, E_MEM as v0) | 8,064 | 4,224 | 57,938 | 100,096 |
| the same | `warm,gated,expect,probes,code,casetrees`, 4 MiB | 1,342 | - | 8,064 | 4,224 | 57,938 | 100,096 |
| **published 15:10:57Z** (inlined trees; `work/m7/dist-f`) | `code,casetrees` | 1,342 / 1,342 | **15,844 / 15,845**, 1 decline (the same) | 8,064 | 4,224 | 57,938 | 100,096 |
| the same | `code,casetrees` at threshold 1 | 1,342 | - | 8,064 | 4,224 | 57,938 | 100,096 |
| sandbox of 14:50Z (inlined trees, `dist-e`) | `warm,gated,expect,code,casetrees`, 4 MiB | - | - | 8,064 | 4,224 | 57,938 | 100,096 |
| **main tree of 15:39Z** (thresholds 1 / 10; M4's `Conv` calls `Rules.unfoldLazy`; M6's rule "a declaration that enters eager reduction is declined" is armed; `work/m7/dist-g`) | `code,casetrees,m7a` | 1,341 of 1,342 | 15,839 of 15,845 | 8,053 of 8,064 | 4,216 of 4,224 | 57,774 of 57,938 | 99,893 of 100,096 |
| the same | `warm,gated,expect,code,casetrees,m7a`, 4 MiB | - | - | 8,053 | 4,216 | 57,774 | 99,893 |

Every cell: **0 differences (SOUNDNESS 0, COMPLETENESS 0)** on the units compared. The units not compared in the
last two rows are declines of the machine with `UNSUPPORTED_INPUT ... eager reduction` (M6's rule of 15:07Z,
not this package: 1 / 5 / 11 / 8 / 164 / 203 units), which the driver hands to the reference engine, and
loop-unfolded-once. "all 230" = 226 files with `-Xmx4g` and the four memory-hungry perf files (app-lam,
gf3-matrix-product, magma-list-deep-n36, magma-list-pair-n21) with the launcher's heap, one at a time.

Module tests on the published state: fast group green at every publication (the publish script); default group
`M7*Test` green (`M7TierTest.initPrelude`: 25,824 cases, 25,590 identical ids, 0 different, 0 up to PEEK, 234
ill-typed applications failing in both tiers, D = 128 and D = 0; `M7ListingTest`: the listings of ISA 3.7
unchanged; `M7CorpusTest`: pubcheck on every block, two workers word-identical); slow `M7CorpusTest` on Init
(126,664 blocks when every role is elaborated, RULES 1,400 -> 2,564) and Std (218,654; RULES 2,209 -> 4,019).

Switches I consider ready to be on by default, with the evidence above: **`code`, `casetrees`** (together; with
the reduction threshold 1 and tree inlining, which are not switches) and **`m7a`** on top of them (M6 may give it
its final name; it is meaningless without `casetrees`). Not ready: `tele` (no gain; the Cedar slice loses),
`t2` (the tier is another module). M4's `lazyeval` sends the one-sided lazy-delta steps through WHNF and so
through the trees of 8.3; it is M4's switch and M4's measurement.
