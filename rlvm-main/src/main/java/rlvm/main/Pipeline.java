package rlvm.main;

import java.io.PrintStream;

import rlvm.api.EngineFactory;
import rlvm.driver.Run;
import rlvm.driver.RunConfig;
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
}
