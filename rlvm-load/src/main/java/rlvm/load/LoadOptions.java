package rlvm.load;

/** Options of the loader. Immutable; the {@code with...} methods return copies. */
public final class LoadOptions {

    /** How finished units become visible to the checking threads. */
    public enum Mode {
        /** Publish once, at end of file (levels frozen once). The simple, always-correct mode. */
        BATCH,
        /** Publish as units complete, so that checking overlaps loading (ARCHITECTURE.md 3.5). */
        PIPELINED
    }

    /** Default of {@link #maxSegmentRecords}: 2^26 records = 1 GiB of segment buffer. */
    public static final int DEFAULT_MAX_SEGMENT_RECORDS = 1 << 26;

    /**
     * Input bytes the loader assumes per record line when it turns {@link #publishEveryBytes} into a
     * number of lines (the sink interface has no byte positions; exporter output has 53.5 to 56.4
     * bytes per line).
     */
    public static final int BYTES_PER_LINE = 56;

    public final Mode mode;
    /** PIPELINED: publish after at least this many units ... */
    public final int publishEveryUnits;
    /**
     * ... or this many input bytes, whichever comes first. Counted in lines of {@link #BYTES_PER_LINE}
     * bytes; a publication happens only at the end of a declaration unit.
     */
    public final long publishEveryBytes;
    /**
     * Keep theorem and opaque values as proof blocks. False: their values are shared terms like a
     * definition's; records that no root of their segment reaches still go to (ownerless) blocks.
     */
    public final boolean proofBlocks;
    /** Size of the input in bytes, for presizing (0 = unknown). */
    public final long inputBytes;
    /** Compute the SHARED / BODY / INVARIANT node flags (RECONCILIATION section 3). Default true. */
    public final boolean sharingFlags;
    /**
     * Largest number of expression records between two declaration lines; a longer segment is declined
     * ({@link UnsupportedExportException}). The segment buffer holds 16 bytes per record.
     */
    public final int maxSegmentRecords;
    /**
     * Called on the loader thread after every publication (PIPELINED: each hand-off and the final
     * one; BATCH: the final one, and the partial one of {@link #publishOnDecline}). Null = nobody.
     */
    public final LoadListener listener;
    /**
     * When the load ends with a decline ({@link UnsupportedExportException}), first publish the units
     * that were completely loaded, so that the driver still checks them and the decline counts as the
     * status of the first unit that was not loaded (verdict = first non-accepted unit in file order).
     * The load is never complete then ({@code Store.loadComplete()} stays false: no ACCEPT). Always
     * on in PIPELINED mode; in BATCH mode off unless asked for, because the caller must be prepared
     * to create worker views after a failed load (freeze its level store).
     */
    public final boolean publishOnDecline;
    /**
     * Scanner threads of the reader ({@code rlvm.export.ExportReader.Options.threads}) that
     * {@link ExportLoader#readerOptions} asks for: 1 = everything on the loader thread; N &gt; 1 = N
     * helper threads tokenise chunks of a mapped file, the loader thread replays them in file order
     * (identical sink calls; wall clock only).
     */
    public final int scanThreads;
    /**
     * Decline a well-formed header that announces a format version whose major is not 3. Default
     * false: the official reader never looks at line 1, so the header decides nothing.
     */
    public final boolean strictFormatVersion;
    /**
     * Take the packed runs that the reader's scanner threads prepare ({@link Loader#exprRun}; they
     * exist only with scanner threads on mapped input). Default true. The store that is built, every
     * failure and every message are the same either way; false keeps the scanner threads to
     * tokenising, as before the parallel packing.
     */
    public final boolean packedRuns;

    public LoadOptions(Mode mode, int publishEveryUnits, long publishEveryBytes, boolean proofBlocks,
            long inputBytes) {
        this(mode, publishEveryUnits, publishEveryBytes, proofBlocks, inputBytes, true, DEFAULT_MAX_SEGMENT_RECORDS,
                null, mode == Mode.PIPELINED, 1, false, true);
    }

    private LoadOptions(Mode mode, int publishEveryUnits, long publishEveryBytes, boolean proofBlocks,
            long inputBytes, boolean sharingFlags, int maxSegmentRecords, LoadListener listener,
            boolean publishOnDecline, int scanThreads, boolean strictFormatVersion, boolean packedRuns) {
        if (mode == null) {
            throw new NullPointerException("mode");
        }
        if (publishEveryUnits < 1 || publishEveryBytes < 1) {
            throw new IllegalArgumentException("publication intervals must be positive");
        }
        this.mode = mode;
        this.publishEveryUnits = publishEveryUnits;
        this.publishEveryBytes = publishEveryBytes;
        this.proofBlocks = proofBlocks;
        this.inputBytes = inputBytes;
        this.sharingFlags = sharingFlags;
        this.maxSegmentRecords = maxSegmentRecords;
        this.listener = listener;
        this.publishOnDecline = publishOnDecline || mode == Mode.PIPELINED;
        this.scanThreads = scanThreads;
        this.strictFormatVersion = strictFormatVersion;
        this.packedRuns = packedRuns;
    }

