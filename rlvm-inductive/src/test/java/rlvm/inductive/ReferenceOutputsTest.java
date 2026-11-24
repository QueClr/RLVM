package rlvm.inductive;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Test;

/**
 * The reference outputs of {@code docs/research/03-kernel-declarations.md} section 6.14 and the
 * hand-built fixtures of the work package: Nat, List, Eq, Acc, a structure, a mutual pair, nested
 * types (Tree, Rose). Expected recursor types and rules are written with named binders and compared
 * structurally ({@code TermOps.eqv}).
 */
class ReferenceOutputsTest {

    static void assertTerm(Env e, String expected, int actual) {
        int want = e.expect(expected);
        if (!e.tops.eqv(want, actual)) {
            fail("term differs\n expected: " + e.show(want) + "\n actual:   " + e.show(actual));
        }
    }

    static String[] levelParams(Env e, int list) {
        int n = e.levels.length(list);
        String[] r = new String[n];
        for (int i = 0; i < n; i++) {
            r[i] = e.names.toString(e.levels.get(list, i));
        }
        return r;
    }

    static String ctorName(Env e, int cid) {
        return e.names.toString(e.decls.name(cid));
    }

    @Test
    void nat() {
        Env e = new Env();
        Env.Block nat = Fixtures.nat(e);
        e.freeze();
        GeneratedBlock g = e.admit(nat);

        assertEquals(0, g.numNested);
        assertTrue(g.isRec);
        assertFalse(g.isReflexive);
        assertFalse(g.elimOnlyAtZero);
        assertFalse(g.k);
        assertArrayEquals(new int[] {0}, java.util.Arrays.copyOf(g.typeNumIndices, 1));
        assertEquals(0, g.ctorNumFields[0]);
        assertEquals(1, g.ctorNumFields[1]);
        assertEquals(0, g.ctorIndex[0]);
        assertEquals(1, g.ctorIndex[1]);
        assertEquals(1, g.recCount);
        assertEquals(1, g.recNumMotives);
        assertEquals(2, g.recNumMinors);
        assertEquals(0, g.recNumIndices[0]);
        assertArrayEquals(new String[] {"u"}, levelParams(e, g.recLevelParams));
        assertEquals("Nat", e.names.toString(g.recNamePrefix[0]));
        assertEquals("rec", g.recNameLast[0]);
        assertEquals(nat.in.typeCid[0], g.recMajorInduct[0]);

        // 03 section 6.14, in de Bruijn form exactly as printed there
        assertTerm(e, "(Nat → Sort u) → (#0 Nat.zero) → (Nat → (#2 #0) → #3 (Nat.succ #1)) → Nat → #3 #0", g.recType[0]);
        String pre = "(motive : Nat → Sort u) (zero : motive Nat.zero) (succ : ∀ (n : Nat), motive n → motive (Nat.succ n))";
        assertTerm(e, "∀ " + pre + " (t : Nat), motive t", g.recType[0]);
        assertEquals(2, g.recRuleCount[0]);
        assertEquals("Nat.zero", ctorName(e, g.recRuleCtor[0][0]));
        assertEquals(0, g.recRuleFields[0][0]);
        assertTerm(e, "λ " + pre + ", zero", g.recRuleRhs[0][0]);
        assertEquals("Nat.succ", ctorName(e, g.recRuleCtor[0][1]));
        assertEquals(1, g.recRuleFields[0][1]);
        assertTerm(e, "λ " + pre + " (n : Nat), succ n (Nat.rec.{u} motive zero succ n)", g.recRuleRhs[0][1]);
    }

    @Test
    void list() {
        Env e = new Env();
        Env.Block list = Fixtures.list(e);
        e.freeze();
        GeneratedBlock g = e.admit(list);

        assertTrue(g.isRec);
        assertFalse(g.k);
        assertArrayEquals(new String[] {"u_1", "u"}, levelParams(e, g.recLevelParams));
        String pre = "(α : Type u) (motive : List.{u} α → Sort u_1) (nil : motive (List.nil.{u} α)) "
                + "(cons : ∀ (head : α) (tail : List.{u} α), motive tail → motive (List.cons.{u} α head tail))";
        assertTerm(e, "∀ " + pre + " (t : List.{u} α), motive t", g.recType[0]);
        assertTerm(e, "λ " + pre + ", nil", g.recRuleRhs[0][0]);
        assertTerm(e, "λ " + pre + " (head : α) (tail : List.{u} α), cons head tail (List.rec.{u_1,u} α motive nil cons tail)",
                g.recRuleRhs[0][1]);
        assertEquals(2, g.recRuleFields[0][1]);
    }

