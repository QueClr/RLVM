package rlvm.api;

import rlvm.store.TermOps;
import rlvm.store.Terms;

/**
 * The type checker's judgments on term ids, with the official kernel's meaning
 * (*agentic-note*: study-kernel-typechecker). Admission code is written against this interface
 * and runs unchanged on the reference kernel or on the machine.
 *
 * <p>Terms are ids valid in {@link #terms()}: shared ids and ids of the session's overlay. Result
 * terms are built in that overlay. Free variables are {@code Kind.FVAR} nodes made by
 * {@link #mkLocal(int)}; a term handed to these methods may contain them but no loose bound variable
 * at the root.
 *
 * <p>Errors: {@link KernelException} for a kernel error (the declaration is not admissible),
 * {@link ResourceException} for "cannot decide".
 *
 * <p>Syntactic output: the reference kernel's {@link #whnf(int)} returns exactly the term the
 * official substitution-based algorithm returns (needed by trap T4 of inductive admission). An engine
 * that cannot guarantee this must say so through {@link #whnfIsSyntactic()}.
 */
public interface KernelOps {

    /**
     * Opens the scope of one declaration: constants of rank below {@code rank} are visible,
     * {@code levelParams} (a list id of names) are the declared universe parameters for check mode.
     * Drops facts that are only valid inside the previous scope.
     */
    void begin(int rank, int levelParams);

    /**
     * Additionally makes visible the constants of the unit of rank {@code rank} whose kind bit
     * ({@code 1 << DeclKind.X}) is set in {@code kindMask}; inductive admission widens this as the
     * official kernel adds the block's types, then constructors, then recursors.
     */
    void exposeUnitMembers(int kindMask);

    /** Type of a term assumed well typed (the official infer-only mode). */
    int infer(int term);

    /** Type of a term, checking it completely (the official {@code check}). */
    int check(int term);

    int whnf(int term);

    /** Weak head normal form without delta and without the Nat extension. */
    int whnfCore(int term);

    boolean isDefEq(int a, int b);

    /** Level id {@code l} such that {@code term} is, or reduces to, {@code Sort l}; else TYPE_EXPECTED. */
    int ensureSort(int term);

    /** A PI term that {@code term} is or reduces to; else FUNCTION_EXPECTED. */
    int ensurePi(int term);

    /**
     * The throwing, level-normalising test of the official {@code is_prop}: true iff the sort of
     * {@code infer(type)} has a level that normalises to zero; a sort that does not reduce to a sort
     * is a kernel error, not "false".
     */
    boolean isProp(int type);

    /** A fresh free variable of the given type. */
    int mkLocal(int type);

    Terms terms();

    TermOps termOps();

    LevelOps levels();

    /** True if {@link #whnf(int)} and {@link #whnfCore(int)} return the official algorithm's exact term. */
    boolean whnfIsSyntactic();

    /** Steps executed so far (for budgets and statistics). */
    long steps();
}
