package rlvm.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import rlvm.api.Budget;
import rlvm.api.Outcome;

/**
 * Static facts (ISA 3.5; staging step 2, switch {@code facts}): the record of a constant as derived by SIGD
 * on the hand-made prelude, the manifest rule, the accessors, publication (one record per constant, once),
 * and that nothing of it exists while the switch is off or in strict mode.
 */
@Tag("fast")
class M5FactsTest {

    @BeforeAll
    static void ready() {
        assumeTrue(M5Support.ready(), "EVAL / WHNF / CONV are not implemented in this tree yet");
    }

    static Fixture fixture() {
        Fixture f = Fixture.prelude();
        // W : id (Sort 2) Type, a type whose own type is a sort only after reduction: NOT manifest
        f.def("W", "", "((@ id 3) (sort 2) Type)", "Nat", Fixture.regular(1));
        f.axiom("useW", "", "(pi (x : W) (y : Nat) Nat)");
        // a family of sorts: the sort of the second domain depends on the first argument
        f.axiom("Fam", "", "(-> Nat Type)");
        f.axiom("useFam", "", "(pi (n : Nat) (x : (Fam n)) (h : ((@ Eq 1) (Fam n) x x)) Nat)");
        f.def("konst", "u v", "(pi (α : (sort u)) (β : (sort v)) (a : α) (b : β) α)",
                "(fun (α : (sort u)) (β : (sort v)) (a : α) (b : β) a)", Fixture.regular(1));
        f.seal();
        f.switches("facts");
        return f;
    }

    static int sigd(Fixture f, String name) {
        return f.run(Facts.sigd(f.m, f.cid(name), 0));
    }

    @Test
    void theRecordOfEqAndItsConstructor() {
        for (int depth : new int[] {Op.HOST_DEPTH, 0}) {
            Fixture f = fixture();
            f.hostDepth(depth);
            // Eq.{u} : (α : Sort u) (a : α) (b : α) : Prop
            int eq = sigd(f, "Eq");
            assertNotEquals(0, eq);
            assertTrue(Facts.usable(f.m, eq));
            assertEquals(3, Facts.arity(f.m, eq));
            assertEquals(0, Facts.lamArity(f.m, eq));
            assertEquals(0b001L, Facts.reads(f.m, eq), "only α is read (by the domains of a and b)");
            assertEquals(0b111L, Facts.manifest(f.m, eq));
            assertEquals(0L, Facts.dep(f.m, eq, 0));
            assertEquals(0b1L, Facts.dep(f.m, eq, 1));
            assertEquals(0b1L, Facts.dep(f.m, eq, 2));
            assertEquals(f.level("(succ u)"), Facts.slvl(f.m, eq, 0));
            assertEquals(f.level("u"), Facts.slvl(f.m, eq, 1));
            assertEquals(f.level("u"), Facts.slvl(f.m, eq, 2));
            assertEquals(f.level("1"), Facts.rlvl(f.m, eq));
            // Eq.refl.{u} : (α : Sort u) (a : α) : Eq α a a -- the result's type is the SORT record Prop
            int refl = sigd(f, "Eq.refl");
            assertEquals(2, Facts.arity(f.m, refl));
            assertEquals(0b11L, Facts.reads(f.m, refl), "the result reads both");
            assertEquals(f.level("0"), Facts.rlvl(f.m, refl));
            // static PROOF: a saturated Eq.refl is a proof, a saturated Eq is not, a partial one is unknown
            assertEquals(Layout.TRI_YES, Facts.staticProof(f.m, f.eval(f.term("((@ Eq.refl 1) Nat Nat.zero)"))));
            assertEquals(Layout.TRI_NO, Facts.staticProof(f.m, f.eval(f.term("((@ Eq 1) Nat Nat.zero Nat.zero)"))));
            assertEquals(Layout.TRI_UNKNOWN, Facts.staticProof(f.m, f.eval(f.term("((@ Eq 1) Nat Nat.zero)"))));
            assertEquals(Layout.TRI_NO, Facts.staticProof(f.m, f.eval(f.term("Type"))));
            assertEquals(Layout.TRI_NO, Facts.staticProof(f.m, f.eval(f.term("(lit 5)"))));
            assertEquals(Layout.TRI_NO, Facts.staticProof(f.m, f.eval(f.term("(-> Nat Nat)"))));
            // prop mask of Eq at level 1: α, a, b are not proofs; at level 0 (α : Prop) a and b are
            assertEquals(0L, Facts.propMask(f.m, f.eval(f.term("(@ Eq 1)"))));
            assertEquals(0b110L, Facts.propMask(f.m, f.eval(f.term("(@ Eq 0)"))));
            assertTrue(f.m.idle(), "SIGD leaves no binder, scratch or environment behind");
            f.close();
        }
    }

