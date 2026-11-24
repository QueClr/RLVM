package rlvm.admit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import rlvm.api.Budget;
import rlvm.api.Engine;
import rlvm.api.EngineConfig;
import rlvm.api.Outcome;
import rlvm.api.Session;
import rlvm.load.MalformedExportException;
import rlvm.load.UnsupportedExportException;
import rlvm.ref.RefEngine;

/**
 * Integration of the admission flow with the real upstream modules: the real loader, the real
 * {@code rlvm-inductive} and the reference kernel ({@code rlvm-ref}, test scope), one worker, the
 * environment pass driven lazily by {@link Admission} - the vertical slice without the driver.
 *
 * <p>The verdict of a file is the outcome of its first non-accepted unit. A WRONG verdict (a reject
 * test accepted, an accept test rejected) fails the test; declines and the reason of every
 * non-matching file are written to {@code work/rlvm-admit/pipeline-report.txt}.
 */
class ArenaPipelineTest {

    /** Session cap of this harness; small, so that resets happen inside the arena files too. */
    static final long SESSION_BYTES = Long.getLong("rlvm.admit.sessionBytes", 1L << 20);

    static final class Result {
        int outcome;
        int unit = -1;
        String message = "";
        int resets;
        int units;
    }

    static Result run(Path file) throws IOException {
        return run(file, Policy.arena(), true);
    }

    /**
     * @param attached true: one worker drives the environment pass lazily through its {@link Admission}
     *     (the single-worker shape); false: the pass runs to its end first and the units are then
     *     admitted by an {@code Admission} that knows nothing of it, as the driver's workers do
     */
    static Result run(Path file, Policy policy, boolean attached) throws IOException {
        Result r = new Result();
        ArenaFiles.Loaded l;
        try {
            l = new ArenaFiles.Loaded(file);
        } catch (MalformedExportException e) {
            r.outcome = Outcome.REJECT;
            r.message = "loader: " + e.getMessage();
            return r;
        } catch (UnsupportedExportException e) {
            r.outcome = Outcome.DECLINE;
            r.message = "loader: " + e.getMessage();
            return r;
        }
        try (Engine engine = RefEngine.FACTORY.create(l.store, EngineConfig.defaults());
                Session session = engine.newSession(l.ctx)) {
            EnvBuilder env = new EnvBuilder(l.store, session.ops(), policy, null);
            Admission admission = attached ? new Admission(l.ctx, policy, env) : new Admission(l.ctx, policy);
            Budget budget = Budget.cold(4L << 30);
            int units = l.store.loadedUnits();
            if (!attached) {
                env.run();
                if (env.failedUnit() >= 0) {
                    // the units below the failed one decide first, as in a run
                    units = env.failedUnit();
                }
            }
            for (int u = 0; u < units; u++) {
                int o;
                try {
                    o = admission.admit(u, session, budget);
                } catch (RuntimeException e) {
                    r.outcome = Outcome.DECLINE;
                    r.unit = u;
                    r.message = "exception: " + e;
                    return r;
                }
                if (o != Outcome.ACCEPT) {
                    r.outcome = o;
                    r.unit = u;
                    r.message = admission.lastReason() + ": " + admission.lastMessage();
                    return r;
                }
                // What the driver does between units: the session (shared with the environment pass,
                // which keeps no overlay id across advanceTo calls) is reset when it has grown.
                if (session.footprintBytes() + l.ctx.footprintBytes() > SESSION_BYTES) {
                    session.reset();
                    l.ctx.reset();
                    r.resets++;
                }
            }
            if (!attached && env.failedUnit() >= 0) {
                r.outcome = env.failureReason().outcome();
                r.unit = env.failedUnit();
                r.message = env.failureReason() + ": " + env.failureMessage();
                return r;
            }
            r.outcome = Outcome.ACCEPT;
            r.units = units;
            return r;
        }
    }

    private static void runGroup(String group) throws IOException {
        Path root = ArenaFiles.testsRoot();
        assumeTrue(root != null, "work/arena-site/tests is not available");
        List<String> wrong = new ArrayList<>();
        List<String> other = new ArrayList<>();
        List<String> all = new ArrayList<>();
        int correct = 0;
        int total = 0;
        for (String expected : new String[] {"good", "bad"}) {
            int want = expected.equals("good") ? Outcome.ACCEPT : Outcome.REJECT;
            for (Path file : ArenaFiles.files(expected)) {
                if (!file.getParent().getFileName().toString().equals(group)) {
                    continue;
                }
                total++;
                long t0 = System.nanoTime();
                Result r = run(file);
                long ms = (System.nanoTime() - t0) / 1_000_000;
                String line = group + "/" + file.getFileName() + " expected " + Outcome.name(want) + " got "
                        + Outcome.name(r.outcome) + " at unit " + r.unit + " (" + ms + " ms): " + r.message;
                all.add(line);
                if (r.outcome == want) {
                    correct++;
                } else if (r.outcome == Outcome.DECLINE) {
                    other.add(line);
                } else {
                    wrong.add(line);
                }
            }
        }
        StringBuilder report = new StringBuilder();
        report.append(group).append(": ").append(correct).append(" / ").append(total).append(" correct, ")
                .append(wrong.size()).append(" WRONG, ").append(other.size()).append(" declined\n");
        for (String s : wrong) {
            report.append("WRONG    ").append(s).append('\n');
        }
        for (String s : other) {
            report.append("declined ").append(s).append('\n');
        }
        System.out.println(report);
        report.append("\nevery file:\n");
        for (String s : all) {
            report.append(s).append('\n');
        }
        Path out = Path.of("..", "work", "rlvm-admit", "pipeline-report-" + group + ".txt").toAbsolutePath().normalize();
        Files.createDirectories(out.getParent());
        Files.writeString(out, report);
        assertTrue(wrong.isEmpty(), report.toString());
    }

    @Test
    void tutorialTests() throws IOException {
        runGroup("tutorial");
    }

    @Test
    void bugsTests() throws IOException {
        runGroup("bugs");
    }

    @Test
    void otherTests() throws IOException {
        runGroup("other");
    }

    @Test
    void perfTests() throws IOException {
        assumeTrue(Boolean.getBoolean("rlvm.admit.perf"), "perf group only with -Drlvm.admit.perf=true");
        runGroup("perf");
    }
}
