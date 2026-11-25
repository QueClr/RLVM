#!/usr/bin/env bash
# scripts/check-light.sh - one export through a distribution with LIGHT flags, so that thousands of
# small files are judged quickly: what the mutant campaigns and the hostile step of scripts/gate.sh run.
# Not for measurements and not the arena run line.
#   default (RLVM_LAUNCHER unset or jvm): the distribution's jars on the JVM, C1 only, 4 GB heap,
#                                         class-data sharing
#   RLVM_LAUNCHER=native:                 the distribution's executable bin/rlvm with a 4 GB heap cap
#                                         (scripts/gate.sh --native: the binary a release ships)
#
#   scripts/check-light.sh EXPORT.ndjson          exit status: the launcher's (0 accept, 1 reject, 2 decline, 3, 64)
#
# Environment: RLVM_DIST (distribution directory, default work/dist), RLVM_THREADS (default 1;
# 0 = automatic), RLVM_ENGINE (default reference; "default" = no -X engine: the distribution's own
# default engine), RLVM_LOAD (default batch; "default" = no -X load: the checker's own rule),
# RLVM_EXTRA_CP (JVM only: class path entries after the distribution's jars, e.g. an engine that is not
# in the distribution), RLVM_CHECK_ARGS (further launcher arguments, split on blanks, e.g. "-X levels=official").
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
dist="${RLVM_DIST:-$ROOT/work/dist}"
case "$dist" in /*) ;; *) dist="$ROOT/$dist" ;; esac
engine="-X engine=${RLVM_ENGINE:-reference}"; [ "${RLVM_ENGINE:-}" != default ] || engine=""
load="-X load=${RLVM_LOAD:-batch}"; [ "${RLVM_LOAD:-}" != default ] || load=""
if [ "${RLVM_LAUNCHER:-jvm}" = native ]; then
  # shellcheck disable=SC2086
  exec "$dist/bin/rlvm" -Xmx4g --threads "${RLVM_THREADS:-1}" "$1" $engine $load ${RLVM_CHECK_ARGS:-}
fi
# shellcheck disable=SC2086
exec "$ROOT/.toolchain/graalvm-ce-25/bin/java" -Xmx4g -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xshare:auto \
  -XX:+ExitOnOutOfMemoryError --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow \
  -cp "$dist/lib/*${RLVM_EXTRA_CP:+:$RLVM_EXTRA_CP}" rlvm.cli.Main --threads "${RLVM_THREADS:-1}" "$1" \
  $engine $load ${RLVM_CHECK_ARGS:-}
