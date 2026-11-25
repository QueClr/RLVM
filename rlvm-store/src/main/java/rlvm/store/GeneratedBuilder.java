package rlvm.store;

/**
 * The environment thread's writer for constants that the official kernel generates itself and the
 * export does not list: a recursor {@code I.rec} / {@code I.rec_N} without a record, and
 * {@code Quot.mk}, {@code Quot.lift}, {@code Quot.ind} when a quot record exists without them
 * (*agentic-note*: design-architecture-document, 2.2, 4.2, 4.6). Obtained from {@link Store#generated()}.
 *
 * <p><b>Who may write, and when.</b> One thread, the one that runs the environment pass, and only
 * after the loader has finished ({@link Store#loadComplete()}): from then on it is the single writer
 * of names, constant rows and the generated term region, exactly as the loader was before, so no
 * structure needs a second writer. In a pipelined run a unit that needs a generated constant while
 * the file is still loading waits for the end of the load (every method throws
 * {@link IllegalStateException} before that; admission treats it like its other load-dependent
 * outcomes). What is written for a unit becomes visible to the workers with the usual
 * {@link Store#publishEnv} of that unit.
 *
 * <p><b>Protocol</b> for one generated constant of unit {@code u}:
 * <pre>
 *   GeneratedBuilder g = store.generated();
 *   int name = g.name(prefix, utf8, 0, len);          // the name id, interned now if the file never mentions it
 *   int type = g.term(terms, overlayType);            // shared copy of the type the kernel derived
 *   int rhs  = g.term(terms, overlayRuleRhs);         // ... and of every rule right-hand side
 *   int cid  = g.constant(u, DeclKind.REC, name, lparams, type);    // 0: the name is already declared
 *   store.decls().setRecursor(cid, ...);              // the derived facts, as for an exported recursor
 *   ...
 *   store.publishEnv(u + 1);
 * </pre>
 * Terms of a generated constant may mention the constant itself and other constants generated before
 * it: make the row first with a type that does not need them, or build the terms in the overlay
 * after {@link #constant} returned the cid (an overlay CONST node needs the row, not the type).
 *
 * <p><b>What can be shared.</b> A generated term is read by every worker, so everything in it must
 * mean the same thing to all of them: constant ids, name ids, shared literals, and level and
 * level-list ids of the run's shared (base) level store. {@link #term} checks that through
 * {@link LevelInfo#isSharedLevel} and {@link IntLists#isSharedList} and refuses
 * ({@link GeneratedTermException}) a level or list that only exists in the calling thread's overlay;
 * making such a level shared is the business of the level module. The same holds for the list of
 * universe parameters given to {@link #constant}.
 *
 * <p><b>References written earlier.</b> A CONST / PROJ node that the loader wrote before the constant
 * existed is unbound ({@code ~name}) for good; {@link TermOps#rebind} gives a bound copy.
 */
public final class GeneratedBuilder {

    private final Store store;
    private final TermStore terms;
    private final NameTable names;
    private final DeclTable decls;

    GeneratedBuilder(Store store) {
        this.store = store;
        this.terms = store.terms();
        this.names = store.names();
        this.decls = store.decls();
    }

    private void requireComplete() {
        if (!store.loadComplete()) {
            throw new IllegalStateException("generated constants need the complete file: the load is still running");
        }
    }

    /**
     * The id of name {@code prefix.component} (UTF-8 bytes of the component), interned now if the
     * export never mentioned it ("late name"). Same ids as the loader's interning.
     */
    public int name(int prefix, byte[] utf8, int off, int len) {
        requireComplete();
        return names.internStr(prefix, utf8, off, len);
    }

    /** The id of name {@code prefix.n} for a numeric component. */
    public int nameNum(int prefix, long unsignedValue) {
        requireComplete();
        return names.internNum(prefix, unsignedValue);
    }

    /** True if no declaration, generated constant or dropped record holds {@code name}. */
    public boolean isFree(int name) {
        return names.constOf(name) == 0;
    }

    /**
     * A shared copy, in the generated region, of closed term {@code term} of the caller's overlay
     * (the term itself if it already is a shared term). See {@link TermStore#appendGenerated} for
     * what the term may contain; on a refusal nothing is added. {@code from} is refreshed.
     *
     * @throws GeneratedTermException the term cannot be shared as it is
     */
    public int term(Terms from, int term) {
        requireComplete();
        return terms.appendGenerated(from, term);
    }

    /**
     * Adds a generated constant to unit {@code unit} (which the environment pass is admitting: it
     * must not be published yet) and claims its name.
     *
     * @param kind {@link DeclKind#REC}, {@link DeclKind#CTOR}, {@link DeclKind#INDUCT} or one of the
     *     quotient kinds; a side row is allocated for the first three
     * @param lparams the universe-parameter names, a list id of the shared list store
     * @param type a closed shared term (export or generated region), or 0 to set it later with
     *     {@link #setType}
     * @return the constant id, or 0 if the name is already declared (the official "already declared")
     * @throws GeneratedTermException {@code lparams} is not a shared list
     */
    public int constant(int unit, int kind, int name, int lparams, int type) {
        requireComplete();
        if (unit < store.envUnits() || unit >= store.loadedUnits()) {
            throw new IllegalArgumentException("unit " + unit + " is not being admitted (environment frontier "
                    + store.envUnits() + ", loaded " + store.loadedUnits() + ")");
        }
        boolean ok = kind == DeclKind.REC || kind == DeclKind.CTOR || kind == DeclKind.INDUCT
                || kind == DeclKind.QUOT_TYPE || kind == DeclKind.QUOT_MK || kind == DeclKind.QUOT_LIFT
                || kind == DeclKind.QUOT_IND;
        if (!ok) {
            throw new IllegalArgumentException("the kernel generates no constant of kind " + kind);
        }
        if (lparams != 0 && !store.lists().isSharedList(lparams)) {
            throw new GeneratedTermException("the universe parameter list " + lparams + " is not a shared list");
        }
        if (type != 0) {
            checkRoot(type);
        }
        return decls.addGenerated(kind, name, lparams, type, unit);
    }

    /** Sets the type of a generated constant of a unit that is not published yet (a closed shared term). */
    public void setType(int cid, int type) {
        requireComplete();
        if (cid <= 0 || cid > decls.constantCount() || !decls.isGenerated(cid)) {
            throw new IllegalArgumentException("constant " + cid + " was not generated");
        }
        if (decls.unit(cid) < store.envUnits()) {
            throw new IllegalStateException("the unit of constant " + cid + " is already published");
        }
        checkRoot(type);
        decls.setType(cid, type);
    }

    private void checkRoot(int type) {
        if (type <= 0 || Node.isOverlay(type)) {
            throw new IllegalArgumentException("the type of a generated constant must be a shared term: " + type);
        }
        boolean export = type < Node.GEN_BASE && type < terms.exportEnd();
        boolean generated = Node.isGenerated(type) && type < terms.generatedEnd();
        if (!export && !generated) {
            throw new IllegalArgumentException("not a term id: " + type);
        }
        int[] r = export ? terms.exportArray() : terms.generatedArray();
        int h = r[Node.wordOf(type)];
        if (Node.looseRange(h) != 0) {
            throw new GeneratedTermException("the type has a loose bound variable");
        }
        if ((h & Node.F_UNRESOLVED) != 0) {
            throw new GeneratedTermException("the type has an unresolved reference");
        }
    }
}
