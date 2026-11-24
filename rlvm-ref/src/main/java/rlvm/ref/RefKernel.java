package rlvm.ref;

import java.util.Arrays;
import rlvm.api.Budget;
import rlvm.api.EngineConfig;
import rlvm.api.KernelException;
import rlvm.api.KernelOps;
import rlvm.api.LevelOps;
import rlvm.api.NatOps;
import rlvm.api.Reason;
import rlvm.api.ResourceException;
import rlvm.api.RunSignals;
import rlvm.api.WorkerContext;
import rlvm.store.DeclKind;
import rlvm.store.DeclTable;
import rlvm.store.IntLists;
import rlvm.store.Kind;
import rlvm.store.NameTable;
import rlvm.store.Node;
import rlvm.store.Overlay;
import rlvm.store.Specials;
import rlvm.store.Store;
import rlvm.store.TermOps;
import rlvm.store.Terms;

/**
 * The official type checker ({@code refs/lean4/src/kernel/type_checker.cpp} at v4.36.0, specified in
 * {@code docs/research/02-kernel-typechecker.md}) on term ids: locally nameless (binders are opened
 * with FVAR nodes of the worker's overlay), substitution-based, with the official order of every
 * strategy decision in {@code is_def_eq} and lazy delta reduction. Method names follow the C++
 * source; comments cite its functions.
 *
 * <p><b>Canonical terms.</b> The official caches and every {@code t == s} are structural. Here every
 * term the kernel works on is first copied into the worker's overlay through the hash-consing
 * constructors ({@link #canon}); from then on structural equality is id equality and the seven caches
 * are keyed by id. Everything the substitution algebra builds from canonical terms is canonical
 * again. Two literals with the same value may still have different ids; they are compared by value.
 *
 * <p><b>Depth.</b> Recursive like the original, except that the tail calls of {@code whnf_core} and
 * {@code is_def_eq_core} and the walk down an application spine in check mode are loops. The four
 * recursive entry points count depth and raise {@code ResourceException(STACK_DEPTH)} at the limit;
 * a {@code StackOverflowError} is caught at the public entry points as a backstop.
 *
 * <p><b>Imports that outlive a declaration.</b> Every declaration needs the types, values and
 * recursor rules of the constants it mentions as canonical terms under the universe levels of the
 * reference; building those copies was a fifth of the kernel's time. A copy is a function of the
 * store and of the level list alone, so a session keeps the ones that many declarations ask for
 * ({@link ImportCache}): after {@link RefOptions#importMinUses} declarations had to build the same
 * copy, {@link #promoteImports} builds it once more below the next declaration's overlay mark, where
 * no {@code Overlay.resetTo} of a declaration reaches it, and from then on {@link #importSlow}
 * answers from the table. The copies are ordinary hash-consed overlay nodes, so a cached id is
 * exactly the id a fresh copy would get; nothing the official algorithm computes is remembered.
 * The table dies with {@link #reset()} and whenever the overlay turns out to have been cut below
 * the cached copies (a sentinel node with a random payload is checked at every entry). Engines in
 * strict mode do not use it.
 *
 * <p>One instance per session; not thread-safe. After any {@link ResourceException} the caller must
 * reset the session and its {@link WorkerContext} (the overlay may have been abandoned mid-update).
 */
public final class RefKernel implements KernelOps {

    /**
     * Depth limit when the caller sets none: what a 1 GiB stack holds with a margin. Measured on
     * HotSpot (DepthProbe): 211 bytes per level on the whnf and is_def_eq paths, less on the infer
     * path, i.e. 5 million levels in 1 GiB. The official kernel runs on 1 GB threads as well and the
     * arena needs 524,300 levels (perf/unfold-before-literal-bad evaluates 2^19 nested additions).
     */
    static final int DEEP_DEPTH = Integer.getInteger("rlvm.ref.deepDepth", 1 << 21);
    /** Longest chain of head reductions inside one {@code whnf_core} (the official kernel recurses there). */
    static final int CHAIN_LIMIT = Integer.getInteger("rlvm.ref.chainLimit", 1 << 22);

    private static final int UNDEF = 0;
    private static final int TRUE = 1;
    private static final int FALSE = 2;

    private static final int CONTINUE = 0;
    private static final int UNKNOWN = 1;
    private static final int EQUAL = 2;
    private static final int DIFF = 3;

    /** {@link #lparams} value for "do not check universe parameters" (official: {@code m_lparams == nullptr}). */
    private static final int NO_LPARAMS = -1;

    private static final int OP_INFER = 0;
    private static final int OP_CHECK = 1;
    private static final int OP_WHNF = 2;
    private static final int OP_WHNF_CORE = 3;
    private static final int OP_DEF_EQ = 4;
    private static final int OP_ENSURE_SORT = 5;
    private static final int OP_ENSURE_PI = 6;
    private static final int OP_IS_PROP = 7;
    private static final int OP_CHECK_CONSTANT = 8;
    private static final int OP_MK_LOCAL = 9;

    private final WorkerContext ctx;
    private final Store store;
    private final Terms terms;
    private final Overlay ov;
    private final TermOps ops;
    private final DeclTable decls;
    private final Specials specials;
    private final NameTable names;
    private final IntLists lists;
    private final LevelOps levels;
    private final NatOps nats;
    private final RunSignals signals;

    // ---- scope of the current declaration ----------------------------------------------------------
    private int rank = Integer.MAX_VALUE;
    private int lparams = NO_LPARAMS;
    private int exposeMask;
    /** Unit the scope belongs to ({@link #begin}: rank - 1; {@link #checkConstant}: the constant's), or -1: no cancellation polling. */
    private int unit = -1;

    // ---- the official caches (type_checker.h:25-49), keyed by canonical term id -----------------------
    private final IntIntMap inferCheckCache = new IntIntMap();
    private final IntIntMap inferOnlyCache = new IntIntMap();
    /**
     * Types of application prefixes that {@link #inferAppCheck} has determined but not built:
     * pairs (remaining type with loose variables, number of trailing arguments to substitute). An
     * entry {@code -(i + 1)} of {@link #inferCheckCache} stands for pair {@code i}; {@link #forcePrefix}
     * turns it into the term the first time somebody asks.
     */
    private int[] prefixTypes = new int[64];
    private int prefixCount;
    private final IntIntMap whnfCoreCache = new IntIntMap();
    private final IntIntMap whnfCache = new IntIntMap();
    private final PairSet success = new PairSet();
    private final PairSet failure = new PairSet();
    private final IntIntMap unfoldCache = new IntIntMap();
    /**
     * {@code e -> whnf_core(unfold_definition(e))} for applications of a definition whose value is a
     * lambda: the official {@code m_whnf_core} entry of the intermediate term {@code value a1 .. an},
     * which is a function of {@code e} and is not built here (see {@link #unfoldWhnfCore}).
     */
    private final IntIntMap unfoldWhnfCache = new IntIntMap();
    /**
     * Lambda value of a definition -> the first constant reference with that value that was unfolded
     * under arguments in this declaration. The official key of an {@link #unfoldWhnfCache} entry is
     * the term {@code value a1 .. an}; it is kept under {@code c a1 .. an} for THIS reference c, so
     * that two constants with one value (or one constant at two level lists that instantiate to one
     * value) share their entries as they do officially.
     */
    private final IntIntMap valueOwner = new IntIntMap();
    /**
     * Lambdas that are the head of an explicit beta redex for which a full {@code whnf_core} may have
     * written an {@link #whnfCoreCache} entry. If such a lambda is also the value of a definition,
     * the unfolding of that definition has to look the explicit term up ({@link #unfoldWhnfCore}).
     */
    private final IntIntMap explicitLam = new IntIntMap();

    // ---- own tables -----------------------------------------------------------------------------------
    /** Any term id -> its canonical copy. */
    private final IntIntMap canonMemo = new IntIntMap();
    /** Let-bound free variable -> its value (the official local context's let declarations). */
    private final IntIntMap letValues = new IntIntMap();
    /** (stored term, CONST node carrying the universe instantiation) -> canonical instantiated term. */
    private final LongIntMap instCache = new LongIntMap();
    /** Per-call memo of {@link #canonInst}. */
    private final IntIntMap instMemo = new IntIntMap();

    // ---- imports that outlive a declaration (see the class comment) ------------------------------------
    /** Null when the session keeps nothing between declarations (strict engines, {@code ref.imports=off}). */
    private final ImportCache imports;
    private final int importMinUses;
    private final int importMaxNodes;
    private final boolean importVerify;
    /** Overlay nodes spent on kept copies since the table was last emptied. */
    private int importNodes;
    /** Overlay node count right after the last promotion; the kept copies lie at or below it. */
    private int importTop;
    /** A NATLIT node holding {@link #importNonce}, made right after the last promotion; 0 = none. */
    private int importSentinel;
    private long importNonce;
    private long nonceState;
    /** True inside a declaration whose end the session reports ({@link #checkConstant}): misses are counted. */
    private boolean counting;
    /** Keys (constant, sub-term, level list) to promote when the declaration ends. */
    private int[] pending = new int[48];
    private int pendingCount;
    /** Set by {@link #copy} when it resolved a late-bound reference by name. */
    private boolean copyLateBound;
    private long statImportHits;
    private long statImportMisses;
    private long statImportPromoted;
    private long statImportDrops;
    private long statImportNodes;

    /**
     * Universe levels are compared with the official normal-form test (the default, {@link RefOptions});
     * false = the complete relation, the opt-in {@code -X levels=complete}.
     */
    private final boolean officialLevels;

    // ---- work stacks (argument buffers, telescopes, pending cache keys) -------------------------------
    private int[] stk = new int[1024];
    private int sp;
    private int[] lstk = new int[64];
    private int lsp;

    // ---- budget ---------------------------------------------------------------------------------------
    private long steps;
    private long stepLimit = Long.MAX_VALUE;
    private long byteLimit = Long.MAX_VALUE;
    private long bytesAtBegin;
    private int depth;
    private int depthLimit = DEEP_DEPTH;
    private int maxDepthSeen;

    private boolean eagerReduce;
    /** Out-parameters of {@link #lazyDeltaStep} and {@link #lazyDeltaReduction} (the official {@code expr &}). */
    private int outT;
    private int outS;
    /**
     * Object identity (README, "Object identity"). A stuck projection is FRESH while the object the
     * official kernel holds for it is not the object of an {@code m_whnf_core} entry: the cheap
     * {@code whnf_core} computed it as stuck by itself (no entry answered) and no full
     * {@code whnf_core} of this very object has written the entry since. {@link #wcFresh} is that
     * fact about the result of the last {@link #whnfCore} / {@link #unfoldWhnfCore} call;
     * {@link #outTFresh} / {@link #outSFresh} go with {@link #outT} / {@link #outS}.
     */
    private boolean wcFresh;
    private boolean outTFresh;
    private boolean outSFresh;
    /** {@link RefOptions#identity}. */
    private final int identityMode;
    /**
     * Probe mode ({@link #secondPassRepeatsNothing}): the judgments run as usual, but anything that
     * would add a fact to an official cache whose content depends on when it was computed
     * ({@code m_whnf_core}, the success and failure sets) ends the probe with {@link ProbeAbort}.
     * Function memos (inferred types, {@code m_whnf} of a term whose reduction needed no such
     * entry, {@code m_unfold}) may be written: they hold what any later computation would find.
     */
    private boolean frozen;
    /** Diagnostics: why the last probe failed (which cache it would have written, or which step decided). */
    private String probeWhy = "";

    /** Ends a probe (no stack trace: it is control flow). */
    private static final class ProbeAbort extends RuntimeException {
        private static final long serialVersionUID = 1L;
        static final ProbeAbort INSTANCE = new ProbeAbort();

        private ProbeAbort() {
            super("probe", null, false, false);
        }
    }
    /**
     * Object-identity events (see {@link #isDefEqCore}, step 10): [0] all; [1] harmless: the official
     * second pass was run as a probe and repeats nothing but cache hits (only counted when the mode
     * probes); of the others, by what the second pass would do: [2] a K-like recursor application is
     * reduced again, [3] the type of a term that lazy delta produced is inferred, [4] the same
     * projection of two structures is compared again; [5] a nested call stored the entry of the
     * projection a full whnf_core was working on.
     */
    private final long[] statIdentity = new long[6];
    /** Number of events to describe on stderr ({@code -Drlvm.ref.identityTrace=N}, diagnostics). */
    private int identityTrace = Integer.getInteger("rlvm.ref.identityTrace", 0);
    private static final int HAZARD_K = 1;
    private static final int HAZARD_INFER = 2;
    private static final int HAZARD_PROJ = 4;
    /** Set when a judgment was declined for that reason, for the session's message. */
    private boolean identityDeclined;
    /**
     * Strict mode, {@link RefEngine.RefSession#check}: the decisions for the first
     * {@link #scriptLength} object-identity hazards of the declaration (bit i set = hazard i is taken
     * as "another object": the official second pass runs). A hazard beyond the script ends the
     * judgment with UNSUPPORTED_INPUT and {@link #identityFork} set: the session then checks the
     * declaration once per decision and gives a verdict only if both agree.
     */
    private long identityScript;
    private int scriptLength = -1;
    /** Hazards met since the declaration began. */
    private int hazardIndex;
    private boolean identityFork;

    /** Set when a StackOverflowError was caught: the worker context may have been left mid-update. */
    private boolean overflowed;
    /** Set by {@link #nonTermination()}. */
    private boolean cycled;

    private int[] ovArr;
    private int lastNodeCount;
    private int dontCare;
    private int levelZeroList;

    public RefKernel(WorkerContext ctx, EngineConfig config) {
        this(ctx, config, RefOptions.fromSystemProperties());
    }

    public RefKernel(WorkerContext ctx, EngineConfig config, RefOptions options) {
        if (config != null && config.profile != EngineConfig.KernelProfile.LEAN_4_36) {
            throw new UnsupportedOperationException(
                    "rlvm-ref implements the v4.36 kernel only; profile " + config.profile + " is deferred");
        }
        this.officialLevels = options.officialLevels;
        this.identityMode = options.identity;
        // Strict mode: no cache that survives a unit (RECONCILIATION.md section 2, facade).
        boolean keep = options.importBytes >= 32L && (config == null || !config.strict);
        this.imports = keep ? new ImportCache() : null;
        this.importMinUses = options.importMinUses;
        this.importMaxNodes = (int) Math.min(options.importBytes >>> 5, Integer.MAX_VALUE);
        this.importVerify = options.importVerify || (config != null && config.memoCheck);
        this.nonceState = System.nanoTime() ^ ((long) System.identityHashCode(this) << 32) ^ 0x9E3779B97F4A7C15L;
        this.ctx = ctx;
        this.store = ctx.store;
        this.terms = ctx.terms;
        this.ov = ctx.overlay;
        this.lists = ctx.lists;
        this.levels = ctx.levels;
        this.nats = ctx.nats;
        this.signals = ctx.signals == null ? RunSignals.NONE : ctx.signals;
        this.decls = store.decls();
        this.specials = store.specials();
        this.names = store.names();
        this.ops = new TermOps(terms, lists);
    }

