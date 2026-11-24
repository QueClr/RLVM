#!/usr/bin/env bash
# rlvm-machine/src/test/resources/m8/convcost.sh - CPU cost of the skeleton's two deviations from the
# measured host convention (NOTES-M6 S3, S4): the depth bound as the field m.hostDepth instead of the
# constant Op.HOST_DEPTH, and the masked TICK line instead of `--m.fuel < 0`.
#
#   convcost.sh micro [--rounds N] [--n DEPTH] [--docker]
#       the micro-benchmark rlvm.machine.M8ConvCost (toy judgments that differ in exactly those lines),
#       on the JVM with C2 (the launcher's JIT today: -XX:-UseJVMCICompiler) and with the Graal JIT;
#       --docker: inside the arena-shaped container (8 cores), else on the host.
#       -> work/status/machine-convcost-micro.txt
#   convcost.sh corpus [--runs N] [--host] CORPUS..
#       the real machine: three variant jars are built from a copy of the main tree
#           const-depth   every `d > m.hostDepth`  ->  `d > Op.HOST_DEPTH`
#           plain-tick    every `(--m.fuel & Op.POLL_MASK) == Op.POLL_MASK && m.tickSlow()`
#                         ->  `--m.fuel < 0 && m.tickSlow()`      (no poll: measurement only)
#           const-plain   both
#       and measured against the published jar on the corpora with the cost table's machinery
#       (report.sh cost --stage convcost --engines machine --jar J --name NAME): rows in
#       work/status/machine-cost.md. Only meaningful for a corpus the machine checks without declines.
#
# Nothing here changes the main tree; the variant sources live in work/m8/convcost-PID and are removed.
{ # the whole script is one compound command: bash has read all of it before it runs any of it, so a
  # publication that replaces this file while it runs (it runs for minutes) cannot derail it
set -euo pipefail
SELF="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
ROOT="$SELF"
while [ "$ROOT" != "/" ] && ! { [ -f "$ROOT/env.sh" ] && [ -f "$ROOT/rlvm-machine/OWNERS.tsv" ]; }; do ROOT="$(dirname "$ROOT")"; done
[ "$ROOT" != "/" ] || { echo "convcost.sh: cannot find the project root" >&2; exit 2; }
# shellcheck source=/dev/null
. "$ROOT/env.sh"
[ "$#" -ge 1 ] || { sed -n '2,/^{ #/p' "$0" | sed '$d' | sed 's/^# \{0,1\}//'; exit 2; }
mode="$1"; shift
work="$ROOT/work/m8/convcost-$$"
trap 'rm -rf "$work"' EXIT
mkdir -p "$work" "$ROOT/work/status"

publishing() { [ -f "$1.holder" ] && grep -q '^publish' "$1.holder" 2>/dev/null; }

copy_tree() { # copy_tree DST: a copy of the main tree taken in a moment without a publication (see report.sh)
  local dst="$1" lock="$ROOT/work/sandbox/.lock" log="$ROOT/work/sandbox/publish.log" i n0 n1
  for i in $(seq 1 600); do
    if publishing "$lock"; then sleep 1; continue; fi
    n0="$(wc -l < "$log" 2>/dev/null || echo 0)"
    rm -rf "$dst/rlvm-machine"; mkdir -p "$dst/rlvm-machine"
    tar -C "$ROOT/rlvm-machine" --exclude=./target -cf - . | tar -C "$dst/rlvm-machine" -xf -
    n1="$(wc -l < "$log" 2>/dev/null || echo 0)"
    if [ "$n0" = "$n1" ] && ! publishing "$lock"; then cp -p "$ROOT/pom.xml" "$dst/pom.xml"; return 0; fi
  done
  echo "convcost.sh: no moment without a publication in ten minutes" >&2; exit 2
}

case "$mode" in
  micro)
    rounds=15; n=32; docker=0
    while [ "$#" -gt 0 ]; do case "$1" in
      --rounds) rounds="$2"; shift 2 ;; --n) n="$2"; shift 2 ;; --docker) docker=1; shift ;;
      *) echo "convcost.sh: unknown option $1" >&2; exit 2 ;; esac; done
    copy_tree "$work/tree"
    # shellcheck disable=SC2086
    $MVN -o -q -f "$work/tree/rlvm-machine/pom.xml" "-Drlvm.root=$ROOT" test-compile \
        org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath -Dmdep.outputFile="$work/cp.txt" -Dmdep.includeScope=test \
        > "$work/build.out" 2>&1 || { tail -n 20 "$work/build.out" >&2; exit 2; }
    # the classes are copied out of the tree that is removed at exit; inside the container the same paths are visible (work/)
    cp="$work/tree/rlvm-machine/target/test-classes:$work/tree/rlvm-machine/target/classes:$(cat "$work/cp.txt")"
    out="$ROOT/work/status/machine-convcost-micro.txt"
    {
      echo "# machine-convcost-micro: m.hostDepth as a field and the masked TICK line against the convention's constants"
      echo "# generated $(date -u +%Y-%m-%dT%H:%M:%SZ) by: rlvm-machine/src/test/resources/m8/convcost.sh micro --rounds $rounds --n $n$([ $docker -eq 1 ] && echo ' --docker')"
      echo "# variants: FM field + masked (as built), CM constant + masked, FP field + plain, CP constant + plain (the convention), R plain recursion"
      echo "# CPU time of the measuring thread, minimum over the rounds, per judgment; host load average: $(cut -d' ' -f1-3 /proc/loadavg)"
      for jit in "C2:-XX:-UseJVMCICompiler" "Graal:-XX:+UseJVMCICompiler"; do
        echo "## JIT ${jit%%:*}"
        cmd=("$JAVA_HOME/bin/java" -XX:+UseSerialGC "${jit#*:}" -Xss2m -Xmx2g -cp "$cp" rlvm.machine.M8ConvCost "$rounds" "$n")
        if [ "$docker" -eq 1 ]; then
          "$ROOT/bench/arena-docker.sh" --arena-limits --no-network "${cmd[@]}" 2>/dev/null
        else
          "${cmd[@]}"
        fi
      done
    } | tee "$out"
    echo "report: $out"
    ;;
  corpus)
    runs=1; host=(); corpora=()
    while [ "$#" -gt 0 ]; do case "$1" in
      --runs) runs="$2"; shift 2 ;; --host) host=(--host); shift ;; *) corpora+=("$1"); shift ;; esac; done
    [ "${#corpora[@]}" -ge 1 ] || { echo "convcost.sh: corpus needs at least one corpus (init, std, cedar, ...)" >&2; exit 2; }
    mkdir -p "$ROOT/work/m8/variants"
    for v in const-depth plain-tick const-plain; do
      copy_tree "$work/$v"
      src="$work/$v/rlvm-machine/src/main/java/rlvm/machine"
      case "$v" in const-depth|const-plain) sed -i 's/d > m\.hostDepth/d > Op.HOST_DEPTH/g' "$src"/*.java ;; esac
      case "$v" in plain-tick|const-plain)
        sed -i 's/(--m\.fuel & Op\.POLL_MASK) == Op\.POLL_MASK && m\.tickSlow()/--m.fuel < 0 \&\& m.tickSlow()/g' "$src"/*.java ;; esac
      echo "variant $v: $(grep -c 'Op.HOST_DEPTH)' "$src"/*.java | awk -F: '{s+=$2} END {print s}') constant depth tests, $(grep -c -- '--m.fuel < 0' "$src"/*.java | awk -F: '{s+=$2} END {print s}') plain ticks" >&2
      # shellcheck disable=SC2086
      $MVN -o -q -f "$work/$v/rlvm-machine/pom.xml" "-Drlvm.root=$ROOT" package -DskipTests > "$work/$v.out" 2>&1 \
          || { echo "convcost.sh: variant $v does not compile" >&2; tail -n 20 "$work/$v.out" >&2; exit 2; }
      cp "$work/$v/rlvm-machine/target/"rlvm-machine-*.jar "$ROOT/work/m8/variants/rlvm-machine-$v.jar"
    done
    python3 "$SELF/cost.py" --stage convcost --runs "$runs" ${host[@]+"${host[@]}"} --engines machine --name as-built "${corpora[@]}"
    for v in const-depth plain-tick const-plain; do
      python3 "$SELF/cost.py" --stage convcost --runs "$runs" ${host[@]+"${host[@]}"} --engines machine \
          --jar "work/m8/variants/rlvm-machine-$v.jar" --name "$v" "${corpora[@]}"
    done
    ;;
  *) echo "convcost.sh: unknown mode '$mode' (micro, corpus)" >&2; exit 2 ;;
esac
exit $?
}
