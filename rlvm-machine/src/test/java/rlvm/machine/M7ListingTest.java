package rlvm.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import rlvm.api.LevelOps;
import rlvm.store.DeclKind;

/**
 * The listings of ISA 3.7, reproduced from the constants of {@code Init.Prelude} as exported
 * ({@code work/arena-site/tests/good/init-prelude.ndjson}). Where a line differs from the text of ISA 3.7
 * the difference is listed in NOTES-M7 ("Listings"): capture lists show every slot of the node's mask,
 * every entry of a sub-block is printed, TARG is three instructions, an active argument is a BKIND /
 * MKSUSP / MKAPP group (the K0-equivalent form), and positions whose sort has level parameters are PROOFL.
 * Not tagged fast (it loads an export: about one second).
 */
class M7ListingTest {

    private static final String PRELUDE = "work/arena-site/tests/good/init-prelude.ndjson";
    private static M7World w;

    @BeforeAll
    static void load() throws Exception {
        assumeTrue(Fixture.exists(PRELUDE));
        w = M7World.load(PRELUDE);
    }

    private static String listing(String name, int role) {
        return CodeShow.role(w.m, w.cid(name), role, false).trim();
    }

    @Test
    void bodyOfAProjectionFunction() {
        // ISA 3.7: BODY(HAdd.hAdd) arity 4, LEAF, shape PROJARG, unread {0,1,2}: MKPROJ r0, HAdd, 0, A3 ; RET r0
        assertEquals("BODY(HAdd.hAdd)  arity 4, LEAF, shape PROJARG, unread {0,1,2}\n   MKPROJ r0, HAdd, 0, A3 ; RET r0",
                listing("HAdd.hAdd", Op.ROLE_BODY));
    }

    @Test
    void bodyOfAnInstance() {
        // ISA 3.7: BODY(instHAdd) arity 2, LEAF: MKLAM r0, T(fun a b => ..), B1, [A0, A1] ; KCONST r1, HAdd.mk, {u_1,u_1,u_1}
        //          MKAPP r2, r1, A0, A0, A0, r0 ; RET r2;   B1 (2 lambdas applied): KCONST r0, Add.add, {u_1} ; MKAPP r1, r0, C0, C1, A0, A1 ; RET r1
        assertEquals("BODY(instHAdd)  arity 2, LEAF, shape CTORBODY\n"
                + "   MKLAM r0, T(fun), B1, [A0, A1] ; KCONST r1, HAdd.mk, {u_1,u_1,u_1} ; MKAPP r2, r1, A0, A0, A0, r0 ; RET r2\n"
                + "   B1 (1 applied):  MKLAM r0, T(fun), -, [C0, C1, A0] ; RET r0\n"
                + "   B1 (2 applied):  KCONST r0, Add.add, {u_1} ; MKAPP r1, r0, C0, C1, A0, A1 ; RET r1",
                listing("instHAdd", Op.ROLE_BODY));
    }

    @Test
    void bodyAndCaseTreeOfCasesOn() {
        // ISA 3.7: BODY(Nat.casesOn) arity 4: MKLAM r0, T(fun n ih => ..), B1, [A3] ; KCONST r1, Nat.rec, {u} ;
        //          MKAPP r2, r1, A0, A2, r0, A1 ; RET r2          (the closure also captures the motive: its second domain reads it)
        assertEquals("BODY(Nat.casesOn)  arity 4, LEAF\n"
                + "   MKLAM r0, T(fun), B1, [A0, _, _, A3] ; KCONST r1, Nat.rec, {u} ; MKAPP r2, r1, A0, A2, r0, A1 ; RET r2\n"
                + "   B1 (1 applied):  MKLAM r0, T(fun), -, [C0, _, _, C3, A0] ; RET r0\n"
                + "   B1 (2 applied):  TAILAPPLY C3, A0",
                listing("Nat.casesOn", Op.ROLE_BODY));
        // ISA 3.7: RULES(Nat.casesOn) shape CASESON: WHNF r0, A1 ; SWITCH r0, Lz, Ls, Lx
        //          Lz: RET A2    Ls: FIELDS r1, r0, 1 ; TAILAPPLY A3, r1    Lx: RET NONE
        assertEquals("RULES(Nat.casesOn)  arity 4, shape CASESON\n"
                + "   WHNF r0, A1 ; SWITCH r0, Nat, L1, L2, L3 ; L1: RET A2 ; L2: FIELDS r1, r0, 1 ; TAILAPPLY A3, r1 ; L3: RET NONE",
                listing("Nat.casesOn", Op.ROLE_RULES));
    }

