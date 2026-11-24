package rlvm.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

import rlvm.api.Outcome;
import rlvm.api.Reason;

/**
 * The status chain of ARCHITECTURE.md 8.1 over its whole input space for small unit counts, against
 * an independent statement of the verdict rule of RECONCILIATION.md section 2.
 */
class VerdictBoardTest {

    private static final int NONE = VerdictBoard.NONE;
    private static final int ACC = VerdictBoard.ACCEPTED;
    private static final int SKIP = VerdictBoard.SKIPPED;
    private static final int REJ = VerdictBoard.REJECTED;
    private static final int DEC = VerdictBoard.DECLINED;

    private static final int IN_NONE = 0;
    private static final int IN_MALFORMED = 1;
    private static final int IN_UNSUPPORTED_AT = 2;
    private static final int IN_UNSUPPORTED = 3;

    /** The rule, written down once more. {@code complete < 0}: complete() was not called. */
    private static int oracle(int[] st, int input, int inputPos, int complete, boolean internal, boolean stopped) {
        if (internal) {
            return Outcome.EXIT_DECLINE;
        }
        if (input == IN_MALFORMED) {
            return Outcome.EXIT_REJECT;
        }
        int highest = -1;
        for (int u = 0; u < st.length; u++) {
            if (st[u] != NONE) {
                highest = u;
            }
        }
        // The units that exist as far as anyone told the board.
        int end;
        if (input == IN_UNSUPPORTED_AT) {
            end = complete >= 0 ? Math.min(inputPos, complete) : inputPos;
        } else if (input == IN_UNSUPPORTED) {
            end = complete >= 0 ? Math.min(highest + 1, complete) : highest + 1;
        } else {
            end = complete >= 0 ? complete : highest + 1;
        }
        for (int u = 0; u < end; u++) {
            int s = u < st.length ? st[u] : NONE;
            if (s != ACC && s != SKIP) {
                return s == REJ ? Outcome.EXIT_REJECT : Outcome.EXIT_DECLINE;
            }
        }
        if (input != IN_NONE || complete < 0 || stopped || highest >= complete) {
            return Outcome.EXIT_DECLINE;
        }
        return Outcome.EXIT_ACCEPT;
    }

    private static void apply(VerdictBoard b, int unit, int status) {
        switch (status) {
            case ACC:
                b.accept(unit);
                break;
            case SKIP:
                b.skip(unit);
                break;
            case REJ:
                b.reject(unit, Reason.DECL_TYPE_MISMATCH, "r" + unit);
                break;
            case DEC:
                b.decline(unit, Reason.BUDGET_MEMORY, "d" + unit);
                break;
            default:
                break;
        }
    }

