package rlvm.inductive;

import java.util.Arrays;
import rlvm.api.KernelException;
import rlvm.api.KernelOps;
import rlvm.api.LevelOps;
import rlvm.api.Reason;
import rlvm.api.ResourceException;
import rlvm.store.DeclKind;
import rlvm.store.DeclTable;
import rlvm.store.IntLists;
import rlvm.store.Kind;
import rlvm.store.NameTable;
import rlvm.store.Node;
import rlvm.store.Overlay;
import rlvm.store.Store;
import rlvm.store.TermOps;
import rlvm.store.Terms;

/**
 * The official {@code add_inductive} on one block: pre-checks, nested-occurrence elimination, type
 * formers, constructors and positivity, elimination universe, K, motives and minor premises,
 * recursor types and rules, the defensive checks, restoration of nested blocks
 * ({@code docs/research/03-kernel-declarations.md} sections 6.1 - 6.12, checklist items 25 - 48;
 * {@code refs/lean4/src/kernel/inductive.cpp} at v4.36.0, whose function names are quoted in the
 * comments below).
 *
 * <p>One instance per admission thread. Works in the overlay of the given {@link KernelOps}; requires
 * {@link KernelOps#whnfIsSyntactic()} for the three places where the output of {@code whnf} is
 * observable (trap T4), which the reference kernel provides; with an engine that does not, a block
 * in which such a {@code whnf} changes the term is declined.
 *
 * <h2>Stand-ins</h2>
 * The official kernel grows its environment while it works: the block's types are added before the
 * constructors are checked, the constructors before the recursors are built, and for a nested block
 * all of that happens in a temporary environment with auxiliary types that never reach the real one.
 * Here the members of the block in flight are never constants of the engine: every type,
 * constructor and recursor of the (auxiliary) block is represented by a free variable (a "stand-in"
 * atom, {@code KernelOps.mkLocal}) whose type is the member's type, and the terms handed to the
 * engine contain no constant of the unit being admitted. An inductive type, constructor or recursor
 * whose facts are not yet in the environment behaves exactly like an opaque symbol of its type, so
 * every judgment has the official result (README, "Stand-ins"). The generated recursor types and
 * rules are converted to constants at the very end, by renaming atoms.
 *
 * <p>Consequences: {@link KernelOps#exposeUnitMembers} is never called; the engine is asked only
 * about terms over constants of smaller rank; a derived recursor needs the constant id of its
 * exported record to be named in the output (a block whose export lacks a recursor record is
 * declined with {@code UNSUPPORTED_INPUT}: generated constants are deferred).
 *
 * <p>Verified against the official checker: {@code OfficialDifferentialTest} writes fixtures out
 * with the derived recursors and has {@code leanchecker} regenerate and compare them; the
 * integration tests regenerate every inductive block of the exports in {@code work/exports}.
 */
public final class InductiveAdmission {

    private final Store store;
    private final KernelOps ops;

    private Terms terms;
    private Overlay ov;
    private TermOps tops;
    private LevelOps levels;
    private IntLists lists;
    private NameTable names;
    private DeclTable decls;
    private TermTools tools;
    /** A second traversal state for {@link #rebuildTerm}: it runs inside the hooks of {@link #tools}. */
    private TermTools rebuildTools;
    private boolean syntacticWhnf;
    /** Set when a non-syntactic engine's whnf changed a term (see {@link #whnfT}). */
    private boolean whnfChanged;

    // ---- the block --------------------------------------------------------------------------------------
    private BlockInput in;
    private int nparams;
    /** Level list [Param p | p in lparams]. */
    private int lvls;
    /** Own-unit members: cid -> code and name id -> code; code = type index + 1, CODE_CTOR, CODE_REC. */
    private final IntIntMap memberOfCid = new IntIntMap(64);
    private final IntIntMap memberOfName = new IntIntMap(64);
    private static final int CODE_CTOR = -1;
    private static final int CODE_REC = -2;

    // types of the auxiliary block: [0, nUser) user types, [nUser, nTypes) auxiliary types
    private int nUser;
    private int nTypes;
    private int[] tHeader = new int[4];
    private int[] tAtom = new int[4];
    private int[] tCtor0 = new int[4];
    private int[] tCtorN = new int[4];
    private int[] tIdx0 = new int[4];
    private int[] tNumIdx = new int[4];
    private int[] tMajor = new int[4];
    private int[] tMotive = new int[4];
    /** Auxiliary types: the nested application {@code (J.{ls} Ds)[As := P]}, container cid, its levels. */
    private int[] tKey = new int[4];
    private int[] tContCid = new int[4];
    private int[] tContLvls = new int[4];

    // constructors of the auxiliary block, in block order (= order of the minor premises)
    private int nCtors;
    private int[] cType = new int[8];
    /** User constructors: the declared type with the block's types as atoms (before elimination). */
    private int[] cDeclared = new int[8];
    private int[] cAtom = new int[8];
    /** User constructors: own cid. Auxiliary ones: cid of the container's constructor. */
    private int[] cCid = new int[8];
    private int[] cOwner = new int[8];
    /** Index in the BlockInput constructor arrays, or -1. */
    private int[] cInput = new int[8];
    private int[] cField0 = new int[8];
    private int[] cNumFields = new int[8];
    private int[] cResult = new int[8];
    private int[] cIntro = new int[8];
    private int[] cMinor = new int[8];
    private int[] cReduct = new int[8];
    private int[] cRhs = new int[8];

    // pools
    private int[] idxPool = new int[16];
    private int idxTop;
    private int[] fieldPool = new int[16];
    private int fieldTop;
    /** Per field: -1, or the index of the block type the field's type ends in (recursive field). */
    private int[] fieldRec = new int[16];
    private int[] fieldLevel = new int[16];
    private int[] fieldXs0 = new int[16];
    private int[] fieldXsN = new int[16];
    private int[] fieldEnd = new int[16];
    private int[] xsPool = new int[16];
    private int xsTop;

    /** m_params: one local per parameter, from the first type's header. */
    private int[] params = new int[4];
    /** Parameters of {@code elim_nested_inductive_fn} (raw binder domains of the first header). */
    private int[] elimP = new int[4];
    /** Parameters of the constructor / term currently rewritten. */
    private int[] curAs = new int[4];

    private int resultLevel;
    private boolean isNotZero;
    private boolean isRec;
    private boolean isReflexive;
    private boolean elimOnlyAtZero;
    private boolean kTarget;
    private int elimLevel;
    private int elimName;

    // recursors (one per type of the auxiliary block)
    private int[] recTypeAux = new int[4];
    private int[] recAtom = new int[4];
    private int[] recPre = new int[4];
    private int[] recCid = new int[4];
    private int[] recFinalAtom = new int[4];
    private int[] recTypeCheck = new int[4];

    private int[] pre = new int[16];
    private int nPre;
    private int[] tele = new int[32];
    private int[] buf = new int[32];
    private int[] buf2 = new int[32];

    // stand-in lookup: FVAR id -> index + 1
    private final IntIntMap atomType = new IntIntMap(64);
    private final IntIntMap atomCtor = new IntIntMap(64);
    private final IntIntMap atomRec = new IntIntMap(64);

    private final LongSet occSeen = new LongSet(256);
    private int[] occStack = new int[256];

    public InductiveAdmission(Store store, KernelOps ops) {
        if (store == null || ops == null) {
            throw new NullPointerException();
        }
        this.store = store;
        this.ops = ops;
    }

    private static KernelException reject(Reason r) {
        return new KernelException(r);
    }

    /** The cause of the last decline raised by {@link #generate}, or null. */
    private String declineDetail;

    private ResourceException decline(String why) {
        declineDetail = why;
        return new ResourceException(Reason.UNSUPPORTED_INPUT);
    }

    /**
     * Why the last call of {@link #generate} ended with a {@code ResourceException} of this class
     * (null if it did not, or if the exception came from the kernel operations: a budget, the stack).
     */
    public String lastDeclineDetail() {
        return declineDetail;
    }

    private void bind() {
        Terms t = ops.terms();
        if (t != terms || tools == null) {
            terms = t;
            ov = t.overlay();
            tools = new TermTools(t);
            rebuildTools = new TermTools(t);
        }
        tops = ops.termOps();
        levels = ops.levels();
        lists = levels.lists();
        names = store.names();
        decls = store.decls();
        syntacticWhnf = ops.whnfIsSyntactic();
        whnfChanged = false;
    }

    /**
     * Derives the block. Throws {@code KernelException} (INDUCTIVE_INVALID and others) if the block is
     * not admissible and {@code ResourceException} if it cannot be decided.
     *
     * <p>Precondition: the caller opened the scope with {@code ops.begin(in.rank, in.levelParams)}.
     * This method re-opens it (same rank) with the recursors' universe parameters where the official
     * code checks with them, and leaves it in that state.
     *
     * @param in inputs; of the claims in it only the NAMES of the exported recursor records (to find
     *     their constant ids) and the first universe-parameter name of each (to find the name id of the
     *     elimination universe) are read here, both after verification against the derived name
     * @param out receives the derived facts (reused by the caller). {@code out.declineReason} is set
     *     when the block passed every check but cannot be admitted here; {@link RecordCompare#compare}
     *     turns it into a {@code ResourceException} once the exported records are known to be identical
     */
    public void generate(BlockInput in, GeneratedBlock out) {
        this.in = in;
        declineDetail = null;
        out.declineDetail = null;
        bind();
        if (!in.consistent) {
            throw decline("the records of the inductive item do not form one block (inconsistent input)");
        }
        if (in.typeCount <= 0) {
            // "invalid empty (mutual) inductive datatype declaration"
            throw reject(Reason.INDUCTIVE_INVALID);
        }
        if (in.numParams < 0) {
            throw reject(Reason.INDUCTIVE_INVALID);
        }
        nparams = in.numParams;
        nUser = in.typeCount;
        nTypes = 0;
        nCtors = 0;
        idxTop = 0;
        fieldTop = 0;
        xsTop = 0;
        atomType.clear();
        atomCtor.clear();
        atomRec.clear();
        lvlRebuilt.clear();
        lvlTouched.clear();
        rebuild.memo.clear();
        rebuildChanged = false;

        indexMembers();
        buildLevels();
        preChecks();
        checkUniformOccs();
        // Nothing is sized by numParams before the first header is known to have that many binders (an
        // export can claim any number): elim_nested's get_params fails on the first missing one.
        int header = in.typeType[0];
        for (int i = 0; i < nparams; i++) {
            if (terms.kind(header) != Kind.PI) {
                throw reject(Reason.INDUCTIVE_INVALID); // "incorrect number of parameters"
            }
            header = terms.b(header);
        }
        params = ensure(params, nparams);
        elimP = ensure(elimP, nparams);
        curAs = ensure(curAs, nparams);
        translate();
        elimNested();
        checkInductiveTypes();
        computeFlags();
        checkConstructors();
        initElimLevel();
        kTarget = nTypes == 1 && levels.isAlwaysZero(resultLevel) && tCtorN[0] == 1 && cNumFields[tCtor0[0]] == 0;
        mkRecInfos();
        mkRecursors();
        checkRecursors();
        finish(out);
    }

