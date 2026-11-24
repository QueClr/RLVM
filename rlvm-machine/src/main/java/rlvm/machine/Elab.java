package rlvm.machine;

import rlvm.api.MachineErrors;
import rlvm.store.DeclKind;
import rlvm.store.DeclTable;
import rlvm.store.Kind;
import rlvm.store.Node;
import rlvm.store.Terms;

/**
 * The K1 elaborator (ISA 3.4): single pass, total on any closed term. Owner: M7.
 *
 * <p>A role of a constant is compiled from its source term (type, value or rule right-hand side) into a
 * block of the layout of ISA 3.3. The output is a function of the store alone: no value, no session
 * level, no fact is consulted, the term is not assumed to be well typed, and two workers that elaborate
 * the same role produce equal blocks (ISA 3.3 "either index may win"; the only store-dependent words are
 * the ids of sub-blocks, which the node hint makes converge). A role that cannot be compiled (a far
 * frame, more than 255 window words, an unresolved constant, a term in the generated region or in an
 * overlay, a nest deeper than {@link #MAX_DEPTH}) stays at the generic tier: {@link #elaborate} returns 0.
 *
 * <h2>The compilation scheme (ISA 3.4 "emit", mirrored on EVAL / ARGV of 4.2 so that both tiers build the
 * same values, decision 6)</h2>
 * <pre>
 * position EVAL (the value of the node is needed now: block result, head, struct of a projection)
 *   variable            its operand (A slot, C slot or register)
 *   sort, const, lit    KSORT / KCONST / KLIT (a small numeral is an immediate operand)
 *   closed, non-trivial KVAL (pool entry: the term itself; EVAL once per session and ls, then the inline cache)
 *   lam, pi             MKLAM / MKPI over the capture list of the node's mask; the body becomes a sub-block
 *   app, constant head  operands of the arguments in position ARG, KCONST, MKAPP                    (inert)
 *   app, lambda head    static beta: the arguments become the operands of the binders (HOLE if unread)
 *   app, other head     APPLY (TAILAPPLY in tail position); an argument that has to be built is preceded
 *                       by BUNREAD
 *   let                 the value in position ARG (as the generic EVAL binds it), then the body
 *   proj                MKPROJ over the struct in position EVAL
 * position ARG (the node is an argument: ARGV)
 *   atoms, lam, pi, app with constant head: as above
 *   app, variable head  BKIND on the head value: neutral: MKAPP now; else MKSUSP of the node
 *   everything else     MKSUSP of the node (closed: KVAL in ARGV mode)
 * </pre>
 */
final class Elab {
    private Elab() {}

    /** Operand value of "no operand": the role stays at the generic tier (mode 15 is never a real mode). */
    static final int BAD = 0xF000_0000;
    /** Result of a tail emission: the terminal instruction has been written. */
    static final int DONE = 0xE000_0000;
    static final int HOLE_OP = Op.MODE_V << Op.OPND_SHIFT | Layout.HOLE;
    static final int NONE_OP = Op.MODE_V << Op.OPND_SHIFT | Layout.NONE;

    /** Host recursion bound of the elaborator (nesting of open sub-terms). Deterministic: deeper terms stay generic. */
    static final int MAX_DEPTH = 96;
    /** Largest block the elaborator makes, in words. */
    static final int MAX_WORDS = 1 << 14;
    /** Largest arity (leading binders) of an elaborated role. */
    static final int MAX_ARITY = 160;
    /** A binder body larger than this many nodes gets no sub-block (ISA 3.4). */
    static final int SUB_NODES = 64;
    /** Nesting bound of sub-blocks. */
    static final int MAX_SUB_NEST = 8;

    private static final int POS_ARG = 0;
    private static final int POS_EVAL = 1;
    private static final int POS_TAIL = 2;

    private static final int PAY = Op.OPND_PAYLOAD_MASK;

    /** The working buffers of one session's elaborator (allocated at the first elaboration, reused). */
    static final class State {
        int[] buf = new int[1024];
        /** Next free word of {@link #buf}. */
        int n;
        /** First word of the block under construction. */
        int base;
        /** Operand of each binder level of the block under construction: {@code env[envBase + level]}. */
        int[] env = new int[128];
        int envBase;
        int depth;
        int arity;
        int nextReg;
        int maxReg;
        /** label -> pc relative to {@link #base}; -1 = not bound yet. */
        int[] lab = new int[32];
        int labN;
        /** Pairs (index in buf of a label operand, label). */
        int[] fix = new int[64];
        int fixN;
        /** Spines and operand lists under construction. */
        int[] tmp = new int[128];
        int tn;
        int insns;
        int classJ;
        int rec;
        int subNest;
        int treeDepth;
        int treeLevels;
        boolean bad;
        /** ISA section 12, open question 1: APPLY instead of BKIND / MKSUSP for an active argument (changes value ids). */
        boolean strictArgs;
        /** No sub-blocks for inner binders (their closures are walked). */
        boolean noSubBlocks;
    }

    // ---------------------------------------------------------------------------------------------------
    // Entry point
    // ---------------------------------------------------------------------------------------------------

    /**
     * Elaborates role {@code role} ({@code Op.ROLE_*}) of constant {@code row} into a block (ISA 3.4) and publishes it
     * (3.3: export or generated source terms only; overlay-sourced roles are never elaborated).
     * @return the block id, 0 if the role stays at the generic tier, or FATAL ({@code E_AUDIT}: the block
     *     broke the publication rule; only tested in debug mode {@code pubcheck})
     */
    static int elaborate(Machine m, int row, int role) {
        if (m.engine == null || m.strict || row <= 0 || row > m.decls.constantCount()) {
            return 0;
        }
        CodeStore cs = m.engine.code;
        int b = cs.blockOf(row, role);
        if (b != 0) {
            return b;
        }
        if (role == Op.ROLE_CONG) {
            return Cong.planOf(m, row);
        }
        State s = m.x7.elab();
        begin(s);
        int len = build(m, s, row, role);
        if (len == 0 || s.bad) {
            m.x7.stayedGeneric[role]++;
            return 0;
        }
        if ((m.debug & Op.DBG_PUBCHECK) != 0 && Code.pubcheckSlow(m, s.buf, 0, len) != 0) {
            return m.fault(MachineErrors.E_AUDIT);
        }
        m.x7.elaborated[role]++;
        return cs.publish(row, role, s.buf, len);
    }

