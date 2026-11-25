/**
 * RLVM module {@code rlvm-main}: the kernel as a plug-in of the launcher. It contains the one class
 * that the launcher's service lookup finds ({@link rlvm.main.RlvmChecker}, to implement
 * {@code rlvm.cli.Checker}) and the wiring: launcher options to {@code RunConfig}, engine lookup
 * through {@link rlvm.api.EngineProvider}, reader + loader + driver, exit code to verdict
 * (*agentic-note*: design-architecture-document, sections 1 and 8).
 *
 * <p>Depends on {@code rlvm-driver} and what it needs; later on {@code rlvm-cli} (the SPI),
 * {@code rlvm-export}, {@code rlvm-level}, {@code rlvm-nat}. It has no compile-time dependency on an
 * engine: {@code rlvm-ref} and the machine register themselves as {@code EngineProvider}s. Nothing
 * depends on this module.
 */
package rlvm.main;
