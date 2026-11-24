# Owner's design notes (verbatim, in order received)

These are the project owner's own steering comments, collected by the orchestrator so that every
agent sees them. Where a research document or a design draft conflicts with them, the notes win;
say what following them costs. The "Reading" lines are the orchestrator's interpretation, not the
owner's words.

1. "notice that the leader board time is virtual clock and it is reported that nano-lean leading
   does not reflect wall clock"
   Reading: optimise and report both instruction count (board rank) and wall/CPU time.

2. "2.06 s with SerialGC, 7.46 s with ParallelGC and 10.0 s with G1: notice that realistic workload
   requires parallelism"
   Reading: runtime and GC choices are evaluated with parallel workers and wall clock, never from
   single-threaded CPU totals. Parallel workers are the design point.

3. "notice that depending on your 'core elaboration language design', the NbE VM can be pretty hot."
   Reading: the language that constants and terms are elaborated into is the central design
   artifact; the machine should carry essentially all of the checking work.

4. "I am thinking that the whole project is like a mechanization of lean kernel tyck. similar to
   other VM-like mechanization work." / "I mean 'mechanization' in real type theory context. e.g. A
   mechanical formalization of higher-ranked polymorphic type inference. It is like building a VM
   for DK."
   Reading: the worklist formulation (Zhao, Oliveira, Schrijvers, ICFP 2019): judgments are
   worklist entries, inference judgments carry continuations, the algorithm is a first-order
   small-step transition system. RLVM is the machine that steps it for the Lean kernel.

5. "I don't think lambda-calculus is the language for truffle. truffle jit should be for the
   mechanized check progress. not the language itself."
   Reading: the Truffle guest language is the judgment/instruction language; lambda terms and
   values are its data.

6. "this is like developing an instruction set for lean4 NbE and def_eq. which sounds exciting"

7. "notice that nat itself is a good thing to specialize: e.g. native integer for small range ..."
   Reading: Nat gets first-class specialisation in the instruction set and value model, not only a
   literal-arithmetic library: small naturals as immediate (unboxed) values that never touch the
   value store, arithmetic/comparison instructions with a native fast path and an exact overflow
   guard that falls to the big-number path, cheap views between literal and `Nat.succ`/`Nat.zero`
   constructor forms (offsets), `Nat.rec`/`casesOn` on a literal in one step. In the Truffle tier
   this is the classic type specialisation (long with overflow check, rewrite to big on overflow).

8. "meanwhile some data structure specialization may also be interesting, like how graaljs
   specialize their array, but this is more like an open design question"
   Reading (open question, to be explored with measurements, not committed): storage strategies in
   the GraalJS sense - a value keeps a specialised representation while its contents allow it and
   transitions to a more general one when they do not. Candidates: application spines and captured
   frames (inline small / flat / chunked), constructor cells by arity (nullary as immediates),
   `List`/`Array`/`String`/`Char`/`BitVec`/`Fin`/`UIntN` values that stay packed until a recursor or
   projection needs the constructor view. Any strategy that short-cuts a library definition is
   only sound if the definition is verified to have the expected shape, exactly like the Nat and
   String literal prerequisites. The value model should leave room for strategy tags and
   transitions.

9. "You need to check Cedar level workload. Init may not be interesting enough"
   Reading: heat and JIT economics must be judged on realistic project workloads (the arena's cedar,
   con-leche, reachability, navier-stokes-euler, cslib corpora and mathlib), where the kernel really
   reduces, not on Init/Std. A dynamic workload characterisation is in docs/research/13 (in progress).

10. "We don't want to beat instructions. care more about CPU time plz."
    Reading: THE PRIMARY METRIC IS CPU TIME (task-clock summed over all threads), then wall clock with
    parallel workers. Instruction counts are still recorded because the arena's board uses them, but
    they are not the optimisation target. Consequences: prefer cache-friendly designs even when they
    execute more instructions (compact records, locality, fewer random probes into large tables,
    matching against a pattern instead of building and interning); budgets and governors (compilation,
    speculation) are expressed in CPU time or steps, not in billed instructions; the platform choice
    (JVM with JIT versus native image) is decided by measured CPU time and wall clock; every benchmark
    table lists CPU time first. Where ISA.md or ARCHITECTURE.md argue from instruction counts, re-read
    the argument with CPU time in mind.

11. (2026-11-24, answering four open questions) "1. resemble official by default 2. do not download EE on
    default, but you can report by here 3. commit each step, I need history. 4. you can increase to 32 agents
    a time. profile more and optimize more."
    Reading:
    - Universe levels: BOTH engines compare universe levels as the official kernel does by default (its
      incomplete algorithm); the complete relation is an opt-in switch only. The two arena corner cases
      `imax-right-successor` and `imax-nested-absorption` must be rejected by default, as the official
      checker rejects them.
    - Oracle GraalVM ("EE", GFTC licence): the default build, the arena build and every default script must
      not download or use it. It may be used for measurements that are reported to the owner, clearly
      labelled; nothing that ships depends on it.
    - Version control: the orchestrator commits every step (agents still do not run git commands).
    - Team size: up to 32 agents at a time; the extra seats go to profiling and optimisation.