    /**
     * Compiles a role into {@code s.buf[0 .. len)} without publishing it (tests, the listing printer).
     * Sub-blocks and rule blocks are published as they are made.
     *
     * @return the length in words, 0 if the role stays at the generic tier
     */
    static int build(Machine m, State s, int row, int role) {
        DeclTable decls = m.decls;
        int kind = decls.kind(row);
        int len = 0;
        switch (role) {
            case Op.ROLE_BODY:
                if ((kind == DeclKind.DEFN || kind == DeclKind.THM) && sourceOk(decls.value(row))
                        && (decls.flags(row) & DeclTable.CF_BLOCK_VALUE) == 0) {
                    len = lambdas(m, s, row, Op.ROLE_BODY, decls.value(row));
                }
                break;
            case Op.ROLE_RULE:
                if (kind == DeclKind.REC) {
                    len = ruleBlock(m, s, row);
                }
                break;
            case Op.ROLE_TELE:
                if (sourceOk(decls.type(row))) {
                    len = tele(m, s, row, decls.type(row));
                }
                break;
            case Op.ROLE_RULES:
                if (kind == DeclKind.DEFN && sourceOk(decls.value(row)) && (decls.flags(row) & DeclTable.CF_BLOCK_VALUE) == 0) {
                    len = caseTree(m, s, row, decls.value(row));
                }
                break;
            default:
                break;
        }
        return s.bad ? 0 : len;
    }

    /** A source term of the export region (ISA 3.3): positive, no overlay bit, not in the generated region. */
    static boolean sourceOk(int t) {
        return t > 0 && t < Node.GEN_BASE;
    }

    static void begin(State s) {
        s.n = 0;
        s.base = 0;
        s.envBase = 0;
        s.depth = 0;
        s.arity = 0;
        s.nextReg = 0;
        s.maxReg = 0;
        s.labN = 0;
        s.fixN = 0;
        s.tn = 0;
        s.insns = 0;
        s.classJ = 0;
        s.rec = 0;
        s.subNest = 0;
        s.bad = false;
    }

    // ---------------------------------------------------------------------------------------------------
    // Emission primitives
    // ---------------------------------------------------------------------------------------------------

    static int opR(int r) {
        return Op.MODE_R << Op.OPND_SHIFT | r;
    }

    static int opA(int i) {
        return Op.MODE_A << Op.OPND_SHIFT | i;
    }

    static int opI(int n) {
        return Op.MODE_I << Op.OPND_SHIFT | n;
    }

    private static boolean isReg(int o) {
        return o >>> Op.OPND_SHIFT == Op.MODE_R;
    }

    private static void room(State s, int n) {
        if (s.n + n > s.buf.length) {
            s.buf = java.util.Arrays.copyOf(s.buf, Math.max(s.buf.length << 1, s.n + n));
        }
    }

    /** Writes an instruction word; the operands follow with {@link #put}. */
    static void ins(State s, int op, int d, int nops, int x) {
        if (s.n - s.base + 1 + nops > MAX_WORDS || nops > 255 || d < 0 || d > 255) {
            s.bad = true;
        }
        if (s.bad) {
            return;
        }
        room(s, 1 + nops);
        s.buf[s.n++] = Op.insn(op, d, nops, x);
        s.insns++;
    }

    static void put(State s, int operand) {
        if (operand == BAD || operand == DONE) {
            s.bad = true;
        }
        if (s.bad) {
            return;
        }
        room(s, 1);
        s.buf[s.n++] = operand;
    }

    private static int typed(State s, int mode, int payload) {
        if (payload < 0 || payload > PAY) {
            s.bad = true;
            return BAD;
        }
        return mode << Op.OPND_SHIFT | payload;
    }

    /** A fresh register (window index). */
    static int reg(State s) {
        int r = s.nextReg++;
        if (r > 254) {
            s.bad = true;
            return 0;
        }
        if (s.nextReg > s.maxReg) {
            s.maxReg = s.nextReg;
        }
        return r;
    }

    static int label(State s) {
        if (s.labN == s.lab.length) {
            s.lab = java.util.Arrays.copyOf(s.lab, s.labN << 1);
        }
        s.lab[s.labN] = -1;
        return s.labN++;
    }

    static void bind(State s, int l) {
        s.lab[l] = s.n - s.base;
    }

    /** A label operand, resolved when the block is finished. */
    static void putLabel(State s, int l) {
        if (s.bad) {
            return;
        }
        if (s.fixN + 2 > s.fix.length) {
            s.fix = java.util.Arrays.copyOf(s.fix, s.fixN << 1);
        }
        s.fix[s.fixN++] = s.n;
        s.fix[s.fixN++] = l;
        put(s, Op.MODE_B << Op.OPND_SHIFT);
    }

    private static void push(State s, int operand) {
        int i = s.envBase + s.depth;
        if (i >= s.env.length) {
            s.env = java.util.Arrays.copyOf(s.env, i << 1);
        }
        s.env[i] = operand;
        s.depth++;
    }

    /** The operand of de Bruijn index {@code i}; an unread or unbound variable makes the role generic. */
    private static int var(State s, int i) {
        int lvl = s.depth - 1 - i;
        if (i < 0 || lvl < 0) {
            s.bad = true;
            return BAD;
        }
        int o = s.env[s.envBase + lvl];
        if (o == HOLE_OP || o == BAD) {
            s.bad = true;
            return BAD;
        }
        return o;
    }

    /**
     * Starts a block at the top of the buffer: header and {@code nentry} entry words are reserved.
     * The caller saved what it needs of the enclosing block ({@link #subBlock}).
     */
    private static void open(State s, int arity, int nentry, int firstReg) {
        s.base = s.n;
        room(s, Op.BLK_ENTRIES + nentry);
        for (int i = 0; i < Op.BLK_ENTRIES + nentry; i++) {
            s.buf[s.n++] = 0;
        }
        s.arity = arity;
        s.nextReg = firstReg;
        s.maxReg = firstReg;
        s.insns = 0;
        s.classJ = 0;
    }

    /** Entry {@code e} of the block under construction starts here. */
    private static void entry(State s, int e) {
        s.buf[s.base + Op.BLK_ENTRIES + e] = s.n - s.base;
    }

