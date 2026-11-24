package rlvm.machine;

import static rlvm.machine.Op.ABORT;
import static rlvm.machine.Op.FATAL;
import static rlvm.machine.Op.SIG;
import static rlvm.machine.Op.SUSP;

import java.util.Arrays;
import rlvm.api.MachineErrors;
import rlvm.api.RunSignals;
import rlvm.api.WorkerContext;
import rlvm.api.bind.NatBinding;
import rlvm.nat.NatStore;
import rlvm.store.DeclTable;
import rlvm.store.IntLists;
import rlvm.store.LitPool;
import rlvm.store.Specials;
import rlvm.store.Store;
import rlvm.store.Terms;

/**
 * The per-session machine state (ISA section 1) and the control primitives of the host convention
 * ({@code docs/design/review/stepper-convention.md}, rules H1-H12). No judgment logic.
 *
 * <p>Shared file, owner M6. One instance per session, confined to its thread. Everything is primitive
 * arrays allocated once and reused; nothing here allocates on a normal path (growth doubles an array).
 *
 * <h2>Stores (ISA 1.1, 1.6)</h2>
 * <pre>
 * vh / vp            value records, 8 words each; record r at vh[8r]; vp = next record index (first = 2)
 * vtab               intern table over record indices (Layout: tag:4 | index:28; linear probing)
 * rs / rsp           run slab; run id = offset of its header; rsp = next free word (first = 1)
 * rtab               intern table over FRAME and REST runs
 * es / ep            environment slab (LIFO, not interned);  envBase = second result of Ops.push / Ops.enter
 * pair / plog        pair table (4 ints per entry) and the log of inserted slots
 * ic, lsub, lsubList, limp, sigt, fv0, undec     direct-mapped caches, each cell stamped (Layout)
 * W / wp             worklist: the SUSPENDED frames (H1);  S / sp: side buffer of an unwind (H6)
 * SC / ssp           scope stack: the open binders' FVAR refs
 * X / xp             scratch (argument buffers, spine collection), LIFO, owned by the judgment that pushed
 * RS / rp            register stack of code activations (H7)
 * </pre>
 * ISA 1.6 calls the run-slab pointer {@code rp}; here it is {@code rsp}, because the convention gives
 * {@code rp} to the register stack.
 *
 * <h2>Registers (ISA 1.2, as changed by H7)</h2>
 * The result register A is the Java return value of a step; {@code tryp} does not exist. {@code fuel}
 * = ticks left in the innermost budget; {@code ticks() = tickBase - fuel}; {@code ER}; {@code decl},
 * {@code gen}, {@code epoch}; {@code err} = code of the failure that is propagating; {@code sig} = the
 * signal the tick slow path wants returned; {@code A2}, {@code A3} = extra results of LAZYD.
 *
 * <h2>The step protocol (normative; see WORK-PACKAGES.md for the full template)</h2>
 * <pre>
 * static int J(Machine m, int x, int d) {
 *     // 1. inline answers (identity, flag, memo, cache): return at once. No tick, no event, no frame.
 *     if ((--m.fuel &amp; Op.POLL_MASK) == Op.POLL_MASK &amp;&amp; m.tickSlow()) return m.sig;       // 2. TICK
 *     if (m.stats) m.call(Op.J);                                                        // 2b. call event
 *     if (d &gt; m.hostDepth) return m.spill1(Op.K_J, x);                                  // 3. too deep
 *     int r = K(m, y, d + 1);                                                           // 4. call K(y) =&gt; 1
 *     return r &gt;= Op.SIG ? m.sig1(r, K_J_1, x) : k_J1(m, x, r, d);
 * }
 * </pre>
 */
public final class Machine {

    /**
     * Counters of a package's session state: an {@code Ext} class ({@code m.x1 .. m.x7}) that implements
     * this has its counters summed over the sessions of an engine and written to the statistics as
     * {@code machine.<name>} (e.g. name {@code "match.tried"}), without a change of M6's files. The two
     * arrays have the same length and the same order in every session; they are read once, when the session
     * is closed (no cost on a checking path).
     */
    public interface Counters {
        String[] counterNames();

        long[] counterValues();
    }

    /** Resumes the frames of family EXT (toy judgments of tests, experiments). */
    public interface Resumer {
        int resume(Machine m, int key, int[] w, int p, int r);
    }

    /** log2 capacities of the session arrays (ISA 1.6, "Cap and sizes"). */
    public static final class Sizes {
        public int records = 18;
        public int vtab = 19;
        public int runWords = 20;
        public int rtab = 18;
        public int pairs = 17;
        public int plog = 16;
        public int env = 17;
        public int worklist = 16;
        public int side = 12;
        public int scope = 10;
        public int scratch = 16;
        public int regs = 12;
        /**
         * The record slab and the run slab keep the capacity they grew to across a reset, up to these sizes
         * (log2 records / run words; never below the base). The slabs are filled by a bump pointer, so a larger
         * slab costs no locality - only the tables do - and a reset that shrank them made every larger
         * declaration copy them again (growRecords + growRuns: 2-4% of the worker's samples on Init and the
         * Cedar slice with small bases). 0 = return to the base size (tests of the growth paths).
         */
        public int keepRecords = 0;
        public int keepRunWords = 0;
        /**
         * The LIFO working arrays (environment slab, worklist, side buffer, scope stack, scratch, registers)
         * keep the capacity they grew to across a reset, up to 2^keepStack words each (never below the base);
         * 0 = return to the base size. A stack is only touched up to its high-water mark, so a larger one
         * costs no locality, and returning it to the base made the next deep declaration allocate and fault
         * it in again.
         */
        public int keepStack = 0;
        /**
         * Session arrays are pooled per machine (NOTES-M6 F25): a slab that outgrows its kept size leaves the
         * kept array behind for the next reset instead of making it garbage, and pair tables that grew are
         * pooled by size like the intern tables. False = every such array is allocated anew (the A/B of F25,
         * {@code -Drlvm.machine.pool=off}).
         */
        public boolean pool = true;

        /**
         * The base sizes of a session. ISA 1.6 sized them for the 16 MiB cap (2^18 records, 2^19 / 2^18 table
         * slots, 2^20 run words, 2^17 pairs: about 20 MiB per worker, the field initialisers above). Measured
         * on Init and the Cedar slice (integration log, 2026-11-24), those tables cost CPU time: a declaration
         * makes about 900 records, and every probe of a 1-2 MiB table that holds them is a cache miss. The
         * arrays therefore start small - a small declaration's tables stay in the first-level cache - and grow
         * by doubling; {@link Machine#reset()} returns them to the base. Default 2^14 records, 2^15 / 2^14 table
         * slots, 2^16 run words, 2^12 pairs, 2^10 plog (the tables are 256 KiB together). Sweep with
         * {@code -Drlvm.machine.sizes=records,vtab,runWords,rtab,pairs,plog} (log2 each; {@code isa} = the
         * ISA 1.6 sizes).
         */
        public static Sizes standard() {
            Sizes s = new Sizes();
            int[] o = BASE;
            if (o != null) {
                s.records = o[0];
                s.vtab = o[1];
                s.runWords = o[2];
                s.rtab = o[3];
                s.pairs = o[4];
                s.plog = o[5];
            }
            int[] k = KEEP;
            s.keepRecords = Math.max(s.records, k[0]);   // default: the ISA 1.6 capacities, 8 MiB + 4 MiB per worker at most
            s.keepRunWords = Math.max(s.runWords, k[1]);
            s.keepStack = k[2];
            s.pool = POOL;
            return s;
        }

        /**
         * Kept capacities in force (log2): records, run words, stack words. {@code -Drlvm.machine.keep=R,W,S};
         * default 18, 20, 20 (8 MiB of records, 4 MiB of run words, 4 MiB per working array at most).
         */
        private static final int[] KEEP = keepSizes(System.getProperty("rlvm.machine.keep"));
        private static final boolean POOL = !"off".equals(System.getProperty("rlvm.machine.pool"));

        static int[] keepSizes(String p) {
            int[] dflt = {18, 20, 20};
            if (p == null || p.isEmpty()) {
                return dflt;
            }
            String[] f = p.split(",");
            if (f.length != dflt.length) {
                throw new IllegalArgumentException("rlvm.machine.keep: three numbers expected, got '" + p + "'");
            }
            int[] o = new int[f.length];
            for (int i = 0; i < f.length; i++) {
                o[i] = Integer.parseInt(f[i].trim());
                if (o[i] < 0 || o[i] > 27) {
                    throw new IllegalArgumentException("rlvm.machine.keep: log2 sizes are 0..27, got '" + p + "'");
                }
            }
            return o;
        }

        /** Base sizes in force (log2): records, vtab, run words, rtab, pairs, plog; null = the ISA 1.6 sizes. */
        private static final int[] BASE = baseSizes(System.getProperty("rlvm.machine.sizes"));

        static int[] baseSizes(String p) {
            int[] dflt = {14, 15, 16, 14, 12, 10};
            if (p == null || p.isEmpty()) {
                return dflt;
            }
            if (p.equals("isa")) {
                return null;
            }
            String[] f = p.split(",");
            if (f.length != dflt.length) {
                throw new IllegalArgumentException("rlvm.machine.sizes: six numbers expected, got '" + p + "'");
            }
            int[] o = new int[f.length];
            for (int i = 0; i < f.length; i++) {
                o[i] = Integer.parseInt(f[i].trim());
                if (o[i] < 2 || o[i] > 24) {
                    throw new IllegalArgumentException("rlvm.machine.sizes: log2 sizes are 2..24, got '" + p + "'");
                }
            }
            return o;
        }

        /** Very small arrays, so that tests exercise every growth path. */
        public static Sizes tiny() {
            Sizes s = new Sizes();
            s.records = 3;
            s.vtab = 4;
            s.runWords = 4;
            s.rtab = 3;
            s.pairs = 3;
            s.plog = 2;
            s.env = 2;
            s.worklist = 3;
            s.side = 3;
            s.scope = 1;
            s.scratch = 2;
            s.regs = 2;
            return s;
        }
    }

    // ---------------------------------------------------------------------------------------------------
    // Context (set by attach; null in a bare machine used by control tests)
    // ---------------------------------------------------------------------------------------------------

