package rlvm.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.Arrays;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import rlvm.api.MachineErrors;

/**
 * Step 5 ({@code Op.SW_PROBES}, off by default): the speculative segments of conversion (ISA 6.2, 6.3): B12
 * closure congruence ({@code CCLO}), B1 same-head argument probe, B2, the quick refutation of pass A and
 * pass B of {@code CARGS}, and the discipline of {@code FAILED} / {@code UNDEC} (5.4): FAILED only for a
 * comparison that completed with F; a probe that ends in U leaves FAILED unset and is noted in UNDEC,
 * which only suppresses another probe. Owner: M4.
 *
 * <p>Three machines over the same prelude: P64 and P0 run with the switch under host depth bounds 64 and
 * 0 (every TRY segment of a row is suspended and resumed through its TRY frame under bound 0) and must
 * end in identical states; V runs v0 (the complete procedure) and gives the answer every probe row must
 * agree with.
 */
@Tag("fast")
@TestMethodOrder(MethodOrderer.MethodName.class)
class M4ProbeTest {

    private static final int T = Layout.T;
    private static final int F = Layout.F;
    private static final int U = Layout.U;

    static final class Fx {
        final Fixture f;
        final Machine m;
        final boolean probes;
        long[] calls;
        long[] probeCounts;
        int traceMark;

        Fx(int depth, boolean probes) {
            this.probes = probes;
            f = Fixture.prelude();
            f.def("const2", "(-> Nat Nat Nat)", "(fun (a : Nat) (b : Nat) a)", Fixture.regular(1));
            f.def("idn", "(-> Nat Nat)", "(fun (a : Nat) a)", Fixture.regular(3));
            f.def("idab", "(-> Nat Nat)", "(fun (a : Nat) a)", Fixture.ABBREV);
            f.axiom("h", "(-> Nat Nat Nat)");
            f.seal();
            m = f.m;
            f.hostDepth(depth);
            if (probes) {
                f.switches("probes");
            }
            m.stats = true;
            m.debug |= Op.DBG_TRACE;
            f.fvar("vx", "Nat");
            f.fvar("vy", "Nat");
        }

        int val(String text) {
            return f.eval(f.term(text));
        }

        void mark() {
            calls = m.calls.clone();
            probeCounts = m.probes.clone();
            traceMark = m.traceN;
        }

        int called(int op) {
            return (int) (m.calls[op] - calls[op]);
        }

        /** Segments of shortcut {@code b} (Op.B1 ..) that ended with {@code outcome} since the mark. */
        int probed(int b, int outcome) {
            return (int) (m.probes[3 * b + outcome] - probeCounts[3 * b + outcome]);
        }

        int after(int op) {
            for (int i = traceMark; i + 1 < m.traceN; i++) {
                if (m.trace[i] == op) {
                    return m.trace[i + 1];
                }
            }
            return -1;
        }
    }

    private static Fx[] fxs;
    private static boolean stubbed;

    /** P64, P0 (probes), V (v0). */
    private static Fx[] all() {
        if (fxs == null && !stubbed) {
            try {
                fxs = new Fx[] {new Fx(Op.HOST_DEPTH, true), new Fx(0, true), new Fx(Op.HOST_DEPTH, false)};
            } catch (UnsupportedOperationException e) {
                stubbed = true;
            }
        }
        assumeTrue(fxs != null, "a neighbour's judgment is still a stub");
        return fxs;
    }

    private static void guarded(Runnable body) {
        all();
        try {
            body.run();
        } catch (UnsupportedOperationException e) {
            stubbed = true;
            fxs = null;
            assumeTrue(false, "a neighbour's judgment is still a stub: " + e.getMessage());
        }
    }

    /**
     * An expensive comparison that succeeds: about 20 ticks per unit of n in the complete procedure. {@code k}
     * makes the pair unrelated to the pairs of other rows (no shared memo, no POS of an inner pair).
     */
    private static String[] slow(int n, int k) {
        return new String[] {"(Nat.add (f (lit " + k + ")) (lit " + n + "))",
            "(Nat.add (Nat.succ (f (lit " + k + "))) (lit " + (n - 1) + "))"};
    }