    // =====================================================================================================
    // KernelOps
    // =====================================================================================================

    @Override
    public void begin(int rank, int levelParams) {
        this.rank = rank;
        this.lparams = levelParams;
        this.exposeMask = 0;
        // Ranks are 1-based positions in admission order (rank = unit + 1 for exporter output), so the
        // judgments of this scope belong to unit rank - 1: they are abandoned once an earlier unit
        // has failed. A scope "after everything" (tests, tools) is never cancelled.
        this.unit = rank > 0 && rank != Integer.MAX_VALUE ? rank - 1 : -1;
        this.counting = false;
        this.hazardIndex = 0;
        clearAll();
        terms.refresh();
        ovArr = ov.array();
        lastNodeCount = ov.nodeCount();
        bytesAtBegin = footprintBytes();
    }

    @Override
    public void exposeUnitMembers(int kindMask) {
        exposeMask |= kindMask;
        // The environment grew: facts that depend on a constant being absent (a stuck recursor
        // application, a failed comparison) must go, as the official add_inductive creates a fresh
        // type_checker for every phase.
        inferCheckCache.clear();
        prefixCount = 0;
        inferOnlyCache.clear();
        whnfCoreCache.clear();
        whnfCache.clear();
        unfoldWhnfCache.clear();
        explicitLam.clear();
        success.clear();
        failure.clear();
    }

    @Override
    public int infer(int term) {
        return guarded(OP_INFER, term, 0);
    }

    @Override
    public int check(int term) {
        return guarded(OP_CHECK, term, 0);
    }

    @Override
    public int whnf(int term) {
        return guarded(OP_WHNF, term, 0);
    }

    @Override
    public int whnfCore(int term) {
        return guarded(OP_WHNF_CORE, term, 0);
    }

    @Override
    public boolean isDefEq(int a, int b) {
        return guarded(OP_DEF_EQ, a, b) != 0;
    }

    @Override
    public int ensureSort(int term) {
        return guarded(OP_ENSURE_SORT, term, 0);
    }

    @Override
    public int ensurePi(int term) {
        return guarded(OP_ENSURE_PI, term, 0);
    }

    @Override
    public boolean isProp(int type) {
        return guarded(OP_IS_PROP, type, 0) != 0;
    }

    @Override
    public int mkLocal(int type) {
        return guarded(OP_MK_LOCAL, type, 0);
    }

    @Override
    public Terms terms() {
        return terms;
    }

    @Override
    public TermOps termOps() {
        return ops;
    }

    @Override
    public LevelOps levels() {
        return levels;
    }

    @Override
    public boolean whnfIsSyntactic() {
        return true;
    }

    @Override
    public long steps() {
        return steps;
    }

    /** Sets the step, byte and depth limits for the judgments that follow (see {@link Budget}). */
    public void setBudget(Budget budget) {
        long max = budget.maxSteps;
        stepLimit = max >= Long.MAX_VALUE - steps ? Long.MAX_VALUE : steps + max;
        byteLimit = budget.maxBytes;
        depthLimit = Math.min(budget.maxDepth, DEEP_DEPTH);
    }

    /**
     * The official {@code add_axiom / add_definition / add_theorem / add_opaque} body for constant
     * {@code cid} (ARCHITECTURE.md 4.4), after the structural checks. Opens the constant's scope
     * itself ({@link #begin}). Throws {@link KernelException} on rejection.
     */
    public void checkConstant(int cid) {
        guarded(OP_CHECK_CONSTANT, cid, 0);
    }

    /** Forgets everything, the session's imports included (session reset; also required after the overlay was reset). */
    public void reset() {
        dropImports();
        endDeclaration();
    }

    /**
     * Forgets what the declaration that just ended learned; the session's imports stay. The caller
     * then cuts the overlay back to the declaration's mark and calls {@link #promoteImports}.
     */
    public void endDeclaration() {
        clearAll();
        counting = false;
        rank = Integer.MAX_VALUE;
        lparams = NO_LPARAMS;
        exposeMask = 0;
        unit = -1;
        sp = 0;
        lsp = 0;
        depth = 0;
        eagerReduce = false;
        lastNodeCount = 0;
    }

    /** True if a judgment since the last {@link #takeOverflowed} ended in a caught StackOverflowError. */
    boolean peekOverflowed() {
        return overflowed;
    }

    boolean takeOverflowed() {
        boolean r = overflowed;
        overflowed = false;
        return r;
    }

    /** Bytes of the kernel's own tables (the overlay is accounted by the {@link WorkerContext}). */
    public long tableBytes() {
        return inferCheckCache.bytes() + inferOnlyCache.bytes() + whnfCoreCache.bytes() + whnfCache.bytes()
                + success.bytes() + failure.bytes() + unfoldCache.bytes() + unfoldWhnfCache.bytes()
                + valueOwner.bytes() + explicitLam.bytes() + canonMemo.bytes()
                + letValues.bytes()
                + instCache.bytes() + instMemo.bytes() + ((long) stk.length << 2) + ((long) lstk.length << 2)
                + ((long) prefixTypes.length << 2)
                + (imports == null ? 0L : imports.bytes() + ((long) pending.length << 2));
    }

    /** Deepest recursion seen since the kernel was created (statistics). */
    public int maxDepthSeen() {
        return maxDepthSeen;
    }

    private long footprintBytes() {
        return ov.usedBytes() + tableBytes();
    }

    private void clearAll() {
        inferCheckCache.clear();
        prefixCount = 0;
        if (prefixTypes.length > (1 << 16)) {
            prefixTypes = new int[64];
        }
        inferOnlyCache.clear();
        whnfCoreCache.clear();
        whnfCache.clear();
        unfoldWhnfCache.clear();
        valueOwner.clear();
        explicitLam.clear();
        success.clear();
        failure.clear();
        unfoldCache.clear();
        canonMemo.clear();
        letValues.clear();
        instCache.clear();
        instMemo.clear();
        dontCare = 0;
        levelZeroList = 0;
        if (stk.length > (1 << 16)) {
            stk = new int[1024];
        }
        if (lstk.length > (1 << 12)) {
            lstk = new int[64];
        }
    }

    /** Runs one public judgment: restores the work stacks on any exit and converts resource failures. */
    private int guarded(int op, int a, int b) {
        if (ov.nodeCount() < lastNodeCount) {
            // The overlay was reset behind our back: every cached id is stale.
            clearAll();
        }
        sync();
        if (importSentinel != 0 && !importsIntact()) {
            dropImports();
        }
        int sp0 = sp;
        int lsp0 = lsp;
        int depth0 = depth;
        boolean eager0 = eagerReduce;
        try {
            switch (op) {
                case OP_INFER:
                    return inferCore(canon(a), true);
                case OP_CHECK:
                    return inferCore(canon(a), false);
                case OP_WHNF: {
                    int c = canon(a);
                    int r = whnfFull(c);
                    return r == c ? a : r;
                }
                case OP_WHNF_CORE: {
                    int c = canon(a);
                    int r = whnfCore(c, false);
                    return r == c ? a : r;
                }
                case OP_DEF_EQ:
                    return isDefEqInt(canon(a), canon(b)) ? 1 : 0;
                case OP_ENSURE_SORT:
                    return terms.a(ensureSortCore(canon(a)));
                case OP_ENSURE_PI: {
                    int c = canon(a);
                    int r = ensurePiCore(c);
                    return r == c ? a : r;
                }
                case OP_IS_PROP:
                    return isPropInt(canon(a)) ? 1 : 0;
                case OP_CHECK_CONSTANT:
                    checkConstantInt(a);
                    return 0;
                case OP_MK_LOCAL:
                    return newFVar(canon(a));
                default:
                    throw new IllegalStateException("op " + op);
            }
        } catch (StackOverflowError overflow) {
            // Backstop only: the depth account is meant to end first. Whatever was being written is
            // abandoned, so nothing learned so far is kept.
            overflowed = true;
            clearAll();
            dropImports();
            throw new ResourceException(Reason.STACK_DEPTH);
        } catch (Overlay.OverlayFullException full) {
            throw new ResourceException(Reason.BUDGET_MEMORY);
        } finally {
            sp = sp0;
            lsp = lsp0;
            depth = depth0;
            eagerReduce = eager0;
            lastNodeCount = ov.nodeCount();
        }
    }

    // =====================================================================================================
    // Declarations (environment.cpp:126-222)
    // =====================================================================================================

    private void checkConstantInt(int cid) {
        int kind = decls.kind(cid);
        if (kind != DeclKind.AXIOM && kind != DeclKind.DEFN && kind != DeclKind.THM && kind != DeclKind.OPAQUE) {
            throw new IllegalArgumentException("checkConstant: constant " + cid + " of kind " + kind
                    + " is admitted by rlvm-inductive / rlvm-admit, not by the engine");
        }
        begin(decls.rank(cid), decls.levelParams(cid));
        unit = decls.unit(cid);
        pendingCount = 0;
        counting = imports != null;
        // check_constant_val: the type is well typed and its type is (or reduces to) a sort.
        int type = canon(decls.type(cid));
        int sort = inferCore(type, false);
        ensureSortCore(sort);
        if (kind == DeclKind.AXIOM) {
            return;
        }
        if (kind == DeclKind.THM && !isPropInt(type)) {
            throw new KernelException(Reason.THEOREM_NOT_PROP, type, 0);
        }
        int value = canon(valueRoot(cid));
        int valueType = inferCore(value, false);
        if (!isDefEqInt(valueType, type)) {
            throw new KernelException(Reason.DECL_TYPE_MISMATCH, valueType, type);
        }
    }

    /** The value of a definition, theorem or opaque as a term id (decoding its proof block if needed). */
    private int valueRoot(int cid) {
        int v = decls.value(cid);
        if (v > 0) {
            return v;
        }
        if (v < 0) {
            int root = ov.blockRoot(-v - 1);
            sync();
            return root;
        }
        throw new KernelException(Reason.MALFORMED_EXPORT);
    }

    // =====================================================================================================
    // Environment access
    // =====================================================================================================

    /** The official "is in the environment": rank below the scope's, or an exposed member of its own unit. */
    private boolean visible(int cid) {
        int r = decls.rank(cid);
        if (r < rank) {
            return true;
        }
        return r == rank && ((exposeMask >>> decls.kind(cid)) & 1) != 0;
    }

    /**
     * Constant id of special {@code which}; 0 if it is not declared or not visible (the official kernel
     * then finds no such constant). Declines if its shape guard failed.
     */
    private int special(int which) {
        int cid = specials.cid(which);
        if (cid == 0 || !visible(cid)) {
            return 0;
        }
        if (specials.state(which) != Specials.VALID) {
            throw new ResourceException(Reason.UNSUPPORTED_PRELUDE);
        }
        return cid;
    }

    /** Special index of a {@code Const name []} node (the official {@code f == *g_nat_add} tests), or -1. */
    private int specialOf(int constTerm) {
        if (terms.b(constTerm) != IntLists.EMPTY) {
            return -1;
        }
        int cid = terms.a(constTerm);
        return cid > 0 ? specials.which(cid) : -1;
    }

    private boolean isSpecialConst(int term, int which) {
        return terms.kind(term) == Kind.CONST && specialOf(term) == which;
    }

    private void requireValid(int which) {
        if (specials.state(which) != Specials.VALID) {
            throw new ResourceException(Reason.UNSUPPORTED_PRELUDE);
        }
    }

    private boolean hasFVar(int t) {
        return (terms.header(t) & Node.F_HAS_FVAR) != 0;
    }

    // =====================================================================================================
    // Term construction (every constructor may move the overlay's array)
    // =====================================================================================================

    private void sync() {
        int[] a = ov.array();
        if (a != ovArr) {
            ovArr = a;
            terms.refresh();
        }
    }

    private int mkApp(int f, int a) {
        int r = ov.mkApp(f, a);
        sync();
        return r;
    }

    private int mkApps(int f, int from, int n) {
        if (n == 0) {
            return f;
        }
        int r = ov.mkApps(f, stk, from, n);
        sync();
        return r;
    }

    private int mkConst(int cid, int levelList) {
        int r = ov.mkConst(cid, levelList);
        sync();
        return r;
    }

    private int mkSort(int level) {
        int r = ov.mkSort(level);
        sync();
        return r;
    }

    private int mkProj(int structCid, int index, int struct) {
        int r = ov.mkProj(structCid, index, struct);
        sync();
        return r;
    }

    private int mkNatLit(long value) {
        int r = ov.mkNatLit(value);
        sync();
        return r;
    }

    private int newFVar(int type) {
        int r = ov.mkFVar(type);
        sync();
        canonMemo.put(type, type);
        return r;
    }

    private int inst1(int body, int arg) {
        if (terms.looseRange(body) == 0) {
            return body;
        }
        int r = ops.instantiate1(body, arg);
        sync();
        return r;
    }

    /** {@code instantiate_rev(t, n, stk + from)}: #0 := stk[from + n - 1]. */
    private int instRev(int t, int from, int n) {
        if (n == 0 || terms.looseRange(t) == 0) {
            return t;
        }
        int r = ops.instantiateRev(t, stk, from, n);
        sync();
        return r;
    }

    private int abstractFVars(int t, int from, int n) {
        if (n == 0 || !hasFVar(t)) {
            return t;
        }
        int r = ops.abstractFVars(t, stk, from, n);
        sync();
        return r;
    }

    private int alloc(int n) {
        int at = sp;
        int need = at + n;
        if (need > stk.length) {
            stk = Arrays.copyOf(stk, Math.max(stk.length << 1, need + 64));
        }
        sp = need;
        return at;
    }

    private void push(int v) {
        if (sp == stk.length) {
            stk = Arrays.copyOf(stk, stk.length << 1);
        }
        stk[sp++] = v;
    }

    private void lpush(int v) {
        if (lsp == lstk.length) {
            lstk = Arrays.copyOf(lstk, lstk.length << 1);
        }
        lstk[lsp++] = v;
    }

    /** The {@code i}-th argument (0-based) of an application with {@code nargs} arguments. */
    private int nthArg(int app, int nargs, int i) {
        int t = app;
        for (int j = nargs - 1; j > i; j--) {
            t = terms.a(t);
        }
        return terms.b(t);
    }

    /**
     * The canonical copy of a term: the same structure built with the overlay's hash-consing
     * constructors, so that structural equality of canonical terms is id equality. Late-bound
     * constant references are resolved by name here. Iterative.
     */
    private int canon(int t) {
        int done = canonMemo.get(t);
        if (done != 0) {
            return done;
        }
        return copy(t, canonMemo, 0, 0, true);
    }

    /**
     * The canonical copy of {@code t} with its universe parameters instantiated: {@link #canon}
     * followed by the official {@code instantiate_lparams} (instantiate.cpp:216-230; levels of SORT
     * and CONST nodes go through the simplifying constructors), in one pass.
     */
    private int canonInst(int t, int paramNames, int levelList) {
        instMemo.clear();
        return copy(t, instMemo, paramNames, levelList, false);
    }

