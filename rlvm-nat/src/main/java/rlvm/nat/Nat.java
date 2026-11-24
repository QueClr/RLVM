package rlvm.nat;

import java.math.BigInteger;

/**
 * Natural numbers as {@code long} handles, and the literal operations of the Lean 4 kernel (v4.36).
 *
 * <h2>Handles</h2>
 * <pre>
 *   h &gt;= 0                         the value h itself (every natural below 2^63); no store involved
 *   h = BIG | id, 1 &lt;= id &lt; 2^31    the value with that id in a {@link NatStore} (always &gt;= 2^63)
 *   any other negative h           not a value: {@link #NONE}, {@link #TOO_BIG}, {@link #COUNT_TOO_BIG},
 *                                  {@link #MALFORMED}
 * </pre>
 * The encoding is canonical: a value below 2^63 is never stored and a stored value has one id per store
 * view (base + one overlay). Hence {@code a == b} on handles of one view is equality of the numbers, a
 * handle can be used directly as a hash key, and the split "small / big" coincides with the official
 * runtime's "scalar / mpz" split on 64-bit platforms (which the size limit is defined in terms of).
 *
 * <h2>Operations</h2>
 * Each operation is a static method with the {@code long} fast path inline (exact overflow detection, no
 * store access, no allocation) and a call into the store for everything else. A caller that specialises on
 * the fast path itself (e.g. a Truffle node with {@code (a | b | (a + b)) >= 0} as its guard) can call the
 * same static method on its slow path, behind its own boundary. Semantics and
 * guards are those of {@code type_checker::reduce_nat} in v4.36 and of the runtime functions it calls:
 * <ul>
 *   <li>{@code succ add sub mul}: exact, {@code sub} truncated at 0; the <em>result</em> is refused with
 *       {@link #TOO_BIG} if it is larger than the store's {@link NatStore#maxBytes()} (official: "the
 *       kernel refused a Nat numeral because its size exceeds the maximum", a kernel exception);</li>
 *   <li>{@code div mod gcd land lor xor shiftRight beq ble}: no guard; {@code x / 0 = 0}, {@code x % 0 = x},
 *       {@code gcd 0 b = b}, a shift count beyond the operand yields 0 whatever its size;</li>
 *   <li>{@code pow shiftLeft}: guarded <em>before</em> computing, see {@link #pow} and {@link #shiftLeft};
 *       the limit is a parameter.</li>
 * </ul>
 * The size of a numeral is that of the official runtime: 8 bytes below 2^63, else 8 bytes per 64-bit limb.
 * {@code log2}, {@code pred} and {@code compare} are utilities; the v4.36 kernel does not accelerate them.
 *
 * <p>Operands must be values or error handles. Error handles propagate through the value-returning
 * operations (first operand first); the predicates and queries throw {@link IllegalArgumentException} on
 * them, as does every operation on a handle that is not live in the given store.
 */
public final class Nat {
    private Nat() {}

    /** Tag bit of every non-small handle. */
    public static final long BIG = Long.MIN_VALUE;
    static final long STATUS_MASK = 0x7FFF_FFFF_0000_0000L;
    /** One above the largest big handle. */
    private static final long BIG_END = BIG + (1L << 31);

    /** "No value". Never produced by an operation; free for the caller's use. */
    public static final long NONE = BIG;
    /** The numeral size limit was exceeded (official kernel: kernel exception, declaration rejected). */
    public static final long TOO_BIG = BIG | (1L << 32);
    /**
     * The second argument of {@code Nat.pow} / {@code Nat.shiftLeft} does not fit an unsigned 32-bit integer
     * (official kernel: kernel exception, declaration rejected).
     */
    public static final long COUNT_TOO_BIG = BIG | (2L << 32);
    /** The text is not a decimal numeral. */
    public static final long MALFORMED = BIG | (3L << 32);

    /** Result of the guard functions when the operation may be computed. */
    public static final long OK = 0;

    /** Default numeral size limit of the official kernel (v4.35+), {@code LEAN_NAT_MAX_SIZE}: 128 MiB. */
    public static final long OFFICIAL_MAX_BYTES = NatStore.OFFICIAL_MAX_BYTES;

    // ------------------------------------------------------------------ handle tests

    /** True for a value below 2^63. */
    public static boolean isSmall(long h) {
        return h >= 0;
    }

    /** True for a value stored in a {@link NatStore} (at least 2^63). */
    public static boolean isBig(long h) {
        return h > BIG && h < BIG_END;
    }

