import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;

/** Shared measurement helpers: process CPU time (all threads incl. GC/JIT), allocation, GC counters. */
final class Bench {
    static final com.sun.management.ThreadMXBean TMX =
        (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    static final com.sun.management.OperatingSystemMXBean OS =
        (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

    static long gcCount() {
        long c = 0;
        for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) c += Math.max(0, b.getCollectionCount());
        return c;
    }
    static long gcMillis() {
        long c = 0;
        for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) c += Math.max(0, b.getCollectionTime());
        return c;
    }
    static long heapUsed() {
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }
    /** Bytes in the old generation (call right after System.gc(): everything live has been compacted there). */
    static long oldUsed() {
        long u = 0;
        for (java.lang.management.MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans()) {
            String n = p.getName();
            if (n.contains("Tenured") || n.contains("Old")) u += p.getUsage().getUsed();
        }
        return u;
    }

    final String name;
    long cpu0, wall0, alloc0, gcc0, gcm0, tcpu0;
    Bench(String name) { this.name = name; }

    Bench start() {
        gcc0 = gcCount(); gcm0 = gcMillis();
        alloc0 = TMX.getThreadAllocatedBytes(Thread.currentThread().threadId());
        tcpu0 = TMX.getCurrentThreadCpuTime();
        cpu0 = OS.getProcessCpuTime();
        wall0 = System.nanoTime();
        return this;
    }

    /** ops = number of logical operations for per-op normalisation. */
    void stop(long ops) {
        long wall = System.nanoTime() - wall0;
        long cpu = OS.getProcessCpuTime() - cpu0;
        long tcpu = TMX.getCurrentThreadCpuTime() - tcpu0;
        long alloc = TMX.getThreadAllocatedBytes(Thread.currentThread().threadId()) - alloc0;
        long gcc = gcCount() - gcc0, gcm = gcMillis() - gcm0;
        System.out.printf("%-34s procCPU %8.1f ms  threadCPU %8.1f ms  wall %8.1f ms  %7.2f ns/op(procCPU)  %7.1f B/op  GCs %4d  gc %5d ms%n",
            name, cpu / 1e6, tcpu / 1e6, wall / 1e6, (double) cpu / ops, (double) alloc / ops, gcc, gcm);
    }

    /** Runs r reps times; reports the minimum thread-CPU and process-CPU time over the last (reps - warm) runs. */
    static void best(String name, long ops, int reps, int warm, Runnable r) {
        long bestT = Long.MAX_VALUE, bestP = Long.MAX_VALUE, alloc = 0, gcs = 0, gcm = 0;
        for (int i = 0; i < reps; i++) {
            long g0 = gcCount(), m0 = gcMillis();
            long a0 = TMX.getThreadAllocatedBytes(Thread.currentThread().threadId());
            long p0 = OS.getProcessCpuTime();
            long t0 = TMX.getCurrentThreadCpuTime();
            r.run();
            long t = TMX.getCurrentThreadCpuTime() - t0;
            long pc = OS.getProcessCpuTime() - p0;
            if (i >= warm) {
                bestT = Math.min(bestT, t); bestP = Math.min(bestP, pc);
                alloc = TMX.getThreadAllocatedBytes(Thread.currentThread().threadId()) - a0;
                gcs = gcCount() - g0; gcm = gcMillis() - m0;
            }
        }
        System.out.printf("%-36s threadCPU %8.1f ms (%6.2f ns/op)  procCPU %8.1f ms  %7.1f B/op  GCs/run %3d (%d ms)%n",
            name, bestT / 1e6, (double) bestT / ops, bestP / 1e6, (double) alloc / ops, gcs, gcm);
    }
}
