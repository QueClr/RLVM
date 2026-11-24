package rlvm.store;

/**
 * Loader-side writer of the shared store. One thread. Works in STORE ids throughout: the loader
 * translates export ids before calling (it owns the export-id maps and the segment logic,
 * {@code rlvm-load}).
 *
 * <p>Protocol per declaration unit (ARCHITECTURE.md 3.3): open the unit and claim its name(s) FIRST
 * ({@link #beginUnit} and {@link #reserve}; or {@link #beginDeclaration}; or {@link #beginBlock} and
 * {@link #claimMember}), then write the literals, shared terms (children first) and at most one
 * proof block the unit needs, then set the roots and close the unit ({@link #complete} and
 * {@link #endUnit}; or {@link #endDeclaration}; or {@link #setMemberType} and {@link #endBlock}),
 * then {@link #publish()} when a publication is due. Claiming first is what lets references to the
 * unit's own names bind: the members of an inductive block see each other, and a theorem that
 * mentions itself gets {@code depMax = own rank}. The one-call forms {@link #declare} and
 * {@link #member} claim the name only after the terms exist; a reference to the name written before
 * that stays unbound ({@link Node#F_UNRESOLVED}).
 *
 * <p>Every shared node written between the end of the previous unit and the end of this one belongs
 * to this unit's segment: {@link DeclTable#unitFirstTerm} records where it starts, and the sharing
 * flags of {@link Node} are maintained for exactly these nodes (the loader does not have to count
 * referrers). Roots given to this class are flagged {@link Node#F_BODY}; other roots (recursor rule
 * right-hand sides, which travel in the claims blob) can be flagged with {@link #markRoot}.
 */
public final class StoreBuilder {

    private final Store store;
    private final TermStore terms;
    private final NameTable names;
    private final DeclTable decls;
    private final LitPool lits;
    private final ProofBlocks blocks;
    private int unitKind;

    StoreBuilder(Store store) {
        this.store = store;
        this.terms = store.terms();
        this.names = store.names();
        this.decls = store.decls();
        this.lits = store.lits();
        this.blocks = store.blocks();
    }

    // ---- terms of the export region; each returns the new shared id --------------------------------

    public int bvar(int index) {
        return terms.appendExport(Kind.BVAR, index, 0, 0);
    }

    public int sort(int level) {
        return terms.appendExport(Kind.SORT, level, 0, 0);
    }

    /**
     * A constant reference by name. Binds it to the constant currently declared under {@code name}
     * (then depMax = that constant's rank); otherwise the node is marked unresolved and resolved
     * late, by name (ARCHITECTURE.md 4.2). A bound reference with the wrong number of levels is
     * marked unresolved as well.
     */
    public int constant(int name, int levelList) {
        int cid = names.constOf(name);
        return terms.appendExport(Kind.CONST, cid > 0 ? cid : ~name, levelList, name);
    }

    public int app(int fn, int arg) {
        return terms.appendExport(Kind.APP, fn, arg, 0);
    }

    public int lam(int domain, int body) {
        return terms.appendExport(Kind.LAM, domain, body, 0);
    }

    public int pi(int domain, int body) {
        return terms.appendExport(Kind.PI, domain, body, 0);
    }

    public int let(int type, int value, int body) {
        return terms.appendExport(Kind.LET, type, value, body);
    }

    public int proj(int structName, int fieldIndex, int struct) {
        int cid = names.constOf(structName);
        return terms.appendExport(Kind.PROJ, struct, fieldIndex, cid > 0 ? cid : ~structName);
    }

    public int natSmall(long unsignedValue) {
        return terms.appendExport(Kind.NATLIT, (int) unsignedValue, (int) (unsignedValue >>> 32), 0);
    }

    /** A literal of the {@link LitPool}; a pooled value below 2^64 is stored in the node like {@link #natSmall}. */
    public int natBig(int litIndex) {
        if (lits.natLimbCount(litIndex) == 1) {
            return natSmall(lits.natLimb(litIndex, 0));
        }
        return terms.appendExport(Kind.NATLIT, litIndex, 0, 0, Node.F_NAT_BIG);
    }

    public int str(int litIndex) {
        return terms.appendExport(Kind.STRLIT, litIndex, lits.stringCodePointCount(litIndex), 0);
    }

    /** Facts of an existing shared node, for the loader's own derivations. */
    public int header(int sharedId) {
        if (sharedId <= 0 || sharedId >= terms.exportEnd()) {
            throw new IllegalArgumentException("not a shared term: " + sharedId);
        }
        return terms.writerArray()[sharedId << 3];
    }

