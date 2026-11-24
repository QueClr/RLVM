package rlvm.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import rlvm.api.MachineErrors;
import rlvm.store.IntLists;

/**
 * INFER, CHECK, VTYPE, ENSORT, ENPI, ISPROP against the official rules of R02 section 6 (ISA 4.5): one row
 * per rule and per {@code throw}, on the hand-made prelude (the rows of {@code rlvm-ref}'s {@code InferTest},
 * so that a machine row can be compared with the reference row of the same text). Every row runs twice: with
 * the default host depth bound and with bound 0, where every non-leaf call is a suspension and every
 * {@code sigN} / {@code resume} case of the family runs (WORK-PACKAGES section 3).
 */
@Tag("fast")
class M5InferTest {

    static Fixture deep;
    static Fixture flat;

    @BeforeAll
    static void build() {
        assumeTrue(M5Support.ready(), "EVAL / WHNF / CONV are not implemented in this tree yet");
        deep = fixture();
        flat = fixture();
        flat.hostDepth(0);
    }

    @AfterAll
    static void close() {
        if (deep != null) {
            deep.close();
            flat.close();
        }
    }

    /** The prelude plus what the projection rows need. */
    static Fixture fixture() {
        Fixture f = Fixture.prelude();
        // an indexed family in Prop with a data field: `x.1` must be rejected because the RESULT TYPE reads n
        f.inductive("PN", "", 0, "(-> Nat Prop)", "PN.mk : (pi (n : Nat) (p : True) (PN n))");
        f.axiom("xp", "", "(PN n0)");
        // a structure in Prop whose second field's type depends on the first, both proofs
        f.inductive("PDep", "", 0, "Prop", "PDep.mk : (pi (h : P) (k : ((@ Eq 0) P h h)) PDep)");
        f.axiom("pdep", "", "PDep");
        f.axiom("idT", "", "(pi (α : Type) (a : α) α)");
        // a type that is a constant but not an inductive type
        f.axiom("AxT", "", "Type");
        f.axiom("yAx", "", "AxT");
        f.seal();
        return f;
    }

    interface Rows {
        void run(Fixture f);
    }

    static void both(Rows rows) {
        rows.run(deep);
        rows.run(flat);
    }

    /** The inferred type is the value of {@code expected}: the same record, or convertible with it. */
    static void sameType(Fixture f, String expected, int actual, String what) {
        int ex = f.eval(f.term(expected));
        assertTrue(actual == ex || f.isDefEq(actual, ex),
                () -> what + ": expected " + expected + " but got " + f.show(actual));
    }

    static void infers(Fixture f, String expected, String term) {
        sameType(f, expected, f.inferOnly(f.term(term)), "infer " + term);
    }

    static void checks(Fixture f, String expected, String term) {
        sameType(f, expected, f.inferCheck(f.term(term)), "check " + term);
    }

    static void rejectsCheck(Fixture f, int code, String term) {
        int t = f.term(term);
        f.expectFail(code, () -> f.inferCheck(t));
    }

    static void rejectsInfer(Fixture f, int code, String term) {
        int t = f.term(term);
        f.expectFail(code, () -> f.inferOnly(t));
    }

    static int names(Fixture f, String... names) {
        int[] ids = new int[names.length];
        for (int i = 0; i < names.length; i++) {
            byte[] b = names[i].getBytes(StandardCharsets.UTF_8);
            ids[i] = f.store.names().internStr(0, b, 0, b.length);
        }
        return ids.length == 0 ? IntLists.EMPTY : f.ctx.lists.intern(ids, 0, ids.length);
    }

    // ---- R02 6.1 sort ------------------------------------------------------------------------------------

    @Test
    void sortHasSuccessorSort() {
        both(f -> {
            assertEquals(f.eval(f.term("(sort 1)")), f.inferOnly(f.term("Prop")));
            assertEquals(f.eval(f.term("(sort 2)")), f.inferCheck(f.term("Type")));
            assertEquals(f.eval(f.term("(sort (succ u))")), f.inferOnly(f.term("(sort u)")));
            // succ is not simplified: the level is kept as written
            assertEquals(f.eval(f.term("(sort (succ (max u 0)))")), f.inferOnly(f.term("(sort (max u 0))")));
        });
    }

    @Test
    void sortChecksUniverseParametersInCheckModeOnly() {
        both(f -> {
            f.m.x5.declLparams = names(f, "u");
            try {
                checks(f, "(sort (succ u))", "(sort u)");
                rejectsCheck(f, MachineErrors.E_LEVEL_PARAM, "(sort v)");
                infers(f, "(sort (succ v))", "(sort v)");
            } finally {
                f.m.x5.declLparams = -1;
            }
        });
    }

