package rlvm.driver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import rlvm.api.StatsSink;

/**
 * Statistics of one run and the {@link StatsSink} that engines write into. Collected only when
 * enabled ({@code RunConfig.statsFile} is set); otherwise every method is a no-op and nothing is
 * allocated.
 *
 * <p>Content (ARCHITECTURE.md 6.9): wall time per phase (load, check), CPU time per thread, engine
 * steps, units and session resets per worker, peak accounted bytes, the 50 heaviest units by steps,
 * scheduler counters, and whatever the engines add through {@link StatsSink}. Everything is recorded
 * per unit or per phase, never per step. Thread-safe (one lock; statistics are off by default).
 *
 * <p>JSON layout: {@code {"phases": {name: {"wallNanos": n}}, "threads": {name: {"cpuNanos": n, "runQueueNanos": n}},
 * "values": {dotted.key: value}, "tables": {key: {"columns": [...], "rows": [{"label": s, "values":
 * [...]}]}}, "units": {"count": n, "steps": n, "nanos": n, "accepted": n, "rejected": n, "declined":
 * n, "top": [{"unit": u, "worker": w, "steps": s, "nanos": t, "outcome": o}], "slow": [the same, by nanos],
 * "histogram": [{"belowMicros": 2^k, "units": n, "nanos": t}]}}}.
 */
public final class RunStats implements StatsSink {

    private final boolean enabled;

    // ---- guarded by `this`; all null when disabled --------------------------------------------------------
    private String[] phaseNames;
    private long[] phaseStart;
    private long[] phaseNanos;
    private int phaseCount;

    private String[] threadNames;
    private long[] threadCpu;
    private long[] threadWait;
    private int threadCount;

    private String[] keys;
    private String[] values; // rendered JSON values
    private int valueCount;

    private String[] tableKeys;
    private String[] tableJson;
    private int tableCount;

    /** Everything recorded per unit; workers keep one of their own and merge it at their end. */
    private UnitStats units;

    public RunStats(boolean enabled) {
        this.enabled = enabled;
        if (enabled) {
            phaseNames = new String[8];
            phaseStart = new long[8];
            phaseNanos = new long[8];
            threadNames = new String[16];
            threadCpu = new long[16];
            threadWait = new long[16];
            keys = new String[64];
            values = new String[64];
            tableKeys = new String[8];
            tableJson = new String[8];
            units = new UnitStats();
        }
    }

    public boolean enabled() {
        return enabled;
    }

    /** Marks the start of a phase; returns a token for {@link #phaseEnd(long)}. */
    public long phaseStart(String name) {
        if (!enabled) {
            return 0;
        }
        long now = System.nanoTime();
        synchronized (this) {
            if (phaseCount == phaseNames.length) {
                phaseNames = java.util.Arrays.copyOf(phaseNames, phaseCount * 2);
                phaseStart = java.util.Arrays.copyOf(phaseStart, phaseCount * 2);
                phaseNanos = java.util.Arrays.copyOf(phaseNanos, phaseCount * 2);
            }
            phaseNames[phaseCount] = name;
            phaseStart[phaseCount] = now;
            phaseNanos[phaseCount] = -1;
            return ++phaseCount;
        }
    }

    public void phaseEnd(long token) {
        if (!enabled || token <= 0) {
            return;
        }
        long now = System.nanoTime();
        synchronized (this) {
            int i = (int) token - 1;
            if (i < phaseCount && phaseNanos[i] < 0) {
                phaseNanos[i] = now - phaseStart[i];
            }
        }
    }

    /** Wall nanoseconds of the named phase, or -1 if it did not run or has not ended. */
    public synchronized long phaseNanos(String name) {
        if (!enabled) {
            return -1;
        }
        for (int i = 0; i < phaseCount; i++) {
            if (phaseNames[i].equals(name)) {
                return phaseNanos[i];
            }
        }
        return -1;
    }

    /** Records the CPU time a thread consumed (called by the thread itself when it ends). */
    public void threadCpu(String thread, long cpuNanos) {
        threadTimes(thread, cpuNanos, -1);
    }

