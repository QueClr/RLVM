#!/usr/bin/env bash
# scripts/arena/build.sh - the `build:` command of the arena checker definition (rlvm.yaml).
#
# Runs in the checker's checkout (cwd = repository root), on the arena runner (x86-64) or here
# (aarch64). Network is needed: nothing but python, a C compiler and the usual shell tools is assumed
# on the machine.
#
#   1. scripts/fetch-toolchain.sh   GraalVM CE and Maven for this architecture, checksum-pinned
#   2. zlib                         native-image links every image against libz. If the C compiler
#                                   cannot link -lz (no zlib development files, e.g. inside the arena's
#                                   nix shell), zlib 1.3.1 is built from its sha256-pinned source as a
#                                   static library and handed to the linker
#   3. scripts/build.sh             Maven (online, strict checksums; the third-party jars that go into
#                                   the image are compared with scripts/arena/deps.sha256), native image
#                                   with Truffle runtime compilation, verification of the image.
#                                   A tree build: every module that SHIPS is compiled from this checkout
#                                   (the test kit, rlvm-testkit, is neither compiled nor on the image's
#                                   class path: the image has the 22 jars of the distributions that are
#                                   gated). The image gets the machine's inline lists ("H10"), generated
#                                   from the machine jar with the javap and jar of the GraalVM that step 1
#                                   fetched; if that fails the image is built without them
#
# Result: work/dist/bin/rlvm, the ONE executable (the release layout of scripts/build.sh: no jars, no
# second launcher), which the `run:` line of rlvm.yaml executes. GraalVM Community Edition only: the
# optional Oracle GraalVM build (scripts/build.sh --oracle-pgo) is never used here.
# Environment: RLVM_ARENA_OPT (native-image optimisation level, default 3),
#              RLVM_ARENA_MARCH (e.g. native; default: native-image's portable default),
#              RLVM_ARENA_FORCE_ZLIB=1 (build zlib even if the system has it; for testing).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
cd "$ROOT"
log() { echo "arena/build.sh: $*" >&2; }
die() { echo "arena/build.sh: $*" >&2; exit 1; }

ZLIB_VERSION="1.3.1"
ZLIB_SHA256="9a93b2b7dfdac77ceba5a558a580e74667dd6fede4585b91eefb60f03b72df23"
ZLIB_URLS=(
  "https://github.com/madler/zlib/releases/download/v$ZLIB_VERSION/zlib-$ZLIB_VERSION.tar.gz"
  "https://zlib.net/fossils/zlib-$ZLIB_VERSION.tar.gz"
)

start="$(date +%s)"
scripts/fetch-toolchain.sh

CC_BIN="$(command -v gcc || command -v cc || true)"
[ -n "$CC_BIN" ] || die "no C compiler (gcc or cc) on PATH; native-image needs one to link"

build_args=(--online --opt "${RLVM_ARENA_OPT:-3}" --verify-deps "$ROOT/scripts/arena/deps.sha256")
[ -z "${RLVM_ARENA_MARCH:-}" ] || build_args+=(--march "$RLVM_ARENA_MARCH")

probe_dir="$(mktemp -d)"
trap 'rm -rf "$probe_dir"' EXIT
echo 'int main(void) { return 0; }' > "$probe_dir/t.c"
if [ "${RLVM_ARENA_FORCE_ZLIB:-0}" != 1 ] && "$CC_BIN" "$probe_dir/t.c" -lz -o "$probe_dir/t" 2>/dev/null; then
  log "the C compiler links -lz: using the system zlib"
else
  zdir="$ROOT/work/build/zlib"
  if [ ! -f "$zdir/lib/libz.a" ]; then
    log "no linkable zlib: building zlib $ZLIB_VERSION from source"
    rm -rf "$zdir"; mkdir -p "$zdir/src" "$zdir/lib"
    ok=0
    for url in "${ZLIB_URLS[@]}"; do
      if curl -sSfL --retry 3 -o "$zdir/zlib.tar.gz" "$url"; then ok=1; break; fi
    done
    [ "$ok" = 1 ] || die "could not download zlib"
    echo "$ZLIB_SHA256  $zdir/zlib.tar.gz" | sha256sum -c - >&2 || die "zlib checksum mismatch"
    tar -xzf "$zdir/zlib.tar.gz" -C "$zdir/src" --strip-components=1
    ( cd "$zdir/src" && CC="$CC_BIN" CFLAGS="-O2 -fPIC" ./configure --static > "$zdir/configure.log" 2>&1 \
        && make -j"$(nproc)" libz.a > "$zdir/make.log" 2>&1 ) || die "zlib build failed (logs in $zdir)"
    cp "$zdir/src/libz.a" "$zdir/lib/libz.a"
  fi
  log "linking against $zdir/lib/libz.a"
  build_args+=(--ni-arg -H:+UnlockExperimentalVMOptions --ni-arg "-H:CLibraryPath=$zdir/lib" --ni-arg -H:-UnlockExperimentalVMOptions)
fi

# Extra arguments of this script go to scripts/build.sh (e.g. --modules A,B).
scripts/build.sh "${build_args[@]}" "$@"

[ -x work/dist/bin/rlvm ] || die "work/dist/bin/rlvm was not produced"
log "done in $(( $(date +%s) - start )) s: $(ls -l work/dist/bin/rlvm | awk '{print $5}') bytes, $(work/dist/bin/rlvm --version | head -n 1)"
