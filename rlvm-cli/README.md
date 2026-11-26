# rlvm-cli — launcher, verdict protocol, packaging

Java package `rlvm.cli`. Owns the `rlvm` command and nothing of the kernel. Companion scripts:
`scripts/build.sh` (distribution), `scripts/dev.sh` (one entry point), `scripts/arena/` (arena
definition), `bench/rlvm/` (run / suite / measure). Each script documents itself in its header and
with `--help`.

```
scripts/dev.sh test rlvm-cli          # 67 unit tests, 8 s
scripts/build.sh                      # THE RELEASE: one executable, work/dist/bin/rlvm (GraalVM CE native image)
scripts/dev.sh dist-native            # a development distribution: bin/rlvm plus lib/*.jar and bin/rlvm-jvm
scripts/package.sh                    # rlvm-<version>-linux-<arch>.tar.gz: rlvm, LICENSE, README.md
scripts/dev.sh selftest               # exit-status protocol + Truffle probe of every built launcher
scripts/dev.sh run work/arena-site/tests/good/init-prelude.ndjson
scripts/dev.sh suite [--docker]       # the 230 published arena tests
scripts/dev.sh measure work/arena-site/generated/init.ndjson      # instructions for 1/4/8 threads
```

## Command line

```
rlvm [options] [EXPORT.ndjson]            export also via the environment variable IN (arena)
  -j N, --threads N     worker threads; default auto (RLVM_THREADS)
      --stats           statistics on stderr when done
      --stack-size S    stack of the checker thread, default 512m (RLVM_STACK_SIZE)
      --checker NAME    pick a Checker implementation (see --version)
      --platform P      image | jvm | auto: who checks, the executable or a JVM it hands the run to
                        (RLVM_PLATFORM; section "The hybrid entry")
  -X KEY[=VALUE]        passed through to the checker (Options.extra()); --help ends with the
                        settings of the checker that would run (Checker.usage()), for the RLVM
                        kernel e.g. -X policy=practical|strict, -X engine=NAME, -X load=...
  -q, --quiet | -V, --version | -h, --help
      --truffle-probe   verify Truffle runtime compilation in this build
      --protocol-selftest MODE    fake checker for testing the exit status
```

`--version`, `--help`, `--truffle-probe` and `--protocol-selftest` answer without checking anything,
so they are refused (status 64) when an export is given on the command line: a mistyped arena run
line cannot turn every test into an acceptance.

## Exit status (fail closed)

| status | meaning | who produces it |
|---|---|---|
| 0 | accept | only `Verdict.ACCEPT` returned cleanly by the checker |
| 1 | reject | `Verdict.REJECT` |
| 2 | decline | `Verdict.DECLINE` (the placeholder checker always) |
| 3 | internal error | any `Throwable` (incl. `OutOfMemoryError`, `StackOverflowError`), a `null` verdict, a thread that died of an uncaught throwable, an interrupted launcher; also the runtime itself via `ExitOnOutOfMemoryError` |
| 64 | usage error | bad option, no export, export missing or unreadable; a setting the checker refuses: `check` threw the public `rlvm.cli.UsageException` (an unknown `-X KEY`, a malformed value, an unknown engine) and no thread died meanwhile |

How it is enforced (`VerdictProtocol`, `Main`):

- The status variable starts as 3 in `Main.main` and in `VerdictProtocol.decide`; the single
  assignment of a verdict's status happens only if `check` returned normally, the verdict is
  non-null, no thread died and the wait was not interrupted. `decide` is a pure function and is
  tested over its whole input space (64 combinations). The only other status that can leave it is
  64, for a `UsageException` thrown by `check` (wave 4; `Checker`s throw it before any work is done;
  `--protocol-selftest usage` exercises it in a built launcher, and `scripts/build.sh` verifies it).
- Nothing may leave `main` as an exception: a JVM and a native image both exit with **1** (= reject)
  when the main thread dies. `main` ends the process itself, with `Runtime.halt` (no shutdown hooks:
  cheaper, and nothing to hang in); `RLVM_EXIT=exit` uses `System.exit` instead (a PGO-instrumented
  image writes its profile from a shutdown hook).
