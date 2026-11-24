package rlvm.driver;

/**
 * The loaded frontier of a pipelined run as a monitor: the loading thread announces publications and
 * the end of the load, the environment thread and workers wait on it. Nobody polls: a waiter parks
 * until the frontier moves, the load ends or (for cancellable waits) a short interval passed in
 * which the first failure may have moved.
 *
 * <pre>
 * loader thread:   published(n)*  ...  end()        (end() also after a failed load)
 * environment:     awaitBeyond(seen)                 -> units loaded now; returns at once after end()
 * a worker:        awaitEnd(unit)                    -> true once the load has ended
 * </pre>
 */
final class LoadFrontier {

    private static final long RECHECK_MILLIS = 50;

    private final VerdictBoard board;
    private volatile int loaded;
    private volatile boolean ended;
    private long publications;

    LoadFrontier(VerdictBoard board) {
        this.board = board;
    }

    /** Units {@code [0, units)} are published (monotone; smaller values are ignored). */
    synchronized void published(int units) {
        publications++;
        if (units > loaded) {
            loaded = units;
        }
        notifyAll();
    }

    /** The load is over (complete or failed): {@link #loaded()} is final. */
    synchronized void end() {
        ended = true;
        notifyAll();
    }

    int loaded() {
        return loaded;
    }

    boolean ended() {
        return ended;
    }

    synchronized long publications() {
        return publications;
    }

    /**
     * Parks until more than {@code seen} units are loaded, the load has ended, or the run is
     * cancelled (the first failure lies below {@code seen}).
     *
     * @return the units loaded now
     */
    synchronized int awaitBeyond(int seen) {
        while (loaded <= seen && !ended && board.firstFailure() >= seen) {
            try {
                wait(RECHECK_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return loaded;
    }

    /**
     * Parks until the load has ended.
     *
     * @return false if {@code unit} came to lie above the first failure meanwhile (its outcome can
     *     no longer matter), or the thread was interrupted
     */
    synchronized boolean awaitEnd(int unit) {
        while (!ended) {
            if (unit > board.firstFailure()) {
                return false;
            }
            try {
                wait(RECHECK_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return unit <= board.firstFailure();
    }

    /** Wakes the waiters so that they look at the first failure again. */
    synchronized void wake() {
        notifyAll();
    }
}
