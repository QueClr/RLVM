#!/usr/bin/env bash
# scripts/dev.sh - one entry point for developers and agents.
#
#   scripts/dev.sh <command> [arguments]
#
# Maven, in the working tree, one module at a time (never touches another module's target/):
#   build MODULE              compile the module                        mvn -o -q -pl MODULE compile
#   test MODULE [TEST]        run its unit tests; TEST = Class or Class#method (surefire -Dtest)
#   install MODULE            put the module's jar (and the parent pom) into the local repository
#                             .toolchain/m2, so that modules depending on it can be built with
#                             `build`/`test` on their own. Run it when your module's API changes.
#   modules                   list the modules of the parent pom
#
# Building (from a snapshot of the sources in work/build/, safe while others work). THE RELEASE IS ONE
# EXECUTABLE, bin/rlvm (the GraalVM CE native image; OWNER-NOTES 16): plain `scripts/build.sh` builds
# exactly that. The commands below build DEVELOPMENT distributions, which add lib/*.jar and
# bin/rlvm-jvm, the JVM launcher that the gates, the warm benchmarks and the test kit run:
#   dist [options]            the shared development distribution work/dist/ WITHOUT the executable:
#                             lib/*.jar and bin/rlvm-jvm, built from the PUBLISHED jars
#                             (scripts/build.sh --published --jvm-only: rlvm-main is compiled from
#                             the tree and every other RLVM module is the jar its owner last installed
#                             into .toolchain/m2), a few seconds. The default ENGINE is decided by the
#                             engines' priorities: `rlvm --version` prints it. Options are passed on
#                             to build.sh. work/dist is SHARED and VERSIONED: each build is a new
#                             directory work/dist.d/<UTC time>-<jars>-<jvm|native> and work/dist is a
#                             symbolic link switched to it atomically; gate / slice / track / platform
#                             pin the version at their start, old versions go after 90 minutes
#                             (build.sh header). Copy it with `cp -aL work/dist DIR`
#   dist-native [options]     the same plus the executable bin/rlvm (CE native image at -O3 with the
#                             machine's inline lists; 2-3 minutes, about 900 s of CPU):
#                             scripts/build.sh --published --dev; `--opt 2` builds in half the time
#                             and runs 10-18 % slower. Holds one of the host's two native-image slots
#                             (bench/slot.sh -c ni -n 2), as dist-tree, release and profiles do
#   dist-versions             list the versions in work/dist.d/ and the one work/dist names
#   dist-tree [options]       a development distribution with everything compiled from the working
#                             tree: scripts/build.sh --dev (it fails if any module of the tree does
#                             not compile at the moment)
#   dist-quick [options]      scripts/build.sh --jvm-only from the working tree
#   release [options]         THE RELEASE: scripts/package.sh --out work/release --build [options]
#                             = the one executable built from the working tree into
#                             work/release/dist/bin/rlvm (GraalVM CE) and the archive
#                             work/release/rlvm-<version>-linux-<arch>.tar.gz with exactly rlvm,
#                             LICENSE and README.md. Options go to build.sh (--online, --published)
#   release-oracle [options]  the OPTIONAL build with ORACLE GraalVM (GFTC licence; never a default)
#                             and the profiles shipped in profiles/: scripts/build.sh --oracle-pgo
#                             --dist work/dist-oracle-pgo [options]. Needs .toolchain/oracle-graalvm-25
#                             (or --accept-oracle-licence to download the pinned one)
#   profiles [--train FILE]... [options]
#                             regenerate profiles/ for release-oracle: scripts/build.sh --oracle-pgo
#                             --train-profiles (instrumented image, one training run per input in the
#                             default configuration, profiles/*.iprof.gz + profiles/PROFILE-INFO.txt).
#                             Default training inputs: work/exports/init.ndjson (see the journal,
#                             design-packaging: what the measurements of the profile found best)
#   info                      print work/dist/BUILD-INFO.txt and the toolchain versions
#
# Test kit (rlvm-testkit/bin/testkit; see rlvm-testkit/README.md):
#   oracle | verdict | mutate | corpus | judge [args]
#                             the official verdict cache, mutants of an export, the mutant corpus
#                             and its judgment of a checker command
#   testkit ARGS...           any other testkit command
#   diff [options] EXPORT|DIR...
#                             rlvm-testkit/bin/testkit diff: the differential campaign through the
#                             real launcher and driver (engine under test against the reference
#                             kernel on every unit; report; exit status). --engine NAME, --cp PATH
#   diff-launcher EXPORT [rlvm options]
#                             one differential run of the JVM launcher: -X engine=diff with the test
#                             kit's DiffProvider (rlvm-testkit/spi + its published jar) added to the
#                             class path through RLVM_EXTRA_CP (the caller's RLVM_EXTRA_CP is kept,
#                             e.g. an engine that is not in the distribution); never part of what is
#                             released. Without the test kit, `rlvm -X engine=diff` uses the
#                             driver's own differential mode (needs a second engine on the class path)
#   track CORPUS [args]       bench/track/track.py: one measured run of a corpus in the arena-shaped
#                             container, history in work/perf-history/, comparison with the baselines
#
# Steward (integration) commands:
#   steward-install MODULE... scripts/steward-install.sh: test a frozen copy of the module's sources
#                             and install the jar built from that copy
#   slice [options]           scripts/slice.sh: the end-to-end table of the published tests
#                             (default: tutorial, reference engine, one worker) in work/status/slice.md
#   gate [options]            scripts/gate.sh: the end-to-end guards in one command - distribution,
#                             all 230 published tests, mutant campaign(s); `--dist DIR` guards a
#                             distribution built before publication; `--defaults` runs it exactly
#                             as a user does (default engine, automatic workers, default load:
#                             the gate of a change of a default); `--hostile` adds the hostile
#                             exports, judged by their official verdicts (scripts/gate.sh --help)
#   machine ARGS...           scripts/machine-sandbox.sh (the machine workflow's script), passed through
#   platform ...              scripts/platform-matrix.py: launcher / JIT / collector / heap matrix in
#                             the arena-shaped container (docs/PLATFORM.md); `platform-report` prints
#                             its tables
#
# Running and measuring the built checker (see the headers of bench/rlvm/*.sh):
#   run [options] EXPORT      bench/rlvm/run.sh: one export, exactly as the arena runs it
#   selftest [--native|--jvm|--pgo]
#                             bench/rlvm/run.sh --selftest for one launcher, or for every launcher
#                             present in work/dist: exit-status protocol and Truffle probe
#   suite [options]           bench/rlvm/suite.sh: the 230 published arena tests, on the host
#                             (verdicts, time) or with --docker (instruction counts)
#   measure [options] EXPORT  bench/rlvm/measure.sh: instructions / CPU / wall / RSS in the
#                             arena-shaped container for 1, 4 and 8 worker threads
#   arena-test                build and run the arena checker definition end to end in the
#                             container, from a clean copy of the sources (downloads the toolchain)
#
# Environment: RLVM_MVN_ONLINE=1 lets Maven download (default: offline, -o).
# Exit status: that of the underlying tool.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
die() { echo "dev.sh: $*" >&2; exit 2; }
usage() { sed -n '2,104p' "$0" | sed 's/^# \{0,1\}//'; }

