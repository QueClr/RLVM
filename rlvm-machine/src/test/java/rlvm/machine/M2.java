package rlvm.machine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Helpers of the M2 tests (family EV): judgments the fixture has no method for, probes over the records, traces. */
final class M2 {
    private M2() {}

    private static Fixture shared;

    /**
     * The prelude with a fresh machine session, an empty context, no switch, no debug mode and the given depth bound.
     * One store serves every test of the JVM (building it generates every recursor with the reference kernel); tests
     * of one JVM run one after the other.
     */
    static synchronized Fixture fresh(int hostDepth) {
        if (shared == null) {
            shared = Fixture.prelude();
        }
        reset(shared, hostDepth, "");
        return shared;
    }

    /**
     * A fresh machine session in the same fixture (the store, the overlay and so every term id stay): empty context,
     * the given depth bound and staging switches, no debug mode, no step limit.
     */
    static void reset(Fixture f, int hostDepth, String switches) {
        f.seal();
        f.closeContext();
        f.m.reset();
        f.m.beginDecl(Integer.MAX_VALUE, -1, Op.FUEL_UNLIMITED, Long.MAX_VALUE);
        f.m.sw = Debug.parseSwitches(switches);
        f.m.debug = 0;
        f.m.stats = false;
        f.m.hostDepth = hostDepth;
    }

    /** ARGV(term) under the fixture's context. */
    static int argv(Fixture f, int term) {
        return f.run(Eval.argv(f.m, term, 0, f.m.ep, 0, 0));
    }

    /** DOM(v). */
    static int dom(Fixture f, int v) {
        return f.run(Eval.dom(f.m, v, 0));
    }

    /** True if nothing is suspended and the machine stacks hold only the fixture's context (I-STACKS, I-SCOPE). */
    static boolean settled(Fixture f) {
        Machine m = f.m;
        return m.wp == 0 && m.sp == m.S.length && m.xp == 0 && m.rp == 0 && m.ER == 0 && m.ep == m.ssp;
    }

    static List<Integer> list(int... xs) {
        List<Integer> l = new ArrayList<>();
        for (int x : xs) {
            l.add(x);
        }
        return l;
    }

    /** Number of records of the given kind in the session. */
    static int count(Machine m, int kind) {
        int n = 0;
        for (int r = m.vbase; r < m.vp; r++) {
            if (Layout.kindOf(m.vh[r << Layout.REC_SHIFT]) == kind) {
                n++;
            }
        }
        return n;
    }

    /** The first record of the given kind whose word a is {@code a}, or 0. */
    static int find(Machine m, int kind, int a) {
        for (int r = m.vbase; r < m.vp; r++) {
            int o = r << Layout.REC_SHIFT;
            if (Layout.kindOf(m.vh[o]) == kind && m.vh[o + Layout.A] == a) {
                return r;
            }
        }
        return 0;
    }

    /** The value printed with every suspension forced (to a depth of 8). */
    static String forced(Fixture f, int v) {
        StringBuilder sb = new StringBuilder();
        forced(sb, f, v, 8);
        return sb.toString();
    }

    private static void forced(StringBuilder sb, Fixture f, int v, int depth) {
        v = f.force(v);
        if (depth > 0 && v > Layout.HOLE && Values.kind(f.m, v) == Layout.NEU) {
            sb.append('(');
            forced(sb, f, Values.a(f.m, v), depth - 1);
            for (int a : f.run2slots(Values.b(f.m, v))) {
                sb.append(' ');
                forced(sb, f, a, depth - 1);
            }
            sb.append(')');
        } else {
            sb.append(f.show(v));
        }
    }

    /** The machine failure {@code body} ends with. */
    static Fixture.MachineFailure failure(Runnable body) {
        try {
            body.run();
        } catch (Fixture.MachineFailure e) {
            return e;
        }
        throw new AssertionError("the judgment completed");
    }

    /** The call events (ops) of {@code body}, in order (ISA 1.3 frame trace; H4 step 2b). */
    static int[] trace(Fixture f, Runnable body) {
        Machine m = f.m;
        boolean stats = m.stats;
        int debug = m.debug;
        m.stats = true;
        m.debug |= Op.DBG_TRACE;
        m.traceN = 0;
        try {
            body.run();
            return Arrays.copyOf(m.trace, m.traceN);
        } finally {
            m.stats = stats;
            m.debug = debug;
        }
    }

    /** The session's records and run words in use, for comparing two machines word by word. */
    static int[] records(Machine m) {
        return Arrays.copyOfRange(m.vh, m.vbase << Layout.REC_SHIFT, m.vp << Layout.REC_SHIFT);
    }

    static int[] runs(Machine m) {
        return Arrays.copyOfRange(m.rs, m.rbase, m.rsp);
    }
}
