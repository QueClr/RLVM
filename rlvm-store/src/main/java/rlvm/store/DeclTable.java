package rlvm.store;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;

/**
 * The declaration table: one row per constant (constant id {@code cid}, dense from 1 in file order)
 * and one row per declaration record ("unit": a def/thm/axiom/opaque/quot line or a whole inductive
 * block), plus the side tables with the facts the kernel reads about inductive types, constructors
 * and recursors.
 *
 * <pre>
 * constant row, 8 ints (32 bytes):
 *   w0 bits 0..3 kind ({@link DeclKind}) | bits 4..9 flags (CF_*) | bits 10..15 special index + 1
 *      ({@link Specials}) | bits 16..31 number of universe parameters
 *   w1 unit   index of the declaration record, 0-based, file order
 *   w2 rank   position in admission order, 1-based: unit + 1 unless the loader had to reorder
 *             (so that {@code depMax == 0} means "mentions no constant" and visibility is
 *             {@code depMax(root) < rank})
 *   w3 name id
 *   w4 type   shared term id
 *   w5 value  DEFN: shared term id. THM/OPAQUE: shared term id, or -(block+1) if the value is a proof block.
 *             Else 0 (a constructor's inductive type is the derived {@link #ctorInduct}).
 *   w6 hint   DEFN: {@link DeclKind#HINT_OPAQUE}, 1+h, {@link DeclKind#HINT_ABBREV}. THM: 0.
 *             INDUCT / CTOR / REC: row in the kind's side table.
 *   w7 lparams  list id of the universe-parameter names, in order
 * unit row, 4 ints: first cid | number of constants | kind of record | first export term id of the unit's segment;
 *                   status in an atomic int column
 * inductive side row, 8 ints: numParams | numIndices | numCtors | ctors (pool offset) | all (pool offset, count in
 *                   the high bits of w6) | IF_* flags | numNested + all count | cid of I.rec
 * constructor side row, 4 ints: inductive cid | cidx | numParams | numFields
 * recursor side row, 8 ints: numParams | numIndices | numMotives | numMinors | RF_* flags | rules (pool offset)
 *                            | number of rules | cid of the inductive type of the major
 * rules: triples (constructor cid, nfields, rhs term id) in the table's own pool
 * </pre>
 *
 * <p>Trust: rows w0..w7 of def/thm/axiom/opaque constants are inputs. Every side-table value is
 * DERIVED by admission (ARCHITECTURE.md 4.6) and is readable only for units below
 * {@link Store#envUnits()}; what the export claimed is held only in the per-unit claims blob
 * ({@link #claims(int)}), which admission drops after the comparison.
 *
 * <p>Writers: the loader thread (constant and unit rows, allocation of side rows), the environment
 * thread (contents of side rows, the pool of constructor lists and rule tables). The only word
 * written after publication is the unit status. Row arrays grow by reallocation and are published
 * through volatile fields; side rows live in chunks that never move, so the two writers never copy
 * each other's data. Constructor lists, {@code all} lists and rule tables are stored in the table
 * itself (critic finding C1.13): the environment thread cannot intern into list stores that the
 * loader froze.
 */
public final class DeclTable {

    /** Definitions and theorems: the constant has a value that delta may unfold. */
    public static final int CF_UNFOLDS = 1 << 4;
    /** Value is a proof block (w5 negative). */
    public static final int CF_BLOCK_VALUE = 1 << 5;
    /** Created by admission; the export had no record of this name. */
    public static final int CF_GENERATED = 1 << 6;
    /** Member of a multi-constant unit (inductive block). */
    public static final int CF_BLOCK_MEMBER = 1 << 7;
    private static final int CF_MASK = 0x3F0;

    public static final int IF_REC = 1;
    public static final int IF_REFLEXIVE = 1 << 1;
    /** One constructor, no indices, not recursive: structure eta and projections-as-eta apply. */
    public static final int IF_STRUCT_LIKE = 1 << 2;
    /** IF_STRUCT_LIKE and the constructor has no fields. */
    public static final int IF_UNIT_LIKE = 1 << 3;
    /** Exactly one constructor (what {@code Expr.proj} typing needs). */
    public static final int IF_ONE_CTOR = 1 << 4;

