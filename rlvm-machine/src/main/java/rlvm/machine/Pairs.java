package rlvm.machine;

/**
 * The pair table {@code PAIR} and {@code UNDEC} (ISA 1.5, 1.6, 5.4). Entry format and hash are frozen in
 * {@link Layout} and {@link Hash}; {@link Machine#addPairAt} appends (log, growth). Owner: M4.
 *
 * <p>{@code PAIR} is one open-addressing table of 16-byte entries {@code lo | hi | status | 0}: one probe
 * (one cache line in the common case) serves the three memos of conversion, the status bits
 * {@code POS} (the official {@code m_success}, R02 section 5: exact pairs only, no closure), {@code NEG}
 * (an {@code F} of the complete procedure) and {@code FAILED} (the official {@code m_failure}: a
 * same-head argument comparison that completed with {@code F}). The key is the unordered pair; the two
 * refs are ordered as UNSIGNED 32-bit numbers, so that an immediate (bit 31 set) is a key like any other
 * and {@code lo == 0} can mean "empty" (NONE is never compared).
 *
 * <p>{@code UNDEC} is a direct-mapped, lossy cache of 2^12 cells stamped with {@code m.declStamp}
 * (declaration scope): it only remembers that a budgeted probe of a pair ended in {@code U}, so that the
 * same probe is not repeated; the complete procedure never reads it.
 */
final class Pairs {
    private Pairs() {}

    private static final int UNDEC_MASK = (1 << Layout.UNDEC_LOG2) - 1;

    /**
     * PAIRGET (ISA 4.1, 1.5): the status bits ({@code Layout.POS | NEG | FAILED}) of the unordered pair, 0 if absent.
     * The key orders the two refs as UNSIGNED numbers (an immediate has bit 31 set).
     */
    static int status(Machine m, int a, int b) {
        int lo = a;
        int hi = b;
        if (a + Integer.MIN_VALUE > b + Integer.MIN_VALUE) {
            lo = b;
            hi = a;
        }
        int[] t = m.pair;
        int mask = m.pairMask;
        int slot = Hash.pair(lo, hi) & mask;
        for (;;) {
            int o = slot << Layout.PAIR_SHIFT;
            int l = t[o];
            if (l == 0) {
                return 0;
            }
            if (l == lo && t[o + Layout.PAIR_HI] == hi) {
                return t[o + Layout.PAIR_STATUS];
            }
            slot = (slot + 1) & mask;
        }
    }

    /**
     * PAIRPUT (ISA 4.1): ors {@code bits} into the pair's entry, creating it. Completion rule 5.4: POS exact pairs only;
     * NEG only for an F of the complete procedure, not under ER, not when a side is a LAM; FAILED only for a
     * same-head comparison that completed with F. (The rule is the caller's: this is the table operation.)
     */
    static void add(Machine m, int a, int b, int bits) {
        int lo = a;
        int hi = b;
        if (a + Integer.MIN_VALUE > b + Integer.MIN_VALUE) {
            lo = b;
            hi = a;
        }
        if (lo == Layout.NONE) {
            return; // NONE is never a side of a comparison; lo == 0 is the empty entry
        }
        int[] t = m.pair;
        int mask = m.pairMask;
        int slot = Hash.pair(lo, hi) & mask;
        for (;;) {
            int o = slot << Layout.PAIR_SHIFT;
            int l = t[o];
            if (l == 0) {
                m.addPairAt(slot, lo, hi, bits);
                return;
            }
            if (l == lo && t[o + Layout.PAIR_HI] == hi) {
                t[o + Layout.PAIR_STATUS] |= bits;
                return;
            }
            slot = (slot + 1) & mask;
        }
    }

    /**
     * UNDEC (ISA 1.5, 5.4): log2 of the largest budget of a probe of this pair that ended in U in this declaration, 0
     * if none. Only suppresses another probe; the complete procedure never reads it.
     */
    static int undec(Machine m, int a, int b) {
        int lo = a;
        int hi = b;
        if (a + Integer.MIN_VALUE > b + Integer.MIN_VALUE) {
            lo = b;
            hi = a;
        }
        int[] c = m.undec;
        int i = (Hash.cell(lo, hi) & UNDEC_MASK) << Layout.CELL_SHIFT;
        if (c[i + Layout.UNDEC_STAMP] == m.declStamp && c[i + Layout.UNDEC_LO] == lo && c[i + Layout.UNDEC_HI] == hi) {
            return c[i + Layout.UNDEC_LOG];
        }
        return 0;
    }

    /**
     * Records that a probe of this pair under a budget of 2^log2Budget ticks ended in U (never as FAILED).
     * The cell keeps the largest budget of the pair; another pair that maps to the cell replaces it (lossy).
     */
    static void setUndec(Machine m, int a, int b, int log2Budget) {
        int lo = a;
        int hi = b;
        if (a + Integer.MIN_VALUE > b + Integer.MIN_VALUE) {
            lo = b;
            hi = a;
        }
        int[] c = m.undec;
        int i = (Hash.cell(lo, hi) & UNDEC_MASK) << Layout.CELL_SHIFT;
        if (c[i + Layout.UNDEC_STAMP] == m.declStamp && c[i + Layout.UNDEC_LO] == lo && c[i + Layout.UNDEC_HI] == hi) {
            if (c[i + Layout.UNDEC_LOG] < log2Budget) {
                c[i + Layout.UNDEC_LOG] = log2Budget;
            }
            return;
        }
        c[i + Layout.UNDEC_LO] = lo;
        c[i + Layout.UNDEC_HI] = hi;
        c[i + Layout.UNDEC_STAMP] = m.declStamp;
        c[i + Layout.UNDEC_LOG] = log2Budget;
    }

    /** log2 of a probe budget, rounded up (the size class under which a U is recorded in UNDEC); at least 1. */
    static int log2(long budget) {
        return budget <= 2 ? 1 : 64 - Long.numberOfLeadingZeros(budget - 1);
    }
}