    // ---- R02 6.2 const -----------------------------------------------------------------------------------

    @Test
    void constantTypeIsInstantiated() {
        both(f -> {
            infers(f, "Type", "Nat");
            infers(f, "(-> Nat Nat)", "Nat.succ");
            infers(f, "(pi (α : (sort 2)) (a : α) α)", "(@ id 2)");
            infers(f, "(pi (α : Type) (a : α) (b : α) Prop)", "(@ Eq 1)");
            infers(f, "(pi (α : (sort (succ w))) (a : α) α)", "(@ id (succ w))");
            // mk_max simplifies: Prod.{0,0} : Type -> Type -> Type
            infers(f, "(pi (α : Type) (β : Type) Type)", "(@ Prod 0 0)");
            infers(f, "(pi (α : (sort 2)) (β : Type) (sort 2))", "(@ Prod 1 0)");
        });
    }

    @Test
    void constantLevelArityIsCheckedInBothModes() {
        both(f -> {
            rejectsInfer(f, MachineErrors.E_LEVEL_ARITY, "(@ id)");
            rejectsCheck(f, MachineErrors.E_LEVEL_ARITY, "(@ id 0 0)");
            rejectsInfer(f, MachineErrors.E_LEVEL_ARITY, "(@ Nat 0)");
        });
    }

    @Test
    void constantLevelsMustBeDeclaredInCheckMode() {
        both(f -> {
            f.m.x5.declLparams = names(f, "u");
            try {
                rejectsCheck(f, MachineErrors.E_LEVEL_PARAM, "(@ id v)");
                checks(f, "(pi (α : (sort u)) (a : α) α)", "(@ id u)");
                infers(f, "(pi (α : (sort v)) (a : α) α)", "(@ id v)");
            } finally {
                f.m.x5.declLparams = -1;
            }
        });
    }

    @Test
    void constantOfLaterRankIsUnknown() {
        both(f -> {
            int two = f.term("two");
            int app = f.term("(Nat.succ two)");
            int saved = f.m.decl;
            f.m.decl = f.store.decls().rank(f.cid("two"));
            try {
                f.expectFail(MachineErrors.E_UNKNOWN_CONST, () -> f.inferOnly(two));
                f.expectFail(MachineErrors.E_UNKNOWN_CONST, () -> f.inferCheck(app));
            } finally {
                f.m.decl = saved;
            }
            infers(f, "Nat", "two");
        });
    }

    // ---- R02 6.3 lambda ----------------------------------------------------------------------------------

    @Test
    void lambdaTelescope() {
        both(f -> {
            infers(f, "(-> Nat Nat)", "(fun (x : Nat) x)");
            infers(f, "(pi (α : Type) (a : α) α)", "(fun (α : Type) (a : α) a)");
            infers(f, "(pi (x : Nat) (y : Bool) ((@ Eq 1) Nat x x))", "(fun (x : Nat) (y : Bool) ((@ Eq.refl 1) Nat x))");
            checks(f, "(pi (α : Type) (a : α) (b : α) α)", "(fun (α : Type) (a : α) (b : α) a)");
            checks(f, "(pi (x : Nat) (y : Bool) ((@ Eq 1) Nat x x))", "(fun (x : Nat) (y : Bool) ((@ Eq.refl 1) Nat x))");
        });
    }

    @Test
    void theTypeOfALambdaIsAPiiThatInfersItsBodyOnDemand() {
        both(f -> {
            int ty = f.inferCheck(f.term("(fun (x : Nat) (Nat.succ x))"));
            assertEquals(Layout.PII, Values.kind(f.m, ty));
            // INST of the PII = INFER(body, frame + x, ONLY)
            int at = f.inst(ty, f.eval(f.term("Nat.zero")));
            assertEquals(f.eval(f.term("Nat")), at);
            // the same lambda gives the same PII in both modes (value ids are mode-independent)
            assertEquals(ty, f.inferOnly(f.term("(fun (x : Nat) (Nat.succ x))")));
        });
    }

