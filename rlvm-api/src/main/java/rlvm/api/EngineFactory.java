package rlvm.api;

import rlvm.store.Store;

/**
 * Creates an {@link Engine} over a store. The CLI passes factories to the driver, so the driver has
 * no compile-time dependency on any engine implementation.
 */
@FunctionalInterface
public interface EngineFactory {

    /**
     * @param store the run's store; loading may still be in progress (the engine reads published units only)
     * @param config engine options
     */
    Engine create(Store store, EngineConfig config);
}