    // =====================================================================================================
    // members, levels
    // =====================================================================================================

    private boolean isSimpleName(int name, String s) {
        return name != 0 && names.prefix(name) == NameTable.ANONYMOUS && !names.isNumeric(name)
                && names.lastComponentIs(name, s);
    }

    private void indexMembers() {
        memberOfCid.clear();
        memberOfName.clear();
        for (int k = 0; k < nUser; k++) {
            int name = in.typeName[k];
            // mk_local_decl("t", I params indices) strips type annotations by constant NAME; with a block
            // type called outParam/optParam/... the official major premise loses its type. Not reproduced.
            if (isSimpleName(name, "outParam") || isSimpleName(name, "semiOutParam") || isSimpleName(name, "optParam")
                    || isSimpleName(name, "autoParam")) {
                throw decline("an inductive type named outParam / semiOutParam / optParam / autoParam: the official"
                        + " kernel strips type annotations by these names, which is not reproduced");
            }
            if (memberOfCid.put(in.typeCid[k], k + 1) != IntIntMap.NONE
                    || memberOfName.put(name, k + 1) != IntIntMap.NONE) {
                throw reject(Reason.DUPLICATE_DECLARATION);
            }
        }
        for (int k = 0; k < nUser; k++) {
            int c0 = in.typeCtorStart[k];
            int cn = in.typeCtorCount[k];
            if (cn < 0 || c0 < 0 || c0 + cn > in.ctorCount) {
                throw decline("constructor ranges of the block input are out of bounds");
            }
            for (int i = c0; i < c0 + cn; i++) {
                // "duplicate constructor name" / check_name in declare_constructors
                if (memberOfCid.put(in.ctorCid[i], CODE_CTOR) != IntIntMap.NONE
                        || memberOfName.put(in.ctorName[i], CODE_CTOR) != IntIntMap.NONE) {
                    throw reject(Reason.DUPLICATE_DECLARATION);
                }
            }
        }
        // Constructor records of the item that no type lists, and the recursor records: members of the
        // unit that the official kernel never has in its environment while it checks the block.
        for (int i = 0; i < in.ctorCount; i++) {
            if (!memberOfCid.contains(in.ctorCid[i])) {
                memberOfCid.put(in.ctorCid[i], CODE_CTOR);
                memberOfName.put(in.ctorName[i], CODE_CTOR);
            }
        }
        for (int r = 0; r < in.recCount; r++) {
            if (!memberOfCid.contains(in.recCid[r])) {
                memberOfCid.put(in.recCid[r], CODE_REC);
                memberOfName.put(in.recName[r], CODE_REC);
            }
        }
    }

    /** Code of the unit member a CONST's {@code a} word / a PROJ's {@code c} word refers to, or 0. */
    private int memberCode(int ref) {
        if (ref > 0) {
            int code = memberOfCid.get(ref);
            if (code == 0 && ref <= decls.constantCount() && decls.unit(ref) == in.unit) {
                code = CODE_CTOR; // a member of the unit that the block does not list
            }
            return code;
        }
        return ref < 0 ? memberOfName.get(~ref) : 0;
    }

    private void buildLevels() {
        int lp = in.levelParams;
        int n = lists.length(lp);
        buf = ensure(buf, n + 1);
        for (int i = 0; i < n; i++) {
            int name = lists.get(lp, i);
            for (int j = 0; j < i; j++) {
                if (lists.get(lp, j) == name) {
                    throw reject(Reason.DUPLICATE_LEVEL_PARAM); // check_duplicated_univ_params
                }
            }
            buf[i] = levels.param(name);
        }
        lvls = lists.intern(buf, 0, n);
    }

    /**
     * The official {@code is_equivalent} / {@code is_geq} (comparison of official normal forms, which
     * is incomplete): admission must reject exactly where the official kernel rejects, so the complete
     * relations of {@link LevelOps} are only the fallback for an implementation without the official ones.
     */
    private boolean levelEquiv(int a, int b) {
        if (a == b) {
            return true;
        }
        try {
            return levels.isEquivOfficial(a, b);
        } catch (UnsupportedOperationException e) {
            return levels.isEquiv(a, b);
        }
    }

    private boolean levelGeq(int a, int b) {
        try {
            return levels.isGeqOfficial(a, b);
        } catch (UnsupportedOperationException e) {
            return levels.isGeq(a, b);
        }
    }

    private boolean sameLevelList(int a, int b) {
        if (a == b) {
            return true;
        }
        int n = lists.length(a);
        if (n != lists.length(b)) {
            return false;
        }
        for (int i = 0; i < n; i++) {
            if (lists.get(a, i) != lists.get(b, i)) {
                return false;
            }
        }
        return true;
    }

    // =====================================================================================================
    // the official rebuild of levels (references the kernel makes itself)
    // =====================================================================================================
    //
    // add_inductive refers to the members of the block with level-parameter objects it creates itself
    // (m_levels = lparams_to_levels(m_lparams); m_ind_cnsts; mk_constant(cnstr_name, m_levels);
    // get_rec_levels(); m_lvls of elim_nested_inductive_fn). The official instantiate (level.cpp:
    // replace_level_fn, update_succ, update_max) keeps a level node only when its operands come back
    // POINTER-equal, and a fresh parameter object is never pointer-equal to the one of the declared
    // type. So the type the official type checker infers for such a reference
    // (infer_constant -> instantiate_type_lparams) is the declared type with every level node above a
    // parameter of the block rebuilt through mk_succ / mk_max / mk_imax: `imax u 1` becomes `max u 1`,
    // which the official is_equivalent does not identify with `imax u 1`, and the block is rejected
    // ("application type mismatch"). A reference written in the export carries the level objects of
    // the declared type and gets the declared type as it is.
    //
    // Here: the stand-in of a member that only the kernel refers to (constructors, recursors, auxiliary
    // types) is a local whose type is the REBUILT type; the stand-in of a user type keeps the declared
    // header for the occurrences in the constructor types, and the one application the kernel builds
    // itself (the major premise `T params indices`) is checked once against a local of the rebuilt
    // header ({@link #checkRecursors}). Every comparison is then the engine's own application check,
    // with the official sides: type of the argument against the binder domain of the rebuilt type.

    /** Rebuilt level per level id of the block in flight. */
    private final IntIntMap lvlRebuilt = new IntIntMap(64);
    /** 2 if the level mentions a universe parameter of the block, else 1. */
    private final IntIntMap lvlTouched = new IntIntMap(64);
    private int[] lvlStack = new int[32];
    private int[] lvlBuf = new int[8];
    /** True once the rebuild changed the spelling of a level of the block in flight. */
    private boolean rebuildChanged;

    /**
     * True if, in the last call of {@link #generate}, the official rebuild gave another spelling to at
     * least one level of a type it is applied to (statistics: such blocks are the only ones for which
     * the checks of this class differ from a kernel that keeps the declared spelling).
     */
    public boolean lastRebuildChanged() {
        return rebuildChanged;
    }

    private boolean isBlockParam(int name) {
        int lp = in.levelParams;
        for (int i = 0, n = lists.length(lp); i < n; i++) {
            if (lists.get(lp, i) == name) {
                return true;
            }
        }
        return false;
    }

    /**
     * The official {@code instantiate(l, lparams, fresh)}: a level that mentions no parameter of the
     * block is kept; a parameter is itself; every other node is rebuilt from its rebuilt operands with
     * {@code mk_succ} / {@code mk_max} / {@code mk_imax}. Iterative (the depth of a level is the
     * export's).
     */
    private int rebuildLevel(int level) {
        int done = lvlRebuilt.get(level);
        if (done != 0) {
            return done;
        }
        int sp = 0;
        lvlStack[sp++] = level;
        while (sp > 0) {
            int l = lvlStack[sp - 1];
            if (lvlRebuilt.contains(l)) {
                sp--;
                continue;
            }
            if (!levels.hasParam(l)) {
                lvlRebuilt.put(l, l);
                lvlTouched.put(l, 1);
                sp--;
                continue;
            }
            int kind = levels.kind(l);
            int r;
            boolean touched;
            if (kind == LevelOps.KIND_PARAM) {
                r = l;
                touched = isBlockParam(levels.paramName(l));
            } else if (kind == LevelOps.KIND_SUCC) {
                int a = levels.succOf(l);
                int ra = lvlRebuilt.get(a);
                if (ra == 0) {
                    sp = lvlPush(sp, a);
                    continue;
                }
                touched = lvlTouched.get(a) == 2;
                r = touched ? levels.succ(ra) : l;
            } else if (kind == LevelOps.KIND_MAX || kind == LevelOps.KIND_IMAX) {
                int a = levels.lhs(l);
                int b = levels.rhs(l);
                int ra = lvlRebuilt.get(a);
                if (ra == 0) {
                    sp = lvlPush(sp, a);
                    continue;
                }
                int rb = lvlRebuilt.get(b);
                if (rb == 0) {
                    sp = lvlPush(sp, b);
                    continue;
                }
                touched = lvlTouched.get(a) == 2 || lvlTouched.get(b) == 2;
                r = !touched ? l : kind == LevelOps.KIND_MAX ? levels.mkMax(ra, rb) : levels.mkIMax(ra, rb);
            } else {
                r = l;
                touched = false;
            }
            if (r != l) {
                rebuildChanged = true;
            }
            lvlRebuilt.put(l, r);
            lvlTouched.put(l, touched ? 2 : 1);
            sp--;
        }
        return lvlRebuilt.get(level);
    }

