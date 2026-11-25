package rlvm.admit;

/**
 * Admission policies (*agentic-note*: design-architecture-document, 4.9). Immutable; {@link #arena()} is the arena configuration.
 *
 * <p>Policies that need no knob because they are fixed: unsafe and partial records are dropped by the
 * loader and their users fail with "unknown constant"; {@code Lean.reduceBool}, {@code reduceNat},
 * {@code ofReduceBool} and {@code trustCompiler} are ordinary constants; malformed input is rejected
 * by the reader and the loader; the header never decides.
 */
public final class Policy {

    /** What to do with a declaration that references a constant declared later in the file. */
    public enum ForwardRefs {
        /**
         * Exit 2. The v1 behaviour. Only the reference of a declaration to itself is rejected (the
         * official replay rejects every cycle; detecting longer ones is left to {@link #REORDER}).
         */
        DECLINE,
        /**
         * Admit in dependency order like the official replay ({@code Lean.Replay}): from the first unit
         * of a completely loaded file that file order cannot decide, the environment pass admits every
         * unit itself, dependencies first; a cycle is rejected; where the official verdict depends on
         * the official checker's order of independent declarations (the hash order of their names),
         * that order is simulated ({@code OfficialOrder}), also for a small file in declaration order.
         * Exporter output is admitted exactly as with {@link #DECLINE}.
         */
        REORDER
    }

    /** What to report when the machine rejects a unit and the reference kernel could not finish the re-check. */
    public enum Unconfirmed {
        DECLINE,
        REJECT
    }

    /**
     * Accept every well-typed axiom (the official behaviour). False: an axiom the engine accepted is
     * declined unless it is {@code propext}, {@code Quot.sound}, {@code Classical.choice} or {@code sorryAx}.
     */
    public final boolean allowAllAxioms;
    public final ForwardRefs forwardRefs;
    public final Unconfirmed unconfirmedReject;
    /**
     * Shape guards on Nat, Bool, String and the accelerated functions (level L1): a special constant
     * declared with another shape gets the guard state INVALID and an engine that needs it declines.
     * False = level L0, official: every declared special is VALID.
     */
    public final boolean preludeGuards;
    /**
     * Skip the defensive type checks of derived recursors when the exported record was identical
     * (measured optimisation, off by default). No effect yet: {@code rlvm-inductive} has no entry
     * point for it.
     */
    public final boolean trustMatchingRecursors;

    public Policy(boolean allowAllAxioms, ForwardRefs forwardRefs, Unconfirmed unconfirmedReject,
            boolean preludeGuards, boolean trustMatchingRecursors) {
        this.allowAllAxioms = allowAllAxioms;
        this.forwardRefs = forwardRefs;
        this.unconfirmedReject = unconfirmedReject;
        this.preludeGuards = preludeGuards;
        this.trustMatchingRecursors = trustMatchingRecursors;
    }

    public static Policy arena() {
        return new Policy(true, ForwardRefs.REORDER, Unconfirmed.DECLINE, true, false);
    }
}