    @AfterAll
    static void theMachinesUnderBound64AndBound0AreIdentical() {
        if (fxs == null) {
            return;
        }
        Machine ma = fxs[0].m;
        Machine mb = fxs[1].m;
        assertNull(TierEq.compare(ma, mb), "records, memo words, runs and pairs under D = 64 and D = 0 with probes");
        assertEquals(ma.ticks(), mb.ticks(), "ticks: budgets are exact under every bound (H11)");
        assertNull(TierEq.traceDifference(Arrays.copyOf(ma.trace, ma.traceN), Arrays.copyOf(mb.trace, mb.traceN)), "call trace");
        assertTrue(mb.framesWritten > 100 && mb.framesWritten > 10 * ma.framesWritten,
                "D = 0 wrote " + mb.framesWritten + " frames, D = 64 " + ma.framesWritten);
        assertEquals(Arrays.toString(ma.probes), Arrays.toString(mb.probes), "probe outcomes");
    }

    // ---- B12: closure congruence ------------------------------------------------------------------------------

    @Test
    void b12_suspensionsOfOneTermAreComparedByTheirFramesWithoutForcing() {
        guarded(() -> {
            for (Fx x : all()) {
                Machine m = x.m;
                int lam = x.val("(fun (w : Nat) (f ((fun (z : Nat) (g z w)) n0)))");
                int a = x.f.apply(lam, x.val("two"));
                int b = x.f.apply(lam, x.val("(lit 2)"));
                int sa = Runs.arg(m, Values.b(m, a), 0);
                int sb = Runs.arg(m, Values.b(m, b), 0);
                assumeTrue(Values.kind(m, sa) == Layout.SUSP && Values.kind(m, sb) == Layout.SUSP,
                        "the redex argument is a suspension");
                assertTrue(Conv.sameClosure(m, sa, sb));
                x.mark();
                assertEquals(T, x.f.conv(a, b));
                if (x.probes) {
                    assertEquals(1, x.called(Op.CCLO));
                    assertEquals(1, x.probed(Op.B12, T));
                    assertEquals(0, Values.memo(m, sa, Layout.M_VAL - Layout.M0), "T without forcing the suspension");
                    assertEquals(Layout.POS, x.f.pair(sa, sb), "POS for the pair of suspensions");
                    // the same question again: answered from the table before any segment starts
                    x.mark();
                    assertEquals(T, x.f.conv(sa, sb));
                    assertEquals(0, x.called(Op.CCLO));
                } else {
                    assertEquals(0, x.called(Op.CCLO), "v0 has no B12");
                    assertTrue(Values.memo(m, sa, Layout.M_VAL - Layout.M0) != 0, "the complete procedure forces both");
                }
                // frames that are not convertible: the probe says F, which is not an answer; the complete procedure decides
                int c = x.f.apply(lam, x.val("(lit 3)"));
                x.mark();
                assertEquals(F, x.f.conv(a, c));
                if (x.probes) {
                    assertEquals(1, x.probed(Op.B12, F));
                }
            }
        });
    }

    @Test
    void b12_closuresOfOneTerm() {
        guarded(() -> {
            for (Fx x : all()) {
                int mk = x.val("(fun (w : Nat) (fun (y : Nat) (g y w)))");
                int a = x.f.apply(mk, x.val("twoAlias"));
                int b = x.f.apply(mk, x.val("(lit 2)"));
                assertEquals(Layout.LAM, Values.kind(x.m, a));
                x.mark();
                assertEquals(T, x.f.conv(a, b));
                assertEquals(x.probes ? 0 : 1, x.called(Op.CBIND), "with the probe the binder is never opened");
                assertEquals(Layout.POS, x.f.pair(a, b));
                int c = x.f.apply(mk, x.val("(lit 3)"));
                x.mark();
                assertEquals(F, x.f.conv(a, c));
                assertEquals(1, x.called(Op.CBIND), "F of the probe hands over to CBIND");
                assertEquals(0, x.f.pair(a, c), "NEG is not written for a lambda side");
                // Pi closures
                int mkPi = x.val("(fun (w : Nat) (pi (y : Nat) ((@ Eq 1) Nat y w)))");
                int pa = x.f.apply(mkPi, x.val("twoAlias"));
                int pb = x.f.apply(mkPi, x.val("(lit 2)"));
                assertEquals(Layout.PI, Values.kind(x.m, pa));
                x.mark();
                assertEquals(T, x.f.conv(pa, pb));
                assertEquals(x.probes ? 0 : 1, x.called(Op.CBIND));
                assertEquals(2, x.m.ssp, "no binder left open");
            }
        });
    }

