package rlvm.machine;

import static rlvm.machine.Op.SIG;

import java.math.BigInteger;
import rlvm.api.MachineErrors;
import rlvm.nat.Nat;
import rlvm.nat.NatStore;
import rlvm.store.Kind;
import rlvm.store.LitPool;
import rlvm.store.Node;

/**
 * The literal bridge (ISA 2.9; RECONCILIATION section 2 "Literal identity"): Nat and String literal
 * values, import of export literals, content comparison. The machine calls {@code rlvm.nat} directly.
 * Owner: M1.
 *
 * <pre>
 * Nat literal n      n &lt; 2^31            immediate (never a record)
 *                    2^31 &lt;= n &lt; 2^63    NBIG, aux 0: a = low 32 bits, b = high 31 bits       canonical by value
 *                    n &gt;= 2^63           NBIG, aux 1: a = id in the worker's NatStore (m.nats), which interns
 *                                        by content; b = content hash, c = limb count (staleness check)
 * String literal                         STR: a = LitPool string index (interned by content in one pool)
 * rlvm.nat handle    h &gt;= 0: the number; Nat.BIG | id: the NatStore id         ({@link #natHandle}, Values.mkNat)
 * </pre>
 * Two literal refs that are equal denote equal literals. Two that differ are compared by content before
 * anybody answers "different" ({@link #sameContent}): immediates and two-word NBIG records are canonical
 * by construction, NatStore ids and LitPool indices are canonical only within one store view.
 */
final class Lits {
    private Lits() {}

    /**
     * Content comparison of two literal refs that differ (ISA 2.9; RECONCILIATION section 2 "Literal identity"):
     * limbs for two NBIG records with store ids, bytes for two STR records. Also true for equal refs; false when
     * one side is not a literal or the two are literals of different types.
     */
    static boolean sameContent(Machine m, int a, int b) {
        if (a == b) {
            return true;
        }
        if (a <= Layout.HOLE || b <= Layout.HOLE) {
            return false; // an immediate is canonical: it differs from every other literal ref
        }
        return sameContentSlow(m, a, b);
    }

    private static boolean sameContentSlow(Machine m, int a, int b) {
        int[] vh = m.vh;
        int oa = a << Layout.REC_SHIFT;
        int ob = b << Layout.REC_SHIFT;
        int wa = vh[oa];
        int wb = vh[ob];
        int ka = wa >>> Layout.KIND_SHIFT;
        if (ka != wb >>> Layout.KIND_SHIFT) {
            return false;
        }
        if (ka == Layout.STR) {
            return m.lits.stringEquals(vh[oa + Layout.A], vh[ob + Layout.A]);
        }
        if (ka != Layout.NBIG) {
            return false;
        }
        boolean sa = (wa & Layout.AUX_MASK) == Layout.NBIG_STORE;
        boolean sb = (wb & Layout.AUX_MASK) == Layout.NBIG_STORE;
        if (!sa && !sb) {
            return vh[oa + Layout.A] == vh[ob + Layout.A] && vh[oa + Layout.B] == vh[ob + Layout.B];
        }
        NatStore s = m.nats;
        if (sa != sb) {
            // A stored number is 2^63 or more and a two-word one is below: different, unless the store holds
            // a non-canonical one-limb entry, which is compared by value.
            int st = sa ? a : b;
            long h = natHandle(m, st);
            long w = natHandle(m, sa ? b : a);
            int id = Nat.bigId(h);
            return s.limbCount(id) == 1 && s.limb(id, 0) == w;
        }
        int ia = Nat.bigId(natHandle(m, a));
        int ib = Nat.bigId(natHandle(m, b));
        int n = s.limbCount(ia);
        if (n != s.limbCount(ib)) {
            return false;
        }
        for (int i = 0; i < n; i++) {
            if (s.limb(ia, i) != s.limb(ib, i)) {
                return false;
            }
        }
        return true;
    }

    /**
     * KLIT (ISA 4.2): the value of a literal node (immediate, NBIG or STR); an export literal >= 2^63 is imported
     * into the worker's NatStore once per session.
     * @return the value, or ABORT (E_NATSIZE: the literal is larger than the store can hold, hence larger than
     *     the official limit) or FATAL (E_INTERNAL: not a literal node; E_MEM)
     */
    static int klit(Machine m, int term) {
        int[] r = m.terms.regionOf(term);
        int w = Node.wordOf(term);
        int hdr = r[w + Node.W_HEADER];
        int kind = hdr & 0xF;
        if (kind == Kind.NATLIT) {
            if ((hdr & Node.F_NAT_BIG) == 0) {
                int hi = r[w + Node.W_B];
                int lo = r[w + Node.W_A];
                if (hi == 0 && lo >= 0) {
                    return lo | Layout.IMM;
                }
                long v = (lo & 0xFFFFFFFFL) | ((long) hi << 32);
                return Values.mkNat(m, v >= 0 ? v : Nat.ofUnsignedLong(m.nats, v));
            }
            return poolNatSlow(m, r[w + Node.W_A]);
        }
        if (kind == Kind.STRLIT) {
            return Values.mkStr(m, r[w + Node.W_A]);
        }
        return m.fault(MachineErrors.E_INTERNAL);
    }

