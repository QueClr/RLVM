package rlvm.admit;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.function.IntSupplier;

import rlvm.api.KernelException;
import rlvm.api.KernelOps;
import rlvm.api.Reason;
import rlvm.api.ResourceException;
import rlvm.inductive.BlockInput;
import rlvm.inductive.GeneratedBlock;
import rlvm.store.DeclKind;
import rlvm.store.DeclTable;
import rlvm.store.IntLists;
import rlvm.store.Kind;
import rlvm.store.NameTable;
import rlvm.store.Node;
import rlvm.store.Overlay;
import rlvm.store.Specials;
import rlvm.store.Store;
import rlvm.store.Terms;

/**
 * The environment pass: the single writer of derived facts.
 *
 * <p>For each loaded unit, in admission order:
 * <ul>
 *   <li>def / thm / axiom / opaque: bind special names, run the prelude guards, move the unit status
 *       LOADED -&gt; ENV_READY; nothing else (their rows are inputs and are checked later by the
 *       workers through {@link Admission}). A non-quot record named {@code Quot.mk}, {@code Quot.lift}
 *       or {@code Quot.ind} is rejected here (the official checker never finds it in the final
 *       environment);
 *   <li>quot: pre-checks (reserved name, {@code Eq} earlier, {@code Quot} first), {@code KernelOps.begin},
 *       {@code QuotAdmission.admit}, bind the special;
 *   <li>inductive block: fill {@code BlockInput} from the rows and the claims blob (checklist 25, 26),
 *       structural checks of the type and constructor-type roots (items 28, 33, 38 as far as they are
 *       structural), {@code KernelOps.begin}, {@code InductiveAdmission.generate},
 *       {@code RecordCompare.compare}, commit the derived facts to the declaration table's side tables
 *       (the exported shared terms are used: an identical record is the same term), bind specials and
 *       run the guards;
 * </ul>
 * then advance {@code Store.envUnits()} past the unit. Units the pass decides itself (quot, inductive)
 * get their final status (ACCEPTED / REJECTED / DECLINED) here and are reported to the listener.
 *
 * <p><b>Frontier.</b> A unit that fails (reject or decline) is reported to the listener and stops the
 * frontier for good: {@code envUnits} stays at the failed unit, no later unit is ever processed,
 * because the run can no longer be accepted and a later unit cannot change the first failure in file
 * order. Units below the failed one are still checked by the workers.
 *
 * <p><b>Exact or decline.</b> Where the official checker's behaviour on a hand-made file is not a
 * certain rejection (type records of one item that disagree, constructor or recursor records sitting
 * in another item than their type, a quot record under a foreign name, unsafe members of a safe
 * block, derived recursors the export does not list) the unit is DECLINED, never rejected.
 *
 * <p><b>Threading.</b> One thread at a time drives the pass: a worker calling {@link #advanceTo(int)}
 * lazily (the single-worker slice), or a dedicated thread calling {@link #run()} once the load is
 * complete. Uses the judgments of the given {@link KernelOps} (the reference kernel in v1). The pass
 * keeps no overlay term, level or list id between two calls of {@link #advanceTo(int)}: the owner of
 * the session behind {@code ops} may reset it between calls.
 *
 * <p>Deferred (throws {@code UnsupportedOperationException} / declines): running while the file is
 * still loading (pipelined mode), generated constants (a derived recursor without an exported
 * record), {@code Policy.ForwardRefs.REORDER}, {@code Policy.trustMatchingRecursors} (no effect).
 */
public final class EnvBuilder implements Runnable {

    /**
     * The official replay order is simulated for a file in declaration order only if the file is
     * small (hand-made files and the arena's test files; an exporter's output of a library is not
     * looked at): at most this many units, shared term nodes and proof-block records, and this many
     * node visits. A file that left file order gets the larger budget.
     */
    /**
     * The derived terms of an admitted inductive block are given back to the session's overlay as
     * soon as the block is committed ({@code -Drlvm.admit.releaseBlocks=false} keeps them until the
     * owner of the session resets it: the behaviour before wave 7, kept for measurements).
     */
    private final boolean releaseBlocks = !"false".equals(System.getProperty("rlvm.admit.releaseBlocks"));

    /**
     * {@code -Drlvm.admit.stats=true}: the pass measures itself (two clock reads per call of
     * {@link #advanceTo} that has work to do) and prints one line to standard error when the
     * frontier reaches the end of the completely loaded file. Off by default; {@link #blockNanos()}
     * and {@link #blockUnits()} are always counted (two clock reads per inductive / quot unit).
     */
    private final boolean stats = Boolean.getBoolean("rlvm.admit.stats");
    private long passNanos;
    private long blockNanos;
    private int blockUnits;
    private int respelledBlocks;
    private boolean statsPrinted;

    private static final int ORDER_CHECK_UNITS = 1 << 14;
    private static final int ORDER_CHECK_NODES = 1 << 19;
    private static final int ORDER_CHECK_RECORDS = 1 << 19;
    private static final long ORDER_CHECK_VISITS = 1L << 20;
    private static final long ORDER_REPLAY_VISITS = 1L << 25;

    private static final Listener NO_LISTENER = new Listener() {
        @Override
        public void unitAdmitted(int unit) {}

        @Override
        public void unitFailed(int unit, Reason reason, String message) {}

        @Override
        public void frontierAdvanced(int units) {}
    };

    private final Store store;
    private final KernelOps ops;
    private final Policy policy;
    private final Listener listener;
    private final BlockOps blockOps;
    private final ClaimsSource claimsSource;
    private final DeclTable decls;
    private final NameTable names;
    private final Specials specials;
    private final Terms terms;
    /** Reader of every list id found in rows, claims and shared terms (the session's list view). */
    private final IntLists lists;
    private final StructuralChecks structural;
    private final PreludeGuards guards;

    private final BlockInput in = new BlockInput();
    private final GeneratedBlock gen = new GeneratedBlock();

    /** Scratch of block admission, indexed by (cid - first cid of the unit). */
    private int[] memberEntry = new int[16];
    private boolean[] memberUsed = new boolean[16];
    private int[] typeOrder = new int[4];
    private int[] listBuf = new int[16];

    /** The frontier as far as this pass moved it (equals {@code Store.envUnits()}). */
    private int env;
    private int failedUnit = -1;
    private Reason failReason;
    private String failMessage;
    /** Set by {@link #process(int)}: the unit was decided here (quot, inductive), not only prepared. */
    private boolean decided;
    private String detail;
    private boolean droppedQuotChecked;
    /** Set by {@link #prepareBlock}: the inductive record has no types. */
    private boolean typeless;
    private volatile boolean cancelled;
    /** The last call of {@link #advanceTo} stopped at a unit that cannot be decided before the file is loaded. */
    private boolean waitingForLoad;
    /** Set by {@link #plainUnit}: a root of the unit has an unresolved reference (dependency order needed). */
    private boolean outOfOrder;
    /** A nested inductive block was admitted while the file was still loading (see {@link #lateNestedName}). */
    private boolean nestedBeforeLoadEnd;
    /** 0 = unknown, 1 = a proof block with something unresolved is decoded to see whether its root reaches it, 2 = not (no overlay). */
    private int inspectBlocks;

    // ---- admission in dependency order (see the section "replay" below)
    private ReplayState replay;
    private final Collector collector = new Collector();
    private final Collector headerCollector = new Collector();
    private int[] replayStack = new int[16];
    /** Parallel to {@link #replayStack}: the unit was reached through a dependency the official replay does not have. */
    private boolean[] replayImplicit = new boolean[16];
    /** Needs of units below the replay's first unit, computed on demand for the presence test. */
    private HashMap<Integer, int[]> earlyNeeds;
    /** The environment session met a stack overflow: nothing it holds is trusted any more. */
    private boolean poisoned;
    /** The file was completely loaded when this pass was created (a batch run: every session covers the file). */
    private final boolean completeAtStart;
    /** The number of units the caller's session covers (see {@link #setCoverage}), or null. */
    private IntSupplier coverage;
    /** The simulated official replay order of the file, or null (not computed, or outside the model). */
    private OfficialOrder order;
    /** The official order was looked at once the file was loaded (see {@link #officialOrderCheck}). */
    private boolean orderChecked;
    /**
     * A unit in file order that the official replay adds while something it mentions is still
     * missing (-1: none): the pass leaves file order there.
     */
    private int orderAbsentUnit = -1;
    /**
     * The unit at which the pass reports that the official replay order rejects a unit it had already
     * passed in file order (a pipelined run), or -1; {@link #orderRejectDetail} says which.
     */
    private int orderRejectUnit = -1;
    private String orderRejectDetail;

    public EnvBuilder(Store store, KernelOps ops, Policy policy, Listener listener) {
        this(store, ops, policy, listener, new BlockOps.Inductive(store, ops), new StoreClaims(store));
    }

    /** For tests: scripted block admission and claims. */
    EnvBuilder(Store store, KernelOps ops, Policy policy, Listener listener, BlockOps blockOps,
            ClaimsSource claimsSource) {
        if (ops == null) {
            throw new IllegalArgumentException("EnvBuilder needs KernelOps (the reference kernel's Session.ops())");
        }
        this.store = store;
        this.ops = ops;
        this.policy = policy;
        this.listener = listener == null ? NO_LISTENER : listener;
        this.blockOps = blockOps;
        this.claimsSource = claimsSource;
        this.decls = store.decls();
        this.names = store.names();
        this.specials = store.specials();
        this.terms = ops.terms();
        // The session's own list view reads every shared list; the base list store is the loader's.
        this.lists = ops.levels() != null && ops.levels().lists() != null ? ops.levels().lists() : store.lists();
        this.structural = new StructuralChecks(store, terms, lists, policy);
        this.guards = new PreludeGuards(store, terms, ops.levels());
        this.env = store.envUnits();
        this.completeAtStart = store.loadComplete();
        // Outcomes recorded by an earlier pass over this store are not this pass's.
        ReplayState.retire(store);
    }

    /**
     * Tells the pass how many units the session behind its {@code KernelOps} covers: its level and
     * list view can read the units {@code [0, coveredUnits)} (in a pipelined run a context sits on a
     * snapshot of the levels: {@code WorkerContext.coveredUnits()}). Admission in dependency order
     * reads and admits units beyond the argument of {@link #advanceTo} (the dependencies of the unit
     * at the frontier, the whole file for the official order), so it is entered only when the
     * session covers every loaded unit of the complete file.
     *
     * <p>Without this call the pass assumes that the session covers the file exactly if the file was
     * completely loaded when the pass was created (a batch run); a pass created while the file is
     * still loading then stays in file order for good.
     */
    public void setCoverage(IntSupplier coveredUnits) {
        this.coverage = coveredUnits;
    }

    /** True if the file is complete and the session of this pass can read all of it. */
    private boolean sessionCoversFile() {
        if (!store.loadComplete()) {
            return false;
        }
        IntSupplier c = coverage;
        boolean covered = c != null ? c.getAsInt() >= store.loadedUnits() : completeAtStart;
        return covered && listViewIsCurrent();
    }

    /**
     * Guard against a session whose list view is older than the file (a context created before the
     * end of a pipelined load): the lists the pass is about to read through the session - the
     * universe parameters of the last constants, the {@code all} and constructor lists of the
     * inductive records not admitted yet - must read the same through the store's own list store,
     * which is final once the load is complete.
     */
    private boolean listViewIsCurrent() {
        IntLists base = store.lists();
        if (base == null || base == lists) {
            return true;
        }
        try {
            int n = decls.constantCount();
            for (int cid = Math.max(1, n - 255); cid <= n; cid++) {
                if (!sameListAcross(base, decls.levelParams(cid))) {
                    return false;
                }
            }
            for (int u = env, units = store.loadedUnits(); u < units; u++) {
                if (decls.unitSize(u) == 0 || decls.unitKind(u) != DeclKind.INDUCT) {
                    continue;
                }
                int[] blob = claimsSource.claims(u);
                if (blob == null || Claims.validate(blob) != null) {
                    continue;
                }
                for (int i = 0, nT = blob[Claims.H_TYPES]; i < nT; i++) {
                    int e = Claims.typeEntry(i);
                    if (!sameListAcross(base, blob[e + Claims.T_ALL]) || !sameListAcross(base, blob[e + Claims.T_CTORS])) {
                        return false;
                    }
                }
            }
            return true;
        } catch (RuntimeException e) {
            return false; // an id the session's view does not have
        }
    }

