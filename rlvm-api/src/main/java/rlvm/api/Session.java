package rlvm.api;

/**
 * One worker's view of an {@link Engine}: its caches, value arenas and the overlay of its
 * {@link WorkerContext}. Confined to the thread that created it.
 *
 * <p>Lifecycle (driven by {@code rlvm-driver}): {@code check*} many times, with caches surviving from
 * one unit to the next; {@link #reset()} when {@link #footprintBytes()} passes the session cap, after
 * any outcome other than ACCEPT, and before a cold re-check; {@link #close()} at the end.
 *
 * <p>Soundness obligations of an implementation:
 * <ul>
 *   <li>A fact kept across units must be a function of ids and of immutable store data only. In
 *       particular nothing derived while a {@link ResourceException} was propagating, and no fact
 *       that depends on a constant being absent, may be kept.
 *   <li>The outcome of {@link #check(int, Budget)} in a freshly reset session with a given budget is
 *       a function of the store and the constant alone (determinism of the cold re-check).
 * </ul>
 */
public interface Session extends AutoCloseable {

    /**
     * Checks one axiom, definition, theorem or opaque constant, i.e. the official
     * {@code add_axiom / add_definition / add_theorem / add_opaque} after the structural checks:
     * <ol>
     *   <li>the type is well typed (check mode) and its type reduces to a sort;
     *   <li>theorem: the type is a proposition (the sort's level normalises to zero; a stuck sort is an error);
     *   <li>definition, theorem, opaque: the value is well typed (check mode) and its inferred type is
     *       definitionally equal to the declared type, in that argument order.
     * </ol>
     * Established by the caller ({@code rlvm-admit}) before the call, in O(1) from the store: the name
     * is unique, the universe parameters are distinct, type and value are closed, every universe
     * parameter they mention is declared, every constant they mention has a smaller rank and is
     * applied to the right number of levels. The engine may rely on all of that.
     *
     * @return {@link Outcome#ACCEPT}, {@link Outcome#REJECT} or {@link Outcome#DECLINE}; never throws
     *     for a property of the input (only {@link Error}s it cannot handle escape)
     */
    int check(int cid, Budget budget);

    /** Reason of the last non-ACCEPT outcome of this session. */
    Reason lastReason();

    /** Human-readable detail of the last non-ACCEPT outcome; built on demand, may be slow. */
    String lastMessage();

    /**
     * Term-level judgments in this session, for admission code (inductive types, quotients) and for
     * differential tests. May return null if the engine offers none (the driver then uses the
     * reference kernel for admission).
     */
    KernelOps ops();

    /** Forgets everything the session learned; overlay ids handed out before become invalid. */
    void reset();

    /** Bytes of session memory in use (overlay terms plus the engine's arenas). */
    long footprintBytes();

    /** Steps executed since the session was created (monotone; the unit of {@link Budget#maxSteps}). */
    long steps();

    @Override
    void close();
}
