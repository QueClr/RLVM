package rlvm.load;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import rlvm.level.LevelStore;
import rlvm.store.DeclKind;
import rlvm.store.DeclTable;
import rlvm.store.Kind;
import rlvm.store.NameTable;
import rlvm.store.Node;
import rlvm.store.Store;
import rlvm.store.Terms;

/**
 * The loader against the real {@code rlvm.store.Store} (no fakes): everything is written through
 * {@code new Loader(Store, ...)}'s production binding and read back through the store's reader API.
 */
class StoreIntegrationTest {

    private static StoreOracle load(Nd nd) throws IOException {
        StoreOracle o = new StoreOracle(nd.bytes(), true);
        o.load();
        o.verify();
        o.verifyStoreFlags();
        return o;
    }

    @Test
    void all230FilesIntoTheRealStore() throws IOException {
        List<Path> files = ArenaFilesTest.files();
        assertEquals(230, files.size());
        List<String> rejected = new ArrayList<>();
        long blockNodes = 0;
        long blockBytes = 0;
        for (Path file : files) {
            String rel = ArenaFilesTest.TESTS.relativize(file).toString();
            byte[] data = Files.readAllBytes(file);
            for (boolean loaderFlags : new boolean[] {true, false}) {
                StoreOracle o = new StoreOracle(data, loaderFlags);
                try {
                    o.load();
                } catch (MalformedExportException e) {
                    assertTrue(e.getMessage().startsWith("Duplicate declaration"), rel + ": " + e.getMessage());
                    if (loaderFlags) {
                        rejected.add(rel);
                    }
                    continue;
                }
                try {
                    o.verify();
                    if (loaderFlags) {
                        o.verifyStoreFlags();
                    } else {
                        blockNodes += o.report.blockNodes;
                        blockBytes += o.report.blockBytes;
                    }
                } catch (AssertionError | RuntimeException e) {
                    throw new AssertionError(rel + " (loader flags " + loaderFlags + "): " + e, e);
                }
                assertTrue(o.store.loadComplete(), rel);
                assertEquals(o.store.decls().unitCount(), o.store.loadedUnits(), rel);
            }
        }
        assertEquals(List.of("bad/tutorial/132_dup_defs.ndjson", "bad/tutorial/133_dup_ind_def.ndjson",
                "bad/tutorial/134_dup_ctor_def.ndjson", "bad/tutorial/135_dup_rec_def.ndjson",
                "bad/tutorial/138_dup_ctor_rec.ndjson", "bad/tutorial/139_DupConCon.ndjson"), rejected);
        assertTrue(blockNodes > 0 && blockBytes < 8 * blockNodes, "packed blocks: " + blockBytes + " bytes for " + blockNodes
                + " records");
    }

    @Test
    void all230FilesWithoutProofBlocks() throws IOException {
        for (Path file : ArenaFilesTest.files()) {
            byte[] data = Files.readAllBytes(file);
            StoreOracle o = new StoreOracle(data, true);
            try {
                o.load(LoadOptions.batch(data.length).withProofBlocks(false));
            } catch (MalformedExportException e) {
                continue;
            }
            try {
                o.verify();
                o.verifyStoreFlags();
            } catch (AssertionError | RuntimeException e) {
                throw new AssertionError(ArenaFilesTest.TESTS.relativize(file) + ": " + e, e);
            }
            assertEquals(0, o.report.blockValues);
            for (int c = 1; c <= o.store.decls().constantCount(); c++) {
                assertTrue(o.store.decls().value(c) >= 0, "no value is a block");
            }
        }
    }

    private static StoreOracle big(String name, boolean loaderFlags) throws IOException {
        Path file = BigFilesTest.EXPORTS.resolve(name);
        Assumptions.assumeTrue(Files.isRegularFile(file), name + " not present");
        StoreOracle o = new StoreOracle(file, loaderFlags);
        o.load();
        o.verify();
        if (loaderFlags) {
            o.verifyStoreFlags();
        }
        LoadReport r = o.report;
        assertEquals(0, r.unresolvedRefs);
        assertEquals(0, r.droppedUnsafe);
        Store s = o.store;
        assertEquals(r.constants, s.decls().constantCount());
        assertEquals(r.units, s.decls().unitCount());
        assertEquals(r.blocks, s.blocks().count());
        assertEquals(r.blockNodes, s.blocks().recordCount());
        assertEquals(r.sharedNodes + r.promotedNodes, s.terms().exportEnd() - 1);
        assertEquals(r.promotedNodes, s.blocks().promotedCount());
        System.out.println("[" + name + " -> store] " + r + String.format(java.util.Locale.ROOT,
                "  store: %.1f MB shared structures (terms %.1f, names %.1f, blocks %.1f, decls %.1f, lits %.1f)%n",
                s.sharedBytes() / 1e6, s.terms().bytes() / 1e6, s.names().bytes() / 1e6, s.blocks().bytes() / 1e6,
                s.decls().bytes() / 1e6, s.lits().bytes() / 1e6));
        return o;
    }