    @Test
    void statusChainByEnumeration() {
        long cases = 0;
        long accepts = 0;
        Random rnd = new Random(7);
        for (int n = 0; n <= 4; n++) {
            int combos = 1;
            for (int i = 0; i < n; i++) {
                combos *= 5;
            }
            int[] st = new int[n];
            int[] order = new int[n];
            for (int c = 0; c < combos; c++) {
                int x = c;
                for (int i = 0; i < n; i++) {
                    st[i] = x % 5;
                    x /= 5;
                }
                int[] completes = {-1, n - 1, n, n + 1};
                for (int complete : completes) {
                    if (complete < -1) {
                        continue;
                    }
                    for (int input = IN_NONE; input <= IN_UNSUPPORTED; input++) {
                        int positions = input == IN_UNSUPPORTED_AT ? n + 1 : 1;
                        for (int pos = 0; pos < positions; pos++) {
                            for (int flags = 0; flags < 4; flags++) {
                                boolean internal = (flags & 1) != 0;
                                boolean stopped = (flags & 2) != 0;
                                // report in a random order: the verdict must not depend on it
                                for (int i = 0; i < n; i++) {
                                    order[i] = i;
                                }
                                for (int i = n - 1; i > 0; i--) {
                                    int j = rnd.nextInt(i + 1);
                                    int t = order[i];
                                    order[i] = order[j];
                                    order[j] = t;
                                }
                                VerdictBoard b = new VerdictBoard();
                                assertEquals(Outcome.EXIT_DECLINE, b.exitCode(), "a fresh board declines");
                                boolean completeFirst = rnd.nextBoolean();
                                if (completeFirst && complete >= 0) {
                                    b.complete(complete);
                                }
                                if (input == IN_MALFORMED) {
                                    b.inputFailure(Reason.MALFORMED_EXPORT, "bad line");
                                } else if (input == IN_UNSUPPORTED_AT) {
                                    b.inputFailure(pos, Reason.UNSUPPORTED_INPUT, "too big");
                                }
                                for (int i = 0; i < n; i++) {
                                    apply(b, order[i], st[order[i]]);
                                    if (complete < 0) {
                                        assertFalse(b.exitCode() == Outcome.EXIT_ACCEPT, "accept before complete()");
                                    }
                                }
                                if (input == IN_UNSUPPORTED) {
                                    b.inputFailure(Reason.UNSUPPORTED_INPUT, "too big");
                                }
                                if (internal) {
                                    b.internalFailure("boom");
                                }
                                if (stopped) {
                                    b.stopped("stop-after");
                                }
                                if (!completeFirst && complete >= 0) {
                                    b.complete(complete);
                                }
                                int expected = oracle(st, input, pos, complete, internal, stopped);
                                int actual = b.exitCode();
                                if (expected != actual) {
                                    assertEquals(expected, actual, "n=" + n + " st=" + java.util.Arrays.toString(st)
                                            + " input=" + input + "@" + pos + " complete=" + complete + " internal="
                                            + internal + " stopped=" + stopped);
                                }
                                // The fail-closed chain, stated as properties.
                                if (actual == Outcome.EXIT_ACCEPT) {
                                    accepts++;
                                    // complete(k) was called, exactly units 0..k-1 have a status, all accepted or skipped
                                    assertTrue(complete >= 0 && complete <= n && input == IN_NONE && !internal && !stopped);
                                    for (int i = 0; i < n; i++) {
                                        assertTrue(i < complete ? st[i] == ACC || st[i] == SKIP : st[i] == NONE);
                                    }
                                    assertNull(b.explanation());
                                    assertEquals(-1, b.decidingUnit());
                                    assertEquals(complete, b.acceptedCount());
                                } else {
                                    assertNotNull(b.explanation());
                                }
                                if (actual == Outcome.EXIT_REJECT && input != IN_MALFORMED) {
                                    int u = b.decidingUnit();
                                    assertTrue(u >= 0 && st[u] == REJ, "REJECT without a rejected deciding unit");
                                    for (int i = 0; i < u; i++) {
                                        assertTrue(st[i] == ACC || st[i] == SKIP);
                                    }
                                }
                                // firstFailure: the smallest failed unit, or -1 once everything is cancelled
                                int ff = Integer.MAX_VALUE;
                                for (int i = n - 1; i >= 0; i--) {
                                    if (st[i] == REJ || st[i] == DEC) {
                                        ff = i;
                                    }
                                }
                                if (internal || input == IN_MALFORMED) {
                                    ff = -1;
                                }
                                assertEquals(ff, b.firstFailure());
                                for (int i = 0; i < n; i++) {
                                    assertEquals(st[i], b.status(i));
                                }
                                assertFalse(b.hasConflict());
                                cases++;
                            }
                        }
                    }
                }
            }
        }
        assertTrue(cases > 50_000, "enumerated " + cases);
        // exactly the boards with complete(k) for k = n (2^n boards: all units accepted or skipped) or
        // k = n - 1 (2^(n-1) boards: the last enumerated unit has no status, i.e. does not exist)
        assertEquals((1 + 2 + 4 + 8 + 16) + (1 + 2 + 4 + 8), accepts);
    }

    @Test
    void aSecondReportForAUnitDeclines() {
        int[] statuses = {ACC, SKIP, REJ, DEC};
        for (int first : statuses) {
            for (int second : statuses) {
                VerdictBoard b = new VerdictBoard();
                b.accept(0);
                apply(b, 1, first);
                apply(b, 1, second);
                b.accept(2);
                b.complete(3);
                assertTrue(b.hasConflict());
                assertEquals(first, b.status(1), "the first status stays");
                assertEquals(Outcome.EXIT_DECLINE, b.exitCode(), first + " then " + second);
                assertTrue(b.explanation().contains("reported twice"), b.explanation());
            }
        }
    }

