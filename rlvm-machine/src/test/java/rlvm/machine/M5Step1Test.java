package rlvm.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import rlvm.api.Budget;
import rlvm.api.Outcome;

/**
 * The inference shortcuts of wave 6 (NOTES-M5, sections "MATCH", "PCHK", "Joint telescope", "Front cache"), all
 * off by default: {@code m5a} = MATCH of a telescope domain against the argument's type instead of building
 * the domain (ISA 4.4.4, 4.5.4, B5), {@code pchk} = shared check facts (ISA 5.1, 5.3), {@code m5b} = the joint
 * telescope check of a declaration's type and value (ISA 4.5.2, R07 1.8), and INFER's front cache (system
 * property {@code rlvm.machine.m5ic}). Every row has the verdict of the generic rule under every switch set
 * and both host depth bounds, and each shortcut is seen to fire.
 */
@Tag("fast")
class M5Step1Test {

    @BeforeAll
    static void ready() {
        assumeTrue(M5Support.ready(), "EVAL / WHNF / CONV are not implemented in this tree yet");
    }

    static final String EQ_NN = "((@ Eq 1) Nat n n)";
    static final String REFL_N0 = "((@ Eq.refl 1) Nat n0)";

    static Fixture fixture(int depth, String switches) {
        Fixture f = Fixture.prelude();
        // dependent telescopes whose domains are patterns over earlier binders
        f.axiom("dep", "", "(pi (α : Type) (a : α) (b : α) (h : ((@ Eq 1) α a b)) Nat)");
        f.axiom("depFn", "", "(pi (α : Type) (k : (-> α Nat)) (a : α) (h : ((@ Eq 1) Nat (k a) (k a))) Nat)");
        f.axiom("depPi", "", "(pi (α : Type) (k : (-> α α)) (h : (-> α α)) α)");
        f.axiom("depLvl", "u", "(pi (α : (sort u)) (a : α) (h : ((@ Eq u) α a a)) Nat)");
        f.def("okDep", "", "Nat", "(dep Nat n0 n0 " + REFL_N0 + ")", Fixture.regular(1));
        f.def("okDepAlias", "", "Nat", "(dep NatAlias n0 n0 " + REFL_N0 + ")", Fixture.regular(2));
        f.def("okDepEqn", "", "Nat", "(dep Nat n0 (f n0) eqn2)", Fixture.regular(1));
        f.def("okDepTwo", "", "Nat", "(dep Nat two (Nat.succ (Nat.succ Nat.zero)) ((@ Eq.refl 1) Nat two))", Fixture.regular(2));
        f.def("okDepFn", "", "Nat", "(depFn Nat f n0 ((@ Eq.refl 1) Nat (f n0)))", Fixture.regular(1));
        f.def("okDepPi", "", "Nat", "(depPi Nat f (fun (x : Nat) x))", Fixture.regular(1));
        f.def("okDepLvl", "", "Nat", "((@ depLvl 1) Nat n0 " + REFL_N0 + ")", Fixture.regular(1));
        f.def("okNested", "", "Nat", "(dep Nat (dep Nat n0 n0 " + REFL_N0 + ") (dep Nat n0 n0 " + REFL_N0
                + ") ((@ Eq.refl 1) Nat (dep Nat n0 n0 " + REFL_N0 + ")))", Fixture.regular(1));
        f.def("badDep", "", "Nat", "(dep Nat n0 (f n0) " + REFL_N0 + ")", Fixture.regular(1));
        f.def("badDepEqn", "", "Nat", "(dep Nat (f n0) n0 eqn2)", Fixture.regular(1));
        f.def("badDepFn", "", "Nat", "(depFn Nat f n0 " + REFL_N0 + ")", Fixture.regular(1));
        f.def("badDepType", "", "Nat", "(dep Bool n0 n0 " + REFL_N0 + ")", Fixture.regular(1));
        f.def("badDepLvl", "", "Nat", "((@ depLvl 2) Nat n0 " + REFL_N0 + ")", Fixture.regular(1));
        f.def("badNested", "", "Nat", "(dep Nat (dep Nat n0 n0 " + REFL_N0 + ") (dep Nat n0 (f n0) " + REFL_N0
                + ") ((@ Eq.refl 1) Nat (dep Nat n0 n0 " + REFL_N0 + ")))", Fixture.regular(1));
        // type and value with a common telescope
        f.def("okJoint3", "u", "(pi (α : (sort u)) (a : α) (b : α) α)", "(fun (α : (sort u)) (a : α) (b : α) a)", Fixture.regular(1));
        f.thm("okJointThm", "", "(pi (n : Nat) " + EQ_NN + ")", "(fun (n : Nat) ((@ Eq.refl 1) Nat n))");
        f.def("okJointFewer", "", "(pi (a : Nat) (b : Nat) Nat)", "(fun (a : Nat) (g a))", Fixture.regular(1));
        f.def("okJointMore", "", "(pi (a : Nat) FnAlias)", "(fun (a : Nat) (b : Nat) (g a b))", Fixture.regular(2));
        f.def("okJointLet", "", "(pi (a : Nat) Nat)", "(fun (a : Nat) (let (k : Nat := two) (Nat.add a k)))", Fixture.regular(2));
        f.def("okJointDep", "", "(pi (a : Nat) (h : ((@ Eq 1) Nat a a)) Nat)",
                "(fun (a : Nat) (h : ((@ Eq 1) Nat a a)) (dep Nat a a h))", Fixture.regular(1));
        f.thm("badJointBody", "", "(pi (n : Nat) ((@ Eq 1) Nat n (Nat.succ n)))", "(fun (n : Nat) ((@ Eq.refl 1) Nat n))");
        f.thm("badJointNotProp", "", "(pi (n : Nat) Nat)", "(fun (n : Nat) n)");
        f.def("badJointDomType", "", "(pi (x : Nat.zero) Nat)", "(fun (x : Nat.zero) Nat.zero)", Fixture.regular(1));
        f.def("badJointDom", "", "(pi (a : Nat) Nat)", "(fun (a : Bool) Nat.zero)", Fixture.regular(1));
        f.def("badJointSecond", "u", "(pi (α : (sort u)) (a : α) (b : α) α)", "(fun (α : (sort u)) (a : α) (b : Nat) a)", Fixture.regular(1));
        f.def("badJointDep", "", "(pi (a : Nat) (h : ((@ Eq 1) Nat a a)) Nat)",
                "(fun (a : Nat) (h : ((@ Eq 1) Nat a a)) (dep Nat a (f a) h))", Fixture.regular(1));
        // the same closed terms again, in later declarations (shared check facts)
        // (the hand-built store does not hash-cons: only defShared gives a later declaration the SAME node)
        f.defShared("okAgain", "", "Nat", "okDep", Fixture.regular(1));
        f.defShared("okAgainNested", "", "Nat", "okNested", Fixture.regular(1));
        f.defShared("badAgain", "", "Nat", "badDep", Fixture.regular(1));
        f.defShared("badAgainNested", "", "Nat", "badNested", Fixture.regular(1));
        f.defShared("badAgainType", "", "Bool", "okDep", Fixture.regular(1));
        f.seal();
        f.hostDepth(depth);
        f.switches(switches);
        f.m.stats = true;
        return f;
    }

