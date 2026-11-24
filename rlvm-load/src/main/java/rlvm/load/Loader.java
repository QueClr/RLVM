package rlvm.load;

import java.util.Arrays;

import rlvm.export.ExportReader;
import rlvm.export.ExportSink;
import rlvm.store.DeclKind;
import rlvm.store.Kind;
import rlvm.store.Store;

/**
 * Builds the shared {@link Store} from the record stream. One instance per run, driven by one thread
 * (the loader thread), which calls the {@link ExportSink} methods in file order and finally
 * {@link #finish()}.
 *
 * <p>What it does (ARCHITECTURE.md 3.2 - 3.5):
 * <ul>
 *   <li>names: structural interning in the {@code NameTable}; returns the store name id;
 *   <li>levels: interning through {@link LevelInterner}; returns the level id;
 *   <li>expressions: appended to the segment buffer of the declaration being read; returns the record
 *       ordinal (1, 2, 3, ...). The location table {@code loc[ordinal]} later says where the record
 *       went: a shared term id (positive), or a global proof-block record number ({@code -(record+1)});
 *   <li>declaration: claim the name(s); split the segment into shared terms and one proof block
 *       (theorem and opaque values); write the declaration rows.
 * </ul>
 *
 * <h2>The segment</h2>
 * The segment is the run of expression records since the last admitted declaration: 4 ints per record
 * (word 0: kind, a saturating 2-bit referrer counter, the BODY and MARK bits; words 1..3: payload).
 * A child is stored as its 1-based index in the segment (positive) or, when it is an earlier record,
 * as a PENDING reference (its ordinal) whose slot is remembered in a list. At the declaration line,
 * after the names of the unit have been claimed, the pending references are resolved in one tight
 * loop: the location of the earlier record is read (the misses of that loop overlap, which they
 * cannot while records are being scanned), a record in the shared region gives minus its shared term
 * id, a record that still sits in a proof block is promoted first. So the sweep never reads the
 * location table for records outside the segment, promotion never interleaves with the writing of a
 * block, and a promoted CONST / PROJ binds to the names of the unit that needs it.
 *
 * <h2>The split</h2>
 * At a declaration line the records of the segment that the unit's roots reach become shared nodes;
 * all the others go to one proof block. The roots are: the type; the value of a definition; the member
 * types and rule right-hand sides of an inductive block. A theorem's or opaque's value is not a root
 * (unless proof blocks are switched off): what only it reaches is the proof block, and the value is
 * that block. For the other kinds the block, if any, is ownerless: it holds records no root reached
 * (records of a later declaration that the file wrote early), which are promoted when they are used.
 *
 * <h2>Packed runs</h2>
 * In the reader's parallel mode the scanner threads offer stretches of consecutive app / lam /
 * forallE / letE records in packed form ({@link #exprRun}, {@link #RUN_FORMAT}). A run occupies its
 * records' places in the segment without being written there. At the declaration line a run all of
 * whose locally encoded children lie above the type root of a theorem or opaque (and inside the
 * segment) is LINKED: its records are proof-private, so its packed bytes, completed by the
 * references only this thread can resolve, are the bytes of the block records. Any other run is
 * EXPANDED into the segment records its calls would have written. Either way the store, the
 * location of every record and the order of the promotions are those of the record-by-record path.
 *
 * <p>Throws {@link MalformedExportException} (the official reader would reject: here only a
 * declaration name declared twice; the reader reports the rest) and
 * {@link UnsupportedExportException} (RLVM declines).
 */
public final class Loader implements ExportSink, RecordSink {

    // ---- segment word 0 --------------------------------------------------------------------------
    private static final int K_MASK = 0xF;
    /** Saturating 2-bit referrer counter (bits 4..5). */
    private static final int RC_ONE = 1 << 4;
    private static final int RC_MASK = 3 << 4;
    /** Counter value 2 or 3. Shifted left by 8 it is {@link LoadTarget#F_SHARED}. */
    private static final int RC_TWO_PLUS = 1 << 5;
    /** Body of a binder or root of a declaration. Shifted left by 8 it is {@link LoadTarget#F_BODY}. */
    private static final int S_BODY = 1 << 6;
    /** Reachable from the type root of the declaration being split: goes to the shared region. */
    private static final int S_MARK = 1 << 7;
    /** NATLIT whose word 1 is a literal-pool index. */
    private static final int S_BIG = 1 << 8;
    private static final int FLAG_SHIFT = 8;
    /**
     * Child encoding of an earlier record until the declaration line resolves it:
     * {@code -(PENDING + ordinal)}. Shared ids are below 2^28, so everything at or below
     * {@code -PENDING} is pending.
     */
    private static final int PENDING = 1 << 28;

    /** Ordinals are ints; a segment (at most 2^29 - 1 records) must still fit above this. */
    private static final int MAX_EXPR_RECORDS = Integer.MAX_VALUE - (1 << 29) - 16;

    /**
     * The packed form in which this loader takes runs ({@link #exprRun}): the tags are the node kinds
     * of the store, so the bytes of a child encoded by a scanner thread are the bytes of a
     * block-local reference of {@code rlvm.store.ProofBlocks}. Runs shorter than four records are not
     * worth the bookkeeping.
     */
    public static final ExportReader.RunFormat RUN_FORMAT =
            new ExportReader.RunFormat(Kind.APP, Kind.LAM, Kind.PI, Kind.LET, 4);

    // ---- runs of the open segment: RUN_WORDS ints each ---------------------------------------------
    /** 0-based segment index of the first record. */
    private static final int R_FIRST = 0;
    private static final int R_COUNT = 1;
    /** Ordinal: every child encoded in the packed bytes has at least this ordinal. */
    private static final int R_MIN_LOCAL = 2;
    private static final int R_PACKED_OFF = 3;
    private static final int R_PACKED_LEN = 4;
    /** Offset of the open children (two ints each: place and body bit, then the child) and their number. */
    private static final int R_OPEN_OFF = 5;
    private static final int R_OPEN_COUNT = 6;
    /** {@link #RUN_PENDING}, {@link #RUN_LINKED} or {@link #RUN_EXPANDED}; bit {@link #RUN_OWNED}. */
    private static final int R_STATE = 7;
    /** Ordinal minus file id of the open children. */
    private static final int R_DELTA = 8;
    private static final int R_LENGTHS_OFF = 9;
    /** Largest shared id that a scanner thread wrote into the record bytes (0: none). */
    private static final int R_MAX_EARLY = 10;
    private static final int RUN_WORDS = 11;
    private static final int RUN_PENDING = 0;
    private static final int RUN_LINKED = 1;
    private static final int RUN_EXPANDED = 2;
    private static final int RUN_STATE_MASK = 3;
    /** The arrays of the run are this loader's own copies (set by {@link #exprRunsEnd()}). */
    private static final int RUN_OWNED = 4;

    private final LoadTarget out;
    private final LoadOptions options;
    private final LevelInterner levels;
    private final NatDecoder nats;
    private final NatDecoder.Limbs limbs = new NatDecoder.Limbs();
    /** Universe parameters with two level records (finding A2-F1). */
    private final DoubledParams paramRecords = new DoubledParams();
    private final LoadReport report = new LoadReport();
    private final boolean proofBlocks;
    /** {@code S_BODY | RC_TWO_PLUS} if sharing flags are computed, else 0. */
    private final int flagMask;
    /** Whether references are counted (false: the target derives the sharing flags itself). */
    private final boolean counting;
    private final int maxSegmentInts;

    /** Number of expression ordinals issued (ordinals are 1 .. exprCount). */
    private int exprCount;
    /** Location of every record of a finished segment: shared id (&gt; 0) or -(global block record + 1). */
    private int[] loc;
    /** The segment buffer, 4 ints per record. */
    private int[] seg;
    /** Ordinal of the last record before the segment. */
    private int segBase;
    /** Records in the segment. */
    private int segLen;
    /** Segment slots (indices into {@link #seg}) that hold a PENDING reference. */
    private int[] pend = new int[256];
    private int pendCount;
    /** Whether packed runs are taken (proof blocks on, no referrer counting). */
    private final boolean takeRuns;
    /** The runs of the open segment in arrival order ({@link #RUN_WORDS} ints each) and their arrays. */
    private int[] runs = new int[RUN_WORDS * 8];
    private byte[][] runPacked = new byte[8][];
    private byte[][] runLengths = new byte[8][];
    private int[][] runOpen = new int[8][];
    private int runCount;
    /**
     * What the scanner threads may read ({@link ExportReader.EarlyRefs}): the location table and the
     * difference between ordinal and file id, published once the first run was taken. A scanner
     * thread encodes a reference to an earlier record whose location it finds POSITIVE there (a
     * shared term id: such a location never changes again) into the record bytes; everything else
     * stays open and is resolved here, in file order.
     */
    private volatile int[] earlyTable;
    private volatile int earlyDelta;
    private final ExportReader.EarlyRefs early = new ExportReader.EarlyRefs() {
        @Override
        public int[] table() {
            return earlyTable;
        }

        @Override
        public int delta() {
            return earlyDelta;
        }
    };
    /**
     * One bit per expression ordinal: the record is wrapped by, or contains, a metadata node. Null until
     * the first {@code mdata} record (lean4export writes none unless asked to), so files without
     * metadata pay one null test per record. Metadata is dropped (ARCHITECTURE.md 2.1), which is
     * invisible to type checking but not to the syntactic checks and structural comparisons of
     * inductive and quot records: such a record is declined when a root of it carries the bit.
     */
    private long[] mdata;

