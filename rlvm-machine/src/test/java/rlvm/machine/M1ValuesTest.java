package rlvm.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import rlvm.api.MachineErrors;
import rlvm.nat.NatOp;
import rlvm.store.IntLists;
import rlvm.store.Specials;

/**
 * M1 acceptance: value records and interning (ISA 2.1-2.6, 4.2): I-ID on random records (two constructions,
 * one id), PI against PII, {@code ls} in closure identity, CONST heads, applications in one probe, the
 * {@code Nat.succ} rows, projections of constructors, PEEK, and reset in O(used).
 */
@Tag("fast")
class M1ValuesTest {

    static Fixture f;
    static Machine m;

    @BeforeAll
    static void prelude() {
        f = Fixture.prelude0();
        m = f.m;
    }

    static int args(Machine m, int... vs) {
        if (!m.ensureScratch(vs.length)) {
            throw new IllegalStateException("scratch");
        }
        System.arraycopy(vs, 0, m.X, m.xp, vs.length);
        return m.xp;
    }

    static int app(Machine m, int fn, int... vs) {
        return Values.mkApp(m, fn, args(m, vs), vs.length);
    }

    static int konst(String name, String... levels) {
        return Values.mkConst(m, f.cid(name), f.levels(levels));
    }

    static int flag(int v, int mask) {
        return Values.flags(m, v) & mask;
    }

    // ---- I-ID on random records ---------------------------------------------------------------------------

    /** Two constructions of the same random value give one id; different constructions give different ids (ISA 2.5 I-ID). */
    @Test
    void randomRecordsAreInternedOnceOnATinyMachine() {
        Machine t = new Machine(Machine.Sizes.tiny());
        Random rnd = new Random(42);
        int n = 4000;
        int[] kind = new int[n];
        int[] a = new int[n];
        int[] b = new int[n];
        int[] c = new int[n];
        int[] ref = new int[n];
        int nat = Values.mkSort(t, 1000); // outside the random range
        int x0 = Values.mkFVar(t, nat, 0);
        for (int i = 0; i < n; i++) {
            kind[i] = rnd.nextInt(7);
            a[i] = 1 + rnd.nextInt(60);
            b[i] = kind[i] >= 5 ? rnd.nextInt(8) : 0;          // only FVAR and NEU read b
            c[i] = kind[i] == 4 ? rnd.nextInt(2) : kind[i] == 6 ? rnd.nextInt(3) : 0;   // SUSP: let; NEU: spine length
            ref[i] = make(t, kind[i], a[i], b[i], c[i], x0);
            assertTrue(ref[i] >= Layout.FIRST_RECORD && ref[i] < Op.SIG, "a record ref");
        }
        // second construction: the same ids, although the tables have grown many times in between
        for (int i = 0; i < n; i++) {
            assertEquals(ref[i], make(t, kind[i], a[i], b[i], c[i], x0), "row " + i);
        }
        // one id per distinct key, and no two distinct keys share an id
        java.util.HashMap<Long, Integer> seen = new java.util.HashMap<>();
        java.util.HashMap<Integer, Long> back = new java.util.HashMap<>();
        for (int i = 0; i < n; i++) {
            long key = ((long) kind[i] << 48) | ((long) a[i] << 32) | ((long) b[i] << 16) | c[i];
            Integer prev = seen.put(key, ref[i]);
            assertTrue(prev == null || prev == ref[i]);
            Long other = back.put(ref[i], key);
            assertTrue(other == null || other == key, "two keys, one id");
        }
        assertEquals(seen.size() + 2, t.vp - Layout.FIRST_RECORD, "exactly one record per distinct value");
        assertEquals(t.vp - Layout.FIRST_RECORD, used(t.vtab), "one intern slot per record");
    }

    private static int make(Machine t, int kind, int a, int b, int c, int x0) {
        switch (kind) {
            case 0:
                return Values.mkSort(t, a);
            case 1:
                return Values.mkLam(t, a, 0, 0);
            case 2:
                return Values.mkPi(t, a, 0, 0);
            case 3:
                return Values.mkPii(t, a, 0, 0);
            case 4:
                return Values.mkSusp(t, a, 0, 0, c == 1);
            case 5:
                return Values.mkFVar(t, x0, a * 8 + b);
            default: {
                int xb = t.xp;
                t.ensureScratch(3);
                t.X[xb] = Layout.imm(a * 8 + b);
                t.X[xb + 1] = x0;
                t.X[xb + 2] = Layout.imm(a);
                return Values.mkNeu(t, x0, xb, 1 + c);
            }
        }
    }

    private static int used(int[] tab) {
        int n = 0;
        for (int e : tab) {
            if (e != 0) {
                n++;
            }
        }
        return n;
    }

