package rlvm.driver;

import java.util.concurrent.atomic.AtomicLongArray;

/**
 * The run's memory account (*agentic-note*: design-architecture-document, 6.7): bytes of the shared store plus, per checking
 * thread, the bytes its sessions hold (allocated, not merely used: see {@code RunHost.Lane.slackBytes}).
 * Sessions poll it through {@code RunSignals.memoryExhausted()} and give up their unit with
 * {@code BUDGET_MEMORY} instead of letting the runner's OOM killer end the process.
 *
 * <p><b>Who gives up.</b> When the sum passes the limit, not every session gives up: the thread
 * holding the most is the <em>victim</em> ({@link #exhausted(int)} is true for its slot only). Its
 * unit is declined warm, its session is reset, and the unit is checked again alone
 * ({@link HeavyGate}); everybody else keeps the work done so far. If the sum keeps growing
 * nevertheless (the victim does not poll, or the others grow), every thread gives up above
 * {@code limit + margin}. A thread that holds the heavy gate is exempt from both rules: it gives up
 * only when it does not fit into the limit by itself ({@link #exhaustedAlone(int)}), so whether a
 * unit is declined for memory does not depend on what the other workers happened to be doing.
 *
 * <p>Each thread owns one slot and reports its own total; reports are rare (a thread reports only
 * when its total moved by {@link #GRANULE} bytes or more), so the recomputation takes a lock. The
 * polls are volatile reads.
 */
public final class MemoryAccount {

    /** A thread re-reports when its footprint changed by at least this much. */
    public static final long GRANULE = 1L << 20;

    /** Slots are 16 longs (128 bytes) apart so that two threads never write one cache line. */
    private static final int PAD = 16;

    private final long limitBytes;
    private final long hardBytes;
    private final int slotCount;
    private final AtomicLongArray slots;
    /** The sum is over the limit. */
    private volatile boolean over;
    /** The sum is over the limit plus the margin: every thread gives up. */
    private volatile boolean hardOver;
    /** The slot that gives up while {@link #over}; -1 = none. */
    private volatile int victim = -1;
    /** The slot that holds the heavy gate (never the victim); -1 = none. */
    private volatile int exempt = -1;
    /** A slot that is never the victim (the environment thread: its declines are final); -1 = none. */
    private volatile int lastResort = -1;

    // guarded by `this`
    private long shared;
    private long peak;
    private long peakSlots;
    private long victims;

    public MemoryAccount(long limitBytes, int slots) {
        this.limitBytes = limitBytes;
        long margin = Math.min(1L << 30, Math.max(0, limitBytes) >> 3);
        this.hardBytes = limitBytes > Long.MAX_VALUE - margin ? Long.MAX_VALUE : limitBytes + margin;
        this.slotCount = slots;
        this.slots = new AtomicLongArray((slots + 1) * PAD);
    }

    public long limitBytes() {
        return limitBytes;
    }

    /** Bytes of the shared store; refreshed whenever the loader or the environment pass published. */
    public synchronized void setShared(long bytes) {
        shared = Math.max(0, bytes);
        recompute();
    }

    /** The calling thread's sessions hold {@code bytes} bytes in total. */
    public void report(int slot, long bytes) {
        slots.set((slot + 1) * PAD, Math.max(0, bytes));
        synchronized (this) {
            recompute();
        }
    }

    /** The thread of {@code slot} holds the heavy gate from now on ({@code -1}: nobody does). */
    public synchronized void setExempt(int slot) {
        exempt = slot;
        recompute();
    }

    /**
     * The thread of {@code slot} is never chosen to give way while others can (the environment
     * thread: nothing checks its units again); it gives up by {@link #exhaustedLastResort(int)}.
     */
    public synchronized void setLastResort(int slot) {
        lastResort = slot;
        recompute();
    }

    private void recompute() {
        long sum = shared;
        long held = 0;
        long most = -1;
        int mostSlot = -1;
        int ex = exempt;
        int lr = lastResort;
        for (int i = 0; i < slotCount; i++) {
            long b = slots.get((i + 1) * PAD);
            sum += b;
            held += b;
            if (sum < 0 || held < 0) {
                sum = Long.MAX_VALUE;
                held = Long.MAX_VALUE;
            }
            if (i != ex && i != lr && b > most) {
                most = b;
                mostSlot = i;
            }
        }
        if (sum > peak) {
            peak = sum;
        }
        if (held > peakSlots) {
            peakSlots = held;
        }
        boolean o = sum > limitBytes;
        // A victim with nothing to give back cannot help: then everybody gives up at once.
        boolean h = sum > hardBytes || (o && most < GRANULE);
        int v = o ? mostSlot : -1;
        if (v != victim) {
            if (v >= 0) {
                victims++;
            }
            victim = v;
        }
        if (h != hardOver) {
            hardOver = h;
        }
        if (o != over) {
            over = o;
        }
    }

    /** True while the accounted bytes exceed the limit. */
    public boolean exhausted() {
        return over;
    }

    /**
     * True when the thread of {@code slot} must give up its unit: the run is over its limit and this
     * thread holds the most, or the run is over the limit by more than the margin.
     */
    public boolean exhausted(int slot) {
        if (!over) {
            return false;
        }
        return hardOver || victim == slot;
    }

    /**
     * For the thread set by {@link #setLastResort(int)}: the run is over its limit by more than the
     * margin, nobody else has anything to give back, or this thread alone does not fit.
     */
    public boolean exhaustedLastResort(int slot) {
        if (!over) {
            return false;
        }
        return hardOver || exhaustedAlone(slot);
    }

    /** True when the shared store and the sessions of {@code slot} alone exceed the limit. */
    public boolean exhaustedAlone(int slot) {
        if (!over) {
            return false;
        }
        long own = slots.get((slot + 1) * PAD);
        long s;
        synchronized (this) {
            s = shared;
        }
        return s > limitBytes || own > limitBytes - s;
    }

    /** Bytes currently accounted. */
    public synchronized long accountedBytes() {
        long sum = shared;
        for (int i = 0; i < slotCount; i++) {
            sum += slots.get((i + 1) * PAD);
        }
        return sum;
    }

    /** Largest sum seen at a report. */
    public synchronized long peakBytes() {
        return peak;
    }

    /** Largest sum of the threads' slots (without the shared store) seen at a report. */
    public synchronized long peakSessionBytes() {
        return peakSlots;
    }

    /** How often a thread was chosen to give up its unit. */
    public synchronized long victimCount() {
        return victims;
    }
}
