package rlvm.inductive;

import rlvm.api.KernelException;
import rlvm.api.KernelOps;
import rlvm.api.Reason;
import rlvm.api.ResourceException;
import rlvm.store.DeclTable;
import rlvm.store.IntLists;
import rlvm.store.NameTable;
import rlvm.store.Store;
import rlvm.store.TermOps;

/**
 * Compares the exported constructor and recursor records of a block with the derived ones
 * (checklist items 49 - 51): every safe exported record must have a derived constant of the same
 * name and kind and be identical to it in every field; expressions by structural {@code ==}
 * ({@code TermOps.eqv}), names and universe-parameter lists exactly.
 *
 * <p>Deliberately no fallback to definitional equality (unsound through proof irrelevance:
 * {@code bugs/rec-rule-proof-irrel}, {@code rec-type-proof-irrel}).
 *
 * <p>What is compared (03 section 3): constructor {@code levelParams}, {@code induct}, {@code cidx},
 * {@code numParams}, {@code numFields} (its type is an input); recursor {@code levelParams},
 * {@code all}, {@code numParams}, {@code numIndices}, {@code numMotives}, {@code numMinors},
 * {@code k}, {@code type}, and per rule {@code ctor}, {@code nfields}, {@code rhs}, in order.
 *
 * <p>Records flagged unsafe inside a safe block ({@code BlockInput.ctorUnsafe}, {@code recUnsafe}) are
 * what the official checker never compares (it skips unsafe constants when it replays, and the kernel
 * generates the block's constructors and recursors whatever such a record says): a difference in one
 * of them is not a rejection. The row of such a constant must nevertheless be the generated constant,
 * because later declarations read it; where it is not, {@code GeneratedBlock.declineReason} is set
 * (the difference could only be represented by a generated constant).
 */
public final class RecordCompare {

    private final Store store;
    private final KernelOps ops;
    /** The cause of the last decline raised by {@link #compare}, or null. */
    private String declineDetail;

    /**
     * Why the last call of {@link #compare} ended with a {@code ResourceException}
     * ({@code GeneratedBlock.declineDetail}); null if it did not.
     */
    public String lastDeclineDetail() {
        return declineDetail;
    }

    public RecordCompare(Store store, KernelOps ops) {
        if (store == null || ops == null) {
            throw new NullPointerException();
        }
        this.store = store;
        this.ops = ops;
    }

    private static KernelException mismatch() {
        return new KernelException(Reason.RECORD_MISMATCH);
    }

    private static KernelException mismatch(int a, int b) {
        return new KernelException(Reason.RECORD_MISMATCH, a, b);
    }

