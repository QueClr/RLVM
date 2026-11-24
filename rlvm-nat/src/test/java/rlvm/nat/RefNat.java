package rlvm.nat;

import java.math.BigInteger;

/**
 * Slow, obviously-correct reference for the Nat literal operations of the Lean 4 kernel (v4.36), written
 * directly from {@code type_checker.cpp} ({@code reduce_nat}, {@code reduce_pow}, {@code reduce_shiftLeft})
 * and the runtime ({@code lean_nat_*}) with {@code BigInteger} only. Shares no code with the main tree.
 *
 * <p>A result is a {@code BigInteger} (number), a {@code Boolean} (beq/ble), or one of the two markers.
 */
final class RefNat {
    private RefNat() {}

    static final String TOO_BIG = "TOO_BIG";
    static final String COUNT_TOO_BIG = "COUNT_TOO_BIG";

    static final BigInteger TWO63 = BigInteger.ONE.shiftLeft(63);
    static final BigInteger UINT_MAX = BigInteger.valueOf(0xFFFFFFFFL);

    /** {@code lean_nat_size_in_bytes}: one word for a scalar (below 2^63), else 8 bytes per GMP limb. */
    static long sizeInBytes(BigInteger n) {
        if (n.compareTo(TWO63) < 0) {
            return 8;
        }
        return 8L * ((n.bitLength() + 63) / 64);
    }

    private static Object checked(BigInteger r, long max) {
        return sizeInBytes(r) > max ? TOO_BIG : r;
    }

    static Object apply1(int op, BigInteger a, long max) {
        switch (op) {
            case NatOp.SUCC:
                return checked(a.add(BigInteger.ONE), max);
            case NatOp.LOG2:
                return a.signum() == 0 ? BigInteger.ZERO : BigInteger.valueOf(a.bitLength() - 1);
            default:
                throw new IllegalArgumentException();
        }
    }

    static Object apply2(int op, BigInteger a, BigInteger b, long max) {
        switch (op) {
            case NatOp.ADD:
                return checked(a.add(b), max);
            case NatOp.SUB:
                return checked(a.compareTo(b) <= 0 ? BigInteger.ZERO : a.subtract(b), max);
            case NatOp.MUL:
                return checked(a.multiply(b), max);
            case NatOp.DIV:
                return b.signum() == 0 ? BigInteger.ZERO : a.divide(b);
            case NatOp.MOD:
                return b.signum() == 0 ? a : a.mod(b);
            case NatOp.GCD:
                return a.gcd(b);
            case NatOp.BEQ:
                return a.equals(b);
            case NatOp.BLE:
                return a.compareTo(b) <= 0;
            case NatOp.LAND:
                return a.and(b);
            case NatOp.LOR:
                return a.or(b);
            case NatOp.XOR:
                return a.xor(b);
            case NatOp.SHIFT_LEFT: {
                if (a.signum() == 0) {
                    return BigInteger.ZERO;
                }
                if (b.compareTo(UINT_MAX) > 0) {
                    return COUNT_TOO_BIG;
                }
                long k = b.longValue();
                if (sizeInBytes(a) + k / 8 + 1 > max) {
                    return TOO_BIG;
                }
                return a.shiftLeft((int) k);
            }
            case NatOp.SHIFT_RIGHT: {
                if (b.compareTo(BigInteger.valueOf(a.bitLength())) >= 0) {
                    return BigInteger.ZERO;
                }
                return a.shiftRight(b.intValue());
            }
            case NatOp.POW: {
                if (b.compareTo(UINT_MAX) > 0) {
                    return COUNT_TOO_BIG;
                }
                long k = b.longValue();
                if (a.compareTo(BigInteger.ONE) > 0 && k != 0 && sizeInBytes(a) > max / k) {
                    return TOO_BIG;
                }
                if (a.signum() == 0) {
                    return k == 0 ? BigInteger.ONE : BigInteger.ZERO;
                }
                if (a.equals(BigInteger.ONE)) {
                    return BigInteger.ONE;
                }
                return a.pow((int) k);
            }
            default:
                throw new IllegalArgumentException();
        }
    }
}
