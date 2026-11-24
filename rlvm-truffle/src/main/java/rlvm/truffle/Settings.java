package rlvm.truffle;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The settings of the T2 tier: system properties {@code rlvm.t2.*}, read once per engine (README.md).
 * The tier itself is selected by the machine's staging switch {@code t2}
 * ({@code -X machine.switches=code,casetrees,t2}); these only tune it.
 *
 * <pre>
 * rlvm.t2.mode=cpu|warm  the governor's profile; the single settings below override it.
 *                      cpu (default): the governor of ISA 8.5 - total CPU time is the metric, compilation is
 *                        charged: rent 1.0, share 0.02, max 256, period 1024, one compiler thread;
 *                      warm: the steady state is the metric and warm-up is not charged (OWNER-NOTES 12):
 *                        promotion by entry count without the heat accounting (rlvm.t2.count): at 20000 entries
 *                        and 2% of all activations (rlvm.t2.at, rlvm.t2.hot), at most 256 blocks, two compiler threads.
 * rlvm.t2.rent=F       rent-to-own factor: a block is compiled once the CPU time it has spent in the base
 *                      tier is at least F times its estimated compile cost (default 1.0; ISA 8.5)
 * rlvm.t2.share=F      governor: compiler CPU time (spent + estimated for the candidate) at most this share
 *                      of the process CPU time so far (default 0.02)
 * rlvm.t2.max=N        at most N promoted blocks per engine (default 256)
 * rlvm.t2.minInstr=N   a block of fewer than N instructions is never promoted (default 0; entering compiled code
 *                      costs about as much as the stepper spends on a block of a few instructions, NOTES-T2)
 * rlvm.t2.maxInstr=N   a block of more than N instructions is never promoted (default 256: Graal refuses
 *                      to install the code of very large blocks, "code is too large")
 * rlvm.t2.period=N     heat listener period: entries of one block by one worker (default 1024)
 * rlvm.t2.threads=N    compiler threads of the Truffle engine (default 1; ISA 8.5)
 * rlvm.t2.timing=N     one entry in N is timed by the machine's heat accounting (default 4096; 0 = never). A timed
 *                      entry times every block entered beneath it, so on deep evaluation the accounting's own
 *                      default of 64 times most entries (the size of that cost is not measured, NOTES-T2 5.1)
 * rlvm.t2.unit0=NS, rlvm.t2.unit1=NS   base-tier time of one entry of a block that has no timed sample yet:
 *                      unit0 + unit1 per instruction, nanoseconds (defaults 20 and 10; a model, not a measurement)
 * rlvm.t2.at=N         N > 0: promote at N entries, whatever the rent and the governor say (tests, sweeps; the
 *                      threshold of rlvm.t2.count: default 20000 in mode warm, 1000 with rlvm.t2.count alone)
 * rlvm.t2.hot=N        count mode: promote a block only while it has at least N per mille of ALL activations of the
 *                      engine so far, compiled ones included (default 20 = 2% in mode warm, else 0 = no such test).
 *                      The entry count is cumulative, so without this test every block that is entered at all
 *                      crosses any threshold in the end; on library corpora that means hundreds of lukewarm blocks,
 *                      minutes of compilation and a slower steady state (NOTES-T3 section 1).
 * rlvm.t2.count=B      promotion by entry count WITHOUT the machine's heat accounting (default: true in mode warm):
 *                      the tier counts the activations it does not take, per machine, and a block is promoted when
 *                      the sum over the machines reaches rlvm.t2.at (and rlvm.t2.hot holds). An activation that compiled code takes is not
 *                      counted or timed by anybody. With false the heat accounting of the machine is switched on
 *                      (it costs about 11% of a warm pass on reachability on the JVM, NOTES-T3 section 2).
 * rlvm.t2.ahead=N       EXPERIMENT, REJECTED BY MEASUREMENT (NOTES-T3 section 6; default 0 = off; 2 is the setting that was
 *                      measured): run-ahead of the interning probes in compiled blocks: before a stretch of
 *                      value construction with at least N interning instructions (MKAPP, MKLAM, MKPI, MKSUSP) the block loads
 *                      the table slots of their probes early, so that the cache misses overlap (reads only; NOTES-T3 section 4).
 *                      rlvm.t2.aheadMaxInstr=N (default 128): not in blocks with more instructions.
 *                      rlvm.t2.aheadExact=B (default true): the early-loaded table word is also read, so that a
 *                      record that exists already is known as such (false: every key is predicted to be new).
 *                      rlvm.t2.aheadCheck=true counts how often a predicted record is the one the operation returns.
 * rlvm.t2.cost0=MS, rlvm.t2.cost1=MS   compile cost model before anything was compiled: cost0 + cost1 per
 *                      instruction, CPU milliseconds (defaults 3.0 and 2.5: measured 2.2 - 3.1 ms per instruction;
 *                      then learned from the compilations)
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
    final int minInstr;
    final int period;
    final int threads;
    final int timing;
    final int at;
    /** Promotion by the tier's own entry counters on the stepper path, without the machine's heat accounting. */
    final boolean count;
    /** Count mode: a block is promoted only while it has at least this many per mille of all activations of the engine (0: no such test). */
    final int hot;
    /** Run-ahead of the interning probes: 0 = off, N > 0 = where at least N interning instructions follow (NOTES-T3 section 6). */
    final int ahead;
    final int aheadMaxInstr;
    final boolean aheadExact;
    /** The hash functions the bridge writes out for compiled code are the machine's (checked once per engine). */
    final boolean hashOk = rlvm.machine.T2Bridge.hashOk();
    final double unit0Ns;
    final double unit1Ns;
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
        String mode = System.getProperty("rlvm.t2.mode", "cpu");
        boolean warm = mode.equals("warm");
        if (!warm && !mode.equals("cpu")) {
            throw new IllegalArgumentException("rlvm.t2.mode=" + mode + ": cpu or warm");
        }
        rent = dbl("rlvm.t2.rent", warm ? 0.05 : 1.0);
        share = dbl("rlvm.t2.share", warm ? 1.0 : 0.02);
        max = Integer.getInteger("rlvm.t2.max", 256);
        maxInstr = Integer.getInteger("rlvm.t2.maxInstr", 256);
        minInstr = Integer.getInteger("rlvm.t2.minInstr", 0);
        period = Math.max(1, Integer.getInteger("rlvm.t2.period", warm ? 256 : 1024));
        threads = Math.max(1, Integer.getInteger("rlvm.t2.threads", warm ? 2 : 1));
        timing = Integer.getInteger("rlvm.t2.timing", 4096);
        unit0Ns = dbl("rlvm.t2.unit0", 20.0);
        unit1Ns = dbl("rlvm.t2.unit1", 10.0);
        count = bool("rlvm.t2.count", warm);
        at = Integer.getInteger("rlvm.t2.at", warm ? 20000 : count ? 1000 : 0);
        hot = Integer.getInteger("rlvm.t2.hot", warm ? 20 : 0);
        ahead = Integer.getInteger("rlvm.t2.ahead", 0);
        aheadMaxInstr = Integer.getInteger("rlvm.t2.aheadMaxInstr", 128);
        aheadExact = bool("rlvm.t2.aheadExact", true);
        if (count && at <= 0) {
            throw new IllegalArgumentException("rlvm.t2.count needs rlvm.t2.at > 0");
        }
        cost0Ms = dbl("rlvm.t2.cost0", 3.0);
        cost1Ms = dbl("rlvm.t2.cost1", 2.5);
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
