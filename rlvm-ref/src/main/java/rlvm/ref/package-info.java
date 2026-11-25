/**
 * RLVM module {@code rlvm-ref}: the reference kernel, a straightforward port of the official
 * substitution-based type checker ({@code refs/lean4/src/kernel/type_checker.cpp} at v4.36.0, as
 * specified in *agentic-note*: study-kernel-typechecker) over term ids.
 *
 * <p>Purpose (*agentic-note*: design-architecture-document, section 5): (1) the judgments used by inductive and
 * quotient admission, including the exact syntactic output of {@code whnf}; (2) the oracle of the
 * differential tests; (3) the re-check before a rejection is reported; (4) a complete
 * {@link rlvm.api.Engine} so that the whole pipeline runs before the machine exists.
 *
 * <p>Rules: follow the official control flow line by line; no shortcut that the official kernel does
 * not have, except identity of hash-consed ids as the equality fast path and caches keyed by id
 * instead of by structure. Seven caches as in the official checker, cleared per declaration.
 *
 * <p>Depends on {@code rlvm-store} and {@code rlvm-api}. Must never depend on the machine or Truffle.
 */
package rlvm.ref;
