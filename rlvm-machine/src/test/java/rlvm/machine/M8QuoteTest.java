package rlvm.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import rlvm.api.MachineErrors;
import rlvm.store.IntLists;

/**
 * {@link Quote}: the term each record kind stands for (ISA section 2), row by row. The records are made
 * directly in the frozen layout of {@link Layout} (the reference probe {@code Machine.internRecord} and
 * the run helpers of {@link TestTables}), so that the readback is tested independently of the
 * constructors and judgments of the other packages; {@code M8QuoteMachineTest} repeats it on values the
 * machine made itself.
 */
@Tag("fast")
class M8QuoteTest {

    private final Fixture f = Fixture.prelude0();
    private final Machine m = f.m;

    private int rec(int kind, int flags, int aux, int a, int b, int c) {
        int r = m.internRecord(Layout.w0(kind, flags, aux), a, b, c);
        assertTrue(r < Op.SIG);
        return r;
    }

    private int constant(String name) {
        return rec(Layout.CONST, Layout.FL_WHNF, 0, f.cid(name), IntLists.EMPTY, 0);
    }

    private int neu(int head, int... args) {
        return rec(Layout.NEU, Layout.FL_WHNF, args.length, head, TestTables.spineRun(m, args), 12345);
    }

    private int frame(int... slots) {
        return TestTables.internRun(m, Layout.RUN_FRAME, 0, slots);
    }

    private void same(String expected, int value) {
        int t = f.quote(value);
        assertEquals(expected, f.showTerm(t));
        assertEquals(f.term(expected), t, "the readback is the hash-consed overlay term");
    }

    @Test
    void literalsSortsAndConstants() {
        same("(lit 5)", Layout.imm(5));
        same("(lit 0)", Layout.imm(0));
        same("(lit 2147483647)", Layout.imm(Integer.MAX_VALUE));
        long big = (1L << 40) + 7;
        same("(lit " + big + ")", rec(Layout.NBIG, Layout.FL_WHNF, Layout.NBIG_INLINE, (int) big, (int) (big >>> 32), 0));
        long top = Long.MAX_VALUE;
        same("(lit " + top + ")", rec(Layout.NBIG, Layout.FL_WHNF, Layout.NBIG_INLINE, (int) top, (int) (top >>> 32), 0));
        same("Type", rec(Layout.SORT, Layout.FL_WHNF, 0, f.level("1"), 0, 0));
        same("Prop", rec(Layout.SORT, Layout.FL_WHNF, 0, f.level("0"), 0, 0));
        same("Nat.zero", constant("Nat.zero"));
        same("(@ Eq 1)", rec(Layout.CONST, Layout.FL_WHNF, 0, f.cid("Eq"), f.levels("1"), 0));
        int abc = f.term("(str \"abc\")");
        same("(str \"abc\")", rec(Layout.STR, Layout.FL_WHNF, 0, f.ctx.terms.a(abc), 0, 0));
    }

    @Test
    void applicationsProjectionsAndSuccessorCells() {
        same("(Nat.add Nat.zero (lit 2))", neu(constant("Nat.add"), constant("Nat.zero"), Layout.imm(2)));
        same("(g (f n0) n0)", neu(constant("g"), neu(constant("f"), constant("n0")), constant("n0")));
        same("(proj Prod 0 pr)", rec(Layout.PROJ, 0, 0, constant("pr"), f.cid("Prod"), 0));
        same("(proj Prod 1 pr)", rec(Layout.PROJ, 0, 0, constant("pr"), f.cid("Prod"), 1));
        // a projection as the head of an application (ISA 2.2 PROJ)
        same("((proj Prod 0 pr) n0)", neu(rec(Layout.PROJ, 0, 0, constant("pr"), f.cid("Prod"), 0), constant("n0")));
        int fn0 = neu(constant("f"), constant("n0"));
        same("(Nat.succ (f n0))", rec(Layout.NSUC, 0, 0, fn0, 1, 0));
        same("(Nat.succ (Nat.succ (Nat.succ (f n0))))", rec(Layout.NSUC, 0, 0, fn0, 3, 0));
        Quote.QuoteFailure e = assertThrows(Quote.QuoteFailure.class, () -> f.quote(rec(Layout.NSUC, 0, 0, fn0, Quote.NSUC_MAX + 1, 0)));
        assertEquals(MachineErrors.E_LIMIT, e.code);
    }

