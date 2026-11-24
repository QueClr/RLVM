#!/usr/bin/env bash
# rlvm-machine/h10/native-image-args.sh - prints the native-image arguments of the host control convention
# (docs/design/review/stepper-convention.md H10, rule 3) for the machine classes it is given. Owner: M6.
#
#   rlvm-machine/h10/native-image-args.sh [JAR | CLASSES-DIR]      (default: the published jar in .toolchain/m2)
#
# Output (one line, to be appended to the native-image command of scripts/build.sh):
#   -H:+UnlockExperimentalVMOptions -H:PriorityForceInline=<every continuation step k_*>
#   -H:PriorityNeverInline=<Machine.sig*, Machine.spill*, Machine.tickSlow, every *Slow method>
# The lists name every method explicitly (class.method), as the spike's build did
# (spikes/stepper/results/native-build.txt); they are derived from the class files with javap, so the
# output changes whenever a package adds a continuation step: generate it at build time, do not copy it.
# Tuning, not correctness: 0 to 3 points of CPU time in the spike; not measured on the real machine yet.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
case "$ROOT" in */work/sandbox/*) ROOT="${ROOT%%/work/sandbox/*}" ;; esac
src="${1:-$ROOT/.toolchain/m2/rlvm/rlvm-machine/0.1.0-SNAPSHOT/rlvm-machine-0.1.0-SNAPSHOT.jar}"
[ -e "$src" ] || { echo "native-image-args: no $src" >&2; exit 2; }
# shellcheck source=../../env.sh
. "$ROOT/env.sh" > /dev/null 2>&1 || true
javap_bin="${JAVA_HOME:+$JAVA_HOME/bin/}javap"
if [ -d "$src" ]; then
  classes=$(cd "$src" && find rlvm/machine -name '*.class' | sed 's/\.class$//' | tr / . | LC_ALL=C sort)
else
  classes=$(unzip -Z1 "$src" 'rlvm/machine/*.class' | sed 's/\.class$//' | tr / . | LC_ALL=C sort)
fi
force=""
never=""
for c in $classes; do
  # "  static int k_conv1(rlvm.machine.Machine, int, int, int, int);" -> k_conv1
  methods=$("$javap_bin" -p -cp "$src" "$c" 2>/dev/null | sed -n 's/^.* \([A-Za-z_$][A-Za-z0-9_$]*\)(.*$/\1/p' | LC_ALL=C sort -u)
  for m in $methods; do
    case "$m" in
      k_*) force="$force${force:+,}$c.$m" ;;
      *Slow) never="$never${never:+,}$c.$m" ;;
    esac
    if [ "$c" = rlvm.machine.Machine ]; then
      case "$m" in sig[0-9X]*|spill[0-9]*) never="$never${never:+,}$c.$m" ;; esac
    fi
  done
done
printf '%s' "-H:+UnlockExperimentalVMOptions"
[ -z "$force" ] || printf ' %s' "-H:PriorityForceInline=$force"
[ -z "$never" ] || printf ' %s' "-H:PriorityNeverInline=$never"
printf '\n'
