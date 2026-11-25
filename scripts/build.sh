#!/usr/bin/env bash
# scripts/build.sh - build RLVM: the release executable, or a development distribution.
#
#   scripts/build.sh [options]            (scripts/build.sh --help)
#
# THE RELEASE IS ONE EXECUTABLE (OWNER-NOTES 16). Result (default work/dist/):
#   bin/rlvm         the native image, built with GraalVM Community Edition: self-contained (no JDK,
#                    no jars, no second launcher), with Truffle runtime compilation; `rlvm FILE` checks
#                    with the fastest configuration and `rlvm --version` says what that is. It is what
#                    scripts/package.sh puts into the release archive and what the arena definition runs
#   BUILD-INFO.txt   versions, flags, build times, sizes (not part of the release archive)
# and nothing else. A DEVELOPMENT distribution (--dev, or --jvm-only without the executable) adds
#   lib/*.jar        every module of the reactor plus its runtime dependencies (Truffle, polyglot)
#   bin/rlvm-jvm     a shell launcher that runs those jars on the build's GraalVM (HotSpot + JIT): the
#                    platform of the warm benchmarks (bench/warm.sh, OWNER-NOTES 12), of the gates
#                    (scripts/gate.sh) and of the test kit. A tool of this repository: it carries the
#                    path of the build's JDK and is never packaged
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
#   2. Maven packages the modules and copies their runtime dependencies. The test kit (rlvm-testkit)
#      is a development tool: no build uses its jar, so a tree build and a --published build are made
#      from the same set of jars.
#   3. native-image builds bin/rlvm from exactly those jars. Image-wide options are set here - the
#      optimisation level, the collector and the MEMORY SETTINGS that are baked in (IMAGE_HEAP below) -
#      and the text `rlvm --version` prints as "build:" (-Drlvm.build=...); package-specific options
#      (build-time initialisation) come from each jar's own
#      META-INF/native-image/**/native-image.properties. The machine's inline lists ("H10": force-
#      inline its continuation steps, never inline its slow paths) are generated from the machine
#      jar of the build and added when that works (--no-h10: never).
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
# ORACLE GraalVM WITH A SHIPPED PROFILE: an OPTION, never the default (OWNER-NOTES 16 and 11)
#   scripts/build.sh --oracle-pgo        builds THE SAME single executable bin/rlvm with Oracle GraalVM
#                                        and profile-guided optimisation from the profiles that are part
#                                        of the repository (profiles/*.iprof.gz; profiles/PROFILE-INFO.txt
#                                        says which sources, settings, training inputs and compiler made
#                                        them): no training run is needed. `rlvm --version` of that
#                                        binary says "Oracle GraalVM, profile-guided".
#   Oracle GraalVM is licensed under the GraalVM Free Terms and Conditions (GFTC), not an open-source
#   licence; the option says so on its first line of output. It uses .toolchain/oracle-graalvm-25 (or
#   $RLVM_ORACLE_GRAALVM) when that exists and downloads the pinned Oracle GraalVM ONLY when
#   --accept-oracle-licence is given as well (scripts/fetch-toolchain.sh --oracle --accept-gftc). The
#   default build, the arena build and CI use GraalVM Community Edition and never download or run it.
#   The modules are compiled against the Truffle version of the Oracle GraalVM (its GRAALVM_VERSION).
#   REGENERATING THE PROFILES (scripts/dev.sh profiles = scripts/build.sh --oracle-pgo --train-profiles
#   --train FILE...): an instrumented image is built (native-image --pgo-instrument), it runs
#   `--truffle-probe` (so that the runtime compiler itself is profiled) and then checks every training
#   export in the DEFAULT configuration (plus --train-arg ARG...; with --train-also 'ARGS' a second time
#   with those arguments, e.g. the engine that is not the default one), with RLVM_EXIT=exit because the
#   profile is written by a shutdown hook that the launcher's default Runtime.halt would skip; the
#   profiles are compressed into profiles/ with a new PROFILE-INFO.txt, and the executable is then
#   built from them like any --oracle-pgo build. The profile is only as good as the training runs.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"

