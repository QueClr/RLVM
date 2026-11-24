package rlvm.nat;

/**
 * Codes and constant names of the Nat literal operations.
 *
 * <p>The official v4.36 kernel ({@code type_checker.cpp}, {@code reduce_nat}) accelerates exactly the 15
 * functions {@link #SUCC} .. {@link #SHIFT_RIGHT}, recognised as the constants {@code Nat.succ} (applied to
 * exactly one argument) and {@code Nat.add} ... {@code Nat.shiftRight} (applied to exactly two arguments),
 * each written without universe levels. An argument counts as a literal if it is a Nat literal or the
 * constant {@code Nat.zero}. {@link #LOG2} is provided by this module but is <em>not</em> accelerated by
 * that kernel (earlier versions and several other checkers do fold it).
 */
public final class NatOp {
    private NatOp() {}

    public static final int SUCC = 1;
    public static final int ADD = 2;
    public static final int SUB = 3;
    public static final int MUL = 4;
    public static final int POW = 5;
    public static final int GCD = 6;
    public static final int MOD = 7;
    public static final int DIV = 8;
    public static final int BEQ = 9;
    public static final int BLE = 10;
    public static final int LAND = 11;
    public static final int LOR = 12;
    public static final int XOR = 13;
    public static final int SHIFT_LEFT = 14;
    public static final int SHIFT_RIGHT = 15;
    /** Not a kernel-accelerated function in v4.36. */
    public static final int LOG2 = 16;

    /** Smallest and largest code. */
    public static final int FIRST = SUCC;
    public static final int LAST = LOG2;
    /** Largest code of a function the v4.36 kernel accelerates. */
    public static final int LAST_OFFICIAL = SHIFT_RIGHT;

    private static final String[] NAMES = {
        null, "succ", "add", "sub", "mul", "pow", "gcd", "mod", "div", "beq", "ble", "land", "lor", "xor",
        "shiftLeft", "shiftRight", "log2",
    };

    /** Last component of the constant's name, e.g. {@code "add"} for {@code Nat.add}; the prefix is {@code Nat}. */
    public static String shortName(int op) {
        check(op);
        return NAMES[op];
    }

    /** Full constant name, e.g. {@code "Nat.add"}. */
    public static String name(int op) {
        return "Nat." + shortName(op);
    }

    /** Code for the last name component ({@code "add"} ...), or 0 if it is not one of the operations. */
    public static int ofShortName(String shortName) {
        for (int op = FIRST; op <= LAST; op++) {
            if (NAMES[op].equals(shortName)) {
                return op;
            }
        }
        return 0;
    }

    /** Number of arguments: 1 for {@code succ} and {@code log2}, 2 otherwise. */
    public static int arity(int op) {
        check(op);
        return op == SUCC || op == LOG2 ? 1 : 2;
    }

    /** True for {@code beq} and {@code ble}, whose result is {@code Bool.true} / {@code Bool.false}. */
    public static boolean isPredicate(int op) {
        check(op);
        return op == BEQ || op == BLE;
    }

    /** True for the functions the official v4.36 kernel accelerates. */
    public static boolean isOfficial(int op) {
        check(op);
        return op <= LAST_OFFICIAL;
    }

    /**
     * True for the operations whose <em>result</em> the official kernel size-checks after computing
     * ({@code succ add sub mul}).
     */
    public static boolean checksResultSize(int op) {
        check(op);
        return op <= MUL;
    }

    /** True for the operations guarded before computing ({@code pow shiftLeft}). */
    public static boolean hasCountGuard(int op) {
        check(op);
        return op == POW || op == SHIFT_LEFT;
    }

    private static void check(int op) {
        if (op < FIRST || op > LAST) {
            throw new IllegalArgumentException("not a Nat operation code: " + op);
        }
    }
}