    @Test
    void freeVariablesAreTheLooseVariablesOfTheirBinders() {
        int nat = constant("Nat");
        int x = Scope.open(m, nat);
        same("#0", x);
        int y = Scope.open(m, nat);
        assertNotEquals(x, y);
        same("#0", y);
        same("#1", x);
        same("(g #1 #0)", neu(constant("g"), x, y));
        Scope.close(m, 1);
        // the same value one binder further out: the memo is per depth
        same("(g #0 #0)", neu(constant("g"), x, x));
        same("#0", x);
        // a variable that is not in scope is an error (I-FV3), unless it was given a local term
        Quote.QuoteFailure e = assertThrows(Quote.QuoteFailure.class, () -> f.quote(y));
        assertTrue(e.getMessage().contains("not bound by an open binder"), e.getMessage());
        Quote q = Quote.of(m);
        int n0 = f.term("n0");
        q.local(y, n0);
        assertEquals(n0, q.quote(y));
        assertEquals(f.term("(g #0 n0)"), q.quote(neu(constant("g"), x, y)));
        q.local(x, f.term("(f n0)"));
        assertEquals(f.term("(g (f n0) n0)"), q.quote(neu(constant("g"), x, y)));
        q.clearLocals();
        same("#0", x);
        Scope.close(m, 1);
    }

    @Test
    void closuresAndSuspensionsAreReadBackBySubstitution() {
        int a = constant("n0");
        int b = neu(constant("f"), constant("n0"));
        // index 0 is the innermost binding = the LAST slot of the frame (ISA 2.4 FGET)
        int lam = f.term("(fun (z : Nat) (g #1 #2))");
        same("(fun (_ : Nat) (g (f n0) n0))", rec(Layout.LAM, Layout.FL_WHNF, 0, lam, frame(a, b), 0));
        same("(fun (_ : Nat) (g n0 (f n0)))", rec(Layout.LAM, Layout.FL_WHNF, 0, lam, frame(b, a), 0));
        int pi = f.term("(pi (z : Nat) ((@ Eq 1) Nat #0 #1))");
        same("(pi (_ : Nat) ((@ Eq 1) Nat #0 n0))", rec(Layout.PI, Layout.FL_WHNF, 0, pi, frame(a), 0));
        same("(g (f n0) n0)", rec(Layout.SUSP, 0, 0, f.term("(g #0 #1)"), frame(a, b), 0));
        // a closed term under the empty frame is itself
        int closed = f.term("(fun (z : Nat) (Nat.succ z))");
        assertEquals(closed, f.quote(rec(Layout.LAM, Layout.FL_WHNF, 0, closed, Layout.RUN_EMPTY, 0)));
        // a captured free variable under the closure's own binder is lifted
        int x = Scope.open(m, constant("Nat"));
        same("(fun (_ : Nat) (g #1 #0))", rec(Layout.LAM, Layout.FL_WHNF | Layout.FL_OPEN, 0, f.term("(fun (z : Nat) (g #1 #0))"), frame(x), 0));
        Scope.close(m, 1);
    }