    public static final int RF_K = 1;

    private static final VarHandle INT_ELEMENTS = MethodHandles.arrayElementVarHandle(int[].class);

    private static final int STATUS_SHIFT = 14;
    private static final int STATUS_CHUNK = 1 << STATUS_SHIFT;
    private static final int STATUS_DIR = 1 << 12;

    private final NameTable names;
    private final Specials specials;
    private final IntLists lists;

    private int[] rows;
    private volatile int[] rowsPub;
    private int constants;

    private int[] units;
    private volatile int[] unitsPub;
    private volatile int unitCount;
    private int openUnit = -1;
    private final int[][] status = new int[STATUS_DIR][];
    private int[][] claims = new int[16][];
    private volatile int[][] claimsPub = claims;

    private final Side induct = new Side(3);
    private final Side ctor = new Side(2);
    private final Side rec = new Side(3);

    /** Environment thread's pool: constructor lists, all lists, rule triples. Offset 0 is unused. */
    private int[] pool = new int[64];
    private volatile int[] poolPub = pool;
    private int poolUsed = 1;

    private int nestedRootUnit = -1;
    /** First generated constant (0 = none) and their number; they follow the loader's constants. */
    private volatile int firstGenerated;
    private volatile int generatedCount;

    /**
     * The first unit that declares a constant whose name has the reserved root {@code _nested}
     * (the official kernel's auxiliary names of nested inductive types live there), or -1 if no unit
     * loaded so far does. Written by the loader; a reader sees every unit below the loaded frontier.
     */
    public int firstUnitWithNestedRootName() {
        return nestedRootUnit;
    }

    /** A table without a name guard: {@link #addConstant} does not claim names (tests). */
    public DeclTable(int expectedConstants) {
        this(expectedConstants, null, null, null);
    }

    DeclTable(int expectedConstants, NameTable names, Specials specials, IntLists lists) {
        this.names = names;
        this.specials = specials;
        this.lists = lists;
        this.rows = new int[(Math.max(expectedConstants, 15) + 1) << 3];
        this.rowsPub = rows;
        this.units = new int[Math.max(expectedConstants, 16) << 2];
        this.unitsPub = units;
    }

    // ---- constants -------------------------------------------------------------------------------

    /** Number of constants; valid cids are {@code 1 .. constantCount()}. Writer-side counter. */
    public int constantCount() {
        return constants;
    }

    public int kind(int cid) {
        return rowsPub[cid << 3] & 0xF;
    }

    public int flags(int cid) {
        return rowsPub[cid << 3] & CF_MASK;
    }

    /** {@link Specials} index of the constant, or -1: one load from the constant's row. */
    public int special(int cid) {
        return ((rowsPub[cid << 3] >>> 10) & 0x3F) - 1;
    }

    public int numLevelParams(int cid) {
        return rowsPub[cid << 3] >>> 16;
    }

    public int unit(int cid) {
        return rowsPub[(cid << 3) + 1];
    }

    public int rank(int cid) {
        return rowsPub[(cid << 3) + 2];
    }

    public int name(int cid) {
        return rowsPub[(cid << 3) + 3];
    }

    public int type(int cid) {
        return rowsPub[(cid << 3) + 4];
    }

    /** Raw value word: positive shared term id, {@code -(block+1)}, or 0. */
    public int value(int cid) {
        return rowsPub[(cid << 3) + 5];
    }

    public int hint(int cid) {
        return rowsPub[(cid << 3) + 6];
    }

    public int levelParams(int cid) {
        return rowsPub[(cid << 3) + 7];
    }

    // ---- derived facts (valid only below Store.envUnits()) ----------------------------------------

    public int inductNumParams(int cid) {
        return induct.get(sideRow(cid, DeclKind.INDUCT), 0);
    }

    public int inductNumIndices(int cid) {
        return induct.get(sideRow(cid, DeclKind.INDUCT), 1);
    }

