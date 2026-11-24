#!/usr/bin/env bash
# Build one native image and report build CPU/wall/RSS and binary size.
#   scripts/build-native.sh <ce|ee> <name> <classpath> <MainClass> [native-image flags...]
# Output: $OUT/ni/<name>   (and a line appended to results/native-build.txt)
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/../env.sh"
ed="$1"; name="$2"; cp="$3"; main="$4"; shift 4
case "$ed" in
  ce) NI="$GRAAL_CE/bin/native-image" ;;
  ee) NI="$GRAAL_EE/bin/native-image" ;;
  *) echo "edition must be ce or ee" >&2; exit 2 ;;
esac
mkdir -p "$OUT/ni" "$OUT/ni-work/$name"
cd "$OUT/ni-work/$name"
log="$OUT/ni-work/$name/build.log"
tmp="$(mktemp)"
if /usr/bin/time -o "$tmp" -f "cpu %U+%S s wall %e s rss %M KB" "$NI" "$@" -cp "$cp" "$main" -o "$OUT/ni/$name" >"$log" 2>&1; then
  size=$(stat -c %s "$OUT/ni/$name")
  line="$ed $name [$*] OK | $(tail -1 "$tmp") | binary $((size / 1024 / 1024)) MB | $(grep -E 'Graal compiler:|Garbage collector:' "$log" | sed 's/^ - //' | tr '\n' ';')"
else
  line="$ed $name [$*] FAILED | $(grep -m3 -iE '^Error|error:' "$log" | tr '\n' ' ')"
fi
rm -f "$tmp"
echo "$line" | tee -a "$SPIKE/results/native-build.txt"
