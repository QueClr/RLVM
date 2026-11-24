package rlvm.load;

import rlvm.store.DeclTable;
import rlvm.store.LitPool;
import rlvm.store.NameTable;
import rlvm.store.Node;
import rlvm.store.ProofBlocks;
import rlvm.store.Store;
import rlvm.store.StoreBuilder;
import rlvm.store.TermStore;

/**
 * {@link LoadTarget} over {@code rlvm.store.Store}: names and literals go to the store's tables, terms,
 * blocks and rows through its {@link StoreBuilder}.
 *
 * <p>Sharing flags: the store derives SHARED / BODY / INVARIANT itself while the nodes of a unit are
 * appended ({@code TermStore}), from the references it sees, so this target does not want the
 * loader's ({@link #wantsSharingFlags()} is false) and only names the roots that the builder cannot
 * see: the right-hand sides of recursor rules.
 */
final class StoreTarget implements LoadTarget {
    private final StoreBuilder b;
    private final TermStore terms;
    private final NameTable names;
    private final LitPool lits;
    private final ProofBlocks blocks;
    private final DeclTable decls;
    private ProofBlocks.Writer writer;
    private long blockBytes;
    private long unresolved;
    private long unresolvedInBlocks;

    StoreTarget(Store store) {
        this.b = store.builder();
        this.terms = store.terms();
        this.names = store.names();
        this.lits = store.lits();
        this.blocks = store.blocks();
        this.decls = store.decls();
    }

    // ---- names, literals -------------------------------------------------------------------------

    @Override
    public int nameAnonymous() {
        return NameTable.ANONYMOUS;
    }

    @Override
    public int nameStr(int prefix, byte[] utf8, int off, int len) {
        return names.internStr(prefix, utf8, off, len);
    }

    @Override
    public int nameNum(int prefix, long unsignedValue) {
        return names.internNum(prefix, unsignedValue);
    }

    @Override
    public int nameNumBig(int prefix, byte[] digits, int off, int len) {
        return names.internNumBig(prefix, digits, off, len);
    }

    @Override
    public int addNat(long[] limbs, int from, int len) {
        return lits.addNat(limbs, from, len);
    }

    @Override
    public int addString(byte[] utf8, int off, int len) {
        return lits.addString(utf8, off, len);
    }

    // ---- shared nodes ----------------------------------------------------------------------------

    @Override
    public int bvar(int index, int flags) {
        return b.bvar(index);
    }

    @Override
    public int sort(int level, int flags) {
        return b.sort(level);
    }

    @Override
    public int constant(int name, int levelList, int flags) {
        int id = b.constant(name, levelList);
        if ((b.header(id) & Node.F_UNRESOLVED) != 0) {
            unresolved++;
        }
        return id;
    }

    @Override
    public int app(int fn, int arg, int flags) {
        return b.app(fn, arg);
    }

    @Override
    public int lam(int domain, int body, int flags) {
        return b.lam(domain, body);
    }

    @Override
    public int pi(int domain, int body, int flags) {
        return b.pi(domain, body);
    }

    @Override
    public int let(int type, int value, int body, int flags) {
        return b.let(type, value, body);
    }

    @Override
    public int proj(int structName, int fieldIndex, int struct, int flags) {
        int id = b.proj(structName, fieldIndex, struct);
        if (names.constOf(structName) <= 0) {
            unresolved++;
        }
        return id;
    }

    @Override
    public int natSmall(long unsignedValue, int flags) {
        return b.natSmall(unsignedValue);
    }

    @Override
    public int natBig(int litIndex, int flags) {
        return b.natBig(litIndex);
    }

    @Override
    public int str(int litIndex, int flags) {
        return b.str(litIndex);
    }

    @Override
    public void touch(int sharedId) {
        b.touch(sharedId);
    }

    @Override
    public void singleReferrer(int child, int parent) {
        // the store compares the masks itself at the first reference
    }

    @Override
    public boolean wantsSharingFlags() {
        return false;
    }

    // ---- blocks ----------------------------------------------------------------------------------

    @Override
    public int blockBegin() {
        if (writer != null) {
            throw new IllegalStateException("a proof block is already open");
        }
        writer = b.blockWriter();
        return blocks.recordCount();
    }

