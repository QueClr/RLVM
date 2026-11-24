package rlvm.machine;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import rlvm.api.MachineErrors;
import rlvm.store.Overlay;

/**
 * Stack safety of family EV (ISA section 9; convention H9, H12 item 3): a 913,500-element list literal and
 * 1,000,000-deep application, binder and mixed nests, evaluated on a thread with a 512 KiB stack under the depth
 * bounds 64 and 0 (and by the inert-nest loop), with identical value ids, records, runs and ticks in every
 * configuration (group {@code slow}: {@code scripts/machine-sandbox.sh test M2 -Dgroups=slow -DexcludedGroups= -Dtest=M2DeepTest}).
 * The same nests at a depth of 5,000 are in the fast group.
 */
class M2DeepTest {

    /** One way of running the machine: depth bound and staging switches. */
    record Config(int hostDepth, String switches) {}

    static final Config[] CONFIGS = {new Config(Op.HOST_DEPTH, ""), new Config(0, ""), new Config(Op.HOST_DEPTH, "inert"),
        new Config(0, "inert")};

    /** What a run leaves behind. */
    record Outcome(int result, long ticks, int records, int runWords, int recordHash, int runHash, long frames) {}

    interface Body {
        int run(Fixture f);
    }

    /** Runs {@code body} on a thread with a 512 KiB stack. */
    static int onSmallStack(Fixture f, Body body) {
        int[] out = new int[1];
        Throwable[] err = new Throwable[1];
        Thread t = new Thread(null, () -> {
            try {
                out[0] = body.run(f);
            } catch (Throwable e) {
                err[0] = e;
            }
        }, "m2-512k", 512 * 1024);
        t.start();
        try {
            t.join();
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
        if (err[0] instanceof Fixture.MachineFailure) {
            throw (Fixture.MachineFailure) err[0];
        }
        if (err[0] != null) {
            throw new AssertionError("on a 512 KiB stack: " + err[0], err[0]);
        }
        return out[0];
    }

    /** Runs {@code body} in a fresh session of {@code f} under every configuration; all outcomes must be equal. */
    static Outcome everyConfig(Fixture f, Body body) {
        Outcome first = null;
        for (Config c : CONFIGS) {
            M2.reset(f, c.hostDepth(), c.switches());
            Machine m = f.m;
            long t0 = m.ticks();
            long fr0 = m.framesWritten;
            int r = onSmallStack(f, body);
            assertTrue(M2.settled(f), "stacks restored under " + c);
            Outcome o = new Outcome(r, m.ticks() - t0, m.vp - m.vbase, m.rsp - m.rbase, java.util.Arrays.hashCode(M2.records(m)),
                    java.util.Arrays.hashCode(M2.runs(m)), 0);
            if (first == null) {
                first = o;
            } else {
                assertEquals(first, o, "identical value ids, records, runs and ticks under " + c);
            }
            if (c.hostDepth() == 0 && c.switches().isEmpty()) {
                assertTrue(m.framesWritten > fr0, "D = 0 writes frames");
            }
        }
        return first;
    }

    // ---- term builders (iterative: the overlay's constructors) --------------------------------------------

    /** {@code head (head (.. (head leaf)))}, depth applications. */
    static int appNest(Fixture f, int head, int leaf, int depth) {
        Overlay ov = f.ctx.overlay;
        int t = leaf;
        for (int i = 0; i < depth; i++) {
            t = ov.mkApp(head, t);
        }
        return t;
    }

    /** {@code [lit 0, lit 1, .., lit (n - 1)]} as {@code List.cons.{0} Nat (lit i) ..}: n nested applications of three arguments. */
    static int listLiteral(Fixture f, int n) {
        Overlay ov = f.ctx.overlay;
        int nat = f.term("Nat");
        int cons = ov.mkApp(f.term("(@ List.cons 0)"), nat);
        int t = ov.mkApp(f.term("(@ List.nil 0)"), nat);
        for (int i = n - 1; i >= 0; i--) {
            t = ov.mkApp(ov.mkApp(cons, ov.mkNatLit(i)), t);
        }
        return t;
    }

    /**
     * {@code (fun x0 => (fun x1 => .. (fun x_(n-1) => x_(n-1)) (f x_(n-2)) ..) (f x0)) (lit 0)}: n nested beta redexes in
     * evaluated position; each binder is read once, by the argument of the next redex.
     */
    static int binderNest(Fixture f, int n) {
        Overlay ov = f.ctx.overlay;
        int nat = f.term("Nat");
        int fx = ov.mkApp(f.term("f"), ov.mkBVar(0));
        int t = ov.mkBVar(0);
        for (int i = 1; i < n; i++) {
            t = ov.mkApp(ov.mkLam(nat, t), fx);
        }
        return ov.mkApp(ov.mkLam(nat, t), ov.mkNatLit(0));
    }

    /** n levels in evaluated position, by level: let body, beta body, struct of a projection, head of an application. */
    static int mixedNest(Fixture f, int n) {
        Overlay ov = f.ctx.overlay;
        int nat = f.term("Nat");
        int n0 = f.term("n0");
        int prod = f.cid("Prod");
        int t = f.term("pr");
        for (int i = 0; i < n; i++) {
            switch (i & 3) {
                case 0:
                    t = ov.mkLet(nat, n0, t);
                    break;
                case 1:
                    t = ov.mkApp(ov.mkLam(nat, t), n0);
                    break;
                case 2:
                    t = ov.mkProj(prod, 0, t);
                    break;
                default:
                    t = ov.mkApp(t, n0);
                    break;
            }
        }
        return t;
    }

    /** {@code id' (id' (.. (id' leaf)))} with {@code id' = fun x => x}: every argument is active, so the nest is a chain of suspensions. */
    static int lazyNest(Fixture f, int leaf, int depth) {
        return appNest(f, f.term("(fun (x : Nat) x)"), leaf, depth);
    }

    // ---- checks on results (iterative) ----------------------------------------------------------------------

    /** Length of the spine chain {@code v = h a1 .. (h b1 .. (.. leaf))} following the last argument. */
    static int chainLength(Machine m, int v) {
        int n = 0;
        while (v > Layout.HOLE && Values.kind(m, v) == Layout.NEU) {
            int run = Values.b(m, v);
            v = Runs.arg(m, run, Runs.len(m, run) - 1);
            n++;
        }
        return n;
    }

    // ---- the acceptance sizes ---------------------------------------------------------------------------------

    @Test
    @Tag("slow")
    void listLiteralOf913500Elements() {
        Fixture f = Fixture.prelude0();
        int n = 913_500;
        int t = listLiteral(f, n);
        Outcome o = everyConfig(f, g -> {
            int v = g.eval(t);
            assertEquals(n + 1, chainLength(g.m, v), "List.cons applications, then List.nil Nat");
            return v;
        });
        assertEquals(n + 1, o.ticks(), "one tick per application node");
        M2.reset(f, Op.HOST_DEPTH, "");
        assertArrayEquals(new int[] {Op.EVAL}, M2.trace(f, () -> onSmallStack(f, g -> g.eval(t))), "the literal is one call event");
        f.close();
    }

    @Test
    @Tag("slow")
    void applicationNestOfOneMillion() {
        Fixture f = Fixture.prelude0();
        int t = appNest(f, f.term("f"), f.term("n0"), 1_000_000);
        everyConfig(f, g -> {
            int v = g.eval(t);
            assertEquals(1_000_000, chainLength(g.m, v));
            return v;
        });
        f.close();
    }

    @Test
    @Tag("slow")
    void binderNestOfOneMillion() {
        Fixture f = Fixture.prelude0();
        int t = binderNest(f, 1_000_000);
        everyConfig(f, g -> {
            int v = g.eval(t);
            assertEquals(999_999, chainLength(g.m, v), "f^(n-1) 0");
            return v;
        });
        f.close();
    }

    @Test
    @Tag("slow")
    void mixedNestOfOneMillion() {
        Fixture f = Fixture.prelude0();
        int t = mixedNest(f, 1_000_000);
        everyConfig(f, g -> g.eval(t));
        f.close();
    }

    @Test
    @Tag("slow")
    void suspensionChainOfOneMillion() {
        Fixture f = Fixture.prelude0();
        int t = lazyNest(f, f.term("n0"), 1_000_000);
        everyConfig(f, g -> {
            int s = g.eval(t);
            assertEquals(Layout.SUSP, Values.kind(g.m, s), "the outermost redex returns its delayed argument");
            int v = g.force(s);
            assertEquals("n0", g.show(v));
            return v;
        });
        f.close();
    }

    // ---- the same at a depth of 5,000 (fast group) ------------------------------------------------------------

    @Test
    @Tag("fast")
    void deepNestsAt5000() {
        Fixture f = M2.fresh(Op.HOST_DEPTH);
        int n = 5_000;
        int list = listLiteral(f, n);
        int app = appNest(f, f.term("f"), f.term("n0"), n);
        int binders = binderNest(f, n);
        int mixed = mixedNest(f, n);
        int lazy = lazyNest(f, f.term("n0"), n);
        everyConfig(f, g -> {
            Machine m = g.m;
            int a = g.eval(list);
            assertEquals(n + 1, chainLength(m, a));
            int b = g.eval(app);
            assertEquals(n, chainLength(m, b));
            int c = g.eval(binders);
            assertEquals(n - 1, chainLength(m, c));
            int d = g.eval(mixed);
            int e = g.force(g.eval(lazy));
            assertEquals("n0", g.show(e));
            return a * 31 + b * 17 + c * 7 + d;
        });
    }

    /** H12 item 3: a step budget that runs out inside a deep nest ends the judgment with E_STEPS and leaves the stacks clean. */
    @Test
    @Tag("fast")
    void aBudgetThatRunsOutInsideANest() {
        Fixture f = M2.fresh(Op.HOST_DEPTH);
        int n = 5_000;
        int[] terms = {listLiteral(f, n), binderNest(f, n), mixedNest(f, n)};
        for (Config c : CONFIGS) {
            M2.reset(f, c.hostDepth(), c.switches());
            for (int t : terms) {
                for (long budget : new long[] {1, 3, 4096, 4097}) {
                    f.m.beginDecl(Integer.MAX_VALUE, -1, budget, Long.MAX_VALUE);
                    long t0 = f.m.ticks();
                    f.expectFail(MachineErrors.E_STEPS, () -> f.eval(t));
                    assertEquals(budget, f.m.ticks() - t0, "the budget is exact under " + c);
                    assertTrue(M2.settled(f), "stacks restored after the abort under " + c);
                }
            }
            f.m.beginDecl(Integer.MAX_VALUE, -1, Op.FUEL_UNLIMITED, Long.MAX_VALUE);
            assertEquals(n + 1, chainLength(f.m, f.eval(terms[0])), "the machine is usable afterwards");
        }
    }
}
