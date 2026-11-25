package rlvm.driver;

/**
 * The loader's frontier as the driver sees it during a pipelined load (*agentic-note*: design-architecture-document, 3.5):
 * {@link #published(int)} is called on the loading thread after every
 * {@code Store.publishLoaded(units, complete)}, so that the environment thread and the workers are
 * woken at hand-offs and nobody polls. In production the caller is the loader's own
 * {@code rlvm.load.LoadListener}, which the driver puts into the {@code LoadOptions} it hands to
 * {@link Wiring#load}; the driver's tests call it from a fake loader.
 *
 * <p>What the caller guarantees: everything units {@code [0, units)} mention (terms, proof blocks,
 * names, literals, levels and lists) was readable by other threads before the call; the same value
 * may be announced twice; the last publication (end of file) is announced too. The call is cheap
 * (one lock, a notification) and never throws.
 */
interface LoadProgress {

    /** Units {@code [0, units)} are published. */
    void published(int units);
}