    // ---- counters (copied into the report on demand) -------------------------------------------------
    private long nNameStr;
    private long nNameNum;
    private long nLevelSucc;
    private long nLevelMax;
    private long nLevelIMax;
    private long nLevelParam;
    private long nBVar;
    private long nSort;
    private long nConst;
    private long nApp;
    private long nLam;
    private long nPi;
    private long nLet;
    private long nProj;
    private long nNat;
    private long nNatBig;
    private long nStr;
    private long nMData;
    private long nAxiom;
    private long nDef;
    private long nThm;
    private long nOpaque;
    private long nQuot;
    private long nInductive;
    private long nInductTypes;
    private long nInductCtors;
    private long nInductRecs;
    private long nRecRules;
    private long nConstants;
    private long nUnits;
    private long nSharedWritten;
    private long nBlockNodes;
    private long nBlocks;
    private long nBlockValues;
    private long nPromotions;
    private long nDropped;
    private long nRuns;
    private long nRunRecords;
    private long nRunsExpanded;
    private long nRunEarly;
    private long nUnresolved;
    private int largestSegment;
    private boolean finished;

    // ---- publication (ARCHITECTURE.md 3.5) -----------------------------------------------------------
    /** Publish while reading ({@link LoadOptions.Mode#PIPELINED}). */
    private final boolean pipelined;
    private final int publishEveryUnits;
    /** {@link LoadOptions#publishEveryBytes} in lines. */
    private final long publishEveryLines;
    private final LoadListener listener;
    /** Units of the target: admitted declarations plus one per dropped record. */
    private int storeUnits;
    /** {@link #storeUnits} and {@link #line()} at the last publication. */
    private int unitsAtPublish;
    private long lineAtPublish;
    private long nPublications;
    private boolean attached;

    /** Store name ids of the anonymous name and of {@code Quot}, {@code Quot.mk}, {@code Quot.lift}, {@code Quot.ind}; -1 = not seen. */
    private int anonymous;
    private int quotName = -1;
    private int quotMk = -1;
    private int quotLift = -1;
    private int quotInd = -1;

    // ---- scratch of the inductive block being read ---------------------------------------------------
    private boolean inBlock;
    private int annTypes;
    private int annCtors;
    private int annRecs;
    private int gotTypes;
    private int gotCtors;
    private int gotRecs;
    /** Members in arrival order (types, constructors, recursors). */
    private int members;
    private int[] mName = new int[16];
    private int[] mLparams = new int[16];
    /** Type root: the ordinal, then the segment encoding, then the shared id. */
    private int[] mType = new int[16];
    private int[] mFlags = new int[16];
    /** Types: all list. Constructors: induct name. Recursors: all list. */
    private int[] mA = new int[16];
    /** Types: ctors list. Recursors: index of the first rule. */
    private int[] mB = new int[16];
    /** Recursors: announced number of rules. */
    private int[] mRules = new int[16];
    /** Four numbers per member (see {@link #inductType}, {@link #inductCtor}, {@link #inductRec}). */
    private long[] mNum = new long[64];
    private int rules;
    private int rulesExpected;
    private int[] ruleCtor = new int[16];
    private int[] ruleRhs = new int[16];
    private long[] ruleFields = new long[16];
    private int[] blob = new int[256];

    /**
     * The production constructor: writes into {@code store}. With
     * {@link LoadOptions.Mode#PIPELINED} the store must have been created over the list store and the
     * level view of the {@code levels} given here (a {@link LevelChain}: {@code new Store(bytes,
     * chain.lists(), chain.info())}), because the store's own writer interns lists while it derives
     * node summaries.
     */
    public Loader(Store store, LoadOptions options, LevelInterner levels, NatDecoder nats) {
        this(new StoreTarget(store), options, levels, nats);
    }

    /** Writes into any {@link LoadTarget} (tests, tools). */
    public Loader(LoadTarget target, LoadOptions options, LevelInterner levels, NatDecoder nats) {
        if (target == null || options == null || levels == null || nats == null) {
            throw new NullPointerException();
        }
        this.pipelined = options.mode == LoadOptions.Mode.PIPELINED;
        this.publishEveryUnits = options.publishEveryUnits;
        this.publishEveryLines = Math.max(1, options.publishEveryBytes / LoadOptions.BYTES_PER_LINE);
        this.listener = options.listener;
        this.out = target;
        this.options = options;
        this.levels = levels;
        this.nats = nats;
        this.proofBlocks = options.proofBlocks;
        this.counting = options.sharingFlags && target.wantsSharingFlags();
        this.flagMask = counting ? (S_BODY | RC_TWO_PLUS) : 0;
        this.takeRuns = options.packedRuns && options.proofBlocks && !counting;
        this.maxSegmentInts = options.maxSegmentRecords << 2;
        long expected = options.inputBytes / 56 + 1024;
        this.loc = new int[(int) Math.min(expected, 1 << 28)];
        this.seg = new int[1 << 12];
        report.inputBytes = options.inputBytes;
    }

    // ============================================================================================
    // life cycle
    // ============================================================================================

    /** The options this loader was created with. */
    public LoadOptions options() {
        return options;
    }

    /** End of file reached without a syntax error: flushes, checks completeness, final publication. */
    public void finish() {
        if (finished) {
            throw new IllegalStateException("finish() called twice");
        }
        if (inBlock) {
            throw new IllegalStateException("end of input inside an inductive block");
        }
        int units = checkedUnits();
        levels.finish(units);
        out.finish();
        finished = true;
        unitsAtPublish = units;
        nPublications++;
        if (listener != null) {
            listener.published(units, true);
        }
    }

    /** The loader's count of units against the target's (when it has one). */
    private int checkedUnits() {
        int units = storeUnits;
        int stored = out.unitCount();
        if (stored >= 0 && stored != units) {
            throw new IllegalStateException("the loader counted " + units + " units, the store holds " + stored);
        }
        return units;
    }

    /** A hand-off: everything up to the unit that just ended becomes readable by other threads. */
    private void publishUnits() {
        int units = checkedUnits();
        levels.publish(units);
        out.publish();
        unitsAtPublish = units;
        lineAtPublish = line();
        nPublications++;
        if (listener != null) {
            listener.published(units, false);
        }
    }

    /** A unit of the target is complete (an admitted declaration, or a dropped record). */
    private void unitDone() {
        storeUnits++;
        if (pipelined && (storeUnits - unitsAtPublish >= publishEveryUnits
                || line() - lineAtPublish >= publishEveryLines)) {
            publishUnits();
        }
    }

    /**
     * For a load that ends with a decline: publishes the units that are completely loaded, without
     * completing the load ({@code Store.loadComplete()} stays false, so the run can never be
     * accepted). The driver then still checks those units, and the decline is the status of the first
     * unit that was not loaded, as the verdict rule wants (first non-accepted unit in file order).
     * Does nothing unless {@link LoadOptions#publishOnDecline} is set, or after {@link #finish()}.
     * {@link ExportLoader} calls it before it lets an {@link UnsupportedExportException} out; never
     * call it for a rejection ({@link MalformedExportException}: the official reader fails on the
     * whole file before it checks anything).
     *
     * @return true if units {@code [0, n)} are published now, n being the number of complete units
     */
    public boolean publishCompleted() {
        if (finished || !options.publishOnDecline) {
            return false;
        }
        if (storeUnits != unitsAtPublish || nPublications == 0) {
            publishUnits();
        }
        return true;
    }

    /** Statistics and the final state; valid after {@link #finish()} or after an exception. */
    public LoadReport report() {
        LoadReport r = report;
        r.nameStrRecords = nNameStr;
        r.nameNumRecords = nNameNum;
        r.nameRecords = nNameStr + nNameNum;
        r.levelSuccRecords = nLevelSucc;
        r.levelMaxRecords = nLevelMax;
        r.levelIMaxRecords = nLevelIMax;
        r.levelParamRecords = nLevelParam;
        r.levelRecords = nLevelSucc + nLevelMax + nLevelIMax + nLevelParam;
        r.exprBVar = nBVar;
        r.exprSort = nSort;
        r.exprConst = nConst;
        r.exprApp = nApp;
        r.exprLam = nLam;
        r.exprForall = nPi;
        r.exprLet = nLet;
        r.exprProj = nProj;
        r.exprNatLit = nNat;
        r.exprStrLit = nStr;
        r.exprMData = nMData;
        r.exprRecords = exprCount + nMData;
        r.bigNatLits = nNatBig;
        r.axiomRecords = nAxiom;
        r.defRecords = nDef;
        r.thmRecords = nThm;
        r.opaqueRecords = nOpaque;
        r.quotRecords = nQuot;
        r.inductiveRecords = nInductive;
        r.declRecords = declRecords();
        r.inductTypes = nInductTypes;
        r.inductCtors = nInductCtors;
        r.inductRecs = nInductRecs;
        r.recRules = nRecRules;
        r.constants = nConstants;
        r.units = nUnits;
        r.sharedNodes = nSharedWritten;
        r.blockNodes = nBlockNodes;
        r.blocks = nBlocks;
        r.blockValues = nBlockValues;
        r.blockBytes = out.blockBytes();
        r.promotions = nPromotions;
        r.packedRuns = nRuns;
        r.packedRunRecords = nRunRecords;
        r.expandedRuns = nRunsExpanded;
        r.packedEarlyRefs = nRunEarly;
        r.promotedNodes = out.sharedNodes() - nSharedWritten;
        r.droppedUnsafe = nDropped;
        r.unresolvedRefs = out.unresolvedRefs();
        r.unresolvedBlockRefs = out.unresolvedBlockRefs();
        r.trailingExprRecords = segLen;
        r.largestSegment = Math.max(largestSegment, segLen);
        r.publications = nPublications;
        r.publishedUnits = unitsAtPublish;
        r.complete = finished;
        return r;
    }

    /** Number of expression ordinals issued so far. */
    public int exprCount() {
        return exprCount;
    }

    /**
     * Where expression record {@code ordinal} went: a shared term id (&gt; 0), {@code -(global block
     * record + 1)}, or 0 while the record is still in the segment buffer (its declaration line has not
     * been read, or was dropped).
     */
    public int locationOf(int ordinal) {
        if (ordinal < 1 || ordinal > exprCount) {
            throw new IllegalArgumentException("no expression record " + ordinal);
        }
        return ordinal > segBase ? 0 : loc[ordinal];
    }

    private long declRecords() {
        return nAxiom + nDef + nThm + nOpaque + nQuot + nInductive;
    }

