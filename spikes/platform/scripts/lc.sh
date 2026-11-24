#!/usr/bin/env bash
# Run the Truffle spike launcher in JVM mode and print one result line + CPU/RSS from GNU time.
#   scripts/lc.sh [-J<jvm flag> ...] [--engine.X=Y ...] <plain|ast|bc> <fib|church|tree> <n> <iterations>
# Environment: LC_JAVA (default: GraalVM CE java), LC_MODULEPATH=1 to run from the module path.
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/../env.sh"
JAVA="${LC_JAVA:-$JAVA_HOME/bin/java}"
T="$SPIKE/truffle-lc/target"
jvm=()
app=()
for a in "$@"; do
  case "$a" in
    -J*) jvm+=("${a#-J}") ;;
    *) app+=("$a") ;;
  esac
done
quiet=(--enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow)
exec /usr/bin/time -f "    cpu user %U s + sys %S s | wall %e s | maxrss %M KB" \
  "$JAVA" "${quiet[@]}" "${jvm[@]}" -cp "$T/lc.jar:$T/lib/*" rlvm.spike.lc.Main "${app[@]}"