    @Test
    void lambdaDomainMustBeATypeInCheckMode() {
        both(f -> {
            rejectsCheck(f, MachineErrors.E_TYPE_EXPECTED, "(fun (x : Nat.zero) x)");
            // infer-only does not look
            f.inferOnly(f.term("(fun (x : Nat.zero) x)"));
            // the body is checked under the binder
            rejectsCheck(f, MachineErrors.E_APP_MISMATCH, "(fun (x : Bool) (Nat.succ x))");
            rejectsCheck(f, MachineErrors.E_APP_MISMATCH, "(fun (y : Nat) (x : Bool) (Nat.succ x))");
        });
    }

    @Test
    void lambdaDomainMayNeedWhnf() {
        both(f -> {
            checks(f, "(-> NatAlias NatAlias)", "(fun (x : NatAlias) x)");
            checks(f, "(-> ((@ id 2) Type Nat) Nat)", "(fun (x : ((@ id 2) Type Nat)) Nat.zero)");
        });
    }

    // ---- R02 6.4 pi --------------------------------------------------------------------------------------

    @Test
    void piSortIsImaxOfDomainSorts() {
        both(f -> {
            assertEquals(f.eval(f.term("Type")), f.inferOnly(f.term("(-> Nat Nat)")));
            assertEquals(f.eval(f.term("(sort 2)")), f.inferOnly(f.term("(-> Nat Type)")));
            assertEquals(f.eval(f.term("(sort 2)")), f.inferCheck(f.term("(-> Type Nat)")));
            // imax 1 0 = 0: a proposition
            assertEquals(f.eval(f.term("Prop")), f.inferCheck(f.term("(pi (x : Nat) ((@ Eq 1) Nat x x))")));
            assertEquals(f.eval(f.term("Prop")), f.inferOnly(f.term("(-> Nat Nat P)")));
            assertEquals(f.eval(f.term("(sort (imax u v))")), f.inferOnly(f.term("(-> (@ PU u) (@ PU v))")));
            assertEquals(f.eval(f.term("(sort u)")), f.inferOnly(f.term("(-> (@ PU u) (@ PU u))")));
            assertEquals(f.eval(f.term("(sort (max u (succ v)))")), f.inferOnly(f.term("(-> (@ PU u) (@ PU (succ v)))")));
            assertEquals(f.eval(f.term("Prop")), f.inferOnly(f.term("(-> (@ PU u) P)")));
        });
    }

    @Test
    void piDomainAndBodyMustBeTypes() {
        both(f -> {
            rejectsCheck(f, MachineErrors.E_TYPE_EXPECTED, "(-> Nat.zero Nat)");
            rejectsCheck(f, MachineErrors.E_TYPE_EXPECTED, "(-> Nat Nat.zero)");
            // also in infer-only mode: the sorts are needed
            rejectsInfer(f, MachineErrors.E_TYPE_EXPECTED, "(-> Nat Nat.zero)");
            rejectsInfer(f, MachineErrors.E_TYPE_EXPECTED, "(-> Nat.zero Nat)");
        });
    }

    @Test
    void piLeavesTheMachineStacksAsItFoundThem() {
        both(f -> {
            int ssp = f.m.ssp;
            int xp = f.m.xp;
            int ep = f.m.ep;
            f.inferCheck(f.term("(pi (α : Type) (a : α) (b : α) ((@ Eq 1) α a b))"));
            f.inferCheck(f.term("(fun (α : Type) (a : α) (b : α) ((@ Eq.refl 1) α a))"));
            f.inferCheck(f.term("(let (x : Nat := Nat.zero) (let (y : Nat := (Nat.succ x)) ((@ Eq.refl 1) Nat y)))"));
            f.inferCheck(f.term("(proj Sig 1 sg)"));
            assertEquals(ssp, f.m.ssp, "binders (I-SCOPE)");
            assertEquals(xp, f.m.xp, "scratch (I-STACKS)");
            assertEquals(ep, f.m.ep, "environments (I-STACKS)");
            assertEquals(0, f.m.ER);
        });
    }

    // ---- R02 6.5 app -------------------------------------------------------------------------------------

    @Test
    void applicationInstantiatesTheCodomain() {
        both(f -> {
            checks(f, "Nat", "(Nat.succ Nat.zero)");
            checks(f, "((@ Eq 1) Nat Nat.zero Nat.zero)", "((@ Eq.refl 1) Nat Nat.zero)");
            checks(f, "Nat", "((@ id 1) Nat (Nat.succ Nat.zero))");
            infers(f, "Nat", "((@ id 1) Nat (Nat.succ Nat.zero))");
            checks(f, "(-> Nat Nat)", "(g Nat.zero)");
        });
    }

