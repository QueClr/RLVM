package rlvm.store;

/**
 * The persistent store of one run: shared terms, names, lists, literals, declarations, proof blocks,
 * special constants, and the two publication frontiers.
 *
 * <p>Frontiers (volatile, monotone):
 * <ul>
 *   <li>{@link #loadedUnits()}: units {@code [0, n)} are completely stored (rows, terms, blocks).
 *   <li>{@link #envUnits()}: additionally every inductive and quot unit below it has been admitted and
 *       its derived facts are in the {@link DeclTable} side tables. A worker may check unit {@code u}
 *       only when {@code u < envUnits()}.
 * </ul>
 * Everything reachable from a published unit is immutable; that is the whole concurrency contract.
 * A reader must have read the frontier that covers a datum before it touches the datum: the volatile
 * frontier write is the happens-before edge for everything the writer stored before it.
 *
 * <p>The store depends on nothing but the JDK, so the two things it needs from {@code rlvm-level}
 * come in as adapters: the run's single list store ({@link IntLists}) and a structural view of
 * levels ({@link LevelInfo}, used to derive {@code lpSet}). A store created without them uses a
 * private {@link ArrayIntLists} and cannot write SORT / CONST nodes with levels until
 * {@link #bindLevels} is called.
 */
public final class Store {

    private final TermStore terms;
    private final NameTable names;
    private final IntLists lists;
    private final LitPool lits;
    private final DeclTable decls;
    private final ProofBlocks blocks;
    private final Specials specials;
    private final SharedFacts facts;
    private final NodeCtx ctx;
    private StoreBuilder builder;
    private GeneratedBuilder generated;

    private volatile int loadedUnits;
    private volatile boolean loadComplete;
    private volatile int envUnits;

    /**
     * A store with its own list store and no level adapter (see {@link #bindLevels}).
     *
     * @param inputBytesHint size of the export file, used to size the tables (0 = unknown)
     */
    public Store(long inputBytesHint) {
        this(inputBytesHint, null, null);
    }

    /**
     * @param lists the run's base list store (level lists, name lists, lpSet sets); null = a private
     *     {@link ArrayIntLists}
     * @param levels the loader thread's view of the level store; null = bind later
     */
    public Store(long inputBytesHint, IntLists lists, LevelInfo levels) {
        long hint = Math.max(inputBytesHint, 0);
        this.lists = lists != null ? lists : new ArrayIntLists();
        // ARCHITECTURE.md 2.2: one shared node per 144 input bytes; mathlib has one name per 1,480
        // bytes and one constant per 8,560. Every table grows if the estimate is too small.
        this.terms = new TermStore((int) Math.min(hint / 144 + 1024, Node.INDEX_MASK - 1));
        this.names = new NameTable((int) Math.min(hint / 1024 + 256, 1 << 28));
        this.specials = new Specials(names);
        this.decls = new DeclTable((int) Math.min(hint / 4096 + 64, 1 << 26), names, specials, this.lists);
        this.specials.attach(decls);
        this.lits = new LitPool();
        this.blocks = new ProofBlocks();
        this.facts = new SharedFacts(terms);
        this.ctx = new NodeCtx(decls, specials, this.lists, levels);
        this.terms.bind(ctx, lits);
        this.blocks.bind(this);
    }

    /**
     * Sets the level adapter of the writer side (loader thread, before the first SORT / CONST node is
     * written). Overlays created afterwards without their own adapter inherit it.
     */
    public void bindLevels(LevelInfo levels) {
        ctx.setLevels(levels);
    }

    /** The writer side's level adapter, or null. */
    public LevelInfo levelInfo() {
        return ctx.levels();
    }

    public TermStore terms() {
        return terms;
    }

    public NameTable names() {
        return names;
    }

    /** The shared (base) list store. */
    public IntLists lists() {
        return lists;
    }

    public LitPool lits() {
        return lits;
    }

    public DeclTable decls() {
        return decls;
    }

    public ProofBlocks blocks() {
        return blocks;
    }

    public Specials specials() {
        return specials;
    }

    /** Racy, idempotent per-term facts shared by all workers. */
    public SharedFacts facts() {
        return facts;
    }

    public int loadedUnits() {
        return loadedUnits;
    }

    /** True once the loader reached the end of the file without error. */
    public boolean loadComplete() {
        return loadComplete;
    }

