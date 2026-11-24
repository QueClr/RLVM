package rlvm.cli;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Test-only door into the launcher's package-private entry points ({@code Main.run},
 * {@code Checkers}), so that this module's tests can run the real command line in-process and see
 * the exit status the launcher would end with.
 */
public final class LauncherAccess {
    private LauncherAccess() {}

    /** Result of one in-process launcher run. */
    public static final class Result {
        public final int status;
        public final String out;
        public final String err;

        Result(int status, String out, String err) {
            this.status = status;
            this.out = out;
            this.err = err;
        }

        @Override
        public String toString() {
            return "status " + status + "\nstdout: " + out + "\nstderr: " + err;
        }
    }

    /** {@code rlvm ARGS} with the given environment; nothing is inherited from the process. */
    public static Result run(Map<String, String> env, String... args) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ByteArrayOutputStream e = new ByteArrayOutputStream();
        int status;
        try (PrintStream out = new PrintStream(o, true, StandardCharsets.UTF_8);
                PrintStream err = new PrintStream(e, true, StandardCharsets.UTF_8)) {
            status = Main.run(args, env, out, err);
        }
        return new Result(status, o.toString(StandardCharsets.UTF_8), e.toString(StandardCharsets.UTF_8));
    }

    /** Names of the checkers the launcher finds, in the order of preference. */
    public static List<String> checkerNames() {
        return Checkers.all().stream().map(Checker::name).toList();
    }

    public static String defaultCheckerName() throws Exception {
        return Checkers.select(null).name();
    }
}