    @Test
    void b12_aSegmentThatRunsOutOfBudgetIsNotAnAnswer() {
        guarded(() -> {
            String[] e = slow(130, 1001);
            for (Fx x : all()) {
                int mk = x.val("(fun (w : Nat) (fun (y : Nat) (g y w)))");
                int a = x.f.apply(mk, x.val(e[0]));
                int b = x.f.apply(mk, x.val(e[1]));
                long ticks = x.m.ticks();
                x.mark();
                assertEquals(T, x.f.conv(a, b), "the complete procedure decides after the U");
                if (x.probes) {
                    assertEquals(1, x.probed(Op.B12, U), "the slot comparison needs more than 1024 ticks");
                    assertEquals(1, x.called(Op.CBIND));
                } else {
                    assertTrue(x.m.ticks() - ticks > Op.B_CCLO, "the comparison is expensive: " + (x.m.ticks() - ticks) + " ticks");
                }
                assertEquals(Layout.POS, x.f.pair(a, b));
            }
        });
    }

    // ---- B1: the same-head argument probe -------------------------------------------------------------------------

    @Test
    void b1_sameHeadArgumentsBeforeAnythingElse() {
        guarded(() -> {
            for (Fx x : all()) {
                int a = x.val("(const2 (f two) n0)");
                int b = x.val("(const2 (f (lit 2)) n0)");
                x.mark();
                assertEquals(T, x.f.conv(a, b));
                assertEquals(Layout.POS, x.f.pair(a, b));
                if (x.probes) {
                    assertEquals(Op.CARGS, x.after(Op.CONV), "c1 comes before c2 .. c4");
                    assertEquals(1, x.probed(Op.B1, T));
                }
                // a recursor application with a literal major: the arguments are compared before iota runs
                int r1 = x.val("((@ Nat.rec 1) (fun (x : Nat) Nat) n0 (fun (x : Nat) (ih : Nat) (g ih two)) (lit 9))");
                int r2 = x.val("((@ Nat.rec 1) (fun (x : Nat) Nat) n0 (fun (x : Nat) (ih : Nat) (g ih (lit 2))) (lit 9))");
                x.mark();
                assertEquals(T, x.f.conv(r1, r2));
                if (x.probes) {
                    assertEquals(0, x.called(Op.IOTA), "T by congruence: the recursor never fires");
                    assertEquals(1, x.probed(Op.B1, T));
                } else {
                    assertTrue(x.called(Op.IOTA) >= 2, "the complete procedure reduces both sides");
                }
            }
        });
    }

    /** ISA 5.4: FAILED holds only same-head argument comparisons that completed with F. */
    @Test
    void b1_aCompletedFEntersFailed() {
        guarded(() -> {
            for (Fx x : all()) {
                int a = x.val("(idn (f (lit 81)))");
                int b = x.val("(idn (f (lit 82)))");
                x.mark();
                assertEquals(F, x.f.conv(a, b));
                assertEquals(Layout.NEG | Layout.FAILED, x.f.pair(a, b));
                if (x.probes) {
                    assertEquals(1, x.probed(Op.B1, F));
                }
                assertEquals(0, Pairs.undec(x.m, a, b));
            }
        });
    }