    @Test
    void initIntoTheRealStore() throws IOException {
        LoadReport r = big("init.ndjson", false).report;
        assertEquals(6_495_296, r.lines);
        assertEquals(6_137_092, r.exprRecords);
        assertEquals(59_979, r.constants);
        assertEquals(58_547, r.units);
    }

    @Test
    void initSharingFlagsOfStoreAndLoaderAgree() throws IOException {
        big("init.ndjson", true);
    }

    @Test
    void stdIntoTheRealStore() throws IOException {
        LoadReport r = big("std.ndjson", false).report;
        assertEquals(10_611_507, r.lines);
        assertEquals(9_928_514, r.exprRecords);
        assertEquals(103_885, r.constants);
        assertEquals(101_146, r.units);
    }

    @Test
    void randomExportsIntoTheRealStore() throws IOException {
        int loaded = 0;
        for (long seed = 1; seed <= 400; seed++) {
            byte[] data = RandomExportTest.generate(seed);
            for (boolean proofBlocks : new boolean[] {true, false}) {
                StoreOracle o = new StoreOracle(data, true);
                try {
                    o.load(LoadOptions.batch(data.length).withProofBlocks(proofBlocks));
                    o.verify();
                    o.verifyStoreFlags();
                } catch (AssertionError | RuntimeException e) {
                    throw new AssertionError("seed " + seed + " proofBlocks=" + proofBlocks + ": " + e, e);
                }
                loaded++;
            }
        }
        assertEquals(800, loaded);
    }

    @Test
    void namesAreClaimedBeforeTheTermsAreWritten() throws IOException {
        Nd nd = new Nd();
        int prop = nd.sort(0);
        int p = nd.name("P");
        nd.axiom(p, prop);
        int cp = nd.cnst(p);
        // a theorem whose proof mentions itself: bound to its own cid, depMax = own rank
        int self = nd.name("selfProof");
        int cself = nd.cnst(self);
        nd.thm(self, cp, cself);
        // a definition whose value mentions itself (shared node)
        int selfDef = nd.name("selfDef");
        nd.def(selfDef, cp, nd.cnst(selfDef));
        // an inductive type and its constructor
        int nat = nd.name("N");
        int zero = nd.str(nat, "zero");
        int type1 = nd.sort(nd.succ(0));
        int cnat = nd.cnst(nat);
        nd.inductive(Nd.strs(Nd.type(nat, type1, 0, 0, Nd.ints(nat), Nd.ints(zero), false)),
                Nd.strs(Nd.ctor(zero, cnat, nat, 0, 0, 0, false)), Nd.strs());
        // unknown name, later name, wrong number of levels
        int later = nd.name("later");
        nd.def(nd.name("fwd"), cp, nd.cnst(later));
        nd.axiom(later, prop);
        nd.def(nd.name("arity"), cp, nd.cnst(p, nd.succ(0)));
        StoreOracle o = load(nd);
        Store s = o.store;
        DeclTable d = s.decls();
        NameTable names = s.names();
        Terms t = new Terms(s.terms(), null);
        int selfDefCid = cidOf(o, "selfDef");
        int v = d.value(selfDefCid);
        assertEquals(Kind.CONST, t.kind(v));
        assertEquals(selfDefCid, t.a(v), "self reference bound to the own cid");
        assertEquals(d.rank(selfDefCid), t.depMax(v), "depMax = own rank");
        assertEquals(0, t.flags(v) & Node.F_UNRESOLVED);
        int zeroCid = cidOf(o, "N.zero");
        int natCid = cidOf(o, "N");
        assertEquals(natCid, t.a(d.type(zeroCid)), "members of a block see each other");
        assertEquals(d.unit(natCid), d.unit(zeroCid));
        assertEquals(DeclKind.INDUCT, d.unitKind(d.unit(natCid)));
        assertTrue(d.claims(d.unit(natCid)) != null);
        int fwd = cidOf(o, "fwd");
        assertTrue((t.flags(d.value(fwd)) & Node.F_UNRESOLVED) != 0, "a later name stays unbound");
        assertTrue(t.a(d.value(fwd)) < 0);
        assertTrue((t.flags(d.value(cidOf(o, "arity"))) & Node.F_UNRESOLVED) != 0, "wrong number of levels");
        assertEquals(2, o.report.unresolvedRefs);
        // the theorem's value is a block
        int thm = cidOf(o, "selfProof");
        assertTrue(d.value(thm) < 0);
        assertTrue((d.flags(thm) & DeclTable.CF_BLOCK_VALUE) != 0);
        assertEquals(1, s.blocks().count());
        assertEquals(names.constOf(d.name(thm)), thm);
        // unitFirstTerm: monotone, inside the export region
        int last = 1;
        for (int u = 0; u < d.unitCount(); u++) {
            assertTrue(d.unitFirstTerm(u) >= last && d.unitFirstTerm(u) <= s.terms().exportEnd(), "first term of unit " + u);
            last = d.unitFirstTerm(u);
        }
    }