usage() {
  cat <<'EOF'
Usage: scripts/build.sh [options]

What to build (default: THE RELEASE, one executable bin/rlvm built with GraalVM Community Edition):
  --dev                 a development distribution: bin/rlvm plus lib/*.jar and bin/rlvm-jvm, the JVM
                        launcher of the warm benchmarks, the gates and the test kit
  --jvm-only            a development distribution without the executable: lib/*.jar and bin/rlvm-jvm
                        (about 15 s)
  --oracle-pgo          build the executable with ORACLE GraalVM (GFTC licence, not open source) and
                        profile-guided optimisation from the profiles shipped in profiles/. Never the
                        default. Uses .toolchain/oracle-graalvm-25 if it exists; downloads the pinned
                        Oracle GraalVM only together with --accept-oracle-licence. Its distribution
                        is work/dist-oracle-pgo unless --dist says otherwise (never the shared work/dist)
  --accept-oracle-licence
                        with --oracle-pgo: you accept the GraalVM Free Terms and Conditions; the pinned
                        Oracle GraalVM may be downloaded (scripts/fetch-toolchain.sh --oracle --accept-gftc)
  --profiles DIR        with --oracle-pgo: the directory of the profiles (default profiles/)
  --train-profiles      with --oracle-pgo: make the profiles anew before building: instrumented image,
                        training runs (--train), profiles/*.iprof.gz and profiles/PROFILE-INFO.txt
  --train FILE          training export of --train-profiles (repeatable)
  --train-arg ARG       extra argument of every training run (repeatable; default: none, i.e. the
                        default configuration)
  --train-also 'ARGS'   a SECOND training run of every export with these extra arguments (one string,
                        split on blanks), e.g. --train-also '-X engine=reference', so that the profile
                        also covers an engine or a setting that the default configuration does not run
  --modules A,B         build only these modules (and what they depend on); default: all modules
                        of the parent pom
  --published           compile only the --modules (default: rlvm-main) and take every other RLVM
                        module as published in .toolchain/m2 (see "How it builds")
  --tests               run the unit tests during the Maven build (default: not even compiled)

Native image:
  --opt LEVEL           optimisation level: b (fast build), 1, 2, 3 (default; what the release and
                        the arena build use: 10-18 % less CPU time than 2 for the machine, 2-3 minutes
                        of build instead of 1-2)
  --march VALUE         e.g. native, compatibility (default: native-image's own default)
  --gc NAME             serial (default), epsilon, G1 (Oracle GraalVM only)
  --ni-arg ARG          extra native-image argument (repeatable), e.g. --ni-arg -R:MaxHeapSize=12g
                        (it follows the baked-in memory settings: a later value wins)
  --no-h10              build the CE image without the machine's inline lists (default: with them
                        when rlvm-machine is in the build; see "H10" in the script)

Other:
  --dist DIR            where to put the distribution (default: work/dist, the shared one, which
                        is versioned: work/dist is a symbolic link into work/dist.d/, see the
                        header of this script)
  --versioned           install DIR as DIR.d/<version> + symbolic link, like the shared distribution
  --in-place            replace the directory itself (the default for --dist DIR)
  --online              let Maven download missing artifacts (default: offline)
  --verify-deps FILE    check the third-party jars against a sha256 list (scripts/arena/deps.sha256)
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
dev=0             # 1: lib/*.jar and bin/rlvm-jvm as well (a development distribution)
flavor=ce         # ce | oracle-pgo. Oracle GraalVM is used only on request: OWNER-NOTES 11 and 16
pgo_alias=0
accept_oracle=0
profiles_dir="$ROOT/profiles"
train_profiles=0
modules=""
published=0
run_tests=0
opt=3
march=""
versioned=auto
train_args=()
train_also=""
gc=serial
ni_extra=()
train=()
offline=1
verify_deps=""
print_deps=0

h10=auto

while [ "$#" -gt 0 ]; do
  case "$1" in
    --dev) dev=1; shift ;;
    --jvm-only) want_native=0; dev=1; shift ;;
    --no-h10) h10=no; shift ;;
    --h10) h10=auto; shift ;;
    --no-pgo) shift ;;   # older command lines: the default has always been GraalVM CE
    --oracle-pgo) flavor=oracle-pgo; shift ;;
    --pgo) flavor=oracle-pgo; pgo_alias=1; shift ;;
    --accept-oracle-licence|--accept-oracle-license) accept_oracle=1; shift ;;
    --profiles) profiles_dir="$(realpath -m "${2:?--profiles needs a directory}")"; shift 2 ;;
    --train-profiles) train_profiles=1; shift ;;
    --modules) modules="${2:?--modules needs a list}"; shift 2 ;;
    --published) published=1; shift ;;
    --tests) run_tests=1; shift ;;
    --opt) opt="${2:?--opt needs a level}"; shift 2 ;;
    --march) march="${2:?--march needs a value}"; shift 2 ;;
    --gc) gc="${2:?--gc needs a name}"; shift 2 ;;
    --ni-arg) ni_extra+=("${2:?--ni-arg needs an argument}"); shift 2 ;;
    --train) train+=("$(realpath -e "${2:?--train needs a file}")"); shift 2 ;;
    --train-arg) train_args+=("${2:?--train-arg needs an argument}"); shift 2 ;;
    --train-also) train_also="${2:?--train-also needs the arguments of the second run}"; shift 2 ;;
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
if [ "$flavor" = oracle-pgo ]; then
  # The first line of output of the option (OWNER-NOTES 16): what it uses and under which licence.
  echo "build.sh: --oracle-pgo: this build uses ORACLE GraalVM, which is licensed under the GraalVM Free Terms and Conditions (GFTC), with profile-guided optimisation; the default build uses GraalVM Community Edition only and never downloads or runs Oracle GraalVM." >&2
  [ "$pgo_alias" = 0 ] || log "--pgo is the old name of --oracle-pgo; it no longer builds a second binary bin/rlvm-pgo: the executable of this build is bin/rlvm"
  [ "$dev" = 0 ] || die "--oracle-pgo builds the release layout only (one executable); for the JVM launcher build a --dev distribution with GraalVM CE"
elif [ "$train_profiles" = 1 ] || [ "$accept_oracle" = 1 ]; then
  die "--train-profiles and --accept-oracle-licence belong to --oracle-pgo"
fi
if [ "$train_profiles" = 1 ] && [ "${#train[@]}" -eq 0 ]; then
  die "--train-profiles needs at least one --train FILE (scripts/dev.sh profiles names the inputs)"
fi
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
elif [ "$flavor" = oracle-pgo ]; then
  # Never the shared distribution: work/dist is what the gates, the benchmarks and the arena definition
  # use, and it is a GraalVM CE build. The optional build has a directory of its own.
  DIST="$ROOT/work/dist-oracle-pgo"
  [ "$versioned" != auto ] || versioned=0
else
  [ "$versioned" != auto ] || versioned=1
fi
if [ "$flavor" = oracle-pgo ] && [ "$DIST" = "$ROOT/work/dist" ]; then
  die "--oracle-pgo: work/dist is the shared GraalVM CE distribution (gates, benchmarks, the arena definition); give the Oracle build another --dist"
fi

# shellcheck source=../env.sh
. "$ROOT/env.sh"
[ -x "$JAVA_HOME/bin/java" ] || die "no JDK at $JAVA_HOME (run scripts/fetch-toolchain.sh)"
CE_HOME="$JAVA_HOME"
ORACLE_HOME_DIR="${RLVM_ORACLE_GRAALVM:-$ROOT/.toolchain/oracle-graalvm-25}"

# ------------------------------------------------------------------------------------------------
# MEMORY SETTINGS BAKED INTO THE EXECUTABLE (run-time defaults of the image; -Xmx / -Xms / -Xmn and
# -XX:... on its command line still override them). Decided by measurement for what one invocation
# of an executable is - a single run of a fresh process on each of the corpora, 8 workers - within the
# 16 GB of the arena's runner and without a cost for small inputs (*agentic-note*: dec-EGMG,
# *agentic-note*: design-packaging; the numbers below are the machine with the switch set
# default,eager,skipargs,congruence on the aarch64 development host, 2026-11-25).
#   -R:MaxHeapSize=13g   the heap limit: 13 GB leaves 3 GB of a 16 GB machine for stacks, code and the
#                        page cache; the memory account of the driver declines at 12 GiB, before it
#   -R:InitialCollectionPolicy=BySpaceAndTime
#                        the serial collector's policy with a young generation of fixed size (256 MB,
#                        less under a small -Xmx) that divides its time evenly between young and full
#                        collections, instead of the default (Adaptive2), which runs this workload with
#                        an eden of about 54 MB and a full collection every 12 to 15 young ones: 0.94
#                        to 0.99 times the wall clock on Init, Std, cedar, navier-stokes-euler, cslib and
#                        mathlib (6 to 10 runs each; mathlib 184 -> 41 collections), reachability and the
#                        heaviest published tests within their noise. It holds more memory: +12 to +30 %
#                        resident on the corpora (mathlib 4.0 -> 5.4 GB), bounded by the heap limit
#   NOT baked in, measured and rejected: a smaller young generation for that policy
#   (-R:MaximumYoungGenerationSizePercent=1, 133 MB: the same gain, but 7.2 GB resident on mathlib; an
#   absolute -R:MaxNewSize would make the image refuse to start under a smaller run-time -Xmx, exit
#   status 1), its time ratio (20: half the gain; 80: no more gain, more memory), a minimum heap
#   (-Xms2g / -Xms6g: nothing consistent, up to twice the resident memory), the policies Proportionate
#   and Adaptive, -R:MaxHeapFree (the garbage is large arrays in chunks of their own, which the
#   collector always returns to the system), 4 MiB heap chunks (only with the copying old generation:
#   3-5 % slower).
#   A small input is not affected: nothing is pre-touched (a tutorial test: 20 MB resident, as before).
# IMAGE_HEAP_TEXT is what `rlvm --version` says about them.
IMAGE_HEAP=(-R:MaxHeapSize=13g -R:InitialCollectionPolicy=BySpaceAndTime)
# The same settings for the optional Oracle GraalVM build: Oracle GraalVM 25.0.4 knows the collection
# policy only as an option of the image BUILD (-H:, experimental; its own default is Adaptive), not as
# a run-time default (-R:) as GraalVM CE 25.5.5 does, so there it cannot be changed on the command line.
IMAGE_HEAP_ORACLE=(-R:MaxHeapSize=13g -H:+UnlockExperimentalVMOptions -H:InitialCollectionPolicy=BySpaceAndTime -H:-UnlockExperimentalVMOptions)
IMAGE_HEAP_TEXT="heap at most 13g, collection policy BySpaceAndTime"
# ------------------------------------------------------------------------------------------------

graalvm_version() { sed -n 's/^GRAALVM_VERSION="\{0,1\}\([^"]*\)"\{0,1\}$/\1/p' "$1/release"; }

if [ "$flavor" = oracle-pgo ]; then
  if [ ! -x "$ORACLE_HOME_DIR/bin/native-image" ]; then
    if [ "$accept_oracle" = 1 ] && [ -z "${RLVM_ORACLE_GRAALVM:-}" ]; then
      "$ROOT/scripts/fetch-toolchain.sh" --oracle --accept-gftc || die "--oracle-pgo: could not fetch the pinned Oracle GraalVM"
    else
      die "--oracle-pgo: no Oracle GraalVM at $ORACLE_HOME_DIR. It is not downloaded unless you also pass --accept-oracle-licence (you then accept the GraalVM Free Terms and Conditions)"
    fi
  fi
  [ -x "$ORACLE_HOME_DIR/bin/native-image" ] || die "--oracle-pgo: no native-image in $ORACLE_HOME_DIR"
  if [ "$train_profiles" = 0 ]; then
    ls "$profiles_dir"/*.iprof.gz >/dev/null 2>&1 || die "--oracle-pgo: no profiles (*.iprof.gz) in $profiles_dir; make them with scripts/dev.sh profiles"
  fi
elif [ "$want_native" = 1 ]; then
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
# Modules of the reactor that are development tools and never part of a distribution: the test kit
# (oracle cache, mutants, the differential engine; it reaches a launcher through RLVM_EXTRA_CP). A tree
# build used to put its jar on the class path of the image (23 jars) while every distribution that is
# gated and measured is built from the published jars and has 22 (AR1.md 0.5, item 2): the arena build
# is a tree build and must be the gated binary. So it is neither compiled by default nor ever copied
# into lib/ (name it in --modules to have it compiled).
NOT_SHIPPED="rlvm-testkit"
shipped_modules="$(echo "$all_modules" | tr ',' '\n' | grep -vxF -e "$NOT_SHIPPED" | paste -sd, -)"
if [ -z "$modules" ]; then
  if [ "$published" = 1 ]; then modules="rlvm-main"; else modules="$shipped_modules"; fi
fi

STAGE="$BUILD/stage"
rm -rf "$STAGE"
mkdir -p "$STAGE/dist/bin"
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
    case "$(basename "$jar")" in "$NOT_SHIPPED"-*) continue ;; esac   # a development tool, never shipped
    cp "$jar" "$dir/lib/"; n=$((n + 1))
  done
  [ "$n" -gt 0 ] || die "[$variant] maven produced no jars"
  # Dependencies; a reactor module that is somebody's dependency is already there under the same name.
  for jar in "$dir"/deps/*.jar; do
    [ -e "$jar" ] || continue
    case "$(basename "$jar")" in "$NOT_SHIPPED"-*) continue ;; esac
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

# The H10 inline lists are 7 kB of method names: logs and BUILD-INFO.txt show their sizes instead.
brief_flags() {
  echo "$1" | awk '{ for (i = 1; i <= NF; i++) if ($i ~ /^-H:Priority(Force|Never)Inline=/) { n = split($i, m, ","); sub(/=.*/, "=<" n " methods of rlvm-machine>", $i) } print }'
}
ni_soft_fail=0