    /**
     * Records the CPU time a thread consumed and the time it spent runnable but waiting for a CPU
     * (-1 = unknown). The second number tells whether workers that were not on a CPU were starved by
     * the machine (run-queue wait) or blocked (locks, stop-the-world pauses).
     */
    public void threadTimes(String thread, long cpuNanos, long runQueueNanos) {
        if (!enabled) {
            return;
        }
        synchronized (this) {
            if (threadCount == threadNames.length) {
                threadNames = java.util.Arrays.copyOf(threadNames, threadCount * 2);
                threadCpu = java.util.Arrays.copyOf(threadCpu, threadCount * 2);
                threadWait = java.util.Arrays.copyOf(threadWait, threadCount * 2);
            }
            threadNames[threadCount] = thread;
            threadWait[threadCount] = runQueueNanos;
            threadCpu[threadCount++] = cpuNanos;
        }
    }

    /**
     * CPU time of the calling thread in nanoseconds, from {@code /proc/thread-self/schedstat} (no
     * {@code java.management}, which would be pulled into the native image); -1 where unavailable.
     */
    public static long currentThreadCpuNanos() {
        return currentThreadSched()[0];
    }

    /**
     * {@code {on-CPU nanoseconds, run-queue wait nanoseconds}} of the calling thread from
     * {@code /proc/thread-self/schedstat}; -1 where unavailable.
     */
    public static long[] currentThreadSched() {
        long[] out = {-1, -1};
        try {
            String s = Files.readString(Path.of("/proc/thread-self/schedstat"), StandardCharsets.ISO_8859_1).trim();
            String[] f = s.split(" +");
            out[0] = Long.parseLong(f[0]);
            if (f.length > 1) {
                out[1] = Long.parseLong(f[1]);
            }
        } catch (IOException | RuntimeException e) {
            // not Linux, or no scheduler statistics: unknown
        }
        return out;
    }

    /**
     * Records one checked unit. Takes this object's lock: a worker records into a {@link UnitStats}
     * of its own instead and hands it over once, with {@link #mergeUnits(UnitStats)}.
     */
    public void unit(int unit, int worker, long steps, long nanos, int outcome) {
        if (!enabled) {
            return;
        }
        synchronized (this) {
            units.unit(unit, worker, steps, nanos, outcome);
        }
    }

    /** Adds the per-unit statistics a checking thread collected by itself. */
    void mergeUnits(UnitStats of) {
        if (!enabled || of == null) {
            return;
        }
        synchronized (this) {
            units.merge(of);
        }
    }

    @Override
    public void put(String key, long value) {
        if (enabled) {
            putRendered(key, Long.toString(value));
        }
    }

    @Override
    public void put(String key, double value) {
        if (enabled) {
            putRendered(key, Double.isFinite(value) ? Double.toString(value) : "null");
        }
    }

    @Override
    public void put(String key, String value) {
        if (enabled) {
            putRendered(key, value == null ? "null" : quote(value));
        }
    }

    private synchronized void putRendered(String key, String rendered) {
        for (int i = 0; i < valueCount; i++) {
            if (keys[i].equals(key)) {
                values[i] = rendered;
                return;
            }
        }
        if (valueCount == keys.length) {
            keys = java.util.Arrays.copyOf(keys, valueCount * 2);
            values = java.util.Arrays.copyOf(values, valueCount * 2);
        }
        keys[valueCount] = key;
        values[valueCount++] = rendered;
    }

    /** The value recorded under {@code key} as rendered JSON, or null. */
    public synchronized String value(String key) {
        if (!enabled) {
            return null;
        }
        for (int i = 0; i < valueCount; i++) {
            if (keys[i].equals(key)) {
                return values[i];
            }
        }
        return null;
    }

    @Override
    public void table(String key, String[] columns, long[][] rows, String[] rowLabels) {
        if (!enabled) {
            return;
        }
        StringBuilder sb = new StringBuilder(64 + rows.length * 32);
        sb.append("{\"columns\": [");
        for (int i = 0; i < columns.length; i++) {
            sb.append(i == 0 ? "" : ", ").append(quote(columns[i]));
        }
        sb.append("], \"rows\": [");
        for (int r = 0; r < rows.length; r++) {
            sb.append(r == 0 ? "" : ", ").append("{\"label\": ");
            sb.append(rowLabels != null && r < rowLabels.length && rowLabels[r] != null ? quote(rowLabels[r]) : "null");
            sb.append(", \"values\": [");
            for (int c = 0; c < rows[r].length; c++) {
                sb.append(c == 0 ? "" : ", ").append(rows[r][c]);
            }
            sb.append("]}");
        }
        sb.append("]}");
        synchronized (this) {
            if (tableCount == tableKeys.length) {
                tableKeys = java.util.Arrays.copyOf(tableKeys, tableCount * 2);
                tableJson = java.util.Arrays.copyOf(tableJson, tableCount * 2);
            }
            tableKeys[tableCount] = key;
            tableJson[tableCount++] = sb.toString();
        }
    }

