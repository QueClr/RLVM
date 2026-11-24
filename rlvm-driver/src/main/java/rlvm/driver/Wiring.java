package rlvm.driver;

import rlvm.api.RunSignals;
import rlvm.api.WorkerContext;
import rlvm.load.LoadOptions;
import rlvm.load.LoadReport;
import rlvm.store.Store;

/**
 * What the driver cannot do itself because it must not see {@code rlvm-export}, {@code rlvm-level} or
 * {@code rlvm-nat}: creating the store, reading the export into it, and creating the per-thread
 * {@link WorkerContext}s. Implemented by the wiring module ({@code rlvm-main}) on top of
 * {@code rlvm.api.bind.Bindings}; shape agreed in {@code work/status/requests.log}.
 */
public interface Wiring {

    /** The run's store, sized for an input of {@code inputBytes} bytes (0 = unknown). Called once. */
    Store newStore(long inputBytes);

    /**
     * Reads the export into {@code store} on the calling thread: reader, loader, store; returns after
     * {@code Loader.finish()} and after everything the workers read is frozen.
     *
     * <p><b>Pipelined</b> ({@code options.mode == PIPELINED}, ARCHITECTURE.md 3.5): the options are
     * passed to the loader as they are. They carry the hand-off interval and the driver's
     * {@code rlvm.load.LoadListener}: units are published while the file is read
     * ({@code Store.publishLoaded}) and every publication is announced to the listener on the calling
     * thread, after everything the published units mention (terms, blocks, names, literals, levels
     * and lists) became readable by other threads. The environment thread and the workers of the run
     * check published units meanwhile, so {@link #newWorkerContext} is called on other threads while
     * this method runs (never before the first publication), and a context covers the units below
     * {@code WorkerContext.coveredUnits()}; the driver resets it before a unit beyond that. A wiring
     * that loads in batch mode whatever the options say is fine: one publication at the end is a
     * batch run.
     *
     * @throws rlvm.load.MalformedExportException the official reader rejects the file (verdict REJECT)
     * @throws rlvm.load.UnsupportedExportException RLVM cannot represent the file (the published units
     *     are still checked; the verdict is never ACCEPT)
     * @throws Exception anything else, e.g. {@code IOException}: the run declines
     */
    void load(Store store, LoadOptions options) throws Exception;

    /** Counters of the load; valid after {@link #load} returned or threw. May be null. */
    LoadReport loadReport();

    /**
     * A new context over {@code store}. Called on the thread that will use it. A worker thread asks
     * for several: one per session it keeps (warm session, strict re-check session, reference
     * re-check session, environment session). {@code workerId} is unique among the sessions of one
     * engine instance, not among all contexts: the warm, strict and reference lanes of worker
     * {@code w} all use {@code w}; the environment lane uses the number of workers.
     *
     * @param overlayMaxBytes hard cap of the context's term overlay
     */
    WorkerContext newWorkerContext(int workerId, Store store, RunSignals signals, long overlayMaxBytes);
}