    /** Roots (types, values, rule right-hand sides) of all constants that contain an unbound CONST / PROJ. */
    private static int unresolvedRoots(StoreOracle o) {
        Store s = o.store;
        DeclTable d = s.decls();
        Terms t = new Terms(s.terms(), null);
        int n = 0;
        for (int c = 1; c <= d.constantCount(); c++) {
            if ((t.flags(d.type(c)) & Node.F_UNRESOLVED) != 0) {
                n++;
            }
            if (d.value(c) > 0 && (t.flags(d.value(c)) & Node.F_UNRESOLVED) != 0) {
                n++;
            }
        }
        for (int u = 0; u < d.unitCount(); u++) {
            int[] claims = d.claims(u);
            if (claims == null) {
                continue;
            }
            int q = BlockClaims.firstRec(claims);
            for (int r = 0; r < BlockClaims.numRecs(claims); r++) {
                for (int i = 0; i < claims[q + BlockClaims.R_NUM_RULES]; i++) {
                    if ((t.flags(claims[BlockClaims.ruleAt(q, i) + BlockClaims.RULE_RHS]) & Node.F_UNRESOLVED) != 0) {
                        n++;
                    }
                }
                q = BlockClaims.nextRec(claims, q);
            }
        }
        return n;
    }

    /**
     * Hand-written files may write every expression record before the first declaration line. A record
     * that no root of the declaration at hand reaches must not become a shared node then: it would be
     * bound to the names declared so far, for good. It waits in an ownerless block and is promoted,
     * bound to the names of the unit that uses it, when that unit is written.
     */
    @Test
    void recordsWrittenBeforeEarlierDeclarationsBindWhenTheyAreUsed() throws IOException {
        Nd nd = new Nd();
        int prop = nd.sort(0);
        int type1 = nd.sort(nd.succ(0));
        int p = nd.name("P");
        int q = nd.name("Q");
        int t = nd.name("T");
        int mk = nd.str(t, "mk");
        int rec = nd.str(t, "rec");
        int cp = nd.cnst(p);
        int cq = nd.cnst(q);
        int ct = nd.cnst(t);
        int cmk = nd.cnst(mk);
        int qToT = nd.pi(cq, ct);          // Q -> T
        int useMk = nd.app(cmk, cq);       // garbage-looking term that mentions T.mk
        int thmValue = nd.lam(cq, nd.bvar(0));
        int thmType = nd.pi(cq, cq);
        // declarations only from here on
        nd.axiom(p, prop);
        nd.axiom(q, cp);
        nd.inductive(Nd.strs(Nd.type(t, type1, 0, 0, Nd.ints(t), Nd.ints(mk), false)),
                Nd.strs(Nd.ctor(mk, qToT, t, 0, 0, 1, false)),
                Nd.strs(Nd.rec(rec, qToT, Nd.ints(t), 0, 0, 1, 1, false, false, Nd.ints(mk, 1, useMk))));
        nd.thm(nd.name("thm"), thmType, thmValue);
        nd.def(nd.name("d"), ct, useMk);
        StoreOracle o = load(nd);
        assertEquals(0, unresolvedRoots(o), "no root was written before its names were claimed");
        assertEquals(0, o.report.unresolvedRefs);
        assertEquals(3, o.report.unresolvedBlockRefs, "const Q, T, T.mk waited unbound in the ownerless block (P was claimed)");
        Store s = o.store;
        DeclTable d = s.decls();
        Terms terms = new Terms(s.terms(), null);
        int qCid = cidOf(o, "Q");
        assertEquals(Kind.CONST, terms.kind(d.type(qCid)));
        assertEquals(cidOf(o, "P"), terms.a(d.type(qCid)), "const P, written at unit 1, is bound to P");
        int mkCid = cidOf(o, "T.mk");
        int mkType = d.type(mkCid);
        assertEquals(Kind.PI, terms.kind(mkType));
        assertEquals(cidOf(o, "T"), terms.a(terms.b(mkType)), "the constructor's result type is bound to the type of its own unit");
        assertEquals(d.rank(mkCid), terms.depMax(mkType));
        // the theorem's proof was promoted out of the ownerless block of unit 0: no block value
        assertTrue(d.value(cidOf(o, "thm")) > 0);
        assertEquals(1, s.blocks().count(), "one ownerless block, written at the first declaration");
        assertEquals(-1, s.blocks().rootRecord(0));
        assertEquals(o.report.sharedNodes, 1, "only Prop was reachable from the first axiom");
    }

