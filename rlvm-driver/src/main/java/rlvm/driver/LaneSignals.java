package rlvm.driver;

import rlvm.api.RunSignals;

/**
 * The {@link RunSignals} one checking thread hands to its sessions. {@link #firstFailure()} is the
 * board's volatile read. {@link #memoryExhausted()} is the poll at which the thread's footprint is
 * sampled into the {@link MemoryAccount}: sessions call it every few thousand steps, so a unit that
 * grows without bound is seen while it runs, not only at the next unit boundary.
 *
 * <p>The answer of {@link #memoryExhausted()} depends on what the thread is doing:
 * <ul>
 *   <li>ordinarily: this thread is the one that has to give way ({@link MemoryAccount#exhausted(int)});
 *   <li>while it holds the heavy gate for a unit ({@link #holding(HeavyGate, int)}): the unit does not
 *       fit into the limit by itself, or a lower unit waits for the gate ({@link #yielded()} then
 *       tells the worker that the decline is not the unit's outcome);
 *   <li>while a guard is set ({@link #guard(int)}): additionally, the first failure lies below the
 *       guarded unit.
 * </ul>
 */
final class LaneSignals implements RunSignals {

    /** Samples the owning thread's session footprint and reports it to the memory account. */
    interface Probe {
        void sample();
    }

    private final VerdictBoard board;
    private final MemoryAccount account;
    private final int slot;
    private final boolean lastResort;
    private Probe probe;
    private volatile int guard = -1;

    // confined to the owning thread
    private HeavyGate gate;
    private int gateUnit = -1;
    private boolean yielded;
    private boolean memoryDeclined;

    /** @param slot the owning thread's slot of the memory account */
    LaneSignals(VerdictBoard board, MemoryAccount account, int slot) {
        this(board, account, slot, false);
    }

    /**
     * @param lastResort the owner is the environment thread: it gives way to memory pressure only
     *     when the workers cannot ({@link MemoryAccount#exhaustedLastResort(int)})
     */
    LaneSignals(VerdictBoard board, MemoryAccount account, int slot, boolean lastResort) {
        this.board = board;
        this.account = account;
        this.slot = slot;
        this.lastResort = lastResort;
    }

    void setProbe(Probe probe) {
        this.probe = probe;
    }

    @Override
    public int firstFailure() {
        return board.firstFailure();
    }

    /**
     * The owner is about to run judgments for units at or above {@code unit} outside
     * {@code Session.check} (the environment pass, through {@code KernelOps}); -1 ends it. A kernel
     * that is used through {@code KernelOps} need not know its unit and then cannot compare it with
     * {@link #firstFailure()}; but it does poll {@link #memoryExhausted()}, which then answers true
     * as soon as the first failure lies below the guarded unit. The work is given up with a decline
     * that is never recorded (it lies behind the first failure), instead of running on, possibly
     * forever, behind an ill-typed earlier unit.
     */
    void guard(int unit) {
        guard = unit;
    }

    /** The owning thread holds {@code gate} for {@code unit} from now on. */
    void holding(HeavyGate gate, int unit) {
        this.gate = gate;
        this.gateUnit = unit;
        this.yielded = false;
    }

    /** The owning thread gave the gate up. */
    void released() {
        this.gate = null;
        this.gateUnit = -1;
    }

    /** True if the last answer "exhausted" while holding the gate was a request to give way to a lower unit. */
    boolean yielded() {
        return yielded;
    }

    /** True if {@link #memoryExhausted()} answered true since the last {@link #clearMemoryDeclined()}. */
    boolean memoryDeclined() {
        return memoryDeclined;
    }

    void clearMemoryDeclined() {
        memoryDeclined = false;
    }

    @Override
    public boolean memoryExhausted() {
        Probe p = probe;
        if (p != null) {
            p.sample();
        }
        int g = guard;
        if (g >= 0 && board.firstFailure() < g) {
            return true;
        }
        HeavyGate h = gate;
        if (h != null) {
            if (h.shouldYield(gateUnit)) {
                yielded = true;
                memoryDeclined = true;
                return true;
            }
            if (account.exhaustedAlone(slot)) {
                memoryDeclined = true;
                return true;
            }
            return false;
        }
        if (lastResort ? account.exhaustedLastResort(slot) : account.exhausted(slot)) {
            memoryDeclined = true;
            return true;
        }
        return false;
    }
}
