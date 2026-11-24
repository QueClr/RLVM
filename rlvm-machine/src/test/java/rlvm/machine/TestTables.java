package rlvm.machine;

/**
 * Reference probes over the frozen table formats of {@link Layout} / {@link Hash}, for the tests of the
 * machine state. {@code Runs} (M1) and {@code Pairs} (M4) implement the real ones; theirs must find the
 * entries these make and vice versa.
 */
final class TestTables {
    private TestTables() {}

    /** FRAME / REST run with the given slots, interned by content. */
    static int internRun(Machine m, int kind, int rest, int... slots) {
        int folded = Hash.seed(slots.length);
        for (int s : slots) {
            folded = Hash.step(folded, s);
        }
        int hash = Hash.run(folded, kind, rest);
        int hdr = Layout.runHeader(slots.length, kind, rest != 0);
        int slot = hash & m.rtabMask;
        for (;;) {
            int e = m.rtab[slot];
            if (e == 0) {
                break;
            }
            if (((e ^ hash) >>> Layout.TAB_TAG_SHIFT) == 0) {
                int r = e & Layout.TAB_INDEX_MASK;
                if ((m.rs[r] & ~Layout.RUN_OPEN) == hdr && (rest == 0 || m.rs[r + Layout.RUN_REST_WORD] == rest) && sameSlots(m, r, slots)) {
                    return r;
                }
            }
            slot = (slot + 1) & m.rtabMask;
        }
        int r = m.addRun(hdr, hash, rest);
        if (r >= Op.SIG) {
            return r;
        }
        System.arraycopy(slots, 0, m.rs, r + Layout.runHeaderWords(hdr), slots.length);
        m.putRunAt(slot, hash, r);
        return r;
    }

    /** A SPINE run (not interned: owned by its NEU). */
    static int spineRun(Machine m, int... slots) {
        int hdr = Layout.runHeader(slots.length, Layout.RUN_SPINE, false);
        int r = m.addRun(hdr, 0, 0);
        if (r >= Op.SIG) {
            return r;
        }
        System.arraycopy(slots, 0, m.rs, r + Layout.RUN_H, slots.length);
        return r;
    }

    private static boolean sameSlots(Machine m, int r, int[] slots) {
        int base = r + Layout.runHeaderWords(m.rs[r]);
        for (int i = 0; i < slots.length; i++) {
            if (m.rs[base + i] != slots[i]) {
                return false;
            }
        }
        return true;
    }

    /** Entry index of the unordered pair, or ~(index of the empty entry its probe sequence ends in). */
    static int findPair(Machine m, int a, int b) {
        int lo = Integer.compareUnsigned(a, b) <= 0 ? a : b;
        int hi = lo == a ? b : a;
        int slot = Hash.pair(lo, hi) & m.pairMask;
        for (;;) {
            int o = slot << Layout.PAIR_SHIFT;
            int l = m.pair[o + Layout.PAIR_LO];
            if (l == 0) {
                return ~slot;
            }
            if (l == lo && m.pair[o + Layout.PAIR_HI] == hi) {
                return slot;
            }
            slot = (slot + 1) & m.pairMask;
        }
    }

    static int pairStatus(Machine m, int a, int b) {
        int i = findPair(m, a, b);
        return i < 0 ? 0 : m.pair[(i << Layout.PAIR_SHIFT) + Layout.PAIR_STATUS];
    }

    static void pairAdd(Machine m, int a, int b, int bits) {
        int i = findPair(m, a, b);
        if (i >= 0) {
            m.pair[(i << Layout.PAIR_SHIFT) + Layout.PAIR_STATUS] |= bits;
            return;
        }
        int lo = Integer.compareUnsigned(a, b) <= 0 ? a : b;
        int hi = lo == a ? b : a;
        m.addPairAt(~i, lo, hi, bits);
    }
}