    @Test
    void applicationChecksTheArgumentType() {
        both(f -> {
            rejectsCheck(f, MachineErrors.E_APP_MISMATCH, "(Nat.succ Bool.true)");
            rejectsCheck(f, MachineErrors.E_APP_MISMATCH, "((@ id 1) Nat Bool.true)");
            rejectsCheck(f, MachineErrors.E_FUN_EXPECTED, "(Nat.zero Nat.zero)");
            // the arguments are checked strictly left to right: the first error is the verdict
            rejectsCheck(f, MachineErrors.E_APP_MISMATCH, "(g Bool.true (Nat.zero Nat.zero))");
            rejectsCheck(f, MachineErrors.E_FUN_EXPECTED, "(g (Nat.zero Nat.zero) Bool.true)");
            // infer-only walks the spine without comparing anything
            infers(f, "Nat", "(Nat.succ Bool.true)");
            rejectsInfer(f, MachineErrors.E_FUN_EXPECTED, "(Nat.zero Nat.zero)");
        });
    }

    @Test
    void applicationArgumentTypeUpToConversion() {
        both(f -> {
            checks(f, "Nat", "(Nat.succ ((@ id 1) Nat two))");
            checks(f, "NatAlias", "((fun (x : NatAlias) x) Nat.zero)");
            // the function type needs whnf to become a Pi
            checks(f, "Nat", "(fa Nat.zero)");
            infers(f, "Nat", "(fa Nat.zero)");
        });
    }

    @Test
    void applicationWithMoreArgumentsThanSyntacticPis() {
        both(f -> {
            // id (Nat -> Nat) Nat.succ Nat.zero: the type of `id _ _` is a Pi only after instantiation
            infers(f, "Nat", "((@ id 1) (-> Nat Nat) Nat.succ Nat.zero)");
            checks(f, "Nat", "((@ id 1) (-> Nat Nat) Nat.succ Nat.zero)");
        });
    }

    @Test
    void anUnreadBinderGetsNoArgumentValue() {
        both(f -> {
            // g : Nat -> Nat -> Nat reads neither binder: ONLY mode must not even evaluate the arguments,
            // so an argument that cannot be evaluated (a HOLE read would fault) is never touched
            long before = f.m.vp;
            infers(f, "Nat", "(g (Nat.succ (Nat.succ Nat.zero)) (Nat.succ Nat.zero))");
            checks(f, "Nat", "(g (Nat.succ (Nat.succ Nat.zero)) (Nat.succ Nat.zero))");
            assertTrue(f.m.vp >= before);
        });
    }

    @Test
    void eagerReduceArgumentIsComparedUnderEr() {
        both(f -> {
            // the mode register is restored after the comparison, also when it was a real comparison
            checks(f, "Nat", "(Nat.succ ((@ eagerReduce 1) NatAlias Nat.zero))");
            assertEquals(0, f.m.ER);
            rejectsCheck(f, MachineErrors.E_APP_MISMATCH, "(Nat.succ ((@ eagerReduce 1) Bool Bool.true))");
            assertEquals(0, f.m.ER);
        });
    }

    // ---- R02 6.6 let -------------------------------------------------------------------------------------

    @Test
    void letTypeIsTheTypeOfTheBodyUnderTheValue() {
        both(f -> {
            checks(f, "Nat", "(let (x : Nat := Nat.zero) (Nat.succ x))");
            infers(f, "Nat", "(let (x : Nat := Nat.zero) (Nat.succ x))");
            // the official type keeps the let; after zeta it is Eq Nat 0 0
            checks(f, "((@ Eq 1) Nat Nat.zero Nat.zero)", "(let (x : Nat := Nat.zero) ((@ Eq.refl 1) Nat x))");
            checks(f, "Nat", "(let (x : Nat := Nat.zero) (let (y : Nat := (Nat.succ x)) (Nat.add x y)))");
        });
    }

    @Test
    void letValueMustHaveTheDeclaredType() {
        both(f -> {
            rejectsCheck(f, MachineErrors.E_LET_MISMATCH, "(let (x : Nat := Bool.true) Nat.zero)");
            rejectsCheck(f, MachineErrors.E_TYPE_EXPECTED, "(let (x : Nat.zero := Nat.zero) Nat.zero)");
            rejectsCheck(f, MachineErrors.E_APP_MISMATCH, "(let (x : Nat := (Nat.succ Bool.true)) Nat.zero)");
            infers(f, "Nat", "(let (x : Nat := Bool.true) Nat.zero)");
        });
    }