    /**
     * The dense OPEN bit table (NOTES-M1 section 3) is a copy of the records' own flag: for every record this
     * class made the entry exists and agrees with flag OPEN; a record made by other code has no entry and is
     * answered from the record; a reset forgets every entry.
     */
    @Test
    void theOpenBitTableAgreesWithTheFlags() {
        Machine t = new Machine(Machine.Sizes.tiny());
        int ty = Values.mkSort(t, 3);
        int x = Values.mkFVar(t, ty, 0);
        int n = 70_000; // more records than the table's base size covers: it grows
        t.ensureScratch(2);
        for (int i = 0; i < n; i++) {
            t.X[0] = Layout.imm(i);
            t.X[1] = (i & 1) == 0 ? x : Layout.imm(7);
            int v = Values.mkNeu(t, (i % 3) == 0 ? x : ty, 0, 2); // head: the variable (open) or a sort (closed head, for the flags only)
            assertTrue(v < Op.SIG);
        }
        int open = 0;
        for (int r = t.vbase; r < t.vp; r++) {
            int bits = Values.openBitsOf(t, r);
            assertTrue(bits == 1 || bits == 3, "every record made by Values has an entry: record " + r);
            boolean flag = (Values.flags(t, r) & Layout.FL_OPEN) != 0;
            assertEquals(flag, bits == 3, "record " + r);
            assertEquals(flag ? Layout.W0_OPEN : 0, Values.openOf(t, r));
            open += flag ? 1 : 0;
        }
        assertTrue(open > n / 2 && open < n, "both kinds occur: " + open);
        assertEquals(0, Values.openOf(t, Layout.imm(5)));
        assertEquals(0, Values.openOf(t, Layout.HOLE));
        assertEquals(0, Values.openOf(t, Layout.NONE));
        // a record made through the reference probe has no entry: the record's flag answers
        int foreignOpen = t.internRecord(Layout.w0(Layout.NSUC, Layout.FL_OPEN, 0), x, 9, 0);
        int foreignClosed = t.internRecord(Layout.w0(Layout.NSUC, 0, 0), ty, 9, 0);
        assertEquals(0, Values.openBitsOf(t, foreignOpen));
        assertEquals(Layout.W0_OPEN, Values.openOf(t, foreignOpen));
        assertEquals(0, Values.openOf(t, foreignClosed));
        // frames and applications over them take the answer from either source
        t.X[0] = foreignOpen;
        assertTrue(Runs.isOpen(t, Runs.frame(t, 0, 1, 0)));
        t.X[0] = foreignClosed;
        assertFalse(Runs.isOpen(t, Runs.frame(t, 0, 1, 0)));
        assertFalse(Values.isOpen(t, Values.mkNeu(t, ty, 0, 1)));
        t.X[0] = foreignOpen;
        assertTrue(Values.isOpen(t, Values.mkNeu(t, ty, 0, 1)));
        assertTrue(t.x1.bytes() > 0 && t.x1.bytes() <= 8L * ((t.vp >>> 5) + 1), "1/128 of the records");
        // reset: no entry survives, and the same record index of the next session is judged afresh
        int vp = t.vp;
        t.reset();
        assertEquals(0, t.x1.bytes());
        for (int r = 0; r < vp; r += 7) {
            assertEquals(0, Values.openBitsOf(t, r));
        }
        int a = Values.mkSort(t, 3); // takes the index that x's type had; then a closed record where x was
        int b = Values.mkSort(t, 4);
        assertEquals(1, Values.openBitsOf(t, a));
        assertEquals(1, Values.openBitsOf(t, b));
        assertEquals(0, Values.openOf(t, b));
    }

    /** The probe of Values and the reference probe of Machine find each other's records (frozen table format). */
    @Test
    void theProbeAgreesWithTheReferenceProbe() {
        Machine t = new Machine(Machine.Sizes.tiny());
        for (int i = 1; i < 300; i++) {
            int w0 = Layout.w0(Layout.LAM, Layout.FL_WHNF, 0);
            int r = (i & 1) == 0 ? t.internRecord(w0, i, 0, 0) : Values.mkLam(t, i, 0, 0);
            assertEquals(r, Values.mkLam(t, i, 0, 0));
            assertEquals(r, t.internRecord(w0, i, 0, 0));
        }
    }

    // ---- kinds and closure identity -------------------------------------------------------------------------

    @Test
    void piAndPiiOverTheSameTermAndFrameAreDifferentValues() {
        int t = f.term("(fun (x : Nat) x)");
        int lam = Values.mkLam(m, t, 0, 0);
        int pi = Values.mkPi(m, t, 0, 0);
        int pii = Values.mkPii(m, t, 0, 0);
        assertNotEquals(pi, pii, "bugs/pi-closure-context");
        assertNotEquals(lam, pii);
        assertNotEquals(lam, pi);
        assertEquals(Layout.LAM, Values.kind(m, lam));
        assertEquals(Layout.PI, Values.kind(m, pi));
        assertEquals(Layout.PII, Values.kind(m, pii));
        assertEquals(pii, Values.mkPii(m, t, 0, 0));
        assertEquals(Layout.FL_WHNF, Values.flags(m, lam), "a closure over the empty frame: WHNF, not OPEN");
        assertEquals(t, Values.a(m, lam));
        assertEquals(0, Values.b(m, lam));
    }

    /** ISA 2.7: ls is part of closure identity, and it is 0 for a term without level parameters. */
    @Test
    void lsIsPartOfClosureIdentityOnlyForTermsWithLevelParameters() {
        int s12 = Values.mkConst(m, f.cid("c1"), f.levels("1", "2"));
        int s21 = Values.mkConst(m, f.cid("c1"), f.levels("2", "1"));
        int ls12 = Levels.substOf(m, s12);
        int ls21 = Levels.substOf(m, s21);
        assertTrue(ls12 > 0 && ls21 > 0 && ls12 != ls21);
        int body = f.store.decls().value(f.cid("c1")); // (-> (sort u) (sort v)): mentions u and v
        assertTrue(m.terms.hasLevelParam(body));
        assertNotEquals(Values.mkPi(m, body, 0, ls12), Values.mkPi(m, body, 0, ls21), "bugs/closure-level-subst");
        assertEquals(Values.mkPi(m, body, 0, ls12), Values.mkPi(m, body, 0, ls12));
        assertNotEquals(Values.mkPi(m, body, 0, ls12), Values.mkPi(m, body, 0, 0));
        int closed = f.term("(fun (x : Nat) x)"); // no level parameter: ls is canonicalised to 0
        assertFalse(m.terms.hasLevelParam(closed));
        assertEquals(Values.mkLam(m, closed, 0, 0), Values.mkLam(m, closed, 0, ls12));
        assertEquals(0, Values.c(m, Values.mkLam(m, closed, 0, ls21)));
        assertEquals(Values.suspRecord(m, closed, 0, 0), Values.suspRecord(m, closed, 0, ls12));
    }

