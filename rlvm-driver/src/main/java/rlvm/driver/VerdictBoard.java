package rlvm.driver;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;

import rlvm.api.Outcome;
import rlvm.api.Reason;

/**
 * Per-unit outcomes and the run's verdict. Thread-safe; the only shared mutable state of the checking
 * phase besides the scheduler's queues and the memory account.
 *
 * <p>Verdict rule (RECONCILIATION.md section 2, "Verdict rule"; ARCHITECTURE.md 8.1), independent of
 * the number of workers and of timing:
 * <pre>
 * DECLINE  if an internal failure was recorded (a throwable outside a unit boundary), or two
 *          different statuses were reported for one unit
 * REJECT   else if the loader reported malformed input (the official reader rejects before checking)
 * else     let u = the first unit in file order whose status is not ACCEPTED or SKIPPED
 *          (below the position of a non-malformed input failure, if there is one):
 *            status(u) = REJECTED            -> REJECT   (a rejection is only ever recorded confirmed)
 *            status(u) = DECLINED or none    -> DECLINE
 *          no such u:
 *            input failure                   -> DECLINE
 *            complete(n) not called, or stopped(..) -> DECLINE
 *            a status exists at or above n   -> DECLINE  (counts inconsistent)
 *            otherwise                       -> ACCEPT
 * </pre>
 * Statuses of units behind the first non-accepted one never change the verdict, so it does not matter
 * which of them were still checked when the run was cancelled. A unit status is written once, by
 * compare-and-set from "none"; a second report for the same unit is an internal inconsistency and
 * turns the verdict into DECLINE.
 */
public final class VerdictBoard {

    /** No status reported (yet). */
    public static final int NONE = 0;
    public static final int ACCEPTED = 1;
    /** Unsafe or partial record: not admitted, not checked; counts as accepted. */
    public static final int SKIPPED = 2;
    /** Rejected, and the rejection is confirmed (reference kernel, structural check, environment pass). */
    public static final int REJECTED = 3;
    public static final int DECLINED = 4;

    /** Receives a unit index, returns a name for the explanation line. */
    public interface UnitNamer {
        String name(int unit);
    }

    private static final int SEG_BITS = 12;
    private static final int SEG_SIZE = 1 << SEG_BITS;
    private static final int SEG_MASK = SEG_SIZE - 1;
    private static final int MAX_EVENTS = 16;

    /** Copy-on-write directory of status segments; a slot never changes once it is non-null. */
    private volatile AtomicIntegerArray[] segments = new AtomicIntegerArray[0];

    private final AtomicInteger firstFailure = new AtomicInteger(Integer.MAX_VALUE);
    private final AtomicInteger rejected = new AtomicInteger();
    private final AtomicInteger declined = new AtomicInteger();

    private volatile int completeUnits = -1;
    private volatile boolean conflict;
    private volatile boolean internalFailure;
    private volatile boolean stopped;

    // ---- guarded by `this` (failure paths only) ------------------------------------------------------
    private int reportUnit = Integer.MAX_VALUE;
    private Reason reportReason;
    private String reportMessage;
    private Reason inputReason;
    private String inputMessage;
    private int inputUnits = Integer.MAX_VALUE;
    private String internalMessage;
    private String conflictMessage;
    private String stopMessage;
    private int warmAnomalies;
    private int machineIncomplete;
    private int disagreements;
    private final String[] events = new String[MAX_EVENTS];
    private int eventCount;
    private UnitNamer namer;

    public VerdictBoard() {}

    /** Pre-allocates the status column for {@code units} units (optional; it grows on demand). */
    public void ensureUnits(int units) {
        if (units > 0) {
            segment(units - 1);
        }
    }

    public void setNamer(UnitNamer namer) {
        synchronized (this) {
            this.namer = namer;
        }
    }

    // ---- reports ---------------------------------------------------------------------------------------

    public void accept(int unit) {
        if (!segment(unit).compareAndSet(unit & SEG_MASK, NONE, ACCEPTED)) {
            conflict(unit, ACCEPTED);
        }
    }

    /** An unsafe or partial record that was dropped at load time. */
    public void skip(int unit) {
        if (!segment(unit).compareAndSet(unit & SEG_MASK, NONE, SKIPPED)) {
            conflict(unit, SKIPPED);
        }
    }

