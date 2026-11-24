package rlvm.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.Locale;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import rlvm.store.DeclKind;
import rlvm.store.DeclTable;

/**
 * CPU time of family EV on a real export (not a gate; group {@code slow}):
 *
 * <pre>
 * scripts/machine-sandbox.sh test M2 -Dgroups=slow -DexcludedGroups= -Dtest=M2PerfTest [-Dm2.file=work/exports/init.ndjson] [-Dm2.rounds=3]
 * </pre>
 *
 * The workload is the one of the stepper spike (ISA 0.3, 8.2 acceptance) without readback: for every constant the
 * type is evaluated and its Pi telescope walked ({@code DOM}, {@code OPEN}, {@code INST}, {@code FORCE} per binder);
 * for every definition the value is evaluated and its lambda telescope walked ({@code DOM}, {@code OPEN},
 * {@code APPLY}); then every suspension reachable from the body's value is forced (the evaluating half of a readback).
 * Sessions are reset at 16 MiB. The environment pass is not run (no derived inductive facts), so a
 * projection of a constructor application stays a PROJ cell and {@code Nat.succ} stays an application; nothing
 * else in this family depends on them. Each configuration prints one line: CPU time of the worker thread (minimum
 * over the rounds), ticks, call events by op, frames written, records and runs made, tabling hits.
 */
class M2PerfTest {

    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();

    private static final String[][] CONFIGS = {
        // name, depth bound, switches
        {"D=64", "64", ""}, {"D=unbounded", "max", ""}, {"D=64 inert", "64", "inert"}, {"D=0", "0", ""},
        {"D=64 gated", "64", "gated"}, {"D=64 gated,inert", "64", "gated,inert"},
    };

    private long ticks;
    private long records;
    private long runWords;
    private long resets;
    private long errors;
    private long binders;

    @Test
    @Tag("slow")
    void evalAndTelescopesOfAnExport() throws Exception {
        String file = System.getProperty("m2.file", "work/exports/init.ndjson");
        assumeTrue(Fixture.exists(file), file + " is not available");
        int rounds = Integer.getInteger("m2.rounds", 3);
        Fixture g = Fixture.load(file);
        DeclTable d = g.store.decls();
        int n = d.constantCount();
        System.out.printf(Locale.ROOT, "M2PERF file %s: %d constants%n", file, n);
        long[] best = new long[CONFIGS.length];
        java.util.Arrays.fill(best, Long.MAX_VALUE);
        String[] detail = new String[CONFIGS.length];
        Throwable[] failure = new Throwable[1];
        // one worker thread with a deep stack, so that the unbounded configuration can run at all
        Thread worker = new Thread(null, () -> {
            try {
                for (int round = 0; round <= rounds; round++) {
                    for (int c = 0; c < CONFIGS.length; c++) {
                        boolean stats = round == rounds; // the last round counts events and is not timed
                        long cpu = pass(g, d, n, CONFIGS[c], stats);
                        if (stats) {
                            detail[c] = describe(g.m);
                        } else if (round > 0 || rounds == 1) {
                            best[c] = Math.min(best[c], cpu); // round 0 warms the compilers up
                        }
                    }
                }
            } catch (Throwable e) {
                failure[0] = e;
            }
        }, "m2-perf", 1L << 30);
        worker.start();
        worker.join();
        if (failure[0] != null) {
            throw new AssertionError(failure[0]);
        }
        for (int c = 0; c < CONFIGS.length; c++) {
            System.out.printf(Locale.ROOT, "M2PERF %-18s cpu %8.1f ms  %s%n", CONFIGS[c][0], best[c] / 1e6, detail[c]);
        }
        assertEquals(0, errors, "no judgment ended with a signal");
        g.close();
    }

    private String describe(Machine m) {
        long calls = m.calls[Op.EVAL] + m.calls[Op.FORCE] + m.calls[Op.APPLY] + m.calls[Op.INST] + m.calls[Op.DOM];
        return String.format(Locale.ROOT,
                "ticks %d  calls EVAL %d FORCE %d APPLY %d INST %d DOM %d  frames %d (%.5f per call)  records %d  run words %d"
                        + "  hits %d  lazy %d  binders %d  forced %d  resets %d",
                ticks, m.calls[Op.EVAL], m.calls[Op.FORCE], m.calls[Op.APPLY], m.calls[Op.INST], m.calls[Op.DOM],
                m.framesWritten, calls == 0 ? 0.0 : (double) m.framesWritten / calls, records, runWords, m.x2.hits, m.x2.lazy,
                binders, forced, resets);
    }

