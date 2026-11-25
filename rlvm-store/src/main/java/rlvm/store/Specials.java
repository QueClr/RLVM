package rlvm.store;

import java.nio.charset.StandardCharsets;

/**
 * The constants the kernel knows by name, bound to constant ids once, and the result of the shape
 * guards that decide whether a kernel extension may be used (*agentic-note*: design-architecture-document, 4.8).
 *
 * <p>The class is split in two halves (*agentic-note*: design-reconciliation-document, section 2, "Literal depMax"):
 * <ul>
 *   <li><b>Loader half</b>: the 34 special names are interned when the table is created, so their name
 *       ids are fixed and small. When a declaration claims one of them
 *       ({@link DeclTable#addConstant}), the constant id and its rank are recorded here. This is what
 *       {@link #cid}, {@link #rank}, {@link #which}, {@link #natLiteralRank}, {@link #stringLiteralRank}
 *       and {@link #quotRank} answer; it is published with {@link Store#loadedUnits()}. The literal
 *       ranks are what the node derivation copies into the {@code depMax} of literal nodes, so they
 *       do not depend on how far the environment thread has come.
 *   <li><b>Environment half</b>: the guard state per special, {@link #ABSENT} until the environment
 *       pass evaluated the shape guard of the declaration, then {@link #VALID} or {@link #INVALID}
 *       (declared with another shape: using the extension makes the engine DECLINE, it neither
 *       accepts nor rejects on that basis). Written with {@link #bind} / {@link #setState}; readable
 *       for units below {@link Store#envUnits()}.
 * </ul>
 * A slot is 0 until a declaration with that name is loaded.
 */
public final class Specials {

    public static final int ABSENT = 0;
    public static final int VALID = 1;
    public static final int INVALID = 2;

    /** Indices for {@link #cid(int)} / {@link #state(int)}. Order of the Nat functions = order in the 4.36 kernel. */
    public static final int NAT = 0;
    public static final int NAT_ZERO = 1;
    public static final int NAT_SUCC = 2;
    public static final int NAT_ADD = 3;
    public static final int NAT_SUB = 4;
    public static final int NAT_MUL = 5;
    public static final int NAT_POW = 6;
    public static final int NAT_GCD = 7;
    public static final int NAT_MOD = 8;
    public static final int NAT_DIV = 9;
    public static final int NAT_BEQ = 10;
    public static final int NAT_BLE = 11;
    public static final int NAT_LAND = 12;
    public static final int NAT_LOR = 13;
    public static final int NAT_XOR = 14;
    public static final int NAT_SHIFT_LEFT = 15;
    public static final int NAT_SHIFT_RIGHT = 16;
    public static final int BOOL = 17;
    public static final int BOOL_FALSE = 18;
    public static final int BOOL_TRUE = 19;
    public static final int STRING = 20;
    public static final int STRING_OF_LIST = 21;
    public static final int CHAR = 22;
    public static final int CHAR_OF_NAT = 23;
    public static final int LIST = 24;
    public static final int LIST_NIL = 25;
    public static final int LIST_CONS = 26;
    public static final int EQ = 27;
    public static final int EQ_REFL = 28;
    public static final int QUOT = 29;
    public static final int QUOT_MK = 30;
    public static final int QUOT_LIFT = 31;
    public static final int QUOT_IND = 32;
    public static final int EAGER_REDUCE = 33;
    public static final int COUNT = 34;

    /** Indices for {@link #annotationName(int)}: the type annotations stripped by {@link TermOps#consumeTypeAnnotations}. */
    public static final int ANN_OPT_PARAM = 0;
    public static final int ANN_AUTO_PARAM = 1;
    public static final int ANN_OUT_PARAM = 2;
    public static final int ANN_SEMI_OUT_PARAM = 3;

    private static final String[] NAMES = {
        "Nat", "Nat.zero", "Nat.succ", "Nat.add", "Nat.sub", "Nat.mul", "Nat.pow", "Nat.gcd", "Nat.mod", "Nat.div",
        "Nat.beq", "Nat.ble", "Nat.land", "Nat.lor", "Nat.xor", "Nat.shiftLeft", "Nat.shiftRight",
        "Bool", "Bool.false", "Bool.true", "String", "String.ofList", "Char", "Char.ofNat",
        "List", "List.nil", "List.cons", "Eq", "Eq.refl", "Quot", "Quot.mk", "Quot.lift", "Quot.ind", "eagerReduce",
    };
    private static final String[] ANNOTATIONS = {"optParam", "autoParam", "outParam", "semiOutParam"};

    /** The prerequisite names of Nat literals and of string literals (*agentic-note*: design-architecture-document, 2.1). */
    private static final int[] NAT_PREREQ = {NAT, NAT_ZERO, NAT_SUCC};
    private static final int[] STRING_PREREQ = {NAT, NAT_ZERO, NAT_SUCC, STRING, STRING_OF_LIST, CHAR, CHAR_OF_NAT,
        LIST_NIL, LIST_CONS};
    private static final int[] QUOT_PREREQ = {QUOT, QUOT_MK, QUOT_LIFT, QUOT_IND};