    WorkerContext ctx;
    Store store;
    Terms terms;
    DeclTable decls;
    Specials specials;
    rlvm.api.LevelOps levels;
    IntLists lists;
    LitPool lits;
    /**
     * The machine's number overlay (ISA 2.9): big naturals (2^63 and above) made in this session, interned
     * by content; NBIG records hold its ids. It is an overlay of the machine's own on the run's base store
     * ({@link #ownNats}), emptied by {@link #reset()} and counted in the footprint - not the cache store of
     * the context's {@code NatBinding}, which that binding empties on its own schedule (its ids would be
     * reissued under live records). Null if the context has no {@code NatBinding}; the binding's store
     * itself if that store has no frozen base to overlay (then {@code ownNats} is false and the session
     * must not outlive a use of {@code ctx.nats}: M1's records detect a reissued id and decline).
     */
    NatStore nats;
    boolean ownNats;
    /**
     * The machine keeps its big naturals in an overlay of its own (request of M1, 2026-11-24). A handle of
     * {@code m.nats} means nothing to the context's {@code NatBinding}: who needs a term for it moves it
     * with {@code Nat.transfer(m.nats, handle, binding.store())} first (the test tree's {@code Quote} does).
     */
    static final boolean OWN_NAT_OVERLAY = true;
    RunSignals signals = RunSignals.NONE;
    /** The engine this session belongs to (engine-wide FactStore / CodeStore), or null. */
    MachineEngine engine;

    // ---------------------------------------------------------------------------------------------------
    // Session stores
    // ---------------------------------------------------------------------------------------------------

    final Sizes sizes;
    int[] vh;
    int vp = Layout.FIRST_RECORD;
    /**
     * First record index of the current session: {@code Layout.FIRST_RECORD}, except under
     * {@code Op.DBG_POISON}, where every reset moves it past the records of the session that ended (which
     * are zeroed: kind 0), so that a record ref kept across a reset denotes no value (ISA 5.5 "poison").
     * The records in use are {@code [vbase, vp)}.
     */
    int vbase = Layout.FIRST_RECORD;
    int[] vtab;
    int vtabMask;
    int[] rs;
    int rsp = Layout.FIRST_RUN;
    /** First run word of the current session ({@code Layout.FIRST_RUN}; moved by a reset under {@code Op.DBG_POISON}). */
    int rbase = Layout.FIRST_RUN;
    int[] rtab;
    int rtabMask;
    int rtabUsed;
    int[] es;
    int ep;
    /** Second result of {@code Ops.push} / {@code Ops.enter}: the base of the environment they produced. */
    int envBase;
    int[] pair;
    int pairMask;
    int pairUsed;
    int[] plog;
    int plogN;
    final int[] ic = new int[Layout.CELL_WORDS << Layout.IC_LOG2];
    final int[] lsub = new int[Layout.CELL_WORDS << Layout.LSUB_LOG2];
    final int[] lsubList = new int[Layout.CELL_WORDS << Layout.LSUB_LOG2];
    final int[] limp = new int[Layout.CELL_WORDS << Layout.LIMP_LOG2];
    final int[] sigt = new int[Layout.CELL_WORDS << Layout.SIGT_LOG2];
    final int[] fv0 = new int[Layout.CELL_WORDS << Layout.FV0_LOG2];
    final int[] undec = new int[Layout.CELL_WORDS << Layout.UNDEC_LOG2];

    // ---------------------------------------------------------------------------------------------------
    // Control state
    // ---------------------------------------------------------------------------------------------------

    /** The suspended frames (H1). Only {@link #drive}, {@code sigN}, {@code spillN} touch W, wp, S, sp (H7). */
    int[] W;
    int wp;
    /** Side buffer of an unwind in progress: frames at S[sp .. S.length), innermost on top (H6). */
    int[] S;
    int sp;
    int[] SC;
    int ssp;
    int[] X;
    int xp;
    int[] RS;
    int rp;

    /** Ticks left in the innermost budget (ISA 1.4). Decremented by the TICK line of every entry step. */
    long fuel = Op.FUEL_UNLIMITED;
    /** {@code ticks() == tickBase - max(fuel, 0)} at all times; {@link #tryEnter} and {@code Frames.k_try} keep it so. */
    long tickBase = Op.FUEL_UNLIMITED;
    /**
     * 1 while the argument of an {@code eagerReduce _ _} application is compared with its domain (R02 8.9).
     * Set through {@link #enterER()} (never by a plain assignment of 1), restored by assigning the saved value.
     */
    int ER;
    /**
     * True once the current declaration entered eager reduction ({@link #enterER()}).
     *
     * <p>Why it matters (NOTES-M6 F26; findings M6-F26, S-F4, S-F5 of 2026-11-24; arena test
     * bad/bugs/rec-of-subst-prop). Eager reduction is the one MODE of the official {@code is_def_eq}: it lifts
     * the hasFVar guards of the Bool.true test and of the Nat folding, so one pair of terms can be T in plain
     * mode and F under ER, and the reverse. The official kernel answers a pair from its success cache
     * whatever the mode, but that cache lives for one declaration and is keyed by terms whose free variables
     * are unique per binder. The machine's pair table and memo words are keyed by values with CANONICAL
     * variables and (in warm sessions) outlive the declaration: a POS written in plain mode in one binder
     * scope or declaration was read under ER in another one, where the official kernel computes F. No cheap
     * rule reproduces the official cache exactly, so the machine does not check such a declaration at all:
     * under {@link #erDeclines} it is DECLINED (the reference engine decides it), and nothing it derived is
     * kept.
     */
    boolean erSeen;
    /**
     * The facade's rule is in force (set by {@code MachineSession} around the DECL driver; false in a bare
     * machine, so that the tests of the judgments can still run a comparison under ER): entering eager
     * reduction ends the declaration with {@code fault(E_LIMIT)} at the next tick, and the facade declines
     * the unit whatever the driver returned.
     */
    boolean erDeclines;
    /** {@code limitDetail} of a declaration that was declined because it entered eager reduction. */
    static final String ER_DETAIL = "eager reduction (an argument 'eagerReduce _ _'): conversion under it depends on the mode"
            + " and on the official per-declaration caches, which the machine's tables do not reproduce; the reference engine checks this declaration";
    /** Code ({@code rlvm.api.MachineErrors}) of the failure whose signal is propagating; 0 = none. */
    int err;
    /**
     * The site of the failure, for {@code Session.lastMessage()} only: the two values of a mismatch (the
     * inferred and the expected type, the two sides of a comparison), or one value and 0; set by
     * {@link #fail(int, int, int)}. Never read by a judgment.
     */
    int errA;
    int errB;
    /** The signal {@link #tickSlow} wants the step to return (ABORT or FATAL). */
    int sig;
    /**
     * Which limit raised {@code E_MEM} / {@code E_STACK} (a constant string, for {@code lastMessage()} and
     * the statistics): set by the growth methods and the poll when they refuse; null = not recorded.
     */
    String limitDetail;
    /** Extra results of a judgment with more than one result (LAZYD: the final pair tn, sn; H7). */
    int A2;
    int A3;

    /** Rank of the declaration being checked (visibility bound). */
    int decl;
    /** Unit index of the declaration being checked (for the first-failure poll); -1 = never cancelled. */
    int unit = -1;
    /** Check generation (ISA 5.3); 0 is "infer-only", so it starts at 1. */
    int gen = 1;
    /** Session number; stamps the direct-mapped caches. Starts at 1 so that a zeroed cell is empty. */
    int epoch = 1;
    /** Stamp of the UNDEC cells (declaration scope): incremented by {@link #beginDecl}. */
    int declStamp = 1;
    /** Footprint at which the poll raises E_MEM (declaration start + Budget.maxBytes). */
    long byteLimit = Long.MAX_VALUE;

    /** True from a {@link #reset()} (or the creation) until the next {@link #beginDecl}: the session holds nothing. */
    boolean fresh = true;

    /** The host depth bound D in force (H9). 0: every non-leaf call becomes a frame (the worklist machine). */
    int hostDepth = Op.HOST_DEPTH;
    /** Count call events and frames (EngineConfig.stats, tests). */
    boolean stats;
    /** Staging switches in force ({@code Op.SW_*}); 0 = v0. */
    int sw;
    /** Debug modes in force ({@code Op.DBG_*}). */
    int debug;
    /** Strict mode (ISA 14.2): no switch may be on. */
    boolean strict;
    /** Resumer of family EXT frames, or null. */
    Resumer ext;

    // statistics (only written when stats is set, except the cheap counters of cold paths)
    final long[] calls = new long[Op.OP_COUNT];
    /**
     * Inline answers by op (ISA 14.5 "memo and pair-table hit rates"): a judgment whose fast path answered
     * from a memo word, the pair table or a cache may count it with {@code if (m.stats) m.hits[Op.J]++;}.
     * The hit rate of J is {@code hits[J] / (hits[J] + calls[J])}. Identity and flag answers are not hits.
     */
    final long[] hits = new long[Op.OP_COUNT];
    /**
     * Outcomes of speculative segments by shortcut (ISA 6.3 rows B1..B12, 14.5): index
     * {@code 3 * shortcut + outcome} with outcome F = 0, T = 1, U = 2; written through {@link #probe}.
     */
    final long[] probes = new long[3 * Op.SHORTCUT_COUNT];
    /** Blocks elaborated in this session, by role ({@code Op.ROLE_*}); written by the elaborator under {@code stats}. */
    final long[] elabs = new long[Op.ROLE_COUNT];
    /** Fact records derived by this session, and how many of them were {@code F_NONE} (ISA 3.5, 14.5). */
    long factsDerived;
    long factsNone;
    /** Records and run words made in sessions that were reset (the live session's are {@code vp - vbase}, {@code rsp - rbase}). */
    long recordsMade;
    long runWordsMade;
    long pairsMade;
    /** Audits run and memo hits re-computed by the debug modes (ISA 5.5). */
    long audits;
    long memoChecks;
    long memoInconclusive;
    /** Memo checks whose two values differ as constructions (beyond PEEK) and were found convertible by CONV. */
    long memoByConv;
    long framesWritten;
    long frameWords;
    long spills;
    long flushes;
    long cuts;
    long polls;
    long resets;
    long bytesAtReset;
    int maxWp;
    /** Frame trace (DBG_TRACE): the ops of the call events, in order. */
    int[] trace = new int[0];
    int traceN;
    /** Nesting of debug re-computations ({@code Debug.memoCheck}): inside one, no audit starts another. */
    int dbgNest;
    /** Detail of the failed audit that raised {@code E_AUDIT} (built on the failing path only), or null. */
    String auditMessage;
    /** Session state of the debug modes (allocated on first use by {@code Debug.state}; never touched on a normal path). */
    Debug.State dbg;

