/**
 * RLVM module {@code rlvm-machine}: the machine of {@code docs/design/ISA.md} - an instruction set for
 * Lean 4 normalisation by evaluation and definitional equality, the worklist mechanisation of the Lean
 * kernel's type checker. Registered as {@link rlvm.api.EngineProvider} "machine" beside "reference".
 *
 * <p>Reading order: {@code docs/design/OWNER-NOTES.md}, {@code RECONCILIATION.md}, {@code ISA.md},
 * {@code review/stepper-convention.md}, then {@code rlvm-machine/WORK-PACKAGES.md} and
 * {@code OWNERSHIP.md}. Shared definitions: {@link rlvm.machine.Layout}, {@link rlvm.machine.Op},
 * {@link rlvm.machine.Hash}, {@link rlvm.machine.Machine}, {@link rlvm.machine.Frames},
 * {@link rlvm.machine.Ops}.
 *
 * <p>Rules of the module (ISA 13.1): values, runs, frames and tables are {@code int} ids in flat
 * primitive arrays; no Java object per value, no boxing, no {@code java.util} collection, lambda, stream
 * or exception on a normal path; no Truffle artifact, no reflection. Compile dependencies:
 * {@code rlvm-store}, {@code rlvm-api}, {@code rlvm-level}, {@code rlvm-nat}.
 */
package rlvm.machine;
