package rlvm.cli;

import java.io.PrintStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * What the launcher hands to a {@link Checker}. Immutable.
 *
 * <p>New checker settings do not need a launcher change: anything given as {@code -X key=value} on the
 * command line arrives in {@link #extra()}.
 */
public final class Options {
    /** Value of {@link #threads()} when the user did not choose: the checker decides. */
    public static final int AUTO_THREADS = 0;
    /** Upper bound used by {@link #effectiveThreads()}: the arena runner has 8 vCPUs. */
    public static final int MAX_AUTO_THREADS = 8;
    /** Default stack of the checker thread; virtual memory only, pages are committed when touched. */
    public static final long DEFAULT_STACK_BYTES = 512L << 20;

    private final int threads;
    private final boolean stats;
    private final boolean quiet;
    private final long stackBytes;
    private final Map<String, String> extra;
    private final PrintStream out;
    private final PrintStream err;

    private Options(Builder b) {
        this.threads = b.threads;
        this.stats = b.stats;
        this.quiet = b.quiet;
        this.stackBytes = b.stackBytes;
        this.extra = Collections.unmodifiableMap(new LinkedHashMap<>(b.extra));
        this.out = Objects.requireNonNull(b.out, "out");
        this.err = Objects.requireNonNull(b.err, "err");
    }

    /** Worker threads requested with {@code --threads}/{@code RLVM_THREADS}, or {@link #AUTO_THREADS}. */
    public int threads() {
        return threads;
    }

    /**
     * The requested worker count, or for {@link #AUTO_THREADS} the number of available processors capped
     * at {@link #MAX_AUTO_THREADS}. A checker is free to use fewer (small exports are cheaper on one thread).
     */
    public int effectiveThreads() {
        if (threads > 0) {
            return threads;
        }
        return Math.max(1, Math.min(MAX_AUTO_THREADS, Runtime.getRuntime().availableProcessors()));
    }

    /** {@code --stats}: the checker should print its statistics to {@link #err()} when it finishes. */
    public boolean stats() {
        return stats;
    }

    /** {@code --quiet}: print nothing but errors. */
    public boolean quiet() {
        return quiet;
    }

    /**
     * Stack size in bytes of the thread that {@link Checker#check} is called on. Worker threads that the
     * checker creates itself should use the same size: {@code new Thread(null, body, name, stackBytes())}.
     */
    public long stackBytes() {
        return stackBytes;
    }

    /** {@code -X key=value} settings, in command-line order; a bare {@code -X key} has the value {@code "true"}. */
    public Map<String, String> extra() {
        return extra;
    }

    /** One {@code -X} setting, or {@code fallback} when it was not given. */
    public String extra(String key, String fallback) {
        String v = extra.get(key);
        return v == null ? fallback : v;
    }

    /** Standard output of the run. The arena keeps only the first and last 100 lines; printing costs instructions. */
    public PrintStream out() {
        return out;
    }

    /** Diagnostics and statistics. */
    public PrintStream err() {
        return err;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Mutable companion; also convenient in tests. */
    public static final class Builder {
        private int threads = AUTO_THREADS;
        private boolean stats;
        private boolean quiet;
        private long stackBytes = DEFAULT_STACK_BYTES;
        private final Map<String, String> extra = new LinkedHashMap<>();
        private PrintStream out = System.out;
        private PrintStream err = System.err;

        private Builder() {
        }

        public Builder threads(int n) {
            if (n < 0) {
                throw new IllegalArgumentException("threads must be >= 0: " + n);
            }
            this.threads = n;
            return this;
        }

        public Builder stats(boolean on) {
            this.stats = on;
            return this;
        }

        public Builder quiet(boolean on) {
            this.quiet = on;
            return this;
        }

        public Builder stackBytes(long bytes) {
            if (bytes <= 0) {
                throw new IllegalArgumentException("stack size must be positive: " + bytes);
            }
            this.stackBytes = bytes;
            return this;
        }

        public Builder extra(String key, String value) {
            this.extra.put(Objects.requireNonNull(key), Objects.requireNonNull(value));
            return this;
        }

        public Builder out(PrintStream stream) {
            this.out = stream;
            return this;
        }

        public Builder err(PrintStream stream) {
            this.err = stream;
            return this;
        }

        public Options build() {
            return new Options(this);
        }
    }
}
