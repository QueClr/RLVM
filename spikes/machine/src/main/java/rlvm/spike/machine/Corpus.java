package rlvm.spike.machine;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Quick reader for lean4export NDJSON (format 3.1.0, exporter-canonical lines only) and a binary cache of the
 * result, so that measured runs do not pay for JSON parsing. Everything is static: the spike has one corpus per
 * process.
 *
 * Expression table (ids are the export's dense ids):
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
 *   PROJ   typeName  idx     struct
 * </pre>
 */
public final class Corpus {
    private Corpus() {
    }

    public static final byte BVAR = 0, SORT = 1, CONST = 2, APP = 3, LAM = 4, PI = 5, LET = 6, LIT = 7, PROJ = 8;
    public static final byte CK_AXIOM = 0, CK_DEF = 1, CK_THM = 2, CK_OPAQUE = 3, CK_QUOT = 4, CK_IND = 5, CK_CTOR = 6, CK_REC = 7;
    public static final long FAR = 1L << 63;

    public static int nExpr;
    public static byte[] kind;
    public static int[] ea, eb, ec;
    /** Number of loose bound variables (max loose index + 1); 0 = closed. */
    public static int[] loose;
    /** Bit i (i &lt; 63): de Bruijn index i occurs free; bit 63: some index &gt;= 63 occurs. */
    public static long[] mask;

    public static int nConst;
    public static int[] constName, constType, constValue;
    public static byte[] constKind;
    /** name id -> constant index + 1 (0 = not a constant). */
    public static int[] constOfName;
    public static int nNames;
    /** Extra expression roots (recursor rule right-hand sides). */
    public static int[] extraRoots;
    public static String[] constStr;

    // ------------------------------------------------------------------ NDJSON parsing

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

    public static void parseNdjson(Path file) throws IOException {
        buf = Files.readAllBytes(file);
        int len = buf.length;
        int lines = 0;
        for (int i = 0; i < len; i++) {
            if (buf[i] == '\n') {
                lines++;
            }
        }
        kind = new byte[lines];
        ea = new int[lines];
        eb = new int[lines];
        ec = new int[lines];
        int[] namePre = new int[lines / 4 + 16];
        Object[] nameComp = new Object[lines / 4 + 16];
        int maxName = 0;
        int ne = 0;
        List<int[]> consts = new ArrayList<>();
        List<Integer> extra = new ArrayList<>();
        p = 0;
        // line 1 = meta
        while (buf[p] != '\n') {
            p++;
        }
        p++;
        while (p < len) {
            int ls = p;
            int le = ls;
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
            } else if (c2 == 'c') { // {"const":{"name":N,"us":[..]},"ie":N}
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
            } else if (c2 == 'i' && c3 == 'n' && buf[ls + 4] == '"') { // name
                int id = inum();
                if (id >= namePre.length) {
                    namePre = java.util.Arrays.copyOf(namePre, id * 2);
                    nameComp = java.util.Arrays.copyOf(nameComp, id * 2);
                }
                maxName = Math.max(maxName, id);
                if (buf[p + 2] == 'n') { // "num":{"i":I,"pre":P}
                    long i = num();
                    namePre[id] = inum();
                    nameComp[id] = Long.toString(i);
                } else { // "str":{"pre":P,"str":"..."}
                    namePre[id] = inum();
                    p += 8; // ,"str":"
                    int s = p;
                    while (buf[p] != '"') {
                        p += buf[p] == '\\' ? 2 : 1;
                    }
                    nameComp[id] = new String(buf, s, p - s, StandardCharsets.UTF_8);
                }
            } else if (c2 == 'i' && c3 == 'l') {
                // level: ignored
            } else { // declaration line: generic JSON
                while (buf[le] != '\n') {
                    le++;
                }
                p = ls;
                Object o = json();
                @SuppressWarnings("unchecked")
                Map<String, Object> top = (Map<String, Object>) o;
                decl(top, consts, extra);
            }
            while (buf[p] != '\n') {
                p++;
            }
            p++;
        }
        buf = null;
        nExpr = ne;
        nNames = maxName + 1;
        nConst = consts.size();
        constName = new int[nConst];
        constType = new int[nConst];
        constValue = new int[nConst];
        constKind = new byte[nConst];
        constStr = new String[nConst];
        for (int i = 0; i < nConst; i++) {
            int[] c = consts.get(i);
            constName[i] = c[0];
            constType[i] = c[1];
            constValue[i] = c[2];
            constKind[i] = (byte) c[3];
            StringBuilder sb = new StringBuilder();
            nameString(namePre, nameComp, c[0], sb);
            constStr[i] = sb.toString();
        }
        extraRoots = new int[extra.size()];
        for (int i = 0; i < extraRoots.length; i++) {
            extraRoots[i] = extra.get(i);
        }
    }

    private static void nameString(int[] pre, Object[] comp, int id, StringBuilder sb) {
        if (id == 0) {
            return;
        }
        nameString(pre, comp, pre[id], sb);
        if (sb.length() > 0) {
            sb.append('.');
        }
        sb.append(comp[id]);
    }

    private static void expect(boolean b, int at) {
        if (!b) {
            throw new IllegalStateException("non-canonical line at byte " + at);
        }
    }

    private static int i(Object o) {
        return (int) (long) (Long) o;
    }

    @SuppressWarnings("unchecked")
    private static void decl(Map<String, Object> top, List<int[]> consts, List<Integer> extra) {
        Map.Entry<String, Object> en = top.entrySet().iterator().next();
        Map<String, Object> d = (Map<String, Object>) en.getValue();
        switch (en.getKey()) {
            case "axiom" -> consts.add(new int[]{i(d.get("name")), i(d.get("type")), -1, CK_AXIOM});
            case "def" -> consts.add(new int[]{i(d.get("name")), i(d.get("type")), i(d.get("value")), CK_DEF});
            case "thm" -> consts.add(new int[]{i(d.get("name")), i(d.get("type")), i(d.get("value")), CK_THM});
            case "opaque" -> consts.add(new int[]{i(d.get("name")), i(d.get("type")), i(d.get("value")), CK_OPAQUE});
            case "quot" -> consts.add(new int[]{i(d.get("name")), i(d.get("type")), -1, CK_QUOT});
            case "inductive" -> {
                for (Object t : (List<Object>) d.get("types")) {
                    Map<String, Object> m = (Map<String, Object>) t;
                    consts.add(new int[]{i(m.get("name")), i(m.get("type")), -1, CK_IND});
                }
                for (Object t : (List<Object>) d.get("ctors")) {
                    Map<String, Object> m = (Map<String, Object>) t;
                    consts.add(new int[]{i(m.get("name")), i(m.get("type")), -1, CK_CTOR});
                }
                for (Object t : (List<Object>) d.get("recs")) {
                    Map<String, Object> m = (Map<String, Object>) t;
                    consts.add(new int[]{i(m.get("name")), i(m.get("type")), -1, CK_REC});
                    for (Object r : (List<Object>) m.get("rules")) {
                        extra.add(i(((Map<String, Object>) r).get("rhs")));
                    }
                }
            }
            default -> throw new IllegalStateException("unknown declaration kind " + en.getKey());
        }
    }

    /** Minimal JSON value parser (declaration lines only: objects, arrays, naturals, strings, booleans). */
    private static Object json() {
        byte c = buf[p];
        if (c == '{') {
            p++;
            Map<String, Object> m = new HashMap<>();
            if (buf[p] == '}') {
                p++;
                return m;
            }
            for (;;) {
                String k = (String) json();
                p++; // ':'
                m.put(k, json());
                if (buf[p++] == '}') {
                    return m;
                }
            }
        } else if (c == '[') {
            p++;
            List<Object> l = new ArrayList<>();
            if (buf[p] == ']') {
                p++;
                return l;
            }
            for (;;) {
                l.add(json());
                if (buf[p++] == ']') {
                    return l;
                }
            }
        } else if (c == '"') {
            int s = ++p;
            while (buf[p] != '"') {
                p += buf[p] == '\\' ? 2 : 1;
            }
            String r = new String(buf, s, p - s, StandardCharsets.UTF_8);
            p++;
            return r;
        } else if (c == 't') {
            p += 4;
            return Boolean.TRUE;
        } else if (c == 'f') {
            p += 5;
            return Boolean.FALSE;
        } else {
            return num();
        }
    }

    // ------------------------------------------------------------------ binary cache

    private static final int MAGIC = 0x524C5631;

    public static void save(Path file) throws IOException {
        byte[][] strs = new byte[nConst][];
        long strBytes = 0;
        for (int i = 0; i < nConst; i++) {
            strs[i] = constStr[i].getBytes(StandardCharsets.UTF_8);
            strBytes += 4 + strs[i].length;
        }
        long size = 20L + nExpr + 12L * nExpr + 12L * nConst + nConst + 4L * extraRoots.length + strBytes;
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
            raf.setLength(size);
            ByteBuffer bb = raf.getChannel().map(FileChannel.MapMode.READ_WRITE, 0, size).order(ByteOrder.LITTLE_ENDIAN);
            bb.putInt(MAGIC).putInt(nExpr).putInt(nConst).putInt(nNames).putInt(extraRoots.length);
            bb.put(kind, 0, nExpr);
            putInts(bb, ea, nExpr);
            putInts(bb, eb, nExpr);
            putInts(bb, ec, nExpr);
            putInts(bb, constName, nConst);
            putInts(bb, constType, nConst);
            putInts(bb, constValue, nConst);
            bb.put(constKind, 0, nConst);
            putInts(bb, extraRoots, extraRoots.length);
            for (byte[] s : strs) {
                bb.putInt(s.length);
                bb.put(s);
            }
        }
    }

    private static void putInts(ByteBuffer bb, int[] a, int n) {
        for (int i = 0; i < n; i++) {
            bb.putInt(a[i]);
        }
    }

    private static int[] getInts(ByteBuffer bb, int n) {
        int[] a = new int[n];
        for (int i = 0; i < n; i++) {
            a[i] = bb.getInt();
        }
        return a;
    }

    public static void load(Path file) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            long size = raf.length();
            ByteBuffer bb = raf.getChannel().map(FileChannel.MapMode.READ_ONLY, 0, size).order(ByteOrder.LITTLE_ENDIAN);
            if (bb.getInt() != MAGIC) {
                throw new IOException("bad cache file " + file);
            }
            nExpr = bb.getInt();
            nConst = bb.getInt();
            nNames = bb.getInt();
            int nx = bb.getInt();
            kind = new byte[nExpr];
            bb.get(kind);
            ea = getInts(bb, nExpr);
            eb = getInts(bb, nExpr);
            ec = getInts(bb, nExpr);
            constName = getInts(bb, nConst);
            constType = getInts(bb, nConst);
            constValue = getInts(bb, nConst);
            constKind = new byte[nConst];
            bb.get(constKind);
            extraRoots = getInts(bb, nx);
            constStr = new String[nConst];
            for (int i = 0; i < nConst; i++) {
                byte[] s = new byte[bb.getInt()];
                bb.get(s);
                constStr[i] = new String(s, StandardCharsets.UTF_8);
            }
        }
    }

    // ------------------------------------------------------------------ derived per-node facts

    public static void derive() {
        int n = nExpr;
        loose = new int[n];
        mask = new long[n];
        for (int e = 0; e < n; e++) {
            switch (kind[e]) {
                case BVAR -> {
                    loose[e] = ea[e] + 1;
                    mask[e] = 1L << Math.min(ea[e], 63);
                }
                case APP -> {
                    loose[e] = Math.max(loose[ea[e]], loose[eb[e]]);
                    mask[e] = mask[ea[e]] | mask[eb[e]];
                }
                case LAM, PI -> {
                    loose[e] = Math.max(loose[ea[e]], loose[eb[e]] - 1);
                    mask[e] = mask[ea[e]] | under(eb[e]);
                }
                case LET -> {
                    loose[e] = Math.max(Math.max(loose[ea[e]], loose[eb[e]]), loose[ec[e]] - 1);
                    mask[e] = mask[ea[e]] | mask[eb[e]] | under(ec[e]);
                }
                case PROJ -> {
                    loose[e] = loose[ec[e]];
                    mask[e] = mask[ec[e]];
                }
                default -> {
                }
            }
        }
        constOfName = new int[nNames + 1];
        for (int i = 0; i < nConst; i++) {
            constOfName[constName[i]] = i + 1;
        }
    }

    private static long under(int body) {
        return (mask[body] >>> 1) | (loose[body] > 64 ? FAR : 0);
    }
}