    static final String[] DECLS = {"okDep", "okDepAlias", "okDepEqn", "okDepTwo", "okDepFn", "okDepPi", "okDepLvl", "okNested",
        "badDep", "badDepEqn", "badDepFn", "badDepType", "badDepLvl", "badNested", "okJoint3", "okJointThm", "okJointFewer",
        "okJointMore", "okJointLet", "okJointDep", "badJointBody", "badJointNotProp", "badJointDomType", "badJointDom",
        "badJointSecond", "badJointDep", "okAgain", "okAgainNested", "badAgain", "badAgainNested", "badAgainType", "id", "Nat.add", "c1", "four"};

    static final String[] SWITCH_SETS = {"m5a", "m5b", "pchk", "m5a,m5b,pchk", "expect,m5a,m5b,pchk", "gated,expect,m5a,m5b,pchk",
        "warm,gated,expect,m5a,m5b,pchk", "warm,gated,expect,probes,facts,m5a,m5b,pchk"};

    /** Outcomes of DECLS in order, as one string. */
    static String outcomes(Fixture f) {
        Budget budget = new Budget(50_000_000L, 1L << 30, Integer.MAX_VALUE);
        StringBuilder sb = new StringBuilder();
        for (String name : DECLS) {
            int o = f.session.check(f.cid(name), budget);
            assertEquals(name.startsWith("bad") ? Outcome.REJECT : Outcome.ACCEPT, o, name + " [" + f.session.lastMessage() + "]");
            assertTrue(f.m.idle(), name + ": the machine is idle");
            sb.append(name).append('=').append(Outcome.name(o)).append(' ');
        }
        return sb.toString();
    }

