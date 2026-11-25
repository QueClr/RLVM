#!/usr/bin/env bash
# bench/rlvm/run.sh - run the built RLVM checker on one export exactly as the arena would.
#
#   bench/rlvm/run.sh [--native|--jvm|--pgo] [--threads N] [options] EXPORT.ndjson [-- rlvm options...]
#   bench/rlvm/run.sh [--native|--jvm|--pgo] --selftest
#
# "As the arena would": the command is the `run:` line of scripts/arena/rlvm.yaml, executed with
# `sh -c`, cwd = the project root (the arena: the checker's checkout), IN = absolute path of the
# export, LC_ALL=C, stdin = /dev/null. Nothing else is added, so what is measured through this
# script (or through the command printed by --print) is what the arena would measure.
#
#   --native        work/dist/bin/rlvm       native image, GraalVM CE   (default; the arena's binary)
#   --jvm           work/dist/bin/rlvm-jvm   the same jars on the JVM
#   --pgo           work/dist-oracle-pgo/bin/rlvm   the OPTIONAL executable built with Oracle GraalVM and
#                   the shipped profile (scripts/build.sh --oracle-pgo; GFTC licence, never a default
#                   build): the same single executable in a distribution of its own. With --dist DIR:
#                   bin/rlvm of that directory
#   --threads N     replace the worker count of the run line (yaml default: see `run:`)
#   --dist DIR      use another distribution directory (default: work/dist)
#   --print         print the shell command instead of running it (used by suite.sh, measure.sh)
#   --selftest      test the exit-status protocol of the chosen binary (no export needed):
#                   every --protocol-selftest mode, real heap exhaustion, usage errors, the
#                   Truffle runtime-compilation probe. Prints one line per case; exit 0 if all pass.
#   -- ARGS         appended to the command (rlvm accepts options after the export), e.g. -- --stats
#
# Exit status: the checker's (0 accept, 1 reject, 2 decline, 3 internal error, 64 usage error);
# 64 for a wrong command line here, 70 if the distribution is not built (scripts/build.sh).
set -euo pipefail

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
ROOT="$(cd "$SELF_DIR/../.." && pwd -P)"
YAML="$ROOT/scripts/arena/rlvm.yaml"

mode=native
threads=""
dist="work/dist"
dist_given=0
print_only=0
selftest=0
export_file=""
extra=()

usage() { sed -n '2,28p' "$0" | sed 's/^# \{0,1\}//'; }

while [ "$#" -gt 0 ]; do
  case "$1" in
    --native|--jvm|--pgo) mode="${1#--}"; shift ;;
    --threads|-j) threads="${2:?$1 needs a number}"; shift 2 ;;
    --dist) dist="${2:?--dist needs a directory}"; dist_given=1; shift 2 ;;
    --print) print_only=1; shift ;;
    --selftest) selftest=1; shift ;;
    -h|--help) usage; exit 0 ;;
    --) shift; extra=("$@"); break ;;
    -*) echo "run.sh: unknown option: $1 (see --help)" >&2; exit 64 ;;
    *)
      [ -z "$export_file" ] || { echo "run.sh: more than one export given" >&2; exit 64; }
      export_file="$1"; shift ;;
  esac
done
case "$threads" in ''|*[!0-9]*) [ -z "$threads" ] || { echo "run.sh: --threads needs a number" >&2; exit 64; } ;; esac

case "$mode" in
  native) binary=rlvm ;;
  jvm) binary=rlvm-jvm ;;
  pgo) binary=rlvm; [ "$dist_given" = 1 ] || dist="work/dist-oracle-pgo" ;;   # no build makes bin/rlvm-pgo any more
esac
case "$dist" in /*) dist_abs="$dist" ;; *) dist_abs="$ROOT/$dist" ;; esac

# The arena's run line, with the binary, the distribution directory and the thread count replaced
# on request. Kept as one string because the arena passes exactly one string to `sh -c`.
run_line="$(sed -n 's/^run:[[:space:]]*//p' "$YAML" | head -n 1)"
[ -n "$run_line" ] || { echo "run.sh: no 'run:' line in $YAML" >&2; exit 70; }
marker='work/dist/bin/rlvm '
in_ref='"$IN"'
rest="${run_line#*"$marker"}"
if [ "$rest" = "$run_line" ] || [ "${rest#*"$marker"}" != "$rest" ] || [ "${run_line#*"$in_ref"}" = "$run_line" ]; then
  echo "run.sh: the run line of $YAML must mention '$marker' exactly once and $in_ref: $run_line" >&2
  exit 70
fi
cmd="${run_line%%"$marker"*}$dist/bin/$binary $rest"
if [ -n "$threads" ]; then
  case "$cmd" in
    *"--threads "[0-9]*) cmd="$(printf '%s' "$cmd" | sed -E "s/--threads [0-9]+/--threads $threads/")" ;;
    *) cmd="${cmd%%"$in_ref"*}--threads $threads $in_ref${cmd#*"$in_ref"}" ;;
  esac
