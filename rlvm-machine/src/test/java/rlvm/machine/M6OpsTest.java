package rlvm.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;
import rlvm.api.EngineConfig;
import rlvm.api.KernelException;
import rlvm.api.KernelOps;
import rlvm.api.Reason;
import rlvm.ref.RefKernel;
import rlvm.store.IntLists;
import rlvm.store.Kind;

/**
 * {@link MachineOps} (ISA 14.6): the machine as {@code KernelOps}, compared with the reference kernel on
 * the same worker context. Term answers are compared up to the reference kernel's {@code isDefEq}
 * ({@code whnfIsSyntactic()} is false). Not in the fast group: it needs the judgments of M2-M5 and M8's
 * {@code Quote}; a row whose judgment is still a stub is skipped (assumption), never reported as passed.
 */
class M6OpsTest {

    private static final String[] TERMS = {
        "Nat.zero",
        "(Nat.succ Nat.zero)",
        "(Nat.add (Nat.succ Nat.zero) (lit 2))",
        "(fun (x : Nat) (Nat.succ x))",
        "(pi (x : Nat) Nat)",
        "Nat",
        "Prop",
        "(sort 3)",
        "(lit 7)",
        "((fun (x : Nat) (Nat.succ x)) (lit 4))",
        "(let (y : Nat := (lit 3)) (Nat.succ y))",
        "two",
        "((@ id 1) Nat)",
        "(fun (A : Type) (a : A) a)",
    };

    private static boolean ready(Runnable probe) {
        try {
            probe.run();
            return true;
        } catch (UnsupportedOperationException e) {
            return false; // a judgment (or Quote) is still a stub
        }
    }

    @Test
    void inferCheckWhnfAndTheSortQuestionsAgreeWithTheReferenceKernel() {
        Fixture f = Fixture.prelude0();
        MachineOps mo = new MachineOps(f.m, Quote::quote);
        KernelOps rk = new RefKernel(f.ctx, EngineConfig.defaults());
        mo.begin(Integer.MAX_VALUE, IntLists.EMPTY);
        rk.begin(Integer.MAX_VALUE, IntLists.EMPTY);
        assertFalse(mo.whnfIsSyntactic());
        assertTrue(mo.terms() == f.ctx.terms && mo.levels() == f.ctx.levels);
        assumeTrue(ready(() -> mo.infer(f.term("Nat.zero"))), "INFER or Quote is a stub");
        int compared = 0;
        for (String text : TERMS) {
            int t = f.term(text);
            int ty = mo.infer(t);
            assertTrue(rk.isDefEq(ty, rk.infer(t)), () -> "infer " + text + " = " + f.showTerm(ty));
            int cty = mo.check(t);
            assertTrue(rk.isDefEq(cty, rk.check(t)), () -> "check " + text + " = " + f.showTerm(cty));
            int w = mo.whnf(t);
            assertTrue(rk.isDefEq(w, t), () -> "whnf " + text + " = " + f.showTerm(w));
            int wc = mo.whnfCore(t);
            assertTrue(rk.isDefEq(wc, t), () -> "whnfCore " + text + " = " + f.showTerm(wc));
            // the type of a term is a type: its sort level is the reference kernel's, up to equivalence
            int lvl = mo.ensureSort(mo.infer(ty));
            assertTrue(f.ctx.levels.isEquiv(lvl, rk.ensureSort(rk.infer(rk.infer(t)))), () -> "sort of the type of " + text);
            assertEquals(rk.isProp(rk.infer(t)), mo.isProp(ty), () -> "isProp of the type of " + text);
            compared++;
        }
        assertEquals(TERMS.length, compared);
        // whnf really reduces: the head of (Nat.add 1 2) is a literal or Nat.succ, never Nat.add
        int sum = mo.whnf(f.term("(Nat.add (Nat.succ Nat.zero) (lit 2))"));
        assertTrue(f.ctx.terms.kind(sum) == Kind.NATLIT || f.showTerm(sum).startsWith("(Nat.succ"), f.showTerm(sum));
        // ensurePi
        int pi = mo.ensurePi(mo.infer(f.term("(fun (x : Nat) (Nat.succ x))")));
        assertEquals(Kind.PI, f.ctx.terms.kind(pi));
        assertTrue(rk.isDefEq(pi, f.term("(pi (x : Nat) Nat)")));
        // kernel errors surface as KernelException with the reason of ISA 14.3
        KernelException e = assertThrows(KernelException.class, () -> mo.ensureSort(f.term("Nat.zero")));
        assertEquals(Reason.TYPE_EXPECTED, e.reason());
        e = assertThrows(KernelException.class, () -> mo.ensurePi(f.term("Nat")));
        assertEquals(Reason.FUNCTION_EXPECTED, e.reason());
        e = assertThrows(KernelException.class, () -> mo.check(f.term("(Nat.zero Nat.zero)")));
        assertEquals(Reason.FUNCTION_EXPECTED, e.reason());
        assertTrue(f.m.idle(), "after a kernel error the machine stacks are restored");
        assertEquals(f.ctx.terms.kind(mo.infer(f.term("Nat.zero"))), Kind.CONST, "and the next judgment runs");
        assertThrows(UnsupportedOperationException.class, () -> mo.exposeUnitMembers(1));
        assertTrue(mo.steps() > 0);
        f.close();
    }

