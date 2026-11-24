package rlvm.admit;

import java.nio.file.Path;

import rlvm.load.MalformedExportException;
import rlvm.load.UnsupportedExportException;

/**
 * Development aid for {@link OfficialOrder}: for each export, whether the simulated
 * official replay adds a declaration while something it mentions is missing ("absent NAME at UNIT"),
 * to be compared with leanchecker's verdict on files that are otherwise well typed.
 * {@code java -cp $(cat work/rlvm-admit/cp.txt) rlvm.admit.OfficialOrderMain FILE...}
 */
public final class OfficialOrderMain {
    private OfficialOrderMain() {}

    public static void main(String[] args) throws Exception {
        for (String arg : args) {
            ArenaFiles.Loaded l;
            try {
                l = new ArenaFiles.Loaded(Path.of(arg));
            } catch (MalformedExportException | UnsupportedExportException e) {
                System.out.println("loader " + arg);
                continue;
            }
            long c0 = java.lang.management.ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime();
            OfficialOrder o = new OfficialOrder(l.store, l.ctx.terms, l.ctx.lists, u -> l.store.decls().claims(u), null,
                    Long.getLong("rlvm.admit.orderVisits", 1L << 24));
            if (Boolean.getBoolean("rlvm.admit.orderTime")) {
                long c1 = java.lang.management.ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime();
                System.out.println("simulation: " + (c1 - c0) / 1000 + " us thread CPU, " + l.store.loadedUnits() + " units, "
                        + l.store.terms().exportEnd() + " shared nodes, " + l.store.blocks().recordCount() + " block records");
            }
            if (!o.available()) {
                System.out.println("unavailable " + arg + " (" + o.whyUnavailable() + ")");
            } else if (o.firstAbsent() < 0) {
                System.out.println("ok " + arg);
            } else {
                System.out.println("absent " + arg + " '" + l.store.names().toString(l.store.decls().name(o.absentConstant()))
                        + "' at unit " + o.firstAbsent() + " '"
                        + l.store.names().toString(l.store.decls().name(l.store.decls().unitFirstCid(o.firstAbsent()))) + "'");
            }
        }
    }
}
