package rlvm.driver;

import java.util.Arrays;

import rlvm.store.Store;

/**
 * Distributes units to workers (RECONCILIATION.md section 3 "Scheduler", replacing the claim rule of
 * ARCHITECTURE.md 6.3; the scheme is gruszka's, applied per publication batch).
 *
 * <pre>
 * ready      units [0, ready) may be handed out (the frontier announced through unitsReady)
 * batch      the units that became ready since the last split; when a worker without work finds one,
 *            it is cut into min(W, |batch| / chunk) contiguous runs (at least one), one per worker
 *            starting with the caller; a run is appended to its worker's queue
 * claim(w)   1. units other workers gave back, unless w's own run starts below them
 *            2. the next `chunk` units from the front of w's first run
 *            3. no run left: split a waiting batch, if there is one, and go to 2
 *            4. else steal: the largest remaining run of any worker is cut in half and w takes the
 *               back half (the whole run if it is no longer than a chunk); go to 2
 *            5. nothing anywhere: park until units become ready, units are given back, or the
 *               scheduler is closed and no worker holds a claim (then 0 = terminate)
 * cut        units above the board's first failure are never handed out
 * </pre>
 * A worker therefore sees increasing units inside a claim and from claim to claim until it steals,
 * and neighbouring declarations stay on one worker. With one worker this degenerates to file order.
 * Which worker checks a unit depends on timing; which units are checked below the first failure,
 * and therefore the verdict, does not.
 *
 * <p>Thread-safe: one lock, taken once per claim (a claim is {@code chunk} = 32 units), never while
 * a unit is checked. Idle workers wait on the lock's monitor; nothing spins.
 *
 * <p>Only {@code OrderPolicy.Kind.FILE} is implemented: positions are unit indices.
 */
public final class Scheduler {

    /** Where the ready frontier is read from by {@link #unitsReady()} and {@link #close()}. */
    public interface Frontier {
        int readyUnits();
    }

    private final Frontier frontier;
    private final VerdictBoard board;
    private final int workers;
    private final int chunk;

    // ---- everything below is guarded by `this` ----------------------------------------------------------
    /** Units {@code [0, ready)} may be handed out. */
    private int ready;
    /** Units {@code [0, split)} have been distributed into runs. */
    private int split;
    /** {@code runs[w]} holds worker w's runs as (lo, hi) pairs, ascending; {@code runLen[w]} ints are used. */
    private final int[][] runs;
    private final int[] runLen;
    /** Units given back, stored in descending order so that popping yields ascending units. */
    private int[] spill = new int[64];
    private int spillLen;
    private int[] heavy = new int[16];
    private int heavyHead;
    private int heavyTail;
    private boolean closed;
    private boolean heavyClosed;
    private final boolean[] holding;
    private int busy;
    private int waiting;
    private long claims;
    private long steals;
    private long splits;
    private long spilled;

    /** The skeleton's constructor: frontier = {@code Store.envUnits()}, worker count and chunk from the configuration. */
    public Scheduler(Store store, OrderPolicy order, RunConfig config, VerdictBoard board) {
        this(new StoreFrontier(store), order, Run.resolveWorkers(config.workers), config.chunkUnits, board);
    }

    /**
     * @param frontier source of the ready frontier for {@link #unitsReady()} and {@link #close()}; may
     *     be null if the frontier is only ever announced through {@link #unitsReady(int)}
     * @param order must be the file order (null means file order)
     * @param workers number of workers that will call {@link #claim(int, int[])}, ids {@code 0 .. workers-1}
     * @param chunk largest number of units per claim
     */
    public Scheduler(Frontier frontier, OrderPolicy order, int workers, int chunk, VerdictBoard board) {
        if (order != null && order != OrderPolicy.FILE_ORDER) {
            throw new UnsupportedOperationException(
                    "rlvm-driver: only the file order is implemented; namespace ordering is deferred until after the vertical slice");
        }
        if (workers < 1 || chunk < 1) {
            throw new IllegalArgumentException("workers and chunk must be positive");
        }
        this.frontier = frontier;
        this.board = board;
        this.workers = workers;
        this.chunk = chunk;
        this.runs = new int[workers][8];
        this.runLen = new int[workers];
        this.holding = new boolean[workers];
    }

    private static final class StoreFrontier implements Frontier {
        private final Store store;

        StoreFrontier(Store store) {
            this.store = store;
        }

        @Override
        public int readyUnits() {
            return store.envUnits();
        }
    }

    // ---- claiming ---------------------------------------------------------------------------------------