    /** 1-based number of the line of the record being processed (line 1 is the header). */
    private long line() {
        return 2 + nNameStr + nNameNum + nLevelSucc + nLevelMax + nLevelIMax + nLevelParam + exprCount + nMData
                + declRecords();
    }

    private MalformedExportException duplicate(int name) {
        // the declaration line has been counted already
        return new MalformedExportException("Duplicate declaration: " + out.nameToString(name), line() - 1);
    }

    /** For failures in an expression record (the record has not been counted yet). */
    private UnsupportedExportException unsupported(String what) {
        return new UnsupportedExportException(what + " (line " + line() + ")");
    }

    /** For failures in a declaration record (the line has been counted already). */
    private UnsupportedExportException unsupportedDecl(String what) {
        return new UnsupportedExportException(what + " (line " + (line() - 1) + ")");
    }

    // ============================================================================================
    // header, names, levels
    // ============================================================================================

    @Override
    public void meta(boolean wellFormed, String exporterName, String exporterVersion, String formatVersion,
            String leanGithash, String leanVersion) {
        LoadReport r = report;
        r.metaWellFormed = wellFormed;
        r.exporterName = exporterName;
        r.exporterVersion = exporterVersion;
        r.formatVersion = formatVersion;
        r.leanGithash = leanGithash;
        r.leanVersion = leanVersion;
        r.formatVersionSupported = !(wellFormed && formatVersion != null && !isMajorVersion3(formatVersion));
        attach();
        // The official reader never looks at line 1, so the header decides nothing: a file whose
        // header announces another format generation is read like any other (and rejected or
        // declined by what its records say). LoadOptions.strictFormatVersion restores the decline.
        if (options.strictFormatVersion && !r.formatVersionSupported) {
            throw new UnsupportedExportException("export format version " + formatVersion + " (supported: 3.x)");
        }
    }

    /** The calling thread is the loader thread (first record of the file). */
    private void attach() {
        if (!attached) {
            attached = true;
            levels.attach();
        }
    }

    private static boolean isMajorVersion3(String version) {
        int n = version.length();
        return n >= 1 && version.charAt(0) == '3' && (n == 1 || version.charAt(1) == '.');
    }

    @Override
    public int nameAnonymous() {
        anonymous = out.nameAnonymous();
        return anonymous;
    }

    @Override
    public int nameStr(int prefix, byte[] utf8, int off, int len) {
        int id = out.nameStr(prefix, utf8, off, len);
        nNameStr++;
        if (len >= 2 && len <= 4) {
            noteQuotName(prefix, utf8, off, len, id);
        }
        return id;
    }

    /** Remembers the ids of the four quotient names (see {@link #drop}). */
    private void noteQuotName(int prefix, byte[] b, int off, int len, int id) {
        if (prefix == anonymous) {
            if (len == 4 && b[off] == 'Q' && b[off + 1] == 'u' && b[off + 2] == 'o' && b[off + 3] == 't') {
                quotName = id;
            }
        } else if (prefix == quotName) {
            if (len == 2 && b[off] == 'm' && b[off + 1] == 'k') {
                quotMk = id;
            } else if (len == 4 && b[off] == 'l' && b[off + 1] == 'i' && b[off + 2] == 'f' && b[off + 3] == 't') {
                quotLift = id;
            } else if (len == 3 && b[off] == 'i' && b[off + 1] == 'n' && b[off + 2] == 'd') {
                quotInd = id;
            }
        }
    }

    @Override
    public int nameNum(int prefix, long value) {
        int id = out.nameNum(prefix, value);
        nNameNum++;
        return id;
    }

    @Override
    public int nameNumBig(int prefix, byte[] digits, int off, int len) {
        // the reader sends everything from 2^63 here; values below 2^64 are still one unsigned long
        int id;
        if (fitsUnsignedLong(digits, off, len)) {
            id = out.nameNum(prefix, parseUnsignedLong(digits, off, len));
        } else {
            id = out.nameNumBig(prefix, digits, off, len);
        }
        nNameNum++;
        return id;
    }

    @Override
    public int levelZero() {
        return levels.zero();
    }

    @Override
    public int levelSucc(int level) {
        int id = levels.succ(level);
        levelRecord(id);
        nLevelSucc++;
        return id;
    }

    @Override
    public int levelMax(int lhs, int rhs) {
        int id = levels.max(lhs, rhs);
        levelRecord(id);
        nLevelMax++;
        return id;
    }

    @Override
    public int levelIMax(int lhs, int rhs) {
        int id = levels.imax(lhs, rhs);
        levelRecord(id);
        nLevelIMax++;
        return id;
    }

    @Override
    public int levelParam(int name) {
        int id = levels.param(name);
        // Finding A2-F1: a second level record for one universe parameter (see DoubledParams).
        rlvm.level.LevelStore run = levels.structure();
        if (run != null) {
            String why = paramRecords.param(run, name, id);
            if (why != null) {
                throw unsupported(why);
            }
        }
        nLevelParam++;
        return id;
    }

    /** A succ / max / imax record gave level {@code id} (the record has not been counted yet). */
    private void levelRecord(int id) {
        DoubledParams p = paramRecords;
        if (!p.active()) {
            p.note(id);
            return;
        }
        rlvm.level.LevelStore run = levels.structure();
        if (run != null) {
            String why = p.node(run, id);
            if (why != null) {
                throw unsupported(why);
            }
        }
    }

    // ============================================================================================
    // expressions: append to the segment
    // ============================================================================================

    /** Offset of the next segment record; grows the buffer. */
    private int nextSlot() {
        int p = segLen << 2;
        if (p + 4 > seg.length) {
            growSegment(p);
        }
        return p;
    }

    private void growSegment(int used) {
        if (used >= maxSegmentInts) {
            throw unsupported("more than " + options.maxSegmentRecords
                    + " expression records between two declarations (LoadOptions.maxSegmentRecords)");
        }
        long want = Math.min(Math.max((long) seg.length * 2, 1 << 12), maxSegmentInts);
        seg = Arrays.copyOf(seg, (int) Math.max(want, used + 4));
    }

    /**
     * Encodes a reference to record {@code ordinal}, made by the record being appended, for segment slot
     * {@code slot}, and counts it: the 1-based index in the segment, or a PENDING reference to an
     * earlier record (resolved by {@link #resolvePending()} at the declaration line).
     */
    private int child(int ordinal, int slot) {
        int local = ordinal - segBase;
        if (local > 0) {
            if (ordinal > exprCount) {
                throw badReference(ordinal);
            }
            if (counting) {
                int i = (local - 1) << 2;
                int w = seg[i];
                if ((w & RC_MASK) != RC_MASK) {
                    seg[i] = w + RC_ONE;
                }
            }
            return local;
        }
        if (ordinal <= 0) {
            throw badReference(ordinal);
        }
        // An earlier record: its location is not read here. loc[] is far larger than the caches and
        // the next record cannot be scanned before this load has been issued, so one miss at a time
        // would be outstanding; resolvePending() reads the locations of all such references of the
        // segment in one tight loop, where the misses overlap. Nothing moves in between: locations of
        // earlier records change only by promotion, which happens in that loop and after it.
        int n = pendCount;
        if (n == pend.length) {
            pend = Arrays.copyOf(pend, n * 2);
        }
        pend[n] = slot;
        pendCount = n + 1;
        return -(PENDING + ordinal);
    }

    /** As {@link #child}, for a binder body. */
    private int bodyChild(int ordinal, int slot) {
        int enc = child(ordinal, slot);
        if (enc > 0 && counting) {
            seg[(enc - 1) << 2] |= S_BODY;
        }
        return enc;
    }

    /**
     * Encodes a reference from a root of the declaration being written (its names are claimed): as
     * {@link #child}, but an earlier record in a proof block is promoted at once.
     */
    private int rootRef(int ordinal) {
        if (ordinal < 1 || ordinal > exprCount) {
            throw badReference(ordinal);
        }
        int local = ordinal - segBase;
        if (local > 0) {
            if (counting) {
                int i = (local - 1) << 2;
                int w = seg[i];
                seg[i] = ((w & RC_MASK) != RC_MASK ? w + RC_ONE : w) | S_BODY;
            }
            return local;
        }
        int l = loc[ordinal];
        if (l < 0) {
            l = promote(ordinal, l);
        }
        return -l;
    }

    /**
     * Resolves the references of the segment to earlier records (all of them are PENDING until here):
     * a record in the shared region gives its id, a record that still sits in a proof block is
     * promoted first. Called once the names of the unit are claimed.
     */
    private void resolvePending() {
        final int[] s = seg;
        final int[] pd = pend;
        for (int i = 0, n = pendCount; i < n; i++) {
            int slot = pd[i];
            if (slot < 0) {
                resolveRun(-slot - 1); // a run stands here in the order of arrival
                continue;
            }
            int ordinal = -s[slot] - PENDING;
            int l = loc[ordinal];
            if (l < 0) {
                l = promote(ordinal, l);
            }
            s[slot] = -l;
        }
        pendCount = 0;
    }

    // ============================================================================================
    // packed runs
    // ============================================================================================

    /**
     * The packed form in which this loader takes runs, with its table of finished records for the
     * scanner threads; null if it takes none (no proof blocks, referrer counting, switched off):
     * the scanner threads then tokenise only.
     */
    public ExportReader.RunFormat runFormat() {
        return takeRuns ? RUN_FORMAT.withEarly(early) : null;
    }

