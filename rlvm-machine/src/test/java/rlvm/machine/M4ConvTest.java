package rlvm.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.Arrays;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import rlvm.api.EngineConfig;
import rlvm.api.MachineErrors;
import rlvm.ref.RefKernel;
import rlvm.store.IntLists;

/**
 * CONV and its sub-judgments, state for state (ISA 4.4.1-4.4.3, 6.1) against the official order of
 * {@code is_def_eq_core} (R02 section 8): one row per state c0 .. c13, per branch of LAZYD and per rule of
 * the pair table (5.4). Owner: M4.
 *
 * <p>Every row runs on three machines over the same hand-built prelude: A with host depth bound 64 and
 * B with bound 0 (every non-leaf call is a frame: every {@code sigN} site and every {@code resume} case
 * of the row runs) execute the same sequence and must end in identical states (records with memo words,
 * runs, pairs, ticks, call trace: the convention equivalence of H12, checked once after all tests); C
 * (bound 2) compares each pair in the other order first and carries the oracle: the reference kernel on
 * the same terms must give the verdict the row states; S runs every row with the speculative segments
 * of step 5 switched on ({@code Op.SW_PROBES}) and must give the same answers. In a row, {@code $name} is
 * a free variable of the fixture's context.
 *
 * <p>A neighbour's judgment that is still a stub aborts the test (assumption), so that this class can be
 * in the fast group whatever the state of the main tree is.
 */
@Tag("fast")
@TestMethodOrder(MethodOrderer.MethodName.class)
class M4ConvTest {

    private static final int T = Layout.T;
    private static final int F = Layout.F;

    /** Context: name, type. The machine opens them as binders, the oracle makes locals. */
    private static final String[][] CTX = {
        {"vx", "Nat"}, {"vy", "Nat"}, {"hp1", "P"}, {"hp2", "P"}, {"hid", "((@ id 1) Prop P)"}, {"ua", "Unit"},
        {"ub", "Unit"}, {"ia", "(IUnit Nat.zero)"}, {"ib", "(IUnit Nat.zero)"}, {"vp", "((@ Prod 0 0) Nat Bool)"},
        {"vfn", "(-> Nat Nat)"}, {"vsg", "(Sig B)"},
    };

    static final class Fx {
        final Fixture f;
        final Machine m;
        final boolean swapped;
        RefKernel k;
        long[] calls;
        int traceMark;

        Fx(int depth, boolean swapped, boolean oracle, boolean probes) {
            this.swapped = swapped;
            f = Fixture.prelude();
            f.def("nAlias", "Nat", "n0", Fixture.regular(1));
            f.def("two2", "Nat", "(lit 2)", Fixture.regular(1));
            f.def("opq", "Nat", "two", Fixture.OPAQUE_HINT);
            f.def("pdA", "((@ Prod 0 0) Nat Bool)", "((@ Prod.mk 0 0) Nat Bool n0 Bool.true)", Fixture.regular(1));
            f.def("pdB", "((@ Prod 0 0) Nat Bool)", "((@ Prod.mk 0 0) Nat Bool n0 Bool.false)", Fixture.regular(1));
            f.def("const2", "(-> Nat Nat Nat)", "(fun (a : Nat) (b : Nat) a)", Fixture.regular(1));
            f.def("idn", "(-> Nat Nat)", "(fun (a : Nat) a)", Fixture.regular(3));
            f.def("pdC", "((@ Prod 0 0) Nat Bool)", "((@ Prod.mk 0 0) Nat Bool n0 Bool.true)", Fixture.regular(1));
            f.def("pdX", "((@ Prod 0 0) Nat Bool)", "((@ Prod.mk 0 0) Nat Bool (f (lit 71)) Bool.true)", Fixture.regular(1));
            f.axiom("pp", "(-> Nat P)");
            f.axiom("h", "(-> Nat Nat Nat)");
            f.inductive("Fn", "Fn.mk : (-> (-> Nat Nat) Fn)");
            f.axiom("fnv", "Fn");
            f.def("fnD", "Fn", "(Fn.mk (fun (a : Nat) a))", Fixture.regular(1));
            f.seal();
            m = f.m;
            f.hostDepth(depth);
            if (probes) {
                f.switches("probes"); // step 5: the answers must be those of the complete procedure
                f.m.x4.opts |= Conv.Ext.OPT_CONGRUENCE; // incl. the non-official option: these rows do not separate it
            }
            m.stats = true;
            m.debug |= Op.DBG_TRACE;
            if (oracle) {
                k = new RefKernel(f.ctx, EngineConfig.defaults());
                k.begin(Integer.MAX_VALUE, IntLists.EMPTY);
            }
            for (String[] c : CTX) {
                f.fvar(c[0], c[1]);
                if (oracle) {
                    f.bind(c[0], k.mkLocal(f.term(c[1])));
                }
            }
        }

        /** The machine value of a row term. */
        int val(String text) {
            return f.eval(f.term(text.replace("$", "")));
        }

        void mark() {
            calls = m.calls.clone();
            traceMark = m.traceN;
        }

        int called(int op) {
            return (int) (m.calls[op] - calls[op]);
        }

        int[] trace() {
            return Arrays.copyOfRange(m.trace, traceMark, m.traceN);
        }

        /** The op of the call event that follows the first event {@code op} since the mark, or -1. */
        int after(int op) {
            int[] t = trace();
            for (int i = 0; i + 1 < t.length; i++) {
                if (t[i] == op) {
                    return t[i + 1];
                }
            }
            return -1;
        }
    }

    private static Fx[] fxs;
    private static boolean stubbed;

    private static Fx[] all() {
        if (fxs == null && !stubbed) {
            try {
                fxs = new Fx[] {new Fx(Op.HOST_DEPTH, false, false, false), new Fx(0, false, false, false),
                    new Fx(2, true, true, false), new Fx(Op.HOST_DEPTH, false, false, true)};
            } catch (UnsupportedOperationException e) {
                stubbed = true;
            }
        }
        assumeTrue(fxs != null, "a neighbour's judgment is still a stub");
        return fxs;
    }

