package rlvm.ref;

import rlvm.store.Specials;

/** The hand-made environment of the fixture tests: small versions of the prelude's key declarations. */
final class Prelude {
    private Prelude() {}

    static final String NAT_MOTIVE = "(motive : (-> Nat (sort u)))";
    static final String NAT_MINORS =
            "(zero : (motive Nat.zero)) (succ : (pi (n : Nat) (ih : (motive n)) (motive (Nat.succ n))))";

    static final String EQ_MOTIVE = "(motive : (pi (b : α) (h : ((@ Eq u) α a b)) (sort u_1)))";

    static void install(Fixture fx) {
        // inductive Nat : Type | zero | succ (n : Nat)
        fx.induct("Nat", "", "Type", 0, 0, true)
                .ctor("Nat.zero", "Nat", 0)
                .ctor("Nat.succ", "(-> Nat Nat)", 1)
                .rec("Nat.rec", "u", "(pi " + NAT_MOTIVE + " " + NAT_MINORS + " (t : Nat) (motive t))", 1, 2, false)
                .rule("Nat.zero", 0, "(fun " + NAT_MOTIVE + " " + NAT_MINORS + " zero)")
                .rule("Nat.succ", 1,
                        "(fun " + NAT_MOTIVE + " " + NAT_MINORS + " (n : Nat) (succ n ((@ Nat.rec u) motive zero succ n)))")
                .done();
        fx.special(Specials.NAT, "Nat");
        fx.special(Specials.NAT_ZERO, "Nat.zero");
        fx.special(Specials.NAT_SUCC, "Nat.succ");

        // inductive Bool : Type | false | true
        String boolMotive = "(motive : (-> Bool (sort u)))";
        String boolMinors = "(false : (motive Bool.false)) (true : (motive Bool.true))";
        fx.induct("Bool", "", "Type", 0, 0, false)
                .ctor("Bool.false", "Bool", 0)
                .ctor("Bool.true", "Bool", 0)
                .rec("Bool.rec", "u", "(pi " + boolMotive + " " + boolMinors + " (t : Bool) (motive t))", 1, 2, false)
                .rule("Bool.false", 0, "(fun " + boolMotive + " " + boolMinors + " false)")
                .rule("Bool.true", 0, "(fun " + boolMotive + " " + boolMinors + " true)")
                .done();
        fx.special(Specials.BOOL, "Bool");
        fx.special(Specials.BOOL_FALSE, "Bool.false");
        fx.special(Specials.BOOL_TRUE, "Bool.true");

        // The accelerated functions. Only Nat.add has its real body; the others are never unfolded here.
        fx.def("Nat.add", "", "(-> Nat Nat Nat)",
                "(fun (n : Nat) (m : Nat) ((@ Nat.rec 1) (fun (x : Nat) Nat) n (fun (x : Nat) (ih : Nat) (Nat.succ ih)) m))",
                Fixture.regular(1));
        fx.special(Specials.NAT_ADD, "Nat.add");
        String[] binary = {"Nat.sub", "Nat.mul", "Nat.pow", "Nat.gcd", "Nat.mod", "Nat.div", null, null, "Nat.land",
            "Nat.lor", "Nat.xor", "Nat.shiftLeft", "Nat.shiftRight"};
        for (int i = 0; i < binary.length; i++) {
            if (binary[i] != null) {
                fx.opaque(binary[i], "", "(-> Nat Nat Nat)", "(fun (n : Nat) (m : Nat) n)");
                fx.special(Specials.NAT_SUB + i, binary[i]);
            }
        }
        fx.opaque("Nat.beq", "", "(-> Nat Nat Bool)", "(fun (n : Nat) (m : Nat) Bool.true)");
        fx.opaque("Nat.ble", "", "(-> Nat Nat Bool)", "(fun (n : Nat) (m : Nat) Bool.true)");
        fx.special(Specials.NAT_BEQ, "Nat.beq");
        fx.special(Specials.NAT_BLE, "Nat.ble");

        // inductive Eq.{u} {α : Sort u} (a : α) : α → Prop | refl : Eq a a      (K-like recursor)
        fx.induct("Eq", "u", "(pi (α : (sort u)) (a : α) (b : α) Prop)", 2, 1, false)
                .ctor("Eq.refl", "(pi (α : (sort u)) (a : α) ((@ Eq u) α a a))", 0)
                .rec("Eq.rec", "u_1 u",
                        "(pi (α : (sort u)) (a : α) " + EQ_MOTIVE + " (refl : (motive a ((@ Eq.refl u) α a)))"
                                + " (b : α) (t : ((@ Eq u) α a b)) (motive b t))",
                        1, 1, true)
                .rule("Eq.refl", 0,
                        "(fun (α : (sort u)) (a : α) " + EQ_MOTIVE + " (refl : (motive a ((@ Eq.refl u) α a))) refl)")
                .done();
        fx.special(Specials.EQ, "Eq");
        fx.special(Specials.EQ_REFL, "Eq.refl");

        // structure Prod.{u,v} (α : Type u) (β : Type v) : Type (max u v) where mk :: (fst : α) (snd : β)
        String prodParams = "(α : (sort (succ u))) (β : (sort (succ v)))";
        String prodMotive = "(motive : (-> ((@ Prod u v) α β) (sort w)))";
        String prodMinor = "(mk : (pi (fst : α) (snd : β) (motive ((@ Prod.mk u v) α β fst snd))))";
        fx.induct("Prod", "u v", "(pi " + prodParams + " (sort (succ (max u v))))", 2, 0, false)
                .ctor("Prod.mk", "(pi " + prodParams + " (fst : α) (snd : β) ((@ Prod u v) α β))", 2)
                .rec("Prod.rec", "w u v",
                        "(pi " + prodParams + " " + prodMotive + " " + prodMinor + " (t : ((@ Prod u v) α β)) (motive t))",
                        1, 1, false)
                .rule("Prod.mk", 2,
                        "(fun " + prodParams + " " + prodMotive + " " + prodMinor + " (fst : α) (snd : β) (mk fst snd))")
                .done();

        // inductive Unit : Type | unit        (unit-like)
        fx.induct("Unit", "", "Type", 0, 0, false)
                .ctor("Unit.unit", "Unit", 0)
                .rec("Unit.rec", "u",
                        "(pi (motive : (-> Unit (sort u))) (unit : (motive Unit.unit)) (t : Unit) (motive t))", 1, 1, false)
                .rule("Unit.unit", 0, "(fun (motive : (-> Unit (sort u))) (unit : (motive Unit.unit)) unit)")
                .done();

        // inductive IUnit : Nat → Type | mk : IUnit Nat.zero      (indexed: neither unit-like nor a structure)
        fx.induct("IUnit", "", "(-> Nat Type)", 0, 1, false)
                .ctor("IUnit.mk", "(IUnit Nat.zero)", 0)
                .done();

        // inductive True : Prop | intro
        fx.induct("True", "", "Prop", 0, 0, false)
                .ctor("True.intro", "True", 0)
                .rec("True.rec", "u",
                        "(pi (motive : (-> True (sort u))) (intro : (motive True.intro)) (t : True) (motive t))", 1, 1, true)
                .rule("True.intro", 0, "(fun (motive : (-> True (sort u))) (intro : (motive True.intro)) intro)")
                .done();

        // structure And (a b : Prop) : Prop where intro :: (left : a) (right : b)
        String andMotive = "(motive : (-> (And a b) (sort u)))";
        String andMinor = "(intro : (pi (left : a) (right : b) (motive (And.intro a b left right))))";
        fx.induct("And", "", "(-> Prop Prop Prop)", 2, 0, false)
                .ctor("And.intro", "(pi (a : Prop) (b : Prop) (left : a) (right : b) (And a b))", 2)
                .rec("And.rec", "u",
                        "(pi (a : Prop) (b : Prop) " + andMotive + " " + andMinor + " (t : (And a b)) (motive t))", 1, 1,
                        false)
                .rule("And.intro", 2,
                        "(fun (a : Prop) (b : Prop) " + andMotive + " " + andMinor
                                + " (left : a) (right : b) (intro left right))")
                .done();

        // inductive Exists.{u} {α : Sort u} (p : α → Prop) : Prop | intro (w : α) (h : p w)
        fx.induct("Exists", "u", "(pi (α : (sort u)) (p : (-> α Prop)) Prop)", 2, 0, false)
                .ctor("Exists.intro", "(pi (α : (sort u)) (p : (-> α Prop)) (w : α) (h : (p w)) ((@ Exists u) α p))", 2)
                .done();

        // structure PSigma-like in Type: Sig (β : Nat → Type) : Type where mk :: (fst : Nat) (snd : β fst)
        fx.induct("Sig", "", "(-> (-> Nat Type) Type)", 1, 0, false)
                .ctor("Sig.mk", "(pi (β : (-> Nat Type)) (fst : Nat) (snd : (β fst)) (Sig β))", 2)
                .done();

        // Quot
        fx.quot();

        // List, Char, String and the constants behind string literals
        String listParam = "(α : (sort (succ u)))";
        fx.induct("List", "u", "(pi " + listParam + " (sort (succ u)))", 1, 0, true)
                .ctor("List.nil", "(pi " + listParam + " ((@ List u) α))", 0)
                .ctor("List.cons", "(pi " + listParam + " (head : α) (tail : ((@ List u) α)) ((@ List u) α))", 2)
                .done();
        fx.axiom("Char", "", "Type");
        fx.axiom("String", "", "Type");
        fx.axiom("Char.ofNat", "", "(-> Nat Char)");
        fx.axiom("String.ofList", "", "(-> ((@ List 0) Char) String)");
        fx.special(Specials.LIST, "List");
        fx.special(Specials.LIST_NIL, "List.nil");
        fx.special(Specials.LIST_CONS, "List.cons");
        fx.special(Specials.CHAR, "Char");
        fx.special(Specials.STRING, "String");
        fx.special(Specials.CHAR_OF_NAT, "Char.ofNat");
        fx.special(Specials.STRING_OF_LIST, "String.ofList");

        for (String str : new String[] {"", "a", "ab", "abc", "abd", "\u00e9"}) {
            fx.string(str);
        }

        // A few definitions
        fx.def("id", "u", "(pi (α : (sort u)) (a : α) α)", "(fun (α : (sort u)) (a : α) a)", Fixture.ABBREV);
        fx.def("two", "", "Nat", "(Nat.succ (Nat.succ Nat.zero))", Fixture.regular(1));
        fx.def("four", "", "Nat", "(Nat.add two two)", Fixture.regular(2));
        fx.def("NatAlias", "", "Type", "Nat", Fixture.regular(1));
        fx.axiom("P", "", "Prop");
        fx.axiom("p1", "", "P");
        fx.axiom("p2", "", "P");
        fx.axiom("Q", "", "Prop");
        fx.axiom("q1", "", "Q");
        fx.thm("pThm", "", "P", "p1");
        fx.opaque("opaqueTwo", "", "Nat", "two");
        fx.axiom("f", "", "(-> Nat Nat)");
        fx.axiom("g", "", "(-> Nat Nat Nat)");
        fx.axiom("n0", "", "Nat");
        fx.axiom("u0", "", "Unit");
        fx.axiom("u1", "", "Unit");
        fx.axiom("pr", "", "((@ Prod 0 0) Nat Bool)");
        fx.axiom("eqn", "", "((@ Eq 1) Nat n0 n0)");
        fx.axiom("eqn2", "", "((@ Eq 1) Nat n0 (f n0))");
        fx.axiom("r", "", "(-> Nat Nat Prop)");
        fx.axiom("hr", "", "(pi (a : Nat) (b : Nat) (-> (r a b) ((@ Eq 1) Nat (f a) (f b))))");
        fx.axiom("qb", "", "(-> ((@ Quot 1) Nat r) Prop)");
        fx.axiom("qm", "", "(pi (a : Nat) (qb ((@ Quot.mk 1) Nat r a)))");
        fx.def("prDef", "", "((@ Prod 0 0) Nat Bool)", "((@ Prod.mk 0 0) Nat Bool n0 Bool.true)", Fixture.regular(1));
        fx.def("prDef2", "", "((@ Prod 0 0) Nat Bool)", "prDef", Fixture.regular(2));
        fx.def("twoAlias", "", "Nat", "two", Fixture.regular(2));
        fx.axiom("iu0", "", "(IUnit Nat.zero)");
        fx.axiom("iu1", "", "(IUnit Nat.zero)");
        fx.def("eagerReduce", "u", "(pi (α : (sort u)) (a : α) α)", "(fun (α : (sort u)) (a : α) a)", Fixture.ABBREV);
        fx.special(Specials.EAGER_REDUCE, "eagerReduce");
        fx.def("FnAlias", "", "Type", "(-> Nat Nat)", Fixture.regular(1));
        fx.axiom("fa", "", "FnAlias");
        fx.axiom("B", "", "(-> Nat Type)");
        fx.axiom("sg", "", "(Sig B)");
        fx.axiom("andPQ", "", "(And P Q)");
        fx.axiom("pred", "", "(-> Nat Prop)");
        fx.axiom("ex", "", "((@ Exists 1) Nat pred)");
        fx.axiom("PI", "", "(sort (imax 1 0))");
        fx.axiom("PU", "u", "(sort u)");
    }
}