    /**
     * Completes the block under construction: labels resolved from fixup {@code fix0} on, header written.
     *
     * @return its length, 0 if the role stays generic
     */
    private static int close(State s, int role, int row, int flags, int nentry, int hdr3, int fix0) {
        if (s.bad) {
            return 0;
        }
        int len = s.n - s.base;
        for (int i = fix0; i < s.fixN; i += 2) {
            int pc = s.lab[s.fix[i + 1]];
            if (pc < 0 || pc > len) {
                s.bad = true;
                return 0;
            }
            s.buf[s.fix[i]] = Op.MODE_B << Op.OPND_SHIFT | pc;
        }
        s.fixN = fix0;
        int nreg = s.maxReg - s.arity;
        if (len > MAX_WORDS || s.maxReg > 255 || nreg < 0) {
            s.bad = true;
            return 0;
        }
        if (s.classJ == 0 && s.insns <= 8) {
            flags |= Op.BLKF_LEAF;
        }
        s.buf[s.base + Op.BLK_HDR0] = Code.hdr0(len, nreg, role);
        s.buf[s.base + Op.BLK_ROW] = row;
        s.buf[s.base + Op.BLK_HDR2] = Code.hdr2(s.arity, flags, nentry);
        s.buf[s.base + Op.BLK_FACTS] = hdr3;
        return len;
    }

    // ---------------------------------------------------------------------------------------------------
    // BODY and RULE (ISA 3.2, 3.4)
    // ---------------------------------------------------------------------------------------------------

    /**
     * BODY(c): the leading lambdas of the value are the arguments A0.. (arity m >= 1), the innermost body
     * is compiled in tail position; one entry. A value without a leading lambda is not elaborated: DELTA
     * memoises its value per CONST record (ISA 4.3.2 {@code UNF(head)}).
     */
    private static int lambdas(Machine m, State s, int row, int role, int t) {
        Terms terms = m.terms;
        int arity = 0;
        int body = t;
        while (terms.kind(body) == Kind.LAM && arity < MAX_ARITY) {
            body = terms.b(body);
            arity++;
        }
        if (arity == 0 || terms.kind(body) == Kind.LAM || !terms.isClosed(t)) {
            return 0;
        }
        int fix0 = s.fixN;
        open(s, arity, 1, arity);
        s.envBase = 0;
        s.depth = 0;
        for (int i = 0; i < arity; i++) {
            push(s, opA(i));
        }
        entry(s, 0);
        tail(m, s, body);
        return close(s, role, row, shapeOf(m, body, arity) << Code.BLKF_SHAPE_SHIFT, 1, 0, fix0);
    }

    /** The LEAF shapes of ISA 3.5: PICK (a parameter), PROJARG (a projection of a parameter), CTORBODY. */
    private static int shapeOf(Machine m, int body, int arity) {
        Terms terms = m.terms;
        switch (terms.kind(body)) {
            case Kind.BVAR:
                return Layout.SHAPE_PICK;
            case Kind.PROJ:
                return terms.kind(terms.a(body)) == Kind.BVAR ? Layout.SHAPE_PROJARG : Layout.SHAPE_NONE;
            case Kind.APP: {
                int h = terms.head(body);
                return terms.kind(h) == Kind.CONST && terms.a(h) > 0 && m.decls.kind(terms.a(h)) == DeclKind.CTOR
                        ? Layout.SHAPE_CTORBODY : Layout.SHAPE_NONE;
            }
            default:
                return Layout.SHAPE_NONE;
        }
    }

    /**
     * Role RULE of a recursor: one block with an entry per rule, in rule order (ISA 3.2 {@code RULE(r, j)}).
     * Rule j takes {@code params + motives + minors + fields(j)} arguments (the leading lambdas of its
     * regenerated right-hand side); the block's arity is the largest of them, so that one window serves
     * every entry. An entry word of 0 means "this rule is walked" ({@code Code.hasEntry}).
     */
    private static int ruleBlock(Machine m, State s, int row) {
        DeclTable decls = m.decls;
        Terms terms = m.terms;
        int rules = decls.recRuleCount(row);
        int fixed = decls.recNumParams(row) + decls.recNumMotives(row) + decls.recNumMinors(row);
        int max = 0;
        for (int j = 0; j < rules; j++) {
            max = Math.max(max, fixed + decls.recRuleFields(row, j));
        }
        if (rules == 0 || rules > 255 || max == 0 || max > MAX_ARITY) {
            return 0;
        }
        int fix0 = s.fixN;
        open(s, max, rules, max);
        int made = 0;
        for (int j = 0; j < rules; j++) {
            int rhs = decls.recRuleRhs(row, j);
            int want = fixed + decls.recRuleFields(row, j);
            int body = rhs;
            int arity = 0;
            while (arity < want && sourceOk(body) && terms.kind(body) == Kind.LAM) {
                body = terms.b(body);
                arity++;
            }
            if (arity != want || !sourceOk(rhs) || !terms.isClosed(rhs)) {
                continue;
            }
            int n0 = s.n;
            int fixN = s.fixN;
            int labN = s.labN;
            int maxReg = s.maxReg;
            int insns = s.insns;
            int classJ = s.classJ;
            s.envBase = 0;
            s.depth = 0;
            s.nextReg = max;
            s.rec = 0;
            for (int i = 0; i < arity; i++) {
                push(s, opA(i));
            }
            tail(m, s, body);
            if (s.bad) {
                // this rule stays generic; the others keep their code
                s.bad = false;
                s.n = n0;
                s.fixN = fixN;
                s.labN = labN;
                s.maxReg = maxReg;
                s.insns = insns;
                s.classJ = classJ;
                continue;
            }
            s.buf[s.base + Op.BLK_ENTRIES + j] = n0 - s.base;
            made++;
        }
        if (made == 0) {
            return 0;
        }
        s.classJ++; // not a LEAF: the flag describes single-entry blocks
        return close(s, Op.ROLE_RULE, row, 0, rules, 0, fix0);
    }

    /** pubcheck of a block that is added to the directory without going through {@link #elaborate}. */
    private static boolean checked(Machine m, State s, int len) {
        return (m.debug & Op.DBG_PUBCHECK) == 0 || Code.pubcheckSlow(m, s.buf, s.base, len) == 0;
    }

    private static void tmpRoom(State s, int n) {
        if (s.tn + n > s.tmp.length) {
            s.tmp = java.util.Arrays.copyOf(s.tmp, Math.max(s.tmp.length << 1, s.tn + n));
        }
    }

    // ---------------------------------------------------------------------------------------------------
    // TELE (ISA 3.2, 3.4, 4.5.4)
    // ---------------------------------------------------------------------------------------------------

