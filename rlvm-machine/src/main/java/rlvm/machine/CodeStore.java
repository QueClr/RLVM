package rlvm.machine;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import rlvm.store.Store;

/**
 * The engine-wide code store (ISA 3.3, 12 R6): immutable blocks in shared {@code int[]} chunks behind a
 * block directory, per-row tables by role, the rent-to-own request counters (3.1). Publication by one
 * release store; readers load with acquire and treat 0 as "none". Owner: M7. Staging steps 2-4; in v0
 * no block exists and every reader sees 0.
 *
 * <h2>Layout</h2>
 * <pre>
 * code chunks    int[CHUNK_WORDS] each (a block longer than a chunk gets a chunk of its own); a block is
 *                contiguous inside one chunk and is never moved or changed after its id is published
 * directory      block id (1 ..) -&gt; address = chunk index &lt;&lt; 16 | word offset; chunked int arrays;
 *                the address is written with a RELEASE store after the block's words, read with ACQUIRE
 * row slots      (row, role) -&gt; block id; chunked int arrays indexed by row * ROLE_COUNT + role; one
 *                release store publishes a role; the first publication of a slot wins (two workers may
 *                build the same block: the contents are equal, ISA 3.3)
 * counters       one int per row: a saturating 4-bit request counter per role (racy, 3.1 / 8.3)
 * </pre>
 * Everything a reader can reach through an id it loaded with acquire was written before the release
 * store of that id: the block's words, the chunk reference, and every block the block refers to
 * (sub-blocks and rule blocks are added to the directory before their parent).
 *
 * <h2>Concurrency</h2>
 * Readers ({@link #blockOf}, {@link #chunkOf}, {@link #offsetOf}, {@link #hint}) are lock-free and never
 * wait. Writers ({@code add}, {@link #addSub}, {@link #publish}) serialise on the store's monitor: a publication is a
 * copy of a few dozen words that happens a few ten thousand times per run, so the monitor is never
 * contended for long (NOTES-M7 D2: ISA 3.3 asks for chunks installed by compare-and-set; the monitor
 * gives the same reader-side guarantees with a writer-side protocol that is easier to audit). The
 * request counters are plain racy words: a lost increment only delays or skips an elaboration.
 *
 * <h2>Publication rule</h2>
 * The store holds what it is given; {@code Code.pubcheck} asserts ISA 3.3 on a block before it gets here
 * (debug mode {@code pubcheck}, and unconditionally in the M7 corpus tests).
 */
final class CodeStore {

    /** log2 of the words of a code chunk (256 KiB). */
    static final int CHUNK_SHIFT = 16;
    static final int CHUNK_WORDS = 1 << CHUNK_SHIFT;
    /** Chunk directory size: 2^11 chunks (512 MiB). The byte cap is reached before (ISA 1.6: 0.4 GB with the facts). */
    static final int MAX_CHUNKS = 1 << 11;
    /** Cap of the code store in words (256 MiB); beyond it further roles stay at the generic tier. */
    static final long MAX_WORDS = 64L << 20;
    /** Largest block (hdr0 has 20 bits for the length). */
    static final int MAX_BLOCK_WORDS = (1 << 20) - 1;
    /** Largest block id + 1: an inline-cache site is {@code block << 12 | pc} (ISA 1.5). */
    static final int MAX_BLOCKS = 1 << 19;

    private static final int DIR_SHIFT = 12;
    private static final int DIR_CHUNK = 1 << DIR_SHIFT;
    private static final int ROW_SHIFT = 12;
    private static final int ROW_CHUNK = 1 << ROW_SHIFT;
    /** Slot chunk directory: 2^12 chunks of 2^12 slots = 2.4 M rows (mathlib has 0.7 M); rows beyond it stay generic. */
    private static final int MAX_ROW_CHUNKS = 1 << 12;

    private static final VarHandle INTS = MethodHandles.arrayElementVarHandle(int[].class);
    private static final VarHandle CHUNKS = MethodHandles.arrayElementVarHandle(int[][].class);

    private final Store store;

