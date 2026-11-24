/**
 * RLVM module {@code rlvm-store}: the persistent data of a run.
 *
 * <p>Everything here is plain data in flat primitive arrays (decision A of the architecture): terms,
 * names, literals, declarations and proof blocks are {@code int} ids; id 0 is "none". The module holds
 * no kernel logic and depends on nothing but the JDK. Design: {@code docs/design/ARCHITECTURE.md}
 * sections 2 (layouts) and 3 (how the loader fills it).
 *
 * <ul>
 *   <li>{@link rlvm.store.Node}, {@link rlvm.store.Kind}: the 32-byte term node and its bit layout.
 *   <li>{@link rlvm.store.TermStore}: the shared, append-only, progressively frozen term regions (flat arrays).
 *   <li>{@link rlvm.store.Overlay}: a worker's private scratch terms (hash-consed, bulk reset).
 *   <li>{@link rlvm.store.Terms}: one reader over shared and overlay ids; {@link rlvm.store.TermOps}:
 *       syntactic operations (instantiate, abstract, structural equality).
 *   <li>{@link rlvm.store.NameTable}, {@link rlvm.store.LitPool}, {@link rlvm.store.IntLists}.
 *   <li>{@link rlvm.store.DeclTable}, {@link rlvm.store.DeclKind}, {@link rlvm.store.Specials}: every
 *       per-constant fact the kernel reads.
 *   <li>{@link rlvm.store.ProofBlocks}: packed, undecoded proof terms; {@link rlvm.store.SharedFacts}:
 *       racy idempotent per-term facts.
 *   <li>{@link rlvm.store.Store}: the aggregate plus the two publication frontiers;
 *       {@link rlvm.store.StoreBuilder}: the single-writer interface of the loader.
 * </ul>
 *
 * <p>Must never depend on: {@code rlvm-export} (the reader), any checker module, Truffle.
 */
package rlvm.store;
