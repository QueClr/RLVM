package rlvm.inductive;

import rlvm.api.KernelException;
import rlvm.api.KernelOps;
import rlvm.api.LevelOps;
import rlvm.api.Reason;
import rlvm.api.ResourceException;
import rlvm.store.DeclKind;
import rlvm.store.DeclTable;
import rlvm.store.IntLists;
import rlvm.store.NameTable;
import rlvm.store.Node;
import rlvm.store.Overlay;
import rlvm.store.Specials;
import rlvm.store.Store;
import rlvm.store.TermOps;
import rlvm.store.Terms;

/**
 * The quotient package ({@code quot.cpp}; 03 section 5, checklist items 18 - 24): on the first
 * {@code quot} record check the shape of {@code Eq} and of its constructor, require the four names to
 * be free, build the four canonical types, and require every exported quot record to be one of the
 * four names with identical kind, universe parameters ({@code u}; {@code u, v} for lift) and type.
 * Purely structural: no type checking is involved.
 *
 * <p>How the official checker treats quot records ({@code LeanChecker.lean}, 03 section 2): the
 * records named {@code Quot.mk}, {@code Quot.lift}, {@code Quot.ind} are set aside; any other quot
 * record (normally {@code Quot}) makes the kernel run {@code add_quot}, which ignores the record and
 * adds the four canonical constants; at the end each of the four names that the export contains must
 * be in the environment and equal to the exported record. Mapping onto one call per record:
 * <ul>
 *   <li>a quot record under any other name is never added officially (the file is accepted if nothing
 *       uses it); here its row would carry a quot kind, so it is DECLINED ({@code UNSUPPORTED_INPUT});
 *   <li>the canonical constants exist officially even without a record; here a record must exist for
 *       {@code Quot} (always) and for {@code Quot.mk} (when {@code Quot.ind} is admitted), else the
 *       file is declined: generated constants are deferred;
 *   <li>the official replay admits {@code Eq} before the quot declaration whatever the file order;
 *       here {@code Eq} must have a smaller rank, else the record is declined
 *       ({@code FORWARD_REFERENCE}).
 * </ul>
 * Everything else is a rejection ({@code QUOT_MISMATCH}, or {@code UNKNOWN_CONSTANT} when the file has
 * no usable {@code Eq}).
 */
public final class QuotAdmission {

    private final Store store;
    private final KernelOps ops;
    /** The cause of the last decline raised by this object, or null. */
    private String declineDetail;

    private ResourceException decline(Reason reason, String why) {
        declineDetail = why;
        return new ResourceException(reason);
    }

    /**
     * Why the last call of {@link #admit} or {@link #canonicalType} ended with a
     * {@code ResourceException} of this class (null if it did not, or if the exception came from the
     * kernel operations).
     */
    public String lastDeclineDetail() {
        return declineDetail;
    }

    private boolean initialised;
    private int eqCid;
    private int quotCid;
    private int uName;
    private int vName;

    public QuotAdmission(Store store, KernelOps ops) {
        if (store == null || ops == null) {
            throw new NullPointerException();
        }
        this.store = store;
        this.ops = ops;
    }

    private static KernelException mismatch() {
        return new KernelException(Reason.QUOT_MISMATCH);
    }

    private static boolean isQuotKind(int kind) {
        return kind >= DeclKind.QUOT_TYPE && kind <= DeclKind.QUOT_IND;
    }

    private boolean isSimpleName(int name, String s) {
        NameTable names = store.names();
        return name != 0 && names.prefix(name) == NameTable.ANONYMOUS && !names.isNumeric(name)
                && names.lastComponentIs(name, s);
    }

    /** The {@code DeclKind.QUOT_*} kind the name is reserved for, or 0. */
    private int reservedKind(int name) {
        NameTable names = store.names();
        if (isSimpleName(name, "Quot")) {
            return DeclKind.QUOT_TYPE;
        }
        if (name == 0 || names.isNumeric(name) || !isSimpleName(names.prefix(name), "Quot")) {
            return 0;
        }
        if (names.lastComponentIs(name, "mk")) {
            return DeclKind.QUOT_MK;
        }
        if (names.lastComponentIs(name, "lift")) {
            return DeclKind.QUOT_LIFT;
        }
        if (names.lastComponentIs(name, "ind")) {
            return DeclKind.QUOT_IND;
        }
        return 0;
    }