    public int inductCtorCount(int cid) {
        return induct.get(sideRow(cid, DeclKind.INDUCT), 2);
    }

    /** The {@code i}-th constructor cid of inductive type {@code cid}. */
    public int inductCtor(int cid, int i) {
        int row = sideRow(cid, DeclKind.INDUCT);
        if (i < 0 || i >= induct.get(row, 2)) {
            throw new IndexOutOfBoundsException("constructor " + i + " of inductive " + cid);
        }
        return poolPub[induct.get(row, 3) + i];
    }

    /**
     * List id of the constructor cids, in order: the id passed to the list-id form of
     * {@link #setInductive}, or 0 if the array form was used. Prefer {@link #inductCtor(int, int)}.
     */
    public int inductCtors(int cid) {
        int at = induct.get(sideRow(cid, DeclKind.INDUCT), 3);
        return at == 0 ? 0 : poolPub[at - 2];
    }

    /** List id of the cids of the user-declared types of the mutual block (see {@link #inductCtors}). */
    public int inductAll(int cid) {
        int at = induct.get(sideRow(cid, DeclKind.INDUCT), 3);
        return at == 0 ? 0 : poolPub[at - 1];
    }

    /** Number of user-declared types of the mutual block. */
    public int inductAllCount(int cid) {
        return induct.get(sideRow(cid, DeclKind.INDUCT), 6) >>> 16;
    }

    /** The {@code i}-th type cid of the mutual block. */
    public int inductAllAt(int cid, int i) {
        int row = sideRow(cid, DeclKind.INDUCT);
        if (i < 0 || i >= induct.get(row, 6) >>> 16) {
            throw new IndexOutOfBoundsException("type " + i + " of the block of inductive " + cid);
        }
        return poolPub[induct.get(row, 4) + i];
    }

    public int inductFlags(int cid) {
        return induct.get(sideRow(cid, DeclKind.INDUCT), 5);
    }

    public int inductNumNested(int cid) {
        return induct.get(sideRow(cid, DeclKind.INDUCT), 6) & 0xFFFF;
    }

    /** Cid of the type's main recursor {@code I.rec}, or 0. */
    public int inductRec(int cid) {
        return induct.get(sideRow(cid, DeclKind.INDUCT), 7);
    }

    public int ctorInduct(int cid) {
        return ctor.get(sideRow(cid, DeclKind.CTOR), 0);
    }

    public int ctorIndex(int cid) {
        return ctor.get(sideRow(cid, DeclKind.CTOR), 1);
    }

    public int ctorNumParams(int cid) {
        return ctor.get(sideRow(cid, DeclKind.CTOR), 2);
    }

    public int ctorNumFields(int cid) {
        return ctor.get(sideRow(cid, DeclKind.CTOR), 3);
    }

    public int recNumParams(int cid) {
        return rec.get(sideRow(cid, DeclKind.REC), 0);
    }

    public int recNumIndices(int cid) {
        return rec.get(sideRow(cid, DeclKind.REC), 1);
    }

    public int recNumMotives(int cid) {
        return rec.get(sideRow(cid, DeclKind.REC), 2);
    }

    public int recNumMinors(int cid) {
        return rec.get(sideRow(cid, DeclKind.REC), 3);
    }

    public int recFlags(int cid) {
        return rec.get(sideRow(cid, DeclKind.REC), 4);
    }

    /** numParams + numMotives + numMinors + numIndices. */
    public int recMajorIndex(int cid) {
        int row = sideRow(cid, DeclKind.REC);
        return rec.get(row, 0) + rec.get(row, 1) + rec.get(row, 2) + rec.get(row, 3);
    }

    /** Inductive type of the major premise (the container type for auxiliary recursors). */
    public int recMajorInduct(int cid) {
        return rec.get(sideRow(cid, DeclKind.REC), 7);
    }

    public int recRuleCount(int cid) {
        return rec.get(sideRow(cid, DeclKind.REC), 6);
    }

