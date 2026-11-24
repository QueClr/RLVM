package rlvm.load;

/**
 * What the loader needs from the level store ({@code rlvm.level.LevelStore}): interning of the
 * export's level records and of level and name lists, and the publication snapshot.
 *
 * <p>Constructors are the PLAIN ones: an export level record is stored as written (the official
 * reader does not simplify), because exported recursor types are compared syntactically.
 */
public interface LevelInterner {

    /** The level id of {@code Level.zero} (export level id 0). */
    int zero();

    int succ(int level);

    int max(int a, int b);

    int imax(int a, int b);

    int param(int nameId);

    /** Interns a list of level ids or of name ids; 0 for the empty list. */
    int internList(int[] buf, int from, int len);

    /** True if a name list contains the same name twice. */
    boolean hasDuplicates(int nameList);

    /**
     * Makes every level and list created so far readable by other threads (BATCH: called once, at
     * end of file, = freeze; PIPELINED: called before each publication of the store).
     */
    void publish();

    /**
     * {@link #publish()}, told how far the store is about to be published: units {@code [0, units)}
     * mention only levels and lists that exist now. The loader calls this form, immediately before
     * {@code Store.publishLoaded(units, ..)}; an implementation that keeps snapshots records
     * {@code units} with the snapshot ({@link LevelChain}). The default ignores the number.
     */
    default void publish(int units) {
        publish();
    }

    /**
     * The last publication of a load that reached the end of the file: {@link #publish(int)}, after
     * which nothing more is interned. The default is {@code publish(units)}.
     */
    default void finish(int units) {
        publish(units);
    }

    /**
     * Called by the loader on the thread that feeds it, before the first record: the calling thread
     * is the loader thread from here on. The default does nothing.
     */
    default void attach() {
    }
}