    @Test
    void eq() {
        Env e = new Env();
        Env.Block eq = Fixtures.eq(e);
        e.freeze();
        GeneratedBlock g = e.admit(eq);

        assertFalse(g.isRec);
        assertTrue(g.k);
        assertFalse(g.elimOnlyAtZero);
        assertEquals(1, g.typeNumIndices[0]);
        assertEquals(1, g.recNumIndices[0]);
        assertArrayEquals(new String[] {"u", "u_1"}, levelParams(e, g.recLevelParams));
        String pre = "(α : Sort u_1) (a : α) (motive : ∀ (b : α), Eq.{u_1} α a b → Sort u) (refl : motive a (Eq.refl.{u_1} α a))";
        assertTerm(e, "∀ " + pre + " (b : α) (t : Eq.{u_1} α a b), motive b t", g.recType[0]);
        assertEquals(0, g.recRuleFields[0][0]);
        assertTerm(e, "λ " + pre + ", refl", g.recRuleRhs[0][0]);
    }

    @Test
    void acc() {
        Env e = new Env();
        Env.Block acc = Fixtures.acc(e);
        e.freeze();
        GeneratedBlock g = e.admit(acc);

        assertTrue(g.isRec);
        assertTrue(g.isReflexive);
        assertFalse(g.k);
        assertFalse(g.elimOnlyAtZero);
        assertArrayEquals(new String[] {"u_1", "u"}, levelParams(e, g.recLevelParams));
        String pre = "(α : Sort u) (r : α → α → Prop) (motive : ∀ (a : α), Acc.{u} α r a → Sort u_1) "
                + "(intro : ∀ (x : α) (h : ∀ (y : α), r y x → Acc.{u} α r y), "
                + "(∀ (y : α) (a : r y x), motive y (h y a)) → motive x (Acc.intro.{u} α r x h))";
        assertTerm(e, "∀ " + pre + " (a : α) (t : Acc.{u} α r a), motive a t", g.recType[0]);
        assertEquals(2, g.recRuleFields[0][0]);
        assertTerm(e, "λ " + pre + " (x : α) (h : ∀ (y : α), r y x → Acc.{u} α r y), "
                + "intro x h (λ (y : α) (a : r y x), Acc.rec.{u_1,u} α r motive intro y (h y a))", g.recRuleRhs[0][0]);
    }

    @Test
    void structure() {
        Env e = new Env();
        Env.Block prod = Fixtures.prod(e);
        Env.Block and = Fixtures.and(e);
        e.freeze();
        GeneratedBlock g = e.admit(prod);
        assertFalse(g.isRec);
        assertFalse(g.k);
        assertArrayEquals(new String[] {"u_1", "u", "v"}, levelParams(e, g.recLevelParams));
        String pre = "(α : Type u) (β : Type v) (motive : Prod.{u,v} α β → Sort u_1) "
                + "(mk : ∀ (fst : α) (snd : β), motive (Prod.mk.{u,v} α β fst snd))";
        assertTerm(e, "∀ " + pre + " (t : Prod.{u,v} α β), motive t", g.recType[0]);
        assertTerm(e, "λ " + pre + " (fst : α) (snd : β), mk fst snd", g.recRuleRhs[0][0]);
        assertEquals(2, g.ctorNumFields[0]);

        // And: a Prop with one constructor whose fields are proofs eliminates into every universe; no K (fields)
        GeneratedBlock a = e.admit(and);
        assertFalse(a.elimOnlyAtZero);
        assertFalse(a.k);
        assertArrayEquals(new String[] {"u"}, levelParams(e, a.recLevelParams));
        String apre = "(a b : Prop) (motive : And a b → Sort u) (intro : ∀ (left : a) (right : b), motive (And.intro a b left right))";
        assertTerm(e, "∀ " + apre + " (t : And a b), motive t", a.recType[0]);
    }