    /**
     * Post-order copy of the DAG below {@code t} through the hash-consing constructors, memoised in
     * {@code memo}; with {@code plain} the levels are kept, otherwise {@code paramNames := levelList}
     * is applied to them. A non-negative stack entry is a term to visit, {@code ~n} a term whose
     * children are done.
     */
    private int copy(int t, IntIntMap memo, int paramNames, int levelList, boolean plain) {
        int base = sp;
        push(t);
        while (sp > base) {
            int n = stk[--sp];
            int r;
            if (n >= 0) {
                if (memo.get(n) != 0) {
                    continue;
                }
                int k = terms.kind(n);
                switch (k) {
                    case Kind.APP:
                    case Kind.LAM:
                    case Kind.PI:
                        push(~n);
                        push(terms.b(n));
                        push(terms.a(n));
                        continue;
                    case Kind.LET:
                        push(~n);
                        push(terms.c(n));
                        push(terms.b(n));
                        push(terms.a(n));
                        continue;
                    case Kind.PROJ:
                        push(~n);
                        push(terms.a(n));
                        continue;
                    case Kind.BVAR:
                        r = ov.mkBVar(terms.a(n));
                        break;
                    case Kind.SORT: {
                        int l = terms.a(n);
                        r = ov.mkSort(plain ? l : levels.instantiate(l, paramNames, levelList));
                        break;
                    }
                    case Kind.CONST: {
                        int cid = terms.a(n);
                        if (cid <= 0) {
                            copyLateBound = true;
                            cid = cid == 0 ? 0 : names.constOf(~cid);
                            if (cid <= 0) {
                                throw new KernelException(Reason.UNKNOWN_CONSTANT, n, 0);
                            }
                        }
                        int ls = terms.b(n);
                        r = ov.mkConst(cid,
                                plain || ls == IntLists.EMPTY ? ls : levels.instantiateList(ls, paramNames, levelList));
                        break;
                    }
                    case Kind.NATLIT:
                        r = (terms.header(n) & Node.F_NAT_BIG) != 0 ? ov.mkNatLitBig(terms.a(n))
                                : ov.mkNatLit(terms.natSmall(n));
                        break;
                    case Kind.STRLIT:
                        r = ov.mkStrLit(terms.a(n));
                        break;
                    case Kind.FVAR:
                        r = n;
                        break;
                    default:
                        throw new IllegalStateException("not a term node: id " + n + " kind " + k);
                }
            } else {
                n = ~n;
                if (memo.get(n) != 0) {
                    continue;
                }
                int k = terms.kind(n);
                switch (k) {
                    case Kind.APP:
                        r = ov.mkApp(memo.get(terms.a(n)), memo.get(terms.b(n)));
                        break;
                    case Kind.LAM:
                        r = ov.mkLam(memo.get(terms.a(n)), memo.get(terms.b(n)));
                        break;
                    case Kind.PI:
                        r = ov.mkPi(memo.get(terms.a(n)), memo.get(terms.b(n)));
                        break;
                    case Kind.LET:
                        r = ov.mkLet(memo.get(terms.a(n)), memo.get(terms.b(n)), memo.get(terms.c(n)));
                        break;
                    default: {
                        int scid = terms.c(n);
                        if (scid <= 0) {
                            copyLateBound = true;
                            scid = scid == 0 ? 0 : names.constOf(~scid);
                            if (scid <= 0) {
                                throw new KernelException(Reason.INVALID_PROJECTION, n, 0);
                            }
                        }
                        r = ov.mkProj(scid, terms.b(n), memo.get(terms.a(n)));
                        break;
                    }
                }
            }
            sync();
            memo.put(n, r);
            if (plain && r != n) {
                // The copy is canonical: it maps to itself.
                memo.put(r, r);
            }
        }
        return memo.get(t);
    }

    /**
     * A term of constant {@code cid} (its type or a recursor rule, as stored; {@code sub} says which,
     * see {@link ImportCache}) as a canonical term under the universe instantiation carried by the
     * CONST node {@code constTerm}; the caller has checked the arity.
     */
    private int importFor(int storedTerm, int cid, int sub, int constTerm) {
        int ls = terms.b(constTerm);
        if (ls == IntLists.EMPTY) {
            int r = canonMemo.get(storedTerm);
            return r != 0 ? r : importSlow(storedTerm, cid, sub, ls);
        }
        long key = LongIntMap.key(storedTerm, constTerm);
        int r = instCache.get(key);
        if (r == 0) {
            r = importSlow(storedTerm, cid, sub, ls);
            instCache.put(key, r);
        }
        return r;
    }

    /**
     * The canonical copy of {@code storedTerm} (term {@code sub} of constant {@code cid}) under the
     * level list {@code ls}, for a declaration that has not imported it yet: from the session's
     * table if a copy is kept there, otherwise built here. A declaration that has to build it is
     * counted; at {@link #importMinUses} the key is queued for {@link #promoteImports}.
     */
    private int importSlow(int storedTerm, int cid, int sub, int ls) {
        ImportCache ic = imports;
        if (ic == null || storedTerm >= Node.OVERLAY_BIT) {
            // Nothing is kept, or the term lives in a decoded proof block of this declaration.
            return ls == IntLists.EMPTY ? canon(storedTerm) : canonInst(storedTerm, decls.levelParams(cid), ls);
        }
        int r = ic.get(cid, sub, ls);
        if (r != 0) {
            statImportHits++;
            if (importVerify) {
                int again = ls == IntLists.EMPTY ? canon(storedTerm) : canonInst(storedTerm, decls.levelParams(cid), ls);
                if (again != r) {
                    throw new IllegalStateException("import cache: constant " + cid + " term " + sub + " levels " + ls
                            + " is cached as " + r + " but copies to " + again);
                }
            }
            if (ls == IntLists.EMPTY) {
                canonMemo.put(storedTerm, r);
            }
            return r;
        }
        r = ls == IntLists.EMPTY ? canon(storedTerm) : canonInst(storedTerm, decls.levelParams(cid), ls);
        if (counting && (terms.header(storedTerm) & Node.F_UNRESOLVED) == 0) {
            statImportMisses++;
            if (importNodes < importMaxNodes && ic.miss(cid, sub, ls) == importMinUses) {
                if (pendingCount + 3 > pending.length) {
                    pending = Arrays.copyOf(pending, pending.length << 1);
                }
                pending[pendingCount] = cid;
                pending[pendingCount + 1] = sub;
                pending[pendingCount + 2] = ls;
                pendingCount += 3;
            }
        }
        return r;
    }

    /** True if the copies the session keeps are still in the overlay (it was not reset or cut below them). */
    private boolean importsIntact() {
        int s = importSentinel;
        return ov.nodeCount() >= importTop && terms.kind(s) == Kind.NATLIT
                && (terms.header(s) & Node.F_NAT_BIG) == 0 && terms.natSmall(s) == importNonce;
    }

    private void dropImports() {
        if (imports != null) {
            if (imports.size() != 0) {
                statImportDrops++;
            }
            imports.clear();
        }
        statImportNodes += importNodes;
        importNodes = 0;
        importTop = 0;
        importSentinel = 0;
        pendingCount = 0;
    }

    /**
     * Builds the copies queued by the declaration that just ended, where they outlive the following
     * declarations. Call it after {@link #endDeclaration()} and after the overlay was cut back to the
     * declaration's mark ({@code Overlay.resetTo}), and only if the declaration was accepted; with
     * {@code accepted == false} the queue is dropped.
     */
    public void promoteImports(boolean accepted) {
        ImportCache ic = imports;
        int n = pendingCount;
        pendingCount = 0;
        if (ic == null || n == 0 || !accepted) {
            return;
        }
        sync();
        if (importSentinel != 0 && !importsIntact()) {
            dropImports();
            return;
        }
        int before = ov.nodeCount();
        int sp0 = sp;
        boolean any = false;
        try {
            for (int i = 0; i < n && importNodes + (ov.nodeCount() - before) < importMaxNodes; i += 3) {
                int cid = pending[i];
                int sub = pending[i + 1];
                int ls = pending[i + 2];
                int stored = sub == ImportCache.TYPE ? decls.type(cid)
                        : sub == ImportCache.VALUE ? decls.value(cid) : decls.recRuleRhs(cid, sub - ImportCache.RULE);
                if (stored <= 0 || stored >= Node.OVERLAY_BIT || (terms.header(stored) & Node.F_UNRESOLVED) != 0) {
                    continue;
                }
                copyLateBound = false;
                int r;
                if (ls == IntLists.EMPTY) {
                    // A fresh memo per root, so that the walk sees every node (and every late-bound reference).
                    canonMemo.clear();
                    r = copy(stored, canonMemo, 0, 0, true);
                } else {
                    r = canonInst(stored, decls.levelParams(cid), ls);
                }
                if (!copyLateBound && ic.put(cid, sub, ls, r)) {
                    statImportPromoted++;
                    any = true;
                }
            }
            if (any) {
                // The sentinel is the topmost kept node: any cut below the kept copies removes it.
                nonceState = nonceState * 6364136223846793005L + 1442695040888963407L;
                importNonce = nonceState ^ (nonceState >>> 29);
                importSentinel = ov.mkNatLit(importNonce);
                sync();
            }
            importNodes += ov.nodeCount() - before;
            importTop = ov.nodeCount();
        } catch (RuntimeException e) {
            // Whatever was built stays as dead nodes until the session is reset; nothing is trusted.
            // (An Error is not caught: the overlay may then be inconsistent, and the caller fails closed.)
            sp = sp0;
            dropImports();
        } finally {
            canonMemo.clear();
            instMemo.clear();
            lastNodeCount = ov.nodeCount();
        }
    }

    /**
     * Session statistics: {hits, imports a declaration copied itself, keys promoted, times the table
     * was dropped, nodes kept now, nodes kept in all (dropped tables included)}.
     */
    long[] importStats() {
        return new long[] {statImportHits, statImportMisses, statImportPromoted, statImportDrops, importNodes,
            statImportNodes + importNodes};
    }

    private int levelCount(int constTerm) {
        int ls = terms.b(constTerm);
        return ls == IntLists.EMPTY ? 0 : lists.length(ls);
    }

    // =====================================================================================================
    // Budget
    // =====================================================================================================

    private void tick() {
        if ((++steps & 1023L) == 0L) {
            slowTick();
        }
    }

    private void slowTick() {
        if (steps > stepLimit) {
            throw new ResourceException(Reason.BUDGET_STEPS);
        }
        // Cancellation first: a unit behind the first failure reports CANCELLED whatever else is true.
        if (unit >= 0 && signals.firstFailure() < unit) {
            throw new ResourceException(Reason.CANCELLED);
        }
        if (signals.memoryExhausted()) {
            throw new ResourceException(Reason.BUDGET_MEMORY);
        }
        if (byteLimit != Long.MAX_VALUE && footprintBytes() - bytesAtBegin > byteLimit) {
            throw new ResourceException(Reason.BUDGET_MEMORY);
        }
    }

    private void enter() {
        int d = ++depth;
        if (d > depthLimit) {
            throw new ResourceException(Reason.STACK_DEPTH);
        }
        if (d > maxDepthSeen) {
            maxDepthSeen = d;
        }
    }

    // =====================================================================================================
    // infer_type (type_checker.cpp:85-385)
    // =====================================================================================================

    private void checkLevel(int level) {
        if (lparams != NO_LPARAMS && !levels.paramsDeclared(level, lparams)) {
            throw new KernelException(Reason.UNDECLARED_LEVEL_PARAM);
        }
    }

    private int ensureSortCore(int e) {
        if (terms.kind(e) == Kind.SORT) {
            return e;
        }
        int n = whnfFull(e);
        if (terms.kind(n) == Kind.SORT) {
            return n;
        }
        throw new KernelException(Reason.TYPE_EXPECTED, e, 0);
    }

    private int ensurePiCore(int e) {
        if (terms.kind(e) == Kind.PI) {
            return e;
        }
        int n = whnfFull(e);
        if (terms.kind(n) == Kind.PI) {
            return n;
        }
        throw new KernelException(Reason.FUNCTION_EXPECTED, e, 0);
    }

    /** {@code infer_type_core}. */
    private int inferCore(int e, boolean inferOnly) {
        if (terms.looseRange(e) != 0) {
            throw new KernelException(Reason.LOOSE_BVAR, e, 0);
        }
        IntIntMap cache = inferOnly ? inferOnlyCache : inferCheckCache;
        int r = cache.get(e);
        if (r != 0) {
            return r > 0 ? r : forcePrefix(e, r);
        }
        enter();
        tick();
        switch (terms.kind(e)) {
            case Kind.NATLIT:
                r = inferNatLit(e);
                break;
            case Kind.STRLIT:
                r = inferStrLit(e);
                break;
            case Kind.PROJ:
                r = inferProj(e, inferOnly);
                break;
            case Kind.FVAR:
                r = canon(terms.b(e));
                break;
            case Kind.SORT:
                if (!inferOnly) {
                    checkLevel(terms.a(e));
                }
                r = mkSort(levels.succ(terms.a(e)));
                break;
            case Kind.CONST:
                r = inferConstant(e, inferOnly);
                break;
            case Kind.LAM:
                r = inferLambda(e, inferOnly);
                break;
            case Kind.PI:
                r = inferPi(e, inferOnly);
                break;
            case Kind.APP:
                r = inferOnly ? inferAppOnly(e) : inferAppCheck(e);
                break;
            case Kind.LET:
                r = inferLet(e, inferOnly);
                break;
            default:
                throw new IllegalStateException("infer: kind " + terms.kind(e));
        }
        cache.put(e, r);
        depth--;
        return r;
    }

    /** {@code infer_lit} for a Nat literal: {@code Const Nat []}. */
    private int inferNatLit(int e) {
        int cid = special(Specials.NAT);
        if (cid == 0) {
            throw new KernelException(Reason.UNKNOWN_CONSTANT, e, 0);
        }
        nats.checkLiteralSize(e);
        return mkConst(cid, IntLists.EMPTY);
    }

    private int inferStrLit(int e) {
        int cid = special(Specials.STRING);
        if (cid == 0) {
            throw new KernelException(Reason.UNKNOWN_CONSTANT, e, 0);
        }
        return mkConst(cid, IntLists.EMPTY);
    }

    /** {@code infer_constant}. */
    private int inferConstant(int e, boolean inferOnly) {
        int cid = terms.a(e);
        if (cid <= 0 || !visible(cid)) {
            throw new KernelException(Reason.UNKNOWN_CONSTANT, e, 0);
        }
        int ls = terms.b(e);
        int n = ls == IntLists.EMPTY ? 0 : lists.length(ls);
        if (n != decls.numLevelParams(cid)) {
            throw new KernelException(Reason.LEVEL_ARITY_MISMATCH, e, 0);
        }
        if (!inferOnly) {
            // Unsafe and partial constants are not in the store at all (ARCHITECTURE.md 4.5).
            for (int i = 0; i < n; i++) {
                checkLevel(lists.get(ls, i));
            }
        }
        return importFor(decls.type(cid), cid, ImportCache.TYPE, e);
    }