    /**
     * Admits the quot record with constant id {@code cid}. The first call performs the {@code Eq}
     * checks. Throws {@code KernelException(QUOT_MISMATCH)} on any difference.
     */
    public void admit(int cid) {
        admit(cid, store.decls().rank(cid));
    }

    /**
     * As {@link #admit(int)}, in a scope of rank {@code scopeRank}: {@code Eq}, {@code Quot} and
     * {@code Quot.mk} count as declared before the record if their rank is below it. The caller
     * ({@code rlvm-admit}, admitting in dependency order) passes a rank above the record's own after it
     * has admitted those declarations although they come later in the file.
     */
    public void admit(int cid, int scopeRank) {
        DeclTable decls = store.decls();
        IntLists lists = ops.levels().lists();
        int name = decls.name(cid);
        int which = reservedKind(name);
        declineDetail = null;
        if (which == 0) {
            throw decline(Reason.UNSUPPORTED_INPUT, "a quot record under a name other than Quot, Quot.mk, Quot.lift,"
                    + " Quot.ind: the official checker adds the quotient package for it and no constant of that name");
        }
        if (!initialised) {
            init(cid, which, scopeRank);
            initialised = true;
        }
        if (decls.kind(cid) != which) {
            throw mismatch();
        }
        int lp = decls.levelParams(cid);
        int nlp = lists.length(lp);
        if (nlp != (which == DeclKind.QUOT_LIFT ? 2 : 1) || !isSimpleName(lists.get(lp, 0), "u")) {
            throw mismatch();
        }
        uName = lists.get(lp, 0);
        if (which == DeclKind.QUOT_LIFT) {
            if (!isSimpleName(lists.get(lp, 1), "v")) {
                throw mismatch();
            }
            vName = lists.get(lp, 1);
        }
        int exported = decls.type(cid);
        int canonical = canonicalType(which, scopeRank);
        if (!ops.termOps().eqv(exported, canonical)) {
            if ((ops.terms().header(exported) & Node.F_UNRESOLVED) != 0 && !store.loadComplete()) {
                // An unbound reference whose constant may still be declared. (Once the file is loaded the
                // comparison is final: it is by name, and the official post-check compares the record
                // with the kernel's constant whatever the order of the file.)
                throw decline(Reason.FORWARD_REFERENCE, "the type of the quot record mentions a constant that is not"
                        + " declared yet (the file is still loading)");
            }
            throw new KernelException(Reason.QUOT_MISMATCH, exported, canonical);
        }
    }