    @Test
    void theSameVerdictAsTheGenericRule() {
        for (int depth : new int[] {Op.HOST_DEPTH, 0}) {
            Fixture generic = fixture(depth, "");
            String expected = outcomes(generic);
            generic.close();
            for (String sw : SWITCH_SETS) {
                Fixture f = fixture(depth, sw);
                assertEquals(expected, outcomes(f), "switches " + sw + ", D = " + depth);
                f.close();
            }
        }
    }

    @Test
    void theShortcutsFire() {
        Fixture f = fixture(Op.HOST_DEPTH, "m5a");
        outcomes(f);
        assertTrue(f.m.x5.matchHit > 0, "MATCH answered a telescope domain");
        assertTrue(f.m.x5.matchTried > f.m.x5.matchHit, "and left some to the complete procedure");
        f.close();
        f = fixture(Op.HOST_DEPTH, "pchk"); // one session per declaration: only the shared bit carries over
        outcomes(f);
        assertTrue(f.m.x5.pchkSets > 0, "PCHK bits set");
        assertTrue(f.m.x5.pchkHits > 0, "PCHK answered a CHECKED inference in a later declaration");
        f.close();
    }

    /**
     * On a real export (the loader shares equal terms, so the Pi of a type and the lambda of its value have
     * the same domain node): the joint telescope fires and every switch set gives the verdict of v0.
     */
    @Test
    void initPrelude() throws java.io.IOException {
        String file = "work/arena-site/tests/good/init-prelude.ndjson";
        assumeTrue(Fixture.exists(file), "no arena tests in this tree");
        Fixture v0 = Fixture.load(file);
        Fixture.Verdict expected = v0.checkAll();
        v0.close();
        assertEquals(Outcome.ACCEPT, expected.outcome);
        for (String sw : new String[] {"m5b", "expect,m5b", "warm,gated,expect,pchk,m5a,m5b"}) {
            Fixture f = Fixture.load(file);
            f.switches(sw);
            f.m.stats = true;
            Fixture.Verdict v = f.checkAll();
            assertEquals(Outcome.name(expected.outcome), Outcome.name(v.outcome), "switches " + sw + ": " + v);
            assertEquals(0, f.session.declined, "switches " + sw);
            assertTrue(f.m.x5.jointBinders > 1000, "joint binders (switches " + sw + "): " + f.m.x5.jointBinders);
            if (sw.contains("pchk")) {
                assertTrue(f.m.x5.pchkHits > 0 && f.m.x5.matchHit > 0, "PCHK and MATCH fire on the prelude");
            }
            f.close();
        }
    }

    @Test
    void shortcutsAreAuditedByTheCompleteProcedure() {
        Fixture f = fixture(Op.HOST_DEPTH, "expect,m5a,m5b,pchk");
        f.debug("shortcheck");
        long audits = f.m.audits;
        outcomes(f);
        assertTrue(f.m.audits > audits, "shortcheck re-derived MATCH and PCHK answers");
        f.close();
    }

    @Test
    void frontCacheChangesNothing() {
        String key = "rlvm.machine.m5ic";
        String old = System.getProperty(key);
        try {
            for (String sw : new String[] {"", "warm,gated,expect", "warm,gated,expect,m5a,m5b,pchk"}) {
                System.setProperty(key, "false");
                Fixture off = fixture(Op.HOST_DEPTH, sw);
                String expected = outcomes(off);
                long ticks = off.m.ticks();
                off.close();
                System.setProperty(key, "true");
                Fixture on = fixture(Op.HOST_DEPTH, sw);
                assertEquals(expected, outcomes(on), "switches " + sw);
                assertEquals(ticks, on.m.ticks(), "the front cache changes no tick (switches " + sw + ")");
                if (sw.isEmpty()) { // v0 tables every node; the hand-built terms carry no sharing flags for the gate
                    assertTrue(on.m.x5.icHits > 0, "the front cache answered");
                }
                on.close();
            }
        } finally {
            if (old == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, old);
            }
        }
    }
}