    @Test
    void anUnreadHoleIsFineAndAReadHoleIsAnError() {
        int a = constant("n0");
        // the term reads index 0 only; slot of index 1 is HOLE (PRUNE by the node mask, ISA 2.4, B6)
        same("(f n0)", rec(Layout.SUSP, 0, 0, f.term("(f #0)"), frame(Layout.HOLE, a), 0));
        Quote.QuoteFailure e = assertThrows(Quote.QuoteFailure.class,
                () -> f.quote(rec(Layout.SUSP, 0, 0, f.term("(g #0 #1)"), frame(Layout.HOLE, a), 0)));
        assertEquals(MachineErrors.E_HOLE, e.code);
        assertTrue(e.getMessage().contains("HOLE"), e.getMessage());
    }

    @Test
    void levelParametersAreInstantiatedByTheSubstitution() {
        // id.{u} at u := 1 (ISA 2.7: ls is mkSubst(lparams(row), levels))
        int lparams = f.store.decls().levelParams(f.cid("id"));
        int ls = f.ctx.levels.mkSubst(lparams, f.levels("1"));
        assertTrue(ls > 0);
        same("Type", rec(Layout.SUSP, 0, 0, f.term("(sort u)"), Layout.RUN_EMPTY, ls));
        same("(pi (_ : Type) (pi (_ : #0) #1))", rec(Layout.PI, Layout.FL_WHNF, 0, f.term("(pi (α : (sort u)) (a : α) α)"), Layout.RUN_EMPTY, ls));
        // c1.{u,v} and c2.{v,u} share a body term; at the same level list they are different values (2.7)
        int body = f.term("(-> (sort u) (sort v))");
        int ls1 = f.ctx.levels.mkSubst(f.store.decls().levelParams(f.cid("c1")), f.levels("1", "2"));
        int ls2 = f.ctx.levels.mkSubst(f.store.decls().levelParams(f.cid("c2")), f.levels("1", "2"));
        same("(pi (_ : Type) (sort 2))", rec(Layout.PI, Layout.FL_WHNF, 0, body, Layout.RUN_EMPTY, ls1));
        same("(pi (_ : (sort 2)) Type)", rec(Layout.PI, Layout.FL_WHNF, 0, body, Layout.RUN_EMPTY, ls2));
    }

    @Test
    void theSubstitutionOfAClosureDoesNotReachIntoItsBindings() {
        // id.{u} called at u := succ u from a declaration that has a parameter u of its own: the closure's term
        // is the callee's (its u is instantiated), the captured value is the caller's (its u stays). Found by the
        // sampler on bugs/large-elim-prop-bool (Bool.casesOn.{u+1} (fun _ => Sort u) ..).
        int lparams = f.store.decls().levelParams(f.cid("id"));
        int ls = f.ctx.levels.mkSubst(lparams, f.levels("(succ u)"));
        int callersSort = rec(Layout.SORT, Layout.FL_WHNF, 0, f.level("u"), 0, 0);
        same("(fun (_ : (sort (succ u))) (sort u))", rec(Layout.LAM, Layout.FL_WHNF, 0, f.term("(fun (z : (sort u)) #1)"), frame(callersSort), ls));
        same("(pi (_ : (sort (succ u))) (sort u))", rec(Layout.PI, Layout.FL_WHNF, 0, f.term("(pi (z : (sort u)) #1)"), frame(callersSort), ls));
        same("(g (sort (succ u)) (sort u))", rec(Layout.SUSP, 0, 0, f.term("(g (sort u) #0)"), frame(callersSort), ls));
    }

    @Test
    void theMemoDiesWithTheSession() {
        int v = neu(constant("f"), constant("n0"));
        int t = f.quote(v);
        assertEquals(t, f.quote(v));
        f.session.reset();
        f.ctx.reset();
        // the same record index now holds another value
        int w = neu(constant("g"), constant("n0"), constant("n0"));
        assertEquals("(g n0 n0)", f.showTerm(f.quote(w)));
        assertThrows(Quote.QuoteFailure.class, () -> f.quote(m.vp + 5));
        assertThrows(Quote.QuoteFailure.class, () -> f.quote(Layout.NONE));
    }
}