    @Test
    void localsAreMachineVariablesAndStayOpenUntilTheNextBegin() {
        Fixture f = Fixture.prelude0();
        MachineOps mo = new MachineOps(f.m, Quote::quote);
        KernelOps rk = new RefKernel(f.ctx, EngineConfig.defaults());
        mo.begin(Integer.MAX_VALUE, IntLists.EMPTY);
        rk.begin(Integer.MAX_VALUE, IntLists.EMPTY);
        assumeTrue(ready(() -> mo.mkLocal(f.term("Nat"))), "EVAL is a stub");
        mo.begin(Integer.MAX_VALUE, IntLists.EMPTY);
        assertEquals(0, f.m.ssp);
        int x = mo.mkLocal(f.term("Nat"));
        int y = mo.mkLocal(f.term("Nat"));
        assertEquals(Kind.FVAR, f.ctx.terms.kind(x));
        assertEquals(2, f.m.ssp, "two machine binders are open");
        assertTrue(mo.variableOf(x) != 0 && mo.variableOf(x) != mo.variableOf(y));
        assertTrue(Scope.inScope(f.m, mo.variableOf(y)));
        f.bind("x", x);
        f.bind("y", y);
        assumeTrue(ready(() -> mo.infer(f.term("(Nat.add $x $y)"))), "INFER or Quote is a stub");
        int t = f.term("(Nat.add $x (Nat.succ $y))");
        int ty = mo.infer(t);
        assertTrue(rk.isDefEq(ty, f.term("Nat")), () -> f.showTerm(ty));
        // a value that mentions the locals is read back over the same FVAR nodes
        int w = mo.whnf(t);
        assertTrue(rk.isDefEq(w, t), () -> f.showTerm(w));
        int lam = mo.whnf(f.term("(fun (z : Nat) (Nat.add $x z))"));
        assertTrue(rk.isDefEq(lam, f.term("(fun (z : Nat) (Nat.add $x z))")), () -> f.showTerm(lam));
        // a local whose type mentions an earlier local
        int p = mo.mkLocal(f.term("(Eq.{1} Nat $x $y)".replace("Eq.{1}", "(@ Eq 1)")));
        f.bind("p", p);
        assertEquals(3, f.m.ssp);
        int pty = mo.infer(f.term("$p"));
        assertTrue(rk.isDefEq(pty, f.term("((@ Eq 1) Nat $x $y)")), () -> f.showTerm(pty));
        assertTrue(mo.isProp(pty));
        // begin closes all locals
        mo.begin(Integer.MAX_VALUE, IntLists.EMPTY);
        assertEquals(0, f.m.ssp);
        assertEquals(0, mo.variableOf(x));
        f.close();
    }

    @Test
    void isDefEqIsTheExactYesNo() {
        Fixture f = Fixture.prelude0();
        MachineOps mo = new MachineOps(f.m, Quote::quote);
        KernelOps rk = new RefKernel(f.ctx, EngineConfig.defaults());
        mo.begin(Integer.MAX_VALUE, IntLists.EMPTY);
        rk.begin(Integer.MAX_VALUE, IntLists.EMPTY);
        assumeTrue(ready(() -> mo.isDefEq(f.term("(Nat.add (lit 1) (lit 2))"), f.term("(lit 3)"))), "CONV is a stub");
        String[][] pairs = {
            {"(Nat.add (lit 1) (lit 2))", "(lit 3)"},
            {"(Nat.succ (Nat.succ Nat.zero))", "(lit 2)"},
            {"two", "(lit 2)"},
            {"(lit 2)", "(lit 3)"},
            {"Nat", "Prop"},
            {"(fun (x : Nat) (Nat.succ x))", "Nat.succ"},
            {"(fun (x : Nat) x)", "(fun (x : Nat) (Nat.succ x))"},
            {"((fun (x : Nat) (Nat.succ x)) (lit 4))", "(lit 5)"},
            {"(pi (x : Nat) Nat)", "(-> Nat Nat)"},
        };
        for (String[] p : pairs) {
            int a = f.term(p[0]);
            int b = f.term(p[1]);
            assertEquals(rk.isDefEq(a, b), mo.isDefEq(a, b), () -> p[0] + " =?= " + p[1]);
            assertEquals(rk.isDefEq(b, a), mo.isDefEq(b, a), () -> p[1] + " =?= " + p[0]);
        }
        f.close();
    }
}
