package rlvm.machine;

import static rlvm.machine.Op.SIG;

import rlvm.api.MachineErrors;
import rlvm.store.DeclKind;
import rlvm.store.DeclTable;
import rlvm.store.Node;
import rlvm.store.Terms;

/**
 * The {@code DECL} driver (ISA 4.5.6): the Java method that calls {@link Machine#drive}; its locals are
 * the declaration's phase state (stepper-convention.md H6). Owner: M5.
 */
final class Decl {
    private Decl() {}

    /**
     * DECL(row) (ISA 4.5.6, 14.1): the official {@code add_axiom / add_definition / add_theorem / add_opaque} after the
     * structural checks (R03 4.2). {@code m.beginDecl} was called by the facade. Phases, each a judgment run to completion
     * with {@code m.drive(.., d = 0)}: ENSORT(INFER(type, CHECKED)); def / thm / opaque: V = EVAL(type),
     * CHECK(value, V); theorem: ISPROP(V) must hold (E_NOT_PROP). A value kept as a proof block is decoded into the
     * overlay first (reload the term arrays, R4). Inductive, constructor, recursor and quot rows are not checked here.
     *
     * <p>Order of the official {@code add_theorem} (R03 4.2; {@code RefKernel.checkConstantInt}): the type is
     * checked, then it must be a proposition, then the value is checked against it. "The type is a
     * proposition" is decided on the sort level that phase 1 returned: the official {@code is_prop(type)}
     * re-infers the same sort in infer-only mode.
     *
     * <p>Before the first phase the regime of ISA 5.3 is fixed for this declaration: if both roots pass the
     * summary test ({@code depMax < rank}, no UNRESOLVED flag, {@code lpSet} within the declared parameters),
     * CHKCONST and CHKLVL are no-ops ({@code Infer.Ext.trusted}); otherwise they run per node. The check
     * generation stays while consecutive declarations pass the test, or while the level parameters of this
     * declaration contain those of the previous one; otherwise it advances (5.3).
     * @return {@code MachineErrors.E_NONE} (0) = ACCEPT; else the code of the failure ({@code MachineErrors.outcome}
     *     gives REJECT or DECLINE). After a failure the machine stacks have been restored with {@code m.abandon()}
     */
    static int check(Machine m, int cid) {
        DeclTable decls = m.decls;
        int kind = decls.kind(cid);
        if (kind != DeclKind.AXIOM && kind != DeclKind.DEFN && kind != DeclKind.THM && kind != DeclKind.OPAQUE) {
            return MachineErrors.E_INTERNAL; // not a row an engine checks: no verdict
        }
        Terms terms = m.terms;
        int type = decls.type(cid);
        int value = 0;
        if (kind != DeclKind.AXIOM) {
            int v = decls.value(cid);
            if (v > 0) {
                value = v;
            } else if (v < 0) {
                value = m.ctx.overlay.blockRoot(-v - 1); // the proof block, decoded into the worker's overlay
                terms.refresh(); // the overlay array may have been reallocated (ISA 12 R4)
            } else {
                return MachineErrors.E_INTERNAL;
            }
        }
        if (type <= 0) {
            return MachineErrors.E_INTERNAL;
        }
        if (!terms.isClosed(type) || (value != 0 && !terms.isClosed(value))) {
            return MachineErrors.E_LOOSE_BVAR; // infer_type_core: has_loose_bvars(e) -> throw (R02 6.1)
        }
        regime(m, cid, type, value);
        return phases(m, cid, kind, type, value);
    }

    /** Fixes the regime of ISA 5.3 for this declaration and applies the generation rule. */
    private static void regime(Machine m, int cid, int type, int value) {
        DeclTable decls = m.decls;
        int rank = decls.rank(cid);
        int lparams = decls.levelParams(cid);
        Infer.Ext x = m.x5;
        boolean trusted = rootOk(m, type, rank, lparams) && (value == 0 || rootOk(m, value, rank, lparams));
        if (!(trusted && x.prevTrusted) && !m.lists.subsetOf(x.prevLparams, lparams)) {
            m.gen++; // check-mode results of earlier declarations no longer answer checking queries (5.3)
        }
        x.prevLparams = lparams;
        x.prevTrusted = trusted;
        x.trusted = trusted;
        x.declLparams = lparams;
        x.firstTerm = m.unit >= 0 ? decls.unitFirstTerm(m.unit) : 0;
    }

    /** The phases of DECL (ISA 4.5.6), each a judgment driven to completion from host depth 0. */
    private static int phases(Machine m, int cid, int kind, int type, int value) {
        // phase 1: check_constant_val: the type is well typed and its type is (or reduces to) a sort
        int r = m.drive(Infer.infer(m, type, 0, 0, 0, Op.CHECKED, 0));
        if (r >= SIG) {
            return failed(m);
        }
        int u = m.drive(Infer.ensort(m, r, 0));
        if (u >= SIG) {
            return failed(m);
        }
        if (kind == DeclKind.AXIOM) {
            return accepted(m, cid);
        }
        if (kind == DeclKind.THM && !m.levels.isAlwaysZero(u)) {
            m.abandon();
            return MachineErrors.E_NOT_PROP; // theorem_type_is_not_prop (R03 4.2)
        }
        // phase 2: V = EVAL(type); CHECK(value, V)
        int v = m.drive(Eval.eval(m, type, 0, 0, 0, 0));
        if (v >= SIG) {
            return failed(m);
        }
        r = m.drive(Infer.check(m, value, 0, 0, 0, v, 0));
        if (r >= SIG) {
            return failed(m);
        }
        return accepted(m, cid);
    }

    /** The root summaries of ISA 5.3 / ARCHITECTURE 4.3 on one root, O(1). */
    private static boolean rootOk(Machine m, int root, int rank, int lparams) {
        Terms terms = m.terms;
        int hdr = terms.header(root);
        if ((hdr & Node.F_UNRESOLVED) != 0 || terms.depMax(root) >= rank) {
            return false;
        }
        int lp = terms.lpSet(root);
        return lp == 0 || m.lists.subsetOf(lp, lparams);
    }

    /**
     * ACCEPT is produced only here, after the last phase completed with a result (ISA section 9). A judgment
     * that completes has restored every machine stack (I-STACKS, I-SCOPE); if one did not, the declaration is
     * declined rather than accepted on a machine in an unknown state.
     */
    private static int accepted(Machine m, int cid) {
        if (!m.idle()) {
            m.abandon();
            return MachineErrors.E_INTERNAL;
        }
        if (m.on(Op.SW_FACTS) && !m.strict) {
            Facts.onAccept(m, cid);
        }
        return MachineErrors.E_NONE;
    }

    /** A phase ended with ABORT or FATAL: the stacks are restored and the code of the failure is the result. */
    private static int failed(Machine m) {
        m.abandon();
        int e = m.err;
        return e != MachineErrors.E_NONE ? e : MachineErrors.E_INTERNAL;
    }
}
