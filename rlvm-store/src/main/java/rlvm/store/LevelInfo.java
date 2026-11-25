package rlvm.store;

/**
 * What the store needs to know about universe levels: an adapter over {@code rlvm.level.LevelStore}
 * (the store itself depends on nothing but the JDK). Level ids and level-list ids are opaque here.
 *
 * <p>The structural view ({@link #hasParam}, {@link #paramName}, {@link #lhs}, {@link #rhs}) is used
 * to derive {@code lpSet}, the set of universe-parameter names below a term node (*agentic-note*: design-architecture-document,
 * 2.1); the store walks a level iteratively and memoises the result itself, so an implementation is
 * four one-line delegations. The two {@code instantiate} methods serve
 * {@link TermOps#instantiateLevelParams}; their signatures are those of {@code rlvm.api.LevelOps}.
 *
 * <p>Over {@code rlvm.level.LevelStore ls}:
 * <pre>
 *   hasParam(l)   = ls.hasParam(l)
 *   paramName(l)  = ls.kind(l) == KIND_PARAM ? ls.paramName(l) : -1
 *   lhs(l)        = kind SUCC ? ls.succOf(l) : kind MAX / IMAX ? ls.lhs(l) : 0
 *   rhs(l)        = kind MAX / IMAX ? ls.rhs(l) : 0
 *   instantiate, instantiateList = the simplifying instantiation of the level store
 * </pre>
 * One instance per thread that writes term nodes: the loader's over the base level store, each
 * worker's over its level overlay.
 */
public interface LevelInfo {

    /** False only if no universe parameter occurs in {@code level} (a cheap pre-test; may always answer true). */
    boolean hasParam(int level);

    /** The name id if {@code level} is a parameter, else -1. */
    int paramName(int level);

    /** First operand: the predecessor of a successor, the left operand of max / imax; 0 if none. */
    int lhs(int level);

    /** Second operand of max / imax; 0 if none. */
    int rhs(int level);

    /** {@code level[paramNames := levels]} with the simplifying constructors; identity if nothing changes. */
    int instantiate(int level, int paramNames, int levels);

    /** Pointwise {@link #instantiate} on a level list; returns a level-list id. */
    int instantiateList(int levelList, int paramNames, int levels);

    /**
     * True if {@code level} is an id of the run's SHARED level store: it denotes the same level for
     * every thread and for the rest of the run (an id of the frozen base, not of a worker's level
     * overlay). The generated term region accepts only such ids ({@link GeneratedBuilder}). The
     * default answers false: an implementation that cannot tell declines to share.
     */
    default boolean isSharedLevel(int level) {
        return false;
    }
}