    /** {@code infer_lambda}: the whole telescope is opened at once. */
    private int inferLambda(int lam, boolean inferOnly) {
        int base = sp;
        int e = lam;
        while (terms.kind(e) == Kind.LAM) {
            int d = instRev(terms.a(e), base, sp - base);
            if (!inferOnly) {
                ensureSortCore(inferCore(d, false));
            }
            // The local context is extended only after the domain has been checked.
            int fv = newFVar(d);
            push(fv);
            e = terms.b(e);
        }
        int n = sp - base;
        int r = inferCore(instRev(e, base, n), inferOnly);
        r = cheapBetaReduce(r);
        r = mkPiOver(base, n, r, false);
        sp = base;
        return r;
    }

    /** {@code infer_pi}. */
    private int inferPi(int pi, boolean inferOnly) {
        int base = sp;
        int lbase = lsp;
        int e = pi;
        while (terms.kind(e) == Kind.PI) {
            int d = instRev(terms.a(e), base, sp - base);
            int t1 = ensureSortCore(inferCore(d, inferOnly));
            lpush(terms.a(t1));
            int fv = newFVar(d);
            push(fv);
            e = terms.b(e);
        }
        int n = sp - base;
        e = instRev(e, base, n);
        int s = ensureSortCore(inferCore(e, inferOnly));
        int r = terms.a(s);
        for (int i = n - 1; i >= 0; i--) {
            r = levels.mkIMax(lstk[lbase + i], r);
        }
        sp = base;
        lsp = lbase;
        return mkSort(r);
    }

    /** Is {@code e} of the form {@code eagerReduce _ _}? */
    private boolean isEagerReduce(int e) {
        if (terms.kind(e) != Kind.APP) {
            return false;
        }
        int f = terms.a(e);
        if (terms.kind(f) != Kind.APP) {
            return false;
        }
        int h = terms.a(f);
        if (terms.kind(h) != Kind.CONST) {
            return false;
        }
        int cid = terms.a(h);
        return cid > 0 && specials.which(cid) == Specials.EAGER_REDUCE;
    }

    /**
     * {@code infer_app} in check mode. The official code recurses on the function part, so that every
     * prefix of the spine is checked and cached on its own; this is the same sequence of judgments
     * with the walk down the spine as a loop (an application of 20,000 arguments is 20,000 deep there).
     *
     * <p>The official code also instantiates the whole remaining function type once per argument
     * ({@code instantiate(binding_body(f_type), arg)}), which is what it stores as the type of each
     * prefix. Here the arguments that are not substituted yet stay pending, as in the official
     * infer-only branch: each argument is compared with its own domain under the pending arguments,
     * the result type is built once at the end, and the type of a prefix is built only if it is ever
     * asked for ({@link #prefixTypes}). Every term that is compared, returned or read from the cache
     * is the term the official sequence produces: the arguments are closed, so substituting them one
     * by one and all at once give the same term.
     */
    private int inferAppCheck(int e) {
        int base = sp;
        int cur = e;
        int fType;
        for (;;) {
            push(cur);
            int fn = terms.a(cur);
            if (terms.kind(fn) != Kind.APP) {
                fType = inferCore(fn, false);
                break;
            }
            int cached = inferCheckCache.get(fn);
            if (cached != 0) {
                fType = cached > 0 ? cached : forcePrefix(fn, cached);
                break;
            }
            tick();
            cur = fn;
        }
        int top = sp;
        int n = top - base;
        // The arguments in application order; stk[ab + j .. ab + q) are the pending ones.
        int ab = alloc(n);
        for (int q = 0; q < n; q++) {
            stk[ab + q] = terms.b(stk[top - 1 - q]);
        }
        int j = 0;
        for (int q = 0; q < n; q++) {
            if (terms.kind(fType) != Kind.PI) {
                fType = instRev(fType, ab + j, q - j);
                j = q;
                fType = ensurePiCore(fType);
            }
            int arg = stk[ab + q];
            int aType = inferCore(arg, false);
            int dType = instRev(terms.a(fType), ab + j, q - j);
            boolean ok;
            if (isEagerReduce(arg)) {
                boolean saved = eagerReduce;
                eagerReduce = true;
                ok = isDefEqInt(aType, dType);
                eagerReduce = saved;
            } else {
                ok = isDefEqInt(aType, dType);
            }
            if (!ok) {
                throw new KernelException(Reason.APP_TYPE_MISMATCH, stk[top - 1 - q], aType);
            }
            fType = terms.b(fType);
            if (terms.looseRange(fType) == 0) {
                // Nothing below mentions an argument: the remaining type is the prefix's type as it stands.
                j = q + 1;
                if (q < n - 1) {
                    inferCheckCache.put(stk[top - 1 - q], fType);
                }
            } else if (q < n - 1) {
                inferCheckCache.put(stk[top - 1 - q], suspendPrefix(fType, q + 1 - j));
            }
        }
        int r = instRev(fType, ab + j, n - j);
        sp = base;
        return r;
    }

    /** Records "the remaining type {@code rawType} under the last {@code k} arguments of the prefix"; returns the cache entry. */
    private int suspendPrefix(int rawType, int k) {
        int at = prefixCount << 1;
        if (at + 2 > prefixTypes.length) {
            prefixTypes = Arrays.copyOf(prefixTypes, prefixTypes.length << 1);
        }
        prefixTypes[at] = rawType;
        prefixTypes[at + 1] = k;
        return -(++prefixCount);
    }

    /**
     * The type of the application prefix {@code app} whose {@link #inferCheckCache} entry is the
     * pending pair {@code entry}: built now, and stored in place of the pair.
     */
    private int forcePrefix(int app, int entry) {
        int at = (-entry - 1) << 1;
        int rawType = prefixTypes[at];
        int k = prefixTypes[at + 1];
        int ab = alloc(k);
        int t = app;
        for (int q = k - 1; q >= 0; q--) {
            stk[ab + q] = terms.b(t);
            t = terms.a(t);
        }
        int r = instRev(rawType, ab, k);
        sp = ab;
        inferCheckCache.put(app, r);
        return r;
    }

    /** {@code infer_app} in infer-only mode: the whole spine, no checks, one instantiation per Pi group. */
    private int inferAppOnly(int e) {
        int n = terms.spineLength(e);
        int base = alloc(n);
        int f = terms.spine(e, stk, base);
        int fType = inferCore(f, true);
        int j = 0;
        for (int i = 0; i < n; i++) {
            if (terms.kind(fType) == Kind.PI) {
                fType = terms.b(fType);
            } else {
                fType = instRev(fType, base + j, i - j);
                fType = ensurePiCore(fType);
                fType = terms.b(fType);
                j = i;
            }
        }
        int r = instRev(fType, base + j, n - j);
        sp = base;
        return r;
    }

    /** {@code infer_let}: let variables become free variables that carry their value. */
    private int inferLet(int let, boolean inferOnly) {
        int base = sp;
        int e = let;
        while (terms.kind(e) == Kind.LET) {
            int n = sp - base;
            int type = instRev(terms.a(e), base, n);
            int val = instRev(terms.b(e), base, n);
            if (!inferOnly) {
                ensureSortCore(inferCore(type, false));
                int valType = inferCore(val, false);
                if (!isDefEqInt(valType, type)) {
                    throw new KernelException(Reason.LET_TYPE_MISMATCH, valType, type);
                }
            }
            int fv = newFVar(type);
            letValues.put(fv, val);
            push(fv);
            e = terms.c(e);
        }
        int n = sp - base;
        int r = inferCore(instRev(e, base, n), inferOnly);
        r = cheapBetaReduce(r);
        r = mkPiOver(base, n, r, true);
        sp = base;
        return r;
    }

    /** {@code infer_proj} (soundness critical: the restrictions on projections out of Prop structures). */
    private int inferProj(int e, boolean inferOnly) {
        int struct = terms.a(e);
        int idx = terms.b(e);
        int sCid = terms.c(e);
        int type = whnfFull(inferCore(struct, inferOnly));
        if (idx < 0) {
            throw new KernelException(Reason.INVALID_PROJECTION, e, 0);
        }
        int nargs = terms.spineLength(type);
        int base = alloc(nargs);
        int ind = terms.spine(type, stk, base);
        if (terms.kind(ind) != Kind.CONST) {
            throw new KernelException(Reason.INVALID_PROJECTION, e, 0);
        }
        int iCid = terms.a(ind);
        if (iCid != sCid || iCid <= 0) {
            throw new KernelException(Reason.INVALID_PROJECTION, e, 0);
        }
        if (!visible(iCid)) {
            throw new KernelException(Reason.UNKNOWN_CONSTANT, e, 0);
        }
        if (decls.kind(iCid) != DeclKind.INDUCT) {
            throw new KernelException(Reason.INVALID_PROJECTION, e, 0);
        }
        int nparams = decls.inductNumParams(iCid);
        if (decls.inductCtorCount(iCid) != 1 || nargs != nparams + decls.inductNumIndices(iCid)) {
            throw new KernelException(Reason.INVALID_PROJECTION, e, 0);
        }
        int ctor = decls.inductCtor(iCid, 0);
        // instantiate_type_lparams(c_info, const_levels(I)): the constructor's type at I's levels.
        int r = inferCore(mkConst(ctor, terms.b(ind)), true);
        for (int i = 0; i < nparams; i++) {
            r = whnfFull(r);
            if (terms.kind(r) != Kind.PI) {
                throw new KernelException(Reason.INVALID_PROJECTION, e, 0);
            }
            r = inst1(terms.b(r), stk[base + i]);
        }
        boolean isPropType = isPropInt(type);
        for (int i = 0; i < idx; i++) {
            r = whnfFull(r);
            if (terms.kind(r) != Kind.PI) {
                throw new KernelException(Reason.INVALID_PROJECTION, e, 0);
            }
            int body = terms.b(r);
            if (terms.looseRange(body) != 0) {
                // A later field type depends on field i.
                if (isPropType && !isPropInt(terms.a(r))) {
                    throw new KernelException(Reason.INVALID_PROJECTION, e, 0);
                }
                r = inst1(body, mkProj(sCid, i, struct));
            } else {
                r = body;
            }
        }
        r = whnfFull(r);
        if (terms.kind(r) != Kind.PI) {
            throw new KernelException(Reason.INVALID_PROJECTION, e, 0);
        }
        r = terms.a(r);
        if (isPropType && !isPropInt(r)) {
            throw new KernelException(Reason.INVALID_PROJECTION, e, 0);
        }
        sp = base;
        return r;
    }

    /**
     * {@code is_prop}: the sort of the type of {@code e} has a level that normalises to zero. Throws
     * TYPE_EXPECTED when that sort does not reduce to a sort; it never answers "not a proposition" then.
     */
    private boolean isPropInt(int e) {
        int s = ensureSortCore(inferCore(e, true));
        return levels.isAlwaysZero(terms.a(s));
    }

    /** {@code cheap_beta_reduce} (instantiate.cpp:195-214). */
    private int cheapBetaReduce(int e) {
        if (terms.kind(e) != Kind.APP) {
            return e;
        }
        int fn = ops.head(e);
        if (terms.kind(fn) != Kind.LAM) {
            return e;
        }
        int n = terms.spineLength(e);
        int base = alloc(n);
        terms.spine(e, stk, base);
        int i = 0;
        while (terms.kind(fn) == Kind.LAM && i < n) {
            i++;
            fn = terms.b(fn);
        }
        int r;
        if (terms.looseRange(fn) == 0) {
            r = mkApps(fn, base + i, n - i);
        } else if (terms.kind(fn) == Kind.BVAR && terms.a(fn) < i) {
            r = mkApps(stk[base + i - terms.a(fn) - 1], base + i, n - i);
        } else {
            r = e;
        }
        sp = base;
        return r;
    }

    /**
     * {@code local_ctx::mk_pi(fvars, body, remove_dead_let)} over the free variables
     * {@code stk[from .. from+n)}: let variables become {@code let} binders (dropped when unused and
     * {@code removeDeadLet}), the others Pi binders.
     */
    private int mkPiOver(int from, int n, int body, boolean removeDeadLet) {
        int r = abstractFVars(body, from, n);
        for (int i = n - 1; i >= 0; i--) {
            int fv = stk[from + i];
            int val = letValues.get(fv);
            if (val != 0) {
                if (!removeDeadLet || (terms.fvMask(r) & 1L) != 0L) {
                    int type = abstractFVars(canon(terms.b(fv)), from, i);
                    int value = abstractFVars(val, from, i);
                    r = ov.mkLet(type, value, r);
                    sync();
                } else {
                    // lower_loose_bvars(r, 1, 1): #0 does not occur, so instantiating it only lowers the rest.
                    r = inst1(r, fv);
                }
            } else {
                int type = abstractFVars(canon(terms.b(fv)), from, i);
                r = ov.mkPi(type, r);
                sync();
            }
        }
        return r;
    }

    // =====================================================================================================
    // whnf_core (type_checker.cpp:389-547), inductive.h, quot.h
    // =====================================================================================================

