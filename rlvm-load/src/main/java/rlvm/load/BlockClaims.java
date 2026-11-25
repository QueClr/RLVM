package rlvm.load;

/**
 * Layout of the claims blob of an inductive unit: everything the {@code inductive} record of the
 * export says beyond the name, universe parameters and type of each member (those are in the
 * declaration rows). Written by the {@link Loader}, read by admission, dropped after the comparison.
 *
 * <p>All entries are ints. Names are store name ids, lists are list ids of the run's list store,
 * terms are shared term ids, and every natural number of the format that may be large is a pair of
 * ints (low word first; see {@link #getLong}), so that a hostile count never has to be truncated.
 *
 * <pre>
 * header, {@link #HEADER} ints:   VERSION | numTypes | numCtors | numRecs | offset of the first recursor entry | 0 | 0 | 0
 * numTypes x {@link #TYPE_WORDS} ints, in file order (the order of the official reader):
 *     cid | numParams (2) | numIndices (2) | numNested (2) | flags | all (name list) | ctors (name list) | 0 | 0
 * numCtors x {@link #CTOR_WORDS} ints, in file order:
 *     cid | induct (name) | cidx (2) | numParams (2) | numFields (2) | flags | 0 | 0 | 0
 * numRecs entries of {@link #REC_WORDS} + {@link #RULE_WORDS} x numRules ints, in file order:
 *     cid | all (name list) | numParams (2) | numIndices (2) | numMotives (2) | numMinors (2) | flags | numRules | 0 x 4
 *     then per rule: ctor (name) | nfields (2) | rhs (term)
 * </pre>
 * Inputs of admission in here: {@code numParams}, {@code all} and {@code ctors} of the types (and the
 * {@code cid}s, whose rows hold name, universe parameters and type). Everything else is a claim that is
 * either compared with the derived value or ignored (*agentic-note*: design-architecture-document, 4.6).
 *
 * <p>{@link #F_UNSAFE} on a constructor or recursor entry: the record was flagged {@code isUnsafe}
 * inside a block whose types are safe; the official checker neither replays nor compares such a
 * record, but still takes a constructor's name and type from it.
 */
public final class BlockClaims {
    private BlockClaims() {}

    public static final int VERSION = 1;

    public static final int HEADER = 8;
    public static final int H_VERSION = 0;
    public static final int H_NUM_TYPES = 1;
    public static final int H_NUM_CTORS = 2;
    public static final int H_NUM_RECS = 3;
    public static final int H_RECS_OFFSET = 4;

    public static final int TYPE_WORDS = 12;
    public static final int T_CID = 0;
    public static final int T_NUM_PARAMS = 1;
    public static final int T_NUM_INDICES = 3;
    public static final int T_NUM_NESTED = 5;
    public static final int T_FLAGS = 7;
    public static final int T_ALL = 8;
    public static final int T_CTORS = 9;

    public static final int CTOR_WORDS = 12;
    public static final int C_CID = 0;
    public static final int C_INDUCT = 1;
    public static final int C_CIDX = 2;
    public static final int C_NUM_PARAMS = 4;
    public static final int C_NUM_FIELDS = 6;
    public static final int C_FLAGS = 8;

    public static final int REC_WORDS = 16;
    public static final int R_CID = 0;
    public static final int R_ALL = 1;
    public static final int R_NUM_PARAMS = 2;
    public static final int R_NUM_INDICES = 4;
    public static final int R_NUM_MOTIVES = 6;
    public static final int R_NUM_MINORS = 8;
    public static final int R_FLAGS = 10;
    public static final int R_NUM_RULES = 11;

    public static final int RULE_WORDS = 4;
    public static final int RULE_CTOR = 0;
    public static final int RULE_NFIELDS = 1;
    public static final int RULE_RHS = 3;

    /** Type flag: exported {@code isRec}. Recursor flag: exported {@code k}. */
    public static final int F_REC = 1;
    public static final int F_K = 1;
    /** Type flag: exported {@code isReflexive}. */
    public static final int F_REFLEXIVE = 2;
    /** The record was flagged {@code isUnsafe}. */
    public static final int F_UNSAFE = 4;

    public static int numTypes(int[] blob) {
        return blob[H_NUM_TYPES];
    }

    public static int numCtors(int[] blob) {
        return blob[H_NUM_CTORS];
    }

    public static int numRecs(int[] blob) {
        return blob[H_NUM_RECS];
    }

    /** Offset of type entry {@code i}. */
    public static int typeAt(int[] blob, int i) {
        return HEADER + i * TYPE_WORDS;
    }

    /** Offset of constructor entry {@code i}. */
    public static int ctorAt(int[] blob, int i) {
        return HEADER + blob[H_NUM_TYPES] * TYPE_WORDS + i * CTOR_WORDS;
    }

    /** Offset of the first recursor entry (or the blob length if there is none). */
    public static int firstRec(int[] blob) {
        return blob[H_RECS_OFFSET];
    }

    /** Offset of the recursor entry that follows the one at {@code recOffset}. */
    public static int nextRec(int[] blob, int recOffset) {
        return recOffset + REC_WORDS + blob[recOffset + R_NUM_RULES] * RULE_WORDS;
    }

    /** Offset of rule {@code rule} of the recursor entry at {@code recOffset}. */
    public static int ruleAt(int recOffset, int rule) {
        return recOffset + REC_WORDS + rule * RULE_WORDS;
    }

    /** The natural number stored at {@code blob[offset], blob[offset + 1]} (0 .. 2^63 - 1). */
    public static long getLong(int[] blob, int offset) {
        return (blob[offset] & 0xFFFF_FFFFL) | ((long) blob[offset + 1] << 32);
    }

    static void putLong(int[] blob, int offset, long value) {
        blob[offset] = (int) value;
        blob[offset + 1] = (int) (value >>> 32);
    }
}
