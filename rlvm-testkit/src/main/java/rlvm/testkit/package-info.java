/**
 * RLVM module {@code rlvm-testkit}: tools for the testing strategy of
 * {@code docs/design/ARCHITECTURE.md} section 9. Not part of the shipped binary.
 *
 * <ul>
 *   <li>{@link rlvm.testkit.DiffEngine}: an {@code Engine} that runs two engines on every unit and
 *       records any disagreement (machine against reference kernel);
 *       {@link rlvm.testkit.DiffProvider} offers it to {@code ServiceLoader}-based launchers.
 *   <li>{@link rlvm.testkit.Leanchecker}: runs the official {@code leanchecker --from-export} as the
 *       ground truth for verdicts, with a cache by content hash;
 *       {@link rlvm.testkit.OfficialVerdicts} applies it to a directory of arena tests and writes
 *       the verdict cache and the manifest of expected outcomes.
 *   <li>{@link rlvm.testkit.ExportMutator}: derives variants of an export (forged recursor rule,
 *       altered level, swapped argument, ...) for differential runs against the official checker;
 *       {@link rlvm.testkit.MutantCorpus} builds a classified corpus of them and judges a checker
 *       against it.
 *   <li>{@link rlvm.testkit.Main}: the command line ({@code rlvm-testkit/bin/testkit}).
 * </ul>
 *
 * <p>Depends on {@code rlvm-store}, {@code rlvm-api}, {@code rlvm-load}, {@code rlvm-driver} (only
 * {@code DiffEngine} uses RLVM types). Nothing may depend on this module except tests and developer
 * launchers.
 */
package rlvm.testkit;
