/**
 * RLVM module {@code rlvm-export}: the reader for lean4export NDJSON files.
 *
 * <p>{@link rlvm.export.ExportReader} turns a file into calls on an {@link rlvm.export.ExportSink};
 * {@link rlvm.export.ColumnarStore} is a reference sink in flat arrays; {@link rlvm.export.Ownership}
 * computes the proof-private split and last use per expression. See the module README.
 */
package rlvm.export;