    /**
     * TELE(c): one TARG group per syntactic Pi of the type; the telescope variables g_i are the arguments
     * A_i; entry 0 is the first TARG, entry k (0 &lt; k &lt; n) returns the remaining Pi after k arguments
     * (partial application), entry n is the result code.
     * <pre>
     * TARG.INFER i, Lbind, Lnext        [MATCH Tt, T(A_i), i, Lbind]   domain code   TARG.CONV i, D
     * Lbind: TARG.BIND i                Lnext:
     * </pre>
     * The domain is built in position EVAL, as {@code DOM} of the generic walk does (ISA 4.4.3).
     */
    private static int tele(Machine m, State s, int row, int type) {
        Terms terms = m.terms;
        int n = 0;
        int t = type;
        while (terms.kind(t) == Kind.PI && n < MAX_ARITY) {
            t = terms.b(t);
            n++;
        }
        if (n == 0 || terms.kind(t) == Kind.PI || !terms.isClosed(type)) {
            return 0;
        }
        int fix0 = s.fixN;
        open(s, n, n + 1, n + Code.TELE_SITE);
        s.envBase = 0;
        s.depth = 0;
        int rT = opR(n + Code.S_T);
        int tb = s.tn;
        tmpRoom(s, n);
        s.tn += n;
        entry(s, 0);
        t = type;
        for (int i = 0; i < n; i++) {
            s.tmp[tb + i] = t;
            int dom = terms.a(t);
            int body = terms.b(t);
            int read = (terms.fvMask(body) & 1) != 0 ? Code.X_READ : 0;
            int lBind = label(s);
            int lNext = label(s);
            ins(s, Op.TARG, i, 2, Code.PH_INFER | read);
            putLabel(s, lBind);
            putLabel(s, lNext);
            if (Match.isPattern(m, dom)) {
                ins(s, Op.MATCH, 0, 4, 0);
                put(s, rT);
                put(s, typed(s, Op.MODE_T, dom));
                put(s, opI(i));
                putLabel(s, lBind);
            }
            int d = eval(m, s, dom);
            ins(s, Op.TARG, i, 1, Code.PH_CONV);
            put(s, d);
            bind(s, lBind);
            ins(s, Op.TARG, i, 0, Code.PH_BIND | read);
            bind(s, lNext);
            push(s, opA(i));
            t = body;
        }
        entry(s, n);
        if (Match.isPattern(m, t)) {
            ins(s, Op.TARG, 0, 2, Code.PH_EXPECT);
            put(s, typed(s, Op.MODE_T, t));
            put(s, opI(n));
        }
        int r = eval(m, s, t);
        ins(s, Op.TRET, 0, 1, 0);
        put(s, r);
        for (int k = 1; k < n; k++) {
            s.depth = 0; // a sub-block made in between reused the levels above the last entry's depth
            for (int i = 0; i < k; i++) {
                push(s, opA(i));
            }
            entry(s, k);
            // The Pi that remains after a partial application gets no sub-block: its INST is rare, and a block
            // per telescope suffix would triple the code of a TELE (measured on Init.Prelude, NOTES-M7).
            boolean noSub = s.noSubBlocks;
            s.noSubBlocks = true;
            int c = closure(m, s, s.tmp[tb + k], Kind.PI);
            s.noSubBlocks = noSub;
            ins(s, Op.TRET, 0, 1, 0);
            put(s, c);
        }
        s.tn = tb;
        s.classJ++;
        return close(s, Op.ROLE_TELE, row, 0, n + 1, 0, fix0);
    }

    // ---------------------------------------------------------------------------------------------------
    // Terms in the three positions
    // ---------------------------------------------------------------------------------------------------

    /** Tail position: the code ends with RET / TAILAPPLY. */
    static void tail(Machine m, State s, int t) {
        int o = term(m, s, t, POS_TAIL);
        if (o != DONE) {
            ins(s, Op.RET, 0, 1, 0);
            put(s, o);
        }
    }

    /** Position EVAL: an operand that holds {@code EVAL(t)}. */
    static int eval(Machine m, State s, int t) {
        return term(m, s, t, POS_EVAL);
    }

    /** Position ARG: an operand that holds {@code ARGV(t)}. */
    static int arg(Machine m, State s, int t) {
        return term(m, s, t, POS_ARG);
    }

    private static int term(Machine m, State s, int t, int pos) {
        if (s.bad || ++s.rec > MAX_DEPTH || !sourceOk(t)) {
            s.bad = true;
            return BAD;
        }
        Terms terms = m.terms;
        int k = terms.kind(t);
        int o;
        switch (k) {
            case Kind.BVAR:
                o = var(s, terms.a(t));
                break;
            case Kind.SORT:
            case Kind.CONST:
            case Kind.NATLIT:
            case Kind.STRLIT:
                o = atom(m, s, t, k);
                break;
            case Kind.LAM:
            case Kind.PI:
                o = closure(m, s, t, k);
                break;
            case Kind.APP:
                o = terms.isClosed(t) ? kval(m, s, t, pos) : app(m, s, t, pos);
                break;
            case Kind.LET:
                o = terms.isClosed(t) ? kval(m, s, t, pos) : pos == POS_ARG ? susp(m, s, t, false) : let(m, s, t, pos);
                break;
            case Kind.PROJ:
                o = terms.isClosed(t) ? kval(m, s, t, pos) : pos == POS_ARG ? susp(m, s, t, false) : proj(m, s, t);
                break;
            default:
                s.bad = true;
                o = BAD;
                break;
        }
        s.rec--;
        return o;
    }

    /** KSORT / KCONST / KLIT; a numeral below 2^28 is an immediate operand (ISA 2.1: always an immediate value). */
    private static int atom(Machine m, State s, int t, int k) {
        Terms terms = m.terms;
        int r;
        switch (k) {
            case Kind.SORT: {
                int l = terms.a(t);
                r = reg(s);
                ins(s, Op.KSORT, r, 1, m.levels.hasParam(l) ? Code.X_LPARAM : 0);
                put(s, typed(s, Op.MODE_L, l));
                return opR(r);
            }
            case Kind.CONST: {
                int row = terms.a(t);
                if (row <= 0) {
                    s.bad = true; // an unresolved constant: the structural checks never pass such a root
                    return BAD;
                }
                r = reg(s);
                ins(s, Op.KCONST, r, 2, terms.hasLevelParam(t) ? Code.X_LPARAM : 0);
                put(s, typed(s, Op.MODE_D, row));
                put(s, typed(s, Op.MODE_LL, terms.b(t)));
                return opR(r);
            }
            case Kind.NATLIT:
                if (!terms.isNatBig(t)) {
                    long v = terms.natSmall(t);
                    if (v >= 0 && v <= PAY) {
                        return opI((int) v);
                    }
                }
                // fall through
            default:
                r = reg(s);
                ins(s, Op.KLIT, r, 1, 0);
                put(s, typed(s, Op.MODE_T, t));
                return opR(r);
        }
    }

