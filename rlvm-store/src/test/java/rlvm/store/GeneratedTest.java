package rlvm.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import rlvm.store.Model.M;

/**
 * The generated region (*agentic-note*: design-architecture-document, 2.2, 4.2): shared copies of terms the environment thread
 * derived, generated constants, late names, and the rebinding of references written before the
 * constant existed.
 */
class GeneratedTest {

    private static Model.Gen gen(int size) {
        Model.Gen g = new Model.Gen();
        g.size = size;
        g.unresolved = false;
        return g;
    }

    /** {@code fun (_ : Sort 1) .. => m}, six binders: closes every variable the generator uses. */
    private static M closed(Model fx, M m) {
        M s = Model.sort(fx.levelIds[1]);
        for (int i = 0; i < 6; i++) {
            m = Model.lam(s, m);
        }
        return m;
    }

    /** Walks model and stored term together; checks every node once and collects the ids met. */
    private static void checkAll(Model fx, Terms t, int id, M m, IdentityHashMap<M, Integer> seen, String where) {
        Integer before = seen.put(m, id);
        if (before != null) {
            assertEquals(before, id, where + ": one model node, one stored node (sharing is kept)");
            return;
        }
        Checks.checkNode(fx, t, id, m, where);
        switch (m.kind) {
            case Kind.APP:
            case Kind.LAM:
            case Kind.PI:
                checkAll(fx, t, t.a(id), m.a, seen, where);
                checkAll(fx, t, t.b(id), m.b, seen, where);
                break;
            case Kind.LET:
                checkAll(fx, t, t.a(id), m.a, seen, where);
                checkAll(fx, t, t.b(id), m.b, seen, where);
                checkAll(fx, t, t.c(id), m.c, seen, where);
                break;
            case Kind.PROJ:
                checkAll(fx, t, t.a(id), m.a, seen, where);
                break;
            default:
                break;
        }
    }

    @Test
    void closedOverlayTermsBecomeSharedNodeForNode() {
        for (int seed = 0; seed < 40; seed++) {
            Model fx = new Model();
            fx.declareLiteralPrerequisites(true);
            Random rnd = new Random(seed);
            List<M> nodes = fx.random(rnd, gen(120));
            // the first third already exists as export terms: the copy must reference, not copy, them
            IdentityHashMap<M, Integer> memo = new IdentityHashMap<>();
            for (int i = 0; i < nodes.size() / 3; i++) {
                fx.toShared(nodes.get(i), memo);
            }
            fx.builder.finish();
            Overlay o = new Overlay(fx.store, fx.store.lists(), 1L << 24);
            Terms t = new Terms(fx.store.terms(), o);
            TermStore ts = fx.store.terms();
            GeneratedBuilder g = fx.store.generated();
            int exportEnd = ts.exportEnd();
            for (int round = 0; round < 6; round++) {
                M m = closed(fx, nodes.get(nodes.size() - 1 - rnd.nextInt(20)));
                int ov = fx.toOverlay(o, m, memo);
                assertTrue(Node.isOverlay(ov));
                int before = ts.generatedEnd();
                int overlayNodes = o.nodeCount();
                int id = g.term(t, ov);
                assertTrue(Node.isGenerated(id), "seed " + seed);
                assertEquals(ts.generatedEnd() - 1, id, "the root is written last");
                assertEquals(overlayNodes, o.nodeCount(), "the overlay is only read");
                assertEquals(exportEnd, ts.exportEnd(), "the export region is only read");
                assertEquals(fx.digest(m), fx.digest(t, id), "seed " + seed + " round " + round);
                Checks.clear();
                fx.clearMemos();
                IdentityHashMap<M, Integer> seen = new IdentityHashMap<>();
                checkAll(fx, t, id, m, seen, "generated, seed " + seed);
                // (two model nodes may be one overlay node, hence one generated node: count ids)
                java.util.HashSet<Integer> generated = new java.util.HashSet<>();
                for (java.util.Map.Entry<M, Integer> e : seen.entrySet()) {
                    int n = e.getValue();
                    assertFalse(Node.isOverlay(n), "a shared term never points into an overlay");
                    if (Node.isGenerated(n)) {
                        generated.add(n);
                        assertTrue(n >= before && n < ts.generatedEnd());
                        assertTrue((t.flags(n) & Node.F_SHARED) != 0, "generated nodes are SHARED");
                        assertEquals(0, t.flags(n) & (Node.F_HAS_FVAR | Node.F_UNRESOLVED));
                    } else {
                        assertTrue(n < exportEnd);
                        assertEquals(memo.get(e.getKey()), n, "an export sub-term is referenced as it is");
                    }
                }
                assertEquals(generated.size(), ts.generatedEnd() - before, "one generated node per overlay node reached");
                assertSame(ts.generatedArray(), t.regionOf(id), "the caller's reader was refreshed");
                assertEquals(id, g.term(t, id), "a shared term is returned as it is");
            }
            StoreMemory mem = fx.store.memory();
            assertEquals(ts.generatedEnd() - Node.GEN_BASE, mem.count[StoreMemory.GENERATED]);
            assertEquals(32L * mem.count[StoreMemory.GENERATED], mem.used[StoreMemory.GENERATED]);
            assertTrue(mem.allocated[StoreMemory.GENERATED] >= mem.used[StoreMemory.GENERATED]);
        }
    }