    /**
     * A run of {@code count} app / lam / forallE / letE records in the packed form of
     * {@link #RUN_FORMAT} (see {@link ExportSink#exprRun}). Taken unless something makes the
     * record-by-record path necessary: then the reader delivers the records one by one, and what
     * that path does (also where it fails) is by definition what this loader does.
     */
    @Override
    public int exprRun(int firstFileId, int count, int delta, int minLocal, byte[] packed, int off, int len,
            byte[] lengths, int lengthsOff, int[] open, int openOff, int openCount, int numLam, int numPi, int numLet,
            int numEarly, int maxEarly) {
        if (!takeRuns || mdata != null || count <= 0 || firstFileId + delta != exprCount + 1
                || exprCount > MAX_EXPR_RECORDS - count) {
            return ExportSink.RUN_REFUSED;
        }
        long ints = ((long) segLen + count) << 2;
        if (ints > maxSegmentInts) {
            return ExportSink.RUN_REFUSED; // the record that exceeds the segment limit is found one by one
        }
        if (ints > seg.length) {
            seg = Arrays.copyOf(seg, (int) Math.min(Math.max((long) seg.length * 2, ints), maxSegmentInts));
        }
        int r = runCount;
        if (r == runPacked.length) {
            runs = Arrays.copyOf(runs, r * 2 * RUN_WORDS);
            runPacked = Arrays.copyOf(runPacked, r * 2);
            runLengths = Arrays.copyOf(runLengths, r * 2);
            runOpen = Arrays.copyOf(runOpen, r * 2);
        }
        int q = r * RUN_WORDS;
        runs[q + R_FIRST] = segLen;
        runs[q + R_COUNT] = count;
        runs[q + R_MIN_LOCAL] = minLocal + delta;
        runs[q + R_PACKED_OFF] = off;
        runs[q + R_PACKED_LEN] = len;
        runs[q + R_OPEN_OFF] = openOff;
        runs[q + R_OPEN_COUNT] = openCount;
        runs[q + R_STATE] = RUN_PENDING;
        runs[q + R_DELTA] = delta;
        runs[q + R_LENGTHS_OFF] = lengthsOff;
        runs[q + R_MAX_EARLY] = maxEarly;
        runPacked[r] = packed;
        runLengths[r] = lengths;
        runOpen[r] = open;
        runCount = r + 1;
        // the run's references to earlier records are resolved where the records stand in the file
        int n = pendCount;
        if (n == pend.length) {
            pend = Arrays.copyOf(pend, n * 2);
        }
        pend[n] = -(r + 1);
        pendCount = n + 1;
        int first = exprCount + 1;
        segLen += count;
        exprCount += count;
        nApp += count - numLam - numPi - numLet;
        nLam += numLam;
        nPi += numPi;
        nLet += numLet;
        nRuns++;
        nRunRecords += count;
        nRunEarly += numEarly;
        if (earlyTable == null) {
            // from now on the scanner threads may look finished records up
            earlyDelta = delta;
            earlyTable = loc;
        }
        return first;
    }

    /** The reader is about to reuse the arrays of the runs it handed over: keep what the open segment needs. */
    @Override
    public void exprRunsEnd() {
        for (int r = 0; r < runCount; r++) {
            int q = r * RUN_WORDS;
            if ((runs[q + R_STATE] & RUN_OWNED) == 0) {
                int off = runs[q + R_PACKED_OFF];
                runPacked[r] = Arrays.copyOfRange(runPacked[r], off, off + runs[q + R_PACKED_LEN]);
                runs[q + R_PACKED_OFF] = 0;
                int lengthsOff = runs[q + R_LENGTHS_OFF];
                runLengths[r] = Arrays.copyOfRange(runLengths[r], lengthsOff, lengthsOff + runs[q + R_COUNT]);
                runs[q + R_LENGTHS_OFF] = 0;
                int openOff = runs[q + R_OPEN_OFF];
                runOpen[r] = Arrays.copyOfRange(runOpen[r], openOff, openOff + 2 * runs[q + R_OPEN_COUNT]);
                runs[q + R_OPEN_OFF] = 0;
                runs[q + R_STATE] |= RUN_OWNED;
            }
        }
    }

    /**
     * Decides every run of the segment at its declaration line. {@code limit} is the largest ordinal
     * that is not certainly a proof-private record of this segment: the type root or the last record
     * before the segment, whichever is larger, for a theorem or opaque whose value is kept as a block;
     * {@code Integer.MAX_VALUE} for every other unit. A run whose locally encoded children all lie
     * above it is linked (its records and all records between them and those children are unmarked,
     * so they are consecutive records of the unit's block and the encoded distances are block
     * distances); any other run is expanded into its segment records.
     */
    private void decideRuns(int limit) {
        for (int r = 0; r < runCount; r++) {
            int q = r * RUN_WORDS;
            if (runs[q + R_MIN_LOCAL] > limit) {
                runs[q + R_STATE] = (runs[q + R_STATE] & RUN_OWNED) | RUN_LINKED;
            } else {
                expandRun(r);
            }
        }
    }

    /**
     * Writes the segment records of run {@code r}: what {@link #exprApp}, {@link #exprLam},
     * {@link #exprForall} and {@link #exprLet} write, except that a reference to an earlier record is
     * left PENDING in its slot without an entry in the pending list ({@link #resolveRun} resolves
     * these when the pending list reaches the run's place), and that a reference a scanner thread
     * found already resolved is written resolved: the shared id that {@link #resolvePending} would
     * read from the location table, which does not change once it is a shared id.
     */
    private void expandRun(int r) {
        final int q = r * RUN_WORDS;
        final byte[] pk = runPacked[r];
        final byte[] ln = runLengths[r];
        final int[] op = runOpen[r];
        final int delta = runs[q + R_DELTA];
        final int base = segBase;
        final int[] s = seg;
        final int p0 = runs[q + R_PACKED_OFF];
        int p = p0;
        int l = runs[q + R_LENGTHS_OFF];
        int e = runs[q + R_OPEN_OFF];
        final int openEnd = e + 2 * runs[q + R_OPEN_COUNT];
        int slot = runs[q + R_FIRST] << 2;
        int ordinal = base + runs[q + R_FIRST] + 1;
        for (int i = 0, n = runs[q + R_COUNT]; i < n; i++, slot += 4, ordinal++) {
            final int recordEnd = p + (ln[l++] & 0xFF);
            final int kind = pk[p++];
            final int children = kind == Kind.LET ? 3 : 2;
            s[slot] = kind;
            s[slot + 3] = 0;
            for (int c = 0; c < children; c++) {
                int child;
                if (e < openEnd && (op[e] & 0x7FFF_FFFF) == p - p0) {
                    child = op[e + 1] + delta;
                    e += 2;
                } else {
                    int v = 0;
                    int shift = 0;
                    int b;
                    do {
                        b = pk[p++];
                        v |= (b & 0x7F) << shift;
                        shift += 7;
                    } while (b < 0);
                    if ((v & 1) != 0) {
                        s[slot + 1 + c] = -(v >>> 1); // resolved by a scanner thread: a shared term
                        continue;
                    }
                    child = ordinal - (v >>> 1);
                }
                if (child < 1 || child >= ordinal) {
                    throw new IllegalStateException("corrupt packed run: record " + ordinal + " refers to " + child);
                }
                int local = child - base;
                s[slot + 1 + c] = local > 0 ? local : -(PENDING + child);
            }
            if (p != recordEnd) {
                throw new IllegalStateException("corrupt packed run: record " + ordinal);
            }
        }
        runs[q + R_STATE] = (runs[q + R_STATE] & RUN_OWNED) | RUN_EXPANDED;
        nRunsExpanded++;
    }

    /**
     * The references of run {@code r} to earlier records, in file order, as {@link #resolvePending}
     * resolves a single one. An expanded run has them PENDING in its slots; a linked run in its list
     * of open children, where a record before the segment is replaced by its shared id and a record
     * of the segment by minus its ordinal (it gets its place when the sweep reaches the run).
     */
    private void resolveRun(int r) {
        final int q = r * RUN_WORDS;
        if ((runs[q + R_STATE] & RUN_STATE_MASK) == RUN_EXPANDED) {
            final int[] s = seg;
            int slot = runs[q + R_FIRST] << 2;
            for (int end = slot + (runs[q + R_COUNT] << 2); slot < end; slot += 4) {
                for (int c = 1; c <= 3; c++) {
                    int v = s[slot + c];
                    if (v <= -PENDING) {
                        int ordinal = -v - PENDING;
                        int l = loc[ordinal];
                        if (l < 0) {
                            l = promote(ordinal, l);
                        }
                        s[slot + c] = -l;
                    }
                }
            }
            return;
        }
        final int[] op = runOpen[r];
        final int delta = runs[q + R_DELTA];
        final int base = segBase;
        for (int e = runs[q + R_OPEN_OFF] + 1, end = e + 2 * runs[q + R_OPEN_COUNT]; e < end; e += 2) {
            int ordinal = op[e] + delta;
            if (ordinal > base) {
                op[e] = -ordinal;
            } else {
                int l = loc[ordinal];
                if (l < 0) {
                    l = promote(ordinal, l);
                }
                op[e] = l;
            }
        }
    }

    private int promote(int ordinal, int location) {
        int shared = out.promote(-location - 1);
        if (shared <= 0) {
            throw new IllegalStateException("promotion of block record " + (-location - 1) + " returned " + shared);
        }
        loc[ordinal] = shared;
        nPromotions++;
        return shared;
    }

    private IllegalArgumentException badReference(int ordinal) {
        return new IllegalArgumentException("expression id " + ordinal + " was not returned by this sink (line "
                + line() + "; ids issued: 1.." + exprCount + ")");
    }

    @Override
    public int exprBVar(int index) {
        if (index < 0) {
            throw new IllegalArgumentException("negative de Bruijn index");
        }
        int p = nextSlot();
        int[] s = seg;
        s[p] = Kind.BVAR;
        s[p + 1] = index;
        s[p + 2] = 0;
        s[p + 3] = 0;
        segLen++;
        nBVar++;
        return ++exprCount;
    }

    @Override
    public int exprSort(int level) {
        int p = nextSlot();
        int[] s = seg;
        s[p] = Kind.SORT;
        s[p + 1] = level;
        s[p + 2] = 0;
        s[p + 3] = 0;
        segLen++;
        nSort++;
        return ++exprCount;
    }

    @Override
    public int exprConst(int name, int[] levelIds, int off, int count) {
        int list = count == 0 ? 0 : levels.internList(levelIds, off, count);
        int p = nextSlot();
        int[] s = seg;
        s[p] = Kind.CONST;
        s[p + 1] = name;
        s[p + 2] = list;
        s[p + 3] = 0;
        segLen++;
        nConst++;
        return ++exprCount;
    }

