package rlvm.admit;

import java.util.Arrays;

import rlvm.api.Reason;
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
 * The checks of {@code check_constant_val} and of declaration roots that need no type checking
 * (03 checklist items 4 - 8, 11, 12), decided from the node summaries in O(1) per root:
 *
 * <pre>
 * name unique                         -> guaranteed by the loader (NameTable.claim); re-checked here in O(1)
 * universe parameters distinct        -> list test, once per declaration            else DUPLICATE_LEVEL_PARAM
 * root closed                         -> looseRange(root) == 0                       else LOOSE_BVAR
 * universe parameters declared        -> lists.subsetOf(lpSet(root), lparams)        else UNDECLARED_LEVEL_PARAM
 * constants known, earlier, right     -> depMax(root) &lt; rank and no F_UNRESOLVED    else slow path:
 *   universe arity                       exact walk, giving UNKNOWN_CONSTANT, LEVEL_ARITY_MISMATCH,
 *                                        FORWARD_REFERENCE / UNSUPPORTED_* (decline) or success
 * </pre>
 *
 * <p>The exact walk (iterative, one visit per DAG node, sub-DAGs whose summary already proves them
 * fine are skipped) looks at every CONST, PROJ and literal node that the summary could not clear:
 *
 * <pre>
 * name dropped as unsafe / partial                     -> UNKNOWN_CONSTANT          (reject; tutorial 140, 141)
 * name not declared in the whole file                  -> UNKNOWN_CONSTANT          (reject; decline while loading)
 * wrong number of universe levels                      -> LEVEL_ARITY_MISMATCH      (reject; bugs/constlevels)
 * constant of the unit itself, kind not exposed        -> UNKNOWN_CONSTANT          (reject; tutorial 014)
 * constant of a later unit                             -> FORWARD_REFERENCE         (decline)
 * unbound node whose name is declared by now           -> FORWARD_REFERENCE         (decline, see below)
 * literal whose prerequisite constants are not earlier -> UNSUPPORTED_PRELUDE / FORWARD_REFERENCE (decline)
 * </pre>
 * A reject found anywhere wins over a decline: the official checker replays dependencies first and
 * then checks every sub-term of every root, so a structural defect is a rejection whatever else the
 * root contains.
 *
 * <p>What a passed root guarantees to an engine: it is closed; its universe parameters are declared;
 * every CONST / PROJ node below it is bound to a constant id ({@code a > 0}) of smaller rank (or of
 * the unit itself where {@code ownKindMask} exposes that kind) with the right number of levels; the
 * prerequisites of every literal are declared earlier. A root containing an unbound node
 * ({@code ~nameId}) never passes: v1 declines "late-bound" names instead of asking engines to
 * resolve names (deviation from ARCHITECTURE.md 4.3, which lets them pass; such a file has an earlier
 * forward reference or reuses the expressions of a dropped record, neither of which the exporter
 * produces).
 *
 * <p>Stateless apart from scratch buffers and the message of the last failure; one instance per
 * thread.
 */
public final class StructuralChecks {

    /** {@code ownKindMask}: no constant of the unit itself is visible (def, thm, axiom, opaque, inductive headers). */
    public static final int OWN_NONE = 0;
    /** {@code ownKindMask}: the unit's inductive types are visible (constructor types). */
    public static final int OWN_TYPES = 1 << DeclKind.INDUCT;
    /** {@code ownKindMask}: every member of the unit is visible. */
    public static final int OWN_ALL = (1 << DeclKind.INDUCT) | (1 << DeclKind.CTOR) | (1 << DeclKind.REC);

    private final Store store;
    private final Terms terms;
    private final IntLists lists;
    private final DeclTable decls;
    private final NameTable names;
    private final Specials specials;

    /** Explicit DFS stack of the exact walk. */
    private int[] stack = new int[64];
    /** Visited set of the exact walk: open addressing, 0 = free (term ids are never 0). */
    private int[] seen = new int[128];
    /** Slots of {@link #seen} in use, so that clearing costs O(visited). */
    private int[] seenSlots = new int[64];
    private int seenCount;
    private int[] sortBuf = new int[0];

    private String message;

