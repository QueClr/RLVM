package rlvm.admit;

import rlvm.api.LevelOps;
import rlvm.store.DeclKind;
import rlvm.store.DeclTable;
import rlvm.store.IntLists;
import rlvm.store.Kind;
import rlvm.store.NameTable;
import rlvm.store.Specials;
import rlvm.store.Store;
import rlvm.store.Terms;

/**
 * Shape guards for the constants the kernel extensions rely on (03 section 8, level L1). Called by
 * the {@link EnvBuilder} when a declaration with a special name passes the environment pass; the
 * result is stored in {@code Specials} as VALID or INVALID.
 *
 * <pre>
 * Nat            inductive, no universe parameters, type Sort 1, no parameters or indices, not nested,
 *                ctors exactly [Nat.zero : Nat, Nat.succ : Nat -> Nat]
 * Nat.zero/succ  constructor 0 / 1 of the constant named Nat, with that type
 * Bool           inductive, no universe parameters, type Sort 1, ctors exactly [Bool.false : Bool, Bool.true : Bool]
 * Bool.false/true  constructor 0 / 1 of the constant named Bool, type Bool
 * Nat.add .. Nat.shiftRight   definition, no universe parameters, type Nat -> Nat -> Nat
 * Nat.beq, Nat.ble            the same with type Nat -> Nat -> Bool
 * String, Char   no universe parameters, type Sort 1 (any kind)
 * String.ofList  no universe parameters, type List.{0} Char -> String   (any kind)
 * Char.ofNat     no universe parameters, type Nat -> Char               (any kind)
 * List           inductive List.{u} : Sort (u+1) -> Sort (u+1), one parameter, no index, ctors exactly [List.nil, List.cons]
 * List.nil       constructor 0 of List, type Pi (a : Sort (u+1)), List.{u} a
 * List.cons      constructor 1 of List, type Pi (a : Sort (u+1)), a -> List.{u} a -> List.{u} a
 * Eq             inductive Eq.{u} : Pi (a : Sort u), a -> a -> Prop with one constructor (informational; the
 *                quotient package checks it itself)
 * Eq.refl        constructor 0 of Eq, type Pi (a : Sort u) (x : a), Eq.{u} a x x
 * Quot, Quot.mk, Quot.lift, Quot.ind   the constant has the matching quot kind (its type was compared with the
 *                canonical one by the quotient admission)
 * eagerReduce    definition eagerReduce.{u} : Pi (A : Sort u), A -> A
 * </pre>
 * A reserved name declared with another kind or shape is INVALID. The guards compare types by
 * pattern matching on the stored nodes; they involve no reduction and no engine. Universe levels are
 * compared with {@code LevelOps.isEquiv} (a type written {@code Sort (max 1 1)} is still the standard
 * one). "The constant named Nat" is resolved through the flat namespace
 * ({@code NameTable.constOf(Specials.nameOf(NAT))}), so a guard does not depend on the order in which
 * the specials of one unit are bound.
 *
 * <p>The guards do not validate the BODIES of the accelerated functions (the official kernel does not
 * either; level L2 is future work). Every guard reads statements, not verdicts (optimistic
 * environment, ARCHITECTURE.md 4.1 I2): the run is accepted only if every unit is.
 *
 * <p>Holds no level, list or overlay id between calls. One instance per thread.
 */
public final class PreludeGuards {

    private final Terms terms;
    private final LevelOps levels;
    private final IntLists lists;
    private final DeclTable decls;
    private final NameTable names;
    private final Specials specials;

    /**
     * Without level operations: every guard that has to compare a universe level answers INVALID
     * (fail closed). Prefer {@link #PreludeGuards(Store, Terms, LevelOps)}.
     */
    public PreludeGuards(Store store, Terms terms) {
        this(store, terms, null);
    }

    /**
     * @param terms a reader that can see every shared term
     * @param levels level operations of the calling thread (used for {@code zero}, {@code succ},
     *     {@code param} and {@code isEquiv}); null disables the guards that need them
     */
    public PreludeGuards(Store store, Terms terms, LevelOps levels) {
        this.terms = terms;
        this.levels = levels;
        // A session's list view reads every shared list; the base list store belongs to the loader thread.
        this.lists = levels != null && levels.lists() != null ? levels.lists() : store.lists();
        this.decls = store.decls();
        this.names = store.names();
        this.specials = store.specials();
    }