- The checker runs on its own thread, `new Thread(null, body, "rlvm-checker", stackBytes)`.
- A default uncaught-exception handler is installed for the duration of the check: a worker that dies
  spoils the result whatever the driver returns (acceptance *and* rejection).
- Both launchers run with `ExitOnOutOfMemoryError`: heap exhaustion ends the process at once with 3.
- **Runtime start-up failures exit with 1** before `main` is reached (measured: unknown `-XX` option,
  `-Xms` > `-Xmx`, `-Xmx` above the 32 GB compressed-reference limit, JVM that cannot reserve its
  heap, missing class path; a native image that cannot create its isolate exits with 33). To keep
  those from reading as a rejection, `RLVM_STATUS_BASE=N` adds N to every status rlvm produces itself,
  and the arena run line maps exactly 10/11/12 back to 0/1/2 and everything else to 3:

  ```
  RLVM_STATUS_BASE=10 work/dist/bin/rlvm --threads 4 "$IN"; case $? in 10) exit 0;; 11) exit 1;; 12) exit 2;; *) exit 3;; esac
  ```

## The hybrid entry (`--platform`, class `Handover`)

The executable can hand a run over to a JVM (*agentic-note*: rep-OPT-K has the measurements, the design
and its security argument). In short:

- **The jars are inside the executable.** `scripts/build.sh` puts the RLVM jars of the build (not
  `rlvm-truffle`, no third-party jar: 1.1 MB) with an index on the class path of the image build;
  `Handover.Jars.EMBEDDED` reads them at image build time. On a JVM it is null.
- **The rule** (`Handover.decide`): `--platform image` (the default, `Handover.Platform.DEFAULT`) never,
  `--platform jvm` always, `auto` for `-X passes=N` with N above 1 on the default engine. A run that names
  the Truffle tier, and under `auto` one with a named checker, another engine or a VM option of the
  executable, stays.
