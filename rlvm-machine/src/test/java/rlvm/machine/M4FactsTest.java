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

/**
 * Step 2 ({@code Op.SW_FACTS}, off by default) in conversion: the static PROOF of c4 (B7), the comparison
 * plan of {@code CARGS} with the agreement rule on a crafted unread-then-proof telescope (B3, B4; ISA 6.1),
 * the prop mask of {@code ETAS}. Every answer must be the answer of the complete procedure (the machine V
 * without the switch), and {@code shortcheck} must find nothing. Owner: M4.
 *
 * <p>The telescope: {@code e : (n : Nat) -> (h : predK n) -> Nat := fun n h => useTrue h} with
 * {@code predK := fun _ => True}: the body reads h and not n (lamArity 2, unread {0}); position 1 is a
 * proof position (the sort of {@code predK n} is manifest) whose domain reads position 0.
 */
@Tag("fast")
@TestMethodOrder(MethodOrderer.MethodName.class)
class M4FactsTest {

    private static final int T = Layout.T;
    private static final int F = Layout.F;

    static final class Fx {
        final Fixture f;
        final Machine m;
        final boolean facts;
        final boolean audited;
        long[] calls;
        long[] probeCounts;

        Fx(int depth, boolean facts, boolean shortcheck) {
            this.facts = facts;
            this.audited = shortcheck;
            f = Fixture.prelude();
            f.def("predK", "(-> Nat Prop)", "(fun (n : Nat) True)", Fixture.regular(1));
            f.axiom("useTrue", "(-> True Nat)");
            f.def("e", "(pi (n : Nat) (h : (predK n)) Nat)", "(fun (n : Nat) (h : (predK n)) (useTrue h))", Fixture.regular(2));
            f.axiom("ax", "(pi (n : Nat) (h : (pred n)) Nat)");
            f.axiom("pk", "(pi (n : Nat) (predK n))");
            f.axiom("pk2", "(pi (n : Nat) (predK n))");
            f.axiom("pd", "(pi (n : Nat) (pred n))");
            f.axiom("pd2", "(pi (n : Nat) (pred n))");
            f.axiom("h", "(-> Nat Nat Nat)");
            f.axiom("pp", "(-> Nat P)");
            // a structure in Type with a proof field
            f.inductive("Sub", "Sub.mk : (pi (val : Nat) (prop : (pred val)) Sub)");
            f.axiom("sx", "Sub");
            f.axiom("pv", "(pi (s : Sub) (pred (proj Sub 0 s)))");
            f.seal();
            m = f.m;
            f.hostDepth(depth);
            if (facts) {
                f.switches("facts");
                f.m.x4.opts |= Conv.Ext.OPT_SKIP_ARGS; // B3 / B4 / the ETAS mask are the non-official option (finding S-F2)
            }
            if (shortcheck) {
                f.debug("shortcheck");
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
        }

        int called(int op) {
            return (int) (m.calls[op] - calls[op]);
        }

        int probed(int b, int outcome) {
            return (int) (m.probes[3 * b + outcome] - probeCounts[3 * b + outcome]);
        }
    }

    private static Fx[] fxs;
    private static boolean stubbed;