    @Test
    void whatCannotBeSharedIsRefusedAndLeavesNothingBehind() {
        Model fx = new Model();
        fx.declareLiteralPrerequisites(true);
        Store store = fx.store;
        assertThrows(IllegalStateException.class, store::generated, "not before the load is complete");
        fx.builder.finish();
        LitPool pool = new LitPool(store.lits());
        Overlay o = new Overlay(store, store.lists(), store.levelInfo(), pool, 1L << 24);
        Terms t = new Terms(store.terms(), o);
        TermStore ts = store.terms();
        GeneratedBuilder g = store.generated();
        int s1 = o.mkSort(fx.levelIds[1]);
        int c0 = o.mkConst(fx.constCids[0], 0);
        List<Integer> bad = new ArrayList<>();
        List<String> why = new ArrayList<>();
        bad.add(o.mkApp(c0, o.mkFVar(s1)));
        why.add("free variable");
        bad.add(o.mkLam(s1, o.mkApp(o.mkBVar(0), o.mkFVar(s1))));
        why.add("free variable under a binder");
        bad.add(o.mkLam(s1, o.mkApp(o.mkBVar(0), o.mkBVar(1))));
        why.add("loose bound variable");
        bad.add(o.mkApp(c0, o.mkConstByName(fx.undeclaredName, 0)));
        why.add("unbound constant");
        bad.add(o.mkProj(~fx.undeclaredName, 0, c0));
        why.add("unbound projection");
        bad.add(o.mkApp(c0, o.mkConst(fx.constCids[1], fx.levelLists[2])));
        why.add("wrong number of levels");
        bad.add(o.mkApp(c0, o.mkNatLitBig(pool.addNat(new long[] {1, 2, 3}, 0, 3))));
        why.add("worker literal");
        byte[] str = "only this worker".getBytes(StandardCharsets.UTF_8);
        bad.add(o.mkApp(c0, o.mkStrLit(pool.addString(str, 0, str.length))));
        why.add("worker string");
        // a level that exists only in this worker's level overlay
        int local = fx.levels.succ(fx.levels.succ(fx.levels.succ(fx.levelIds[4])));
        fx.levels.sharedBelow = local;
        bad.add(o.mkLam(o.mkSort(local), s1));
        why.add("worker level");
        for (int i = 0; i < bad.size(); i++) {
            int end = ts.generatedEnd();
            int[] array = ts.generatedArray();
            final int term = bad.get(i);
            assertThrows(GeneratedTermException.class, () -> g.term(t, term), why.get(i));
            assertEquals(end, ts.generatedEnd(), why.get(i) + ": nothing was added");
            assertSame(array, ts.generatedArray(), why.get(i) + ": nothing was published");
        }
        // the good neighbour of each of them is accepted, and literals of the shared pool are fine
        int good = o.mkLam(s1, o.mkApp(o.mkApp(c0, o.mkBVar(0)), o.mkApp(o.mkNatLitBig(fx.bigNat), o.mkStrLit(fx.strLit))));
        int id = g.term(t, good);
        assertTrue(Node.isGenerated(id));
        assertEquals(fx.bigNat, t.a(t.a(t.b(t.b(id)))));
        assertTrue(t.isNatBig(t.a(t.b(t.b(id)))));
        assertThrows(IllegalArgumentException.class, () -> g.term(t, 0));
        assertThrows(IllegalArgumentException.class, () -> g.term(t, ts.exportEnd() + 3));
        assertThrows(IllegalArgumentException.class, () -> g.term(t, Node.GEN_BASE | 999_999));
    }