    private int lvlPush(int sp, int l) {
        if (sp == lvlStack.length) {
            lvlStack = Arrays.copyOf(lvlStack, sp * 2);
        }
        lvlStack[sp] = l;
        return sp + 1;
    }

    /** The official {@code instantiate_lparams(e, lparams, fresh)}: every SORT and CONST level rebuilt. */
    private final TermTools.Rewriter rebuild = new TermTools.Rewriter() {
        @Override
        boolean skip(int header) {
            return (header & Node.F_HAS_LPARAM) == 0;
        }

        @Override
        int sort(int node) {
            int l = terms.a(node);
            int r = rebuildLevel(l);
            return r == l ? 0 : ov.mkSort(r);
        }

        @Override
        int leaf(int node, int kind) {
            if (kind != Kind.CONST) {
                return 0;
            }
            int list = terms.b(node);
            int n = lists.length(list);
            lvlBuf = ensure(lvlBuf, n);
            boolean changed = false;
            for (int i = 0; i < n; i++) {
                int l = lists.get(list, i);
                int r = rebuildLevel(l);
                lvlBuf[i] = r;
                changed |= r != l;
            }
            if (!changed) {
                return 0;
            }
            int cid = terms.a(node);
            if (cid <= 0) {
                throw decline("a reference to an undeclared constant carries a universe level that the official"
                        + " kernel respells when it instantiates the block's own constants: not reproduced");
            }
            return ov.mkConst(cid, lists.intern(lvlBuf, 0, n));
        }
    };

    /**
     * The type the official kernel infers for a reference to a member of the block that it built itself:
     * {@code type} with its levels rebuilt. The same id if no level changes its spelling.
     */
    private int rebuildTerm(int type) {
        if ((terms.header(type) & Node.F_HAS_LPARAM) == 0) {
            return type;
        }
        try {
            return rebuildTools.replace(type, rebuild);
        } catch (UnsupportedOperationException e) {
            // A LevelOps without the structural view cannot say what the official kernel compares.
            throw decline("the level operations at hand cannot rebuild a universe level as the official kernel does"
                    + " when it instantiates the block's own constants (" + e.getMessage() + ")");
        }
    }

    // =====================================================================================================
    // probes and rewriters
    // =====================================================================================================

    private static final int CONSTISH = Node.F_HAS_CONST | Node.F_HAS_PROJ | Node.F_UNRESOLVED;

    /** check_no_nested_aux: a constant or projection structure name under the reserved root "_nested". */
    private final TermTools.Probe nestedPrefix = new TermTools.Probe() {
        @Override
        boolean skip(int header) {
            return (header & CONSTISH) == 0;
        }

        @Override
        boolean hit(int node, int kind) {
            if (kind == Kind.FVAR) {
                return false;
            }
            int ref = kind == Kind.CONST ? terms.a(node) : terms.c(node);
            int name = ref > 0 ? decls.name(ref) : ~ref;
            return ref != 0 && names.hasNestedRoot(name);
        }
    };

    /** A constant or projection structure of the unit being admitted. */
    private final TermTools.Probe ownMember = new TermTools.Probe() {
        @Override
        boolean skip(int header) {
            return (header & CONSTISH) == 0;
        }

        @Override
        boolean hit(int node, int kind) {
            if (kind == Kind.FVAR) {
                return false;
            }
            return memberCode(kind == Kind.CONST ? terms.a(node) : terms.c(node)) != 0;
        }
    };

    /** has_ind_occ: an occurrence of a type of the (auxiliary) block, i.e. of a type atom. */
    private final TermTools.Probe indOcc = new TermTools.Probe() {
        @Override
        boolean skip(int header) {
            return (header & Node.F_HAS_FVAR) == 0;
        }

        @Override
        boolean hit(int node, int kind) {
            return kind == Kind.FVAR && atomType.get(node) != 0;
        }
    };

    private boolean hasIndOcc(int term) {
        return tools.any(term, indOcc);
    }

    /** Declared constructor types: {@code Const(T_k, lvls)} becomes the atom of {@code T_k}. */
    private final TermTools.Rewriter toAtoms = new TermTools.Rewriter() {
        @Override
        boolean skip(int header) {
            return (header & CONSTISH) == 0;
        }

        @Override
        int leaf(int node, int kind) {
            if (kind != Kind.CONST) {
                return 0;
            }
            int code = memberCode(terms.a(node));
            return code > 0 ? tAtom[code - 1] : 0;
        }
    };

    /** replace_if_nested. Subterms without an atom cannot contain a nested occurrence. */
    private final TermTools.Rewriter nested = new TermTools.Rewriter() {
        @Override
        boolean skip(int header) {
            return (header & Node.F_HAS_FVAR) == 0;
        }

        @Override
        int app(int node, int head, int[] args, int n) {
            return replaceIfNested(head, args, n);
        }
    };

    /** restore_nested (the replace inside it). */
    private final TermTools.Rewriter restore = new TermTools.Rewriter() {
        @Override
        boolean skip(int header) {
            return (header & Node.F_HAS_FVAR) == 0;
        }

        @Override
        int app(int node, int head, int[] args, int n) {
            if (terms.kind(head) != Kind.FVAR) {
                return 0;
            }
            return restoreApp(head, args, n);
        }

        @Override
        int leaf(int node, int kind) {
            if (kind != Kind.FVAR) {
                return 0;
            }
            int j = atomRec.get(node) - 1;
            if (j >= nUser) {
                return recFinalAtom[j]; // aux_rec_name_map
            }
            return restoreApp(node, buf, 0);
        }
    };

    /** Renaming of atoms through {@link #renameMap}. */
    private final IntIntMap renameMap = new IntIntMap(64);
    private final TermTools.Rewriter rename = new TermTools.Rewriter() {
        @Override
        boolean skip(int header) {
            return (header & Node.F_HAS_FVAR) == 0;
        }

        @Override
        int leaf(int node, int kind) {
            return kind == Kind.FVAR ? renameMap.get(node) : 0;
        }
    };

    // =====================================================================================================
    // 6.2 pre-checks
    // =====================================================================================================

    private void preChecks() {
        for (int k = 0; k < nUser; k++) {
            if (tools.any(in.typeType[k], nestedPrefix)) {
                throw reject(Reason.INDUCTIVE_INVALID);
            }
            for (int i = in.typeCtorStart[k], e = i + in.typeCtorCount[k]; i < e; i++) {
                if (tools.any(in.ctorType[i], nestedPrefix)) {
                    throw reject(Reason.INDUCTIVE_INVALID);
                }
            }
        }
    }

    /**
     * check_uniform_ind_occs: every occurrence of a type of the block in a constructor type is applied
     * to the block's levels and to the constructor's parameter variables. The official {@code for_each}
     * asks at every application node; for a spine with a block type at its head and more than
     * {@code nparams} arguments it descends until the prefix with exactly {@code nparams} arguments,
     * checks that prefix and visits the remaining arguments. That is what the spine-wise loop does.
     *
     * <p>A node is visited once per binder offset, as officially (the outcome below a binder depends
     * on the offset even for a closed node: {@code fun b => T b} is uniform only where its own binder
     * happens to have the index of a parameter). Nodes without any occurrence are skipped; they are
     * found by one marking pass per block ({@link #markOccurrences}), not through node summaries.
     */
    private void checkUniformOccs() {
        occMark.clear();
        for (int k = 0; k < nUser; k++) {
            for (int i = in.typeCtorStart[k], e = i + in.typeCtorCount[k]; i < e; i++) {
                markOccurrences(in.ctorType[i]);
                uniformOccs(in.ctorType[i]);
            }
        }
    }

    /** Per node of the declared constructor types: 1 = no block type below, 2 = some. */
    private final IntIntMap occMark = new IntIntMap(256);

    private void occPush(int node, int offset) {
        if (occSp + 2 > occStack.length) {
            occStack = Arrays.copyOf(occStack, occStack.length * 2);
        }
        occStack[occSp++] = node;
        occStack[occSp++] = offset;
    }

    private int occSp;

    /** Post-order marking of every node reachable from {@code root} in {@link #occMark}. */
    private void markOccurrences(int root) {
        occSp = 0;
        occPush(root, 0);
        while (occSp > 0) {
            int phase = occStack[--occSp];
            int node = occStack[--occSp];
            int h = terms.header(node);
            int kind = Node.kind(h);
            if (phase == 0) {
                if (occMark.contains(node)) {
                    continue;
                }
                if ((h & CONSTISH) == 0) {
                    occMark.put(node, 1);
                    continue;
                }
                switch (kind) {
                    case Kind.CONST:
                        occMark.put(node, memberCode(terms.a(node)) > 0 ? 2 : 1);
                        break;
                    case Kind.APP:
                    case Kind.LAM:
                    case Kind.PI:
                        occPush(node, 1);
                        occPush(terms.a(node), 0);
                        occPush(terms.b(node), 0);
                        break;
                    case Kind.LET:
                        occPush(node, 1);
                        occPush(terms.a(node), 0);
                        occPush(terms.b(node), 0);
                        occPush(terms.c(node), 0);
                        break;
                    case Kind.PROJ:
                        occPush(node, 1);
                        occPush(terms.a(node), 0);
                        break;
                    default:
                        occMark.put(node, 1);
                        break;
                }
            } else {
                boolean some = occMark.get(terms.a(node)) == 2;
                if (kind != Kind.PROJ) {
                    some |= occMark.get(terms.b(node)) == 2;
                    if (kind == Kind.LET) {
                        some |= occMark.get(terms.c(node)) == 2;
                    }
                }
                occMark.put(node, some ? 2 : 1);
            }
        }
    }

