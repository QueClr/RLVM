#!/usr/bin/env python3
"""Mutation check for rlvm-nat: apply one small semantic change to the main sources at a time, run the
test suite, expect it to FAIL, and restore the source. Prints one line per mutant.

    python3 -I rlvm-nat/src/test/tools/mutate.py [index ...]        (about one minute per mutant)

It edits files under rlvm-nat/src/main in place (and restores them, also on Ctrl-C): do not edit or build
the module while it runs. A mutant is a (file, exact old text, new text) triple; when the source changes
the triple has to follow ("PATTERN NOT UNIQUE (0)" is reported otherwise). Known equivalent mutants, which
must survive: the `b < 64` variant of the shiftLeft fast path, the `p < to` variant of the leading-zero
loop, the `p > 8` variant of Limbs.fromBigInteger."""
import os, shutil, subprocess, sys
ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "..", ".."))
SRC = ROOT + "/rlvm-nat/src/main/java/rlvm/nat/"
MVN = [ROOT + "/.toolchain/apache-maven-3.9.16/bin/mvn", "-Dmaven.repo.local=" + ROOT + "/.toolchain/m2", "-B", "-ntp", "-o", "-q",
       "-pl", "rlvm-nat", "test"]
FAST = "-Dtest=LimbsTest,NatStoreTest,OfficialVectorsTest,NatDecimalTest,Utf8Test"
FULL = "-Dtest=LimbsTest,NatOpsDifferentialTest,NatStoreTest,OfficialVectorsTest,NatDecimalTest,Utf8Test"
ENV = dict(os.environ, JAVA_HOME=ROOT + "/.toolchain/graalvm-ce-25", PATH=ROOT + "/.toolchain/graalvm-ce-25/bin:" + os.environ["PATH"])

