#!/bin/sh
# Arena-style run command for the spike (JVM mode, instruction-frugal flags; see
# docs/research/10-graalvm-platform.md section 1). Deep recursion is handled by the launcher, which
# runs the work in a thread created with an explicit large stack size (no -Xss needed).
#   usage: sh arena/run.sh <launcher args...>      e.g.  sh arena/run.sh ast tree 20 200
# A real checker would be:  run: sh arena/run.sh "$IN"   with exit codes 0 accept / 1 reject / 2 decline.
set -eu
B="$PWD/.build"
if [ -x "$B/lc-native" ] && [ "${RLVM_FORCE_JVM:-0}" != 1 ]; then
  # Native image: serial GC, fixed heap, large young generation (default young max is only 10% of heap).
  exec "$B/lc-native" -Xms6g -Xmx10g -Xmn3g "$@"
fi
exec "$B/graalvm/bin/java" \
  -XX:+UseSerialGC -Xms6g -Xmx10g -Xmn3g \
  -XX:-UseJVMCICompiler -XX:-TieredCompilation \
  -XX:+UseCompactObjectHeaders -XX:-UsePerfData -Xshare:auto \
  --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow \
  -Dpolyglot.engine.WarnInterpreterOnly=false \
  -cp "$B/lc.jar:$B/lib/*" rlvm.spike.lc.Main "$@"
