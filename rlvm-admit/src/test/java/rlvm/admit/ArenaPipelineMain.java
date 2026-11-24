package rlvm.admit;

import java.nio.file.Path;

import rlvm.api.Budget;
import rlvm.api.Engine;
import rlvm.api.EngineConfig;
import rlvm.api.Outcome;
import rlvm.api.Session;
import rlvm.ref.RefEngine;

/**
 * Command-line form of {@link ArenaPipelineTest}: runs the admission flow (real loader, real
 * rlvm-inductive, reference kernel, one worker, no driver) on each export given as an argument and
 * prints one line per file. For files that are too heavy for the unit-test JVM (the perf group,
 * library exports): {@code java -Xss900m -Xmx12g -cp ... rlvm.admit.ArenaPipelineMain FILE...}.
 *
 * <p>With {@code -Drlvm.admit.split=true} the phases are separated and timed: load, the whole
 * environment pass ({@code EnvBuilder.advanceTo} in batches of 4096 units, session reset between
 * batches), then the checks ({@code Admission} without an attached pass).
 */
public final class ArenaPipelineMain {
    private ArenaPipelineMain() {}

    public static void main(String[] args) throws Exception {
        for (String arg : args) {
            if (Boolean.getBoolean("rlvm.admit.split")) {
                split(Path.of(arg));
                continue;
            }
            long t0 = System.nanoTime();
            ArenaPipelineTest.Result r = ArenaPipelineTest.run(Path.of(arg));
            long ms = (System.nanoTime() - t0) / 1_000_000;
            System.out.println(arg + " " + Outcome.name(r.outcome) + " unit " + r.unit + " " + ms + " ms, " + r.units
                    + " units, " + r.resets + " session resets "
                    + (r.message.length() > 300 ? r.message.substring(0, 300) : r.message));
            System.out.flush();
        }
    }

    private static void split(Path file) throws Exception {
        long t0 = System.nanoTime();
        ArenaFiles.Loaded l = new ArenaFiles.Loaded(file);
        long t1 = System.nanoTime();
        int units = l.store.loadedUnits();
        int blocks = 0;
        for (int u = 0; u < units; u++) {
            int kind = l.store.decls().unitKind(u);
            if (l.store.decls().unitSize(u) > 0 && kind >= rlvm.store.DeclKind.INDUCT) {
                blocks++;
            }
        }
        try (Engine engine = RefEngine.FACTORY.create(l.store, EngineConfig.defaults());
                Session session = engine.newSession(l.ctx)) {
            EnvBuilder env = new EnvBuilder(l.store, session.ops(), Policy.arena(), null);
            int resets = 0;
            while (env.envUnits() < units && env.failedUnit() < 0) {
                env.advanceTo(Math.min(units, env.envUnits() + 4096));
                if (session.footprintBytes() + l.ctx.footprintBytes() > ArenaPipelineTest.SESSION_BYTES) {
                    session.reset();
                    l.ctx.reset();
                    resets++;
                }
            }
            long t2 = System.nanoTime();
            Admission admission = new Admission(l.ctx, Policy.arena());
            Budget budget = Budget.cold(4L << 30);
            int outcome = Outcome.ACCEPT;
            int at = -1;
            String message = "";
            if (env.failedUnit() >= 0) {
                outcome = env.failureReason().outcome();
                at = env.failedUnit();
                message = env.failureReason() + ": " + env.failureMessage();
            }
            int limit = env.envUnits();
            for (int u = 0; u < limit; u++) {
                int o = admission.admit(u, session, budget);
                if (o != Outcome.ACCEPT) {
                    outcome = o;
                    at = u;
                    message = admission.lastReason() + ": " + admission.lastMessage();
                    break;
                }
                if (session.footprintBytes() + l.ctx.footprintBytes() > ArenaPipelineTest.SESSION_BYTES) {
                    session.reset();
                    l.ctx.reset();
                    resets++;
                }
            }
            long t3 = System.nanoTime();
            System.out.println(file + " " + Outcome.name(outcome) + " unit " + at + ": load " + (t1 - t0) / 1_000_000
                    + " ms, environment pass " + (t2 - t1) / 1_000_000 + " ms (" + units + " units, " + blocks
                    + " inductive/quot units), checks " + (t3 - t2) / 1_000_000 + " ms, " + resets + " session resets "
                    + message);
        }
    }
}
