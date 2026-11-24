# spikes/platform - GraalVM / Truffle platform experiments for RLVM

Evidence behind `docs/research/10-graalvm-platform.md`. Everything here is small, self-written code;
nothing from `refs/` is built or executed. Raw measurement logs are in `results/`.

## Layout

| path | what |
|---|---|
| `env.sh` | pins `JAVA_HOME` (GraalVM CE 25.5.5.0.3), Maven, the local Maven repo (`.toolchain/m2`), `$OUT` (= `work/platform-out`, gitignored) |
| `truffle-lc/` | Maven project: a tiny lambda-calculus language `lc` with three back-ends sharing one front-end: `plain` (plain-Java closure-tree interpreter, no Truffle), `ast` (Truffle AST interpreter with DSL specialisations and inline-cached calls), `bc` (Truffle Bytecode DSL interpreter). Launcher `rlvm.spike.lc.Main`, programs in `Programs.java` (`fib`, `church`, `tree`, `many:K`) |
| `plain/NbeBench.java` | Java port of AndrasKovacs/normalization-bench (HOAS) + Guest0x0's `NBE.closure.list` in three dispatch styles (`hoas`, `tag`, `virt`, `pat`) |
| `plain/GcBench.java` | allocation benchmark: N million long-lived hash-consed nodes + a stream of short-lived trees |
| `plain/JavacBench.java` | javac run in-process on 21k lines: "large application" proxy for JIT warm-up cost |
| `plain/ObjSize.java` | object sizes without JOL (allocated-bytes counter / Epsilon heap delta) |
| `plain/DeepRec.java` | deep recursion: max depth per stack size, bytes per frame, StackOverflowError recovery |
| `plain/MemAccess.java` | `int[]` vs buffers vs `MemorySegment` vs `VarHandle` vs `Unsafe` for packed records |
| `plain/Hello.java` | start-up probe |
| `arena/build.sh`, `arena/run.sh` | arena-style build (pinned GraalVM + Truffle jars by sha256, plain `javac`, optional native image) and run command, exercised inside the arena-shaped container |
| `scripts/` | runners, see below |
| `results/` | raw logs of every table in the document |

## Build

```sh
. spikes/platform/env.sh
spikes/platform/scripts/build-all.sh        # javac for plain/*.java, Maven package for truffle-lc (offline after first run)
```

Maven is `.toolchain/apache-maven-3.9.16` (sha512-verified download from Maven Central); the local
repository is `.toolchain/m2`. To build the spike against the Truffle version of another GraalVM:

```sh
$MVN -q -f spikes/platform/truffle-lc/pom.xml -Dgraalvm.version=25.0.4 -Dlc.out=target/25.0.4 package
```

Oracle GraalVM 25.0.4 (GFTC licence) for the PGO/G1 experiments: `scripts/fetch-oracle-graalvm.sh`
(downloads to `.toolchain/oracle-graalvm-25`, sha256-verified).

## Run (JVM mode)

```sh
scripts/lc.sh ast fib 32 20                                   # backend program n iterations
scripts/lc.sh --engine.TraceCompilation=true ast tree 20 30   # proves runtime compilation ("opt done" lines)
scripts/lc.sh --engine.Compilation=false bc fib 32 20         # interpreter only
scripts/lc.sh -J-XX:-UseJVMCICompiler ast fib 32 20           # -J<flag> passes a JVM flag
scripts/lc.sh --compile-at=fib:3:2 --engine.FirstTierCompilationThreshold=2000000000 \
              --engine.LastTierCompilationThreshold=2000000000 --engine.TraceCompilation=true ast fib 30 8
                                                              # explicit compilation through the internal runtime API
scripts/lc.sh -J-Dlc.veto=fib --engine.TraceCompilationDetails=true ast fib 30 8   # prepareForCompilation veto
scripts/lc.sh --mem --engine.Compilation=false bc many:2000 20 1                  # node/bytecode footprint
scripts/jrun.sh "-XX:+UseSerialGC -Xmx4g" NbeBench tag tree4m-norm 10
scripts/jrun.sh "-Xms8g -Xmx8g -XX:+UseSerialGC" GcBench 40 200
scripts/gc-matrix.sh                                          # all collectors, CPU time (host)
```

## Native images

```sh
scripts/build-native.sh ce lc-ce "$SPIKE/truffle-lc/target/lc.jar:$(ls $SPIKE/truffle-lc/target/lib/*.jar | tr '\n' ':')" rlvm.spike.lc.Main
$OUT/ni/lc-ce --engine.TraceCompilation=true ast fib 32 20
scripts/build-native.sh ce nbe-ce $OUT/plain NbeBench
scripts/build-native.sh ee nbe-ee-instr $OUT/plain NbeBench --pgo-instrument      # Oracle GraalVM only
$OUT/ni/nbe-ee-instr -XX:ProfilesDumpFile=$OUT/nbe.iprof tag tree2m-norm 5
scripts/build-native.sh ee nbe-ee-pgo $OUT/plain NbeBench --pgo=$OUT/nbe.iprof
scripts/build-native.sh ee gc-ee-g1 $OUT/plain GcBench --gc=G1
```

`build-native.sh` appends build CPU/wall/RSS and binary size to `results/native-build.txt`.

## Instruction counts (the arena metric)

Host perf counters are closed (`perf_event_paranoid=4`). `scripts/perf.sh` therefore runs the command
through the infra team's container wrapper (`bench/arena-docker.sh` + `bench/measure.sh`:
`perf stat -e instructions:u`, all threads; `--limits` = 8 CPUs / 16 GB like the arena runner):

```sh
scripts/perf.sh --limits "label" -- $JAVA_HOME/bin/java -XX:+UseSerialGC -cp $OUT/plain NbeBench tag tree4m-norm 10
scripts/gc-matrix-perf.sh                                     # all collectors, instructions
```

Arena-style end-to-end test (downloads GraalVM CE + jars into `$PWD/.build`, about 1 GB):

```sh
mkdir -p $OUT/arena-sim && cd $OUT/arena-sim
$RLVM_ROOT/bench/arena-docker.sh --arena-limits -w $PWD -- sh $SPIKE/arena/build.sh $SPIKE/truffle-lc
$RLVM_ROOT/bench/arena-docker.sh --arena-limits -w $PWD $RLVM_ROOT/bench/measure.sh -c "sh $SPIKE/arena/run.sh ast tree 20 200"
```

## Notes

* Steady-state numbers are computed as (instructions of a long run - instructions of a short run) /
  (iteration difference); warm-up = short run - iterations x steady.
* This host is aarch64 (Cortex-X925/A725). Instruction counts on the x86-64 arena runner will differ
  in absolute terms; ratios between configurations are what the document relies on.
* All native images, PGO profiles, downloaded archives and the arena simulation directory were
  deleted after measuring; the commands above recreate them (1-2 minutes each).
* Known generator problems hit while writing the Bytecode DSL back-end are documented in the
  research document (section 4.3) and worked around in `LcBytecodeRootNode.java`.
