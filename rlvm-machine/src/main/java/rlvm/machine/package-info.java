/**
 * RLVM module {@code rlvm-machine}: the machine of *agentic-note*: design-isa-document - an instruction set for
 * Lean 4 normalisation by evaluation and definitional equality, the worklist mechanisation of the Lean
 * kernel's type checker. Registered as {@link rlvm.api.EngineProvider} "machine" beside "reference".
 *
 * <p>Reading order: *agentic-note*: owner-notes, *agentic-note*: design-reconciliation-document, *agentic-note*: design-isa-document,
 * *agentic-note*: rep-stepper-convention, then *agentic-note*: rep-machine-work-packages and
 * *agentic-note*: rep-machine-ownership. Shared definitions: {@link rlvm.machine.Layout}, {@link rlvm.machine.Op},
 * {@link rlvm.machine.Hash}, {@link rlvm.machine.Machine}, {@link rlvm.machine.Frames},
 * {@link rlvm.machine.Ops}.
 *
 * <p>Rules of the module (ISA 13.1): values, runs, frames and tables are {@code int} ids in flat
 * primitive arrays; no Java object per value, no boxing, no {@code java.util} collection, lambda, stream
 * or exception on a normal path; no Truffle artifact, no reflection. Compile dependencies:
 * {@code rlvm-store}, {@code rlvm-api}, {@code rlvm-level}, {@code rlvm-nat}.
 */
package rlvm.machine;