    public int recRuleCtor(int cid, int rule) {
        return poolPub[ruleBase(cid, rule)];
    }

    public int recRuleFields(int cid, int rule) {
        return poolPub[ruleBase(cid, rule) + 1];
    }

    public int recRuleRhs(int cid, int rule) {
        return poolPub[ruleBase(cid, rule) + 2];
    }

    /** Index of the rule for constructor {@code ctorCid}, or -1. */
    public int recRuleFor(int cid, int ctorCid) {
        int row = sideRow(cid, DeclKind.REC);
        int n = rec.get(row, 6);
        int base = rec.get(row, 5);
        int[] p = poolPub;
        for (int i = 0; i < n; i++) {
            if (p[base + 3 * i] == ctorCid) {
                return i;
            }
        }
        return -1;
    }

    private int ruleBase(int cid, int rule) {
        int row = sideRow(cid, DeclKind.REC);
        if (rule < 0 || rule >= rec.get(row, 6)) {
            throw new IndexOutOfBoundsException("rule " + rule + " of recursor " + cid);
        }
        return rec.get(row, 5) + 3 * rule;
    }

    private int sideRow(int cid, int kind) {
        int[] r = rowsPub;
        int w = cid << 3;
        if ((r[w] & 0xF) != kind) {
            throw new IllegalArgumentException("constant " + cid + " has kind " + (r[w] & 0xF) + ", not " + kind);
        }
        return r[w + 6];
    }

    // ---- units -----------------------------------------------------------------------------------

    /** Number of units written so far (volatile; readers use the store's frontiers). */
    public int unitCount() {
        return unitCount;
    }

    public int unitFirstCid(int unit) {
        return unitsPub[unit << 2];
    }

    public int unitSize(int unit) {
        return unitsPub[(unit << 2) + 1];
    }

    /** Kind of the record: the constant kind for single-constant units, INDUCT for a block. */
    public int unitKind(int unit) {
        return unitsPub[(unit << 2) + 2];
    }

    /**
     * First export term id of the unit's segment: the shared nodes written for this unit are
     * {@code unitFirstTerm(u) .. unitFirstTerm(u + 1) - 1} (to the end of the region for the last
     * unit). Every shared term below it belongs to an earlier unit and counts as shared for the
     * machine's tabling gate.
     */
    public int unitFirstTerm(int unit) {
        return unitsPub[(unit << 2) + 3];
    }

    /** A {@code DeclKind.ST_*} value (volatile read). */
    public int status(int unit) {
        return (int) INT_ELEMENTS.getVolatile(status[unit >>> STATUS_SHIFT], unit & (STATUS_CHUNK - 1));
    }

    public boolean casStatus(int unit, int expect, int update) {
        return INT_ELEMENTS.compareAndSet(status[unit >>> STATUS_SHIFT], unit & (STATUS_CHUNK - 1), expect, update);
    }

    /** The claims blob of an inductive unit ({@link StoreBuilder#blockClaims}), or null. */
    public int[] claims(int unit) {
        int[][] c = claimsPub;
        return unit < c.length ? c[unit] : null;
    }

    /** Same as {@link #claims(int)}. */
    public int[] unitClaims(int unit) {
        return claims(unit);
    }

    /** Environment thread: frees the claims blob after the comparison. */
    public void dropClaims(int unit) {
        int[][] c = claimsPub;
        if (unit < c.length) {
            c[unit] = null;
        }
    }

    // ---- writers ---------------------------------------------------------------------------------