    @Test
    void propositionalFieldsOfConstructors() {
        Fixture f = fixture();
        // And.intro : (a b : Prop) (left : a) (right : b) : And a b
        int and = sigd(f, "And.intro");
        assertEquals(4, Facts.arity(f.m, and));
        assertEquals(0b1111L, Facts.manifest(f.m, and));
        assertEquals(0b0011L, Facts.reads(f.m, and));
        assertEquals(0b1100L, Facts.propMask(f.m, f.eval(f.term("And.intro"))));
        assertEquals(Layout.TRI_YES, Facts.staticProof(f.m, f.eval(f.term("(And.intro P Q p1 q1)"))));
        // Exists.intro.{u} : (α : Sort u) (p : α -> Prop) (w : α) (h : p w): the witness is a proof only at u = 0
        int ex = sigd(f, "Exists.intro");
        assertEquals(0b1111L, Facts.manifest(f.m, ex));
        assertEquals(0b1000L, Facts.propMask(f.m, f.eval(f.term("(@ Exists.intro 1)"))));
        assertEquals(0b1100L, Facts.propMask(f.m, f.eval(f.term("(@ Exists.intro 0)"))));
        assertEquals(0b0110L, Facts.dep(f.m, ex, 3), "the domain of h reads p and w");
        // Prod.mk.{u,v}: no proof positions at any level
        sigd(f, "Prod.mk");
        assertEquals(0L, Facts.propMask(f.m, f.eval(f.term("(@ Prod.mk 0 0)"))));
        // without a record: no position is skipped, and nothing is cached
        assertEquals(0L, Facts.propMask(f.m, f.eval(f.term("Or.inl"))));
        sigd(f, "Or.inl");
        assertEquals(0b100L, Facts.propMask(f.m, f.eval(f.term("Or.inl"))));
        f.close();
    }

    @Test
    void aSortThatAppearsOnlyAfterReductionIsNotManifest() {
        Fixture f = fixture();
        // useW : (x : W) (y : Nat) : Nat, W : id (Sort 2) Type. INFER(W) is `id (Sort 2) Type`, not a SORT record.
        int idx = sigd(f, "useW");
        assertTrue(Facts.usable(f.m, idx));
        assertEquals(2, Facts.arity(f.m, idx));
        assertEquals(0b10L, Facts.manifest(f.m, idx), "position 0 has no recorded sort; position 1 (Nat : Type) has");
        assertEquals(0, Facts.slvl(f.m, idx, 0));
        assertEquals(f.level("1"), Facts.slvl(f.m, idx, 1));
        assertEquals(f.level("1"), Facts.rlvl(f.m, idx));
        // an unknown sort is never a proof position
        assertEquals(0L, Facts.propMask(f.m, f.eval(f.term("useW"))));
        // a sort that depends on an earlier binder but is a SORT record as returned is manifest
        int fam = sigd(f, "useFam");
        assertEquals(0b111L, Facts.manifest(f.m, fam));
        assertEquals(0b100L, Facts.propMask(f.m, f.eval(f.term("useFam"))));
        assertEquals(0b011L, Facts.reads(f.m, fam));
        assertEquals(0b011L, Facts.dep(f.m, fam, 2));
        f.close();
    }