    /** True for a number, small or big. */
    public static boolean isValue(long h) {
        return h > BIG && h < BIG_END || h >= 0;
    }

    /** True for {@link #NONE} and the error handles. */
    public static boolean isError(long h) {
        return h < 0 && !(h > BIG && h < BIG_END);
    }

    /** Store id of a big handle. */
    public static int bigId(long h) {
        return (int) h;
    }

    /** Big handle of a store id. */
    public static long ofBigId(int id) {
        return BIG | id;
    }

    // ------------------------------------------------------------------ succ / pred / zero views

    /** {@code Nat.succ}: {@code a + 1}; {@link #TOO_BIG} if the result exceeds the store's limit. */
    public static long succ(NatStore s, long a) {
        if (a >= 0 && a != Long.MAX_VALUE) {
            return a + 1;
        }
        return s.succSlow(a);
    }

    /**
     * {@code a - 1}, and 0 for 0: the predecessor used to view a literal {@code n > 0} as
     * {@code Nat.succ (n - 1)}. Not size-checked (the result is smaller than the operand).
     */
    public static long pred(NatStore s, long a) {
        if (a > 0) {
            return a - 1;
        }
        return s.predSlow(a);
    }

    /** True iff the handle is the number zero. */
    public static boolean isZero(long h) {
        return h == 0;
    }

    // ------------------------------------------------------------------ add / sub / mul

    /** {@code Nat.add}; {@link #TOO_BIG} if the result exceeds the store's limit. */
    public static long add(NatStore s, long a, long b) {
        long r = a + b;
        if ((a | b | r) >= 0) {
            return r;
        }
        return s.addSlow(a, b);
    }

    /** {@code Nat.sub}: {@code a - b}, 0 if {@code a <= b}. */
    public static long sub(NatStore s, long a, long b) {
        if ((a | b) >= 0) {
            long d = a - b; // no overflow: both are in [0, 2^63)
            return d & ~(d >> 63); // 0 if negative; branch-free, the sign is data dependent
        }
        return s.subSlow(a, b);
    }

    /** {@code Nat.mul}; {@link #TOO_BIG} if the result exceeds the store's limit. */
    public static long mul(NatStore s, long a, long b) {
        if ((a | b) >= 0) {
            // A cheaper test for "both below 2^31" in front of this one was measured and saves nothing.
            long hi = Math.multiplyHigh(a, b);
            long lo = a * b;
            if (hi == 0 && lo >= 0) {
                return lo;
            }
        }
        return s.mulSlow(a, b);
    }

    // ------------------------------------------------------------------ div / mod / gcd

    /** {@code Nat.div}: floor division, {@code a / 0 = 0}. */
    public static long div(NatStore s, long a, long b) {
        if ((a | b) >= 0) {
            return b == 0 ? 0 : a / b;
        }
        return s.divSlow(a, b);
    }

    /** {@code Nat.mod}: remainder, {@code a % 0 = a}. */
    public static long mod(NatStore s, long a, long b) {
        if ((a | b) >= 0) {
            return b == 0 ? a : a % b;
        }
        return s.modSlow(a, b);
    }

    /** {@code Nat.gcd}: {@code gcd 0 b = b}, {@code gcd a 0 = a}. */
    public static long gcd(NatStore s, long a, long b) {
        if ((a | b) >= 0) {
            return gcdLong(a, b);
        }
        return s.gcdSlow(a, b);
    }

    /** Euclid on non-negative longs. */
    static long gcdLong(long a, long b) {
        while (b != 0) {
            long t = a % b;
            a = b;
            b = t;
        }
        return a;
    }

    // ------------------------------------------------------------------ predicates and comparison

    /** {@code Nat.beq}. Handles are canonical, so this is handle equality after validation. */
    public static boolean beq(NatStore s, long a, long b) {
        if ((a | b) < 0) {
            requireValues(s, a, b);
        }
        return a == b;
    }

    /** {@code Nat.ble}: {@code a <= b}. */
    public static boolean ble(NatStore s, long a, long b) {
        if ((a | b) >= 0) {
            return a <= b;
        }
        return compare(s, a, b) <= 0;
    }

    /** {@code a < b} ({@code Nat.blt}; not accelerated by the v4.36 kernel). */
    public static boolean blt(NatStore s, long a, long b) {
        if ((a | b) >= 0) {
            return a < b;
        }
        return compare(s, a, b) < 0;
    }

