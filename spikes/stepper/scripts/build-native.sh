#!/usr/bin/env bash
# Builds a native image of the spike with GraalVM CE 25 (the recipe of spikes/machine/scripts/build-native.sh):
#   scripts/build-native.sh <name> [native-image flags...]        ->  $OUT/ni/<name>
# The package is initialised at build time, so Vals.STATS (false) and the depth bounds are constants in the image.
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/../env.sh"
name="$1"; shift
mkdir -p "$OUT/ni" "$OUT/ni-work/$name"
cd "$OUT/ni-work/$name"
log="$OUT/ni-work/$name/build.log"
tmp="$(mktemp)"
if /usr/bin/time -o "$tmp" -f "cpu %U+%S s wall %e s rss %M KB" "$JAVA_HOME/bin/native-image" --no-fallback \
     --initialize-at-build-time=rlvm.spike.stepper "$@" -cp "$OUT/stepper.jar" rlvm.spike.stepper.Main -o "$OUT/ni/$name" >"$log" 2>&1; then
  size=$(stat -c %s "$OUT/ni/$name")
  line="ce $name [$*] OK | $(tail -1 "$tmp") | binary $((size / 1024 / 1024)) MB | $(grep -E 'Graal compiler:|Garbage collector:' "$log" | sed 's/^ - //' | tr '\n' ';')"
else
  line="ce $name [$*] FAILED | $(grep -m3 -iE '^Error|error:' "$log" | tr '\n' ' ')"
fi
rm -f "$tmp"
echo "$line" | tee -a "$SPIKE/results/native-build.txt"
