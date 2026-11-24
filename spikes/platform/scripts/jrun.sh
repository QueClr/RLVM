#!/usr/bin/env bash
# Run a plain-Java spike class and append GNU-time CPU/RSS numbers on the same line.
#   scripts/jrun.sh "<jvm flags>" <MainClass> <args...>
# Environment: JRUN_JAVA to override the java binary (e.g. Oracle GraalVM), JRUN_CP for the class path.
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/../env.sh"
JAVA="${JRUN_JAVA:-$JAVA_HOME/bin/java}"
flags="$1"; shift
tmp="$(mktemp)"
# shellcheck disable=SC2086
out="$(/usr/bin/time -o "$tmp" -f "| CPU user %U sys %S wall %e s rss %M KB" "$JAVA" $flags -cp "${JRUN_CP:-$OUT/plain}" "$@" 2>&1 | tail -3 | tr '\n' ' ')" || true
echo "[$flags] $out $(tail -1 "$tmp")"
rm -f "$tmp"
