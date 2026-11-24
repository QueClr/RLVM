package rlvm.truffle;

import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleRuntime;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.compiler.TruffleCompilerListener.CompilationResultInfo;
import com.oracle.truffle.compiler.TruffleCompilerListener.GraphInfo;
import com.oracle.truffle.runtime.AbstractCompilationTask;
import com.oracle.truffle.runtime.OptimizedCallTarget;
import com.oracle.truffle.runtime.OptimizedTruffleRuntime;
import com.oracle.truffle.runtime.OptimizedTruffleRuntimeListener;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Supplier;
import org.graalvm.polyglot.Context;

/**
 * The process-wide Truffle side of the tier: the one polyglot engine, the promoter thread, explicit
 * compilation and its listener (docs/research/10 section 5.4), and the entry into compiled code.
 *
 * <p>Nothing here runs before the first promotion: a run that promotes no block never initialises
 * Truffle. The promoter thread (a daemon) creates the engine, builds the root of a promoted block and
 * submits it with {@code OptimizedCallTarget.compile(true)}; Graal compiles on its one background thread;
 * the listener installs the block when its code is valid. Workers never build or compile anything; they
 * call a target only while {@code isValid()} holds, so a root is never run by the Truffle interpreter
 * (ISA 8.1), and a target that lost its code is simply not taken any more.
 *
 * <p>Engine options (ISA 8.5): one compiler thread, background compilation, single tier, no splitting,
 * no OSR, static thresholds that are never reached (promotion is explicit), at most one compilation per
 * target, silent failures (a failed compilation marks the block hostile).
 *
 * <p>{@code OptimizedCallTarget} and the listener are internal API of the optimising runtime
 * ({@code org.graalvm.truffle.runtime}); on the class path they are reachable as they are, a native image
 * needs {@code --add-exports} (this jar's native-image.properties).
 */
final class T2Runtime {
    private T2Runtime() {}

    private static final class Request {
        final T2Engine engine;
        final T2Engine.Rec rec;
        final CountDownLatch done = new CountDownLatch(1);

        Request(T2Engine engine, T2Engine.Rec rec) {
            this.engine = engine;
            this.rec = rec;
        }
    }

    private static final LinkedBlockingQueue<Request> QUEUE = new LinkedBlockingQueue<>();
    private static Thread promoter;
    /** Promoter thread only. */
    private static Context context;
    private static volatile String broken;

