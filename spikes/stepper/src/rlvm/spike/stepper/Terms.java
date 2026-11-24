package rlvm.spike.stepper;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * The term table: the export's expressions (dense ids, columns) followed by the normal forms that readback
 * emits. Terms {@code >= base} are hash-consed ("scratch region"); the export region is taken as it is.
 *
 * <pre>
 *   kind   ea        eb      ec
 *   BVAR   index
 *   SORT   level id
 *   CONST  name id                      (universe arguments dropped: the spike ignores levels)
 *   APP    fn        arg
 *   LAM    type      body
 *   PI     type      body
 *   LET    type      value   body
 *   LIT                                 (natVal / strVal: opaque, identity = expression id)
 *   PROJ   typeName  idx     struct     (normal forms: typeName = 0)
 * </pre>
 *
 * {@code flag[e] & INERT}: e is an application or projection nest that evaluates by construction alone: every
 * application head in it is a constant, every leaf is a variable, sort, constant, literal, lambda or pi (the
 * last two evaluate to closures without entering the body).
 *
 * The NDJSON reader and the binary cache are taken from spikes/machine (Corpus.java), reduced to what the
 * spike needs.
 */
public final class Terms {
    private Terms() {
    }

    public static final byte BVAR = 0, SORT = 1, CONST = 2, APP = 3, LAM = 4, PI = 5, LET = 6, LIT = 7, PROJ = 8;
    public static final byte INERT = 1;
    public static final byte CK_AXIOM = 0, CK_DEF = 1, CK_THM = 2, CK_OPAQUE = 3, CK_QUOT = 4, CK_IND = 5, CK_CTOR = 6, CK_REC = 7;

    public static byte[] kind, flag;
    public static int[] ea, eb, ec;
    /** Number of terms; first hash-consed term (= size of the export region). */
    public static int n, base;

    public static int nConst;
    public static int[] constType, constValue;
    public static byte[] constKind;

    private static int[] tab;
    private static int tmask, tcount;
    public static long probes;

    // ------------------------------------------------------------------ hash-consed region

    private static int hash(int k, int a, int b, int c) {
        int h = a * 0x9E3779B1 + b;
        h = (h ^ (h >>> 15)) * 0x85EBCA77;
        h ^= (k + c * 31) * 0x27D4EB2F;
        h = (h ^ (h >>> 13)) * 0xC2B2AE3D;
        return h ^ (h >>> 16);
    }

    /** The term {@code (k, a, b, c)} of the hash-consed region. */
    public static int mk(int k, int a, int b, int c) {
        if (Vals.STATS) {
            probes++;
        }
        int[] t = tab;
        int m = tmask;
        int i = hash(k, a, b, c) & m;
        for (;;) {
            int e = t[i];
            if (e == 0) {
                return insert(i, k, a, b, c);
            }
            if (kind[e] == k && ea[e] == a && eb[e] == b && ec[e] == c) {
                return e;
            }
            i = (i + 1) & m;
        }
    }

    private static int insert(int slot, int k, int a, int b, int c) {
        int e = n;
        if (e == kind.length) {
            grow(e + (e >> 2) + 1024);
        }
        kind[e] = (byte) k;
        ea[e] = a;
        eb[e] = b;
        ec[e] = c;
        setFlag(e);
        n = e + 1;
        tab[slot] = e;
        if (++tcount * 2 > tmask) {
            rehash((tmask + 1) << 1);
        }
        return e;
    }

    private static void grow(int cap) {
        kind = Arrays.copyOf(kind, cap);
        flag = Arrays.copyOf(flag, cap);
        ea = Arrays.copyOf(ea, cap);
        eb = Arrays.copyOf(eb, cap);
        ec = Arrays.copyOf(ec, cap);
    }

    private static void rehash(int cap) {
        int[] t = new int[cap];
        int m = cap - 1;
        for (int e = base; e < n; e++) {
            int i = hash(kind[e], ea[e], eb[e], ec[e]) & m;
            while (t[i] != 0) {
                i = (i + 1) & m;
            }
            t[i] = e;
        }
        tab = t;
        tmask = m;
    }

    /** Drops every normal form (start of a pass). */
    public static void reset() {
        n = base;
        tcount = 0;
        Arrays.fill(tab, 0);
    }

    /** Capacity for {@code extra} more terms and a table of {@code 2^tbits} slots. */
    public static void reserve(int extra, int tbits) {
        if (base == 0) {
            throw new IllegalStateException("term id 0 must belong to the export");
        }
        grow(base + extra);
        tab = new int[1 << tbits];
        tmask = (1 << tbits) - 1;
        tcount = 0;
    }

    public static int tableSize() {
        return tmask + 1;
    }

    private static boolean atomOrInert(int t) {
        byte k = kind[t];
        return k == BVAR || k == SORT || k == CONST || k == LIT || k == LAM || k == PI || (flag[t] & INERT) != 0;
    }