    private void uniformOccs(int root) {
        occSeen.clear();
        occSp = 0;
        occPush(root, 0);
        while (occSp > 0) {
            int offset = occStack[--occSp];
            int node = occStack[--occSp];
            if (occMark.get(node) != 2) {
                continue; // no occurrence below
            }
            switch (terms.kind(node)) {
                case Kind.CONST:
                    // a bare occurrence: zero arguments
                    if (nparams != 0 || !sameLevelList(terms.b(node), lvls)) {
                        throw reject(Reason.INDUCTIVE_INVALID);
                    }
                    break;
                case Kind.APP: {
                    if (!occSeen.add(((long) node << 32) | offset)) {
                        break;
                    }
                    int n = terms.spineLength(node);
                    buf = ensure(buf, n);
                    int head = terms.spine(node, buf, 0);
                    int from = 0;
                    if (terms.kind(head) == Kind.CONST && memberCode(terms.a(head)) > 0) {
                        boolean ok = n >= nparams && offset >= nparams && sameLevelList(terms.b(head), lvls);
                        for (int i = 0; ok && i < nparams; i++) {
                            int a = buf[i];
                            ok = terms.kind(a) == Kind.BVAR && terms.a(a) == offset - 1 - i;
                        }
                        if (!ok) {
                            // "invalid occurrence of datatype ... being declared"
                            throw reject(Reason.INDUCTIVE_INVALID);
                        }
                        from = nparams;
                    } else {
                        occPush(head, offset);
                    }
                    for (int i = from; i < n; i++) {
                        occPush(buf[i], offset);
                    }
                    break;
                }
                case Kind.LAM:
                case Kind.PI:
                    if (!occSeen.add(((long) node << 32) | offset)) {
                        break;
                    }
                    occPush(terms.a(node), offset);
                    occPush(terms.b(node), offset + 1);
                    break;
                case Kind.LET:
                    if (!occSeen.add(((long) node << 32) | offset)) {
                        break;
                    }
                    occPush(terms.a(node), offset);
                    occPush(terms.b(node), offset);
                    occPush(terms.c(node), offset + 1);
                    break;
                case Kind.PROJ:
                    occPush(terms.a(node), offset);
                    break;
                default:
                    break;
            }
        }
    }

    // =====================================================================================================
    // stand-ins for the user types; 6.3 nested occurrences
    // =====================================================================================================

    private int newType(int header) {
        int t = nTypes++;
        if (t == tHeader.length) {
            int n = t * 2;
            tHeader = Arrays.copyOf(tHeader, n);
            tAtom = Arrays.copyOf(tAtom, n);
            tCtor0 = Arrays.copyOf(tCtor0, n);
            tCtorN = Arrays.copyOf(tCtorN, n);
            tIdx0 = Arrays.copyOf(tIdx0, n);
            tNumIdx = Arrays.copyOf(tNumIdx, n);
            tMajor = Arrays.copyOf(tMajor, n);
            tMotive = Arrays.copyOf(tMotive, n);
            tKey = Arrays.copyOf(tKey, n);
            tContCid = Arrays.copyOf(tContCid, n);
            tContLvls = Arrays.copyOf(tContLvls, n);
        }
        tHeader[t] = header;
        // An auxiliary type is only ever referred to as `auxJ.{m_lvls}` (replace_if_nested) and
        // `auxJ.{m_levels}` (m_ind_cnsts): its stand-in has the rebuilt header. A user type is also
        // referred to by the constructor types of the export, with the levels of the declaration:
        // its stand-in keeps the header, and the kernel's own application is checked in checkRecursors.
        int atom = ops.mkLocal(t >= nUser ? rebuildTerm(header) : header);
        tAtom[t] = atom;
        atomType.put(atom, t + 1);
        tCtor0[t] = nCtors;
        tCtorN[t] = 0;
        tKey[t] = 0;
        tContCid[t] = 0;
        tContLvls[t] = 0;
        return t;
    }

    private int newCtor(int owner, int type, int cid, int inputIndex) {
        int c = nCtors++;
        if (c == cType.length) {
            int n = c * 2;
            cType = Arrays.copyOf(cType, n);
            cDeclared = Arrays.copyOf(cDeclared, n);
            cAtom = Arrays.copyOf(cAtom, n);
            cCid = Arrays.copyOf(cCid, n);
            cOwner = Arrays.copyOf(cOwner, n);
            cInput = Arrays.copyOf(cInput, n);
            cField0 = Arrays.copyOf(cField0, n);
            cNumFields = Arrays.copyOf(cNumFields, n);
            cResult = Arrays.copyOf(cResult, n);
            cIntro = Arrays.copyOf(cIntro, n);
            cMinor = Arrays.copyOf(cMinor, n);
            cReduct = Arrays.copyOf(cReduct, n);
            cRhs = Arrays.copyOf(cRhs, n);
        }
        cType[c] = type;
        cDeclared[c] = type;
        cCid[c] = cid;
        cOwner[c] = owner;
        cInput[c] = inputIndex;
        cAtom[c] = 0;
        tCtorN[owner]++;
        return c;
    }

    private void translate() {
        for (int k = 0; k < nUser; k++) {
            newType(in.typeType[k]);
        }
        toAtoms.memo.clear();
        for (int k = 0; k < nUser; k++) {
            tCtor0[k] = nCtors;
            for (int i = in.typeCtorStart[k], e = i + in.typeCtorCount[k]; i < e; i++) {
                newCtor(k, tools.replace(in.ctorType[i], toAtoms), in.ctorCid[i], i);
            }
        }
    }

    /**
     * Peels {@code nparams} leading binders of {@code term} into fresh locals {@code out[0..nparams)}
     * carrying the raw binder domains; returns the instantiated rest, or 0 if a binder is missing.
     *
     * @param lambdaToo accept LAM binders as well (restore_nested)
     */
    private int peelParams(int term, int[] out, boolean lambdaToo) {
        int t = term;
        for (int i = 0; i < nparams; i++) {
            int kind = terms.kind(t);
            if (kind != Kind.PI && !(lambdaToo && kind == Kind.LAM)) {
                return 0;
            }
            out[i] = ops.mkLocal(tops.instantiateRev(terms.a(t), out, 0, i));
            t = terms.b(t);
        }
        return tops.instantiateRev(t, out, 0, nparams);
    }

    /** elim_nested_inductive_fn::operator(). */
    private void elimNested() {
        // get_params(m_new_types[0].get_type(), ...): "incorrect number of parameters"
        if (peelParams(tHeader[0], elimP, false) == 0) {
            throw reject(Reason.INDUCTIVE_INVALID);
        }
        for (int q = 0; q < nTypes; q++) {
            for (int c = tCtor0[q], e = c + tCtorN[q]; c < e; c++) {
                int body = peelParams(cType[c], curAs, false);
                if (body == 0) {
                    throw reject(Reason.INDUCTIVE_INVALID);
                }
                nested.memo.clear();
                int newBody = tools.replace(body, nested);
                if (newBody != body) {
                    cType[c] = tops.mkPiOver(curAs, 0, nparams, newBody);
                }
            }
        }
    }

    /** replace_params: {@code e[As := P]}. */
    private int toKey(int e) {
        return tops.instantiateRev(tops.abstractFVars(e, curAs, 0, nparams), elimP, 0, nparams);
    }

    /** instantiate_pi_params; rejects with "ill-formed declaration". */
    private int instantiatePiParams(int e, int np, int[] args) {
        for (int i = 0; i < np; i++) {
            if (terms.kind(e) != Kind.PI) {
                throw reject(Reason.INDUCTIVE_INVALID);
            }
            e = terms.b(e);
        }
        return tops.instantiateRev(e, args, 0, np);
    }

    /**
     * replace_if_nested on the maximal application {@code head args}. The official function is also
     * asked at every prefix of the spine; a prefix has the same head and the same parameter arguments
     * (or too few arguments), so it answers "none" whenever the maximal application does.
     */
    private int replaceIfNested(int head, int[] args, int n) {
        // is_nested_inductive_app
        if (terms.kind(head) != Kind.CONST) {
            return 0;
        }
        int icid = terms.a(head);
        if (icid <= 0 || memberCode(icid) != 0 || decls.kind(icid) != DeclKind.INDUCT || decls.rank(icid) >= in.rank) {
            return 0; // not an inductive type of the environment
        }
        int np = decls.inductNumParams(icid);
        if (np > n) {
            return 0;
        }
        boolean isNested = false;
        boolean loose = false;
        for (int i = 0; i < np; i++) {
            if (terms.looseRange(args[i]) > 0) {
                loose = true;
            }
            if (!isNested && hasIndOcc(args[i])) {
                isNested = true;
            }
        }
        if (!isNested) {
            return 0;
        }
        if (loose) {
            // "nested inductive datatypes parameters cannot contain local variables"
            throw reject(Reason.INDUCTIVE_INVALID);
        }
        int ilvls = terms.b(head);
        int key = toKey(ov.mkApps(head, args, 0, np));
        for (int t = nUser; t < nTypes; t++) {
            if (tops.eqv(tKey[t], key)) {
                return ov.mkApps(ov.mkApps(tAtom[t], curAs, 0, nparams), args, np, n - np);
            }
        }
        // Copy the whole mutual block of the container. The arguments are saved first: `args` is the
        // traversal's scratch buffer and the loop below runs term operations.
        int[] ds = Arrays.copyOfRange(args, 0, n);
        int nall = decls.inductAllCount(icid);
        int result = 0;
        for (int a = 0; a < nall; a++) {
            int jcid = decls.inductAllAt(icid, a);
            if (lists.length(ilvls) != decls.numLevelParams(jcid)) {
                throw reject(Reason.LEVEL_ARITY_MISMATCH);
            }
            int jlp = decls.levelParams(jcid);
            int jty = tops.instantiateLevelParams(decls.type(jcid), jlp, ilvls);
            jty = instantiatePiParams(jty, np, ds);
            jty = tops.mkPiOver(curAs, 0, nparams, jty);
            int t = newType(jty);
            int jconst = jcid == icid ? head : ov.mkConst(jcid, ilvls);
            tKey[t] = toKey(ov.mkApps(jconst, ds, 0, np));
            tContCid[t] = jcid;
            tContLvls[t] = ilvls;
            if (jcid == icid) {
                result = ov.mkApps(ov.mkApps(tAtom[t], curAs, 0, nparams), ds, np, n - np);
            }
            int nc = decls.inductCtorCount(jcid);
            for (int i = 0; i < nc; i++) {
                int ccid = decls.inductCtor(jcid, i);
                if (lists.length(ilvls) != decls.numLevelParams(ccid)) {
                    throw reject(Reason.LEVEL_ARITY_MISMATCH);
                }
                // The auxiliary constructor is named J_cnstr_name.replace_prefix(J_name, auxJ_name). If
                // J's name is not a prefix of its constructor's name the name stays as it is, and
                // check_name fails in check_constructors: the container's constructor has that name.
                if (!isNamePrefix(decls.name(jcid), decls.name(ccid))) {
                    throw reject(Reason.DUPLICATE_DECLARATION);
                }
                int cty = tops.instantiateLevelParams(decls.type(ccid), decls.levelParams(ccid), ilvls);
                cty = instantiatePiParams(cty, np, ds);
                cty = tops.mkPiOver(curAs, 0, nparams, cty);
                newCtor(t, cty, ccid, -1);
            }
        }
        if (result == 0) {
            throw new IllegalStateException("container type is not in its own `all` list");
        }
        return result;
    }

