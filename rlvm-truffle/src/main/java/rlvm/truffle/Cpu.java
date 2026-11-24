package rlvm.truffle;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;

/** CPU clocks for the governor (ISA 8.5: budgets are in CPU time). */
final class Cpu {
    private Cpu() {}

    private static volatile boolean threadClockBroken;
    private static volatile boolean procBroken;
    private static final long START_NANOS = System.nanoTime();

    /** CPU time of the calling thread in nanoseconds (the wall clock if the platform has no thread clock). */
    static long threadNanos() {
        if (!threadClockBroken) {
            try {
                long t = ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime();
                if (t >= 0) {
                    return t;
                }
            } catch (Throwable e) {
                // fall through
            }
            threadClockBroken = true;
        }
        return System.nanoTime();
    }

    /**
     * CPU time of the process (all threads, user + system) in nanoseconds: {@code /proc/self/stat} (10 ms
     * resolution), else the JVM's process clock, else the wall clock since start times the processors.
     */
    static long processNanos() {
        if (!procBroken) {
            try {
                String s = Files.readString(Path.of("/proc/self/stat"));
                int p = s.lastIndexOf(')');
                String[] f = s.substring(p + 2).split(" ");
                // after "pid (comm) ": state is field 3, utime 14, stime 15 (1-based)
                long ticks = Long.parseLong(f[11]) + Long.parseLong(f[12]);
                return ticks * 10_000_000L;
            } catch (Throwable e) {
                procBroken = true;
            }
        }
        try {
            java.lang.management.OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
            if (os instanceof com.sun.management.OperatingSystemMXBean) {
                long t = ((com.sun.management.OperatingSystemMXBean) os).getProcessCpuTime();
                if (t >= 0) {
                    return t;
                }
            }
        } catch (Throwable e) {
            // fall through
        }
        return System.nanoTime() - START_NANOS;
    }
}
