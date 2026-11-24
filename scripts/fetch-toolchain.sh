#!/usr/bin/env bash
# scripts/fetch-toolchain.sh - download the pinned build toolchain into .toolchain/ with checksum
# verification, for the architecture of this machine (linux x86-64 or aarch64).
#
#   scripts/fetch-toolchain.sh [--check] [--force]
#
#   .toolchain/graalvm-ce-25         GraalVM Community Edition 25.5.5.0.3 (JDK 25.0.5.0.3), sha256-pinned
#   .toolchain/apache-maven-3.9.16   Apache Maven 3.9.16, sha512-pinned
#
# These are the paths ./env.sh expects. A component that is already present with the right version
# is left alone, so the script is cheap to call from every build (scripts/arena/build.sh does).
#   --check   only report what is present; exit 1 if something is missing
#   --force   download and unpack again even if present
#
# Needs curl (or wget), tar, sha256sum, sha512sum. Downloads go to .toolchain/download/ and are
# deleted after unpacking. Oracle GraalVM (for scripts/build.sh --pgo) is not fetched here: it is
# under the GFTC licence and the arena build does not use it.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
TC="$ROOT/.toolchain"

# --- pins ---------------------------------------------------------------------------------------
GRAALVM_VERSION="25.5.5.0.3"                     # GRAALVM_VERSION in <home>/release; = Truffle version
GRAALVM_TAG="graal-25.5.5.0.3"
GRAALVM_FILE_PREFIX="graalvm-community-jdk-25i5-25.0.5.0.3_linux"
GRAALVM_BASE_URL="https://github.com/graalvm/graalvm-ce-builds/releases/download/$GRAALVM_TAG"
GRAALVM_SHA256_x64="c6438dee6c3ade2220d40b7ccdcad24b512d30e233e61bb4fc70519522de34ef"
GRAALVM_SHA256_aarch64="41741c0650ae7564a78335f9e4fc353a902e6ff1d4140f2e4fabd1c55d5cce66"

MAVEN_VERSION="3.9.16"
MAVEN_FILE="apache-maven-$MAVEN_VERSION-bin.tar.gz"
MAVEN_URLS=(
  "https://archive.apache.org/dist/maven/maven-3/$MAVEN_VERSION/binaries/$MAVEN_FILE"
  "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/$MAVEN_VERSION/$MAVEN_FILE"
  "https://dlcdn.apache.org/maven/maven-3/$MAVEN_VERSION/binaries/$MAVEN_FILE"
)
MAVEN_SHA512="831a8591fe20c8243b1dbe7d71e3244f31d1665b0804b2e825e38cbbe5ce0cafb8338851f90780735568773e0a6cd07bbec107cda0b896b008b861075358b6f6"
# ------------------------------------------------------------------------------------------------

die() { echo "fetch-toolchain.sh: $*" >&2; exit 1; }
log() { echo "fetch-toolchain.sh: $*" >&2; }

check_only=0
force=0
for a in "$@"; do
  case "$a" in
    --check) check_only=1 ;;
    --force) force=1 ;;
    -h|--help) sed -n '2,19p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) die "unknown option: $a" ;;
  esac
done

[ "$(uname -s)" = Linux ] || die "only Linux is supported (this is $(uname -s))"
case "$(uname -m)" in
  x86_64|amd64) arch=x64; graalvm_sha="$GRAALVM_SHA256_x64" ;;
  aarch64|arm64) arch=aarch64; graalvm_sha="$GRAALVM_SHA256_aarch64" ;;
  *) die "unsupported architecture: $(uname -m)" ;;
esac
graalvm_file="${GRAALVM_FILE_PREFIX}-${arch}_bin.tar.gz"

have_graalvm() {
  [ -x "$TC/graalvm-ce-25/bin/native-image" ] \
    && grep -q "^GRAALVM_VERSION=\"\\{0,1\\}$GRAALVM_VERSION\"\\{0,1\\}\$" "$TC/graalvm-ce-25/release" 2>/dev/null \
    && grep -q "^OS_ARCH=\"\\{0,1\\}$(uname -m | sed 's/amd64/x86_64/; s/arm64/aarch64/')\"\\{0,1\\}\$" "$TC/graalvm-ce-25/release" 2>/dev/null
}
have_maven() { [ -x "$TC/apache-maven-$MAVEN_VERSION/bin/mvn" ]; }

if [ "$check_only" = 1 ]; then
  rc=0
  if have_graalvm; then echo "GraalVM CE $GRAALVM_VERSION ($arch): present"; else echo "GraalVM CE $GRAALVM_VERSION ($arch): MISSING"; rc=1; fi
  if have_maven; then echo "Maven $MAVEN_VERSION: present"; else echo "Maven $MAVEN_VERSION: MISSING"; rc=1; fi
  exit "$rc"
fi

download() { # destination url...
  local dest="$1" url; shift
  for url in "$@"; do
    log "downloading $url"
    if command -v curl >/dev/null 2>&1; then
      if curl -sSfL --retry 3 --retry-delay 2 -o "$dest.part" "$url"; then mv "$dest.part" "$dest"; return 0; fi
    elif command -v wget >/dev/null 2>&1; then
      if wget -q -O "$dest.part" "$url"; then mv "$dest.part" "$dest"; return 0; fi
    else
      die "neither curl nor wget is available"
    fi
    log "failed: $url"
  done
  rm -f "$dest.part"
  return 1
}

# Unpacks a tarball whose content is one top-level directory into $2, replacing it.
unpack() {
  local tarball="$1" target="$2" tmp="$2.unpack.$$"
  rm -rf "$tmp"; mkdir -p "$tmp"
  tar -xzf "$tarball" -C "$tmp" --strip-components=1
  rm -rf "$target"
  mv "$tmp" "$target"
}

mkdir -p "$TC/download"

if [ "$force" = 1 ] || ! have_graalvm; then
  f="$TC/download/$graalvm_file"
  download "$f" "$GRAALVM_BASE_URL/$graalvm_file" || die "could not download GraalVM"
  echo "$graalvm_sha  $f" | sha256sum -c - >&2 || { rm -f "$f"; die "GraalVM checksum mismatch"; }
  unpack "$f" "$TC/graalvm-ce-25"
  rm -f "$f"
  have_graalvm || die "unpacked GraalVM is not version $GRAALVM_VERSION for $(uname -m)"
  log "installed $TC/graalvm-ce-25 ($("$TC/graalvm-ce-25/bin/java" -version 2>&1 | sed -n 2p))"
else
  log "GraalVM CE $GRAALVM_VERSION ($arch) already present"
fi

if [ "$force" = 1 ] || ! have_maven; then
  f="$TC/download/$MAVEN_FILE"
  download "$f" "${MAVEN_URLS[@]}" || die "could not download Maven"
  echo "$MAVEN_SHA512  $f" | sha512sum -c - >&2 || { rm -f "$f"; die "Maven checksum mismatch"; }
  unpack "$f" "$TC/apache-maven-$MAVEN_VERSION"
  rm -f "$f"
  have_maven || die "unpacked Maven has no bin/mvn"
  log "installed $TC/apache-maven-$MAVEN_VERSION"
else
  log "Maven $MAVEN_VERSION already present"
fi
rmdir "$TC/download" 2>/dev/null || true