    /** Uses the shared list store: enough for shared roots (rows of the declaration table). */
    public StructuralChecks(Store store, Terms terms, Policy policy) {
        this(store, terms, store.lists(), policy);
    }

    /**
     * @param lists the list store that can read the {@code lpSet} of every root this instance will
     *     see: a worker's own list overlay when roots may be decoded proof blocks, else the shared one
     */
    public StructuralChecks(Store store, Terms terms, IntLists lists, Policy policy) {
        this.store = store;
        this.terms = terms;
        this.lists = lists;
        this.decls = store.decls();
        this.names = store.names();
        this.specials = store.specials();
    }

    /**
     * Checks the universe parameters and the type and (if any) value roots of constant {@code cid}.
     * A proof-block value is decoded into the overlay of this instance's {@link Terms} first.
     *
     * @return null if all checks pass, else the reason (a reject reason or a decline reason)
     */
    public Reason checkConstant(int cid) {
        message = null;
        int kind = decls.kind(cid);
        int name = decls.name(cid);
        if (names.constOf(name) != cid) {
            return fail(Reason.DUPLICATE_DECLARATION, "the name of ", name, " is claimed by another declaration");
        }
        int lparams = decls.levelParams(cid);
        if (!distinct(lparams)) {
            return fail(Reason.DUPLICATE_LEVEL_PARAM, "duplicate universe parameter in ", name, null);
        }
        if (decls.numLevelParams(cid) != lists.length(lparams)) {
            return fail(Reason.UNSUPPORTED_INPUT, "universe parameter count of ", name, " does not fit its row");
        }
        int rank = decls.rank(cid);
        int own = kind == DeclKind.CTOR ? OWN_TYPES : kind == DeclKind.REC ? OWN_ALL : OWN_NONE;
        Reason first = checkRoot(decls.type(cid), rank, lparams, own);
        if (first != null && first.isReject()) {
            return first;
        }
        if (kind != DeclKind.DEFN && kind != DeclKind.THM && kind != DeclKind.OPAQUE) {
            return first;
        }
        String firstMessage = message;
        int value = decls.value(cid);
        int root;
        if (value > 0) {
            root = value;
        } else if (value < 0) {
            Overlay overlay = terms.overlay();
            if (overlay == null) {
                return fail(Reason.INTERNAL_ERROR, "no overlay to decode the proof block of ", name, null);
            }
            root = overlay.blockRoot(-(value + 1));
            terms.refresh();
            if (root == 0) {
                return fail(Reason.INTERNAL_ERROR, "the proof block of ", name, " has no root");
            }
        } else {
            return fail(Reason.INTERNAL_ERROR, "declaration ", name, " has no value");
        }
        Reason second = checkRoot(root, rank, lparams, OWN_NONE);
        if (second != null && second.isReject()) {
            return second;
        }
        if (first != null) {
            message = firstMessage;
            return first;
        }
        return second;
    }

    /**
     * The root checks for one term of a unit of rank {@code rank} with parameters {@code lparams}.
     * {@code allowOwnUnit} exposes every constant of the unit itself; prefer
     * {@link #checkRoot(int, int, int, int)} with a kind mask.
     */
    public Reason checkRoot(int term, int rank, int lparams, boolean allowOwnUnit) {
        return checkRoot(term, rank, lparams, allowOwnUnit ? OWN_ALL : OWN_NONE);
    }

    /**
     * The root checks for one term of a unit of rank {@code rank} with parameters {@code lparams}.
     *
     * @param ownKindMask bit {@code 1 << DeclKind.X} set: constants of kind X of the unit itself
     *     (rank equal to {@code rank}) are visible, as the official kernel adds an inductive block's
     *     types before it checks the constructors ({@link #OWN_NONE}, {@link #OWN_TYPES}, {@link #OWN_ALL})
     * @return null if all checks pass, else the reason; {@link #lastMessage()} describes it
     */
    public Reason checkRoot(int term, int rank, int lparams, int ownKindMask) {
        if (term <= 0) {
            message = "declaration root is missing";
            return Reason.INTERNAL_ERROR;
        }
        int header = terms.header(term);
        if (Node.looseRange(header) != 0) {
            message = "loose bound variable in a declaration root";
            return Reason.LOOSE_BVAR;
        }
        int lpSet = terms.lpSet(term);
        if (lpSet != 0 && !lists.subsetOf(lpSet, lparams)) {
            message = undeclaredMessage(lpSet, lparams);
            return Reason.UNDECLARED_LEVEL_PARAM;
        }
        if ((header & Node.F_UNRESOLVED) == 0 && terms.depMax(term) < rank) {
            return null;
        }
        return walk(term, rank, ownKindMask);
    }