    private boolean isNamePrefix(int prefix, int name) {
        for (int p = name; p != NameTable.ANONYMOUS; p = names.prefix(p)) {
            if (p == prefix) {
                return true;
            }
        }
        return false;
    }

    /** Constants of the table scanned so far for names under the reserved root "_nested". */
    private int nestedScanned;
    /** True once a constant whose name has the root "_nested" was seen. */
    private boolean nestedNameSeen;

    /**
     * The auxiliary types, constructors and recursors of a nested block are named
     * {@code _nested.<J>_<n>}, {@code _nested.<J>_<n>.<ctor>}, {@code _nested.<J>_<n>.rec} in the
     * official temporary environment, and each name is checked against the environment
     * ({@code check_name}); a declaration may legally be called {@code _nested.X}
     * ({@code bugs/nested-name-capture}), and the official checker replays declarations by dependency,
     * not in file order, so such a declaration may be in its environment whether it comes earlier or
     * later in the file. The auxiliary names are not reproduced here. They cannot clash when no
     * constant of the file and no member of the block has a name under {@code _nested}; otherwise
     * the block cannot be decided.
     *
     * <p>Scans the constants loaded so far (all of them in batch mode).
     */
    private boolean nestedNamesInUse() {
        for (int k = 0; k < nUser; k++) {
            if (names.hasNestedRoot(in.typeName[k])) {
                return true;
            }
        }
        for (int i = 0; i < in.ctorCount; i++) {
            if (names.hasNestedRoot(in.ctorName[i])) {
                return true;
            }
        }
        int n = decls.constantCount();
        for (int cid = nestedScanned + 1; cid <= n && !nestedNameSeen; cid++) {
            nestedNameSeen = names.hasNestedRoot(decls.name(cid));
        }
        nestedScanned = n;
        return nestedNameSeen;
    }

    // =====================================================================================================
    // 6.4 type formers
    // =====================================================================================================

    /**
     * {@code whnf} at a position where its output is observable (trap T4). A non-syntactic engine may
     * return any weak head normal form; if it returns the term unchanged the term is in weak head
     * normal form and the official result is the same term. Otherwise the block is declined when the
     * output would be used (see callers).
     */
    private int whnfT(int t) {
        int r = ops.whnf(t);
        if (!syntacticWhnf && r != t && !tops.eqv(r, t)) {
            whnfChanged = true;
        }
        return r;
    }

    private void pushIdx(int x) {
        if (idxTop == idxPool.length) {
            idxPool = Arrays.copyOf(idxPool, idxTop * 2);
        }
        idxPool[idxTop++] = x;
    }

    /** check_inductive_types. The index locals are kept for mk_rec_infos, which repeats the same walk. */
    private void checkInductiveTypes() {
        for (int k = 0; k < nTypes; k++) {
            int header = tHeader[k];
            // tc().check(type) in an environment without the block: any reference to a member of the
            // block (a constant of the unit, or an atom inside an auxiliary header) is an unknown constant.
            if (k < nUser && tools.any(header, ownMember)) {
                throw reject(Reason.UNKNOWN_CONSTANT);
            }
            if (hasIndOcc(header)) {
                throw reject(Reason.UNKNOWN_CONSTANT);
            }
            ops.check(header);
            whnfChanged = false;
            int ty = whnfT(header);
            int i = 0;
            tIdx0[k] = idxTop;
            while (terms.kind(ty) == Kind.PI) {
                if (i < nparams) {
                    if (k == 0) {
                        int p = ops.mkLocal(tops.consumeTypeAnnotations(terms.a(ty)));
                        params[i] = p;
                        ty = tops.instantiate1(terms.b(ty), p);
                    } else {
                        if (!ops.isDefEq(terms.a(ty), terms.b(params[i]))) {
                            // "parameters of all inductive datatypes must match"
                            throw reject(Reason.INDUCTIVE_INVALID);
                        }
                        ty = tops.instantiate1(terms.b(ty), params[i]);
                    }
                    i++;
                } else {
                    int x = ops.mkLocal(tops.consumeTypeAnnotations(terms.a(ty)));
                    pushIdx(x);
                    ty = tops.instantiate1(terms.b(ty), x);
                }
                ty = whnfT(ty);
            }
            if (i != nparams) {
                // "number of parameters mismatch in inductive datatype declaration"
                throw reject(Reason.INDUCTIVE_INVALID);
            }
            tNumIdx[k] = idxTop - tIdx0[k];
            int l = ops.ensureSort(ty);
            if (whnfChanged) {
                throw decline("a parameter or index type of an inductive type is not a syntactic Pi telescope:"
                        + " the official recursor is built from weak-head normal forms that are not reproduced"); // (T4)
            }
            if (k == 0) {
                resultLevel = l;
                isNotZero = levels.isNeverZero(l);
            } else if (!levelEquiv(l, resultLevel)) {
                // "mutually inductive types must live in the same universe"
                throw reject(Reason.INDUCTIVE_INVALID);
            }
        }
    }

    // =====================================================================================================
    // 6.5 flags
    // =====================================================================================================

    /**
     * is_rec and is_reflexive of the auxiliary block. Both walk the syntactic Pi spine of every
     * constructor type; is_reflexive instantiates the binders with locals, which changes neither
     * "the domain is a Pi" nor "the domain mentions a type of the block".
     */
    private void computeFlags() {
        boolean rec = false;
        boolean refl = false;
        for (int c = 0; c < nCtors && !(rec && refl); c++) {
            int t = cType[c];
            while (terms.kind(t) == Kind.PI) {
                int dom = terms.a(t);
                if (hasIndOcc(dom)) {
                    rec = true;
                    if (terms.kind(dom) == Kind.PI) {
                        refl = true;
                    }
                }
                t = terms.b(t);
            }
        }
        isRec = rec;
        isReflexive = refl;
    }

    // =====================================================================================================
    // 6.6 constructors
    // =====================================================================================================

    /** is_valid_ind_app(t, k). */
    private boolean isValidIndApp(int t, int k) {
        int n = terms.spineLength(t);
        if (n != nparams + tNumIdx[k]) {
            return false;
        }
        buf2 = ensure(buf2, n);
        int head = terms.spine(t, buf2, 0);
        if (head != tAtom[k]) {
            return false;
        }
        for (int i = 0; i < nparams; i++) {
            if (buf2[i] != params[i]) {
                return false;
            }
        }
        for (int i = nparams; i < n; i++) {
            if (hasIndOcc(buf2[i])) {
                return false;
            }
        }
        return true;
    }

    /** is_valid_ind_app(t): the type index, or -1. */
    private int validIndApp(int t) {
        int head = terms.kind(t) == Kind.APP ? terms.c(t) : t;
        if (terms.kind(head) != Kind.FVAR) {
            return -1;
        }
        int k = atomType.get(head) - 1;
        return k >= 0 && isValidIndApp(t, k) ? k : -1;
    }

    private void pushXs(int x) {
        if (xsTop == xsPool.length) {
            xsPool = Arrays.copyOf(xsPool, xsTop * 2);
        }
        xsPool[xsTop++] = x;
    }

    /**
     * check_positivity on a field type, iteratively. Returns -1 for a non-recursive field, else the
     * index of the block type the field ends in; then the locals of the field's telescope are
     * {@code xsPool[xs0 .. xsTop)} and {@link #lastEnd} is the weak-head-normal conclusion. This is also
     * the walk of is_rec_argument and of the hypothesis telescope in mk_rec_infos / mk_rec_rules (same
     * whnf calls on the same terms), so its result is kept (03, decision 11).
     *
     * @param strict false = is_rec_argument only (no positivity errors; used for the re-walk of an
     *     annotation-stripped type)
     */
    private int fieldWalk(int dom, boolean strict) {
        int t = whnfT(dom);
        while (true) {
            if (strict && !hasIndOcc(t)) {
                return -1;
            }
            if (terms.kind(t) == Kind.PI) {
                if (strict && hasIndOcc(terms.a(t))) {
                    // "has a non positive occurrence of the datatypes being declared"
                    throw reject(Reason.INDUCTIVE_INVALID);
                }
                int x = ops.mkLocal(tops.consumeTypeAnnotations(terms.a(t)));
                pushXs(x);
                t = whnfT(tops.instantiate1(terms.b(t), x));
            } else {
                int k = validIndApp(t);
                if (k < 0 && strict) {
                    // "contains a non valid occurrence of the datatypes being declared"
                    throw reject(Reason.INDUCTIVE_INVALID);
                }
                lastEnd = t;
                return k;
            }
        }
    }

    private int lastEnd;

