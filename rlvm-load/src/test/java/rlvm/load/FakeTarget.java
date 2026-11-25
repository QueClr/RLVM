package rlvm.load;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

import rlvm.store.Kind;

/**
 * In-memory {@link LoadTarget} for the loader's own tests: flat arrays, structural name interning,
 * proof blocks kept as decoded records, promotion as specified in *agentic-note*: design-architecture-document, 3.3. It checks the
 * protocol of {@link LoadTarget} as it goes (children exist, block references point backwards, units
 * are opened and closed, names are reserved before rows are completed).
 */
final class FakeTarget implements LoadTarget {
    static final int NAME_ANON = 0;
    static final int NAME_STR = 1;
    static final int NAME_NUM = 2;
    static final int NAME_NUM_BIG = 3;
    static final int DROPPED = -1;

    // ---- names -----------------------------------------------------------------------------------
    final HashMap<String, Integer> nameIndex = new HashMap<>();
    int[] namePrefix = new int[64];
    byte[] nameKind = new byte[64];
    /** STR: UTF-8; NUM, NUM_BIG: canonical decimal digits. */
    byte[][] nameData = new byte[64][];
    int[] constOf = new int[64];
    int names = 1; // id 0 = anonymous

    // ---- literals --------------------------------------------------------------------------------
    final ArrayList<long[]> nats = new ArrayList<>();
    final ArrayList<byte[]> strings = new ArrayList<>();

    // ---- shared nodes; id 0 unused ----------------------------------------------------------------
    int[] nKind = new int[1024];
    int[] nA = new int[1024];
    int[] nB = new int[1024];
    int[] nC = new int[1024];
    int[] nFlags = new int[1024];
    boolean[] nBig = new boolean[1024];
    boolean[] nPromoted = new boolean[1024];
    int nodes = 1;
    /** (child, parent) pairs reported through {@link #singleReferrer}. */
    int[] singles = new int[1024];
    int singleCount;

    // ---- blocks: 4 ints per record (kind | BIG, a, b, c), refs as encoded by LoadTarget ---------------
    static final int BIG = 1 << 8;
    int[] bRec = new int[4096];
    int bRecords; // global record count
    int[] blockFirst = new int[64];
    int[] blockCount = new int[64];
    int[] blockRoot = new int[64];
    int blocks;
    boolean blockOpen;
    int openFirst;
    final HashMap<Integer, Integer> promo = new HashMap<>();
    long promoteCalls;

    // ---- declarations; cid 0 unused ---------------------------------------------------------------
    int[] cKind = new int[64];
    int[] cName = new int[64];
    int[] cLparams = new int[64];
    int[] cType = new int[64];
    int[] cValue = new int[64];
    int[] cHint = new int[64];
    int[] cUnit = new int[64];
    boolean[] cComplete = new boolean[64];
    int consts = 1;
    int[] uKind = new int[64];
    int[] uFirstCid = new int[64];
    int[] uSize = new int[64];
    /** Shared node count when the unit was opened (the unit's first export id). */
    int[] uFirstNode = new int[64];
    int[][] uClaims = new int[64][];
    int units;
    boolean unitOpen;
    int published;
    boolean finished;
    long unresolved;
    long unresolvedInBlocks;
    private boolean promoting;

    // ============================================================================================ names

    private int internName(int prefix, int kind, byte[] data) {
        if (prefix < 0 || prefix >= names) {
            throw new IllegalArgumentException("unknown prefix name " + prefix);
        }
        String key = prefix + ":" + kind + ":" + new String(data, StandardCharsets.ISO_8859_1);
        Integer old = nameIndex.get(key);
        if (old != null) {
            return old;
        }
        int id = names++;
        if (id == namePrefix.length) {
            namePrefix = Arrays.copyOf(namePrefix, id * 2);
            nameKind = Arrays.copyOf(nameKind, id * 2);
            nameData = Arrays.copyOf(nameData, id * 2);
            constOf = Arrays.copyOf(constOf, id * 2);
        }
        namePrefix[id] = prefix;
        nameKind[id] = (byte) kind;
        nameData[id] = data;
        nameIndex.put(key, id);
        return id;
    }

    @Override
    public int nameAnonymous() {
        return 0;
    }

    @Override
    public int nameStr(int prefix, byte[] utf8, int off, int len) {
        return internName(prefix, NAME_STR, Arrays.copyOfRange(utf8, off, off + len));
    }

