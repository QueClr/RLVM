/**
 * RLVM module {@code rlvm-inductive}: admission of inductive blocks and of the quotient package, a
 * port of {@code refs/lean4/src/kernel/inductive.cpp} and {@code quot.cpp} at v4.36.0 as specified in
 * {@code docs/research/03-kernel-declarations.md}.
 *
 * <p>Regenerate and compare: from the inputs of a block (universe parameters, parameter count, names
 * and types of the types and constructors) {@link rlvm.inductive.InductiveAdmission} derives every
 * other fact and every recursor, and {@link rlvm.inductive.RecordCompare} requires each exported
 * constructor and recursor record to be identical to the derived one (structural {@code ==}, never
 * definitional equality). Nothing exported is used.
 *
 * <p>All judgments go through {@link rlvm.api.KernelOps}; the code is engine-agnostic. Cold path:
 * about 1 % of the declarations of a library.
 *
 * <p>Depends on {@code rlvm-store} and {@code rlvm-api}. Must never depend on an engine
 * implementation, the loader, or the driver.
 */
package rlvm.inductive;
