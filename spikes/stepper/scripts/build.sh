#!/usr/bin/env bash
# Compiles the spike with plain javac (no dependencies) into $OUT/classes and $OUT/stepper.jar.
# Variants that differ from a template only in a compile-time constant are generated here:
#   Rec  -> RecI                     (INERT = true)
#   W1   -> W1n                      (INERT = false)
#   Hyb  -> Hyb0 Hyb4 Hyb8 Hyb32 Hyb64 Hyb256 HybInf (the depth bound D), HybI64 (INERT = true),
#           HybT0 HybT64 HybTInf (TICKS = true: budgets and TRY)
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/../env.sh"
SRC="$SPIKE/src/rlvm/spike/stepper"
GEN="$OUT/gen/rlvm/spike/stepper"
rm -rf "$OUT/gen" "$OUT/classes"; mkdir -p "$GEN" "$OUT/classes"
gen() { # template class, new class, sed expressions...
  local from="$1" to="$2"; shift 2
  [ -f "$SRC/$from.java" ] || return 0
  sed -E -e "s/\\b$from\\b/$to/g" "$@" "$SRC/$from.java" > "$GEN/$to.java"
}
gen Rec RecI -e 's/(static final boolean INERT = )false/\1true/'
gen W1 W1n -e 's/(static final boolean INERT = )true/\1false/'
for d in 0 4 8 32 64 256; do gen Hyb "Hyb$d" -e "s/(static final int D = )[0-9]+/\\1$d/"; done
gen Hyb HybInf -e 's/(static final int D = )[0-9]+/\1Integer.MAX_VALUE/'
gen Hyb HybI64 -e 's/(static final int D = )[0-9]+/\164/' -e 's/(static final boolean INERT = )false/\1true/'
gen Hyb HybT0 -e 's/(static final int D = )[0-9]+/\10/' -e 's/(static final boolean TICKS = )false/\1true/'
gen Hyb HybT64 -e 's/(static final int D = )[0-9]+/\164/' -e 's/(static final boolean TICKS = )false/\1true/'
gen Hyb HybTInf -e 's/(static final int D = )[0-9]+/\1Integer.MAX_VALUE/' -e 's/(static final boolean TICKS = )false/\1true/'
"$JAVA_HOME/bin/javac" -Xlint:all,-serial -Werror -d "$OUT/classes" "$SRC"/*.java "$GEN"/*.java
(cd "$OUT/classes" && "$JAVA_HOME/bin/jar" --create --file "$OUT/stepper.jar" --main-class rlvm.spike.stepper.Main .)
# Inlining directives for the JVM runs "jfi" / "cfi" (-XX:CompileCommandFile): the continuation steps of H are
# inlined into their callers, the cold materialisation helpers are kept out of line. scripts/build-images.sh
# passes the same two lists to native-image.
{
  echo quiet
  for m in evalApp1 apply evalLet1 rbApp1 rbB1 rbB2 rbB3 convApp1 convB1 convB2 convB3 convB4 convB5; do echo "inline rlvm/spike/stepper/Hyb*.$m"; done
  for m in sig1 sig2 sig3 sig4 save1 save2 save3 save4 spill2 spill3; do echo "dontinline rlvm/spike/stepper/Hyb*.$m"; done
} > "$OUT/inline.cmd"
echo "built $OUT/stepper.jar"
