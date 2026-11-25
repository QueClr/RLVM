/**
 * RLVM module {@code rlvm-admit}: the declaration admission pipeline and its policies
 * (*agentic-note*: design-architecture-document, section 4).
 *
 * <ul>
 *   <li>{@link rlvm.admit.StructuralChecks}: the checks every declaration passes before an engine
 *       sees it (closedness, declared universe parameters, visibility of constants by rank, universe
 *       arity, duplicate names), O(1) per root from the node summaries with an exact walk as fallback.
 *   <li>{@link rlvm.admit.EnvBuilder}: the environment pass. Walks the units in admission order,
 *       admits inductive blocks and the quotient package through {@code rlvm-inductive}, binds the
 *       special constants and runs {@link rlvm.admit.PreludeGuards}, writes the derived facts and
 *       advances {@code Store.envUnits()}; stops for good at the first unit that fails.
 *   <li>{@link rlvm.admit.Admission}: the per-unit flow a worker runs for axioms, definitions,
 *       theorems and opaques: structural checks, then {@code Session.check}.
 *   <li>{@link rlvm.admit.Policy}: the knobs (axioms, forward references, unconfirmed rejections,
 *       prelude guards).
 * </ul>
 *
 * <p>Rule of the module: a REJECT is issued only where the official checker certainly rejects;
 * whatever else cannot be accepted is a DECLINE.
 *
 * <p>Depends on {@code rlvm-store}, {@code rlvm-api}, {@code rlvm-inductive}. Must never depend on an
 * engine implementation, the loader, or the driver; it receives sessions and {@code KernelOps}.
 */
package rlvm.admit;