    public int envUnits() {
        return envUnits;
    }

    /** Loader thread. */
    public void publishLoaded(int units, boolean complete) {
        if (units < loadedUnits) {
            throw new IllegalArgumentException("the loaded frontier is monotone: " + units + " < " + loadedUnits);
        }
        if (units > decls.unitCount()) {
            throw new IllegalArgumentException("only " + decls.unitCount() + " units were written");
        }
        lits.publish();
        blocks.publish();
        if (complete) {
            loadComplete = true;
        }
        loadedUnits = units;
    }

    /** Environment thread. */
    public void publishEnv(int units) {
        if (units < envUnits) {
            throw new IllegalArgumentException("the environment frontier is monotone: " + units + " < " + envUnits);
        }
        if (units > loadedUnits) {
            throw new IllegalArgumentException("the environment frontier cannot pass the loaded frontier");
        }
        envUnits = units;
    }

    /**
     * The environment thread's writer of generated constants (the same object at every call).
     * Usable only once the load is complete: see {@link GeneratedBuilder}.
     */
    public GeneratedBuilder generated() {
        if (!loadComplete) {
            throw new IllegalStateException("generated constants need the complete file: the load is still running");
        }
        if (generated == null) {
            generated = new GeneratedBuilder(this);
        }
        return generated;
    }

    /** The single-writer interface for the loader (the same object at every call). */
    public StoreBuilder builder() {
        if (builder == null) {
            builder = new StoreBuilder(this);
        }
        return builder;
    }

    /**
     * Memory of the shared structures by region ({@link StoreMemory}): allocated and used bytes and
     * the number of entities. Exact when the loader is not writing.
     */
    public StoreMemory memory() {
        StoreMemory m = new StoreMemory();
        m.allocated[StoreMemory.EXPORT] = 4L * terms.exportArray().length;
        m.used[StoreMemory.EXPORT] = terms.usedBytes();
        m.count[StoreMemory.EXPORT] = terms.exportEnd() - 1;
        m.allocated[StoreMemory.GENERATED] = 4L * terms.generatedArray().length;
        m.used[StoreMemory.GENERATED] = 32L * (terms.generatedEnd() - Node.GEN_BASE);
        m.count[StoreMemory.GENERATED] = terms.generatedEnd() - Node.GEN_BASE;
        m.allocated[StoreMemory.BLOCKS] = blocks.storageBytes();
        m.used[StoreMemory.BLOCKS] = blocks.usedBytes();
        m.count[StoreMemory.BLOCKS] = blocks.recordCount();
        m.allocated[StoreMemory.LOADER] = blocks.loaderBytes() + terms.loaderBytes();
        m.used[StoreMemory.LOADER] = m.allocated[StoreMemory.LOADER];
        m.count[StoreMemory.LOADER] = blocks.promotedCount();
        m.allocated[StoreMemory.NAMES] = names.bytes();
        m.used[StoreMemory.NAMES] = names.usedBytes();
        m.count[StoreMemory.NAMES] = names.size() - 1;
        m.allocated[StoreMemory.DECLS] = decls.bytes();
        m.used[StoreMemory.DECLS] = decls.usedBytes();
        m.count[StoreMemory.DECLS] = decls.constantCount();
        m.allocated[StoreMemory.LITS] = lits.bytes();
        m.used[StoreMemory.LITS] = lits.usedBytes();
        m.count[StoreMemory.LITS] = lits.natCount() + lits.stringCount();
        m.allocated[StoreMemory.FACTS] = facts.bytes();
        m.used[StoreMemory.FACTS] = facts.bytes();
        long listBytes = lists instanceof ArrayIntLists ? ((ArrayIntLists) lists).bytes() : 0;
        m.allocated[StoreMemory.LISTS] = listBytes;
        m.used[StoreMemory.LISTS] = listBytes;
        return m;
    }

    /** Bytes held by all shared structures (for the memory budget and the statistics dump). */
    public long sharedBytes() {
        long listBytes = lists instanceof ArrayIntLists ? ((ArrayIntLists) lists).bytes() : 0;
        return terms.bytes() + terms.loaderBytes() + names.bytes() + lits.bytes() + decls.bytes() + blocks.bytes()
                + facts.bytes() + listBytes;
    }
}