# H10 (docs/design/review/stepper-convention.md, rule 3; request of the machine's facade owner, measured
# by the wave-6 integrator on matched images, rlvm-machine/SWITCHES.md "Build flag measured": CPU time of
# the machine -0.7 to -4.4 % on four of five corpora, wall clock equal): the CE image is built with
#   -H:PriorityForceInline=<every continuation step k_* of rlvm-machine>
#   -H:PriorityNeverInline=<its slow paths: Machine.sig*, spill*, *Slow>
# The lists are generated at build time from the class files of the machine jar that goes into the
# image, by the machine's own script rlvm-machine/h10/native-image-args.sh. It needs javap, which is in
# the GraalVM this build runs on, and reads a directory of classes without any other tool: the jar is
# unpacked with the JDK's own `jar` (the script would use unzip on a jar, and the arena's build shell,
# refs/lean-kernel-arena/flake.nix, has no unzip). Tuning only: the lists name methods of the machine,
# which runs on request (-X engine=machine); if anything of this fails - no machine jar, no script, a
# native-image that refuses the lists - the image is built without them and the log says so.
#   h10_lists <lib directory>  ->  the words in the array ni_h10 (empty: not available)
ni_h10=()
h10_lists() {
  local lib="$1" mjar script="$ROOT/rlvm-machine/h10/native-image-args.sh" work="$BUILD/ce/h10" line
  ni_h10=()
  mjar="$(ls "$lib"/rlvm-machine-*.jar 2>/dev/null | head -n 1 || true)"
  [ -n "$mjar" ] || { log "[h10] no rlvm-machine jar in the build: no inline lists"; return 0; }
  [ -x "$script" ] || { log "[h10] $script is not there: no inline lists"; return 0; }
  [ -x "$CE_HOME/bin/javap" ] && [ -x "$CE_HOME/bin/jar" ] || { log "[h10] no javap / jar in $CE_HOME: no inline lists"; return 0; }
  rm -rf "$work"; mkdir -p "$work/classes"
  if ! ( cd "$work/classes" && "$CE_HOME/bin/jar" xf "$mjar" rlvm/machine ) > "$work/log.txt" 2>&1; then
    log "[h10] could not unpack $mjar (log: $work/log.txt): no inline lists"; return 0
  fi
  if ! line="$(JAVA_HOME="$CE_HOME" PATH="$CE_HOME/bin:$PATH" "$script" "$work/classes" 2>> "$work/log.txt")"; then
    log "[h10] $script failed (log: $work/log.txt): no inline lists"; return 0
  fi
  case "$line" in *-H:PriorityForceInline=rlvm.machine.*) ;; *) log "[h10] the script printed no ForceInline list: no inline lists"; return 0 ;; esac
  read -r -a ni_h10 <<< "$line"
  # The script unlocks the experimental options; lock them again behind the two lists.
  ni_h10+=(-H:-UnlockExperimentalVMOptions)
  printf '%s\n' "${ni_h10[@]}" > "$work/args.txt"
  log "[h10] inline lists from $(basename "$mjar"): $(brief_flags "${ni_h10[*]}")"
}

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
              -H:+ReportExceptionStackTraces)
  # The memory settings of the executable (IMAGE_HEAP at the top of this script).
  if [ "$variant" = oracle ]; then args+=("${IMAGE_HEAP_ORACLE[@]}"); else args+=("${IMAGE_HEAP[@]}"); fi
  [ -z "$march" ] || args+=("-march=$march")
  args+=("${ni_extra[@]}" "$@")
  # The words for logs and BUILD-INFO.txt: the arguments so far (none of them contains a blank).
  local shown="${args[*]}"
  # What `rlvm --version` prints as "build:" (rlvm.cli.BuildInfo is initialised at image build time,
  # so the property of the BUILD becomes a constant of the image). The text has blanks: one argument.
  [ -z "${build_text:-}" ] || args+=("-Drlvm.build=$build_text")
  mkdir -p "$dir/ni-$name"
  tmp="$dir/ni-$name/time.txt"
  log "[$variant] native-image $name: $(brief_flags "$shown")"
  local timer=() t0
  # GNU time gives CPU time and peak memory of the builder; without it only the wall time is recorded.
  if [ -n "$GNU_TIME" ]; then timer=("$GNU_TIME" -o "$tmp" -f '%e %U %S %M'); fi
  t0="$(now)"
  ( cd "$dir/ni-$name" && "${timer[@]}" \
      "$ghome/bin/native-image" "${args[@]}" -cp "$cp_" -o "$dir/$name" rlvm.cli.Main > build.log 2>&1 ) \
    || { grep -m 20 -iE '^Error|error:|Caused by' "$dir/ni-$name/build.log" >&2 || tail -n 40 "$dir/ni-$name/build.log" >&2
         # A caller that has a second way to build (the image without the H10 lists) asks for a status.
         if [ "$ni_soft_fail" = 1 ]; then cp "$dir/ni-$name/build.log" "$dir/ni-$name/build-failed.log"; return 1; fi
         die "[$variant] native-image failed (log: $dir/ni-$name/build.log)"; }
  if [ -n "$GNU_TIME" ]; then
    read -r ni_wall ni_user ni_sys ni_rss < <(tail -n 1 "$tmp")
  else
    ni_wall="$(since "$t0")"; ni_user=0; ni_sys=0; ni_rss=0
  fi
  ni_cpu="$(awk -v u="$ni_user" -v s="$ni_sys" 'BEGIN { printf "%.1f", u + s }')"
  ni_rss_mb=$((ni_rss / 1024))
  ni_size="$(stat -c %s "$dir/$name")"
  ni_flags="$(brief_flags "$shown")"
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

