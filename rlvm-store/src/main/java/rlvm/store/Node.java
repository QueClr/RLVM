package rlvm.store;

/**
 * Bit-level layout of a term node and of term ids. A node is 8 consecutive {@code int}s (32 bytes,
 * half a cache line) of one flat {@code int[]} per region; all facts every consumer needs sit in the
 * node itself. Flat, not chunked: a chunk directory costs about 25 % more instructions per access
 * (*agentic-note*: study-memory-reuse-parallelism, B.3).
 *
 * <pre>
 * word 0  header   bits 0..3 kind ({@link Kind}) | bits 4..15 flags (F_*) | bits 16..31 looseRange
 * word 1  a        payload, by kind
 * word 2  b
 * word 3  c
 * word 4  fvMask low   bit i (i &lt; 63): de Bruijn index i occurs free; bit 63: some index &gt;= 63 does
 * word 5  fvMask high
 * word 6  depMax   largest rank of a constant in the sub-DAG; 0 = none; DEP_UNRESOLVED = unknown
 * word 7  lpSet    {@link IntLists} id of the sorted set of universe-parameter names in the sub-DAG
 *
 * term id  bit 31 = 0 always (negative ints are free for the machine's own tagging)
 *          1 .. EXPORT_MAX            export region   (shared; written by the loader thread)
 *          GEN_BASE | i               generated region (shared; written by the environment thread; normally empty)
 *          OVERLAY_BIT | i            overlay          (worker-local scratch)
 *          first word of node id = (id &amp; INDEX_MASK) &lt;&lt; 3 in the region's array
 * </pre>
 *
 * {@code looseRange} is "largest loose index + 1" (0 = closed), saturating at 0xFFFF with
 * {@link #F_LOOSE_SAT}. Derivation rules for looseRange, fvMask, depMax and lpSet are in
 * *agentic-note*: design-architecture-document, section 2.1; they are computed once, when the node is written.
 */
public final class Node {
    private Node() {}

    public static final int WORDS = 8;
    public static final int W_HEADER = 0;
    public static final int W_A = 1;
    public static final int W_B = 2;
    public static final int W_C = 3;
    public static final int W_MASK_LO = 4;
    public static final int W_MASK_HI = 5;
    public static final int W_DEPMAX = 6;
    public static final int W_LPSET = 7;

    public static final int OVERLAY_BIT = 0x4000_0000;
    public static final int GEN_BASE = 0x2000_0000;
    /** Index of a node inside its region. A region holds at most 2^28 - 1 nodes (an int[] of 2^31 - 8). */
    public static final int INDEX_MASK = 0x0FFF_FFFF;
    /** Largest id of the export region. */
    public static final int EXPORT_MAX = INDEX_MASK;

    /** lpSet != 0: the term mentions a universe parameter. */
    public static final int F_HAS_LPARAM = 1 << 4;
    /** depMax != 0: the term mentions a constant (CONST, PROJ, or a literal, which mentions its prerequisites). */
    public static final int F_HAS_CONST = 1 << 5;
    public static final int F_HAS_LIT = 1 << 6;
    public static final int F_HAS_LET = 1 << 7;
    public static final int F_HAS_PROJ = 1 << 8;
    /** Overlay only. */
    public static final int F_HAS_FVAR = 1 << 9;
    /** A CONST/PROJ below could not be bound to a constant when the node was written. */
    public static final int F_UNRESOLVED = 1 << 10;
    public static final int F_LOOSE_SAT = 1 << 11;
    /** NATLIT whose value is in the {@link LitPool}. */
    public static final int F_NAT_BIG = 1 << 12;
    /**
     * Sharing flags (*agentic-note*: design-reconciliation-document, section 3; *agentic-note*: design-isa-document, R1'). They are the three header bits that
     * *agentic-note*: design-architecture-document, 2.1 reserved for the machine. All three are hints for the machine's tabling
     * gate and never under-approximate: a node that is not flagged has at most one referrer inside
     * its declaration's segment.
     *
     * <p>{@code F_SHARED}: two or more references to the node from records of its own declaration
     * segment (two child slots of one parent count as two). Always set on promoted nodes and on
     * nodes made by the overlay's hash-consing constructors.
     */
    public static final int F_SHARED = 1 << 13;
    /** The node is the body of a binder (LAM / PI / LET body) or the root of a declaration. */
    public static final int F_BODY = 1 << 14;
    /**
     * The node's first referrer has a different (hence strictly larger) free-variable mask, and the
     * node is not that referrer's binder body. Meaningful only when {@link #F_SHARED} is clear.
     */
    public static final int F_INVARIANT = 1 << 15;
    /** A node with any of these bits is tabled by the machine. */
    public static final int F_TABLED_MASK = F_SHARED | F_BODY | F_INVARIANT;
    /** Bits 13..15 (the sharing flags above; kept under its old name for the machine's skeleton). */
    public static final int F_RESERVED_MASK = 7 << 13;
    /** The flags a node inherits from its children (bits 4..10). */
    public static final int F_INHERIT_MASK = F_HAS_LPARAM | F_HAS_CONST | F_HAS_LIT | F_HAS_LET | F_HAS_PROJ
            | F_HAS_FVAR | F_UNRESOLVED;

    public static final int LOOSE_SAT = 0xFFFF;
    public static final int DEP_UNRESOLVED = Integer.MAX_VALUE;

    public static int kind(int header) {
        return header & 0xF;
    }

    public static int flags(int header) {
        return header & 0xFFF0;
    }

    public static int looseRange(int header) {
        return header >>> 16;
    }

    public static int header(int kind, int flags, int looseRange) {
        return kind | flags | (looseRange << 16);
    }

    public static boolean isOverlay(int id) {
        return (id & OVERLAY_BIT) != 0;
    }

    public static boolean isGenerated(int id) {
        return (id & (OVERLAY_BIT | GEN_BASE)) == GEN_BASE;
    }

    /** Index of word 0 of node {@code id} in its region's array. */
    public static int wordOf(int id) {
        return (id & INDEX_MASK) << 3;
    }

    /** fvMask of a binder body seen from outside the binder. */
    public static long maskUnder(long bodyMask, int bodyLooseRange) {
        return (bodyMask >>> 1) | (bodyLooseRange > 64 ? 1L << 63 : 0L);
    }
}
