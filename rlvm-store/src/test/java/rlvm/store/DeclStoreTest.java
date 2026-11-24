package rlvm.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** WP-A2: declaration table, specials (loader / environment split), builder protocol, frontiers, shared facts. */
class DeclStoreTest {

    @Test
    void rowsUnitsAndRanks() {
        Model fx = new Model();
        Store store = fx.store;
        DeclTable d = store.decls();
        StoreBuilder b = fx.builder;
        assertEquals(6, d.constantCount());
        assertEquals(6, d.unitCount());
        for (int i = 0; i < 6; i++) {
            int cid = fx.constCids[i];
            assertEquals(i + 1, cid);
            assertEquals(DeclKind.AXIOM, d.kind(cid));
            assertEquals(i, d.unit(cid));
            assertEquals(i + 1, d.rank(cid), "ranks are 1-based");
            assertEquals(fx.constNames[i], d.name(cid));
            assertEquals(i % 3, d.numLevelParams(cid));
            assertEquals(i % 3, store.lists().length(d.levelParams(cid)));
            assertEquals(0, d.value(cid));
            assertEquals(0, d.flags(cid));
            assertEquals(-1, d.special(cid));
            assertEquals(cid, store.names().constOf(fx.constNames[i]));
            assertEquals(cid, d.unitFirstCid(i));
            assertEquals(1, d.unitSize(i));
            assertEquals(DeclKind.AXIOM, d.unitKind(i));
            assertEquals(DeclKind.ST_LOADED, d.status(i));
        }
        // a definition: value, hint, UNFOLDS; the roots are flagged BODY
        int ty = b.sort(fx.levelIds[1]);
        int val = b.app(b.constant(fx.constNames[0], 0), b.natSmall(3));
        int def = b.beginDeclaration(DeclKind.DEFN, fx.name("def"), 0);
        b.endDeclaration(def, ty, val, DeclKind.hintRegular(4));
        assertEquals(DeclKind.DEFN, d.kind(def));
        assertEquals(ty, d.type(def));
        assertEquals(val, d.value(def));
        assertEquals(5, d.hint(def));
        assertEquals(DeclTable.CF_UNFOLDS, d.flags(def));
        Terms t = new Terms(store.terms(), null);
        assertTrue((t.header(ty) & Node.F_BODY) != 0);
        assertTrue((t.header(val) & Node.F_BODY) != 0);
        assertEquals(ty, d.unitFirstTerm(d.unit(def)), "the unit's segment starts at its first node");
        // a theorem with a block value
        ProofBlocks.Writer w = b.blockWriter();
        int thm = b.beginDeclaration(DeclKind.THM, fx.name("thm"), 0);
        int tty = b.sort(fx.levelIds[0]);
        w.bvar(0);
        int block = w.finish(0);
        b.endDeclaration(thm, tty, -(block + 1), 0);
        assertEquals(-(block + 1), d.value(thm));
        assertEquals(DeclTable.CF_UNFOLDS | DeclTable.CF_BLOCK_VALUE, d.flags(thm));
        assertEquals(DeclKind.HINT_OPAQUE, d.hint(thm));
        assertEquals(tty, d.unitFirstTerm(d.unit(thm)));
        assertEquals(DeclKind.HINT_ABBREV, Integer.MAX_VALUE);
        assertEquals(Integer.MAX_VALUE - 1, DeclKind.hintRegular(0xFFFF_FFFFL), "regular heights stay below abbrev");
        // status transitions
        int u = d.unit(def);
        assertTrue(d.casStatus(u, DeclKind.ST_LOADED, DeclKind.ST_ENV_READY));
        assertFalse(d.casStatus(u, DeclKind.ST_LOADED, DeclKind.ST_RUNNING));
        assertTrue(d.casStatus(u, DeclKind.ST_ENV_READY, DeclKind.ST_RUNNING));
        assertTrue(d.casStatus(u, DeclKind.ST_RUNNING, DeclKind.ST_ACCEPTED));
        assertEquals(DeclKind.ST_ACCEPTED, d.status(u));
        assertEquals(DeclKind.ST_LOADED, d.status(u + 1));
        assertTrue(d.bytes() > 0);
        assertTrue(store.sharedBytes() > 0);
        assertThrows(IllegalStateException.class, () -> b.complete(def, ty, 0, 0), "no open unit");
    }