# One compiler per build: GraalVM CE (the default), or Oracle GraalVM for --oracle-pgo. The modules are
# compiled against the Truffle version of the GraalVM that builds the image.
if [ "$flavor" = oracle-pgo ]; then
  variant=oracle; img_home="$ORACLE_HOME_DIR"; img_label="Oracle GraalVM"
  img_version="$(graalvm_version "$ORACLE_HOME_DIR")"
  [ -n "$img_version" ] || die "cannot read GRAALVM_VERSION from $ORACLE_HOME_DIR/release"
else
  variant=ce; img_home="$CE_HOME"; img_label="GraalVM CE"; img_version="$ce_version"
fi
maven_build "$variant" "$img_home" "$img_version"
LIB="$BUILD/$variant/lib"   # the jars of this build: the class path of the image, lib/ of a development distribution
if [ "$print_deps" = 1 ]; then deps_list "$LIB"; exit 0; fi
if [ -n "$verify_deps" ]; then
  deps_list "$LIB" > "$BUILD/$variant/deps.sha256"
  if ! diff -u <(grep -vE '^(#|[[:space:]]*$)' "$verify_deps") "$BUILD/$variant/deps.sha256" >&2; then
    die "third-party jars differ from the pinned list $verify_deps"
  fi
  log "[$variant] third-party jars match $verify_deps"