    /** One pass over every constant; returns the CPU time of this thread in ns. */
    private long pass(Fixture g, DeclTable d, int n, String[] config, boolean stats) {
        Machine m = g.m;
        m.reset();
        g.ctx.reset();
        m.sw = Debug.parseSwitches(config[2]);
        m.debug = 0;
        m.hostDepth = config[1].equals("max") ? Op.HOST_DEPTH_UNBOUNDED : Integer.parseInt(config[1]);
        m.stats = stats;
        java.util.Arrays.fill(m.calls, 0);
        m.framesWritten = 0;
        m.x2.hits = 0;
        m.x2.lazy = 0;
        ticks = 0;
        records = 0;
        runWords = 0;
        resets = 0;
        binders = 0;
        forced = 0;
        seenSession = -1;
        long t0 = m.ticks();
        long cpu0 = THREADS.getCurrentThreadCpuTime();
        for (int cid = 1; cid <= n; cid++) {
            if (m.engineBytes() > (16L << 20)) {
                records += m.vp - m.vbase;
                runWords += m.rsp - m.rbase;
                resets++;
                m.reset();
            }
            m.beginDecl(Integer.MAX_VALUE, d.unit(cid), Op.FUEL_UNLIMITED, Long.MAX_VALUE);
            int type = d.type(cid);
            if (type > 0) {
                telescope(m, type);
            }
            int value = d.value(cid);
            if (value > 0 && d.kind(cid) == DeclKind.DEFN) {
                telescope(m, value);
            }
        }
        long cpu = THREADS.getCurrentThreadCpuTime() - cpu0;
        ticks = m.ticks() - t0;
        records += m.vp - m.vbase;
        runWords += m.rsp - m.rbase;
        return cpu;
    }

    /** EVAL(t), then under each leading binder of the value: DOM, OPEN, INST (Pi) or APPLY (lambda), FORCE. */
    private void telescope(Machine m, int t) {
        int v = m.drive(Eval.eval(m, t, 0, 0, 0, 0));
        int opened = 0;
        while (v < Op.SIG) {
            v = m.drive(Eval.force(m, v, 0));
            if (v >= Op.SIG || v <= Layout.HOLE) {
                break;
            }
            int kind = Values.kind(m, v);
            if (kind != Layout.PI && kind != Layout.LAM) {
                break;
            }
            int dom = m.drive(Eval.dom(m, v, 0));
            if (dom >= Op.SIG) {
                v = dom;
                break;
            }
            int x = Scope.open(m, dom);
            if (x >= Op.SIG) {
                v = x;
                break;
            }
            opened++;
            if (kind == Layout.PI) {
                v = m.drive(Eval.inst(m, v, x, 0));
            } else {
                int xb = m.xp;
                m.ensureScratch(1);
                m.X[xb] = x;
                m.xp = xb + 1;
                v = m.drive(Eval.apply(m, v, xb, 1, 0));
                m.xp = xb;
            }
        }
        binders += opened;
        if (v < Op.SIG && DEEP) {
            v = deepForce(m, v);
        }
        if (v >= Op.SIG) {
            if (errors++ < 5) {
                System.out.println("M2PERF signal " + rlvm.api.MachineErrors.name(m.err) + " on term " + t);
            }
            m.abandon();
        } else {
            Scope.close(m, opened);
        }
    }

    /**
     * The readback part of the workload without building terms: every suspension reachable from v through spines,
     * projections and offsets is forced (each record once per session, at most 20,000 per declaration), which runs the
     * delayed evaluations of the active arguments.
     * @return v, or a signal
     */
    private int deepForce(Machine m, int v) {
        if (seenSession != m.resets || seen.length < m.vh.length >>> Layout.REC_SHIFT) {
            seen = new boolean[m.vh.length >>> Layout.REC_SHIFT];
            seenSession = m.resets;
        }
        int sp = 0;
        stack[sp++] = v;
        int budget = 20_000;
        while (sp > 0 && budget-- > 0) {
            int x = m.drive(Eval.force(m, stack[--sp], 0));
            if (x >= Op.SIG) {
                return x;
            }
            forced++;
            if (x <= Layout.HOLE) {
                continue;
            }
            if (x >= seen.length) {
                seen = java.util.Arrays.copyOf(seen, m.vh.length >>> Layout.REC_SHIFT);
            }
            if (seen[x]) {
                continue;
            }
            seen[x] = true;
            int kind = Values.kind(m, x);
            if (kind == Layout.NEU) {
                int run = Values.b(m, x);
                int n = Runs.len(m, run);
                if (sp + n + 1 > stack.length) {
                    stack = java.util.Arrays.copyOf(stack, Math.max(stack.length * 2, sp + n + 1));
                }
                stack[sp++] = Values.a(m, x);
                for (int i = 0; i < n; i++) {
                    stack[sp++] = Runs.arg(m, run, i);
                }
            } else if (kind == Layout.PROJ || kind == Layout.NSUC) {
                if (sp == stack.length) {
                    stack = java.util.Arrays.copyOf(stack, stack.length * 2);
                }
                stack[sp++] = Values.a(m, x);
            }
        }
        return v;
    }

    private static final boolean DEEP = !"false".equals(System.getProperty("m2.deep"));
    private boolean[] seen = new boolean[0];
    private long seenSession = -1;
    private int[] stack = new int[1024];
    private long forced;

    /** A quick self-check of the workload on a small file (fast group): no signal, the same ticks under D = 64 and 0. */
    @Test
    @Tag("fast")
    void theWorkloadRunsOnASmallExport() throws IOException {
        String file = "work/arena-site/tests/good/init-prelude.ndjson";
        assumeTrue(Fixture.exists(file), file + " is not available");
        Fixture g = Fixture.load(file);
        DeclTable d = g.store.decls();
        int n = Math.min(d.constantCount(), 400);
        pass(g, d, n, CONFIGS[0], true);
        long base = ticks;
        long baseRecords = records;
        for (int c = 2; c < 4; c++) {
            pass(g, d, n, CONFIGS[c], true);
            assertEquals(base, ticks, CONFIGS[c][0]);
            assertEquals(baseRecords, records, CONFIGS[c][0]);
        }
        assertEquals(0, errors);
        g.close();
    }
}