fi
if [ "${#extra[@]}" -gt 0 ]; then
  # rlvm accepts options after the export, so they go right behind "$IN".
  cmd="${cmd%%"$in_ref"*}$in_ref$(printf ' %q' "${extra[@]}")${cmd#*"$in_ref"}"
fi

if [ "$print_only" = 1 ]; then
  printf '%s\n' "$cmd"
  exit 0
fi

bin="$dist_abs/bin/$binary"
if [ ! -x "$bin" ]; then
  echo "run.sh: $bin not found; build it with scripts/build.sh" >&2
  exit 70
fi

# ------------------------------------------------------------------------------------------------
if [ "$selftest" = 1 ]; then
  pass=0; fail=0
  smoke=""
  for f in "$ROOT/work/arena-site/tests/good/init-prelude.ndjson" "$ROOT"/work/arena-site/tests/good/tutorial/*.ndjson; do
    if [ -f "$f" ]; then smoke="$f"; break; fi
  done
  check() { # label expected command...
    local label="$1" want="$2" got=0 out; shift 2
    out="$("$@" 2>&1 </dev/null)" || got=$?
    if [ "$got" = "$want" ]; then
      pass=$((pass + 1)); printf '  ok    %-44s exit %s\n' "$label" "$got"
    else
      fail=$((fail + 1)); printf '  FAIL  %-44s exit %s, expected %s\n' "$label" "$got" "$want"
      printf '%s\n' "$out" | tail -n 6 | sed 's/^/          /'
    fi
  }
  echo "protocol self-test of $bin"
  cd "$ROOT"
  for m in accept:0 reject:1 decline:2 null:3 throw:3 error:3 oom:3 stackoverflow:3 thread-death:3; do
    check "--protocol-selftest ${m%%:*}" "${m##*:}" "$bin" --protocol-selftest "${m%%:*}"
  done
  check "real stack overflow, 1 GiB stack" 3 "$bin" --protocol-selftest stackoverflow --stack-size 1g
  check "real heap exhaustion (-Xmx128m)" 3 "$bin" -Xmx128m --protocol-selftest oom-real
  check "no export" 64 env -u IN "$bin"
  check "missing export file" 64 env IN=/nonexistent/x.ndjson "$bin"
  check "unknown option" 64 "$bin" --no-such-option
  check "--version" 0 "$bin" --version
  check "--version with an export is refused" 64 "$bin" --version /dev/null
  check "self-test with an export is refused" 64 "$bin" --protocol-selftest accept /dev/null
  if [ -n "$smoke" ]; then
    check "placeholder declines (argument)" 2 "$bin" --checker placeholder "$smoke"
    check "placeholder declines (\$IN)" 2 env "IN=$smoke" "$bin" --checker placeholder
    # The default checker is the real pipeline (rlvm-main) since 2026-11-24: the smoke export is a
    # good arena test, so the arena run line must accept it (it declined while only the placeholder existed).
    check "arena run line accepts a good export" 0 env "IN=$smoke" LC_ALL=C sh -c "$cmd"
  fi
  # RLVM_STATUS_BASE and the mapping of the arena run line: a runtime that fails to start exits with
  # 1 on its own, which must reach the arena as an error (3), not as a rejection.
  check "status base 10: accept -> 10" 10 env RLVM_STATUS_BASE=10 "$bin" --protocol-selftest accept
  check "status base 10: reject -> 11" 11 env RLVM_STATUS_BASE=10 "$bin" --protocol-selftest reject
  check "status base 10: decline -> 12" 12 env RLVM_STATUS_BASE=10 "$bin" --protocol-selftest decline
  check "status base 10: throwable -> 13" 13 env RLVM_STATUS_BASE=10 "$bin" --protocol-selftest throw
  check "malformed status base -> 3" 3 env RLVM_STATUS_BASE=x "$bin" --protocol-selftest accept
  check "runtime fails to start: raw status is 1" 1 "$bin" -XX:NoSuchRlvmOption=1 --version
  if [ -n "$smoke" ]; then
    case "$cmd" in
      *" --threads "*)
        check "the same through the arena run line -> 3" 3 \
          env "IN=$smoke" LC_ALL=C sh -c "${cmd/ --threads / -XX:NoSuchRlvmOption=1 --threads }" ;;
    esac
  fi
  check "Truffle runtime compilation (--truffle-probe)" 0 "$bin" --truffle-probe
  check "probe fails without compilation" 3 "$bin" --truffle-probe -X engine.Compilation=false
  echo "$pass passed, $fail failed"
  [ "$fail" = 0 ] || exit 1
  exit 0
fi

# ------------------------------------------------------------------------------------------------
[ -n "$export_file" ] || { echo "run.sh: no export given (see --help)" >&2; exit 64; }
[ -f "$export_file" ] || { echo "run.sh: no such file: $export_file" >&2; exit 64; }
IN="$(realpath -s "$export_file")"
LC_ALL=C
export IN LC_ALL
cd "$ROOT"
exec sh -c "$cmd" </dev/null
