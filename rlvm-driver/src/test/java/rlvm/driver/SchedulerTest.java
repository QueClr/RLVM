package rlvm.driver;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicIntegerArray;

import org.junit.jupiter.api.Test;

import rlvm.api.Outcome;
import rlvm.api.Reason;

/**
 * The scheduler of RECONCILIATION.md section 3: contiguous runs per worker, stealing of the back
 * half of the largest remaining run, the first-failure cut; and, by exhaustive exploration of the
 * interleavings of small cases, the verdict and first failure under every schedule.
 */
class SchedulerTest {

    private static Scheduler scheduler(int workers, int chunk, VerdictBoard board) {
        return new Scheduler((Scheduler.Frontier) null, null, workers, chunk, board);
    }

    private static int[] take(Scheduler s, int worker, int chunk) {
        int[] out = new int[chunk];
        int n = s.tryClaim(worker, out);
        return n <= 0 ? new int[0] : Arrays.copyOf(out, n);
    }

    @Test
    void oneWorkerSeesFileOrderInChunks() {
        VerdictBoard b = new VerdictBoard();
        Scheduler s = scheduler(1, 32, b);
        s.unitsReady(100);
        s.close();
        int next = 0;
        int[] out = new int[64];
        for (;;) {
            int n = s.claim(0, out);
            if (n == 0) {
                break;
            }
            assertTrue(n <= 32);
            for (int i = 0; i < n; i++) {
                assertEquals(next++, out[i]);
            }
        }
        assertEquals(100, next);
        assertEquals(0, s.claim(0, out), "stays terminated");
        assertEquals(0, s.stealCount());
        assertEquals(1, s.splitCount());
    }

    @Test
    void aBatchIsSplitIntoOneContiguousRunPerWorker() {
        VerdictBoard b = new VerdictBoard();
        Scheduler s = scheduler(4, 8, b);
        s.unitsReady(400);
        s.close();
        // every worker takes one chunk: worker w's run starts at w * 100 (the caller of the split gets part 0)
        assertArrayEquals(new int[] {0, 1, 2, 3, 4, 5, 6, 7}, take(s, 0, 8));
        assertArrayEquals(new int[] {100, 101, 102, 103, 104, 105, 106, 107}, take(s, 1, 8));
        assertArrayEquals(new int[] {200, 201, 202, 203, 204, 205, 206, 207}, take(s, 2, 8));
        assertArrayEquals(new int[] {300, 301, 302, 303, 304, 305, 306, 307}, take(s, 3, 8));
        assertArrayEquals(new int[] {8, 9, 10, 11, 12, 13, 14, 15}, take(s, 0, 8));
        assertEquals(0, s.stealCount());
    }

