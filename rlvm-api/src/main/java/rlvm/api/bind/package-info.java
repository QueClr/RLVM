/**
 * Bindings of the {@code rlvm.api} adapter interfaces to the wave-2a modules:
 * {@link rlvm.api.bind.LevelBinding} ({@code LevelOps} + {@code IntLists} over
 * {@code rlvm.level.LevelStore}), {@link rlvm.api.bind.NatBinding} ({@code NatOps} over
 * {@code rlvm.nat}), {@link rlvm.api.bind.NameOracles} (Lean's name order from the store's
 * {@code NameTable}) and {@link rlvm.api.bind.Bindings}, the per-run factory that creates the base
 * stores and the per-worker {@code WorkerContext}.
 *
 * <p>This is the only package of {@code rlvm-api} that sees {@code rlvm-level} and {@code rlvm-nat};
 * engines, admission and the driver use the interfaces of {@code rlvm.api}.
 */
package rlvm.api.bind;