    /** The two machines that execute the same sequence (bounds 64 and 0). */
    private static Fx[] ab() {
        Fx[] x = all();
        return new Fx[] {x[0], x[1]};
    }

    /** Runs a test body; a stub of another package aborts it. */
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

    /** CONV(a, b) on every machine (both orders), the stacks restored, and the oracle's verdict. */
    private static void row(boolean expect, String a, String b) {
        for (Fx x : all()) {
            int va = x.val(a);
            int vb = x.val(b);
            int r1 = x.swapped ? x.f.conv(vb, va) : x.f.conv(va, vb);
            int r2 = x.swapped ? x.f.conv(va, vb) : x.f.conv(vb, va);
            String what = a + " =?= " + b + " (D = " + x.m.hostDepth + ")";
            assertEquals(expect ? T : F, r1, what);
            assertEquals(expect ? T : F, r2, what + ", other order");
            assertEquals(CTX.length, x.m.ssp, "binders closed: " + what);
            assertEquals(0, x.m.xp, "scratch restored: " + what);
            assertEquals(0, x.m.wp, "worklist empty: " + what);
            if (x.k != null) {
                assertEquals(expect, x.k.isDefEq(x.f.term(a), x.f.term(b)), "the reference kernel on " + what);
            }
        }
    }

    private static void eq(String a, String b) {
        row(true, a, b);
    }

    private static void ne(String a, String b) {
        row(false, a, b);
    }

    @AfterAll
    static void theMachinesUnderBound64AndBound0AreIdentical() {
        if (fxs == null) {
            return;
        }
        Machine ma = fxs[0].m;
        Machine mb = fxs[1].m;
        assertNull(TierEq.compare(ma, mb), "records, memo words, runs and pairs under D = 64 and D = 0");
        assertEquals(ma.ticks(), mb.ticks(), "ticks");
        assertNull(TierEq.traceDifference(Arrays.copyOf(ma.trace, ma.traceN), Arrays.copyOf(mb.trace, mb.traceN)), "call trace");
        assertTrue(mb.framesWritten > 100 && ma.framesWritten == 0, "D = 0 wrote frames (" + mb.framesWritten
                + "), D = 64 none (" + ma.framesWritten + ")");
    }

    // ---- c0: identity, literals, the pair table, sorts, binders ---------------------------------------------

    @Test
    void c0_identityAndLiterals() {
        guarded(() -> {
            eq("(f n0)", "(f n0)");
            eq("Nat", "Nat");
            eq("(lit 2)", "(lit 2)");
            ne("(lit 2)", "(lit 3)");
            eq("(lit 123456789012345678901234567890)", "(lit 123456789012345678901234567890)");
            ne("(lit 123456789012345678901234567890)", "(lit 123456789012345678901234567891)");
            ne("(lit 4294967296)", "(lit 3)"); // an NBIG against an immediate
            ne("(lit 4294967296)", "(lit 4294967297)");
            eq("(str \"abc\")", "(str \"abc\")");
            ne("(str \"abc\")", "(str \"abd\")");
            ne("(str \"a\")", "(lit 97)");
            // literals are answered inline: no tick, no call event, no pair entry
            for (Fx x : ab()) {
                int two = x.val("(lit 2)");
                int three = x.val("(lit 3)");
                long ticks = x.m.ticks();
                x.mark();
                assertEquals(F, x.f.conv(two, three));
                assertEquals(ticks, x.m.ticks());
                assertEquals(0, x.called(Op.CONV));
                assertEquals(0, x.f.pair(two, three));
                assertEquals(F, Conv.convFast(x.m, two, three));
                assertEquals(T, Conv.convFast(x.m, two, two));
            }
        });
    }

    @Test
    void c0_sortsCompareLevelsSemantically() {
        guarded(() -> {
            eq("(sort (imax 2 1))", "(sort 2)");
            ne("Prop", "Type");
            ne("(sort 1)", "(sort 2)"); // no cumulativity
            for (Fx x : all()) {
                // level parameters (the oracle's scope declares none: machines only)
                int s1 = x.val("(sort (max u v))");
                int s2 = x.val("(sort (max v u))");
                int s3 = x.val("(sort u)");
                assertEquals(T, x.f.conv(s1, s2));
                assertEquals(F, x.f.conv(s1, s3));
                assertEquals(T, x.f.conv(x.val("(sort (max u u))"), s3));
                assertEquals(T, x.f.conv(x.val("(sort (imax u 0))"), x.val("Prop")));
                assertEquals(0, x.f.pair(s1, s2), "a pair of sorts is not recorded (ISA c0: ret LVLEQ)");
                assertEquals(0, x.f.pair(s1, s3));
            }
        });
    }

    @Test
    void c0_bindersAreDecidedByCbind() {
        guarded(() -> {
            eq("(fun (x : Nat) x)", "(fun (y : NatAlias) y)");
            eq("(pi (x : Nat) ((@ Eq 1) Nat x two))", "(pi (x : NatAlias) ((@ Eq 1) Nat x (lit 2)))");
            ne("(-> Nat Nat)", "(-> Nat Bool)");
            ne("(-> Nat Nat)", "(-> Bool Nat)");
            ne("(fun (x : Nat) x)", "(fun (x : Nat) Nat.zero)");
            ne("(-> Nat Nat)", "(-> Nat Nat Nat)");
            ne("(fun (x : Nat) x)", "(-> Nat Nat)");
            // telescopes: several binders, dependent and not; a body that is a binder only after reduction
            eq("(fun (x : Nat) (y : Nat) (g x y))", "(fun (a : Nat) (b : NatAlias) (g a b))");
            ne("(fun (x : Nat) (y : Nat) (g x y))", "(fun (a : Nat) (b : Nat) (g b a))");
            eq("(pi (x : Nat) (y : (B x)) Nat)", "(pi (x : Nat) (y : (B x)) NatAlias)");
            ne("(pi (x : Nat) (y : (B x)) Nat)", "(pi (x : Nat) (y : (B Nat.zero)) Nat)");
            eq("(fun (x : Nat) ((fun (z : Nat) (y : Nat) z) x))", "(fun (x : Nat) (y : Nat) x)");
            // under free variables of the context
            eq("(fun (x : Nat) (g $vx x))", "(fun (x : Nat) (g $vx x))");
            ne("(fun (x : Nat) (g $vx x))", "(fun (x : Nat) (g $vy x))");
            // the type of a lambda (PII) against the Pi it has
            for (Fx x : all()) {
                int lam = x.val("(fun (x : Nat) (f x))");
                int pii = x.f.vtype(lam);
                assertEquals(Layout.PII, Values.kind(x.m, pii));
                assertEquals(T, x.f.conv(pii, x.val("(-> Nat Nat)")));
                assertEquals(T, x.f.conv(x.val("(-> NatAlias Nat)"), pii));
                assertEquals(F, x.f.conv(pii, x.val("(-> Nat Bool)")));
                assertEquals(CTX.length, x.m.ssp);
            }
        });
    }