    /** ISA 5.4, section 11: a probe that ends in U must leave FAILED unset; UNDEC only suppresses another probe. */
    @Test
    void b1_aProbeThatEndsInULeavesFailedUnset() {
        guarded(() -> {
            String[] e = slow(140, 1002);
            for (Fx x : all()) {
                Machine m = x.m;
                // idab is an abbreviation: budget 1024; its argument comparison needs more
                int a = x.val("(idab " + e[0] + ")");
                int b = x.val("(idab " + e[1] + ")");
                x.mark();
                assertEquals(T, x.f.conv(a, b), "the complete procedure decides");
                assertEquals(Layout.POS, x.f.pair(a, b), "U never enters FAILED");
                if (x.probes) {
                    assertEquals(1, x.probed(Op.B1, U));
                    assertEquals(10, Pairs.undec(m, a, b), "log2 of the budget that ran out");
                } else {
                    assertEquals(0, Pairs.undec(m, a, b), "the complete procedure never writes UNDEC");
                }
                // the same with an answer F: no FAILED either (for an abbreviation the official kernel has no
                // same-head comparison at all); the probe is not repeated while UNDEC remembers it
                int c = x.val("(idab (Nat.add (f (lit 1005)) (lit 150)))");
                int d = x.val("(idab (Nat.add (Nat.succ (Nat.succ (f (lit 1005)))) (lit 149)))");
                x.mark();
                assertEquals(F, x.f.conv(c, d));
                assertEquals(Layout.NEG, x.f.pair(c, d));
                if (x.probes) {
                    assertEquals(1, x.probed(Op.B1, U));
                    assertEquals(10, Pairs.undec(m, c, d));
                    m.ER = 1; // NEG is not read under ER: the comparison runs again
                    try {
                        x.mark();
                        assertEquals(F, x.f.conv(c, d));
                        assertEquals(0, x.probed(Op.B1, U) + x.probed(Op.B1, F) + x.probed(Op.B1, T),
                                "UNDEC suppresses a second probe of the same size");
                    } finally {
                        m.ER = 0;
                    }
                    m.beginDecl(Integer.MAX_VALUE, -1, Op.FUEL_UNLIMITED, Long.MAX_VALUE);
                    x.f.closeContext();
                    x.f.fvar("vx", "Nat");
                    x.f.fvar("vy", "Nat");
                    assertEquals(0, Pairs.undec(m, c, d), "UNDEC lives for one declaration");
                }
            }
        });
    }

    @Test
    void b1_aKernelErrorInsideAProbeIsAUAndTheCompleteProcedureRaisesIt() {
        guarded(() -> {
            for (Fx x : all()) {
                int a = x.val("(idn (Nat.zero Nat.zero))");
                int b = x.val("(idn (Nat.zero n0))");
                x.mark();
                x.f.expectFail(MachineErrors.E_FUN_EXPECTED, () -> x.f.conv(a, b));
                assertEquals(0, x.f.pair(a, b), "nothing is recorded for the abandoned comparison");
                assertEquals(2, x.m.ssp);
                if (x.probes) {
                    assertEquals(1, x.probed(Op.B1, U), "inside the segment the error is a U (ISA 1.4)");
                }
            }
        });
    }

    // ---- CARGS: quick refutation and pass B ---------------------------------------------------------------------------

    @Test
    void cargs_passBFindsACheapRefutationBeforeAnExpensivePairCompletes() {
        guarded(() -> {
            String[] e = slow(160, 1003);
            long[] ticks = new long[3];
            int i = 0;
            for (Fx x : all()) {
                // official order (last to first): the expensive pair first, then the pair that refutes
                int a = x.val("(const2 (f (lit 91)) " + e[0] + ")");
                int b = x.val("(const2 (f (lit 92)) " + e[1] + ")");
                long t0 = x.m.ticks();
                assertEquals(F, x.f.conv(a, b));
                ticks[i++] = x.m.ticks() - t0;
                assertEquals(Layout.NEG | Layout.FAILED, x.f.pair(a, b));
                int ea = x.val(e[0]);
                int eb = x.val(e[1]);
                if (x.probes) {
                    assertEquals(0, x.f.pair(ea, eb) & (Layout.POS | Layout.NEG), "the expensive pair was never decided");
                    assertEquals(8, Pairs.undec(x.m, ea, eb), "its probe ended in U (budget 256)");
                } else {
                    assertTrue((x.f.pair(ea, eb) & Layout.POS) != 0, "the complete procedure compares it first, to the end");
                }
            }
            assertEquals(ticks[0], ticks[1], "ticks do not depend on the host depth bound");
            assertTrue(ticks[0] < 1200 && ticks[2] > 2500, "probes " + ticks[0] + " ticks, complete procedure " + ticks[2]);
        });
    }

