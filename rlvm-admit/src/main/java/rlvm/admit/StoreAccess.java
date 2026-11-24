package rlvm.admit;

import rlvm.store.DeclTable;

/**
 * The few calls into {@code rlvm-store} whose signatures were settled after the first skeleton
 * (environment-side lists, critic finding C1.13; the claims blob), kept in one place.
 */
final class StoreAccess {
    private StoreAccess() {}

    /** The claims blob the loader attached to an inductive unit ({@code StoreBuilder.blockClaims}), or null. */
    static int[] claims(DeclTable decls, int unit) {
        return decls.claims(unit);
    }

    /** Frees the claims blob once the unit is decided. */
    static void dropClaims(DeclTable decls, int unit) {
        decls.dropClaims(unit);
    }

    /** Derived facts of an inductive type; the constructor and block lists are arrays of cids. */
    static void setInductive(DeclTable decls, int cid, int numParams, int numIndices, int[] ctors, int ctorCount,
            int[] all, int allCount, int flags, int numNested, int recCid) {
        decls.setInductive(cid, numParams, numIndices, ctors, ctorCount, all, allCount, flags, numNested, recCid);
    }

    /** Derived facts of a recursor; {@code rules} holds {@code ruleCount} triples (ctor cid, nfields, rhs term). */
    static void setRecursor(DeclTable decls, int cid, int numParams, int numIndices, int numMotives, int numMinors,
            int flags, int[] rules, int ruleCount, int majorInduct) {
        decls.setRecursor(cid, numParams, numIndices, numMotives, numMinors, flags, rules, ruleCount, majorInduct);
    }

    /** The {@code i}-th constructor cid of an admitted inductive type. */
    static int inductCtor(DeclTable decls, int cid, int i) {
        return decls.inductCtor(cid, i);
    }
}