    private final int[][] code = new int[MAX_CHUNKS][];
    private final int[][] dir = new int[MAX_BLOCKS >> DIR_SHIFT][];
    private final int[][] slots = new int[MAX_ROW_CHUNKS][];
    private final int[][] counters = new int[MAX_ROW_CHUNKS][];

    // writer state, guarded by the monitor
    private int chunkCount;
    /** Next free word of the current (last regular) chunk; CHUNK_WORDS when there is none. */
    private int top = CHUNK_WORDS;
    private int curChunk = -1;
    private int nextBlock = 1;
    private long words;
    private volatile int publishedBlocks;
    private volatile long publishedWords;

    /**
     * The rent-to-own thresholds (ISA 3.1 {@code ELAB_AT}; the 4-bit request counter covers 1..15), read once at
     * engine creation. {@link #elabAt}: the reduction roles (BODY, RULE, RULES), default {@link #REDUCE_AT} = 1:
     * elaboration at the first request costs nothing measurable on Init, the Cedar slice and reachability
     * (instructions per run equal at 1, 3 and 10; 2.2 MiB of code on Init instead of 0.9) and a closure that is
     * hot inside a body that is unfolded only a few times gets its sub-block (perf/unfold-before-literal: half the
     * time, M3's measurement). {@link #teleAt}: TELE (and FIELDS), default {@code Op.ELAB_AT} = 10: telescope
     * blocks are large (152 words on average on Init). System property {@code rlvm.machine.elabAt} sets both
     * (sweeps); NOTES-M7 section 8.
     */
    final int elabAt;
    final int teleAt;
    static final int REDUCE_AT = 1;

    /**
     * Case trees inline the trees of the definitions they call (matchers; {@code Elab.inline}). On; system
     * property {@code rlvm.machine.m7.inline=off} keeps the trees of ISA 3.6 as they were (for A/B measurements:
     * the blocks are a function of the store and of this setting, which is one per engine).
     */
    final boolean inlineTrees;

    CodeStore(Store store) {
        this.store = store;
        Integer at = Integer.getInteger("rlvm.machine.elabAt");
        this.elabAt = at == null ? REDUCE_AT : at < 1 ? 1 : at > 15 ? 15 : at;
        this.teleAt = at == null ? Op.ELAB_AT : this.elabAt;
        this.inlineTrees = !"off".equals(System.getProperty("rlvm.machine.m7.inline"));
    }

    // ---------------------------------------------------------------------------------------------------
    // Readers (any thread, lock-free)
    // ---------------------------------------------------------------------------------------------------

    /** The published block of {@code (row, role)}, 0 = none (the role is walked by the generic tier). */
    int blockOf(int row, int role) {
        int i = row * Op.ROLE_COUNT + role;
        if (i < 0 || (i >>> ROW_SHIFT) >= MAX_ROW_CHUNKS) {
            return 0;
        }
        int[] c = (int[]) CHUNKS.getAcquire(slots, i >>> ROW_SHIFT);
        return c == null ? 0 : (int) INTS.getAcquire(c, i & (ROW_CHUNK - 1));
    }

    /**
     * Address of a block ({@code chunk << 16 | offset + 1}), 0 = no such block (yet): one acquire load of the
     * directory entry. {@link #chunkAt} and {@link #offsetAt} decode it (the stepper locates a block once per entry).
     */
    int address(int block) {
        if (block <= 0 || block >= MAX_BLOCKS) {
            return 0;
        }
        int[] d = (int[]) CHUNKS.getAcquire(dir, block >>> DIR_SHIFT);
        return d == null ? 0 : (int) INTS.getAcquire(d, block & (DIR_CHUNK - 1));
    }

    /** The chunk of a non-zero {@link #address}. */
    int[] chunkAt(int address) {
        return code[address >>> CHUNK_SHIFT];
    }

    /** The word offset of the block inside its chunk, for a non-zero {@link #address}. */
    static int offsetAt(int address) {
        return (address & (CHUNK_WORDS - 1)) - 1;
    }

    /** True if {@code block} is a published block id. */
    boolean exists(int block) {
        return address(block) != 0;
    }