    /**
     * Evaluates the guard of special {@code which} (a {@code Specials} index) for constant {@code cid}.
     * For inductive types and constructors the derived facts of the unit must already be committed.
     *
     * @return {@code Specials.VALID} or {@code Specials.INVALID}
     */
    public int evaluate(int which, int cid) {
        return holds(which, cid) ? Specials.VALID : Specials.INVALID;
    }

    private boolean holds(int which, int cid) {
        if (cid <= 0) {
            return false;
        }
        int kind = decls.kind(cid);
        int type = decls.type(cid);
        int nlp = decls.numLevelParams(cid);
        switch (which) {
            case Specials.NAT:
                return kind == DeclKind.INDUCT && nlp == 0 && isSortOne(type) && plainInductive(cid, 0, 2)
                        && ctorIs(cid, 0, Specials.NAT_ZERO) && ctorIs(cid, 1, Specials.NAT_SUCC)
                        && isConst0(decls.type(ctorAt(cid, 0)), cid) && isNatArrow(decls.type(ctorAt(cid, 1)), cid, cid);
            case Specials.NAT_ZERO:
                return isCtorOf(cid, Specials.NAT, 0) && nlp == 0 && isConst0(type, special(Specials.NAT));
            case Specials.NAT_SUCC:
                return isCtorOf(cid, Specials.NAT, 1) && nlp == 0
                        && isNatArrow(type, special(Specials.NAT), special(Specials.NAT));
            case Specials.NAT_ADD:
            case Specials.NAT_SUB:
            case Specials.NAT_MUL:
            case Specials.NAT_POW:
            case Specials.NAT_GCD:
            case Specials.NAT_MOD:
            case Specials.NAT_DIV:
            case Specials.NAT_LAND:
            case Specials.NAT_LOR:
            case Specials.NAT_XOR:
            case Specials.NAT_SHIFT_LEFT:
            case Specials.NAT_SHIFT_RIGHT:
                return kind == DeclKind.DEFN && nlp == 0 && isBinary(type, special(Specials.NAT), special(Specials.NAT));
            case Specials.NAT_BEQ:
            case Specials.NAT_BLE:
                return kind == DeclKind.DEFN && nlp == 0 && isBinary(type, special(Specials.NAT), special(Specials.BOOL));
            case Specials.BOOL:
                return kind == DeclKind.INDUCT && nlp == 0 && isSortOne(type) && plainInductive(cid, 0, 2)
                        && ctorIs(cid, 0, Specials.BOOL_FALSE) && ctorIs(cid, 1, Specials.BOOL_TRUE)
                        && isConst0(decls.type(ctorAt(cid, 0)), cid) && isConst0(decls.type(ctorAt(cid, 1)), cid);
            case Specials.BOOL_FALSE:
                return isCtorOf(cid, Specials.BOOL, 0) && nlp == 0 && isConst0(type, special(Specials.BOOL));
            case Specials.BOOL_TRUE:
                return isCtorOf(cid, Specials.BOOL, 1) && nlp == 0 && isConst0(type, special(Specials.BOOL));
            case Specials.STRING:
            case Specials.CHAR:
                return nlp == 0 && isSortOne(type);
            case Specials.STRING_OF_LIST:
                return nlp == 0 && isStringOfListType(type);
            case Specials.CHAR_OF_NAT:
                return nlp == 0 && isNatArrow(type, special(Specials.NAT), special(Specials.CHAR));
            case Specials.LIST:
                return kind == DeclKind.INDUCT && nlp == 1 && isListType(type, param0(cid)) && plainInductive(cid, 1, 2)
                        && ctorIs(cid, 0, Specials.LIST_NIL) && ctorIs(cid, 1, Specials.LIST_CONS);
            case Specials.LIST_NIL:
                return isCtorOf(cid, Specials.LIST, 0) && nlp == 1 && isNilType(type, param0(cid));
            case Specials.LIST_CONS:
                return isCtorOf(cid, Specials.LIST, 1) && nlp == 1 && isConsType(type, param0(cid));
            case Specials.EQ:
                return kind == DeclKind.INDUCT && nlp == 1 && decls.inductCtorCount(cid) == 1
                        && isEqType(type, param0(cid));
            case Specials.EQ_REFL:
                return isCtorOf(cid, Specials.EQ, 0) && nlp == 1 && isReflType(type, param0(cid));
            case Specials.QUOT:
                return kind == DeclKind.QUOT_TYPE;
            case Specials.QUOT_MK:
                return kind == DeclKind.QUOT_MK;
            case Specials.QUOT_LIFT:
                return kind == DeclKind.QUOT_LIFT;
            case Specials.QUOT_IND:
                return kind == DeclKind.QUOT_IND;
            case Specials.EAGER_REDUCE:
                return kind == DeclKind.DEFN && nlp == 1 && isEagerReduceType(type, param0(cid));
            default:
                return false;
        }
    }