    @Test
    void valuePart() {
        Fixture f = fixture();
        // id.{u} = fun α a => a: the body is parameter 1 and does not read α
        int id = sigd(f, "id");
        assertEquals(2, Facts.arity(f.m, id));
        assertEquals(2, Facts.lamArity(f.m, id));
        assertEquals(Layout.SHAPE_PICK, Facts.shape(f.m, id));
        assertEquals(1, Facts.shapeOperand(f.m, id));
        assertEquals(0b01L, Facts.unreadMask(f.m, f.cid("id"), 2));
        assertEquals(0b01L, Facts.unreadMask(f.m, f.cid("id"), 5));
        assertEquals(0L, Facts.unreadMask(f.m, f.cid("id"), 1), "a spine shorter than lamArity skips nothing");
        // konst = fun α β a b => a
        int k = sigd(f, "konst");
        assertEquals(4, Facts.lamArity(f.m, k));
        assertEquals(0b1011L, Facts.unreadMask(f.m, f.cid("konst"), 4));
        assertEquals(2, Facts.shapeOperand(f.m, k));
        // Nat.add reads both parameters; its value is not a pick
        int add = sigd(f, "Nat.add");
        assertEquals(2, Facts.lamArity(f.m, add));
        assertEquals(0L, Facts.unreadMask(f.m, f.cid("Nat.add"), 2));
        assertEquals(Layout.SHAPE_NONE, Facts.shape(f.m, add));
        // a constant without a value: lamArity 0
        assertEquals(0, Facts.lamArity(f.m, sigd(f, "Nat.succ")));
        assertEquals(0L, Facts.unreadMask(f.m, f.cid("Nat.succ"), 1));
        f.close();
    }

    @Test
    void oneRecordPerConstantPublishedOnce() {
        Fixture f = fixture();
        FactStore fs = f.engine.facts;
        assertEquals(0, fs.indexOf(f.cid("Eq")));
        int a = sigd(f, "Eq");
        long published = fs.publishedRecords();
        long ticks = f.m.ticks();
        assertEquals(a, sigd(f, "Eq"), "the second request is answered inline by the published index");
        assertEquals(a, Facts.facts(f.m, f.cid("Eq")));
        assertEquals(ticks, f.m.ticks());
        assertEquals(published, fs.publishedRecords());
        // a second session of the same engine sees the record and does not derive it again
        MachineSession other = (MachineSession) f.engine.newSession(f.ctx);
        other.m.sw = f.m.sw;
        assertEquals(a, Facts.facts(other.m, f.cid("Eq")));
        // a publication that finds a record keeps the first one; equal words are not a difference
        int len = fs.lengthOf(a);
        int[] copy = new int[len];
        System.arraycopy(fs.chunkOf(a), fs.offsetOf(a), copy, 0, len);
        assertEquals(a, fs.publish(f.cid("Eq"), copy, len));
        assertEquals(1, fs.lostRaces());
        assertEquals(0, fs.lostDifferent());
        copy[Layout.F_READS] ^= 4;
        assertEquals(a, fs.publish(f.cid("Eq"), copy, len));
        assertEquals(1, fs.lostDifferent(), "two complete records with different words are counted");
        f.close();
    }

