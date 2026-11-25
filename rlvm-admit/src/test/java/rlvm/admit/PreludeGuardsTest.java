package rlvm.admit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import rlvm.store.DeclKind;
import rlvm.store.Specials;

/**
 * The guard matrix of *agentic-note*: design-architecture-document, 4.8 (level L1 of research doc 03 section 8): the standard
 * prelude shapes are VALID, every deviation in kind, universe parameters, type or constructor list
 * is INVALID. No engine is involved.
 */
class PreludeGuardsTest {

    /** Switches for one deviation from the standard prelude each. */
    static final class Variant {
        boolean natIsAxiom;
        boolean natHasUniverseParam;
        boolean natInTypeOne;
        boolean natCtorsSwapped;
        boolean natThirdCtor;
        boolean natSuccBinary;
        boolean natSortWrittenAsMax;
        boolean boolCtorsSwapped;
        boolean boolTrueOfNat;
        int addKind = DeclKind.DEFN;
        boolean addHasUniverseParam;
        boolean addUnary;
        boolean addReturnsBool;
        boolean beqReturnsNat;
        boolean charOfNatReturnsNat;
        boolean stringOfListAtLevelOne;
        boolean stringOfListOfNat;
        boolean stringIsProp;
        boolean listTwoParams;
        boolean listInSortU;
        boolean nilWrongResult;
        boolean consTailNotList;
        boolean eagerReduceIsAxiom;
        boolean eagerReduceWrongType;
        boolean eqTwoCtors;
    }

