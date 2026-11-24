package rlvm.api;

import rlvm.store.IntLists;

/**
 * Universe levels as hash-consed {@code int} ids: the adapter over {@code rlvm.level.LevelStore}
 * (frozen shared base plus one overlay per worker). Level id 0 is "none"; ids are stable for shared
 * levels and session-local for levels a worker creates.
 *
 * <p>{@link #isEquiv} / {@link #isGeq} are the complete semantic relations (a superset of what the
 * official normal form decides, which is sound); the {@code mk*} constructors are the official
 * simplifying ones and must be used wherever the kernel's generated terms are compared syntactically.
 *
 * <p><b>Substitutions</b> (RECONCILIATION.md B1). The universe instantiation of a constant is a
 * <em>substitution id</em>: {@code mkSubst(lparams(row), levels)}. It, not the bare level list, is
 * what closures, suspensions, memo keys and block calls carry: two constants that share a body term
 * under differently ordered parameter lists get different substitution ids for the same level list.
 * 0 is the identity substitution (also the empty one). Substitution ids of a worker die with
 * {@link #reset()}.
 *
 * <p>The methods added after the first skeleton are {@code default} methods so that hand-written
 * fakes keep compiling; the real implementation ({@code rlvm.api.bind.LevelBinding}) overrides all
 * of them.
 */
public interface LevelOps {

    /** Level kinds, numbered like the official {@code level_kind} (and like {@code rlvm.level.LevelStore}). */
    int KIND_ZERO = 0;
    int KIND_SUCC = 1;
    int KIND_MAX = 2;
    int KIND_IMAX = 3;
    int KIND_PARAM = 4;

    int zero();

    int succ(int level);

    /** Plain constructors (no simplification), as the export's records. */
    int max(int a, int b);

    int imax(int a, int b);

    int param(int nameId);

    /** The official {@code mk_max} / {@code mk_imax} (simplifying). */
    int mkMax(int a, int b);

    int mkIMax(int a, int b);

    boolean isEquiv(int a, int b);

    boolean isGeq(int a, int b);

    /** The official {@code normalizes_to_zero}: zero under every assignment. */
    boolean isAlwaysZero(int level);

    /** The official {@code is_not_zero}: never zero under any assignment. */
    boolean isNeverZero(int level);

    /** Pointwise {@link #isEquiv} on two level lists of equal length. */
    boolean listsEquiv(int listA, int listB);

    /**
     * {@code level[paramNames := levels]} with the simplifying constructors; identity if nothing changes.
     * The two lists must have the same length (established per root by the structural checks); if
     * they do not, the binding throws {@link KernelException} with {@link Reason#LEVEL_ARITY_MISMATCH}.
     */
    int instantiate(int level, int paramNames, int levels);

    int instantiateList(int levelList, int paramNames, int levels);

    /** True if every parameter of {@code level} occurs in the name list {@code paramNames}. */
    boolean paramsDeclared(int level, int paramNames);

    IntLists lists();

    /** Forgets the levels, lists and substitutions this worker created (session reset). */
    void reset();

    // ---- substitution ids (RECONCILIATION.md B1) ---------------------------------------------------

    /**
     * The substitution {@code paramNames[i] := levels[i]} as an id.
     *
     * @return 0 for the empty and for the identity substitution, -1 if the two lists differ in length
     *     (the caller rejects with {@link Reason#LEVEL_ARITY_MISMATCH}), otherwise a positive id; equal
     *     (names, levels) pairs give equal ids within one session
     */
    default int mkSubst(int paramNames, int levels) {
        throw new UnsupportedOperationException("LevelOps.mkSubst is not implemented by " + getClass().getName());
    }

    /** The parameter-name list of a substitution made by {@link #mkSubst} (not for 0 or -1). */
    default int substParams(int subst) {
        throw new UnsupportedOperationException("LevelOps.substParams is not implemented by " + getClass().getName());
    }

    /** The level list of a substitution made by {@link #mkSubst} (not for 0 or -1). */
    default int substLevels(int subst) {
        throw new UnsupportedOperationException("LevelOps.substLevels is not implemented by " + getClass().getName());
    }

    /**
     * The official {@code instantiate} under substitution {@code subst} (0 = identity): parameters are
     * replaced, unknown parameters stay, rebuilt {@code max}/{@code imax} nodes go through the
     * simplifying constructors, an unchanged level is returned as is. Cached per (level, subst).
     */
    default int instantiate(int level, int subst) {
        throw new UnsupportedOperationException("LevelOps.instantiate(level, subst) is not implemented by "
                + getClass().getName());
    }

    /** {@link #instantiate(int, int)} on every element of a level list; the same id if nothing changes. */
    default int instantiateList(int levelList, int subst) {
        throw new UnsupportedOperationException("LevelOps.instantiateList(list, subst) is not implemented by "
                + getClass().getName());
    }

    // ---- structure (for the reference kernel, admission and diagnostics) ---------------------------

    /** One of the {@code KIND_*} constants. */
    default int kind(int level) {
        throw new UnsupportedOperationException("LevelOps.kind is not implemented by " + getClass().getName());
    }

    /** Operand of a {@code succ}. */
    default int succOf(int level) {
        throw new UnsupportedOperationException("LevelOps.succOf is not implemented by " + getClass().getName());
    }

    /** Left operand of a {@code max} / {@code imax}. */
    default int lhs(int level) {
        throw new UnsupportedOperationException("LevelOps.lhs is not implemented by " + getClass().getName());
    }

    /** Right operand of a {@code max} / {@code imax}. */
    default int rhs(int level) {
        throw new UnsupportedOperationException("LevelOps.rhs is not implemented by " + getClass().getName());
    }

    /** Name id of a {@code param}. */
    default int paramName(int level) {
        throw new UnsupportedOperationException("LevelOps.paramName is not implemented by " + getClass().getName());
    }

    /** The syntactic {@code is_zero}: the level is the node {@code zero}. */
    default boolean isZero(int level) {
        return level == zero();
    }

    /** True if the level mentions a universe parameter. */
    default boolean hasParam(int level) {
        throw new UnsupportedOperationException("LevelOps.hasParam is not implemented by " + getClass().getName());
    }

    /**
     * The official, incomplete relations ({@code is_equivalent} / {@code is_geq} on the official normal
     * form). Only for code that must mirror the official kernel exactly; everything else uses
     * {@link #isEquiv} / {@link #isGeq}, which accept a superset.
     */
    default boolean isEquivOfficial(int a, int b) {
        throw new UnsupportedOperationException("LevelOps.isEquivOfficial is not implemented by "
                + getClass().getName());
    }

    default boolean isGeqOfficial(int a, int b) {
        throw new UnsupportedOperationException("LevelOps.isGeqOfficial is not implemented by " + getClass().getName());
    }

    /**
     * 0 if every parameter of {@code level} occurs in {@code paramNames}, else the offending
     * {@code param} level (for the error message of {@link Reason#UNDECLARED_LEVEL_PARAM}).
     */
    default int undeclaredParam(int level, int paramNames) {
        throw new UnsupportedOperationException("LevelOps.undeclaredParam is not implemented by "
                + getClass().getName());
    }

    /** Diagnostics only: {@code max (u+1) v}, {@code imax u v}, {@code 2}. */
    default String toString(int level) {
        return "level#" + level;
    }

    /** Bytes held by what this worker created (for the session footprint); 0 if unknown. */
    default long footprintBytes() {
        return 0L;
    }
}