    @Test
    void namesAreClaimedBeforeTheTermsAreWritten() {
        Model fx = new Model();
        StoreBuilder b = fx.builder;
        DeclTable d = fx.store.decls();
        Terms t = new Terms(fx.store.terms(), null);
        IntLists ls = fx.store.lists();
        int u1 = ls.intern(fx.paramNames, 0, 1);
        // two-phase: a reference to the constant inside its own terms binds, with depMax = own rank
        int self = fx.name("self");
        int cid = b.beginDeclaration(DeclKind.THM, self, u1);
        int ref = b.constant(self, fx.levelLists[1]);
        t.refresh();
        assertEquals(cid, t.a(ref));
        assertEquals(d.rank(cid), t.depMax(ref));
        assertEquals(0, t.header(ref) & Node.F_UNRESOLVED);
        int bad = b.constant(self, 0);
        t.refresh();
        assertTrue((t.header(bad) & Node.F_UNRESOLVED) != 0, "wrong number of levels");
        assertEquals(cid, t.a(bad), "but bound");
        assertEquals(Node.DEP_UNRESOLVED, t.depMax(bad));
        b.endDeclaration(cid, ref, ref, 0);
        // one call: the name is claimed after the terms, a self reference stays unbound
        int late = fx.name("late");
        int lref = b.constant(late, 0);
        int lcid = b.declare(DeclKind.DEFN, late, 0, lref, lref, 0);
        t.refresh();
        assertTrue(lcid > 0);
        assertEquals(~late, t.a(lref));
        assertEquals(late, t.c(lref));
        assertTrue((t.header(lref) & Node.F_UNRESOLVED) != 0);
        assertEquals(Node.DEP_UNRESOLVED, t.depMax(lref));
        // a later node for the same name is bound
        int lref2 = b.constant(late, 0);
        t.refresh();
        assertEquals(lcid, t.a(lref2));
        assertEquals(d.rank(lcid), t.depMax(lref2));
        // an inductive block: types, constructors and recursors see each other
        int unit = b.beginBlock();
        int nI = fx.name("I");
        int nMk = fx.name("I.mk");
        int nRec = fx.name("I.rec");
        int ci = b.claimMember(DeclKind.INDUCT, nI, 0);
        int cmk = b.claimMember(DeclKind.CTOR, nMk, 0);
        int crec = b.claimMember(DeclKind.REC, nRec, u1);
        int tI = b.sort(fx.levelIds[1]);
        int tMk = b.pi(b.constant(nI, 0), b.constant(nI, 0));
        int tRec = b.app(b.constant(nRec, fx.levelLists[1]), b.proj(nI, 0, b.constant(nMk, 0)));
        b.setMemberType(ci, tI);
        b.setMemberType(cmk, tMk);
        b.setMemberType(crec, tRec);
        b.blockClaims(new int[] {1, 2, 3, 99}, 3);
        b.endBlock();
        t.refresh();
        assertEquals(unit + 1, t.depMax(tMk));
        assertEquals(unit + 1, t.depMax(tRec));
        assertEquals(0, t.header(tRec) & Node.F_UNRESOLVED);
        assertEquals(ci, t.c(t.b(tRec)), "PROJ is bound to the structure's cid");
        assertEquals(3, d.unitSize(unit));
        assertEquals(ci, d.unitFirstCid(unit));
        assertEquals(DeclKind.INDUCT, d.unitKind(unit));
        for (int c : new int[] {ci, cmk, crec}) {
            assertEquals(unit, d.unit(c));
            assertEquals(unit + 1, d.rank(c));
            assertEquals(DeclTable.CF_BLOCK_MEMBER, d.flags(c));
        }
        assertEquals(tRec, d.type(crec));
        assertArrayEquals(new int[] {1, 2, 3}, d.claims(unit));
        assertArrayEquals(new int[] {1, 2, 3}, d.unitClaims(unit));
        assertNull(d.claims(unit - 1));
        d.dropClaims(unit);
        assertNull(d.claims(unit));
        // names under the reserved root _nested are noticed
        assertEquals(-1, d.firstUnitWithNestedRootName());
        int nu = b.beginUnit(DeclKind.AXIOM);
        int nc = b.reserve(DeclKind.AXIOM, fx.name("_nested.I_1"), 0);
        b.complete(nc, tI, 0, 0);
        b.endUnit();
        assertEquals(nu, d.firstUnitWithNestedRootName());
        // the generic protocol, as rlvm-load uses it
        int gu = b.beginUnit(DeclKind.OPAQUE);
        int gc = b.reserve(DeclKind.OPAQUE, fx.name("opq"), 0);
        int gt = b.constant(fx.name("opq"), 0);
        b.complete(gc, gt, gt, 0);
        b.endUnit();
        assertEquals(gu, d.unit(gc));
        assertEquals(gt, d.type(gc));
        assertEquals(gt, d.value(gc));
        assertEquals(0, d.flags(gc), "an opaque does not unfold");
    }