    /** A rejection confirmed by the re-check protocol (or produced by the reference kernel). */
    public void reject(int unit, Reason reason, String message) {
        fail(unit, REJECTED, reason, message);
        rejected.incrementAndGet();
    }

    public void decline(int unit, Reason reason, String message) {
        fail(unit, DECLINED, reason, message);
        declined.incrementAndGet();
    }

    private void fail(int unit, int status, Reason reason, String message) {
        if (!segment(unit).compareAndSet(unit & SEG_MASK, NONE, status)) {
            conflict(unit, status);
            return;
        }
        synchronized (this) {
            if (unit < reportUnit) {
                reportUnit = unit;
                reportReason = reason;
                reportMessage = message;
            }
        }
        lowerFirstFailure(unit);
    }

    private void lowerFirstFailure(int unit) {
        int cur = firstFailure.get();
        while (unit < cur && !firstFailure.compareAndSet(cur, unit)) {
            cur = firstFailure.get();
        }
    }

    private void conflict(int unit, int status) {
        synchronized (this) {
            if (conflictMessage == null) {
                conflictMessage = "unit " + unit + " reported twice (" + statusName(status(unit)) + ", then "
                        + statusName(status) + ")";
            }
        }
        conflict = true;
    }

    /**
     * Failure of the input as a whole (reader or loader), position unknown. A reject reason
     * ({@code MALFORMED_EXPORT}) makes the verdict REJECT and cancels every unit; a decline reason
     * means that loading stopped: the verdict is never ACCEPT.
     */
    public void inputFailure(Reason reason, String message) {
        inputFailure(Integer.MAX_VALUE, reason, message);
    }

    /**
     * Failure of the input after {@code unitsLoaded} units were published: units at or above that
     * position do not exist.
     */
    public void inputFailure(int unitsLoaded, Reason reason, String message) {
        boolean cancel;
        synchronized (this) {
            if (inputReason == null || (reason.isReject() && !inputReason.isReject())) {
                inputReason = reason;
                inputMessage = message;
            }
            if (unitsLoaded < inputUnits) {
                inputUnits = unitsLoaded;
            }
            cancel = inputReason.isReject();
        }
        if (cancel) {
            firstFailure.set(-1);
        }
    }

    /**
     * Something went wrong outside a unit boundary (a throwable in a worker loop, a dead thread). The
     * verdict can no longer be anything but DECLINE, and every unit is cancelled.
     */
    public void internalFailure(String message) {
        synchronized (this) {
            if (internalMessage == null) {
                internalMessage = message;
            }
        }
        internalFailure = true;
        firstFailure.set(-1);
    }

    /** Marks the run as complete: the loader reached end of file and {@code units} units exist. */
    public void complete(int units) {
        completeUnits = units;
    }

    /**
     * The run was ended on purpose before every unit was checked (a {@code stop-after} phase): the
     * verdict can no longer be ACCEPT. A later {@link #complete(int)} does not undo it.
     */
    public void stopped(String message) {
        synchronized (this) {
            if (stopMessage == null) {
                stopMessage = message;
            }
        }
        stopped = true;
    }

    // ---- events of the re-check protocol ----------------------------------------------------------------

    /** The primary engine rejected in a warm session what it accepts cold and strict. */
    public void warmAnomaly(int unit, String detail) {
        synchronized (this) {
            warmAnomalies++;
            event("WARM-ANOMALY", unit, detail);
        }
    }

    /** The primary engine rejected what the reference kernel accepts. */
    public void machineIncomplete(int unit, String detail) {
        synchronized (this) {
            machineIncomplete++;
            event("MACHINE-INCOMPLETE", unit, detail);
        }
    }

    /** Differential mode: one engine accepted what the other rejected. */
    public void disagreement(int unit, String detail) {
        synchronized (this) {
            disagreements++;
            event("ENGINE-DISAGREEMENT", unit, detail);
        }
    }

    private void event(String kind, int unit, String detail) {
        if (eventCount < MAX_EVENTS) {
            events[eventCount++] = kind + " unit " + unit + nameOf(unit) + (detail == null ? "" : ": " + detail);
        }
    }

    public synchronized int warmAnomalies() {
        return warmAnomalies;
    }

    public synchronized int machineIncomplete() {
        return machineIncomplete;
    }

