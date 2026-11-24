package rlvm.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import rlvm.api.Budget;
import rlvm.api.MachineErrors;
import rlvm.api.Outcome;

/**
 * B9, expected-type checking of lambdas and lets (ISA 4.5.2, 4.5.4, 6.3; staging step 1, switch
 * {@code expect}, off by default): the same verdict as the generic rule on every row, under both host depth
 * bounds, and the tabling record gets the lambda's own type (its PII), never the expected one.
 */
@Tag("fast")
class M5ExpectTest {

    @BeforeAll
    static void ready() {
        assumeTrue(M5Support.ready(), "EVAL / WHNF / CONV are not implemented in this tree yet");
    }

    static Fixture fixture(int depth, boolean expect) {
        Fixture f = Fixture.prelude();
        f.def("okLam", "", "(-> Nat Nat)", "(fun (x : Nat) (Nat.succ x))", Fixture.regular(1));
        f.def("okLamAlias", "", "FnAlias", "(fun (x : Nat) (Nat.succ x))", Fixture.regular(2));
        f.def("okLam3", "u", "(pi (α : (sort u)) (a : α) (b : α) α)", "(fun (α : (sort u)) (a : α) (b : α) a)", Fixture.regular(1));
        f.def("okLetLam", "", "(-> Nat Nat)", "(let (k : Nat := two) (fun (x : Nat) (Nat.add x k)))", Fixture.regular(2));
        f.def("okArg", "", "Nat", "((fun (h : (-> Nat Nat)) (h Nat.zero)) (fun (x : Nat) (Nat.succ x)))", Fixture.regular(2));
        f.thm("okThmLam", "", "(pi (n : Nat) ((@ Eq 1) Nat n n))", "(fun (n : Nat) ((@ Eq.refl 1) Nat n))");
        f.def("badLamBody", "", "(-> Nat Bool)", "(fun (x : Nat) x)", Fixture.regular(1));
        f.def("badLamDom", "", "(-> Bool Nat)", "(fun (x : Nat) x)", Fixture.regular(1));
        f.def("badLamDomType", "", "(-> Nat Nat)", "(fun (x : Nat.zero) Nat.zero)", Fixture.regular(1));
        f.def("badLamNotPi", "", "Nat", "(fun (x : Nat) x)", Fixture.regular(1));
        f.def("badLam3", "u", "(pi (α : (sort u)) (a : α) (b : α) α)", "(fun (α : (sort u)) (a : α) (b : Nat) a)", Fixture.regular(1));
        f.def("badLetVal", "", "(-> Nat Nat)", "(let (k : Nat := Bool.true) (fun (x : Nat) x))", Fixture.regular(1));
        f.def("badLetBody", "", "(-> Nat Bool)", "(let (k : Nat := two) (fun (x : Nat) k))", Fixture.regular(1));
        f.def("badArg", "", "Nat", "((fun (h : (-> Nat Nat)) (h Nat.zero)) (fun (x : Bool) Nat.zero))", Fixture.regular(2));
        f.thm("badThmLam", "", "(pi (n : Nat) ((@ Eq 1) Nat n (Nat.succ n)))", "(fun (n : Nat) ((@ Eq.refl 1) Nat n))");
        f.seal();
        f.hostDepth(depth);
        if (expect) {
            f.switches("expect");
        }
        return f;
    }

    static final String[] DECLS = {"okLam", "okLamAlias", "okLam3", "okLetLam", "okArg", "okThmLam", "badLamBody", "badLamDom",
        "badLamDomType", "badLamNotPi", "badLam3", "badLetVal", "badLetBody", "badArg", "badThmLam", "id", "Nat.add", "c1"};

    @Test
    void theSameVerdictAsTheGenericRule() {
        Budget budget = new Budget(50_000_000L, 1L << 30, Integer.MAX_VALUE);
        for (int depth : new int[] {Op.HOST_DEPTH, 0}) {
            Fixture generic = fixture(depth, false);
            Fixture expect = fixture(depth, true);
            for (String name : DECLS) {
                int a = generic.session.check(generic.cid(name), budget);
                int b = expect.session.check(expect.cid(name), budget);
                assertEquals(Outcome.name(a), Outcome.name(b), name + " (D = " + depth + "): " + expect.session.lastMessage());
                assertEquals(name.startsWith("bad") ? Outcome.REJECT : Outcome.ACCEPT, b, name);
                assertTrue(expect.m.idle(), name + ": the machine is idle");
            }
            generic.close();
            expect.close();
        }
    }