    // per-package session state (owned by the package that owns the class; see WORK-PACKAGES.md)
    final Values.Ext x1 = new Values.Ext();
    final Eval.Ext x2 = new Eval.Ext();
    final Reduce.Ext x3 = new Reduce.Ext();
    final Conv.Ext x4 = new Conv.Ext();
    final Infer.Ext x5 = new Infer.Ext();
    final Code.Ext x7 = new Code.Ext();

    public Machine() {
        this(Sizes.standard());
    }

    public Machine(Sizes sizes) {
        this.sizes = sizes;
        vh = new int[Layout.REC_WORDS << sizes.records];
        vtab = new int[1 << sizes.vtab];
        vtabMask = vtab.length - 1;
        rs = new int[1 << sizes.runWords];
        rtab = new int[1 << sizes.rtab];
        rtabMask = rtab.length - 1;
        es = new int[1 << sizes.env];
        pair = new int[Layout.PAIR_WORDS << sizes.pairs];
        pairMask = (1 << sizes.pairs) - 1;
        plog = new int[1 << sizes.plog];
        W = new int[1 << sizes.worklist];
        S = new int[1 << sizes.side];
        sp = S.length;
        SC = new int[1 << sizes.scope];
        X = new int[1 << sizes.scratch];
        RS = new int[1 << sizes.regs];
    }

    /** Binds the machine to a worker context and an engine (either may be null in tests of the control code). */
    void attach(WorkerContext ctx, MachineEngine engine) {
        this.ctx = ctx;
        this.engine = engine;
        if (ctx != null) {
            store = ctx.store;
            terms = ctx.terms;
            decls = ctx.store == null ? null : ctx.store.decls();
            specials = ctx.store == null ? null : ctx.store.specials();
            levels = ctx.levels;
            lists = ctx.lists;
            lits = ctx.lits;
            nats = null;
            ownNats = false;
            if (ctx.nats instanceof NatBinding) {
                NatStore cache = ((NatBinding) ctx.nats).store();
                NatStore base = cache.base();
                if (OWN_NAT_OVERLAY && base != null && base.isFrozen()) {
                    nats = base.newOverlay(cache.maxBytes());
                    ownNats = true;
                } else {
                    nats = cache;
                }
            }
            signals = ctx.signals == null ? RunSignals.NONE : ctx.signals;
        }
    }

    /** True if staging switch {@code s} ({@code Op.SW_*}) is on. */
    boolean on(int s) {
        return (sw & s) != 0;
    }

    // ---------------------------------------------------------------------------------------------------
    // Records (ISA 2.2, 2.5)
    // ---------------------------------------------------------------------------------------------------

    /**
     * Appends the record {@code (w0, a, b, c)} with empty memo words and enters it into vtab at
     * {@code slot}, which the caller found empty at the end of the probe sequence of {@code hash}.
     *
     * @return the record ref, or FATAL ({@code E_MEM}: 2^28 records)
     */
    int addRecordAt(int slot, int hash, int w0, int a, int b, int c) {
        int r = vp;
        int o = r << Layout.REC_SHIFT;
        if (o == vh.length && !growRecords()) {
            return fault(MachineErrors.E_MEM);
        }
        int[] v = vh;
        v[o] = w0;
        v[o + Layout.A] = a;
        v[o + Layout.B] = b;
        v[o + Layout.C] = c;
        v[o + Layout.M0] = 0;
        v[o + Layout.M1] = 0;
        v[o + Layout.M2] = 0;
        v[o + Layout.M3] = 0;
        vp = r + 1;
        vtab[slot] = Layout.tabWord(hash, r);
        if ((vp - vbase << 1) > vtab.length && !growVtab()) {
            return fault(MachineErrors.E_MEM); // the record exists; the session ends with this declaration
        }
        return r;
    }

    /**
     * The reference probe: the record with key {@code (kind and aux of w0, a, b, c)}, made if absent
     * with the flags of {@code w0}. Not for NEU (keyed by its spine). {@code Values} may inline its own
     * copy of this loop; the result must be the same record.
     */
    int internRecord(int w0, int a, int b, int c) {
        int hash = Hash.record(w0, a, b, c);
        int[] tab = vtab;
        int[] v = vh;
        int mask = vtabMask;
        int slot = hash & mask;
        int key0 = w0 & Hash.W0_KEY_MASK;
        for (;;) {
            int e = tab[slot];
            if (e == 0) {
                return addRecordAt(slot, hash, w0, a, b, c);
            }
            if (((e ^ hash) >>> Layout.TAB_TAG_SHIFT) == 0) {
                int o = (e & Layout.TAB_INDEX_MASK) << Layout.REC_SHIFT;
                if ((v[o] & Hash.W0_KEY_MASK) == key0 && v[o + Layout.A] == a && v[o + Layout.B] == b
                        && v[o + Layout.C] == c) {
                    return e & Layout.TAB_INDEX_MASK;
                }
            }
            slot = (slot + 1) & mask;
        }
    }

    private boolean growRecords() {
        // 8 words per record in one int[]: 2^27 records are 2^30 words, the last doubling an array allows
        // (ISA section 9 says 2^28 records; Layout.RECORD_MAX is the bound of the ref encoding, not of the array)
        if (vp >= Layout.RECORD_MAX || vh.length >= 1 << 30) {
            limitDetail = "value records: 2^27 per session (ISA section 9; int[] capacity)";
            return false;
        }
        int[] old = vh;
        int[] grown = copyOrNull(old, old.length << 1);
        if (grown == null) {
            return false;
        }
        vh = grown;
        allocGrows++;
        allocGrowBytes += 8L * old.length;
        if (sizes.pool && old.length == keptRecordWords()) {
            vhKept = old; // the next reset takes it back: no regrowth from the base after a large declaration
        }
        return true;
    }

    /** Words of the record slab a reset keeps (the larger of the base and the kept size). */
    private int keptRecordWords() {
        return Layout.REC_WORDS << Math.max(sizes.records, sizes.keepRecords);
    }

    /** Words of the run slab a reset keeps. */
    private int keptRunWords() {
        return 1 << Math.max(sizes.runWords, sizes.keepRunWords);
    }

    /** Words of a working array of base size 2^base that a reset keeps. */
    private int keptStackWords(int base) {
        return 1 << Math.max(base, sizes.keepStack);
    }

    /** A slab that outgrew its kept size left this array behind ({@code Sizes.pool}); null = none. */
    private int[] vhKept;
    private int[] rsKept;
    /** Arrays allocated by growth (doublings) and their bytes; arrays allocated by {@link #reset()} and their bytes. Cold paths only. */
    long allocGrows;
    long allocGrowBytes;
    long allocResets;
    long allocResetBytes;

    private boolean growVtab() {
        if (!heapAllows(8L * vtab.length)) {
            return false;
        }
        int[] tab = takeTable(vpool, Integer.numberOfTrailingZeros(vtab.length) + 1);
        int mask = tab.length - 1;
        int[] v = vh;
        for (int r = vbase; r < vp; r++) {
            int h = Hash.ofRecord(v, r);
            int slot = h & mask;
            while (tab[slot] != 0) {
                slot = (slot + 1) & mask;
            }
            tab[slot] = Layout.tabWord(h, r);
        }
        int[] old = vtab;
        vtab = tab;
        vtabMask = mask;
        giveTable(vpool, old);
        return true;
    }

    // ---------------------------------------------------------------------------------------------------
    // Retired intern tables (NOTES-INT I5)
    // ---------------------------------------------------------------------------------------------------

    /** Tables of up to 2^TAB_POOL_MAX slots (4 MiB) are kept for reuse; larger ones go back to the heap. */
    private static final int TAB_POOL_MAX = 20;

    /**
     * Retired intern tables by log2 size, EVERY ONE ALL ZERO: a declaration that outgrows a table takes the
     * next size from here, and {@link #reset()} puts the grown table back and takes the base one. Without
     * this every larger declaration allocated its tables anew (kernel time for the fresh pages was 12% of
     * the process on the Cedar slice and 15% on reachability; integration log 2026-11-24).
     * Invariant: an array is zeroed before it enters a pool (a stale entry could name a record beyond the
     * bump pointer); {@link #tablesClean()} checks it in tests.
     */
    private final int[][] vpool = new int[TAB_POOL_MAX + 1][];
    private final int[][] rpool = new int[TAB_POOL_MAX + 1][];

    /** A table of 2^k slots, all zero: the pooled one, or a new array. */
    private static int[] takeTable(int[][] pool, int k) {
        if (k <= TAB_POOL_MAX) {
            int[] t = pool[k];
            if (t != null) {
                pool[k] = null;
                return t;
            }
        }
        return newTable(1 << k);
    }

    /**
     * A new all-zero table of {@code n} words. An allocation that the heap cannot serve is a refused growth
     * ({@link HeapLimit}: the facade declines the unit with E_MEM), never an {@code OutOfMemoryError} that
     * escapes (F27).
     */
    private static int[] newTable(int n) {
        try {
            return new int[n];
        } catch (OutOfMemoryError e) {
            throw new HeapLimit("the Java heap: a table of " + 4L * n + " bytes could not be allocated");
        }
    }

    /** Zeroes {@code t} and keeps it for reuse (unless it is too large or its size is already kept). */
    private static void giveTable(int[][] pool, int[] t) {
        int k = Integer.numberOfTrailingZeros(t.length);
        if (k <= TAB_POOL_MAX && pool[k] == null) {
            Arrays.fill(t, 0);
            pool[k] = t;
        }
    }

    /** Test support, O(capacity): the session is pristine and both intern tables and every pooled table are all zero. */
    boolean tablesClean() {
        if (!pristine() || !allZero(vtab) || !allZero(rtab)) {
            return false;
        }
        for (int k = 0; k <= TAB_POOL_MAX; k++) {
            if ((vpool[k] != null && !allZero(vpool[k])) || (rpool[k] != null && !allZero(rpool[k]))) {
                return false;
            }
        }
        if (!allZero(pair)) {
            return false;
        }
        for (int[] t : ppool) {
            if (t != null && !allZero(t)) {
                return false;
            }
        }
        return true;
    }