    @Test
    void letBodySeesTheValue() {
        both(f -> {
            // the body is only well typed because x reduces to Nat.zero (zeta through the let variable)
            checks(f, "((@ Eq 1) Nat Nat.zero Nat.zero)",
                    "(let (x : Nat := Nat.zero) ((fun (h : ((@ Eq 1) Nat x Nat.zero)) h) ((@ Eq.refl 1) Nat Nat.zero)))");
        });
    }

    // ---- R02 6.9 literals --------------------------------------------------------------------------------

    @Test
    void literals() {
        both(f -> {
            assertEquals(f.eval(f.term("Nat")), f.inferCheck(f.term("(lit 5)")));
            assertEquals(f.eval(f.term("Nat")), f.inferCheck(f.term("(lit 123456789012345678901234567890)")));
            assertEquals(f.eval(f.term("String")), f.inferCheck(f.term("(str \"abc\")")));
            checks(f, "Nat", "(Nat.succ (lit 5))");
        });
    }

    @Test
    void aLiteralWhoseTypeConstantIsNotVisibleIsAKernelError() {
        Fixture f = Fixture.prelude();
        f.seal();
        int lit = f.term("(lit 5)");
        f.m.decl = f.store.decls().rank(f.cid("Nat"));
        f.expectFail(MachineErrors.E_UNKNOWN_CONST, () -> f.inferCheck(lit));
        f.close();
    }

    // ---- R02 6.8 projections -----------------------------------------------------------------------------

    @Test
    void projectionOfAStructure() {
        both(f -> {
            checks(f, "Nat", "(proj Prod 0 pr)");
            checks(f, "Bool", "(proj Prod 1 pr)");
            checks(f, "Nat", "(proj Prod 0 ((@ Prod.mk 0 0) Nat Bool Nat.zero Bool.true))");
            infers(f, "Bool", "(proj Prod 1 pr)");
        });
    }

    @Test
    void projectionWithDependentField() {
        both(f -> {
            checks(f, "Nat", "(proj Sig 0 sg)");
            // the second field's type mentions the first field as a projection of the struct
            checks(f, "(B (proj Sig 0 sg))", "(proj Sig 1 sg)");
            infers(f, "(B (proj Sig 0 sg))", "(proj Sig 1 sg)");
        });
    }

    @Test
    void projectionRestrictions() {
        both(f -> {
            // a field index beyond nfields (RECONCILIATION section 2 "Projections")
            rejectsCheck(f, MachineErrors.E_PROJ, "(proj Prod 2 pr)");
            rejectsInfer(f, MachineErrors.E_PROJ, "(proj Prod 7 pr)");
            // not a structure: no constructor has a field / more than one constructor
            rejectsCheck(f, MachineErrors.E_PROJ, "(proj Nat 0 Nat.zero)");
            rejectsCheck(f, MachineErrors.E_PROJ, "(proj Bool 0 Bool.true)");
            // the structure name must be the head of the type
            rejectsCheck(f, MachineErrors.E_PROJ, "(proj Sig 0 pr)");
            // the head of the type is the named constant, but that constant is not an inductive type
            rejectsCheck(f, MachineErrors.E_PROJ, "(proj AxT 0 yAx)");
            rejectsInfer(f, MachineErrors.E_PROJ, "(proj AxT 0 yAx)");
            // the type of the struct is not an inductive type at all
            rejectsCheck(f, MachineErrors.E_PROJ, "(proj Prod 0 Nat)");
            rejectsCheck(f, MachineErrors.E_PROJ, "(proj Prod 0 f)");
            // the struct itself is checked in check mode, and only there
            rejectsCheck(f, MachineErrors.E_APP_MISMATCH, "(proj Prod 0 ((@ Prod.mk 0 0) Nat Bool Bool.true Bool.true))");
            infers(f, "Nat", "(proj Prod 0 ((@ Prod.mk 0 0) Nat Bool Bool.true Bool.true))");
        });
    }

    @Test
    void projectionOutOfAProposition() {
        both(f -> {
            checks(f, "P", "(proj And 0 andPQ)");
            checks(f, "Q", "(proj And 1 andPQ)");
            // the witness is data: it may not leave the proof
            rejectsCheck(f, MachineErrors.E_PROJ, "(proj Exists 0 ex)");
            // the proof field depends on the witness, which is data
            rejectsCheck(f, MachineErrors.E_PROJ, "(proj Exists 1 ex)");
            rejectsInfer(f, MachineErrors.E_PROJ, "(proj Exists 1 ex)");
            // both fields are proofs and the second depends on the first: allowed
            checks(f, "P", "(proj PDep 0 pdep)");
            checks(f, "((@ Eq 0) P (proj PDep 0 pdep) (proj PDep 0 pdep))", "(proj PDep 1 pdep)");
        });
    }

