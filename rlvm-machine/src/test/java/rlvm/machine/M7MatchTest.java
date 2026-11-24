package rlvm.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * MATCH (ISA 4.4.4, B5): per-form rows, and the acceptance property "MATCH = build + CONV": whenever MATCH
 * answers T for a pattern p over g and a value v, the value built by {@code EVAL(p, g)} is convertible
 * with v ({@code CONV} of the complete procedure says T). MATCH may answer "unknown" (F) for a
 * convertible pair, never T for a pair that is not convertible.
 */
@Tag("fast")
class M7MatchTest {

    /** The fixture's context: n m : Nat, f : Nat -> Nat. */
    private static Fixture ctx() {
        Fixture f = Fixture.prelude();
        f.fvar("n", "Nat");
        f.fvar("m", "Nat");
        f.fvar("f", "(-> Nat Nat)");
        return f;
    }

    private static final String[] PATTERNS = {
        "((@ Eq 1) Nat n n)",                               // constant head, variables
        "((@ Eq 1) Nat n m)",
        "((@ Eq 1) Nat (f n) (f m))",                       // variable heads bound to a neutral
        "((@ Eq 1) Nat (Nat.succ n) m)",                    // Nat.succ against an offset cell (ISA 2.9)
        "((@ Eq 1) Nat (Nat.add n m) (lit 3))",             // nested constant application, a numeral
        "((@ Eq (succ (max 1 2))) (sort (max 1 2)) Nat Nat)", // levels: equivalent, not identical
        "((@ Eq (succ (max 2 1))) (sort (max 2 1)) Nat Nat)",
        "((@ Prod 0 0) Nat Bool)",
        "((@ Eq 2) (-> Nat Type) (fun (x : Nat) Nat) (fun (x : Nat) Nat))",   // closed binders: closure identity
        "((@ Eq 1) (-> Nat Nat) (fun (x : Nat) (f x)) f)",  // an open binder: its frame against g
        "(Sig (fun (x : Nat) ((@ Eq 1) Nat x n)))",
        "((@ Eq 1) Nat n (let (x : Nat := n) x))",          // a let: outside the pattern language
    };

