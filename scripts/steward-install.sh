#!/usr/bin/env bash
# scripts/steward-install.sh - install a module's jar from a TESTED SNAPSHOT of its sources.
#
#   scripts/steward-install.sh [--skip-tests] [--if-changed] MODULE...
#
#   --if-changed   do nothing for a module whose sources (src/ and pom.xml) are byte-identical to
#                  the ones of its last successful install by this script (hash kept in
#                  work/steward/installed/<module>.srcsha); for periodic refreshes
#
# For the integration steward (and for an owner who wants the same guarantee for a module of their
# own). Why not `mvn -pl MODULE test` followed by `mvn -pl MODULE install -DskipTests` in the tree:
# the author may save a file between the two commands, and the jar that gets installed is then a
# version nobody tested (this happened to rlvm-nat on 2026-11-24). Here the module directory is
# copied to work/steward/snap/<module> first (sources and pom only), the copy's test suite is run,
# and the jar built from that very copy is installed into .toolchain/m2. Nothing is written into
# the module's own target/ directory, so a build the author is running is not disturbed.
#
# One line per module is appended to work/status/steward.log. Exit status: 0 if every module was
# installed, 1 if a suite failed (that module is NOT installed; the others still are).
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
SNAP="$ROOT/work/steward/snap"
LOGDIR="$ROOT/work/logs/steward"
STATUS="$ROOT/work/status/steward.log"

skip_tests=0
if_changed=0
while :; do
  case "${1:-}" in
    --skip-tests) skip_tests=1; shift ;;
    --if-changed) if_changed=1; shift ;;
    -h|--help) sed -n '2,21p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) break ;;
  esac
done
[ "$#" -gt 0 ] || { sed -n '2,21p' "$0" | sed 's/^# \{0,1\}//'; exit 2; }
HASHES="$ROOT/work/steward/installed"
src_hash() { # hash of a module directory's pom and sources (paths and contents)
  ( cd "$1" && find pom.xml src -type f -print0 | LC_ALL=C sort -z | xargs -0 sha256sum | sha256sum | cut -c1-64 )
}

# shellcheck source=../env.sh
. "$ROOT/env.sh"
mkdir -p "$SNAP" "$LOGDIR" "$HASHES" "$(dirname "$STATUS")"

rc=0
for module in "$@"; do
  [ -f "$ROOT/$module/pom.xml" ] || { echo "steward-install: no module '$module'" >&2; rc=1; continue; }
  # A private copy per invocation: two installs of the same module must not share a directory.
  dir="$(mktemp -d "$SNAP/$module.XXXXXX")"
  # The parent pom with only this module in <modules> (Maven refuses a reactor with missing
  # children). It is never installed: -pl selects the module alone.
  grep -v "<module>" "$ROOT/pom.xml" | sed "s:<modules>:<modules><module>$module</module>:" >"$dir/pom.xml"
  # Module tests find corpora and resources relative to the repository root ("user.dir/.."): make
  # the snapshot's root look like it (links to work/, refs/, bench/, docs/, ...; never to a module).
  for entry in "$ROOT"/* "$ROOT/.toolchain"; do
    name="$(basename "$entry")"
    case "$name" in pom.xml|rlvm-*) continue ;; esac
    ln -s "$entry" "$dir/$name"
  done
  mkdir -p "$dir/$module"
  cp "$ROOT/$module/pom.xml" "$dir/$module/pom.xml"
  cp -r "$ROOT/$module/src" "$dir/$module/src"
  hash="$(src_hash "$dir/$module")"
  if [ "$if_changed" = 1 ] && [ "$(cat "$HASHES/$module.srcsha" 2>/dev/null)" = "$hash" ]; then
    echo "steward-install: $module unchanged since its last install"
    rm -rf "$dir"
    continue
  fi
  stamp="$(date -u +%FT%TZ)"
  log="$LOGDIR/$module-install-$(date -u +%H%M%S).log"
  if [ "$skip_tests" = 1 ]; then
    args=(install -DskipTests)
  else
    args=(install)
  fi
  if (cd "$ROOT" && $MVN -o -q -f "$dir/pom.xml" -pl "$module" "${args[@]}") >"$log" 2>&1; then
    tests="$(cat "$dir/$module"/target/surefire-reports/*.txt 2>/dev/null |
      awk '/Tests run:/ {gsub(",", ""); t += $3; f += $5; e += $7; s += $9} END {printf "%d tests, %d failures, %d errors, %d skipped", t, f, e, s}')"
    [ "$skip_tests" = 1 ] && tests="tests skipped"
    sha="$(sha256sum "$ROOT/.toolchain/m2/rlvm/$module/0.1.0-SNAPSHOT/$module-0.1.0-SNAPSHOT.jar" | cut -c1-12)"
    echo "$(date -u +%FT%TZ) installed $module 0.1.0-SNAPSHOT from a snapshot of its sources taken at $stamp ($tests; jar sha256 $sha)" >>"$STATUS"
    echo "steward-install: $module installed ($tests)"
    echo "$hash" >"$HASHES/$module.srcsha"
    rm -rf "$dir"
  else
    echo "$(date -u +%FT%TZ) NOT installed: $module (snapshot of $stamp does not build or its suite fails; log $log)" >>"$STATUS"
    echo "steward-install: $module FAILED, see $log" >&2
    grep -m 12 -E 'ERROR|FAIL' "$log" >&2
    rm -rf "$dir"
    rc=1
  fi
done
exit $rc
