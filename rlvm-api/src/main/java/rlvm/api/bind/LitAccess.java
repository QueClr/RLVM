package rlvm.api.bind;

/**
 * How {@link NatBinding} reads and builds literal terms: the bridge between NATLIT / STRLIT term ids
 * and their content. The production implementation is {@link StoreLitAccess} (over the worker's
 * {@code Terms}, {@code Overlay} and literal pool); tests use an array-backed fake.
 *
 * <p>Representation of naturals (that of {@code rlvm.store.Kind.NATLIT}):
 * <ul>
 *   <li><b>word literal</b>: the value is below 2^64 and stored in the node ({@link #natIsWord}).
 *       Every value below 2^64 is a word literal - never a big one - so that word literals are
 *       compared by value in O(1).</li>
 *   <li><b>big literal</b>: the node holds a non-zero reference ({@link #natRef}) into the worker's
 *       view of the literal pools (positive: shared pool, an export literal; negative: the worker's
 *       own pool, a computed number). The limbs are read with {@link #bigLimbCount} /
 *       {@link #bigLimbs}. Equal references mean equal numbers; different references usually mean
 *       different numbers but must be confirmed with {@link #bigEquals}.</li>
 * </ul>
 */
public interface LitAccess {

    /** True if NATLIT term {@code lit} holds its value (below 2^64) in the node. */
    boolean natIsWord(int lit);

    /** The unsigned 64-bit value of a word literal. */
    long natWord(int lit);

    /** The pool reference of a big literal (see the class comment); never 0. */
    int natRef(int lit);

    /** Number of 64-bit limbs of pool entry {@code ref} (top limb non-zero). */
    int bigLimbCount(int ref);

    /** Copies the little-endian limbs of pool entry {@code ref} to {@code dst[off ..]}. */
    void bigLimbs(int ref, long[] dst, int off);

    /** Content equality of two pool entries. */
    boolean bigEquals(int refA, int refB);

    /** The NATLIT term for an unsigned 64-bit value (in the worker's overlay; hash-consed). */
    int mkNatWord(long unsignedValue);

    /**
     * The NATLIT term for the number {@code limbs[from .. from+len)} (little-endian, {@code len >= 2},
     * top limb non-zero): interns the limbs in the worker's pool and builds the node in its overlay.
     * The array is only read during the call.
     */
    int mkNatBig(long[] limbs, int from, int len);

    /**
     * The number of natural entries of the worker's OWN pool (the negative references), or -1 if this
     * bridge has no worker pool or cannot tell. {@link NatBinding} watches it: a value that differs
     * from the one it saw after its own last addition means the pool was truncated or reset behind
     * its back ({@code LitPool.truncateTo}, {@code Overlay.resetTo}), so negative references may
     * have been released or reissued, and the binding forgets what it cached under them.
     */
    default int workerNatCount() {
        return -1;
    }

    /** Number of Unicode scalar values of STRLIT term {@code strLit}. */
    int strLength(int strLit);

    /** The {@code index}-th scalar value of STRLIT term {@code strLit}. */
    int strCodePoint(int strLit, int index);

    /** True if the two STRLIT terms have the same bytes. */
    boolean strEquals(int strLitA, int strLitB);
}