    @Test
    void c0_aSuspendedSideIsForcedFirst() {
        guarded(() -> {
            // arguments that are redexes are delayed (SUSP); CONV forces both sides and records the values
            eq("(f ((fun (z : Nat) z) n0))", "(f n0)");
            eq("(f ((fun (z : Nat) z) $vx))", "(f ((fun (w : Nat) $vx) n0))");
            ne("(f ((fun (z : Nat) z) $vx))", "(f ((fun (w : Nat) $vy) n0))");
            eq("(let (x : Nat := two) (Nat.succ x))", "(lit 3)");
            eq("(let (x : Nat := n0) (f x))", "(f n0)");
        });
    }

    // ---- c2: the Bool.true shortcut ---------------------------------------------------------------------------

    @Test
    void c2_aClosedTermAgainstBoolTrueOnTheRightIsEvaluated() {
        guarded(() -> {
            eq("(Nat.beq (lit 2) (lit 2))", "Bool.true");
            ne("(Nat.beq (lit 2) (lit 3))", "Bool.true");
            eq("(Nat.ble four (lit 4))", "Bool.true");
            eq("(Nat.beq (lit 2) (lit 3))", "Bool.false");
            // Nat.beq is opaque in this prelude: an open term is not evaluated (hasFVar), and nothing else helps
            ne("(Nat.beq $vx $vx)", "Bool.true");
            for (Fx x : ab()) {
                int t = x.val("(Nat.beq (lit 7) (lit 7))");
                int tru = x.val("Bool.true");
                x.mark();
                assertEquals(T, x.f.conv(t, tru));
                assertEquals(Op.WHNF, x.after(Op.CONV), "step 2: whnf(t) is the first thing done");
                assertEquals(0, x.called(Op.LAZYD));
                // only s on the RIGHT is tested: with Bool.true on the left the answer comes from lazy delta (Nat fold)
                int t2 = x.val("(Nat.beq (lit 8) (lit 8))");
                x.mark();
                assertEquals(T, x.f.conv(tru, t2));
                assertEquals(1, x.called(Op.LAZYD));
            }
        });
    }

    // ---- c3: whnf_core with cheap projections, then quick -----------------------------------------------------

    @Test
    void c3_cheapCoreThenQuick() {
        guarded(() -> {
            String recZero = "((@ Nat.rec 1) (fun (x : Nat) Nat) n0 (fun (x : Nat) (ih : Nat) ih) Nat.zero)";
            eq(recZero, "n0");
            ne(recZero, "Nat.zero");
            // both sides become binders: the quick test after the change runs CBIND
            String recLam = "((@ Nat.rec 1) (fun (x : Nat) (-> Nat Nat)) (fun (y : Nat) y) (fun (x : Nat) (ih : (-> Nat Nat)) ih) Nat.zero)";
            eq(recLam, "(fun (z : NatAlias) z)");
            ne(recLam, "(fun (z : Nat) Nat.zero)");
            for (Fx x : ab()) {
                int r = x.val("((@ Nat.rec 1) (fun (x : Nat) Nat) (f n0) (fun (x : Nat) (ih : Nat) ih) Nat.zero)");
                int v = x.val("(f n0)");
                x.mark();
                assertEquals(T, x.f.conv(r, v));
                assertEquals(1, x.called(Op.CORE));
                assertEquals(0, x.called(Op.PIRR), "decided by identity after the cheap core: step 5 is not reached");
                assertEquals(Layout.POS, x.f.pair(r, v));
            }
        });
    }

    // ---- c4: proof irrelevance ---------------------------------------------------------------------------------

    @Test
    void c4_proofIrrelevanceIsDefinitiveInBothDirections() {
        guarded(() -> {
            eq("p1", "p2");
            eq("p1", "pThm");
            ne("p1", "q1"); // different propositions: a definitive F
            eq("$hp1", "$hp2");
            eq("$hp1", "p1");
            eq("$hid", "$hp2"); // the sort of the type is found by whnf (h : id Prop P)
            eq("(pp $vx)", "(pp $vy)");
            ne("n0", "Nat.zero"); // not for data
            ne("$vx", "$vy");
            eq("(fun (x : Nat) p1)", "(fun (x : Nat) p2)");
            ne("(fun (x : Nat) n0)", "(fun (x : Nat) Nat.zero)");
            for (Fx x : ab()) {
                int p3 = x.val("(pp (lit 3))");
                int p4 = x.val("(pp (lit 4))");
                int q = x.val("q1");
                x.mark();
                assertEquals(T, x.f.conv(p3, p4));
                assertEquals(1, x.called(Op.PIRR));
                assertEquals(0, x.called(Op.LAZYD), "T is final: nothing after step 5 runs");
                assertEquals(0, x.called(Op.CARGS));
                assertEquals(Layout.TRI_YES, proof(x.m, p3), "PROOF = 2 memoised on the left side");
                x.mark();
                assertEquals(F, x.f.conv(p4, q));
                assertEquals(0, x.called(Op.LAZYD), "F is final too (perf/irrelevance-refutes)");
                assertEquals(0, x.called(Op.UNITL));
                // data: PROOF = 1 is memoised, and the next comparison does not ask again
                int d1 = x.val("(h (lit 1) (lit 2))");
                int d2 = x.val("(h (lit 1) (lit 3))");
                int d3 = x.val("(h (lit 1) (lit 4))");
                x.mark();
                assertEquals(F, x.f.conv(d1, d2));
                assertEquals(1, x.called(Op.PIRR));
                assertEquals(Layout.TRI_NO, proof(x.m, d1));
                x.mark();
                assertEquals(F, x.f.conv(d1, d3));
                assertEquals(0, x.called(Op.PIRR), "the flag answers inline");
            }
        });
    }

