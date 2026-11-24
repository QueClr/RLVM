package rlvm.api;

/**
 * Literal arithmetic of the kernel's Nat extension and string-literal access: the adapter over
 * {@code rlvm-nat}. Operands and results are NATLIT / STRLIT term ids of the calling worker
 * (results are built in its overlay).
 *
 * <p>Semantics are those of {@code type_checker.cpp} at v4.36.0: the 128 MB result limit, exponent
 * and shift-count limits, {@code Nat.sub} truncating at zero, division and modulo by zero.
 *
 * <p>This interface serves the reference kernel and admission code; the machine calls
 * {@code rlvm.nat} directly (RECONCILIATION.md, "Literal identity"). Two NATLIT term ids may denote
 * the same number (a shared export literal and an overlay literal a worker computed): always compare
 * with {@link #natEquals}, never by term id alone, before answering "different".
 */
public interface NatOps {

    /** Operation codes; the values equal {@code Specials.NAT_ADD ...} minus {@code Specials.NAT_ADD}. */
    int ADD = 0;
    int SUB = 1;
    int MUL = 2;
    int POW = 3;
    int GCD = 4;
    int MOD = 5;
    int DIV = 6;
    int BEQ = 7;
    int BLE = 8;
    int LAND = 9;
    int LOR = 10;
    int XOR = 11;
    int SHIFT_LEFT = 12;
    int SHIFT_RIGHT = 13;

    /**
     * Result literal of a binary operation other than BEQ/BLE, or 0 when the kernel does not reduce
     * (never for the v4.36 profile, where every guard raises). Throws {@link KernelException}
     * (NAT_LITERAL_TOO_BIG) where the official kernel raises: a result of add / sub / mul above the
     * size limit, a {@code Nat.pow} exponent or non-zero {@code Nat.shiftLeft} count above 2^32 - 1,
     * the size guards of pow and shiftLeft. Throws {@link ResourceException} when the session's own
     * memory limits are hit.
     */
    int binary(int op, int litA, int litB);

    /** BEQ / BLE: the truth value. */
    boolean test(int op, int litA, int litB);

    /** {@code n + 1}; {@link KernelException} (NAT_LITERAL_TOO_BIG) above the size limit, as the official kernel. */
    int succ(int lit);

    /** {@code n - 1} for a non-zero literal. */
    int pred(int lit);

    boolean isZero(int lit);

    boolean natEquals(int litA, int litB);

    /** Number of Unicode scalar values of a string literal. */
    int stringLength(int strLit);

    int stringCodePoint(int strLit, int index);

    boolean stringEquals(int strLitA, int strLitB);

    // ---- added after the first skeleton (defaults keep hand-written fakes compiling) ----------------

    /**
     * The official {@code infer_lit} bound: throws {@link KernelException} (NAT_LITERAL_TOO_BIG) if the
     * numeral is larger than the size limit (128 MiB). No export literal of a real library comes near.
     */
    default void checkLiteralSize(int lit) {}

    /** The literal with value {@code unsignedValue} (an unsigned 64-bit number), e.g. 0 for {@code Nat.zero}. */
    default int literal(long unsignedValue) {
        throw new UnsupportedOperationException("NatOps.literal is not implemented by " + getClass().getName());
    }

    /** True if the literal is below 2^63; then {@link #smallValue} is its value. */
    default boolean isSmall(int lit) {
        throw new UnsupportedOperationException("NatOps.isSmall is not implemented by " + getClass().getName());
    }

    /** Value of a literal below 2^63 (undefined otherwise). */
    default long smallValue(int lit) {
        throw new UnsupportedOperationException("NatOps.smallValue is not implemented by " + getClass().getName());
    }

    /** Diagnostics only: the decimal numeral (abbreviated for very long numbers). */
    default String natToString(int lit) {
        return "nat#" + lit;
    }

    /** Forgets every number this worker computed (session reset, together with the term overlay). */
    default void reset() {}

    /** Bytes held by the numbers this worker computed (for budgets and the session footprint). */
    default long footprintBytes() {
        return 0L;
    }
}