    @Test
    void duplicateNamesAndDroppedRecords() {
        Model fx = new Model();
        StoreBuilder b = fx.builder;
        DeclTable d = fx.store.decls();
        NameTable names = fx.store.names();
        int ty = b.sort(fx.levelIds[1]);
        int units = d.unitCount();
        int constants = d.constantCount();
        assertEquals(0, b.beginDeclaration(DeclKind.DEFN, fx.constNames[2], 0), "declared twice");
        assertEquals(units + 1, d.unitCount(), "the failed unit is closed and holds no constant");
        assertEquals(0, d.unitSize(units));
        assertEquals(constants, d.constantCount());
        assertEquals(0, b.declare(DeclKind.AXIOM, fx.constNames[0], 0, ty, 0, 0));
        // members of one block with the same name
        b.beginBlock();
        int x = fx.name("X");
        assertTrue(b.member(DeclKind.INDUCT, x, 0, ty) > 0);
        assertEquals(0, b.member(DeclKind.CTOR, x, 0, ty));
        b.endBlock();
        // an unsafe record is dropped: the name is remembered, users stay unbound
        int unsafe = fx.name("unsafeLoop");
        int before = d.unitCount();
        assertTrue(b.dropUnsafe(unsafe));
        assertEquals(NameTable.DROPPED_UNSAFE, names.constOf(unsafe));
        assertEquals(before + 1, d.unitCount());
        assertEquals(DeclKind.ST_SKIPPED, d.status(before));
        assertEquals(0, d.unitSize(before));
        assertEquals(DeclKind.NONE, d.unitKind(before));
        int user = b.constant(unsafe, 0);
        Terms t = new Terms(fx.store.terms(), null);
        assertEquals(~unsafe, t.a(user));
        assertEquals(Node.DEP_UNRESOLVED, t.depMax(user));
        // the official reader keeps one map for all records: any second record of a name is a duplicate
        assertFalse(b.dropUnsafe(unsafe));
        assertFalse(b.dropName(unsafe));
        assertEquals(0, b.declare(DeclKind.AXIOM, unsafe, 0, ty, 0, 0));
        assertFalse(b.dropUnsafe(fx.constNames[1]));
        assertEquals(fx.constCids[1], names.constOf(fx.constNames[1]));
        assertTrue(b.dropName(fx.name("another")));
    }