    private static void setFlag(int e) {
        byte k = kind[e];
        if (k == APP) {
            int fn = ea[e];
            boolean fnOk = kind[fn] == CONST || (kind[fn] == APP && (flag[fn] & INERT) != 0);
            flag[e] = fnOk && atomOrInert(eb[e]) ? INERT : 0;
        } else if (k == PROJ) {
            flag[e] = atomOrInert(ec[e]) ? INERT : 0;
        } else {
            flag[e] = 0;
        }
    }

    // ------------------------------------------------------------------ NDJSON (lean4export 3.1.0, canonical lines)

    private static byte[] buf;
    private static int p;

    private static long num() {
        byte[] b = buf;
        int q = p;
        while (b[q] < '0' || b[q] > '9') {
            q++;
        }
        long v = 0;
        while (b[q] >= '0' && b[q] <= '9') {
            v = v * 10 + (b[q++] - '0');
        }
        p = q;
        return v;
    }

    private static int inum() {
        long v = num();
        if (v > Integer.MAX_VALUE) {
            throw new IllegalStateException("id too large: " + v);
        }
        return (int) v;
    }

    private static void expect(boolean b, int at) {
        if (!b) {
            throw new IllegalStateException("non-canonical line at byte " + at);
        }
    }

    /** Finds {@code "key":} in the current line (from {@code from}) and returns the number after it, or -1. */
    private static int field(int from, int lineEnd, String key) {
        byte[] b = buf;
        int kl = key.length();
        outer: for (int i = from; i + kl + 3 < lineEnd; i++) {
            if (b[i] == '"' && b[i + kl + 1] == '"' && b[i + kl + 2] == ':') {
                for (int j = 0; j < kl; j++) {
                    if (b[i + 1 + j] != key.charAt(j)) {
                        continue outer;
                    }
                }
                int q = i + kl + 3;
                if (b[q] < '0' || b[q] > '9') {
                    continue;
                }
                p = q;
                return inum();
            }
        }
        return -1;
    }

