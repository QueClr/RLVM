package rlvm.store;

/** Constant kinds (bits 0..3 of word 0 of a {@link DeclTable} row) and unit status values. */
public final class DeclKind {
    private DeclKind() {}

    public static final int NONE = 0;
    public static final int AXIOM = 1;
    public static final int DEFN = 2;
    public static final int THM = 3;
    public static final int OPAQUE = 4;
    public static final int INDUCT = 5;
    public static final int CTOR = 6;
    public static final int REC = 7;
    public static final int QUOT_TYPE = 8;
    public static final int QUOT_MK = 9;
    public static final int QUOT_LIFT = 10;
    public static final int QUOT_IND = 11;

    /** Reducibility hint encoding of word 6 for DEFN (THM counts as opaque). Larger unfolds first. */
    public static final int HINT_OPAQUE = 0;
    public static final int HINT_ABBREV = Integer.MAX_VALUE;

    public static int hintRegular(long height) {
        return (int) Math.min(height, Integer.MAX_VALUE - 2L) + 1;
    }

    // Unit status (DeclTable.status). Transitions: LOADED -> ENV_READY -> one of the final states.
    public static final int ST_LOADED = 0;
    /** Derived facts of the unit (and of all earlier units) are published. */
    public static final int ST_ENV_READY = 1;
    public static final int ST_RUNNING = 2;
    public static final int ST_ACCEPTED = 3;
    public static final int ST_REJECTED = 4;
    public static final int ST_DECLINED = 5;
    /** Unsafe or partial record: not admitted, not checked. */
    public static final int ST_SKIPPED = 6;
}
