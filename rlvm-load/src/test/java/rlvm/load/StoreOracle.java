package rlvm.load;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;

import rlvm.level.LevelStore;
import rlvm.level.NameOracle;
import rlvm.store.DeclTable;
import rlvm.store.Kind;
import rlvm.store.LitPool;
import rlvm.store.Node;
import rlvm.store.Overlay;
import rlvm.store.ProofBlocks;
import rlvm.store.Store;
import rlvm.store.Terms;

/**
 * The oracle of {@link Oracle} over the REAL store: the export goes through {@code new Loader(Store,
 * ...)}'s production binding ({@link StoreTarget}); the loaded side is read back with the store's own
 * reader API ({@link Terms} for the export region, {@link Overlay#blockRoot} to decode every proof
 * block, {@link DeclTable} rows and claims).
 */
final class StoreOracle extends Oracle {
    Store store;
    RecordingTarget rec;
    private final boolean loaderFlags;

    /** @param loaderFlags let the loader compute its sharing flags too, for {@link #verifyStoreFlags()} */
    StoreOracle(byte[] data, boolean loaderFlags) {
        super(data);
        this.loaderFlags = loaderFlags;
    }

    StoreOracle(Path file, boolean loaderFlags) throws IOException {
        super(file);
        this.loaderFlags = loaderFlags;
    }

    /** The level store exists before the name table; the name order is bound right after. */
    private static final class Order implements NameOracle {
        NameOracle target;

        @Override
        public int compareNames(int a, int b) {
            return target.compareNames(a, b);
        }

        @Override
        public void appendName(StringBuilder sb, int nameId) {
            target.appendName(sb, nameId);
        }
    }

    /** Pipelined loads: the chain of level snapshots; the oracle reads the last one. */
    LevelChain chain;