    @Test
    void sideTablesHoldTheDerivedFacts() {
        Model fx = new Model();
        StoreBuilder b = fx.builder;
        DeclTable d = fx.store.decls();
        int ty = b.sort(fx.levelIds[1]);
        b.beginBlock();
        int i1 = b.member(DeclKind.INDUCT, fx.name("T"), 0, ty);
        int i2 = b.member(DeclKind.INDUCT, fx.name("U"), 0, ty);
        int c1 = b.member(DeclKind.CTOR, fx.name("T.a"), 0, ty);
        int c2 = b.member(DeclKind.CTOR, fx.name("T.b"), 0, ty);
        int c3 = b.member(DeclKind.CTOR, fx.name("U.c"), 0, ty);
        int r1 = b.member(DeclKind.REC, fx.name("T.rec"), 0, ty);
        int r2 = b.member(DeclKind.REC, fx.name("U.rec"), 0, ty);
        b.endBlock();
        b.finish();
        // nothing derived yet
        assertEquals(0, d.inductCtorCount(i1));
        assertEquals(0, d.inductCtors(i1));
        assertEquals(0, d.recRuleCount(r1));
        assertEquals(-1, d.recRuleFor(r1, c1));
        // the environment thread fills the rows, with arrays
        d.setInductive(i1, 2, 1, new int[] {c1, c2}, 2, new int[] {i1, i2}, 2, DeclTable.IF_REC | DeclTable.IF_REFLEXIVE, 3, r1);
        d.setInductive(i2, 2, 0, new int[] {c3, 0, 0}, 1, new int[] {i1, i2}, 2, DeclTable.IF_STRUCT_LIKE | DeclTable.IF_ONE_CTOR, 0, r2);
        d.setConstructor(c1, i1, 0, 2, 0);
        d.setConstructor(c2, i1, 1, 2, 3);
        d.setConstructor(c3, i2, 0, 2, 1);
        d.setRecursor(r1, 2, 1, 2, 3, DeclTable.RF_K, new int[] {c1, 0, ty, c2, 3, ty + 0}, 2, i1);
        d.setRecursor(r2, 2, 0, 2, 3, 0, new int[] {c3, 1, ty}, 1, i2);
        assertEquals(2, d.inductNumParams(i1));
        assertEquals(1, d.inductNumIndices(i1));
        assertEquals(2, d.inductCtorCount(i1));
        assertEquals(c1, d.inductCtor(i1, 0));
        assertEquals(c2, d.inductCtor(i1, 1));
        assertThrows(IndexOutOfBoundsException.class, () -> d.inductCtor(i1, 2));
        assertEquals(2, d.inductAllCount(i1));
        assertEquals(i2, d.inductAllAt(i1, 1));
        assertEquals(DeclTable.IF_REC | DeclTable.IF_REFLEXIVE, d.inductFlags(i1));
        assertEquals(3, d.inductNumNested(i1));
        assertEquals(r1, d.inductRec(i1));
        assertEquals(1, d.inductCtorCount(i2));
        assertEquals(c3, d.inductCtor(i2, 0));
        assertEquals(0, d.inductNumNested(i2));
        assertEquals(i1, d.ctorInduct(c2));
        assertEquals(1, d.ctorIndex(c2));
        assertEquals(2, d.ctorNumParams(c2));
        assertEquals(3, d.ctorNumFields(c2));
        assertEquals(i2, d.ctorInduct(c3));
        assertEquals(2, d.recNumParams(r1));
        assertEquals(1, d.recNumIndices(r1));
        assertEquals(2, d.recNumMotives(r1));
        assertEquals(3, d.recNumMinors(r1));
        assertEquals(8, d.recMajorIndex(r1));
        assertEquals(DeclTable.RF_K, d.recFlags(r1));
        assertEquals(i1, d.recMajorInduct(r1));
        assertEquals(2, d.recRuleCount(r1));
        assertEquals(c2, d.recRuleCtor(r1, 1));
        assertEquals(3, d.recRuleFields(r1, 1));
        assertEquals(ty, d.recRuleRhs(r1, 1));
        assertEquals(1, d.recRuleFor(r1, c2));
        assertEquals(0, d.recRuleFor(r1, c1));
        assertEquals(-1, d.recRuleFor(r1, c3));
        assertEquals(0, d.recRuleFor(r2, c3));
        assertThrows(IndexOutOfBoundsException.class, () -> d.recRuleCtor(r1, 2));
        // facts are per kind
        assertThrows(IllegalArgumentException.class, () -> d.inductNumParams(c1));
        assertThrows(IllegalArgumentException.class, () -> d.ctorInduct(i1));
        assertThrows(IllegalArgumentException.class, () -> d.recNumParams(fx.constCids[0]));
        // the list-id forms copy lists that the base list store holds
        IntLists ls = fx.store.lists();
        int ctors = ls.intern(new int[] {c2, c1}, 0, 2);
        int all = ls.intern(new int[] {i1}, 0, 1);
        int rules = ls.intern(new int[] {c2, 7, ty}, 0, 3);
        d.setInductive(i1, 5, 6, ctors, all, 0, 0, 0);
        d.setRecursor(r1, 1, 1, 1, 1, 0, rules, i1);
        assertEquals(ctors, d.inductCtors(i1));
        assertEquals(all, d.inductAll(i1));
        assertEquals(c2, d.inductCtor(i1, 0));
        assertEquals(1, d.inductAllCount(i1));
        assertEquals(5, d.inductNumParams(i1));
        assertEquals(1, d.recRuleCount(r1));
        assertEquals(7, d.recRuleFields(r1, 0));
        // many side rows (several chunks) and pool growth
        b.beginBlock();
        int n = 5000;
        int[] cids = new int[n];
        for (int k = 0; k < n; k++) {
            cids[k] = b.member(k % 2 == 0 ? DeclKind.CTOR : DeclKind.REC, fx.name("m" + k), 0, ty);
        }
        b.endBlock();
        for (int k = 0; k < n; k++) {
            if (k % 2 == 0) {
                d.setConstructor(cids[k], i1, k, k + 1, k + 2);
            } else {
                d.setRecursor(cids[k], k, 0, 0, 0, 0, new int[] {cids[k - 1], k, ty}, 1, i1);
            }
        }
        for (int k = 0; k < n; k++) {
            if (k % 2 == 0) {
                assertEquals(k, d.ctorIndex(cids[k]));
                assertEquals(k + 2, d.ctorNumFields(cids[k]));
            } else {
                assertEquals(k, d.recNumParams(cids[k]));
                assertEquals(cids[k - 1], d.recRuleCtor(cids[k], 0));
                assertEquals(k, d.recRuleFields(cids[k], 0));
            }
        }
        assertEquals(c1, d.inductCtor(i1, 1), "earlier pool entries survive the growth");
    }

