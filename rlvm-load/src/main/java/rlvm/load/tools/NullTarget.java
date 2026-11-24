package rlvm.load.tools;

import rlvm.load.LoadTarget;

/**
 * A {@link LoadTarget} that stores nothing and only numbers what it is given. For measuring the cost
 * of reader + loader without the store. Not a valid target for checking: names are not interned, so
 * duplicate declarations are not detected.
 */
final class NullTarget implements LoadTarget {
    private int names;
    private int lits;
    private int nodes;
    private int blockRecords;
    private int blocks;
    private int consts;
    private int units;
    private long singles;

    @Override
    public int nameAnonymous() {
        return 0;
    }

    @Override
    public int nameStr(int prefix, byte[] utf8, int off, int len) {
        return ++names;
    }

    @Override
    public int nameNum(int prefix, long unsignedValue) {
        return ++names;
    }

    @Override
    public int nameNumBig(int prefix, byte[] digits, int off, int len) {
        return ++names;
    }

    @Override
    public int addNat(long[] limbs, int from, int len) {
        return ++lits;
    }

    @Override
    public int addString(byte[] utf8, int off, int len) {
        return ++lits;
    }

    @Override
    public int bvar(int index, int flags) {
        return ++nodes;
    }

    @Override
    public int sort(int level, int flags) {
        return ++nodes;
    }

    @Override
    public int constant(int name, int levelList, int flags) {
        return ++nodes;
    }

    @Override
    public int app(int fn, int arg, int flags) {
        return ++nodes;
    }

    @Override
    public int lam(int domain, int body, int flags) {
        return ++nodes;
    }

    @Override
    public int pi(int domain, int body, int flags) {
        return ++nodes;
    }

    @Override
    public int let(int type, int value, int body, int flags) {
        return ++nodes;
    }

    @Override
    public int proj(int structName, int fieldIndex, int struct, int flags) {
        return ++nodes;
    }

    @Override
    public int natSmall(long unsignedValue, int flags) {
        return ++nodes;
    }

    @Override
    public int natBig(int litIndex, int flags) {
        return ++nodes;
    }

    @Override
    public int str(int litIndex, int flags) {
        return ++nodes;
    }

    @Override
    public void singleReferrer(int child, int parent) {
        singles++;
    }

    @Override
    public int blockBegin() {
        return blockRecords;
    }

    @Override
    public void blockBVar(int index) {
        blockRecords++;
    }

    @Override
    public void blockSort(int level) {
        blockRecords++;
    }

    @Override
    public void blockConstant(int name, int levelList) {
        blockRecords++;
    }

    @Override
    public void blockApp(int refFn, int refArg) {
        blockRecords++;
    }

    @Override
    public void blockLam(int refDomain, int refBody) {
        blockRecords++;
    }

    @Override
    public void blockPi(int refDomain, int refBody) {
        blockRecords++;
    }

    @Override
    public void blockLet(int refType, int refValue, int refBody) {
        blockRecords++;
    }

    @Override
    public void blockProj(int structName, int fieldIndex, int refStruct) {
        blockRecords++;
    }

    @Override
    public void blockNatSmall(long unsignedValue) {
        blockRecords++;
    }

    @Override
    public void blockNatBig(int litIndex) {
        blockRecords++;
    }

    @Override
    public void blockStr(int litIndex) {
        blockRecords++;
    }

    @Override
    public int blockEnd(int rootLocal) {
        return blocks++;
    }

    @Override
    public int promote(int record) {
        return ++nodes;
    }

    @Override
    public int beginUnit(int recordKind) {
        return units++;
    }

    @Override
    public int reserve(int kind, int name, int lparams) {
        return ++consts;
    }

    @Override
    public void complete(int cid, int type, int value, int hint) {}

    @Override
    public void unitClaims(int[] blob, int len) {}

    @Override
    public void endUnit() {}

    @Override
    public boolean dropUnsafe(int name) {
        return true;
    }

    @Override
    public boolean dropName(int name) {
        return true;
    }

    @Override
    public boolean wantsSharingFlags() {
        return true;
    }

    @Override
    public void publish() {}

    @Override
    public void finish() {}

    @Override
    public long sharedNodes() {
        return nodes;
    }

    @Override
    public long blockBytes() {
        return 0;
    }

    @Override
    public long unresolvedRefs() {
        return 0;
    }

    @Override
    public long unresolvedBlockRefs() {
        return 0;
    }

    @Override
    public String nameToString(int name) {
        return "#" + name;
    }

    long singles() {
        return singles;
    }
}
