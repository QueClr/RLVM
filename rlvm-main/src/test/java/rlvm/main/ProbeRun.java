package rlvm.main;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import rlvm.admit.Admission;
import rlvm.admit.EnvBuilder;
import rlvm.admit.Policy;
import rlvm.api.Budget;
import rlvm.api.Engine;
import rlvm.api.EngineConfig;
import rlvm.api.Outcome;
import rlvm.api.Reason;
import rlvm.api.RunSignals;
import rlvm.api.Session;
import rlvm.api.WorkerContext;
import rlvm.load.LoadOptions;
import rlvm.load.MalformedExportException;
import rlvm.load.UnsupportedExportException;
import rlvm.store.DeclTable;
import rlvm.store.Store;

/**
 * The steward's probe: the smallest sequential pipeline that makes the published modules run
 * together - wiring load, one environment pass, one admission, the reference engine - without the
 * driver. It exists to find integration failures early and as an independent cross-check of the
 * driver's verdicts; it is test code and implements none of the driver's policies (no re-check, no
 * session cap, no memory account, no statistics).
 *
 * <pre>
 * load (MainWiring)                          malformed -> REJECT, unsupported / IO -> DECLINE
 * two worker contexts and two reference sessions: one for the environment pass, one for checking
 * for every unit in file order: Admission.admit(unit, session, Budget.cold)   (3-argument Admission:
 *     it advances the environment pass itself); the first non-ACCEPT unit is the verdict
 * </pre>
 */
final class ProbeRun {

    static final class Result {
        final int outcome; // Outcome.*, or -1 for an exception / error
        final String message;
        final int units;
        final long nanos;
        /** Memory at the end of the run: worker overlays + pools of the checking context, shared store. */
        long workerBytes;
        long sharedBytes;

        Result(int outcome, String message, int units, long nanos) {
            this.outcome = outcome;
            this.message = message;
            this.units = units;
            this.nanos = nanos;
        }

        String status() {
            switch (outcome) {
                case Outcome.ACCEPT:
                    return "accepted";
                case Outcome.REJECT:
                    return "rejected";
                case Outcome.DECLINE:
                    return "declined";
                default:
                    return "error";
            }
        }
    }

    private ProbeRun() {}

    /** Runs the probe on a thread with {@code stackBytes} of stack; never throws for a property of the input. */
    static Result run(Path file, long stackBytes, long timeoutMillis) throws InterruptedException {
        Result[] box = new Result[1];
        long t0 = System.nanoTime();
        Thread t = new Thread(null, () -> {
            try {
                box[0] = check(file, t0);
            } catch (Throwable e) {
                StackTraceElement[] st = e.getStackTrace();
                StringBuilder where = new StringBuilder();
                int frames = Integer.getInteger("rlvm.probe.frames", 1);
                for (int i = 0; i < st.length && i < frames; i++) {
                    where.append(i == 0 ? " at " : " < ").append(st[i]);
                }
                box[0] = new Result(-1, "EXCEPTION " + e + where, 0, System.nanoTime() - t0);
            }
        }, "probe", stackBytes);
        t.setDaemon(true);
        t.start();
        t.join(timeoutMillis);
        if (box[0] == null) {
            return new Result(-1, "TIMEOUT after " + timeoutMillis + " ms (probe thread left running)", 0,
                    System.nanoTime() - t0);
        }
        return box[0];
    }

    private static Result check(Path file, long t0) throws IOException {
        MainWiring w = new MainWiring(file, MainOptions.parse(null));
        long bytes = Files.size(file);
        Store store = w.newStore(bytes);
        try {
            w.load(store, LoadOptions.batch(bytes));
        } catch (MalformedExportException e) {
            return new Result(Outcome.REJECT, "load: malformed export, line " + e.line() + ": " + e.getMessage(), 0,
                    System.nanoTime() - t0);
        } catch (UnsupportedExportException e) {
            return new Result(Outcome.DECLINE, "load: unsupported export: " + e.getMessage(), 0, System.nanoTime() - t0);
        }
        long cap = 512L << 20;
        WorkerContext envCtx = w.newWorkerContext(0, store, RunSignals.NONE, cap);
        WorkerContext ctx = w.newWorkerContext(1, store, RunSignals.NONE, cap);
        Policy policy = Policy.arena();
        try (Engine engine = Engines.reference().factory().create(store, EngineConfig.defaults())) {
            Session envSession = engine.newSession(envCtx);
            Session session = engine.newSession(ctx);
            String[] envFailure = new String[1];
            EnvBuilder env = new EnvBuilder(store, envSession.ops(), policy, new EnvBuilder.Listener() {
                @Override
                public void unitAdmitted(int unit) {}

                @Override
                public void unitFailed(int unit, Reason reason, String message) {
                    envFailure[0] = reason + ": " + message;
                }

                @Override
                public void frontierAdvanced(int units) {}
            });
            Admission admission = new Admission(ctx, policy, env);
            DeclTable decls = store.decls();
            int units = store.loadedUnits();
            for (int u = 0; u < units; u++) {
                int o = admission.admit(u, session, Budget.cold(1L << 30));
                if (o != Outcome.ACCEPT) {
                    String name = "?";
                    try {
                        if (decls.unitSize(u) > 0) {
                            name = store.names().toString(decls.name(decls.unitFirstCid(u)));
                        }
                    } catch (RuntimeException ignored) {
                        // diagnostics only
                    }
                    String why = admission.lastReason() + ": " + admission.lastMessage();
                    if (envFailure[0] != null && !why.contains(envFailure[0])) {
                        why += " [environment pass: " + envFailure[0] + "]";
                    }
                    return new Result(o, "unit " + u + " (" + name + "): " + why, units, System.nanoTime() - t0);
                }
                if (ctx.footprintBytes() > (64L << 20)) {
                    session.reset();
                    ctx.reset();
                }
            }
            if (!store.loadComplete()) {
                return new Result(Outcome.DECLINE, "load not complete", units, System.nanoTime() - t0);
            }
            Result ok = new Result(Outcome.ACCEPT, "", units, System.nanoTime() - t0);
            ok.workerBytes = ctx.footprintBytes() + envCtx.footprintBytes();
            ok.sharedBytes = store.sharedBytes();
            return ok;
        }
    }
}