    @Test
    void specialsAreBoundByTheLoaderAndJudgedByTheEnvironment() {
        Model fx = new Model();
        StoreBuilder b = fx.builder;
        Store store = fx.store;
        Specials sp = store.specials();
        DeclTable d = store.decls();
        Terms t = new Terms(store.terms(), null);
        assertEquals(fx.name("Nat"), sp.nameOf(Specials.NAT));
        assertEquals(fx.name("Nat.shiftRight"), sp.nameOf(Specials.NAT_SHIFT_RIGHT));
        assertEquals(fx.name("eagerReduce"), sp.nameOf(Specials.EAGER_REDUCE));
        assertEquals(Specials.QUOT_LIFT, sp.indexOfName(fx.name("Quot.lift")));
        assertEquals(-1, sp.indexOfName(fx.constNames[0]));
        assertEquals(-1, sp.indexOfName(fx.name("Nat.foo")));
        for (int w = 0; w < Specials.COUNT; w++) {
            assertEquals(0, sp.cid(w));
            assertEquals(Specials.ABSENT, sp.state(w));
            assertEquals(w, sp.indexOfName(sp.nameOf(w)));
            assertEquals(Specials.nameString(w), store.names().toString(sp.nameOf(w)));
        }
        assertEquals(Integer.MAX_VALUE, sp.natLiteralRank());
        assertEquals(Integer.MAX_VALUE, sp.stringLiteralRank());
        assertEquals(Integer.MAX_VALUE, sp.quotRank());
        // a literal written before Nat is declared is unresolved for good
        int early = b.natSmall(5);
        assertEquals(Node.DEP_UNRESOLVED, t.depMax(early));
        assertTrue((t.header(early) & Node.F_HAS_CONST) != 0);
        int ty = b.sort(fx.levelIds[1]);
        // the Nat block: bound at claim, i.e. before the block's own terms are written
        int unit = b.beginBlock();
        int nat = b.claimMember(DeclKind.INDUCT, fx.name("Nat"), 0);
        int zero = b.claimMember(DeclKind.CTOR, fx.name("Nat.zero"), 0);
        assertEquals(Integer.MAX_VALUE, sp.natLiteralRank(), "Nat.succ is still missing");
        int succ = b.claimMember(DeclKind.CTOR, fx.name("Nat.succ"), 0);
        assertEquals(unit + 1, sp.natLiteralRank());
        int inBlock = b.natSmall(6);
        b.setMemberType(nat, ty);
        b.setMemberType(zero, ty);
        b.setMemberType(succ, ty);
        b.endBlock();
        t.refresh();
        assertEquals(unit + 1, t.depMax(inBlock));
        assertEquals(nat, sp.cid(Specials.NAT));
        assertEquals(succ, sp.cid(Specials.NAT_SUCC));
        assertEquals(unit + 1, sp.rank(Specials.NAT_ZERO));
        assertEquals(Specials.NAT, sp.which(nat));
        assertEquals(Specials.NAT_SUCC, sp.which(succ));
        assertEquals(Specials.NAT_SUCC, d.special(succ));
        assertEquals(-1, sp.which(fx.constCids[0]));
        assertEquals(Specials.ABSENT, sp.state(Specials.NAT), "the guard is the environment's business");
        int later = b.natSmall(5);
        int big = b.natBig(fx.bigNat);
        int str = b.str(fx.strLit);
        t.refresh();
        assertEquals(unit + 1, t.depMax(later));
        assertEquals(unit + 1, t.depMax(big));
        assertEquals(Node.DEP_UNRESOLVED, t.depMax(str), "string prerequisites are missing");
        assertEquals(Node.DEP_UNRESOLVED, t.depMax(early));
        // the string prerequisites, one unit each; the rank is the largest
        int last = 0;
        for (String n : new String[] {"String", "String.ofList", "Char", "Char.ofNat", "List.nil", "List.cons"}) {
            assertEquals(Integer.MAX_VALUE, sp.stringLiteralRank());
            last = b.beginDeclaration(DeclKind.AXIOM, fx.name(n), 0);
            b.endDeclaration(last, ty, 0, 0);
        }
        assertEquals(d.rank(last), sp.stringLiteralRank());
        int str2 = b.str(fx.strLit);
        t.refresh();
        assertEquals(d.rank(last), t.depMax(str2));
        assertEquals(unit + 1, sp.natLiteralRank());
        // quot
        int q = 0;
        for (String n : new String[] {"Quot", "Quot.mk", "Quot.lift", "Quot.ind"}) {
            assertEquals(Integer.MAX_VALUE, sp.quotRank());
            q = b.beginDeclaration(n.equals("Quot") ? DeclKind.QUOT_TYPE : DeclKind.QUOT_LIFT, fx.name(n), 0);
            b.endDeclaration(q, ty, 0, 0);
        }
        assertEquals(d.rank(q), sp.quotRank());
        assertEquals(Specials.QUOT_IND, sp.which(q));
        // environment half
        sp.bind(Specials.NAT, nat, Specials.VALID);
        sp.setState(Specials.NAT_SUCC, Specials.INVALID);
        assertEquals(Specials.VALID, sp.state(Specials.NAT));
        assertEquals(Specials.INVALID, sp.state(Specials.NAT_SUCC));
        assertEquals(Specials.ABSENT, sp.state(Specials.NAT_ZERO));
        assertThrows(IllegalStateException.class, () -> sp.bind(Specials.NAT, succ, Specials.VALID));
        // reordered admission: ranks follow, and so do the literal ranks
        d.setRank(unit, 40);
        assertEquals(40, d.rank(succ));
        assertEquals(40, sp.natLiteralRank());
        assertThrows(IllegalArgumentException.class, () -> d.setRank(unit, 0));
        // annotation names exist for TermOps
        assertEquals(fx.name("optParam"), sp.annotationName(Specials.ANN_OPT_PARAM));
        assertEquals(fx.name("semiOutParam"), sp.annotationName(Specials.ANN_SEMI_OUT_PARAM));
    }