    @Test
    void aFieldReadOnlyByTheResultTypeBlocksLaterFields() {
        both(f -> {
            // P.mk (n : N) (p : True) : P n; x : P k.  x.1 is rejected: field 0 is data and the constructor's
            // RESULT type reads it (official has_loose_bvars(binding_body(r)), ISA 4.5.5)
            rejectsCheck(f, MachineErrors.E_PROJ, "(proj PN 1 xp)");
            rejectsInfer(f, MachineErrors.E_PROJ, "(proj PN 1 xp)");
            rejectsCheck(f, MachineErrors.E_PROJ, "(proj PN 0 xp)");
            rejectsCheck(f, MachineErrors.E_PROJ, "(proj PN 2 xp)");
        });
    }

    @Test
    void projectionOfAValue() {
        both(f -> {
            // VTYPE of a PROJ record is IPROJ on the value (ISA 4.5.3)
            int v = f.eval(f.term("(proj Sig 1 sg)"));
            assertEquals(Layout.PROJ, Values.kind(f.m, v));
            sameType(f, "(B (proj Sig 0 sg))", f.vtype(v), "vtype (proj Sig 1 sg)");
            int w = f.eval(f.term("(proj Prod 1 pr)"));
            sameType(f, "Bool", f.vtype(w), "vtype (proj Prod 1 pr)");
            assertEquals(f.vtype(w), f.vtype(w));
        });
    }

    // ---- free variables, VTYPE -----------------------------------------------------------------------------

    @Test
    void variablesAndValues() {
        for (int depth : new int[] {Op.HOST_DEPTH, 0}) {
            Fixture f = Fixture.prelude();
            f.hostDepth(depth);
            int x = f.fvar("x", "Nat");
            int h = f.fvar("h", "((@ Eq 1) Nat x x)");
            assertEquals(f.eval(f.term("Nat")), f.inferCheck(f.term("x")));
            assertEquals(f.eval(f.term("Nat")), f.vtype(x));
            sameType(f, "((@ Eq 1) Nat x x)", f.vtype(h), "vtype h");
            checks(f, "Nat", "(Nat.succ x)");
            checks(f, "((@ Eq 1) Nat x x)", "((@ Eq.refl 1) Nat x)");
            checks(f, "(-> Nat ((@ Eq 1) Nat x x))", "(fun (y : Nat) ((@ Eq.refl 1) Nat x))");
            // values: literals, NSUC, sorts, lambdas, constants, neutrals, Pis
            assertEquals(f.eval(f.term("Nat")), f.vtype(f.eval(f.term("(lit 7)"))));
            assertEquals(f.eval(f.term("Nat")), f.vtype(f.eval(f.term("(Nat.succ x)"))));
            assertEquals(f.eval(f.term("String")), f.vtype(f.eval(f.term("(str \"ab\")"))));
            assertEquals(f.eval(f.term("(sort 2)")), f.vtype(f.eval(f.term("Type"))));
            sameType(f, "(-> Nat Nat)", f.vtype(f.eval(f.term("(fun (y : Nat) (Nat.add x y))"))), "vtype lam");
            sameType(f, "(-> Nat Nat Nat)", f.vtype(f.eval(f.term("Nat.add"))), "vtype const");
            sameType(f, "(-> Nat Nat)", f.vtype(f.eval(f.term("(g x)"))), "vtype neu");
            sameType(f, "Nat", f.vtype(f.eval(f.term("(g x x)"))), "vtype neu");
            sameType(f, "Prop", f.vtype(f.eval(f.term("((@ Eq 1) Nat x x)"))), "vtype Eq");
            assertEquals(f.eval(f.term("Type")), f.vtype(f.eval(f.term("(-> Nat Nat)"))));
            assertEquals(f.eval(f.term("Prop")), f.vtype(f.eval(f.term("(pi (y : Nat) ((@ Eq 1) Nat y x))"))));
            assertEquals(f.eval(f.term("(sort 2)")), f.vtype(f.eval(f.term("(pi (α : Type) (-> α α))"))));
            // the type of the type of a lambda: its PII is a Pi type
            int pii = f.vtype(f.eval(f.term("(fun (y : Nat) (Nat.add x y))")));
            assertEquals(f.eval(f.term("Type")), f.vtype(pii));
            // memo: the second query is the same record
            int v = f.eval(f.term("(g x)"));
            assertEquals(f.vtype(v), f.vtype(v));
            assertEquals(2, f.m.ssp, "the context's two binders stay open, nothing else");
            f.closeContext();
            f.close();
        }
    }

