package rlvm.main;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Process-wide counters for the per-pass report of {@link Passes}: CPU time and resident memory from
 * {@code /proc/self} (the same source as the launcher's {@code --stats}; it works in the native image),
 * collector time and count from the runtime's collector beans. Every value is -1 where unavailable.
 * Nothing here runs unless {@code -X passes=N} asks for more than one pass.
 */
final class ProcessClock {
    private ProcessClock() {}

    /**
     * CPU time of the process in milliseconds, user + system, all threads, the ended ones included:
     * fields 14 and 15 of {@code /proc/self/stat}, in clock ticks of 10 ms.
     */
    static long cpuMillis() {
        try {
            // "pid (comm) state ppid ...": comm may contain spaces, so count from the last ')'.
            String stat = Files.readString(Path.of("/proc/self/stat"), StandardCharsets.ISO_8859_1);
            String[] f = stat.substring(stat.lastIndexOf(')') + 2).trim().split(" +");
            return (Long.parseLong(f[11]) + Long.parseLong(f[12])) * 10;
        } catch (Exception e) {
            return -1; // not Linux, or /proc not mounted
        }
    }

    /** {@code {VmRSS, VmHWM}} of {@code /proc/self/status} in kB: resident set now, and its peak. */
    static long[] rssKb() {
        long[] out = {-1, -1};
        try {
            for (String line : Files.readAllLines(Path.of("/proc/self/status"), StandardCharsets.ISO_8859_1)) {
                if (line.startsWith("VmRSS:")) {
                    out[0] = firstNumber(line);
                } else if (line.startsWith("VmHWM:")) {
                    out[1] = firstNumber(line);
                }
            }
        } catch (Exception e) {
            // unavailable
        }
        return out;
    }

    /**
     * {@code {collections, milliseconds}} summed over the runtime's collectors since the process
     * started (HotSpot: young and old collector of the chosen GC; native image: its scavenger beans).
     */
    static long[] gc() {
        long count = 0;
        long millis = 0;
        boolean any = false;
        try {
            for (java.lang.management.GarbageCollectorMXBean b : java.lang.management.ManagementFactory
                    .getGarbageCollectorMXBeans()) {
                long c = b.getCollectionCount();
                long t = b.getCollectionTime();
                if (c >= 0) {
                    count += c;
                    any = true;
                }
                if (t >= 0) {
                    millis += t;
                }
            }
        } catch (Throwable e) {
            return new long[] {-1, -1}; // a runtime without the management beans
        }
        return any ? new long[] {count, millis} : new long[] {-1, -1};
    }

    /** Heap in use right now, in kB (as the runtime reports it; exact only right after a collection). */
    static long heapUsedKb() {
        Runtime rt = Runtime.getRuntime();
        return (rt.totalMemory() - rt.freeMemory()) >> 10;
    }

    private static long firstNumber(String line) {
        long n = 0;
        boolean seen = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c >= '0' && c <= '9') {
                n = n * 10 + (c - '0');
                seen = true;
            } else if (seen) {
                break;
            }
        }
        return seen ? n : -1;
    }
}