    /** KVAL d, K(t): a closed non-trivial term (ISA 3.4 "pool entry"); ARGV mode in an argument position. */
    private static int kval(Machine m, State s, int t, int pos) {
        int r = reg(s);
        ins(s, Op.KVAL, r, 1, (pos == POS_ARG ? Code.KV_ARGV : 0) | (m.terms.hasLevelParam(t) ? Code.KV_LPARAM : 0));
        put(s, typed(s, Op.MODE_K, t));
        return opR(r);
    }

    /** Writes the capture list of a node: for each index up to the highest bit of its mask the variable's operand, else HOLE. */
    private static void captures(State s, long mask, int len) {
        for (int j = 0; j < len; j++) {
            int idx = len - 1 - j;
            put(s, (mask >>> idx & 1) != 0 ? var(s, idx) : HOLE_OP);
        }
    }

    /** MKLAM / MKPI d, T, B, captures (ISA 3.4). A far frame (mask bit 63) keeps the role generic. */
    private static int closure(Machine m, State s, int t, int kind) {
        long mask = m.terms.fvMask(t);
        if (mask < 0) {
            s.bad = true;
            return BAD;
        }
        int len = 64 - Long.numberOfLeadingZeros(mask);
        int blk = subBlock(m, s, t, kind, mask, len);
        int r = reg(s);
        ins(s, kind == Kind.LAM ? Op.MKLAM : Op.MKPI, r, 2 + len, m.terms.hasLevelParam(t) ? Code.X_LPARAM : 0);
        put(s, typed(s, Op.MODE_T, t));
        put(s, typed(s, Op.MODE_B, blk));
        captures(s, mask, len);
        return opR(r);
    }

    /** MKSUSP d, T, captures (ISA 3.4): a delayed active argument (x bit LET: a LET suspension; not emitted, NOTES-M7 D17). */
    private static int susp(Machine m, State s, int t, boolean let) {
        int r = reg(s);
        suspInto(m, s, t, r, let);
        return opR(r);
    }

    private static void suspInto(Machine m, State s, int t, int r, boolean let) {
        long mask = m.terms.fvMask(t);
        if (mask < 0) {
            s.bad = true;
            return;
        }
        int len = 64 - Long.numberOfLeadingZeros(mask);
        ins(s, Op.MKSUSP, r, 1 + len, (m.terms.hasLevelParam(t) ? Code.X_LPARAM : 0) | (let ? Code.X_LET : 0));
        put(s, typed(s, Op.MODE_T, t));
        captures(s, mask, len);
    }

    /**
     * let chain (ISA 4.2 EVAL "let"; official zeta of {@code whnf_core}: {@code instantiate(body, value)}, R02 7.1):
     * each bound value is what an argument of a beta step is, ARGV of the value - an atom, an inert application
     * built at once, or a plain suspension of an active term - as {@code Eval.letFrom} binds it (NOTES-M2 D9: the
     * LET suspension of ISA 2.8 is made by INFER only, where a let variable is a free variable; NOTES-M7 D17).
     * A let variable the body does not read gets HOLE (B6). The body continues in the same position.
     */
    private static int let(Machine m, State s, int t, int pos) {
        Terms terms = m.terms;
        int depth = s.depth;
        while (terms.kind(t) == Kind.LET && !s.bad) {
            int body = terms.c(t);
            push(s, (terms.fvMask(body) & 1) != 0 ? arg(m, s, terms.b(t)) : HOLE_OP);
            t = body;
        }
        int o = term(m, s, t, pos);
        s.depth = depth;
        return o;
    }

    /** MKPROJ d, S, i, struct (ISA 4.2 EVAL "proj": the struct is evaluated). */
    private static int proj(Machine m, State s, int t) {
        Terms terms = m.terms;
        int row = terms.c(t);
        if (row <= 0) {
            s.bad = true;
            return BAD;
        }
        int so = eval(m, s, terms.a(t));
        int r = reg(s);
        ins(s, Op.MKPROJ, r, 3, 0);
        put(s, typed(s, Op.MODE_D, row));
        put(s, typed(s, Op.MODE_I, terms.b(t)));
        put(s, so);
        return opR(r);
    }

    /** An open application in position {@code pos}. */
    private static int app(Machine m, State s, int t, int pos) {
        Terms terms = m.terms;
        int n = terms.spineLength(t);
        if (n > 250) {
            s.bad = true;
            return BAD;
        }
        int tb = s.tn;
        tmpRoom(s, n);
        s.tn += n;
        int h = terms.spine(t, s.tmp, tb);
        int hk = terms.kind(h);
        int result;
        if (hk == Kind.CONST) {
            for (int i = 0; i < n; i++) {
                s.tmp[tb + i] = arg(m, s, s.tmp[tb + i]);
            }
            int ho = atom(m, s, h, hk);
            int r = reg(s);
            ins(s, Op.MKAPP, r, n + 1, 0);
            put(s, ho);
            for (int i = 0; i < n; i++) {
                put(s, s.tmp[tb + i]);
            }
            result = opR(r);
        } else if (pos == POS_ARG) {
            result = hk == Kind.BVAR ? argApp(m, s, t, h, tb, n) : susp(m, s, t, false);
        } else if (hk == Kind.LAM) {
            result = beta(m, s, h, tb, n, pos);
        } else {
            int fo = hk == Kind.BVAR ? var(s, terms.a(h)) : eval(m, s, h);
            result = applyTo(m, s, fo, tb, 0, n, pos);
        }
        s.tn = tb;
        return result;
    }

    /**
     * An application with a variable head in an argument position (ISA 3.4, 4.2 ARGV): if the head value
     * is neutral at run time the application is inert and is built now, otherwise the node is suspended.
     */
    private static int argApp(Machine m, State s, int t, int h, int tb, int n) {
        int fo = var(s, m.terms.a(h));
        int r = reg(s);
        if (s.strictArgs) {
            for (int i = 0; i < n; i++) {
                s.tmp[tb + i] = arg(m, s, s.tmp[tb + i]);
            }
            ins(s, Op.APPLY, r, n + 1, 0);
            put(s, fo);
            for (int i = 0; i < n; i++) {
                put(s, s.tmp[tb + i]);
            }
            s.classJ++;
            return opR(r);
        }
        int lNeu = label(s);
        int lEnd = label(s);
        ins(s, Op.BKIND, 0, 3, 0);
        put(s, fo);
        put(s, opI(Code.NEUTRAL_KINDS));
        putLabel(s, lNeu);
        suspInto(m, s, t, r, false);
        ins(s, Op.JMP, 0, 1, 0);
        putLabel(s, lEnd);
        bind(s, lNeu);
        for (int i = 0; i < n; i++) {
            s.tmp[tb + i] = arg(m, s, s.tmp[tb + i]);
        }
        ins(s, Op.MKAPP, r, n + 1, 0);
        put(s, fo);
        for (int i = 0; i < n; i++) {
            put(s, s.tmp[tb + i]);
        }
        bind(s, lEnd);
        return opR(r);
    }

