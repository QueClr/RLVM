package rlvm.cli;

import java.nio.file.Path;

/**
 * A checker registered through {@code META-INF/services/rlvm.cli.Checker} of the test class path, the
 * way a real driver module registers itself. Its verdict is chosen with {@code -X verdict=...}.
 */
public final class TestPluginChecker implements Checker {
    /** Set by {@link #check}: what the launcher passed in, for assertions. */
    static volatile Path lastExport;
    static volatile Options lastOptions;
    static volatile String lastThreadName;

    @Override
    public String name() {
        return "test-plugin";
    }

    @Override
    public String description() {
        return "service-loaded test double";
    }

    @Override
    public int priority() {
        return 5;
    }

    @Override
    public String usage() {
        return "  verdict=accept|reject|decline   what this test double answers\n";
    }

    @Override
    public String defaults() {
        return "the test double declines";
    }

    @Override
    public Verdict check(Path export, Options options) throws UsageException {
        lastExport = export;
        lastOptions = options;
        lastThreadName = Thread.currentThread().getName();
        return switch (options.extra("verdict", "decline")) {
            case "accept" -> Verdict.ACCEPT;
            case "reject" -> Verdict.REJECT;
            case "decline" -> Verdict.DECLINE;
            // What a real checker does with a setting it does not know.
            case "unknown-setting" -> throw new UsageException("unknown checker setting -X verdict=unknown-setting");
            default -> throw new IllegalArgumentException("verdict?");
        };
    }
}