    @Override
    public int exprApp(int fn, int arg) {
        int p = nextSlot();
        int f = child(fn, p + 1);
        int a = child(arg, p + 2);
        int[] s = seg;
        s[p] = Kind.APP;
        s[p + 1] = f;
        s[p + 2] = a;
        s[p + 3] = 0;
        segLen++;
        nApp++;
        int id = ++exprCount;
        if (mdata != null && (hasMData(fn) || hasMData(arg))) {
            markMData(id);
        }
        return id;
    }

    @Override
    public int exprLam(int binderName, int binderInfo, int type, int body) {
        int p = nextSlot();
        int t = child(type, p + 1);
        int b = bodyChild(body, p + 2);
        int[] s = seg;
        s[p] = Kind.LAM;
        s[p + 1] = t;
        s[p + 2] = b;
        s[p + 3] = 0;
        segLen++;
        nLam++;
        int id = ++exprCount;
        if (mdata != null && (hasMData(type) || hasMData(body))) {
            markMData(id);
        }
        return id;
    }

    @Override
    public int exprForall(int binderName, int binderInfo, int type, int body) {
        int p = nextSlot();
        int t = child(type, p + 1);
        int b = bodyChild(body, p + 2);
        int[] s = seg;
        s[p] = Kind.PI;
        s[p + 1] = t;
        s[p + 2] = b;
        s[p + 3] = 0;
        segLen++;
        nPi++;
        int id = ++exprCount;
        if (mdata != null && (hasMData(type) || hasMData(body))) {
            markMData(id);
        }
        return id;
    }

    @Override
    public int exprLet(int binderName, int type, int value, int body, boolean nondep) {
        int p = nextSlot();
        int t = child(type, p + 1);
        int v = child(value, p + 2);
        int b = bodyChild(body, p + 3);
        int[] s = seg;
        s[p] = Kind.LET;
        s[p + 1] = t;
        s[p + 2] = v;
        s[p + 3] = b;
        segLen++;
        nLet++;
        int id = ++exprCount;
        if (mdata != null && (hasMData(type) || hasMData(value) || hasMData(body))) {
            markMData(id);
        }
        return id;
    }

    @Override
    public int exprProj(int typeName, long index, int struct) {
        if (index < 0 || index > Integer.MAX_VALUE) {
            // no structure has that many fields, so the official kernel fails on every use; RLVM's
            // node holds an int, and declining is the safe answer
            throw unsupported("projection index " + Long.toUnsignedString(index) + " above 2^31 - 1");
        }
        int p = nextSlot();
        int e = child(struct, p + 1);
        int[] s = seg;
        s[p] = Kind.PROJ;
        s[p + 1] = e;
        s[p + 2] = (int) index;
        s[p + 3] = typeName;
        segLen++;
        nProj++;
        int id = ++exprCount;
        if (mdata != null && hasMData(struct)) {
            markMData(id);
        }
        return id;
    }

    @Override
    public int exprNatLit(byte[] digits, int off, int len) {
        int p = nextSlot();
        int w;
        int a;
        int b;
        if (fitsUnsignedLong(digits, off, len)) {
            long v = parseUnsignedLong(digits, off, len);
            w = Kind.NATLIT;
            a = (int) v;
            b = (int) (v >>> 32);
        } else {
            int n = nats.decode(digits, off, len, limbs);
            if (n < 2 || limbs.words[n - 1] == 0) {
                throw new IllegalStateException("NatDecoder returned a non-canonical number for a literal of 2^64 or more");
            }
            w = Kind.NATLIT | S_BIG;
            a = out.addNat(limbs.words, 0, n);
            b = 0;
            nNatBig++;
        }
        int[] s = seg;
        s[p] = w;
        s[p + 1] = a;
        s[p + 2] = b;
        s[p + 3] = 0;
        segLen++;
        nNat++;
        return ++exprCount;
    }

    @Override
    public int exprStrLit(byte[] utf8, int off, int len) {
        int p = nextSlot();
        int lit = out.addString(utf8, off, len);
        int[] s = seg;
        s[p] = Kind.STRLIT;
        s[p + 1] = lit;
        s[p + 2] = 0;
        s[p + 3] = 0;
        segLen++;
        nStr++;
        return ++exprCount;
    }

    /**
     * Metadata is dropped: the wrapped expression stands for the record. The wrapped record is marked
     * (see {@link #mdata}); a direct reference to it counts as carrying metadata too, which only errs
     * on the side of declining.
     */
    @Override
    public int exprMData(int expr) {
        if (expr < 1 || expr > exprCount) {
            throw badReference(expr);
        }
        nMData++;
        markMData(expr);
        return expr;
    }

    private boolean hasMData(int ordinal) {
        long[] t = mdata;
        int w = ordinal >>> 6;
        return t != null && w < t.length && (t[w] >>> ordinal & 1L) != 0;
    }

    private void markMData(int ordinal) {
        long[] t = mdata;
        int w = ordinal >>> 6;
        if (t == null || w >= t.length) {
            int cap = Math.max(w + 1, t == null ? 64 : (int) Math.min((long) t.length * 2, (Integer.MAX_VALUE >>> 6) + 1));
            mdata = t = t == null ? new long[cap] : Arrays.copyOf(t, cap);
        }
        t[w] |= 1L << ordinal;
    }

    /**
     * Declines an inductive or quot record one of whose roots carries metadata: the official checker
     * compares these records, metadata included, with the declarations its kernel generates, and
     * tests several of their roots syntactically ({@code is_pi}, {@code is_sort}); after dropping the
     * metadata RLVM could accept a record the official checker rejects.
     */
    private void requireNoMData(int root, String what) {
        if (mdata != null && hasMData(root)) {
            throw unsupportedDecl("metadata (mdata) node inside " + what
                    + ": RLVM drops metadata, so its comparison with the kernel's own declaration would not be exact");
        }
    }

    /** Declines a declaration whose root {@code root} is wrapped by, or contains, a metadata node. */
    private void requireNoMDataInDeclaration(int root) {
        if (root >= 1 && root <= exprCount && hasMData(root)) {
            throw unsupportedDecl("metadata (mdata) node in the type or value of a declaration: the official kernel"
                    + " treats it as a term of its own, RLVM drops it, so what RLVM would check is not that declaration");
        }
    }

    /** Canonical decimal digits denote a number below 2^64. */
    private static boolean fitsUnsignedLong(byte[] d, int off, int len) {
        if (len < 20) {
            return true;
        }
        if (len > 20) {
            return false;
        }
        // 20 digits: compare with 18446744073709551615
        final String max = "18446744073709551615";
        for (int i = 0; i < 20; i++) {
            int c = d[off + i] - max.charAt(i);
            if (c != 0) {
                return c < 0;
            }
        }
        return true;
    }

    private static long parseUnsignedLong(byte[] d, int off, int len) {
        long v = 0;
        for (int i = off, e = off + len; i < e; i++) {
            int c = d[i] - '0';
            if (c < 0 || c > 9) {
                throw new IllegalArgumentException("not a decimal digit in a number handed over by the reader");
            }
            v = v * 10 + c;
        }
        return v;
    }

    // ============================================================================================
    // the declaration sweep
    // ============================================================================================

    /** Marks segment record {@code enc} (if it is one) as a root of the shared part; returns the largest root so far. */
    private int markRoot(int enc, int top) {
        if (enc > 0) {
            seg[(enc - 1) << 2] |= S_MARK;
            return Math.max(top, enc);
        }
        return top;
    }

    /**
     * Marks the segment records reachable from the marked roots, the largest of which is record
     * {@code top} (1-based): one backward pass (a record only refers to earlier ones).
     */
    private void markReachable(int top) {
        int[] s = seg;
        final LoadTarget o = out;
        for (int p = (top - 1) << 2; p >= 0; p -= 4) {
            int w = s[p];
            if ((w & S_MARK) == 0) {
                continue;
            }
            // A marked record becomes a shared node, and writing it reads the nodes of its children.
            // A child in the segment is marked; a child that is an earlier shared term is announced
            // to the target (LoadTarget.touch), so that its node is on its way into the cache when
            // the sweep comes to this record instead of being missed there, one record at a time.
            switch (w & K_MASK) {
                case Kind.APP, Kind.LAM, Kind.PI -> {
                    int a = s[p + 1];
                    int b = s[p + 2];
                    if (a > 0) {
                        s[(a - 1) << 2] |= S_MARK;
                    } else {
                        o.touch(-a);
                    }
                    if (b > 0) {
                        s[(b - 1) << 2] |= S_MARK;
                    } else {
                        o.touch(-b);
                    }
                }
                case Kind.LET -> {
                    int a = s[p + 1];
                    int b = s[p + 2];
                    int c = s[p + 3];
                    if (a > 0) {
                        s[(a - 1) << 2] |= S_MARK;
                    } else {
                        o.touch(-a);
                    }
                    if (b > 0) {
                        s[(b - 1) << 2] |= S_MARK;
                    } else {
                        o.touch(-b);
                    }
                    if (c > 0) {
                        s[(c - 1) << 2] |= S_MARK;
                    } else {
                        o.touch(-c);
                    }
                }
                case Kind.PROJ -> {
                    int a = s[p + 1];
                    if (a > 0) {
                        s[(a - 1) << 2] |= S_MARK;
                    } else {
                        o.touch(-a);
                    }
                }
                default -> {
                }
            }
        }
    }

    private void ensureLoc(int lastOrdinal) {
        if (lastOrdinal > MAX_EXPR_RECORDS) {
            throw unsupportedDecl("more than " + MAX_EXPR_RECORDS + " expression records");
        }
        if (lastOrdinal >= loc.length) {
            long want = Math.max((long) loc.length + (loc.length >> 2) + 1024, (long) lastOrdinal + 1);
            loc = Arrays.copyOf(loc, (int) Math.min(want, Integer.MAX_VALUE - 8));
            if (earlyTable != null) {
                earlyTable = loc; // the scanner threads follow the table
            }
        }
    }