    @Test
    void cargs_quickRefutationOfPassA() {
        guarded(() -> {
            String[] e = slow(170, 1004);
            for (Fx x : all()) {
                Machine m = x.m;
                // two literals of different content refute the spine before the expensive pair is touched
                int a = x.val("(h (lit 1) " + e[0] + ")");
                int b = x.val("(h (lit 2) " + e[1] + ")");
                long t0 = m.ticks();
                assertEquals(F, x.f.run(Cargs.cargs(m, a, b, Op.POL_SAMEDEF, 0)));
                if (x.probes) {
                    assertEquals(t0, m.ticks(), "inline: no tick");
                } else {
                    assertTrue(m.ticks() - t0 > 1000, "v0 compares in the official order");
                }
                // constructor applications with different constructors refute only when both are known not to be proofs
                int z = x.val("((@ List.nil 0) Nat)");
                int c = x.val("((@ List.cons 0) Nat n0 ((@ List.nil 0) Nat))");
                assertTrue(!Conv.refutes(m, z, c), "PROOF is unknown");
                assertEquals(F, x.f.conv(z, c));
                assertEquals(F, x.f.conv(c, x.val("((@ List.nil 0) Bool)")));
                assertTrue(Conv.refutes(m, z, c), "both sides were found not to be proofs (c4 memoised PROOF = 1)");
                assertTrue(!Conv.refutes(m, c, c));
                assertTrue(!Conv.refutes(m, x.val("p1"), x.val("p2")));
                assertTrue(Conv.refutes(m, x.val("(lit 4)"), x.val("(lit 5)")));
                assertTrue(!Conv.refutes(m, x.val("(lit 4)"), x.val("(f n0)")));
                // spines of different length
                int s1 = x.val("(h (f n0))");
                int s2 = x.val("(h (f n0) (f n0))");
                assertEquals(F, x.f.run(Cargs.cargs(m, s1, s2, Op.POL_SAMEDEF, 0)));
            }
        });
    }

    /**
     * NOTES-M4 D25: the budgets of segments that end in U accumulate as the declaration's probe debt; a T halves it;
     * at {@code Conv.Ext.DEBT_MAX} no further segment starts, so failing probes cost a bounded number of ticks.
     */
    @Test
    void debt_boundsWhatFailingProbesCost() {
        guarded(() -> {
            String[] e = slow(135, 1006);
            for (Fx x : all()) {
                if (!x.probes) {
                    continue;
                }
                Machine m = x.m;
                long before = m.x4.debt;
                int mk = x.val("(fun (w : Nat) (fun (y : Nat) (h y w)))");
                int a = x.f.apply(mk, x.val(e[0]));
                int b = x.f.apply(mk, x.val(e[1]));
                x.mark();
                assertEquals(T, x.f.conv(a, b));
                assertEquals(1, x.probed(Op.B12, U));
                assertTrue(m.x4.debt >= before / 2 + Op.B_CCLO / 2, "a U adds its budget (a later T halves the debt): " + m.x4.debt);
                m.x4.debt = Conv.Ext.DEBT_MAX;
                int c = x.f.apply(mk, x.val("(idn twoAlias)"));
                int d = x.f.apply(mk, x.val("(idn (lit 2))"));
                x.mark();
                assertEquals(T, x.f.conv(c, d));
                assertEquals(0, x.called(Op.CCLO), "no segment is started while the debt is at its bound");
                assertEquals(0, x.probed(Op.B12, T) + x.probed(Op.B1, T));
                assertTrue(x.called(Op.CBIND) >= 1, "the complete procedure answers");
                m.x4.debt = 0;
                x.mark();
                assertEquals(T, x.f.conv(x.f.apply(mk, x.val("(idn four)")), x.f.apply(mk, x.val("(idn (lit 4))"))));
                assertEquals(1, x.called(Op.CCLO));
            }
        });
    }

    // ---- B2, and agreement with the complete procedure ---------------------------------------------------------------