    private static int proof(Machine m, int v) {
        return (Values.flags(m, v) >>> Layout.PROOF_SHIFT) & 3;
    }

    @Test
    void c4_errorsOfInferencePropagate() {
        guarded(() -> {
            // proof irrelevance infers the type of the term: a non-function applied is an error, not F (R02 8.10)
            for (Fx x : all()) {
                int bad = x.val("(Nat.zero Nat.zero)");
                int n0 = x.val("n0");
                x.f.expectFail(MachineErrors.E_FUN_EXPECTED, () -> x.f.conv(bad, n0));
                assertEquals(0, x.f.pair(bad, n0), "an abandoned comparison writes nothing (I-MEMO)");
                assertEquals(CTX.length, x.m.ssp);
            }
        });
    }

    // ---- c5: lazy delta, one row per branch ---------------------------------------------------------------------

    @Test
    void c5_l0_natOffsets() {
        guarded(() -> {
            eq("(lit 3)", "(Nat.succ (Nat.succ (Nat.succ Nat.zero)))");
            eq("(lit 0)", "Nat.zero");
            ne("(lit 1)", "Nat.zero");
            ne("(lit 2)", "(Nat.succ (Nat.succ (Nat.succ Nat.zero)))");
            eq("(Nat.succ $vx)", "(Nat.succ $vx)");
            ne("(Nat.succ $vx)", "(Nat.succ $vy)");
            ne("(lit 5)", "(Nat.succ $vx)");
            ne("(Nat.succ $vx)", "Nat.zero");
            ne("(Nat.succ $vx)", "(lit 0)");
            eq("(Nat.succ (Nat.succ (f $vx)))", "(Nat.succ (Nat.succ (f $vx)))");
            ne("(Nat.succ (Nat.succ (f $vx)))", "(Nat.succ (f $vx))");
            eq("(Nat.succ (Nat.succ (f two)))", "(Nat.succ (Nat.succ (f (lit 2))))");
            ne("(lit 7)", "(Nat.succ (Nat.succ (f two)))");
            eq("(Nat.succ (Nat.succ $vx))", "(Nat.add $vx (lit 2))");
        });
    }

    @Test
    void c5_l1_natFoldingOnClosedTermsOnly() {
        guarded(() -> {
            eq("(Nat.add (lit 2) (lit 3))", "(lit 5)");
            eq("(Nat.mul (lit 4) (lit 5))", "(Nat.add (lit 10) (lit 10))");
            ne("(Nat.mul (lit 4) (lit 5))", "(Nat.add (lit 10) (lit 11))");
            eq("(Nat.succ (lit 4))", "(lit 5)");
            eq("(Nat.pow (lit 2) (lit 100))", "(lit 1267650600228229401496703205376)");
            ne("(Nat.pow (lit 2) (lit 100))", "(lit 1267650600228229401496703205377)");
            eq("(Nat.succ (Nat.mul (lit 2) (lit 3)))", "(lit 7)"); // the unary case of reduce_nat (an NSUC cell)
            eq("(Nat.mul (lit 2) (lit 3))", "(Nat.succ (Nat.mul (lit 1) (lit 5)))");
            ne("(Nat.succ (Nat.mul (lit 2) (lit 3)))", "(Nat.mul (lit 2) (lit 4))");
            // Nat.mul is opaque here: on an open term nothing folds and nothing unfolds
            ne("(Nat.mul $vx (lit 0))", "(lit 0)");
            ne("(Nat.mul n0 (lit 2))", "n0");
        });
    }

    @Test
    void c5_l2_unfoldingByHints() {
        guarded(() -> {
            // only one side unfolds
            eq("two", "(Nat.succ (Nat.succ Nat.zero))");
            eq("NatAlias", "Nat");
            eq("((@ id 1) Nat n0)", "n0");
            ne("((@ id 1) Nat n0)", "Nat.zero");
            // both unfold: the greater height first
            eq("twoAlias", "two");
            eq("four", "(lit 4)");
            ne("four", "(lit 5)");
            eq("(Nat.add two two)", "four");
            eq("(f twoAlias)", "(f two)");
            // equal heights, different constants: both unfold
            eq("two", "two2");
            // abbrev before regular; a definition with hint opaque last; an opaque constant never
            eq("((@ id 1) Nat two)", "twoAlias");
            eq("opq", "twoAlias");
            eq("opq", "(lit 2)");
            ne("opq", "(lit 3)");
            ne("opaqueTwo", "two");
            ne("opaqueTwo", "(lit 2)");
            eq("opaqueTwo", "opaqueTwo");
            for (Fx x : ab()) {
                // heights: twoAlias (2) against two (1): the first step unfolds the higher side only
                int ta = x.val("(g twoAlias n0)");
                int tw = x.val("(g two n0)");
                assertEquals(T, x.f.conv(ta, tw));
                int al = x.val("twoAlias");
                int two = x.val("two");
                x.mark();
                assertEquals(Layout.UNDEF, x.f.run(Conv.lstep(x.m, x.val("n0"), x.val("(f n0)"), 0)), "neither unfolds: Unknown");
                assertEquals(0, x.called(Conv.LSTEP), "a leaf: no tick, no event");
                assertEquals(x.val("n0"), x.m.A2);
                x.mark();
                int st = x.f.run(Conv.lstep(x.m, al, two, 0));
                assertEquals(T, st, "twoAlias unfolds to the record of two: Equal");
                assertEquals(1, x.called(Conv.LSTEP));
                x.mark();
                st = x.f.run(Conv.lstep(x.m, two, x.val("(f n0)"), 0));
                assertEquals(Conv.AGAIN, st, "only the left side unfolds: Continue");
                assertEquals(Layout.imm(2), x.m.A2);
                assertEquals(x.val("(f n0)"), x.m.A3);
            }
        });
    }

