package rlvm.machine;

import rlvm.api.LevelOps;
import rlvm.store.Kind;
import rlvm.store.Terms;

/**
 * Comparison-plan builder (ISA 6.1, {@code CONG(c)}). Owner: M7. Staging step 2.
 *
 * <p>{@code CONG(c)} is the residual of {@code CARGS} for the constant head {@code c}: what is decided
 * per argument position when {@code c} is known. It is a block of role CONG built from {@code FACTS(c)}
 * and the type term of {@code c}; it holds classes, ranks and masks only - no term, value or level id -
 * so it obeys the publication rule trivially (ISA 3.3, 6.1).
 * <pre>
 * hdr0 = len | 0 | CONG     hdr1 = row     hdr2 = npos:12 | flags:12 | 0     hdr3 = facts index
 * word 4        lamArity
 * 3 words per position i &lt; npos = min(arity, 64):
 *   class:3 | UNREAD (bit 3) | probe rank:6 (bits 4..9)      Layout.CONG_*
 *   dep[i] low, dep[i] high                                  bit j: the domain of position i reads binder j
 * </pre>
 * Classes: SAME (compare; the default); TYPE (compare; the argument is a type or a type former, so a
 * mismatch is cheap to find and decides most later positions: probed first); PROOF (the sort of the
 * domain is manifest and zero under every assignment: skipped under the agreement rule); PROOFL (the
 * sort is manifest and mentions level parameters: a proof position when it is zero at the head's levels,
 * answered by the prop mask, {@code Facts.propMask}). UNREAD is a flag beside the class: the value's body
 * does not read the parameter (skipped under SAMEDEF when the spine covers lamArity). A position whose
 * sort is not manifest is never a proof position (ISA 3.5).
 *
 * <p>The plan is built at the first request once the fact record exists (it costs a few dozen
 * instructions per position), not rent-to-own (NOTES-M7 D5). Behind {@code Op.SW_FACTS}.
 */
final class Cong {
    private Cong() {}

    /** Word offsets inside a CONG block. */
    static final int C_LAMARITY = Op.BLK_ENTRIES;
    static final int C_POS = Op.BLK_ENTRIES + 1;
    static final int C_WORDS = 3;

    /**
     * The comparison plan CONG(row) (ISA 6.1): a block of role CONG built from FACTS(row) and the type term; 0 = none
     * (every position SAME). Staging step 2.
     */
    static int planOf(Machine m, int row) {
        if (m.engine == null || m.strict || !m.on(Op.SW_FACTS)) {
            return 0;
        }
        CodeStore cs = m.engine.code;
        int b = cs.blockOf(row, Op.ROLE_CONG);
        if (b != 0) {
            return b;
        }
        int fi = Facts.facts(m, row);
        if (fi <= 0 || fi >= Op.SIG) {
            return 0;
        }
        FactStore fs = m.engine.facts;
        Elab.State s = m.x7.elab();
        int len = build(m, row, fs.chunkOf(fi), fs.offsetOf(fi), fi, s);
        if (len == 0) {
            m.x7.stayedGeneric[Op.ROLE_CONG]++;
            return 0;
        }
        m.x7.elaborated[Op.ROLE_CONG]++;
        return cs.publish(row, Op.ROLE_CONG, s.buf, len);
    }

    /**
     * Builds the plan of {@code row} from the fact record at {@code f[fo ..]} (layout {@code Layout.F_*}, ISA
     * 3.5) into {@code s.buf[0 ..]}.
     *
     * @return the length of the block, 0 if there is no plan (no facts, {@code F_NONE}, no arguments)
     */
    static int build(Machine m, int row, int[] f, int fo, int factsIndex, Elab.State s) {
        if ((f[fo + Layout.F_FLAGS] & Layout.F_NONE) != 0) {
            return 0;
        }
        int arity = f[fo + Layout.F_ARITY] >>> 16;
        int lamArity = f[fo + Layout.F_ARITY] & 0xFFFF;
        int n = Math.min(arity, Layout.FACT_POSITIONS);
        if (n == 0) {
            return 0;
        }
        long unread = word2(f, fo + Layout.F_UNREAD);
        long manifest = word2(f, fo + Layout.F_MANIFEST);
        int len = C_POS + C_WORDS * n;
        if (s.buf.length < len) {
            s.buf = new int[len];
        }
        int[] out = s.buf;
        Terms terms = m.terms;
        LevelOps lv = m.levels;
        int t = m.decls.type(row);
        boolean propStatic = true;
        for (int i = 0; i < n; i++) {
            int cls = Layout.CONG_SAME;
            int ref = (manifest >>> i & 1) != 0 ? f[fo + Layout.F_SLVL + i] : Layout.LREF_UNKNOWN;
            if (ref != Layout.LREF_UNKNOWN) {
                int l = (ref & Layout.LREF_TAG_MASK) == Layout.LREF_BASE ? ref & Layout.LREF_PAYLOAD_MASK
                        : Levels.importLevel(m, factsIndex, Layout.F_SLVL + i);
                if (lv.isAlwaysZero(l)) {
                    cls = Layout.CONG_PROOF;
                } else if (lv.hasParam(l) && !lv.isNeverZero(l)) {
                    cls = Layout.CONG_PROOFL;
                    propStatic = false;
                } else if (lv.kind(l) == LevelOps.KIND_SUCC && lv.kind(lv.succOf(l)) == LevelOps.KIND_SUCC) {
                    cls = Layout.CONG_TYPE; // the domain lives in Sort (l + 2): its inhabitants are types or type formers
                }
            }
            boolean pi = t > 0 && terms.kind(t) == Kind.PI;
            if (cls == Layout.CONG_SAME && pi && endsInSort(terms, terms.a(t))) {
                cls = Layout.CONG_TYPE;
            }
            if (i < lamArity && (unread >>> i & 1) != 0) {
                cls |= Layout.CONG_UNREAD;
            }
            int o = C_POS + C_WORDS * i;
            out[o] = cls;
            out[o + 1] = f[fo + Layout.F_SLVL + n + 2 * i];
            out[o + 2] = f[fo + Layout.F_SLVL + n + 2 * i + 1];
            if (pi) {
                t = terms.b(t);
            }
        }
        ranks(out, n);
        out[Op.BLK_HDR0] = Code.hdr0(len, 0, Op.ROLE_CONG);
        out[Op.BLK_ROW] = row;
        out[Op.BLK_HDR2] = Code.hdr2(n, propStatic ? Layout.CONG_PROP_STATIC : 0, 0);
        out[Op.BLK_FACTS] = factsIndex;
        out[C_LAMARITY] = lamArity;
        return len;
    }