    private static boolean sameList(IntLists lists, int a, int b) {
        if (a == b) {
            return true;
        }
        int n = lists.length(a);
        if (n != lists.length(b)) {
            return false;
        }
        for (int i = 0; i < n; i++) {
            if (lists.get(a, i) != lists.get(b, i)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Throws {@code KernelException(RECORD_MISMATCH)} on the first difference; if there is none and
     * {@code generated.declineReason} is set, {@code ResourceException} with that reason.
     *
     * @return a bit set over the derived recursors: bit {@code j} set if the export contains a record
     *     for recursor {@code j} (those reuse the exported shared terms; the others are generated
     *     constants). Recursors with index 64 and above are not represented in the result (they have
     *     been compared like the others).
     */
    public long compare(BlockInput in, GeneratedBlock generated) {
        NameTable names = store.names();
        DeclTable decls = store.decls();
        TermOps tops = ops.termOps();
        IntLists lists = ops.levels().lists();

        // ---- constructors (items 49, 51) ---------------------------------------------------------------
        boolean undecidable = false;
        String why = null;
        declineDetail = null;
        for (int k = 0; k < in.typeCount; k++) {
            for (int i = in.typeCtorStart[k], e = i + in.typeCtorCount[k]; i < e; i++) {
                if (generated.ctorIndex[i] < 0) {
                    throw mismatch(); // listed, but not derived: cannot happen after generate()
                }
                if (in.ctorUnsafe != null && in.ctorUnsafe[i]) {
                    // Never compared officially (the record is not replayed). The row keeps the record's
                    // universe parameters, which must then be the generated constructor's.
                    if (!sameList(lists, in.ctorClaimLevelParams[i], in.levelParams)) {
                        undecidable = true;
                        why = "a constructor record flagged unsafe has other universe parameters than its block: the"
                                + " official checker ignores the record, but its row is not the constructor the kernel"
                                + " generates (generated constants are deferred)";
                    }
                    continue;
                }
                if (!sameList(lists, in.ctorClaimLevelParams[i], in.levelParams)
                        || in.ctorClaimInduct[i] != in.typeName[k]
                        || in.ctorClaimCidx[i] != generated.ctorIndex[i]
                        || in.ctorClaimNumParams[i] != in.numParams
                        || in.ctorClaimNumFields[i] != generated.ctorNumFields[i]) {
                    throw mismatch();
                }
            }
        }
        // A constructor record that no type of the block lists has no derived twin ("No such constructor").
        for (int i = 0; i < in.ctorCount; i++) {
            if (generated.ctorIndex[i] < 0) {
                throw mismatch();
            }
        }

        // ---- recursors (items 50, 51); matched by name (trap T8) ------------------------------------------
        long present = 0;
        for (int r = 0; r < in.recCount; r++) {
            boolean ignorable = in.recUnsafe != null && in.recUnsafe[r];
            int name = in.recName[r];
            int j = -1;
            if (name != 0 && !names.isNumeric(name)) {
                int prefix = names.prefix(name);
                for (int q = 0; q < generated.recCount; q++) {
                    if (generated.recNamePrefix[q] == prefix && names.lastComponentIs(name, generated.recNameLast[q])) {
                        j = q;
                        break;
                    }
                }
            }
            if (j < 0) {
                if (ignorable) {
                    undecidable = true; // the official checker ignores the record; its row has no generated twin
                    why = "a recursor record flagged unsafe names no recursor of the block: the official checker ignores"
                            + " the record, but its row would be a constant the kernel never generates";
                    continue;
                }
                throw mismatch(); // "No such recursor"
            }
            if (j < 64) {
                if ((present & (1L << j)) != 0) {
                    throw mismatch(); // two records for one recursor: excluded by the flat namespace
                }
                present |= 1L << j;
            }
            if (!sameRecursor(in, generated, r, j, lists, decls, tops)) {
                if (ignorable) {
                    undecidable = true; // ignored officially, but the row is not the generated recursor
                    why = "the recursor record '" + names.toString(name) + "' is flagged unsafe and differs from the"
                            + " recursor the kernel generates: the official checker ignores the record, here its row"
                            + " would have to be replaced by a generated constant (generated constants are deferred)";
                    continue;
                }
                throw mismatchOfRecursor(in, generated, r, j, tops);
            }
        }
        if (undecidable && generated.declineReason == null) {
            generated.declineReason = Reason.UNSUPPORTED_INPUT;
            generated.declineDetail = why;
        }
        if (generated.declineReason != null) {
            declineDetail = generated.declineDetail;
            // nothing is forged, but the block itself cannot be admitted here (GeneratedBlock.declineReason)
            throw new ResourceException(generated.declineReason);
        }
        return present;
    }
    /** True if exported recursor record {@code r} is identical to derived recursor {@code j} in every compared field. */
    private static boolean sameRecursor(BlockInput in, GeneratedBlock generated, int r, int j, IntLists lists,
            DeclTable decls, TermOps tops) {
        if (!sameList(lists, in.recLevelParams[r], generated.recLevelParams)) {
            return false;
        }
        // all = the user-declared types of the block, in order
        int all = in.recAll[r];
        if (lists.length(all) != in.typeCount) {
            return false;
        }
        for (int k = 0; k < in.typeCount; k++) {
            if (lists.get(all, k) != in.typeName[k]) {
                return false;
            }
        }
        if (in.recNumParams[r] != in.numParams || in.recNumIndices[r] != generated.recNumIndices[j]
                || in.recNumMotives[r] != generated.recNumMotives || in.recNumMinors[r] != generated.recNumMinors
                || in.recK[r] != generated.k) {
            return false;
        }
        if (!tops.eqv(in.recType[r], generated.recType[j])) {
            return false;
        }
        int[] gCtor = generated.recRuleCtor[j];
        int[] gFields = generated.recRuleFields[j];
        int[] gRhs = generated.recRuleRhs[j];
        int[] xCtor = in.recRuleCtorName[r];
        long[] xFields = in.recRuleFields[r];
        int[] xRhs = in.recRuleRhs[r];
        int nrules = generated.recRuleCount[j];
        int xrules = in.recRuleCount != null ? in.recRuleCount[r] : xCtor == null ? 0 : xCtor.length;
        if (xrules != nrules) {
            return false;
        }
        for (int i = 0; i < nrules; i++) {
            if (xCtor[i] != decls.name(gCtor[i]) || xFields[i] != gFields[i]) {
                return false;
            }
            if (!tops.eqv(xRhs[i], gRhs[i])) {
                return false;
            }
        }
        return true;
    }

    /** The rejection for a safe record that differs: carries the two terms when the difference is a term. */
    private static KernelException mismatchOfRecursor(BlockInput in, GeneratedBlock generated, int r, int j,
            TermOps tops) {
        if (!tops.eqv(in.recType[r], generated.recType[j])) {
            return mismatch(in.recType[r], generated.recType[j]);
        }
        return mismatch();
    }
}