    @Override
    public void blockBVar(int index) {
        writer.bvar(index);
    }

    @Override
    public void blockSort(int level) {
        writer.sort(level);
    }

    @Override
    public void blockConstant(int name, int levelList) {
        int bound = b.bindName(name);
        if (bound < 0) {
            unresolvedInBlocks++;
        }
        writer.constant(bound, levelList, name);
    }

    @Override
    public void blockApp(int refFn, int refArg) {
        writer.app(refFn, refArg);
    }

    @Override
    public void blockLam(int refDomain, int refBody) {
        writer.lam(refDomain, refBody);
    }

    @Override
    public void blockPi(int refDomain, int refBody) {
        writer.pi(refDomain, refBody);
    }

    @Override
    public void blockLet(int refType, int refValue, int refBody) {
        writer.let(refType, refValue, refBody);
    }

    @Override
    public void blockProj(int structName, int fieldIndex, int refStruct) {
        int bound = b.bindName(structName);
        if (bound < 0) {
            unresolvedInBlocks++;
        }
        writer.proj(bound, fieldIndex, refStruct);
    }

    @Override
    public void blockNatSmall(long unsignedValue) {
        writer.natSmall(unsignedValue);
    }

    @Override
    public void blockNatBig(int litIndex) {
        writer.natBig(litIndex);
    }

    @Override
    public void blockStr(int litIndex) {
        writer.str(litIndex);
    }

    @Override
    public void blockRun(int blockIndex, byte[] packed, int off, int len, int count, int[] ext, int extOff,
            int extCount) {
        if (writer.size() != blockIndex) {
            throw new IllegalStateException("the block has " + writer.size() + " records, the run starts at " + blockIndex);
        }
        writer.run(packed, off, len, count, ext, extOff, extCount);
    }

    @Override
    public int blockEnd(int rootLocal) {
        int block = writer.finish(rootLocal);
        writer = null;
        blockBytes += blocks.byteLength(block);
        return block;
    }

    @Override
    public int promote(int record) {
        return b.promote(record);
    }

    // ---- declarations ----------------------------------------------------------------------------
    // StoreBuilder has this protocol verbatim; NameTable.claim fails on any earlier claim of the name,
    // by a constant or by a dropped record, which is the official reader's duplicate rule.

    @Override
    public int beginUnit(int recordKind) {
        return b.beginUnit(recordKind);
    }

    @Override
    public int reserve(int kind, int name, int lparams) {
        return b.reserve(kind, name, lparams);
    }

    @Override
    public void complete(int cid, int type, int value, int hint) {
        b.complete(cid, type, value, hint);
    }

    @Override
    public void unitClaims(int[] blob, int len) {
        // roots the builder does not see: the right-hand sides of the recursor rules
        int recs = BlockClaims.numRecs(blob);
        int q = BlockClaims.firstRec(blob);
        for (int r = 0; r < recs; r++) {
            int rules = blob[q + BlockClaims.R_NUM_RULES];
            for (int i = 0; i < rules; i++) {
                b.markRoot(blob[BlockClaims.ruleAt(q, i) + BlockClaims.RULE_RHS]);
            }
            q = BlockClaims.nextRec(blob, q);
        }
        b.unitClaims(blob, len);
    }

    @Override
    public void endUnit() {
        b.endUnit();
    }

    @Override
    public boolean dropUnsafe(int name) {
        return b.dropUnsafe(name);
    }

    @Override
    public boolean dropName(int name) {
        return b.dropName(name);
    }

    @Override
    public void publish() {
        b.publish();
    }

    @Override
    public int unitCount() {
        return decls.unitCount();
    }

    @Override
    public void finish() {
        b.finish();
    }

    // ---- statistics ------------------------------------------------------------------------------

    @Override
    public long sharedNodes() {
        return terms.exportEnd() - 1;
    }

    @Override
    public long blockBytes() {
        return blockBytes;
    }

    @Override
    public long unresolvedRefs() {
        return unresolved;
    }

    @Override
    public long unresolvedBlockRefs() {
        return unresolvedInBlocks;
    }

    @Override
    public String nameToString(int name) {
        return names.toString(name);
    }
}