    // ---- constants -------------------------------------------------------------------------------------

    /** Constant currently declared under the name of special {@code which}, or 0. */
    private int special(int which) {
        int name = specials.nameOf(which);
        if (name == 0) {
            return 0;
        }
        int cid = names.constOf(name);
        return cid > 0 ? cid : 0;
    }

    /** First universe-parameter name of {@code cid} (which has at least one). */
    private int param0(int cid) {
        return lists.get(decls.levelParams(cid), 0);
    }

    /** {@code cid} is an inductive type with the given derived parameter and constructor counts, no index, not nested. */
    private boolean plainInductive(int cid, int numParams, int numCtors) {
        return decls.inductNumParams(cid) == numParams && decls.inductNumIndices(cid) == 0
                && decls.inductNumNested(cid) == 0 && decls.inductCtorCount(cid) == numCtors;
    }

    private int ctorAt(int inductCid, int index) {
        return StoreAccess.inductCtor(decls, inductCid, index);
    }

    /** Constructor {@code index} of {@code inductCid} carries the name of special {@code which}. */
    private boolean ctorIs(int inductCid, int index, int which) {
        int ctor = ctorAt(inductCid, index);
        int name = specials.nameOf(which);
        return ctor > 0 && name != 0 && decls.kind(ctor) == DeclKind.CTOR && decls.name(ctor) == name;
    }

    /** {@code cid} is constructor number {@code index} of the constant named like special {@code inductWhich}. */
    private boolean isCtorOf(int cid, int inductWhich, int index) {
        int induct = special(inductWhich);
        return induct != 0 && decls.kind(cid) == DeclKind.CTOR && decls.kind(induct) == DeclKind.INDUCT
                && decls.ctorInduct(cid) == induct && decls.ctorIndex(cid) == index;
    }

    // ---- term patterns ---------------------------------------------------------------------------------

    private boolean isKind(int term, int kind) {
        return term > 0 && terms.kind(term) == kind;
    }

    private boolean isBVar(int term, int index) {
        return isKind(term, Kind.BVAR) && terms.a(term) == index;
    }

    /**
     * The constant a CONST node names: its id, or, for a node the loader wrote before any declaration
     * had claimed the name (a file that is not in declaration order), the constant that has the name
     * now - which is how every engine reads such a node.
     */
    private int constantOf(int term) {
        int a = terms.a(term);
        if (a > 0) {
            return a;
        }
        return a == 0 ? 0 : Math.max(names.constOf(~a), 0);
    }

    /** {@code term} is the constant {@code cid} applied to no universe levels. */
    private boolean isConst0(int term, int cid) {
        return cid > 0 && isKind(term, Kind.CONST) && constantOf(term) == cid && terms.b(term) == IntLists.EMPTY;
    }

    /** {@code term} is the constant {@code cid} applied to exactly one level equivalent to {@code level}. */
    private boolean isConst1(int term, int cid, int level) {
        if (cid <= 0 || !isKind(term, Kind.CONST) || constantOf(term) != cid) {
            return false;
        }
        int ls = terms.b(term);
        return lists.length(ls) == 1 && sameLevel(lists.get(ls, 0), level);
    }

    private boolean sameLevel(int a, int b) {
        return a == b || (a != 0 && b != 0 && levels.isEquiv(a, b));
    }

    private boolean isSort(int term, int level) {
        return isKind(term, Kind.SORT) && sameLevel(terms.a(term), level);
    }

    private boolean isSortOne(int term) {
        return levels != null && isSort(term, levels.succ(levels.zero()));
    }

    /** {@code dom -> cod} for two constants without universe levels. */
    private boolean isNatArrow(int term, int dom, int cod) {
        return isKind(term, Kind.PI) && isConst0(terms.a(term), dom) && isConst0(terms.b(term), cod);
    }

