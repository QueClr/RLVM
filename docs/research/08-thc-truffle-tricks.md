# 08 — THC and Cadenza (Kmett): Truffle tricks for a lazy evaluation VM, and what transfers to RLVM

Sources (all paths below are relative to `refs/`): `thc/` (main @ `7b97d82d`, 2026-11-12, shallow history
2026-09-28..2026-11-12, 1642 commits), `cadenza/` (master @ `e2b66e2`, 2026-09-22, full history from 2019),
plus `normalization-bench-kovacs/`, `normalization-bench-guest0x0/` for JVM-relevant HOAS/NbE numbers.
Truffle facts marked **[verified 25.5.5.0.3]** were read out of the project toolchain itself
(`.toolchain/graalvm-ce-25`, `.toolchain/m2/org/graalvm/truffle/*/25.5.5.0.3/*.jar`) with `javap`/`strings`,
not taken from memory. Nothing from the external repos was built or run.
Markers: **[inference]** = my conclusion from cited facts; **[unverified]** = not checked against source.

---

## 0. Decision-relevant findings first

1. **Neither project shows that Truffle pays for a proof checker; both show what it costs.**
   THC publishes no benchmark numbers at all (`thc/AGENTS.md:100-101`: "Keep development benchmark results
   private"). Cadenza publishes careful ones (§6): compiled guest code is ~64× faster than the same AST run
   in Truffle interpreter mode (711.6 µs vs 11.2 µs, `cadenza/bench/results/2026-09-22/argument-array.md`),
   and one Graal tier-2 compilation of a small root costs 0.03–12 s of compiler time in THC's archived logs
   (118 compilations: median 532 ms, mean 1424 ms, of which PE 219 ms; graph dumping was on, so treat as an
   upper bound; `thc/docs/**/*.log`, §6.3). At ~3·10⁹ instr/s the median is ~1.6 G instructions per
   compiled root, against a whole-Mathlib budget of 400–600 G; even if the true cost is 5–10× lower,
   **a root must run for a large fraction of a second in the interpreter before compiling it is a net
   win.**
2. **Truffle's own interpreter tier is the wrong tier-0 for RLVM.** From Cadenza's numbers: ~150 ns per
   guest call in Truffle interpreter mode vs ~2.4 ns compiled (§6.1, my division). Kernel checking is a
   workload "where the amount of computation is small but term sizes are large"
   (`normalization-bench-guest0x0/README.md:729-731`), so most code runs once. Recommendation: a plain-Java evaluator is tier 0; Truffle is an optional accelerator
   for the few closed, hot definitions/recursor rules (§1). The sibling doc
   `docs/research/07-leader-rpylean-and-tenet-jvm.md` §0.2/§1.16 reaches the same conclusion from rpylean's
   removed kernel-loop JIT.
3. **Cadenza's NbE-on-Truffle lesson, with numbers:** neutrals routed through an exception
   (`SlowPathException`) cost nothing on closed code but make symbolic evaluation 16–28× slower than
   value-returning neutrals; value-returning neutrals made compiled closed code 80% slower and +91%
   allocation (`cadenza/bench/results/2026-09-22/neutral-tracing.md`). So: **open-term evaluation (the
   kernel's normal case) needs first-class neutral values and therefore belongs in tier 0; the Truffle
   tier should evaluate closed terms only and treat "stuck" as a bail-out**, exactly Cadenza's split.
   Cadenza implements only `eval` + neutral propagation; there is **no quote/readback/conversion** in it
   (grep over `cadenza/src`: no fresh-variable or quote function exists).
4. **"JAM-patched GraalVM" is a custom garbage collector, not a JIT feature.** RLVM needs none of it and
   none of THC's Truffle/Sulong patches; stock GraalVM 25.5.5.0.3 + Maven `org.graalvm.truffle:*:25.5.5.0.3`
   is a coherent pair (§5). The "silently stays in the interpreter" hazard is Truffle's runtime/compiler
   version check falling back to `DefaultTruffleRuntime`; guard against it at startup (§5.3).
5. **The transferable engineering is the calling convention and the PE discipline**, not the Haskell
   machinery: flat `Closure{code, env, pap[], arity}` with eval/apply dispatch nodes and a 3-entry target
   cache; a 3-field thunk that drops its captures on update; one-shot `@CompilationFinal` booleans instead
   of DSL rewriting; "cold in compiled code ⇒ go generic behind a boundary, never deopt"; no retry after
   `UnexpectedResultException`; single compilation tier at threshold 10 000 with 1–2 compiler threads
   (§2–§4).
6. **Defaults that will burn the instruction budget if left alone** **[verified 25.5.5.0.3]**:
   `engine.FirstTierCompilationThreshold=400` (a second, last-tier compilation follows at 10 000),
   `engine.DynamicCompilationThresholds=true` (thresholds are rescaled with compile-queue load, so they are
   not deterministic), `engine.Splitting=true`, `engine.CompilerThreads=-1` (scaled with cores), `engine.MaximumCompilations=100`
   per target, `compiler.InliningRecursionDepth=2`. Settings in §1.7.

---

## 1. Recommended RLVM model (derived from §2–§6; this is the part to implement)

### 1.1 Tiers

| Tier | What | Implemented as | Entered |
|---|---|---|---|
| K | type inference, definitional equality, caches, environment, export store | plain Java, monomorphic `switch` on term kind | always |
| T0 | WHNF / NbE evaluator over the kernel term store, open terms, first-class neutrals, call-by-need | plain Java (no `Node`, no `VirtualFrame`) | every reduction |
| T2 | closed-term evaluator for *promoted* code objects | Truffle AST nodes + `RootNode`/`CallTarget`, compiled by Graal | only when a code object's T0 heat counter crosses the promotion threshold |

There is deliberately no "T1 = Truffle AST in interpreter mode" as a general tier: a promoted root is
expected to be compiled almost immediately (its heat already proved it hot).

### 1.2 Values (plain Java, shared by T0 and T2)

`Object` is the value type; dispatch by exact-class `instanceof` on `final` classes (Graal and C2 turn this
into one klass compare). No Truffle `DynamicObject`, no `StaticShape` (see §3.6 for why not).

```java
// java.lang.Long        Nat literal < 2^63 (canonical: a value < 2^63 is never a BigInteger)
// java.math.BigInteger  Nat literal >= 2^63
// VStr                  String literal; expands to String.mk (List Char) only when inspected
final class Closure {            // thc/src/main/java/thc/runtime/Closure.java:11-33, cadenza/src/cadenza/data/Closure.kt:28-34
    final Code code;             // immutable; code.arity = #binders of the lambda telescope
    final Object env;            // captured environment in T0's representation; null if closed
    final Object[] pap;          // arguments already supplied; shared EMPTY array when none
    final int arity;             // remaining = code.arity - pap.length (> 0)
}
final class VCtor {              // constructor application in WHNF
    final CtorInfo info;         // info.idx, info.nparams, info.nfields (all final)
    final Object[] args;         // params ++ fields; fields may be Thunks.  Nullary: one shared instance per ctor
}
final class Thunk { Object v; Code code; Object env; }      // code == null  <=>  evaluated, v is WHNF
final class Code {               // one per lambda telescope / delayed sub-term / recursor rule RHS / definition body
    final int term;              // T0 view: handle into the term store
    final int arity; final int[] captures;      // captures: de Bruijn indices of free bvars actually used
    @CompilationFinal volatile RootCallTarget target;        // T2 view; null until promoted
    long heat;                   // T0 counter, plain (racy increments are fine)
    boolean t2Hostile;           // set when T2 bailed on this code; never promote / re-enter
}
// T0 only (never inspected by T2 fast paths): VNeutral{head, spine}, VPi, VSort, VLazyConst{c, spine, Thunk unfolded}
```

Rules taken from the sources:
- **Constant closures.** A lambda with no captures has exactly one preallocated `Closure`
  (`thc/.../MakeClosure.java:24,28`; `cadenza/src/cadenza/jit/code.kt:184-189`; measured 72 → 40 B/op,
  `cadenza/bench/results/2026-09-22/ast-followup.md`).
- **No wrapper around the environment.** Cadenza's port to `StaticShape` added a `CapturedFrame` wrapper and
  regressed escaping closures 104 → 136 B/op until removed (`.../README.md`, `.../ast-followup.md`).
- **PAP creation copies once**, off the fast path (`Closure.pap`, `thc/.../Closure.java:34-47`; 248 → 224 B/op
  in `cadenza/.../ast-calls.md`). Skip the prefix `arraycopy` when `pap.length == 0`
  (`thc/.../Applications.java` `appendWithHeader`, commit `d5d741c6`).
- **Small-value caches.** THC caches boxed `I#` for −16…255 and `C#` for 0…255 per layout
  (`thc/docs/boxed-values.md`, `thc/.../DataLayout.java:175-180,243-248`) and keeps one shared instance per
  nullary constructor (`DataLayout.java:171,253-255`). RLVM: `Long.valueOf` already caches −128…127; add an
  own table (e.g. 0…1023) only if allocation profiles show boxed Nats.
- **Thunk drops its captures when updated** (`thc/.../Thunk.java:56-62`:
  `value = result; target = null; environment = null`). Mandatory to avoid leaking whole environments.

### 1.3 Thunk protocol (pure kernel ⇒ far simpler than THC's)

THC's thunk has six states and a monitor (`thc/.../Thunk.java:21-22`: 0 unevaluated, 1 owned, 2 WHNF,
3 failure, 4 interrupted, 5 parked) because Haskell needs blackholing, exceptions and concurrency. Kernel
terms have no `letrec`, so a thunk cannot demand itself; no blackhole state is needed.

```java
static Object force(Object x) {                 // hot path shape = thc/.../Force.java:43-44, 73-76
    if (!(x instanceof Thunk t)) return x;      // most values are not thunks: test first, return
    Code c = t.code;
    if (c == null) return t.v;                  // already WHNF
    Object r = T0.whnf(c, t.env);               // or c.target.call(...) when promoted
    // convention: r is never a Thunk  (thc Force.java:696 "Thunk target violated WHNF convention")
    t.v = r; t.env = null; t.code = null;       // write v BEFORE clearing code
    return r;
}
```

- **Do not create a thunk for atoms.** bvar (pass the existing value/thunk through), const, literal, sort,
  lambda (closure creation is O(1)) need no `Thunk`. THC gets this from GHC certificates
  (`knownWhnf`/`safeToEvaluate`, `thc/docs/core-evidence.md`); for Lean terms it is a syntactic check at
  the `app`/`let` site.
- **Strict positions need no thunk either**: recursor major premise, the struct of `proj`, both operands
  of accelerated Nat ops. THC's analogue is `entryStrict` ("a known saturated call can avoid constructing
  a thunk whose only purpose would be immediate evaluation", `thc/docs/entry-contracts.md`).
- **Write-back after forcing a local** (T2 only, needs mutable slots): after `force(slot)` succeeds, store
  the result into the frame slot if it still holds that thunk, so later reads skip the state check
  (`thc/.../LocalRead.java:73-86`, `thc/.../BytecodeRoot.java:616-653`, `thc/docs/thunk-updates.md`).
- **Threads.** If a `Thunk` can be reached from two checker threads (e.g. through a cached constant value),
  either forbid it (publish only fully evaluated values across threads) or read/write `code` with
  `VarHandle.getAcquire/setRelease` as THC does for `state` (`Thunk.java:13-18`, commit `25303817`).
  Double evaluation is harmless in a pure language; a reader seeing `code == null` with a stale `v` is not.

### 1.4 Application: eval/apply with arity (the Cadenza convention)

Both projects use **eval/apply**: the callee's remaining arity is stored in the closure and the *call site*
decides exact / under / over application (`cadenza/README.md` TODO, `cadenza/src/cadenza/jit/dispatch.kt`,
`thc/.../Dispatch.java:18` "Cadenza exact/PAP/overapplication specializations with fixed site arity").
Flatten `app` spines so a site has a fixed argument count `n`.

T0 (no arrays for small arities; Cadenza measured that packed argument arrays are 99.75% of surviving
allocation in compiled recursion, `cadenza/bench/results/2026-09-22/fib-allocation.md`):

```java
Object apply(Object f, Object[] args, int off, int n) {      // generic, loops on over-application
    for (;;) {                                               // = cadenza dispatch.kt:118-153 GenericDispatch
        if (!(f instanceof Closure c)) return stuck(f, args, off, n);    // T0: build VNeutral
        if (c.arity > n) return c.pap(args, off, n);                      // under: copy once
        Object r = enter(c, args, off, c.arity);                          // exact prefix
        if (c.arity == n) return r;
        off += c.arity; n -= c.arity; f = force(r);                       // over: continue with result
    }
}
// plus apply1/apply2/apply3(Closure, a, b, c) fast entries that avoid Object[] when c.arity matches
```

T2 call site (`AppNode` with `@Child Dispatch`), specializations in this order
(`cadenza/.../dispatch.kt:51-112`, `thc/.../Dispatch.java:40-127`):

| # | Guard | Action |
|---|---|---|
| 1 | `fn.arity == n && fn.code == cachedCode`, `limit = "3"` | `DirectCallNode` to `cachedCode.target`; args array = `[env, pap…, actual…]`; `pap.length` and `env != null` are `@Cached` constants |
| 2 | `fn.arity < n && fn.arity == cachedArity && fn.code == cachedCode`, `limit = "3"` | direct call with first `cachedArity` args, `force` result, recurse into a child `Dispatch(n - cachedArity)` |
| 3 | `fn.arity > n` | `fn.pap(args)` (behind `@TruffleBoundary`) |
| 4 | `fn.arity == n`, `replaces = 1` | one `IndirectCallNode` for every target |
| 5 | `fn.arity < n`, `replaces = 2` | one generic loop (as T0 above); "avoid a second bounded cache of arities: the same application site can observe arbitrarily many currying shapes" (`dispatch.kt:106-112`) |

All inline caches in both code bases use `limit = "3"` (7 of 7 `limit=` occurrences in
`thc/src/main/java/thc/runtime`). The cache key is the **code/call target**, not the closure
(`cadenza/TODO.md`: "arity equality == too weak; callTarget equality (+ pap arity); closure equality == too
specific"). Add a **known-call fast path**: when the head is `const c` and `n == arity(c)`, emit a node
holding a `DirectCallNode` and skip closure/arity checks entirely.

If `code.target == null` at a T2 call site (callee not promoted), call T0 through a
`@TruffleBoundary(transferToInterpreterOnException = false)` helper; never promote from inside compiled
code.

### 1.5 T2 node set for kernel terms

Mirror rpylean's `CodeNode` list (sibling doc 07 §1.10); the Truffle realisation below is what THC/Cadenza
add. All metadata arrays are `@CompilationFinal(dimensions = 1)`, all loops over them `@ExplodeLoop`.

| Kernel term | T2 node | Behaviour / source pattern |
|---|---|---|
| `bvar i` | `ReadLocal(slot)` / entry preamble copying `env[k]` → slot | Cadenza copies only the captures the body uses into frame slots at entry (`envPreamble`, `cadenza/src/cadenza/jit/nodes.kt:169-198`); THC `FunctionRoot.buildFrame` (`thc/.../FunctionRoot.java:454-481`) |
| `lam` | `MakeClosure(code, captureSlots)` | constant closure when no captures (§1.2) |
| `app` | `AppNode` + `Dispatch(n)`; args are atom nodes or `DelayNode` | §1.4; fill the argument array directly, no intermediate collection (−12.9% interpreter allocation, `cadenza/.../argument-array.md`) |
| `let x := v; b` | `LetNode(slot, DelayNode(v) \| atom, b)` | lazy zeta |
| `const c`, definition | `ConstNode(c)` reading a per-constant cell | cell = lazily prepared, then constant-folded: `@CompilationFinal volatile boolean prepared; Object preparedValue;` with a re-check behind a boundary so publication never needs invalidation (`thc/.../GlobalBinding.java:15-19,40-56,88-114`) |
| `const C`, constructor, saturated | `CtorNode(info, argNodes)` | `new VCtor`; nullary → shared instance |
| recursor applied to ≥ major | `RecNode(recInfo)` | force major; `VCtor v` → `switch`/cache on `v.info.idx` → direct call of rule RHS root with `[params, motives, minors, fields…]`; `Long` major for `Nat.rec`: `0` → zero rule, `k>0` → succ rule with `k-1`; IH is a `DelayNode` (lazy) |
| `proj S i e` | `ProjNode(i)` | force `e`; `VCtor` → `args[nparams + i]`; else bail |
| Nat literal ops (`Nat.add` …) | `NatBinNode` | `long` path with `Math.addExact`; overflow → `BigInteger` path behind `@TruffleBoundary`. Never retry the operands after an overflow (see §4.2 rule 3) |
| `lit` | constant node | |
| anything type-level (`sort`, `forallE`), `mvar`, free variables | none | T2 code is only built for closed, computational code; reaching one ⇒ `EvalBail` |

**Case dispatch.** THC's `Case` node is an exploded linear scan over alternatives with a class-identity or
layout test per arm, switching to a binary search over a sorted key array when there are more than 8
literal labels (`thc/.../Case.java:46-88,187-206`, `thc/docs/ast-case-boundaries.md` "Literal label
selection"). RLVM has a dense `ctorIdx`, so use a direct array index in the interpreter and a
`limit = "3"` cache on `idx` (then indirect call) for compiled code **[inference]**.

**Bail-out, not neutrals.** T2 never constructs a neutral. On any stuck shape throw one preallocated
`EvalBail extends ControlFlowException` from a `@TruffleBoundary(transferToInterpreterOnException = false)`
method; catch it only in the plain-Java caller of `target.call(...)`, mark `code.t2Hostile = true`, and
redo the reduction in T0. No `catch` blocks inside guest nodes: Cadenza's attempt to make its neutral
exception compiler-visible failed with `BailoutException: Code installation failed: code is too large`
on AArch64 because the handlers became part of every compiled body
(`cadenza/bench/results/2026-09-22/neutral-tracing.md` "Compiler gates").

### 1.6 Recursion depth, tail calls, loops

- Kernel terms have no general recursion; every unbounded loop passes through a recursor and (lazily)
  through forcing induction hypotheses. The Cadenza/THC bloom-filter tail-call machinery (§3.4) is
  therefore **not needed** in RLVM **[inference]**; what is needed is protection against deep *non-tail*
  recursion when IH thunks are forced in a chain.
- THC bounds nesting with a depth counter (`AstStackScope.MAX_DEPTH = 64`,
  `thc/.../AstStackScope.java:7`, bytecode op `StackLimit` `thc/.../BytecodeRoot.java:228-235`) and then
  spills the Java stack into heap continuations — this required resumable interpreters (`enableYield`),
  patched Truffle and ~3 500 lines of capture/continuation classes (`Ast*`, `Delimited*`, `CallSegment*`,
  `BytecodeContinuations`, …: 3 571 lines by `wc -l`) plus most of `Force.java`. Do not copy.
- RLVM: run each checker on a thread created with an explicit large stack size, keep a depth counter in T2
  and `EvalBail` above a limit so that pathological depth is handled by T0 (which can use an explicit
  continuation stack). JVM evidence that deep stacks hurt: Scala HOAS is on par with GHC on shallow tree
  benchmarks but 1.5–17× slower on 5M/10M-deep Church numerals (3.7× and 17× for normalisation) even
  with `-Xss1000M`
  (`normalization-bench-kovacs/README.md:94-105,113-119`).
- Self-recursion to loops and OSR matter only if T2 gets a real loop node. If one is added (e.g. a
  literal-driven `Nat.rec` with a strict accumulator), use Truffle `LoopNode` exactly as Cadenza's
  `SelfTailCallLoop` (`cadenza/src/cadenza/jit/tail_calls.kt:193-221`): first iteration peeled, loop body
  re-entered by rewriting frame slots; OSR then triggers at `engine.OSRCompilationThreshold=100352`
  back-edges **[verified 25.5.5.0.3]**.

### 1.7 JIT budget when the metric is total instructions

Promotion policy (all in plain Java, T0 side):

```
on T0 entry of a Code object c (closure body, thunk body, rule RHS):
    if (c.target != null && !c.t2Hostile)  -> call c.target            // already promoted
    else if (++c.heat == PROMOTE && budget.tryAcquire() && isClosedComputational(c))
         -> c.target = lowerToTruffle(c)                               // creates nodes + RootNode
```

- `PROMOTE` should be of the order 10⁵–10⁶ entries, not Truffle's 400/1 000/10 000 **[inference]**:
  break-even entries ≈ compile cost / (T0 cost − compiled cost per entry) ≈ 3·10⁸ / 500 ≈ 6·10⁵, assuming
  100 ms (3·10⁸ instructions) per compilation — 5× below the dumped median of §6.3 — and 500 instructions
  saved per entry. Measure both numbers and tune; expose as an option.
- `budget`: a global cap on the number of promoted roots and on cumulative compile time (read
  `|Time …ms|` from `engine.TraceCompilation` during tuning). Start with ≤ 50 roots per run.
- Each T2 `RootNode` overrides `prepareForCompilation(boolean rootCompilation, int tier, boolean lastTier)`
  and returns `false` when the budget is exhausted. Javadoc (graal master `RootNode.java`, `@since 24.2`):
  "By default, this method returns true to indicate sufficient profiling for compilation, but
  implementations can return false if not"; paraphrased remainder: for a root compilation `false` defers
  the compilation, during inlining it disables inlining of that call site, and a thrown exception fails
  compilation of the root permanently. Method present **[verified 25.5.5.0.3]**; THC uses it at
  `thc/.../FunctionRoot.java:834-836`, `thc/.../BytecodeRoot.java:133-135`.
- Leave `isCloningAllowed()` at its default `false` (no splitting ⇒ no duplicate compilations). THC and
  Cadenza return `true` to recover monomorphism (`cadenza/.../nodes.kt:59,130,206`,
  `thc/.../FunctionRoot.java:838-840`); RLVM should instead keep T2 roots few and already specialised.

Engine/compiler options. THC's launcher values are from `thc/src/main/java/thc/Main.java:35-47`; defaults
are **[verified 25.5.5.0.3]**.

| Option | Default | THC | RLVM starting point | Why |
|---|---|---|---|---|
| `engine.Compilation` | true | true | **false for the first milestone**, then true | T0-only baseline first |
| `engine.MultiTier` | true | **false** | false | one compilation per root |
| `engine.SingleTierCompilationThreshold` | 1000 | **10000** | 100–1000 | hotness is decided by `PROMOTE`; but inline caches and one-shot flags must warm up in the Truffle interpreter first, otherwise the first compilation bakes in uninitialised sites and deopts (`engine.MinInvokeThreshold` default 3) |
| `engine.FirstTierCompilationThreshold` / `LastTier…` | 400 / 10000 | n/a | n/a (single tier) | |
| `engine.DynamicCompilationThresholds` | true (`MinNormalLoad` 0, `MaxNormalLoad` 90, `MinScale` 0.1) | default | **false** | deterministic thresholds. Option text: thresholds are decreased when queue load falls below `MinNormalLoad` (down to `MinScale`) and increased above `MaxNormalLoad`; with the shipped `MinNormalLoad=0` only the increase can apply **[behaviour not tested]** |
| `engine.CompilerThreads` | -1 (scaled with cores) | 2 if ≥ 4 CPUs else 1 | **1** | compiler threads are billed |
| `engine.BackgroundCompilation` | true | true (false in tests) | true | measure `false` as an alternative |
| `engine.Splitting` | true | default | **false** | belt and braces with `isCloningAllowed() == false` |
| `engine.OSR` | true | default | false unless a `LoopNode` exists | |
| `engine.MaximumCompilations` | 100 | default | **2–3** | hard cap on deopt/recompile cycles per root |
| `engine.CompilationFailureAction` | Silent (enum Silent/Print/Throw/Diagnose/ExitVM) | Print | Silent in the arena run, Print while developing | `Diagnose` retries the compilation with diagnostics |
| `compiler.DiagnoseFailure` | false | false (explicit) | false | |
| `compiler.MaximumGraalGraphSize` | -1 (disabled) | **1000** | 5000–20000 | bounds worst-case PE cost; a bail-out is wasted work, so do not set it as low as THC (§4.4) |
| `compiler.CompilationTimeout` | 100 s | 30 s | 5–10 s | |
| `compiler.InliningRecursionDepth` | 2 | default | **0–1** | Cadenza: recursive inlining "uses up inlining budget on recursive occs" and slowed fib; faster with max recursive inlining 1 (`cadenza/benckmarks.md`) |
| `compiler.InliningExpansionBudget` / `InliningInliningBudget` | 12000 / 12000 | default | tune down | compile cost grows with inlined IR |
| `compiler.DeoptCycleDetectionThreshold` | 15 | default | default | |

JVM flags seen in THC that are relevant: `-XX:+UseCompactObjectHeaders` and `-XX:+UseCompressedOops` in its
benchmark launcher (`thc/bin/benchmark-jvm-options.sh:11-18`; flag exists and defaults to `false` in the
project toolchain **[verified]**), `-Xss2m` (THC app default, `thc/build.gradle:271`), `-Xss32m` (Cadenza
benchmarks). THC's `-Xshare:off`, `-Xms256m -Xmx256m`, `-XX:-UseCompactObjectHeaders`, `-XX:+UseJamGC`
(`thc/build.gradle:267-268`) are JAM requirements, not tuning advice.

### 1.8 What should be plain Java vs Truffle

| Piece | Where | Reason |
|---|---|---|
| Export parsing, term store, hash-consing, caches | plain Java | data, not program |
| `inferType`, `isDefEq`, lazy delta heuristics, eta, proof irrelevance | plain Java | megamorphic tree walk; rpylean measured a JIT on these loops as a 15–19% loss (doc 07 §0.2) |
| WHNF / NbE with neutrals, quote, conversion | plain Java (T0) | neutrals on the fast path are fatal for PE'd code (§6.2) |
| Closed evaluation of hot definitions, recursor rules, Nat/Bool/`decide`-style computation | Truffle (T2) | only place where a *program position* is stable enough for PE |
| Values, thunks, closures | plain Java classes used by both | THC/Cadenza keep values strictly separate from nodes (`thc/docs/site/jvm.md` "Values, nodes and descriptions") |
| Lazily mapped export | plain Java `MemorySegment` | THC maps its Core containers with `FileChannel.map(READ_ONLY, 0, size, Arena.ofShared())` and decodes a binding only when first demanded (`thc/src/main/java/thc/CoreFileMappings.java:135-139`, `thc/docs/compact-core-format.md`: ULEB128 records, `(start,len)` string spans, sorted 24-byte fingerprint→offset table) |

---

## 2. THC architecture (item 1)

- **Pipeline** (`thc/docs/architecture.md`): GHC 9.14.1 does parsing/typechecking/optimisation; a Core
  plugin exports post-Tidy Core plus "evidence" (runtime representation, evaluatedness, entry strictness,
  demand, join points) into a compact binary container (CBD); the JVM runtime links, lowers lazily
  ("Eligible cold bindings are decoded and lowered on demand") and executes.
- **Two backends over one value model** (`thc/docs/bytecode.md`, `thc/README.md:168-172`):
  - *AST*: `Program.java` (2 797 lines) lowers Core to `Expr` nodes (`thc/.../Expr.java`), one
    `FunctionRoot` per lambda/thunk body (`FunctionRoot.java`, 841 lines).
  - *Bytecode (default)*: `BytecodeProgram.java` (8 574 lines) lowers Core to **Truffle Bytecode DSL**
    operations declared in `BytecodeRoot.java` (7 880 lines):
    `@GenerateBytecode(languageClass = Language.class, enableYield = true, enableTagInstrumentation = true,
    boxingEliminationTypes = {int.class, long.class, float.class, double.class, boolean.class})`
    (`BytecodeRoot.java:51-52`). No uncached interpreter tier is enabled (Cadenza's prototype does enable
    `enableUncachedInterpreter`, `cadenza/src/cadenza/bytecode/BytecodeRoot.java:33-37`).
- **DSL usage is minimal.** Only 6 files in `thc/src/main/java/thc/runtime` contain `@Specialization`
  (the bytecode operations and the dispatch caches). Everything else is hand-written nodes with explicit
  `@CompilationFinal` state. Counts in that directory (656 files): 612 `@TruffleBoundary` annotations,
  196 `@CompilationFinal`, 123 `transferToInterpreterAndInvalidate`, 111 `@ExplodeLoop`, 35
  `inCompiledCode()` tests, 35 profile uses, 22 `Assumption` uses.
- **Frames.** Indexed `FrameDescriptor` slots allocated at lowering time
  (`thc/.../FrameLayout.java:15-45`; slot 0 is the tail-call bloom filter). Slot kinds start `Illegal` and
  widen monotonically primitive → Object (`thc/.../FrameAccess.java`, `cadenza/.../frame_layout.kt:43-73`).
  Frames are never captured by closures: "`CapturedFrame` is selective heap storage, not a saved
  `VirtualFrame`" (`thc/docs/site/jvm.md`).
- **Object model.** Truffle **Static Object Model** (`StaticShape`) for closure environments
  (`thc/.../CaptureLayout.java:109-147,212-240`) and constructor payloads (`thc/.../DataLayout.java`), to get
  typed final primitive/reference fields per shape. No `DynamicObject`.
- **Language registration**: `contextPolicy = SHARED` (`thc/src/main/java/thc/Language.java:25-28`); per-context
  heap state, shareable code ("reusable AST code", `thc/docs/reusable-code.md`).

## 3. THC/Cadenza runtime representation and calls (item 3)

### 3.1 Closures and PAPs
`Closure{CapturedFrame environment; Object[] supplied; int arity; RootCallTarget target; int suppliedCount;
HandoffStorage typedSupplied}`, annotated `@CompilerDirectives.ValueType`, `supplied` is
`@CompilationFinal(dimensions = 1)` (`thc/.../Closure.java:11-33`). A PAP is the same class with a longer
`supplied` and smaller `arity`. Call packet layout: `[bloom, env?, supplied…, args…]`
(`thc/.../Dispatch.java:49-51`, `cadenza/.../dispatch.kt:63-64`). Truffle's residual call ABI is
`Object[] → Object`; primitives survive only inside one compilation unit (`thc/docs/call-boundaries.md`).

### 3.2 Thunks, indirections, update
`Thunk{RootCallTarget target; CapturedFrame environment; volatile int state; Object value; Thread owner;…}`
(`thc/.../Thunk.java`). Forcing is a node (`thc/.../Force.java`): non-thunk → return; state 2 → value;
otherwise claim under the thunk's monitor, call the body through a 3-entry target cache
(`DispatchThunkTarget.java:19-29`, packet `{0L}` or `{0L, env}`), publish. There are **no update frames**:
the `Force` activation is the update frame (`Force.java:676-698`). Blackholing = state 1 with
`owner == currentThread` ⇒ `"Blackhole: cyclic thunk entered while evaluating"` (`Force.java:264-267`).
Recursive `let` uses `RecCell` indirections captured by identity and published after the group is
initialised (`thc/.../RecCell.java`, `thc/docs/core-evidence.md` "Physical recursive cells"); Cadenza's
equivalent is `Indirection` (`cadenza/.../nodes.kt:209-212`, `code.kt:305-327`).

### 3.3 Data, unboxed primitives, typed execution
`DataValue` + `DataLayout` (StaticShape with exact primitive/reference fields; strict fields of known type
get concrete Java field types, lazy or polymorphic fields stay `Object`, `thc/docs/core-evidence.md`
"Precise reference storage"). Constructor match = Java class identity where a generated storage class has
a unique layout owner, else an explicit layout-pointer compare; an `Assumption`-guarded "exclusive
carrier class" variant is an opt-in experiment (`thc/.../DataLayout.java:216-227`,
`thc/.../ConstructorClassIdentity.java`, `thc/docs/entry-contracts.md`). `Expr` has typed entry points
`executeInt/Long/Float/Double/Closure/DataValue` backed by a Truffle type system
(`thc/.../Expr.java:91-167`). THC explicitly does **not** do small/big integer widening
(`thc/docs/typed-tail.md`); Cadenza does: `int` fast path, `BigInt` on overflow
(`cadenza/src/cadenza/data/DataTypes.java`, `cadenza/bench/results/2026-09-22/six-hour-impact.md`).

### 3.4 Tail calls (Cadenza design, kept by THC)
1. Mark tail positions syntactically (`cadenza/src/cadenza/jit/code_transforms.kt:3-10`).
2. Every root has a 64-bit mask with ≤ 5 bits from its identity hash
   (`cadenza/.../nodes.kt:40-46`, `thc/.../GuestRoot.java:100-106`); frame slot 0 / `args[0]` carry the OR of
   masks on the current tail chain (`0L` for a non-tail call).
3. Tail call to target T (`cadenza/.../tail_calls.kt:44-61`, `thc/.../TailCheck.java:21-28`): if
   `(mask & T.mask) == T.mask` throw `TailCallException(T, args)` (a `ControlFlowException`); else perform an
   ordinary nested call passing `mask`. Each nested tail call adds ≥ 1 new bit, so at most 64 frames
   accumulate; distinct functions in a tail chain stay ordinary (inlinable) calls.
4. A root catches a transfer aimed at its own body identity and loops via a `LoopNode`, rewriting its frame
   (`cadenza/.../nodes.kt:142-155`, `tail_calls.kt:193-221`; clones keep `bodyIdentity`,
   `nodes.kt:177-188`, `thc/.../GuestRoot.java:107-109`). The first iteration is peeled ("constant recursive
   arguments can still fold").
5. Non-tail call sites wrap the call in `try … catch (TailCallException)` and run a trampoline `LoopNode`
   whose `RepeatingNode` holds a 3-entry target cache (`tail_calls.kt:128-189`; copied from Enso's
   `LoopingCallOptimiserNode`, comment at `tail_calls.kt:141-142`).
6. THC adds: cycles close at the *matching* active root, not only at the outer trampoline
   (`thc/docs/typed-tail.md` "A → B → C → D → E → C"); bytecode self-tail calls restore locals and branch
   to a Bytecode DSL back-edge without building a packet (`thc/docs/bytecode.md`,
   `thc/.../BytecodeRoot.java:2893-2918,2962-2991`).

### 3.5 What comes from GHC rather than from the runtime
Let-floating, case-of-known-constructor, worker/wrapper, join points and strictness analysis are done by
GHC before export. THC only consumes the results as certificates: `evaluated` (WHNF), `entryStrict`
(call-by-value entry obligations enforced at saturation, including PAP prefixes), `callDemand`
(caller-side demand, opt-in `-Dthc.callDemands=true`, default off), `knownWhnf`/`safeToEvaluate`
(`thc/docs/core-evidence.md`, `thc/docs/entry-contracts.md`, `thc/docs/demand-probe.md`). Joins become
local control flow (`thc/docs/architecture.md`). RLVM has no such front end; the cheap syntactic
equivalents are listed in §1.3.

### 3.6 Memory tricks actually present (item 6)
- Typed `StaticShape` fields avoid boxing in environments/constructors; cost: one generated class per
  shape, plus the wrapper problem measured by Cadenza (§1.2). For RLVM's `Object`-only kernel values
  (no unboxed doubles/ints inside constructors) this buys nothing **[inference]**.
- Shared empty arrays, constant closures, nullary singletons, boxed small-int cache (§1.2).
- Thunk releases `target`/`environment` on update; a forced local is overwritten with its value.
- Fresh-thunk escape analysis: `Thunk.publishSuccess` is
  `@TruffleBoundary(transferToInterpreterOnException = false, allowInlining = true)` so "partial evaluation
  leaves the publication body outside its graph, while later Java inlining can recover escape analysis for
  fresh local thunks" (`thc/.../Thunk.java:56`, commit `2af0147f`).
- **In-place reuse / uniqueness: none.** "Immutable constructor fields and capture properties are not
  rewritten" (`thc/docs/thunk-updates.md`). The only reuse scheme is the opt-in argument "handoff slab"
  (`-Dthc.handoffSlabs=true`, default off, `thc/docs/handoff-slabs.md`): a per-context, per-layout reusable
  argument record with loan/generation checks; the doc itself says it "does not by itself prove
  allocation elimination or a performance gain". No evidence here for RLVM's few-bit refcount idea.
- No arenas for guest values. Off-heap only for mapped input files and byte arrays.

## 4. Keeping partial evaluation effective and cheap (item 4)

### 4.1 Mechanisms in use
- `@CompilationFinal(dimensions = 1)` on every metadata array (`thc/.../FunctionRoot.java:63-87`,
  `GuestRoot.java:34-53`), `@ExplodeLoop` over them (frame building `FunctionRoot.java:454`, capture
  `CaptureLayout.java:109`, case arms `Case.java:187`), and
  `@ExplodeLoop(kind = FULL_UNROLL_UNTIL_RETURN)` for the thunk-claim loop (`Force.java:211`, commit
  `a03f5571`).
- `@CompilerDirectives.ValueType` on `Closure` (`Closure.java:11`; Cadenza on `Closure` and `NeutralValue`).
- `BranchProfile` on rare control paths (tail bounce, resume, suspension: `Force.java:32-34`,
  `DirectCallerNode.java`), `InlinedConditionProfile` in the generic dispatch
  (`cadenza/.../dispatch.kt:128-129`), `@ReportPolymorphism` on dispatch nodes so splitting can
  de-pessimise them (`Dispatch.java:19`, `DispatchCallTarget.java`).
- Assumptions only for rare global mode flips (trace sink on/off `thc/docs/hints-and-tracing.md`;
  constructor-class exclusivity; "no graph failure" `thc/.../ContextRoot.java:18`). Where a flip would retire
  much code they deliberately use "the context's irreversible volatile guest-admission bit instead of an
  invalidatable assumption" (`thc/docs/reusable-code.md`).
- Boundary placement: cold protocol code (contention, failures, PAP construction, continuation capture)
  sits behind `@TruffleBoundary`, usually with `transferToInterpreterOnException = false`
  (`Force.java:59,67,83`, `Closure.java:35,38`). Commit subjects show this was iterative work: "Keep
  contended thunk retries outside ordinary inline demand" (`339d6f0c`), "Keep contended thunk notifications
  outside partial evaluation" (`84576e9a`), "Keep host wait bookkeeping outside guest compilation"
  (`4fcf8796`), "Keep operand resume construction outside guest compilation" (`ac50aade`).

### 4.2 Deoptimisation-loop rules they converged on (copy these)
1. **One-shot monotone flags instead of rewriting.** e.g. `@CompilationFinal volatile boolean seenThunk`
   in `Force` (`Force.java:35,50-56`), `observed` in `PreparedDispatch` (`PreparedDispatch.java:30,51-73`),
   `genericLong/genericClosure/genericDataValue` in `Evaluate` (`Evaluate.java:86-151`). Each flips at most
   once ⇒ bounded invalidations per node.
2. **Unobserved site reached from compiled code ⇒ take a generic `@TruffleBoundary` path, do not deopt.**
   `if (!seenThunk) { if (CompilerDirectives.inCompiledCode()) return executeCold(…);
   transferToInterpreterAndInvalidate(); seenThunk = true; }` (`Force.java:50-56`); same for call sites:
   "An untouched compiled site shares the existing generic application algorithm"
   (`PreparedDispatch.java:14-18,50-73`) and for unobserved bytecode branches (`thc/docs/bytecode.md`).
3. **Never re-execute after `UnexpectedResultException`.** "Widening consumes the value saved in
   `UnexpectedResultException`; it never retries the expression … repeatedly throwing that exception would
   repeatedly invalidate compiled code" (`thc/docs/typed-tail.md`; code `Evaluate.java:106-111`).
4. **Publication without invalidation.** A lazily initialised global is read as
   `prepared ? preparedValue : prepareValue()` where `prepareValue` is a boundary that re-reads the volatile
   flag, so code compiled before publication stays valid (`GlobalBinding.java:15-19,40-41,88-91` comments).
5. **Monotone frame-slot widening** (primitive → Object, never back). Cadenza tried per-frame tags
   (`FrameDescriptor.Builder.useSlotKinds(false)`) and rejected it: no gain, +16 B/op
   (`cadenza/bench/results/2026-09-22/frame-recovery.md`).
6. **Megamorphic sites get exactly one generic node**, never a second bounded cache (§1.4 row 5).
7. **Check order for the interpreter**: test `CompilerDirectives.inCompiledCode()` before loading a volatile
   flag, so the interpreter never pays the load (`FunctionRoot.java:636`, commit `25f9c855` "Skip cold-entry
   flag loads in the AST interpreter").

### 4.3 Engine options
See §1.7 table (`thc/src/main/java/thc/Main.java:35-47`). Also: automatic loop vectorisation is disabled
(`-Djdk.graal.VectorizeLoops=false`, `thc/README.md:232-237`); test contexts use
`engine.BackgroundCompilation=false` and `engine.CompilationFailureAction=Throw`.

### 4.4 The 1 000-node graph budget and "terminal targets" (a war story, not a recommendation)
THC sets `compiler.MaximumGraalGraphSize=1000` ("speculative graph budget", `thc/docs/ast-case-boundaries.md`
"Fresh-entry recovery"; option semantics **[verified 25.5.5.0.3]**: "Stop partial evaluation when the graph
exceeded this size, disabled if < 0. (default: -1)"). Consequences visible in the repo:
- compilation failures are routine; the failed target is *retired* and, at the next fresh entry, the
  function is cloned with one case arm / join argument outlined into a side root, then retried
  (`thc/docs/ast-case-boundaries.md`, `thc/tools/truffle-protocol/README.md` "Graph-budget recovery
  lifecycle", `thc/.../GraphRecovery.java`);
- this needed three runtime patches (`graph-budget-runtime`, `terminal-target-runtime`,
  `terminal-splitting-runtime`) to stop stock Truffle from re-queuing or re-arming failed targets through
  splitting and partial-block retries (`thc/docs/truffle-patches.md`);
- bytecode roots are pre-partitioned: constructor cases whose arms reach a preparation weight of 64 get one
  side root per arm "from the first compilation" (`thc/docs/bytecode.md`);
- it is still not closed: "FastSmoke still fails its original first-compilation check at the unchanged
  graph limit" (commit `d5d741c6`, 2026-11-11).
Lesson for RLVM: small compilation units are good (rule RHS and lambda bodies are natural units), but an
aggressive graph cap converts compile cost into failure-handling complexity. Use the cap as a safety net
only.

## 5. "JAM-patched GraalVM" and the Truffle patches (item 2)

### 5.1 What JAM is
A separate project (`github.com/ekmett/jam`) providing **a new garbage collector integrated into HotSpot,
the Graal compiler and SubstrateVM**, selected with `-XX:+UseJamGC` / `--gc=jam`. Purpose: Haskell
`System.Mem.Weak` semantics (key→(value, finalizer) associations resolved to a fixed point, finalizers that
may resurrect keys) via `jam.vm.Weak` and the `jam.vm.Lifted` value protocol (`resolve()`, `project(n)`)
(`thc/docs/jam-runtime.md:1-16,96-152`). Evidence that it is a GC port, not a JIT change:
`thc/src/vm/patches/hotspot-jam.patch` (3 680 lines) adds `src/hotspot/share/gc/jam/*` (heap, barrier sets
for C1/C2/assembler, `jamWeak.cpp`); `thc/src/vm/patches/graal-jam.patch` (990 lines) touches
`HotSpotAllocationSnippets`, write-barrier snippets, `CompressionNode`, Truffle safepoint phases and
SubstrateVM heap/thread classes. Operational constraints: ordinary (non-compact) object headers, CDS off,
fixed heap `-Xms == -Xmx` (`thc/build.gradle:263-268`, `thc/docs/jam-runtime.md:49-53`). Pinned package:
"Jam VM" 25.3.4.1-dev, Java 25.0.4.1 (`thc/etc/jam-graalvm.json`).

### 5.2 The separate Truffle/Sulong JAR patches (`thc/docs/truffle-patches.md`, `thc/tools/truffle-protocol/`)
| Patch | Purpose | Needed by RLVM? |
|---|---|---|
| `materializable-root` | declare before first compile that a root's frame may be materialised (continuations) | no (no continuations) |
| `declared-return-*` | root may return a value *or* a saved continuation: start with a polymorphic return profile | no |
| `RootNode.assumeArgumentCount` | expose exact argument-array length to PE | no (minor) |
| `graph-budget-*`, `terminal-target`, `terminal-splitting` | retire failed compile targets permanently | no, if the graph cap is not abused (§4.4); use `engine.MaximumCompilations`, `CompilationFailureAction=Silent`, `prepareForCompilation` |
| OSR submission locking, `mount-carrier-lookup` (virtual threads), AOT exception profile | concurrency / AOT cache | no |
| Sulong globals / Windows lookup | native C dependencies of Haskell packages | no |

`-Pthc.stockTruffle=true` builds THC against unpatched artifacts, with the stated loss of those
first-compilation/recovery contracts only.

### 5.3 The version-mismatch hazard, and RLVM's situation
`thc/README.md:50-52`: "incompatible Graal and Truffle compiler versions can disable runtime compilation and
leave execution in the interpreter." Mechanism **[verified 25.5.5.0.3]**: since Truffle is consumed from
Maven, `truffle-runtime` checks the JDK's compiler version at start-up
(`com.oracle.truffle.runtime.hotspot.HotSpotTruffleRuntimeAccess` contains `MIN_COMPILER_VERSION`,
`MIN_JDK_VERSION`, `MAX_JDK_VERSION` and the message "Your Java runtime '%s' with compiler version '%s' is
incompatible with polyglot version '%s'."); on failure `com.oracle.truffle.api.Truffle` falls back to
`com.oracle.truffle.api.impl.DefaultTruffleRuntime` (strings: "No optimizing Truffle runtime found on the
module or class-path."; explicit selection via `-Dtruffle.UseFallbackRuntime`).
- RLVM toolchain: `release` says `GRAALVM_VERSION="25.5.5.0.3"`, `lib/truffle/truffle-compiler.jar` manifest
  `Implementation-Version: 25.5.5.0.3`, `UseJVMCICompiler=true`, `EnableJVMCI=true`. Maven Central lists
  `org.graalvm.truffle:truffle-api:25.5.5.0.3` (checked 2026-11-23). **Pin all `org.graalvm.*` artifacts to
  exactly 25.5.5.0.3**; nothing needs patching.
- Add a start-up assertion: if compilation is expected and
  `Truffle.getRuntime().getClass().getName().equals("com.oracle.truffle.api.impl.DefaultTruffleRuntime")`,
  fail loudly (exit code ≠ 0/1/2) instead of silently running slow. The arena runner is x86-64 while the
  dev host is aarch64: run this check on both.

## 6. Benchmarks and war stories (item 7)

### 6.1 Cadenza, 2026-09-22 (Apple M3, GraalVM 25.3.4.1, JMH, 2 forks; `cadenza/bench/results/2026-09-22/`)
| Measurement | Result | File |
|---|---|---|
| Fibonacci 15–18, guest compiled vs `engine.Compilation=false` | 11.16 µs vs 711.6 µs (**64×**); 10–13: 1.03 vs 67.4 µs | `argument-array.md` |
| Per guest call **[my arithmetic: mean 4 673 calls/op for n=15..18]** | ≈ 2.4 ns compiled, ≈ 152 ns Truffle-interpreted (workload goes through a `fixNatF` builtin) | — |
| AST backend vs Bytecode-DSL backend (both JIT) | bytecode slower: counter 1.36×, accumulator 2.49×, fib 7.32×; escaping closures 0.90× (80 vs 120 B) | `README.md` |
| Self-tail accumulator after frame reuse + typed slots | 9.10 → 3.18 µs, 110 776 → 56 B/op (2.86×) | `final-comparison.md` |
| Fibonacci after six hours of call-path work | 69.4 → 15.1 µs, 748 080 → 50 173 B/op (4.6×, −93% allocation) | `final-comparison.md` |
| Primitive capture restored directly into typed frame slot | 6.78 → 5.19 µs, 16 360 → 224 B/op | `capture-restore.md` |
| Remaining allocation in compiled fib | 99.75% = 32-byte argument arrays `[bloom, self, x]` | `fib-allocation.md` |
| 2020 data: plain Kotlin de Bruijn interpreter vs Truffle-compiled (`cadenza/benckmarks.md`) | Fib 0.234 vs 0.026 ms (9×); Add 0.102 vs 0.001 ms (~100×, loop folded away) | `benckmarks.md` |

**[inference]** Combining rows 1 and 8 (different years, machines and inputs — order of magnitude only): a
plain JVM interpreter is ~9× slower than Truffle-compiled code but ~7× *faster* than Truffle AST nodes
running uncompiled. This is the quantitative basis for §0.2.

### 6.2 Cadenza neutral-path experiment (`neutral-tracing.md`)
| Workload | Exception design (shipped) | Value-returning neutrals (rejected) |
|---|---|---|
| Closed Fibonacci 15–18 | 10.87 µs, 50 173 B/op | 19.59 µs (+80%), 95 745 B/op (+91%) |
| Symbolic arithmetic DAG | 0.578 µs | 0.0355 µs (16.3× faster) |
| Symbolic conditional/recursive sums | 5.525 µs | 0.200 µs (27.7× faster) |
| Symbolic higher-order/partial application | 0.610 µs | 0.0304 µs (20.1× faster) |

Also: with the shipped design, a test that feeds neutrals into *compiled* code fails "because repeated
deoptimizations triggered the configured compilation failure action" — every neutral deoptimises. Their
decision: keep the exception; "Selecting a separate normalizer at a known normalization boundary is a
possible future design." That separate normaliser is RLVM's T0.

### 6.3 THC compile-cost data (my aggregation of archived trace lines)
`grep 'opt done'` over `thc/docs/**/*.log` (24 files, e.g. `thc/docs/bytecode-graphs/ast-capture.log`,
`thc/docs/cadenza-graphs/*-run.log`), all Tier 2, Apple M3, **IGV graph dumping enabled** (inflates mostly
the back-end part): n = 118; total 168 s; mean 1 424 ms (PE 219 ms + back end 1 205 ms); median 532 ms;
p90 3 519 ms; max 12 341 ms; mean AST size 299 nodes → mean IR 5 635 nodes → mean code 25.5 KB. Example
line: `lambda sc, x, ds1 |Tier 2|Time 3294( 425+2869)ms|AST 772|Inlined 9Y 21N|IR 5612/11018|CodeSize 52392`.
The same logs contain 17 deopt/invalidation events. THC publishes no throughput figures.

### 6.4 What gave the largest gains (Cadenza, measured)
1. Frame reuse for self tail calls + typed frame slots (2.8×, allocation → ~0).
2. Removing per-call allocations on the fixed-point path: retained self closure, direct original-target
   call (fib 4.6×, −93% allocation).
3. Keeping primitives primitive across capture restore (1.3–1.6× on affected loops).
4. Allocation-only wins without measurable time change: constant closures, no env wrapper, single-copy PAP,
   direct argument-array fill.

### 6.5 What failed (measured and reverted)
| Experiment | Outcome | File |
|---|---|---|
| Unary "bound self" entry root (smaller arg array) | −17% allocation but **2.5–2.6× slower** | `bound-self-root.md` |
| Identity profile on the recursive closure argument | allocation unchanged, small fib **+69% time** | `fixed-self-profile.md` |
| Per-frame slot tags instead of shared descriptor kinds | no gain, +16 B/op | `frame-recovery.md` |
| Value-returning neutrals | closed fib 80% slower (10.87 → 19.59 µs), +91% allocation | `neutral-tracing.md` |
| Neutral exception as `ControlFlowException` | compiled code too large to install (AArch64) | `neutral-tracing.md` |
| Recursive inlining + loop peeling (2020) | fib 0.077 ms vs 0.026 ms without | `cadenza/benckmarks.md` |
| Bytecode-DSL backend (Cadenza, current state) | 1.4–7.3× slower than AST | `README.md` |
| THC opt-in experiments still default-off, no claimed gain | handoff slabs, boxed-value cache, call demands, leading-case return, static-shape unchecked, constructor class identity | `thc/docs/handoff-slabs.md`, `boxed-values.md`, `demand-probe.md`, `call-boundaries.md`, `entry-contracts.md` |

Pattern: "obvious" micro-optimisations of the calling convention frequently made Graal's output worse;
every change was gated on a matched before/after measurement. RLVM should gate on instruction counts the
same way.

## 7. Interpreter-mode performance (item 5)

What the sources contain:
- **No host-inlining annotations anywhere**: no `HostCompilerDirectives.BytecodeInterpreterSwitch`,
  `InliningCutoff`, `EarlyInline` in `thc/src/main/java` or `cadenza/src` (grep). Interpreter speed is not a
  goal of either project; THC measures after warm-up against native GHC.
- **No superinstructions.** THC relies on what the Bytecode DSL generates: quickening and boxing
  elimination for `int/long/float/double/boolean` (`BytecodeRoot.java:51-52`), typed operations
  (`ForceValue`, `ReadDataField`, `CaptureRead` have primitive specialisations, `BytecodeRoot.java:551-601,
  3124-3138`), constant operands for layouts/arity (`@ConstantOperand`), and `ForceLocal` fusing
  force + write-back (`BytecodeRoot.java:616-653`).
- **Interpreter-relevant micro-rules** that do appear: direct argument-array fill (Cadenza, −7.8…−12.9%
  interpreter allocation); skip empty-prefix copies; `needsCallState()` so leaf bodies skip per-call
  bookkeeping (`thc/.../Expr.java:79-90`, `FunctionRoot.java:647-650`); "Return evaluated force operands
  before AST root lookup" (commit `24490d1f`); rule 7 in §4.2.
- **Lazy lowering** is the main cold-code trick: bindings are decoded and lowered only when first read
  (`GlobalBinding.defer`, `thc/docs/architecture.md`), with an optional parallel pre-lowering pool
  (`thc/docs/reusable-code.md`).

What the toolchain offers beyond the sources **[verified 25.5.5.0.3 for existence; effect unmeasured]**:
`@GenerateBytecode` has `enableUncachedInterpreter`, `defaultUncachedThreshold`, `enableQuickening`,
`enableThreadedSwitch`, `enableInstructionRewriting`, `inlinePrimitiveConstants`;
`HostCompilerDirectives.{BytecodeInterpreterSwitch, BytecodeInterpreterHandler, InliningCutoff, InliningRoot}`
exist; Graal host inlining is on by default (`TruffleHostInlining=true`, base budget 5 000, explore budget
10 000, bytecode-interpreter budget 100 000, min call frequency 0.001). External evidence: Larose, Kaleba,
Burchell, Marr, "AST vs. Bytecode: Interpreters in the Age of Meta-Compilation" (OOPSLA 2023,
doi:10.1145/3622808): on GraalVM and RPython, AST and bytecode interpreters are on par after JIT, AST is on
par or slightly faster interpreter-only, bytecode is more compact.

Consequence for RLVM: T0's speed must come from ordinary Java engineering (monomorphic switch, no boxing,
fixed-arity apply entries, few objects per step), not from Truffle. If a Truffle tier-0 is ever
reconsidered, the only candidate is a Bytecode-DSL *uncached* interpreter, and Cadenza's current numbers
say its bytecode backend is slower than its AST even when compiled.

## 8. Cadenza: NbE on Truffle nodes and frames (item 8)

- **Values** (`cadenza/src/cadenza/data/`): `Closure`, `int`, `boolean`, `BigInt`, `Unit`, and
  `NeutralValue(type, Neutral)` with `Neutral = NIf(cond, then, else) | NCallBuiltin(builtin, args) |
  NApp(rator, rands)` (`nbe.kt:16-38,41-70`); listed in the type system `DataTypes.java`. Neutrals carry
  their type (needed for arity).
- **Two execute protocols on every node** (`cadenza/src/cadenza/jit/code.kt:41-57`):
  `execute(frame)` — the fast path, `@Throws(NeutralException)`, never *returns* a neutral;
  `executeAny(frame) = try { execute(frame) } catch (e: NeutralException) { e.get() }` — used wherever a
  value is merely transported (arguments, `let` right-hand sides, function results).
  `NeutralException extends SlowPathException` (`NeutralException.kt:6`), so PE compiles no handler code.
- **Where neutrals are produced/consumed**: `Var.execute`/`Arg.execute` raise if the slot holds a
  `NeutralValue`, `executeAny` returns it as is (`code.kt:110-115,201-215`); `App.execute` on a neutral head
  evaluates the operands with `executeAny` and rethrows `NApp` (`code.kt:92-102`); over-application of a
  neutral result extends the spine (`dispatch.kt:87,149`); `If` on a neutral condition normalises **both**
  branches (each under its own trampoline, since they are no longer tail positions) and throws `NIf`
  (`code.kt:129-150`); builtins with any neutral operand build `NCallBuiltin` (`code.kt:240-272`,
  `nodes.kt:122-128`).
- **Closures and frames**: one `ClosureRootNode` per lambda; frame slots for parameters and for the used
  captures, copied in at entry (`nodes.kt:190-198`); environment = `StaticShape` object with a primitive
  and an object arm per `Nat`/`Bool` capture (`cadenza/src/cadenza/frame/capture_layout.kt:45-104`).
  Evaluation is **call-by-value**; there are no thunks (`cadenza/TODO.md`: "Eventual laziness or CBPV").
- **Missing half**: no `quote`, no fresh variables, no conversion check; neutrals are injected only by
  tests/interop (`cadenza/test/neutral.kt:26-27`). The README still says the package "will eventually
  provide" NbE. The bytecode backend "targets closed terms with concrete runtime values"
  (`cadenza/docs/bytecode.md`).
- **Lessons**: (1) the exceptional-neutral design is right exactly when neutrals are rare — true for a
  closed-term accelerator, false for a type checker; (2) a single evaluator cannot serve both without
  paying on one side (§6.2); (3) under a binder, "tail position" stops being tail position (the `If` case),
  so symbolic evaluation needs its own control structure; (4) the closure/PAP/dispatch design is
  independent of the neutral question and can be reused as is.

## 9. HOAS/NbE on the JVM: what the two normalization-bench repos add

(Full treatment: sibling document `docs/research/09-nbe-hoas-tricks.md`; only the JVM-relevant points here.)
- `normalization-bench-kovacs/scala/normalization-bench/HOAS.scala` is 170 lines of deep HOAS
  (`VLam(t: Val => Val)`). With `-Xss1000M -Xmx4G` on OpenJDK 8 it matches GHC HOAS on tree benchmarks
  (Tree 2M normalisation 85 ms vs 86 ms; Tree 8M conversion 625 vs 723 ms) and collapses on deep ones (Nat
  10M normalisation 3 908 ms vs 227 ms; GraalVM 21 as JIT: 5 833 ms)
  (`normalization-bench-kovacs/README.md:94-105`). Author's remarks: Scala/nodejs "performance becomes
  dismal without free RAM", occasional multi-second GC pauses and OOM kills; all JIT entries are CBV and
  "CBN is essential for type checking … there is a good chance that CBN would be a significant performance
  hit" (`README.md:158-172`); conclusion "At the moment it does not seem worth to write high-performance
  proof assistant implementation in any of the JIT languages" (`README.md:182-190`).
- `normalization-bench-guest0x0/README.md` (OCaml): closures-with-environment and HOAS perform the same
  (`:387-393`); linked-list environments beat trees and skew lists (`:366-386`); the abstract machine with
  heap-allocated continuations has "a stable constant overhead" over recursive NbE (`:400-408`); for
  machines, inlining the continuation's tail into each frame object beats list or array stacks
  (`:613-652`); compiling terms to host code is fast to run but "the overly long compilation time renders
  these efficient approaches impractical for most everyday type checking, where the amount of computation
  is small but term sizes are large" (`:729-731`); "data layout in memory seems to have a very significant
  effect … one less indirection means more performance" (`:733-737`).
- Implications for RLVM **[inference]**: (a) plain closures on the JVM are competitive when the stack is
  shallow and the heap roomy — both are scarce under a 16 GB / instruction-count regime, so depth control
  and allocation discipline come first; (b) the compile-time warning applies verbatim to Truffle PE.

## 10. Other Truffle (or meta-compiled) lazy/functional/proof-assistant implementations

| Project | What | Transferable point | Status of my check |
|---|---|---|---|
| **Montuno** — J. Zárybnický, MSc thesis, Brno UT 2021, "Just-in-Time Compilation of the Dependently-Typed Lambda Calculus" | λΠ dependently typed language; a plain Kotlin interpreter and a Truffle one compared on normalisation and elaboration tasks | Abstract: "The results are strongly negative … the influence of JIT compilation is not noticeable given the large overhead of the JVM platform." Closest prior art to RLVM; supports §0.1–0.2 | abstract read (fit.vut.cz thesis 24198); thesis body not read |
| **Enso** (enso-org/enso) | functional, Truffle | source of Cadenza's trampoline (`LoopingCallOptimiserNode`); `TailCallException` design | only the Cadenza comment verified; Enso source not read |
| **FastR** (R promises are thunks) | lazy arguments on Truffle | eager evaluation of cheap promise expressions under an assumption | [unverified, from memory] |
| **rpylean** (RPython, not Truffle) | JIT-driven CEK machine for closed Lean terms only | the scope RLVM's T2 should have; see doc 07 §1.10 | sibling doc |
| Larose et al., OOPSLA 2023 | AST vs bytecode on Truffle/RPython | §7 | abstract-level |
| Mozart-Graal (Oz), Yona, shen-truffle, Horák 2023 thesis | strict or dataflow functional languages on Truffle | tail-call optimisation via exceptions/loop nodes | search results only |

No Truffle implementation of Lean, Coq, Agda or Idris was found.

## 11. Risks, and things not verified

- THC compile times in §6.3 include graph dumping; real Graal costs on the arena's x86-64 runner are
  unmeasured. The `PROMOTE` threshold and budget in §1.7 are derived, not measured.
- The fraction of Mathlib instructions spent in closed, repeatedly executed code (the only thing T2 can
  speed up) is unknown here; rpylean reports ~0–1.6% effect of its JIT on library corpora (doc 07 §0.1).
  If RLVM's T0 profile says the same, T2 should stay switched off for the arena run.
- `prepareForCompilation == false` "defers" a root compilation; how often the runtime re-attempts and what
  each attempt costs was not measured.
- `enableThreadedSwitch`, `enableTailCallHandlers`, `enableInstructionRewriting` exist on
  `@GenerateBytecode` in 25.5.5.0.3; their semantics/effects were not examined.
- Whether `-XX:-UseJVMCICompiler` (C2 for host code, Graal only for Truffle) lowers total instructions for
  a short, Java-heavy run was not tested; with it, Graal host inlining of Truffle interpreter code is lost.
- THC history before 2026-09-28 was not fetched (`--depth 500`); the `legacy` branch is an unrelated 2015
  C++ RTS. THC issues on GitHub were not read.
- Enso, FastR and the Montuno thesis body were not read; statements about them are limited as marked.
