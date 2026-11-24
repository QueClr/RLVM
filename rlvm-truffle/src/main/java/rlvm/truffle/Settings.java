package rlvm.truffle;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The settings of the T2 tier: system properties {@code rlvm.t2.*}, read once per engine (README.md).
 * The tier itself is selected by the machine's staging switch {@code t2}
 * ({@code -X machine.switches=code,casetrees,t2}); these only tune it.
 *
 * <pre>
 * rlvm.t2.rent=F       rent-to-own factor: a block is compiled once the CPU time it has spent in the base
 *                      tier is at least F times its estimated compile cost (default 1.0; ISA 8.5)
 * rlvm.t2.share=F      governor: compiler CPU time (spent + estimated for the candidate) at most this share
 *                      of the process CPU time so far (default 0.02)
 * rlvm.t2.max=N        at most N promoted blocks per engine (default 256)
 * rlvm.t2.maxInstr=N   a block of more than N instructions is never promoted (default 256: Graal refuses
 *                      to install the code of very large blocks, "code is too large")
 * rlvm.t2.period=N     heat listener period: entries of one block by one worker (default 1024)
 * rlvm.t2.timing=N     one entry in N is timed by the machine's heat accounting (default 64)
 * rlvm.t2.at=N         N > 0: promote at N entries, whatever the rent and the governor say (tests, sweeps)
 * rlvm.t2.cost0=MS, rlvm.t2.cost1=MS   compile cost model before anything was compiled: cost0 + cost1 per
 *                      instruction, CPU milliseconds (defaults 3.0 and 0.15; then learned from the compilations)
 * rlvm.t2.roles=LIST   promotable roles: tele,body,rule,rules,sub (default all)
 * rlvm.t2.slots=B      registers in frame slots (default true; false: every register stays in the window)
 * rlvm.t2.nodes=B      own code per instruction (default true; false: every instruction through Tier.step)
 * rlvm.t2.resume=B     compile the resume points of a block as entries (default true; false: a resumed
 *                      activation of a promoted block continues in the stepper)
 * rlvm.t2.sync=B       compile synchronously: the worker that crosses the threshold waits (tests)
 * rlvm.t2.trace=B      one line per promotion / compilation on stderr
 * rlvm.t2.report=FILE  write the report (README "Report") to FILE at every change and at exit
 * rlvm.t2.opt.X=V      engine or compiler option X for the Truffle engine (e.g. rlvm.t2.opt.engine.TraceCompilation=true)
 * </pre>
 */
final class Settings {
    final double rent;
    final double share;
    final int max;
    final int maxInstr;
    final int period;
    final int timing;
    final int at;
    final double cost0Ms;
    final double cost1Ms;
    final int roles;
    final boolean slots;
    final boolean nodes;
    final boolean resume;
    final boolean sync;
    final boolean trace;
    final String report;
    final Map<String, String> engineOptions = new LinkedHashMap<>();

    static final int ROLE_SUB_BIT = 1 << 15;

    Settings() {
        rent = dbl("rlvm.t2.rent", 1.0);
        share = dbl("rlvm.t2.share", 0.02);
        max = Integer.getInteger("rlvm.t2.max", 256);
        maxInstr = Integer.getInteger("rlvm.t2.maxInstr", 256);
        period = Math.max(1, Integer.getInteger("rlvm.t2.period", 1024));
        timing = Integer.getInteger("rlvm.t2.timing", 64);
        at = Integer.getInteger("rlvm.t2.at", 0);
        cost0Ms = dbl("rlvm.t2.cost0", 3.0);
        cost1Ms = dbl("rlvm.t2.cost1", 0.15);
        roles = roles(System.getProperty("rlvm.t2.roles", "tele,body,rule,rules,sub"));
        slots = bool("rlvm.t2.slots", true);
        nodes = bool("rlvm.t2.nodes", true);
        resume = bool("rlvm.t2.resume", true);
        sync = bool("rlvm.t2.sync", false);
        trace = bool("rlvm.t2.trace", false);
        report = System.getProperty("rlvm.t2.report");
        for (String k : System.getProperties().stringPropertyNames()) {
            if (k.startsWith("rlvm.t2.opt.")) {
                engineOptions.put(k.substring("rlvm.t2.opt.".length()), System.getProperty(k));
            }
        }
    }

    private static double dbl(String key, double dflt) {
        String v = System.getProperty(key);
        if (v == null) {
            return dflt;
        }
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + "=" + v + ": not a number");
        }
    }

    private static boolean bool(String key, boolean dflt) {
        String v = System.getProperty(key);
        return v == null ? dflt : v.isEmpty() || v.equalsIgnoreCase("true") || v.equalsIgnoreCase("on") || v.equals("1");
    }

    private static int roles(String list) {
        int r = 0;
        for (String s : list.split(",")) {
            switch (s.trim()) {
                case "tele": r |= 1 << rlvm.machine.Op.ROLE_TELE; break;
                case "body": r |= 1 << rlvm.machine.Op.ROLE_BODY; break;
                case "rule": r |= 1 << rlvm.machine.Op.ROLE_RULE; break;
                case "rules": r |= 1 << rlvm.machine.Op.ROLE_RULES; break;
                case "sub": r |= ROLE_SUB_BIT; break;
                case "": break;
                default: throw new IllegalArgumentException("rlvm.t2.roles: unknown role '" + s + "'");
            }
        }
        return r;
    }
}