    /**
     * Claims the next run of units for worker {@code worker}, blocking while none is available and
     * the run is not finished. Calling it also ends the worker's previous claim.
     *
     * @param out receives the unit indices, ascending
     * @return the number of units claimed; 0 means "no more work, terminate"
     */
    public int claim(int worker, int[] out) {
        synchronized (this) {
            for (;;) {
                int n = tryClaim(worker, out);
                if (n >= 0) {
                    return n;
                }
                waiting++;
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return 0;
                } finally {
                    waiting--;
                }
            }
        }
    }

    /**
     * The skeleton's claim without a worker id: claims as worker 0. Kept for source compatibility;
     * workers of a multi-worker run must use {@link #claim(int, int[])}.
     */
    public int claim(int[] out) {
        return claim(0, out);
    }

    /**
     * {@link #claim(int, int[])} without blocking.
     *
     * @return the number of units claimed, 0 for "terminate", or -1 if the caller would have to wait
     */
    public synchronized int tryClaim(int worker, int[] out) {
        if (holding[worker]) {
            holding[worker] = false;
            busy--;
        }
        int n = take(worker, out);
        if (n > 0) {
            holding[worker] = true;
            busy++;
            claims++;
            return n;
        }
        // Nothing is available now. More can only come from a publication (not after close, and not
        // usefully once the first failure lies below everything unsplit) or from a worker giving back.
        if (busy == 0 && (closed || board.firstFailure() < split)) {
            if (waiting > 0) {
                notifyAll();
            }
            return 0;
        }
        return -1;
    }

    private int take(int w, int[] out) {
        int max = Math.min(chunk, out.length);
        int ff = board.firstFailure();
        for (;;) {
            // Given-back units are served first, but never ahead of a lower unit waiting in the worker's own
            // run: a worker's units stay ascending as long as it has a run, so the lowest undecided unit of
            // the run is always the next one somebody takes (see "Progress" in the module README).
            int ownFront = runLen[w] > 0 ? runs[w][0] : Integer.MAX_VALUE;
            if (spillLen > 0 && spill[spillLen - 1] < ownFront) {
                int n = 0;
                while (spillLen > 0 && n < max && spill[spillLen - 1] < ownFront) {
                    int u = spill[--spillLen];
                    if (u <= ff) {
                        out[n++] = u;
                    }
                }
                if (n > 0) {
                    return n;
                }
                continue;
            }
            if (runLen[w] > 0) {
                int n = takeOwn(w, out, max, ff);
                if (n > 0) {
                    return n;
                }
                continue; // the run lay above the first failure and was dropped
            }
            if (split < ready) {
                distribute(w, ff);
                if (runLen[w] > 0) {
                    continue;
                }
            }
            if (!steal(w, ff)) {
                return 0;
            }
        }
    }

    /** Takes up to {@code max} units from the front of worker w's first run. */
    private int takeOwn(int w, int[] out, int max, int ff) {
        if (runLen[w] == 0) {
            return 0;
        }
        int[] r = runs[w];
        int lo = r[0];
        int hi = r[1];
        if (lo > ff) {
            runLen[w] = 0; // runs ascend: everything this worker still owns lies above the first failure
            return 0;
        }
        int end = Math.min(hi, lo + max);
        boolean cut = false;
        if (end - 1 > ff) {
            end = ff + 1;
            cut = true;
        }
        int n = end - lo;
        for (int i = 0; i < n; i++) {
            out[i] = lo + i;
        }
        if (cut) {
            runLen[w] = 0;
        } else if (end >= hi) {
            removeRun(w, 0);
        } else {
            r[0] = end;
        }
        return n;
    }

    /** Cuts the batch {@code [split, ready)} into contiguous runs, one per worker, starting with worker w. */
    private void distribute(int w, int ff) {
        int from = split;
        int to = ready;
        split = to;
        if (from > ff) {
            return;
        }
        int len = to - from;
        int parts = Math.max(1, Math.min(workers, len / chunk));
        for (int j = 0; j < parts; j++) {
            int lo = from + (int) ((long) len * j / parts);
            int hi = from + (int) ((long) len * (j + 1) / parts);
            if (lo < hi) {
                appendRun((w + j) % workers, lo, hi);
            }
        }
        splits++;
        if (waiting > 0) {
            notifyAll();
        }
    }

    /** Moves the back half of the largest remaining run to worker w. */
    private boolean steal(int w, int ff) {
        int bestWorker = -1;
        int bestRun = -1;
        int bestLen = 0;
        for (int v = 0; v < workers; v++) {
            int[] r = runs[v];
            for (int k = 0; k < runLen[v]; k += 2) {
                int lo = r[k];
                if (lo > ff) {
                    runLen[v] = k; // dead from here on
                    break;
                }
                if (r[k + 1] - 1 > ff) {
                    r[k + 1] = ff + 1;
                }
                int len = r[k + 1] - lo;
                if (len > bestLen) {
                    bestLen = len;
                    bestWorker = v;
                    bestRun = k;
                }
            }
        }
        if (bestWorker < 0) {
            return false;
        }
        int[] r = runs[bestWorker];
        int lo = r[bestRun];
        int hi = r[bestRun + 1];
        int mid = bestLen <= chunk ? lo : lo + bestLen / 2;
        if (mid == lo) {
            removeRun(bestWorker, bestRun);
        } else {
            r[bestRun + 1] = mid;
        }
        appendRun(w, mid, hi);
        steals++;
        return true;
    }

    private void appendRun(int w, int lo, int hi) {
        int n = runLen[w];
        if (n + 2 > runs[w].length) {
            runs[w] = Arrays.copyOf(runs[w], runs[w].length * 2);
        }
        runs[w][n] = lo;
        runs[w][n + 1] = hi;
        runLen[w] = n + 2;
    }

    private void removeRun(int w, int k) {
        int n = runLen[w];
        System.arraycopy(runs[w], k + 2, runs[w], k, n - k - 2);
        runLen[w] = n - 2;
    }

    /**
     * The worker ends (normally, or because its thread is dying): whatever claim it still held is over.
     * Without this a worker that dies inside a claim would keep the others waiting for units it might
     * give back.
     */
    public synchronized void retire(int worker) {
        if (holding[worker]) {
            holding[worker] = false;
            busy--;
        }
        notifyAll();
    }

    /**
     * Returns the unstarted units {@code units[from .. from+n)} of a claimed run (a unit turned out to
     * be slow) so that other workers can take them; they are served before anything else.
     */
    public synchronized void giveBack(int[] units, int from, int n) {
        if (n <= 0) {
            return;
        }
        if (spillLen + n > spill.length) {
            spill = Arrays.copyOf(spill, Math.max(spill.length * 2, spillLen + n));
        }
        // Keep the stack descending: the returned units are ascending and lie below anything pushed earlier
        // only by accident, so sort the whole stack when the order would break.
        boolean ordered = spillLen == 0 || spill[spillLen - 1] > units[from + n - 1];
        for (int i = from + n - 1; i >= from; i--) {
            spill[spillLen++] = units[i];
        }
        if (!ordered) {
            sortDescending(spill, spillLen);
        }
        spilled += n;
        notifyAll();
    }

    private static void sortDescending(int[] a, int n) {
        Arrays.sort(a, 0, n);
        for (int i = 0, j = n - 1; i < j; i++, j--) {
            int t = a[i];
            a[i] = a[j];
            a[j] = t;
        }
    }

    // ---- frontier ---------------------------------------------------------------------------------------

    /** Called by the environment thread (or the loader) when the ready frontier moved: re-reads it. */
    public void unitsReady() {
        unitsReady(frontier == null ? 0 : frontier.readyUnits());
    }

    /** Units {@code [0, units)} are ready. Smaller values than already announced are ignored. */
    public synchronized void unitsReady(int units) {
        if (units > ready) {
            ready = units;
            notifyAll();
        }
    }

    /** No unit will become ready any more (load complete and environment pass finished, or cancelled). */
    public synchronized void close() {
        if (frontier != null) {
            int r = frontier.readyUnits();
            if (r > ready) {
                ready = r;
            }
        }
        closed = true;
        heavyClosed = true;
        notifyAll();
    }

    /** Wakes every parked worker so that it re-reads the first failure (called after a cancellation). */
    public synchronized void wake() {
        notifyAll();
    }

    // ---- heavy lane ---------------------------------------------------------------------------------------

    /**
     * Queues a unit for a heavy-lane thread. {@link Run} does not use this queue: it re-checks a unit
     * on the worker's own deep-stack thread, at once (see the module README); the queue is kept for
     * a later scheduler that moves re-checks to dedicated threads.
     */
    public synchronized void defer(int unit) {
        if (heavyTail == heavy.length) {
            if (heavyHead > 0) {
                System.arraycopy(heavy, heavyHead, heavy, 0, heavyTail - heavyHead);
                heavyTail -= heavyHead;
                heavyHead = 0;
            } else {
                heavy = Arrays.copyOf(heavy, heavy.length * 2);
            }
        }
        heavy[heavyTail++] = unit;
        notifyAll();
    }

    /** Next deferred unit for a heavy-lane thread, or -1 when the lane is empty and no more can come. */
    public synchronized int claimHeavy() {
        for (;;) {
            if (heavyHead < heavyTail) {
                return heavy[heavyHead++];
            }
            if (heavyClosed) {
                return -1;
            }
            try {
                wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return -1;
            }
        }
    }

    /** No unit will be deferred any more. */
    public synchronized void closeHeavy() {
        heavyClosed = true;
        notifyAll();
    }

    /** The complete internal state as text, for the tests that explore interleavings state by state. */
    synchronized String debugState() {
        StringBuilder sb = new StringBuilder(64);
        sb.append(ready).append('/').append(split).append(closed ? 'C' : 'o').append(busy);
        for (int w = 0; w < workers; w++) {
            sb.append(holding[w] ? " H[" : " -[");
            for (int k = 0; k < runLen[w]; k++) {
                sb.append(runs[w][k]).append(k + 1 < runLen[w] ? "," : "");
            }
            sb.append(']');
        }
        sb.append(" s");
        for (int i = 0; i < spillLen; i++) {
            sb.append(spill[i]).append(',');
        }
        return sb.toString();
    }

    // ---- statistics ---------------------------------------------------------------------------------------

    public synchronized long claimCount() {
        return claims;
    }

    public synchronized long stealCount() {
        return steals;
    }

    public synchronized long splitCount() {
        return splits;
    }

    public synchronized long spilledUnits() {
        return spilled;
    }

    public synchronized int readyUnits() {
        return ready;
    }
}
