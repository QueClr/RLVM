#!/usr/bin/env bash
# scripts/build.sh - build RLVM and assemble a runnable distribution.
#
#   scripts/build.sh [options]            (scripts/build.sh --help)
#
# Result (default work/dist/):
#   bin/rlvm-jvm     JVM-mode launcher (sh script; runs lib/*.jar on the build's GraalVM)
#   bin/rlvm         native image, GraalVM CE, with Truffle runtime compilation
#   bin/rlvm-pgo     native image, Oracle GraalVM + profile-guided optimisation: ONLY with --pgo, for
#                    measurements that are labelled as such (owner's decision of 2026-11-24, OWNER-NOTES
#                    item 11: no default build or script downloads or uses Oracle GraalVM; nothing
#                    that ships depends on it). See "PGO" below
#   lib/*.jar        every module of the reactor plus its runtime dependencies (Truffle, polyglot)
#   BUILD-INFO.txt   versions, flags, build times, sizes
# A history of build times and sizes is appended to work/build/build-times.tsv.
#
# How it builds
#   1. The sources (parent pom + modules, without target/) are copied to work/build/<variant>/src and
#      Maven runs there. The working tree's own target/ directories are never touched, so this
#      script can run while other people run `mvn -pl <module> test` in the tree, and the Oracle variant
#      can be compiled against its own Truffle version next to the CE one.
#      With --published only the modules named by --modules (default then: rlvm-main) are compiled
#      from the snapshot; every other RLVM module is taken from the local repository .toolchain/m2,
#      i.e. the jar its owner last published with `mvn -pl MODULE install`. That is the mode for a
#      tree in which several people work at once: a module that does not compile at the moment
#      cannot break the distribution, and what is assembled is what was published.
#   2. Maven packages the modules and copies their runtime dependencies; all jars go to lib/.
#   3. native-image builds bin/rlvm from exactly those jars. Image-wide options are set here;
#      package-specific ones (build-time initialisation) come from each jar's own
#      META-INF/native-image/**/native-image.properties.
#   4. Every artifact is verified before it is installed: --version, the verdict protocol
#      (--protocol-selftest), a decline on a real export, and `--truffle-probe`, which fails unless a
#      Truffle call target reaches last-tier compiled code inside the artifact. A build whose image
#      cannot compile at run time fails here, not in the arena.
#   5. The new distribution replaces the old one as a whole; a distribution never mixes builds.
#      The SHARED distribution (the default, work/dist) is versioned: every build is installed as its
#      own directory work/dist.d/<UTC time>-<sha256 prefix of the RLVM jars>-<jvm|native>, and
#      work/dist is a symbolic link that is switched to it with one rename(2). Whoever resolved the
#      link before the switch (scripts/gate.sh, scripts/slice.sh, bench/track and the platform matrix
#      do so once, at their start) keeps a complete, unchanging build under its feet; an old version
#      is deleted only when it is neither one of the newest RLVM_DIST_KEEP (default 4) nor younger
#      than RLVM_DIST_MIN_AGE_MIN minutes (default 90: longer than any guard runs). A copy of the
#      shared distribution is `cp -aL work/dist DIR` (-L: the files, not the link). A private
#      distribution (--dist DIR) is replaced in place as before (--versioned gives it the same scheme).
#
# PGO (Oracle GraalVM only; `--pgo-instrument` does not exist in CE). Never part of a default build:
# it needs --pgo and an Oracle GraalVM that somebody put into .toolchain/oracle-graalvm-25 by hand.
#   a. the modules are compiled against the Truffle version of the Oracle GraalVM (its GRAALVM_VERSION);
#   b. an instrumented image is built (native-image --pgo-instrument);
#   c. TRAINING: the instrumented image runs `--truffle-probe` (so that the runtime compiler itself is
#      profiled) and then checks every training export (--train FILE, repeatable; default: the arena's
#      init-prelude and, if present, work/arena-site/generated/init.ndjson) with RLVM_EXIT=exit, because
#      the profile is written by a shutdown hook that the launcher's default Runtime.halt would skip.
#      Each run writes one .iprof file to work/build/oracle/profiles/;
#   d. the final image is built with --pgo=<those files>.
#   The profile is only as good as the training runs: with the placeholder checker it covers start-up
#   and the Truffle compiler; once a kernel is plugged in, train on init.ndjson (348 MB) or larger.
#   Oracle GraalVM is licensed under the GFTC; the arena definition (scripts/arena/) uses CE only.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"