    /**
     * The substitution regression of ISA 2.7 (RECONCILIATION B1): c1.{u,v} and c2.{v,u} share one body term;
     * c1.{1,2} and c2.{1,2} give different substitutions, different closures and different LSUB results.
     */
    @Test
    void substitutionRegressionOfTwoConstantsSharingABodyUnderSwappedParameters() {
        int c1 = f.cid("c1");
        int c2 = f.cid("c2");
        int body = f.store.decls().value(c1);
        assertEquals(body, f.store.decls().value(c2), "the fixture shares ONE body term (Fixture.defShared)");
        int levels = f.levels("1", "2");
        int h1 = Values.mkConst(m, c1, levels);
        int h2 = Values.mkConst(m, c2, levels);
        assertNotEquals(h1, h2);
        int ls1 = Levels.substOf(m, h1);
        int ls2 = Levels.substOf(m, h2);
        assertNotEquals(ls1, ls2, "the substitution pairs the levels with the parameter names");
        assertNotEquals(Values.mkPi(m, body, 0, ls1), Values.mkPi(m, body, 0, ls2), "different closures at the same levels");
        int u = f.level("u");
        int v = f.level("v");
        assertEquals(f.level("1"), Levels.lsub(m, u, ls1));
        assertEquals(f.level("2"), Levels.lsub(m, u, ls2));
        assertEquals(f.level("2"), Levels.lsub(m, v, ls1));
        assertEquals(f.level("1"), Levels.lsub(m, v, ls2));
        assertNotEquals(Values.mkSort(m, Levels.lsub(m, u, ls1)), Values.mkSort(m, Levels.lsub(m, u, ls2)));
        // the swapped level list under the swapped parameters is the same instantiation of the body
        int h2s = Values.mkConst(m, c2, f.levels("2", "1"));
        assertEquals(Levels.lsub(m, u, ls1), Levels.lsub(m, u, Levels.substOf(m, h2s)));
        // identity instantiation: 0 (ISA 2.7), for both
        assertEquals(0, Levels.substOf(m, Values.mkConst(m, c1, f.levels("u", "v"))));
        assertEquals(0, Levels.substOf(m, Values.mkConst(m, c2, f.levels("v", "u"))));
        assertNotEquals(0, Levels.substOf(m, Values.mkConst(m, c2, f.levels("u", "v"))));
        // second call of everything: the caches answer the same
        assertEquals(f.level("1"), Levels.lsub(m, u, ls1));
        assertEquals(h1, Values.mkConst(m, c1, levels));
    }

    @Test
    void sortsAreInternedThroughTheSortCache() {
        int l1 = f.level("1");
        int s = Values.mkSort(m, l1);
        assertEquals(s, Values.mkSort(m, l1));
        assertEquals(s, m.internRecord(Layout.w0(Layout.SORT, 0, 0), l1, 0, 0), "the cache is in front of the table, not instead of it");
        assertEquals(Layout.FL_WHNF, Values.flags(m, s));
        assertEquals(l1, Values.a(m, s));
        // more levels than the cache has slots: every one keeps its record
        int[] refs = new int[200];
        int l = f.level("0");
        int[] ids = new int[200];
        for (int i = 0; i < 200; i++) {
            ids[i] = l;
            refs[i] = Values.mkSort(m, l);
            l = m.levels.succ(l);
        }
        for (int i = 0; i < 200; i++) {
            assertEquals(refs[i], Values.mkSort(m, ids[i]));
            assertEquals(ids[i], Values.a(m, refs[i]));
        }
        f.expectFail(MachineErrors.E_INTERNAL, () -> f.run(Values.mkSort(m, 0)));
        assertEquals(Op.ABORT, Values.mkSort(m, Op.ABORT), "a signal given as the level is passed on");
    }

    // ---- CONST ----------------------------------------------------------------------------------------------

