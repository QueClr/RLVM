package rlvm.machine;

import static rlvm.machine.Op.ABORT;
import static rlvm.machine.Op.FATAL;
import static rlvm.machine.Op.SIG;

import java.util.Arrays;
import rlvm.api.MachineErrors;
import rlvm.api.bind.LevelBinding;
import rlvm.store.Kind;
import rlvm.store.Node;
import rlvm.store.Terms;

/**
 * Debug modes of ISA 5.5 ({@code memocheck}, {@code shortcheck}, {@code poison}, {@code scopeaudit},
 * {@code maskaudit}, {@code sharecheck}, {@code pubcheck}, plus {@code trace}) and the names of the
 * staging switches. A failed audit is {@code fault(E_AUDIT)}, fatal: the declaration ends with DECLINE
 * whatever is on W (ISA 1.4), and {@code Session.lastMessage()} carries the detail. Owner: M6.
 *
 * <p>Nothing here runs on a normal path: every call site is guarded by a test of {@code m.debug}, which
 * is 0 unless {@code -Drlvm.machine.debug=..} (or {@code EngineConfig.memoCheck}, or a test) set it.
 *
 * <h2>Hooks (what the owner of a judgment writes)</h2>
 * <pre>
 * // memocheck, generic form: at a memo hit of judgment J (the fast path found the answer w for key v)
 * if (m.debug != 0 &amp;&amp; Debug.memoEnter(m)) {          // mode on, not nested, sampled in: state saved
 *     vh[o + Layout.M_WHNF] = 0;                        // bypass the entry
 *     int r = m.drive(whnf(m, v, 0));                   // recompute (the entry step with d = 0, then drive)
 *     m.vh[o + Layout.M_WHNF] = w;                      // put the entry back (the array may have grown)
 *     w = Debug.memoExit(m, Op.WHNF, v, 0, w, r);       // compare: w, or FATAL (E_AUDIT, or a fatal fault of r)
 *     if (w &gt;= Op.SIG) return w;
 * }
 * // memocheck, one line, for the memos whose slot is fixed by Layout (see memoCheck for the list)
 * if (m.debug != 0) { w = Debug.memoCheck(m, Op.WHNF, v, 0, w); if (w &gt;= Op.SIG) return w; }
 * // scopeaudit: at every GET (ISA 2.8 I-FV3)
 * if ((m.debug &amp; Op.DBG_SCOPEAUDIT) != 0 &amp;&amp; Debug.scopeAudit(m, v) != 0) return Op.FATAL;
 * // maskaudit: where a closure or suspension of term t is made (before PRUNE)
 * if ((m.debug &amp; Op.DBG_MASKAUDIT) != 0 &amp;&amp; Debug.maskAudit(m, t) != 0) return Op.FATAL;
 * // sharecheck: where the walk enters an untabled node t under frame run fr (stage 1)
 * if ((m.debug &amp; Op.DBG_SHARECHECK) != 0 &amp;&amp; Debug.shareCheck(m, t, fr) != 0) return Op.FATAL;
 * // shortcheck: a shortcut answered T for CONV(a, b) (stage 2+; M4)
 * if ((m.debug &amp; Op.DBG_SHORTCHECK) != 0 &amp;&amp; Debug.shortCheck(m, a, b, Layout.T) != 0) return Op.FATAL;
 * // pubcheck: before a block or fact record is published (M5, M7)
 * if ((m.debug &amp; Op.DBG_PUBCHECK) != 0 &amp;&amp; Debug.pubTerm(m, t) != 0) return Op.FATAL;
 * </pre>
 * A memo check runs a judgment (a nested {@code m.drive}); call it only where a sub-judgment call is
 * legal: the scratch, environment and register words the caller uses lie below {@code xp}, {@code ep},
 * {@code rp}, and no unwind is in progress. {@code poison} needs no hook: it is the reset rule of
 * {@link Machine#reset()}; {@link #stale} is the test a table owner can assert with.
 */
final class Debug {
    private Debug() {}

    private static final String[] MODES = {"memocheck", "shortcheck", "poison", "scopeaudit", "maskaudit", "sharecheck",
        "pubcheck", "trace"};
    private static final String[] SWITCHES = {"gated", "pchk", "expect", "warm", "facts", "tele", "code", "casetrees",
        "probes", "k2", "inert", "m1a", "m1b", "m2a", "m2b", "m3a", "m3b", "m4a", "m4b", "m5a", "m5b", "m7a", "m7b", "t2", "lazyeval", "levels-complete", "eager", "convexpect",
        "skipargs", "congruence"};

    /**
     * Second names of switch bits ({@code Op.SW_MATCH = SW_M5A}, {@code SW_JOINT = SW_M5B}, {@code SW_SAME = SW_M4A},
     * {@code SW_BODIES = SW_M2A}, {@code SW_DIRECT_RULES = SW_M3A}, {@code SW_LAZY_CASES = SW_M7A}):
     * read like the first name, and printed instead of it.
     */
    private static final String[][] SWITCH_ALIASES = {{"match", "m5a"}, {"joint", "m5b"}, {"same", "m4a"},
        {"bodies", "m2a"}, {"directrules", "m3a"}, {"lazycases", "m7a"}};

    /** {@code "memocheck,poison"} -> {@code Op.DBG_*} bits; unknown names are an error (fail closed). */
    static int parseModes(String list) {
        if (list.trim().equals("all")) {
            return (1 << MODES.length) - 1 & ~Op.DBG_TRACE;
        }
        return parse(list, MODES, "debug mode");
    }