    /**
     * Adds a constant row and claims its name; returns the cid, or 0 if the name is already declared
     * (the caller rejects the file). Loader thread. (Constants that admission generates are added by
     * the environment thread after the load, through {@link GeneratedBuilder}.)
     *
     * <p>{@code type} and {@code value} may be 0 and set later with {@link #setType} /
     * {@link #setValue}: the loader claims the names of a unit BEFORE it writes the unit's terms, so
     * that references inside the unit bind (ARCHITECTURE.md 3.3).
     */
    public int addConstant(int kind, int flags, int name, int lparams, int type, int value, int hint, int unit) {
        if (unit != openUnit) {
            throw new IllegalStateException("unit " + unit + " is not the open unit (" + openUnit + ")");
        }
        int nlp = lists == null ? 0 : lists.length(lparams);
        if (nlp > 0xFFFF) {
            throw new StoreLimitException("more than 65,535 universe parameters");
        }
        int cid = constants + 1;
        if (names != null && !names.claim(name, cid)) {
            return 0;
        }
        int w = cid << 3;
        if (w + 8 > rows.length) {
            if (cid >= (1 << 27)) {
                throw new StoreLimitException("more than 2^27 constants");
            }
            int[] bigger = Arrays.copyOf(rows, (int) Math.min((long) rows.length * 2, Integer.MAX_VALUE - 15));
            rows = bigger;
            rowsPub = bigger;
        }
        int[] r = rows;
        int rank = unit + 1;
        int sp = specials == null ? -1 : specials.indexOfName(name);
        int side = 0;
        switch (kind) {
            case DeclKind.INDUCT:
                side = induct.alloc();
                break;
            case DeclKind.CTOR:
                side = ctor.alloc();
                break;
            case DeclKind.REC:
                side = rec.alloc();
                break;
            default:
                break;
        }
        r[w] = (kind & 0xF) | (flags & CF_MASK) | ((sp + 1) << 10) | (nlp << 16);
        r[w + 1] = unit;
        r[w + 2] = rank;
        r[w + 3] = name;
        r[w + 4] = type;
        r[w + 5] = value;
        r[w + 6] = side != 0 ? side : hint;
        r[w + 7] = lparams;
        constants = cid;
        if (nestedRootUnit < 0 && names != null && names.hasNestedRoot(name)) {
            nestedRootUnit = unit;
        }
        int u = unit << 2;
        if (units[u + 1] == 0) {
            units[u] = cid;
        }
        units[u + 1]++;
        if (sp >= 0) {
            specials.bindLoaded(sp, cid, rank);
        }
        return cid;
    }

    /**
     * Environment thread, after the load is complete ({@link GeneratedBuilder#constant}): adds a
     * constant that admission generated for {@code unit} - a recursor without an export record, a
     * quotient constant. The row is flagged {@link #CF_GENERATED}, belongs to {@code unit} and has
     * that unit's rank; it is not one of the unit's own constants ({@link #unitFirstCid} /
     * {@link #unitSize} still describe the export's records) and is found through
     * {@link #generatedCid}. Returns the cid, or 0 if the name is already declared.
     */
    int addGenerated(int kind, int name, int lparams, int type, int unit) {
        if (openUnit >= 0) {
            throw new IllegalStateException("unit " + openUnit + " is still being loaded");
        }
        if (unit < 0 || unit >= unitCount) {
            throw new IllegalArgumentException("no unit " + unit);
        }
        int nlp = lists == null ? 0 : lists.length(lparams);
        if (nlp > 0xFFFF) {
            throw new StoreLimitException("more than 65,535 universe parameters");
        }
        int cid = constants + 1;
        if (cid >= (1 << 27)) {
            throw new StoreLimitException("more than 2^27 constants");
        }
        if (names != null && !names.claim(name, cid)) {
            return 0;
        }
        int w = cid << 3;
        if (w + 8 > rows.length) {
            int[] bigger = Arrays.copyOf(rows, (int) Math.min((long) rows.length * 2, Integer.MAX_VALUE - 15));
            rows = bigger;
            rowsPub = bigger;
        }
        int[] r = rows;
        int u = unit << 2;
        int rank = units[u + 1] > 0 ? r[(units[u] << 3) + 2] : unit + 1;
        int sp = specials == null ? -1 : specials.indexOfName(name);
        int side = 0;
        switch (kind) {
            case DeclKind.INDUCT:
                side = induct.alloc();
                break;
            case DeclKind.CTOR:
                side = ctor.alloc();
                break;
            case DeclKind.REC:
                side = rec.alloc();
                break;
            default:
                break;
        }
        int flags = CF_GENERATED | (units[u + 2] == DeclKind.INDUCT ? CF_BLOCK_MEMBER : 0);
        r[w] = (kind & 0xF) | flags | ((sp + 1) << 10) | (nlp << 16);
        r[w + 1] = unit;
        r[w + 2] = rank;
        r[w + 3] = name;
        r[w + 4] = type;
        r[w + 5] = 0;
        r[w + 6] = side;
        r[w + 7] = lparams;
        constants = cid;
        if (firstGenerated == 0) {
            firstGenerated = cid;
        }
        generatedCount = generatedCount + 1;
        if (sp >= 0) {
            specials.bindLoaded(sp, cid, rank);
        }
        return cid;
    }