[ "$#" -gt 0 ] || { usage; exit 2; }
cmd="$1"; shift

modules() { sed -n 's:.*<module>\(.*\)</module>.*:\1:p' "$ROOT/pom.xml"; }

need_module() {
  [ "$#" -ge 1 ] || die "$cmd needs a module: $(modules | paste -sd' ' -)"
  modules | grep -qx "$1" || die "unknown module '$1' (modules: $(modules | paste -sd' ' -))"
}

mvn() {
  # shellcheck source=../env.sh
  . "$ROOT/env.sh"
  local offline=(-o)
  [ "${RLVM_MVN_ONLINE:-0}" != 1 ] || offline=()
  cd "$ROOT"
  # shellcheck disable=SC2086
  $MVN "${offline[@]}" -q "$@"
}

# Host rule of 2026-11-24 (orchestrator, work/status/requests.log 14:32Z): at most two native-image
# builds at a time on the whole host - `bench/slot.sh -c ni -n 2 -- BUILD`. dist-native, dist-tree,
# release, release-oracle and profiles take that slot themselves unless the caller already holds one (an inherited descriptor on
# work/build/slots/ni.N) or RLVM_NO_SLOT is set. A direct `scripts/build.sh` call is the caller's to wrap.
slot=()
ni_slot() {
  if [ -x "$ROOT/bench/slot.sh" ] && [ -z "${RLVM_NO_SLOT:-}" ] \
      && ! ls -l "/proc/$$/fd" 2>/dev/null | grep -q 'work/build/slots/ni\.'; then
    slot=("$ROOT/bench/slot.sh" -c ni -n 2 --)
  fi
}

