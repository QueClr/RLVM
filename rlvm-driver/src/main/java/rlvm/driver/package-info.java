/**
 * RLVM module {@code rlvm-driver}: runs a check from a store (or an export, through a
 * {@link rlvm.driver.Wiring}) to a verdict (*agentic-note*: design-architecture-document, sections 6 and 8 as
 * amended by *agentic-note*: design-reconciliation-document, sections 2 and 3; see the module README).
 *
 * <ul>
 *   <li>{@link rlvm.driver.Run}: the orchestrator. Loads in batch mode through the wiring, starts the
 *       workers (and, with more than one worker, the environment thread), computes the verdict,
 *       writes statistics.
 *   <li>{@link rlvm.driver.Wiring}: what the wiring module supplies: store, load, worker contexts.
 *   <li>{@link rlvm.driver.Scheduler}: contiguous runs of ready units per worker, stealing, give-back.
 *   <li>{@link rlvm.driver.OrderPolicy}: the order in which ready units are offered (file order only).
 *   <li>{@link rlvm.driver.Worker}: one checking thread with its lanes and sessions; the re-check
 *       protocol.
 *   <li>{@link rlvm.driver.VerdictBoard}: per-unit outcomes, first failure, the fail-closed exit code.
 *   <li>{@link rlvm.driver.MemoryAccount}: the run's memory account and watermark.
 *   <li>{@link rlvm.driver.RunHost} / {@link rlvm.driver.StoreHost}: the seam between the driver and
 *       the modules below it (unit table, admission, environment pass), so that the driver's own
 *       logic is tested with fakes.
 *   <li>{@link rlvm.driver.RunConfig}, {@link rlvm.driver.RunStats}.
 * </ul>
 *
 * <p>Depends on {@code rlvm-store}, {@code rlvm-api}, {@code rlvm-load}, {@code rlvm-admit}. Engines
 * arrive as {@code EngineFactory} values: the driver must never depend on the machine, on the
 * reference kernel's classes, on the reader or on Truffle (its tests do, in test scope). It never
 * calls {@code System.exit}; {@code rlvm-cli} does.
 */
package rlvm.driver;