    @Test
    void inSliceModeAWorkerTakesTheNextSliceForItself() {
        VerdictBoard b = new VerdictBoard();
        // slices of at most 40 units: nothing is put into another worker's queue
        Scheduler s = new Scheduler((Scheduler.Frontier) null, null, 4, 8, 40, b);
        s.unitsReady(400);
        s.close();
        assertArrayEquals(new int[] {0, 1, 2, 3, 4, 5, 6, 7}, take(s, 0, 8));        // worker 0: slice [0, 40)
        assertArrayEquals(new int[] {40, 41, 42, 43, 44, 45, 46, 47}, take(s, 2, 8)); // worker 2: slice [40, 80)
        assertArrayEquals(new int[] {8, 9, 10, 11, 12, 13, 14, 15}, take(s, 0, 8));
        assertArrayEquals(new int[] {80, 81, 82, 83, 84, 85, 86, 87}, take(s, 1, 8)); // worker 1: slice [80, 120)
        assertEquals(3, s.splitCount());
        assertEquals(0, s.stealCount());
        // a worker that works off its slice takes the next one: no stealing while units wait to be handed out
        for (int k = 0; k < 4; k++) {
            assertEquals(48 + 8 * k, take(s, 2, 8)[0]);
        }
        assertArrayEquals(new int[] {120, 121, 122, 123, 124, 125, 126, 127}, take(s, 2, 8));
        assertEquals(0, s.stealCount());
        // every unit exactly once, whoever takes it; stealing only when nothing is left to hand out
        int[] seenUnits = new int[400];
        for (int u = 0; u < 16; u++) {
            seenUnits[u]++;
        }
        for (int u = 40; u < 88; u++) {
            seenUnits[u]++;
        }
        for (int u = 120; u < 128; u++) {
            seenUnits[u]++;
        }
        for (int w = 3, idle = 0; idle < 4; w = (w + 1) % 4) {
            int[] got = take(s, w, 8);
            idle = got.length == 0 ? idle + 1 : 0;
            for (int u : got) {
                seenUnits[u]++;
            }
        }
        for (int u = 0; u < 400; u++) {
            assertEquals(1, seenUnits[u], "unit " + u);
        }
        // few ready units are shared out: a slice is the ready units divided by the workers, at least a chunk
        VerdictBoard b3 = new VerdictBoard();
        Scheduler few = new Scheduler((Scheduler.Frontier) null, null, 4, 8, 256, b3);
        few.unitsReady(64);
        assertArrayEquals(new int[] {0, 1, 2, 3, 4, 5, 6, 7}, take(few, 0, 8));
        assertArrayEquals(new int[] {16, 17, 18, 19, 20, 21, 22, 23}, take(few, 1, 8));     // 64 / 4 = 16 for worker 0
        assertArrayEquals(new int[] {28, 29, 30, 31, 32, 33, 34, 35}, take(few, 2, 8));     // 48 / 4 = 12 for worker 1
        assertArrayEquals(new int[] {37, 38, 39, 40, 41, 42, 43, 44}, take(few, 3, 8));     // 36 / 4 = 9 for worker 2
        // a slice bound below the chunk is raised to the chunk; a failure below the next slice ends the run
        VerdictBoard b2 = new VerdictBoard();
        Scheduler t = new Scheduler((Scheduler.Frontier) null, null, 2, 8, 3, b2);
        t.unitsReady(100);
        t.close();
        assertArrayEquals(new int[] {0, 1, 2, 3, 4, 5, 6, 7}, take(t, 0, 8));
        b2.decline(5, rlvm.api.Reason.INTERNAL_ERROR, "x");
        assertEquals(0, take(t, 1, 8).length);
        assertEquals(0, t.tryClaim(0, new int[8]));
        assertEquals(0, t.tryClaim(1, new int[8]));
    }

    @Test
    void anIdleWorkerStealsTheBackHalfOfTheLargestRun() {
        VerdictBoard b = new VerdictBoard();
        Scheduler s = scheduler(2, 10, b);
        s.unitsReady(200);
        s.close();
        // worker 0 owns [0,100), worker 1 owns [100,200); worker 1 works through its run
        assertEquals(0, take(s, 0, 10)[0]);
        for (int i = 0; i < 10; i++) {
            assertEquals(100 + 10 * i, take(s, 1, 10)[0]);
        }
        // worker 1 is out of work: the largest remaining run is worker 0's [10,100); it takes [55,100)
        assertArrayEquals(new int[] {55, 56, 57, 58, 59, 60, 61, 62, 63, 64}, take(s, 1, 10));
        assertEquals(1, s.stealCount());
        // worker 0 keeps the front half
        assertEquals(10, take(s, 0, 10)[0]);
        // drain worker 1's stolen run, then it steals again: worker 0 has [20,55) left -> [37,55)
        for (int i = 1; i < 5; i++) {
            assertEquals(55 + 10 * i, take(s, 1, 10)[0]);
        }
        assertArrayEquals(new int[] {37, 38, 39, 40, 41, 42, 43, 44, 45, 46}, take(s, 1, 10));
        assertEquals(2, s.stealCount());
    }

    @Test
    void aRunNoLongerThanAChunkIsStolenWhole() {
        VerdictBoard b = new VerdictBoard();
        Scheduler s = scheduler(2, 4, b);
        s.unitsReady(12);
        s.close();
        assertArrayEquals(new int[] {0, 1, 2, 3}, take(s, 0, 4)); // worker 0: [0,6), worker 1: [6,12)
        assertArrayEquals(new int[] {6, 7, 8, 9}, take(s, 1, 4));
        assertArrayEquals(new int[] {10, 11}, take(s, 1, 4));
        assertArrayEquals(new int[] {4, 5}, take(s, 1, 4)); // [4,6) is shorter than a chunk: taken whole
        assertEquals(-1, s.tryClaim(0, new int[4]), "nothing left, but worker 1 still holds a claim");
        assertEquals(0, s.tryClaim(1, new int[4]));
        assertEquals(0, s.tryClaim(0, new int[4]));
    }