fi
jars_bytes="$(du -cb "$LIB"/*.jar | tail -n 1 | cut -f1)"
if [ "$want_native" = 0 ]; then layout="development, JVM launcher only: lib/*.jar, bin/rlvm-jvm"
elif [ "$dev" = 1 ]; then layout="development: bin/rlvm, lib/*.jar, bin/rlvm-jvm"
else layout="release: one executable, bin/rlvm"; fi

{
  echo "RLVM distribution"
  echo "built:            $stamp on $(uname -sm) by scripts/build.sh"
  echo "layout:           $layout"
  echo "modules:          $modules$([ "$published" = 1 ] && echo ' (compiled); all other RLVM modules as published in .toolchain/m2:')"
  [ "$published" = 0 ] || cat "$BUILD/$variant/published.txt"
  echo "java.home:        $img_home"
  echo "graalvm:          $img_label $img_version ($(sed -n 's/^JAVA_RUNTIME_VERSION="\(.*\)"$/\1/p' "$img_home/release"))$([ "$flavor" = oracle-pgo ] && echo ' - GFTC licence; an explicitly requested build (--oracle-pgo), never the default')"
  echo "maven build:      ${maven_secs}s"
  echo "jars:             $jars_bytes bytes in $(ls "$LIB" | wc -l) files$([ "$dev" = 1 ] && echo ' (lib/)' || echo ' (compiled into the executable; not part of this layout)')"
  echo "jars sha256:      $(cat "$LIB"/rlvm-*.jar | sha256sum | cut -c1-12) (over the RLVM jars in name order: the id of the build; bench/warm.py reads it)"
} > "$INFO"
record "jars" "maven package ($modules)" "$maven_secs" "" "" "$jars_bytes"

# The engine a run without -X engine checks with, as the jars of this build say it ("default engine:
# NAME" in `rlvm --version`: the engines' priorities decide, rlvm-main).
default_engine="$("$img_home/bin/java" -Xmx256m -XX:TieredStopAtLevel=1 -cp "$(classpath_of "$LIB")" rlvm.cli.Main --version 2>/dev/null \
    | sed -n 's/.*default engine: \([A-Za-z0-9_.-]*\).*/\1/p' | head -n 1 || true)"
log "default engine of this build: ${default_engine:-unknown (no rlvm-main in the build, or one that does not say)}"
echo "default engine:   ${default_engine:-unknown} (a run without -X engine; -X policy=strict = the reference engine)" >> "$INFO"