    /** The chunk that holds block {@code block}; its words start at {@link #offsetOf}. Null if there is no such block. */
    int[] chunkOf(int block) {
        int a = address(block);
        return a == 0 ? null : code[a >>> CHUNK_SHIFT];
    }

    /** Word offset of block {@code block} inside {@link #chunkOf}. */
    int offsetOf(int block) {
        int a = address(block);
        return a == 0 ? -1 : (a & (CHUNK_WORDS - 1)) - 1;
    }

    /** Number of words of a published block (its hdr0 length field). */
    int lengthOf(int block) {
        int[] c = chunkOf(block);
        return c == null ? 0 : c[offsetOf(block) + Op.BLK_HDR0] >>> 12;
    }

    /**
     * Validates a block hint read from the reserved {@code c} word of a binder node (ISA 3.3 "Finding the
     * block of a closure"): the word is a lossy, racy hint, so the reader checks that the id names a
     * published sub-block whose source term is that binder.
     *
     * @return {@code hint} if block {@code hint} is the sub-block of binder term {@code term}, else 0
     */
    int hint(int term, int hint) {
        int a = address(hint);
        if (a == 0) {
            return 0;
        }
        int[] c = code[a >>> CHUNK_SHIFT];
        int o = (a & (CHUNK_WORDS - 1)) - 1;
        return c[o + Op.BLK_FACTS] == term && (c[o + Op.BLK_HDR2] >>> 8 & Code.BLKF_SUB) != 0 ? hint : 0;
    }

    /** Number of blocks published so far (statistics, tests). */
    int blockCount() {
        return publishedBlocks;
    }

    /** Bytes of code published so far (statistics; capped, ISA 1.6). */
    long bytes() {
        return publishedWords << 2;
    }

    // ---------------------------------------------------------------------------------------------------
    // Request counters (ISA 3.1, 8.3): racy, saturating, 4 bits per role
    // ---------------------------------------------------------------------------------------------------

    /** Counts a request of {@code (row, role)}; true exactly when the count reaches the role's threshold ({@link #elabAt}, {@link #teleAt}; racy). */
    boolean request(int row, int role) {
        if (row < 0 || (row >>> ROW_SHIFT) >= MAX_ROW_CHUNKS) {
            return false;
        }
        int[] c = counters[row >>> ROW_SHIFT];
        if (c == null) {
            c = counterChunk(row >>> ROW_SHIFT);
        }
        int i = row & (ROW_CHUNK - 1);
        int w = c[i];
        int sh = role << 2;
        int n = (w >>> sh) & 15;
        if (n == 15) {
            return false;
        }
        c[i] = w + (1 << sh);
        return n + 1 == (role == Op.ROLE_TELE || role == Op.ROLE_FIELDS ? teleAt : elabAt);
    }

    /** The current request count of {@code (row, role)} (0..15; tests and statistics). */
    int requests(int row, int role) {
        if (row < 0 || (row >>> ROW_SHIFT) >= MAX_ROW_CHUNKS) {
            return 0;
        }
        int[] c = counters[row >>> ROW_SHIFT];
        return c == null ? 0 : (c[row & (ROW_CHUNK - 1)] >>> (role << 2)) & 15;
    }

    private synchronized int[] counterChunk(int i) {
        int[] c = counters[i];
        if (c == null) {
            c = new int[ROW_CHUNK];
            CHUNKS.setRelease(counters, i, c);
        }
        return c;
    }

    // ---------------------------------------------------------------------------------------------------
    // Writers
    // ---------------------------------------------------------------------------------------------------

    /**
     * Copies a complete block into the shared chunks and enters it into the directory (rule blocks:
     * blocks that are reached through another block, not through a row slot).
     *
     * @return the block id, 0 if the store is full (the role stays at the generic tier)
     */
    int add(int[] block, int len) {
        return add(block, 0, len);
    }

