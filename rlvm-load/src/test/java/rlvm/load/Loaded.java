package rlvm.load;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import rlvm.level.IntListStore;
import rlvm.level.LevelStore;
import rlvm.level.NameOracle;
import rlvm.store.DeclTable;
import rlvm.store.Kind;
import rlvm.store.LitPool;
import rlvm.store.NameTable;
import rlvm.store.Node;
import rlvm.store.Overlay;
import rlvm.store.ProofBlocks;
import rlvm.store.Store;

/**
 * Test fixture: one export loaded into a real store through the production binding, in batch mode
 * (plain level store, frozen at the end) or in pipelined mode ({@link LevelChain}); and the
 * record-by-record comparison of two such stores.
 */
final class Loaded {
    final Store store;
    /** Pipelined: the chain; batch: null. */
    final LevelChain chain;
    private final LevelStore batchLevels;
    final Loader loader;
    LoadReport report;
    /** The exception that ended the load, or null. */
    RuntimeException failure;

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

    private Loaded(long bytes, LoadOptions options) {
        this(bytes, options, options.mode == LoadOptions.Mode.PIPELINED);
    }

    /** @param onChain put the store on a {@link LevelChain} (always for a pipelined load; possible for a batch load) */
    private Loaded(long bytes, LoadOptions options, boolean onChain) {
        Order order = new Order();
        LevelStore root = new LevelStore(order);
        if (onChain) {
            chain = new LevelChain(root);
            batchLevels = null;
            store = new Store(bytes, chain.lists(), chain.info());
            order.target = new NameTableOracle(store.names());
            loader = new Loader(store, options, chain, new NatStoreDecoder());
        } else {
            chain = null;
            batchLevels = root;
            store = new Store(bytes, LevelStoreViews.lists(root), LevelStoreViews.info(root));
            order.target = new NameTableOracle(store.names());
            loader = new Loader(store, options, new LevelStoreInterner(root), new NatStoreDecoder());
        }
    }

    /** A loader over a fresh store, not yet fed (for tests that run the load on a thread of their own). */
    static Loaded prepare(long bytes, LoadOptions options) {
        return new Loaded(bytes, options);
    }

    /** Runs the load on the calling thread; a failure is kept in {@link #failure}. */
    Loaded run(byte[] data) {
        try {
            report = ExportLoader.read(data, loader);
        } catch (MalformedExportException | UnsupportedExportException e) {
            failure = e;
            report = loader.report();
        }
        return this;
    }

    Loaded run(Path file) throws IOException {
        try {
            report = ExportLoader.read(file, loader);
        } catch (MalformedExportException | UnsupportedExportException e) {
            failure = e;
            report = loader.report();
        }
        return this;
    }

    static Loaded load(byte[] data, LoadOptions options) {
        return new Loaded(data.length, options).run(data);
    }

    /** A load whose level store is a {@link LevelChain} whatever the mode. */
    static Loaded loadOnChain(byte[] data, LoadOptions options) {
        return new Loaded(data.length, options, true).run(data);
    }

    static Loaded loadOnChain(Path file, LoadOptions options) throws IOException {
        return new Loaded(Files.size(file), options, true).run(file);
    }

    static Loaded load(Path file, LoadOptions options) throws IOException {
        return new Loaded(Files.size(file), options).run(file);
    }

    /**
     * The level store a reader may use for the units published so far: the newest snapshot of the
     * chain, or the batch store (frozen by a complete load; unfrozen and usable only by the loading
     * thread after a failed one).
     */
    LevelStore base() {
        if (chain != null) {
            LevelChain.Snapshot s = chain.current();
            return s == null ? null : s.levels;
        }
        return batchLevels;
    }

    /** A private level store for a reader on the calling thread. */
    LevelStore readerLevels() {
        LevelStore b = base();
        assertNotNull(b, "nothing was published");
        return b.isFrozen() ? b.newOverlay() : b;
    }

    /** Digests of units {@code [0, n)}, computed on the calling thread. */
    long[] digests(int n) {
        StoreDigest d = new StoreDigest(store);
        d.bind(readerLevels());
        long[] out = new long[n];
        for (int u = 0; u < n; u++) {
            out[u] = d.unit(u);
        }
        return out;
    }

