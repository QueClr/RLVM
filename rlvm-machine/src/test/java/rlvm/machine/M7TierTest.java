package rlvm.machine;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Tier equivalence K0 = K1 (ISA section 11; decision 6): the code stepper against the generic judgments,
 * role by role ({@link M7Tier}), with the default host depth bound and with D = 0 (every sub-judgment of
 * an activation is a suspension: every ACT frame and every {@code Code.resume} continuation runs).
 */
class M7TierTest {

    private static String run(M7Tier t, int n, String title) {
        t.assertIdle("at the start");
        t.bodies(1, n);
        t.assertIdle("after BODY");
        String s = t.summary(title + " BODY") + "\n";
        t.rules(1, n);
        t.assertIdle("after RULE");
        s += t.summary(title + " +RULE") + "\n";
        t.closures();
        t.assertIdle("after the closures");
        s += t.summary(title + " +closures") + "\n";
        t.caseTrees(1, n);
        t.assertIdle("after RULES");
        s += t.summary(title + " +RULES") + " (trees fired " + t.fired + ", declined " + t.declined + ")\n";
        t.telescopes(1, n);
        t.assertIdle("after TELE");
        s += t.summary(title + " +TELE") + " (MATCH " + t.matchHits + " of " + t.matchSites + " own values; " + t.foreignHits
                + " of " + t.foreign + " foreign values matched, each confirmed by CONV; expected-type shortcut on " + t.expectCases
                + " saturated sites)\n";
        return s;
    }

    /**
     * Definitions that exercise every case of the elaboration scheme (ISA 3.4), so that the fast group notices
     * when the generic EVAL / ARGV of M2 and the elaborator stop building the same values: let chains (bound
     * values that are atoms, inert applications, closures, active terms; an unread let), static beta with read
     * and unread binders and over-application, projections of variables and of redexes, variable heads in
     * argument and in tail position, closed arguments of every kind, nested binders that capture.
     */
    private static void shapes(Fixture f) {
        String nn = "(-> Nat Nat)";
        f.def("k1.let", "", nn, "(fun (n : Nat) (let (x : Nat := (Nat.succ n)) (let (g : (-> Nat Nat) := (fun (y : Nat) (Nat.add y x)))"
                + " (let (u : Nat := (g n)) (let (w : Nat := (lit 7)) (Nat.add (g x) (g n)))))))", Fixture.regular(3));
        f.def("k1.letClosed", "", nn, "(fun (n : Nat) (let (c : Nat := (Nat.add (lit 2) (lit 3))) (let (h : (-> Nat Nat) := (fun (y : Nat) y))"
                + " (Nat.add c (h n)))))", Fixture.regular(3));
        f.def("k1.beta", "", nn, "(fun (n : Nat) ((fun (a : Nat) (b : Nat) (c : Nat) (Nat.add a c)) (Nat.succ n) (Nat.add n n) n))",
                Fixture.regular(3));
        f.def("k1.betaOver", "", nn, "(fun (n : Nat) ((fun (a : Nat) (fun (b : Nat) (Nat.add a b))) (Nat.succ n) n))", Fixture.regular(3));
        f.def("k1.betaUnder", "", "(-> Nat Nat Nat)", "(fun (n : Nat) ((fun (a : Nat) (b : Nat) (Nat.add a b)) (Nat.succ n)))",
                Fixture.regular(3));
        f.def("k1.proj", "", "(-> ((@ Prod 0 0) Nat Bool) Nat)", "(fun (p : ((@ Prod 0 0) Nat Bool)) (Nat.succ (proj Prod 0 p)))",
                Fixture.regular(3));
        f.def("k1.projRedex", "", nn, "(fun (n : Nat) (proj Prod 0 ((@ Prod.mk 0 0) Nat Bool (Nat.succ n) Bool.true)))",
                Fixture.regular(3));
        f.def("k1.projArg", "", "(-> ((@ Prod 0 0) Nat Bool) Nat)", "(fun (p : ((@ Prod 0 0) Nat Bool)) (Nat.add (proj Prod 0 p) (lit 1)))",
                Fixture.regular(3));
        f.def("k1.varHead", "", "(-> (-> Nat Nat) Nat Nat)", "(fun (h : (-> Nat Nat)) (n : Nat) (Nat.add (h n) (h (h (Nat.succ n)))))",
                Fixture.regular(3));
        f.def("k1.varTail", "", "(-> (-> Nat Nat Nat) Nat Nat)", "(fun (h : (-> Nat Nat Nat)) (n : Nat) (h (Nat.add n n) (h n n)))",
                Fixture.regular(3));
        f.def("k1.closedArgs", "", nn, "(fun (n : Nat) (g ((fun (z : Nat) z) (lit 3)) (Nat.add (Nat.add n (lit 4)) (f (f n0)))))",
                Fixture.regular(3));
        f.def("k1.nested", "", "(-> Nat Nat Nat Nat)", "(fun (a : Nat) (b : Nat) (fun (c : Nat) ((fun (d : Nat) (fun (e : Nat) (g a (g c e)))) b c)))",
                Fixture.regular(3));
        f.def("k1.capture", "", "(-> Nat (-> Nat (-> Nat Nat)))", "(fun (a : Nat) (fun (b : Nat) (fun (c : Nat) (Nat.add a c))))",
                Fixture.regular(3));
        f.def("k1.piBody", "u", "(-> (sort u) (sort (imax u u)))", "(fun (α : (sort u)) (pi (x : α) (y : α) α))", Fixture.regular(3));
        f.def("k1.strLit", "", "(-> Nat String)", "(fun (n : Nat) (str \"ab\"))", Fixture.regular(3));
        f.def("k1.match", "u", "(pi (motive : (-> Nat (sort u))) (x : Nat) (z : (motive Nat.zero)) (s : (pi (k : Nat) (motive (Nat.succ k))))"
                + " (motive x))",
                "(fun (motive : (-> Nat (sort u))) (x : Nat) (z : (motive Nat.zero)) (s : (pi (k : Nat) (motive (Nat.succ k))))"
                        + " ((@ Nat.rec u) motive z (fun (k : Nat) (ih : (motive k)) (s k)) x))", Fixture.ABBREV);
    }