    /** Three-way comparison of two values: negative, zero or positive. */
    public static int compare(NatStore s, long a, long b) {
        if ((a | b) >= 0) {
            return Long.compare(a, b);
        }
        requireValues(s, a, b);
        return s.compareSlow(a, b);
    }

    private static void requireValues(NatStore s, long a, long b) {
        s.requireValues(a, b); // rejects error handles and ids that are not live
    }

    // ------------------------------------------------------------------ bitwise

    /** {@code Nat.land}. */
    public static long land(NatStore s, long a, long b) {
        if ((a | b) >= 0) {
            return a & b;
        }
        return s.landSlow(a, b);
    }

    /** {@code Nat.lor}. */
    public static long lor(NatStore s, long a, long b) {
        if ((a | b) >= 0) {
            return a | b;
        }
        return s.lorSlow(a, b);
    }

    /** {@code Nat.xor}. */
    public static long xor(NatStore s, long a, long b) {
        if ((a | b) >= 0) {
            return a ^ b;
        }
        return s.xorSlow(a, b);
    }

    // ------------------------------------------------------------------ shifts

    /** {@code Nat.shiftLeft} with the store's limit; see {@link #shiftLeft(NatStore, long, long, long)}. */
    public static long shiftLeft(NatStore s, long a, long b) {
        return shiftLeft(s, a, b, s.maxBytes());
    }

    /**
     * {@code Nat.shiftLeft a b = a * 2^b} under the official guard, with the limit as a parameter:
     * <pre>
     *   a = 0                                       -&gt; 0, for any b (even one that is not a machine word)
     *   b &gt; 2^32 - 1                                -&gt; COUNT_TOO_BIG
     *   size(a) + b / 8 + 1 &gt; limitBytes            -&gt; TOO_BIG          (integer division)
     *   otherwise                                   -&gt; the result (not size-checked again)
     * </pre>
     * With {@code limitBytes = } {@link #OFFICIAL_MAX_BYTES} both error results are kernel exceptions in the
     * official kernel. A smaller limit is a resource policy of the caller, who then decides what an error
     * means (leave the application stuck, or decline); {@link #shiftLeftGuard} tells the two apart without
     * computing. Limits above {@link NatStore#HARD_MAX_BYTES} are treated as that.
     */
    public static long shiftLeft(NatStore s, long a, long b, long limitBytes) {
        // 0 <= b <= 62 and no bit of a shifted out of the low 63: size(a) = 8 and b / 8 <= 7.
        if (a >= 0 && b >= 0 && b < 63 && (a >>> (63 - b)) == 0 && limitBytes >= 16) {
            return a << b;
        }
        return s.shiftLeftSlow(a, b, limitBytes);
    }

    /**
     * The guard of {@link #shiftLeft(NatStore, long, long, long)} alone: {@link #OK}, {@link #COUNT_TOO_BIG}
     * or {@link #TOO_BIG} (or an error operand). Constant time.
     */
    public static long shiftLeftGuard(NatStore s, long a, long b, long limitBytes) {
        return s.shiftLeftGuard(a, b, limitBytes);
    }

    /** {@code Nat.shiftRight a b = floor(a / 2^b)}; 0 once {@code b} reaches the bit length, however large. */
    public static long shiftRight(NatStore s, long a, long b) {
        if ((a | b) >= 0) {
            return b < 64 ? a >>> b : 0;
        }
        return s.shiftRightSlow(a, b);
    }

    // ------------------------------------------------------------------ pow / log2

    /** {@code Nat.pow} with the store's limit; see {@link #pow(NatStore, long, long, long)}. */
    public static long pow(NatStore s, long a, long b) {
        return s.powSlow(a, b, s.maxBytes());
    }

    /**
     * {@code Nat.pow a b = a^b} under the official guard, with the limit as a parameter:
     * <pre>
     *   b &gt; 2^32 - 1                                    -&gt; COUNT_TOO_BIG   (also for a = 0 and a = 1)
     *   a &gt; 1 and b != 0 and size(a) &gt; limitBytes / b   -&gt; TOO_BIG         (integer division)
     *   otherwise                                       -&gt; the result (not size-checked again)
     * </pre>
     * So a word-sized base of 2 or more admits exponents up to {@code limitBytes / 8} (2^24 officially), and
     * the result has at most {@code limitBytes} bytes. See {@link #shiftLeft(NatStore, long, long, long)} for
     * the meaning of the limit. A general power costs what {@code BigInteger.pow} costs (no FFT
     * multiplication): callers that must bound work should pass a limit well below the official one.
     */
    public static long pow(NatStore s, long a, long b, long limitBytes) {
        return s.powSlow(a, b, limitBytes);
    }