usage() {
  cat <<'EOF'
Usage: scripts/build.sh [options]

What to build (default: JVM launcher + CE native image; GraalVM CE only):
  --jvm-only            jars and bin/rlvm-jvm only (about 15 s)
  --pgo                 also build bin/rlvm-pgo with Oracle GraalVM + PGO, for MEASUREMENTS ONLY
                        (GFTC licence; never the default, never what ships; fails if
                        .toolchain/oracle-graalvm-25 is missing - it is not downloaded by any script)
  --no-pgo              no bin/rlvm-pgo (the default; kept for older command lines)
  --modules A,B         build only these modules (and what they depend on); default: all modules
                        of the parent pom
  --published           compile only the --modules (default: rlvm-main) and take every other RLVM
                        module as published in .toolchain/m2 (see "How it builds")
  --tests               run the unit tests during the Maven build (default: not even compiled)

Native image:
  --opt LEVEL           optimisation level: b (fast build), 1, 2, 3 (default; what the arena
                        build uses: 10-18 % less CPU time than 2 for the machine, 2-3 minutes of
                        build instead of 1-2)
  --march VALUE         e.g. native, compatibility (default: native-image's own default)
  --gc NAME             serial (default), epsilon, G1 (Oracle GraalVM only)
  --ni-arg ARG          extra native-image argument (repeatable), e.g. --ni-arg -R:MaxHeapSize=12g
  --train FILE          PGO training export (repeatable)
  --train-arg ARG       extra argument of every PGO training run (repeatable), e.g.
                        --train-arg -X --train-arg engine=machine to profile another engine than
                        the default one

Other:
  --dist DIR            where to put the distribution (default: work/dist, the shared one, which
                        is versioned: work/dist is a symbolic link into work/dist.d/, see the
                        header of this script)
  --versioned           install DIR as DIR.d/<version> + symbolic link, like the shared distribution
  --in-place            replace the directory itself (the default for --dist DIR)
  --online              let Maven download missing artifacts (default: offline)
  --verify-deps FILE    check lib/ against a sha256 list (scripts/arena/deps.sha256)
  --print-deps          print such a list for the jars of this build and exit after the Maven step
  -h, --help

Environment: JAVA_HOME / MVN are taken from ./env.sh; RLVM_ORACLE_GRAALVM overrides the location of
the Oracle GraalVM (default .toolchain/oracle-graalvm-25); RLVM_DIST_KEEP (default 4) and
RLVM_DIST_MIN_AGE_MIN (default 90) decide which old versions of a versioned distribution are deleted.
EOF
}

die() { echo "build.sh: $*" >&2; exit 1; }
log() { printf 'build.sh: %s\n' "$*" >&2; }
now() { date +%s.%N; }
since() { awk -v a="$1" -v b="$(now)" 'BEGIN { printf "%.1f", b - a }'; }

DIST="$ROOT/work/dist"
want_native=1
want_pgo=no   # Oracle GraalVM is used only on request (--pgo): OWNER-NOTES item 11
modules=""
published=0
run_tests=0
opt=3
march=""
versioned=auto
train_args=()
gc=serial
ni_extra=()
train=()
offline=1
verify_deps=""
print_deps=0

while [ "$#" -gt 0 ]; do
  case "$1" in
    --jvm-only) want_native=0; want_pgo=no; shift ;;
    --no-pgo) want_pgo=no; shift ;;
    --pgo) want_pgo=yes; shift ;;
    --modules) modules="${2:?--modules needs a list}"; shift 2 ;;
    --published) published=1; shift ;;
    --tests) run_tests=1; shift ;;
    --opt) opt="${2:?--opt needs a level}"; shift 2 ;;
    --march) march="${2:?--march needs a value}"; shift 2 ;;
    --gc) gc="${2:?--gc needs a name}"; shift 2 ;;
    --ni-arg) ni_extra+=("${2:?--ni-arg needs an argument}"); shift 2 ;;
    --train) train+=("$(realpath -e "${2:?--train needs a file}")"); shift 2 ;;
    --train-arg) train_args+=("${2:?--train-arg needs an argument}"); shift 2 ;;
    --dist) dist_arg="${2:?--dist needs a directory}"; shift 2 ;;
    --versioned) versioned=1; shift ;;
    --in-place) versioned=0; shift ;;
    --online) offline=0; shift ;;
    --verify-deps) verify_deps="$(realpath -e "${2:?--verify-deps needs a file}")"; shift 2 ;;
    --print-deps) print_deps=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown option: $1 (see --help)" ;;
  esac
done
case "$opt" in b|1|2|3) ;; *) die "--opt must be b, 1, 2 or 3" ;; esac
# The distribution's path is NOT resolved through a final symbolic link: for a versioned
# distribution the link itself is what gets switched (the directories above it are resolved).
if [ -n "${dist_arg:-}" ]; then
  DIST="$(realpath -m "$(dirname "$dist_arg")")/$(basename "$dist_arg")"
  [ "$versioned" != auto ] || versioned=0
  # A --dist that names the shared distribution (or any existing link into DIR.d/) stays versioned.
  if [ "$versioned" = 0 ] && [ -L "$DIST" ]; then
    case "$(readlink "$DIST")" in "$(basename "$DIST").d/"*) versioned=1 ;; esac
  fi
else
  [ "$versioned" != auto ] || versioned=1
fi

