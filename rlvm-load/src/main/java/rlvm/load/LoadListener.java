package rlvm.load;

/**
 * The loader's hand-off signal ({@link LoadOptions#withListener}): the hook with which a driver wakes
 * the threads that wait for more units, instead of polling {@code Store.loadedUnits()}.
 *
 * <p>Order of events at a publication, all on the loader thread:
 * <ol>
 *   <li>{@link LevelInterner#publish(int)}: the levels and lists created so far become readable
 *       ({@link LevelChain}: a new frozen snapshot);
 *   <li>{@code Store.publishLoaded(units, complete)}: the volatile frontier write. Everything
 *       reachable from units {@code [0, units)} is immutable from here on;
 *   <li>{@link #published}.
 * </ol>
 * So a thread that reads {@code Store.loadedUnits() == n}, by this callback or by itself, finds the
 * level snapshot that covers units {@code [0, n)} already published.
 */
public interface LoadListener {

    /**
     * Units {@code [0, loadedUnits)} are published. Called on the loader thread; must not block for
     * long and must not call back into the loader.
     *
     * @param loadedUnits the new value of {@code Store.loadedUnits()} (monotone; may repeat)
     * @param complete true for the final publication of a load that reached the end of the file. A load
     *     that fails never reports {@code complete}; its failure is the exception that leaves
     *     {@code ExportLoader.read} on the loader thread, after the last call of this method.
     */
    void published(int loadedUnits, boolean complete);
}
