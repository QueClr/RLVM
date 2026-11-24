#!/usr/bin/env bash
# Download Oracle GraalVM 25.0.4 (GraalVM Free Terms and Conditions licence - read LICENSE.txt in the
# archive before use) for this machine's architecture into .toolchain/oracle-graalvm-25, verifying
# the published sha256. Needed only for the PGO / G1 / Graal-EE measurements.
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/../env.sh"
case "$(uname -m)" in
  x86_64)  A=x64;     SHA=76007c309f821aaf435bce63162ea0395587fc77350801c81643fe7feea37276 ;;
  aarch64) A=aarch64; SHA=7031aead8da4c6a7816c4e2be4eabfddaf7f4abcfe8e5f16134c4da69a5e70de ;;
  *) echo "unsupported arch" >&2; exit 1 ;;
esac
F="graalvm-jdk-25.0.4_linux-${A}_bin.tar.gz"
D="$RLVM_ROOT/.toolchain/oracle-graalvm-dl"
mkdir -p "$D" "$GRAAL_EE"
[ -f "$D/$F" ] || curl -sSfL --retry 3 -o "$D/$F" "https://download.oracle.com/graalvm/25/archive/$F"
echo "$SHA  $D/$F" | sha256sum -c -
tar -xzf "$D/$F" -C "$GRAAL_EE" --strip-components=1
rm -f "$D/$F"
"$GRAAL_EE/bin/java" -version