    @Test
    void aSmallBatchIsNotSpreadOverAllWorkers() {
        VerdictBoard b = new VerdictBoard();
        Scheduler s = scheduler(6, 32, b);
        s.unitsReady(70); // two chunks' worth: two runs, the other four workers have nothing of their own
        int[] first = take(s, 3, 32);
        assertEquals(0, first[0]);
        assertEquals(32, first.length);
        int[] second = take(s, 4, 32);
        assertEquals(35, second[0]);
        // worker 5 has no run: it steals the back half of the largest remaining run ([35+32,70) = 3 units: whole)
        int[] third = take(s, 5, 32);
        assertEquals(3, third.length);
        assertEquals(32, third[0]);
    }

    @Test
    void laterBatchesAreAppendedInAscendingOrder() {
        VerdictBoard b = new VerdictBoard();
        Scheduler s = scheduler(2, 4, b);
        s.unitsReady(16);
        assertArrayEquals(new int[] {0, 1, 2, 3}, take(s, 0, 4)); // w0 [0,8), w1 [8,16)
        s.unitsReady(32);
        assertArrayEquals(new int[] {8, 9, 10, 11}, take(s, 1, 4));
        assertArrayEquals(new int[] {4, 5, 6, 7}, take(s, 0, 4));
        // worker 0's run is empty: the waiting batch [16,32) is split, worker 0 gets its front
        assertArrayEquals(new int[] {16, 17, 18, 19}, take(s, 0, 4));
        assertArrayEquals(new int[] {12, 13, 14, 15}, take(s, 1, 4));
        assertArrayEquals(new int[] {24, 25, 26, 27}, take(s, 1, 4)); // its part of the second batch
        assertEquals(2, s.splitCount());
        assertEquals(0, s.stealCount());
        assertEquals(32, s.readyUnits());
        s.unitsReady(20); // a smaller frontier is ignored
        assertEquals(32, s.readyUnits());
    }

    @Test
    void unitsAboveTheFirstFailureAreNeverHandedOut() {
        VerdictBoard b = new VerdictBoard();
        Scheduler s = scheduler(2, 8, b);
        s.unitsReady(100);
        s.close();
        assertEquals(0, take(s, 0, 8)[0]);
        assertEquals(50, take(s, 1, 8)[0]);
        b.decline(12, Reason.BUDGET_MEMORY, "x");
        assertArrayEquals(new int[] {8, 9, 10, 11, 12}, take(s, 0, 8), "cut at the first failure");
        assertEquals(-1, s.tryClaim(1, new int[8]), "worker 1's run lies above the failure; worker 0 still holds a claim");
        assertEquals(0, s.tryClaim(0, new int[8]));
        assertEquals(0, s.tryClaim(1, new int[8]));
    }

    @Test
    void aFailureBelowEverythingEndsTheRunWithoutClose() {
        VerdictBoard b = new VerdictBoard();
        Scheduler s = scheduler(2, 8, b);
        s.unitsReady(40);
        assertEquals(0, take(s, 0, 8)[0]);
        assertEquals(-1, new Object() {
            int blocked() {
                // drain everything with worker 1, then it would block: not closed, no failure
                int[] out = new int[8];
                int n;
                while ((n = s.tryClaim(1, out)) > 0) {
                    // keep claiming
                }
                return n;
            }
        }.blocked());
        b.internalFailure("cancel everything");
        assertEquals(0, s.tryClaim(0, new int[8]));
        assertEquals(0, s.tryClaim(1, new int[8]));
    }