    @Test
    void c5_l2_aProjectionOnTheOtherSideIsReducedBeforeTheDefinitionUnfolds() {
        guarded(() -> {
            eq("nAlias", "(proj Prod 0 prDef)");
            eq("(proj Prod 0 prDef)", "n0");
            eq("(proj Prod 1 prDef)", "Bool.true");
            ne("(proj Prod 1 prDef)", "Bool.false");
            for (Fx x : ab()) {
                // the struct pdA unfolds only under full CORE: cheap mode leaves the projection stuck
                int pj = x.val("(proj Prod 1 pdC)");
                int al = x.val("((@ id 1) Bool Bool.true)");
                assertEquals(Layout.PROJ, Values.kind(x.m, pj));
                x.mark();
                int st = x.f.run(Conv.lstep(x.m, al, pj, 0));
                assertEquals(Op.CORE, x.after(Conv.LSTEP), "try_unfold_proj_app first");
                assertEquals(Conv.AGAIN, st);
                assertEquals(al, x.m.A2, "the definition was not unfolded");
                assertEquals(x.val("Bool.true"), x.m.A3);
                x.mark();
                st = x.f.run(Conv.lstep(x.m, pj, al, 0));
                assertEquals(Conv.AGAIN, st);
                assertEquals(x.val("Bool.true"), x.m.A2);
                assertEquals(al, x.m.A3);
                // a projection that does not reduce: the definition unfolds
                int stuck = x.val("(proj Prod 1 $vp)");
                x.mark();
                st = x.f.run(Conv.lstep(x.m, al, stuck, 0));
                assertEquals(Conv.AGAIN, st);
                assertEquals(x.val("Bool.true"), x.m.A2);
                assertEquals(stuck, x.m.A3);
            }
        });
    }

    @Test
    void c5_l2_sameHeadComparesArgumentsBeforeUnfolding() {
        guarded(() -> {
            eq("(Nat.add n0 two)", "(Nat.add n0 (lit 2))");
            ne("(Nat.add n0 two)", "(Nat.add n0 (lit 3))");
            eq("(Nat.add (f two) n0)", "(Nat.add (f twoAlias) n0)");
            // the arguments differ but the definition ignores the one that does: FAILED, then both unfold
            eq("(const2 n0 (lit 1))", "(const2 n0 (lit 2))");
            ne("(const2 (lit 1) n0)", "(const2 (lit 2) n0)");
            // spines of different length: the official is_def_eq_args compares from the last argument and fails
            ne("(const2 n0)", "(const2 n0 (lit 1))");
            eq("(const2 n0 (lit 1))", "(idn n0)");
            for (Fx x : all()) {
                int s1 = x.val("(Nat.add (f n0) two)");
                int s2 = x.val("(Nat.add (f n0) (lit 2))");
                x.mark();
                assertEquals(T, x.f.conv(s1, s2));
                assertEquals(1, x.called(Op.CARGS));
                assertEquals(0, Values.memo(x.m, s1, Layout.M_UNF - Layout.M0), "Nat.add was not unfolded");
                assertEquals(Layout.POS, x.f.pair(s1, s2), "no FAILED for a comparison that succeeded");
                // FAILED only for a same-head comparison that completed with F (ISA 5.4)
                int c1 = x.val("(const2 (f n0) (lit 5))");
                int c2 = x.val("(const2 (f n0) (lit 6))");
                assertEquals(T, x.f.conv(c1, c2));
                assertEquals(Layout.POS | Layout.FAILED, x.f.pair(c1, c2));
                int e1 = x.val("(const2 (lit 5) (f n0))");
                int e2 = x.val("(const2 (lit 6) (f n0))");
                assertEquals(F, x.f.conv(e1, e2));
                assertEquals(Layout.NEG | Layout.FAILED, x.f.pair(e1, e2));
                // a pair in FAILED is not compared again: lazy delta unfolds at once
                assertEquals(0, x.f.pair(e2, e1) & Layout.POS);
                x.mark();
                assertEquals(F, x.f.run(Conv.lstep(x.m, e1, e2, 0)), "both unfold at once: 5 against 6 is Diff");
                assertEquals(0, x.called(Op.CARGS), "failed_before: no second argument comparison");
            }
        });
    }

    // ---- c6: constants and projections ------------------------------------------------------------------------

    @Test
    void c6_constantLevelsCompareSemantically() {
        guarded(() -> {
            for (Fx x : all()) {
                int a1 = x.val("(@ PU (max u v))");
                int a2 = x.val("(@ PU (max v u))");
                int a3 = x.val("(@ PU u)");
                assertNotEquals(a1, a2);
                assertEquals(T, x.f.conv(a1, a2));
                assertEquals(F, x.f.conv(a1, a3));
                assertEquals(T, x.f.conv(x.val("(@ id (max u v))"), x.val("(@ id (max v u))")));
                assertEquals(F, x.f.conv(x.val("(@ id u)"), x.val("(@ id v)")));
            }
        });
    }

    @Test
    void c6_sameProjectionOfTwoStructuresUnfoldsThemInLockstep() {
        guarded(() -> {
            eq("(proj Prod 0 pr)", "(proj Prod 0 pr)");
            ne("(proj Prod 0 pr)", "n0");
            eq("(proj Prod 0 prDef)", "(proj Prod 0 prDef2)");
            eq("(proj Prod 0 pr)", "(proj Prod 0 ((@ id 1) ((@ Prod 0 0) Nat Bool) pr))");
            ne("(proj Prod 0 pr)", "(proj Prod 1 pr)");
            ne("(proj Prod 0 $vp)", "(proj Prod 0 pr)");
            // the structures differ, the projected fields agree: the fields are compared (reduce_proj_core)
            eq("(proj Prod 0 pdA)", "(proj Prod 0 pdB)");
            ne("(proj Prod 1 pdA)", "(proj Prod 1 pdB)");
            for (Fx x : ab()) {
                int p1 = x.val("(proj Prod 0 ((@ id 1) ((@ Prod 0 0) Nat Bool) pdA))");
                int p2 = x.val("(proj Prod 0 pdB)");
                x.mark();
                assertEquals(T, x.f.conv(p1, p2));
                assertEquals(1, x.called(Op.PROJD));
                assertTrue(x.called(Conv.LSTEP) >= 2, "lazy_delta_reduction_step in a loop");
                assertEquals(0, Values.memo(x.m, Values.a(x.m, p1), Layout.M_WHNF - Layout.M0),
                        "the structure was unfolded lazily, never normalised (perf/proj-lazy-struct)");
            }
        });
    }

