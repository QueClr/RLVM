package rlvm.ref.corpus;

import java.nio.file.Path;
import java.nio.file.Paths;
import rlvm.api.Budget;
import rlvm.api.EngineConfig;
import rlvm.api.WorkerContext;
import rlvm.ref.RefKernel;
import rlvm.store.DeclKind;
import rlvm.store.DeclTable;

/**
 * Memory diagnostics (test tool): for every axiom, definition, theorem and opaque of an export, how
 * many overlay nodes the reference kernel built and how many of them the official caches refer to
 * at the end of the declaration ({@link RefKernel#retention}). The difference is what a kernel
 * with reference counting would have freed before the declaration ends.
 *
 * <pre>
 * java -cp ... rlvm.ref.corpus.RetentionProbe [--min NODES] [--stack MB] FILE
 * </pre>
 */
public final class RetentionProbe {

    private RetentionProbe() {}

    public static void main(String[] args) throws Exception {
        long min = 100_000;
        long stackMb = 1024;
        String file = null;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--min")) {
                min = Long.parseLong(args[++i]);
            } else if (args[i].equals("--stack")) {
                stackMb = Long.parseLong(args[++i]);
            } else {
                file = args[i];
            }
        }
        Path path = Paths.get(file);
        long minNodes = min;
        Throwable[] failure = new Throwable[1];
        Thread t = new Thread(null, () -> {
            try {
                run(path, minNodes);
            } catch (Throwable e) {
                failure[0] = e;
            }
        }, "retention", stackMb << 20);
        t.start();
        t.join();
        if (failure[0] != null) {
            throw new RuntimeException(failure[0]);
        }
    }

    private static void run(Path file, long minNodes) throws Exception {
        TrustingLoader loader = TrustingLoader.load(file);
        DeclTable decls = loader.store.decls();
        WorkerContext ctx = loader.newWorkerContext();
        RefKernel kernel = new RefKernel(ctx, EngineConfig.defaults());
        Budget budget = Budget.cold(10L << 30);
        long[] total = new long[14];
        long peakTables = 0;
        int n = decls.constantCount();
        int checked = 0;
        System.out.println("# name: built / official-reachable / import-only / garbage ; entries inferCheck inferOnly"
                + " whnfCore whnf unfoldWhnf unfold success failure canon inst ; table MB");
        for (int cid = 1; cid <= n; cid++) {
            int kind = decls.kind(cid);
            if (kind != DeclKind.AXIOM && kind != DeclKind.DEFN && kind != DeclKind.THM && kind != DeclKind.OPAQUE) {
                continue;
            }
            checked++;
            long mark = ctx.overlay.mark();
            int first = ctx.overlay.nodeCount() + 1;
            kernel.setBudget(budget);
            try {
                kernel.checkConstant(cid);
            } catch (RuntimeException e) {
                System.out.println("! " + loader.store.names().toString(decls.name(cid)) + ": " + e);
                kernel.reset();
                ctx.reset();
                continue;
            }
            long[] r = kernel.retention(first);
            for (int i = 0; i < 13; i++) {
                total[i] += r[i];
            }
            peakTables = Math.max(peakTables, r[13]);
            if (r[0] >= minNodes) {
                System.out.printf("%s: %d / %d / %d / %d (%.1f%% garbage) ; %d %d %d %d %d %d %d %d %d %d ; %d MB%n",
                        loader.store.names().toString(decls.name(cid)), r[0], r[1], r[2], r[0] - r[1] - r[2],
                        100.0 * (r[0] - r[1] - r[2]) / Math.max(1, r[0]), r[3], r[4], r[5], r[6], r[7], r[8], r[9],
                        r[10], r[11], r[12], r[13] >> 20);
            }
            kernel.reset();
            ctx.overlay.resetTo(mark);
            ctx.terms.refresh();
        }
        System.out.printf("TOTAL %s: %d declarations; built %d / official-reachable %d (%.1f%%) / import-only %d (%.1f%%)"
                + " / garbage %d (%.1f%%); entries inferCheck %d inferOnly %d whnfCore %d whnf %d unfoldWhnf %d unfold %d"
                + " success %d failure %d canon %d inst %d; peak tables %d MB%n",
                file.getFileName(), checked, total[0], total[1], 100.0 * total[1] / Math.max(1, total[0]), total[2],
                100.0 * total[2] / Math.max(1, total[0]), total[0] - total[1] - total[2],
                100.0 * (total[0] - total[1] - total[2]) / Math.max(1, total[0]), total[3], total[4], total[5],
                total[6], total[7], total[8], total[9], total[10], total[11], total[12], peakTables >> 20);
    }
}