    /** The four hand-written arena tests with all expression records in front (requests.log, rlvm-admit (g)). */
    @Test
    void handWrittenArenaFilesHaveNoLateBoundRoots() throws IOException {
        for (String name : new String[] {"large-elim-param", "level-imax-leq", "level-imax-normalization", "nat-rec-rules"}) {
            Path file = ArenaFilesTest.TESTS.resolve("bad/bugs/" + name + ".ndjson");
            Assumptions.assumeTrue(Files.isRegularFile(file), name);
            StoreOracle o = new StoreOracle(Files.readAllBytes(file), false);
            o.load();
            o.verify();
            assertEquals(0, unresolvedRoots(o), name);
            assertEquals(0, o.report.unresolvedRefs, name);
            assertTrue(o.report.unresolvedBlockRefs > 0, name);
            assertTrue(o.report.promotions > 0, name + ": the later units promote their records");
        }
    }

    private static int cidOf(StoreOracle o, String dotted) {
        NameTable names = o.store.names();
        int id = NameTable.ANONYMOUS;
        for (String part : dotted.split("\\.")) {
            byte[] b = part.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            id = names.findStr(id, b, 0, b.length);
            assertTrue(id != 0, "name " + dotted);
        }
        int cid = names.constOf(id);
        assertTrue(cid > 0, "constant " + dotted);
        return cid;
    }

    /**
     * A definition, theorem or opaque whose record names the type and value RECORDS of an earlier one of the same
     * kind, universe parameters and hint is marked as its repeat ({@code DeclTable.repeatOf}); nothing else is.
     */
    @Test
    void repeatedDeclarationsAreMarkedInTheRealStore() throws IOException {
        Nd nd = new Nd();
        int u = nd.name("u");
        int v = nd.name("v");
        int prop = nd.sort(0);
        int p = nd.name("P");
        nd.axiom(p, prop);                                       // unit 0
        int cp = nd.cnst(p);
        int ty = nd.pi(cp, cp);
        int val = nd.lam(cp, nd.bvar(0));
        nd.thm(nd.name("t1"), ty, val);                          // unit 1: the first theorem (value: a proof block)
        nd.thm(nd.name("t2"), ty, val);                          // unit 2: repeats 1 (value: the promoted block root)
        nd.def(nd.name("d1"), ty, val);                          // unit 3: another kind, the first definition
        nd.thm(nd.name("t3"), ty, val);                          // unit 4: repeats 1
        nd.def(nd.name("d2"), ty, val);                          // unit 5: repeats 3
        nd.def(nd.name("d3"), ty, val, "\"abbrev\"", "safe");    // unit 6: another hint
        nd.def(nd.name("d4"), ty, val, "{\"regular\":2}", "safe"); // unit 7: another height
        nd.def(nd.name("d5"), ty, val, "\"abbrev\"", "safe");    // unit 8: repeats 6
        nd.thm(nd.name("t4"), ty, val, u);                       // unit 9: other universe parameters
        nd.thm(nd.name("t5"), ty, val, v);                       // unit 10: again others
        nd.thm(nd.name("t6"), ty, val, u);                       // unit 11: repeats 9
        nd.opaque(nd.name("o1"), ty, val, false);                // unit 12: the first opaque
        nd.opaque(nd.name("o2"), ty, val, false);                // unit 13: repeats 12
        nd.def(nd.name("loop"), ty, val, "\"opaque\"", "unsafe"); // unit 14: dropped
        int val2 = nd.lam(cp, nd.bvar(0));                       // the same term as another record
        nd.thm(nd.name("t7"), ty, val2);                         // unit 15: not the same record
        nd.thm(nd.name("t8"), ty, val2);                         // unit 16: repeats 15
        int ty2 = nd.pi(cp, cp);
        nd.thm(nd.name("t9"), ty2, val);                         // unit 17: another type record
        nd.axiom(nd.name("Q"), prop);                            // unit 18: an axiom repeats nothing
        nd.thm(nd.name("t10"), ty, val);                         // unit 19: repeats 1
        StoreOracle o = load(nd);
        DeclTable d = o.store.decls();
        int[] expect = {-1, -1, 1, -1, 1, 3, -1, -1, 6, -1, -1, 9, -1, 12, -1, -1, 15, -1, -1, 1};
        assertEquals(expect.length, d.unitCount());
        for (int unit = 0; unit < expect.length; unit++) {
            assertEquals(expect[unit], d.repeatOf(unit), "unit " + unit);
        }
        assertEquals(8, o.report.repeatUnits);
        assertEquals(8, d.repeatCount());
        // the first theorem keeps its proof as a block; its repeats share ONE promoted copy of it
        assertTrue(d.value(d.unitFirstCid(1)) < 0);
        int promoted = d.value(d.unitFirstCid(2));
        assertTrue(promoted > 0);
        assertEquals(promoted, d.value(d.unitFirstCid(4)));
        assertEquals(promoted, d.value(d.unitFirstCid(19)));

        // the same file published unit by unit, and without proof blocks, says the same
        for (LoadOptions options : new LoadOptions[] {LoadOptions.pipelined(nd.bytes().length).withPublishEvery(1, 1L),
            LoadOptions.batch(nd.bytes().length).withProofBlocks(false)}) {
            StoreOracle q = new StoreOracle(nd.bytes(), false);
            q.load(options);
            q.verify();
            for (int unit = 0; unit < expect.length; unit++) {
                assertEquals(expect[unit], q.store.decls().repeatOf(unit), "unit " + unit + ", " + options.mode);
            }
        }
    }