    // ---- c7: the second whnf_core and the restart -----------------------------------------------------------------

    @Test
    void c7_fullCoreChangesASideAndTheProcedureRestarts() {
        guarded(() -> {
            eq("(proj Prod 0 pdA)", "n0");
            ne("(proj Prod 1 pdA)", "Bool.false");
            eq("(g (proj Prod 0 pdA) n0)", "(g n0 n0)");
            eq("(proj Sig 0 ((@ id 1) (Sig B) $vsg))", "(proj Sig 0 $vsg)");
            for (Fx x : all()) {
                int pj = x.val("(proj Prod 1 pdB)");
                int fl = x.val("Bool.false");
                int tr = x.val("Bool.true");
                assertEquals(T, x.f.conv(pj, fl));
                assertEquals(Layout.POS, x.f.pair(pj, fl), "POS for the pair CONV was called with");
                assertEquals(F, x.f.conv(pj, tr));
                assertEquals(Layout.NEG, x.f.pair(pj, tr), "NEG for the pair CONV was called with");
                // ... and not for the pair in hand after the restart
                int px = x.val("(proj Prod 0 pdX)");
                int f72 = x.val("(f (lit 72))");
                assertEquals(F, x.f.conv(px, f72));
                assertEquals(Layout.NEG, x.f.pair(px, f72));
                assertEquals(0, x.f.pair(x.val("(f (lit 71))"), f72), "not for the pair in hand");
                int f71 = x.val("(f (lit 71))");
                int py = x.val("(proj Prod 0 pdX)");
                assertEquals(px, py);
                assertEquals(T, x.f.conv(px, f71));
                assertEquals(Layout.POS, x.f.pair(px, f71));
            }
        });
    }

    // ---- c8: application congruence -------------------------------------------------------------------------------

    @Test
    void c8_applicationCongruenceFirstToLast() {
        guarded(() -> {
            eq("(g $vx two)", "(g $vx (lit 2))");
            ne("(g $vx $vy)", "(g $vy $vx)");
            ne("(g $vx)", "(g $vx $vy)");
            ne("(f $vx)", "(g $vx $vx)");
            ne("(f Nat.zero)", "(f n0)");
            eq("($vfn two)", "($vfn (lit 2))");
            ne("($vfn two)", "(f two)");
            eq("((@ Prod.mk 0 0) Nat Bool two Bool.true)", "((@ Prod.mk 0 0) NatAlias Bool (lit 2) Bool.true)");
            ne("((@ Prod.mk 0 0) Nat Bool two Bool.true)", "((@ Prod.mk 0 0) Nat Bool (lit 3) Bool.true)");
            eq("((proj Fn 0 fnv) two)", "((proj Fn 0 fnv) (lit 2))"); // a projection as head
            ne("((proj Fn 0 fnv) two)", "((proj Fn 0 fnv) (lit 3))");
            // an applied projection is reduced by full core when the other side is a definition (R02 8.4 "only dt")
            eq("((proj Fn 0 fnD) n0)", "(idn n0)");
            ne("((proj Fn 0 fnD) n0)", "(idn Nat.zero)");
            for (Fx x : ab()) {
                // first to last: the first pair that differs ends the comparison
                int t1 = x.val("(h (f (lit 11)) (f (lit 12)))");
                int t2 = x.val("(h (f (lit 21)) (f (lit 22)))");
                x.mark();
                assertEquals(F, x.f.conv(t1, t2));
                assertEquals(Layout.NEG, x.f.pair(x.val("(f (lit 11))"), x.val("(f (lit 21))")), "the first pair was compared");
                assertEquals(0, x.f.pair(x.val("(f (lit 12))"), x.val("(f (lit 22))")), "the second was not");
            }
            // failures are not facts about related pairs (no closure): the same question, the same answer
            ne("(f Nat.zero)", "(f n0)");
            eq("(f two)", "(f (lit 2))");
            ne("(f Nat.zero)", "(f n0)");
        });
    }

    // ---- c9: eta -----------------------------------------------------------------------------------------------------

    @Test
    void c9_functionEta() {
        guarded(() -> {
            eq("f", "(fun (x : Nat) (f x))");
            eq("g", "(fun (x : Nat) (y : Nat) (g x y))");
            eq("(g n0)", "(fun (y : Nat) (g n0 y))");
            ne("f", "(fun (x : Nat) (f Nat.zero))");
            ne("f", "(fun (x : Nat) x)");
            eq("fa", "(fun (x : Nat) (fa x))"); // the other side's type needs whnf to become a Pi
            eq("$vfn", "(fun (x : NatAlias) ($vfn x))");
            ne("$vfn", "(fun (x : Bool) ($vfn n0))"); // the domains must agree
            ne("n0", "(fun (x : Nat) x)"); // the type of the other side is not a Pi: F, not an error
            ne("((@ Prod.mk 0 0) Nat Bool n0)", "(fun (b : Bool) pr)"); // a partial constructor application is not expanded
            for (Fx x : all()) {
                x.mark();
                assertEquals(T, x.f.conv(x.val("h"), x.val("(fun (x : Nat) (y : Nat) (h x y))")));
                assertEquals(2, x.called(Op.ETA), "one expansion per binder");
                assertEquals(CTX.length, x.m.ssp);
            }
        });
    }

    // ---- c10: structure eta -------------------------------------------------------------------------------------------

