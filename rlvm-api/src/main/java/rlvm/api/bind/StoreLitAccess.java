package rlvm.api.bind;

import rlvm.store.LitPool;
import rlvm.store.Node;
import rlvm.store.Overlay;
import rlvm.store.Terms;

/**
 * {@link LitAccess} over the worker's {@link Terms} / {@link Overlay} and its literal pool, with the
 * node layout of {@code rlvm.store.Kind}:
 * <pre>
 * NATLIT, flag F_NAT_BIG clear   a = low 32 bits, b = high 32 bits of a value below 2^64 (Terms.natSmall)
 * NATLIT, flag F_NAT_BIG set     a = index of the literal pool (positive: shared, negative: the worker's own)
 * STRLIT                         a = index of the literal pool
 * </pre>
 * {@code pool} is the pool the worker reads and writes literals through: its own worker pool (which
 * resolves indices of either sign and interns by content), or the shared pool for a worker that
 * computes no big numbers.
 */
public final class StoreLitAccess implements LitAccess {

    private final Terms terms;
    private final Overlay overlay;
    private final LitPool pool;
    private long[] scratchA = new long[8];
    private long[] scratchB = new long[8];

    public StoreLitAccess(Terms terms, Overlay overlay, LitPool pool) {
        if (terms == null || overlay == null || pool == null) {
            throw new NullPointerException();
        }
        this.terms = terms;
        this.overlay = overlay;
        this.pool = pool;
    }

    @Override
    public boolean natIsWord(int lit) {
        return (terms.header(lit) & Node.F_NAT_BIG) == 0;
    }

    @Override
    public long natWord(int lit) {
        return terms.natSmall(lit);
    }

    @Override
    public int natRef(int lit) {
        return terms.a(lit);
    }

    @Override
    public int bigLimbCount(int ref) {
        return pool.natLimbCount(ref);
    }

    @Override
    public void bigLimbs(int ref, long[] dst, int off) {
        pool.natLimbs(ref, dst, off);
    }

    @Override
    public boolean bigEquals(int refA, int refB) {
        if (refA == refB) {
            return true;
        }
        int n = pool.natLimbCount(refA);
        if (n != pool.natLimbCount(refB)) {
            return false;
        }
        if (scratchA.length < n) {
            scratchA = new long[n];
            scratchB = new long[n];
        }
        pool.natLimbs(refA, scratchA, 0);
        pool.natLimbs(refB, scratchB, 0);
        for (int i = 0; i < n; i++) {
            if (scratchA[i] != scratchB[i]) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int workerNatCount() {
        return pool.isWorkerPool() ? pool.natCount() : -1;
    }

    @Override
    public int mkNatWord(long unsignedValue) {
        return overlay.mkNatLit(unsignedValue);
    }

    @Override
    public int mkNatBig(long[] limbs, int from, int len) {
        return overlay.mkNatLitBig(pool.addNat(limbs, from, len));
    }

    @Override
    public int strLength(int strLit) {
        return pool.stringCodePointCount(terms.a(strLit));
    }

    @Override
    public int strCodePoint(int strLit, int index) {
        return pool.stringCodePointAt(terms.a(strLit), index);
    }

    @Override
    public boolean strEquals(int strLitA, int strLitB) {
        int a = terms.a(strLitA);
        int b = terms.a(strLitB);
        return a == b || pool.stringEquals(a, b);
    }
}
