package rlvm.inductive;

/**
 * The inputs of one inductive block, plus the claims of the export that must be compared. Filled by
 * {@code rlvm-admit} from the declaration table and the block's claims blob. Plain mutable arrays;
 * an instance is reused from block to block (arrays may be longer than the counts say).
 *
 * <p>Conventions: a "name" is a {@code NameTable} id, a "list" an {@code IntLists} id, a "term" a
 * shared term id, a "cid" a constant id of the declaration table. Claims are kept as the export
 * wrote them: names stay names (they are compared with the names of the derived constants), numbers
 * stay {@code long}.
 */
public final class BlockInput {

    /**
     * The unit, and the rank of the scope the block is admitted in: constants of smaller rank count as
     * "in the environment". For a file in declaration order this is the unit's own rank; when
     * {@code rlvm-admit} admits in dependency order (a block that uses declarations placed later in the
     * file) it is larger than the rank of every declaration the block depends on.
     */
    public int unit;
    public int rank;

    // ---- inputs (the only data admission derives from) ------------------------------------------------
    /** Universe-parameter names (list id). */
    public int levelParams;
    public int numParams;
    /** Number of types ({@code all} of the first type record), in the order of that list. */
    public int typeCount;
    /** Per type: cid, name, type term. */
    public int[] typeCid;
    public int[] typeName;
    public int[] typeType;
    /**
     * Per type: first constructor index and count in the ctor arrays below (order of its {@code ctors}
     * list). The ranges of different types must not overlap. Constructor records of the item that no
     * type lists come after all ranges (they are rejected by {@link RecordCompare}).
     */
    public int[] typeCtorStart;
    public int[] typeCtorCount;
    /** Number of constructor records of the item (listed ones and others). */
    public int ctorCount;
    public int[] ctorCid;
    public int[] ctorName;
    public int[] ctorType;

    // ---- claims: exported constructor records --------------------------------------------------------
    /** Universe-parameter names of the constructor record (list id). */
    public int[] ctorClaimLevelParams;
    /** Name of the type the record says it belongs to ({@code induct}). */
    public int[] ctorClaimInduct;
    public long[] ctorClaimCidx;
    public long[] ctorClaimNumParams;
    public long[] ctorClaimNumFields;
    /**
     * Optional (null = no such record). Per constructor record: the record is flagged unsafe inside a
     * safe block. The official checker still takes the constructor's name and type from it (they are
     * inputs) but never compares the record with the generated constructor, so its claims
     * ({@code induct}, {@code cidx}, {@code numParams}, {@code numFields}) are not compared here either.
     * Its universe parameters must still be the block's: the row of the constant carries them, and a
     * row that differs from the generated constant cannot be admitted ({@code declineReason}).
     */
    public boolean[] ctorUnsafe;

    // ---- claims: exported recursor records (any order; matched by name) -------------------------------
    public int recCount;
    public int[] recCid;
    public int[] recName;
    public int[] recType;
    /** Universe-parameter names of the recursor record (list id). */
    public int[] recLevelParams;
    /** Per recursor: list id of the names in its {@code all}. */
    public int[] recAll;
    public long[] recNumParams;
    public long[] recNumIndices;
    public long[] recNumMotives;
    public long[] recNumMinors;
    public boolean[] recK;
    /**
     * Optional (null = no such record). Per recursor record: the record is flagged unsafe inside a safe
     * block. The official checker ignores such a record (the generated recursor exists whatever the
     * record says), so a difference is never a rejection; but the row of the constant then differs
     * from the constant the kernel generates, which cannot be admitted without generated constants:
     * {@code GeneratedBlock.declineReason} is set instead.
     */
    public boolean[] recUnsafe;
    /**
     * Per recursor: number of rules. Optional for callers that size the rule arrays exactly: if this
     * field is null the length of {@code recRuleCtorName[r]} is the number of rules.
     */
    public int[] recRuleCount;
    /** Per recursor: rule constructor names, field counts, right-hand sides. */
    public int[][] recRuleCtorName;
    public long[][] recRuleFields;
    public int[][] recRuleRhs;

    /**
     * True if the block's records were consistent enough to fill this structure (checklist item 25).
     * {@link InductiveAdmission#generate} declines ({@code UNSUPPORTED_INPUT}) when it is false: the
     * official checker has no notion of an item (it finds constructors and the block's types by name
     * in one flat map), so an inconsistent item is not necessarily a rejection there.
     */
    public boolean consistent;
}