    private boolean sameListAcross(IntLists base, int list) {
        if (list == IntLists.EMPTY) {
            return true;
        }
        int n = base.length(list);
        if (lists.length(list) != n) {
            return false;
        }
        for (int i = 0; i < n; i++) {
            if (lists.get(list, i) != base.get(list, i)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Processes the remaining units of a completely loaded file until the end, the first failure or
     * {@link #cancel()}. Running while the loader is still publishing is deferred.
     */
    @Override
    public void run() {
        if (!store.loadComplete()) {
            throw new UnsupportedOperationException(
                    "EnvBuilder.run() while the file is still loading (pipelined mode) is deferred; "
                            + "call it after Store.loadComplete(), or drive the pass with advanceTo");
        }
        int total = store.loadedUnits();
        while (env < total && failedUnit < 0 && !cancelled) {
            advanceTo(Math.min(total, env + 4096));
        }
    }

    /**
     * Processes every loaded unit below {@code unit}, in order, on the calling thread; returns the new
     * {@code envUnits}. Returns at once if the frontier is already there, after a failure (the
     * frontier then stays at the failed unit) or after {@link #cancel()}.
     */
    public int advanceTo(int unit) {
        if (unit <= env || failedUnit >= 0 || cancelled) {
            return env;
        }
        int limit = Math.min(unit, store.loadedUnits());
        if (limit <= env) {
            return env;
        }
        if (!stats) {
            return advance(limit);
        }
        long t0 = System.nanoTime();
        try {
            return advance(limit);
        } finally {
            passNanos += System.nanoTime() - t0;
            if (!statsPrinted && store.loadComplete() && (env >= store.loadedUnits() || failedUnit >= 0)) {
                statsPrinted = true;
                System.err.println("rlvm-admit-stats: environment pass " + env + " units in " + passNanos / 1000
                        + " us (inductive / quot units: " + blockUnits + " in " + blockNanos / 1000 + " us; "
                        + (replay != null ? "dependency order from unit " + replay.first : "file order")
                        + "; inductive blocks with a level the official kernel respells: " + respelledBlocks + ")");
            }
        }
    }

    /**
     * Wall-clock nanoseconds this pass spent deciding inductive and quot units in file order
     * (structural checks, {@code rlvm-inductive}, the judgments of its {@code KernelOps}, commit).
     * The thread that runs the pass reads it; for the driver's statistics.
     */
    public long blockNanos() {
        return blockNanos;
    }

    /** Number of inductive and quot units this pass looked at in file order. */
    public int blockUnits() {
        return blockUnits;
    }

    /**
     * Number of inductive blocks, of those this pass regenerated, with a universe level that the
     * official kernel respells when it refers to the block's own constants (finding L-F1: the blocks
     * for which admission compares anything it would not compare with the declared spelling kept).
     */
    public int respelledBlocks() {
        return respelledBlocks;
    }

    private int advance(int limit) {
        terms.refresh();
        waitingForLoad = false;
        int start = env;
        Reason failure = null;
        while (env < limit && !cancelled) {
            int u = env;
            decided = false;
            detail = null;
            outOfOrder = false;
            if (replay == null && !orderChecked) {
                officialOrderCheck(u);
            }
            Reason r;
            if (replay == null && u == orderRejectUnit) {
                decided = true;
                r = fail(Reason.UNKNOWN_CONSTANT, orderRejectDetail);
            } else {
                r = replay != null ? guarded(u, true) : u == orderAbsentUnit ? null : guarded(u, false);
            }
            if (replay == null && r == null && outOfOrder && policy.forwardRefs == Policy.ForwardRefs.REORDER
                    && !store.loadComplete()) {
                // A unit that mentions something not declared yet: only the complete file says whether
                // dependency order admits it. Not passed (a worker could only decline it), not recorded.
                waitingForLoad = true;
                break;
            }
            if (replay == null && u != orderRejectUnit && (u == orderAbsentUnit || wantsReplay(r))) {
                // The unit is not decidable in file order (a reference to a later declaration, a record
                // only the official replay order decides, a declaration the official replay adds too
                // early): from here on, admit in dependency order.
                replay = new ReplayState(u, store.loadedUnits());
                if (order == null) {
                    order = simulateOfficialOrder(ORDER_REPLAY_VISITS);
                }
                ReplayState.publish(store, replay);
                decided = false;
                detail = null;
                r = guarded(u, true);
            }
            if (r != null && !r.isReject() && !store.loadComplete()) {
                // Not decidable before the end of the file (the rest of it may declare what is missing,
                // or make the official kernel generate it): nothing is recorded, the caller comes back.
                waitingForLoad = true;
                break;
            }
            if (r != null) {
                failure = r;
                failedUnit = u;
                failReason = r;
                failMessage = describe(u, detail);
                setStatus(u, r.isReject() ? DeclKind.ST_REJECTED : DeclKind.ST_DECLINED);
                break;
            }
            if (decided) {
                setStatus(u, DeclKind.ST_ACCEPTED);
            } else {
                decls.casStatus(u, DeclKind.ST_LOADED, DeclKind.ST_ENV_READY);
            }
            env = u + 1;
            store.publishEnv(env);
            if (decided) {
                listener.unitAdmitted(u);
            }
        }
        if (env > start) {
            listener.frontierAdvanced(env);
        }
        if (failure != null) {
            listener.unitFailed(failedUnit, failReason, failMessage);
        }
        return env;
    }

    /** One unit of the frontier, in file order ({@code inReplay == false}) or in dependency order. */
    private Reason guarded(int u, boolean inReplay) {
        blockOps.clearDeclineDetail();
        try {
            Reason r = droppedQuotName();
            if (r == null) {
                r = inReplay ? replayAt(u) : lateNestedName(u);
                if (r == null && !inReplay) {
                    r = process(u);
                }
            }
            return r;
        } catch (KernelException e) {
            detail = "kernel error " + e.reason();
            return e.reason();
        } catch (ResourceException e) {
            detail = gaveUp(e);
            return e.reason();
        } catch (Overlay.OverlayFullException e) {
            detail = "term overlay full during the environment pass";
            return Reason.BUDGET_MEMORY;
        } catch (UnsupportedOperationException e) {
            detail = "not implemented: " + e.getMessage();
            return Reason.UNSUPPORTED_INPUT;
        } catch (RuntimeException e) {
            detail = "exception in the environment pass: " + e;
            return Reason.INTERNAL_ERROR;
        }
    }

    /**
     * The message of a decline: the reason and its cause. The cause is the one {@code rlvm-inductive}
     * states for the unit being looked at ({@link BlockOps#declineDetail()}), else what the reason
     * itself means for a judgment of the environment session (the pass has no retry with a larger
     * budget or a deeper stack).
     */
    private String gaveUp(ResourceException e) {
        String why = blockOps.declineDetail();
        if (why == null) {
            switch (e.reason()) {
                case STACK_DEPTH:
                    why = "the judgments of the environment pass ran out of stack (no deep-stack retry here)";
                    break;
                case BUDGET_MEMORY:
                    why = "the environment session ran out of its memory budget";
                    break;
                case BUDGET_STEPS:
                    why = "the environment session ran out of its step budget";
                    break;
                case FORWARD_REFERENCE:
                    why = "a constant that is declared later in the file (or not yet loaded) is needed";
                    break;
                case UNSUPPORTED_INPUT:
                    why = "a root of the unit mentions a constant without a usable record (it is unbound in the store)";
                    break;
                default:
                    why = "the reference kernel could not decide a judgment of the environment pass";
                    break;
            }
        }
        return "gave up: " + e.reason() + " (" + why + ")";
    }

    /**
     * True if the outcome of a unit in file order calls for admission in dependency order: the unit
     * (or, for a plain unit, one of its roots) depends on something file order cannot decide, the
     * policy asks for the official replay order, and the whole file is there to look at.
     */
    private boolean wantsReplay(Reason r) {
        if (policy.forwardRefs != Policy.ForwardRefs.REORDER || !store.loadComplete() || terms.overlay() == null) {
            return false;
        }
        boolean wanted = r == null ? outOfOrder
                : r == Reason.FORWARD_REFERENCE || r == Reason.UNSUPPORTED_INPUT || r == Reason.UNSUPPORTED_PRELUDE;
        // Dependency order reads units beyond the caller's target: only with a session that covers them.
        return wanted && sessionCoversFile();
    }

    /**
     * Once per pass, when the file is completely loaded and small: simulates the official replay
     * order ({@link OfficialOrder}). A file in declaration order is a valid environment, but the
     * official checker adds its declarations in the order of {@code Name.quickCmp} on their names,
     * dependencies first - and a constant the kernel adds on the side ({@code Quot.mk / lift / ind},
     * what only another type's header of a mutual block mentions) is not a dependency in that sense.
     * If that order adds a unit at or behind the frontier before something it mentions, the official
     * checker rejects the file ("unknown constant") although file order would accept it: the pass
     * leaves file order at that unit and rejects it there ({@link #presence}).
     */
    private void officialOrderCheck(int frontier) {
        if (policy.forwardRefs != Policy.ForwardRefs.REORDER || !store.loadComplete() || terms.overlay() == null) {
            return; // not yet (still loading), or never (file-order policy, an engine without an overlay)
        }
        if (coverage == null && !completeAtStart) {
            orderChecked = true; // a pass created while the file was loading: its session never covers the file
            return;
        }
        if (!sessionCoversFile()) {
            return; // the caller's session does not cover the file yet: asked again at the next unit
        }
        orderChecked = true;
        if (store.loadedUnits() > ORDER_CHECK_UNITS || store.terms().exportEnd() > ORDER_CHECK_NODES
                || store.blocks().recordCount() > ORDER_CHECK_RECORDS) {
            return;
        }
        if (!orderSensitive()) {
            return;
        }
        OfficialOrder o = simulateOfficialOrder(ORDER_CHECK_VISITS);
        if (o != null) {
            order = o;
            int absent = o.firstAbsent();
            if (absent >= frontier) {
                orderAbsentUnit = absent;
            } else if (absent >= 0) {
                // The unit is already behind the frontier (it was passed in file order while the file was
                // loading): the file is rejected all the same, here.
                orderRejectUnit = frontier;
                orderRejectDetail = "the official replay, which goes by the hash order of the names, adds unit " + absent
                        + " '" + nameOf(decls.unitFirstCid(absent)) + "' before '" + nameOf(o.absentConstant())
                        + "', which it mentions (unknown constant; that unit was passed in file order before the file"
                        + " was completely loaded)";
            }
        }
    }

    /**
     * False if the official order cannot differ from file order in what is in the environment when a
     * declaration is added - the case of almost every file, decided without looking at a term. In a
     * file in declaration order every mention is of an earlier declaration, and replaying its NAME
     * makes the official checker add that declaration first, except for the names whose record is not
     * replayed or does not lead to the declaration that provides the constant: {@code Quot.mk},
     * {@code Quot.lift}, {@code Quot.ind}, and the types of a mutual block (the block is declared from
     * the first of its types the replay finishes). Unsafe member records, the third kind, take a file
     * out of file order anyway.
     */
    private boolean orderSensitive() {
        for (int w = Specials.QUOT_MK; w <= Specials.QUOT_IND; w++) {
            if (specials.cid(w) != 0) {
                return true;
            }
        }
        for (int u = 0, n = store.loadedUnits(); u < n; u++) {
            if (decls.unitKind(u) != DeclKind.INDUCT) {
                continue;
            }
            int first = decls.unitFirstCid(u);
            int types = 0;
            for (int cid = first, end = first + decls.unitSize(u); cid < end && types < 2; cid++) {
                if (decls.kind(cid) == DeclKind.INDUCT) {
                    types++;
                }
            }
            if (types > 1) {
                return true;
            }
        }
        return false;
    }

    /**
     * The simulated official order, or null if the file is outside the model or too large. Run only
     * between units (no judgment in flight): the proof blocks it decodes are given back at once.
     */
    private OfficialOrder simulateOfficialOrder(long visits) {
        Overlay overlay = terms.overlay();
        if (overlay == null || !store.loadComplete()) {
            return null;
        }
        long mark;
        try {
            mark = overlay.mark();
        } catch (UnsupportedOperationException e) {
            return null;
        }
        try {
            OfficialOrder o = new OfficialOrder(store, terms, lists, claimsSource, replay, visits);
            return o.available() ? o : null;
        } catch (RuntimeException e) {
            return null;
        } finally {
            overlay.resetTo(mark);
            terms.refresh();
        }
    }

    /**
     * True if the last {@link #advanceTo} stopped, without recording anything, at a unit that is
     * undecided while the file is still loading: call {@link #advanceTo} again once
     * {@code Store.loadComplete()} holds (or earlier; it then stops at the same unit again).
     */
    public boolean waitingForLoad() {
        return waitingForLoad;
    }

    /** The outcomes of the units admitted in dependency order, or null if the pass is in file order. */
    ReplayState replayState() {
        return replay;
    }

    /** Stops the pass at the next unit boundary; it cannot be resumed. */
    public void cancel() {
        cancelled = true;
    }

    /** The frontier as far as this pass moved it. */
    public int envUnits() {
        return env;
    }

    /** The unit the pass failed at, or -1. */
    public int failedUnit() {
        return failedUnit;
    }

    /** Reason of the failure of {@link #failedUnit()}, or null. */
    public Reason failureReason() {
        return failReason;
    }

    /** Human-readable detail of the failure, or null. */
    public String failureMessage() {
        return failMessage;
    }

    /**
     * For tests: the judgment-free part of the admission of inductive unit {@code u} (claims, record
     * consistency, structural checks of its roots) without touching the frontier or the side tables.
     */
    Reason checkBlockStructure(int u) {
        terms.refresh();
        detail = null;
        return prepareBlock(u, decls.unitFirstCid(u), decls.unitSize(u));
    }

    /** For tests: the detail of the last result of {@link #checkBlockStructure(int)}. */
    String lastDetail() {
        return detail;
    }

    // ---- one unit --------------------------------------------------------------------------------------

    /** @return null if the unit passed, else the reason; {@link #detail} then describes it */
    private Reason process(int u) {
        int size = decls.unitSize(u);
        if (decls.status(u) == DeclKind.ST_SKIPPED || size == 0) {
            return null; // dropped unsafe / partial record
        }
        int first = decls.unitFirstCid(u);
        int kind = decls.unitKind(u);
        switch (kind) {
            case DeclKind.AXIOM:
            case DeclKind.DEFN:
            case DeclKind.THM:
            case DeclKind.OPAQUE:
                return plainUnit(first);
            case DeclKind.QUOT_TYPE:
            case DeclKind.QUOT_MK:
            case DeclKind.QUOT_LIFT:
            case DeclKind.QUOT_IND:
            case DeclKind.INDUCT: {
                decided = true;
                long t0 = System.nanoTime();
                try {
                    return kind == DeclKind.INDUCT ? blockUnit(u, first, size) : quotUnit(first, kind);
                } finally {
                    blockNanos += System.nanoTime() - t0;
                    blockUnits++;
                }
            }
            default:
                return fail(Reason.INTERNAL_ERROR, "unit of unknown kind " + kind);
        }
    }

    /**
     * Once per run, as soon as the whole file is loaded: an unsafe or partial record (dropped by the
     * loader) named {@code Quot}, {@code Quot.mk}, {@code Quot.lift} or {@code Quot.ind}. The official
     * checker requires every record with one of these names to be found in its final environment,
     * which a skipped record never is: a rejection, reported at the unit being processed. (The loader
     * rejects such a record itself; this is the second line.)
     */
    private Reason droppedQuotName() {
        if (droppedQuotChecked || !store.loadComplete()) {
            return null;
        }
        droppedQuotChecked = true;
        for (int w = Specials.QUOT; w <= Specials.QUOT_IND; w++) {
            int name = specials.nameOf(w);
            if (name != 0 && names.constOf(name) < 0) {
                return fail(Reason.QUOT_MISMATCH, "an unsafe or partial record is named '" + names.toString(name)
                        + "', which is reserved for the quotient package (reported at the first unit checked after loading)");
            }
        }
        return null;
    }

    private Reason plainUnit(int cid) {
        int which = specialOf(decls.name(cid));
        if (which == Specials.QUOT_MK || which == Specials.QUOT_LIFT || which == Specials.QUOT_IND) {
            // The official checker removes these three names from the constants it replays and afterwards
            // requires them in the kernel environment as quotient constants: always a rejection.
            return fail(Reason.QUOT_MISMATCH, "'" + names.toString(decls.name(cid))
                    + "' is reserved for the quotient package but declared by another kind of record");
        }
        if (which >= 0) {
            bind(which, cid);
        }
        outOfOrder = unresolved(cid);
        return null;
    }

    /**
     * True if a root of the plain constant {@code cid} mentions something that was not declared when
     * the loader wrote it (a later declaration, an unknown or dropped name, a literal before its
     * prerequisites): O(1) from the root summaries. The value of a theorem that is a proof block has
     * no summary until it is decoded; the block's row says whether anything in it was unresolved when
     * it was written ({@code ProofBlocks.hasUnresolved}: one word, exact when false), and only a block
     * that has something unresolved is decoded here to see whether its ROOT reaches it (never in
     * exporter output; the decoded nodes are given back, the worker decodes the block for itself).
     */
    private boolean unresolved(int cid) {
        int rank = decls.rank(cid);
        if (rootUnresolved(decls.type(cid), rank)) {
            return true;
        }
        int v = decls.value(cid);
        if (v > 0) {
            return rootUnresolved(v, rank);
        }
        if (v == 0 || policy.forwardRefs != Policy.ForwardRefs.REORDER || !store.loadComplete()) {
            return false;
        }
        if (!store.blocks().hasUnresolved(-(v + 1))) {
            // Every reference of the block was bound when it was written, to a constant declared by then
            // (rank <= the unit's own): the decoded root cannot have depMax > rank.
            return false;
        }
        if (inspectBlocks == 0) {
            inspectBlocks = terms.overlay() != null ? 1 : 2;
        }
        if (inspectBlocks != 1) {
            return false;
        }
        Overlay overlay = terms.overlay();
        long mark;
        try {
            mark = overlay.mark();
        } catch (UnsupportedOperationException e) {
            inspectBlocks = 2;
            return false;
        }
        try {
            int root = overlay.blockRoot(-(v + 1));
            terms.refresh();
            return root != 0 && rootUnresolved(root, rank);
        } finally {
            overlay.resetTo(mark);
            terms.refresh();
        }
    }

    /**
     * One word of the root's summary: {@code depMax} is {@code DEP_UNRESOLVED} when a CONST / PROJ
     * below is unbound or has the wrong number of levels or a literal's prerequisites were not
     * declared, and otherwise the largest rank mentioned (the unit's own rank for a self-reference,
     * which the worker rejects as before).
     */
    private boolean rootUnresolved(int root, int rank) {
        return root > 0 && terms.depMax(root) > rank;
    }

    /**
     * Pipelined loading only: a nested inductive block was admitted before the file was complete, and
     * a later unit declares a constant under the reserved root {@code _nested}. In a batch run the
     * block itself is declined for that (rlvm-inductive, "Declines"); here the later unit is.
     */
    private Reason lateNestedName(int u) {
        if (!nestedBeforeLoadEnd) {
            return null;
        }
        int n = decls.firstUnitWithNestedRootName();
        if (n >= 0 && n <= u) {
            return fail(Reason.UNSUPPORTED_INPUT, "a constant under the reserved root '_nested' is declared after a"
                    + " nested inductive block was admitted");
        }
        return null;
    }

    private Reason quotUnit(int cid, int kind) {
        int name = decls.name(cid);
        int which = specialOf(name);
        if (which < Specials.QUOT || which > Specials.QUOT_IND) {
            // Official: such a record adds the four canonical constants and is itself never added.
            return fail(Reason.UNSUPPORTED_INPUT, "quot record under the foreign name '" + names.toString(name) + "'");
        }
        int rank = decls.rank(cid);
        int eqName = specials.nameOf(Specials.EQ);
        int eq = eqName == 0 ? 0 : names.constOf(eqName);
        if (eq <= 0) {
            if (!store.loadComplete()) {
                return fail(Reason.FORWARD_REFERENCE, "quot record before 'Eq' is loaded");
            }
            return fail(Reason.QUOT_MISMATCH, "the quotient package needs 'Eq', which is not declared");
        }
        if (decls.rank(eq) >= rank) {
            return fail(Reason.FORWARD_REFERENCE, "'Eq' is declared after the quot record '" + names.toString(name) + "'");
        }
        if (which != Specials.QUOT) {
            int quotName = specials.nameOf(Specials.QUOT);
            int quot = quotName == 0 ? 0 : names.constOf(quotName);
            if (quot <= 0 || decls.kind(quot) != DeclKind.QUOT_TYPE || decls.rank(quot) >= rank) {
                return fail(Reason.UNSUPPORTED_INPUT,
                        "quot record '" + names.toString(name) + "' without an earlier quot record 'Quot'");
            }
        }
        ops.begin(rank, decls.levelParams(cid));
        blockOps.admitQuot(cid); // KernelException(QUOT_MISMATCH) on any difference
        // Cheap re-statement of what the canonical constant fixes besides its type.
        int expectedKind = DeclKind.QUOT_TYPE + (which - Specials.QUOT);
        if (kind != expectedKind) {
            return fail(Reason.QUOT_MISMATCH, "quot record '" + names.toString(name) + "' has the wrong kind");
        }
        if (!quotLevelParams(decls.levelParams(cid), which == Specials.QUOT_LIFT)) {
            return fail(Reason.QUOT_MISMATCH, "quot record '" + names.toString(name) + "' has the wrong universe parameters");
        }
        bind(which, cid);
        return null;
    }

    /** The universe parameters of the quotient constants are literally {@code [u]}, and {@code [u, v]} for lift. */
    private boolean quotLevelParams(int lparams, boolean lift) {
        int n = lists.length(lparams);
        if (n != (lift ? 2 : 1)) {
            return false;
        }
        return isSimpleName(lists.get(lparams, 0), "u") && (!lift || isSimpleName(lists.get(lparams, 1), "v"));
    }

    private boolean isSimpleName(int name, String ascii) {
        return name != 0 && names.prefix(name) == NameTable.ANONYMOUS && names.lastComponentIs(name, ascii);
    }

    // ---- inductive block -------------------------------------------------------------------------------

    private Reason blockUnit(int u, int first, int size) {
        Reason r = prepareBlock(u, first, size);
        if (r != null || typeless) {
            return r;
        }
        Overlay overlay = releaseBlocks ? terms.overlay() : null;
        long mark = 0L;
        if (overlay != null) {
            try {
                mark = overlay.mark();
            } catch (UnsupportedOperationException e) {
                overlay = null;
            }
        }
        r = generateAndCompare(in.rank);
        if (r != null) {
            return r;
        }
        if (gen.numNested > 0 && !store.loadComplete()) {
            nestedBeforeLoadEnd = true;
        }
        commitBlock(u, first, size);
        if (overlay != null) {
            // Nothing derived survives the block (the rows hold exported shared terms and numbers): give
            // the derived terms back at once, as checkPlain does for a declaration. The session's
            // hash-consing index then stays at the size of ONE block instead of growing to the caller's
            // session cap, and every probe of the next block stays in the cache (measured: README).
            ops.begin(Integer.MAX_VALUE, IntLists.EMPTY);
            overlay.resetTo(mark);
            terms.refresh();
        }
        return null;
    }

    /**
     * Regenerates the block in {@link #in} in a scope of rank {@code scope} and compares the exported
     * records with it (checklist 27 - 51). Throws what {@code rlvm-inductive} throws.
     */
    private Reason generateAndCompare(int scope) {
        in.rank = scope;
        ops.begin(scope, in.levelParams);
        try {
            blockOps.generate(in, gen);
        } finally {
            if (blockOps.respelled()) {
                respelledBlocks++;
            }
        }
        long present = blockOps.compare(in, gen);
        return recheckClaims(in.recCount, present);
    }

    /** Commit (checklist 52), then the specials of the block. */
    private void commitBlock(int u, int first, int size) {
        commit(in.typeCount);
        StoreAccess.dropClaims(decls, u);
        for (int cid = first; cid < first + size; cid++) {
            int which = specialOf(decls.name(cid));
            if (which >= 0) {
                bind(which, cid);
            }
        }
    }

    /**
     * Everything about an inductive unit that needs no judgment: reads the claims blob, checks the
     * consistency of the record (checklist 25, 26), fills {@link #in} in block order and runs the
     * structural checks of the type and constructor-type roots (items 28, 33 and 38 as far as they
     * are structural). Sets {@link #typeless} for a record without types, which is decided completely
     * here.
     *
     * @return null if the block may be handed to {@code rlvm-inductive} (or, for a typeless record,
     *     if there is nothing to admit), else the reason
     */
    Reason prepareBlock(int u, int first, int size) {
        Reason r = blockShape(u, first, size, false);
        if (r != null || typeless) {
            return r;
        }
        return blockRoots();
    }

    /**
     * The first half of {@link #prepareBlock}: claims, record consistency, {@link #in}.
     *
     * @param exact the unit is admitted in dependency order with the whole file loaded: records
     *     flagged unsafe inside the safe block are taken as the official checker takes them (a
     *     constructor record gives name and type and is not compared; a recursor record is ignored),
     *     and an {@code all} or constructor list that does not match the record is rejected where the
     *     official replay certainly fails ({@link #inconsistentAll}, {@link #foreignConstructor})
     */
    private Reason blockShape(int u, int first, int size, boolean exact) {
        typeless = false;
        int[] blob = claimsSource.claims(u);
        String defect = Claims.validate(blob);
        if (defect != null) {
            return fail(Reason.INTERNAL_ERROR, defect);
        }
        int nT = blob[Claims.H_TYPES];
        int nC = blob[Claims.H_CTORS];
        int nR = blob[Claims.H_RECS];
        if (nT == 0) {
            typeless = true;
            return typelessItem(u, blob, first, size);
        }
        if (memberEntry.length < size) {
            memberEntry = new int[size];
            memberUsed = new boolean[size];
        }
        Arrays.fill(memberEntry, 0, size, -1);
        Arrays.fill(memberUsed, 0, size, false);
        for (int cid = first; cid < first + size; cid++) {
            if (names.constOf(decls.name(cid)) != cid) {
                return fail(Reason.DUPLICATE_DECLARATION, "the name '" + names.toString(decls.name(cid))
                        + "' is claimed by another declaration");
            }
            int which = specialOf(decls.name(cid));
            if (which == Specials.QUOT_MK || which == Specials.QUOT_LIFT || which == Specials.QUOT_IND) {
                // The official checker removes these names from the constants it replays; what it then does
                // with an inductive record that lists one is not a certain rejection.
                return fail(Reason.UNSUPPORTED_INPUT, "'" + nameOf(cid)
                        + "' is reserved for the quotient package but is a member of an inductive record");
            }
        }

        // ---- type records: must agree; the block's order is the order of `all` (checklist 25)
        int e0 = Claims.typeEntry(0);
        int typeCid0 = blob[e0 + Claims.T_CID];
        if (!isMember(typeCid0, first, size, DeclKind.INDUCT)) {
            return fail(Reason.INTERNAL_ERROR, "claims blob names a type that is not a row of the unit");
        }
        int rank = decls.rank(typeCid0);
        int lparams = decls.levelParams(typeCid0);
        long numParams = Claims.getLong(blob, e0 + Claims.T_NUM_PARAMS);
        int allList = blob[e0 + Claims.T_ALL];
        for (int i = 0; i < nT; i++) {
            int e = Claims.typeEntry(i);
            int cid = blob[e + Claims.T_CID];
            if (!isMember(cid, first, size, DeclKind.INDUCT) || memberEntry[cid - first] >= 0) {
                return fail(Reason.INTERNAL_ERROR, "claims blob names a type that is not a row of the unit");
            }
            memberEntry[cid - first] = i;
            if ((blob[e + Claims.T_FLAGS] & Claims.F_UNSAFE) != 0) {
                return fail(Reason.UNSUPPORTED_INPUT, "unsafe inductive type '" + nameOf(cid) + "' was not dropped");
            }
        }
        // Each type record against the list of the record's types (the official replay builds the block
        // from the `all` of whichever type it reaches first).
        if (typeOrder.length < nT) {
            typeOrder = new int[nT];
        }
        for (int i = 0; i < nT; i++) {
            int e = Claims.typeEntry(i);
            int list = blob[e + Claims.T_ALL];
            boolean matches = lists.length(list) == nT;
            if (matches) {
                Arrays.fill(memberUsed, 0, size, false);
                for (int k = 0; k < nT && matches; k++) {
                    int c = names.constOf(lists.get(list, k));
                    matches = c > 0 && isMember(c, first, size, DeclKind.INDUCT) && !memberUsed[c - first];
                    if (matches) {
                        memberUsed[c - first] = true;
                    }
                }
            }
            if (!matches) {
                return exact ? inconsistentAll(u, blob, i, first, size)
                        : fail(Reason.UNSUPPORTED_INPUT, "'all' of '" + nameOf(blob[e + Claims.T_CID])
                                + "' is not the list of the record's types");
            }
        }
        for (int i = 0; i < nT; i++) {
            int e = Claims.typeEntry(i);
            if (decls.levelParams(blob[e + Claims.T_CID]) != lparams
                    || Claims.getLong(blob, e + Claims.T_NUM_PARAMS) != numParams || !sameList(lists, blob[e + Claims.T_ALL], allList)) {
                // Officially the first type the replay reaches gives levelParams, numParams and the order.
                return fail(Reason.UNSUPPORTED_INPUT,
                        "the type records of one inductive record disagree on levelParams, numParams or all");
            }
        }
        Arrays.fill(memberUsed, 0, size, false);
        for (int k = 0; k < nT; k++) {
            int c = names.constOf(lists.get(allList, k));
            memberUsed[c - first] = true;
            typeOrder[k] = memberEntry[c - first];
        }

        // ---- constructor records
        for (int j = 0; j < nC; j++) {
            int e = Claims.ctorEntry(blob, j);
            int cid = blob[e + Claims.C_CID];
            if (!isMember(cid, first, size, DeclKind.CTOR) || memberEntry[cid - first] >= 0) {
                return fail(Reason.INTERNAL_ERROR, "claims blob names a constructor that is not a row of the unit");
            }
            memberEntry[cid - first] = e;
            if (!exact && (blob[e + Claims.C_FLAGS] & Claims.F_UNSAFE) != 0) {
                return fail(Reason.UNSUPPORTED_INPUT, "unsafe constructor '" + nameOf(cid) + "' inside a safe inductive record");
            }
        }

        // ---- fill the inputs in block order
        ensureInput(nT, nC, nR);
        in.unit = u;
        in.rank = rank;
        in.levelParams = lparams;
        in.numParams = numParams < 0 || numParams > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) numParams;
        in.typeCount = nT;
        int total = 0;
        for (int k = 0; k < nT; k++) {
            int e = Claims.typeEntry(typeOrder[k]);
            int cid = blob[e + Claims.T_CID];
            in.typeCid[k] = cid;
            in.typeName[k] = decls.name(cid);
            in.typeType[k] = decls.type(cid);
            int ctorsList = blob[e + Claims.T_CTORS];
            int n = lists.length(ctorsList);
            in.typeCtorStart[k] = total;
            in.typeCtorCount[k] = n;
            for (int x = 0; x < n; x++) {
                int ctorName = lists.get(ctorsList, x);
                int c = names.constOf(ctorName);
                if (c <= 0 || !isMember(c, first, size, DeclKind.CTOR)) {
                    return exact ? foreignConstructor(u, blob, cid, ctorName, c, first, size)
                            : fail(Reason.UNSUPPORTED_INPUT, "constructor '" + names.toString(ctorName) + "' of '"
                                    + nameOf(cid) + "' has no constructor record in the same inductive record");
                }
                if (memberUsed[c - first]) {
                    return fail(Reason.DUPLICATE_DECLARATION, "constructor '" + names.toString(ctorName)
                            + "' is listed twice in the inductive record");
                }
                memberUsed[c - first] = true;
                int ce = memberEntry[c - first];
                in.ctorCid[total] = c;
                in.ctorName[total] = ctorName;
                in.ctorType[total] = decls.type(c);
                in.ctorClaimLevelParams[total] = decls.levelParams(c);
                in.ctorClaimInduct[total] = blob[ce + Claims.C_INDUCT];
                in.ctorClaimCidx[total] = Claims.getLong(blob, ce + Claims.C_CIDX);
                in.ctorClaimNumParams[total] = Claims.getLong(blob, ce + Claims.C_NUM_PARAMS);
                in.ctorClaimNumFields[total] = Claims.getLong(blob, ce + Claims.C_NUM_FIELDS);
                in.ctorUnsafe[total] = (blob[ce + Claims.C_FLAGS] & Claims.F_UNSAFE) != 0;
                total++;
            }
        }
        in.ctorCount = total;
        // A constructor record no type of the record lists (checklist 51).
        for (int j = 0; j < nC; j++) {
            int e = Claims.ctorEntry(blob, j);
            int cid = blob[e + Claims.C_CID];
            if (!memberUsed[cid - first]) {
                if ((blob[e + Claims.C_FLAGS] & Claims.F_UNSAFE) != 0) {
                    // Ignored by the official checker; here its row would stay a constant nobody generates.
                    return fail(Reason.UNSUPPORTED_INPUT, "unsafe constructor record '" + nameOf(cid)
                            + "' that no type of the inductive record lists");
                }
                return orphanConstructor(u, cid, blob[e + Claims.C_INDUCT]);
            }
        }

        // ---- recursor records (claims only; matched by name against the derived ones)
        in.recCount = nR;
        int at = Claims.firstRec(blob);
        for (int j = 0; j < nR; j++, at = Claims.nextRec(blob, at)) {
            int cid = blob[at + Claims.R_CID];
            if (!isMember(cid, first, size, DeclKind.REC) || memberEntry[cid - first] >= 0) {
                return fail(Reason.INTERNAL_ERROR, "claims blob names a recursor that is not a row of the unit");
            }
            memberEntry[cid - first] = at;
            boolean unsafeRec = (blob[at + Claims.R_FLAGS] & Claims.F_UNSAFE) != 0;
            if (!exact && unsafeRec) {
                return fail(Reason.UNSUPPORTED_INPUT, "unsafe recursor '" + nameOf(cid) + "' inside a safe inductive record");
            }
            in.recUnsafe[j] = unsafeRec;
            int owner = names.constOf(names.prefix(decls.name(cid)));
            if (owner > 0 && decls.kind(owner) == DeclKind.INDUCT && decls.unit(owner) != u) {
                return fail(Reason.UNSUPPORTED_INPUT, "recursor record '" + nameOf(cid)
                        + "' sits in another inductive record than its type");
            }
            in.recCid[j] = cid;
            in.recName[j] = decls.name(cid);
            in.recType[j] = decls.type(cid);
            in.recLevelParams[j] = decls.levelParams(cid);
            in.recAll[j] = blob[at + Claims.R_ALL];
            in.recNumParams[j] = Claims.getLong(blob, at + Claims.R_NUM_PARAMS);
            in.recNumIndices[j] = Claims.getLong(blob, at + Claims.R_NUM_INDICES);
            in.recNumMotives[j] = Claims.getLong(blob, at + Claims.R_NUM_MOTIVES);
            in.recNumMinors[j] = Claims.getLong(blob, at + Claims.R_NUM_MINORS);
            in.recK[j] = (blob[at + Claims.R_FLAGS] & Claims.F_K) != 0;
            int rules = blob[at + Claims.R_NUM_RULES];
            int[] ruleCtor = new int[rules];
            long[] ruleFields = new long[rules];
            int[] ruleRhs = new int[rules];
            for (int r = 0; r < rules; r++) {
                int re = at + Claims.REC_WORDS + Claims.RULE_WORDS * r;
                ruleCtor[r] = blob[re + Claims.RULE_CTOR];
                ruleFields[r] = Claims.getLong(blob, re + Claims.RULE_NFIELDS);
                ruleRhs[r] = blob[re + Claims.RULE_RHS];
            }
            in.recRuleCtorName[j] = ruleCtor;
            in.recRuleFields[j] = ruleFields;
            in.recRuleRhs[j] = ruleRhs;
        }
        in.consistent = true;
        return null;
    }

    /**
     * The second half of {@link #prepareBlock}: the structural checks of the roots the kernel will
     * check (types, then constructor types) of the block in {@link #in}, in file order.
     */
    private Reason blockRoots() {
        int nT = in.typeCount;
        int total = in.ctorCount;
        int rank = in.rank;
        int lparams = in.levelParams;
        if (!structural.distinct(lparams)) {
            return fail(Reason.DUPLICATE_LEVEL_PARAM, "duplicate universe parameter in '" + names.toString(in.typeName[0]) + "'");
        }
        Reason decline = null;
        String declineDetail = null;
        for (int k = 0; k < nT; k++) {
            Reason r = structural.checkRoot(in.typeType[k], rank, lparams, StructuralChecks.OWN_NONE);
            if (r != null) {
                String d = "type of '" + names.toString(in.typeName[k]) + "': " + structural.lastMessage();
                if (r.isReject()) {
                    return fail(r, d);
                }
                if (decline == null) {
                    decline = r;
                    declineDetail = d;
                }
            }
        }
        for (int i = 0; i < total; i++) {
            Reason r = structural.checkRoot(in.ctorType[i], rank, lparams, StructuralChecks.OWN_TYPES);
            if (r != null) {
                String d = "type of '" + names.toString(in.ctorName[i]) + "': " + structural.lastMessage();
                if (r.isReject()) {
                    return fail(r, d);
                }
                if (decline == null) {
                    decline = r;
                    declineDetail = d;
                }
            }
        }
        return decline == null ? null : fail(decline, declineDetail);
    }

    /**
     * An {@code inductive} record without types: its constructor and recursor records can only be
     * valid if a type of another record derives them (which the official checker allows and v1
     * declines); a record that no inductive type can derive is a rejection.
     */
    private Reason typelessItem(int u, int[] blob, int first, int size) {
        Reason decline = null;
        String declineDetail = null;
        int nC = blob[Claims.H_CTORS];
        int nR = blob[Claims.H_RECS];
        for (int j = 0; j < nC; j++) {
            int e = Claims.ctorEntry(blob, j);
            int cid = blob[e + Claims.C_CID];
            if (!isMember(cid, first, size, DeclKind.CTOR)) {
                return fail(Reason.INTERNAL_ERROR, "claims blob names a constructor that is not a row of the unit");
            }
            Reason r = (blob[e + Claims.C_FLAGS] & Claims.F_UNSAFE) != 0
                    ? fail(Reason.UNSUPPORTED_INPUT, "unsafe constructor record '" + nameOf(cid) + "' without its type")
                    : orphanConstructor(u, cid, blob[e + Claims.C_INDUCT]);
            if (r.isReject()) {
                return r;
            }
            if (decline == null) {
                decline = r;
                declineDetail = detail;
            }
        }
        int at = Claims.firstRec(blob);
        for (int j = 0; j < nR; j++, at = Claims.nextRec(blob, at)) {
            int cid = blob[at + Claims.R_CID];
            if (!isMember(cid, first, size, DeclKind.REC)) {
                return fail(Reason.INTERNAL_ERROR, "claims blob names a recursor that is not a row of the unit");
            }
            int owner = names.constOf(names.prefix(decls.name(cid)));
            Reason r;
            if ((blob[at + Claims.R_FLAGS] & Claims.F_UNSAFE) != 0) {
                r = fail(Reason.UNSUPPORTED_INPUT, "unsafe recursor record '" + nameOf(cid) + "' without its type");
            } else if (owner > 0 && decls.kind(owner) == DeclKind.INDUCT) {
                r = fail(Reason.UNSUPPORTED_INPUT, "recursor record '" + nameOf(cid)
                        + "' sits in another inductive record than its type");
            } else if (!store.loadComplete()) {
                r = fail(Reason.FORWARD_REFERENCE, "recursor record '" + nameOf(cid) + "' before its type is loaded");
            } else {
                return fail(Reason.RECORD_MISMATCH, "no such recursor '" + nameOf(cid) + "': no inductive type derives it");
            }
            if (decline == null) {
                decline = r;
                declineDetail = detail;
            }
        }
        return decline == null ? null : fail(decline, declineDetail);
    }

    /** A constructor record that no type of its own record lists. */
    private Reason orphanConstructor(int u, int cid, int inductName) {
        int claimed = names.constOf(inductName);
        if (claimed > 0 && decls.kind(claimed) == DeclKind.INDUCT && decls.unit(claimed) != u) {
            // The official checker keys records by name: the type may sit in another record and adopt it.
            return fail(Reason.UNSUPPORTED_INPUT, "constructor record '" + nameOf(cid)
                    + "' sits in another inductive record than its type '" + names.toString(inductName) + "'");
        }
        if (claimed == 0 && !store.loadComplete()) {
            return fail(Reason.FORWARD_REFERENCE, "constructor record '" + nameOf(cid) + "' before its type is loaded");
        }
        return fail(Reason.RECORD_MISMATCH, "no such constructor '" + nameOf(cid) + "': no inductive type declares it");
    }

    /**
     * After {@code RecordCompare}: maps every derived recursor to its exported record, requires every
     * exported record to have a derived twin, and re-states the scalar fields of checklist items 49
     * and 50 (the terms were compared by {@code RecordCompare}). Fills {@link #listBuf} with the
     * exported index of each derived recursor.
     */
    private Reason recheckClaims(int nR, long present) {
        int nT = in.typeCount;
        int derived = gen.recCount;
        if (derived < nT) {
            return fail(Reason.INTERNAL_ERROR, "fewer derived recursors than types");
        }
        if (derived > 64) {
            return fail(Reason.UNSUPPORTED_INPUT, "inductive record with more than 64 derived recursors");
        }
        // constructors (item 49)
        for (int k = 0; k < nT; k++) {
            int start = in.typeCtorStart[k];
            for (int x = 0; x < in.typeCtorCount[k]; x++) {
                int i = start + x;
                if (gen.ctorIndex[i] != x) {
                    return fail(Reason.INTERNAL_ERROR, "derived constructor index disagrees with the block order");
                }
                if (in.ctorUnsafe[i]) {
                    // Not compared by the official checker (an unsafe record is not replayed); its name and
                    // type were inputs, the derived facts are committed. The row keeps the record's
                    // universe parameters, which must be the generated constructor's.
                    if (in.ctorClaimLevelParams[i] != in.levelParams) {
                        return fail(Reason.UNSUPPORTED_INPUT, "unsafe constructor record '" + names.toString(in.ctorName[i])
                                + "' has other universe parameters than the constructor the kernel generates");
                    }
                    continue;
                }
                String differs = in.ctorClaimLevelParams[i] != in.levelParams ? "levelParams"
                        : in.ctorClaimInduct[i] != in.typeName[k] ? "induct"
                        : in.ctorClaimCidx[i] != x ? "cidx"
                        : in.ctorClaimNumParams[i] != in.numParams ? "numParams"
                        : in.ctorClaimNumFields[i] != gen.ctorNumFields[i] ? "numFields" : null;
                if (differs != null) {
                    return fail(Reason.RECORD_MISMATCH, "invalid constructor '" + names.toString(in.ctorName[i])
                            + "': the exported record differs from the derived constructor in " + differs);
                }
            }
        }
        // recursors (items 50, 51)
        if (listBuf.length < derived + nR) {
            listBuf = new int[derived + nR];
        }
        Arrays.fill(listBuf, 0, derived + nR, -1);
        for (int j = 0; j < derived; j++) {
            int found = -1;
            for (int i = 0; i < nR; i++) {
                if (names.prefix(in.recName[i]) == gen.recNamePrefix[j]
                        && names.lastComponentIs(in.recName[i], gen.recNameLast[j])) {
                    found = i;
                    break;
                }
            }
            boolean isPresent = ((present >>> j) & 1L) != 0;
            if (found < 0) {
                if (isPresent) {
                    return fail(Reason.INTERNAL_ERROR, "record comparison reports a recursor the export does not contain");
                }
                return missingRecursor(j);
            }
            if (!isPresent) {
                return fail(Reason.INTERNAL_ERROR, "record comparison skipped the exported recursor '"
                        + names.toString(in.recName[found]) + "'");
            }
            listBuf[j] = found;
            listBuf[derived + found] = j;
            Reason r = recheckRecursor(j, found);
            if (r != null) {
                return r;
            }
        }
        for (int i = 0; i < nR; i++) {
            if (listBuf[derived + i] < 0) {
                return fail(Reason.RECORD_MISMATCH, "no such recursor '" + names.toString(in.recName[i])
                        + "': the inductive record does not derive it");
            }
        }
        return null;
    }

    private Reason recheckRecursor(int j, int i) {
        String differs = recursorDifference(j, i);
        if (differs != null) {
            return fail(Reason.RECORD_MISMATCH, "invalid recursor '" + names.toString(in.recName[i])
                    + "': the exported record differs from the derived recursor in " + differs);
        }
        int rules = gen.recRuleCtor[j].length;
        for (int r = 0; r < rules; r++) {
            int rhs = in.recRuleRhs[i][r];
            if (rhs <= 0 || Node.isOverlay(rhs)) {
                return fail(Reason.INTERNAL_ERROR, "a rule of '" + names.toString(in.recName[i])
                        + "' passed the record comparison but is not a shared term");
            }
            if (replay == null && ((terms.header(rhs) & Node.F_UNRESOLVED) != 0 || terms.depMax(rhs) > in.rank)) {
                return unboundClaim(i, "a rule");
            }
        }
        int type = in.recType[i];
        if (type <= 0) {
            return fail(Reason.INTERNAL_ERROR, "recursor '" + names.toString(in.recName[i]) + "' has no type");
        }
        if (replay == null && ((terms.header(type) & Node.F_UNRESOLVED) != 0 || terms.depMax(type) > in.rank)) {
            return unboundClaim(i, "the type");
        }
        return null;
    }

    /**
     * An exported recursor term that equals the derived one (the comparison is by name) but contains a
     * node the loader wrote before the constant it names was declared. Committing it would hand
     * unbound nodes to every engine: declined, like any other root with late-bound names.
     */
    private Reason unboundClaim(int i, String what) {
        return fail(Reason.UNSUPPORTED_INPUT, what + " of the exported recursor '" + names.toString(in.recName[i])
                + "' contains an unbound reference (written before the constant it names was declared)");
    }

    /** The first scalar field in which exported recursor {@code i} differs from derived recursor {@code j}, or null. */
    private String recursorDifference(int j, int i) {
        int nT = in.typeCount;
        if (!sameList(lists, in.recLevelParams[i], gen.recLevelParams)) {
            return "levelParams";
        }
        if (in.recNumParams[i] != in.numParams) {
            return "numParams";
        }
        if (in.recNumIndices[i] != gen.recNumIndices[j]) {
            return "numIndices";
        }
        if (in.recNumMotives[i] != gen.recNumMotives) {
            return "numMotives";
        }
        if (in.recNumMinors[i] != gen.recNumMinors) {
            return "numMinors";
        }
        if (in.recK[i] != gen.k) {
            return "k";
        }
        if (lists.length(in.recAll[i]) != nT) {
            return "all";
        }
        for (int k = 0; k < nT; k++) {
            if (lists.get(in.recAll[i], k) != in.typeName[k]) {
                return "all";
            }
        }
        int rules = gen.recRuleCtor[j].length;
        if (in.recRuleCtorName[i].length != rules) {
            return "the number of rules";
        }
        for (int r = 0; r < rules; r++) {
            int ctor = gen.recRuleCtor[j][r];
            if (ctor <= 0 || in.recRuleCtorName[i][r] != decls.name(ctor)) {
                return "the constructor of rule " + r;
            }
            if (in.recRuleFields[i][r] != gen.recRuleFields[j][r]) {
                return "nfields of rule " + r;
            }
        }
        return null;
    }

    private static boolean sameList(IntLists view, int a, int b) {
        if (a == b) {
            return true;
        }
        int n = view.length(a);
        if (n != view.length(b)) {
            return false;
        }
        for (int i = 0; i < n; i++) {
            if (view.get(a, i) != view.get(b, i)) {
                return false;
            }
        }
        return true;
    }

    /** Derived recursor {@code j} has no exported record: a name clash is a rejection, else generated constants are needed. */
    private Reason missingRecursor(int j) {
        String last = gen.recNameLast[j];
        String full = names.toString(gen.recNamePrefix[j]) + "." + last;
        if (store.loadComplete()) {
            byte[] utf8 = last.getBytes(StandardCharsets.UTF_8);
            int name = names.findStr(gen.recNamePrefix[j], utf8, 0, utf8.length);
            if (name != 0 && names.constOf(name) > 0) {
                return fail(Reason.DUPLICATE_DECLARATION, "constant '" + full
                        + "' has already been declared (the name of a derived recursor)");
            }
        }
        return fail(Reason.UNSUPPORTED_INPUT, "derived recursor '" + full
                + "' is not in the export (generated constants are deferred)");
    }

    /** Writes the derived facts of the block (the single writer of the side tables). */
    private void commit(int nT) {
        int derived = gen.recCount;
        // listBuf[0 .. derived) = exported index of each derived recursor (recheckClaims)
        int[] exported = Arrays.copyOf(listBuf, derived);
        int[] all = Arrays.copyOf(in.typeCid, nT);
        for (int k = 0; k < nT; k++) {
            int start = in.typeCtorStart[k];
            int n = in.typeCtorCount[k];
            int[] ctors = Arrays.copyOfRange(in.ctorCid, start, start + n);
            int numIndices = gen.typeNumIndices[k];
            int flags = (gen.isRec ? DeclTable.IF_REC : 0) | (gen.isReflexive ? DeclTable.IF_REFLEXIVE : 0);
            if (n == 1) {
                flags |= DeclTable.IF_ONE_CTOR;
                if (numIndices == 0 && !gen.isRec) {
                    flags |= DeclTable.IF_STRUCT_LIKE;
                    if (gen.ctorNumFields[start] == 0) {
                        flags |= DeclTable.IF_UNIT_LIKE;
                    }
                }
            }
            StoreAccess.setInductive(decls, in.typeCid[k], in.numParams, numIndices, ctors, n, all, nT, flags,
                    gen.numNested, in.recCid[exported[k]]);
            for (int x = 0; x < n; x++) {
                int i = start + x;
                decls.setConstructor(in.ctorCid[i], in.typeCid[k], x, in.numParams, gen.ctorNumFields[i]);
            }
        }
        for (int j = 0; j < derived; j++) {
            int i = exported[j];
            int rules = gen.recRuleCtor[j].length;
            int[] triples = new int[3 * rules];
            for (int r = 0; r < rules; r++) {
                triples[3 * r] = gen.recRuleCtor[j][r];
                triples[3 * r + 1] = gen.recRuleFields[j][r];
                triples[3 * r + 2] = in.recRuleRhs[i][r]; // the exported shared term, identical to the derived one
            }
            StoreAccess.setRecursor(decls, in.recCid[i], in.numParams, gen.recNumIndices[j], gen.recNumMotives,
                    gen.recNumMinors, gen.k ? DeclTable.RF_K : 0, triples, rules, gen.recMajorInduct[j]);
        }
    }

    /** Sizes the input arrays exactly: consumers may rely on the array lengths as well as on the counts. */
    private void ensureInput(int nT, int nC, int nR) {
        if (in.typeCid == null || in.typeCid.length != nT) {
            in.typeCid = new int[nT];
            in.typeName = new int[nT];
            in.typeType = new int[nT];
            in.typeCtorStart = new int[nT];
            in.typeCtorCount = new int[nT];
        }
        if (in.ctorCid == null || in.ctorCid.length != nC) {
            in.ctorCid = new int[nC];
            in.ctorName = new int[nC];
            in.ctorType = new int[nC];
            in.ctorClaimLevelParams = new int[nC];
            in.ctorClaimInduct = new int[nC];
            in.ctorClaimCidx = new long[nC];
            in.ctorClaimNumParams = new long[nC];
            in.ctorClaimNumFields = new long[nC];
            in.ctorUnsafe = new boolean[nC];
        }
        if (in.recCid == null || in.recCid.length != nR) {
            in.recCid = new int[nR];
            in.recName = new int[nR];
            in.recType = new int[nR];
            in.recLevelParams = new int[nR];
            in.recAll = new int[nR];
            in.recNumParams = new long[nR];
            in.recNumIndices = new long[nR];
            in.recNumMotives = new long[nR];
            in.recNumMinors = new long[nR];
            in.recK = new boolean[nR];
            in.recUnsafe = new boolean[nR];
            in.recRuleCtorName = new int[nR][];
            in.recRuleFields = new long[nR][];
            in.recRuleRhs = new int[nR][];
        }
        in.consistent = false;
    }

    private boolean isMember(int cid, int first, int size, int kind) {
        return cid >= first && cid < first + size && decls.kind(cid) == kind;
    }

    // ---- inconsistent records (exact mode) -------------------------------------------------------------

    /**
     * Type record {@code i} of unit {@code u} whose {@code all} is not the list of the unit's types.
     * The official replay builds the block from the {@code all} of the type it reaches first, looking
     * every name up in one flat map; a name without a record becomes a placeholder type named
     * {@code [anonymous]} of type {@code _inhabitedExprDummy} (Lean's default {@code ConstantInfo}).
     * Rejected where that certainly fails, declined otherwise:
     * <ul>
     *   <li>the list holds a name that no record of the file has, and no other type record lists this
     *       type (so the replay does reach it with this list): the kernel rejects the placeholder,
     *       unless the file declares a constant named {@code _inhabitedExprDummy};
     *   <li>the type is in no {@code all} list at all, its own included, so it is never declared, and
     *       a safe constructor record names it as its inductive type or a safe recursor record is
     *       named after it: the official post-check finds no such constructor / recursor.
     * </ul>
     */
    private Reason inconsistentAll(int u, int[] blob, int i, int first, int size) {
        int e = Claims.typeEntry(i);
        int cid = blob[e + Claims.T_CID];
        int name = decls.name(cid);
        int list = blob[e + Claims.T_ALL];
        String what = "'all' of '" + names.toString(name) + "' is not the list of the record's types";
        if (listedInAnotherAll(name, u, blob, i)) {
            return fail(Reason.UNSUPPORTED_INPUT, what + " (and another type record lists it: the official verdict"
                    + " depends on the replay order)");
        }
        boolean listsItself = false;
        boolean hasMissing = false;
        int missing = 0;
        for (int k = 0, n = lists.length(list); k < n; k++) {
            int m = lists.get(list, k);
            if (m == name) {
                listsItself = true;
            } else if (names.constOf(m) == 0 && !hasMissing) {
                hasMissing = true;
                missing = m;
            }
        }
        if (hasMissing) {
            if (placeholderDeclared()) {
                return fail(Reason.UNSUPPORTED_INPUT, what + " (and the file declares '_inhabitedExprDummy')");
            }
            return fail(Reason.INDUCTIVE_INVALID, what + ": '" + names.toString(missing)
                    + "' has no record (the official replay hands the kernel a placeholder type)");
        }
        if (!listsItself) {
            // Never declared: every safe constructor record of this type and its recursor record fail the
            // official post-check ("No such constructor" / "No such recursor"; "Invalid" if another type
            // adopts the name).
            int nC = blob[Claims.H_CTORS];
            for (int j = 0; j < nC; j++) {
                int ce = Claims.ctorEntry(blob, j);
                if (blob[ce + Claims.C_INDUCT] == name && (blob[ce + Claims.C_FLAGS] & Claims.F_UNSAFE) == 0
                        && !quotMember(blob[ce + Claims.C_CID])) {
                    return fail(Reason.RECORD_MISMATCH, what + ": the type is never declared, so there is no such"
                            + " constructor '" + nameOf(blob[ce + Claims.C_CID]) + "'");
                }
            }
            int nR = blob[Claims.H_RECS];
            int at = Claims.firstRec(blob);
            for (int j = 0; j < nR; j++, at = Claims.nextRec(blob, at)) {
                int rn = decls.name(blob[at + Claims.R_CID]);
                if ((blob[at + Claims.R_FLAGS] & Claims.F_UNSAFE) == 0 && names.prefix(rn) == name
                        && !names.isNumeric(rn) && names.lastComponentIs(rn, "rec")) {
                    return fail(Reason.RECORD_MISMATCH, what + ": the type is never declared, so there is no such"
                            + " recursor '" + names.toString(rn) + "'");
                }
            }
        }
        return fail(Reason.UNSUPPORTED_INPUT, what);
    }

    /** True if the constant has one of the three names the official checker sets aside for the quotient package. */
    private boolean quotMember(int cid) {
        int which = specialOf(decls.name(cid));
        return which == Specials.QUOT_MK || which == Specials.QUOT_LIFT || which == Specials.QUOT_IND;
    }

    /**
     * True if a type record other than entry {@code self} of unit {@code u} lists {@code name} in its
     * {@code all}: another type of the unit, or a type of a unit that is not admitted yet (admitted
     * units are consistent: their lists hold their own types only).
     */
    private boolean listedInAnotherAll(int name, int u, int[] blob, int self) {
        if (inAll(blob, name, self)) {
            return true;
        }
        for (int v = 0, n = store.loadedUnits(); v < n; v++) {
            if (v == u || decls.unitSize(v) == 0 || decls.unitKind(v) != DeclKind.INDUCT) {
                continue;
            }
            int[] other = claimsSource.claims(v);
            if (other != null && Claims.validate(other) == null && inAll(other, name, -1)) {
                return true;
            }
        }
        return false;
    }

    private boolean inAll(int[] blob, int name, int except) {
        for (int i = 0, nT = blob[Claims.H_TYPES]; i < nT; i++) {
            if (i == except) {
                continue;
            }
            int list = blob[Claims.typeEntry(i) + Claims.T_ALL];
            for (int k = 0, n = lists.length(list); k < n; k++) {
                if (lists.get(list, k) == name) {
                    return true;
                }
            }
        }
        return false;
    }

    /** True if the file has a record named {@code _inhabitedExprDummy} (what Lean's default expression mentions). */
    private boolean placeholderDeclared() {
        byte[] utf8 = "_inhabitedExprDummy".getBytes(StandardCharsets.UTF_8);
        int n = names.findStr(NameTable.ANONYMOUS, utf8, 0, utf8.length);
        return n != 0 && names.constOf(n) != 0;
    }

    /**
     * Type {@code typeCid} of unit {@code u} lists the constructor name {@code ctorName}, which is not
     * a constructor record of the unit ({@code c} = what the name is claimed by). The official replay
     * takes name and type of whatever record has that name:
     * <ul>
     *   <li>no record: a placeholder constructor of type {@code _inhabitedExprDummy}, which the kernel
     *       rejects (unless the file declares that name);
     *   <li>an axiom, definition, theorem or opaque: the name is declared twice, by the block and by
     *       that declaration, whichever comes first ("already declared");
     *   <li>an inductive type: the same clash, or (a type that is never declared) a constructor whose
     *       type ends in a sort;
     *   <li>a safe recursor record of this unit: its type ends in a motive, and if it were a valid
     *       constructor type the post-check would find a constructor where the record is a recursor;
     *   <li>anything else (a constructor record of another inductive record, which the official
     *       checker adopts; a dropped or quot record): declined.
     * </ul>
     */
    private Reason foreignConstructor(int u, int[] blob, int typeCid, int ctorName, int c, int first, int size) {
        String what = "constructor '" + names.toString(ctorName) + "' of '" + nameOf(typeCid)
                + "' has no constructor record in the same inductive record";
        if (c == 0) {
            if (placeholderDeclared()) {
                return fail(Reason.UNSUPPORTED_INPUT, what + " (and the file declares '_inhabitedExprDummy')");
            }
            return fail(Reason.INDUCTIVE_INVALID, what + ", nor any other record (the official replay hands the"
                    + " kernel a placeholder constructor)");
        }
        if (c < 0) {
            return fail(Reason.UNSUPPORTED_INPUT, what + " (the name belongs to a dropped unsafe or partial record)");
        }
        switch (decls.kind(c)) {
            case DeclKind.AXIOM:
            case DeclKind.DEFN:
            case DeclKind.THM:
            case DeclKind.OPAQUE:
                return fail(Reason.DUPLICATE_DECLARATION, what + ": the name is declared by another kind of record");
            case DeclKind.INDUCT:
                return fail(Reason.DUPLICATE_DECLARATION, what + ": the name is an inductive type");
            case DeclKind.REC:
                // A safe recursor record must be the recursor the kernel generates under that name; here
                // the name becomes a constructor (or the two declarations of the name collide).
                if (safeMemberRecord(c)) {
                    return fail(Reason.RECORD_MISMATCH, what + ": the name is a recursor record");
                }
                return fail(Reason.UNSUPPORTED_INPUT, what);
            case DeclKind.CTOR: {
                // A constructor record of another inductive record. The official checker adopts it when it
                // says that it belongs to this type; if it names another type, the generated constructor
                // differs from the record ("Invalid constructor"), or two blocks declare the name.
                int claimed = claimedInduct(c);
                if (claimed != 0 && claimed != decls.name(typeCid) && safeMemberRecord(c)) {
                    return fail(Reason.RECORD_MISMATCH, what + ": the name is a constructor record of '"
                            + names.toString(claimed) + "'");
                }
                return fail(Reason.UNSUPPORTED_INPUT, what);
            }
            default:
                return fail(Reason.UNSUPPORTED_INPUT, what);
        }
    }

    /**
     * True if the constructor or recursor record of constant {@code c} is certainly safe, i.e. replayed
     * by the official checker: a member of a block admitted in file order (none of those has unsafe
     * members), or of a block whose claims say so.
     */
    private boolean safeMemberRecord(int c) {
        int v = decls.unit(c);
        int[] blob = claimsSource.claims(v);
        if (blob == null || Claims.validate(blob) != null) {
            // admitted already: in file order (no unsafe member), or in dependency order (flags recorded)
            return replay != null && (v < replay.first
                    || (replay.done(v) && replay.reasonOf(v) == null && !replay.isUnsafeMember(c)));
        }
        for (int j = 0, nC = blob[Claims.H_CTORS]; j < nC; j++) {
            int e = Claims.ctorEntry(blob, j);
            if (blob[e + Claims.C_CID] == c) {
                return (blob[e + Claims.C_FLAGS] & Claims.F_UNSAFE) == 0;
            }
        }
        int at = Claims.firstRec(blob);
        for (int j = 0, nR = blob[Claims.H_RECS]; j < nR; j++, at = Claims.nextRec(blob, at)) {
            if (blob[at + Claims.R_CID] == c) {
                return (blob[at + Claims.R_FLAGS] & Claims.F_UNSAFE) == 0;
            }
        }
        return false;
    }

    /** The {@code induct} name the constructor record of constant {@code c} gives, or 0 if it is not known any more. */
    private int claimedInduct(int c) {
        int[] blob = claimsSource.claims(decls.unit(c));
        if (blob != null && Claims.validate(blob) == null) {
            for (int j = 0, nC = blob[Claims.H_CTORS]; j < nC; j++) {
                int e = Claims.ctorEntry(blob, j);
                if (blob[e + Claims.C_CID] == c) {
                    return blob[e + Claims.C_INDUCT];
                }
            }
            return 0;
        }
        // admitted: the record was compared, so it names the type that lists it
        int induct = decls.ctorInduct(c);
        return induct > 0 ? decls.name(induct) : 0;
    }

    // ---- replay: admission in dependency order -----------------------------------------------------------
    //
    // From the first unit of a completely loaded file that file order cannot decide, the pass decides
    // EVERY unit itself, dependencies first, with the judgments of its own KernelOps, as the official
    // checker replays (Lean.Replay): a unit is checked once everything its kernel-checked roots mention
    // has been admitted, a cycle is a rejection ("unknown constant": the official replay adds a member
    // of the cycle while the next one is still pending), a unit that depends on a rejected or declined
    // unit inherits that outcome. The scope of a unit is the smallest rank above every rank in its
    // dependency closure, so the engine's own visibility test stays as tight as the order allows.
    //
    // The official order between independent declarations is a hash order that is not reproduced. A
    // unit whose official verdict depends on it is declined: a constant that exists only because the
    // kernel adds it together with something else (a constructor or recursor whose record is flagged
    // unsafe, Quot.mk / Quot.lift / Quot.ind, the prerequisites of a literal) is in the environment in
    // time only if a constant the unit mentions forces it (see presence()).

    /** Collects the distinct constants a unit's roots mention. */
    private static final class Collector implements StructuralChecks.RefSink {
        int[] cids = new int[16];
        int n;
        boolean nat;
        boolean str;
        private int[] table = new int[64];

        void reset() {
            if (n != 0) {
                if (table.length > 4096) {
                    table = new int[64];
                } else {
                    Arrays.fill(table, 0);
                }
            }
            n = 0;
            nat = false;
            str = false;
        }

        boolean contains(int cid) {
            int mask = table.length - 1;
            int h = (cid * 0x9E3779B1) >>> 7 & mask;
            while (table[h] != 0) {
                if (table[h] == cid) {
                    return true;
                }
                h = (h + 1) & mask;
            }
            return false;
        }

        @Override
        public void constant(int cid) {
            int mask = table.length - 1;
            int h = (cid * 0x9E3779B1) >>> 7 & mask;
            while (table[h] != 0) {
                if (table[h] == cid) {
                    return;
                }
                h = (h + 1) & mask;
            }
            table[h] = cid;
            if (n == cids.length) {
                cids = Arrays.copyOf(cids, n * 2);
            }
            cids[n++] = cid;
            if (n * 2 > table.length) {
                int[] old = table;
                table = new int[old.length * 2];
                mask = table.length - 1;
                for (int x : old) {
                    if (x != 0) {
                        int g = (x * 0x9E3779B1) >>> 7 & mask;
                        while (table[g] != 0) {
                            g = (g + 1) & mask;
                        }
                        table[g] = x;
                    }
                }
            }
        }

        @Override
        public void literal(boolean string) {
            if (string) {
                str = true;
            } else {
                nat = true;
            }
        }
    }

    private static final int[] NO_NEEDS = new int[0];
    private static final int[] NAT_PREREQ = {Specials.NAT, Specials.NAT_ZERO, Specials.NAT_SUCC};
    private static final int[] STRING_PREREQ = {Specials.NAT, Specials.NAT_ZERO, Specials.NAT_SUCC, Specials.STRING,
        Specials.STRING_OF_LIST, Specials.CHAR, Specials.CHAR_OF_NAT, Specials.LIST_NIL, Specials.LIST_CONS};

    /** The frontier reached unit {@code u} in dependency-order mode: decide it (if not done) and report. */
    private Reason replayAt(int u) {
        ReplayState rs = replay;
        if (!rs.covers(u)) {
            return fail(Reason.INTERNAL_ERROR, "unit " + u + " was loaded after admission in dependency order began");
        }
        replayRun(u);
        int i = u - rs.first;
        if (decls.status(u) == DeclKind.ST_SKIPPED || decls.unitSize(u) == 0) {
            return null;
        }
        int kind = decls.unitKind(u);
        if (kind == DeclKind.AXIOM || kind == DeclKind.DEFN || kind == DeclKind.THM || kind == DeclKind.OPAQUE) {
            return null; // the worker that draws the unit reports the recorded outcome (Admission)
        }
        decided = true;
        detail = rs.message[i];
        return rs.reason[i];
    }

    /** Decides unit {@code root} and, before it, every undecided unit it depends on. Iterative. */
    private void replayRun(int root) {
        ReplayState rs = replay;
        if (rs.state[root - rs.first] == ReplayState.DONE) {
            return;
        }
        int sp = 0;
        replayImplicit[sp] = false;
        replayStack[sp++] = root;
        while (sp > 0) {
            int x = replayStack[sp - 1];
            int i = x - rs.first;
            if (rs.state[i] == ReplayState.NEW) {
                rs.state[i] = ReplayState.ACTIVE;
                rs.cursor[i] = 0;
                rs.cycle[i] = 0;
                rs.needs[i] = null;
                Reason r = collectGuarded(x);
                if (rs.needs[i] == null) {
                    record(x, r, detail);
                    sp--;
                    continue;
                }
            }
            int[] needs = rs.needs[i];
            boolean pushed = false;
            while (rs.cursor[i] < needs.length) {
                int e = needs[rs.cursor[i]];
                int p = e > 0 ? decls.unit(e) : ~e;
                if (p < rs.first || p >= rs.total || rs.state[p - rs.first] == ReplayState.DONE) {
                    rs.cursor[i]++;
                    continue;
                }
                if (rs.state[p - rs.first] == ReplayState.ACTIVE) {
                    if (rs.cycle[i] == 0) {
                        // A cycle is a certain rejection only if every step of it is a mention the kernel
                        // checks; one that passes through a dependency of our own is undecided.
                        boolean official = e > 0;
                        for (int k = sp - 1; k >= 0 && replayStack[k] != p; k--) {
                            official &= !replayImplicit[k];
                        }
                        rs.cycle[i] = official ? rs.cursor[i] + 1 : -(rs.cursor[i] + 1);
                    }
                    rs.cursor[i]++;
                    continue;
                }
                if (sp == replayStack.length) {
                    replayStack = Arrays.copyOf(replayStack, sp * 2);
                    replayImplicit = Arrays.copyOf(replayImplicit, sp * 2);
                }
                replayImplicit[sp] = e < 0;
                replayStack[sp++] = p;
                pushed = true;
                break;
            }
            if (pushed) {
                continue;
            }
            Reason r = decideGuarded(x);
            record(x, r, detail);
            sp--;
        }
    }

    private void record(int x, Reason r, String what) {
        ReplayState rs = replay;
        int i = x - rs.first;
        rs.reason[i] = r;
        String m = null;
        if (r != null) {
            int kind = decls.unitSize(x) == 0 ? DeclKind.NONE : decls.unitKind(x);
            boolean plain = kind == DeclKind.AXIOM || kind == DeclKind.DEFN || kind == DeclKind.THM
                    || kind == DeclKind.OPAQUE;
            m = plain ? "'" + nameOf(decls.unitFirstCid(x)) + "': " + what : what;
        }
        rs.message[i] = m;
        rs.state[i] = ReplayState.DONE;
    }

    private Reason collectGuarded(int x) {
        detail = null;
        try {
            return collect(x);
        } catch (Overlay.OverlayFullException e) {
            replay.needs[x - replay.first] = null;
            return fail(Reason.BUDGET_MEMORY, "term overlay full during the environment pass");
        } catch (RuntimeException e) {
            replay.needs[x - replay.first] = null;
            return fail(Reason.INTERNAL_ERROR, "exception in the environment pass: " + e);
        }
    }

    private Reason decideGuarded(int x) {
        detail = null;
        blockOps.clearDeclineDetail();
        if (poisoned) {
            return fail(Reason.STACK_DEPTH, "the environment session was abandoned after a stack overflow");
        }
        try {
            return decide(x);
        } catch (KernelException e) {
            return fail(e.reason(), "kernel error " + e.reason());
        } catch (ResourceException e) {
            if (e.reason() == Reason.STACK_DEPTH) {
                poisoned = true;
            }
            return fail(e.reason(), gaveUp(e));
        } catch (Overlay.OverlayFullException e) {
            return fail(Reason.BUDGET_MEMORY, "term overlay full during the environment pass");
        } catch (UnsupportedOperationException e) {
            return fail(Reason.UNSUPPORTED_INPUT, "not implemented: " + e.getMessage());
        } catch (RuntimeException e) {
            return fail(Reason.INTERNAL_ERROR, "exception in the environment pass: " + e);
        }
    }

    /**
     * First half of a unit: what its kernel-checked roots mention. Sets {@code replay.needs} of the
     * unit, or leaves it null and returns the unit's outcome when that is already certain (null = the
     * unit needs no admission).
     */
    private Reason collect(int x) {
        ReplayState rs = replay;
        int i = x - rs.first;
        int size = decls.unitSize(x);
        if (decls.status(x) == DeclKind.ST_SKIPPED || size == 0) {
            return null;
        }
        int first = decls.unitFirstCid(x);
        int kind = decls.unitKind(x);
        Collector c = collector;
        c.reset();
        switch (kind) {
            case DeclKind.AXIOM:
            case DeclKind.DEFN:
            case DeclKind.THM:
            case DeclKind.OPAQUE: {
                Reason r = collectPlain(first, kind, x, c);
                if (r != null) {
                    return r;
                }
                rs.weak[i] = null;
                break;
            }
            case DeclKind.INDUCT: {
                Reason r = blockShape(x, first, size, true);
                if (r != null || typeless) {
                    return r;
                }
                int lparams = in.levelParams;
                if (!structural.distinct(lparams)) {
                    return fail(Reason.DUPLICATE_LEVEL_PARAM, "duplicate universe parameter in '"
                            + names.toString(in.typeName[0]) + "'");
                }
                Collector h = headerCollector;
                h.reset();
                Reason decline = null;
                String declineDetail = null;
                for (int k = 0; k < in.typeCount; k++) {
                    r = structural.collectRoot(in.typeType[k], x, lparams, StructuralChecks.OWN_NONE, h);
                    if (r != null) {
                        String d = "type of '" + names.toString(in.typeName[k]) + "': " + structural.lastMessage();
                        if (r.isReject()) {
                            return fail(r, d);
                        }
                        if (decline == null) {
                            decline = r;
                            declineDetail = d;
                        }
                    }
                }
                for (int j = 0; j < in.ctorCount; j++) {
                    r = structural.collectRoot(in.ctorType[j], x, lparams, StructuralChecks.OWN_TYPES, c);
                    if (r != null) {
                        String d = "type of '" + names.toString(in.ctorName[j]) + "': " + structural.lastMessage();
                        if (r.isReject()) {
                            return fail(r, d);
                        }
                        if (decline == null) {
                            decline = r;
                            declineDetail = d;
                        }
                    }
                }
                if (decline != null) {
                    return fail(decline, declineDetail);
                }
                // A mutual block is declared by whichever of its types the official replay finishes first,
                // and only that type's header is replayed beforehand: what only headers mention is not forced.
                int[] weak = null;
                if (in.typeCount > 1) {
                    int w = 0;
                    for (int k = 0; k < h.n; k++) {
                        if (!c.contains(h.cids[k])) {
                            w++;
                        }
                    }
                    weak = new int[w];
                    w = 0;
                    for (int k = 0; k < h.n; k++) {
                        if (!c.contains(h.cids[k])) {
                            weak[w++] = h.cids[k];
                        }
                    }
                }
                for (int k = 0; k < h.n; k++) {
                    c.constant(h.cids[k]);
                }
                c.nat |= h.nat;
                c.str |= h.str;
                rs.weak[i] = weak;
                break;
            }
            case DeclKind.QUOT_TYPE:
            case DeclKind.QUOT_MK:
            case DeclKind.QUOT_LIFT:
            case DeclKind.QUOT_IND:
                rs.weak[i] = null;
                return collectQuot(first, x);
            default:
                return fail(Reason.INTERNAL_ERROR, "unit of unknown kind " + kind);
        }
        // the prerequisites of literals: needed by the engine, not replayed by the official checker
        int extra = 0;
        int[] implicit = null;
        if (c.nat || c.str) {
            int[] prereq = c.str ? STRING_PREREQ : NAT_PREREQ;
            implicit = new int[prereq.length];
            for (int w : prereq) {
                int cid = specials.cid(w);
                if (cid <= 0) {
                    return fail(Reason.UNSUPPORTED_PRELUDE, (c.str ? "String" : "Nat") + " literal used, but the constants"
                            + " behind " + (c.str ? "String" : "Nat") + " literals are not all declared");
                }
                int p = decls.unit(cid);
                if (p == x) {
                    return fail(Reason.UNSUPPORTED_PRELUDE, "literal used inside the declaration of a constant behind literals");
                }
                boolean dup = false;
                for (int k = 0; k < extra; k++) {
                    dup |= implicit[k] == ~p;
                }
                if (!dup) {
                    implicit[extra++] = ~p;
                }
            }
        }
        int[] needs = new int[c.n + extra];
        System.arraycopy(c.cids, 0, needs, 0, c.n);
        for (int k = 0; k < extra; k++) {
            needs[c.n + k] = implicit[k];
        }
        rs.needs[i] = needs;
        return null;
    }

    /** The structural checks of a plain constant (as {@code StructuralChecks.checkConstant}) with its mentions collected. */
    private Reason collectPlain(int cid, int kind, int x, Collector c) {
        int name = decls.name(cid);
        if (quotMember(cid)) {
            return fail(Reason.QUOT_MISMATCH, "'" + names.toString(name)
                    + "' is reserved for the quotient package but declared by another kind of record");
        }
        if (names.constOf(name) != cid) {
            return fail(Reason.DUPLICATE_DECLARATION, "the name is claimed by another declaration");
        }
        int lparams = decls.levelParams(cid);
        if (!structural.distinct(lparams)) {
            return fail(Reason.DUPLICATE_LEVEL_PARAM, "duplicate universe parameter");
        }
        if (decls.numLevelParams(cid) != lists.length(lparams)) {
            return fail(Reason.UNSUPPORTED_INPUT, "universe parameter count does not fit its row");
        }
        Reason first = structural.collectRoot(decls.type(cid), x, lparams, StructuralChecks.OWN_NONE, c);
        if (first != null && first.isReject()) {
            return fail(first, structural.lastMessage());
        }
        String firstMessage = structural.lastMessage();
        if (kind != DeclKind.AXIOM) {
            int root = valueRoot(cid);
            if (root <= 0) {
                return fail(Reason.INTERNAL_ERROR, "declaration has no value");
            }
            Reason second = structural.collectRoot(root, x, lparams, StructuralChecks.OWN_NONE, c);
            if (second != null && (second.isReject() || first == null)) {
                return fail(second, structural.lastMessage());
            }
        }
        if (first != null) {
            return fail(first, firstMessage);
        }
        return null;
    }

    /** The value of a definition, theorem or opaque as a term of the pass's session (a proof block is decoded), or 0. */
    private int valueRoot(int cid) {
        int v = decls.value(cid);
        if (v > 0) {
            return v;
        }
        if (v == 0) {
            return 0;
        }
        Overlay overlay = terms.overlay();
        if (overlay == null) {
            return 0;
        }
        int root = overlay.blockRoot(-(v + 1));
        terms.refresh();
        return root;
    }

    /**
     * A quot record. Officially only a record under another name than {@code Quot.mk / lift / ind}
     * is replayed (it makes the kernel add all four constants, after {@code Eq}); the three others are
     * compared with the kernel's constants at the very end. Here every record is admitted against the
     * canonical type, which needs the rows of {@code Eq}, {@code Quot} and {@code Quot.mk}.
     */
    private Reason collectQuot(int cid, int x) {
        ReplayState rs = replay;
        int name = decls.name(cid);
        int which = specialOf(name);
        int quotName = specials.nameOf(Specials.QUOT);
        int quot = quotName == 0 ? 0 : names.constOf(quotName);
        boolean quotRecord = quot > 0 && decls.kind(quot) == DeclKind.QUOT_TYPE;
        int n = 0;
        int[] needs = new int[3];
        if (which < Specials.QUOT || which > Specials.QUOT_IND) {
            // Replayed officially: adds the four canonical constants (once), never a constant of this name.
            if (!quotRecord) {
                return fail(Reason.UNSUPPORTED_INPUT, "quot record under the foreign name '" + names.toString(name)
                        + "' in a file without a quot record 'Quot' (generated constants are deferred)");
            }
            needs[n++] = ~decls.unit(quot);
            rs.needs[x - rs.first] = Arrays.copyOf(needs, n);
            return null;
        }
        int eqName = specials.nameOf(Specials.EQ);
        int eq = eqName == 0 ? 0 : names.constOf(eqName);
        if (eq <= 0) {
            return fail(Reason.QUOT_MISMATCH, "the quotient package needs 'Eq', which is not declared");
        }
        if (decls.unit(eq) == x) {
            return fail(Reason.INTERNAL_ERROR, "'Eq' is the quot record itself");
        }
        needs[n++] = which == Specials.QUOT ? eq : ~decls.unit(eq);
        if (which != Specials.QUOT) {
            if (!quotRecord) {
                if (!quotTriggerExists()) {
                    // Nothing makes the official kernel add the quotient constants: the post-check finds none.
                    return fail(Reason.QUOT_MISMATCH, "quot record '" + names.toString(name) + "' in a file without a"
                            + " quot record that makes the kernel add the quotient package");
                }
                return fail(Reason.UNSUPPORTED_INPUT, "quot record '" + names.toString(name)
                        + "' without a quot record 'Quot' (generated constants are deferred)");
            }
            needs[n++] = ~decls.unit(quot);
            if (which == Specials.QUOT_IND) {
                int mkName = specials.nameOf(Specials.QUOT_MK);
                int mk = mkName == 0 ? 0 : names.constOf(mkName);
                if (mk > 0 && decls.unit(mk) != x) {
                    needs[n++] = ~decls.unit(mk);
                }
            }
        }
        rs.needs[x - rs.first] = Arrays.copyOf(needs, n);
        return null;
    }

    /** True if the file has a quot record under a name the official checker replays (any but Quot.mk / lift / ind). */
    private boolean quotTriggerExists() {
        for (int cid = 1, n = decls.constantCount(); cid <= n; cid++) {
            int kind = decls.kind(cid);
            if (kind >= DeclKind.QUOT_TYPE && kind <= DeclKind.QUOT_IND && !quotMember(cid)) {
                return true;
            }
        }
        return false;
    }

    /** Second half of a unit, when everything it depends on is decided. */
    private Reason decide(int x) {
        ReplayState rs = replay;
        int i = x - rs.first;
        Reason r = dependencies(x);
        if (r != null) {
            return r;
        }
        int first = decls.unitFirstCid(x);
        int size = decls.unitSize(x);
        int kind = decls.unitKind(x);
        int scope = Math.max(decls.rank(first), rs.closureMax[i] + 1);
        switch (kind) {
            case DeclKind.AXIOM:
            case DeclKind.DEFN:
            case DeclKind.THM:
            case DeclKind.OPAQUE: {
                r = checkPlain(first, kind, scope);
                if (r == null) {
                    r = presence(x);
                }
                if (r == null) {
                    r = crossCheck(x);
                }
                if (r == null) {
                    int which = specialOf(decls.name(first));
                    if (which >= 0) {
                        bind(which, first);
                    }
                }
                return r;
            }
            case DeclKind.INDUCT: {
                r = blockShape(x, first, size, true);
                if (r != null || typeless) {
                    return r != null ? r : fail(Reason.INTERNAL_ERROR, "inductive record changed shape");
                }
                for (int k = 0; k < in.typeCount; k++) {
                    in.typeType[k] = rebind(in.typeType[k]);
                }
                for (int j = 0; j < in.ctorCount; j++) {
                    in.ctorType[j] = rebind(in.ctorType[j]);
                }
                terms.refresh();
                r = generateAndCompare(scope);
                if (r == null) {
                    r = presence(x);
                }
                if (r == null) {
                    r = crossCheck(x);
                }
                if (r == null) {
                    for (int j = 0; j < in.ctorCount; j++) {
                        if (in.ctorUnsafe[j]) {
                            rs.markUnsafeMember(in.ctorCid[j]);
                        }
                    }
                    for (int j = 0; j < in.recCount; j++) {
                        if (in.recUnsafe[j]) {
                            rs.markUnsafeMember(in.recCid[j]);
                        }
                    }
                    commitBlock(x, first, size);
                }
                return r;
            }
            default:
                return decideQuot(first, kind, scope);
        }
    }

    /**
     * What unit {@code x} inherits from its dependencies: a cycle, a rejected or declined dependency,
     * a constant that the official environment never contains. Computes the unit's {@code closureMax}.
     */
    private Reason dependencies(int x) {
        ReplayState rs = replay;
        int i = x - rs.first;
        int[] needs = rs.needs[i];
        if (rs.cycle[i] > 0) {
            int e = needs[rs.cycle[i] - 1];
            return fail(Reason.UNKNOWN_CONSTANT, "unknown constant '" + nameOf(e) + "' (cyclic dependency: it is not"
                    + " in the environment when the official replay adds this declaration)");
        }
        if (rs.cycle[i] < 0) {
            return fail(Reason.UNSUPPORTED_INPUT, "cyclic dependency through the prerequisites of a literal or of the"
                    + " quotient package");
        }
        Reason decline = null;
        String declineDetail = null;
        int max = 0;
        for (int e : needs) {
            int p = e > 0 ? decls.unit(e) : ~e;
            int rank = e > 0 ? decls.rank(e) : decls.rank(decls.unitFirstCid(p));
            max = Math.max(max, rank);
            if (p >= rs.first && p < rs.total) {
                int j = p - rs.first;
                max = Math.max(max, rs.closureMax[j]);
                Reason pr = rs.reason[j];
                if (pr != null) {
                    String d = "depends on '" + nameOf(e > 0 ? e : decls.unitFirstCid(p)) + "', which is "
                            + (pr.isReject() ? "rejected" : "declined") + " (" + pr + "): " + rs.message[j];
                    if (pr.isReject()) {
                        return fail(pr, d);
                    }
                    if (decline == null) {
                        decline = pr;
                        declineDetail = d;
                    }
                    continue;
                }
            }
            if (e > 0) {
                int k = decls.kind(e);
                if (k >= DeclKind.QUOT_TYPE && k <= DeclKind.QUOT_IND) {
                    int which = specialOf(decls.name(e));
                    if (which < Specials.QUOT || which > Specials.QUOT_IND) {
                        // The official checker never adds a quot record under a foreign name.
                        if (structural.kernelMayGenerate(decls.name(e))) {
                            if (decline == null) {
                                decline = Reason.UNSUPPORTED_INPUT;
                                declineDetail = "constant '" + nameOf(e) + "' is a quot record under a foreign name,"
                                        + " but the official kernel generates a constant of that name";
                            }
                            continue;
                        }
                        return fail(Reason.UNKNOWN_CONSTANT, "unknown constant '" + nameOf(e)
                                + "' (a quot record under a foreign name is never added to the environment)");
                    }
                }
            }
        }
        rs.closureMax[i] = max;
        return decline == null ? null : fail(decline, declineDetail);
    }

    /** The official {@code add_axiom / add_definition / add_theorem / add_opaque} body through the pass's KernelOps. */
    private Reason checkPlain(int cid, int kind, int scope) {
        Overlay overlay = terms.overlay();
        long mark = 0L;
        boolean marked = false;
        if (overlay != null) {
            try {
                mark = overlay.mark();
                marked = true;
            } catch (UnsupportedOperationException e) {
                marked = false;
            }
        }
        boolean intact = true;
        try {
            ops.begin(scope, decls.levelParams(cid));
            int type = decls.type(cid);
            ops.ensureSort(ops.check(type));
            if (kind == DeclKind.AXIOM) {
                return null;
            }
            if (kind == DeclKind.THM && !ops.isProp(type)) {
                return fail(Reason.THEOREM_NOT_PROP, "the type of the theorem is not a proposition");
            }
            int value = valueRoot(cid);
            if (value <= 0) {
                return fail(Reason.INTERNAL_ERROR, "declaration has no value");
            }
            int valueType = ops.check(value);
            if (!ops.isDefEq(valueType, type)) {
                return fail(Reason.DECL_TYPE_MISMATCH, "the type of the value is not the declared type");
            }
            return null;
        } catch (ResourceException e) {
            intact = e.reason() != Reason.STACK_DEPTH;
            throw e;
        } finally {
            if (marked && intact) {
                // Nothing of a declaration survives it, as in an engine session.
                ops.begin(Integer.MAX_VALUE, IntLists.EMPTY);
                overlay.resetTo(mark);
                terms.refresh();
            }
        }
    }

    private Reason decideQuot(int cid, int kind, int scope) {
        int name = decls.name(cid);
        int which = specialOf(name);
        if (which < Specials.QUOT || which > Specials.QUOT_IND) {
            // Under a foreign name: the canonical record 'Quot' (a dependency) is admitted, so the package
            // exists whichever of the two the official replay reaches first; this record adds nothing.
            return null;
        }
        ops.begin(scope, decls.levelParams(cid));
        blockOps.admitQuot(cid, scope); // KernelException(QUOT_MISMATCH) on any difference
        int expectedKind = DeclKind.QUOT_TYPE + (which - Specials.QUOT);
        if (kind != expectedKind) {
            return fail(Reason.QUOT_MISMATCH, "quot record '" + names.toString(name) + "' has the wrong kind");
        }
        if (!quotLevelParams(decls.levelParams(cid), which == Specials.QUOT_LIFT)) {
            return fail(Reason.QUOT_MISMATCH, "quot record '" + names.toString(name) + "' has the wrong universe parameters");
        }
        bind(which, cid);
        return null;
    }

    // ---- replay: is everything the unit mentions certainly in the official environment in time? ------------

    /** True if replaying the name of constant {@code cid} makes the official checker add the constant's provider. */
    private boolean forcing(int cid) {
        switch (decls.kind(cid)) {
            case DeclKind.CTOR:
            case DeclKind.REC:
                return !replay.isUnsafeMember(cid);
            case DeclKind.QUOT_MK:
            case DeclKind.QUOT_LIFT:
            case DeclKind.QUOT_IND:
                return false;
            case DeclKind.QUOT_TYPE:
                return specialOf(decls.name(cid)) == Specials.QUOT;
            default:
                return true;
        }
    }

    /** The unit whose official admission puts constant {@code cid} into the environment. */
    private int provider(int cid) {
        int kind = decls.kind(cid);
        if (kind >= DeclKind.QUOT_MK && kind <= DeclKind.QUOT_IND) {
            int quotName = specials.nameOf(Specials.QUOT);
            int quot = quotName == 0 ? 0 : names.constOf(quotName);
            if (quot > 0 && decls.kind(quot) == DeclKind.QUOT_TYPE) {
                return decls.unit(quot);
            }
        }
        return decls.unit(cid);
    }

    /**
     * The official replay adds a declaration after it has replayed the NAMES its type and value
     * mention, in an order that is otherwise a hash order. A mention whose record is not replayed (a
     * constructor or recursor flagged unsafe, {@code Quot.mk / lift / ind}) and a header-only mention
     * of a mutual block are therefore in the environment in time only if a forcing mention's unit is
     * their provider or (transitively, through declarations that were admitted and hence had
     * everything they mention) depends on it. Otherwise the official verdict depends on the hash
     * order: declined.
     *
     * <p>The prerequisites of literals are not tested: the official kernel types and converts a
     * literal by name without looking the constants up, so they are needed in the environment only
     * when a conversion's result is inferred or unfolded - which the judgments do not report. A unit
     * in file order is admitted on the same terms (README, "Known gaps").
     */
    private Reason presence(int x) {
        ReplayState rs = replay;
        int i = x - rs.first;
        int[] needs = rs.needs[i];
        int[] weak = rs.weak[i];
        int open = 0;
        int nf = 0;
        for (int e : needs) {
            if (e > 0) {
                if (forcing(e) && !isWeak(weak, e)) {
                    nf++;
                } else {
                    open++;
                }
            }
        }
        if (open == 0) {
            return null;
        }
        int[] forced = new int[nf];
        nf = 0;
        for (int e : needs) {
            if (e > 0 && forcing(e) && !isWeak(weak, e)) {
                forced[nf++] = provider(e);
            }
        }
        for (int e : needs) {
            if (e < 0 || (forcing(e) && !isWeak(weak, e))) {
                continue;
            }
            int target = provider(e);
            if (target == x) {
                continue;
            }
            boolean found = false;
            for (int k = 0; k < nf && !found; k++) {
                found = forced[k] == target;
            }
            if (!found && !reaches(forced, nf, target)) {
                // Nothing forces it: the official order of the names decides.
                if (order == null || order.actTime(x) == 0) {
                    return fail(Reason.UNSUPPORTED_INPUT, "the official verdict depends on the replay order: nothing this"
                            + " declaration mentions makes the official checker add '" + nameOf(e) + "' before it");
                }
                if (!order.providedBefore(e, x)) {
                    return fail(Reason.UNKNOWN_CONSTANT, "unknown constant '" + nameOf(e) + "' (the official replay, which"
                            + " goes by the hash order of the names, adds this declaration before the one that provides it)");
                }
            }
        }
        return null;
    }

    /**
     * Defence in depth for a unit about to be admitted: where the official order was simulated, it
     * must agree that everything the unit mentions is added before the unit. A disagreement means
     * that one of the two analyses is wrong about this file: declined.
     */
    private Reason crossCheck(int x) {
        if (order == null || order.actTime(x) == 0) {
            return null;
        }
        for (int e : replay.needs[x - replay.first]) {
            if (e > 0 && !order.providedBefore(e, x)) {
                return fail(Reason.UNSUPPORTED_INPUT, "the simulated official replay adds this declaration before '"
                        + nameOf(e) + "', which the dependency analysis takes to be there");
            }
        }
        return null;
    }

    private static boolean isWeak(int[] weak, int cid) {
        if (weak != null) {
            for (int w : weak) {
                if (w == cid) {
                    return true;
                }
            }
        }
        return false;
    }

    private static final int REACH_BUDGET = 4096;

    /** True if unit {@code target} is in the dependency closure of one of the {@code n} units of {@code from}. */
    private boolean reaches(int[] from, int n, int target) {
        java.util.HashSet<Integer> seenUnits = new java.util.HashSet<>();
        java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
        for (int k = 0; k < n; k++) {
            if (seenUnits.add(from[k])) {
                queue.add(from[k]);
            }
        }
        while (!queue.isEmpty()) {
            if (seenUnits.size() > REACH_BUDGET) {
                return false;
            }
            int q = queue.poll();
            for (int e : needsOf(q)) {
                if (e < 0) {
                    continue; // not something the official checker needed
                }
                int p = provider(e);
                if (p == target) {
                    return true;
                }
                if (seenUnits.add(p)) {
                    queue.add(p);
                }
            }
        }
        return false;
    }

    /** The mentions of a decided unit: recorded for units admitted in dependency order, collected on demand for earlier ones. */
    private int[] needsOf(int q) {
        ReplayState rs = replay;
        if (q >= rs.first && q < rs.total) {
            int[] needs = rs.needs[q - rs.first];
            return needs == null ? NO_NEEDS : needs;
        }
        if (earlyNeeds == null) {
            earlyNeeds = new HashMap<>();
        }
        int[] known = earlyNeeds.get(q);
        if (known != null) {
            return known;
        }
        Collector c = headerCollector;
        c.reset();
        int size = decls.unitSize(q);
        if (size > 0 && decls.status(q) != DeclKind.ST_SKIPPED) {
            int first = decls.unitFirstCid(q);
            int kind = decls.unitKind(q);
            try {
                if (kind == DeclKind.AXIOM || kind == DeclKind.DEFN || kind == DeclKind.THM || kind == DeclKind.OPAQUE) {
                    int lparams = decls.levelParams(first);
                    structural.collectRoot(decls.type(first), q, lparams, StructuralChecks.OWN_NONE, c);
                    int root = kind == DeclKind.AXIOM ? 0 : valueRoot(first);
                    if (root > 0) {
                        structural.collectRoot(root, q, lparams, StructuralChecks.OWN_NONE, c);
                    }
                } else if (kind == DeclKind.INDUCT) {
                    for (int cid = first; cid < first + size; cid++) {
                        int k = decls.kind(cid);
                        if (k == DeclKind.INDUCT || k == DeclKind.CTOR) {
                            structural.collectRoot(decls.type(cid), q, decls.levelParams(cid), StructuralChecks.OWN_ALL, c);
                        }
                    }
                } else if (specialOf(decls.name(first)) == Specials.QUOT) {
                    int eqName = specials.nameOf(Specials.EQ);
                    int eq = eqName == 0 ? 0 : names.constOf(eqName);
                    if (eq > 0) {
                        c.constant(eq);
                    }
                }
            } catch (RuntimeException e) {
                // an incomplete list only makes the presence test fail closed (decline)
            }
        }
        int[] needs = Arrays.copyOf(c.cids, c.n);
        earlyNeeds.put(q, needs);
        return needs;
    }

    // ---- replay: bound copies of roots for rlvm-inductive ---------------------------------------------------

    /**
     * {@code term} with every reference that the loader wrote before its constant was declared bound
     * to that constant, built in the session's overlay; {@code term} itself if it has none.
     * {@code rlvm-inductive} looks at constant ids (containers of nested occurrences, members of the
     * block), which an unbound node does not have. Called after {@link #collect} found every
     * reference of the root resolvable. Iterative.
     */
    private int rebind(int term) {
        if ((terms.header(term) & Node.F_UNRESOLVED) == 0) {
            return term;
        }
        Overlay ov = terms.overlay();
        HashMap<Integer, Integer> memo = new HashMap<>();
        int[] st = new int[64];
        int sp = 0;
        st[sp++] = term;
        while (sp > 0) {
            int n = st[--sp];
            boolean post = n < 0;
            if (post) {
                n = ~n;
            }
            if (memo.containsKey(n)) {
                continue;
            }
            int header = terms.header(n);
            if ((header & Node.F_UNRESOLVED) == 0) {
                memo.put(n, n);
                continue;
            }
            int k = Node.kind(header);
            if (sp + 4 > st.length) {
                st = Arrays.copyOf(st, st.length * 2);
            }
            int r;
            switch (k) {
                case Kind.CONST: {
                    int cid = terms.a(n);
                    if (cid <= 0) {
                        cid = cid == 0 ? 0 : names.constOf(~cid);
                    }
                    if (cid <= 0) {
                        throw new ResourceException(Reason.UNSUPPORTED_INPUT);
                    }
                    r = ov.mkConst(cid, terms.b(n));
                    break;
                }
                case Kind.PROJ:
                    if (!post) {
                        st[sp++] = ~n;
                        st[sp++] = terms.a(n);
                        continue;
                    } else {
                        int scid = terms.c(n);
                        if (scid <= 0) {
                            scid = scid == 0 ? 0 : names.constOf(~scid);
                        }
                        if (scid <= 0) {
                            throw new ResourceException(Reason.UNSUPPORTED_INPUT);
                        }
                        r = ov.mkProj(scid, terms.b(n), memo.get(terms.a(n)));
                    }
                    break;
                case Kind.APP:
                case Kind.LAM:
                case Kind.PI:
                    if (!post) {
                        st[sp++] = ~n;
                        st[sp++] = terms.b(n);
                        st[sp++] = terms.a(n);
                        continue;
                    }
                    r = k == Kind.APP ? ov.mkApp(memo.get(terms.a(n)), memo.get(terms.b(n)))
                            : k == Kind.LAM ? ov.mkLam(memo.get(terms.a(n)), memo.get(terms.b(n)))
                                    : ov.mkPi(memo.get(terms.a(n)), memo.get(terms.b(n)));
                    break;
                case Kind.LET:
                    if (!post) {
                        st[sp++] = ~n;
                        st[sp++] = terms.c(n);
                        st[sp++] = terms.b(n);
                        st[sp++] = terms.a(n);
                        continue;
                    }
                    r = ov.mkLet(memo.get(terms.a(n)), memo.get(terms.b(n)), memo.get(terms.c(n)));
                    break;
                default:
                    r = n; // a leaf that cannot be unresolved
                    break;
            }
            terms.refresh();
            memo.put(n, r);
        }
        return memo.get(term);
    }

    // ---- specials --------------------------------------------------------------------------------------

    /** Special index of a declared name, or -1. */
    private int specialOf(int name) {
        return name == 0 ? -1 : specials.indexOfName(name);
    }

    private void bind(int which, int cid) {
        int state = policy.preludeGuards ? guards.evaluate(which, cid) : Specials.VALID;
        specials.bind(which, cid, state);
    }

    // ---- bookkeeping -----------------------------------------------------------------------------------

    private Reason fail(Reason reason, String what) {
        detail = what;
        return reason;
    }

    private String nameOf(int cid) {
        return names.toString(decls.name(cid));
    }

    private String describe(int u, String what) {
        StringBuilder sb = new StringBuilder(96);
        sb.append("unit ").append(u);
        if (decls.unitSize(u) > 0) {
            sb.append(" '");
            names.appendTo(sb, decls.name(decls.unitFirstCid(u)));
            sb.append('\'');
        }
        if (what != null) {
            sb.append(": ").append(what);
        }
        return sb.toString();
    }

    private void setStatus(int u, int status) {
        if (!decls.casStatus(u, DeclKind.ST_LOADED, status) && !decls.casStatus(u, DeclKind.ST_ENV_READY, status)) {
            decls.casStatus(u, DeclKind.ST_RUNNING, status);
        }
    }

    /** Where the claims blob of an inductive unit comes from (a seam for tests). */
    interface ClaimsSource {
        int[] claims(int unit);
    }

    /** The blob the loader attached with {@code StoreBuilder.blockClaims}. */
    private static final class StoreClaims implements ClaimsSource {
        private final Store store;

        StoreClaims(Store store) {
            this.store = store;
        }

        @Override
        public int[] claims(int unit) {
            return StoreAccess.claims(store.decls(), unit);
        }
    }

    /** Receives the outcome of every unit the environment pass decides itself (inductive, quot). */
    public interface Listener {
        void unitAdmitted(int unit);

        void unitFailed(int unit, Reason reason, String message);

        /** {@code Store.envUnits()} moved to {@code units}. */
        void frontierAdvanced(int units);
    }
}