    // ---- record-by-record comparison of two stores ---------------------------------------------------

    /**
     * Compares the two stores word by word. Everything must be identical except level ids (the chain
     * numbers the normal forms of each snapshot between the export's levels) and, as a consequence,
     * the ids of lists (a list of level ids and a list of name ids with the same numbers share an id):
     * levels are compared by structure, with a bijection between the two numberings, lists by content.
     */
    static void assertSameStore(Loaded a, Loaded b, String what) {
        new Comparison(a, b, what, false).run();
    }

    /** As {@link #assertSameStore}, and level ids and list ids must be the very same numbers. */
    static void assertIdenticalStore(Loaded a, Loaded b, String what) {
        new Comparison(a, b, what, true).run();
    }

    private static final class Comparison {
        private final Loaded x;
        private final Loaded y;
        private final String what;
        private final LevelStore lx;
        private final LevelStore ly;
        private final IntListStore listsX;
        private final IntListStore listsY;
        private int[] levelMap = new int[64];
        private int[] levelBack = new int[64];
        private final boolean exact;

        Comparison(Loaded x, Loaded y, String what, boolean exact) {
            this.x = x;
            this.y = y;
            this.what = what;
            this.exact = exact;
            this.lx = x.readerLevels();
            this.ly = y.readerLevels();
            this.listsX = lx.lists();
            this.listsY = ly.lists();
        }

        private void eq(long expected, long actual, String field, int index) {
            if (expected != actual) {
                fail(what + ": " + field + " " + index + ": batch " + expected + ", pipelined " + actual);
            }
        }

        /** Same level, structurally; the correspondence of ids must be one to one. */
        private void level(int p, int q, String field, int index) {
            if (exact) {
                eq(p, q, field + " (level id)", index);
            }
            if (p == 0 || q == 0) {
                eq(p, q, field + " (level)", index);
                return;
            }
            if (p >= levelMap.length) {
                levelMap = Arrays.copyOf(levelMap, Math.max(p + 1, levelMap.length * 2));
            }
            if (q >= levelBack.length) {
                levelBack = Arrays.copyOf(levelBack, Math.max(q + 1, levelBack.length * 2));
            }
            if (levelMap[p] != 0 || levelBack[q] != 0) {
                if (levelMap[p] != q || levelBack[q] != p) {
                    fail(what + ": " + field + " " + index + ": level " + lx.toString(p) + " (batch id " + p
                            + ") against " + ly.toString(q) + " (pipelined id " + q + ")");
                }
                return;
            }
            int k = lx.kind(p);
            eq(k, ly.kind(q), field + " (level kind)", index);
            switch (k) {
                case LevelStore.KIND_SUCC -> level(lx.succOf(p), ly.succOf(q), field, index);
                case LevelStore.KIND_MAX, LevelStore.KIND_IMAX -> {
                    level(lx.lhs(p), ly.lhs(q), field, index);
                    level(lx.rhs(p), ly.rhs(q), field, index);
                }
                case LevelStore.KIND_PARAM -> eq(lx.paramName(p), ly.paramName(q), field + " (level parameter)", index);
                default -> {
                }
            }
            if (p >= levelMap.length) {
                levelMap = Arrays.copyOf(levelMap, Math.max(p + 1, levelMap.length * 2));
            }
            if (q >= levelBack.length) {
                levelBack = Arrays.copyOf(levelBack, Math.max(q + 1, levelBack.length * 2));
            }
            levelMap[p] = q;
            levelBack[q] = p;
        }

        private void levelList(int p, int q, String field, int index) {
            if (exact) {
                eq(p, q, field + " (list id)", index);
            }
            int n = listsX.length(p);
            eq(n, listsY.length(q), field + " (list length)", index);
            for (int i = 0; i < n; i++) {
                level(listsX.get(p, i), listsY.get(q, i), field, index);
            }
        }

        private void intList(int p, int q, String field, int index) {
            if (exact) {
                eq(p, q, field + " (list id)", index);
            }
            int n = listsX.length(p);
            eq(n, listsY.length(q), field + " (list length)", index);
            for (int i = 0; i < n; i++) {
                eq(listsX.get(p, i), listsY.get(q, i), field + " (list element " + i + ")", index);
            }
        }

