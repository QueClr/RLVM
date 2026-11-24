package rlvm.nat.bench;

import java.math.BigInteger;
import java.util.Random;
import rlvm.nat.Nat;
import rlvm.nat.NatStore;

/**
 * Micro-benchmark of the Nat literal operations, built for instruction counting from outside:
 * {@code NatBench <case> <iterations> [<warm-up iterations>]} runs one operation in a tight loop over 256
 * operand pairs (after the warm-up) and prints a checksum. The per-operation cost is the slope between two runs with different iteration counts, minus the
 * slope of the {@code baseline} case (loop, two array loads, checksum); JVM start-up and JIT compilation
 * cancel. See bench/modules/rlvm-nat/run-bench.sh.
 *
 * <p>Cases: {@code nat.<op>.<shape>} is the product ({@link Nat} on a {@link NatStore} overlay, results
 * interned, the overlay truncated every 256 operations); {@code big.<op>.<shape>} is the same work with the
 * {@link BigIntegerStore} alternative; {@code jdk.<op>.<shape>} is the bare {@code BigInteger} operation
 * without any store.
 */
public final class NatBench {
    private NatBench() {}

    private static final int PAIRS = 256;

    /** One benchmarked operation on handles of a store of type S. */
    private interface Op<S> {
        long apply(S s, long a, long b);
    }

    private interface RawOp {
        BigInteger apply(BigInteger a, BigInteger b);
    }

    // ------------------------------------------------------------------ operand shapes

    private static BigInteger exactBits(Random r, int bits) {
        return new BigInteger(bits, r).setBit(bits - 1);
    }

    /** Operand pairs for a shape; index 0 = left operands, 1 = right operands. */
    private static BigInteger[][] operands(String op, String shape) {
        Random r = new Random(0xC0FFEE);
        BigInteger[][] p = new BigInteger[2][PAIRS];
        BigInteger gf3Ci = BigInteger.ZERO; // perf/gf3-matrix-product: one bit per column block, rep1 d d
        if (shape.equals("gf3")) {
            for (int k = 0; k < 781; k++) {
                gf3Ci = gf3Ci.setBit(k * 781);
            }
        }
        for (int i = 0; i < PAIRS; i++) {
            BigInteger a;
            BigInteger b;
            switch (shape) {
                case "small": // both operands and the result below 2^63: the long fast path
                    switch (op) {
                        case "mul": a = exactBits(r, i % 2 == 0 ? 31 : 20); b = exactBits(r, i % 2 == 0 ? 31 : 40); break;
                        case "mulw": a = exactBits(r, 20); b = exactBits(r, 40); break; // one operand above 2^31
                        case "div": case "mod": a = exactBits(r, 62); b = exactBits(r, 20); break;
                        case "shl": a = exactBits(r, 20); b = BigInteger.valueOf(r.nextInt(41)); break;
                        case "shr": a = exactBits(r, 62); b = BigInteger.valueOf(r.nextInt(64)); break;
                        case "pow": a = BigInteger.valueOf(2 + r.nextInt(9)); b = BigInteger.valueOf(r.nextInt(19)); break;
                        case "gcd": a = exactBits(r, 62); b = exactBits(r, 62); break;
                        default: a = exactBits(r, 62); b = exactBits(r, 62); break;
                    }
                    break;
                case "ovf": // word-sized operands whose result leaves the long range
                    if (op.equals("mul")) {
                        a = exactBits(r, 40);
                        b = exactBits(r, 40);
                    } else {
                        a = exactBits(r, 63);
                        b = exactBits(r, 63);
                    }
                    break;
                case "u64": // the UInt64 pattern: a 128-bit value against 2^64
                    a = exactBits(r, 128);
                    b = BigInteger.ONE.shiftLeft(64);
                    break;
                case "gf3": { // perf/gf3-matrix-product: 609961-bit bit-sliced matrices, d = 781
                    int n = 609961;
                    int d = 781;
                    BigInteger dense = exactBits(r, n);
                    BigInteger ci = gf3Ci;
                    switch (op) {
                        case "mul": a = dense.and(ci).setBit(n - d); b = exactBits(r, d); break; // sparse x 781 bits
                        case "land": a = dense; b = ci; break;
                        case "shr": a = dense; b = BigInteger.valueOf(r.nextInt(d * d)); break;
                        case "shl": a = dense; b = BigInteger.valueOf(n); break;
                        default: a = dense; b = exactBits(r, n - r.nextInt(64)); break;
                    }
                    break;
                }
                default: {
                    boolean mixed = shape.endsWith("s"); // e.g. 256s: big against a word-sized operand
                    int bits = Integer.parseInt(mixed ? shape.substring(0, shape.length() - 1) : shape);
                    a = exactBits(r, bits);
                    if (mixed) {
                        b = exactBits(r, 30).setBit(0);
                    } else if (op.equals("div") || op.equals("mod")) {
                        b = exactBits(r, bits / 2).setBit(0);
                    } else if (op.equals("shl") || op.equals("shr")) {
                        b = BigInteger.valueOf(1 + r.nextInt(bits - 2));
                    } else if (op.equals("pow")) {
                        b = BigInteger.valueOf(2 + r.nextInt(3));
                    } else {
                        b = exactBits(r, bits);
                    }
                    break;
                }
            }
            p[0][i] = a;
            p[1][i] = b;
        }
        return p;
    }

