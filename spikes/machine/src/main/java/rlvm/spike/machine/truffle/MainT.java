package rlvm.spike.machine.truffle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import rlvm.spike.machine.Exp1;
import rlvm.spike.machine.Instantiator;
import rlvm.spike.machine.Main;

/**
 * Launcher with the Truffle tier. Everything {@link Main} accepts, plus
 *
 * <pre>
 *   [--engine.X=Y ...] exp1 CACHE c       variant (c); run-time properties:
 *        -Drlvm.threshold=N|never   heat at which a code object is promoted (default 1000)
 *        -Drlvm.use=false           compile at the threshold but keep stepping (isolates the compile cost)
 *        -Drlvm.inline=0|1|2        0 = call-threaded code (every operation is a call into the AOT library),
 *                                   1 = APP/PROJ hash-consing probes inlined, 2 = closure probes too (default)
 *        -Drlvm.call=1              enter compiled code with callIndirect(null, ..) instead of call(..)
 *        -Drlvm.tier=first          first-tier instead of last-tier compilation
 *        -Drlvm.bg=false            synchronous compilation (engine.BackgroundCompilation=false)
 *   [--engine.X=Y ...] verify CACHE       a0, b and two (c) configurations in one arena: identical ids?
 * </pre>
 */
public final class MainT {
    private MainT() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> engine = new LinkedHashMap<>();
        List<String> rest = new ArrayList<>();
        for (String a : args) {
            if (a.startsWith("--engine.") || a.startsWith("--compiler.")) {
                int eq = a.indexOf('=');
                engine.put(a.substring(2, eq < 0 ? a.length() : eq), eq < 0 ? "true" : a.substring(eq + 1));
            } else {
                rest.add(a);
            }
        }
        String[] r = rest.toArray(new String[0]);
        Throwable[] failure = new Throwable[1];
        Thread t = new Thread(null, () -> {
            try {
                if (!run(r, engine)) {
                    System.err.println("usage: see MainT.java / Main.java");
                    System.exit(3);
                }
            } catch (Throwable e) {
                failure[0] = e;
            }
        }, "machine-main", 1L << 30);
        t.start();
        t.join();
        if (failure[0] != null) {
            failure[0].printStackTrace();
            System.exit(3);
        }
        System.exit(0);
    }

    private static TruffleInst fromProperties(Map<String, String> engine) {
        String th = System.getProperty("rlvm.threshold", "1000");
        int threshold = th.equals("never") ? Integer.MAX_VALUE : (int) Double.parseDouble(th);
        if (!Boolean.parseBoolean(System.getProperty("rlvm.bg", "true"))) {
            engine.put("engine.BackgroundCompilation", "false");
        }
        return new TruffleInst(threshold, Boolean.parseBoolean(System.getProperty("rlvm.use", "true")), Integer.getInteger("rlvm.inline", 2),
                        Integer.getInteger("rlvm.call", 0), !System.getProperty("rlvm.tier", "last").equals("first"), engine);
    }

    private static boolean run(String[] args, Map<String, String> engine) throws Exception {
        if (args.length >= 3 && args[0].equals("exp1") && args[2].equals("c")) {
            Main.setup(args[1]);
            Exp1.run("c", fromProperties(engine), Integer.getInteger("rlvm.passes", 1));
            return true;
        }
        if (args.length >= 2 && args[0].equals("verify")) {
            Main.setup(args[1]);
            Map<String, String> sync = new LinkedHashMap<>(engine);
            sync.put("engine.BackgroundCompilation", "false");
            int th = Integer.getInteger("rlvm.threshold", 1000);
            String[] names = {"a0", "b", "c inline=2 sync", "c inline=0 (call-threaded) sync", "c inline=1 sync callIndirect"};
            Instantiator[] vs = {Main.plainVariant("a0"), new Instantiator.StepperInst(), new TruffleInst(th, true, 2, 0, true, sync), new TruffleInst(th, true, 0, 0, true, sync),
                            new TruffleInst(th, true, 1, 1, true, sync)};
            boolean ok = Exp1.verify(names, vs);
            System.out.println(ok ? "VERIFY OK" : "VERIFY FAILED");
            return true;
        }
        return Main.run(args);
    }
}