# shellcheck source=../env.sh
. "$ROOT/env.sh"
[ -x "$JAVA_HOME/bin/java" ] || die "no JDK at $JAVA_HOME (run scripts/fetch-toolchain.sh)"
CE_HOME="$JAVA_HOME"
ORACLE_HOME_DIR="${RLVM_ORACLE_GRAALVM:-$ROOT/.toolchain/oracle-graalvm-25}"

graalvm_version() { sed -n 's/^GRAALVM_VERSION="\{0,1\}\([^"]*\)"\{0,1\}$/\1/p' "$1/release"; }

if [ "$want_pgo" = yes ]; then
  [ -x "$ORACLE_HOME_DIR/bin/native-image" ] || die "--pgo: no Oracle GraalVM at $ORACLE_HOME_DIR"
fi
if [ "$want_native" = 1 ]; then
  [ -x "$CE_HOME/bin/native-image" ] || die "no native-image in $CE_HOME"
fi

GNU_TIME="$(type -P time || true)"

BUILD="$ROOT/work/build"
mkdir -p "$BUILD"
# One build at a time: two would fight over work/build and the distribution.
if command -v flock >/dev/null 2>&1; then
  exec 9>"$BUILD/.lock"
  if ! flock -n 9; then
    log "another build is running; waiting for it"
    flock 9
  fi
fi

all_modules="$(sed -n 's:.*<module>\(.*\)</module>.*:\1:p' "$ROOT/pom.xml" | paste -sd, -)"
[ -n "$all_modules" ] || die "no <module> entries in $ROOT/pom.xml"
if [ -z "$modules" ]; then
  if [ "$published" = 1 ]; then modules="rlvm-main"; else modules="$all_modules"; fi
fi

STAGE="$BUILD/stage"
rm -rf "$STAGE"
mkdir -p "$STAGE/dist/bin" "$STAGE/dist/lib"
INFO="$STAGE/dist/BUILD-INFO.txt"
HISTORY="$BUILD/build-times.tsv"
[ -f "$HISTORY" ] || printf 'utc\tartifact\tflags\tbuild_wall_s\tbuild_cpu_s\tbuild_peak_rss_mb\tsize_bytes\n' > "$HISTORY"
stamp="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
record() { # artifact flags wall cpu rss size
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$stamp" "$1" "$2" "$3" "$4" "$5" "$6" >> "$HISTORY"
}