    /** Number of constants that admission generated (readable for units below the environment frontier). */
    public int generatedCount() {
        return generatedCount;
    }

    /** The {@code i}-th generated constant, in the order they were made (they follow the loader's constants). */
    public int generatedCid(int i) {
        if (i < 0 || i >= generatedCount) {
            throw new IndexOutOfBoundsException("generated constant " + i + " of " + generatedCount);
        }
        return firstGenerated + i;
    }

    /** True if admission generated constant {@code cid} (the export has no record of it). */
    public boolean isGenerated(int cid) {
        return (rowsPub[cid << 3] & CF_GENERATED) != 0;
    }

    /** Loader thread, before the unit is published. */
    public void setType(int cid, int type) {
        rows[(cid << 3) + 4] = type;
    }

    /** Loader thread, before the unit is published. {@code value}: shared term id, {@code -(block+1)}, or 0. */
    public void setValue(int cid, int value) {
        int w = cid << 3;
        rows[w + 5] = value;
        if (value < 0) {
            rows[w] |= CF_BLOCK_VALUE;
        } else {
            rows[w] &= ~CF_BLOCK_VALUE;
        }
    }

    /** Loader thread, before the unit is published; only for kinds without a side row. */
    public void setHint(int cid, int hint) {
        int w = cid << 3;
        int k = rows[w] & 0xF;
        if (k == DeclKind.INDUCT || k == DeclKind.CTOR || k == DeclKind.REC) {
            throw new IllegalArgumentException("constant " + cid + " keeps its side row in the hint word");
        }
        rows[w + 6] = hint;
    }

    /** Opens a unit; its constants are the ones added until {@link #endUnit()}. Returns the unit index. */
    public int beginUnit(int recordKind) {
        return beginUnit(recordKind, 0);
    }

    int beginUnit(int recordKind, int firstTerm) {
        if (openUnit >= 0) {
            throw new IllegalStateException("unit " + openUnit + " is still open");
        }
        int unit = unitCount;
        int u = unit << 2;
        if (u + 4 > units.length) {
            int[] bigger = Arrays.copyOf(units, units.length * 2);
            units = bigger;
            unitsPub = bigger;
        }
        int chunk = unit >>> STATUS_SHIFT;
        if (chunk >= STATUS_DIR) {
            throw new StoreLimitException("more than 2^26 declaration records");
        }
        if (status[chunk] == null) {
            status[chunk] = new int[STATUS_CHUNK];
        }
        units[u] = 0;
        units[u + 1] = 0;
        units[u + 2] = recordKind;
        units[u + 3] = firstTerm;
        openUnit = unit;
        return unit;
    }

    public void endUnit() {
        if (openUnit < 0) {
            throw new IllegalStateException("no open unit");
        }
        openUnit = -1;
        unitCount = unitCount + 1;
    }

    /** The unit being written, or -1. */
    int openUnit() {
        return openUnit;
    }

    /** Loader thread, before publication: marks a unit that holds a dropped (unsafe / partial) record. */
    void setStatusUnpublished(int unit, int st) {
        INT_ELEMENTS.setVolatile(status[unit >>> STATUS_SHIFT], unit & (STATUS_CHUNK - 1), st);
    }

