package rlvm.store;

/**
 * Hash-consed immutable lists of {@code int}s (universe-level lists, parameter-name lists, constructor
 * lists, recursor rule tables, sets of parameter names). List id 0 is the empty list; equal contents
 * give equal ids.
 *
 * <p>This is an adapter interface: the run uses ONE list store, the one inside {@code rlvm-level}
 * ({@code rlvm.level.IntListStore}), so that a level-list id means the same thing to the store, the
 * level algebra and the machine. Like the level store it has a frozen shared base and per-worker
 * overlays; a worker's instance may intern, the shared one may not after it is frozen.
 */
public interface IntLists {

    int EMPTY = 0;

    /** Interns {@code buf[from .. from+len)}. */
    int intern(int[] buf, int from, int len);

    int length(int list);

    int get(int list, int index);

    void copyTo(int list, int[] dst, int dstOff);

    /**
     * Union of two sorted (ascending) duplicate-free lists (used for lpSet). The default merges into a
     * temporary array and interns; implementations may memoise. The store keeps its own pair cache in
     * front of this call, so a plain implementation is fine.
     */
    default int unionSorted(int setA, int setB) {
        return IntListOps.unionSorted(this, setA, setB);
    }

    /**
     * True if {@code list} is an id of the run's SHARED list store: it denotes the same list for
     * every thread and for the rest of the run (an id of the frozen base, not of a worker's
     * overlay). The generated term region and generated constants accept only such ids
     * ({@link GeneratedBuilder}). The default answers false for every non-empty list: an
     * implementation that cannot tell declines to share.
     */
    default boolean isSharedList(int list) {
        return list == EMPTY;
    }

    /** True if every element of sorted set {@code sub} occurs in list {@code list} (any order). */
    default boolean subsetOf(int sub, int list) {
        return IntListOps.subsetOf(this, sub, list);
    }
}