    public synchronized int disagreements() {
        return disagreements;
    }

    /** The first events of the re-check protocol, one line each (at most 16 are kept). */
    public synchronized String[] events() {
        String[] out = new String[eventCount];
        System.arraycopy(events, 0, out, 0, eventCount);
        return out;
    }

    // ---- queries ---------------------------------------------------------------------------------------

    /**
     * Smallest unit index with a recorded REJECTED or DECLINED status so far, or
     * {@code Integer.MAX_VALUE}; -1 once the whole run is cancelled (malformed input, internal
     * failure). Units above it are neither handed out nor recorded. A plain volatile read.
     */
    public int firstFailure() {
        return firstFailure.get();
    }

    /** The status of a unit: {@link #NONE}, {@link #ACCEPTED}, {@link #SKIPPED}, {@link #REJECTED}, {@link #DECLINED}. */
    public int status(int unit) {
        AtomicIntegerArray[] dir = segments;
        int s = unit >>> SEG_BITS;
        if (unit < 0 || s >= dir.length || dir[s] == null) {
            return NONE;
        }
        return dir[s].get(unit & SEG_MASK);
    }

    public int rejectedCount() {
        return rejected.get();
    }

    public int declinedCount() {
        return declined.get();
    }

    public boolean isComplete() {
        return completeUnits >= 0;
    }

    public boolean hasInternalFailure() {
        return internalFailure;
    }

    /** True once {@link #inputFailure} was called. */
    public synchronized boolean hasInputFailure() {
        return inputReason != null;
    }

    public boolean hasConflict() {
        return conflict;
    }

    /** Number of units with status ACCEPTED or SKIPPED (a scan; for statistics and tests). */
    public int acceptedCount() {
        AtomicIntegerArray[] dir = segments;
        int n = 0;
        for (AtomicIntegerArray seg : dir) {
            if (seg == null) {
                continue;
            }
            for (int i = 0; i < SEG_SIZE; i++) {
                int st = seg.get(i);
                if (st == ACCEPTED || st == SKIPPED) {
                    n++;
                }
            }
        }
        return n;
    }

    /**
     * The unit whose status decides the verdict: the first unit in file order that is not ACCEPTED or
     * SKIPPED, or -1 if there is none below the end of the input (as far as the board knows it), and
     * -1 when the input itself decides (malformed input: the verdict is REJECT whatever the units
     * that were checked before the reader found it say, so that a pipelined run names the same
     * "unit" as a batch run, which checks none).
     */
    public int decidingUnit() {
        int end;
        synchronized (this) {
            if (inputReason != null && inputReason.isReject()) {
                return -1;
            }
            end = inputUnits;
        }
        int complete = completeUnits;
        if (complete >= 0 && complete < end) {
            end = complete;
        }
        return firstNotAccepted(end);
    }

    /** First unit below {@code end} that is not ACCEPTED or SKIPPED; -1 if all are (unknown units count as not accepted). */
    private int firstNotAccepted(int end) {
        AtomicIntegerArray[] dir = segments;
        if (end == Integer.MAX_VALUE) {
            // Position unknown: the units that exist are the ones with a status.
            end = highestReported(dir) + 1;
        }
        for (int u = 0; u < end; u++) {
            int s = u >>> SEG_BITS;
            if (s >= dir.length || dir[s] == null) {
                return u;
            }
            int st = dir[s].get(u & SEG_MASK);
            if (st != ACCEPTED && st != SKIPPED) {
                return u;
            }
        }
        return -1;
    }

    private static int highestReported(AtomicIntegerArray[] dir) {
        for (int s = dir.length - 1; s >= 0; s--) {
            if (dir[s] == null) {
                continue;
            }
            for (int i = SEG_SIZE - 1; i >= 0; i--) {
                if (dir[s].get(i) != NONE) {
                    return (s << SEG_BITS) | i;
                }
            }
        }
        return -1;
    }