    @Test
    void matchIsBuildPlusConv() {
        Fixture f = ctx();
        Machine m = f.m;
        int n = PATTERNS.length;
        int[] pat = new int[n];
        int[] val = new int[n];
        for (int i = 0; i < n; i++) {
            pat[i] = f.term(PATTERNS[i]);
            val[i] = f.force(f.eval(pat[i]));
        }
        int ownT = 0;
        int foreignT = 0;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                int r = Match.matchIn(m, m.es, 0, 3, pat[i], 0, val[j]);
                assertTrue(r == Layout.T || r == Layout.F, "MATCH answers T or unknown");
                assertEquals(0, m.xp, "MATCH leaves no scratch");
                if (r == Layout.T) {
                    // the acceptance property: T implies convertible with the built value
                    assertEquals(Layout.T, f.conv(val[i], val[j]), PATTERNS[i] + " matched the value of " + PATTERNS[j]);
                    if (i == j) {
                        ownT++;
                    } else {
                        foreignT++;
                    }
                }
            }
        }
        // every pattern of the language matches the value built from it; the let is "unknown"
        assertEquals(n - 1, ownT);
        assertEquals(Layout.F, Match.matchIn(m, m.es, 0, 3, pat[n - 1], 0, val[n - 1]));
        // the two level-equivalent types match each other although their values are different records
        assertTrue(val[5] != val[6]);
        assertEquals(Layout.T, Match.matchIn(m, m.es, 0, 3, pat[5], 0, val[6]));
        assertEquals(Layout.T, Match.matchIn(m, m.es, 0, 3, pat[6], 0, val[5]));
        assertEquals(2, foreignT);
    }

    @Test
    void rowsOfTheForms() {
        Fixture f = ctx();
        Machine m = f.m;
        int nat = f.eval(f.term("Nat"));
        int nv = m.es[0];
        int mv = m.es[1];
        // variable j: g_j == v and g_j != HOLE
        assertEquals(Layout.T, Match.matchIn(m, m.es, 0, 3, f.term("n"), 0, nv));
        assertEquals(Layout.F, Match.matchIn(m, m.es, 0, 3, f.term("n"), 0, mv));
        int saved = m.es[0];
        m.es[0] = Layout.HOLE;
        assertEquals(Layout.F, Match.matchIn(m, m.es, 0, 3, f.term("n"), 0, Layout.HOLE), "a HOLE never matches");
        m.es[0] = saved;
        // sort: v is SORT and LVLEQ
        assertEquals(Layout.T, Match.matchIn(m, m.es, 0, 3, f.term("(sort (max 1 1))"), 0, f.eval(f.term("Type"))));
        assertEquals(Layout.F, Match.matchIn(m, m.es, 0, 3, f.term("Type"), 0, f.eval(f.term("Prop"))));
        assertEquals(Layout.F, Match.matchIn(m, m.es, 0, 3, f.term("Type"), 0, nat));
        // constant: the same row, equivalent levels
        assertEquals(Layout.T, Match.matchIn(m, m.es, 0, 3, f.term("Nat"), 0, nat));
        assertEquals(Layout.F, Match.matchIn(m, m.es, 0, 3, f.term("Bool"), 0, nat));
        // application: the same length
        int eqN = f.eval(f.term("((@ Eq 1) Nat n)"));
        assertEquals(Layout.F, Match.matchIn(m, m.es, 0, 3, f.term("((@ Eq 1) Nat n n)"), 0, eqN), "the value is shorter");
        assertEquals(Layout.F, Match.matchIn(m, m.es, 0, 3, f.term("((@ Eq 1) Nat)"), 0, eqN), "the pattern is shorter");
        assertEquals(Layout.T, Match.matchIn(m, m.es, 0, 3, f.term("((@ Eq 1) Nat n)"), 0, eqN));
        // a definitional alias is convertible but not matched: unknown, the caller builds and converts (B5)
        int alias = f.eval(f.term("((@ Eq 1) NatAlias n n)"));
        int plain = f.eval(f.term("((@ Eq 1) Nat n n)"));
        assertEquals(Layout.F, Match.matchIn(m, m.es, 0, 3, f.term("((@ Eq 1) Nat n n)"), 0, alias));
        assertEquals(Layout.T, f.conv(alias, plain));
        // the frozen entry point with g in scratch
        int xb = m.xp;
        m.ensureScratch(3);
        System.arraycopy(m.es, 0, m.X, xb, 3);
        m.xp = xb + 3;
        assertEquals(Layout.T, Match.match(m, f.term("((@ Eq 1) Nat n m)"), xb, 3, 0, f.eval(f.term("((@ Eq 1) Nat n m)"))));
        assertEquals(xb + 3, m.xp);
        m.xp = xb;
    }

    @Test
    void theBudgetIsSixtyFourPatternNodes() {
        Fixture f = ctx();
        Machine m = f.m;
        // g n (g n (g n ...)): 40 nested applications = 121 pattern nodes; identical value, yet unknown
        StringBuilder small = new StringBuilder("n");
        for (int i = 0; i < 10; i++) {
            small = new StringBuilder("(g n ").append(small).append(')');
        }
        StringBuilder big = new StringBuilder("n");
        for (int i = 0; i < 40; i++) {
            big = new StringBuilder("(g n ").append(big).append(')');
        }
        int ps = f.term(small.toString());
        int pb = f.term(big.toString());
        assertEquals(Layout.T, Match.matchIn(m, m.es, 0, 3, ps, 0, f.eval(ps)));
        assertEquals(Layout.F, Match.matchIn(m, m.es, 0, 3, pb, 0, f.eval(pb)), "more than Op.MATCH_NODES nodes: unknown");
        assertEquals(0, m.xp);
    }
}
