#!/usr/bin/env bash
# Build one native image of the spike.
#   scripts/build-native.sh <ce|ee> <name> <plain|truffle> [native-image flags...]
#     plain    class path = machine.jar only, main = rlvm.spike.machine.Main (no Truffle in the image)
#     truffle  class path = machine.jar + lib/*, main = rlvm.spike.machine.truffle.MainT
#              (Truffle runtime compilation is enabled by truffle-runtime's native-image.properties)
# ee uses Oracle GraalVM 25.0.4 and the jars built with -Dgraalvm.version=25.0.4 ($OUT/target-25.0.4).
# Output: $OUT/ni/<name>, build log in $OUT/ni-work/<name>/build.log, one line appended to results/native-build.txt
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/../env.sh"
ed="$1"; name="$2"; kind="$3"; shift 3
case "$ed" in
  ce) NI="$GRAAL_CE/bin/native-image"; T="$OUT/target" ;;
  ee) NI="$GRAAL_EE/bin/native-image"; T="$OUT/target-25.0.4" ;;
  *) echo "edition must be ce or ee" >&2; exit 2 ;;
esac
case "$kind" in
  plain)   cp="$T/machine.jar"; main=rlvm.spike.machine.Main; extra=() ;;
  truffle) cp="$T/machine.jar:$(ls "$T"/lib/*.jar | tr '\n' ':')"; main=rlvm.spike.machine.truffle.MainT
           # TruffleInst uses OptimizedCallTarget.compile()/isValid(); truffle-runtime is forced onto the module path
           extra=(--add-exports org.graalvm.truffle.runtime/com.oracle.truffle.runtime=ALL-UNNAMED) ;;
  *) echo "kind must be plain or truffle" >&2; exit 2 ;;
esac
mkdir -p "$OUT/ni" "$OUT/ni-work/$name"
cd "$OUT/ni-work/$name"
log="$OUT/ni-work/$name/build.log"
tmp="$(mktemp)"
if /usr/bin/time -o "$tmp" -f "cpu %U+%S s wall %e s rss %M KB" "$NI" --no-fallback "${extra[@]}" "$@" -cp "$cp" "$main" -o "$OUT/ni/$name" >"$log" 2>&1; then
  size=$(stat -c %s "$OUT/ni/$name")
  line="$ed $name $kind [$*] OK | $(tail -1 "$tmp") | binary $((size / 1024 / 1024)) MB | $(grep -E 'Graal compiler:|Garbage collector:' "$log" | sed 's/^ - //' | tr '\n' ';')"
else
  line="$ed $name $kind [$*] FAILED | $(grep -m3 -iE '^Error|error:' "$log" | tr '\n' ' ')"
fi
rm -f "$tmp"
echo "$line" | tee -a "$SPIKE/results/native-build.txt"