    /**
     * {@code whnf_core(e, cheap_rec = false, cheap_proj)}: beta, zeta, projections, iota and quotient
     * reduction; no delta, no Nat extension. The official function calls itself on every reduct; here
     * that chain is a loop, and the terms whose stack frames would store the result in
     * {@code m_whnf_core} on the way back are kept in a list and stored at the end.
     */
    private int whnfCore(int e0, boolean cheapProj) {
        int k = terms.kind(e0);
        switch (k) {
            case Kind.APP:
            case Kind.LET:
            case Kind.PROJ:
                break;
            case Kind.FVAR:
                if (letValues.get(e0) == 0) {
                    wcFresh = false;
                    return e0;
                }
                break;
            default:
                wcFresh = false;
                return e0;
        }
        enter();
        int base = sp;
        int e = e0;
        int r;
        // True when the official result of this call is a stuck projection that is NOT the object of
        // its m_whnf_core entry: a cheap call that found it stuck itself (it returns its argument and
        // stores nothing), or a full call that found it stuck while a nested call had already stored
        // an entry for the same term (the insert below does not replace it).
        boolean fresh = false;
        loop:
        for (;;) {
            k = terms.kind(e);
            switch (k) {
                case Kind.APP:
                case Kind.LET:
                case Kind.PROJ:
                    break;
                case Kind.FVAR:
                    if (letValues.get(e) == 0) {
                        r = e;
                        break loop;
                    }
                    break;
                default:
                    r = e;
                    break loop;
            }
            int cached = whnfCoreCache.get(e);
            if (cached != 0) {
                r = cached;
                break;
            }
            tick();
            if (sp - base > CHAIN_LIMIT) {
                throw new ResourceException(Reason.STACK_DEPTH);
            }
            switch (k) {
                case Kind.FVAR:
                    // whnf_fvar: zeta reduction of a let variable; this frame stores nothing.
                    e = letValues.get(e);
                    continue;
                case Kind.PROJ: {
                    int m = reduceProj(e, cheapProj);
                    push(e);
                    if (m == 0) {
                        // Stuck: the ORIGINAL term, with the structure untouched. A full call stores
                        // (e, e) below; a cheap one returns its own argument and stores nothing.
                        r = e;
                        fresh = cheapProj;
                        break loop;
                    }
                    e = m;
                    continue;
                }
                case Kind.LET: {
                    int next = inst1(terms.c(e), terms.b(e));
                    push(e);
                    e = next;
                    continue;
                }
                default: {
                    int f0 = ops.head(e);
                    if (terms.kind(f0) == Kind.LAM) {
                        // An explicit redex `V a1 .. an`. If V is the value of a definition that was
                        // unfolded in this declaration, the official entry of this very term may have
                        // been written through the unfolding, which keeps it under `c a1 .. an`
                        // (unfoldWhnfCore): the look-up of this frame covers that table too.
                        int owner = valueOwner.get(f0);
                        if (owner != 0) {
                            int hit = unfoldWhnfCache.get(withHead(e, owner));
                            if (hit != 0) {
                                r = hit;
                                break loop;
                            }
                        }
                        if (!cheapProj) {
                            explicitLam.put(f0, 1);
                        }
                    }
                    int f = whnfCore(f0, cheapProj);
                    if (terms.kind(f) == Kind.LAM) {
                        // Multi-argument beta with one instantiation.
                        int n = terms.spineLength(e);
                        int ab = alloc(n);
                        terms.spine(e, stk, ab);
                        int m = 1;
                        int body = terms.b(f);
                        while (terms.kind(body) == Kind.LAM && m < n) {
                            body = terms.b(body);
                            m++;
                        }
                        int next = mkApps(instRev(body, ab, m), ab + m, n - m);
                        sp = ab;
                        push(e);
                        e = next;
                        continue;
                    } else if (f == f0) {
                        int next = reduceRecursor(e, cheapProj);
                        if (next != 0) {
                            // iota / quotient reduction: a tail call, this frame stores nothing.
                            e = next;
                            continue;
                        }
                        r = e;
                        break loop;
                    } else {
                        int n = terms.spineLength(e);
                        int ab = alloc(n);
                        terms.spine(e, stk, ab);
                        int next = mkApps(f, ab, n);
                        sp = ab;
                        push(e);
                        e = next;
                        continue;
                    }
                }
            }
        }
        if (!cheapProj) {
            for (int i = base; i < sp; i++) {
                // The official insert never replaces an entry (one that a nested call wrote meanwhile stays).
                int key = stk[i];
                if (whnfCoreCache.get(key) == 0) {
                    if (frozen) {
                        sp = base;
                        throw probeAbort("a new m_whnf_core entry");
                    }
                    whnfCoreCache.put(key, r);
                } else if (key == r) {
                    // This frame found the projection stuck, and while it worked a nested call stored
                    // an entry for an occurrence of the same term. If that occurrence is this object,
                    // nothing happened; if it is another object, this frame returns an object that is
                    // not the entry's, and every cache that keeps the result keeps that object.
                    // Canonical ids cannot tell: no verdict (never seen on a real declaration).
                    if (frozen) {
                        sp = base;
                        throw probeAbort("a nested m_whnf_core entry");
                    }
                    statIdentity[0]++;
                    statIdentity[5]++;
                    if (identityMode == RefOptions.IDENTITY_DECLINE) {
                        sp = base;
                        identityDeclined = true;
                        throw new ResourceException(Reason.UNSUPPORTED_INPUT);
                    }
                    fresh = identityMode == RefOptions.IDENTITY_OTHER;
                }
            }
        }
        sp = base;
        depth--;
        wcFresh = fresh;
        return r;
    }

    /** The application {@code e} with its head replaced by {@code head}. */
    private int withHead(int e, int head) {
        int n = terms.spineLength(e);
        int ab = alloc(n);
        terms.spine(e, stk, ab);
        int r = mkApps(head, ab, n);
        sp = ab;
        return r;
    }

    /** {@code reduce_proj}. */
    private int reduceProj(int e, boolean cheapProj) {
        int idx = terms.b(e);
        if (idx < 0) {
            return 0;
        }
        int c = cheapProj ? whnfCore(terms.a(e), true) : whnfFull(terms.a(e));
        return reduceProjCore(c, terms.c(e), idx);
    }

    /** {@code reduce_proj_core}: field {@code idx} of a constructor application of structure {@code sCid}. */
    private int reduceProjCore(int c, int sCid, int idx) {
        if (terms.kind(c) == Kind.STRLIT) {
            c = whnfFull(stringLitToConstructor(c));
        }
        int mk = ops.head(c);
        if (terms.kind(mk) != Kind.CONST) {
            return 0;
        }
        int mkCid = terms.a(mk);
        if (mkCid <= 0 || !visible(mkCid) || decls.kind(mkCid) != DeclKind.CTOR) {
            return 0;
        }
        // A constructor of any other inductive type must not be projected.
        if (decls.ctorInduct(mkCid) != sCid) {
            return 0;
        }
        int nparams = decls.ctorNumParams(mkCid);
        int nargs = terms.spineLength(c);
        if (nparams + idx < nargs) {
            return nthArg(c, nargs, nparams + idx);
        }
        return 0;
    }

    /** {@code reduce_recursor}: quotient reduction first, then iota. */
    private int reduceRecursor(int e, boolean cheapProj) {
        int fn = ops.head(e);
        if (terms.kind(fn) != Kind.CONST) {
            return 0;
        }
        int cid = terms.a(fn);
        if (cid <= 0 || !visible(cid)) {
            return 0;
        }
        switch (decls.kind(cid)) {
            case DeclKind.QUOT_LIFT:
                return quotReduceRec(e, 5, 3);
            case DeclKind.QUOT_IND:
                return quotReduceRec(e, 4, 3);
            case DeclKind.REC:
                return inductiveReduceRec(e, fn, cid);
            default:
                return 0;
        }
    }

    /** {@code quot_reduce_rec} (quot.h:39-70). */
    private int quotReduceRec(int e, int mkPos, int argPos) {
        int n = terms.spineLength(e);
        if (n <= mkPos) {
            return 0;
        }
        int base = alloc(n);
        terms.spine(e, stk, base);
        int mk = whnfFull(stk[base + mkPos]);
        int mkFn = ops.head(mk);
        int r = 0;
        if (terms.kind(mkFn) == Kind.CONST && terms.a(mkFn) > 0 && decls.kind(terms.a(mkFn)) == DeclKind.QUOT_MK
                && terms.spineLength(mk) == 3) {
            r = mkApp(stk[base + argPos], terms.b(mk));
            r = mkApps(r, base + mkPos + 1, n - mkPos - 1);
        }
        sp = base;
        return r;
    }

    /** {@code is_non_rec_structure}: an inductive type with one constructor, no indices, not recursive. */
    private boolean isNonRecStructure(int cid) {
        if (cid <= 0 || !visible(cid)) {
            throw new KernelException(Reason.UNKNOWN_CONSTANT);
        }
        if (decls.kind(cid) != DeclKind.INDUCT) {
            return false;
        }
        return decls.inductCtorCount(cid) == 1 && decls.inductNumIndices(cid) == 0
                && (decls.inductFlags(cid) & DeclTable.IF_REC) == 0;
    }

    /** {@code inductive_reduce_rec} (inductive.h:74-119). */
    private int inductiveReduceRec(int e, int recFn, int recCid) {
        int majorIdx = decls.recMajorIndex(recCid);
        int n = terms.spineLength(e);
        if (majorIdx >= n) {
            return 0;
        }
        int base = alloc(n);
        terms.spine(e, stk, base);
        int major = stk[base + majorIdx];
        if ((decls.recFlags(recCid) & DeclTable.RF_K) != 0) {
            // BEFORE any reduction of the major premise.
            major = toCnstrWhenK(recCid, major);
        }
        major = whnfFull(major);
        int mk = terms.kind(major);
        if (mk == Kind.NATLIT) {
            major = natLitToConstructor(major);
        } else if (mk == Kind.STRLIT) {
            major = whnfFull(stringLitToConstructor(major));
        } else {
            major = toCnstrWhenStructure(decls.recMajorInduct(recCid), major);
        }
        int result = 0;
        int majorFn = major == 0 ? 0 : ops.head(major);
        if (majorFn != 0 && terms.kind(majorFn) == Kind.CONST) {
            int rule = decls.recRuleFor(recCid, terms.a(majorFn));
            if (rule >= 0) {
                int nfields = decls.recRuleFields(recCid, rule);
                int majorNargs = terms.spineLength(major);
                if (nfields <= majorNargs && levelCount(recFn) == decls.numLevelParams(recCid)) {
                    int rhs = importFor(decls.recRuleRhs(recCid, rule), recCid, ImportCache.RULE + rule, recFn);
                    // Parameters, motives and minor premises; the indices are dropped.
                    int lead = decls.recNumParams(recCid) + decls.recNumMotives(recCid) + decls.recNumMinors(recCid);
                    rhs = mkApps(rhs, base, lead);
                    // The LAST nfields arguments of the major premise (nested inductives may differ in
                    // the number of constructor parameters).
                    int mb = alloc(majorNargs);
                    terms.spine(major, stk, mb);
                    rhs = mkApps(rhs, mb + majorNargs - nfields, nfields);
                    sp = mb;
                    // Extra arguments after the major premise.
                    rhs = mkApps(rhs, base + majorIdx + 1, n - majorIdx - 1);
                    result = rhs;
                }
            }
        }
        sp = base;
        return result;
    }

    /** {@code nat_lit_to_constructor}: one constructor layer; 0 if the constructors do not exist. */
    private int natLitToConstructor(int lit) {
        if (nats.isZero(lit)) {
            int zero = special(Specials.NAT_ZERO);
            return zero == 0 ? 0 : mkConst(zero, IntLists.EMPTY);
        }
        int succ = special(Specials.NAT_SUCC);
        if (succ == 0) {
            return 0;
        }
        int pred = nats.pred(lit);
        sync();
        return mkApp(mkConst(succ, IntLists.EMPTY), pred);
    }

    /**
     * {@code string_lit_to_constructor}: {@code String.ofList (List.cons.{0} Char (Char.ofNat c1) ..
     * (List.nil.{0} Char))}, one element per Unicode scalar value.
     */
    private int stringLitToConstructor(int lit) {
        int ofList = special(Specials.STRING_OF_LIST);
        int chr = special(Specials.CHAR);
        int ofNat = special(Specials.CHAR_OF_NAT);
        int nil = special(Specials.LIST_NIL);
        int cons = special(Specials.LIST_CONS);
        if (ofList == 0 || chr == 0 || ofNat == 0 || nil == 0 || cons == 0) {
            // The official kernel would build a term over unknown constants here; RLVM does not
            // represent those, and admission treats a literal as mentioning all of its prerequisites.
            throw new ResourceException(Reason.UNSUPPORTED_PRELUDE);
        }
        if (levelZeroList == 0) {
            int at = alloc(1);
            stk[at] = levels.zero();
            levelZeroList = lists.intern(stk, at, 1);
            sp = at;
        }
        int charType = mkConst(chr, IntLists.EMPTY);
        int consChar = mkApp(mkConst(cons, levelZeroList), charType);
        int ofNatConst = mkConst(ofNat, IntLists.EMPTY);
        int r = mkApp(mkConst(nil, levelZeroList), charType);
        int len = nats.stringLength(lit);
        for (int i = len - 1; i >= 0; i--) {
            int c = mkApp(ofNatConst, mkNatLit(nats.stringCodePoint(lit, i)));
            r = mkApp(mkApp(consChar, c), r);
        }
        return mkApp(mkConst(ofList, IntLists.EMPTY), r);
    }

    /**
     * {@code to_cnstr_when_K} (inductive.h:28-48): for a K-like recursor, replace the major premise by
     * the nullary constructor application if its type is definitionally the major's type. The major
     * itself is neither inspected nor reduced.
     */
    private int toCnstrWhenK(int recCid, int e) {
        int appType = whnfFull(inferCore(e, true));
        int ind = ops.head(appType);
        if (terms.kind(ind) != Kind.CONST) {
            return e;
        }
        int iCid = terms.a(ind);
        if (iCid != decls.recMajorInduct(recCid)) {
            return e;
        }
        // mk_nullary_cnstr
        if (iCid <= 0 || !visible(iCid)) {
            throw new KernelException(Reason.UNKNOWN_CONSTANT);
        }
        if (decls.kind(iCid) != DeclKind.INDUCT || decls.inductCtorCount(iCid) == 0) {
            return e;
        }
        int ctor = decls.inductCtor(iCid, 0);
        int nparams = decls.recNumParams(recCid);
        int n = terms.spineLength(appType);
        int base = alloc(n);
        terms.spine(appType, stk, base);
        int cnstr = mkApps(mkConst(ctor, terms.b(ind)), base, Math.min(nparams, n));
        sp = base;
        int newType = inferCore(cnstr, true);
        if (frozen && appType != newType && !success.contains(appType, newType)) {
            // A K test that no cache answers: its outcome is not a function of the terms alone.
            throw probeAbort("a K test that no cache answers");
        }
        if (!isDefEqInt(appType, newType)) {
            return e;
        }
        return cnstr;
    }

    /**
     * {@code to_cnstr_when_structure} (inductive.h:60-72): structure eta on a recursor major premise;
     * not for proofs, not when the major already is a constructor application.
     */
    private int toCnstrWhenStructure(int inductCid, int e) {
        if (!isNonRecStructure(inductCid) || isConstructorApp(e)) {
            return e;
        }
        int eType = whnfFull(inferCore(e, true));
        int ind = ops.head(eType);
        if (terms.kind(ind) != Kind.CONST || terms.a(ind) != inductCid) {
            return e;
        }
        if (isPropInt(eType)) {
            return e;
        }
        // expand_eta_struct
        int ctor = decls.inductCtor(inductCid, 0);
        int nparams = decls.ctorNumParams(ctor);
        int nfields = decls.ctorNumFields(ctor);
        int n = terms.spineLength(eType);
        int base = alloc(n);
        terms.spine(eType, stk, base);
        int result = mkApps(mkConst(ctor, terms.b(ind)), base, Math.min(nparams, n));
        sp = base;
        for (int i = 0; i < nfields; i++) {
            result = mkApp(result, mkProj(inductCid, i, e));
        }
        return result;
    }

    private boolean isConstructorApp(int e) {
        int fn = ops.head(e);
        if (terms.kind(fn) != Kind.CONST) {
            return false;
        }
        int cid = terms.a(fn);
        return cid > 0 && visible(cid) && decls.kind(cid) == DeclKind.CTOR;
    }

    // =====================================================================================================
    // whnf, delta, the Nat extension (type_checker.cpp:551-739)
    // =====================================================================================================

    /**
     * {@code is_delta} on the head of a term: the constant id if the head is a definition or a theorem
     * in the environment applied to the right number of levels, else 0.
     */
    private int deltaCid(int head) {
        if (terms.kind(head) != Kind.CONST) {
            return 0;
        }
        int cid = terms.a(head);
        if (cid <= 0 || !visible(cid)) {
            return 0;
        }
        int k = decls.kind(cid);
        if (k != DeclKind.DEFN && k != DeclKind.THM) {
            return 0;
        }
        return levelCount(head) == decls.numLevelParams(cid) ? cid : 0;
    }

