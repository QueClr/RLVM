package rlvm.admit;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import rlvm.api.Reason;
import rlvm.store.Store;

/**
 * What the environment pass recorded for the units it decided in dependency order
 * ({@code Policy.ForwardRefs.REORDER}): from the first unit of a file that is not in declaration
 * order (or has another irregularity that only the official replay order decides) the pass admits
 * every unit itself, dependencies first, and a worker that draws such a unit only reads the outcome
 * here ({@link Admission}).
 *
 * <p>Written by the one thread that drives the environment pass; a unit's entries are complete before
 * {@code Store.publishEnv} moves the frontier past the unit, and a worker reads them only for units
 * below the frontier it has read, so the frontier's volatile write and read order the accesses.
 *
 * <p>A worker's {@link Admission} has no reference to the {@link EnvBuilder} of the run (the driver
 * creates them separately), so the state is found through the store: {@link #find(Store)}.
 */
final class ReplayState {

    static final byte NEW = 0;
    static final byte ACTIVE = 1;
    static final byte DONE = 2;

    private static final Map<Store, ReplayState> BY_STORE = Collections.synchronizedMap(new WeakHashMap<>());
    /** Number of states ever published; 0 means no store is in replay mode (one volatile read per unit). */
    private static volatile int published;

    /** The first unit decided in dependency order; every unit from here on is. */
    final int first;
    /** Number of units of the (completely loaded) file. */
    final int total;

    /** Per unit ({@code unit - first}): NEW, ACTIVE (its dependencies are being admitted), DONE. */
    final byte[] state;
    /** Outcome of a DONE unit: null = admitted, else the reject or decline reason. */
    final Reason[] reason;
    final String[] message;
    /** Largest rank of a constant in the dependency closure of a DONE unit (0 = none). */
    final int[] closureMax;
    /**
     * Dependencies of a unit: a positive entry is the constant id of a constant of another unit that
     * a kernel-checked root of the unit mentions; a negative entry {@code ~unit} is a unit the
     * admission of this one needs for another reason (literal prerequisites, the quotient package).
     */
    final int[][] needs;
    /** Mentions of a mutual block that only a type header makes (not forced by the official replay), or null. */
    final int[][] weak;
    /** DFS cursor into {@link #needs}. */
    final int[] cursor;
    /** For an ACTIVE unit: index + 1 of the first dependency that closes a cycle, 0 = none. */
    final int[] cycle;

    /** Constant ids of constructor / recursor records flagged unsafe inside an admitted safe block. */
    private long[] unsafeMembers = new long[4];

    ReplayState(int first, int total) {
        this.first = first;
        this.total = total;
        int n = Math.max(0, total - first);
        this.state = new byte[n];
        this.reason = new Reason[n];
        this.message = new String[n];
        this.closureMax = new int[n];
        this.needs = new int[n][];
        this.weak = new int[n][];
        this.cursor = new int[n];
        this.cycle = new int[n];
    }

    boolean covers(int unit) {
        return unit >= first && unit < total;
    }

    boolean done(int unit) {
        return covers(unit) && state[unit - first] == DONE;
    }

    Reason reasonOf(int unit) {
        return reason[unit - first];
    }

    String messageOf(int unit) {
        return message[unit - first];
    }

    void markUnsafeMember(int cid) {
        int w = cid >>> 6;
        if (w >= unsafeMembers.length) {
            unsafeMembers = java.util.Arrays.copyOf(unsafeMembers, Math.max(w + 1, unsafeMembers.length * 2));
        }
        unsafeMembers[w] |= 1L << (cid & 63);
    }

    boolean isUnsafeMember(int cid) {
        int w = cid >>> 6;
        return w < unsafeMembers.length && (unsafeMembers[w] & (1L << (cid & 63))) != 0;
    }

    // ---- lookup by store -------------------------------------------------------------------------------

    static void publish(Store store, ReplayState state) {
        BY_STORE.put(store, state);
        published++;
    }

    /** Forgets the state an earlier pass over {@code store} published (a new pass starts in file order). */
    static void retire(Store store) {
        if (published != 0) {
            BY_STORE.remove(store);
        }
    }

    /** True if some store has a replay state (then {@link #find} is worth a call). */
    static boolean anyPublished() {
        return published != 0;
    }

    /** The replay state of the store's environment pass, or null. */
    static ReplayState find(Store store) {
        return BY_STORE.get(store);
    }
}