    private void growFields() {
        int n = fieldTop * 2;
        fieldPool = Arrays.copyOf(fieldPool, n);
        fieldRec = Arrays.copyOf(fieldRec, n);
        fieldLevel = Arrays.copyOf(fieldLevel, n);
        fieldXs0 = Arrays.copyOf(fieldXs0, n);
        fieldXsN = Arrays.copyOf(fieldXsN, n);
        fieldEnd = Arrays.copyOf(fieldEnd, n);
    }

    /** check_constructors, then declare_constructors (the constructor atoms). */
    private void checkConstructors() {
        for (int c = 0; c < nCtors; c++) {
            int k = cOwner[c];
            int type = cType[c];
            // tc().check(t) in the environment with the block's types only: a constant of the unit that
            // is still here (constructor, recursor, projection structure) is an unknown constant.
            if (tools.any(type, ownMember)) {
                throw reject(Reason.UNKNOWN_CONSTANT);
            }
            ops.check(type);
            int arity = 0;
            for (int t = type; terms.kind(t) == Kind.PI; t = terms.b(t)) {
                arity++;
            }
            tele = ensure(tele, arity);
            cField0[c] = fieldTop;
            int t = type;
            for (int i = 0; i < arity; i++, t = terms.b(t)) {
                int dom = tops.instantiateRev(terms.a(t), tele, 0, i);
                if (i < nparams) {
                    if (!ops.isDefEq(dom, terms.b(params[i]))) {
                        // "does not match inductive datatypes parameters"
                        throw reject(Reason.INDUCTIVE_INVALID);
                    }
                    tele[i] = params[i];
                    continue;
                }
                int lf = ops.ensureSort(ops.infer(dom));
                if (!(levelGeq(resultLevel, lf) || levels.isAlwaysZero(resultLevel))) {
                    // "universe level of type_of(arg) is too big for the corresponding inductive datatype"
                    throw reject(Reason.INDUCTIVE_INVALID);
                }
                int xs0 = xsTop;
                whnfChanged = false;
                int rec = fieldWalk(dom, true);
                int end = lastEnd;
                int stripped = tops.consumeTypeAnnotations(dom);
                if (rec >= 0 && stripped != dom && !tops.eqv(stripped, dom)) {
                    // mk_rec_infos builds the hypothesis from whnf(type of the local), i.e. from the
                    // annotation-stripped type, while is_rec_argument decided on the declared domain.
                    xsTop = xs0;
                    int rec2 = fieldWalk(stripped, false);
                    if (rec2 < 0) {
                        throw decline("a recursive field whose type is recursive only under a type annotation"
                                + " (official behaviour undefined: get_I_indices on a non-application)");
                    }
                    rec = rec2;
                    end = lastEnd;
                }
                if (rec < 0) {
                    xsTop = xs0;
                } else if (whnfChanged) {
                    throw decline("the type of a recursive field is not a syntactic Pi telescope: the official"
                            + " induction hypothesis is built from a weak-head normal form that is not reproduced"); // (T4)
                }
                int x = ops.mkLocal(stripped);
                tele[i] = x;
                if (fieldTop == fieldPool.length) {
                    growFields();
                }
                int f = fieldTop++;
                fieldPool[f] = x;
                fieldRec[f] = rec;
                fieldLevel[f] = lf;
                fieldXs0[f] = xs0;
                fieldXsN[f] = xsTop - xs0;
                fieldEnd[f] = end;
            }
            if (arity < nparams) {
                throw reject(Reason.INDUCTIVE_INVALID); // excluded by elimNested; kept for safety
            }
            int result = tops.instantiateRev(t, tele, 0, arity);
            if (!isValidIndApp(result, k)) {
                // "invalid return type"
                throw reject(Reason.INDUCTIVE_INVALID);
            }
            cResult[c] = result;
            cNumFields[c] = arity - nparams;
        }
        // declare_constructors. A constructor of the block is referred to only by the kernel itself
        // (mk_constant(cnstr_name, m_levels) in mk_rec_infos and check_recursors): the type inferred
        // for it is the constructor type with its levels rebuilt.
        for (int c = 0; c < nCtors; c++) {
            int atom = ops.mkLocal(rebuildTerm(cType[c]));
            cAtom[c] = atom;
            atomCtor.put(atom, c + 1);
        }
    }

    // =====================================================================================================
    // 6.8 elimination universe
    // =====================================================================================================

    /** elim_only_at_universe_zero, on the walk of check_constructors (same domains, same sorts). */
    private boolean computeElimOnlyAtZero() {
        if (isNotZero) {
            return false;
        }
        if (nTypes > 1) {
            return true;
        }
        int n = tCtorN[0];
        if (n > 1) {
            return true;
        }
        if (n == 0) {
            return false;
        }
        int c = tCtor0[0];
        int res = cResult[c];
        int m = terms.spineLength(res);
        buf = ensure(buf, m);
        terms.spine(res, buf, 0);
        for (int f = cField0[c], e = f + cNumFields[c]; f < e; f++) {
            if (levels.isAlwaysZero(fieldLevel[f])) {
                continue;
            }
            int x = fieldPool[f];
            boolean found = false;
            for (int i = 0; i < m && !found; i++) {
                found = buf[i] == x;
            }
            if (!found) {
                return true;
            }
        }
        return false;
    }

    /** init_elim_level: Zero, or Param u with u the first of "u", "u_1", "u_2", ... not in lparams. */
    private void initElimLevel() {
        elimOnlyAtZero = computeElimOnlyAtZero();
        if (elimOnlyAtZero) {
            elimLevel = levels.zero();
            elimName = 0;
            return;
        }
        int lp = in.levelParams;
        int nlp = lists.length(lp);
        String u = "u";
        for (int i = 1;; i++) {
            boolean taken = false;
            for (int j = 0; j < nlp && !taken; j++) {
                taken = isSimpleName(lists.get(lp, j), u);
            }
            if (!taken) {
                break;
            }
            u = "u_" + i;
        }
        // The name id of u: the store interns names on the loader thread only, so the id is taken from
        // an exported recursor record (first universe parameter) after checking that it IS that name.
        int id = 0;
        for (int r = 0; r < in.recCount && id == 0; r++) {
            int l = in.recLevelParams[r];
            if (lists.length(l) > 0 && isSimpleName(lists.get(l, 0), u)) {
                id = lists.get(l, 0);
            }
        }
        if (id == 0) {
            for (int r = 0; r < in.recCount; r++) {
                if (in.recUnsafe == null || !in.recUnsafe[r]) {
                    // No exported recursor has the elimination universe as its first parameter: each of
                    // them differs from the recursor the kernel generates (or names none), whatever else
                    // holds. (A record flagged unsafe is not compared officially and proves nothing.)
                    throw reject(Reason.RECORD_MISMATCH);
                }
            }
            throw decline("the inductive block eliminates into every universe but the export has no recursor record"
                    + " (or only records flagged unsafe) to take the name of the elimination universe from: the"
                    + " recursors would be constants the kernel generates (generated constants are deferred)");
        }
        elimName = id;
        elimLevel = levels.param(id);
    }

    // =====================================================================================================
    // 6.10 motives and minor premises; 6.11 recursors and rules
    // =====================================================================================================

    private void mkRecInfos() {
        int sortElim = ov.mkSort(elimLevel);
        for (int j = 0; j < nTypes; j++) {
            int nidx = tNumIdx[j];
            int app = ov.mkApps(ov.mkApps(tAtom[j], params, 0, nparams), idxPool, tIdx0[j], nidx);
            int major = ops.mkLocal(app);
            tMajor[j] = major;
            tele = ensure(tele, nidx + 1);
            System.arraycopy(idxPool, tIdx0[j], tele, 0, nidx);
            tele[nidx] = major;
            tMotive[j] = ops.mkLocal(tops.mkPiOver(tele, 0, nidx + 1, sortElim));
        }
        for (int c = 0; c < nCtors; c++) {
            int j = cOwner[c];
            int nf = cNumFields[c];
            int f0 = cField0[c];
            int res = cResult[c];
            int m = terms.spineLength(res);
            buf = ensure(buf, m);
            terms.spine(res, buf, 0);
            int capp = ov.mkApps(tMotive[j], buf, nparams, m - nparams);
            int intro = ov.mkApps(ov.mkApps(cAtom[c], params, 0, nparams), fieldPool, f0, nf);
            cIntro[c] = intro;
            capp = ov.mkApp(capp, intro);
            int nih = 0;
            for (int f = f0; f < f0 + nf; f++) {
                if (fieldRec[f] >= 0) {
                    nih++;
                }
            }
            tele = ensure(tele, nf + nih);
            System.arraycopy(fieldPool, f0, tele, 0, nf);
            int ih = nf;
            for (int f = f0; f < f0 + nf; f++) {
                int r = fieldRec[f];
                if (r < 0) {
                    continue;
                }
                int end = fieldEnd[f];
                int me = terms.spineLength(end);
                buf = ensure(buf, me);
                terms.spine(end, buf, 0);
                int happ = ov.mkApps(tMotive[r], buf, nparams, me - nparams);
                happ = ov.mkApp(happ, ov.mkApps(fieldPool[f], xsPool, fieldXs0[f], fieldXsN[f]));
                tele[ih++] = ops.mkLocal(tops.mkPiOver(xsPool, fieldXs0[f], fieldXsN[f], happ));
            }
            cMinor[c] = ops.mkLocal(tops.mkPiOver(tele, 0, nf + nih, capp));
        }
    }