    /** The exit code under the rule above; never EXIT_ACCEPT before {@link #complete(int)} was called. */
    public int exitCode() {
        if (internalFailure) {
            return Outcome.EXIT_DECLINE;
        }
        Reason input;
        int inputPos;
        synchronized (this) {
            input = inputReason;
            inputPos = inputUnits;
        }
        if (input != null && input.isReject()) {
            return Outcome.EXIT_REJECT;
        }
        if (conflict) {
            return Outcome.EXIT_DECLINE;
        }
        int complete = completeUnits;
        int end = inputPos;
        if (input == null && complete >= 0) {
            end = complete;
        } else if (complete >= 0 && complete < end) {
            end = complete;
        }
        int u = firstNotAccepted(end);
        if (u >= 0) {
            return status(u) == REJECTED ? Outcome.EXIT_REJECT : Outcome.EXIT_DECLINE;
        }
        if (input != null || complete < 0 || stopped) {
            return Outcome.EXIT_DECLINE;
        }
        // Every unit below `complete` is accepted or skipped. Counts must be consistent: no status at or above it.
        if (highestReported(segments) >= complete) {
            return Outcome.EXIT_DECLINE;
        }
        return Outcome.EXIT_ACCEPT;
    }

    /** One line for stderr describing the reported failure, or null on accept. Bounded to about 1 KiB. */
    public String explanation() {
        int code = exitCode();
        if (code == Outcome.EXIT_ACCEPT) {
            return null;
        }
        String line;
        synchronized (this) {
            if (internalFailure) {
                line = "internal error: " + internalMessage;
            } else if (inputReason != null && inputReason.isReject()) {
                line = "input rejected (" + inputReason + "): " + inputMessage;
            } else if (conflict) {
                line = "internal error: " + conflictMessage;
            } else {
                int u = decidingUnit();
                if (u >= 0) {
                    int st = status(u);
                    if (st == NONE) {
                        line = inputReason != null && u >= inputUnits
                                ? "input declined (" + inputReason + "): " + inputMessage
                                : stopMessage != null ? stopMessage
                                        : "unit " + u + nameOf(u) + " was not decided (run incomplete)";
                    } else if (u == reportUnit) {
                        line = "unit " + u + nameOf(u) + " " + statusName(st) + " (" + reportReason + ")"
                                + (reportMessage == null ? "" : ": " + reportMessage);
                    } else {
                        line = "unit " + u + nameOf(u) + " " + statusName(st);
                    }
                } else if (inputReason != null) {
                    line = "input declined (" + inputReason + "): " + inputMessage;
                } else if (stopMessage != null) {
                    line = stopMessage;
                } else if (completeUnits < 0) {
                    line = "run incomplete: end of input was not reached";
                } else {
                    line = "inconsistent unit counts: a status exists beyond the " + completeUnits + " units of the input";
                }
            }
        }
        line = Outcome.name(code) + ": " + line.replace('\n', ' ').replace('\r', ' ');
        return line.length() > 1024 ? line.substring(0, 1021) + "..." : line;
    }

    private String nameOf(int unit) {
        UnitNamer n = namer;
        if (n == null) {
            return "";
        }
        try {
            String s = n.name(unit);
            return s == null ? "" : " '" + s + "'";
        } catch (RuntimeException e) {
            return "";
        }
    }

    static String statusName(int status) {
        switch (status) {
            case NONE:
                return "undecided";
            case ACCEPTED:
                return "accepted";
            case SKIPPED:
                return "skipped";
            case REJECTED:
                return "rejected";
            case DECLINED:
                return "declined";
            default:
                return "invalid(" + status + ")";
        }
    }

    // ---- status column ------------------------------------------------------------------------------------

    private AtomicIntegerArray segment(int unit) {
        if (unit < 0) {
            throw new IllegalArgumentException("unit " + unit);
        }
        int s = unit >>> SEG_BITS;
        AtomicIntegerArray[] dir = segments;
        if (s < dir.length && dir[s] != null) {
            return dir[s];
        }
        return grow(s);
    }

    private synchronized AtomicIntegerArray grow(int s) {
        AtomicIntegerArray[] dir = segments;
        if (s < dir.length && dir[s] != null) {
            return dir[s];
        }
        AtomicIntegerArray[] next = new AtomicIntegerArray[Math.max(s + 1, dir.length)];
        System.arraycopy(dir, 0, next, 0, dir.length);
        for (int i = 0; i <= s; i++) {
            if (next[i] == null) {
                next[i] = new AtomicIntegerArray(SEG_SIZE);
            }
        }
        segments = next;
        return next[s];
    }
}