    /** Hands a promoted block to the promoter thread; {@code sync}: waits until its compilation has ended. */
    static void submit(T2Engine engine, T2Engine.Rec rec, boolean sync) {
        Request r = new Request(engine, rec);
        synchronized (QUEUE) {
            if (promoter == null) {
                promoter = new Thread(T2Runtime::loop, "rlvm-t2-promoter");
                promoter.setDaemon(true);
                promoter.start();
            }
        }
        QUEUE.add(r);
        if (sync) {
            try {
                r.done.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void loop() {
        for (;;) {
            Request r;
            try {
                r = QUEUE.take();
            } catch (InterruptedException e) {
                return;
            }
            try {
                promote(r);
            } catch (Throwable t) {
                r.engine.failed(r.rec, "promotion failed: " + t, 0);
            } finally {
                r.done.countDown();
            }
        }
    }

    /** Creates the engine at the first promotion; false if this process cannot compile (ISA 8.6). */
    private static boolean ensureEngine(Settings s) {
        if (context != null) {
            return true;
        }
        if (broken != null) {
            return false;
        }
        try {
            TruffleRuntime rt = Truffle.getRuntime();
            if (!(rt instanceof OptimizedTruffleRuntime)) {
                broken = "no optimising Truffle runtime (" + rt.getName() + ")";
                return false;
            }
            Map<String, String> opts = new LinkedHashMap<>();
            opts.put("engine.BackgroundCompilation", "true");
            opts.put("engine.CompilerThreads", Integer.toString(s.threads));
            opts.put("engine.MultiTier", "false");
            opts.put("engine.SingleTierCompilationThreshold", "2000000000");
            opts.put("engine.DynamicCompilationThresholds", "false");
            opts.put("engine.Splitting", "false");
            opts.put("engine.OSR", "false");
            opts.put("engine.MaximumCompilations", "1");
            opts.put("engine.CompilationFailureAction", s.trace ? "Print" : "Silent");
            opts.put("engine.WarnInterpreterOnly", "false");
            opts.putAll(s.engineOptions);
            Context c = Context.newBuilder(RlvmLanguage.ID).allowExperimentalOptions(true).options(opts).build();
            c.initialize(RlvmLanguage.ID);
            c.enter();
            if (RlvmLanguage.instance == null) {
                broken = "the language 'rlvm' was not initialised";
                return false;
            }
            ((OptimizedTruffleRuntime) rt).addListener(new Listener());
            context = c;
            return true;
        } catch (Throwable t) {
            broken = "the Truffle engine cannot be created (" + t + ")";
            return false;
        }
    }

    private static void promote(Request r) {
        T2Engine eng = r.engine;
        Settings s = eng.settings;
        if (!ensureEngine(s)) {
            eng.failed(r.rec, broken, 0);
            eng.disable(broken);
            return;
        }
        int[] code = eng.heat.block(r.rec.blk);
        Decoded d = code == null ? null : new Decoded(code, r.rec.blk, s.slots && s.nodes);
        if (d == null || d.hostile != null) {
            eng.failed(r.rec, d == null ? "no such block" : d.hostile, 0);
            return;
        }
        BlockRoot root = new BlockRoot(RlvmLanguage.instance, eng, d, s, r.rec.what);
        root.rec = r.rec;
        OptimizedCallTarget target = (OptimizedCallTarget) root.getCallTarget();
        root.target = target;
        target.compile(true);
        if (s.sync) {
            target.waitForCompilation();
            if (target.isValid()) {
                eng.installed(root, target, -1, 0, 0); // normally done by the listener already
            } else {
                eng.failed(r.rec, "no valid code after the compilation", 0);
            }
        }
    }

    /**
     * Enters the compiled code of an installed block at entry {@code e} for the machine of {@code w}.
     *
     * @return false, with nothing changed, if the code is not valid (any more): the stepper runs the activation
     */
    static boolean enter(T2Engine.Installed c, T2Tier w, int e, int rb, int d) {
        OptimizedCallTarget t = c.target;
        if (!t.isValid()) {
            return false;
        }
        w.entry = e;
        w.rb = rb;
        w.d = d;
        t.callIndirect(null, w.args);
        return true;
    }

    /**
     * Runs on the compiler thread: the compile cost of a block is that thread's CPU time between start and end.
     * Nothing here may fail a compilation: every callback catches whatever its bookkeeping throws.
     */
    private static final class Listener implements OptimizedTruffleRuntimeListener {
        private static BlockRoot rootOf(OptimizedCallTarget target) {
            return target.getRootNode() instanceof BlockRoot ? (BlockRoot) target.getRootNode() : null;
        }

        @Override
        public void onCompilationStarted(OptimizedCallTarget target, AbstractCompilationTask task) {
            try {
                BlockRoot root = rootOf(target);
                if (root != null) {
                    root.compileStartCpu = Cpu.threadNanos();
                }
            } catch (Throwable t) {
                // bookkeeping only
            }
        }

        @Override
        public void onCompilationSuccess(OptimizedCallTarget target, AbstractCompilationTask task, GraphInfo graph, CompilationResultInfo result) {
            BlockRoot root = rootOf(target);
            if (root == null) {
                return;
            }
            long cpu = -1;
            int size = 0;
            int nodes = 0;
            try {
                cpu = root.compileStartCpu == 0 ? -1 : Cpu.threadNanos() - root.compileStartCpu;
                size = result == null ? 0 : result.getTargetCodeSize();
                nodes = graph == null ? 0 : graph.getNodeCount();
            } catch (Throwable t) {
                // the compiler's info classes are not accessible: no sizes in the report
            }
            try {
                root.owner.installed(root, target, cpu, size, nodes);
            } catch (Throwable t) {
                T2Engine.warn("installing " + root + " failed (" + t + "); the block stays in the stepper");
            }
        }

        @Override
        public void onCompilationFailed(OptimizedCallTarget target, String reason, boolean bailout, boolean permanentBailout, int tier,
                Supplier<String> lazyStackTrace) {
            try {
                BlockRoot root = rootOf(target);
                if (root == null) {
                    return;
                }
                long cpu = root.compileStartCpu == 0 ? 0 : Cpu.threadNanos() - root.compileStartCpu;
                String detail = reason;
                if (root.owner.settings.trace && lazyStackTrace != null) {
                    try {
                        detail = reason + "\n" + lazyStackTrace.get();
                    } catch (Throwable t) {
                        // keep the reason
                    }
                }
                root.owner.failed(root.rec, "compilation failed: " + detail, cpu);
            } catch (Throwable t) {
                // bookkeeping only
            }
        }

        @Override
        public void onCompilationDeoptimized(OptimizedCallTarget target, Frame frame, String reason) {
            try {
                BlockRoot root = rootOf(target);
                if (root != null) {
                    root.owner.deoptimized(root, String.valueOf(reason));
                }
            } catch (Throwable t) {
                // bookkeeping only
            }
        }

        @Override
        public void onCompilationInvalidated(OptimizedCallTarget target, Object source, CharSequence reason) {
            try {
                BlockRoot root = rootOf(target);
                if (root != null) {
                    root.owner.invalidated(root, String.valueOf(reason));
                }
            } catch (Throwable t) {
                // bookkeeping only
            }
        }
    }
}