    /** declare_recursors and mk_rec_rules. */
    private void mkRecursors() {
        nPre = nparams + nTypes + nCtors;
        pre = ensure(pre, nPre);
        System.arraycopy(params, 0, pre, 0, nparams);
        System.arraycopy(tMotive, 0, pre, nparams, nTypes);
        System.arraycopy(cMinor, 0, pre, nparams + nTypes, nCtors);
        recTypeAux = ensure(recTypeAux, nTypes);
        recAtom = ensure(recAtom, nTypes);
        recPre = ensure(recPre, nTypes);
        recCid = ensure(recCid, nTypes);
        recFinalAtom = ensure(recFinalAtom, nTypes);
        recTypeCheck = ensure(recTypeCheck, nTypes);
        for (int j = 0; j < nTypes; j++) {
            int nidx = tNumIdx[j];
            int len = nPre + nidx + 1;
            tele = ensure(tele, len);
            System.arraycopy(pre, 0, tele, 0, nPre);
            System.arraycopy(idxPool, tIdx0[j], tele, nPre, nidx);
            tele[nPre + nidx] = tMajor[j];
            int capp = ov.mkApp(ov.mkApps(tMotive[j], idxPool, tIdx0[j], nidx), tMajor[j]);
            recTypeAux[j] = tops.mkPiOver(tele, 0, len, capp);
        }
        for (int j = 0; j < nTypes; j++) {
            int atom = ops.mkLocal(recTypeAux[j]);
            recAtom[j] = atom;
            atomRec.put(atom, j + 1);
            recPre[j] = ov.mkApps(atom, pre, 0, nPre);
        }
        for (int c = 0; c < nCtors; c++) {
            int nf = cNumFields[c];
            int f0 = cField0[c];
            int eapp = ov.mkApps(cMinor[c], fieldPool, f0, nf);
            for (int f = f0; f < f0 + nf; f++) {
                int r = fieldRec[f];
                if (r < 0) {
                    continue;
                }
                int end = fieldEnd[f];
                int me = terms.spineLength(end);
                buf = ensure(buf, me);
                terms.spine(end, buf, 0);
                int rapp = ov.mkApps(recPre[r], buf, nparams, me - nparams);
                rapp = ov.mkApp(rapp, ov.mkApps(fieldPool[f], xsPool, fieldXs0[f], fieldXsN[f]));
                eapp = ov.mkApp(eapp, tops.mkLambdaOver(xsPool, fieldXs0[f], fieldXsN[f], rapp));
            }
            cReduct[c] = eapp;
            int len = nPre + nf;
            tele = ensure(tele, len);
            System.arraycopy(pre, 0, tele, 0, nPre);
            System.arraycopy(fieldPool, f0, tele, nPre, nf);
            cRhs[c] = tops.mkLambdaOver(tele, 0, len, eapp);
        }
    }

    private int recLevelParams;

    /**
     * check_recursors. (1) every recursor type is well typed under the recursor's universe parameters;
     * (2) every rule preserves types: {@code infer(whnf(lhs))} against {@code infer(lhs)} with
     * {@code lhs = rec params Cs minors idx (ctor params fields)}. The recursor is an atom here, so the
     * one iota step of {@code whnf(lhs)} is done by hand: the rule's right-hand side applied to
     * parameters, motives, minors and fields beta-reduces to {@code minor fields ihs}, whose head is a
     * local, which is where the official whnf stops as well.
     */
    private void checkRecursors() {
        if (elimName != 0) {
            int nlp = lists.length(in.levelParams);
            buf = ensure(buf, nlp + 1);
            buf[0] = elimName;
            for (int i = 0; i < nlp; i++) {
                buf[i + 1] = lists.get(in.levelParams, i);
            }
            recLevelParams = lists.intern(buf, 0, nlp + 1);
            ops.begin(in.rank, recLevelParams);
        } else {
            recLevelParams = in.levelParams;
        }
        // tc().check(rec type): the major premise `t : T_k.{m_levels} params indices` (in every motive
        // and in the recursor of T_k) is an application of a reference the kernel made itself, so
        // T_k has the REBUILT header there: the type of each parameter and index local is compared
        // with the binder domain of the rebuilt header. With the declared header (the stand-in of a
        // user type) this is part of the check below; with a header that the rebuild respells it is
        // made here, by the engine's own application check on a local of the rebuilt header.
        for (int k = 0; k < nUser; k++) {
            int rebuilt = rebuildTerm(tHeader[k]);
            if (rebuilt != tHeader[k]) {
                int fresh = ops.mkLocal(rebuilt);
                ops.check(ov.mkApps(ov.mkApps(fresh, params, 0, nparams), idxPool, tIdx0[k], tNumIdx[k]));
            }
        }
        for (int j = 0; j < nTypes; j++) {
            ops.check(recTypeAux[j]);
            for (int c = tCtor0[j], e = c + tCtorN[j]; c < e; c++) {
                int res = cResult[c];
                int m = terms.spineLength(res);
                buf = ensure(buf, m);
                terms.spine(res, buf, 0);
                int lhs = ov.mkApp(ov.mkApps(recPre[j], buf, nparams, m - nparams), cIntro[c]);
                int expected = ops.infer(lhs);
                int actual = ops.infer(cReduct[c]);
                if (!ops.isDefEq(actual, expected)) {
                    // "generated recursor computation rule ... is not type-preserving"
                    throw reject(Reason.INDUCTIVE_INVALID);
                }
            }
        }
    }

    // =====================================================================================================
    // output; 6.12 restoring nested blocks
    // =====================================================================================================

    /** Index of the exported recursor record named {@code prefix.last}, or -1. */
    private int findRecord(int prefix, String last) {
        for (int r = 0; r < in.recCount; r++) {
            int n = in.recName[r];
            if (n != 0 && names.prefix(n) == prefix && !names.isNumeric(n) && names.lastComponentIs(n, last)) {
                return r;
            }
        }
        return -1;
    }

    private static String auxRecName(int j) {
        return "rec_" + j;
    }

    private void finish(GeneratedBlock out) {
        int numNested = nTypes - nUser;
        // Checklist item 51, decided here because it does not depend on anything that follows: an
        // exported recursor record that names no derived recursor, or a constructor record that no
        // type of the block lists, has no generated counterpart. The official checker rejects the file
        // for it (postponed check) if the block is admitted, and rejects the block otherwise.
        for (int r = 0; r < in.recCount; r++) {
            int n = in.recName[r];
            boolean derived = false;
            for (int j = 0; j < nTypes && !derived; j++) {
                derived = names.prefix(n) == (j < nUser ? in.typeName[j] : in.typeName[0])
                        && names.lastComponentIs(n, j < nUser ? "rec" : auxRecName(j - nUser + 1));
            }
            if (!derived) {
                if (in.recUnsafe != null && in.recUnsafe[r]) {
                    // Ignored by the official checker (unsafe records are not replayed), but its row is a
                    // constant the kernel never generates: not admissible here.
                    throw decline("a recursor record flagged unsafe names no recursor of the block: the official checker"
                            + " ignores the record, but its row would be a constant the kernel never generates");
                }
                throw reject(Reason.RECORD_MISMATCH);
            }
        }
        int listed = 0;
        for (int k = 0; k < nUser; k++) {
            listed += in.typeCtorCount[k];
        }
        if (listed != in.ctorCount) {
            throw reject(Reason.RECORD_MISMATCH);
        }
        out.declineReason = null;
        if (numNested > 0 && nestedNamesInUse()) {
            out.declineReason = Reason.UNSUPPORTED_INPUT;
            out.declineDetail = "a nested inductive block in a file that declares constants under the reserved root"
                    + " '_nested': the official auxiliary names could collide with them";
        }
        // names and constant ids of the recursors
        out.recNamePrefix = ensure(out.recNamePrefix, nTypes);
        if (out.recNameLast == null || out.recNameLast.length < nTypes) {
            out.recNameLast = new String[nTypes];
        }
        for (int j = 0; j < nTypes; j++) {
            int prefix = j < nUser ? in.typeName[j] : in.typeName[0];
            String last = j < nUser ? "rec" : auxRecName(j - nUser + 1);
            out.recNamePrefix[j] = prefix;
            out.recNameLast[j] = last;
            int r = findRecord(prefix, last);
            if (r < 0) {
                // check_name(rec_name): a type or constructor of the block with the recursor's name.
                for (int k = 0; k < nUser; k++) {
                    int n = in.typeName[k];
                    if (names.prefix(n) == prefix && !names.isNumeric(n) && names.lastComponentIs(n, last)) {
                        throw reject(Reason.DUPLICATE_DECLARATION);
                    }
                }
                for (int i = 0; i < in.ctorCount; i++) {
                    int n = in.ctorName[i];
                    if (names.prefix(n) == prefix && !names.isNumeric(n) && names.lastComponentIs(n, last)) {
                        throw reject(Reason.DUPLICATE_DECLARATION);
                    }
                }
                // ... or any other constant: declared earlier, check_name fails here; declared later,
                // that declaration collides with the generated recursor. (Name lookup needs the
                // complete name table: batch loading.)
                if (store.loadComplete()) {
                    byte[] utf8 = last.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
                    int n = names.findStr(prefix, utf8, 0, utf8.length);
                    if (n != 0 && names.constOf(n) > 0) {
                        throw reject(Reason.DUPLICATE_DECLARATION);
                    }
                }
                // The export has no record of this recursor: it would be a generated constant.
                throw decline("the export has no record for the recursor '" + names.toString(prefix) + "." + last
                        + "', which the official kernel generates (generated constants are deferred)");
            }
            recCid[j] = in.recCid[r];
        }
        int recLevels;
        if (elimName != 0) {
            int n = lists.length(lvls);
            buf = ensure(buf, n + 1);
            buf[0] = elimLevel;
            for (int i = 0; i < n; i++) {
                buf[i + 1] = lists.get(lvls, i);
            }
            recLevels = lists.intern(buf, 0, n + 1);
        } else {
            recLevels = lvls;
        }

        out.numNested = numNested;
        out.isRec = isRec;
        out.isReflexive = isReflexive;
        out.elimOnlyAtZero = elimOnlyAtZero;
        out.k = kTarget;
        out.typeNumIndices = ensure(out.typeNumIndices, nUser);
        System.arraycopy(tNumIdx, 0, out.typeNumIndices, 0, nUser);
        out.ctorNumFields = ensure(out.ctorNumFields, in.ctorCount);
        out.ctorIndex = ensure(out.ctorIndex, in.ctorCount);
        Arrays.fill(out.ctorNumFields, 0, in.ctorCount, -1);
        Arrays.fill(out.ctorIndex, 0, in.ctorCount, -1);
        for (int c = 0; c < nCtors; c++) {
            if (cInput[c] >= 0) {
                out.ctorNumFields[cInput[c]] = cNumFields[c];
                out.ctorIndex[cInput[c]] = c - tCtor0[cOwner[c]];
            }
        }
        out.recCount = nTypes;
        out.recLevelParams = recLevelParams;
        out.recNumMotives = nTypes;
        out.recNumMinors = nCtors;
        out.recType = ensure(out.recType, nTypes);
        out.recNumIndices = ensure(out.recNumIndices, nTypes);
        out.recRuleCount = ensure(out.recRuleCount, nTypes);
        out.recMajorInduct = ensure(out.recMajorInduct, nTypes);
        if (out.recRuleCtor == null || out.recRuleCtor.length < nTypes) {
            out.recRuleCtor = new int[nTypes][];
            out.recRuleFields = new int[nTypes][];
            out.recRuleRhs = new int[nTypes][];
        }
        for (int j = 0; j < nTypes; j++) {
            out.recNumIndices[j] = tNumIdx[j];
            out.recMajorInduct[j] = j < nUser ? in.typeCid[j] : tContCid[j];
            int n = tCtorN[j];
            out.recRuleCount[j] = n;
            if (out.recRuleCtor[j] == null || out.recRuleCtor[j].length != n) {
                out.recRuleCtor[j] = new int[n];
                out.recRuleFields[j] = new int[n];
                out.recRuleRhs[j] = new int[n];
            }
            for (int i = 0; i < n; i++) {
                int c = tCtor0[j] + i;
                out.recRuleCtor[j][i] = cCid[c];
                out.recRuleFields[j][i] = cNumFields[c];
            }
        }

        // atoms -> constants
        IntIntMap outMap = renameMap;
        if (numNested == 0) {
            outMap.clear();
            for (int k = 0; k < nUser; k++) {
                outMap.put(tAtom[k], ov.mkConst(in.typeCid[k], lvls));
                outMap.put(recAtom[k], ov.mkConst(recCid[k], recLevels));
            }
            for (int c = 0; c < nCtors; c++) {
                outMap.put(cAtom[c], ov.mkConst(cCid[c], lvls));
            }
            rename.memo.clear();
            for (int j = 0; j < nTypes; j++) {
                out.recType[j] = closedOutput(tools.replace(recTypeAux[j], rename));
                for (int i = 0; i < tCtorN[j]; i++) {
                    out.recRuleRhs[j][i] = closedOutput(tools.replace(cRhs[tCtor0[j] + i], rename));
                }
            }
            return;
        }
        restoreNested(out, recLevels);
    }