    @Test
    void caseTreeOfAMatcher() {
        // ISA 3.6: Nat.add.match_1 is one level with two leaves; the motive and Nat.casesOn are never built
        assertEquals("RULES(Nat.add.match_1)  arity 5, shape MATCHER\n"
                + "   WHNF r0, A2 ; SWITCH r0, Nat, L1, L2, L3 ; L1: TAILAPPLY A3, A1 ; L2: FIELDS r1, r0, 1 ; "
                + "TAILAPPLY A4, A1, r1 ; L3: RET NONE",
                listing("Nat.add.match_1", Op.ROLE_RULES));
        // not case trees: a structural recursion through brecOn, a projection function, a K-like recursor
        assertEquals("", listing("Nat.add", Op.ROLE_RULES));
        assertEquals("", listing("HAdd.hAdd", Op.ROLE_RULES));
        assertEquals("", listing("Eq.casesOn", Op.ROLE_RULES));
    }

    @Test
    void rulesOfNatRec() {
        // ISA 3.7: RULE(Nat.rec, succ) arity 4: BUNREAD A2, 1, r0, L1 ; KCONST r1, Nat.rec, {u} ; MKAPP r0, r1, A0, A1, A2, A3
        //          L1: TAILAPPLY A2, A3, r0
        assertEquals("RULE(Nat.rec, Nat.zero)  arity 3\n   RET A1\n"
                + "RULE(Nat.rec, Nat.succ)  arity 4\n"
                + "   BUNREAD A2, 1/2, r1, L1 ; KCONST r0, Nat.rec, {u} ; MKAPP r1, r0, A0, A1, A2, A3 ; L1: TAILAPPLY A2, A3, r1",
                listing("Nat.rec", Op.ROLE_RULE));
    }

    @Test
    void telescopeOfCongrArg() {
        // ISA 3.7: TELE(congrArg) n 6, reads 0b011111: TARG 0, SORT u ; TARG 1, SORT v ; TARG 2, g0 ; TARG 3, g0 ;
        //   TARG 4, PI(T(α -> β), [g0, g1]) ; TARG 5, pattern Eq.{u} g0 g2 g3 ; APPLY r0, g4, g2 ; APPLY r1, g4, g3 ;
        //   KCONST r2, Eq, {v} ; MKAPP r3, r2, g1, r0, r1 ; TRET r3
        String t = listing("congrArg", Op.ROLE_TELE);
        String main = "TELE(congrArg)  n 6, reads 0b011111\n"
                + "   TARG 0 ; KSORT r8, u ; TCONV 0, r8 ; L1: TBIND 0 ; "
                + "L2: TARG 1 ; KSORT r9, v ; TCONV 1, r9 ; L3: TBIND 1 ; "
                + "L4: TARG 2 ; TCONV 2, g0 ; L5: TBIND 2 ; "
                + "L6: TARG 3 ; TCONV 3, g0 ; L7: TBIND 3 ; "
                + "L8: TARG 4 ; MKPI r10, T(pi), B1, [g0, g1, _, _] ; TCONV 4, r10 ; L9: TBIND 4 ; "
                + "L10: TARG 5 unread ; MATCH r7, T(app), L11 ; KCONST r11, Eq, {u} ; MKAPP r12, r11, g0, g2, g3 ; TCONV 5, r12 ; "
                + "L11: TBIND 5 ; "
                + "L12: TEXPECT T(app) ; BKIND g4, neutral, L13 ; MKSUSP r13, T(app), [g2, _, g4, _] ; JMP L14 ; "
                + "L13: MKAPP r13, g4, g2 ; L14: BKIND g4, neutral, L15 ; MKSUSP r14, T(app), [g3, g4, _] ; JMP L16 ; "
                + "L15: MKAPP r14, g4, g3 ; L16: KCONST r15, Eq, {v} ; MKAPP r16, r15, g1, r13, r14 ; TRET r16 ; ";
        assertTrue(t.startsWith(main), t);
        // partial applications return the remaining Pi over the telescope variables bound so far
        assertTrue(t.contains("MKPI r17, T(pi), -, [g0] ; TRET r17 ; MKPI r18, T(pi), -, [g0, g1] ; TRET r18 ; "
                + "MKPI r19, T(pi), -, [g0, g1, g2] ; TRET r19 ; MKPI r20, T(pi), -, [g0, g1, g2, g3] ; TRET r20 ; "
                + "MKPI r21, T(pi), -, [g0, g1, g2, g3, g4] ; TRET r21\n"), t);
        // the codomain of α -> β does not read its variable
        assertTrue(t.endsWith("\n   B1 (1 applied):  RET C1"), t);
        // the value of the theorem is a proof block: never elaborated (ISA 3.3)
        int c = w.cid("congrArg");
        assertEquals(DeclKind.THM, w.store.decls().kind(c));
        assertTrue(w.store.decls().value(c) < 0);
        assertEquals("", listing("congrArg", Op.ROLE_BODY));
        assertEquals(0, Elab.elaborate(w.m, c, Op.ROLE_BODY));
    }