    /**
     * The value of natural {@code index} of the literal pool (2^64 or more): its limbs are copied into the
     * worker's NatStore, which interns by content. A small cache remembers the result for SHARED pool entries
     * (positive indices: immutable for the whole run); a worker-pool index can be reissued to another number
     * when the pool is truncated, so it is imported every time.
     */
    private static int poolNatSlow(Machine m, int index) {
        Values.Ext x = m.x1;
        int slot = (index & (Values.Ext.LIT_SLOTS - 1)) << 1;
        if (index > 0 && x.litImp[slot] == index) {
            return x.litImp[slot + 1];
        }
        LitPool pool = m.lits;
        NatStore s = m.nats;
        if (index == 0 || pool == null || s == null) {
            return m.fault(MachineErrors.E_INTERNAL);
        }
        int n = pool.natLimbCount(index);
        if (x.limbs.length < n) {
            x.limbs = new long[Math.max(n, x.limbs.length << 1)];
        }
        long[] limbs = x.limbs;
        pool.natLimbs(index, limbs, 0);
        while (n > 1 && limbs[n - 1] == 0) {
            n--;
        }
        long h;
        if (n == 1) {
            h = Nat.ofUnsignedLong(s, limbs[0]);
        } else {
            // rlvm-nat has no raw-limb import: through a big-endian magnitude, as rlvm.api.bind.NatBinding does.
            byte[] mag = new byte[n * 8];
            for (int i = 0; i < n; i++) {
                long w = limbs[i];
                int base = (n - 1 - i) * 8;
                for (int k = 7; k >= 0; k--) {
                    mag[base + k] = (byte) w;
                    w >>>= 8;
                }
            }
            h = Nat.ofBigInteger(s, new BigInteger(1, mag));
        }
        int v = Values.mkNat(m, h);
        if (v < SIG && index > 0) {
            x.litImp[slot] = index;
            x.litImp[slot + 1] = v;
        }
        return v;
    }

    /**
     * True for a Nat literal value (immediate or NBIG).
     */
    static boolean isNat(Machine m, int v) {
        return v < 0 || (v > Layout.HOLE && m.vh[v << Layout.REC_SHIFT] >>> Layout.KIND_SHIFT == Layout.NBIG);
    }

    /** True for a literal value: a Nat literal or a STR record. */
    static boolean isLit(Machine m, int v) {
        if (v <= Layout.HOLE) {
            return v < 0;
        }
        int k = m.vh[v << Layout.REC_SHIFT] >>> Layout.KIND_SHIFT;
        return k == Layout.NBIG || k == Layout.STR;
    }

    /**
     * The {@code rlvm.nat} handle of a Nat literal value (ISA 2.9): the number itself below 2^63, else
     * {@code Nat.BIG | id}. Precondition: {@link #isNat}.
     * @throws IllegalStateException if the NatStore id of the record no longer names the number the record was
     *     made for (the store was emptied under the session); the facade turns it into DECLINE (ISA 14.1)
     */
    static long natHandle(Machine m, int v) {
        if (v < 0) {
            return v & Layout.IMM_MASK;
        }
        int[] vh = m.vh;
        int o = v << Layout.REC_SHIFT;
        if ((vh[o] & Layout.AUX_MASK) == Layout.NBIG_INLINE) {
            return (vh[o + Layout.A] & 0xFFFFFFFFL) | ((long) vh[o + Layout.B] << 32);
        }
        return storeHandleSlow(m, vh[o + Layout.A], vh[o + Layout.B], vh[o + Layout.C]);
    }

    private static long storeHandleSlow(Machine m, int id, int hash, int limbs) {
        NatStore s = m.nats;
        if (s == null || id <= 0 || id >= s.nextId() || s.hashOf(id) != hash || s.limbCount(id) != limbs) {
            throw new IllegalStateException("stale NatStore id " + id + " in an NBIG record");
        }
        return Nat.ofBigId(id);
    }

    /** The string index (in {@code m.lits}) of a STR record. */
    static int strIndex(Machine m, int v) {
        return m.vh[(v << Layout.REC_SHIFT) + Layout.A];
    }
}
