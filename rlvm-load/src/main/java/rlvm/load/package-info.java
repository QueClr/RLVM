/**
 * RLVM module {@code rlvm-load}: turns the record stream of an export into the shared store
 * ({@code docs/design/ARCHITECTURE.md} section 3).
 *
 * <p>{@link rlvm.load.Loader} is an {@code rlvm.export.ExportSink}: structural name interning, the
 * per-declaration segment buffer with the ownership split (shared terms versus proof block),
 * promotion of block records that a later declaration references, the referrer counters behind the
 * sharing flags, the claims blob of inductive blocks ({@link rlvm.load.BlockClaims}).
 * {@link rlvm.load.ExportLoader} runs reader and loader over a file. {@link rlvm.load.LoadTarget} is
 * the narrow interface through which the loader writes; its production implementation binds to
 * {@code rlvm.store}. Pipelined publication (work package L2) is not implemented.
 *
 * <p>Depends on {@code rlvm-store}, {@code rlvm-export}, {@code rlvm-level} and {@code rlvm-nat} (the
 * last two only for the adapters {@link rlvm.load.LevelStoreInterner} and
 * {@link rlvm.load.NatStoreDecoder}). Must never depend on an engine, on admission, or on the driver.
 * Kernel logic does not belong here: the loader validates only what the official reader validates
 * (ids defined before use and bound once, declaration names unique, record shapes).
 */
package rlvm.load;
