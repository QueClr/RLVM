package rlvm.spike.machine;

/**
 * The static operation library: hash-consed values in a flat arena of ints.
 *
 * A value id is either
 * <ul>
 * <li>negative: an opaque atom {@code -(x + 1)} (experiment 1: the argument term x; experiment 2: the free
 * variable of level x) - never stored as a record, or</li>
 * <li>positive: the word offset of its record in the arena H.</li>
 * </ul>
 * Records (word 0 = header: kind in bits 0..3, auxiliary number in bits 4..31):
 * <pre>
 *   APP    [hdr, fn, arg]                    neutral spine cell
 *   PROJ   [hdr | idx<<4, struct]
 *   SORT   [hdr, level]    CONST [hdr, name]    LIT [hdr, expr]
 *   LAM/PI [hdr | ncap<<4, expr, cap_1 .. cap_ncap]      closure trimmed to the variables it uses
 *   experiment 2 only: ENV [hdr, parent, val], LAME/PIE [hdr, expr, env], TAPP/TLAM/TPI [hdr, a, b],
 *                      TBVAR [hdr, idx], TPROJ [hdr | idx<<4, t]
 * </pre>
 * Hash-consing: open addressing with linear probing over a power-of-two table of value ids; keys are compared
 * against the arena words directly.
 *
 * Every constructor is split into {@code findK} (probe; no allocation, no exception: this is what Truffle
 * partial evaluation may inline) and {@code insK} (slow path).
 */
public final class Vals {
    private Vals() {
    }

    public static final int K_APP = 1, K_PROJ = 2, K_SORT = 3, K_CONST = 4, K_LIT = 5, K_LAM = 6, K_PI = 7, K_ENV = 8, K_LAME = 9, K_PIE = 10,
                    K_TAPP = 11, K_TLAM = 12, K_TPI = 13, K_TBVAR = 14, K_TPROJ = 15;

    /** Next free arena word. */
    public static int hp;
    /** Number of interned records. */
    public static int count;
    public static int tmask;
    private static int tlimit;
    /** When false (experiment 2, "lean" configuration) records are bump-allocated without interning. */
    public static boolean INTERN = true;

    public static void init(int hcapWords, int tbits) {
        Mem.alloc(hcapWords, 1 << tbits);
        hp = 8;
        count = 0;
        tmask = (1 << tbits) - 1;
        tlimit = (1 << tbits) / 2;
    }

    public static int kindOf(int v) {
        return Mem.h(v) & 15;
    }

    // ---------------------------------------------------------------- hashing

    public static int hash2(int hdr, int a) {
        int h = (a * 0x9E3779B1) ^ (hdr * 0x85EBCA77);
        h ^= h >>> 15;
        h *= 0x2C1B3C6D;
        h ^= h >>> 12;
        return h;
    }

    public static int hash3(int hdr, int a, int b) {
        int h = a * 0x9E3779B1 + b;
        h = (h ^ (h >>> 15)) * 0x85EBCA77;
        h ^= hdr * 0x27D4EB2F;
        h = (h ^ (h >>> 13)) * 0xC2B2AE3D;
        h ^= h >>> 16;
        return h;
    }

    private static int hashStep(int h, int x) {
        h = (h ^ x) * 0x9E3779B1;
        return h ^ (h >>> 15);
    }

    // ---------------------------------------------------------------- 2-word records

    /** Returns the value id if present, else {@code -1 - slot} for the free table slot. */
    public static int find2(int hdr, int a) {
        int i = hash2(hdr, a) & tmask;
        for (;;) {
            int v = Mem.t(i);
            if (v == 0) {
                return -1 - i;
            }
            if (Mem.h(v) == hdr && Mem.h(v + 1) == a) {
                return v;
            }
            i = (i + 1) & tmask;
        }
    }

    public static int ins2(int slot, int hdr, int a) {
        int v = hp;
        if (v + 2 > Mem.hcap) {
            Mem.growH(v + 2);
        }
        Mem.hset(v, hdr);
        Mem.hset(v + 1, a);
        hp = v + 2;
        Mem.tset(slot, v);
        if (++count > tlimit) {
            growTable();
        }
        return v;
    }

    public static int mk2(int hdr, int a) {
        if (!INTERN) {
            return bump2(hdr, a);
        }
        int r = find2(hdr, a);
        return r > 0 ? r : ins2(-1 - r, hdr, a);
    }

    // ---------------------------------------------------------------- 3-word records

    public static int find3(int hdr, int a, int b) {
        int i = hash3(hdr, a, b) & tmask;
        for (;;) {
            int v = Mem.t(i);
            if (v == 0) {
                return -1 - i;
            }
            if (Mem.h(v) == hdr && Mem.h(v + 1) == a && Mem.h(v + 2) == b) {
                return v;
            }
            i = (i + 1) & tmask;
        }
    }

