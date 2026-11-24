# 10 - GraalVM / Truffle platform engineering for RLVM (measured)

Status: experiments run on the project host (aarch64, Cortex-X925/A725, 20 cores, 121 GB) with
GraalVM CE 25.5.5.0.3 (`.toolchain/graalvm-ce-25`) and Oracle GraalVM 25.0.4
(`.toolchain/oracle-graalvm-25`). Spikes, scripts and raw logs: `spikes/platform/` (README there).
All "G" figures are **user-space instructions in units of 1e9, summed over all threads**, measured
with `perf stat -e instructions:u` inside the infra team's container
(`bench/arena-docker.sh --arena-limits` = 8 CPUs / 16 GB, `bench/measure.sh`), i.e. the arena's
ranking metric. Arena "virtual seconds" = G / 6. CPU-time figures are labelled as such.

Scope note. The user's request for this workflow was to check HOAS/NbE tricks and the two
normalization-bench repositories. This document is the platform half of that: the benchmark of
`refs/normalization-bench-kovacs` was ported to Java and both repositories' conclusions were
re-measured on JDK 25 / native-image (section 10); the algorithmic side is in
`09-nbe-hoas-tricks.md`.

---

## 1. Decisions first

### 1.1 Recommendation table

| | A. JVM mode, C2 host JIT, SerialGC (GraalVM CE JDK) | B. native-image CE | C. native-image Oracle GraalVM + PGO |
|---|---|---|---|
| Fixed overhead per run | JVM start 0.3-1.2 G; +3.5 G if a Truffle engine is created; host JIT warm-up 0.1-0.3 G for a 10-method kernel, 65 G for a javac-sized hot code base (C2 only), 110 G (C2 tiered) | 0.015 G | 0.015 G |
| Steady-state host code (instructions, lower is better) | 1.00 (reference) | 1.19-1.60 x | 0.88-1.44 x (up to 1.9 x on code the training run did not cover) |
| GC cost on a 2.5 GB live hash-consed heap (GcBench, total incl. mutator) | 54-58 G (mutator alone 35.7 G) | 112 G with `-Xmn4g`, 233 G with defaults | 64 G (serial); G1 255-279 G |
| Truffle guest compilation | works (libgraal); guest code 0.85-2.4 x the instructions it needs under SVM; first compilations pay JVM warm-up | works; cheapest fixed cost | works; same code quality as CE in our tests |
| Object header | 12 B (8 B with `-XX:+UseCompactObjectHeaders`, +2-3 % instructions) | 8 B | 8 B |
| RSS overhead beyond the Java heap | 40-300 MB | 15-60 MB | 15-60 MB |
| Build | `javac` + 9 jars (8 runtime, 1 annotation processor); 15 s; no C toolchain | + gcc and zlib; 40-120 s; 460-660 CPU-s; 39 MB binary | + training run and two more image builds (+2-3 min); 43 MB |
| Main risks | JIT cost grows with the amount of warm code; run-to-run variation about 1 % | C toolchain/zlib in the arena shell unverified; slow GC; `MemorySegment`/`VarHandle` traps | GFTC licence accepted by whoever runs the build; mis-training makes code slower; training input needed at build time |

### 1.2 Recommended configurations

**Primary: A (JVM mode).** It has the best steady-state code and by far the cheapest GC in
instructions, the simplest build, and its fixed costs are small against a 400-600 G budget as long
as the hot kernel stays compact.

```
$GRAALVM/bin/java \
  -XX:+UseSerialGC -Xms8g -Xmx12g -Xmn4g \
  -XX:-UseJVMCICompiler \
  -XX:-TieredCompilation \
  -XX:-UsePerfData \
  --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow \
  -Dpolyglot.engine.WarnInterpreterOnly=false \
  -cp rlvm.jar:lib/* rlvm.Main "$IN"
```

* `-XX:+UseSerialGC`: G1 (the default) costs 3.8-5.7 x the instructions of Serial on GcBench; ZGC 4 x;
  Parallel 1.25-1.4 x (section 8.1).
* `-Xms` = large and `-Xmn4g`: Serial with JVM default sizing does 106 young collections instead of
  3 (83.8 G instead of 54.4 G); with deep recursion every extra collection walks the whole stack
  (section 9.2).
* `-XX:-UseJVMCICompiler`: makes C2 the top-tier host JIT. On the GraalVM JDK the default is the Graal
  JIT (libgraal), which spent 1.7-2.4 x the total instructions of C2 on the javac workload (section 6).
  Truffle keeps using libgraal for guest code with this flag (verified).
* `-XX:-TieredCompilation`: 20-24 % fewer total instructions on javac, equal on the plain interpreter
  workload; never worse in our tests. Re-validate on the real checker (A/B, one flag).
* Optional `-XX:+UseCompactObjectHeaders` if RSS is at risk (2-ref node 24 -> 16 B); it cost
  +1.6-3 % instructions.
* Run the checker in `new Thread(null, r, "checker", 4L << 30)`; do not rely on `-Xss`.
* If Truffle compilation is used at all, add
  `-Dpolyglot.engine.FirstTierCompilationThreshold=25000 -Dpolyglot.engine.LastTierCompilationThreshold=2000000000 -Dpolyglot.engine.CompilerThreads=1`
  (tier 2 off) and never `engine.Mode=latency` or `engine.BackgroundCompilation=false` (section 5).

**Fallback 1: C (Oracle native image with PGO, serial GC)** if the measured JIT overhead of the real
checker on mathlib exceeds about 15 % of its total, or if Truffle-compiled guest code turns out to
dominate:

```
native-image --pgo=<profiles> -march=native -cp rlvm.jar:lib/* rlvm.Main -o rlvm
./rlvm -Xms8g -Xmx12g -Xmn4g "$IN"
```

**Fallback 2: B (CE native image)**, same run flags, when Oracle binaries may not be downloaded.
`-Xmn` is mandatory: the default young generation is at most 10 % of the maximum heap.

### 1.3 The twelve findings that drive this

1. **CPU time is a misleading proxy on this host.** It executes 7.7 G instr/s on a C loop, 17.6 G/s on
   CPython, 24 G/s on Java interpreter loops but only 1.6 G/s in memory-bound phases. Cache misses are
   free in the arena metric; instruction-dense code is not. Everything below is in instructions.
2. **GC choice is the largest single platform lever**: Serial 54 G, Parallel 75 G, G1 204 G, ZGC 221 G on
   the same workload (mutator 35.7 G).
3. **CE native-image code executes 19-60 % more instructions than HotSpot-JIT code** on our
   interpreters and NbE kernels, and JIT warm-up is small for small kernels. Oracle PGO closes part
   of the gap (0-20 %) and beat the JIT only on one workload.
4. **The Graal JIT as host compiler is expensive**: javac x60 costs 439 G (CE) / 315 G (Oracle) against
   181-228 G with C2.
5. **A Truffle compilation costs about 1 M instructions per AST node at tier 1 and about 2.5 M per
   inlined node at tier 2.** With default thresholds (400 / 10 000 calls) compiling was a net loss
   until a function had run more than about 100 000 times.
6. **Truffle interpreters are slower than a plain-Java interpreter before compilation**: AST 1.5-2.7 x,
   Bytecode DSL 2.1-4.2 x the instructions of the plain closure-tree interpreter on the same programs.
7. **Truffle-compiled code is 10-50 x cheaper than interpretation** on hot recursive guest code, but
   given finding 5 compilation is worth it only for code that is provably hot and long-running.
8. **Deep host recursion multiplies GC cost**: one collection with a 10-million-frame stack costs
   about 10 G on HotSpot and 5 G on SVM. Keep collections rare or the stack shallow.
9. **JVM start-up is irrelevant** (0.3-0.5 G); the JDK 25 AOT cache does not reduce total
   instructions.
10. **`int[]` is the right packed storage on both platforms**; `MemorySegment` is 20-250 x slower in a
    CE native image.
11. **Bytecode DSL in 25.x is usable but young**: three generator/runtime defects hit in one day
    (section 4.3); API differs between 25.0.x and 25.5.x.
12. **The arena's `jdk` is OpenJDK 21** (no Graal compiler, no compact headers): RLVM must download
    its own GraalVM in the build step; a tested script is in `spikes/platform/arena/`.