    @Test
    @Tag("fast")
    void handBuiltPrelude() {
        for (int depth : new int[] {Op.HOST_DEPTH, 0}) {
            Fixture f = Fixture.prelude();
            shapes(f);
            f.seal();
            f.m.hostDepth = depth;
            M7Tier t = new M7Tier(f.m);
            String s = run(t, f.store.decls().constantCount(), "prelude D=" + depth);
            System.out.print(s);
            assertTrue(t.clean(), s + t.failures);
            assertTrue(t.identical > 500, s);
            assertTrue(t.fired > 0, "the case tree of k1.match fired: " + s);
            assertTrue(t.matchHits == t.matchSites, s + t.failures);
        }
    }

    @Test
    void initPrelude() throws Exception {
        String path = "work/arena-site/tests/good/init-prelude.ndjson";
        assumeTrue(Fixture.exists(path));
        StringBuilder report = new StringBuilder();
        for (int depth : new int[] {Op.HOST_DEPTH, 0}) {
            M7World w = M7World.load(path);
            w.m.hostDepth = depth;
            M7Tier t = new M7Tier(w.m);
            String s = run(t, w.store.decls().constantCount(), "init-prelude D=" + depth);
            System.out.print(s);
            System.out.print(t.both);
            System.out.print(t.peekNotes);
            report.append(s);
            assertTrue(t.clean(), s + t.failures);
            assertTrue(t.matchHits * 10 >= t.matchSites * 9, s + t.failures);
        }
        java.nio.file.Files.writeString(Fixture.root().resolve("work/status/machine-M7-tier-init-prelude.txt"), report.toString());
    }
}