    @Test
    void laterUnitsNeverChangeTheVerdict() {
        // RECONCILIATION C3.13: decline at 5, reject at 9 -> DECLINE whatever was recorded behind unit 5.
        for (int mask = 0; mask < 16; mask++) {
            VerdictBoard b = new VerdictBoard();
            for (int u = 0; u < 5; u++) {
                b.accept(u);
            }
            b.decline(5, Reason.BUDGET_MEMORY, "m");
            if ((mask & 1) != 0) {
                b.accept(6);
            }
            if ((mask & 2) != 0) {
                b.reject(9, Reason.DECL_TYPE_MISMATCH, "x");
            }
            if ((mask & 4) != 0) {
                b.decline(7, Reason.STACK_DEPTH, "y");
            }
            if ((mask & 8) != 0) {
                b.complete(12);
            }
            assertEquals(Outcome.EXIT_DECLINE, b.exitCode());
            assertEquals(5, b.decidingUnit());
            assertEquals(5, b.firstFailure());
        }
        VerdictBoard b = new VerdictBoard();
        b.reject(9, Reason.DECL_TYPE_MISMATCH, "late");
        for (int u = 0; u < 9; u++) {
            b.accept(u);
        }
        b.complete(20);
        assertEquals(Outcome.EXIT_REJECT, b.exitCode());
        assertEquals(9, b.decidingUnit());
    }

    @Test
    void explanationNamesTheFirstFailure() {
        VerdictBoard b = new VerdictBoard();
        b.setNamer(new VerdictBoard.UnitNamer() {
            @Override
            public String name(int unit) {
                return "Foo.bar" + unit;
            }
        });
        b.accept(0);
        b.reject(3, Reason.APP_TYPE_MISMATCH, "late\nfailure");
        b.reject(1, Reason.DECL_TYPE_MISMATCH, "value does not have the declared type");
        b.complete(4);
        String line = b.explanation();
        assertTrue(line.startsWith("reject: unit 1 'Foo.bar1' rejected (DECL_TYPE_MISMATCH): value does not"), line);
        assertFalse(line.contains("\n"));
        assertEquals(2, b.rejectedCount());

        VerdictBoard c = new VerdictBoard();
        c.accept(0);
        c.complete(2);
        assertEquals(Outcome.EXIT_DECLINE, c.exitCode());
        assertTrue(c.explanation().contains("unit 1 was not decided"), c.explanation());

        VerdictBoard d = new VerdictBoard();
        d.inputFailure(Reason.MALFORMED_EXPORT, "invalid JSON (line 3)");
        assertEquals(Outcome.EXIT_REJECT, d.exitCode());
        assertTrue(d.explanation().contains("invalid JSON"), d.explanation());

        VerdictBoard e = new VerdictBoard();
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            big.append('x');
        }
        e.decline(0, Reason.INTERNAL_ERROR, big.toString());
        assertTrue(e.explanation().length() <= 1024);
    }

    @Test
    void manyUnitsAcrossSegments() {
        VerdictBoard b = new VerdictBoard();
        int n = 20_000;
        for (int u = n - 1; u >= 0; u--) {
            b.accept(u);
        }
        assertEquals(Outcome.EXIT_DECLINE, b.exitCode());
        b.complete(n);
        assertEquals(Outcome.EXIT_ACCEPT, b.exitCode());
        assertEquals(n, b.acceptedCount());
        b.accept(n + 5000); // a status beyond the end of the input: counts inconsistent
        assertEquals(Outcome.EXIT_DECLINE, b.exitCode());
        assertTrue(b.explanation().contains("inconsistent"), b.explanation());
    }

    @Test
    void eventsAreCountedAndBounded() {
        VerdictBoard b = new VerdictBoard();
        for (int i = 0; i < 40; i++) {
            b.warmAnomaly(i, "a");
            b.machineIncomplete(i, "m");
        }
        b.disagreement(3, "d");
        assertEquals(40, b.warmAnomalies());
        assertEquals(40, b.machineIncomplete());
        assertEquals(1, b.disagreements());
        assertEquals(16, b.events().length);
        assertTrue(b.events()[0].startsWith("WARM-ANOMALY unit 0"));
    }
}