# --- JVM launcher (development distributions only) ----------------------------------------------
# bin/rlvm-jvm is a TOOL OF THIS REPOSITORY, not part of the release (OWNER-NOTES 16: the release is
# one executable): the warm benchmarks (bench/warm.sh; warm, HotSpot needs 0.74-0.81 times the image's
# wall clock), the gates and the test kit run the jars through it. It is written only into a --dev or
# --jvm-only distribution and never packaged.
# Default VM flags. The line is the one proposed by the profiler J1 (docs/research/profiles/J1.md, section 5;
# prototype work/j1/proto-src/rlvm-jvm.in) and re-measured on this script's launcher (docs/PLATFORM.md):
#   -XX:+UseSerialGC             no collector beats it on the real checker with parallel workers
#                                (docs/research/profiles/GC.md: Parallel and G1 1.00-1.03 times its CPU
#                                time, ZGC 1.04-1.07, Shenandoah 1.15-1.26; no collector improves the
#                                8-worker wall clock; smallest resident set)
#   -Xms13g -Xmx13g -Xmn1g       the heap of every engine but the machine (see "Heap BY ENGINE" below):
#                                fixed heap and nursery, no resizing (ARCHITECTURE.md 7.2: the working set
#                                is primitive arrays in the old generation, only big-number garbage fills
#                                the nursery; 13 GB leaves 3 GB of the runner's 16 GB for stacks, code and
#                                the page cache; the memory account declines at 12 GiB, before the heap
#                                limit). Was 8g / 8g / 3g, sized for an object working set. Dropped when
#                                the caller passes its own -Xmx/-Xms/-Xmn. Nothing is pre-touched, so a
#                                small test costs no more memory than before
#   -XX:-UseJVMCICompiler        C2 instead of Graal as the *host* JIT: same steady state (J1.md 2.2: 1.98 s
#                                against 1.99-2.10 s on cedar with 8 workers), 400 MB less resident memory.
#                                Truffle runtime compilation still uses libgraal
#   -XX:CICompilerCount=8        compiler threads (a cap: they are created on demand). The JVM launcher is
#                                the WARM platform (OWNER-NOTES 12: warm-up is reported, not charged): with
#                                the old value 2 a tiered VM has ONE C2 thread beside eight workers and
#                                needs five or six passes to reach its steady state; with 8 the third pass
#                                is steady and the cold wall clock below mathlib nearly halves (J1.md 3.2:
#                                Init 4.1-5.3 -> 2.8-3.6 s, Std 5.5 -> 3.1, cedar 8.5-9.9 -> 5.2-5.8), the
#                                steady state unchanged. Was 2 ("compiler threads are billed": the arena's
#                                metric, which this launcher does not serve)
#   -XX:+UseTransparentHugePages the one flag with a consistent steady-state gain (J1.md 2.2: mathlib -6 %
#                                warm wall in an A B A B, reachability -2 to -4 %): TLB reach for a store of
#                                gigabytes. Takes effect where the host's setting is madvise or always;
#                                with never the VM prints one warning (on standard error) and runs as before
#   -Xlog:disable -Xlog:all=warning:stderr   the VM's own messages (huge pages unavailable, ...) go to
#                                standard error; unified logging prints them on standard OUTPUT by default
#   -XX:+ExitOnOutOfMemoryError  the VM exits with status 3 (= internal error) on the first OOM
#   --enable-native-access / --sun-misc-unsafe-memory-access   silence JDK 25 warnings caused by Truffle
#   NOT there: the JDK 25 AOT cache (J1.md 3.3: 0.4-1.1 s off the cold wall, every later pass 5-9 % slower)
# Heap BY ENGINE (J1.md section 4). The engine of a run is what -X engine=NAME (or -Xengine=NAME) on its
# command line says; without it, the distribution's DEFAULT engine, which this script asks the jars for
# ("default engine: NAME" in `rlvm --version`: the engines' priorities decide, rlvm-main) and writes into
# the launcher - so a distribution whose default engine is the machine gets the machine's heap by itself;
# -X policy=strict is the reference engine (OWNER-NOTES 13).
#   machine                      -Xmx7g -Xmn1g, no -Xms: the same warm wall clock on mathlib as 13 GB
#                                (23.9-24.2 s), 5.7-6.7 GB resident instead of 8.0-8.7 GB; 7 GB is the
#                                smallest cap at which the machine decides every unit it decides at 13 GB
#                                (6 GB once finding J1-F1, the machine's young reserve, is fixed); all 230
#                                published tests pass under it (peak 3.3 GB)
#   anything else                -Xms13g -Xmx13g -Xmn1g as before (the reference engine: 13.4 GB resident
#                                on mathlib), also for -X engine=diff (two engines in one process)
# The class path is spelled out, jar by jar, in the shell's sorted order: "lib/*" is expanded by the VM in
# directory order, which differs between file systems (and with it the order of ServiceLoader providers).
JVM_FLAGS="-XX:+UseSerialGC -XX:-UseJVMCICompiler -XX:CICompilerCount=8 -XX:+UseTransparentHugePages -Xlog:disable -Xlog:all=warning:stderr -XX:+ExitOnOutOfMemoryError --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow"
JVM_HEAP_FLAGS="-Xms13g -Xmx13g -Xmn1g"
JVM_HEAP_FLAGS_MACHINE="-Xmx7g -Xmn1g"
if [ "$dev" = 1 ]; then
mkdir -p "$STAGE/dist/lib"
cp "$LIB"/*.jar "$STAGE/dist/lib/"
cat > "$STAGE/dist/bin/rlvm-jvm" <<LAUNCHER
#!/bin/sh
# RLVM on the JVM: a DEVELOPMENT launcher (warm benchmarks, gates, test kit); the release is the one
# executable bin/rlvm. Generated by scripts/build.sh on $stamp; do not edit.
#
#   rlvm-jvm [-J<jvm flag>]... [rlvm options] [EXPORT.ndjson]        (export also via \$IN)
#
# Like the native image, it takes -Xmx/-Xms/-Xmn/-Xss, -XX:... and -D... for the VM itself; giving a
# heap size (-Xmx/-Xms/-Xmn) drops the default heap flags.
# Default heap by engine: machine = $JVM_HEAP_FLAGS_MACHINE; any other engine = $JVM_HEAP_FLAGS.
# The engine is -X engine=NAME; without it this distribution's default engine (${default_engine:-unknown});
# -X policy=strict = the reference engine.
# Environment: RLVM_JAVA (java binary), RLVM_JAVA_OPTS (replaces all default VM flags below, the heap
# included), RLVM_EXTRA_CP (class path entries appended after the jars of lib/: development plug-ins
# such as the test kit's differential engine; nothing of it is part of the distribution).
here="\$(cd "\$(dirname "\$0")" && pwd -P)"
java="\${RLVM_JAVA:-}"
if [ -z "\$java" ]; then
  if [ -x "$CE_HOME/bin/java" ]; then java="$CE_HOME/bin/java"; else java=java; fi
fi
user=""
userheap=0
engine=""
policy=""
prev=""
n=\$#
while [ "\$n" -gt 0 ]; do
  a="\$1"; shift; n=\$((n - 1))
  case "\$a" in
    -J-Xmx*|-J-Xms*|-J-Xmn*) user="\$user \${a#-J}"; userheap=1 ;;
    -J*) user="\$user \${a#-J}" ;;
    -Xmx[0-9]*|-Xms[0-9]*|-Xmn[0-9]*) user="\$user \$a"; userheap=1 ;;
    -Xss[0-9]*|-XX:*|-D*) user="\$user \$a" ;;
    *)
      # The engine of the run, for the default heap: -X engine=NAME or -Xengine=NAME, and the policy
      # (the last one wins, as in the checker).
      case "\$a" in
        -Xengine=*) engine="\${a#-Xengine=}" ;;
        -Xpolicy=*) policy="\${a#-Xpolicy=}" ;;
        engine=*) if [ "\$prev" = "-X" ]; then engine="\${a#engine=}"; fi ;;
        policy=*) if [ "\$prev" = "-X" ]; then policy="\${a#policy=}"; fi ;;
      esac
      set -- "\$@" "\$a" ;;
  esac
  prev="\$a"
done
[ -n "\$engine" ] || engine="$default_engine"
[ "\$policy" != strict ] || engine=reference
if [ "\$userheap" = 1 ]; then heap=""
elif [ "\$engine" = machine ]; then heap="$JVM_HEAP_FLAGS_MACHINE"
else heap="$JVM_HEAP_FLAGS"; fi
cp=""
for j in "\$here"/../lib/*.jar; do cp="\$cp\${cp:+:}\$j"; done
if [ -n "\${RLVM_JAVA_OPTS:-}" ]; then flags="\$RLVM_JAVA_OPTS"; else flags="$JVM_FLAGS \$heap"; fi
# shellcheck disable=SC2086
exec "\$java" \$flags \$user -cp "\$cp\${RLVM_EXTRA_CP:+:\$RLVM_EXTRA_CP}" rlvm.cli.Main "\$@"
LAUNCHER
chmod +x "$STAGE/dist/bin/rlvm-jvm"
echo "jvm launcher:     bin/rlvm-jvm (development tool, not part of a release): $JVM_FLAGS + heap by engine: machine $JVM_HEAP_FLAGS_MACHINE; any other $JVM_HEAP_FLAGS" >> "$INFO"
verify "$STAGE/dist/bin/rlvm-jvm" "rlvm-jvm"
fi

# --- the executable -----------------------------------------------------------------------------
# Settings that came through --ni-arg (a later -R: value wins over IMAGE_HEAP; -H: options change how
# the image is built): named in the text of --version so that it stays true. Arguments with a path
# (library directories) and the switches that unlock experimental options are not shown.
extra_r=""
for x in "${ni_extra[@]}"; do
  case "$x" in
    */*|-H:+UnlockExperimentalVMOptions|-H:-UnlockExperimentalVMOptions) ;;
    -R:*|-H:*) extra_r="$extra_r ${x#-?:}" ;;
    *) extra_r="$extra_r $x" ;;
  esac