    static Fixture prelude(Consumer<Variant> change) {
        Variant v = new Variant();
        change.accept(v);
        Fixture f = new Fixture();
        int u = f.name("u");
        int w = f.name("w");
        int one = f.succ(f.zero());
        int type0 = f.sort(v.natSortWrittenAsMax ? f.max(one, one) : one);
        int type1 = f.sort(f.succ(one));

        // ---- Nat
        int natName = f.name("Nat");
        int zeroName = f.name("Nat.zero");
        int succName = f.name("Nat.succ");
        int natLps = v.natHasUniverseParam ? f.names(u) : f.names();
        if (v.natIsAxiom) {
            f.axiom(natName, natLps, type0);
        } else {
            Fixture.Block b = f.block();
            int nat = b.reserve(DeclKind.INDUCT, natName, natLps);
            int first = b.reserve(DeclKind.CTOR, v.natCtorsSwapped ? succName : zeroName, natLps);
            int second = b.reserve(DeclKind.CTOR, v.natCtorsSwapped ? zeroName : succName, natLps);
            int third = v.natThirdCtor ? b.reserve(DeclKind.CTOR, f.name("Nat.third"), natLps) : 0;
            int natRef = v.natHasUniverseParam ? f.cnst(natName, f.param(u)) : f.cnst(natName);
            int succType = v.natSuccBinary ? f.pi(natRef, f.pi(natRef, natRef)) : f.pi(natRef, natRef);
            b.complete(nat, v.natInTypeOne ? type1 : type0);
            b.complete(first, v.natCtorsSwapped ? succType : natRef);
            b.complete(second, v.natCtorsSwapped ? natRef : succType);
            if (third != 0) {
                b.complete(third, natRef);
            }
            b.end();
            if (third != 0) {
                f.commitInductive(nat, 0, 0, true, first, second, third);
            } else {
                f.commitInductive(nat, 0, 0, true, first, second);
            }
        }
        int natRef = v.natHasUniverseParam ? f.cnst(natName, f.zero()) : f.cnst(natName);

        // ---- Bool
        int boolName = f.name("Bool");
        int falseName = f.name("Bool.false");
        int trueName = f.name("Bool.true");
        {
            Fixture.Block b = f.block();
            int bool = b.reserve(DeclKind.INDUCT, boolName, f.names());
            int first = b.reserve(DeclKind.CTOR, v.boolCtorsSwapped ? trueName : falseName, f.names());
            int second = b.reserve(DeclKind.CTOR, v.boolCtorsSwapped ? falseName : trueName, f.names());
            int boolRef = f.cnst(boolName);
            b.complete(bool, f.sort(one));
            b.complete(first, boolRef);
            b.complete(second, v.boolTrueOfNat && !v.boolCtorsSwapped ? natRef : boolRef);
            b.end();
            f.commitInductive(bool, 0, 0, false, first, second);
        }
        int boolRef = f.cnst(boolName);

        // ---- accelerated functions
        int natNatNat = f.pi(natRef, f.pi(natRef, natRef));
        int natNatBool = f.pi(natRef, f.pi(natRef, boolRef));
        int addType = v.addUnary ? f.pi(natRef, natRef) : v.addReturnsBool ? natNatBool : natNatNat;
        int addLps = v.addHasUniverseParam ? f.names(u) : f.names();
        f.declare(v.addKind, f.name("Nat.add"), addLps, addType, v.addKind == DeclKind.AXIOM ? 0 : natRef);
        for (String fn : new String[] {"sub", "mul", "pow", "gcd", "mod", "div", "land", "lor", "xor", "shiftLeft",
                "shiftRight"}) {
            f.def(f.name("Nat." + fn), f.names(), natNatNat, natRef);
        }
        f.def(f.name("Nat.beq"), f.names(), v.beqReturnsNat ? natNatNat : natNatBool, natRef);
        f.def(f.name("Nat.ble"), f.names(), natNatBool, natRef);

        // ---- Char, List, String
        int charName = f.name("Char");
        f.axiom(charName, f.names(), f.sort(one));
        int charRef = f.cnst(charName);
        f.def(f.name("Char.ofNat"), f.names(), f.pi(natRef, v.charOfNatReturnsNat ? natRef : charRef), natRef);

        int listName = f.name("List");
        int nilName = f.name("List.nil");
        int consName = f.name("List.cons");
        int listLps = v.listTwoParams ? f.names(u, w) : f.names(u);
        int typeU = v.listInSortU ? f.sort(f.param(u)) : f.sort(f.succ(f.param(u)));
        {
            Fixture.Block b = f.block();
            int list = b.reserve(DeclKind.INDUCT, listName, listLps);
            int nil = b.reserve(DeclKind.CTOR, nilName, listLps);
            int cons = b.reserve(DeclKind.CTOR, consName, listLps);
            int listU = v.listTwoParams ? f.cnst(listName, f.param(u), f.param(w)) : f.cnst(listName, f.param(u));
            b.complete(list, f.pi(typeU, typeU));
            b.complete(nil, f.pi(typeU, v.nilWrongResult ? f.bvar(0) : f.app(listU, f.bvar(0))));
            int tail = v.consTailNotList ? f.bvar(1) : f.app(listU, f.bvar(1));
            b.complete(cons, f.pi(typeU, f.pi(f.bvar(0), f.pi(tail, f.app(listU, f.bvar(2))))));
            b.end();
            f.commitInductive(list, 1, 0, true, nil, cons);
        }
        int stringName = f.name("String");
        f.axiom(stringName, f.names(), v.stringIsProp ? f.sort(f.zero()) : f.sort(one));
        int listChar = f.app(
                v.listTwoParams ? f.cnst(listName, f.zero(), f.zero())
                        : f.cnst(listName, v.stringOfListAtLevelOne ? one : f.zero()),
                v.stringOfListOfNat ? natRef : charRef);
        f.def(f.name("String.ofList"), f.names(), f.pi(listChar, f.cnst(stringName)), natRef);

        // ---- Eq, eagerReduce
        int eqName = f.name("Eq");
        int reflName = f.name("Eq.refl");
        {
            Fixture.Block b = f.block();
            int eq = b.reserve(DeclKind.INDUCT, eqName, f.names(u));
            int refl = b.reserve(DeclKind.CTOR, reflName, f.names(u));
            int extra = v.eqTwoCtors ? b.reserve(DeclKind.CTOR, f.name("Eq.other"), f.names(u)) : 0;
            int sortU = f.sort(f.param(u));
            int eqU = f.cnst(eqName, f.param(u));
            int reflType = f.pi(sortU, f.pi(f.bvar(0), f.app(f.app(f.app(eqU, f.bvar(1)), f.bvar(0)), f.bvar(0))));
            b.complete(eq, f.pi(sortU, f.pi(f.bvar(0), f.pi(f.bvar(1), f.sort(f.zero())))));
            b.complete(refl, reflType);
            if (extra != 0) {
                b.complete(extra, reflType);
            }
            b.end();
            if (extra != 0) {
                f.commitInductive(eq, 2, 1, false, refl, extra);
            } else {
                f.commitInductive(eq, 2, 1, false, refl);
            }
        }
        int sortU = f.sort(f.param(u));
        int idType = v.eagerReduceWrongType ? f.pi(sortU, f.pi(f.bvar(0), f.bvar(0))) : f.pi(sortU, f.pi(f.bvar(0), f.bvar(1)));
        if (v.eagerReduceIsAxiom) {
            f.axiom(f.name("eagerReduce"), f.names(u), idType);
        } else {
            f.def(f.name("eagerReduce"), f.names(u), idType, f.lam(sortU, f.lam(f.bvar(0), f.bvar(0))));
        }
        f.finish();
        return f;
    }

