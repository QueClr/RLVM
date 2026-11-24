package rlvm.inductive;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import rlvm.api.KernelException;
import rlvm.api.ResourceException;
import rlvm.store.DeclKind;

/**
 * Differential test against the official checker ({@code leanchecker --from-export}, Lean 4.36.0):
 * every fixture is written out as a {@code lean4export} file whose recursor records are the ones
 * this module derived ({@link Ndjson}). The official kernel regenerates the recursors and requires
 * the exported records to be identical, so
 * <ul>
 *   <li>a fixture this module admits must be ACCEPTED by the official checker (the derived facts,
 *       recursor types and rules are then the official ones, binder for binder);
 *   <li>a fixture this module rejects must be REJECTED by the official kernel while it replays that
 *       very block.
 * </ul>
 * Fixtures this module declines are only reported (a decline is never a wrong verdict). The official
 * answers are stored by the SHA-256 of the export ({@link OfficialGolden}): no checker binary is run.
 */
class OfficialDifferentialTest {

    private static final int ACCEPT = 0;
    private static final int REJECT = 1;
    private static final int DECLINE = 2;

    private static final class Scenario {
        final String name;
        final Consumer<Env> build;
        final int expect;

        Scenario(String name, int expect, Consumer<Env> build) {
            this.name = name;
            this.build = build;
            this.expect = expect;
        }
    }

    private static final ArrayList<Scenario> SCENARIOS = new ArrayList<>();

    private static void accept(String name, Consumer<Env> build) {
        SCENARIOS.add(new Scenario(name, ACCEPT, build));
    }

    /** The LAST block declared by {@code build} must be rejected, here and officially. */
    private static void reject(String name, Consumer<Env> build) {
        SCENARIOS.add(new Scenario(name, REJECT, build));
    }

    private static void decline(String name, Consumer<Env> build) {
        SCENARIOS.add(new Scenario(name, DECLINE, build));
    }