    /** Shared id of a segment-encoded child whose record has been written to the shared region. */
    private int sharedOf(int enc) {
        if (enc < 0) {
            return -enc;
        }
        int l = loc[segBase + enc];
        if (l <= 0) {
            throw new IllegalStateException("segment record " + (segBase + enc) + " is not shared (location " + l + ")");
        }
        return l;
    }

    /** Reports {@code enc} to the target if it is a record of the segment with exactly one referrer. */
    private void single(int enc, int parent) {
        if (enc > 0 && (seg[(enc - 1) << 2] & RC_MASK) == RC_ONE) {
            out.singleReferrer(loc[segBase + enc], parent);
        }
    }

    /**
     * Writes the whole segment: marked records go to the shared region, the others to one proof block.
     * Fills {@code loc} for the segment.
     *
     * @param valueEnc segment encoding of the owner's value, or 0 if the unit has no single value
     * @return the value word of the owner (shared id, or {@code -(block + 1)}); 0 without a value
     */
    private int sweep(int valueEnc) {
        final int n = segLen;
        final int base = segBase;
        ensureLoc(base + n);
        final int[] s = seg;
        final int[] lc = loc;
        final LoadTarget o = out;
        final int fm = flagMask;
        final boolean invariants = fm != 0;
        int firstRecord = 0;
        int k = 0; // records written to the block
        long shared = 0;
        int run = nextLinkedRun(0);
        int runAt = run < runCount ? runs[run * RUN_WORDS + R_FIRST] : -1;
        for (int i = 0, p = 0; i < n; i++, p += 4) {
            if (i == runAt) {
                // a linked run: its records are block records k, k + 1, ...
                final int q = run * RUN_WORDS;
                final int count = runs[q + R_COUNT];
                if (k == 0) {
                    firstRecord = o.blockBegin();
                    if (firstRecord < 0 || firstRecord > Integer.MAX_VALUE - n - 2) {
                        throw unsupportedDecl("more than 2^31 proof-block records");
                    }
                }
                final int[] op = runOpen[run];
                final int openOff = runs[q + R_OPEN_OFF];
                final int openCount = runs[q + R_OPEN_COUNT];
                // open children that are records of this segment: their places are known now
                for (int e = openOff + 1, end = e + 2 * openCount; e < end; e += 2) {
                    int v = op[e];
                    if (v < 0) {
                        int l = lc[-v];
                        op[e] = l > 0 ? l : l + firstRecord; // -(index in the block + 1)
                    }
                }
                o.blockRun(k, runPacked[run], runs[q + R_PACKED_OFF], runs[q + R_PACKED_LEN], runLengths[run],
                        runs[q + R_LENGTHS_OFF], count, op, openOff, openCount, runs[q + R_MAX_EARLY]);
                for (int c = 0, at = base + 1 + i, rec = -(firstRecord + k + 1); c < count; c++) {
                    lc[at + c] = rec - c;
                }
                k += count;
                i += count - 1;
                p += (count - 1) << 2;
                run = nextLinkedRun(run + 1);
                runAt = run < runCount ? runs[run * RUN_WORDS + R_FIRST] : -1;
                continue;
            }
            final int w = s[p];
            final int kind = w & K_MASK;
            if ((w & S_MARK) != 0) {
                final int flags = (w & fm) << FLAG_SHIFT;
                int id;
                switch (kind) {
                    case Kind.APP -> {
                        int ea = s[p + 1];
                        int eb = s[p + 2];
                        id = o.app(ea < 0 ? -ea : lc[base + ea], eb < 0 ? -eb : lc[base + eb], flags);
                        if (invariants) {
                            single(ea, id);
                            single(eb, id);
                        }
                    }
                    case Kind.LAM -> {
                        int ea = s[p + 1];
                        int eb = s[p + 2];
                        id = o.lam(ea < 0 ? -ea : lc[base + ea], eb < 0 ? -eb : lc[base + eb], flags);
                        if (invariants) {
                            single(ea, id);
                        }
                    }
                    case Kind.PI -> {
                        int ea = s[p + 1];
                        int eb = s[p + 2];
                        id = o.pi(ea < 0 ? -ea : lc[base + ea], eb < 0 ? -eb : lc[base + eb], flags);
                        if (invariants) {
                            single(ea, id);
                        }
                    }
                    case Kind.LET -> {
                        int ea = s[p + 1];
                        int eb = s[p + 2];
                        int ec = s[p + 3];
                        id = o.let(ea < 0 ? -ea : lc[base + ea], eb < 0 ? -eb : lc[base + eb],
                                ec < 0 ? -ec : lc[base + ec], flags);
                        if (invariants) {
                            single(ea, id);
                            single(eb, id);
                        }
                    }
                    case Kind.PROJ -> {
                        int ea = s[p + 1];
                        id = o.proj(s[p + 3], s[p + 2], ea < 0 ? -ea : lc[base + ea], flags);
                    }
                    case Kind.CONST -> id = o.constant(s[p + 1], s[p + 2], flags);
                    case Kind.BVAR -> id = o.bvar(s[p + 1], flags);
                    case Kind.SORT -> id = o.sort(s[p + 1], flags);
                    case Kind.NATLIT -> id = (w & S_BIG) != 0
                            ? o.natBig(s[p + 1], flags)
                            : o.natSmall((s[p + 1] & 0xFFFF_FFFFL) | ((long) s[p + 2] << 32), flags);
                    case Kind.STRLIT -> id = o.str(s[p + 1], flags);
                    default -> throw new IllegalStateException("corrupt segment record kind " + kind);
                }
                if (id <= 0) {
                    throw new IllegalStateException("the store returned shared id " + id);
                }
                lc[base + 1 + i] = id;
                shared++;
            } else {
                if (k == 0) {
                    firstRecord = o.blockBegin();
                    if (firstRecord < 0 || firstRecord > Integer.MAX_VALUE - n - 2) {
                        throw unsupportedDecl("more than 2^31 proof-block records");
                    }
                }
                switch (kind) {
                    case Kind.APP -> o.blockApp(ref(s[p + 1], k, firstRecord), ref(s[p + 2], k, firstRecord));
                    case Kind.LAM -> o.blockLam(ref(s[p + 1], k, firstRecord), ref(s[p + 2], k, firstRecord));
                    case Kind.PI -> o.blockPi(ref(s[p + 1], k, firstRecord), ref(s[p + 2], k, firstRecord));
                    case Kind.LET -> o.blockLet(ref(s[p + 1], k, firstRecord), ref(s[p + 2], k, firstRecord),
                            ref(s[p + 3], k, firstRecord));
                    case Kind.PROJ -> o.blockProj(s[p + 3], s[p + 2], ref(s[p + 1], k, firstRecord));
                    case Kind.CONST -> o.blockConstant(s[p + 1], s[p + 2]);
                    case Kind.BVAR -> o.blockBVar(s[p + 1]);
                    case Kind.SORT -> o.blockSort(s[p + 1]);
                    case Kind.NATLIT -> {
                        if ((w & S_BIG) != 0) {
                            o.blockNatBig(s[p + 1]);
                        } else {
                            o.blockNatSmall((s[p + 1] & 0xFFFF_FFFFL) | ((long) s[p + 2] << 32));
                        }
                    }
                    case Kind.STRLIT -> o.blockStr(s[p + 1]);
                    default -> throw new IllegalStateException("corrupt segment record kind " + kind);
                }
                lc[base + 1 + i] = -(firstRecord + k + 1);
                k++;
            }
        }
        nSharedWritten += shared;
        int valueWord;
        int rootLocal = -1;
        if (valueEnc == 0) {
            valueWord = 0;
        } else if (valueEnc < 0) {
            valueWord = -valueEnc;
        } else {
            int l = lc[base + valueEnc];
            if (l > 0) {
                valueWord = l;
            } else {
                rootLocal = -l - 1 - firstRecord;
                valueWord = 0; // set below
            }
        }
        if (k > 0) {
            int block = o.blockEnd(rootLocal);
            if (block < 0) {
                throw new IllegalStateException("the store returned block index " + block);
            }
            nBlocks++;
            nBlockNodes += k;
            if (rootLocal >= 0) {
                valueWord = -(block + 1);
                nBlockValues++;
            }
        }
        return valueWord;
    }

    /** The first linked run from run {@code r} on; {@link #runCount} if there is none. */
    private int nextLinkedRun(int r) {
        while (r < runCount && (runs[r * RUN_WORDS + R_STATE] & RUN_STATE_MASK) != RUN_LINKED) {
            r++;
        }
        return r;
    }

    /** Block reference to a segment-encoded child, for the block record with index {@code k}. */
    private int ref(int enc, int k, int firstRecord) {
        if (enc < 0) {
            return LoadTarget.blockShared(-enc);
        }
        int l = loc[segBase + enc];
        if (l > 0) {
            return LoadTarget.blockShared(l);
        }
        return LoadTarget.blockLocal(k - (-l - 1 - firstRecord));
    }

    /** The segment has been written: the next record starts a new one. */
    private void endSegment() {
        if (segLen > largestSegment) {
            largestSegment = segLen;
        }
        segBase = exprCount;
        segLen = 0;
        for (int r = 0; r < runCount; r++) {
            runPacked[r] = null;
            runLengths[r] = null;
            runOpen[r] = null;
        }
        runCount = 0;
    }

    private void requireNoBlock() {
        if (inBlock) {
            throw new IllegalStateException("declaration record inside an inductive block");
        }
    }

    private int lparams(int[] ids, int off, int count) {
        return count == 0 ? 0 : levels.internList(ids, off, count);
    }

    /**
     * A dropped (unsafe or partial) record: only its name is remembered; its records stay in the segment.
     *
     * <p>One case is a certain rejection: the official checker's quotient post-check requires every one
     * of {@code Quot}, {@code Quot.mk}, {@code Quot.lift}, {@code Quot.ind} that the export declares to
     * be the kernel's own constant, and an unsafe or partial record never is ("Could not find quotient
     * constant in final kernel env", verified with leanchecker 4.36.0). No unit exists for a dropped
     * record, so the loader is the one place that can say so.
     */
    private void drop(int name, boolean firstOfRecord) {
        if (!(firstOfRecord ? out.dropUnsafe(name) : out.dropName(name))) {
            throw duplicate(name);
        }
        if (name == quotName || name == quotMk || name == quotLift || name == quotInd) {
            throw new MalformedExportException("Could not find quotient constant in final kernel env: "
                    + out.nameToString(name) + " (declared by an unsafe or partial record)", line() - 1);
        }
        nDropped++;
    }