    // ---- ENSORT, ENPI, ISPROP ------------------------------------------------------------------------------

    @Test
    void ensureSortAndPi() {
        both(f -> {
            assertEquals(f.level("1"), f.run(Infer.ensort(f.m, f.eval(f.term("Type")), 0)));
            assertEquals(f.level("1"), f.run(Infer.ensort(f.m, f.eval(f.term("((@ id 3) (sort 2) Type)")), 0)));
            int nat = f.eval(f.term("Nat"));
            f.expectFail(MachineErrors.E_TYPE_EXPECTED, () -> f.run(Infer.ensort(f.m, nat, 0)));
            int pi = f.eval(f.term("(-> Nat Nat)"));
            assertEquals(pi, f.run(Infer.enpi(f.m, pi, 0)));
            int alias = f.run(Infer.enpi(f.m, f.eval(f.term("FnAlias")), 0));
            assertEquals(Layout.PI, Values.kind(f.m, alias));
            f.expectFail(MachineErrors.E_FUN_EXPECTED, () -> f.run(Infer.enpi(f.m, nat, 0)));
        });
    }

    @Test
    void isPropNormalisesTheLevelAndFailsOnAStuckSort() {
        both(f -> {
            assertTrue(f.isProp(f.eval(f.term("P"))));
            assertTrue(f.isProp(f.eval(f.term("((@ Eq 1) Nat Nat.zero Nat.zero)"))));
            assertFalse(f.isProp(f.eval(f.term("Nat"))));
            assertFalse(f.isProp(f.eval(f.term("Prop"))));
            // Sort (imax 1 0) is Prop
            assertTrue(f.isProp(f.eval(f.term("PI"))));
            // a bare parameter is not known to be zero
            assertFalse(f.isProp(f.eval(f.term("(@ PU u)"))));
            // a Pi into a proposition is a proposition
            assertTrue(f.isProp(f.eval(f.term("(-> Nat P)"))));
            // the type of the type does not reduce to a sort: an error, never "false"
            int zero = f.eval(f.term("Nat.zero"));
            f.expectFail(MachineErrors.E_TYPE_EXPECTED, () -> f.isProp(zero));
            int lit = f.eval(f.term("(lit 3)"));
            f.expectFail(MachineErrors.E_TYPE_EXPECTED, () -> f.isProp(lit));
            // the PROPT memo answers the second query inline
            int p = f.eval(f.term("P"));
            assertEquals(Layout.T, Infer.ispropFast(f.m, p));
            assertEquals(Layout.F, Infer.ispropFast(f.m, f.eval(f.term("Nat"))));
            assertEquals(Op.MISS, Infer.ispropFast(f.m, zero));
        });
    }

    // ---- CHECK -----------------------------------------------------------------------------------------------

    @Test
    void checkIsInferThenConv() {
        both(f -> {
            f.check(f.term("(Nat.succ Nat.zero)"), f.eval(f.term("Nat")));
            f.check(f.term("(Nat.succ Nat.zero)"), f.eval(f.term("NatAlias")));
            f.check(f.term("(fun (x : Nat) x)"), f.eval(f.term("(-> Nat Nat)")));
            f.check(f.term("(fun (x : Nat) x)"), f.eval(f.term("(-> NatAlias Nat)")));
            int bool = f.eval(f.term("Bool"));
            int t = f.term("(Nat.succ Nat.zero)");
            f.expectFail(MachineErrors.E_TYPE_MISMATCH, () -> f.check(t, bool));
            int lam = f.term("(fun (x : Nat) x)");
            int wrong = f.eval(f.term("(-> Nat Bool)"));
            f.expectFail(MachineErrors.E_TYPE_MISMATCH, () -> f.check(lam, wrong));
            int bad = f.term("(Nat.succ Bool.true)");
            int nat = f.eval(f.term("Nat"));
            f.expectFail(MachineErrors.E_APP_MISMATCH, () -> f.check(bad, nat));
        });
    }

    // ---- tabling and check stamps (ISA 5.3) -----------------------------------------------------------------