    @Test
    void mutualPredicates() {
        Env e = new Env();
        Env.Block nat = Fixtures.nat(e);
        Env.Block eo = Fixtures.evenOdd(e);
        e.freeze();
        e.admit(nat);
        GeneratedBlock g = e.admit(eo);

        assertEquals(2, g.recCount);
        assertEquals(2, g.recNumMotives);
        assertEquals(3, g.recNumMinors);
        assertFalse(g.k);
        assertTrue(g.elimOnlyAtZero);
        assertTrue(g.isRec);
        assertArrayEquals(new String[] {}, levelParams(e, g.recLevelParams));
        assertEquals(1, g.recNumIndices[0]);
        assertEquals(1, g.recNumIndices[1]);
        String pre = "(motive_1 : ∀ (a : Nat), EvenT a → Prop) (motive_2 : ∀ (a : Nat), OddT a → Prop) "
                + "(zero : motive_1 Nat.zero EvenT.zero) "
                + "(succ : ∀ (n : Nat) (a : OddT n), motive_2 n a → motive_1 (Nat.succ n) (EvenT.succ n a)) "
                + "(succ' : ∀ (n : Nat) (a : EvenT n), motive_1 n a → motive_2 (Nat.succ n) (OddT.succ n a))";
        assertTerm(e, "∀ " + pre + " (a : Nat) (t : EvenT a), motive_1 a t", g.recType[0]);
        assertTerm(e, "∀ " + pre + " (a : Nat) (t : OddT a), motive_2 a t", g.recType[1]);
        assertEquals(2, g.recRuleCount[0]);
        assertEquals(1, g.recRuleCount[1]);
        assertTerm(e, "λ " + pre + ", zero", g.recRuleRhs[0][0]);
        assertTerm(e, "λ " + pre + " (n : Nat) (a : OddT n), succ n a (OddT.rec motive_1 motive_2 zero succ succ' n a)",
                g.recRuleRhs[0][1]);
        assertTerm(e, "λ " + pre + " (n : Nat) (a : EvenT n), succ' n a (EvenT.rec motive_1 motive_2 zero succ succ' n a)",
                g.recRuleRhs[1][0]);
        assertEquals("OddT", e.names.toString(g.recNamePrefix[1]));
    }

    static final String TREE_PRE = "(α : Type u) (motive_1 : Tree.{u} α → Sort u_1) (motive_2 : List.{u} (Tree.{u} α) → Sort u_1) "
            + "(node : ∀ (a : α) (as : List.{u} (Tree.{u} α)), motive_2 as → motive_1 (Tree.node.{u} α a as)) "
            + "(nil : motive_2 (List.nil.{u} (Tree.{u} α))) "
            + "(cons : ∀ (head : Tree.{u} α) (tail : List.{u} (Tree.{u} α)), motive_1 head → motive_2 tail → "
            + "motive_2 (List.cons.{u} (Tree.{u} α) head tail))";

    @Test
    void nestedTree() {
        Env e = new Env();
        Env.Block list = Fixtures.list(e);
        Env.Block tree = Fixtures.tree(e);
        e.freeze();
        e.admit(list);
        GeneratedBlock g = e.admit(tree);

        assertEquals(1, g.numNested);
        assertTrue(g.isRec);
        assertFalse(g.k);
        assertEquals(2, g.recCount);
        assertEquals(2, g.recNumMotives);
        assertEquals(3, g.recNumMinors);
        assertArrayEquals(new String[] {"u_1", "u"}, levelParams(e, g.recLevelParams));
        assertEquals("Tree", e.names.toString(g.recNamePrefix[0]));
        assertEquals("rec", g.recNameLast[0]);
        assertEquals("Tree", e.names.toString(g.recNamePrefix[1]));
        assertEquals("rec_1", g.recNameLast[1]);
        assertEquals(tree.in.typeCid[0], g.recMajorInduct[0]);
        assertEquals(list.in.typeCid[0], g.recMajorInduct[1]);
        assertEquals(2, g.ctorNumFields[0]);

        assertTerm(e, "∀ " + TREE_PRE + " (t : Tree.{u} α), motive_1 t", g.recType[0]);
        assertTerm(e, "∀ " + TREE_PRE + " (t : List.{u} (Tree.{u} α)), motive_2 t", g.recType[1]);

        assertEquals(1, g.recRuleCount[0]);
        assertEquals("Tree.node", ctorName(e, g.recRuleCtor[0][0]));
        assertEquals(2, g.recRuleFields[0][0]);
        assertTerm(e, "λ " + TREE_PRE + " (a : α) (as : List.{u} (Tree.{u} α)), "
                + "node a as (Tree.rec_1.{u_1,u} α motive_1 motive_2 node nil cons as)", g.recRuleRhs[0][0]);

        // rules of the auxiliary recursor name the container's constructors (trap T7)
        assertEquals(2, g.recRuleCount[1]);
        assertEquals("List.nil", ctorName(e, g.recRuleCtor[1][0]));
        assertEquals(0, g.recRuleFields[1][0]);
        assertTerm(e, "λ " + TREE_PRE + ", nil", g.recRuleRhs[1][0]);
        assertEquals("List.cons", ctorName(e, g.recRuleCtor[1][1]));
        assertEquals(2, g.recRuleFields[1][1]);
        assertTerm(e, "λ " + TREE_PRE + " (head : Tree.{u} α) (tail : List.{u} (Tree.{u} α)), cons head tail "
                + "(Tree.rec.{u_1,u} α motive_1 motive_2 node nil cons head) "
                + "(Tree.rec_1.{u_1,u} α motive_1 motive_2 node nil cons tail)", g.recRuleRhs[1][1]);
    }

