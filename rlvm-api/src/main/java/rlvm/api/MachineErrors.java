package rlvm.api;

/**
 * The machine's error codes ({@code E_*} of *agentic-note*: design-isa-document, sections 4 and 9) and their
 * mapping to the facade's {@link Reason}s (*agentic-note*: design-reconciliation-document, section 2, "Facade" and "Faults inside
 * TRY"). Codes are small {@code int}s so that a machine can keep one in a register; an engine is
 * free to use them directly or to translate its own enum once, at the facade.
 *
 * <p>Three classes:
 * <ul>
 *   <li><b>kernel errors</b> ({@link #isKernelError}): what the official kernel raises. Outcome
 *       REJECT; inside a {@code TRY} they are recoverable (the probe ends with {@code U}).
 *       {@link #E_NATSIZE} is in this class: the Nat-size guard mirrors the official REJECT (the
 *       reference kernel confirms it like every other rejection).</li>
 *   <li><b>limits</b> ({@link #isLimit}): budget, capacity and prelude-guard declines. Outcome
 *       DECLINE. {@link #E_STEPS} and {@link #E_LIMIT} are recoverable inside a {@code TRY}.</li>
 *   <li><b>fatal</b> ({@link #isFatal}): {@code E_HOLE}, {@code E_VIS}, {@code E_ILLTYPED},
 *       {@code E_INTERNAL}, a failed audit, the first-failure signal and the memory watermark cut to
 *       {@code DECL} whatever {@code tryp} is: DECLINE and session reset.</li>
 * </ul>
 */
public final class MachineErrors {
    private MachineErrors() {}

    public static final int E_NONE = 0;

    // ---- kernel errors: REJECT ---------------------------------------------------------------------
    public static final int E_TYPE_EXPECTED = 1;
    public static final int E_FUN_EXPECTED = 2;
    public static final int E_APP_MISMATCH = 3;
    public static final int E_LET_MISMATCH = 4;
    /** Declared type against inferred type ({@code CHECK}, {@code TRET}, the root of {@code DECL}). */
    public static final int E_TYPE_MISMATCH = 5;
    public static final int E_PROJ = 6;
    public static final int E_UNKNOWN_CONST = 7;
    public static final int E_LEVEL_ARITY = 8;
    public static final int E_LEVEL_PARAM = 9;
    /** Numeral bound, {@code Nat.pow} / {@code Nat.shiftLeft} guards (official: kernel exception). */
    public static final int E_NATSIZE = 10;
    /** A theorem whose type is not a proposition. */
    public static final int E_NOT_PROP = 11;
    public static final int E_LOOSE_BVAR = 12;

    // ---- limits: DECLINE ---------------------------------------------------------------------------
    public static final int E_STEPS = 16;
    /** Session memory share, or the run's memory watermark. */
    public static final int E_MEM = 17;
    /** Worklist capacity. */
    public static final int E_STACK = 18;
    /** Spine length, open binders and similar representation limits. */
    public static final int E_LIMIT = 19;
    /** A prelude guard is INVALID for an extension that is needed. */
    public static final int E_PRELUDE = 20;

    // ---- fatal: DECLINE and session reset ----------------------------------------------------------
    public static final int E_HOLE = 24;
    public static final int E_VIS = 25;
    public static final int E_ILLTYPED = 26;
    public static final int E_INTERNAL = 27;
    /** Memo check or id poisoning found a disagreement. */
    public static final int E_AUDIT = 28;
    /** {@link RunSignals#firstFailure()} is below the unit being checked. */
    public static final int E_CANCEL = 29;

    /** One more than the largest code. */
    public static final int COUNT = 30;

    private static final Reason[] REASONS = new Reason[COUNT];
    private static final String[] NAMES = new String[COUNT];

    private static void def(int code, String name, Reason reason) {
        REASONS[code] = reason;
        NAMES[code] = name;
    }

