package rlvm.main;

import java.io.PrintStream;

import rlvm.api.EngineFactory;
import rlvm.driver.Run;
import rlvm.driver.RunConfig;

/** The one place that constructs the driver's {@link Run}. */
final class Pipeline {
    private Pipeline() {}

    static Run newRun(RunConfig config, MainWiring wiring, EngineFactory primary, EngineFactory reference,
            PrintStream diagnostics) {
        Run run = new Run(config, wiring, primary, reference);
        // The driver prints the explanation line (and anomaly events) itself; null = print nothing.
        run.setDiagnostics(diagnostics);
        return run;
    }
}
