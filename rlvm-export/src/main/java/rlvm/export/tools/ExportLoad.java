package rlvm.export.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import rlvm.export.ColumnarStore;
import rlvm.export.ExportException;
import rlvm.export.ExportReader;
import rlvm.export.ExportSink;

/**
 * Loads an export into a {@link ColumnarStore} (or into a sink that discards everything) and prints
 * what was read and how long it took. This is the command measured for the numbers in the README.
 *
 * <pre>
 * ExportLoad [options] FILE
 *   --io auto|mapped|buffered   input strategy (default auto)
 *   --threads N                 scanner threads (default 1)
 *   --sink store|store-nomask|null   (default store)
 *   --general                   send every line through the general JSON path
 *   --repeat N                  load N times (JIT warm-up studies)
 *   --chunk BYTES               buffer size (1 thread) and chunk size (parallel)
 *   --no-unload                 keep processed pages of the mapping resident
 * </pre>
 * Exit status: 0 read, 1 malformed (reject), 2 unsupported (decline), 3 I/O or usage error.
 */
public final class ExportLoad {
    private ExportLoad() {}

    public static void main(String[] args) {
        ExportReader.Options o = new ExportReader.Options();
        String sinkKind = "store";
        int repeat = 1;
        Path file = null;
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--io" -> o.io = ExportReader.Io.valueOf(args[++i].toUpperCase(java.util.Locale.ROOT));
                    case "--threads" -> o.threads = Integer.parseInt(args[++i]);
                    case "--sink" -> sinkKind = args[++i];
                    case "--general" -> o.generalPathOnly = true;
                    case "--repeat" -> repeat = Integer.parseInt(args[++i]);
                    case "--chunk" -> o.parallelChunkBytes = o.bufferBytes = Integer.parseInt(args[++i]);
                    case "--no-unload" -> o.unloadMapped = false;
                    default -> file = Path.of(args[i]);
                }
            }
            if (file == null) {
                throw new IllegalArgumentException("no file");
            }
        } catch (RuntimeException e) {
            System.err.println("usage: ExportLoad [--io auto|mapped|buffered] [--threads N] "
                    + "[--sink store|store-nomask|null] [--general] [--repeat N] [--chunk BYTES] [--no-unload] FILE");
            System.exit(3);
            return;
        }
        try {
            long size = Files.size(file);
            for (int r = 0; r < repeat; r++) {
                ExportSink sink = switch (sinkKind) {
                    case "store" -> new ColumnarStore(size, true, false);
                    case "store-nomask" -> new ColumnarStore(size, false, false);
                    case "null" -> new NullSink();
                    default -> throw new IllegalArgumentException("unknown sink " + sinkKind);
                };
                long t0 = System.nanoTime();
                ExportReader.Result res = ExportReader.read(file, sink, o);
                double s = (System.nanoTime() - t0) / 1e9;
                StringBuilder sb = new StringBuilder();
                sb.append(String.format("read %s: %d bytes, %d lines in %.3f s (%.0f MB/s), io=%s threads=%d%n",
                        file.getFileName(), res.bytes, res.lines, s, res.bytes / 1e6 / s, res.io, res.threads));
                sb.append(String.format("  names %d (max id %d, %s)  levels %d (max id %d, %s)  exprs %d (max id %d, %s)%n",
                        res.names, res.maxNameId, res.namesDense ? "dense" : "sparse",
                        res.levels, res.maxLevelId, res.levelsDense ? "dense" : "sparse",
                        res.exprs, res.maxExprId, res.exprsDense ? "dense" : "sparse"));
                sb.append(String.format("  general-path lines %d (%.3f %%), id tables needed: %s%n",
                        res.generalPathLines, 100.0 * res.generalPathLines / Math.max(1, res.lines),
                        res.tableFree ? "no" : "yes"));
                if (sink instanceof ColumnarStore st) {
                    sb.append(String.format("  store: %d names, %d levels, %d exprs, %d constants in %d declarations, %.0f MB of columns%n",
                            st.nameCount(), st.levelCount(), st.exprCount(), st.constCount(), st.declCount(),
                            st.memoryBytes() / 1e6));
                }
                System.out.print(sb);
            }
        } catch (ExportException e) {
            System.err.println(e.getMessage());
            System.exit(e.kind() == ExportException.Kind.MALFORMED ? 1 : 2);
        } catch (IOException | RuntimeException e) {
            System.err.println("error: " + e);
            System.exit(3);
        }
    }
}
