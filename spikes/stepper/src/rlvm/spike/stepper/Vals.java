package rlvm.spike.stepper;

import java.util.Arrays;

/**
 * The operation library shared by every control convention: hash-consed values in one flat {@code int[]}.
 *
 * A value id is negative for a free variable ({@code -(level + 1)}) and otherwise the word offset of its record
 * in {@link #H} (word 0 = header: kind in bits 0..3, auxiliary number above):
 * <pre>
 *   APP   [hdr, fn, arg]              neutral application cell
 *   PROJ  [hdr | idx << 4, struct]
 *   SORT  [hdr, level]    CONST [hdr, name]    LIT [hdr, term]
 *   ENV   [hdr, parent, value]        environment cell (0 = empty environment)
 *   LAM   [hdr, term, env]            closure of a lambda term
 *   PI    [hdr, term, env]            closure of a pi term
 * </pre>
 * Open addressing with linear probing over a power-of-two table of value ids, load at most 1/2 (the code is
 * spikes/machine's Vals with the access-mode switch removed).
 */
public final class Vals {
    private Vals() {
    }

    public static final int K_APP = 1, K_PROJ = 2, K_SORT = 3, K_CONST = 4, K_LIT = 5, K_ENV = 6, K_LAM = 7, K_PI = 8;

    /** Counters are compiled in only when the process is started with -Drlvm.stats=true (JVM mode). */
    public static final boolean STATS = Boolean.getBoolean("rlvm.stats");

    public static int[] H, T;
    /** Next free arena word; number of records; table mask. */
    public static int hp, count, tmask;
    private static int tlimit;
    public static long probes;

    public static void init(int hcapWords, int tbits) {
        H = new int[hcapWords];
        T = new int[1 << tbits];
        hp = 8;
        count = 0;
        tmask = (1 << tbits) - 1;
        tlimit = (1 << tbits) / 2;
    }

    /** Forgets every value (start of a pass); capacities are kept. */
    public static void reset() {
        Arrays.fill(T, 0);
        hp = 8;
        count = 0;
    }

    private static int hash2(int hdr, int a) {
        int h = (a * 0x9E3779B1) ^ (hdr * 0x85EBCA77);
        h ^= h >>> 15;
        h *= 0x2C1B3C6D;
        h ^= h >>> 12;
        return h;
    }

    private static int hash3(int hdr, int a, int b) {
        int h = a * 0x9E3779B1 + b;
        h = (h ^ (h >>> 15)) * 0x85EBCA77;
        h ^= hdr * 0x27D4EB2F;
        h = (h ^ (h >>> 13)) * 0xC2B2AE3D;
        h ^= h >>> 16;
        return h;
    }

    public static int mk2(int hdr, int a) {
        if (STATS) {
            probes++;
        }
        int[] t = T, h = H;
        int m = tmask;
        int i = hash2(hdr, a) & m;
        for (;;) {
            int v = t[i];
            if (v == 0) {
                return ins2(i, hdr, a);
            }
            if (h[v] == hdr && h[v + 1] == a) {
                return v;
            }
            i = (i + 1) & m;
        }
    }

    public static int mk3(int hdr, int a, int b) {
        if (STATS) {
            probes++;
        }
        int[] t = T, h = H;
        int m = tmask;
        int i = hash3(hdr, a, b) & m;
        for (;;) {
            int v = t[i];
            if (v == 0) {
                return ins3(i, hdr, a, b);
            }
            if (h[v] == hdr && h[v + 1] == a && h[v + 2] == b) {
                return v;
            }
            i = (i + 1) & m;
        }
    }

    private static int ins2(int slot, int hdr, int a) {
        int v = hp;
        if (v + 2 > H.length) {
            growH(v + 2);
        }
        H[v] = hdr;
        H[v + 1] = a;
        hp = v + 2;
        T[slot] = v;
        if (++count > tlimit) {
            growTable();
        }
        return v;
    }

    private static int ins3(int slot, int hdr, int a, int b) {
        int v = hp;
        if (v + 3 > H.length) {
            growH(v + 3);
        }
        H[v] = hdr;
        H[v + 1] = a;
        H[v + 2] = b;
        hp = v + 3;
        T[slot] = v;
        if (++count > tlimit) {
            growTable();
        }
        return v;
    }

    private static void growH(int need) {
        H = Arrays.copyOf(H, Math.max(need, H.length + (H.length >> 1)));
    }

    private static void growTable() {
        int[] old = T;
        int cap = old.length << 1;
        int[] t = new int[cap];
        int m = cap - 1;
        for (int v : old) {
            if (v != 0) {
                int hdr = H[v];
                int k = hdr & 15;
                int j = (k == K_APP || k == K_ENV || k == K_LAM || k == K_PI ? hash3(hdr, H[v + 1], H[v + 2]) : hash2(hdr, H[v + 1])) & m;
                while (t[j] != 0) {
                    j = (j + 1) & m;
                }
                t[j] = v;
            }
        }
        T = t;
        tmask = m;
        tlimit = cap / 2;
    }

    /** de Bruijn lookup in an environment list. */
    public static int lookup(int env, int i) {
        int[] h = H;
        while (i > 0) {
            env = h[env + 1];
            i--;
        }
        return h[env + 2];
    }
}