    /** add_quot: check_eq_type and the four check_name calls. */
    private void init(int cid, int which, int rank) {
        DeclTable decls = store.decls();
        NameTable names = store.names();
        Specials specials = store.specials();
        IntLists lists = ops.levels().lists();
        LevelOps levels = ops.levels();
        Overlay ov = ops.terms().overlay();
        TermOps tops = ops.termOps();

        // Something must make the kernel run add_quot: a quot record named `Quot`.
        int quotName = which == DeclKind.QUOT_TYPE ? decls.name(cid) : names.prefix(decls.name(cid));
        int q = names.constOf(quotName);
        if (q > 0 && !isQuotKind(decls.kind(q))) {
            throw mismatch(); // `Quot` is taken by another declaration
        }
        if (q <= 0) {
            throw decline(Reason.UNSUPPORTED_INPUT, "the file has no usable record for 'Quot': the official kernel"
                    + " generates the quotient package itself (generated constants are deferred)");
        }
        quotCid = q;

        // check_name(Quot.mk / Quot.lift / Quot.ind): the names may only be claimed by quot records.
        for (int w = Specials.QUOT; w <= Specials.QUOT_IND; w++) {
            int n = specials.nameOf(w);
            if (n == 0) {
                continue;
            }
            int c = names.constOf(n);
            if (c > 0 && !isQuotKind(decls.kind(c))) {
                throw mismatch();
            }
        }

        // check_eq_type
        int eqName = specials.nameOf(Specials.EQ);
        int eq = eqName == 0 ? 0 : names.constOf(eqName);
        if (eq < 0 || (eq == 0 && store.loadComplete())) {
            throw new KernelException(Reason.UNKNOWN_CONSTANT); // env.get("Eq")
        }
        if (eq == 0 || decls.rank(eq) >= rank) {
            throw decline(Reason.FORWARD_REFERENCE, "'Eq' is declared after the quot record (or not yet): the official"
                    + " checker adds the quotient package after 'Eq'");
        }
        if (decls.kind(eq) != DeclKind.INDUCT) {
            throw mismatch(); // "environment does not have 'Eq' type"
        }
        if (decls.numLevelParams(eq) != 1) {
            throw mismatch(); // "unexpected number of universe params at 'Eq' type"
        }
        if (decls.inductCtorCount(eq) != 1) {
            throw mismatch(); // "unexpected number of constructors for 'Eq' type"
        }
        int sortU = ov.mkSort(levels.param(lists.get(decls.levelParams(eq), 0)));
        int prop = ov.mkSort(levels.zero());
        // Pi(Sort u, Pi(#0, Pi(#1, Sort 0)))
        int eqType = ov.mkPi(sortU, ov.mkPi(ov.mkBVar(0), ov.mkPi(ov.mkBVar(1), prop)));
        if (!tops.eqv(decls.type(eq), eqType)) {
            throw new KernelException(Reason.QUOT_MISMATCH, decls.type(eq), eqType);
        }
        int refl = decls.inductCtor(eq, 0);
        int reflLp = decls.levelParams(refl);
        if (lists.length(reflLp) < 1) {
            throw mismatch();
        }
        int u1 = levels.param(lists.get(reflLp, 0));
        buf[0] = u1;
        int eqU = ov.mkConst(eq, lists.intern(buf, 0, 1));
        // Pi(Sort u', Pi(#0, Eq.{u'} #1 #0 #0))
        int app = ov.mkApp(ov.mkApp(ov.mkApp(eqU, ov.mkBVar(1)), ov.mkBVar(0)), ov.mkBVar(0));
        int reflType = ov.mkPi(ov.mkSort(u1), ov.mkPi(ov.mkBVar(0), app));
        if (!tops.eqv(decls.type(refl), reflType)) {
            throw new KernelException(Reason.QUOT_MISMATCH, decls.type(refl), reflType);
        }
        eqCid = eq;
    }

    private final int[] buf = new int[2];

    /**
     * Canonical type of the quot constant of the given {@code DeclKind.QUOT_*} kind, in the overlay.
     * Usable after a quot record with that kind's universe parameters was admitted (the name ids of
     * {@code u} and {@code v} are taken from admitted records); otherwise, and when a constant the
     * type mentions has no record, {@code ResourceException(UNSUPPORTED_INPUT)}.
     */
    public int canonicalType(int quotKind) {
        declineDetail = null;
        return canonicalType(quotKind, Integer.MAX_VALUE);
    }