    @Test
    void listsAndParameterSetsMustBeObtainableInTheSharedListStore() {
        Model fx = new Model();
        fx.declareLiteralPrerequisites(false);
        int u = fx.levelIds[2];
        int w = fx.levels.param(fx.paramNames[2]);
        // what the export mentions exists in the base: Sort u, Sort w, c2.{v, 1} and their parameter sets
        fx.builder.sort(u);
        fx.builder.sort(w);
        fx.builder.constant(fx.constNames[2], fx.levelLists[2]);
        fx.builder.finish();
        Store store = fx.store;
        ArrayIntLists base = (ArrayIntLists) store.lists();
        // from here on the base refuses new lists, as the run's list store does after the load
        base.freeze();
        ArrayIntLists workerLists = base.newOverlay();
        Overlay o = new Overlay(store, workerLists, store.levelInfo(), store.lits(), 1L << 24);
        Terms t = new Terms(store.terms(), o);
        GeneratedBuilder g = store.generated();
        TermStore ts = store.terms();
        // a level list that only the worker has
        int workerList = workerLists.intern(new int[] {w, w}, 0, 2);
        assertFalse(base.isSharedList(workerList));
        assertFalse(workerLists.isSharedList(workerList));
        assertTrue(workerLists.isSharedList(fx.levelLists[2]));
        int withWorkerList = o.mkConst(fx.constCids[2], workerList);
        int end = ts.generatedEnd();
        GeneratedTermException notShared = assertThrows(GeneratedTermException.class, () -> g.term(t, withWorkerList));
        assertTrue(notShared.getMessage().contains("not a shared list"), "refused for the list, before it is read: "
                + notShared.getMessage());
        // the parameter set {u, w} is in no export node: the frozen base cannot make it
        int su = o.mkSort(u);
        int sw = o.mkSort(w);
        int both = o.mkApp(su, sw);
        GeneratedTermException e = assertThrows(GeneratedTermException.class, () -> g.term(t, both));
        assertTrue(e.getCause() instanceof IllegalStateException, "the frozen list store said no");
        assertEquals(end, ts.generatedEnd(), "the half-written copy was withdrawn");
        // each sort alone, and a constant over a base list, are fine: the export has their parameter sets
        int gu = g.term(t, su);
        int gc = g.term(t, o.mkConst(fx.constCids[2], fx.levelLists[2]));
        assertTrue(Node.isGenerated(gu) && Node.isGenerated(gc));
        assertEquals(fx.levelLists[2], t.b(gc));
        assertEquals(1, base.length(t.lpSet(gu)));
        // and so is a generated constant's parameter list only if it is shared
        int unit = store.decls().unitCount() - 1;
        int name = g.name(NameTable.ANONYMOUS, "gen".getBytes(StandardCharsets.UTF_8), 0, 3);
        int workerNames = workerLists.intern(new int[] {fx.paramNames[2], fx.paramNames[0]}, 0, 2);
        assertThrows(GeneratedTermException.class, () -> g.constant(unit, DeclKind.REC, name, workerNames, gu));
        assertTrue(g.isFree(name), "a refused constant claims nothing");
    }