    // ---- CONG from a fact record of the layout of ISA 3.5 (hand-made: the facts of M5 are staging step 2) ----

    /** A fact record: arity, lamArity, unread mask, the levels of the domains' sorts (0 = not manifest), dep masks. */
    private static int[] facts(int arity, int lamArity, long unread, int[] slvl, long[] dep) {
        int[] f = new int[Layout.F_SLVL + 3 * arity];
        f[Layout.F_ARITY] = arity << 16 | lamArity;
        f[Layout.F_UNREAD] = (int) unread;
        f[Layout.F_UNREAD + 1] = (int) (unread >>> 32);
        long manifest = 0;
        for (int i = 0; i < arity; i++) {
            if (slvl[i] != 0) {
                manifest |= 1L << i;
                f[Layout.F_SLVL + i] = Layout.LREF_BASE | slvl[i];
            }
            f[Layout.F_SLVL + arity + 2 * i] = (int) dep[i];
            f[Layout.F_SLVL + arity + 2 * i + 1] = (int) (dep[i] >>> 32);
        }
        f[Layout.F_MANIFEST] = (int) manifest;
        f[Layout.F_MANIFEST + 1] = (int) (manifest >>> 32);
        return f;
    }

    private static int param(String name) {
        byte[] b = name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return w.ctx.levels.param(w.store.names().findStr(0, b, 0, b.length));
    }

    private static String cong(String name, int[] f) {
        Elab.State s = new Elab.State();
        int len = Cong.build(w.m, w.cid(name), f, 0, 1, s);
        assertTrue(len > 0);
        assertEquals(0, Code.pubcheckSlow(w.m, s.buf, 0, len));
        int[] b = java.util.Arrays.copyOf(s.buf, len);
        int id = w.engine.code.add(b, len);
        return CodeShow.published(w.m, id, false).trim();
    }

    @Test
    void planOfCongrArg() {
        // ISA 3.7: CONG(congrArg) 6 positions: 0 TYPE dep {} ; 1 TYPE dep {} ; 2 SAME dep {0} ; 3 SAME dep {0} ;
        //          4 SAME dep {0,1} ; 5 PROOF dep {0,2,3}
        // slvl [u+1, v+1, u, u, imax u v, 0], all manifest. Positions 2-4 have a sort with level parameters that can
        // be zero (congrArg.{0,0} compares proofs there): class PROOFL of ISA 6.1, which the listing of 3.7 prints as SAME.
        LevelOps lv = w.ctx.levels;
        int u = param("u");
        int v = param("v");
        int[] slvl = {lv.succ(u), lv.succ(v), u, u, lv.imax(u, v), lv.zero()};
        long[] dep = {0, 0, 1, 1, 3, 0b1101};
        assertEquals("CONG(congrArg)  6 positions, lamArity 0\n"
                + "   0 TYPE dep {} rank 0 ; 1 TYPE dep {} rank 1 ; 2 PROOFL dep {0} rank 3 ; 3 PROOFL dep {0} rank 2 ; "
                + "4 PROOFL dep {0,1} rank 5 ; 5 PROOF dep {0,2,3} rank 4",
                cong("congrArg", facts(6, 0, 0, slvl, dep)));
    }

