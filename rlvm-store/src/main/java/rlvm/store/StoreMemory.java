package rlvm.store;

/**
 * A snapshot of the shared store's memory by region: bytes allocated (array capacity, what the heap
 * holds) and bytes in use (what the data needs). Taken with {@link Store#memory()}; exact when the
 * loader is not writing (the counters are the writer's own).
 *
 * <p>The regions follow the budget table of ARCHITECTURE.md 2.9. Worker-local memory (term
 * overlays, level and literal overlays, engine sessions) is not part of the store and not in here:
 * see {@link Overlay#allocatedBytes()} / {@link Overlay#usedBytes()} and
 * {@code Session.footprintBytes()}.
 */
public final class StoreMemory {

    /** Export term region: 32 bytes per shared node. */
    public static final int EXPORT = 0;
    /** Generated term region (terms of constants that admission generates). */
    public static final int GENERATED = 1;
    /** Proof blocks: packed records and the block table. */
    public static final int BLOCKS = 2;
    /**
     * What only the loader needs: the promotion memo and sparse record index of the proof blocks and
     * the referrer counters of the open unit. Released by {@link StoreBuilder#releaseLoaderState()}.
     */
    public static final int LOADER = 3;
    /** Name rows, component strings and the intern index. */
    public static final int NAMES = 4;
    /** Constant rows, unit rows, statuses, side tables, claims blobs. */
    public static final int DECLS = 5;
    /** Literal pool: big naturals and strings. */
    public static final int LITS = 6;
    /** Racy per-term facts (one byte per export node once a worker wrote one). */
    public static final int FACTS = 7;
    /** The store's own list store (0 when the run's list store lives in {@code rlvm-level}). */
    public static final int LISTS = 8;
    public static final int REGIONS = 9;

    private static final String[] NAMES_OF = {"export terms", "generated terms", "proof blocks", "loader state",
        "names", "declarations", "literals", "shared facts", "lists"};

    public final long[] allocated = new long[REGIONS];
    public final long[] used = new long[REGIONS];
    /** Entities per region: nodes, nodes, block records, promoted records, names, constants, literals, 0, 0. */
    public final long[] count = new long[REGIONS];

    StoreMemory() {}

    public static String name(int region) {
        return NAMES_OF[region];
    }

    public long totalAllocated() {
        long s = 0;
        for (long b : allocated) {
            s += b;
        }
        return s;
    }

    public long totalUsed() {
        long s = 0;
        for (long b : used) {
            s += b;
        }
        return s;
    }

    /** One line per region: name, allocated MB, used MB, entities. */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < REGIONS; r++) {
            line(sb, NAMES_OF[r], allocated[r], used[r], count[r]);
        }
        line(sb, "total", totalAllocated(), totalUsed(), -1);
        return sb.toString();
    }

    private static void line(StringBuilder sb, String name, long alloc, long use, long n) {
        sb.append(name);
        for (int i = name.length(); i < 18; i++) {
            sb.append(' ');
        }
        sb.append(String.format("allocated %9.1f MB   used %9.1f MB", alloc / 1048576.0, use / 1048576.0));
        if (n >= 0) {
            sb.append("   ").append(n);
        }
        sb.append('\n');
    }
}
