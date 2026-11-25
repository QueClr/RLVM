#!/usr/bin/env bash
# scripts/fetch-toolchain.sh - download the pinned build toolchain into .toolchain/ with checksum
# verification, for the architecture of this machine (linux x86-64 or aarch64).
#
#   scripts/fetch-toolchain.sh [--check] [--force]
#   scripts/fetch-toolchain.sh --oracle --accept-gftc      (only for scripts/build.sh --oracle-pgo)
#
#   .toolchain/graalvm-ce-25         GraalVM Community Edition 25.5.5.0.3 (JDK 25.0.5.0.3), sha256-pinned
#   .toolchain/apache-maven-3.9.16   Apache Maven 3.9.16, sha512-pinned
#
# These are the paths ./env.sh expects. A component that is already present with the right version
# is left alone, so the script is cheap to call from every build (scripts/arena/build.sh does).
#   --check   only report what is present; exit 1 if something is missing
#   --force   download and unpack again even if present
#
# ORACLE GraalVM IS NEVER FETCHED BY DEFAULT (OWNER-NOTES 11 and 16). It is licensed under the GraalVM
# Free Terms and Conditions (GFTC), not an open-source licence, and only the optional build
# `scripts/build.sh --oracle-pgo` uses it. It is downloaded only by
#   --oracle --accept-gftc   .toolchain/oracle-graalvm-25: Oracle GraalVM 25.0.4, sha256-pinned; with
#                            this pair of options YOU accept the GFTC (the text is LICENSE.txt in the
#                            archive and https://www.oracle.com/downloads/licenses/graal-free-license.html).
#                            Nothing else is fetched in that call; --oracle without --accept-gftc fails
#
# Needs curl (or wget), tar, sha256sum, sha512sum. Downloads go to .toolchain/download/ and are
# deleted after unpacking.
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

# Oracle GraalVM (GFTC): only with --oracle --accept-gftc. The checksums are the ones Oracle publishes
# beside the archives (recorded in *agentic-note*: study-graalvm-platform, section 7.3).
ORACLE_VERSION="25.0.4"                          # GRAALVM_VERSION in <home>/release
ORACLE_BASE_URL="https://download.oracle.com/graalvm/25/archive"
ORACLE_FILE_PREFIX="graalvm-jdk-25.0.4_linux"
ORACLE_SHA256_x64="76007c309f821aaf435bce63162ea0395587fc77350801c81643fe7feea37276"
ORACLE_SHA256_aarch64="7031aead8da4c6a7816c4e2be4eabfddaf7f4abcfe8e5f16134c4da69a5e70de"

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
oracle=0
accept_gftc=0
for a in "$@"; do
  case "$a" in
    --check) check_only=1 ;;
    --force) force=1 ;;
    --oracle) oracle=1 ;;
    --accept-gftc) accept_gftc=1 ;;
    -h|--help) sed -n '2,28p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) die "unknown option: $a" ;;
  esac
done
[ "$accept_gftc" = 0 ] || [ "$oracle" = 1 ] || die "--accept-gftc belongs to --oracle"

[ "$(uname -s)" = Linux ] || die "only Linux is supported (this is $(uname -s))"
case "$(uname -m)" in
  x86_64|amd64) arch=x64; graalvm_sha="$GRAALVM_SHA256_x64"; oracle_sha="$ORACLE_SHA256_x64" ;;
  aarch64|arm64) arch=aarch64; graalvm_sha="$GRAALVM_SHA256_aarch64"; oracle_sha="$ORACLE_SHA256_aarch64" ;;
  *) die "unsupported architecture: $(uname -m)" ;;
esac
graalvm_file="${GRAALVM_FILE_PREFIX}-${arch}_bin.tar.gz"

have_graalvm() {
  [ -x "$TC/graalvm-ce-25/bin/native-image" ] \
    && grep -q "^GRAALVM_VERSION=\"\\{0,1\\}$GRAALVM_VERSION\"\\{0,1\\}\$" "$TC/graalvm-ce-25/release" 2>/dev/null \
    && grep -q "^OS_ARCH=\"\\{0,1\\}$(uname -m | sed 's/amd64/x86_64/; s/arm64/aarch64/')\"\\{0,1\\}\$" "$TC/graalvm-ce-25/release" 2>/dev/null
}
have_maven() { [ -x "$TC/apache-maven-$MAVEN_VERSION/bin/mvn" ]; }
have_oracle() {
  [ -x "$TC/oracle-graalvm-25/bin/native-image" ] \
    && grep -q "^GRAALVM_VERSION=\"\\{0,1\\}$ORACLE_VERSION\"\\{0,1\\}\$" "$TC/oracle-graalvm-25/release" 2>/dev/null
}

if [ "$check_only" = 1 ]; then
  rc=0
  if have_graalvm; then echo "GraalVM CE $GRAALVM_VERSION ($arch): present"; else echo "GraalVM CE $GRAALVM_VERSION ($arch): MISSING"; rc=1; fi
  if have_maven; then echo "Maven $MAVEN_VERSION: present"; else echo "Maven $MAVEN_VERSION: MISSING"; rc=1; fi
  if [ "$oracle" = 1 ]; then
    if have_oracle; then echo "Oracle GraalVM $ORACLE_VERSION (GFTC; --oracle-pgo builds only): present"; else echo "Oracle GraalVM $ORACLE_VERSION (GFTC; --oracle-pgo builds only): MISSING"; rc=1; fi
  fi
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

if [ "$oracle" = 1 ]; then
  # Only on request, and only with the explicit acceptance of the licence; nothing else in this call.
  echo "fetch-toolchain.sh: --oracle: Oracle GraalVM $ORACLE_VERSION is licensed under the GraalVM Free Terms and Conditions (GFTC); it is used by 'scripts/build.sh --oracle-pgo' only, never by a default build." >&2
  if [ "$force" = 0 ] && have_oracle; then
    log "Oracle GraalVM $ORACLE_VERSION ($arch) already present in $TC/oracle-graalvm-25"
    exit 0
  fi
  [ "$accept_gftc" = 1 ] || die "--oracle downloads Oracle GraalVM only together with --accept-gftc (by passing it you accept the GraalVM Free Terms and Conditions)"
  oracle_file="${ORACLE_FILE_PREFIX}-${arch}_bin.tar.gz"
  f="$TC/download/$oracle_file"
  download "$f" "$ORACLE_BASE_URL/$oracle_file" || die "could not download Oracle GraalVM"
  echo "$oracle_sha  $f" | sha256sum -c - >&2 || { rm -f "$f"; die "Oracle GraalVM checksum mismatch"; }
  unpack "$f" "$TC/oracle-graalvm-25"
  rm -f "$f"
  have_oracle || die "unpacked Oracle GraalVM is not version $ORACLE_VERSION"
  log "installed $TC/oracle-graalvm-25 (Oracle GraalVM $ORACLE_VERSION; licence: $TC/oracle-graalvm-25/LICENSE.txt)"
  rmdir "$TC/download" 2>/dev/null || true
  exit 0
fi

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