    @Test
    void generatedConstantsLateNamesAndRebinding() throws Exception {
        Model fx = new Model();
        fx.declareLiteralPrerequisites(false);
        StoreBuilder b = fx.builder;
        Store store = fx.store;
        DeclTable d = store.decls();
        NameTable names = store.names();
        IntLists ls = store.lists();
        int u = fx.levelIds[2];
        int lpU = ls.intern(new int[] {fx.paramNames[0]}, 0, 1);
        int listU = fx.levelLists[1];
        // unit A: an inductive block I, I.mk whose export has no recursor record
        int nI = fx.name("I");
        int nMk = fx.name("I.mk");
        int unitI = b.beginBlock();
        int cI = b.claimMember(DeclKind.INDUCT, nI, lpU);
        int cMk = b.claimMember(DeclKind.CTOR, nMk, lpU);
        int tyI = b.sort(u);
        b.setMemberType(cI, tyI);
        b.setMemberType(cMk, b.constant(nI, listU));
        b.endBlock();
        // unit B: a quot record under its own name only
        int nQuot = fx.name("Quot");
        int unitQ = d.unitCount();
        int cQuot = b.beginDeclaration(DeclKind.QUOT_TYPE, nQuot, lpU);
        b.endDeclaration(cQuot, b.sort(u), 0, 0);
        // unit C: an axiom whose type mentions I.rec and Quot.lift, which have no record: unbound at load
        int nRec = fx.name("I.rec");
        int nLift = fx.name("Quot.lift");
        int listUU = ls.intern(new int[] {u, u}, 0, 2);
        int lpUV = ls.intern(new int[] {fx.paramNames[0], fx.paramNames[1]}, 0, 2);
        int cAx = b.beginDeclaration(DeclKind.AXIOM, fx.name("user"), lpU);
        int refRec = b.constant(nRec, listUU);
        int refLift = b.constant(nLift, listUU);
        int projUnbound = b.proj(fx.name("S"), 1, refRec);
        int rootUser = b.pi(b.app(refRec, refLift), b.app(projUnbound, b.constant(nI, listU)));
        b.endDeclaration(cAx, rootUser, 0, 0);
        int unitAx = d.unit(cAx);
        // unit D mentions a name nobody will ever declare, and a reference with the wrong arity
        int cAx2 = b.beginDeclaration(DeclKind.AXIOM, fx.name("user2"), lpU);
        int rootUser2 = b.app(b.constant(fx.undeclaredName, 0), b.app(b.constant(nRec, listUU), b.constant(nI, 0)));
        b.endDeclaration(cAx2, rootUser2, 0, 0);
        // unit E projects out of a structure whose constant will be generated
        int nLate = fx.name("I.late");
        int cAx3 = b.beginDeclaration(DeclKind.AXIOM, fx.name("user3"), lpU);
        int projLate = b.proj(nLate, 2, b.constant(nI, listU));
        int rootUser3 = b.lam(b.sort(u), b.app(projLate, b.bvar(0)));
        b.endDeclaration(cAx3, rootUser3, 0, 0);
        assertEquals(~nLate, new Terms(store.terms(), null).c(projLate));
        int loaderConstants = d.constantCount();
        int namesBefore = names.size();
        Terms shared = new Terms(store.terms(), null);
        assertTrue((shared.flags(rootUser) & Node.F_UNRESOLVED) != 0);
        assertEquals(~nRec, shared.a(refRec));
        assertEquals(Node.DEP_UNRESOLVED, shared.depMax(rootUser));
        assertThrows(IllegalStateException.class, store::generated);
        b.finish();

        // a worker that exists before anything is generated
        Overlay workerOverlay = new Overlay(store, ls, 1L << 22);
        Terms workerTerms = new Terms(store.terms(), workerOverlay);

        // ---- the environment thread admits unit A and generates I.rec
        Overlay o = new Overlay(store, ls, 1L << 24);
        Terms t = new Terms(store.terms(), o);
        TermOps ops = new TermOps(t, ls, store.levelInfo());
        GeneratedBuilder g = store.generated();
        assertEquals(nRec, g.name(nI, "rec".getBytes(StandardCharsets.UTF_8), 0, 3), "a name of the file keeps its id");
        assertEquals(namesBefore, names.size());
        int nRec1 = g.name(nI, "rec_1".getBytes(StandardCharsets.UTF_8), 0, 5);
        assertEquals(namesBefore, nRec1, "a late name gets the next id");
        assertEquals(nRec1, g.name(nI, "rec_1".getBytes(StandardCharsets.UTF_8), 0, 5));
        assertEquals("I.rec_1", names.toString(nRec1));
        int nNum = g.nameNum(nRec1, 7);
        assertEquals("I.rec_1.7", names.toString(nNum));
        assertTrue(g.isFree(nRec) && g.isFree(nRec1) && !g.isFree(nI));

        int motive = o.mkPi(o.mkConst(cI, listU), o.mkSort(fx.levelIds[3]));
        int ovType = o.mkPi(motive, o.mkPi(o.mkConst(cI, listU), o.mkApp(o.mkBVar(1), o.mkBVar(0))));
        int type = g.term(t, ovType);
        int cRec = g.constant(unitI, DeclKind.REC, nRec, lpUV, type);
        assertEquals(loaderConstants + 1, cRec, "generated constants follow the loader's");
        assertEquals(DeclKind.REC, d.kind(cRec));
        assertEquals(DeclTable.CF_GENERATED | DeclTable.CF_BLOCK_MEMBER, d.flags(cRec));
        assertTrue(d.isGenerated(cRec) && !d.isGenerated(cI));
        assertEquals(unitI, d.unit(cRec));
        assertEquals(d.rank(cI), d.rank(cRec), "the rank of the unit it was generated for");
        assertEquals(nRec, d.name(cRec));
        assertEquals(type, d.type(cRec));
        assertEquals(lpUV, d.levelParams(cRec));
        assertEquals(2, d.numLevelParams(cRec));
        assertEquals(0, d.value(cRec));
        assertEquals(cRec, names.constOf(nRec));
        assertEquals(2, d.unitSize(unitI), "the unit's own constants are still the export's records");
        assertEquals(1, d.generatedCount());
        assertEquals(cRec, d.generatedCid(0));
        assertEquals(0, g.constant(unitI, DeclKind.REC, nRec, lpUV, type), "already declared");
        assertEquals(0, g.constant(unitI, DeclKind.REC, nI, lpUV, type), "the name of an exported constant");
        assertEquals(loaderConstants + 1, d.constantCount(), "a refused name adds no row");
        // its rule mentions the recursor itself: the row exists, so the overlay can refer to it
        int self = o.mkConst(cRec, listUU);
        int rhs = g.term(t, o.mkLam(motive, o.mkLam(o.mkConst(cI, listU), o.mkApp(self, o.mkBVar(0)))));
        assertEquals(d.rank(cRec), t.depMax(rhs));
        d.setRecursor(cRec, 0, 0, 1, 1, 0, new int[] {cMk, 0, rhs}, 1, cI);
        assertEquals(1, d.recRuleCount(cRec));
        assertEquals(rhs, d.recRuleRhs(cRec, 0));
        assertEquals(cMk, d.recRuleCtor(cRec, 0));
        assertEquals(cI, d.recMajorInduct(cRec));
        // an auxiliary recursor whose type is set after the row exists
        int cRec1 = g.constant(unitI, DeclKind.REC, nRec1, lpUV, 0);
        assertEquals(cRec + 1, cRec1);
        g.setType(cRec1, g.term(t, o.mkPi(o.mkConst(cRec, listUU), o.mkConst(cRec1, listUU))));
        assertEquals(d.rank(cI), t.depMax(d.type(cRec1)));
        int cLate = g.constant(unitI, DeclKind.CTOR, nLate, lpU, type);
        assertEquals(cRec1 + 1, cLate);
        // what a generated constant cannot be
        assertThrows(IllegalArgumentException.class, () -> g.constant(unitI, DeclKind.DEFN, nNum, 0, type));
        assertThrows(IllegalArgumentException.class, () -> g.constant(unitI, DeclKind.THM, nNum, 0, type));
        assertThrows(IllegalArgumentException.class, () -> g.constant(unitI, DeclKind.REC, nNum, 0, ovType),
                "an overlay term is not a type");
        assertThrows(GeneratedTermException.class, () -> g.constant(unitI, DeclKind.REC, nNum, 0, rootUser),
                "a type with an unresolved reference");
        assertThrows(IllegalArgumentException.class, () -> g.constant(d.unitCount(), DeclKind.REC, nNum, 0, type));
        assertThrows(IllegalArgumentException.class, () -> g.setType(cI, type), "not a generated constant");
        assertTrue(g.isFree(nNum));
        store.publishEnv(unitI + 1);
        assertThrows(IllegalArgumentException.class, () -> g.constant(unitI, DeclKind.REC, nNum, 0, type),
                "the unit is published");
        assertThrows(IllegalStateException.class, () -> g.setType(cRec1, type));

        // ---- unit B: the quotient constants the export does not have
        int cLift = g.constant(unitQ, DeclKind.QUOT_LIFT, nLift, lpUV, g.term(t, o.mkPi(o.mkSort(u), o.mkSort(fx.levelIds[3]))));
        assertEquals(cLate + 1, cLift);
        assertEquals(DeclTable.CF_GENERATED, d.flags(cLift));
        assertEquals(Specials.QUOT_LIFT, d.special(cLift));
        assertEquals(cLift, store.specials().cid(Specials.QUOT_LIFT));
        assertEquals(d.rank(cQuot), store.specials().rank(Specials.QUOT_LIFT));
        assertEquals(4, d.generatedCount());
        assertEquals(cLift, d.generatedCid(3));
        assertEquals(cLate, d.generatedCid(2));
        store.publishEnv(unitQ + 1);

        // ---- unit C can be checked now: its references are bound in a copy
        assertEquals(~nRec, t.a(refRec), "the export node itself never changes");
        int bound = ops.rebind(rootUser);
        assertTrue(Node.isOverlay(bound));
        assertEquals(cRec, t.a(t.a(t.a(bound))));
        assertEquals(cLift, t.a(t.b(t.a(bound))));
        assertEquals(nRec, t.c(t.a(t.a(bound))), "the name stays");
        assertEquals(listUU, t.b(t.a(t.a(bound))));
        assertEquals(d.rank(cRec), t.depMax(t.a(t.a(bound))));
        // .. except the projection out of a structure that still has no constant
        assertTrue((t.flags(bound) & Node.F_UNRESOLVED) != 0);
        assertEquals(Node.DEP_UNRESOLVED, t.depMax(bound));
        assertEquals(~fx.name("S"), t.c(t.a(t.b(bound))));
        assertEquals(0, t.flags(t.a(bound)) & Node.F_UNRESOLVED, "the part without it is resolved");
        assertEquals(Math.max(d.rank(cRec), d.rank(cLift)), t.depMax(t.a(bound)));
        assertTrue(d.rank(cLift) < d.rank(cAx) && d.rank(cRec) < d.rank(cAx), "visible to the unit that uses them");
        assertTrue(ops.eqv(rootUser, bound), "the same term up to binding");
        assertEquals(bound, ops.rebind(rootUser), "hash-consed: the same copy again");
        assertEquals(bound, ops.rebind(bound), "nothing more to bind");
        int clean = t.a(bound);
        assertEquals(clean, ops.rebind(clean), "a resolved term is returned as it is");
        assertEquals(tyI, ops.rebind(tyI));
        // a projection out of a structure that has a constant now is bound too
        int bound3 = ops.rebind(rootUser3);
        int proj3 = t.a(t.b(bound3));
        assertEquals(Kind.PROJ, t.kind(proj3));
        assertEquals(cLate, t.c(proj3));
        assertEquals(2, t.b(proj3));
        assertEquals(t.a(projLate), t.a(proj3), "the resolved structure argument is kept as it is");
        assertEquals(0, t.flags(bound3) & Node.F_UNRESOLVED);
        assertEquals(Math.max(d.rank(cLate), d.rank(cI)), t.depMax(bound3));
        assertEquals(1, t.looseRange(t.b(bound3)));
        assertTrue(ops.eqv(rootUser3, bound3));
        assertTrue(unitAx < d.unitCount());
        // unit D: the unknown name stays unbound, the wrong arity stays flagged
        int bound2 = ops.rebind(rootUser2);
        assertEquals(~fx.undeclaredName, t.a(t.a(bound2)));
        int recWrong = t.a(t.b(bound2));
        assertEquals(cRec, t.a(recWrong));
        assertEquals(0, t.flags(recWrong) & Node.F_UNRESOLVED, "two levels for two parameters");
        int iWrong = t.b(t.b(bound2));
        assertEquals(cI, t.a(iWrong));
        assertTrue((t.flags(iWrong) & Node.F_UNRESOLVED) != 0, "no level for one parameter stays flagged");
        assertTrue((t.flags(bound2) & Node.F_UNRESOLVED) != 0);

        // ---- another thread reads what was published
        AtomicReference<Throwable> failure = new AtomicReference<>();
        final long typeDigest = fx.digest(t, type);
        final long rhsDigest = fx.digest(t, rhs);
        Thread worker = new Thread(() -> {
            try {
                assertEquals(unitQ + 1, store.envUnits());
                workerTerms.refresh();
                assertEquals(type, store.decls().type(cRec));
                assertEquals(typeDigest, fx.digest(workerTerms, type));
                assertEquals(rhsDigest, fx.digest(workerTerms, store.decls().recRuleRhs(cRec, 0)));
                int app = workerOverlay.mkApp(type, store.decls().recRuleRhs(cRec, 0));
                assertEquals(type, workerTerms.a(app));
                assertEquals(store.decls().rank(cRec), workerTerms.depMax(app));
                TermOps wops = new TermOps(workerTerms, ls, store.levelInfo());
                int inst = wops.instantiateLevelParams(type, lpUV, listUU);
                assertNotEquals(type, inst);
                assertEquals(cRec, store.names().constOf(nRec));
                assertEquals(cRec, workerTerms.a(wops.rebind(refRec)));
            } catch (Throwable e) {
                failure.set(e);
            }
        });
        worker.start();
        worker.join();
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
    }

