package rlvm.driver;

/**
 * Per-unit statistics of ONE checking thread: counts, the heaviest units by engine steps and the
 * slowest units by wall time. Confined to its thread while the run is in progress (a worker records
 * a unit without taking any shared lock) and merged into {@link RunStats} when the thread ends.
 * Exists only when statistics are enabled.
 */
final class UnitStats {

    /** Entries kept per list. */
    static final int TOP = 50;
    /** Longs per entry: key (steps or nanos), the other of the two, unit, worker, outcome. */
    static final int W = 5;

    long count;
    long steps;
    long nanos;
    final long[] byOutcome = new long[3];
    /** Min-heap on steps (ties: nanos): {@code steps, nanos, unit, worker, outcome}. */
    final long[] top = new long[TOP * W];
    int topCount;
    /** Min-heap on nanos (ties: steps): {@code nanos, steps, unit, worker, outcome}. */
    final long[] slow = new long[TOP * W];
    int slowCount;
    /**
     * Wall time of units by duration class: {@code hist[2 k]} units and {@code hist[2 k + 1]}
     * nanoseconds for units that took less than {@code 2^k} microseconds (the last class is open).
     */
    final long[] hist = new long[2 * HIST];
    static final int HIST = 24;

    void unit(int unit, int worker, long unitSteps, long unitNanos, int outcome) {
        count++;
        steps += unitSteps;
        nanos += unitNanos;
        if (outcome >= 0 && outcome < byOutcome.length) {
            byOutcome[outcome]++;
        }
        long micros = unitNanos / 1000;
        int k = micros <= 0 ? 0 : Math.min(HIST - 1, 64 - Long.numberOfLeadingZeros(micros));
        hist[2 * k]++;
        hist[2 * k + 1] += unitNanos;
        topCount = offer(top, topCount, unitSteps, unitNanos, unit, worker, outcome);
        slowCount = offer(slow, slowCount, unitNanos, unitSteps, unit, worker, outcome);
    }

    /** Adds everything of {@code o} to this. */
    void merge(UnitStats o) {
        count += o.count;
        steps += o.steps;
        nanos += o.nanos;
        for (int i = 0; i < byOutcome.length; i++) {
            byOutcome[i] += o.byOutcome[i];
        }
        for (int i = 0; i < hist.length; i++) {
            hist[i] += o.hist[i];
        }
        for (int i = 0; i < o.topCount; i++) {
            int b = i * W;
            topCount = offer(top, topCount, o.top[b], o.top[b + 1], o.top[b + 2], o.top[b + 3], o.top[b + 4]);
        }
        for (int i = 0; i < o.slowCount; i++) {
            int b = i * W;
            slowCount = offer(slow, slowCount, o.slow[b], o.slow[b + 1], o.slow[b + 2], o.slow[b + 3], o.slow[b + 4]);
        }
    }

    private static boolean heavier(long k, long t, long k2, long t2) {
        return k > k2 || (k == k2 && t > t2);
    }

    /** Keeps the {@link #TOP} entries with the largest (key, tie); returns the new count. */
    private static int offer(long[] heap, int n, long key, long tie, long unit, long worker, long outcome) {
        if (n < TOP) {
            set(heap, n, key, tie, unit, worker, outcome);
            int i = n;
            while (i > 0) {
                int p = (i - 1) / 2;
                if (!heavier(heap[p * W], heap[p * W + 1], heap[i * W], heap[i * W + 1])) {
                    break;
                }
                swap(heap, i, p);
                i = p;
            }
            return n + 1;
        }
        if (!heavier(key, tie, heap[0], heap[1])) {
            return n;
        }
        set(heap, 0, key, tie, unit, worker, outcome);
        int i = 0;
        for (;;) {
            int l = 2 * i + 1;
            int r = l + 1;
            int m = i;
            if (l < n && heavier(heap[m * W], heap[m * W + 1], heap[l * W], heap[l * W + 1])) {
                m = l;
            }
            if (r < n && heavier(heap[m * W], heap[m * W + 1], heap[r * W], heap[r * W + 1])) {
                m = r;
            }
            if (m == i) {
                return n;
            }
            swap(heap, i, m);
            i = m;
        }
    }

    private static void set(long[] heap, int i, long key, long tie, long unit, long worker, long outcome) {
        int o = i * W;
        heap[o] = key;
        heap[o + 1] = tie;
        heap[o + 2] = unit;
        heap[o + 3] = worker;
        heap[o + 4] = outcome;
    }

    private static void swap(long[] heap, int i, int j) {
        for (int k = 0; k < W; k++) {
            long t = heap[i * W + k];
            heap[i * W + k] = heap[j * W + k];
            heap[j * W + k] = t;
        }
    }

    /** The entries of a heap, largest first: indices into it (entry {@code i} starts at {@code i * W}). */
    static int[] descending(long[] heap, int n) {
        int[] order = new int[n];
        boolean[] used = new boolean[n];
        for (int k = 0; k < n; k++) {
            int best = -1;
            for (int i = 0; i < n; i++) {
                if (!used[i] && (best < 0 || heavier(heap[i * W], heap[i * W + 1], heap[best * W], heap[best * W + 1]))) {
                    best = i;
                }
            }
            used[best] = true;
            order[k] = best;
        }
        return order;
    }
}