done
image_text() { # first words -> the text of "build:" in `rlvm --version`
  echo "$1 ($img_label $img_version, -O$opt, $gc collector, $IMAGE_HEAP_TEXT${extra_r:+; also:$extra_r})"
}

if [ "$want_native" = 1 ] && [ "$flavor" = ce ]; then
  build_text="$(image_text "GraalVM CE native image")"
  [ "$h10" = no ] || h10_lists "$LIB"
  if [ "${#ni_h10[@]}" -gt 0 ]; then
    ni_soft_fail=1
    if ! native_build ce "$CE_HOME" rlvm "${ni_h10[@]}"; then
      log "[h10] WARNING: native-image failed with the inline lists (log: $BUILD/ce/ni-rlvm/build-failed.log); building the image without them"
      ni_h10=()
      ni_soft_fail=0
      native_build ce "$CE_HOME" rlvm
    fi
    ni_soft_fail=0
  else
    native_build ce "$CE_HOME" rlvm
  fi
  verify "$BUILD/ce/rlvm" "rlvm (native, CE)"
  cp "$BUILD/ce/rlvm" "$STAGE/dist/bin/rlvm"
  {
    echo "executable:       bin/rlvm  $ni_size bytes; GraalVM CE native image; built in ${ni_wall}s wall, ${ni_cpu}s cpu, peak ${ni_rss_mb} MB"
    echo "  says:           $build_text"
    echo "  flags:          $ni_flags"
    echo "  inline lists:   $([ "${#ni_h10[@]}" -gt 0 ] && echo "H10, generated from the machine jar of this build (rlvm-machine/h10/native-image-args.sh)" || echo "none")"
    echo "  builder:        $ni_summary"
    echo "  truffle probe:  $probe_line"
  } >> "$INFO"
  record "rlvm (native CE $ce_version)" "$ni_flags" "$ni_wall" "$ni_cpu" "$ni_rss_mb" "$ni_size"
fi

# --- the executable, Oracle GraalVM + shipped profiles (--oracle-pgo only) ----------------------
if [ "$flavor" = oracle-pgo ]; then
  prof="$BUILD/oracle/profiles"
  rm -rf "$prof"; mkdir -p "$prof"
  if [ "$train_profiles" = 1 ]; then
    # Regenerate the profiles of the repository: instrumented image, training runs, profiles/.
    build_text="Oracle GraalVM, instrumented for profiling: NOT a release ($img_label $img_version)"
    native_build oracle "$ORACLE_HOME_DIR" rlvm-instr --pgo-instrument
    instr_wall="$ni_wall"; instr_size="$ni_size"
    record "rlvm-instr (Oracle $img_version, instrumented)" "$ni_flags" "$ni_wall" "$ni_cpu" "$ni_rss_mb" "$ni_size"
    t0="$(now)"
    training=("--truffle-probe -> probe.iprof.gz")
    RLVM_EXIT=exit "$BUILD/oracle/rlvm-instr" "-XX:ProfilesDumpFile=$prof/probe.iprof" --truffle-probe > "$prof/probe.log" 2>&1 \
      || { cat "$prof/probe.log" >&2; die "[oracle] training run --truffle-probe failed"; }
    # train_one <export> <file name of the profile> <arguments...>
    train_one() {
      local f="$1" n="$2" rc=0 t1; shift 2
      [ ! -e "$prof/$n.iprof" ] || die "[oracle] two training runs are called $n"
      t1="$(now)"
      RLVM_EXIT=exit "$BUILD/oracle/rlvm-instr" "-XX:ProfilesDumpFile=$prof/$n.iprof" --stats "$f" "$@" > "$prof/$n.log" 2>&1 || rc=$?
      # A training run may accept, reject or decline; anything else means the profile is not to be trusted.
      case "$rc" in 0|1|2) ;; *) cat "$prof/$n.log" >&2; die "[oracle] training run on $f ($*) ended with status $rc" ;; esac
      [ -s "$prof/$n.iprof" ] || die "[oracle] the training run on $f wrote no profile"
      training+=("$(basename "$f") ($(stat -Lc %s "$f") bytes, sha256 $(sha256sum "$f" | cut -c1-16)), arguments: ${*:-none (the default configuration)}; exit $rc, $(since "$t1") s instrumented -> $n.iprof.gz")
      log "[oracle] trained on $f ${*:+with $* }(exit $rc)"
    }
    for f in "${train[@]}"; do
      n="train-$(basename "$f" .ndjson | tr -c 'A-Za-z0-9._\n-' '_')"
      train_one "$f" "$n" "${train_args[@]}"
      if [ -n "$train_also" ]; then
        # shellcheck disable=SC2086
        train_one "$f" "$n-also" "${train_args[@]}" $train_also
      fi
    done
    train_secs="$(since "$t0")"
    log "[oracle] training took ${train_secs}s: $(ls "$prof"/*.iprof | wc -l) profiles, $(du -cb "$prof"/*.iprof | tail -n 1 | cut -f1) bytes"
    # Into the repository: compressed (a profile is JSON: about a seventh of its size), with the text
    # file that says what made them. No host path goes into either.
    mkdir -p "$profiles_dir"
    rm -f "$profiles_dir"/*.iprof.gz
    for p in "$prof"/*.iprof; do gzip -9 -n -c "$p" > "$profiles_dir/$(basename "$p").gz"; done
    profile_id="$(cat "$profiles_dir"/*.iprof.gz | sha256sum | cut -c1-12)"
    {
      echo "RLVM profiles for 'scripts/build.sh --oracle-pgo' (profile-guided optimisation with Oracle GraalVM)."
      echo "Made by 'scripts/build.sh --oracle-pgo --train-profiles' (scripts/dev.sh profiles); do not edit."
      echo
      echo "id:        $profile_id"
      echo "made:      $stamp on $(uname -sm)"
      echo "compiler:  Oracle GraalVM $img_version ($(sed -n 's/^JAVA_RUNTIME_VERSION="\(.*\)"$/\1/p' "$ORACLE_HOME_DIR/release")), native-image --pgo-instrument, -O$opt, $gc collector"
      echo "settings:  the default configuration of the instrumented executable (default engine: ${default_engine:-unknown})${train_args[*]:+; training arguments of every run: ${train_args[*]}}${train_also:+; every export a second time with: $train_also}"
      echo "sources:   the RLVM jars of the build (compiled against Truffle $img_version, the version of this compiler), sha256 prefixes:"
      ( cd "$LIB" && for j in rlvm-*.jar; do printf '             %-18s %s\n' "${j%-[0-9]*}" "$(sha256sum "$j" | cut -c1-12)"; done )
      echo "training:  one run of the instrumented executable each, RLVM_EXIT=exit:"
      for t in "${training[@]}"; do echo "             $t"; done
      echo "files:     sha256, bytes, name"
      ( cd "$profiles_dir" && for p in *.iprof.gz; do printf '             %s %9s %s\n' "$(sha256sum "$p" | cut -c1-16)" "$(stat -c %s "$p")" "$p"; done )
    } > "$profiles_dir/PROFILE-INFO.txt"
    log "[oracle] profiles written to $profiles_dir (id $profile_id)"
  else
    for p in "$profiles_dir"/*.iprof.gz; do gzip -dc "$p" > "$prof/$(basename "$p" .gz)" || die "[oracle] cannot unpack $p"; done
    profile_id="$(sed -n 's/^id: *//p' "$profiles_dir/PROFILE-INFO.txt" 2>/dev/null | head -n 1)"
    [ "$profile_id" = "$(cat "$profiles_dir"/*.iprof.gz | sha256sum | cut -c1-12)" ] \
      || log "[oracle] WARNING: $profiles_dir/PROFILE-INFO.txt does not describe these profile files (id ${profile_id:-missing})"
    profile_id="$(cat "$profiles_dir"/*.iprof.gz | sha256sum | cut -c1-12)"
  fi
  # STALENESS GUARD: the profiles were recorded with the jars that profiles/PROFILE-INFO.txt lists; if
  # the jars of this build differ, the profile is stale. A stale profile still helps (measured, report
  # PG1: hours-old profiles keep a half to three quarters of the gain and are never slower than the CE
  # executable), so the build goes on, but it says so loudly, in BUILD-INFO.txt and in `rlvm --version`.
  # `--train-profiles` (scripts/dev.sh profiles) makes fresh ones.
  profile_stale=""
  if [ -f "$profiles_dir/PROFILE-INFO.txt" ]; then
    n_all=0; n_diff=0; names=""
    while read -r m h; do
      j="$(ls "$LIB/$m"-[0-9]*.jar 2>/dev/null | head -n 1)"
      n_all=$((n_all + 1))
      if [ -z "$j" ] || [ "$(sha256sum "$j" | cut -c1-12)" != "$h" ]; then n_diff=$((n_diff + 1)); names="$names ${m#rlvm-}"; fi
    done < <(sed -n 's/^ *\(rlvm-[a-z]*\)  *\([0-9a-f]\{12\}\) *$/\1 \2/p' "$profiles_dir/PROFILE-INFO.txt")
    if [ "$n_all" = 0 ]; then
      profile_stale="it is not known which jars the profiles were recorded with"
    elif [ "$n_diff" -gt 0 ]; then
      profile_stale="profile stale: $n_diff of $n_all jars differ from the ones it was recorded with (${names# })"
    fi
  else
    profile_stale="it is not known which jars the profiles were recorded with (no PROFILE-INFO.txt)"
  fi
  if [ -n "$profile_stale" ]; then
    echo "build.sh: ====== WARNING: $profile_stale. The build goes on with the stale profile (it keeps part of its gain); 'scripts/dev.sh profiles' records a fresh one. ======" >&2
  else
    log "[oracle] the profiles were recorded with exactly the jars of this build"
  fi
  profiles="$(ls "$prof"/*.iprof | LC_ALL=C sort | paste -sd, -)"
  build_text="$(image_text "Oracle GraalVM, profile-guided native image") [profiles $profile_id${profile_stale:+; ${profile_stale%% (*}}]"
  native_build oracle "$ORACLE_HOME_DIR" rlvm "--pgo=$profiles"
  verify "$BUILD/oracle/rlvm" "rlvm (native, Oracle GraalVM, profile-guided)"
  "$BUILD/oracle/rlvm" --version | grep -q '^build: Oracle GraalVM, profile-guided' \
    || log "[oracle] NOTE: 'rlvm --version' does not say 'Oracle GraalVM, profile-guided' (a launcher jar from before 2026-11-25 has no build line)"
  cp "$BUILD/oracle/rlvm" "$STAGE/dist/bin/rlvm"
  {
    echo "executable:       bin/rlvm  $ni_size bytes; ORACLE GraalVM native image, profile-guided; built in ${ni_wall}s wall, ${ni_cpu}s cpu, peak ${ni_rss_mb} MB"
    echo "  licence:        Oracle GraalVM $img_version is under the GraalVM Free Terms and Conditions (GFTC); this build was asked for with --oracle-pgo"
    echo "  says:           $build_text"
    echo "  flags:          $(echo "$ni_flags" | sed "s:$prof/::g")"
    echo "  profile state:  ${profile_stale:-fresh: recorded with exactly the jars of this build}"
    echo "  profiles:       $(ls "$profiles_dir"/*.iprof.gz | wc -l) files of $(realpath --relative-to="$ROOT" "$profiles_dir" 2>/dev/null || echo "$profiles_dir"), id $profile_id$([ "$train_profiles" = 1 ] && echo " (made by this build: instrumented image in ${instr_wall}s, $instr_size bytes; training ${train_secs}s)")"
    echo "  builder:        $ni_summary"
    echo "  truffle probe:  $probe_line"
  } >> "$INFO"
  record "rlvm (Oracle $img_version, PGO)" "$(echo "$ni_flags" | sed "s:$prof/::g")" "$ni_wall" "$ni_cpu" "$ni_rss_mb" "$ni_size"
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
  version="$(date -u +%Y%m%dT%H%M%SZ)-$(cat "$LIB"/rlvm-*.jar | sha256sum | cut -c1-12)-$([ -e "$STAGE/dist/bin/rlvm" ] && echo native || echo jvm)"
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