    /** The cid-or-{@code ~name} word a CONST / PROJ record for {@code name} gets now (for {@link ProofBlocks.Writer}). */
    public int bindName(int name) {
        int cid = names.constOf(name);
        return cid > 0 ? cid : ~name;
    }

    /**
     * A hint of the loader: shared term {@code sharedId} will be named as a child of a node appended
     * soon. Loads the node's words now, so that the cache miss of a far child overlaps with other
     * work instead of stalling the append. Changes nothing; an id that is not an export term is ignored.
     */
    public void touch(int sharedId) {
        terms.touch(sharedId);
    }

    /** Flags a shared term of the unit being written as a declaration root ({@link Node#F_BODY}). */
    public void markRoot(int sharedId) {
        if (sharedId > 0) {
            terms.markRoot(sharedId);
        }
    }

    // ---- blocks ----------------------------------------------------------------------------------

    /** The writer for the proof block of the unit being written (one block at a time). */
    public ProofBlocks.Writer blockWriter() {
        return blocks.newWriter();
    }

    /** Promotes global block record {@code record} into the export region (see {@link ProofBlocks}). */
    public int promote(int record) {
        return blocks.promote(record, terms);
    }

    // ---- declarations -----------------------------------------------------------------------------
    //
    // The general protocol is  beginUnit, reserve (one per constant), ..terms.., complete (one per
    // constant), [unitClaims], endUnit.  The methods after it are the same thing spelled per kind.

    private static int flagsOf(int kind, int value) {
        int f = kind == DeclKind.DEFN || kind == DeclKind.THM ? DeclTable.CF_UNFOLDS : 0;
        return value < 0 ? f | DeclTable.CF_BLOCK_VALUE : f;
    }

    /**
     * Opens a unit (one declaration record): {@code recordKind} is the constant kind of a
     * single-constant record, {@link DeclKind#INDUCT} for an inductive block. Returns the unit index.
     * The unit's segment starts where the previous unit ended ({@link DeclTable#unitFirstTerm}).
     */
    public int beginUnit(int recordKind) {
        unitKind = recordKind;
        return decls.beginUnit(recordKind, terms.segmentStart());
    }

    /**
     * Adds a constant of the open unit and claims its name, before the unit's terms are written: the
     * row gets kind, name, universe parameters, unit and rank; type and value follow with
     * {@link #complete}. Returns the cid, or 0 if the name is already declared (the caller rejects
     * the file).
     */
    public int reserve(int kind, int name, int lparams) {
        int unit = requireUnit();
        int flags = flagsOf(kind, 0) | (unitKind == DeclKind.INDUCT ? DeclTable.CF_BLOCK_MEMBER : 0);
        return decls.addConstant(kind, flags, name, lparams, 0, 0, 0, unit);
    }

    /**
     * Sets the roots of a constant of the open unit. {@code type} is a shared term id; {@code value}
     * is a shared term id, {@code -(block+1)}, or 0; {@code hint} is the reducibility hint of a
     * definition ({@link DeclKind}), 0 otherwise. Shared roots are flagged {@link Node#F_BODY}.
     */
    public void complete(int cid, int type, int value, int hint) {
        if (cid <= 0 || decls.unit(cid) != requireUnit()) {
            throw new IllegalArgumentException("constant " + cid + " is not a constant of the open unit");
        }
        decls.setType(cid, type);
        if (value != 0) {
            decls.setValue(cid, value);
        }
        if (hint != 0) {
            decls.setHint(cid, hint);
        }
        markRoot(type);
        markRoot(value);
    }

    /**
     * Attaches the untrusted remainder of an inductive record (counts, flags, rule tables, as written
     * in the export) as an opaque blob for admission to compare against ({@link DeclTable#claims});
     * freed after admission ({@link DeclTable#dropClaims}). The store does not interpret it.
     */
    public void unitClaims(int[] blob, int len) {
        decls.setClaims(requireUnit(), java.util.Arrays.copyOf(blob, len));
    }

    /** Closes the open unit and its segment. */
    public void endUnit() {
        requireUnit();
        decls.endUnit();
        terms.endSegment();
    }

    private int requireUnit() {
        int unit = decls.openUnit();
        if (unit < 0) {
            throw new IllegalStateException("no open unit");
        }
        return unit;
    }