    @Test
    void constantHeadsCarryClassFlagsHintAndNatOp() {
        int nat = konst("Nat");
        assertEquals(Layout.HC_IND, Values.hclass(m, nat));
        assertEquals(Layout.FL_WHNF, flag(nat, Layout.FL_WHNF | Layout.FL_ACTIVE | Layout.FL_OPEN));
        int zero = konst("Nat.zero");
        assertEquals(Layout.HC_CTOR, Values.hclass(m, zero));
        assertEquals(Layout.FL_WHNF, flag(zero, Layout.FL_WHNF | Layout.FL_ACTIVE));
        assertEquals(0, Values.natOp(m, zero));
        int succ = konst("Nat.succ");
        assertEquals(NatOp.SUCC, Values.natOp(m, succ));
        int add = konst("Nat.add");
        assertEquals(Layout.HC_DEFN, Values.hclass(m, add));
        assertEquals(Layout.FL_ACTIVE, flag(add, Layout.FL_WHNF | Layout.FL_ACTIVE));
        assertEquals(NatOp.ADD, Values.natOp(m, add));
        assertEquals(Layout.HINT_REGULAR, Values.hintClass(m, add));
        assertEquals(NatOp.SHIFT_RIGHT, Values.natOp(m, konst("Nat.shiftRight")));
        assertEquals(NatOp.BLE, Values.natOp(m, konst("Nat.ble")));
        int ble = konst("Nat.ble");
        assertEquals(Layout.HC_AXIOM, Values.hclass(m, ble), "the fixture's stand-in is an opaque constant ...");
        assertEquals(Layout.FL_ACTIVE, flag(ble, Layout.FL_WHNF | Layout.FL_ACTIVE),
                "... but reduce_nat matches the name before delta (R02 7.6): a nat-op head is never flagged WHNF");
        assertEquals(Layout.FL_ACTIVE,
                flag(app(m, ble, Layout.imm(1), Layout.imm(2)), Layout.FL_WHNF | Layout.FL_ACTIVE));
        assertEquals(Layout.FL_WHNF, flag(konst("Nat.succ"), Layout.FL_WHNF | Layout.FL_ACTIVE), "Nat.succ is a constructor");
        assertEquals(Layout.HINT_ABBREV, Values.hintClass(m, konst("id", "1")));
        int thm = konst("pThm");
        assertEquals(Layout.HC_DEFN, Values.hclass(m, thm), "a theorem unfolds in whnf (R02 7.6)");
        assertEquals(Layout.HINT_OPAQUE, Values.hintClass(m, thm), "and counts as opaque in lazy delta (R02 8.4)");
        assertEquals(Layout.FL_ACTIVE, flag(thm, Layout.FL_WHNF | Layout.FL_ACTIVE));
        int ax = konst("f");
        assertEquals(Layout.HC_AXIOM, Values.hclass(m, ax));
        assertEquals(Layout.FL_WHNF, flag(ax, Layout.FL_WHNF | Layout.FL_ACTIVE));
        assertEquals(Layout.HC_AXIOM, Values.hclass(m, konst("opaqueTwo")));
        assertEquals(Layout.FL_WHNF, flag(konst("opaqueTwo"), Layout.FL_WHNF | Layout.FL_ACTIVE));
        int rec = konst("Nat.rec", "1");
        assertEquals(Layout.HC_REC, Values.hclass(m, rec));
        assertEquals(Layout.FL_ACTIVE, flag(rec, Layout.FL_WHNF | Layout.FL_ACTIVE));
        assertEquals(Layout.HC_AXIOM, Values.hclass(m, konst("Quot", "1")));
        assertEquals(Layout.HC_AXIOM, Values.hclass(m, konst("Quot.mk", "1")));
        assertEquals(Layout.FL_WHNF, flag(konst("Quot.mk", "1"), Layout.FL_WHNF | Layout.FL_ACTIVE));
        assertEquals(Layout.HC_QUOT, Values.hclass(m, konst("Quot.lift", "1", "1")));
        assertEquals(Layout.HC_QUOT, Values.hclass(m, konst("Quot.ind", "1")));
        assertEquals(Layout.FL_ACTIVE, flag(konst("Quot.ind", "1"), Layout.FL_WHNF | Layout.FL_ACTIVE));
        // identity: (row, levels); the record words
        assertEquals(nat, konst("Nat"));
        assertEquals(f.cid("Nat"), Values.a(m, nat));
        assertEquals(IntLists.EMPTY, Values.b(m, nat));
        assertEquals(0, Levels.substOf(m, nat));
        assertNotEquals(konst("id", "1"), konst("id", "2"));
        assertEquals(konst("id", "1"), konst("id", "1"));
        assertEquals(Layout.CONST, Values.kind(m, nat));
    }

    @Test
    void aConstantWithTheWrongNumberOfLevelsHasSubstitutionMinusOne() {
        int bad = konst("id"); // id.{u} without levels
        assertEquals(-1, Levels.substOf(m, bad));
        int bad2 = konst("Nat", "1");
        assertEquals(-1, Levels.substOf(m, bad2));
        assertEquals(0, Values.natOp(m, konst("Nat.succ", "1")), "the official test is on `Const Nat.succ []`");
        assertEquals(Op.ABORT, Levels.lsub(m, f.level("u"), -1));
        assertEquals(MachineErrors.E_LEVEL_ARITY, m.err);
        m.err = 0;
        assertEquals(f.level("1"), Levels.lsub(m, f.level("1"), 0));
    }

    @Test
    void anUnboundOrInvisibleConstantIsAFaultNeverAValue() {
        assertEquals(Op.FATAL, Values.mkConst(m, ~5, IntLists.EMPTY));
        assertEquals(MachineErrors.E_INTERNAL, m.err);
        assertEquals(Op.FATAL, Values.mkConst(m, 0, IntLists.EMPTY));
        m.err = 0;
        int saved = m.decl;
        try {
            m.decl = f.store.decls().rank(f.cid("four")); // `four` itself and everything after it is invisible
            int four = f.cid("four");
            assertEquals(Op.FATAL, Values.mkConst(m, four, IntLists.EMPTY));
            assertEquals(MachineErrors.E_VIS, m.err);
            assertTrue(Values.mkConst(m, f.cid("two"), IntLists.EMPTY) < Op.SIG);
        } finally {
            m.decl = saved;
            m.err = 0;
        }
    }

    // ---- applications ---------------------------------------------------------------------------------------