    /**
     * Static beta (ISA 3.4 "app, lambda head"): {@code (fun x1..xk => body) a1..an}; the first
     * {@code j = min(k, n)} arguments become the operands of the binders (not built when the body after j
     * binders does not read them, B6), then the body, then the application of the rest.
     */
    private static int beta(Machine m, State s, int h, int tb, int n, int pos) {
        Terms terms = m.terms;
        int j = 0;
        int body = h;
        while (j < n && terms.kind(body) == Kind.LAM) {
            body = terms.b(body);
            j++;
        }
        if (j >= Layout.NEAR_SLOTS) {
            s.bad = true;
            return BAD;
        }
        long mask = terms.fvMask(body);
        for (int i = 0; i < j; i++) {
            s.tmp[tb + i] = (mask >>> (j - 1 - i) & 1) != 0 ? arg(m, s, s.tmp[tb + i]) : HOLE_OP;
        }
        int depth = s.depth;
        for (int i = 0; i < j; i++) {
            push(s, s.tmp[tb + i]);
        }
        int result;
        if (j == n) {
            result = term(m, s, body, pos);
            s.depth = depth;
        } else {
            int fo = eval(m, s, body);
            s.depth = depth;
            result = applyTo(m, s, fo, tb, j, n, pos);
        }
        return result;
    }

    /**
     * APPLY / TAILAPPLY of a run-time callee to the argument terms {@code tmp[tb + from .. tb + n)}
     * (ISA 3.4): an argument that has to be built is preceded by BUNREAD, so that it is not built when
     * the callee does not read it.
     */
    private static int applyTo(Machine m, State s, int fo, int tb, int from, int n, int pos) {
        Terms terms = m.terms;
        int count = n - from;
        for (int i = from; i < n && !s.bad; i++) {
            int a = s.tmp[tb + i];
            int at = s.n;
            int fixN = s.fixN;
            int labN = s.labN;
            int insns = s.insns;
            int lSkip = label(s);
            ins(s, Op.BUNREAD, 0, 4, 0);
            put(s, fo);
            put(s, opI(i - from));
            put(s, opI(count));
            putLabel(s, lSkip);
            int o = arg(m, s, a);
            if (s.bad) {
                break;
            }
            if (isReg(o) && s.n > at + 5) {
                s.buf[at] |= (o & PAY) << 16;
                bind(s, lSkip);
            } else {
                // nothing was built (a variable, a register that already holds the value, an immediate): no BUNREAD
                s.n = at;
                s.fixN = fixN;
                s.labN = labN;
                s.insns = insns;
            }
            s.tmp[tb + i] = o;
        }
        int r = pos == POS_TAIL ? 0 : reg(s);
        ins(s, pos == POS_TAIL ? Op.TAILAPPLY : Op.APPLY, r, count + 1, 0);
        put(s, fo);
        for (int i = from; i < n; i++) {
            put(s, s.tmp[tb + i]);
        }
        s.classJ++;
        return pos == POS_TAIL ? DONE : opR(r);
    }

    // ---------------------------------------------------------------------------------------------------
    // Sub-blocks of inner binders (ISA 3.3 "Finding the block of a closure", 3.4)
    // ---------------------------------------------------------------------------------------------------

    /**
     * The block of binder node {@code t} (an inner lambda chain or one Pi): captured variables are C slots
     * (the node's mask), the k leading binders the arguments; entry j - 1 is "j binders applied" (j &lt; k:
     * the closure of the rest; j = k: the body in tail position). The block is added to the directory
     * and its id is left in the node's c word (a lossy hint). A body of more than {@link #SUB_NODES} nodes
     * gets no block.
     *
     * @return the block id, 0 = none (the closure is walked)
     */
    private static int subBlock(Machine m, State s, int t, int kind, long mask, int len) {
        if (s.noSubBlocks || s.bad || s.subNest >= MAX_SUB_NEST || m.engine == null) {
            return 0;
        }
        Terms terms = m.terms;
        CodeStore cs = m.engine.code;
        int h = terms.c(t);
        if (h != 0) {
            h = cs.hint(t, h);
            if (h != 0) {
                return h;
            }
        }
        int k = 0;
        int body = t;
        if (kind == Kind.PI) {
            body = terms.b(t);
            k = 1;
        } else {
            while (terms.kind(body) == Kind.LAM && k < 16) {
                body = terms.b(body);
                k++;
            }
        }
        if (size(terms, body, SUB_NODES) < 0 || len + k > 200) {
            return 0;
        }
        // save the enclosing block
        int base = s.base;
        int envBase = s.envBase;
        int depth = s.depth;
        int arity = s.arity;
        int nextReg = s.nextReg;
        int maxReg = s.maxReg;
        int insns = s.insns;
        int classJ = s.classJ;
        int rec = s.rec;
        int fix0 = s.fixN;
        int n0 = s.n;
        boolean noSub = s.noSubBlocks;
        s.subNest++;
        s.rec = 0;
        s.envBase = envBase + depth;
        s.depth = 0;
        open(s, k, k, k);
        for (int j = 0; j < len; j++) {
            push(s, (mask >>> (len - 1 - j) & 1) != 0 ? Op.MODE_C << Op.OPND_SHIFT | j : HOLE_OP);
        }
        int inner = t;
        for (int j = 1; j <= k && !s.bad; j++) {
            inner = terms.b(inner);
            push(s, opA(j - 1));
            s.nextReg = k;
            entry(s, j - 1);
            if (j == k) {
                tail(m, s, body);
            } else {
                s.noSubBlocks = true; // the closure of a partial application is walked
                int c = closure(m, s, inner, Kind.LAM);
                s.noSubBlocks = noSub;
                ins(s, Op.RET, 0, 1, 0);
                put(s, c);
            }
        }
        int id = 0;
        int sublen = close(s, Op.ROLE_BODY, 0, Code.BLKF_SUB, k, t, fix0);
        if (sublen != 0 && !s.bad && checked(m, s, sublen)) {
            id = cs.addSub(t, s.buf, s.base, sublen);
        }
        // restore the enclosing block; a sub-block that could not be compiled only loses the hint
        s.bad = false;
        s.n = n0;
        s.fixN = fix0;
        s.base = base;
        s.envBase = envBase;
        s.depth = depth;
        s.arity = arity;
        s.nextReg = nextReg;
        s.maxReg = maxReg;
        s.insns = insns;
        s.classJ = classJ;
        s.rec = rec;
        s.noSubBlocks = noSub;
        s.subNest--;
        return id;
    }