    public static LoadOptions batch(long inputBytes) {
        return new LoadOptions(Mode.BATCH, Integer.MAX_VALUE, Long.MAX_VALUE, true, inputBytes);
    }

    /** Pipelined loading with the default hand-off interval: 4,096 units or 32 MiB of input. */
    public static LoadOptions pipelined(long inputBytes) {
        return new LoadOptions(Mode.PIPELINED, 4096, 32L << 20, true, inputBytes);
    }

    private LoadOptions copy(Mode mode, int units, long bytes, boolean proofBlocks, boolean sharingFlags,
            int maxSegmentRecords, LoadListener listener, boolean publishOnDecline, int scanThreads,
            boolean strictFormatVersion) {
        return new LoadOptions(mode, units, bytes, proofBlocks, inputBytes, sharingFlags, maxSegmentRecords, listener,
                publishOnDecline, scanThreads, strictFormatVersion, packedRuns);
    }

    /** A copy in another mode; BATCH resets the publication intervals, PIPELINED keeps or defaults them. */
    public LoadOptions withMode(Mode value) {
        if (value == mode) {
            return this;
        }
        boolean batch = value == Mode.BATCH;
        return copy(value, batch ? Integer.MAX_VALUE : 4096, batch ? Long.MAX_VALUE : 32L << 20, proofBlocks,
                sharingFlags, maxSegmentRecords, listener, publishOnDecline, scanThreads, strictFormatVersion);
    }

    /** A copy that publishes after {@code units} units or {@code bytes} input bytes, whichever comes first. */
    public LoadOptions withPublishEvery(int units, long bytes) {
        return copy(mode, units, bytes, proofBlocks, sharingFlags, maxSegmentRecords, listener, publishOnDecline,
                scanThreads, strictFormatVersion);
    }

    /** A copy with {@link #proofBlocks} set ({@code -X no-proof-blocks}). */
    public LoadOptions withProofBlocks(boolean value) {
        return copy(mode, publishEveryUnits, publishEveryBytes, value, sharingFlags, maxSegmentRecords, listener,
                publishOnDecline, scanThreads, strictFormatVersion);
    }

    public LoadOptions withSharingFlags(boolean value) {
        return copy(mode, publishEveryUnits, publishEveryBytes, proofBlocks, value, maxSegmentRecords, listener,
                publishOnDecline, scanThreads, strictFormatVersion);
    }

    public LoadOptions withMaxSegmentRecords(int value) {
        if (value < 1 || value > (1 << 29) - 1) {
            throw new IllegalArgumentException("maxSegmentRecords out of range: " + value);
        }
        return copy(mode, publishEveryUnits, publishEveryBytes, proofBlocks, sharingFlags, value, listener,
                publishOnDecline, scanThreads, strictFormatVersion);
    }

    /** A copy with {@link #listener} set (null removes it). */
    public LoadOptions withListener(LoadListener value) {
        return copy(mode, publishEveryUnits, publishEveryBytes, proofBlocks, sharingFlags, maxSegmentRecords, value,
                publishOnDecline, scanThreads, strictFormatVersion);
    }

    /** A copy with {@link #publishOnDecline} set (PIPELINED mode always publishes). */
    public LoadOptions withPublishOnDecline(boolean value) {
        return copy(mode, publishEveryUnits, publishEveryBytes, proofBlocks, sharingFlags, maxSegmentRecords, listener,
                value, scanThreads, strictFormatVersion);
    }

    /** A copy with {@link #scanThreads} set ({@code -X scan-threads=N}); values below 1 mean 1. */
    public LoadOptions withScanThreads(int value) {
        return copy(mode, publishEveryUnits, publishEveryBytes, proofBlocks, sharingFlags, maxSegmentRecords, listener,
                publishOnDecline, Math.max(1, value), strictFormatVersion);
    }

    /** A copy with {@link #packedRuns} set. */
    public LoadOptions withPackedRuns(boolean value) {
        return new LoadOptions(mode, publishEveryUnits, publishEveryBytes, proofBlocks, inputBytes, sharingFlags,
                maxSegmentRecords, listener, publishOnDecline, scanThreads, strictFormatVersion, value);
    }

    /** A copy with {@link #strictFormatVersion} set. */
    public LoadOptions withStrictFormatVersion(boolean value) {
        return copy(mode, publishEveryUnits, publishEveryBytes, proofBlocks, sharingFlags, maxSegmentRecords, listener,
                publishOnDecline, scanThreads, value);
    }
}