    private static boolean allZero(int[] a) {
        for (int x : a) {
            if (x != 0) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------------------------------------------
    // Runs (ISA 1.6, 2.4)
    // ---------------------------------------------------------------------------------------------------

    /**
     * Appends a run header and reserves its slots; the caller writes the slots at
     * {@code rs[run + Layout.runHeaderWords(hdr) ..]} (re-read {@code m.rs}: the slab may have grown).
     *
     * @param rest the REST run of a far frame (written only if the header has the far bit)
     * @return the run id, or FATAL ({@code E_MEM}: 2^28 run words)
     */
    int addRun(int hdr, int hash, int rest) {
        int h = Layout.runHeaderWords(hdr);
        int need = h + Layout.runLen(hdr);
        int r = rsp;
        if (r + need > rs.length && !growRuns(r + need)) {
            return fault(MachineErrors.E_MEM);
        }
        int[] a = rs;
        a[r] = hdr;
        a[r + Layout.RUN_HASH] = hash;
        if (h > Layout.RUN_H) {
            a[r + Layout.RUN_REST_WORD] = rest;
        }
        rsp = r + need;
        return r;
    }

    /** Enters run {@code run} (FRAME or REST) into rtab at the empty {@code slot} its probe sequence ended in. */
    void putRunAt(int slot, int hash, int run) {
        rtab[slot] = Layout.tabWord(hash, run);
        if ((++rtabUsed << 1) > rtab.length) {
            growRtab();
        }
    }

    private boolean growRuns(int need) {
        if (need > Layout.RUN_WORDS_MAX) {
            limitDetail = "run words: 2^28 per session (ISA section 9)";
            return false;
        }
        int n = rs.length;
        while (n < need) {
            n <<= 1;
        }
        int[] old = rs;
        int[] grown = copyOrNull(old, n);
        if (grown == null) {
            return false;
        }
        rs = grown;
        allocGrows++;
        allocGrowBytes += 4L * n;
        if (sizes.pool && old.length == keptRunWords()) {
            rsKept = old;
        }
        return true;
    }

    private void growRtab() {
        if (!heapAllows(8L * rtab.length)) {
            throw new HeapLimit(limitDetail);
        }
        int[] tab = takeTable(rpool, Integer.numberOfTrailingZeros(rtab.length) + 1);
        int mask = tab.length - 1;
        int[] a = rs;
        for (int r = rbase; r < rsp;) {
            int hdr = a[r];
            if (Layout.runKind(hdr) != Layout.RUN_SPINE) {
                int h = a[r + Layout.RUN_HASH];
                int slot = h & mask;
                while (tab[slot] != 0) {
                    slot = (slot + 1) & mask;
                }
                tab[slot] = Layout.tabWord(h, r);
            }
            r += Layout.runHeaderWords(hdr) + Layout.runLen(hdr);
        }
        int[] old = rtab;
        rtab = tab;
        rtabMask = mask;
        giveTable(rpool, old);
    }

    // ---------------------------------------------------------------------------------------------------
    // Pairs (ISA 1.5, 1.6)
    // ---------------------------------------------------------------------------------------------------

    /**
     * Writes a new entry {@code (lo, hi, status)} at the empty entry index {@code slot} (the end of the
     * probe sequence of {@code Hash.pair(lo, hi)}) and logs it for the reset. {@code lo}, {@code hi} are
     * ordered as unsigned numbers; {@code lo != 0}.
     */
    void addPairAt(int slot, int lo, int hi, int status) {
        int o = slot << Layout.PAIR_SHIFT;
        pair[o + Layout.PAIR_LO] = lo;
        pair[o + Layout.PAIR_HI] = hi;
        pair[o + Layout.PAIR_STATUS] = status;
        if (plogN == plog.length) {
            int[] grown = copyOrNull(plog, plogN << 1);
            if (grown == null) {
                throw new HeapLimit(limitDetail);
            }
            plog = grown;
            allocGrows++;
            allocGrowBytes += 8L * plogN;
        }
        plog[plogN++] = slot;
        if ((++pairUsed << 1) > pairMask + 1) {
            growPairs();
        }
    }

    private void growPairs() {
        int entries = (pairMask + 1) << 1;
        if (entries > 1 << 28) {
            limitDetail = "pair table: 2^27 entries per session";
            throw new HeapLimit(limitDetail);
        }
        if (!heapAllows(4L * (entries << Layout.PAIR_SHIFT))) {
            throw new HeapLimit(limitDetail);
        }
        int[] tab = takePairs(Integer.numberOfTrailingZeros(entries), false);
        int mask = entries - 1;
        int[] old = pair;
        for (int i = 0; i < plogN; i++) {
            int o = plog[i] << Layout.PAIR_SHIFT;
            int lo = old[o + Layout.PAIR_LO];
            int hi = old[o + Layout.PAIR_HI];
            int slot = Hash.pair(lo, hi) & mask;
            while (tab[slot << Layout.PAIR_SHIFT] != 0) {
                slot = (slot + 1) & mask;
            }
            int n = slot << Layout.PAIR_SHIFT;
            tab[n + Layout.PAIR_LO] = lo;
            tab[n + Layout.PAIR_HI] = hi;
            tab[n + Layout.PAIR_STATUS] = old[o + Layout.PAIR_STATUS];
            old[o + Layout.PAIR_LO] = 0; // the old table is all zero when the loop ends: it can be pooled as it is
            old[o + Layout.PAIR_HI] = 0;
            old[o + Layout.PAIR_STATUS] = 0;
            plog[i] = slot;
        }
        poolPairs(old);
        pair = tab;
        pairMask = mask;
    }

    /**
     * Retired pair tables by log2 of their entry count, EVERY ONE ALL ZERO (as {@link #vpool}; NOTES-M6 F25):
     * before, every declaration that outgrew the base pair table allocated each larger table anew and the
     * reset allocated a new base table.
     */
    private final int[][] ppool = new int[TAB_POOL_MAX - Layout.PAIR_SHIFT + 1][];

    /** A pair table of 2^k entries, all zero: the pooled one, or a new array (counted as a growth or as a reset allocation). */
    private int[] takePairs(int k, boolean atReset) {
        if (sizes.pool && k < ppool.length) {
            int[] t = ppool[k];
            if (t != null) {
                ppool[k] = null;
                return t;
            }
        }
        if (atReset) {
            allocResets++;
            allocResetBytes += 4L << (k + Layout.PAIR_SHIFT);
        } else {
            allocGrows++;
            allocGrowBytes += 4L << (k + Layout.PAIR_SHIFT);
        }
        return newTable(1 << (k + Layout.PAIR_SHIFT));
    }

    /** Keeps the ALL-ZERO pair table {@code t} for reuse (unless it is too large or its size is already kept). */
    private void poolPairs(int[] t) {
        if (!sizes.pool) {
            return;
        }
        int k = Integer.numberOfTrailingZeros(t.length) - Layout.PAIR_SHIFT;
        if (k < ppool.length && ppool[k] == null) {
            ppool[k] = t;
        }
    }

    // ---------------------------------------------------------------------------------------------------
    // Stacks: environments, scratch, registers, scope
    // ---------------------------------------------------------------------------------------------------

    /** Makes room for {@code n} more words on the environment slab; false = limit reached (the caller faults E_MEM). */
    boolean ensureEnv(int n) {
        if (ep + n <= es.length) {
            return true;
        }
        if (ep + n > Op.W_MAX) {
            limitDetail = "environment slab: 2^28 words";
            return false;
        }
        int[] grown = copyOrNull(es, grown(es.length, ep + n));
        if (grown == null) {
            return false;
        }
        es = grown;
        allocGrows++;
        allocGrowBytes += 4L * es.length;
        return true;
    }

    /** Makes room for {@code n} more words of scratch; false = limit reached. */
    boolean ensureScratch(int n) {
        if (xp + n <= X.length) {
            return true;
        }
        if (xp + n > Op.W_MAX) {
            limitDetail = "scratch: 2^28 words";
            return false;
        }
        int[] grown = copyOrNull(X, grown(X.length, xp + n));
        if (grown == null) {
            return false;
        }
        X = grown;
        allocGrows++;
        allocGrowBytes += 4L * X.length;
        return true;
    }

    /** Makes room for {@code n} more registers; false = limit reached. */
    boolean ensureRegs(int n) {
        if (rp + n <= RS.length) {
            return true;
        }
        if (rp + n > Op.W_MAX) {
            limitDetail = "register stack: 2^28 words";
            return false;
        }
        int[] grown = copyOrNull(RS, grown(RS.length, rp + n));
        if (grown == null) {
            return false;
        }
        RS = grown;
        allocGrows++;
        allocGrowBytes += 4L * RS.length;
        return true;
    }

    /** Pushes an open binder's variable on SC; false = 65535 binders are open (the caller fails E_LIMIT). */
    boolean pushScope(int fvar) {
        if (ssp == SC.length) {
            if (ssp >= Op.BINDERS_MAX) {
                return false;
            }
            SC = Arrays.copyOf(SC, Math.min(ssp << 1, Op.BINDERS_MAX));
        }
        SC[ssp++] = fvar;
        return true;
    }

    /** The {@code cut} of ISA 1.4 for binders: closes every binder above {@code mark} (I-SCOPE). */
    void closeBindersAbove(int mark) {
        if (ssp > mark) {
            Scope.closeTo(this, mark);
        }
    }

    // ---------------------------------------------------------------------------------------------------
    // The Java heap (ISA section 9: limits produce DECLINE). The driver's memory account bounds the bytes
    // in use; an array that doubles needs its new capacity on top of the old one while it is copied, which
    // the account does not see. A growth that would not fit is refused: E_MEM, never an OutOfMemoryError
    // (the launchers run with ExitOnOutOfMemoryError: the whole run would end with status 3).
    // ---------------------------------------------------------------------------------------------------

    /** A growth that a void method had to refuse (table growth): the facade turns it into DECLINE(E_MEM). */
    static final class HeapLimit extends RuntimeException {
        private static final long serialVersionUID = 1L;

        HeapLimit(String what) {
            super(what, null, false, false);
        }
    }

    /** True once {@link #heapAllows} refused a growth in this declaration (the refusal is E_MEM whatever array it was). */
    boolean heapRefused;
    /** Arrays below this size are allocated without asking. */
    long heapCheckMin = 32L << 20;
    /** Bytes that must stay free after a growth (other sessions, the reference kernel's lanes, the loader). */
    long heapReserve = 256L << 20;

    /**
     * True if a new array of {@code bytes} fits into the Java heap with {@link #heapReserve} to spare (twice
     * the array when the engine has several workers, which grow at the same time). Free space is measured
     * after a collection if the first reading says no: the arrays of earlier doublings may still be counted.
     */
    boolean heapAllows(long bytes) {
        if (bytes < heapCheckMin) {
            return true;
        }
        long need = bytes + heapReserve + YOUNG_RESERVE + (engine != null && engine.config.workers > 1 ? bytes : 0);
        Runtime rt = Runtime.getRuntime();
        long free = rt.maxMemory() - rt.totalMemory() + rt.freeMemory();
        if (need <= free) {
            return true;
        }
        System.gc();
        free = rt.maxMemory() - rt.totalMemory() + rt.freeMemory();
        if (need <= free) {
            return true;
        }
        limitDetail = "the Java heap: a session array of " + bytes + " bytes does not fit (" + free + " bytes free of "
                + rt.maxMemory() + ", reserve " + heapReserve + ")";
        heapRefused = true;
        return false;
    }

    /**
     * Space that the free-memory reading counts but a large array cannot use: on a JVM with a generational
     * collector a large array must fit into the old generation, so the young generation's capacity is not
     * available to it (the launchers run {@code -Xmn1g}: 1 GiB, capped at a third of the heap). 0 in a native
     * image, whose heap is not split into regions of fixed size. Found by the driver's invariance suite (a
     * 6 GiB heap: 2.5 GiB "free" of which 1 GiB young, a 2 GiB slab: OutOfMemoryError in growRecords).
     */
    static final long YOUNG_RESERVE = "runtime".equals(System.getProperty("org.graalvm.nativeimage.imagecode")) ? 0
            : Math.min(1L << 30, Runtime.getRuntime().maxMemory() / 3);

    /**
     * {@code Arrays.copyOf(a, n)} if the Java heap can hold the copy, else null with {@link #limitDetail} and
     * {@link #heapRefused} set (the caller refuses the growth: E_MEM). {@link #heapAllows} asks first; an
     * {@code OutOfMemoryError} of the allocation itself (the estimate was wrong, or another worker took the
     * space meanwhile) is caught HERE, around this one allocation, and is a refusal too: the heap is as it
     * was, nothing was half done (F27; a launcher that runs with ExitOnOutOfMemoryError still ends, which is
     * why heapAllows exists).
     */
    private int[] copyOrNull(int[] a, int n) {
        if (!heapAllows(4L * n)) {
            return null;
        }
        try {
            return Arrays.copyOf(a, n);
        } catch (OutOfMemoryError e) {
            limitDetail = "the Java heap: a session array of " + 4L * n + " bytes could not be allocated";
            heapRefused = true;
            return null;
        }
    }

    private static int grown(int length, int need) {
        int n = Math.max(length, 1);
        while (n < need) {
            n <<= 1;
        }
        return n;
    }

    // ---------------------------------------------------------------------------------------------------
    // Ticks, budgets, failure classes (ISA 1.4; H11)
    // ---------------------------------------------------------------------------------------------------

    /** Ticks executed since the machine was created (the facade's {@code steps()}); not cleared by reset. */
    long ticks() {
        return tickBase - (fuel < 0 ? 0 : fuel);
    }

    /**
     * The slow part of TICK, entered when {@code (fuel & POLL_MASK) == POLL_MASK} after the decrement:
     * either the budget is exhausted ({@code fuel == -1}: ABORT with {@code E_STEPS}), or 4096 ticks have
     * passed and the signals are polled (FATAL with {@code E_CANCEL} / {@code E_MEM}).
     *
     * @return true if the step must return {@link #sig}
     */
    boolean tickSlow() {
        if (erSeen && erDeclines) {
            limitDetail = ER_DETAIL;
            err = MachineErrors.E_LIMIT;
            sig = FATAL;
            return true;
        }
        if (fuel < 0) {
            err = MachineErrors.E_STEPS;
            sig = ABORT;
            return true;
        }
        int e = poll();
        if (e != 0) {
            err = e;
            sig = FATAL;
            return true;
        }
        return false;
    }

    /**
     * {@code TICK(n)} for n > 1 (a step that charges several ticks at once): 0, or the signal to return.
     * The ticks that were left before an exhaustion count as executed.
     */
    int tick(int n) {
        long before = fuel;
        long after = before - n;
        if (after < 0) {
            fuel = -1;
            err = MachineErrors.E_STEPS;
            return ABORT;
        }
        fuel = after;
        if ((before >>> 12) != (after >>> 12)) {
            int e = poll();
            if (e != 0) {
                return fault(e);
            }
        }
        return 0;
    }

    /** The poll of ISA 1.4 / 14.4: 0, or the code of the fatal fault to raise. */
    int poll() {
        polls++;
        if (erSeen && erDeclines) {
            limitDetail = ER_DETAIL;
            return MachineErrors.E_LIMIT;
        }
        if (signals.firstFailure() < unit) {
            return MachineErrors.E_CANCEL;
        }
        if (signals.memoryExhausted()) {
            limitDetail = "the run's memory watermark (RunSignals.memoryExhausted)";
            return MachineErrors.E_MEM;
        }
        if (footprintBytes() > byteLimit) {
            limitDetail = "Budget.maxBytes: the unit added more session bytes than its budget";
            return MachineErrors.E_MEM;
        }
        return 0;
    }

    /**
     * Enters eager-reduce mode: {@code ER = 1}, for the comparison of the type of an {@code eagerReduce _ _}
     * argument with its domain (R02 6.5, 8.9). The caller saves {@link #ER} before and assigns the saved
     * value back afterwards. EVERY place that sets ER to 1 goes through this method ({@code M6GuardTest}).
     * Under {@link #erDeclines} the declaration ends here: the next TICK takes its slow path, which raises
     * {@code fault(E_LIMIT)} (fatal: no TRY turns it into U); a comparison that is answered without a tick
     * returns normally, and the facade declines the unit when the driver returns.
     */
    void enterER() {
        ER = 1;
        if (!erSeen) {
            erSeen = true;
            if (erDeclines) {
                // the next (--fuel & POLL_MASK) == POLL_MASK; ticks() = tickBase - fuel is unchanged
                long f = fuel & ~Op.POLL_MASK;
                tickBase -= fuel - f;
                fuel = f;
            }
        }
    }

    /** {@code fail(E)}: a kernel error or a recoverable limit. Returns the signal to return (ABORT). */
    int fail(int code) {
        if (!MachineErrors.isRecoverableInTry(code)) {
            return fault(code);
        }
        err = code;
        return ABORT;
    }

    /**
     * {@code fail(E)} with the site of the failure: {@code a}, {@code b} are the values a message should
     * show (ISA 14.1 "the two types of a mismatch"); 0 = none. Same signal as {@link #fail(int)}.
     */
    int fail(int code, int a, int b) {
        errA = a;
        errB = b;
        return fail(code);
    }

    /** {@code fault(E)}: an invariant was seen broken, or the run no longer wants this unit. Returns FATAL. */
    int fault(int code) {
        err = code;
        return FATAL;
    }

    /**
     * A debug audit failed (ISA 5.5): {@code fault(E_AUDIT)}, fatal, with a detail for {@code lastMessage()}.
     * Only called on the failing path, so building the message costs nothing otherwise.
     */
    int auditFail(String detail) {
        if (auditMessage == null) {
            auditMessage = detail;
        }
        return fault(MachineErrors.E_AUDIT);
    }

    /**
     * Counts the outcome ({@code Layout.F / T / U}) of a speculative segment of shortcut {@code b}
     * ({@code Op.B1 .. Op.B12}; ISA 6.3, 14.5). Call it under {@code if (m.stats)}.
     */
    void probe(int b, int outcome) {
        probes[3 * b + outcome]++;
    }

    /** {@code FAIL} / {@code FAULT} by the class of the code (ISA 4.1). */
    int raise(int code) {
        return MachineErrors.isRecoverableInTry(code) ? fail(code) : fault(code);
    }

    /**
     * Start of a speculative segment (H11): {@code fuel = min(budget, fuel)}.
     *
     * @return the fuel to restore (pass it to {@link #saveTry} / {@code Frames.k_try})
     */
    long tryEnter(long budget) {
        long saved = fuel;
        long given = Math.min(budget, saved);
        fuel = given;
        tickBase += given - saved;
        return saved;
    }

    /** The segment was suspended: writes the TRY frame of ISA 1.3 (layout {@code Op.TRY_*}) and returns SUSP. */
    int saveTry(long saved, long budget, int ssp, int xp, int ep, int rp, int er) {
        int p = room(Op.TRY_N + 1);
        int[] s = S;
        s[p + Op.TRY_FUEL_HI] = (int) (saved >>> 32);
        s[p + Op.TRY_FUEL_LO] = (int) saved;
        s[p + Op.TRY_BUDGET_HI] = (int) (budget >>> 32);
        s[p + Op.TRY_BUDGET_LO] = (int) budget;
        s[p + Op.TRY_SSP] = ssp;
        s[p + Op.TRY_XP] = xp;
        s[p + Op.TRY_EP] = ep;
        s[p + Op.TRY_RP] = rp;
        s[p + Op.TRY_ER] = er;
        s[p + Op.TRY_N] = Op.header(Op.K_TRY, Op.TRY_N);
        framesWritten++;
        frameWords += Op.TRY_N + 1;
        return SUSP;
    }

    // ---------------------------------------------------------------------------------------------------
    // Call events (H4 step 2b; ISA 8.3, section 11 "frames per declaration")
    // ---------------------------------------------------------------------------------------------------

    /** Records the call event of a judgment (only reached when {@link #stats} is set). */
    void call(int op) {
        calls[op]++;
        if ((debug & Op.DBG_TRACE) != 0) {
            if (traceN == trace.length) {
                trace = Arrays.copyOf(trace, Math.max(64, traceN << 1));
            }
            trace[traceN++] = op;
        }
    }

    /** A spilled judgment is entered again by the driver: its tick and its call event are given back. */
    private void uncall(int key) {
        fuel++;
        spills++;
        if (stats) {
            calls[key >>> 4]--;
            if ((debug & Op.DBG_TRACE) != 0 && traceN > 0) {
                traceN--;
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------
    // Frames: the side buffer, sigN, spillN (H3, H4, H6)
    // ---------------------------------------------------------------------------------------------------

    /** Reserves {@code n} words below the frames already in the side buffer. */
    private int room(int n) {
        if (sp < n) {
            int used = S.length - sp;
            int[] s = new int[grown(S.length << 1, used + n)];
            System.arraycopy(S, sp, s, s.length - used, used);
            sp = s.length - used;
            S = s;
        }
        return sp -= n;
    }

    // ---- sig0..sig12, spill0..spill12 (generated; one pair per payload size) ----

    /** H3: a sub-judgment returned the signal {@code r}. SUSP: write the continuation frame {@code key} with 0 payload words; else pass {@code r} on. */
    int sig0(int r, int key) {
        return r != SUSP ? r : put0(key);
    }

    /** H4 step 3: the judgment is too deep to start; it becomes its state-0 frame {@code key} and gives its tick back. */
    int spill0(int key) {
        uncall(key);
        return put0(key);
    }

    private int put0(int key) {
        int p = room(1);
        int[] s = S;
        s[p] = key << Op.KEY_SHIFT;
        framesWritten++;
        frameWords += 1;
        return SUSP;
    }

    /** H3: a sub-judgment returned the signal {@code r}. SUSP: write the continuation frame {@code key} with 1 payload word; else pass {@code r} on. */
    int sig1(int r, int key, int p0) {
        return r != SUSP ? r : put1(key, p0);
    }

    /** H4 step 3: the judgment is too deep to start; it becomes its state-0 frame {@code key} and gives its tick back. */
    int spill1(int key, int p0) {
        uncall(key);
        return put1(key, p0);
    }

    private int put1(int key, int p0) {
        int p = room(2);
        int[] s = S;
        s[p] = p0;
        s[p + 1] = key << Op.KEY_SHIFT | 1;
        framesWritten++;
        frameWords += 2;
        return SUSP;
    }

    /** H3: a sub-judgment returned the signal {@code r}. SUSP: write the continuation frame {@code key} with 2 payload words; else pass {@code r} on. */
    int sig2(int r, int key, int p0, int p1) {
        return r != SUSP ? r : put2(key, p0, p1);
    }

    /** H4 step 3: the judgment is too deep to start; it becomes its state-0 frame {@code key} and gives its tick back. */
    int spill2(int key, int p0, int p1) {
        uncall(key);
        return put2(key, p0, p1);
    }

    private int put2(int key, int p0, int p1) {
        int p = room(3);
        int[] s = S;
        s[p] = p0;
        s[p + 1] = p1;
        s[p + 2] = key << Op.KEY_SHIFT | 2;
        framesWritten++;
        frameWords += 3;
        return SUSP;
    }

    /** H3: a sub-judgment returned the signal {@code r}. SUSP: write the continuation frame {@code key} with 3 payload words; else pass {@code r} on. */
    int sig3(int r, int key, int p0, int p1, int p2) {
        return r != SUSP ? r : put3(key, p0, p1, p2);
    }

    /** H4 step 3: the judgment is too deep to start; it becomes its state-0 frame {@code key} and gives its tick back. */
    int spill3(int key, int p0, int p1, int p2) {
        uncall(key);
        return put3(key, p0, p1, p2);
    }

    private int put3(int key, int p0, int p1, int p2) {
        int p = room(4);
        int[] s = S;
        s[p] = p0;
        s[p + 1] = p1;
        s[p + 2] = p2;
        s[p + 3] = key << Op.KEY_SHIFT | 3;
        framesWritten++;
        frameWords += 4;
        return SUSP;
    }

    /** H3: a sub-judgment returned the signal {@code r}. SUSP: write the continuation frame {@code key} with 4 payload words; else pass {@code r} on. */
    int sig4(int r, int key, int p0, int p1, int p2, int p3) {
        return r != SUSP ? r : put4(key, p0, p1, p2, p3);
    }

    /** H4 step 3: the judgment is too deep to start; it becomes its state-0 frame {@code key} and gives its tick back. */
    int spill4(int key, int p0, int p1, int p2, int p3) {
        uncall(key);
        return put4(key, p0, p1, p2, p3);
    }

    private int put4(int key, int p0, int p1, int p2, int p3) {
        int p = room(5);
        int[] s = S;
        s[p] = p0;
        s[p + 1] = p1;
        s[p + 2] = p2;
        s[p + 3] = p3;
        s[p + 4] = key << Op.KEY_SHIFT | 4;
        framesWritten++;
        frameWords += 5;
        return SUSP;
    }

    /** H3: a sub-judgment returned the signal {@code r}. SUSP: write the continuation frame {@code key} with 5 payload words; else pass {@code r} on. */
    int sig5(int r, int key, int p0, int p1, int p2, int p3, int p4) {
        return r != SUSP ? r : put5(key, p0, p1, p2, p3, p4);
    }

    /** H4 step 3: the judgment is too deep to start; it becomes its state-0 frame {@code key} and gives its tick back. */
    int spill5(int key, int p0, int p1, int p2, int p3, int p4) {
        uncall(key);
        return put5(key, p0, p1, p2, p3, p4);
    }

    private int put5(int key, int p0, int p1, int p2, int p3, int p4) {
        int p = room(6);
        int[] s = S;
        s[p] = p0;
        s[p + 1] = p1;
        s[p + 2] = p2;
        s[p + 3] = p3;
        s[p + 4] = p4;
        s[p + 5] = key << Op.KEY_SHIFT | 5;
        framesWritten++;
        frameWords += 6;
        return SUSP;
    }

    /** H3: a sub-judgment returned the signal {@code r}. SUSP: write the continuation frame {@code key} with 6 payload words; else pass {@code r} on. */
    int sig6(int r, int key, int p0, int p1, int p2, int p3, int p4, int p5) {
        return r != SUSP ? r : put6(key, p0, p1, p2, p3, p4, p5);
    }

    /** H4 step 3: the judgment is too deep to start; it becomes its state-0 frame {@code key} and gives its tick back. */
    int spill6(int key, int p0, int p1, int p2, int p3, int p4, int p5) {
        uncall(key);
        return put6(key, p0, p1, p2, p3, p4, p5);
    }

    private int put6(int key, int p0, int p1, int p2, int p3, int p4, int p5) {
        int p = room(7);
        int[] s = S;
        s[p] = p0;
        s[p + 1] = p1;
        s[p + 2] = p2;
        s[p + 3] = p3;
        s[p + 4] = p4;
        s[p + 5] = p5;
        s[p + 6] = key << Op.KEY_SHIFT | 6;
        framesWritten++;
        frameWords += 7;
        return SUSP;
    }

    /** H3: a sub-judgment returned the signal {@code r}. SUSP: write the continuation frame {@code key} with 7 payload words; else pass {@code r} on. */
    int sig7(int r, int key, int p0, int p1, int p2, int p3, int p4, int p5, int p6) {
        return r != SUSP ? r : put7(key, p0, p1, p2, p3, p4, p5, p6);
    }

    /** H4 step 3: the judgment is too deep to start; it becomes its state-0 frame {@code key} and gives its tick back. */
    int spill7(int key, int p0, int p1, int p2, int p3, int p4, int p5, int p6) {
        uncall(key);
        return put7(key, p0, p1, p2, p3, p4, p5, p6);
    }

    private int put7(int key, int p0, int p1, int p2, int p3, int p4, int p5, int p6) {
        int p = room(8);
        int[] s = S;
        s[p] = p0;
        s[p + 1] = p1;
        s[p + 2] = p2;
        s[p + 3] = p3;
        s[p + 4] = p4;
        s[p + 5] = p5;
        s[p + 6] = p6;
        s[p + 7] = key << Op.KEY_SHIFT | 7;
        framesWritten++;
        frameWords += 8;
        return SUSP;
    }

    /** H3: a sub-judgment returned the signal {@code r}. SUSP: write the continuation frame {@code key} with 8 payload words; else pass {@code r} on. */
    int sig8(int r, int key, int p0, int p1, int p2, int p3, int p4, int p5, int p6, int p7) {
        return r != SUSP ? r : put8(key, p0, p1, p2, p3, p4, p5, p6, p7);
    }

    /** H4 step 3: the judgment is too deep to start; it becomes its state-0 frame {@code key} and gives its tick back. */
    int spill8(int key, int p0, int p1, int p2, int p3, int p4, int p5, int p6, int p7) {
        uncall(key);
        return put8(key, p0, p1, p2, p3, p4, p5, p6, p7);
    }

    private int put8(int key, int p0, int p1, int p2, int p3, int p4, int p5, int p6, int p7) {
        int p = room(9);
        int[] s = S;
        s[p] = p0;
        s[p + 1] = p1;
        s[p + 2] = p2;
        s[p + 3] = p3;
        s[p + 4] = p4;
        s[p + 5] = p5;
        s[p + 6] = p6;
        s[p + 7] = p7;
        s[p + 8] = key << Op.KEY_SHIFT | 8;
        framesWritten++;
        frameWords += 9;
        return SUSP;
    }

    /** H3: a sub-judgment returned the signal {@code r}. SUSP: write the continuation frame {@code key} with 9 payload words; else pass {@code r} on. */
    int sig9(int r, int key, int p0, int p1, int p2, int p3, int p4, int p5, int p6, int p7, int p8) {
        return r != SUSP ? r : put9(key, p0, p1, p2, p3, p4, p5, p6, p7, p8);
    }

    /** H4 step 3: the judgment is too deep to start; it becomes its state-0 frame {@code key} and gives its tick back. */
    int spill9(int key, int p0, int p1, int p2, int p3, int p4, int p5, int p6, int p7, int p8) {
        uncall(key);
        return put9(key, p0, p1, p2, p3, p4, p5, p6, p7, p8);
    }

    private int put9(int key, int p0, int p1, int p2, int p3, int p4, int p5, int p6, int p7, int p8) {
        int p = room(10);
        int[] s = S;
        s[p] = p0;
        s[p + 1] = p1;
        s[p + 2] = p2;
        s[p + 3] = p3;
        s[p + 4] = p4;
        s[p + 5] = p5;
        s[p + 6] = p6;
        s[p + 7] = p7;
        s[p + 8] = p8;
        s[p + 9] = key << Op.KEY_SHIFT | 9;
        framesWritten++;
        frameWords += 10;
        return SUSP;
    }

    /** H3: a sub-judgment returned the signal {@code r}. SUSP: write the continuation frame {@code key} with 10 payload words; else pass {@code r} on. */
    int sig10(int r, int key, int p0, int p1, int p2, int p3, int p4, int p5, int p6, int p7, int p8, int p9) {
        return r != SUSP ? r : put10(key, p0, p1, p2, p3, p4, p5, p6, p7, p8, p9);
    }

    /** H4 step 3: the judgment is too deep to start; it becomes its state-0 frame {@code key} and gives its tick back. */
    int spill10(int key, int p0, int p1, int p2, int p3, int p4, int p5, int p6, int p7, int p8, int p9) {
        uncall(key);
        return put10(key, p0, p1, p2, p3, p4, p5, p6, p7, p8, p9);
    }

    private int put10(int key, int p0, int p1, int p2, int p3, int p4, int p5, int p6, int p7, int p8, int p9) {
        int p = room(11);
        int[] s = S;
        s[p] = p0;
        s[p + 1] = p1;
        s[p + 2] = p2;
        s[p + 3] = p3;
        s[p + 4] = p4;
        s[p + 5] = p5;
        s[p + 6] = p6;
        s[p + 7] = p7;
        s[p + 8] = p8;
        s[p + 9] = p9;
        s[p + 10] = key << Op.KEY_SHIFT | 10;
        framesWritten++;
        frameWords += 11;
        return SUSP;
    }

    /** H3: a sub-judgment returned the signal {@code r}. SUSP: write the continuation frame {@code key} with 11 payload words; else pass {@code r} on. */
    int sig11(int r, int key, int p0, int p1, int p2, int p3, int p4, int p5, int p6, int p7, int p8, int p9, int p10) {
        return r != SUSP ? r : put11(key, p0, p1, p2, p3, p4, p5, p6, p7, p8, p9, p10);
    }

    /** H4 step 3: the judgment is too deep to start; it becomes its state-0 frame {@code key} and gives its tick back. */
    int spill11(int key, int p0, int p1, int p2, int p3, int p4, int p5, int p6, int p7, int p8, int p9, int p10) {
        uncall(key);
        return put11(key, p0, p1, p2, p3, p4, p5, p6, p7, p8, p9, p10);
    }

    private int put11(int key, int p0, int p1, int p2, int p3, int p4, int p5, int p6, int p7, int p8, int p9, int p10) {
        int p = room(12);
        int[] s = S;
        s[p] = p0;
        s[p + 1] = p1;
        s[p + 2] = p2;
        s[p + 3] = p3;
        s[p + 4] = p4;
        s[p + 5] = p5;
        s[p + 6] = p6;
        s[p + 7] = p7;
        s[p + 8] = p8;
        s[p + 9] = p9;
        s[p + 10] = p10;
        s[p + 11] = key << Op.KEY_SHIFT | 11;
        framesWritten++;
        frameWords += 12;
        return SUSP;
    }

    /** H3: a sub-judgment returned the signal {@code r}. SUSP: write the continuation frame {@code key} with 12 payload words; else pass {@code r} on. */
    int sig12(int r, int key, int p0, int p1, int p2, int p3, int p4, int p5, int p6, int p7, int p8, int p9, int p10, int p11) {
        return r != SUSP ? r : put12(key, p0, p1, p2, p3, p4, p5, p6, p7, p8, p9, p10, p11);
    }

    /** H4 step 3: the judgment is too deep to start; it becomes its state-0 frame {@code key} and gives its tick back. */
    int spill12(int key, int p0, int p1, int p2, int p3, int p4, int p5, int p6, int p7, int p8, int p9, int p10, int p11) {
        uncall(key);
        return put12(key, p0, p1, p2, p3, p4, p5, p6, p7, p8, p9, p10, p11);
    }

    private int put12(int key, int p0, int p1, int p2, int p3, int p4, int p5, int p6, int p7, int p8, int p9, int p10, int p11) {
        int p = room(13);
        int[] s = S;
        s[p] = p0;
        s[p + 1] = p1;
        s[p + 2] = p2;
        s[p + 3] = p3;
        s[p + 4] = p4;
        s[p + 5] = p5;
        s[p + 6] = p6;
        s[p + 7] = p7;
        s[p + 8] = p8;
        s[p + 9] = p9;
        s[p + 10] = p10;
        s[p + 11] = p11;
        s[p + 12] = key << Op.KEY_SHIFT | 12;
        framesWritten++;
        frameWords += 13;
        return SUSP;
    }

    /** The general form of {@code sigN} for a payload held in scratch: {@code X[xb .. xb + n)}. */
    int sigX(int r, int key, int xb, int n) {
        if (r != SUSP) {
            return r;
        }
        int p = room(n + 1);
        System.arraycopy(X, xb, S, p, n);
        S[p + n] = Op.header(key, n);
        framesWritten++;
        frameWords += n + 1;
        return SUSP;
    }

    /** Moves the frames of the finished unwind onto W; false = W limit (2^28 words). */
    private boolean flush() {
        int n = S.length - sp;
        if (wp + n > W.length) {
            if (wp + n > Op.W_MAX) {
                sp = S.length;
                limitDetail = "worklist W: 2^28 words (ISA section 9)";
                return false;
            }
            int[] grown = copyOrNull(W, grown(W.length, wp + n));
            if (grown == null) {
                sp = S.length;
                return false;
            }
            W = grown;
            allocGrows++;
            allocGrowBytes += 4L * W.length;
        }
        System.arraycopy(S, sp, W, wp, n);
        wp += n;
        sp = S.length;
        flushes++;
        if (wp > maxWp) {
            maxWp = wp;
        }
        return true;
    }

    // ---------------------------------------------------------------------------------------------------
    // The driver (H6)
    // ---------------------------------------------------------------------------------------------------

    /**
     * Completes a judgment that was called at host depth 0: {@code r} is what its entry step returned.
     * If it was suspended, W is run until it is back at the level of the call: pop the top frame, enter
     * its step with the result register and d = 0.
     *
     * <p>Only code at host depth 0 may call this (the DECL driver, the facade, tests): a judgment never
     * does (it would nest host recursion without bound).
     *
     * @return the result; or ABORT (no TRY frame was left: {@code err} is the kernel error or limit) or
     *     FATAL ({@code err} is the fault), with W cut back to the level of the call. After ABORT or FATAL
     *     the caller restores the machine stacks with {@link #abandon()}.
     */
    int drive(int r) {
        final int base = wp;
        for (;;) {
            if (r >= SIG) {
                if (r == SUSP) {
                    if (!flush()) {
                        r = fault(heapRefused ? MachineErrors.E_MEM : MachineErrors.E_STACK);
                        continue;
                    }
                    r = 0;
                } else {
                    sp = S.length;
                    cuts++;
                    if (r == FATAL) {
                        wp = base;
                        return FATAL;
                    }
                    // ABORT: cut W to the innermost TRY frame, which receives the signal (ISA 1.4 cut)
                    int[] w = W;
                    int p = wp;
                    while (p > base && (w[p - 1] >>> Op.KEY_SHIFT) != Op.K_TRY) {
                        p -= (w[p - 1] & Op.N_MASK) + 1;
                    }
                    wp = p;
                    if (p == base) {
                        return ABORT;
                    }
                }
            } else if (wp == base) {
                return r;
            }
            int[] w = W;
            int hdr = w[--wp];
            wp -= hdr & Op.N_MASK;
            r = resume(hdr >>> Op.KEY_SHIFT, w, wp, r);
        }
    }

    /** Family dispatch of the driver (H6, H10 rule 2): the family's {@code resume} switches on the key. */
    private int resume(int key, int[] w, int p, int r) {
        switch (key >>> 8) {
            case Op.FAM_EV:
                return Eval.resume(this, key, w, p, r);
            case Op.FAM_RD:
                return Reduce.resume(this, key, w, p, r);
            case Op.FAM_CV:
                return Conv.resume(this, key, w, p, r);
            case Op.FAM_TY:
                return Infer.resume(this, key, w, p, r);
            case Op.FAM_CODE:
                return Code.resume(this, key, w, p, r);
            case Op.FAM_CTL:
                return Frames.resume(this, key, w, p, r);
            case Op.FAM_EXT:
                return ext != null ? ext.resume(this, key, w, p, r) : fault(MachineErrors.E_INTERNAL);
            default:
                return fault(MachineErrors.E_INTERNAL);
        }
    }

    // ---------------------------------------------------------------------------------------------------
    // Declarations and sessions (ISA 1.6, 5.3, 14)
    // ---------------------------------------------------------------------------------------------------

    /**
     * Prepares the machine for one declaration: empty control stacks, the step budget ({@code maxSteps},
     * clamped to {@link Op#FUEL_UNLIMITED}), the byte limit relative to the current footprint, the UNDEC
     * stamp. {@code gen} is advanced by the caller (M5 / M6) according to ISA 5.3.
     */
    void beginDecl(int rank, int unitIndex, long maxSteps, long maxBytes) {
        erSeen = false;
        long done = ticks();
        fuel = maxSteps > Op.FUEL_UNLIMITED || maxSteps < 0 ? Op.FUEL_UNLIMITED : maxSteps;
        tickBase = done + fuel;
        decl = rank;
        unit = unitIndex;
        if (++declStamp == Integer.MAX_VALUE) {
            Arrays.fill(undec, 0);
            declStamp = 1;
        }
        long used = footprintBytes();
        byteLimit = maxBytes >= Long.MAX_VALUE - used || maxBytes < 0 ? Long.MAX_VALUE : used + maxBytes;
        wp = 0;
        sp = S.length;
        xp = 0;
        ep = 0;
        rp = 0;
        ER = 0;
        err = 0;
        errA = 0;
        errB = 0;
        sig = 0;
        dbgNest = 0;
        auditMessage = null;
        limitDetail = null;
        heapRefused = false;
        fresh = false;
        closeBindersAbove(0);
        x1.beginDecl(this);
        x2.beginDecl(this);
        x3.beginDecl(this);
        x4.beginDecl(this);
        x5.beginDecl(this);
        x7.beginDecl(this);
    }

    /**
     * After {@link #drive} returned ABORT or FATAL (or at any point where the declaration is given up):
     * restores what the abandoned judgments left on the machine stacks (H7): binders are closed, W, the
     * side buffer, scratch, environments and registers are emptied, ER is cleared. {@code err} is kept.
     */
    void abandon() {
        wp = 0;
        sp = S.length;
        closeBindersAbove(0);
        xp = 0;
        ep = 0;
        rp = 0;
        ER = 0;
        dbgNest = 0;
    }

    /**
     * {@link #abandon()} down to marks: for a caller that keeps binders and an environment of its own
     * below the abandoned judgment (the fixture's context, the locals of {@code MachineOps}): binders above
     * {@code sspMark} are closed, the environment slab is cut to {@code epMark}, everything else is emptied.
     */
    void abandonTo(int sspMark, int epMark) {
        wp = 0;
        sp = S.length;
        closeBindersAbove(sspMark);
        xp = 0;
        ep = epMark;
        rp = 0;
        ER = 0;
        dbgNest = 0;
    }

    /**
     * Forgets everything the session learned, in time proportional to what was used (ISA 1.6): every
     * used record and run is re-probed and its table slot zeroed, the logged pair entries are zeroed, the
     * direct-mapped caches are invalidated by the epoch. Arrays that grew return to their base size.
     * {@code ticks()} is not cleared. The worker context's overlays are reset by the caller.
     *
     * <p>Under {@code Op.DBG_POISON} (ISA 5.5) the ids of the next session are shifted: its records and
     * runs start where the ended session's stopped, and the ended session's words are zeroed, so that a
     * ref kept across the reset reads as kind 0 / an empty run and faults where it is used. The bases
     * wrap to the start once half of the base capacity is behind them.
     */
    /**
     * {@link #reset()} clears a table of base size with one fill instead of one probe per entry when the
     * table has at most 2^RESET_FILL_SHIFT slots per entry (a probe recomputes the hash and is a random
     * access; a fill is sequential and vectorised). Still O(used).
     */
    private static final int RESET_FILL_SHIFT = 5;

    void reset() {
        resets++;
        bytesAtReset += engineBytes();
        recordsMade += vp - vbase;
        runWordsMade += rsp - rbase;
        pairsMade += pairUsed;
        long done = ticks();
        boolean poison = (debug & Op.DBG_POISON) != 0;
        // vtab
        if (vtab.length != 1 << sizes.vtab) {
            giveTable(vpool, vtab); // zeroed there: O(size), at most 4 slots per record
            vtab = takeTable(vpool, sizes.vtab);
            vtabMask = vtab.length - 1;
        } else if ((long) (vp - vbase) << RESET_FILL_SHIFT >= vtab.length) {
            Arrays.fill(vtab, 0); // a sequential fill of a small table is cheaper than one probe per record
        } else {
            int[] tab = vtab;
            int mask = vtabMask;
            int[] v = vh;
            for (int r = vbase; r < vp; r++) {
                int slot = Hash.ofRecord(v, r) & mask;
                int n = 0;
                while ((tab[slot] & Layout.TAB_INDEX_MASK) != r && n++ <= mask) {
                    slot = (slot + 1) & mask;
                }
                if (n <= mask) {
                    tab[slot] = 0;
                }
            }
        }
        if (vh.length > keptRecordWords()) {
            // a slab beyond the kept size goes back to the heap; the kept array it left behind is taken back
            // (its content is dead: a record is written whole before it is read)
            if (vhKept != null) {
                vh = vhKept;
                if (poison) {
                    Arrays.fill(vh, 0);
                }
            } else {
                vh = new int[Layout.REC_WORDS << sizes.records];
                allocResets++;
                allocResetBytes += 4L * vh.length;
            }
            vp = Layout.FIRST_RECORD;
        } else if (poison) {
            Arrays.fill(vh, vbase << Layout.REC_SHIFT, vp << Layout.REC_SHIFT, 0);
            if (vp > 1 << (sizes.records - 1)) {
                vp = Layout.FIRST_RECORD;
            }
        } else {
            vp = Layout.FIRST_RECORD;
        }
        vhKept = null;
        vbase = vp;
        // rtab
        if (rtab.length != 1 << sizes.rtab) {
            giveTable(rpool, rtab);
            rtab = takeTable(rpool, sizes.rtab);
            rtabMask = rtab.length - 1;
        } else if ((long) rtabUsed << RESET_FILL_SHIFT >= rtab.length) {
            Arrays.fill(rtab, 0);
        } else {
            int[] tab = rtab;
            int mask = rtabMask;
            int[] a = rs;
            for (int r = rbase; r < rsp;) {
                int hdr = a[r];
                if (Layout.runKind(hdr) != Layout.RUN_SPINE) {
                    int slot = a[r + Layout.RUN_HASH] & mask;
                    int n = 0;
                    while ((tab[slot] & Layout.TAB_INDEX_MASK) != r && n++ <= mask) {
                        slot = (slot + 1) & mask;
                    }
                    if (n <= mask) {
                        tab[slot] = 0;
                    }
                }
                r += Layout.runHeaderWords(hdr) + Layout.runLen(hdr);
            }
        }
        if (rs.length > keptRunWords()) {
            if (rsKept != null) {
                rs = rsKept;
                if (poison) {
                    Arrays.fill(rs, 0);
                }
            } else {
                rs = new int[1 << sizes.runWords];
                allocResets++;
                allocResetBytes += 4L * rs.length;
            }
            rsp = Layout.FIRST_RUN;
        } else if (poison) {
            Arrays.fill(rs, rbase, rsp, 0);
            if (rsp > 1 << (sizes.runWords - 1)) {
                rsp = Layout.FIRST_RUN;
            }
        } else {
            rsp = Layout.FIRST_RUN;
        }
        rsKept = null;
        rbase = rsp;
        rtabUsed = 0;
        // pairs
        {
            // the entries in use are those of the log: zeroed in O(used), whatever the table's size
            int[] t = pair;
            for (int i = 0; i < plogN; i++) {
                int o = plog[i] << Layout.PAIR_SHIFT;
                t[o + Layout.PAIR_LO] = 0;
                t[o + Layout.PAIR_HI] = 0;
                t[o + Layout.PAIR_STATUS] = 0;
            }
            if (pairMask + 1 != 1 << sizes.pairs) {
                poolPairs(t); // all zero now
                pair = takePairs(sizes.pairs, true);
                pairMask = (1 << sizes.pairs) - 1;
            }
        }
        if (plog.length > keptStackWords(sizes.plog)) {
            plog = new int[1 << sizes.plog]; // the log is written before it is read: a grown one is kept as it is
            allocResets++;
            allocResetBytes += 4L * plog.length;
        }
        plogN = 0;
        pairUsed = 0;
        // numbers made in this session (NBIG records are gone: no id of the overlay is referenced any more)
        if (ownNats && nats.nextId() != nats.firstId()) {
            nats.reset();
        }
        // direct-mapped caches
        if (++epoch == Integer.MAX_VALUE) {
            Arrays.fill(ic, 0);
            Arrays.fill(lsub, 0);
            Arrays.fill(lsubList, 0);
            Arrays.fill(limp, 0);
            Arrays.fill(sigt, 0);
            Arrays.fill(fv0, 0);
            epoch = 1;
        }
        // control stacks (the records the scope stack points to are gone: nothing to release)
        // (a working array that grew is kept up to 2^keepStack words: LIFO, touched up to its high-water mark only)
        if (es.length > keptStackWords(sizes.env)) {
            es = resetArray(sizes.env);
        }
        if (W.length > keptStackWords(sizes.worklist)) {
            W = resetArray(sizes.worklist);
        }
        if (S.length > keptStackWords(sizes.side)) {
            S = resetArray(sizes.side);
        }
        if (SC.length > keptStackWords(sizes.scope)) {
            SC = resetArray(sizes.scope);
        }
        if (X.length > keptStackWords(sizes.scratch)) {
            X = resetArray(sizes.scratch);
        }
        if (RS.length > keptStackWords(sizes.regs)) {
            RS = resetArray(sizes.regs);
        }
        wp = 0;
        sp = S.length;
        ssp = 0;
        xp = 0;
        ep = 0;
        rp = 0;
        ER = 0;
        err = 0;
        sig = 0;
        errA = 0;
        errB = 0;
        gen = 1;
        dbgNest = 0;
        fresh = true;
        fuel = Op.FUEL_UNLIMITED;
        tickBase = done + fuel;
        byteLimit = Long.MAX_VALUE;
        x1.reset(this);
        x2.reset(this);
        x3.reset(this);
        x4.reset(this);
        x5.reset(this);
        x7.reset(this);
    }

    /** A working array of base size 2^log2, allocated by a reset (counted). */
    private int[] resetArray(int log2) {
        allocResets++;
        allocResetBytes += 4L << log2;
        return new int[1 << log2];
    }

    /** True if the session holds nothing: no record, run or pair entry (a reset would change nothing but the epoch). */
    boolean pristine() {
        return vp == vbase && rsp == rbase && plogN == 0;
    }

    /**
     * Bytes in use of the machine's own arenas, counted once (ISA 1.6): records, run words, pair entries,
     * the pair log, the occupied intern-table slots, the limbs of the machine's number overlay, and what the
     * packages report for their own tables.
     * O(1). Capacity is never part of it; working storage (W, SC, X, es, caches) is not counted.
     */
    long engineBytes() {
        long records = vp - vbase;
        return Layout.REC_BYTES * records + 4L * ((rsp - rbase) + ((long) pairUsed << Layout.PAIR_SHIFT) + plogN)
                + 4L * (records + rtabUsed) + (ownNats ? nats.bytesUsed() : 0) + x1.bytes() + x2.bytes() + x3.bytes()
                + x4.bytes() + x5.bytes() + x7.bytes();
    }

    /** {@link #engineBytes()} plus the bytes in use of the worker context's overlays (the facade's footprint). */
    long footprintBytes() {
        return engineBytes() + (ctx == null ? 0 : ctx.footprintBytes());
    }

    /** True if nothing is suspended and no machine stack holds anything (the state between two judgments at depth 0). */
    boolean idle() {
        return wp == 0 && sp == S.length && ssp == 0 && xp == 0 && ep == 0 && rp == 0 && ER == 0;
    }
}