    /**
     * {@code "warm,gated"} -> {@code Op.SW_*} bits; unknown names are an error (fail closed). The list is read
     * from left to right, starting from the empty set: a name (or {@code +name}) adds a switch,
     * {@code -name} removes it, {@code none} empties the set, {@code default} adds {@link Op#SW_DEFAULT},
     * {@code all} adds the staging switches of ISA section 11 but {@code k2} (bits 0..10: not {@code t2},
     * {@code lazyeval}, {@code levels-complete}, nor an experiment switch {@code m1a .. m7b}). So
     * {@code ""} and {@code "none"} are v0, {@code "default,probes"} is the default set plus probes, and
     * {@code "default,-warm"} the default set without warm sessions. A switch that needs another one brings it
     * ({@link Op#implied}: {@code skipargs} -> {@code facts}, {@code congruence} -> {@code probes}), whatever the
     * order of the list: {@code "default,skipargs,-facts"} still has {@code facts}.
     */
    static int parseSwitches(String list) {
        int bits = 0;
        int from = 0;
        int n = list.length();
        while (from <= n) {
            int to = list.indexOf(',', from);
            if (to < 0) {
                to = n;
            }
            String name = list.substring(from, to).trim();
            from = to + 1;
            if (name.isEmpty()) {
                continue;
            }
            if (name.equals("none")) {
                bits = 0;
            } else if (name.equals("default")) {
                bits |= Op.SW_DEFAULT;
            } else if (name.equals("all")) {
                bits |= Op.SW_ALL;
            } else if (name.charAt(0) == '-') {
                bits &= ~parse(firstName(name.substring(1)), SWITCHES, "staging switch");
            } else {
                bits |= parse(firstName(name.charAt(0) == '+' ? name.substring(1) : name), SWITCHES, "staging switch");
            }
        }
        return Op.implied(bits);
    }

    private static int parse(String list, String[] names, String what) {
        int bits = 0;
        int from = 0;
        int n = list.length();
        while (from <= n) {
            int to = list.indexOf(',', from);
            if (to < 0) {
                to = n;
            }
            String name = list.substring(from, to).trim();
            from = to + 1;
            if (name.isEmpty()) {
                continue;
            }
            int bit = -1;
            for (int i = 0; i < names.length; i++) {
                if (names[i].equals(name)) {
                    bit = i;
                }
            }
            if (bit < 0) {
                throw new IllegalArgumentException("unknown " + what + " '" + name + "'");
            }
            bits |= 1 << bit;
        }
        return bits;
    }

    static String modeNames(int bits) {
        return names(bits, MODES);
    }

    static String switchNames(int bits) {
        String[] names = SWITCHES.clone();
        for (String[] alias : SWITCH_ALIASES) {
            for (int i = 0; i < names.length; i++) {
                if (names[i].equals(alias[1])) {
                    names[i] = alias[0];
                }
            }
        }
        return names(bits, names);
    }

    /** The first name of a switch that has a second one ({@link #SWITCH_ALIASES}); else the name itself. */
    private static String firstName(String name) {
        String n = name.trim();
        for (String[] alias : SWITCH_ALIASES) {
            if (alias[0].equals(n)) {
                return alias[1];
            }
        }
        return n;
    }