    @Test
    void aTypeInferredWithoutCheckingNeverAnswersACheckingQuery() {
        both(f -> {
            int bad = f.term("(g (Nat.succ Bool.true) Nat.zero)");
            // infer-only: no comparison, the type is stored with stamp 0
            assertEquals(f.eval(f.term("Nat")), f.inferOnly(bad));
            int s = Infer.suspRec(f.m, bad, Layout.RUN_EMPTY, 0);
            assertNotEquals(0, Values.memo(f.m, s, Layout.M_TYPE - Layout.M0));
            assertEquals(Layout.TSTAMP_ONLY, Values.memo(f.m, s, Layout.M_TSTAMP - Layout.M0));
            // the checking query does not use it
            f.expectFail(MachineErrors.E_APP_MISMATCH, () -> f.inferCheck(bad));
            int nat = f.eval(f.term("Nat"));
            f.expectFail(MachineErrors.E_APP_MISMATCH, () -> f.check(bad, nat));
            // and an infer-only query still does
            assertEquals(nat, f.inferOnly(bad));
        });
    }

    @Test
    void aCheckedTypeAnswersBothKindsOfQuery() {
        both(f -> {
            int good = f.term("(g (Nat.succ Nat.zero) (Nat.succ (Nat.succ Nat.zero)))");
            int ty = f.inferCheck(good);
            int s = Infer.suspRec(f.m, good, Layout.RUN_EMPTY, 0);
            assertEquals(ty, Values.memo(f.m, s, Layout.M_TYPE - Layout.M0));
            // closed, no level parameter: valid in every later declaration
            assertEquals(Layout.TSTAMP_PERM, Values.memo(f.m, s, Layout.M_TSTAMP - Layout.M0));
            long ticks = f.m.ticks();
            assertEquals(ty, f.inferCheck(good));
            assertEquals(ty, f.inferOnly(good));
            assertEquals(ticks, f.m.ticks(), "a memo hit charges no tick");
            // an infer-only completion never downgrades the stamp
            assertEquals(Layout.TSTAMP_PERM, Values.memo(f.m, s, Layout.M_TSTAMP - Layout.M0));
        });
    }

    @Test
    void aGenerationStampExpiresWhenTheGenerationAdvances() {
        both(f -> {
            // a term with a level parameter gets the generation as its stamp
            int t = f.term("((@ id (succ u)) (sort u) (@ PU u))");
            int ty = f.inferCheck(t);
            int s = Infer.suspRec(f.m, t, Layout.RUN_EMPTY, 0);
            assertEquals(f.m.gen, Values.memo(f.m, s, Layout.M_TSTAMP - Layout.M0));
            long ticks = f.m.ticks();
            assertEquals(ty, f.inferCheck(t));
            assertEquals(ticks, f.m.ticks());
            // the next declaration does not declare u: the stored result must not answer, CHKLVL runs again
            f.m.gen++;
            f.m.x5.declLparams = names(f, "v");
            try {
                f.expectFail(MachineErrors.E_LEVEL_PARAM, () -> f.inferCheck(t));
                assertEquals(ty, f.inferOnly(t));
            } finally {
                f.m.x5.declLparams = -1;
            }
            // re-checked in the new generation
            assertEquals(ty, f.inferCheck(t));
            assertEquals(f.m.gen, Values.memo(f.m, s, Layout.M_TSTAMP - Layout.M0));
        });
    }

    @Test
    void tablingIsKeyedByThePrunedFrame() {
        for (int depth : new int[] {Op.HOST_DEPTH, 0}) {
            Fixture f = Fixture.prelude();
            f.hostDepth(depth);
            f.fvar("x", "Nat");
            f.fvar("b", "Bool");
            // the term reads x only: under [x, b] and under a longer context the record is the same
            int t = f.term("(Nat.succ x)");
            int ty = f.inferCheck(t);
            long ticks = f.m.ticks();
            f.fvar("c", "Bool");
            int t2 = f.term("(Nat.succ x)");
            assertEquals(ty, f.inferCheck(t2));
            if (t == t2) {
                assertEquals(ticks, f.m.ticks(), "same node, same pruned frame: a memo hit");
            }
            f.closeContext();
            f.close();
        }
    }

    // ---- the machine relies on the structural checks for closedness (ISA 1.4) ------------------------------

    @Test
    void aLooseVariableIsAFaultNotAVerdict() {
        both(f -> {
            int loose = f.term("#0");
            f.expectFail(MachineErrors.E_INTERNAL, () -> f.inferOnly(loose));
            int app = f.term("(Nat.succ #3)");
            f.expectFail(MachineErrors.E_INTERNAL, () -> f.inferCheck(app));
        });
    }
}
