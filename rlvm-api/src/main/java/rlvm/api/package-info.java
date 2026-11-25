/**
 * RLVM module {@code rlvm-api}: the contracts between "everything around the machine"
 * (*agentic-note*: design-architecture-document) and the checking engines.
 *
 * <ul>
 *   <li>{@link rlvm.api.Engine} / {@link rlvm.api.Session}: the facade an engine offers to the driver:
 *       "check this axiom/definition/theorem/opaque constant and return a verdict", plus statistics.
 *       Implemented by the machine (*agentic-note*: design-isa-document) and by the reference kernel.
 *   <li>{@link rlvm.api.EngineProvider}: how an engine module registers itself (ServiceLoader).
 *   <li>{@link rlvm.api.KernelOps}: infer / whnf / def-eq on term ids, for admission code.
 *   <li>{@link rlvm.api.LevelOps}, {@link rlvm.api.NatOps}: adapter interfaces over {@code rlvm-level}
 *       and {@code rlvm-nat}, so that engines and admission do not depend on those modules' classes.
 *   <li>{@link rlvm.api.Outcome}, {@link rlvm.api.Reason}, {@link rlvm.api.KernelException},
 *       {@link rlvm.api.ResourceException}, {@link rlvm.api.Budget}, {@link rlvm.api.RunSignals}:
 *       verdict vocabulary and cancellation.
 * </ul>
 *
 *
 * <p>{@link rlvm.api.MachineErrors} is the table of the machine's {@code E_*} codes and their
 * {@link rlvm.api.Reason}s. The package {@code rlvm.api.bind} holds the implementations of the two
 * adapter interfaces over {@code rlvm-level} and {@code rlvm-nat} and the factory of worker contexts.
 *
 * <p>Depends on {@code rlvm-store}; {@code rlvm.api.bind} additionally on {@code rlvm-level} and
 * {@code rlvm-nat}. Must never depend on an engine, on Truffle, or on the driver.
 */
package rlvm.api;
