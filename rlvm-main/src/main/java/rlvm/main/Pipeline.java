package rlvm.main;

import java.io.PrintStream;

import rlvm.api.EngineFactory;
import rlvm.driver.Run;
import rlvm.driver.RunConfig;
import rlvm.driver.RunHost;
import rlvm.driver.Wiring;

/** The one place that constructs the driver's {@link Run}. */
final class Pipeline {
    private Pipeline() {}

    /** A complete run: {@code wiring} is the {@link MainWiring}, or {@link Passes}' wrapper of it that takes times. */
    static Run newRun(RunConfig config, Wiring wiring, EngineFactory primary, EngineFactory reference,
            PrintStream diagnostics) {
        Run run = new Run(config, wiring, primary, reference);
        // The driver prints the explanation line (and anomaly events) itself; null = print nothing.
        run.setDiagnostics(diagnostics);
        return run;
    }

    /**
     * A run over a store that an earlier run of this process loaded and admitted (a later pass of
     * {@code -X passes-mode=recheck}): the driver's batch path over a host, with exactly
     * {@code workers} workers.
     */
    static Run newRun(RunConfig config, RunHost host, int workers, EngineFactory primary, EngineFactory reference,
            PrintStream diagnostics) {
        config.workers = workers;
        Run run = new Run(config, host, primary, reference);
        // The worker count of the first pass, whatever the number of units (the batch rule would
        // lower it for a file with fewer than 128 units per worker, which a pipelined run ignores).
        run.setMinUnitsPerWorker(1);
        run.setDiagnostics(diagnostics);
        return run;
    }
}