    // ---------------------------------------------------------------------------------------------------
    // RULES: the K1 case trees (ISA 3.6)
    // ---------------------------------------------------------------------------------------------------

    /**
     * RULES(c) for a definition whose value, under its leading lambdas, is a case tree (ISA 3.6):
     * <pre>
     * tree  ::= I.rec params motive minor_1 .. minor_k indices x        no minor reads its induction hypotheses
     *         | D args                                                  D a definition of shape CASESON on I
     * minor ::= fun fields [ihs] =&gt; tree | fun fields [ihs] =&gt; leaf
     * </pre>
     * with {@code I.rec} not K-like, one motive, {@code I} not structure-like (its major could need eta),
     * and {@code x} a parameter or a field of an enclosing arm. Code: {@code WHNF rM, x; SWITCH rM, I, L_1 ..
     * L_k, Lx; L_j: FIELDS ..; arm; Lx: RET NONE}. A leaf is compiled in tail position like a BODY: under
     * the arm's fields it is the value that DELTA, the recursor rule and beta produce (NOTES-M7 D6: any
     * term is accepted as a leaf, a superset of ISA 3.6's {@code p ȳ}, by the same argument).
     * Shape CASESON: one level and every leaf is a parameter applied to the fields; MATCHER otherwise.
     */
    private static int caseTree(Machine m, State s, int row, int value) {
        Terms terms = m.terms;
        int arity = 0;
        int body = value;
        while (terms.kind(body) == Kind.LAM && arity < MAX_ARITY) {
            body = terms.b(body);
            arity++;
        }
        if (arity == 0 || terms.kind(body) != Kind.APP || !terms.isClosed(value)) {
            return 0;
        }
        int fix0 = s.fixN;
        open(s, arity, 1, arity);
        s.envBase = 0;
        s.depth = 0;
        for (int i = 0; i < arity; i++) {
            push(s, opA(i));
        }
        entry(s, 0);
        int lX = label(s);
        s.treeDepth = 0;
        s.treeLevels = 0;
        if (!tree(m, s, body, lX)) {
            s.bad = true;
            return 0;
        }
        bind(s, lX);
        ins(s, Op.RET, 0, 1, 0);
        put(s, NONE_OP);
        int kind = m.decls.kind(terms.a(terms.head(body)));
        int shape = kind == DeclKind.REC && s.treeLevels == 1 ? Layout.SHAPE_CASESON : Layout.SHAPE_MATCHER;
        return close(s, Op.ROLE_RULES, row, shape << Code.BLKF_SHAPE_SHIFT, 1, 0, fix0);
    }

    /** Emits one node of a case tree if {@code t} is one; false (nothing usable emitted) if it is a leaf. */
    private static boolean tree(Machine m, State s, int t, int lX) {
        Terms terms = m.terms;
        DeclTable decls = m.decls;
        if (terms.kind(t) != Kind.APP || s.bad || s.treeDepth >= 3) {
            return false;
        }
        int h = terms.head(t);
        if (terms.kind(h) != Kind.CONST || terms.a(h) <= 0) {
            return false;
        }
        int c = terms.a(h);
        int n = terms.spineLength(t);
        int kind = decls.kind(c);
        int tb = s.tn;
        tmpRoom(s, n + 2 * 255 + 4);
        int rec;
        int majorArg;
        int minor0;
        boolean direct = kind == DeclKind.REC;
        if (direct) {
            rec = c;
            if (!recOk(decls, rec) || n != decls.recMajorIndex(rec) + 1) {
                return false;
            }
            majorArg = n - 1;
            minor0 = decls.recNumParams(rec) + 1;
        } else {
            if (kind != DeclKind.DEFN || !casesOnInfo(m, s, c, tb + n) || n != s.tmp[tb + n + 1]) {
                return false;
            }
            rec = s.tmp[tb + n];
            majorArg = s.tmp[tb + n + 2];
            minor0 = -1;
        }
        int ind = decls.recMajorInduct(rec);
        int k = decls.recRuleCount(rec);
        s.tn = tb + n + 4 + 2 * k;
        terms.spine(t, s.tmp, tb);
        int major = s.tmp[tb + majorArg];
        if (terms.kind(major) != Kind.BVAR) {
            s.tn = tb;
            return false;
        }
        int rM = reg(s);
        ins(s, Op.WHNF, rM, 1, 0);
        put(s, var(s, terms.a(major)));
        s.classJ++;
        int l0 = s.labN;
        for (int j = 0; j < k; j++) {
            label(s);
        }
        ins(s, Op.SWITCH, 0, k + 3, 0);
        put(s, opR(rM));
        put(s, typed(s, Op.MODE_D, ind));
        for (int j = 0; j < k; j++) {
            putLabel(s, l0 + j);
        }
        putLabel(s, lX);
        s.treeDepth++;
        s.treeLevels++;
        for (int j = 0; j < k && !s.bad; j++) {
            int cidx = decls.ctorIndex(decls.recRuleCtor(rec, j));
            int nf = decls.recRuleFields(rec, j);
            int nih = direct ? ruleIhs(m, rec, j) : 0;
            int minor = s.tmp[tb + (direct ? minor0 + cidx : s.tmp[tb + n + 4 + cidx])];
            if (cidx < 0 || cidx >= k || nih < 0) {
                s.bad = true;
                break;
            }
            bind(s, l0 + cidx);
            arm(m, s, minor, nf, nih, rM, lX);
        }
        s.treeDepth--;
        s.tn = tb;
        return !s.bad;
    }

    /** One arm: the minor's {@code nf + nih} binders are the fields (FIELDS) and unread hypotheses; then a tree or a leaf. */
    private static void arm(Machine m, State s, int minor, int nf, int nih, int rM, int lX) {
        Terms terms = m.terms;
        int body = minor;
        for (int i = 0; i < nf + nih; i++) {
            if (terms.kind(body) != Kind.LAM) {
                s.bad = true;
                return;
            }
            body = terms.b(body);
        }
        if (nih > 0 && (nih >= Layout.NEAR_SLOTS || (terms.fvMask(body) & ((1L << nih) - 1)) != 0)) {
            s.bad = true; // a minor that reads an induction hypothesis: not a K1 shape
            return;
        }
        int depth = s.depth;
        if (nf > 200) {
            s.bad = true;
            return;
        }
        if (nf > 0) {
            int r0 = reg(s);
            for (int i = 1; i < nf; i++) {
                reg(s);
            }
            ins(s, Op.FIELDS, r0, 1, nf);
            put(s, opR(rM));
            for (int i = 0; i < nf; i++) {
                push(s, opR(r0 + i));
            }
        }
        for (int i = 0; i < nih; i++) {
            push(s, HOLE_OP);
        }
        int n0 = s.n;
        int insns = s.insns;
        int fixN = s.fixN;
        int labN = s.labN;
        int nextReg = s.nextReg;
        if (!tree(m, s, body, lX)) {
            if (s.bad) {
                return;
            }
            s.n = n0;
            s.insns = insns;
            s.fixN = fixN;
            s.labN = labN;
            s.nextReg = nextReg;
            tail(m, s, body);
        }
        s.depth = depth;
    }

