package rlvm.api;

/**
 * A checking engine: the machine of *agentic-note*: design-isa-document, or the reference kernel. One instance
 * per run, shared by all worker threads; everything mutable lives in the {@link Session}s it hands
 * out, except engine-wide artefacts that are functions of the immutable store alone (for the machine:
 * the code objects elaborated from constants and whatever the Truffle tier compiled from them).
 *
 * <p>Contract with the driver:
 * <ul>
 *   <li>The engine reads only published store data: a session asked to check a constant of unit
 *       {@code u} may read units below {@code u} (rows, derived facts, terms, proof blocks).
 *   <li>Engine-wide artefacts must be safe to build concurrently from several sessions and must not
 *       make a verdict depend on which session built them or when.
 *   <li>The engine never calls {@code System.exit} and never prints; diagnostics go through
 *       {@link #dumpStats(StatsSink)} and {@link Session#lastMessage()}.
 * </ul>
 */
public interface Engine extends AutoCloseable {

    /** Short stable name ("machine", "reference") for reports. */
    String name();

    /**
     * A new session for the calling worker thread. The session owns {@code ctx} (overlay, list and
     * level overlays) until it is closed.
     */
    Session newSession(WorkerContext ctx);

    /**
     * Hint from the environment thread: every unit below {@code units} now has its derived facts.
     * An engine may use it to elaborate constants ahead of use; the default does nothing.
     */
    default void environmentAdvanced(int units) {}

    /**
     * Writes counters and heat tables (per-instruction counts, per-constant entry counts, tier
     * promotions, cache hit rates, compile time). Called once, after the last session is closed.
     * Must cost nothing when statistics were not enabled in the {@link EngineConfig}.
     */
    void dumpStats(StatsSink sink);

    @Override
    void close();
}