- **The JDK** (`Handover.findJdk`, `inspect`): `RLVM_JAVA`, else `JAVA_HOME`, else `java` on `PATH`; judged
  by the `release` file beside it (`JAVA_VERSION` 25 or more, the modules `java.base` and
  `java.management`, HotSpot's server VM), without starting it.
- **The cache** (`Handover.secureDir`, `materialise`): `$RLVM_CACHE_DIR`, else `$XDG_CACHE_HOME/rlvm`, else
  `$HOME/.cache/rlvm`, plus the build id; mode 0700; it and every directory above it must be closed to
  other users' writes; every jar is compared with the executable's copy, byte for byte, on every start.
- **The step** (`Handover.attempt`, `NativeExec`): one probe per JDK and flag line
  (`java ... rlvm.cli.Main --version` as a child, remembered by a marker file), then `execv`. The JVM is
  the same process, and the exit status, `RLVM_STATUS_BASE` included, is the JVM side's own.
- **Nothing of this can fail a check**: whatever goes wrong before `execv` leaves the process as it was
  and the export is checked in-process. `--stats` ends with `platform=image|jvm why=...`, and
  `--version` has a line `platform:` with the JDK found, or the reason there is none.
- The VM flags are `JVM_FLAGS` of `scripts/build.sh` without the two options that silence warnings about
  Truffle and with `-XX:+IgnoreUnrecognizedVMOptions`; the heap is the JVM launcher's by engine;
  `RLVM_JAVA_OPTS` replaces both.

`scripts/build.sh` exercises the hand-over on every executable it builds (`verify_handover`), and
`HandoverTest` covers the rule, the search, the cache and the command line on a JVM.

## Plugging in a checker

```java
public interface Checker {                       // rlvm.cli.Checker
    String name();                               // "--checker NAME", shown by --version
    default String description() { ... }
    default String usage() { return ""; }        // lines for --help: the checker's own -X settings
    default String defaults() { return ""; }     // one line for --version: what a run without options does
    default int priority() { return 0; }         // highest wins; the placeholder is Integer.MIN_VALUE
    Verdict check(Path export, Options options) throws Exception;   // ACCEPT | REJECT | DECLINE
}
```

1. Implement it in any module, with a public no-argument constructor.
2. Add `src/main/resources/META-INF/services/rlvm.cli.Checker` containing the class name.
3. Add the module to the parent pom. `scripts/build.sh` puts every module jar on the class path of
   the JVM launcher and of the native image; `ServiceLoader` finds the provider in both (see
   `MainTest.serviceLoadedCheckerIsTheDefault`, which does exactly this on the test class path).

No change in `rlvm-cli` is needed. Contract for `check`: return `ACCEPT` only after the whole export
has been processed; never catch `Error` to carry on; never call `System.exit`; create worker threads
with `options.stackBytes()`; `options.effectiveThreads()` is the requested count or, for auto,
`min(8, processors)`; settings of your own arrive through `-X key=value` in `options.extra()`.

`Options` has: `threads()`, `effectiveThreads()`, `stats()`, `quiet()`, `stackBytes()`, `extra()`,
`out()`, `err()`.

### Native image: what a module has to do

Image-wide options are set in `scripts/build.sh`. Anything specific to a package belongs to the
module's own `src/main/resources/META-INF/native-image/rlvm/<artifact>/native-image.properties`,
which native-image picks up from the jar. Typical content for a module with Truffle nodes or a
`TruffleLanguage`:

```
Args = --initialize-at-build-time=rlvm.<package>
```

Truffle language, node and generated classes must be initialised at build time; such classes must not
keep static state that depends on the build machine (environment, processor count, open files).
Reflection is not available unless registered; `ServiceLoader.load(X.class)` with a constant class
works without registration.

## Truffle in the image

`rlvm-cli` depends on `truffle-api`, `truffle-runtime` and `polyglot` for one reason: the image must
contain Truffle *runtime compilation* and prove it. `rlvm --truffle-probe` (package `rlvm.cli.probe`)
calls a trivial root node until the code that answers is last-tier compiled code
(`CompilerDirectives.inCompiledCode() && !hasNextTier()`), on two routes: a root node without a
language, and a root node of a registered language inside a polyglot `Context` built with engine
options (`-X engine.CompilerThreads=1` etc. are passed to it). It exits non-zero if Truffle runs on
`DefaultTruffleRuntime` (the silent interpreter-only fall-back after a version mismatch) or if no
compiled code appears within 20 s. `scripts/build.sh` runs it against every artifact and fails the
build otherwise. Nothing of Truffle is touched on a normal run.

The Truffle version must equal the `GRAALVM_VERSION` of the GraalVM that builds the image:
25.5.5.0.3 for the CE toolchain (parent pom property `graalvm.version`), 25.0.4 for Oracle GraalVM;
`scripts/build.sh --oracle-pgo` compiles the modules with `-Dgraalvm.version=25.0.4` for that
optional build.

## The executable and the distributions (`scripts/build.sh`)

The release is ONE executable (*agentic-note*: design-packaging). `scripts/build.sh` builds it as
`work/dist/bin/rlvm` (native image, GraalVM CE) with `BUILD-INFO.txt` beside it and nothing else;
`--dev` adds `lib/*.jar` and `bin/rlvm-jvm` (a sh launcher that runs the jars on the build's JDK: a
development tool for the warm benchmarks, the gates and the test kit, never packaged); `--jvm-only`
is the development distribution without the executable. `--oracle-pgo` builds the same executable
with Oracle GraalVM (GFTC licence) and the profiles shipped in `profiles/`: an option, never a
default. The script builds from a snapshot of the sources in `work/build/`, so it never writes into
a module's `target/` and can run while others work; every artifact is verified (protocol self-test,
real heap exhaustion under `-Xmx128m`, Truffle probe) before the distribution directory is replaced
as a whole. Build times and sizes: `work/build/build-times.tsv`.