        /** One node, given both arrays and the offsets of its first word. */
        private void node(int[] p, int wp, int[] q, int wq, String field, int index) {
            int header = p[wp];
            eq(header, q[wq], field + " header", index);
            int kind = Node.kind(header);
            if (kind == Kind.SORT) {
                level(p[wp + 1], q[wq + 1], field + " sort", index);
            } else {
                eq(p[wp + 1], q[wq + 1], field + " a", index);
            }
            if (kind == Kind.CONST) {
                levelList(p[wp + 2], q[wq + 2], field + " const levels", index);
            } else {
                eq(p[wp + 2], q[wq + 2], field + " b", index);
            }
            eq(p[wp + 3], q[wq + 3], field + " c", index);
            eq(p[wp + 4], q[wq + 4], field + " mask low", index);
            eq(p[wp + 5], q[wq + 5], field + " mask high", index);
            eq(p[wp + 6], q[wq + 6], field + " depMax", index);
            intList(p[wp + 7], q[wq + 7], field + " lpSet", index);
        }

        void run() {
            Store s = x.store;
            Store t = y.store;
            if (exact) {
                eq(x.base().size(), y.base().size(), "levels", 0);
                eq(x.base().listCount(), y.base().listCount(), "lists", 0);
            }
            // names
            NameTable ns = s.names();
            NameTable nt = t.names();
            eq(ns.size(), nt.size(), "name count", 0);
            for (int n = 0; n < ns.size(); n++) {
                eq(ns.prefix(n), nt.prefix(n), "name prefix", n);
                eq(ns.constOf(n), nt.constOf(n), "name constOf", n);
                if (!ns.toString(n).equals(nt.toString(n))) {
                    fail(what + ": name " + n + ": " + ns.toString(n) + " against " + nt.toString(n));
                }
            }
            // literals
            LitPool ps = s.lits();
            LitPool pt = t.lits();
            eq(ps.natCount(), pt.natCount(), "pooled naturals", 0);
            for (int i = 1; i <= ps.natCount(); i++) {
                int len = ps.natLimbCount(i);
                eq(len, pt.natLimbCount(i), "natural limbs", i);
                for (int k = 0; k < len; k++) {
                    eq(ps.natLimb(i, k), pt.natLimb(i, k), "natural limb", i);
                }
            }
            eq(ps.stringCount(), pt.stringCount(), "pooled strings", 0);
            for (int i = 1; i <= ps.stringCount(); i++) {
                int len = ps.stringByteLength(i);
                eq(len, pt.stringByteLength(i), "string bytes", i);
                byte[] bs = new byte[len];
                byte[] bt = new byte[len];
                ps.stringBytes(i, bs, 0);
                pt.stringBytes(i, bt, 0);
                assertTrue(Arrays.equals(bs, bt), what + ": string literal " + i);
            }
            // shared term nodes
            int end = s.terms().exportEnd();
            eq(end, t.terms().exportEnd(), "export nodes", 0);
            int[] es = s.terms().exportArray();
            int[] et = t.terms().exportArray();
            for (int id = 1; id < end; id++) {
                node(es, id << 3, et, id << 3, "node", id);
            }
            // declarations
            DeclTable ds = s.decls();
            DeclTable dt = t.decls();
            eq(ds.constantCount(), dt.constantCount(), "constants", 0);
            eq(ds.unitCount(), dt.unitCount(), "units", 0);
            for (int c = 1; c <= ds.constantCount(); c++) {
                eq(ds.kind(c), dt.kind(c), "constant kind", c);
                eq(ds.flags(c), dt.flags(c), "constant flags", c);
                eq(ds.special(c), dt.special(c), "constant special", c);
                eq(ds.numLevelParams(c), dt.numLevelParams(c), "constant level parameters", c);
                eq(ds.unit(c), dt.unit(c), "constant unit", c);
                eq(ds.rank(c), dt.rank(c), "constant rank", c);
                eq(ds.name(c), dt.name(c), "constant name", c);
                eq(ds.type(c), dt.type(c), "constant type", c);
                eq(ds.value(c), dt.value(c), "constant value", c);
                eq(ds.hint(c), dt.hint(c), "constant hint", c);
                intList(ds.levelParams(c), dt.levelParams(c), "constant universe parameters", c);
            }
            for (int u = 0; u < ds.unitCount(); u++) {
                eq(ds.unitFirstCid(u), dt.unitFirstCid(u), "unit first constant", u);
                eq(ds.unitSize(u), dt.unitSize(u), "unit size", u);
                eq(ds.unitKind(u), dt.unitKind(u), "unit kind", u);
                eq(ds.unitFirstTerm(u), dt.unitFirstTerm(u), "unit first term", u);
                eq(ds.status(u), dt.status(u), "unit status", u);
                claims(ds.claims(u), dt.claims(u), u);
            }
            // proof blocks, decoded record by record
            ProofBlocks bs = s.blocks();
            ProofBlocks bt = t.blocks();
            eq(bs.count(), bt.count(), "blocks", 0);
            eq(bs.recordCount(), bt.recordCount(), "block records", 0);
            eq(bs.promotedCount(), bt.promotedCount(), "promoted records", 0);
            Overlay os = new Overlay(s, LevelStoreViews.lists(lx), LevelStoreViews.info(lx), new LitPool(s.lits()), 1L << 33);
            Overlay ot = new Overlay(t, LevelStoreViews.lists(ly), LevelStoreViews.info(ly), new LitPool(t.lits()), 1L << 33);
            for (int blk = 0; blk < bs.count(); blk++) {
                int n = bs.nodeCount(blk);
                eq(n, bt.nodeCount(blk), "block nodes", blk);
                eq(bs.rootRecord(blk), bt.rootRecord(blk), "block root", blk);
                eq(bs.firstRecord(blk), bt.firstRecord(blk), "block first record", blk);
                int baseS = os.nodeCount();
                int baseT = ot.nodeCount();
                eq(baseS, baseT, "overlay base", blk);
                int rs = os.blockRoot(blk);
                int rt = ot.blockRoot(blk);
                eq(rs, rt, "decoded block root", blk);
                eq(os.nodeCount(), ot.nodeCount(), "decoded nodes", blk);
                int[] as = os.array();
                int[] at = ot.array();
                for (int i = baseS, e = os.nodeCount(); i < e; i++) {
                    node(as, i << 3, at, i << 3, "block " + blk + " record", i - baseS);
                }
                os.reset();
                ot.reset();
            }
            // every expression record went to the same place
            eq(x.loader.exprCount(), y.loader.exprCount(), "expression records", 0);
            for (int o = 1, e = x.loader.exprCount(); o <= e; o++) {
                eq(x.loader.locationOf(o), y.loader.locationOf(o), "location of expression", o);
            }
            eq(s.loadedUnits(), t.loadedUnits(), "published units", 0);
            assertEquals(s.loadComplete(), t.loadComplete(), what + ": load complete");
        }

        private void claims(int[] p, int[] q, int unit) {
            if (p == null || q == null) {
                assertTrue(p == null && q == null, what + ": claims blob of unit " + unit);
                return;
            }
            eq(p.length, q.length, "claims length", unit);
            int nt = BlockClaims.numTypes(p);
            int nc = p[BlockClaims.H_NUM_CTORS];
            int nr = BlockClaims.numRecs(p);
            boolean[] list = new boolean[p.length];
            int at = BlockClaims.HEADER;
            for (int i = 0; i < nt; i++, at += BlockClaims.TYPE_WORDS) {
                list[at + BlockClaims.T_ALL] = true;
                list[at + BlockClaims.T_CTORS] = true;
            }
            at += nc * BlockClaims.CTOR_WORDS;
            for (int i = 0; i < nr; i++) {
                list[at + BlockClaims.R_ALL] = true;
                at = BlockClaims.nextRec(p, at);
            }
            for (int w = 0; w < p.length; w++) {
                if (list[w]) {
                    intList(p[w], q[w], "claims list of unit " + unit + " word", w);
                } else {
                    eq(p[w], q[w], "claims of unit " + unit + " word", w);
                }
            }
        }
    }
}