    // ------------------------------------------------------------------ loops

    private static long runNat(Op<NatStore> op, NatStore s, long[] a, long[] b, long iters) {
        long acc = 0;
        for (long done = 0; done < iters; done += PAIRS) {
            int mark = s.mark();
            for (int k = 0; k < PAIRS; k++) {
                acc += op.apply(s, a[k], b[k]);
            }
            s.truncate(mark);
        }
        return acc;
    }

    private static long runBig(Op<BigIntegerStore> op, BigIntegerStore s, long[] a, long[] b, long iters) {
        long acc = 0;
        for (long done = 0; done < iters; done += PAIRS) {
            int mark = s.mark();
            for (int k = 0; k < PAIRS; k++) {
                acc += op.apply(s, a[k], b[k]);
            }
            s.truncate(mark);
        }
        return acc;
    }

    private static long runRaw(RawOp op, BigInteger[] a, BigInteger[] b, long iters) {
        long acc = 0;
        for (long done = 0; done < iters; done += PAIRS) {
            for (int k = 0; k < PAIRS; k++) {
                acc += op.apply(a[k], b[k]).bitLength();
            }
        }
        return acc;
    }

    private static long runBaseline(long[] a, long[] b, long iters) {
        long acc = 0;
        for (long done = 0; done < iters; done += PAIRS) {
            for (int k = 0; k < PAIRS; k++) {
                acc += a[k] ^ b[k];
            }
        }
        return acc;
    }

    // ------------------------------------------------------------------ operations

    private static Op<NatStore> natOp(String op) {
        switch (op) {
            case "add": return Nat::add;
            case "sub": return Nat::sub;
            case "mul": case "mulw": return Nat::mul;
            case "div": return Nat::div;
            case "mod": return Nat::mod;
            case "gcd": return Nat::gcd;
            case "land": return Nat::land;
            case "lor": return Nat::lor;
            case "xor": return Nat::xor;
            case "shl": return (s, a, b) -> Nat.shiftLeft(s, a, b);
            case "shr": return Nat::shiftRight;
            case "pow": return (s, a, b) -> Nat.pow(s, a, b);
            case "beq": return (s, a, b) -> Nat.beq(s, a, b) ? 1 : 0;
            case "ble": return (s, a, b) -> Nat.ble(s, a, b) ? 1 : 0;
            case "succ": return (s, a, b) -> Nat.succ(s, a);
            case "log2": return (s, a, b) -> Nat.log2(s, a);
            default: throw new IllegalArgumentException(op);
        }
    }