    /** {@code arg -> arg -> result}. */
    private boolean isBinary(int term, int arg, int result) {
        return isKind(term, Kind.PI) && isConst0(terms.a(term), arg) && isNatArrow(terms.b(term), arg, result);
    }

    /** {@code List.{0} Char -> String}. */
    private boolean isStringOfListType(int term) {
        if (levels == null || !isKind(term, Kind.PI)) {
            return false;
        }
        int dom = terms.a(term);
        return isKind(dom, Kind.APP) && isConst1(terms.a(dom), special(Specials.LIST), levels.zero())
                && isConst0(terms.b(dom), special(Specials.CHAR))
                && isConst0(terms.b(term), special(Specials.STRING));
    }

    /** {@code Sort (u+1) -> Sort (u+1)}. */
    private boolean isListType(int term, int u) {
        if (levels == null || !isKind(term, Kind.PI)) {
            return false;
        }
        int typeU = levels.succ(levels.param(u));
        return isSort(terms.a(term), typeU) && isSort(terms.b(term), typeU);
    }

    /** {@code List.{u} #index}. */
    private boolean isListOf(int term, int u, int index) {
        return isKind(term, Kind.APP) && isConst1(terms.a(term), special(Specials.LIST), levels.param(u))
                && isBVar(terms.b(term), index);
    }

    /** {@code Pi (a : Sort (u+1)), List.{u} a}. */
    private boolean isNilType(int term, int u) {
        if (levels == null || !isKind(term, Kind.PI)) {
            return false;
        }
        return isSort(terms.a(term), levels.succ(levels.param(u))) && isListOf(terms.b(term), u, 0);
    }

    /** {@code Pi (a : Sort (u+1)) (head : a) (tail : List.{u} a), List.{u} a}. */
    private boolean isConsType(int term, int u) {
        if (levels == null || !isKind(term, Kind.PI) || !isSort(terms.a(term), levels.succ(levels.param(u)))) {
            return false;
        }
        int t1 = terms.b(term);
        if (!isKind(t1, Kind.PI) || !isBVar(terms.a(t1), 0)) {
            return false;
        }
        int t2 = terms.b(t1);
        return isKind(t2, Kind.PI) && isListOf(terms.a(t2), u, 1) && isListOf(terms.b(t2), u, 2);
    }

    /** {@code Pi (a : Sort u), a -> a -> Sort 0}. */
    private boolean isEqType(int term, int u) {
        if (levels == null || !isKind(term, Kind.PI) || !isSort(terms.a(term), levels.param(u))) {
            return false;
        }
        int t1 = terms.b(term);
        if (!isKind(t1, Kind.PI) || !isBVar(terms.a(t1), 0)) {
            return false;
        }
        int t2 = terms.b(t1);
        return isKind(t2, Kind.PI) && isBVar(terms.a(t2), 1) && isSort(terms.b(t2), levels.zero());
    }

    /** {@code Pi (a : Sort u) (x : a), Eq.{u} a x x}. */
    private boolean isReflType(int term, int u) {
        if (levels == null || !isKind(term, Kind.PI) || !isSort(terms.a(term), levels.param(u))) {
            return false;
        }
        int t1 = terms.b(term);
        if (!isKind(t1, Kind.PI) || !isBVar(terms.a(t1), 0)) {
            return false;
        }
        int app3 = terms.b(t1);
        if (!isKind(app3, Kind.APP) || !isBVar(terms.b(app3), 0)) {
            return false;
        }
        int app2 = terms.a(app3);
        if (!isKind(app2, Kind.APP) || !isBVar(terms.b(app2), 0)) {
            return false;
        }
        int app1 = terms.a(app2);
        return isKind(app1, Kind.APP) && isBVar(terms.b(app1), 1)
                && isConst1(terms.a(app1), special(Specials.EQ), levels.param(u));
    }

    /** {@code Pi (A : Sort u), A -> A}. */
    private boolean isEagerReduceType(int term, int u) {
        if (levels == null || !isKind(term, Kind.PI) || !isSort(terms.a(term), levels.param(u))) {
            return false;
        }
        int t1 = terms.b(term);
        return isKind(t1, Kind.PI) && isBVar(terms.a(t1), 0) && isBVar(terms.b(t1), 1);
    }
}
