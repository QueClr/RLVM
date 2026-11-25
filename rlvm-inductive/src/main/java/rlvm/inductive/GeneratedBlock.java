package rlvm.inductive;

/**
 * What the kernel derives for one inductive block: the facts that go into the declaration table's
 * side tables. Terms are ids of the admission overlay; {@code rlvm-admit} commits them (*agentic-note*: design-architecture-document,
 * 4.6: when an exported record is identical, the exported shared term is used; otherwise the term is
 * copied into the generated region).
 *
 * <p>Filled by {@link InductiveAdmission#generate}; arrays are reused and may be longer than the
 * counts say, except the per-recursor rule arrays, whose lengths are exactly {@code recRuleCount[j]}.
 */
public final class GeneratedBlock {

    /** Number of auxiliary types the nested-inductive elimination created ({@code numNested}). */
    public int numNested;
    /** Block-wide, computed on the auxiliary block (a nested block is always recursive). */
    public boolean isRec;
    public boolean isReflexive;
    /** Recursors can only eliminate into Prop. */
    public boolean elimOnlyAtZero;
    public boolean k;

    /** Per user type. */
    public int[] typeNumIndices;

    /**
     * Per constructor record (order of {@link BlockInput}): number of fields and position in its
     * type's {@code ctors} list; both -1 for a record that no type of the block lists.
     */
    public int[] ctorNumFields;
    public int[] ctorIndex;

    /** Recursors: the user types' first, then the auxiliary ones ({@code rec_1 ..}). */
    public int recCount;
    /** Name as (prefix name id, last component): the user type's name + "rec", or first type + "rec_j". */
    public int[] recNamePrefix;
    public String[] recNameLast;
    /** Universe-parameter names (list id): {@code [u*] ++ lparams} or {@code lparams}. */
    public int recLevelParams;
    public int[] recType;
    public int[] recNumIndices;
    public int recNumMotives;
    public int recNumMinors;
    /** Per recursor: number of rules (= number of constructors of the type it eliminates). */
    public int[] recRuleCount;
    /**
     * Per recursor, per rule: constructor cid (for an auxiliary recursor: the container's
     * constructor), number of fields, right-hand side.
     */
    public int[][] recRuleCtor;
    public int[][] recRuleFields;
    public int[][] recRuleRhs;
    /** Per recursor: cid of the inductive type of its major premise (auxiliary: the container type). */
    public int[] recMajorInduct;

    /**
     * Null, or why the block must be DECLINED although every check this module can make has passed
     * and the derived facts above are complete (README, "Declines"): the official verdict then depends
     * on something that is not reproduced here. {@link RecordCompare#compare} throws
     * {@code ResourceException(declineReason)} after all comparisons have passed, so that a forged
     * record is still a rejection; a caller that does not call {@code compare} must test this field.
     */
    public rlvm.api.Reason declineReason;

    /** Null, or the cause of {@link #declineReason} in words (for the message of the decline). */
    public String declineDetail;
}