    @Override
    Loader newLoader(LoadOptions options) {
        Order order = new Order();
        ls = new LevelStore(order);
        boolean pipelined = options.mode == LoadOptions.Mode.PIPELINED;
        chain = pipelined ? new LevelChain(ls) : null;
        try {
            store = pipelined ? new Store(size(), chain.lists(), chain.info())
                    : new Store(size(), LevelStoreViews.lists(ls), LevelStoreViews.info(ls));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        order.target = new NameTableOracle(store.names());
        rec = new RecordingTarget(new StoreTarget(store), loaderFlags);
        view = new StoreView();
        return new Loader(rec, options, pipelined ? chain : new LevelStoreInterner(ls), new NatStoreDecoder());
    }

    @Override
    Oracle load(LoadOptions options) throws IOException {
        try {
            return super.load(options);
        } finally {
            if (chain != null && chain.current() != null) {
                ls = chain.current().levels; // the frozen store that holds every level of the load
            }
        }
    }

    private final class StoreView extends View {
        private final DeclTable decls = store.decls();
        private final ProofBlocks blocks = store.blocks();

        @Override
        boolean finished() {
            return store.loadComplete() && store.loadedUnits() == decls.unitCount();
        }

        @Override
        int sharedNodes() {
            return store.terms().exportEnd() - 1;
        }

        @Override
        int blockRecords() {
            return blocks.recordCount();
        }

        @Override
        int blocks() {
            return blocks.count();
        }

        @Override
        int consts() {
            return decls.constantCount();
        }

        @Override
        int units() {
            return decls.unitCount();
        }

        @Override
        boolean droppedRecordsHaveUnits() {
            return true;
        }

        @Override
        int distinctNames() {
            return rec.distinctNames;
        }

        @Override
        int constOf(int name) {
            return store.names().constOf(name);
        }

        @Override
        int cKind(int cid) {
            return decls.kind(cid);
        }

        @Override
        int cName(int cid) {
            return decls.name(cid);
        }

        @Override
        int cLparams(int cid) {
            return decls.levelParams(cid);
        }

        @Override
        int cType(int cid) {
            return decls.type(cid);
        }

        @Override
        int cValue(int cid) {
            return decls.value(cid);
        }

        @Override
        int cHint(int cid) {
            return decls.hint(cid);
        }

        @Override
        int cUnit(int cid) {
            return decls.unit(cid);
        }

        @Override
        int uFirstCid(int unit) {
            return decls.unitFirstCid(unit);
        }

        @Override
        int uSize(int unit) {
            return decls.unitSize(unit);
        }

        @Override
        int uKind(int unit) {
            return decls.unitKind(unit);
        }

        @Override
        int[] uClaims(int unit) {
            return decls.claims(unit);
        }

        @Override
        int uRepeatOf(int unit) {
            return decls.repeatOf(unit);
        }

        @Override
        int blockFirst(int block) {
            return blocks.firstRecord(block);
        }

        @Override
        int blockRoot(int block) {
            return blocks.rootRecord(block);
        }

        @Override
        void hash(Oracle o) {
            hashStore();
        }
    }

    /** Hash of node {@code id}; children are looked up in {@code fs} (shared) or {@code local} (overlay, from {@code base}). */
    private long nodeHash(Terms t, int id, long[] local, int base) {
        LitPool lits = store.lits();
        int a = t.a(id);
        int b = t.b(id);
        int c = t.c(id);
        switch (t.kind(id)) {
            case Kind.BVAR:
                return hBVar(a);
            case Kind.SORT:
                return hSort(fl[a]);
            case Kind.CONST:
                return listHash(mix(H_CONST, fn[c]), b, fl);
            case Kind.APP:
                return hApp(childHash(a, local, base), childHash(b, local, base));
            case Kind.LAM:
                return hLam(childHash(a, local, base), childHash(b, local, base));
            case Kind.PI:
                return hPi(childHash(a, local, base), childHash(b, local, base));
            case Kind.LET:
                return hLet(childHash(a, local, base), childHash(b, local, base), childHash(c, local, base));
            case Kind.PROJ:
                return hProj(fn[c > 0 ? store.decls().name(c) : ~c], b, childHash(a, local, base));
            case Kind.NATLIT:
                if (t.isNatBig(id)) {
                    long[] limbs = new long[lits.natLimbCount(a)];
                    lits.natLimbs(a, limbs, 0);
                    return natHashBig(limbs);
                }
                return natHashSmall(t.natSmall(id));
            case Kind.STRLIT: {
                byte[] bytes = new byte[lits.stringByteLength(a)];
                lits.stringBytes(a, bytes, 0);
                assertEquals(new String(bytes, java.nio.charset.StandardCharsets.UTF_8).codePointCount(0,
                        new String(bytes, java.nio.charset.StandardCharsets.UTF_8).length()), b, "code points of a string literal");
                return strHash(bytes);
            }
            default:
                throw new AssertionError("node kind " + t.kind(id) + " of term " + id);
        }
    }

    private long childHash(int id, long[] local, int base) {
        if (id >= Node.OVERLAY_BIT) {
            return local[(id & Node.INDEX_MASK) - base];
        }
        return fs[id];
    }

    private void hashStore() {
        fn = Arrays.copyOf(rec.nameHash, rec.maxName + 1);
        hashLevels();
        // workers read through an overlay; the base level store is frozen, so lpSet unions need a level overlay
        LevelStore worker = ls.newOverlay();
        Overlay overlay = new Overlay(store, LevelStoreViews.lists(worker), LevelStoreViews.info(worker), store.lits(), 1L << 34);
        Terms t = new Terms(store.terms(), overlay);
        int end = store.terms().exportEnd();
        fs = new long[end];
        for (int id = 1; id < end; id++) {
            fs[id] = nodeHash(t, id, null, 0);
        }
        ProofBlocks blocks = store.blocks();
        fb = new long[blocks.recordCount()];
        long[] local = new long[1024];
        for (int blk = 0; blk < blocks.count(); blk++) {
            int n = blocks.nodeCount(blk);
            if (local.length < n) {
                local = new long[Math.max(n, local.length * 2)];
            }
            int base = overlay.nodeCount() + 1;
            int root = overlay.blockRoot(blk);
            t.refresh();
            assertEquals(base + n - 1, overlay.nodeCount(), "one overlay node per block record");
            int rootLocal = blocks.rootRecord(blk);
            if (rootLocal >= 0) {
                assertEquals((base + rootLocal) | Node.OVERLAY_BIT, root, "root of block " + blk);
            } else {
                assertEquals(0, root, "an ownerless block has no root");
            }
            int first = blocks.firstRecord(blk);
            for (int k = 0; k < n; k++) {
                local[k] = nodeHash(t, (base + k) | Node.OVERLAY_BIT, local, base);
                fb[first + k] = local[k];
            }
            overlay.reset();
            t.refresh();
        }
    }

    /**
     * Two implementations of RECONCILIATION section 3 against each other: the store derives the sharing
     * flags from the references it sees while nodes are appended; the loader counts referrers per
     * segment record. For every shared node the loader wrote: BODY agrees exactly; the store's SHARED
     * implies the loader's (the loader also counts a declaration root as a referrer, and such a node
     * is BODY on both sides); and "tabled" (any of the three flags) agrees. Promoted copies are SHARED.
     */
    void verifyStoreFlags() {
        assertTrue(loaderFlags, "the oracle was created without loader flags");
        Terms t = new Terms(store.terms(), null);
        int end = store.terms().exportEnd();
        boolean[] invariant = new boolean[end];
        for (int i = 0; i < rec.singleCount; i++) {
            int child = rec.singles[2 * i];
            int parent = rec.singles[2 * i + 1];
            invariant[child] = t.fvMask(child) != t.fvMask(parent);
        }
        long written = 0;
        long tabled = 0;
        for (int id = 1; id < end; id++) {
            int h = t.header(id);
            int f = id < rec.flagOf.length ? rec.flagOf[id] : 0;
            if ((f & RecordingTarget.WRITTEN) == 0) {
                if ((h & Node.F_SHARED) == 0) {
                    fail("promoted node " + id + " is not SHARED");
                }
                continue;
            }
            written++;
            boolean loaderShared = (f & LoadTarget.F_SHARED) != 0;
            boolean loaderBody = (f & LoadTarget.F_BODY) != 0;
            boolean storeShared = (h & Node.F_SHARED) != 0;
            boolean storeBody = (h & Node.F_BODY) != 0;
            if (loaderBody != storeBody) {
                fail("BODY of shared node " + id + ": loader " + loaderBody + ", store " + storeBody);
            }
            if (storeShared && !loaderShared) {
                fail("shared node " + id + " is SHARED for the store but not for the loader");
            }
            if (loaderShared && !storeShared && !storeBody) {
                fail("shared node " + id + " is SHARED for the loader only, and not a root or body");
            }
            boolean loaderTabled = loaderShared || loaderBody || invariant[id];
            boolean storeTabled = (h & Node.F_TABLED_MASK) != 0;
            if (loaderTabled != storeTabled) {
                fail("tabling of shared node " + id + ": loader " + loaderTabled + ", store " + storeTabled + " (header "
                        + Integer.toHexString(h) + ", loader flags " + Integer.toHexString(f) + ")");
            }
            if (storeTabled) {
                tabled++;
            }
        }
        assertEquals(report.sharedNodes, written);
        assertTrue(written == 0 || tabled > 0 || written < 3, "some node is tabled");
    }
}