    @Test
    void frontiersAreMonotone() {
        Model fx = new Model();
        Store store = fx.store;
        assertEquals(0, store.loadedUnits());
        assertEquals(0, store.envUnits());
        assertFalse(store.loadComplete());
        fx.builder.publish();
        assertEquals(6, store.loadedUnits());
        assertFalse(store.loadComplete());
        assertThrows(IllegalArgumentException.class, () -> store.publishEnv(7));
        store.publishEnv(4);
        assertEquals(4, store.envUnits());
        assertThrows(IllegalArgumentException.class, () -> store.publishEnv(3));
        assertThrows(IllegalArgumentException.class, () -> store.publishLoaded(5, false));
        assertThrows(IllegalArgumentException.class, () -> store.publishLoaded(7, false));
        int c = fx.builder.beginDeclaration(DeclKind.AXIOM, fx.name("more"), 0);
        fx.builder.endDeclaration(c, fx.builder.sort(fx.levelIds[1]), 0, 0);
        assertEquals(6, store.loadedUnits(), "not published yet");
        fx.builder.finish();
        assertEquals(7, store.loadedUnits());
        assertTrue(store.loadComplete());
        store.publishEnv(7);
        assertEquals(store.builder(), fx.builder);
        // the generated region exists and is empty until admission generates a constant (GeneratedTest)
        assertThrows(IllegalArgumentException.class, () -> store.terms().appendGenerated(null, 0));
        assertEquals(Node.GEN_BASE, store.terms().generatedEnd());
        assertEquals(0, store.terms().generatedArray().length);
        assertEquals(0, store.decls().generatedCount());
    }