# The default training inputs of `profiles` (paths below the repository root; the exports are not part
# of the repository: bench/README.md says how to fetch the arena's corpora).
PROFILE_TRAINING="work/exports/init.ndjson"

# run / suite / measure default to the native image; when the distribution has only the JVM
# launcher (scripts/dev.sh dist) and the caller named no launcher, use that one.
default_mode() {
  local a
  for a in "$@"; do
    case "$a" in --native|--jvm|--pgo) return 0 ;; --) break ;; esac
  done
  if [ ! -x "$ROOT/work/dist/bin/rlvm" ] && [ -x "$ROOT/work/dist/bin/rlvm-jvm" ]; then echo "--jvm"; fi
}

case "$cmd" in
  build)
    need_module "$@"
    mvn -pl "$1" compile
    ;;
  test)
    need_module "$@"
    if [ "$#" -ge 2 ]; then
      mvn -pl "$1" test "-Dtest=$2" -Dsurefire.failIfNoSpecifiedTests=false
    else
      mvn -pl "$1" test
    fi
    ;;
  install)
    need_module "$@"
    # The parent pom first (non-recursive): a module resolved from the repository needs it there.
    mvn -N install
    mvn -pl "$1" install -Dmaven.test.skip=true
    ;;
  modules)
    modules
    ;;
  dist)
    exec "$ROOT/scripts/build.sh" --published --jvm-only "$@"
    ;;
  dist-native)
    ni_slot
    exec "${slot[@]}" "$ROOT/scripts/build.sh" --published --dev "$@"
    ;;
  dist-tree)
    ni_slot
    exec "${slot[@]}" "$ROOT/scripts/build.sh" --dev "$@"
    ;;
  release)
    ni_slot
    exec "${slot[@]}" "$ROOT/scripts/package.sh" --out "$ROOT/work/release" --build "$@"
    ;;
  release-oracle)
    ni_slot
    exec "${slot[@]}" "$ROOT/scripts/build.sh" --oracle-pgo --dist "$ROOT/work/dist-oracle-pgo" "$@"
    ;;
  profiles)
    # The training inputs: every --train FILE of the command line, else the default set.
    has_train=0
    for a in "$@"; do [ "$a" != --train ] || has_train=1; done
    train=()
    if [ "$has_train" = 0 ]; then
      for f in $PROFILE_TRAINING; do
        [ -f "$ROOT/$f" ] || die "profiles: the training input $f is missing (give --train FILE...)"
        train+=(--train "$ROOT/$f")
      done
    fi
    ni_slot
    exec "${slot[@]}" "$ROOT/scripts/build.sh" --oracle-pgo --train-profiles --dist "$ROOT/work/dist-oracle-pgo" "${train[@]}" "$@"
    ;;
  dist-versions)
    cur="$(readlink "$ROOT/work/dist" 2>/dev/null || true)"
    [ -d "$ROOT/work/dist.d" ] || { echo "work/dist.d: no versions yet (scripts/dev.sh dist)"; exit 0; }
    for v in $(ls -1t "$ROOT/work/dist.d"); do
      mark=" "; [ "dist.d/$v" != "$cur" ] || mark="*"
      printf '%s %-48s %6s  %s\n' "$mark" "$v" "$(du -sh "$ROOT/work/dist.d/$v" | cut -f1)" \
        "$(sed -n 's/^built: *//p' "$ROOT/work/dist.d/$v/BUILD-INFO.txt" 2>/dev/null | cut -d' ' -f1)"
    done
    [ -L "$ROOT/work/dist" ] || echo "(work/dist is not a link yet: the next 'scripts/dev.sh dist' converts it)"
    ;;
  oracle|verdict|mutate|corpus|judge)
    exec "$ROOT/rlvm-testkit/bin/testkit" "$cmd" "$@"
    ;;
  testkit)
    exec "$ROOT/rlvm-testkit/bin/testkit" "$@"
    ;;
  diff)
    # The test kit's differential campaign: several exports or directories, report, exit status.
    exec "$ROOT/rlvm-testkit/bin/testkit" diff "$@"
    ;;
  diff-launcher)
    [ "$#" -ge 1 ] || die "diff-launcher needs an export"
    [ -x "$ROOT/work/dist/bin/rlvm-jvm" ] || die "work/dist/bin/rlvm-jvm not built (a development distribution: scripts/dev.sh dist)"
    jar="$(ls "$ROOT"/.toolchain/m2/rlvm/rlvm-testkit/*/rlvm-testkit-*.jar 2>/dev/null | head -n 1)"
    [ -n "$jar" ] || die "rlvm-testkit is not published in .toolchain/m2"
    # The caller's own RLVM_EXTRA_CP (e.g. an engine that is not in the distribution yet:
    # RLVM_EXTRA_CP=rlvm-machine/target/classes scripts/dev.sh diff FILE) stays on the class path.
    export RLVM_EXTRA_CP="$ROOT/rlvm-testkit/spi:$jar${RLVM_EXTRA_CP:+:$RLVM_EXTRA_CP}"
    exec "$ROOT/work/dist/bin/rlvm-jvm" -X engine=diff "$@"
    ;;
  track)
    [ -x "$ROOT/bench/track/track.py" ] || die "bench/track/track.py does not exist yet (work package T2)"
    exec "$ROOT/bench/track/track.py" "$@"
    ;;
  steward-install)
    exec "$ROOT/scripts/steward-install.sh" "$@"
    ;;
  slice)
    exec "$ROOT/scripts/slice.sh" "$@"
    ;;
  gate)
    exec "$ROOT/scripts/gate.sh" "$@"
    ;;
  machine)
    # The machine workflow's own script (not the steward's): sandbox copies, fast tests, publication.
    [ -x "$ROOT/scripts/machine-sandbox.sh" ] || die "scripts/machine-sandbox.sh does not exist (it belongs to the machine workflow)"
    exec "$ROOT/scripts/machine-sandbox.sh" "$@"
    ;;
  platform)
    exec "$ROOT/scripts/platform-matrix.py" "$@"
    ;;
  platform-report)
    exec "$ROOT/scripts/platform-report.py" "$@"
    ;;
  dist-quick)
    exec "$ROOT/scripts/build.sh" --jvm-only "$@"
    ;;
  info)
    # shellcheck source=../env.sh
    . "$ROOT/env.sh"
    echo "root:    $ROOT"
    echo "java:    $("$JAVA_HOME/bin/java" -version 2>&1 | sed -n 2p)"
    echo "maven:   $($MVN -v 2>/dev/null | head -n 1)"
    # Oracle GraalVM is never run by a default command (OWNER-NOTES 11 and 16): its version is read
    # from the release file. Only the optional build `scripts/build.sh --oracle-pgo` uses it.
    if [ -r "$ROOT/.toolchain/oracle-graalvm-25/release" ]; then
      echo "oracle:  present, not used by any default build ($(sed -n 's/^GRAALVM_VERSION="\{0,1\}\([^"]*\)"\{0,1\}$/Oracle GraalVM \1/p' "$ROOT/.toolchain/oracle-graalvm-25/release"), GFTC licence; only scripts/build.sh --oracle-pgo)"
    else
      echo "oracle:  not installed (only scripts/build.sh --oracle-pgo needs it; it downloads it with --accept-oracle-licence)"
    fi
    echo "modules: $(modules | paste -sd' ' -)"
    if [ -f "$ROOT/work/dist/BUILD-INFO.txt" ]; then
      echo "--- work/dist/BUILD-INFO.txt"
      cat "$ROOT/work/dist/BUILD-INFO.txt"
    else
      echo "work/dist: not built (scripts/dev.sh dist)"
    fi
    ;;
  run)
    exec "$ROOT/bench/rlvm/run.sh" $(default_mode "$@") "$@"
    ;;
  selftest)
    if [ "$#" -gt 0 ]; then
      exec "$ROOT/bench/rlvm/run.sh" "$@" --selftest
    fi
    rc=0; any=0
    for m in native pgo jvm; do
      case "$m" in native) b=rlvm ;; pgo) b=rlvm-pgo ;; jvm) b=rlvm-jvm ;; esac
      [ -x "$ROOT/work/dist/bin/$b" ] || continue
      any=1
      "$ROOT/bench/rlvm/run.sh" "--$m" --selftest || rc=1
    done
    [ "$any" = 1 ] || die "nothing built in work/dist (scripts/dev.sh dist)"
    exit "$rc"
    ;;
  suite)
    exec "$ROOT/bench/rlvm/suite.sh" $(default_mode "$@") "$@"
    ;;
  measure)
    exec "$ROOT/bench/rlvm/measure.sh" $(default_mode "$@") "$@"
    ;;
  arena-test)
    exec "$ROOT/scripts/arena/test-in-docker.sh" "$@"
    ;;
  -h|--help|help)
    usage
    ;;
  *)
    die "unknown command '$cmd' (see scripts/dev.sh --help)"
    ;;
esac