    /**
     * Reads the first {@code maxBytes} bytes of an export (0 = all), cut at a line end. Declarations: every
     * {@code "type":N} / {@code "value":N} pair of a declaration line (axiom, def, thm, opaque, quot, and the
     * types, constructors and recursors of an inductive block).
     */
    public static void parseNdjson(Path file, long maxBytes) throws IOException {
        long size = Files.size(file);
        long want = maxBytes > 0 ? Math.min(size, maxBytes) : size;
        if (want > Integer.MAX_VALUE - 16) {
            throw new IOException("file too large for this reader; give a byte limit");
        }
        buf = new byte[(int) want];
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            raf.readFully(buf);
        }
        int len = buf.length;
        while (len > 0 && buf[len - 1] != '\n') {
            len--;
        }
        int lines = 0;
        for (int i = 0; i < len; i++) {
            if (buf[i] == '\n') {
                lines++;
            }
        }
        kind = new byte[lines + 1];
        ea = new int[lines + 1];
        eb = new int[lines + 1];
        ec = new int[lines + 1];
        int ne = 0;
        int[] cType = new int[1 << 16], cValue = new int[1 << 16];
        byte[] cKind = new byte[1 << 16];
        int nc = 0;
        p = 0;
        while (buf[p] != '\n') { // line 1 = meta
            p++;
        }
        p++;
        while (p < len) {
            int ls = p;
            byte c2 = buf[ls + 2], c3 = buf[ls + 3];
            if (c2 == 'a' && c3 == 'p') { // {"app":{"arg":A,"fn":F},"ie":N}
                int arg = inum(), fn = inum(), id = inum();
                expect(id == ne, ls);
                kind[ne] = APP;
                ea[ne] = fn;
                eb[ne] = arg;
                ne++;
            } else if (c2 == 'i' && c3 == 'e') { // {"ie":N,"<key>":...
                int id = inum();
                expect(id == ne, ls);
                byte k0 = buf[p + 2], k1 = buf[p + 3];
                if (k0 == 'l' && k1 == 'a') { // lam: body, name, type
                    int body = inum();
                    inum();
                    int type = inum();
                    kind[ne] = LAM;
                    ea[ne] = type;
                    eb[ne] = body;
                } else if (k0 == 'l' && k1 == 'e') { // letE: body, name, nondep, type, value
                    int body = inum();
                    inum();
                    int type = inum(), value = inum();
                    kind[ne] = LET;
                    ea[ne] = type;
                    eb[ne] = value;
                    ec[ne] = body;
                } else if (k0 == 'p') { // proj: idx, struct, typeName
                    int idx = inum(), struct = inum(), tn = inum();
                    kind[ne] = PROJ;
                    ea[ne] = tn;
                    eb[ne] = idx;
                    ec[ne] = struct;
                } else if (k0 == 's' && k1 == 'o') { // sort
                    kind[ne] = SORT;
                    ea[ne] = inum();
                } else if ((k0 == 'n' && k1 == 'a') || (k0 == 's' && k1 == 't')) { // natVal / strVal
                    kind[ne] = LIT;
                } else {
                    throw new IllegalStateException("unsupported expr line at byte " + ls);
                }
                ne++;
            } else if (c2 == 'f') { // forallE: body, name, type, ie
                int body = inum();
                inum();
                int type = inum(), id = inum();
                expect(id == ne, ls);
                kind[ne] = PI;
                ea[ne] = type;
                eb[ne] = body;
                ne++;
            } else if (c2 == 'c' && c3 == 'o') { // {"const":{"name":N,"us":[..]},"ie":N}
                int name = inum();
                while (buf[p] != ']') {
                    p++;
                }
                int id = inum();
                expect(id == ne, ls);
                kind[ne] = CONST;
                ea[ne] = name;
                ne++;
            } else if (c2 == 'b') { // {"bvar":N,"ie":N}
                int idx = inum(), id = inum();
                expect(id == ne, ls);
                kind[ne] = BVAR;
                ea[ne] = idx;
                ne++;
            } else if (c2 == 'i' && (c3 == 'l' || (c3 == 'n' && buf[ls + 4] == '"'))) {
                // name / level: ignored
            } else { // declaration line
                int le = ls;
                while (buf[le] != '\n') {
                    le++;
                }
                byte ck = c2 == 'a' ? CK_AXIOM : c2 == 'd' ? CK_DEF : c2 == 't' ? CK_THM : c2 == 'o' ? CK_OPAQUE : c2 == 'q' ? CK_QUOT : CK_IND;
                if (ck == CK_IND) {
                    // every "type":N in the block (types, constructors, recursors); rule right-hand sides are skipped
                    int from = ls;
                    for (;;) {
                        int t = field(from, le, "type");
                        if (t < 0) {
                            break;
                        }
                        from = p;
                        if (nc == cType.length) {
                            cType = Arrays.copyOf(cType, nc * 2);
                            cValue = Arrays.copyOf(cValue, nc * 2);
                            cKind = Arrays.copyOf(cKind, nc * 2);
                        }
                        cType[nc] = t;
                        cValue[nc] = -1;
                        cKind[nc++] = CK_IND;
                    }
                } else {
                    int t = field(ls, le, "type");
                    int v = ck == CK_DEF || ck == CK_THM || ck == CK_OPAQUE ? field(ls, le, "value") : -1;
                    expect(t >= 0, ls);
                    if (nc == cType.length) {
                        cType = Arrays.copyOf(cType, nc * 2);
                        cValue = Arrays.copyOf(cValue, nc * 2);
                        cKind = Arrays.copyOf(cKind, nc * 2);
                    }
                    cType[nc] = t;
                    cValue[nc] = v;
                    cKind[nc++] = ck;
                }
                p = le;
            }
            while (buf[p] != '\n') {
                p++;
            }
            p++;
        }
        buf = null;
        n = base = ne;
        nConst = nc;
        constType = Arrays.copyOf(cType, nc);
        constValue = Arrays.copyOf(cValue, nc);
        constKind = Arrays.copyOf(cKind, nc);
    }

    // ------------------------------------------------------------------ binary cache

    private static final int MAGIC = 0x524C5332;

    public static void save(Path file) throws IOException {
        long size = 12L + 13L * base + 9L * nConst;
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
            raf.setLength(size);
            ByteBuffer bb = raf.getChannel().map(FileChannel.MapMode.READ_WRITE, 0, size).order(ByteOrder.LITTLE_ENDIAN);
            bb.putInt(MAGIC).putInt(base).putInt(nConst);
            bb.put(kind, 0, base);
            bb.asIntBuffer().put(ea, 0, base).put(eb, 0, base).put(ec, 0, base);
            bb.position(bb.position() + 12 * base);
            bb.asIntBuffer().put(constType, 0, nConst).put(constValue, 0, nConst);
            bb.position(bb.position() + 8 * nConst);
            bb.put(constKind, 0, nConst);
        }
    }

    public static void load(Path file) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            long size = raf.length();
            ByteBuffer bb = raf.getChannel().map(FileChannel.MapMode.READ_ONLY, 0, size).order(ByteOrder.LITTLE_ENDIAN);
            if (bb.getInt() != MAGIC) {
                throw new IOException("bad cache file " + file);
            }
            base = n = bb.getInt();
            nConst = bb.getInt();
            kind = new byte[base];
            bb.get(kind);
            ea = new int[base];
            eb = new int[base];
            ec = new int[base];
            bb.asIntBuffer().get(ea).get(eb).get(ec);
            bb.position(bb.position() + 12 * base);
            constType = new int[nConst];
            constValue = new int[nConst];
            bb.asIntBuffer().get(constType).get(constValue);
            bb.position(bb.position() + 8 * nConst);
            constKind = new byte[nConst];
            bb.get(constKind);
        }
    }

    /** Flags of the export region (children precede parents in an export). */
    public static void derive() {
        flag = new byte[kind.length];
        for (int e = 0; e < base; e++) {
            setFlag(e);
        }
    }
}