    @Test
    void planOfHAdd() {
        // ISA 3.7: CONG(HAdd.hAdd) 6 positions, lamArity 4, unread {0,1,2}: 0 TYPE / UNREAD ; 1 TYPE / UNREAD ;
        //          2 TYPE / UNREAD ; 3 SAME dep {0,1,2} ; 4 SAME dep {0} ; 5 SAME dep {1}
        LevelOps lv = w.ctx.levels;
        int u = param("u");
        int v = param("v");
        int x = param("w");
        int[] slvl = {lv.succ(lv.succ(u)), lv.succ(lv.succ(v)), lv.succ(lv.succ(x)), lv.succ(lv.max(lv.max(u, v), x)),
            lv.succ(u), lv.succ(v)};
        long[] dep = {0, 0, 0, 0b111, 0b001, 0b010};
        assertEquals("CONG(HAdd.hAdd)  6 positions, lamArity 4, PROP_STATIC\n"
                + "   0 TYPE / UNREAD dep {} rank 1 ; 1 TYPE / UNREAD dep {} rank 0 ; 2 TYPE / UNREAD dep {} rank 2 ; "
                + "3 SAME dep {0,1,2} rank 5 ; 4 SAME dep {0} rank 4 ; 5 SAME dep {1} rank 3",
                cong("HAdd.hAdd", facts(6, 4, 0b0111, slvl, dep)));
    }

    /** The plan of a constant from the fact record that SIGD (M5) derives and publishes (ISA 3.5, 6.1). */
    private static String realPlan(String name) {
        Machine m = w.m;
        int sw = m.sw;
        m.sw |= Op.SW_FACTS;
        try {
            int row = w.cid(name);
            int fi = m.drive(Facts.sigd(m, row, 0));
            assertTrue(fi > 0 && fi < Op.SIG, "SIGD(" + name + ") = " + fi + " err " + m.err);
            int plan = Cong.planOf(m, row);
            assertTrue(plan > 0, "no plan for " + name);
            assertEquals(plan, Cong.planOf(m, row), "published once");
            assertEquals(plan, w.engine.code.blockOf(row, Op.ROLE_CONG));
            assertEquals(0, Code.pubcheckSlow(m, w.engine.code.chunkOf(plan), w.engine.code.offsetOf(plan), w.engine.code.lengthOf(plan)));
            return CodeShow.published(m, plan, false).trim();
        } finally {
            m.sw = sw;
        }
    }

    @Test
    void plansFromTheDerivedFacts() {
        // the same plans as the hand-made records above, now from FACTS(c) as derived by SIGD
        assertEquals("CONG(congrArg)  6 positions, lamArity 0\n"
                + "   0 TYPE dep {} rank 0 ; 1 TYPE dep {} rank 1 ; 2 PROOFL dep {0} rank 3 ; 3 PROOFL dep {0} rank 2 ; "
                + "4 PROOFL dep {0,1} rank 5 ; 5 PROOF dep {0,2,3} rank 4", realPlan("congrArg"));
        assertEquals("CONG(HAdd.hAdd)  6 positions, lamArity 4, PROP_STATIC\n"
                + "   0 TYPE / UNREAD dep {} rank 1 ; 1 TYPE / UNREAD dep {} rank 0 ; 2 TYPE / UNREAD dep {} rank 2 ; "
                + "3 SAME dep {0,1,2} rank 5 ; 4 SAME dep {0} rank 4 ; 5 SAME dep {1} rank 3", realPlan("HAdd.hAdd"));
        // without the switch there is no plan: every position is SAME (v0, strict mode)
        assertEquals(0, Cong.planOf(w.m, w.cid("Eq.refl")));
    }

    @Test
    void planWithoutManifestSortsComparesEverything() {
        // no manifest sort: no proof position; the syntactic rule still finds the type positions (ISA 6.1 TYPE)
        long[] dep = {0, 0, 1, 1, 3, 0b1101};
        String p = cong("congrArg", facts(6, 0, 0, new int[6], dep));
        assertTrue(p.contains("0 TYPE dep {}") && p.contains("1 TYPE dep {}") && p.contains("5 SAME dep {0,2,3}"), p);
        assertTrue(p.contains("PROP_STATIC"), p);
        // F_NONE: no plan at all
        int[] f = facts(6, 0, 0, new int[6], dep);
        f[Layout.F_FLAGS] = Layout.F_NONE;
        assertEquals(0, Cong.build(w.m, w.cid("congrArg"), f, 0, 1, new Elab.State()));
    }
}