    @Test
    void droppedRecordsAndDuplicatesOnTheRealStore() throws IOException {
        Nd nd = new Nd();
        int prop = nd.sort(0);
        int p = nd.name("P");
        nd.axiom(p, prop);
        int cp = nd.cnst(p);
        int loop = nd.name("unsafeLoop");
        nd.def(loop, cp, nd.cnst(loop), "\"opaque\"", "unsafe");
        nd.thm(nd.name("user"), cp, nd.cnst(loop));
        StoreOracle o = load(nd);
        Store s = o.store;
        byte[] b = "unsafeLoop".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int name = s.names().findStr(NameTable.ANONYMOUS, b, 0, b.length);
        assertEquals(NameTable.DROPPED_UNSAFE, s.names().constOf(name));
        assertEquals(1, o.report.droppedUnsafe);
        assertEquals(2, o.report.constants);
        assertEquals(3, s.decls().unitCount(), "the dropped record occupies a unit without constants");
        assertEquals(DeclKind.ST_SKIPPED, s.decls().status(1));
        assertEquals(0, s.decls().unitSize(1));
        assertEquals(2, o.report.unresolvedBlockRefs, "both const records naming the dropped constant are unbound");
        assertEquals(0, o.report.unresolvedRefs);

        // duplicate against a dropped name, and against a constant
        nd.axiom(loop, prop);
        MalformedExportException e = assertThrows(MalformedExportException.class, () -> new StoreOracle(nd.bytes(), false).load());
        assertEquals("Duplicate declaration: unsafeLoop", e.getMessage());
        Nd dup = new Nd();
        int sort = dup.sort(0);
        int x = dup.name("A.b");
        dup.axiom(x, sort);
        dup.def(dup.name("A.b"), sort, sort);
        e = assertThrows(MalformedExportException.class, () -> new StoreOracle(dup.bytes(), false).load());
        assertEquals("Duplicate declaration: A.b", e.getMessage());
        assertEquals(8, e.line());
    }

    @Test
    void theOneCallEntryPointWiresEverything() throws IOException {
        Path file = ArenaFilesTest.TESTS.resolve("good/init-prelude.ndjson");
        Assumptions.assumeTrue(Files.isRegularFile(file));
        LevelStore levels = new LevelStore();
        Store store = new Store(Files.size(file), LevelStoreViews.lists(levels), LevelStoreViews.info(levels));
        LoadReport r = ExportLoader.load(file, store, new LevelStoreInterner(levels), new NatStoreDecoder());
        assertTrue(r.complete);
        assertTrue(store.loadComplete());
        assertTrue(levels.isFrozen(), "batch mode freezes the level store at the end");
        assertEquals(r.constants, store.decls().constantCount());
        assertTrue(r.blockNodes > 0 && r.blockBytes > 0);
        assertFalse(r.sparseIds);
        assertTrue(r.wallNanos > 0);
    }
}