    private static int state(Fixture f, int which) {
        PreludeGuards guards = new PreludeGuards(f.store, f.ctx.terms, f.ctx.levels);
        int name = f.store.specials().nameOf(which);
        int cid = name == 0 ? 0 : f.store.names().constOf(name);
        return guards.evaluate(which, cid);
    }

    private static void expect(Fixture f, int expected, int... specials) {
        for (int which : specials) {
            assertEquals(expected, state(f, which), "special " + which);
        }
    }

    private static final int[] NAT_BINARY = {Specials.NAT_ADD, Specials.NAT_SUB, Specials.NAT_MUL, Specials.NAT_POW,
        Specials.NAT_GCD, Specials.NAT_MOD, Specials.NAT_DIV, Specials.NAT_LAND, Specials.NAT_LOR, Specials.NAT_XOR,
        Specials.NAT_SHIFT_LEFT, Specials.NAT_SHIFT_RIGHT};

    @Test
    void standardPreludeIsValid() {
        Fixture f = prelude(v -> {});
        expect(f, Specials.VALID, Specials.NAT, Specials.NAT_ZERO, Specials.NAT_SUCC, Specials.BOOL,
                Specials.BOOL_FALSE, Specials.BOOL_TRUE, Specials.NAT_BEQ, Specials.NAT_BLE, Specials.STRING,
                Specials.STRING_OF_LIST, Specials.CHAR, Specials.CHAR_OF_NAT, Specials.LIST, Specials.LIST_NIL,
                Specials.LIST_CONS, Specials.EQ, Specials.EQ_REFL, Specials.EAGER_REDUCE);
        expect(f, Specials.VALID, NAT_BINARY);
        // the four quotient names are not declared here
        expect(f, Specials.INVALID, Specials.QUOT, Specials.QUOT_MK, Specials.QUOT_LIFT, Specials.QUOT_IND);
    }

    @Test
    void equivalentUniverseLevelsAreAccepted() {
        Fixture f = prelude(v -> v.natSortWrittenAsMax = true);
        expect(f, Specials.VALID, Specials.NAT, Specials.NAT_ZERO, Specials.NAT_SUCC);
    }

    @Test
    void natMustBeTheStandardInductiveType() {
        Fixture axiom = prelude(v -> v.natIsAxiom = true);
        expect(axiom, Specials.INVALID, Specials.NAT);
        // the functions still have the right signature over the constant named Nat
        expect(axiom, Specials.VALID, Specials.NAT_ADD, Specials.NAT_BLE, Specials.CHAR_OF_NAT);

        expect(prelude(v -> v.natHasUniverseParam = true), Specials.INVALID, Specials.NAT, Specials.NAT_ZERO,
                Specials.NAT_SUCC, Specials.NAT_ADD, Specials.NAT_BEQ, Specials.CHAR_OF_NAT);
        expect(prelude(v -> v.natInTypeOne = true), Specials.INVALID, Specials.NAT);
        expect(prelude(v -> v.natCtorsSwapped = true), Specials.INVALID, Specials.NAT, Specials.NAT_ZERO,
                Specials.NAT_SUCC);
        expect(prelude(v -> v.natThirdCtor = true), Specials.INVALID, Specials.NAT);
        Fixture binarySucc = prelude(v -> v.natSuccBinary = true);
        expect(binarySucc, Specials.INVALID, Specials.NAT, Specials.NAT_SUCC);
        expect(binarySucc, Specials.VALID, Specials.NAT_ZERO);
    }