    static {
        // ---- admitted: the derived recursors must be the official ones ---------------------------------
        accept("Nat", Fixtures::nat);
        accept("List", Fixtures::list);
        accept("Eq", Fixtures::eq);
        accept("Acc", Fixtures::acc);
        accept("Prod + And", e -> {
            Fixtures.prod(e);
            Fixtures.and(e);
        });
        accept("True + False", e -> {
            Fixtures.trueProp(e);
            Fixtures.falseProp(e);
        });
        accept("EvenT / OddT", e -> {
            Fixtures.nat(e);
            Fixtures.evenOdd(e);
        });
        accept("Tree (nested through List)", e -> {
            Fixtures.list(e);
            Fixtures.tree(e);
        });
        accept("Rose (nested through Array and List)", e -> {
            Fixtures.list(e);
            Fixtures.array(e);
            Fixtures.rose(e);
        });
        accept("Eq + Quot", e -> {
            Fixtures.eq(e);
            Fixtures.quot(e);
        });
        accept("Or", e -> e.inductive("", 2).type("Or", "Prop → Prop → Prop")
                .ctor("Or.inl", "∀ (a b : Prop), a → Or a b").ctor("Or.inr", "∀ (a b : Prop), b → Or a b").rec("Or.rec", "").end());
        accept("Exists", e -> e.inductive("u", 2).type("Exists", "∀ (α : Sort u), (α → Prop) → Prop")
                .ctor("Exists.intro", "∀ (α : Sort u) (p : α → Prop) (w : α), p w → Exists.{u} α p").rec("Exists.rec", "u").end());
        accept("sortElimPropRec", e -> {
            Fixtures.nat(e);
            e.inductive("", 0).type("S", "Nat → Prop").ctor("S.mk", "∀ (n : Nat), S n").rec("S.rec", "u").end();
        });
        accept("sortElimProp2Rec", e -> {
            Fixtures.nat(e);
            e.def("idNat", "", "Nat → Nat", "λ (n : Nat), n");
            e.inductive("", 0).type("S", "Nat → Prop").ctor("S.mk", "∀ (n : Nat), S (idNat n)").rec("S.rec", "").end();
        });
        accept("MyBool : Sort u", e -> e.inductive("u", 0).type("MyBool", "Sort u").ctor("MyBool.tt", "MyBool.{u}")
                .ctor("MyBool.ff", "MyBool.{u}").rec("MyBool.rec", "u").end());
        accept("PUnit", e -> e.inductive("u", 0).type("PUnit", "Sort u").ctor("PUnit.unit", "PUnit.{u}").rec("PUnit.rec", "u_1 u").end());
        accept("universe names u, u_1 taken", e -> e.inductive("u u_1", 0).type("P2", "Sort (max u u_1)")
                .ctor("P2.mk", "P2.{u,u_1}").rec("P2.rec", "u_2 u u_1").end());
        accept("vanishing occurrence", e -> {
            Fixtures.nat(e);
            e.inductive("", 0).type("V", "Type").ctor("V.mk", "((λ (x : Type), Nat) V) → V").rec("V.rec", "u").end();
        });
        accept("annotations (T2)", e -> {
            Fixtures.nat(e);
            e.def("outParam", "", "Type 1 → Type 1", "λ (a : Type 1), a");
            e.def("optParam", "", "∀ (a : Type), a → Type", "λ (a : Type) (d : a), a");
            e.inductive("", 1).type("OP", "outParam Type → Type")
                    .ctor("OP.mk", "∀ (α : outParam Type), α → optParam Nat Nat.zero → OP α").rec("OP.rec", "u").end();
        });
        accept("annotation on a recursive field (T2)", e -> {
            Fixtures.nat(e);
            e.def("optParam", "", "Type → Nat → Type", "λ (a : Type) (n : Nat), a");
            e.inductive("", 0).type("R", "Type").ctor("R.leaf", "R")
                    .ctor("R.node", "optParam R Nat.zero → R").rec("R.rec", "u").end();
        });
        accept("hypothesis from whnf (T4)", e -> {
            e.def("constType", "", "Type → Type → Type", "λ (a b : Type), a");
            e.inductive("", 1).type("T", "Type → Type")
                    .ctor("T.mk", "∀ (α : Type), (α → constType (T α) α) → T α").rec("T.rec", "u").end();
        });
        accept("recursive field keeps its declared type (T4)", e -> {
            Fixtures.nat(e);
            e.def("constType", "", "Type → Type → Type", "λ (a b : Type), a");
            e.inductive("", 1).type("T", "Type → Type").ctor("T.leaf", "∀ (α : Type), T α")
                    .ctor("T.mk", "∀ (α : Type), constType (T α) Nat → T α").rec("T.rec", "u").end();
        });
        accept("header behind a definition (T4)", e -> {
            Fixtures.nat(e);
            e.def("Fam", "", "Type 1", "Nat → Type");
            e.inductive("", 0).type("I", "Fam").ctor("I.mk", "∀ (n : Nat), I n").rec("I.rec", "u").end();
        });
        accept("indexed family with a reflexive field", e -> {
            Fixtures.nat(e);
            e.inductive("u", 1).type("W", "Type u → Nat → Type u")
                    .ctor("W.leaf", "∀ (α : Type u), α → W.{u} α Nat.zero")
                    .ctor("W.sup", "∀ (α : Type u) (n : Nat), (Nat → W.{u} α n) → W.{u} α (Nat.succ n)")
                    .rec("W.rec", "u_1 u").end();
        });
        accept("mutual types with parameters", e -> e.inductive("u", 1)
                .type("A", "Type u → Type u").ctor("A.nil", "∀ (α : Type u), A.{u} α")
                .ctor("A.cons", "∀ (α : Type u), α → B.{u} α → A.{u} α")
                .type("B", "Type u → Type u").ctor("B.mk", "∀ (α : Type u), A.{u} α → (α → A.{u} α) → B.{u} α")
                .rec("A.rec", "u_1 u").rec("B.rec", "u_1 u").end());
        accept("nested twice through the same container", e -> {
            Fixtures.list(e);
            Fixtures.prod(e);
            e.inductive("", 0).type("N", "Type")
                    .ctor("N.mk", "List.{0} N → Prod.{0,0} N (List.{0} N) → List.{0} (List.{0} N) → N")
                    .rec("N.rec", "u").rec("N.rec_1", "u").rec("N.rec_2", "u").rec("N.rec_3", "u").end();
        });
        accept("nested in a mutual block, with an index in the container", e -> {
            Fixtures.nat(e);
            Fixtures.list(e);
            e.inductive("u", 1).type("Vec", "Type u → Nat → Type u")
                    .ctor("Vec.nil", "∀ (α : Type u), Vec.{u} α Nat.zero")
                    .ctor("Vec.cons", "∀ (α : Type u) (n : Nat), α → Vec.{u} α n → Vec.{u} α (Nat.succ n)")
                    .rec("Vec.rec", "u_1 u").end();
            e.inductive("", 0)
                    .type("P", "Type").ctor("P.mk", "∀ (n : Nat), Vec.{0} Q n → P")
                    .type("Q", "Type").ctor("Q.leaf", "Q").ctor("Q.mk", "List.{0} P → Q")
                    .rec("P.rec", "u").rec("Q.rec", "u").rec("P.rec_1", "u").rec("P.rec_2", "u").end();
        });
        accept("nested occurrence only in a container index (kept as written)", e -> {
            Fixtures.nat(e);
            Fixtures.list(e);
            e.inductive("", 1).type("Wrap", "Type → Type → Type").ctor("Wrap.mk", "∀ (α : Type), Wrap α Nat")
                    .rec("Wrap.rec", "u").end();
            e.inductive("", 0).type("T", "Type").ctor("T.mk", "((λ (x : Type), Nat) (Wrap T (List.{0} T))) → T")
                    .rec("T.rec", "u").rec("T.rec_1", "u").end();
        });

        accept("PSigma (dependent structure, two universes)", e -> e.inductive("u v", 2)
                .type("PSigma", "∀ (α : Sort u), (α → Sort v) → Sort (max 1 u v)".replace("max 1 u v", "max (max 1 u) v"))
                .ctor("PSigma.mk", "∀ (α : Sort u) (β : α → Sort v) (fst : α), β fst → PSigma.{u,v} α β")
                .rec("PSigma.rec", "u_1 u v").end());
        accept("HEq (two parameters, two indices, K)", e -> e.inductive("u", 2)
                .type("HEq", "∀ (α : Sort u), α → ∀ (β : Sort u), β → Prop")
                .ctor("HEq.refl", "∀ (α : Sort u) (a : α), HEq.{u} α a α a").rec("HEq.rec", "u_1 u").end());
        accept("nested Prop through And (no motive universe)", e -> {
            Fixtures.and(e);
            e.inductive("", 0).type("N", "Prop").ctor("N.mk", "And N N → N").rec("N.rec", "").rec("N.rec_1", "").end();
        });
        accept("nested under a function type", e -> {
            Fixtures.nat(e);
            Fixtures.list(e);
            e.inductive("", 0).type("T", "Type").ctor("T.mk", "(Nat → List.{0} T) → T").rec("T.rec", "u").rec("T.rec_1", "u").end();
        });
        accept("nested with the block's parameter inside the container arguments", e -> {
            Fixtures.list(e);
            Fixtures.prod(e);
            e.inductive("u", 1).type("T", "Type u → Type u")
                    .ctor("T.mk", "∀ (α : Type u), List.{u} (Prod.{u,u} α (T.{u} α)) → T.{u} α")
                    .rec("T.rec", "u_1 u").rec("T.rec_1", "u_1 u").rec("T.rec_2", "u_1 u").end();
        });
        accept("container universes are instantiated with simplification (T3)", e -> {
            e.inductive("u", 0).type("PUnit", "Sort u").ctor("PUnit.unit", "PUnit.{u}").rec("PUnit.rec", "u_1 u").end();
            e.inductive("a b", 1).type("C", "Type (max a b) → Type (max a b)")
                    .ctor("C.mk", "∀ (α : Type (max a b)), (PUnit.{(max a b)+1} → α) → C.{a,b} α").rec("C.rec", "u a b").end();
            e.inductive("u", 0).type("T", "Type u").ctor("T.leaf", "T.{u}").ctor("T.mk", "C.{u,u} T.{u} → T.{u}")
                    .rec("T.rec", "u_1 u").rec("T.rec_1", "u_1 u").end();
        });
        accept("container at a compound universe", e -> {
            Fixtures.list(e);
            e.inductive("u v", 0).type("T", "Type (max u v)").ctor("T.mk", "List.{(max u v)} T.{u,v} → T.{u,v}")
                    .rec("T.rec", "u_1 u v").rec("T.rec_1", "u_1 u v").end();
        });
        accept("index binders out of whnf between binders (T4)", e -> {
            Fixtures.nat(e);
            e.def("G", "", "Nat → Type 1", "λ (n : Nat), Nat → Type");
            e.inductive("", 0).type("J", "∀ (n : Nat), G n").ctor("J.mk", "∀ (n m : Nat), J n m").rec("J.rec", "u").end();
        });
        accept("annotation on an index binder (T2)", e -> {
            Fixtures.nat(e);
            e.def("outParam", "", "Type 1 → Type 1", "λ (a : Type 1), a");
            e.inductive("", 0).type("Q", "outParam Type → Type").ctor("Q.mk", "Nat → Q Nat").rec("Q.rec", "u").end();
        });
        accept("autoParam with two arguments on a field (T2)", e -> {
            Fixtures.nat(e);
            e.def("autoParam", "", "Type → Nat → Type", "λ (a : Type) (n : Nat), a");
            e.inductive("", 0).type("AP", "Type").ctor("AP.mk", "autoParam Nat Nat.zero → autoParam AP Nat.zero → AP")
                    .rec("AP.rec", "u").end();
        });
        accept("Prop with a hidden Type field (small elimination)",
                e -> e.inductive("", 0).type("P", "Prop").ctor("P.mk", "Type → P").rec("P.rec", "").end());
        accept("parametric predicate whose field is an index (large elimination, K excluded)",
                e -> e.inductive("", 1).type("P", "∀ (α : Type), α → Prop").ctor("P.mk", "∀ (α : Type) (a : α), P α a")
                        .rec("P.rec", "u").end());
        accept("unit-like Prop with a parameter (K)", e -> {
            Fixtures.nat(e);
            e.inductive("", 1).type("Foo", "Nat → Prop").ctor("Foo.mk", "∀ (a : Nat), Foo a").rec("Foo.rec", "u").end();
        });
        accept("mutual nested: two blocks' worth of auxiliary types in discovery order", e -> {
            Fixtures.list(e);
            Fixtures.prod(e);
            e.inductive("", 0)
                    .type("A", "Type").ctor("A.mk", "Prod.{0,0} B (List.{0} A) → A")
                    .type("B", "Type").ctor("B.leaf", "B").ctor("B.mk", "List.{0} A → List.{0} B → B")
                    .rec("A.rec", "u").rec("B.rec", "u").rec("A.rec_1", "u").rec("A.rec_2", "u").rec("A.rec_3", "u").end();
        });

        accept("occurrences that differ by a beta-redex give two auxiliary types (corner-cases/nested-redex-aux)", e -> {
            Fixtures.list(e);
            e.inductive("", 0).type("T", "Type").ctor("T.mk", "List.{0} T → List.{0} ((λ (x : Type), x) T) → T")
                    .rec("T.rec", "u").rec("T.rec_1", "u").rec("T.rec_2", "u").end();
        });
        accept("parameters equal only up to conversion in a field are fine (corner-cases/proof-param-ok)", e -> {
            e.def("TypeAlias", "", "Type 1", "Type");
            e.inductive("", 1).type("T", "Type → Type").ctor("T.mk", "∀ (α : TypeAlias), α → T α").rec("T.rec", "u").end();
        });

        // finding L-F1: levels the official kernel respells when it refers to the block's own constants
        accept("respelled but equivalent: parameter of sort imax u (v+1)", e -> e.inductive("u v", 1)
                .type("T", "Sort (imax u (v+1)) → Sort (imax u (v+1))")
                .ctor("T.mk", "∀ (a : Sort (imax u (v+1))), a → T.{u,v} a").rec("T.rec", "u_1 u v").end());
        accept("respelled result sort alone (never compared)", e -> e.inductive("u", 0).type("T", "Sort (imax u 1)")
                .ctor("T.mk", "T.{u}").rec("T.rec", "u_1 u").end());
        accept("respelled phantom universe of a definition in a result index (equal after unfolding)", e -> {
            e.axiom("G", "", "Type");
            e.axiom("g0", "", "G");
            e.def("gd", "u", "G", "g0");
            e.inductive("u", 0).type("T", "G → Type").ctor("T.mk", "T.{u} gd.{(imax u 1)}").rec("T.rec", "u_1 u").end();
        });

        // ---- rejected: the official kernel must reject the same block -------------------------------------
        reject("L-F1 (a): parameter of sort imax u 1", e -> e.inductive("u v w", 1)
                .type("T", "Sort (imax u 1) → Sort (imax u 1)")
                .ctor("T.mk", "∀ (a : Sort (imax u 1)), a → T.{u,v,w} a").rec("T.rec", "u_1 u v w").end());
        reject("L-F1 (b): field of type F.{imax u 1}", e -> {
            e.axiom("F", "u", "Type");
            e.inductive("u v w", 0).type("T", "Type").ctor("T.mk", "F.{(imax u 1)} → T.{u,v,w}").rec("T.rec", "u_1 u v w").end();
        });
        reject("L-F1 (N): constructor parameter imax u 1 against the header's imax 0 (imax u 1)", e -> e.inductive("u v w", 1)
                .type("T", "Sort (imax 0 (imax u 1)) → Type")
                .ctor("T.mk", "∀ (a : Sort (imax u 1)), T.{u,v,w} a").rec("T.rec", "u_1 u v w").end());
        reject("L-F1: index domain of sort imax u 1", e -> {
            e.axiom("A", "u", "Sort (imax u 1)");
            e.inductive("u", 0).type("T", "Sort (imax u 1) → Type").ctor("T.mk", "T.{u} A.{u}").rec("T.rec", "u_1 u").end();
        });
        reject("L-F1: result index g.{imax u 1} against the major premise", e -> {
            e.axiom("G", "", "Type");
            e.axiom("g", "u", "G");
            e.inductive("u", 0).type("T", "G → Type").ctor("T.mk", "T.{u} g.{(imax u 1)}").rec("T.rec", "u_1 u").end();
        });
        reject("L-F1: recursive field T g.{imax u 1}", e -> {
            e.axiom("G", "", "Type");
            e.axiom("g0", "", "G");
            e.axiom("g", "u", "G");
            e.inductive("u", 0).type("T", "G → Type").ctor("T.base", "T.{u} g0")
                    .ctor("T.step", "T.{u} g.{(imax u 1)} → T.{u} g0").rec("T.rec", "u_1 u").end();
        });
        reject("L-F1: second type of a mutual block with a parameter of sort imax u 1", e -> e.inductive("u", 1)
                .type("A", "Sort (imax u 1) → Type").ctor("A.mk", "∀ (a : Sort (imax u 1)), A.{u} a")
                .type("B", "Sort (imax u 1) → Type").ctor("B.mk", "∀ (a : Sort (imax u 1)), A.{u} a → B.{u} a")
                .rec("A.rec", "u_1 u").rec("B.rec", "u_1 u").end());
        reject("L-F1: index domain of an auxiliary type (nested block)", e -> {
            e.axiom("F", "u", "Type");
            e.inductive("u", 1).type("J", "Type → F.{u} → Type")
                    .ctor("J.mk", "∀ (α : Type) (i : F.{u}), α → J.{u} α i").rec("J.rec", "u_1 u").end();
            e.inductive("u", 0).type("T", "Type")
                    .ctor("T.mk", "∀ (i : F.{(imax u 1)}), J.{(imax u 1)} T.{u} i → T.{u}")
                    .rec("T.rec", "u_1 u").rec("T.rec_1", "u_1 u").end();
        });

        reject("non-uniform parameter inside a nested container (corner-cases/nested-nonuniform-param)", e -> {
            Fixtures.nat(e);
            Fixtures.list(e);
            e.inductive("", 1).type("T", "Type → Type").ctor("T.mk", "∀ (α : Type), List.{0} (T Nat) → T α")
                    .rec("T.rec", "u").rec("T.rec_1", "u").end();
        });
        reject("result parameter only convertible to the parameter (corner-cases/proof-param-swap)", e -> {
            e.def("idT", "", "Type → Type", "λ (a : Type), a");
            e.inductive("", 1).type("T", "Type → Type").ctor("T.mk", "∀ (α : Type), T (idT α)").rec("T.rec", "u").end();
        });
        reject("shared subterm with an occurrence, uniform at one binder offset only", e -> {
            Fixtures.nat(e);
            Fixtures.sharedOccurrenceAtTwoOffsets(e);
        });
        reject("negative occurrence inside a nested container", e -> {
            Fixtures.nat(e);
            Fixtures.list(e);
            e.inductive("", 0).type("T", "Type").ctor("T.mk", "List.{0} (T → Nat) → T").rec("T.rec", "u").rec("T.rec_1", "u").end();
        });
        reject("constructor result is another type of the block", e -> e.inductive("", 0)
                .type("A", "Type").ctor("A.mk", "B")
                .type("B", "Type").ctor("B.mk", "B").rec("A.rec", "u").rec("B.rec", "u").end());
        reject("result index count is wrong", e -> {
            Fixtures.nat(e);
            e.inductive("", 0).type("I", "Nat → Type").ctor("I.mk", "I").rec("I.rec", "u").end();
        });
        reject("field is not a type", e -> {
            Fixtures.nat(e);
            e.inductive("", 0).type("T", "Type").ctor("T.mk", "Nat.zero → T").rec("T.rec", "u").end();
        });
        reject("header is not a sort", e -> {
            Fixtures.nat(e);
            e.inductive("", 0).type("Bad", "Nat").ctor("Bad.mk", "Bad").rec("Bad.rec", "u").end();
        });
        reject("too few parameters", e -> e.inductive("", 1).type("Bad", "Type").ctor("Bad.mk", "Bad").rec("Bad.rec", "u").end());
        reject("parameters hidden behind a definition", e -> {
            e.def("F", "", "Type 1", "Type → Type");
            e.inductive("", 1).type("Bad", "F").ctor("Bad.mk", "∀ (α : Type), Bad α").rec("Bad.rec", "u").end();
        });
        reject("constructor parameter of another type",
                e -> e.inductive("", 1).type("T", "Type → Type").ctor("T.mk", "∀ (α : Prop), T α").rec("T.rec", "u").end());
        reject("result parameters swapped", e -> e.inductive("", 2).type("T", "Type → Type → Type")
                .ctor("T.mk", "∀ (α β : Type), T β α").rec("T.rec", "u").end());
        reject("result levels swapped", e -> e.inductive("u v", 0).type("T", "Sort (max u v)")
                .ctor("T.mk", "T.{v,u}").rec("T.rec", "u_1 u v").end());
        reject("under-applied occurrence", e -> {
            Fixtures.nat(e);
            e.inductive("", 1).type("T", "Type → Type")
                    .ctor("T.mk", "∀ (α : Type), ((λ (f : Type → Type), Nat) T) → T α").rec("T.rec", "u").end();
        });
        reject("block type inside an index of the result", e -> {
            Fixtures.nat(e);
            e.inductive("", 0).type("I", "Type → Type").ctor("I.mk", "I (I Nat)").rec("I.rec", "u").end();
        });
        reject("negative occurrence", e -> {
            Fixtures.nat(e);
            e.inductive("", 0).type("Bad", "Type").ctor("Bad.mk", "(Bad → Nat) → Bad").rec("Bad.rec", "u").end();
        });
        reject("negative occurrence behind a reducible codomain", e -> {
            Fixtures.nat(e);
            e.def("constType", "", "Type → Type → Type", "λ (a b : Type), a");
            e.inductive("", 0).type("Bad", "Type").ctor("Bad.mk", "(constType Nat Bad → Bad) → Bad").rec("Bad.rec", "u").end();
        });
        reject("occurrence under an opaque function", e -> {
            e.axiom("Box", "", "Type → Type");
            e.inductive("", 0).type("Bad", "Type").ctor("Bad.mk", "Box Bad → Bad").rec("Bad.rec", "u").end();
        });
        reject("field universe too big", e -> e.inductive("", 0).type("T", "Type").ctor("T.mk", "Type → T").rec("T.rec", "u").end());
        reject("invalid return type", e -> {
            Fixtures.nat(e);
            e.inductive("", 0).type("T", "Type").ctor("T.mk", "Nat").rec("T.rec", "u").end();
        });
        reject("reserved prefix", e -> {
            e.def("_nested.X", "", "Type 1", "Type");
            e.inductive("", 0).type("T", "Type 1").ctor("T.mk", "_nested.X → T").rec("T.rec", "u").end();
        });
        reject("header mentions its own block",
                e -> e.inductive("", 0).type("T", "T → Type").ctor("T.mk", "T").rec("T.rec", "u").end());
        reject("constructor type mentions a constructor of the block", e -> {
            Fixtures.eq(e);
            e.inductive("", 0).type("T", "Type").ctor("T.a", "T").ctor("T.b", "Eq.{1} T T.a T.a → T").rec("T.rec", "u").end();
        });
        reject("mutual types in different universes", e -> e.inductive("", 0).type("A", "Type").ctor("A.mk", "A")
                .type("B", "Type 1").ctor("B.mk", "B").rec("A.rec", "u").rec("B.rec", "u").end());
        reject("mutual types with different parameters", e -> e.inductive("", 1).type("A", "Type → Type")
                .ctor("A.mk", "∀ (α : Type), A α").type("B", "Prop → Type").ctor("B.mk", "∀ (α : Prop), B α")
                .rec("A.rec", "u").rec("B.rec", "u").end());
        reject("nested parameters with local variables", e -> {
            Fixtures.nat(e);
            e.inductive("", 2).type("Vec", "Type → Nat → Type").ctor("Vec.mk", "∀ (α : Type) (n : Nat), Vec α n")
                    .rec("Vec.rec", "u").end();
            e.inductive("", 0).type("T", "Type").ctor("T.mk", "∀ (n : Nat), Vec T n → T")
                    .rec("T.rec", "u").rec("T.rec_1", "u").end();
        });
        reject("nested parameter that vanishes", e -> {
            Fixtures.nat(e);
            e.inductive("", 2).type("K", "Type → Nat → Type").ctor("K.mk", "∀ (α : Type) (n : Nat), K α n")
                    .rec("K.rec", "u").end();
            e.inductive("", 0).type("T", "Type").ctor("T.mk", "K T Nat.succ → T").rec("T.rec", "u").rec("T.rec_1", "u").end();
        });
        reject("container index type mentions the block", e -> {
            e.inductive("", 1).type("Fam", "∀ (α : Type), (α → Prop) → Type")
                    .ctor("Fam.mk", "∀ (α : Type) (p : α → Prop), Fam α p").rec("Fam.rec", "u").end();
            e.inductive("", 0).type("T", "Type").ctor("T.mk", "∀ (p : T → Prop), Fam T p → T")
                    .rec("T.rec", "u").rec("T.rec_1", "u").end();
        });

        // ---- declined here: what does the official kernel say? (reported only) ----------------------------
        decline("block type named like a type annotation", e -> e.inductive("u", 1).type("outParam", "Sort u → Sort (max 1 u)")
                .ctor("outParam.mk", "∀ (α : Sort u), α → outParam.{u} α").rec("outParam.rec", "u_1 u").end());
        decline("nested block after a declaration under _nested", e -> {
            Fixtures.list(e);
            e.def("_nested.List_1.rec", "", "Type 1", "Type");
            Fixtures.tree(e);
        });
        decline("container arguments mention a constructor of the block", e -> {
            e.inductive("", 2).type("Foo", "∀ (α : Type), α → Type").ctor("Foo.mk", "∀ (α : Type) (a : α), Foo α a")
                    .rec("Foo.rec", "u").end();
            e.inductive("", 0).type("T", "Type").ctor("T.leaf", "T").ctor("T.node", "Foo T T.leaf → T")
                    .rec("T.rec", "u").rec("T.rec_1", "u").end();
        });
    }