    @Test
    void givenBackUnitsAreServedBeforeHigherUnitsOfTheOwnRun() {
        VerdictBoard b = new VerdictBoard();
        Scheduler s = scheduler(2, 4, b);
        s.unitsReady(40);
        s.close();
        int[] c0 = take(s, 0, 4); // 0..3, worker 0 keeps [4,20)
        int[] c1 = take(s, 1, 4); // 20..23, worker 1 keeps [24,40)
        s.giveBack(c1, 1, 3); // 21, 22, 23
        s.giveBack(c0, 2, 2); // 2, 3 -> below what is already there
        assertEquals(5, s.spilledUnits());
        // worker 0: the given-back units below the front of its own run (4), not the ones above it
        assertArrayEquals(new int[] {2, 3}, take(s, 0, 4));
        assertArrayEquals(new int[] {4, 5, 6, 7}, take(s, 0, 4));
        // worker 1: its own run starts at 24, so 21..23 come first
        assertArrayEquals(new int[] {21, 22, 23}, take(s, 1, 4));
        assertArrayEquals(new int[] {24, 25, 26, 27}, take(s, 1, 4));
        // a given-back unit above the first failure is dropped
        int[] c = take(s, 1, 4); // 28..31
        s.giveBack(c, 1, 3);
        b.reject(28, Reason.DECL_TYPE_MISMATCH, "x");
        for (int w = 0; w < 2; w++) {
            int[] out = new int[4];
            int n;
            while ((n = s.tryClaim(w, out)) > 0) {
                for (int i = 0; i < n; i++) {
                    assertTrue(out[i] <= 28, "unit " + out[i] + " handed out above the first failure");
                }
            }
        }
    }

    @Test
    void aWorkerWithoutARunTakesGivenBackUnitsBeforeStealing() {
        VerdictBoard b = new VerdictBoard();
        Scheduler s = scheduler(2, 4, b);
        s.unitsReady(16);
        s.close();
        int[] c0 = take(s, 0, 4); // 0..3, worker 0 keeps [4,8)
        take(s, 1, 4); // 8..11
        take(s, 1, 4); // 12..15: worker 1 has no run left
        s.giveBack(c0, 1, 3);
        assertArrayEquals(new int[] {1, 2, 3}, take(s, 1, 4));
        assertArrayEquals(new int[] {4, 5, 6, 7}, take(s, 1, 4), "then it steals worker 0's short run whole");
        assertEquals(1, s.stealCount());
    }

