package rlvm.cli.probe;

import java.io.PrintStream;
import java.util.Map;

import org.graalvm.polyglot.Context;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleRuntime;

/**
 * Checks that Truffle <em>runtime compilation</em> works in the running artifact: a trivial call target
 * is called until the code that answers is compiled code of the last tier. Used by
 * {@code rlvm --truffle-probe}, which {@code scripts/build.sh} runs against every image it builds.
 *
 * <p>Two routes are exercised, because the upper tier of the checker may use either: a root node without
 * a language (options then come from {@code -Dpolyglot.engine.*} system properties) and a root node of a
 * registered language inside a polyglot {@link Context} built with explicit engine options.
 *
 * <p>The failure this guards against is silent: with mismatching Truffle and compiler versions, or an
 * image built without the Truffle runtime feature, Truffle falls back to
 * {@code DefaultTruffleRuntime} and everything still works, in the interpreter.
 */
public final class TruffleProbe {
    /** Class name of the interpreter-only fallback runtime. */
    public static final String FALLBACK_RUNTIME = "com.oracle.truffle.api.impl.DefaultTruffleRuntime";

    private static final int LOOP_ITERATIONS = 1000;
    /** Give up after this long without last-tier code; a healthy runtime needs well under a second. */
    private static final long BUDGET_NANOS = 20_000_000_000L;
    private static final String[] TIER_NAMES = {"interpreted", "first-tier", "last-tier"};

    private TruffleProbe() {
    }

    /** What happened to one call target. */
    public static final class Result {
        /** Highest tier seen: 0 interpreter, 1 first tier, 2 last tier. */
        public final int tier;
        public final long calls;
        public final long callsToCompiled;
        public final long nanosToCompiled;
        public final long nanosToLastTier;

        Result(int tier, long calls, long callsToCompiled, long nanosToCompiled, long nanosToLastTier) {
            this.tier = tier;
            this.calls = calls;
            this.callsToCompiled = callsToCompiled;
            this.nanosToCompiled = nanosToCompiled;
            this.nanosToLastTier = nanosToLastTier;
        }

        public boolean lastTier() {
            return tier == ProbeRootNode.LAST_TIER;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("tier=").append(TIER_NAMES[tier]).append(" calls=").append(calls);
            if (tier >= ProbeRootNode.FIRST_TIER) {
                sb.append(" calls_to_compiled=").append(callsToCompiled).append(" ms_to_compiled=").append(nanosToCompiled / 1_000_000);
            }
            if (tier == ProbeRootNode.LAST_TIER) {
                sb.append(" ms_to_last_tier=").append(nanosToLastTier / 1_000_000);
            }
            return sb.toString();
        }
    }

    /** True if {@code report} (a {@link Result#toString()}) says that last-tier code ran. */
    public static boolean reportsLastTier(String report) {
        return report != null && report.startsWith("tier=" + TIER_NAMES[ProbeRootNode.LAST_TIER] + " ");
    }

    /** Name and class of the active Truffle runtime. */
    public static String runtimeDescription() {
        TruffleRuntime runtime = Truffle.getRuntime();
        return runtime.getName() + " (" + runtime.getClass().getName() + ")";
    }

    /** True if Truffle is running on the interpreter-only fallback runtime. */
    public static boolean isFallbackRuntime() {
        return Truffle.getRuntime().getClass().getName().equals(FALLBACK_RUNTIME);
    }

    /** Calls {@code target} until last-tier code answers or the budget is used up. */
    public static Result drive(CallTarget target) {
        long start = System.nanoTime();
        long calls = 0;
        long callsToCompiled = 0;
        long nanosToCompiled = 0;
        int best = ProbeRootNode.INTERPRETED;
        long expected = -1;
        while (true) {
            long raw = (Long) target.call(LOOP_ITERATIONS);
            calls++;
            int tier = (int) (raw & 3);
            long value = raw >> 2;
            if (expected == -1) {
                expected = value;
            } else if (value != expected) {
                throw new IllegalStateException("truffle probe: tier " + tier + " computed " + value + ", the first call " + expected);
            }
            if (tier > best) {
                best = tier;
                if (callsToCompiled == 0) {
                    callsToCompiled = calls;
                    nanosToCompiled = System.nanoTime() - start;
                }
                if (tier == ProbeRootNode.LAST_TIER) {
                    return new Result(best, calls, callsToCompiled, nanosToCompiled, System.nanoTime() - start);
                }
            }
            if ((calls & 0xff) == 0 && System.nanoTime() - start > BUDGET_NANOS) {
                return new Result(best, calls, callsToCompiled, nanosToCompiled, 0);
            }
        }
    }

    /**
     * Runs both routes and prints one line each.
     *
     * @param engineOptions options for the polyglot engine, e.g. {@code engine.CompilerThreads=1}
     * @return true if last-tier compiled code ran on both routes
     */
    public static boolean run(PrintStream out, Map<String, String> engineOptions) {
        out.println("rlvm: truffle-probe: runtime: " + runtimeDescription());
        if (isFallbackRuntime()) {
            out.println("rlvm: truffle-probe: FAIL: the fallback runtime is active; nothing will ever be compiled");
            return false;
        }
        Result bare = drive(new ProbeRootNode(null).getCallTarget());
        out.println("rlvm: truffle-probe: bare call target: " + bare);
        String polyglot;
        try (Context context = Context.newBuilder(ProbeLanguage.ID).allowExperimentalOptions(true).options(engineOptions).build()) {
            polyglot = context.eval(ProbeLanguage.ID, "probe").asString();
        }
        out.println("rlvm: truffle-probe: polyglot engine " + engineOptions + ": " + polyglot);
        boolean ok = bare.lastTier() && reportsLastTier(polyglot);
        out.println("rlvm: truffle-probe: " + (ok ? "OK: runtime compilation works" : "FAIL: no last-tier compiled code was reached"));
        return ok;
    }
}