    /**
     * Runs one scenario with admission on the reference kernel or on the {@link FakeKernel}; returns a
     * report line, or throws on a difference.
     */
    private static String run(Scenario s, boolean referenceKernel) throws Exception {
        Env e = new Env(referenceKernel);
        s.build.accept(e);
        e.freeze();
        Ndjson w = new Ndjson(e);
        int mine = ACCEPT;
        String why = "";
        Env.Block subject = e.blocks.get(e.blocks.size() - 1);
        int n = e.decls.unitCount();
        for (int u = 0; u < n; u++) {
            int first = e.decls.unitFirstCid(u);
            if (e.decls.unitKind(u) != DeclKind.INDUCT) {
                w.constant(first);
                continue;
            }
            Env.Block b = null;
            for (Env.Block x : e.blocks) {
                if (x.in.unit == u) {
                    b = x;
                }
            }
            GeneratedBlock g = null;
            try {
                g = e.admit(b);
            } catch (KernelException x) {
                mine = REJECT;
                why = x.reason().toString();
            } catch (ResourceException x) {
                mine = DECLINE;
                why = x.reason().toString();
            }
            if (g != null && g.declineReason != null) {
                mine = DECLINE; // complete derived records, but the block is not decidable here
                why = g.declineReason.toString();
            }
            if (g == null && b != subject) {
                fail(s.name + ": a block before the subject was not admitted: " + why);
            }
            w.inductive(b, g);
        }
        // quot records of the fixture
        if (mine == ACCEPT) {
            QuotAdmission qa = new QuotAdmission(e.store, e.ops);
            for (int u = 0; u < n; u++) {
                int k = e.decls.unitKind(u);
                if (k >= DeclKind.QUOT_TYPE && k <= DeclKind.QUOT_IND) {
                    qa.admit(e.decls.unitFirstCid(u));
                }
            }
        }
        OfficialGolden.Verdict v = OfficialGolden.official(s.name, w.text());
        if (v == null) {
            return s.name + ": no stored answer of the official checker";
        }
        String first = " | " + (v.output.length() > 160 ? v.output.substring(0, 160) : v.output);
        String line = String.format("%-62s here %-8s %-22s official exit %d%s", s.name,
                mine == ACCEPT ? "ACCEPT" : mine == REJECT ? "REJECT" : "DECLINE", why, v.exit, v.exit == 0 ? "" : first);
        if (mine != s.expect) {
            fail(s.name + ": expected outcome " + s.expect + " here, got " + mine + " " + why);
        }
        if (mine == ACCEPT && v.exit != 0) {
            fail(s.name + ": admitted here, REJECTED by the official checker:\n" + v.output + "\n" + w.text());
        }
        if (mine == REJECT) {
            String subjectName = e.names.toString(subject.in.typeName[0]);
            boolean named = false; // the replay names whichever type of the block it reached first
            for (int k = 0; k < subject.in.typeCount; k++) {
                named |= v.output.contains("while replaying declaration '" + e.names.toString(subject.in.typeName[k]) + "'");
            }
            boolean kernel = v.output.contains("(kernel)") && named;
            if (v.exit == 0 || !kernel) {
                fail(s.name + ": rejected here (" + why + "), but the official kernel did not reject block '" + subjectName
                        + "':\nexit " + v.exit + "\n" + v.output);
            }
        }
        return line;
    }

    @Test
    void fixturesAgainstTheOfficialChecker() throws Exception {
        ArrayList<String> report = new ArrayList<>();
        for (Scenario s : SCENARIOS) {
            // on the real engine (the reference kernel) and on the test's own small checker
            report.add(run(s, true));
            run(s, false);
        }
        Path dir = Path.of("target");
        if (Files.isDirectory(dir)) {
            Files.write(dir.resolve("official-differential-report.txt"), report);
        }
        OfficialGolden.failIfMissing();
        long accepted = SCENARIOS.stream().filter(s -> s.expect == ACCEPT).count();
        long rejected = SCENARIOS.stream().filter(s -> s.expect == REJECT).count();
        System.out.printf("official differential: %d fixtures admitted here and accepted officially, %d rejected by both, %d declined here%n",
                accepted, rejected, SCENARIOS.size() - accepted - rejected);
        for (String l : report) {
            if (l.contains("DECLINE")) {
                System.out.println("  " + l);
            }
        }
        assertTrue(report.size() == SCENARIOS.size());
    }
}