    @Test
    void c10_structureEta() {
        guarded(() -> {
            eq("pr", "((@ Prod.mk 0 0) Nat Bool (proj Prod 0 pr) (proj Prod 1 pr))");
            ne("pr", "((@ Prod.mk 0 0) Nat Bool (proj Prod 0 pr) Bool.true)");
            eq("$vp", "((@ Prod.mk 0 0) Nat Bool (proj Prod 0 $vp) (proj Prod 1 $vp))");
            eq("$vsg", "(Sig.mk B (proj Sig 0 $vsg) (proj Sig 1 $vsg))");
            ne("$vsg", "(Sig.mk B (proj Sig 0 sg) (proj Sig 1 sg))");
            // the types are compared first: a constructor application of another instance is refused
            ne("pr", "((@ Prod.mk 0 0) Bool Bool (proj Prod 1 pr) (proj Prod 1 pr))");
            for (Fx x : ab()) {
                // a constructor without parameters and fields is "fully applied" as a bare constant: u0 = Unit.unit
                // is answered by structure eta (step 13), before the unit-like rule (step 15) is reached
                int u0 = x.val("u0");
                int uu = x.val("Unit.unit");
                x.mark();
                assertEquals(T, x.f.conv(u0, uu));
                assertEquals(1, x.called(Op.ETAS));
                assertEquals(0, x.called(Op.UNITL));
                assertTrue(Conv.etasShape(x.m, uu));
                assertTrue(!Conv.etasShape(x.m, x.val("((@ Prod.mk 0 0) Nat Bool n0)")), "partially applied");
                assertTrue(!Conv.etasShape(x.m, x.val("(Nat.succ $vx)")), "an NSUC cell is not a structure value");
                assertTrue(!Conv.etasShape(x.m, x.val("((@ List.nil 0) Nat)")), "List is recursive and has two constructors");
                assertTrue(!Conv.etasShape(x.m, x.val("IUnit.mk")), "an indexed family is not a structure");
            }
        });
    }

    // ---- c11: string literals -----------------------------------------------------------------------------------------

    @Test
    void c11_stringLiteralExpansion() {
        guarded(() -> {
            String a = "(Char.ofNat (lit 97))";
            String b = "(Char.ofNat (lit 98))";
            String nil = "((@ List.nil 0) Char)";
            eq("(str \"a\")", "(String.ofList ((@ List.cons 0) Char " + a + " " + nil + "))");
            eq("(str \"ab\")", "(String.ofList ((@ List.cons 0) Char " + a + " ((@ List.cons 0) Char " + b + " " + nil + ")))");
            ne("(str \"a\")", "(String.ofList ((@ List.cons 0) Char " + b + " " + nil + "))");
            ne("(str \"a\")", "(String.ofList " + nil + ")");
            eq("(str \"\")", "(String.ofList " + nil + ")");
            // one element per Unicode scalar value, not per byte: U+00E9 is 233
            eq("(str \"é\")", "(String.ofList ((@ List.cons 0) Char (Char.ofNat (lit 233)) " + nil + "))");
            for (Fx x : ab()) {
                int s = x.val("(str \"abd\")");
                int l = x.val("(String.ofList " + nil + ")");
                x.mark();
                assertEquals(F, x.f.conv(l, s));
                assertEquals(1, x.called(Op.STRX_F), "either direction; the expansion is definitive");
            }
        });
    }

    // ---- c12, c13: unit-like types, and F ---------------------------------------------------------------------------

    @Test
    void c12_unitLikeTypes() {
        guarded(() -> {
            for (Fx x0 : ab()) {
                x0.mark();
                assertEquals(T, x0.f.conv(x0.val("$ua"), x0.val("$ub")));
                assertEquals(1, x0.called(Op.UNITL));
            }
            eq("u0", "u1");
            eq("$ua", "$ub");
            eq("$ua", "u0");
            ne("iu0", "iu1"); // an indexed family is not unit-like
            ne("$ia", "$ib");
            ne("pr", "$vp"); // a structure with fields is not unit-like
            ne("$ua", "n0"); // the types are compared (bugs/unit-rec-rule)
            eq("(fun (x : Nat) $ua)", "(fun (x : Nat) $ub)");
            for (Fx x : ab()) {
                // F is the answer of the last rule: every rule before it was tried
                int d1 = x.val("(h $vx (lit 31))");
                int d2 = x.val("(h $vx (lit 32))");
                x.mark();
                assertEquals(F, x.f.conv(d1, d2));
                assertEquals(1, x.called(Op.PIRR));
                assertEquals(1, x.called(Op.CARGS));
                assertEquals(1, x.called(Op.UNITL));
                assertEquals(0, x.called(Op.ETA) + x.called(Op.ETAS) + x.called(Op.STRX_F));
            }
        });
    }

    // ---- rec: the pair table (ISA 5.4) --------------------------------------------------------------------------------

    @Test
    void rec_posForBothPairsNegOnlyForTheCalledPair() {
        guarded(() -> {
            for (Fx x : all()) {
                // T: POS for (t, s) and for the pair in hand when it was decided
                int t = x.val("(g twoAlias (f (lit 40)))");
                int s = x.val("(g two (f (lit 40)))");
                assertEquals(T, x.f.conv(t, s));
                assertEquals(Layout.POS, x.f.pair(t, s));
                assertEquals(Layout.POS, x.f.pair(x.val("twoAlias"), x.val("two")), "the argument pair");
                // a second comparison is a hit: no tick, no event
                long ticks = x.m.ticks();
                x.mark();
                assertEquals(T, x.f.conv(s, t));
                assertEquals(ticks, x.m.ticks());
                assertEquals(0, x.called(Op.CONV));
                // F: NEG, also a hit
                int u = x.val("(g two (f (lit 41)))");
                assertEquals(F, x.f.conv(t, u));
                assertEquals(Layout.NEG, x.f.pair(t, u));
                ticks = x.m.ticks();
                assertEquals(F, x.f.conv(u, t));
                assertEquals(ticks, x.m.ticks());
                assertEquals(F, Conv.convFast(x.m, t, u));
                assertEquals(T, Conv.convFast(x.m, t, s));
                assertEquals(Op.MISS, Conv.convFast(x.m, s, x.val("(g two (f (lit 42)))")));
            }
        });
    }