    @Test
    void applicationsAreOneRecordPerHeadAndArguments() {
        int g = konst("g");
        int n0 = konst("n0");
        int x = Values.mkFVar(m, konst("Nat"), 0);
        int gx = app(m, g, x, n0);
        assertEquals(Layout.NEU, Values.kind(m, gx));
        assertEquals(gx, app(m, g, x, n0), "two constructions, one id");
        assertEquals(gx, app(m, app(m, g, x), n0), "extending a NEU gives the application of the head to all arguments");
        assertNotEquals(gx, app(m, g, n0, x));
        assertNotEquals(app(m, g, x), gx);
        assertEquals(g, Values.a(m, gx));
        assertEquals(2, Values.aux(m, gx));
        assertEquals(g, Values.head(m, gx));
        int run = Values.b(m, gx);
        assertEquals(2, Runs.len(m, run));
        assertEquals(x, Runs.arg(m, run, 0));
        assertEquals(n0, Runs.arg(m, run, 1));
        assertEquals(Layout.RUN_SPINE, Layout.runKind(m.rs[run]));
        assertEquals(Hash.ofRecord(m.vh, gx), Values.c(m, gx), "a NEU stores its hash in word c");
        // flags: OPEN = or over head and arguments; WHNF / ACTIVE / HCLASS from the head
        assertEquals(Layout.FL_OPEN | Layout.FL_WHNF, flag(gx, Layout.FL_OPEN | Layout.FL_WHNF | Layout.FL_ACTIVE));
        assertEquals(Layout.HC_AXIOM, Values.hclass(m, gx));
        int closed = app(m, g, n0, n0);
        assertEquals(Layout.FL_WHNF, flag(closed, Layout.FL_OPEN | Layout.FL_WHNF | Layout.FL_ACTIVE));
        int add = app(m, konst("Nat.add"), Layout.imm(2), Layout.imm(3));
        assertEquals(Layout.FL_ACTIVE, flag(add, Layout.FL_OPEN | Layout.FL_WHNF | Layout.FL_ACTIVE), "no reduction at construction");
        assertEquals(Layout.HC_DEFN, Values.hclass(m, add));
        int xs = app(m, x, n0);
        assertEquals(Layout.HC_FVAR, Values.hclass(m, xs));
        assertEquals(Layout.FL_OPEN | Layout.FL_WHNF, flag(xs, Layout.FL_OPEN | Layout.FL_WHNF | Layout.FL_ACTIVE | Layout.FL_INSCOPE));
        // extension keeps the OPEN of the prefix
        assertTrue(Values.isOpen(m, app(m, app(m, g, x), n0)));
        assertTrue(Values.isOpen(m, app(m, app(m, g, n0), x)));
        assertFalse(Values.isOpen(m, app(m, app(m, g, n0), n0)));
        // an argument that is an open closure makes the application open
        int openFrame = frameOf(x);
        int lam = Values.mkLam(m, f.term("(fun (x : Nat) x)"), openFrame, 0);
        assertTrue(Values.isOpen(m, lam));
        assertTrue(Values.isOpen(m, app(m, g, lam, n0)));
        // n = 0: the head itself
        assertEquals(g, Values.mkApp(m, g, m.xp, 0));
        assertEquals(g, Values.mkNeu(m, g, m.xp, 0));
    }

    static int frameOf(int... slots) {
        return Runs.frame(m, args(m, slots), slots.length, 0);
    }

    @Test
    void illTypedHeadsAreFaults() {
        int n0 = konst("n0");
        assertEquals(Op.FATAL, app(m, Layout.imm(3), n0));
        assertEquals(MachineErrors.E_ILLTYPED, m.err);
        assertEquals(Op.FATAL, app(m, Values.mkSort(m, f.level("1")), n0));
        assertEquals(MachineErrors.E_ILLTYPED, m.err);
        assertEquals(Op.FATAL, app(m, Values.mkPi(m, f.term("(-> Nat Nat)"), 0, 0), n0));
        assertEquals(MachineErrors.E_ILLTYPED, m.err);
        assertEquals(Op.FATAL, app(m, Values.mkLam(m, f.term("(fun (x : Nat) x)"), 0, 0), n0));
        assertEquals(MachineErrors.E_INTERNAL, m.err, "a closure must be applied by APPLY, not folded");
        assertEquals(Op.FATAL, app(m, Layout.HOLE, n0));
        assertEquals(MachineErrors.E_INTERNAL, m.err);
        m.err = 0;
    }

    @Test
    void aSpineLongerThan65535ArgumentsIsALimit() {
        Machine t = new Machine();
        int ty = Values.mkSort(t, 3);
        int x = Values.mkFVar(t, ty, 0);
        int n = Op.SPINE_MAX;
        t.ensureScratch(n + 1);
        for (int i = 0; i <= n; i++) {
            t.X[i] = Layout.imm(i);
        }
        int big = Values.mkNeu(t, x, 0, n);
        assertTrue(big < Op.SIG);
        assertEquals(n, Values.aux(t, big));
        assertEquals(big, Values.mkNeu(t, x, 0, n));
        assertEquals(Op.ABORT, Values.mkNeu(t, x, 0, n + 1));
        assertEquals(MachineErrors.E_LIMIT, t.err);
        t.err = 0;
        assertEquals(Op.ABORT, Values.mkApp(t, big, 0, 1));
        assertEquals(MachineErrors.E_LIMIT, t.err);
    }

    // ---- Nat.succ rows (ISA 4.2 NSUCC, 2.1 canonical forms, RECONCILIATION section 2) ----------------------------