    public static int ins3(int slot, int hdr, int a, int b) {
        int v = hp;
        if (v + 3 > Mem.hcap) {
            Mem.growH(v + 3);
        }
        Mem.hset(v, hdr);
        Mem.hset(v + 1, a);
        Mem.hset(v + 2, b);
        hp = v + 3;
        Mem.tset(slot, v);
        if (++count > tlimit) {
            growTable();
        }
        return v;
    }

    public static int mk3(int hdr, int a, int b) {
        if (!INTERN) {
            return bump3(hdr, a, b);
        }
        int r = find3(hdr, a, b);
        return r > 0 ? r : ins3(-1 - r, hdr, a, b);
    }

    public static int mkApp(int f, int a) {
        int r = find3(K_APP, f, a);
        return r > 0 ? r : ins3(-1 - r, K_APP, f, a);
    }

    /** 2-word record, always interned (experiment 1: PROJ, SORT, CONST, LIT). */
    public static int mk2i(int hdr, int a) {
        int r = find2(hdr, a);
        return r > 0 ? r : ins2(-1 - r, hdr, a);
    }

    // ---------------------------------------------------------------- closures: [hdr | n<<4, e, caps...]

    /** {@code hdr} already contains the capture count; captures are {@code src[off .. off+n)}. */
    public static int findN(int hdr, int e, int[] src, int off, int n) {
        int h = hash2(hdr, e);
        for (int k = 0; k < n; k++) {
            h = hashStep(h, src[off + k]);
        }
        int i = (h ^ (h >>> 13)) & tmask;
        outer: for (;;) {
            int v = Mem.t(i);
            if (v == 0) {
                return -1 - i;
            }
            if (Mem.h(v) == hdr && Mem.h(v + 1) == e) {
                for (int k = 0; k < n; k++) {
                    if (Mem.h(v + 2 + k) != src[off + k]) {
                        i = (i + 1) & tmask;
                        continue outer;
                    }
                }
                return v;
            }
            i = (i + 1) & tmask;
        }
    }

    public static int insN(int slot, int hdr, int e, int[] src, int off, int n) {
        int v = hp;
        if (v + 2 + n > Mem.hcap) {
            Mem.growH(v + 2 + n);
        }
        Mem.hset(v, hdr);
        Mem.hset(v + 1, e);
        for (int k = 0; k < n; k++) {
            Mem.hset(v + 2 + k, src[off + k]);
        }
        hp = v + 2 + n;
        Mem.tset(slot, v);
        if (++count > tlimit) {
            growTable();
        }
        return v;
    }

    public static int mkN(int hdr, int e, int[] src, int off, int n) {
        int r = findN(hdr, e, src, off, n);
        return r > 0 ? r : insN(-1 - r, hdr, e, src, off, n);
    }

    // ---------------------------------------------------------------- no interning (experiment 2 "lean")

    private static int bump2(int hdr, int a) {
        int v = hp;
        if (v + 2 > Mem.hcap) {
            Mem.growH(v + 2);
        }
        Mem.hset(v, hdr);
        Mem.hset(v + 1, a);
        hp = v + 2;
        return v;
    }

    private static int bump3(int hdr, int a, int b) {
        int v = hp;
        if (v + 3 > Mem.hcap) {
            Mem.growH(v + 3);
        }
        Mem.hset(v, hdr);
        Mem.hset(v + 1, a);
        Mem.hset(v + 2, b);
        hp = v + 3;
        return v;
    }

    // ---------------------------------------------------------------- table growth

    private static int hashOf(int v) {
        int hdr = Mem.h(v);
        switch (hdr & 15) {
            case K_APP, K_ENV, K_LAME, K_PIE, K_TAPP, K_TLAM, K_TPI:
                return hash3(hdr, Mem.h(v + 1), Mem.h(v + 2));
            case K_LAM, K_PI: {
                int n = hdr >>> 4;
                int h = hash2(hdr, Mem.h(v + 1));
                for (int k = 0; k < n; k++) {
                    h = hashStep(h, Mem.h(v + 2 + k));
                }
                return h ^ (h >>> 13);
            }
            default:
                return hash2(hdr, Mem.h(v + 1));
        }
    }

    private static void growTable() {
        int oldCap = tmask + 1;
        int cap = oldCap << 1;
        long old = Mem.swapT(cap);
        int m = cap - 1;
        for (int i = 0; i < oldCap; i++) {
            int v = Mem.oldT(old, i);
            if (v != 0) {
                int j = hashOf(v) & m;
                while (Mem.t(j) != 0) {
                    j = (j + 1) & m;
                }
                Mem.tset(j, v);
            }
        }
        Mem.freeOldT(old);
        tmask = m;
        tlimit = cap / 2;
    }
}
