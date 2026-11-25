package rlvm.driver;

/**
 * The heavy lane of *agentic-note*: design-reconciliation-document, section 3 ("the heavy lane is for memory retries") as mutual
 * exclusion instead of a thread: a unit that was declined for memory is checked again while its
 * worker holds this gate, one such unit at a time, on the worker's own deep-stack thread. The
 * holder is exempt from the run's memory watermark (it gives up only when it does not fit into the
 * limit by itself, {@link MemoryAccount#exhaustedAlone}); the other workers go on with ordinary
 * units and are the ones that give way when the sum passes the limit.
 *
 * <p><b>Order.</b> The gate goes to the lowest waiting unit, and a holder gives way to a lower unit
 * that starts waiting ({@link #shouldYield}: its stage ends with a decline that is not recorded, and
 * it enters again). So the lowest undecided unit of the run never waits for a higher one, which is
 * what the progress argument of the scheduler needs: a unit that never terminates behind an
 * ill-typed unit cannot keep that unit from being checked and recorded.
 *
 * <p><b>Cancellation.</b> A waiting unit that comes to lie above the first failure leaves the queue
 * ({@link #enter} returns false). Waiters are woken by {@link #leave} and by {@link #wake()} (the
 * scheduler's wake-up after a cancellation); they also look at the first failure every
 * {@link #RECHECK_MILLIS} ms, so that no wake-up can be lost. Waiting is rare (two heavy units at
 * the same time) and costs no CPU time.
 */
final class HeavyGate {

    private static final long RECHECK_MILLIS = 50;

    private final VerdictBoard board;
    private final MemoryAccount account;

    // ---- guarded by `this` ---------------------------------------------------------------------------
    /** The unit that holds the gate, or -1. */
    private int holder = -1;
    /** Waiting units, unordered. */
    private int[] waiting = new int[4];
    private int waitingCount;
    private long entries;
    private long waits;
    private long yields;

    /** The lowest waiting unit, or {@code Integer.MAX_VALUE}: what a holder compares itself with. */
    private volatile int lowestWaiting = Integer.MAX_VALUE;

    HeavyGate(VerdictBoard board, MemoryAccount account) {
        this.board = board;
        this.account = account;
    }

    /**
     * Blocks until {@code unit} holds the gate.
     *
     * @param slot the caller's slot of the memory account; it is exempt from the watermark while it
     *     holds the gate
     * @return false if the unit came to lie above the first failure while waiting (the gate is then
     *     not held), or the thread was interrupted
     */
    boolean enter(int unit, int slot) {
        synchronized (this) {
            if (unit > board.firstFailure()) {
                return false;
            }
            add(unit);
            boolean waited = false;
            try {
                while (holder >= 0 || lowestWaiting != unit) {
                    if (unit > board.firstFailure()) {
                        return false;
                    }
                    if (!waited) {
                        waited = true;
                        waits++;
                    }
                    try {
                        wait(RECHECK_MILLIS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
                if (unit > board.firstFailure()) {
                    return false;
                }
                holder = unit;
                entries++;
            } finally {
                remove(unit);
                if (holder != unit) {
                    notifyAll(); // the next lowest unit may be able to go
                }
            }
        }
        account.setExempt(slot);
        return true;
    }

    /** Gives the gate up. */
    void leave(int unit) {
        account.setExempt(-1);
        synchronized (this) {
            if (holder == unit) {
                holder = -1;
            }
            notifyAll();
        }
    }

    /**
     * Polled by the holder (a volatile read): a lower unit is waiting, give way.
     *
     * @param unit the unit the caller holds the gate for
     */
    boolean shouldYield(int unit) {
        if (lowestWaiting < unit) {
            synchronized (this) {
                yields++;
            }
            return true;
        }
        return false;
    }

    /** Wakes the waiters so that they look at the first failure again. */
    synchronized void wake() {
        notifyAll();
    }

    private void add(int unit) {
        if (waitingCount == waiting.length) {
            waiting = java.util.Arrays.copyOf(waiting, waitingCount * 2);
        }
        waiting[waitingCount++] = unit;
        if (unit < lowestWaiting) {
            lowestWaiting = unit;
        }
    }

    private void remove(int unit) {
        int low = Integer.MAX_VALUE;
        int n = 0;
        boolean removed = false;
        for (int i = 0; i < waitingCount; i++) {
            int u = waiting[i];
            if (u == unit && !removed) {
                removed = true;
                continue;
            }
            waiting[n++] = u;
            if (u < low) {
                low = u;
            }
        }
        waitingCount = n;
        lowestWaiting = low;
    }

    synchronized long entryCount() {
        return entries;
    }

    synchronized long waitCount() {
        return waits;
    }

    synchronized long yieldCount() {
        return yields;
    }
}
