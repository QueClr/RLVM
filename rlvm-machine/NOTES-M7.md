# NOTES-M7 - K1 elaborator, code stepper, code store, MATCH, comparison plans (owner: M7)

Files: `Elab`, `Code`, `CodeStore`, `Match`, `Cong`, `CodeShow` (claimed: the listing printer); tests
`M7*.java`. **Nothing of this package runs in v0**: every entry point answers "walk the term" (0) while
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
| D16 | probe `ELAB_AT` is `Op.ELAB_AT` | `CodeStore.elabAt`, system property `rlvm.machine.elabAt` (1..15, default 10) | the sweep of ISA 3.1 |

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
| `Cargs` (M4) | `Facts.sigd` (derive), then `Cong.planOf` + `Cong.words / base / positions / lamArity / classWord / dep / propStatic` | `SW_FACTS`; **not wired yet** (M4, step 2) |

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
- The IC table (`m.ic`) is used by KVAL only; a site beyond pc 4095 bypasses it.
- Heat counters per block (ISA 8.3) are not kept; `Code.Ext` counts activations entered and elaborations by role.
- `dumpStats` of the engine does not print the code store's numbers (M6's file).
