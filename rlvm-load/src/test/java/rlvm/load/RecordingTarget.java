package rlvm.load;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Test wrapper around another {@link LoadTarget}: forwards everything and remembers what the tests
 * need to compare a real store with the reference sink without reading names back from it (a
 * structural hash per name id), plus the sharing flags the loader computed per shared node.
 */
final class RecordingTarget implements LoadTarget {
    private final LoadTarget to;
    private final boolean flagsWanted;
    long[] nameHash = new long[1024];
    int maxName;
    /** Distinct name ids returned by the target (the store has names of its own: the kernel's special names). */
    int distinctNames;
    /** Per shared id: loader flags | WRITTEN; 0 for ids the loader did not write (promoted copies). */
    int[] flagOf = new int[1024];
    static final int WRITTEN = 1;
    int[] singles = new int[1024];
    int singleCount;

    RecordingTarget(LoadTarget to, boolean flagsWanted) {
        this.to = to;
        this.flagsWanted = flagsWanted;
        nameHash[0] = Oracle.H_ANONYMOUS;
    }

    private int name(int id, long hash) {
        if (id >= nameHash.length) {
            nameHash = Arrays.copyOf(nameHash, Math.max(id + 1, nameHash.length * 2));
        }
        if (nameHash[id] != 0 && nameHash[id] != hash) {
            throw new AssertionError("name id " + id + " stands for two different names");
        }
        if (nameHash[id] == 0) {
            distinctNames++;
        }
        nameHash[id] = hash;
        maxName = Math.max(maxName, id);
        return id;
    }

    private int node(int id, int flags) {
        if (id >= flagOf.length) {
            flagOf = Arrays.copyOf(flagOf, Math.max(id + 1, flagOf.length * 2));
        }
        flagOf[id] = flags | WRITTEN;
        return id;
    }

    @Override
    public int nameAnonymous() {
        int id = to.nameAnonymous();
        if (id != 0) {
            throw new AssertionError("the anonymous name is expected to be id 0");
        }
        return id;
    }

    @Override
    public int nameStr(int prefix, byte[] utf8, int off, int len) {
        return name(to.nameStr(prefix, utf8, off, len), Oracle.hNameStr(nameHash[prefix], utf8, off, len));
    }

    @Override
    public int nameNum(int prefix, long unsignedValue) {
        return name(to.nameNum(prefix, unsignedValue), Oracle.hNameNum(nameHash[prefix], Long.toUnsignedString(unsignedValue)));
    }

    @Override
    public int nameNumBig(int prefix, byte[] digits, int off, int len) {
        return name(to.nameNumBig(prefix, digits, off, len),
                Oracle.hNameNum(nameHash[prefix], new String(digits, off, len, StandardCharsets.US_ASCII)));
    }

    @Override
    public int addNat(long[] limbs, int from, int len) {
        return to.addNat(limbs, from, len);
    }

    @Override
    public int addString(byte[] utf8, int off, int len) {
        return to.addString(utf8, off, len);
    }

    @Override
    public int bvar(int index, int flags) {
        return node(to.bvar(index, flags), flags);
    }

    @Override
    public int sort(int level, int flags) {
        return node(to.sort(level, flags), flags);
    }

    @Override
    public int constant(int name, int levelList, int flags) {
        return node(to.constant(name, levelList, flags), flags);
    }

    @Override
    public int app(int fn, int arg, int flags) {
        return node(to.app(fn, arg, flags), flags);
    }

    @Override
    public int lam(int domain, int body, int flags) {
        return node(to.lam(domain, body, flags), flags);
    }

    @Override
    public int pi(int domain, int body, int flags) {
        return node(to.pi(domain, body, flags), flags);
    }

    @Override
    public int let(int type, int value, int body, int flags) {
        return node(to.let(type, value, body, flags), flags);
    }

    @Override
    public int proj(int structName, int fieldIndex, int struct, int flags) {
        return node(to.proj(structName, fieldIndex, struct, flags), flags);
    }

    @Override
    public int natSmall(long unsignedValue, int flags) {
        return node(to.natSmall(unsignedValue, flags), flags);
    }

    @Override
    public int natBig(int litIndex, int flags) {
        return node(to.natBig(litIndex, flags), flags);
    }

    @Override
    public int str(int litIndex, int flags) {
        return node(to.str(litIndex, flags), flags);
    }

    @Override
    public void singleReferrer(int child, int parent) {
        if (singleCount * 2 == singles.length) {
            singles = Arrays.copyOf(singles, singles.length * 2);
        }
        singles[2 * singleCount] = child;
        singles[2 * singleCount + 1] = parent;
        singleCount++;
        to.singleReferrer(child, parent);
    }

    @Override
    public boolean wantsSharingFlags() {
        return flagsWanted;
    }

    @Override
    public int blockBegin() {
        return to.blockBegin();
    }

    @Override
    public void blockBVar(int index) {
        to.blockBVar(index);
    }

    @Override
    public void blockSort(int level) {
        to.blockSort(level);
    }

    @Override
    public void blockConstant(int name, int levelList) {
        to.blockConstant(name, levelList);
    }

    @Override
    public void blockApp(int refFn, int refArg) {
        to.blockApp(refFn, refArg);
    }

    @Override
    public void blockLam(int refDomain, int refBody) {
        to.blockLam(refDomain, refBody);
    }

    @Override
    public void blockPi(int refDomain, int refBody) {
        to.blockPi(refDomain, refBody);
    }

    @Override
    public void blockLet(int refType, int refValue, int refBody) {
        to.blockLet(refType, refValue, refBody);
    }

    @Override
    public void blockProj(int structName, int fieldIndex, int refStruct) {
        to.blockProj(structName, fieldIndex, refStruct);
    }

    @Override
    public void blockNatSmall(long unsignedValue) {
        to.blockNatSmall(unsignedValue);
    }

    @Override
    public void blockNatBig(int litIndex) {
        to.blockNatBig(litIndex);
    }

    @Override
    public void blockStr(int litIndex) {
        to.blockStr(litIndex);
    }

    @Override
    public int blockEnd(int rootLocal) {
        return to.blockEnd(rootLocal);
    }

    @Override
    public int promote(int record) {
        return to.promote(record);
    }

    @Override
    public int beginUnit(int recordKind) {
        return to.beginUnit(recordKind);
    }

    @Override
    public int reserve(int kind, int name, int lparams) {
        return to.reserve(kind, name, lparams);
    }

    @Override
    public void complete(int cid, int type, int value, int hint) {
        to.complete(cid, type, value, hint);
    }

    @Override
    public void unitClaims(int[] blob, int len) {
        to.unitClaims(blob, len);
    }

    @Override
    public void endUnit() {
        to.endUnit();
    }

    @Override
    public boolean dropUnsafe(int name) {
        return to.dropUnsafe(name);
    }

    @Override
    public boolean dropName(int name) {
        return to.dropName(name);
    }

    @Override
    public void publish() {
        to.publish();
    }

    @Override
    public int unitCount() {
        return to.unitCount();
    }

    @Override
    public void finish() {
        to.finish();
    }

    @Override
    public long sharedNodes() {
        return to.sharedNodes();
    }

    @Override
    public long blockBytes() {
        return to.blockBytes();
    }

    @Override
    public long unresolvedRefs() {
        return to.unresolvedRefs();
    }

    @Override
    public long unresolvedBlockRefs() {
        return to.unresolvedBlockRefs();
    }

    @Override
    public String nameToString(int name) {
        return to.nameToString(name);
    }
}