M = [
 ("Nat.java", "if ((a | b | r) >= 0) {\n            return r;", "if ((a | b) >= 0) {\n            return r;"),
 ("Nat.java", "return d & ~(d >> 63); // 0 if negative", "return d; // 0 if negative"),
 ("Nat.java", "if (hi == 0 && lo >= 0) {", "if (hi == 0) {"),
 ("Nat.java", "return b == 0 ? 0 : a / b;", "return b == 0 ? a : a / b;"),
 ("Nat.java", "return b == 0 ? a : a % b;", "return b == 0 ? 0 : a % b;"),
 ("Nat.java", "b < 63 && (a >>> (63 - b)) == 0", "b < 64 && (a >>> (63 - b)) == 0"),
 ("Nat.java", "(a >>> (63 - b)) == 0 && limitBytes >= 16", "(a >>> (64 - b)) == 0 && limitBytes >= 16"),
 ("Nat.java", "limitBytes >= 16) {", "limitBytes >= 8) {"),
 ("Nat.java", "return b < 64 ? a >>> b : 0;\n        }\n        return s.shiftRightSlow", "return a >>> b;\n        }\n        return s.shiftRightSlow"),
 ("Nat.java", "if (a >= 0 && a != Long.MAX_VALUE) {", "if (a >= 0) {"),
 ("Nat.java", "return a < 2 ? 0 : 63 - Long.numberOfLeadingZeros(a);", "return a < 2 ? 0 : 64 - Long.numberOfLeadingZeros(a);"),
 ("Nat.java", "if (digits <= 18) {", "if (digits <= 19) {"),
 ("Nat.java", "while (p < to - 1 && buf[p] == '0') {", "while (p < to && buf[p] == '0') {"),
 ("NatStore.java", "if (b < 0 || b > 0xFFFFFFFFL) {\n            return Nat.COUNT_TOO_BIG; // official", "if (b < 0 || b > 0x7FFFFFFFL) {\n            return Nat.COUNT_TOO_BIG; // official"),
 ("NatStore.java", "if (bytes > Math.min(limitBytes, HARD_MAX_BYTES) / b) {", "if (bytes >= Math.min(limitBytes, HARD_MAX_BYTES) / b) {"),
 ("NatStore.java", "if ((a < 0 || a > 1) && b != 0) {", "if ((a < 0 || a > 0) && b != 0) {"),
 ("NatStore.java", "if (8L * an + (b >>> 3) + 1 > Math.min(limitBytes, HARD_MAX_BYTES)) {", "if (8L * an + (b >>> 3) > Math.min(limitBytes, HARD_MAX_BYTES)) {"),
 ("NatStore.java", "return Nat.OK; // 0 <<< b = 0 for any b, also when b does not fit 32 bits", "return b < 0 ? Nat.COUNT_TOO_BIG : Nat.OK;"),
 ("NatStore.java", "if (len > maxLimbs) {\n            return Nat.TOO_BIG;", "if (len >= maxLimbs) {\n            return Nat.TOO_BIG;"),
 ("NatStore.java", "if ((long) an + bn - 1 > maxLimbs) {", "if ((long) an + bn > maxLimbs) {"),
 ("NatStore.java", "if (len == 1 && l[t] >= 0) {\n            return l[t];\n        }", "if (len == 1 && l[t] >= 0 && l[t] < 1000) {\n            return l[t];\n        }"),
 ("NatStore.java", "limbs[top + n - 1] &= (1L << tz) - 1;", "limbs[top + n - 1] &= (1L << tz);"),
 ("NatStore.java", "            t[i] = 0;\n        }\n        top = offs[mark - firstId];", "        }\n        top = offs[mark - firstId];"),
 ("NatStore.java", "        if (base != null) {\n            int id = base.find(l, t, len, h);", "        if (base != null && len > 1) {\n            int id = base.find(l, t, len, h);"),
 ("NatStore.java", "        if (a == 0) {\n            return b;\n        }\n        if (b == 0) {\n            return a;\n        }\n        if (a >= 0 && b >= 0) {\n            return Nat.gcdLong(a, b);", "        if (a == 0) {\n            return a;\n        }\n        if (b == 0) {\n            return a;\n        }\n        if (a >= 0 && b >= 0) {\n            return Nat.gcdLong(a, b);"),
 ("NatStore.java", "return b >= 0 ? a & b : a & limb0(b);", "return b >= 0 ? a & b : a;"),
 ("NatStore.java", "        if (a == b) {\n            return isXor ? 0 : a;\n        }", "        if (a == b) {\n            return a;\n        }"),
 ("NatStore.java", "            return c == 0 ? 1 : 0;\n        }\n        if (Limbs.isPow2", "            return 0;\n        }\n        if (Limbs.isPow2"),
 ("NatStore.java", "return shiftRightSlow(a, Limbs.bitLength(arrOf(ib), offOf(ib), bn) - 1);", "return shiftRightSlow(a, Limbs.bitLength(arrOf(ib), offOf(ib), bn));"),
 ("NatStore.java", "if (bits * b <= 63) {", "if (bits * b <= 64) {"),
 ("NatStore.java", "long shift = Long.numberOfTrailingZeros(a) * b;", "long shift = Long.numberOfTrailingZeros(a) * b + 1;"),
 ("NatStore.java", "if (b >= Limbs.bitLength(arrOf(ia), offOf(ia), an)) {\n            return 0;", "if (b >= 64L * an + 64) {\n            return 0;"),
 ("NatStore.java", "int maxLimbsNeeded = digits / 19 + 2;", "int maxLimbsNeeded = digits / 19 + 2;\n        if (digits == 40) { return Nat.TOO_BIG; }"),
 ("NatStore.java", "while ((18L << level) >= digits) {", "while ((18L << level) > digits) {"),
 ("Limbs.java", "borrow = ((~x & y) | ((~x | y) & t)) >>> 63;", "borrow = ((~x & y) | ((~x | y) & ~t)) >>> 63;"),
 ("Limbs.java", "carry = ((x & y) | ((x | y) & ~t)) >>> 63;", "carry = ((x & y) | ((x ^ y) & ~t)) >>> 62;"),
 ("Limbs.java", "carry = t == 0 ? 1 : 0;", "carry = 0;"),
 ("Limbs.java", "borrow = x == 0 ? 1 : 0;", "borrow = 0;"),
 ("Limbs.java", "System.arraycopy(a, ao + i, r, ro + i, an - i);\n            return an;", "System.arraycopy(a, ao + i, r, ro + i, an - i - 1);\n            return an;"),
 ("Limbs.java", "r[ro + i] = (cur >>> bs) | (next << inv);", "r[ro + i] = (cur >>> bs);"),
 ("Limbs.java", "r[p + i] = (x << bs) | carry;", "r[p + i] = (x << bs);"),
 ("Limbs.java", "carry = Math.unsignedMultiplyHigh(x, y) + (carryOut(z, lo, t) + carryOut(t, carry, u));", "carry = Math.unsignedMultiplyHigh(x, y) + carryOut(z, lo, t);"),
 ("Limbs.java", "                q0--;\n                rhat += vn1;", "                q0--;\n                rhat += vn0;"),
 ("Limbs.java", "rem = (un21 << 32) + un0 - q0 * v;", "rem = (un21 << 32) + un0 - q0 * vn1;"),
 ("Limbs.java", "if ((d >>> 32) == 0) {", "if ((d >>> 33) == 0) {"),
 ("Limbs.java", "return (int) (h ^ (h >>> 32));\n    }\n\n    private static long mix", "return (int) (h >>> 60);\n    }\n\n    private static long mix"),
 ("Limbs.java", "for (; i < n && p >= 8; i++) {", "for (; i < n && p > 8; i++) {"),
 ("Utf8.java", "if (r < 0x800 || (r >= 0xD800 && r <= 0xDFFF)) {", "if (r < 0x800) {"),
 ("Utf8.java", "if (c < 0xC2) {", "if (c < 0xC0) {"),
 ("Utf8.java", "if (r < 0x10000 || r > MAX_SCALAR) {", "if (r < 0x10000) {"),
 ("Utf8.java", "if (c < 0xF5) {\n            if (pos + 3 >= to) {", "if (c < 0xF5) {\n            if (pos + 3 > to) {"),
 ("Utf8.java", "while (p < to && (b[p] & 0xC0) == 0x80) {\n                    p++;\n                }\n            }\n        }\n        return q - dstOff;", "            }\n        }\n        return q - dstOff;"),
 ("StrLit.java", "(natHandle > 0xDFFF && natHandle < 0x110000)", "(natHandle >= 0xDFFF && natHandle < 0x110000)"),
 ("StrLit.java", "return isValidChar(natHandle) ? (int) natHandle : 0;", "return natHandle >= 0 && natHandle < 0x110000 ? (int) natHandle : 0;"),
]