    @Test
    void theRegionGrowsWithoutDisturbingWhatWasPublished() {
        Model fx = new Model();
        fx.declareLiteralPrerequisites(false);
        fx.builder.finish();
        Store store = fx.store;
        Overlay o = new Overlay(store, store.lists(), 1L << 28);
        Terms t = new Terms(store.terms(), o);
        TermStore ts = store.terms();
        GeneratedBuilder g = store.generated();
        int s = o.mkSort(fx.levelIds[1]);
        int first = g.term(t, o.mkLam(s, o.mkBVar(0)));
        int[] early = ts.generatedArray();
        long firstDigest = fx.digest(t, first);
        Terms old = new Terms(ts, null);
        int cur = first;
        for (int round = 0; round < 50; round++) {
            int chain = cur;
            for (int i = 0; i < 4000; i++) {
                chain = o.mkApp(chain, o.mkNatLit(((long) round << 32) | i));
            }
            cur = g.term(t, chain);
            assertEquals(4000 * (round + 1), t.spineLength(cur));
            o.reset();
        }
        assertEquals(first, t.head(cur));
        assertNotEquals(early.length, ts.generatedArray().length, "the region was reallocated");
        assertEquals(Kind.LAM, Node.kind(early[Node.wordOf(first)]), "the old array still holds what was published in it");
        assertEquals(firstDigest, fx.digest(old, first), "a reader that did not refresh still reads the old ids");
        assertEquals(firstDigest, fx.digest(t, first));
        assertEquals(3 + 50 * 8000, ts.generatedEnd() - Node.GEN_BASE);
        // the block hint of generated binders
        ts.setBinderHint(first, 31337);
        assertEquals(31337, t.c(first));
        ts.setBinderHint(cur, 5);
        assertEquals(first, t.c(cur), "not a binder: the head word of an application is left alone");
        ts.setBinderHint(Node.GEN_BASE | (ts.generatedEnd() + 10), 5);
    }
}