    @Test
    void sharedFactsAreBytesPerExportNode() {
        Model fx = new Model();
        SharedFacts f = fx.store.facts();
        int a = fx.builder.bvar(0);
        assertEquals(0, f.bytes(), "nothing is allocated before the first fact");
        assertFalse(f.isChecked(a));
        assertEquals(0, f.get(a));
        f.setChecked(a);
        assertTrue(f.isChecked(a));
        f.or(a, 0x40);
        assertEquals(0x41, f.get(a));
        assertEquals(0, f.get(a + 1));
        // later nodes, beyond the array as it was
        int last = a;
        for (int i = 0; i < 100_000; i++) {
            last = fx.builder.bvar(i & 7);
        }
        assertEquals(0, f.get(last));
        f.or(last, SharedFacts.MACHINE_MASK);
        assertEquals(SharedFacts.MACHINE_MASK, f.get(last));
        assertFalse(f.isChecked(last));
        assertEquals(0x41, f.get(a));
        // ids outside the export region are ignored
        f.setChecked(Node.OVERLAY_BIT | 5);
        f.setChecked(Node.GEN_BASE | 5);
        f.setChecked(0);
        assertFalse(f.isChecked(Node.OVERLAY_BIT | 5));
        assertFalse(f.isChecked(Node.GEN_BASE | 5));
        assertNotEquals(0, f.bytes());
    }

    @Test
    void tablesGrowFromSmallEstimates() {
        Model fx = new Model(0);
        StoreBuilder b = fx.builder;
        DeclTable d = fx.store.decls();
        int ty = b.sort(fx.levelIds[1]);
        int n = 120_000;
        int[] cids = new int[n];
        int[] names = new int[n];
        for (int i = 0; i < n; i++) {
            names[i] = fx.store.names().internNum(fx.constNames[0], i);
            cids[i] = b.beginDeclaration(DeclKind.DEFN, names[i], 0);
            int v = b.app(b.constant(names[i > 0 ? i - 1 : 0], 0), b.natSmall(i));
            b.endDeclaration(cids[i], ty, v, DeclKind.hintRegular(i));
            if (i % 1000 == 0) {
                b.publish();
            }
        }
        b.finish();
        Terms t = new Terms(fx.store.terms(), null);
        assertEquals(n + 6, d.constantCount());
        assertEquals(n + 6, fx.store.loadedUnits());
        for (int i = 0; i < n; i += 13) {
            int cid = cids[i];
            assertEquals(names[i], d.name(cid));
            assertEquals(i + 1, d.hint(cid));
            assertEquals(i, t.natSmall(t.b(d.value(cid))));
            assertEquals(Node.DEP_UNRESOLVED, t.depMax(d.value(cid)), "the literal's prerequisites are not declared");
            assertEquals(d.rank(i > 0 ? cids[i - 1] : cid), t.depMax(t.a(d.value(cid))));
            assertEquals(i > 0 ? cids[i - 1] : cid, t.a(t.a(d.value(cid))));
            assertEquals(DeclKind.ST_LOADED, d.status(d.unit(cid)));
            if (i > 0) {
                assertEquals(t.a(d.value(cid)), d.unitFirstTerm(d.unit(cid)));
            }
        }
    }
}