    private static String names(int bits, String[] names) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.length; i++) {
            if ((bits & 1 << i) != 0) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(names[i]);
            }
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------------------------------------------
    // Session state of the debug modes (Machine.dbg; allocated on first use)
    // ---------------------------------------------------------------------------------------------------

    /** Default tick budget of one memo re-computation ({@code -X machine.memocheck.budget}); beyond it the check is inconclusive. */
    static final long MEMO_BUDGET = 1L << 16;
    /** Nodes visited by one scope audit at most (ISA 5.5: "to a small depth"). */
    static final int SCOPE_NODES = 96;

    static final class State {
        /** Check one memo hit out of {@code every} ({@code -X machine.memocheck.every}, default 1 = all). */
        long every = 1;
        /** Tick budget of one re-computation ({@code -X machine.memocheck.budget}). */
        long budget = MEMO_BUDGET;
        long hitsSeen;
        // what a memo re-computation saves and restores
        long fuel;
        long tickBase;
        boolean stats;
        int ssp;
        int xp;
        int ep;
        int rp;
        int er;
        int wp;
        int err;
        int sig;
        int a2;
        int a3;
        // scope audit
        final int[] stack = new int[SCOPE_NODES];
        // mask audit: naive masks by term id; shared ids are kept for the life of the machine, overlay ids
        // until the next reset (the overlay may reissue them)
        final LongMap sharedMasks = new LongMap();
        final LongMap overlayMasks = new LongMap();
        int maskEpoch;
        int[] walk = new int[256];
        // share check: (term, frame) pairs of untabled nodes walked in this declaration
        final LongMap walked = new LongMap();
        int walkedStamp;
    }

    /** Open-addressing map from a non-zero {@code long} key to a {@code long}; grows by doubling; {@code clear} is O(capacity used). */
    static final class LongMap {
        long[] keys = new long[64];
        long[] vals = new long[64];
        int n;

        /** Index of {@code key}'s cell: the cell holds it, or is empty (key 0). */
        int find(long key) {
            int mask = keys.length - 1;
            int i = (int) (key * 0x9E3779B97F4A7C15L >>> 32) & mask;
            while (keys[i] != 0 && keys[i] != key) {
                i = (i + 1) & mask;
            }
            return i;
        }

        void put(long key, long val) {
            int i = find(key);
            if (keys[i] == 0) {
                if ((++n << 1) > keys.length) {
                    grow();
                    i = find(key);
                }
                keys[i] = key;
            }
            vals[i] = val;
        }

        private void grow() {
            long[] k = keys;
            long[] v = vals;
            keys = new long[k.length << 1];
            vals = new long[k.length << 1];
            for (int i = 0; i < k.length; i++) {
                if (k[i] != 0) {
                    int j = find(k[i]);
                    keys[j] = k[i];
                    vals[j] = v[i];
                }
            }
        }

        void clear() {
            if (n != 0) {
                if (keys.length > 4096) {
                    keys = new long[64];
                    vals = new long[64];
                } else {
                    Arrays.fill(keys, 0);
                }
                n = 0;
            }
        }
    }

    static State state(Machine m) {
        State st = m.dbg;
        if (st == null) {
            st = new State();
            if (m.engine != null) {
                st.every = m.engine.memoEvery;
                st.budget = m.engine.memoBudget;
            }
            st.maskEpoch = m.epoch;
            st.walkedStamp = m.declStamp;
            m.dbg = st;
        }
        return st;
    }

    // ---------------------------------------------------------------------------------------------------
    // memocheck (ISA 5.5; nano-lean's memocheck, R04 section 2): every memo hit is recomputed with the
    // entry bypassed and compared
    // ---------------------------------------------------------------------------------------------------

    /**
     * Start of the re-computation of a memo hit. True: the mode is on, no other re-computation is running
     * and the hit is sampled in; the tick budget, the statistics switch and the stack marks are saved and
     * the caller now bypasses its entry, runs the judgment with {@code m.drive(J(m, .., 0))}, restores the
     * entry and calls {@link #memoExit}. False: the hit is used as it is.
     *
     * <p>A re-computation is charged to nobody: its ticks, call events and trace entries are taken back.
     * A run with {@code memocheck} is still not tick-identical to a run without: a re-computation leaves
     * records and memo entries that a later judgment finds (Init: 93,555,840 ticks against 93,573,982),
     * and value ids differ for the same reason.
     */
    static boolean memoEnter(Machine m) {
        if ((m.debug & Op.DBG_MEMOCHECK) == 0 || m.dbgNest != 0) {
            return false;
        }
        State st = state(m);
        if (st.hitsSeen++ % st.every != 0) {
            return false;
        }
        enter(m, st);
        m.memoChecks++;
        return true;
    }

    /** Saves what a nested re-computation must not change and gives it its own tick budget. */
    private static void enter(Machine m, State st) {
        st.fuel = m.fuel;
        st.tickBase = m.tickBase;
        st.stats = m.stats;
        st.ssp = m.ssp;
        st.xp = m.xp;
        st.ep = m.ep;
        st.rp = m.rp;
        st.er = m.ER;
        st.wp = m.wp;
        st.err = m.err;
        st.sig = m.sig;
        st.a2 = m.A2;
        st.a3 = m.A3;
        m.dbgNest = 1;
        m.stats = false;
        long given = Math.min(st.budget, m.fuel);
        m.fuel = given;
        m.tickBase += given - st.fuel;
    }

    /**
     * End of the re-computation started by {@link #memoEnter}: {@code r} is what {@code m.drive} returned.
     *
     * @param op the judgment ({@code Op.*}), {@code a}, {@code b} its key (for the message)
     * @param hit the memoised answer
     * @return {@code hit} if the re-computation agrees or was inconclusive (its budget ran out). For a
     *     judgment that returns a value "agrees" is: the same id; or the same construction up to PEEK
     *     ({@link #sameUpToPeek}: the re-computation meets suspensions and projections whose value has
     *     become known since the entry was written); or, failing both, convertible by {@code CONV} (run
     *     in the same regime; counted in {@code Machine.memoByConv}). FATAL
     *     with {@code E_AUDIT} if it produced another answer, a kernel error or a broken-invariant fault
     *     ({@code E_HOLE}, {@code E_INTERNAL}, ..); FATAL with the fault's own code for a cancellation or a
     *     memory limit
     */
    static int memoExit(Machine m, int op, int a, int b, int hit, int r) {
        State st = m.dbg;
        boolean compared = false;
        if (r < SIG && r != hit && returnsValue(op)) {
            if (sameUpToPeek(m, hit, r)) {
                r = hit; // the same construction once known suspensions and projections are replaced
            } else {
                // The two constructions differ beyond PEEK, typically "a value then, a new suspension now"
                // (the re-computation delays an argument under a frame that was built from values known by
                // now). What a memo must preserve is the value: decide by one more judgment in the same
                // regime, CONV.
                compared = true;
                int c = m.drive(Conv.conv(m, hit, r, 0));
                if (c == Layout.T) {
                    m.memoByConv++;
                    r = hit;
                } else if (c >= SIG) {
                    r = c;
                }
            }
        }
        int code = m.err;
        if (r >= SIG) {
            // the abandoned re-computation restored nothing (H7): cut to the marks
            m.wp = st.wp;
            m.closeBindersAbove(st.ssp);
            m.xp = st.xp;
            m.ep = st.ep;
            m.rp = st.rp;
            m.ER = st.er;
        }
        boolean stacks = m.wp == st.wp && m.ssp == st.ssp && m.xp == st.xp && m.ep == st.ep && m.rp == st.rp
                && m.ER == st.er;
        m.fuel = st.fuel;
        m.tickBase = st.tickBase;
        m.stats = st.stats;
        m.A2 = st.a2;
        m.A3 = st.a3;
        m.dbgNest = 0;
        String what = compared ? "the comparison (CONV) of its value with the re-computed one" : "the re-computation";
        if (r == FATAL) {
            if (code == MachineErrors.E_CANCEL || code == MachineErrors.E_MEM || code == MachineErrors.E_STACK
                    || code == MachineErrors.E_PRELUDE || code == MachineErrors.E_AUDIT) {
                return FATAL; // the run no longer wants or can afford this unit, or a nested audit failed: passed on
            }
            // an invariant seen broken by the re-computation only: the memo holds an answer its own derivation
            // cannot reproduce (or the hook sits where the judgment may not be called)
            m.err = st.err;
            return m.auditFail("memocheck: " + Op.name(op) + "(" + ref(m, a) + (b != 0 ? ", " + ref(m, b) : "")
                    + ") is memoised as " + ref(m, hit) + " but " + what + " faults with " + MachineErrors.name(code));
        }
        m.err = st.err;
        m.sig = st.sig;
        if (r == ABORT) {
            if (code == MachineErrors.E_STEPS) {
                m.memoInconclusive++;
                return hit;
            }
            return m.auditFail("memocheck: " + Op.name(op) + "(" + ref(m, a) + (b != 0 ? ", " + ref(m, b) : "")
                    + ") is memoised as " + ref(m, hit) + " but " + what + " fails with " + MachineErrors.name(code));
        }
        if (!stacks) {
            return m.auditFail("memocheck: the re-computation of " + Op.name(op) + "(" + ref(m, a)
                    + ") returned a result and left a machine stack changed (I-STACKS, I-SCOPE)");
        }
        if (r != hit) {
            return m.auditFail("memocheck: " + Op.name(op) + "(" + ref(m, a) + (b != 0 ? ", " + ref(m, b) : "")
                    + ") is memoised as " + ref(m, hit) + " but the re-computation gives " + ref(m, r)
                    + (compared ? ", which is not convertible with it" : "") + "; first difference " + firstDifference(m, hit, r));
        }
        return hit;
    }

    /**
     * Where two values first differ, for audit messages: a path through heads, spines ({@code .arg[i]}),
     * captured frames ({@code .frame[i]}, {@code .rest}), projections and offsets, then the two sub-values.
     * A suspension whose VAL is known stands for its value (ISA 2.1 PEEK), so a difference that is only
     * "suspension then, value now" is not reported as one. Bounded; never throws.
     */
    static String firstDifference(Machine m, int a, int b) {
        try {
            StringBuilder path = new StringBuilder();
            int[] budget = {4096};
            String d = diff(m, a, b, path, 48, budget);
            return d == null ? "none up to PEEK (the two ids denote the same construction once known suspensions are replaced by their values)" : d;
        } catch (RuntimeException e) {
            return "(not rendered: " + e + ")";
        }
    }

    /**
     * The value a ref stands for once what is known by now is taken into account (ISA 2.1 PEEK): a
     * suspension whose VAL is known is its value; a projection whose struct has become a constructor
     * application is that field (EVAL's projection-of-constructor, ISA 0.2 decision 7). Two constructions
     * of one value made at different times differ exactly by these steps.
     */
    static int peeked(Machine m, int v) {
        for (int i = 0; i < 64 && v > Layout.HOLE && v >= m.vbase && v < m.vp; i++) {
            int[] vh = m.vh;
            int o = v << Layout.REC_SHIFT;
            int kind = Layout.kindOf(vh[o]);
            if (kind == Layout.SUSP) {
                if (vh[o + Layout.M_VAL] == 0) {
                    break;
                }
                v = vh[o + Layout.M_VAL];
            } else if (kind == Layout.PROJ) {
                int s = peeked(m, vh[o + Layout.A]);
                if (s <= Layout.HOLE || s < m.vbase || s >= m.vp) {
                    break;
                }
                int os = s << Layout.REC_SHIFT;
                int w0 = vh[os];
                if (Layout.kindOf(w0) != Layout.NEU || ((Layout.flagsOf(w0) & Layout.FL_HCLASS_MASK) >>> Layout.HCLASS_SHIFT) != Layout.HC_CTOR) {
                    break;
                }
                int head = vh[os + Layout.A];
                if (head <= Layout.HOLE || Layout.kindOf(vh[head << Layout.REC_SHIFT]) != Layout.CONST || m.decls == null) {
                    break;
                }
                int j = m.decls.ctorNumParams(vh[(head << Layout.REC_SHIFT) + Layout.A]) + vh[o + Layout.C];
                int run = vh[os + Layout.B];
                if (j < 0 || j >= Layout.runLen(m.rs[run])) {
                    break;
                }
                v = m.rs[run + Layout.runHeaderWords(m.rs[run]) + j];
            } else {
                break;
            }
        }
        return v;
    }

    /**
     * True if the two refs denote the same value up to PEEK ({@link #peeked}), compared through heads,
     * spines and captured frames; beyond the bound of the comparison the answer is true (no alarm).
     */
    static boolean sameUpToPeek(Machine m, int a, int b) {
        if (a == b) {
            return true;
        }
        try {
            return diff(m, a, b, new StringBuilder(), 48, new int[] {4096}) == null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * True for the judgments whose result is a value ref (compared up to PEEK, then by CONV); false for
     * those that return a truth value (the whole conversion family, CHECK, ISPROP) or a level id (ENSORT)
     * and for the toy judgments of family EXT: their results are compared exactly.
     */
    private static boolean returnsValue(int op) {
        switch (op >>> 4) {
            case Op.FAM_EV:
            case Op.FAM_RD:
                return true;
            case Op.FAM_TY:
                return op != Op.CHECK && op != Op.CHECK2 && op != Op.ISPROP_F && op != Op.ENSORT_F;
            default:
                return false; // CV: T / F / U / UNDEF; CODE, CTL, EXT: exact
        }
    }

    private static String diff(Machine m, int a, int b, StringBuilder path, int depth, int[] budget) {
        a = peeked(m, a);
        b = peeked(m, b);
        if (a == b) {
            return null;
        }
        if (depth == 0 || budget[0]-- <= 0) {
            return null; // inconclusive: not reported as a difference
        }
        if (a <= Layout.HOLE || b <= Layout.HOLE || a < m.vbase || a >= m.vp || b < m.vbase || b >= m.vp) {
            return "at " + (path.length() == 0 ? "the root" : path) + ": " + ref(m, a) + " against " + ref(m, b);
        }
        int[] vh = m.vh;
        int oa = a << Layout.REC_SHIFT;
        int ob = b << Layout.REC_SHIFT;
        int kind = Layout.kindOf(vh[oa]);
        boolean sameShape = ((vh[oa] ^ vh[ob]) & Hash.W0_KEY_MASK) == 0;
        int mark = path.length();
        String d = null;
        if (sameShape) {
            switch (kind) {
                case Layout.NEU:
                    path.append(".head");
                    d = diff(m, vh[oa + Layout.A], vh[ob + Layout.A], path, depth - 1, budget);
                    path.setLength(mark);
                    if (d == null) {
                        d = diffRuns(m, vh[oa + Layout.B], vh[ob + Layout.B], ".arg", path, depth - 1, budget);
                    }
                    return d;
                case Layout.LAM:
                case Layout.PI:
                case Layout.PII:
                case Layout.SUSP:
                    if (vh[oa + Layout.A] == vh[ob + Layout.A] && vh[oa + Layout.C] == vh[ob + Layout.C]) {
                        return diffRuns(m, vh[oa + Layout.B], vh[ob + Layout.B], ".frame", path, depth - 1, budget);
                    }
                    break;
                case Layout.PROJ:
                    if (vh[oa + Layout.B] == vh[ob + Layout.B] && vh[oa + Layout.C] == vh[ob + Layout.C]) {
                        path.append(".struct");
                        d = diff(m, vh[oa + Layout.A], vh[ob + Layout.A], path, depth - 1, budget);
                        path.setLength(mark);
                        return d;
                    }
                    break;
                case Layout.NSUC:
                case Layout.FVAR:
                    if (vh[oa + Layout.B] == vh[ob + Layout.B]) {
                        path.append(kind == Layout.NSUC ? ".base" : ".type");
                        d = diff(m, vh[oa + Layout.A], vh[ob + Layout.A], path, depth - 1, budget);
                        path.setLength(mark);
                        return d;
                    }
                    break;
                default:
                    break;
            }
        }
        return "at " + (path.length() == 0 ? "the root" : path) + ": " + ref(m, a) + " against " + ref(m, b);
    }

    private static String diffRuns(Machine m, int ra, int rb, String label, StringBuilder path, int depth, int[] budget) {
        if (ra == rb) {
            return null;
        }
        int mark = path.length();
        if (ra == Layout.RUN_EMPTY || rb == Layout.RUN_EMPTY || ra < m.rbase || ra >= m.rsp || rb < m.rbase || rb >= m.rsp) {
            return "at " + path + label + ": run " + ra + " against run " + rb;
        }
        int[] rs = m.rs;
        int ha = rs[ra];
        int hb = rs[rb];
        int n = Layout.runLen(ha);
        if (n != Layout.runLen(hb) || ((ha ^ hb) & Layout.RUN_FAR) != 0) {
            return "at " + path + label + ": " + Show.runHeader(m, ra) + " against " + Show.runHeader(m, rb);
        }
        int base = Layout.runHeaderWords(ha);
        for (int i = 0; i < n; i++) {
            path.append(label).append('[').append(i).append(']');
            String d = diff(m, rs[ra + base + i], rs[rb + base + i], path, depth, budget);
            path.setLength(mark);
            if (d != null) {
                return d;
            }
        }
        if ((ha & Layout.RUN_FAR) != 0) {
            path.append(".rest");
            String d = diffRuns(m, rs[ra + Layout.RUN_REST_WORD], rs[rb + Layout.RUN_REST_WORD], label, path, depth, budget);
            path.setLength(mark);
            return d;
        }
        return null;
    }

    /**
     * The one-line form of the memo check for the memos whose place is fixed by {@code Layout}:
     *
     * <pre>
     * op          key a, b                 entry bypassed                       re-computed by
     * Op.FORCE    SUSP record              M_VAL                                Eval.force(a)
     * Op.WHNF     CONST / NEU / PROJ / NSUC  M_WHNF                             Reduce.whnf(a)
     * Op.CORE     value, mode (FULL/CHEAP) M_CORE and the flag COREFULL         Reduce.core(a, b)
     * Op.DELTA    CONST / NEU / PROJ       M_UNF                                Reduce.delta(a)
     * Op.VTYPE    CONST / NEU / PROJ / LAM M_TYPE                               Infer.vtype(a)
     * Op.DOM      LAM / PI / PII           M_DOM                                Eval.dom(a)
     * Op.CONV     the pair (a, b)          status bits POS and NEG of its entry Conv.conv(a, b)
     * Op.ISPROP_F type value               flag PROPT                           Infer.isprop(a)
     * </pre>
     * {@code hit} is the memoised answer (a value ref; T / F for CONV and ISPROP). A judgment whose memo
     * has another shape (EVAL / INFER / CHECK on a SUSP record with its stamp, the COD and SLVL words)
     * uses {@link #memoEnter} / {@link #memoExit}.
     *
     * @return {@code hit}, or FATAL
     */
    static int memoCheck(Machine m, int op, int a, int b, int hit) {
        if (!memoEnter(m)) {
            return hit;
        }
        int o = a << Layout.REC_SHIFT;
        int r;
        switch (op) {
            case Op.FORCE: {
                m.vh[o + Layout.M_VAL] = 0;
                r = m.drive(Eval.force(m, a, 0));
                m.vh[o + Layout.M_VAL] = hit;
                break;
            }
            case Op.WHNF: {
                m.vh[o + Layout.M_WHNF] = 0;
                r = m.drive(Reduce.whnf(m, a, 0));
                m.vh[o + Layout.M_WHNF] = hit;
                break;
            }
            case Op.CORE: {
                int w0 = m.vh[o];
                m.vh[o + Layout.M_CORE] = 0;
                m.vh[o] = w0 & ~Layout.W0_COREFULL;
                r = m.drive(Reduce.core(m, a, b, 0));
                m.vh[o + Layout.M_CORE] = hit;
                m.vh[o] = m.vh[o] & ~Layout.W0_COREFULL | w0 & Layout.W0_COREFULL;
                break;
            }
            case Op.DELTA: {
                m.vh[o + Layout.M_UNF] = 0;
                r = m.drive(Reduce.delta(m, a, 0));
                m.vh[o + Layout.M_UNF] = hit;
                break;
            }
            case Op.VTYPE: {
                m.vh[o + Layout.M_TYPE] = 0;
                r = m.drive(Infer.vtype(m, a, 0));
                m.vh[o + Layout.M_TYPE] = hit;
                break;
            }
            case Op.DOM: {
                m.vh[o + Layout.M_DOM] = 0;
                r = m.drive(Eval.dom(m, a, 0));
                m.vh[o + Layout.M_DOM] = hit;
                break;
            }
            case Op.CONV: {
                int e = pairEntry(m, a, b);
                int saved = e < 0 ? 0 : m.pair[e + Layout.PAIR_STATUS];
                if (e >= 0) {
                    m.pair[e + Layout.PAIR_STATUS] = saved & ~(Layout.POS | Layout.NEG);
                }
                r = m.drive(Conv.conv(m, a, b, 0));
                e = pairEntry(m, a, b); // the table may have grown
                if (e >= 0) {
                    m.pair[e + Layout.PAIR_STATUS] |= saved;
                }
                break;
            }
            case Op.ISPROP_F: {
                int w0 = m.vh[o];
                m.vh[o] = w0 & ~(Layout.FL_PROPT_MASK << Layout.FLAG_SHIFT);
                r = m.drive(Infer.isprop(m, a, 0));
                m.vh[o] |= w0 & (Layout.FL_PROPT_MASK << Layout.FLAG_SHIFT);
                break;
            }
            default:
                r = m.fault(MachineErrors.E_INTERNAL);
                break;
        }
        return memoExit(m, op, a, b, hit, r);
    }

    /** Word offset in {@code m.pair} of the entry of the unordered pair (a, b), or -1 (the frozen probe of Layout / Hash). */
    static int pairEntry(Machine m, int a, int b) {
        int lo = a;
        int hi = b;
        if (Integer.compareUnsigned(lo, hi) > 0) {
            lo = b;
            hi = a;
        }
        int[] t = m.pair;
        int mask = m.pairMask;
        int slot = Hash.pair(lo, hi) & mask;
        for (;;) {
            int o = slot << Layout.PAIR_SHIFT;
            int l = t[o + Layout.PAIR_LO];
            if (l == 0) {
                return -1;
            }
            if (l == lo && t[o + Layout.PAIR_HI] == hi) {
                return o;
            }
            slot = (slot + 1) & mask;
        }
    }

    // ---------------------------------------------------------------------------------------------------
    // shortcheck (ISA 5.5, 6.3): a shortcut's answer is re-derived by the complete procedure
    // ---------------------------------------------------------------------------------------------------

    /**
     * A shortcut answered {@code answer} (T) for CONV(a, b): the complete procedure (rule 3 of ISA 6.3:
     * every staging switch off, the pair's own entry bypassed) must agree. Inconclusive when its budget
     * runs out.
     *
     * @return 0, or FATAL ({@code E_AUDIT}; or the fatal fault of the re-derivation)
     */
    static int shortCheck(Machine m, int a, int b, int answer) {
        if ((m.debug & Op.DBG_SHORTCHECK) == 0 || m.dbgNest != 0) {
            return 0;
        }
        enter(m, state(m));
        m.audits++;
        int sw = m.sw;
        m.sw = 0;
        int e = pairEntry(m, a, b);
        int saved = e < 0 ? 0 : m.pair[e + Layout.PAIR_STATUS];
        if (e >= 0) {
            m.pair[e + Layout.PAIR_STATUS] = 0;
        }
        int r = m.drive(Conv.conv(m, a, b, 0));
        e = pairEntry(m, a, b);
        if (e >= 0) {
            m.pair[e + Layout.PAIR_STATUS] |= saved;
        }
        m.sw = sw;
        r = memoExit(m, Op.CONV, a, b, answer, r);
        return r >= SIG ? r : 0;
    }

    // ---------------------------------------------------------------------------------------------------
    // poison (ISA 5.5): the ids of a session are shifted at every reset (Machine.reset)
    // ---------------------------------------------------------------------------------------------------

    /**
     * True if {@code v} cannot be a value of the current session: a record ref outside
     * {@code [m.vbase, m.vp)} (NONE, HOLE and immediates are never stale), or a record of kind 0. With
     * {@code poison} on, every ref that survived a reset is stale in this sense.
     */
    static boolean stale(Machine m, int v) {
        if (v < 0 || v == Layout.NONE || v == Layout.HOLE) {
            return false;
        }
        return v < m.vbase || v >= m.vp || Layout.kindOf(m.vh[v << Layout.REC_SHIFT]) == Layout.K_SLOT;
    }

    /** True if {@code run} cannot be a run of the current session ({@code Layout.RUN_EMPTY} never is stale). */
    static boolean staleRun(Machine m, int run) {
        return run != Layout.RUN_EMPTY && (run < m.rbase || run >= m.rsp);
    }

    // ---------------------------------------------------------------------------------------------------
    // scopeaudit (ISA 2.8 I-FV3, 5.5): every FVAR reachable from a value that a judgment reads is INSCOPE
    // ---------------------------------------------------------------------------------------------------

    /**
     * Audits the value {@code v} read through a variable (GET): every FVAR reachable from it through
     * heads, spines, captured frames, projections, offsets and variable types, to at most
     * {@link #SCOPE_NODES} records, is bound by an open binder; every ref on the way is a record of the
     * current session.
     *
     * @return 0, or FATAL ({@code E_AUDIT})
     */
    static int scopeAudit(Machine m, int v) {
        m.audits++;
        State st = state(m);
        int[] stack = st.stack;
        int n = 0;
        int budget = SCOPE_NODES;
        stack[n++] = v;
        while (n > 0 && budget-- > 0) {
            int x = stack[--n];
            if (x < 0 || x == Layout.NONE || x == Layout.HOLE) {
                continue;
            }
            if (x < m.vbase || x >= m.vp) {
                return m.auditFail("scopeaudit: ref " + x + " reached from " + ref(m, v)
                        + " is not a record of this session [" + m.vbase + ", " + m.vp + ")");
            }
            int[] vh = m.vh;
            int o = x << Layout.REC_SHIFT;
            int w0 = vh[o];
            switch (Layout.kindOf(w0)) {
                case Layout.FVAR:
                    if ((w0 & Layout.W0_INSCOPE) == 0) {
                        return m.auditFail("scopeaudit: " + ref(m, x) + " reached from " + ref(m, v)
                                + " is not bound by an open binder (I-FV3); " + m.ssp + " binders are open");
                    }
                    n = push(stack, n, vh[o + Layout.A]);
                    break;
                case Layout.NEU:
                    n = push(stack, n, vh[o + Layout.A]);
                    n = pushRun(m, stack, n, vh[o + Layout.B]);
                    break;
                case Layout.LAM:
                case Layout.PI:
                case Layout.PII:
                case Layout.SUSP:
                    n = pushRun(m, stack, n, vh[o + Layout.B]);
                    break;
                case Layout.PROJ:
                case Layout.NSUC:
                    n = push(stack, n, vh[o + Layout.A]);
                    break;
                case Layout.K_SLOT:
                    return m.auditFail("scopeaudit: record " + x + " reached from " + ref(m, v) + " has kind 0 (a stale ref)");
                default:
                    break;
            }
        }
        return 0;
    }

    private static int push(int[] stack, int n, int v) {
        if (n < stack.length && v > Layout.HOLE) {
            stack[n++] = v;
        }
        return n;
    }

    private static int pushRun(Machine m, int[] stack, int n, int run) {
        while (run != Layout.RUN_EMPTY && run >= m.rbase && run < m.rsp) {
            int[] rs = m.rs;
            int hdr = rs[run];
            int h = Layout.runHeaderWords(hdr);
            int len = Layout.runLen(hdr);
            for (int i = 0; i < len && n < stack.length; i++) {
                n = push(stack, n, rs[run + h + i]);
            }
            run = (hdr & Layout.RUN_FAR) != 0 ? rs[run + Layout.RUN_REST_WORD] : Layout.RUN_EMPTY;
        }
        return n;
    }

    // ---------------------------------------------------------------------------------------------------
    // maskaudit (ISA 2.4 "mask contract", 5.5): the node mask is a superset of the indices the term reads
    // ---------------------------------------------------------------------------------------------------

    /** Makes a term id a non-zero map key. */
    private static final long EXPANDED = 1L << 40;

    /**
     * Re-derives the free-variable mask of term {@code t} by a naive traversal (memoised per node:
     * shared nodes for the life of the machine, overlay nodes until the next reset) and requires the
     * stored mask {@code Terms.fvMask(t)} to cover it: bit i for a free index i &lt; 63, bit 63 for an index
     * &gt;= 63. The traversal is iterative (a 913,500-deep nest must not recurse).
     *
     * @return 0, or FATAL ({@code E_AUDIT})
     */
    static int maskAudit(Machine m, int t) {
        m.audits++;
        State st = state(m);
        if (st.maskEpoch != m.epoch) {
            st.maskEpoch = m.epoch;
            st.overlayMasks.clear();
        }
        Terms terms = m.terms;
        long naive = naiveMask(st, terms, t);
        long stored = terms.fvMask(t);
        if ((naive & ~stored) != 0) {
            long missing = naive & ~stored;
            return m.auditFail("maskaudit: term " + t + " (kind " + terms.kind(t) + ") reads de Bruijn index "
                    + (missing < 0 && (missing & Long.MAX_VALUE) == 0 ? ">= 63" : Long.toString(Long.numberOfTrailingZeros(missing)))
                    + " which its mask 0x" + Long.toHexString(stored) + " lacks (naive mask 0x" + Long.toHexString(naive) + ")");
        }
        return 0;
    }

    /** The exact set of free indices of {@code root} as a mask (bit 63 = some index >= 63), never a superset. */
    static long naiveMask(State st, Terms terms, int root) {
        if (known(st, root)) {
            return maskOf(st, root);
        }
        int[] walk = st.walk;
        int n = 0;
        // explicit post-order: the top entry is computed when every child is known, else its unknown
        // children are pushed above it
        walk[n++] = root;
        while (n > 0) {
            int t = walk[n - 1];
            if (known(st, t)) {
                n--;
                continue;
            }
            int kind = terms.kind(t);
            int c1 = 0;
            int c2 = 0;
            int c3 = 0;
            switch (kind) {
                case Kind.APP:
                case Kind.LAM:
                case Kind.PI:
                    c1 = terms.a(t);
                    c2 = terms.b(t);
                    break;
                case Kind.LET:
                    c1 = terms.a(t);
                    c2 = terms.b(t);
                    c3 = terms.c(t);
                    break;
                case Kind.PROJ:
                    c1 = terms.a(t);
                    break;
                default:
                    break;
            }
            boolean k1 = c1 == 0 || known(st, c1);
            boolean k2 = c2 == 0 || known(st, c2);
            boolean k3 = c3 == 0 || known(st, c3);
            if (!(k1 && k2 && k3)) {
                if (n + 3 > walk.length) {
                    walk = st.walk = Arrays.copyOf(walk, walk.length << 1);
                }
                if (!k3) {
                    walk[n++] = c3;
                }
                if (!k2) {
                    walk[n++] = c2;
                }
                if (!k1) {
                    walk[n++] = c1;
                }
                continue;
            }
            long m1 = c1 == 0 ? 0 : maskOf(st, c1);
            long m2 = c2 == 0 ? 0 : maskOf(st, c2);
            long m3 = c3 == 0 ? 0 : maskOf(st, c3);
            long mask;
            switch (kind) {
                case Kind.BVAR: {
                    int i = terms.a(t);
                    mask = i >= 0 && i < Layout.NEAR_SLOTS ? 1L << i : Layout.MASK_FAR;
                    break;
                }
                case Kind.APP:
                    mask = m1 | m2;
                    break;
                case Kind.LAM:
                case Kind.PI:
                    mask = m1 | under(terms, c2, m2);
                    break;
                case Kind.LET:
                    mask = m1 | m2 | under(terms, c3, m3);
                    break;
                case Kind.PROJ:
                    mask = m1;
                    break;
                default:
                    mask = 0;
                    break;
            }
            (Node.isOverlay(t) ? st.overlayMasks : st.sharedMasks).put(t | EXPANDED, mask);
            n--;
        }
        return maskOf(st, root);
    }

    /**
     * The mask of a binder body seen from outside the binder: index i + 1 becomes i, index 0 is bound.
     * Bit 63 of the body ("some index >= 63") is resolved with the exact {@code looseRange}: when the
     * largest loose index is 63 it becomes index 62 and nothing far remains; otherwise the far bit stays
     * and bit 62 is left as the near bits give it (never an over-approximation).
     */
    private static long under(Terms terms, int body, long mask) {
        long near = (mask & ~Layout.MASK_FAR) >>> 1;
        if ((mask & Layout.MASK_FAR) == 0) {
            return near;
        }
        return terms.looseRange(body) == Layout.NEAR_SLOTS + 1 ? near | 1L << (Layout.NEAR_SLOTS - 1) : near | Layout.MASK_FAR;
    }

    private static boolean known(State st, int t) {
        LongMap map = Node.isOverlay(t) ? st.overlayMasks : st.sharedMasks;
        return map.keys[map.find(t | EXPANDED)] != 0;
    }

    private static long maskOf(State st, int t) {
        LongMap map = Node.isOverlay(t) ? st.overlayMasks : st.sharedMasks;
        return map.vals[map.find(t | EXPANDED)];
    }

    // ---------------------------------------------------------------------------------------------------
    // sharecheck (ISA 3.1, 5.5): a walk must not visit an untabled node twice under one frame
    // ---------------------------------------------------------------------------------------------------

    /**
     * Records that the generic walk entered the untabled node {@code term} under the pruned frame
     * {@code frame} (a run id) in this declaration; the second visit of the same pair is the failure:
     * the tabling gate (SHARED / BODY / INVARIANT, stage 1) lost a sharing that it promised.
     *
     * @return 0, or FATAL ({@code E_AUDIT})
     */
    static int shareCheck(Machine m, int term, int frame) {
        if (m.dbgNest != 0) {
            return 0;
        }
        m.audits++;
        State st = state(m);
        if (st.walkedStamp != m.declStamp) {
            st.walkedStamp = m.declStamp;
            st.walked.clear();
        }
        long key = (long) term << 32 | (frame & 0xFFFFFFFFL) | 1L << 63;
        int i = st.walked.find(key);
        if (st.walked.keys[i] != 0) {
            return m.auditFail("sharecheck: the untabled term " + term + " was walked twice under frame run " + frame);
        }
        st.walked.put(key, 1);
        return 0;
    }

    // ---------------------------------------------------------------------------------------------------
    // pubcheck (ISA 3.3, 5.5): the publication rule on what a block or a fact record contains
    // ---------------------------------------------------------------------------------------------------

    /** A published term id must be an export or generated id (never an overlay id, never 0). 0 or FATAL. */
    static int pubTerm(Machine m, int term) {
        m.audits++;
        if (term <= 0 || Node.isOverlay(term)) {
            return m.auditFail("pubcheck: term id " + term + " is not an export or generated id (publication rule, ISA 3.3)");
        }
        return 0;
    }

    /** A published level id must be a base id of {@code rlvm-level} (derived levels are stored structurally). 0 or FATAL. */
    static int pubLevel(Machine m, int level) {
        m.audits++;
        if (m.levels instanceof LevelBinding) {
            int base = ((LevelBinding) m.levels).store().baseSize();
            if (level < 0 || level >= base) {
                return m.auditFail("pubcheck: level id " + level + " is not a base level id (< " + base + "; ISA 3.3)");
            }
        }
        return 0;
    }

    /** A published level-list id must be a base list id. 0 or FATAL. */
    static int pubList(Machine m, int list) {
        m.audits++;
        if (m.levels instanceof LevelBinding) {
            int base = ((LevelBinding) m.levels).store().baseListCount();
            if (list < 0 || list >= base) {
                return m.auditFail("pubcheck: level-list id " + list + " is not a base list id (< " + base + "; ISA 3.3)");
            }
        }
        return 0;
    }

    /** A published constant id must be a row of the declaration table. 0 or FATAL. */
    static int pubConst(Machine m, int cid) {
        m.audits++;
        if (cid < 0 || m.decls == null || cid >= m.decls.constantCount()) {
            return m.auditFail("pubcheck: constant id " + cid + " is not a declaration row (ISA 3.3)");
        }
        return 0;
    }

    /** A published word must never be a session value ref or run id: the caller names the kind of every word it publishes; this is the catch-all. */
    static int pubNoValue(Machine m, String what) {
        m.audits++;
        return m.auditFail("pubcheck: " + what + " would publish a session-local id (ISA 3.3)");
    }

    // ---------------------------------------------------------------------------------------------------

    /** Short rendering of a ref for audit messages (never throws). */
    static String ref(Machine m, int v) {
        if (v < 0) {
            return "#" + Layout.immValue(v);
        }
        if (v == Layout.NONE) {
            return "NONE/F";
        }
        if (v == Layout.HOLE) {
            return "HOLE/T";
        }
        if (v < m.vbase || v >= m.vp) {
            return "?" + v;
        }
        try {
            String s = Show.value(m, v);
            return s.length() > 120 ? "@" + v + ":" + s.substring(0, 117) + "..." : "@" + v + ":" + s;
        } catch (RuntimeException e) {
            return "@" + v;
        }
    }
}