    /** F64, F0 (facts on, bounds 64 and 0), V (v0), S (facts on, shortcheck). */
    private static Fx[] all() {
        if (fxs == null && !stubbed) {
            try {
                fxs = new Fx[] {new Fx(Op.HOST_DEPTH, true, false), new Fx(0, true, false), new Fx(Op.HOST_DEPTH, false, false),
                    new Fx(Op.HOST_DEPTH, true, true)};
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

    @AfterAll
    static void theMachinesUnderBound64AndBound0AreIdentical() {
        if (fxs == null) {
            return;
        }
        Machine ma = fxs[0].m;
        Machine mb = fxs[1].m;
        assertNull(TierEq.compare(ma, mb), "records, memo words, runs and pairs under D = 64 and D = 0 with facts");
        assertEquals(ma.ticks(), mb.ticks(), "ticks");
        assertNull(TierEq.traceDifference(Arrays.copyOf(ma.trace, ma.traceN), Arrays.copyOf(mb.trace, mb.traceN)), "call trace");
        assertEquals(0, fxs[3].m.err, "shortcheck found nothing");
    }

    /** B7 (ISA 3.5): the result sort of the head is manifest: no dynamic proof-irrelevance test. */
    @Test
    void b7_staticProofAnswersC4() {
        guarded(() -> {
            for (Fx x : all()) {
                // data: h : Nat -> Nat -> Nat, result sort Type (never zero): PROOF = 1 without VTYPE / ISPROP
                int d1 = x.val("(h vx (lit 31))");
                int d2 = x.val("(h vx (lit 32))");
                x.mark();
                assertEquals(F, x.f.conv(d1, d2));
                assertEquals(Layout.TRI_NO, (Values.flags(x.m, d1) >>> Layout.PROOF_SHIFT) & 3);
                if (x.facts) {
                    assertEquals(0, x.called(Op.PIRR), "static PROOF = 1: c4 is skipped");
                    assertEquals(1, x.probed(Op.B7, F));
                } else {
                    assertEquals(1, x.called(Op.PIRR));
                }
                // proofs: pp : Nat -> P, result sort Prop (always zero): PROOF = 2, the types are compared, no ISPROP
                int p3 = x.val("(pp (lit 3))");
                int p4 = x.val("(pp (lit 4))");
                x.mark();
                assertEquals(T, x.f.conv(p3, p4));
                assertEquals(Layout.TRI_YES, (Values.flags(x.m, p3) >>> Layout.PROOF_SHIFT) & 3);
                assertEquals(1, x.called(Op.PIRR));
                if (x.facts) {
                    assertEquals(0, x.called(Op.ISPROP_F), "static PROOF = 2: no dynamic is_prop");
                    assertEquals(1, x.probed(Op.B7, T));
                }
                assertEquals(F, x.f.conv(p3, x.val("q1")), "a definitive F, as in the complete procedure");
                // a partial application has no static answer: the dynamic test runs
                int g1 = x.val("(h vx)");
                int g2 = x.val("(h vy)");
                x.mark();
                assertEquals(F, x.f.conv(g1, g2));
                assertTrue(x.called(Op.PIRR) >= 1);
                assertEquals(Layout.TRI_NO, (Values.flags(x.m, g1) >>> Layout.PROOF_SHIFT) & 3, "memoised by the dynamic test");
                // sorts, Pis and literals are never proofs
                x.mark();
                assertEquals(F, x.f.conv(x.val("(-> Nat Nat)"), x.val("(h vx vx)")));
                if (x.facts) {
                    assertEquals(0, x.called(Op.PIRR));
                }
            }
        });
    }

    /** ISA 6.1: the plan of CARGS on the unread-then-proof telescope, and the agreement rule. */
    @Test
    void cargs_planAndAgreementRule() {
        guarded(() -> {
            for (Fx x : all()) {
                Machine m = x.m;
                // SAMEDEF, the unread position differs: the proof position that reads it is compared like data
                int a = x.val("(e (lit 1) (pk (lit 1)))");
                int b = x.val("(e (lit 2) (pk2 (lit 2)))");
                x.mark();
                assertEquals(T, x.f.conv(a, b), "both unfold to useTrue of a proof of True");
                if (x.facts) {
                    int index = Facts.facts(m, x.f.cid("e"));
                    assertTrue(Facts.usable(m, index), "the record of e was derived by the comparison");
                    assertEquals(0b01L, Cargs.skipMask(m, a, b, Op.POL_SAMEDEF, index),
                            "position 0 is unread and differs: position 1 (a proof whose domain reads it) is NOT skipped");
                    assertEquals(0b10L, Cargs.skipMask(m, a, b, Op.POL_APP, index),
                            "under APP nothing is unread; the proof position is skipped, position 0 is compared");
                    // the unread position agrees (identical): the proof position is skipped too
                    int c = x.val("(e (lit 1) (pk2 (lit 1)))");
                    assertEquals(0b11L, Cargs.skipMask(m, a, c, Op.POL_SAMEDEF, index));
                    x.mark();
                    assertEquals(T, x.f.conv(a, c));
                    if (!x.audited) {
                        assertEquals(0, x.called(Op.PIRR), "neither argument was compared");
                        assertEquals(0, x.called(Op.DELTA), "nothing was unfolded");
                    }
                } else {
                    assertEquals(T, x.f.conv(a, x.val("(e (lit 1) (pk2 (lit 1)))")));
                }
                // APP (an axiom head): the proof position is skipped when the position it reads agrees ...
                int u = x.val("(ax (f two) (pd (f two)))");
                int v = x.val("(ax (f (lit 2)) (pd2 (f (lit 2))))");
                x.mark();
                assertEquals(T, x.f.conv(u, v));
                if (x.facts) {
                    if (!x.audited) { // shortcheck re-derives the answer by the complete procedure, which compares them
                        assertEquals(0, x.f.pair(x.val("(pd (f two))"), x.val("(pd2 (f (lit 2)))")), "the proofs were never compared");
                    }
                    assertTrue(x.probed(Op.B3, T) >= 1);
                } else {
                    assertEquals(Layout.POS, x.f.pair(x.val("(pd (f two))"), x.val("(pd2 (f (lit 2)))")));
                }
                // ... and the comparison fails on the position it reads when that one differs
                assertEquals(F, x.f.conv(u, x.val("(ax (f (lit 3)) (pd2 (f (lit 3))))")));
                assertEquals(2, m.ssp);
            }
        });
    }

    /** ISA 4.4 ETAS: the proof fields of the constructor are skipped, after the type comparison. */
    @Test
    void etas_propMaskOfTheConstructor() {
        guarded(() -> {
            for (Fx x : all()) {
                int sx = x.val("sx");
                int mk = x.val("(Sub.mk (proj Sub 0 sx) (pv sx))");
                x.mark();
                assertEquals(T, x.f.conv(sx, mk));
                assertEquals(1, x.called(Op.ETAS));
                int fld = x.val("(proj Sub 1 sx)");
                int pv = x.val("(pv sx)");
                if (x.facts) {
                    if (!x.audited) {
                        assertEquals(0, x.f.pair(fld, pv), "the proof field was not compared");
                    }
                } else {
                    assertEquals(Layout.POS, x.f.pair(fld, pv), "the complete procedure compares it (proof irrelevance)");
                }
                // a data field that differs still refutes
                assertEquals(F, x.f.conv(sx, x.val("(Sub.mk (Nat.succ (proj Sub 0 sx)) (pd (Nat.succ (proj Sub 0 sx))))")));
                // the mask is applied only after the types were compared: another structure is refused
                assertEquals(F, x.f.conv(x.val("pr"), mk));
            }
        });
    }

    @Test
    void theAnswersAreThoseOfTheCompleteProcedure() {
        guarded(() -> {
            String[] terms = {"(e (lit 1) (pk (lit 1)))", "(e (lit 2) (pk2 (lit 2)))", "(e vx (pk vx))", "(e vy (pk2 vy))",
                "(useTrue (pk vx))", "(useTrue True.intro)", "(ax vx (pd vx))", "(ax vx (pd2 vx))", "(ax vy (pd vy))",
                "(ax two (pd two))", "(ax (lit 2) (pd2 (lit 2)))", "(pk vx)", "(pk2 vy)", "True.intro", "(pd vx)", "(pd2 vx)",
                "(pd vy)", "sx", "(Sub.mk (proj Sub 0 sx) (proj Sub 1 sx))", "(Sub.mk (proj Sub 0 sx) (pv sx))",
                "(Sub.mk vx (pd vx))", "(Sub.mk vx (pd2 vx))", "(Sub.mk vy (pd vy))", "(h vx vy)", "(h vx vx)", "(predK vx)",
                "(predK vy)", "True", "(pred vx)", "(pred vy)", "(pp vx)", "(pp vy)", "p1", "two", "(lit 2)",
                "((@ Eq.refl 1) Nat two)", "((@ Eq.refl 1) Nat (lit 2))", "((@ Eq 1) Nat two two)", "((@ Eq 1) Nat (lit 2) two)",
                "(And.intro P P p1 p2)", "(And.intro P P p2 p1)", "((@ Exists.intro 1) Nat pred vx (pd vx))",
                "((@ Exists.intro 1) Nat pred vx (pd2 vx))", "((@ Exists.intro 1) Nat pred vy (pd vy))"};
            Fx[] xs = all();
            int n = terms.length;
            int[][] vals = new int[xs.length][n];
            for (int k = 0; k < xs.length; k++) {
                for (int i = 0; i < n; i++) {
                    vals[k][i] = xs[k].val(terms[i]);
                }
            }
            int same = 0;
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    int expect = xs[2].f.conv(vals[2][i], vals[2][j]);
                    for (int k = 0; k < xs.length; k++) {
                        if (k != 2) {
                            assertEquals(expect, xs[k].f.conv(vals[k][i], vals[k][j]), terms[i] + " =?= " + terms[j]
                                    + " with facts" + (k == 3 ? " and shortcheck" : ", D = " + xs[k].m.hostDepth));
                            assertEquals(2, xs[k].m.ssp);
                        }
                    }
                    same += expect;
                }
            }
            assertTrue(same > n, same + " convertible pairs");
        });
    }
}