    /** {@code unfold_definition_core}: the instantiated value of a delta-reducible constant, or 0. */
    private int unfoldCore(int e) {
        int cid = deltaCid(e);
        if (cid == 0) {
            return 0;
        }
        int ls = terms.b(e);
        if (ls != IntLists.EMPTY) {
            int r = unfoldCache.get(e);
            if (r == 0) {
                r = importSlow(valueRoot(cid), cid, ImportCache.VALUE, ls);
                unfoldCache.put(e, r);
            }
            return r;
        }
        int stored = valueRoot(cid);
        int r = canonMemo.get(stored);
        return r != 0 ? r : importSlow(stored, cid, ImportCache.VALUE, ls);
    }

    /**
     * {@code whnf_core(*unfold_definition(e), false, cheap_proj)} for a term whose head is
     * delta-reducible ({@link #deltaCid} of its head is not 0): the two places where the official
     * kernel unfolds ({@code whnf} and {@code lazy_delta_reduction_step}) both continue with
     * {@code whnf_core} at once.
     *
     * <p>{@code unfold_definition} builds {@code value a1 .. an} without beta. When the value is a
     * lambda, the following {@code whnf_core} looks that term up in {@code m_whnf_core}, contracts
     * the first {@code min(lambdas, n)} arguments with one instantiation, normalises the result, and
     * stores it under the intermediate term unless it runs in cheap mode. The intermediate term is a
     * function of {@code e}, so the same lookups and stores are done here under {@code e} itself and
     * the n application nodes are never built.
     */
    private int unfoldWhnfCore(int e, boolean cheapProj) {
        if (terms.kind(e) != Kind.APP) {
            return whnfCore(unfoldCore(e), cheapProj);
        }
        int head = ops.head(e);
        int value = unfoldCore(head);
        int n = terms.spineLength(e);
        if (terms.kind(value) != Kind.LAM) {
            int base = alloc(n);
            terms.spine(e, stk, base);
            int app = mkApps(value, base, n);
            sp = base;
            return whnfCore(app, cheapProj);
        }
        // The official key is the term `value a1 .. an`. It stands under `c a1 .. an` for the first
        // reference c with this value (valueOwner), so that another constant with the same value, or
        // the same constant at other levels with the same instantiated value, finds the entry too.
        int key = e;
        int owner = valueOwner.get(value);
        if (owner == 0) {
            valueOwner.put(value, head);
        } else if (owner != head) {
            key = withHead(e, owner);
        }
        int cached = unfoldWhnfCache.get(key);
        if (cached != 0) {
            wcFresh = false;
            return cached;
        }
        if (explicitLam.get(value) != 0) {
            // The explicit term `value a1 .. an` went through a full whnf_core in this declaration and
            // may have its entry in whnfCoreCache: take the official path, with the term built.
            int base = alloc(n);
            terms.spine(e, stk, base);
            int app = mkApps(value, base, n);
            sp = base;
            return whnfCore(app, cheapProj);
        }
        tick();
        int base = alloc(n);
        terms.spine(e, stk, base);
        int m = 1;
        int body = terms.b(value);
        while (terms.kind(body) == Kind.LAM && m < n) {
            body = terms.b(body);
            m++;
        }
        int next = mkApps(instRev(body, base, m), base + m, n - m);
        sp = base;
        int r = whnfCore(next, cheapProj);
        if (!cheapProj && unfoldWhnfCache.get(key) == 0) {
            if (frozen) {
                throw probeAbort("a new m_whnf_core entry of an unfolded definition");
            }
            unfoldWhnfCache.put(key, r);
        }
        return r;
    }

    /** A Nat literal for {@code x} if it is a literal or {@code Nat.zero} (the official {@code is_nat_lit_ext}), else 0. */
    private int natLitExt(int x) {
        int k = terms.kind(x);
        if (k == Kind.NATLIT) {
            return x;
        }
        if (k == Kind.CONST && specialOf(x) == Specials.NAT_ZERO) {
            requireValid(Specials.NAT_ZERO);
            return mkNatLit(0L);
        }
        return 0;
    }

    private int boolConst(boolean value) {
        int cid = special(value ? Specials.BOOL_TRUE : Specials.BOOL_FALSE);
        if (cid == 0) {
            throw new ResourceException(Reason.UNSUPPORTED_PRELUDE);
        }
        return mkConst(cid, IntLists.EMPTY);
    }

    /** {@code reduce_nat}: the accelerated Nat functions on literal arguments; 0 if it does not apply. */
    private int reduceNat(int e) {
        if (terms.kind(e) != Kind.APP) {
            return 0;
        }
        int f1 = terms.a(e);
        int k1 = terms.kind(f1);
        if (k1 == Kind.CONST) {
            if (specialOf(f1) != Specials.NAT_SUCC) {
                return 0;
            }
            int arg = natLitExt(whnfFull(terms.b(e)));
            if (arg == 0) {
                return 0;
            }
            requireValid(Specials.NAT_SUCC);
            int r = nats.succ(arg);
            sync();
            return r;
        }
        if (k1 != Kind.APP) {
            return 0;
        }
        int f = terms.a(f1);
        if (terms.kind(f) != Kind.CONST) {
            return 0;
        }
        int which = specialOf(f);
        if (which < Specials.NAT_ADD || which > Specials.NAT_SHIFT_RIGHT) {
            return 0;
        }
        int a = natLitExt(whnfFull(terms.b(f1)));
        if (a == 0) {
            return 0;
        }
        int b = natLitExt(whnfFull(terms.b(e)));
        if (b == 0) {
            return 0;
        }
        requireValid(which);
        int op = which - Specials.NAT_ADD;
        if (op == NatOps.BEQ || op == NatOps.BLE) {
            return boolConst(nats.test(op, a, b));
        }
        int r = nats.binary(op, a, b);
        sync();
        return r;
    }

    /** {@code whnf}: whnf_core, then the Nat extension, then one delta step, until nothing applies. */
    private int whnfFull(int e) {
        switch (terms.kind(e)) {
            case Kind.BVAR:
            case Kind.SORT:
            case Kind.PI:
            case Kind.NATLIT:
            case Kind.STRLIT:
                return e;
            case Kind.FVAR:
                if (letValues.get(e) == 0) {
                    return e;
                }
                break;
            default:
                break;
        }
        int cached = whnfCache.get(e);
        if (cached != 0) {
            return cached;
        }
        enter();
        int t1 = whnfCore(e, false);
        // Brent's cycle detection on the sequence of terms: see nonTermination().
        int seen = t1;
        int lap = 0;
        int lapLength = 1;
        for (;;) {
            tick();
            int v = reduceNat(t1);
            if (v != 0) {
                whnfCache.put(e, v);
                depth--;
                return v;
            }
            if (deltaCid(ops.head(t1)) == 0) {
                whnfCache.put(e, t1);
                depth--;
                return t1;
            }
            // t = unfold_definition(t1); t1 = whnf_core(t)
            t1 = unfoldWhnfCore(t1, false);
            if (t1 == seen) {
                throw nonTermination();
            }
            if (++lap == lapLength) {
                seen = t1;
                lap = 0;
                lapLength <<= 1;
            }
        }
    }

    /**
     * The loop of {@code whnf} (and of lazy delta reduction) came back to a state it was in before.
     * The next state is a function of the current one, so the official kernel does not terminate
     * here (only ill-typed terms and the non-normalising proofs of the {@code corner-cases} tests do
     * this); it never produces a verdict, and neither do we: the judgment is declined instead of
     * running into the arena's timeout.
     */
    private ResourceException nonTermination() {
        cycled = true;
        return new ResourceException(Reason.BUDGET_STEPS);
    }

    /** True (once) if the last BUDGET_STEPS decline was a detected cycle, for the session's message. */
    boolean takeCycled() {
        boolean r = cycled;
        cycled = false;
        return r;
    }

    /** True (once) if the last UNSUPPORTED_INPUT decline was an object-identity decline. */
    boolean takeIdentityDeclined() {
        boolean r = identityDeclined;
        identityDeclined = false;
        return r;
    }

    /**
     * Strict mode: the decisions for the object-identity hazards of the declaration that is checked
     * next ({@code length} of them, bit i of {@code decisions} set = "another object"); a hazard
     * beyond them ends the check with UNSUPPORTED_INPUT and {@link #takeIdentityFork()} true.
     * {@code length < 0}: no script, every hazard declines (judgments through {@link KernelOps}).
     */
    void setIdentityScript(long decisions, int length) {
        identityScript = decisions;
        scriptLength = length;
    }

    /** True (once) if the last decline asks for a decision on the next object-identity hazard. */
    boolean takeIdentityFork() {
        boolean r = identityFork;
        identityFork = false;
        return r;
    }

    /** Object-identity events since the kernel was created: see {@link #statIdentity}. */
    long[] identityStats() {
        return statIdentity.clone();
    }

    /** Events at which the two possible official continuations may differ (what strict mode declines). */
    long identityHazards() {
        return statIdentity[0] - statIdentity[1];
    }

    // =====================================================================================================
    // is_def_eq (type_checker.cpp:748-1210)
    // =====================================================================================================

    /** {@code is_def_eq}: the core procedure, recording a success in {@code m_success}. */
    private boolean isDefEqInt(int t, int s) {
        if (t == s) {
            // quick_is_def_eq answers this at once; nothing else of is_def_eq_core is reached.
            return true;
        }
        boolean r = isDefEqCore(t, s);
        if (r) {
            if (frozen && !success.contains(t, s)) {
                throw probeAbort("a new success pair");
            }
            success.add(t, s);
        }
        return r;
    }

    /**
     * {@code is_equivalent(l1, l2)} on two levels (level.cpp: {@code l1 == l2 || normalize(l1) ==
     * normalize(l2)}): the official test, identity of the official normal forms, which is sound and
     * incomplete; with {@code -X levels=complete} the complete relation, which accepts a superset. The
     * two callers are the official ones: {@code is_def_eq(level, level)} for two sorts
     * ({@link #quick}) and {@code is_def_eq(levels, levels)} for the level lists of two references to
     * one constant ({@link #levelsEquiv}: lazy delta reduction with equal heads, and the constant
     * case of {@code is_def_eq_core}). Every other comparison of the official kernel is structural
     * ({@code ==}), which is identity of ids here; {@code is_prop} is {@code normalizes_to_zero} in
     * both modes.
     */
    private boolean levelEquiv(int a, int b) {
        return a == b || (officialLevels ? levels.isEquivOfficial(a, b) : levels.isEquiv(a, b));
    }

    /** Pairwise {@link #levelEquiv} on the level lists of two constants; the lengths must agree. */
    private boolean levelsEquiv(int listA, int listB) {
        if (listA == listB) {
            return true;
        }
        int na = listA == IntLists.EMPTY ? 0 : lists.length(listA);
        int nb = listB == IntLists.EMPTY ? 0 : lists.length(listB);
        if (na != nb) {
            return false;
        }
        if (!officialLevels) {
            return levels.listsEquiv(listA, listB);
        }
        for (int i = 0; i < na; i++) {
            if (!levelEquiv(lists.get(listA, i), lists.get(listB, i))) {
                return false;
            }
        }
        return true;
    }

    /** {@code quick_is_def_eq}: the easy cases. */
    private int quick(int t, int s) {
        if (t == s || success.contains(t, s)) {
            return TRUE;
        }
        int kt = terms.kind(t);
        int ks = terms.kind(s);
        if (kt == ks) {
            switch (kt) {
                case Kind.LAM:
                case Kind.PI:
                    return isDefEqBinding(t, s) ? TRUE : FALSE;
                case Kind.SORT:
                    return levelEquiv(terms.a(t), terms.a(s)) ? TRUE : FALSE;
                case Kind.NATLIT:
                    return nats.natEquals(t, s) ? TRUE : FALSE;
                case Kind.STRLIT:
                    return nats.stringEquals(t, s) ? TRUE : FALSE;
                default:
                    return UNDEF;
            }
        }
        // Both are literals (one expression kind in the official kernel) with different values.
        if ((kt == Kind.NATLIT && ks == Kind.STRLIT) || (kt == Kind.STRLIT && ks == Kind.NATLIT)) {
            return FALSE;
        }
        return UNDEF;
    }

    /** {@code is_def_eq_binding}: two lambdas or two Pis, the telescope opened once. */
    private boolean isDefEqBinding(int t, int s) {
        int base = sp;
        int k = terms.kind(t);
        do {
            int n = sp - base;
            int varSType = 0;
            int dt = terms.a(t);
            int ds = terms.a(s);
            if (dt != ds) {
                varSType = instRev(ds, base, n);
                int varTType = instRev(dt, base, n);
                if (!isDefEqInt(varTType, varSType)) {
                    sp = base;
                    return false;
                }
            }
            if (terms.looseRange(terms.b(t)) != 0 || terms.looseRange(terms.b(s)) != 0) {
                // A free variable is needed only if a body may use the binder.
                if (varSType == 0) {
                    varSType = instRev(ds, base, n);
                }
                int fv = newFVar(varSType);
                push(fv);
            } else {
                if (dontCare == 0) {
                    dontCare = mkSort(levels.zero());
                }
                push(dontCare);
            }
            t = terms.b(t);
            s = terms.b(s);
        } while (terms.kind(t) == k && terms.kind(s) == k);
        int n = sp - base;
        boolean r = isDefEqInt(instRev(t, base, n), instRev(s, base, n));
        sp = base;
        return r;
    }

    /** {@code is_def_eq_args}: arguments from the last to the first; the arities must agree. */
    private boolean isDefEqArgs(int t, int s) {
        while (terms.kind(t) == Kind.APP && terms.kind(s) == Kind.APP) {
            if (!isDefEqInt(terms.b(t), terms.b(s))) {
                return false;
            }
            t = terms.a(t);
            s = terms.a(s);
        }
        return terms.kind(t) != Kind.APP && terms.kind(s) != Kind.APP;
    }

    /** {@code try_eta_expansion_core}: {@code (fun x => b) =?= s} by eta-expanding {@code s}. */
    private boolean tryEtaExpansionCore(int t, int s) {
        if (terms.kind(t) == Kind.LAM && terms.kind(s) != Kind.LAM) {
            int sType = whnfFull(inferCore(s, true));
            if (terms.kind(sType) != Kind.PI) {
                return false;
            }
            int bv = ov.mkBVar(0);
            sync();
            int body = mkApp(s, bv);
            int newS = ov.mkLam(terms.a(sType), body);
            sync();
            return isDefEqInt(t, newS);
        }
        return false;
    }

    /** {@code try_eta_struct_core}: {@code t =?= mk t.1 .. t.n} for a fully applied structure constructor. */
    private boolean tryEtaStructCore(int t, int s) {
        int f = ops.head(s);
        if (terms.kind(f) != Kind.CONST) {
            return false;
        }
        int cid = terms.a(f);
        if (cid <= 0 || !visible(cid)) {
            throw new KernelException(Reason.UNKNOWN_CONSTANT);
        }
        if (decls.kind(cid) != DeclKind.CTOR) {
            return false;
        }
        int nparams = decls.ctorNumParams(cid);
        int n = terms.spineLength(s);
        if (n != nparams + decls.ctorNumFields(cid)) {
            return false;
        }
        int ind = decls.ctorInduct(cid);
        if (!isNonRecStructure(ind)) {
            return false;
        }
        if (!isDefEqInt(inferCore(t, true), inferCore(s, true))) {
            return false;
        }
        int base = alloc(n);
        terms.spine(s, stk, base);
        boolean r = true;
        for (int i = nparams; i < n; i++) {
            int proj = mkProj(ind, i - nparams, t);
            if (!isDefEqInt(proj, stk[base + i])) {
                r = false;
                break;
            }
        }
        sp = base;
        return r;
    }

