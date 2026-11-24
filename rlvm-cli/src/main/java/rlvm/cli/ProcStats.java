package rlvm.cli;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Process statistics for {@code --stats}, read from {@code /proc/self} so that neither JMX nor
 * {@code java.management} is pulled into the native image. Every value is -1 where unavailable.
 */
final class ProcStats {
    /** Peak resident set size (what GNU time reports as max RSS), in kB. */
    final long peakRssKb;
    /** Current resident set size in kB. */
    final long rssKb;
    /** CPU time of all threads, user and system, in milliseconds. */
    final long userMs;
    final long sysMs;
    /** Live threads of the process. */
    final long threads;

    private ProcStats(long peakRssKb, long rssKb, long userMs, long sysMs, long threads) {
        this.peakRssKb = peakRssKb;
        this.rssKb = rssKb;
        this.userMs = userMs;
        this.sysMs = sysMs;
        this.threads = threads;
    }

    static ProcStats read() {
        long peak = -1;
        long rss = -1;
        long threads = -1;
        long user = -1;
        long sys = -1;
        try {
            for (String line : Files.readAllLines(Path.of("/proc/self/status"), StandardCharsets.ISO_8859_1)) {
                if (line.startsWith("VmHWM:")) {
                    peak = firstNumber(line);
                } else if (line.startsWith("VmRSS:")) {
                    rss = firstNumber(line);
                } else if (line.startsWith("Threads:")) {
                    threads = firstNumber(line);
                }
            }
            // "pid (comm) state ppid ... utime stime": comm may contain spaces, so count from the last ')'.
            String stat = Files.readString(Path.of("/proc/self/stat"), StandardCharsets.ISO_8859_1);
            String[] f = stat.substring(stat.lastIndexOf(')') + 2).trim().split(" +");
            // f[0] is field 3 (state); utime is field 14, stime field 15; unit: clock ticks, 100 per second on Linux.
            user = Long.parseLong(f[11]) * 10;
            sys = Long.parseLong(f[12]) * 10;
        } catch (Exception e) {
            // Not Linux, or /proc not mounted: leave the defaults.
        }
        return new ProcStats(peak, rss, user, sys, threads);
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