    /** True if the name list has no duplicate. */
    public boolean distinct(int nameList) {
        if (nameList == IntLists.EMPTY) {
            return true;
        }
        int n = lists.length(nameList);
        if (n <= 1) {
            return true;
        }
        if (n <= 16) {
            for (int i = 1; i < n; i++) {
                int x = lists.get(nameList, i);
                for (int j = 0; j < i; j++) {
                    if (lists.get(nameList, j) == x) {
                        return false;
                    }
                }
            }
            return true;
        }
        if (sortBuf.length < n) {
            sortBuf = new int[n];
        }
        lists.copyTo(nameList, sortBuf, 0);
        Arrays.sort(sortBuf, 0, n);
        for (int i = 1; i < n; i++) {
            if (sortBuf[i] == sortBuf[i - 1]) {
                return false;
            }
        }
        return true;
    }

    /** Detail of the last non-null result of this instance (diagnostics; built only on failure). */
    public String lastMessage() {
        return message;
    }

    // ---- exact walk ------------------------------------------------------------------------------------

    private Reason walk(int root, int rank, int ownKindMask) {
        Reason decline = null;
        String declineMessage = null;
        int sp = 0;
        stack[sp++] = root;
        try {
            while (sp > 0) {
                int t = stack[--sp];
                int header = terms.header(t);
                if ((header & Node.F_UNRESOLVED) == 0 && terms.depMax(t) < rank) {
                    continue; // the summary clears the whole sub-DAG
                }
                if (!markSeen(t)) {
                    continue;
                }
                Reason r = null;
                switch (Node.kind(header)) {
                    case Kind.CONST:
                        r = reference(terms.a(t), terms.b(t), true, rank, ownKindMask);
                        break;
                    case Kind.PROJ:
                        r = reference(terms.c(t), 0, false, rank, ownKindMask);
                        sp = push(sp, terms.a(t));
                        break;
                    case Kind.APP:
                    case Kind.LAM:
                    case Kind.PI:
                        sp = push(sp, terms.b(t));
                        sp = push(sp, terms.a(t));
                        break;
                    case Kind.LET:
                        sp = push(sp, terms.c(t));
                        sp = push(sp, terms.b(t));
                        sp = push(sp, terms.a(t));
                        break;
                    case Kind.NATLIT:
                        r = literal(specials.natLiteralRank(), rank, "Nat");
                        break;
                    case Kind.STRLIT:
                        r = literal(specials.stringLiteralRank(), rank, "String");
                        break;
                    default:
                        break; // BVAR, SORT, FVAR: nothing to resolve
                }
                if (r != null) {
                    if (r.isReject()) {
                        return r;
                    }
                    if (decline == null) {
                        decline = r;
                        declineMessage = message;
                    }
                }
            }
        } finally {
            clearSeen();
        }
        if (decline != null) {
            message = declineMessage;
        }
        return decline;
    }

