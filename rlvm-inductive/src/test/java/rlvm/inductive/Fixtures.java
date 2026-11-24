package rlvm.inductive;

import rlvm.store.DeclKind;

/** The inductive types the unit tests admit, in the notation of {@link TermParser}. */
final class Fixtures {
    private Fixtures() {}

    static Env.Block nat(Env e) {
        return e.inductive("", 0).type("Nat", "Type")
                .ctor("Nat.zero", "Nat").ctor("Nat.succ", "Nat → Nat")
                .rec("Nat.rec", "u").end();
    }

    static Env.Block list(Env e) {
        return e.inductive("u", 1).type("List", "Type u → Type u")
                .ctor("List.nil", "∀ (α : Type u), List.{u} α")
                .ctor("List.cons", "∀ (α : Type u), α → List.{u} α → List.{u} α")
                .rec("List.rec", "u_1 u").end();
    }

    /** As in Init.Prelude: the universe parameter of Eq is called u_1, so the recursor gets u. */
    static Env.Block eq(Env e) {
        return e.inductive("u_1", 2).type("Eq", "∀ (α : Sort u_1), α → α → Prop")
                .ctor("Eq.refl", "∀ (α : Sort u_1) (a : α), Eq.{u_1} α a a")
                .rec("Eq.rec", "u u_1").end();
    }

    static Env.Block acc(Env e) {
        return e.inductive("u", 2).type("Acc", "∀ (α : Sort u), (α → α → Prop) → α → Prop")
                .ctor("Acc.intro",
                        "∀ (α : Sort u) (r : α → α → Prop) (x : α), (∀ (y : α), r y x → Acc.{u} α r y) → Acc.{u} α r x")
                .rec("Acc.rec", "u_1 u").end();
    }

    static Env.Block prod(Env e) {
        return e.inductive("u v", 2).type("Prod", "Type u → Type v → Type (max u v)")
                .ctor("Prod.mk", "∀ (α : Type u) (β : Type v), α → β → Prod.{u,v} α β")
                .rec("Prod.rec", "u_1 u v").end();
    }

    static Env.Block and(Env e) {
        return e.inductive("", 2).type("And", "Prop → Prop → Prop")
                .ctor("And.intro", "∀ (a b : Prop), a → b → And a b")
                .rec("And.rec", "u").end();
    }

    static Env.Block trueProp(Env e) {
        return e.inductive("", 0).type("True", "Prop").ctor("True.intro", "True").rec("True.rec", "u").end();
    }

    static Env.Block falseProp(Env e) {
        return e.inductive("", 0).type("False", "Prop").rec("False.rec", "u").end();
    }

    /** Needs Nat. A mutual pair of predicates: recursors eliminate into Prop only. */
    static Env.Block evenOdd(Env e) {
        return e.inductive("", 0)
                .type("EvenT", "Nat → Prop")
                .ctor("EvenT.zero", "EvenT Nat.zero")
                .ctor("EvenT.succ", "∀ (n : Nat), OddT n → EvenT (Nat.succ n)")
                .type("OddT", "Nat → Prop")
                .ctor("OddT.succ", "∀ (n : Nat), EvenT n → OddT (Nat.succ n)")
                .rec("EvenT.rec", "").rec("OddT.rec", "").end();
    }

    /** Needs List. */
    static Env.Block tree(Env e) {
        return e.inductive("u", 1).type("Tree", "Type u → Type u")
                .ctor("Tree.node", "∀ (α : Type u), α → List.{u} (Tree.{u} α) → Tree.{u} α")
                .rec("Tree.rec", "u_1 u").rec("Tree.rec_1", "u_1 u").end();
    }

    /** Needs List. */
    static Env.Block array(Env e) {
        return e.inductive("u", 1).type("Array", "Type u → Type u")
                .ctor("Array.mk", "∀ (α : Type u), List.{u} α → Array.{u} α")
                .rec("Array.rec", "u_1 u").end();
    }

    /** Needs Array (and List). */
    static Env.Block rose(Env e) {
        return e.inductive("u", 1).type("Rose", "Type u → Type u")
                .ctor("Rose.node", "∀ (α : Type u), α → Array.{u} (Rose.{u} α) → Rose.{u} α")
                .rec("Rose.rec", "u_1 u").rec("Rose.rec_1", "u_1 u").rec("Rose.rec_2", "u_1 u").end();
    }

    /**
     * {@code T.mk : ∀ (α : (λ f, Type) L), ((λ f, Nat) L) → T α} with ONE node {@code L = λ β, T β} used at
     * binder offsets 0 and 1. Needs Nat. At offset 0 the occurrence {@code T β} is uniform (β has the
     * index a parameter would have), at offset 1 it is not: the official for_each visits a shared node
     * once per offset.
     */
    static Env.Block sharedOccurrenceAtTwoOffsets(Env e) {
        return e.inductive("", 1).type("T", "Type → Type").ctorBuilt("T.mk", env -> {
            TermParser p = env.shared;
            int lam = p.parse("λ (β : Type), T β");
            int dom0 = env.sb.app(p.parse("λ (f : Type → Type), Type"), lam);
            int dom1 = env.sb.app(p.parse("λ (f : Type → Type), Nat"), lam);
            return env.sb.pi(dom0, env.sb.pi(dom1, env.sb.app(p.parse("T"), env.sb.bvar(1))));
        }).rec("T.rec", "u").end();
    }

    /** The four quot records of a canonical export, after Eq. */
    static int[] quot(Env e) {
        String rel = "(α → α → Prop)";
        int q = e.quot(DeclKind.QUOT_TYPE, "Quot", "u", "∀ (α : Sort u), " + rel + " → Sort u");
        int mk = e.quot(DeclKind.QUOT_MK, "Quot.mk", "u", "∀ (α : Sort u) (r : " + rel + "), α → Quot.{u} α r");
        int lift = e.quot(DeclKind.QUOT_LIFT, "Quot.lift", "u v",
                "∀ (α : Sort u) (r : " + rel + ") (β : Sort v) (f : α → β), "
                        + "(∀ (a b : α), r a b → Eq.{v} β (f a) (f b)) → Quot.{u} α r → β");
        int ind = e.quot(DeclKind.QUOT_IND, "Quot.ind", "u",
                "∀ (α : Sort u) (r : " + rel + ") (β : Quot.{u} α r → Prop), "
                        + "(∀ (a : α), β (Quot.mk.{u} α r a)) → ∀ (q : Quot.{u} α r), β q");
        return new int[] {q, mk, lift, ind};
    }
}