    /**
     * The guard of {@link #pow(NatStore, long, long, long)} alone: {@link #OK}, {@link #COUNT_TOO_BIG} or
     * {@link #TOO_BIG} (or an error operand). Constant time.
     */
    public static long powGuard(NatStore s, long a, long b, long limitBytes) {
        return s.powGuard(a, b, limitBytes);
    }

    /**
     * {@code Nat.log2}: floor of the binary logarithm, 0 for 0 and 1. Not one of the functions the v4.36
     * kernel accelerates; folding it is only sound for the standard definition of {@code Nat.log2}.
     */
    public static long log2(NatStore s, long a) {
        if (a >= 0) {
            return a < 2 ? 0 : 63 - Long.numberOfLeadingZeros(a);
        }
        if (isError(a)) {
            return a;
        }
        return s.bitLengthSlow(a) - 1;
    }

    // ------------------------------------------------------------------ sizes

    /** Number of significant bits; 0 for 0. */
    public static long bitLength(NatStore s, long h) {
        if (h >= 0) {
            return 64 - Long.numberOfLeadingZeros(h);
        }
        requireValues(s, h, 0);
        return s.bitLengthSlow(h);
    }

    /**
     * Size of a numeral as the official kernel measures it ({@code lean_nat_size_in_bytes}): 8 for a value
     * below 2^63, otherwise 8 per 64-bit limb.
     */
    public static long sizeInBytes(NatStore s, long h) {
        if (h >= 0) {
            return 8;
        }
        requireValues(s, h, 0);
        return s.sizeInBytesSlow(h);
    }

    /**
     * The check {@code infer_lit} applies to a literal: false iff the numeral is larger than
     * {@code limitBytes} (official: kernel exception).
     */
    public static boolean fitsLimit(NatStore s, long h, long limitBytes) {
        return sizeInBytes(s, h) <= limitBytes;
    }

    // ------------------------------------------------------------------ generic entry point

    /**
     * Applies a binary operation given by its {@link NatOp} code. Predicates return 1 for true and 0 for
     * false; {@code pow} and {@code shiftLeft} use the store's limit.
     */
    public static long apply2(NatStore s, int op, long a, long b) {
        switch (op) {
            case NatOp.ADD: return add(s, a, b);
            case NatOp.SUB: return sub(s, a, b);
            case NatOp.MUL: return mul(s, a, b);
            case NatOp.POW: return pow(s, a, b);
            case NatOp.GCD: return gcd(s, a, b);
            case NatOp.MOD: return mod(s, a, b);
            case NatOp.DIV: return div(s, a, b);
            case NatOp.BEQ: return beq(s, a, b) ? 1 : 0;
            case NatOp.BLE: return ble(s, a, b) ? 1 : 0;
            case NatOp.LAND: return land(s, a, b);
            case NatOp.LOR: return lor(s, a, b);
            case NatOp.XOR: return xor(s, a, b);
            case NatOp.SHIFT_LEFT: return shiftLeft(s, a, b);
            case NatOp.SHIFT_RIGHT: return shiftRight(s, a, b);
            default: throw new IllegalArgumentException("not a binary Nat operation: " + op);
        }
    }

    /** Applies a unary operation given by its {@link NatOp} code ({@code SUCC}, {@code LOG2}). */
    public static long apply1(NatStore s, int op, long a) {
        switch (op) {
            case NatOp.SUCC: return succ(s, a);
            case NatOp.LOG2: return log2(s, a);
            default: throw new IllegalArgumentException("not a unary Nat operation: " + op);
        }
    }

    // ------------------------------------------------------------------ conversions

    /** Handle of an unsigned 64-bit value. */
    public static long ofUnsignedLong(NatStore s, long x) {
        return x >= 0 ? x : s.internUnsigned(x);
    }

    /** Handle of a non-negative {@code BigInteger}; {@link #TOO_BIG} above {@link NatStore#HARD_MAX_BYTES}. */
    public static long ofBigInteger(NatStore s, BigInteger v) {
        return s.ofBigInteger(v);
    }

    /** The number as a {@code BigInteger} (allocates; for diagnostics and tests). */
    public static BigInteger toBigInteger(NatStore s, long h) {
        if (isError(h)) {
            throw new IllegalArgumentException("operand is not a number");
        }
        return s.toBigInteger(h);
    }

