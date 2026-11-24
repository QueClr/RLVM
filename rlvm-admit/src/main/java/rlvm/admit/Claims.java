package rlvm.admit;

/**
 * Layout of the claims blob of an inductive unit: the untrusted remainder of an {@code inductive}
 * record (counts, flags, name lists, rule tables), written by {@code rlvm-load} with
 * {@code StoreBuilder.blockClaims} and read here to fill a {@code BlockInput}.
 *
 * <p>Layout version 1 (agreed in {@code work/status/requests.log}, entry "(4) CLAIMS BLOB"); all
 * ints; names are store name ids, lists are {@code IntLists} ids, terms are shared term ids, a long
 * is two ints (low, high):
 *
 * <pre>
 * [0] = 1 (layout version)  [1] numTypes  [2] numCtors  [3] numRecs  [4] offset of the first rec entry  [5..7] 0
 * numTypes x 12 ints from 8:   cid | numParams lo,hi | numIndices lo,hi | numNested lo,hi
 *                              | flags (1 isRec, 2 isReflexive, 4 isUnsafe) | all (name list) | ctors (name list) | 0 | 0
 * numCtors x 12 ints:          cid | induct name | cidx lo,hi | numParams lo,hi | numFields lo,hi
 *                              | flags (4 isUnsafe) | 0 | 0 | 0
 * per recursor 16 + 4 * numRules ints, from [4]:
 *                              cid | all (name list) | numParams lo,hi | numIndices lo,hi | numMotives lo,hi
 *                              | numMinors lo,hi | flags (1 k, 4 isUnsafe) | numRules | 0 x 4
 *                              | numRules x (ctor name | nfields lo,hi | rhs term)
 * </pre>
 * Name, universe parameters and type of every member are in its declaration row ({@code cid}).
 *
 * <p>This class is a private copy of the offsets ({@code rlvm-admit} must not depend on
 * {@code rlvm-load}); it trusts nothing: {@link #validate(int[])} checks every length before a field
 * is read.
 */
final class Claims {
    private Claims() {}

    static final int VERSION = 1;

    static final int H_VERSION = 0;
    static final int H_TYPES = 1;
    static final int H_CTORS = 2;
    static final int H_RECS = 3;
    static final int H_REC_OFFSET = 4;
    static final int HEADER_WORDS = 8;

    static final int TYPE_WORDS = 12;
    static final int T_CID = 0;
    static final int T_NUM_PARAMS = 1;
    static final int T_NUM_INDICES = 3;
    static final int T_NUM_NESTED = 5;
    static final int T_FLAGS = 7;
    static final int T_ALL = 8;
    static final int T_CTORS = 9;

    static final int CTOR_WORDS = 12;
    static final int C_CID = 0;
    static final int C_INDUCT = 1;
    static final int C_CIDX = 2;
    static final int C_NUM_PARAMS = 4;
    static final int C_NUM_FIELDS = 6;
    static final int C_FLAGS = 8;

    static final int REC_WORDS = 16;
    static final int R_CID = 0;
    static final int R_ALL = 1;
    static final int R_NUM_PARAMS = 2;
    static final int R_NUM_INDICES = 4;
    static final int R_NUM_MOTIVES = 6;
    static final int R_NUM_MINORS = 8;
    static final int R_FLAGS = 10;
    static final int R_NUM_RULES = 11;

    static final int RULE_WORDS = 4;
    static final int RULE_CTOR = 0;
    static final int RULE_NFIELDS = 1;
    static final int RULE_RHS = 3;

    static final int F_IS_REC = 1;
    static final int F_IS_REFLEXIVE = 2;
    static final int F_UNSAFE = 4;
    /** Recursor entries: the exported {@code k}. */
    static final int F_K = 1;

    static long getLong(int[] blob, int at) {
        return (blob[at] & 0xFFFF_FFFFL) | ((long) blob[at + 1] << 32);
    }

    static int typeEntry(int index) {
        return HEADER_WORDS + TYPE_WORDS * index;
    }

    static int ctorEntry(int[] blob, int index) {
        return HEADER_WORDS + TYPE_WORDS * blob[H_TYPES] + CTOR_WORDS * index;
    }

    /** Offset of the first recursor entry. */
    static int firstRec(int[] blob) {
        return blob[H_REC_OFFSET];
    }

    /** Offset of the entry after the recursor entry at {@code at}. */
    static int nextRec(int[] blob, int at) {
        return at + REC_WORDS + RULE_WORDS * blob[at + R_NUM_RULES];
    }

    /**
     * Checks that the blob has the announced shape.
     *
     * @return null if every entry lies inside the array, else a description of the defect
     */
    static String validate(int[] blob) {
        if (blob == null) {
            return "the inductive unit has no claims blob";
        }
        if (blob.length < HEADER_WORDS) {
            return "claims blob shorter than its header";
        }
        if (blob[H_VERSION] != VERSION) {
            return "claims blob layout version " + blob[H_VERSION] + ", expected " + VERSION;
        }
        long nt = blob[H_TYPES];
        long nc = blob[H_CTORS];
        long nr = blob[H_RECS];
        if (nt < 0 || nc < 0 || nr < 0) {
            return "negative count in the claims blob";
        }
        long fixed = HEADER_WORDS + TYPE_WORDS * nt + CTOR_WORDS * nc;
        if (fixed > blob.length) {
            return "claims blob shorter than its type and constructor entries";
        }
        long at = blob[H_REC_OFFSET];
        if (at < fixed || at > blob.length) {
            return "recursor offset of the claims blob out of range";
        }
        for (long j = 0; j < nr; j++) {
            if (at + REC_WORDS > blob.length) {
                return "claims blob shorter than its recursor entries";
            }
            long rules = blob[(int) at + R_NUM_RULES];
            if (rules < 0) {
                return "negative rule count in the claims blob";
            }
            at += REC_WORDS + RULE_WORDS * rules;
            if (at > blob.length) {
                return "claims blob shorter than its recursor rules";
            }
        }
        return null;
    }
}