import signal
signal.signal(signal.SIGTERM, lambda *a: (_ for _ in ()).throw(KeyboardInterrupt()))

def run(cmd, timeout):
    """Runs maven in its own process group; on timeout the whole group (maven and the forked test JVM) is killed."""
    p = subprocess.Popen(cmd, cwd=ROOT, env=ENV, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, start_new_session=True)
    try:
        out, _ = p.communicate(timeout=timeout)
        return p.returncode, out
    except subprocess.TimeoutExpired:
        os.killpg(p.pid, signal.SIGKILL)
        p.communicate()
        return "timeout", ""
    except BaseException:
        os.killpg(p.pid, signal.SIGKILL)
        raise

def main():
    only = [int(x) for x in sys.argv[1:]]
    survived = []
    for i, (f, old, new) in enumerate(M):
        if only and i not in only:
            continue
        path = SRC + f
        orig = open(path).read()
        if orig.count(old) != 1:
            print(f"#{i} {f}: PATTERN NOT UNIQUE ({orig.count(old)}): {old[:60]!r}", flush=True)
            continue
        try:
            open(path, "w").write(orig.replace(old, new))
            rc, out = run(MVN + [FAST], 300)
            if rc == 0:  # survived the fast classes: try the heavy differential test too
                rc, out = run(MVN + [FULL], 900)
            killed = rc != 0
            which = sorted({l.split("[ERROR]   ")[1].split(":")[0].split(".")[0] for l in out.splitlines() if l.startswith("[ERROR]   ") and ":" in l})
            if "COMPILATION ERROR" in out:
                which = ["COMPILATION ERROR"]
            if rc == "timeout":
                which = ["TIMEOUT (the tests hang)"]
            print(f"#{i} {f}: {'killed' if killed else 'SURVIVED'} {','.join(which)}   [{new.strip().splitlines()[0][:70]}]", flush=True)
            if not killed:
                survived.append(i)
        finally:
            open(path, "w").write(orig)
    print("survivors:", survived, flush=True)

main()