    @Test
    void natSuccRows() {
        int succ = konst("Nat.succ");
        int zero = konst("Nat.zero");
        int nat = konst("Nat");
        // Nat.succ Nat.zero is the literal 1
        assertEquals(Layout.imm(1), app(m, succ, zero));
        // Nat.succ of a literal is the literal + 1
        assertEquals(Layout.imm(8), app(m, succ, Layout.imm(7)));
        assertEquals(Layout.imm(1), Values.nsucc(m, Layout.imm(0)));
        // NBIG at 2^31
        int big = app(m, succ, Layout.imm(Integer.MAX_VALUE));
        assertEquals(Layout.NBIG, Values.kind(m, big));
        assertEquals(Layout.NBIG_INLINE, Values.aux(m, big));
        assertEquals(1L << 31, Lits.natHandle(m, big));
        assertEquals(big, Values.mkNat(m, 1L << 31));
        assertEquals(Values.mkNat(m, (1L << 31) + 1), Values.nsucc(m, big));
        assertEquals(Layout.FL_WHNF, Values.flags(m, big));
        // an open variable: one NSUC cell, WHNF because the base is a weak head normal form
        int x = Values.mkFVar(m, nat, 0);
        int sx = app(m, succ, x);
        assertEquals(Layout.NSUC, Values.kind(m, sx));
        assertEquals(x, Values.a(m, sx));
        assertEquals(1, Values.b(m, sx));
        assertEquals(Layout.FL_WHNF | Layout.FL_OPEN, Values.flags(m, sx));
        // Nat.succ^k of anything else is ONE cell
        int s3x = app(m, succ, app(m, succ, sx));
        assertEquals(Layout.NSUC, Values.kind(m, s3x));
        assertEquals(x, Values.a(m, s3x));
        assertEquals(3, Values.b(m, s3x));
        assertEquals(s3x, Values.nsucc(m, Values.nsucc(m, Values.nsucc(m, x))));
        assertEquals(Layout.FL_WHNF | Layout.FL_OPEN, Values.flags(m, s3x));
        // WHNF flag only on a normal base: `Nat.add n0 n0` may still reduce, so its successor is not flagged
        int sum = app(m, konst("Nat.add"), konst("n0"), konst("n0"));
        int ssum = app(m, succ, sum);
        assertEquals(Layout.NSUC, Values.kind(m, ssum));
        assertEquals(0, Values.flags(m, ssum), "base is not a weak head normal form: no WHNF flag; closed: no OPEN");
        assertEquals(0, Values.flags(m, Values.nsucc(m, ssum)));
        // a base that is an inert closed value
        int sn0 = app(m, succ, konst("n0"));
        assertEquals(Layout.FL_WHNF, Values.flags(m, sn0));
        // a suspension as base: not flagged
        int susp = Values.mkSusp(m, f.term("(Nat.add n0 two)"), 0, 0, false);
        assertEquals(Layout.SUSP, Values.kind(m, susp));
        assertEquals(0, Values.flags(m, Values.nsucc(m, susp)));
        // over-application is an ordinary (ill-typed) application, not a successor
        int two = app(m, succ, x, x);
        assertEquals(Layout.NEU, Values.kind(m, two));
        // Nat.succ with levels is not the special constant
        int weird = app(m, konst("Nat.succ", "1"), Layout.imm(1));
        assertEquals(Layout.NEU, Values.kind(m, weird));
        // Nat.zero itself is not rewritten
        assertEquals(Layout.CONST, Values.kind(m, zero));
    }

    /** NSUC views for IOTA and OFFSET (ISA 2.9, 4.4): predecessor, peeling, k successors at once. */
    @Test
    void successorViewsOfAnOffsetCell() {
        int succ = konst("Nat.succ");
        int zero = konst("Nat.zero");
        int x = Values.mkFVar(m, konst("Nat"), 0);
        int s1 = Values.nsucc(m, x);
        int s2 = Values.nsucc(m, s1);
        int s5 = Values.nsuccK(m, x, 5);
        assertEquals(Layout.NSUC, Values.kind(m, s5));
        assertEquals(5, Values.b(m, s5));
        assertEquals(s5, Values.nsucc(m, Values.nsucc(m, Values.nsucc(m, s2))), "k steps and one step of k: one id");
        assertEquals(s5, Values.nsuccK(m, s2, 3));
        assertEquals(x, Values.nsuccK(m, x, 0));
        assertEquals(s1, Values.nsuccK(m, x, 1));
        assertEquals(Layout.FL_WHNF | Layout.FL_OPEN, Values.flags(m, s5));
        // predecessor: NSUC(b, k) -> NSUC(b, k - 1), and the base itself at k = 1
        assertEquals(s1, Values.npred(m, s2));
        assertEquals(x, Values.npred(m, s1));
        assertEquals(s2, Values.npeel(m, s5, 3));
        assertEquals(x, Values.npeel(m, s5, 5));
        assertEquals(Op.FATAL, Values.npeel(m, s5, 6));
        assertEquals(Op.FATAL, Values.npeel(m, s5, 0));
        assertEquals(Op.FATAL, Values.npred(m, x));
        assertEquals(MachineErrors.E_INTERNAL, m.err);
        m.err = 0;
        // literals and Nat.zero
        assertEquals(Layout.imm(12), Values.nsuccK(m, Layout.imm(7), 5));
        assertEquals(Layout.imm(5), Values.nsuccK(m, zero, 5));
        assertEquals(Values.mkNat(m, (1L << 31) + 4), Values.nsuccK(m, Layout.imm(Integer.MAX_VALUE), 5));
        assertEquals(Values.mkNat(m, (1L << 40) + 9), Values.nsuccK(m, Values.mkNat(m, 1L << 40), 9));
        int over = Values.nsuccK(m, Values.mkNat(m, Long.MAX_VALUE - 1), 3);
        assertEquals(java.math.BigInteger.ONE.shiftLeft(63).add(java.math.BigInteger.ONE),
                rlvm.nat.Nat.toBigInteger(m.nats, Lits.natHandle(m, over)));
        // a closed, reducible base: no WHNF flag on any of its offsets
        int sum = app(m, konst("Nat.add"), konst("n0"), konst("two"));
        assertEquals(0, Values.flags(m, Values.nsuccK(m, sum, 4)));
        assertEquals(Values.nsuccK(m, sum, 4), Values.nsucc(m, Values.nsuccK(m, sum, 3)));
        assertEquals(app(m, succ, app(m, succ, sum)), Values.nsuccK(m, sum, 2));
    }