    @Test
    void rec_negIsNotWrittenForALambdaSide() {
        guarded(() -> {
            for (Fx x : all()) {
                int l1 = x.val("(fun (x : Nat) (g x (lit 50)))");
                int l2 = x.val("(fun (x : Nat) (g x (lit 51)))");
                assertEquals(F, x.f.conv(l1, l2));
                assertEquals(0, x.f.pair(l1, l2), "both sides are lambdas");
                int fn = x.val("f");
                int l3 = x.val("(fun (x : Nat) (g x (lit 52)))");
                assertEquals(F, x.f.conv(fn, l3));
                assertEquals(0, x.f.pair(fn, l3), "one side is a lambda");
                assertEquals(F, x.f.conv(l3, fn));
                assertEquals(0, x.f.pair(fn, l3));
                // a Pi is not a lambda: NEG is written
                int p1 = x.val("(pi (x : Nat) ((@ Eq 1) Nat x (lit 50)))");
                int p2 = x.val("(pi (x : Nat) ((@ Eq 1) Nat x (lit 51)))");
                assertEquals(F, x.f.conv(p1, p2));
                assertEquals(Layout.NEG, x.f.pair(p1, p2));
                // T is recorded for lambdas too
                int l4 = x.val("(fun (x : NatAlias) (g x (lit 50)))");
                assertEquals(T, x.f.conv(l1, l4));
                assertEquals(Layout.POS, x.f.pair(l1, l4));
            }
        });
    }

    /**
     * ER (R02 8.9): while the type of an {@code eagerReduce _ _} argument is compared, the closed-term guards of
     * step 2 and of the Nat folding are lifted. NEG is neither written nor read under ER; POS is (m_success).
     */
    @Test
    void rec_negIsNeitherWrittenNorReadUnderEagerReduce() {
        guarded(() -> {
            for (Fx x : all()) {
                Machine m = x.m;
                // Nat.mul is opaque here: on an open term the equation holds only by folding the literal arguments
                int open = x.val("(Nat.mul ((fun (y : Nat) (lit 2)) vx) (lit 3))");
                int six = x.val("(lit 6)");
                assertTrue((Values.flags(m, open) & Layout.FL_OPEN) != 0);
                assertEquals(F, x.f.conv(open, six));
                assertEquals(Layout.NEG, x.f.pair(open, six));
                m.ER = 1;
                try {
                    assertEquals(Op.MISS, Conv.convFast(m, open, six), "NEG is not read under ER");
                    assertEquals(T, x.f.conv(open, six), "under ER the fold is done");
                    assertEquals(Layout.POS | Layout.NEG, x.f.pair(open, six));
                    int seven = x.val("(lit 7)");
                    assertEquals(F, x.f.conv(open, seven));
                    assertEquals(0, x.f.pair(open, seven), "NEG is not written under ER");
                    // step 2 under ER: an open term against Bool.true is evaluated
                    int beq = x.val("(Nat.beq ((fun (y : Nat) (lit 2)) vx) (lit 2))");
                    assertEquals(T, x.f.conv(beq, x.val("Bool.true")));
                } finally {
                    m.ER = 0;
                }
                assertEquals(T, x.f.conv(open, six), "POS wins over NEG (the official m_success is consulted first)");
                assertEquals(T, Conv.convFast(m, open, six));
            }
        });
    }

    // ---- CARGS (v0: the official order, every position SAME) -----------------------------------------------------------

    @Test
    void cargs_officialOrderPerPolicy() {
        guarded(() -> {
            for (Fx x : ab()) { // v0 only: step 5 reorders (quick refutation, pass B)
                Machine m = x.m;
                int a = x.val("(h (f (lit 61)) (f (lit 62)))");
                int b = x.val("(h (f (lit 63)) (f (lit 64)))");
                int f61 = x.val("(f (lit 61))");
                int f62 = x.val("(f (lit 62))");
                int f63 = x.val("(f (lit 63))");
                int f64 = x.val("(f (lit 64))");
                // SAMEDEF: last to first (is_def_eq_args): the last pair is compared and refutes
                assertEquals(F, x.f.run(Cargs.cargs(m, a, b, Op.POL_SAMEDEF, 0)));
                assertEquals(Layout.NEG, x.f.pair(f62, f64));
                assertEquals(0, x.f.pair(f61, f63));
                // APP: first to last (is_def_eq_app)
                assertEquals(F, x.f.run(Cargs.cargs(m, a, b, Op.POL_APP, 0)));
                assertEquals(Layout.NEG, x.f.pair(f61, f63));
                // all pairs identical: a leaf (no tick, no event)
                long ticks = m.ticks();
                x.mark();
                assertEquals(T, x.f.run(Cargs.cargs(m, a, a, Op.POL_APP, 0)));
                assertEquals(ticks, m.ticks());
                assertEquals(0, x.called(Op.CARGS));
                // identical pairs are skipped, the others compared
                int c = x.val("(h (f (lit 61)) (f two))");
                int d = x.val("(h (f (lit 61)) (f (lit 2)))");
                x.mark();
                assertEquals(T, x.f.run(Cargs.cargs(m, c, d, Op.POL_APP, 0)));
                assertEquals(T, x.f.run(Cargs.cargs(m, c, d, Op.POL_SAMEDEF, 0)));
                // different lengths: APP answers F before any comparison; SAMEDEF walks from the last argument
                int e = x.val("(h (f (lit 65)))");
                int g = x.val("(h (f (lit 66)) (f (lit 67)))");
                assertEquals(F, x.f.run(Cargs.cargs(m, e, g, Op.POL_APP, 0)));
                assertEquals(0, x.f.pair(x.val("(f (lit 65))"), x.val("(f (lit 67))")));
                assertEquals(F, x.f.run(Cargs.cargs(m, e, g, Op.POL_SAMEDEF, 0)));
                assertEquals(Layout.NEG, x.f.pair(x.val("(f (lit 65))"), x.val("(f (lit 67))")), "aligned at the end");
                int e2 = x.val("(h (f (lit 67)))");
                assertEquals(F, x.f.run(Cargs.cargs(m, e2, g, Op.POL_SAMEDEF, 0)), "the common suffix agrees, the arity does not");
            }
        });
    }
}