    @Test
    void lambdaAgainstPi() {
        for (int depth : new int[] {Op.HOST_DEPTH, 0}) {
            Fixture f = fixture(depth, true);
            int lam = f.term("(fun (x : Nat) (Nat.succ x))");
            f.check(lam, f.eval(f.term("(-> Nat Nat)")));
            // the tabling record holds the lambda's own type, a PII, with a check stamp; never the expected type
            int s = Infer.suspRec(f.m, lam, Layout.RUN_EMPTY, 0);
            int ty = Values.memo(f.m, s, Layout.M_TYPE - Layout.M0);
            assertEquals(Layout.PII, Values.kind(f.m, ty));
            assertEquals(Layout.TSTAMP_PERM, Values.memo(f.m, s, Layout.M_TSTAMP - Layout.M0));
            assertEquals(ty, f.inferCheck(lam), "INFER answers from the record CHECK filled");
            // a second CHECK against a convertible type is answered by CONV(TYPE(s), V)
            long ticks = f.m.ticks();
            f.check(lam, f.eval(f.term("(-> NatAlias Nat)")));
            f.check(lam, f.eval(f.term("FnAlias")));
            int wrong = f.eval(f.term("(-> Nat Bool)"));
            f.expectFail(MachineErrors.E_TYPE_MISMATCH, () -> f.check(lam, wrong));
            assertNotEquals(ticks, f.m.ticks());
            // the expected type needs WHNF to become a Pi
            f.check(f.term("(fun (y : Nat) y)"), f.eval(f.term("FnAlias")));
            // not a Pi after WHNF: the generic rule decides
            int nat = f.eval(f.term("Nat"));
            int lam2 = f.term("(fun (z : Nat) z)");
            f.expectFail(MachineErrors.E_TYPE_MISMATCH, () -> f.check(lam2, nat));
            // domain mismatch, body mismatch, ill-typed domain, ill-typed body
            int boolToNat = f.eval(f.term("(-> Bool Nat)"));
            int lam3 = f.term("(fun (w : Nat) w)");
            f.expectFail(MachineErrors.E_TYPE_MISMATCH, () -> f.check(lam3, boolToNat));
            int natToNat = f.eval(f.term("(-> Nat Nat)"));
            int lam4 = f.term("(fun (v : Nat.zero) Nat.zero)");
            f.expectFail(MachineErrors.E_TYPE_EXPECTED, () -> f.check(lam4, natToNat));
            int lam5 = f.term("(fun (v : Nat) (Nat.succ Bool.true))");
            f.expectFail(MachineErrors.E_APP_MISMATCH, () -> f.check(lam5, natToNat));
            assertTrue(f.m.idle());
            f.close();
        }
    }

    @Test
    void dependentTelescopeAndLet() {
        for (int depth : new int[] {Op.HOST_DEPTH, 0}) {
            Fixture f = fixture(depth, true);
            f.check(f.term("(fun (α : Type) (a : α) (b : α) a)"), f.eval(f.term("(pi (α : Type) (a : α) (b : α) α)")));
            f.check(f.term("(fun (n : Nat) ((@ Eq.refl 1) Nat n))"), f.eval(f.term("(pi (n : Nat) ((@ Eq 1) Nat n n))")));
            int bad = f.term("(fun (n : Nat) ((@ Eq.refl 1) Nat n))");
            int wrong = f.eval(f.term("(pi (n : Nat) ((@ Eq 1) Nat n Nat.zero))"));
            f.expectFail(MachineErrors.E_TYPE_MISMATCH, () -> f.check(bad, wrong));
            // let: the bindings are checked, the expected type goes to the body
            f.check(f.term("(let (k : Nat := two) (fun (x : Nat) (Nat.add x k)))"), f.eval(f.term("(-> Nat Nat)")));
            int badLet = f.term("(let (k : Nat := Bool.true) (fun (x : Nat) x))");
            int natToNat = f.eval(f.term("(-> Nat Nat)"));
            f.expectFail(MachineErrors.E_LET_MISMATCH, () -> f.check(badLet, natToNat));
            // a lambda argument of an application is checked against the binder's domain
            assertEquals(f.eval(f.term("Nat")), f.inferCheck(f.term("((fun (h : (-> Nat Nat)) (h Nat.zero)) (fun (x : Nat) (Nat.succ x)))")));
            int badArg = f.term("((fun (h : (-> Nat Nat)) (h Nat.zero)) (fun (x : Bool) Nat.zero))");
            f.expectFail(MachineErrors.E_TYPE_MISMATCH, () -> f.inferCheck(badArg));
            assertTrue(f.m.idle());
            f.close();
        }
    }
}
