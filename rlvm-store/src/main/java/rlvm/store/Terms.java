package rlvm.store;

import java.util.Arrays;

/**
 * One thread's reader over every term id it may hold: shared ids and the ids of its own overlay.
 * An accessor is a region test (one or two well-predicted compares) and one bounds-checked load from
 * a flat array held in a field; they are meant to be inlined into the machine and the reference
 * kernel. Not thread-safe. {@link #refresh()} must be called at the start of every unit: it re-reads
 * the shared arrays, which the loader may have reallocated. (The overlay's array is kept current
 * automatically: the overlay updates its readers when it grows or is reset.)
 *
 * <p>The overlay may be null: a reader over shared terms only.
 */
public final class Terms {

    private static final int[] NO_INTS = new int[0];

    private final TermStore shared;
    private final Overlay overlay;
    int[] ex;
    int[] gen;
    int[] ov;

    private int[] walkStack;
    private long[] walkMemo;

    public Terms(TermStore shared, Overlay overlay) {
        this.shared = shared;
        this.overlay = overlay;
        this.ov = NO_INTS;
        if (overlay != null) {
            overlay.attach(this);
        }
        refresh();
    }

    public TermStore shared() {
        return shared;
    }

    public Overlay overlay() {
        return overlay;
    }

    /** Re-reads the region arrays (shared ones may have grown; the overlay's after it grew or was reset). */
    public void refresh() {
        ex = shared.exportArray();
        gen = shared.generatedArray();
        if (overlay != null) {
            overlay.refresh();
            ov = overlay.array();
        }
    }

    private int[] arr(int id) {
        return id >= Node.OVERLAY_BIT ? ov : (id < Node.GEN_BASE ? ex : gen);
    }

    /**
     * The array that holds node {@code id}; its words are at {@code Node.wordOf(id) + Node.W_*}. For
     * consumers that read several words of one node with a single region dispatch (ISA.md R1). The
     * array of an overlay id is valid until the overlay grows, i.e. until the next node is made.
     */
    public int[] regionOf(int id) {
        return id >= Node.OVERLAY_BIT ? ov : (id < Node.GEN_BASE ? ex : gen);
    }

    /** Word {@code w} (a {@code Node.W_*} constant) of node {@code id}. */
    public int word(int id, int w) {
        return arr(id)[((id & Node.INDEX_MASK) << 3) + w];
    }

    public int header(int id) {
        return arr(id)[(id & Node.INDEX_MASK) << 3];
    }

    public int kind(int id) {
        return arr(id)[(id & Node.INDEX_MASK) << 3] & 0xF;
    }

    /** The flag bits of the header ({@code Node.F_*}). */
    public int flags(int id) {
        return arr(id)[(id & Node.INDEX_MASK) << 3] & 0xFFF0;
    }

    public int a(int id) {
        return arr(id)[((id & Node.INDEX_MASK) << 3) + 1];
    }

    public int b(int id) {
        return arr(id)[((id & Node.INDEX_MASK) << 3) + 2];
    }

    public int c(int id) {
        return arr(id)[((id & Node.INDEX_MASK) << 3) + 3];
    }

    /** Superset of the free de Bruijn indices: bit i (i &lt; 63) for index i, bit 63 for any index &gt;= 63. */
    public long fvMask(int id) {
        int[] r = arr(id);
        int w = (id & Node.INDEX_MASK) << 3;
        return (r[w + 4] & 0xFFFFFFFFL) | ((long) r[w + 5] << 32);
    }

    /**
     * Largest loose de Bruijn index + 1; exact even when the header field is saturated (then by an
     * iterative walk of the term, memoised within the call).
     */
    public int looseRange(int id) {
        int r = arr(id)[(id & Node.INDEX_MASK) << 3] >>> 16;
        return r != Node.LOOSE_SAT ? r : exactLooseRange(id);
    }

    /** The header's looseRange field: exact below {@link Node#LOOSE_SAT}, which means "65,535 or more, or unknown". */
    public int looseRangeField(int id) {
        return arr(id)[(id & Node.INDEX_MASK) << 3] >>> 16;
    }

    /** No loose bound variable. Exact (a saturated header is resolved by walking). */
    public boolean isClosed(int id) {
        int r = arr(id)[(id & Node.INDEX_MASK) << 3] >>> 16;
        return r == 0 || (r == Node.LOOSE_SAT && exactLooseRange(id) == 0);
    }

    public int depMax(int id) {
        return arr(id)[((id & Node.INDEX_MASK) << 3) + 6];
    }

    public int lpSet(int id) {
        return arr(id)[((id & Node.INDEX_MASK) << 3) + 7];
    }

    public boolean hasFVar(int id) {
        return (arr(id)[(id & Node.INDEX_MASK) << 3] & Node.F_HAS_FVAR) != 0;
    }

    public boolean hasLevelParam(int id) {
        return (arr(id)[(id & Node.INDEX_MASK) << 3] & Node.F_HAS_LPARAM) != 0;
    }

    /** Head of the application spine ending at {@code id} (the term itself if it is not an APP). */
    public int head(int id) {
        int[] r = arr(id);
        int w = (id & Node.INDEX_MASK) << 3;
        return (r[w] & 0xF) == Kind.APP ? r[w + 3] : id;
    }