    /**
     * One CONST ({@code ref} = word a) or PROJ ({@code ref} = word c) node: a constant id, or
     * {@code ~nameId} when the node was written before any declaration claimed the name.
     */
    private Reason reference(int ref, int levelList, boolean isConst, int rank, int ownKindMask) {
        boolean bound = ref > 0;
        int cid;
        if (bound) {
            cid = ref;
        } else {
            if (ref == 0) {
                message = "constant reference without a constant or a name";
                return Reason.INTERNAL_ERROR;
            }
            int name = ~ref;
            int claimed = names.constOf(name);
            if (claimed <= 0 && store.loadComplete() && kernelMayGenerate(name)) {
                // The official kernel adds this constant itself (quotient package, recursor of an
                // inductive type), whether or not the export has a record for it; RLVM has no generated
                // constants yet, so it cannot tell "unknown" from "generated": never a rejection.
                return fail(Reason.UNSUPPORTED_INPUT, "constant ", name,
                        " has no usable record, but the official kernel generates it (generated constants are deferred)");
            }
            if (claimed < 0) {
                if (!store.loadComplete()) {
                    // A later inductive record may still make the official kernel generate this name
                    // (kernelMayGenerate needs the whole file): not a certain rejection before the end.
                    return fail(Reason.FORWARD_REFERENCE, "constant ", name,
                            " was dropped (unsafe or partial); undecided until the file is loaded");
                }
                return fail(Reason.UNKNOWN_CONSTANT, "unknown constant ", name, " (dropped: unsafe or partial)");
            }
            if (claimed == 0) {
                if (store.loadComplete()) {
                    return fail(Reason.UNKNOWN_CONSTANT, "unknown constant ", name, null);
                }
                return fail(Reason.FORWARD_REFERENCE, "constant ", name, " is not declared in the loaded part of the file");
            }
            cid = claimed;
        }
        int name = decls.name(cid);
        if (isConst && lists.length(levelList) != decls.numLevelParams(cid)) {
            message = "incorrect number of universe levels for '" + names.toString(name) + "': #"
                    + decls.numLevelParams(cid) + " expected, #" + lists.length(levelList) + " provided";
            return Reason.LEVEL_ARITY_MISMATCH;
        }
        int r = decls.rank(cid);
        if (r < rank) {
            if (bound) {
                return null;
            }
            return fail(Reason.FORWARD_REFERENCE, "reference to ", name,
                    " was written before its declaration (late-bound names are declined)");
        }
        if (r == rank) {
            if (((ownKindMask >>> decls.kind(cid)) & 1) == 0) {
                return fail(Reason.UNKNOWN_CONSTANT, "unknown constant ", name,
                        " (a declaration is not in the environment while it is checked)");
            }
            if (bound) {
                return null;
            }
            return fail(Reason.UNSUPPORTED_INPUT, "unbound reference to ", name, ", a member of the unit itself");
        }
        return fail(Reason.FORWARD_REFERENCE, "constant ", name, " is declared later in the file");
    }