    static {
        def(E_TYPE_EXPECTED, "E_TYPE_EXPECTED", Reason.TYPE_EXPECTED);
        def(E_FUN_EXPECTED, "E_FUN_EXPECTED", Reason.FUNCTION_EXPECTED);
        def(E_APP_MISMATCH, "E_APP_MISMATCH", Reason.APP_TYPE_MISMATCH);
        def(E_LET_MISMATCH, "E_LET_MISMATCH", Reason.LET_TYPE_MISMATCH);
        def(E_TYPE_MISMATCH, "E_TYPE_MISMATCH", Reason.DECL_TYPE_MISMATCH);
        def(E_PROJ, "E_PROJ", Reason.INVALID_PROJECTION);
        def(E_UNKNOWN_CONST, "E_UNKNOWN_CONST", Reason.UNKNOWN_CONSTANT);
        def(E_LEVEL_ARITY, "E_LEVEL_ARITY", Reason.LEVEL_ARITY_MISMATCH);
        def(E_LEVEL_PARAM, "E_LEVEL_PARAM", Reason.UNDECLARED_LEVEL_PARAM);
        def(E_NATSIZE, "E_NATSIZE", Reason.NAT_LITERAL_TOO_BIG);
        def(E_NOT_PROP, "E_NOT_PROP", Reason.THEOREM_NOT_PROP);
        def(E_LOOSE_BVAR, "E_LOOSE_BVAR", Reason.LOOSE_BVAR);
        def(E_STEPS, "E_STEPS", Reason.BUDGET_STEPS);
        def(E_MEM, "E_MEM", Reason.BUDGET_MEMORY);
        def(E_STACK, "E_STACK", Reason.STACK_DEPTH);
        def(E_LIMIT, "E_LIMIT", Reason.UNSUPPORTED_INPUT);
        def(E_PRELUDE, "E_PRELUDE", Reason.UNSUPPORTED_PRELUDE);
        def(E_HOLE, "E_HOLE", Reason.INTERNAL_ERROR);
        def(E_VIS, "E_VIS", Reason.INTERNAL_ERROR);
        def(E_ILLTYPED, "E_ILLTYPED", Reason.INTERNAL_ERROR);
        def(E_INTERNAL, "E_INTERNAL", Reason.INTERNAL_ERROR);
        def(E_AUDIT, "E_AUDIT", Reason.INTERNAL_ERROR);
        def(E_CANCEL, "E_CANCEL", Reason.CANCELLED);
    }

    /** True for a code this table defines ({@link #E_NONE} is not an error). */
    public static boolean isDefined(int code) {
        return code > 0 && code < COUNT && REASONS[code] != null;
    }

    /**
     * The facade reason of a machine error. An unknown code maps to {@link Reason#INTERNAL_ERROR}: a
     * decline, never a rejection (fail closed).
     */
    public static Reason reason(int code) {
        return isDefined(code) ? REASONS[code] : Reason.INTERNAL_ERROR;
    }

    /** {@link Outcome#REJECT} for kernel errors, {@link Outcome#DECLINE} for everything else (also unknown codes). */
    public static int outcome(int code) {
        return reason(code).outcome();
    }

    public static boolean isKernelError(int code) {
        return code >= E_TYPE_EXPECTED && code <= E_LOOSE_BVAR;
    }

    public static boolean isLimit(int code) {
        return code >= E_STEPS && code <= E_PRELUDE;
    }

    /**
     * Fatal faults cut to {@code DECL} whatever {@code tryp} is. The memory watermark ({@link #E_MEM})
     * is fatal although it is a limit; unknown codes are fatal.
     */
    public static boolean isFatal(int code) {
        return !isRecoverableInTry(code);
    }

    /** Becomes {@code U} inside a {@code TRY}: kernel errors (incl. {@link #E_NATSIZE}), {@link #E_STEPS}, {@link #E_LIMIT}. */
    public static boolean isRecoverableInTry(int code) {
        return isKernelError(code) || code == E_STEPS || code == E_LIMIT;
    }

    public static String name(int code) {
        return isDefined(code) ? NAMES[code] : code == E_NONE ? "E_NONE" : "E_?(" + code + ")";
    }
}