    private static RawOp rawOp(String op) {
        switch (op) {
            case "add": return BigInteger::add;
            case "sub": return (a, b) -> a.compareTo(b) <= 0 ? BigInteger.ZERO : a.subtract(b);
            case "mul": return BigInteger::multiply;
            case "div": return (a, b) -> b.signum() == 0 ? BigInteger.ZERO : a.divide(b);
            case "mod": return (a, b) -> b.signum() == 0 ? a : a.mod(b);
            case "gcd": return BigInteger::gcd;
            case "land": return BigInteger::and;
            case "lor": return BigInteger::or;
            case "xor": return BigInteger::xor;
            case "shl": return (a, b) -> a.shiftLeft(b.intValue());
            case "shr": return (a, b) -> a.shiftRight(b.intValue());
            case "pow": return (a, b) -> a.pow(b.intValue());
            case "succ": return (a, b) -> a.add(BigInteger.ONE);
            default: throw new IllegalArgumentException(op);
        }
    }

    private static Op<BigIntegerStore> bigOp(String op) {
        if (op.equals("beq")) {
            return (s, a, b) -> a == b ? 1 : 0;
        }
        if (op.equals("ble")) {
            return (s, a, b) -> s.get(a).compareTo(s.get(b)) <= 0 ? 1 : 0;
        }
        RawOp raw = rawOp(op);
        return (s, a, b) -> s.intern(raw.apply(s.get(a), s.get(b)));
    }

    // ------------------------------------------------------------------ main

    /** Calls of the loop method before the measured one; each runs {@code warmup / WARM_CALLS} iterations. */
    private static final int WARM_CALLS = 20;

    public static void main(String[] args) {
        String[] c = args[0].split("\\.");
        long iters = Long.parseLong(args[1]);
        // Warm-up: the same loop method is called WARM_CALLS times first, so that the measured call runs
        // normally compiled code from its first iteration (no OSR, no late deoptimisation of the loop).
        long warm = (args.length > 2 ? Long.parseLong(args[2]) : 0) / WARM_CALLS;
        long t0;
        long acc;
        if (c[0].equals("baseline")) {
            BigInteger[][] p = operands("add", "small");
            long[] a = new long[PAIRS];
            long[] b = new long[PAIRS];
            for (int i = 0; i < PAIRS; i++) {
                a[i] = p[0][i].longValue();
                b[i] = p[1][i].longValue();
            }
            for (int w = 0; w < WARM_CALLS && warm > 0; w++) {
                runBaseline(a, b, warm);
            }
            t0 = System.nanoTime();
            acc = runBaseline(a, b, iters);
        } else {
            BigInteger[][] p = operands(c[1], c[2]);
            long[] a = new long[PAIRS];
            long[] b = new long[PAIRS];
            switch (c[0]) {
                case "nat": {
                    NatStore base = NatStore.newBase();
                    for (int i = 0; i < PAIRS; i++) {
                        a[i] = Nat.ofBigInteger(base, p[0][i]);
                        b[i] = Nat.ofBigInteger(base, p[1][i]);
                    }
                    base.freeze();
                    NatStore overlay = base.newOverlay();
                    Op<NatStore> op = natOp(c[1]);
                    for (int w = 0; w < WARM_CALLS && warm > 0; w++) {
                        runNat(op, overlay, a, b, warm);
                    }
                    t0 = System.nanoTime();
                    acc = runNat(op, overlay, a, b, iters);
                    break;
                }
                case "big": {
                    BigIntegerStore s = new BigIntegerStore();
                    for (int i = 0; i < PAIRS; i++) {
                        a[i] = s.intern(p[0][i]);
                        b[i] = s.intern(p[1][i]);
                    }
                    Op<BigIntegerStore> op = bigOp(c[1]);
                    for (int w = 0; w < WARM_CALLS && warm > 0; w++) {
                        runBig(op, s, a, b, warm);
                    }
                    t0 = System.nanoTime();
                    acc = runBig(op, s, a, b, iters);
                    break;
                }
                case "jdk": {
                    RawOp op = rawOp(c[1]);
                    for (int w = 0; w < WARM_CALLS && warm > 0; w++) {
                        runRaw(op, p[0], p[1], warm);
                    }
                    t0 = System.nanoTime();
                    acc = runRaw(op, p[0], p[1], iters);
                    break;
                }
                default:
                    throw new IllegalArgumentException(args[0]);
            }
        }
        System.out.println(args[0] + " iters=" + iters + " checksum=" + acc + " loop_ms=" + (System.nanoTime() - t0) / 1_000_000);
    }
}