    /** {@code is_def_eq_app}: heads, then arguments from the first to the last. */
    private boolean isDefEqApp(int t, int s) {
        if (terms.kind(t) != Kind.APP || terms.kind(s) != Kind.APP) {
            return false;
        }
        int nt = terms.spineLength(t);
        int tb = alloc(nt);
        int tf = terms.spine(t, stk, tb);
        int ns = terms.spineLength(s);
        int sb = alloc(ns);
        int sf = terms.spine(s, stk, sb);
        boolean r = false;
        if (isDefEqInt(tf, sf) && nt == ns) {
            int i = 0;
            for (; i < nt; i++) {
                if (!isDefEqInt(stk[tb + i], stk[sb + i])) {
                    break;
                }
            }
            r = i == nt;
        }
        sp = tb;
        return r;
    }

    /** {@code is_def_eq_proof_irrel}: definitive in both directions when the type is a proposition. */
    private int isDefEqProofIrrel(int t, int s) {
        int tType = inferCore(t, true);
        if (!isPropInt(tType)) {
            return UNDEF;
        }
        int sType = inferCore(s, true);
        return isDefEqInt(tType, sType) ? TRUE : FALSE;
    }

    /** {@code try_unfold_proj_app}: {@code e = s.i ..} that whnf_core can reduce. */
    private int tryUnfoldProjApp(int e, boolean fresh) {
        int f = ops.head(e);
        if (terms.kind(f) != Kind.PROJ) {
            wcFresh = fresh;
            return 0;
        }
        // Object identity: a full whnf_core on the object itself. If an entry answers, the object is
        // what it was; if the call computes, the object wrote the entry (unless a nested call did).
        boolean answered = whnfCoreCache.get(e) != 0;
        int n = whnfCore(e, false);
        if (n != e) {
            return n;
        }
        if (answered) {
            wcFresh = fresh;
        }
        return 0;
    }

    private static boolean hintIsRegular(int hint) {
        return hint != DeclKind.HINT_OPAQUE && hint != DeclKind.HINT_ABBREV;
    }

    /** Hints of a delta-reducible constant; everything that is not a definition counts as opaque. */
    private int hintOf(int cid) {
        return decls.kind(cid) == DeclKind.DEFN ? decls.hint(cid) : DeclKind.HINT_OPAQUE;
    }

    /** {@code compare(reducibility_hints, reducibility_hints)} (declaration.cpp:19-44). */
    private static int compareHints(int h1, int h2) {
        boolean r1 = hintIsRegular(h1);
        boolean r2 = hintIsRegular(h2);
        if (r1 && r2) {
            return h1 == h2 ? 0 : h1 > h2 ? -1 : 1;
        }
        if (h1 == h2) {
            return 0;
        }
        if (h1 == DeclKind.HINT_OPAQUE) {
            return 1;
        }
        if (h2 == DeclKind.HINT_OPAQUE) {
            return -1;
        }
        if (h1 == DeclKind.HINT_ABBREV) {
            return -1;
        }
        return 1;
    }

    /** {@code whnf_core(*unfold_definition(e), false, true)}. */
    private int unfoldAndWhnfCore(int e) {
        return unfoldWhnfCore(e, true);
    }

    /**
     * {@code lazy_delta_reduction_step}. Returns CONTINUE, UNKNOWN, EQUAL or DIFF and leaves the
     * updated terms in {@link #outT} / {@link #outS}, and whether each is a fresh projection object
     * (see {@link #wcFresh}) in {@link #outTFresh} / {@link #outSFresh}.
     */
    private int lazyDeltaStep(int tn, int sn, boolean tFresh, boolean sFresh) {
        int dt = deltaCid(ops.head(tn));
        int ds = deltaCid(ops.head(sn));
        if (dt == 0 && ds == 0) {
            outT = tn;
            outS = sn;
            outTFresh = tFresh;
            outSFresh = sFresh;
            return UNKNOWN;
        } else if (dt != 0 && ds == 0) {
            // If sn is a projection application, try to unfold it instead of the expensive side.
            int snNew = tryUnfoldProjApp(sn, sFresh);
            sFresh = wcFresh;
            if (snNew != 0) {
                sn = snNew;
            } else {
                tn = unfoldAndWhnfCore(tn);
                tFresh = wcFresh;
            }
        } else if (dt == 0) {
            int tnNew = tryUnfoldProjApp(tn, tFresh);
            tFresh = wcFresh;
            if (tnNew != 0) {
                tn = tnNew;
            } else {
                sn = unfoldAndWhnfCore(sn);
                sFresh = wcFresh;
            }
        } else {
            int ht = hintOf(dt);
            int hs = hintOf(ds);
            int c = compareHints(ht, hs);
            if (c < 0) {
                tn = unfoldAndWhnfCore(tn);
                tFresh = wcFresh;
            } else if (c > 0) {
                sn = unfoldAndWhnfCore(sn);
                sFresh = wcFresh;
            } else {
                if (terms.kind(tn) == Kind.APP && terms.kind(sn) == Kind.APP && dt == ds && hintIsRegular(ht)) {
                    // Same regular definition on both sides: compare the arguments before unfolding.
                    if (!failure.contains(tn, sn)) {
                        if (levelsEquiv(terms.b(ops.head(tn)), terms.b(ops.head(sn))) && isDefEqArgs(tn, sn)) {
                            outT = tn;
                            outS = sn;
                            outTFresh = tFresh;
                            outSFresh = sFresh;
                            return EQUAL;
                        }
                        if (frozen) {
                            throw probeAbort("a new failure pair");
                        }
                        failure.add(tn, sn);
                    }
                }
                tn = unfoldAndWhnfCore(tn);
                tFresh = wcFresh;
                sn = unfoldAndWhnfCore(sn);
                sFresh = wcFresh;
            }
        }
        int q = quick(tn, sn);
        outT = tn;
        outS = sn;
        outTFresh = tFresh;
        outSFresh = sFresh;
        return q == TRUE ? EQUAL : q == FALSE ? DIFF : CONTINUE;
    }

    private boolean isNatZero(int t) {
        int k = terms.kind(t);
        if (k == Kind.NATLIT) {
            return nats.isZero(t);
        }
        return k == Kind.CONST && specialOf(t) == Specials.NAT_ZERO;
    }

    /** Does {@link #natPred} exist for {@code t} (a non-zero literal or {@code Nat.succ a})? Allocates nothing. */
    private boolean hasNatPred(int t) {
        int k = terms.kind(t);
        if (k == Kind.NATLIT) {
            return !nats.isZero(t);
        }
        return k == Kind.APP && isSpecialConst(terms.a(t), Specials.NAT_SUCC);
    }

    /** {@code is_nat_succ}: the predecessor of a non-zero literal or of {@code Nat.succ a}; else 0. */
    private int natPred(int t) {
        int k = terms.kind(t);
        if (k == Kind.NATLIT) {
            if (nats.isZero(t)) {
                return 0;
            }
            int r = nats.pred(t);
            sync();
            return r;
        }
        if (k == Kind.APP && isSpecialConst(terms.a(t), Specials.NAT_SUCC)) {
            return terms.b(t);
        }
        return 0;
    }

    /** {@code is_def_eq_offset}: {@code Nat.zero}/literal 0 and {@code Nat.succ}/literal n+1. */
    private int isDefEqOffset(int t, int s) {
        if (isNatZero(t) && isNatZero(s)) {
            if (terms.kind(t) != terms.kind(s)) {
                requireValid(Specials.NAT_ZERO);
            }
            return TRUE;
        }
        // The official code computes both predecessors before it looks whether both exist and frees the
        // unused one at once; here a literal lives until the declaration ends, so look first.
        if (!hasNatPred(t) || !hasNatPred(s)) {
            return UNDEF;
        }
        int pt = natPred(t);
        int ps = natPred(s);
        if (terms.kind(t) != Kind.NATLIT || terms.kind(s) != Kind.NATLIT) {
            requireValid(Specials.NAT_SUCC);
        }
        return isDefEqCore(pt, ps) ? TRUE : FALSE;
    }

    /**
     * {@code lazy_delta_reduction}; the updated terms are left in {@link #outT} / {@link #outS} and,
     * when the answer is UNDEF, their freshness in {@link #outTFresh} / {@link #outSFresh}.
     */
    private int lazyDeltaReduction(int tn, int sn, boolean tFresh, boolean sFresh) {
        int seenT = tn;
        int seenS = sn;
        int lap = 0;
        int lapLength = 1;
        for (;;) {
            int r = isDefEqOffset(tn, sn);
            if (r != UNDEF) {
                outT = tn;
                outS = sn;
                return r;
            }
            if ((!hasFVar(tn) && !hasFVar(sn)) || eagerReduce) {
                int v = reduceNat(tn);
                if (v != 0) {
                    boolean b = isDefEqCore(v, sn);
                    outT = tn;
                    outS = sn;
                    return b ? TRUE : FALSE;
                }
                v = reduceNat(sn);
                if (v != 0) {
                    boolean b = isDefEqCore(tn, v);
                    outT = tn;
                    outS = sn;
                    return b ? TRUE : FALSE;
                }
            }
            int st = lazyDeltaStep(tn, sn, tFresh, sFresh);
            tn = outT;
            sn = outS;
            tFresh = outTFresh;
            sFresh = outSFresh;
            switch (st) {
                case CONTINUE:
                    break;
                case UNKNOWN:
                    return UNDEF;
                case EQUAL:
                    return TRUE;
                default:
                    return FALSE;
            }
            if (tn == seenT && sn == seenS) {
                throw nonTermination();
            }
            if (++lap == lapLength) {
                seenT = tn;
                seenS = sn;
                lap = 0;
                lapLength <<= 1;
            }
        }
    }

    /** {@code lazy_delta_proj_reduction}: {@code a.i =?= b.i} with the structures unfolded in lockstep. */
    private boolean lazyDeltaProjReduction(int a, int b, int sCid, int idx) {
        int seenA = a;
        int seenB = b;
        int lap = 0;
        int lapLength = 1;
        for (;;) {
            // The structures are not asked for again by a second whnf_core here: no freshness to track.
            int st = lazyDeltaStep(a, b, false, false);
            a = outT;
            b = outS;
            switch (st) {
                case CONTINUE:
                    if (a == seenA && b == seenB) {
                        throw nonTermination();
                    }
                    if (++lap == lapLength) {
                        seenA = a;
                        seenB = b;
                        lap = 0;
                        lapLength <<= 1;
                    }
                    break;
                case EQUAL:
                    return true;
                default: {
                    if (idx >= 0) {
                        int x = reduceProjCore(a, sCid, idx);
                        if (x != 0) {
                            int y = reduceProjCore(b, sCid, idx);
                            if (y != 0) {
                                return isDefEqCore(x, y);
                            }
                        }
                    }
                    return isDefEqCore(a, b);
                }
            }
        }
    }

    /** {@code try_string_lit_expansion_core}. */
    private int tryStringLitExpansionCore(int t, int s) {
        if (terms.kind(t) == Kind.STRLIT && terms.kind(s) == Kind.APP
                && isSpecialConst(terms.a(s), Specials.STRING_OF_LIST)) {
            return isDefEqCore(whnfFull(stringLitToConstructor(t)), s) ? TRUE : FALSE;
        }
        return UNDEF;
    }

    /** {@code is_def_eq_unit_like}: both sides inhabit a structure type with no fields. */
    private boolean isDefEqUnitLike(int t, int s) {
        int tType = whnfFull(inferCore(t, true));
        int ind = ops.head(tType);
        if (terms.kind(ind) != Kind.CONST || !isNonRecStructure(terms.a(ind))) {
            return false;
        }
        int ctor = decls.inductCtor(terms.a(ind), 0);
        if (!visible(ctor)) {
            throw new KernelException(Reason.UNKNOWN_CONSTANT);
        }
        if (decls.ctorNumFields(ctor) != 0) {
            return false;
        }
        return isDefEqCore(tType, inferCore(s, true));
    }

    private boolean isBoolTrue(int t) {
        if (terms.kind(t) != Kind.CONST) {
            return false;
        }
        int cid = terms.a(t);
        return cid > 0 && specials.which(cid) == Specials.BOOL_TRUE;
    }

    /**
     * The official second pass {@code is_def_eq_core(t_n_n, s_n_n)} for terms that the second
     * {@code whnf_core} left unchanged, run as a probe: true if it reaches its own second
     * {@code whnf_core} again without deciding anything and without adding a fact to a cache whose
     * content depends on when it was computed ({@link #frozen}). Then the second pass ends in the
     * tail of the first, in the same cache state, and the comparison is the same whether the
     * projection in hand is the cache entry's object or another one.
     *
     * <p>What the second pass does, step by step: the easy cases (look-ups); {@code Bool.true}
     * ({@code whnf} of a stuck projection: a function memo); the cheap {@code whnf_core}
     * (projections are answered by their entries; a stuck application is computed again: its head and
     * major premise are cache hits, only a K test that no cache answers runs again); proof
     * irrelevance (the type of {@code t_n} and whether it is a proposition: new work if lazy delta
     * replaced {@code t_n}); lazy delta reduction (the last round of the first pass again); the same
     * projection of two structures ({@code lazy_delta_proj_reduction} runs again); the second
     * {@code whnf_core}.
     */
    private boolean secondPassRepeatsNothing(int t, int s) {
        boolean frozen0 = frozen;
        int sp0 = sp;
        int lsp0 = lsp;
        int depth0 = depth;
        frozen = true;
        try {
            if (quick(t, s) != UNDEF) {
                probeWhy = "the easy cases decide";
                return false;
            }
            if ((!hasFVar(t) || eagerReduce) && isBoolTrue(s) && isBoolTrue(whnfFull(t))) {
                probeWhy = "Bool.true decides";
                return false;
            }
            if (whnfCore(t, true) != t || whnfCore(s, true) != s) {
                probeWhy = "the cheap whnf_core changes a term";
                return false;
            }
            if (isDefEqProofIrrel(t, s) != UNDEF) {
                probeWhy = "proof irrelevance decides";
                return false;
            }
            if (lazyDeltaReduction(t, s, false, false) != UNDEF || outT != t || outS != s) {
                probeWhy = "lazy delta reduction decides or changes a term";
                return false;
            }
            int kt = terms.kind(t);
            int ks = terms.kind(s);
            if (kt == Kind.CONST && ks == Kind.CONST && terms.a(t) == terms.a(s) && levelsEquiv(terms.b(t), terms.b(s))) {
                probeWhy = "the same constant";
                return false;
            }
            if (kt == Kind.PROJ && ks == Kind.PROJ && terms.c(t) == terms.c(s) && terms.b(t) == terms.b(s)
                    && lazyDeltaProjReduction(terms.a(t), terms.a(s), terms.c(t), terms.b(t))) {
                probeWhy = "lazy_delta_proj_reduction succeeds";
                return false;
            }
            if (whnfCore(t, false) != t || whnfCore(s, false) != s) {
                probeWhy = "the second whnf_core changes a term";
                return false;
            }
            return true;
        } catch (ProbeAbort abort) {
            sp = sp0;
            lsp = lsp0;
            depth = depth0;
            return false;
        } catch (KernelException error) {
            // The second pass would raise a kernel error where the first pass goes on.
            sp = sp0;
            lsp = lsp0;
            depth = depth0;
            probeWhy = "a kernel error: " + error.reason();
            return false;
        } finally {
            frozen = frozen0;
        }
    }