    private static long word2(int[] f, int o) {
        return (f[o] & 0xFFFFFFFFL) | (long) f[o + 1] << 32;
    }

    /** A sort, or a Pi telescope that ends in one (ISA 6.1 class TYPE). */
    private static boolean endsInSort(Terms terms, int t) {
        int k = terms.kind(t);
        while (k == Kind.PI) {
            t = terms.b(t);
            k = terms.kind(t);
        }
        return k == Kind.SORT;
    }

    /**
     * Probe ranks (ISA 6.1): TYPE positions first, then by decreasing number of later positions whose dep
     * contains the position, then last to first. Rank 0 is probed first.
     */
    private static void ranks(int[] out, int n) {
        long done = 0;
        for (int rank = 0; rank < n; rank++) {
            int best = -1;
            int bestType = -1;
            int bestCount = -1;
            for (int i = n - 1; i >= 0; i--) {
                if ((done >>> i & 1) != 0) {
                    continue;
                }
                int type = (out[C_POS + C_WORDS * i] & Layout.CONG_CLASS_MASK) == Layout.CONG_TYPE ? 1 : 0;
                int count = 0;
                for (int j = i + 1; j < n; j++) {
                    long dep = (out[C_POS + C_WORDS * j + 1] & 0xFFFFFFFFL) | (long) out[C_POS + C_WORDS * j + 2] << 32;
                    count += (int) (dep >>> i & 1);
                }
                if (type > bestType || type == bestType && count > bestCount) {
                    best = i;
                    bestType = type;
                    bestCount = count;
                }
            }
            done |= 1L << best;
            out[C_POS + C_WORDS * best] |= rank << Layout.CONG_RANK_SHIFT;
        }
    }

    // ---------------------------------------------------------------------------------------------------
    // Readers (for CARGS, M4): fetch the chunk and the offset once per comparison, then decode
    // ---------------------------------------------------------------------------------------------------

    /** The chunk that holds plan {@code plan}. */
    static int[] words(Machine m, int plan) {
        return m.engine.code.chunkOf(plan);
    }

    /** The offset of plan {@code plan} inside {@link #words}. */
    static int base(Machine m, int plan) {
        return m.engine.code.offsetOf(plan);
    }

    /** Number of positions with a plan entry: {@code min(arity, 64)}; positions beyond it are SAME. */
    static int positions(int[] c, int o) {
        return Code.arity(c[o + Op.BLK_HDR2]);
    }

    static int lamArity(int[] c, int o) {
        return c[o + C_LAMARITY];
    }

    /** True if no position is PROOFL: the prop mask of the constant does not depend on its levels. */
    static boolean propStatic(int[] c, int o) {
        return (Code.flags(c[o + Op.BLK_HDR2]) & Layout.CONG_PROP_STATIC) != 0;
    }

    /** The class word of position i: {@code Layout.CONG_CLASS_MASK}, {@code CONG_UNREAD}, rank at {@code CONG_RANK_SHIFT}. */
    static int classWord(int[] c, int o, int i) {
        return c[o + C_POS + C_WORDS * i];
    }

    /** dep[i]: bit j = the domain of position i reads binder j (a superset, ISA 3.5). */
    static long dep(int[] c, int o, int i) {
        int p = o + C_POS + C_WORDS * i;
        return (c[p + 1] & 0xFFFFFFFFL) | (long) c[p + 2] << 32;
    }
}