`rlvm --version` prints, after the version: `build:` - how the executable was built (compiler,
optimisation level, collector, the heap settings baked in; the text is the system property
`rlvm.build` of the image BUILD, kept as a constant by `BuildInfo`, which is initialised at image
build time; on a JVM it says so) - then the runtime, the checkers and `default:` - what a run
without options does, in the words of the checker that would run (`Checker.defaults()`).

The JVM launcher's flags and their reasons are in the comment above `JVM_FLAGS` in
`scripts/build.sh`. `rlvm-jvm` accepts `-Xmx/-Xms/-Xmn/-Xss`, `-XX:...`, `-D...` and `-J<flag>`
like the native image does; `RLVM_JAVA_OPTS` replaces the defaults.

PGO training is step c of the header of `scripts/build.sh`: the instrumented image runs
`--truffle-probe` and every `--train FILE` export with `RLVM_EXIT=exit`; one `.iprof` per run.

## Measured (2026-11-23, aarch64 host, placeholder checker)

The host had a load average of about 30 from other jobs, so build times are upper bounds. Raw data:
`work/build/build-times.tsv`, `work/dist/BUILD-INFO.txt`, `work/rlvm/suite/*.json`,
`work/rlvm/measure/*.jsonl`, `work/logs/rlvm/arena-e2e*.log`.

| artifact | build wall | build CPU | builder peak RSS | size |
|---|---|---|---|---|
| jars (Maven, 4 modules, from a source snapshot) | 3-15 s | | | 19.5 MB incl. Truffle |
| `bin/rlvm` native, CE, `-O2` (host, 20 cores) | 56-66 s | 498-573 s | 3.0 GB | 38.8 MB |
| `bin/rlvm` native, CE, `-O3` (arena build, 8-CPU container) | 115 s | 522 s | 3.1 GB | 60.9 MB |
| instrumented image, Oracle GraalVM | 94-138 s | 663-720 s | 5.5-5.8 GB | 116.5 MB |
| `bin/rlvm-pgo` native, Oracle GraalVM + PGO (the builder switches to `-O3` when given a profile) | 46-62 s | 466-532 s | 4.0-4.5 GB | 41.0 MB |
| whole `scripts/build.sh` (all of the above) | 218-251 s | | | |
| arena `build:` from an empty checkout in the container (335 MB of downloads, Maven online, `-O3` image, verification) | 153 s | | | |

Start-up floor of one run, measured like the arena does (`perf stat` user instructions, 8 CPUs /
16 GB container, the placeholder checker declining `init-prelude`):

| launcher | instructions | wall | max RSS |
|---|---|---|---|
| `bin/rlvm` (binary alone) | 2.38-2.44 M | 4-18 ms | 12.1 MB |
| `bin/rlvm` through the arena run line (status mapping) | 2.28-2.47 M | 5-15 ms | 12.1 MB |
| `bin/rlvm-pgo` | 2.59-2.74 M | 4-13 ms | 19.0 MB |
| `bin/rlvm-jvm`, launcher's default flags | 413-461 M | 80-130 ms | 59 MB |
| `bin/rlvm-jvm` with only `-XX:+UseSerialGC` (Graal as host JIT, default heap) | 777-830 M | 100-160 ms | 108 MB |
| `bin/rlvm-jvm` with plain JVM defaults (G1) | 770-860 M | 100-150 ms | 109-113 MB |
| `bin/rlvm-jvm`, default flags + `-XX:TieredStopAtLevel=1` | 301-326 M | 80-100 ms | 56 MB |

The status-mapping run line costs nothing measurable. The 230 published tests (all declined):
5.50e8 instructions in total for the native image, 9.83e10 for the JVM launcher.
Truffle probe inside the native image (polyglot route, one compiler thread): CE image, first
compiled code after 1-6 ms and last-tier code after 7-14 ms in five builds; PGO image 3-4 and
10 ms in two builds, 23 and 46 ms in a third (loaded host).

Arena definition, end to end in the container (`scripts/dev.sh arena-test`): passed twice, once
with the system zlib and once with `--force-zlib` (zlib 1.3.1 built from its pinned source; the
binary then has no `libz.so` dependency). Not covered here: the arena's nix shell and x86-64.