    /** {@link #add(int[], int)} for the block at {@code block[from .. from + len)}. */
    synchronized int add(int[] block, int from, int len) {
        if (len <= Op.BLK_ENTRIES - 1 || len > MAX_BLOCK_WORDS || nextBlock >= MAX_BLOCKS || words + len > MAX_WORDS) {
            return 0;
        }
        int chunk;
        int off;
        if (len > CHUNK_WORDS - 1) {
            if (chunkCount >= MAX_CHUNKS) {
                return 0;
            }
            chunk = chunkCount++;
            code[chunk] = new int[len];
            off = 0;
        } else {
            if (top + len > CHUNK_WORDS) {
                if (chunkCount >= MAX_CHUNKS) {
                    return 0;
                }
                curChunk = chunkCount++;
                code[curChunk] = new int[CHUNK_WORDS];
                top = 0;
            }
            chunk = curChunk;
            off = top;
            top += len;
        }
        System.arraycopy(block, from, code[chunk], off, len);
        int id = nextBlock++;
        int[] d = dir[id >>> DIR_SHIFT];
        if (d == null) {
            d = new int[DIR_CHUNK];
            CHUNKS.setRelease(dir, id >>> DIR_SHIFT, d);
        }
        words += len;
        publishedWords = words;
        publishedBlocks = id;
        // The one release store that publishes the block: offset + 1 in the offset field, so that 0 stays "none".
        INTS.setRelease(d, id & (DIR_CHUNK - 1), chunk << CHUNK_SHIFT | (off + 1));
        return id;
    }

    /**
     * Adds the sub-block of binder node {@code term} (ISA 3.3 "Finding the block of a closure") and leaves
     * its id in the reserved c word of the node, unless the node already carries a valid hint: then that
     * block is returned and nothing is stored. All hint writers come through here, so a binder has one
     * block and two workers that elaborate the same role refer to the same sub-block ids.
     *
     * @return the block id of the binder's sub-block, 0 if the store is full
     */
    synchronized int addSub(int term, int[] block, int from, int len) {
        int[] ex = store.terms().exportArray();
        int w = (term << 3) + 3;
        if (term > 0 && w < ex.length) {
            int h = hint(term, ex[w]);
            if (h != 0) {
                return h;
            }
        }
        int id = add(block, from, len);
        if (id != 0) {
            store.terms().setBinderHint(term, id);
        }
        return id;
    }

    /**
     * Publishes a complete block for {@code (row, role)} with one release store of its id into the row
     * slot. If the slot is already taken (another worker elaborated the same role: the contents are
     * equal, ISA 3.3) nothing is stored and the existing id is returned.
     *
     * @return the block id readers will see, 0 if the store is full
     */
    synchronized int publish(int row, int role, int[] block, int len) {
        int i = row * Op.ROLE_COUNT + role;
        if (row < 0 || role <= 0 || role >= Op.ROLE_COUNT || i < 0 || (i >>> ROW_SHIFT) >= MAX_ROW_CHUNKS) {
            return 0;
        }
        int[] c = slots[i >>> ROW_SHIFT];
        if (c == null) {
            c = new int[ROW_CHUNK];
            CHUNKS.setRelease(slots, i >>> ROW_SHIFT, c);
        }
        int old = c[i & (ROW_CHUNK - 1)];
        if (old != 0) {
            return old;
        }
        int id = add(block, 0, len);
        if (id != 0) {
            INTS.setRelease(c, i & (ROW_CHUNK - 1), id);
        }
        return id;
    }

    /** The store the blocks were elaborated from. */
    Store store() {
        return store;
    }

    // ---------------------------------------------------------------------------------------------------
    // Heat accounting (ISA 8.3; class Tier)
    // ---------------------------------------------------------------------------------------------------

    /** The heat readers of this engine, null until somebody asked for them ({@code Tier.heat}); read once per declaration. */
    private volatile Tier.Heat heat;

    /** The engine's heat accounting; {@code create}: switch it on if it is off. */
    Tier.Heat heat(boolean create) {
        Tier.Heat h = heat;
        if (h == null && create) {
            synchronized (this) {
                h = heat;
                if (h == null) {
                    heat = h = new Tier.Heat(this);
                }
            }
        }
        return h;
    }
}