    /**
     * One constant, one unit.
     *
     * @param value ordinal of the value, or 0 if the kind has none
     * @param asBlock keep what only the value reaches as a proof block (theorem, opaque)
     */
    private void declare(int kind, int name, int[] ids, int lpOff, int lpCount, int type, int value, int hint,
            boolean asBlock) {
        if (mdata != null) {
            // Metadata is dropped by this loader, but officially an mdata node is a term of its own
            // for every cache of the kernel and hides what is under it from its syntactic tests: a
            // declaration whose type or value carries metadata is not what the official checker checks
            // (finding S3-F2 / R2: a wrong accept and a wrong reject of the reference engine).
            requireNoMDataInDeclaration(type);
            if (value != 0) {
                requireNoMDataInDeclaration(value);
            }
        }
        int lp = lparams(ids, lpOff, lpCount);
        out.beginUnit(kind);
        int cid = out.reserve(kind, name, lp);
        if (cid == 0) {
            throw duplicate(name);
        }
        if (runCount != 0) {
            decideRuns(asBlock && proofBlocks ? Math.max(type, segBase) : Integer.MAX_VALUE);
        }
        resolvePending();
        int typeEnc = rootRef(type);
        int valueEnc = value != 0 ? rootRef(value) : 0;
        int top = markRoot(typeEnc, 0);
        if (!(asBlock && proofBlocks)) {
            top = markRoot(valueEnc, top);
        }
        if (top > 0) {
            markReachable(top);
        }
        int valueWord = sweep(valueEnc);
        out.complete(cid, sharedOf(typeEnc), valueWord, hint);
        out.endUnit();
        nConstants++;
        nUnits++;
        endSegment();
        unitDone();
    }

    @Override
    public void declAxiom(int name, int[] ids, int lpOff, int lpCount, int type, boolean isUnsafe) {
        requireNoBlock();
        nAxiom++;
        if (isUnsafe) {
            drop(name, true);
            unitDone();
            return;
        }
        declare(DeclKind.AXIOM, name, ids, lpOff, lpCount, type, 0, 0, false);
    }

    @Override
    public void declDef(int name, int[] ids, int lpOff, int lpCount, int type, int value, int hintKind,
            int hintHeight, int safety, int allOff, int allCount) {
        requireNoBlock();
        nDef++;
        if (safety != ExportSink.SAFETY_SAFE) {
            if (safety != ExportSink.SAFETY_UNSAFE && safety != ExportSink.SAFETY_PARTIAL) {
                throw new IllegalArgumentException("unknown safety code " + safety);
            }
            drop(name, true);
            unitDone();
            return;
        }
        int hint;
        switch (hintKind) {
            case ExportSink.HINT_OPAQUE -> hint = DeclKind.HINT_OPAQUE;
            case ExportSink.HINT_ABBREV -> hint = DeclKind.HINT_ABBREV;
            case ExportSink.HINT_REGULAR -> hint = DeclKind.hintRegular(Integer.toUnsignedLong(hintHeight));
            default -> throw new IllegalArgumentException("unknown hint code " + hintKind);
        }
        declare(DeclKind.DEFN, name, ids, lpOff, lpCount, type, value, hint, false);
    }

    @Override
    public void declThm(int name, int[] ids, int lpOff, int lpCount, int type, int value, int allOff,
            int allCount) {
        requireNoBlock();
        nThm++;
        declare(DeclKind.THM, name, ids, lpOff, lpCount, type, value, 0, true);
    }

    @Override
    public void declOpaque(int name, int[] ids, int lpOff, int lpCount, int type, int value, boolean isUnsafe,
            int allOff, int allCount) {
        requireNoBlock();
        nOpaque++;
        if (isUnsafe) {
            drop(name, true);
            unitDone();
            return;
        }
        declare(DeclKind.OPAQUE, name, ids, lpOff, lpCount, type, value, 0, true);
    }

    @Override
    public void declQuot(int name, int[] ids, int lpOff, int lpCount, int type, int kind) {
        requireNoBlock();
        nQuot++;
        int declKind;
        switch (kind) {
            case ExportSink.QUOT_TYPE -> declKind = DeclKind.QUOT_TYPE;
            case ExportSink.QUOT_CTOR -> declKind = DeclKind.QUOT_MK;
            case ExportSink.QUOT_LIFT -> declKind = DeclKind.QUOT_LIFT;
            case ExportSink.QUOT_IND -> declKind = DeclKind.QUOT_IND;
            default -> throw new IllegalArgumentException("unknown quot kind code " + kind);
        }
        if (type >= 1 && type <= exprCount) {
            requireNoMData(type, "a quot record");
        }
        declare(declKind, name, ids, lpOff, lpCount, type, 0, 0, false);
    }

    // ============================================================================================
    // inductive blocks
    // ============================================================================================

    @Override
    public void inductBegin(int numTypes, int numCtors, int numRecs) {
        requireNoBlock();
        if (numTypes < 0 || numCtors < 0 || numRecs < 0) {
            throw new IllegalArgumentException("negative member count");
        }
        nInductive++;
        inBlock = true;
        annTypes = numTypes;
        annCtors = numCtors;
        annRecs = numRecs;
        gotTypes = 0;
        gotCtors = 0;
        gotRecs = 0;
        members = 0;
        rules = 0;
        rulesExpected = 0;
    }

    private int newMember(int name, int lp, int type, int flags) {
        int m = members;
        if (m == mName.length) {
            int cap = m * 2;
            mName = Arrays.copyOf(mName, cap);
            mLparams = Arrays.copyOf(mLparams, cap);
            mType = Arrays.copyOf(mType, cap);
            mFlags = Arrays.copyOf(mFlags, cap);
            mA = Arrays.copyOf(mA, cap);
            mB = Arrays.copyOf(mB, cap);
            mRules = Arrays.copyOf(mRules, cap);
            mNum = Arrays.copyOf(mNum, cap * 4);
        }
        if (type < 1 || type > exprCount) {
            throw badReference(type);
        }
        mName[m] = name;
        mLparams[m] = lp;
        mType[m] = type;
        mFlags[m] = flags;
        mA[m] = 0;
        mB[m] = 0;
        mRules[m] = 0;
        members = m + 1;
        return m;
    }

    private void requireBlock(boolean ok, String what) {
        if (!inBlock || !ok || rules != rulesExpected) {
            throw new IllegalStateException("unexpected " + what + " record (inductive block protocol)");
        }
    }

    @Override
    public void inductType(int name, int[] ids, int lpOff, int lpCount, int type, long numParams, long numIndices,
            int allOff, int allCount, int ctorsOff, int ctorsCount, long numNested, boolean isRec, boolean isUnsafe,
            boolean isReflexive) {
        requireBlock(gotTypes < annTypes && gotCtors == 0 && gotRecs == 0, "inductive type");
        int lp = lparams(ids, lpOff, lpCount);
        int all = allCount == 0 ? 0 : levels.internList(ids, allOff, allCount);
        int ctors = ctorsCount == 0 ? 0 : levels.internList(ids, ctorsOff, ctorsCount);
        int m = newMember(name, lp, type, (isRec ? BlockClaims.F_REC : 0) | (isReflexive ? BlockClaims.F_REFLEXIVE : 0)
                | (isUnsafe ? BlockClaims.F_UNSAFE : 0));
        mA[m] = all;
        mB[m] = ctors;
        mNum[4 * m] = numParams;
        mNum[4 * m + 1] = numIndices;
        mNum[4 * m + 2] = numNested;
        mNum[4 * m + 3] = 0;
        gotTypes++;
        nInductTypes++;
    }

    @Override
    public void inductCtor(int name, int[] ids, int lpOff, int lpCount, int type, int induct, long cidx,
            long numParams, long numFields, boolean isUnsafe) {
        requireBlock(gotTypes == annTypes && gotCtors < annCtors && gotRecs == 0, "constructor");
        int lp = lparams(ids, lpOff, lpCount);
        int m = newMember(name, lp, type, isUnsafe ? BlockClaims.F_UNSAFE : 0);
        mA[m] = induct;
        mNum[4 * m] = cidx;
        mNum[4 * m + 1] = numParams;
        mNum[4 * m + 2] = numFields;
        mNum[4 * m + 3] = 0;
        gotCtors++;
        nInductCtors++;
    }

    @Override
    public void inductRec(int name, int[] ids, int lpOff, int lpCount, int type, int allOff, int allCount,
            long numParams, long numIndices, long numMotives, long numMinors, boolean k, boolean isUnsafe,
            int numRules) {
        requireBlock(gotTypes == annTypes && gotCtors == annCtors && gotRecs < annRecs && numRules >= 0, "recursor");
        int lp = lparams(ids, lpOff, lpCount);
        int all = allCount == 0 ? 0 : levels.internList(ids, allOff, allCount);
        int m = newMember(name, lp, type, (k ? BlockClaims.F_K : 0) | (isUnsafe ? BlockClaims.F_UNSAFE : 0));
        mA[m] = all;
        mB[m] = rules;
        mRules[m] = numRules;
        mNum[4 * m] = numParams;
        mNum[4 * m + 1] = numIndices;
        mNum[4 * m + 2] = numMotives;
        mNum[4 * m + 3] = numMinors;
        rulesExpected = rules + numRules;
        gotRecs++;
        nInductRecs++;
    }