    @Override
    public int nameNum(int prefix, long unsignedValue) {
        return internName(prefix, NAME_NUM, Long.toUnsignedString(unsignedValue).getBytes(StandardCharsets.US_ASCII));
    }

    @Override
    public int nameNumBig(int prefix, byte[] digits, int off, int len) {
        if (len < 20) {
            throw new IllegalArgumentException("nameNumBig for a number below 2^64");
        }
        return internName(prefix, NAME_NUM_BIG, Arrays.copyOfRange(digits, off, off + len));
    }

    @Override
    public String nameToString(int name) {
        if (name == 0) {
            return "[anonymous]";
        }
        String last = new String(nameData[name], StandardCharsets.UTF_8);
        return namePrefix[name] == 0 ? last : nameToString(namePrefix[name]) + "." + last;
    }

    /** Looks a dotted name up (components must not contain dots); 0 if absent. */
    int findName(String dotted) {
        int id = 0;
        for (String part : dotted.split("\\.")) {
            boolean numeric = !part.isEmpty() && part.chars().allMatch(Character::isDigit);
            String key = id + ":" + (numeric ? NAME_NUM : NAME_STR) + ":"
                    + new String(part.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
            Integer next = nameIndex.get(key);
            if (next == null && numeric) {
                next = nameIndex.get(id + ":" + NAME_NUM_BIG + ":" + part);
            }
            if (next == null) {
                return 0;
            }
            id = next;
        }
        return id;
    }

    // ========================================================================================= literals

    @Override
    public int addNat(long[] limbs, int from, int len) {
        if (len < 2 || limbs[from + len - 1] == 0) {
            throw new IllegalArgumentException("big literal must be normalised and at least 2^64");
        }
        nats.add(Arrays.copyOfRange(limbs, from, from + len));
        return nats.size(); // index 0 unused
    }

    @Override
    public int addString(byte[] utf8, int off, int len) {
        strings.add(Arrays.copyOfRange(utf8, off, off + len));
        return strings.size();
    }

    // ===================================================================================== shared nodes

    private int node(int kind, int a, int b, int c, int flags) {
        if ((flags & ~(F_SHARED | F_BODY)) != 0) {
            throw new IllegalArgumentException("unexpected flag bits " + Integer.toHexString(flags));
        }
        int id = nodes++;
        if (id == nKind.length) {
            int cap = id * 2;
            nKind = Arrays.copyOf(nKind, cap);
            nA = Arrays.copyOf(nA, cap);
            nB = Arrays.copyOf(nB, cap);
            nC = Arrays.copyOf(nC, cap);
            nFlags = Arrays.copyOf(nFlags, cap);
            nBig = Arrays.copyOf(nBig, cap);
            nPromoted = Arrays.copyOf(nPromoted, cap);
        }
        nKind[id] = kind;
        nA[id] = a;
        nB[id] = b;
        nC[id] = c;
        nFlags[id] = flags;
        return id;
    }

    private int term(int id) {
        if (id < 1 || id >= nodes) {
            throw new IllegalArgumentException("not a shared term id: " + id);
        }
        return id;
    }

    private int resolve(int name) {
        return resolve(name, false);
    }

    private int resolve(int name, boolean inBlock) {
        if (name < 0 || name >= names) {
            throw new IllegalArgumentException("unknown name " + name);
        }
        int c = constOf[name];
        if (c > 0) {
            return c;
        }
        if (inBlock) {
            unresolvedInBlocks++;
        } else if (!promoting) {
            unresolved++;
        }
        return ~name;
    }

    @Override
    public int bvar(int index, int flags) {
        return node(Kind.BVAR, index, 0, 0, flags);
    }

    @Override
    public int sort(int level, int flags) {
        return node(Kind.SORT, level, 0, 0, flags);
    }

    @Override
    public int constant(int name, int levelList, int flags) {
        return node(Kind.CONST, resolve(name), levelList, name, flags);
    }

    @Override
    public int app(int fn, int arg, int flags) {
        return node(Kind.APP, term(fn), term(arg), 0, flags);
    }

    @Override
    public int lam(int domain, int body, int flags) {
        return node(Kind.LAM, term(domain), term(body), 0, flags);
    }

    @Override
    public int pi(int domain, int body, int flags) {
        return node(Kind.PI, term(domain), term(body), 0, flags);
    }

    @Override
    public int let(int type, int value, int body, int flags) {
        return node(Kind.LET, term(type), term(value), term(body), flags);
    }

    @Override
    public int proj(int structName, int fieldIndex, int struct, int flags) {
        // a = structure expression, b = field index, c = cid or ~name (the name itself is kept in nameOfProj)
        int id = node(Kind.PROJ, term(struct), fieldIndex, resolve(structName), flags);
        projName.put(id, structName);
        return id;
    }

    /** PROJ shared node id to the structure name id it was written with. */
    final HashMap<Integer, Integer> projName = new HashMap<>();

    @Override
    public int natSmall(long unsignedValue, int flags) {
        return node(Kind.NATLIT, (int) unsignedValue, (int) (unsignedValue >>> 32), 0, flags);
    }

    @Override
    public int natBig(int litIndex, int flags) {
        if (litIndex < 1 || litIndex > nats.size()) {
            throw new IllegalArgumentException("no such nat literal");
        }
        int id = node(Kind.NATLIT, litIndex, 0, 0, flags);
        nBig[id] = true;
        return id;
    }

    @Override
    public int str(int litIndex, int flags) {
        if (litIndex < 1 || litIndex > strings.size()) {
            throw new IllegalArgumentException("no such string literal");
        }
        return node(Kind.STRLIT, litIndex, 0, 0, flags);
    }

    @Override
    public void singleReferrer(int child, int parent) {
        term(child);
        term(parent);
        if (child >= parent) {
            throw new IllegalArgumentException("child written after its parent");
        }
        if (singleCount * 2 == singles.length) {
            singles = Arrays.copyOf(singles, singles.length * 2);
        }
        singles[2 * singleCount] = child;
        singles[2 * singleCount + 1] = parent;
        singleCount++;
    }

    // =========================================================================================== blocks

    private void record(int kind, int a, int b, int c) {
        if (!blockOpen) {
            throw new IllegalStateException("no open block");
        }
        int p = bRecords * 4;
        if (p + 4 > bRec.length) {
            bRec = Arrays.copyOf(bRec, bRec.length * 2);
        }
        bRec[p] = kind;
        bRec[p + 1] = a;
        bRec[p + 2] = b;
        bRec[p + 3] = c;
        bRecords++;
    }

    /** Validates a reference of the record about to be written. */
    private int ref(int r) {
        if ((r & 1) != 0) {
            term(r >>> 1);
        } else {
            int back = r >> 1;
            int k = bRecords - openFirst;
            if (back < 1 || back > k) {
                throw new IllegalArgumentException("block reference " + back + " back from record " + k);
            }
        }
        return r;
    }

    @Override
    public int blockBegin() {
        if (blockOpen) {
            throw new IllegalStateException("block already open");
        }
        blockOpen = true;
        openFirst = bRecords;
        return bRecords;
    }

    @Override
    public void blockBVar(int index) {
        record(Kind.BVAR, index, 0, 0);
    }

    @Override
    public void blockSort(int level) {
        record(Kind.SORT, level, 0, 0);
    }

    @Override
    public void blockConstant(int name, int levelList) {
        record(Kind.CONST, resolve(name, true), levelList, name);
    }

    @Override
    public void blockApp(int refFn, int refArg) {
        record(Kind.APP, ref(refFn), ref(refArg), 0);
    }

    @Override
    public void blockLam(int refDomain, int refBody) {
        record(Kind.LAM, ref(refDomain), ref(refBody), 0);
    }

    @Override
    public void blockPi(int refDomain, int refBody) {
        record(Kind.PI, ref(refDomain), ref(refBody), 0);
    }

    @Override
    public void blockLet(int refType, int refValue, int refBody) {
        record(Kind.LET, ref(refType), ref(refValue), ref(refBody));
    }

    @Override
    public void blockProj(int structName, int fieldIndex, int refStruct) {
        resolve(structName, true);
        // a = struct ref, b = index, c = NAME id (the fake keeps the name; a store would keep cid or ~name)
        record(Kind.PROJ, ref(refStruct), fieldIndex, structName);
    }

    @Override
    public void blockNatSmall(long unsignedValue) {
        record(Kind.NATLIT, (int) unsignedValue, (int) (unsignedValue >>> 32), 0);
    }

    @Override
    public void blockNatBig(int litIndex) {
        record(Kind.NATLIT | BIG, litIndex, 0, 0);
    }

    @Override
    public void blockStr(int litIndex) {
        record(Kind.STRLIT, litIndex, 0, 0);
    }

    @Override
    public int blockEnd(int rootLocal) {
        if (!blockOpen) {
            throw new IllegalStateException("no open block");
        }
        int n = bRecords - openFirst;
        if (n == 0) {
            throw new IllegalStateException("empty block");
        }
        if (rootLocal < -1 || rootLocal >= n) {
            throw new IllegalArgumentException("block root " + rootLocal + " of " + n);
        }
        int b = blocks++;
        if (b == blockFirst.length) {
            blockFirst = Arrays.copyOf(blockFirst, b * 2);
            blockCount = Arrays.copyOf(blockCount, b * 2);
            blockRoot = Arrays.copyOf(blockRoot, b * 2);
        }
        blockFirst[b] = openFirst;
        blockCount[b] = n;
        blockRoot[b] = rootLocal;
        blockOpen = false;
        return b;
    }

    int blockOfRecord(int record) {
        int lo = 0;
        int hi = blocks - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (blockFirst[mid] <= record) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    @Override
    public int promote(int record) {
        if (blockOpen) {
            throw new IllegalStateException("promote while a block is open");
        }
        if (record < 0 || record >= bRecords) {
            throw new IllegalArgumentException("no block record " + record);
        }
        promoteCalls++;
        Integer done = promo.get(record);
        if (done != null) {
            return done;
        }
        promoting = true;
        int b = blockOfRecord(record);
        int first = blockFirst[b];
        int top = record - first;
        boolean[] mark = new boolean[top + 1];
        mark[top] = true;
        for (int k = top; k >= 0; k--) {
            if (!mark[k] || promo.containsKey(first + k)) {
                continue;
            }
            int p = (first + k) * 4;
            switch (bRec[p] & 0xF) {
                case Kind.APP, Kind.LAM, Kind.PI -> {
                    markRef(mark, k, bRec[p + 1]);
                    markRef(mark, k, bRec[p + 2]);
                }
                case Kind.LET -> {
                    markRef(mark, k, bRec[p + 1]);
                    markRef(mark, k, bRec[p + 2]);
                    markRef(mark, k, bRec[p + 3]);
                }
                case Kind.PROJ -> markRef(mark, k, bRec[p + 1]);
                default -> {
                }
            }
        }
        for (int k = 0; k <= top; k++) {
            if (!mark[k] || promo.containsKey(first + k)) {
                continue;
            }
            int p = (first + k) * 4;
            int w = bRec[p];
            int id;
            switch (w & 0xF) {
                case Kind.APP -> id = node(Kind.APP, pref(first, k, bRec[p + 1]), pref(first, k, bRec[p + 2]), 0, F_SHARED);
                case Kind.LAM -> id = node(Kind.LAM, pref(first, k, bRec[p + 1]), pref(first, k, bRec[p + 2]), 0, F_SHARED);
                case Kind.PI -> id = node(Kind.PI, pref(first, k, bRec[p + 1]), pref(first, k, bRec[p + 2]), 0, F_SHARED);
                case Kind.LET -> id = node(Kind.LET, pref(first, k, bRec[p + 1]), pref(first, k, bRec[p + 2]),
                        pref(first, k, bRec[p + 3]), F_SHARED);
                case Kind.PROJ -> {
                    id = node(Kind.PROJ, pref(first, k, bRec[p + 1]), bRec[p + 2], resolve(bRec[p + 3]), F_SHARED);
                    projName.put(id, bRec[p + 3]);
                }
                case Kind.CONST -> id = node(Kind.CONST, resolve(bRec[p + 3]), bRec[p + 2], bRec[p + 3], F_SHARED);
                case Kind.NATLIT -> {
                    id = node(Kind.NATLIT, bRec[p + 1], bRec[p + 2], 0, F_SHARED);
                    nBig[id] = (w & BIG) != 0;
                }
                default -> id = node(w & 0xF, bRec[p + 1], bRec[p + 2], bRec[p + 3], F_SHARED);
            }
            nPromoted[id] = true;
            promo.put(first + k, id);
        }
        promoting = false;
        return promo.get(record);
    }

    private static void markRef(boolean[] mark, int k, int r) {
        if ((r & 1) == 0) {
            mark[k - (r >> 1)] = true;
        }
    }

    private int pref(int first, int k, int r) {
        return (r & 1) != 0 ? r >>> 1 : promo.get(first + k - (r >> 1));
    }

    // ===================================================================================== declarations

    @Override
    public int beginUnit(int recordKind) {
        if (unitOpen || blockOpen) {
            throw new IllegalStateException("unit or block still open");
        }
        unitOpen = true;
        int u = units++;
        if (u == uKind.length) {
            uKind = Arrays.copyOf(uKind, u * 2);
            uFirstCid = Arrays.copyOf(uFirstCid, u * 2);
            uSize = Arrays.copyOf(uSize, u * 2);
            uFirstNode = Arrays.copyOf(uFirstNode, u * 2);
            uClaims = Arrays.copyOf(uClaims, u * 2);
        }
        uKind[u] = recordKind;
        uFirstCid[u] = consts;
        uSize[u] = 0;
        uFirstNode[u] = nodes;
        return u;
    }

    @Override
    public int reserve(int kind, int name, int lparams) {
        if (!unitOpen) {
            throw new IllegalStateException("reserve outside a unit");
        }
        if (name < 0 || name >= names) {
            throw new IllegalArgumentException("unknown name " + name);
        }
        if (constOf[name] != 0) {
            return 0;
        }
        int cid = consts++;
        if (cid == cKind.length) {
            int cap = cid * 2;
            cKind = Arrays.copyOf(cKind, cap);
            cName = Arrays.copyOf(cName, cap);
            cLparams = Arrays.copyOf(cLparams, cap);
            cType = Arrays.copyOf(cType, cap);
            cValue = Arrays.copyOf(cValue, cap);
            cHint = Arrays.copyOf(cHint, cap);
            cUnit = Arrays.copyOf(cUnit, cap);
            cComplete = Arrays.copyOf(cComplete, cap);
        }
        cKind[cid] = kind;
        cName[cid] = name;
        cLparams[cid] = lparams;
        cUnit[cid] = units - 1;
        constOf[name] = cid;
        uSize[units - 1]++;
        return cid;
    }

    @Override
    public void complete(int cid, int type, int value, int hint) {
        if (!unitOpen || blockOpen || cid < uFirstCid[units - 1] || cid >= consts || cComplete[cid]) {
            throw new IllegalStateException("complete(" + cid + ") out of protocol");
        }
        term(type);
        if (value > 0) {
            term(value);
        } else if (value < 0 && (-value - 1 >= blocks || blockRoot[-value - 1] < 0)) {
            throw new IllegalArgumentException("value block " + (-value - 1) + " has no root");
        }
        cType[cid] = type;
        cValue[cid] = value;
        cHint[cid] = hint;
        cComplete[cid] = true;
    }

    @Override
    public void unitClaims(int[] blob, int len) {
        if (!unitOpen) {
            throw new IllegalStateException("claims outside a unit");
        }
        uClaims[units - 1] = Arrays.copyOf(blob, len);
    }

    @Override
    public void endUnit() {
        if (!unitOpen || blockOpen) {
            throw new IllegalStateException("endUnit out of protocol");
        }
        for (int c = uFirstCid[units - 1]; c < consts; c++) {
            if (!cComplete[c]) {
                throw new IllegalStateException("constant " + c + " reserved but not completed");
            }
        }
        unitOpen = false;
    }

    @Override
    public boolean dropUnsafe(int name) {
        if (unitOpen) {
            throw new IllegalStateException("drop inside a unit");
        }
        if (constOf[name] != 0) {
            return false;
        }
        constOf[name] = DROPPED;
        return true;
    }

    @Override
    public boolean dropName(int name) {
        return dropUnsafe(name);
    }

    @Override
    public boolean wantsSharingFlags() {
        return true;
    }

    @Override
    public void publish() {
        published = units;
    }

    @Override
    public void finish() {
        if (unitOpen || blockOpen) {
            throw new IllegalStateException("finish with an open unit or block");
        }
        published = units;
        finished = true;
    }

    @Override
    public long sharedNodes() {
        return nodes - 1;
    }

    @Override
    public long blockBytes() {
        return 16L * bRecords;
    }

    @Override
    public long unresolvedRefs() {
        return unresolved;
    }

    @Override
    public long unresolvedBlockRefs() {
        return unresolvedInBlocks;
    }
}