    @Test
    void natSuccCrossesIntoTheNatStoreAt2Pow63() {
        int top = Values.mkNat(m, Long.MAX_VALUE);
        assertEquals(Layout.NBIG_INLINE, Values.aux(m, top));
        int over = Values.nsucc(m, top);
        assertEquals(Layout.NBIG, Values.kind(m, over));
        assertEquals(Layout.NBIG_STORE, Values.aux(m, over));
        assertEquals(java.math.BigInteger.ONE.shiftLeft(63), rlvm.nat.Nat.toBigInteger(m.nats, Lits.natHandle(m, over)));
        assertEquals(over, Values.nsucc(m, top), "the NatStore interns by content: one id, one record");
        int over2 = Values.nsucc(m, over);
        assertNotEquals(over, over2);
        assertEquals(java.math.BigInteger.ONE.shiftLeft(63).add(java.math.BigInteger.ONE),
                rlvm.nat.Nat.toBigInteger(m.nats, Lits.natHandle(m, over2)));
    }

    @Test
    void natSuccFollowsThePreludeGuards() {
        Fixture g = Fixture.empty();
        g.inductive("Nat", "Nat.zero : Nat", "Nat.succ : (-> Nat Nat)");
        g.special(Specials.NAT, "Nat");
        g.special(Specials.NAT_ZERO, "Nat.zero");
        g.specialState(Specials.NAT_SUCC, "Nat.succ", Specials.INVALID);
        g.seal();
        int succ = Values.mkConst(g.m, g.cid("Nat.succ"), IntLists.EMPTY);
        assertEquals(NatOp.SUCC, Values.natOp(g.m, succ), "the code names the function; the guard is consulted at use");
        assertEquals(Op.FATAL, app(g.m, succ, Layout.imm(1)));
        assertEquals(MachineErrors.E_PRELUDE, g.m.err);
        g.close();

        Fixture h = Fixture.empty();
        h.inductive("Nat", "Nat.zero : Nat", "Nat.succ : (-> Nat Nat)");
        h.special(Specials.NAT, "Nat");
        h.special(Specials.NAT_SUCC, "Nat.succ");
        h.specialState(Specials.NAT_ZERO, "Nat.zero", Specials.INVALID);
        h.seal();
        int hs = Values.mkConst(h.m, h.cid("Nat.succ"), IntLists.EMPTY);
        int hz = Values.mkConst(h.m, h.cid("Nat.zero"), IntLists.EMPTY);
        assertEquals(Layout.imm(5), app(h.m, hs, Layout.imm(4)));
        assertEquals(Op.FATAL, app(h.m, hs, hz), "the literal view of Nat.zero needs its own guard");
        assertEquals(MachineErrors.E_PRELUDE, h.m.err);
        h.close();
    }

    // ---- projections, PEEK, suspensions --------------------------------------------------------------------

    @Test
    void projectionOfASyntacticConstructorIsItsField() {
        int mk = konst("Prod.mk", "0", "0");
        int nat = konst("Nat");
        int bool = konst("Bool");
        int n0 = konst("n0");
        int tt = konst("Bool.true");
        int pair = app(m, mk, nat, bool, n0, tt);
        int prod = f.cid("Prod");
        assertEquals(n0, Values.mkProj(m, prod, 0, pair), "field 0 after the 2 parameters");
        assertEquals(tt, Values.mkProj(m, prod, 1, pair));
        // not enough arguments: stuck
        int partial = app(m, mk, nat, bool, n0);
        int p1 = Values.mkProj(m, prod, 1, partial);
        assertEquals(Layout.PROJ, Values.kind(m, p1));
        assertEquals(n0, Values.mkProj(m, prod, 0, partial));
        // a field index beyond the constructor: stuck (never a read outside the spine)
        assertEquals(Layout.PROJ, Values.kind(m, Values.mkProj(m, prod, 2, pair)));
        // the struct is not reduced: a definition that unfolds to a constructor stays a PROJ cell
        int prDef = konst("prDef");
        int cell = Values.mkProj(m, prod, 0, prDef);
        assertEquals(Layout.PROJ, Values.kind(m, cell));
        assertEquals(cell, Values.mkProj(m, prod, 0, prDef));
        assertEquals(prDef, Values.a(m, cell));
        assertEquals(prod, Values.b(m, cell));
        assertEquals(0, Values.c(m, cell));
        assertNotEquals(cell, Values.mkProj(m, prod, 1, prDef));
        assertEquals(Layout.HC_PROJ, Values.hclass(m, cell));
        assertEquals(Layout.FL_ACTIVE, flag(cell, Layout.FL_ACTIVE | Layout.FL_WHNF | Layout.FL_OPEN));
        // a constructor of another type is not a field access
        int sig = app(m, konst("Sig.mk"), konst("B"), n0, konst("iu0"));
        assertEquals(Layout.PROJ, Values.kind(m, Values.mkProj(m, prod, 0, sig)));
        assertEquals(n0, Values.mkProj(m, f.cid("Sig"), 0, sig));
        // a PROJ is a head for outer applications
        int x = Values.mkFVar(m, nat, 0);
        int px = Values.mkProj(m, prod, 0, x);
        assertTrue(Values.isOpen(m, px));
        int papp = app(m, px, n0);
        assertEquals(Layout.NEU, Values.kind(m, papp));
        assertEquals(Layout.HC_PROJ, Values.hclass(m, papp));
        assertEquals(Layout.FL_ACTIVE | Layout.FL_OPEN, flag(papp, Layout.FL_ACTIVE | Layout.FL_WHNF | Layout.FL_OPEN));
        // a literal struct stays a cell (the constructor view is WHNF's business)
        assertEquals(Layout.PROJ, Values.kind(m, Values.mkProj(m, prod, 0, Layout.imm(3))));
    }