    private ProbeAbort probeAbort(String why) {
        probeWhy = why;
        return ProbeAbort.INSTANCE;
    }

    /**
     * Diagnostics: what the second pass would do beyond look-ups, read off the terms (bit set of
     * HAZARD_K, HAZARD_INFER, HAZARD_PROJ; see {@link #statIdentity}).
     */
    private int identityHazard(int tn, int sn, boolean tChanged) {
        int hazard = 0;
        if (terms.kind(tn) == Kind.PROJ && terms.kind(sn) == Kind.PROJ && terms.c(tn) == terms.c(sn)
                && terms.b(tn) == terms.b(sn)) {
            hazard |= HAZARD_PROJ;
        }
        if (isKRecursorApp(tn) || isKRecursorApp(sn)) {
            hazard |= HAZARD_K;
        }
        if (tChanged) {
            boolean answered = false;
            int type = inferOnlyCache.get(tn);
            if (type != 0) {
                int sort = inferOnlyCache.get(type);
                if (sort != 0) {
                    int w = terms.kind(sort) == Kind.SORT ? sort : whnfCache.get(sort);
                    answered = w != 0 && terms.kind(w) == Kind.SORT && !levels.isAlwaysZero(terms.a(w));
                }
            }
            if (!answered) {
                hazard |= HAZARD_INFER;
            }
        }
        return hazard;
    }

    /** An application of a K-like recursor with its major premise present. */
    private boolean isKRecursorApp(int e) {
        if (terms.kind(e) != Kind.APP) {
            return false;
        }
        int fn = ops.head(e);
        if (terms.kind(fn) != Kind.CONST) {
            return false;
        }
        int cid = terms.a(fn);
        return cid > 0 && visible(cid) && decls.kind(cid) == DeclKind.REC
                && (decls.recFlags(cid) & DeclTable.RF_K) != 0 && decls.recMajorIndex(cid) < terms.spineLength(e);
    }

    /**
     * {@code is_def_eq_core}, in the official order (research document 02, section 8.1). The official
     * tail call after the second {@code whnf_core} is the loop.
     */
    private boolean isDefEqCore(int t, int s) {
        enter();
        boolean res;
        for (;;) {
            tick();
            // 1. easy cases
            int q = quick(t, s);
            if (q != UNDEF) {
                res = q == TRUE;
                break;
            }
            // 2. proofs by reflection: a closed term against Bool.true is evaluated.
            if ((!hasFVar(t) || eagerReduce) && isBoolTrue(s)) {
                if (isBoolTrue(whnfFull(t))) {
                    res = true;
                    break;
                }
            }
            // 3. whnf_core without delta and with cheap projections
            int tn = whnfCore(t, true);
            boolean tFresh = wcFresh;
            int sn = whnfCore(s, true);
            boolean sFresh = wcFresh;
            final int tn3 = tn;
            // 4. (The official test is on pointers: it repeats the easy cases also when a projection was
            // answered by the entry of another object. They give the same answer for the same terms.)
            if (tn != t || sn != s) {
                q = quick(tn, sn);
                if (q != UNDEF) {
                    res = q == TRUE;
                    break;
                }
            }
            // 5. proof irrelevance
            q = isDefEqProofIrrel(tn, sn);
            if (q != UNDEF) {
                res = q == TRUE;
                break;
            }
            // 6. lazy delta reduction (updates tn, sn even when undecided)
            q = lazyDeltaReduction(tn, sn, tFresh, sFresh);
            tn = outT;
            sn = outS;
            if (q != UNDEF) {
                res = q == TRUE;
                break;
            }
            tFresh = outTFresh;
            sFresh = outSFresh;
            int kt = terms.kind(tn);
            int ks = terms.kind(sn);
            // 7. the same constant
            if (kt == Kind.CONST && ks == Kind.CONST && terms.a(tn) == terms.a(sn)
                    && levelsEquiv(terms.b(tn), terms.b(sn))) {
                res = true;
                break;
            }
            // 8. the same free variable
            if (kt == Kind.FVAR && ks == Kind.FVAR && tn == sn) {
                res = true;
                break;
            }
            // 9. the same projection of two structures
            if (kt == Kind.PROJ && ks == Kind.PROJ && terms.c(tn) == terms.c(sn) && terms.b(tn) == terms.b(sn)) {
                if (lazyDeltaProjReduction(terms.a(tn), terms.a(sn), terms.c(tn), terms.b(tn))) {
                    res = true;
                    break;
                }
            }
            // 10. whnf_core again, now reducing projections with full whnf. The official test that
            // follows is on POINTERS: `if (!is_eqp(t_n_n, t_n) || !is_eqp(s_n_n, s_n)) return
            // is_def_eq_core(t_n_n, s_n_n)`. A term that did not change comes back as another object
            // exactly when it is a stuck projection that an m_whnf_core entry answers and the object in
            // hand is not that entry's (it was fresh, see wcFresh, and another call wrote the entry since).
            boolean tOther = tFresh && whnfCoreCache.get(tn) != 0;
            int tnn = whnfCore(tn, false);
            boolean sOther = sFresh && whnfCoreCache.get(sn) != 0;
            int snn = whnfCore(sn, false);
            if (tnn != tn || snn != sn) {
                t = tnn;
                s = snn;
                continue;
            }
            if (tOther || sOther) {
                // The entry was written by a full whnf_core on an occurrence of the same term. Officially
                // the comparison goes on from here if that occurrence is the object in hand, and runs
                // once more from the start (with everything the first pass cached) if it is another
                // object. Which it is depends on the sharing of the export's records and on the kernel's
                // own allocation, which canonical ids do not keep (README, "Object identity").
                if (frozen) {
                    throw probeAbort("an object-identity event inside the second pass");
                }
                statIdentity[0]++;
                boolean decided = false;
                if (identityMode == RefOptions.IDENTITY_DECLINE || identityMode == RefOptions.IDENTITY_COUNT) {
                    // If the second pass repeats nothing but cache hits, both continuations are this one.
                    decided = secondPassRepeatsNothing(tn, sn);
                }
                if (decided) {
                    statIdentity[1]++;
                } else {
                    int hazard = identityHazard(tn, sn, tn != tn3);
                    if ((hazard & HAZARD_K) != 0) {
                        statIdentity[2]++;
                    }
                    if ((hazard & HAZARD_INFER) != 0) {
                        statIdentity[3]++;
                    }
                    if ((hazard & HAZARD_PROJ) != 0) {
                        statIdentity[4]++;
                    }
                    if (identityTrace > 0) {
                        identityTrace--;
                        System.err.println("rlvm-ref identity event (" + (tOther ? "t " : "") + (sOther ? "s " : "")
                                + "hazard " + hazard + "; the second pass: " + probeWhy + "): " + describe(tn) + "  =?=  "
                                + describe(sn));
                    }
                    if (identityMode == RefOptions.IDENTITY_DECLINE) {
                        int index = hazardIndex++;
                        if (index >= scriptLength) {
                            // No decision for this hazard: the session decides by checking both ways.
                            identityDeclined = true;
                            identityFork = scriptLength >= 0 && index < 62;
                            throw new ResourceException(Reason.UNSUPPORTED_INPUT);
                        }
                        if ((identityScript >>> index & 1L) != 0L) {
                            t = tn;
                            s = sn;
                            continue;
                        }
                    } else if (identityMode == RefOptions.IDENTITY_OTHER) {
                        t = tn;
                        s = sn;
                        continue;
                    }
                }
            }
            // 11.-15.
            if (isDefEqApp(tn, sn)) {
                res = true;
                break;
            }
            if (tryEtaExpansionCore(tn, sn) || tryEtaExpansionCore(sn, tn)) {
                res = true;
                break;
            }
            if (tryEtaStructCore(tn, sn) || tryEtaStructCore(sn, tn)) {
                res = true;
                break;
            }
            q = tryStringLitExpansionCore(tn, sn);
            if (q == UNDEF) {
                q = tryStringLitExpansionCore(sn, tn);
            }
            if (q != UNDEF) {
                res = q == TRUE;
                break;
            }
            res = isDefEqUnitLike(tn, sn);
            break;
        }
        depth--;
        return res;
    }

    // =====================================================================================================
    // Diagnostics (never on a hot path)
    // =====================================================================================================

    /**
     * What the official caches keep alive, for memory diagnostics (call it after a judgment and
     * before {@link #reset}). The official kernel frees a term when nothing refers to it any more;
     * what its seven caches refer to at the end of a declaration is what it retains.
     *
     * @param firstIndex overlay node index of the first node of the declaration ({@code
     *     Overlay.nodeCount() + 1} at its start)
     * @return {@code [0]} overlay nodes the declaration built, {@code [1]} of those reachable from the
     *     keys and values of the official caches (infer x2, whnf_core, whnf, unfold, the unfold +
     *     whnf_core fusion, success, failure), {@code [2]} of those reachable from the kernel's own
     *     import tables only, {@code [3..12]} entries of inferCheck, inferOnly, whnfCore, whnf,
     *     unfoldWhnf, unfold, success, failure, canonMemo, instCache, {@code [13]} bytes of all tables
     */
    public long[] retention(int firstIndex) {
        int top = ov.nodeCount();
        long[] marks = new long[(top >>> 6) + 1];
        long[] out = new long[14];
        out[0] = Math.max(0, top - firstIndex + 1);
        long official = 0;
        IntIntMap[] maps = {inferCheckCache, inferOnlyCache, whnfCoreCache, whnfCache, unfoldWhnfCache, unfoldCache};
        for (int m = 0; m < maps.length; m++) {
            IntIntMap map = maps[m];
            out[3 + m] = map.size();
            for (int j = 0; j < map.size(); j++) {
                official += reach(map.keyAt(j), marks, firstIndex);
                int v = map.valueAt(j);
                // A pending prefix type: what it would be built from is what is alive.
                official += reach(v > 0 ? v : prefixTypes[(-v - 1) << 1], marks, firstIndex);
            }
        }
        PairSet[] sets = {success, failure};
        for (int m = 0; m < sets.length; m++) {
            PairSet set = sets[m];
            out[9 + m] = set.size();
            for (int j = 0; j < set.size(); j++) {
                long pair = set.pairAt(j);
                official += reach((int) (pair >>> 32), marks, firstIndex);
                official += reach((int) pair, marks, firstIndex);
            }
        }
        out[1] = official;
        long own = 0;
        out[11] = canonMemo.size();
        for (int j = 0; j < canonMemo.size(); j++) {
            own += reach(canonMemo.valueAt(j), marks, firstIndex);
        }
        out[12] = instCache.size();
        for (int j = 0; j < instCache.size(); j++) {
            own += reach(instCache.valueAt(j), marks, firstIndex);
        }
        out[2] = own;
        out[13] = tableBytes();
        return out;
    }

    /** Marks the overlay nodes below {@code root}; returns how many new ones have an index of at least {@code firstIndex}. */
    private long reach(int root, long[] marks, int firstIndex) {
        long count = 0;
        int base = sp;
        push(root);
        while (sp > base) {
            int t = stk[--sp];
            if (t < Node.OVERLAY_BIT) {
                continue;
            }
            int idx = t & Node.INDEX_MASK;
            if ((marks[idx >>> 6] & (1L << idx)) != 0L) {
                continue;
            }
            marks[idx >>> 6] |= 1L << idx;
            if (idx >= firstIndex) {
                count++;
            }
            switch (terms.kind(t)) {
                case Kind.APP:
                case Kind.LAM:
                case Kind.PI:
                    push(terms.a(t));
                    push(terms.b(t));
                    break;
                case Kind.LET:
                    push(terms.a(t));
                    push(terms.b(t));
                    push(terms.c(t));
                    break;
                case Kind.PROJ:
                    push(terms.a(t));
                    break;
                case Kind.FVAR:
                    push(terms.b(t));
                    if (terms.c(t) > 0) {
                        push(terms.c(t));
                    }
                    break;
                default:
                    break;
            }
        }
        return count;
    }

    /** A bounded rendering of a term for error messages. */
    public String describe(int term) {
        StringBuilder sb = new StringBuilder();
        try {
            describe(sb, term, 6);
        } catch (RuntimeException e) {
            sb.append("<?>");
        }
        return sb.toString();
    }

    private void describe(StringBuilder sb, int t, int fuel) {
        if (t == 0) {
            sb.append("<none>");
            return;
        }
        if (fuel == 0 || sb.length() > 600) {
            sb.append("..");
            return;
        }
        switch (terms.kind(t)) {
            case Kind.BVAR:
                sb.append('#').append(terms.a(t));
                break;
            case Kind.SORT:
                sb.append("Sort ").append(levels.toString(terms.a(t)));
                break;
            case Kind.CONST: {
                int cid = terms.a(t);
                if (cid > 0) {
                    names.appendTo(sb, decls.name(cid));
                } else {
                    sb.append("<unbound>");
                }
                break;
            }
            case Kind.APP:
                sb.append('(');
                describe(sb, terms.a(t), fuel);
                sb.append(' ');
                describe(sb, terms.b(t), fuel - 1);
                sb.append(')');
                break;
            case Kind.LAM:
                sb.append("(fun : ");
                describe(sb, terms.a(t), fuel - 1);
                sb.append(" => ");
                describe(sb, terms.b(t), fuel - 1);
                sb.append(')');
                break;
            case Kind.PI:
                sb.append("(Pi : ");
                describe(sb, terms.a(t), fuel - 1);
                sb.append(", ");
                describe(sb, terms.b(t), fuel - 1);
                sb.append(')');
                break;
            case Kind.LET:
                sb.append("(let := ");
                describe(sb, terms.b(t), fuel - 1);
                sb.append("; ");
                describe(sb, terms.c(t), fuel - 1);
                sb.append(')');
                break;
            case Kind.PROJ:
                describe(sb, terms.a(t), fuel - 1);
                sb.append('.').append(terms.b(t));
                break;
            case Kind.NATLIT:
                sb.append(nats.natToString(t));
                break;
            case Kind.STRLIT:
                sb.append("\"..\"");
                break;
            case Kind.FVAR:
                sb.append("?x").append(terms.a(t));
                break;
            default:
                sb.append("<kind ").append(terms.kind(t)).append('>');
        }
    }
}
