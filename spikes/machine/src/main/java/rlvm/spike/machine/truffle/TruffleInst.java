package rlvm.spike.machine.truffle;

import java.util.LinkedHashMap;
import java.util.Map;

import org.graalvm.polyglot.Context;

import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.runtime.OptimizedCallTarget;

import rlvm.spike.machine.Corpus;
import rlvm.spike.machine.Elab;
import rlvm.spike.machine.Exp1;
import rlvm.spike.machine.Instantiator;
import rlvm.spike.machine.Machine;
import rlvm.spike.machine.Stepper;
import rlvm.spike.machine.Workload;

/**
 * Variant (c): the plain stepper is the base tier; when a constant's heat counter reaches the threshold its
 * code object is turned into a Truffle root and submitted to Graal. Until the compiled code is installed (and
 * for every constant below the threshold) instantiation keeps running in the stepper, so the Truffle
 * interpreter is never executed.
 *
 * Uses two methods of the Truffle runtime that are not public API: {@code OptimizedCallTarget.compile(boolean)}
 * and {@code isValid()}.
 */
public final class TruffleInst implements Instantiator {
    private static final Object[] NO_ARGS = new Object[0];
    private static Context context;

    private final int threshold;
    private final boolean useCompiled;
    private final int inline;
    private final int callStyle;
    private final boolean lastTier;
    private final Map<String, String> engineOptions;
    /** OptimizedCallTarget, typed Object so that the Truffle runtime classes load only at the first promotion. */
    private final Object[] targets;
    private final int[] heat;
    /** Arity profile per constant: bit j = instantiated with j (capped at n) arguments while in the stepper. */
    private final long[] seen;
    private long fallbacks;

    private int promoted;
    private int validAtLoopEnd = -1;
    private int validAtFinish;
    private long compiledCalls;
    private long promoteNanos;

    public TruffleInst(int threshold, boolean useCompiled, int inline, int callStyle, boolean lastTier, Map<String, String> engineOptions) {
        this.threshold = threshold;
        this.useCompiled = useCompiled;
        this.inline = inline;
        this.callStyle = callStyle;
        this.lastTier = lastTier;
        this.engineOptions = engineOptions;
        this.targets = new Object[Corpus.nConst];
        this.heat = new int[Corpus.nConst];
        this.seen = new long[Corpus.nConst];
    }

    @Override
    public void inst(int c, int[] S, int fp, int k) {
        Object t = targets[c];
        if (t != null) {
            if (useCompiled && Promoted.isValid(t)) {
                Machine.fp = fp;
                Machine.k = k;
                if (Promoted.call(t, callStyle) == Boolean.TRUE) {
                    compiledCalls++;
                    return;
                }
                fallbacks++;
            }
        } else {
            int n = Workload.nparams[c];
            int j = k < n ? k : n;
            seen[c] |= 1L << (j < 63 ? j : 63);
            if (++heat[c] == threshold) {
                promote(c);
            }
        }
        int co = Elab.codeStart[c];
        if (co == 0) {
            co = Elab.elaborate(c);
        }
        Stepper.run(co, S, fp, k);
    }

    private void promote(int c) {
        long t0 = System.nanoTime();
        if (context == null) {
            Map<String, String> opts = new LinkedHashMap<>();
            opts.put("engine.CompilationFailureAction", "Print");
            opts.put("engine.DynamicCompilationThresholds", "false");
            opts.put("engine.Splitting", "false");
            opts.put("engine.OSR", "false");
            opts.putAll(engineOptions);
            context = Context.newBuilder(TelLanguage.ID).allowExperimentalOptions(true).options(opts).build();
            context.initialize(TelLanguage.ID);
            context.enter();
        }
        int co = Elab.codeStart[c];
        if (co == 0) {
            co = Elab.elaborate(c);
        }
        targets[c] = Promoted.create(TelRoot.build(TelLanguage.instance, Corpus.constStr[c], co, seen[c] & ~(1L << 63), inline), lastTier);
        promoted++;
        promoteNanos += System.nanoTime() - t0;
    }

    private int countValid() {
        int v = 0;
        for (Object t : targets) {
            if (t != null && Promoted.isValid(t)) {
                v++;
            }
        }
        return v;
    }

    /** The only code that names Truffle runtime internals; first used after the engine exists. */
    private static final class Promoted {
        static Object create(TelRoot root, boolean lastTier) {
            OptimizedCallTarget t = (OptimizedCallTarget) root.getCallTarget();
            t.compile(lastTier);
            return t;
        }

        static boolean isValid(Object t) {
            return ((OptimizedCallTarget) t).isValid();
        }

        static Object call(Object t, int style) {
            if (style == 0) {
                return ((OptimizedCallTarget) t).call(NO_ARGS);
            } else {
                return ((OptimizedCallTarget) t).callIndirect(null, NO_ARGS);
            }
        }
    }

    /** Waits for the compilations still queued, so that the process total contains every compilation in full. */
    @Override
    public void finish() {
        if (validAtLoopEnd < 0) {
            validAtLoopEnd = countValid();
        }
        long deadline = System.nanoTime() + 300_000_000_000L;
        int v;
        while ((v = countValid()) < promoted && System.nanoTime() < deadline) {
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                break;
            }
        }
        validAtFinish = v;
    }

    @Override
    public String info() {
        return "runtime \"" + Truffle.getRuntime().getName() + "\" threshold " + threshold + " use_compiled " + useCompiled + " inline " + inline + " call_style " + callStyle
                        + " tier " + (lastTier ? "last" : "first") + " promoted " + promoted + " valid_at_loop_end " + validAtLoopEnd + " valid_at_finish " + validAtFinish
                        + " compiled_calls " + compiledCalls + " unprofiled_arity_fallbacks " + fallbacks + " promote_ms(driver thread) " + promoteNanos / 1_000_000 + " | " + Exp1.elabInfo();
    }
}