    /** A recursor whose rules a case tree may apply directly (ISA 3.6): not K-like, one motive, not structure-like. */
    private static boolean recOk(DeclTable decls, int rec) {
        int ind = decls.recMajorInduct(rec);
        return (decls.recFlags(rec) & DeclTable.RF_K) == 0 && decls.recNumMotives(rec) == 1 && ind > 0
                && decls.kind(ind) == DeclKind.INDUCT && (decls.inductFlags(ind) & DeclTable.IF_STRUCT_LIKE) == 0
                && decls.recRuleCount(rec) == decls.recNumMinors(rec) && decls.recRuleCount(rec) == decls.inductCtorCount(ind)
                && decls.recRuleCount(rec) > 0 && decls.recRuleCount(rec) <= 250;
    }

    /**
     * The number of induction hypotheses the j-th rule passes to its minor premise: the arguments of the
     * rule's right-hand side body beyond the fields (its body is {@code minor fields ihs}); -1 if the rule
     * does not have that form.
     */
    private static int ruleIhs(Machine m, int rec, int j) {
        Terms terms = m.terms;
        DeclTable decls = m.decls;
        int nf = decls.recRuleFields(rec, j);
        int t = decls.recRuleRhs(rec, j);
        int skip = decls.recNumParams(rec) + decls.recNumMotives(rec) + decls.recNumMinors(rec) + nf;
        for (int i = 0; i < skip; i++) {
            if (!sourceOk(t) || terms.kind(t) != Kind.LAM) {
                return -1;
            }
            t = terms.b(t);
        }
        if (!sourceOk(t) || terms.kind(terms.head(t)) != Kind.BVAR) {
            return -1;
        }
        return terms.spineLength(t) - nf;
    }

    /**
     * Is definition {@code c} a case analysis in the sense of {@code I.casesOn} (ISA 3.6, shape CASESON):
     * {@code fun a_0 .. a_(q-1) => I.rec params motive minor_1 .. minor_k indices a_p}, each minor the
     * eta-expansion {@code fun fields ihs => a_(m_j) fields} of a parameter? Writes
     * {@code tmp[at ..] = rec, q, p, 0, m_1 .. m_k} (m_j indexed by constructor).
     */
    private static boolean casesOnInfo(Machine m, State s, int c, int at) {
        Terms terms = m.terms;
        DeclTable decls = m.decls;
        int v = decls.value(c);
        if (!sourceOk(v) || (decls.flags(c) & DeclTable.CF_BLOCK_VALUE) != 0) {
            return false;
        }
        int q = 0;
        int body = v;
        while (terms.kind(body) == Kind.LAM && q < MAX_ARITY) {
            body = terms.b(body);
            q++;
        }
        int h = terms.head(body);
        if (q == 0 || terms.kind(body) != Kind.APP || terms.kind(h) != Kind.CONST || terms.a(h) <= 0
                || decls.kind(terms.a(h)) != DeclKind.REC) {
            return false;
        }
        int rec = terms.a(h);
        int n = terms.spineLength(body);
        if (!recOk(decls, rec) || n != decls.recMajorIndex(rec) + 1) {
            return false;
        }
        int k = decls.recRuleCount(rec);
        int sb = at + 4 + k;
        tmpRoom(s, 4 + k + n + (sb - s.tn));
        terms.spine(body, s.tmp, sb);
        int major = s.tmp[sb + n - 1];
        if (terms.kind(major) != Kind.BVAR || terms.a(major) >= q) {
            return false;
        }
        s.tmp[at] = rec;
        s.tmp[at + 1] = q;
        s.tmp[at + 2] = q - 1 - terms.a(major);
        s.tmp[at + 3] = 0;
        int minor0 = decls.recNumParams(rec) + 1;
        for (int j = 0; j < k; j++) {
            int cidx = decls.ctorIndex(decls.recRuleCtor(rec, j));
            int nf = decls.recRuleFields(rec, j);
            int nih = ruleIhs(m, rec, j);
            if (cidx < 0 || cidx >= k || nih < 0) {
                return false;
            }
            int t = s.tmp[sb + minor0 + cidx];
            for (int i = 0; i < nf + nih; i++) {
                if (terms.kind(t) != Kind.LAM) {
                    return false;
                }
                t = terms.b(t);
            }
            // t must be (#p f_1 .. f_nf): the parameter applied to exactly the fields, in order
            if (terms.spineLength(t) != nf) {
                return false;
            }
            int cur = t;
            for (int i = nf - 1; i >= 0; i--) {
                int a = terms.b(cur);
                if (terms.kind(a) != Kind.BVAR || terms.a(a) != nih + (nf - 1 - i)) {
                    return false;
                }
                cur = terms.a(cur);
            }
            if (terms.kind(cur) != Kind.BVAR) {
                return false;
            }
            int lvl = q - 1 - (terms.a(cur) - nf - nih);
            if (lvl < 0 || lvl >= q) {
                return false;
            }
            s.tmp[at + 4 + cidx] = lvl;
        }
        return true;
    }

    /**
     * Counts the nodes the elaborator would visit for {@code t}, giving up below zero: closed sub-terms and
     * binder domains count as one node.
     *
     * @return the budget that is left, negative if {@code t} is larger than the budget
     */
    private static int size(Terms terms, int t, int budget) {
        while (budget >= 0) {
            budget--;
            switch (terms.kind(t)) {
                case Kind.APP:
                    if (terms.isClosed(t)) {
                        return budget;
                    }
                    budget = size(terms, terms.b(t), budget);
                    t = terms.a(t);
                    break;
                case Kind.LAM:
                case Kind.PI:
                    t = terms.b(t);
                    break;
                case Kind.LET:
                    budget = size(terms, terms.b(t), budget);
                    t = terms.c(t);
                    break;
                case Kind.PROJ:
                    t = terms.a(t);
                    break;
                default:
                    return budget;
            }
        }
        return budget;
    }
}