    @Test
    void recordsObeyThePublicationRule() {
        Fixture f = fixture();
        for (String name : new String[] {"Eq", "Eq.refl", "Eq.rec", "Nat.rec", "Prod.mk", "Exists.intro", "Sig.mk", "List.cons",
            "List.rec", "Quot.lift", "Quot.ind", "id", "konst", "c1", "hr", "useFam"}) {
            int idx = sigd(f, name);
            assertTrue(Facts.usable(f.m, idx), name);
            int arity = Facts.arity(f.m, idx);
            int n = Math.min(arity, Layout.FACT_POSITIONS);
            long manifest = Facts.manifest(f.m, idx);
            for (int i = 0; i < n; i++) {
                int ref = Facts.word(f.m, idx, Layout.F_SLVL + i);
                boolean bit = (manifest >>> i & 1) != 0;
                assertEquals(bit, ref != Layout.LREF_UNKNOWN, name + " slvl " + i);
                if (bit) {
                    // a structural encoding inside the record: no level id of any session
                    assertEquals(Layout.LREF_POOL, ref & Layout.LREF_TAG_MASK, name);
                    int at = ref & Layout.LREF_PAYLOAD_MASK;
                    assertTrue(at >= Layout.F_SLVL + 3 * n && at < f.engine.facts.lengthOf(idx), name);
                }
            }
        }
        // the levels of a record are re-interned per session: after a reset the same record gives the same level
        int eq = Facts.facts(f.m, f.cid("Eq"));
        int before = Facts.slvl(f.m, eq, 0);
        assertEquals(f.level("(succ u)"), before);
        f.close();
    }

    @Test
    void aBudgetThatRunsOutPublishesNoFacts() {
        Fixture f = fixture();
        // the declaration's remaining budget caps the segment: the derivation cannot finish
        f.m.beginDecl(Integer.MAX_VALUE, -1, 3L, Long.MAX_VALUE);
        int r = f.m.drive(Facts.sigd(f.m, f.cid("Exists.intro"), 0));
        // capped by the enclosing budget, which is now empty: the declaration's own exhaustion (ISA 1.4)
        assertEquals(Op.ABORT, r);
        f.m.abandon();
        f.m.beginDecl(Integer.MAX_VALUE, -1, Op.FUEL_UNLIMITED, Long.MAX_VALUE);
        assertEquals(0, f.engine.facts.indexOf(f.cid("Exists.intro")), "nothing is published by a cut derivation");
        int idx = sigd(f, "Exists.intro");
        assertTrue(Facts.usable(f.m, idx));
        f.close();
    }

    @Test
    void offInV0AndInStrictMode() {
        Fixture f = Fixture.prelude();
        f.seal();
        assertEquals(0, f.run(Facts.sigd(f.m, f.cid("Eq"), 0)));
        assertEquals(0, Facts.facts(f.m, f.cid("Eq")));
        assertEquals(0L, Facts.propMask(f.m, f.eval(f.term("And.intro"))));
        assertEquals(0L, Facts.unreadMask(f.m, f.cid("id"), 2));
        assertEquals(Layout.TRI_UNKNOWN, Facts.staticProof(f.m, f.eval(f.term("((@ Eq.refl 1) Nat Nat.zero)"))));
        assertEquals(Layout.TRI_UNKNOWN, Facts.staticProof(f.m, f.eval(f.term("Type"))));
        assertEquals(0, f.engine.facts.publishedRecords());
        f.switches("facts");
        assertNotEquals(0, f.run(Facts.sigd(f.m, f.cid("Eq"), 0)));
        f.strict();
        assertEquals(0, Facts.facts(f.m, f.cid("Eq")), "strict mode reads no shared fact");
        assertEquals(0L, Facts.propMask(f.m, f.eval(f.term("And.intro"))));
        f.close();
    }

    @Test
    void declDerivesTheRecordOfAnAcceptedDeclaration() {
        Fixture f = fixture();
        Budget budget = new Budget(50_000_000L, 1L << 30, Integer.MAX_VALUE);
        assertEquals(0, f.engine.facts.indexOf(f.cid("konst")));
        assertEquals(Outcome.ACCEPT, f.session.check(f.cid("konst"), budget));
        int idx = f.engine.facts.indexOf(f.cid("konst"));
        assertNotEquals(0, idx, "on ACCEPT the record is derived (ISA 4.5.6)");
        assertFalse((Facts.word(f.m, idx, Layout.F_FLAGS) & Layout.F_NONE) != 0);
        assertTrue(f.m.idle());
        f.close();
    }
}
