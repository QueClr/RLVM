#!/bin/sh
# Arena-style build of the spike WITHOUT Maven/Gradle and WITHOUT the runner's JDK:
#   1. download a pinned GraalVM CE for the runner's architecture, verify sha256, unpack
#   2. download the pinned Truffle jars from Maven Central, verify sha256
#   3. javac (+ Truffle DSL annotation processor) -> lc.jar
#   4. optional: native-image (RLVM_NATIVE=1)
# Everything lands in $PWD/.build ; nothing outside the checkout is touched. Offline rebuilds work
# as long as .build/dl is kept (set RLVM_OFFLINE=1 to forbid any network access).
#
#   usage:  sh arena/build.sh <source-root>          (source-root = spikes/platform/truffle-lc)
# A checker repository would call this from its `build:` line, e.g.   build: sh arena/build.sh .
set -eu

SRC_ROOT="$(cd "${1:-.}" && pwd)"
B="$PWD/.build"
DL="$B/dl"
mkdir -p "$DL" "$B/lib" "$B/proc"

GRAAL_TAG=graal-25.5.5.0.3
GRAAL_JDK=25.0.5.0.3
TRUFFLE=25.5.5.0.3
case "$(uname -m)" in
  x86_64)  ARCH=x64;     GRAAL_SHA=c6438dee6c3ade2220d40b7ccdcad24b512d30e233e61bb4fc70519522de34ef ;;
  aarch64) ARCH=aarch64; GRAAL_SHA=41741c0650ae7564a78335f9e4fc353a902e6ff1d4140f2e4fabd1c55d5cce66 ;;
  *) echo "unsupported architecture $(uname -m)" >&2; exit 1 ;;
esac
GRAAL_FILE="graalvm-community-jdk-25i5-${GRAAL_JDK}_linux-${ARCH}_bin.tar.gz"
GRAAL_URL="https://github.com/graalvm/graalvm-ce-builds/releases/download/${GRAAL_TAG}/${GRAAL_FILE}"

fetch() { # fetch <url> <sha256> <dest>
  if [ ! -f "$3" ] || ! echo "$2  $3" | sha256sum -c - >/dev/null 2>&1; then
    [ "${RLVM_OFFLINE:-0}" = 1 ] && { echo "offline and $3 missing or corrupt" >&2; exit 1; }
    curl -sSfL --retry 3 --retry-delay 2 -o "$3.part" "$1"
    mv "$3.part" "$3"
  fi
  echo "$2  $3" | sha256sum -c - >/dev/null || { echo "checksum mismatch for $3" >&2; rm -f "$3"; exit 1; }
}

# --- 1. GraalVM ---------------------------------------------------------------------------------
if [ ! -x "$B/graalvm/bin/java" ]; then
  fetch "$GRAAL_URL" "$GRAAL_SHA" "$DL/$GRAAL_FILE"
  rm -rf "$B/graalvm" && mkdir -p "$B/graalvm"
  tar -xzf "$DL/$GRAAL_FILE" -C "$B/graalvm" --strip-components=1
fi
JAVA_HOME="$B/graalvm"
grep -q "GRAALVM_VERSION=\"$TRUFFLE\"" "$JAVA_HOME/release" || { echo "GraalVM/Truffle version mismatch" >&2; exit 1; }

# --- 2. Truffle jars (Maven Central, architecture independent) -------------------------------------
MC=https://repo1.maven.org/maven2/org/graalvm
jar() { # jar <group-path> <artifact> <sha256> <dir>
  fetch "$MC/$1/$2/$TRUFFLE/$2-$TRUFFLE.jar" "$3" "$DL/$2-$TRUFFLE.jar"
  cp "$DL/$2-$TRUFFLE.jar" "$4/"
}
jar polyglot polyglot                 51096bb48c663a82a720358d9f5f9145b9809ea3d155459211a6a05759f4f43c "$B/lib"
jar sdk      collections              694c52b4536e744fd091257d190af6ba95c78f37a2eb1cb7b5d1cfc8e7c1efca "$B/lib"
jar sdk      nativeimage              b8d337a9d413169953285b600bb1bd243f1b294a10654a324751f56b2a88d749 "$B/lib"
jar sdk      word                     11a21c7f6a132bd21c566ec4b8b397fb06656db5d293ff52966ee8582b79ddd1 "$B/lib"
jar sdk      jniutils                 1c46ac4d58434d9118026ab2f4a585102460ec07a4cfcf31973b0fb13f84268e "$B/lib"
jar truffle  truffle-api              ce6662ca5f319c329eb8867f4aa809ea3e892e07b2975bf9d364d94bdad166d0 "$B/lib"
jar truffle  truffle-runtime          a4b8df5041e6001d00019d92617cad6b7f5628a4f84d4d94b53b9e56c6b80a0e "$B/lib"
jar truffle  truffle-compiler         f12a579e489daea109d7e19d231b48fe33b2a8a34033251618ef7a54579a236d "$B/lib"
jar truffle  truffle-dsl-processor    39daafb069ea01c5ef961a04cdb6d491800b019c4ec994f8eb9ce4fb88e5f290 "$B/proc"

# --- 3. compile ----------------------------------------------------------------------------------
CP="$(ls "$B"/lib/*.jar | tr '\n' ':')"
rm -rf "$B/classes" "$B/gen" && mkdir -p "$B/classes" "$B/gen"
find "$SRC_ROOT/src/main/java" -name '*.java' | sort > "$B/sources.txt"
"$JAVA_HOME/bin/javac" --release 25 -Xlint:-options -nowarn \
  -cp "$CP" -processorpath "$B/proc/truffle-dsl-processor-$TRUFFLE.jar" \
  -s "$B/gen" -d "$B/classes" @"$B/sources.txt"
if [ -d "$SRC_ROOT/src/main/resources" ]; then cp -r "$SRC_ROOT/src/main/resources/." "$B/classes/"; fi
"$JAVA_HOME/bin/jar" --create --file "$B/lc.jar" -C "$B/classes" .

# --- 4. optional native image --------------------------------------------------------------------
if [ "${RLVM_NATIVE:-0}" = 1 ]; then
  "$JAVA_HOME/bin/native-image" -march=native -cp "$B/lc.jar:$CP" rlvm.spike.lc.Main -o "$B/lc-native" >"$B/native-image.log" 2>&1 \
    || { tail -30 "$B/native-image.log" >&2; exit 1; }
fi
echo "build ok: $B (java $("$JAVA_HOME/bin/java" -version 2>&1 | sed -n 2p))"