    @Test
    void boolMustBeTheStandardInductiveType() {
        expect(prelude(v -> v.boolCtorsSwapped = true), Specials.INVALID, Specials.BOOL, Specials.BOOL_FALSE,
                Specials.BOOL_TRUE);
        Fixture trueOfNat = prelude(v -> v.boolTrueOfNat = true);
        expect(trueOfNat, Specials.INVALID, Specials.BOOL, Specials.BOOL_TRUE);
        expect(trueOfNat, Specials.VALID, Specials.BOOL_FALSE, Specials.NAT_BEQ);
    }

    @Test
    void acceleratedFunctionsNeedKindAndSignature() {
        expect(prelude(v -> v.addKind = DeclKind.AXIOM), Specials.INVALID, Specials.NAT_ADD);
        expect(prelude(v -> v.addKind = DeclKind.OPAQUE), Specials.INVALID, Specials.NAT_ADD);
        expect(prelude(v -> v.addKind = DeclKind.THM), Specials.INVALID, Specials.NAT_ADD);
        expect(prelude(v -> v.addHasUniverseParam = true), Specials.INVALID, Specials.NAT_ADD);
        expect(prelude(v -> v.addUnary = true), Specials.INVALID, Specials.NAT_ADD);
        Fixture addBool = prelude(v -> v.addReturnsBool = true);
        expect(addBool, Specials.INVALID, Specials.NAT_ADD);
        expect(addBool, Specials.VALID, Specials.NAT_SUB, Specials.NAT_BEQ);
        Fixture beqNat = prelude(v -> v.beqReturnsNat = true);
        expect(beqNat, Specials.INVALID, Specials.NAT_BEQ);
        expect(beqNat, Specials.VALID, Specials.NAT_BLE, Specials.NAT_ADD);
    }

    @Test
    void stringLiteralPrerequisites() {
        expect(prelude(v -> v.charOfNatReturnsNat = true), Specials.INVALID, Specials.CHAR_OF_NAT);
        expect(prelude(v -> v.stringOfListAtLevelOne = true), Specials.INVALID, Specials.STRING_OF_LIST);
        expect(prelude(v -> v.stringOfListOfNat = true), Specials.INVALID, Specials.STRING_OF_LIST);
        Fixture prop = prelude(v -> v.stringIsProp = true);
        expect(prop, Specials.INVALID, Specials.STRING);
        expect(prop, Specials.VALID, Specials.STRING_OF_LIST, Specials.CHAR);
        expect(prelude(v -> v.listTwoParams = true), Specials.INVALID, Specials.LIST, Specials.LIST_NIL,
                Specials.LIST_CONS, Specials.STRING_OF_LIST);
        expect(prelude(v -> v.listInSortU = true), Specials.INVALID, Specials.LIST, Specials.LIST_NIL,
                Specials.LIST_CONS);
        Fixture nil = prelude(v -> v.nilWrongResult = true);
        expect(nil, Specials.INVALID, Specials.LIST_NIL);
        expect(nil, Specials.VALID, Specials.LIST, Specials.LIST_CONS);
        expect(prelude(v -> v.consTailNotList = true), Specials.INVALID, Specials.LIST_CONS);
    }

    @Test
    void eqAndEagerReduce() {
        expect(prelude(v -> v.eagerReduceIsAxiom = true), Specials.INVALID, Specials.EAGER_REDUCE);
        expect(prelude(v -> v.eagerReduceWrongType = true), Specials.INVALID, Specials.EAGER_REDUCE);
        Fixture eq = prelude(v -> v.eqTwoCtors = true);
        expect(eq, Specials.INVALID, Specials.EQ);
        expect(eq, Specials.VALID, Specials.EQ_REFL);
    }

    @Test
    void withoutLevelOperationsSortGuardsFailClosed() {
        Fixture f = prelude(v -> {});
        PreludeGuards guards = new PreludeGuards(f.store, f.ctx.terms);
        int nat = f.store.names().constOf(f.store.specials().nameOf(Specials.NAT));
        int add = f.store.names().constOf(f.store.specials().nameOf(Specials.NAT_ADD));
        assertEquals(Specials.INVALID, guards.evaluate(Specials.NAT, nat));
        assertEquals(Specials.VALID, guards.evaluate(Specials.NAT_ADD, add));
        assertEquals(Specials.INVALID, guards.evaluate(Specials.NAT, 0));
    }
}