    /** Everything as one JSON object (see the class comment); "{}" when disabled. */
    public synchronized String toJson() {
        if (!enabled) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder(4096);
        sb.append("{\n  \"phases\": {");
        for (int i = 0; i < phaseCount; i++) {
            sb.append(i == 0 ? "" : ", ").append(quote(phaseNames[i])).append(": {\"wallNanos\": ")
                    .append(phaseNanos[i]).append('}');
        }
        sb.append("},\n  \"threads\": {");
        for (int i = 0; i < threadCount; i++) {
            sb.append(i == 0 ? "" : ", ").append(quote(threadNames[i])).append(": {\"cpuNanos\": ")
                    .append(threadCpu[i]).append(", \"runQueueNanos\": ").append(threadWait[i]).append('}');
        }
        sb.append("},\n  \"values\": {");
        for (int i = 0; i < valueCount; i++) {
            sb.append(i == 0 ? "\n    " : ",\n    ").append(quote(keys[i])).append(": ").append(values[i]);
        }
        sb.append(valueCount == 0 ? "" : "\n  ").append("},\n  \"tables\": {");
        for (int i = 0; i < tableCount; i++) {
            sb.append(i == 0 ? "\n    " : ",\n    ").append(quote(tableKeys[i])).append(": ").append(tableJson[i]);
        }
        sb.append(tableCount == 0 ? "" : "\n  ").append("},\n  \"units\": {\"count\": ").append(units.count);
        sb.append(", \"steps\": ").append(units.steps).append(", \"nanos\": ").append(units.nanos);
        sb.append(", \"accepted\": ").append(units.byOutcome[0]).append(", \"rejected\": ").append(units.byOutcome[1]);
        sb.append(", \"declined\": ").append(units.byOutcome[2]).append(", \"top\": [");
        // heaviest first (at most 50 entries)
        int[] order = UnitStats.descending(units.top, units.topCount);
        for (int n = 0; n < order.length; n++) {
            int o = order[n] * UnitStats.W;
            sb.append(n == 0 ? "\n    " : ",\n    ").append("{\"unit\": ").append(units.top[o + 2]).append(", \"worker\": ")
                    .append(units.top[o + 3]).append(", \"steps\": ").append(units.top[o]).append(", \"nanos\": ")
                    .append(units.top[o + 1]).append(", \"outcome\": ").append(units.top[o + 4]).append('}');
        }
        // slowest first: the same entries ordered by wall time
        sb.append(order.length == 0 ? "" : "\n  ").append("], \"slow\": [");
        order = UnitStats.descending(units.slow, units.slowCount);
        for (int n = 0; n < order.length; n++) {
            int o = order[n] * UnitStats.W;
            sb.append(n == 0 ? "\n    " : ",\n    ").append("{\"unit\": ").append(units.slow[o + 2]).append(", \"worker\": ")
                    .append(units.slow[o + 3]).append(", \"steps\": ").append(units.slow[o + 1]).append(", \"nanos\": ")
                    .append(units.slow[o]).append(", \"outcome\": ").append(units.slow[o + 4]).append('}');
        }
        // wall time by duration class: units that took less than 2^k microseconds
        sb.append(order.length == 0 ? "" : "\n  ").append("], \"histogram\": [");
        boolean first = true;
        for (int k = 0; k < UnitStats.HIST; k++) {
            if (units.hist[2 * k] == 0) {
                continue;
            }
            sb.append(first ? "" : ", ").append("{\"belowMicros\": ").append(1L << k).append(", \"units\": ")
                    .append(units.hist[2 * k]).append(", \"nanos\": ").append(units.hist[2 * k + 1]).append('}');
            first = false;
        }
        sb.append("]}\n}\n");
        return sb.toString();
    }

    /** Writes everything as one JSON object. Does nothing when statistics are disabled. */
    public void writeJson(Path file) throws IOException {
        if (!enabled) {
            return;
        }
        Files.writeString(file, toJson(), StandardCharsets.UTF_8);
    }

    static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.append('"').toString();
    }
}
