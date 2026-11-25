#!/usr/bin/env bash
# scripts/package.sh - the release archive: rlvm-<version>-linux-<arch>.tar.gz.
#
#   scripts/package.sh [--dist DIR] [--out DIR] [--build [build.sh options...]]
#
# The archive holds one directory, rlvm-<version>-linux-<arch>/, with exactly three files:
#   rlvm        the executable: the native image bin/rlvm of a distribution (scripts/build.sh). It is
#               self-contained - no JDK, no jars, nothing of this repository - and needs only the C
#               library and zlib of the system (ldd is printed below)
#   LICENSE     the licence of the code (Apache-2.0 OR MIT)
#   README.md   the README of the repository
# and nothing else: the JVM launcher and the jars of a development distribution are never packaged.
#
#   --dist DIR   the distribution whose bin/rlvm is packaged (default: work/dist)
#   --out DIR    where the archive and its .sha256 go (default: work/release)
#   --build ...  build the release first: `scripts/build.sh --dist work/release/dist ...` with the
#                options that follow (e.g. --build --online, or --build --published), then package it
#
# Before it packs, the script runs the executable from an EMPTY directory with an empty environment
# (--version, the exit-status protocol and, if a published test is at hand, one accept and one reject):
# what is shipped has been seen to run without the repository around it. The archive is reproducible
# for a given executable (sorted names, fixed owner, the executable's modification time).
# An executable built with Oracle GraalVM (scripts/build.sh --oracle-pgo) is packaged under the name
# rlvm-<version>-linux-<arch>-oracle-pgo.tar.gz and the script says under which licence it stands.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
die() { echo "package.sh: $*" >&2; exit 1; }
log() { echo "package.sh: $*" >&2; }

dist="$ROOT/work/dist"
out="$ROOT/work/release"
build=0
build_args=()
while [ "$#" -gt 0 ]; do
  case "$1" in
    --dist) dist="$(realpath -m "${2:?--dist needs a directory}")"; shift 2 ;;
    --out) out="$(realpath -m "${2:?--out needs a directory}")"; shift 2 ;;
    --build) build=1; shift; build_args=("$@"); break ;;
    -h|--help) sed -n '2,24p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) die "unknown option: $1 (see --help)" ;;
  esac
done

if [ "$build" = 1 ]; then
  dist="$out/dist"
  "$ROOT/scripts/build.sh" --dist "$dist" "${build_args[@]}"
fi
exe="$dist/bin/rlvm"
[ -x "$exe" ] || die "no executable at $exe (scripts/build.sh builds it; --build does it here)"
[ -f "$ROOT/LICENSE" ] && [ -f "$ROOT/README.md" ] || die "LICENSE or README.md is missing in $ROOT"

# What is being packaged, in the executable's own words.
info="$(env -i "$exe" --version)" || die "$exe --version failed"
version="$(printf '%s\n' "$info" | sed -n '1s/^rlvm //p')"
[ -n "$version" ] || die "$exe --version prints no version"
case "$(uname -m)" in
  x86_64|amd64) arch=x86_64 ;;
  aarch64|arm64) arch=aarch64 ;;
  *) arch="$(uname -m)" ;;
esac
suffix=""
if printf '%s\n' "$info" | grep -q -e '^build: Oracle GraalVM' -e '^runtime: .*Oracle GraalVM'; then
  suffix="-oracle-pgo"
  log "NOTE: this executable was built with ORACLE GraalVM (GraalVM Free Terms and Conditions, GFTC), not with GraalVM Community Edition: the archive is named '...$suffix' and is not the default release"
fi
name="rlvm-$version-linux-$arch$suffix"

# The executable must run with nothing of the repository around it: an empty directory, an empty
# environment, no JAVA_HOME, no PATH.
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/$name" "$work/run"
cp "$exe" "$work/$name/rlvm"
cp "$ROOT/LICENSE" "$ROOT/README.md" "$work/$name/"
chmod 755 "$work/$name" "$work/$name/rlvm"; chmod 644 "$work/$name/LICENSE" "$work/$name/README.md"
check() { # expected status, arguments...
  local want="$1" got=0; shift
  ( cd "$work/run" && env -i "$work/$name/rlvm" "$@" > "$work/run/out.txt" 2>&1 ) || got=$?
  [ "$got" = "$want" ] || { cat "$work/run/out.txt" >&2; die "the packaged executable: 'rlvm $*' ended with status $got, expected $want"; }
}
check 0 --version
check 0 --help
for mode in accept:0 reject:1 decline:2 error:3; do
  check "${mode##*:}" --protocol-selftest "${mode%%:*}"
done
check 64
checked="--version, --help, the exit-status protocol"
good="$ROOT/work/arena-site/tests/good/tutorial/001_basicDef.ndjson"
bad="$ROOT/work/arena-site/tests/bad/tutorial/002_badDef.ndjson"
if [ -f "$good" ] && [ -f "$bad" ]; then
  check 0 "$good"
  check 1 "$bad"
  checked="$checked, one accepted and one rejected published test"
fi
log "the executable runs from an empty directory with an empty environment: $checked"
log "dynamic dependencies: $(ldd "$exe" 2>/dev/null | awk '{print $1}' | paste -sd' ' - || echo unknown)"

mkdir -p "$out"
archive="$out/$name.tar.gz"
# Reproducible for a given executable: sorted names, numeric owner 0, the executable's own time.
mtime="$(date -u -r "$exe" +%Y-%m-%dT%H:%M:%SZ)"
tar -C "$work" --sort=name --owner=0 --group=0 --numeric-owner --mtime="$mtime" -cf - "$name" | gzip -9 -n > "$archive.tmp"
mv "$archive.tmp" "$archive"
( cd "$out" && sha256sum "$name.tar.gz" > "$name.tar.gz.sha256" )

listing="$(tar -tzf "$archive" | LC_ALL=C sort | paste -sd' ' -)"
[ "$listing" = "$name/ $name/LICENSE $name/README.md $name/rlvm" ] || die "the archive does not hold exactly rlvm, LICENSE and README.md: $listing"
log "$archive: $(stat -c %s "$archive") bytes, sha256 $(cut -c1-64 "$archive.sha256")"
log "contents: $listing"
printf '%s\n' "$info" | sed 's/^/package.sh:   /' >&2
echo "$archive"
