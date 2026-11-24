package rlvm.load.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import rlvm.export.ExportReader;
import rlvm.level.LevelStore;
import rlvm.load.ExportLoader;
import rlvm.load.LevelStoreInterner;
import rlvm.load.LoadOptions;
import rlvm.load.LoadReport;
import rlvm.load.LoadTarget;
import rlvm.load.Loader;
import rlvm.load.MalformedExportException;
import rlvm.load.NatStoreDecoder;
import rlvm.load.UnsupportedExportException;

/**
 * Loads an export and prints the load report: the command behind the loader's measurements and the
 * cross-check of its record counts against {@code rlvm.export.tools.ExportStats}.
 *
 * <pre>
 * LoadTool [options] FILE
 *   --target store|null     store (default): the real rlvm.store.Store; null: a target that stores nothing
 *                           (cost of reader + loader alone; no duplicate detection)
 *   --no-proof-blocks       everything goes to the shared region
 *   --no-flags              do not compute the SHARED / BODY / INVARIANT flags
 *   --io auto|mapped|buffered   the reader's input strategy (default auto)
 *   --repeat N              load N times (JIT warm-up studies); the report of every run is printed
 *   --quiet                 print one line per run instead of the report
 *   --pipelined             publish while reading (LoadOptions.pipelined; the store sits on a LevelChain)
 *   --publish-units N       hand-off interval in units (default 4096)
 *   --publish-mib N         hand-off interval in MiB of input (default 32)
 *   --scan-threads N        scanner threads of the reader (default 1; needs mapped input); with them the
 *                           runs of proof-private records are packed by those threads (LoadOptions.packedRuns)
 *   --no-packed-runs        scanner threads tokenise only
 *   --chunk-kib N           size of the chunk a scanner thread takes (default 4096)
 *   --followers N           with --pipelined: N threads that follow the frontier as a worker must (park
 *                           on the hand-off signal, take a private overlay of the newest level snapshot
 *                           whenever the frontier passes their view, read the new unit rows); measures
 *                           what following costs, not checking
 *   --strict-format         decline a header that announces a format version other than 3.x
 * </pre>
 * Exit status: 0 loaded, 1 malformed (reject), 2 unsupported (decline), 3 I/O or usage error.
 */
public final class LoadTool {
    private LoadTool() {}

    public static void main(String[] args) {
        ExportReader.Options ro = new ExportReader.Options();
        String target = "store";
        boolean proofBlocks = true;
        boolean flags = true;
        boolean quiet = false;
        int repeat = 1;
        boolean pipelined = false;
        int publishUnits = 4096;
        long publishMib = 32;
        int scanThreads = 1;
        boolean packedRuns = true;
        int followers = 0;
        boolean strict = false;
        Path file = null;
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--target" -> target = args[++i];
                    case "--no-proof-blocks" -> proofBlocks = false;
                    case "--no-flags" -> flags = false;
                    case "--quiet" -> quiet = true;
                    case "--io" -> ro.io = ExportReader.Io.valueOf(args[++i].toUpperCase(Locale.ROOT));
                    case "--repeat" -> repeat = Integer.parseInt(args[++i]);
                    case "--pipelined" -> pipelined = true;
                    case "--publish-units" -> publishUnits = Integer.parseInt(args[++i]);
                    case "--publish-mib" -> publishMib = Long.parseLong(args[++i]);
                    case "--scan-threads" -> scanThreads = Integer.parseInt(args[++i]);
                    case "--no-packed-runs" -> packedRuns = false;
                    case "--chunk-kib" -> ro.parallelChunkBytes = Integer.parseInt(args[++i]) << 10;
                    case "--followers" -> followers = Integer.parseInt(args[++i]);
                    case "--strict-format" -> strict = true;
                    default -> {
                        if (args[i].startsWith("--") || file != null) {
                            throw new IllegalArgumentException(args[i]);
                        }
                        file = Path.of(args[i]);
                    }
                }
            }
            if (file == null || !(target.equals("store") || target.equals("null"))) {
                throw new IllegalArgumentException("no file");
            }
            if (followers > 0 && !(pipelined && target.equals("store"))) {
                throw new IllegalArgumentException("--followers needs --pipelined and the store target");
            }
        } catch (RuntimeException e) {
            System.err.println("usage: LoadTool [--target store|null] [--no-proof-blocks] [--no-flags] "
                    + "[--io auto|mapped|buffered] [--repeat N] [--quiet] [--pipelined] [--publish-units N] "
                    + "[--publish-mib N] [--scan-threads N] [--no-packed-runs] [--chunk-kib N] [--followers N] "
                    + "[--strict-format] FILE");
            System.exit(3);
            return;
        }
        try {
            long size = Files.size(file);
            LoadOptions options = (pipelined ? LoadOptions.pipelined(size).withPublishEvery(publishUnits, publishMib << 20)
                    : LoadOptions.batch(size)).withProofBlocks(proofBlocks).withSharingFlags(flags)
                    .withScanThreads(scanThreads).withStrictFormatVersion(strict).withPackedRuns(packedRuns);
            ro.threads = options.scanThreads;
            ro.runs = ExportLoader.readerOptions(options).runs;
            for (int r = 0; r < repeat; r++) {
                Loaded l = target.equals("null") ? nullTarget(options) : StoreLoad.create(size, options, followers);
                LoadReport report;
                try {
                    report = ExportLoader.read(file, l.loader, ro);
                } finally {
                    l.close();
                }
                if (quiet) {
                    System.out.printf(Locale.ROOT, "loaded %s: %d bytes, %d lines, %d constants in %.3f s (%.0f MB/s)%s%n",
                            file.getFileName(), report.inputBytes, report.lines, report.constants,
                            report.wallNanos / 1e9, report.inputBytes / 1e6 / (report.wallNanos / 1e9), l.summary());
                } else {
                    System.out.print(report);
                    System.out.println(l.summary());
                }
            }
        } catch (MalformedExportException e) {
            System.err.println("malformed export, line " + e.line() + ": " + e.getMessage());
            System.exit(1);
        } catch (UnsupportedExportException e) {
            System.err.println("unsupported export: " + e.getMessage());
            System.exit(2);
        } catch (IOException | RuntimeException e) {
            System.err.println("error: " + e);
            System.exit(3);
        }
    }

    /** A loader and a one-line description of what its target holds afterwards. */
    abstract static class Loaded {
        final Loader loader;

        Loaded(Loader loader) {
            this.loader = loader;
        }

        abstract String summary();

        /** The load has ended (normally or not): stop whatever ran next to it. */
        void close() {
        }
    }

    private static Loaded nullTarget(LoadOptions options) {
        NullTarget t = new NullTarget();
        LevelStore levels = new LevelStore();
        Loader loader = new Loader((LoadTarget) t, options, new LevelStoreInterner(levels), new NatStoreDecoder());
        return new Loaded(loader) {
            @Override
            String summary() {
                return String.format(Locale.ROOT, "  null target: %,d single-referrer reports, %,d levels, %,d lists",
                        t.singles(), levels.size() - 1, levels.listCount());
            }
        };
    }
}