# ------------------------------------------------------------------------------------------------
# 1+2. Maven in a snapshot of the sources.
#   maven_build <variant> <java home> <graalvm version>  ->  $BUILD/<variant>/lib/*.jar
maven_build() {
  local variant="$1" jhome="$2" gv="$3"
  local dir="$BUILD/$variant" t0
  rm -rf "$dir/src" "$dir/lib" "$dir/deps"
  mkdir -p "$dir/src" "$dir/lib" "$dir/deps"
  # tar instead of rsync: available everywhere, and it leaves target/ behind.
  ( cd "$ROOT" && tar --exclude=target -cf - pom.xml $(echo "$all_modules" | tr ',' ' ') ) | tar -C "$dir/src" -xf -
  local pl="$modules"
  # Published mode with another Truffle version than the parent pom's (the Oracle GraalVM variant):
  # the published rlvm-cli pom resolves ${graalvm.version} through the parent pom in the repository,
  # where -Dgraalvm.version does not reach, and would bring the CE Truffle jars into the Oracle image
  # (native-image then fails in TruffleBaseFeature with NoSuchMethodError). rlvm-cli is the only
  # module with Truffle dependencies: compile it from the snapshot too, so that it is a reactor
  # module and its dependencies follow -Dgraalvm.version.
  if [ "$published" = 1 ] && [ "$gv" != "$pom_version" ]; then
    case ",$pl," in *,rlvm-cli,*) ;; *) pl="rlvm-cli,$pl" ;; esac
  fi
  local mvn_args=(-q -f "$dir/src/pom.xml" -pl "$pl" "-Dgraalvm.version=$gv")
  # Published mode: no -am, so upstream RLVM modules resolve from the local repository.
  if [ "$published" = 0 ]; then mvn_args+=(-am); fi
  if [ "$offline" = 1 ]; then mvn_args+=(-o); else mvn_args+=(--strict-checksums); fi
  if [ "$run_tests" = 0 ]; then mvn_args+=(-Dmaven.test.skip=true); fi
  log "[$variant] maven package (modules: $pl$([ "$published" = 1 ] && echo '; other RLVM modules as published in .toolchain/m2'); Truffle $gv; $([ "$offline" = 1 ] && echo offline || echo online))"
  t0="$(now)"
  # shellcheck disable=SC2086
  JAVA_HOME="$jhome" PATH="$jhome/bin:$PATH" $MVN "${mvn_args[@]}" \
      package org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy-dependencies \
      -DincludeScope=runtime "-DoutputDirectory=$dir/deps" -Dmdep.overWriteSnapshots=true \
      > "$dir/maven.log" 2>&1 || {
        grep -E '^\[ERROR\]' "$dir/maven.log" | head -n 12 >&2 || tail -n 40 "$dir/maven.log" >&2
        die "[$variant] maven failed (log: $dir/maven.log). If a module you do not need is broken, build without it (--modules rlvm-cli,...) or from the published jars (--published)"
      }
  local jar n=0
  for jar in "$dir"/src/*/target/*.jar; do
    case "$jar" in *-tests.jar|*-sources.jar|*-javadoc.jar) continue ;; esac
    cp "$jar" "$dir/lib/"; n=$((n + 1))
  done
  [ "$n" -gt 0 ] || die "[$variant] maven produced no jars"
  # Dependencies; a reactor module that is somebody's dependency is already there under the same name.
  for jar in "$dir"/deps/*.jar; do
    [ -e "$jar" ] || continue
    [ -e "$dir/lib/$(basename "$jar")" ] || cp "$jar" "$dir/lib/"
  done
  # Engines found through ServiceLoader that rlvm-main does not depend on (rlvm-machine: a dependency
  # of rlvm-main on it would close a reactor cycle through rlvm-testkit's test scope; rlvm-truffle: the
  # machine's upper tier, found by the machine through ServiceLoader and used only under the switch t2).
  # A tree build has the jars already (they are reactor modules); a published build takes the jars their
  # owners installed. Which engine is the default is decided by the providers' priorities (rlvm-main).
  if [ "$published" = 1 ]; then
    local eng ejar
    for eng in rlvm-machine rlvm-truffle; do
      case ",$all_modules," in *",$eng,"*) ;; *) continue ;; esac
      ls "$dir/lib/$eng"-*.jar >/dev/null 2>&1 && continue
      ejar="$(ls "$M2_REPO/rlvm/$eng"/*/"$eng"-*.jar 2>/dev/null | grep -v -e '-tests\.jar$' -e '-sources\.jar$' | head -n 1 || true)"
      if [ -n "$ejar" ]; then cp "$ejar" "$dir/lib/"; else log "[$variant] WARNING: no published $eng jar in $M2_REPO; the distribution has no engine '${eng#rlvm-}'"; fi
    done
  fi
  ls "$dir/lib"/rlvm-cli-*.jar >/dev/null 2>&1 || die "[$variant] rlvm-cli jar missing (is rlvm-cli among the modules?)"
  if [ "$published" = 1 ]; then
    # Record exactly which published jars went in (name, sha256 prefix, time of publication).
    ( cd "$dir/lib" && for j in rlvm-*.jar; do
        m="${j%-[0-9]*}"; src="$M2_REPO/rlvm/$m"
        printf '  %-18s %s  %s\n' "$m" "$(sha256sum "$j" | cut -c1-12)" \
          "$( [ -e "$dir/src/$m/target/$j" ] && echo 'compiled from the working tree' || date -u -r "$(ls "$src"/*/"$j" 2>/dev/null | head -n 1)" +'published %Y-%m-%dT%H:%M:%SZ' 2>/dev/null || echo published )"
      done ) > "$dir/published.txt"
  fi
  maven_secs="$(since "$t0")"
  log "[$variant] maven done in ${maven_secs}s: $(ls "$dir/lib" | wc -l) jars"
}

classpath_of() { ls "$1"/*.jar | LC_ALL=C sort | paste -sd: -; }

deps_list() { # sha256 list of third-party jars in a lib directory
  ( cd "$1" && for j in *.jar; do case "$j" in rlvm-*) ;; *) sha256sum "$j" ;; esac; done | LC_ALL=C sort -k2 )
}

# ------------------------------------------------------------------------------------------------
# 3. native-image.
#   native_build <variant> <graalvm home> <output name> <extra args...>   ->  $BUILD/<variant>/<name>
native_build() {
  local variant="$1" ghome="$2" name="$3"; shift 3
  local dir="$BUILD/$variant" tmp cp_ line
  cp_="$(classpath_of "$dir/lib")"
  local args=("-O$opt" "--gc=$gc" --no-fallback
              # An out-of-memory error ends the process at once with status 3 (= internal error).
              -R:+ExitOnOutOfMemoryError
              # ARCHITECTURE.md 7.2: the JVM launcher's heap limit as the image's run-time default
              # (-Xmx on the command line still overrides it). The nursery bound of 7.2
              # (-R:MaxNewSize=1g) is NOT baked in: with it a run-time -Xmx below 1g makes the
              # image refuse to start ("maximum new generation size must not be larger than the
              # maximum heap size", exit status 1). Pass --ni-arg -R:MaxNewSize=1g to measure it.
              -R:MaxHeapSize=13g
              -H:+ReportExceptionStackTraces)
  [ -z "$march" ] || args+=("-march=$march")
  args+=("${ni_extra[@]}" "$@")
  mkdir -p "$dir/ni-$name"
  tmp="$dir/ni-$name/time.txt"
  log "[$variant] native-image $name: ${args[*]}"
  local timer=() t0
  # GNU time gives CPU time and peak memory of the builder; without it only the wall time is recorded.
  if [ -n "$GNU_TIME" ]; then timer=("$GNU_TIME" -o "$tmp" -f '%e %U %S %M'); fi
  t0="$(now)"
  ( cd "$dir/ni-$name" && "${timer[@]}" \
      "$ghome/bin/native-image" "${args[@]}" -cp "$cp_" -o "$dir/$name" rlvm.cli.Main > build.log 2>&1 ) \
    || { grep -m 20 -iE '^Error|error:|Caused by' "$dir/ni-$name/build.log" >&2 || tail -n 40 "$dir/ni-$name/build.log" >&2
         die "[$variant] native-image failed (log: $dir/ni-$name/build.log)"; }
  if [ -n "$GNU_TIME" ]; then
    read -r ni_wall ni_user ni_sys ni_rss < <(tail -n 1 "$tmp")
  else
    ni_wall="$(since "$t0")"; ni_user=0; ni_sys=0; ni_rss=0
  fi
  ni_cpu="$(awk -v u="$ni_user" -v s="$ni_sys" 'BEGIN { printf "%.1f", u + s }')"
  ni_rss_mb=$((ni_rss / 1024))
  ni_size="$(stat -c %s "$dir/$name")"
  ni_flags="${args[*]}"
  # CE prints " - Graal compiler: ...", Oracle GraalVM " Graal compiler: ...".
  line="$(grep -E '^ (- )?(Graal compiler|Garbage collector):' "$dir/ni-$name/build.log" | sed -E 's/^ (- )?//' | paste -sd';' - || true)"
  log "[$variant] $name: ${ni_wall}s wall, ${ni_cpu}s cpu, peak ${ni_rss_mb} MB, $((ni_size / 1024 / 1024)) MB binary | $line"
  ni_summary="$line"
}

# ------------------------------------------------------------------------------------------------
# 4. Verification of one launcher (path, label). Exits non-zero on the first failure.
smoke_export=""
for f in "$ROOT/work/arena-site/tests/good/init-prelude.ndjson" "$ROOT"/work/arena-site/tests/good/tutorial/*.ndjson; do
  if [ -f "$f" ]; then smoke_export="$f"; break; fi
done
expect() { # label expected-status command...
  local label="$1" want="$2" got=0; shift 2
  "$@" > "$STAGE/verify.out" 2>&1 || got=$?
  if [ "$got" != "$want" ]; then
    cat "$STAGE/verify.out" >&2
    die "verification failed: $label: exit status $got, expected $want"
  fi
}
verify() {
  local bin="$1" label="$2" mode t0
  t0="$(now)"
  expect "$label --version" 0 "$bin" --version
  grep -q '^rlvm ' "$STAGE/verify.out" || die "verification failed: $label --version prints no version"
  for mode in accept:0 reject:1 decline:2 null:3 throw:3 error:3 oom:3 stackoverflow:3 thread-death:3; do
    expect "$label --protocol-selftest ${mode%%:*}" "${mode##*:}" "$bin" --protocol-selftest "${mode%%:*}" --stack-size 16m
  done
  # Real heap exhaustion under a 128 MB cap: status 3, never 0 (the fake checker would "accept").
  expect "$label oom-real" 3 "$bin" -Xmx128m --protocol-selftest oom-real
  expect "$label no input" 64 env -u IN "$bin"
  # A setting the checker refuses is a usage error (64), never a verdict and never 3.
  expect "$label --protocol-selftest usage" 64 "$bin" --protocol-selftest usage --stack-size 16m
  if [ -n "$smoke_export" ] && "$bin" --version 2>/dev/null | grep -q '^checker: rlvm '; then
    expect "$label unknown -X setting" 64 "$bin" -X no-such-setting=1 "$smoke_export"
  fi
  expect "$label --version with an export" 64 "$bin" --version /dev/null
  if [ -n "$smoke_export" ]; then
    expect "$label placeholder on $(basename "$smoke_export")" 2 env "IN=$smoke_export" "$bin" --checker placeholder
  fi
  # The default checker (the real pipeline when rlvm-main is in the build) on one good and one bad
  # published test: the right verdict or a decline; a wrong verdict or an error fails the build.
  local t want got
  for t in good/tutorial/001_basicDef:0 bad/tutorial/002_badDef:1; do
    want="${t##*:}"; t="$ROOT/work/arena-site/tests/${t%%:*}.ndjson"
    [ -f "$t" ] || continue
    got=0
    "$bin" --threads 1 "$t" > "$STAGE/verify.out" 2>&1 || got=$?
    if [ "$got" != "$want" ] && [ "$got" != 2 ]; then
      cat "$STAGE/verify.out" >&2
      die "verification failed: $label on $(basename "$t"): exit status $got, expected $want (or 2 = decline)"
    fi
    [ "$got" = "$want" ] || log "[verify] $label DECLINES $(basename "$t") (expected $want): $(head -n 1 "$STAGE/verify.out")"
  done
  expect "$label --truffle-probe" 0 "$bin" --truffle-probe -X engine.CompilerThreads=1
  grep -q 'OK: runtime compilation works' "$STAGE/verify.out" || die "verification failed: $label --truffle-probe"
  probe_line="$(grep 'polyglot engine' "$STAGE/verify.out" | sed 's/^rlvm: truffle-probe: //')"
  log "[verify] $label ok in $(since "$t0")s | $probe_line"
}

# ================================================================================================
total_t0="$(now)"
ce_version="$(graalvm_version "$CE_HOME")"
[ -n "$ce_version" ] || die "cannot read GRAALVM_VERSION from $CE_HOME/release"
pom_version="$(sed -n 's:.*<graalvm.version>\(.*\)</graalvm.version>.*:\1:p' "$ROOT/pom.xml")"
[ "$pom_version" = "$ce_version" ] || log "WARNING: parent pom says graalvm.version=$pom_version but $CE_HOME is $ce_version; building against $ce_version"

maven_build ce "$CE_HOME" "$ce_version"
if [ "$print_deps" = 1 ]; then deps_list "$BUILD/ce/lib"; exit 0; fi
if [ -n "$verify_deps" ]; then
  deps_list "$BUILD/ce/lib" > "$BUILD/ce/deps.sha256"
  if ! diff -u <(grep -vE '^(#|[[:space:]]*$)' "$verify_deps") "$BUILD/ce/deps.sha256" >&2; then
    die "third-party jars differ from the pinned list $verify_deps"
  fi
  log "[ce] third-party jars match $verify_deps"
fi
cp "$BUILD"/ce/lib/*.jar "$STAGE/dist/lib/"

{
  echo "RLVM distribution"
  echo "built:            $stamp on $(uname -sm) by scripts/build.sh"
  echo "modules:          $modules$([ "$published" = 1 ] && echo ' (compiled); all other RLVM modules as published in .toolchain/m2:')"
  [ "$published" = 0 ] || cat "$BUILD/ce/published.txt"
  echo "java.home:        $CE_HOME"
  echo "graalvm (CE):     $ce_version ($(sed -n 's/^JAVA_RUNTIME_VERSION="\(.*\)"$/\1/p' "$CE_HOME/release"))"
  echo "maven build:      ${maven_secs}s"
  echo "jars:             $(du -cb "$STAGE"/dist/lib/*.jar | tail -n 1 | cut -f1) bytes in $(ls "$STAGE/dist/lib" | wc -l) files"
} > "$INFO"
record "jars" "maven package ($modules)" "$maven_secs" "" "" "$(du -cb "$STAGE"/dist/lib/*.jar | tail -n 1 | cut -f1)"

# --- JVM launcher -------------------------------------------------------------------------------
# Default VM flags (docs/research/11-memory-reuse-parallelism.md section 0 and B.10; measurements in
# spikes/platform/results/ and spikes/memory/results/):
#   -XX:+UseSerialGC             fewest instructions of all collectors (gc-matrix-instr.txt: Serial 54 G,
#                                Parallel 75 G, G1 204 G for the same job); collects while workers are parked
#   -Xms13g -Xmx13g -Xmn1g       fixed heap and nursery, no resizing (ARCHITECTURE.md 7.2: the working set
#                                is primitive arrays in the old generation, only big-number garbage fills
#                                the nursery; 13 GB leaves 3 GB of the runner's 16 GB for stacks, code and
#                                the page cache; the memory account declines at 12 GiB, before the heap
#                                limit). Was 8g / 8g / 3g, sized for an object working set. Dropped when
#                                the caller passes its own -Xmx/-Xms/-Xmn. Nothing is pre-touched, so a
#                                small test costs no more memory than before
#   -XX:-UseJVMCICompiler        C2 instead of Graal as the *host* JIT: fewer instructions for the same
#                                steady state (javac-instr.txt 33.7 G vs 46.6 G; truffle-many-instr.txt
#                                49.6 G vs 65.8 G). Truffle runtime compilation still uses libgraal
#   -XX:CICompilerCount=2        the minimum for C1 + C2; compiler threads are billed
#   -XX:+ExitOnOutOfMemoryError  the VM exits with status 3 (= internal error) on the first OOM
#   --enable-native-access / --sun-misc-unsafe-memory-access   silence JDK 25 warnings caused by Truffle
JVM_FLAGS="-XX:+UseSerialGC -XX:-UseJVMCICompiler -XX:CICompilerCount=2 -XX:+ExitOnOutOfMemoryError --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow"
JVM_HEAP_FLAGS="-Xms13g -Xmx13g -Xmn1g"
cat > "$STAGE/dist/bin/rlvm-jvm" <<LAUNCHER
#!/bin/sh
# RLVM, JVM mode. Generated by scripts/build.sh on $stamp; do not edit.
#
#   rlvm-jvm [-J<jvm flag>]... [rlvm options] [EXPORT.ndjson]        (export also via \$IN)
#
# Like the native image, it takes -Xmx/-Xms/-Xmn/-Xss, -XX:... and -D... for the VM itself; giving a
# heap size (-Xmx/-Xms/-Xmn) drops the default heap flags.
# Environment: RLVM_JAVA (java binary), RLVM_JAVA_OPTS (replaces all default VM flags below),
# RLVM_EXTRA_CP (class path entries appended after lib/*: development plug-ins such as the test
# kit's differential engine; nothing of it is part of the distribution).
here="\$(cd "\$(dirname "\$0")" && pwd -P)"
java="\${RLVM_JAVA:-}"
if [ -z "\$java" ]; then
  if [ -x "$CE_HOME/bin/java" ]; then java="$CE_HOME/bin/java"; else java=java; fi
fi
user=""
heap="$JVM_HEAP_FLAGS"
n=\$#
while [ "\$n" -gt 0 ]; do
  a="\$1"; shift; n=\$((n - 1))
  case "\$a" in
    -J-Xmx*|-J-Xms*|-J-Xmn*) user="\$user \${a#-J}"; heap="" ;;
    -J*) user="\$user \${a#-J}" ;;
    -Xmx[0-9]*|-Xms[0-9]*|-Xmn[0-9]*) user="\$user \$a"; heap="" ;;
    -Xss[0-9]*|-XX:*|-D*) user="\$user \$a" ;;
    *) set -- "\$@" "\$a" ;;
  esac
done
if [ -n "\${RLVM_JAVA_OPTS:-}" ]; then flags="\$RLVM_JAVA_OPTS"; else flags="$JVM_FLAGS \$heap"; fi
# shellcheck disable=SC2086
exec "\$java" \$flags \$user -cp "\$here/../lib/*\${RLVM_EXTRA_CP:+:\$RLVM_EXTRA_CP}" rlvm.cli.Main "\$@"
LAUNCHER
chmod +x "$STAGE/dist/bin/rlvm-jvm"
echo "jvm flags:        $JVM_FLAGS $JVM_HEAP_FLAGS" >> "$INFO"
verify "$STAGE/dist/bin/rlvm-jvm" "rlvm-jvm"

# --- native image, CE ---------------------------------------------------------------------------
if [ "$want_native" = 1 ]; then
  native_build ce "$CE_HOME" rlvm
  verify "$BUILD/ce/rlvm" "rlvm (native, CE)"
  cp "$BUILD/ce/rlvm" "$STAGE/dist/bin/rlvm"
  {
    echo "native (CE):      bin/rlvm  $ni_size bytes; built in ${ni_wall}s wall, ${ni_cpu}s cpu, peak ${ni_rss_mb} MB"
    echo "  flags:          $ni_flags"
    echo "  builder:        $ni_summary"
    echo "  truffle probe:  $probe_line"
  } >> "$INFO"
  record "rlvm (native CE $ce_version)" "$ni_flags" "$ni_wall" "$ni_cpu" "$ni_rss_mb" "$ni_size"
fi

# --- native image, Oracle GraalVM + PGO ---------------------------------------------------------
if [ "$want_pgo" = yes ]; then
  oracle_version="$(graalvm_version "$ORACLE_HOME_DIR")"
  [ -n "$oracle_version" ] || die "cannot read GRAALVM_VERSION from $ORACLE_HOME_DIR/release"
  maven_build oracle "$ORACLE_HOME_DIR" "$oracle_version"
  native_build oracle "$ORACLE_HOME_DIR" rlvm-instr --pgo-instrument
  instr_wall="$ni_wall"; instr_size="$ni_size"
  record "rlvm-instr (Oracle $oracle_version, instrumented)" "$ni_flags" "$ni_wall" "$ni_cpu" "$ni_rss_mb" "$ni_size"

  # Training (step c of the header).
  prof="$BUILD/oracle/profiles"
  rm -rf "$prof"; mkdir -p "$prof"
  if [ "${#train[@]}" -eq 0 ]; then
    for f in "$ROOT/work/arena-site/tests/good/init-prelude.ndjson" "$ROOT/work/arena-site/generated/init.ndjson"; do
      if [ -f "$f" ]; then train+=("$f"); fi
    done
  fi
  t0="$(now)"
  RLVM_EXIT=exit "$BUILD/oracle/rlvm-instr" "-XX:ProfilesDumpFile=$prof/probe.iprof" --truffle-probe > "$prof/probe.log" 2>&1 \
    || { cat "$prof/probe.log" >&2; die "[oracle] training run --truffle-probe failed"; }
  i=0
  for f in "${train[@]}"; do
    i=$((i + 1)); rc=0
    RLVM_EXIT=exit "$BUILD/oracle/rlvm-instr" "-XX:ProfilesDumpFile=$prof/train-$i.iprof" --stats "$f" "${train_args[@]}" > "$prof/train-$i.log" 2>&1 || rc=$?
    # A training run may accept, reject or decline; anything else means the profile is not to be trusted.
    case "$rc" in 0|1|2) ;; *) cat "$prof/train-$i.log" >&2; die "[oracle] training run on $f ended with status $rc" ;; esac
    log "[oracle] trained on $f (exit $rc)"
  done
  profiles="$(ls "$prof"/*.iprof 2>/dev/null | paste -sd, -)"
  [ -n "$profiles" ] || die "[oracle] the training runs wrote no profile"
  train_secs="$(since "$t0")"
  log "[oracle] training took ${train_secs}s: $(ls "$prof"/*.iprof | wc -l) profiles, $(du -cb "$prof"/*.iprof | tail -n 1 | cut -f1) bytes"

  native_build oracle "$ORACLE_HOME_DIR" rlvm-pgo "--pgo=$profiles"
  verify "$BUILD/oracle/rlvm-pgo" "rlvm-pgo (native, Oracle GraalVM + PGO)"
  cp "$BUILD/oracle/rlvm-pgo" "$STAGE/dist/bin/rlvm-pgo"
  {
    echo "native (PGO):     bin/rlvm-pgo  $ni_size bytes; built in ${ni_wall}s wall, ${ni_cpu}s cpu, peak ${ni_rss_mb} MB"
    echo "  graalvm:        Oracle GraalVM $oracle_version ($ORACLE_HOME_DIR), GFTC licence"
    echo "  flags:          $(echo "$ni_flags" | sed "s:$prof/::g")"
    echo "  builder:        $ni_summary"
    echo "  instrumented:   built in ${instr_wall}s, $instr_size bytes"
    echo "  training:       ${train_secs}s; --truffle-probe + ${#train[@]} export(s): ${train[*]:-none}${train_args[*]:+; training arguments: ${train_args[*]}}"
    echo "  truffle probe:  $probe_line"
  } >> "$INFO"
  record "rlvm-pgo (Oracle $oracle_version, PGO)" "$(echo "$ni_flags" | sed "s:$prof/::g")" "$ni_wall" "$ni_cpu" "$ni_rss_mb" "$ni_size"
fi

echo "total build:      $(since "$total_t0")s" >> "$INFO"

# --- 5. install -----------------------------------------------------------------------------------
mkdir -p "$(dirname "$DIST")"
if [ "$versioned" = 1 ]; then
  # Versioned: the build becomes DIST.d/<version>, and DIST is a symbolic link switched to it by one
  # rename(2). Nobody ever sees a half-replaced or missing distribution, and a reader that resolved
  # the link before the switch keeps the complete old build (deleted only by the pruning below).
  store="$DIST.d"
  mkdir -p "$store"
  version="$(date -u +%Y%m%dT%H%M%SZ)-$(cat "$STAGE"/dist/lib/rlvm-*.jar | sha256sum | cut -c1-12)-$([ -e "$STAGE/dist/bin/rlvm" ] && echo native || echo jvm)"
  [ ! -e "$store/$version" ] || version="$version.$$"
  echo "version:          $(basename "$store")/$version" >> "$INFO"
  mv "$STAGE/dist" "$store/$version"
  if [ -e "$DIST" ] && [ ! -L "$DIST" ]; then
    # One-time conversion of a distribution that is still a plain directory: it becomes a version
    # as well (processes running from it keep their open files), then the link takes its place.
    legacy="$store/$(date -u -r "$DIST" +%Y%m%dT%H%M%SZ 2>/dev/null || echo unknown)-before-versioning"
    [ ! -e "$legacy" ] || legacy="$legacy.$$"
    mv "$DIST" "$legacy"
    log "converted the directory $DIST into the version $(basename "$legacy")"
  fi
  ln -s "$(basename "$store")/$version" "$DIST.new.$$"
  mv -T "$DIST.new.$$" "$DIST" 2>/dev/null || { rm -f "$DIST"; mv "$DIST.new.$$" "$DIST"; }
  # Pruning: keep the newest RLVM_DIST_KEEP versions and everything younger than
  # RLVM_DIST_MIN_AGE_MIN minutes (a guard that pinned a version an hour ago still has it).
  keep="${RLVM_DIST_KEEP:-4}"; min_age="${RLVM_DIST_MIN_AGE_MIN:-90}"
  n=0
  while IFS= read -r v; do
    n=$((n + 1))
    [ "$n" -gt "$keep" ] || continue
    [ "$v" != "$store/$version" ] || continue
    [ -n "$(find "$v" -maxdepth 0 -mmin "+$min_age" 2>/dev/null)" ] || continue
    rm -rf "$v" && log "pruned the old version $(basename "$v")"
  done < <(ls -1dt "$store"/*/ 2>/dev/null | sed 's:/$::')
  log "installed $DIST -> $(readlink "$DIST")"
else
  # Whole-directory replacement: processes still running the old binaries or jars keep their files.
  if [ -L "$DIST" ]; then
    rm -f "$DIST"
    mv "$STAGE/dist" "$DIST"
  elif [ -e "$DIST" ]; then
    old="$DIST.old.$$"
    mv "$DIST" "$old"
    mv "$STAGE/dist" "$DIST"
    rm -rf "$old"
  else
    mv "$STAGE/dist" "$DIST"
  fi
  log "installed $DIST"
fi
rm -rf "$STAGE"
cat "$DIST/BUILD-INFO.txt"