    /** A generated term must not contain an atom or a local any more. */
    private int closedOutput(int term) {
        int h = terms.header(term);
        if ((h & Node.F_HAS_FVAR) != 0 || Node.looseRange(h) != 0) {
            throw reject(Reason.INDUCTIVE_INVALID); // "failed to restore nested inductive types"
        }
        return term;
    }

    /** Per term being restored: {@code key[P := As]} of each auxiliary type. */
    private int[] keyAt = new int[4];

    private int keyAt(int t) {
        int k = keyAt[t - nUser];
        if (k == 0) {
            k = tops.instantiateRev(tops.abstractFVars(tKey[t], elimP, 0, nparams), curAs, 0, nparams);
            keyAt[t - nUser] = k;
        }
        return k;
    }

    /**
     * The two application cases of restore_nested: {@code auxJ As is -> (J Ds)[P := As] is} and
     * {@code auxJ.c As fields -> J.c Ds[P := As] fields}. The remaining arguments are not rewritten
     * (the official replace does not descend into a replaced node).
     */
    private int restoreApp(int head, int[] args, int n) {
        int t = atomType.get(head) - 1;
        if (t >= nUser) {
            if (n < nparams) {
                // "auxiliary type is not applied to all parameters"
                throw reject(Reason.INDUCTIVE_INVALID);
            }
            return ov.mkApps(keyAt(t), args, nparams, n - nparams);
        }
        int c = atomCtor.get(head) - 1;
        if (c >= 0 && cOwner[c] >= nUser) {
            if (n < nparams) {
                // "auxiliary constructor is not applied to all parameters"
                throw reject(Reason.INDUCTIVE_INVALID);
            }
            int owner = cOwner[c];
            int key = keyAt(owner);
            // key = J.{ls} Ds: replace the head by the container's constructor
            int m = terms.spineLength(key);
            int[] ds = new int[m];
            int khead = terms.spine(key, ds, 0);
            if (terms.kind(khead) != Kind.CONST) {
                throw reject(Reason.INDUCTIVE_INVALID);
            }
            int fn = ov.mkConst(cCid[c], tContLvls[owner]);
            return ov.mkApps(ov.mkApps(fn, ds, 0, m), args, nparams, n - nparams);
        }
        return 0;
    }

    /** restore_nested(e): peel the parameters, rewrite, rebind with the original binder kind. */
    private int restoreTerm(int e) {
        if ((terms.header(e) & Node.F_HAS_FVAR) == 0) {
            return e;
        }
        boolean pi = terms.kind(e) == Kind.PI;
        int body = peelParams(e, curAs, true);
        if (body == 0) {
            // "failed to restore nested inductive types, fewer binders than parameters"
            throw reject(Reason.INDUCTIVE_INVALID);
        }
        restore.memo.clear();
        Arrays.fill(keyAt, 0, nTypes - nUser, 0);
        int r = tools.replace(body, restore);
        return pi ? tops.mkPiOver(curAs, 0, nparams, r) : tops.mkLambdaOver(curAs, 0, nparams, r);
    }

    private void restoreNested(GeneratedBlock out, int recLevels) {
        int numNested = nTypes - nUser;
        keyAt = ensure(keyAt, numNested);
        int userCtors = tCtor0[nUser - 1] + tCtorN[nUser - 1];

        // The parametric arguments Ds are checked below in the FINAL environment, which contains the
        // block's constructors and recursors with all their reduction rules. Atoms cannot stand for
        // that: if some Ds mention a constructor, a recursor or a projection of the block, the checks
        // in the final environment are not made and the block is marked undecidable.
        boolean finalChecks = true;
        for (int t = nUser; t < nTypes; t++) {
            if (tools.any(tKey[t], ownMember)) {
                finalChecks = false;
                out.declineReason = Reason.UNSUPPORTED_INPUT;
                out.declineDetail = "a nested occurrence whose parameters mention a constructor, recursor or projection"
                        + " of the block itself: the official checks in the final environment are not reproduced";
            }
        }

        // constructors: the restored type is the declared one
        int[] cFinal = new int[userCtors];
        for (int c = 0; c < userCtors; c++) {
            int restored = restoreTerm(cType[c]);
            if (restored != cDeclared[c] && !tops.eqv(restored, cDeclared[c])) {
                throw reject(Reason.RECORD_MISMATCH);
            }
            // In the final environment the recursor types and rules still refer to the constructor
            // with the kernel's own level objects: rebuilt type, as for cAtom.
            cFinal[c] = ops.mkLocal(rebuildTerm(cDeclared[c]));
        }

        // recursor types (they mention no recursor)
        IntIntMap map = renameMap;
        int[] recRestored = new int[nTypes];
        map.clear();
        for (int c = 0; c < userCtors; c++) {
            map.put(cAtom[c], cFinal[c]);
        }
        rename.memo.clear();
        for (int j = 0; j < nTypes; j++) {
            recRestored[j] = restoreTerm(recTypeAux[j]);
            recTypeCheck[j] = tools.replace(recRestored[j], rename);
            // The rules refer to the recursors as `rec.{lvls}` with the kernel's level objects, and
            // the re-check of the rules infers their types in check mode: rebuilt recursor type.
            recFinalAtom[j] = ops.mkLocal(rebuildTerm(recTypeCheck[j]));
        }
        // rules: check form
        for (int j = 0; j < nUser; j++) {
            map.put(recAtom[j], recFinalAtom[j]);
        }
        rename.memo.clear();
        int[] rhsRestored = new int[nCtors];
        int[] rhsCheck = new int[nCtors];
        for (int c = 0; c < nCtors; c++) {
            rhsRestored[c] = restoreTerm(cRhs[c]);
            rhsCheck[c] = tools.replace(rhsRestored[c], rename);
        }

        if (finalChecks) {
            // "Type check the nested inductive applications `I Ds` that were replaced by auxiliary types",
            // under the parameters of elim_nested_inductive_fn and the block's universe parameters.
            ops.begin(in.rank, in.levelParams);
            for (int t = nUser; t < nTypes; t++) {
                ops.check(tKey[t]);
            }
            // "Re-check everything restore_nested rewrote"
            for (int c = 0; c < userCtors; c++) {
                ops.check(cDeclared[c]);
            }
            if (elimName != 0) {
                ops.begin(in.rank, recLevelParams);
            }
            for (int j = 0; j < nTypes; j++) {
                ops.check(recTypeCheck[j]);
                for (int c = tCtor0[j], e = c + tCtorN[j]; c < e; c++) {
                    ops.check(rhsCheck[c]);
                }
            }
        }

        // output form
        map.clear();
        for (int k = 0; k < nUser; k++) {
            map.put(tAtom[k], ov.mkConst(in.typeCid[k], lvls));
            map.put(recAtom[k], ov.mkConst(recCid[k], recLevels));
        }
        for (int c = 0; c < userCtors; c++) {
            map.put(cAtom[c], ov.mkConst(cCid[c], lvls));
        }
        for (int j = nUser; j < nTypes; j++) {
            map.put(recFinalAtom[j], ov.mkConst(recCid[j], recLevels));
        }
        rename.memo.clear();
        for (int j = 0; j < nTypes; j++) {
            out.recType[j] = closedOutput(tools.replace(recRestored[j], rename));
            for (int i = 0; i < tCtorN[j]; i++) {
                out.recRuleRhs[j][i] = closedOutput(tools.replace(rhsRestored[tCtor0[j] + i], rename));
            }
        }
    }

    private static int[] ensure(int[] a, int n) {
        if (a == null) {
            return new int[Math.max(n, 4)];
        }
        return a.length >= n ? a : Arrays.copyOf(a, Math.max(n, a.length * 2));
    }
}