    /**
     * Re-interns a handle of another store view into {@code dst} (e.g. to move a result from one worker's
     * overlay to another store). Small and error handles are returned unchanged.
     */
    public static long transfer(NatStore src, long h, NatStore dst) {
        return dst.importFrom(src, h);
    }

    // ------------------------------------------------------------------ decimal text

    /**
     * Parses the decimal numeral {@code buf[from .. to)}: one or more ASCII digits, leading zeros allowed
     * (as the official reader's {@code String.toNat?} allows them). Returns the handle, {@link #MALFORMED}
     * for anything else (empty range, sign, blank, separator, non-ASCII digit), or {@link #TOO_BIG} if the
     * value exceeds {@link NatStore#HARD_MAX_BYTES}. Up to 19 digits never touch the store unless the value
     * is 2^63 or more. Linear below 1200 digits in practice, sub-quadratic beyond.
     */
    public static long parseDecimal(NatStore s, byte[] buf, int from, int to) {
        if (from >= to) {
            return MALFORMED;
        }
        int p = from;
        while (p < to - 1 && buf[p] == '0') {
            p++;
        }
        int digits = to - p;
        if (digits <= 18) {
            long v = 0;
            for (int i = p; i < to; i++) {
                int d = buf[i] - '0';
                if (d < 0 || d > 9) {
                    return MALFORMED;
                }
                v = v * 10 + d;
            }
            return v;
        }
        for (int i = p; i < to; i++) {
            int d = buf[i] - '0';
            if (d < 0 || d > 9) {
                return MALFORMED;
            }
        }
        if (digits == 19) {
            long v = 0;
            for (int i = p; i < to - 1; i++) {
                v = v * 10 + (buf[i] - '0');
            }
            // v < 10^18, so 10 v + d < 10^19 < 2^64: exact as an unsigned 64-bit value
            return ofUnsignedLong(s, v * 10 + (buf[to - 1] - '0'));
        }
        return s.parseDigits(buf, p, to);
    }

    /**
     * Like {@link #parseDecimal} but with the exact grammar of the official reader's {@code String.toNat?}
     * (v4.36): additionally a single {@code _} is allowed between two digits and ignored. lean4export never
     * writes one.
     */
    public static long parseDecimalLenient(NatStore s, byte[] buf, int from, int to) {
        int underscores = 0;
        boolean lastWasDigit = false;
        for (int i = from; i < to; i++) {
            byte c = buf[i];
            if (c == '_') {
                if (!lastWasDigit) {
                    return MALFORMED;
                }
                lastWasDigit = false;
                underscores++;
            } else if (c >= '0' && c <= '9') {
                lastWasDigit = true;
            } else {
                return MALFORMED;
            }
        }
        if (!lastWasDigit) {
            return MALFORMED;
        }
        if (underscores == 0) {
            return parseDecimal(s, buf, from, to);
        }
        byte[] digits = new byte[to - from - underscores];
        int n = 0;
        for (int i = from; i < to; i++) {
            if (buf[i] != '_') {
                digits[n++] = buf[i];
            }
        }
        return parseDecimal(s, digits, 0, n);
    }

    /** Parses a decimal numeral given as a string (convenience for tests and tools). */
    public static long parseDecimal(NatStore s, String text) {
        byte[] b = text.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        for (int i = 0; i < b.length; i++) {
            if (text.charAt(i) > 0x7F) {
                return MALFORMED;
            }
        }
        return parseDecimal(s, b, 0, b.length);
    }

    /** The decimal numeral of a value, without leading zeros (allocates; sub-quadratic for huge values). */
    public static String toDecimalString(NatStore s, long h) {
        if (h >= 0) {
            return Long.toString(h);
        }
        return toBigInteger(s, h).toString();
    }

    /** A description of any handle for diagnostics: the numeral, or the name of the error. */
    public static String describe(NatStore s, long h) {
        if (h == NONE) {
            return "<none>";
        }
        if (h == TOO_BIG) {
            return "<numeral too big>";
        }
        if (h == COUNT_TOO_BIG) {
            return "<count does not fit 32 bits>";
        }
        if (h == MALFORMED) {
            return "<malformed numeral>";
        }
        if (isError(h)) {
            return "<invalid handle 0x" + Long.toHexString(h) + ">";
        }
        return toDecimalString(s, h);
    }
}