    /**
     * Loader thread: gives every constant of {@code unit} the rank {@code rank} (reordered admission).
     * Must be called before any term that mentions the unit's constants is written, because ranks are
     * copied into the {@code depMax} of term nodes.
     */
    public void setRank(int unit, int rank) {
        if (rank <= 0) {
            throw new IllegalArgumentException("ranks are 1-based");
        }
        int first = units[unit << 2];
        int n = units[(unit << 2) + 1];
        for (int cid = first; cid < first + n; cid++) {
            rows[(cid << 3) + 2] = rank;
            int sp = ((rows[cid << 3] >>> 10) & 0x3F) - 1;
            if (sp >= 0 && specials != null) {
                specials.bindLoaded(sp, cid, rank);
            }
        }
    }

    /** Loader thread. */
    void setClaims(int unit, int[] blob) {
        if (unit >= claims.length) {
            int[][] bigger = Arrays.copyOf(claims, Math.max(claims.length * 2, unit + 1));
            claims = bigger;
            claimsPub = bigger;
        }
        claims[unit] = blob;
    }

    /**
     * Environment thread: the derived facts of an inductive type, with the constructor and block
     * lists given as arrays of cids (stored in the table).
     */
    public void setInductive(int cid, int numParams, int numIndices, int[] ctors, int ctorCount, int[] all,
            int allCount, int flags, int numNested, int recCid) {
        setInductive(cid, numParams, numIndices, ctors, ctorCount, all, allCount, flags, numNested, recCid, 0, 0);
    }

    /**
     * List-id form: the two lists are read through the store's base list store and copied into the
     * table, so the ids must be readable there (lists interned by the loader). Use the array form
     * for lists built by the environment thread.
     */
    public void setInductive(int cid, int numParams, int numIndices, int ctorsList, int allList, int flags,
            int numNested, int recCid) {
        if (lists == null) {
            throw new IllegalStateException("no list store: use the array form of setInductive");
        }
        int nc = lists.length(ctorsList);
        int na = lists.length(allList);
        int[] c = new int[nc];
        int[] a = new int[na];
        lists.copyTo(ctorsList, c, 0);
        lists.copyTo(allList, a, 0);
        setInductive(cid, numParams, numIndices, c, nc, a, na, flags, numNested, recCid, ctorsList, allList);
    }

    private void setInductive(int cid, int numParams, int numIndices, int[] ctors, int ctorCount, int[] all,
            int allCount, int flags, int numNested, int recCid, int ctorsList, int allList) {
        if (numNested < 0 || numNested > 0xFFFF || allCount > 0x7FFF) {
            throw new StoreLimitException("inductive block too large");
        }
        int row = sideRow(cid, DeclKind.INDUCT);
        int at = poolAlloc(2 + ctorCount + allCount);
        pool[at] = ctorsList;
        pool[at + 1] = allList;
        System.arraycopy(ctors, 0, pool, at + 2, ctorCount);
        System.arraycopy(all, 0, pool, at + 2 + ctorCount, allCount);
        induct.set(row, 0, numParams);
        induct.set(row, 1, numIndices);
        induct.set(row, 2, ctorCount);
        induct.set(row, 3, at + 2);
        induct.set(row, 4, at + 2 + ctorCount);
        induct.set(row, 5, flags);
        induct.set(row, 6, numNested | (allCount << 16));
        induct.set(row, 7, recCid);
    }

    /** Environment thread. */
    public void setConstructor(int cid, int inductCid, int cidx, int numParams, int numFields) {
        int row = sideRow(cid, DeclKind.CTOR);
        ctor.set(row, 0, inductCid);
        ctor.set(row, 1, cidx);
        ctor.set(row, 2, numParams);
        ctor.set(row, 3, numFields);
    }