    @Override
    public void inductRecRule(int ctor, long numFields, int rhs) {
        if (!inBlock || rules >= rulesExpected) {
            throw new IllegalStateException("unexpected recursor rule (inductive block protocol)");
        }
        if (rhs < 1 || rhs > exprCount) {
            throw badReference(rhs);
        }
        int r = rules;
        if (r == ruleCtor.length) {
            ruleCtor = Arrays.copyOf(ruleCtor, r * 2);
            ruleRhs = Arrays.copyOf(ruleRhs, r * 2);
            ruleFields = Arrays.copyOf(ruleFields, r * 2);
        }
        ruleCtor[r] = ctor;
        ruleRhs[r] = rhs;
        ruleFields[r] = numFields;
        rules = r + 1;
        nRecRules++;
    }

    /**
     * Removes the type records and the unsafe constructor and recursor records from the scratch of the
     * block being read (their names have been dropped): what remains is an inductive record without
     * types, in the same arrays.
     */
    private void keepSafeMembers() {
        int k = 0;
        int ctors = 0;
        int recs = 0;
        int r2 = 0;
        for (int m = annTypes; m < members; m++) {
            if ((mFlags[m] & BlockClaims.F_UNSAFE) != 0) {
                continue;
            }
            boolean isRec = m >= annTypes + annCtors;
            mName[k] = mName[m];
            mLparams[k] = mLparams[m];
            mType[k] = mType[m];
            mFlags[k] = mFlags[m];
            mA[k] = mA[m];
            System.arraycopy(mNum, 4 * m, mNum, 4 * k, 4);
            if (isRec) {
                int from = mB[m];
                int count = mRules[m];
                // rules move down (r2 <= from): an overlapping forward copy is safe
                for (int i = 0; i < count; i++) {
                    ruleCtor[r2 + i] = ruleCtor[from + i];
                    ruleRhs[r2 + i] = ruleRhs[from + i];
                    ruleFields[r2 + i] = ruleFields[from + i];
                }
                mB[k] = r2;
                mRules[k] = count;
                r2 += count;
                recs++;
            } else {
                mB[k] = 0;
                mRules[k] = 0;
                ctors++;
            }
            k++;
        }
        annTypes = 0;
        annCtors = ctors;
        annRecs = recs;
        members = k;
        rules = r2;
        rulesExpected = r2;
    }

    @Override
    public void inductEnd() {
        requireBlock(gotTypes == annTypes && gotCtors == annCtors && gotRecs == annRecs, "end of inductive");
        inBlock = false;
        if (members == 0) {
            return; // {"types":[],"ctors":[],"recs":[]}: declares nothing, as in the official reader
        }
        // Safety (ARCHITECTURE.md 4.5): a block whose type records are unsafe is dropped.
        int unsafeTypes = 0;
        for (int m = 0; m < annTypes; m++) {
            if ((mFlags[m] & BlockClaims.F_UNSAFE) != 0) {
                unsafeTypes++;
            }
        }
        if (unsafeTypes != 0) {
            if (unsafeTypes != annTypes) {
                // The official replay builds the block from the first safe type and takes the unsafe
                // type records along; RLVM does not implement that mixture.
                throw unsupportedDecl("inductive block that mixes safe and unsafe type records");
            }
            int safeMembers = 0;
            for (int m = annTypes; m < members; m++) {
                if ((mFlags[m] & BlockClaims.F_UNSAFE) == 0) {
                    safeMembers++;
                }
            }
            if (safeMembers == 0) {
                // dropped as a whole: one constant-less unit, every name remembered
                for (int m = 0; m < members; m++) {
                    drop(mName[m], m == 0);
                }
                unitDone();
                return;
            }
            // The official replay never sees the unsafe records (the types are not replayed, so the
            // kernel generates nothing for them) but it remembers every SAFE constructor and recursor
            // record and requires, at the end, a kernel-generated constant of that name that equals
            // it. The safe records are therefore loaded as a unit without types: admission decides
            // (no safe inductive type of the file generates the name: "No such constructor /
            // recursor", a rejection; otherwise the comparison with that type's block).
            for (int m = 0; m < members; m++) {
                if ((mFlags[m] & BlockClaims.F_UNSAFE) != 0) {
                    drop(mName[m], false);
                }
            }
            keepSafeMembers();
        }
        final int n = members;
        if (mdata != null) {
            for (int m = 0; m < n; m++) {
                requireNoMData(mType[m], "an inductive record");
            }
            for (int r = 0; r < rules; r++) {
                requireNoMData(ruleRhs[r], "an inductive record");
            }
        }
        final LoadTarget o = out;
        o.beginUnit(DeclKind.INDUCT);
        final int[] cids = mRules; // reuse after the rule counts have been copied into the blob
        // claims blob, part 1 (everything but cids and rule right-hand sides)
        int need = BlockClaims.HEADER + annTypes * BlockClaims.TYPE_WORDS + annCtors * BlockClaims.CTOR_WORDS
                + annRecs * BlockClaims.REC_WORDS + rules * BlockClaims.RULE_WORDS;
        if (blob.length < need) {
            blob = new int[Math.max(need, blob.length * 2)];
        }
        final int[] bl = blob;
        Arrays.fill(bl, 0, need, 0);
        bl[BlockClaims.H_VERSION] = BlockClaims.VERSION;
        bl[BlockClaims.H_NUM_TYPES] = annTypes;
        bl[BlockClaims.H_NUM_CTORS] = annCtors;
        bl[BlockClaims.H_NUM_RECS] = annRecs;
        int q = BlockClaims.HEADER;
        for (int m = 0; m < annTypes; m++, q += BlockClaims.TYPE_WORDS) {
            BlockClaims.putLong(bl, q + BlockClaims.T_NUM_PARAMS, mNum[4 * m]);
            BlockClaims.putLong(bl, q + BlockClaims.T_NUM_INDICES, mNum[4 * m + 1]);
            BlockClaims.putLong(bl, q + BlockClaims.T_NUM_NESTED, mNum[4 * m + 2]);
            bl[q + BlockClaims.T_FLAGS] = mFlags[m];
            bl[q + BlockClaims.T_ALL] = mA[m];
            bl[q + BlockClaims.T_CTORS] = mB[m];
        }
        for (int m = annTypes; m < annTypes + annCtors; m++, q += BlockClaims.CTOR_WORDS) {
            bl[q + BlockClaims.C_INDUCT] = mA[m];
            BlockClaims.putLong(bl, q + BlockClaims.C_CIDX, mNum[4 * m]);
            BlockClaims.putLong(bl, q + BlockClaims.C_NUM_PARAMS, mNum[4 * m + 1]);
            BlockClaims.putLong(bl, q + BlockClaims.C_NUM_FIELDS, mNum[4 * m + 2]);
            bl[q + BlockClaims.C_FLAGS] = mFlags[m];
        }
        bl[BlockClaims.H_RECS_OFFSET] = q;
        // names first: the members of the block see each other (ARCHITECTURE.md 3.3)
        for (int m = 0; m < n; m++) {
            int kind = m < annTypes ? DeclKind.INDUCT : m < annTypes + annCtors ? DeclKind.CTOR : DeclKind.REC;
            int cid = o.reserve(kind, mName[m], mLparams[m]);
            if (cid == 0) {
                throw duplicate(mName[m]);
            }
            if (kind == DeclKind.REC) {
                // recursor entry header; mRules[m] is read before cids[m] overwrites it
                int nr = mRules[m];
                bl[q + BlockClaims.R_ALL] = mA[m];
                BlockClaims.putLong(bl, q + BlockClaims.R_NUM_PARAMS, mNum[4 * m]);
                BlockClaims.putLong(bl, q + BlockClaims.R_NUM_INDICES, mNum[4 * m + 1]);
                BlockClaims.putLong(bl, q + BlockClaims.R_NUM_MOTIVES, mNum[4 * m + 2]);
                BlockClaims.putLong(bl, q + BlockClaims.R_NUM_MINORS, mNum[4 * m + 3]);
                bl[q + BlockClaims.R_FLAGS] = mFlags[m];
                bl[q + BlockClaims.R_NUM_RULES] = nr;
                bl[q + BlockClaims.R_CID] = cid;
                int rq = q + BlockClaims.REC_WORDS;
                for (int r = mB[m], e = r + nr; r < e; r++, rq += BlockClaims.RULE_WORDS) {
                    bl[rq + BlockClaims.RULE_CTOR] = ruleCtor[r];
                    BlockClaims.putLong(bl, rq + BlockClaims.RULE_NFIELDS, ruleFields[r]);
                }
                q = rq;
            }
            cids[m] = cid;
        }
        // roots: every member type and every rule right-hand side
        if (runCount != 0) {
            decideRuns(Integer.MAX_VALUE);
        }
        resolvePending();
        int top = 0;
        for (int m = 0; m < n; m++) {
            mType[m] = rootRef(mType[m]);
            top = markRoot(mType[m], top);
        }
        for (int r = 0; r < rules; r++) {
            ruleRhs[r] = rootRef(ruleRhs[r]);
            top = markRoot(ruleRhs[r], top);
        }
        if (top > 0) {
            markReachable(top);
        }
        sweep(0);
        // rows, and the cids / terms of the blob
        q = BlockClaims.HEADER;
        for (int m = 0; m < annTypes; m++, q += BlockClaims.TYPE_WORDS) {
            bl[q + BlockClaims.T_CID] = cids[m];
        }
        for (int m = annTypes; m < annTypes + annCtors; m++, q += BlockClaims.CTOR_WORDS) {
            bl[q + BlockClaims.C_CID] = cids[m];
        }
        for (int m = annTypes + annCtors; m < n; m++) {
            int nr = bl[q + BlockClaims.R_NUM_RULES];
            int rq = q + BlockClaims.REC_WORDS;
            for (int r = mB[m], e = r + nr; r < e; r++, rq += BlockClaims.RULE_WORDS) {
                bl[rq + BlockClaims.RULE_RHS] = sharedOf(ruleRhs[r]);
            }
            q = rq;
        }
        for (int m = 0; m < n; m++) {
            o.complete(cids[m], sharedOf(mType[m]), 0, 0);
        }
        o.unitClaims(bl, need);
        o.endUnit();
        nConstants += n;
        nUnits++;
        endSegment();
        unitDone();
    }
}