    @Test
    void b2_aKnownProofIsComparedByTypeAtOnce() {
        guarded(() -> {
            for (Fx x : all()) {
                int thm = x.val("pThm");
                int p1 = x.val("p1");
                int p2 = x.val("p2");
                int q1 = x.val("q1");
                assertEquals(T, x.f.conv(thm, p1));
                assertEquals(Layout.TRI_YES, (Values.flags(x.m, thm) >>> Layout.PROOF_SHIFT) & 3);
                assertEquals(T, x.f.conv(thm, p2));
                assertEquals(F, x.f.conv(thm, q1));
                assertEquals(F, x.f.conv(q1, thm));
            }
        });
    }

    @Test
    void theAnswersAreThoseOfTheCompleteProcedure() {
        guarded(() -> {
            String[] terms = {"two", "twoAlias", "(lit 2)", "(lit 3)", "four", "(Nat.add two two)", "(Nat.succ (Nat.succ vx))",
                "(Nat.add vx (lit 2))", "(Nat.add vx two)", "(idn two)", "(idab (lit 2))", "(const2 two n0)", "(const2 (lit 2) vx)",
                "(f two)", "(f (lit 2))", "(g vx two)", "(g vx (lit 2))", "(g vy two)", "p1", "p2", "pThm", "q1", "u0", "u1",
                "Unit.unit", "(fun (x : Nat) (f x))", "f", "(fun (x : Nat) x)", "(fun (x : NatAlias) (idn x))", "idn",
                "(proj Prod 0 prDef)", "n0", "(proj Prod 0 prDef2)", "(proj Prod 1 prDef)", "Bool.true",
                "(Nat.beq (lit 2) (lit 2))", "(-> Nat Nat)", "(-> NatAlias Nat)", "(pi (x : Nat) ((@ Eq 1) Nat x two))",
                "(pi (x : Nat) ((@ Eq 1) Nat x (lit 2)))", "((@ Nat.rec 1) (fun (x : Nat) Nat) two (fun (x : Nat) (ih : Nat) ih) (lit 3))",
                "((@ Nat.rec 1) (fun (x : Nat) Nat) (lit 2) (fun (x : Nat) (ih : Nat) ih) (lit 3))", "pr",
                "((@ Prod.mk 0 0) Nat Bool (proj Prod 0 pr) (proj Prod 1 pr))"};
            Fx[] xs = all();
            int[][] vals = new int[xs.length][terms.length];
            for (int k = 0; k < xs.length; k++) {
                for (int i = 0; i < terms.length; i++) {
                    vals[k][i] = xs[k].val(terms[i]);
                }
            }
            int same = 0;
            for (int i = 0; i < terms.length; i++) {
                for (int j = 0; j < terms.length; j++) {
                    int expect = xs[2].f.conv(vals[2][i], vals[2][j]);
                    for (int k = 0; k < 2; k++) {
                        assertEquals(expect, xs[k].f.conv(vals[k][i], vals[k][j]),
                                terms[i] + " =?= " + terms[j] + " with probes, D = " + xs[k].m.hostDepth);
                        assertEquals(2, xs[k].m.ssp);
                        assertEquals(0, xs[k].m.xp);
                    }
                    same += expect;
                }
            }
            assertTrue(same > terms.length && same < terms.length * terms.length / 2, same + " convertible pairs");
        });
    }

    /** The debug mode shortcheck (ISA 5.5): every T of a shortcut is re-derived by the complete procedure. */
    @Test
    void shortcheckFindsNothing() {
        guarded(() -> {
            Fixture f = Fixture.prelude();
            f.def("const2", "(-> Nat Nat Nat)", "(fun (a : Nat) (b : Nat) a)", Fixture.regular(1));
            f.seal();
            f.switches("probes");
            f.debug("shortcheck");
            Machine m = f.m;
            long audits = m.audits;
            assertEquals(T, f.conv(f.eval(f.term("(const2 (f two) n0)")), f.eval(f.term("(const2 (f (lit 2)) n0)"))));
            int mk = f.eval(f.term("(fun (w : Nat) (fun (y : Nat) (g y w)))"));
            assertEquals(T, f.conv(f.apply(mk, f.eval(f.term("twoAlias"))), f.apply(mk, f.eval(f.term("(lit 2)")))));
            assertTrue(m.audits - audits >= 2, "both shortcut answers were audited: " + (m.audits - audits));
        });
    }
}