    @Test
    void suspensionsPeekAndTheLetFlag() {
        int t = f.term("(Nat.add n0 n0)");
        int s = Values.mkSusp(m, t, 0, 0, false);
        assertEquals(Layout.SUSP, Values.kind(m, s));
        assertEquals(0, Values.flags(m, s));
        assertEquals(s, Values.suspRecord(m, t, 0, 0));
        assertEquals(s, Values.peek(m, s), "no VAL yet");
        int let = Values.mkSusp(m, t, 0, 0, true);
        assertNotEquals(s, let, "the LET suspension is another record: LET is a function of identity (I-FLAG)");
        assertEquals(Layout.FL_LET | Layout.FL_OPEN, Values.flags(m, let), "LET sets OPEN: the official hasFVar of a let variable");
        assertEquals(let, Values.mkSusp(m, t, 0, 0, true));
        // VAL known: MKSUSP and PEEK give the VAL for the plain record, the LET record is kept
        int val = konst("n0");
        Values.setMemo(m, s, Layout.M_VAL - Layout.M0, val);
        Values.setMemo(m, let, Layout.M_VAL - Layout.M0, val);
        assertEquals(val, Values.mkSusp(m, t, 0, 0, false));
        assertEquals(val, Values.peek(m, s));
        assertEquals(s, Values.suspRecord(m, t, 0, 0), "the tabling record itself is still reachable");
        assertEquals(let, Values.mkSusp(m, t, 0, 0, true));
        assertEquals(let, Values.peek(m, let));
        // PEEK of everything else is the value itself
        assertEquals(val, Values.peek(m, val));
        assertEquals(Layout.imm(4), Values.peek(m, Layout.imm(4)));
        assertEquals(Layout.HOLE, Values.peek(m, Layout.HOLE));
        // a value built from a LET suspension is OPEN
        assertTrue(Values.isOpen(m, app(m, konst("g"), let, val)));
        assertTrue(Values.isOpen(m, Values.nsucc(m, let)));
        // mkProj peeks: the VAL of the plain suspension is what the cell holds
        int cell = Values.mkProj(m, f.cid("Prod"), 0, s);
        assertEquals(val, Values.a(m, cell));
    }

    // ---- reset ------------------------------------------------------------------------------------------------

    /**
     * ISA 1.6 "reset is O(used)": 100,000 sessions of one record each on a machine with the standard tables
     * (vtab 2 MiB, rtab 1 MiB, vh 8 MiB). A reset that cleared a table in full would move 300 GB here; the
     * bound is generous enough for a loaded host and far below that.
     */
    @Test
    void aSessionOfOneRecordResetsWithoutTouchingAFullTable() {
        Machine t = new Machine();
        int[] vtab = t.vtab;
        long t0 = System.nanoTime();
        long acc = 0;
        for (int i = 0; i < 100_000; i++) {
            int s = Values.mkSort(t, 1 + (i & 1023));
            int x = Values.mkFVar(t, s, 0);
            acc += x - s; // always 1: the session starts from the same state
            t.reset();
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertEquals(100_000, acc);
        assertTrue(vtab == t.vtab, "the table is reused");
        MachineStateTest.assertAllZero(t.vtab, "vtab");
        assertTrue(ms < 10_000, "100,000 one-record sessions took " + ms + " ms: reset is not O(used)");
    }

    /** ISA 1.6: a session of a few records resets without touching a full table, and the caches of Values are empty. */
    @Test
    void resetIsProportionalToWhatWasUsedAndClearsTheCaches() {
        Fixture g = Fixture.prelude0();
        Machine t = g.m;
        int[] vtab = t.vtab;
        int[] rtab = t.rtab;
        int nat = Values.mkConst(t, g.cid("Nat"), IntLists.EMPTY);
        int sort = Values.mkSort(t, g.level("1"));
        int x = Values.mkFVar(t, nat, 0);
        int fr = Runs.frame(t, args(t, x), 1, 0);
        int lam = Values.mkLam(t, g.term("(fun (x : Nat) x)"), fr, 0);
        int ap = app(t, Values.mkConst(t, g.cid("f"), IntLists.EMPTY), x);
        assertTrue(ap > lam && lam > x && x > sort && sort > nat);
        int records = t.vp - Layout.FIRST_RECORD;
        assertEquals(records, used(t.vtab));
        assertEquals(1, used(t.rtab), "one frame; the spine of the NEU is not in rtab");
        t.reset();
        assertTrue(vtab == t.vtab && rtab == t.rtab, "no table is replaced by a reset at the base size");
        MachineStateTest.assertAllZero(t.vtab, "vtab");
        MachineStateTest.assertAllZero(t.rtab, "rtab");
        assertEquals(Layout.FIRST_RECORD, t.vp);
        assertEquals(Layout.FIRST_RUN, t.rsp);
        // after the reset the caches must not answer with records of the dead session
        t.beginDecl(Integer.MAX_VALUE, -1, Op.FUEL_UNLIMITED, Long.MAX_VALUE);
        int sort2 = Values.mkSort(t, g.level("1"));
        assertEquals(Layout.FIRST_RECORD, sort2, "the sort cache was cleared: the record is made again");
        assertEquals(Layout.SORT, Values.kind(t, sort2));
        int nat2 = Values.mkConst(t, g.cid("Nat"), IntLists.EMPTY);
        assertEquals(Layout.FIRST_RECORD + 1, nat2, "CHEAD is stamped by the epoch");
        assertEquals(Layout.CONST, Values.kind(t, nat2));
        assertEquals(g.cid("Nat"), Values.a(t, nat2));
        assertEquals(2, used(t.vtab));
        g.close();
    }
}