    /** @param rank rank of the record being compared: a constant the type mentions must come earlier */
    private int canonicalType(int quotKind, int rank) {
        if (!initialised || uName == 0 || (quotKind == DeclKind.QUOT_LIFT && vName == 0)) {
            throw decline(Reason.UNSUPPORTED_INPUT, "no quot record with the universe parameters of this quotient"
                    + " constant was admitted, so its canonical type cannot be stated (generated constants are deferred)");
        }
        DeclTable decls = store.decls();
        NameTable names = store.names();
        IntLists lists = ops.levels().lists();
        LevelOps levels = ops.levels();
        Terms terms = ops.terms();
        Overlay ov = terms.overlay();
        int u = levels.param(uName);
        buf[0] = u;
        int us = lists.intern(buf, 0, 1);
        int sortU = ov.mkSort(u);
        int prop = ov.mkSort(levels.zero());
        int b0 = ov.mkBVar(0);
        int b1 = ov.mkBVar(1);
        int b2 = ov.mkBVar(2);
        int b3 = ov.mkBVar(3);
        int b4 = ov.mkBVar(4);
        // r : α → α → Prop, under [α]
        int rel = ov.mkPi(b0, ov.mkPi(b1, prop));
        if (quotKind != DeclKind.QUOT_TYPE && decls.rank(quotCid) >= rank) {
            throw decline(Reason.FORWARD_REFERENCE, "the record of 'Quot' comes after this quot record");
        }
        int quot = ov.mkConst(quotCid, us);
        switch (quotKind) {
            case DeclKind.QUOT_TYPE:
                // Pi(Sort u, Pi(rel, Sort u))
                return ov.mkPi(sortU, ov.mkPi(rel, sortU));
            case DeclKind.QUOT_MK:
                // Pi(Sort u, Pi(rel, Pi(#1, Quot.{u} #2 #1)))
                return ov.mkPi(sortU, ov.mkPi(rel, ov.mkPi(b1, ov.mkApp(ov.mkApp(quot, b2), b1))));
            case DeclKind.QUOT_LIFT: {
                int v = levels.param(vName);
                buf[0] = v;
                int eqV = ov.mkConst(eqCid, lists.intern(buf, 0, 1));
                // S = Pi(#3, Pi(#4, Pi(#4 #1 #0, Eq.{v} #4 (#3 #2) (#3 #1))))
                int eqApp = ov.mkApp(ov.mkApp(ov.mkApp(eqV, b4), ov.mkApp(b3, b2)), ov.mkApp(b3, b1));
                int s = ov.mkPi(b3, ov.mkPi(b4, ov.mkPi(ov.mkApp(ov.mkApp(b4, b1), b0), eqApp)));
                // Pi(Sort u, Pi(rel, Pi(Sort v, Pi(Pi(#2, #1), Pi(S, Pi(Quot.{u} #4 #3, #3))))))
                int tail = ov.mkPi(s, ov.mkPi(ov.mkApp(ov.mkApp(quot, b4), b3), b3));
                return ov.mkPi(sortU, ov.mkPi(rel, ov.mkPi(ov.mkSort(v), ov.mkPi(ov.mkPi(b2, b1), tail))));
            }
            case DeclKind.QUOT_IND: {
                int mkName = store.specials().nameOf(Specials.QUOT_MK);
                int mk = mkName == 0 ? 0 : names.constOf(mkName);
                if (mk <= 0) {
                    throw decline(Reason.UNSUPPORTED_INPUT, "the type of 'Quot.ind' mentions 'Quot.mk', for which the"
                            + " file has no usable record: the official kernel generates it (generated constants are"
                            + " deferred)");
                }
                if (!isQuotKind(decls.kind(mk))) {
                    throw mismatch();
                }
                if (decls.rank(mk) >= rank) {
                    throw decline(Reason.FORWARD_REFERENCE, "the record of 'Quot.mk' comes after the record of 'Quot.ind'");
                }
                int mkU = ov.mkConst(mk, us);
                // β : Quot.{u} #1 #0 → Prop, under [α, r]
                int beta = ov.mkPi(ov.mkApp(ov.mkApp(quot, b1), b0), prop);
                // Pi(#2, #1 (Quot.mk.{u} #3 #2 #0)), under [α, r, β]
                int all = ov.mkPi(b2, ov.mkApp(b1, ov.mkApp(ov.mkApp(ov.mkApp(mkU, b3), b2), b0)));
                // Pi(Quot.{u} #3 #2, #2 #0), under [α, r, β, mk]
                int last = ov.mkPi(ov.mkApp(ov.mkApp(quot, b3), b2), ov.mkApp(b2, b0));
                return ov.mkPi(sortU, ov.mkPi(rel, ov.mkPi(beta, ov.mkPi(all, last))));
            }
            default:
                throw new IllegalArgumentException("not a quot kind: " + quotKind);
        }
    }
}
