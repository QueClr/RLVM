package rlvm.nat.bench;

import java.math.BigInteger;
import java.util.Arrays;

/**
 * The alternative design that {@code NatStore} was measured against: values of 2^63 and more are
 * {@code java.math.BigInteger} objects in a side table, hash-consed by value with the same open-addressing
 * scheme and the same handle encoding. Only what the benchmark needs; not part of the product.
 */
final class BigIntegerStore {
    private static final long BIG = Long.MIN_VALUE;
    private BigInteger[] vals = new BigInteger[64];
    private int[] hashes = new int[64];
    private int[] table = new int[128];
    private int mask = 127;
    private int next = 1;

    BigInteger get(long h) {
        return h >= 0 ? BigInteger.valueOf(h) : vals[(int) h];
    }

    long intern(BigInteger v) {
        if (v.bitLength() < 64) {
            return v.longValue();
        }
        int h = v.hashCode();
        h ^= h >>> 15;
        h *= 0x2C1B3C6D;
        h ^= h >>> 12;
        int[] t = table;
        int i = h & mask;
        for (;;) {
            int id = t[i];
            if (id == 0) {
                break;
            }
            if (hashes[id] == h && vals[id].equals(v)) {
                return BIG | id;
            }
            i = (i + 1) & mask;
        }
        int id = next++;
        if (id == vals.length) {
            vals = Arrays.copyOf(vals, 2 * id);
            hashes = Arrays.copyOf(hashes, 2 * id);
        }
        vals[id] = v;
        hashes[id] = h;
        t[i] = id;
        if (2 * id > t.length) {
            int[] nt = new int[t.length * 2];
            int m = nt.length - 1;
            for (int k = 1; k < next; k++) {
                int j = hashes[k] & m;
                while (nt[j] != 0) {
                    j = (j + 1) & m;
                }
                nt[j] = k;
            }
            table = nt;
            mask = m;
        }
        return BIG | id;
    }

    int mark() {
        return next;
    }

    void truncate(int mark) {
        for (int id = next - 1; id >= mark; id--) {
            int i = hashes[id] & mask;
            while (table[i] != id) {
                i = (i + 1) & mask;
            }
            table[i] = 0;
            vals[id] = null;
        }
        next = mark;
    }
}