    /**
     * Environment thread: the derived facts of a recursor, with its rules as {@code ruleCount}
     * triples (constructor cid, number of fields, rhs term id) in {@code rules}.
     */
    public void setRecursor(int cid, int numParams, int numIndices, int numMotives, int numMinors, int flags,
            int[] rules, int ruleCount, int majorInduct) {
        int row = sideRow(cid, DeclKind.REC);
        int at = poolAlloc(3 * ruleCount);
        System.arraycopy(rules, 0, pool, at, 3 * ruleCount);
        rec.set(row, 0, numParams);
        rec.set(row, 1, numIndices);
        rec.set(row, 2, numMotives);
        rec.set(row, 3, numMinors);
        rec.set(row, 4, flags);
        rec.set(row, 5, at);
        rec.set(row, 6, ruleCount);
        rec.set(row, 7, majorInduct);
    }

    /** List-id form (rules list = triples); the id must be readable in the store's base list store. */
    public void setRecursor(int cid, int numParams, int numIndices, int numMotives, int numMinors, int flags,
            int rulesList, int majorInduct) {
        if (lists == null) {
            throw new IllegalStateException("no list store: use the array form of setRecursor");
        }
        int n = lists.length(rulesList);
        if (n % 3 != 0) {
            throw new IllegalArgumentException("a rules list is a sequence of triples");
        }
        int[] r = new int[n];
        lists.copyTo(rulesList, r, 0);
        setRecursor(cid, numParams, numIndices, numMotives, numMinors, flags, r, n / 3, majorInduct);
    }

    private int poolAlloc(int n) {
        int at = poolUsed;
        if ((long) at + n > pool.length) {
            long want = Math.max((long) pool.length * 2, (long) at + n);
            if (want > Integer.MAX_VALUE - 15) {
                throw new StoreLimitException("declaration side pool exceeds 2^31 ints");
            }
            int[] bigger = Arrays.copyOf(pool, (int) want);
            pool = bigger;
            poolPub = bigger;
        }
        poolUsed = at + n;
        return at;
    }

    /** Bytes in use: rows of the constants and units written so far, the side pool, statuses, side rows, claims. */
    public long usedBytes() {
        long statusBytes = 4L * unitCount;
        return 32L * (constants + 1) + 16L * unitCount + 4L * poolUsed + statusBytes + induct.usedBytes()
                + ctor.usedBytes() + rec.usedBytes() + claimBytes();
    }

    private long claimBytes() {
        long b = 0;
        int[][] c = claimsPub;
        for (int[] blob : c) {
            if (blob != null) {
                b += 4L * blob.length + 16;
            }
        }
        return b + 8L * c.length;
    }

    public long bytes() {
        long statusBytes = 0;
        for (int[] c : status) {
            if (c != null) {
                statusBytes += 4L * c.length;
            }
        }
        return 4L * (rows.length + units.length + poolPub.length) + statusBytes + induct.bytes() + ctor.bytes()
                + rec.bytes() + claimBytes();
    }

    /**
     * Rows of {@code 1 << shift} ints in chunks that never move: the loader allocates rows, the
     * environment thread fills them, readers see them through the frontiers. Row 0 is "none".
     */
    private static final class Side {
        private static final int CHUNK_SHIFT = 10;
        private static final int DIR = 1 << 13;
        private final int shift;
        private final int[][] dir = new int[DIR][];
        private int next = 1;

        Side(int shift) {
            this.shift = shift;
        }

        int alloc() {
            int row = next;
            int c = row >>> CHUNK_SHIFT;
            if (c >= DIR) {
                throw new StoreLimitException("side table full");
            }
            if (dir[c] == null) {
                dir[c] = new int[1 << (CHUNK_SHIFT + shift)];
            }
            next = row + 1;
            return row;
        }

        int get(int row, int k) {
            return dir[row >>> CHUNK_SHIFT][((row & ((1 << CHUNK_SHIFT) - 1)) << shift) + k];
        }

        void set(int row, int k, int v) {
            dir[row >>> CHUNK_SHIFT][((row & ((1 << CHUNK_SHIFT) - 1)) << shift) + k] = v;
        }

        long bytes() {
            return 4L * (((long) (next + (1 << CHUNK_SHIFT) - 1) >>> CHUNK_SHIFT) << (CHUNK_SHIFT + shift)) + 8L * DIR;
        }

        long usedBytes() {
            return 4L * ((long) next << shift);
        }
    }
}
