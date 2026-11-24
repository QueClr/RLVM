package rlvm.api.bind;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Array-backed {@link LitAccess}: literal "terms" and a two-layer literal pool without a term store.
 * Term ids start at 1. Pool references are positive for "shared" entries (export literals, made by
 * {@link #poolLit}) and negative for "worker" entries (made through {@link #mkNatBig}); the worker
 * layer is interned by content and finds shared entries, like {@code rlvm.store.LitPool}. The shared
 * layer can hold the same number twice on request, and numbers below 2^64, to model writers that do
 * not canonicalise.
 */
final class FakeLits implements LitAccess {
    private static final BigInteger TWO_64 = BigInteger.ONE.shiftLeft(64);

    private final List<long[]> terms = new ArrayList<>(); // {0, word} | {1, ref} | {2, string index}
    private final Map<Long, Integer> wordIds = new HashMap<>();
    private final Map<Integer, Integer> refIds = new HashMap<>();
    private final List<long[]> shared = new ArrayList<>();
    private final List<long[]> worker = new ArrayList<>();
    private final Map<BigInteger, Integer> sharedByValue = new HashMap<>();
    private final Map<BigInteger, Integer> workerByValue = new HashMap<>();
    private final List<int[]> strings = new ArrayList<>();
    int poolReads;
    int workerEntriesEver;

    FakeLits() {
        terms.add(null);
        shared.add(null);
        worker.add(null);
    }

    private static long[] limbsOf(BigInteger v) {
        int n = Math.max(1, (v.bitLength() + 63) / 64);
        long[] limbs = new long[n];
        for (int i = 0; i < n; i++) {
            limbs[i] = v.shiftRight(64 * i).longValue();
        }
        return limbs;
    }

    private static BigInteger valueOf(long[] limbs, int from, int len) {
        BigInteger r = BigInteger.ZERO;
        for (int i = len - 1; i >= 0; i--) {
            r = r.shiftLeft(64).or(new BigInteger(Long.toUnsignedString(limbs[from + i])));
        }
        return r;
    }

    private int termForRef(int ref) {
        Integer id = refIds.get(ref);
        if (id == null) {
            terms.add(new long[] {1, ref});
            id = terms.size() - 1;
            refIds.put(ref, id);
        }
        return id;
    }

    /** A term for a NEW shared-pool entry holding {@code v} (a duplicate entry if the value is already pooled). */
    int poolLit(BigInteger v) {
        shared.add(limbsOf(v));
        int ref = shared.size() - 1;
        sharedByValue.putIfAbsent(v, ref);
        return termForRef(ref);
    }

    /** A literal for {@code v} as the loader stores it: a word literal below 2^64, else a (content-interned) shared entry. */
    int lit(BigInteger v) {
        if (v.compareTo(TWO_64) < 0) {
            return mkNatWord(v.longValue());
        }
        Integer ref = sharedByValue.get(v);
        return ref != null ? termForRef(ref) : poolLit(v);
    }

    int str(String s) {
        strings.add(s.codePoints().toArray());
        terms.add(new long[] {2, strings.size() - 1});
        return terms.size() - 1;
    }

    /** Value of a literal term. */
    BigInteger value(int lit) {
        long[] t = terms.get(lit);
        if (t[0] == 0) {
            return new BigInteger(Long.toUnsignedString(t[1]));
        }
        long[] limbs = entry((int) t[1]);
        return valueOf(limbs, 0, limbs.length);
    }

    private long[] entry(int ref) {
        long[] e = ref > 0 ? shared.get(ref) : worker.get(-ref);
        if (e == null) {
            throw new IllegalArgumentException("dead pool reference " + ref);
        }
        return e;
    }

    int workerEntries() {
        return worker.size() - 1;
    }

    /** Session reset: the worker layer and the overlay terms that refer to it are gone; references are reissued. */
    void resetWorker() {
        refIds.keySet().removeIf(ref -> ref < 0);
        for (int i = 1; i < terms.size(); i++) {
            long[] t = terms.get(i);
            if (t != null && t[0] == 1 && t[1] < 0) {
                terms.set(i, new long[] {3, 0}); // a dead term: any use fails
            }
        }
        worker.clear();
        worker.add(null);
        workerByValue.clear();
    }

    @Override
    public boolean natIsWord(int lit) {
        long kind = terms.get(lit)[0];
        if (kind > 1) {
            throw new IllegalArgumentException("not a live NATLIT: " + lit);
        }
        return kind == 0;
    }

    @Override
    public long natWord(int lit) {
        long[] t = terms.get(lit);
        if (t[0] != 0) {
            throw new IllegalArgumentException("not a word literal: " + lit);
        }
        return t[1];
    }

    @Override
    public int natRef(int lit) {
        long[] t = terms.get(lit);
        if (t[0] != 1) {
            throw new IllegalArgumentException("not a big literal: " + lit);
        }
        return (int) t[1];
    }

    @Override
    public int bigLimbCount(int ref) {
        return entry(ref).length;
    }

    @Override
    public void bigLimbs(int ref, long[] dst, int off) {
        poolReads++;
        long[] limbs = entry(ref);
        System.arraycopy(limbs, 0, dst, off, limbs.length);
    }

    @Override
    public boolean bigEquals(int refA, int refB) {
        return Arrays.equals(entry(refA), entry(refB));
    }

    @Override
    public int mkNatWord(long unsignedValue) {
        Integer id = wordIds.get(unsignedValue);
        if (id == null) {
            terms.add(new long[] {0, unsignedValue});
            id = terms.size() - 1;
            wordIds.put(unsignedValue, id);
        }
        return id;
    }

    @Override
    public int mkNatBig(long[] limbs, int from, int len) {
        if (len < 2 || limbs[from + len - 1] == 0) {
            throw new IllegalArgumentException("NatBinding must pool only canonical numbers of 2^64 and above");
        }
        BigInteger v = valueOf(limbs, from, len);
        Integer ref = sharedByValue.get(v);
        if (ref == null) {
            ref = workerByValue.get(v);
        }
        if (ref == null) {
            worker.add(Arrays.copyOfRange(limbs, from, from + len));
            ref = -(worker.size() - 1);
            workerByValue.put(v, ref);
            workerEntriesEver++;
        }
        return termForRef(ref);
    }

    @Override
    public int strLength(int strLit) {
        return strings.get((int) terms.get(strLit)[1]).length;
    }

    @Override
    public int strCodePoint(int strLit, int index) {
        return strings.get((int) terms.get(strLit)[1])[index];
    }

    @Override
    public boolean strEquals(int strLitA, int strLitB) {
        return Arrays.equals(strings.get((int) terms.get(strLitA)[1]), strings.get((int) terms.get(strLitB)[1]));
    }
}
