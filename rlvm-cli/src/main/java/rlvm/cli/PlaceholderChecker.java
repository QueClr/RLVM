package rlvm.cli;

import java.nio.file.Path;

/**
 * Stands in until a real driver is on the class path: declines every export without reading it.
 * Declining is the only answer that can never be wrong.
 */
public final class PlaceholderChecker implements Checker {
    public static final String NAME = "placeholder";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "no kernel plugged in; declines every export";
    }

    @Override
    public int priority() {
        return Integer.MIN_VALUE;
    }

    @Override
    public Verdict check(Path export, Options options) {
        if (!options.quiet()) {
            options.err().println("rlvm: placeholder checker: no kernel implementation on the class path, declining");
        }
        return Verdict.DECLINE;
    }
}