    @Test
    void blockingClaimWakesOnPublicationAndOnClose() {
        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
            VerdictBoard b = new VerdictBoard();
            Scheduler s = scheduler(2, 4, b);
            int[][] got = new int[2][];
            Thread[] t = new Thread[2];
            for (int w = 0; w < 2; w++) {
                final int id = w;
                t[w] = new Thread(() -> {
                    int[] out = new int[4];
                    int total = 0;
                    int n;
                    while ((n = s.claim(id, out)) > 0) {
                        total += n;
                    }
                    got[id] = new int[] {total};
                });
                t[w].start();
            }
            Thread.sleep(20);
            s.unitsReady(8);
            Thread.sleep(20);
            s.unitsReady(21);
            Thread.sleep(20);
            s.close();
            t[0].join();
            t[1].join();
            assertEquals(21, got[0][0] + got[1][0]);
        });
    }

    @Test
    void heavyLaneIsAQueue() {
        VerdictBoard b = new VerdictBoard();
        Scheduler s = scheduler(1, 4, b);
        for (int i = 0; i < 40; i++) {
            s.defer(i * 3);
        }
        for (int i = 0; i < 40; i++) {
            assertEquals(i * 3, s.claimHeavy());
        }
        s.defer(7);
        s.closeHeavy();
        assertEquals(7, s.claimHeavy());
        assertEquals(-1, s.claimHeavy());
    }

    @Test
    void onlyTheFileOrderIsImplemented() {
        assertEquals(OrderPolicy.FILE_ORDER, OrderPolicy.of(OrderPolicy.Kind.FILE, null));
        assertThrows(UnsupportedOperationException.class, () -> OrderPolicy.of(OrderPolicy.Kind.NAMESPACE_WINDOW, null));
        OrderPolicy reversed = (units, from, n) -> {
        };
        assertThrows(UnsupportedOperationException.class,
                () -> new Scheduler((Scheduler.Frontier) null, reversed, 2, 4, new VerdictBoard()));
    }

    @Test
    void everyUnitIsHandedOutExactlyOnceUnderContention() {
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            for (int round = 0; round < 20; round++) {
                final int units = 20_000 + round * 137;
                final int workers = 2 + round % 7;
                VerdictBoard b = new VerdictBoard();
                // every other round in slice mode
                Scheduler s = round % 2 == 0 ? scheduler(workers, 1 + round % 33, b)
                        : new Scheduler((Scheduler.Frontier) null, null, workers, 1 + round % 33, 64 + round * 50, b);
                AtomicIntegerArray handed = new AtomicIntegerArray(units);
                Thread[] t = new Thread[workers];
                for (int w = 0; w < workers; w++) {
                    final int id = w;
                    t[w] = new Thread(() -> {
                        int[] out = new int[40];
                        int n;
                        int last = -1;
                        while ((n = s.claim(id, out)) > 0) {
                            for (int i = 0; i < n; i++) {
                                handed.incrementAndGet(out[i]);
                                if (i > 0 && out[i] <= out[i - 1]) {
                                    handed.addAndGet(out[i], 1000); // not ascending inside a claim
                                }
                            }
                            if ((out[0] & 63) == 0 && n > 1) {
                                s.giveBack(out, 1, n - 1);
                                for (int i = 1; i < n; i++) {
                                    handed.decrementAndGet(out[i]);
                                }
                            }
                            last = out[0];
                        }
                        assertTrue(last >= -1);
                    });
                    t[w].start();
                }
                // publish in uneven batches while the workers run
                int published = 0;
                while (published < units) {
                    published = Math.min(units, published + 1 + (published * 31 + round) % 3000);
                    s.unitsReady(published);
                    Thread.yield();
                }
                s.close();
                for (Thread th : t) {
                    th.join();
                }
                for (int u = 0; u < units; u++) {
                    if (handed.get(u) != 1) {
                        fail("round " + round + ": unit " + u + " handed out " + handed.get(u) + " times");
                    }
                }
            }
        });
    }

    // ---- exhaustive exploration of interleavings ------------------------------------------------------------

    /** A deterministic model of a run: workers that claim and process one unit per step, and a publisher. */
    private static final class Sim {
        static final int IDLE = 0;
        static final int HOLD = 1;
        static final int DONE = 2;

        final int[] script; // final status per unit: an Outcome code
        final int workers;
        final int[] publications;
        final boolean giveBack;
        final VerdictBoard board = new VerdictBoard();
        final Scheduler scheduler;
        final int[][] claim;
        final int[] n;
        final int[] idx;
        final int[] state;
        final boolean[] held;
        /** The worker's last claim attempt blocked and nothing has changed since. */
        final boolean[] blocked;
        final int[] handed;
        int published;

        Sim(int[] script, int workers, int chunk, int[] publications, boolean giveBack) {
            this.script = script;
            this.workers = workers;
            this.publications = publications;
            this.giveBack = giveBack;
            this.scheduler = new Scheduler((Scheduler.Frontier) null, null, workers, chunk, simSplit, board);
            this.claim = new int[workers][chunk];
            this.n = new int[workers];
            this.idx = new int[workers];
            this.state = new int[workers];
            this.held = new boolean[workers];
            this.blocked = new boolean[workers];
            this.handed = new int[script.length];
        }

        /**
         * Actors: 0..W-1 = a worker's step; W..2W-1 = the worker's step followed by giving back its rest;
         * 2W = publisher; 2W+1..3W = the worker gives back the rest of its claim while the unit it is
         * busy with has not ended (the in-unit give-back of a slow unit).
         */
        int actors() {
            return 3 * workers + 1;
        }

        boolean enabled(int actor) {
            if (actor == 2 * workers) {
                return published <= publications.length; // one step per publication, then close
            }
            if (actor > 2 * workers) {
                int w = actor - 2 * workers - 1;
                return giveBack && state[w] == HOLD && n[w] - idx[w] >= 2;
            }
            if (actor >= workers) {
                int w = actor - workers;
                return giveBack && state[w] == HOLD && n[w] - idx[w] >= 2;
            }
            if (state[actor] == DONE) {
                return false;
            }
            return state[actor] == HOLD || !blocked[actor];
        }

        private void changed() {
            Arrays.fill(blocked, false);
        }

        void step(int actor) {
            if (actor == 2 * workers) {
                if (published < publications.length) {
                    scheduler.unitsReady(publications[published]);
                } else {
                    scheduler.close();
                }
                published++;
                changed();
                return;
            }
            if (actor > 2 * workers) {
                int w = actor - 2 * workers - 1;
                int from = idx[w] + 1;
                scheduler.giveBack(claim[w], from, n[w] - from);
                for (int i = from; i < n[w]; i++) {
                    handed[claim[w][i]]--;
                }
                n[w] = from;
                changed();
                return;
            }
            boolean give = actor >= workers;
            int w = give ? actor - workers : actor;
            if (state[w] == IDLE) {
                boolean wasHeld = held[w];
                int r = scheduler.tryClaim(w, claim[w]);
                held[w] = r > 0;
                if (r > 0) {
                    for (int i = 0; i < r; i++) {
                        handed[claim[w][i]]++;
                    }
                    n[w] = r;
                    idx[w] = 0;
                    state[w] = HOLD;
                    changed();
                } else if (r == 0) {
                    state[w] = DONE;
                    changed();
                } else {
                    if (wasHeld) {
                        changed(); // releasing the previous claim may let others terminate
                    }
                    blocked[w] = true;
                }
                return;
            }
            int u = claim[w][idx[w]++];
            if (u <= board.firstFailure()) {
                switch (script[u]) {
                    case Outcome.ACCEPT:
                        board.accept(u);
                        break;
                    case Outcome.REJECT:
                        board.reject(u, Reason.DECL_TYPE_MISMATCH, "r");
                        break;
                    default:
                        board.decline(u, Reason.BUDGET_MEMORY, "d");
                        break;
                }
            }
            if (give) {
                int rest = n[w] - idx[w];
                scheduler.giveBack(claim[w], idx[w], rest);
                for (int i = idx[w]; i < n[w]; i++) {
                    handed[claim[w][i]]--;
                }
                n[w] = idx[w];
            }
            if (idx[w] == n[w]) {
                state[w] = IDLE;
            }
            changed();
        }

        boolean allDone() {
            for (int s : state) {
                if (s != DONE) {
                    return false;
                }
            }
            return true;
        }

        /** Everything that decides the future of the run. */
        String key() {
            StringBuilder sb = new StringBuilder(96);
            sb.append(scheduler.debugState()).append('|').append(published).append('|').append(board.firstFailure());
            for (int w = 0; w < workers; w++) {
                sb.append('|').append(state[w]).append(held[w] ? 'h' : '-').append(blocked[w] ? 'b' : '-');
                for (int i = idx[w]; i < n[w] && state[w] == HOLD; i++) {
                    sb.append(claim[w][i]).append(',');
                }
            }
            sb.append('|');
            for (int u = 0; u < script.length; u++) {
                sb.append(board.status(u)).append(handed[u]);
            }
            return sb.toString();
        }
    }

    private long terminals;
    private long states;
    private java.util.HashSet<String> seen;
    /** The slice bound of the simulated schedulers (0 = one run per worker and batch). */
    private static int simSplit;

    /**
     * Depth-first search over the schedules, pruned at states already visited: every reachable state
     * is expanded once, so every reachable final state is checked.
     */
    private void explore(int[] script, int workers, int chunk, int[] publications, boolean giveBack, int[] prefix,
            int len, int expectVerdict, int expectUnit) {
        Sim sim = new Sim(script, workers, chunk, publications, giveBack);
        for (int i = 0; i < len; i++) {
            sim.step(prefix[i]);
        }
        if (!seen.add(sim.key())) {
            return;
        }
        states++;
        if (sim.allDone()) {
            terminals++;
            // the publisher's remaining steps cannot give anybody work
            while (sim.enabled(2 * workers)) {
                sim.step(2 * workers);
            }
            for (int w = 0; w < workers; w++) {
                assertEquals(0, sim.scheduler.tryClaim(w, new int[chunk]));
            }
            sim.board.complete(script.length);
            String where = "script " + Arrays.toString(script) + " schedule " + Arrays.toString(Arrays.copyOf(prefix, len));
            assertEquals(expectVerdict, sim.board.exitCode(), where);
            assertEquals(expectUnit, sim.board.decidingUnit(), where);
            int through = expectUnit < 0 ? script.length - 1 : expectUnit;
            for (int u = 0; u < script.length; u++) {
                assertTrue(sim.handed[u] <= 1, "unit " + u + " handed out twice: " + where);
                if (u <= through) {
                    assertEquals(1, sim.handed[u], "unit " + u + " never handed out: " + where);
                    assertTrue(sim.board.status(u) != VerdictBoard.NONE, "unit " + u + " undecided: " + where);
                }
            }
            return;
        }
        boolean any = false;
        for (int a = 0; a < sim.actors(); a++) {
            if (sim.enabled(a)) {
                any = true;
                prefix[len] = a;
                explore(script, workers, chunk, publications, giveBack, prefix, len + 1, expectVerdict, expectUnit);
            }
        }
        if (!any) {
            fail("deadlock: script " + Arrays.toString(script) + " schedule " + Arrays.toString(Arrays.copyOf(prefix, len)));
        }
    }

    /** All assignments of accept / reject / decline to the units, each under every schedule. */
    private long exploreAllScripts(int units, int workers, int chunk, int[] publications, boolean giveBack) {
        terminals = 0;
        states = 0;
        int combos = 1;
        for (int i = 0; i < units; i++) {
            combos *= 3;
        }
        int[] script = new int[units];
        for (int c = 0; c < combos; c++) {
            int x = c;
            int expectVerdict = Outcome.ACCEPT;
            int expectUnit = -1;
            for (int i = 0; i < units; i++) {
                script[i] = x % 3;
                x /= 3;
            }
            for (int i = 0; i < units; i++) {
                if (script[i] != Outcome.ACCEPT) {
                    expectVerdict = script[i];
                    expectUnit = i;
                    break;
                }
            }
            seen = new java.util.HashSet<>();
            explore(script, workers, chunk, publications, giveBack, new int[512], 0, expectVerdict, expectUnit);
        }
        System.out.println("SchedulerTest: " + units + " units, " + workers + " workers, chunk " + chunk + ", publications "
                + Arrays.toString(publications) + (giveBack ? ", give-back" : "") + ": " + combos + " scripts, " + states
                + " states, " + terminals + " final states");
        return states;
    }

    @Test
    void verdictAndFirstFailureUnderAllInterleavingsTwoWorkers() {
        long p = exploreAllScripts(5, 2, 2, new int[] {5}, false);
        assertTrue(p > 1000, "explored " + p);
    }

    @Test
    void verdictAndFirstFailureUnderAllInterleavingsPipelined() {
        long p = exploreAllScripts(5, 2, 1, new int[] {2, 3, 5}, false);
        assertTrue(p > 1000, "explored " + p);
    }

    @Test
    void verdictAndFirstFailureUnderAllInterleavingsThreeWorkers() {
        long p = exploreAllScripts(4, 3, 1, new int[] {2, 4}, false);
        assertTrue(p > 1000, "explored " + p);
    }

    /** Larger state spaces; minutes. Run with {@code -Drlvm.driver.explore=big}. */
    @Test
    void verdictAndFirstFailureUnderAllInterleavingsBig() {
        org.junit.jupiter.api.Assumptions.assumeTrue("big".equals(System.getProperty("rlvm.driver.explore")),
                "run with -Drlvm.driver.explore=big");
        assertTrue(exploreAllScripts(6, 2, 2, new int[] {3, 6}, true) > 100_000);
        assertTrue(exploreAllScripts(5, 3, 1, new int[] {2, 5}, false) > 100_000);
        assertTrue(exploreAllScripts(5, 3, 2, new int[] {5}, true) > 100_000);
    }

    @Test
    void verdictAndFirstFailureUnderAllInterleavingsInSliceMode() {
        // slice mode with slices of at most 2 units: a worker takes its own slice, the others theirs
        simSplit = 2;
        try {
            assertTrue(exploreAllScripts(5, 2, 1, new int[] {5}, false) > 1000);
            assertTrue(exploreAllScripts(5, 2, 1, new int[] {2, 3, 5}, false) > 1000);
            assertTrue(exploreAllScripts(4, 3, 1, new int[] {4}, false) > 1000);
            assertTrue(exploreAllScripts(5, 2, 2, new int[] {5}, true) > 1000);
        } finally {
            simSplit = 0;
        }
    }

    @Test
    void verdictAndFirstFailureUnderAllInterleavingsWithGiveBack() {
        long p = exploreAllScripts(5, 2, 3, new int[] {5}, true);
        assertTrue(p > 1000, "explored " + p);
    }
}
