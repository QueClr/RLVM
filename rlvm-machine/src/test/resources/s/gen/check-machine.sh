#!/usr/bin/env bash
# work/soundness/tools/check-machine.sh SWITCHES EXPORT - one export through the frozen distribution
# work/soundness/dist with the machine as the primary engine under the given staging switches ("v0" = none;
# a suffix @N sets the elaboration threshold). Light JVM flags as scripts/check-light.sh.
# Exit status: the launcher's (0 accept, 1 reject, 2 decline).
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd -P)"
dist="${RLVM_DIST:-$ROOT/work/soundness/dist}"
sw="$1"; in="$2"
elab=""
case "$sw" in *@*) elab="-Drlvm.machine.elabAt=${sw##*@}"; sw="${sw%@*}" ;; esac
args=(-X engine=machine)
[ "$sw" = v0 ] || args+=(-X "machine.switches=$sw")
# shellcheck disable=SC2086
exec "$ROOT/.toolchain/graalvm-ce-25/bin/java" -Xmx4g -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xshare:auto $elab \
  -XX:+ExitOnOutOfMemoryError --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow \
  -cp "$dist/lib/*" rlvm.cli.Main --threads 1 "$in" "${args[@]}"