    private final int[] nameIds = new int[COUNT];
    private final int[] annotationIds = new int[ANNOTATIONS.length];
    /** which + 1 by name id, for the small name ids of the special names. */
    private final byte[] whichOfName;
    private final int[] cids = new int[COUNT];
    private final int[] ranks = new int[COUNT];
    private final byte[] states = new byte[COUNT];
    private int natLitRank = Integer.MAX_VALUE;
    private int strLitRank = Integer.MAX_VALUE;
    private int quotRank = Integer.MAX_VALUE;
    private DeclTable decls;

    /**
     * Interns the special names in {@code names}. Their ids are small when this runs on a fresh name
     * table (as {@link Store} does); it works on any table.
     */
    public Specials(NameTable names) {
        int max = 0;
        for (int i = 0; i < COUNT; i++) {
            nameIds[i] = intern(names, NAMES[i]);
            max = Math.max(max, nameIds[i]);
        }
        for (int i = 0; i < ANNOTATIONS.length; i++) {
            annotationIds[i] = intern(names, ANNOTATIONS[i]);
        }
        whichOfName = max < 4096 ? new byte[max + 1] : null;
        if (whichOfName != null) {
            for (int i = 0; i < COUNT; i++) {
                whichOfName[nameIds[i]] = (byte) (i + 1);
            }
        }
    }

    private static int intern(NameTable names, String dotted) {
        int id = NameTable.ANONYMOUS;
        for (String part : dotted.split("\\.")) {
            byte[] b = part.getBytes(StandardCharsets.UTF_8);
            id = names.internStr(id, b, 0, b.length);
        }
        return id;
    }

    void attach(DeclTable decls) {
        this.decls = decls;
    }

    /** Special index of the name, or -1. */
    public int indexOfName(int name) {
        byte[] w = whichOfName;
        if (w != null) {
            return name >= 0 && name < w.length ? w[name] - 1 : -1;
        }
        for (int i = 0; i < COUNT; i++) {
            if (nameIds[i] == name) {
                return i;
            }
        }
        return -1;
    }

    /** Constant id bound to special {@code which}, or 0. */
    public int cid(int which) {
        return cids[which];
    }

    /** Rank of the constant bound to special {@code which}; 0 if it is not bound. */
    public int rank(int which) {
        return ranks[which];
    }

    /** Guard state of special {@code which}: {@link #ABSENT} until the environment pass evaluated it. */
    public int state(int which) {
        return states[which];
    }

    /** Special index of constant {@code cid}, or -1. O(1): one load from the constant's row. */
    public int which(int cid) {
        DeclTable d = decls;
        if (d != null) {
            return d.special(cid);
        }
        for (int i = 0; i < COUNT; i++) {
            if (cids[i] == cid) {
                return i;
            }
        }
        return -1;
    }

    /** Name id of special {@code which}. (The special names are interned when the store is created.) */
    public int nameOf(int which) {
        return nameIds[which];
    }

    /** Name id of a type annotation ({@code ANN_*}): {@code optParam}, {@code autoParam}, {@code outParam}, {@code semiOutParam}. */
    public int annotationName(int which) {
        return annotationIds[which];
    }

    /**
     * Largest rank of the prerequisites of Nat literals ({@code Nat}, {@code Nat.zero},
     * {@code Nat.succ}); MAX_VALUE while they are not all declared. A Nat literal may be typed and
     * converted by units of a larger rank (if the guards are {@link #VALID}).
     */
    public int natLiteralRank() {
        return natLitRank;
    }

    /**
     * Largest rank of the prerequisites of string literals (those of Nat literals, {@code String},
     * {@code String.ofList}, {@code Char}, {@code Char.ofNat}, {@code List.nil}, {@code List.cons});
     * MAX_VALUE while they are not all declared.
     */
    public int stringLiteralRank() {
        return strLitRank;
    }

    /** Largest rank of the four quot constants (quotient reduction exists for later units); MAX_VALUE if they are not all declared. */
    public int quotRank() {
        return quotRank;
    }

    /** Loader thread ({@link DeclTable#addConstant}): a declaration claimed the name of special {@code which}. */
    void bindLoaded(int which, int cid, int rank) {
        cids[which] = cid;
        ranks[which] = rank;
        natLitRank = maxRank(NAT_PREREQ);
        strLitRank = maxRank(STRING_PREREQ);
        quotRank = maxRank(QUOT_PREREQ);
    }

    private int maxRank(int[] prereq) {
        int max = 0;
        for (int w : prereq) {
            if (cids[w] == 0) {
                return Integer.MAX_VALUE;
            }
            max = Math.max(max, ranks[w]);
        }
        return max;
    }

    /**
     * Environment thread: records the guard result of special {@code which}. The constant was bound
     * by the loader; {@code cid} must be that constant (a table without a loader half, as in unit
     * tests, is bound here with rank 0).
     */
    public void bind(int which, int cid, int state) {
        if (cids[which] == 0) {
            DeclTable d = decls;
            bindLoaded(which, cid, d != null && cid > 0 && cid <= d.constantCount() ? d.rank(cid) : 0);
        } else if (cids[which] != cid) {
            throw new IllegalStateException("special " + NAMES[which] + " is constant " + cids[which] + ", not " + cid);
        }
        states[which] = (byte) state;
    }

    /** Environment thread: records the guard result of an already bound special. */
    public void setState(int which, int state) {
        states[which] = (byte) state;
    }

    /** The Lean name of special {@code which}, for diagnostics. */
    public static String nameString(int which) {
        return NAMES[which];
    }
}