---

## 2. Method and calibration

* Host perf counters are closed (`perf_event_paranoid=4`). `spikes/platform/scripts/perf.sh` wraps
  `bench/arena-docker.sh [--arena-limits] bench/measure.sh -- cmd`. The infra team validated the
  counter against a C loop with a known count (ratio 1.0000, `work/perf-validate/report.json`).
* Steady state per iteration = (long run - short run) / (iteration difference). Fixed overhead =
  short run - iterations x steady.
* Instruction rate of this host by workload: C loop 7.7 G/s; CPython 17.6 G/s; plain-Java
  interpreter 23.9 G/s (76.0 G in 3.18 s CPU); Truffle compiler threads about 8 G/s (estimate from
  27 M instructions per 3.24 ms compilation); GcBench with
  SerialGC 1.56 G/s (54.4 G in 34.8 s). One CPU-second here is therefore 0.26-4 arena virtual seconds
  depending on what runs.
* Not verified: x86-64. Absolute counts will differ on the arena runner; conclusions rely on ratios
  of 1.2 x and more.
* Run-to-run variation seen on repeated JVM runs: about 1 % (311.9 vs 312.0 G; 274.9 vs 277.2 G).

Benchmarks (all self-written, `spikes/platform/`):

| name | what it models |
|---|---|
| `lc` spike, programs `fib`, `tree`, `many:K` | guest-language evaluation in three back-ends (plain Java, Truffle AST, Truffle Bytecode DSL) |
| `NbeBench` | NbE kernel: eval / quote / conversion on untyped terms (port of Kovacs' benchmark) |
| `GcBench 40 200` | 40 M long-lived hash-consed nodes (1.3 GB + 0.5 GB table) and 200 M short-lived nodes |
| `JavacBench` | javac in-process on 21 081 lines: thousands of warm methods, tree walking, allocation |

---

## 3. Dependencies and build (question 1)

### 3.1 Versions

`$JAVA_HOME/release` of the installed CE build: `JAVA_VERSION="25.0.5.0.3"`,
`GRAALVM_VERSION="25.5.5.0.3"`, `JAVA_RUNTIME_VERSION="25.0.5.0.3+1-jvmci-25.5-b26"`. The Truffle
artifacts must carry the **GRAALVM_VERSION**:

| Maven coordinate | version | scope |
|---|---|---|
| `org.graalvm.polyglot:polyglot` | 25.5.5.0.3 | compile |
| `org.graalvm.truffle:truffle-api` | 25.5.5.0.3 | compile |
| `org.graalvm.truffle:truffle-runtime` | 25.5.5.0.3 | runtime (pulls `truffle-compiler`, `org.graalvm.sdk:{collections,nativeimage,word,jniutils}`) |
| `org.graalvm.truffle:truffle-dsl-processor` | 25.5.5.0.3 | annotation processor path only |
| `org.graalvm.truffle:truffle-enterprise` | 25.5.5.0.3 / 25.0.4 | optional, GFTC licence (per its POM); not tested |

Maven Central lists two lines for JDK 25: `25.0.x` (matches Oracle GraalVM 25.0.4 and CE `jdk-25.0.x`
releases) and "innovation" releases `25.1.3 ... 25.5.5.0.3` (CE tags `graal-25.N...`, file names
`graalvm-community-jdk-25iN-...`). `org.graalvm.nativeimage:truffle-runtime-svm` exists on Central
only up to 25.0.5.x; for 25.5 it is inside the GraalVM (`lib/truffle/builder/truffle-runtime-svm.jar`)
and is selected by `--macro:truffle-svm`, which `truffle-runtime.jar` requests through its embedded
`META-INF/native-image/org.graalvm.truffle/truffle-runtime/native-image.properties`.

Mismatch behaviour (verified):

| combination | result |
|---|---|
| Truffle 25.0.4 jars on CE 25.5.5.0.3 JDK | `java.lang.InternalError` in `LibGraal.initializeLibgraal` (crash) |
| Truffle 25.5.5.0.3 jars on Oracle 25.0.4 JDK | silent fallback to the interpreter-only runtime plus the warning below |
| `-XX:-EnableJVMCI` | `InternalError: JVMCI is not enabled` |
| `-Dtruffle.UseFallbackRuntime=true` | interpreter-only runtime plus warning |

The warning text in 25.x: `[engine] WARNING: The polyglot engine uses a fallback runtime that does
not support runtime compilation to native code.` followed by the cause (for example `Version check
failed. Your Java runtime '25.0.4+7-LTS-jvmci-b01' with compiler version '25.0.4' is incompatible
with polyglot version '25.5.5.0.3'`). Treat its appearance as a build failure in CI. It is
suppressed by `engine.WarnInterpreterOnly=false`.

### 3.2 Enabling the optimising runtime in JVM mode

Nothing is needed on a GraalVM JDK: `-XX:+PrintFlagsFinal` shows `EnableJVMCI=true`,
`UseJVMCICompiler=true`, `UseJVMCINativeLibrary=true`, and `lib/libjvmcicompiler.so` (libgraal,
40.6 MB) contains the Truffle compiler. With the jars on the **class path** and no flags,
`--engine.TraceCompilation=true` prints

```
[engine] opt done   engine=1  id=2  fib  |Tier 1|Time  74( 65+10 )ms|AST 23|Inlined 0Y 2N|IR 150/ 450|CodeSize 1872|...
[engine] opt done   engine=1  id=2  fib  |Tier 2|Time  57( 28+29 )ms|AST 23|Inlined 6Y 8N|IR 398/1260|CodeSize 5480|...
```

and fib(30) drops from 114 ms (first iteration) to 13 ms. Two JDK warnings appear and are silenced
by `--enable-native-access=ALL-UNNAMED` (module path: `=org.graalvm.truffle`) and
`--sun-misc-unsafe-memory-access=allow`. Truffle on the module path with the application on the
class path also works (`-p lib --add-modules org.graalvm.polyglot,org.graalvm.truffle.runtime -cp
rlvm.jar`); there is no measured benefit, and the internal compile API of section 5.4 then needs
`--add-exports`. Recommendation: class path.

A stock OpenJDK (the arena's `jdk` is OpenJDK 21, section 11) has JVMCI but no Graal compiler; it
would need the compiler jars on `--upgrade-module-path` and would run the compiler as interpreted
then JIT-compiled Java. Not tested; not recommended.

### 3.3 Build tool

Recommendation: **Maven for development, no build tool on the arena.**

* Maven 3.9.16 project (`spikes/platform/truffle-lc/pom.xml`): three dependencies, the processor
  under `maven-compiler-plugin` `<annotationProcessorPaths>`, `maven-dependency-plugin:copy-dependencies`
  into `target/lib`. First build 10 s including downloads; afterwards `mvn -o` works offline (4-5 s).
  A private repository is selected with `-Dmaven.repo.local=.toolchain/m2`.
* Gradle works too (`refs/cadenza/build.gradle.kts` uses the same coordinates with `kapt`), but adds a
  daemon and a wrapper download for no benefit here.
* The arena build needs neither: `javac --release 25 -cp <8 jars> -processorpath
  truffle-dsl-processor-25.5.5.0.3.jar -s gen -d classes @sources.txt` followed by `jar` is the whole
  build (`spikes/platform/arena/build.sh`, tested in the container: 13.5 s including a 325 MB download).
* A Truffle language jar must contain `META-INF/native-image/<group>/<artifact>/native-image.properties`
  with `Args = --initialize-at-build-time=<language package>`; without it the image build fails with
  "An object of type '...LcLanguageProvider' was found in the image heap".

---

## 4. The Truffle spike and the Bytecode DSL (questions 1 and 2)

### 4.1 Design of the spike language

Front-end (`Parser.java`): s-expressions to a lexically addressed IR (`Expr.java`) with flat
closures. Values are `Long`, `Boolean` and `Closure(RootCallTarget target, Object[] caps)`.
Calling convention: `arguments[0]` = captured array, `arguments[1..]` = actual arguments. Globals are
cells with a `@CompilationFinal` value, so a call through a global is a constant-target direct call.

* `plain`: one Java object per IR node with `Object exec(Object[] args, Object[] caps, Object[] locals)`.
* `ast`: Truffle nodes; arithmetic through DSL `@Specialization`; calls through a `DispatchNode` with a
  3-entry inline cache on the call target (`DirectCallNode`) and an `IndirectCallNode` fallback.
* `bc`: `@GenerateBytecode(enableUncachedInterpreter = true, enableQuickening = true,
  boxingEliminationTypes = {long.class, boolean.class})`; custom operations `Add Sub Mul Less Eq
  LoadCap LoadGlobal MakeClosure Call`; every lambda is its own nested root. `Call` takes
  `@Variadic(startOffset = 1) Object[] argv` so the argument array is reused for the call.

### 4.2 Interpreter and compiled speed (G per iteration, steady state)

fib(32) = 7.05 M guest calls per iteration:

| back-end | JVM CE | native CE | native Oracle PGO |
|---|---|---|---|
| plain Java interpreter | 2.37 | 3.80 | 3.01 |
| Truffle AST, interpreter only | 3.80 | 5.78 | - |
| Truffle BC, interpreter only | 8.50 | 10.99 (8.82 with `enableTailCallHandlers`) | - |
| Truffle AST, compiled | 0.252 | 0.106 | 0.108 |
| Truffle BC, compiled | 0.223 | 0.097 | - |

tree(20) (Church-encoded full binary tree, 1 M leaves; closure-heavy):

| back-end | JVM CE | native CE | native Oracle PGO |
|---|---|---|---|
| plain Java interpreter | 1.36 (Graal JIT), 1.5 (C2) | 1.90 | 1.96 |
| Truffle AST, interpreter only | 3.61 | 3.69 | 3.77 |
| Truffle BC, interpreter only | 5.76 | 6.86 (6.05 with tail-call handlers) | 7.12 |
| Truffle AST, compiled | 0.108 | 0.088 | 0.086 |
| Truffle BC, compiled | 0.073 | 0.086 | - |

Per guest call on fib: plain 336 (JVM) / 539 (native CE) instructions; AST interpreter 539 / 820;
BC interpreter 1206 / 1559; compiled 36 (JVM) / 15 (native).

Fixed overhead of a short Truffle run (short run minus steady state):

| | JVM CE | native CE |
|---|---|---|
| engine start only (`ast fib 20`, one iteration) | 4.6 G (plain back-end, no Truffle: 1.16 G) | 0.022 G (plain: 0.015 G) |
| `ast fib 32` x20 (2 compilations) | 9.0 G | 0.37 G |
| `ast tree 20` x20 (19 compilations) | 9.6 G | 2.4 G |
| `bc tree 20` x20 | 14.3 G | 3.5 G |

Reading: in JVM mode the Truffle runtime's own classes are interpreted and JIT-compiled by HotSpot
first, which costs 4-9 G once. That is about 1-2 % of the budget, acceptable.

### 4.3 Bytecode DSL in 25.x: features and maturity

Features present in `com.oracle.truffle.api.bytecode` 25.5.5.0.3 (read from the sources jar):

| feature | switch | since |
|---|---|---|
| uncached tier, then cached tier, then compiled (`BytecodeTier`) | `enableUncachedInterpreter`, `defaultUncachedThreshold = "16"` | 24.2 |
| quickening | `enableQuickening` (default true), `@ForceQuickening` | 24.2 |
| boxing elimination | `boxingEliminationTypes` | 24.2 |
| serialization (replays builder calls, not a raw dump) | `enableSerialization` | 24.2 |
| continuations (stackless coroutines) | `enableYield`, custom `@Yield` | 24.2 |
| materialized local access for closures | `enableMaterializedLocalAccesses` | 24.2 |
| tag instrumentation | `enableTagInstrumentation` | 24.2 |
| reserved slots in variadic arrays | `@Variadic(startOffset = n)` | 25.0 |
| threaded switch | `enableThreadedSwitch` (default true) | 25.1 |
| peephole instruction rewriting | `enableInstructionRewriting` (default true) | 25.1 |
| one compilation per bytecode handler | `enableTailCallHandlers` (default false) | 25.1 |
| inlined primitive constants, `@Operation(storeBytecodeIndex)` | | 25.1 |
| unwind exceptions | `unwindExceptions` | 25.5 |
| user-defined superinstructions / tracing-derived decisions | absent: `decisionsFile` and `forceTracing` are commented out with "This feature is not yet supported" (TODO GR-57220); the word "superinstruction" does not occur in the package | - |

Defects hit while writing 260 lines of bytecode back-end:

1. 25.5.5.0.3: `enableInstructionTracing = false` generates code that does not compile
   (`cannot find symbol: method publishOldBytecodes()`). Leave the attribute at its default.
2. 25.0.4: a constant operand parameter named `root` clashes with a generated local
   (`variable root is already defined in method validateBytecodes()`). Rename the parameter.
3. 25.0.4: with `boxingEliminationTypes`, recursive `fib` enters a deoptimisation loop and the
   compilation fails permanently (`opt failed ... Deopt taken too many times`); fib then stays in the
   interpreter (245-290 ms instead of 3.6 ms per fib(30)). Without boxing elimination it compiles.
   25.5.5.0.3 is not affected.
4. API drift: `@Operation(storeBytecodeIndex = ...)` does not exist in 25.0.4; source that targets
   both lines must avoid 25.1+ attributes.

Measured assessment for RLVM:

* Interpreter speed: the bytecode interpreter executed 1.6-2.2 x the instructions of the AST
  interpreter on these call-heavy programs (JVM and native). `enableTailCallHandlers` recovers
  12-20 % in a native image. Each call allocates a frame with operand-stack slots; the AST version of
  these programs has no locals.
* Compiled code: equal or slightly better than AST (0.097 vs 0.106; 0.073 vs 0.108 in JVM mode).
* Footprint (`--mem`, program `many:2000`, interpreter only, per defined function of 26 AST nodes
  including its call targets): AST 1.95 KB after parsing, 2.28 KB after first execution; bytecode
  2.23 KB (uncached), 2.65 KB (cached). No footprint advantage at this function size; fixed
  per-call-target overhead dominates.
* Caveat: one small spike, generic operations, no tuning. The result says the Bytecode DSL is not an
  automatic win for call-dominated code, not that it cannot be made fast.

---

## 5. Truffle compilation economics (question 4, guest side)

### 5.1 Cost of one compilation

Program `many:300`: 300 distinct functions of 26 AST nodes, `f_i` calls `f_(i-1)`; each function is
called 1 000 times per iteration (300 000 guest calls per iteration). Native CE,
`--engine.CompilationStatistics=true`, 300 iterations:

| | tier 1 | tier 2 |
|---|---|---|
| compilations | 303 | 60 (plus 13 cancelled or interrupted) |
| time per compilation, average | 3.24 ms (PE 1.82 + Graal 1.23 + install 0.19) | 102 ms (max 436 ms) |
| Graal nodes after partial evaluation, average | 130 | 2 071 |
| AST nodes per unit including inlined callees | 26 | 286 average, 4 356 max |
| machine code, average | 601 B | 1 921 B |

In instructions (differences between runs): about **27 M per tier-1 compilation (1.0 M per AST node)**
and about **0.7 G per tier-2 compilation (2.5 M per inlined AST node)**.

Cost per guest call in this program: plain Java interpreter 972 (native CE) / 733 (JVM); Truffle AST
interpreter 1 420 / 1 118; tier-1 code 43; tier-2 code 9.3.

Break-even for tier 1 against the AST interpreter: 27 M / (1 420 - 43) = about 20 000 calls of a
26-node function; against the plain-Java interpreter about 29 000 calls. Tier 2 after tier 1 pays
only after millions of executions of the compiled unit.

End-to-end confirmation in JVM mode (C2 host), `many:300`, each function called N times:

| calls per function | plain Java | AST, no compilation | AST, default thresholds | AST, 25 000 / 2 000 000 | same + 1 compiler thread | tier 1 only (25 000) |
|---|---|---|---|---|---|---|
| 30 000 | 10.8 | 14.0 | 24.3 | 19.8 | - | - |
| 300 000 | 70.4 | 100.6 | 56.1 | 39.6 | - | - |
| 3 000 000 | 664 | - | 188.7 | 187.7 | 155.5 | 152.4 |

(In the 300 000 and 3 000 000 rows the `plain Java` and `no compilation` cells ran with the Graal
host JIT, everything else with C2; the host JIT changes these by about 10 %.) Compilation with
default thresholds made the 30 000-call case 1.7 x worse than not compiling at all: in JVM mode the
compiler's own warm-up (section 4.2) comes on top of the per-compilation cost. Tier 2 never paid for
itself here, even at 3 M calls per function.

### 5.2 Engine options (names and defaults verified against the running engine)

Full dump: `spikes/platform/results/engine-options-25.5.5.0.3.tsv`. Options are set with
`Context.newBuilder().allowExperimentalOptions(true).option("engine.X", "v")` or
`-Dpolyglot.engine.X=v`.

| option | default | measured effect (native CE unless noted) |
|---|---|---|
| `engine.Compilation` | true | false: `tree` x200 costs 736.9 G instead of 20.0 G |
| `engine.Mode` | default | **`latency`: 110.0 G instead of 20.0 G (JVM: 140.4 vs 31.5)**; `throughput`: no change |
| `engine.MultiTier` | true | false: 19.7 G on `tree`; 47.7 G instead of 30.2 G on `many:300` x300 |
| `engine.FirstTierCompilationThreshold` | 400 | see 5.1; raise to 25 000 or more |
| `engine.LastTierCompilationThreshold` | 10 000 | 2e9 disables tier 2: 12.8 G instead of 30.2 G on `many:300` x300 |
| `engine.SingleTierCompilationThreshold` | 1 000 | used when MultiTier=false |
| `engine.MinInvokeThreshold` / `FirstTierMinInvokeThreshold` | 3 / 1 | not varied |
| `engine.BackgroundCompilation` | true | **false: 376.8 G instead of 30.2 G on `many:300`** (every queued tier-2 unit is compiled; in the background most are dropped as "Target inlined into only caller") |
| `engine.CompilerThreads` | -1 (auto) | 1: 26.7 G instead of 30.2 G; JVM long run 155.5 G instead of 187.7 G |
| `engine.Splitting` | true | false: no change on these programs |
| `engine.OSR`, `engine.OSRCompilationThreshold` | true, 100 352 | OSR=false: no change (no guest loops) |
| `compiler.Inlining` | on (default not shown by the engine) | false: 15.9 G instead of 30.2 G at x300, 46.2 G instead of 61.2 G at x3000 on `many:300` |
| `compiler.FirstTierUseEconomy` | default not shown | false: no change |
| `engine.CompileOnly` | none | comma-separated root-name filter; accepted (`node,leaf`) |
| `engine.DynamicCompilationThresholds` | true | scales thresholds with queue load; not varied; set false for reproducible behaviour |
| `engine.CompilationFailureAction` | Silent | set `Print` in CI to see deopt loops like 4.3 item 3 |
| `engine.TraceCompilation`, `TraceCompilationDetails`, `CompilationStatistics` | false | diagnostics used above |
| `engine.MaximumCompilations` | 100 | per call target |

Host JIT choice for the Truffle interpreter in JVM mode (`ast tree 20` x200): Graal 31.5 G, C2
(`-XX:-UseJVMCICompiler`) 27.9 G, C1 only 26.3 G.

### 5.3 Policy for RLVM

1. Default thresholds are 50-200 x too eager for an instruction budget. Use first tier 25 000 or more
   and disable tier 2 unless a unit is known to run for millions of calls.
2. One compiler thread. Never synchronous compilation, never `Mode=latency`.
3. Prefer deciding in the kernel what to compile (5.4) over global thresholds: mathlib has on the
   order of 10^5 definitions and the budget allows only a few hundred tier-1 compilations
   (300 compilations of 26-node functions = 8 G).
4. Keep compilation units small: tier-2 cost is driven by inlining (4 356 inlined nodes for one
   root here).

### 5.4 Querying and driving compilation programmatically

Public, supported hooks (read in `truffle-api` sources):

* `RootNode.prepareForCompilation(boolean rootCompilation, int compilationTier, boolean lastTier)`
  (since 24.2), called on the compiler thread; returning `false` means "not sufficiently profiled".
  Verified with `-Dlc.veto=fib`: the root is never compiled, **but the runtime re-queues it
  continuously: 5 747 queue/start/unqueue cycles during 8 iterations of fib(30) and about 3 s of extra
  CPU**. Use it as a safety net, not as the policy.
* `LoopNode.reportLoopCount(node, n)` adds `n` to the enclosing root's call-and-loop count, so an
  interpreter can report its own hotness measure. Not exercised in the spike.
* `engine.CompileOnly`, thresholds.

Internal API of the optimising runtime (`org.graalvm.truffle.runtime`, package
`com.oracle.truffle.runtime`, qualified exports only; reachable from the class path, or with
`--add-exports org.graalvm.truffle.runtime/com.oracle.truffle.runtime=ALL-UNNAMED`):

```java
// every RootCallTarget is an OptimizedCallTarget when the optimising runtime is active
boolean compile(boolean lastTierCompilation);   void waitForCompilation();
boolean isValid();  boolean isValidLastTier();  int highestCompiledTier();
int getCallCount(); int getCallAndLoopCount();  int getNonTrivialNodeCount();
boolean isSubmittedForCompilation();  boolean cancelCompilation(CharSequence reason);
boolean invalidate(CharSequence reason);        void resetCompilationProfile();
// OptimizedTruffleRuntime: addListener(OptimizedTruffleRuntimeListener) with onCompilationQueued /
// Started / Success / Failed / Invalidated / Deoptimized; int getCompilationQueueSize()
```

Verified (`scripts/lc.sh --compile-at=fib:3:2` with both thresholds at 2e9): no automatic
compilation happens; the explicit call compiles `fib` straight at tier 2 in 107 ms
(`opt done ... |Tier 2|`), and later iterations run compiled. This is the mechanism for "compile
only what is provably hot and long-running": thresholds effectively infinite, the kernel counts
reductions per definition, and submits a target when its own cost model says so. Risk: internal
API, may change between releases; in a native image the call must be made directly (no reflection)
and the build needs the `--add-exports` above (not tested in a native image).

---

## 6. Host JIT economics (question 4, host side)

### 6.1 Start-up

| configuration (`Hello`) | G |
|---|---|
| defaults (G1, CDS archive, Graal JIT) | 0.47 |
| `-XX:+UseSerialGC` | 0.46 |
| `-XX:+UseSerialGC -XX:-UseJVMCICompiler` | 0.29 |
| `-XX:TieredStopAtLevel=1` | 0.22 |
| `-Xint` | 0.08 |
| `-Xshare:off` (no CDS) | 0.63 |
| `-XX:AOTCache=...` (JEP 483/514) | 0.62 |
| recommended flags with `-Xms6g`, tiny workload | 1.16 |
| native image, tiny workload | 0.015 |

Start-up is below 0.3 % of the budget in every configuration. The AOT cache needs a jar class path
(directories fail with "Cannot have non-empty directory in paths").

### 6.2 Warm-up of a large code base (JavacBench, heap 2 GB, SerialGC)

| configuration | x1 | x5 | x20 | x60 |
|---|---|---|---|---|
| C2, tiered (HotSpot default without Graal) | 33.7 | 80.3 | 136.9 | 227.7 |
| **C2 only (`-XX:-TieredCompilation`)** | - | - | **103.7** | **181.1** |
| C2 tiered, tier-4 thresholds x4 | - | - | 106.3 | 193.6 |
| C2 tiered, `CICompilerCount=2` | - | - | 109.0 | 222.6 |
| C2 only, `-XX:-BackgroundCompilation` | - | - | 102.7 | - |
| C2 only, `CompileThreshold=30000` | - | - | 121.4 | - |
| C1 only (`TieredStopAtLevel=1`) | 10.1 | - | 83.4 | 232.0 |
| Graal JIT, CE (GraalVM default) | 46.6 | - | 232.6 | 438.9 |
| Graal JIT, Oracle | - | - | 182.0 | 314.7 |
| C2 tiered + JDK 25 AOT cache trained on 5 iterations | 33.7 | 75.6 | 136.0 | - |
| all JVM defaults (Graal JIT + G1) | - | - | 225.5 | - |

Steady state is about 1.94 G per iteration with C2 and 3.71 G with C1. Fixed warm-up cost of this
code base: **C2 only about 65 G, C2 tiered about 110 G, C1 only about 9 G, Graal CE more than 300 G
and still compiling after 60 iterations.** In CPU-time terms (C2 tiered, x60) the main thread used
11.6 s of 27.4 s; the rest is compiler threads.

The JDK 25 AOT cache (class loading and linking plus method profiles) brings compiled code earlier
in wall-clock terms but does not reduce total instructions, because every compilation still happens.

### 6.3 Warm-up of a small kernel (NbeBench `tag tree4m-norm`, heap 4 GB, young 2 GB)

| configuration | x10 | x100 | steady per iteration |
|---|---|---|---|
| JVM C2 + Serial | 21.6 | 214.9 | 2.15 |
| JVM Graal JIT + Serial | 23.5 | 232.0 | 2.32 |
| JVM C1 only + Serial | 32.7 | 328.7 | 3.29 |
| native CE (`-O2`) | 28.2 | 309.3 | 3.12 |
| native CE `-O3` | 27.4 | 302.2 | 3.05 |
| native CE `-march=native` | 28.2 | 309.3 | 3.12 |
| native Oracle | 27.4 | 282.8 | 2.84 |
| native Oracle `-O3` | 28.0 | 290.1 | 2.91 |
| native Oracle PGO | 29.0 | 281.2 | 2.80 |
| native Oracle PGO + G1 | 29.5 | 320.7 | 3.24 |

JIT warm-up here is 0.1-0.3 G. Mutator only (Epsilon GC, x10): JVM C2 18.75, JVM Graal 20.65,
native CE 25.40, native Oracle PGO 24.13.

### 6.4 What this means for a 400-600 G run

JIT overhead scales with the amount of warm code, not with run length. A checker whose hot path is a
few hundred methods should land between the two measurements: an estimated 5-30 G (1-7 % of the
budget) with C2; this is an interpolation, not a measurement. Native-image removes that and pays
19-60 % more per steady-state instruction (CE) or between -12 % and +44 % (Oracle PGO). Against CE,
JVM mode wins whenever warm-up is below 19 % of steady-state work (the smallest gap measured), which
holds for mathlib and fails for sub-second tests (which do not affect the ranking).

Rules that follow: keep one implementation of each hot operation (no duplicated evaluators kept warm
side by side), keep rarely-used paths out of the hot classes, and measure the JIT share early with
`-Xlog:jit+compilation=debug` or `CompilationMXBean.getTotalCompilationTime()`.

---

## 7. Native Image (question 3)

### 7.1 Building the spike

```
native-image -cp lc.jar:lib/* rlvm.spike.lc.Main -o lc        # CE or Oracle; Truffle is detected automatically
```

Build output confirms `com.oracle.svm.truffle.TruffleFeature: Provides internal support for Truffle
runtime compilation` and `2,007 runtime compiled methods`; at run time
`--engine.TraceCompilation=true` prints tier-1 and tier-2 `opt done` lines (7 ms and 10 ms for fib).
`--no-fallback` is deprecated in 25 and has no effect.

| image | build wall / CPU / peak RSS (20 cores) | size |
|---|---|---|
| Truffle spike, CE | 37-40 s / 460-490 CPU-s / 3.1 GB (1 min 57 s in the 8-CPU container including javac) | 38.7 MB |
| Truffle spike, Oracle | 48-70 s / 550-575 CPU-s / 4.1-4.3 GB | 50 MB |
| Truffle spike, Oracle `--pgo-instrument` | 61-90 s / 625-660 CPU-s / 5.7-5.9 GB | 114 MB |
| Truffle spike, Oracle `--pgo` | 48-62 s / 515-530 CPU-s / 4.3-4.8 GB | 43 MB |
| Truffle spike, Oracle `--gc=G1` | 52-88 s | 62 MB |
| plain NbeBench, CE / Oracle | 44 s / 200 CPU-s / 1.9 GB ; 57 s / 250 CPU-s / 2.1 GB | 10 MB / 14 MB |

Start-up plus a trivial workload: 0.015 G, RSS 15-20 MB. Peak throughput against JVM mode:
sections 4.2 and 6.3.

### 7.2 What CE offers and what Oracle adds (verified by running the option)

| feature | CE 25.5.5.0.3 | Oracle 25.0.4 |
|---|---|---|
| `--gc=` | `serial`, `epsilon` only ("'G1' is not an accepted value") | + `G1` |
| `--pgo-instrument`, `--pgo=` | "Unrecognized option" | yes; `--pgo` raises the level to 3 |
| `-O3` | accepted ("optimization level: 3"); 2 % fewer instructions on NbeBench | accepted; no gain over `-O2` in our runs |
| `-march=native` | accepted; no change on NbeBench (aarch64) | not run |
| ML-based profile inference | no | reported in the build output as "PGO: ML-inferred" when no profile is given; what it changes (for example inlining) was not examined |
| Truffle enterprise compiler feature | no | active automatically (`EnterpriseTruffleCompilerFeature`) |

Odd but harmless: `java --list-modules` of the CE build lists modules named
`com.oracle.graal.graal_enterprise`, `com.oracle.svm.enterprise.truffle` and similar; the
corresponding features are nevertheless rejected as shown.

### 7.3 Oracle GraalVM availability and licence

* Downloadable without login for both architectures; pinned URLs with published checksums:
  `https://download.oracle.com/graalvm/25/archive/graalvm-jdk-25.0.4_linux-x64_bin.tar.gz`
  (sha256 `76007c309f821aaf435bce63162ea0395587fc77350801c81643fe7feea37276`, 380.5 MB) and
  `..._linux-aarch64_bin.tar.gz` (sha256 `7031aead8da4c6a7816c4e2be4eabfddaf7f4abcfe8e5f16134c4da69a5e70de`,
  351.6 MB). `.../25/latest/...` currently serves the same files.
* Licence (`LICENSE.txt` in the archive): GraalVM Free Terms and Conditions. It grants a licence to
  "internally use the unmodified Programs for the purposes of developing, testing, prototyping and
  demonstrating your applications, and running the Program for Your own personal use or internal
  business operations" and to redistribute the unmodified Program provided no fee is charged; output
  of Native Image "shall be deemed to be an unmodified Program". An arena build step that downloads
  it makes the arena operator the licensee. That is a policy question for the arena maintainers, not
  a technical one (open question 3).
* Oracle GraalVM 25.0.4 needs Truffle 25.0.4 jars, where the Bytecode DSL defects 2-4 of section 4.3
  apply.

### 7.4 PGO and G1 gains (Oracle)

| workload | CE | Oracle | Oracle + PGO | Oracle + G1 |
|---|---|---|---|---|
| plain interpreter, fib(32), G per iteration | 3.80 | 3.77 | 3.01 (-20 %) | 3.80 |
| plain interpreter, tree(20) | 1.90 | 1.88 | 1.96 (+3 %) | 2.07 |
| plain interpreter, `many:300` x300, total | 87.5 | 88.2 | 76.1 (-13 %) | 88.6 |
| Truffle AST interpreter only, `many:300` x300 | 127.8 | 135.7 | 107.8 (-16 %) | 139.9 |
| Truffle-compiled fib / tree, per iteration | 0.106 / 0.088 | 0.108 / 0.087 | 0.108 / 0.086 | 0.108 / 0.099 |
| NbeBench steady (6.3) | 3.12 | 2.84 | 2.80 | 2.89 |
| GcBench mutator only (Epsilon) | 42.5 | - | 31.3 (JVM: 35.7) | - |
| GcBench total, serial, `-Xmn4g` | 112.0 | 73.4 | 64.0 | 254.7-278.9 |

* PGO: 0-20 % on host code, nothing on Truffle-compiled guest code. It beat the HotSpot JIT only on
  the GcBench mutator.
* **Mis-training hazard**: an image trained only on the `tag` evaluator ran the other evaluators
  slower than the untrained CE image (`hoas` 23.0 vs 14.1 G, `pat` 36.6 vs 26.1 G). Training must
  cover every hot path, and it needs a representative export at build time (precedent:
  `checkers/gruszka.yaml` downloads `init.susz` for its PGO build).
* G1 in a native image never reduced instructions (+0 to +14 % on evaluation, 3.8-5 x on GcBench).
  It only helps wall-clock time on several cores.

### 7.5 Serial GC tuning in a native image

Run-time options accepted by the image: `-Xms`, `-Xmx`, `-Xmn`, `-XX:+PrintGC`,
`-XX:ProfilesDumpFile=` (instrumented images). Build-time defaults worth knowing
(`native-image --expert-options-all`): `-H:MaximumHeapSizePercent=80`,
**`-H:MaximumYoungGenerationSizePercent=10`**, `-H:+CompactingOldGen`,
`-H:AlignedHeapChunkSize=524288`, `-H:LargeArrayThreshold=131072` (arrays of 128 KB and more get
their own chunk; whether that avoids copying them was not measured), `-H:-AlwaysPreTouch`, `-H:+UseCompressedReferences` (maximum heap
32 GB: `-Xmx40g` is rejected). GcBench: defaults 233 G, `-Xms8g -Xmx8g` 143 G, plus `-Xmn4g` 112 G.

### 7.6 Traps

* `MemorySegment` and `VarHandle` access is not optimised unless the holder class is initialised at
  build time, and `MemorySegment` stays slow even then (section 8.5).
* Fields that are never read are removed by the closed-world analysis (object-size probes must read
  their fields).
* The G1 image defaults to a maximum heap of 25 % of RAM, the serial image to 80 %.
* The linker needs `gcc` and zlib (`4 native libraries: dl, pthread, rt, z`).

---

## 8. GC and memory on JDK 25 (question 5)

### 8.1 Collectors, in instructions (GcBench 40 200, heap 8 GB, 8 CPUs / 16 GB)

| JVM configuration | G | CPU s | RSS MB | collections |
|---|---|---|---|---|
| Epsilon, `-Xmx14g` (no GC: mutator only) | 35.7 | 32.0 | 8 097 | 0 |
| **Serial, `-Xms8g -Xmx8g`** | **54.4** | 34.8 | 4 517 | 3 |
| Serial + `-Xmn4g` | 57.8 | 34.7 | 5 549 | 2 |
| Serial + `-Xmn1g` | 63.5 | 35.2 | 3 249 | 9 |
| Serial + `-Xmn4g` + compact headers | 58.8 | 34.1 | 5 250 | 1 |
| Serial, JVM default sizing | 83.8 | 43.5 | 2 442 | 106 |
| Parallel (8 threads) | 74.9 | 39.7 | 4 530 | 4 |
| Parallel, 1 / 2 / 4 threads | 75.5 / 75.1 / 75.0 | 34.2 / 33.9 / 35.7 | 4 271-4 531 | 4 |
| Parallel 4 threads + `-Xmn4g` | 67.9 | 35.2 | 5 456 | 2 |
| G1 (default collector) | 204.2 | 53.7 | 5 328 | 10 |
| G1, `ParallelGCThreads=1 ConcGCThreads=1` | 312.1 | 65.1 | 2 848 | 18 |
| G1, 4 parallel / 1 concurrent | 281.6 | 66.8 | 2 851 | 18 |
| ZGC (generational) | 221.2 | 105.0 | 6 565 | 42 cycles |
| all defaults (G1, default sizing) | 262.8 | 63.5 | 3 221 | 55 |

Native images on the same workload: section 7.4.

* Serial costs 18.7 G over the mutator here, almost all of it for promoting the 40 M long-lived nodes
  once (about 470 instructions per promoted object including card scanning of the 512 MB table).
* G1 and ZGC pay for barriers and remembered-set / concurrent work on every reference store into the
  large table; thread-count flags do not fix that.
* Parallel GC spends more instructions than Serial at every thread count; more threads only shorten
  the pause.
* CPU-time view on the bare host (20 cores, `results/gc-matrix-aarch64.txt`): Serial 33.5 s, Parallel
  44.4 s, G1 43.3 s, ZGC 94.5 s, Epsilon 27.2 s: the ranking by CPU time understates G1's cost by 3 x.

### 8.2 Design consequences

1. Objects that live for the whole run should not be small Java objects created in the young
   generation: each is copied once (about 470 instructions) and its table slot dirties a card. Packed
   `int[]` storage is copied by `memmove` at most once per array instead of object by object (cost of
   large-array promotion not measured separately).
2. Size the young generation so that the number of collections is small (2-10 for the whole run).
3. Fixed heap (`-Xms` = most of `-Xmx`): growth steps cost collections.

### 8.3 Other flags

| flag | finding |
|---|---|
| `-XX:+AlwaysPreTouch` | +4.3 s system time, RSS 8.5 GB instead of 5.7 GB; page faults are kernel instructions and are not counted, so pre-touching buys nothing and costs RSS. Do not use. |
| `-XX:+UseTransparentHugePages` | CPU time -4 % (30.6 vs 31.8 s); instruction effect not measured; host THP mode is `madvise`, the runner's is unknown. Harmless. |
| `-XX:-UseAdaptiveSizePolicy` | only relevant to Parallel and not isolated: 4 threads with `-Xmn4g` and adaptive sizing off used 31.4 s CPU against 36.1 s with defaults |
| compressed oops / class pointers | on by default below 32 GB; `-XX:-UseCompressedOops` (or a heap above 32 GB) makes a 2-ref node 32 B instead of 24 B. Stay below 32 GB. |
| `-XX:+UseStringDeduplication` | G1/ZGC/Shenandoah feature; irrelevant with Serial. Intern names once in the parser instead. |
| `-XX:+UseCompactObjectHeaders` (JEP 519, product in 25) | works with Serial, C2, the Graal JIT via libgraal, Truffle runtime compilation in JVM mode, and on the Oracle JDK (all run). Costs +1.6 % (GcBench) to +3 % (plain interpreter) instructions. |

### 8.4 Object sizes (bytes; exact allocation counter on HotSpot, Epsilon heap delta in the image)

| class | HotSpot default | compact headers | no compressed oops | native-image |
|---|---|---|---|---|
| `Object` | 16 | 8 | 16 | 8 |
| `{int}` | 16 | 16 | 16 | 16 |
| `{long}` / boxed `Long` | 24 | 16 | 24 | 16 |
| `{ref}` | 16 | 16 | 24 | 16 |
| `{ref, ref}` | 24 | 16 | 32 | 16 |
| `{byte, ref, ref}` | 24 | 24 | 32 | 24 |
| `{ref, ref, int}` | 24 | 24 | 32 | 24 |
| `{ref, ref, int, int}` | 32 | 24 | 40 | 24 |
| `{ref, ref, ref}` | 24 | 24 | 40 | 24 |
| `{ref, ref, ref, int}` | 32 | 24 | 40 | 24 |
| `{ref x4}` | 32 | 24 | 48 | 24 |
| `{long, ref}` | 24 | 24 | 32 | 24 |
| `int[0]` | 16 | 16 | 16 | 8 |
| `int[3]` / `int[4]` | 32 / 32 | 24 / 32 | 32 / 32 | 24 / 24 |
| `Object[1]` / `[2]` / `[4]` | 24 / 24 / 32 | 16 / 24 / 32 | 24 / 32 / 48 | 16 / 16 / 24 |
| lambda capturing 1 / 2 refs | 16 / 24 | 16 / 16 | 24 / 32 | 16 / 16 |

Headers: HotSpot 12 B (arrays 16 B), compact 8 B (arrays 12 B), native-image 8 B (arrays 8 B; the
identity hash is appended only when used, `-H:+OptionalIdentityHashCodes`). Layout rule: with an 8 B
header a node with two references and no extra field is 16 B; a `byte` tag field costs a whole
8 B. Use the class as the tag, or pack the tag into an existing `int`.

### 8.5 Packed storage access (ns per 12-byte record; sequential scan / random chase)

| store | HotSpot C2 | HotSpot Graal JIT | native CE | native CE, holder class initialised at build time |
|---|---|---|---|---|
| `int[]` | 0.57 / 3.6 | 0.70 / 3.4 | 0.91 / 3.8 | 0.94 / 3.8 |
| heap `IntBuffer` | 1.83 / 4.6 | 0.99 / 3.8 | 4.14 / 4.6 | 4.09 / 4.6 |
| direct `IntBuffer` | 1.89 / 4.2 | 0.72 / 3.7 | 3.89 / 4.6 | 3.90 / 4.6 |
| `MemorySegment.ofArray` | 3.40 / 4.4 | 0.86 / 4.2 | **141 / 148** | 17.2 / 17.2 |
| native `MemorySegment` | 5.01 / 5.2 | 0.82 / 3.6 | **143 / 151** | 17.3 / 17.3 |
| `byte[]` + `VarHandle` | 0.57 / 3.9 | 0.76 / 4.0 | **128 / 130** | 0.99 / 4.1 |
| `Unsafe` on a native address | 0.57 / 4.2 | 0.51 / 3.3 | 2.0 / 3.9 | 1.9 / 3.6 |

(20 M records, 228 MB; times, not instructions.) `int[]` is the fastest or tied everywhere, needs no
flags, and its bounds checks are hoisted by both JITs. Off-heap storage buys nothing for access
speed; its only advantage is that the GC never sees it, which large `int[]` arrays nearly achieve.

---

## 9. Deep recursion (question 6)

### 9.1 Limits and frame sizes (`DeepRec`, a 3-local recursive step that allocates)

| runtime | bytes per frame | default stack | 8 GB thread stack |
|---|---|---|---|
| HotSpot C2 | 32 | `ThreadStackSize=2040` KB on this aarch64 build; overflow at depth 15-17 k while still interpreted | depth 268 M reached |
| HotSpot Graal JIT | 48 | same | 1 GB tested: depth 22 M |
| HotSpot C1 | 112 | | |
| HotSpot interpreter | 160 | | |
| native-image CE | 64 | main thread uses `ulimit -s` (8 MB here): depth 130 k | depth 134 M reached |

* `new Thread(null, runnable, name, stackSizeBytes)` honours multi-gigabyte sizes on both runtimes;
  only touched pages are committed (10 M frames x 32 B = 320 MB of RSS). `-Xss1g` also enlarges the
  Java main thread on HotSpot (depth 33 M). `refs/tenet-jvm/src/main/java/tenet/Main.java:47` uses
  `4L << 30`.
* `StackOverflowError` is catchable and the thread stays usable (four consecutive overflows tested on
  both). Cost: about 100-130 ns per unwound frame on HotSpot (33 M frames: 3.4 s per overflow), about
  the same on SVM; the first descent also pays page faults.
* **Anomaly with the Graal JIT (CE 25.5.5.0.3 and Oracle 25.0.4), reproducible 3 of 3 runs**: after
  one `StackOverflowError` on the main thread, a later thread with a 64 MB stack threw
  `StackOverflowError` at depth of about 1 000 in freshly compiled code. It does not happen with
  `-XX:-UseJVMCICompiler`, with `-XX:-BackgroundCompilation`, or when the first overflow is skipped
  (`-Ddeeprec.skipMain=true`). Not root-caused. Another reason to use C2, and to use an explicit
  depth counter rather than catching `StackOverflowError` as control flow.
* Truffle frames: a guest call in the AST interpreter is several host frames (`callBoundary`,
  `executeRootNode`, `execute` of each node on the path), so interpreted guest recursion is far
  shallower per megabyte than host recursion; compiled guest frames were 64-160 B in the spike
  (`Total frame size` in the compilation statistics).

### 9.2 Deep stacks and GC (NbeBench `nat10m-norm` x5: quote recursion 10 M frames deep)

| configuration | G | collections |
|---|---|---|
| JVM C2, Epsilon | 14.3 | 0 |
| JVM C2, Serial `-Xmn4g` | 16.7 | 1 |
| JVM C2, Serial `-Xmn1g` | 68.7 | 5 |
| JVM C2, Serial `-Xmn256m` | 87.1 | 21 |
| JVM C2, Parallel `-Xmn1g` | 79.3 | 5 |
| JVM C2, G1 | 102.3 | 8 |
| JVM C2, ZGC | 94.7 | 16 |
| JVM `-Xint`, Serial `-Xmn256m`, one iteration | 63.5 | 4 |
| native CE, Epsilon | 18.1 | 0 |
| native CE, `-Xmn4g` | 18.1 | 1 |
| native CE, `-Xmn1g` | 51.5 | 7 |
| native CE, `-Xmn256m` | 234.1 | 43 |

A collection that happens while the stack is 10 M frames deep costs about 10 G on HotSpot (about
1 000 instructions per frame) and about 5 G on SVM. With default heap settings the first iteration
of the HOAS variant took 7.3 s instead of 0.09 s because the deep frames were still interpreted.

Rules: (1) recursion on the host stack is fine and cheapest per step while collections are rare;
(2) for traversals whose depth follows the data (long application spines, long `let` chains, deep
binder telescopes) use loops or explicit worklists held in arrays; (3) never combine a small young
generation with deep recursion.

---

## 10. HOAS / NbE on the JVM: the two normalization-bench repositories, re-measured

### 10.1 What the repositories say (platform-relevant parts)

`refs/normalization-bench-kovacs/README.md` (Scala HOAS on OpenJDK 8 and GraalVM 21, 2020):

* JVM HOAS was the best of the JIT platforms and competitive with GHC on the tree benchmarks, but
  "performance ... degrades sharply from 5M to 10M with natural numbers; perhaps there is some issue
  with deep stacks" (lines 115-119); GraalVM made that case worse.
* It needed `-Xss1000M -Xmx4G` (line 28); "Scala and nodejs performance becomes dismal without free
  RAM" (lines 157-160); occasional multi-second pauses and memory spikes (lines 164-169).
* All JIT entries are call-by-value; call-by-need was not measured.

`refs/normalization-bench-guest0x0/README.md` (OCaml, many normaliser variants):

* Plain untyped NbE with closures and a linked-list environment (`NBE.closure.list`) is among the
  fastest everywhere; list environments beat balanced trees and skew lists (line 370).
* HOAS and first-order closures perform the same in OCaml (line 388); the lazy variant is slower
  (line 395).
* An abstract machine with heap-allocated continuations has a stable constant overhead against
  recursion on the native stack (line 406).
* A memo slot for the quoted term is nearly free when stored inside the value object (variant `v3`,
  one indirection, line 563) and expensive behind a wrapper.
* Full hash-consing has a significant overhead except under extreme sharing (line 585); sharing only
  leaf nodes is a cheap, reliable win (line 592).
* Compiling terms through the host compiler is impractical because of compile time (lines 686, 729).

### 10.2 Re-measurement on JDK 25 (`plain/NbeBench.java`)

Kovacs' table, best of 10 iterations in ms, JVM defaults (G1, Graal JIT), this host:

| workload | hoas | tag | virt | pat | Scala 2020 (README) |
|---|---|---|---|---|---|
| tree 2M normalisation | 33.5 | 66.1 | 58.0 | 60.8 | 85 |
| tree 2M conversion | 119.7 | 100.1 | 114.1 | 101.8 | 118 |
| nat 5M normalisation | 85.3 | 104.5 | 98.6 | 104.6 | 374 |
| nat 5M conversion | 76.8 | 100.6 | 87.4 | 98.7 | 138 |

Mutator instructions (Epsilon GC, tree 4M normalisation x10):

| evaluator | JVM C2 | native CE | native Oracle PGO (trained on `tag`) |
|---|---|---|---|
| `hoas` (Java lambdas) | 12.35 | 14.14 | 22.97 |
| `tag` (closure = env + term, `switch` on a byte tag) | 18.75 | 25.40 | 24.13 |
| `virt` (virtual `eval` method per term class) | 20.73 | 24.61 | 27.07 |
| `pat` (sealed hierarchy, pattern `switch`) | 19.95 | 26.12 | 36.59 |

### 10.3 Platform conclusions

1. The deep-stack degradation Kovacs saw is the stack-walking cost of section 9.2, plus interpreted
   frames early in the run. It disappears with a large young generation (`-Xmn4g`: 16.7 G against
   14.3 G without any GC) and gets worse with G1.
2. "Needs free RAM" is the same effect as section 8.1: collection count, not heap size, is what
   costs. Fixed heap plus large young generation is the cure.
3. On the JVM, host-closure HOAS is 34 % cheaper than the environment machine, unlike in OCaml,
   because the lambda bodies are JIT-compiled Java. It applies only to code that exists as Java at
   build time (primitives, recursor schemes, built-in reductions). For terms that arrive at run time
   the equivalent is a closure tree (`PlainInterp`) or generated classes, whose compile cost is the
   Truffle economics of section 5.
4. Dispatch style matters little on HotSpot (within 10 %); a `switch` on a small tag is the cheapest,
   and the Java 21+ pattern `switch` is fine. In a native image virtual dispatch was marginally
   best.
5. Value layout advice carries over directly: put cache slots in the value object itself, share leaf
   nodes through preallocated arrays, and avoid wrapper objects. Section 8.4 gives the sizes.
6. Guest0x0's verdict on compile-to-host matches our numbers: about 1 M instructions per AST node
   compiled.

---

## 11. Packaging for the arena (question 7)

### 11.1 Facts about the runner

* Runner label `nscloud-ubuntu-22.04-amd64-8x16-with-cache`
  (`refs/lean-kernel-arena/.github/workflows/build-and-deploy.yml:89`); steps run in
  `nix develop -c bash`. Generic glibc binaries such as a downloaded GraalVM should therefore run
  (Ubuntu host loader); inferred, not tested on the runner.
* `flake.nix` lists `jdk`; in the pinned nixpkgs (`flake.lock` rev `0ad6f47e...`,
  `pkgs/top-level/all-packages.nix:4051`) **`jdk = jdk21`**. OpenJDK 21 has no Graal compiler, no
  compact headers and no AOT cache. `checkers/tenet-jvm.yaml` uses it with plain `javac`.
* Build steps may download: `checkers/rpylean.yaml` clones pypy, `checkers/gruszka.yaml` and
  `pestka.yaml` fetch a training file with `curl` and check it with `sha256sum -c`,
  `checkers/kefir-wasm.yaml` installs rustup.
* Measurement: `perf stat -e instructions` around GNU `time` around `sh -c "$RUN"`
  (`refs/lean-kernel-arena/lka.py:496-501`), all child processes and threads included.

### 11.2 Build and run commands

Tested end to end in the arena-shaped container on aarch64 (`spikes/platform/arena/build.sh`,
`run.sh`; 13.5 s for the JVM build, 1 min 57 s with `RLVM_NATIVE=1`). Checker YAML:

```yaml
build: sh arena/build.sh .
run: sh arena/run.sh "$IN"
```

`build.sh`, in outline (the script in the spike is complete and runnable):

```sh
case "$(uname -m)" in
  x86_64)  ARCH=x64;     GRAAL_SHA=c6438dee6c3ade2220d40b7ccdcad24b512d30e233e61bb4fc70519522de34ef ;;
  aarch64) ARCH=aarch64; GRAAL_SHA=41741c0650ae7564a78335f9e4fc353a902e6ff1d4140f2e4fabd1c55d5cce66 ;;
esac
F=graalvm-community-jdk-25i5-25.0.5.0.3_linux-${ARCH}_bin.tar.gz
curl -sSfL --retry 3 -o .build/dl/$F https://github.com/graalvm/graalvm-ce-builds/releases/download/graal-25.5.5.0.3/$F
echo "$GRAAL_SHA  .build/dl/$F" | sha256sum -c -
tar -xzf .build/dl/$F -C .build/graalvm --strip-components=1
# 9 jars from https://repo1.maven.org/maven2/org/graalvm/{polyglot,sdk,truffle}/..., each sha256-checked
javac --release 25 -cp "$CP" -processorpath .build/proc/truffle-dsl-processor-25.5.5.0.3.jar -s .build/gen -d .build/classes @.build/sources.txt
jar --create --file .build/rlvm.jar -C .build/classes .
```

Pinned jar checksums (25.5.5.0.3): polyglot `51096bb4...`, collections `694c52b4...`, nativeimage
`b8d337a9...`, word `11a21c7f...`, jniutils `1c46ac4d...`, truffle-api `ce6662ca...`, truffle-runtime
`a4b8df50...`, truffle-compiler `f12a579e...`, truffle-dsl-processor `39daafb0...` (full values in
`arena/build.sh`). The x64 GraalVM checksum is the digest published by the GitHub release API; the
aarch64 one was verified against the local archive.

Measured in the container with the recommended flags: `ast tree 20` x200 29.6 G (JVM) and 19.8 G
(native); `plain tree 20` x200 321.9 G (JVM, C2 only) and 379.8 G (native CE).

### 11.3 Reproducibility and offline concerns

* Every download is pinned by sha256; the script re-verifies cached files and supports
  `RLVM_OFFLINE=1`. Vendoring the 9 jars (24 MB) in the repository removes the Maven Central
  dependency; the GraalVM archive (351 MB for x64) is too large to vendor.
* GitHub release assets and Maven Central artifacts are immutable; Oracle's `archive/` URLs are
  versioned, `latest/` is not: never use `latest`.
* The build output is about 1 GB (unpacked JDK 635 MB). Delete the archive after unpacking.
* JIT-compiled runs are not bit-reproducible in instruction count (about 1 %).
* If RLVM does not use Truffle at all, the checker could run on the arena's own `jdk` 21 with zero
  downloads (as tenet-jvm does), giving up compact headers and JDK 25 improvements. Not measured.
* Native image only: the link step needs `gcc` and zlib in the `nix develop` environment. zlib is not
  listed in `flake.nix`; whether it is reachable (for example propagated by `libllvm`) was not
  verified. The infra team's Ubuntu-based container has `zlib1g-dev`, so success there proves nothing
  about the runner.

---

## 12. Risks, unverified items, open questions

Unverified or out of reach on this host:

* x86-64 instruction counts and the behaviour of `-march` / default x86-64-v3 targets.
* OpenJDK 21 from nixpkgs with or without Truffle.
* Whether the arena's nix shell can link a native image (zlib, gcc wrapper).
* `truffle-enterprise` on the class path; explicit `compile()` inside a native image;
  `LoopNode.reportLoopCount` as a hotness channel; Bytecode DSL continuations and serialization.
* The cause of the Graal JIT `StackOverflowError` anomaly and of the 25.0.4 deopt loop.
* JIT overhead of the real checker: estimated between the two measured extremes, not measured.

Open questions for the design team:

1. Is Truffle needed at all? On these spikes a plain-Java closure-tree interpreter beats both Truffle
   interpreters before compilation, and compilation pays only above about 10^5 executions per
   function. What fraction of mathlib's reduction work sits in definitions that hot?
2. If Truffle stays: does the kernel drive compilation explicitly (internal API) or through raised
   global thresholds?
3. May an arena build download Oracle GraalVM under GFTC? If not, configuration C is unavailable.
4. How much of the long-lived term store can be `int[]`-packed, so that GC cost approaches the
   Epsilon line?
5. Is `-XX:-TieredCompilation` still a win on the real checker?

---

## Appendix: reproducing

```sh
. spikes/platform/env.sh && spikes/platform/scripts/build-all.sh
spikes/platform/scripts/lc.sh --engine.TraceCompilation=true ast fib 32 20
spikes/platform/scripts/perf.sh --limits "nbe" -- $JAVA_HOME/bin/java -XX:+UseSerialGC -Xms4g -Xmx4g -Xmn2g \
    -XX:-UseJVMCICompiler -cp $OUT/plain NbeBench tag tree4m-norm 10
spikes/platform/scripts/gc-matrix-perf.sh
```

Raw logs: `spikes/platform/results/` (`truffle-instr.txt`, `truffle-many-instr.txt`,
`truffle-options-instr.txt`, `truffle-ee-instr.txt`, `nbe-instr.txt`, `javac-instr.txt`,
`gc-matrix-instr.txt`, `gc-native-instr.txt`, `deepstack-gc-instr.txt`, `deeprec.txt`,
`objsize.txt`, `memaccess.txt`, `startup-instr.txt`, `native-build.txt`, `arena-sim.txt`,
`jit-plain-interp-instr.txt`, `engine-options-25.5.5.0.3.tsv`).