    /**
     * Opens a single-constant unit and claims its name; the unit's terms are written afterwards.
     * Returns the cid, or 0 on a duplicate name (the unit is then closed, holding no constant, and
     * the caller rejects the file).
     */
    public int beginDeclaration(int kind, int name, int lparams) {
        beginUnit(kind);
        int cid = reserve(kind, name, lparams);
        if (cid == 0) {
            endUnit();
        }
        return cid;
    }

    /**
     * Sets the roots of the constant opened by {@link #beginDeclaration} and closes its unit.
     * {@code value} is a shared term id, {@code -(block+1)}, or 0.
     */
    public void endDeclaration(int cid, int type, int value, int hint) {
        complete(cid, type, value, hint);
        endUnit();
    }

    /**
     * Adds a single-constant unit in one call. {@code value} is a shared term id, {@code -(block+1)},
     * or 0. Returns the cid, or 0 on a duplicate name. Prefer {@link #beginDeclaration} /
     * {@link #endDeclaration}: here the name is claimed after the terms were written, so a reference
     * to the constant inside its own terms is unbound.
     */
    public int declare(int kind, int name, int lparams, int type, int value, int hint) {
        int cid = beginDeclaration(kind, name, lparams);
        if (cid != 0) {
            endDeclaration(cid, type, value, hint);
        }
        return cid;
    }

    /** Opens an inductive unit; members are added with {@link #claimMember} or {@link #member}. Returns the unit index. */
    public int beginBlock() {
        return beginUnit(DeclKind.INDUCT);
    }

    /**
     * Claims the name of a type, constructor or recursor of the open block, before the block's terms
     * are written. Returns the cid, or 0 on a duplicate name.
     */
    public int claimMember(int kind, int name, int lparams) {
        return reserve(kind, name, lparams);
    }

    /** Sets the type of a member claimed with {@link #claimMember} (for a recursor: the exported type, a CLAIM). */
    public void setMemberType(int cid, int type) {
        complete(cid, type, 0, 0);
    }

    /**
     * Adds a type, constructor or recursor row of the open block (inputs only: name, lparams, type;
     * for a recursor the exported type is kept as the CLAIM that admission compares). Returns the
     * cid, or 0 on a duplicate name. The name is claimed after the type was written; prefer
     * {@link #claimMember} / {@link #setMemberType}.
     */
    public int member(int kind, int name, int lparams, int type) {
        int cid = reserve(kind, name, lparams);
        if (cid != 0) {
            complete(cid, type, 0, 0);
        }
        return cid;
    }

    /** Same as {@link #unitClaims}. */
    public void blockClaims(int[] blob, int len) {
        unitClaims(blob, len);
    }

    public void endBlock() {
        endUnit();
    }

    /**
     * Records an unsafe/partial declaration that is dropped: only the name is remembered
     * ({@link NameTable#DROPPED_UNSAFE}). The record occupies a unit without constants whose status is
     * {@link DeclKind#ST_SKIPPED}. Returns false if the name was already declared or dropped (a
     * duplicate declaration: the caller rejects the file); the unit is written all the same.
     */
    public boolean dropUnsafe(int name) {
        boolean fresh = dropName(name);
        int unit = beginUnit(DeclKind.NONE);
        decls.setStatusUnpublished(unit, DeclKind.ST_SKIPPED);
        endUnit();
        return fresh;
    }

    /**
     * Marks a further name of a dropped record (e.g. the other members of an unsafe inductive block);
     * no unit. Returns false if the name was already declared or dropped.
     */
    public boolean dropName(int name) {
        return names.claim(name, NameTable.DROPPED_UNSAFE);
    }

    /**
     * Gives back what only loading needs (the promotion memo and the sparse record index of the proof
     * blocks; 0.29 GB on mathlib). {@link #finish()} calls it: after the end of the input no record
     * is promoted and no block written any more, and {@link #promote} and {@link #blockWriter()}'s
     * {@code finish} throw {@link IllegalStateException} from then on. Everything readers use is
     * unaffected. Idempotent. (A load that ends without {@code finish()} keeps the state.)
     */
    public void releaseLoaderState() {
        blocks.releaseLoaderState();
    }

    /** Makes every unit written so far visible to the other threads. */
    public void publish() {
        store.publishLoaded(decls.unitCount(), false);
    }

    /**
     * End of input: final publication, and the loader's own state (promotion memo, sparse record
     * index) is given back ({@link #releaseLoaderState()}): nothing can be promoted afterwards.
     */
    public void finish() {
        store.publishLoaded(decls.unitCount(), true);
        blocks.releaseLoaderState();
    }
}