    /** Number of arguments of the application spine ending at {@code id} (0 if not an APP). */
    public int spineLength(int id) {
        int n = 0;
        while (kind(id) == Kind.APP) {
            id = a(id);
            n++;
        }
        return n;
    }

    /** Copies the spine arguments, first argument at {@code dst[off]}; returns the head. */
    public int spine(int id, int[] dst, int off) {
        int n = spineLength(id);
        int i = off + n;
        while (i > off) {
            int[] r = arr(id);
            int w = (id & Node.INDEX_MASK) << 3;
            dst[--i] = r[w + 2];
            id = r[w + 1];
        }
        return id;
    }

    /** Unsigned 64-bit value of a small NATLIT. */
    public long natSmall(int id) {
        int[] r = arr(id);
        int w = (id & Node.INDEX_MASK) << 3;
        return (r[w + 1] & 0xFFFFFFFFL) | ((long) r[w + 2] << 32);
    }

    /** True for a NATLIT whose value lives in the {@link LitPool} ({@link #a} is the pool index). */
    public boolean isNatBig(int id) {
        return (arr(id)[(id & Node.INDEX_MASK) << 3] & Node.F_NAT_BIG) != 0;
    }

    // ---- exact loose range of saturated nodes ------------------------------------------------------

    /**
     * Exact loose range (capped at Integer.MAX_VALUE) of a node whose header field is saturated.
     * Post-order walk over the saturated part of the DAG only: an unsaturated node answers from its
     * header. Rare (65,535 nested binders, or an index that large), so nothing is kept between calls.
     */
    private int exactLooseRange(int root) {
        if (walkStack == null || walkMemo.length > (1 << 16)) {
            walkStack = new int[256];
            walkMemo = new long[256];
        } else {
            Arrays.fill(walkMemo, 0L);
        }
        int memoUsed = 0;
        int[] st = walkStack;
        int sp = 0;
        st[sp++] = root;
        while (sp > 0) {
            int t = st[sp - 1];
            if (memoGet(t) >= 0) {
                sp--;
                continue;
            }
            int[] r = arr(t);
            int w = (t & Node.INDEX_MASK) << 3;
            int k = r[w] & 0xF;
            long val;
            if (k == Kind.BVAR) {
                int idx = r[w + 1];
                val = idx < 0 || idx == Integer.MAX_VALUE ? Integer.MAX_VALUE : idx + 1;
            } else {
                int c1 = r[w + 1];
                int c2 = k == Kind.PROJ ? 0 : r[w + 2];
                int c3 = k == Kind.LET ? r[w + 3] : 0;
                long v1 = childRange(c1);
                long v2 = c2 == 0 ? 0 : childRange(c2);
                long v3 = c3 == 0 ? 0 : childRange(c3);
                if (v1 < 0 || v2 < 0 || v3 < 0) {
                    if (sp + 3 > st.length) {
                        st = walkStack = Arrays.copyOf(st, st.length * 2);
                    }
                    if (v3 < 0) {
                        st[sp++] = c3;
                    }
                    if (v2 < 0) {
                        st[sp++] = c2;
                    }
                    if (v1 < 0) {
                        st[sp++] = c1;
                    }
                    continue;
                }
                if (k == Kind.LAM || k == Kind.PI) {
                    val = Math.max(v1, v2 > 0 ? v2 - 1 : 0);
                } else if (k == Kind.LET) {
                    val = Math.max(Math.max(v1, v2), v3 > 0 ? v3 - 1 : 0);
                } else {
                    val = Math.max(v1, v2);
                }
            }
            if ((memoUsed + 1) * 2 > walkMemo.length) {
                long[] old = walkMemo;
                walkMemo = new long[old.length * 2];
                for (long e : old) {
                    if (e != 0) {
                        memoPut((int) (e >>> 32), (int) e);
                    }
                }
            }
            memoPut(t, (int) val);
            memoUsed++;
            sp--;
        }
        return memoGet(root);
    }

    /** Exact range of a child if it is known without a walk or already memoised, else -1. */
    private long childRange(int id) {
        int r = arr(id)[(id & Node.INDEX_MASK) << 3] >>> 16;
        return r != Node.LOOSE_SAT ? r : memoGet(id);
    }

    /** Memo entry: key in the high word (term ids are never 0), exact range in the low word. */
    private int memoGet(int id) {
        long[] m = walkMemo;
        int mask = m.length - 1;
        int i = (id * 0x9E3779B1) >>> 7 & mask;
        for (;;) {
            long e = m[i];
            if (e == 0) {
                return -1;
            }
            if ((int) (e >>> 32) == id) {
                return (int) e;
            }
            i = (i + 1) & mask;
        }
    }

    private void memoPut(int id, int value) {
        long[] m = walkMemo;
        int mask = m.length - 1;
        int i = (id * 0x9E3779B1) >>> 7 & mask;
        while (m[i] != 0) {
            i = (i + 1) & mask;
        }
        m[i] = ((long) id << 32) | (value & 0xFFFFFFFFL);
    }
}