    @Test
    void nestedRose() {
        Env e = new Env();
        Env.Block list = Fixtures.list(e);
        Env.Block array = Fixtures.array(e);
        Env.Block rose = Fixtures.rose(e);
        e.freeze();
        e.admit(list);
        e.admit(array);
        GeneratedBlock g = e.admit(rose);

        // Array (Rose α) is found first and copied; its constructor mentions List (Rose α), found next
        assertEquals(2, g.numNested);
        assertEquals(3, g.recCount);
        assertEquals(3, g.recNumMotives);
        assertEquals(4, g.recNumMinors);
        assertEquals("rec_1", g.recNameLast[1]);
        assertEquals("rec_2", g.recNameLast[2]);
        assertEquals(array.in.typeCid[0], g.recMajorInduct[1]);
        assertEquals(list.in.typeCid[0], g.recMajorInduct[2]);
        String r = "Rose.{u} α";
        String pre = "(α : Type u) (motive_1 : " + r + " → Sort u_1) (motive_2 : Array.{u} (" + r + ") → Sort u_1) "
                + "(motive_3 : List.{u} (" + r + ") → Sort u_1) "
                + "(node : ∀ (a : α) (as : Array.{u} (" + r + ")), motive_2 as → motive_1 (Rose.node.{u} α a as)) "
                + "(mk : ∀ (toList : List.{u} (" + r + ")), motive_3 toList → motive_2 (Array.mk.{u} (" + r + ") toList)) "
                + "(nil : motive_3 (List.nil.{u} (" + r + "))) "
                + "(cons : ∀ (head : " + r + ") (tail : List.{u} (" + r + ")), motive_1 head → motive_3 tail → "
                + "motive_3 (List.cons.{u} (" + r + ") head tail))";
        assertTerm(e, "∀ " + pre + " (t : " + r + "), motive_1 t", g.recType[0]);
        assertTerm(e, "∀ " + pre + " (t : Array.{u} (" + r + ")), motive_2 t", g.recType[1]);
        assertTerm(e, "∀ " + pre + " (t : List.{u} (" + r + ")), motive_3 t", g.recType[2]);
        String recs = " α motive_1 motive_2 motive_3 node mk nil cons ";
        assertTerm(e, "λ " + pre + " (a : α) (as : Array.{u} (" + r + ")), node a as (Rose.rec_1.{u_1,u}" + recs + "as)",
                g.recRuleRhs[0][0]);
        assertEquals("Array.mk", ctorName(e, g.recRuleCtor[1][0]));
        assertTerm(e, "λ " + pre + " (toList : List.{u} (" + r + ")), mk toList (Rose.rec_2.{u_1,u}" + recs + "toList)",
                g.recRuleRhs[1][0]);
        assertTerm(e, "λ " + pre + ", nil", g.recRuleRhs[2][0]);
        assertTerm(e, "λ " + pre + " (head : " + r + ") (tail : List.{u} (" + r + ")), cons head tail "
                + "(Rose.rec.{u_1,u}" + recs + "head) (Rose.rec_2.{u_1,u}" + recs + "tail)", g.recRuleRhs[2][1]);
    }
}