    /**
     * True if the official kernel may add a constant of this name without replaying a record of that
     * name (failure path only; needs the complete file):
     * <ul>
     *   <li>{@code Quot}, {@code Quot.mk}, {@code Quot.lift}, {@code Quot.ind}, when the file has a quot
     *       record under a name the official checker replays (any name but the last three): replaying
     *       it adds all four constants ({@code LeanChecker.lean}, {@code Declaration.quotDecl});</li>
     *   <li>{@code I.rec} and {@code I.rec_N}, when {@code I} is a safe inductive type of the file: the
     *       kernel generates the recursors of a block, auxiliary ones under the first type's name.</li>
     * </ul>
     * Without such a record the name cannot come into existence and "unknown constant" is certain.
     */
    boolean kernelMayGenerate(int name) {
        int which = specials.indexOfName(name);
        if (which >= Specials.QUOT && which <= Specials.QUOT_IND) {
            for (int cid = 1, n = decls.constantCount(); cid <= n; cid++) {
                int kind = decls.kind(cid);
                if (kind >= DeclKind.QUOT_TYPE && kind <= DeclKind.QUOT_IND) {
                    int w = specials.indexOfName(decls.name(cid));
                    if (w != Specials.QUOT_MK && w != Specials.QUOT_LIFT && w != Specials.QUOT_IND) {
                        return true;
                    }
                }
            }
            return false;
        }
        int prefix = names.prefix(name);
        if (prefix == NameTable.ANONYMOUS || names.isNumeric(name)) {
            return false;
        }
        int owner = names.constOf(prefix);
        if (owner <= 0 || decls.kind(owner) != DeclKind.INDUCT) {
            return false;
        }
        if (names.lastComponentIs(name, "rec")) {
            return true;
        }
        String s = names.toString(name);
        int at = s.lastIndexOf(".rec_");
        if (at < 0 || at + 5 >= s.length()) {
            return false;
        }
        for (int i = at + 5; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    // ---- dependency collection (admission in dependency order) -----------------------------------------

    /** Receives what a root of a unit mentions, for {@link #collectRoot}. */
    interface RefSink {
        /** A constant of another unit ({@code cid > 0}); called once per CONST / PROJ node that names it. */
        void constant(int cid);

        /** A Nat ({@code string == false}) or string literal. */
        void literal(boolean string);
    }

    /**
     * The root checks of {@link #checkRoot(int, int, int, int)} for a unit that is admitted in
     * dependency order: nothing is decided from ranks. Every CONST and PROJ node of the root is
     * resolved (by constant id, or by name for a node written before its constant was declared; the
     * file must be completely loaded) and reported to {@code sink} unless it names a constant of
     * {@code unit} itself, which is allowed or rejected by {@code ownKindMask} as usual.
     *
     * @return null, a rejection (loose bound variable, undeclared universe parameter, unknown constant,
     *     wrong number of universe levels, reference of the unit to itself), or a decline (a name the
     *     official kernel generates itself); {@link #lastMessage()} describes it
     */
    Reason collectRoot(int term, int unit, int lparams, int ownKindMask, RefSink sink) {
        if (term <= 0) {
            message = "declaration root is missing";
            return Reason.INTERNAL_ERROR;
        }
        int rootHeader = terms.header(term);
        if (Node.looseRange(rootHeader) != 0) {
            message = "loose bound variable in a declaration root";
            return Reason.LOOSE_BVAR;
        }
        int lpSet = terms.lpSet(term);
        if (lpSet != 0 && !lists.subsetOf(lpSet, lparams)) {
            message = undeclaredMessage(lpSet, lparams);
            return Reason.UNDECLARED_LEVEL_PARAM;
        }
        Reason decline = null;
        String declineMessage = null;
        int sp = 0;
        stack[sp++] = term;
        try {
            while (sp > 0) {
                int t = stack[--sp];
                int header = terms.header(t);
                if ((header & (Node.F_HAS_CONST | Node.F_HAS_LIT | Node.F_UNRESOLVED)) == 0) {
                    continue; // mentions no constant and no literal
                }
                if (!markSeen(t)) {
                    continue;
                }
                Reason r = null;
                switch (Node.kind(header)) {
                    case Kind.CONST:
                        r = collectReference(terms.a(t), terms.b(t), true, unit, ownKindMask, sink);
                        break;
                    case Kind.PROJ:
                        r = collectReference(terms.c(t), 0, false, unit, ownKindMask, sink);
                        sp = push(sp, terms.a(t));
                        break;
                    case Kind.APP:
                    case Kind.LAM:
                    case Kind.PI:
                        sp = push(sp, terms.b(t));
                        sp = push(sp, terms.a(t));
                        break;
                    case Kind.LET:
                        sp = push(sp, terms.c(t));
                        sp = push(sp, terms.b(t));
                        sp = push(sp, terms.a(t));
                        break;
                    case Kind.NATLIT:
                        sink.literal(false);
                        break;
                    case Kind.STRLIT:
                        sink.literal(true);
                        break;
                    default:
                        break;
                }
                if (r != null) {
                    if (r.isReject()) {
                        return r;
                    }
                    if (decline == null) {
                        decline = r;
                        declineMessage = message;
                    }
                }
            }
        } finally {
            clearSeen();
        }
        if (decline != null) {
            message = declineMessage;
        }
        return decline;
    }

    private Reason collectReference(int ref, int levelList, boolean isConst, int unit, int ownKindMask, RefSink sink) {
        int cid;
        if (ref > 0) {
            cid = ref;
        } else {
            if (ref == 0) {
                message = "constant reference without a constant or a name";
                return Reason.INTERNAL_ERROR;
            }
            int name = ~ref;
            int claimed = names.constOf(name);
            if (claimed <= 0) {
                if (!store.loadComplete()) {
                    return fail(Reason.FORWARD_REFERENCE, "constant ", name, " is not declared in the loaded part of the file");
                }
                if (kernelMayGenerate(name)) {
                    return fail(Reason.UNSUPPORTED_INPUT, "constant ", name,
                            " has no usable record, but the official kernel generates it (generated constants are deferred)");
                }
                return fail(Reason.UNKNOWN_CONSTANT, "unknown constant ", name,
                        claimed < 0 ? " (dropped: unsafe or partial)" : null);
            }
            cid = claimed;
        }
        int name = decls.name(cid);
        if (isConst && lists.length(levelList) != decls.numLevelParams(cid)) {
            message = "incorrect number of universe levels for '" + names.toString(name) + "': #"
                    + decls.numLevelParams(cid) + " expected, #" + lists.length(levelList) + " provided";
            return Reason.LEVEL_ARITY_MISMATCH;
        }
        if (decls.unit(cid) == unit) {
            if (((ownKindMask >>> decls.kind(cid)) & 1) == 0) {
                return fail(Reason.UNKNOWN_CONSTANT, "unknown constant ", name,
                        " (a declaration is not in the environment while it is checked)");
            }
            return null;
        }
        sink.constant(cid);
        return null;
    }

    /** A literal node: its prerequisite constants must all be declared by earlier units. */
    private Reason literal(int literalRank, int rank, String what) {
        if (literalRank < rank) {
            return null;
        }
        if (literalRank == Integer.MAX_VALUE) {
            if (store.loadComplete()) {
                message = what + " literal used, but the constants behind " + what + " literals are not all declared";
                return Reason.UNSUPPORTED_PRELUDE;
            }
            message = what + " literal used before the constants behind " + what + " literals are loaded";
            return Reason.FORWARD_REFERENCE;
        }
        if (literalRank == rank) {
            message = what + " literal used inside the declaration of a constant behind " + what + " literals";
            return Reason.UNSUPPORTED_PRELUDE;
        }
        message = what + " literal used before the constants behind " + what + " literals are declared";
        return Reason.FORWARD_REFERENCE;
    }

    private int push(int sp, int term) {
        if (sp == stack.length) {
            stack = Arrays.copyOf(stack, sp << 1);
        }
        stack[sp] = term;
        return sp + 1;
    }

    /** Adds {@code term} to the visited set; false if it was there. */
    private boolean markSeen(int term) {
        int mask = seen.length - 1;
        int i = mix(term) & mask;
        while (true) {
            int x = seen[i];
            if (x == term) {
                return false;
            }
            if (x == 0) {
                break;
            }
            i = (i + 1) & mask;
        }
        seen[i] = term;
        if (seenCount == seenSlots.length) {
            seenSlots = Arrays.copyOf(seenSlots, seenCount << 1);
        }
        seenSlots[seenCount++] = i;
        if (seenCount << 1 > seen.length) {
            growSeen();
        }
        return true;
    }

    private void growSeen() {
        int[] old = seen;
        int[] oldSlots = seenSlots;
        int n = seenCount;
        seen = new int[old.length << 1];
        seenSlots = new int[Math.max(oldSlots.length, n << 1)];
        int mask = seen.length - 1;
        for (int k = 0; k < n; k++) {
            int term = old[oldSlots[k]];
            int i = mix(term) & mask;
            while (seen[i] != 0) {
                i = (i + 1) & mask;
            }
            seen[i] = term;
            seenSlots[k] = i;
        }
    }

    private void clearSeen() {
        for (int k = 0; k < seenCount; k++) {
            seen[seenSlots[k]] = 0;
        }
        seenCount = 0;
        if (seen.length > (1 << 16)) { // do not keep the table of one huge root alive
            seen = new int[128];
            seenSlots = new int[64];
        }
    }

    private static int mix(int x) {
        x *= 0x9E3779B1;
        return x ^ (x >>> 15);
    }

    // ---- messages (failure path only) ------------------------------------------------------------------

    private Reason fail(Reason reason, String before, int name, String after) {
        String s = before + "'" + names.toString(name) + "'";
        message = after == null ? s : s + after;
        return reason;
    }

    private String undeclaredMessage(int lpSet, int lparams) {
        int n = lists.length(lpSet);
        int m = lists.length(lparams);
        for (int i = 0; i < n; i++) {
            int p = lists.get(lpSet, i);
            boolean declared = false;
            for (int j = 0; j < m && !declared; j++) {
                declared = lists.get(lparams, j) == p;
            }
            if (!declared) {
                return "invalid reference to undefined universe level parameter '" + names.toString(p) + "'";
            }
        }
        return "invalid reference to an undefined universe level parameter";
    }
}
